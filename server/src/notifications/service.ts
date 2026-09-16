import { env } from '../env.js';
import { isoOrNull, type Queryable } from '../db.js';
import { badRequest, notFound } from '../http/errors.js';
import { parsePageSize, type Page } from '../channels/cursor.js';
import { createPushSender, type PushSender } from './push.js';

/**
 * Channel notifications (§17) and device registration.
 *
 * The rules §17 lists are all refusals, so that is where the care is:
 *
 *  * a muted channel (§17's mute/unmute) produces NO notification — not a
 *    suppressed one;
 *  * a blocked channel produces none;
 *  * a suspended, banned or deleted channel produces none;
 *  * a removed post notifies nobody, including a post removed after the
 *    notification was queued;
 *  * and the person who published it is never notified about their own post.
 *
 * All of those are resolved in ONE query against `channel_followers`, so a
 * recipient list is never assembled from several places that could disagree —
 * the same reasoning that keeps visibility rules in a single module.
 *
 * The other half is delivery: a notification is written to the database first
 * and pushed second. A push failure therefore loses nothing, and re-running a
 * fan-out cannot double-notify because `(user_id, dedupe_key)` is unique.
 */

export interface DeviceRegistration {
  readonly token: string;
  readonly platform: 'android' | 'ios' | 'web';
}

/** Register (or refresh) a device's push token. */
export async function registerDevice(
  database: Queryable,
  userId: string,
  input: DeviceRegistration
): Promise<{ readonly token: string }> {
  const token = input.token.trim();
  if (token.length < 10 || token.length > 400) {
    throw badRequest('invalid_token', 'That device token does not look valid.');
  }

  const owned = await database.queryOne<{ count: string }>(
    `SELECT count(*)::text AS count FROM device_tokens
      WHERE user_id = $1 AND disabled_at IS NULL AND token <> $2`,
    [userId, token]
  );
  if (Number(owned?.count ?? 0) >= env.MAX_DEVICE_TOKENS_PER_USER) {
    // A cap rather than an unbounded list: a client that leaks a fresh token on
    // every launch would otherwise grow this table forever and make every
    // fan-out slower.
    throw badRequest(
      'too_many_devices',
      `A single account can register at most ${env.MAX_DEVICE_TOKENS_PER_USER} devices.`
    );
  }

  // ON CONFLICT moves the token to the account that is actually signed in. A
  // device that changes hands must not keep receiving the previous account's
  // pushes — that is a privacy leak, not merely a stale address.
  await database.query(
    `INSERT INTO device_tokens (token, user_id, platform)
     VALUES ($1, $2, $3::device_platform)
     ON CONFLICT (token) DO UPDATE
       SET user_id = EXCLUDED.user_id,
           platform = EXCLUDED.platform,
           last_seen_at = now(),
           disabled_at = NULL`,
    [token, userId, input.platform]
  );

  return { token };
}

/** Unregister a device, on sign-out. Idempotent: an unknown token is not an error. */
export async function unregisterDevice(
  database: Queryable,
  userId: string,
  token: string
): Promise<void> {
  await database.query(`DELETE FROM device_tokens WHERE token = $1 AND user_id = $2`, [
    token,
    userId,
  ]);
}

export interface NotificationSummary {
  readonly id: string;
  readonly kind: string;
  readonly title: string;
  readonly body: string;
  readonly channelId: string | null;
  readonly postId: string | null;
  readonly createdAt: string;
  readonly readAt: string | null;
}

/** The inbox (§17), newest first. Never another account's rows. */
export async function listNotifications(
  database: Queryable,
  userId: string,
  query: { readonly limit?: string | undefined } = {}
): Promise<Page<NotificationSummary>> {
  const limit = parsePageSize(query.limit, env.DEFAULT_PAGE_SIZE, env.MAX_PAGE_SIZE);

  const rows = await database.query<{
    id: string;
    kind: string;
    title: string;
    body: string;
    channel_id: string | null;
    post_id: string | null;
    created_at: unknown;
    read_at: unknown;
  }>(
    `SELECT id, kind, title, body, channel_id, post_id, created_at, read_at
       FROM notifications
      WHERE user_id = $1
      ORDER BY created_at DESC, id DESC
      LIMIT $2`,
    [userId, limit]
  );

  // A feed with an `id`-descending tie-break would support keyset paging, but
  // notifications are pushed before they are read: the first page is the whole
  // working set, and a cursor here would only add a way for a client to fall
  // behind a moving list.
  return {
    items: rows.map((row) => ({
      id: row.id,
      kind: row.kind,
      title: row.title,
      body: row.body,
      channelId: row.channel_id,
      postId: row.post_id,
      createdAt: isoOrNull(row.created_at) ?? '',
      readAt: isoOrNull(row.read_at),
    })),
    nextCursor: null,
  };
}

