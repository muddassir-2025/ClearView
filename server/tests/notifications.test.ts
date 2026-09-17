import type { Express } from 'express';
import request from 'supertest';
import type { PGlite } from '@electric-sql/pglite';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { closePool, type Queryable } from '../src/db.js';
import { setChannelStatus } from '../src/moderation/service.js';
import {
  fanOutPostNotification,
  sweepOldNotifications,
  sweepStaleDeviceTokens,
  unreadNotificationCount,
} from '../src/notifications/service.js';
import type { PushMessage, PushResult, PushSender } from '../src/notifications/push.js';
import { runRetentionSweep } from '../src/jobs/retention.js';
import { applyAllMigrations, asQueryable, freshDatabase, resetData } from './helpers/database.js';
import { authed, createChannelVia, fakeVerifier, registeredIn } from './helpers/accounts.js';
import { FakeObjectStore } from './helpers/storage.js';

/**
 * M7 notifications (§17) and delivery.
 *
 * §17 is a list of REFUSALS more than a list of sends, so that is what this
 * suite asserts: a muted channel notifies nobody, a blocked channel notifies
 * nobody, a suspended channel notifies nobody, a fan-out that runs twice
 * notifies nobody twice, and the person who published is never told about their
 * own post. Each of those is a way a notification system becomes noise, and
 * each is checked by counting rows in `notifications` rather than by inspecting
 * a return value — a fan-out that returned `notified: 0` while having written
 * the row would still be wrong.
 *
 * The push sender is a recording fake. That is deliberate and not a shortcut:
 * what can be wrong here is WHICH tokens are addressed and how often, and both
 * are ours to get wrong. Requiring a real FCM project to test them would mean
 * they were tested rarely or never. What the fake cannot prove — that FCM
 * accepts a well-formed token — is not something this code decides.
 */

class RecordingPush implements PushSender {
  readonly kind = 'fcm' as const;

  /** One entry per `send` call, so a double-push is visible rather than folded. */
  readonly calls: { tokens: string[]; message: PushMessage }[] = [];

  /** Tokens the provider will report as permanently gone, for the sweep test. */
  stale: string[] = [];

  /** Set to make the next send throw, exercising "delivery never breaks the write". */
  failNext = false;

  async send(tokens: readonly string[], message: PushMessage): Promise<PushResult> {
    this.calls.push({ tokens: [...tokens], message });
    if (this.failNext) {
      this.failNext = false;
      throw new Error('[test] push provider is unreachable');
    }
    return { delivered: tokens.length - this.stale.length, staleTokens: this.stale, skipped: false };
  }

  reset(): void {
    this.calls.length = 0;
    this.stale = [];
    this.failNext = false;
  }

  /** Every token addressed across every call, for "who was pushed to". */
  addressed(): string[] {
    return this.calls.flatMap((call) => call.tokens);
  }
}

const PHONE_A = '+923005550001';
const PHONE_B = '+923005550002';
const PHONE_C = '+923005550003';

let pglite: PGlite;
let database: Queryable;
let store: FakeObjectStore;
let push: RecordingPush;
let app: Express;

beforeAll(async () => {
  pglite = await freshDatabase();
  await applyAllMigrations(pglite);
  database = asQueryable(pglite);
  store = new FakeObjectStore();
  push = new RecordingPush();
  app = buildApp({ database, verifier: fakeVerifier(), store, push });
});

beforeEach(async () => {
  await resetData(pglite);
  store.reset();
  push.reset();
});

afterAll(async () => {
  await pglite.close();
  await closePool();
});

interface Session {
  accessToken: string;
  user: { id: string };
}

const registered = (phone: string, name?: string) => registeredIn(app, phone, name);

const createChannel = (session: Session, body: Record<string, unknown>) =>
  createChannelVia(app, session, body);

async function follow(session: Session, channelId: string, enabled = true): Promise<void> {
  const followed = await request(app)
    .post(`/api/v1/channels/${channelId}/follow`)
    .set(authed(session.accessToken));
  expect(followed.status).toBe(200);

  // §17's mute state. `DEFAULT_NOTIFICATIONS_ENABLED` is false, so opting IN is
  // an explicit step — and the test performs it rather than assuming a default,
  // because the default is configuration and configuration changes.
  const notifications = await request(app)
    .put(`/api/v1/channels/${channelId}/notifications`)
    .set(authed(session.accessToken))
    .send({ enabled });
  expect(notifications.status).toBe(200);
}

