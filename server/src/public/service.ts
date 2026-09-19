import { env } from '../env.js';
import { cursorKeyOf, isoOrNull, type Queryable } from '../db.js';
import { notFound } from '../http/errors.js';
import {
  cursorOf,
  encodeCursor,
  isUuid,
  parsePageSize,
  withLimit,
  type Page,
  type PageQuery,
} from '../channels/cursor.js';
import { getPublicChannel, type ChannelPayload } from '../channels/service.js';
import { mediaForPosts, signObjectUrl, type MediaSummary } from '../media/service.js';
import { reactionsForPosts, type ReactionCount } from '../reactions/service.js';
import { UnconfiguredObjectStore, type MediaKind, type ObjectStore } from '../media/store.js';

/**
 * The PUBLIC read surface (§24, §26).
 *
 * Good Post is a broadcast system, not a social network, and this module is the
 * line that makes that true: a reader needs no account, no token and no session
 * to browse channels, open one, or read its posts. Everything here is a
 * `SELECT`, so there is no ownership question to answer and no per-viewer state
 * to compute — which is precisely why it lives apart from the admin services
 * rather than as a nullable-user branch inside them.
 *
 * Channels themselves are read through [channels/service], which owns that
 * table for both surfaces. What is left here is posts and media: the two things
 * an administrator writes and a reader only ever reads.
 *
 * Two rules, and each exists because the alternative leaks something:
 *
 *  * **No IDENTITY leaves this module.** Who published a post is not part of a
 *    channel's public surface, and neither is who reacted to it.
 *
 *  * **Counters are counts, not lists.** A post carries how many readers chose
 *    each reaction and how many times it has been read (§9). Those used to be
 *    refused here outright, and that was the right default for a broadcast
 *    product with no reader interaction at all; reactions changed the
 *    requirement, not the reasoning, so the rule is now the narrower one: a
 *    number may leave, a name may not. Nothing here returns a reader id, an
 *    email or a list of who reacted, and nothing in the product may sort by
 *    these — they are drawn on a card and nothing else depends on them.
 *
 * Soft-deleted posts are filtered in SQL for the same reason channels are: a
 * removed post must not be readable, and it must not appear in a preview.
 */

export type { ChannelPayload };

export type PublicChannel = ChannelPayload;

/** The channel a post belongs to, as a post payload names it. */
export interface PublicChannelRef {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
}

export type PublicPostType = 'text' | 'image' | 'video' | 'link' | 'document';

/**
 * A post as a reader sees it.
 *
 * There is still no `viewerCanManage` and no per-viewer state: a viewer cannot
 * manage anything, and whether a given reader has reacted is theirs, fetched
 * separately (see `reactions/service.ts`). A text-only post needs no bucket —
 * `media` is simply empty — which is what keeps S3 optional (§22).
 */
export interface PublicPost {
  readonly id: string;
  readonly channelId: string;
  readonly type: PublicPostType;
  readonly body: string | null;
  readonly linkUrl: string | null;
  readonly linkTitle: string | null;
  readonly media: readonly MediaSummary[];
  readonly createdAt: string;
  readonly editedAt: string | null;
  /**
   * How many readers chose each emoji (§9), commonest first.
   *
   * A count per emoji and nothing else — no reader is named, and the caller
   * cannot ask who. Empty for a post nobody has reacted to, which is most of
   * them, so a client draws nothing rather than an empty row.
   */
  readonly reactions: readonly ReactionCount[];
  /**
   * How many times this post has been read (§9).
   *
   * Approximate, and documented as such where it is written — see
   * `004_post_views.sql`. It is here because a channel's posts are its audience,
   * and a publisher is entitled to a rough sense of whether anyone is reading.
   */
  readonly views: number;
  /** Present only on [getPublicPost], where a reader arrives without a channel. */
  readonly channel?: PublicChannelRef;
}

/** One asset in a channel's "Media and links" gallery (§13). */
export interface PublicMediaItem {
  readonly id: string;
  readonly postId: string;
  readonly kind: MediaKind;
  readonly contentType: string;
  readonly width: number | null;
  readonly height: number | null;
  readonly durationMs: number | null;
  /** The sender's file name, for a document. Null for the other kinds. */
  readonly fileName: string | null;
  readonly createdAt: string;
  readonly url: string | null;
}

/**
 * The page query plus a search term (§9).
 *
 * A channel's history is long and a reader arrives knowing a word from the
 * message rather than when it was sent, so the term narrows the SAME keyset
 * walk rather than replacing it: results stay paged, stay newest-first, and
 * carry the same payload as the unfiltered read.
 */
export interface PostQuery extends PageQuery {
  readonly q?: string | undefined;
}

