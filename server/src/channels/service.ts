import { randomBytes } from 'node:crypto';
import { channelIdentityVersion } from './identity.js';
import { env } from '../env.js';
import { cursorKeyOf, isoOrNull, one, type Queryable } from '../db.js';
import { badRequest, conflict, forbidden, notFound } from '../http/errors.js';
import {
  claimChannelIcon,
  releaseChannelIcon,
  removeObjectQuietly,
  signObjectUrl,
} from '../media/service.js';
import type { ObjectStore } from '../media/store.js';
import {
  cursorOf,
  encodeCursor,
  isUuid,
  parsePageSize,
  withLimit,
  type Page,
  type PageQuery,
} from './cursor.js';
import { SLUG_MAX_LENGTH, SLUG_PATTERN, slugCandidate } from './slug.js';

/**
 * Channels: the single owner of the `channels` table (§3, §6, §7).
 *
 * Both surfaces read through this module — the anonymous one that serves a
 * reader and the administrator one that edits a channel — so a row can never be
 * serialised two different ways by two different files. Two mappings of the same
 * table is how a field ends up missing from one screen and present on another.
 *
 * Two rules the shapes here enforce:
 *
 *  * **A channel payload carries no counters and no identity.** There is no
 *    follower count, no post count and no owner, because Good Post has no
 *    followers, no accounts to own anything, and §1 excludes engagement
 *    numbers outright. The schema has no such column, so the shapes cannot
 *    regress into carrying them.
 *
 *  * **The profile image leaves as a URL, never as a key.** `icon_object_key`
 *    is internal: it is read here, signed, and the result is what a client
 *    receives. An object key is a permanent name for something meant to be
 *    temporary, and handing one out would let it be used forever — so it is not
 *    a field on any payload, and `iconUrl` is the only form it takes.
 *
 *  * **Visibility is decided in SQL.** A public read filters `status = 'active'`
 *    in the WHERE clause rather than after the fact, so no route can forget to
 *    hide a channel that was taken down.
 *
 *  * **There is one delete, and it is real.** A channel is removed rather than
 *    flagged. Nothing here reads a `deleted_at` column on `channels`, because
 *    there is no such column — the state a soft delete would have represented
 *    is the absence of the row.
 */

/** Mirrors the `channel_status` enum in `001_init.sql`. */
export type ChannelStatus = 'active' | 'suspended' | 'banned';

/**
 * A channel as anyone sees it: a reader, and equally an administrator looking
 * at their own.
 *
 * Deliberately free of owner and follower identity. Who runs a channel is not
 * part of its public surface (§12), and an administrator's own binding comes
 * from their session, not from this payload.
 */
export interface ChannelPayload {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
  readonly description: string | null;
  /**
   * A short-lived signed read URL for the channel's profile image, or null.
   *
   * Null is a normal state (§22): a channel with no image, and equally a
   * deployment with no bucket. The app draws its initials circle in both cases
   * rather than a broken image, which is why this is a URL and not an error.
   */
  readonly iconUrl: string | null;
  readonly categorySlug: string | null;
  readonly categoryLabel: string | null;
  readonly countryCode: string | null;
  readonly createdAt: string;
  readonly lastPostAt: string | null;
  /** The newest post's kind, or null when the channel has never posted. */
  readonly lastPostType: string | null;
  /** The opening of the newest post's text, or null for a media-only post. */
  readonly lastPostPreview: string | null;
  /**
   * The newest post's view count, and zero when the channel has never posted.
   *
   * Read from the same lateral join as the preview, for the same reason: a count
   * and the post it describes have to come from one row, and two sources can
   * disagree.
   *
   * Zero rather than null for the reason `followerCount` is zero rather than
   * null: the app renders the number on every card, so one shape is better than
   * two. A channel with no posts has no views, which is exactly what zero says.
   */
  readonly lastPostViews: number;
  /**
   * How many readers follow this channel (§4).
   *
   * A COUNT over `channel_follows` rather than a column: a denormalised counter
   * would have to be maintained by every follow, unfollow and cascade, and the
   * number is read on lists that are already one query per page. It is a real
   * number about real rows, which is the whole difference between it and a
   * social metric invented to fill a gap under a channel's name.
   */
  readonly followerCount: number;
  /** App deep link (§6): `clearview://goodpost/channel/<slug>`. */
  readonly shareLink: string;
  /** Present only on an administrator's own payloads. */
  readonly status?: ChannelStatus;
}

