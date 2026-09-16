import { randomBytes } from 'node:crypto';
import { Router, type NextFunction, type Request, type RequestHandler, type Response } from 'express';
import { z } from 'zod';
import { env, hashIp } from '../env.js';
import type { Queryable } from '../db.js';
import { badRequest, forbidden, notFound, unauthorized } from '../http/errors.js';
import { parseBody, pathIdParam } from '../http/validate.js';
import { rateLimit, type FixedWindowRateLimiter, type RateLimitConfig } from '../http/rateLimit.js';
import { setAccountStatus, setChannelStatus } from '../moderation/service.js';
import { fanOutPlatformNotice } from '../notifications/service.js';
import type { PushSender } from '../notifications/push.js';
import { revokeAllSessions } from '../auth/service.js';
import { can, permissionsFor, type AdminAction, type AdminRole } from './permissions.js';
import {
  createAdmin,
  listAdmins,
  listAudit,
  loadAdmin,
  loginAdmin,
  logoutAdmin,
  refreshAdminSession,
  revokeAdminSessions,
  setAdminRole,
  setAdminStatus,
  writeAudit,
  isAdminSessionLive,
  type AdminAccount,
} from './service.js';
import { verifyAdminAccessToken } from './tokens.js';
import {
  getChannelDetail,
  getReport,
  getReportContext,
  getUserDetail,
  listAdminMessages,
  listBannedIdentities,
  listReports,
  overview,
  searchChannels,
  searchPosts,
  searchUsers,
} from './query.js';

/**
 * The admin API (§21–§30), mounted at `/admin/api`.
 *
 * Four things about this router are load-bearing.
 *
 *  * **A separate path prefix from `/api/v1`.** No user endpoint can be reached
 *    with an admin token, and no admin endpoint with a user token: the tokens
 *    are signed with different keys AND the paths do not overlap, so a mistake
 *    in either layer alone does not open a hole (§48).
 *
 *  * **The permission check is per route, server-side** (§32). The client sends
 *    no role, no permission and no "I am an admin" flag that is believed; the
 *    role is read from `admin_users` on every request.
 *
 *  * **Every sensitive action is audited, including refusals** (§29), written
 *    AFTER the action commits so the log records what actually happened rather
 *    than what was attempted.
 *
 *  * **Mutations require a CSRF header.** The dashboard is same-origin, holds
 *    its access token in memory and is intended to be usable straight from a
 *    browser, so the token for the session itself is carried in a cookie. A
 *    cookie-authenticated write endpoint without CSRF protection is the classic
 *    way a moderation dashboard gets weaponised; `SameSite=Strict` plus a
 *    double-submit header is what replaces a CSRF library here.
 */

const ADMIN_SESSION_COOKIE = 'gp_admin_session';
const ADMIN_CSRF_COOKIE = 'gp_admin_csrf';
const CSRF_HEADER = 'x-csrf-token';

interface AdminRequest extends Request {
  admin?: AdminContext;
}

export interface AdminContext {
  readonly adminId: string;
  readonly sessionId: string;
  readonly role: AdminRole;
  readonly account: AdminAccount;
}

/** Cookies, parsed without a dependency. Values are opaque, so no decoding. */
function cookiesOf(req: Request): Record<string, string> {
  const header = req.header('cookie') ?? '';
  const out: Record<string, string> = {};
  for (const part of header.split(';')) {
    const index = part.indexOf('=');
    if (index <= 0) continue;
    out[part.slice(0, index).trim()] = part.slice(index + 1).trim();
  }
  return out;
}

function adminOf(req: Request): AdminContext {
  const context = (req as AdminRequest).admin;
  if (!context) throw new Error('[admin] adminOf() called on a route without requireAdmin');
  return context;
}

/**
 * Require a live admin session AND a specific permission.
 *
 * Both halves matter and neither is optional: authentication without the
 * permission check is how a MODERATOR ends up able to create administrators,
 * and the permission check without re-reading the status is how a disabled
 * administrator keeps working until their 10-minute token expires.
 */
