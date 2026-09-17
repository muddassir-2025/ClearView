import { randomBytes } from 'node:crypto';
import { env } from '../env.js';
import { cursorKeyOf, isoOrNull, one, type Queryable } from '../db.js';
import { badRequest, conflict, forbidden, notFound } from '../http/errors.js';
import {
  cursorOf,
  encodeCursor,
  isUuid,
  parsePageSize,
  withLimit,
  type ChannelSort,
  type Page,
  type PageQuery,
} from './cursor.js';
import { SLUG_MAX_LENGTH, SLUG_PATTERN, slugCandidate } from './slug.js';

/**
 * Channels: creation, management, following and discovery (§5, §6, §7, §12).
 *
 * Rules this module exists to enforce:
 *
 *  - **Ownership is never asserted by the client.** Every write resolves the
 *    caller's role from `channel_admins` for that specific channel (§32). A
 *    body field claiming `ownerId` or `role` is not read at all.
 *
 *  - **Nothing private leaves through a channel payload.** The shapes below
 *    are built field by field from an explicit column list; there is no row
 *    spread, so adding a column to `channels` (or joining a table with an
 *    email in it) cannot silently start serialising it (§38).
 *
 *  - **Owner identity is not exposed.** Like a broadcast channel in the
 *    product this is modelled on, who owns a channel is not part of its public
 *    surface. `channel_admins` is read for authorization and returned only as
 *    the caller's own role (§12: follower identity is private, and the same
 *    reasoning applies to ownership).
 *
 *  - **Blocked and non-active channels are excluded in SQL**, not filtered
 *    afterwards, so no route can forget to hide one.
 */

/** A channel as seen by any signed-in user. Free of owner and follower identity. */
export interface ChannelSummary {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
  readonly description: string | null;
  readonly iconObjectKey: string | null;
  readonly categorySlug: string | null;
  readonly categoryLabel: string | null;
  readonly countryCode: string | null;
  readonly followerCount: number;
  readonly postCount: number;
  readonly allowFollowerMessages: boolean;
  readonly createdAt: string;
  readonly lastPostAt: string | null;
  /**
   * What the channel last published, so a list row can say it.
   *
   * `lastPostType` is the post's own type (`text`, `link`, …) or `poll` when
   * the post carries one — poll-ness is a property of the post's content
   * rather than of its `type` column, so the distinction is derived here
   * instead of being guessed by the client.
   *
   * `lastPostPreview` is the opening of the body, trimmed to one line's worth
   * server-side because the only consumer is a single-line row. Null when the
   * last post has no text at all, which is a photo or a video and is described
   * by its type.
   */
  readonly lastPostType: string | null;
  readonly lastPostPreview: string | null;
  /** App deep link (§6). See the note on [channelShareLink]. */
  readonly shareLink: string;
}

/** A channel in the viewer's own lists, where per-viewer state is meaningful. */
export interface ChannelForViewer extends ChannelSummary {
  readonly isFollowing: boolean;
  readonly notificationsEnabled: boolean;
  readonly isBlocked: boolean;
  /** The viewer's role in this channel, or null when they hold none. */
  readonly viewerRole: ChannelRole | null;
  /** True when the channel's last post is newer than the viewer's last read. */
  readonly hasUnread: boolean;
}

export interface ChannelDetail extends ChannelForViewer {
  readonly status: ChannelStatus;
}

/** Columns every channel payload needs. Kept in one place so the shapes agree. */
const CHANNEL_COLUMNS = `
  c.id, c.slug, c.name, c.description, c.icon_object_key, c.category_slug,
  cat.label AS category_label, c.country_code, c.status, c.follower_count,
  c.post_count, c.allow_follower_messages, c.last_post_at, c.created_at,
  COALESCE(c.last_post_at, c.created_at) AS activity_at,
  c.owner_id
`;

/**
 * The last visible post, for a row's preview.
 *
 * A lateral join rather than a correlated subquery per column: three scalar
 * subqueries would each re-plan the same ORDER BY, and the point of a preview
 * is that it costs one index scan over `posts`, not three.
 *
 * `deleted_at IS NULL` matches every other read of posts, so a preview can
 * never advertise something the reader cannot open.
 */
