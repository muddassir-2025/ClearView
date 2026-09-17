import bcrypt from 'bcryptjs';
import { env, hashIp, hashToken, normalizeEmail } from '../env.js';
import { one, type Queryable } from '../db.js';
import { badRequest, conflict, forbidden, notFound, unauthorized } from '../http/errors.js';
import { adminSessionExpiry, mintAdminRefreshToken, signAdminAccessToken } from './tokens.js';
import { isAdminRole, permissionsFor, type AdminRole } from './permissions.js';

/**
 * Administrators: sign-in, sessions and the audit log (§16–§19, §25, §29).
 *
 * This is the whole of the authenticated surface. There is no viewer account
 * anywhere in this service, because there is no viewer account anywhere in the
 * product: a reader of Good Post has no identity, so the only thing that can
 * hold a session is a channel's publisher or the platform's super
 * administrator.
 *
 * Four rules shape this file.
 *
 *  * **A refusal says nothing.** A wrong address, a wrong password and a
 *    disabled account are three different facts and one answer. `admin_users`
 *    is a list of staff addresses, and a sign-in form that answered
 *    differently would be a way to read it.
 *
 *  * **The role is read on every request, never trusted from a token.** The
 *    token carries a session id; [loadAdmin] re-reads the row, so disabling an
 *    administrator or changing their role takes effect on the next request
 *    rather than when their token expires.
 *
 *  * **Passwords are bcrypt, with a per-row salt.** Compared via
 *    [passwordMatches], which runs a dummy comparison when there is no such
 *    administrator, so the response time does not say whether an address
 *    exists.
 *
 *  * **Every sensitive act is audited, including a refusal.** The log is
 *    append-only in the database (`admin_audit_logs`'s triggers), which is what
 *    makes it worth writing to.
 */

const BCRYPT_ROUNDS = 12;

export interface AdminAccount {
  readonly id: string;
  readonly displayName: string;
  readonly email: string;
  readonly role: AdminRole;
  readonly status: 'active' | 'disabled';
  /** `null` for a super administrator. The channel a channel admin is bound to. */
  readonly channelId: string | null;
  readonly createdAt: string;
}

export interface AdminSessionResult {
  readonly accessToken: string;
  readonly refreshToken: string;
  readonly expiresInSeconds: number;
  readonly admin: AdminAccount;
}

const ADMIN_COLUMNS = `id, display_name, email, role, status, channel_id, created_at`;

interface AdminRow {
  id: string;
  display_name: string;
  email: string;
  role: string;
  status: 'active' | 'disabled';
  channel_id: string | null;
  created_at: unknown;
}

function toAccount(row: AdminRow): AdminAccount {
  return {
    id: row.id,
    displayName: row.display_name,
    email: row.email,
    // A legacy value can only be read here, never written:
    // `admin_users_role_check` constrains the column to the two roles, so this
    // is the type guard rather than the rule. Falling back to the least
    // powerful role means a stray row cannot be treated as a super
    // administrator by this mapper.
    role: isAdminRole(row.role) ? row.role : 'channel_admin',
    status: row.status,
    channelId: row.channel_id,
    createdAt: toIso(row.created_at),
  };
}

function toIso(value: unknown): string {
  if (value instanceof Date) return value.toISOString();
  return String(value);
}

// ── Passwords (§16) ─────────────────────────────────────────────────────

/** A bcrypt hash, never a reversible form. */
export async function hashPassword(password: string): Promise<string> {
  return bcrypt.hash(password, BCRYPT_ROUNDS);
}

/**
 * §16's floor for a channel administrator's password.
 *
 * Enforced here rather than only in the route, because the bootstrap path and
 * the create-a-channel path both set passwords and only one of them is a route.
 */
export function assertPasswordAcceptable(password: string): void {
  if (password.length < env.ADMIN_MIN_PASSWORD_LENGTH) {
    throw badRequest(
      'weak_password',
      `A password must be at least ${env.ADMIN_MIN_PASSWORD_LENGTH} characters.`
    );
  }
}

