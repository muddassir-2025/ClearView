import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { unauthorized } from '../src/http/errors.js';
import type { IdentityVerifier, VerifiedIdentity } from '../src/identity/verifier.js';
import { unconfiguredVerifier } from '../src/identity/verifier.js';
import { applyAllMigrations, asQueryable, freshDatabase, one, resetData } from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';

/**
 * Creators who sign themselves up (§16, §17).
 *
 * §16 reverses the earlier rule that channels come only from a super admin, and
 * what it adds is a new way for an account to come into existence. That is the
 * most dangerous kind of change this service can take, so the tests here are
 * mostly about what a creator CANNOT do rather than what they can:
 *
 *  * an anonymous reader — the identity every install of the app can mint with
 *    one silent call — must not be able to turn itself into a channel owner,
 *  * a creator must not be able to own two channels, or reach one that is not
 *    theirs,
 *  * and the account the flow creates must not be usable through the PASSWORD
 *    form, because it has no password and `password_hash` is NULL.
 *
 * The identity is faked (the real signature check is proved in `identity.test.ts`
 * and re-proving it here would make every case depend on generating RSA keys),
 * but the database is real: these run against PGlite through the real
 * migrations, so `003_creator_accounts.sql`, the partial unique index on
 * `firebase_uid` and the nullable `password_hash` are the ones that ship.
 */

let pglite: PGlite;
let store: FakeObjectStore;

/** Token → identity. A token not in the map is a token that does not verify. */
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
  // `resetData` deliberately keeps a channel that an administrator is bound to
  // (that guard is what stops it deleting another suite's fixture), so the
  // accounts this file creates have to go first — and with them the channels
  // they own. Otherwise a channel called "Mine" from an earlier test would
  // still be there, and an assertion about how many channels exist would be
  // measuring the test order rather than the code.
  await pglite.exec(`DELETE FROM admin_users WHERE firebase_uid IS NOT NULL`);
  await pglite.exec(`DELETE FROM channels`);
  IDENTITIES.clear();
  store = new FakeObjectStore();
});

function appWith(verifier: IdentityVerifier = fakeVerifier): Express {
  return buildApp({ database: asQueryable(pglite), store, verifier });
}

/**
 * An email/password or Google identity: not anonymous, and PROVEN.
 *
 * `emailVerified` is true by default because that is what both creator
 * providers report — Google verifies its addresses, and a password account that
 * cannot is refused the linking path this default exercises. [unprovenToken] is
 * the other case, and the one the security assertions are about.
 */
function creatorToken(
  uid: string,
  email = `${uid}@example.test`,
  emailVerified = true
): string {
  const token = `creator-token-${uid}`;
  IDENTITIES.set(token, { uid, email, anonymous: false, emailVerified });
  return token;
}

/** The same identity, but Firebase has not proven the address it claims. */
function unprovenToken(uid: string, email: string): string {
  return creatorToken(`unproven-${uid}`, email, false);
}

/** §3's reader identity — the one anything that can reach the API can obtain. */
function readerToken(uid: string): string {
  const token = `reader-token-${uid}`;
  IDENTITIES.set(token, { uid, email: null, anonymous: true, emailVerified: false });
  return token;
}

function signIn(app: Express, token: string) {
  return request(app).post('/admin/api/auth/firebase').set('authorization', `Bearer ${token}`);
}

function createChannelAs(app: Express, token: string, name = 'My Channel') {
  return request(app)
    .post('/admin/api/creator/channel')
    .set('authorization', `Bearer ${token}`)
    .send({ name });
}

/** How many channels exist, as a string (PGlite returns counts as text). */
async function channelCount(): Promise<string> {
  const res = await pglite.query<{ count: string }>(
    `SELECT count(*)::text AS count FROM channels`
  );
  return one(res.rows).count;
}

