import { Router } from 'express';
import { z } from 'zod';
import type { Queryable } from '../db.js';
import { parseBody, pathIdParam } from '../http/validate.js';
import { requireAdmin } from '../admin/routes.js';
import { DIRECTORY_PLATFORMS, type Enricher } from './enrich.js';
import {
  createCategory,
  createChannel,
  createSubcategory,
  deleteCategory,
  deleteChannel,
  deleteSubcategory,
  listDirectory,
  refreshChannel,
  updateCategory,
  updateChannel,
  updateSubcategory,
} from './service.js';

/**
 * Channel-directory management, mounted at `/admin/api/directory`.
 *
 * ## Super admins only, checked on the server
 *
 * Every route goes through [requireAdmin], which re-reads the administrator's
 * row and checks a `directory.*` permission. Only a `super_admin` holds those,
 * so the app can hide the entry point all it likes — the answer to a channel
 * administrator who calls the route anyway is a refusal, not a hidden button.
 *
 * ## It is a platform concern, not a channel one
 *
 * The directory is one list every reader sees, so it is the same kind of power as
 * an advertisement card and lives in its own module for the same reason: nothing
 * in here has to know that channels exist.
 *
 * ## Enrichment is injectable
 *
 * Adding a channel by handle copies its name and icon from the platform, which
 * means a network call. The enricher is a parameter so the route can be driven
 * end to end in a test against a stub — the rules that matter (normalisation,
 * filing, clashes) are ours, and requiring the platforms to be reachable to
 * verify them would mean they were never verified.
 */

const Platform = z.enum(DIRECTORY_PLATFORMS as [string, ...string[]]);

/** A handle as TYPED — normalised and validated by the service. */
const Handle = z.string().trim().min(1).max(120);

const CategoryBody = z
  .object({
    name: z.string().trim().min(1).max(80),
    sort: z.number().int().min(0).max(100_000).default(0),
  })
  .strict();

const CategoryPatch = z
  .object({
    name: z.string().trim().min(1).max(80).optional(),
    sort: z.number().int().min(0).max(100_000).optional(),
  })
  .strict();

const SubcategoryBody = z
  .object({
    categoryId: z.string().min(1).max(64),
    name: z.string().trim().min(1).max(80),
    sort: z.number().int().min(0).max(100_000).default(0),
  })
  .strict();

const SubcategoryPatch = z
  .object({
    name: z.string().trim().min(1).max(80).optional(),
    sort: z.number().int().min(0).max(100_000).optional(),
  })
  .strict();

const ChannelBody = z
  .object({
    platform: Platform,
    handle: Handle,
    name: z.string().trim().max(200).nullable().optional(),
    iconUrl: z.string().trim().max(1024).nullable().optional(),
    categoryId: z.string().min(1).max(64).nullable().optional(),
    subcategoryId: z.string().min(1).max(64).nullable().optional(),
    sort: z.number().int().min(0).max(100_000).default(0),
  })
  .strict();

const ChannelPatch = z
  .object({
    name: z.string().trim().max(200).nullable().optional(),
    iconUrl: z.string().trim().max(1024).nullable().optional(),
    categoryId: z.string().min(1).max(64).nullable().optional(),
    subcategoryId: z.string().min(1).max(64).nullable().optional(),
    sort: z.number().int().min(0).max(100_000).optional(),
  })
  .strict();

export function buildDirectoryAdminRouter(database: Queryable, enricher?: Enricher): Router {
  const router = Router();

  /** The whole directory, for the manager screen. */
  router.get('/', requireAdmin(database, 'directory.read'), async (_req, res) => {
    res.status(200).json(await listDirectory(database));
  });

  // ── Categories ─────────────────────────────────────────────────────────
  router.post('/categories', requireAdmin(database, 'directory.manage'), async (req, res) => {
    const body = parseBody(CategoryBody, req.body);
    res.status(201).json({ category: await createCategory(database, body.name, body.sort) });
  });

  router.patch('/categories/:id', requireAdmin(database, 'directory.manage'), async (req, res) => {
    const id = pathIdParam(req.params.id, 'invalid_category_id');
    const body = parseBody(CategoryPatch, req.body);
    await updateCategory(database, id, body);
    res.status(200).json(await listDirectory(database));
  });

  router.delete('/categories/:id', requireAdmin(database, 'directory.manage'), async (req, res) => {
    const id = pathIdParam(req.params.id, 'invalid_category_id');
    await deleteCategory(database, id);
    res.status(200).json({ ok: true });
  });

  // ── Subcategories ──────────────────────────────────────────────────────
  router.post('/subcategories', requireAdmin(database, 'directory.manage'), async (req, res) => {
    const body = parseBody(SubcategoryBody, req.body);
    res
      .status(201)
      .json({ subcategory: await createSubcategory(database, body.categoryId, body.name, body.sort) });
  });

  router.patch(
    '/subcategories/:id',
    requireAdmin(database, 'directory.manage'),
    async (req, res) => {
      const id = pathIdParam(req.params.id, 'invalid_subcategory_id');
      const body = parseBody(SubcategoryPatch, req.body);
      await updateSubcategory(database, id, body);
      res.status(200).json(await listDirectory(database));
    }
  );

  router.delete(
    '/subcategories/:id',
    requireAdmin(database, 'directory.manage'),
    async (req, res) => {
      const id = pathIdParam(req.params.id, 'invalid_subcategory_id');
      await deleteSubcategory(database, id);
      res.status(200).json({ ok: true });
    }
  );

  // ── Channels ───────────────────────────────────────────────────────────
  router.post('/channels', requireAdmin(database, 'directory.manage'), async (req, res) => {
    const body = parseBody(ChannelBody, req.body);
    const channel = await createChannel(
      database,
      {
        platform: body.platform as (typeof DIRECTORY_PLATFORMS)[number],
        handle: body.handle,
        name: body.name ?? null,
        iconUrl: body.iconUrl ?? null,
        categoryId: body.categoryId ?? null,
        subcategoryId: body.subcategoryId ?? null,
        sort: body.sort,
      },
      enricher
    );
    res.status(201).json({ channel });
  });

  router.patch('/channels/:id', requireAdmin(database, 'directory.manage'), async (req, res) => {
    const id = pathIdParam(req.params.id, 'invalid_channel_id');
    const body = parseBody(ChannelPatch, req.body);
    res.status(200).json({ channel: await updateChannel(database, id, body) });
  });

  /** Re-copy the name and icon from the platform, on demand. */
  router.post('/channels/:id/refresh', requireAdmin(database, 'directory.manage'), async (req, res) => {
    const id = pathIdParam(req.params.id, 'invalid_channel_id');
    res.status(200).json({ channel: await refreshChannel(database, id, enricher) });
  });

  router.delete('/channels/:id', requireAdmin(database, 'directory.manage'), async (req, res) => {
    const id = pathIdParam(req.params.id, 'invalid_channel_id');
    await deleteChannel(database, id);
    res.status(200).json({ ok: true });
  });

  return router;
}
