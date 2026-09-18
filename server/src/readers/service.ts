import { cursorKeyOf, isoOrNull, type Queryable } from '../db.js';
import { notFound } from '../http/errors.js';
import type { ObjectStore } from '../media/store.js';
import { signObjectUrl } from '../media/service.js';
import type { VerifiedIdentity } from '../identity/verifier.js';
import {
  CHANNEL_COLUMNS,
  LAST_POST_COLUMNS,
  LAST_POST_JOIN,
  loadChannelRow,
  mapChannel,
  resolveChannelLookup,
  type ChannelPayload,
  type ChannelRow,
} from '../channels/service.js';
import {
  cursorOf,
  encodeCursor,
  parsePageSize,
  withLimit,
  type Page,
  type PageQuery,
} from '../channels/cursor.js';
import { env } from '../env.js';

/**
 * A reader and what they follow (§3, §4, §5).
 *
 * The reader side of Good Post. Everything here is scoped to one verified uid,
 * so nothing in this file takes a reader from the request — the caller must
 * already have proved who it is (see `identity/verifier.ts`), and a route in
 * this file that forgot to would be a route that reads somebody else's state.
 *
 * ## What a reader may and may not do
 *
 * May: follow, unfollow, mute a channel, mark one read, and list their own
 * follows. That is the whole vocabulary, and it is small on purpose — §5 and §6
 * ask for a channel list, a badge and a mute switch, and none of those need an
 * account, a profile, a follower count or a public identity.
 *
 * May NOT: see another reader's anything, or see how many readers follow a
 * channel. A follower count is an engagement metric (§1 excludes them), and it
 * is also the one number that would turn this table into a popularity ranking
 * the moment somebody sorted by it. No query here returns a count of follows;
 * `unreadCount` is a count of POSTS, and only ever for the caller.
 *
 * ## Why following changes nothing about reading
 *
 * Following is a bookkeeping fact about the reader, not a permission (§4).
 * Every channel this API exposes is readable without it, and every channel a
 * reader has followed is readable the moment they stop. The retention window
 * (§14) is what bounds how far back anybody can read, following or not.
 */

/** The row behind a uid. */
export interface ReaderRef {
  readonly id: string;
}

/**
 * The reader's relationship to one channel.
 *
 * Returned by follow, unfollow, mute and mark-read, so the Android client can
 * update one row of the home screen from the response rather than refetching the
 * list it was already showing (§4's "updates quickly").
 */
export interface FollowState {
  readonly channelId: string;
  readonly following: boolean;
  readonly notificationsMuted: boolean;
  readonly followedAt: string | null;
  /** Posts published since the reader last read this channel (§5). */
  readonly unreadCount: number;
}

/** A channel on the reader's home screen: a channel payload, plus their state. */
export type FollowedChannel = ChannelPayload & {
  readonly following: true;
  readonly notificationsMuted: boolean;
  readonly followedAt: string;
  readonly unreadCount: number;
};

interface FollowedChannelRow extends ChannelRow {
  notifications_muted: boolean;
  followed_at: unknown;
  last_read_at: unknown;
  /**
   * `count(*)` is a bigint, and node-postgres hands bigints back as STRINGS to
   * avoid silently losing precision. A count of unread posts cannot exceed a
   * handful, so it is coerced at the edge instead of carried as a string that
   * every caller would have to remember to parse.
   */
  unread_count: string | number;
}

/**
 * Record that this uid exists, and return its row.
 *
 * Called on the way into every reader-scoped request, so it doubles as the
 * liveness signal: `last_seen_at` is the only evidence this product has that a
 * uid is still in use, because a reader never signs in in a way we can observe.
 *
 * An upsert rather than a select-then-insert because two requests can arrive
 * together from a client that has just been created — the app signs in and
 * immediately asks what it follows — and a plain insert would fail one of them
 * on the unique index for no reason a client could act on.
 */
export async function ensureReader(
  database: Queryable,
  identity: VerifiedIdentity
): Promise<ReaderRef> {
  const row = await database.queryOne<{ id: string }>(
    `INSERT INTO readers (firebase_uid)
     VALUES ($1)
     ON CONFLICT (firebase_uid) DO UPDATE SET last_seen_at = now()
     RETURNING id`,
    [identity.uid]
  );

  // Unreachable: an insert-or-update always returns its row. Thrown rather than
  // cast so a future change to the statement cannot turn this into a silent
  // undefined that is used as a reader id.
  if (!row) throw new Error('[readers] upsert returned no row');
  return { id: row.id };
}

