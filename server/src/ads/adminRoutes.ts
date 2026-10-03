import { Router, type Request } from 'express';
import { z } from 'zod';
import type { Queryable } from '../db.js';
import { forbidden } from '../http/errors.js';
import { parseBody, pathIdParam } from '../http/validate.js';
import type { ObjectStore } from '../media/store.js';
import { removeObjectQuietly } from '../media/service.js';
import { requireAdmin } from '../admin/routes.js';
import {
  createAdvertisement,
  deleteAdvertisement,
  getAdvertisement,
  listActiveAdvertisements,
  listAdsForAdmin,
  updateAdvertisement,
  type AdvertisementInput,
} from './service.js';

/**
 * Advertisement management (§10–§16), mounted at `/admin/api/ads`.
 *
 * ## Authorization is the SERVER's, not a hidden button
 *
 * Every route goes through [requireAdmin], which re-reads the administrator's
 * row on each request and checks the `ads.*` permission against the compiled
 * matrix. Only a `super_admin` holds those permissions, so a channel
 * administrator — and, of course, any unauthenticated caller — is refused here
 * regardless of what the app chooses to draw.
 *
 * ## It is a separate router on purpose
 *
 * Advertisement cards are a platform concern, not a channel one, and the channel
 * admin surface has no place for them. Building this beside `admin/routes.ts`
 * rather than inside it keeps the two role models from bleeding into each other:
 * this file only ever asks "is this a super admin?".
 *
 * ## The image is an upload, not a URL
 *
 * An image card references a media row produced by the EXISTING upload handshake
 * (`/admin/api/media/...`) and passes its `mediaId` here. The service claims it,
 * so a client can never point a card at an arbitrary URL — which is the whole
 * reason an advertisement image is uploadable at all.
 */

interface AdminRequest extends Request {
  admin?: { readonly adminId: string };
}

function actorOf(req: Request): { adminId: string } {
  const admin = (req as AdminRequest).admin;
  if (!admin) throw forbidden('admin_forbidden', 'Administrator access is required.');
  return { adminId: admin.adminId };
}

/** `https://…` or `mailto:…`, matching the database's own backstop. */
const TARGET_URL = /^(https?:\/\/\S+|mailto:\S+)$/i;

const AdBodySchema = z
  .object({
    contentType: z.enum(['image', 'text']),
    text: z.string().trim().max(1000).nullable().optional(),
    targetUrl: z
      .string()
      .trim()
      .max(2048)
      .refine((v) => TARGET_URL.test(v), 'Target URL must be an http(s) or mailto link.')
      .nullable()
      .optional(),
    showInChannels: z.boolean().default(true),
    showInExplore: z.boolean().default(true),
    enabled: z.boolean().default(true),
    /** ISO instants. Omitted start means \"now\". */
    startsAt: z
      .string()
      .refine((v) => !Number.isNaN(Date.parse(v)), 'Must be an ISO date-time.')
      .nullable()
      .optional(),
    expiresAt: z
      .string()
      .refine((v) => !Number.isNaN(Date.parse(v)), 'Must be an ISO date-time.')
      .nullable()
      .optional(),
    priority: z.number().int().min(0).max(100_000).default(100),
    /** A text card's ink. `#RRGGBB` only — the painter cannot read anything else. */
    textColor: z
      .string()
      .regex(/^#[0-9A-Fa-f]{6}$/, 'Colour must be a #RRGGBB value.')
      .default('#E9EDEF'),
    /** How an image card's picture fills it. */
    imageFit: z.enum(['cover', 'contain']).default('cover'),
    /** The crop's centre, 0..1 on each axis. */
    imageFocusX: z.number().min(0).max(1).default(0.5),
    imageFocusY: z.number().min(0).max(1).default(0.5),
    /** A confirmed image upload to attach (required for an image card). */
    mediaId: z.string().uuid().nullable().optional(),
  })
  .strict();

type AdBody = z.infer<typeof AdBodySchema>;

function toInput(body: AdBody): AdvertisementInput {
  return {
    contentType: body.contentType,
    text: body.text ?? null,
    targetUrl: body.targetUrl ?? null,
    showInChannels: body.showInChannels,
    showInExplore: body.showInExplore,
    enabled: body.enabled,
    startsAt: body.startsAt ? new Date(body.startsAt) : null,
    expiresAt: body.expiresAt ? new Date(body.expiresAt) : null,
    priority: body.priority,
    textColor: body.textColor,
    imageFit: body.imageFit,
    imageFocusX: body.imageFocusX,
    imageFocusY: body.imageFocusY,
    mediaId: body.mediaId ?? null,
  };
}

export function buildAdsAdminRouter(database: Queryable, store: ObjectStore): Router {
  const router = Router();

  /** Every card, active or not, so the admin can manage all of them. */
  router.get('/', requireAdmin(database, 'ads.read'), async (_req, res) => {
    res.status(200).json({ ads: await listAdsForAdmin(database, store) });
  });

  /** One card, for the editor. */
  router.get('/:adId', requireAdmin(database, 'ads.read'), async (req, res) => {
    const adId = pathIdParam(req.params.adId, 'invalid_ad_id');
    const ad = await getAdvertisement(database, store, adId);
    if (!ad) {
      res.status(404).json({ error: 'ad_not_found' });
      return;
    }
    res.status(200).json({ ad });
  });

  /** Create a card. */
  router.post('/', requireAdmin(database, 'ads.manage'), async (req, res) => {
    const body = parseBody(AdBodySchema, req.body);
    const ad = await createAdvertisement(database, store, actorOf(req).adminId, toInput(body));
    res.status(201).json({ ad });
  });

  /** Edit a card. */
  router.patch('/:adId', requireAdmin(database, 'ads.manage'), async (req, res) => {
    const adId = pathIdParam(req.params.adId, 'invalid_ad_id');
    const body = parseBody(AdBodySchema, req.body);
    const { ad, removedObjectKey } = await updateAdvertisement(
      database,
      store,
      actorOf(req).adminId,
      adId,
      toInput(body)
    );
    // The replaced image is deleted AFTER the commit; a failure here is an
    // unreferenced object, never a card pointing at nothing.
    await removeObjectQuietly(store, removedObjectKey);
    res.status(200).json({ ad });
  });

  /** Delete a card. */
  router.delete('/:adId', requireAdmin(database, 'ads.manage'), async (req, res) => {
    const adId = pathIdParam(req.params.adId, 'invalid_ad_id');
    const objectKey = await deleteAdvertisement(database, adId);
    await removeObjectQuietly(store, objectKey);
    res.status(200).json({ ok: true });
  });

  /**
   * A preview of the cards as a reader would see them for one placement (§14).
   * Active-row filtering only, so the editor can show exactly what will appear.
   */
  router.get('/preview/:placement', requireAdmin(database, 'ads.read'), async (req, res) => {
    const placement = req.params.placement === 'explore' ? 'explore' : 'channels';
    res.status(200).json({ ads: await listActiveAdvertisements(database, store, placement) });
  });

  return router;
}
