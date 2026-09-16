import { env, hashPhone, normalizeEmail, hashToken } from '../env.js';
import { one, type Queryable } from '../db.js';
import {
  badRequest,
  conflict,
  forbidden,
  notFound,
  tooManyRequests,
  unauthorized,
} from '../http/errors.js';
import type { PhoneIdentityVerifier } from './firebase.js';
import { accessTokenTtlSeconds, mintRefreshToken, refreshTokenExpiry, signAccessToken } from './tokens.js';

/**
 * Good Post identity (§2, §3, §19).
 *
 * Every function here takes a `Queryable` rather than reaching for the pool,
 * so the suite runs this exact SQL against a real Postgres (PGlite). The rules
 * this module exists to enforce:
 *
 *  - The mobile number is the abuse identity. It is hashed on arrival and the
 *    raw value is never stored, logged or returned (§38).
 *  - A ban is checked BEFORE anything else, and on registration as well as
 *    sign-in, so a banned identity cannot re-enter by re-registering (§19).
 *  - Whatever the Android client claims about itself is ignored: the phone
 *    number comes from a token Google signed, and status comes from the
 *    database. A client cannot assert that it is an admin, or un-banned.
 */

/** What a phone-verification challenge is for. Mirrors the 001 CHECK clause. */
export type VerificationPurpose = 'register' | 'signin' | 'recover';

/**
 * The caller's OWN account, as returned by /me and the auth endpoints.
 *
 * Named `OwnAccount` rather than `User` on purpose: it carries the private
 * email, so it must never be the type used to describe someone else (§38).
 * Channel and post payloads get their own narrower shapes in M2/M3, which
 * makes "accidentally serialised a private field" a compile error rather than
 * a code review catch.
 */
export interface OwnAccount {
  readonly id: string;
  readonly displayName: string;
  readonly email: string;
  readonly bio: string | null;
  readonly avatarObjectKey: string | null;
  readonly countryCode: string | null;
  readonly status: string;
  readonly createdAt: string;
}

export interface AuthSession {
  readonly accessToken: string;
  readonly refreshToken: string;
  readonly expiresIn: number;
  readonly user: OwnAccount;
}

/** Columns selected for an `OwnAccount`. Deliberately excludes `phone_hash`. */
const ACCOUNT_COLUMNS = `id, display_name, email, status, bio, avatar_object_key, country_code, created_at`;

interface AccountRow {
  id: string;
  display_name: string;
  email: string;
  status: 'active' | 'suspended' | 'banned';
  bio: string | null;
  avatar_object_key: string | null;
  country_code: string | null;
  created_at: Date | string;
  deleted_at?: Date | string | null;
}

function toOwnAccount(row: AccountRow): OwnAccount {
  return {
    id: row.id,
    displayName: row.display_name,
    email: row.email,
    bio: row.bio,
    avatarObjectKey: row.avatar_object_key,
    countryCode: row.country_code,
    status: row.status,
    // Tolerates a Date (pg) or a string, so the shape is identical whether the
    // row came from Neon or from PGlite in the suite.
    createdAt: new Date(row.created_at).toISOString(),
  };
}

/** E.164: a leading +, no leading zero, 7–15 digits total. */
const E164 = /^\+[1-9]\d{6,14}$/;

/**
 * Deliberately permissive: the authoritative check on an email is that it is
 * unique and deliverable later, not that it matches a regex. This only rejects
 * input that is obviously not an address.
 */
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * True when the value is usable as an email address.
 *
 * Exported so the email sign-in flow enforces exactly this and not a second,
 * slowly-diverging copy: the address registered must be one an address can be
 * signed in with.
 */
export function isValidEmail(value: string): boolean {
  return value.length > 0 && value.length <= 254 && EMAIL.test(value);
}

function assertE164(phone: string): void {
  if (!E164.test(phone)) {
    throw badRequest('invalid_phone', 'Phone number must be in E.164 format, e.g. +923001234567.');
  }
}

/** A unique-index violation, as thrown by both `pg` and PGlite. */
function isUniqueViolation(err: unknown): boolean {
  return (err as { code?: unknown } | null)?.code === '23505';
}

