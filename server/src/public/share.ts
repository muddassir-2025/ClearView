import { Router, type Request, type Response } from 'express';
import { getPublicChannel, channelDeepLink } from '../channels/service.js';
import { listPublicChannelPosts } from './service.js';
import type { Queryable } from '../db.js';
import type { ObjectStore } from '../media/store.js';
import { env } from '../env.js';
import { ApiError } from '../http/errors.js';

/**
 * The page a shared channel link opens (§6).
 *
 * This exists because a share link has to be a real URL. `shareLink` used to be
 * a `clearview://` deep link, which is fine for the app that understands it and
 * invisible to everything else: messaging apps render nothing for an unknown
 * scheme, so a shared channel arrived as bare text with no name and no preview.
 * §6 asks for the thing WhatsApp has, and that is an `https://` link that opens
 * a page — which means somebody has to serve the page.
 *
 * ## What it is, and is not
 *
 * It is a READ-ONLY summary: the channel, its description and its most recent
 * posts. It is not a second client. There is no login, no follow button and no
 * posting — a visitor who wants any of that is sent into the app, which is what
 * the buttons at the top do. That is also why this endpoint is not behind the
 * reader API: it takes no token, and it must not, because the whole point is
 * that it works for someone who has never heard of ClearView.
 *
 * ## A preview, not an archive (§12)
 *
 * The page shows the newest [PREVIEW_POSTS] posts and nothing else — no paging,
 * no "load older", no complete history — and the limit is in the QUERY rather
 * than applied to a full result here (see [PREVIEW_POSTS]). It draws them as a
 * grid of mixed tiles rather than as a feed of cards, because a column of posts
 * that keeps going is a reader's experience and six squares are a profile: the
 * page answers "what is this channel" and then hands the visitor to the app for
 * everything else. The one thing it must never do is look like the place where
 * the channel lives.
 *
 * ## Why it looks like a product page and not like JSON
 *
 * The first version rendered the channel's name and a list of paragraphs, which
 * is what the data is and not what a person opening a shared link is looking at.
 * A shared link is most of what a channel's audience sees of it, so this page
 * carries the channel's own identity: its picture, its name and handle, what it
 * is about, and its recent updates rendered the way the app renders them —
 * including the inline formatting markers, which used to appear as literal
 * asterisks and backticks on the web and as bold and code in the app.
 *
 * ## Escaping, and the two pipelines
 *
 * EVERY value from the database is escaped, including the channel name. A
 * channel name is free text typed by a person, and this is the only place in the
 * product where such a value is interpolated into HTML rather than JSON — so it
 * is the only place where a name like `<script>` could become a script. The
 * escape is applied once, at the point of interpolation, rather than trusted to
 * each caller.
 *
 * The body's markers are turned into tags by [renderBody], which escapes as it
 * builds and never parses HTML — the same rule the app's own renderer follows,
 * for the same reason.
 *
 * ## Why the icons have their own route
 *
 * `GET /c/:slug/icon` answers with a redirect to a freshly signed URL. The page
 * could put the signed URL in `src` directly, and for a human who opens the link
 * now that would work — but a signed URL expires in minutes, and every consumer
 * that matters (a messaging app's crawler, a search engine, a reader who leaves
 * the tab open) fetches it later than that. A stable same-origin URL that is
 * signed at FETCH time is the only version that keeps working.
 */

/** Anything interpolated into the HTML goes through this. */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * A URL safe to put inside an HTML attribute.
 *
 * Two jobs: escape the attribute, and refuse anything that is not http(s). A
 * `javascript:` URL in an `href` is a script in a page that otherwise only
 * displays data, and every URL here — the icon, the deep link — is built by this
 * server, so the scheme check costs nothing and removes the question.
 */
function safeAttributeUrl(url: string): string | null {
  try {
    const { protocol } = new URL(url);
    return protocol === 'http:' || protocol === 'https:' ? escapeHtml(url) : null;
  } catch {
    return null;
  }
}

/**
 * When a post was published, as a person reads it.
 *
 * Rendered in UTC and SAID to be UTC, rather than converted: this page is served
 * to a browser this process knows nothing about, and guessing a zone from the
 * server's own clock is how a reader in another country sees the wrong day. The
 * machine-readable value goes in the `datetime` attribute beside it, so a
 * crawler, a screen reader and a calendar all get the exact instant.
 */
function readableDate(value: unknown): string {
  const date = value instanceof Date ? value : new Date(String(value ?? ''));
  if (Number.isNaN(date.getTime())) return '';

  const formatted = new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'UTC',
  }).format(date);

  return `${formatted} UTC`;
}

/** The same instant in the form a machine reads. */
function machineDate(value: unknown): string {
  const date = value instanceof Date ? value : new Date(String(value ?? ''));
  return Number.isNaN(date.getTime()) ? '' : date.toISOString();
}

/**
 * The same instant, short enough to sit on a picture.
 *
 * A tile is about 110px wide and already carries a play badge, so the day and
 * month is all that fits under a photograph; the full date and time ride in the
 * tile's tooltip and in nothing else. In UTC like every other date here, and for
 * the same reason: the server does not know the reader's clock.
 */
function shortDate(value: unknown): string {
  const date = value instanceof Date ? value : new Date(String(value ?? ''));
  if (Number.isNaN(date.getTime())) return '';

  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  }).format(date);
}

/**
 * One inline format, and the pattern that finds it in a body.
 *
 * These are the SAME four patterns the Android renderer uses (`GoodPostText.kt`),
 * copied rather than approximated: a post that reads as bold in the app and as
 * literal asterisks on the web is the same text seen two ways, which is exactly
 * what a shared link must not do. The guards are the conservative WhatsApp ones —
 * a delimiter cannot be part of a word, and the content cannot start or end with
 * whitespace, so `2*3*4` stays arithmetic.
 */
const FORMAT_PATTERNS: ReadonlyArray<{ tag: string; regex: RegExp }> = [
  // Longest delimiter first: at one position, ``` is monospace rather than two
  // stray characters around a monospace pair.
  { tag: 'code', regex: /```(?=\S)([\s\S]+?)(?<=\S)```/g },
  { tag: 'strong', regex: /(?<![A-Za-z0-9])\*(?=\S)([\s\S]+?)(?<=\S)\*(?![A-Za-z0-9])/g },
  { tag: 'em', regex: /(?<![A-Za-z0-9])_(?=\S)([\s\S]+?)(?<=\S)_(?![A-Za-z0-9])/g },
  { tag: 's', regex: /(?<![A-Za-z0-9])~(?=\S)([\s\S]+?)(?<=\S)~(?![\dA-Za-z])/g },
];