export function requireAdmin(database: Queryable, action: AdminAction): RequestHandler {
  return async (req: Request, _res: Response, next: NextFunction) => {
    const header = req.header('authorization') ?? '';
    const token = header.startsWith('Bearer ') ? header.slice('Bearer '.length).trim() : '';

    if (!token) {
      next(unauthorized('missing_token', 'An admin bearer token is required.'));
      return;
    }

    const claims = verifyAdminAccessToken(token);
    if (!claims) {
      next(unauthorized('invalid_token', 'The admin token is not valid.'));
      return;
    }

    if (!(await isAdminSessionLive(database, claims.sub, claims.sid))) {
      next(unauthorized('session_revoked', 'This admin session is no longer valid. Sign in again.'));
      return;
    }

    const account = await loadAdmin(database, claims.sub);
    if (!account) {
      next(unauthorized('session_revoked', 'This admin session is no longer valid. Sign in again.'));
      return;
    }
    if (account.status !== 'active') {
      next(forbidden('admin_disabled', 'This administrator account is disabled.'));
      return;
    }

    if (!can(account.role, action)) {
      // A refused attempt is one of the more interesting audit rows: it is the
      // only evidence that anyone tried.
      await writeAudit(database, {
        adminId: account.id,
        adminEmail: account.email,
        actorRole: account.role,
        action: `denied.${action}`,
        targetType: 'route',
        targetId: `${req.method} ${req.baseUrl}${req.path}`,
        outcome: 'denied',
        metadata: { requiredAction: action },
        ipHash: hashIp(req.ip ?? 'unknown'),
      });
      next(forbidden('admin_forbidden', 'Your role does not permit that action.'));
      return;
    }

    (req as AdminRequest).admin = {
      adminId: account.id,
      sessionId: claims.sid,
      role: account.role,
      account,
    };

    // Reads are deliberately NOT audited here. §29 lists sensitive ACTIONS, and
    // an audit log that records every dashboard refresh stops being readable by
    // the people who need it — a log nobody reads is not a control. Mutations
    // audit themselves, and a refusal is audited below.
    next();
  };
}

/**
 * A permission check that depends on the REQUEST BODY rather than the route.
 *
 * Account status is one endpoint with three statuses, and §28 gives them to
 * different roles: `admin` may suspend, only `super_admin` may ban. Route
 * middleware cannot express that, so the check runs inside the handler — and it
 * runs before anything touches the account, so a refusal is a refusal and not a
 * partial action.
 */
async function assertMay(
  database: Queryable,
  context: AdminContext,
  action: AdminAction,
  req: Request
): Promise<void> {
  if (can(context.role, action)) return;

  await writeAudit(database, {
    adminId: context.adminId,
    adminEmail: context.account.email,
    actorRole: context.role,
    action: `denied.${action}`,
    targetType: 'route',
    targetId: `${req.method} ${req.baseUrl}${req.path}`,
    outcome: 'denied',
    metadata: { requiredAction: action },
    ipHash: hashIp(req.ip ?? 'unknown'),
  });

  throw forbidden('admin_forbidden', 'Your role does not permit that action.');
}

/**
 * Double-submit CSRF check for every non-GET admin request.
 *
 * The token must be present in BOTH the readable cookie and the header. An
 * attacker who can make a browser send the session cookie cannot read the CSRF
 * cookie (same-origin policy) and therefore cannot set the header — which is
 * the whole mechanism. `SameSite=Strict` on the session cookie is the second
 * layer, not a substitute.
 */
function requireCsrf(req: Request, _res: Response, next: NextFunction): void {
  if (req.method === 'GET' || req.method === 'HEAD' || req.method === 'OPTIONS') {
    next();
    return;
  }
  const cookies = cookiesOf(req);
  const fromCookie = cookies[ADMIN_CSRF_COOKIE] ?? '';
  const fromHeader = req.header(CSRF_HEADER) ?? '';

  if (fromCookie === '' || fromHeader === '' || fromCookie !== fromHeader) {
    next(forbidden('csrf_failed', 'This request is missing its CSRF token.'));
    return;
  }
  next();
}

const LoginSchema = z.object({
  identifier: z.string().min(3).max(254),
  password: z.string().min(1).max(200),
});

const RefreshSchema = z.object({ refreshToken: z.string().min(1).max(256).optional() });

const UserStatusSchema = z.object({
  status: z.enum(['active', 'suspended', 'banned']),
  reason: z.string().min(3).max(200),
  note: z.string().max(2000).optional(),
});

const ChannelStatusSchema = z.object({
  status: z.enum(['active', 'suspended', 'banned']),
  reason: z.string().min(3).max(200).optional(),
});

