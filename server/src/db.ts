import pg from 'pg';
import { env } from './env.js';

// `pg` is CommonJS; this is the reliable ESM interop shape.
const { Pool, types } = pg;

// Return BIGINT as a number. Every BIGINT in this schema is a count or an
// epoch-millisecond value — both are far inside Number.MAX_SAFE_INTEGER — and
// pg's default of returning a string would silently turn `COUNT(*) > 0` into
// a string comparison.
types.setTypeParser(types.builtins.INT8, (v: string) => Number(v));

/**
 * Neon mandates TLS. Their certificate chain is valid, but pinning it would
 * mean re-deploying this service whenever Neon rotates CAs, so TLS is
 * required but not pinned. This still encrypts transit; it only declines to
 * authenticate the endpoint certificate.
 */
function sslFor(connectionString: string): pg.PoolConfig['ssl'] {
  const needsTls =
    /sslmode=(require|verify-ca|verify-full)/i.test(connectionString) ||
    process.env.PGSSLMODE === 'require' ||
    /\.neon\.tech/i.test(connectionString);
  return needsTls ? { rejectUnauthorized: false } : undefined;
}

function makePool(connectionString: string, max: number): pg.Pool {
  const pool = new Pool({
    connectionString,
    max,
    ssl: sslFor(connectionString),
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 10_000,
    application_name: 'clearview-goodpost',
  });

  // A pool-level error means an IDLE client died (Neon suspends idle
  // computes). Without this listener Node treats it as an unhandled error
  // event and kills the process.
  pool.on('error', (err) => {
    console.error('[db] idle client error:', err.message);
  });

  return pool;
}

export const pool = makePool(env.DATABASE_URL, env.PG_POOL_MAX);

/**
 * A second, single-connection pool over the DIRECT Neon host.
 *
 * Migrations take a session-level advisory lock and run DDL, neither of which
 * is safe through a transaction-pooling endpoint (the pooler can hand
 * different statements to different backends). Falls back to DATABASE_URL
 * when no direct URL is configured, so local development needs one variable.
 */
let migrationPool: pg.Pool | null = null;
export function directPool(): pg.Pool {
  if (!migrationPool) {
    migrationPool = makePool(env.DATABASE_URL_DIRECT ?? env.DATABASE_URL, 1);
  }
  return migrationPool;
}

export type QueryParams = readonly unknown[];

/** Run a query and return its rows. */
export async function query<T extends pg.QueryResultRow = pg.QueryResultRow>(
  sql: string,
  params: QueryParams = []
): Promise<T[]> {
  const result = await pool.query<T>(sql, params as unknown[]);
  return result.rows;
}

/** Run a query and return the first row, or null. */
export async function queryOne<T extends pg.QueryResultRow = pg.QueryResultRow>(
  sql: string,
  params: QueryParams = []
): Promise<T | null> {
  const rows = await query<T>(sql, params);
  return rows[0] ?? null;
}

/**
 * Run `fn` inside a transaction, rolling back on any throw. Every multi-step
 * write (register, ban, publish) goes through this so a partial write can
 * never leave, say, a user with no session or a post with no media row.
 */
export async function withTransaction<T>(
  fn: (client: pg.PoolClient) => Promise<T>
): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    try {
      await client.query('ROLLBACK');
    } catch {
      // The connection is already broken; the original error is the useful one.
    }
    throw err;
  } finally {
    client.release();
  }
}

/**
 * The database surface the application code actually needs.
 *
 * Services take a `Queryable` instead of importing `pool` directly. That one
 * indirection is what lets the auth suite drive the REAL SQL against PGlite
 * (a genuine Postgres in WASM) rather than mocking it — a mocked query proves
 * the call was made, never that the constraints, triggers and unique indexes
 * behave. It also makes "this service runs against a transaction" a type-level
 * fact instead of a convention.
 */
export interface Queryable {
  query<T extends pg.QueryResultRow = pg.QueryResultRow>(
    sql: string,
    params?: QueryParams
  ): Promise<T[]>;
  queryOne<T extends pg.QueryResultRow = pg.QueryResultRow>(
    sql: string,
    params?: QueryParams
  ): Promise<T | null>;
  transaction<T>(fn: (tx: Queryable) => Promise<T>): Promise<T>;
}

/**
 * First row, or a thrown error.
 *
 * `noUncheckedIndexedAccess` makes `rows[0]` optional, which is correct — a
 * query that must return a row (an INSERT ... RETURNING, a unique-index hit)
 * returning none is a bug worth failing loudly on, not a `?? null` to paper
 * over.
 */
export function one<T>(rows: T[]): T {
  const row = rows[0];
  if (row === undefined) throw new Error('[db] expected at least one row, got none');
  return row;
}

/** A `Queryable` backed by one connection, used inside a transaction. */
function clientQueryable(client: pg.PoolClient): Queryable {
  const run = <T extends pg.QueryResultRow>(sql: string, params: QueryParams) =>
    client.query<T>(sql, params as unknown[]).then((r) => r.rows);

  return {
    query: run,
    async queryOne<T extends pg.QueryResultRow>(sql: string, params: QueryParams = []) {
      const rows = await run<T>(sql, params);
      return rows[0] ?? null;
    },
    transaction() {
      // Nesting would need SAVEPOINTs, which nothing requires yet. Failing
      // loudly beats silently running inner statements outside the outer
      // transaction's atomicity guarantees.
      return Promise.reject(
        new Error('[db] nested transactions are not supported; pass the existing tx instead')
      );
    },
  };
}

/**
 * A timestamp column as an ISO string, or null.
 *
 * Timestamps arrive as `Date` from `pg` and as strings from PGlite, so the
 * shape is normalised in one place rather than assumed at each call site. A
 * `Date` reaching `JSON.stringify` directly would serialise to a different
 * string than the `timestamptz` comparison its value is later fed back into —
 * which is exactly how page two of a cursor feed ends up repeating page one.
 */
export function isoOrNull(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (value instanceof Date) return value.toISOString();
  return String(value);
}

/** [isoOrNull] for a value that is known to be present (a sort key). */
export function cursorKeyOf(value: unknown): string {
  if (value instanceof Date) return value.toISOString();
  return String(value);
}

/** The pool-backed `Queryable` the running service uses. */
export const db: Queryable = {
  query,
  queryOne,
  transaction: (fn) => withTransaction((client) => fn(clientQueryable(client))),
};

/** Liveness probe: is the database reachable and answering? */
export async function pingDatabase(): Promise<boolean> {
  try {
    await pool.query('SELECT 1');
    return true;
  } catch (err) {
    console.error('[db] health check failed:', (err as Error).message);
    return false;
  }
}

export async function closePool(): Promise<void> {
  await pool.end();
  if (migrationPool) await migrationPool.end();
}

/**
 * "host/database" of the configured target, for a log line that says which
 * database a command is about to touch. Never credentials: this is printed by
 * `db:reset` before it deletes a schema, where "which one am I on?" is the one
 * question worth answering out loud.
 */
export function databaseTarget(): string {
  try {
    const u = new URL(env.DATABASE_URL_DIRECT ?? env.DATABASE_URL);
    return `${u.hostname}${u.pathname}`;
  } catch {
    /* parseUrl already validated the shape at boot */
    return '(unparseable DATABASE_URL)';
  }
}

if (env.NODE_ENV === 'development') {
  // Visibility that the right database is targeted, without ever printing
  // credentials. Development only so test output stays readable.
  console.log(`[db] target ${databaseTarget()}`);
}