/**
 * A stored body as HTML (§17).
 *
 * Left to right, earliest match wins, one pass, no nesting — the Android parser's
 * algorithm, and deliberately the same one: the two renderers disagreeing about
 * what a body says would be a formatting feature that works in one place.
 *
 * Everything is escaped as it is appended, so this function cannot emit a tag
 * that did not come from this file, whatever the body contains.
 */
function renderBody(raw: string): string {
  let out = '';
  let index = 0;

  while (index < raw.length) {
    let best: { tag: string; match: RegExpExecArray } | null = null;

    for (const { tag, regex } of FORMAT_PATTERNS) {
      regex.lastIndex = index;
      const match = regex.exec(raw);
      if (match === null) continue;
      // Earliest match wins; a tie goes to the longer delimiter.
      if (
        best === null ||
        match.index < best.match.index ||
        (match.index === best.match.index && match[0].length > best.match[0].length)
      ) {
        best = { tag, match };
      }
    }

    if (best === null) {
      out += escapeHtml(raw.slice(index));
      break;
    }

    out += escapeHtml(raw.slice(index, best.match.index));
    out += `<${best.tag}>${escapeHtml(best.match[1] ?? '')}</${best.tag}>`;
    index = best.match.index + best.match[0].length;
  }

  // Paragraphs survive as paragraphs: the app draws the newlines it is given, and
  // a body of three thoughts run together as one is a different post.
  return out.replace(/\n/g, '<br>');
}

/** The host a link points at, for a link card's second line. */
function domainOf(url: string): string {
  try {
    return new URL(url).host.replace(/^www\./, '');
  } catch {
    return '';
  }
}

/** Where a visitor without the app goes: the store listing for this build (§6). */
function playStoreUrl(): string {
  // `PLAY_STORE_URL` first, because a closed testing track or a listing under
  // another address is a real thing an operator has and this file cannot know.
  // The derived URL is the package name's own listing, which is what a published
  // app's store link IS — so an unset variable still points somewhere real
  // instead of at a placeholder.
  return env.PLAY_STORE_URL ?? `https://play.google.com/store/apps/details?id=${env.ANDROID_PACKAGE_NAME}`;
}

/**
 * How many posts a shared link shows: the newest three of words, three of media.
 *
 * The page is a PREVIEW, not the channel (§12): a visitor follows a link to see
 * what the channel is, and the app is where its history lives. Six posts is about
 * one screen, and the split is what keeps both halves of the page honest — a
 * channel that posts three photos in a row should still show that it writes, and
 * a channel of nothing but words should not end in an empty grid.
 *
 * These two are counts this file APPLIES, not numbers it hides after loading a
 * hundred: the public posts endpoint takes a page size, so the media beyond what
 * is drawn here is never signed, never transferred and never billed.
 */
const PREVIEW_ROWS = 3;
const PREVIEW_TILES = 3;

/**
 * How many posts the query may look through to fill those six slots.
 *
 * More than six, for a reason: the newest six posts of a photo channel are six
 * photos, and asking the database for only six would leave the words section
 * empty on a channel that has plenty of words. 24 is a few screens of a feed —
 * one indexed read, still bounded, and the point past which a channel has buried
 * its own text deep enough that a preview of it is honest without it.
 */
const PREVIEW_SCAN = '24';

/**
 * The most characters of a post's text a tile shows before it stops.
 *
 * A grid tile is roughly 110px wide on a phone: three lines is already all that
 * fits, and a 400-character post rendered into one would either overflow the
 * square or shrink to unreadable. The rest is in the app, which the tile says.
 */
const PREVIEW_CHARS = 140;

/**
 * The same limit for a full-width row, which has the width to hold more — about
 * four lines at a reading size, which is where a preview stops being one.
 */
const ROW_CHARS = 260;

/**
 * A post's text cut to what a preview can show, and whether it was cut.
 *
 * Cut at a word boundary rather than mid-word, then stripped of trailing
 * punctuation and of a trailing formatting marker: a cut that lands inside
 * `*bold*` leaves the opening asterisk in the text, and a lone asterisk in a
 * preview reads as a typo rather than as formatting.
 */
