/**
 * Copying a channel's name and picture from the platform it lives on (§ new).
 *
 * ## What this is, and what it deliberately is not
 *
 * When an administrator adds a channel by handle, the row needs something to
 * show. Asking the platform ONCE, at the moment the channel is added, is the
 * narrowest possible version of that: a single page fetch, scraped for the two
 * values its own Open Graph tags already publish, and then stored. Nothing is
 * fetched again on a timer, no API key is held, and no content is read — only the
 * `<title>`-shaped meta tags every page serves to link unfurlers.
 *
 * Every failure is non-fatal and returns blanks. A platform that rate-limits us,
 * a handle that does not exist, an offline deployment: all of them mean the
 * administrator types the name (or not — a channel with a handle and no name is
 * still a working row, because the handle is identity).
 */

export type DirectoryPlatform = 'youtube' | 'instagram' | 'x';

export const DIRECTORY_PLATFORMS: readonly DirectoryPlatform[] = [
  'youtube',
  'instagram',
  'x',
];

export interface EnrichedChannel {
  readonly name: string | null;
  readonly iconUrl: string | null;
}

const EMPTY: EnrichedChannel = { name: null, iconUrl: null };

/** How long to wait for a platform's page before giving up. */
const FETCH_TIMEOUT_MS = 5000;

/**
 * The page a handle lives at.
 *
 * Also the share target the app opens, so it is defined once here and returned
 * in every channel payload — the client never builds a platform URL itself.
 */
export function channelUrl(platform: DirectoryPlatform, handle: string): string {
  switch (platform) {
    case 'youtube':
      return `https://www.youtube.com/@${handle}`;
    case 'instagram':
      return `https://www.instagram.com/${handle}/`;
    case 'x':
      return `https://x.com/${handle}`;
  }
}

/** The page an unfurler is served, which is where the tags are read from. */
function profilePage(platform: DirectoryPlatform, handle: string): string {
  // The same URL in every case today; kept as its own function because the
  // unfurl target and the share target are different ideas that happen to agree.
  return channelUrl(platform, handle);
}

/**
 * Pull one Open Graph tag out of a page's `<head>`.
 *
 * Attribute order is not fixed — some pages emit `property` first and some emit
 * `content` first — so both shapes are matched rather than assuming one.
 */
function metaContent(html: string, property: string): string | null {
  const escaped = property.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const first = new RegExp(
    `<meta[^>]+property=["']${escaped}["'][^>]+content=["']([^"']+)["']`,
    'i'
  );
  const second = new RegExp(
    `<meta[^>]+content=["']([^"']+)["'][^>]+property=["']${escaped}["']`,
    'i'
  );
  const match = first.exec(html) ?? second.exec(html);
  return match ? decodeEntities(match[1] ?? '') : null;
}

/** The handful of entities an Open Graph value actually contains. */
function decodeEntities(value: string): string {
  return value
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>');
}

/** Only an absolute http(s) URL is ever stored as an icon. */
function absoluteHttpUrl(value: string | null): string | null {
  if (!value) return null;
  return /^https?:\/\/\S+$/i.test(value) ? value : null;
}

export type Enricher = (platform: DirectoryPlatform, handle: string) => Promise<EnrichedChannel>;

/**
 * Fetch a channel's name and icon from its platform page.
 *
 * Best-effort by construction: every path that is not a clean read returns
 * blanks rather than throwing, because a directory row is still useful without
 * an icon and an administrator adding a channel should never be blocked by a
 * platform being slow.
 */
export const fetchChannelProfile: Enricher = async (platform, handle) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  try {
    const response = await fetch(profilePage(platform, handle), {
      signal: controller.signal,
      redirect: 'follow',
      headers: {
        // A plausible browser UA. Not a disguise — a default server UA is
        // refused outright by some of these pages before they send any tags.
        'user-agent':
          'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36',
        accept: 'text/html,application/xhtml+xml',
      },
    });
    if (!response.ok) return EMPTY;

    const html = (await response.text()).slice(0, 512 * 1024);
    const name = metaContent(html, 'og:title');
    const iconUrl = absoluteHttpUrl(metaContent(html, 'og:image'));
    return { name, iconUrl };
  } catch {
    return EMPTY;
  } finally {
    clearTimeout(timer);
  }
};
