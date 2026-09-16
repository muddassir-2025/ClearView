import type { Express } from 'express';
import request from 'supertest';
import type { PGlite } from '@electric-sql/pglite';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { closePool, type Queryable } from '../src/db.js';
import { env } from '../src/env.js';
import { slugCandidate, slugify, SLUG_MAX_LENGTH } from '../src/channels/slug.js';
import { decodeCursor, encodeCursor, isUuid, parsePageSize } from '../src/channels/cursor.js';
import { applyAllMigrations, asQueryable, freshDatabase, resetData } from './helpers/database.js';
import { authed, createChannelVia, fakeVerifier, registeredIn } from './helpers/accounts.js';

/**
 * M2 channels and discovery (§5, §6, §7, §12, §32).
 *
 * Runs the real routes against a real Postgres (PGlite), so a pass means the
 * schema, the SQL, the authorization reads and the error mapping agree. The
 * adversarial cases carry most of the weight: §39 asks for unauthorized
 * requests to be attempted explicitly, and the interesting bug in a feature
 * like this is never "creating a channel works".
 */

const PHONE_A = '+923001110001';
const PHONE_B = '+923001110002';

let pglite: PGlite;
let database: Queryable;
let app: Express;

beforeAll(async () => {
  pglite = await freshDatabase();
  await applyAllMigrations(pglite);
  database = asQueryable(pglite);
  app = buildApp({ database, verifier: fakeVerifier() });
});

beforeEach(async () => {
  await resetData(pglite);
});

afterAll(async () => {
  await pglite.close();
  await closePool();
});

// ── Helpers ─────────────────────────────────────────────────────────────
// Bound to this file's app so the call sites below read as one argument.
// The bodies live in `helpers/accounts.ts` because the posts suite needs the
// same three steps, and a second copy of "register a user" is a second place
// for the flow to drift from the one the app actually serves.

const registered = (phone: string, name?: string, email?: string) =>
  registeredIn(app, phone, name, email);

const createChannel = (session: { accessToken: string }, body: Record<string, unknown>) =>
  createChannelVia(app, session, body);

// ── Pure helpers ────────────────────────────────────────────────────────
// These need no database, so they run as plain units.

describe('channel slugs', () => {
  it('folds a display name into a constraint-shaped slug', () => {
    expect(slugify('ClearView Updates')).toBe('clearview-updates');
    expect(slugify('  Mixed   SPACES  ')).toBe('mixed-spaces');
    expect(slugify('Café & Co.')).toBe('cafe-co');
    expect(slugify('--Already--Dashed--')).toBe('already-dashed');
  });

  it('never returns a slug the CHECK constraint would reject', () => {
    // A product name can be entirely non-ASCII, which collapses to nothing
    // under the ASCII fold. Returning an empty slug here would be an INSERT
    // that fails with a 500 rather than a channel with a dull link.
    for (const name of ['اخبار', '!!!', '###', '日本語', '   ']) {
      const slug = slugify(name);
      expect(slug.length).toBeGreaterThan(0);
      expect(slug).toMatch(/^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$/);
    }
  });

  it('keeps attempt 0 short and every retry unique and in range', () => {
    expect(slugCandidate('News', 0, 'abcdef')).toBe('news');

    const retry = slugCandidate('News', 1, 'abcdef');
    expect(retry).toBe('news-abcdef');

    // A name already at the length cap must still fit once a suffix is added,
    // otherwise the retry that resolves a collision would itself be rejected.
    const long = slugCandidate('x'.repeat(80), 1, 'abcdef');
    expect(long.length).toBeLessThanOrEqual(SLUG_MAX_LENGTH);
    expect(long).toMatch(/^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$/);
  });
});