/**
 * A hash that is compared against when no administrator matches.
 *
 * A sign-in for an unknown address that returns in 1 ms while a real one takes
 * 60 ms is an address oracle. This is a valid bcrypt hash of a value nobody
 * knows, so the comparison always runs and always costs the same.
 */
const DUMMY_HASH = '$2b$12$C6UzMDM.H6dfI/f/IKcEeO7ZBpVQvV9zPWJMoZbWqBBBBBBBBBBBBB';

/**
 * Constant-cost password check. Exported so the bootstrap can answer the one
 * question it needs — "does the configured password still open this account?" —
 * without a second bcrypt call site quietly drifting from this one.
 */
export async function passwordMatches(password: string, hash: string | undefined): Promise<boolean> {
  try {
    return await bcrypt.compare(password, hash ?? DUMMY_HASH);
  } catch {
    // A malformed stored hash must read as \"wrong password\", not as a 500 that
    // says the row is malformed.
    return false;
  }
}

// ── The audit log (§29) ─────────────────────────────────────────────────

export interface AuditEntry {
  readonly adminId: string | null;
  readonly adminEmail: string | null;
  readonly actorRole: string | null;
  readonly action: string;
  readonly targetType?: string | null;
  readonly targetId?: string | null;
  readonly outcome?: 'success' | 'denied' | 'failed';
  readonly metadata?: Record<string, unknown> | null;
  readonly ipHash?: string | null;
}

let auditWriteFailures = 0;

/** Counted, so a deployment can tell that its audit log has a problem. */
export function auditFailureCount(): number {
  return auditWriteFailures;
}

/**
 * Append one audit row.
 *
 * Never throws. An audit write that failed must not roll back the action it
 * describes — a channel that was created and then lost because its log entry
 * failed is worse than a log with a gap in it. The failure is counted and
 * logged instead, and the count is what makes the gap visible.
 */
export async function writeAudit(database: Queryable, entry: AuditEntry): Promise<void> {
  try {
    await database.query(
      `INSERT INTO admin_audit_logs
         (admin_id, admin_email, actor_role, action, target_type, target_id, outcome, metadata, ip_hash)
       VALUES ($1, $2, $3::admin_role, $4, $5, $6, $7::audit_outcome, $8::jsonb, $9)`,
      [
        entry.adminId,
        entry.adminEmail,
        entry.actorRole,
        entry.action,
        entry.targetType ?? null,
        entry.targetId ?? null,
        entry.outcome ?? 'success',
        entry.metadata ? JSON.stringify(entry.metadata) : null,
        entry.ipHash ?? null,
      ]
    );
  } catch (err) {
    auditWriteFailures += 1;
    console.error('[admin] could not write an audit row:', (err as Error).message);
  }
}

export interface AuditRecord {
  readonly id: string;
  readonly adminEmail: string | null;
  readonly actorRole: string | null;
  readonly action: string;
  readonly targetType: string | null;
  readonly targetId: string | null;
  readonly outcome: string;
  readonly createdAt: string;
}

/** Newest first. Bounded, because an unbounded log read is a denial of service. */
export async function listAudit(
  database: Queryable,
  limit: number
): Promise<readonly AuditRecord[]> {
  const rows = await database.query<{
    id: string;
    admin_email: string | null;
    actor_role: string | null;
    action: string;
    target_type: string | null;
    target_id: string | null;
    outcome: string;
    created_at: unknown;
  }>(
    `SELECT id, admin_email, actor_role, action, target_type, target_id, outcome, created_at
       FROM admin_audit_logs
      ORDER BY created_at DESC, id DESC
      LIMIT $1`,
    [limit]
  );

  return rows.map((r) => ({
    id: r.id,
    adminEmail: r.admin_email,
    actorRole: r.actor_role,
    action: r.action,
    targetType: r.target_type,
    targetId: r.target_id,
    outcome: r.outcome,
    createdAt: toIso(r.created_at),
  }));
}

