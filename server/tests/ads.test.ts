import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import {
  applyAllMigrations,
  asQueryable,
  freshDatabase,
  resetData,
} from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';
import {
  authed,
  createChannelWithAdmin,
  signIn,
  superAdminSession,
  type Session,
} from './helpers/accounts.js';

/**
 * Advertisement cards (§9–§17).
 *
 * Two claims are asserted here, and both are about the SERVER rather than the
 * UI: that only an authorized administrator may mutate a card, and that the
 * public read returns exactly the cards that are currently active for the
 * requested placement — never an expired, disabled or elsewhere-placed one.
 * A rule enforced only by hiding a button is not a rule.
 */

let pglite: PGlite;
let app: Express;
let store: FakeObjectStore;
let superAdmin: Session;

const SEEDED_DEFAULT_AD = '00000000-0000-4000-8000-000000000001';

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
  // Advertisements are not part of resetData (they are not reader content), so
  // each case removes the rows it may have added and keeps the seeded default.
  await pglite.exec(`DELETE FROM advertisements WHERE id <> '${SEEDED_DEFAULT_AD}'`);
  store.reset();
  superAdmin = await superAdminSession(app, pglite);
});

/** Upload and confirm an image, returning its mediaId. */
async function uploadImage(session: Session): Promise<string> {
  const upload = await request(app)
    .post('/admin/api/media/uploads')
    .set(authed(session.accessToken))
    .send({ contentType: 'image/png', byteSize: 1024 });
  expect(upload.status, JSON.stringify(upload.body)).toBe(201);
  const mediaId = upload.body.upload.mediaId as string;
  store.put();
  const confirmed = await request(app)
    .post(`/admin/api/media/uploads/${mediaId}/confirm`)
    .set(authed(session.accessToken));
  expect(confirmed.status, JSON.stringify(confirmed.body)).toBe(200);
  return mediaId;
}

function iso(offsetMs: number): string {
  return new Date(Date.now() + offsetMs).toISOString();
}

describe('advertisement authorization (§16)', () => {
  it('refuses an unauthenticated read', async () => {
    const res = await request(app).get('/admin/api/ads');
    expect(res.status).toBe(401);
  });

  it('refuses a channel administrator, who may not touch platform ads', async () => {
    const { adminEmail, password } = await createChannelWithAdmin(app, superAdmin, 'Ads Channel');
    const channelAdmin = await signIn(app, adminEmail, password);

    const read = await request(app)
      .get('/admin/api/ads')
      .set(authed(channelAdmin.accessToken));
    expect(read.status).toBe(403);

    const create = await request(app)
      .post('/admin/api/ads')
      .set(authed(channelAdmin.accessToken))
      .send({ contentType: 'text', text: 'buy this' });
    expect(create.status).toBe(403);
  });

  it('lets the public read advertisements with no token at all', async () => {
    const res = await request(app).get('/api/v1/ads?placement=channels');
    expect(res.status).toBe(200);
    expect(Array.isArray(res.body.ads)).toBe(true);
  });
});

describe('a text card (§11)', () => {
  it('is created, listed, edited, disabled and deleted', async () => {
    const create = await request(app)
      .post('/admin/api/ads')
      .set(authed(superAdmin.accessToken))
      .send({
        contentType: 'text',
        text: 'Half price today',
        targetUrl: 'https://example.test/offer',
        showInChannels: true,
        showInExplore: false,
        priority: 5,
      });
    expect(create.status, JSON.stringify(create.body)).toBe(201);
    const id = create.body.ad.id as string;
    expect(create.body.ad.text).toBe('Half price today');

    const list = await request(app).get('/admin/api/ads').set(authed(superAdmin.accessToken));
    expect(list.body.ads.some((a: { id: string }) => a.id === id)).toBe(true);

    const patch = await request(app)
      .patch(`/admin/api/ads/${id}`)
      .set(authed(superAdmin.accessToken))
      .send({
        contentType: 'text',
        text: 'Now 25% off',
        showInChannels: true,
        showInExplore: true,
        enabled: false,
      });
    expect(patch.status, JSON.stringify(patch.body)).toBe(200);
    expect(patch.body.ad.text).toBe('Now 25% off');
    expect(patch.body.ad.enabled).toBe(false);

    const del = await request(app)
      .delete(`/admin/api/ads/${id}`)
      .set(authed(superAdmin.accessToken));
    expect(del.status).toBe(200);

    const gone = await request(app)
      .get(`/admin/api/ads/${id}`)
      .set(authed(superAdmin.accessToken));
    expect(gone.status).toBe(404);
  });

  it('refuses a text card with no text', async () => {
    const res = await request(app)
      .post('/admin/api/ads')
      .set(authed(superAdmin.accessToken))
      .send({ contentType: 'text', text: '   ' });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('ad_content_required');
  });

  it('refuses a destination that is not an http(s) or mailto link', async () => {
    const res = await request(app)
      .post('/admin/api/ads')
      .set(authed(superAdmin.accessToken))
      .send({ contentType: 'text', text: 'hi', targetUrl: 'javascript:alert(1)' });
    expect(res.status).toBe(400);
  });
});

