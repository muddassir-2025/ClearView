/**
 * Read-only diagnosis of the super administrator's stored hash (§18).
 *
 * Answers one question without changing anything: does the password in THIS
 * process's environment open the account in the database? That distinguishes
 * "the environment is right and the deployment is stale" from "the environment
 * and the account disagree", which are the two completely different causes of
 * "Invalid credentials" and need different fixes.
 *
 * It never prints the password, the hash, or whether a candidate is close — only
 * whether each configured value is accepted.
 */
import { closePool, db } from '../src/db.js';
import { env } from '../src/env.js';
import { hashPassword, passwordMatches } from '../src/admin/service.js';

const email = (env.SUPER_ADMIN_EMAIL ?? '').trim().toLowerCase();

const row = await db.queryOne<{ id: string; password_hash: string; role: string; status: string }>(
  `SELECT id, password_hash, role, status FROM admin_users WHERE email_normalized = $1`,
  [email]
);

console.log(`SUPER_ADMIN_EMAIL       ${email || '(unset)'}`);
console.log(`SUPER_ADMIN_PASSWORD    ${env.SUPER_ADMIN_PASSWORD ? 'set' : 'unset'}`);
console.log(`SUPER_ADMIN_PASSWORD_HASH ${env.SUPER_ADMIN_PASSWORD_HASH ? 'set' : 'unset'}`);
console.log('');

if (!row) {
  console.log('No administrator row uses that address. The bootstrap will create one on next boot.');
} else {
  console.log(`account                 ${row.role} / ${row.status}`);

  if (env.SUPER_ADMIN_PASSWORD) {
    const ok = await passwordMatches(env.SUPER_ADMIN_PASSWORD, row.password_hash);
    console.log(`stored hash accepts SUPER_ADMIN_PASSWORD : ${ok ? 'YES' : 'NO'}`);
    if (!ok) {
      console.log('');
      console.log('  ⇒ This is §18. The environment and the account disagree, so the');
      console.log('    configured password cannot sign in. The next boot reconciles it');
      console.log('    (admin/bootstrap.ts rewrites the hash from SUPER_ADMIN_PASSWORD).');
    }
  } else {
    console.log('No plaintext configured, so nothing can be compared against the row.');
    console.log('A changed SUPER_ADMIN_PASSWORD_HASH is applied only when the account');
    console.log('is created; rotate with: npm run create-admin -- --reset --password <new>');
  }
}

// Proves bcrypt is working at all, so a NO above is about the values rather
// than about a broken comparison.
const roundTrip = await passwordMatches('self-test-value', await hashPassword('self-test-value'));
console.log('');
console.log(`bcrypt round-trip self-test: ${roundTrip ? 'ok' : 'BROKEN'}`);

await closePool();