describe('channel cursors', () => {
  it('round-trips a cursor', () => {
    const cursor = { k: '12', id: '2f1c4f9e-6f1a-4a3b-8c2d-9e0f1a2b3c4d' };
    expect(decodeCursor(encodeCursor(cursor))).toEqual(cursor);
  });

  it('refuses anything malformed instead of guessing', () => {
    // A cursor is attacker-controlled input that ends up in a keyset
    // comparison, so each of these must be rejected rather than reaching
    // Postgres as an invalid uuid or timestamp literal (a 500, not a 400).
    expect(decodeCursor(undefined)).toBeNull();
    expect(decodeCursor('')).toBeNull();
    expect(decodeCursor('!!!!')).toBeNull();
    expect(decodeCursor(Buffer.from('not json', 'utf8').toString('base64url'))).toBeNull();

    const noId = Buffer.from(JSON.stringify({ k: '1' }), 'utf8').toString('base64url');
    expect(decodeCursor(noId)).toBeNull();

    const badId = Buffer.from(JSON.stringify({ k: '1', id: 'not-a-uuid' }), 'utf8').toString('base64url');
    expect(decodeCursor(badId)).toBeNull();

    // Oversized input is refused before it is decoded.
    expect(decodeCursor('a'.repeat(513))).toBeNull();
  });

  it('clamps page size into the configured range', () => {
    expect(parsePageSize(undefined, 30, 100)).toBe(30);
    expect(parsePageSize('10', 30, 100)).toBe(10);
    expect(parsePageSize('500', 30, 100)).toBe(100);
    expect(parsePageSize('0', 30, 100)).toBe(30);
    expect(parsePageSize('-5', 30, 100)).toBe(30);
    expect(parsePageSize('abc', 30, 100)).toBe(30);
    expect(parsePageSize('1e9', 30, 100)).toBe(1);
  });

  it('recognises only real uuids', () => {
    expect(isUuid('2f1c4f9e-6f1a-4a3b-8c2d-9e0f1a2b3c4d')).toBe(true);
    expect(isUuid('1; DROP TABLE channels')).toBe(false);
    expect(isUuid('')).toBe(false);
  });
});

// ── Categories ──────────────────────────────────────────────────────────

describe('GET /api/v1/channels/categories', () => {
  it('returns the seeded categories, so categories need no deploy to add', async () => {
    const session = await registered(PHONE_A);
    const res = await request(app)
      .get('/api/v1/channels/categories')
      .set(authed(session.accessToken));

    expect(res.status).toBe(200);
    const slugs = (res.body.categories as { slug: string }[]).map((c) => c.slug);
    expect(slugs).toContain('technology');
    expect(slugs).toContain('programming');
    expect(slugs).toContain('islamic');
  });

  it('refuses an unauthenticated caller', async () => {
    // Anonymous access is deliberate: no endpoint here is public (§38).
    const res = await request(app).get('/api/v1/channels/categories');
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('missing_token');
  });
});

// ── Creation ────────────────────────────────────────────────────────────

describe('POST /api/v1/channels', () => {
  it('creates a channel, makes the caller its owner, and derives a link', async () => {
    const session = await registered(PHONE_A);
    const channel = await createChannel(session, { name: 'ClearView Updates' });

    expect(channel.slug).toBe('clearview-updates');
    expect(channel.viewerRole).toBe('owner');
    expect(channel.followerCount).toBe(0);
    expect(channel.status).toBe('active');
    expect(channel.shareLink).toContain('clearview-updates');

    // A deep link rather than an https URL: public channel pages are an open
    // product decision, and a share link that 404s is worse than an app link.
    expect(channel.shareLink.startsWith('clearview://')).toBe(true);

    // The owner row is written in the same transaction as the channel.
    const admins = await pglite.query<{ role: string }>(
      'SELECT role FROM channel_admins WHERE channel_id = $1',
      [channel.id]
    );
    expect(admins.rows).toHaveLength(1);
    expect(admins.rows[0]?.role).toBe('owner');
  });

  it('rejects an unknown category and a bad country code', async () => {
    const session = await registered(PHONE_A);

    const badCategory = await request(app)
      .post('/api/v1/channels')
      .set(authed(session.accessToken))
      .send({ name: 'Valid', categorySlug: 'not-a-category' });
    expect(badCategory.status).toBe(400);
    expect(badCategory.body.error).toBe('invalid_category');

    const badCountry = await request(app)
      .post('/api/v1/channels')
      .set(authed(session.accessToken))
      .send({ name: 'Valid', countryCode: 'PAK' });
    expect(badCountry.status).toBe(400);
  });

  it('rejects a name that is too short or too long', async () => {
    const session = await registered(PHONE_A);

    const tooShort = await request(app)
      .post('/api/v1/channels')
      .set(authed(session.accessToken))
      .send({ name: 'x' });
    expect(tooShort.status).toBe(400);
    expect(tooShort.body.error).toBe('invalid_request');

    const tooLong = await request(app)
      .post('/api/v1/channels')
      .set(authed(session.accessToken))
      .send({ name: 'x'.repeat(env.MAX_CHANNEL_NAME_LENGTH + 1) });
    expect(tooLong.status).toBe(400);
  });

  it('gives two channels with the same name different slugs', async () => {
    const a = await registered(PHONE_A);
    const b = await registered(PHONE_B);

    const first = await createChannel(a, { name: 'Daily News' });
    const second = await createChannel(b, { name: 'Daily News' });

    // A collision must resolve to a second valid link, not a 500 from the
    // unique index.
    expect(first.slug).not.toBe(second.slug);
    expect(second.slug.startsWith('daily-news')).toBe(true);
  });

  it('enforces MAX_CHANNELS_PER_USER server-side', async () => {
    const session = await registered(PHONE_A);

    for (let i = 0; i < env.MAX_CHANNELS_PER_USER; i += 1) {
      await createChannel(session, { name: `Channel ${i}` });
    }

    const over = await request(app)
      .post('/api/v1/channels')
      .set(authed(session.accessToken))
      .send({ name: 'One too many' });

    expect(over.status).toBe(403);
    expect(over.body.error).toBe('channel_limit_reached');
  });
});