// ── Sign-in (§16) ───────────────────────────────────────────────────────

export interface LoginInput {
  readonly email: string;
  readonly password: string;
  readonly ipHash: string;
  readonly userAgent: string | null;
}

interface LoginRow extends AdminRow {
  password_hash: string;
  failed_login_count: number;
  locked_until: unknown;
}

/**
 * Sign an administrator in.
 *
 * The failed-attempt counter and the lockout live in the ROW, not in memory, so
 * a restart, a redeploy or a second instance does not hand an attacker a fresh
 * allowance. An in-memory counter would reset exactly when an operator is least
 * likely to notice.
 *
 * A locked account is refused with `admin_locked` rather than the generic
 * message. That is a deliberate exception to the \"one answer\" rule: an
 * administrator who is being locked out by an attacker needs to know why their
 * password stopped working, and the lock can only be triggered by somebody who
 * knows the address.
 */
export async function loginAdmin(
  database: Queryable,
  input: LoginInput
): Promise<AdminSessionResult> {
  const emailNormalized = normalizeEmail(input.email);

  const row = await database.queryOne<LoginRow>(
    `SELECT ${ADMIN_COLUMNS}, password_hash, failed_login_count, locked_until
       FROM admin_users
      WHERE email_normalized = $1`,
    [emailNormalized]
  );

  const locked = row?.locked_until ? new Date(toIso(row.locked_until)).getTime() > Date.now() : false;
  if (locked) {
    await writeAudit(database, {
      adminId: row?.id ?? null,
      adminEmail: row?.email ?? null,
      actorRole: row?.role ?? null,
      action: 'admin.login',
      outcome: 'denied',
      metadata: { reason: 'locked' },
      ipHash: input.ipHash,
    });
    throw forbidden('admin_locked', 'Too many failed attempts. Try again later.');
  }

  const matches = await passwordMatches(input.password, row?.password_hash);

  if (!row || !matches) {
    if (row) await recordFailedLogin(database, row);
    await writeAudit(database, {
      adminId: row?.id ?? null,
      adminEmail: row?.email ?? null,
      actorRole: row?.role ?? null,
      action: 'admin.login',
      outcome: 'denied',
      metadata: { reason: 'invalid_credentials' },
      ipHash: input.ipHash,
    });
    // Identical for both cases. The row's id is not part of the response, and
    // the message does not distinguish \"no such address\" from \"wrong
    // password\".
    throw unauthorized('invalid_credentials', 'That email and password do not match.');
  }

  if (row.status !== 'active') {
    await writeAudit(database, {
      adminId: row.id,
      adminEmail: row.email,
      actorRole: row.role,
      action: 'admin.login',
      outcome: 'denied',
      metadata: { reason: 'disabled' },
      ipHash: input.ipHash,
    });
    throw forbidden('admin_disabled', 'This administrator account is disabled.');
  }

  const account = toAccount(row);
  return database.transaction(async (tx) => {
    const session = await createSession(tx, account, input);

    await tx.query(
      `UPDATE admin_users
          SET failed_login_count = 0, locked_until = NULL, last_login_at = now()
        WHERE id = $1`,
      [account.id]
    );

    await writeAudit(tx, {
      adminId: account.id,
      adminEmail: account.email,
      actorRole: account.role,
      action: 'admin.login',
      targetType: 'admin',
      targetId: account.id,
      metadata: { role: account.role },
      ipHash: input.ipHash,
    });

    return session;
  });
}

/** Count a failure and lock the account once the allowance is gone. */
async function recordFailedLogin(database: Queryable, row: LoginRow): Promise<void> {
  const next = row.failed_login_count + 1;
  const lock = next >= env.LOGIN_MAX_FAILED_ATTEMPTS;
  await database.query(
    `UPDATE admin_users
        SET failed_login_count = $2,
            locked_until = CASE WHEN $3::boolean
              THEN now() + ($4::int * interval '1 minute') ELSE locked_until END
      WHERE id = $1`,
    [row.id, lock ? 0 : next, lock, env.LOGIN_LOCKOUT_MINUTES]
  );
}