function previewOf(raw: string, limit = PREVIEW_CHARS): { text: string; truncated: boolean } {
  const trimmed = raw.trim();
  if (trimmed.length <= limit) return { text: trimmed, truncated: false };

  const cut = trimmed.slice(0, limit);
  const boundary = Math.max(cut.lastIndexOf(' '), cut.lastIndexOf('\n'));
  const head = boundary > limit / 2 ? cut.slice(0, boundary) : cut;
  return { text: head.replace(/[\s,;:.!?-]+$/, '').replace(/[*_~`]+$/, ''), truncated: true };
}

/** One attachment, rendered as itself. */
function renderMedia(media: {
  readonly kind: string;
  readonly url: string | null;
  readonly width: number | null;
  readonly height: number | null;
}): string {
  const url = media.url === null ? null : safeAttributeUrl(media.url);
  if (url === null) return '';

  if (media.kind === 'video') {
    return `<video class="tile-media" preload="metadata" muted playsinline src="${url}#t=0.1"></video>`;
  }

  return `<img class="tile-media" loading="lazy" alt="" src="${url}">`;
}

/**
 * One post, as a tile of the preview grid (§5–§9).
 *
 * ## The three kinds, and why the choice is made here
 *
 * A GoodPost post is words, media or a link, and the three do not want the same
 * tile: a photo shown as a square of text is not a photo, and text shown as a
 * square of cover-cropped nothing is not text. Media wins when a post carries
 * both, because the picture is what a grid is FOR — the caption is one tap away
 * in the app, and printing it over the picture would be the caption covering the
 * thing it describes.
 *
 * ## Media only
 *
 * Words do not come through here: a text or link post is a full-width ROW
 * ([postRow]), because a paragraph in a 110px square is either three words or a
 * font nobody can read. The two layouts are two sections of the page, so this
 * function only ever draws a square.
 *
 * ## What a tap does
 *
 * A media tile opens the media — the signed URL, full size, in the browser. The
 * tile crops it to a square, so a tap that did nothing would leave a visitor no
 * way to see the whole of it, and this is the one kind of tile with somewhere
 * else to go on the web. It is a real `<a>` element; nothing depends on the
 * script.
 *
 * ## Why the video is asked for a tenth of a second
 *
 * A tile cannot autoplay — six clips starting at once is a page nobody asked for
 * and a bill nobody wants — and a `<video>` that has not played paints an empty
 * black box. `preload="metadata"` plus the media fragment gets the first frame
 * on screen, so the tile shows the clip it stands for. `muted` and `playsinline`
 * are what make some browsers willing to paint it at all.
 */
function postTile(
  post: {
    readonly type: string;
    readonly body: string | null;
    readonly linkUrl: string | null;
    readonly linkTitle: string | null;
    readonly media: readonly {
      readonly id: string;
      readonly kind: string;
      readonly url: string | null;
      readonly width: number | null;
      readonly height: number | null;
    }[];
    readonly createdAt: string;
  }
): string {
  const when = escapeHtml(readableDate(post.createdAt));
  // One visible date, and it is the short one: the day, in the corner of the
  // square, over the same scrim the play badge uses. The full instant rides in
  // the element's tooltip and in `datetime`, because "18 Sept 2026, 13:47 UTC"
  // across a 110px tile is four lines of date and no picture.
  const stamp = `<time class="tile-date" datetime="${escapeHtml(
    machineDate(post.createdAt)
  )}">${escapeHtml(shortDate(post.createdAt))}</time>`;

  const first = post.media[0];
  const href = first === undefined || first.url === null ? null : safeAttributeUrl(first.url);
  if (first === undefined || href === null) return '';

  const clip = first.kind === 'video';
  // `data-lightbox` is what the page's script turns into "open the overlay at this
  // tile" (§6). The href stays a real URL, so a visitor with no JavaScript still
  // gets the full-size picture — the overlay is an improvement on that, not the
  // only way to see it.
  return `<a class="tile" href="${href}" data-lightbox data-kind="${
    clip ? 'video' : 'image'
  }" rel="noopener" title="${when}">
  ${renderMedia(first)}${
    clip ? '\n  <span class="play" aria-hidden="true">\u25b6</span>' : ''
  }
  ${stamp}
  <span class="sr">${clip ? 'Video' : 'Image'}</span>
</a>`;
}

/**
 * A post with no media, as a full-width row (§7, §8).
 *
 * One row per line, the whole width of the page, because words need the width and
 * a grid has none to spare: the same post as a square tile was three lines of
 * type in a 110px column. It also gives the text room to be worth reading — a
 * longer preview at a real reading size, rather than a caption.
 *
 * A link post is the same row with a headline and a domain where the body would
 * be, which is how the app draws one.
 *
 * Nothing here links to the web: a row is a preview of something that lives in
 * the app, so tapping it opens the channel there — the same promise every button
 * on the page makes.
 *
 * ## One date, above the words
 *
 * Every row carries its date as its first line: an update without one is a post
 * of unknown age, and the corner badge on a tile is the other half of the same
 * rule. It is dim and small so it labels the card rather than competing with it.
 *
 * ## Why a word post no longer ends in "Open"
 *
 * The card itself is the link, and a text row that ended in "Open \u2192" (or in
 * "Read more \u2192" under a cut that the app is where you finish) said the same
 * thing twice — once in the sentence, once in the thing you are already pressing.
 * A link row keeps its single foot, because a headline and a domain could
 * otherwise read as the whole of a post that is really a link to one.
 */
function postRow(
  post: {
    readonly body: string | null;
    readonly linkUrl: string | null;
    readonly linkTitle: string | null;
    readonly createdAt: string;
  },
  deepLink: string
): string {
  const when = escapeHtml(readableDate(post.createdAt));
  const stamp = `<time class="post-row-date" datetime="${escapeHtml(
    machineDate(post.createdAt)
  )}">${when}</time>`;
  const open = `href="${escapeHtml(deepLink)}" rel="noopener" title="${when}"`;

  // ── A link ──
  if (post.linkUrl !== null) {
    const title = post.linkTitle?.trim();
    const heading = previewOf(title && title !== '' ? title : domainOf(post.linkUrl), 96);
    return `<a class="post-row" ${open}>
  ${stamp}
  <span class="post-link-title">${escapeHtml(heading.text)}${heading.truncated ? '\u2026' : ''}</span>
  <span class="post-link-domain">${escapeHtml(domainOf(post.linkUrl))}</span>
  <span class="post-row-foot">Open \u2192</span>
</a>`;
  }

  // ── Words ──
  const cut = post.body === null || post.body.trim() === '' ? null : previewOf(post.body, ROW_CHARS);
  const shown = cut === null ? 'This update has no text.' : renderBody(cut.text);

  return `<a class="post-row" ${open}>
  ${stamp}
  <span class="post-row-body">${shown}</span>
</a>`;
}

export function buildShareRouter(database: Queryable, store: ObjectStore): Router {
  const router = Router();

  /**
   * The page's one script (§6).
   *
   * A file of its own rather than an inline `<script>` or an `onclick`
   * attribute, because the CSP this service sends (`script-src 'self'`,
   * `script-src-attr 'none'`) blocks both — and a security header is not worth
   * weakening for one button.
   *
   * What it does is the standard "open the app if it is there" move, which no
   * amount of HTML can do: it lets the scheme handler try, and if the page is
   * still in front a second later then nothing claimed the link, so the visitor
   * is sent to the store instead. A visitor with JavaScript turned off still has
   * both buttons and both work — this only chooses between them.
   */
  router.get('/app.js', (_req: Request, res: Response) => {
    res.type('application/javascript').setHeader('Cache-Control', 'public, max-age=3600');
    res.send(APP_JS);
  });

  /**
   * ClearView's mark, for the tab and for anything that asks for it by URL (§14).
   *
   * Its own route rather than a data: URI so the favicon is cacheable and can be
   * referenced from a head that has no markup of its own to inline it into. The
   * page's header draws the same mark inline — a logo that pops in a moment after
   * the brand line it belongs to is worse than no logo.
   *
   * Registered before `/:slug` for the same reason `/app.js` is: two path
   * segments here, one in the channel route, so a channel called "logo.svg" is
   * still reachable.
   */
  router.get('/logo.svg', (_req: Request, res: Response) => {
    res.type('image/svg+xml').setHeader('Cache-Control', 'public, max-age=604800');
    res.send(LOGO_SVG);
  });

  /**
   * A channel's picture, as a redirect to a URL signed NOW (§6).
   *
   * Registered before `/:slug` and with its own path shape, so a channel actually
   * named "icon" is still reachable at `/:slug` — Express matches on the number
   * of segments, and this one has two.
   *
   * A missing channel and a channel with no image answer the same way — a 302 to
   * nothing would leave a browser showing a broken image, so both end in a small
   * transparent SVG that is generated here rather than fetched. The page's own
   * initials circle is drawn behind it, which is why "no picture" still looks
   * deliberate.
   */
  router.get('/:slug/icon', async (req: Request, res: Response) => {
    const slug = pathSlug(req);

    try {
      const channel = await getPublicChannel(database, store, slug);
      const icon = channel.iconUrl === null ? null : safeAttributeUrl(channel.iconUrl);
      if (icon !== null) {
        // Cache for a MINUTE and not for the signature's lifetime: the picture
        // behind this URL can be replaced at any moment by the channel's owner,
        // and a page that keeps serving yesterday's avatar after a change is the
        // bug this whole route exists to avoid.
        res.setHeader('Cache-Control', 'public, max-age=60');
        res.redirect(302, channel.iconUrl as string);
        return;
      }
    } catch {
      // Falls through to the blank image: an icon that cannot be resolved is not
      // an error a browser can do anything about.
    }

    res.status(200).type('image/svg+xml').setHeader('Cache-Control', 'public, max-age=60');
    res.send(BLANK_ICON);
  });

  /**
   * One channel's page.
   *
   * Errors are rendered as a page rather than as the JSON error every other
   * route returns: this endpoint's caller is a browser, and a JSON body would
   * show a recipient of the link a wall of braces. The status code is still
   * honest — a channel that does not exist is a 404 — because link scrapers and
   * crawlers read the code even when a human only reads the text.
   */
  router.get('/:slug', async (req: Request, res: Response) => {
    const slug = pathSlug(req);

    let channel;
    let posts;
    try {
      channel = await getPublicChannel(database, store, slug);
      // A short page: the newest few posts are what a visitor needs to judge
      // the channel, and the app is where the rest of them live. Older posts are
      // not paginated here on purpose — a channel is not an archive (§14), and a
      // crawler walking an unbounded list is a load nobody asked for.
      const page = await listPublicChannelPosts(database, store, slug, { limit: PREVIEW_SCAN });
      posts = page.items;
    } catch (err) {
      const type = err instanceof ApiError ? err.type : 'internal_error';
      const status = err instanceof ApiError && err.status < 500 ? err.status : 500;
      res.status(status).type('html').send(notFoundPage(slug, type));
      return;
    }

    res
      .status(200)
      .type('html')
      // A shared link's content is the channel's, and it changes; a minute of
      // caching keeps a burst of previews from hammering the database without
      // letting a reader see an update that has already been replaced.
      .setHeader('Cache-Control', 'public, max-age=60')
      .send(channelPage(channel, posts));
  });

  return router;
}

/** The slug from the path, collapsed out of the array Express allows. */
function pathSlug(req: Request): string {
  const raw = req.params['slug'];
  const slug = Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '');
  return slug;
}