// ── Following, muting, blocking ─────────────────────────────────────────

describe('follow / unfollow / mute / block', () => {
  it('follows, is idempotent, and keeps the counter honest', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Follow Me' });

    const first = await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(follower.accessToken));
    expect(first.status).toBe(200);
    expect(first.body).toEqual({ following: true, followerCount: 1 });

    // Following twice is not an error and must not double-count: the counter
    // is incremented only when a row was actually inserted.
    const again = await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(follower.accessToken));
    expect(again.status).toBe(200);
    expect(again.body.followerCount).toBe(1);
  });

  it('refuses to let an owner follow their own channel', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Mine' });

    const res = await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('cannot_follow_own_channel');
  });

  it('unfollows idempotently and never drives the counter negative', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Fickle' });

    await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(follower.accessToken));

    const first = await request(app)
      .delete(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(follower.accessToken));
    expect(first.body).toEqual({ following: false, followerCount: 0 });

    const second = await request(app)
      .delete(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(follower.accessToken));
    expect(second.status).toBe(200);
    expect(second.body.followerCount).toBe(0);
  });

  it('will not let a suspended channel gain followers', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Suspended' });

    await pglite.query(`UPDATE channels SET status = 'suspended' WHERE id = $1`, [channel.id]);

    const res = await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(follower.accessToken));

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('channel_unavailable');
  });

  it('mutes and unmutes only an existing follow', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Quiet Please' });

    const beforeFollowing = await request(app)
      .put(`/api/v1/channels/${channel.id}/notifications`)
      .set(authed(follower.accessToken))
      .send({ enabled: false });
    expect(beforeFollowing.status).toBe(404);
    expect(beforeFollowing.body.error).toBe('not_following');

    await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(follower.accessToken));

    const muted = await request(app)
      .put(`/api/v1/channels/${channel.id}/notifications`)
      .set(authed(follower.accessToken))
      .send({ enabled: false });
    expect(muted.status).toBe(200);
    expect(muted.body).toEqual({ notificationsEnabled: false });

    // Muting must not quietly create a follow row either: the follow count is
    // the one number the whole platform ranks on.
    const counted = await pglite.query<{ follower_count: number }>(
      'SELECT follower_count FROM channels WHERE id = $1',
      [channel.id]
    );
    expect(counted.rows[0]?.follower_count).toBe(1);
  });

  it('blocking ends the follow, hides the channel, and survives unblocking', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Blocked Soon' });

    await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(follower.accessToken));

    const blocked = await request(app)
      .post(`/api/v1/channels/${channel.id}/block`)
      .set(authed(follower.accessToken));
    expect(blocked.status).toBe(200);

    // The follow is dropped in the same transaction, so a blocked channel
    // cannot still be counted as followed.
    const following = await request(app)
      .get('/api/v1/channels/following')
      .set(authed(follower.accessToken));
    expect(following.body.items).toHaveLength(0);

    const discovered = await request(app)
      .get('/api/v1/discover/channels')
      .set(authed(follower.accessToken));
    expect(discovered.body.items).toHaveLength(0);

    const detail = await request(app)
      .get(`/api/v1/channels/${channel.id}`)
      .set(authed(follower.accessToken));
    expect(detail.body.channel.isBlocked).toBe(true);
    expect(detail.body.channel.followerCount).toBe(0);

    // Refollowing while blocked is refused rather than silently succeeding.
    const refollow = await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(follower.accessToken));
    expect(refollow.status).toBe(403);
    expect(refollow.body.error).toBe('channel_blocked');

    const unblocked = await request(app)
      .delete(`/api/v1/channels/${channel.id}/block`)
      .set(authed(follower.accessToken));
    expect(unblocked.status).toBe(200);

    // Unblocking does not resurrect the follow: ending it was the user's
    // choice, and re-following is theirs to make.
    const afterUnblock = await request(app)
      .get(`/api/v1/channels/${channel.id}`)
      .set(authed(follower.accessToken));
    expect(afterUnblock.body.channel.isBlocked).toBe(false);
    expect(afterUnblock.body.channel.isFollowing).toBe(false);
  });
});

