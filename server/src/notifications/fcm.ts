import jwt from 'jsonwebtoken';
import { env } from '../env.js';

/**
 * Push delivery, through Firebase Cloud Messaging's HTTP v1 API (§8, §12).
 *
 * ## Why this file has no dependency
 *
 * `firebase-admin` would do this job, and it would arrive with gRPC, a protobuf
 * runtime and a tree of transitive packages for what is, from here, two HTTP
 * requests: exchange a signed JWT for an access token, then POST one message. The
 * JWT is signed with `jsonwebtoken`, which this service already uses to verify
 * reader tokens, and `fetch` is in the runtime. A deployment that does not want
 * push pays nothing for it.
 *
 * ## Why data-only messages
 *
 * The message carries no `notification` block. A notification message would be
 * drawn by the system while the app is in the background, which means it would be
 * drawn WITHOUT the app's own rules: the reader's master switch, a channel's mute
 * and the "already announced" watermark all live in the client. Sending data
 * only means the app always gets to decide, in one place, whether and how to show
 * something — which is what makes "no duplicate notifications" (the requirement
 * that produced this feature) true rather than hopeful.
 *
 * The cost of that choice is honest and worth stating: with the app force-stopped
 * or the device in a long Doze window, a data message can be DELAYED. It is not
 * lost — and the periodic catch-up check the app already runs is what covers the
 * gap, which is why that job stays.
 *
 * ## What a failure means
 *
 * Firebase answers per message. Two answers matter:
 *
 *  * `UNREGISTERED` / a 404 — this token is gone (the app was uninstalled, the
 *    data was cleared, the token rotated). The row is deleted, so the next
 *    publish does not pay for it again. Nothing else is done: an invalid token is
 *    not an error in this product, it is a phone that stopped listening.
 *  * A 401 — the access token expired between being minted and being used. The
 *    token is refreshed once and the message is retried.
 */

/** One message, addressed to one install. */
export interface PushMessage {
  readonly token: string;
  /** The heading: a channel's name. */
  readonly title: string;
  /** The line under it: the first line of what was posted. */
  readonly body: string;
  /**
   * What the client needs to open the right screen, and to decide whether this
   * is news. Every value is a string: FCM's data map is strings only, and a
   * number that arrives as one is a number the client has to parse anyway.
   */
  readonly data: Readonly<Record<string, string>>;
}

/** What a batch of sends produced. */
export interface PushResult {
  readonly sent: number;
  /** Tokens Firebase says no longer exist. Their rows are removed. */
  readonly dead: readonly string[];
  /** Everything else that failed, counted rather than itemised. */
  readonly failed: number;
}

/** The shape the notification service needs from a sender. */
export interface PushSender {
  /** False when no service account is configured, so callers can skip the query. */
  readonly configured: boolean;
  send(messages: readonly PushMessage[]): Promise<PushResult>;
}

/**
 * The sender used when push is not configured.
 *
 * `send` resolves rather than throws: a deployment without a service account
 * still publishes posts, and an exception here would fail a publish that has
 * already committed. It reports zero sent, which is the truth.
 */
export class UnconfiguredPushSender implements PushSender {
  readonly configured = false;

  async send(): Promise<PushResult> {
    return { sent: 0, dead: [], failed: 0 };
  }
}

/** What a service account file gives us, as far as this file is concerned. */
export interface ServiceAccount {
  readonly projectId: string;
  readonly clientEmail: string;
  readonly privateKey: string;
}

const TOKEN_ENDPOINT = 'https://oauth2.googleapis.com/token';
const FCM_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

/** How many sends may be in flight at once. */
const SEND_CONCURRENCY = 8;

/** How long a minted access token is reused. Google issues them for an hour. */
const TOKEN_LIFETIME_MS = 55 * 60 * 1000;

/**
 * Read the service account out of the environment.
 *
 * Two spellings are accepted, because both are things people actually paste:
 * the JSON as downloaded, and the same JSON base64-encoded (which survives every
 * dashboard's whitespace handling). Newlines inside `private_key` are repaired,
 * because a JSON blob that has been through a YAML file or a shell variable
 * often arrives with the escapes intact.
 *
 * Null rather than a thrown error when it is absent: push is optional, and a
 * deployment that has not set this must boot and serve everything else.
 */
export function readServiceAccount(raw: string | undefined): ServiceAccount | null {
  const value = (raw ?? '').trim();
  if (value === '') return null;

  const parsed = parseMaybeBase64(value);
  if (!parsed) {
    console.error(
      '[push] FIREBASE_SERVICE_ACCOUNT_JSON is not readable JSON. Push is off; everything else is unaffected.'
    );
    return null;
  }

  const projectId = typeof parsed.project_id === 'string' ? parsed.project_id : '';
  const clientEmail = typeof parsed.client_email === 'string' ? parsed.client_email : '';
  const rawKey = typeof parsed.private_key === 'string' ? parsed.private_key : '';

  if (projectId === '' || clientEmail === '' || rawKey === '') {
    console.error(
      '[push] FIREBASE_SERVICE_ACCOUNT_JSON is missing project_id, client_email or private_key. Push is off.'
    );
    return null;
  }

  // A pasted key whose newlines were escaped stays one unusable line otherwise,
  // and the failure it produces ("DECODER routines::unsupported") says nothing
  // about the cause.
  const privateKey = rawKey.includes('\\n') ? rawKey.replace(/\\n/g, '\n') : rawKey;

  return { projectId, clientEmail, privateKey };
}