const LAST_POST_JOIN = `
  LEFT JOIN LATERAL (
    SELECT p.type AS preview_type,
           LEFT(BTRIM(COALESCE(p.body, '')), 120) AS preview_body,
           EXISTS (SELECT 1 FROM polls po WHERE po.post_id = p.id) AS preview_is_poll
      FROM posts p
     WHERE p.channel_id = c.id AND p.deleted_at IS NULL
     ORDER BY p.created_at DESC, p.id DESC
     LIMIT 1
  ) lp ON true
`;

/** Columns [LAST_POST_JOIN] adds. Null when the channel has no visible post. */
const LAST_POST_COLUMNS = `
  lp.preview_type AS last_post_type,
  NULLIF(lp.preview_body, '') AS last_post_preview,
  COALESCE(lp.preview_is_poll, false) AS last_post_is_poll
`;

/**
 * A channel row with the extra columns every payload needs.
 *
 * Exported because the posts module reuses [loadViewable] and [roleOf] rather
 * than re-deriving channel visibility — two implementations of "which channels
 * may this viewer see" is one implementation too many.
 */
export interface ChannelRow {
  id: string;
  slug: string;
  /** Read for authorization only. Never mapped into a payload. */
  owner_id: string;
  name: string;
  description: string | null;
  icon_object_key: string | null;
  category_slug: string | null;
  category_label: string | null;
  country_code: string | null;
  status: ChannelStatus;
  follower_count: number;
  post_count: number;
  allow_follower_messages: boolean;
  last_post_at: unknown;
  created_at: unknown;
  activity_at: unknown;
  /**
   * From [LAST_POST_JOIN], and therefore OPTIONAL: the channel-creation and
   * single-channel-authorization reads deliberately do not pay for it. Undefined
   * maps to null, which is the same answer a channel with no posts gives.
   */
  last_post_type?: string | null;
  last_post_preview?: string | null;
  last_post_is_poll?: boolean;
}

export type ChannelStatus = 'active' | 'suspended' | 'banned';
export type ChannelRole = 'owner' | 'editor' | 'responder';

/**
 * A deep link the Android client can resolve (§6).
 *
 * Deliberately the app scheme rather than an `https://` URL. An https share
 * link would have to be served by a public, unauthenticated page, and public
 * read-only channel pages are an open product decision (§ plan §5.3) that has
 * not been taken — publishing a link that 404s, or quietly adding anonymous
 * reads to a spec that says every endpoint requires a session, would both be
 * worse than handing the client the app link now.
 */
function channelShareLink(slug: string): string {
  return `clearview://goodpost/channel/${slug}`;
}

function mapChannel(row: ChannelRow): ChannelSummary {
  return {
    id: row.id,
    slug: row.slug,
    name: row.name,
    description: row.description,
    iconObjectKey: row.icon_object_key,
    categorySlug: row.category_slug,
    categoryLabel: row.category_label,
    countryCode: row.country_code,
    followerCount: row.follower_count,
    postCount: row.post_count,
    allowFollowerMessages: row.allow_follower_messages,
    createdAt: isoOrNull(row.created_at) ?? '',
    lastPostAt: isoOrNull(row.last_post_at),
    // A poll is described as a poll whatever its `type` column says, because
    // that is the word a reader recognises on the row.
    lastPostType: row.last_post_is_poll ? 'poll' : (row.last_post_type ?? null),
    lastPostPreview: row.last_post_preview ?? null,
    shareLink: channelShareLink(row.slug),
  };
}

/** Postgres unique-violation. Used to resolve slug collisions without a race. */
function isUniqueViolation(err: unknown, constraint: string): boolean {
  const e = err as { code?: string; constraint?: string };
  return e?.code === '23505' && e?.constraint === constraint;
}

export interface Category {
  readonly slug: string;
  readonly label: string;
}

/** §5: database-driven categories, so adding one needs no deploy. */
export async function listCategories(database: Queryable): Promise<Category[]> {
  const rows = await database.query<{ slug: string; label: string }>(
    `SELECT slug, label FROM channel_categories
      WHERE is_active = true
      ORDER BY sort_order ASC, label ASC`
  );
  return rows.map((r) => ({ slug: r.slug, label: r.label }));
}

export interface CreateChannelInput {
  readonly name: string;
  readonly description?: string | undefined;
  readonly categorySlug?: string | undefined;
  readonly countryCode?: string | undefined;
}