/** A one-pixel transparent SVG, for a picture that is not there. */
const BLANK_ICON =
  '<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1" viewBox="0 0 1 1"></svg>';

/**
 * ClearView's mark: the split heart (§14).
 *
 * The SAME geometry as the app's launcher icon — `ic_launcher_foreground.xml`, a
 * black heart with its right half painted deep red — carried over as path data
 * rather than as a picture, so the line between the halves stays sharp at any
 * size, the file is a kilobyte, and there is nothing extra to fetch or cache.
 *
 * The viewBox crops to the heart: the source draws it at x=32..76, y=33.8..74.2
 * on the icon's 108-unit canvas, and a favicon that is 44% empty canvas is a
 * favicon that looks too small in a tab.
 */
const LOGO_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="32 33.8 44 40.4" role="img" aria-label="ClearView"><path fill="#000000" d="M54,74.185 l-3.19,-2.904 C39.48,61.007 32,54.231 32,45.915 C32,39.139 37.324,33.815 44.1,33.815 c3.828,0 7.502,1.782 9.9,4.598 C56.398,35.597 60.072,33.815 63.9,33.815 C70.676,33.815 76,39.139 76,45.915 c0,8.316 -7.48,15.092 -18.81,25.388 L54,74.185z"/><path fill="#C62828" d="M54,38.413 C56.398,35.597 60.072,33.815 63.9,33.815 C70.676,33.815 76,39.139 76,45.915 c0,8.316 -7.48,15.092 -18.81,25.388 L54,74.185z"/></svg>`;

/**
 * The script served at `/c/app.js` (§6).
 *
 * Written as ES5 on purpose — an older WebView inside a messaging app is exactly
 * the client this page is opened in, and a syntax error there is a dead button.
 * It is also deliberately tiny: one listener, one timer, nothing to fail.
 */
const APP_JS = `(function () {
  // ── The app-or-store links ──
  //
  // Every link that promises the app, not just the one at the top: the page has
  // a second pair at the bottom and every row carries the same promise, and a
  // script that wired up only the first would leave the rest dead for a visitor
  // with a browser and no app.
  var links = document.querySelectorAll('a[data-store]');

  var wire = function (link) {
    link.addEventListener('click', function (event) {
      var store = link.getAttribute('data-store');
      if (!store) return;

      // Held, not navigated to yet: if the app answers, this page goes to the
      // background and the timer is dropped before it fires.
      var timer = setTimeout(function () {
        window.location.href = store;
      }, 1200);

      var settled = function () {
        if (document.hidden) clearTimeout(timer);
      };
      document.addEventListener('visibilitychange', settled);
      window.addEventListener('pagehide', settled);

      event.preventDefault();
      window.location.href = link.getAttribute('href');
    });
  };

  for (var i = 0; i < links.length; i++) wire(links[i]);

  // ── The lightbox (§6) ──
  //
  // A visitor sent a link with six pictures should be able to look at all six
  // without six back-and-forths, and without leaving the channel. So a tap on a
  // media tile opens the media HERE, over the page, with the other tiles a swipe
  // or an arrow away.
  //
  // Built from the tiles that are already in the document: nothing is fetched
  // until it is shown (the browser serves the ones the grid already loaded from
  // its cache), and the overlay holds one element at a time, so the cost of
  // paging through six is one image.
  var tiles = document.querySelectorAll('[data-lightbox]');
  var box = document.getElementById('lightbox');
  if (!tiles.length || !box) return;

  var stage = document.getElementById('lb-stage');
  var counter = document.getElementById('lb-count');
  var closeButton = document.getElementById('lb-close');
  var prevButton = document.getElementById('lb-prev');
  var nextButton = document.getElementById('lb-next');
  var at = 0;

  var show = function (index) {
    if (index < 0) index = tiles.length - 1;
    if (index >= tiles.length) index = 0;
    at = index;

    var tile = tiles[index];
    var clip = tile.getAttribute('data-kind') === 'video';
    // The tile's own URL. The fragment is dropped for a clip: the grid asked for
    // a frame at 0.1s so the tile had something to paint, and starting the
    // visitor there in the player is pointless.
    var src = String(tile.getAttribute('href')).replace('#t=0.1', '');

    var el = document.createElement(clip ? 'video' : 'img');
    el.className = 'lb-media';
    el.setAttribute('src', src);
    if (clip) {
      el.setAttribute('controls', '');
      el.setAttribute('autoplay', '');
      el.setAttribute('playsinline', '');
    } else {
      el.setAttribute('alt', '');
    }

    // Replacing the element, rather than reusing it: a clip that is removed from
    // the document stops, which is the whole of the "pause the last one" logic.
    stage.innerHTML = '';
    stage.appendChild(el);

    counter.textContent = index + 1 + ' / ' + tiles.length;
    var many = tiles.length > 1;
    prevButton.hidden = !many;
    nextButton.hidden = !many;
  };

  var close = function () {
    box.hidden = true;
    stage.innerHTML = '';
    document.documentElement.style.overflow = '';
  };

  var open = function (index) {
    if (document.documentElement.style.overflow !== 'hidden') {
      box.hidden = false;
      // The page behind must not scroll while the overlay is up, or a swipe meant
      // to move between pictures moves the page.
      document.documentElement.style.overflow = 'hidden';
    }
    show(index);
    closeButton.focus();
  };

  for (var t = 0; t < tiles.length; t++) {
    (function (tile, index) {
      tile.addEventListener('click', function (event) {
        event.preventDefault();
        open(index);
      });
    })(tiles[t], t);
  }

  closeButton.addEventListener('click', close);
  prevButton.addEventListener('click', function () {
    show(at - 1);
  });
  nextButton.addEventListener('click', function () {
    show(at + 1);
  });

  // Tapping the dark surround closes, tapping the picture does not.
  box.addEventListener('click', function (event) {
    if (event.target === box) close();
  });

  document.addEventListener('keydown', function (event) {
    if (box.hidden) return;
    if (event.key === 'Escape') close();
    if (event.key === 'ArrowLeft') show(at - 1);
    if (event.key === 'ArrowRight') show(at + 1);
  });

  // Swipe, because this page is opened on a phone from a chat. 40px is the point
  // where a deliberate swipe stops looking like a scroll that went nowhere.
  var startX = 0;
  stage.addEventListener(
    'touchstart',
    function (event) {
      startX = event.changedTouches[0].clientX;
    },
    false
  );
  stage.addEventListener(
    'touchend',
    function (event) {
      var moved = event.changedTouches[0].clientX - startX;
      if (moved > 40) show(at - 1);
      if (moved < -40) show(at + 1);
    },
    false
  );
})();
`;

/**
 * What ClearView is, for the two pages that have to introduce it (§5).
 *
 * One paragraph, and deliberately about the whole app rather than about channels:
 * a visitor arriving from a shared link has met Good Post and nothing else, so
 * this is the only place the rest of it is named. The four things it lists are
 * the app's four tabs in the order they appear — the Quran, media, channels, and
 * the tools behind "More" — described in the general terms a stranger can picture
 * rather than as feature names they cannot.
 *
 * Shared by the channel page and the not-found page so the two cannot drift into
 * describing two different products, and plain text so it is escaped by whoever
 * interpolates it.
 */
const ABOUT_CLEARVIEW =
  'A calm place for the time you spend on a phone. Read and search the Quran, ' +
  'keep your own media without an endless feed, follow channels like this one, ' +
  'and set the limits around all of it — to-dos, a zikr counter, screen-time ' +
  'limits and content protection, in one app. Reading never needs an account, ' +
  'and nothing here is built to keep you scrolling.';

/** The channel page, with the preview card a messaging app will build. */
function channelPage(
  channel: Awaited<ReturnType<typeof getPublicChannel>>,
  posts: Awaited<ReturnType<typeof listPublicChannelPosts>>['items']
): string {
  const name = escapeHtml(channel.name);
  const slug = escapeHtml(channel.slug);
  const description = channel.description ?? `Updates from ${channel.name}`;
  const handle = `@${slug}`;
  // Kept unescaped for the tile builder, which escapes what it interpolates;
  // escaped once here for the buttons.
  const rawDeepLink = channelDeepLink(channel.slug);
  const deepLink = escapeHtml(rawDeepLink);
  const shareUrl = escapeHtml(new URL(`/c/${channel.slug}`, env.PUBLIC_BASE_URL).toString());
  // Same-origin and signed at fetch time, so a crawler that reads this page
  // tomorrow still gets a picture (§6). The page and the deep link both use it.
  const iconPath = `/c/${encodeURIComponent(channel.slug)}/icon`;
  const store = escapeHtml(playStoreUrl());
  const initial = escapeHtml(channel.name.trim().slice(0, 1).toUpperCase() || 'C');

  const facts = [
    channel.categoryLabel === null ? null : escapeHtml(channel.categoryLabel),
    `${channel.followerCount} ${channel.followerCount === 1 ? 'follower' : 'followers'}`,
  ]
    .filter((item): item is string => item !== null)
    .join('<span class="sep">·</span>');

  // Two layouts, in two sections (§7, §8): words as full-width rows on their own
  // lines, then the pictures and clips as squares. Grouped rather than interleaved
  // because a grid with a paragraph in the middle of it is neither a grid nor a
  // readable page — and because the rows have to come first for the squares to
  // form whole rows of their own at the bottom.
  //
  // Three of each ([PREVIEW_ROWS], [PREVIEW_TILES]) and no more. The two sections
  // are counted separately because they are shown separately: taking the newest
  // six posts and splitting them would give a photo channel no words at all and a
  // written channel an empty grid.
  //
  // The order WITHIN each section is still newest first, which is the only order
  // a preview can honestly claim.
  // What a post IS decides which section it belongs in, and a post's media rows
  // decide that — not whether the signing worked. Classifying on the URL would
  // send a picture whose link could not be signed into the rows, where it would
  // print "This update has no text" over a post that is entirely a photograph;
  // this way it simply does not appear.
  const withMedia = posts.filter((post) => post.media.length > 0);
  const withoutMedia = posts.filter((post) => post.media.length === 0);

  const rowPosts = withoutMedia.slice(0, PREVIEW_ROWS);
  const tilePosts = withMedia
    .filter((post) => post.media[0]?.url !== null && post.media[0]?.url !== undefined)
    .slice(0, PREVIEW_TILES);
  const rows = rowPosts.map((post) => postRow(post, rawDeepLink)).join('\n');
  const tiles = tilePosts.map(postTile).join('\n');
  const shown = rowPosts.length + tilePosts.length;

  // The bottom section says what this page is (§11, §12). Naming the count is
  // the honest version of "this is a preview": a visitor can see that the page
  // is complete in itself and that the channel is bigger than what they were
  // sent, without the page ever claiming how much bigger. With no posts at all
  // it says that instead, because "the latest 0 updates" is not a sentence.
  const previewHeading = posts.length === 0 ? 'This channel is on ClearView' : 'See more from this channel';
  const previewNote =
    posts.length === 0
      ? 'Follow it in the app to see what gets posted.'
      : shown === 0
        ? 'See all posts in the app — the whole channel, and everything it posts next, is in ClearView. Reading it never needs an account.'
        : `See all posts in the app. This link is a preview of the newest ${shown} ${shown === 1 ? 'update' : 'updates'} — the whole channel, and everything it posts next, is in ClearView. Reading it never needs an account.`;

  // What stands in for the sections when neither has anything in it. Two states,
  // not one: "No posts yet" printed over a channel that HAS posted but whose
  // pictures could not be signed on this deployment would be a lie about somebody
  // else's channel, and one a visitor would take as the channel being abandoned.
  const body =
    shown > 0
      ? '<h2 class="section-heading">Latest posts</h2>'
      : posts.length === 0
        ? '<p class="dim empty">No posts yet. This channel has not posted anything — check back later.</p>'
        : '<p class="dim empty">None of this channel\u2019s recent posts can be shown here. Open it in ClearView to read them.</p>';

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${name} (${handle}) · ClearView</title>
<meta name="description" content="${escapeHtml(description)}">
<link rel="canonical" href="${shareUrl}">
<meta name="theme-color" content="#0b141a">
<meta property="og:type" content="website">
<meta property="og:site_name" content="ClearView">
<meta property="og:title" content="${name} · ClearView">
<meta property="og:description" content="${escapeHtml(description)}">
<meta property="og:url" content="${shareUrl}">
<meta property="og:image" content="${escapeHtml(new URL(iconPath, env.PUBLIC_BASE_URL).toString())}">
<meta name="twitter:card" content="summary">
<link rel="icon" type="image/svg+xml" href="/c/logo.svg">
<style>${STYLES}</style>
</head>
<body>
<main>
  <p class="brand"><span class="brand-mark">${LOGO_SVG}</span><span>ClearView</span><span class="brand-sep">\u00b7</span><span class="brand-sub">Good Post</span></p>

  <header class="profile">
    <div class="avatar">
      <span class="avatar-initial">${initial}</span>
      <img src="${iconPath}" alt="">
    </div>
    <div class="identity">
      <h1>${name}</h1>
      <p class="description">${escapeHtml(description)}</p>
      <p class="facts">${facts}</p>
    </div>
  </header>

  ${body}
  ${rows === '' ? '' : `<div class="rows">\n${rows}\n  </div>`}
  ${
    // The second heading appears only when both sections do: with one of them
    // there is nothing to tell apart, and "Media" over an empty page is a label
    // for a section that is not there.
    tiles === '' || rows === '' ? '' : '<h3 class="section-heading">Media</h3>'
  }
  ${tiles === '' ? '' : `<div class="grid">\n${tiles}\n  </div>`}

  <section class="more">
    <h2>${previewHeading}</h2>
    <p class="dim">${escapeHtml(previewNote)}</p>
    <div class="cta">
      <a class="button primary" href="${deepLink}" data-store="${store}">Open in ClearView</a>
      <a class="button" href="${store}" rel="noopener">Get ClearView</a>
    </div>
  </section>

  <section class="about">
    <h2>ClearView</h2>
    <p>${ABOUT_CLEARVIEW}</p>
    <p class="about-cta"><a href="${store}" rel="noopener">Explore ClearView on Google Play \u2192</a></p>
  </section>

  <footer>
    <span>Shared from ClearView</span>
    <span class="sep">\u00b7</span>
    <a href="${shareUrl}">${name}</a>
  </footer>
</main>
${
  // The overlay itself, empty (§6). Rendered with the page and left hidden, so
  // opening it is a class change rather than a second request, and so a visitor
  // whose JavaScript never ran sees nothing at all instead of a dead panel.
  tiles === ''
    ? ''
    : `<div class="lightbox" id="lightbox" hidden role="dialog" aria-modal="true" aria-label="Media from this channel">
  <div class="lb-stage" id="lb-stage"></div>
  <button type="button" class="lb-btn lb-close" id="lb-close" aria-label="Close">\u00d7</button>
  <button type="button" class="lb-btn lb-prev" id="lb-prev" aria-label="Previous">\u2039</button>
  <button type="button" class="lb-btn lb-next" id="lb-next" aria-label="Next">\u203a</button>
  <div class="lb-bar"><span id="lb-count"></span><a href="${deepLink}" data-store="${store}">Open in ClearView</a></div>
