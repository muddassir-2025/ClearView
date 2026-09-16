import { Router } from 'express';
import { z } from 'zod';
import { env } from '../env.js';
import type { Queryable } from '../db.js';
import { badRequest } from '../http/errors.js';
import { parseBody, pathIdParam } from '../http/validate.js';
import { rateLimit, type FixedWindowRateLimiter, type RateLimitConfig } from '../http/rateLimit.js';
import { authOf, requireAuth } from '../auth/middleware.js';
import {
  blockChannel,
  createChannel,
  discoverChannels,
  followChannel,
  getChannel,
  getChannelBySlug,
  listCategories,
  listFollowing,
  listManagedChannels,
  markChannelRead,
  setChannelNotifications,
  unblockChannel,
  unfollowChannel,
  updateChannel,
} from './service.js';

/**
 * Channels and discovery (§5, §6, §7, §12), mounted at /api/v1/channels and
 * /api/v1/discover.
 *
 * **Every route requires a session.** This is stricter than the product it is
 * modelled on and is a deliberate reading of §38: with no anonymous surface
 * there is no unauthenticated endpoint to enumerate channels, owners or
 * activity through. Public read-only channel pages would reverse this and are
 * an open decision (documented in docs/GOODPOST_PLAN.md §5.3), not something to
 * introduce by accident here.
 *
 * Writes carry the `write` rate-limit rule in addition to the global one.
 * `WRITE_RATE_LIMIT_MAX` had been declared since M0 without a consumer, so
 * these are the first routes where that configuration actually does something.
 *
 * Handlers are `async` and throw `ApiError`s; Express 5 forwards a rejected
 * handler promise to the central error handler, so no handler needs a
 * try/catch and none can swallow an error by omitting one.
 */

const CreateChannelSchema = z.object({
  name: z.string().min(2).max(env.MAX_CHANNEL_NAME_LENGTH),
  description: z.string().max(env.MAX_CHANNEL_DESCRIPTION_LENGTH).optional(),
  categorySlug: z.string().min(1).max(60).optional(),
  countryCode: z.string().length(2).optional(),
});

/**
 * Every field optional, because a patch is a partial update. An explicitly
 * `null` description or category clears it, while omitting the key leaves it
 * alone — the service relies on that distinction to serve both "rename" and
 * "remove the description" from one endpoint.
 */
const UpdateChannelSchema = z.object({
  name: z.string().min(2).max(env.MAX_CHANNEL_NAME_LENGTH).optional(),
  description: z.string().max(env.MAX_CHANNEL_DESCRIPTION_LENGTH).nullable().optional(),
  categorySlug: z.string().min(1).max(60).nullable().optional(),
  countryCode: z.string().length(2).nullable().optional(),
  allowFollowerMessages: z.boolean().optional(),
});

const NotificationsSchema = z.object({
  /** §17: false mutes the channel. */
  enabled: z.boolean(),
});

/**
 * Discovery parameters.
 *
 * `limit` and `cursor` stay strings: the page size is clamped by
 * `parsePageSize`, and a cursor is opaque. `sort` is an enum rather than a
 * defaulted string so an unknown value is a 400 — silently falling back would
 * hide a client bug behind plausible-looking results.
 *
 * Express parses `?sort=a&sort=b` into an array, which fails these schemas.
 * That is intended: a repeated parameter is a client mistake, and coercing it
 * would mean guessing which of the two the caller meant.
 */
const DiscoverQuerySchema = z.object({
  q: z.string().max(100).optional(),
  category: z.string().max(60).optional(),
  country: z.string().length(2).optional(),
  sort: z.enum(['popular', 'active', 'new']).optional(),
  limit: z.string().max(10).optional(),
  cursor: z.string().max(512).optional(),
});

const PageQuerySchema = z.object({
  limit: z.string().max(10).optional(),
  cursor: z.string().max(512).optional(),
});

function channelIdParam(raw: unknown): string {
  return pathIdParam(raw, 'invalid_channel_id');
}

