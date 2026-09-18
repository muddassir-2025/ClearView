import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { unauthorized } from '../src/http/errors.js';
import type { IdentityVerifier, VerifiedIdentity } from '../src/identity/verifier.js';
import { readServiceAccount, UnconfiguredPushSender } from '../src/notifications/fcm.js';
import { announcePost, previewOf } from '../src/notifications/service.js';
import { applyAllMigrations, asQueryable, freshDatabase, one, resetData } from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';
import { authed, createBareChannel, publishTextPost, superAdminSession } from './helpers/accounts.js';
import { RecordingPushSender } from './helpers/push.js';

/**
 * Push notifications (§8, §12).
 *
 * What is under test is the decision — WHO is told, who is not, what the message
 * carries, and what happens to a token that stopped working — because that is
 * the part this server owns. The FCM client itself is faked: proving that
 * Firebase accepts a message needs a service account and a phone, and neither
 * belongs in a test that has to run on every commit.
 *
 * The database is real (PGlite through the real migrations), so the follow/mute
 * SQL and the token upsert are the ones that ship.
 */

let pglite: PGlite;
let store: FakeObjectStore;
let push: RecordingPushSender;

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
  push = new RecordingPushSender();
});

/** An app wired with a recording push sender and a verifier that knows our tokens. */
function appWith(sender: RecordingPushSender = push): Express {
  return buildApp({
    database: asQueryable(pglite),
    store,
    verifier: fakeVerifier,
    pushSender: sender,
  });
}

function readerToken(uid: string): string {
  const token = `reader-token-${uid}`;
  IDENTITIES.set(token, { uid, anonymous: true, email: null, emailVerified: false });
  return token;
}

const DEVICE = 'd'.repeat(40);
const OTHER_DEVICE = 'e'.repeat(40);

describe('registering a device (§8)', () => {
  it('records a token against the authenticated reader, and nothing else', async () => {
    const app = appWith();
    const token = readerToken('anon-1');

    const response = await request(app)
      .post('/api/v1/readers/me/devices')
      .set(authed(token))
      .send({ token: DEVICE });

    expect(response.status).toBe(200);
    expect(response.body.device).toMatchObject({ token: DEVICE, platform: 'android' });

    const rows = await pglite.query<{ reader_id: string; platform: string }>(
      `SELECT reader_id, platform FROM reader_devices`
    );
    expect(rows.rows).toHaveLength(1);
    expect(rows.rows[0]!.platform).toBe('android');

    const readers = await pglite.query<{ id: string }>(`SELECT id FROM readers`);
    expect(rows.rows[0]!.reader_id).toBe(one(readers.rows).id);
  });

  it('requires a token, and refuses one that is not a real device token', async () => {
    const app = appWith();
    const token = readerToken('anon-1');

    const anonymous = await request(app)
      .post('/api/v1/readers/me/devices')
      .send({ token: DEVICE });
    expect(anonymous.status).toBe(401);

    const tooShort = await request(app)
      .post('/api/v1/readers/me/devices')
      .set(authed(token))
      .send({ token: 'short' });
    expect(tooShort.status).toBe(400);
    expect(tooShort.body.error).toBe('invalid_request');

    const unknownPlatform = await request(app)
      .post('/api/v1/readers/me/devices')
      .set(authed(token))
      .send({ token: DEVICE, platform: 'symbian' });
    expect(unknownPlatform.status).toBe(400);
  });

  it('moves a token to the reader who is signed in on that phone now', async () => {
    // A token names an INSTALL, and an install has one reader at a time. Left as
    // two rows, the first reader would keep receiving notifications about their
    // channels on a phone somebody else is holding.
    const app = appWith();
    const alice = readerToken('alice-uid');
    const bob = readerToken('bob-uid');

    await request(app).post('/api/v1/readers/me/devices').set(authed(alice)).send({ token: DEVICE });
    await request(app).post('/api/v1/readers/me/devices').set(authed(bob)).send({ token: DEVICE });

    const rows = await pglite.query<{ reader_id: string }>(
      `SELECT reader_id FROM reader_devices WHERE token = $1`,
      [DEVICE]
    );
    expect(rows.rows).toHaveLength(1);

    const bobRow = await pglite.query<{ id: string }>(`SELECT id FROM readers WHERE firebase_uid = $1`, [
      'bob-uid',
    ]);
    expect(rows.rows[0]!.reader_id).toBe(one(bobRow.rows).id);
  });

  it('unregisters a device, and cannot unregister somebody else’s', async () => {
    const app = appWith();
    const alice = readerToken('alice-uid');
    const bob = readerToken('bob-uid');

    await request(app).post('/api/v1/readers/me/devices').set(authed(alice)).send({ token: DEVICE });

    // Bob naming Alice's token: scoped in SQL, so the row survives.
    const byBob = await request(app)
      .delete(`/api/v1/readers/me/devices/${DEVICE}`)
      .set(authed(bob));
    expect(byBob.status).toBe(200);
    expect((await pglite.query(`SELECT 1 FROM reader_devices`)).rows).toHaveLength(1);

    const byAlice = await request(app)
      .delete(`/api/v1/readers/me/devices/${DEVICE}`)
      .set(authed(alice));
    expect(byAlice.status).toBe(200);
    expect((await pglite.query(`SELECT 1 FROM reader_devices`)).rows).toHaveLength(0);
  });

  it('takes the tokens with the reader when the reader row goes', async () => {
    const app = appWith();
    const token = readerToken('anon-1');
    await request(app).post('/api/v1/readers/me/devices').set(authed(token)).send({ token: DEVICE });

    await pglite.query(`DELETE FROM readers`);
    expect((await pglite.query(`SELECT 1 FROM reader_devices`)).rows).toHaveLength(0);
  });
});

