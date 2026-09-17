import type { Express } from 'express';
import request from 'supertest';
import type { PGlite } from '@electric-sql/pglite';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { closePool, type Queryable } from '../src/db.js';
import { setAccountStatus, setChannelStatus } from '../src/moderation/service.js';
import { applyAllMigrations, asQueryable, freshDatabase, resetData } from './helpers/database.js';
import { authed, createChannelVia, fakeVerifier, registeredIn } from './helpers/accounts.js';
import { FakeObjectStore } from './helpers/storage.js';

/**
 * M5 moderation (§12, §16, §18, §19).
 *
 * Two things carry this suite, and neither is "the happy path works".
 *
 *  * **§19's ban is tested as a whole.** A ban is three writes — status,
 *    identity block, session revocation — and asserting only one of them would
 *    let the other two rot. The test drives the service, then proves the
 *    effect through the HTTP surface a client actually uses: an existing
 *    session stops working, and the same mobile number cannot register again.
 *
 *  * **§18's and §16's privacy claims are asserted on the wire.** A message
 *    payload is checked for an email or a phone hash by string search, not by a
 *    typed field, because a mapper that drops a field would still satisfy a
 *    shape assertion.
 */

const PHONE_A = '+923004440001';
const PHONE_B = '+923004440002';
const PHONE_C = '+923004440003';

let pglite: PGlite;
let database: Queryable;
let store: FakeObjectStore;
let app: Express;

beforeAll(async () => {
  pglite = await freshDatabase();
  await applyAllMigrations(pglite);
  database = asQueryable(pglite);
  store = new FakeObjectStore();
  app = buildApp({ database, verifier: fakeVerifier(), store });
});

beforeEach(async () => {
  await resetData(pglite);
  store.reset();
  await seedActingAdmin();
});

/**
 * A real administrator row to attribute moderation actions to.
 *
 * `banned_identities.banned_by_admin_id` is a foreign key (attached in
 * migration 008), so a made-up uuid would be refused — and rightly: "who banned
 * this number" is exactly the kind of record that must not point at nobody.
 * `admin_users` is not touched by [resetData], which truncates user-side tables
 * only, so this is idempotent across tests.
 */
const ACTING_ADMIN_ID = '00000000-0000-0000-0000-000000000001';

async function seedActingAdmin(): Promise<void> {
  await pglite.query(
    `INSERT INTO admin_users
       (id, display_name, email, email_normalized, phone_hash, password_hash, role)
     VALUES ($1, 'Acting admin', 'acting@example.test', 'acting@example.test',
             'acting-admin-phone-hash', 'not-a-real-bcrypt-hash', 'super_admin')
     ON CONFLICT (id) DO NOTHING`,
    [ACTING_ADMIN_ID]
  );
}

afterAll(async () => {
  await pglite.close();
  await closePool();
});

interface Session {
  accessToken: string;
  user: { id: string };
}

const registered = (phone: string, name?: string, email?: string) =>
  registeredIn(app, phone, name, email);

const createChannel = (session: Session, body: Record<string, unknown>) =>
  createChannelVia(app, session, body);

async function follow(session: Session, channelId: string): Promise<void> {
  const res = await request(app)
    .post(`/api/v1/channels/${channelId}/follow`)
    .set(authed(session.accessToken));
  expect(res.status).toBe(200);
}

async function published(session: Session, channelId: string, body: Record<string, unknown>) {
  const res = await request(app)
    .post(`/api/v1/channels/${channelId}/posts`)
    .set(authed(session.accessToken))
    .send(body);
  expect(res.status).toBe(201);
  return res.body.post as { id: string };
}

/** A channel A owns with B following it, and messages enabled. */
async function messagingChannel() {
  const a = await registered(PHONE_A, 'Owner');
  const b = await registered(PHONE_B, 'Follower');
  const channel = await createChannel(a, { name: `Inbox ${Math.random().toString(36).slice(2, 8)}` });
  await follow(b, channel.id);

  const enabled = await request(app)
    .patch(`/api/v1/channels/${channel.id}`)
    .set(authed(a.accessToken))
    .send({ allowFollowerMessages: true });
  expect(enabled.status).toBe(200);

  return { a, b, channel };
}