/**
 * Create a channel and its owner row.
 *
 * The cap in `MAX_CHANNELS_PER_USER` is checked here rather than left to the
 * client: §5 wants discovery to stay usable, and a client-side limit is not a
 * limit at all.
 *
 * Slug collisions are resolved by retrying the whole transaction instead of
 * pre-checking with a SELECT. A pre-check ("is this slug free?") is a race —
 * two creates can both see it free — and, more importantly, a failed INSERT
 * aborts the surrounding Postgres transaction, so an in-transaction retry loop
 * could not simply try again. Attempt 0 uses the bare slug so the common case
 * produces a short, memorable link.
 */
export async function createChannel(
  database: Queryable,
  userId: string,
  input: CreateChannelInput
): Promise<ChannelDetail> {
  const country = input.countryCode?.trim().toUpperCase() || null;
  if (country && !/^[A-Z]{2}$/.test(country)) {
    throw badRequest('invalid_country', 'countryCode must be a two-letter ISO-3166-1 code.');
  }

  // Validated against the category table rather than a hard-coded list, so a
  // category added to the database is immediately usable.
  if (input.categorySlug) {
    const category = await database.queryOne(
      `SELECT slug FROM channel_categories WHERE slug = $1 AND is_active = true`,
      [input.categorySlug]
    );
    if (!category) throw badRequest('invalid_category', 'That category does not exist.');
  }

  for (let attempt = 0; attempt < 5; attempt += 1) {
    const slug = slugCandidate(input.name, attempt, randomBytes(3).toString('hex'));

    try {
      return await database.transaction(async (tx) => {
        const owned = await tx.queryOne<{ count: string }>(
          `SELECT count(*)::text AS count FROM channels
            WHERE owner_id = $1 AND deleted_at IS NULL`,
          [userId]
        );
        if (Number(owned?.count ?? 0) >= env.MAX_CHANNELS_PER_USER) {
          throw forbidden(
            'channel_limit_reached',
            `You can own at most ${env.MAX_CHANNELS_PER_USER} channels.`
          );
        }

        const inserted = await tx.query<ChannelRow>(
          `INSERT INTO channels (owner_id, slug, name, description, category_slug, country_code)
           VALUES ($1, $2, $3, $4, $5, $6)
           RETURNING id, owner_id, slug, name, description, icon_object_key, category_slug,
                     NULL::text AS category_label, country_code, status, follower_count,
                     post_count, allow_follower_messages, last_post_at, created_at,
                     created_at AS activity_at`,
          [userId, slug, input.name.trim(), input.description?.trim() ?? null, input.categorySlug ?? null, country]
        );
        const channel = one(inserted);

        // Same transaction as the channel, so "a channel always has an owner"
        // holds even if the process dies immediately after this call.
        await tx.query(
          `INSERT INTO channel_admins (channel_id, user_id, role) VALUES ($1, $2, 'owner')`,
          [channel.id, userId]
        );

        return {
          ...mapChannel(channel),
          status: channel.status,
          isFollowing: false,
          notificationsEnabled: false,
          isBlocked: false,
          viewerRole: 'owner' as const,
          hasUnread: false,
        };
      });
    } catch (err) {
      // Only a slug collision is retryable; the cap and category errors above
      // must propagate untouched.
      if (isUniqueViolation(err, 'channels_slug_key') && attempt < 4) continue;
      throw err;
    }
  }

  // Unreachable in practice: five random suffixes colliding is not a thing.
  throw conflict('slug_unavailable', 'Could not allocate a channel link. Try a different name.');
}

/**
 * The caller's role in a channel, or null.
 *
 * The single authorization read for every management action (§32). Returning
 * null rather than throwing lets each caller choose its own error, and keeps
 * "not found" from leaking whether a channel exists.
 */
export async function roleOf(
  database: Queryable,
  channelId: string,
  userId: string
): Promise<ChannelRole | null> {
  const row = await database.queryOne<{ role: ChannelRole }>(
    `SELECT role FROM channel_admins WHERE channel_id = $1 AND user_id = $2`,
    [channelId, userId]
  );
  return row?.role ?? null;
}

