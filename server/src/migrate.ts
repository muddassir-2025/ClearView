import { createHash } from 'node:crypto';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { directPool } from './db.js';

/**
 * Forward-only SQL migration runner.
 *
 * Why this exists rather than "sync the schema at boot" (§33 forbids that):
 *
 *  - Each file runs inside its own transaction, so a migration either fully
 *    applies or not at all. A half-applied DDL change would leave the API
 *    running against a schema nobody can reason about.
 *  - A session-level advisory lock serialises concurrent runs. Render can
 *    overlap a pre-deploy migration with an instance that is still draining,
 *    and two runners racing the same `CREATE TABLE` is a real failure mode.
 *  - Checksums make applied migrations immutable. Editing an applied file
 *    would silently diverge production from every other environment; the
 *    runner refuses and forces a new file instead.
 *
 * It runs against the DIRECT Neon host (`DATABASE_URL_DIRECT`), because the
 * transaction pooler can route the statements of one migration across
 * different backends, which breaks both DDL and session advisory locks.
 *
 * `runMigrations` is exported because `src/db-reset.ts` drops the schema and
 * then needs to apply exactly this set of files under exactly this lock — a
 * second copy of the loop below would be a second thing to keep in step.
 */

const MIGRATIONS_DIR = path.resolve(process.cwd(), 'migrations');

// Arbitrary but fixed: every deployment of this service must use the same key
// for the lock to actually exclude other runners.
const ADVISORY_LOCK_KEY = 9_532_114_207;

export async function runMigrations(): Promise<void> {
  const pool = directPool();
  const client = await pool.connect();

  try {
    await client.query(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version    text PRIMARY KEY,
        checksum   text NOT NULL,
        applied_at timestamptz NOT NULL DEFAULT now()
      )
    `);

    await client.query('SELECT pg_advisory_lock($1)', [ADVISORY_LOCK_KEY]);

    try {
      const files = (await readdir(MIGRATIONS_DIR))
        .filter((f) => f.endsWith('.sql'))
        .sort();

      const { rows } = await client.query<{ version: string; checksum: string }>(
        'SELECT version, checksum FROM schema_migrations'
      );
      const applied = new Map(rows.map((r) => [r.version, r.checksum]));

      let ran = 0;
      for (const file of files) {
        const sql = await readFile(path.join(MIGRATIONS_DIR, file), 'utf8');
        const checksum = createHash('sha256').update(sql).digest('hex');
        const previous = applied.get(file);

        if (previous !== undefined) {
          if (previous !== checksum) {
            throw new Error(
              `[migrate] ${file} has changed since it was applied. Migrations are immutable — add a new file instead.`
            );
          }
          continue;
        }

        console.log(`[migrate] applying ${file}`);
        await client.query('BEGIN');
        try {
          await client.query(sql);
          await client.query(
            'INSERT INTO schema_migrations (version, checksum) VALUES ($1, $2)',
            [file, checksum]
          );
          await client.query('COMMIT');
          ran += 1;
        } catch (err) {
          await client.query('ROLLBACK');
          throw new Error(`[migrate] ${file} failed: ${(err as Error).message}`, { cause: err });
        }
      }

      console.log(ran === 0 ? '[migrate] schema already up to date' : `[migrate] applied ${ran} migration(s)`);
    } finally {
      await client.query('SELECT pg_advisory_unlock($1)', [ADVISORY_LOCK_KEY]);
    }
  } finally {
    client.release();
    await pool.end();
  }
}

// Only when this file IS the command. Importing it (db-reset.ts) must not apply
// migrations as a side effect of the import.
const invokedDirectly =
  process.argv[1] !== undefined &&
  import.meta.url === pathToFileURL(process.argv[1]).href;

if (invokedDirectly) {
  runMigrations().catch((err: unknown) => {
    console.error((err as Error).message);
    process.exit(1);
  });
}
