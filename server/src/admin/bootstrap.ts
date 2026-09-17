import { env } from '../env.js';
import type { Queryable } from '../db.js';
import { badRequest } from '../http/errors.js';
import { hashPassword, insertAdmin, loadAdmin, setAdminPassword, writeAudit } from './service.js';

/**
 * The first administrator (§17).
 *
 * §17 asks for exactly one initial SUPER_ADMIN, whose credentials come from the
 * SERVER's environment and never from the Android app. This is that: a single
 * account, provisioned from `SUPER_ADMIN_EMAIL` and a password that is already
 * hashed (`SUPER_ADMIN_PASSWORD_HASH`) or hashed here from
 * `SUPER_ADMIN_PASSWORD`.
 *
 * Two properties matter.
 *
 *  * **It is idempotent, and it never overwrites a working password.** Run at
 *    every boot, so a fresh deployment needs no manual step and a redeploy
 *    cannot lock the operator out. If the account already exists it is left
 *    exactly as it is — a boot that reset the password from an environment
 *    variable would silently undo a rotation made through the app.
 *
 *  * **It cannot create a second one.** The email is the identity: the same
 *    address is reconciled, a different one is refused while any administrator
 *    exists. A bootstrap that minted a fresh super administrator per deploy
 *    would be a privilege-escalation endpoint with a config file for a key.
 */

/** Placeholder shapes, refused so a committed template cannot become a login. */
const PLACEHOLDER = /^(replace-with|changeme|change-me|your-|placeholder|xxx)/i;

export interface BootstrapResult {
  readonly created: boolean;
  readonly adminId: string | null;
  readonly reason: string;
}

/**
 * Ensure the configured super administrator exists.
 *
 * Returns what it did rather than logging from here, so the CLI and the boot
 * path can each report it in their own way — and so the suite can assert the
 * outcome without capturing stdout.
 */
export async function ensureSuperAdmin(
  database: Queryable,
  /**
   * A password supplied by the caller, which wins over the environment.
   *
   * Used by `create-admin --generate`, where the value must be printed once and
   * therefore cannot live in the environment the process booted with. An
   * explicit hash still wins over it, so a deployment that has both keeps
   * working.
   */
  passwordOverride?: string | undefined
): Promise<BootstrapResult> {
  const email = (env.SUPER_ADMIN_EMAIL ?? '').trim();
  const passwordHash = (env.SUPER_ADMIN_PASSWORD_HASH ?? '').trim();
  const password = (passwordOverride ?? env.SUPER_ADMIN_PASSWORD ?? '').trim();

  if (email === '') {
    return { created: false, adminId: null, reason: 'no_super_admin_configured' };
  }

  const emailNormalized = email.toLowerCase();

  const existing = await database.queryOne<{ id: string }>(
    `SELECT id FROM admin_users WHERE email_normalized = $1`,
    [emailNormalized]
  );

  if (existing) {
    // Reconciled, not reconfigured: the account is left alone. A boot that
    // re-wrote the password from the environment would undo a rotation the
    // operator made on purpose.
    return { created: false, adminId: existing.id, reason: 'already_provisioned' };
  }

  const others = await database.queryOne<{ id: string }>(`SELECT id FROM admin_users LIMIT 1`);
  if (others) {
    // One initial super administrator, by construction. Once any administrator
    // exists, which addresses may sign in is decided inside the product (a super
    // admin creating a channel admin), not by whoever controls the environment.
    return {
      created: false,
      adminId: null,
      reason: 'administrators_already_exist',
    };
  }

  if (passwordHash !== '' && PLACEHOLDER.test(passwordHash)) {
    throw badRequest('weak_password', 'SUPER_ADMIN_PASSWORD_HASH is still a placeholder value.');
  }
  if (passwordHash === '' && (password === '' || PLACEHOLDER.test(password))) {
    throw badRequest(
      'weak_password',
      'Set SUPER_ADMIN_PASSWORD_HASH, or SUPER_ADMIN_PASSWORD with a real value. A placeholder is refused so it cannot become a working login.'
    );
  }

  const admin = await insertAdmin(database, {
    displayName: env.SUPER_ADMIN_DISPLAY_NAME,
    email,
    role: 'super_admin',
    channelId: null,
    createdByAdminId: null,
    // A hash from the environment is used as-is (re-hashing it would hash the
    // hash and the configured password would not work); a plaintext value is
    // hashed here, once.
    passwordHash: passwordHash !== '' ? passwordHash : await hashPassword(password),
  });

  await writeAudit(database, {
    adminId: null,
    adminEmail: null,
    actorRole: null,
    action: 'admin.bootstrap',
    targetType: 'admin',
    targetId: admin.id,
    metadata: { role: 'super_admin', email: admin.email, via: 'environment' },
  });

  return { created: true, adminId: admin.id, reason: 'created' };
}

/**
 * Re-hash and store a new password for the configured super administrator.
 *
 * Used by `npm run super-admin -- --password <new>` when the account exists and
 * its credentials have been lost. Deliberately not automatic: an automatic
 * reset would mean anyone able to set an environment variable can take over the
 * account on the next deploy.
 */
export async function resetSuperAdminPassword(
  database: Queryable,
  newPassword: string
): Promise<void> {
  const emailNormalized = (env.SUPER_ADMIN_EMAIL ?? '').trim().toLowerCase();
  if (emailNormalized === '') {
    throw badRequest('invalid_request', 'SUPER_ADMIN_EMAIL is not set.');
  }

  const row = await database.queryOne<{ id: string }>(
    `SELECT id FROM admin_users WHERE email_normalized = $1`,
    [emailNormalized]
  );
  if (!row) throw badRequest('admin_not_found', 'No administrator uses SUPER_ADMIN_EMAIL.');

  // Validate before writing, so a too-short password is refused rather than
  // stored, and log the change without the password — the audit table is the
  // one place §30's rule could be broken by accident.
  await setAdminPassword(database, row.id, newPassword);
  await writeAudit(database, {
    adminId: row.id,
    adminEmail: emailNormalized,
    actorRole: 'super_admin',
    action: 'admin.password.reset',
    targetType: 'admin',
    targetId: row.id,
    metadata: { via: 'cli' },
  });
}

/** Whether any administrator exists at all — the app's own readiness signal. */
export async function hasAnyAdmin(database: Queryable): Promise<boolean> {
  return (await database.queryOne(`SELECT 1 FROM admin_users LIMIT 1`)) !== null;
}

/** A loaded account, for the CLI to print (never the hash). */
export { loadAdmin, hashPassword };