/** Load a channel it is legitimate for this viewer to see, or throw. */
export async function loadViewable(database: Queryable, channelId: string): Promise<ChannelRow> {
  if (!isUuid(channelId)) throw notFound('channel_not_found');

  const row = await database.queryOne<ChannelRow>(
    `SELECT ${CHANNEL_COLUMNS}
       FROM channels c
       LEFT JOIN channel_categories cat ON cat.slug = c.category_slug
      WHERE c.id = $1 AND c.deleted_at IS NULL`,
    [channelId]
  );
  if (!row) throw notFound('channel_not_found');
  return row;
}

/**
 * Channel detail plus the viewer's own relationship to it (§5, §6).
 *
 * One query serves both the id and slug lookups because a share link and an
 * in-app tap must answer with the SAME payload: a deep link that opened a
 * thinner shape than the screen it was shared from would be a bug no test
 * would catch, since both results are individually valid.
 *
 * Which column is matched is chosen from two hardcoded predicates rather than
 * by interpolating a column name, so no caller-supplied value is ever
 * concatenated into SQL. `$1` is the key and `$2` the viewer in both cases.
 */
async function loadChannelDetail(
  database: Queryable,
  userId: string,
  key: { readonly by: 'id' | 'slug'; readonly value: string }
): Promise<ChannelDetail> {
  const row = await database.queryOne<ChannelRow & ViewerStateRow>(
    `SELECT ${CHANNEL_COLUMNS},
            ${LAST_POST_COLUMNS},
            (f.user_id IS NOT NULL) AS is_following,
            COALESCE(f.notifications_enabled, false) AS notifications_enabled,
            (b.user_id IS NOT NULL) AS is_blocked,
            a.role AS viewer_role,
            (c.last_post_at IS NOT NULL
             AND c.last_post_at > COALESCE(f.last_read_at, f.followed_at)) AS has_unread
       FROM channels c
       LEFT JOIN channel_categories cat ON cat.slug = c.category_slug
       ${LAST_POST_JOIN}
       LEFT JOIN channel_followers f ON f.channel_id = c.id AND f.user_id = $2
       LEFT JOIN channel_blocks b ON b.channel_id = c.id AND b.user_id = $2
       LEFT JOIN channel_admins a ON a.channel_id = c.id AND a.user_id = $2
      WHERE ${key.by === 'id' ? 'c.id = $1' : 'c.slug = $1'} AND c.deleted_at IS NULL`,
    [key.value, userId]
  );
  if (!row) throw notFound('channel_not_found');

  return {
    ...mapChannel(row),
    status: row.status,
    isFollowing: row.is_following,
    notificationsEnabled: row.notifications_enabled,
    isBlocked: row.is_blocked,
    viewerRole: row.viewer_role,
    hasUnread: row.has_unread,
  };
}

/** Channel detail by id. */
export async function getChannel(
  database: Queryable,
  channelId: string,
  userId: string
): Promise<ChannelDetail> {
  if (!isUuid(channelId)) throw notFound('channel_not_found');
  return loadChannelDetail(database, userId, { by: 'id', value: channelId });
}

/**
 * Channel detail by share slug (§6), which is what makes a deep link resoluble.
 *
 * The slug is the only identifier a shared link carries, and this is the single
 * place it becomes a channel. Every rejection is `channel_not_found` — a dead
 * link, a malformed one and a typo are the same thing to the person holding the
 * link, and telling them apart would leak which slugs exist.
 *
 * A channel that exists but is suspended is still returned, with its `status`,
 * exactly as [getChannel] does: the screen can then explain itself instead of
 * the link appearing broken.
 */
export async function getChannelBySlug(
  database: Queryable,
  slug: string,
  userId: string
): Promise<ChannelDetail> {
  // Lower-cased because a link can be typed by hand and slugs are generated
  // lowercase; the length is capped BEFORE the pattern test so an absurd path
  // is rejected by a length check rather than fed to a matcher.
  const normalised = slug.trim().toLowerCase();
  if (normalised.length === 0 || normalised.length > SLUG_MAX_LENGTH) {
    throw notFound('channel_not_found');
  }
  if (!SLUG_PATTERN.test(normalised)) throw notFound('channel_not_found');

  return loadChannelDetail(database, userId, { by: 'slug', value: normalised });
}

interface ViewerStateRow {
  is_following: boolean;
  notifications_enabled: boolean;
  is_blocked: boolean;
  viewer_role: ChannelRole | null;
  has_unread: boolean;
}

