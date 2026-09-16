import { Router } from 'express';
import { z } from 'zod';
import type { Queryable } from '../db.js';
import { parseBody, pathIdParam } from '../http/validate.js';
import { rateLimit, type FixedWindowRateLimiter, type RateLimitConfig } from '../http/rateLimit.js';
import { authOf, requireAuth } from '../auth/middleware.js';
import { fanOutMessageNotification, fanOutReplyNotification } from '../notifications/service.js';
import type { PushSender } from '../notifications/push.js';
import {
  REPORT_REASONS,
  REPORT_TARGET_TYPES,
  blockUser,
  createReport,
  getConversation,
  listBlockedUsers,
  listChannelConversations,
  listMessages,
  listNoticesForUser,
  listOwnConversations,
  listOwnReports,
  loadPublicProfile,
  markNoticesRead,
  sendMessage,
  setConversationBlocked,
  startConversation,
  unblockUser,
  type ReportReason,
  type ReportTargetType,
} from './service.js';

/**
 * Moderation and private messaging routes (§12, §16, §18).
 *
 * Three groups, one router, one `requireAuth` per route — the same reasoning as
 * the engagement router: a self-contained router cannot be mounted somewhere
 * and silently lose authentication, and route-level middleware keeps a request
 * to another router's path from paying for a session check it does not need.
 *
 * Mounted at `/api/v1` BEFORE the channel router, because
 * `/channels/:channelId/conversations` is a channel-scoped path. Express
 * matches in registration order, so the channel router's blanket
 * `requireSession` never sees it.
 */

const ReportSchema = z.object({
  /**
   * Shape only. The enum membership is checked in the service so the failure is
   * `invalid_reason` / `invalid_target_type` — codes the Android client words —
   * rather than zod's generic `invalid_request`.
   */
  targetType: z.string().min(1).max(32),
  targetId: z.string().min(1).max(64),
  reason: z.string().min(1).max(32),
  details: z.string().max(2000).optional(),
});

const MessageSchema = z.object({
  body: z.string().min(1).max(4000),
});

const BlockSchema = z.object({ blocked: z.boolean() });

/** §26's inbox: which official notices the caller has read. */
const NoticeReadSchema = z.object({
  ids: z.array(z.string().min(1).max(64)).max(100),
});

const PageSchema = z.object({
  limit: z.string().max(10).optional(),
  cursor: z.string().max(512).optional(),
});