// ── Editing ─────────────────────────────────────────────────────────────

describe('PATCH /api/v1/channels/:id', () => {
  it('lets the owner edit, and pins the slug so shared links keep working', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Old Name', description: 'Before' });

    const res = await request(app)
      .patch(`/api/v1/channels/${channel.id}`)
      .set(authed(owner.accessToken))
      .send({ name: 'New Name', categorySlug: 'technology' });

    expect(res.status).toBe(200);
    expect(res.body.channel.name).toBe('New Name');
    expect(res.body.channel.categorySlug).toBe('technology');
    // The description was omitted, so it must be left alone rather than nulled.
    expect(res.body.channel.description).toBe('Before');
    // Renaming must not break a link someone already shared.
    expect(res.body.channel.slug).toBe(channel.slug);
  });

  it('distinguishes an omitted field from an explicit null', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Has Description', description: 'Body' });

    const cleared = await request(app)
      .patch(`/api/v1/channels/${channel.id}`)
      .set(authed(owner.accessToken))
      .send({ description: null });

    expect(cleared.status).toBe(200);
    expect(cleared.body.channel.description).toBeNull();
    expect(cleared.body.channel.name).toBe('Has Description');
  });

  it('refuses a non-owner, including a follower', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Not Yours' });

    await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(follower.accessToken));

    const res = await request(app)
      .patch(`/api/v1/channels/${channel.id}`)
      .set(authed(follower.accessToken))
      .send({ name: 'Hijacked' });

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('channel_forbidden');

    const unchanged = await pglite.query<{ name: string }>(
      'SELECT name FROM channels WHERE id = $1',
      [channel.id]
    );
    expect(unchanged.rows[0]?.name).toBe('Not Yours');
  });

  it('rejects a patch that changes nothing', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'No-op' });

    const res = await request(app)
      .patch(`/api/v1/channels/${channel.id}`)
      .set(authed(owner.accessToken))
      .send({});

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('empty_update');
  });
});

// ── Discovery ───────────────────────────────────────────────────────────

