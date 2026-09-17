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
 * Postgres, so the filters the suite pins (removed posts, suspended channels)
 * are the filters the query actually applies.
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
  lastPostAt?: string | null;
  category?: string | null;
}

async function seedChannel(seed: ChannelSeed = {}): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO channels
       (slug, name, description, status, category_slug, last_post_at)
     VALUES ($1, $2, $3, $4::channel_status, $5, $6::timestamptz)
     RETURNING id`,
    [
      seed.slug ?? 'clearview',
      seed.name ?? 'ClearView',
      seed.description ?? null,
      seed.status ?? 'active',
      seed.category ?? null,
      seed.lastPostAt ?? null,
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
    // `author_id` is NULL on purpose: authorship is optional (`posts.author_id`
    // is nullable), and a post whose author is unknown is still a post.
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

/** A reader, and the fact that they follow a channel (§4). */
async function seedFollow(channelId: string, uid: string): Promise<void> {
  await pglite.query(
    `WITH r AS (
       INSERT INTO readers (firebase_uid) VALUES ($2)
       ON CONFLICT (firebase_uid) DO UPDATE SET last_seen_at = now()
       RETURNING id
     )
     INSERT INTO channel_follows (reader_id, channel_id)
     SELECT id, $1::uuid FROM r
     ON CONFLICT DO NOTHING`,
    [channelId, uid]
  );
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

  it('hides a suspended channel', async () => {
    const live = unique('live');
    const paused = unique('paused');
    await seedChannel({ slug: live, name: 'Live' });
    await seedChannel({ slug: paused, name: 'Paused', status: 'suspended' });

    const res = await request(app).get('/api/v1/channels');
    const slugs = res.body.items.map((c: { slug: string }) => c.slug);

    expect(slugs).toContain(live);
    expect(slugs).not.toContain(paused);
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
 * A post a reader cannot see anything in (§9).
 *
 * An earlier design stored rows with `type = 'poll'` and a null body, which
 * nothing could render, so every read carried a predicate that filtered them
 * out — and a query that forgot one served an empty bubble. The constraint
 * below is what makes such a row unwritable, so the guarantee belongs to the
 * database rather than to each query, which is what these tests assert instead
 * of asserting the filter.
 */
describe('a post a reader cannot see anything in', () => {
  it('cannot be stored: the type is constrained to the four shapes', async () => {
    const channelId = await seedChannel({ slug: unique('shapes') });

    // The `poll` label still exists in the enum — a PostgreSQL type cannot lose
    // one — so the CHECK is the thing standing between it and a row.
    await expect(
      pglite.query(
        `INSERT INTO posts (channel_id, type, body) VALUES ($1, 'poll'::post_type, NULL)`,
        [channelId]
      )
    ).rejects.toThrow(/posts_type_is_renderable/);
  });

  it('leaves a channel with nothing to show describing nothing', async () => {
    const slug = unique('quiet');
    // `channels.last_post_at` is advanced on publish and recomputed by the
    // retention sweep, so it can outlive the post it names once that post is
    // deleted. A row's preview and its timestamp come from the join for exactly
    // this reason, and this fixture is that state: activity recorded, no post.
    await seedChannel({ slug, name: 'Quiet', lastPostAt: new Date().toISOString() });

    const detail = await request(app).get(`/api/v1/channels/${slug}`);
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

/**
 * The two numbers a channel now carries (§9).
 *
 * Both are read on the list screen and on a channel's information page, and
 * both are DB-derived rather than stored twice: the follower count is a COUNT
 * over the follows that already exist, and the view count is the newest post's
 * own column. What is pinned here is that neither can be invented — a channel
 * nobody follows reports zero, and a post nobody has reported reading reports
 * zero rather than a placeholder.
 */
describe('a channel’s follower and view numbers (§9)', () => {
  it('counts the readers who follow it, and nobody else', async () => {
    const followed = unique('counted');
    const lonely = unique('lonely');
    const followedId = await seedChannel({ slug: followed });
    const lonelyId = await seedChannel({ slug: lonely });

    await seedFollow(followedId, 'reader-one');
    await seedFollow(followedId, 'reader-two');
    await seedFollow(lonelyId, 'reader-three');

    const list = await request(app).get('/api/v1/channels?limit=50');
    const bySlug = new Map(
      list.body.items.map((c: { slug: string; followerCount: number }) => [c.slug, c.followerCount])
    );

    expect(bySlug.get(followed)).toBe(2);
    expect(bySlug.get(lonely)).toBe(1);

    const detail = await request(app).get(`/api/v1/channels/${followed}`);
    expect(detail.body.channel.followerCount).toBe(2);
  });

  it('reports the newest post’s views, and zero before anybody has read it', async () => {
    const slug = unique('views');
    const channelId = await seedChannel({ slug });
    await seedPost(channelId, {
      body: 'older',
      createdAt: new Date(Date.now() - 60_000).toISOString(),
    });
    const newest = await seedPost(channelId, { body: 'newest' });

    // A post nobody has reported reading is a real zero, not a missing value.
    const before = await request(app).get(`/api/v1/channels/${slug}`);
    expect(before.body.channel.lastPostViews).toBe(0);

    const reported = await request(app)
      .post(`/api/v1/channels/${slug}/posts/views`)
      .send({ ids: [newest] });
    expect(reported.status, JSON.stringify(reported.body)).toBe(200);
    expect(reported.body.counted).toBe(1);

    const after = await request(app).get(`/api/v1/channels/${slug}`);
    expect(after.body.channel.lastPostViews).toBe(1);

    // The count is on the post payload too, which is what a channel's own feed
    // renders.
    const posts = await request(app).get(`/api/v1/channels/${slug}/posts`);
    const byBody = new Map(
      posts.body.items.map((p: { body: string; views: number }) => [p.body, p.views])
    );
    expect(byBody.get('newest')).toBe(1);
    expect(byBody.get('older')).toBe(0);
  });
});

/**
 * The one write on the reader surface (§9).
 *
 * Its rules are all about scope and about not treating a race as an error, so
 * each case below is one of those: a batch is counted once, a post belonging to
 * another channel is not counted through this one, a post that was removed is
 * not counted at all, and a stale id does not fail the batch it arrived in.
 */
describe('reporting views (§9)', () => {
  it('counts a batch once, and requires no credential to do it', async () => {
    const slug = unique('views');
    const channelId = await seedChannel({ slug });
    const first = await seedPost(channelId, { body: 'one' });
    const second = await seedPost(channelId, { body: 'two' });

    const res = await request(app)
      .post(`/api/v1/channels/${slug}/posts/views`)
      .send({ ids: [first, second] });

    expect(res.status).toBe(200);
    expect(res.body.counted).toBe(2);

    const rows = await pglite.query<{ id: string; view_count: number }>(
      `SELECT id, view_count FROM posts WHERE channel_id = $1 ORDER BY body`,
      [channelId]
    );
    expect(rows.rows.map((r) => r.view_count)).toEqual([1, 1]);
  });

  it('cannot count another channel’s post through this one', async () => {
    const mine = unique('mine');
    const theirs = unique('theirs');
    const myChannel = await seedChannel({ slug: mine });
    const theirChannel = await seedChannel({ slug: theirs });
    const theirPost = await seedPost(theirChannel, { body: 'not mine' });

    const res = await request(app)
      .post(`/api/v1/channels/${mine}/posts/views`)
      .send({ ids: [theirPost] });

    expect(res.status).toBe(200);
    expect(res.body.counted).toBe(0);

    const rows = await pglite.query<{ view_count: number }>(
      `SELECT view_count FROM posts WHERE id = $1`,
      [theirPost]
    );
    expect(rows.rows[0]?.view_count).toBe(0);
    expect(myChannel).not.toBe(theirChannel);
  });

  it('leaves a removed post at zero, and does not fail the batch over it', async () => {
    const slug = unique('views');
    const channelId = await seedChannel({ slug });
    const visible = await seedPost(channelId, { body: 'still here' });
    const removed = await seedPost(channelId, { body: 'gone', deleted: true });

    const res = await request(app)
      .post(`/api/v1/channels/${slug}/posts/views`)
      .send({ ids: [visible, removed, '11111111-1111-4111-8111-111111111111'] });

    // The two it could count are counted; the third is simply not a match.
    expect(res.status).toBe(200);
    expect(res.body.counted).toBe(1);

    const rows = await pglite.query<{ body: string | null; view_count: number }>(
      `SELECT body, view_count FROM posts WHERE channel_id = $1 ORDER BY body`,
      [channelId]
    );
    expect(rows.rows.map((r) => r.view_count)).toEqual([0, 1]);
  });

  it('refuses an empty batch and an id that is not one', async () => {
    const slug = unique('views');
    await seedChannel({ slug });

    const empty = await request(app).post(`/api/v1/channels/${slug}/posts/views`).send({ ids: [] });
    expect(empty.status).toBe(400);

    const junk = await request(app)
      .post(`/api/v1/channels/${slug}/posts/views`)
      .send({ ids: ['not-a-uuid'] });
    expect(junk.status).toBe(400);
  });

  it('404s for a channel that does not exist, like every other channel route', async () => {
    const res = await request(app)
      .post('/api/v1/channels/nobody/posts/views')
      .send({ ids: [await seedPost(await seedChannel({ slug: unique('x') }))] });

    expect(res.status).toBe(404);
    expect(res.body.error).toBe('channel_not_found');
  });
});

/**
 * Search inside ONE channel (§9).
 *
 * The feature a reader actually uses this for is "where did the channel say X"
 * — the answers have to stay inside the channel they asked about, and they have
 * to still be a page of the same feed rather than a second, differently shaped
 * result set the client has to render with different code.
 */
describe('searching a channel’s posts', () => {
  it('returns only the posts that contain the term, newest first', async () => {
    const slug = unique('search');
    const channelId = await seedChannel({ slug });
    await seedPost(channelId, {
      body: 'Zakat is due on savings',
      createdAt: new Date(Date.now() - 180_000).toISOString(),
    });
    await seedPost(channelId, { body: 'Salah times for tonight' });
    await seedPost(channelId, {
      body: 'Reminder about ZAKAT',
      createdAt: new Date(Date.now() - 60_000).toISOString(),
    });

    const res = await request(app).get(`/api/v1/channels/${slug}/posts?q=zakat`);
    expect(res.status).toBe(200);
    expect(res.body.items).toHaveLength(2);
    // Case-insensitive, and the newest match leads — the same order the
    // unfiltered feed uses.
    expect(res.body.items.map((p: { body: string }) => p.body)).toEqual([
      'Reminder about ZAKAT',
      'Zakat is due on savings',
    ]);
  });

  it('never returns another channel’s posts', async () => {
    const mine = unique('mine');
    const theirs = unique('theirs');
    const myChannel = await seedChannel({ slug: mine });
    const theirChannel = await seedChannel({ slug: theirs });
    await seedPost(myChannel, { body: 'qibla direction' });
    await seedPost(theirChannel, { body: 'qibla direction, from the other channel' });

    const res = await request(app).get(`/api/v1/channels/${mine}/posts?q=qibla`);
    expect(res.body.items).toHaveLength(1);
    expect(res.body.items[0].channelId).toBe(myChannel);
  });

  it('treats a blank term as no filter at all', async () => {
    const slug = unique('search');
    const channelId = await seedChannel({ slug });
    await seedPost(channelId, { body: 'first' });
    await seedPost(channelId, { body: 'second' });

    for (const term of ['', '   ']) {
      const res = await request(app).get(
        `/api/v1/channels/${slug}/posts?q=${encodeURIComponent(term)}`
      );
      expect(res.body.items).toHaveLength(2);
    }
  });

  it('treats the term as text rather than as a pattern', async () => {
    const slug = unique('search');
    const channelId = await seedChannel({ slug });
    await seedPost(channelId, { body: 'Discount is 50% today' });
    await seedPost(channelId, { body: 'Nothing to see here' });

    // `%` is a LIKE wildcard. A reader searching for it must get the post that
    // says 50%, not every post in the channel.
    const res = await request(app).get(`/api/v1/channels/${slug}/posts?q=${encodeURIComponent('50%')}`);
    expect(res.body.items).toHaveLength(1);
    expect(res.body.items[0].body).toBe('Discount is 50% today');
  });

  it('pages a search the same way it pages the feed', async () => {
    const slug = unique('search');
    const channelId = await seedChannel({ slug });
    for (let i = 0; i < 5; i += 1) {
      await seedPost(channelId, {
        body: `ayah ${i}`,
        createdAt: new Date(Date.now() - i * 60_000).toISOString(),
      });
    }
    await seedPost(channelId, { body: 'unrelated' });

    const first = await request(app).get(`/api/v1/channels/${slug}/posts?q=ayah&limit=2`);
    expect(first.body.items).toHaveLength(2);
    expect(first.body.nextCursor).not.toBeNull();

    const second = await request(app).get(
      `/api/v1/channels/${slug}/posts?q=ayah&limit=2&cursor=${first.body.nextCursor}`
    );
    const bodies = [
      ...first.body.items.map((p: { body: string }) => p.body),
      ...second.body.items.map((p: { body: string }) => p.body),
    ];
    expect(bodies).toEqual(['ayah 0', 'ayah 1', 'ayah 2', 'ayah 3']);
    expect(new Set(bodies).size).toBe(bodies.length);
  });

  it('finds a post by the title of the link it carries', async () => {
    const slug = unique('search');
    const channelId = await seedChannel({ slug });
    await seedPost(channelId, {
      type: 'link',
      body: null,
      linkUrl: 'https://example.test/lecture',
      linkTitle: 'Tafsir of Surah Al-Kahf',
    });

    const res = await request(app).get(`/api/v1/channels/${slug}/posts?q=tafsir`);
    expect(res.body.items).toHaveLength(1);
    expect(res.body.items[0].linkTitle).toBe('Tafsir of Surah Al-Kahf');
  });

  it('omits a removed post even when it matches', async () => {
    const slug = unique('search');
    const channelId = await seedChannel({ slug });
    await seedPost(channelId, { body: 'revised timetable', deleted: true });

    const res = await request(app).get(`/api/v1/channels/${slug}/posts?q=revised`);
    expect(res.body.items).toEqual([]);
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