export interface UpdateChannelInput {
  readonly name?: string | undefined;
  readonly description?: string | null | undefined;
  readonly categorySlug?: string | null | undefined;
  readonly countryCode?: string | null | undefined;
  readonly allowFollowerMessages?: boolean | undefined;
}

/**
 * Edit a channel's public profile (§7).
 *
 * Owner-only in M2. The `editor` and `responder` roles exist in the schema for
 * answering follower messages (§16) and are not grantable yet — when they are,
 * this is the check that widens, in one place.
 *
 * `slug` is intentionally not editable: links have already been shared, and a
 * slug that changes under a recipient is a broken link. Same reason the icon
 * is not set here — that is an S3 operation, which arrives in M3.
 */
export async function updateChannel(
  database: Queryable,
  userId: string,
  channelId: string,
  patch: UpdateChannelInput
): Promise<ChannelDetail> {
  const channel = await loadViewable(database, channelId);

  if ((await roleOf(database, channelId, userId)) !== 'owner') {
    // A channel a user merely follows must not be editable by them, and a
    // channel they cannot see at all must not be distinguishable from one they
    // can. Forbidden rather than not-found is correct here: they were able to
    // load the channel, so its existence is already known to them.
    throw forbidden('channel_forbidden', 'Only the channel owner can edit this channel.');
  }

  if (patch.categorySlug) {
    const category = await database.queryOne(
      `SELECT slug FROM channel_categories WHERE slug = $1 AND is_active = true`,
      [patch.categorySlug]
    );
    if (!category) throw badRequest('invalid_category', 'That category does not exist.');
  }

  const country =
    patch.countryCode === undefined || patch.countryCode === null
      ? undefined
      : patch.countryCode.trim().toUpperCase();
  if (country !== undefined && country !== '' && !/^[A-Z]{2}$/.test(country)) {
    throw badRequest('invalid_country', 'countryCode must be a two-letter ISO-3166-1 code.');
  }

  // COALESCE against the current value, so an omitted field is left alone
  // while an explicit null clears it. Distinguishing those is what lets one
  // endpoint serve both "rename" and "remove the description".
  await database.query(
    `UPDATE channels
        SET name = COALESCE($2, name),
            description = CASE WHEN $3::boolean THEN $4::text ELSE description END,
            category_slug = CASE WHEN $5::boolean THEN $6::text ELSE category_slug END,
            country_code = CASE WHEN $7::boolean THEN $8::text ELSE country_code END,
            allow_follower_messages = COALESCE($9, allow_follower_messages)
      WHERE id = $1`,
    [
      channel.id,
      patch.name?.trim() ?? null,
      patch.description !== undefined,
      patch.description?.trim() || null,
      patch.categorySlug !== undefined,
      patch.categorySlug ?? null,
      patch.countryCode !== undefined,
      country || null,
      patch.allowFollowerMessages ?? null,
    ]
  );

  return getChannel(database, channel.id, userId);
}

/**
 * Resolve a channel that may be followed, or throw the specific reason not.
 *
 * Each failure is a distinct code because the client does something different
 * for each: `channel_blocked` offers unblocking, `channel_unavailable` explains
 * that a moderator acted, and `channel_not_found` is a dead link.
 */
async function loadFollowable(database: Queryable, channelId: string): Promise<ChannelRow> {
  const channel = await loadViewable(database, channelId);
  if (channel.status !== 'active') {
    throw forbidden('channel_unavailable', 'This channel is not currently available.');
  }
  return channel;
}

export interface FollowResult {
  readonly following: boolean;
  readonly followerCount: number;
}

/**
 * Follow a channel (§12).
 *
 * Idempotent: following twice is not an error and does not double-count. The
 * `follower_count` is incremented only when a row was actually inserted, in
 * the same transaction as that insert — the count and the membership cannot
 * disagree.
 */
