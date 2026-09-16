import { createHash } from 'node:crypto';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { PGlite } from '@electric-sql/pglite';
import type { QueryResultRow } from 'pg';
import type { Queryable } from '../../src/db.js';

/**
 * Applies the real migration files to an in-process PostgreSQL (PGlite, a
 * genuine Postgres build compiled to WASM).
 *
 * This exists so the schema is verified rather than assumed. A migration that
 * only ever runs for the first time in production is a migration nobody has
 * ever tested — and by then it is running against the database that matters.
 * PGlite supports DDL, enums, plpgsql triggers, partial unique indexes and
 * `gen_random_uuid()`, which is everything migration 001 uses.
 *
 * The transaction-per-file behaviour mirrors src/migrate.ts deliberately: if
 * the runner wraps each file and this helper does not, the helper would pass
 * on SQL that the runner rejects.
 */

const MIGRATIONS_DIR = path.resolve(process.cwd(), 'migrations');

export async function freshDatabase(): Promise<PGlite> {
  return PGlite.create();
}

export async function applyAllMigrations(db: PGlite): Promise<string[]> {
  const files = (await readdir(MIGRATIONS_DIR)).filter((f) => f.endsWith('.sql')).sort();

  await db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version    text PRIMARY KEY,
      checksum   text NOT NULL,
      applied_at timestamptz NOT NULL DEFAULT now()
    )
  `);

  for (const file of files) {
    const sql = await readFile(path.join(MIGRATIONS_DIR, file), 'utf8');
    const checksum = createHash('sha256').update(sql).digest('hex');

    // Re-applying an already-applied file is a no-op, exactly as in the runner.
    const existing = await db.query<{ checksum: string }>(
      'SELECT checksum FROM schema_migrations WHERE version = $1',
      [file]
    );
    if (existing.rows.length > 0) continue;

    await db.transaction(async (tx) => {
      await tx.exec(sql);
      await tx.query('INSERT INTO schema_migrations (version, checksum) VALUES ($1, $2)', [
        file,
        checksum,
      ]);
    });
  }

  return files;
}

/**
 * Present a PGlite instance as the same [Queryable] the service uses in
 * production.
 *
 * This is the seam that makes the auth suite worth trusting. `src/db.ts`
 * exposes `Queryable`, and every service takes one, so these tests execute the
 * REAL SQL — the real partial unique indexes, the real CHECK constraints, the
 * real `FOR UPDATE` — against a genuine Postgres build. Mocking the database
 * instead would assert only that the code called the database, never that the
 * constraints it depends on actually reject what they are supposed to.
 */
export function asQueryable(pglite: PGlite): Queryable {
  const run = async <T extends QueryResultRow>(sql: string, params: readonly unknown[]) => {
    const result = (await pglite.query(sql, params as unknown[])) as { rows: T[] };
    return result.rows;
  };

  const handle = (): Queryable => ({
    query: run,
    async queryOne<T extends QueryResultRow>(sql: string, params: readonly unknown[] = []) {
      const rows = await run<T>(sql, params);
      return rows[0] ?? null;
    },
    transaction: () =>
      Promise.reject(new Error('[test] nested transactions are not supported')),
  });

  return {
    ...handle(),
    // Mirrors src/migrate.ts's transaction-per-file behaviour: a real
    // transaction, so a failed registration rolls its user row back.
    transaction: async (fn) =>
      pglite.transaction(async (tx) => {
        const txRun = async <T extends QueryResultRow>(
          sql: string,
          params: readonly unknown[]
        ) => {
          const result = (await tx.query(sql, params as unknown[])) as { rows: T[] };
          return result.rows;
        };

        const scoped: Queryable = {
          query: txRun,
          async queryOne<T extends QueryResultRow>(
            sql: string,
            params: readonly unknown[] = []
          ) {
            const rows = await txRun<T>(sql, params);
            return rows[0] ?? null;
          },
          transaction: () =>
            Promise.reject(new Error('[test] nested transactions are not supported')),
        };

        return fn(scoped);
      }),
  };
}

/**
 * Delete every row of the Good Post tables, leaving the schema in place.
 *
 * Cheaper and far more honest than re-creating the database per test: the
 * indexes, triggers and enums under test are created once, exactly as
 * `migrate` would create them, and only the data is reset.
 */
export async function resetData(pglite: PGlite): Promise<void> {
  // Explicit table list rather than relying on TRUNCATE's CASCADE to reach the
  // channel tables through their foreign keys. The cascade would work, but it
  // would also silently swallow a future table that references `users`, and a
  // test that resets "some of" the data is worse than one that fails loudly.
  //
  // `channel_categories` is deliberately absent: it holds migration-seeded
  // reference data, not test data, and deleting it would leave every channel
  // creation in the suite failing on a missing category.
  await pglite.exec(
    `TRUNCATE channel_blocks, channel_followers, channel_admins, channels,
              banned_identities, phone_verifications, user_sessions, users
     RESTART IDENTITY CASCADE`
  );
}

/** First row, or a clear failure — avoids `rows[0]!` noise under strict mode. */
export function one<T>(rows: T[]): T {
  const row = rows[0];
  if (row === undefined) throw new Error('expected at least one row, got none');
  return row;
}

let phoneCounter = 0;

/**
 * Inserts a user with plausible values, overridable per test. The default
 * phone hash is unique per call so tests that do not care about the abuse
 * identity are not fighting the uniqueness constraint.
 */
export async function insertUser(
  db: PGlite,
  overrides: Record<string, unknown> = {}
): Promise<string> {
  phoneCounter += 1;
  const values = {
    display_name: `user-${phoneCounter}`,
    email: `user${phoneCounter}@example.test`,
    email_normalized: `user${phoneCounter}@example.test`,
    phone_hash: `hash-${phoneCounter}`,
    ...overrides,
  };

  const columns = Object.keys(values);
  const placeholders = columns.map((_, i) => `$${i + 1}`).join(', ');
  const rows = await db.query<{ id: string }>(
    `INSERT INTO users (${columns.join(', ')}) VALUES (${placeholders}) RETURNING id`,
    Object.values(values)
  );

  return one(rows.rows).id;
}