</div>`
}
<script src="/c/app.js" defer></script>
</body>
</html>
`;
}

/** A page for a channel that is gone, or was never there. */
function notFoundPage(slug: string, reason: string): string {
  const message =
    reason === 'channel_not_found'
      ? 'That channel is not available. It may have been removed, or the link may be incomplete.'
      : 'Something went wrong loading that channel.';

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Channel unavailable · ClearView</title>
<meta name="theme-color" content="#0b141a">
<style>${STYLES}</style>
</head>
<body>
<main class="narrow">
  <div class="avatar"><span class="avatar-initial">C</span></div>
  <h1 class="not-found">Channel unavailable</h1>
  <p class="dim">${escapeHtml(message)}</p>
  ${slug === '' ? '' : `<p class="dim">Link: <code>/c/${escapeHtml(slug)}</code></p>`}
  <section class="about">
    <h2>ClearView</h2>
    <p>${ABOUT_CLEARVIEW}</p>
    <p class="about-cta"><a href="${escapeHtml(playStoreUrl())}" rel="noopener">Explore ClearView on Google Play →</a></p>
  </section>
</main>
</body>
</html>
`;
}

/**
 * The page's stylesheet.
 *
 * Dark first, because the app is dark and this is the app's page (§6): a visitor
 * arriving from a message should recognise the channel they were sent. A light
 * palette follows the system preference for the readers who live in light mode,
 * which is a colour scheme and not a second design.
 *
 * No external font and no framework: this page is served by the same process that
 * answers the API, and one request that renders text is the whole job.
 */
const STYLES = `
:root {
  color-scheme: dark;
  --canvas: #0b141a;
  --surface: #111b21;
  --raised: #202c33;
  --divider: #1f2c34;
  --text: #e9edef;
  --dim: #8696a0;
  --accent: #00a884;
  --on-accent: #0b141a;
  --radius: 14px;
}
@media (prefers-color-scheme: light) {
  :root {
    color-scheme: light;
    --canvas: #f7f8fa;
    --surface: #ffffff;
    --raised: #f0f2f5;
    --divider: #e4e6eb;
    --text: #111b21;
    --dim: #667781;
    --accent: #008069;
    --on-accent: #ffffff;
  }
}
* { box-sizing: border-box; }
body {
  margin: 0;
  background: var(--canvas);
  color: var(--text);
  font: 16px/1.55 -apple-system, BlinkMacSystemFont, Roboto, "Segoe UI", Helvetica, Arial, sans-serif;
  -webkit-text-size-adjust: 100%;
}
main { max-width: 680px; margin: 0 auto; padding: 26px 18px 56px; }
main.narrow { max-width: 520px; padding-top: 56px; }

h1, h2 { line-height: 1.25; }
h1 { font-size: 21px; margin: 0 0 2px; }
h2 { font-size: 16px; margin: 0 0 8px; }

/* The masthead: whose page this is, in the app's own product voice, under the
   app's own mark (§14). */
.brand {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 18px;
  color: var(--dim);
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.09em;
  text-transform: uppercase;
}

/*
 * The mark gets the app icon's own white tile, and the tile is not decoration.
 *
 * ClearView's heart is BLACK with its right half in deep red — the launcher icon
 * paints it on the solid white background of ic_launcher_background.xml, and that
 * pairing is what makes both halves visible at once. Dropped bare onto this
 * page's near-black canvas the black
 * half disappears and the logo reads as half a heart, which is a worse outcome
 * than no logo. On white it is the icon, at any size, in either theme. */
.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 25px;
  height: 25px;
  border-radius: 8px;
  background: #ffffff;
}
.brand svg { width: 14px; height: 13px; display: block; }
.brand-sep { color: var(--divider); }
.brand-sub { color: var(--dim); opacity: 0.75; }

