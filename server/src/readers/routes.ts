import { Router, type Request } from 'express';
import { z } from 'zod';
import type { Queryable } from '../db.js';
import { unauthorized } from '../http/errors.js';
import { parseBody } from '../http/validate.js';
import { rateLimit, type FixedWindowRateLimiter, type RateLimitConfig } from '../http/rateLimit.js';
import type { ObjectStore } from '../media/store.js';
import type { IdentityVerifier, VerifiedIdentity } from '../identity/verifier.js';
import {
  ensureReader,
  followChannel,
  listFollowedChannels,
  markChannelRead,
  setChannelMuted,
  unfollowChannel,
  type ReaderRef,
} from './service.js';

/**
 * A reader's own routes (§3–§6), mounted at `/api/v1/readers`.
 *
 * The only authenticated part of the reader API, and the split is the point: a
 * reader reads `/api/v1/channels` and `/api/v1/posts` with no token at all, and
 * only the state that is *theirs* — what they follow, what they have read, what
 * is muted — needs one (§3). Nothing here is required to view Good Post.
 *
 * ## Why these paths say `me`
 *
 * No route takes a reader id, and none can: the reader is always the caller,
 * resolved from the verified token. A path parameter for the reader would be a
 * parameter somebody would eventually pass another reader's value to, and the
 * only thing that would stop it is a check this design does not have to write.
 *
 * ## Authorization is the token, and only the token
 *
 * Every route below goes through [requireReader] first, so there is one
 * implementation of "who is calling" and one place a mistake could be made.
 * A channel the reader follows is checked by scoping the statement to their own
 * reader id, not by reading a client-supplied owner and comparing it.
 */

/** A reader identified by a verified token. */
export interface ReaderContext {
  readonly identity: VerifiedIdentity;
  readonly reader: ReaderRef;
}

/**
 * Read the token, verify it, and resolve the reader row.
 *
 * The scheme is parsed case-insensitively because RFC 7235 defines it that way
 * and some HTTP clients lowercase it; rejecting `bearer` would fail a request
 * that is entirely correct.
 *
 * Failures are deliberately one code. A missing header, a malformed one, an
 * expired token and a token signed by a different Firebase project all answer
 * `invalid_token`: the client's action is the same (sign in again), and
 * distinguishing them would describe which part of a forged token was wrong.
 * "Verification is not configured" is the exception and is a 503, because
 * retrying after a deploy is exactly the right response to that one.
 */
async function requireReader(
  req: Request,
  database: Queryable,
  verifier: IdentityVerifier
): Promise<ReaderContext> {
  const header = req.get('authorization') ?? '';
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  const token = match?.[1]?.trim();
  if (token === undefined || token === '') throw unauthorized('invalid_token', 'No bearer token.');

  const identity = await verifier.verify(token);
  return { identity, reader: await ensureReader(database, identity) };
}

const MuteSchema = z.object({ muted: z.boolean() });
const PageQuerySchema = z.object({
  limit: z.string().max(10).optional(),
  cursor: z.string().max(512).optional(),
});

/**
 * A path parameter as a string.
 *
 * Express types a parameter as `string | string[]` because a route may be
 * declared with a pattern that produces repeats. These routes are all plain, so
 * the array case cannot occur — but it is collapsed rather than asserted, so an
 * identifier that did somehow arrive as a list reaches the lookup as one
 * string and fails as "no such channel" instead of reaching a query as an
 * array.
 */
function pathParam(req: Request, name: string): string {
  const value = req.params[name];
  return Array.isArray(value) ? (value[0] ?? '') : (value ?? '');
}

export function buildReadersRouter(
  database: Queryable,
  store: ObjectStore,
  verifier: IdentityVerifier,
  limiter: FixedWindowRateLimiter,
  rateLimits: RateLimitConfig
): Router {
  const router = Router();

  // Sign-in state changes are writes, so they take the write rule: they are the
  // routes a stuck client retries in a loop, and each one touches a row.
  const write = rateLimit(limiter, rateLimits.write);

  /**
   * Who the token belongs to (§3).
   *
   * Deliberately thin, and it returns no uid: the client does not need one (it
   * is the key, not a display value) and the uid is a bearer-adjacent identifier
   * that should not be travelling in responses or landing in logs (§30). What
   * the app actually needs from this is "my token works, and am I a creator or
   * an anonymous reader", which is what it answers.
   */
  router.get('/me', async (req, res) => {
    const { identity } = await requireReader(req, database, verifier);
    res.status(200).json({
      reader: { anonymous: identity.anonymous, email: identity.email },
    });
  });

  /** The home screen: the channels this reader follows (§4, §5). */
  router.get('/me/following', async (req, res) => {
    const { reader } = await requireReader(req, database, verifier);
    const query = parseBody(PageQuerySchema, req.query);
    res.status(200).json(await listFollowedChannels(database, store, reader.id, query));
  });

  /**
   * Follow a channel (§4).
   *
   * POST rather than PUT: the channel is named in the path and the body is
   * empty, but the operation creates a relationship on first use and is
   * idempotent on repeat — which is exactly a POST to a collection of follows.
   */
  router.post('/me/following/:channelIdOrSlug', write, async (req, res) => {
    const { reader } = await requireReader(req, database, verifier);
    const idOrSlug = pathParam(req, 'channelIdOrSlug');
    res.status(200).json({ follow: await followChannel(database, reader.id, idOrSlug) });
  });

  /** Unfollow a channel (§4). */
  router.delete('/me/following/:channelIdOrSlug', write, async (req, res) => {
    const { reader } = await requireReader(req, database, verifier);
    const idOrSlug = pathParam(req, 'channelIdOrSlug');
    res.status(200).json({ follow: await unfollowChannel(database, reader.id, idOrSlug) });
  });

  /**
   * Mute or unmute a channel's notifications (§6).
   *
   * PATCH on the follow itself, because that is what is being changed — only
   * one field of a relationship that already exists.
   */
  router.patch('/me/following/:channelIdOrSlug', write, async (req, res) => {
    const { reader } = await requireReader(req, database, verifier);
    const idOrSlug = pathParam(req, 'channelIdOrSlug');
    const body = parseBody(MuteSchema, req.body);
    res.status(200).json({
      follow: await setChannelMuted(database, reader.id, idOrSlug, body.muted),
    });
  });

  /**
   * Clear a channel's unread badge (§5): the reader has opened it.
   *
   * A POST to a `read` marker rather than a PATCH of a timestamp, so the client
   * never has to know a time — the server's clock is the only one that can
   * decide whether a post was published before or after the reader looked.
   */
  router.post('/me/following/:channelIdOrSlug/read', write, async (req, res) => {
    const { reader } = await requireReader(req, database, verifier);
    const idOrSlug = pathParam(req, 'channelIdOrSlug');
    res.status(200).json({ follow: await markChannelRead(database, reader.id, idOrSlug) });
  });

  return router;
}