// ── Ban enforcement (§19) ───────────────────────────────────────────────

/**
 * Refuse anything to do with a banned mobile identity.
 *
 * Anchored exclusively on `phone_hash`, never on email or display name:
 * both of those are trivially changed, which is the entire reason §19 names
 * the mobile number as the identity. `email_normalized` is stored on a ban
 * row as a secondary signal for administrators, but 001 documents that it must
 * never be the sole basis — so it is not consulted here.
 */
async function assertNotBanned(database: Queryable, phoneHash: string): Promise<void> {
  if (!env.BAN_PHONE_ENFORCED) return;

  const rows = await database.query<{ id: string }>(
    `SELECT id FROM banned_identities WHERE phone_hash = $1 AND lifted_at IS NULL LIMIT 1`,
    [phoneHash]
  );

  if (rows.length > 0) {
    throw forbidden(
      'phone_banned',
      'This mobile number is blocked from Good Post.'
    );
  }
}

/** The live, unexpired challenge for this phone and purpose, if any. */
async function pendingChallenge(
  database: Queryable,
  phoneHash: string,
  purpose: VerificationPurpose
): Promise<{ id: string; attempts: number; max_attempts: number } | null> {
  return database.queryOne<{ id: string; attempts: number; max_attempts: number }>(
    `SELECT id, attempts, max_attempts
       FROM phone_verifications
      WHERE phone_hash = $1
        AND purpose = $2
        AND consumed_at IS NULL
        AND expires_at > now()
      ORDER BY created_at DESC
      LIMIT 1`,
    [phoneHash, purpose]
  );
}

/**
 * Spend the challenge that authorised this registration/sign-in.
 *
 * `expires_at > now()` is part of the lookup rather than a post-hoc check, so
 * "expired" and "never requested" collapse into one answer. That is
 * intentional: an attacker probing phone numbers learns nothing about whether
 * a challenge was recently issued, and the client's instruction is the same in
 * both cases — request a new code.
 */
async function consumeChallenge(
  database: Queryable,
  phoneHash: string,
  purpose: VerificationPurpose
): Promise<void> {
  const challenge = await pendingChallenge(database, phoneHash, purpose);
  if (!challenge) {
    throw badRequest('otp_required', 'Request a verification code before continuing.');
  }
  if (challenge.attempts >= challenge.max_attempts) {
    throw tooManyRequests('otp_locked', 'Too many failed attempts. Request a new code later.');
  }

  await database.query(
    `UPDATE phone_verifications SET verified_at = now(), consumed_at = now() WHERE id = $1`,
    [challenge.id]
  );
}

/**
 * Count a refusal against the phone's live challenge.
 *
 * Only called for `banned` / `suspended` — states that mean the caller is
 * already known to be a problem — and never for ordinary user error such as a
 * duplicate email. Counting ordinary mistakes would let a typo lock a
 * legitimate user out of their own number for the rest of the window.
 */
async function recordBlockedAttempt(
  database: Queryable,
  phoneHash: string,
  purpose: VerificationPurpose
): Promise<void> {
  await database.query(
    `UPDATE phone_verifications
        SET attempts = attempts + 1
      WHERE id = (
        SELECT id FROM phone_verifications
         WHERE phone_hash = $1 AND purpose = $2
           AND consumed_at IS NULL AND expires_at > now()
         ORDER BY created_at DESC
         LIMIT 1
      )`,
    [phoneHash, purpose]
  );
}

/**
 * Reject an account that exists but must not be used.
 *
 * A soft-deleted account reads as `account_not_found` rather than as itself:
 * §37 wants deletion to be indistinguishable from never having registered, and
 * telling a caller "this number had an account you deleted" is a privacy leak
 * to anyone who briefly controls the SIM.
 */
async function assertAccountUsable(
  database: Queryable,
  row: AccountRow,
  phoneHash: string,
  purpose: VerificationPurpose
): Promise<void> {
  if (row.status === 'banned') {
    await recordBlockedAttempt(database, phoneHash, purpose);
    throw forbidden('account_banned', 'This account has been permanently banned.');
  }
  if (row.status === 'suspended') {
    await recordBlockedAttempt(database, phoneHash, purpose);
    throw forbidden('account_suspended', 'This account is suspended.');
  }
}