async function published(session: Session, channelId: string, body: Record<string, unknown>) {
  const res = await request(app)
    .post(`/api/v1/channels/${channelId}/posts`)
    .set(authed(session.accessToken))
    .send(body);
  expect(res.status, JSON.stringify(res.body)).toBe(201);
  return res.body.post as { id: string };
}

/** Rows in `notifications` for one account. */
async function inbox(userId: string): Promise<{ id: string; kind: string; body: string }[]> {
  const rows = await pglite.query<{ id: string; kind: string; body: string }>(
    `SELECT id, kind, body FROM notifications WHERE user_id = $1 ORDER BY created_at ASC`,
    [userId]
  );
  return rows.rows;
}

describe('post fan-out (§17)', () => {
  it('notifies an opted-in follower, and never the author', async () => {
    const owner = await registered(PHONE_A, 'Owner');
    const follower = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(owner, { name: 'Notify Me' });
    await follow(follower, channel.id);

    await request(app)
      .post('/api/v1/devices')
      .set(authed(follower.accessToken))
      .send({ token: 'device-token-for-the-follower', platform: 'android' });

    await published(owner, channel.id, { body: 'First post' });

    const followerRows = await inbox(follower.user.id);
    expect(followerRows).toHaveLength(1);
    expect(followerRows[0]).toMatchObject({ kind: 'channel_post', body: 'First post' });

    // The author is the one person who already knows. A notification here is
    // pure noise, and it is the mistake every naive fan-out makes.
    expect(await inbox(owner.user.id)).toHaveLength(0);

    // Exactly one device was addressed, and it belonged to the follower.
    expect(push.addressed()).toEqual(['device-token-for-the-follower']);
  });

  it('notifies nobody for a muted channel, and holds no row for it either', async () => {
    const owner = await registered(PHONE_A, 'Owner');
    const follower = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(owner, { name: 'Muted' });
    await follow(follower, channel.id, false);

    await published(owner, channel.id, { body: 'Silence' });

    // Not "a suppressed notification": no row at all. A stored row for a muted
    // channel would leave the row and the mute state disagreeing, and a client
    // that showed the row would be right to complain.
    expect(await inbox(follower.user.id)).toHaveLength(0);
    expect(push.calls).toHaveLength(0);
  });

  it('notifies nobody for a blocked channel', async () => {
    const owner = await registered(PHONE_A, 'Owner');
    const follower = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(owner, { name: 'Blocked Later' });
    await follow(follower, channel.id);

    // §12's block ends the follow AND must end notifications. Blocking is the
    // emphatic version of muting.
    const blocked = await request(app)
      .post(`/api/v1/channels/${channel.id}/block`)
      .set(authed(follower.accessToken));
    expect(blocked.status).toBe(200);

    await published(owner, channel.id, { body: 'Into the void' });

    expect(await inbox(follower.user.id)).toHaveLength(0);
    expect(push.calls).toHaveLength(0);
  });

  it('notifies nobody when the channel is suspended or the post is removed', async () => {
    const owner = await registered(PHONE_A, 'Owner');
    const follower = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(owner, { name: 'Moderated' });
    await follow(follower, channel.id);

    await setChannelStatus(database, channel.id, 'suspended', 'spam');

    const suspended = await fanOutPostNotification(database, push, {
      channelId: channel.id,
      postId: '00000000-0000-0000-0000-000000000009',
      authorId: owner.user.id,
      channelName: 'Moderated',
      preview: 'hi',
    });
    expect(suspended.skipped).toBe(true);
    expect(await inbox(follower.user.id)).toHaveLength(0);

    await setChannelStatus(database, channel.id, 'active', 'appeal upheld');

    const post = await published(owner, channel.id, { body: 'Removed soon' });
    expect(await inbox(follower.user.id)).toHaveLength(1);

    // A post removed AFTER it was announced: the fan-out for it must be a no-op
    // rather than a second announcement of content that is gone.
    await request(app)
      .delete(`/api/v1/channels/${channel.id}/posts/${post.id}`)
      .set(authed(owner.accessToken));

    await fanOutPostNotification(database, push, {
      channelId: channel.id,
      postId: post.id,
      authorId: owner.user.id,
      channelName: 'Moderated',
      preview: 'Removed soon',
    });
    expect(await inbox(follower.user.id)).toHaveLength(1);
  });

  it('a re-run notifies nobody twice and does not push twice', async () => {
    const owner = await registered(PHONE_A, 'Owner');
    const follower = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(owner, { name: 'Once Only' });
    await follow(follower, channel.id);

    const post = await published(owner, channel.id, { body: 'Said once' });

    const callsAfterPublish = push.calls.length;
    expect(callsAfterPublish).toBe(1);

    // A retried job or a duplicated publish trigger. `(user_id, dedupe_key)` is
    // what refuses the second row, and the push is deliberately skipped when no
    // row was written — otherwise the database would be right and the phone
    // would still be wrong.
    const again = await fanOutPostNotification(database, push, {
      channelId: channel.id,
      postId: post.id,
      authorId: owner.user.id,
      channelName: 'Once Only',
      preview: 'Said once',
    });

    expect(again.notified).toBe(0);
    expect(await inbox(follower.user.id)).toHaveLength(1);
    expect(push.calls.length).toBe(callsAfterPublish);
  });

  it('still notifies everyone when the push provider is unreachable', async () => {
    const owner = await registered(PHONE_A, 'Owner');
    const follower = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(owner, { name: 'Provider Down' });
    await follow(follower, channel.id);

    await request(app)
      .post('/api/v1/devices')
      .set(authed(follower.accessToken))
      .send({ token: 'device-token-provider-down', platform: 'android' });

    push.failNext = true;

    // The publish must still succeed, and the inbox must still hold the row: a
    // notification is a durable record, push is only a delivery attempt (§17).
    await published(owner, channel.id, { body: 'Delivered to the inbox' });
    expect(await inbox(follower.user.id)).toHaveLength(1);
  });
});

