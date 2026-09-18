import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import type { PGlite } from '@electric-sql/pglite';
import { buildApp } from '../src/app.js';
import { isShareableBase } from '../src/channels/service.js';
import { applyAllMigrations, asQueryable, freshDatabase, one, resetData } from './helpers/database.js';
import { FakeObjectStore } from './helpers/storage.js';
import { UnconfiguredObjectStore } from '../src/media/store.js';

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

/** A post that carries a link, which the page renders as a card. */
async function seedLinkPost(
  channelId: string,
  url: string,
  title: string | null
): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO posts (channel_id, author_id, type, body, link_url, link_title)
     VALUES ($1, NULL, 'link'::post_type, NULL, $2, $3)
     RETURNING id`,
    [channelId, url, title]
  );
  return one(rows.rows).id;
}

/** An image on a post, ready to be signed. */
async function seedImage(postId: string): Promise<string> {
  const rows = await pglite.query<{ id: string }>(
    `INSERT INTO post_media
       (owner_id, post_id, kind, object_key, content_type, byte_size, width, height, status, position)
     VALUES (NULL, $1, 'image'::media_kind, $2, 'image/jpeg', 4096, 1200, 800,
             'ready'::media_status, 0)
     RETURNING id`,
    [postId, `goodpost/channels/photo/${postId}.jpg`]
  );
  return one(rows.rows).id;
}

/** A channel with a profile picture, which the page shows through its own route. */
async function seedIcon(channelId: string, key: string): Promise<void> {
  await pglite.query(
    `INSERT INTO post_media
       (owner_id, channel_id, post_id, kind, object_key, content_type, byte_size, status, position)
     VALUES (NULL, $1, NULL, 'image'::media_kind, $2, 'image/png', 1024, 'ready'::media_status, 0)`,
    [channelId, key]
  );
  await pglite.query(`UPDATE channels SET icon_object_key = $2 WHERE id = $1`, [channelId, key]);
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
    expect(response.text).toContain('<meta property="og:title" content="Dev Updates · ClearView">');
    expect(response.text).toContain('Notes on shipping software.');

    // The description sits UNDER the name — it is the answer to "what is this
    // channel", and the handle that used to sit there was the name again. The
    // follower count and the picture's own stable URL follow it.
    const header = response.text.slice(
      response.text.indexOf('<header class="profile">'),
      response.text.indexOf('</header>')
    );
    expect(header).toContain('<h1>Dev Updates</h1>');
    expect(header).toContain('Notes on shipping software.');
    expect(header.indexOf('Notes on shipping software.')).toBeGreaterThan(
      header.indexOf('<h1>Dev Updates</h1>')
    );
    // And no handle line: the slug under the name was the name again.
    expect(header).not.toMatch(/@(?:[a-z0-9-]+)</);
    expect(response.text).toContain('0 followers');
    expect(response.text).toContain(`/c/${slug}/icon`);

    // The posts, newest first.
    expect(response.text).toContain('First post');
    expect(response.text).toContain('Second post');
    expect(response.text.indexOf('First post')).toBeLessThan(response.text.indexOf('Second post'));

    // A way into the app, which is what turns a visitor into a reader — once,
    // not twice: the same pair of buttons at the top and the bottom asked a
    // visitor to choose between two identical things before they had read
    // anything.
    expect(response.text).toContain(`clearview://goodpost/channel/${slug}`);
    expect(response.text.match(/Open in ClearView/g)).toHaveLength(1);
    expect(response.text.match(/Get ClearView/g)).toHaveLength(1);
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
    expect(missing.text).toContain('Channel unavailable');
    expect(missing.text).not.toContain('"error"');
    // It still introduces the app, in the same words the channel page uses: this
    // is a dead end a visitor may be seeing first, and two pages describing two
    // different products is how a brand reads as unfinished.
    expect(missing.text).toContain('Read and search the Quran');

    // A suspended channel is a 404 here for the same reason it is one in the API.
    const suspended = unique('suspended');
    await pglite.query(
      `INSERT INTO channels (slug, name, status) VALUES ($1, $2, 'suspended'::channel_status)`,
      [suspended, 'Taken down']
    );
    const takenDown = await request(app).get(`/c/${suspended}`);
    expect(takenDown.status).toBe(404);
    expect(takenDown.text).toContain('Channel unavailable');
  });

  it('renders a post the way the app renders it, not as its markers (§17)', async () => {
    // The body is stored with WhatsApp's markers left in it, and the app turns
    // them into bold text. A page that printed the asterisks instead would be the
    // same post seen two ways — which is the one thing a shared link cannot do.
    const slug = unique('format');
    const channelId = await seedChannel(slug, { name: 'Formatting' });
    await seedPost(
      channelId,
      'This is *important* and _quiet_ and ~gone~ and ```code```.'
    );

    const response = await request(app).get(`/c/${slug}`);

    expect(response.text).toContain('<strong>important</strong>');
    expect(response.text).toContain('<em>quiet</em>');
    expect(response.text).toContain('<s>gone</s>');
    expect(response.text).toContain('<code>code</code>');
    expect(response.text).not.toContain('*important*');
    expect(response.text).not.toContain('```code```');
  });

  it('leaves arithmetic alone rather than reading it as formatting (§17)', async () => {
    // The guard the app uses: a delimiter cannot be part of a word. A page that
    // bolded `2*3*4` would bold the one thing nobody meant.
    const slug = unique('maths');
    const channelId = await seedChannel(slug);
    await seedPost(channelId, 'Total is 2*3*4 items');

    const response = await request(app).get(`/c/${slug}`);

    expect(response.text).toContain('2*3*4');
    expect(response.text).not.toContain('<strong>');
  });

  it('shows a post\'s image as a tile of the preview grid, through a signed URL', async () => {
    const slug = unique('photo');
    const channelId = await seedChannel(slug);
    const postId = await seedPost(channelId, 'Look at this');
    await seedImage(postId);

    const response = await request(app).get(`/c/${slug}`);

    // A tile rather than a full-width picture: the grid is what makes six mixed
    // posts a preview instead of a feed.
    expect(response.text).toContain('<div class="grid">');
    expect(response.text).toContain('<img class="tile-media" loading="lazy"');
    expect(response.text).toContain('https://fake-bucket.test/goodpost/channels/photo/');
    // Cropped to the square the grid is made of — the one place this page crops
    // on purpose, since mixed shapes at their true sizes leave the rows ragged.
    expect(response.text).toContain('object-fit: cover');
    // And the tile opens the picture itself, full size, because the square it is
    // shown in is a crop of it.
    expect(response.text).toMatch(/<a class="tile" href="https:\/\/fake-bucket\.test\//);
  });

  it('opens the media in an overlay the visitor can page through (§6)', async () => {
    // A visitor sent a link with six pictures should be able to see all six
    // without six round trips through the back button, and without leaving the
    // channel for an app they may not have.
    const slug = unique('lightbox');
    const channelId = await seedChannel(slug);
    const first = await seedPost(channelId, 'First', 20);
    await seedImage(first);
    const second = await seedPost(channelId, 'Second', 10);
    await seedImage(second);

    const response = await request(app).get(`/c/${slug}`);

    // Each tile says what it is and where it points, which is all the overlay
    // needs — and the href stays, so a visitor with no JavaScript still gets the
    // full-size picture.
    expect(response.text.match(/data-lightbox/g)).toHaveLength(2);
    expect(response.text).toContain('data-kind="image"');
    expect(response.text).toMatch(/<a class="tile" href="https:\/\/fake-bucket\.test\/[^"]+" data-lightbox/);

    // One overlay, hidden, with its controls and a way into the app from it.
    expect(response.text).toContain('<div class="lightbox" id="lightbox" hidden');
    expect(response.text).toContain('id="lb-stage"');
    expect(response.text).toContain('id="lb-close"');
    expect(response.text).toContain('id="lb-prev"');
    expect(response.text).toContain('id="lb-next"');
    expect(response.text).toContain('id="lb-count"');

    const script = await request(app).get('/c/app.js');
    expect(script.text).toContain("getElementById('lightbox')");
    expect(script.text).toContain('ArrowLeft');
    expect(script.text).toContain('touchstart');
  });

  it('draws no overlay on a channel with nothing to show in one', async () => {
    // A text-only channel has no media, so there is nothing to page through and
    // the hidden panel is not rendered at all.
    const slug = unique('nobox');
    const channelId = await seedChannel(slug);
    await seedPost(channelId, 'Words only');

    const response = await request(app).get(`/c/${slug}`);

    expect(response.text).not.toContain('data-lightbox');
    expect(response.text).not.toContain('id="lightbox"');
  });

  it('carries ClearView\'s own mark, in the header and as the tab icon (§14)', async () => {
    // The split heart is the app's icon, carried over as path data: black left
    // half, deep-red right half. Both colours are pinned here because the mark
    // means nothing in one colour, and the page is the only place outside the
    // launcher that draws it.
    const slug = unique('brand');
    await seedChannel(slug, { name: 'Branded' });

    const response = await request(app).get(`/c/${slug}`);

    const header = response.text.slice(
      response.text.indexOf('<p class="brand">'),
      response.text.indexOf('</p>', response.text.indexOf('<p class="brand">'))
    );
    expect(header).toContain('<span class="brand-mark">');
    expect(header).toContain('<svg');
    expect(header).toContain('fill="#000000"');
    expect(header).toContain('fill="#C62828"');
    expect(header).toContain('ClearView');

    // And the mark sits on a WHITE tile, which is the only reason the black half
    // can be seen at all: the page's canvas is near-black, and the app's icon is
    // a black-and-red heart on white. A logo whose left half has vanished is
    // worse than no logo, so the pairing is pinned here rather than trusted.
    expect(response.text).toMatch(/\.brand-mark \{[^}]*background: #ffffff/s);
    expect(response.text).toContain('<link rel="icon" type="image/svg+xml" href="/c/logo.svg">');

    // And the mark is served, as SVG, at its own stable URL.
    const logo = await request(app).get('/c/logo.svg');
    expect(logo.status).toBe(200);
    expect(logo.headers['content-type']).toContain('image/svg+xml');
    // Superagent hands an unparsed image/svg+xml body back as a buffer rather than
    // as text, so read whichever it is: the assertion is about what was served.
    const svg = typeof logo.text === 'string' ? logo.text : String(logo.body);
    expect(svg).toContain('#C62828');
    expect(svg).toContain('<svg');
  });

  it('marks a video tile with a play badge and does not autoplay it', async () => {
    // Six clips starting by themselves is bandwidth the brief rules out (§15), so
    // the tile asks for metadata and a first frame and nothing more.
    const slug = unique('clip');
    const channelId = await seedChannel(slug);
    const rows = await pglite.query<{ id: string }>(
      `INSERT INTO posts (channel_id, author_id, type, created_at)
       VALUES ($1, NULL, 'video'::post_type, now())
       RETURNING id`,
      [channelId]
    );
    const postId = one(rows.rows).id;
    await pglite.query(
      `INSERT INTO post_media
         (owner_id, post_id, kind, object_key, content_type, byte_size, width, height, status, position)
       VALUES (NULL, $1, 'video'::media_kind, $2, 'video/mp4', 8192, 640, 360,
               'ready'::media_status, 0)`,
      [postId, `goodpost/channels/clip/${postId}.mp4`]
    );

    const response = await request(app).get(`/c/${slug}`);

    expect(response.text).toContain('class="play"');
    expect(response.text).toContain('preload="metadata"');
    expect(response.text).toContain('#t=0.1');
    expect(response.text).not.toContain('autoplay');
    expect(response.text).not.toContain('<video controls');
  });

  it('renders a link post as a row with the domain, not as a bare URL', async () => {
    const slug = unique('linkcard');
    const channelId = await seedChannel(slug);
    await seedLinkPost(channelId, 'https://example.com/a-post', 'A post worth reading');

    const response = await request(app).get(`/c/${slug}`);

    // A row, not a square: words get the width (§7).
    expect(response.text).toContain('class="post-link-title"');
    expect(response.text).toContain('A post worth reading');
    expect(response.text).toContain('example.com');
    // A link row is a preview with nothing behind it here, so it opens the
    // channel in the app rather than the URL it is about.
    expect(response.text).not.toContain('href="https://example.com/a-post"');
  });

  it('puts the words in rows above the squares, one post per line (§8)', async () => {
    const slug = unique('two-sections');
    const channelId = await seedChannel(slug);
    // Three posts of words and two of pictures, alternated by time, so the
    // grouping cannot happen by accident of insertion order.
    await seedPost(channelId, 'Oldest words', 50);
    const firstPicture = await seedPost(channelId, 'A caption nobody sees', 40);
    await seedImage(firstPicture);
    await seedPost(channelId, 'Middle words', 30);
    const secondPicture = await seedPost(channelId, 'Another caption', 20);
    await seedImage(secondPicture);
    await seedPost(channelId, 'Newest words', 5);

    const response = await request(app).get(`/c/${slug}`);

    // Every text post is a row, in its own container, before the grid.
    expect(response.text.match(/class="post-row"/g)).toHaveLength(3);
    expect(response.text.match(/class="tile"/g)).toHaveLength(2);
    const rows = response.text.indexOf('<div class="rows">');
    const grid = response.text.indexOf('<div class="grid">');
    expect(rows).toBeGreaterThan(-1);
    expect(grid).toBeGreaterThan(rows);
    // And each row is a line of its own rather than a cell: the container is a
    // column, which is what "one per line" means in CSS.
    expect(response.text).toContain('.rows { display: flex; flex-direction: column;');
    // Newest first inside a section, whichever section it is in.
    expect(response.text.indexOf('Newest words')).toBeLessThan(
      response.text.indexOf('Oldest words')
    );
    // The media label appears with both sections present, since two groups need
    // telling apart.
    expect(response.text).toContain('<h3 class="section-heading">Media</h3>');
  });

  it('leaves the second heading off when there is only one kind of post', async () => {
    const slug = unique('one-section');
    const channelId = await seedChannel(slug);
    await seedPost(channelId, 'Just words');

    const response = await request(app).get(`/c/${slug}`);

    expect(response.text).toContain('<h2 class="section-heading">Latest posts</h2>');
    expect(response.text).not.toContain('<h3 class="section-heading">Media</h3>');
  });

  it('shows the newest three posts of each kind and nothing older (§12)', async () => {
    // The rule the whole page is built around: a shared link is a preview, not
    // the channel's archive. Three of words and three of media, and the fourth
    // of each is not on the page at all — not fetched and hidden, absent.
    //
    // The two kinds are aged alternately, so "the newest three of each" cannot
    // happen by insertion order: a preview that merely took the newest six and
    // split them would fail here, because the newest six are three of each only
    // when the channel alternated by hand.
    const slug = unique('preview');
    const channelId = await seedChannel(slug);
    const pictures: string[] = [];
    for (let i = 0; i < 5; i += 1) {
      await seedPost(channelId, `Words number ${i}`, i * 2);
    }
    for (let i = 0; i < 5; i += 1) {
      const withPicture = await seedPost(channelId, `Caption ${i}`, i * 2 + 1);
      await seedImage(withPicture);
      pictures.push(withPicture);
    }

    const response = await request(app).get(`/c/${slug}`);

    expect(response.text.match(/class="post-row"/g)).toHaveLength(3);
    expect(response.text.match(/class="tile"/g)).toHaveLength(3);
    expect(response.text).toContain('Words number 0');
    expect(response.text).toContain('Words number 2');
    expect(response.text).not.toContain('Words number 3');
    // The fourth picture is absent from the page, and its media was never signed:
    // the key only reaches the HTML through a URL from the store.
    for (const id of pictures.slice(0, 3)) expect(response.text).toContain(`${id}.jpg`);
    for (const id of pictures.slice(3)) expect(response.text).not.toContain(`${id}.jpg`);
    // And the page says what it is rather than pretending to be the whole channel.
    expect(response.text).toContain('See all posts in the app.');
    expect(response.text).toContain('a preview of the newest 6 updates');
  });

  it('cuts a long post to a preview, and leaves the rest to the app (§7)', async () => {
    const slug = unique('longpost');
    const channelId = await seedChannel(slug);
    const long = `${'The channel keeps posting and the words keep coming. '.repeat(8)}END OF POST`;
    await seedPost(channelId, long);

    const response = await request(app).get(`/c/${slug}`);

    // Cut at a word, and cut short: a row that contains the whole post is not a
    // preview.
    expect(response.text).not.toContain('END OF POST');
    expect(response.text).not.toMatch(/coming\.\w/);
    // And no second invitation to press the card. The card IS the link, so a text
    // row prints no "Open →" and no "Read more →" under a cut whose end is in the
    // app anyway.
    expect(response.text).not.toContain('Read more');
    expect(response.text).not.toContain('Open \u2192');
  });

  it('dates every card, in the corner of a tile and above the words', async () => {
    const slug = unique('dates');
    const channelId = await seedChannel(slug);
    await seedPost(channelId, 'Dated words', 90);
    const picture = await seedPost(channelId, 'Dated picture', 30);
    await seedImage(picture);

    const response = await request(app).get(`/c/${slug}`);

    // A row shows the full instant, because a row has the width for it; a tile
    // shows the day, because a 110px square does not.
    expect(response.text).toMatch(
      /<time class="post-row-date" datetime="[^"]+">[^<]+UTC<\/time>/
    );
    expect(response.text).toMatch(/<time class="tile-date" datetime="[^"]+">\d+ \w+<\/time>/);
    // The `datetime` is a real instant rather than a repeat of the printed label,
    // which is what a crawler and a screen reader read.
    expect(response.text).toMatch(/datetime="\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/);
  });

  it('ends on one card holding two buttons and a note about the app (§11)', async () => {
    const slug = unique('more');
    const channelId = await seedChannel(slug);
    await seedPost(channelId, 'Something worth reading');

    const response = await request(app).get(`/c/${slug}`);
    const start = response.text.indexOf('<section class="more">');
    const more = response.text.slice(start, response.text.indexOf('</section>', start));

    expect(more).toContain('See more from this channel');
    // The note is the point of the card: the page is a preview and the rest is in
    // the app, said once, next to the two ways in.
    expect(more).toContain('See all posts in the app');
    expect(more.match(/class="button primary"/g)).toHaveLength(1);
    expect(more.match(/class="button"/g)).toHaveLength(1);
    expect(more.match(/<a /g)).toHaveLength(2);
  });

  it('lets the page load media from the bucket rather than only from its own origin', async () => {
    // Pinned because the failure is invisible: Helmet's default `img-src 'self'
    // refuses every cross-origin image, so the page rendered with an empty avatar
    // and no media at all while the network tab showed nothing but a console-only
    // CSP violation. The page's whole job is showing a channel, most of which is
    // pictures.
    const slug = unique('csp');
    await seedChannel(slug);

    const response = await request(app).get(`/c/${slug}`);
    const csp = String(response.headers['content-security-policy'] ?? '');

    expect(csp).toContain("img-src 'self' data: https:");
    expect(csp).toContain("media-src 'self' https:");
    // Still no wildcard, and scripts still only from this origin.
    expect(csp).not.toContain('img-src *');
    expect(csp).toContain("script-src 'self'");
  });

  it('serves the channel picture through a URL that is signed when it is fetched', async () => {
    // Not the signed URL itself: that expires in minutes, and the consumers that
    // matter — a crawler building a preview card, a browser left open — arrive
    // later than that. This route signs on each request instead.
    const slug = unique('avatar');
    const channelId = await seedChannel(slug, { name: 'With A Picture' });
    await seedIcon(channelId, `goodpost/channels/icon/${slug}.png`);

    const icon = await request(app).get(`/c/${slug}/icon`);
    expect(icon.status).toBe(302);
    expect(icon.headers['location']).toContain(`goodpost/channels/icon/${slug}.png`);

    // A channel with no picture answers with a transparent image rather than a
    // 404: the page draws the channel's initial behind it, and a failed request
    // would put a broken-image glyph on top of that.
    const bare = unique('no-avatar');
    await seedChannel(bare, { name: 'No Picture' });
    const blank = await request(app).get(`/c/${bare}/icon`);
    expect(blank.status).toBe(200);
    expect(blank.headers['content-type']).toContain('image/svg+xml');
  });

  it('offers both ways into the app, including a store listing (§6)', async () => {
    const slug = unique('cta');
    await seedChannel(slug, { name: 'Call To Action' });

    const response = await request(app).get(`/c/${slug}`);

    expect(response.text).toContain('Open in ClearView');
    expect(response.text).toContain('Get ClearView');
    // The listing for this repository's own package, because PLAY_STORE_URL is
    // unset in tests — the default has to point at a real app, not a placeholder.
    expect(response.text).toContain(
      'https://play.google.com/store/apps/details?id=com.muddassir.clearview'
    );
    // The page's script is a file, because the CSP this service sends refuses
    // inline handlers — see `buildApp`.
    expect(response.text).toContain('<script src="/c/app.js" defer></script>');
    const script = await request(app).get('/c/app.js');
    expect(script.status).toBe(200);
    expect(script.headers['content-type']).toContain('javascript');
  });

  it('introduces ClearView rather than stopping at the channel (§6)', async () => {
    const slug = unique('about');
    await seedChannel(slug, { name: 'About Test' });

    const response = await request(app).get(`/c/${slug}`);

    expect(response.text).toContain('<h2>ClearView</h2>');
    expect(response.text).toContain('Explore ClearView on Google Play');
    // Concise: one paragraph, not a marketing page.
    expect(response.text.match(/<h2>ClearView<\/h2>/g)).toHaveLength(1);

    // And the paragraph describes the whole app rather than only channels: a
    // visitor who arrived from a shared link has met Good Post and nothing else.
    const about = response.text.slice(response.text.indexOf('<section class="about">'));
    expect(about).toContain('Read and search the Quran');
    expect(about).toContain('follow channels like this one');
    expect(about).toContain('screen-time');
    expect(about).toContain('content protection');
  });

  it('says a channel with no posts is empty rather than rendering nothing', async () => {
    const slug = unique('empty');
    await seedChannel(slug, { name: 'Quiet Channel' });

    const response = await request(app).get(`/c/${slug}`);
    expect(response.status).toBe(200);
    expect(response.text).toContain('No posts yet');
  });

  it('says a channel HAS posts it cannot show, instead of saying it has none', async () => {
    // A deployment with no bucket serves text posts and signs no media at all,
    // which is a supported state. The two cases read differently because they ARE
    // different: "No posts yet" printed over a channel somebody posts to every day
    // reads as abandoned, and it is a claim about a channel this page cannot see.
    const slug = unique('nobucket');
    const channelId = await seedChannel(slug, { name: 'Has Posts' });
    const picture = await seedPost(channelId, 'A picture', 5);
    await seedImage(picture);

    const bare = buildApp({
      database: asQueryable(pglite),
      store: new UnconfiguredObjectStore(),
    });
    const response = await request(bare).get(`/c/${slug}`);

    expect(response.status).toBe(200);
    expect(response.text).toContain('can be shown here');
    expect(response.text).not.toContain('No posts yet');
    // And the page still offers the app, with the generic note rather than a
    // count of zero.
    expect(response.text).toContain('Open in ClearView');
    expect(response.text).not.toContain('newest 0');
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
