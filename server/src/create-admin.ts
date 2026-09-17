import { randomBytes } from 'node:crypto';
import { closePool, db } from './db.js';
import { env } from './env.js';
import { ensureSuperAdmin, resetSuperAdminPassword } from './admin/bootstrap.js';

/**
 * Provision the initial SUPER_ADMIN (§17).
 *
 * This is how the FIRST administrator comes to exist — and the only way, on
 * purpose:
 *
 *  * **Not an endpoint.** An unauthenticated "create the first admin" route is
 *    not a bootstrap, it is a backdoor. A CLI, run by whoever has deploy access,
 *    is the smallest possible surface.
 *
 *  * **The credentials come from the environment**, which is what §17 requires:
 *    `SUPER_ADMIN_EMAIL` with either `SUPER_ADMIN_PASSWORD_HASH` (bcrypt, the
 *    supported value) or `SUPER_ADMIN_PASSWORD`, which is hashed here. Nothing
 *    lands in the Android app and nothing is written to a file.
 *
 *  * **Idempotent and non-destructive.** Running it against a deployment that
 *    already has its administrator reports what it found and changes nothing.
 *    Overwriting a working password from an environment variable would undo a
 *    rotation somebody made deliberately.
 *
 * Usage:
 *   npm run create-admin                     # provision from SUPER_ADMIN_* env vars
 *   npm run create-admin -- --generate       # generate a password and print it once
 *   npm run create-admin -- --reset --password <new>   # rotate a lost password
 */

interface Args {
  readonly generate: boolean;
  readonly reset: boolean;
  readonly password?: string | undefined;
}

function parseArgs(argv: readonly string[]): Args {
  const out: { [key: string]: string | boolean | undefined } = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (token === undefined) continue;
    if (token === '--generate') {
      out.generate = true;
      continue;
    }
    if (token === '--reset') {
      out.reset = true;
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
    generate: out.generate === true,
    reset: out.reset === true,
    password: out.password as string | undefined,
  };
}

/**
 * A random, printable password.
 *
 * Base64url of 18 bytes, which is ~24 characters of high-entropy text. Chosen
 * over a hex string because `ADMIN_MIN_PASSWORD_LENGTH` would otherwise accept
 * something far weaker, and printed exactly once.
 */
function generatePassword(): string {
  return randomBytes(18).toString('base64url');
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));

  if (args.reset) {
    const password = args.password ?? '';
    if (password === '') {
      console.error('[create-admin] --reset needs --password <new password>.');
      process.exit(1);
    }
    await resetSuperAdminPassword(db, password);
    console.log(`[create-admin] password rotated for ${env.SUPER_ADMIN_EMAIL ?? '(unset)'}`);
    return;
  }

  if (args.generate) {
    // Generated here rather than in the bootstrap, so it can be printed once.
    // Passed in rather than put in the environment: `env` was parsed when this
    // process started, so writing to `process.env` now would change nothing and
    // the account would be created with no usable password.
    const password = generatePassword();
    const result = await ensureSuperAdmin(db, password);

    if (result.created) {
      console.log('');
      console.log('  Super administrator created');
      console.log(`    email  ${env.SUPER_ADMIN_EMAIL ?? ''}`);
      console.log('');
      console.log('  Password (shown once — store it in a password manager):');
      console.log(`    ${password}`);
      console.log('');
      return;
    }

    console.log(`[create-admin] nothing to do: ${result.reason}`);
    return;
  }

  const result = await ensureSuperAdmin(db);

  if (!result.created) {
    // The reason is the useful part: "no_super_admin_configured" is a
    // configuration mistake, "already_provisioned" is not a problem at all.
    console.log(`[create-admin] nothing to do: ${result.reason}`);
    if (result.reason === 'no_super_admin_configured') {
      console.error(
        '[create-admin] Set SUPER_ADMIN_EMAIL and SUPER_ADMIN_PASSWORD_HASH (or SUPER_ADMIN_PASSWORD) in the environment.'
      );
      process.exitCode = 1;
    }
    if (result.reason === 'administrators_already_exist') {
      console.error(
        '[create-admin] Another administrator already exists. The first super administrator is\n' +
          '  provisioned once; the rest are created from the app by a super admin (§17).'
      );
    }
    return;
  }

  console.log('');
  console.log('  Super administrator created');
  console.log(`    id     ${result.adminId ?? ''}`);
  console.log(`    email  ${env.SUPER_ADMIN_EMAIL ?? ''}`);
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
