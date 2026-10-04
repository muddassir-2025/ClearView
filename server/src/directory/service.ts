import type { Queryable } from '../db.js';
import { badRequest, notFound } from '../http/errors.js';
import {
  channelUrl,
  fetchChannelProfile,
  type DirectoryPlatform,
  type Enricher,
} from './enrich.js';

/**
 * The channel directory (§ new): a hand-picked list of external channels.
 *
 * Nothing here is a ClearView channel and nothing here can post. A row is a
 * platform, a handle, and the two things worth showing — a name and a picture —
 * and the only writer is a super administrator.
 */

// ── Shapes ───────────────────────────────────────────────────────────────

export interface DirectoryChannelNode {
  readonly id: string;
  readonly platform: DirectoryPlatform;
  readonly handle: string;
  readonly name: string | null;
  readonly iconUrl: string | null;
  /** The platform page a tap opens. Built here so the client never has to. */
  readonly url: string;
  readonly categoryId: string | null;
  readonly subcategoryId: string | null;
  readonly sort: number;
}

export interface DirectorySubcategoryNode {
  readonly id: string;
  readonly categoryId: string;
  readonly name: string;
  readonly slug: string;
  readonly sort: number;
  readonly channels: DirectoryChannelNode[];
}

export interface DirectoryCategoryNode {
  readonly id: string;
  readonly name: string;
  readonly slug: string;
  readonly sort: number;
  readonly subcategories: DirectorySubcategoryNode[];
  /** Channels filed directly under the category, with no subcategory. */
  readonly channels: DirectoryChannelNode[];
}

/**
 * The whole directory as one snapshot.
 *
 * One object rather than per-category calls: a reader opening the screen wants
 * the list and its shape together, and a tab that fetched itself would flicker
 * as each landed. The whole thing is small — a curated list, not a catalogue.
 *
 * [unfiled] is the channels added before a category was chosen (or whose
 * category was later deleted). They are not an error state — the read still
 * returns them — and the client shows them under an "All" tab.
 */
export interface DirectorySnapshot {
  readonly categories: DirectoryCategoryNode[];
  readonly unfiled: DirectoryChannelNode[];
}

interface CategoryRow {
  id: string;
  name: string;
  slug: string;
  sort: number;
}
interface SubcategoryRow {
  id: string;
  category_id: string;
  name: string;
  slug: string;
  sort: number;
}
interface ChannelRow {
  id: string;
  platform: DirectoryPlatform;
  handle: string;
  name: string | null;
  icon_url: string | null;
  category_id: string | null;
  subcategory_id: string | null;
  sort: number;
}

// ── Normalisation ────────────────────────────────────────────────────────

/** The shape a handle may take once the "@" is off and the case is down. */
const HANDLE_SHAPE = /^[a-z0-9._-]{1,100}$/;

/**
 * A handle as it is stored: no leading "@", lowercased, trimmed.
 *
 * Normalised rather than kept as typed, because `@Foo`, `foo` and `FOO` are the
 * same channel and the unique index is what has to be able to see that.
 */
export function normalizeHandle(raw: string): string {
  const cleaned = raw.trim().replace(/^@+/, '').toLowerCase();
  if (!HANDLE_SHAPE.test(cleaned)) {
    throw badRequest(
      'invalid_handle',
      'A handle may contain letters, numbers, dots, dashes and underscores.'
    );
  }
  return cleaned;
}

/** A URL-safe key derived from a name. Never user-facing wording. */
function slugify(name: string): string {
  const base = name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 80);
  return base.length > 0 ? base : 'group';
}

/**
 * A slug no sibling already holds.
 *
 * A category's slug is the client's key for its tab, so a clash cannot be
 * allowed to make two categories the same tab — a suffix is appended until it
 * is free. Only ever consulted at CREATE time: renaming a category deliberately
 * keeps the slug it was born with.
 */
async function uniqueCategorySlug(database: Queryable, base: string): Promise<string> {
  let candidate = base;
  for (let n = 2; n <= 50; n += 1) {
    const rows = await database.query<{ id: string }>(
      'SELECT id FROM directory_categories WHERE slug = $1',
      [candidate]
    );
    if (rows.length === 0) return candidate;
    candidate = `${base}-${n}`;
  }
  return `${base}-${Date.now().toString(36)}`;
}

