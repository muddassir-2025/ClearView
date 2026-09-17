import { env } from './env.js';
import { databaseTarget, directPool } from './db.js';
import { runMigrations } from './migrate.js';

/**
 * Rebuilds the database from the migrations, by deleting the schema first.
 *
 * Why this exists: `migrate` is forward-only, which is right for a database
 * somebody is using and wrong for the one this service was built on. The
 * earlier ClearView deployment is gone, the Good Post schema was rebuilt as a
 * single file (`001_init.sql`) rather than migrated into, and a database still
 * holding the 16-file chain's `schema_migrations` rows would refuse to apply it
 * — the runner sees a version it already recorded with a different checksum and
 * stops, correctly.
 *
 * `DROP SCHEMA public CASCADE` and not `DROP DATABASE`: creating a database needs
 * a role that can create one, and on Neon the connection string this service is
 * given generally cannot. It also leaves the database's own settings alone.
 * Nothing outside `public` is touched, so an operator who has put something in
 * another schema keeps it.
 *
 * This DELETES EVERY ROW in the Good Post tables. It is a development and
 * first-deploy tool: run it when the database holds nothing you need. In
 * production (`NODE_ENV=production`) it refuses unless `--force` is passed, so
 * the destructive path cannot be reached by a routine command.
 *
 *   npm run db:reset
 */
async function main(): Promise<void> {
  const forced = process.argv.includes('--force');

  console.log(
    `[db:reset] target ${databaseTarget()}\n` +
      '[db:reset] this DELETES every table in schema "public" and re-applies migrations.'
  );

  if (env.NODE_ENV === 'production' && !forced) {
    throw new Error(
      '[db:reset] refusing to run with NODE_ENV=production. Re-run with --force if this is really the database to rebuild.'
    );
  }

  // The direct (non-pooled) host, for the same reason `migrate` uses it: DDL and
  // the migration runner's advisory lock need one session, and a transaction
  // pooler may route the statements elsewhere.
  const pool = directPool();
  const client = await pool.connect();

  try {
    await client.query('DROP SCHEMA IF EXISTS public CASCADE');
    await client.query('CREATE SCHEMA public');
    console.log('[db:reset] schema "public" recreated empty');
  } finally {
    // The client is released but the pool is NOT ended: `directPool()` returns
    // one shared pool per process, and `runMigrations()` below ends it when it
    // is finished. Ending it here would hand the runner a closed pool.
    client.release();
  }

  await runMigrations();
}

main().catch((err: unknown) => {
  console.error((err as Error).message);
  process.exit(1);
});