describe('private message fan-out (§16, §17)', () => {
  /** A owns a channel B follows, with follower messages switched on. */
  async function messagingChannel() {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(a, {
      name: `Inbox ${Math.random().toString(36).slice(2, 8)}`,
    });
    await follow(b, channel.id);

    const enabled = await request(app)
      .patch(`/api/v1/channels/${channel.id}`)
      .set(authed(a.accessToken))
      .send({ allowFollowerMessages: true });
    expect(enabled.status).toBe(200);

    const opened = await request(app)
      .post(`/api/v1/channels/${channel.id}/conversations`)
      .set(authed(b.accessToken));
    expect(opened.status === 200 || opened.status === 201).toBe(true);
    const conversationId = opened.body.conversation.id as string;

    return { a, b, channel, conversationId };
  }

  async function send(session: Session, conversationId: string, body: string) {
    const res = await request(app)
      .post(`/api/v1/conversations/${conversationId}/messages`)
      .set(authed(session.accessToken))
      .send({ body });
    expect(res.status, JSON.stringify(res.body)).toBe(201);
    return res.body.message as { id: string; fromAdmin: boolean };
  }

  it('tells the channel when a follower writes, and the follower when it answers', async () => {
    const { a, b, conversationId } = await messagingChannel();

    await request(app)
      .post('/api/v1/devices')
      .set(authed(a.accessToken))
      .send({ token: 'owner-device-token', platform: 'android' });
    await request(app)
      .post('/api/v1/devices')
      .set(authed(b.accessToken))
      .send({ token: 'follower-device-token', platform: 'android' });

    const question = await send(b, conversationId, 'Is this thing on?');
    expect(question.fromAdmin).toBe(false);

    // The ADMIN side is notified, and the follower who wrote is not.
    expect(await inbox(a.user.id)).toHaveLength(1);
    expect(await inbox(b.user.id)).toHaveLength(0);
    expect(push.addressed()).toEqual(['owner-device-token']);

    push.reset();

    const answer = await send(a, conversationId, 'It is.');
    expect(answer.fromAdmin).toBe(true);

    // The REPLY goes to exactly one person — the follower who asked. A notice to
    // every admin here would put one follower's message in front of the whole
    // channel team, which §38 forbids.
    expect(await inbox(b.user.id)).toHaveLength(1);
    expect(await inbox(a.user.id)).toHaveLength(1);
    expect(push.addressed()).toEqual(['follower-device-token']);
  });

  it('does not notify an admin about their own reply', async () => {
    const { a, b, conversationId } = await messagingChannel();

    await send(b, conversationId, 'A question');
    expect(await inbox(a.user.id)).toHaveLength(1);

    push.reset();
    await send(a, conversationId, 'My own answer');

    // Still ONE row on the admin side, and it is the follower's question. An
    // admin who answers their own channel's inbox is a SENDER, not a
    // recipient — a fan-out that ignored that would ring the replier on every
    // reply they write, and eventually the row would be a duplicate of a
    // message they wrote themselves.
    const adminRows = await pglite.query<{ dedupe_key: string; body: string }>(
      `SELECT dedupe_key, body FROM notifications WHERE user_id = $1`,
      [a.user.id]
    );
    expect(adminRows.rows).toHaveLength(1);
    expect(adminRows.rows[0]?.body).toBe('A question');

    // And the reply was pushed to the follower, not to the admin.
    expect(push.addressed()).toEqual([]);

    // The follower holds their own notification of the reply, and no duplicate.
    expect(await inbox(b.user.id)).toHaveLength(1);
  });

  it('messages_disabled produces no notification for either side', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(a, { name: 'Closed Door' });
    await follow(b, channel.id);

    // The channel starts out accepting messages (§16 defaults to on), so the
    // owner closes the door first — that refusal is what this covers.
    await request(app)
      .patch(`/api/v1/channels/${channel.id}`)
      .set(authed(a.accessToken))
      .send({ allowFollowerMessages: false });

    const refused = await request(app)
      .post(`/api/v1/channels/${channel.id}/conversations`)
      .set(authed(b.accessToken));
    expect(refused.status).toBe(403);
    expect(refused.body.error).toBe('messages_disabled');

    expect(push.calls).toHaveLength(0);
  });
});

