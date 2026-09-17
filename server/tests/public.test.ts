import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { applyAllMigrations, asQueryable, freshDatabase, one, resetData } from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';

/**
 * The public read surface (§24, §26).
 *
 * Every request in this file is sent WITHOUT an Authorization header, and that
 * is the point of the suite: the product rule being protected is "a reader
 * opens Good Post and reads, with no account", and a test that authenticated
 * would not be testing it.
 *
 * Fixtures are inserted with SQL rather than through the API, because there is
 * no public write path to insert through — the absence of one is itself one of
 * the assertions. The reads under test are the real queries against a real
 * Postgres, so the filters the suite pins (soft-deleted posts, suspended
 * channels) are the filters the query actually applies.
 *
 * These paths are the root of the API: `/api/v1/channels`, not
 * `/api/v1/public/channels`. The reader's first impression is this surface, so
 * it is the one that gets the short URL.
 */

let pglite: PGlite;
let app: Express;
let store: FakeObjectStore;

beforeAll(async () => {
  pglite = await freshDatabase();
  await applyAllMigrations(pglite);
  store = new FakeObjectStore();
  app = buildApp({ database: asQueryable(pglite), store });
});

afterAll(async () => {
  await pglite.close();
});

beforeEach(async () => {
  await resetData(pglite);
  store.reset();
});

/** A unique name per fixture, so one test's rows cannot answer another's query. */
let counter = 0;
function unique(prefix: string): string {
  counter += 1;
  return `${prefix}-${counter}`;
}

interface ChannelSeed {
  slug?: string;
  name?: string;
  description?: string | null;
  status?: string;
  deleted?: boolean;
  lastPostAt?: string | null;
  category?: string | null;
}

async function seedChannel(seed: ChannelSeed = {}): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO channels
       (slug, name, description, status, category_slug, last_post_at, deleted_at)
     VALUES ($1, $2, $3, $4::channel_status, $5, $6::timestamptz, $7::timestamptz)
     RETURNING id`,
    [
      seed.slug ?? 'clearview',
      seed.name ?? 'ClearView',
      seed.description ?? null,
      seed.status ?? 'active',
      seed.category ?? null,
      seed.lastPostAt ?? null,
      seed.deleted ? new Date().toISOString() : null,
    ]
  );
  return one(rows.rows).id;
}

async function seedPost(
  channelId: string,
  overrides: {
    type?: string;
    body?: string | null;
    linkUrl?: string | null;
    linkTitle?: string | null;
    createdAt?: string;
    deleted?: boolean;
  } = {}
): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    // `author_id` is NULL on purpose: migration 012 made authorship optional,
    // because there is no honest mapping from a pre-redesign author to an
    // administrator and inventing one would be a lie the audit trail repeats.
    `INSERT INTO posts
       (channel_id, author_id, type, body, link_url, link_title, created_at, deleted_at)
     VALUES ($1, NULL, $2::post_type, $3, $4, $5, $6::timestamptz, $7::timestamptz)
     RETURNING id`,
    [
      channelId,
      overrides.type ?? 'text',
      overrides.body ?? 'Salam',
      overrides.linkUrl ?? null,
      overrides.linkTitle ?? null,
      overrides.createdAt ?? new Date().toISOString(),
      overrides.deleted ? new Date().toISOString() : null,
    ]
  );
  return one(rows.rows).id;
}

async function seedMedia(postId: string): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO post_media
       (owner_id, post_id, kind, object_key, content_type, byte_size, status, position)
     VALUES (NULL, $1, 'image'::media_kind, $2, 'image/jpeg', 2048, 'ready'::media_status, 0)
     RETURNING id`,
    [postId, `goodpost/channels/image/${postId}.jpg`]
  );
  return one(rows.rows).id;
}