describe('GET /api/v1/discover/channels', () => {
  it('excludes suspended, soft-deleted and blocked channels', async () => {
    const owner = await registered(PHONE_A);
    const viewer = await registered(PHONE_B);

    const visible = await createChannel(owner, { name: 'Visible One' });
    const suspended = await createChannel(owner, { name: 'Suspended One' });
    const deleted = await createChannel(owner, { name: 'Deleted One' });

    await pglite.query(`UPDATE channels SET status = 'suspended' WHERE id = $1`, [suspended.id]);
    await pglite.query(`UPDATE channels SET deleted_at = now() WHERE id = $1`, [deleted.id]);

    const res = await request(app)
      .get('/api/v1/discover/channels')
      .set(authed(viewer.accessToken));

    expect(res.status).toBe(200);
    const ids = (res.body.items as { id: string }[]).map((c) => c.id);
    expect(ids).toEqual([visible.id]);
  });

  it('filters by search term, category and country', async () => {
    const owner = await registered(PHONE_A);
    const viewer = await registered(PHONE_B);

    await createChannel(owner, {
      name: 'Karachi Runners',
      categorySlug: 'sports',
      countryCode: 'pk',
    });
    await createChannel(owner, { name: 'Lahore Coders', categorySlug: 'programming' });

    const byName = await request(app)
      .get('/api/v1/discover/channels?q=runners')
      .set(authed(viewer.accessToken));
    expect((byName.body.items as unknown[]).length).toBe(1);

    const byCategory = await request(app)
      .get('/api/v1/discover/channels?category=programming')
      .set(authed(viewer.accessToken));
    expect((byCategory.body.items as { name: string }[])[0]?.name).toBe('Lahore Coders');

    // The country code is normalised on the way in, so a lower-case value
    // finds a channel stored upper-case.
    const byCountry = await request(app)
      .get('/api/v1/discover/channels?country=PK')
      .set(authed(viewer.accessToken));
    expect((byCountry.body.items as { name: string }[])[0]?.name).toBe('Karachi Runners');
  });

  it('treats % in a search term as a literal, not a wildcard', async () => {
    const owner = await registered(PHONE_A);
    const viewer = await registered(PHONE_B);

    await createChannel(owner, { name: 'Ordinary Channel' });

    const res = await request(app)
      .get('/api/v1/discover/channels?q=%25')
      .set(authed(viewer.accessToken));

    // Unescaped, '%' would match every row here — the LIKE-injection case.
    expect(res.body.items).toHaveLength(0);
  });

  it('ranks by popularity and pages without repeating or skipping a row', async () => {
    const owner = await registered(PHONE_A);
    const viewer = await registered(PHONE_B);

    const alpha = await createChannel(owner, { name: 'Alpha' });
    const beta = await createChannel(owner, { name: 'Beta' });
    const gamma = await createChannel(owner, { name: 'Gamma' });

    // Popularity, descending.
    await pglite.query('UPDATE channels SET follower_count = 30 WHERE id = $1', [alpha.id]);
    await pglite.query('UPDATE channels SET follower_count = 20 WHERE id = $1', [beta.id]);
    await pglite.query('UPDATE channels SET follower_count = 10 WHERE id = $1', [gamma.id]);

    const page1 = await request(app)
      .get('/api/v1/discover/channels?limit=2')
      .set(authed(viewer.accessToken));

    expect((page1.body.items as { name: string }[]).map((c) => c.name)).toEqual(['Alpha', 'Beta']);
    expect(page1.body.nextCursor).toEqual(expect.any(String));

    const page2 = await request(app)
      .get(`/api/v1/discover/channels?limit=2&cursor=${encodeURIComponent(page1.body.nextCursor)}`)
      .set(authed(viewer.accessToken));

    expect((page2.body.items as { name: string }[]).map((c) => c.name)).toEqual(['Gamma']);
    expect(page2.body.nextCursor).toBeNull();
  });

  it('rejects a malformed cursor and an unknown sort instead of guessing', async () => {
    const viewer = await registered(PHONE_B);

    const badCursor = await request(app)
      .get('/api/v1/discover/channels?cursor=not-a-cursor')
      .set(authed(viewer.accessToken));
    expect(badCursor.status).toBe(400);
    expect(badCursor.body.error).toBe('invalid_cursor');

    const badSort = await request(app)
      .get('/api/v1/discover/channels?sort=trending')
      .set(authed(viewer.accessToken));
    expect(badSort.status).toBe(400);
  });

  it('orders by recency for the new sort', async () => {
    const owner = await registered(PHONE_A);
    const viewer = await registered(PHONE_B);

    const older = await createChannel(owner, { name: 'Older' });
    const newer = await createChannel(owner, { name: 'Newer' });

    await pglite.query(`UPDATE channels SET created_at = now() - interval '2 days' WHERE id = $1`, [
      older.id,
    ]);

    const res = await request(app)
      .get('/api/v1/discover/channels?sort=new')
      .set(authed(viewer.accessToken));

    expect((res.body.items as { name: string }[]).map((c) => c.name)).toEqual(['Newer', 'Older']);
  });
});