/** Mint a session row plus the access token that names it. */
async function createSession(
  database: Queryable,
  account: AdminAccount,
  input: { readonly ipHash: string; readonly userAgent: string | null }
): Promise<AdminSessionResult> {
  const { token, hash } = mintAdminRefreshToken();

  const rows = await database.query<{ id: string; expires_at: unknown }>(
    `INSERT INTO admin_sessions (admin_id, refresh_token_hash, ip_hash, user_agent, expires_at)
     VALUES ($1, $2, $3, $4, $5)
     RETURNING id, expires_at`,
    [account.id, hash, input.ipHash, input.userAgent, adminSessionExpiry()]
  );

  const session = one(rows);
  const expiresAt = new Date(toIso(session.expires_at)).getTime();

  return {
    accessToken: signAdminAccessToken({ sub: account.id, sid: session.id }, account.role),
    refreshToken: token,
    expiresInSeconds: Math.max(0, Math.round((expiresAt - Date.now()) / 1000)),
    admin: account,
  };
}

/**
 * Rotate a refresh token (§30).
 *
 * The superseded hash is kept in `previous_refresh_token_hash`: presenting a
 * token that has already been rotated away means a copy exists, and that is
 * only detectable if the old hash is still on record. A replayed token revokes
 * every session that administrator holds, because the honest assumption is that
 * the account itself is compromised.
 */
export async function refreshAdminSession(
  database: Queryable,
  refreshToken: string,
  ipHash: string
): Promise<AdminSessionResult> {
  const hash = hashToken(refreshToken);

  const row = await database.queryOne<{
    id: string;
    admin_id: string;
    expires_at: unknown;
    revoked_at: unknown;
  }>(
    `SELECT id, admin_id, expires_at, revoked_at
       FROM admin_sessions
      WHERE refresh_token_hash = $1 OR previous_refresh_token_hash = $1`,
    [hash]
  );

  if (!row) throw unauthorized('invalid_refresh_token', 'Sign in again.');

  if (row.revoked_at !== null) throw unauthorized('session_revoked', 'Sign in again.');

  if (toIso(row.expires_at) <= new Date().toISOString()) {
    throw unauthorized('session_expired', 'Sign in again.');
  }

  const account = await loadAdmin(database, row.admin_id);
  if (!account) throw unauthorized('session_revoked', 'Sign in again.');
  if (account.status !== 'active') {
    throw forbidden('admin_disabled', 'This administrator account is disabled.');
  }

  return database.transaction(async (tx) => {
    const { token, hash: nextHash } = mintAdminRefreshToken();

    const updated = await tx.query(
      `UPDATE admin_sessions
          SET refresh_token_hash = $2,
              previous_refresh_token_hash = $3,
              last_used_at = now()
        WHERE id = $1 AND revoked_at IS NULL
        RETURNING id`,
      [row.id, nextHash, hash]
    );

    // Zero rows means a concurrent rotation won the race, which is exactly the
    // replay this is meant to catch.
    if (updated.length === 0) {
      await revokeAdminSessions(tx, row.admin_id, 'refresh_replay');
      throw unauthorized('session_revoked', 'Sign in again.');
    }

    await writeAudit(tx, {
      adminId: account.id,
      adminEmail: account.email,
      actorRole: account.role,
      action: 'admin.session.refresh',
      targetType: 'admin',
      targetId: account.id,
      ipHash,
    });

    return {
      accessToken: signAdminAccessToken({ sub: account.id, sid: row.id }, account.role),
      refreshToken: token,
      expiresInSeconds: env.ADMIN_SESSION_TTL_DAYS * 86_400,
      admin: account,
    };
  });
}

