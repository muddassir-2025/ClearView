import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { unauthorized } from '../src/http/errors.js';
import type { IdentityVerifier, VerifiedIdentity } from '../src/identity/verifier.js';
import { unconfiguredVerifier } from '../src/identity/verifier.js';
import { applyAllMigrations, asQueryable, freshDatabase, one, resetData } from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';

/**
 * A reader's own state (§3, §4, §5, §6).
 *
 * The identity itself is proved in `identity.test.ts`; what is under test here
 * is everything that DEPENDS on it — that a follow belongs to the reader who
 * made it and to nobody else, that the unread badge counts the right posts, and
 * that a token which cannot be verified gets nothing at all rather than
 * somebody's data.
 *
 * Tokens are stand-ins with the verifier faked, because the real signature check
 * is already covered and re-proving it here would only make every one of these
 * tests depend on generating RSA keys. What is NOT faked is the database: these
 * run against a real Postgres (PGlite) through the real migrations, so the
 * foreign keys, the cascade on a deleted channel and the unread SQL are the
 * ones that ship.
 */

let pglite: PGlite;
let store: FakeObjectStore;

/** Token → identity. A token not in the map is a token that does not verify. */
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

/** An app whose verifier knows the given tokens. */
function appWith(verifier: IdentityVerifier = fakeVerifier): Express {
  return buildApp({ database: asQueryable(pglite), store, verifier });
}

/** Register a reader token and return it. */
function readerToken(uid: string, options: { email?: string; anonymous?: boolean } = {}): string {
  const token = `token-for-${uid}`;
  const anonymous = options.anonymous ?? true;
  IDENTITIES.set(token, {
    uid,
    anonymous,
    email: anonymous ? null : (options.email ?? `${uid}@example.test`),
  });
  return token;
}

let counter = 0;
function unique(prefix: string): string {
  counter += 1;
  return `${prefix}-${counter}`;
}

async function seedChannel(
  slug: string,
  options: { status?: 'active' | 'suspended'; name?: string; createdAt?: string } = {}
): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO channels (slug, name, status, created_at)
     VALUES ($1, $2, $3::channel_status, COALESCE($4::timestamptz, now()))
     RETURNING id`,
    [slug, options.name ?? slug, options.status ?? 'active', options.createdAt ?? null]
  );
  return one(rows.rows).id;
}

/** A post, dated relative to now, through the same columns the API reads. */
async function seedPost(
  channelId: string,
  options: { ageHours?: number; body?: string } = {}
): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO posts (channel_id, author_id, type, body, created_at)
     VALUES ($1, NULL, 'text'::post_type, $2, now() - ($3::numeric * interval '1 hour'))
     RETURNING id`,
    [channelId, options.body ?? 'Salam', options.ageHours ?? 0]
  );
  return one(rows.rows).id;
}

/**
 * Move every follow's date into the past, to stand in for elapsed time.
 *
 * The alternative — sleeping, or inserting a future-dated post — would make the
 * unread assertions depend on the clock rather than on the rule, and the rule is
 * "published after the follow", not "published recently". Applied to all rows
 * because the isolation cases need both readers to have followed a while ago.
 */
async function backdateFollows(interval: string): Promise<void> {
  await pglite.query(`UPDATE channel_follows SET followed_at = now() - ($1::text)::interval`, [
    interval,
  ]);
}

function authed(req: request.Test, token: string): request.Test {
  return req.set('Authorization', `Bearer ${token}`);
}

