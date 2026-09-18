import type { Queryable } from '../db.js';
import { env } from '../env.js';
import type { PushMessage, PushSender } from './fcm.js';

/**
 * Telling a reader's phone that a channel they follow has posted (§8, §12).
 *
 * ## The shape of the work
 *
 * One publish is one QUERY and then N sends: the query finds the devices of the
 * readers who follow the channel and have not muted it, and the sends are
 * addressed to those tokens. Nothing here walks posts or channels, because the
 * only event that announces anything is a publish.
 *
 * ## Where this is called from, and why it must not throw
 *
 * From the publish route, AFTER the post is committed and the response is
 * already written. A post that exists must never be reported as failed because a
 * notification could not be delivered: the publish is the product, the
 * notification is a courtesy. Every failure here is logged and swallowed, which
 * is also why the caller does not `await` it.
 *
 * ## What is deliberately not announced
 *
 * Not edits (a corrected typo is not news, and a channel that edits twice would
 * buzz twice for one post), not deletions, and not a channel's first post to a
 * reader who followed afterwards — the reader's own watermark handles the last
 * one, on the client, where "what have I already been told" belongs.
 */

/** A device token as the client presents it. */
export interface RegisterDeviceInput {
  readonly token: string;
  readonly platform: string;
}

/**
 * Record that this reader's phone wants to be told things.
 *
 * An upsert on the TOKEN, which is the part that matters: a token names one
 * install, and an install has one signed-in reader at a time. Upserting on
 * (reader, token) instead would leave a second reader's row behind when a phone
 * changes hands — and the first reader would keep receiving notifications about
 * channels they follow, on a device they no longer hold.
 *
 * `last_seen_at` moves on every registration so a stale row is recognisable
 * without asking Firebase about it.
 */
export async function registerDevice(
  database: Queryable,
  readerId: string,
  input: RegisterDeviceInput
): Promise<void> {
  await database.query(
    `INSERT INTO reader_devices (reader_id, token, platform)
     VALUES ($1, $2, $3)
     ON CONFLICT (token) DO UPDATE
       SET reader_id = EXCLUDED.reader_id,
           platform = EXCLUDED.platform,
           last_seen_at = now()`,
    [readerId, input.token, input.platform]
  );
}

/**
 * Forget one device.
 *
 * Scoped to the reader in SQL, so calling it with somebody else's token deletes
 * nothing rather than unsubscribing another person's phone. Returns the number of
 * rows removed, which the route reports as a plain success either way: whether it
 * was there is not information the caller needs, and answering "no such token"
 * would confirm something about another account.
 */
export async function unregisterDevice(
  database: Queryable,
  readerId: string,
  token: string
): Promise<number> {
  const rows = await database.query(
    `DELETE FROM reader_devices WHERE reader_id = $1 AND token = $2 RETURNING id`,
    [readerId, token]
  );
  return rows.length;
}

/** A publish, as the fan-out needs to describe it. */
export interface AnnounceInput {
  readonly channelId: string;
  readonly postId: string;
  readonly body: string | null;
  /** ISO 8601, the post's own `created_at`. The client's watermark compares it. */
  readonly publishedAt: string;
}

/** What a fan-out did. Returned rather than logged only, so it is testable. */
export interface AnnounceResult {
  readonly devices: number;
  readonly sent: number;
  readonly removed: number;
}

interface FollowerDeviceRow {
  channel_name: string;
  channel_slug: string;
  token: string;
}

/**
 * Notify the readers who follow this channel (§8).
 *
 * The follow row decides everything: a reader with no follow row is not
 * notified, and a reader whose follow is MUTED is filtered out in SQL rather
 * than in JavaScript — so the mute is respected once, by the same statement that
 * finds the tokens, and a mute cannot be forgotten by a later branch.
 *
 * Bounded by [env.PUSH_FANOUT_MAX]. A channel with a hundred thousand followers
 * publishing an update would otherwise hold a free instance for minutes; the cap
 * means the newest-registered devices get the push and the rest fall back to the
 * app's own catch-up check, which is the honest trade at this size. It is not a
 * correctness bound: nothing is lost, because the posts are readable regardless.
 */
export async function announcePost(
  database: Queryable,
  sender: PushSender,
  input: AnnounceInput
): Promise<AnnounceResult> {
  // Checked before the query: a deployment with no service account should not
  // read a single row to discover it has nowhere to send.
  if (!sender.configured) return { devices: 0, sent: 0, removed: 0 };

  const rows = await database.query<FollowerDeviceRow>(
    `SELECT c.name AS channel_name, c.slug AS channel_slug, d.token
       FROM channels c
       JOIN channel_follows f
         ON f.channel_id = c.id
        AND f.notifications_muted = false
       JOIN reader_devices d ON d.reader_id = f.reader_id
      WHERE c.id = $1
      ORDER BY d.last_seen_at DESC
      LIMIT $2`,
    [input.channelId, env.PUSH_FANOUT_MAX]
  );

  if (rows.length === 0) return { devices: 0, sent: 0, removed: 0 };

  const channelName = rows[0]!.channel_name;
  const channelSlug = rows[0]!.channel_slug;
  const preview = previewOf(input.body);

  const messages: PushMessage[] = rows.map((row) => ({
    token: row.token,
    title: channelName,
    body: preview,
    data: {
      // `type` is the client's own discriminator: one FCM project can carry more
      // than one feature's messages, and a handler that assumed every message was
      // a Good Post update would eventually be wrong.
      type: 'goodpost.post',
      channelId: input.channelId,
      channelSlug,
      channelName,
      postId: input.postId,
      publishedAt: input.publishedAt,
      preview,
    },
  }));

  const result = await sender.send(messages);

  let removed = 0;
  if (result.dead.length > 0) {
    // One statement for the whole batch. A token Firebase has retired is not an
    // error to report anywhere: it is an install that stopped listening, and the
    // only useful thing to do with it is stop paying for it on every publish.
    const deleted = await database.query(
      `DELETE FROM reader_devices WHERE token = ANY($1::text[]) RETURNING id`,
      [result.dead]
    );
    removed = deleted.length;
  }

  return { devices: rows.length, sent: result.sent, removed };
}

/**
 * The line under the heading.
 *
 * The post's own first line, with the formatting markers removed: a notification
 * reading `*breaking* news` is a notification that shows the machinery, and
 * `GoodPostNotifications` on the client has a fallback for a post with no text at
 * all — so an empty preview is a legitimate answer and is sent as one.
 */
export function previewOf(body: string | null, limit = 140): string {
  if (body === null) return '';
  const collapsed = body.replace(/\s+/g, ' ').trim();
  if (collapsed === '') return '';

  const plain = collapsed
    .replace(/```([^`]*)```/g, '$1')
    .replace(/[*_~]/g, '')
    .trim();

  return plain.length <= limit ? plain : `${plain.slice(0, limit - 1).trimEnd()}…`;
}