/** Unread count, for the badge. */
export async function unreadNotificationCount(
  database: Queryable,
  userId: string
): Promise<number> {
  const row = await database.queryOne<{ count: number }>(
    `SELECT count(*)::int AS count FROM notifications WHERE user_id = $1 AND read_at IS NULL`,
    [userId]
  );
  return row?.count ?? 0;
}

/**
 * Mark notifications read.
 *
 * Without ids, everything in the inbox is marked — which is what an app does on
 * open. With ids, only those, so a client that has scrolled to one card does
 * not clear the badge for content it never showed.
 */
export async function markNotificationsRead(
  database: Queryable,
  userId: string,
  ids: readonly string[]
): Promise<number> {
  if (ids.length === 0) {
    const rows = await database.query(
      `UPDATE notifications SET read_at = now()
        WHERE user_id = $1 AND read_at IS NULL
        RETURNING id`,
      [userId]
    );
    return rows.length;
  }

  const rows = await database.query(
    `UPDATE notifications SET read_at = now()
      WHERE user_id = $1 AND read_at IS NULL AND id = ANY($2::uuid[])
      RETURNING id`,
    [userId, ids]
  );
  return rows.length;
}

export interface FanOutResult {
  readonly notified: number;
  readonly delivered: number;
  readonly staleTokens: readonly string[];
  /** True when nothing was sent because the channel is not active. */
  readonly skipped: boolean;
}

interface AudienceRow {
  user_id: string;
}

/**
 * Notify a channel's followers about a new post (§17).
 *
 * The audience is ONE query, and every exclusion §17 requires is in it:
 * `notifications_enabled` (mute), `channel_blocks` (block), the channel being
 * `active`, and the author. Resolving any of those afterwards would mean
 * writing a notification row and then deciding not to send it, which is how an
 * inbox ends up holding notices for a channel the user muted.
 *
 * The channel's state is checked by the caller BEFORE this runs (the publish
 * path already refuses a non-active channel), but it is checked again here: a
 * fan-out can be triggered by something other than a live publish — a retried
 * job, for instance — and the check is one join.
 */
