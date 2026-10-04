import { Router } from 'express';
import type { Queryable } from '../db.js';
import { listDirectory } from './service.js';

/**
 * The public channel directory read, mounted at `/api/v1/directory`.
 *
 * Anonymous and read-only, like the rest of `/api/v1`: the directory is the same
 * for every reader, so there is nothing to authenticate and no route here that
 * could change it. A phone fetches this once, caches it, and re-fetches when the
 * reader asks — there is no polling and no per-reader state.
 *
 * The whole list is one response. It is a curated set a person maintains, not a
 * catalogue, so it stays small enough that pagination would be more machinery
 * than content.
 */
export function buildDirectoryPublicRouter(database: Queryable): Router {
  const router = Router();

  router.get('/', async (_req, res) => {
    res.status(200).json(await listDirectory(database));
  });

  return router;
}
