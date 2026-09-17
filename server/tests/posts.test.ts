import { randomUUID } from 'node:crypto';
import type { Express } from 'express';
import request from 'supertest';
import type { PGlite } from '@electric-sql/pglite';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { closePool, type Queryable } from '../src/db.js';
import { env } from '../src/env.js';
import {
  UnconfiguredObjectStore,
  extensionFor,
  isAccessDenied,
  isNotFound,
  kindFor,
  mediaObjectKey,
} from '../src/media/store.js';
import { sweepAbandonedUploads } from '../src/media/service.js';
import { runRetentionSweep } from '../src/jobs/retention.js';
import { applyAllMigrations, asQueryable, freshDatabase, resetData } from './helpers/database.js';
import { authed, createChannelVia, fakeVerifier, registeredIn } from './helpers/accounts.js';
import { FakeObjectStore } from './helpers/storage.js';

/**
 * M3 posts and media (§4, §7, §8, §9, §10, §34).
 *
 * The whole pipeline runs against a real Postgres (PGlite) and a fake S3, so a
 * pass means the schema, the SQL, the ownership rules and the upload state
 * machine agree. The adversarial cases carry the weight: §39 asks for
 * unauthorized requests to be attempted explicitly, and the interesting failure
 * in a feature like this is never "publishing works" — it is a post that
 * attaches someone else's upload, or media that stays served after its post was
 * removed.
 */

const PHONE_A = '+923002220001';
const PHONE_B = '+923002220002';
const PHONE_C = '+923002220003';

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

// ── Helpers ─────────────────────────────────────────────────────────────

const registered = (phone: string, name?: string, email?: string) =>
  registeredIn(app, phone, name, email);

const createChannel = (session: { accessToken: string }, body: Record<string, unknown>) =>
  createChannelVia(app, session, body);

interface Session {
  accessToken: string;
  user: { id: string };
}

/** Asks for an upload URL and returns the issued payload. */
async function startUpload(session: Session, contentType: string, byteSize = 1024) {
  const res = await request(app)
    .post('/api/v1/media/uploads')
    .set(authed(session.accessToken))
    .send({ contentType, byteSize });

  expect(res.status, `upload request for ${contentType}`).toBe(201);
  return res.body.upload as {
    mediaId: string;
    kind: string;
    contentType: string;
    byteSize: number;
    uploadUrl: string;
    uploadHeaders: Record<string, string>;
    expiresInSeconds: number;
  };
}

/** The full happy path: presign → PUT → confirm. Returns the media id. */
async function uploadAndConfirm(
  session: Session,
  contentType: string,
  byteSize = 1024
): Promise<string> {
  const upload = await startUpload(session, contentType, byteSize);
  store.put();

  const res = await request(app)
    .post(`/api/v1/media/uploads/${upload.mediaId}/confirm`)
    .set(authed(session.accessToken));

  expect(res.status).toBe(200);
  return upload.mediaId;
}

const publishPost = (session: Session, channelId: string, body: Record<string, unknown>) =>
  request(app)
    .post(`/api/v1/channels/${channelId}/posts`)
    .set(authed(session.accessToken))
    .send(body);

/** Publishes and asserts success, returning the post payload. */
async function published(session: Session, channelId: string, body: Record<string, unknown>) {
  const res = await publishPost(session, channelId, body);
  expect(res.status, `publish ${JSON.stringify(body)}`).toBe(201);
  return res.body.post as PostPayload;
}

interface PostPayload {
  id: string;
  channelId: string;
  type: string;
  body: string | null;
  linkUrl: string | null;
  linkTitle: string | null;
  media: { id: string; kind: string; position: number; url: string | null }[];
  createdAt: string;
  editedAt: string | null;
  isEdited: boolean;
  viewerCanManage: boolean;
  channel?: { id: string; slug: string; name: string };
}

/** Makes the viewer follow a channel, which is what fills their feed. */
async function follow(session: Session, channelId: string): Promise<void> {
  const res = await request(app)
    .post(`/api/v1/channels/${channelId}/follow`)
    .set(authed(session.accessToken));
  expect(res.status).toBe(200);
}

/**
 * Moves a post's `created_at` backwards, so ordering and the edit window are
 * deterministic instead of depending on how fast the test runs.
 */
async function agePost(postId: string, minutes: number): Promise<void> {
  await pglite.query(
    `UPDATE posts SET created_at = now() - ($2 || ' minutes')::interval WHERE id = $1`,
    [postId, String(minutes)]
  );
}

/** Gives a user a role in a channel without going through ownership. */
async function grantRole(channelId: string, userId: string, role: string): Promise<void> {
  await pglite.query(
    `INSERT INTO channel_admins (channel_id, user_id, role) VALUES ($1, $2, $3)`,
    [channelId, userId, role]
  );
}

async function readChannel(session: Session, channelId: string) {
  const res = await request(app)
    .get(`/api/v1/channels/${channelId}`)
    .set(authed(session.accessToken));
  expect(res.status).toBe(200);
  return res.body.channel as { postCount: number; lastPostAt: string | null };
}

// ── Pure storage policy ─────────────────────────────────────────────────
// No database and no bucket: these are the rules that decide what the bucket
// may ever serve.