// ── Sessions ────────────────────────────────────────────────────────────

/**
 * Mint a device session and hand back the one and only copy of its refresh
 * token. The database stores a SHA-256 hash, so the value returned here is
 * unrecoverable afterwards — by design (§3).
 */
async function createSession(
  database: Queryable,
  userId: string,
  device: { deviceLabel?: string | null; ipHash?: string | null }
): Promise<{ sessionId: string; refreshToken: string }> {
  const { token, hash } = mintRefreshToken();

  const rows = await database.query<{ id: string }>(
    `INSERT INTO user_sessions (user_id, refresh_token_hash, device_label, ip_hash, expires_at)
     VALUES ($1, $2, $3, $4, $5)
     RETURNING id`,
    [userId, hash, device.deviceLabel ?? null, device.ipHash ?? null, refreshTokenExpiry()]
  );

  return { sessionId: one(rows).id, refreshToken: token };
}

function sessionResponse(
  user: OwnAccount,
  session: { sessionId: string; refreshToken: string }
): AuthSession {
  return {
    accessToken: signAccessToken({ sub: user.id, sid: session.sessionId }),
    refreshToken: session.refreshToken,
    expiresIn: accessTokenTtlSeconds(),
    user,
  };
}

// ── Public operations ───────────────────────────────────────────────────

export interface OtpRequestResult {
  readonly expiresAt: string;
  readonly sendsRemaining: number;
}

/**
 * The server-side half of OTP issuance (§3).
 *
 * Firebase sends the SMS, so this endpoint cannot withhold the message — what
 * it does is enforce the limits Firebase alone will not give us per account:
 * how many codes may be requested for a number per hour, and whether a number
 * is banned. The client MUST call this before asking Firebase to send, and the
 * register/sign-in endpoints then require the resulting challenge to exist.
 *
 * The raw phone number is hashed on the way in and never persisted. It arrives
 * only because the caller is stating its own number; it is not a claim the
 * server trusts for anything until a Firebase token proves it.
 */
export async function requestOtp(
  database: Queryable,
  input: { phone: string; purpose: VerificationPurpose; ipHash?: string | null }
): Promise<OtpRequestResult> {
  assertE164(input.phone);
  const phoneHash = hashPhone(input.phone);

  // A banned number never receives a code. This is also the earliest point a
  // ban can be enforced — before any SMS is paid for.
  await assertNotBanned(database, phoneHash);

  const windowStart = new Date(Date.now() - 60 * 60 * 1000);
  const totals = await database.query<{ total: number }>(
    `SELECT COALESCE(SUM(send_count), 0)::int AS total
       FROM phone_verifications
      WHERE phone_hash = $1 AND created_at > $2`,
    [phoneHash, windowStart]
  );
  const sent = totals[0]?.total ?? 0;

  if (sent >= env.OTP_MAX_SENDS_PER_HOUR) {
    throw tooManyRequests(
      'otp_rate_limited',
      `Too many verification codes requested. Try again later.`
    );
  }

  const expiresAt = new Date(Date.now() + env.OTP_TTL_MINUTES * 60 * 1000);
  await database.query(
    `INSERT INTO phone_verifications (phone_hash, purpose, expires_at, max_attempts)
     VALUES ($1, $2, $3, $4)`,
    [phoneHash, input.purpose, expiresAt, env.OTP_MAX_ATTEMPTS]
  );

  return {
    expiresAt: expiresAt.toISOString(),
    sendsRemaining: env.OTP_MAX_SENDS_PER_HOUR - (sent + 1),
  };
}

export interface RegisterInput {
  readonly idToken: string;
  readonly displayName: string;
  readonly email: string;
  readonly deviceLabel?: string | null;
  readonly ipHash?: string | null;
}

/**
 * Create a Good Post account.
 *
 * Note the ordering: the ban check happens before the challenge is consumed
 * and before the transaction opens, so a banned identity is refused without
 * touching the users table at all. The uniqueness checks then run inside the
 * transaction, but the transaction is NOT what makes them safe — the partial
 * unique indexes are. A concurrent double-registration of the same number is
 * caught by `users_phone_hash_uniq` and translated into the same 409 the
 * pre-check produces, which is why the catch below exists rather than being
 * defensive noise.
 */
