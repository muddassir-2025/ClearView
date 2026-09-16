import { env, isProduction } from '../env.js';
import { serviceUnavailable } from '../http/errors.js';

/**
 * Outbound email, behind a small interface (§44: the OTP provider is abstracted
 * so the application is not welded to one vendor).
 *
 * This module sends exactly one kind of message — a sign-in code — and that
 * narrowness is deliberate: it is not a general notification system, and the
 * only thing the rest of the codebase may ask of it is "deliver these words to
 * this address". Swapping Resend for another provider means writing one more
 * class here and nothing else.
 *
 * Two rules hold for whatever implementation is selected:
 *
 *  - The CODE is never logged, never echoed in an error, and never included in
 *    a thrown message. §3 forbids it, and the code is a live credential for the
 *    duration of its window.
 *  - A delivery failure surfaces as `email_unavailable` (503) rather than as a
 *    success. §36 forbids reporting a step as done before it is, and "we sent
 *    you a code" that never arrived is the exact lie that sends a user hunting
 *    through a spam folder for something that does not exist.
 */

export interface OutboundMail {
  readonly to: string;
  readonly subject: string;
  readonly text: string;
  readonly html: string;
}

export interface Mailer {
  readonly kind: 'resend' | 'console' | 'disabled';
  send(mail: OutboundMail): Promise<void>;
}

/** Resend's REST endpoint. Called with `fetch` rather than an SDK, so adding
 *  email did not add a dependency to a service that must boot on Render. */
const RESEND_ENDPOINT = 'https://api.resend.com/emails';

/** True when email sign-in can actually deliver. */
export const emailDeliveryConfigured = Boolean(
  env.EMAIL_DELIVERY_MODE === 'resend' && env.RESEND_API_KEY && env.EMAIL_FROM
);

class ResendMailer implements Mailer {
  readonly kind = 'resend' as const;

  async send(mail: OutboundMail): Promise<void> {
    let response: Response;
    try {
      response = await fetch(RESEND_ENDPOINT, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${env.RESEND_API_KEY}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: env.EMAIL_FROM,
          to: [mail.to],
          subject: mail.subject,
          text: mail.text,
          html: mail.html,
        }),
        // Without this a hung provider would hold the request open until
        // Render's own timeout, and the user would see nothing at all.
        signal: AbortSignal.timeout(10_000),
      });
    } catch (err) {
      // The transport error only. `fetch` rejections do not carry the body, so
      // neither the address nor the code can leak through here.
      console.error('[email] delivery failed:', (err as Error).name);
      throw serviceUnavailable('email_unavailable', 'Could not send the verification email.');
    }

    if (!response.ok) {
      // Status plus the provider's own error NAME, and nothing else. The body's
      // message routinely quotes the recipient address and the content it
      // rejected (§38), so it is discarded unread — but the name is what tells
      // "this account may not send to that address" apart from "the provider is
      // down". Without it every refusal looked identical here, which is exactly
      // how a sandbox restriction spent an evening looking like a server fault.
      const reason = await providerErrorName(response);
      console.error(
        `[email] provider rejected the send: HTTP ${response.status}` +
          (reason ? ` (${reason})` : '')
      );
      throw serviceUnavailable('email_unavailable', 'Could not send the verification email.');
    }
  }
}

/**
 * The provider's own error identifier, or null.
 *
 * Reads a bounded prefix and keeps only `name`/`type` — short, enum-like values
 * such as `validation_error` or `rate_limit_exceeded`. Everything else in the
 * body is discarded without being parsed, so nothing a provider echoes back can
 * reach a log line. Exported so the shape a real refusal takes stays pinned by a
 * test rather than by memory.
 */
export async function providerErrorName(response: Response): Promise<string | null> {
  try {
    const raw = (await response.text()).slice(0, 4096);
    const parsed = JSON.parse(raw) as { name?: unknown; type?: unknown };
    for (const value of [parsed.name, parsed.type]) {
      if (typeof value === 'string' && /^[a-z0-9_]{3,40}$/i.test(value)) return value;
    }
  } catch {
    // A non-JSON body — an HTML error page, an empty 5xx — is not worth parsing.
  }
  return null;
}

/**
 * Writes the message to the process log instead of sending it.
 *
 * For local development and the test suite, and unreachable in production: the
 * boot guard in env.ts refuses `EMAIL_DELIVERY_MODE=console`, and the check
 * below fails closed anyway. Both exist because a single missed guard here
 * would print live sign-in codes into a production log.
 */
class ConsoleMailer implements Mailer {
  readonly kind = 'console' as const;

  async send(mail: OutboundMail): Promise<void> {
    if (isProduction) {
      throw serviceUnavailable('email_unavailable', 'Email delivery is disabled.');
    }
    console.log(
      `[email] (console mode, not sent) to ${redact(mail.to)} :: ${mail.subject}\n${mail.text}`
    );
  }
}

/** No provider configured: honest 503 rather than a silent no-op. */
class DisabledMailer implements Mailer {
  readonly kind = 'disabled' as const;

  async send(): Promise<void> {
    throw serviceUnavailable(
      'email_unavailable',
      'Email sign-in is not configured on this server.'
    );
  }
}

/**
 * An address reduced to something safe to log.
 *
 * Enough to tell two deliveries apart while debugging, too little to be a
 * usable list of users if logs are ever handed to someone else (§38).
 */
export function redact(email: string): string {
  const at = email.lastIndexOf('@');
  // No usable local part or domain: there is nothing worth keeping.
  if (at <= 0 || at === email.length - 1) return '***';
  return `${email.slice(0, 1)}***@${email.slice(at + 1)}`;
}

/** The mailer selected by EMAIL_DELIVERY_MODE. */
export function createMailer(): Mailer {
  switch (env.EMAIL_DELIVERY_MODE) {
    case 'resend':
      // Falls back to the disabled mailer if the key or sender is missing, so a
      // half-configured deployment answers 503 instead of throwing an
      // unhandled error on the first sign-in attempt.
      return emailDeliveryConfigured ? new ResendMailer() : new DisabledMailer();
    case 'console':
      return new ConsoleMailer();
    default:
      return new DisabledMailer();
  }
}

/** The message body. Plain text is the source of truth; HTML is the same text. */
export function signInCodeMail(code: string, minutes: number): Omit<OutboundMail, 'to'> {
  const text =
    `Your ClearView sign-in code is ${code}.\n\n` +
    `It expires in ${minutes} minutes. If you did not request it, ignore this ` +
    `message — nobody can sign in without the code.`;
  return {
    subject: 'Your ClearView sign-in code',
    text,
    html:
      `<p>Your ClearView sign-in code is <strong>${code}</strong>.</p>` +
      `<p>It expires in ${minutes} minutes. If you did not request it, ignore ` +
      `this message — nobody can sign in without the code.</p>`,
  };
}
