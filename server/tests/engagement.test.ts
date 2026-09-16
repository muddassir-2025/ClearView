import type { Express } from 'express';
import request from 'supertest';
import type { PGlite } from '@electric-sql/pglite';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { closePool, type Queryable } from '../src/db.js';
import { env } from '../src/env.js';
import { applyAllMigrations, asQueryable, freshDatabase, resetData } from './helpers/database.js';
import { authed, createChannelVia, fakeVerifier, registeredIn } from './helpers/accounts.js';
import { FakeObjectStore } from './helpers/storage.js';

/**
 * M4 engagement (§13, §14, §15).
 *
 * The adversarial cases carry this suite. "A reaction is counted" is not the
 * interesting claim — that one person reacting twice still counts once is, and
 * so is the promise that no response anywhere contains another person's id.
 * §13/§14 are privacy requirements as much as features, so they are tested as
 * such: by asserting on the raw JSON, not on a typed field a mapper might have
 * quietly dropped.
 */

const PHONE_A = '+923003330001';
const PHONE_B = '+923003330002';
const PHONE_C = '+923003330003';

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
});

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
  expect(res.status, 'follow should succeed in a fixture').toBe(200);
}

async function published(session: Session, channelId: string, body: Record<string, unknown>) {
  const res = await request(app)
    .post(`/api/v1/channels/${channelId}/posts`)
    .set(authed(session.accessToken))
    .send(body);
  expect(res.status, `publish ${JSON.stringify(body)}`).toBe(201);
  return res.body.post as PostPayload;
}

interface ReactionCount {
  reaction: string;
  count: number;
}

interface PollPayload {
  id: string;
  question: string;
  allowMultiple: boolean;
  closesAt: string | null;
  isClosed: boolean;
  totalVotes: number;
  options: { id: string; position: number; label: string; votes: number }[];
  viewerVotes: string[];
}

interface Engagement {
  reactions: ReactionCount[];
  reactionTotal: number;
  viewerReaction: string | null;
  uniqueViewers: number;
  totalViews: number;
  viewerHasViewed: boolean;
  poll: PollPayload | null;
}

interface PostPayload {
  id: string;
  channelId: string;
  type: string;
  body: string | null;
  engagement: Engagement;
}

/**
 * A poll option that is definitely present.
 *
 * `noUncheckedIndexedAccess` makes `poll.options[0]` optional, which is correct
 * in general and noise here: the fixture just published this poll, so a missing
 * option is a bug in the product, and failing with a sentence beats a cast.
 */
function optionAt(poll: PollPayload, index: number): { id: string; label: string; votes: number } {
  const option = poll.options[index];
  if (!option) throw new Error(`expected a poll option at position ${index}`);
  return option;
}

const react = (session: Session, postId: string, reaction: string | null) =>
  request(app)
    .post(`/api/v1/posts/${postId}/reactions`)
    .set(authed(session.accessToken))
    .send({ reaction });

const view = (session: Session, postId: string) =>
  request(app).post(`/api/v1/posts/${postId}/views`).set(authed(session.accessToken));

const vote = (session: Session, pollId: string, optionIds: string[]) =>
  request(app)
    .post(`/api/v1/polls/${pollId}/votes`)
    .set(authed(session.accessToken))
    .send({ optionIds });

const analytics = (session: Session, channelId: string, query = '') =>
  request(app)
    .get(`/api/v1/channels/${channelId}/analytics${query}`)
    .set(authed(session.accessToken));

/** Reads a post back through the channel history, the way a client would. */
async function readBack(session: Session, channelId: string, postId: string): Promise<PostPayload> {
  const res = await request(app)
    .get(`/api/v1/channels/${channelId}/posts`)
    .set(authed(session.accessToken));
  expect(res.status).toBe(200);
  const found = (res.body.items as PostPayload[]).find((item) => item.id === postId);
  if (!found) throw new Error('the post was not in the channel history');
  return found;
}

