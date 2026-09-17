import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { runRetentionSweep } from '../src/jobs/retention.js';
import { applyAllMigrations, asQueryable, freshDatabase, one, resetData } from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';

/**
 * What the scheduled sweep removes, and why (§14, §34).
 *
 * Three rules live here, and the first two are decided by different things:
 *
 *  * **Time closes a post.** The server keeps roughly \u00a714's month of history:
 *    a post older than `POST_RETENTION_DAYS` is deleted with its media, so that
 *    someone who does not follow a channel can still discover it and read back
 *    far enough to judge it, while what the deployment stores stays bounded no
 *    matter how long a channel runs. This is the only rule here that shortens a
 *    channel's history without anybody asking it to, which is why the window is
 *    pinned in `vitest.config.ts` and why the tests around it assert BOTH sides
 *    of the line — a sweep that deleted everything, or nothing, would satisfy
 *    half of them.
 *
 *  * **A deleted post is removed for real.** Deleting is a soft delete so the app
 *    stops showing it immediately; the sweep is what makes it final, and what
 *    releases the objects it was holding. Decided by `deleted_at` alone and
 *    asserted with posts INSIDE the window, so it cannot pass on the age rule.
 *
 *  * **An object in use is never removed.** Both passes hand their keys to one
 *    reference count, which is the property that lets two rules delete rows
 *    without either of them breaking a post that is still live.
 */

let pglite: PGlite;
let app: Express;
let store: FakeObjectStore;
let db: ReturnType<typeof asQueryable>;

beforeAll(async () => {
  pglite = await freshDatabase();
  await applyAllMigrations(pglite);
  store = new FakeObjectStore();
  db = asQueryable(pglite);
  app = buildApp({ database: db, store });
});

afterAll(async () => {
  await pglite.close();
});

beforeEach(async () => {
  await resetData(pglite);
  store.reset();
});

let counter = 0;
function unique(prefix: string): string {
  counter += 1;
  return `${prefix}-${counter}`;
}

async function seedChannel(slug: string): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO channels (slug, name, status) VALUES ($1, $2, 'active'::channel_status)
     RETURNING id`,
    [slug, slug]
  );
  return one(rows.rows).id;
}

/**
 * A post, dated by the test.
 *
 * `ageDays` is the whole point of the fixture: the sweep selects on time, so a
 * post that is a day old cannot tell an expiring sweep from a non-expiring one.
 */
async function seedPost(
  channelId: string,
  options: { ageDays?: number; body?: string; deleted?: boolean } = {}
): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO posts (channel_id, author_id, type, body, created_at, deleted_at)
     VALUES (
       $1, NULL, 'text'::post_type, $2,
       now() - ($3::int * interval '1 day'),
       CASE WHEN $4::boolean THEN now() - interval '1 hour' ELSE NULL END
     )
     RETURNING id`,
    [channelId, options.body ?? 'Salam', options.ageDays ?? 0, options.deleted ?? false]
  );
  return one(rows.rows).id;
}

async function seedMedia(postId: string): Promise<string> {
  const key = `goodpost/channels/image/${postId}.jpg`;
  await pglite.query(
    `INSERT INTO post_media
       (owner_id, post_id, kind, object_key, content_type, byte_size, status, position)
     VALUES (NULL, $1, 'image'::media_kind, $2, 'image/jpeg', 2048, 'ready'::media_status, 0)`,
    [postId, key]
  );
  // The object itself, so the sweep's removal has something real to remove: the
  // fake store only knows about keys that were actually "uploaded".
  store.presignUpload({ key, contentType: 'image/jpeg', byteSize: 2048 });
  store.put();
  return key;
}

