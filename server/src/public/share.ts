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
 * the button at the top does. That is also why this endpoint is not behind the
 * reader API: it takes no token, and it must not, because the whole point is
 * that it works for someone who has never heard of ClearView.
 *
 * ## Why the Open Graph tags matter
 *
 * They are not decoration. `og:title`, `og:description` and `og:image` are what a
 * messaging app reads to build its preview card, and a card is most of what makes
 * a link worth sharing. Without them the same page still renders, but the share
 * looks broken — which is the complaint this page exists to answer.
 *
 * ## Escaping
 *
 * EVERY value from the database is escaped, including the channel name. A
 * channel name is free text typed by a person, and this is the only place in the
 * product where such a value is interpolated into HTML rather than JSON — so it
 * is the only place where a name like `<script>` could become a script. The
 * escape is applied once, at the point of interpolation, rather than trusted to
 * each caller.
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

/** A date as a reader reads it, in the deployment's own locale rules. */
function readableDate(value: unknown): string {
  if (value instanceof Date) {
    return value.toISOString().replace('T', ' ').slice(0, 16) + ' UTC';
  }
  return '';
}

export function buildShareRouter(database: Queryable, store: ObjectStore): Router {
  const router = Router();

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
    // Express types a path parameter as `string | string[]` because a pattern can
    // produce repeats. This route is plain, so the array case cannot occur — but
    // it is collapsed rather than asserted, so a value that somehow arrived as a
    // list reaches the lookup as one string and fails as "no such channel"
    // instead of reaching the database as an array.
    const raw = req.params.slug;
    const slug = Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '');

    let channel;
    let posts;
    try {
      channel = await getPublicChannel(database, store, slug);
      // A short page: the newest few posts are what a visitor needs to judge
      // the channel, and the app is where the rest of them live. Older posts are
      // not paginated here on purpose — a channel is not an archive (§14), and a
      // crawler walking an unbounded list is a load nobody asked for.
      const page = await listPublicChannelPosts(database, store, slug, { limit: '5' });
      posts = page.items;
    } catch (err) {
      const type = err instanceof ApiError ? err.type : 'internal_error';
      const status = err instanceof ApiError && err.status < 500 ? err.status : 500;
      res.status(status).type('html').send(notFoundPage(type));
      return;
    }

    res
      .status(200)
      .type('html')
      .send(channelPage(channel, posts));
  });

  return router;
}

/** The channel page, with the preview card a messaging app will build. */
function channelPage(
  channel: Awaited<ReturnType<typeof getPublicChannel>>,
  posts: Awaited<ReturnType<typeof listPublicChannelPosts>>['items']
): string {
  const name = escapeHtml(channel.name);
  const description = channel.description ?? `Updates from ${channel.name}`;
  const icon = channel.iconUrl === null ? null : safeAttributeUrl(channel.iconUrl);
  const deepLink = escapeHtml(channelDeepLink(channel.slug));
  const shareUrl = escapeHtml(new URL(`/c/${channel.slug}`, env.PUBLIC_BASE_URL).toString());

  const rows = posts
    .map((post) => {
      // A post with no body is a media post: the page has no image to show it
      // with (that is the app's job), so it says so rather than rendering an
      // empty paragraph that reads like a failure.
      const body = post.body === null || post.body.trim() === ''
        ? '<span class="dim">(media post)</span>'
        : escapeHtml(post.body).replace(/\n/g, '<br>');
      const when = readableDate(post.createdAt);
      return `<article><div class="meta">${escapeHtml(when)}</div><p>${body}</p></article>`;
    })
    .join('\n');

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${name} · ClearView</title>
<meta name="description" content="${escapeHtml(description)}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="ClearView">
<meta property="og:title" content="${name}">
<meta property="og:description" content="${escapeHtml(description)}">
<meta property="og:url" content="${shareUrl}">
${icon === null ? '' : `<meta property="og:image" content="${icon}">`}
<meta name="twitter:card" content="${icon === null ? 'summary' : 'summary_large_image'}">
<style>
  :root { color-scheme: dark; }
  body { margin: 0; background: #0b141a; color: #e9edef;
         font: 16px/1.5 -apple-system, Roboto, "Segoe UI", sans-serif; }
  main { max-width: 620px; margin: 0 auto; padding: 28px 20px 64px; }
  header { display: flex; align-items: center; gap: 14px; margin-bottom: 22px; }
  .avatar { width: 64px; height: 64px; border-radius: 50%; object-fit: cover;
            background: #202c33; flex: 0 0 auto; }
  .initial { width: 64px; height: 64px; border-radius: 50%; background: #202c33;
             color: #00a884; font-size: 26px; font-weight: 700;
             display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
  h1 { font-size: 21px; margin: 0 0 4px; }
  .sub { color: #8696a0; font-size: 14px; margin: 0; }
  .open { display: inline-block; margin: 20px 0 8px; padding: 11px 18px;
          background: #00a884; color: #0b141a; font-weight: 600;
          border-radius: 22px; text-decoration: none; }
  article { border-top: 1px solid #1f2c34; padding: 14px 0; }
  article p { margin: 6px 0 0; white-space: pre-wrap; }
  .meta { color: #8696a0; font-size: 12px; }
  .dim { color: #8696a0; font-style: italic; }
  footer { color: #8696a0; font-size: 13px; margin-top: 28px; }
</style>
</head>
<body>
<main>
  <header>
    ${
      icon === null
        ? `<div class="initial">${escapeHtml(channel.name.slice(0, 1).toUpperCase())}</div>`
        : `<img class="avatar" src="${icon}" alt="">`
    }
    <div>
      <h1>${name}</h1>
      <p class="sub">${escapeHtml(description)}</p>
    </div>
  </header>

  <a class="open" href="${deepLink}">Open in ClearView</a>

  ${rows === '' ? '<p class="dim">No updates yet.</p>' : rows}

  <footer>Shared from ClearView · ${shareUrl}</footer>
</main>
</body>
</html>
`;
}

/** A page for a channel that is gone, or was never there. */
function notFoundPage(reason: string): string {
  const message =
    reason === 'channel_not_found'
      ? 'That channel is not available. It may have been removed, or the link may be incomplete.'
      : 'Something went wrong loading that channel.';

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Channel not available · ClearView</title>
<style>
  body { margin: 0; background: #0b141a; color: #e9edef;
         font: 16px/1.5 -apple-system, Roboto, "Segoe UI", sans-serif; }
  main { max-width: 520px; margin: 0 auto; padding: 64px 20px; }
  p { color: #8696a0; }
</style>
</head>
<body>
<main>
  <h1>Channel not available</h1>
  <p>${escapeHtml(message)}</p>
</main>
</body>
</html>
`;
}