export async function register(
  database: Queryable,
  verifier: PhoneIdentityVerifier,
  input: RegisterInput
): Promise<AuthSession> {
  const displayName = input.displayName.trim();
  const email = input.email.trim();

  if (displayName.length < 1 || displayName.length > 60) {
    throw badRequest('invalid_display_name', 'Display name must be 1–60 characters.');
  }
  if (!isValidEmail(email)) {
    throw badRequest('invalid_email', 'Enter a valid email address.');
  }

  // Only Google's signature makes this phone number trustworthy.
  const { phoneE164 } = await verifier.verifyIdToken(input.idToken);
  const phoneHash = hashPhone(phoneE164);
  const emailNormalized = normalizeEmail(email);

  await assertNotBanned(database, phoneHash);

  try {
    return await database.transaction(async (tx) => {
      const existingPhone = await tx.query<{ id: string }>(
        `SELECT id FROM users WHERE phone_hash = $1`,
        [phoneHash]
      );
      if (existingPhone.length > 0) {
        // 001 makes phone_hash unique across ALL rows, deleted included: a
        // self-deleted account deliberately does not release its number, or
        // deleting would be a one-tap ban bypass.
        throw conflict(
          'phone_already_registered',
          'This mobile number already has an account. Sign in instead.'
        );
      }

      const existingEmail = await tx.query<{ id: string }>(
        `SELECT id FROM users WHERE email_normalized = $1 AND deleted_at IS NULL`,
        [emailNormalized]
      );
      if (existingEmail.length > 0) {
        throw conflict('email_already_registered', 'That email address is already in use.');
      }

      const inserted = await tx.query<AccountRow>(
        `INSERT INTO users (display_name, email, email_normalized, phone_hash, phone_hash_version)
         VALUES ($1, $2, $3, $4, $5)
         RETURNING ${ACCOUNT_COLUMNS}`,
        [displayName, email, emailNormalized, phoneHash, env.PHONE_HASH_PEPPER_VERSION]
      );
      const user = toOwnAccount(one(inserted));

      // Consumed only once the account write has succeeded, so a validation
      // failure does not burn the user's code.
      await consumeChallenge(tx, phoneHash, 'register');

      const session = await createSession(tx, user.id, input);
      return sessionResponse(user, session);
    });
  } catch (err) {
    if (isUniqueViolation(err)) {
      throw conflict('account_already_exists', 'That account already exists. Sign in instead.');
    }
    throw err;
  }
}

export interface SignInInput {
  readonly idToken: string;
  readonly deviceLabel?: string | null;
  readonly ipHash?: string | null;
}

/**
 * Sign in an existing account.
 *
 * `account_not_found` is a 404 to the *number's verified owner*, which is safe:
 * the caller has just proved control of that SIM, so telling them no account
 * exists is both necessary (to offer registration) and reveals nothing to
 * anyone else. No phone number ever appears in a response.
 */
export async function signIn(
  database: Queryable,
  verifier: PhoneIdentityVerifier,
  input: SignInInput
): Promise<AuthSession> {
  const { phoneE164 } = await verifier.verifyIdToken(input.idToken);
  const phoneHash = hashPhone(phoneE164);

  await assertNotBanned(database, phoneHash);

  const rows = await database.query<AccountRow>(
    `SELECT ${ACCOUNT_COLUMNS}, deleted_at FROM users WHERE phone_hash = $1`,
    [phoneHash]
  );
  const row = rows[0];

  if (!row || row.deleted_at != null) {
    throw notFound('account_not_found', 'No Good Post account uses this mobile number.');
  }

  // Deliberately OUTSIDE the transaction below. This path counts a blocked
  // attempt against the phone's challenge and then throws; inside a
  // transaction the throw would roll the counter back, so the attempt limit
  // could never be reached and a banned user could retry forever.
  await assertAccountUsable(database, row, phoneHash, 'signin');

  return database.transaction(async (tx) => {
    await consumeChallenge(tx, phoneHash, 'signin');

    const user = toOwnAccount(row);
    const session = await createSession(tx, user.id, input);
    return sessionResponse(user, session);
  });
}