describe('reaching the reader API', () => {
  it('refuses a request with no token, a bad scheme, or an unknown token', async () => {
    const app = appWith();
    const known = readerToken('anon-1');

    const missing = await request(app).get('/api/v1/readers/me');
    expect(missing.status).toBe(401);
    expect(missing.body.error).toBe('invalid_token');

    // The scheme is case-insensitive (RFC 7235); anything else is not a token.
    const wrongScheme = await request(app)
      .get('/api/v1/readers/me')
      .set('Authorization', `Basic ${known}`);
    expect(wrongScheme.status).toBe(401);

    const unknown = await authed(request(app).get('/api/v1/readers/me'), 'not-a-real-token');
    expect(unknown.status).toBe(401);
    expect(unknown.body.error).toBe('invalid_token');
  });

  it('recognises a reader without telling them their own uid', async () => {
    const app = appWith();
    const token = readerToken('anon-1');

    const response = await authed(request(app).get('/api/v1/readers/me'), token);
    expect(response.status).toBe(200);
    expect(response.body.reader).toEqual({ anonymous: true, email: null });
    // The uid is a bearer-adjacent key, not a display value: it stays out of
    // responses so it cannot end up in a log or a screenshot (§30).
    expect(JSON.stringify(response.body)).not.toContain('anon-1');

    // §16's creator account goes through the same route and is told apart by
    // the provider, not by having a token of a different shape.
    const creator = readerToken('maker-1', { anonymous: false, email: 'maker@example.test' });
    const asCreator = await authed(request(app).get('/api/v1/readers/me'), creator);
    expect(asCreator.body.reader).toEqual({ anonymous: false, email: 'maker@example.test' });
  });

  it('answers `auth_unavailable` everywhere when verification is not configured', async () => {
    // 503 rather than 401 and rather than 404: the deployment is incomplete, so
    // retrying after a deploy is the right response, and a reader must never be
    // shown "sign in again" for a server that cannot verify anybody.
    const app = appWith(unconfiguredVerifier());
    const token = readerToken('anon-1');

    for (const call of [
      authed(request(app).get('/api/v1/readers/me'), token),
      authed(request(app).get('/api/v1/readers/me/following'), token),
      authed(request(app).post('/api/v1/readers/me/following/whatever'), token),
    ]) {
      const response = await call;
      expect(response.status).toBe(503);
      expect(response.body.error).toBe('auth_unavailable');
    }

    // And the public read API is unaffected, which is the whole point of
    // degrading rather than failing: Good Post still works for a reader.
    const channels = await request(app).get('/api/v1/channels');
    expect(channels.status).toBe(200);
  });
});

describe('following a channel (§4)', () => {
  it('follows by slug, and the channel appears on the home list', async () => {
    const app = appWith();
    const token = readerToken('anon-1');
    const slug = unique('channels');
    await seedChannel(slug, { name: 'Dev Updates' });

    const followed = await authed(
      request(app).post(`/api/v1/readers/me/following/${slug}`),
      token
    );
    expect(followed.status).toBe(200);
    expect(followed.body.follow).toMatchObject({
      following: true,
      notificationsMuted: false,
      unreadCount: 0,
    });
    expect(followed.body.follow.followedAt).toBeTruthy();

    const list = await authed(request(app).get('/api/v1/readers/me/following'), token);
    expect(list.status).toBe(200);
    expect(list.body.items.map((c: { slug: string }) => c.slug)).toEqual([slug]);
    expect(list.body.items[0]).toMatchObject({
      name: 'Dev Updates',
      following: true,
      notificationsMuted: false,
      unreadCount: 0,
    });
  });

  it('follows by uuid as well as by slug', async () => {
    // A share link carries a slug and the app holds a uuid; a reader must not
    // see a different result depending on which one they arrived through.
    const app = appWith();
    const token = readerToken('anon-1');
    const channelId = await seedChannel(unique('channels'));

    const response = await authed(
      request(app).post(`/api/v1/readers/me/following/${channelId}`),
      token
    );
    expect(response.status).toBe(200);
    expect(response.body.follow.channelId).toBe(channelId);
  });

  it('is idempotent, and does not move the follow date', async () => {
    // `followed_at` bounds the initial unread count, so a double tap that
    // refreshed it would make a channel's whole history unread again.
    const app = appWith();
    const token = readerToken('anon-1');
    const slug = unique('channels');
    await seedChannel(slug);

    const first = await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), token);
    const second = await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), token);

    expect(second.status).toBe(200);
    expect(second.body.follow.followedAt).toBe(first.body.follow.followedAt);

    const rows = await pglite.query(`SELECT 1 FROM channel_follows`);
    expect(rows.rows).toHaveLength(1);
  });

  it('refuses a channel that does not exist, or is not open', async () => {
    const app = appWith();
    const token = readerToken('anon-1');
    const suspended = unique('suspended');
    await seedChannel(suspended, { status: 'suspended' });

    const missing = await authed(
      request(app).post('/api/v1/readers/me/following/no-such-channel'),
      token
    );
    expect(missing.status).toBe(404);
    expect(missing.body.error).toBe('channel_not_found');

    // A suspended channel answers exactly as a missing one: moderation state is
    // not something a reader has any business reading.
    const takenDown = await authed(
      request(app).post(`/api/v1/readers/me/following/${suspended}`),
      token
    );
    expect(takenDown.status).toBe(404);
    expect(takenDown.body.error).toBe('channel_not_found');

    const list = await authed(request(app).get('/api/v1/readers/me/following'), token);
    expect(list.body.items).toEqual([]);
  });

  it('unfollows, and un-following twice is not an error', async () => {
    const app = appWith();
    const token = readerToken('anon-1');
    const slug = unique('channels');
    await seedChannel(slug);

    await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), token);
    const gone = await authed(request(app).delete(`/api/v1/readers/me/following/${slug}`), token);

    expect(gone.status).toBe(200);
    expect(gone.body.follow).toMatchObject({ following: false, unreadCount: 0, followedAt: null });

    const again = await authed(request(app).delete(`/api/v1/readers/me/following/${slug}`), token);
    expect(again.status).toBe(200);

    const list = await authed(request(app).get('/api/v1/readers/me/following'), token);
    expect(list.body.items).toEqual([]);
  });

  it('takes the follow with the channel when the channel is deleted', async () => {
    // §17's delete is real, and a follow pointing at nothing could only ever
    // render as a channel the reader cannot open.
    const app = appWith();
    const token = readerToken('anon-1');
    const slug = unique('channels');
    const channelId = await seedChannel(slug);
    await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), token);

    await pglite.query(`DELETE FROM channels WHERE id = $1`, [channelId]);

    const list = await authed(request(app).get('/api/v1/readers/me/following'), token);
    expect(list.status).toBe(200);
    expect(list.body.items).toEqual([]);

    const rows = await pglite.query(`SELECT 1 FROM channel_follows`);
    expect(rows.rows).toHaveLength(0);
  });
});