/** Columns every channel payload needs. Aliased `c` in every query. */
export const CHANNEL_COLUMNS = `
  c.id, c.slug, c.name, c.description, c.icon_object_key, c.category_slug,
  cat.label AS category_label, c.country_code, c.status, c.created_at, c.last_post_at,
  COALESCE(c.last_post_at, c.created_at) AS activity_at,
  (SELECT count(*)::int FROM channel_follows cf WHERE cf.channel_id = c.id) AS follower_count
`;

/**
 * The newest visible post, for a row's preview and for its timestamp.
 *
 * A lateral join rather than two correlated subqueries: the preview is read once
 * per row on the list screen, and re-planning the same ORDER BY per column would
 * pay for the same index scan twice.
 *
 * `preview_at` is why the row's timestamp comes from here and not from
 * `channels.last_post_at`. That column is the right key to SORT by — it is
 * indexed — but a preview and its timestamp must describe the same post, and two
 * sources can disagree (§4).
 */
/**
 * Nothing filters a post into renderability any more.
 *
 * The previous version of this file carried a `RENDERABLE_POST_SQL` predicate
 * that every post read had to remember: it excluded the poll rows an earlier
 * design left behind and required a post to have something in it. Both halves
 * are now facts about the data rather than rules a query can forget —
 * `posts_type_is_renderable` constrains `posts.type` to the four shapes the
 * composer produces, and publishing refuses an empty update at the point of
 * writing it. A reader therefore has no legacy case to filter, and the queries
 * below say only what they mean.
 */
export const LAST_POST_JOIN = `
  LEFT JOIN LATERAL (
    SELECT p.type AS preview_type,
           p.created_at AS preview_at,
           p.view_count AS preview_views,
           LEFT(BTRIM(COALESCE(p.body, '')), 120) AS preview_body
      FROM posts p
     WHERE p.channel_id = c.id AND p.deleted_at IS NULL
     ORDER BY p.created_at DESC, p.id DESC
      LIMIT 1
  ) lp ON true
`;

export const LAST_POST_COLUMNS = `
  lp.preview_type AS last_post_type,
  lp.preview_at AS preview_at,
  lp.preview_views AS last_post_views,
  NULLIF(lp.preview_body, '') AS last_post_preview
`;

export interface ChannelRow {
  id: string;
  slug: string;
  name: string;
  description: string | null;
  icon_object_key: string | null;
  category_slug: string | null;
  category_label: string | null;
  country_code: string | null;
  status: ChannelStatus;
  created_at: unknown;
  last_post_at: unknown;
  activity_at: unknown;
  last_post_type?: string | null;
  last_post_preview?: string | null;
  last_post_views?: number | null;
  follower_count?: number | null;
  preview_at?: unknown;
}

/**
 * The link a reader hands to somebody else (§6).
 *
 * An `https://` URL that actually resolves, rather than the `clearview://` deep
 * link this used to return. The old value was defensible when there was no page
 * behind it — a URL that 404s is worse than one that opens the app — but it made
 * sharing useless in the way that matters most: WhatsApp, Telegram and every
 * other messaging app show nothing for an unknown scheme, so a shared channel
 * arrived as dead text with no name and no preview. §6 asks for a link like
 * WhatsApp's, and that means a real page.
 *
 * `GET /c/:slug` is that page (`public/share.ts`): it renders the channel, its
 * latest posts and an "Open in ClearView" button, and carries the Open Graph
 * tags the messaging apps read to build a preview card.
 *
 * The deep link is still what the app itself resolves — the page contains it,
 * and a reader already holding ClearView gets the app either way.
 *
 * A DEPLOYMENT-WIDE URL is baked in at boot, which is why it is not per request:
 * the link is data (it is stored in nothing, but it is cached by messaging apps
 * and quoted by readers), and a value that changed with the request that produced
 * it would be a link that works for one person and not the next.
 *
 * `localhost` is the one case that keeps the old behaviour: a development build
 * has no address anybody else can reach, so it hands out the deep link rather
 * than an `http://localhost:8080/...` that would be broken for every recipient.
 *
 * The `?v=` is [channelIdentityVersion], and it is not decoration: a messaging
 * app caches the card it built from a URL and there is no way to ask it to look
 * again, so a renamed channel needs a URL it has never seen. A channel nobody
 * edits keeps one stable link.
 */
