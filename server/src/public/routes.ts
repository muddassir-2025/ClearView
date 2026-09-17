import { Router } from 'express';
import { z } from 'zod';
import type { Queryable } from '../db.js';
import { parseBody } from '../http/validate.js';
import type { ObjectStore } from '../media/store.js';
import { getPublicChannel, listCategories, listPublicChannels } from '../channels/service.js';
import {
  getPublicPost,
  listPublicChannelMedia,
  listPublicChannelPosts,
  recordPostViews,
} from './service.js';

/**
 * The public read API (§24, §26), mounted at the ROOT of `/api/v1`.
 *
 * **No route here requires a token, and none may ever accept one as an
 * authority.** Good Post's readers are anonymous: they open the tab and read.
 * The surface is read-only by construction — there is no write route in this
 * file to authorize — so the worst a caller can do with it is read something
 * that was published to the world on purpose.
 *
 * These paths are the product's own: `/api/v1/channels`, `/api/v1/posts`. They
 * used to sit behind a `/public` prefix, which was there to keep them from
 * colliding with the authenticated channel surface that has since been deleted.
 * With no second surface left, the prefix would only be a longer URL for a
 * reader to fetch — and the reader's first impression is this one (§26).
 *
 * What is left of the API is therefore two clearly different prefixes:
 *
 *   /api/v1/...      everything a reader may fetch, anonymously, read-only
 *   /admin/api/...   everything an administrator may change, bearer token only
 *
 * Rate limiting is NOT applied here: `/api/v1` already carries the global rule,
 * mounted once in `app.ts`, and a second limiter would either double-count the
 * same request or need its own namespace to describe the same allowance twice.
 *
 * Errors are `{ error: '<machine_code>' }`, as everywhere else; the Android
 * client branches on the code and never on prose.
 */

/**
 * Query parameters are validated for SHAPE only.
 *
 * `limit` and `cursor` stay strings — the page size is clamped by
 * `parsePageSize` and a cursor is opaque. `sort` is an enum rather than a
 * defaulted string so an unknown value is a 400: silently falling back would
 * hide a client bug behind plausible-looking results.
 */
const ChannelQuerySchema = z.object({
  q: z.string().max(100).optional(),
  category: z.string().max(60).optional(),
  sort: z.enum(['recent', 'name']).optional(),
  limit: z.string().max(10).optional(),
  cursor: z.string().max(512).optional(),
});

const PageQuerySchema = z.object({
  limit: z.string().max(10).optional(),
  cursor: z.string().max(512).optional(),
});

/**
 * A channel's history, optionally narrowed by a search term (§9).
 *
 * `q` is capped at the length the service will actually use, so a client that
 * pastes a document into the search box is not refused for it — the term is
 * truncated where it is used, and the shape check only keeps the request from
 * being unbounded.
 */
const PostQuerySchema = PageQuerySchema.extend({
  q: z.string().max(200).optional(),
});

/**
 * A batch of posts a reader displayed (§9).
 *
 * Bounded at both ends: an empty batch is a client that has nothing to report
 * (a 400, because a request that can only do nothing is a bug), and the upper
 * bound is twice the largest page, so a screen can report everything it drew
 * without the endpoint being usable as a bulk updater.
 */
const PostViewsSchema = z.object({
  ids: z.array(z.string().uuid()).min(1).max(60),
});

export function buildPublicRouter(database: Queryable, store: ObjectStore): Router {
  const router = Router();

  /**
   * Explore and search (§6, §7).
   *
   * One endpoint for both: browsing is a search with an empty term, and the
   * client's Explore screen renders the same list either way. A separate browse
   * route would be the same query with the same filters minus one optional
   * clause.
   */
  router.get('/channels', async (req, res) => {
    const query = parseBody(ChannelQuerySchema, req.query);
    res.status(200).json(await listPublicChannels(database, store, query));
  });

  /** Discover categories an Explore filter can offer. */
  router.get('/categories', async (_req, res) => {
    res.status(200).json({ categories: await listCategories(database) });
  });

  // ── Parameterised paths ───────────────────────────────────────────────
  // Registered after the static ones above, because Express matches in
  // registration order: `/channels/categories` reversed would be read as a
  // channel id and 404 as `channel_not_found`.

  /** One channel, by uuid or by the slug a share link carries (§6). */
  router.get('/channels/:channelIdOrSlug', async (req, res) => {
    const idOrSlug = req.params.channelIdOrSlug ?? '';
    res.status(200).json({ channel: await getPublicChannel(database, store, idOrSlug) });
  });

  /**
   * A channel's history, newest first, optionally filtered by a search term
   * (§9).
   *
   * One endpoint for the feed and for search inside a channel, deliberately.
   * "Show me this channel's posts" and "show me this channel's posts about X"
   * are the same read with one optional predicate, and a separate search route
   * would be a second place for the visibility rule, the payload shape and the
   * paging contract to drift out of step with this one.
   */
  router.get('/channels/:channelIdOrSlug/posts', async (req, res) => {
    const idOrSlug = req.params.channelIdOrSlug ?? '';
    const query = parseBody(PostQuerySchema, req.query);
    res.status(200).json(await listPublicChannelPosts(database, store, idOrSlug, query));
  });

  /** A channel's images and videos, for the profile screen's gallery (§13). */
  router.get('/channels/:channelIdOrSlug/media', async (req, res) => {
    const idOrSlug = req.params.channelIdOrSlug ?? '';
    const query = parseBody(PageQuerySchema, req.query);
    res.status(200).json(await listPublicChannelMedia(database, store, idOrSlug, query));
  });

  /**
   * Report the posts a reader has just seen (§9).
   *
   * The only WRITE on the reader surface, and it needs no token: a view count is
   * a fact about the post, not about the reader — there is nothing here that
   * says WHO read anything, which is why the reader identity is deliberately not
   * consulted even though it is available.
   *
   * A batch, not one request per post: a feed is up to thirty rows, and thirty
   * writes per screen open would be thirty round trips for a number nobody
   * acts on. The client sends what it actually drew, once.
   *
   * It answers 200 with the count it changed, and never 404 for an id that was
   * not found: a post that was removed between the read and the report is the
   * normal way this race resolves, not a client error (§23). A channel that does
   * not exist — or is suspended — still 404s, because that is a different claim.
   */
  router.post('/channels/:channelIdOrSlug/posts/views', async (req, res) => {
    const idOrSlug = req.params.channelIdOrSlug ?? '';
    const body = parseBody(PostViewsSchema, req.body);
    const counted = await recordPostViews(database, idOrSlug, body.ids);
    res.status(200).json({ counted });
  });

  /** One post, with the channel it came from (§9). */
  router.get('/posts/:postId', async (req, res) => {
    const postId = req.params.postId ?? '';
    res.status(200).json({ post: await getPublicPost(database, store, postId) });
  });

  return router;
}