describe('the media content-type policy', () => {
  it('refuses types that would be served back as active content', () => {
    // `text/html` and `image/svg+xml` stored under a URL a browser will fetch
    // are a stored-XSS primitive served from our own domain, which is why this
    // is an allow-list and not a deny-list.
    for (const type of ['text/html', 'image/svg+xml', 'application/javascript', 'application/pdf']) {
      expect(kindFor(type), type).toBeNull();
      expect(extensionFor(type), type).toBeNull();
    }
  });

  it('accepts a type that carries parameters, and normalises its case', () => {
    // A client sending `image/jpeg; charset=utf-8` is not sending a different
    // type, and treating it as unlisted would reject valid uploads.
    expect(kindFor('IMAGE/JPEG; charset=UTF-8')).toBe('image');
    expect(extensionFor('image/jpeg; charset=utf-8')).toBe('jpg');
  });

  it('derives an object key from the id and never from a path', () => {
    const id = randomUUID();
    const key = mediaObjectKey('image', id, 'jpg');

    expect(key).toBe(`goodpost/channels/image/${id}.jpg`);
    // The layout §9 asks for, so an operator can attach a lifecycle rule per
    // kind without reading the database.
    expect(key.startsWith('goodpost/channels/')).toBe(true);
  });
});

// ── Upload lifecycle ────────────────────────────────────────────────────

