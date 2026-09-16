import type { Express } from 'express';
import request from 'supertest';
import type { PGlite } from '@electric-sql/pglite';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { closePool, type Queryable } from '../src/db.js';
import { provisionBootstrapAdmin } from '../src/admin/bootstrap.js';
import { can, roleIsSubsetOf, type AdminRole } from '../src/admin/permissions.js';
import { applyAllMigrations, asQueryable, freshDatabase, resetData } from './helpers/database.js';
import { authed, createChannelVia, fakeVerifier, registeredIn } from './helpers/accounts.js';
import { FakeObjectStore } from './helpers/storage.js';

/**
 * M6 administration (§20–§30, §32, §39).
 *
 * This suite is mostly an abuse matrix, and that is the point. The interesting
 * question about an admin surface is never "can a SUPER_ADMIN suspend someone" —
 * it is "what happens when a MODERATOR posts to the create-admin endpoint", and
 * "can a Good Post user token do any of this at all".
 *
 * Every one of those attempts is made against the real HTTP surface with a
 * real token, because that is the only way to know the answer.
 */

const CSRF = 'test-csrf-token-value';
const csrfHeaders = { Cookie: `gp_admin_csrf=${CSRF}`, 'X-CSRF-Token': CSRF };

const SUPER_PASSWORD = 'bootstrap-password-1';
const ADMIN_PASSWORD = 'second-admin-password';
const MOD_PASSWORD = 'moderator-password-1';

let pglite: PGlite;
let database: Queryable;
let app: Express;

beforeAll(async () => {
  pglite = await freshDatabase();
  await applyAllMigrations(pglite);
  database = asQueryable(pglite);
  app = buildApp({ database, verifier: fakeVerifier(), store: new FakeObjectStore() });
});

beforeEach(async () => {
  await resetData(pglite);
  // TRUNCATE cascades from users but not to the admin tables, which have no
  // foreign key to them — deliberately, since administrators are a separate
  // identity. They are cleared explicitly, and `admin_audit_logs` cannot be
  // truncated at all, so its rows are removed through a test-only path.
  await pglite.exec('DELETE FROM admin_message_reads');
  await pglite.exec('DELETE FROM admin_messages');
  // The audit rows go FIRST. `admin_audit_logs.admin_id` is a foreign key with
  // NO ACTION, so an administrator whose actions are recorded cannot be deleted
  // until those rows are gone — which is the design, and the reason the order
  // here is not arbitrary.
  await resetAuditTrail();
  await pglite.exec('DELETE FROM admin_sessions');
  await pglite.exec('DELETE FROM admin_users');
});

afterAll(async () => {
  await pglite.close();
  await closePool();
});

/**
 * The audit log is append-only, enforced by a trigger — including TRUNCATE.
 * A test that needs a clean slate must therefore drop the trigger, delete, and
 * put it back, which is itself a demonstration that the protection is real.
 */
async function resetAuditTrail(): Promise<void> {
  await pglite.exec('DROP TRIGGER IF EXISTS admin_audit_logs_no_delete ON admin_audit_logs');
  await pglite.exec('DELETE FROM admin_audit_logs');
  await pglite.exec(`
    CREATE TRIGGER admin_audit_logs_no_delete
      BEFORE DELETE ON admin_audit_logs
      FOR EACH ROW EXECUTE FUNCTION admin_audit_logs_immutable()
  `);
}

interface Session {
  accessToken: string;
  refreshToken?: string;
  user: { id: string };
}

const registered = (phone: string, name?: string, email?: string) =>
  registeredIn(app, phone, name, email);

const createChannel = (session: Session, body: Record<string, unknown>) =>
  createChannelVia(app, session, body);

