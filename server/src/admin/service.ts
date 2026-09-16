import bcrypt from 'bcryptjs';
import { env, hashPhone, hashToken, normalizeEmail } from '../env.js';
import { isoOrNull, one, type Queryable } from '../db.js';
import { badRequest, conflict, forbidden, notFound, tooManyRequests, unauthorized } from '../http/errors.js';
import { isValidEmail } from '../auth/service.js';
import { ADMIN_ROLES, type AdminRole, type AdminStatus } from './permissions.js';
import {
  adminSessionExpiry,
  mintAdminRefreshToken,
  signAdminAccessToken,
} from './tokens.js';

/**
 * Administrator identity, sessions and the audit log (§20, §21, §27, §29, §30).
 *
 * Rules this module exists to enforce:
 *
 *  * **Admin login is not the user flow.** A separate table, a separate token
 *    audience, a separate signing key and password authentication instead of
 *    an OTP. §21 is explicit, and §48 explains why: a Good Post registration
 *    must never be able to produce an administrator.
 *
 *  * **No enumeration.** An unknown address, a wrong password and a disabled
 *    account answer the same thing, `invalid_credentials`. The one exception is
 *    deliberate and safe: once the PASSWORD is correct, a disabled account is
 *    told it is disabled, because the caller has already proved they own it and
 *    "you cannot sign in" without a reason is a support ticket.
 *
 *  * **Failed attempts are counted in the database**, per administrator, so a
 *    restart does not hand an attacker a fresh allowance (§30).
 *
 *  * **Every sensitive action is audited**, including refusals — §29's log is
 *    where "did anyone try?" has to be answerable.
 */

/** bcrypt cost. 12 is the current sane default: ~250ms on commodity hardware. */
const BCRYPT_ROUNDS = 12;

export interface AdminAccount {
  readonly id: string;
  readonly displayName: string;
  readonly email: string;
  readonly role: AdminRole;
  readonly status: AdminStatus;
  readonly createdAt: string;
  readonly lastLoginAt: string | null;
}

export interface AdminSessionResult {
  readonly accessToken: string;
  readonly refreshToken: string;
  readonly expiresIn: number;
  readonly admin: AdminAccount;
}

interface AdminRow {
  id: string;
  display_name: string;
  email: string;
  email_normalized: string;
  phone_hash: string;
  password_hash: string;
  role: AdminRole;
  status: AdminStatus;
  failed_login_count: number;
  locked_until: unknown;
  last_login_at: unknown;
  created_at: unknown;
}

/**
 * Columns for an `AdminAccount`. `password_hash` is absent on purpose: the only
 * shapes this module returns are built from this list, so a hash cannot reach a
 * response body by accident.
 */
const ADMIN_COLUMNS = `id, display_name, email, email_normalized, role, status,
  failed_login_count, locked_until, last_login_at, created_at`;

function toAdminAccount(row: AdminRow): AdminAccount {
  return {
    id: row.id,
    displayName: row.display_name,
    email: row.email,
    role: row.role,
    status: row.status,
    createdAt: isoOrNull(row.created_at) ?? '',
    lastLoginAt: isoOrNull(row.last_login_at),
  };
}

// ── Password handling ───────────────────────────────────────────────────

export async function hashPassword(password: string): Promise<string> {
  assertPasswordAcceptable(password);
  return bcrypt.hash(password, BCRYPT_ROUNDS);
}

/**
 * §27's floor, checked in one place.
 *
 * Deliberately NOT a complexity rule ("one digit, one symbol"): those push
 * people toward `Password1!` and are actively counterproductive. Length is the
 * property that correlates with strength, and this is a password only ever
 * typed by staff into a dashboard.
 */