async function openConversation(session: Session, channelId: string): Promise<string> {
  const res = await request(app)
    .post(`/api/v1/channels/${channelId}/conversations`)
    .set(authed(session.accessToken));
  expect(res.status === 200 || res.status === 201, `open conversation (${res.status})`).toBe(true);
  return res.body.conversation.id as string;
}

const send = (session: Session, conversationId: string, body: string) =>
  request(app)
    .post(`/api/v1/conversations/${conversationId}/messages`)
    .set(authed(session.accessToken))
    .send({ body });

const readMessages = (session: Session, conversationId: string, query = '') =>
  request(app)
    .get(`/api/v1/conversations/${conversationId}/messages${query}`)
    .set(authed(session.accessToken));

// ── Reports (§18) ───────────────────────────────────────────────────────

describe('reports (§18)', () => {
  it('files a report and returns only the reporter-facing fields', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Reporter');
    const channel = await createChannel(a, { name: 'Reported' });

    const res = await request(app)
      .post('/api/v1/reports')
      .set(authed(b.accessToken))
      .send({ targetType: 'channel', targetId: channel.id, reason: 'spam', details: 'Posting junk' });

    expect(res.status).toBe(201);
    expect(res.body.report).toMatchObject({
      targetType: 'channel',
      reason: 'spam',
      status: 'open',
      resolvedAt: null,
    });
    // Nothing a reporter should not know: no target owner, no moderator, no
    // internal note.
    expect(Object.keys(res.body.report).sort()).toEqual([
      'actionTaken',
      'createdAt',
      'details',
      'id',
      'reason',
      'resolvedAt',
      'status',
      'targetType',
    ]);
  });

  it('refuses the same report twice rather than queueing it again', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Reporter');
    const channel = await createChannel(a, { name: 'Reported' });
    const body = { targetType: 'channel', targetId: channel.id, reason: 'spam' };

    const first = await request(app)
      .post('/api/v1/reports')
      .set(authed(b.accessToken))
      .send(body);
    expect(first.status).toBe(201);

    const second = await request(app)
      .post('/api/v1/reports')
      .set(authed(b.accessToken))
      .send(body);
    expect(second.status).toBe(409);
    expect(second.body.error).toBe('duplicate_report');
  });

  it('refuses a reason the app does not offer', async () => {
    const b = await registered(PHONE_B, 'Reporter');
    const c = await registered(PHONE_C, 'Target');

    const res = await request(app)
      .post('/api/v1/reports')
      .set(authed(b.accessToken))
      .send({ targetType: 'user', targetId: c.user.id, reason: 'i_just_dont_like_them' });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_reason');
  });

  it('refuses a report against the reporter themselves', async () => {
    const b = await registered(PHONE_B, 'Reporter');

    const res = await request(app)
      .post('/api/v1/reports')
      .set(authed(b.accessToken))
      .send({ targetType: 'user', targetId: b.user.id, reason: 'abuse' });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('cannot_report_self');
  });

  it('answers a report against a nonexistent target without confirming what exists', async () => {
    const b = await registered(PHONE_B, 'Reporter');
    const missing = '11111111-2222-3333-4444-555555555555';

    const res = await request(app)
      .post('/api/v1/reports')
      .set(authed(b.accessToken))
      .send({ targetType: 'channel', targetId: missing, reason: 'spam' });

    expect(res.status).toBe(404);
    expect(res.body.error).toBe('report_target_not_found');
  });

  it('lets a report target a post, and hides a removed post behind the same code', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Reporter');
    await follow(b, (await createChannel(a, { name: 'Feed' })).id);
    const channel = await createChannel(a, { name: 'Feed Two' });
    const post = await published(a, channel.id, { body: 'something' });

    const ok = await request(app)
      .post('/api/v1/reports')
      .set(authed(b.accessToken))
      .send({ targetType: 'post', targetId: post.id, reason: 'misinformation' });
    expect(ok.status).toBe(201);

    const removed = await published(a, channel.id, { body: 'gone soon' });
    await request(app)
      .delete(`/api/v1/channels/${channel.id}/posts/${removed.id}`)
      .set(authed(a.accessToken));

    const res = await request(app)
      .post('/api/v1/reports')
      .set(authed(b.accessToken))
      .send({ targetType: 'post', targetId: removed.id, reason: 'spam' });
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('report_target_not_found');
  });

  it('shows a reporter their own reports and nobody else`s', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Reporter');
    const c = await registered(PHONE_C, 'Other reporter');
    const channel = await createChannel(a, { name: 'Reported' });

    await request(app)
      .post('/api/v1/reports')
      .set(authed(b.accessToken))
      .send({ targetType: 'channel', targetId: channel.id, reason: 'spam' });

    const mine = await request(app).get('/api/v1/reports/mine').set(authed(b.accessToken));
    expect(mine.status).toBe(200);
    expect(mine.body.items).toHaveLength(1);

    const theirs = await request(app).get('/api/v1/reports/mine').set(authed(c.accessToken));
    expect(theirs.body.items).toHaveLength(0);
  });

  it('refuses a message report from someone who is not in the conversation', async () => {
    const { a, b, channel } = await messagingChannel();
    const outsider = await registered(PHONE_C, 'Outsider');
    const conversationId = await openConversation(b, channel.id);
    const message = await send(b, conversationId, 'hello');
    expect(message.status).toBe(201);

    const res = await request(app)
      .post('/api/v1/reports')
      .set(authed(outsider.accessToken))
      .send({ targetType: 'message', targetId: message.body.message.id, reason: 'harassment' });

    expect(res.status).toBe(404);
    expect(res.body.error).toBe('report_target_not_found');
    void a;
  });

  it('lets a channel admin report a follower`s message, since they can read it', async () => {
    const { a, b, channel } = await messagingChannel();
    const conversationId = await openConversation(b, channel.id);
    const message = await send(b, conversationId, 'something rude');
    expect(message.status).toBe(201);

    const res = await request(app)
      .post('/api/v1/reports')
      .set(authed(a.accessToken))
      .send({ targetType: 'message', targetId: message.body.message.id, reason: 'harassment' });

    expect(res.status).toBe(201);
    expect(res.body.report.targetType).toBe('message');
  });
});

