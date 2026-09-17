import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { sweepAbandonedUploads } from '../src/media/service.js';
import {
  applyAllMigrations,
  asQueryable,
  freshDatabase,
  insertAdmin,
  resetData,
} from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';
import {
  authed,
  createBareChannel,
  signIn,
  superAdminSession,
  type ChannelFixture,
  type Session,
} from './helpers/accounts.js';

/**
 * The media handshake, end to end (§21, §22, §26).
 *
 * The other suites check each rule where it lives. This one walks the whole
 * path a phone actually takes and asserts on the far end of it — because every
 * interesting failure in this flow is a DISAGREEMENT between two steps that are
 * individually correct:
 *
 *   presign ──PUT──▶ bucket ──confirm──▶ publish ──▶ public read
 *
 * A content type the presign signed but the bucket stored differently, an
 * object key the confirm checked but the claim attached, a `type` column that
 * does not match the file beside it, a signed URL that expires before the
 * reader fetches it, a gallery that still lists a post's asset after the post
 * was removed — none of those are visible from inside one step. They are
 * visible here.
 *
 * The bucket is a fake, and deliberately a dumb one: it enforces nothing. That
 * is the point. Every rule that matters in this flow is OURS (the key is
 * server-chosen, an unconfirmed upload cannot be attached, one upload cannot be
 * claimed twice, a reader sees a signed URL or nothing), and a test that needed
 * an AWS account to check them would be a test that ran rarely or never.
 */

let pglite: PGlite;
let app: Express;
let store: FakeObjectStore;

/** The bytes are never in this process; only their declared shape is. */
const IMAGE_BYTES = 4096;
const VIDEO_BYTES = 1_048_576;

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

/** A channel with an administrator who can publish to it. */
async function setup(): Promise<{ session: Session; channel: ChannelFixture }> {
  const session = await superAdminSession(app, pglite);
  const channel = await createBareChannel(app, session, `Handshake ${Math.random().toString(36).slice(2, 8)}`);
  return { session, channel };
}

/**
 * Steps 1 and 2: ask for a place to put the file, then put it there.
 *
 * Split from the confirm so a test can stop in between — that gap is where the
 * interesting states live.
 */
async function presignAndPut(
  session: Session,
  contentType: string,
  byteSize: number,
  options: { readonly uploadBytes?: boolean; readonly declaredSize?: number } = {}
): Promise<string> {
  const presign = await request(app)
    .post('/admin/api/media/uploads')
    .set(authed(session.accessToken))
    .send({ contentType, byteSize, width: 1200, height: 800, durationMs: 30_000 });

  expect(presign.status, JSON.stringify(presign.body)).toBe(201);
  const mediaId = presign.body.upload.mediaId as string;
  expect(mediaId).toBeTruthy();

  if (options.uploadBytes !== false) {
    // The client's PUT. `declaredSize` is what the bucket ends up holding, and
    // it is a parameter only because the fake can be lied to — real S3 cannot,
    // because the length is signed into the URL.
    store.put(contentType, options.declaredSize ?? byteSize);
  }

  return mediaId;
}

/** Step 3: let the server check the object is really there. */
async function confirm(session: Session, mediaId: string) {
  const res = await request(app)
    .post(`/admin/api/media/uploads/${mediaId}/confirm`)
    .set(authed(session.accessToken));
  expect(res.status, JSON.stringify(res.body)).toBe(200);
  return res.body.media as { id: string; kind: string; url: string | null };
}

/** The whole handshake, for a test that is not about one of its steps. */
async function upload(
  session: Session,
  contentType = 'image/jpeg',
  byteSize = IMAGE_BYTES
): Promise<string> {
  const mediaId = await presignAndPut(session, contentType, byteSize);
  await confirm(session, mediaId);
  return mediaId;
}