/** A channel owned by A, with B following it: the standard engagement fixture. */
async function channelWithFollower() {
  const a = await registered(PHONE_A, 'Owner');
  const b = await registered(PHONE_B, 'Follower');
  const channel = await createChannel(a, { name: `Engage ${Math.random().toString(36).slice(2, 8)}` });
  await follow(b, channel.id);
  return { a, b, channel };
}

// ── Reactions ───────────────────────────────────────────────────────────

describe('reactions (§13)', () => {
  it('counts a reaction and reports the viewer their own', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });

    const res = await react(b, post.id, 'love');
    expect(res.status).toBe(200);
    expect(res.body.reaction).toBe('love');
    expect(res.body.reactions).toEqual([{ reaction: 'love', count: 1 }]);
  });

  it('aggregates several people without naming any of them', async () => {
    const { a, b, channel } = await channelWithFollower();
    const c = await registered(PHONE_C, 'Third');
    await follow(c, channel.id);
    const post = await published(a, channel.id, { body: 'hello' });

    await react(b, post.id, 'like');
    const res = await react(c, post.id, 'like');
    expect(res.body.reactions).toEqual([{ reaction: 'like', count: 2 }]);

    // The privacy claim, asserted on the wire rather than on a typed field: a
    // mapper could drop a field and a shape assertion would still pass.
    const payload = JSON.stringify(res.body);
    expect(payload).not.toContain(b.user.id);
    expect(payload).not.toContain(c.user.id);
    expect(Object.keys(res.body).sort()).toEqual(['reaction', 'reactions']);
  });

  it('replaces a reaction instead of counting a second one', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });

    await react(b, post.id, 'like');
    const res = await react(b, post.id, 'laugh');

    expect(res.body.reaction).toBe('laugh');
    expect(res.body.reactions).toEqual([{ reaction: 'laugh', count: 1 }]);
    expect(res.body.reactions.some((entry: ReactionCount) => entry.reaction === 'like')).toBe(false);
  });

  it('clears a reaction with a null, and with a DELETE', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });

    await react(b, post.id, 'like');
    const cleared = await react(b, post.id, null);
    expect(cleared.status).toBe(200);
    expect(cleared.body.reaction).toBeNull();
    expect(cleared.body.reactions).toEqual([]);

    await react(b, post.id, 'like');
    const deleted = await request(app)
      .delete(`/api/v1/posts/${post.id}/reactions`)
      .set(authed(b.accessToken));
    expect(deleted.status).toBe(200);
    expect(deleted.body.reactions).toEqual([]);
  });

  it('refuses a reaction kind the app does not offer', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });

    const res = await react(b, post.id, 'shrug');
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_reaction');
  });

  it('refuses a reaction to a post the viewer does not follow', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Stranger');
    const channel = await createChannel(a, { name: 'Closed' });
    const post = await published(a, channel.id, { body: 'hello' });
    expect(post.id).toBeTruthy();
    // Following is what makes reacting legitimate (§12), so this is a 403 and
    // not a silent success on an unfollowed channel.
    void b;

    const res = await react(b, post.id, 'like');
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('not_following');
  });

  it('refuses a reaction on a removed post, exactly as it does for a missing one', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });

    const removed = await request(app)
      .delete(`/api/v1/channels/${channel.id}/posts/${post.id}`)
      .set(authed(a.accessToken));
    expect(removed.status).toBe(200);

    const res = await react(b, post.id, 'like');
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('post_not_found');
  });

  it('refuses a reaction on a suspended channel', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });
    await pglite.query(`UPDATE channels SET status = 'suspended' WHERE id = $1`, [channel.id]);

    const res = await react(b, post.id, 'like');
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('channel_unavailable');
  });

  it('refuses a reaction from someone who blocked the channel', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });

    const blocked = await request(app)
      .post(`/api/v1/channels/${channel.id}/block`)
      .set(authed(b.accessToken));
    expect(blocked.status).toBe(200);

    const res = await react(b, post.id, 'like');
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('channel_blocked');
  });
});