async function uniqueSubcategorySlug(
  database: Queryable,
  categoryId: string,
  base: string
): Promise<string> {
  let candidate = base;
  for (let n = 2; n <= 50; n += 1) {
    const rows = await database.query<{ id: string }>(
      'SELECT id FROM directory_subcategories WHERE category_id = $1 AND slug = $2',
      [categoryId, candidate]
    );
    if (rows.length === 0) return candidate;
    candidate = `${base}-${n}`;
  }
  return `${base}-${Date.now().toString(36)}`;
}

function toChannelNode(row: ChannelRow): DirectoryChannelNode {
  return {
    id: row.id,
    platform: row.platform,
    handle: row.handle,
    name: row.name,
    iconUrl: row.icon_url,
    url: channelUrl(row.platform, row.handle),
    categoryId: row.category_id,
    subcategoryId: row.subcategory_id,
    sort: row.sort,
  };
}

// ── Reads ────────────────────────────────────────────────────────────────

/**
 * The whole directory, assembled from three ordered queries.
 *
 * Three queries rather than a join, because a join over categories ×
 * subcategories × channels repeats every category once per channel and the
 * client would have to un-repeat it. The lists are tiny and bounded, so the
 * extra round trips are the cheap half of the trade.
 */
export async function listDirectory(database: Queryable): Promise<DirectorySnapshot> {
  const categories = await database.query<CategoryRow>(
    'SELECT id, name, slug, sort FROM directory_categories ORDER BY sort, name'
  );
  const subcategories = await database.query<SubcategoryRow>(
    'SELECT id, category_id, name, slug, sort FROM directory_subcategories ORDER BY sort, name'
  );
  const channels = await database.query<ChannelRow>(
    `SELECT id, platform, handle, name, icon_url, category_id, subcategory_id, sort
       FROM directory_channels
      ORDER BY sort, created_at`
  );

  const nodes = channels.map(toChannelNode);

  const subNodes = new Map<string, DirectorySubcategoryNode>();
  for (const sub of subcategories) {
    subNodes.set(sub.id, {
      id: sub.id,
      categoryId: sub.category_id,
      name: sub.name,
      slug: sub.slug,
      sort: sub.sort,
      channels: [],
    });
  }
  // A channel whose subcategory no longer exists falls back to its category
  // rather than disappearing: the row is still real, and a file that was
  // deleted must not silently take its channels with it.
  const subByChannel = new Map<string, string>();
  const categoryNodes: DirectoryCategoryNode[] = categories.map((category) => ({
    id: category.id,
    name: category.name,
    slug: category.slug,
    sort: category.sort,
    subcategories: [],
    channels: [],
  }));
  const byId = new Map(categoryNodes.map((node) => [node.id, node]));

  for (const channel of nodes) {
    const sub = channel.subcategoryId ? subNodes.get(channel.subcategoryId) : undefined;
    if (sub && sub.categoryId === channel.categoryId) {
      sub.channels.push(channel);
      subByChannel.set(channel.id, sub.id);
    } else {
      const category = channel.categoryId ? byId.get(channel.categoryId) : undefined;
      if (category) category.channels.push(channel);
    }
  }

  for (const sub of subcategories) {
    const parent = byId.get(sub.category_id);
    if (parent) parent.subcategories.push(subNodes.get(sub.id)!);
  }

  const unfiled = nodes.filter((channel) => {
    if (!channel.categoryId) return true;
    const category = byId.get(channel.categoryId);
    if (!category) return true;
    if (subByChannel.has(channel.id)) return false;
    return !category.channels.some((c) => c.id === channel.id);
  });

  return { categories: categoryNodes, unfiled };
}

// ── Category administration ──────────────────────────────────────────────

export async function createCategory(
  database: Queryable,
  name: string,
  sort: number
): Promise<DirectoryCategoryNode> {
  const slug = await uniqueCategorySlug(database, slugify(name));
  const rows = await database.query<CategoryRow>(
    `INSERT INTO directory_categories (name, slug, sort)
     VALUES ($1, $2, $3)
     RETURNING id, name, slug, sort`,
    [name.trim(), slug, sort]
  );
  const row = rows[0]!;
  return { id: row.id, name: row.name, slug: row.slug, sort: row.sort, subcategories: [], channels: [] };
}