/**
 * A search term, or null when there is nothing to search for.
 *
 * Whitespace-only is null rather than a term: an empty box means "everything",
 * and `%  %`-style matching would otherwise return only the posts that contain
 * two spaces. Capped so a term cannot be turned into a large scan by a client
 * that sends a paragraph.
 */
export function searchTermOf(raw: string | undefined): string | null {
  const term = (raw ?? '').trim();
  if (term === '') return null;
  return term.slice(0, MAX_SEARCH_TERM_LENGTH);
}

/** Longest term the search will use. Longer input is truncated, not refused. */
const MAX_SEARCH_TERM_LENGTH = 100;

/** Post columns every public post payload needs. Aliased `p` in every query. */
export const POST_COLUMNS = `
  p.id, p.channel_id, p.type, p.body, p.link_url, p.link_title, p.created_at, p.edited_at,
  p.view_count
`;

export interface PostRow {
  id: string;
  channel_id: string;
  type: string;
  body: string | null;
  link_url: string | null;
  link_title: string | null;
  created_at: unknown;
  edited_at: unknown;
  view_count?: number | null;
}

/**
 * A row as a reader sees it.
 *
 * The cast is honest because the database enforces its own range:
 * `posts_type_is_renderable` constrains `posts.type` to the four shapes in
 * [PublicPostType], and the write
 * path derives that type from the attachments rather than trusting a client. The
 * previous version's `poll -> text` translation is gone with the rows it existed
 * for — there is no longer a type that needs drawing as something it is not.
 */
export function mapPublicPost(
  row: PostRow,
  media: readonly MediaSummary[],
  reactions: readonly ReactionCount[] = []
): PublicPost {
  return {
    id: row.id,
    channelId: row.channel_id,
    type: row.type as PublicPostType,
    body: row.body,
    linkUrl: row.link_url,
    linkTitle: row.link_title,
    media,
    createdAt: isoOrNull(row.created_at) ?? '',
    editedAt: isoOrNull(row.edited_at),
    reactions,
    views: row.view_count ?? 0,
  };
}

/**
 * A channel's posts, newest first (§9).
 *
 * The channel is resolved first and must be public, so asking for the posts of a
 * channel that does not exist, is suspended, or was deleted is the same 404 as
 * asking for the channel itself — the reader learns nothing extra from the
 * failure.
 */