/*
 * The channel, as a profile rather than a byline (§3).
 *
 * Avatar and identity side by side on anything with room, and stacked and
 * centred on a phone, which is what the page is mostly read on: a 76px circle
 * beside two lines of text squeezes the name into a column on a 360px screen,
 * and the name is the first thing a visitor is looking for.
 */
.profile {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px;
  background: var(--surface);
  border: 1px solid var(--divider);
  border-radius: 18px;
}
.avatar {
  position: relative;
  width: 76px; height: 76px; flex: 0 0 auto;
  border-radius: 50%;
  background: var(--raised);
  overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.avatar img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.avatar-initial { color: var(--accent); font-size: 30px; font-weight: 700; }
/*
 * The identity is the name, what the channel is about, and its two numbers —
 * and NOT the handle.
 *
 * The @slug line used to sit under the name and it was the name again: the slug
 * is derived from it, so a channel called "idk" read "idk / @idk" with the one
 * thing a visitor came to find out nowhere in sight. The description is what
 * belongs there (§3); the slug is still in the URL, the share link and the
 * document title, which is where an identifier is useful and a person is not
 * reading it.
 */
.identity { min-width: 0; }
.description { margin: 6px 0 0; color: var(--text); font-size: 15px; white-space: pre-wrap; overflow-wrap: anywhere; }
.facts { margin: 7px 0 0; color: var(--dim); font-size: 13px; }
.sep { margin: 0 6px; color: var(--divider); }
@media (prefers-color-scheme: light) { .sep { color: var(--dim); } }

/*
 * Stacked and centred on a narrow screen. Centring the avatar is the one place
 * this page is laid out like a profile rather than a document, and the header is
 * where a shared link is judged — the picture is most of what a visitor will
 * remember of the channel.
 */
@media (max-width: 420px) {
  .profile { flex-direction: column; text-align: center; gap: 12px; padding: 22px 18px; }
  .identity { width: 100%; }
}

.description:empty { display: none; }

/*
 * Two buttons, side by side while they fit and stacked when they do not (§4).
 * The primary takes the room it needs rather than half the row: on a phone it is
 * the action, and the store link beside it is the consolation.
 */
.cta { display: flex; flex-wrap: wrap; gap: 10px; margin: 18px 0 0; }
.button {
  display: inline-block;
  padding: 12px 20px;
  border-radius: 24px;
  background: var(--raised);
  color: var(--text);
  font-size: 14.5px;
  font-weight: 600;
  text-decoration: none;
  border: 1px solid transparent;
}
.button.primary { background: var(--accent); color: var(--on-accent); }
.button:active { opacity: 0.85; }
@media (max-width: 420px) {
  .cta { justify-content: center; }
  .button { flex: 1 1 auto; text-align: center; }
}

/*
 * Words, as rows (§7): one post per line, the full width of the page.
 *
 * This is what a text post gets instead of a square: the same post in a 110px
 * column was three lines of type, and a paragraph is the one thing a grid cannot
 * hold. A row is quiet on purpose — a surface, a hairline and space, with the
 * words at a reading size rather than a caption's.
 */
.rows { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; }
.post-row {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 15px 16px;
  background: var(--surface);
  border: 1px solid var(--divider);
  border-radius: 14px;
  color: var(--text);
  text-decoration: none;
}
.post-row:active { opacity: 0.9; }
.post-row-body { font-size: 15px; line-height: 1.55; overflow-wrap: anywhere; white-space: pre-wrap; }
.post-row-body code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13.5px;
  background: var(--raised);
  padding: 1px 5px;
  border-radius: 6px;
}
.post-link-title { font-size: 15.5px; font-weight: 600; overflow-wrap: anywhere; }
.post-link-domain { color: var(--dim); font-size: 12.5px; }
.post-row-foot { color: var(--accent); font-size: 12.5px; font-weight: 600; }
/*
 * The date on a row: the first line of the card, dim and small so it labels the
 * post instead of competing with the words. Tabular figures because dates in a
 * column of rows should line up — proportional ones make the column look ragged.
 */