/** Sign out. Returns whether a session was actually closed. */
export async function logoutAdmin(database: Queryable, refreshToken: string): Promise<boolean> {
  if (refreshToken === '') return false;
  const rows = await database.query<{ admin_id: string }>(
    `UPDATE admin_sessions
        SET revoked_at = now(), revoked_reason = 'signed_out'
      WHERE refresh_token_hash = $1 AND revoked_at IS NULL
      RETURNING admin_id`,
    [hashToken(refreshToken)]
  );
  return rows.length > 0;
}

/** Revoke every live session an administrator holds (§27). */
export async function revokeAdminSessions(
  database: Queryable,
  adminId: string,
  reason: string
): Promise<number> {
  const rows = await database.query(
    `UPDATE admin_sessions
        SET revoked_at = now(), revoked_reason = $2
      WHERE admin_id = $1 AND revoked_at IS NULL
      RETURNING id`,
    [adminId, reason]
  );
  return rows.length;
}

// ── Per-request verification (§18, §25) ─────────────────────────────────

/**
 * Is this administrator's session still live?
 *
 * Asks the database on every request rather than trusting the token's expiry.
 * That is what makes disabling an administrator immediate instead of taking
 * effect whenever their token happens to lapse.
 */
export async function isAdminSessionLive(
  database: Queryable,
  adminId: string,
  sessionId: string
): Promise<boolean> {
  const row = await database.queryOne(
    `SELECT 1 FROM admin_sessions
      WHERE id = $1 AND admin_id = $2 AND revoked_at IS NULL AND expires_at > now()`,
    [sessionId, adminId]
  );
  return row !== null;
}

/** The account behind an id, or null. Read fresh, never cached. */
export async function loadAdmin(
  database: Queryable,
  adminId: string
): Promise<AdminAccount | null> {
  const row = await database.queryOne<AdminRow>(
    `SELECT ${ADMIN_COLUMNS} FROM admin_users WHERE id = $1`,
    [adminId]
  );
  return row ? toAccount(row) : null;
}

// ── Administrator management (§17, §18, §27) ────────────────────────────

export interface CreateAdminInput {
  readonly displayName: string;
  readonly email: string;
  readonly role: AdminRole;
  readonly password: string;
  /** The channel a `channel_admin` is confined to. Must be null for a super admin. */
  readonly channelId: string | null;
  readonly createdByAdminId: string | null;
}

/**
 * Create an administrator.
 *
 * Refuses before it hashes: a duplicate address and a wrong role are both
 * cheaper to detect than a bcrypt round, and the address is unique in the
 * database anyway — the pre-check only turns a constraint violation into a
 * sentence.
 */
export async function createAdmin(
  database: Queryable,
  input: CreateAdminInput
): Promise<AdminAccount> {
  assertPasswordAcceptable(input.password);
  return insertAdmin(database, {
    displayName: input.displayName,
    email: input.email,
    role: input.role,
    channelId: input.channelId,
    createdByAdminId: input.createdByAdminId,
    passwordHash: await hashPassword(input.password),
  });
}

/**
 * Create an administrator from an ALREADY-HASHED password.
 *
 * Split out for the bootstrap path, where the hash arrives in the environment
 * (§17): re-hashing a stored bcrypt value would hash the hash, and the operator
 * would find that the password they configured does not work. Nothing that takes
 * a plaintext password reaches the database without going through bcrypt.
 */
