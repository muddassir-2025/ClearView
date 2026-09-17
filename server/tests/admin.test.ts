import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { env } from '../src/env.js';
import { ensureSuperAdmin } from '../src/admin/bootstrap.js';
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

  it('refuses a file type that is not an image or a video', async () => {
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

/**
 * Deleting a channel (§17).
 *
 * Written around what must go and what must not. A channel's posts, its media
 * rows, the objects behind them and the login created to run it all belong to
 * the channel, so leaving one behind is a leftover every later query has to know
 * about — which is the arrangement this suite exists to prevent. The audit trail
 * is the exception, and it is the interesting one: it must outlive the account it
 * describes, or the record of a deletion deletes itself.
 */
describe('deleting a channel (§17)', () => {
  /**
   * A channel in the state one is really deleted from: a profile image, a
   * published image post, and an administrator signed in to run it.
   */
  async function doomedChannel(superSession: Session): Promise<{
    channelId: string;
    slug: string;
    adminEmail: string;
    owner: Session;
  }> {
    const created = await createChannelWithAdmin(app, superSession, uniqueName('Doomed'));
    const owner = await signIn(app, created.adminEmail, created.password);

    // An icon: the one attachment that belongs to the CHANNEL rather than to a
    // post, which is why the delete names it separately.
    const iconId = await uploadImage(owner);
    const patched = await request(app)
      .patch(`/admin/api/channels/${created.channel.id}`)
      .set(authed(owner.accessToken))
      .send({ iconMediaId: iconId });
    expect(patched.status, JSON.stringify(patched.body)).toBe(200);

    const postMediaId = await uploadImage(owner);
    const published = await request(app)
      .post(`/admin/api/channels/${created.channel.id}/posts`)
      .set(authed(owner.accessToken))
      .send({ body: 'About to go', mediaIds: [postMediaId] });
    expect(published.status, JSON.stringify(published.body)).toBe(201);

    return {
      channelId: created.channel.id,
      slug: created.channel.slug,
      adminEmail: created.adminEmail,
      owner,
    };
  }

  /** Presign → PUT → confirm, the same handshake the phone performs. */
  async function uploadImage(session: Session): Promise<string> {
    const presign = await request(app)
      .post('/admin/api/media/uploads')
      .set(authed(session.accessToken))
      .send({ contentType: 'image/jpeg', byteSize: 4096 });
    expect(presign.status, JSON.stringify(presign.body)).toBe(201);

    store.put();
    const mediaId = presign.body.upload.mediaId as string;
    const confirmed = await request(app)
      .post(`/admin/api/media/uploads/${mediaId}/confirm`)
      .set(authed(session.accessToken));
    expect(confirmed.status, JSON.stringify(confirmed.body)).toBe(200);
    return mediaId;
  }

  /** Everything that should stop existing, counted in one round trip. */
  async function rowsFor(channelId: string): Promise<{ channels: number; posts: number; media: number }> {
    const res = await pglite.query<{ channels: number; posts: number; media: number }>(
      `SELECT (SELECT count(*) FROM channels WHERE id = $1)::int AS channels,
              (SELECT count(*) FROM posts WHERE channel_id = $1)::int AS posts,
              (SELECT count(*) FROM post_media m
                 JOIN posts p ON p.id = m.post_id
                WHERE p.channel_id = $1)::int AS media`,
      [channelId]
    );
    return res.rows[0] as { channels: number; posts: number; media: number };
  }

  it('takes the posts, the media, the bucket objects and the channel login with it', async () => {
    const superSession = await superAdminSession(app, pglite);
    const doomed = await doomedChannel(superSession);
    const objectKeys = store.issued.map((i) => i.key);

    // The state before, so the zeros below mean something: an assertion that a
    // count is zero proves nothing unless it was not zero to begin with.
    expect(await rowsFor(doomed.channelId)).toEqual({ channels: 1, posts: 1, media: 1 });

    const res = await request(app)
      .delete(`/admin/api/channels/${doomed.channelId}`)
      .set(authed(superSession.accessToken));
    expect(res.status, JSON.stringify(res.body)).toBe(200);
    expect(res.body.deleted).toBe(true);

    expect(await rowsFor(doomed.channelId)).toEqual({ channels: 0, posts: 0, media: 0 });

    // The objects, named by the keys the delete handed back. A row can be gone
    // while the file it pointed at stays in the bucket forever, and that is the
    // half of a delete that nothing else would notice.
    for (const key of objectKeys) expect(store.removed).toContain(key);

    // A reader gets the same 404 as a channel that never existed.
    expect((await request(app).get(`/api/v1/channels/${doomed.slug}`)).status).toBe(404);

    // The credentials created to run the channel stop working with it — the
    // account is gone, so the token it was issued cannot resolve to anyone.
    const account = await pglite.query(`SELECT id FROM admin_users WHERE email = $1`, [
      doomed.adminEmail,
    ]);
    expect(account.rows).toHaveLength(0);

    const stale = await request(app)
      .get('/admin/api/channels')
      .set(authed(doomed.owner.accessToken));
    expect(stale.status).toBe(401);
  });

  it('keeps the audit trail, including the history of the account it removed', async () => {
    const superSession = await superAdminSession(app, pglite);
    const doomed = await doomedChannel(superSession);

    await request(app)
      .delete(`/admin/api/channels/${doomed.channelId}`)
      .set(authed(superSession.accessToken));

    const audit = await request(app).get('/admin/api/audit').set(authed(superSession.accessToken));
    const deletion = audit.body.items.find(
      (row: { action: string; targetId: string }) =>
        row.action === 'channel.delete' && row.targetId === doomed.channelId
    );
    expect(deletion).toBeTruthy();
    expect(deletion.adminEmail).toContain('@example.test');

    // The removed login's own history survives it BYTE FOR BYTE: the actor id it
    // recorded is still there and simply no longer resolves to a row (015 drops
    // the foreign key rather than rewriting an immutable log). The email is
    // snapshotted alongside, so "who did this" is answerable from the log alone.
    const theirs = await pglite.query<{ admin_id: string | null; admin_email: string }>(
      `SELECT admin_id, admin_email FROM admin_audit_logs WHERE admin_email = $1`,
      [doomed.adminEmail]
    );
    expect(theirs.rows.length).toBeGreaterThan(0);
    expect(theirs.rows.every((row) => row.admin_id !== null)).toBe(true);

    const resolutions = await pglite.query(
      `SELECT id FROM admin_users WHERE id = ANY($1::uuid[])`,
      [[...new Set(theirs.rows.map((row) => row.admin_id))]]
    );
    expect(resolutions.rows).toHaveLength(0);

    // And none of it can be rewritten afterwards.
    await expect(pglite.exec(`DELETE FROM admin_audit_logs`)).rejects.toThrow();
  });

  it('is refused to the channel administrator who runs it', async () => {
    const superSession = await superAdminSession(app, pglite);
    const created = await createChannelWithAdmin(app, superSession, uniqueName('Kept'));
    const owner = await signIn(app, created.adminEmail, created.password);

    const res = await request(app)
      .delete(`/admin/api/channels/${created.channel.id}`)
      .set(authed(owner.accessToken));

    // Refused as a permission, before any lookup: "manage your channel" and
    // "destroy your channel's history" are different verbs, and only one of
    // them is in §18.
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('admin_forbidden');

    const rows = await pglite.query(`SELECT id FROM channels WHERE id = $1`, [created.channel.id]);
    expect(rows.rows).toHaveLength(1);
  });

  it('is a 404 the second time, so a double tap is not a second delete', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Once'));

    const first = await request(app)
      .delete(`/admin/api/channels/${channel.id}`)
      .set(authed(superSession.accessToken));
    expect(first.status).toBe(200);

    const second = await request(app)
      .delete(`/admin/api/channels/${channel.id}`)
      .set(authed(superSession.accessToken));
    expect(second.status).toBe(404);
    expect(second.body.error).toBe('channel_not_found');
  });
});

/**
 * Removing several posts at once (§17).
 *
 * The gesture is a long press and a few taps, so it is one action and one
 * request. The property that matters is that it is ALL OR NOTHING: a selection
 * that includes a post the caller may not touch is refused whole, because the
 * alternative is a selection that half-disappeared with no way to tell which
 * half — and a client that re-sends would delete whatever survived twice.
 */
describe('removing several posts at once (§17)', () => {
  it('removes the selection, and refuses all of it when one post is not theirs', async () => {
    const superSession = await superAdminSession(app, pglite);
    const mine = await createChannelWithAdmin(app, superSession, uniqueName('Mine'));
    const theirs = await createChannelWithAdmin(app, superSession, uniqueName('Theirs'));
    const owner = await signIn(app, mine.adminEmail, mine.password);
    const otherOwner = await signIn(app, theirs.adminEmail, theirs.password);

    const one = await publishTextPost(app, owner, mine.channel.id, 'one');
    const two = await publishTextPost(app, owner, mine.channel.id, 'two');
    const notMine = await publishTextPost(app, otherOwner, theirs.channel.id, 'not mine');

    const mixed = await request(app)
      .post('/admin/api/posts/bulk-delete')
      .set(authed(owner.accessToken))
      .send({ postIds: [one, two, notMine] });

    // The outsider's post is refused as a CHANNEL they may not name, which is
    // what the scope check answers for everyone else's channel — and nothing was
    // removed, including the two that were theirs.
    expect(mixed.status).toBe(404);
    expect(mixed.body.error).toBe('channel_not_found');

    const kept = await request(app)
      .get(`/api/v1/channels/${mine.channel.slug}/posts`);
    expect(kept.body.items).toHaveLength(2);

    const own = await request(app)
      .post('/admin/api/posts/bulk-delete')
      .set(authed(owner.accessToken))
      .send({ postIds: [one, two] });
    expect(own.status, JSON.stringify(own.body)).toBe(200);
    expect(own.body.deleted).toBe(2);

    // And the reader sees them gone, which is the only part that is the point.
    const after = await request(app).get(`/api/v1/channels/${mine.channel.slug}/posts`);
    expect(after.body.items).toHaveLength(0);
  });

  it('counts a post once when a selection names it twice', async () => {
    const superSession = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, superSession, uniqueName('Dupes'));
    const postId = await publishTextPost(app, superSession, channel.id, 'once');

    const res = await request(app)
      .post('/admin/api/posts/bulk-delete')
      .set(authed(superSession.accessToken))
      .send({ postIds: [postId, postId] });

    // A second delete of the same id would be a 404 partway through, so the
    // duplicate is collapsed before anything is touched.
    expect(res.status, JSON.stringify(res.body)).toBe(200);
    expect(res.body.deleted).toBe(1);
  });

  it('refuses an empty selection and a body that is not a list of ids', async () => {
    const superSession = await superAdminSession(app, pglite);

    const empty = await request(app)
      .post('/admin/api/posts/bulk-delete')
      .set(authed(superSession.accessToken))
      .send({ postIds: [] });
    expect(empty.status).toBe(400);

    const wrong = await request(app)
      .post('/admin/api/posts/bulk-delete')
      .set(authed(superSession.accessToken))
      .send({ postIds: ['not-a-uuid'] });
    expect(wrong.status).toBe(400);
  });
});