/** Create an administrator directly, which is how the bootstrap does it. */
async function makeAdmin(
  role: AdminRole,
  options: { email?: string; phone?: string; password?: string } = {}
) {
  const email = options.email ?? `${role}@example.test`;
  // Written out in full rather than assembled from a prefix: an off-by-one in
  // a concatenated E.164 number produces a VALID but different number, and the
  // symptom is a confusing 401 rather than an error.
  const phone =
    options.phone ??
    (role === 'super_admin'
      ? '+923000000001'
      : role === 'admin'
        ? '+923000000002'
        : '+923000000003');
  const password = options.password ?? (role === 'super_admin' ? SUPER_PASSWORD : role === 'admin' ? ADMIN_PASSWORD : MOD_PASSWORD);

  const created = await provisionBootstrapAdmin(database, {
    displayName: `${role} user`,
    email,
    phone,
    role,
    password,
  });
  return created.admin;
}

/** Sign in through the real endpoint and return the token pair. */
async function signIn(identifier: string, password: string, expectStatus = 200) {
  const res = await request(app).post('/admin/api/auth/login').send({ identifier, password });
  expect(res.status, `admin login for ${identifier}`).toBe(expectStatus);
  return res.body as {
    accessToken: string;
    refreshToken: string;
    expiresIn: number;
    csrfToken: string;
    admin: { id: string; role: string; status: string };
  };
}

const admin = (token: string, extra: Record<string, string> = {}) => ({
  Authorization: `Bearer ${token}`,
  ...extra,
});

// ── Login (§21, §30) ────────────────────────────────────────────────────

