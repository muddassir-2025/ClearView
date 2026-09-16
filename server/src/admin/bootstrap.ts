import type { Queryable } from '../db.js';
import { badRequest, conflict } from '../http/errors.js';
import { ADMIN_ROLES, type AdminRole } from './permissions.js';
import { createAdmin, type AdminAccount } from './service.js';
import { writeAudit } from './service.js';

/**
 * Provisioning the first administrator (§21, §27, §40).
 *
 * Split out of the CLI so the rules are testable without spawning a process,
 * and so the CLI holds nothing but argument parsing and printing.
 *
 * Two refusals are deliberate:
 *
 *  * **No password may come from a placeholder.** §40 says placeholders go in
 *    `.env.example` and real values never do; a bootstrap that accepted
 *    `change-me` would be the one path that turns a committed placeholder into
 *    a working administrator login.
 *
 *  * **The first administrator cannot be created through the API.** There is no
 *    route that calls this. §21 requires an initial account configured on the
 *    backend, and an unauthenticated "create the first admin" endpoint is not a
 *    bootstrap, it is a backdoor.
 */

export interface BootstrapInput {
  readonly displayName: string;
  readonly email: string;
  readonly phone: string;
  readonly role: string;
  readonly password: string;
}

export interface BootstrapResult {
  readonly admin: AdminAccount;
}

/** Placeholder shapes, shared with the env validator's own list. */
const PLACEHOLDER = /^(replace-with|changeme|change-me|your-|placeholder|xxx)/i;

/** Is any administrator already provisioned? */
export async function adminExists(database: Queryable): Promise<boolean> {
  const row = await database.queryOne(`SELECT 1 FROM admin_users LIMIT 1`);
  return row !== null;
}

/**
 * Create the bootstrap administrator.
 *
 * The audit row is written with a NULL actor and `system` in the metadata: the
 * action is a change to the administrator set, which §29 lists as auditable,
 * and there is no signed-in administrator to attribute it to. Recording it as
 * "nobody" is more honest than attributing it to the account it creates.
 */
export async function provisionBootstrapAdmin(
  database: Queryable,
  input: BootstrapInput
): Promise<BootstrapResult> {
  const role = input.role.trim();
  if (!ADMIN_ROLES.includes(role as AdminRole)) {
    throw badRequest('invalid_role', `Role must be one of: ${ADMIN_ROLES.join(', ')}.`);
  }

  const password = input.password;
  if (password.trim() === '' || PLACEHOLDER.test(password)) {
    throw badRequest(
      'weak_password',
      'A real password is required. A placeholder value is refused here so it cannot become a working login.'
    );
  }

  const existing = await database.queryOne<{ id: string; email_normalized: string }>(
    `SELECT id, email_normalized FROM admin_users WHERE email_normalized = $1`,
    [input.email.trim().toLowerCase()]
  );
  if (existing) {
    throw conflict('email_taken', 'An administrator already uses that email.');
  }

  const admin = await createAdmin(database, {
    displayName: input.displayName,
    email: input.email,
    phone: input.phone,
    role: role as AdminRole,
    password,
    createdByAdminId: null,
  });

  await writeAudit(database, {
    adminId: null,
    adminEmail: null,
    actorRole: null,
    action: 'admin.create',
    targetType: 'admin',
    targetId: admin.id,
    outcome: 'success',
    metadata: { role: admin.role, email: admin.email, via: 'bootstrap-script' },
  });

  return { admin };
}
