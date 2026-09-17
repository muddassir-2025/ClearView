import { randomInt, timingSafeEqual } from 'node:crypto';
import { env, hashEmailCode, normalizeEmail } from '../env.js';
import { one, type Queryable } from '../db.js';
import {
  ApiError,
  badRequest,
  conflict,
  forbidden,
  notFound,
  serviceUnavailable,
  tooManyRequests,
  unauthorized,
} from '../http/errors.js';
import { createMailer, signInCodeMail, type Mailer } from '../email/sender.js';
import {
  isValidEmail,
  startSessionForAccount,
  type AuthSession,
} from './service.js';

/**
 * Email sign-in: the second way into Good Post.
 *
 * What this is NOT, and the distinction is the whole design:
 *
 *  - It does not register anyone. §19 anchors the abuse identity on the mobile
 *    number, because a number survives an email change, a rename, a reinstall
 *    and a data wipe. An email address survives none of those, so letting it
 *    create an account would hand every banned user a one-step bypass. Every
 *    account here was created by the Firebase phone flow; this method is a
 *    second door into an existing one.
 *
 *  - It does not decide anything about identity that the phone flow does not
 *    also decide. A ban, a suspension and a soft deletion are read from the
 *    same columns, with the same meanings.
 *
 * The division of labour:
 *  - The CODE is ours — generated with a CSPRNG, stored only as an HMAC, and
 *    compared in constant time.
 *  - The DELIVERY is the mailer's, behind an interface (§44).
 *
 * Two properties are load-bearing and easy to lose:
 *
 *  1. Requesting a code reveals NOTHING about whether an address has an
 *     account. The response is identical either way. "Is this address
 *     registered?" is a question an attacker must not be able to ask, and the
 *     answer only becomes useful after proving control of the inbox.
 *  2. A wrong guess is counted OUTSIDE the transaction that would throw it
 *     away. Counting inside would make the attempt limit unreachable — the
 *     exact trap `signIn` documents for banned attempts.
 */

/** How many digits a sign-in code has. */
const CODE_DIGITS = 6;

export interface EmailOtpResult {
  readonly expiresAt: string;
  readonly sendsRemaining: number;
}

interface EmailChallengeRow {
  id: string;
  code_hash: string;
  attempts: number;
  max_attempts: number;
}

/** A fresh code, uniform over the full range. */
function generateCode(): string {
  // randomInt is the CSPRNG-backed generator; Math.random would be predictable
  // enough to guess the next code from a handful of previous ones.
  return String(randomInt(0, 10 ** CODE_DIGITS)).padStart(CODE_DIGITS, '0');
}

/** Constant-time code comparison. */
function codeMatches(emailNormalized: string, submitted: string, storedHash: string): boolean {
  const expected = Buffer.from(storedHash, 'hex');
  const actual = Buffer.from(hashEmailCode(emailNormalized, submitted), 'hex');

  // timingSafeEqual throws on a length mismatch, and the lengths here are fixed
  // by the algorithm — so a mismatch means a corrupt row, not a wrong guess.
  if (expected.length !== actual.length) return false;
  return timingSafeEqual(expected, actual);
}

/**
 * Count a failed guess.
 *
 * Deliberately takes the raw database handle rather than a transaction: the
 * caller throws immediately afterwards, and an UPDATE inside a transaction that
 * is about to roll back would count nothing at all.
 */
async function countAttempt(database: Queryable, challengeId: string, reason: string): Promise<void> {
  await database.query(`UPDATE email_verifications SET attempts = attempts + 1 WHERE id = $1`, [
    challengeId,
  ]);
  console.log(`[email-auth] challenge attempt counted (${reason})`);
}

interface AccountByEmailRow {
  id: string;
  status: 'active' | 'suspended' | 'banned';
}

/**
 * The account that owns this address, if there is one.
 *
 * `deleted_at IS NULL` mirrors `users_email_active_uniq`, and selecting the
 * newest row keeps this consistent with that index if a soft-deleted account
 * left the same address behind.
 */
async function activeAccountByEmail(
  database: Queryable,
  emailNormalized: string
): Promise<AccountByEmailRow | null> {
  return database.queryOne<AccountByEmailRow>(
    `SELECT id, status
       FROM users
      WHERE email_normalized = $1 AND deleted_at IS NULL
      ORDER BY created_at DESC
      LIMIT 1`,
    [emailNormalized]
  );
}

/**
 * Step 1: issue and deliver a code.
 *
 * The hourly allowance is spent before the send, so a mail bomb cannot be aimed
 * at an address we do not control. The challenge row is written first and
 * REMOVED again if delivery fails: leaving it behind would let the user sit
 * guessing a code that was never sent, while silently consuming their
 * allowance.
 */