/**
 * Issue a session for an account whose identity was proven some other way.
 *
 * Email sign-in proves the address, not the number, so it cannot reuse
 * `signIn`, which begins by verifying a Firebase ID token. Everything after
 * that point is identical, and it deliberately lives here rather than being
 * copied: a second session-creation path is where `deleted_at`, a re-check of
 * status, and the access token's subject are easiest to get subtly wrong.
 */
export async function startSessionForAccount(
  database: Queryable,
  userId: string,
  client: { deviceLabel?: string | null; ipHash?: string | null }
): Promise<AuthSession> {
  // Re-read rather than trusting the caller's row: `deleted_at` may have been
  // set, or a status changed, between the check that authorised this and here.
  const account = await loadOwnAccount(database, userId);
  if (!account) {
    throw unauthorized('invalid_token', 'That account is no longer available.');
  }

  const session = await createSession(database, userId, client);
  return sessionResponse(account, session);
}

export interface RefreshInput {
  readonly refreshToken: string;
  readonly deviceLabel?: string | null;
  readonly ipHash?: string | null;
}

interface SessionRow {
  id: string;
  user_id: string;
  revoked_at: Date | string | null;
  expires_at: Date | string;
  status: AccountRow['status'];
  deleted_at: Date | string | null;
}

/**
 * How a refresh finished.
 *
 * `reuse` is returned rather than thrown, and that is load-bearing: detecting
 * reuse writes a revocation, and throwing from inside the transaction would
 * roll that write back. The response would then announce that every session
 * had been killed while, in reality, nothing had changed — a security control
 * that silently does nothing is worse than not having it, because it is
 * believed. The caller raises the 401 after the commit succeeds.
 */
type RefreshOutcome =
  | { readonly outcome: 'reuse' }
  | { readonly outcome: 'ok'; readonly session: AuthSession };

/**
 * Rotate a refresh token (§3).
 *
 * Rotation with REUSE DETECTION, which is the whole point of doing it in two
 * columns. Rotation alone would let a stolen token keep working for its full
 * 30 days. So the old hash is retained in `previous_refresh_token_hash`
 * (migration 002): presenting a token that was already rotated is not a stale
 * client, it is evidence that someone else has a copy — and the response is to
 * revoke every session on the account and demand a fresh phone verification.
 *
 * `sid` is deliberately left unchanged across a rotation, so the caller's
 * short-lived access token keeps working instead of forcing a hard logout on
 * every refresh.
 */
