import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { applyAllMigrations, asQueryable, freshDatabase, resetData } from './helpers/database.js';
import { authed, createChannelWithAdmin, signIn, superAdminSession, type Session } from './helpers/accounts.js';

/**
 * The curated channel directory (§ new).
 *
 * Two things are asserted, and both are the SERVER's rather than the app's: that
 * only a super administrator may change the directory, and that adding a channel
 * files and normalises it the way the read expects. The name and icon come from
 * the platform, so `fetch` is stubbed — the rule under test is ours, and
 * depending on YouTube being reachable would mean it is never tested.
 */

let pglite: PGlite;
let app: Express;
let superAdmin: Session;

beforeAll(async () => {
  pglite = await freshDatabase();
  await applyAllMigrations(pglite);
  app = buildApp({ database: asQueryable(pglite) });
});

afterAll(async () => {
  await pglite.close();
});

beforeEach(async () => {
  await resetData(pglite);
  // The platforms are stubbed for every case. A page with the two Open Graph
  // tags the enricher reads, and nothing else.
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      new Response(
        '<html><head>' +
          '<meta property="og:title" content="Khan Academy">' +
          '<meta property="og:image" content="https://example.test/khan.png">' +
          '</head><body></body></html>',
        { status: 200, headers: { 'content-type': 'text/html' } }
      )
    )
  );
  superAdmin = await superAdminSession(app, pglite);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

// ── The public read ──────────────────────────────────────────────────────

describe('the public directory read', () => {
  it('returns the seeded categories to an anonymous caller', async () => {
    const res = await request(app).get('/api/v1/directory');
    expect(res.status).toBe(200);
    const slugs = (res.body.categories as { slug: string }[]).map((c) => c.slug);
    expect(slugs).toContain('islamic');
    expect(slugs).toContain('education');
    expect(res.body.unfiled).toEqual([]);
  });
});

// ── Administration ───────────────────────────────────────────────────────

describe('a super administrator curating the directory', () => {
  it('creates a category and a subcategory under it', async () => {
    const category = await request(app)
      .post('/admin/api/directory/categories')
      .set(authed(superAdmin.accessToken))
      .send({ name: 'Science', sort: 5 });
    expect(category.status).toBe(201);

    const sub = await request(app)
      .post('/admin/api/directory/subcategories')
      .set(authed(superAdmin.accessToken))
      .send({ categoryId: category.body.category.id, name: 'Physics' });
    expect(sub.status).toBe(201);

    const snapshot = await request(app).get('/api/v1/directory');
    const science = (snapshot.body.categories as { slug: string; subcategories: { slug: string }[] }[])
      .find((c) => c.slug === 'science');
    expect(science?.subcategories.map((s) => s.slug)).toContain('physics');
  });

  it('adds a channel by handle, copying its name and icon from the platform', async () => {
    const res = await request(app)
      .post('/admin/api/directory/channels')
      .set(authed(superAdmin.accessToken))
      .send({ platform: 'youtube', handle: '@KhanAcademy' });

    expect(res.status).toBe(201);
    // The "@" and the case are gone, and the platform's own name and picture
    // have been filled in.
    expect(res.body.channel.handle).toBe('khanacademy');
    expect(res.body.channel.name).toBe('Khan Academy');
    expect(res.body.channel.iconUrl).toBe('https://example.test/khan.png');
    // The share target is built by the server, so the client never has to.
    expect(res.body.channel.url).toBe('https://www.youtube.com/@khanacademy');
  });

  it('files a channel under the subcategory AND its category', async () => {
    const category = await request(app)
      .post('/admin/api/directory/categories')
      .set(authed(superAdmin.accessToken))
      .send({ name: 'Science' });
    const sub = await request(app)
      .post('/admin/api/directory/subcategories')
      .set(authed(superAdmin.accessToken))
      .send({ categoryId: category.body.category.id, name: 'Physics' });

    const res = await request(app)
      .post('/admin/api/directory/channels')
      .set(authed(superAdmin.accessToken))
      .send({
        platform: 'x',
        handle: 'physics',
        subcategoryId: sub.body.subcategory.id,
      });
    expect(res.status).toBe(201);

    const snapshot = await request(app).get('/api/v1/directory');
    const science = (snapshot.body.categories as { slug: string }[]).find((c) => c.slug === 'science') as
      | { subcategories: { slug: string; channels: { handle: string }[] }[] }
      | undefined;
    const physics = science?.subcategories.find((s) => s.slug === 'physics');
    expect(physics?.channels.map((c) => c.handle)).toContain('physics');
  });

  it('refuses the same handle twice on one platform', async () => {
    await request(app)
      .post('/admin/api/directory/channels')
      .set(authed(superAdmin.accessToken))
      .send({ platform: 'instagram', handle: 'nas' });

    const again = await request(app)
      .post('/admin/api/directory/channels')
      .set(authed(superAdmin.accessToken))
      .send({ platform: 'instagram', handle: '@NAS' });

    expect(again.status).toBe(400);
    expect(again.body.error).toBe('channel_exists');
  });

  it('refuses a malformed handle', async () => {
    const res = await request(app)
      .post('/admin/api/directory/channels')
      .set(authed(superAdmin.accessToken))
      .send({ platform: 'youtube', handle: 'not a handle' });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_handle');
  });

  it('unfiles a category’s channels instead of deleting them', async () => {
    const category = await request(app)
      .post('/admin/api/directory/categories')
      .set(authed(superAdmin.accessToken))
      .send({ name: 'Temporary' });
    await request(app)
      .post('/admin/api/directory/channels')
      .set(authed(superAdmin.accessToken))
      .send({ platform: 'youtube', handle: 'keepme', categoryId: category.body.category.id });

    const removed = await request(app)
      .delete(`/admin/api/directory/categories/${category.body.category.id}`)
      .set(authed(superAdmin.accessToken));
    expect(removed.status).toBe(200);

    const snapshot = await request(app).get('/api/v1/directory');
    expect((snapshot.body.unfiled as { handle: string }[]).map((c) => c.handle)).toContain('keepme');
  });
});

describe('a channel administrator', () => {
  it('cannot read or change the directory', async () => {
    const { adminEmail, password } = await createChannelWithAdmin(app, superAdmin, 'Owner Channel');
    const channelAdmin = await signIn(app, adminEmail, password);

    const read = await request(app)
      .get('/admin/api/directory')
      .set(authed(channelAdmin.accessToken));
    expect(read.status).toBe(403);

    const write = await request(app)
      .post('/admin/api/directory/categories')
      .set(authed(channelAdmin.accessToken))
      .send({ name: 'Nope' });
    expect(write.status).toBe(403);
  });
});