describe('the upload lifecycle', () => {
  it('issues a presigned URL and records a pending row', async () => {
    const owner = await registered(PHONE_A);
    const upload = await startUpload(owner, 'image/jpeg', 2048);

    expect(upload.kind).toBe('image');
    expect(upload.uploadUrl).toContain('X-Amz-Signature');
    expect(upload.uploadHeaders['Content-Type']).toBe('image/jpeg');

    const row = await pglite.query<{ status: string; object_key: string; post_id: string | null }>(
      `SELECT status, object_key, post_id FROM post_media WHERE id = $1`,
      [upload.mediaId]
    );
    expect(row.rows[0]?.status).toBe('pending');
    expect(row.rows[0]?.post_id).toBeNull();
  });

  it('ignores a client-supplied object key', async () => {
    // The single most valuable property here: a request cannot choose where its
    // bytes land, so it cannot overwrite or claim another account's object.
    const owner = await registered(PHONE_A);

    const res = await request(app)
      .post('/api/v1/media/uploads')
      .set(authed(owner.accessToken))
      .send({
        contentType: 'image/jpeg',
        byteSize: 1024,
        objectKey: '../../evil.jpg',
        key: '../../evil.jpg',
      });

    expect(res.status).toBe(201);
    const key = store.lastIssuedKey();
    expect(key).toMatch(/^goodpost\/channels\/image\/[0-9a-f-]{36}\.jpg$/);
    // Derived from the media row's own id, which is what makes a double claim
    // detectable and a collision impossible.
    expect(key).toContain(res.body.upload.mediaId);
    expect(key).not.toContain('evil');
  });

  it('refuses an unsupported content type', async () => {
    const owner = await registered(PHONE_A);

    const res = await request(app)
      .post('/api/v1/media/uploads')
      .set(authed(owner.accessToken))
      .send({ contentType: 'text/html', byteSize: 1024 });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('unsupported_media_type');
  });

  it('refuses a file above the configured ceiling', async () => {
    const owner = await registered(PHONE_A);

    const res = await request(app)
      .post('/api/v1/media/uploads')
      .set(authed(owner.accessToken))
      .send({ contentType: 'video/mp4', byteSize: env.S3_MAX_UPLOAD_BYTES + 1 });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('media_too_large');
  });

  it('refuses to confirm an upload that never arrived', async () => {
    // The check that stops a published post from shipping a broken image. The
    // bucket is asked, not the client.
    const owner = await registered(PHONE_A);
    const upload = await startUpload(owner, 'image/jpeg');

    const res = await request(app)
      .post(`/api/v1/media/uploads/${upload.mediaId}/confirm`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('media_not_uploaded');
  });

  it('marks the row ready once the object is there', async () => {
    const owner = await registered(PHONE_A);
    const mediaId = await uploadAndConfirm(owner, 'image/jpeg');

    const row = await pglite.query<{ status: string; confirmed_at: unknown }>(
      `SELECT status, confirmed_at FROM post_media WHERE id = $1`,
      [mediaId]
    );
    expect(row.rows[0]?.status).toBe('ready');
    expect(row.rows[0]?.confirmed_at).not.toBeNull();
  });

  it('is idempotent: confirming twice is not an error', async () => {
    // A client that retried after a dropped response must not have its upload
    // rejected for having succeeded the first time.
    const owner = await registered(PHONE_A);
    const mediaId = await uploadAndConfirm(owner, 'image/jpeg');

    const res = await request(app)
      .post(`/api/v1/media/uploads/${mediaId}/confirm`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(200);
  });

  it('refuses an object whose size differs from the declared one', async () => {
    const owner = await registered(PHONE_A);
    const upload = await startUpload(owner, 'image/jpeg', 2048);

    // Real S3 enforces this through the signed ContentLength; the fake says so
    // directly. Either way the row must not become claimable.
    store.put('image/jpeg', 999);

    const res = await request(app)
      .post(`/api/v1/media/uploads/${upload.mediaId}/confirm`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(409);
    expect(res.body.error).toBe('media_size_mismatch');
  });

  it('hides another account\'s upload', async () => {
    const owner = await registered(PHONE_A);
    const stranger = await registered(PHONE_B);
    const upload = await startUpload(owner, 'image/jpeg');
    store.put();

    const res = await request(app)
      .post(`/api/v1/media/uploads/${upload.mediaId}/confirm`)
      .set(authed(stranger.accessToken));

    // Not-found rather than forbidden: whether that id exists is not something
    // a stranger gets to learn.
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('media_not_found');
  });

  it('requires a session for every media route', async () => {
    const res = await request(app)
      .post('/api/v1/media/uploads')
      .send({ contentType: 'image/jpeg', byteSize: 1024 });

    expect(res.status).toBe(401);
  });
});

// ── Serving media ───────────────────────────────────────────────────────

describe('media read URLs', () => {
  it('serves a preview URL for the owner\'s own unconfirmed upload', async () => {
    // This is what lets a composer preview a file before publishing it.
    const owner = await registered(PHONE_A);
    const upload = await startUpload(owner, 'image/jpeg');
    store.put();

    const res = await request(app)
      .get(`/api/v1/media/${upload.mediaId}/url`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(200);
    expect(res.body.url).toContain('X-Amz-Signature');
  });

  it('does not serve someone else\'s unconfirmed upload', async () => {
    const owner = await registered(PHONE_A);
    const stranger = await registered(PHONE_B);
    const upload = await startUpload(owner, 'image/jpeg');

    const res = await request(app)
      .get(`/api/v1/media/${upload.mediaId}/url`)
      .set(authed(stranger.accessToken));

    expect(res.status).toBe(404);
  });

  it('serves a published post\'s media to any signed-in reader', async () => {
    const owner = await registered(PHONE_A);
    const reader = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Media Serving' });
    const mediaId = await uploadAndConfirm(owner, 'image/jpeg');
    const post = await published(owner, channel.id, { body: 'photo', mediaIds: [mediaId] });

    const res = await request(app)
      .get(`/api/v1/media/${mediaId}/url`)
      .set(authed(reader.accessToken));

    expect(res.status).toBe(200);
    // The URL is signed per request, so it is not the one embedded in the
    // post payload — a cached payload cannot hold a dead link.
    expect(res.body.url).toContain('X-Amz-Signature');
    expect(post.media[0]?.url).toContain('X-Amz-Signature');
  });

  it('stops serving media whose post was removed', async () => {
    // The property that makes a removal mean something (§25). The object is
    // still in the bucket — §34's cleanup owns that — but it is no longer
    // served, which is what a reader experiences.
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Removed Media' });
    const mediaId = await uploadAndConfirm(owner, 'image/jpeg');
    const post = await published(owner, channel.id, { mediaIds: [mediaId] });

    await request(app)
      .delete(`/api/v1/channels/${channel.id}/posts/${post.id}`)
      .set(authed(owner.accessToken));

    const res = await request(app)
      .get(`/api/v1/media/${mediaId}/url`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('media_forbidden');
  });

  it('stops serving a suspended channel\'s media to followers', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Suspended Media' });
    await follow(follower, channel.id);

    const mediaId = await uploadAndConfirm(owner, 'image/jpeg');
    await published(owner, channel.id, { mediaIds: [mediaId] });

    await pglite.query(`UPDATE channels SET status = 'suspended' WHERE id = $1`, [channel.id]);

    const blocked = await request(app)
      .get(`/api/v1/media/${mediaId}/url`)
      .set(authed(follower.accessToken));
    expect(blocked.status).toBe(403);

    // The owner keeps read access, because a moderation action must not leave
    // them unable to see what they are being asked to review.
    const asOwner = await request(app)
      .get(`/api/v1/media/${mediaId}/url`)
      .set(authed(owner.accessToken));
    expect(asOwner.status).toBe(200);
  });

  it('treats an unknown or malformed media id as not found', async () => {
    const owner = await registered(PHONE_A);

    for (const id of [randomUUID(), 'not-a-uuid', '..']) {
      const res = await request(app)
        .get(`/api/v1/media/${encodeURIComponent(id)}/url`)
        .set(authed(owner.accessToken));

      expect(res.status, id).toBe(404);
    }
  });
});

// ── Publishing ──────────────────────────────────────────────────────────

describe('publishing', () => {
  it('publishes a text post and updates the channel counters', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Text Posts' });

    const post = await published(owner, channel.id, { body: '  Hello Good Post  ' });

    expect(post.type).toBe('text');
    // Trimmed on the way in, so a client cannot store a body that is only
    // whitespace and satisfy the schema's non-empty CHECK by accident.
    expect(post.body).toBe('Hello Good Post');
    expect(post.media).toEqual([]);

    const after = await readChannel(owner, channel.id);
    expect(after.postCount).toBe(1);
    expect(after.lastPostAt).not.toBeNull();
  });

  it('derives the post type from the media, ignoring a claimed type', async () => {
    // A client saying `type: 'video'` while attaching a JPEG would describe its
    // own post wrongly, and every reader would render it wrongly.
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Derived Type' });
    const mediaId = await uploadAndConfirm(owner, 'image/jpeg');

    const post = await published(owner, channel.id, {
      body: 'a photo',
      type: 'video',
      mediaIds: [mediaId],
    });

    expect(post.type).toBe('image');
    expect(post.media).toHaveLength(1);
    expect(post.media[0]?.kind).toBe('image');
  });

  it('keeps the media in the order the request asked for', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Ordered Media' });
    const first = await uploadAndConfirm(owner, 'image/jpeg');
    const second = await uploadAndConfirm(owner, 'image/png');

    const post = await published(owner, channel.id, { mediaIds: [second, first] });

    expect(post.media.map((m) => m.id)).toEqual([second, first]);
    expect(post.media.map((m) => m.position)).toEqual([0, 1]);
  });

  it('refuses a media post whose upload was never confirmed', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Unconfirmed' });
    const upload = await startUpload(owner, 'image/jpeg');
    store.put();

    const res = await publishPost(owner, channel.id, { mediaIds: [upload.mediaId] });

    expect(res.status).toBe(409);
    expect(res.body.error).toBe('media_not_ready');
  });

  it('refuses media that belongs to someone else', async () => {
    const owner = await registered(PHONE_A);
    const stranger = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Stolen Upload' });
    const strangerMedia = await uploadAndConfirm(stranger, 'image/jpeg');

    const res = await publishPost(owner, channel.id, { mediaIds: [strangerMedia] });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('unknown_media');
  });

  it('refuses an upload that is already part of another post', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Double Claim' });
    const mediaId = await uploadAndConfirm(owner, 'image/jpeg');
    await published(owner, channel.id, { mediaIds: [mediaId] });

    const res = await publishPost(owner, channel.id, { body: 'again', mediaIds: [mediaId] });

    expect(res.status).toBe(409);
    expect(res.body.error).toBe('media_already_used');
  });

  it('refuses mixed media kinds in one post', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Mixed Media' });
    const image = await uploadAndConfirm(owner, 'image/jpeg');
    const audio = await uploadAndConfirm(owner, 'audio/mpeg');

    const res = await publishPost(owner, channel.id, { mediaIds: [image, audio] });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('mixed_media');
  });

  it('bounds the media count and rejects a repeated id', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Too Many' });

    const tooMany = await publishPost(owner, channel.id, {
      mediaIds: Array.from({ length: env.MAX_POST_MEDIA + 1 }, () => randomUUID()),
    });
    expect(tooMany.status).toBe(400);
    expect(tooMany.body.error).toBe('too_many_media');

    const duplicated = await publishPost(owner, channel.id, {
      mediaIds: [randomUUID(), randomUUID()].flatMap((id) => [id, id]),
    });
    expect(duplicated.status).toBe(400);
  });

  it('rejects an empty post and a non-http link', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Bad Content' });

    const empty = await publishPost(owner, channel.id, { body: '   ' });
    expect(empty.status).toBe(400);
    expect(empty.body.error).toBe('empty_post');

    const javascriptish = await publishPost(owner, channel.id, { linkUrl: 'javascript:alert(1)' });
    expect(javascriptish.status).toBe(400);
    expect(javascriptish.body.error).toBe('invalid_link');

    const notAUrl = await publishPost(owner, channel.id, { linkUrl: 'hello world' });
    expect(notAUrl.status).toBe(400);
  });

  it('publishes a link post', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Links' });

    const post = await published(owner, channel.id, {
      linkUrl: 'https://example.test/article',
      linkTitle: 'An article',
    });

    expect(post.type).toBe('link');
    expect(post.linkUrl).toBe('https://example.test/article');
    expect(post.linkTitle).toBe('An article');
  });

  it('bounds the text length', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Long Text' });

    const res = await publishPost(owner, channel.id, {
      body: 'x'.repeat(env.MAX_TEXT_LENGTH + 1),
    });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('text_too_long');
  });

  it('refuses a publisher who does not administer the channel (§32)', async () => {
    const owner = await registered(PHONE_A);
    const stranger = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Not Yours' });

    const res = await publishPost(stranger, channel.id, { body: 'hi' });

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('post_forbidden');
  });

  it('lets an editor publish, and refuses a responder', async () => {
    const owner = await registered(PHONE_A);
    const editor = await registered(PHONE_B);
    const responder = await registered(PHONE_C);
    const channel = await createChannel(owner, { name: 'Roles' });

    await grantRole(channel.id, editor.user.id, 'editor');
    await grantRole(channel.id, responder.user.id, 'responder');

    expect((await publishPost(editor, channel.id, { body: 'from an editor' })).status).toBe(201);

    // A responder answers follower messages (§16). Broadcasting as the channel
    // is a different power from replying on its behalf.
    const refused = await publishPost(responder, channel.id, { body: 'from a responder' });
    expect(refused.status).toBe(403);
  });

  it('refuses to publish to a suspended channel', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Suspended Publisher' });
    await pglite.query(`UPDATE channels SET status = 'suspended' WHERE id = $1`, [channel.id]);

    const res = await publishPost(owner, channel.id, { body: 'hello' });

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('channel_unavailable');
  });

  it('treats an unknown channel as not found', async () => {
    const owner = await registered(PHONE_A);
    const res = await publishPost(owner, randomUUID(), { body: 'hello' });
    expect(res.status).toBe(404);
  });

  it('requires a session to publish', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Closed' });

    const res = await request(app)
      .post(`/api/v1/channels/${channel.id}/posts`)
      .send({ body: 'anonymous' });

    expect(res.status).toBe(401);
  });
});