export async function refreshSession(
  database: Queryable,
  input: RefreshInput
): Promise<AuthSession> {
  if (!input.refreshToken) throw badRequest('refresh_token_required');

  const presentedHash = hashToken(input.refreshToken);

  const result = await database.transaction<RefreshOutcome>(async (tx) => {
    const live = await tx.query<SessionRow>(
      `SELECT s.id, s.user_id, s.revoked_at, s.expires_at, u.status, u.deleted_at
         FROM user_sessions s
         JOIN users u ON u.id = s.user_id
        WHERE s.refresh_token_hash = $1
        FOR UPDATE OF s`,
      [presentedHash]
    );

    const session = live[0];

    if (!session) {
      // Not the current token. If it matches a token this account already
      // rotated away from, the only explanations are theft or a leaked
      // database snapshot — so kill the whole device set and make the owner
      // re-prove their number.
      const reused = await tx.query<{ user_id: string }>(
        `SELECT user_id FROM user_sessions WHERE previous_refresh_token_hash = $1 LIMIT 1`,
        [presentedHash]
      );
      const owner = reused[0]?.user_id;

      if (owner) {
        await tx.query(
          `UPDATE user_sessions
              SET revoked_at = now(), revoked_reason = 'refresh_token_reuse_detected'
            WHERE user_id = $1 AND revoked_at IS NULL`,
          [owner]
        );
        // Return, do not throw: this transaction has just revoked the
        // account's sessions and must COMMIT. See [RefreshOutcome].
        return { outcome: 'reuse' as const };
      }

      // Nothing has been written on this path, so a throw is safe here.
      throw unauthorized('invalid_refresh_token', 'The refresh token is not valid.');
    }

    if (session.revoked_at != null) {
      throw unauthorized('session_revoked', 'This session has been revoked. Sign in again.');
    }
    if (new Date(session.expires_at).getTime() <= Date.now()) {
      throw unauthorized('refresh_token_expired', 'This session has expired. Sign in again.');
    }
    if (session.status !== 'active' || session.deleted_at != null) {
      // The account was suspended or banned after this token was issued.
      throw forbidden(
        session.status === 'banned' ? 'account_banned' : 'account_suspended',
        'This account is not active.'
      );
    }

    const accountRows = await tx.query<AccountRow>(
      `SELECT ${ACCOUNT_COLUMNS} FROM users WHERE id = $1`,
      [session.user_id]
    );
    const user = toOwnAccount(one(accountRows));

    const { token, hash } = mintRefreshToken();
    await tx.query(
      `UPDATE user_sessions
          SET refresh_token_hash = $2,
              previous_refresh_token_hash = $3,
              last_used_at = now(),
              expires_at = $4,
              device_label = COALESCE($5, device_label),
              ip_hash = COALESCE($6, ip_hash)
        WHERE id = $1`,
      [session.id, hash, presentedHash, refreshTokenExpiry(), input.deviceLabel ?? null, input.ipHash ?? null]
    );

    return {
      outcome: 'ok' as const,
      session: sessionResponse(user, { sessionId: session.id, refreshToken: token }),
    };
  });

  if (result.outcome === 'reuse') {
    // Reached only after the revocation committed.
    throw unauthorized(
      'refresh_token_reused',
      'This session was already rotated. All sessions have been revoked; sign in again.'
    );
  }

  return result.session;
}

/**
 * Revoke the session a refresh token belongs to.
 *
 * Revoking by token rather than by session id means a caller can only ever
 * log itself out: it has to already hold the credential it is destroying.
 */
export async function revokeSessionByToken(
  database: Queryable,
  refreshToken: string,
  reason = 'logout'
): Promise<boolean> {
  if (!refreshToken) return false;

  const rows = await database.query<{ id: string }>(
    `UPDATE user_sessions
        SET revoked_at = now(), revoked_reason = $2
      WHERE refresh_token_hash = $1 AND revoked_at IS NULL
      RETURNING id`,
    [hashToken(refreshToken), reason]
  );

  return rows.length > 0;
}

/** Kill every device for an account — used by logout-all and by bans (§19). */
export async function revokeAllSessions(
  database: Queryable,
  userId: string,
  reason: string
): Promise<number> {
  const rows = await database.query<{ id: string }>(
    `UPDATE user_sessions
        SET revoked_at = now(), revoked_reason = $2
      WHERE user_id = $1 AND revoked_at IS NULL
      RETURNING id`,
    [userId, reason]
  );

  return rows.length;
}

/**
 * The caller's own account, or null if it is gone or no longer usable.
 * Backs GET /auth/me, which is how the client decides whether a stored token
 * is still worth keeping.
 */
export async function loadOwnAccount(
  database: Queryable,
  userId: string
): Promise<OwnAccount | null> {
  const row = await database.queryOne<AccountRow>(
    `SELECT ${ACCOUNT_COLUMNS} FROM users WHERE id = $1 AND deleted_at IS NULL`,
    [userId]
  );

  return row ? toOwnAccount(row) : null;
}

/**
 * Is this session still live?
 *
 * Called on every authenticated request, which costs one indexed lookup.
 * That cost buys instant revocation: a ban, a forced logout or a reuse
 * detection takes effect on the user's very next request rather than when
 * their 15-minute access token happens to expire. §19 explicitly requires
 * current sessions to be revoked at ban time, and a token-signature-only check
 * could not honour that.
 */
export async function isSessionLive(
  database: Queryable,
  userId: string,
  sessionId: string
): Promise<boolean> {
  const row = await database.queryOne<{ id: string }>(
    `SELECT id FROM user_sessions
      WHERE id = $1 AND user_id = $2
        AND revoked_at IS NULL
        AND expires_at > now()`,
    [sessionId, userId]
  );

  return row !== null;
}
