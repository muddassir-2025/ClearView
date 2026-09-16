import { Router } from 'express';
import { z } from 'zod';
import type { Queryable } from '../db.js';
import { parseBody, pathIdParam } from '../http/validate.js';
import { rateLimit, type FixedWindowRateLimiter, type RateLimitConfig } from '../http/rateLimit.js';
import { authOf, requireAuth } from '../auth/middleware.js';
import type { ObjectStore } from './store.js';
import { confirmMediaUpload, mediaDownloadUrl, requestMediaUpload } from './service.js';

/**
 * Media (§9, §10), mounted at `/api/v1/media`.
 *
 * Three steps, in the order a composer uses them:
 *
 *   POST /uploads                     → a presigned URL to PUT one file to
 *   POST /uploads/:mediaId/confirm    → "it is there", verified by a HEAD
 *   GET  /:mediaId/url                → a fresh presigned read URL, for display
 *                                       or for the manual download of §10
 *
 * Every route requires a session. No AWS credential reaches a client at any
 * point: the only thing that crosses this boundary is a signed URL scoped to a
 * single object, a single method and a short expiry.
 *
 * The upload and confirm steps carry the `write` rate-limit rule. They are the
 * two that cost storage and an AWS call, so they are the ones a client
 * retrying in a loop must not be able to hammer.
 *
 * `byteSize` is validated for shape here; the configured ceiling, the content
 * type allow-list and the decision that this deployment can store anything at
 * all live in the service, so the client gets a code it can word
 * (`media_too_large`, `unsupported_media_type`, `media_unavailable`) instead of
 * a generic `invalid_request`.
 */

const RequestUploadSchema = z.object({
  contentType: z.string().min(3).max(120),
  byteSize: z.number().int().positive(),
  /** Optional hints for layout; nothing depends on them (§9). */
  width: z.number().int().positive().optional(),
  height: z.number().int().positive().optional(),
  durationMs: z.number().int().positive().optional(),
});

export function buildMediaRouter(
  database: Queryable,
  store: ObjectStore,
  limiter: FixedWindowRateLimiter,
  rateLimits: RateLimitConfig
): Router {
  const router = Router();
  const write = rateLimit(limiter, rateLimits.write);

  router.use(requireAuth(database));

  // ── Static paths first ────────────────────────────────────────────────
  // `/uploads` is declared before `/:mediaId/url` so neither can be captured as
  // the other's parameter. They differ in segment count today, which makes the
  // order cosmetic — but it is the order that keeps it that way.
  router.post('/uploads', write, async (req, res) => {
    const auth = authOf(req);
    const body = parseBody(RequestUploadSchema, req.body);
    res.status(201).json({ upload: await requestMediaUpload(database, auth.userId, store, body) });
  });

  router.post('/uploads/:mediaId/confirm', write, async (req, res) => {
    const auth = authOf(req);
    const mediaId = pathIdParam(req.params.mediaId, 'invalid_media_id');
    res.status(200).json({ media: await confirmMediaUpload(database, auth.userId, store, mediaId) });
  });

  router.get('/:mediaId/url', async (req, res) => {
    const auth = authOf(req);
    const mediaId = pathIdParam(req.params.mediaId, 'invalid_media_id');
    res.status(200).json(await mediaDownloadUrl(database, auth.userId, store, mediaId));
  });

  return router;
}