// ── Channel history ─────────────────────────────────────────────────────

describe('channel history', () => {
  it('returns an empty page for a channel with no posts', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Empty History' });

    const res = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(200);
    expect(res.body.items).toEqual([]);
    expect(res.body.nextCursor).toBeNull();
  });

  it('pages newest first without repeating or skipping a post', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Paging' });

    const ids: string[] = [];
    for (let i = 0; i < 5; i += 1) {
      const post = await published(owner, channel.id, { body: `post ${i}` });
      // Explicit ages, so the ordering under test does not depend on how fast
      // five requests happen to run. The first published is the newest, which
      // makes `ids` the expected newest-first order.
      await agePost(post.id, i + 1);
      ids.push(post.id);
    }

    const seen: string[] = [];
    let cursor: string | null = null;
    for (let page = 0; page < 5; page += 1) {
      const url: string = `/api/v1/channels/${channel.id}/posts?limit=2${
        cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''
      }`;
      const res = await request(app).get(url).set(authed(owner.accessToken));

      expect(res.status).toBe(200);
      for (const item of res.body.items as { id: string }[]) seen.push(item.id);

      cursor = res.body.nextCursor as string | null;
      if (!cursor) break;
    }

    expect(seen).toEqual(ids);
    expect(new Set(seen).size).toBe(seen.length);
  });

  it('refuses a malformed cursor instead of silently restarting', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Bad Cursor' });

    const res = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts?cursor=not-a-cursor`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_cursor');
  });

  it('excludes a removed post', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Removal' });
    const kept = await published(owner, channel.id, { body: 'kept' });
    const removed = await published(owner, channel.id, { body: 'removed' });

    await request(app)
      .delete(`/api/v1/channels/${channel.id}/posts/${removed.id}`)
      .set(authed(owner.accessToken));

    const res = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken));

    expect((res.body.items as { id: string }[]).map((p) => p.id)).toEqual([kept.id]);
  });

  it('reports whether the viewer may manage each post', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Manage Flag' });
    await follow(follower, channel.id);
    await published(owner, channel.id, { body: 'a post' });

    const asOwner = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken));
    expect(asOwner.body.items[0].viewerCanManage).toBe(true);

    const asFollower = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(follower.accessToken));
    expect(asFollower.body.items[0].viewerCanManage).toBe(false);
  });

  it('hides a suspended channel\'s history from followers but not its admins', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Suspended History' });
    await follow(follower, channel.id);
    await published(owner, channel.id, { body: 'still readable by the owner' });

    await pglite.query(`UPDATE channels SET status = 'suspended' WHERE id = $1`, [channel.id]);

    const asFollower = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(follower.accessToken));
    expect(asFollower.status).toBe(403);
    expect(asFollower.body.error).toBe('channel_unavailable');

    const asOwner = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken));
    expect(asOwner.status).toBe(200);
  });

  it('hides a banned channel\'s history from everyone', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Banned History' });
    await published(owner, channel.id, { body: 'gone' });

    await pglite.query(`UPDATE channels SET status = 'banned' WHERE id = $1`, [channel.id]);

    const res = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(403);
  });

  it('is not readable without a session', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Closed History' });

    const res = await request(app).get(`/api/v1/channels/${channel.id}/posts`);
    expect(res.status).toBe(401);
  });
});

// ── The aggregated feed ─────────────────────────────────────────────────

describe('the aggregated feed', () => {
  async function feed(session: Session, query = '') {
    const res = await request(app)
      .get(`/api/v1/posts/feed${query}`)
      .set(authed(session.accessToken));
    expect(res.status).toBe(200);
    return res.body as { items: PostPayload[]; nextCursor: string | null };
  }

  it('carries the channel each post belongs to', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Feed Channel' });
    await follow(follower, channel.id);
    await published(owner, channel.id, { body: 'hello feed' });

    const page = await feed(follower);

    expect(page.items).toHaveLength(1);
    expect(page.items[0]?.body).toBe('hello feed');
    expect(page.items[0]?.channel?.id).toBe(channel.id);
    expect(page.items[0]?.channel?.name).toBe('Feed Channel');
    // Owner and follower identity are not part of a feed row (§12, §38).
    expect(Object.keys(page.items[0]?.channel ?? {})).toEqual([
      'id',
      'slug',
      'name',
      'iconObjectKey',
    ]);
  });

  it('excludes channels the viewer does not follow', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const followed = await createChannel(owner, { name: 'Followed' });
    const unfollowed = await createChannel(owner, { name: 'Not Followed' });

    await follow(follower, followed.id);
    await published(owner, followed.id, { body: 'visible' });
    await published(owner, unfollowed.id, { body: 'hidden' });

    const page = await feed(follower);

    expect(page.items.map((p) => p.body)).toEqual(['visible']);
  });

  it('excludes a blocked channel', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Blocked Feed' });
    await follow(follower, channel.id);
    await published(owner, channel.id, { body: 'before blocking' });
    expect((await feed(follower)).items).toHaveLength(1);

    await request(app)
      .post(`/api/v1/channels/${channel.id}/block`)
      .set(authed(follower.accessToken));

    // Blocking has to remove a channel everywhere at once, and a feed is the
    // surface where forgetting it would be most visible.
    expect((await feed(follower)).items).toHaveLength(0);
  });

  it('excludes a suspended channel', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Suspended Feed' });
    await follow(follower, channel.id);
    await published(owner, channel.id, { body: 'gone from the feed' });

    await pglite.query(`UPDATE channels SET status = 'suspended' WHERE id = $1`, [channel.id]);

    expect((await feed(follower)).items).toHaveLength(0);
  });

  it('excludes a removed post', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Feed Removal' });
    await follow(follower, channel.id);
    const post = await published(owner, channel.id, { body: 'removed soon' });

    await request(app)
      .delete(`/api/v1/channels/${channel.id}/posts/${post.id}`)
      .set(authed(owner.accessToken));

    expect((await feed(follower)).items).toHaveLength(0);
  });

  it('pages across the mixed channels without repeating a post', async () => {
    const owner = await registered(PHONE_A);
    const follower = await registered(PHONE_B);
    const first = await createChannel(owner, { name: 'Feed One' });
    const second = await createChannel(owner, { name: 'Feed Two' });
    await follow(follower, first.id);
    await follow(follower, second.id);

    const ids: string[] = [];
    for (let i = 0; i < 4; i += 1) {
      const post = await published(owner, i % 2 === 0 ? first.id : second.id, { body: `p${i}` });
      await agePost(post.id, i + 1);
      ids.push(post.id);
    }

    const seen: string[] = [];
    let cursor: string | null = null;
    for (let page = 0; page < 4; page += 1) {
      const query: string = `?limit=3${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
      const result = await feed(follower, query);
      seen.push(...result.items.map((p) => p.id));
      cursor = result.nextCursor;
      if (!cursor) break;
    }

    expect(seen).toEqual(ids);
  });

  it('is not readable without a session', async () => {
    expect((await request(app).get('/api/v1/posts/feed')).status).toBe(401);
  });
});