// ── User blocks (§12) ───────────────────────────────────────────────────

describe('user blocks', () => {
  it('blocks, lists and unblocks another person', async () => {
    const b = await registered(PHONE_B, 'Blocker');
    const c = await registered(PHONE_C, 'Blocked');

    const block = await request(app)
      .post(`/api/v1/blocks/${c.user.id}`)
      .set(authed(b.accessToken));
    expect(block.status).toBe(200);

    const list = await request(app).get('/api/v1/blocks').set(authed(b.accessToken));
    expect(list.body.items).toEqual([
      { id: c.user.id, displayName: 'Blocked', bio: null, avatarObjectKey: null },
    ]);
    // A block list is one-directional: C must not learn they were blocked.
    const theirs = await request(app).get('/api/v1/blocks').set(authed(c.accessToken));
    expect(theirs.body.items).toEqual([]);

    const unblock = await request(app)
      .delete(`/api/v1/blocks/${c.user.id}`)
      .set(authed(b.accessToken));
    expect(unblock.status).toBe(200);
    const after = await request(app).get('/api/v1/blocks').set(authed(b.accessToken));
    expect(after.body.items).toEqual([]);
  });

  it('refuses blocking yourself', async () => {
    const b = await registered(PHONE_B, 'Blocker');
    const res = await request(app).post(`/api/v1/blocks/${b.user.id}`).set(authed(b.accessToken));
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('cannot_block_self');
  });

  it('stops a conversation once either side has blocked the other', async () => {
    const { a, b, channel } = await messagingChannel();
    const conversationId = await openConversation(b, channel.id);
    await send(b, conversationId, 'hello');

    const blocked = await request(app)
      .post(`/api/v1/blocks/${a.user.id}`)
      .set(authed(b.accessToken));
    expect(blocked.status).toBe(200);

    const again = await send(b, conversationId, 'still here?');
    expect(again.status).toBe(403);
    expect(again.body.error).toBe('conversation_blocked');
    void channel;
  });

  it('exposes another user only as a public profile, never with private fields', async () => {
    const b = await registered(PHONE_B, 'Viewer');
    const c = await registered(PHONE_C, 'Viewed');

    const res = await request(app).get(`/api/v1/users/${c.user.id}`).set(authed(b.accessToken));
    expect(res.status).toBe(200);
    expect(res.body.user).toEqual({
      id: c.user.id,
      displayName: 'Viewed',
      bio: null,
      avatarObjectKey: null,
    });

    // §38 asserted on the raw body: an email or a phone hash appearing here
    // would be a leak even if every field name still looked plausible.
    expect(JSON.stringify(res.body)).not.toContain('@');
    expect(JSON.stringify(res.body)).not.toContain('phone');
  });

  it('answers an unknown user id exactly as it answers a deleted one', async () => {
    const b = await registered(PHONE_B, 'Viewer');
    const res = await request(app)
      .get('/api/v1/users/11111111-2222-3333-4444-555555555555')
      .set(authed(b.accessToken));
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('user_not_found');
  });
});