const RemovePostSchema = z.object({ reason: z.string().min(3).max(200) });

const ResolveReportSchema = z.object({
  status: z.enum(['reviewing', 'resolved', 'dismissed']),
  actionTaken: z.string().max(200).optional(),
  resolutionNote: z.string().max(2000).optional(),
});

const AdminMessageSchema = z.object({
  targetUserId: z.string().min(1).max(64).optional(),
  targetChannelId: z.string().min(1).max(64).optional(),
  subject: z.string().min(1).max(200),
  body: z.string().min(1).max(8000),
});

const CreateAdminSchema = z.object({
  displayName: z.string().min(1).max(80),
  email: z.string().min(3).max(254),
  phone: z.string().min(8).max(20),
  role: z.enum(['super_admin', 'admin', 'moderator']),
  password: z.string().min(1).max(200),
});

const RoleSchema = z.object({
  role: z.enum(['super_admin', 'admin', 'moderator']),
});

const PageSchema = z.object({
  limit: z.string().max(10).optional(),
  cursor: z.string().max(512).optional(),
  q: z.string().max(120).optional(),
  status: z.string().max(20).optional(),
  targetType: z.string().max(20).optional(),
  channelId: z.string().max(64).optional(),
  removed: z.string().max(5).optional(),
  days: z.string().max(4).optional(),
});

