import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { isShareableBase } from '../src/channels/service.js';
import { applyAllMigrations, asQueryable, freshDatabase, one, resetData } from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';

/**
 * The page a shared channel link opens (§6).
 *
 * Two separate things are under test, and they are separate on purpose:
 *
 *  * **The link is worth sharing.** That used to fail at the first hop — the API
 *    handed out `clearview://…`, which a messaging app renders as nothing — so
 *    the two halves are tested together: the URL the API publishes, and the page
 *    it points at.
 *
 *  * **The page is safe to serve.** It is the only place in this product where a
 *    value typed by a person is interpolated into HTML rather than JSON, so the
 *    escaping is asserted with a name that would otherwise run as a script. A
 *    channel name is free text; nothing stops one being called `<script>`.
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
function unique(prefix: string): string {
  counter += 1;
  return `${prefix}-${counter}`;
}

async function seedChannel(
  slug: string,
  options: { name?: string; description?: string | null } = {}
): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO channels (slug, name, description, status)
     VALUES ($1, $2, $3, 'active'::channel_status)
     RETURNING id`,
    [slug, options.name ?? slug, options.description ?? null]
  );
  return one(rows.rows).id;
}

async function seedPost(channelId: string, body: string, minutesAgo = 0): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO posts (channel_id, author_id, type, body, created_at)
     VALUES ($1, NULL, 'text'::post_type, $2, now() - ($3::int * interval '1 minute'))
     RETURNING id`,
    [channelId, body, minutesAgo]
  );
  return one(rows.rows).id;
}

describe('the shared-channel page (§6)', () => {
  it('renders the channel, its posts and a preview card', async () => {
    const slug = unique('shared');
    const channelId = await seedChannel(slug, {
      name: 'Dev Updates',
      description: 'Notes on shipping software.',
    });
    await seedPost(channelId, 'First post');
    await seedPost(channelId, 'Second post', 5);

    const response = await request(app).get(`/c/${slug}`);

    expect(response.status).toBe(200);
    expect(response.headers['content-type']).toContain('text/html');

    // The name and description, twice each: once in the page, once in the meta
    // tags a messaging app reads to build its card. Asserting both is the point
    // — a page that renders but previews as a bare URL is the bug this fixes.
    expect(response.text).toContain('<h1>Dev Updates</h1>')
    expect(response.text).toContain('<meta property="og:title" content="Dev Updates">');
    expect(response.text).toContain('Notes on shipping software.');

    // The posts, newest first.
    expect(response.text).toContain('First post');
    expect(response.text).toContain('Second post');
    expect(response.text.indexOf('First post')).toBeLessThan(response.text.indexOf('Second post'));

    // A way into the app, which is what turns a visitor into a reader.
    expect(response.text).toContain(`clearview://goodpost/channel/${slug}`);
  });

  it('publishes an https share link that matches the page it points at', async () => {
    // The link the API hands the app and the page the link opens, checked as a
    // pair: a share link is only as good as what it resolves to.
    const slug = unique('link');
    await seedChannel(slug, { name: 'Shared Channel' });

    const detail = await request(app).get(`/api/v1/channels/${slug}`);
    expect(detail.status).toBe(200);

    const link: string = detail.body.channel.shareLink;
    // In tests PUBLIC_BASE_URL is unset, so it defaults to localhost — which is
    // exactly the case that must NOT be handed to a recipient, because nobody
    // else can reach it. The server falls back to the deep link there.
    expect(link).toBe(`clearview://goodpost/channel/${slug}`);

    const page = await request(app).get(`/c/${slug}`);
    expect(page.status).toBe(200);
    expect(page.text).toContain('Shared Channel');
  });

  it('escapes a channel name instead of running it', async () => {
    // A channel name is free text typed by a person. This page is the only place
    // such a value reaches HTML, so an unescaped one would be stored XSS on the
    // deployment's own origin.
    const slug = unique('hostile');
    const hostile = '<script>alert(1)</script>';
    const channelId = await seedChannel(slug, { name: hostile });
    await seedPost(channelId, '<img src=x onerror=alert(2)>');

    const response = await request(app).get(`/c/${slug}`);

    expect(response.status).toBe(200);

    // The assertion that matters is about TAGS, not about the words: a payload
    // whose `<` and `>` are escaped is inert text, and its letters are expected
    // to still be on the page. What must not survive is a real element, so what
    // is checked is that neither payload opened one.
    expect(response.text).not.toContain('<script>alert(1)</script>');
    expect(response.text).not.toContain('<img src=x');

    expect(response.text).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
    // The title tag too: an unescaped value there breaks out of the element.
    expect(response.text).toContain('&lt;img src=x onerror=alert(2)&gt;');
  });

  it('keeps newlines in a post rather than collapsing them into one line', async () => {
    const slug = unique('lines');
    const channelId = await seedChannel(slug);
    await seedPost(channelId, 'First line\nSecond line');

    const response = await request(app).get(`/c/${slug}`);
    expect(response.text).toContain('First line<br>Second line');
  });

  it('answers a page, not JSON, for a channel that is gone', async () => {
    // The caller here is a browser: a JSON error body would show a recipient of
    // the link a wall of braces. The STATUS is still honest, because crawlers and
    // unfurlers read the code and not the text.
    const missing = await request(app).get('/c/no-such-channel-at-all');
    expect(missing.status).toBe(404);
    expect(missing.headers['content-type']).toContain('text/html');
    expect(missing.text).toContain('Channel not available');
    expect(missing.text).not.toContain('"error"');

    // A suspended channel is a 404 here for the same reason it is one in the API.
    const suspended = unique('suspended');
    await pglite.query(
      `INSERT INTO channels (slug, name, status) VALUES ($1, $2, 'suspended'::channel_status)`,
      [suspended, 'Taken down']
    );
    const takenDown = await request(app).get(`/c/${suspended}`);
    expect(takenDown.status).toBe(404);
    expect(takenDown.text).toContain('Channel not available');
  });

  it('says a channel with no posts is empty rather than rendering nothing', async () => {
    const slug = unique('empty');
    await seedChannel(slug, { name: 'Quiet Channel' });

    const response = await request(app).get(`/c/${slug}`);
    expect(response.status).toBe(200);
    expect(response.text).toContain('No updates yet');
  });
});

describe('deciding whether a base URL is worth putting in a share link', () => {
  it('refuses the addresses only the server itself can reach', () => {
    // A link built on one of these works for the sender and for nobody the share
    // was sent to, which is worse than the deep link it falls back to.
    expect(isShareableBase('http://localhost:8080')).toBe(false);
    expect(isShareableBase('http://127.0.0.1:8080')).toBe(false);
    expect(isShareableBase('http://[::1]:8080')).toBe(false);
    expect(isShareableBase('http://0.0.0.0:8080')).toBe(false);
    // A LAN address is unreachable from outside too, but it is not loopback and
    // the server cannot tell a home network from a VPN — so it is allowed, and
    // the operator decides by what they configure.
    expect(isShareableBase('http://192.168.1.10:8080')).toBe(true);
  });

  it('accepts a real deployment and rejects anything that is not a URL', () => {
    expect(isShareableBase('https://clearview-goodpost-api.onrender.com')).toBe(true);
    expect(isShareableBase('https://example.com/')).toBe(true);
    // Not http(s): a `javascript:` base would put a script behind every share link.
    expect(isShareableBase('javascript:alert(1)')).toBe(false);
    expect(isShareableBase('clearview://goodpost')).toBe(false);
    expect(isShareableBase('')).toBe(false);
    expect(isShareableBase('not a url at all')).toBe(false);
  });
});