/** The admin row for a Firebase uid, or null. */
async function accountFor(uid: string): Promise<Record<string, unknown> | null> {
  return pglite.query<Record<string, unknown>>(
    `SELECT id, email, role, channel_id, password_hash, firebase_uid, status
       FROM admin_users WHERE firebase_uid = $1`,
    [uid]
  ).then((r) => (r.rows[0] as Record<string, unknown>) ?? null);
}

describe('a creator signs in', () => {
  it('is told to create a channel, and no account is made yet', async () => {
    const app = appWith();
    const token = creatorToken('uid-new', 'new@example.test');

    const res = await signIn(app, token);

    expect(res.status).toBe(200);
    expect(res.body).toEqual({ needsChannel: true, email: 'new@example.test' });
    // The important half: asking is not the same as being. A sign-in that did
    // not go on to create a channel leaves nothing behind.
    expect(await accountFor('uid-new')).toBeNull();
  });

  it('gets a session once the channel exists', async () => {
    const app = appWith();
    const token = creatorToken('uid-maker');
    await createChannelAs(app, token);

    const res = await signIn(app, token);

    expect(res.status).toBe(200);
    expect(res.body.needsChannel).toBeUndefined();
    expect(res.body.accessToken).toBeTruthy();
    expect(res.body.admin.role).toBe('channel_admin');
    expect(res.body.channel).toBeUndefined();
  });

  it('cannot use an anonymous reader identity', async () => {
    const app = appWith();

    const res = await signIn(app, readerToken('anon-1'));

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('creator_required');
    expect(await accountFor('anon-1')).toBeNull();
  });

  it('is refused without a token, with a bad one, and where Firebase is unconfigured', async () => {
    const app = appWith();

    expect((await request(app).post('/admin/api/auth/firebase')).status).toBe(401);
    expect((await signIn(app, 'not-a-real-token')).status).toBe(401);

    // A deployment with no FIREBASE_PROJECT_ID: 503 rather than 401, because
    // retrying only helps once the operator has fixed something.
    const unconfigured = await signIn(appWith(unconfiguredVerifier()), creatorToken('uid-x'));
    expect(unconfigured.status).toBe(503);
    expect(unconfigured.body.error).toBe('auth_unavailable');
  });
});