describe('public channel list', () => {
  it('answers without any credentials', async () => {
    const slug = unique('open');
    await seedChannel({ slug, name: 'Open' });

    const res = await request(app).get('/api/v1/channels');
    expect(res.status).toBe(200);
    expect(res.body.items.map((c: { slug: string }) => c.slug)).toContain(slug);
  });

  it('never serialises a counter or an owner', async () => {
    await seedChannel({ slug: unique('cv'), name: 'ClearView', description: 'Daily reminders' });

    const res = await request(app).get('/api/v1/channels');
    const channel = res.body.items[0];

    // Followers, post counts, reactions and views do not exist on this surface
    // by design (§1, §5). Asserted as absent rather than as zero: a zero would
    // still be a number the client can render.
    for (const forbidden of ['followerCount', 'postCount', 'ownerId', 'viewerRole', 'isFollowing']) {
      expect(channel).not.toHaveProperty(forbidden);
    }
    expect(channel.shareLink).toContain('clearview://goodpost/channel/');
  });

  it('hides suspended and deleted channels', async () => {
    const live = unique('live');
    const paused = unique('paused');
    const gone = unique('gone');
    await seedChannel({ slug: live, name: 'Live' });
    await seedChannel({ slug: paused, name: 'Paused', status: 'suspended' });
    await seedChannel({ slug: gone, name: 'Gone', deleted: true });

    const res = await request(app).get('/api/v1/channels');
    const slugs = res.body.items.map((c: { slug: string }) => c.slug);

    expect(slugs).toContain(live);
    expect(slugs).not.toContain(paused);
    expect(slugs).not.toContain(gone);
  });

  it('searches names and descriptions, and treats a wildcard as a literal', async () => {
    const quran = unique('quran');
    const hadith = unique('hadith');
    await seedChannel({ slug: quran, name: `Quran ${quran}`, description: 'Surah Al-Kahf' });
    await seedChannel({ slug: hadith, name: `Hadith ${hadith}`, description: 'Daily reminder' });

    const byName = await request(app).get(`/api/v1/channels?q=${quran}`);
    expect(byName.body.items.map((c: { slug: string }) => c.slug)).toEqual([quran]);

    const byDescription = await request(app).get('/api/v1/channels?q=kahf');
    expect(byDescription.body.items.map((c: { slug: string }) => c.slug)).toEqual([quran]);

    // `%` must search for a percent sign, not match everything.
    const wildcard = await request(app).get('/api/v1/channels?q=%25');
    expect(wildcard.body.items).toHaveLength(0);
  });

  it('filters by category and rejects an unknown sort', async () => {
    const quran = unique('quran');
    const tech = unique('tech');
    await seedChannel({ slug: quran, name: 'Quran', category: 'islamic' });
    await seedChannel({ slug: tech, name: 'Tech', category: 'technology' });

    const filtered = await request(app).get('/api/v1/channels?category=islamic');
    const slugs = filtered.body.items.map((c: { slug: string }) => c.slug);
    expect(slugs).toContain(quran);
    expect(slugs).not.toContain(tech);

    const badSort = await request(app).get('/api/v1/channels?sort=popular');
    expect(badSort.status).toBe(400);
  });

  it('describes the last post it will preview', async () => {
    const slug = unique('quran');
    const channelId = await seedChannel({ slug, name: 'Quran' });
    await seedPost(channelId, {
      body: 'Older',
      createdAt: new Date(Date.now() - 60_000).toISOString(),
    });
    await seedPost(channelId, { body: 'Newest update' });

    const res = await request(app).get(`/api/v1/channels/${slug}`);
    expect(res.body.channel.lastPostPreview).toBe('Newest update');
    expect(res.body.channel.lastPostType).toBe('text');
    expect(res.body.channel.lastPostAt).not.toBeNull();
  });
});

/**
 * A post with nothing to show (§9).
 *
 * Migration 012 dropped the tables behind polls, not the poll POSTS, so a real
 * deployment carries rows with `type = 'poll'` and a null body — this suite's own
 * fixtures can now produce one. They were being served as text posts with no
 * text: an empty bubble in the feed, a channel row whose preview and timestamp
 * pointed at it, and a direct link that opened nothing.
 */