export async function fanOutPostNotification(
  database: Queryable,
  push: PushSender,
  input: {
    readonly channelId: string;
    readonly postId: string;
    readonly authorId: string;
    readonly channelName: string;
    readonly preview: string;
  }
): Promise<FanOutResult> {
  const channel = await database.queryOne<{ status: string; deleted_at: unknown; name: string }>(
    `SELECT status, deleted_at, name FROM channels WHERE id = $1`,
    [input.channelId]
  );

  if (!channel || channel.deleted_at !== null || channel.status !== 'active') {
    return { notified: 0, delivered: 0, staleTokens: [], skipped: true };
  }

  const post = await database.queryOne<{ deleted_at: unknown }>(
    `SELECT deleted_at FROM posts WHERE id = $1`,
    [input.postId]
  );
  if (!post || post.deleted_at !== null) {
    return { notified: 0, delivered: 0, staleTokens: [], skipped: true };
  }

  const audience = await database.query<AudienceRow>(
    `SELECT f.user_id
       FROM channel_followers f
      WHERE f.channel_id = $1
        AND f.user_id <> $2
        AND f.notifications_enabled = true
        AND NOT EXISTS (
          SELECT 1 FROM channel_blocks b WHERE b.channel_id = $1 AND b.user_id = f.user_id
        )`,
    [input.channelId, input.authorId]
  );

  if (audience.length === 0) {
    return { notified: 0, delivered: 0, staleTokens: [], skipped: false };
  }

  const title = channel.name;
  const body = input.preview.length > 0 ? truncate(input.preview, 180) : 'New post';
  const dedupeKey = `post:${input.postId}`;

  // One statement for the whole audience. `ON CONFLICT DO NOTHING` is what makes
  // a re-run harmless: the unique key is the post, so a second fan-out notifies
  // nobody rather than everyone twice.
  const inserted = await database.query<{ user_id: string }>(
    `INSERT INTO notifications (user_id, kind, channel_id, post_id, title, body, dedupe_key)
     SELECT user_id, 'channel_post', $2, $3, $4, $5, $6
       FROM unnest($1::uuid[]) AS user_id
     ON CONFLICT (user_id, dedupe_key) DO NOTHING
     RETURNING user_id`,
    [
      audience.map((row) => row.user_id),
      input.channelId,
      input.postId,
      title,
      body,
      dedupeKey,
    ]
  );

  if (inserted.length === 0) {
    // Everyone was already notified. Deliberately no push: re-pushing on a
    // duplicate is exactly the repeated notification §17 asks to avoid.
    return { notified: 0, delivered: 0, staleTokens: [], skipped: false };
  }

  const notifiedIds = inserted.map((row) => row.user_id);
  const tokens = await database.query<{ token: string }>(
    `SELECT token FROM device_tokens
      WHERE user_id = ANY($1::uuid[]) AND disabled_at IS NULL`,
    [notifiedIds]
  );

  const result = await push.send(
    tokens.map((row) => row.token),
    {
      title,
      body,
      // Routing data only: ids the client already has access to, never a
      // follower's identity and never anything private (§38).
      data: { kind: 'channel_post', channelId: input.channelId, postId: input.postId },
    }
  );

  if (result.staleTokens.length > 0) {
    await database.query(
      `UPDATE device_tokens SET disabled_at = now() WHERE token = ANY($1::text[])`,
      [result.staleTokens]
    );
  }

  return {
    notified: inserted.length,
    delivered: result.delivered,
    staleTokens: result.staleTokens,
    skipped: result.skipped,
  };
}

/**
 * Notify a channel's admins that a follower wrote (§17, §16).
 *
 * The audience is the channel's `channel_admins`, not its followers: this is
 * the one notification an owner must not miss. Respects nothing else, because
 * there is no mute state for "my channel has mail".
 */
export async function fanOutMessageNotification(
  database: Queryable,
  push: PushSender,
  input: {
    readonly channelId: string;
    readonly conversationId: string;
    readonly messageId: string;
    readonly channelName: string;
    readonly preview: string;
    /**
     * Who sent it. Excluded from the audience: an admin who replies in their
     * own channel's inbox must not be notified about their own reply. Optional
     * so an existing caller keeps working, and because a fan-out triggered by
     * something other than a live send has no sender to exclude.
     */
    readonly senderId?: string | undefined;
  }
): Promise<FanOutResult> {
  const admins = await database.query<AudienceRow>(
    `SELECT user_id FROM channel_admins
      WHERE channel_id = $1 AND ($2::uuid IS NULL OR user_id <> $2)`,
    [input.channelId, input.senderId ?? null]
  );
  if (admins.length === 0) {
    return { notified: 0, delivered: 0, staleTokens: [], skipped: false };
  }

  const inserted = await database.query<{ user_id: string }>(
    `INSERT INTO notifications (user_id, kind, channel_id, title, body, dedupe_key)
     SELECT user_id, 'channel_message', $2, $3, $4, $5
       FROM unnest($1::uuid[]) AS user_id
     ON CONFLICT (user_id, dedupe_key) DO NOTHING
     RETURNING user_id`,
    [
      admins.map((row) => row.user_id),
      input.channelId,
      `Message in ${input.channelName}`,
      truncate(input.preview, 180),
      `message:${input.messageId}`,
    ]
  );
  if (inserted.length === 0) {
    return { notified: 0, delivered: 0, staleTokens: [], skipped: false };
  }

  const tokens = await database.query<{ token: string }>(
    `SELECT token FROM device_tokens
      WHERE user_id = ANY($1::uuid[]) AND disabled_at IS NULL`,
    [inserted.map((row) => row.user_id)]
  );

  const result = await push.send(
    tokens.map((row) => row.token),
    {
      title: `Message in ${input.channelName}`,
      body: truncate(input.preview, 180),
      data: { kind: 'channel_message', channelId: input.channelId },
    }
  );

  return {
    notified: inserted.length,
    delivered: result.delivered,
    staleTokens: result.staleTokens,
    skipped: result.skipped,
  };
}