.post-row-date { color: var(--dim); font-size: 12px; font-variant-numeric: tabular-nums; }

/*
 * The media grid (§5, §6, §8) — squares, under the rows.
 *
 * auto-fill with a 104px floor rather than a flat three columns: three on any
 * phone worth the name, two on a very narrow one, four on a tablet — and never a
 * column so thin that a word of a text post cannot fit in it. A square tile is
 * what makes the rows line up whatever the media's own shape,
 * which is the one place this page crops on purpose: a grid of mixed portrait
 * and landscape pictures at their true sizes is a mosaic with no rows in it.
 */
.section-heading {
  margin: 30px 0 10px;
  color: var(--dim);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
/* The Media label sits under the rows it follows, so it needs less air above. */
h3.section-heading { margin-top: 22px; font-size: 11.5px; }
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(104px, 1fr));
  gap: 7px;
}
.tile {
  position: relative;
  display: block;
  aspect-ratio: 1 / 1;
  border-radius: 12px;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--divider);
  color: var(--text);
  text-decoration: none;
}
.tile:active { opacity: 0.9; }
.tile-media { width: 100%; height: 100%; display: block; object-fit: cover; background: var(--raised); }
.play {
  position: absolute;
  right: 7px; top: 7px;
  width: 24px; height: 24px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #ffffff;
  font-size: 10px;
  display: flex; align-items: center; justify-content: center;
}
/*
 * The day a tile was posted, over the same scrim the play badge uses — white on
 * a photograph needs one, and the same treatment at the other corner is what
 * makes the pair look deliberate rather than stuck on. The square is cropped
 * from the media, so the badge has to survive whatever is underneath it.
 */