// ── Private follower messages (§16) ─────────────────────────────────────

describe('private follower messages (§16)', () => {
  it('refuses to open a conversation on a channel that has messages off', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(a, { name: 'No DMs' });
    await follow(b, channel.id);

    // Off is no longer the starting state (§16 now defaults to on), so the
    // owner has to refuse explicitly — which is the case this covers.
    await request(app)
      .patch(`/api/v1/channels/${channel.id}`)
      .set(authed(a.accessToken))
      .send({ allowFollowerMessages: false });

    const res = await request(app)
      .post(`/api/v1/channels/${channel.id}/conversations`)
      .set(authed(b.accessToken));
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('messages_disabled');
  });

  it('refuses messages from someone who does not follow the channel', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Stranger');
    const channel = await createChannel(a, { name: 'Opted in' });
    await request(app)
      .patch(`/api/v1/channels/${channel.id}`)
      .set(authed(a.accessToken))
      .send({ allowFollowerMessages: true });

    const res = await request(app)
      .post(`/api/v1/channels/${channel.id}/conversations`)
      .set(authed(b.accessToken));
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('not_following');
  });

  it('lets the follower write and the admin answer, and marks each side read', async () => {
    const { a, b, channel } = await messagingChannel();
    const conversationId = await openConversation(b, channel.id);

    const sent = await send(b, conversationId, 'Is this open?');
    expect(sent.status).toBe(201);
    expect(sent.body.message.fromAdmin).toBe(false);

    const inbox = await request(app)
      .get(`/api/v1/channels/${channel.id}/conversations`)
      .set(authed(a.accessToken));
    expect(inbox.status).toBe(200);
    expect(inbox.body.items).toHaveLength(1);
    expect(inbox.body.items[0]).toMatchObject({
      id: conversationId,
      unreadCount: 1,
      lastMessagePreview: 'Is this open?',
      blocked: false,
    });
    // The inbox names the follower but must never carry their contact details.
    const inboxPayload = JSON.stringify(inbox.body);
    expect(inboxPayload).toContain('Follower');
    expect(inboxPayload).not.toContain('@');
    expect(inboxPayload).not.toContain('phone');

    const reply = await send(a, conversationId, 'Yes it is.');
    expect(reply.status).toBe(201);
    expect(reply.body.message.fromAdmin).toBe(true);

    const thread = await readMessages(b, conversationId);
    expect(thread.status).toBe(200);
    expect(thread.body.items.map((m: { body: string }) => m.body)).toEqual([
      'Yes it is.',
      'Is this open?',
    ]);
    // Reading is what marks read, and only the OTHER side's messages.
    expect(thread.body.conversation.unreadCount).toBe(0);
  });

  it('refuses a third party the messages, with no distinction from a missing thread', async () => {
    const { b, channel } = await messagingChannel();
    const outsider = await registered(PHONE_C, 'Outsider');
    const conversationId = await openConversation(b, channel.id);
    await send(b, conversationId, 'private');

    const res = await readMessages(outsider, conversationId);
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('conversation_not_found');

    const unknown = await readMessages(outsider, '11111111-2222-3333-4444-555555555555');
    expect(unknown.status).toBe(404);
    expect(unknown.body.error).toBe('conversation_not_found');
  });

  it('stops the follower from writing once the channel blocks the conversation', async () => {
    const { a, b, channel } = await messagingChannel();
    const conversationId = await openConversation(b, channel.id);
    await send(b, conversationId, 'hello');

    const blocked = await request(app)
      .post(`/api/v1/conversations/${conversationId}/block`)
      .set(authed(a.accessToken))
      .send({ blocked: true });
    expect(blocked.status).toBe(200);
    expect(blocked.body.conversation.blocked).toBe(true);

    const res = await send(b, conversationId, 'again');
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('conversation_blocked');

    // The admin must unblock before replying too — otherwise the block only
    // silenced one direction.
    const adminReply = await send(a, conversationId, 'hold on');
    expect(adminReply.status).toBe(403);
    expect(adminReply.body.error).toBe('conversation_blocked');

    const unblocked = await request(app)
      .post(`/api/v1/conversations/${conversationId}/block`)
      .set(authed(a.accessToken))
      .send({ blocked: false });
    expect(unblocked.status).toBe(200);
    expect((await send(a, conversationId, 'ok now')).status).toBe(201);
  });

  it('refuses a follower the moment the channel turns messages off', async () => {
    const { a, b, channel } = await messagingChannel();
    const conversationId = await openConversation(b, channel.id);
    await send(b, conversationId, 'hello');

    await request(app)
      .patch(`/api/v1/channels/${channel.id}`)
      .set(authed(a.accessToken))
      .send({ allowFollowerMessages: false });

    const res = await send(b, conversationId, 'still here');
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('messages_disabled');
  });

  it('refuses an empty or oversized message', async () => {
    const { b, channel } = await messagingChannel();
    const conversationId = await openConversation(b, channel.id);

    const empty = await send(b, conversationId, '   ');
    expect([400, 403]).toContain(empty.status);

    const huge = await send(b, conversationId, 'x'.repeat(5000));
    expect(huge.status).toBe(400);
  });

  it('lists the follower`s own conversations, and opens each channel only once', async () => {
    const { b, channel } = await messagingChannel();
    const first = await openConversation(b, channel.id);
    const second = await openConversation(b, channel.id);
    expect(second).toBe(first);

    const mine = await request(app).get('/api/v1/conversations').set(authed(b.accessToken));
    expect(mine.status).toBe(200);
    expect(mine.body.items).toHaveLength(1);
    expect(mine.body.items[0].channelId).toBe(channel.id);
  });

  it('refuses the channel admin their own conversation, since a channel cannot message itself', async () => {
    const { a, channel } = await messagingChannel();
    const res = await request(app)
      .post(`/api/v1/channels/${channel.id}/conversations`)
      .set(authed(a.accessToken));
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('own_channel');
  });
});