describe('the inbox (§17)', () => {
  it('returns only the caller`s rows, counts unread, and marks read', async () => {
    const owner = await registered(PHONE_A, 'Owner');
    const first = await registered(PHONE_B, 'First');
    const second = await registered(PHONE_C, 'Second');
    const channel = await createChannel(owner, { name: 'Many Followers' });
    await follow(first, channel.id);
    await follow(second, channel.id);

    await published(owner, channel.id, { body: 'Hello everyone' });

    const mine = await request(app)
      .get('/api/v1/notifications')
      .set(authed(first.accessToken));
    expect(mine.status).toBe(200);
    expect(mine.body.items).toHaveLength(1);

    // The other follower's row is not in this payload, in any field.
    expect(JSON.stringify(mine.body)).not.toContain('Second');

    const count = await unreadNotificationCount(database, first.user.id);
    expect(count).toBe(1);

    // Marking ONE id read leaves the rest — a client that showed a single card
    // must not clear a badge for content it never rendered.
    const marked = await request(app)
      .post('/api/v1/notifications/read')
      .set(authed(first.accessToken))
      .send({ ids: [mine.body.items[0].id] });
    expect(marked.status).toBe(200);
    expect(marked.body.marked).toBe(1);
    expect(await unreadNotificationCount(database, first.user.id)).toBe(0);

    // …and the second account is untouched by the first account's action.
    expect(await unreadNotificationCount(database, second.user.id)).toBe(1);

    const all = await request(app)
      .post('/api/v1/notifications/read')
      .set(authed(second.accessToken))
      .send({});
    expect(all.body.marked).toBe(1);
    expect(await unreadNotificationCount(database, second.user.id)).toBe(0);
  });

  it('an unauthenticated caller gets the inbox only with a session', async () => {
    const anonymous = await request(app).get('/api/v1/notifications');
    expect(anonymous.status).toBe(401);
  });
});