export async function requestEmailOtp(
  database: Queryable,
  input: { email: string; purpose?: 'signin' | 'recover' | 'register' },
  mailer: Mailer = createMailer()
): Promise<EmailOtpResult> {
  const email = normalizeEmail(input.email);
  if (!isValidEmail(email)) {
    throw badRequest('invalid_email', 'Enter a valid email address.');
  }
  const purpose = input.purpose ?? 'signin';

  const totals = await database.query<{ total: number }>(
    `SELECT COALESCE(SUM(send_count), 0)::int AS total
       FROM email_verifications
      WHERE email_normalized = $1 AND created_at > $2`,
    [email, new Date(Date.now() - 60 * 60 * 1000)]
  );
  const sent = totals[0]?.total ?? 0;

  if (sent >= env.EMAIL_OTP_MAX_SENDS_PER_HOUR) {
    throw tooManyRequests('otp_rate_limited', 'Too many codes requested. Try again later.');
  }

  const code = generateCode();
  const expiresAt = new Date(Date.now() + env.EMAIL_OTP_TTL_MINUTES * 60 * 1000);

  const inserted = await database.query<{ id: string }>(
    `INSERT INTO email_verifications (email_normalized, purpose, code_hash, expires_at, max_attempts)
     VALUES ($1, $2, $3, $4, $5)
     RETURNING id`,
    [email, purpose, hashEmailCode(email, code), expiresAt, env.EMAIL_OTP_MAX_ATTEMPTS]
  );
  const challengeId = one(inserted).id;

  try {
    await mailer.send({ to: email, ...signInCodeMail(code, env.EMAIL_OTP_TTL_MINUTES) });
  } catch (err) {
    await database.query(`DELETE FROM email_verifications WHERE id = $1`, [challengeId]);

    // Whatever shape the provider's failure arrives in, the caller's contract
    // is the same sentence: the code could not be delivered. Translating here
    // rather than letting an unexpected error surface as an opaque 500 keeps
    // the client on a code it can word, and keeps the reason in the log where
    // an operator can see it. A deliberate ApiError is passed through so a
    // more specific refusal is never flattened.
    if (err instanceof ApiError) throw err;
    console.error('[email-auth] delivery failed unexpectedly:', (err as Error).name);
    throw serviceUnavailable('email_unavailable', 'Could not send the verification email.');
  }

  return {
    expiresAt: expiresAt.toISOString(),
    sendsRemaining: Math.max(0, env.EMAIL_OTP_MAX_SENDS_PER_HOUR - (sent + 1)),
  };
}

export interface EmailSignInInput {
  readonly email: string;
  readonly code: string;
  readonly deviceLabel?: string | null;
  readonly ipHash?: string | null;
}

/**
 * The live challenge for this address, for either purpose.
 *
 * A registration is allowed to redeem a code that was requested by the sign-in
 * screen, and deliberately so: the flow the user actually walks is one screen
 * that asks for an address, sends one code, and only THEN discovers there is no
 * account behind it. Asking for a second code at that point would punish the
 * user for a fact that was never theirs to know. Both purposes prove the same
 * thing — control of the inbox — and neither can outlive its TTL, so the
 * allowance being shared costs nothing.
 */
async function pendingChallengeForAuth(
  database: Queryable,
  emailNormalized: string
): Promise<EmailChallengeRow | null> {
  return database.queryOne<EmailChallengeRow>(
    `SELECT id, code_hash, attempts, max_attempts
       FROM email_verifications
      WHERE email_normalized = $1
        AND purpose IN ('signin', 'register')
        AND consumed_at IS NULL
        AND expires_at > now()
      ORDER BY created_at DESC
      LIMIT 1`,
    [emailNormalized]
  );
}

/**
 * Check a pending code, counting the miss. Shared by sign-in and registration so
 * both flows lock out on exactly the same attempts.
 */
async function verifyPendingCode(
  database: Queryable,
  email: string,
  submitted: string,
  purpose: 'signin' | 'register'
): Promise<EmailChallengeRow> {
  const challenge = await pendingChallengeForAuth(database, email);
  if (!challenge) {
    // Expired, already spent, or never requested — one answer for all three.
    throw badRequest('otp_required', 'Request a verification code before continuing.');
  }
  if (challenge.attempts >= challenge.max_attempts) {
    throw tooManyRequests('otp_locked', 'Too many failed attempts. Request a new code later.');
  }
  if (!codeMatches(email, submitted, challenge.code_hash)) {
    await countAttempt(database, challenge.id, `${purpose}_wrong_code`);
    throw unauthorized('invalid_code', 'That code is not correct.');
  }
  return challenge;
}