export async function followChannel(
  database: Queryable,
  userId: string,
  channelId: string
): Promise<FollowResult> {
  const channel = await loadFollowable(database, channelId);

  if (channel.owner_id === userId) {
    throw badRequest('cannot_follow_own_channel', 'You cannot follow your own channel.');
  }

  const blocked = await database.queryOne(
    `SELECT 1 FROM channel_blocks WHERE channel_id = $1 AND user_id = $2`,
    [channelId, userId]
  );
  if (blocked) {
    throw forbidden('channel_blocked', 'Unblock this channel before following it.');
  }

  return database.transaction(async (tx) => {
    const inserted = await tx.query(
      `INSERT INTO channel_followers (channel_id, user_id, notifications_enabled)
       VALUES ($1, $2, $3)
       ON CONFLICT (channel_id, user_id) DO NOTHING
       RETURNING 1 AS inserted`,
      [channelId, userId, env.DEFAULT_NOTIFICATIONS_ENABLED]
    );

    if (inserted.length === 0) {
      // Already following. Report the current count rather than a stale one.
      const current = await tx.queryOne<{ follower_count: number }>(
        `SELECT follower_count FROM channels WHERE id = $1`,
        [channelId]
      );
      return { following: true, followerCount: current?.follower_count ?? channel.follower_count };
    }

    const updated = await tx.query<{ follower_count: number }>(
      `UPDATE channels SET follower_count = follower_count + 1
        WHERE id = $1 RETURNING follower_count`,
      [channelId]
    );
    return { following: true, followerCount: one(updated).follower_count };
  });
}

/** Unfollow (§12). Idempotent, and never leaves the count below zero. */
export async function unfollowChannel(
  database: Queryable,
  userId: string,
  channelId: string
): Promise<FollowResult> {
  const channel = await loadViewable(database, channelId);

  return database.transaction(async (tx) => {
    const removed = await tx.query(
      `DELETE FROM channel_followers WHERE channel_id = $1 AND user_id = $2 RETURNING 1 AS removed`,
      [channelId, userId]
    );

    if (removed.length === 0) {
      return { following: false, followerCount: channel.follower_count };
    }

    // GREATEST guards the count against drift in the safe direction. The CHECK
    // constraint would reject a negative value, which would turn a data
    // inconsistency into a failed request for the user rather than a clamped
    // number.
    const updated = await tx.query<{ follower_count: number }>(
      `UPDATE channels SET follower_count = GREATEST(follower_count - 1, 0)
        WHERE id = $1 RETURNING follower_count`,
      [channelId]
    );
    return { following: false, followerCount: one(updated).follower_count };
  });
}

export interface NotificationState {
  readonly notificationsEnabled: boolean;
}

/**
 * Mute or unmute one channel (§17).
 *
 * Deliberately fails when the viewer does not follow the channel: mute is
 * per-follow state, and silently creating one would add a follower behind the
 * `follower_count`'s back — the exact drift the counter's transaction is
 * designed to prevent.
 */
export async function setChannelNotifications(
  database: Queryable,
  userId: string,
  channelId: string,
  enabled: boolean
): Promise<NotificationState> {
  const updated = await database.query<{ notifications_enabled: boolean }>(
    `UPDATE channel_followers
        SET notifications_enabled = $3,
            muted_at = CASE WHEN $3 THEN NULL ELSE now() END
      WHERE channel_id = $1 AND user_id = $2
      RETURNING notifications_enabled`,
    [channelId, userId, enabled]
  );

  if (updated.length === 0) {
    throw notFound('not_following', 'Follow this channel before changing its notifications.');
  }
  return { notificationsEnabled: one(updated).notifications_enabled };
}

/** Record the viewer as having read a channel (§4 unread state). */
export async function markChannelRead(
  database: Queryable,
  userId: string,
  channelId: string
): Promise<void> {
  await database.query(
    `UPDATE channel_followers SET last_read_at = now()
      WHERE channel_id = $1 AND user_id = $2`,
    [channelId, userId]
  );
}

/**
 * Block a channel (§12).
 *
 * Drops the follow in the same transaction. A blocked-but-followed channel
 * would still appear in the Channels view and still count toward
 * `follower_count`, which is a state with no coherent meaning — so it is made
 * unrepresentable rather than handled at every read site.
 */
export async function blockChannel(
  database: Queryable,
  userId: string,
  channelId: string
): Promise<void> {
  await loadViewable(database, channelId);

  await database.transaction(async (tx) => {
    const inserted = await tx.query(
      `INSERT INTO channel_blocks (channel_id, user_id) VALUES ($1, $2)
       ON CONFLICT (user_id, channel_id) DO NOTHING RETURNING 1 AS inserted`,
      [channelId, userId]
    );

    if (inserted.length > 0) {
      const removed = await tx.query(
        `DELETE FROM channel_followers WHERE channel_id = $1 AND user_id = $2 RETURNING 1 AS removed`,
        [channelId, userId]
      );
      if (removed.length > 0) {
        await tx.query(
          `UPDATE channels SET follower_count = GREATEST(follower_count - 1, 0) WHERE id = $1`,
          [channelId]
        );
      }
    }
  });
}