export function buildAdminRouter(
  database: Queryable,
  limiter: FixedWindowRateLimiter,
  rateLimits: RateLimitConfig,
  push: PushSender
): Router {
  const router = Router();
  const loginLimit = rateLimit(limiter, rateLimits.auth);
  const messageLimit = rateLimit(limiter, rateLimits.adminMessage);

  /**
   * Establish the CSRF cookie for a browser session.
   *
   * Issued on login and refresh, and readable by the dashboard's JavaScript by
   * design — that is what makes the double submit possible.
   */
  function issueCsrf(res: Response): string {
    const token = randomBytes(24).toString('base64url');
    // Not `Secure` when running locally: a Secure cookie is dropped over plain
    // HTTP, which would make the dashboard unusable in development while
    // silently looking like a CSRF failure.
    const secure = env.NODE_ENV === 'production' ? '; Secure' : '';
    res.setHeader('Set-Cookie', [
      `${ADMIN_CSRF_COOKIE}=${token}; Path=/; SameSite=Strict${secure}`,
    ]);
    return token;
  }

  /** The session cookie, httpOnly so no script can read it (§30). */
  function issueSession(res: Response, refreshToken: string): void {
    const secure = env.NODE_ENV === 'production' ? '; Secure' : '';
    const maxAge = env.ADMIN_SESSION_TTL_DAYS * 86_400;
    res.append(
      'Set-Cookie',
      `${ADMIN_SESSION_COOKIE}=${refreshToken}; Path=/admin; HttpOnly; SameSite=Strict; Max-Age=${maxAge}${secure}`
    );
  }

  // ── Authentication (§21, §30) ───────────────────────────────────────

  router.post('/auth/login', loginLimit, async (req, res) => {
    const body = parseBody(LoginSchema, req.body);
    const session = await loginAdmin(database, {
      identifier: body.identifier,
      password: body.password,
      ipHash: hashIp(req.ip ?? 'unknown'),
      userAgent: req.header('user-agent') ?? null,
    });

    const csrf = issueCsrf(res);
    issueSession(res, session.refreshToken);
    res.status(200).json({ ...session, csrfToken: csrf });
  });

  // EVERY route below this line requires the double-submit token on any
  // non-GET request. Applied as router-level middleware rather than per route
  // precisely so a new mutating endpoint cannot be added without it — the
  // failure mode of a forgotten `requireCsrf` is a moderation action a web page
  // can trigger on a signed-in administrator's behalf.
  //
  // Login is above it on purpose: there is no session yet, so there is nothing
  // to protect, and requiring a token to sign in would break the first request
  // of any new browser.
  router.use(requireCsrf);

  router.post('/auth/refresh', async (req, res) => {
    const body = parseBody(RefreshSchema, req.body);
    // The cookie is preferred; the body is accepted so a non-browser client
    // (a script, a test) can rotate without pretending to be one.
    const token = cookiesOf(req)[ADMIN_SESSION_COOKIE] ?? body.refreshToken ?? '';
    if (token === '') throw unauthorized('invalid_refresh_token', 'Sign in again.');

    const session = await refreshAdminSession(database, token, hashIp(req.ip ?? 'unknown'));
    const csrf = issueCsrf(res);
    issueSession(res, session.refreshToken);
    res.status(200).json({ ...session, csrfToken: csrf });
  });

  router.post('/auth/logout', async (req, res) => {
    const body = parseBody(RefreshSchema, req.body);
    const token = cookiesOf(req)[ADMIN_SESSION_COOKIE] ?? body.refreshToken ?? '';
    if (token !== '') await logoutAdmin(database, token);

    const secure = env.NODE_ENV === 'production' ? '; Secure' : '';
    res.setHeader('Set-Cookie', [
      `${ADMIN_CSRF_COOKIE}=; Path=/; Max-Age=0; SameSite=Strict${secure}`,
      // Scoped to the same path it was issued on, or the browser keeps it.
      `${ADMIN_SESSION_COOKIE}=; Path=/admin; HttpOnly; Max-Age=0; SameSite=Strict${secure}`,
    ]);
    res.status(200).json({ signedOut: true });
  });

  /** Who am I, and what may I do? The dashboard's only source of capabilities. */
  router.get('/auth/me', requireAdmin(database, 'overview.read'), async (req, res) => {
    const context = adminOf(req);
    res.status(200).json({
      admin: context.account,
      permissions: permissionsFor(context.role),
    });
  });

  // ── Overview (§22) ──────────────────────────────────────────────────

  router.get('/overview', requireAdmin(database, 'overview.read'), async (req, res) => {
    const context = adminOf(req);
    res.status(200).json({
      counts: await overview(database),
      permissions: permissionsFor(context.role),
      role: context.role,
    });
  });

  // ── Users (§23) ─────────────────────────────────────────────────────

  router.get('/users', requireAdmin(database, 'users.read'), async (req, res) => {
    const query = parseBody(PageSchema, req.query);
    res.status(200).json(await searchUsers(database, query));
  });

  router.get('/users/:userId', requireAdmin(database, 'users.read'), async (req, res) => {
    const userId = pathIdParam(req.params.userId, 'invalid_user_id');
    res.status(200).json(await getUserDetail(database, userId));
  });

  /**
   * Change an account's status (§23).
   *
   * A ban is checked against `users.ban` rather than `users.moderate`: §28 gives
   * "ban users" to SUPER_ADMIN only, so the two statuses come through the same
   * endpoint but not through the same permission.
   */
  router.post('/users/:userId/status', requireAdmin(database, 'users.read'), async (req, res) => {
    const context = adminOf(req);
    const body = parseBody(UserStatusSchema, req.body);
    await assertMay(
      database,
      context,
      body.status === 'banned' ? 'users.ban' : 'users.moderate',
      req
    );

    const userId = pathIdParam(req.params.userId, 'invalid_user_id');
    const result = await setAccountStatus(database, userId, body.status, {
      adminId: context.adminId,
      reason: body.reason,
      note: body.note,
    });

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: `user.${body.status}`,
      targetType: 'user',
      targetId: userId,
      metadata: { reason: body.reason, sessionsRevoked: result.sessionsRevoked },
    });
    res.status(200).json(result);
  });

  /** Force a user to sign in again on every device (§23). */
  router.post('/users/:userId/sessions/revoke', requireAdmin(database, 'users.moderate'), async (req, res) => {
    const context = adminOf(req);
    const userId = pathIdParam(req.params.userId, 'invalid_user_id');
    const revoked = await revokeAllSessions(database, userId, 'revoked_by_admin');

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'user.sessions.revoke',
      targetType: 'user',
      targetId: userId,
      metadata: { revoked },
    });
    res.status(200).json({ revoked });
  });

  // ── Channels (§24) ──────────────────────────────────────────────────

  router.get('/channels', requireAdmin(database, 'channels.read'), async (req, res) => {
    const query = parseBody(PageSchema, req.query);
    res.status(200).json(await searchChannels(database, query));
  });

  router.get('/channels/:channelId', requireAdmin(database, 'channels.read'), async (req, res) => {
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    res.status(200).json(await getChannelDetail(database, channelId));
  });

  router.post('/channels/:channelId/status', requireAdmin(database, 'channels.moderate'), async (req, res) => {
    const context = adminOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    const body = parseBody(ChannelStatusSchema, req.body);

    const result = await setChannelStatus(database, channelId, body.status, body.reason);
    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: `channel.${body.status}`,
      targetType: 'channel',
      targetId: channelId,
      metadata: { reason: body.reason ?? null },
    });
    res.status(200).json(result);
  });

  /**
   * A channel's private follower conversations (§24, §26).
   *
   * Guarded by `conversations.read`, which only ADMIN and SUPER_ADMIN hold: a
   * follower's private messages are the most sensitive content in the product,
   * and §16's promise to them is only as strong as who can read the thread.
   */
  router.get('/channels/:channelId/conversations', requireAdmin(database, 'conversations.read'), async (req, res) => {
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    const detail = await getChannelDetail(database, channelId);

    // Deliberately the channel-level list only: subject, participant display
    // name and message counts. A moderator reads an individual message through
    // a REPORT (§18), which is a bounded, recorded act — not by browsing.
    res.status(200).json({
      channelId,
      conversationCount: detail.conversations,
      allowFollowerMessages: detail.allowFollowerMessages,
    });
  });

  // ── Posts (§25) ─────────────────────────────────────────────────────

  router.get('/posts', requireAdmin(database, 'posts.read'), async (req, res) => {
    const query = parseBody(PageSchema, req.query);
    res.status(200).json(await searchPosts(database, query));
  });

  /** Remove a post (§25). Soft, so §18's reversibility holds. */
  router.post('/posts/:postId/remove', requireAdmin(database, 'posts.moderate'), async (req, res) => {
    const context = adminOf(req);
    const postId = pathIdParam(req.params.postId, 'invalid_post_id');
    const body = parseBody(RemovePostSchema, req.body);

    const removed = await removeOrRestorePost(database, postId, true, body.reason);
    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'post.remove',
      targetType: 'post',
      targetId: postId,
      metadata: { reason: body.reason, channelId: removed.channelId },
    });
    res.status(200).json(removed);
  });

  router.post('/posts/:postId/restore', requireAdmin(database, 'posts.moderate'), async (req, res) => {
    const context = adminOf(req);
    const postId = pathIdParam(req.params.postId, 'invalid_post_id');

    const restored = await removeOrRestorePost(database, postId, false, null);
    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'post.restore',
      targetType: 'post',
      targetId: postId,
      metadata: { channelId: restored.channelId },
    });
    res.status(200).json(restored);
  });

  // ── Reports (§18) ───────────────────────────────────────────────────

  router.get('/reports', requireAdmin(database, 'reports.read'), async (req, res) => {
    const query = parseBody(PageSchema, req.query);
    res.status(200).json(await listReports(database, query));
  });

  router.get('/reports/:reportId', requireAdmin(database, 'reports.read'), async (req, res) => {
    const reportId = pathIdParam(req.params.reportId, 'invalid_report_id');
    const report = await getReport(database, reportId);
    if (!report) throw notFound('report_not_found');

    // §18's queue is only useful if a moderator can judge a report without
    // hunting for its subject, so the target's own content comes back with it.
    res.status(200).json({
      report,
      context: await getReportContext(database, report.targetType, report.targetId),
    });
  });

  /** Claim a report, or close it. §18's status machine, in one endpoint. */
  router.post('/reports/:reportId/resolve', requireAdmin(database, 'reports.work'), async (req, res) => {
    const context = adminOf(req);
    const reportId = pathIdParam(req.params.reportId, 'invalid_report_id');
    const body = parseBody(ResolveReportSchema, req.body);

    if (body.status === 'resolved' && !body.actionTaken?.trim()) {
      throw badRequest('action_required', 'Say what action was taken before resolving a report.');
    }

    const updated = await resolveReport(database, reportId, context.adminId, body);
    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: `report.${body.status}`,
      targetType: 'report',
      targetId: reportId,
      metadata: {
        actionTaken: body.actionTaken ?? null,
        hasNote: Boolean(body.resolutionNote?.trim()),
      },
    });
    res.status(200).json({ report: updated });
  });

  // ── Banned identities (§19, §23) ────────────────────────────────────

  router.get('/identities', requireAdmin(database, 'identities.read'), async (req, res) => {
    const query = parseBody(PageSchema, req.query);
    res.status(200).json(
      await listBannedIdentities(database, { ...query, activeOnly: query.status !== 'all' })
    );
  });

  router.post('/identities/:identityId/lift', requireAdmin(database, 'identities.lift'), async (req, res) => {
    const context = adminOf(req);
    const identityId = pathIdParam(req.params.identityId, 'invalid_identity_id');

    const lifted = await liftIdentity(database, identityId);
    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'identity.lift',
      targetType: 'identity',
      targetId: identityId,
      metadata: { userId: lifted.userId },
    });
    res.status(200).json(lifted);
  });

  // ── Official messages (§26) ─────────────────────────────────────────

  router.get('/messages', requireAdmin(database, 'messages.send'), async (req, res) => {
    const query = parseBody(PageSchema, req.query);
    res.status(200).json(await listAdminMessages(database, query));
  });

  router.post('/messages', requireAdmin(database, 'messages.send'), messageLimit, async (req, res) => {
    const context = adminOf(req);
    const body = parseBody(AdminMessageSchema, req.body);

    const hasUser = Boolean(body.targetUserId);
    const hasChannel = Boolean(body.targetChannelId);
    if (hasUser === hasChannel) {
      throw badRequest('invalid_target', 'Send to either a user or a channel, not both.');
    }

    const message = await sendOfficialMessage(database, context.adminId, {
      targetUserId: body.targetUserId,
      targetChannelId: body.targetChannelId,
      subject: body.subject,
      body: body.body,
    });

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'message.send',
      targetType: hasUser ? 'user' : 'channel',
      targetId: body.targetUserId ?? body.targetChannelId ?? null,
      metadata: { messageId: message.id, subject: body.subject },
    });

    // §26's inbox copy, then the push attempt (§17). AFTER the audit row and the
    // message are committed, and never fatal: an official notice that reached
    // the recipient's inbox is a completed action even if their phone was
    // unreachable, and failing the request would revoke a notice the reviewer
    // already sent — and would leave the audit log claiming it was sent.
    try {
      await fanOutPlatformNotice(database, push, {
        adminMessageId: message.id,
        targetUserId: body.targetUserId,
        targetChannelId: body.targetChannelId,
        subject: body.subject,
        body: body.body,
      });
    } catch (err) {
      console.error('[notifications] platform notice fan-out failed:', (err as Error).message);
    }

    res.status(201).json({ message });
  });

  // ── Administrators (§27) ────────────────────────────────────────────

  router.get('/admins', requireAdmin(database, 'admins.read'), async (_req, res) => {
    res.status(200).json({ items: await listAdmins(database) });
  });

  router.post('/admins', requireAdmin(database, 'admins.manage'), async (req, res) => {
    const context = adminOf(req);
    const body = parseBody(CreateAdminSchema, req.body);

    const created = await createAdmin(database, {
      displayName: body.displayName,
      email: body.email,
      phone: body.phone,
      role: body.role,
      password: body.password,
      createdByAdminId: context.adminId,
    });

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'admin.create',
      targetType: 'admin',
      targetId: created.id,
      metadata: { role: created.role, email: created.email },
    });
    res.status(201).json({ admin: created });
  });

  router.post('/admins/:adminId/status', requireAdmin(database, 'admins.manage'), async (req, res) => {
    const context = adminOf(req);
    const adminId = pathIdParam(req.params.adminId, 'invalid_admin_id');
    const body = parseBody(z.object({ status: z.enum(['active', 'disabled']) }), req.body);

    // Locking yourself out is not moderation, it is a mistake — and with the
    // last SUPER_ADMIN it is unrecoverable, which is why it is refused rather
    // than merely warned about.
    if (adminId === context.adminId && body.status === 'disabled') {
      throw badRequest('cannot_disable_self', 'You cannot disable your own account.');
    }

    const result = await setAdminStatus(database, adminId, body.status);
    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: `admin.${body.status}`,
      targetType: 'admin',
      targetId: adminId,
      metadata: { sessionsRevoked: result.sessionsRevoked },
    });
    res.status(200).json(result);
  });

  router.post('/admins/:adminId/role', requireAdmin(database, 'admins.manage'), async (req, res) => {
    const context = adminOf(req);
    const adminId = pathIdParam(req.params.adminId, 'invalid_admin_id');
    const body = parseBody(RoleSchema, req.body);

    const updated = await setAdminRole(database, adminId, body.role);
    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'admin.role',
      targetType: 'admin',
      targetId: adminId,
      metadata: { role: body.role },
    });
    res.status(200).json({ admin: updated });
  });

  router.post('/admins/:adminId/sessions/revoke', requireAdmin(database, 'admins.manage'), async (req, res) => {
    const context = adminOf(req);
    const adminId = pathIdParam(req.params.adminId, 'invalid_admin_id');

    const revoked = await revokeAdminSessions(database, adminId, 'revoked_by_admin');
    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'admin.sessions.revoke',
      targetType: 'admin',
      targetId: adminId,
      metadata: { revoked },
    });
    res.status(200).json({ revoked });
  });

  // ── Audit and settings (§29, §30) ───────────────────────────────────

  router.get('/audit', requireAdmin(database, 'audit.read'), async (req, res) => {
    const query = parseBody(PageSchema, req.query);
    const limit = query.limit === undefined ? 100 : Number(query.limit);
    res.status(200).json({
      items: await listAudit(database, {
        limit: Number.isFinite(limit) ? limit : 100,
      }),
    });
  });

  /**
   * Effective, NON-SECRET configuration (§22 "Settings").
   *
   * Read-only in M6, and `settings.write` is deliberately not wired to any
   * mutating route: §28 grants it to SUPER_ADMIN, and every setting that
   * matters here (`GOODPOST_HISTORY_DAYS`, retention, limits) is an environment
   * variable on Render. A dashboard that changed them would be a second,
   * unaudited source of truth for the deployment's behaviour.
   */
  router.get('/settings', requireAdmin(database, 'settings.read'), async (_req, res) => {
    res.status(200).json({
      settings: {
        historyDays: env.GOODPOST_HISTORY_DAYS,
        purgeGraceDays: env.PURGE_GRACE_DAYS,
        editWindowDays: env.EDIT_WINDOW_DAYS,
        maxTextLength: env.MAX_TEXT_LENGTH,
        maxPostMedia: env.MAX_POST_MEDIA,
        maxChannelsPerUser: env.MAX_CHANNELS_PER_USER,
        defaultPageSize: env.DEFAULT_PAGE_SIZE,
        maxPageSize: env.MAX_PAGE_SIZE,
        viewDedupeWindowMinutes: env.VIEW_DEDUPE_WINDOW_MINUTES,
        pollOptions: { min: env.POLL_MIN_OPTIONS, max: env.POLL_MAX_OPTIONS },
        banPhoneEnforced: env.BAN_PHONE_ENFORCED,
        phoneVerifyMode: env.PHONE_VERIFY_MODE,
        emailDeliveryMode: env.EMAIL_DELIVERY_MODE,
        fcmEnabled: env.FCM_ENABLED,
        s3Configured: Boolean(env.AWS_S3_BUCKET),
        adminSessionTtlDays: env.ADMIN_SESSION_TTL_DAYS,
        adminAccessTokenTtl: env.ADMIN_ACCESS_TOKEN_TTL,
      },
    });
  });

  return router;
}

