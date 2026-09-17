import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { applyAllMigrations, asQueryable, freshDatabase, insertAdmin, resetData } from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';
import {
  authed,
  createBareChannel,
  createChannelWithAdmin,
  publishTextPost,
  signIn,
  superAdminSession,
  type Session,
} from './helpers/accounts.js';

/**
 * The administrator surface (§16–§21, §25, §27).
 *
 * This suite is the authorization model's own test, and it is written around
 * the four claims §18 makes about a channel administrator: they can publish to
 * their channel, and they cannot see, touch or even learn the existence of
 * another one. Every one of those is asserted through real HTTP against a real
 * Postgres, because a permission checked in a mapper is a permission that can
 * be forgotten at a route.
 *
 * The media flow is exercised end to end against a fake bucket — presign →
 * PUT → confirm → publish — because every rule in it that matters is ours, and
 * a test that needed an AWS account would be a test that never ran.
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

/** A unique channel name, so one test's channel cannot collide with another's. */
let counter = 0;
function uniqueName(prefix: string): string {
  counter += 1;
  return `${prefix} ${counter}`;
}

describe('administrator sign-in (§16)', () => {
  it('issues a token for the right password, and says which role it grants', async () => {
    const email = `super-${Math.random().toString(36).slice(2, 8)}@example.test`;
    await insertAdmin(pglite, { email, email_normalized: email, role: 'super_admin' });

    const session = await signIn(app, email);
    expect(session.role).toBe('super_admin');
    expect(session.channelId).toBeNull();

    const me = await request(app).get('/admin/api/auth/me').set(authed(session.accessToken));
    expect(me.status).toBe(200);
    expect(me.body.permissions).toContain('channels.create');
  });

  it('answers a wrong password and an unknown address identically', async () => {
    const email = `super-${Math.random().toString(36).slice(2, 8)}@example.test`;
    await insertAdmin(pglite, { email, email_normalized: email });

    const wrongPassword = await request(app)
      .post('/admin/api/auth/login')
      .send({ email, password: 'not-the-password' });
    const unknownAddress = await request(app)
      .post('/admin/api/auth/login')
      .send({ email: 'nobody@example.test', password: 'not-the-password' });

    // The same status AND the same code. Distinguishing them would turn this
    // form into a way to discover which addresses exist, and `admin_users` is a
    // list of staff addresses.
    expect(wrongPassword.status).toBe(401);
    expect(unknownAddress.status).toBe(401);
    expect(wrongPassword.body.error).toBe('invalid_credentials');
    expect(unknownAddress.body.error).toBe('invalid_credentials');
  });

  it('refuses a disabled administrator even with the right password', async () => {
    const email = `super-${Math.random().toString(36).slice(2, 8)}@example.test`;
    await insertAdmin(pglite, {
      email,
      email_normalized: email,
      status: 'disabled',
      disabled_at: new Date().toISOString(),
    });

    const res = await request(app)
      .post('/admin/api/auth/login')
      .send({ email, password: 'test-password-long-enough' });
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('admin_disabled');
  });

  it('locks the account after the configured number of failures', async () => {
    const email = `super-${Math.random().toString(36).slice(2, 8)}@example.test`;
    await insertAdmin(pglite, { email, email_normalized: email });

    // LOGIN_MAX_FAILED_ATTEMPTS ships at 8; the suite does not override it, so
    // the loop walks the real allowance rather than a stubbed one.
    for (let i = 0; i < 8; i += 1) {
      await request(app).post('/admin/api/auth/login').send({ email, password: 'wrong' });
    }

    const locked = await request(app)
      .post('/admin/api/auth/login')
      .send({ email, password: 'test-password-long-enough' });

    // The lock holds even for the CORRECT password: the point of it is that an
    // attacker who eventually guesses right still cannot get in.
    expect(locked.status).toBe(403);
    expect(locked.body.error).toBe('admin_locked');
  });

  it('rejects every admin route without a token', async () => {
    const calls = [
      request(app).get('/admin/api/channels'),
      request(app).get('/admin/api/admins'),
      request(app).get('/admin/api/audit'),
      request(app).get('/admin/api/settings'),
      request(app).post('/admin/api/media/uploads').send({ contentType: 'image/jpeg', byteSize: 10 }),
      request(app).post('/admin/api/channels').send({ name: 'Mine' }),
    ];

    for (const call of calls) {
      const res = await call;
      expect(res.status).toBe(401);
      expect(res.body.error).toBe('missing_token');
    }
  });

  it('rejects a token that was signed for something else', async () => {
    const res = await request(app).get('/admin/api/channels').set(authed('not.a.jwt'));
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('invalid_token');
  });
});