describe('administrator sign-in (§21)', () => {
  it('signs in by email and by mobile number', async () => {
    await makeAdmin('super_admin');

    const byEmail = await signIn('super_admin@example.test', SUPER_PASSWORD);
    expect(byEmail.admin.role).toBe('super_admin');
    expect(byEmail.accessToken).toBeTruthy();
    // The refresh token is handed to the client; only its hash is stored.
    const stored = await pglite.query<{ refresh_token_hash: string }>(
      'SELECT refresh_token_hash FROM admin_sessions'
    );
    expect(stored.rows[0]?.refresh_token_hash).not.toBe(byEmail.refreshToken);

    const byPhone = await signIn('+923000000001', SUPER_PASSWORD);
    expect(byPhone.admin.id).toBe(byEmail.admin.id);
  });

  it('refuses a wrong password and an unknown account with the same answer', async () => {
    await makeAdmin('admin');

    const wrong = await request(app)
      .post('/admin/api/auth/login')
      .send({ identifier: 'admin@example.test', password: 'not-the-password' });
    const unknown = await request(app)
      .post('/admin/api/auth/login')
      .send({ identifier: 'nobody@example.test', password: 'not-the-password' });

    expect(wrong.status).toBe(401);
    expect(unknown.status).toBe(401);
    expect(wrong.body.error).toBe('invalid_credentials');
    expect(unknown.body.error).toBe('invalid_credentials');
    // §38/§21: the response must not distinguish the two cases in any way.
    expect(JSON.stringify(wrong.body)).toBe(JSON.stringify(unknown.body));
  });

  it('locks the account after the configured number of failures', async () => {
    await makeAdmin('admin');
    const attempts = Number(process.env.LOGIN_MAX_FAILED_ATTEMPTS ?? 8);

    for (let i = 0; i < attempts; i += 1) {
      const res = await request(app)
        .post('/admin/api/auth/login')
        .send({ identifier: 'admin@example.test', password: 'wrong' });
      expect(res.status).toBe(401);
    }

    const locked = await request(app)
      .post('/admin/api/auth/login')
      .send({ identifier: 'admin@example.test', password: ADMIN_PASSWORD });
    expect(locked.status).toBe(429);
    expect(locked.body.error).toBe('admin_locked');
  });

  it('tells a disabled administrator so, but only after the password is right', async () => {
    const created = await makeAdmin('admin');
    const session = await signIn('admin@example.test', ADMIN_PASSWORD);

    // Disable through the API as a super administrator.
    await makeAdmin('super_admin');
    const superSession = await signIn('super_admin@example.test', SUPER_PASSWORD);
    const disabled = await request(app)
      .post(`/admin/api/admins/${created.id}/status`)
      .set(admin(superSession.accessToken, csrfHeaders))
      .send({ status: 'disabled' });
    expect(disabled.status).toBe(200);

    // Their existing session stops working immediately.
    const me = await request(app)
      .get('/admin/api/auth/me')
      .set(admin(session.accessToken));
    expect(me.status).toBe(401);
    expect(me.body.error).toBe('session_revoked');

    const correct = await request(app)
      .post('/admin/api/auth/login')
      .send({ identifier: 'admin@example.test', password: ADMIN_PASSWORD });
    expect(correct.status).toBe(403);
    expect(correct.body.error).toBe('admin_disabled');

    const wrong = await request(app)
      .post('/admin/api/auth/login')
      .send({ identifier: 'admin@example.test', password: 'nope' });
    expect(wrong.status).toBe(401);
    expect(wrong.body.error).toBe('invalid_credentials');
  });

  it('rotates the refresh token, and detects a reused one', async () => {
    await makeAdmin('super_admin');
    const session = await signIn('super_admin@example.test', SUPER_PASSWORD);

    const first = await request(app)
      .post('/admin/api/auth/refresh')
      .set(csrfHeaders)
      .send({ refreshToken: session.refreshToken });
    expect(first.status).toBe(200);
    expect(first.body.refreshToken).not.toBe(session.refreshToken);

    // Presenting the OLD token again is the reuse case: every session for that
    // administrator is revoked, including the rotated one.
    const reuse = await request(app)
      .post('/admin/api/auth/refresh')
      .set(csrfHeaders)
      .send({ refreshToken: session.refreshToken });
    expect(reuse.status).toBe(401);

    const live = await pglite.query<{ count: string }>(
      `SELECT count(*)::text AS count FROM admin_sessions WHERE revoked_at IS NULL`
    );
    expect(Number(live.rows[0]?.count)).toBe(0);
  });

  it('refuses a mutation without the CSRF header, and accepts it with one', async () => {
    await makeAdmin('super_admin');
    const session = await signIn('super_admin@example.test', SUPER_PASSWORD);

    const missing = await request(app)
      .post('/admin/api/auth/logout')
      .set(admin(session.accessToken))
      .send({});
    expect(missing.status).toBe(403);
    expect(missing.body.error).toBe('csrf_failed');

    const mismatched = await request(app)
      .post('/admin/api/auth/logout')
      .set(admin(session.accessToken))
      .set({ Cookie: 'gp_admin_csrf=one', 'X-CSRF-Token': 'two' })
      .send({});
    expect(mismatched.status).toBe(403);

    const ok = await request(app)
      .post('/admin/api/auth/logout')
      .set(admin(session.accessToken, csrfHeaders))
      .send({});
    expect(ok.status).toBe(200);
  });
});

// ── Token separation and permissions (§28, §32, §48) ────────────────────

