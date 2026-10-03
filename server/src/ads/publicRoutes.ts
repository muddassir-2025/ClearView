import { Router } from 'express';
import type { Queryable } from '../db.js';
import type { ObjectStore } from '../media/store.js';
import { listActiveAdvertisements, type AdPlacement } from './service.js';

/**
 * The public advertisement read (§12), mounted at `/api/v1/ads`.
 *
 * Anonymous and read-only, like the rest of `/api/v1`: a reader needs no account
 * to be shown a card, and there is no route here that could change one.
 *
 * The placement is a query parameter rather than a path so the two surfaces
 * share one endpoint and one response shape. Anything that is not
 * `channels`/`explore` is treated as `channels` — the safe default — rather than
 * erroring, because a wrong-but-harmless card is a better outcome for a reader
 * than a failed screen.
 *
 * What is returned is decided ENTIRELY by the server: enabled, started, not
 * expired, and placed here. An expired card disappears on its own with no
 * cleanup, and a disabled one is never sent.
 */
export function buildAdsPublicRouter(database: Queryable, store: ObjectStore): Router {
  const router = Router();

  router.get('/', async (req, res) => {
    const raw = typeof req.query.placement === 'string' ? req.query.placement : '';
    const placement: AdPlacement = raw === 'explore' ? 'explore' : 'channels';
    res.status(200).json({ ads: await listActiveAdvertisements(database, store, placement) });
  });

  return router;
}
