import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { unauthorized } from '../src/http/errors.js';
import type { IdentityVerifier, VerifiedIdentity } from '../src/identity/verifier.js';
import { applyAllMigrations, asQueryable, freshDatabase, one, resetData } from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';
import { REACTION_EMOJI } from '../src/reactions/service.js';

/**
 * Reactions on a post (§9).
 *
 * What is under test is the set of rules the feature is MADE of, not the shape of
 * the responses: one reaction per reader (replacing it does not add a second),
 * counts that are honest about who is asking and who is not, a reaction that a
 * reader cannot put on a post they cannot see, and the one privacy claim the
 * feature makes — that a count is a count and no reader is named in it.
 *
 * The database is real (PGlite, through the real migrations), so the primary key,
 * the foreign keys, the emoji constraint and the cascade on a deleted post are
 * the ones that ship. Tokens are stand-ins with a faked verifier, as in the other
 * reader suites: the signature check is proved in `identity.test.ts`.
 */

let pglite: PGlite;
let store: FakeObjectStore;

const IDENTITIES = new Map<string, VerifiedIdentity>();

const fakeVerifier: IdentityVerifier = {
  configured: true,
  async verify(token: string): Promise<VerifiedIdentity> {
    const identity = IDENTITIES.get(token);
    if (!identity) throw unauthorized('invalid_token', 'Unknown test token.');
    return identity;
  },
};

beforeAll(async () => {
  pglite = await freshDatabase();
  await applyAllMigrations(pglite);
});

afterAll(async () => {
  await pglite.close();
});

beforeEach(async () => {
  await resetData(pglite);
  IDENTITIES.clear();
  store = new FakeObjectStore();
});

function app(): Express {
  return buildApp({ database: asQueryable(pglite), store, verifier: fakeVerifier });
}

let readers = 0;
function readerToken(): string {
  readers += 1;
  const uid = `anon-${readers}`;
  const token = `token-for-${uid}`;
  IDENTITIES.set(token, {
    uid,
    anonymous: true,
    email: null,
    emailVerified: false,
  });
  return token;
}

const authed = (token: string) => ({ Authorization: `Bearer ${token}` });

let channels = 0;
async function seedChannel(status: 'active' | 'suspended' = 'active'): Promise<string> {
  channels += 1;
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO channels (slug, name, status) VALUES ($1, $1, $2::channel_status) RETURNING id`,
    [`channel-${channels}`, status]
  );
  return one(rows.rows).id;
}

/** A seeded channel's slug, for the routes that take one instead of an id. */
async function slugOf(channelId: string): Promise<string> {
  const rows = await pglite.query<{ slug: string }>(`SELECT slug FROM channels WHERE id = $1`, [
    channelId,
  ]);
  return one(rows.rows).slug;
}

async function seedPost(channelId: string, deleted = false): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO posts (channel_id, author_id, type, body, deleted_at)
     VALUES ($1, NULL, 'text'::post_type, 'Salam', $2::timestamptz)
     RETURNING id`,
    [channelId, deleted ? new Date().toISOString() : null]
  );
  return one(rows.rows).id;
}

/** The post payload as a reader (or anybody) sees it. */
async function publicPost(app: Express, postId: string): Promise<{ reactions: unknown }> {
  const res = await request(app).get(`/api/v1/posts/${postId}`);
  expect(res.status, `reading the post should succeed (${JSON.stringify(res.body)})`).toBe(200);
  return res.body.post as { reactions: unknown };
}

describe('the vocabulary (§9)', () => {
  it('is publishable without a token, so a client can draw the buttons before signing in', async () => {
    const res = await request(app()).get('/api/v1/readers/reactions');
    expect(res.status).toBe(200);
    expect(res.body.emoji).toEqual([...REACTION_EMOJI]);
  });
});