describe('authorization (§28, §32, §48)', () => {
  it('refuses a Good Post USER token on every admin route', async () => {
    const user = await registered('+923005550001', 'Regular user');

    const attempts = [
      request(app).get('/admin/api/overview'),
      request(app).get('/admin/api/users'),
      request(app).get('/admin/api/reports'),
      request(app).get('/admin/api/audit'),
      request(app).get('/admin/api/admins'),
      request(app)
        .post('/admin/api/messages')
        .send({ targetUserId: user.user.id, subject: 'hi', body: 'hi' }),
    ];

    for (const attempt of attempts) {
      const res = await attempt.set(authed(user.accessToken)).set(csrfHeaders);
      // 401 with an admin-shaped error: the user token is not merely
      // unauthorized, it is unverifiable (§48's separate signing key).
      expect(res.status).toBe(401);
      expect(res.body.error).toBe('invalid_token');
    }
  });

  it('refuses an admin token on the user API', async () => {
    await makeAdmin('super_admin');
    const session = await signIn('super_admin@example.test', SUPER_PASSWORD);

    const res = await request(app)
      .get('/api/v1/auth/me')
      .set(authed(session.accessToken));
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('invalid_token');
  });

  it('refuses every sensitive action to a MODERATOR, and allows the ones §28 grants', async () => {
    const user = await registered('+923005550002', 'Target user');
    const owner = await registered('+923005550003', 'Channel owner');
    const channel = await createChannel(owner, { name: 'Moderated channel' });
    const reporter = await registered('+923005550004', 'Reporter');
    const report = await request(app)
      .post('/api/v1/reports')
      .set(authed(reporter.accessToken))
      .send({ targetType: 'channel', targetId: channel.id, reason: 'spam' });
    expect(report.status).toBe(201);

    await makeAdmin('moderator');
    const mod = await signIn('moderator@example.test', MOD_PASSWORD);
    const headers = admin(mod.accessToken, csrfHeaders);

    // Allowed by §28's "reports, posts, limited user moderation".
    expect((await request(app).get('/admin/api/reports').set(headers)).status).toBe(200);
    expect((await request(app).get('/admin/api/posts').set(headers)).status).toBe(200);
    expect(
      (
        await request(app)
          .post(`/admin/api/reports/${report.body.report.id}/resolve`)
          .set(headers)
          .send({ status: 'reviewing' })
      ).status
    ).toBe(200);
    expect(
      (
        await request(app)
          .post(`/admin/api/users/${user.user.id}/sessions/revoke`)
          .set(headers)
      ).status
    ).toBe(403);

    // Refused: banning is a SUPER_ADMIN power, suspending is an ADMIN power.
    const ban = await request(app)
      .post(`/admin/api/users/${user.user.id}/status`)
      .set(headers)
      .send({ status: 'banned', reason: 'trying it on' });
    expect(ban.status).toBe(403);
    expect(ban.body.error).toBe('admin_forbidden');

    const suspend = await request(app)
      .post(`/admin/api/users/${user.user.id}/status`)
      .set(headers)
      .send({ status: 'suspended', reason: 'trying it on' });
    expect(suspend.status).toBe(403);

    // Refused: §26's official notices are an ADMIN power.
    const message = await request(app)
      .post('/admin/api/messages')
      .set(headers)
      .send({ targetUserId: user.user.id, subject: 'Notice', body: 'Body' });
    expect(message.status).toBe(403);

    // Refused: creating administrators.
    const create = await request(app)
      .post('/admin/api/admins')
      .set(headers)
      .send({
        displayName: 'Sneaky',
        email: 'sneaky@example.test',
        phone: '+923009990001',
        role: 'super_admin',
        password: 'sneaky-password-1',
      });
    expect(create.status).toBe(403);

    // Refused: reading a channel's private follower conversations.
    const conversations = await request(app)
      .get(`/admin/api/channels/${channel.id}/conversations`)
      .set(headers);
    expect(conversations.status).toBe(403);

    // Refused: suspending a channel.
    const channelStatus = await request(app)
      .post(`/admin/api/channels/${channel.id}/status`)
      .set(headers)
      .send({ status: 'suspended' });
    expect(channelStatus.status).toBe(403);

    // Refused: the banned-identity list, and lifting a ban.
    //
    // Reading the list is refused as well as changing it, which is the stricter
    // reading of §28's "reports, posts, limited user moderation": a ban list is
    // a list of people the platform has excluded, and neither reviewing a post
    // nor working a report needs it.
    const identities = await request(app).get('/admin/api/identities').set(headers);
    expect(identities.status).toBe(403);
    const lift = await request(app)
      .post('/admin/api/identities/11111111-2222-3333-4444-555555555555/lift')
      .set(headers);
    expect(lift.status).toBe(403);

    // Refused: platform settings and administrator list.
    expect((await request(app).get('/admin/api/settings').set(headers)).status).toBe(403);
    expect((await request(app).get('/admin/api/admins').set(headers)).status).toBe(403);

    // The refusals are recorded, which is the whole point of an audit log.
    const audit = await request(app).get('/admin/api/audit').set(headers);
    expect(audit.status).toBe(200);
    const denied = audit.body.items.filter(
      (row: { outcome: string }) => row.outcome === 'denied'
    );
    expect(denied.length).toBeGreaterThan(0);
    expect(
      denied.some((row: { action: string }) => row.action === 'denied.users.ban')
    ).toBe(true);
  });

  it('lets an ADMIN moderate users and channels but not create administrators', async () => {
    const user = await registered('+923005550005', 'Target user');
    await makeAdmin('admin');
    const session = await signIn('admin@example.test', ADMIN_PASSWORD);
    const headers = admin(session.accessToken, csrfHeaders);

    const suspend = await request(app)
      .post(`/admin/api/users/${user.user.id}/status`)
      .set(headers)
      .send({ status: 'suspended', reason: 'under review' });
    expect(suspend.status).toBe(200);
    expect(suspend.body.status).toBe('suspended');

    // §28 puts "ban users" under SUPER_ADMIN.
    const ban = await request(app)
      .post(`/admin/api/users/${user.user.id}/status`)
      .set(headers)
      .send({ status: 'banned', reason: 'escalation attempt' });
    expect(ban.status).toBe(403);

    const create = await request(app)
      .post('/admin/api/admins')
      .set(headers)
      .send({
        displayName: 'Another',
        email: 'another@example.test',
        phone: '+923009990002',
        role: 'admin',
        password: 'another-password-1',
      });
    expect(create.status).toBe(403);
  });

  it('keeps the role hierarchy honest: every MODERATOR grant is an ADMIN grant', async () => {
    // A matrix where a junior role holds something a senior one lacks would mean
    // the documented hierarchy is wrong even if each grant is defensible.
    expect(roleIsSubsetOf('moderator', 'admin')).toBe(true);
    expect(roleIsSubsetOf('admin', 'super_admin')).toBe(true);
    expect(roleIsSubsetOf('moderator', 'super_admin')).toBe(true);

    // And the two asymmetries §28 asks for are real.
    expect(can('admin', 'users.ban')).toBe(false);
    expect(can('super_admin', 'users.ban')).toBe(true);
    expect(can('admin', 'admins.manage')).toBe(false);
  });
});