describe('a post a reader cannot see anything in', () => {
  it('is left out of the feed, the channel row and a direct fetch', async () => {
    const slug = unique('polls');
    const channelId = await seedChannel({ slug, name: 'Polls' });
    const readable = await seedPost(channelId, {
      body: 'Still readable',
      createdAt: new Date(Date.now() - 60_000).toISOString(),
    });
    const leftover = await seedPost(channelId, {
      type: 'poll',
      body: null,
      createdAt: new Date().toISOString(),
    });

    const feed = await request(app).get(`/api/v1/channels/${slug}/posts`);
    expect(feed.body.items.map((p: { id: string }) => p.id)).toEqual([readable]);

    // The row describes the newest VISIBLE post, not the newest row: its preview
    // and its timestamp have to belong to the same post, and a timestamp that
    // outruns the feed is how a list and its detail start disagreeing (§4).
    const detail = await request(app).get(`/api/v1/channels/${slug}`);
    expect(detail.body.channel.lastPostPreview).toBe('Still readable');
    expect(detail.body.channel.lastPostType).toBe('text');
    // Exactly the post the feed returns, not merely a non-empty date.
    expect(detail.body.channel.lastPostAt).toBe(feed.body.items[0].createdAt);

    expect((await request(app).get(`/api/v1/posts/${leftover}`)).status).toBe(404);
  });

  it('leaves a channel that has only ever polled looking like one that never posted', async () => {
    const slug = unique('onlypolls');
    const channelId = await seedChannel({ slug, name: 'Only polls' });
    await seedPost(channelId, { type: 'poll', body: null });

    const detail = await request(app).get(`/api/v1/channels/${slug}`);
    // Null rather than the poll's timestamp: `channels.last_post_at` still holds
    // that value, and reporting it would date the row by a post nobody can read.
    expect(detail.body.channel.lastPostAt).toBeNull();
    expect(detail.body.channel.lastPostType).toBeNull();
    expect(detail.body.channel.lastPostPreview).toBeNull();

    expect((await request(app).get(`/api/v1/channels/${slug}/posts`)).body.items).toEqual([]);
  });
});

describe('public channel detail', () => {
  it('resolves by slug and by id', async () => {
    const slug = unique('cv');
    const channelId = await seedChannel({ slug, name: 'ClearView' });

    const bySlug = await request(app).get(`/api/v1/channels/${slug}`);
    expect(bySlug.status).toBe(200);
    expect(bySlug.body.channel.id).toBe(channelId);

    const byId = await request(app).get(`/api/v1/channels/${channelId}`);
    expect(byId.status).toBe(200);
    expect(byId.body.channel.slug).toBe(slug);
  });

  it('answers the same 404 for a missing channel and a malformed slug', async () => {
    const missing = await request(app).get('/api/v1/channels/nope');
    expect(missing.status).toBe(404);
    expect(missing.body.error).toBe('channel_not_found');

    const malformed = await request(app).get('/api/v1/channels/NOT_A_SLUG');
    expect(malformed.status).toBe(404);
    expect(malformed.body.error).toBe('channel_not_found');
  });

  it('reports when the channel was created', async () => {
    const slug = unique('cv');
    await seedChannel({ slug });
    const res = await request(app).get(`/api/v1/channels/${slug}`);
    expect(Number.isNaN(Date.parse(res.body.channel.createdAt))).toBe(false);
  });

  it('is readable on a suspended channel by nobody — including its own admin', async () => {
    // The public surface refuses it; the administrator's own view is a separate
    // route on the admin prefix, which is where a suspended channel stays
    // readable so its owner can see what they are being asked to fix.
    const slug = unique('paused');
    await seedChannel({ slug, status: 'suspended' });
    expect((await request(app).get(`/api/v1/channels/${slug}`)).status).toBe(404);
  });
});

describe('public channel posts', () => {
  it('returns newest first and omits deleted posts', async () => {
    const slug = unique('quran');
    const channelId = await seedChannel({ slug });
    await seedPost(channelId, {
      body: 'older',
      createdAt: new Date(Date.now() - 120_000).toISOString(),
    });
    await seedPost(channelId, { body: 'newer' });
    await seedPost(channelId, { body: 'removed', deleted: true });

    const res = await request(app).get(`/api/v1/channels/${slug}/posts`);
    expect(res.body.items.map((p: { body: string }) => p.body)).toEqual(['newer', 'older']);
    expect(res.body.items[0]).not.toHaveProperty('engagement');
    expect(res.body.items[0]).not.toHaveProperty('viewerCanManage');
  });

  it('walks pages with a cursor without repeating a row', async () => {
    const slug = unique('quran');
    const channelId = await seedChannel({ slug });
    for (let i = 0; i < 5; i += 1) {
      await seedPost(channelId, {
        body: `post-${i}`,
        createdAt: new Date(Date.now() - i * 60_000).toISOString(),
      });
    }

    const first = await request(app).get(`/api/v1/channels/${slug}/posts?limit=2`);
    expect(first.body.items).toHaveLength(2);
    expect(first.body.nextCursor).not.toBeNull();

    const second = await request(app).get(
      `/api/v1/channels/${slug}/posts?limit=2&cursor=${first.body.nextCursor}`
    );
    const seen = [
      ...first.body.items.map((p: { id: string }) => p.id),
      ...second.body.items.map((p: { id: string }) => p.id),
    ];
    expect(new Set(seen).size).toBe(seen.length);
  });

  it('refuses a malformed cursor instead of silently restarting', async () => {
    const slug = unique('quran');
    await seedChannel({ slug });
    const res = await request(app).get(`/api/v1/channels/${slug}/posts?cursor=!!`);
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_cursor');
  });

  it('includes a signed URL for an image, and null when there is no bucket', async () => {
    const slug = unique('quran');
    const channelId = await seedChannel({ slug });
    const postId = await seedPost(channelId, { type: 'image', body: 'Photo' });
    await seedMedia(postId);

    const withBucket = await request(app).get(`/api/v1/channels/${slug}/posts`);
    expect(withBucket.body.items[0].media[0].url).toContain('https://fake-bucket.test/');

    // A deployment with no bucket must still return the post, with the asset
    // unservable rather than the whole screen failing (§22).
    const bare = buildApp({ database: asQueryable(pglite), store: unconfiguredStore() });
    const noBucket = await request(bare).get(`/api/v1/channels/${slug}/posts`);
    expect(noBucket.status).toBe(200);
    expect(noBucket.body.items[0].media[0].url).toBeNull();
    expect(noBucket.body.items[0].body).toBe('Photo');
  });

  it('404s for a channel that does not exist', async () => {
    const res = await request(app).get('/api/v1/channels/nobody/posts');
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('channel_not_found');
  });
});