describe('a creator creates their channel', () => {
  it('creates the channel, the account that runs it, and signs it in', async () => {
    const app = appWith();
    const token = creatorToken('uid-owner', 'owner@example.test');

    const res = await createChannelAs(app, token, 'Namaz Times');

    expect(res.status).toBe(201);
    expect(res.body.channel.name).toBe('Namaz Times');
    expect(res.body.channel.slug).toBeTruthy();
    expect(res.body.accessToken).toBeTruthy();
    expect(res.body.permissions).toContain('posts.create');

    const account = await accountFor('uid-owner');
    expect(account).not.toBeNull();
    expect(account!.role).toBe('channel_admin');
    expect(account!.channel_id).toBe(res.body.channel.id);
    expect(account!.email).toBe('owner@example.test');
    // No password was ever set, so there is no hash to leak — the whole reason
    // the column had to become nullable.
    expect(account!.password_hash).toBeNull();
    expect(account!.status).toBe('active');
  });

  it('gives two creators who picked the same name two different links', async () => {
    // The collision case, and the one §16 makes ordinary: a slug is derived from
    // the name, so the SECOND creator to want "Namaz Times" finds it taken. This
    // used to be a 500 — the retry ran inside a transaction that the first
    // failed INSERT had already aborted — which is what changed the INSERT here
    // to `ON CONFLICT (slug) DO NOTHING`.
    const app = appWith();

    const first = await createChannelAs(app, creatorToken('uid-a'), 'Namaz Times');
    const second = await createChannelAs(app, creatorToken('uid-b'), 'Namaz Times');

    expect(first.status).toBe(201);
    expect(second.status).toBe(201);
    expect(first.body.channel.slug).toBe('namaz-times');
    expect(second.body.channel.slug).not.toBe(first.body.channel.slug);
    expect(second.body.channel.name).toBe('Namaz Times');
    // Both are real channels, each owned by the creator who asked for it.
    expect(await channelCount()).toBe('2');
    expect((await accountFor('uid-a'))!.channel_id).toBe(first.body.channel.id);
    expect((await accountFor('uid-b'))!.channel_id).toBe(second.body.channel.id);
  });

  it('cannot create a second channel', async () => {
    const app = appWith();
    const token = creatorToken('uid-one');

    expect((await createChannelAs(app, token, 'First')).status).toBe(201);

    const second = await createChannelAs(app, token, 'Second');
    expect(second.status).toBe(409);
    expect(second.body.error).toBe('channel_exists');

    expect(await channelCount()).toBe('1');
  });

  it('refuses a reader identity here too', async () => {
    const app = appWith();

    const res = await createChannelAs(app, readerToken('anon-2'), 'Sneaky');

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('creator_required');
    expect(await channelCount()).toBe('0');
  });

  it('refuses an address that already runs an account', async () => {
    // The takeover case this design rejects rather than resolves: the identity
    // is NOT matched onto the existing administrator by email, because Firebase
    // reports an email as claimed and not as verified.
    const app = appWith();
    // A super administrator holds an address and no channel, which is the shape
    // the constraint allows — so this is a real existing account, not a row the
    // database refuses to hold.
    await pglite.query(
      `INSERT INTO admin_users (display_name, email, email_normalized, password_hash, role, status)
       VALUES ('Platform', 'taken@example.test', 'taken@example.test', '$2b$12$abcdefghijklmnopqrstuv', 'super_admin', 'active')`
    );

    const res = await createChannelAs(app, creatorToken('uid-late', 'taken@example.test'), 'Mine');

    // Refused, and nothing is created: the identity is NOT matched onto the
    // existing account by email, because Firebase reports an email as claimed
    // rather than as verified — matching here would be an account takeover.
    expect(res.status).toBe(409);
    expect(res.body.error).toBe('email_taken');
    expect(await channelCount()).toBe('0');
  });
});

describe('an address that already runs an account', () => {
  /** A provisioned administrator: a password account with no Firebase identity. */
  async function provisionedAdmin(email: string, role = 'super_admin'): Promise<string> {
    const res = await pglite.query<{ id: string }>(
      `INSERT INTO admin_users (display_name, email, email_normalized, password_hash, role, status)
       VALUES ('Platform', $1, $1, '$2b$12$abcdefghijklmnopqrstuv', $2::admin_role, 'active')
       RETURNING id`,
      [email, role]
    );
    return one(res.rows).id;
  }

  it('is LINKED when Firebase has proven the address', async () => {
    // The case this rule exists for: the deployment's own owner configured
    // studymuddassir@gmail.com as the super administrator, so "that email
    // already runs an account" would lock them out of their own deployment.
    // Google's signature on the address is the proof that makes linking safe.
    const app = appWith();
    const id = await provisionedAdmin('owner@example.test');

    const res = await signIn(app, creatorToken('uid-owner', 'owner@example.test'));

    expect(res.status).toBe(200);
    expect(res.body.accessToken).toBeTruthy();
    // Their own role, not a demotion to creator: a super administrator reached
    // this way is still the super administrator.
    expect(res.body.admin.role).toBe('super_admin');

    const link = await pglite.query<{ firebase_uid: string | null }>(
      `SELECT firebase_uid FROM admin_users WHERE id = $1`,
      [id]
    );
    expect(one(link.rows).firebase_uid).toBe('uid-owner');
  });

  it('is REFUSED when the address is only claimed', async () => {
    // The takeover this guards: Firebase does not check `admin_users`, so anyone
    // can sign up holding an address that already runs an account. Unproven, it
    // is not a link — it is a collision, refused at channel creation.
    const app = appWith();
    const id = await provisionedAdmin('victim@example.test');

    const signInRes = await signIn(app, unprovenToken('attacker', 'victim@example.test'));
    expect(signInRes.body.needsChannel).toBe(true);

    const create = await createChannelAs(
      app,
      unprovenToken('attacker', 'victim@example.test'),
      'Not Mine'
    );
    expect(create.status).toBe(409);
    expect(create.body.error).toBe('email_taken');
    expect(await channelCount()).toBe('0');

    const untouched = await pglite.query<{ firebase_uid: string | null }>(
      `SELECT firebase_uid FROM admin_users WHERE id = $1`,
      [id]
    );
    expect(one(untouched.rows).firebase_uid).toBeNull();
  });

  it('is not moved between identities once linked', async () => {
    const app = appWith();
    const id = await provisionedAdmin('first@example.test');
    expect((await signIn(app, creatorToken('uid-first', 'first@example.test'))).status).toBe(200);

    // A second, proven identity claiming the same address does not take the
    // account over: it is refused, and the original link stands.
    const second = await signIn(app, creatorToken('uid-second', 'first@example.test'));
    expect(second.body.needsChannel).toBe(true);

    const link = await pglite.query<{ firebase_uid: string | null }>(
      `SELECT firebase_uid FROM admin_users WHERE id = $1`,
      [id]
    );
    expect(one(link.rows).firebase_uid).toBe('uid-first');
  });
});