function channelShareLink(slug: string, version: string): string {
  return isShareableBase(env.PUBLIC_BASE_URL)
    ? new URL(`/c/${slug}?v=${version}`, env.PUBLIC_BASE_URL).toString()
    : `clearview://goodpost/channel/${slug}`;
}

/**
 * Whether a base URL is worth putting in a share link.
 *
 * Loopback and the unspecified address are reachable only from the machine the
 * server runs on, so a link built on one is a link that works for nobody the
 * share was sent to.
 */
export function isShareableBase(base: string): boolean {
  try {
    const { hostname, protocol } = new URL(base);
    if (protocol !== 'http:' && protocol !== 'https:') return false;
    // An IPv6 literal keeps its brackets in `hostname` — `new URL('http://[::1]/')`
    // reports `[::1]`, not `::1` — which is why both spellings are listed. The
    // bracketed form is the one that actually arrives.
    return !['localhost', '127.0.0.1', '::1', '[::1]', '0.0.0.0'].includes(hostname);
  } catch {
    return false;
  }
}

/**
 * The app deep link for a channel (§6).
 *
 * Exported because the shared web page needs it: a visitor who already has
 * ClearView should be sent into the app rather than left reading a summary of a
 * channel they could be reading properly.
 */
export function channelDeepLink(slug: string): string {
  return `clearview://goodpost/channel/${slug}`;
}

/**
 * A row as a payload, with the icon URL the caller has already signed.
 *
 * Signing is async and this is not, so the URL is an argument rather than
 * something computed here. That is deliberate: it makes "this payload has an
 * icon URL" a fact the CALLER decided, and a caller that forgets leaves the
 * image out rather than serialising a key by accident.
 */
export function mapChannel(
  row: ChannelRow,
  includeStatus = false,
  iconUrl: string | null = null
): ChannelPayload {
  return {
    id: row.id,
    slug: row.slug,
    name: row.name,
    description: row.description,
    iconUrl,
    categorySlug: row.category_slug,
    categoryLabel: row.category_label,
    countryCode: row.country_code,
    createdAt: isoOrNull(row.created_at) ?? '',
    // From the lateral join, always — every query that reads these columns joins
    // on it. The denormalised `channels.last_post_at` is deliberately NOT a
    // fallback: it is advanced when a post is published and recomputed by the
    // retention sweep, so it can outlive the post it names once that post
    // expires. A preview and its timestamp must describe the same row, and two
    // sources can disagree. Sorting still uses it (`activity_at`), where the
    // only requirement is that it orders channels by how recently they were
    // active — and where it is indexed.
    lastPostAt: isoOrNull(row.preview_at),
    lastPostType: row.last_post_type ?? null,
    lastPostPreview: row.last_post_preview ?? null,
    lastPostViews: row.last_post_views ?? 0,
    // A channel a query did not count has no followers as far as this payload
    // is concerned, which is true of every row the write paths return: a channel
    // created a moment ago has none, and the read paths that matter select the
    // count. Zero rather than null so the app has one shape to render.
    followerCount: row.follower_count ?? 0,
    // Versioned on the channel's identity, not on when this row was read: see
    // [channelIdentityVersion] for why a rename has to produce a new URL.
    shareLink: channelShareLink(
      row.slug,
      channelIdentityVersion({
        name: row.name,
        description: row.description,
        categorySlug: row.category_slug,
        iconIdentity: row.icon_object_key,
      })
    ),
    ...(includeStatus ? { status: row.status } : {})
  };
}

/**
 * Payloads for a set of rows, with each icon signed.
 *
 * One helper rather than a sign call at each map site, because the list screens
 * are where a channel's image is most visible and where it would be easiest to
 * forget: a missing icon there is a list of initials circles that looks
 * deliberate.
 *
 * Signing is per row and in parallel — an S3 signature is a local HMAC, so the
 * cost is arithmetic rather than a round trip, and a page of thirty avatars
 * should not be thirty sequential awaits.
 */
export async function mapChannelsWithIcons(
  store: ObjectStore,
  rows: readonly ChannelRow[],
  includeStatus = false
): Promise<ChannelPayload[]> {
  return Promise.all(
    rows.map(async (row) =>
      mapChannel(row, includeStatus, await signObjectUrl(store, row.icon_object_key))
    )
  );
}