describe('unread and read position (§5)', () => {
  it('counts only posts published after the reader followed', async () => {
    const app = appWith();
    const token = readerToken('anon-1');
    const slug = unique('channels');
    const channelId = await seedChannel(slug);

    // Published two days ago; the reader follows now, so this is history, not
    // news. A follow that made a channel's whole retention window unread would
    // open with a badge nobody can clear.
    await seedPost(channelId, { ageHours: 48, body: 'Old news' });

    const followed = await authed(
      request(app).post(`/api/v1/readers/me/following/${slug}`),
      token
    );
    expect(followed.body.follow.unreadCount).toBe(0);

    // The reader followed yesterday, and one post has landed since.
    await backdateFollows('1 day');
    await seedPost(channelId, { ageHours: 1, body: 'Fresh news' });

    const list = await authed(request(app).get('/api/v1/readers/me/following'), token);
    expect(list.body.items[0].unreadCount).toBe(1);
  });

  it('clears the badge on read, and counts posts that arrive afterwards', async () => {
    const app = appWith();
    const token = readerToken('anon-1');
    const slug = unique('channels');
    const channelId = await seedChannel(slug);

    await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), token);
    await backdateFollows('1 day');
    await seedPost(channelId, { ageHours: 2 });
    await seedPost(channelId, { ageHours: 1 });

    const before = await authed(request(app).get('/api/v1/readers/me/following'), token);
    expect(before.body.items[0].unreadCount).toBe(2);

    const read = await authed(
      request(app).post(`/api/v1/readers/me/following/${slug}/read`),
      token
    );
    expect(read.status).toBe(200);
    expect(read.body.follow.unreadCount).toBe(0);

    const cleared = await authed(request(app).get('/api/v1/readers/me/following'), token);
    expect(cleared.body.items[0].unreadCount).toBe(0);

    // A second read is harmless, and a post after it counts again.
    await authed(request(app).post(`/api/v1/readers/me/following/${slug}/read`), token);
    await seedPost(channelId, { ageHours: 0 });

    const after = await authed(request(app).get('/api/v1/readers/me/following'), token);
    expect(after.body.items[0].unreadCount).toBe(1);
  });

  it('has nothing to clear for a channel the reader does not follow', async () => {
    // Opening a channel without following it is the normal way to read (§2), so
    // this is a no-op rather than an error the app has to special-case.
    const app = appWith();
    const token = readerToken('anon-1');
    const slug = unique('channels');
    await seedChannel(slug);
    await seedPost(await seedChannel(unique('channels')), { ageHours: 1 });

    const response = await authed(
      request(app).post(`/api/v1/readers/me/following/${slug}/read`),
      token
    );
    expect(response.status).toBe(200);
    expect(response.body.follow).toMatchObject({ following: false, unreadCount: 0 });
  });

  it('starts clean when a channel is followed again after unfollowing', async () => {
    // The relationship ends on unfollow, so the read position and the mute end
    // with it — the alternative is state that outlives the thing it describes.
    const app = appWith();
    const token = readerToken('anon-1');
    const slug = unique('channels');
    const channelId = await seedChannel(slug);

    await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), token);
    await authed(request(app).patch(`/api/v1/readers/me/following/${slug}`).send({ muted: true }), token);
    await authed(request(app).delete(`/api/v1/readers/me/following/${slug}`), token);

    // A post published while unfollowed, then a fresh follow: it is history again.
    await seedPost(channelId, { ageHours: 1 });
    const refollowed = await authed(
      request(app).post(`/api/v1/readers/me/following/${slug}`),
      token
    );

    expect(refollowed.body.follow).toMatchObject({
      following: true,
      notificationsMuted: false,
      unreadCount: 0,
    });
  });
});