.tile-date {
  position: absolute;
  left: 7px; bottom: 7px;
  padding: 2px 6px;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.55);
  color: #ffffff;
  font-size: 10.5px;
  line-height: 1.4;
  font-variant-numeric: tabular-nums;
}
/*
 * The lightbox (§6).
 *
 * Fixed, dark and full-bleed whatever the page's colour scheme is: an overlay is
 * about the picture, and a light scrim around a photograph on a light page makes
 * the photograph look like a mistake. The hidden attribute wins over the flex
 * display, which is why it is repeated with the attribute selector.
 *
 * The controls are 44px circles — the smallest thing a thumb reliably hits —
 * drawn in the corner rather than in a bar, so nothing steals height from the
 * media on a phone held upright.
 */
.lightbox {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(4, 8, 10, 0.94);
  padding: 16px;
  touch-action: pan-y;
}
.lightbox[hidden] { display: none; }
.lb-stage { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; }
.lb-media {
  max-width: 100%;
  max-height: 100%;
  border-radius: 10px;
  background: #000000;
  box-shadow: 0 18px 60px rgba(0, 0, 0, 0.55);
}
.lb-btn {
  position: absolute;
  width: 44px; height: 44px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.16);
  background: rgba(20, 26, 30, 0.75);
  color: #ffffff;
  font-size: 21px;
  line-height: 1;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  padding: 0;
}
.lb-btn:active { opacity: 0.8; }
.lb-close { top: 14px; right: 14px; }
.lb-prev { left: 12px; top: 50%; margin-top: -22px; }
.lb-next { right: 12px; top: 50%; margin-top: -22px; }
.lb-bar {
  position: absolute;
  left: 0; right: 0; bottom: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: rgba(255, 255, 255, 0.72);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
}
.lb-bar a { color: #ffffff; font-weight: 600; text-decoration: none; border-bottom: 1px solid rgba(255, 255, 255, 0.4); }

/*
 * Read by a screen reader, not drawn. A tile's picture is an alt-less image and
 * its clip has no track, so this is the one word that says what kind of media
 * the square holds; the date beside it is a real element and needs no copy here.
 */
.sr {
  position: absolute;
  width: 1px; height: 1px;
  padding: 0; margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

.empty { margin: 4px 0; }
.dim { color: var(--dim); font-size: 14px; }

/*
 * "See more" (§11): the page's own end, and the only place it asks for
 * something. Raised rather than the About card's flat surface, because it is the
 * page's second chance at the app and the About section is a footnote.
 */
.more {
  margin: 34px 0 0;
  padding: 20px 18px;
  background: var(--raised);
  border: 1px solid var(--divider);
  border-radius: 18px;
}
.more p { margin: 0; }

.about {
  margin: 22px 0 0;
  padding: 16px 17px;
  background: var(--surface);
  border: 1px solid var(--divider);
  border-radius: var(--radius);
}
.about p { margin: 0; color: var(--dim); font-size: 14px; }
.about-cta { margin-top: 10px !important; }
.about-cta a { color: var(--accent); font-weight: 600; text-decoration: none; font-size: 14px; }

footer {
  margin: 26px 0 0;
  padding-top: 16px;
  border-top: 1px solid var(--divider);
  color: var(--dim);
  font-size: 13px;
  display: flex; flex-wrap: wrap; align-items: center;
}
footer a { color: var(--dim); }
.not-found { margin: 18px 0 6px; }
code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 0.92em; }
`;