describe('device registration (§17)', () => {
  it('moves a token between accounts rather than duplicating it', async () => {
    const first = await registered(PHONE_A, 'First');
    const second = await registered(PHONE_B, 'Second');

    const token = 'a-shared-handset-token';

    await request(app)
      .post('/api/v1/devices')
      .set(authed(first.accessToken))
      .send({ token, platform: 'android' });
    await request(app)
      .post('/api/v1/devices')
      .set(authed(second.accessToken))
      .send({ token, platform: 'android' });

    const rows = await pglite.query<{ user_id: string }>(
      'SELECT user_id FROM device_tokens WHERE token = $1',
      [token]
    );

    // One row, owned by whoever signed in last. Two rows would mean the phone
    // keeps receiving the previous account's notifications — a privacy leak
    // (§38) and not merely a stale address.
    expect(rows.rows).toHaveLength(1);
    expect(rows.rows[0]?.user_id).toBe(second.user.id);

    // And the fan-out now addresses that phone for the new account only.
    const owner = await registered(PHONE_C, 'Owner');
    const channel = await createChannel(owner, { name: 'Second Account Follows' });
    await follow(second, channel.id);
    await published(owner, channel.id, { body: 'For the new account' });
    expect(push.addressed()).toEqual([token]);
  });

  it('unregisters on sign-out and refuses an implausible token', async () => {
    const user = await registered(PHONE_A, 'Owner');

    const short = await request(app)
      .post('/api/v1/devices')
      .set(authed(user.accessToken))
      .send({ token: 'tiny', platform: 'android' });
    expect(short.status).toBe(400);

    const token = 'a-perfectly-plausible-device-token';
    await request(app)
      .post('/api/v1/devices')
      .set(authed(user.accessToken))
      .send({ token, platform: 'android' });

    const removed = await request(app)
      .delete(`/api/v1/devices/${token}`)
      .set(authed(user.accessToken));
    expect(removed.status).toBe(200);

    const rows = await pglite.query('SELECT 1 FROM device_tokens WHERE token = $1', [token]);
    expect(rows.rows).toHaveLength(0);
  });

  it('disables a token the provider reports as gone', async () => {
    const owner = await registered(PHONE_A, 'Owner');
    const follower = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(owner, { name: 'Dead Token' });
    await follow(follower, channel.id);

    const token = 'an-uninstalled-apps-token';
    await request(app)
      .post('/api/v1/devices')
      .set(authed(follower.accessToken))
      .send({ token, platform: 'android' });

    // FCM reports a dead token inside an otherwise successful response.
    // Ignoring it is how a deployment sends to uninstalled apps forever.
    push.stale = [token];
    await published(owner, channel.id, { body: 'Nobody is listening' });

    const rows = await pglite.query<{ disabled_at: string | null }>(
      'SELECT disabled_at FROM device_tokens WHERE token = $1',
      [token]
    );
    expect(rows.rows[0]?.disabled_at).not.toBeNull();

    // The inbox row survives: the notification is a record, the token is only a
    // delivery address.
    expect(await inbox(follower.user.id)).toHaveLength(1);
  });
});

describe('retention (§11) reaches notifications and delivery addresses', () => {
  it('prunes old notifications and stale device tokens, and leaves fresh ones', async () => {
    const owner = await registered(PHONE_A, 'Owner');
    // The follower is the one notified: an author is never told about their own
    // post, so a read-back through the publisher's inbox would always be empty.
    const follower = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(owner, { name: 'Aged' });
    await follow(follower, channel.id);

    const old = await published(owner, channel.id, { body: 'Ancient' });
    const fresh = await published(owner, channel.id, { body: 'Recent' });

    await pglite.query(
      `UPDATE notifications SET created_at = now() - interval '400 days' WHERE dedupe_key = $1`,
      [`post:${old.id}`]
    );
    expect(await inbox(follower.user.id)).toHaveLength(2);

    await request(app)
      .post('/api/v1/devices')
      .set(authed(follower.accessToken))
      .send({ token: 'stale-but-registered', platform: 'android' });
    await pglite.query(
      `UPDATE device_tokens SET last_seen_at = now() - interval '400 days' WHERE token = $1`,
      ['stale-but-registered']
    );

    const report = await runRetentionSweep(database, store);
    expect(report.notificationsRemoved).toBe(1);
    expect(report.staleDevicesDisabled).toBe(1);

    // The recent one survived: a sweep that removed everything would pass a
    // count-only assertion and destroy every inbox on the platform.
    const keys = await pglite.query<{ dedupe_key: string }>(
      'SELECT dedupe_key FROM notifications WHERE user_id = $1',
      [follower.user.id]
    );
    expect(keys.rows.map((row) => row.dedupe_key)).toEqual([`post:${fresh.id}`]);

    const disabled = await pglite.query<{ disabled_at: string | null }>(
      'SELECT disabled_at FROM device_tokens WHERE token = $1',
      ['stale-but-registered']
    );
    // Disabled, never deleted: the row explains a delivery failure, and a token
    // that comes back is re-enabled by the register upsert.
    expect(disabled.rows[0]?.disabled_at).not.toBeNull();
  });

  it('the individual sweeps are idempotent', async () => {
    const owner = await registered(PHONE_A, 'Owner');
    const follower = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(owner, { name: 'Idempotent' });
    await follow(follower, channel.id);
    await published(owner, channel.id, { body: 'Kept' });

    // Nothing is old enough to remove, so a pass must remove nothing — the
    // property that makes running the sweep on a timer safe.
    expect(await sweepOldNotifications(database)).toBe(0);
    expect(await sweepStaleDeviceTokens(database)).toBe(0);
    expect(await inbox(follower.user.id)).toHaveLength(1);
  });
});