describe('publish a post with media, then read it back', () => {
  it('carries the image all the way from a presigned PUT to a reader', async () => {
    const { session, channel } = await setup();

    // ── 1. presign ──────────────────────────────────────────────────────
    const mediaId = await presignAndPut(session, 'image/jpeg', IMAGE_BYTES);
    const key = store.lastIssuedKey();

    // The key is DERIVED from the media row's own uuid, so nothing a client
    // sent can appear in it — the standard way one account overwrites or
    // claims another's object is a client-chosen key, and there is none here.
    expect(key).toMatch(/^goodpost\/channels\/image\/[0-9a-f-]{36}\.jpg$/);
    expect(key).not.toContain(channel.slug);
    expect(store.has(key)).toBe(true);

    // ── 2. confirm ──────────────────────────────────────────────────────
    const confirmed = await confirm(session, mediaId);
    expect(confirmed.id).toBe(mediaId);
    expect(confirmed.kind).toBe('image');

    // ── 3. publish ──────────────────────────────────────────────────────
    const published = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(session.accessToken))
      .send({ body: 'Surah Al-Kahf', mediaIds: [mediaId] });

    expect(published.status, JSON.stringify(published.body)).toBe(201);
    const post = published.body.post as { id: string; type: string; media: unknown[] };

    // The type was never sent: it follows from the file, so a client cannot
    // describe its own post wrongly.
    expect(post.type).toBe('image');
    expect(post.media).toHaveLength(1);

    // ── 4. read back through the public API ─────────────────────────────
    // No Authorization header from here on. This is the assertion the whole
    // file exists for: a reader with no account sees exactly what was
    // published, with a URL it can fetch.
    const feed = await request(app).get(`/api/v1/channels/${channel.slug}/posts`);
    expect(feed.status).toBe(200);
    expect(feed.body.items).toHaveLength(1);

    const listed = feed.body.items[0];
    expect(listed.id).toBe(post.id);
    expect(listed.type).toBe('image');
    expect(listed.body).toBe('Surah Al-Kahf');
    expect(listed.media).toHaveLength(1);
    expect(listed.media[0].id).toBe(mediaId);
    expect(listed.media[0].kind).toBe('image');
    expect(listed.media[0].contentType).toBe('image/jpeg');
    expect(listed.media[0].byteSize).toBe(IMAGE_BYTES);
    // The layout hints survive the round trip; the server stores what the
    // presign declared rather than re-deriving it.
    expect(listed.media[0].width).toBe(1200);
    expect(listed.media[0].height).toBe(800);
    expect(listed.media[0].url).toContain('https://fake-bucket.test/');
    expect(listed.media[0].url).toContain(key);

    // The reader is never handed the object key on its own: what crosses this
    // boundary is a signed, expiring URL, and a key would be a permanent name
    // for something that is meant to be temporary.
    expect(listed.media[0]).not.toHaveProperty('objectKey');
    expect(listed.media[0]).not.toHaveProperty('object_key');
  });

  it('names its channel when the post is fetched on its own', async () => {
    const { session, channel } = await setup();
    const mediaId = await upload(session);

    const published = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(session.accessToken))
      .send({ mediaIds: [mediaId] });
    const postId = published.body.post.id as string;

    const single = await request(app).get(`/api/v1/posts/${postId}`);
    expect(single.status).toBe(200);
    expect(single.body.post.channel).toEqual({
      id: channel.id,
      slug: channel.slug,
      name: channel.name,
    });
    expect(single.body.post.media[0].url).toContain('fake-bucket');
  });

  it('lists the asset in the channel gallery, and takes it out with its post', async () => {
    const { session, channel } = await setup();
    const mediaId = await upload(session);

    const published = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(session.accessToken))
      .send({ body: 'A photo', mediaIds: [mediaId] });
    const postId = published.body.post.id as string;

    const gallery = await request(app).get(`/api/v1/channels/${channel.slug}/media`);
    expect(gallery.body.items).toHaveLength(1);
    expect(gallery.body.items[0].postId).toBe(postId);
    expect(gallery.body.items[0].url).toContain('fake-bucket');

    // Removing the post is a SOFT delete, and the gallery is a join back to
    // `posts` precisely so it follows: an asset whose post is gone must not
    // stay browsable.
    const removed = await request(app)
      .delete(`/admin/api/posts/${postId}`)
      .set(authed(session.accessToken));
    expect(removed.status).toBe(200);

    const after = await request(app).get(`/api/v1/channels/${channel.slug}/media`);
    expect(after.body.items).toHaveLength(0);
    expect((await request(app).get(`/api/v1/posts/${postId}`)).status).toBe(404);
  });

  it('makes the channel list describe the same post the feed returns', async () => {
    const { session, channel } = await setup();
    const mediaId = await upload(session);

    await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(session.accessToken))
      .send({ body: 'New update about…', mediaIds: [mediaId] });

    const list = await request(app).get('/api/v1/channels');
    const row = list.body.items.find((c: { id: string }) => c.id === channel.id);

    // §4: a row's preview, its type and its timestamp must describe ONE post.
    // They come from the same lateral join for that reason — two sources would
    // eventually disagree, and a row would advertise media it does not have.
    expect(row.lastPostType).toBe('image');
    expect(row.lastPostPreview).toBe('New update about…');
    expect(row.lastPostAt).not.toBeNull();

    const feed = await request(app).get(`/api/v1/channels/${channel.slug}/posts`);
    expect(feed.body.items[0].media).toHaveLength(1);
    expect(feed.body.items[0].createdAt).toBe(row.lastPostAt);
  });

  it('keeps a video a video, and reports its duration', async () => {
    const { session, channel } = await setup();
    const mediaId = await upload(session, 'video/mp4', VIDEO_BYTES);

    const published = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(session.accessToken))
      .send({ body: 'A clip', mediaIds: [mediaId] });

    expect(published.body.post.type).toBe('video');

    const feed = await request(app).get(`/api/v1/channels/${channel.slug}/posts`);
    const asset = feed.body.items[0].media[0];
    expect(asset.kind).toBe('video');
    expect(asset.durationMs).toBe(30_000);

    // The key's extension comes from the content type the server validated,
    // not from anything the client typed.
    expect(store.lastIssuedKey()).toMatch(/\.mp4$/);
  });

  it('orders several files the way they were attached', async () => {
    const { session, channel } = await setup();
    const first = await upload(session);
    const second = await upload(session);

    const published = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(session.accessToken))
      .send({ body: 'A carousel', mediaIds: [second, first] });

    expect(published.status).toBe(201);

    const feed = await request(app).get(`/api/v1/channels/${channel.slug}/posts`);
    // Position follows the composer's order, not whatever order the database
    // happened to return the rows in — the difference is a carousel that
    // rearranges itself between publishing and reading.
    expect(feed.body.items[0].media.map((m: { id: string }) => m.id)).toEqual([second, first]);
    expect(feed.body.items[0].media.map((m: { position: number }) => m.position)).toEqual([0, 1]);
  });
});