describe('an image card (§11)', () => {
  it('claims an uploaded image and serves it publicly as a signed URL', async () => {
    const mediaId = await uploadImage(superAdmin);

    const create = await request(app)
      .post('/admin/api/ads')
      .set(authed(superAdmin.accessToken))
      .send({
        contentType: 'image',
        mediaId,
        targetUrl: 'https://example.test',
        showInChannels: true,
        showInExplore: true,
      });
    expect(create.status, JSON.stringify(create.body)).toBe(201);
    expect(create.body.ad.imageUrl).toContain('fake-bucket.test');

    const publicRead = await request(app).get('/api/v1/ads?placement=explore');
    const mine = publicRead.body.ads.find(
      (a: { id: string }) => a.id === create.body.ad.id
    );
    expect(mine).toBeDefined();
    expect(mine.imageUrl).toContain('fake-bucket.test');
  });

  it('refuses an image card with no image', async () => {
    const res = await request(app)
      .post('/admin/api/ads')
      .set(authed(superAdmin.accessToken))
      .send({ contentType: 'image' });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('ad_image_required');
  });
});

describe('display rules (§12)', () => {
  async function create(body: Record<string, unknown>): Promise<string> {
    const res = await request(app)
      .post('/admin/api/ads')
      .set(authed(superAdmin.accessToken))
      .send({ contentType: 'text', text: 'card', ...body });
    expect(res.status, JSON.stringify(res.body)).toBe(201);
    return res.body.ad.id as string;
  }

  async function publicIds(placement: 'channels' | 'explore'): Promise<Set<string>> {
    const res = await request(app).get(`/api/v1/ads?placement=${placement}`);
    return new Set(res.body.ads.map((a: { id: string }) => a.id));
  }

  it('hides a card from a placement it is not set to appear in', async () => {
    const id = await create({ showInChannels: true, showInExplore: false });
    expect((await publicIds('channels')).has(id)).toBe(true);
    expect((await publicIds('explore')).has(id)).toBe(false);
  });

  it('never returns a disabled card', async () => {
    const id = await create({ enabled: false });
    expect((await publicIds('channels')).has(id)).toBe(false);
    expect((await publicIds('explore')).has(id)).toBe(false);
  });

  it('never returns an expired card', async () => {
    const id = await create({ startsAt: iso(-2 * 3600_000), expiresAt: iso(-3600_000) });
    expect((await publicIds('channels')).has(id)).toBe(false);
  });

  it('never returns a card that has not started yet', async () => {
    const id = await create({ startsAt: iso(3600_000) });
    expect((await publicIds('channels')).has(id)).toBe(false);
  });

  it('returns a currently-active card', async () => {
    const id = await create({ startsAt: iso(-60_000) });
    expect((await publicIds('channels')).has(id)).toBe(true);
  });

  it('seeds one editable default card with a mailto destination', async () => {
    const res = await request(app).get('/api/v1/ads?placement=channels');
    const seeded = res.body.ads.find((a: { id: string }) => a.id === SEEDED_DEFAULT_AD);
    expect(seeded).toBeDefined();
    expect(seeded.text).toContain('YOUR AD HERE');
    expect(seeded.targetUrl).toContain('mailto:studymuddassir@gmail.com');
  });
});
