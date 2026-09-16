import { Router } from 'express';
import { z } from 'zod';
import type { Queryable } from '../db.js';
import { parseBody } from '../http/validate.js';
import { rateLimit, type FixedWindowRateLimiter, type RateLimitConfig } from '../http/rateLimit.js';
import { authOf, requireAuth } from '../auth/middleware.js';
import {
  listNotifications,
  markNotificationsRead,
  registerDevice,
  unregisterDevice,
} from './service.js';

/**
 * Notification endpoints (§17), mounted at `/api/v1`.
 *
 * Three routes, and the reason each exists:
 *
 *  * `/devices` — the client tells the server where to push. Registration moves
 *    a token between accounts rather than duplicating it, so a signed-out
 *    device stops receiving the previous account's notifications.
 *  * `/notifications` — the durable inbox. It works with push switched off,
 *    which is what makes notifications a feature rather than a delivery
 *    mechanism.
 *  * `/notifications/read` — the badge. Clearing everything is the default;
 *    clearing specific ids is offered so a client that showed one card does not
 *    silently mark the rest read.
 */

const DeviceSchema = z.object({
  token: z.string().min(10).max(400),
  platform: z.enum(['android', 'ios', 'web']).default('android'),
});

const ReadSchema = z.object({
  /** Empty or absent means "everything in my inbox". */
  ids: z.array(z.string().min(1).max(64)).max(200).optional(),
});

const PageSchema = z.object({
  limit: z.string().max(10).optional(),
});

export function buildNotificationsRouter(
  database: Queryable,
  limiter: FixedWindowRateLimiter,
  rateLimits: RateLimitConfig
): Router {
  const router = Router();
  const requireSession = requireAuth(database);
  const write = rateLimit(limiter, rateLimits.write);

  router.get('/notifications', requireSession, async (req, res) => {
    const auth = authOf(req);
    const query = parseBody(PageSchema, req.query);
    res.status(200).json(await listNotifications(database, auth.userId, query));
  });

  router.post('/notifications/read', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const body = parseBody(ReadSchema, req.body);
    res.status(200).json({ marked: await markNotificationsRead(database, auth.userId, body.ids ?? []) });
  });

  /** Register or refresh this device's push token, for the signed-in account. */
  router.post('/devices', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const body = parseBody(DeviceSchema, req.body);
    res.status(200).json(await registerDevice(database, auth.userId, body));
  });

  /**
   * Unregister on sign-out.
   *
   * A DELETE with the token in the body would be unusual HTTP; this takes the
   * token as a path segment, which is what lets a client unregister during a
   * sign-out that has already discarded the rest of its state.
   */
  router.delete('/devices/:token', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const token = typeof req.params.token === 'string' ? req.params.token : '';
    await unregisterDevice(database, auth.userId, token);
    res.status(200).json({ unregistered: true });
  });

  return router;
}