/**
 * The super administrator's password and the environment (§17, §18).
 *
 * The bug this suite exists for: `SUPER_ADMIN_PASSWORD` was changed on the
 * deployment and the app kept answering "Invalid credentials". The bootstrap
 * only ever CREATED the account — every later boot returned `already_provisioned`
 * and left the old hash in place — and a stale `SUPER_ADMIN_PASSWORD_HASH`
 * outranked the plaintext that had been edited. Neither was visible from the
 * app, which is what made it a bug worth a test rather than a support answer.
 *
 * These cases drive the real bootstrap and then sign in through the real route,
 * because "the password now works" is a claim about HTTP.
 */
describe('the super administrator’s password follows the environment (§18)', () => {
  const EMAIL = 'super-admin@example.test';
  const ORIGINAL = 'original-password-1234';
  const CHANGED = 'changed-password-5678';

  beforeEach(async () => {
    // The bootstrap refuses to mint a SECOND administrator, so this block has to
    // start from an empty `admin_users` rather than from whatever the test
    // before it left behind. Ordering between tests must not decide the answer.
    //
    // `admin_audit_logs` is deliberately left alone: a trigger refuses deletes
    // from it, which is the property §29 asks for, so the audit is asserted on
    // by counting what a step ADDED rather than by clearing it.
    await pglite.exec('DELETE FROM admin_users');
  });

  /** How many reconcile entries the log holds right now. */
  async function reconciledCount(): Promise<number> {
    const rows = await pglite.query<{ n: number }>(
      `SELECT count(*)::int AS n FROM admin_audit_logs WHERE action = 'admin.password.reconciled'`
    );
    return rows.rows[0]?.n ?? 0;
  }

  it('replaces a stale hash with the configured password, so a changed password works', async () => {
    const database = asQueryable(pglite);

    expect((await ensureSuperAdmin(database, ORIGINAL)).created).toBe(true);

    // A redeploy carrying the SAME password must not touch a working account.
    expect((await ensureSuperAdmin(database, ORIGINAL)).reason).toBe('already_provisioned');

    const before = await reconciledCount();

    // The operator edits SUPER_ADMIN_PASSWORD and the service restarts.
    expect((await ensureSuperAdmin(database, CHANGED)).reason).toBe('password_reconciled');

    // The change is recorded, because nothing else would say it happened.
    expect(await reconciledCount()).toBe(before + 1);

    const renewed = await request(app)
      .post('/admin/api/auth/login')
      .send({ email: EMAIL, password: CHANGED });
    expect(renewed.status, JSON.stringify(renewed.body)).toBe(200);

    // And the value it replaced is gone, not merely shadowed.
    const stale = await request(app)
      .post('/admin/api/auth/login')
      .send({ email: EMAIL, password: ORIGINAL });
    expect(stale.status).toBe(401);
  });

  it('never reconciles a password it cannot compare, so a CLI rotation survives a boot', async () => {
    const database = asQueryable(pglite);
    expect((await ensureSuperAdmin(database, ORIGINAL)).created).toBe(true);
    const before = await reconciledCount();

    // `''` stands in for "no plaintext configured". It has to be explicit: this
    // suite does not pin `SUPER_ADMIN_PASSWORD`, so whatever the machine's
    // server/.env holds would otherwise decide the answer — and a test whose
    // result depends on one developer's .env tests nothing.
    //
    // With no plaintext there is nothing to compare against the account, so the
    // stored hash must be left exactly as it is.
    expect((await ensureSuperAdmin(database, '')).reason).toBe('already_provisioned');
    expect(await reconciledCount()).toBe(before);

    const stillWorks = await request(app)
      .post('/admin/api/auth/login')
      .send({ email: EMAIL, password: ORIGINAL });
    expect(stillWorks.status).toBe(200);
  });

  it('applies a configured password that is below the form floor, and says so rather than hiding it', async () => {
    const database = asQueryable(pglite);
    // One character short of ADMIN_MIN_PASSWORD_LENGTH, and below it only by
    // construction rather than by a number copied into the test — the floor has
    // moved once already (it is eight, chosen because this password is typed
    // into a phone, not because the deployment's own is any particular length).
    // The first version of this fix REFUSED a configured password below the
    // floor, which left the account on the stale hash and reproduced the
    // original symptom exactly, so what is pinned here is that it is applied and
    // merely advised about.
    const short = 'a'.repeat(env.ADMIN_MIN_PASSWORD_LENGTH - 1);

    expect((await ensureSuperAdmin(database, ORIGINAL)).created).toBe(true);

    const applied = await ensureSuperAdmin(database, short);
    expect(applied.reason).toBe('password_reconciled');

    // Advised about, never refused, and never echoed.
    expect(applied.warning).not.toBeNull();
    expect(applied.warning).toContain('SUPER_ADMIN_PASSWORD');
    expect(applied.warning).toContain(String(env.ADMIN_MIN_PASSWORD_LENGTH));
    expect(applied.warning).not.toContain(short);

    // And it actually signs in, which is the whole point: the floor protects a
    // password chosen in a form, and this one was chosen by whoever can set the
    // deployment's environment.
    const res = await request(app)
      .post('/admin/api/auth/login')
      .send({ email: EMAIL, password: short });
    expect(res.status, JSON.stringify(res.body)).toBe(200);
  });
});