// ── Editing and removing ────────────────────────────────────────────────

describe('editing and removing posts', () => {
  it('edits the body and records that it was edited', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Editing' });
    const post = await published(owner, channel.id, { body: 'typo' });

    const res = await request(app)
      .patch(`/api/v1/channels/${channel.id}/posts/${post.id}`)
      .set(authed(owner.accessToken))
      .send({ body: 'fixed' });

    expect(res.status).toBe(200);
    expect(res.body.post.body).toBe('fixed');
    expect(res.body.post.editedAt).not.toBeNull();
    expect(res.body.post.isEdited).toBe(true);
  });

  it('refuses an edit that changes nothing', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Empty Patch' });
    const post = await published(owner, channel.id, { body: 'unchanged' });

    const res = await request(app)
      .patch(`/api/v1/channels/${channel.id}/posts/${post.id}`)
      .set(authed(owner.accessToken))
      .send({});

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('empty_update');
  });

  it('refuses to blank a text post', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Blank Text' });
    const post = await published(owner, channel.id, { body: 'content' });

    const res = await request(app)
      .patch(`/api/v1/channels/${channel.id}/posts/${post.id}`)
      .set(authed(owner.accessToken))
      .send({ body: '   ' });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('empty_post');
  });

  it('closes the edit window', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Edit Window' });
    const post = await published(owner, channel.id, { body: 'old' });

    // One day past the configured window, read from the config so the test does
    // not hardcode a value an operator may change.
    await agePost(post.id, (env.EDIT_WINDOW_DAYS + 1) * 24 * 60);

    const res = await request(app)
      .patch(`/api/v1/channels/${channel.id}/posts/${post.id}`)
      .set(authed(owner.accessToken))
      .send({ body: 'too late' });

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('edit_window_closed');
  });

  it('does not let another channel\'s admin edit this post', async () => {
    const ownerA = await registered(PHONE_A);
    const ownerB = await registered(PHONE_B);
    const channelA = await createChannel(ownerA, { name: 'Owner A' });
    const channelB = await createChannel(ownerB, { name: 'Owner B' });
    const post = await published(ownerB, channelB.id, { body: 'B only' });

    // Correct channel, but the caller does not administer it.
    const wrongAdmin = await request(app)
      .patch(`/api/v1/channels/${channelB.id}/posts/${post.id}`)
      .set(authed(ownerA.accessToken))
      .send({ body: 'hijacked' });
    expect(wrongAdmin.status).toBe(403);

    // Correct role, but the post belongs to another channel. Not-found rather
    // than forbidden: whether it exists elsewhere is not something this caller
    // gets to learn.
    const wrongChannel = await request(app)
      .patch(`/api/v1/channels/${channelA.id}/posts/${post.id}`)
      .set(authed(ownerA.accessToken))
      .send({ body: 'hijacked' });
    expect(wrongChannel.status).toBe(404);
  });

  it('soft-deletes, decrements the counter and hides the post', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Deleting' });
    const post = await published(owner, channel.id, { body: 'temporary' });
    expect((await readChannel(owner, channel.id)).postCount).toBe(1);

    const res = await request(app)
      .delete(`/api/v1/channels/${channel.id}/posts/${post.id}`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(200);

    const row = await pglite.query<{ deleted_at: unknown }>(
      `SELECT deleted_at FROM posts WHERE id = $1`,
      [post.id]
    );
    // Soft, not hard: §18 models moderation as reversible, so the row must
    // survive.
    expect(row.rows).toHaveLength(1);
    expect(row.rows[0]?.deleted_at).not.toBeNull();

    const after = await readChannel(owner, channel.id);
    expect(after.postCount).toBe(0);
    expect(after.lastPostAt).toBeNull();
  });

  it('points last_post_at at the newest remaining post', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Recounted' });
    const older = await published(owner, channel.id, { body: 'older' });
    const newer = await published(owner, channel.id, { body: 'newer' });

    await request(app)
      .delete(`/api/v1/channels/${channel.id}/posts/${newer.id}`)
      .set(authed(owner.accessToken));

    const row = await pglite.query<{ last_post_at: Date | null }>(
      `SELECT last_post_at FROM channels WHERE id = $1`,
      [channel.id]
    );
    const createdAt = await pglite.query<{ created_at: Date }>(
      `SELECT created_at FROM posts WHERE id = $1`,
      [older.id]
    );

    // A stale `last_post_at` would misorder discovery and misreport unread.
    expect(row.rows[0]?.last_post_at?.getTime()).toBe(createdAt.rows[0]?.created_at.getTime());
  });

  it('refuses a second delete, so the counter cannot drift', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Delete Twice' });
    const post = await published(owner, channel.id, { body: 'once' });

    const url = `/api/v1/channels/${channel.id}/posts/${post.id}`;
    await request(app).delete(url).set(authed(owner.accessToken));

    const again = await request(app).delete(url).set(authed(owner.accessToken));
    expect(again.status).toBe(404);
  });

  it('refuses a delete from a non-admin, and requires a session', async () => {
    const owner = await registered(PHONE_A);
    const stranger = await registered(PHONE_B);
    const channel = await createChannel(owner, { name: 'Protected' });
    const post = await published(owner, channel.id, { body: 'safe' });
    const url = `/api/v1/channels/${channel.id}/posts/${post.id}`;

    expect((await request(app).delete(url).set(authed(stranger.accessToken))).status).toBe(403);
    expect((await request(app).delete(url)).status).toBe(401);
  });
});

