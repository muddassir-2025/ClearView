import { Router } from 'express';
import { z } from 'zod';
import type { Queryable } from '../db.js';
import { badRequest } from '../http/errors.js';
import { parseBody, pathIdParam } from '../http/validate.js';
import { rateLimit, type FixedWindowRateLimiter, type RateLimitConfig } from '../http/rateLimit.js';
import { authOf, requireAuth } from '../auth/middleware.js';
import type { ObjectStore } from '../media/store.js';
import { deletePost, listChannelPosts, listFeed, publishPost, updatePost } from './service.js';

/**
 * Posts (§8), mounted at `/api/v1/channels/:channelId/posts` and
 * `/api/v1/posts`.
 *
 * The channel-scoped routes live in their own router, mounted on the same
 * `/api/v1/channels` prefix as M2's channel router rather than nested inside
 * it, so this router stays self-contained and cannot be mounted somewhere and
 * silently lose authentication. Express matches in registration order, and no
 * M2 route matches a two-segment `/x/posts` path, so nothing is swallowed — a
 * case the suite pins down explicitly.
 *
 * `requireAuth` is attached PER ROUTE here rather than to the router.
 * Deliberately: a `router.use(...)` on this router would run for every request
 * under `/api/v1/channels`, including the channel routes that belong to the
 * other router, and every one of those would then verify the same session
 * twice. Route-level middleware only runs on a match, so a channel request
 * never pays for this router at all.
 *
 * The `write` rate-limit rule applies to every mutation, sharing one limiter
 * instance with the channel routes so a single bounded bucket structure backs
 * all of them.
 *
 * The schemas check SHAPE only. Semantic limits — text length, media count,
 * link validity — are enforced in the service, because they own the error codes
 * the Android client words (`text_too_long`, `too_many_media`, `invalid_link`),
 * and a zod `.max()` here would replace them with a generic `invalid_request`.
 */

const CreatePostSchema = z.object({
  body: z.string().optional(),
  linkUrl: z.string().optional(),
  linkTitle: z.string().optional(),
  /** Ids from the upload endpoints, in the order they should be displayed. */
  mediaIds: z.array(z.string().min(1).max(64)).optional(),
});

/**
 * A patch is a partial update, so every field is optional. `null` clears a
 * field and an omitted key leaves it alone — the same convention M2's channel
 * patch uses.
 */
const UpdatePostSchema = z.object({
  body: z.string().nullable().optional(),
  linkUrl: z.string().nullable().optional(),
  linkTitle: z.string().nullable().optional(),
});

const PostListQuerySchema = z.object({
  limit: z.string().max(10).optional(),
  cursor: z.string().max(512).optional(),
});

/**
 * Channel history and publishing (§8).
 *
 * Mounted at `/api/v1/channels/:channelId/posts`.
 */
export function buildChannelPostsRouter(
  database: Queryable,
  store: ObjectStore,
  limiter: FixedWindowRateLimiter,
  rateLimits: RateLimitConfig
): Router {
  const router = Router();
  const requireSession = requireAuth(database);
  const write = rateLimit(limiter, rateLimits.write);

  /** §8 channel history, newest first. */
  router.get('/:channelId/posts', requireSession, async (req, res) => {
    const auth = authOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    const query = parseBody(PostListQuerySchema, req.query);
    res.status(200).json(await listChannelPosts(database, store, auth.userId, channelId, query));
  });

  /** §8 publish. */
  router.post('/:channelId/posts', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    const body = parseBody(CreatePostSchema, req.body);
    res
      .status(201)
      .json({ post: await publishPost(database, store, auth.userId, channelId, body) });
  });

  /** §7 edit the text of a post that is still inside its edit window. */
  router.patch('/:channelId/posts/:postId', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    const postId = pathIdParam(req.params.postId, 'invalid_post_id');
    const patch = parseBody(UpdatePostSchema, req.body);

    // A patch that changes nothing would still satisfy the edit window and
    // write an identical row, making a client bug look like a successful save.
    if (Object.values(patch).every((value) => value === undefined)) {
      throw badRequest('empty_update', 'Provide at least one field to change.');
    }

    res
      .status(200)
      .json({ post: await updatePost(database, store, auth.userId, channelId, postId, patch) });
  });

  /** §7 remove a post. Soft: §25 needs removal to stay reversible. */
  router.delete('/:channelId/posts/:postId', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    const postId = pathIdParam(req.params.postId, 'invalid_post_id');
    await deletePost(database, auth.userId, channelId, postId);
    res.status(200).json({ deleted: true });
  });

  return router;
}

/**
 * The aggregated feed (§4 Posts view).
 *
 * Mounted at `/api/v1/posts`. Deliberately its own router: it is the one
 * surface that spans every channel the viewer follows, so it belongs to
 * neither a channel nor a post.
 */
export function buildPostsRouter(database: Queryable, store: ObjectStore): Router {
  const router = Router();
  // No limiter parameter: the feed is read-only and `/api/v1` already applies
  // the global rule to it. A write added here should take one, rather than
  // inherit a rule that was wired for a route it no longer matches.

  router.use(requireAuth(database));

  router.get('/feed', async (req, res) => {
    const auth = authOf(req);
    const query = parseBody(PostListQuerySchema, req.query);
    res.status(200).json(await listFeed(database, store, auth.userId, query));
  });

  return router;
}