/**
 * Changing the password that runs a channel (§20).
 *
 * The form that creates a channel also mints the login for it, which means the
 * one credential a deployment hands to somebody was, until this, the one thing
 * it could never change: a lost password meant deleting the channel, and a
 * compromised one meant the same. These cases pin the whole of it — who may do
 * it, that the new value works, that the old one stops working, and that a
 * refusal leaves the account exactly as it was.
 */
describe('changing the password that runs a channel (§20)', () => {
  it('lets a super administrator reset the password, and the old one stops working', async () => {
    const superSession = await superAdminSession(app, pglite);
    const created = await createChannelWithAdmin(app, superSession, uniqueName('Rotated'));
    const owner = await signIn(app, created.adminEmail, created.password);

    const res = await request(app)
      .patch(`/admin/api/channels/${created.channel.id}`)
      .set(authed(superSession.accessToken))
      .send({ adminPassword: 'a-brand-new-password' });
    expect(res.status, JSON.stringify(res.body)).toBe(200);

    // Never echoed back — not in the channel payload, not anywhere in the body.
    expect(JSON.stringify(res.body)).not.toContain('a-brand-new-password');

    const renewed = await signIn(app, created.adminEmail, 'a-brand-new-password');
    expect(renewed.channelId).toBe(created.channel.id);

    // The value it replaced is gone, not merely shadowed.
    const stale = await request(app)
      .post('/admin/api/auth/login')
      .send({ email: created.adminEmail, password: created.password });
    expect(stale.status).toBe(401);

    // A reset by somebody else signs that account out of the sessions it had.
    const doomed = await request(app)
      .get('/admin/api/auth/me')
      .set(authed(owner.accessToken));
    expect(doomed.status).toBe(401);
  });

  it('lets a channel administrator change their own password without signing themselves out', async () => {
    const superSession = await superAdminSession(app, pglite);
    const created = await createChannelWithAdmin(app, superSession, uniqueName('Owner'));
    const owner = await signIn(app, created.adminEmail, created.password);

    const res = await request(app)
      .patch(`/admin/api/channels/${created.channel.id}`)
      .set(authed(owner.accessToken))
      .send({ adminPassword: 'chosen-by-the-owner' });
    expect(res.status, JSON.stringify(res.body)).toBe(200);

    // The session they made the change with is deliberately left alive: being
    // signed out of the phone in your hand is not what "change my password"
    // asked for.
    const stillHere = await request(app)
      .get('/admin/api/auth/me')
      .set(authed(owner.accessToken));
    expect(stillHere.status).toBe(200);

    const renewed = await signIn(app, created.adminEmail, 'chosen-by-the-owner');
    expect(renewed.adminId).toBe(owner.adminId);
  });

  it('refuses a password below the floor, and leaves the account on the old one', async () => {
    const superSession = await superAdminSession(app, pglite);
    const created = await createChannelWithAdmin(app, superSession, uniqueName('Weak'));

    const res = await request(app)
      .patch(`/admin/api/channels/${created.channel.id}`)
      .set(authed(superSession.accessToken))
      .send({ adminPassword: 'short' });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('weak_password');

    // A refused password changes nothing — including the rest of the edit that
    // arrived with it, because both are one transaction.
    const stillWorks = await signIn(app, created.adminEmail, created.password);
    expect(stillWorks.channelId).toBe(created.channel.id);
  });

  it('refuses to invent an administrator for a channel that has none', async () => {
    const superSession = await superAdminSession(app, pglite);
    const bare = await createBareChannel(app, superSession, uniqueName('NoLogin'));

    const res = await request(app)
      .patch(`/admin/api/channels/${bare.id}`)
      .set(authed(superSession.accessToken))
      .send({ adminPassword: 'a-password-nobody-owns' });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('channel_has_no_admin');
  });

  it('cannot reach another channel’s administrator', async () => {
    const superSession = await superAdminSession(app, pglite);
    const mine = await createChannelWithAdmin(app, superSession, uniqueName('Mine'));
    const theirs = await createChannelWithAdmin(app, superSession, uniqueName('Theirs'));
    const interloper = await signIn(app, mine.adminEmail, mine.password);

    // The scope check answers 404 rather than 403 — a channel administrator may
    // not even learn that the other channel exists (§18).
    const res = await request(app)
      .patch(`/admin/api/channels/${theirs.channel.id}`)
      .set(authed(interloper.accessToken))
      .send({ adminPassword: 'a-password-i-chose' });
    expect(res.status).toBe(404);

    // And the other channel's own credential is untouched.
    const unaffected = await signIn(app, theirs.adminEmail, theirs.password);
    expect(unaffected.channelId).toBe(theirs.channel.id);
  });
});

/** A store that answers "this deployment has no bucket" (§22). */
function unconfiguredStore(): FakeObjectStore {
  const fake = new FakeObjectStore();
  Object.defineProperty(fake, 'configured', { value: false });
  return fake;
}