// ── Writes that are not in service.ts ───────────────────────────────────

/**
 * Remove or restore a post (§25).
 *
 * Lives here rather than in the moderation service because it is the only
 * caller that removes a post without being its channel's admin — the owner's
 * path (`deletePost`) authenticates the channel instead. The two must agree on
 * what removal MEANS, so both set the same columns and both keep
 * `post_count` and `last_post_at` honest.
 */
async function removeOrRestorePost(
  database: Queryable,
  postId: string,
  remove: boolean,
  reason: string | null
): Promise<{ postId: string; channelId: string; removed: boolean }> {
  return database.transaction(async (tx) => {
    const row = await tx.queryOne<{ id: string; channel_id: string; deleted_at: unknown }>(
      `SELECT id, channel_id, deleted_at FROM posts WHERE id = $1`,
      [postId]
    );
    if (!row) throw notFound('post_not_found');

    const alreadyRemoved = row.deleted_at !== null;
    if (remove && alreadyRemoved) throw badRequest('already_removed', 'That post is already removed.');
    if (!remove && !alreadyRemoved) throw badRequest('not_removed', 'That post is not removed.');

    await tx.query(
      `UPDATE posts
          SET deleted_at = CASE WHEN $2::boolean THEN now() ELSE NULL END,
              deleted_reason = CASE WHEN $2::boolean THEN $3::text ELSE NULL END
        WHERE id = $1`,
      [postId, remove, reason]
    );

    // Recounted from the rows, exactly as the owner's delete path does, so the
    // channel's counters cannot disagree with its content.
    await tx.query(
      `UPDATE channels
          SET post_count = (SELECT count(*) FROM posts WHERE channel_id = $1 AND deleted_at IS NULL),
              last_post_at = (SELECT max(created_at) FROM posts WHERE channel_id = $1 AND deleted_at IS NULL)
        WHERE id = $1`,
      [row.channel_id]
    );

    return { postId, channelId: row.channel_id, removed: remove };
  });
}