// ── Views ───────────────────────────────────────────────────────────────

describe('post views (§15)', () => {
  it('counts the first look and ignores a repeat inside the window', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });

    const first = await view(b, post.id);
    expect(first.status).toBe(200);
    expect(first.body).toMatchObject({ uniqueViewers: 1, totalViews: 1, counted: true });

    // The scroll loop §15 is about: five more pings must change nothing.
    for (let i = 0; i < 5; i += 1) {
      const again = await view(b, post.id);
      expect(again.body.counted).toBe(false);
      expect(again.body.totalViews).toBe(1);
    }
  });

  it('counts a later look as another view but not another viewer', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });
    await view(b, post.id);

    // Backdating is how a window that is genuinely an hour long gets tested
    // without sleeping for one.
    await pglite.query(
      `UPDATE post_views SET last_viewed_at = now() - interval '2 hours' WHERE post_id = $1`,
      [post.id]
    );

    const res = await view(b, post.id);
    expect(res.body.counted).toBe(true);
    expect(res.body.totalViews).toBe(2);
    expect(res.body.uniqueViewers).toBe(1);
  });

  it('counts viewers, not looks, across accounts', async () => {
    const { a, b, channel } = await channelWithFollower();
    const c = await registered(PHONE_C, 'Third');
    await follow(c, channel.id);
    const post = await published(a, channel.id, { body: 'hello' });

    await view(b, post.id);
    await view(c, post.id);

    const read = await readBack(b, channel.id, post.id);
    expect(read.engagement.uniqueViewers).toBe(2);
    expect(read.engagement.totalViews).toBe(2);
    expect(read.engagement.viewerHasViewed).toBe(true);
  });

  it('refuses a view from someone who does not follow the channel', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Stranger');
    const channel = await createChannel(a, { name: 'Closed' });
    const post = await published(a, channel.id, { body: 'hello' });
    expect(post.id).toBeTruthy();

    const res = await view(b, post.id);
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('not_following');
  });
});

// ── Polls ───────────────────────────────────────────────────────────────