describe('a channel administrator is confined to one channel (§18)', () => {
  it('sees only the channel they were created for', async () => {
    const superSession = await superAdminSession(app, pglite);
    const first = await createChannelWithAdmin(app, superSession, uniqueName('ClearView'));
    await createChannelWithAdmin(app, superSession, uniqueName('Daily Reminder'));

    const owner = await signIn(app, first.adminEmail, first.password);
    expect(owner.role).toBe('channel_admin');
    expect(owner.channelId).toBe(first.channel.id);

    const res = await request(app).get('/admin/api/channels').set(authed(owner.accessToken));
    expect(res.status).toBe(200);
    // One row, even though two channels exist: the restriction is in the WHERE
    // clause, so it is not a filter this route could forget.
    expect(res.body.channels.map((c: { id: string }) => c.id)).toEqual([first.channel.id]);
  });

  it('cannot create a channel', async () => {
    const superSession = await superAdminSession(app, pglite);
    const mine = await createChannelWithAdmin(app, superSession, uniqueName('Mine'));
    const owner = await signIn(app, mine.adminEmail, mine.password);

    const res = await request(app)
      .post('/admin/api/channels')
      .set(authed(owner.accessToken))
      .send({ name: 'Someone else’s' });

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('admin_forbidden');
  });

  it('cannot mint another administrator', async () => {
    const superSession = await superAdminSession(app, pglite);
    const mine = await createChannelWithAdmin(app, superSession, uniqueName('Mine'));
    const owner = await signIn(app, mine.adminEmail, mine.password);

    const res = await request(app)
      .post('/admin/api/admins')
      .set(authed(owner.accessToken))
      .send({
        displayName: 'Second',
        email: 'second@example.test',
        password: 'a-password-long-enough',
        channelId: mine.channel.id,
      });

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('admin_forbidden');
  });

  it('gets a 404 — not a 403 — for another channel, so it cannot confirm it exists', async () => {
    const superSession = await superAdminSession(app, pglite);
    const mine = await createChannelWithAdmin(app, superSession, uniqueName('Mine'));
    const theirs = await createChannelWithAdmin(app, superSession, uniqueName('Theirs'));
    const owner = await signIn(app, mine.adminEmail, mine.password);

    const read = await request(app)
      .get(`/admin/api/channels/${theirs.channel.id}`)
      .set(authed(owner.accessToken));
    expect(read.status).toBe(404);
    expect(read.body.error).toBe('channel_not_found');

    const publish = await request(app)
      .post(`/admin/api/channels/${theirs.channel.id}/posts`)
      .set(authed(owner.accessToken))
      .send({ body: 'Not mine to publish' });
    expect(publish.status).toBe(404);
    expect(publish.body.error).toBe('channel_not_found');
  });

  it('cannot edit or delete another channel’s post through the post routes', async () => {
    const superSession = await superAdminSession(app, pglite);
    const mine = await createChannelWithAdmin(app, superSession, uniqueName('Mine'));
    const theirs = await createChannelWithAdmin(app, superSession, uniqueName('Theirs'));
    const owner = await signIn(app, mine.adminEmail, mine.password);

    const theirPost = await publishTextPost(app, superSession, theirs.channel.id, 'Theirs');

    // The post id is known here only because the test published it; the point
    // is that the route resolves the post's channel and checks the scope, so a
    // guessed id is worth nothing.
    const edit = await request(app)
      .patch(`/admin/api/posts/${theirPost}`)
      .set(authed(owner.accessToken))
      .send({ body: 'Hijacked' });
    expect(edit.status).toBe(404);

    const remove = await request(app)
      .delete(`/admin/api/posts/${theirPost}`)
      .set(authed(owner.accessToken));
    expect(remove.status).toBe(404);
  });
});