// ── Privacy and authorization surfaces ──────────────────────────────────

describe('payload and authorization hygiene', () => {
  it('never puts owner or follower identity in a channel payload', async () => {
    const owner = await registered(PHONE_A, 'Owners Real Name', 'owner-private@example.test');
    const viewer = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Quiet Channel' });

    const res = await request(app)
      .get(`/api/v1/channels/${channel.id}`)
      .set(authed(viewer.accessToken));

    const payload = JSON.stringify(res.body);
    expect(res.status).toBe(200);

    // §38: another user's email, phone and identity must not be reachable
    // through any channel surface.
    expect(payload).not.toContain('owner-private@example.test');
    expect(payload).not.toContain('Owners Real Name');
    expect(payload).not.toContain('phone');
    expect(res.body.channel).not.toHaveProperty('ownerId');
    expect(res.body.channel).not.toHaveProperty('owner_id');
    expect(res.body.channel).not.toHaveProperty('phoneHash');
  });

  it('answers 404 for an unknown channel and for a malformed id', async () => {
    const viewer = await registered(PHONE_B);

    // A non-uuid must not reach Postgres and surface as a 500.
    const malformed = await request(app)
      .get('/api/v1/channels/not-a-uuid')
      .set(authed(viewer.accessToken));
    expect(malformed.status).toBe(404);
    expect(malformed.body.error).toBe('channel_not_found');

    const missing = await request(app)
      .get('/api/v1/channels/2f1c4f9e-6f1a-4a3b-8c2d-9e0f1a2b3c4d')
      .set(authed(viewer.accessToken));
    expect(missing.status).toBe(404);
  });

  it('rejects a banned account at the channel surface (§19)', async () => {
    const owner = await registered(PHONE_A);
    const banned = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Any Channel' });

    await pglite.query(`UPDATE users SET status = 'banned' WHERE id = $1`, [banned.user.id]);

    // The ban is re-read per request, so it applies to a token that was
    // already issued.
    const read = await request(app)
      .get('/api/v1/discover/channels')
      .set(authed(banned.accessToken));
    expect(read.status).toBe(403);
    expect(read.body.error).toBe('account_banned');

    const write = await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(banned.accessToken));
    expect(write.status).toBe(403);
    expect(write.body.error).toBe('account_banned');
  });

  it('rejects a garbage bearer token', async () => {
    const res = await request(app)
      .get('/api/v1/discover/channels')
      .set(authed('not.a.real.token'));
    expect(res.status).toBe(401);
  });

  it('lets an owner see a suspended channel they own, but not in discovery', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Under Review' });

    await pglite.query(`UPDATE channels SET status = 'suspended' WHERE id = $1`, [channel.id]);

    const mine = await request(app)
      .get('/api/v1/channels/mine')
      .set(authed(owner.accessToken));
    expect(mine.status).toBe(200);
    expect((mine.body.channels as { status: string }[])[0]?.status).toBe('suspended');

    const discover = await request(app)
      .get('/api/v1/discover/channels')
      .set(authed(owner.accessToken));
    expect(discover.body.items).toHaveLength(0);
  });
});

// ── Unread state ────────────────────────────────────────────────────────

describe('unread state', () => {
  it('flags a channel with activity since the last read, and clears it', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Busy Channel' });

    await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(follower.accessToken));

    // M2 has no posts yet, so activity is simulated directly. The comparison
    // against last_read_at is what M3 will rely on.
    await pglite.query(`UPDATE channels SET last_post_at = now() WHERE id = $1`, [channel.id]);

    const unread = await request(app)
      .get('/api/v1/channels/following')
      .set(authed(follower.accessToken));
    expect(unread.body.items[0].hasUnread).toBe(true);

    await request(app)
      .post(`/api/v1/channels/${channel.id}/read`)
      .set(authed(follower.accessToken));

    const read = await request(app)
      .get('/api/v1/channels/following')
      .set(authed(follower.accessToken));
    expect(read.body.items[0].hasUnread).toBe(false);
  });
});

