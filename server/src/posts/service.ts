import { env } from '../env.js';
import { cursorKeyOf, isoOrNull, one, type Queryable } from '../db.js';
import { badRequest, conflict, notFound } from '../http/errors.js';
import {
  cursorOf,
  encodeCursor,
  isUuid,
  parsePageSize,
  withLimit,
  type Page,
  type PageQuery,
} from '../channels/cursor.js';
import type { ObjectStore } from '../media/store.js';
import {
  attachMediaToPost,
  lockMediaForClaim,
  mediaForPosts,
  type MediaSummary,
} from '../media/service.js';
import {
  mapPublicPost,
  POST_COLUMNS,
  type PostRow,
  type PublicPost,
  type PublicPostType,
} from '../public/service.js';

/**
 * Posts: publishing, editing and removal (§21).
 *
 * Everything here is administrator-only, because there is no other kind of
 * writer. That is the whole shape of the product: a channel broadcasts, and the
 * people who read it have no account with which to reply.
 *
 * Three rules shape it:
 *
 *  * **The post type is DERIVED, never accepted.** A client that says
 *    `type: 'video'` while attaching a JPEG would be describing its own post
 *    wrongly, and every reader would render it wrongly. The type follows from the
 *    attached file's kind, or from the presence of a link, so the two can never
 *    disagree.
 *
 *  * **Media is claimed inside the publish transaction.** The rows are locked
 *    `FOR UPDATE` and attached in the same transaction that inserts the post, so
 *    two concurrent publishes cannot both claim one upload and a failure cannot
 *    leave a post with half its media.
 *
 *  * **A removed post is invisible in SQL.** Every read filters
 *    `deleted_at IS NULL` in the WHERE clause rather than in a mapper, so no
 *    route can forget.
 *
 * Reads return the same payload the public surface returns — [PublicPost] — so a
 * post an administrator is editing and the same post a reader sees cannot drift
 * apart.
 */

/** A generous ceiling for a stored link; the DB CHECK only tests the shape. */
const MAX_LINK_LENGTH = 2048;

export interface PublishPostInput {
  readonly body?: string | undefined;
  readonly linkUrl?: string | undefined;
  readonly linkTitle?: string | undefined;
  readonly mediaIds?: readonly string[] | undefined;
}

export interface UpdatePostInput {
  readonly body?: string | null | undefined;
  readonly linkUrl?: string | null | undefined;
  readonly linkTitle?: string | null | undefined;
}

/**
 * An http(s) link of a length the database will also accept.
 *
 * Parsed rather than pattern-matched, because the DB CHECK is a backstop and
 * `new URL` is what actually rejects the shapes a regex happily admits. The
 * whitespace test is here because the CHECK rejects it too: agreeing with the
 * constraint means a bad link is a 400 the client can word, not a 500 from a
 * violated constraint.
 */