describe('reacting (§9)', () => {
  it('records one reaction and shows its count on the post', async () => {
    const appInstance = app();
    const channel = await seedChannel();
    const post = await seedPost(channel);
    const token = readerToken();

    const res = await request(appInstance)
      .put(`/api/v1/readers/me/reactions/${post}`)
      .set(authed(token))
      .send({ emoji: '❤️' });

    expect(res.status).toBe(200);
    expect(res.body.reaction).toMatchObject({ postId: post, emoji: '❤️', count: 1 });

    // The count is PUBLIC: it is how a card shows that a message has been liked,
    // and the reader who reacted is not named anywhere in it.
    expect(await publicPost(appInstance, post)).toMatchObject({
      reactions: [{ emoji: '❤️', count: 1 }],
    });
  });

  it('replaces the reader’s own reaction instead of adding a second', async () => {
    const appInstance = app();
    const channel = await seedChannel();
    const post = await seedPost(channel);
    const token = readerToken();

    await request(appInstance)
      .put(`/api/v1/readers/me/reactions/${post}`)
      .set(authed(token))
      .send({ emoji: '👍' });
    const second = await request(appInstance)
      .put(`/api/v1/readers/me/reactions/${post}`)
      .set(authed(token))
      .send({ emoji: '😂' });

    expect(second.status).toBe(200);
    // One row, one emoji: the heart is gone, and the thumbs-up count is zero
    // rather than left behind as a stale number.
    expect(await publicPost(appInstance, post)).toMatchObject({
      reactions: [{ emoji: '😂', count: 1 }],
    });
    const rows = await pglite.query<{ count: number }>(
      `SELECT count(*)::int AS count FROM post_reactions WHERE post_id = $1`,
      [post]
    );
    expect(one(rows.rows).count).toBe(1);
  });

  it('counts two readers separately and lists the commoner emoji first', async () => {
    const appInstance = app();
    const channel = await seedChannel();
    const post = await seedPost(channel);
    const a = readerToken();
    const b = readerToken();
    const c = readerToken();

    for (const [token, emoji] of [
      [a, '👍'],
      [b, '👍'],
      [c, '❤️'],
    ] as const) {
      const res = await request(appInstance)
        .put(`/api/v1/readers/me/reactions/${post}`)
        .set(authed(token))
        .send({ emoji });
      expect(res.status).toBe(200);
    }

    expect(await publicPost(appInstance, post)).toMatchObject({
      reactions: [
        { emoji: '👍', count: 2 },
        { emoji: '❤️', count: 1 },
      ],
    });
  });

  it('takes the reaction back off, and says so', async () => {
    const appInstance = app();
    const channel = await seedChannel();
    const post = await seedPost(channel);
    const token = readerToken();

    await request(appInstance)
      .put(`/api/v1/readers/me/reactions/${post}`)
      .set(authed(token))
      .send({ emoji: '🙏' });

    const removed = await request(appInstance)
      .delete(`/api/v1/readers/me/reactions/${post}?emoji=🙏`)
      .set(authed(token));

    expect(removed.status).toBe(200);
    // The count of the emoji the client was SHOWING, so the card can correct the
    // number it has on screen without a second read.
    expect(removed.body.reaction).toMatchObject({ postId: post, emoji: null, count: 0 });
    expect(await publicPost(appInstance, post)).toMatchObject({ reactions: [] });
  });

  it('is idempotent: removing a reaction that is not there is not an error', async () => {
    const appInstance = app();
    const channel = await seedChannel();
    const post = await seedPost(channel);
    const token = readerToken();

    const res = await request(appInstance)
      .delete(`/api/v1/readers/me/reactions/${post}`)
      .set(authed(token));

    expect(res.status).toBe(200);
    expect(res.body.reaction).toMatchObject({ emoji: null, count: 0 });
  });

  it('refuses an emoji that is not one of the six', async () => {
    const appInstance = app();
    const channel = await seedChannel();
    const post = await seedPost(channel);
    const token = readerToken();

    const res = await request(appInstance)
      .put(`/api/v1/readers/me/reactions/${post}`)
      .set(authed(token))
      .send({ emoji: '🍕' });

    expect(res.status).toBe(400);
    const rows = await pglite.query(`SELECT 1 FROM post_reactions WHERE post_id = $1`, [post]);
    expect(rows.rows).toHaveLength(0);
  });

  it('refuses an unauthenticated caller — a reaction belongs to somebody', async () => {
    const appInstance = app();
    const channel = await seedChannel();
    const post = await seedPost(channel);

    const res = await request(appInstance)
      .put(`/api/v1/readers/me/reactions/${post}`)
      .send({ emoji: '👍' });

    expect(res.status).toBe(401);
  });
});