export interface Category {
  readonly slug: string;
  readonly label: string;
}

/** Database-driven categories, so adding one needs no deploy (§7). */
export async function listCategories(database: Queryable): Promise<Category[]> {
  const rows = await database.query<{ slug: string; label: string }>(
    `SELECT slug, label FROM channel_categories
      WHERE is_active = true
      ORDER BY sort_order ASC, label ASC`
  );
  return rows.map((r) => ({ slug: r.slug, label: r.label }));
}

// ── Reads ───────────────────────────────────────────────────────────────

/** How a channel list is ordered. */
export type ChannelSort = 'recent' | 'name';

export interface ChannelQuery extends PageQuery {
  /** Free text over the channel's name and description (§6, §7). */
  readonly q?: string | undefined;
  readonly category?: string | undefined;
  readonly sort?: ChannelSort | undefined;
}

/**
 * Public channels, newest activity first (§3, §7).
 *
 * `recent` rather than `popular`, because popularity would have to mean follower
 * count and no follower count exists on this surface. A channel that just
 * published sorts above one that has not, tie-broken by id so the keyset is
 * total.
 *
 * A search term that matches nothing is an empty page, not a 404: Explore is a
 * list that happens to be short, and the client renders the same empty state for
 * "no channels yet".
 */
export async function listPublicChannels(
  database: Queryable,
  store: ObjectStore,
  query: ChannelQuery
): Promise<Page<ChannelPayload>> {
  const limit = parsePageSize(query.limit, env.DEFAULT_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);
  const sort: ChannelSort = query.sort === 'name' ? 'name' : 'recent';

  const params: unknown[] = [limit + 1];
  const conditions: string[] = [`c.status = 'active'`];

  if (query.q !== undefined && query.q.trim() !== '') {
    // Escaped so a term containing % or _ searches for those characters instead
    // of silently becoming a wildcard match.
    const term = `%${query.q.trim().replace(/[\\%_]/g, (m) => `\\${m}`)}%`;
    params.push(term);
    conditions.push(
      `(c.name ILIKE $${params.length} OR COALESCE(c.description, '') ILIKE $${params.length})`
    );
  }
  if (query.category !== undefined && query.category !== '') {
    params.push(query.category);
    conditions.push(`c.category_slug = $${params.length}`);
  }

  const { orderBy, keyset } = sortOrder(sort, params, cursor);

  const rows = await database.query<ChannelRow>(
    `SELECT ${CHANNEL_COLUMNS},
            ${LAST_POST_COLUMNS}
       FROM channels c
       LEFT JOIN channel_categories cat ON cat.slug = c.category_slug
       ${LAST_POST_JOIN}
      WHERE ${conditions.join('\n        AND ')}
        ${keyset}
      ORDER BY ${orderBy}
      LIMIT $1`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  return {
    items: await mapChannelsWithIcons(store, items),
    nextCursor: hasMore && last ? encodeCursor({ k: sortKey(sort, last), id: last.id }) : null,
  };
}

/** The value the cursor resumes after, per sort. */
function sortKey(sort: ChannelSort, value: ChannelRow): string {
  if (sort === 'name') return value.name.toLowerCase();
  return cursorKeyOf(value.activity_at);
}

/**
 * The ORDER BY and matching keyset predicate for a sort.
 *
 * Both directions are DESC including the id tie-break, so the row comparison
 * `(key, id) < (lastKey, lastId)` is exactly "the rows after the last one
 * returned". Mixing an ASC tie-break with a DESC primary would skip or repeat
 * rows at a page boundary.
 */
function sortOrder(
  sort: ChannelSort,
  params: unknown[],
  cursor: { k: string; id: string } | null
): { orderBy: string; keyset: string } {
  const column = sort === 'name' ? 'LOWER(c.name)' : 'COALESCE(c.last_post_at, c.created_at)';
  const cast = sort === 'name' ? 'text' : 'timestamptz';

  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    const keyParam = `$${params.length - 1}`;
    const idParam = `$${params.length}`;
    keyset = `AND (${column}, c.id) < (${keyParam}::${cast}, ${idParam}::uuid)`;
  }

  return { orderBy: `${column} DESC, c.id DESC`, keyset };
}

/**
 * One channel, by uuid or by share slug (§6).
 *
 * Both lookups answer the same 404. Telling "no such id" apart from "no such
 * slug" would describe the difference between a malformed link and a channel
 * that was taken down, which is state a reader has no business reading.
 */
