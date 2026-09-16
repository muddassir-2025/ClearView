import { env } from '../env.js';
import { cursorKeyOf, isoOrNull, one, type Queryable } from '../db.js';
import { badRequest, conflict, forbidden, notFound } from '../http/errors.js';
import {
  cursorOf,
  encodeCursor,
  isUuid,
  parsePageSize,
  withLimit,
  type Page,
  type PageQuery,
} from '../channels/cursor.js';
import {
  loadViewable as loadViewableChannel,
  roleOf as channelRoleOf,
  type ChannelRole,
} from '../channels/service.js';
import { contentVisibleTo } from '../channels/visibility.js';
import {
  createPollInTransaction,
  engagementForPosts,
  normalisePollInput,
  type CreatePollInput,
  type PostEngagement,
} from '../engagement/service.js';
import {
  attachMediaToPost,
  lockMediaForClaim,
  mediaForPosts,
  type MediaSummary,
} from '../media/service.js';
import type { ObjectStore } from '../media/store.js';

/**
 * Posts (§8), channel history and the aggregated feed (§4).
 *
 * Three rules shape everything here:
 *
 *  * **The post type is DERIVED, never accepted.** A client that says
 *    `type: 'video'` while attaching a JPEG would be describing its own post
 *    wrongly, and every reader would render it wrongly. The type follows from
 *    the attached file's kind, or from the presence of a link, so the two can
 *    never disagree.
 *
 *  * **Media is claimed inside the publish transaction.** The rows are locked
 *    `FOR UPDATE` and attached in the same transaction that inserts the post,
 *    so two concurrent publishes cannot both claim one upload and a failure
 *    cannot leave a post with half its media.
 *
 *  * **A soft-deleted post is invisible in SQL.** Every read filters
 *    `deleted_at IS NULL` in the WHERE clause rather than in a mapper, so no
 *    route can forget — and `mediaDownloadUrl` refuses the same rows, which is
 *    what makes a removal actually remove the asset (§25).
 *
 * §11's history window is deliberately absent from the reads: it is a
 * retention policy enforced by the sweep, not a per-read filter. Keeping it out
 * of the queries means an operator can shorten the window without changing what
 * a reader sees mid-request, and a post disappears at the moment it is purged
 * rather than at an unpredictable boundary.
 */

export type PostType = 'text' | 'image' | 'video' | 'audio' | 'link' | 'poll';

/** Roles that may publish, edit and remove posts (§7). */
const PUBLISHING_ROLES: readonly ChannelRole[] = ['owner', 'editor'];

/** A generous ceiling for a stored link; the DB CHECK only tests the shape. */
const MAX_LINK_LENGTH = 2048;

/**
 * The channel identity a feed row carries.
 *
 * Three fields, and no owner, follower state or analytics: a feed is a list of
 * posts, and the only thing it needs about the channel is what to render beside
 * one (§4).
 */
export interface PostChannelRef {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
  readonly iconObjectKey: string | null;
}

export interface PostSummary {
  readonly id: string;
  readonly channelId: string;
  readonly type: PostType;
  readonly body: string | null;
  readonly linkUrl: string | null;
  readonly linkTitle: string | null;
  readonly media: readonly MediaSummary[];
  readonly createdAt: string;
  readonly editedAt: string | null;
  readonly isEdited: boolean;
  /**
   * Whether THIS viewer may edit or remove it. Per-viewer rather than per-post,
   * like `viewerRole` on a channel: the client needs it to decide whether to
   * offer the controls, and asking the client to re-derive it from a role would
   * put an authorization decision in the UI layer (§32).
   */
  readonly viewerCanManage: boolean;
  /**
   * Reactions, views and the poll, already aggregated (§13, §14, §15).
   *
   * Part of a post's payload rather than a second request per post, because
   * every consumer of this shape — the feed, channel history, the publish
   * response — needs it, and a client that had to fetch it separately would
   * either do so once per post or render counts that do not match the post
   * beside them.
   */
  readonly engagement: PostEngagement;
}