/** Unblock (§12). Following is not restored — that was the user's choice to end. */
export async function unblockChannel(
  database: Queryable,
  userId: string,
  channelId: string
): Promise<void> {
  await database.query(
    `DELETE FROM channel_blocks WHERE channel_id = $1 AND user_id = $2`,
    [channelId, userId]
  );
}

/**
 * Channels the viewer follows (§4 Channels view).
 *
 * Ordered by activity so the most recently active channel is first, and
 * blocked channels are excluded in SQL — a block has to remove a channel from
 * the feed, and doing it in the query means no caller can forget.
 */
export async function listFollowing(
  database: Queryable,
  userId: string,
  query: PageQuery
): Promise<Page<ChannelForViewer>> {
  const limit = parsePageSize(query.limit, env.DEFAULT_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [userId, limit + 1];
  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (COALESCE(c.last_post_at, c.created_at), c.id) < ($3::timestamptz, $4::uuid)`;
  }

  const rows = await database.query<ChannelRow & ViewerStateRow>(
    `SELECT ${CHANNEL_COLUMNS},
            ${LAST_POST_COLUMNS},
            true AS is_following,
            f.notifications_enabled,
            false AS is_blocked,
            a.role AS viewer_role,
            -- Falling back to followed_at is the whole point: a channel that
            -- has never been opened must read as unread, and requiring
            -- last_read_at IS NOT NULL inverted that (a fresh follow with new
            -- posts looked fully read).
            (c.last_post_at IS NOT NULL
             AND c.last_post_at > COALESCE(f.last_read_at, f.followed_at)) AS has_unread
       FROM channel_followers f
       JOIN channels c ON c.id = f.channel_id
       LEFT JOIN channel_categories cat ON cat.slug = c.category_slug
       ${LAST_POST_JOIN}
       LEFT JOIN channel_admins a ON a.channel_id = c.id AND a.user_id = $1
      WHERE f.user_id = $1
        AND c.deleted_at IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM channel_blocks b WHERE b.channel_id = c.id AND b.user_id = $1
        )
        ${keyset}
      ORDER BY COALESCE(c.last_post_at, c.created_at) DESC, c.id DESC
      LIMIT $2`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  return {
    items: items.map((row) => ({
      ...mapChannel(row),
      isFollowing: true,
      notificationsEnabled: row.notifications_enabled,
      isBlocked: false,
      viewerRole: row.viewer_role,
      hasUnread: row.has_unread,
    })),
    // Activity is the sort key for this feed, so its value is what the next
    // page must resume after.
    nextCursor: hasMore && last ? encodeCursor({ k: cursorKeyOf(last.activity_at), id: last.id }) : null,
  };
}

/**
 * Channels the viewer manages (§7).
 *
 * Unlike discovery this includes suspended and soft-deleted channels, because
 * an owner needs to see the state of what they own — hiding a suspended
 * channel from its owner would leave them with no way to understand a
 * moderation action against them.
 */
export async function listManagedChannels(
  database: Queryable,
  userId: string
): Promise<ChannelDetail[]> {
  const rows = await database.query<ChannelRow & ViewerStateRow>(
    `SELECT ${CHANNEL_COLUMNS},
            false AS is_following,
            COALESCE(f.notifications_enabled, false) AS notifications_enabled,
            false AS is_blocked,
            a.role AS viewer_role,
            false AS has_unread
       FROM channel_admins a
       JOIN channels c ON c.id = a.channel_id
       LEFT JOIN channel_categories cat ON cat.slug = c.category_slug
       LEFT JOIN channel_followers f ON f.channel_id = c.id AND f.user_id = $1
      WHERE a.user_id = $1
      ORDER BY c.created_at DESC`,
    [userId]
  );

  return rows.map((row) => ({
    ...mapChannel(row),
    status: row.status,
    isFollowing: row.is_following,
    notificationsEnabled: row.notifications_enabled,
    isBlocked: row.is_blocked,
    viewerRole: row.viewer_role,
    hasUnread: row.has_unread,
  }));
}

export interface DiscoverQuery extends PageQuery {
  readonly q?: string | undefined;
  readonly category?: string | undefined;
  readonly country?: string | undefined;
  readonly sort?: ChannelSort | undefined;
}

/**
 * Discover / Explore (§5).
 *
 * Ranking is `followers × recent activity` in its simplest defensible form:
 * order by the chosen key, tie-broken by id. No recommender — §5 rules that
 * out for v1, and a popularity sort is something a user can reason about.
 *
 * Every filter is a bound parameter and every condition is assembled from a
 * fixed set of fragments; the only interpolated text is a compiled-in sort
 * clause chosen by an enum, never a string from the request.
 */
export async function discoverChannels(
  database: Queryable,
  userId: string,
  query: DiscoverQuery
): Promise<Page<ChannelSummary>> {
  const limit = parsePageSize(query.limit, env.DEFAULT_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);
  const sort: ChannelSort = query.sort ?? 'popular';

  const params: unknown[] = [userId, limit + 1];
  const conditions: string[] = [
    'c.deleted_at IS NULL',
    `c.status = 'active'`,
    // Blocked channels never surface in discovery (§12) — otherwise a block
    // only hides a channel in the one screen the user was already looking at.
    `NOT EXISTS (SELECT 1 FROM channel_blocks b WHERE b.channel_id = c.id AND b.user_id = $1)`,
  ];

  if (query.q) {
    // Escaped so a query containing % or _ searches for those characters
    // instead of silently becoming a wildcard match.
    const term = `%${query.q.trim().replace(/[\\%_]/g, (m) => `\\${m}`)}%`;
    params.push(term);
    conditions.push(`(c.name ILIKE $${params.length} OR COALESCE(c.description, '') ILIKE $${params.length})`);
  }
  if (query.category) {
    params.push(query.category);
    conditions.push(`c.category_slug = $${params.length}`);
  }
  if (query.country) {
    params.push(query.country.trim().toUpperCase());
    conditions.push(`c.country_code = $${params.length}`);
  }

  // Sort key first so the keyset comparison below matches the ORDER BY.
  const { orderBy, keyset } = sortOrder(sort, params, cursor);

  const rows = await database.query<ChannelRow>(
    `SELECT ${CHANNEL_COLUMNS},
            ${LAST_POST_COLUMNS}
       FROM channels c
       LEFT JOIN channel_categories cat ON cat.slug = c.category_slug
       ${LAST_POST_JOIN}
      WHERE ${conditions.join('\n        AND ')}
        ${keyset}
      ORDER BY ${orderBy}
      LIMIT $2`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  return {
    items: items.map(mapChannel),
    nextCursor: hasMore && last ? encodeCursor({ k: sortKeyValue(sort, last), id: last.id }) : null,
  };
}

/** The value the cursor resumes after, per sort. */
function sortKeyValue(sort: ChannelSort, row: ChannelRow): string {
  if (sort === 'popular') return String(row.follower_count);
  if (sort === 'new') return cursorKeyOf(row.created_at);
  return cursorKeyOf(row.activity_at);
}

/**
 * The ORDER BY and matching keyset predicate for a sort.
 *
 * Both directions are DESC, including the tie-break on `id`. That is what
 * makes a row-comparison keyset (`(key, id) < (lastKey, lastId)`) exactly
 * equivalent to "the rows after the last one returned" — mixing an ASC
 * tie-break with a DESC primary would make the tuple comparison skip or repeat
 * rows at a boundary.
 */
function sortOrder(
  sort: ChannelSort,
  params: unknown[],
  cursor: { k: string; id: string } | null
): { orderBy: string; keyset: string } {
  const column =
    sort === 'popular'
      ? 'c.follower_count'
      : sort === 'new'
        ? 'c.created_at'
        : 'COALESCE(c.last_post_at, c.created_at)';

  const cast = sort === 'popular' ? 'int' : 'timestamptz';

  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    const keyParam = `$${params.length - 1}`;
    const idParam = `$${params.length}`;
    keyset = `AND (${column}, c.id) < (${keyParam}::${cast}, ${idParam}::uuid)`;
  }

  return { orderBy: `${column} DESC, c.id DESC`, keyset };
}