export async function getPublicChannel(
  database: Queryable,
  store: ObjectStore,
  idOrSlug: string
): Promise<ChannelPayload> {
  const row = await loadChannelRow(database, resolveChannelLookup(idOrSlug));
  if (row.status !== 'active') throw notFound('channel_not_found');
  return mapChannel(row, false, await signObjectUrl(store, row.icon_object_key));
}

/**
 * "A uuid, or a slug" as something the database can be asked about (§6).
 *
 * Exported because it is no longer only this file's question: a reader
 * following a channel names it the same way a reader opening one does (§4), and
 * a second copy of this rule is where "the follow endpoint accepts a slug the
 * read endpoint rejects" would come from. A slug is validated here, so an
 * identifier that could never name a channel is a 404 before it reaches SQL.
 */
export function resolveChannelLookup(idOrSlug: string): { by: 'id' | 'slug'; value: string } {
  const id = resolveChannelId(idOrSlug);
  return { by: id ? 'id' : 'slug', value: id ?? normaliseSlug(idOrSlug) };
}

/**
 * A channel an administrator is allowed to see, whatever its status.
 *
 * A suspended channel is refused to readers but stays readable to whoever runs
 * it: switching a channel off must leave its administrator able to see what they
 * are being asked to fix. That is the same reasoning the old moderation model
 * used, kept because it is still right.
 */
export async function loadChannelForAdmin(
  database: Queryable,
  idOrSlug: string
): Promise<ChannelRow> {
  const id = resolveChannelId(idOrSlug);
  return loadChannelRow(database, {
    by: id ? 'id' : 'slug',
    value: id ?? normaliseSlug(idOrSlug),
  });
}

/** The id, when the reference is a uuid; null when it is a slug. */
function resolveChannelId(idOrSlug: string): string | null {
  const trimmed = idOrSlug.trim();
  return isUuid(trimmed) ? trimmed : null;
}

/**
 * A slug as a link would carry it, or a 404.
 *
 * Lower-cased because a link can be typed by hand and slugs are generated
 * lowercase. The length is capped BEFORE the pattern test, so an absurd path is
 * rejected by a length check rather than fed to a matcher.
 */
function normaliseSlug(value: string): string {
  const normalised = value.trim().toLowerCase();
  if (normalised.length === 0 || normalised.length > SLUG_MAX_LENGTH) {
    throw notFound('channel_not_found');
  }
  if (!SLUG_PATTERN.test(normalised)) throw notFound('channel_not_found');
  return normalised;
}

/** Load a channel row by id or slug, or throw a 404. */
export async function loadChannelRow(
  database: Queryable,
  lookup: { by: 'id' | 'slug'; value: string }
): Promise<ChannelRow> {
  const column = lookup.by === 'id' ? 'c.id' : 'c.slug';
  const row = await database.queryOne<ChannelRow>(
    `SELECT ${CHANNEL_COLUMNS},
            ${LAST_POST_COLUMNS}
       FROM channels c
       LEFT JOIN channel_categories cat ON cat.slug = c.category_slug
       ${LAST_POST_JOIN}
      WHERE ${column} = $1`,
    [lookup.value]
  );
  if (!row) throw notFound('channel_not_found');
  return row;
}

/**
 * Every channel an administrator may work with (§17, §18).
 *
 * A super administrator sees all of them; a channel administrator sees exactly
 * the one they are bound to. The restriction is in the WHERE clause, so it is
 * not a filter the caller could forget — and a channel administrator therefore
 * cannot enumerate channels even by guessing at a listing endpoint.
 */
export async function listChannelsForAdmin(
  database: Queryable,
  store: ObjectStore,
  scope: { readonly role: string; readonly channelId: string | null }
): Promise<ChannelPayload[]> {
  const params: unknown[] = [];
  const conditions: string[] = [];

  if (scope.role !== 'super_admin') {
    params.push(scope.channelId);
    conditions.push(`c.id = $${params.length}`);
  }

  const rows = await database.query<ChannelRow>(
    `SELECT ${CHANNEL_COLUMNS},
            ${LAST_POST_COLUMNS}
       FROM channels c
       LEFT JOIN channel_categories cat ON cat.slug = c.category_slug
       ${LAST_POST_JOIN}
      ${conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : ''}
      ORDER BY LOWER(c.name) ASC`,
    params
  );

  return mapChannelsWithIcons(store, rows, true);
}