/**
 * Notify a FOLLOWER that the channel answered them (§16).
 *
 * The mirror of [fanOutMessageNotification], and deliberately a separate
 * function rather than a flag on it: the audience is resolved from a different
 * table (`channel_conversations` names exactly one recipient, whereas a channel's
 * inbox names every admin), and the two must never be confused. Sending an
 * admin-side notice to a follower, or a follower's question to every admin,
 * would be a privacy failure in one direction and a missed message in the
 * other (§38).
 *
 * `title` is the channel's name, so the device shows "ClearView News" rather
 * than a generic "New message" — the same reasoning as a post notification.
 * The follower's identity is never part of what is written.
 */
export async function fanOutReplyNotification(
  database: Queryable,
  push: PushSender,
  input: {
    readonly conversationId: string;
    readonly messageId: string;
    readonly channelId: string;
    readonly channelName: string;
    readonly preview: string;
  }
): Promise<FanOutResult> {
  const conversation = await database.queryOne<{ follower_id: string }>(
    `SELECT follower_id FROM channel_conversations WHERE id = $1`,
    [input.conversationId]
  );
  if (!conversation) {
    return { notified: 0, delivered: 0, staleTokens: [], skipped: true };
  }

  // A closed conversation is not a delivery address. Checked here rather than in
  // the route so every caller inherits it.
  const channel = await database.queryOne<{ status: string; deleted_at: unknown }>(
    `SELECT status, deleted_at FROM channels WHERE id = $1`,
    [input.channelId]
  );
  if (!channel || channel.deleted_at !== null || channel.status !== 'active') {
    return { notified: 0, delivered: 0, staleTokens: [], skipped: true };
  }

  const title = input.channelName;
  const body = input.preview.trim().length > 0 ? truncate(input.preview, 180) : 'New reply';

  const inserted = await database.query<{ user_id: string }>(
    `INSERT INTO notifications (user_id, kind, channel_id, title, body, dedupe_key)
     VALUES ($1, 'channel_message', $2, $3, $4, $5)
     ON CONFLICT (user_id, dedupe_key) DO NOTHING
     RETURNING user_id`,
    [conversation.follower_id, input.channelId, title, body, `message:${input.messageId}`]
  );
  if (inserted.length === 0) {
    // Already notified: a retry, not a new reply. Re-pushing here is exactly the
    // repeated notification §17 asks to avoid.
    return { notified: 0, delivered: 0, staleTokens: [], skipped: false };
  }

  const tokens = await database.query<{ token: string }>(
    `SELECT token FROM device_tokens WHERE user_id = $1 AND disabled_at IS NULL`,
    [conversation.follower_id]
  );

  const result = await push.send(
    tokens.map((row) => row.token),
    {
      title,
      body,
      data: { kind: 'channel_message', channelId: input.channelId, conversationId: input.conversationId },
    }
  );

  if (result.staleTokens.length > 0) {
    await database.query(
      `UPDATE device_tokens SET disabled_at = now() WHERE token = ANY($1::text[])`,
      [result.staleTokens]
    );
  }

  return {
    notified: inserted.length,
    delivered: result.delivered,
    staleTokens: result.staleTokens,
    skipped: result.skipped,
  };
}

/**
 * Notify a user or a channel about an official platform message (§26).
 *
 * Written when the admin message is created, so the inbox shows it even if
 * push is unavailable — which is the difference between "you were told" and
 * "your phone was told".
 */