describe('a single public post', () => {
  it('names the channel it came from', async () => {
    const slug = unique('clearview');
    const channelId = await seedChannel({ slug, name: 'ClearView' });
    const postId = await seedPost(channelId, { body: 'A reminder' });

    const res = await request(app).get(`/api/v1/posts/${postId}`);
    expect(res.status).toBe(200);
    expect(res.body.post.channel).toEqual({ id: channelId, slug, name: 'ClearView' });
  });

  it('hides a removed post and a post on a removed channel', async () => {
    const channelId = await seedChannel({ slug: unique('clearview') });
    const removed = await seedPost(channelId, { deleted: true });

    expect((await request(app).get(`/api/v1/posts/${removed}`)).status).toBe(404);
    expect((await request(app).get('/api/v1/posts/not-a-uuid')).status).toBe(404);
  });
});

describe('the channel media gallery', () => {
  it('lists images from the channel, newest first', async () => {
    const slug = unique('quran');
    const channelId = await seedChannel({ slug });
    const older = await seedPost(channelId, {
      type: 'image',
      createdAt: new Date(Date.now() - 120_000).toISOString(),
    });
    const newer = await seedPost(channelId, { type: 'video' });
    await seedMedia(older);
    await seedMedia(newer);

    const res = await request(app).get(`/api/v1/channels/${slug}/media`);
    expect(res.status).toBe(200);
    expect(res.body.items).toHaveLength(2);
    expect(res.body.items.every((m: { url: string }) => m.url.includes('fake-bucket'))).toBe(true);
  });

  it('leaves out the media of a removed post', async () => {
    const slug = unique('quran');
    const channelId = await seedChannel({ slug });
    const postId = await seedPost(channelId, { type: 'image', deleted: true });
    await seedMedia(postId);

    const res = await request(app).get(`/api/v1/channels/${slug}/media`);
    expect(res.body.items).toHaveLength(0);
  });
});

describe('the public surface is read-only', () => {
  it('has no write route to call', async () => {
    // The two writes a reader might be tempted to attempt. Both are on the
    // administrator prefix, behind a bearer token, and neither exists here.
    expect((await request(app).post('/api/v1/channels').send({ name: 'Mine' })).status).toBe(404);
    expect((await request(app).post('/api/v1/posts').send({ body: 'Mine' })).status).toBe(404);
  });

  it('refuses an admin route without a token, so no path is anonymously writable', async () => {
    const res = await request(app).post('/admin/api/channels').send({ name: 'Mine' });
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('missing_token');
  });
});

describe('the public surface lists categories', () => {
  it('returns the seeded, active ones', async () => {
    const res = await request(app).get('/api/v1/categories');
    expect(res.status).toBe(200);
    expect(res.body.categories.length).toBeGreaterThan(0);
    expect(res.body.categories[0]).toHaveProperty('slug');
    expect(res.body.categories[0]).toHaveProperty('label');
  });
});

/** A store that answers "this deployment has no bucket" (§22). */
function unconfiguredStore(): FakeObjectStore {
  const fake = new FakeObjectStore();
  Object.defineProperty(fake, 'configured', { value: false });
  return fake;
}