// ── Writes (§17, §18, §20) ──────────────────────────────────────────────

export interface CreateChannelInput {
  readonly name: string;
  readonly description?: string | undefined;
  readonly categorySlug?: string | undefined;
  readonly countryCode?: string | undefined;
}

/**
 * Create a channel.
 *
 * No owner is recorded on the row. Who runs a channel is
 * `admin_users.channel_id` — a fact about the administrator — which is what
 * lets a channel exist for the moment between its creation and the creation of
 * the login bound to it, both of which happen in one transaction one level up.
 *
 * ## Slug collisions, and why this is `ON CONFLICT` rather than a retry
 *
 * A slug is derived from the name, so two channels called "Namaz Times" are a
 * matter of when rather than whether — and with §16's open creator signup that
 * is now an ordinary event rather than a rare one. Attempt 0 takes the bare slug
 * so the common case produces a short, memorable link, and later attempts append
 * random hex.
 *
 * Choosing between them must not involve a failed INSERT. This function is
 * called INSIDE the transaction that also creates the administrator bound to the
 * channel, and in Postgres one failed statement aborts the whole transaction:
 * `catch { try again }` would retry into an aborted transaction and fail on
 * every attempt with "current transaction is aborted" — a 500 for a second
 * channel whose name merely matched an existing one. (That is what this used to
 * do, and a savepoint would only have moved the problem.)
 *
 * `ON CONFLICT (slug) DO NOTHING` makes a taken slug a zero-row INSERT instead
 * of an error, which is exactly the signal the loop needs and leaves the
 * transaction healthy for the next attempt. Only a slug conflict is absorbed:
 * any other constraint violation still raises, because silencing a different
 * error here would turn a bug into a channel that silently has no name.
 */
export async function createChannel(
  database: Queryable,
  input: CreateChannelInput
): Promise<ChannelRow> {
  const country = input.countryCode?.trim().toUpperCase() || null;
  if (country && !/^[A-Z]{2}$/.test(country)) {
    throw badRequest('invalid_country', 'countryCode must be a two-letter ISO-3166-1 code.');
  }

  // Validated against the category table rather than a hard-coded list, so a
  // category added to the database is immediately usable.
  await assertCategoryExists(database, input.categorySlug);

  for (let attempt = 0; attempt < 5; attempt += 1) {
    const slug = slugCandidate(input.name, attempt, randomBytes(3).toString('hex'));
    const inserted = await database.query<ChannelRow>(
      `INSERT INTO channels (slug, name, description, category_slug, country_code)
       VALUES ($1, $2, $3, $4, $5)
       ON CONFLICT (slug) DO NOTHING
       RETURNING id, slug, name, description, icon_object_key, category_slug,
                 NULL::text AS category_label, country_code, status, created_at,
                 NULL::timestamptz AS last_post_at, created_at AS activity_at`,
      [slug, input.name.trim(), input.description?.trim() ?? null, input.categorySlug ?? null, country]
    );

    // Empty means the slug was taken — nothing was written and, crucially,
    // nothing was aborted. Ask for a new one.
    if (inserted.length > 0) return one(inserted);
  }

  // Unreachable in practice: five random suffixes colliding is not a thing.
  throw conflict('slug_unavailable', 'Could not allocate a channel link. Try a different name.');
}

export interface UpdateChannelInput {
  readonly name?: string | undefined;
  readonly description?: string | null | undefined;
  readonly categorySlug?: string | null | undefined;
  readonly countryCode?: string | null | undefined;
}

/**
 * Edit a channel's public profile (§19).
 *
 * `slug` is intentionally not editable: links have already been shared, and a
 * slug that changes under a recipient is a broken link.
 *
 * COALESCE-style patching against the current value, so an omitted field is left
 * alone while an explicit null clears it. Distinguishing those is what lets one
 * endpoint serve both "rename" and "remove the description".
 */