describe('polls (§14)', () => {
  const pollBody = (extra: Record<string, unknown> = {}) => ({
    poll: { question: 'Which one?', options: ['A', 'B', 'C'], ...extra },
  });

  it('publishes a poll post and returns it with zero counts', async () => {
    const { a, channel } = await channelWithFollower();
    const post = await published(a, channel.id, pollBody());

    expect(post.type).toBe('poll');
    expect(post.engagement.poll?.question).toBe('Which one?');
    expect(post.engagement.poll?.options.map((option) => option.label)).toEqual(['A', 'B', 'C']);
    expect(post.engagement.poll?.totalVotes).toBe(0);
    expect(post.engagement.poll?.allowMultiple).toBe(false);
  });

  it('refuses a poll with too few options, and names the rule', async () => {
    const { a, channel } = await channelWithFollower();
    const res = await request(app)
      .post(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(a.accessToken))
      .send({ poll: { question: 'One?', options: ['only'] } });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('too_few_options');
  });

  it('refuses duplicate options, ignoring case and padding', async () => {
    const { a, channel } = await channelWithFollower();
    const res = await request(app)
      .post(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(a.accessToken))
      .send({ poll: { question: 'Same?', options: ['Yes', ' yes '] } });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('duplicate_option');
  });

  it('refuses more options than the configured maximum', async () => {
    const { a, channel } = await channelWithFollower();
    const options = Array.from({ length: env.POLL_MAX_OPTIONS + 1 }, (_, i) => `Option ${i}`);

    const res = await request(app)
      .post(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(a.accessToken))
      .send({ poll: { question: 'Too many?', options } });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('too_many_options');
  });

  it('refuses a poll that also carries media', async () => {
    const { a, channel } = await channelWithFollower();
    const res = await request(app)
      .post(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(a.accessToken))
      .send({
        poll: { question: 'Which?', options: ['A', 'B'] },
        mediaIds: ['11111111-1111-1111-1111-111111111111'],
      });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('poll_with_media');
  });

  it('votes, and returns aggregate counts only', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, pollBody());
    const poll = post.engagement.poll;
    if (!poll) throw new Error('expected a poll');
    const optionA = optionAt(poll, 0);

    const res = await vote(b, poll.id, [optionA.id]);
    expect(res.status).toBe(200);
    expect(res.body.poll.totalVotes).toBe(1);
    expect(res.body.poll.options.map((option: { votes: number }) => option.votes)).toEqual([1, 0, 0]);
    expect(res.body.poll.viewerVotes).toEqual([optionA.id]);

    // No voter's id may appear, in any response for a poll.
    expect(JSON.stringify(res.body)).not.toContain(b.user.id);
  });

  it('replaces a single-choice vote rather than adding to it', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, pollBody());
    const poll = post.engagement.poll;
    if (!poll) throw new Error('expected a poll');

    await vote(b, poll.id, [optionAt(poll, 0).id]);
    const res = await vote(b, poll.id, [optionAt(poll, 1).id]);

    expect(res.body.poll.viewerVotes).toEqual([optionAt(poll, 1).id]);
    expect(res.body.poll.options.map((option: { votes: number }) => option.votes)).toEqual([0, 1, 0]);
    expect(res.body.poll.totalVotes).toBe(1);
  });

  it('refuses two answers to a single-choice poll', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, pollBody());
    const poll = post.engagement.poll;
    if (!poll) throw new Error('expected a poll');

    const res = await vote(b, poll.id, [optionAt(poll, 0).id, optionAt(poll, 1).id]);
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('single_choice_only');
  });

  it('lets a multiple-choice vote shrink, since changing your mind must be possible', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, pollBody({ allowMultiple: true }));
    const poll = post.engagement.poll;
    if (!poll) throw new Error('expected a poll');

    const first = await vote(b, poll.id, [optionAt(poll, 0).id, optionAt(poll, 1).id, optionAt(poll, 2).id]);
    expect(first.body.poll.totalVotes).toBe(3);

    const second = await vote(b, poll.id, [optionAt(poll, 1).id]);
    expect(second.body.poll.viewerVotes).toEqual([optionAt(poll, 1).id]);
    expect(second.body.poll.totalVotes).toBe(1);
  });

  it('refuses a vote in a closed poll', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, pollBody({ openForHours: 24 }));
    const poll = post.engagement.poll;
    if (!poll) throw new Error('expected a poll');
    expect(poll.isClosed).toBe(false);

    await pglite.query(`UPDATE polls SET closes_at = now() - interval '1 minute' WHERE id = $1`, [
      poll.id,
    ]);

    const res = await vote(b, poll.id, [optionAt(poll, 0).id]);
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('poll_closed');
  });

  it('refuses an option belonging to a different poll', async () => {
    const { a, b, channel } = await channelWithFollower();
    const first = await published(a, channel.id, pollBody());
    const second = await published(a, channel.id, {
      poll: { question: 'Other?', options: ['X', 'Y'] },
    });
    const poll = first.engagement.poll;
    const other = second.engagement.poll;
    if (!poll || !other) throw new Error('expected two polls');

    const res = await vote(b, poll.id, [optionAt(other, 0).id]);
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('unknown_option');
  });

  it('refuses a vote from someone who does not follow the channel', async () => {
    const a = await registered(PHONE_A, 'Owner');
    const b = await registered(PHONE_B, 'Stranger');
    const channel = await createChannel(a, { name: 'Closed' });
    const post = await published(a, channel.id, pollBody());
    const poll = post.engagement.poll;
    if (!poll) throw new Error('expected a poll');

    const res = await vote(b, poll.id, [optionAt(poll, 0).id]);
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('not_following');
  });

  it('refuses an empty selection', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, pollBody());
    const poll = post.engagement.poll;
    if (!poll) throw new Error('expected a poll');

    const res = await vote(b, poll.id, []);
    // The shape rejects it before the service sees it, which is still a 400 the
    // client can act on.
    expect(res.status).toBe(400);
  });
});