/**
 * Follow a channel (§4).
 *
 * Idempotent in the sense the UI needs: tapping follow twice leaves one follow
 * and does not move `followed_at`, which matters because `followed_at` is what
 * bounds the initial unread count. Resetting it on a repeat tap would make a
 * channel's whole retention window unread again.
 *
 * A channel that is not `active` answers `channel_not_found`, exactly as reading
 * it would: a reader cannot follow a channel they cannot open, and telling them
 * it exists but is suspended would describe moderation state to somebody with
 * no business reading it.
 */
export async function followChannel(
  database: Queryable,
  readerId: string,
  idOrSlug: string
): Promise<FollowState> {
  const channel = await requireActiveChannel(database, idOrSlug);

  const inserted = await database.queryOne<FollowRow>(
    `INSERT INTO channel_follows (reader_id, channel_id)
     VALUES ($1, $2)
     ON CONFLICT (reader_id, channel_id) DO NOTHING
     RETURNING notifications_muted, followed_at, last_read_at`,
    [readerId, channel.id]
  );

  // The row that existed before this call when nothing was inserted, so a repeat
  // tap reports the ORIGINAL follow date rather than pretending it just happened.
  const row = inserted ?? (await loadFollow(database, readerId, channel.id));
  if (!row) throw new Error('[readers] follow inserted no row and found none');

  return {
    channelId: channel.id,
    following: true,
    notificationsMuted: row.notifications_muted,
    followedAt: isoOrNull(row.followed_at),
    unreadCount: await unreadCountOf(database, channel.id, row),
  };
}

/**
 * Stop following a channel (§4).
 *
 * Removes the relationship, which is where this parts company with mute: the
 * read position and the mute go with it, so re-following starts clean rather
 * than inheriting a read position from a previous life. The alternative — a
 * follow flag beside a state that outlives it — is a row every query then has to
 * remember to filter.
 *
 * Nothing about the channel is touched, and nothing the reader has already read
 * is affected: the server keeps its copy of the posts for its retention window
 * either way (§14).
 */
export async function unfollowChannel(
  database: Queryable,
  readerId: string,
  idOrSlug: string
): Promise<FollowState> {
  const channel = await requireActiveChannel(database, idOrSlug);

  await database.query(
    `DELETE FROM channel_follows WHERE reader_id = $1 AND channel_id = $2`,
    [readerId, channel.id]
  );

  return {
    channelId: channel.id,
    following: false,
    notificationsMuted: false,
    followedAt: null,
    unreadCount: 0,
  };
}

/**
 * The reader's home screen: what they follow, most recently active first (§4, §5).
 *
 * Ordered by the channel's activity rather than by when it was followed, because
 * that is the order a reader reads a channel list in — the one that published
 * most recently is the one with something new in it. A channel that has never
 * published falls back to its creation time, which is what `LAST_POST_JOIN`'s
 * lateral already provides for the public list.
 */
