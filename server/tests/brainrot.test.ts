import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { applyAllMigrations, asQueryable, freshDatabase, insertAdmin, one, resetData } from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';
import { adminSessionExpiry, mintAdminRefreshToken, signAdminAccessToken } from '../src/admin/tokens.js';

/**
 * The global Brain Rot protection repository.
 *
 * The product rule this suite exists to protect is stated plainly in the spec:
 * **one user's accidental submission must not become everyone's rule.** That is
 * a claim about which code paths can reach the rule tables, so the tests below
 * are mostly about what a device CANNOT do — it can suggest, and it can report,
 * and neither of those changes what anybody blocks until an administrator
 * approves it.
 *
 * Every request from a device is sent WITHOUT an Authorization header, because
 * anonymity is part of what is under test: a route that quietly started
 * requiring a token would break the product's privacy promise, and a suite that
 * authenticated would not notice.
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

let counter = 0;
/** A fresh device id per fixture, so one case's report cannot answer another's. */
function deviceId(): string {
  counter += 1;
  const hex = counter.toString(16).padStart(12, '0');
  return `00000000-0000-4000-8000-${hex}`;
}

/**
 * A signed-in super admin — the only role that may change a rule.
 *
 * Goes through a REAL session row rather than minting a token against a fake
 * session id, because `requireAdmin` checks that the session is live: a token
 * whose session does not exist is refused, so a fixture that skipped the row
 * would be testing the rejection path instead of the authorization one.
 */
