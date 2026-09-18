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

/** One attachment, rendered as itself. */
function renderMedia(media: {
  readonly kind: string;
  readonly url: string | null;
  readonly width: number | null;
  readonly height: number | null;
}): string {
  const url = media.url === null ? null : safeAttributeUrl(media.url);
  if (url === null) {
    return '<p class="dim">An attachment on this update cannot be shown here.</p>';
  }

  // The file's own proportions, so nothing is cropped or stretched. A photo
  // whose dimensions the server did not record still gets a sane box rather than
  // a zero-height one.
  const ratio =
    media.width !== null && media.height !== null && media.width > 0 && media.height > 0
      ? ` style="aspect-ratio:${media.width}/${media.height}"`
      : '';

  if (media.kind === 'video') {
    return `<video class="media" controls preload="metadata" playsinline${ratio} src="${url}"></video>`;
  }

  return `<a class="media-link" href="${url}" rel="noopener"><img class="media" loading="lazy" alt=""${ratio} src="${url}"></a>`;
}

/** One post, as a card. */
function postCard(post: {
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
  readonly editedAt: string | null;
  readonly reactions: readonly { readonly emoji: string; readonly count: number }[];
}): string {
  const media = post.media.map(renderMedia).join('\n');
  const body = post.body === null || post.body.trim() === '' ? '' : renderBody(post.body);
  const when = escapeHtml(readableDate(post.createdAt));
  const stamp = `<time datetime="${escapeHtml(machineDate(post.createdAt))}">${when}</time>`;

  // A post's picture or clip, then its words, then its link — the order the app
  // draws them in, so a shared update and the app's own update read as one thing.
  const edited = post.editedAt === null ? '' : '<span class="sep">·</span><span>edited</span>';

  const link =
    post.linkUrl === null
      ? ''
      : (() => {
          const href = safeAttributeUrl(post.linkUrl);
          if (href === null) return '';
          const title = post.linkTitle?.trim();
          const label = title && title !== '' ? escapeHtml(title) : escapeHtml(domainOf(post.linkUrl));
          return `<a class="link-card" href="${href}" rel="noopener noreferrer">
  <span class="link-title">${label}</span>
  <span class="link-domain">${escapeHtml(domainOf(post.linkUrl))}</span>
</a>`;
        })();

  const reactions = post.reactions
    .filter((reaction) => reaction.count > 0)
    .map(
      (reaction) =>
        `<span class="reaction">${escapeHtml(reaction.emoji)}<span class="reaction-count">${reaction.count}</span></span>`
    )
    .join('');

  return `<article class="post">
  <header class="post-meta">${stamp}${edited}</header>
${media === '' ? '' : `  <div class="post-media">${media}</div>\n`}${body === '' ? '' : `  <p class="post-body">${body}</p>\n`}${link === '' ? '' : `  ${link}\n`}${reactions === '' ? '' : `  <div class="post-reactions">${reactions}</div>\n`}</article>`;
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
      const page = await listPublicChannelPosts(database, store, slug, { limit: '6' });
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
 * The script served at `/c/app.js` (§6).
 *
 * Written as ES5 on purpose — an older WebView inside a messaging app is exactly
 * the client this page is opened in, and a syntax error there is a dead button.
 * It is also deliberately tiny: one listener, one timer, nothing to fail.
 */
const APP_JS = `(function () {
  var link = document.getElementById('open');
  if (!link) return;

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
})();
`;

/** The channel page, with the preview card a messaging app will build. */
function channelPage(
  channel: Awaited<ReturnType<typeof getPublicChannel>>,
  posts: Awaited<ReturnType<typeof listPublicChannelPosts>>['items']
): string {
  const name = escapeHtml(channel.name);
  const slug = escapeHtml(channel.slug);
  const description = channel.description ?? `Updates from ${channel.name}`;
  const handle = `@${slug}`;
  const deepLink = escapeHtml(channelDeepLink(channel.slug));
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

  const cards = posts.map(postCard).join('\n');

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
<link rel="icon" href="${iconPath}">
<style>${STYLES}</style>
</head>
<body>
<main>
  <header class="channel">
    <div class="avatar">
      <span class="avatar-initial">${initial}</span>
      <img src="${iconPath}" alt="">
    </div>
    <div class="channel-text">
      <h1>${name}</h1>
      <p class="handle">${handle}</p>
      <p class="facts">${facts}</p>
    </div>
  </header>

  <p class="description">${escapeHtml(description)}</p>

  <div class="cta">
    <a class="button primary" href="${deepLink}" id="open" data-store="${store}">Open in ClearView</a>
    <a class="button" href="${store}" rel="noopener">Get ClearView</a>
  </div>
  <p class="cta-note">Reading a channel never needs an account.</p>

  <section class="posts">
    ${cards === '' ? '<p class="dim empty">No updates yet. This channel has not posted anything.</p>' : cards}
  </section>

  <section class="about">
    <h2>ClearView</h2>
    <p>
      A focused place to spend time online on purpose: the Quran, a calm media
      space, channel updates like this one, content controls, and simple
      productivity tools — in one app, without a feed built to keep you scrolling.
    </p>
    <p class="about-cta"><a href="${store}" rel="noopener">Explore ClearView on Google Play →</a></p>
  </section>

  <footer>
    <span>Shared from ClearView</span>
    <span class="sep">·</span>
    <a href="${shareUrl}">${name}</a>
  </footer>
</main>
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
    <p>
      A focused place to spend time online on purpose: the Quran, a calm media
      space, channel updates, content controls and simple productivity tools.
    </p>
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

.channel { display: flex; align-items: center; gap: 14px; }
.avatar {
  position: relative;
  width: 68px; height: 68px; flex: 0 0 auto;
  border-radius: 50%;
  background: var(--raised);
  overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.avatar img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.avatar-initial { color: var(--accent); font-size: 27px; font-weight: 700; }
.channel-text { min-width: 0; }
.handle { margin: 0; color: var(--dim); font-size: 14px; }
.facts { margin: 3px 0 0; color: var(--dim); font-size: 13px; }
.sep { margin: 0 6px; color: var(--divider); }
@media (prefers-color-scheme: light) { .sep { color: var(--dim); } }

.description { margin: 16px 0 0; color: var(--text); font-size: 15px; white-space: pre-wrap; }
.description:empty { display: none; }

.cta { display: flex; flex-wrap: wrap; gap: 10px; margin: 18px 0 0; }
.button {
  display: inline-block;
  padding: 11px 18px;
  border-radius: 22px;
  background: var(--raised);
  color: var(--text);
  font-size: 14.5px;
  font-weight: 600;
  text-decoration: none;
  border: 1px solid transparent;
}
.button.primary { background: var(--accent); color: var(--on-accent); }
.button:active { opacity: 0.85; }
.cta-note { margin: 10px 0 0; color: var(--dim); font-size: 12.5px; }

.posts { margin: 26px 0 0; display: flex; flex-direction: column; gap: 12px; }
.post {
  background: var(--surface);
  border: 1px solid var(--divider);
  border-radius: var(--radius);
  padding: 14px 15px;
}
.post-meta { display: flex; align-items: center; color: var(--dim); font-size: 12.5px; margin-bottom: 8px; }
.post-meta time { font-variant-numeric: tabular-nums; }
.post-body { margin: 0; font-size: 15.5px; line-height: 1.55; overflow-wrap: anywhere; }
.post-body code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13.5px;
  background: var(--raised);
  padding: 1px 5px;
  border-radius: 6px;
}
.post-media { display: flex; flex-direction: column; gap: 8px; margin: 0 0 10px; }
.media { width: 100%; display: block; border-radius: 10px; background: var(--raised); }
.media-link { display: block; }
video.media { max-height: 70vh; }

.link-card {
  display: block;
  margin: 10px 0 0;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--raised);
  text-decoration: none;
  color: var(--text);
}
.link-title { display: block; font-size: 14.5px; font-weight: 600; overflow-wrap: anywhere; }
.link-domain { display: block; color: var(--dim); font-size: 12.5px; margin-top: 2px; }

.post-reactions { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0 0; }
.reaction {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--raised);
  border: 1px solid var(--divider);
  font-size: 13px;
}
.reaction-count { color: var(--dim); font-size: 12.5px; }

.empty { margin: 4px 0; }
.dim { color: var(--dim); font-size: 14px; }

.about {
  margin: 34px 0 0;
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