/** A post in the aggregated feed, which spans channels and so must name one. */
export interface FeedPost extends PostSummary {
  readonly channel: PostChannelRef;
}

export interface PublishPostInput {
  readonly body?: string | undefined;
  readonly linkUrl?: string | undefined;
  readonly linkTitle?: string | undefined;
  readonly mediaIds?: readonly string[] | undefined;
  /** §14. A poll post's content is its poll; the body becomes a caption. */
  readonly poll?: CreatePollInput | undefined;
}

export interface UpdatePostInput {
  readonly body?: string | null | undefined;
  readonly linkUrl?: string | null | undefined;
  readonly linkTitle?: string | null | undefined;
}

/** Columns every post payload needs, aliased `p` in every query. */
const POST_COLUMNS = `
  p.id, p.channel_id, p.type, p.body, p.link_url, p.link_title,
  p.created_at, p.edited_at
`;

interface PostRow {
  id: string;
  channel_id: string;
  type: PostType;
  body: string | null;
  link_url: string | null;
  link_title: string | null;
  created_at: unknown;
  edited_at: unknown;
}

interface FeedRow extends PostRow {
  channel_slug: string;
  channel_name: string;
  channel_icon_object_key: string | null;
  viewer_role: ChannelRole | null;
}

function mapPost(
  row: PostRow,
  media: readonly MediaSummary[],
  viewerCanManage: boolean,
  engagement: PostEngagement
): PostSummary {
  const editedAt = isoOrNull(row.edited_at);
  return {
    id: row.id,
    channelId: row.channel_id,
    type: row.type,
    body: row.body,
    linkUrl: row.link_url,
    linkTitle: row.link_title,
    media,
    createdAt: isoOrNull(row.created_at) ?? '',
    editedAt,
    isEdited: editedAt !== null,
    viewerCanManage,
    engagement,
  };
}