async function adminToken(): Promise<string> {
  const adminId = await insertAdmin(pglite);
  const refresh = mintAdminRefreshToken();
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO admin_sessions (admin_id, refresh_token_hash, expires_at)
     VALUES ($1, $2, $3) RETURNING id`,
    [adminId, refresh.hash, adminSessionExpiry().toISOString()]
  );
  const sessionId = one(rows.rows).id;
  return signAdminAccessToken({ sub: adminId, sid: sessionId }, 'super_admin');
}

describe('the global rule set', () => {
  it('is readable with no credentials at all', async () => {
    const res = await request(app).get('/api/v1/brainrot/rules');
    expect(res.status).toBe(200);
    expect(Array.isArray(res.body.keywords)).toBe(true);
    expect(Array.isArray(res.body.channels)).toBe(true);
  });

  it('ships no hardcoded keywords — the list fills from real approvals', async () => {
    const res = await request(app).get('/api/v1/brainrot/rules');
    // The four starter keywords 014 seeded are gone, and nothing replaced them.
    // The product decision is that nothing is blocked globally that a human did
    // not ask for, so a fresh deployment blocks nothing globally.
    expect(res.body.keywords).toEqual([]);
  });

  it('an approved rule carries a real report count, not a missing value', async () => {
    const token = await adminToken();
    await request(app)
      .post('/admin/api/brainrot/keywords')
      .set('Authorization', `Bearer ${token}`)
      .send({ keyword: 'doomscrolling' });
    await request(app)
      .post('/api/v1/brainrot/reports')
      .send({ kind: 'keyword', value: 'doomscrolling', anonymousId: deviceId() });

    const res = await request(app).get('/api/v1/brainrot/rules');
    const rule = res.body.keywords.find(
      (k: { keyword: string }) => k.keyword === 'doomscrolling'
    );
    expect(rule.reports).toBe(1);
  });

  it('carries a version that changes when a rule does', async () => {
    const before = await request(app).get('/api/v1/brainrot/rules');
    const token = await adminToken();
    await request(app)
      .post('/admin/api/brainrot/keywords')
      .set('Authorization', `Bearer ${token}`)
      .send({ keyword: 'doomscrolling' });

    const after = await request(app).get('/api/v1/brainrot/rules');
    expect(after.body.version).not.toBe(before.body.version);
  });
});

describe('what a device may do anonymously', () => {
  it('suggests a keyword without an account, and it blocks nothing yet', async () => {
    const res = await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'skibidi', anonymousId: deviceId() });

    expect(res.status).toBe(202);
    expect(res.body.status).toBe('pending');
    expect(res.body.value).toBe('skibidi');

    // The suggestion is inert: the active rule set does not contain it, so no
    // other device blocks on it.
    const rules = await request(app).get('/api/v1/brainrot/rules');
    expect(rules.body.keywords.map((k: { keyword: string }) => k.keyword)).not.toContain('skibidi');
  });

  it('normalises what it is given, so one keyword is one rule', async () => {
    const res = await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: '  Skibidi   Toilet  ', anonymousId: deviceId() });
    expect(res.body.value).toBe('skibidi toilet');
  });

  it('normalises a channel to a single leading @', async () => {
    const res = await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'channel', value: '@@ExampleChannel', anonymousId: deviceId() });
    expect(res.body.value).toBe('@examplechannel');
  });

  it('refuses a suggestion that is not a plausible handle', async () => {
    const res = await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'channel', value: 'https://youtube.com/@foo', anonymousId: deviceId() });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_channel_handle');
  });

  it('refuses a device id that is not one', async () => {
    const res = await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'x', anonymousId: 'not-a-uuid' });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_anonymous_id');
  });

  it('counts one report per device, not one per tap', async () => {
    const device = deviceId();
    const first = await request(app)
      .post('/api/v1/brainrot/reports')
      .send({ kind: 'keyword', value: 'viral', anonymousId: device });
    expect(first.body.reports).toBe(1);

    // The same device reporting again must not inflate the number — a count of
    // taps is not the signal a reviewer needs.
    const second = await request(app)
      .post('/api/v1/brainrot/reports')
      .send({ kind: 'keyword', value: 'viral', anonymousId: device });
    expect(second.status).toBe(200);
    expect(second.body.reports).toBe(1);
  });

  it('counts two devices as two reports', async () => {
    await request(app)
      .post('/api/v1/brainrot/reports')
      .send({ kind: 'keyword', value: 'doomscrolling', anonymousId: deviceId() });
    const res = await request(app)
      .post('/api/v1/brainrot/reports')
      .send({ kind: 'keyword', value: 'doomscrolling', anonymousId: deviceId() });
    expect(res.body.reports).toBe(2);
  });

  it('cannot change a rule — there is no such route on the public surface', async () => {
    const res = await request(app)
      .post('/api/v1/brainrot/keywords')
      .send({ keyword: 'mine' });
    // 404: the route does not exist here. The one that does is on the admin
    // prefix, behind a token.
    expect(res.status).toBe(404);
  });
});

describe('the review queue', () => {
  it('refuses an unauthenticated caller', async () => {
    const res = await request(app).get('/admin/api/brainrot/submissions');
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('missing_token');
  });

  it('shows a pending suggestion to an administrator', async () => {
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'nonsense', note: 'everywhere', anonymousId: deviceId() });

    const token = await adminToken();
    const res = await request(app)
      .get('/admin/api/brainrot/submissions')
      .set('Authorization', `Bearer ${token}`);

    expect(res.status).toBe(200);
    const item = res.body.submissions.find((s: { value: string }) => s.value === 'nonsense');
    expect(item.status).toBe('pending');
    expect(item.note).toBe('everywhere');
  });

  it('turns an approved suggestion into a global rule', async () => {
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'engagementbait', anonymousId: deviceId() });

    const token = await adminToken();
    const queue = await request(app)
      .get('/admin/api/brainrot/submissions')
      .set('Authorization', `Bearer ${token}`);
    const pending = queue.body.submissions.find((s: { value: string }) => s.value === 'engagementbait');

    const review = await request(app)
      .post(`/admin/api/brainrot/submissions/${pending.id}/review`)
      .set('Authorization', `Bearer ${token}`)
      .send({ decision: 'approved' });
    expect(review.status).toBe(200);
    expect(review.body.ruleId).not.toBeNull();

    // Only NOW does it block anything anywhere.
    const rules = await request(app).get('/api/v1/brainrot/rules');
    expect(rules.body.keywords.map((k: { keyword: string }) => k.keyword)).toContain('engagementbait');
  });

  it('turns a rejected suggestion into nothing at all', async () => {
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'channel', value: '@spamchannel', anonymousId: deviceId() });

    const token = await adminToken();
    const queue = await request(app)
      .get('/admin/api/brainrot/submissions')
      .set('Authorization', `Bearer ${token}`);
    const pending = queue.body.submissions.find((s: { value: string }) => s.value === '@spamchannel');

    const review = await request(app)
      .post(`/admin/api/brainrot/submissions/${pending.id}/review`)
      .set('Authorization', `Bearer ${token}`)
      .send({ decision: 'rejected' });
    expect(review.body.ruleId).toBeNull();

    const rules = await request(app).get('/api/v1/brainrot/rules');
    expect(rules.body.channels.map((c: { handle: string }) => c.handle)).not.toContain('@spamchannel');
  });

  it('refuses to review the same suggestion twice', async () => {
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'twice', anonymousId: deviceId() });

    const token = await adminToken();
    const queue = await request(app)
      .get('/admin/api/brainrot/submissions')
      .set('Authorization', `Bearer ${token}`);
    const pending = queue.body.submissions.find((s: { value: string }) => s.value === 'twice');

    const first = await request(app)
      .post(`/admin/api/brainrot/submissions/${pending.id}/review`)
      .set('Authorization', `Bearer ${token}`)
      .send({ decision: 'approved' });
    expect(first.status).toBe(200);

    const second = await request(app)
      .post(`/admin/api/brainrot/submissions/${pending.id}/review`)
      .set('Authorization', `Bearer ${token}`)
      .send({ decision: 'rejected' });
    expect(second.status).toBe(400);
    expect(second.body.error).toBe('submission_already_reviewed');
  });

  it('does not queue the same value twice from one device', async () => {
    const device = deviceId();
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'once', anonymousId: device });
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'once', anonymousId: device });

    const token = await adminToken();
    const res = await request(app)
      .get('/admin/api/brainrot/submissions')
      .set('Authorization', `Bearer ${token}`);
    const matching = res.body.submissions.filter((s: { value: string }) => s.value === 'once');
    expect(matching).toHaveLength(1);
  });
});

describe('where a submission came from, and where it is', () => {
  it('records the source and the channel name a suggestion carried', async () => {
    await request(app).post('/api/v1/brainrot/suggestions').send({
      kind: 'channel',
      value: '@fromyoutube',
      source: 'youtube_not_interested',
      displayName: 'From YouTube',
      anonymousId: deviceId(),
    });

    const token = await adminToken();
    const res = await request(app)
      .get('/admin/api/brainrot/submissions')
      .set('Authorization', `Bearer ${token}`);
    const item = res.body.submissions.find((s: { value: string }) => s.value === '@fromyoutube');
    expect(item.source).toBe('youtube_not_interested');
    expect(item.displayName).toBe('From YouTube');
  });

  it('defaults an older client with no source to unknown rather than refusing it', async () => {
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'sourceless', anonymousId: deviceId() });

    const token = await adminToken();
    const res = await request(app)
      .get('/admin/api/brainrot/submissions')
      .set('Authorization', `Bearer ${token}`);
    const item = res.body.submissions.find((s: { value: string }) => s.value === 'sourceless');
    expect(item.source).toBe('unknown');
  });

  it('marks a suggestion under review without deciding it', async () => {
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'considering', anonymousId: deviceId() });

    const token = await adminToken();
    const queue = await request(app)
      .get('/admin/api/brainrot/submissions')
      .set('Authorization', `Bearer ${token}`);
    const pending = queue.body.submissions.find((s: { value: string }) => s.value === 'considering');

    const review = await request(app)
      .post(`/admin/api/brainrot/submissions/${pending.id}/review`)
      .set('Authorization', `Bearer ${token}`)
      .send({ decision: 'under_review' });
    expect(review.status).toBe(200);
    // It did NOT become a rule.
    expect(review.body.ruleId).toBeNull();
    const rules = await request(app).get('/api/v1/brainrot/rules');
    expect(rules.body.keywords.map((k: { keyword: string }) => k.keyword)).not.toContain('considering');

    // ...and it can still be decided afterwards.
    const decide = await request(app)
      .post(`/admin/api/brainrot/submissions/${pending.id}/review`)
      .set('Authorization', `Bearer ${token}`)
      .send({ decision: 'approved' });
    expect(decide.body.ruleId).not.toBeNull();
  });

  it('shows a device the fate of its own submissions', async () => {
    const device = deviceId();
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'mine', anonymousId: device });
    // Another device's submission must not appear in this device's list.
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'theirs', anonymousId: deviceId() });

    const mine = await request(app)
      .get('/api/v1/brainrot/submissions')
      .query({ anonymousId: device });
    expect(mine.status).toBe(200);
    expect(mine.body.submissions.map((s: { value: string }) => s.value)).toEqual(['mine']);
  });

  it('refuses a malformed device id on the submissions read', async () => {
    const res = await request(app)
      .get('/api/v1/brainrot/submissions')
      .query({ anonymousId: 'not-a-uuid' });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_anonymous_id');
  });
});

describe('the admin dashboard', () => {
  it('counts the queue by status', async () => {
    const device = deviceId();
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'dash-a', anonymousId: device });
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'keyword', value: 'dash-b', anonymousId: deviceId() });

    const token = await adminToken();
    const res = await request(app)
      .get('/admin/api/brainrot/dashboard')
      .set('Authorization', `Bearer ${token}`);
    expect(res.status).toBe(200);
    expect(res.body.totals.pending).toBe(2);
    expect(res.body.totals.all).toBe(2);
  });

  it('reports how many devices blocked and how many requested a target', async () => {
    // Two devices request it globally; one of them also reports it.
    const a = deviceId();
    const b = deviceId();
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'channel', value: '@demanded', anonymousId: a });
    await request(app)
      .post('/api/v1/brainrot/suggestions')
      .send({ kind: 'channel', value: '@demanded', anonymousId: b });
    await request(app)
      .post('/api/v1/brainrot/reports')
      .send({ kind: 'channel', value: '@demanded', anonymousId: a });

    const token = await adminToken();
    const res = await request(app)
      .get('/admin/api/brainrot/dashboard')
      .set('Authorization', `Bearer ${token}`);
    const row = res.body.topChannels.find((c: { value: string }) => c.value === '@demanded');
    expect(row.globalRequests).toBe(2);
    expect(row.usersBlocking).toBe(1);
  });

  it('refuses the dashboard to an unauthenticated caller', async () => {
    const res = await request(app).get('/admin/api/brainrot/dashboard');
    expect(res.status).toBe(401);
  });
});

describe('managing the global rules directly', () => {
  it('adds and removes a keyword', async () => {
    const token = await adminToken();
    const created = await request(app)
      .post('/admin/api/brainrot/keywords')
      .set('Authorization', `Bearer ${token}`)
      .send({ keyword: 'Chatter', reason: 'Noise' });
    expect(created.status).toBe(201);
    expect(created.body.keyword.keyword).toBe('chatter');

    const removed = await request(app)
      .delete(`/admin/api/brainrot/keywords/${created.body.keyword.id}`)
      .set('Authorization', `Bearer ${token}`);
    expect(removed.status).toBe(200);

    const rules = await request(app).get('/api/v1/brainrot/rules');
    expect(rules.body.keywords.map((k: { keyword: string }) => k.keyword)).not.toContain('chatter');
  });

  it('is idempotent — adding the same keyword twice is one rule', async () => {
    const token = await adminToken();
    await request(app)
      .post('/admin/api/brainrot/keywords')
      .set('Authorization', `Bearer ${token}`)
      .send({ keyword: 'duplicate' });
    await request(app)
      .post('/admin/api/brainrot/keywords')
      .set('Authorization', `Bearer ${token}`)
      .send({ keyword: 'DUPLICATE' });

    const rows = await pglite.query<{ count: number }>(
      `SELECT COUNT(*)::int AS count FROM brainrot_keywords WHERE keyword = 'duplicate'`
    );
    expect(one(rows.rows).count).toBe(1);
  });

  it('disables a rule without deleting it, and the phone stops seeing it', async () => {
    const token = await adminToken();
    const created = await request(app)
      .post('/admin/api/brainrot/keywords')
      .set('Authorization', `Bearer ${token}`)
      .send({ keyword: 'temporarily' });

    await request(app)
      .post(`/admin/api/brainrot/keywords/${created.body.keyword.id}/status`)
      .set('Authorization', `Bearer ${token}`)
      .send({ enabled: false });

    // Gone from the public list the phone fetches...
    const rules = await request(app).get('/api/v1/brainrot/rules');
    expect(rules.body.keywords.map((k: { keyword: string }) => k.keyword)).not.toContain('temporarily');

    // ...but still there for the administrator, so it can be turned back on.
    const admin = await request(app)
      .get('/admin/api/brainrot/keywords')
      .set('Authorization', `Bearer ${token}`);
    const row = admin.body.keywords.find((k: { keyword: string }) => k.keyword === 'temporarily');
    expect(row.enabled).toBe(false);
  });

  it('adds and removes a global channel by handle', async () => {
    const token = await adminToken();
    const created = await request(app)
      .post('/admin/api/brainrot/channels')
      .set('Authorization', `Bearer ${token}`)
      .send({ handle: '@BlockedCreator', displayName: 'Blocked Creator' });
    expect(created.status).toBe(201);
    expect(created.body.channel.handle).toBe('@blockedcreator');

    const rules = await request(app).get('/api/v1/brainrot/rules');
    const handle = rules.body.channels.find((c: { handle: string }) => c.handle === '@blockedcreator');
    expect(handle.name).toBe('Blocked Creator');

    await request(app)
      .delete(`/admin/api/brainrot/channels/${created.body.channel.id}`)
      .set('Authorization', `Bearer ${token}`);
    const after = await request(app).get('/api/v1/brainrot/rules');
    expect(after.body.channels.map((c: { handle: string }) => c.handle)).not.toContain('@blockedcreator');
  });

  it('reports a missing keyword as a 404 rather than a 500', async () => {
    const token = await adminToken();
    const res = await request(app)
      .delete('/admin/api/brainrot/keywords/11111111-1111-4111-8111-111111111111')
      .set('Authorization', `Bearer ${token}`);
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('keyword_not_found');
  });
});

/**
 * The anonymity claim, asserted against the schema rather than the prose.
 *
 * The Privacy card says blocking activity is linked only to an anonymous id and
 * that no personally identifying information is collected. That is only true if
 * there is nowhere to put any — so this checks the tables, not the copy.
 */
describe('what the repository stores about a device', () => {
  it('keeps an anonymous id and nothing that identifies a person', async () => {
    const columns = await pglite.query<{ column_name: string }>(
      `SELECT column_name FROM information_schema.columns
        WHERE table_name IN ('brainrot_devices', 'brainrot_reports', 'brainrot_submissions')
        ORDER BY column_name`
    );
    const names = columns.rows.map((r) => r.column_name);
    for (const forbidden of ['email', 'phone', 'ip', 'ip_hash', 'firebase_uid', 'android_id', 'user_id']) {
      expect(names, `column ${forbidden}`).not.toContain(forbidden);
    }
  });
});
