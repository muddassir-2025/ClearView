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
  requireActiveChannel,
  setChannelMuted,
  unfollowChannel,
  type ReaderRef,
} from './service.js';
import { registerDevice, unregisterDevice } from '../notifications/service.js';
import {
  clearPostReaction,
  isReactionEmoji,
  listReaderReactions,
  setPostReaction,
  REACTION_EMOJI,
} from '../reactions/service.js';

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

/**
 * A device registration (§8).
 *
 * The token is length-bounded rather than pattern-matched: FCM mints it, its
 * shape is Firebase's business, and a regex here would be a rule this server
 * invented about somebody else's format. The bounds keep a body from being a
 * megabyte of nothing.
 *
 * `platform` is an enum with one member because a second client would be the
 * reason the column exists — and a free-text value would silently become two
 * spellings of "android".
 */
const DeviceSchema = z.object({
  token: z.string().min(16).max(4096),
  platform: z.enum(['android']).default('android'),
});
const PageQuerySchema = z.object({
  limit: z.string().max(10).optional(),
  cursor: z.string().max(512).optional(),
});

/**
 * A reaction (§9): one of the six emoji the product offers, and nothing else.
 *
 * Validated against the same list the database constrains, so a client that
 * sends a seventh emoji is told it is wrong rather than being refused by a
 * constraint violation that would arrive as an opaque 500.
 */
const ReactionSchema = z.object({
  emoji: z.string().max(8).refine(isReactionEmoji, { message: 'unknown_emoji' }),
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
   * Where to reach this reader's phone (§8).
   *
   * The token is the DEVICE's, not the reader's, and it is registered by the
   * device that holds it: the reader id comes from the verified token, so a
   * request cannot put somebody else's phone on somebody else's account. That is
   * also why this lives on the reader surface rather than the admin one — a
   * creator who follows two channels is a reader of them, and hears about their
   * updates like anybody else.
   *
   * The answer echoes the token back and nothing else. A JSON body rather than
   * an empty 204 for a practical reason: every other route on this surface
   * answers JSON, and one route that sometimes answers nothing is one more
   * shape for a client to get wrong for no gain.
   */
  router.post('/me/devices', write, async (req, res) => {
    const { reader } = await requireReader(req, database, verifier);
    const body = parseBody(DeviceSchema, req.body);
    await registerDevice(database, reader.id, { token: body.token, platform: body.platform });
    res.status(200).json({ device: { token: body.token, platform: body.platform } });
  });

  /**
   * Stop reaching this device (§8): notifications were turned off, or the app
   * was reinstalled.
   *
   * Scoped to the caller in SQL, so naming another reader's token deletes
   * nothing. Answers the same thing whether or not a row was there — "that token
   * was not registered to you" is a statement about another account's device,
   * and no client behaviour could differ on it.
   */
  router.delete('/me/devices/:token', write, async (req, res) => {
    const { reader } = await requireReader(req, database, verifier);
    const token = pathParam(req, 'token');
    if (token.length >= 16 && token.length <= 4096) {
      await unregisterDevice(database, reader.id, token);
    }
    res.status(200).json({ device: { token, platform: 'android', registered: false } });
  });

  /**
   * The vocabulary (§9).
   *
   * Published rather than hard-coded in the client, so the app cannot offer an
   * emoji the server would refuse. It needs no token: it is the same six for
   * everybody, and a client that is about to sign in still has to draw them.
   */
  router.get('/reactions', (_req, res) => {
    res.status(200).json({ emoji: REACTION_EMOJI });
  });

  /**
   * This reader's reactions in one channel (§9).
   *
   * A channel rather than "all of them": the reader's reactions across the whole
   * product are a record of what they have read, and the only place that record
   * is needed is a channel they are looking at. Scoped to the caller in SQL - no
   * route here takes a reader id.
   */
  router.get('/me/reactions/:channelIdOrSlug', async (req, res) => {
    const { reader } = await requireReader(req, database, verifier);
    const idOrSlug = pathParam(req, 'channelIdOrSlug');
    // Resolved through the same lookup a follow uses, so a channel that does not
    // exist, is suspended or was deleted answers here exactly as it does there.
    const channel = await requireActiveChannel(database, idOrSlug);
    res.status(200).json({ reactions: await listReaderReactions(database, reader.id, channel.id) });
  });

  /**
   * React to a post, or change the reaction (§9).
   *
   * PUT on the reader's reaction to one post: the path names everything that
   * identifies it, the body is the value being set, and a repeat is the same
   * state as the first — the definition of idempotent. A POST would suggest a
   * second reaction could accumulate, which is the one thing this cannot do.
   */
  router.put('/me/reactions/:postId', write, async (req, res) => {
    const { reader } = await requireReader(req, database, verifier);
    const body = parseBody(ReactionSchema, req.body);
    const postId = pathParam(req, 'postId');
    res
      .status(200)
      .json({ reaction: await setPostReaction(database, reader.id, postId, body.emoji) });
  });

  /**
   * Take the reaction back off (§9).
   *
   * DELETE of the reaction itself, so the path is the post and there is no body
   * to get wrong. The emoji the client believed was there may travel as a query
   * parameter, and only so the answer can carry the count of THAT emoji - the
   * number the card is currently showing.
   */
  router.delete('/me/reactions/:postId', write, async (req, res) => {
    const { reader } = await requireReader(req, database, verifier);
    const postId = pathParam(req, 'postId');
    const raw = req.query['emoji'];
    const emoji = typeof raw === 'string' && raw.length <= 8 ? raw : null;
    res
      .status(200)
      .json({ reaction: await clearPostReaction(database, reader.id, postId, emoji) });
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