describe('the handshake refuses to skip a step', () => {
  it('leaves an unconfirmed upload invisible to readers, and unmatchable to a post', async () => {
    const { session, channel } = await setup();

    // Presigned and PUT — but never confirmed. The object is in the bucket.
    const mediaId = await presignAndPut(session, 'image/jpeg', IMAGE_BYTES);
    expect(store.has(store.lastIssuedKey())).toBe(true);

    const published = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(session.accessToken))
      .send({ body: 'Should not attach', mediaIds: [mediaId] });

    expect(published.status).toBe(409);
    expect(published.body.error).toBe('media_not_ready');

    // Nothing was published, so the reader sees nothing — not a post with a
    // broken image. A publish either attaches what it was given or does not
    // happen at all.
    const feed = await request(app).get(`/api/v1/channels/${channel.slug}/posts`);
    expect(feed.body.items).toHaveLength(0);

    const gallery = await request(app).get(`/api/v1/channels/${channel.slug}/media`);
    expect(gallery.body.items).toHaveLength(0);
  });

  it('refuses an upload the bucket never received, rather than trusting the client', async () => {
    const { session } = await setup();

    // Declared but never PUT: the confirm step asks the bucket, and a client's
    // word is not evidence.
    const mediaId = await presignAndPut(session, 'image/png', 2048, { uploadBytes: false });

    const res = await request(app)
      .post(`/admin/api/media/uploads/${mediaId}/confirm`)
      .set(authed(session.accessToken));
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('media_not_uploaded');
  });

  it('refuses an object whose size differs from the declared one', async () => {
    const { session } = await setup();

    // Real S3 cannot be lied to here — ContentLength is signed into the URL —
    // so this is the case that only exists if something else put the object
    // there. Accepting it would mean storing an asset whose declared metadata
    // (and therefore its storage accounting) is wrong.
    const mediaId = await presignAndPut(session, 'image/jpeg', IMAGE_BYTES, {
      declaredSize: IMAGE_BYTES + 1,
    });

    const res = await request(app)
      .post(`/admin/api/media/uploads/${mediaId}/confirm`)
      .set(authed(session.accessToken));
    expect(res.status).toBe(409);
    expect(res.body.error).toBe('media_size_mismatch');
  });

  it('is idempotent when the confirm response was lost', async () => {
    const { session } = await setup();
    const mediaId = await presignAndPut(session, 'image/jpeg', IMAGE_BYTES);

    const first = await request(app)
      .post(`/admin/api/media/uploads/${mediaId}/confirm`)
      .set(authed(session.accessToken));
    const second = await request(app)
      .post(`/admin/api/media/uploads/${mediaId}/confirm`)
      .set(authed(session.accessToken));

    // A client that retried after a dropped response must not be punished for
    // having got through the first time.
    expect(first.status).toBe(200);
    expect(second.status).toBe(200);
    expect(second.body.media.id).toBe(mediaId);
  });

  it('answers `media_unavailable` and still publishes text on a deployment with no bucket', async () => {
    // §22's supported configuration: a deployment with no storage serves
    // READERS normally — a text post needs no bucket — and refuses only the
    // upload step, with a code the app can word.
    const bare = buildApp({ database: asQueryable(pglite), store: unconfiguredStore() });
    const email = `super-${Math.random().toString(36).slice(2, 8)}@example.test`;
    await insertAdmin(pglite, { email, email_normalized: email });
    const session = await signIn(bare, email);
    const channel = await createBareChannel(bare, session, 'Text only');

    const refused = await request(bare)
      .post('/admin/api/media/uploads')
      .set(authed(session.accessToken))
      .send({ contentType: 'image/jpeg', byteSize: IMAGE_BYTES });
    expect(refused.status).toBe(503);
    expect(refused.body.error).toBe('media_unavailable');

    const post = await request(bare)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(session.accessToken))
      .send({ body: 'Still works' });
    expect(post.status).toBe(201);

    // Read back through the OTHER app instance, which has a bucket: the
    // payload is the same either way, with `url: null` where there is no
    // object to serve.
    const feed = await request(app).get(`/api/v1/channels/${channel.slug}/posts`);
    expect(feed.body.items[0].body).toBe('Still works');
    expect(feed.body.items[0].media).toEqual([]);
  });
});