function isHttpUrl(value: string): boolean {
  if (value.length === 0 || value.length > MAX_LINK_LENGTH) return false;
  if (/\s/.test(value)) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

/**
 * A channel's posts, for its own administrator (§19).
 *
 * Deliberately not the public read: this one answers for a channel whatever its
 * status, so an administrator whose channel has been switched off can still see
 * and manage what it published. The public read refuses a suspended channel,
 * which is exactly the wrong answer for the person being asked to fix it.
 */
export async function listChannelPostsForAdmin(
  database: Queryable,
  store: ObjectStore,
  channelId: string,
  query: PageQuery
): Promise<Page<PublicPost>> {
  const limit = parsePageSize(query.limit, env.DEFAULT_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [channelId, limit + 1];
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
 * Publish a post (§21).
 *
 * The order of the checks is deliberate: shape first, so a nonsense request is
 * rejected without touching the database or locking the caller's rows; then the
 * transaction, which claims the media and writes the post together.
 */
export async function publishPost(
  database: Queryable,
  store: ObjectStore,
  adminId: string,
  channelId: string,
  input: PublishPostInput
): Promise<PublicPost> {
  const body = input.body?.trim() ?? '';
  const linkUrl = input.linkUrl?.trim() ?? '';
  const linkTitle = input.linkTitle?.trim() ?? '';
  const mediaIds = input.mediaIds ?? [];

  if (mediaIds.length > env.MAX_POST_MEDIA) {
    throw badRequest('too_many_media', `A post can hold at most ${env.MAX_POST_MEDIA} files.`);
  }
  if (new Set(mediaIds).size !== mediaIds.length) {
    throw badRequest('duplicate_media', 'The same file was attached twice.');
  }
  if (body.length > env.MAX_TEXT_LENGTH) {
    throw badRequest('text_too_long', `A post can hold at most ${env.MAX_TEXT_LENGTH} characters.`);
  }
  if (linkUrl !== '' && !isHttpUrl(linkUrl)) {
    throw badRequest('invalid_link', 'A link must be an http or https address.');
  }
  if (body === '' && linkUrl === '' && mediaIds.length === 0) {
    throw badRequest('empty_post', 'A post needs text, a link or a file.');
  }

  const postId = await database.transaction(async (tx) => {
    const media = await lockMediaForClaim(tx, adminId, mediaIds);

    // Fewer rows than asked for means an id that does not exist, or one that
    // belongs to somebody else. Both are the same answer to the caller.
    if (media.length !== mediaIds.length) {
      throw badRequest('unknown_media', 'One of those files is not available to attach.');
    }
    if (media.some((r) => r.status !== 'ready')) {
      throw conflict('media_not_ready', 'One of those files has not finished uploading.');
    }
    if (media.some((r) => r.post_id !== null)) {
      throw conflict('media_already_used', 'One of those files is already part of another post.');
    }

    const kind = media[0]?.kind ?? null;
    if (kind !== null && media.some((r) => r.kind !== kind)) {
      // One file kind per post, so `type` describes the whole post. A carousel
      // of images and videos would need per-asset types and a different
      // renderer, which is a product decision and not something to infer.
      throw badRequest('mixed_media', 'A post can hold one kind of file at a time.');
    }

    // The type follows from what the post actually carries, so a client cannot
    // describe its own post wrongly.
    const type: PublicPostType = kind ?? (linkUrl === '' ? 'text' : 'link');

    const inserted = await tx.query<{ id: string }>(
      `INSERT INTO posts (channel_id, author_id, type, body, link_url, link_title)
       VALUES ($1, $2, $3, $4, $5, $6)
       RETURNING id`,
      [
        channelId,
        adminId,
        type,
        body === '' ? null : body,
        linkUrl === '' ? null : linkUrl,
        linkTitle === '' ? null : linkTitle,
      ]
    );
    const created = one(inserted).id;

    await attachMediaToPost(tx, created, mediaIds);

    // Same transaction as the insert, so the channel's `last_post_at` cannot
    // disagree with the rows it describes. It drives the list's ordering and the
    // preview's timestamp, so a post that did not update it would be invisible.
    await tx.query(`UPDATE channels SET last_post_at = now() WHERE id = $1`, [channelId]);

    return created;
  });

  return loadPostForAdmin(database, store, postId);
}

/** A single post as an administrator sees it, or a 404. */
export async function loadPostForAdmin(
  database: Queryable,
  store: ObjectStore,
  postId: string
): Promise<PublicPost> {
  const row = await loadPostRow(database, postId);
  const media = await mediaForPosts(database, store, [row.id]);
  return mapPublicPost(row, media.get(row.id) ?? []);
}

/** The row behind a post, for scoping and for edits. */
export async function loadPostRow(database: Queryable, postId: string): Promise<PostRow> {
  if (!isUuid(postId)) throw notFound('post_not_found');
  const row = await database.queryOne<PostRow>(
    `SELECT ${POST_COLUMNS} FROM posts p WHERE p.id = $1 AND p.deleted_at IS NULL`,
    [postId]
  );
  if (!row) throw notFound('post_not_found');
  return row;
}

/**
 * Edit a post's text or link (§21).
 *
 * The type is recomputed rather than kept, because it describes the post: a text
 * post that has just been given a link IS a link post, and leaving the old type
 * would have every reader render it as plain text.
 *
 * Null clears a field and an omitted key leaves it alone — the same convention
 * the channel patch uses, so a client can serve "remove the caption" and "leave
 * the caption" from one endpoint.
 */
export async function updatePost(
  database: Queryable,
  store: ObjectStore,
  postId: string,
  patch: UpdatePostInput
): Promise<PublicPost> {
  const existing = await loadPostRow(database, postId);

  const body = patch.body === undefined ? undefined : (patch.body?.trim() ?? '');
  const linkUrl = patch.linkUrl === undefined ? undefined : (patch.linkUrl?.trim() ?? '');

  if (body !== undefined && body.length > env.MAX_TEXT_LENGTH) {
    throw badRequest('text_too_long', `A post can hold at most ${env.MAX_TEXT_LENGTH} characters.`);
  }
  if (linkUrl !== undefined && linkUrl !== '' && !isHttpUrl(linkUrl)) {
    throw badRequest('invalid_link', 'A link must be an http or https address.');
  }

  const media = await database.query<{ kind: string }>(
    `SELECT kind FROM post_media WHERE post_id = $1 ORDER BY position LIMIT 1`,
    [postId]
  );
  const kind = (media[0]?.kind ?? null) as PublicPostType | null;

  const nextBody = body === undefined ? existing.body : body === '' ? null : body;
  const nextLink = linkUrl === undefined ? existing.link_url : linkUrl === '' ? null : linkUrl;
  const nextTitle =
    patch.linkTitle === undefined
      ? existing.link_title
      : (patch.linkTitle?.trim() || null);

  if (nextBody === null && nextLink === null && kind === null) {
    throw badRequest('empty_post', 'A post needs text, a link or a file.');
  }

  const type: PublicPostType = kind ?? (nextLink === null ? 'text' : 'link');

  const rows = await database.query<PostRow>(
    `UPDATE posts
        SET body = $2, link_url = $3, link_title = $4, type = $5::post_type, edited_at = now()
      WHERE id = $1 AND deleted_at IS NULL
      RETURNING ${POST_COLUMNS.replace(/p\./g, '')}`,
    [postId, nextBody, nextLink, nextTitle, type]
  );

  const row = one(rows);
  const signed = await mediaForPosts(database, store, [postId]);
  return mapPublicPost({ ...row, id: row.id }, signed.get(postId) ?? []);
}

/**
 * Remove a post (§17).
 *
 * Soft: `deleted_at` is set and every read filters on it. §17 wants an
 * administrator to be able to remove content, and a hard delete would take the
 * post's media rows and objects with it — leaving nothing to restore if the
 * removal turns out to have been wrong.
 *
 * The media rows stay attached, which is what makes the removal immediate:
 * [mediaForPosts] is only ever asked for posts that survived the `deleted_at`
 * filter, so a removed post's images stop being served at the same moment the
 * post does.
 */
export async function deletePost(database: Queryable, postId: string): Promise<void> {
  const rows = await database.query<{ channel_id: string }>(
    `UPDATE posts SET deleted_at = now()
      WHERE id = $1 AND deleted_at IS NULL
      RETURNING channel_id`,
    [postId]
  );
  if (rows.length === 0) throw notFound('post_not_found');
}

/** The media on a post, for a caller that already has the row. */
export async function mediaOf(
  database: Queryable,
  store: ObjectStore,
  postId: string
): Promise<readonly MediaSummary[]> {
  const byPost = await mediaForPosts(database, store, [postId]);
  return byPost.get(postId) ?? [];
}

/** Re-exported so a route can word a failure without importing the read layer. */
export { isoOrNull };