export async function updateCategory(
  database: Queryable,
  id: string,
  patch: { name?: string; sort?: number }
): Promise<void> {
  const rows = await database.query<{ id: string }>(
    `UPDATE directory_categories
        SET name = COALESCE($2, name),
            sort = COALESCE($3, sort)
      WHERE id = $1
      RETURNING id`,
    [id, patch.name?.trim() ?? null, patch.sort ?? null]
  );
  if (rows.length === 0) throw notFound('category_not_found', 'That category does not exist.');
}

/** Removing a category unfiles its channels (FKs are SET NULL); it keeps them. */
export async function deleteCategory(database: Queryable, id: string): Promise<void> {
  const rows = await database.query<{ id: string }>(
    'DELETE FROM directory_categories WHERE id = $1 RETURNING id',
    [id]
  );
  if (rows.length === 0) throw notFound('category_not_found', 'That category does not exist.');
}

// ── Subcategory administration ───────────────────────────────────────────

async function requireCategory(database: Queryable, categoryId: string): Promise<void> {
  const rows = await database.query<{ id: string }>(
    'SELECT id FROM directory_categories WHERE id = $1',
    [categoryId]
  );
  if (rows.length === 0) throw notFound('category_not_found', 'That category does not exist.');
}

export async function createSubcategory(
  database: Queryable,
  categoryId: string,
  name: string,
  sort: number
): Promise<DirectorySubcategoryNode> {
  await requireCategory(database, categoryId);
  const slug = await uniqueSubcategorySlug(database, categoryId, slugify(name));
  const rows = await database.query<SubcategoryRow>(
    `INSERT INTO directory_subcategories (category_id, name, slug, sort)
     VALUES ($1, $2, $3, $4)
     RETURNING id, category_id, name, slug, sort`,
    [categoryId, name.trim(), slug, sort]
  );
  const row = rows[0]!;
  return {
    id: row.id,
    categoryId: row.category_id,
    name: row.name,
    slug: row.slug,
    sort: row.sort,
    channels: [],
  };
}

export async function updateSubcategory(
  database: Queryable,
  id: string,
  patch: { name?: string; sort?: number }
): Promise<void> {
  const rows = await database.query<{ id: string }>(
    `UPDATE directory_subcategories
        SET name = COALESCE($2, name),
            sort = COALESCE($3, sort)
      WHERE id = $1
      RETURNING id`,
    [id, patch.name?.trim() ?? null, patch.sort ?? null]
  );
  if (rows.length === 0) throw notFound('subcategory_not_found', 'That subcategory does not exist.');
}

export async function deleteSubcategory(database: Queryable, id: string): Promise<void> {
  const rows = await database.query<{ id: string }>(
    'DELETE FROM directory_subcategories WHERE id = $1 RETURNING id',
    [id]
  );
  if (rows.length === 0) throw notFound('subcategory_not_found', 'That subcategory does not exist.');
}

// ── Channel administration ───────────────────────────────────────────────

export interface ChannelInput {
  readonly platform: DirectoryPlatform;
  readonly handle: string;
  readonly name?: string | null;
  readonly iconUrl?: string | null;
  readonly categoryId?: string | null;
  readonly subcategoryId?: string | null;
  readonly sort?: number;
}

/**
 * Resolve the filing: a subcategory implies its category.
 *
 * A channel filed under a subcategory that belongs to a DIFFERENT category is
 * refused rather than silently corrected, because it means the client sent two
 * answers that disagree and guessing would hide the bug.
 */
async function resolveFiling(
  database: Queryable,
  categoryId: string | null,
  subcategoryId: string | null
): Promise<{ categoryId: string | null; subcategoryId: string | null }> {
  if (!subcategoryId) {
    if (categoryId) await requireCategory(database, categoryId);
    return { categoryId, subcategoryId: null };
  }

  const rows = await database.query<{ category_id: string }>(
    'SELECT category_id FROM directory_subcategories WHERE id = $1',
    [subcategoryId]
  );
  if (rows.length === 0) throw notFound('subcategory_not_found', 'That subcategory does not exist.');
  const owner = rows[0]!.category_id;
  if (categoryId && categoryId !== owner) {
    throw badRequest(
      'category_mismatch',
      'That subcategory belongs to a different category.'
    );
  }
  return { categoryId: owner, subcategoryId };
}