/** §18's decision record: status, action taken, note, resolver and timestamp. */
async function resolveReport(
  database: Queryable,
  reportId: string,
  adminId: string,
  input: { status: 'reviewing' | 'resolved' | 'dismissed'; actionTaken?: string | undefined; resolutionNote?: string | undefined }
): Promise<unknown> {
  const closing = input.status === 'resolved' || input.status === 'dismissed';

  const updated = await database.query(
    `UPDATE reports
        SET status = $2::report_status,
            action_taken = COALESCE($3, action_taken),
            resolution_note = COALESCE($4, resolution_note),
            resolved_by_admin_id = CASE WHEN $5::boolean THEN $6::uuid ELSE resolved_by_admin_id END,
            resolved_at = CASE WHEN $5::boolean THEN now() ELSE resolved_at END
      WHERE id = $1
      RETURNING id`,
    [reportId, input.status, input.actionTaken ?? null, input.resolutionNote ?? null, closing, adminId]
  );
  if (updated.length === 0) throw notFound('report_not_found');

  // Read back through the same mapper as the queue, so the response to the act
  // and the next queue fetch cannot describe the same report differently.
  return getReport(database, reportId);
}

/** Lift a mobile-identity ban (§19, §23), reinstating the affected account. */
async function liftIdentity(
  database: Queryable,
  identityId: string
): Promise<{ identityId: string; userId: string | null }> {
  return database.transaction(async (tx) => {
    const row = await tx.queryOne<{ id: string; phone_hash: string; lifted_at: unknown }>(
      `SELECT id, phone_hash, lifted_at FROM banned_identities WHERE id = $1`,
      [identityId]
    );
    if (!row) throw notFound('identity_not_found');
    if (row.lifted_at !== null) throw badRequest('already_lifted', 'That ban has already been lifted.');

    await tx.query(`UPDATE banned_identities SET lifted_at = now() WHERE id = $1`, [identityId]);

    // The account that holds this identity goes back to active. Without this the
    // number would be registrable again while the existing account stayed
    // banned, which is a state no policy describes.
    const account = await tx.queryOne<{ id: string }>(
      `UPDATE users SET status = 'active', banned_at = NULL
        WHERE phone_hash = $1 AND status = 'banned'
        RETURNING id`,
      [row.phone_hash]
    );

    return { identityId, userId: account?.id ?? null };
  });
}