describe('what a reaction may attach to (§9)', () => {
  it('refuses a post that does not exist, a removed post and a suspended channel', async () => {
    const appInstance = app();
    const channel = await seedChannel();
    const removed = await seedPost(channel, true);
    const suspended = await seedChannel('suspended');
    const onSuspended = await seedPost(suspended);
    const token = readerToken();

    const missing = await request(appInstance)
      .put('/api/v1/readers/me/reactions/3f0b6b1e-0000-4000-8000-000000000000')
      .set(authed(token))
      .send({ emoji: '👍' });
    const gone = await request(appInstance)
      .put(`/api/v1/readers/me/reactions/${removed}`)
      .set(authed(token))
      .send({ emoji: '👍' });
    const elsewhere = await request(appInstance)
      .put(`/api/v1/readers/me/reactions/${onSuspended}`)
      .set(authed(token))
      .send({ emoji: '👍' });

    // One code for all three: the caller's action is the same, and telling them
    // apart would say whether a post id they guessed exists.
    for (const res of [missing, gone, elsewhere]) {
      expect(res.status).toBe(404);
      expect(res.body.error).toBe('post_not_found');
    }

    const rows = await pglite.query(`SELECT 1 FROM post_reactions`);
    expect(rows.rows).toHaveLength(0);
  });

  it('answers a malformed id as a 404 rather than a database error', async () => {
    const appInstance = app();
    const token = readerToken();

    const res = await request(appInstance)
      .put('/api/v1/readers/me/reactions/not-a-uuid')
      .set(authed(token))
      .send({ emoji: '👍' });

    expect(res.status).toBe(404);
  });

  it('takes the reactions away with a deleted post, so no count outlives it', async () => {
    const appInstance = app();
    const channel = await seedChannel();
    const post = await seedPost(channel);
    const token = readerToken();

    await request(appInstance)
      .put(`/api/v1/readers/me/reactions/${post}`)
      .set(authed(token))
      .send({ emoji: '👍' });

    await pglite.query(`DELETE FROM posts WHERE id = $1`, [post]);

    const rows = await pglite.query(`SELECT 1 FROM post_reactions WHERE post_id = $1`, [post]);
    expect(rows.rows).toHaveLength(0);
  });
});

describe('a reader’s own reactions (§9)', () => {
  it('lists the caller’s reactions in a channel, and nobody else’s', async () => {
    const appInstance = app();
    const channel = await seedChannel();
    const other = await seedChannel();
    const post = await seedPost(channel);
    const elsewhere = await seedPost(other);
    const mine = readerToken();
    const theirs = readerToken();

    for (const [token, id, emoji] of [
      [mine, post, '👍'],
      [theirs, post, '😂'],
      [theirs, elsewhere, '❤️'],
    ] as const) {
      await request(appInstance)
        .put(`/api/v1/readers/me/reactions/${id}`)
        .set(authed(token))
        .send({ emoji });
    }

    const res = await request(appInstance)
      .get(`/api/v1/readers/me/reactions/${channel}`)
      .set(authed(mine));

    expect(res.status).toBe(200);
    // Scoped to the reader AND the channel: the other reader's reaction on the
    // same post is not mine, and my own reaction in another channel is not part of
    // this answer.
    expect(res.body.reactions).toEqual({ [post]: '👍' });
  });

  it('resolves the channel by slug as well as by id', async () => {
    const appInstance = app();
    const channel = await seedChannel();
    const post = await seedPost(channel);
    const token = readerToken();

    await request(appInstance)
      .put(`/api/v1/readers/me/reactions/${post}`)
      .set(authed(token))
      .send({ emoji: '😢' });

    const res = await request(appInstance)
      .get(`/api/v1/readers/me/reactions/${await slugOf(channel)}`)
      .set(authed(token));

    expect(res.status).toBe(200);
    expect(res.body.reactions).toEqual({ [post]: '😢' });
  });

  it('refuses without a token, and asks the same question of an unknown channel', async () => {
    const appInstance = app();
    const channel = await seedChannel();

    const anonymous = await request(appInstance).get(`/api/v1/readers/me/reactions/${channel}`);
    expect(anonymous.status).toBe(401);

    const missing = await request(appInstance)
      .get('/api/v1/readers/me/reactions/no-such-channel')
      .set(authed(readerToken()));
    expect(missing.status).toBe(404);
    expect(missing.body.error).toBe('channel_not_found');
  });
});
