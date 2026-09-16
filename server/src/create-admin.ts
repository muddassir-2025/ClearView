import { randomBytes } from 'node:crypto';
import { closePool, db } from './db.js';
import { env } from './env.js';
import { adminExists, provisionBootstrapAdmin } from './admin/bootstrap.js';

/**
 * Provision the initial SUPER_ADMIN (§21, §27, §40).
 *
 * The `create-admin` npm script has pointed at this file since M0; M6 is the
 * milestone that makes it exist. It is how the FIRST administrator is created,
 * and it is the only way, on purpose:
 *
 *  * **Not an endpoint.** §21 forbids a public "create admin" route, and §48
 *    forbids the user registration flow from producing one. A CLI run by
 *    whoever has deploy access is the smallest possible surface.
 *
 *  * **No password in the repository.** The password comes from
 *    `BOOTSTRAP_ADMIN_PASSWORD` in the environment — or, if that is unset, is
 *    generated and printed ONCE so it can be stored in a password manager.
 *    Nothing is written to a file, and §40's "do not commit real values" is
 *    satisfied by construction rather than by discipline.
 *
 *  * **Idempotent and non-destructive.** If an administrator already exists,
 *    this refuses unless `--force`, and it never silently overwrites an
 *    existing account's password.
 *
 * Usage:
 *   npm run create-admin                       # uses BOOTSTRAP_ADMIN_* env vars
 *   npm run create-admin -- --force            # add one even if an admin exists
 *   npm run create-admin -- --email a@b.c --phone +92300... --name "A" --role admin
 *
 * The role defaults to SUPER_ADMIN because the point of this script is the
 * account that can create the others.
 */

interface Args {
  readonly email?: string | undefined;
  readonly phone?: string | undefined;
  readonly name?: string | undefined;
  readonly role?: string | undefined;
  readonly password?: string | undefined;
  readonly force: boolean;
}

function parseArgs(argv: readonly string[]): Args {
  const out: { [key: string]: string | boolean | undefined } = { force: false };
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (token === undefined) continue;
    if (token === '--force') {
      out.force = true;
      continue;
    }
    if (!token.startsWith('--')) continue;
    const key = token.slice(2);
    const value = argv[i + 1];
    if (value === undefined || value.startsWith('--')) continue;
    out[key] = value;
    i += 1;
  }
  return {
    email: out.email as string | undefined,
    phone: out.phone as string | undefined,
    name: out.name as string | undefined,
    role: out.role as string | undefined,
    password: out.password as string | undefined,
    force: out.force === true,
  };
}

/**
 * A random, printable password.
 *
 * Base64url of 18 bytes, which is ~24 characters of high-entropy text. Chosen
 * over a word list because this is a credential that gets pasted into a
 * password manager and typed rarely, and over a hex string because
 * `ADMIN_MIN_PASSWORD_LENGTH` would accept something far weaker.
 */
function generatePassword(): string {
  return randomBytes(18).toString('base64url');
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));

  const email = args.email ?? env.BOOTSTRAP_ADMIN_EMAIL ?? '';
  const phone = args.phone ?? env.BOOTSTRAP_ADMIN_PHONE ?? '';
  const displayName = args.name ?? env.BOOTSTRAP_ADMIN_DISPLAY_NAME;

  if (email === '' || phone === '') {
    console.error(
      '[create-admin] An email and a mobile number are required.\n' +
        '  Set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PHONE in the environment,\n' +
        '  or pass --email <address> --phone <+E.164>.'
    );
    process.exit(1);
  }

  if (!args.force && (await adminExists(db))) {
    console.error(
      '[create-admin] An administrator already exists. Refusing to add another.\n' +
        '  The bootstrap script is for the FIRST administrator; create the rest from\n' +
        '  the dashboard (Administrators → Create), which is audited (§27).\n' +
        '  Pass --force to add one anyway.'
    );
    process.exit(1);
  }

  const providedPassword = args.password ?? env.BOOTSTRAP_ADMIN_PASSWORD ?? '';
  const password = providedPassword === '' ? generatePassword() : providedPassword;

  const created = await provisionBootstrapAdmin(db, {
    displayName,
    email,
    phone,
    role: args.role ?? 'super_admin',
    password,
  });

  // The password is printed ONLY when this script generated it. If an operator
  // supplied it, echoing it back would put a real credential into a shell
  // history and a CI log for no benefit — they already have it.
  console.log('');
  console.log('  Administrator created');
  console.log(`    id     ${created.admin.id}`);
  console.log(`    email  ${created.admin.email}`);
  console.log(`    role   ${created.admin.role}`);
  console.log('');
  if (providedPassword === '') {
    console.log('  Password (shown once, store it in a password manager):');
    console.log(`    ${password}`);
    console.log('');
  }
  console.log('  Sign in at /admin on the service URL.');
  console.log('');
}

// Never let a credential reach a log through an unhandled rejection: the
// message is printed, the stack is not, because a stack trace of a failed
// insert can carry the bound parameters on some drivers.
main()
  .catch((err: unknown) => {
    console.error(`[create-admin] ${(err as Error).message}`);
    process.exitCode = 1;
  })
  .finally(() => {
    void closePool();
  });