/** Send an official platform message (§26). */
async function sendOfficialMessage(
  database: Queryable,
  adminId: string,
  input: {
    readonly targetUserId?: string | undefined;
    readonly targetChannelId?: string | undefined;
    readonly subject: string;
    readonly body: string;
  }
): Promise<{ id: string }> {
  if (input.targetUserId) {
    const exists = await database.queryOne(
      `SELECT 1 FROM users WHERE id = $1 AND deleted_at IS NULL`,
      [input.targetUserId]
    );
    if (!exists) throw notFound('user_not_found');
  } else if (input.targetChannelId) {
    const exists = await database.queryOne(
      `SELECT 1 FROM channels WHERE id = $1 AND deleted_at IS NULL`,
      [input.targetChannelId]
    );
    if (!exists) throw notFound('channel_not_found');
  }

  const inserted = await database.query<{ id: string }>(
    `INSERT INTO admin_messages (admin_id, target_user_id, target_channel_id, subject, body)
     VALUES ($1, $2, $3, $4, $5)
     RETURNING id`,
    [
      adminId,
      input.targetUserId ?? null,
      input.targetChannelId ?? null,
      input.subject.trim(),
      input.body.trim(),
    ]
  );

  const row = inserted[0];
  if (!row) throw new Error('[admin] message insert returned no row');
  return { id: row.id };
}