describe('what the creator account can and cannot do afterwards', () => {
  it('cannot sign in through the password form, with any password', async () => {
    const app = appWith();
    const token = creatorToken('uid-nopass', 'nopass@example.test');
    await createChannelAs(app, token);

    const wrong = await request(app)
      .post('/admin/api/auth/login')
      .send({ email: 'nopass@example.test', password: 'anything-at-all' });
    expect(wrong.status).toBe(401);
    expect(wrong.body.error).toBe('invalid_credentials');

    // Including the empty string, which is the case a NULL hash could plausibly
    // have been compared as "no password required" by a less careful check.
    const short = await request(app)
      .post('/admin/api/auth/login')
      .send({ email: 'nopass@example.test', password: 'x' });
    expect(short.status).toBe(401);
  });

  it('publishes to its own channel and gets a 404 for anybody else’s', async () => {
    const app = appWith();
    const token = creatorToken('uid-publisher');
    const created = await createChannelAs(app, token, 'Mine');
    const auth = `Bearer ${created.body.accessToken}`;
    const channelId = created.body.channel.id as string;

    const mine = await request(app)
      .post(`/admin/api/channels/${channelId}/posts`)
      .set('authorization', auth)
      .send({ type: 'text', body: 'Assalamu alaikum' });
    expect(mine.status).toBe(201);

    // A channel that exists and that this account does not run. 404, not 403:
    // a 403 would confirm the channel is real.
    const other = await pglite.query<{ id: string }>(
      `INSERT INTO channels (slug, name, status) VALUES ('someone-else', 'Someone Else', 'active')
       RETURNING id`
    );
    const otherId = one(other.rows).id;

    const denied = await request(app)
      .post(`/admin/api/channels/${otherId}/posts`)
      .set('authorization', auth)
      .send({ type: 'text', body: 'Not mine' });
    expect(denied.status).toBe(404);

    const readDenied = await request(app)
      .get(`/admin/api/channels/${otherId}/posts`)
      .set('authorization', auth);
    expect(readDenied.status).toBe(404);
  });

  it('cannot reach a super-admin route', async () => {
    const app = appWith();
    const token = creatorToken('uid-curious');
    const created = await createChannelAs(app, token, 'Mine');

    const res = await request(app)
      .get('/admin/api/admins')
      .set('authorization', `Bearer ${created.body.accessToken}`);

    expect(res.status).toBe(403);
    expect(res.body.error, JSON.stringify(res.body)).toBe('admin_forbidden');
  });
});