export async function listFollowedChannels(
  database: Queryable,
  store: ObjectStore,
  readerId: string,
  query: PageQuery
): Promise<Page<FollowedChannel>> {
  const limit = parsePageSize(query.limit, env.DEFAULT_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [readerId, limit + 1];
  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (COALESCE(c.last_post_at, c.created_at), c.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`;
  }

  const rows = await database.query<FollowedChannelRow>(
    `SELECT ${CHANNEL_COLUMNS},
            ${LAST_POST_COLUMNS},
            f.notifications_muted,
            f.followed_at,
            f.last_read_at,
            -- Unread is bounded by the follow and by the retention window, so it
            -- cannot grow without limit: posts published before the reader
            -- followed are not theirs to be told about (§5's badge is a nudge,
            -- not a backlog), and posts older than the window are gone (§14).
            (SELECT count(*) FROM posts p
              WHERE p.channel_id = c.id
                AND p.deleted_at IS NULL
                AND p.created_at > COALESCE(f.last_read_at, f.followed_at)) AS unread_count
       FROM channel_follows f
       JOIN channels c ON c.id = f.channel_id
       LEFT JOIN channel_categories cat ON cat.slug = c.category_slug
       ${LAST_POST_JOIN}
      WHERE f.reader_id = $1
        AND c.status = 'active'
        ${keyset}
      ORDER BY COALESCE(c.last_post_at, c.created_at) DESC, c.id DESC
      LIMIT $2`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  return {
    items: await Promise.all(
      items.map(async (row) => {
        const iconUrl = await signObjectUrl(store, row.icon_object_key);
        return {
          ...mapChannel(row, false, iconUrl),
          following: true as const,
          notificationsMuted: row.notifications_muted,
          followedAt: isoOrNull(row.followed_at) ?? '',
          unreadCount: Number(row.unread_count),
        };
      })
    ),
    nextCursor:
      hasMore && last
        ? encodeCursor({ k: cursorKeyOf(last.last_post_at ?? last.created_at), id: last.id })
        : null,
  };
}

/**
 * Mute or unmute a channel's notifications (§6).
 *
 * Requires a follow, because the mute is a property of the relationship and
 * there is nowhere to put it otherwise. 404 `not_following` rather than creating
 * the follow: muting is not a way to subscribe, and a mute that silently
 * followed a channel would put it on the home screen.
 */
export async function setChannelMuted(
  database: Queryable,
  readerId: string,
  idOrSlug: string,
  muted: boolean
): Promise<FollowState> {
  const channel = await requireActiveChannel(database, idOrSlug);

  const row = await database.queryOne<FollowRow>(
    `UPDATE channel_follows
        SET notifications_muted = $3
      WHERE reader_id = $1 AND channel_id = $2
      RETURNING notifications_muted, followed_at, last_read_at`,
    [readerId, channel.id, muted]
  );

  if (!row) throw notFound('not_following');

  return {
    channelId: channel.id,
    following: true,
    notificationsMuted: row.notifications_muted,
    followedAt: isoOrNull(row.followed_at),
    unreadCount: await unreadCountOf(database, channel.id, row),
  };
}

/**
 * Clear the unread badge for a channel (§5): the reader has opened it.
 *
 * The timestamp is `now()` rather than "the newest post I was shown", and the
 * difference matters at the boundary: the client's newest post could be a
 * response that was already stale when it arrived, and marking read to that
 * point would leave the badge showing a count for posts the reader had already
 * scrolled past. `now()` can only under-count, which is the harmless direction —
 * a missed badge, not a badge that will not clear.
 *
 * A channel the reader does not follow has no badge to clear, and answers as
 * such rather than as an error: opening a channel without following it is the
 * normal way to read (§2, §4).
 */
export async function markChannelRead(
  database: Queryable,
  readerId: string,
  idOrSlug: string
): Promise<FollowState> {
  const channel = await requireActiveChannel(database, idOrSlug);

  const row = await database.queryOne<FollowRow>(
    `UPDATE channel_follows
        SET last_read_at = now()
      WHERE reader_id = $1 AND channel_id = $2
      RETURNING notifications_muted, followed_at, last_read_at`,
    [readerId, channel.id]
  );

  if (!row) {
    return {
      channelId: channel.id,
      following: false,
      notificationsMuted: false,
      followedAt: null,
      unreadCount: 0,
    };
  }

  return {
    channelId: channel.id,
    following: true,
    notificationsMuted: row.notifications_muted,
    followedAt: isoOrNull(row.followed_at),
    // Zero by construction: the badge was just cleared, and reporting anything
    // else would have the client render a count the database disagrees with.
    unreadCount: 0,
  };
}

interface FollowRow {
  notifications_muted: boolean;
  followed_at: unknown;
  last_read_at: unknown;
}

async function loadFollow(
  database: Queryable,
  readerId: string,
  channelId: string
): Promise<FollowRow | null> {
  return database.queryOne<FollowRow>(
    `SELECT notifications_muted, followed_at, last_read_at
       FROM channel_follows
      WHERE reader_id = $1 AND channel_id = $2`,
    [readerId, channelId]
  );
}

/**
 * The unread count for a follow the caller already holds.
 *
 * The same expression the list query uses, and deliberately the same: the badge
 * a reader sees after a follow must equal the badge they see when the list next
 * loads, or the number would visibly change with no new post behind it.
 *
 * `last_read_at` is passed as a DATE and compared against a timestamptz, so the
 * value keeps its instant instead of a formatted string that would be re-parsed
 * against a different timezone.
 */
async function unreadCountOf(
  database: Queryable,
  channelId: string,
  follow: Pick<FollowRow, 'followed_at' | 'last_read_at'>
): Promise<number> {
  const readAt = follow.last_read_at instanceof Date ? follow.last_read_at : null;
  const row = await database.queryOne<{ unread_count: string | number }>(
    `SELECT count(*) AS unread_count
       FROM posts p
      WHERE p.channel_id = $1
        AND p.deleted_at IS NULL
        AND p.created_at > COALESCE($2::timestamptz, $3::timestamptz)`,
    [channelId, readAt, follow.followed_at]
  );
  return Number(row?.unread_count ?? 0);
}

/** A channel a reader may act on: it exists and it is open. */
/**
 * The channel a reader-scoped path names, from an id or a slug.
 *
 * Exported because the reaction routes resolve a channel the same way a follow
 * does, and one resolver is what keeps "which channel is this" answering the
 * same 404 to every reader-facing path.
 */
export async function requireActiveChannel(
  database: Queryable,
  idOrSlug: string
): Promise<{ id: string }> {
  const row = await loadChannelRow(database, resolveChannelLookup(idOrSlug));
  if (row.status !== 'active') throw notFound('channel_not_found');
  return { id: row.id };
}