export function assertPasswordAcceptable(password: string): void {
  if (password.length < env.ADMIN_MIN_PASSWORD_LENGTH) {
    throw badRequest(
      'weak_password',
      `An administrator password must be at least ${env.ADMIN_MIN_PASSWORD_LENGTH} characters.`
    );
  }
  if (password.length > 200) {
    // bcrypt silently truncates beyond 72 bytes; refusing an absurd length is
    // friendlier than accepting a password whose tail does nothing.
    throw badRequest('weak_password', 'That password is too long.');
  }
}

/**
 * Compare a password, always doing the bcrypt work.
 *
 * A missing account is compared against a fixed dummy hash so the response time
 * does not reveal whether the address exists. This is a real side channel on an
 * endpoint that answers `invalid_credentials` for both cases.
 */
const DUMMY_HASH = '$2b$12$C6UzMDM.H6dfI/f/IKcEeO7ZBpVQvV9zPWJMoZbWqBBBBBBBBBBBBB';

async function passwordMatches(password: string, hash: string | undefined): Promise<boolean> {
  try {
    return await bcrypt.compare(password, hash ?? DUMMY_HASH);
  } catch {
    return false;
  }
}

// ── The audit log (§29) ─────────────────────────────────────────────────

export interface AuditEntry {
  readonly adminId: string | null;
  readonly adminEmail: string | null;
  readonly actorRole: AdminRole | null;
  readonly action: string;
  readonly targetType?: string | null;
  readonly targetId?: string | null;
  readonly outcome?: 'success' | 'denied' | 'failed';
  /** Never a credential: §30 forbids logging passwords, tokens and OTP codes. */
  readonly metadata?: Record<string, unknown> | null;
  readonly ipHash?: string | null;
}

/** Counted so a failing append is visible rather than merely printed once. */
let auditWriteFailures = 0;

export function auditFailureCount(): number {
  return auditWriteFailures;
}

/**
 * Append one audit row.
 *
 * It does NOT fail the request that caused it. That trade-off is deliberate and
 * worth stating: by the time this runs the action is already committed, so
 * throwing would report a failed suspension that actually happened — the
 * moderator would retry and suspend someone twice. A write failure is
 * therefore logged loudly, counted, and surfaced on the dashboard's overview,
 * which is where a broken audit trail gets noticed.
 */
export async function writeAudit(database: Queryable, entry: AuditEntry): Promise<void> {
  try {
    await database.query(
      `INSERT INTO admin_audit_logs
         (admin_id, admin_email, actor_role, action, target_type, target_id, outcome, metadata, ip_hash)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9)`,
      [
        entry.adminId,
        entry.adminEmail,
        entry.actorRole,
        entry.action,
        entry.targetType ?? null,
        entry.targetId ?? null,
        entry.outcome ?? 'success',
        entry.metadata === undefined || entry.metadata === null
          ? null
          : JSON.stringify(entry.metadata),
        entry.ipHash ?? null,
      ]
    );
  } catch (err) {
    auditWriteFailures += 1;
    console.error(
      `[audit] FAILED to append "${entry.action}" (target ${entry.targetType ?? '-'}/${
        entry.targetId ?? '-'
      }): ${(err as Error).message} — the action itself was already committed`
    );
  }
}

/** The audit view (§29), newest first. Append-only, so there is nothing else. */
export async function listAudit(
  database: Queryable,
  filter: { readonly adminId?: string | undefined; readonly limit?: number | undefined } = {}
): Promise<readonly AuditRecord[]> {
  const limit = Math.min(Math.max(filter.limit ?? 100, 1), 500);

  const rows = await database.query<{
    id: string;
    admin_id: string | null;
    admin_email: string | null;
    actor_role: AdminRole | null;
    action: string;
    target_type: string | null;
    target_id: string | null;
    outcome: 'success' | 'denied' | 'failed';
    metadata: unknown;
    created_at: unknown;
  }>(
    `SELECT id, admin_id, admin_email, actor_role, action, target_type, target_id,
            outcome, metadata, created_at
       FROM admin_audit_logs
      WHERE ($1::uuid IS NULL OR admin_id = $1::uuid)
      ORDER BY created_at DESC, id DESC
      LIMIT $2`,
    [filter.adminId ?? null, limit]
  );

  return rows.map((row) => ({
    id: row.id,
    adminId: row.admin_id,
    adminEmail: row.admin_email,
    actorRole: row.actor_role,
    action: row.action,
    targetType: row.target_type,
    targetId: row.target_id,
    outcome: row.outcome,
    metadata: (row.metadata as Record<string, unknown> | null) ?? null,
    createdAt: isoOrNull(row.created_at) ?? '',
  }));
}

