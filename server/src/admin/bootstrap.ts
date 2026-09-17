import { env } from '../env.js';
import type { Queryable } from '../db.js';
import { badRequest } from '../http/errors.js';
import {
  hashPassword,
  insertAdmin,
  loadAdmin,
  passwordMatches,
  setAdminPassword,
  writeAudit,
} from './service.js';

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
 *  * **The environment is authoritative for a PLAINTEXT password.** Run at every
 *    boot, so a fresh deployment needs no manual step and a redeploy cannot lock
 *    the operator out. When `SUPER_ADMIN_PASSWORD` is set and it no longer opens
 *    the account, the stored hash is replaced with it — because the alternative
 *    is what this used to do: silently ignore the new value and answer every
 *    sign-in with "invalid credentials", with nothing on screen or in the logs to
 *    say why. That is a bug you cannot debug from the app, only from the server,
 *    and it is the reason this reconcile exists.
 *
 *    `SUPER_ADMIN_PASSWORD_HASH` alone is NOT reconciled: a hash cannot be
 *    compared against the account without the plaintext it came from, so an
 *    operator who sets only the hash keeps the deliberate behaviour — rotate with
 *    `create-admin --reset`, and the environment never overwrites it.
 *
 *  * **On first creation a plaintext password outranks the hash.** Whoever sets
 *    both has edited the human-readable one; letting a pre-existing hash win is
 *    how `create-admin --generate` came to print a password it never stored.
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
  /**
   * Something worth saying that is not an outcome: today, that the configured
   * password is shorter than the floor the app enforces for a password chosen
   * in a form.
   *
   * Returned rather than logged so it stays the caller's to word, like
   * [reason] — and so the suite can assert it. Never contains a value: the
   * message names the variable and the length, never the password.
   */
  readonly warning: string | null;
}

/**
 * The warning for a configured password below [env.ADMIN_MIN_PASSWORD_LENGTH],
 * or null when there is nothing to say.
 *
 * A warning and not a refusal: the operator holds this secret, and a boot that
 * refused would take the reader surface down over a value that still works. But
 * it IS said, because a nine-character password guarding the account that can
 * create channels is worth knowing about.
 */
function floorWarning(password: string): string | null {
  if (password.length === 0 || password.length >= env.ADMIN_MIN_PASSWORD_LENGTH) return null;
  return (
    `SUPER_ADMIN_PASSWORD is ${password.length} characters, below the ` +
    `${env.ADMIN_MIN_PASSWORD_LENGTH}-character floor the app enforces for a password chosen in a form. ` +
    'It is applied as given — consider lengthening it.'
  );
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
    return { created: false, adminId: null, reason: 'no_super_admin_configured', warning: null };
  }

  const emailNormalized = email.toLowerCase();

  const existing = await database.queryOne<{ id: string; password_hash: string }>(
    `SELECT id, password_hash FROM admin_users WHERE email_normalized = $1`,
    [emailNormalized]
  );

  if (existing) {
    // A plaintext password in the environment is the operator saying "this is
    // the password". If it does not open the account, the stored hash is stale
    // — so it is replaced, and the reason says so, because the boot log is the
    // only place this is visible.
    const configured = password; // `passwordOverride` wins over SUPER_ADMIN_PASSWORD.
    if (configured !== '' && !PLACEHOLDER.test(configured)) {
      const stillWorks = await passwordMatches(configured, existing.password_hash);
      if (!stillWorks) {
        // `enforceFloor: false`: this password came from the deployment, not from
        // a form. Refusing it would leave the account on the stale hash — the
        // exact bug — and would do so silently. It is applied and, when short,
        // warned about instead.
        await setAdminPassword(database, existing.id, configured, { enforceFloor: false });
        await writeAudit(database, {
          adminId: existing.id,
          adminEmail: emailNormalized,
          actorRole: 'super_admin',
          action: 'admin.password.reconciled',
          targetType: 'admin',
          targetId: existing.id,
          metadata: { via: 'environment', reason: 'configured_password_replaced_stored_hash' },
        });
        return {
          created: false,
          adminId: existing.id,
          reason: 'password_reconciled',
          warning: floorWarning(configured),
        };
      }
    }

    // Otherwise reconciled, not reconfigured: the account is left alone. The
    // warning still applies — a short configured password is worth knowing about
    // whether or not this boot had to write it.
    return {
      created: false,
      adminId: existing.id,
      reason: 'already_provisioned',
      warning: floorWarning(password),
    };
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
      warning: null,
    };
  }

  if (password !== '' && PLACEHOLDER.test(password)) {
    throw badRequest('weak_password', 'SUPER_ADMIN_PASSWORD is still a placeholder value.');
  }
  if (password === '' && (passwordHash === '' || PLACEHOLDER.test(passwordHash))) {
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
    // A plaintext value wins when both are set (see the header), and a hash
    // from the environment is used as-is rather than re-hashed — hashing a hash
    // would store something the configured password can never match.
    passwordHash: password !== '' ? await hashPassword(password) : passwordHash,
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

  return { created: true, adminId: admin.id, reason: 'created', warning: floorWarning(password) };
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

/** A loaded account, for the CLI to print (never the hash). */
export { loadAdmin, hashPassword };