export async function fanOutPlatformNotice(
  database: Queryable,
  push: PushSender,
  input: {
    readonly adminMessageId: string;
    readonly targetUserId?: string | undefined;
    readonly targetChannelId?: string | undefined;
    readonly subject: string;
    readonly body: string;
  }
): Promise<FanOutResult> {
  const recipients: string[] = [];

  if (input.targetUserId) {
    recipients.push(input.targetUserId);
  } else if (input.targetChannelId) {
    const admins = await database.query<AudienceRow>(
      `SELECT user_id FROM channel_admins WHERE channel_id = $1`,
      [input.targetChannelId]
    );
    recipients.push(...admins.map((row) => row.user_id));
  }

  if (recipients.length === 0) {
    return { notified: 0, delivered: 0, staleTokens: [], skipped: false };
  }

  const inserted = await database.query<{ user_id: string }>(
    `INSERT INTO notifications
       (user_id, kind, channel_id, admin_message_id, title, body, dedupe_key)
     SELECT user_id, 'platform_notice', $2, $3, $4, $5, $6
       FROM unnest($1::uuid[]) AS user_id
     ON CONFLICT (user_id, dedupe_key) DO NOTHING
     RETURNING user_id`,
    [
      recipients,
      input.targetChannelId ?? null,
      input.adminMessageId,
      input.subject,
      truncate(input.body, 300),
      `notice:${input.adminMessageId}`,
    ]
  );
  if (inserted.length === 0) {
    return { notified: 0, delivered: 0, staleTokens: [], skipped: false };
  }

  const tokens = await database.query<{ token: string }>(
    `SELECT token FROM device_tokens
      WHERE user_id = ANY($1::uuid[]) AND disabled_at IS NULL`,
    [inserted.map((row) => row.user_id)]
  );

  const result = await push.send(
    tokens.map((row) => row.token),
    {
      title: input.subject,
      body: truncate(input.body, 180),
      data: { kind: 'platform_notice', adminMessageId: input.adminMessageId },
    }
  );

  return {
    notified: inserted.length,
    delivered: result.delivered,
    staleTokens: result.staleTokens,
    skipped: result.skipped,
  };
}

/**
 * The sender this deployment should use, so a caller never has to build one.
 *
 * Constructed eagerly but harmless: `FcmPushSender` initialises nothing until
 * `send()`, which is what keeps an unconfigured deployment from touching
 * Firebase at all.
 */
export const defaultPush: PushSender = createPushSender();

/** A preview must fit a notification body; the full text stays in the post. */
function truncate(value: string, max: number): string {
  const text = value.replace(/\s+/g, ' ').trim();
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

/** Read one notification, for a client that opened it from a push. */
export async function getNotification(
  database: Queryable,
  userId: string,
  notificationId: string
): Promise<NotificationSummary> {
  const row = await database.queryOne<{
    id: string;
    kind: string;
    title: string;
    body: string;
    channel_id: string | null;
    post_id: string | null;
    created_at: unknown;
    read_at: unknown;
  }>(
    `SELECT id, kind, title, body, channel_id, post_id, created_at, read_at
       FROM notifications WHERE id = $1 AND user_id = $2`,
    [notificationId, userId]
  );
  if (!row) throw notFound('notification_not_found');

  return {
    id: row.id,
    kind: row.kind,
    title: row.title,
    body: row.body,
    channelId: row.channel_id,
    postId: row.post_id,
    createdAt: isoOrNull(row.created_at) ?? '',
    readAt: isoOrNull(row.read_at),
  };
}

/**
 * Disable device tokens that have not been seen for a long time.
 *
 * Android does not promise to tell anyone when an app is uninstalled, so a token
 * can outlive its install indefinitely. `last_seen_at` is refreshed on every
 * register call, which makes a long-unseen token the only evidence available.
 * Disabled rather than deleted: the row explains a delivery failure, and a
 * token that comes back to life is re-enabled by the register upsert.
 */
export async function sweepStaleDeviceTokens(database: Queryable): Promise<number> {
  const rows = await database.query(
    `UPDATE device_tokens SET disabled_at = now()
      WHERE token IN (
        SELECT token FROM device_tokens
         WHERE disabled_at IS NULL
           AND last_seen_at < now() - ($1::int * interval '1 day')
         LIMIT $2
      )
      RETURNING token`,
    [env.DEVICE_TOKEN_STALE_DAYS, env.PURGE_BATCH_SIZE]
  );
  return rows.length;
}

/**
 * Delete notifications older than the retention window.
 *
 * §11's window is about the SERVER's storage, and an inbox is server storage.
 * Downloaded media on a device is a different thing entirely and is never
 * touched by anything in this process (§10).
 */
export async function sweepOldNotifications(database: Queryable): Promise<number> {
  const rows = await database.query(
    `DELETE FROM notifications
      WHERE id IN (
        SELECT id FROM notifications
         WHERE created_at < now() - ($1::int * interval '1 day')
         LIMIT $2
      )
      RETURNING id`,
    [env.NOTIFICATION_RETENTION_DAYS, env.PURGE_BATCH_SIZE]
  );
  return rows.length;
}