describe('muting a channel (§6)', () => {
  it('mutes and unmutes a followed channel', async () => {
    const app = appWith();
    const token = readerToken('anon-1');
    const slug = unique('channels');
    await seedChannel(slug);
    await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), token);

    const muted = await authed(
      request(app).patch(`/api/v1/readers/me/following/${slug}`).send({ muted: true }),
      token
    );
    expect(muted.status).toBe(200);
    expect(muted.body.follow.notificationsMuted).toBe(true);

    const list = await authed(request(app).get('/api/v1/readers/me/following'), token);
    expect(list.body.items[0].notificationsMuted).toBe(true);

    const unmuted = await authed(
      request(app).patch(`/api/v1/readers/me/following/${slug}`).send({ muted: false }),
      token
    );
    expect(unmuted.body.follow.notificationsMuted).toBe(false);
  });

  it('refuses to mute a channel that is not followed', async () => {
    // Muting is not a way to subscribe: a mute that silently followed a channel
    // would put it on the home screen.
    const app = appWith();
    const token = readerToken('anon-1');
    const slug = unique('channels');
    await seedChannel(slug);

    const response = await authed(
      request(app).patch(`/api/v1/readers/me/following/${slug}`).send({ muted: true }),
      token
    );
    expect(response.status).toBe(404);
    expect(response.body.error).toBe('not_following');
  });

  it('rejects a body that is not a boolean, without echoing it', async () => {
    const app = appWith();
    const token = readerToken('anon-1');
    const slug = unique('channels');
    await seedChannel(slug);
    await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), token);

    const response = await authed(
      request(app)
        .patch(`/api/v1/readers/me/following/${slug}`)
        .send({ muted: 'yes-please-not-a-boolean' }),
      token
    );
    expect(response.status).toBe(400);
    expect(response.body.error).toBe('invalid_request');
    expect(JSON.stringify(response.body)).not.toContain('yes-please-not-a-boolean');
  });
});