export interface AuditRecord {
  readonly id: string;
  readonly adminId: string | null;
  readonly adminEmail: string | null;
  readonly actorRole: AdminRole | null;
  readonly action: string;
  readonly targetType: string | null;
  readonly targetId: string | null;
  readonly outcome: 'success' | 'denied' | 'failed';
  readonly metadata: Record<string, unknown> | null;
  readonly createdAt: string;
}

// ── Login (§21, §30) ────────────────────────────────────────────────────

export interface LoginInput {
  /** An email address or an E.164 mobile number; both are accepted (§21). */
  readonly identifier: string;
  readonly password: string;
  readonly ipHash?: string | null;
  readonly userAgent?: string | null;
}

function isE164(value: string): boolean {
  return /^\+[1-9]\d{6,14}$/.test(value);
}

/** Find an administrator by email or phone, without revealing which matched. */
async function findAdmin(
  database: Queryable,
  identifier: string
): Promise<AdminRow | null> {
  const trimmed = identifier.trim();

  if (isE164(trimmed)) {
    return database.queryOne<AdminRow>(
      `SELECT ${ADMIN_COLUMNS}, password_hash FROM admin_users WHERE phone_hash = $1`,
      [hashPhone(trimmed)]
    );
  }

  return database.queryOne<AdminRow>(
    `SELECT ${ADMIN_COLUMNS}, password_hash FROM admin_users WHERE email_normalized = $1`,
    [normalizeEmail(trimmed)]
  );
}

/**
 * Sign an administrator in (§21).
 *
 * The order matters. The lockout and the password check happen BEFORE anything
 * about the account's state is returned, so a disabled account and a wrong
 * password are indistinguishable to someone who does not know the password.
 */