// ── Status changes and ban propagation (§19) ────────────────────────────

describe('account status (§18, §19)', () => {
  it('bans: status, identity block and every session, in one step', async () => {
    const b = await registered(PHONE_B, 'Rule breaker');

    const result = await setAccountStatus(database, b.user.id, 'banned', {
      adminId: ACTING_ADMIN_ID,
      reason: 'repeated abuse',
      note: 'third strike',
    });
    expect(result.status).toBe('banned');
    expect(result.sessionsRevoked).toBe(1);

    // 1. The session the client still holds is dead.
    const me = await request(app).get('/api/v1/auth/me').set(authed(b.accessToken));
    expect(me.status).toBe(401);
    expect(me.body.error).toBe('session_revoked');

    // 2. A fresh sign-in is refused, before anything else.
    const signIn = await request(app)
      .post('/api/v1/auth/signin')
      .send({ idToken: `test:${PHONE_B}` });
    expect(signIn.status).toBe(403);
    expect(signIn.body.error).toBe('phone_banned');

    // 3. The SAME mobile number cannot register a new account, which is the
    //    whole point of anchoring a ban on the phone identity.
    await request(app)
      .post('/api/v1/auth/otp/request')
      .send({ phone: PHONE_B, purpose: 'register' });
    const register = await request(app)
      .post('/api/v1/auth/register')
      .send({ idToken: `test:${PHONE_B}`, displayName: 'Someone Else', email: 'new@example.test' });
    expect(register.status).toBe(403);
    expect(register.body.error).toBe('phone_banned');

    // A different email and a different display name change nothing, and the
    // ban row is keyed on the phone hash rather than on either of them.
    const identities = await pglite.query<{ phone_hash: string; email_normalized: string }>(
      `SELECT phone_hash, email_normalized FROM banned_identities WHERE lifted_at IS NULL`
    );
    expect(identities.rows).toHaveLength(1);
    expect(identities.rows[0]?.email_normalized).toBe('+923004440002@example.test');
  });

  it('does not add a second ban row when the same account is banned twice', async () => {
    const b = await registered(PHONE_B, 'Rule breaker');
    const actor = { adminId: ACTING_ADMIN_ID, reason: 'abuse' };

    await setAccountStatus(database, b.user.id, 'banned', actor);
    await setAccountStatus(database, b.user.id, 'banned', actor);

    const rows = await pglite.query(`SELECT id FROM banned_identities WHERE lifted_at IS NULL`);
    expect(rows.rows).toHaveLength(1);
  });

  it('suspends, which also ends the sessions but does not block the number', async () => {
    const b = await registered(PHONE_B, 'Suspended');
    const result = await setAccountStatus(database, b.user.id, 'suspended', {
      adminId: ACTING_ADMIN_ID,
      reason: 'under review',
    });
    expect(result.sessionsRevoked).toBe(1);

    const me = await request(app).get('/api/v1/auth/me').set(authed(b.accessToken));
    expect(me.status).toBe(401);

    // Suspension is not a ban: the identity is not blocked, so the person can
    // still reach support and be reinstated.
    const banned = await pglite.query(`SELECT id FROM banned_identities`);
    expect(banned.rows).toHaveLength(0);
  });

  it('reinstates, lifting the identity block so the number can register again', async () => {
    const b = await registered(PHONE_B, 'Reinstated');
    const actor = { adminId: ACTING_ADMIN_ID, reason: 'mistake' };

    await setAccountStatus(database, b.user.id, 'banned', actor);
    await setAccountStatus(database, b.user.id, 'active', actor);

    const live = await pglite.query(`SELECT id FROM banned_identities WHERE lifted_at IS NULL`);
    expect(live.rows).toHaveLength(0);

    // A sign-in challenge is required regardless of status — that is M1's flow,
    // not something reinstatement changes.
    await request(app)
      .post('/api/v1/auth/otp/request')
      .send({ phone: PHONE_B, purpose: 'signin' });
    const signIn = await request(app)
      .post('/api/v1/auth/signin')
      .send({ idToken: `test:${PHONE_B}` });
    // The account exists and the number is no longer blocked, so this is a real
    // session rather than a refusal.
    expect(signIn.status).toBe(200);
    expect(signIn.body.accessToken).toBeTruthy();
  });

  it('hides a suspended channel from the feed without erasing it for its owner', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Follower');
    const channel = await createChannel(a, { name: 'Suspend me' });
    await follow(b, channel.id);
    const post = await published(a, channel.id, { body: 'visible' });

    await setChannelStatus(database, channel.id, 'suspended', 'repeated reports');

    const feed = await request(app).get('/api/v1/posts/feed').set(authed(b.accessToken));
    expect(feed.body.items.some((item: { id: string }) => item.id === post.id)).toBe(false);

    // The owner keeps read access, which is what makes a suspension reviewable.
    const history = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(a.accessToken));
    expect(history.status).toBe(200);
    expect(history.body.items).toHaveLength(1);
  });

  it('refuses a banned account in a channel it still administers', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const channel = await createChannel(a, { name: 'Owner gets banned' });

    await setAccountStatus(database, a.user.id, 'banned', {
      adminId: ACTING_ADMIN_ID,
      reason: 'abuse',
    });

    const res = await request(app)
      .post(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(a.accessToken))
      .send({ body: 'still here' });
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('session_revoked');
  });
});
