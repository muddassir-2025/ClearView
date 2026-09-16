import { Router } from 'express';
import { z } from 'zod';
import type { Queryable } from '../db.js';
import { badRequest } from '../http/errors.js';
import { parseBody, pathIdParam } from '../http/validate.js';
import { rateLimit, type FixedWindowRateLimiter, type RateLimitConfig } from '../http/rateLimit.js';
import { authOf, requireAuth } from '../auth/middleware.js';
import { REACTIONS, channelAnalytics, recordView, setReaction, voteInPoll } from './service.js';

/**
 * Engagement routes (§13, §14, §15).
 *
 * Mounted at `/api/v1` rather than under a single prefix, because the paths it
 * owns are naturally spread: a reaction and a view belong to a post, a vote
 * belongs to a poll, and analytics belong to a channel. Each is registered as
 * the full path it answers so the router stays self-contained — the alternative
 * (three tiny routers) would multiply the places `requireAuth` can be forgotten.
 *
 * This router is mounted BEFORE the channel and post routers, which matters for
 * one reason: `POST /channels/:channelId/analytics`-adjacent paths and
 * `/posts/:postId/reactions` would otherwise be seen first by a router whose
 * blanket `requireSession` would verify the session a second time. Express
 * matches in registration order, so a request is answered here without ever
 * entering the other routers. The suite pins both directions.
 *
 * Reads (`GET …/analytics`) are covered by the global rule only; every
 * mutation takes the `write` rule, sharing one limiter instance with the rest
 * of the API.
 */

const ReactionSchema = z.object({
  /**
   * The reaction, or null to take it back. `.null()` is spelled out rather than
   * made optional so that "clear" is an explicit request — an omitted key is a
   * client bug, and treating it as a clear would silently delete a reaction.
   *
   * The value is `z.string()` here and validated against `REACTIONS` in the
   * service, so the error code is the wordable `invalid_reaction` rather than
   * zod's generic `invalid_request`.
   */
  reaction: z.string().min(1).max(16).nullable(),
});

const VoteSchema = z.object({
  optionIds: z.array(z.string().min(1).max(64)).min(1).max(64),
});

/**
 * A chart window, capped at four characters so an absurd value is rejected
 * before it reaches the query. The upper bound itself is clamped in the
 * service (`ANALYTICS_WINDOW_DAYS` is the default, 365 the ceiling).
 */
const AnalyticsQuerySchema = z.object({
  days: z.string().max(4).optional(),
});

export function buildEngagementRouter(
  database: Queryable,
  limiter: FixedWindowRateLimiter,
  rateLimits: RateLimitConfig
): Router {
  const router = Router();
  const requireSession = requireAuth(database);
  const write = rateLimit(limiter, rateLimits.write);

  /** §13 react, change a reaction, or clear it with `reaction: null`. */
  router.post('/posts/:postId/reactions', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const postId = pathIdParam(req.params.postId, 'invalid_post_id');
    const body = parseBody(ReactionSchema, req.body);

    if (body.reaction !== null && !REACTIONS.includes(body.reaction as never)) {
      throw badRequest('invalid_reaction', 'That is not a reaction this app offers.');
    }

    const state = await setReaction(
      database,
      auth.userId,
      postId,
      body.reaction as (typeof REACTIONS)[number] | null
    );
    res.status(200).json(state);
  });

  /**
   * §13 clear a reaction, as its own verb.
   *
   * Present because deleting a reaction is what the action IS — a client that
   * follows HTTP semantics should not have to send a body to say nothing.
   */
  router.delete('/posts/:postId/reactions', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const postId = pathIdParam(req.params.postId, 'invalid_post_id');
    res.status(200).json(await setReaction(database, auth.userId, postId, null));
  });

  /**
   * §15 record a view. A POST rather than a GET deliberately: a GET would be
   * prefetched, cached and retried by every intermediary, turning one look into
   * several — and it would be reachable from a plain link.
   */
  router.post('/posts/:postId/views', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const postId = pathIdParam(req.params.postId, 'invalid_post_id');
    res.status(200).json(await recordView(database, auth.userId, postId));
  });

  /** §14 vote, or change a vote. Aggregate results come back, never voters. */
  router.post('/polls/:pollId/votes', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const pollId = pathIdParam(req.params.pollId, 'invalid_poll_id');
    const body = parseBody(VoteSchema, req.body);
    res.status(200).json({ poll: await voteInPoll(database, auth.userId, pollId, body.optionIds) });
  });

  /** §15 channel analytics. Owner/editor only, enforced in the service. */
  router.get('/channels/:channelId/analytics', requireSession, async (req, res) => {
    const auth = authOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    const query = parseBody(AnalyticsQuerySchema, req.query);
    const days = query.days === undefined ? undefined : Number(query.days);
    res
      .status(200)
      .json({ analytics: await channelAnalytics(database, auth.userId, channelId, days) });
  });

  return router;
}