// ── Share links (§6) ────────────────────────────────────────────────────

/** Pull the slug back out of a link the API produced. */
const slugOf = (shareLink: string) => shareLink.split('/').pop() ?? '';

describe('channel share links', () => {
  it('resolves the slug out of the link it hands the client', async () => {
    const owner = await registered(PHONE_A);
    const viewer = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Shareable Channel' });

    // The slug is taken from the link the API itself returned rather than
    // hardcoded, so if the link format and the lookup route ever drift apart
    // this fails instead of quietly testing a string nobody sends.
    const slug = slugOf(channel.shareLink);
    expect(slug).toBe('shareable-channel');

    const res = await request(app)
      .get(`/api/v1/channels/by-slug/${slug}`)
      .set(authed(viewer.accessToken));

    expect(res.status).toBe(200);
    expect(res.body.channel.id).toBe(channel.id);
  });

  it('answers a slug lookup with exactly what the id lookup answers', async () => {
    // A shared link and an in-app tap land on the same screen. A thinner
    // payload for one of them would be a bug no client test could catch,
    // because each response is individually valid.
    const owner = await registered(PHONE_A);
    const viewer = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Same Shape' });

    await request(app)
      .post(`/api/v1/channels/${channel.id}/follow`)
      .set(authed(viewer.accessToken));

    const byId = await request(app)
      .get(`/api/v1/channels/${channel.id}`)
      .set(authed(viewer.accessToken));
    const bySlug = await request(app)
      .get(`/api/v1/channels/by-slug/${channel.slug}`)
      .set(authed(viewer.accessToken));

    expect(bySlug.body).toEqual(byId.body);
  });

  it('resolves a hand-typed link regardless of case', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Mixed Case Link' });

    const res = await request(app)
      .get(`/api/v1/channels/by-slug/${channel.slug.toUpperCase()}`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(200);
    expect(res.body.channel.id).toBe(channel.id);
  });

  it('treats a dead or malformed slug as a 404, never a 400', async () => {
    // To the person holding a broken link these are one outcome. A 400 would
    // also tell a prober whether a slug merely does not exist or is not a
    // slug at all, which is information they have no use for.
    const viewer = await registered(PHONE_A);

    for (const slug of [
      'no-such-channel-here',
      'UPPER_UNDERSCORE',
      '-leading-dash',
      'trailing-dash-',
      'a'.repeat(200),
      ' ',
    ]) {
      const res = await request(app)
        .get(`/api/v1/channels/by-slug/${encodeURIComponent(slug)}`)
        .set(authed(viewer.accessToken));

      expect(res.status, `slug: ${JSON.stringify(slug)}`).toBe(404);
      expect(res.body.error, `slug: ${JSON.stringify(slug)}`).toBe('channel_not_found');
    }
  });

  it('still resolves a suspended channel, so the link can explain itself', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Under Review Link' });

    await pglite.query(`UPDATE channels SET status = 'suspended' WHERE id = $1`, [channel.id]);

    const res = await request(app)
      .get(`/api/v1/channels/by-slug/${channel.slug}`)
      .set(authed(owner.accessToken));

    // A 404 here would make moderation look like a broken link, which is the
    // one reading the owner must not be left with.
    expect(res.status).toBe(200);
    expect(res.body.channel.status).toBe('suspended');
  });

  it('does not resolve a deleted channel', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Deleted Link' });

    await pglite.query(`UPDATE channels SET deleted_at = now() WHERE id = $1`, [channel.id]);

    const res = await request(app)
      .get(`/api/v1/channels/by-slug/${channel.slug}`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(404);
  });

  it('refuses a slug lookup without a session', async () => {
    // §39. Every Good Post endpoint requires a session; a share link is not an
    // anonymous read surface just because it is shareable.
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Closed Link' });

    const res = await request(app).get(`/api/v1/channels/by-slug/${channel.slug}`);
    expect(res.status).toBe(401);
  });
});