// ── Moderation through the API (§23, §24, §25, §19) ─────────────────────

describe('moderation actions (§23, §24, §25)', () => {
  async function asSuperAdmin() {
    await makeAdmin('super_admin');
    const session = await signIn('super_admin@example.test', SUPER_PASSWORD);
    return admin(session.accessToken, csrfHeaders);
  }

  it('bans a user: identity blocked, sessions gone, and the number cannot re-register', async () => {
    const victim = await registered('+923005560001', 'To be banned');
    const headers = await asSuperAdmin();

    const res = await request(app)
      .post(`/admin/api/users/${victim.user.id}/status`)
      .set(headers)
      .send({ status: 'banned', reason: 'repeated abuse', note: 'reviewed by hand' });
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ status: 'banned', sessionsRevoked: 1 });

    const me = await request(app).get('/api/v1/auth/me').set(authed(victim.accessToken));
    expect(me.status).toBe(401);

    await request(app)
      .post('/api/v1/auth/otp/request')
      .send({ phone: '+923005560001', purpose: 'register' });
    const again = await request(app)
      .post('/api/v1/auth/register')
      .send({ idToken: 'test:+923005560001', displayName: 'Fresh start', email: 'fresh@example.test' });
    expect(again.status).toBe(403);
    expect(again.body.error).toBe('phone_banned');

    // The ban appears in the identities list, and its note is preserved.
    const identities = await request(app).get('/admin/api/identities').set(headers);
    expect(identities.body.items).toHaveLength(1);
    expect(identities.body.items[0]).toMatchObject({
      userId: victim.user.id,
      reason: 'repeated abuse',
      note: 'reviewed by hand',
    });

    // Lifting it makes the number registrable again and reinstates the account.
    const identityId = identities.body.items[0].id;
    const lift = await request(app)
      .post(`/admin/api/identities/${identityId}/lift`)
      .set(headers);
    expect(lift.status).toBe(200);

    const after = await request(app).get(`/admin/api/users/${victim.user.id}`).set(headers);
    expect(after.body.user.status).toBe('active');
    expect(after.body.user.phoneBanned).toBe(false);
  });

  it('removes and restores a post, keeping the channel counters honest', async () => {
    const owner = await registered('+923005560002', 'Channel owner');
    const channel = await createChannel(owner, { name: 'Content channel' });
    const post = await request(app)
      .post(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken))
      .send({ body: 'questionable' });
    expect(post.status).toBe(201);

    const headers = await asSuperAdmin();
    const removed = await request(app)
      .post(`/admin/api/posts/${post.body.post.id}/remove`)
      .set(headers)
      .send({ reason: 'violates policy' });
    expect(removed.status).toBe(200);

    // Gone from the channel's history and from the counters.
    const history = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken));
    expect(history.body.items).toHaveLength(0);
    const channelDetail = await request(app)
      .get(`/api/v1/channels/${channel.id}`)
      .set(authed(owner.accessToken));
    expect(channelDetail.body.channel.postCount).toBe(0);

    // Visible in the removed-posts filter, with the reason recorded.
    const removedList = await request(app)
      .get('/admin/api/posts?removed=true')
      .set(headers);
    expect(removedList.body.items).toHaveLength(1);
    expect(removedList.body.items[0].removedReason).toBe('violates policy');

    const restored = await request(app)
      .post(`/admin/api/posts/${post.body.post.id}/restore`)
      .set(headers);
    expect(restored.status).toBe(200);

    const after = await request(app)
      .get(`/api/v1/channels/${channel.id}/posts`)
      .set(authed(owner.accessToken));
    expect(after.body.items).toHaveLength(1);
  });

  it('suspends a channel without touching its owner`s account', async () => {
    const owner = await registered('+923005560003', 'Channel owner');
    const channel = await createChannel(owner, { name: 'To suspend' });
    const headers = await asSuperAdmin();

    const res = await request(app)
      .post(`/admin/api/channels/${channel.id}/status`)
      .set(headers)
      .send({ status: 'suspended', reason: 'repeated reports' });
    expect(res.status).toBe(200);

    const detail = await request(app).get(`/admin/api/channels/${channel.id}`).set(headers);
    expect(detail.body.channel.status).toBe('suspended');

    const account = await request(app).get(`/admin/api/users/${owner.user.id}`).set(headers);
    expect(account.body.user.status).toBe('active');
  });

  it('works a report from open to resolved, and records what was done', async () => {
    const owner = await registered('+923005560004', 'Channel owner');
    const channel = await createChannel(owner, { name: 'Reported channel' });
    const reporter = await registered('+923005560005', 'Reporter');
    const filed = await request(app)
      .post('/api/v1/reports')
      .set(authed(reporter.accessToken))
      .send({ targetType: 'channel', targetId: channel.id, reason: 'spam', details: 'lots of junk' });
    expect(filed.status).toBe(201);
    const reportId = filed.body.report.id as string;

    await makeAdmin('admin');
    const session = await signIn('admin@example.test', ADMIN_PASSWORD);
    const headers = admin(session.accessToken, csrfHeaders);

    const queue = await request(app).get('/admin/api/reports').set(headers);
    expect(queue.body.items).toHaveLength(1);
    expect(queue.body.items[0].reporterName).toBe('Reporter');

    // The queue carries the target's content, so a moderator can judge it.
    const detail = await request(app).get(`/admin/api/reports/${reportId}`).set(headers);
    expect(detail.status).toBe(200);
    expect(detail.body.context.summary.name).toBe('Reported channel');

    const claim = await request(app)
      .post(`/admin/api/reports/${reportId}/resolve`)
      .set(headers)
      .send({ status: 'reviewing' });
    expect(claim.status).toBe(200);
    expect(claim.body.report.status).toBe('reviewing');

    // A resolution must say what was done — an empty decision is not a record.
    const empty = await request(app)
      .post(`/admin/api/reports/${reportId}/resolve`)
      .set(headers)
      .send({ status: 'resolved' });
    expect(empty.status).toBe(400);
    expect(empty.body.error).toBe('action_required');

    const resolved = await request(app)
      .post(`/admin/api/reports/${reportId}/resolve`)
      .set(headers)
      .send({ status: 'resolved', actionTaken: 'channel suspended', resolutionNote: 'third report this week' });
    expect(resolved.status).toBe(200);
    expect(resolved.body.report).toMatchObject({
      status: 'resolved',
      actionTaken: 'channel suspended',
    });
    expect(resolved.body.report.resolvedAt).not.toBeNull();

    // The reporter sees the outcome on their own report, and nothing else.
    const mine = await request(app).get('/api/v1/reports/mine').set(authed(reporter.accessToken));
    expect(mine.body.items[0].actionTaken).toBe('channel suspended');
    expect(JSON.stringify(mine.body)).not.toContain('third report this week');
  });

  it('sends an official message that lands in the recipient`s inbox', async () => {
    const user = await registered('+923005560006', 'Notice recipient');
    const headers = await asSuperAdmin();

    const sent = await request(app)
      .post('/admin/api/messages')
      .set(headers)
      .send({ targetUserId: user.user.id, subject: 'Please review', body: 'Your channel was suspended.' });
    expect(sent.status).toBe(201);

    const notices = await request(app).get('/api/v1/notices').set(authed(user.accessToken));
    expect(notices.status).toBe(200);
    expect(notices.body.items).toHaveLength(1);
    expect(notices.body.items[0]).toMatchObject({
      subject: 'Please review',
      readAt: null,
    });

    const marked = await request(app)
      .post('/api/v1/notices/read')
      .set(authed(user.accessToken))
      .send({ ids: [notices.body.items[0].id] });
    expect(marked.status).toBe(200);
    expect(marked.body.marked).toBe(1);

    const after = await request(app).get('/api/v1/notices').set(authed(user.accessToken));
    expect(after.body.items[0].readAt).not.toBeNull();

    // A message to another user must not appear in this one's inbox.
    const other = await registered('+923005560007', 'Bystander');
    const theirs = await request(app).get('/api/v1/notices').set(authed(other.accessToken));
    expect(theirs.body.items).toHaveLength(0);
  });

  it('creates an administrator, and refuses to demote the last super administrator', async () => {
    const headers = await asSuperAdmin();

    const created = await request(app)
      .post('/admin/api/admins')
      .set(headers)
      .send({
        displayName: 'New moderator',
        email: 'new-mod@example.test',
        phone: '+923005570001',
        role: 'moderator',
        password: 'a-long-enough-password',
      });
    expect(created.status).toBe(201);
    expect(created.body.admin.role).toBe('moderator');

    // The new administrator can sign in immediately.
    const session = await signIn('new-mod@example.test', 'a-long-enough-password');
    expect(session.admin.role).toBe('moderator');

    // A weak password is refused, with a code the dashboard can word.
    const weak = await request(app)
      .post('/admin/api/admins')
      .set(headers)
      .send({
        displayName: 'Weak',
        email: 'weak@example.test',
        phone: '+923005570002',
        role: 'admin',
        password: 'short',
      });
    expect(weak.status).toBe(400);
    expect(weak.body.error).toBe('weak_password');

    // Demoting the only SUPER_ADMIN would leave a deployment nobody can
    // administer, so it is refused rather than merely warned about.
    const admins = await request(app).get('/admin/api/admins').set(headers);
    const superAdmin = admins.body.items.find(
      (row: { role: string }) => row.role === 'super_admin'
    );
    const demote = await request(app)
      .post(`/admin/api/admins/${superAdmin.id}/role`)
      .set(headers)
      .send({ role: 'admin' });
    expect(demote.status).toBe(409);
    expect(demote.body.error).toBe('last_super_admin');

    // Disabling yourself is refused for the same reason.
    const selfDisable = await request(app)
      .post(`/admin/api/admins/${superAdmin.id}/status`)
      .set(headers)
      .send({ status: 'disabled' });
    expect(selfDisable.status).toBe(400);
    expect(selfDisable.body.error).toBe('cannot_disable_self');
  });
});

