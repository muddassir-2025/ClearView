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
import type { MediaKind, ObjectStore } from '../media/store.js';

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
 *  * **No counters leave this module.** Not reactions, not views. There is no
 *    engagement column left to read — migration 012 dropped those tables — and
 *    the shapes below have nowhere to put a number.
 *
 *  * **No identity leaves this module.** Who published a post is not part of a
 *    channel's public surface.
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

export type PublicPostType = 'text' | 'image' | 'video' | 'audio' | 'link';

/**
 * A post as a reader sees it.
 *
 * There is no `viewerCanManage` and no `engagement`: a viewer cannot manage
 * anything, and there is nothing to count. A text-only post needs no bucket —
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
  readonly createdAt: string;
  readonly url: string | null;
}

/** Post columns every public post payload needs. Aliased `p` in every query. */
export const POST_COLUMNS = `
  p.id, p.channel_id, p.type, p.body, p.link_url, p.link_title, p.created_at, p.edited_at
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
}

/**
 * A row as a reader sees it.
 *
 * `poll` is mapped to `text`: the poll tables were dropped, and a row left over
 * from the previous version must render as its caption rather than as a control
 * the client cannot draw.
 */
export function mapPublicPost(row: PostRow, media: readonly MediaSummary[]): PublicPost {
  return {
    id: row.id,
    channelId: row.channel_id,
    type: row.type === 'poll' ? 'text' : (row.type as PublicPostType),
    body: row.body,
    linkUrl: row.link_url,
    linkTitle: row.link_title,
    media,
    createdAt: isoOrNull(row.created_at) ?? '',
    editedAt: isoOrNull(row.edited_at),
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
  query: PageQuery
): Promise<Page<PublicPost>> {
  const channel = await getPublicChannel(database, store, channelIdOrSlug);

  const limit = parsePageSize(query.limit, env.DEFAULT_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [channel.id, limit + 1];
  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (p.created_at, p.id) < ($3::timestamptz, $4::uuid)`;
  }

  const rows = await database.query<PostRow>(
    `SELECT ${POST_COLUMNS}
       FROM posts p
      WHERE p.channel_id = $1 AND p.deleted_at IS NULL
        ${keyset}
      ORDER BY p.created_at DESC, p.id DESC
      LIMIT $2`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const media = await mediaForPosts(
    database,
    store,
    items.map((r) => r.id)
  );
  const last = items[items.length - 1];

  return {
    items: items.map((r) => mapPublicPost(r, media.get(r.id) ?? [])),
    nextCursor:
      hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
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
        AND c.deleted_at IS NULL
        AND c.status = 'active'`,
    [postId]
  );
  if (!row) throw notFound('post_not_found');

  const media = await mediaForPosts(database, store, [row.id]);

  return {
    ...mapPublicPost(row, media.get(row.id) ?? []),
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
    created_at: unknown;
  }>(
    `SELECT m.id, m.post_id, m.kind, m.object_key, m.content_type,
            m.width, m.height, m.duration_ms, p.created_at
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