export async function loginAdmin(
  database: Queryable,
  input: LoginInput
): Promise<AdminSessionResult> {
  const row = await findAdmin(database, input.identifier);

  const auditFor = (outcome: 'success' | 'denied' | 'failed', reason: string): AuditEntry => ({
    adminId: row?.id ?? null,
    adminEmail: row?.email ?? null,
    actorRole: row?.role ?? null,
    action: 'admin.login',
    targetType: 'admin',
    targetId: row?.id ?? null,
    outcome,
    metadata: { reason },
    ipHash: input.ipHash ?? null,
  });

  if (row && isoOrNull(row.locked_until) !== null) {
    const until = Date.parse(String(row.locked_until));
    if (Number.isFinite(until) && until > Date.now()) {
      await writeAudit(database, auditFor('denied', 'locked'));
      throw tooManyRequests(
        'admin_locked',
        'Too many failed sign-in attempts. Try again later.'
      );
    }
  }

  const ok = await passwordMatches(input.password, row?.password_hash);

  if (!row || !ok) {
    // The counter is only meaningful for a real account; a guessed address must
    // not create rows or locks, so this branch does nothing when `row` is null.
    if (row) {
      const failed = row.failed_login_count + 1;
      const locked = failed >= env.LOGIN_MAX_FAILED_ATTEMPTS;
      await database.query(
        `UPDATE admin_users
            SET failed_login_count = $2,
                locked_until = CASE WHEN $3::boolean THEN now() + ($4::int * interval '1 minute') ELSE locked_until END
          WHERE id = $1`,
        [row.id, locked ? 0 : failed, locked, env.LOGIN_LOCKOUT_MINUTES]
      );
      await writeAudit(
        database,
        auditFor(locked ? 'denied' : 'failed', locked ? 'lockout_triggered' : 'bad_password')
      );
    }
    throw unauthorized('invalid_credentials', 'That email or password is not correct.');
  }

  if (row.status === 'disabled') {
    // Reachable only with the CORRECT password, so this reveals nothing that
    // the caller has not already proved they are entitled to know.
    await writeAudit(database, auditFor('denied', 'disabled'));
    throw forbidden('admin_disabled', 'This administrator account is disabled.');
  }

  const session = await database.transaction(async (tx) => {
    await tx.query(
      `UPDATE admin_users
          SET failed_login_count = 0, locked_until = NULL, last_login_at = now()
        WHERE id = $1`,
      [row.id]
    );

    const { token, hash } = mintAdminRefreshToken();
    const inserted = await tx.query<{ id: string }>(
      `INSERT INTO admin_sessions (admin_id, refresh_token_hash, ip_hash, user_agent, expires_at)
       VALUES ($1, $2, $3, $4, $5)
       RETURNING id`,
      [
        row.id,
        hash,
        input.ipHash ?? null,
        input.userAgent?.slice(0, 400) ?? null,
        adminSessionExpiry(),
      ]
    );

    return { id: one(inserted).id, token };
  });

  await writeAudit(database, {
    ...auditFor('success', 'password'),
    metadata: { sessionId: session.id },
  });

  return {
    accessToken: signAdminAccessToken({ sub: row.id, sid: session.id }, row.role),
    refreshToken: session.token,
    expiresIn: accessTokenTtlSeconds(env.ADMIN_ACCESS_TOKEN_TTL),
    admin: toAdminAccount(row),
  };
}

/** Mirrors the user-side helper: a duration string to seconds. */
function accessTokenTtlSeconds(raw: string): number {
  const match = /^(\d+)\s*([smhd])?$/.exec(raw.trim());
  if (!match) return 600;
  const amount = Number(match[1]);
  const unit = match[2] ?? 's';
  const multiplier = unit === 'm' ? 60 : unit === 'h' ? 3600 : unit === 'd' ? 86400 : 1;
  return amount * multiplier;
}

/**
 * Rotate an admin refresh token (§30).
 *
 * Reuse detection matches the user side: a token presented twice means the
 * stored one was copied, so every session for that administrator is revoked
 * rather than quietly rotated.
 */