// ── Retention and cleanup ───────────────────────────────────────────────

describe('the abandoned-upload sweep', () => {
  const window = () => env.UPLOAD_CLAIM_WINDOW_MINUTES;

  async function ageUpload(mediaId: string, minutes: number): Promise<void> {
    await pglite.query(
      `UPDATE post_media SET created_at = now() - ($2 || ' minutes')::interval WHERE id = $1`,
      [mediaId, String(minutes)]
    );
  }

  it('removes an object nothing ever claimed', async () => {
    // Without this, every abandoned composer session leaves an object in the
    // bucket forever — the one leak M3 could create.
    const owner = await registered(PHONE_A);
    const upload = await startUpload(owner, 'image/jpeg');
    const key = store.put();
    await ageUpload(upload.mediaId, window() + 5);

    const result = await sweepAbandonedUploads(database, store, window());

    expect(result.removed).toBe(1);
    expect(store.removed).toEqual([key]);
    expect(store.has(key)).toBe(false);

    const rows = await pglite.query(`SELECT 1 FROM post_media WHERE id = $1`, [upload.mediaId]);
    expect(rows.rows).toHaveLength(0);
  });

  it('leaves an upload that is still inside the window', async () => {
    const owner = await registered(PHONE_A);
    const upload = await startUpload(owner, 'image/jpeg');
    const key = store.put();

    const result = await sweepAbandonedUploads(database, store, window());

    expect(result.removed).toBe(0);
    expect(store.has(key)).toBe(true);
  });

  it('never removes media a post has claimed, however old', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Claimed Media' });
    const mediaId = await uploadAndConfirm(owner, 'image/jpeg');
    const key = store.lastIssuedKey();
    await published(owner, channel.id, { body: 'mine', mediaIds: [mediaId] });
    await ageUpload(mediaId, window() * 24);

    const result = await sweepAbandonedUploads(database, store, window());

    expect(result.removed).toBe(0);
    expect(store.has(key)).toBe(true);
  });

  it('runs from the retention job without touching a claimed post', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Job Path' });
    const stale = await startUpload(owner, 'image/jpeg');
    const staleKey = store.put();
    const kept = await uploadAndConfirm(owner, 'image/png');
    const keptKey = store.lastIssuedKey();
    await published(owner, channel.id, { body: 'kept', mediaIds: [kept] });
    await ageUpload(stale.mediaId, window() + 1);
    await ageUpload(kept, window() * 24);

    const result = await runRetentionSweep(database, store);

    expect(result.removed).toBe(1);
    expect(store.has(staleKey)).toBe(false);
    expect(store.has(keptKey)).toBe(true);
  });
});