/** The all-zero engagement shape, so no payload has a nullable `engagement`. */
function emptyEngagement(): PostEngagement {
  return {
    reactions: [],
    reactionTotal: 0,
    viewerReaction: null,
    uniqueViewers: 0,
    totalViews: 0,
    viewerHasViewed: false,
    poll: null,
  };
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
 * Whether a post is still inside its edit window (§7).
 *
 * Computed from the stored `created_at` in Node rather than as `now()` in SQL,
 * so the window is testable by backdating one row instead of by moving the
 * database's clock. A 30-day window cannot be sensitive to clock skew between
 * the process and Postgres.
 */
function withinEditWindow(createdAt: unknown): boolean {
  const created = createdAt instanceof Date ? createdAt.getTime() : Date.parse(String(createdAt));
  if (!Number.isFinite(created)) return false;
  return Date.now() - created <= env.EDIT_WINDOW_DAYS * 86_400_000;
}

/**
 * Refuse a caller who does not administer this channel (§32).
 *
 * The role is read from `channel_admins` for this exact channel, never from the
 * request. `responder` is excluded: that role exists to answer follower
 * messages (§16), and broadcasting as the channel is a different power from
 * replying on its behalf.
 */
async function assertMayManagePosts(
  database: Queryable,
  channelId: string,
  userId: string
): Promise<void> {
  const role = await channelRoleOf(database, channelId, userId);
  if (role === null || !PUBLISHING_ROLES.includes(role)) {
    throw forbidden('post_forbidden', 'Only a channel admin can do that.');
  }
}

/**
 * Publish a post (§8).
 *
 * The order of the checks is deliberate: load, authorize, then consider the
 * channel's status. Reversed, a stranger would learn that a channel is
 * suspended, which is moderation state they have no business reading.
 */
export async function publishPost(
  database: Queryable,
  store: ObjectStore,
  userId: string,
  channelId: string,
  input: PublishPostInput
): Promise<FeedPost> {
  const channel = await loadViewableChannel(database, channelId);
  await assertMayManagePosts(database, channel.id, userId);
  if (channel.status !== 'active') {
    throw forbidden('channel_unavailable', 'This channel is not currently available.');
  }

  const body = input.body?.trim() ?? '';
  const linkUrl = input.linkUrl?.trim() ?? '';
  const linkTitle = input.linkTitle?.trim() ?? '';
  const mediaIds = input.mediaIds ?? [];

  // Normalised BEFORE the transaction opens, so an unusable poll is a plain
  // 400 instead of a post-and-media claim that gets rolled back.
  const poll = input.poll === undefined ? null : normalisePollInput(input.poll);

  // Shape first, so a nonsense request is rejected without touching the
  // database or the caller's locked rows.
  if (mediaIds.length > env.MAX_POST_MEDIA) {
    throw badRequest(
      'too_many_media',
      `A post can hold at most ${env.MAX_POST_MEDIA} files.`
    );
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
  if (body === '' && linkUrl === '' && mediaIds.length === 0 && poll === null) {
    throw badRequest('empty_post', 'A post needs text, a link, a file or a poll.');
  }
  if (poll !== null && mediaIds.length > 0) {
    // One thing per post keeps `type` a description of the whole post rather
    // than of its first field. A poll plus a photo would need a renderer rule
    // no product decision has been made about.
    throw badRequest('poll_with_media', 'A poll post cannot also carry a file.');
  }

  const postId = await database.transaction(async (tx) => {
    const media = await lockMediaForClaim(tx, userId, mediaIds);

    // Fewer rows than asked for means an id that does not exist, or one that
    // belongs to somebody else. Both are the same answer to the caller.
    if (media.length !== mediaIds.length) {
      throw badRequest('unknown_media', 'One of those files is not available to attach.');
    }
    if (media.some((row) => row.status !== 'ready')) {
      throw conflict('media_not_ready', 'One of those files has not finished uploading.');
    }
    if (media.some((row) => row.post_id !== null)) {
      throw conflict('media_already_used', 'One of those files is already part of another post.');
    }

    const kind = media[0]?.kind ?? null;
    if (kind !== null && media.some((row) => row.kind !== kind)) {
      // One file kind per post, so `type` describes the whole post. A carousel
      // of images and videos would need per-asset types and a different
      // renderer, which is a product decision and not something to infer.
      throw badRequest('mixed_media', 'A post can hold one kind of file at a time.');
    }

    // The type follows from what the post actually carries, so a client cannot
    // describe its own post wrongly (§8). A poll wins over the text fallback
    // because a poll post IS its poll, with the body as a caption.
    const type: PostType = poll !== null ? 'poll' : (kind ?? (linkUrl === '' ? 'text' : 'link'));

    const inserted = await tx.query<{ id: string }>(
      `INSERT INTO posts (channel_id, author_id, type, body, link_url, link_title)
       VALUES ($1, $2, $3, $4, $5, $6)
       RETURNING id`,
      [
        channel.id,
        userId,
        type,
        body === '' ? null : body,
        linkUrl === '' ? null : linkUrl,
        linkTitle === '' ? null : linkTitle,
      ]
    );
    const created = one(inserted).id;

    await attachMediaToPost(tx, created, mediaIds);

    // The poll is written in the same transaction as the post that promises it,
    // so a post of type `poll` without a poll cannot exist even briefly.
    if (poll !== null) await createPollInTransaction(tx, created, poll);

    // Same transaction as the insert, so the channel's counters cannot
    // disagree with the rows they count — the same rule `follower_count`
    // follows in M2. `last_post_at` also drives discovery ordering and the
    // unread flag, so a post that did not update it would be invisible.
    await tx.query(
      `UPDATE channels SET post_count = post_count + 1, last_post_at = now() WHERE id = $1`,
      [channel.id]
    );

    return created;
  });

  // Read back through the same path a list uses, so the publish response and a
  // later read cannot answer with different shapes.
  const post = await loadPost(database, store, userId, postId, true);

  // The channel is attached here because publish ALREADY loaded it to check
  // ownership, so naming it costs no query — and a caller that just published
  // needs the name to announce it (§17). Returning the feed shape rather than
  // the bare summary also means the publish response and a feed row cannot
  // disagree about the channel a post belongs to.
  return {
    ...post,
    channel: {
      id: channel.id,
      slug: channel.slug,
      name: channel.name,
      iconObjectKey: channel.icon_object_key,
    },
  };
}

/**
 * A single post, by id, as a reader sees it.
 *
 * Shared by publish, edit and every list so there is one payload shape for a
 * post. `viewerCanManage` has already been decided by the caller, which knows
 * the viewer's role from the query it just ran.
 */
async function loadPost(
  database: Queryable,
  store: ObjectStore,
  userId: string,
  postId: string,
  viewerCanManage: boolean
): Promise<PostSummary> {
  const row = await database.queryOne<PostRow>(
    `SELECT ${POST_COLUMNS} FROM posts p WHERE p.id = $1 AND p.deleted_at IS NULL`,
    [postId]
  );
  if (!row) throw notFound('post_not_found');

  const [media, engagement] = await Promise.all([
    mediaForPosts(database, store, [row.id]),
    engagementForPosts(database, userId, [row.id]),
  ]);
  return mapPost(
    row,
    media.get(row.id) ?? [],
    viewerCanManage,
    engagement.get(row.id) ?? emptyEngagement()
  );
}

/**
 * Channel history (§8), newest first.
 *
 * Readable by any signed-in user while the channel is active, and by the
 * channel's own admins afterwards — see [contentVisibleTo] for why. A
 * suspended channel therefore stops appearing in feeds and stops being readable
 * by followers, without its owner losing the ability to look at it.
 */
export async function listChannelPosts(
  database: Queryable,
  store: ObjectStore,
  userId: string,
  channelId: string,
  query: PageQuery
): Promise<Page<PostSummary>> {
  const channel = await loadViewableChannel(database, channelId);
  const role = await channelRoleOf(database, channel.id, userId);

  if (!contentVisibleTo(channel.status, role !== null)) {
    throw forbidden('channel_unavailable', 'This channel is not currently available.');
  }

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
  const canManage = role !== null && PUBLISHING_ROLES.includes(role);
  const last = items[items.length - 1];

  return {
    items: await assemble(database, store, userId, items, () => canManage),
    nextCursor:
      hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
}

/**
 * The aggregated feed: posts from every channel the viewer follows (§4).
 *
 * Three exclusions, all in SQL so no caller can forget one: a soft-deleted
 * post, a soft-deleted channel, and a channel that is not `active` — a
 * suspended channel stops broadcasting, which is what suspension means. Blocked
 * channels are excluded through the same NOT EXISTS pattern the followed-channels
 * list uses, so blocking hides a channel everywhere at once.
 *
 * Ordered by time alone, because the feed mixes channels: any per-channel
 * ordering would need a notion of "how much of each" that §4 does not ask for.
 */
export async function listFeed(
  database: Queryable,
  store: ObjectStore,
  userId: string,
  query: PageQuery
): Promise<Page<FeedPost>> {
  const limit = parsePageSize(query.limit, env.DEFAULT_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [userId, limit + 1];
  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (p.created_at, p.id) < ($3::timestamptz, $4::uuid)`;
  }

  const rows = await database.query<FeedRow>(
    `SELECT ${POST_COLUMNS},
            c.slug AS channel_slug,
            c.name AS channel_name,
            c.icon_object_key AS channel_icon_object_key,
            a.role AS viewer_role
       FROM posts p
       JOIN channels c ON c.id = p.channel_id
       JOIN channel_followers f ON f.channel_id = c.id AND f.user_id = $1
       LEFT JOIN channel_admins a ON a.channel_id = c.id AND a.user_id = $1
      WHERE p.deleted_at IS NULL
        AND c.deleted_at IS NULL
        AND c.status = 'active'
        AND NOT EXISTS (
          SELECT 1 FROM channel_blocks b WHERE b.channel_id = c.id AND b.user_id = $1
        )
        ${keyset}
      ORDER BY p.created_at DESC, p.id DESC
      LIMIT $2`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  const summaries = await assemble(
    database,
    store,
    userId,
    items,
    (row) => row.viewer_role !== null && PUBLISHING_ROLES.includes(row.viewer_role)
  );

  // Paired by index rather than by re-reading the row: `summaries` was built
  // from `items` in order, and a second lookup by id would be a chance for the
  // two to disagree.
  return {
    items: summaries.map((summary, index): FeedPost => {
      const row = items[index];
      return {
        ...summary,
        channel: {
          id: row?.channel_id ?? '',
          slug: row?.channel_slug ?? '',
          name: row?.channel_name ?? '',
          iconObjectKey: row?.channel_icon_object_key ?? null,
        },
      };
    }),
    nextCursor:
      hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
}

/**
 * Map rows to payloads, attaching each post's media.
 *
 * [canManage] is a predicate rather than a boolean because the feed decides it
 * per row (the viewer's role differs by channel) while a single-channel list
 * decides it once.
 */
async function assemble<T extends PostRow>(
  database: Queryable,
  store: ObjectStore,
  userId: string,
  rows: readonly T[],
  canManage: (row: T) => boolean
): Promise<PostSummary[]> {
  const ids = rows.map((row) => row.id);
  // Both are batched per page rather than per post: a 30-post feed would
  // otherwise issue 60 extra queries, which is the difference between a feed
  // that opens and one that feels broken on a phone.
  const [media, engagement] = await Promise.all([
    mediaForPosts(database, store, ids),
    engagementForPosts(database, userId, ids),
  ]);
  return rows.map((row) =>
    mapPost(row, media.get(row.id) ?? [], canManage(row), engagement.get(row.id) ?? emptyEngagement())
  );
}

/**
 * Edit a post's text, link or caption (§7).
 *
 * Only the textual fields. Attaching or removing media after publication would
 * mean deleting an S3 object that a reader may already have downloaded (§10)
 * and re-opening a post's `type`, so it is left out until there is a product
 * decision behind it.
 *
 * `undefined` leaves a field alone and `null` clears it — the same convention
 * M2's channel patch uses, which is what lets one endpoint serve both "fix a
 * typo" and "remove the caption".
 */
export async function updatePost(
  database: Queryable,
  store: ObjectStore,
  userId: string,
  channelId: string,
  postId: string,
  patch: UpdatePostInput
): Promise<PostSummary> {
  const channel = await loadViewableChannel(database, channelId);
  await assertMayManagePosts(database, channel.id, userId);
  if (!isUuid(postId)) throw notFound('post_not_found');

  const row = await database.queryOne<PostRow>(
    `SELECT ${POST_COLUMNS}
       FROM posts p
      WHERE p.id = $1 AND p.channel_id = $2 AND p.deleted_at IS NULL`,
    [postId, channel.id]
  );
  // Not-found rather than forbidden for a post in another channel: whether it
  // exists elsewhere is not something this caller gets to learn.
  if (!row) throw notFound('post_not_found');

  if (!withinEditWindow(row.created_at)) {
    throw forbidden(
      'edit_window_closed',
      `A post can only be edited within ${env.EDIT_WINDOW_DAYS} days of publishing.`
    );
  }

  const nextBody =
    patch.body === undefined ? undefined : patch.body === null ? null : patch.body.trim();
  const nextLinkTitle =
    patch.linkTitle === undefined
      ? undefined
      : patch.linkTitle === null
        ? null
        : patch.linkTitle.trim();
  const nextLinkUrl =
    patch.linkUrl === undefined ? undefined : patch.linkUrl === null ? null : patch.linkUrl.trim();

  if (nextBody !== undefined) {
    if (nextBody !== null && nextBody.length > env.MAX_TEXT_LENGTH) {
      throw badRequest(
        'text_too_long',
        `A post can hold at most ${env.MAX_TEXT_LENGTH} characters.`
      );
    }
    // A text post IS its body, so clearing it would leave a post with no
    // content — which the schema's CHECK also refuses.
    if (row.type === 'text' && (nextBody === null || nextBody === '')) {
      throw badRequest('empty_post', 'A text post cannot be empty.');
    }
  }

  if (nextLinkUrl !== undefined) {
    if (nextLinkUrl !== null && !isHttpUrl(nextLinkUrl)) {
      throw badRequest('invalid_link', 'A link must be an http or https address.');
    }
    if (row.type === 'link' && nextLinkUrl === null) {
      throw badRequest('link_required', 'This post was published as a link and must keep one.');
    }
  }

  await database.query(
    `UPDATE posts
        SET body = CASE WHEN $2::boolean THEN $3::text ELSE body END,
            link_url = CASE WHEN $4::boolean THEN $5::text ELSE link_url END,
            link_title = CASE WHEN $6::boolean THEN $7::text ELSE link_title END,
            edited_at = now()
      WHERE id = $1`,
    [
      postId,
      nextBody !== undefined,
      nextBody ?? null,
      nextLinkUrl !== undefined,
      nextLinkUrl ?? null,
      nextLinkTitle !== undefined,
      nextLinkTitle ?? null,
    ]
  );

  return loadPost(database, store, userId, postId, true);
}

/**
 * Remove a post (§7, §25).
 *
 * A SOFT delete: `deleted_at` is set and the row survives. Two reasons, and
 * both are requirements elsewhere in the spec — §18 models moderation as
 * reversible, so a hard delete would make "restore" impossible, and the media
 * rows (and the S3 objects they name) must stay reachable long enough for §34's
 * cleanup to be the thing that removes them rather than a cascade nobody
 * scheduled.
 *
 * Readers lose it immediately: every read filters `deleted_at IS NULL`, and
 * `mediaDownloadUrl` refuses a removed post's assets, so the S3 object being
 * still present does not mean it is still served.
 *
 * A second delete is a 404, not a success. Silence would decrement
 * `post_count` twice and leave the channel's counter disagreeing with its rows.
 */
export async function deletePost(
  database: Queryable,
  userId: string,
  channelId: string,
  postId: string,
  reason?: string
): Promise<void> {
  const channel = await loadViewableChannel(database, channelId);
  await assertMayManagePosts(database, channel.id, userId);
  if (!isUuid(postId)) throw notFound('post_not_found');

  await database.transaction(async (tx) => {
    const deleted = await tx.query<{ id: string }>(
      `UPDATE posts
          SET deleted_at = now(), deleted_reason = $3
        WHERE id = $1 AND channel_id = $2 AND deleted_at IS NULL
        RETURNING id`,
      [postId, channel.id, reason ?? null]
    );
    if (deleted.length === 0) throw notFound('post_not_found');

    // Recounted rather than decremented from `last_post_at`: the newest
    // remaining post may be older than the one just removed, and a feed sorted
    // by a stale `last_post_at` would order the channel wrongly.
    await tx.query(
      `UPDATE channels
          SET post_count = GREATEST(post_count - 1, 0),
              last_post_at = (
                SELECT max(created_at) FROM posts
                 WHERE channel_id = $1 AND deleted_at IS NULL
              )
        WHERE id = $1`,
      [channel.id]
    );
  });
}