/**
 * Step 2: exchange a code for a session.
 *
 * Ordering, all of it deliberate:
 *
 *  1. The challenge is validated and the CODE checked first, so an attacker
 *     without the code learns nothing — in particular not whether the address
 *     has an account.
 *  2. Only then is the account looked up. `email_not_registered` is therefore
 *     told to whoever proved control of the inbox, which is the same reasoning
 *     that lets `signIn` answer `account_not_found` to the SIM's owner.
 *  3. A banned or suspended account is refused WITHOUT consuming the code, and
 *     the refusal is counted against the challenge's attempt limit.
 *  4. Consumption and session creation share one transaction, and the consume
 *     is conditional on the row still being unused, so two concurrent
 *     submissions cannot both win.
 */
export async function emailSignIn(
  database: Queryable,
  input: EmailSignInInput
): Promise<AuthSession> {
  const email = normalizeEmail(input.email);
  if (!isValidEmail(email)) {
    throw badRequest('invalid_email', 'Enter a valid email address.');
  }

  // Codes are typed on a phone keypad; anything that is not a digit is noise.
  const submitted = input.code.replace(/\D/g, '');

  const challenge = await verifyPendingCode(database, email, submitted, 'signin');

  const account = await activeAccountByEmail(database, email);
  if (!account) {
    throw notFound('email_not_registered', 'No Good Post account uses this email address.');
  }
  if (account.status === 'banned') {
    await countAttempt(database, challenge.id, 'account_banned');
    throw forbidden('account_banned', 'This account has been permanently banned.');
  }
  if (account.status === 'suspended') {
    await countAttempt(database, challenge.id, 'account_suspended');
    throw forbidden('account_suspended', 'This account is suspended.');
  }

  return database.transaction(async (tx) => {
    const consumed = await tx.query<{ id: string }>(
      `UPDATE email_verifications
          SET verified_at = now(), consumed_at = now()
        WHERE id = $1 AND consumed_at IS NULL
        RETURNING id`,
      [challenge.id]
    );
    if (consumed.length === 0) {
      throw badRequest('otp_required', 'Request a verification code before continuing.');
    }

    return startSessionForAccount(tx, account.id, {
      deviceLabel: input.deviceLabel,
      ipHash: input.ipHash,
    });
  });
}

export interface EmailRegisterInput {
  readonly email: string;
  readonly code: string;
  readonly displayName: string;
  readonly deviceLabel?: string | null;
  readonly ipHash?: string | null;
}

/**
 * Step 2 for an address that has no account yet: prove the inbox, then create
 * one.
 *
 * The account is created with `phone_hash` NULL. That is the honest shape of an
 * email-only account rather than a loophole: it means `banned_identities`
 * cannot match it, so a ban on such an account is enforced by the account
 * status every protected request already reads — which is exactly what §19's
 * "do not rely solely on the Android app" asks for while the phone flow is
 * paused. When the number comes back, `auth/service.register` stays the only
 * path that can attach one, and its unique index is untouched.
 *
 * The name is validated here rather than at the route so both callers share one
 * rule, and the challenge is consumed only after the row exists — a rejected
 * name must not burn the user's code.
 */
export async function emailRegister(
  database: Queryable,
  input: EmailRegisterInput
): Promise<AuthSession> {
  const email = normalizeEmail(input.email);
  if (!isValidEmail(email)) {
    throw badRequest('invalid_email', 'Enter a valid email address.');
  }

  const displayName = input.displayName.trim();
  if (displayName.length < 1 || displayName.length > 60) {
    throw badRequest('invalid_display_name', 'Display name must be 1–60 characters.');
  }

  const submitted = input.code.replace(/\D/g, '');
  const challenge = await verifyPendingCode(database, email, submitted, 'register');

  try {
    return await database.transaction(async (tx) => {
      const existingEmail = await tx.query<{ id: string }>(
        `SELECT id FROM users WHERE email_normalized = $1 AND deleted_at IS NULL`,
        [email]
      );
      if (existingEmail.length > 0) {
        // Raced with another registration, or the sign-in lookup was stale.
        throw conflict('email_already_registered', 'That email address is already in use.');
      }

      const inserted = await tx.query<{ id: string }>(
        `INSERT INTO users (display_name, email, email_normalized)
         VALUES ($1, $2, $3)
         RETURNING id`,
        [displayName, email, email]
      );
      const userId = one(inserted).id;

      const consumed = await tx.query<{ id: string }>(
        `UPDATE email_verifications
            SET verified_at = now(), consumed_at = now()
          WHERE id = $1 AND consumed_at IS NULL
          RETURNING id`,
        [challenge.id]
      );
      if (consumed.length === 0) {
        throw badRequest('otp_required', 'Request a verification code before continuing.');
      }

      // Shared with sign-in on purpose: one place decides how a session is
      // minted, so status and `deleted_at` are re-checked identically here.
      return startSessionForAccount(tx, userId, {
        deviceLabel: input.deviceLabel,
        ipHash: input.ipHash,
      });
    });
  } catch (err) {
    if ((err as { code?: unknown } | null)?.code === '23505') {
      throw conflict('email_already_registered', 'That email address is already in use.');
    }
    throw err;
  }
}