export async function refreshAdminSession(
  database: Queryable,
  refreshToken: string,
  ipHash?: string | null
): Promise<AdminSessionResult> {
  const hash = hashToken(refreshToken);

  const row = await database.queryOne<{
    id: string;
    admin_id: string;
    revoked_at: unknown;
    expires_at: unknown;
    status: AdminStatus;
    email: string;
    display_name: string;
    role: AdminRole;
    created_at: unknown;
    last_login_at: unknown;
  }>(
    `SELECT s.id, s.admin_id, s.revoked_at, s.expires_at,
            a.status, a.email, a.display_name, a.role, a.created_at, a.last_login_at
       FROM admin_sessions s
       JOIN admin_users a ON a.id = s.admin_id
      WHERE s.refresh_token_hash = $1`,
    [hash]
  );

  if (!row) {
    // Not the current token. It may be one this session already rotated away
    // from, which is evidence of a copy rather than a stale client — so every
    // session for that administrator is revoked rather than quietly refused.
    const reused = await database.queryOne<{ admin_id: string; email: string; role: AdminRole }>(
      `SELECT s.admin_id, a.email, a.role
         FROM admin_sessions s JOIN admin_users a ON a.id = s.admin_id
        WHERE s.previous_refresh_token_hash = $1
        LIMIT 1`,
      [hash]
    );

    if (reused) {
      const revoked = await database.query(
        `UPDATE admin_sessions
            SET revoked_at = now(), revoked_reason = 'refresh_token_reuse_detected'
          WHERE admin_id = $1 AND revoked_at IS NULL`,
        [reused.admin_id]
      );
      await writeAudit(database, {
        adminId: reused.admin_id,
        adminEmail: reused.email,
        actorRole: reused.role,
        action: 'admin.session.reuse_detected',
        targetType: 'admin',
        targetId: reused.admin_id,
        outcome: 'denied',
        metadata: { revokedSessions: revoked.length },
        ipHash: ipHash ?? null,
      });
    }

    throw unauthorized('invalid_refresh_token', 'Sign in again.');
  }

  if (isoOrNull(row.revoked_at) !== null) {
    throw unauthorized('session_revoked', 'This session has been revoked. Sign in again.');
  }

  const expires = Date.parse(String(row.expires_at));
  if (!Number.isFinite(expires) || expires <= Date.now()) {
    throw unauthorized('session_expired', 'This session has expired. Sign in again.');
  }

  if (row.status === 'disabled') {
    await writeAudit(database, {
      adminId: row.admin_id,
      adminEmail: row.email,
      actorRole: row.role,
      action: 'admin.session.refresh',
      targetType: 'admin',
      targetId: row.admin_id,
      outcome: 'denied',
      metadata: { reason: 'disabled' },
      ipHash: ipHash ?? null,
    });
    throw forbidden('admin_disabled', 'This administrator account is disabled.');
  }

  return database.transaction(async (tx) => {
    const { token, hash: nextHash } = mintAdminRefreshToken();

    // Rotated in place — one session row per device, so revoking "the session
    // that leaked" does not require guessing which of several rows it is —
    // while the SUPERSEDED hash is retained beside it. That retention is what
    // makes the reuse check above possible at all.
    await tx.query(
      `UPDATE admin_sessions
          SET previous_refresh_token_hash = refresh_token_hash,
              refresh_token_hash = $2,
              last_used_at = now(),
              expires_at = $3
        WHERE id = $1`,
      [row.id, nextHash, adminSessionExpiry()]
    );

    return {
      accessToken: signAdminAccessToken({ sub: row.admin_id, sid: row.id }, row.role),
      refreshToken: token,
      expiresIn: accessTokenTtlSeconds(env.ADMIN_ACCESS_TOKEN_TTL),
      admin: {
        id: row.admin_id,
        displayName: row.display_name,
        email: row.email,
        role: row.role,
        status: row.status,
        createdAt: isoOrNull(row.created_at) ?? '',
        lastLoginAt: isoOrNull(row.last_login_at),
      },
    };
  });
}

/** Revoke one admin session (logout) by its refresh token. */
export async function logoutAdmin(database: Queryable, refreshToken: string): Promise<boolean> {
  const rows = await database.query(
    `UPDATE admin_sessions SET revoked_at = now(), revoked_reason = 'logout'
      WHERE refresh_token_hash = $1 AND revoked_at IS NULL
      RETURNING id`,
    [hashToken(refreshToken)]
  );
  return rows.length > 0;
}

/** Kill every session for an administrator (§27's "reset/revoke sessions"). */
export async function revokeAdminSessions(
  database: Queryable,
  adminId: string,
  reason: string
): Promise<number> {
  const rows = await database.query(
    `UPDATE admin_sessions SET revoked_at = now(), revoked_reason = $2
      WHERE admin_id = $1 AND revoked_at IS NULL
      RETURNING id`,
    [adminId, reason]
  );
  return rows.length;
}

/** Is this admin session live, and is its administrator still usable? (§32) */
export async function isAdminSessionLive(
  database: Queryable,
  adminId: string,
  sessionId: string
): Promise<boolean> {
  const row = await database.queryOne(
    `SELECT 1 FROM admin_sessions s
       JOIN admin_users a ON a.id = s.admin_id
      WHERE s.id = $1 AND s.admin_id = $2
        AND s.revoked_at IS NULL
        AND s.expires_at > now()
        AND a.status = 'active'`,
    [sessionId, adminId]
  );
  return row !== null;
}