describe('who a publish notifies (§8)', () => {
  /** A reader who follows [slug]. Returns their token and the reader device. */
  async function followChannel(
    app: Express,
    slug: string,
    uid: string,
    options: { device?: string; muted?: boolean } = {}
  ): Promise<{ readerToken: string; device: string }> {
    const token = readerToken(uid);
    const device = options.device ?? DEVICE;

    await request(app).post('/api/v1/readers/me/devices').set(authed(token)).send({ token: device });
    await request(app).post(`/api/v1/readers/me/following/${slug}`).set(authed(token));

    if (options.muted) {
      await request(app)
        .patch(`/api/v1/readers/me/following/${slug}`)
        .set(authed(token))
        .send({ muted: true });
    }

    return { readerToken: token, device };
  }

  it('pushes to a follower, with everything the client needs to open it', async () => {
    const app = appWith();
    const admin = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, admin, 'Dev Updates');
    await followChannel(app, channel.slug, 'anon-1');

    const postId = await publishTextPost(app, admin, channel.id, 'Salam everyone');
    await push.waitFor(1);

    expect(push.messages).toHaveLength(1);
    const message = push.to(DEVICE);
    expect(message).toBeDefined();
    expect(message!.title).toBe('Dev Updates');
    expect(message!.body).toBe('Salam everyone');
    expect(message!.data).toMatchObject({
      type: 'goodpost.post',
      channelId: channel.id,
      channelSlug: channel.slug,
      postId,
    });
    // The publish time is what the client's watermark compares against, so it
    // must be the post's own timestamp rather than the moment of the send.
    expect(Date.parse(message!.data.publishedAt!)).toBeGreaterThan(0);
  });

  it('says nothing to a reader who does not follow the channel', async () => {
    const app = appWith();
    const admin = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, admin, 'Quiet channel');
    const other = await createBareChannel(app, admin, 'Other channel');

    // A device exists, and a follow exists — of a DIFFERENT channel.
    await followChannel(app, other.slug, 'anon-1');

    await publishTextPost(app, admin, channel.id, 'Nobody is listening');
    await new Promise((resolve) => setTimeout(resolve, 60));

    expect(push.messages).toEqual([]);
  });

  it('respects a muted follow, and only that follower', async () => {
    const app = appWith();
    const admin = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, admin, 'Noisy channel');

    await followChannel(app, channel.slug, 'muted-uid', { device: DEVICE, muted: true });
    await followChannel(app, channel.slug, 'keen-uid', { device: OTHER_DEVICE });

    await publishTextPost(app, admin, channel.id, 'Hello to some of you');
    await push.waitFor(1);

    expect(push.tokensNotified()).toEqual([OTHER_DEVICE]);
  });

  it('sends one message when two followers share a phone', async () => {
    // The token is unique per install, so the reader who registered it last owns
    // it. This is the fan-out half of that rule: two follows, one device, one
    // notification — not the same update arriving twice on one phone.
    const app = appWith();
    const admin = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, admin, 'Shared phone');

    await followChannel(app, channel.slug, 'first-uid', { device: DEVICE });
    await followChannel(app, channel.slug, 'second-uid', { device: DEVICE });

    await publishTextPost(app, admin, channel.id, 'One message per phone');
    await push.waitFor(1);

    expect(push.tokensNotified()).toEqual([DEVICE]);
  });

  it('removes a token Firebase says is gone, so the next publish does not pay for it', async () => {
    const app = appWith();
    const admin = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, admin, 'Retired phones');
    await followChannel(app, channel.slug, 'anon-1');

    push.dead = [DEVICE];
    await publishTextPost(app, admin, channel.id, 'Anybody there?');
    await push.waitFor(1);

    // Wait for the prune, which happens after the send.
    const deadline = Date.now() + 1000;
    while ((await pglite.query(`SELECT 1 FROM reader_devices`)).rows.length > 0 && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    expect((await pglite.query(`SELECT 1 FROM reader_devices`)).rows).toHaveLength(0);
  });

  it('publishes normally when push is not configured', async () => {
    // The deployment without a service account must be a supported one: the post
    // exists, the request succeeds, and nothing tries to send.
    const app = buildApp({
      database: asQueryable(pglite),
      store,
      verifier: fakeVerifier,
      pushSender: new UnconfiguredPushSender(),
    });
    const admin = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, admin, 'No push here');

    const postId = await publishTextPost(app, admin, channel.id, 'Text still works');
    expect(postId).toBeTruthy();

    const posts = await request(app).get(`/api/v1/channels/${channel.slug}/posts`);
    expect(posts.status).toBe(200);
    expect(posts.body.items).toHaveLength(1);
  });
});