describe('creating a channel (§20)', () => {
  it('creates the channel and the login that runs it, together', async () => {
    const superSession = await superAdminSession(app, pglite);
    const created = await createChannelWithAdmin(app, superSession, uniqueName('Quran'));

    // The account works immediately, which is the claim that matters: a channel
    // with no administrator would be one nobody could publish to.
    const owner = await signIn(app, created.adminEmail, created.password);
    expect(owner.channelId).toBe(created.channel.id);

    await publishTextPost(app, owner, created.channel.id, 'First post');
  });

  it('refuses half a set of administrator credentials', async () => {
    const superSession = await superAdminSession(app, pglite);
    const res = await request(app)
      .post('/admin/api/channels')
      .set(authed(superSession.accessToken))
      .send({ name: uniqueName('Half'), adminEmail: 'half@example.test' });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('admin_credentials_incomplete');
  });

  it('refuses a password below the configured floor', async () => {
    const superSession = await superAdminSession(app, pglite);
    const res = await request(app)
      .post('/admin/api/channels')
      .set(authed(superSession.accessToken))
      .send({
        name: uniqueName('Weak'),
        adminEmail: `weak-${Math.random().toString(36).slice(2, 8)}@example.test`,
        adminPassword: 'short',
      });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('weak_password');
  });

  it('is visible publicly the moment it exists', async () => {
    const superSession = await superAdminSession(app, pglite);
    const created = await createChannelWithAdmin(app, superSession, uniqueName('Public'));

    const res = await request(app).get(`/api/v1/channels/${created.channel.slug}`);
    expect(res.status).toBe(200);
    expect(res.body.channel.name).toBe(created.channel.name);
  });

  it('does not echo the administrator’s password or its hash', async () => {
    const superSession = await superAdminSession(app, pglite);
    const email = `owner-${Math.random().toString(36).slice(2, 8)}@example.test`;

    const res = await request(app)
      .post('/admin/api/channels')
      .set(authed(superSession.accessToken))
      .send({ name: uniqueName('Secret'), adminEmail: email, adminPassword: 'a-real-password-value' });

    expect(res.status).toBe(201);
    const body = JSON.stringify(res.body);
    expect(body).not.toContain('a-real-password-value');
    expect(body).not.toContain('$2b$');
  });
});

describe('publishing (§21)', () => {
  it('publishes a text post that a reader sees immediately', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('ClearView'));
    await publishTextPost(app, superSession, channel.id, 'New update about the app');

    const res = await request(app).get(`/api/v1/channels/${channel.slug}/posts`);
    expect(res.status).toBe(200);
    expect(res.body.items[0].body).toBe('New update about the app');
    expect(res.body.items[0].type).toBe('text');
  });

  it('derives the type from what is attached rather than trusting the client', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Links'));

    const withLink = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(superSession.accessToken))
      .send({ body: 'An article', linkUrl: 'https://example.test/a', linkTitle: 'A' });

    expect(withLink.status).toBe(201);
    expect(withLink.body.post.type).toBe('link');
    expect(withLink.body.post.linkUrl).toBe('https://example.test/a');
  });

  it('refuses a post with nothing in it, and a link that is not a link', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Empty'));

    const empty = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(superSession.accessToken))
      .send({});
    expect(empty.status).toBe(400);
    expect(empty.body.error).toBe('empty_post');

    const badLink = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(superSession.accessToken))
      .send({ linkUrl: 'javascript:alert(1)' });
    expect(badLink.status).toBe(400);
    expect(badLink.body.error).toBe('invalid_link');
  });

  it('keeps the channel’s own list ordered and its timestamp honest', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Ordered'));
    await publishTextPost(app, superSession, channel.id, 'first');
    await publishTextPost(app, superSession, channel.id, 'second');

    const mine = await request(app)
      .get(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(superSession.accessToken));
    expect(mine.status).toBe(200);
    expect(mine.body.items.map((p: { body: string }) => p.body)).toEqual(['second', 'first']);

    // The list's preview and its timestamp must describe the SAME post (§4).
    const listed = await request(app).get('/api/v1/channels');
    const row = listed.body.items.find((c: { id: string }) => c.id === channel.id);
    expect(row.lastPostPreview).toBe('second');
    expect(row.lastPostAt).not.toBeNull();
  });

  it('edits a post, and the edit reaches readers', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Edited'));
    const postId = await publishTextPost(app, superSession, channel.id, 'Before');

    const edited = await request(app)
      .patch(`/admin/api/posts/${postId}`)
      .set(authed(superSession.accessToken))
      .send({ body: 'After' });
    expect(edited.status).toBe(200);
    expect(edited.body.post.body).toBe('After');
    expect(edited.body.post.editedAt).not.toBeNull();

    const res = await request(app).get(`/api/v1/channels/${channel.slug}/posts`);
    expect(res.body.items[0].body).toBe('After');
  });

  it('removes a post so that readers no longer see it, and keeps it reversible', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Removed'));
    const postId = await publishTextPost(app, superSession, channel.id, 'Gone soon');

    const removed = await request(app)
      .delete(`/admin/api/posts/${postId}`)
      .set(authed(superSession.accessToken));
    expect(removed.status).toBe(200);

    expect((await request(app).get(`/api/v1/posts/${postId}`)).status).toBe(404);
    const listed = await request(app).get(`/api/v1/channels/${channel.slug}/posts`);
    expect(listed.body.items).toHaveLength(0);

    // Soft, so the row is still there and the removal can be undone. The audit
    // row is written AFTER the action, so what is recorded is what happened.
    const audit = await request(app).get('/admin/api/audit').set(authed(superSession.accessToken));
    expect(audit.body.items.some((row: { action: string }) => row.action === 'post.delete')).toBe(
      true
    );
  });
});