// ── The retention window (§11) ──────────────────────────────────────────

describe('the retention window', () => {
  it('makes a post past the window unreadable, and removes what is behind it', async () => {
    // One sweep does both halves, and that is the product rule rather than an
    // accident of the grace period: the server keeps nothing past the window,
    // so an expired post is unreadable immediately and the row and its S3
    // object go on the same run (§11, §34).
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Window' });
    const fresh = await published(owner, channel.id, { body: 'fresh' });
    const mediaId = await uploadAndConfirm(owner, 'image/jpeg');
    const key = store.lastIssuedKey();
    const old = await published(owner, channel.id, { body: 'old', mediaIds: [mediaId] });

    await agePost(old.id, (env.GOODPOST_HISTORY_DAYS + 1) * 24 * 60);
    const swept = await runRetentionSweep(database, store);

    expect(swept.expired, 'the sweep should expire it').toBeGreaterThan(0);
    expect(swept.purged, 'and purge it on the same run').toBeGreaterThan(0);
    expect(swept.objectsRemoved).toBeGreaterThan(0);

    const res = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken));
    expect((res.body.items as { id: string }[]).map((p) => p.id)).toEqual([fresh.id]);

    const rows = await pglite.query<{ n: number }>(
      `SELECT count(*)::int AS n FROM posts WHERE id = $1`,
      [old.id]
    );
    expect(rows.rows[0]?.n, 'the row must not be left behind').toBe(0);
    expect(store.has(key), 'the object must not be left behind').toBe(false);
  });

  it('leaves a post inside the window alone', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Inside' });
    const post = await published(owner, channel.id, { body: 'here' });

    await agePost(post.id, (env.GOODPOST_HISTORY_DAYS - 1) * 24 * 60);
    await runRetentionSweep(database, store);

    const res = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken));
    expect((res.body.items as { id: string }[]).map((p) => p.id)).toEqual([post.id]);
  });
});