/**
 * Add a channel.
 *
 * The name and the icon are copied from the platform when the caller did not
 * supply them — best-effort, and a failure is not an error: a channel with a
 * handle and no name is still a working row. Enrichment never overwrites a value
 * the administrator typed.
 */
export async function createChannel(
  database: Queryable,
  input: ChannelInput,
  enricher: Enricher = fetchChannelProfile
): Promise<DirectoryChannelNode> {
  const handle = normalizeHandle(input.handle);
  const filing = await resolveFiling(
    database,
    input.categoryId ?? null,
    input.subcategoryId ?? null
  );

  let name = input.name?.trim() || null;
  let iconUrl = input.iconUrl?.trim() || null;
  if (!name || !iconUrl) {
    const fetched = await enricher(input.platform, handle);
    name = name ?? fetched.name;
    iconUrl = iconUrl ?? fetched.iconUrl;
  }

  const rows = await database.query<ChannelRow>(
    `INSERT INTO directory_channels
       (platform, handle, name, icon_url, category_id, subcategory_id, sort)
     VALUES ($1, $2, $3, $4, $5, $6, $7)
     ON CONFLICT (platform, handle) DO NOTHING
     RETURNING id, platform, handle, name, icon_url, category_id, subcategory_id, sort`,
    [input.platform, handle, name, iconUrl, filing.categoryId, filing.subcategoryId, input.sort ?? 0]
  );

  if (rows.length === 0) {
    // The unique index answered, so this is the one case that is genuinely a
    // clash rather than a validation failure.
    throw badRequest(
      'channel_exists',
      'That channel is already in the directory.'
    );
  }
  return toChannelNode(rows[0]!);
}

export async function updateChannel(
  database: Queryable,
  id: string,
  patch: {
    name?: string | null;
    iconUrl?: string | null;
    categoryId?: string | null;
    subcategoryId?: string | null;
    sort?: number;
  }
): Promise<DirectoryChannelNode> {
  const existing = await database.query<ChannelRow>(
    `SELECT id, platform, handle, name, icon_url, category_id, subcategory_id, sort
       FROM directory_channels WHERE id = $1`,
    [id]
  );
  if (existing.length === 0) throw notFound('channel_not_found', 'That channel does not exist.');

  const current = existing[0]!;
  const filingProvided = patch.categoryId !== undefined || patch.subcategoryId !== undefined;
  const filing = filingProvided
    ? await resolveFiling(
        database,
        patch.categoryId === undefined ? current.category_id : patch.categoryId,
        patch.subcategoryId === undefined ? current.subcategory_id : patch.subcategoryId
      )
    : { categoryId: current.category_id, subcategoryId: current.subcategory_id };

  const rows = await database.query<ChannelRow>(
    `UPDATE directory_channels
        SET name = $2,
            icon_url = $3,
            category_id = $4,
            subcategory_id = $5,
            sort = COALESCE($6, sort)
      WHERE id = $1
      RETURNING id, platform, handle, name, icon_url, category_id, subcategory_id, sort`,
    [
      id,
      patch.name === undefined ? current.name : patch.name?.trim() || null,
      patch.iconUrl === undefined ? current.icon_url : patch.iconUrl?.trim() || null,
      filing.categoryId,
      filing.subcategoryId,
      patch.sort ?? null,
    ]
  );
  return toChannelNode(rows[0]!);
}

/** Copy the channel's name and icon from the platform again. On demand only. */
export async function refreshChannel(
  database: Queryable,
  id: string,
  enricher: Enricher = fetchChannelProfile
): Promise<DirectoryChannelNode> {
  const rows = await database.query<ChannelRow>(
    `SELECT id, platform, handle, name, icon_url, category_id, subcategory_id, sort
       FROM directory_channels WHERE id = $1`,
    [id]
  );
  if (rows.length === 0) throw notFound('channel_not_found', 'That channel does not exist.');
  const current = rows[0]!;

  const fetched = await enricher(current.platform, current.handle);
  return updateChannel(database, id, {
    name: fetched.name ?? current.name,
    iconUrl: fetched.iconUrl ?? current.icon_url,
  });
}

export async function deleteChannel(database: Queryable, id: string): Promise<void> {
  const rows = await database.query<{ id: string }>(
    'DELETE FROM directory_channels WHERE id = $1 RETURNING id',
    [id]
  );
  if (rows.length === 0) throw notFound('channel_not_found', 'That channel does not exist.');
}
