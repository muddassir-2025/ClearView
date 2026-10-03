import { Router, type Request } from 'express';
import { z } from 'zod';
import type { Queryable } from '../db.js';
import { forbidden } from '../http/errors.js';
import { parseBody, pathIdParam } from '../http/validate.js';
import { requireAdmin } from '../admin/routes.js';
import {
  addGlobalChannel,
  addGlobalKeyword,
  deleteGlobalChannel,
  deleteGlobalKeyword,
  listGlobalChannels,
  listGlobalKeywords,
  listSubmissions,
  reviewSubmission,
  setGlobalChannelEnabled,
  setGlobalKeywordEnabled,
} from './service.js';

/**
 * Brain Rot repository management, mounted at `/admin/api/brainrot`.
 *
 * ## Authorization is the server's
 *
 * Every route goes through [requireAdmin], which re-reads the administrator's
 * row on each request and checks the permission against the compiled matrix.
 * The `brainrot.*` permissions are held by `super_admin` only, so a channel
 * administrator — and of course an unauthenticated caller — is refused here
 * regardless of what the app chooses to draw.
 *
 * ## Why this is a separate router
 *
 * The global repository is a platform concern, not a channel one. Building it
 * beside `admin/routes.ts` rather than inside it keeps the two role models from
 * bleeding into each other: this file only ever asks \"is this a super admin?\".
 *
 * ## The review queue is the only path from a suggestion to a rule
 *
 * A user's suggestion is inert until [reviewSubmission] approves it, and that
 * call writes the rule and marks the submission in one transaction. There is no
 * route here that creates a rule FROM a submission implicitly, so \"a single
 * accidental submission affected everyone\" is not a state this API can reach.
 */

interface AdminRequest extends Request {
  admin?: { readonly adminId: string };
}

function actorOf(req: Request): { adminId: string } {
  const admin = (req as AdminRequest).admin;
  if (!admin) throw forbidden('admin_forbidden', 'Administrator access is required.');
  return { adminId: admin.adminId };
}

const KeywordBodySchema = z
  .object({
    keyword: z.string().trim().min(1).max(80),
    reason: z.string().trim().max(500).nullable().optional(),
  })
  .strict();

const ChannelBodySchema = z
  .object({
    handle: z.string().trim().min(1).max(120),
    displayName: z.string().trim().max(200).nullable().optional(),
    reason: z.string().trim().max(500).nullable().optional(),
  })
  .strict();

const EnabledBodySchema = z.object({ enabled: z.boolean() }).strict();

const ReviewBodySchema = z
  .object({ decision: z.enum(['approved', 'rejected']) })
  .strict();

const QueueQuerySchema = z.object({
  status: z.enum(['pending', 'approved', 'rejected', 'all']).optional(),
});

export function buildBrainRotAdminRouter(database: Queryable): Router {
  const router = Router();

  // ── Global keywords ───────────────────────────────────────────────────

  /** Every keyword, enabled or not, so the operator can manage all of them. */
  router.get('/keywords', requireAdmin(database, 'brainrot.read'), async (_req, res) => {
    res.status(200).json({ keywords: await listGlobalKeywords(database, { includeDisabled: true }) });
  });

  router.post('/keywords', requireAdmin(database, 'brainrot.manage'), async (req, res) => {
    const body = parseBody(KeywordBodySchema, req.body);
    const keyword = await addGlobalKeyword(database, body.keyword, body.reason ?? null);
    res.status(201).json({ keyword });
  });

  router.post('/keywords/:keywordId/status', requireAdmin(database, 'brainrot.manage'), async (req, res) => {
    const id = pathIdParam(req.params.keywordId, 'invalid_keyword_id');
    const body = parseBody(EnabledBodySchema, req.body);
    await setGlobalKeywordEnabled(database, id, body.enabled);
    res.status(200).json({ ok: true });
  });

  router.delete('/keywords/:keywordId', requireAdmin(database, 'brainrot.manage'), async (req, res) => {
    const id = pathIdParam(req.params.keywordId, 'invalid_keyword_id');
    await deleteGlobalKeyword(database, id);
    res.status(200).json({ ok: true });
  });

  // ── Global channels ───────────────────────────────────────────────────

  router.get('/channels', requireAdmin(database, 'brainrot.read'), async (_req, res) => {
    res.status(200).json({ channels: await listGlobalChannels(database, { includeDisabled: true }) });
  });

  router.post('/channels', requireAdmin(database, 'brainrot.manage'), async (req, res) => {
    const body = parseBody(ChannelBodySchema, req.body);
    const channel = await addGlobalChannel(
      database,
      body.handle,
      body.displayName ?? null,
      body.reason ?? null
    );
    res.status(201).json({ channel });
  });

  router.post('/channels/:channelId/status', requireAdmin(database, 'brainrot.manage'), async (req, res) => {
    const id = pathIdParam(req.params.channelId, 'invalid_channel_id');
    const body = parseBody(EnabledBodySchema, req.body);
    await setGlobalChannelEnabled(database, id, body.enabled);
    res.status(200).json({ ok: true });
  });

  router.delete('/channels/:channelId', requireAdmin(database, 'brainrot.manage'), async (req, res) => {
    const id = pathIdParam(req.params.channelId, 'invalid_channel_id');
    await deleteGlobalChannel(database, id);
    res.status(200).json({ ok: true });
  });

  // ── The review queue ──────────────────────────────────────────────────

  /**
   * Community suggestions, oldest first.
   *
   * Each row carries its report count, so a reviewer can weigh \"one person
   * asked for this\" against \"two hundred devices asked for this\" in the same
   * screen they approve it from.
   */
  router.get('/submissions', requireAdmin(database, 'brainrot.read'), async (req, res) => {
    const query = parseBody(QueueQuerySchema, req.query);
    res.status(200).json({ submissions: await listSubmissions(database, query.status ?? 'pending') });
  });

  /**
   * Approve or reject a suggestion.
   *
   * Approving is the ONLY path from a user's suggestion to a global rule, and it
   * happens in one transaction with the rule it writes — so an approval can
   * never leave a submission marked approved whose rule was not created.
   */
  router.post('/submissions/:submissionId/review', requireAdmin(database, 'brainrot.manage'), async (req, res) => {
    const id = pathIdParam(req.params.submissionId, 'invalid_submission_id');
    const body = parseBody(ReviewBodySchema, req.body);
    const result = await reviewSubmission(database, id, body.decision, actorOf(req).adminId);
    res.status(200).json(result);
  });

  return router;
}