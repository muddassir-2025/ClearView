/**
 * Keyset cursors and the pagination primitives every feed shares (§5, §6, §8).
 *
 * Offset pagination is not used anywhere here, on purpose. Discovery is sorted
 * by a value that changes as the platform is used — a channel gaining
 * followers — so `OFFSET 60` can repeat a row on one page and skip it on the
 * next. A cursor pins the exact position by carrying the sort key of the last
 * row returned, which makes the window stable even while the data moves.
 *
 * The cursor is opaque to the client (base64 of JSON) but it is NOT a security
 * boundary: it is attacker-controlled input that ends up interpolated into a
 * keyset comparison, so [decodeCursor] validates the shape and the id is a
 * UUID. Without that, a hand-crafted cursor reaches Postgres as an invalid
 * uuid or timestamp literal and surfaces as a 500 instead of a 400.
 *
 * Free of Express and database imports so the validation rules are unit-tested
 * directly rather than through a route. The single exception is the error
 * helper, so that a malformed cursor is rejected at this layer rather than
 * handed back as null for a caller to misread as "no cursor".
 */
import { badRequest } from '../http/errors.js';

/** How discovery orders results. Also part of the cursor: see [Cursor]. */
export type ChannelSort = 'popular' | 'active' | 'new';

export interface Cursor {
  /** The sort key of the last row of the previous page. */
  readonly k: string;
  /** Its id, which breaks ties so the order is total and stable. */
  readonly id: string;
}

/**
 * UUID v1–v5, case-insensitive. Deliberately permissive about the version and
 * variant nibbles: the column is `uuid`, so anything Postgres accepts should
 * pass here, and anything it rejects must fail here instead.
 */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Bounded so a hostile request cannot make the process base64-decode an
 * arbitrarily large body before the shape check rejects it.
 */
const MAX_CURSOR_LENGTH = 512;
const MAX_KEY_LENGTH = 64;

export function encodeCursor(cursor: Cursor): string {
  return Buffer.from(JSON.stringify(cursor), 'utf8').toString('base64url');
}

/**
 * Decode a cursor, or return null if it is not usable.
 *
 * Null rather than a thrown error so this module stays free of HTTP concerns;
 * the caller turns null into the appropriate 400. Callers must NOT treat null
 * as "no cursor" and restart from page one — that hides a client bug behind a
 * page of duplicate results. The convention is `invalid_cursor`.
 */
export function decodeCursor(raw: string | null | undefined): Cursor | null {
  if (typeof raw !== 'string' || raw.length === 0 || raw.length > MAX_CURSOR_LENGTH) {
    return null;
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'));
  } catch {
    return null;
  }

  if (typeof parsed !== 'object' || parsed === null) return null;

  const { k, id } = parsed as Record<string, unknown>;
  if (typeof k !== 'string' || k.length === 0 || k.length > MAX_KEY_LENGTH) return null;
  if (typeof id !== 'string' || !UUID_RE.test(id)) return null;

  return { k, id };
}

/**
 * Clamp a requested page size into the configured range.
 *
 * §6 names an unbounded feed as a risk to the Android client, so this is the
 * single place that decides how much a caller may ask for. A missing or
 * unparseable value falls back to the default rather than erroring: page size
 * is a hint, not a contract, and rejecting a request over it would turn a
 * cosmetic client bug into a failed screen.
 */
export function parsePageSize(raw: unknown, fallback: number, max: number): number {
  const parsed = typeof raw === 'string' ? Number.parseInt(raw, 10) : NaN;
  if (!Number.isFinite(parsed) || parsed <= 0) return Math.min(fallback, max);
  return Math.min(Math.trunc(parsed), max);
}

/** True when [value] is a UUID Postgres will accept. */
export function isUuid(value: string): boolean {
  return UUID_RE.test(value);
}

/**
 * The pagination parameters every list endpoint accepts.
 *
 * Both fields stay STRINGS: `limit` is clamped by [parsePageSize] rather than
 * validated as a number (a nonsense size is a hint to ignore, not a request to
 * reject), and a cursor is opaque.
 */
export interface PageQuery {
  readonly limit?: string | undefined;
  readonly cursor?: string | undefined;
}

/** One page of a keyset feed. */
export interface Page<T> {
  readonly items: T[];
  readonly nextCursor: string | null;
}

/**
 * Read a cursor, refusing a malformed one instead of silently restarting.
 *
 * Null means "no cursor supplied", which is a legitimate first page. A cursor
 * that was supplied but cannot be decoded is a 400: treating it as "start
 * over" would hide a client bug behind a page of duplicate results, which
 * looks like the server sends the same thing twice rather than like a bad
 * cursor.
 */
export function cursorOf(raw: string | undefined): Cursor | null {
  if (raw === undefined || raw === '') return null;
  const cursor = decodeCursor(raw);
  if (!cursor) {
    throw badRequest('invalid_cursor', 'The cursor is not valid.');
  }
  return cursor;
}

/**
 * Shared keyset tail: fetch one extra row to learn whether another page
 * exists, then drop it.
 *
 * A `LIMIT n + 1` probe rather than a `COUNT(*)`: the count would scan the
 * whole set to answer a question about a single row, and the extra row is
 * already in the plan.
 */
export function withLimit<T>(rows: T[], limit: number): { items: T[]; hasMore: boolean } {
  if (rows.length <= limit) return { items: rows, hasMore: false };
  return { items: rows.slice(0, limit), hasMore: true };
}