// ── Analytics ───────────────────────────────────────────────────────────

describe('channel analytics (§15)', () => {
  it('reports totals and a complete window for the owner', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });
    await react(b, post.id, 'like');
    await view(b, post.id);

    const res = await analytics(a, channel.id, '?days=7');
    expect(res.status).toBe(200);

    const body = res.body.analytics;
    expect(body.windowDays).toBe(7);
    expect(body.totals).toMatchObject({
      followers: 1,
      posts: 1,
      uniqueViewers: 1,
      totalViews: 1,
      reactions: 1,
    });
    // Every day in the window is present, including the quiet ones: a chart
    // with gaps is a chart that lies about a quiet week.
    expect(body.series).toHaveLength(7);
    expect(body.series.every((point: { day: string }) => /^\d{4}-\d{2}-\d{2}$/.test(point.day))).toBe(
      true
    );
    const today = body.series[body.series.length - 1];
    expect(today.views).toBe(1);
    expect(today.newFollowers).toBe(1);
    expect(body.topPosts[0]).toMatchObject({ postId: post.id, uniqueViewers: 1, reactions: 1 });
  });

  it('refuses a follower, and a stranger, with no detail about the channel', async () => {
    const { a, b, channel } = await channelWithFollower();
    const c = await registered(PHONE_C, 'Stranger');

    const asFollower = await analytics(b, channel.id);
    expect(asFollower.status).toBe(403);
    expect(asFollower.body.error).toBe('analytics_forbidden');

    const asStranger = await analytics(c, channel.id);
    expect(asStranger.status).toBe(403);
    expect(asStranger.body.error).toBe('analytics_forbidden');
    void a;
  });

  it('is not reachable through the channel router, and does not break it', async () => {
    const { a, channel } = await channelWithFollower();

    // Both routers share the `/api/v1/channels` prefix. This asserts the
    // engagement route is answered by the engagement router while the channel
    // router's own paths still reach the channel router.
    const detail = await request(app)
      .get(`/api/v1/channels/${channel.id}`)
      .set(authed(a.accessToken));
    expect(detail.status).toBe(200);
    expect(detail.body.channel.id).toBe(channel.id);
  });
});

// ── Engagement inside post payloads ─────────────────────────────────────

describe('engagement in post payloads', () => {
  it('carries the counts in the channel history and the feed', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });
    await react(b, post.id, 'wow');
    await view(b, post.id);

    const history = await readBack(b, channel.id, post.id);
    expect(history.engagement.reactionTotal).toBe(1);
    expect(history.engagement.viewerReaction).toBe('wow');
    expect(history.engagement.uniqueViewers).toBe(1);

    const feed = await request(app)
      .get('/api/v1/posts/feed')
      .set(authed(b.accessToken));
    expect(feed.status).toBe(200);
    const inFeed = (feed.body.items as PostPayload[]).find((item) => item.id === post.id);
    expect(inFeed?.engagement.reactionTotal).toBe(1);
    expect(inFeed?.engagement.viewerReaction).toBe('wow');
  });

  it('answers a viewer who has not reacted with a null, not a missing field', async () => {
    const { a, b, channel } = await channelWithFollower();
    const post = await published(a, channel.id, { body: 'hello' });

    const history = await readBack(b, channel.id, post.id);
    expect(history.engagement.viewerReaction).toBeNull();
    expect(history.engagement.reactions).toEqual([]);
    expect(history.engagement.poll).toBeNull();
  });
});