describe('one reader’s state is not another’s (§3)', () => {
  it('keeps follows, mutes and read positions apart', async () => {
    const app = appWith();
    const alice = readerToken('alice-uid');
    const bob = readerToken('bob-uid');
    const slug = unique('channels');
    const channelId = await seedChannel(slug);
    const otherSlug = unique('other');

    await seedChannel(otherSlug);

    await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), alice);
    await authed(request(app).post(`/api/v1/readers/me/following/${otherSlug}`), bob);
    await authed(request(app).patch(`/api/v1/readers/me/following/${slug}`).send({ muted: true }), alice);

    const aliceList = await authed(request(app).get('/api/v1/readers/me/following'), alice);
    expect(aliceList.body.items.map((c: { slug: string }) => c.slug)).toEqual([slug]);
    expect(aliceList.body.items[0].notificationsMuted).toBe(true);

    const bobList = await authed(request(app).get('/api/v1/readers/me/following'), bob);
    expect(bobList.body.items.map((c: { slug: string }) => c.slug)).toEqual([otherSlug]);
    // Alice's mute is hers; Bob followed the same channel later and is not muted.
    expect(bobList.body.items[0].notificationsMuted).toBe(false);

    // Alice reading a channel does not clear Bob's badge: Bob follows `slug`
    // through a fresh follow, and the post is his to be told about.
    await backdateFollows('1 day');
    await seedPost(channelId, { ageHours: 1 });
    await authed(request(app).post(`/api/v1/readers/me/following/${slug}/read`), alice);

    await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), bob);
    await backdateFollows('1 day');

    // Looked up by slug rather than by position: Bob follows two channels, and
    // which of them sorts first is a different question with its own test.
    const unreadFor = (body: { items: { slug: string; unreadCount: number }[] }, want: string) =>
      body.items.find((c) => c.slug === want)?.unreadCount;

    const aliceAfter = await authed(request(app).get('/api/v1/readers/me/following'), alice);
    const bobAfter = await authed(request(app).get('/api/v1/readers/me/following'), bob);
    expect(unreadFor(aliceAfter.body, slug)).toBe(0);
    expect(unreadFor(bobAfter.body, slug)).toBe(1);
  });

  it('lets one uid follow and another unfollow independently', async () => {
    const app = appWith();
    const alice = readerToken('alice-uid');
    const bob = readerToken('bob-uid');
    const slug = unique('channels');
    await seedChannel(slug);

    await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), alice);
    await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), bob);
    await authed(request(app).delete(`/api/v1/readers/me/following/${slug}`), alice);

    const aliceList = await authed(request(app).get('/api/v1/readers/me/following'), alice);
    const bobList = await authed(request(app).get('/api/v1/readers/me/following'), bob);
    expect(aliceList.body.items).toEqual([]);
    expect(bobList.body.items.map((c: { slug: string }) => c.slug)).toEqual([slug]);
  });

  it('records the uid once, however many requests a reader makes', async () => {
    const app = appWith();
    const token = readerToken('anon-1');
    await seedChannel(unique('channels'));

    await authed(request(app).get('/api/v1/readers/me'), token);
    await authed(request(app).get('/api/v1/readers/me/following'), token);
    await authed(request(app).get('/api/v1/readers/me'), token);

    const rows = await pglite.query<{ firebase_uid: string; last_seen_at: unknown }>(
      `SELECT firebase_uid FROM readers`
    );
    expect(rows.rows).toEqual([{ firebase_uid: 'anon-1' }]);
  });
});

describe('the home list’s order (§4, §5)', () => {
  it('puts the most recently active channel first', async () => {
    const app = appWith();
    const token = readerToken('anon-1');
    const quiet = unique('quiet');
    const busy = unique('busy');

    // Both channels are OLDER than the quiet one's fallback would be, which is
    // the point: activity decides the order, not when the channel was created.
    // A fixture that let `created_at` be the more recent value would pass even
    // if the ordering ignored activity entirely.
    const twoDaysAgo = new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString();
    const threeDaysAgo = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString();
    const quietId = await seedChannel(quiet, { createdAt: twoDaysAgo });
    const busyId = await seedChannel(busy, { createdAt: threeDaysAgo });

    // Both followed, then one publishes: the list is read in the order a reader
    // reads it, which is "what has something new in it".
    await authed(request(app).post(`/api/v1/readers/me/following/${quiet}`), token);
    await authed(request(app).post(`/api/v1/readers/me/following/${busy}`), token);
    await seedPost(busyId, { ageHours: 1, body: 'Just published' });
    await pglite.query(`UPDATE channels SET last_post_at = now() - interval '1 hour' WHERE id = $1`, [
      busyId,
    ]);
    void quietId;

    const list = await authed(request(app).get('/api/v1/readers/me/following'), token);
    expect(list.body.items.map((c: { slug: string }) => c.slug)).toEqual([busy, quiet]);
  });

  it('paginates without repeating or skipping a channel', async () => {
    const app = appWith();
    const token = readerToken('anon-1');

    const slugs: string[] = [];
    for (let i = 0; i < 3; i += 1) {
      const slug = unique(`page-${i}`);
      slugs.push(slug);
      await seedChannel(slug);
      await authed(request(app).post(`/api/v1/readers/me/following/${slug}`), token);
    }

    const first = await authed(
      request(app).get('/api/v1/readers/me/following?limit=2'),
      token
    );
    expect(first.body.items).toHaveLength(2);
    expect(first.body.nextCursor).toBeTruthy();

    const second = await authed(
      request(app).get(
        `/api/v1/readers/me/following?limit=2&cursor=${encodeURIComponent(first.body.nextCursor)}`
      ),
      token
    );
    expect(second.body.items).toHaveLength(1);
    expect(second.body.nextCursor).toBeNull();

    const seen = [...first.body.items, ...second.body.items].map((c: { slug: string }) => c.slug);
    expect([...seen].sort()).toEqual([...slugs].sort());
    expect(new Set(seen).size).toBe(3);
  });
});