describe('the retention sweep', () => {
  it('keeps a post inside the window, and it stays readable', async () => {
    const slug = unique('recent');
    const channelId = await seedChannel(slug);
    const postId = await seedPost(channelId, { ageDays: 5, body: 'Within the month' });

    const sweep = await runRetentionSweep(db, store);
    expect(sweep.purged).toBe(0);
    expect(sweep.agedOut).toBe(0);
    expect(store.removed).toEqual([]);

    // Read through the public API, because that is the promise a reader sees.
    const posts = await request(app).get(`/api/v1/channels/${slug}/posts`);
    expect(posts.status).toBe(200);
    expect(posts.body.items.map((p: { id: string }) => p.id)).toContain(postId);
  });

  it('draws the line on the window and not near it', async () => {
    // Both sides in one fixture: a day either side of 30 is the whole rule, and
    // asserting only the deletion would also pass if the pass removed
    // everything older than nothing.
    const slug = unique('line');
    const channelId = await seedChannel(slug);
    const insideId = await seedPost(channelId, { ageDays: 29, body: 'Day 29' });
    const outsideId = await seedPost(channelId, { ageDays: 31, body: 'Day 31' });

    const sweep = await runRetentionSweep(db, store);

    expect(sweep.purged).toBe(1);
    expect(sweep.agedOut).toBe(1);

    const posts = await request(app).get(`/api/v1/channels/${slug}/posts`);
    const ids = posts.body.items.map((p: { id: string }) => p.id);
    expect(ids).toContain(insideId);
    expect(ids).not.toContain(outsideId);
  });

  it('takes an aged-out post\u2019s media and corrects the channel it left behind', async () => {
    const slug = unique('aged');
    const channelId = await seedChannel(slug);
    const oldId = await seedPost(channelId, { ageDays: 40, body: 'Last month\u2019s news' });
    const key = await seedMedia(oldId);
    const freshId = await seedPost(channelId, { ageDays: 2, body: 'This week\u2019s news' });
    // The channel is currently described by the post that is about to expire, so
    // the sweep has to move `last_post_at` onto the fresh one — otherwise the
    // channel list advertises an update nobody can open.
    await pglite.query(`UPDATE channels SET last_post_at = now() - interval '40 days' WHERE id = $1`, [
      channelId,
    ]);

    const sweep = await runRetentionSweep(db, store);

    expect(sweep.purged).toBe(1);
    expect(sweep.objectsRemoved).toBe(1);
    expect(store.removed).toEqual([key]);
    expect(store.has(key)).toBe(false);

    const detail = await request(app).get(`/api/v1/channels/${slug}`);
    expect(detail.body.channel.lastPostPreview).toBe('This week\u2019s news');
    expect(detail.body.channel.lastPostAt).toBeTruthy();

    const posts = await request(app).get(`/api/v1/channels/${slug}/posts`);
    expect(posts.body.items.map((p: { id: string }) => p.id)).toEqual([freshId]);
  });

  it('leaves a channel that nothing has expired for alone', async () => {
    // The expensive way to be wrong here is a sweep that empties a channel of
    // recent posts while reporting a small number, so the survivor is asserted
    // directly rather than inferred from the count.
    const slug = unique('untouched');
    const channelId = await seedChannel(slug);
    const postId = await seedPost(channelId, { ageDays: 1, body: 'Still the latest' });

    expect((await runRetentionSweep(db, store)).purged).toBe(0);

    const detail = await request(app).get(`/api/v1/channels/${slug}`);
    expect(detail.body.channel.lastPostPreview).toBe('Still the latest');
    const posts = await request(app).get(`/api/v1/channels/${slug}/posts`);
    expect(posts.body.items.map((p: { id: string }) => p.id)).toEqual([postId]);
  });

  it('removes a deleted post, its media, and the activity it left on the channel', async () => {
    const slug = unique('removed');
    const channelId = await seedChannel(slug);
    const keptId = await seedPost(channelId, { body: 'Still here' });
    const goneId = await seedPost(channelId, { body: 'Deleted on purpose', deleted: true, ageDays: 1 });
    const key = await seedMedia(goneId);
    // Activity recorded for the deleted post, which the sweep must correct rather
    // than leave the list claiming an update that no longer exists.
    await pglite.query(`UPDATE channels SET last_post_at = now() - interval '1 day' WHERE id = $1`, [
      channelId,
    ]);

    const sweep = await runRetentionSweep(db, store);

    expect(sweep.purged).toBe(1);
    expect(sweep.objectsRemoved).toBe(1);
    expect(store.removed).toEqual([key]);
    expect(store.has(key)).toBe(false);

    const posts = await request(app).get(`/api/v1/channels/${slug}/posts`);
    const ids = posts.body.items.map((p: { id: string }) => p.id);
    expect(ids).toContain(keptId);
    expect(ids).not.toContain(goneId);

    const detail = await request(app).get(`/api/v1/channels/${slug}`);
    expect(detail.body.channel.lastPostPreview).toBe('Still here');
  });

  it('is idempotent: a second sweep finds nothing left to do', async () => {
    const slug = unique('twice');
    const channelId = await seedChannel(slug);
    const postId = await seedPost(channelId, { deleted: true });
    await seedMedia(postId);

    expect((await runRetentionSweep(db, store)).purged).toBe(1);
    const again = await runRetentionSweep(db, store);

    expect(again.purged).toBe(0);
    expect(again.objectsRemoved).toBe(0);
    expect(store.removed).toHaveLength(1);
  });

  it('decides on the deleted flag, never on age alone', async () => {
    // Both posts are INSIDE the window on purpose, so the only rule that can
    // remove either of them is the deletion flag. Dating these posts a year back
    // would let the age pass answer for this one and the flag would go
    // unasserted.
    const slug = unique('both');
    const channelId = await seedChannel(slug);
    const published = await seedPost(channelId, { ageDays: 3, body: 'Published' });
    const removed = await seedPost(channelId, { ageDays: 3, deleted: true, body: 'Deleted' });

    const sweep = await runRetentionSweep(db, store);

    expect(sweep.purged).toBe(1);
    expect(sweep.agedOut).toBe(0);
    const rows = await pglite.query<{ id: string }>(`SELECT id FROM posts`);
    expect(rows.rows.map((r) => r.id)).not.toContain(removed);
    expect(rows.rows.map((r) => r.id)).toContain(published);
    expect(rows.rows).toHaveLength(1);
  });
});
