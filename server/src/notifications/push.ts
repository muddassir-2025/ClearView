import { env } from '../env.js';
import { firebaseConfigured, getFirebaseAdminApp } from '../auth/firebase.js';

/**
 * Push delivery (§17).
 *
 * An interface, plus two implementations, for the same reason the OTP provider
 * and the object store are interfaces: the rules that matter here are OURS —
 * who is notified, deduped, and never notified — and they must be testable
 * without a Google account, a network call, or a device.
 *
 * The distinction that shapes this module: **a notification is a ROW, push is a
 * DELIVERY ATTEMPT.** A deployment with FCM switched off still fills every
 * inbox; it simply does not ring anyone's phone. Treating push as the feature
 * would make `FCM_ENABLED=false` silently disable notifications, which is not
 * what the variable says and not what §17 asks for.
 */

export interface PushMessage {
  readonly title: string;
  readonly body: string;
  /** Free-form routing data for the client. Never contains private fields. */
  readonly data?: Readonly<Record<string, string>>;
}

export interface PushResult {
  /** Tokens the provider accepted. */
  readonly delivered: number;
  /** Tokens the provider says are permanently gone, to be disabled. */
  readonly staleTokens: readonly string[];
  /** True when nothing was even attempted (push is off or unconfigured). */
  readonly skipped: boolean;
}

export interface PushSender {
  readonly kind: 'fcm' | 'disabled';
  send(tokens: readonly string[], message: PushMessage): Promise<PushResult>;
}

/**
 * Sends through Firebase Cloud Messaging — the same service account Phone Auth
 * already uses, so there is no second credential to provision (§17).
 *
 * Per-token error codes are inspected rather than the batch result alone: FCM
 * reports a dead token as `messaging/registration-token-not-registered` inside
 * an otherwise successful response, and ignoring that is how a deployment ends
 * up sending to a growing list of uninstalled apps forever.
 */
export class FcmPushSender implements PushSender {
  readonly kind = 'fcm' as const;

  async send(tokens: readonly string[], message: PushMessage): Promise<PushResult> {
    if (tokens.length === 0) return { delivered: 0, staleTokens: [], skipped: true };

    if (!firebaseConfigured) {
      // Not a failure the caller can act on, and not a reason to fail the
      // request that produced the notification — the row is already written.
      console.error('[push] FCM is enabled but no Firebase service account is configured; skipping delivery');
      return { delivered: 0, staleTokens: [], skipped: true };
    }

    // Initialisation is lazy and can throw on a malformed key. Caught here so a
    // credential problem degrades to "no push" instead of failing a publish.
    let app;
    try {
      app = getFirebaseAdminApp();
    } catch (err) {
      console.error('[push] Firebase Admin could not initialise; skipping delivery:', (err as Error).message);
      return { delivered: 0, staleTokens: [], skipped: true };
    }

    const { getMessaging } = await import('firebase-admin/messaging');
    const response = await getMessaging(app).sendEachForMulticast({
      tokens: [...tokens],
      notification: { title: message.title, body: message.body },
      ...(message.data === undefined ? {} : { data: { ...message.data } }),
    });

    const staleTokens: string[] = [];
    response.responses.forEach((result, index) => {
      const token = tokens[index];
      if (token === undefined || result.success) return;
      const code = (result.error as { code?: string } | undefined)?.code ?? '';
      if (
        code === 'messaging/registration-token-not-registered' ||
        code === 'messaging/invalid-registration-token' ||
        code === 'messaging/invalid-argument'
      ) {
        staleTokens.push(token);
      }
    });

    return { delivered: response.successCount, staleTokens, skipped: false };
  }
}

/** Push is off. Every notification still becomes a row the inbox can show. */
export class DisabledPushSender implements PushSender {
  readonly kind = 'disabled' as const;

  async send(): Promise<PushResult> {
    return { delivered: 0, staleTokens: [], skipped: true };
  }
}

/**
 * The sender this deployment should use.
 *
 * `FCM_ENABLED=false` is an operator saying "do not push", and it is honoured.
 * An ENABLED value with no Firebase credentials is refused at boot in
 * production (see env.ts), so this only has to handle the disabled case.
 */
export function createPushSender(): PushSender {
  return env.FCM_ENABLED ? new FcmPushSender() : new DisabledPushSender();
}