export function buildChannelsRouter(
  database: Queryable,
  limiter: FixedWindowRateLimiter,
  rateLimits: RateLimitConfig
): Router {
  const router = Router();
  const requireSession = requireAuth(database);
  const write = rateLimit(limiter, rateLimits.write);

  router.use(requireSession);

  // ── Static paths first ────────────────────────────────────────────────
  // Express matches in registration order, so `/categories`, `/following` and
  // `/mine` MUST be declared before `/:channelId`. Reversed, a request for
  // /channels/categories would be routed as a channel id and 404 as
  // `channel_not_found` — a confusing failure that looks like a missing
  // category rather than a routing mistake.
  router.get('/categories', async (_req, res) => {
    res.status(200).json({ categories: await listCategories(database) });
  });

  /** §4 Channels view: everything the viewer follows. */
  router.get('/following', async (req, res) => {
    const auth = authOf(req);
    const query = parseBody(PageQuerySchema, req.query);
    res.status(200).json(await listFollowing(database, auth.userId, query));
  });

  /** §7 management view: channels the viewer owns or helps run. */
  router.get('/mine', async (req, res) => {
    const auth = authOf(req);
    res.status(200).json({ channels: await listManagedChannels(database, auth.userId) });
  });

  /** §6 create a channel. */
  router.post('/', write, async (req, res) => {
    const auth = authOf(req);
    const body = parseBody(CreateChannelSchema, req.body);
    res.status(201).json({ channel: await createChannel(database, auth.userId, body) });
  });

  // ── Parameterised paths ───────────────────────────────────────────────

  /**
   * §6 resolve a share link: `clearview://goodpost/channel/<slug>`.
   *
   * Registered ahead of `/:channelId` for readability rather than necessity —
   * the two patterns have different segment counts, so a slug could never be
   * captured as a channel id.
   *
   * A dead or malformed slug is a 404, not a validation error: to the person
   * holding the link those are the same outcome, and a 400 would distinguish
   * "this slug does not exist" from "this is not a slug" for anyone probing.
   */
  router.get('/by-slug/:slug', async (req, res) => {
    const auth = authOf(req);
    const slug = req.params.slug ?? '';
    res.status(200).json({ channel: await getChannelBySlug(database, slug, auth.userId) });
  });

  router.get('/:channelId', async (req, res) => {
    const auth = authOf(req);
    const channelId = channelIdParam(req.params.channelId);
    res.status(200).json({ channel: await getChannel(database, channelId, auth.userId) });
  });

  /** §7 edit name / description / category / country / follower messages. */
  router.patch('/:channelId', write, async (req, res) => {
    const auth = authOf(req);
    const channelId = channelIdParam(req.params.channelId);
    const body = parseBody(UpdateChannelSchema, req.body);

    // A patch with no fields would still run the authorization check and write
    // an identical row. Rejecting it makes a client bug visible instead of
    // producing a silent no-op that looks like a successful save.
    if (Object.values(body).every((value) => value === undefined)) {
      throw badRequest('empty_update', 'Provide at least one field to change.');
    }

    res.status(200).json({ channel: await updateChannel(database, auth.userId, channelId, body) });
  });

  router.post('/:channelId/follow', write, async (req, res) => {
    const auth = authOf(req);
    const channelId = channelIdParam(req.params.channelId);
    res.status(200).json(await followChannel(database, auth.userId, channelId));
  });

  router.delete('/:channelId/follow', write, async (req, res) => {
    const auth = authOf(req);
    const channelId = channelIdParam(req.params.channelId);
    res.status(200).json(await unfollowChannel(database, auth.userId, channelId));
  });

  /** §17 mute / unmute. */
  router.put('/:channelId/notifications', write, async (req, res) => {
    const auth = authOf(req);
    const channelId = channelIdParam(req.params.channelId);
    const body = parseBody(NotificationsSchema, req.body);
    res
      .status(200)
      .json(await setChannelNotifications(database, auth.userId, channelId, body.enabled));
  });

  /** §4 clear the unread flag. A POST because it changes state. */
  router.post('/:channelId/read', write, async (req, res) => {
    const auth = authOf(req);
    const channelId = channelIdParam(req.params.channelId);
    await markChannelRead(database, auth.userId, channelId);
    res.status(200).json({ ok: true });
  });

  /** §12 block / unblock. Blocking also ends the follow, in one transaction. */
  router.post('/:channelId/block', write, async (req, res) => {
    const auth = authOf(req);
    const channelId = channelIdParam(req.params.channelId);
    await blockChannel(database, auth.userId, channelId);
    res.status(200).json({ blocked: true });
  });

  router.delete('/:channelId/block', write, async (req, res) => {
    const auth = authOf(req);
    const channelId = channelIdParam(req.params.channelId);
    await unblockChannel(database, auth.userId, channelId);
    res.status(200).json({ blocked: false });
  });

  return router;
}

/**
 * Discovery (§5), mounted at /api/v1/discover.
 *
 * Reads only, so it is covered by the global limit. Kept as its own router
 * because the surface is conceptually separate from a specific channel — it is
 * the "find something to follow" entry point — and because it is the one place
 * a future ranking change would be made.
 */
export function buildDiscoverRouter(database: Queryable): Router {
  const router = Router();
  router.use(requireAuth(database));

  router.get('/channels', async (req, res) => {
    const auth = authOf(req);
    const query = parseBody(DiscoverQuerySchema, req.query);
    res.status(200).json(await discoverChannels(database, auth.userId, query));
  });

  return router;
}