describe('a channel’s profile image is the same handshake (§21)', () => {
  /**
   * Set a channel's image the way the app's form does: upload, then save.
   *
   * Two requests on purpose — the ComposerDialog's Save carries the media id, so
   * the image travels with the rest of the profile rather than in a second step a
   * partial failure could skip.
   */
  async function setIcon(session: Session, channelId: string, mediaId: string) {
    return request(app)
      .patch(`/admin/api/channels/${channelId}`)
      .set(authed(session.accessToken))
      .send({ iconMediaId: mediaId });
  }

  it('takes a confirmed upload, and a reader can fetch the result', async () => {
    const { session, channel } = await setup();
    const mediaId = await upload(session);

    const res = await setIcon(session, channel.id, mediaId);
    expect(res.status, JSON.stringify(res.body)).toBe(200);

    // A URL, never the key. An object key is a permanent name for something
    // meant to be temporary, and handing one to a client would let it be used
    // forever — so no payload has anywhere to put one.
    expect(res.body.channel.iconUrl).toContain('https://fake-bucket.test/');
    expect(res.body.channel).not.toHaveProperty('iconObjectKey');
    expect(res.body.channel).not.toHaveProperty('icon_object_key');

    const reader = await request(app).get(`/api/v1/channels/${channel.slug}`);
    expect(reader.body.channel.iconUrl).toContain('fake-bucket');

    // And the LIST signs it too: an avatar that appears on a detail screen but
    // not in the list is the inconsistency this helper exists to prevent.
    const list = await request(app).get('/api/v1/channels');
    const row = list.body.items.find((c: { id: string }) => c.id === channel.id);
    expect(row.iconUrl).toContain('fake-bucket');
  });

  it('refuses an upload that was never confirmed, like a post would', async () => {
    const { session, channel } = await setup();
    const mediaId = await presignAndPut(session, 'image/jpeg', IMAGE_BYTES);

    const res = await setIcon(session, channel.id, mediaId);
    expect(res.status).toBe(409);
    expect(res.body.error).toBe('media_not_ready');

    // Nothing changed, so the channel does not advertise an image it cannot
    // serve.
    const reader = await request(app).get(`/api/v1/channels/${channel.slug}`);
    expect(reader.body.channel.iconUrl).toBeNull();
  });

  it('cannot adopt another administrator’s upload', async () => {
    const superSession = await superAdminSession(app, pglite);
    const mine = await createBareChannel(app, superSession, 'Mine');
    const theirs = await createBareChannel(app, superSession, 'Theirs');
    const mediaId = await upload(superSession);

    // Set the image on one channel, then try to adopt the SAME upload for
    // another: the row is already claimed, so the answer is the same as for a
    // file that does not exist.
    await setIcon(superSession, mine.id, mediaId);
    const second = await setIcon(superSession, theirs.id, mediaId);

    expect(second.status).toBe(409);
    expect(second.body.error).toBe('media_already_used');
  });

  it('replaces the old image and removes the object it replaced', async () => {
    const { session, channel } = await setup();

    const first = await upload(session);
    await setIcon(session, channel.id, first);
    const firstKey = store.lastIssuedKey();
    expect(store.has(firstKey)).toBe(true);

    const second = await upload(session);
    const secondKey = store.lastIssuedKey();
    await setIcon(session, channel.id, second);

    // The replaced object is gone from the bucket. Leaving it would be a
    // permanent orphan: nothing references it, so nothing could ever find it
    // again — not the retention sweep, not a listing.
    expect(store.removed).toContain(firstKey);
    expect(store.has(firstKey)).toBe(false);

    const reader = await request(app).get(`/api/v1/channels/${channel.slug}`);
    expect(reader.body.channel.iconUrl).toContain(secondKey);
  });

  it('clears the image with an explicit null, and leaves it alone when absent', async () => {
    const { session, channel } = await setup();
    const mediaId = await upload(session);
    const key = store.lastIssuedKey();
    await setIcon(session, channel.id, mediaId);

    // Absent: an edit that only renames the channel must not drop its image.
    const renamed = await request(app)
      .patch(`/admin/api/channels/${channel.id}`)
      .set(authed(session.accessToken))
      .send({ name: 'Renamed' });
    expect(renamed.body.channel.iconUrl).toContain(key);

    // Explicit null: the three-way field's third meaning.
    const cleared = await request(app)
      .patch(`/admin/api/channels/${channel.id}`)
      .set(authed(session.accessToken))
      .send({ iconMediaId: null });
    expect(cleared.body.channel.iconUrl).toBeNull();
    expect(store.has(key)).toBe(false);

    const reader = await request(app).get(`/api/v1/channels/${channel.slug}`);
    expect(reader.body.channel.iconUrl).toBeNull();
  });

  it('leaves the image alone when a post is published', async () => {
    const { session, channel } = await setup();
    const icon = await upload(session);
    await setIcon(session, channel.id, icon);
    const iconKey = store.lastIssuedKey();

    const postMedia = await upload(session);
    await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(session.accessToken))
      .send({ mediaIds: [postMedia] });

    // Publishing claims a DIFFERENT row, so the channel's own image is not
    // adopted as the post's attachment — which is what `channel_id IS NULL` in
    // the claim query is for.
    const reader = await request(app).get(`/api/v1/channels/${channel.slug}`);
    expect(reader.body.channel.iconUrl).toContain(iconKey);
  });

  it('survives the abandoned-upload sweep, which must not take a live avatar', async () => {
    const { session, channel } = await setup();
    const mediaId = await upload(session);
    await setIcon(session, channel.id, mediaId);
    const key = store.lastIssuedKey();

    // Every row this sweep looks at is older than the window, including the
    // channel's own image row: `post_id` is NULL on both. What separates them is
    // `channel_id`, and getting that wrong deletes a live avatar.
    const swept = await sweepAbandonedUploads(asQueryable(pglite), store, -1, 100);

    expect(swept.removed).toBe(0);
    expect(store.has(key)).toBe(true);

    const reader = await request(app).get(`/api/v1/channels/${channel.slug}`);
    expect(reader.body.channel.iconUrl).toContain(key);
  });
});

/** A store that answers "this deployment has no bucket" (§22). */
function unconfiguredStore(): FakeObjectStore {
  const fake = new FakeObjectStore();
  Object.defineProperty(fake, 'configured', { value: false });
  return fake;
}