export async function updateChannel(
  database: Queryable,
  channelId: string,
  patch: UpdateChannelInput
): Promise<ChannelRow> {
  const existing = await loadChannelRow(database, { by: 'id', value: channelId });
  await assertCategoryExists(database, patch.categorySlug);

  const country =
    patch.countryCode === undefined || patch.countryCode === null
      ? patch.countryCode
      : patch.countryCode.trim().toUpperCase();
  if (country !== undefined && country !== null && country !== '' && !/^[A-Z]{2}$/.test(country)) {
    throw badRequest('invalid_country', 'countryCode must be a two-letter ISO-3166-1 code.');
  }

  const rows = await database.query<ChannelRow>(
    `UPDATE channels
        SET name = COALESCE($2, name),
            description = CASE WHEN $3::boolean THEN $4 ELSE description END,
            category_slug = CASE WHEN $5::boolean THEN $6 ELSE category_slug END,
            country_code = CASE WHEN $7::boolean THEN $8 ELSE country_code END
      WHERE id = $1
      RETURNING id, slug, name, description, icon_object_key, category_slug,
                NULL::text AS category_label, country_code, status, created_at,
                last_post_at, COALESCE(last_post_at, created_at) AS activity_at`,
    [
      existing.id,
      patch.name?.trim() ?? null,
      patch.description !== undefined,
      patch.description?.trim() || null,
      patch.categorySlug !== undefined,
      patch.categorySlug ?? null,
      patch.countryCode !== undefined,
      country || null,
    ]
  );

  return one(rows);
}

/**
 * Apply an icon change to a channel, inside the caller's transaction (§21).
 *
 * Two writes that must be atomic: the media row becomes the channel's icon, and
 * the channel points at its object key. Committing one without the other leaves
 * either an avatar nobody owns or an owned object no channel points at.
 *
 * Returns the key of the object being REPLACED, if any. It is not removed here
 * — see [setChannelIcon] — because the removal must happen after the commit that
 * makes it correct.
 */
async function applyChannelIcon(
  database: Queryable,
  adminId: string,
  channelId: string,
  mediaId: string
): Promise<string | null> {
  const claim = await claimChannelIcon(database, adminId, channelId, mediaId);

  const updated = await database.query(
    `UPDATE channels SET icon_object_key = $2
      WHERE id = $1
      RETURNING id`,
    [channelId, claim.objectKey]
  );
  if (updated.length === 0) throw notFound('channel_not_found');

  return claim.previousObjectKey;
}

/**
 * Set a channel's profile image from a confirmed upload (§21).
 *
 * The replaced object is removed AFTER the transaction commits, deliberately: a
 * crash between the two leaves an unreferenced object, which costs a little
 * money and a later sweep can collect, whereas removing it first and then
 * failing to commit would leave the channel's image pointing at nothing.
 *
 * The removal never fails the request. The change the administrator asked for
 * has already succeeded by then, and reporting a failure for a succeeded edit
 * would send them round the loop for no reason.
 */
export async function setChannelIcon(
  database: Queryable,
  store: ObjectStore,
  adminId: string,
  channelId: string,
  mediaId: string
): Promise<void> {
  const previousObjectKey = await database.transaction((tx) =>
    applyChannelIcon(tx, adminId, channelId, mediaId)
  );
  await removeObjectQuietly(store, previousObjectKey);
}

/**
 * Release a channel's icon inside the caller's transaction, returning its key.
 *
 * The row and the channel's pointer move together, so there is no state in
 * which a channel points at an image it no longer owns — which is exactly the
 * state a later publish could adopt.
 */
export async function clearChannelIconIn(
  database: Queryable,
  channelId: string
): Promise<string | null> {
  const objectKey = await releaseChannelIcon(database, channelId);

  const updated = await database.query(
    `UPDATE channels SET icon_object_key = NULL
      WHERE id = $1
      RETURNING id`,
    [channelId]
  );
  if (updated.length === 0) throw notFound('channel_not_found');

  return objectKey;
}

/**
 * Remove a channel's profile image (§21).
 *
 * The object is removed after the commit, and never fatally — the same split as
 * [setChannelIcon], for the same reason.
 */
export async function clearChannelIcon(
  database: Queryable,
  store: ObjectStore,
  channelId: string
): Promise<void> {
  const released = await database.transaction((tx) => clearChannelIconIn(tx, channelId));
  await removeObjectQuietly(store, released);
}

/** Re-exported for the admin routes, which create the icon inside their own transaction. */
export { applyChannelIcon };

async function assertCategoryExists(
  database: Queryable,
  slug: string | null | undefined
): Promise<void> {
  if (slug === undefined || slug === null) return;
  const category = await database.queryOne(
    `SELECT slug FROM channel_categories WHERE slug = $1 AND is_active = true`,
    [slug]
  );
  if (!category) throw badRequest('invalid_category', 'That category does not exist.');
}

