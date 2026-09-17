import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { runRetentionSweep } from '../src/jobs/retention.js';
import { applyAllMigrations, asQueryable, freshDatabase, one, resetData } from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';

/**
 * What the scheduled sweep does and does not remove (§34).
 *
 * Two rules live here, and they pull in opposite directions, which is why they
 * are tested together:
 *
 *  * **A post never expires on its own.** A channel's history is what a channel
 *    is; an update stays readable until an administrator deletes it. This was a
 *    real behaviour once — a 30-day window that quietly flagged and then physically
 *    deleted older posts, media included — and it is exactly the kind of rule that
 *    comes back by accident, since nothing about a working sweep looks wrong until
 *    the day a channel's first month is behind it.
 *
 *  * **A deleted post is removed for real.** Deleting is a soft delete so the app
 *    stops showing it immediately; the sweep is what makes it final, and what
 *    releases the objects it was holding.
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
  it('leaves a post of any age alone, and it stays readable', async () => {
    const slug = unique('archive');
    const channelId = await seedChannel(slug);
    const postId = await seedPost(channelId, { ageDays: 400, body: 'A year old and still true' });
    await pglite.query(`UPDATE channels SET last_post_at = now() - interval '400 days' WHERE id = $1`, [
      channelId,
    ]);

    const sweep = await runRetentionSweep(db, store);
    expect(sweep.purged).toBe(0);
    expect(store.removed).toEqual([]);

    // Read through the public API, because that is the promise: a reader sees the
    // whole history, not the last 30 days of it.
    const posts = await request(app).get(`/api/v1/channels/${slug}/posts`);
    expect(posts.status).toBe(200);
    expect(posts.body.items.map((p: { id: string }) => p.id)).toContain(postId);

    const detail = await request(app).get(`/api/v1/channels/${slug}`);
    expect(detail.body.channel.lastPostPreview).toBe('A year old and still true');
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

  it('decides on the deleted flag, never on age', async () => {
    // The two rules meeting in one fixture: age alone decides nothing, and the
    // deleted flag decides everything.
    const slug = unique('both');
    const channelId = await seedChannel(slug);
    await seedPost(channelId, { ageDays: 365, body: 'Old but published' });
    const removed = await seedPost(channelId, { ageDays: 365, deleted: true, body: 'Old and removed' });

    const sweep = await runRetentionSweep(db, store);

    expect(sweep.purged).toBe(1);
    const rows = await pglite.query<{ id: string }>(`SELECT id FROM posts`);
    expect(rows.rows.map((r) => r.id)).not.toContain(removed);
    expect(rows.rows).toHaveLength(1);
  });
});