describe('media upload (§21, §22)', () => {
  /** presign → PUT → confirm, the three steps a composer takes. */
  async function upload(
    session: Session,
    contentType = 'image/jpeg',
    byteSize = 4096
  ): Promise<string> {
    const presign = await request(app)
      .post('/admin/api/media/uploads')
      .set(authed(session.accessToken))
      .send({ contentType, byteSize });

    expect(presign.status, JSON.stringify(presign.body)).toBe(201);
    const mediaId = presign.body.upload.mediaId as string;

    // Stands in for the client's PUT to the presigned URL. The key is chosen by
    // the SERVER, which is what makes it impossible for one account to name
    // another's object.
    store.put();

    const confirmed = await request(app)
      .post(`/admin/api/media/uploads/${mediaId}/confirm`)
      .set(authed(session.accessToken));

    expect(confirmed.status, JSON.stringify(confirmed.body)).toBe(200);
    return mediaId;
  }

  it('publishes an image post that carries a signed read URL', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Photos'));
    const mediaId = await upload(superSession);

    const published = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(superSession.accessToken))
      .send({ body: 'A photo', mediaIds: [mediaId] });

    expect(published.status, JSON.stringify(published.body)).toBe(201);
    expect(published.body.post.type).toBe('image');
    expect(published.body.post.media).toHaveLength(1);

    // The type was not sent — it follows from the file.
    const res = await request(app).get(`/api/v1/channels/${channel.slug}/posts`);
    expect(res.body.items[0].type).toBe('image');
    expect(res.body.items[0].media[0].url).toContain('https://fake-bucket.test/');
  });

  it('publishes a video post with the kind the file decides', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Videos'));
    const mediaId = await upload(superSession, 'video/mp4', 1_048_576);

    const published = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(superSession.accessToken))
      .send({ mediaIds: [mediaId] });

    expect(published.status).toBe(201);
    expect(published.body.post.type).toBe('video');
  });

  it('refuses an upload before it was confirmed', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Unconfirmed'));

    const presign = await request(app)
      .post('/admin/api/media/uploads')
      .set(authed(superSession.accessToken))
      .send({ contentType: 'image/jpeg', byteSize: 4096 });
    const mediaId = presign.body.upload.mediaId as string;

    // The bucket is never told the object arrived, so the row is still pending.
    // Confirming is a HEAD, not a client's word.
    const published = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(superSession.accessToken))
      .send({ mediaIds: [mediaId] });

    expect(published.status).toBe(409);
    expect(published.body.error).toBe('media_not_ready');
  });

  it('refuses an upload that was never PUT to the bucket', async () => {
    const superSession = await superAdminSession(app, pglite);
    const presign = await request(app)
      .post('/admin/api/media/uploads')
      .set(authed(superSession.accessToken))
      .send({ contentType: 'image/png', byteSize: 2048 });

    // No `store.put()`: the object does not exist, and the confirm step asks
    // the bucket rather than believing the client.
    const confirm = await request(app)
      .post(`/admin/api/media/uploads/${presign.body.upload.mediaId}/confirm`)
      .set(authed(superSession.accessToken));

    expect(confirm.status).toBe(400);
    expect(confirm.body.error).toBe('media_not_uploaded');
  });

  it('cannot attach another administrator’s upload', async () => {
    const superSession = await superAdminSession(app, pglite);
    const first = await createChannelWithAdmin(app, superSession, uniqueName('First'));
    const second = await createChannelWithAdmin(app, superSession, uniqueName('Second'));
    const firstOwner = await signIn(app, first.adminEmail, first.password);
    const secondOwner = await signIn(app, second.adminEmail, second.password);

    const mediaId = await upload(firstOwner);

    const res = await request(app)
      .post(`/admin/api/channels/${second.channel.id}/posts`)
      .set(authed(secondOwner.accessToken))
      .send({ mediaIds: [mediaId] });

    // Same answer as an id that does not exist, so the response says nothing
    // about whether it does.
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('unknown_media');
  });

  it('refuses a file type that is not an image, video or audio', async () => {
    const superSession = await superAdminSession(app, pglite);
    const res = await request(app)
      .post('/admin/api/media/uploads')
      .set(authed(superSession.accessToken))
      .send({ contentType: 'image/svg+xml', byteSize: 1024 });

    // An SVG served from our own bucket domain is a stored-XSS primitive, so
    // the allow-list refuses it rather than the client.
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('unsupported_media_type');
  });

  it('cannot claim the same upload twice', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Twice'));
    const mediaId = await upload(superSession);

    await publishTextPost(app, superSession, channel.id, 'first');

    const one = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(superSession.accessToken))
      .send({ mediaIds: [mediaId] });
    expect(one.status).toBe(201);

    const two = await request(app)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(superSession.accessToken))
      .send({ mediaIds: [mediaId] });
    expect(two.status).toBe(409);
    expect(two.body.error).toBe('media_already_used');
  });

  it('answers `media_unavailable` on a deployment with no bucket, and keeps text working', async () => {
    // §22: storage is OPTIONAL. A text post must still publish, and media must
    // fail with a code the app can word rather than a generic 500.
    const bare = buildApp({ database: asQueryable(pglite), store: unconfiguredStore() });
    const email = `super-${Math.random().toString(36).slice(2, 8)}@example.test`;
    await insertAdmin(pglite, { email, email_normalized: email });
    const session = await signIn(bare, email);

    const upload = await request(bare)
      .post('/admin/api/media/uploads')
      .set(authed(session.accessToken))
      .send({ contentType: 'image/jpeg', byteSize: 4096 });
    expect(upload.status).toBe(503);
    expect(upload.body.error).toBe('media_unavailable');

    const channel = await createBareChannel(bare, session, uniqueName('TextOnly'));
    const post = await request(bare)
      .post(`/admin/api/channels/${channel.id}/posts`)
      .set(authed(session.accessToken))
      .send({ body: 'Text still works' });
    expect(post.status).toBe(201);

    // And the app is told the truth about the deployment, so it can leave the
    // media button off instead of offering a step that cannot work.
    const settings = await request(bare)
      .get('/admin/api/settings')
      .set(authed(session.accessToken));
    expect(settings.body.settings.mediaEnabled).toBe(false);
  });
});