function parseMaybeBase64(value: string): Record<string, unknown> | null {
  if (value.startsWith('{')) {
    try {
      return JSON.parse(value) as Record<string, unknown>;
    } catch {
      return null;
    }
  }

  try {
    const decoded = Buffer.from(value, 'base64').toString('utf8');
    if (!decoded.trim().startsWith('{')) return null;
    return JSON.parse(decoded) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/**
 * The FCM-backed sender.
 *
 * One access token is cached for the process: minting one is a network round trip
 * to Google, and a channel with fifty followers publishing one post is one token
 * and fifty sends, not fifty-one round trips to the token endpoint.
 */
class FcmPushSender implements PushSender {
  readonly configured = true;

  private readonly account: ServiceAccount;
  private accessToken: string | null = null;
  private accessTokenExpiresAt = 0;

  constructor(account: ServiceAccount) {
    this.account = account;
  }

  async send(messages: readonly PushMessage[]): Promise<PushResult> {
    if (messages.length === 0) return { sent: 0, dead: [], failed: 0 };

    const dead: string[] = [];
    let sent = 0;
    let failed = 0;

    // A few at a time. FCM's v1 API takes one message per request, and firing a
    // thousand at once would open a thousand sockets on a small instance to send
    // something that is already bounded by the fan-out cap.
    for (let i = 0; i < messages.length; i += SEND_CONCURRENCY) {
      const chunk = messages.slice(i, i + SEND_CONCURRENCY);
      const results = await Promise.all(chunk.map((message) => this.sendOne(message)));

      for (const [index, outcome] of results.entries()) {
        if (outcome === 'sent') sent += 1;
        else if (outcome === 'dead') dead.push(chunk[index]!.token);
        else failed += 1;
      }
    }

    return { sent, dead, failed };
  }

  private async sendOne(message: PushMessage): Promise<'sent' | 'dead' | 'failed'> {
    const first = await this.attempt(message, await this.token());
    if (first === 'expired') {
      // The access token was refused. Mint a fresh one and try once more: a
      // second failure is a real failure, and looping would turn an outage into
      // a hot loop against Google.
      return normalise(await this.attempt(message, await this.token(true)));
    }
    return normalise(first);
  }

  private async attempt(
    message: PushMessage,
    accessToken: string | null
  ): Promise<string> {
    if (accessToken === null) return 'expired';

    const response = await fetch(
      `https://fcm.googleapis.com/v1/projects/${this.account.projectId}/messages:send`,
      {
        method: 'POST',
        headers: {
          authorization: `Bearer ${accessToken}`,
          'content-type': 'application/json',
        },
        body: JSON.stringify({
          message: {
            token: message.token,
            data: { ...message.data, title: message.title, body: message.body },
            android: {
              // High priority so a data message is delivered promptly rather
              // than with the next batch of deferred work.
              priority: 'high',
              // The client's own notification channel; ignored by data-only
              // messages, and kept because it makes the intent legible.
              ttl: '3600s',
            },
          },
        }),
      }
    );

    if (response.status === 401) return 'expired';
    if (response.ok) return 'sent';

    const detail = await response.text();

    // 404 and UNREGISTERED are the two shapes FCM uses for a token that is no
    // longer valid. Both mean the row should go.
    if (response.status === 404 || detail.includes('UNREGISTERED')) return 'dead';

    // A 400 for a malformed message is ours to fix, not the phone's — and a
    // delete on the strength of it would silently drop a working device.
    console.error(
      `[push] FCM refused a message (${response.status}): ${detail.slice(0, 200)}`
    );
    return 'failed';
  }

  /**
   * An access token, minted from the service account.
   *
   * Cached until shortly before it expires, so a fan-out is one token exchange
   * and the next publish inside the hour is none.
   */
  private async token(force = false): Promise<string | null> {
    const now = Date.now();
    if (!force && this.accessToken !== null && now < this.accessTokenExpiresAt) {
      return this.accessToken;
    }

    try {
      const assertion = jwt.sign(
        {
          iss: this.account.clientEmail,
          scope: FCM_SCOPE,
          aud: TOKEN_ENDPOINT,
        },
        this.account.privateKey,
        { algorithm: 'RS256', expiresIn: '1h' }
      );

      const response = await fetch(TOKEN_ENDPOINT, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
          assertion,
        }),
      });

      if (!response.ok) {
        console.error(
          `[push] could not mint an FCM access token (${response.status}): ${(
            await response.text()
          ).slice(0, 200)}`
        );
        return null;
      }

      const body = (await response.json()) as { access_token?: string };
      if (typeof body.access_token !== 'string' || body.access_token === '') {
        console.error('[push] the token endpoint answered without an access_token.');
        return null;
      }

      this.accessToken = body.access_token;
      this.accessTokenExpiresAt = now + TOKEN_LIFETIME_MS;
      return this.accessToken;
    } catch (err) {
      // A malformed private key lands here, and so does a network failure. Both
      // mean "no push right now", never a failed publish.
      console.error('[push] FCM sign-in failed:', (err as Error).message);
      return null;
    }
  }
}

function normalise(outcome: string): 'sent' | 'dead' | 'failed' {
  if (outcome === 'sent' || outcome === 'dead') return outcome;
  return 'failed';
}

let cached: PushSender | null = null;

/**
 * The sender this process should use.
 *
 * Cached because it holds an access token. Defaults to the unconfigured sender,
 * so a deployment without a service account still publishes — which is the same
 * arrangement media storage uses, and for the same reason: an optional capability
 * must be optional at runtime, not only in the documentation.
 */
export function createPushSender(serviceAccountJson: string | undefined = env.FIREBASE_SERVICE_ACCOUNT_JSON): PushSender {
  if (cached) return cached;

  const account = readServiceAccount(serviceAccountJson);
  cached = account ? new FcmPushSender(account) : new UnconfiguredPushSender();
  return cached;
}

/** Reset the cache. Tests only — production builds it once per process. */
export function resetPushSender(): void {
  cached = null;
}