export function buildModerationRouter(
  database: Queryable,
  limiter: FixedWindowRateLimiter,
  rateLimits: RateLimitConfig,
  push: PushSender
): Router {
  const router = Router();
  const requireSession = requireAuth(database);
  const write = rateLimit(limiter, rateLimits.write);
  // Filing reports gets its own, tighter budget; §18's queue is a shared
  // resource and one account must not be able to fill it.
  const reportLimit = rateLimit(limiter, rateLimits.report);

  /** §38: another user, as anyone may see them. Never email or phone. */
  router.get('/users/:userId', requireSession, async (req, res) => {
    const userId = pathIdParam(req.params.userId, 'invalid_user_id');
    res.status(200).json({ user: await loadPublicProfile(database, userId) });
  });

  // ── Reports (§18) ───────────────────────────────────────────────────

  router.post('/reports', requireSession, reportLimit, async (req, res) => {
    const auth = authOf(req);
    const body = parseBody(ReportSchema, req.body);
    const report = await createReport(database, auth.userId, {
      targetType: body.targetType as ReportTargetType,
      targetId: body.targetId,
      reason: body.reason as ReportReason,
      details: body.details,
    });
    res.status(201).json({ report });
  });

  /** The caller's own reports, so a reporter can see what happened (§18). */
  router.get('/reports/mine', requireSession, async (req, res) => {
    const auth = authOf(req);
    res.status(200).json({ items: await listOwnReports(database, auth.userId) });
  });

  // ── User blocks (§12) ───────────────────────────────────────────────

  router.get('/blocks', requireSession, async (req, res) => {
    const auth = authOf(req);
    res.status(200).json({ items: await listBlockedUsers(database, auth.userId) });
  });

  router.post('/blocks/:userId', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const userId = pathIdParam(req.params.userId, 'invalid_user_id');
    await blockUser(database, auth.userId, userId);
    res.status(200).json({ blocked: true });
  });

  router.delete('/blocks/:userId', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const userId = pathIdParam(req.params.userId, 'invalid_user_id');
    await unblockUser(database, auth.userId, userId);
    res.status(200).json({ blocked: false });
  });

  // ── Official notices (§26) ──────────────────────────────────────────

  /**
   * Personal and channel notices sent by the platform (§26).
   *
   * Read here rather than through the admin API deliberately: the recipient is
   * a normal Good Post user, and their notice list is theirs alone.
   */
  router.get('/notices', requireSession, async (req, res) => {
    const auth = authOf(req);
    res.status(200).json({ items: await listNoticesForUser(database, auth.userId) });
  });

  router.post('/notices/read', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const body = parseBody(NoticeReadSchema, req.body);
    res.status(200).json({ marked: await markNoticesRead(database, auth.userId, body.ids) });
  });

  // ── Private follower messages (§16) ─────────────────────────────────

  /** Open (or find) the caller's conversation with a channel. */
  router.post('/channels/:channelId/conversations', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    const result = await startConversation(database, auth.userId, channelId);
    res.status(result.created ? 201 : 200).json({
      conversation: await getConversation(database, auth.userId, result.conversationId),
    });
  });

  /** The channel's inbox: admin side of §16. */
  router.get('/channels/:channelId/conversations', requireSession, async (req, res) => {
    const auth = authOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    res
      .status(200)
      .json({ items: await listChannelConversations(database, auth.userId, channelId) });
  });

  /** Conversations the caller opened as a follower. */
  router.get('/conversations', requireSession, async (req, res) => {
    const auth = authOf(req);
    res.status(200).json({ items: await listOwnConversations(database, auth.userId) });
  });

  router.get('/conversations/:conversationId/messages', requireSession, async (req, res) => {
    const auth = authOf(req);
    const conversationId = pathIdParam(req.params.conversationId, 'invalid_conversation_id');
    const query = parseBody(PageSchema, req.query);

    // The page first, then the conversation: reading it is what marks the other
    // side's messages read, so fetching the summary first would hand the client
    // an unread count that its own response has just invalidated.
    const page = await listMessages(database, auth.userId, conversationId, query);
    const conversation = await getConversation(database, auth.userId, conversationId);
    res.status(200).json({ conversation, ...page });
  });

  router.post('/conversations/:conversationId/messages', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const conversationId = pathIdParam(req.params.conversationId, 'invalid_conversation_id');
    const body = parseBody(MessageSchema, req.body);

    const message = await sendMessage(database, auth.userId, conversationId, body.body);

    // §17's fan-out, and the DIRECTION depends on who wrote: a follower's
    // question notifies the channel's admins; the channel's answer notifies that
    // one follower. The conversation is read back for the channel's name and id
    // rather than guessed from the message, since the sender's own sends cannot
    // tell a follower id from a channel id.
    //
    // Outside the send's own transaction and never fatal: the message exists and
    // the sender must not be told it failed because a push provider was down.
    try {
      const conversation = await getConversation(database, auth.userId, conversationId);
      if (message.fromAdmin) {
        await fanOutReplyNotification(database, push, {
          conversationId,
          messageId: message.id,
          channelId: conversation.channelId,
          channelName: conversation.channelName,
          preview: message.body,
        });
      } else {
        await fanOutMessageNotification(database, push, {
          channelId: conversation.channelId,
          conversationId,
          messageId: message.id,
          channelName: conversation.channelName,
          preview: message.body,
          senderId: auth.userId,
        });
      }
    } catch (err) {
      console.error('[notifications] message fan-out failed:', (err as Error).message);
    }

    res.status(201).json({ message });
  });

  router.post('/conversations/:conversationId/read', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const conversationId = pathIdParam(req.params.conversationId, 'invalid_conversation_id');
    // `listMessages` already marks read; this exists so a client that has the
    // thread on screen can acknowledge without re-fetching it.
    await listMessages(database, auth.userId, conversationId, { limit: '1' });
    res
      .status(200)
      .json({ conversation: await getConversation(database, auth.userId, conversationId) });
  });

  router.post('/conversations/:conversationId/block', requireSession, write, async (req, res) => {
    const auth = authOf(req);
    const conversationId = pathIdParam(req.params.conversationId, 'invalid_conversation_id');
    const body = parseBody(BlockSchema, req.body);
    res.status(200).json({
      conversation: await setConversationBlocked(
        database,
        auth.userId,
        conversationId,
        body.blocked
      ),
    });
  });

  return router;
}

/** Re-exported so a route test can assert the closed set without re-listing it. */
export { REPORT_REASONS, REPORT_TARGET_TYPES };