// ── Administrator accounts (§27) ────────────────────────────────────────

export async function loadAdmin(
  database: Queryable,
  adminId: string
): Promise<AdminAccount | null> {
  const row = await database.queryOne<AdminRow>(
    `SELECT ${ADMIN_COLUMNS} FROM admin_users WHERE id = $1`,
    [adminId]
  );
  return row ? toAdminAccount(row) : null;
}

export interface CreateAdminInput {
  readonly displayName: string;
  readonly email: string;
  readonly phone?: string | undefined;
  readonly role: AdminRole;
  readonly password: string;
  readonly createdByAdminId?: string | null;
}

/**
 * Create an administrator (§27).
 *
 * Only ever called by a route that has already checked `admins.manage`, or by
 * the bootstrap script. §21 forbids a public "create admin" endpoint, and §48
 * forbids the user registration flow from reaching this: the check is at the
 * call sites, and this function's own guard is the role value being valid.
 */
export async function createAdmin(
  database: Queryable,
  input: CreateAdminInput
): Promise<AdminAccount> {
  if (!ADMIN_ROLES.includes(input.role)) {
    throw badRequest('invalid_role', 'That is not an administrator role.');
  }
  const displayName = input.displayName.trim();
  if (displayName.length === 0 || displayName.length > 80) {
    throw badRequest('invalid_name', 'A name must be between 1 and 80 characters.');
  }

  const email = input.email.trim();
  if (!isValidEmail(email)) {
    throw badRequest('invalid_email', 'That does not look like an email address.');
  }

  const phone = input.phone?.trim() ?? '';
  if (!isE164(phone)) {
    // Required, not optional: §21 wants sign-in by email OR mobile, and §27
    // lists a mobile number among what creating an administrator collects.
    throw badRequest('invalid_phone', 'A mobile number in E.164 format is required.');
  }

  const passwordHash = await hashPassword(input.password);

  // A unique violation is turned into a wordable conflict rather than a 500,
  // and the two columns are checked separately so the message can say WHICH
  // detail is taken without echoing the value.
  const clash = await database.queryOne<{ email_normalized: string | null; phone_hash: string | null }>(
    `SELECT
       (SELECT email_normalized FROM admin_users WHERE email_normalized = $1) AS email_normalized,
       (SELECT phone_hash FROM admin_users WHERE phone_hash = $2) AS phone_hash`,
    [normalizeEmail(email), hashPhone(phone)]
  );
  if (clash?.email_normalized) throw conflict('email_taken', 'An administrator already uses that email.');
  if (clash?.phone_hash) throw conflict('phone_taken', 'An administrator already uses that mobile number.');

  const inserted = await database.query<AdminRow>(
    `INSERT INTO admin_users
       (display_name, email, email_normalized, phone_hash, password_hash, role, created_by_admin_id,
        password_changed_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, now())
     RETURNING ${ADMIN_COLUMNS}`,
    [
      displayName,
      email,
      normalizeEmail(email),
      hashPhone(phone),
      passwordHash,
      input.role,
      input.createdByAdminId ?? null,
    ]
  );

  return toAdminAccount(one(inserted));
}

export async function listAdmins(database: Queryable): Promise<readonly AdminAccount[]> {
  const rows = await database.query<AdminRow>(
    `SELECT ${ADMIN_COLUMNS} FROM admin_users ORDER BY created_at ASC`
  );
  return rows.map(toAdminAccount);
}

/**
 * Enable or disable an administrator (§27).
 *
 * Disabling also revokes their sessions, in the same transaction: an access
 * token is valid for minutes, and "disabled" that leaves the dashboard working
 * until it expires is not disabled. `requireAdmin` re-reads the status on every
 * request too, so the two together make it immediate.
 */