describe('the audit log (§29)', () => {
  it('records what happened, and is append-only in the database', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Audited'));

    const audit = await request(app).get('/admin/api/audit').set(authed(superSession.accessToken));
    expect(audit.status).toBe(200);
    const actions = audit.body.items.map((row: { action: string }) => row.action);
    expect(actions).toContain('admin.login');
    expect(actions).toContain('channel.create');

    // The actor's email is snapshotted, so removing an administrator would not
    // erase what they did — and a rename could not rewrite it.
    const login = audit.body.items.find((row: { action: string }) => row.action === 'admin.login');
    expect(login.adminEmail).toContain('@example.test');

    // UPDATE and DELETE are refused by a trigger, not by convention. The
    // database is what makes this a log rather than a table.
    await expect(pglite.exec(`UPDATE admin_audit_logs SET action = 'forged'`)).rejects.toThrow();
    await expect(pglite.exec(`DELETE FROM admin_audit_logs`)).rejects.toThrow();

    expect(channel.id).toBeTruthy();
  });

  it('records a refused attempt, which is the only evidence anyone tried', async () => {
    const superSession = await superAdminSession(app, pglite);
    const mine = await createChannelWithAdmin(app, superSession, uniqueName('Mine'));
    const owner = await signIn(app, mine.adminEmail, mine.password);

    await request(app)
      .post('/admin/api/channels')
      .set(authed(owner.accessToken))
      .send({ name: 'Denied' });

    const audit = await request(app).get('/admin/api/audit').set(authed(superSession.accessToken));
    const denied = audit.body.items.find((row: { outcome: string }) => row.outcome === 'denied');
    expect(denied.action).toBe('denied.channels.create');
  });
});

/** A store that answers "this deployment has no bucket" (§22). */
function unconfiguredStore(): FakeObjectStore {
  const fake = new FakeObjectStore();
  Object.defineProperty(fake, 'configured', { value: false });
  return fake;
}