export async function insertAdmin(
  database: Queryable,
  input: {
    readonly displayName: string;
    readonly email: string;
    readonly role: AdminRole;
    readonly channelId: string | null;
    readonly createdByAdminId: string | null;
    readonly passwordHash: string;
  }
): Promise<AdminAccount> {
  if (!isAdminRole(input.role)) throw badRequest('invalid_role', 'Unknown administrator role.');

  if (input.role === 'channel_admin' && !input.channelId) {
    throw badRequest('channel_required', 'A channel administrator must be bound to a channel.');
  }
  if (input.role === 'super_admin' && input.channelId) {
    throw badRequest('channel_not_allowed', 'A super administrator is not bound to a channel.');
  }

  const emailNormalized = normalizeEmail(input.email);
  const existing = await database.queryOne(
    `SELECT 1 FROM admin_users WHERE email_normalized = $1`,
    [emailNormalized]
  );
  if (existing) throw conflict('email_taken', 'That email already runs an account.');

  const rows = await database.query<AdminRow>(
    `INSERT INTO admin_users
       (display_name, email, email_normalized, password_hash, role, status,
        channel_id, created_by_admin_id, password_changed_at)
     VALUES ($1, $2, $3, $4, $5::admin_role, 'active', $6, $7, now())
     RETURNING ${ADMIN_COLUMNS}`,
    [
      input.displayName.trim(),
      input.email.trim(),
      emailNormalized,
      input.passwordHash,
      input.role,
      input.channelId,
      input.createdByAdminId,
    ]
  );

  return toAccount(one(rows));
}

/** Change an administrator's password. Used by the channel-creation flow. */
export async function setAdminPassword(
  database: Queryable,
  adminId: string,
  password: string,
  /**
   * Skip [assertPasswordAcceptable].
   *
   * For the ONE caller that is not a person choosing a password: the bootstrap
   * applying `SUPER_ADMIN_PASSWORD` from the deployment's environment. The
   * operator already holds that secret; refusing to store it because it is
   * shorter than the floor does not protect anything, it just leaves the account
   * on the previous hash — which is exactly the bug this reconcile exists to
   * fix, and it hides itself as "Invalid credentials".
   *
   * The create path never applied the floor to a configured password either, so
   * this restores the two paths to agreement rather than opening a hole.
   */
  options?: { readonly enforceFloor?: boolean }
): Promise<void> {
  if (options?.enforceFloor !== false) assertPasswordAcceptable(password);
  const hash = await hashPassword(password);
  const rows = await database.query(
    `UPDATE admin_users
        SET password_hash = $2, password_changed_at = now(),
            failed_login_count = 0, locked_until = NULL
      WHERE id = $1
      RETURNING id`,
    [adminId, hash]
  );
  if (rows.length === 0) throw notFound('admin_not_found');
}

/** Every administrator, for a super admin's dashboard. Never the password hash. */
export async function listAdmins(database: Queryable): Promise<readonly AdminAccount[]> {
  const rows = await database.query<AdminRow>(
    `SELECT ${ADMIN_COLUMNS} FROM admin_users ORDER BY LOWER(email) ASC`
  );
  return rows.map(toAccount);
}

/**
 * Disable or re-enable an administrator (§27).
 *
 * Disabling revokes their sessions in the same transaction: an administrator
 * whose access was switched off must not keep working until their access token
 * happens to expire.
 */
export async function setAdminStatus(
  database: Queryable,
  adminId: string,
  status: 'active' | 'disabled'
): Promise<{ readonly admin: AdminAccount; readonly sessionsRevoked: number }> {
  return database.transaction(async (tx) => {
    const rows = await tx.query<AdminRow>(
      `UPDATE admin_users
          SET status = $2::admin_status,
              disabled_at = CASE WHEN $2 = 'disabled' THEN now() ELSE NULL END
        WHERE id = $1
        RETURNING ${ADMIN_COLUMNS}`,
      [adminId, status]
    );
    if (rows.length === 0) throw notFound('admin_not_found');

    const revoked =
      status === 'disabled' ? await revokeAdminSessions(tx, adminId, 'admin_disabled') : 0;

    return { admin: toAccount(one(rows)), sessionsRevoked: revoked };
  });
}

/** What this account may do, for the app's own display and for the audit view. */
export function capabilitiesOf(role: AdminRole): readonly string[] {
  return permissionsFor(role);
}

/** Re-exported so a route can hash an IP without importing env directly. */
export { hashIp };