// ── The audit log (§29) ─────────────────────────────────────────────────

describe('the audit log (§29)', () => {
  it('records who did what, and cannot be edited or deleted', async () => {
    const user = await registered('+923005580001', 'Audited user');
    await makeAdmin('super_admin');
    const session = await signIn('super_admin@example.test', SUPER_PASSWORD);
    const headers = admin(session.accessToken, csrfHeaders);

    await request(app)
      .post(`/admin/api/users/${user.user.id}/status`)
      .set(headers)
      .send({ status: 'suspended', reason: 'audit test' });

    const audit = await request(app).get('/admin/api/audit').set(headers);
    const suspension = audit.body.items.find(
      (row: { action: string }) => row.action === 'user.suspended'
    );
    expect(suspension).toBeTruthy();
    expect(suspension.adminEmail).toBe('super_admin@example.test');
    expect(suspension.actorRole).toBe('super_admin');
    expect(suspension.targetId).toBe(user.user.id);
    expect(suspension.metadata.reason).toBe('audit test');

    // No credential may have reached the log — §30's rule, and the metadata
    // column is where it would happen by accident.
    const asText = JSON.stringify(audit.body);
    expect(asText).not.toContain(SUPER_PASSWORD);
    expect(asText).not.toContain(session.accessToken);
    expect(asText).not.toContain(session.refreshToken);

    // Append-only, enforced by the database rather than by this API.
    await expect(pglite.exec("UPDATE admin_audit_logs SET action = 'tampered'")).rejects.toThrow();
    await expect(pglite.exec('DELETE FROM admin_audit_logs')).rejects.toThrow();
    await expect(pglite.exec('TRUNCATE admin_audit_logs')).rejects.toThrow();
  });

  it('records a login and a failed login', async () => {
    await makeAdmin('admin');

    await signIn('admin@example.test', ADMIN_PASSWORD);
    await request(app)
      .post('/admin/api/auth/login')
      .send({ identifier: 'admin@example.test', password: 'wrong' });

    await makeAdmin('super_admin');
    const session = await signIn('super_admin@example.test', SUPER_PASSWORD);
    const audit = await request(app)
      .get('/admin/api/audit')
      .set(admin(session.accessToken, csrfHeaders));

    const actions = audit.body.items.map((row: { action: string }) => row.action);
    expect(actions).toContain('admin.login');
    // A failure is recorded too: a log of successes cannot answer "did anyone
    // try?".
    const failed = audit.body.items.find((row: { outcome: string }) => row.outcome === 'failed');
    expect(failed).toBeTruthy();
  });
});

// ── The dashboard shell (§22, §31) ──────────────────────────────────────

describe('the dashboard is served without leaking anything', () => {
  it('serves the shell, stylesheet and script with no data in them', async () => {
    const shell = await request(app).get('/admin');
    expect(shell.status).toBe(200);
    expect(shell.headers['content-type']).toContain('text/html');
    expect(shell.text).toContain('Good Post administration');
    // No credential, key or connection string can be in a static shell.
    expect(shell.text).not.toContain('DATABASE_URL');
    expect(shell.text).not.toContain('AWS');

    const css = await request(app).get('/admin/app.css');
    expect(css.status).toBe(200);
    expect(css.headers['content-type']).toContain('text/css');

    const js = await request(app).get('/admin/app.js');
    expect(js.status).toBe(200);
    expect(js.headers['content-type']).toContain('javascript');
    // The client never touches the database or the bucket directly (§31).
    expect(js.text).not.toContain('neon.tech');
    expect(js.text).not.toContain('AWS_SECRET');
  });
});