describe('notifying without an HTTP request (§8)', () => {
  it('does nothing at all when the sender is unconfigured', async () => {
    // Not even the query: a deployment with nowhere to send should not read the
    // followers to discover it.
    let queries = 0;
    const counted = {
      async query(...args: Parameters<ReturnType<typeof asQueryable>['query']>) {
        queries += 1;
        return (asQueryable(pglite).query as (...inner: unknown[]) => unknown)(...args);
      },
    };

    const result = await announcePost(counted as never, new UnconfiguredPushSender(), {
      channelId: '00000000-0000-0000-0000-000000000000',
      postId: '00000000-0000-0000-0000-000000000001',
      body: 'Nope',
      publishedAt: new Date().toISOString(),
    });

    expect(result).toEqual({ devices: 0, sent: 0, removed: 0 });
    expect(queries).toBe(0);
  });

  it('caps a fan-out and reports what it did', async () => {
    const app = appWith();
    const admin = await superAdminSession(app, pglite);
    const channel = await createBareChannel(app, admin, 'Popular');

    for (let i = 0; i < 3; i += 1) {
      const token = readerToken(`fan-${i}`);
      await request(app)
        .post('/api/v1/readers/me/devices')
        .set(authed(token))
        .send({ token: `${i}`.repeat(40) });
      await request(app).post(`/api/v1/readers/me/following/${channel.slug}`).set(authed(token));
    }

    const result = await announcePost(asQueryable(pglite), push, {
      channelId: channel.id,
      postId: '00000000-0000-0000-0000-000000000002',
      body: 'Fan out',
      publishedAt: new Date().toISOString(),
    });

    expect(result.devices).toBe(3);
    expect(result.sent).toBe(3);
    expect(result.removed).toBe(0);
    expect(push.tokensNotified()).toHaveLength(3);
  });
});

describe('the payload’s preview (§8)', () => {
  it('strips the formatting markers a post is stored with', () => {
    expect(previewOf('*bold* and _italic_ and ~struck~')).toBe('bold and italic and struck');
    expect(previewOf('```monospace```')).toBe('monospace');
  });

  it('collapses newlines into one line, and stops at a sensible length', () => {
    expect(previewOf('First line\n\nsecond line')).toBe('First line second line');

    const long = previewOf('x'.repeat(400));
    expect(long.length).toBe(140);
    expect(long.endsWith('…')).toBe(true);
  });

  it('is empty for a post with no text, rather than a made-up sentence', () => {
    // The client words the fallback ("posted a photo"), so that one phrase lives
    // in one place and can be translated there.
    expect(previewOf(null)).toBe('');
    expect(previewOf('   \n  ')).toBe('');
  });
});

describe('reading the service account (§8)', () => {
  const account = {
    project_id: 'clearview-28413',
    client_email: 'push@clearview-28413.iam.gserviceaccount.com',
    // A real key is multi-line; this stands in for one.
    private_key: '-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----\\n',
  };

  it('is off when the variable is absent or empty', () => {
    expect(readServiceAccount(undefined)).toBeNull();
    expect(readServiceAccount('   ')).toBeNull();
  });

  it('accepts the JSON file as it is downloaded', () => {
    const parsed = readServiceAccount(JSON.stringify(account));
    expect(parsed).toEqual({
      projectId: 'clearview-28413',
      clientEmail: 'push@clearview-28413.iam.gserviceaccount.com',
      privateKey: '-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n',
    });
  });

  it('accepts it base64-encoded, which survives a dashboard that eats whitespace', () => {
    const encoded = Buffer.from(JSON.stringify(account), 'utf8').toString('base64');
    const parsed = readServiceAccount(encoded);
    expect(parsed?.projectId).toBe('clearview-28413');
    // The escaped newlines are repaired either way, so the key is usable.
    expect(parsed?.privateKey).toContain('\n');
  });

  it('refuses a blob that is missing a field, rather than half-configuring push', () => {
    expect(readServiceAccount(JSON.stringify({ project_id: 'x' }))).toBeNull();
    expect(readServiceAccount('not json at all')).toBeNull();
  });
});