/**
 * Disable or re-enable a channel (§17).
 *
 * `suspended` rather than a delete: §17 asks for a channel to be switchable off,
 * and switching one off has to stay reversible. A suspended channel leaves every
 * public surface and is refused to readers, while its own administrator keeps
 * read access so they can see what they are being asked to fix.
 */
export async function setChannelStatus(
  database: Queryable,
  channelId: string,
  status: ChannelStatus
): Promise<ChannelRow> {
  const rows = await database.query<ChannelRow>(
    `UPDATE channels SET status = $2
      WHERE id = $1
      RETURNING id, slug, name, description, icon_object_key, category_slug,
                NULL::text AS category_label, country_code, status, created_at,
                last_post_at, COALESCE(last_post_at, created_at) AS activity_at`,
    [channelId, status]
  );
  if (rows.length === 0) throw notFound('channel_not_found');
  return one(rows);
}

/**
 * Delete a channel, and everything that belonged to it (§17).
 *
 * A real delete rather than a flag: the row goes, and the schema is arranged so
 * the rest goes with it. That is why this is one statement and not a list of
 * cleanups that a future edit could reorder:
 *
 *  * the posts, and their attachments — `posts.channel_id` and
 *    `post_media.channel_id` CASCADE;
 *  * the profile image, which is a `post_media` row owned by the channel
 *    rather than by a post;
 *  * the login created to run the channel — `admin_users.channel_id` CASCADES,
 *    so those credentials stop working instead of resolving to a channel that
 *    is gone. The audit rows that login wrote SURVIVE, unmodified:
 *    `admin_audit_logs.admin_id` is deliberately not a foreign key — a cascade
 *    into a table that is immutable by trigger cannot work — because a trail
 *    that is rewritten or erased as its subjects come and go is not evidence of
 *    anything.
 *
 * The object keys come back rather than being removed here. Rows and bucket
 * objects cannot be one transaction, and the safe order is the one that leaves an
 * unreferenced object — cheap, and collectable by the sweep — instead of a live
 * row pointing at nothing, which is a broken image on every read. The caller
 * removes them once this transaction has committed.
 *
 * Only a super administrator can reach this (`channels.delete`, and the scope
 * check that follows every lookup), which is the whole of the protection §17
 * asks for: a channel's own administrator may manage it and may not destroy its
 * history.
 */
export async function deleteChannel(database: Queryable, channelId: string): Promise<string[]> {
  return database.transaction(async (tx) => {
    // Locked before the keys are read. A publish racing this delete would
    // otherwise be able to attach an upload between the two statements, and the
    // object it attached would never be named again.
    const rows = await tx.query<{ icon_object_key: string | null }>(
      `SELECT icon_object_key FROM channels WHERE id = $1 FOR UPDATE`,
      [channelId]
    );
    if (rows.length === 0) throw notFound('channel_not_found');

    const attachments = await tx.query<{ object_key: string }>(
      `SELECT m.object_key
         FROM post_media m
         LEFT JOIN posts p ON p.id = m.post_id
        WHERE m.channel_id = $1 OR p.channel_id = $1`,
      [channelId]
    );

    await tx.query(`DELETE FROM channels WHERE id = $1`, [channelId]);

    const icon = rows[0]?.icon_object_key ?? null;
    return [...new Set([...attachments.map((r) => r.object_key), ...(icon ? [icon] : [])])];
  });
}

/**
 * Assert that an administrator may act on this channel (§18, §25).
 *
 * The scope is read from the account's own row, never from the request: a
 * channel administrator's `channel_id` is the only channel they can name, and
 * naming another one is a 404 rather than a 403 — telling them it exists would
 * confirm a channel they have no business knowing about.
 *
 * Returns the row so a caller does not pay for the lookup twice.
 */
export async function requireChannelScope(
  database: Queryable,
  scope: { readonly role: string; readonly channelId: string | null },
  channelId: string
): Promise<ChannelRow> {
  const row = await loadChannelForAdmin(database, channelId);

  if (scope.role !== 'super_admin' && row.id !== scope.channelId) {
    throw notFound('channel_not_found');
  }

  if (scope.role !== 'super_admin' && row.status === 'banned') {
    throw forbidden('channel_unavailable', 'This channel is not currently available.');
  }

  return row;
}