export async function setAdminStatus(
  database: Queryable,
  adminId: string,
  status: AdminStatus
): Promise<{ readonly admin: AdminAccount; readonly sessionsRevoked: number }> {
  return database.transaction(async (tx) => {
    // The boolean parameter keeps `$2` typed once: comparing one parameter as
    // `admin_status` and as text makes Postgres unable to deduce a single type
    // for it ("inconsistent types deduced for parameter").
    const updated = await tx.query<AdminRow>(
      `UPDATE admin_users
          SET status = $2::admin_status,
              disabled_at = CASE WHEN $3::boolean THEN now() ELSE NULL END
        WHERE id = $1
        RETURNING ${ADMIN_COLUMNS}`,
      [adminId, status, status === 'disabled']
    );
    if (updated.length === 0) throw notFound('admin_not_found');

    const revoked =
      status === 'disabled' ? await revokeAdminSessions(tx, adminId, 'admin_disabled') : 0;

    return { admin: toAdminAccount(one(updated)), sessionsRevoked: revoked };
  });
}

/**
 * Change an administrator's role (§27).
 *
 * Refuses to remove the LAST super administrator: §27 makes SUPER_ADMIN the
 * only role that can create administrators, so demoting the last one would
 * leave a deployment nobody can administer — a self-inflicted lockout that no
 * API call could undo.
 */
export async function setAdminRole(
  database: Queryable,
  adminId: string,
  role: AdminRole
): Promise<AdminAccount> {
  if (!ADMIN_ROLES.includes(role)) {
    throw badRequest('invalid_role', 'That is not an administrator role.');
  }

  return database.transaction(async (tx) => {
    const current = await tx.queryOne<{ role: AdminRole }>(
      `SELECT role FROM admin_users WHERE id = $1`,
      [adminId]
    );
    if (!current) throw notFound('admin_not_found');

    if (current.role === 'super_admin' && role !== 'super_admin') {
      const remaining = await tx.queryOne<{ count: string }>(
        `SELECT count(*)::text AS count FROM admin_users
          WHERE role = 'super_admin' AND status = 'active' AND id <> $1`,
        [adminId]
      );
      if (Number(remaining?.count ?? 0) === 0) {
        throw conflict(
          'last_super_admin',
          'This is the only active super administrator. Promote another before changing this one.'
        );
      }
    }

    const updated = await tx.query<AdminRow>(
      `UPDATE admin_users SET role = $2 WHERE id = $1 RETURNING ${ADMIN_COLUMNS}`,
      [adminId, role]
    );
    return toAdminAccount(one(updated));
  });
}

/**
 * Change an administrator's own password.
 *
 * Not exposed as an admin-API route in M6: §27 lists reset/revoke, and a
 * password reset for someone else would need a delivery channel this milestone
 * does not have. The current password is still required, so the function is
 * safe to expose the moment an endpoint is added.
 */
export async function changeOwnPassword(
  database: Queryable,
  adminId: string,
  currentPassword: string,
  newPassword: string
): Promise<void> {
  const row = await database.queryOne<{ password_hash: string }>(
    `SELECT password_hash FROM admin_users WHERE id = $1`,
    [adminId]
  );
  if (!row) throw notFound('admin_not_found');

  if (!(await passwordMatches(currentPassword, row.password_hash))) {
    throw unauthorized('invalid_credentials', 'That password is not correct.');
  }

  const hash = await hashPassword(newPassword);
  await database.transaction(async (tx) => {
    await tx.query(
      `UPDATE admin_users SET password_hash = $2, password_changed_at = now() WHERE id = $1`,
      [adminId, hash]
    );
    // A password change ends every other session: the usual reason to change
    // one is that somebody else may have it.
    await revokeAdminSessions(tx, adminId, 'password_changed');
  });
}