export async function listPublicChannelPosts(
  database: Queryable,
  store: ObjectStore,
  channelIdOrSlug: string,
  query: PostQuery
): Promise<Page<PublicPost>> {
  const channel = await getPublicChannel(database, store, channelIdOrSlug);

  const limit = parsePageSize(query.limit, env.DEFAULT_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);
  const term = searchTermOf(query.q);

  // Placeholders are numbered as the conditions are ADDED rather than being
  // written out by hand: search is optional and the keyset is optional, so a
  // hard-coded `$3` would point at the wrong value the first time the two
  // arrive in the other order — and it would do it silently, comparing a
  // timestamp to a search term.
  const params: unknown[] = [channel.id, limit + 1];
  const conditions: string[] = [];

  if (cursor) {
    params.push(cursor.k, cursor.id);
    conditions.push(
      `(p.created_at, p.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`
    );
  }

  if (term !== null) {
    params.push(term.toLowerCase());
    // `strpos`, not `ILIKE '%' || $n || '%'`: a term containing `%` or `_` is
    // then a literal rather than a pattern, and a reader searching for "100%"
    // gets the posts that say 100% instead of every post in the channel. The
    // list is already bounded by the channel, so the missing trigram index on
    // this predicate costs a scan of one channel's history (§6).
    const at = `$${params.length}`;
    conditions.push(
      `(strpos(lower(p.body), ${at}) > 0 OR strpos(lower(p.link_title), ${at}) > 0)`
    );
  }

  const where = conditions.map((condition) => `AND ${condition}`).join('\n        ');

  const rows = await database.query<PostRow>(
    `SELECT ${POST_COLUMNS}
       FROM posts p
      WHERE p.channel_id = $1 AND p.deleted_at IS NULL
        ${where}
      ORDER BY p.created_at DESC, p.id DESC
      LIMIT $2`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  // Two aggregates over the page, each one query rather than one per post.
  const ids = items.map((r) => r.id);
  const [media, reactions] = await Promise.all([
    mediaForPosts(database, store, ids),
    reactionsForPosts(database, ids),
  ]);
  const last = items[items.length - 1];

  return {
    items: items.map((r) => mapPublicPost(r, media.get(r.id) ?? [], reactions.get(r.id) ?? [])),
    nextCursor:
      hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
}

/**
 * Count reads of some of a channel's posts (§9).
 *
 * The one WRITE on the reader surface, and it is deliberately the smallest one
 * that can work: a set of post ids in a single statement, scoped to the channel
 * they were read in.
 *
 * Why it is scoped rather than "increment these ids": an endpoint that took ids
 * alone would let anybody inflate any post in the product, including in a
 * channel they have never opened. Requiring the channel means the worst a caller
 * can do is add to counts they could already see, on a channel that is public by
 * definition — and it costs one predicate.
 *
 * Why the ids are INTERSECTED with the channel rather than validated first: the
 * caller is reporting what it drew, and a post that has since been removed (or
 * belongs to another channel because a feed was paged across a change) is not an
 * error. It simply does not match, and nothing is counted for it. Refusing the
 * whole batch over one stale id would lose the counts for the posts that are
 * fine.
 *
 * Soft-deleted posts are excluded for the same reason they are excluded from
 * every read: a removed post is gone, and counting its reads would leave a
 * number behind for something no reader can see.
 */
export async function recordPostViews(
  database: Queryable,
  channelIdOrSlug: string,
  postIds: readonly string[]
): Promise<number> {
  // Resolved through the same lookup every other channel read uses, so a
  // suspended or missing channel refuses here exactly as it does there — and so
  // the ids below are known to belong to a channel a reader could see.
  const channel = await getPublicChannel(database, new UnconfiguredObjectStore(), channelIdOrSlug);

  const ids = postIds.filter((id) => isUuid(id));
  if (ids.length === 0) return 0;

  const rows = await database.query(
    `UPDATE posts p
        SET view_count = p.view_count + 1
      WHERE p.channel_id = $1
        AND p.id = ANY($2::uuid[])
        AND p.deleted_at IS NULL
      RETURNING p.id`,
    [channel.id, ids]
  );

  return rows.length;
}

/**
 * One post, with the channel it came from (§9).
 *
 * A post is reachable directly — a link, a share, a notification — so it carries
 * its channel's identity; without it a reader who arrived here would have no way
 * back to the channel that published it.
 */
export async function getPublicPost(
  database: Queryable,
  store: ObjectStore,
  postId: string
): Promise<PublicPost> {
  if (!isUuid(postId)) throw notFound('post_not_found');

  const row = await database.queryOne<PostRow & { channel_slug: string; channel_name: string }>(
    `SELECT ${POST_COLUMNS},
            c.slug AS channel_slug,
            c.name AS channel_name
       FROM posts p
       JOIN channels c ON c.id = p.channel_id
      WHERE p.id = $1
        AND p.deleted_at IS NULL
        AND c.status = 'active'`,
    [postId]
  );
  if (!row) throw notFound('post_not_found');

  const media = await mediaForPosts(database, store, [row.id]);
  const reactions = await reactionsForPosts(database, [row.id]);

  return {
    ...mapPublicPost(row, media.get(row.id) ?? [], reactions.get(row.id) ?? []),
    channel: { id: row.channel_id, slug: row.channel_slug, name: row.channel_name },
  };
}

/**
 * The channel's recent images and videos (§13).
 *
 * A gallery over the posts table rather than the media table alone: a media row
 * is only ever part of a post, and joining back is what lets a removed post take
 * its assets out of the gallery with it — the same rule the posts read enforces,
 * applied to a second query so the two cannot disagree.
 *
 * Unconfigured storage yields `url: null` rather than an error (§22): a text
 * deployment still lists its media rows honestly, and the client shows an
 * unavailable tile rather than a broken image.
 */
export async function listPublicChannelMedia(
  database: Queryable,
  store: ObjectStore,
  channelIdOrSlug: string,
  query: PageQuery
): Promise<Page<PublicMediaItem>> {
  const channel = await getPublicChannel(database, store, channelIdOrSlug);

  const limit = parsePageSize(query.limit, env.DEFAULT_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [channel.id, limit + 1];
  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (p.created_at, m.id) < ($3::timestamptz, $4::uuid)`;
  }

  const rows = await database.query<{
    id: string;
    post_id: string;
    kind: MediaKind;
    object_key: string;
    content_type: string;
    width: number | null;
    height: number | null;
    duration_ms: number | null;
    file_name: string | null;
    created_at: unknown;
  }>(
    `SELECT m.id, m.post_id, m.kind, m.object_key, m.content_type,
            m.width, m.height, m.duration_ms, m.file_name, p.created_at
       FROM post_media m
       JOIN posts p ON p.id = m.post_id
      WHERE p.channel_id = $1
        AND p.deleted_at IS NULL
        ${keyset}
      ORDER BY p.created_at DESC, m.id DESC
      LIMIT $2`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  const signed = await Promise.all(
    items.map(async (r) => ({
      id: r.id,
      postId: r.post_id,
      kind: r.kind,
      contentType: r.content_type,
      width: r.width,
      height: r.height,
      durationMs: r.duration_ms,
      fileName: r.file_name,
      createdAt: isoOrNull(r.created_at) ?? '',
      url: await signObjectUrl(store, r.object_key),
    }))
  );

  return {
    items: signed,
    nextCursor:
      hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
}