// ── A deployment with no bucket ─────────────────────────────────────────

describe('a deployment with no bucket configured', () => {
  let noBucketApp: Express;

  beforeAll(() => {
    // The same database, the same code, and no S3: this is what a deployment
    // looks like before the credentials arrive, and it must stay usable for
    // everything that does not need storage.
    noBucketApp = buildApp({
      database,
      verifier: fakeVerifier(),
      store: new UnconfiguredObjectStore(),
    });
  });

  it('refuses uploads with a code the client can word', async () => {
    const owner = await registered(PHONE_A);

    const res = await request(noBucketApp)
      .post('/api/v1/media/uploads')
      .set(authed(owner.accessToken))
      .send({ contentType: 'image/jpeg', byteSize: 1024 });

    // 503 rather than 500: the request was well-formed and a text post on this
    // deployment still works, which is a materially different instruction from
    // "the server broke".
    expect(res.status).toBe(503);
    expect(res.body.error).toBe('media_unavailable');
  });

  it('still publishes and reads text posts', async () => {
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'No Bucket' });

    const res = await request(noBucketApp)
      .post(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken))
      .send({ body: 'text needs no bucket' });
    expect(res.status).toBe(201);

    const history = await request(noBucketApp)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken));
    expect(history.status).toBe(200);
    expect(history.body.items[0].body).toBe('text needs no bucket');
  });

  it('renders media with a null URL instead of failing the page', async () => {
    // Created through the configured app, read through the deployment with no
    // bucket: one unservable asset must not take the feed down with it.
    const owner = await registered(PHONE_A);
    const channel = await createChannel(owner, { name: 'Half Configured' });
    const mediaId = await uploadAndConfirm(owner, 'image/jpeg');
    await published(owner, channel.id, { body: 'has media', mediaIds: [mediaId] });

    const res = await request(noBucketApp)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken));

    expect(res.status).toBe(200);
    expect(res.body.items[0].media).toHaveLength(1);
    expect(res.body.items[0].media[0].url).toBeNull();
  });
});

// ── Telling an absent object from a refused request ─────────────────────

/**
 * The two S3 outcomes the upload lifecycle reads, pinned to the shapes AWS
 * really sends.
 *
 * These exist because the distinction is not guessable: a HEAD for a missing
 * key comes back 404 only when the caller may list the bucket, and otherwise
 * arrives as an `Unknown`-named error with status 403. Matching on the error
 * name alone therefore classifies the commonest case as "unknown failure" —
 * which is how a live deployment answered 500 to a client that had simply not
 * uploaded yet.
 */
describe('classifying an S3 HEAD that did not return metadata', () => {
  it('reads a missing object as absent, not as a failure', () => {
    expect(isNotFound({ name: 'NotFound', $metadata: { httpStatusCode: 404 } })).toBe(true);
    expect(isNotFound({ name: 'NoSuchKey' })).toBe(true);
    expect(isNotFound({ name: 'NotFound' })).toBe(true);
    // A shape carrying only the status must still count. Both orders are real:
    // the SDK reports `NotFound` for a HEAD, and 404 for some HTTP paths.
    expect(isNotFound({ $metadata: { httpStatusCode: 404 } })).toBe(true);
  });

  it('does not read a refused request as absent', () => {
    // The one that matters. This is what a missing key looks like on a bucket
    // policy without s3:ListBucket, and treating it as absent would tell a user
    // their upload failed when the file may be sitting in the bucket.
    expect(isNotFound({ name: 'Unknown', $metadata: { httpStatusCode: 403 } })).toBe(false);
    expect(isAccessDenied({ name: 'Unknown', $metadata: { httpStatusCode: 403 } })).toBe(true);
    expect(isAccessDenied({ name: 'AccessDenied', $metadata: { httpStatusCode: 403 } })).toBe(true);
    // Status alone, because the name is not reliable for this case either.
    expect(isAccessDenied({ $metadata: { httpStatusCode: 403 } })).toBe(true);
  });

  it('leaves anything else unclassified, so it stays a server fault', () => {
    for (const err of [
      { name: 'ThrottlingException', $metadata: { httpStatusCode: 429 } },
      { name: 'NetworkingError' },
      new Error('socket hang up'),
      undefined,
    ]) {
      expect(isNotFound(err)).toBe(false);
      expect(isAccessDenied(err)).toBe(false);
    }
  });
});
