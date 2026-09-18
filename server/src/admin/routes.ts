import { Router, type NextFunction, type Request, type RequestHandler, type Response } from 'express';
import { z } from 'zod';
import { env, hashIp } from '../env.js';
import type { Queryable } from '../db.js';
import { badRequest, forbidden, notFound, unauthorized } from '../http/errors.js';
import { parseBody, pathIdParam } from '../http/validate.js';
import { rateLimit, type FixedWindowRateLimiter, type RateLimitConfig } from '../http/rateLimit.js';
import {
  applyChannelIcon,
  clearChannelIcon,
  clearChannelIconIn,
  createChannel,
  deleteChannel,
  listChannelsForAdmin,
  loadChannelRow,
  mapChannel,
  requireChannelScope,
  setChannelIcon,
  setChannelStatus,
  updateChannel,
  type ChannelRow,
} from '../channels/service.js';
import {
  deletePost,
  listChannelPostsForAdmin,
  loadPostRow,
  publishPost,
  updatePost,
} from '../posts/service.js';
import {
  confirmMediaUpload,
  removeObjectQuietly,
  requestMediaUpload,
  signObjectUrl,
  type MediaSummary,
} from '../media/service.js';
import type { ObjectStore } from '../media/store.js';
import {
  createFirebaseVerifier,
  type IdentityVerifier,
  type VerifiedIdentity,
} from '../identity/verifier.js';
import { createCreatorChannel, signInCreator } from './creator.js';
import { createPushSender, type PushSender } from '../notifications/fcm.js';
import { announcePost } from '../notifications/service.js';
import { can, type AdminAction, type AdminRole } from './permissions.js';
import {
  capabilitiesOf,
  changeChannelAdminPassword,
  createAdmin,
  listAdmins,
  listAudit,
  loadAdmin,
  loginAdmin,
  logoutAdmin,
  refreshAdminSession,
  revokeAdminSessions,
  setAdminStatus,
  isAdminSessionLive,
  writeAudit,
  type AdminAccount,
} from './service.js';
import { verifyAdminAccessToken } from './tokens.js';

/**
 * The administrator API (§16–§21, §25, §27), mounted at `/admin/api`.
 *
 * Five things about this router are load-bearing.
 *
 *  * **A separate path prefix from `/api/v1`.** The reader surface is anonymous
 *    and read-only; this one requires a token on every route. Two prefixes mean
 *    a route cannot be added to the wrong one by accident, and no reader
 *    request can reach a publishing endpoint by guessing a path.
 *
 *  * **The role is read from `admin_users` on every request** (§18). The client
 *    sends no role, no scope and no channel id that is believed. A channel
 *    administrator's `channel_id` comes from their own row, so \"only my
 *    channel\" is a fact about the request rather than a filter somebody has to
 *    remember.
 *
 *  * **A channel a channel admin may not touch answers 404, not 403.** Telling
 *    them a channel exists but is not theirs would confirm a channel they have
 *    no business knowing about.
 *
 *  * **No cookies, and therefore no CSRF token.** The only client is the Android
 *    app, which sends a bearer token in a header; nothing here is authenticated
 *    by an ambient credential a browser would attach on its own. A cookie-based
 *    session would need the double-submit machinery back, so it is deliberately
 *    absent rather than forgotten.
 *
 *  * **Mutations are audited AFTER they commit**, so the log records what
 *    happened rather than what was attempted — and a refusal is audited too,
 *    because the only evidence that anyone tried is in the log.
 */

interface AdminRequest extends Request {
  admin?: AdminContext;
}

export interface AdminContext {
  readonly adminId: string;
  readonly sessionId: string;
  readonly role: AdminRole;
  /** The channel a channel admin is confined to; null for a super admin. */
  readonly channelId: string | null;
  readonly account: AdminAccount;
}

/** The account context, for the scope checks. Derived from the session. */
function scopeOf(context: AdminContext): { role: string; channelId: string | null } {
  return { role: context.role, channelId: context.channelId };
}

function adminOf(req: Request): AdminContext {
  const context = (req as AdminRequest).admin;
  if (!context) throw new Error('[admin] adminOf() called on a route without requireAdmin');
  return context;
}

/**
 * Require a live session AND a specific permission (§18, §25).
 *
 * Both halves matter. Authentication without the permission check is how a
 * channel administrator ends up able to mint another administrator, and the
 * permission check without re-reading the row is how a disabled administrator
 * keeps working until their token lapses.
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
      channelId: account.channelId,
      account,
    };
    next();
  };
}

/** A name, a description and a category, shared by create and update (§19, §20). */
const ChannelInputSchema = z.object({
  name: z.string().min(1).max(env.MAX_CHANNEL_NAME_LENGTH),
  description: z.string().max(env.MAX_CHANNEL_DESCRIPTION_LENGTH).nullish(),
  categorySlug: z.string().max(60).nullish(),
  countryCode: z.string().max(2).nullish(),
});

/**
 * The profile image, as a field on the channel form (§21).
 *
 * A media ID rather than a file or a URL: the bytes have already gone to the
 * bucket through the same presign → PUT → confirm handshake as post media, and
 * all that is left to say is WHICH confirmed upload this channel should adopt.
 * A URL would be a client-chosen pointer, which is the one thing object keys
 * are never allowed to be.
 */
const ChannelIconSchema = z.object({
  iconMediaId: z.string().uuid().nullish(),
});

/**
 * Creating a channel also creates the login that runs it (§20).
 *
 * Both credential fields are optional so a super admin may create a channel
 * they run themselves, but they travel together: an address with no password
 * could not sign in.
 */
const CreateChannelSchema = ChannelInputSchema.extend({
  adminEmail: z.string().min(3).max(254).optional(),
  adminPassword: z.string().min(1).max(200).optional(),
  adminDisplayName: z.string().min(1).max(80).optional(),
  iconMediaId: z.string().uuid().optional(),
});

/**
 * An edit, where the profile image is a THREE-way field:
 *
 *   absent  → leave the image alone
 *   a uuid  → adopt that confirmed upload as the image
 *   null    → remove the image
 *
 * The same convention the description and category already use, so "clear the
 * field" and "do not touch it" are one rule across the endpoint rather than one
 * rule per column.
 */
const UpdateChannelSchema = z.object({
  name: z.string().min(1).max(env.MAX_CHANNEL_NAME_LENGTH).optional(),
  description: z.string().max(env.MAX_CHANNEL_DESCRIPTION_LENGTH).nullish(),
  categorySlug: z.string().max(60).nullish(),
  countryCode: z.string().max(2).nullish(),
  iconMediaId: z.string().uuid().nullish(),
  /**
   * A new password for the account that runs this channel (§20).
   *
   * Optional, and absent on almost every edit: the field is on the form so the
   * one person who needs it — a super administrator handing a channel over, or a
   * channel administrator who thinks their password is known — does not have to
   * sign in as somebody else to change it. Never echoed back, and never
   * remembered by the form (§30).
   */
  adminPassword: z.string().min(1).max(200).optional(),
});

const PostInputSchema = z.object({
  body: z.string().max(env.MAX_TEXT_LENGTH).optional(),
  linkUrl: z.string().max(2048).optional(),
  linkTitle: z.string().max(200).optional(),
  /**
   * Uploads to attach, in order (§21).
   *
   * The type of the post is NOT accepted here: it is derived from these files,
   * so a client cannot describe its own post as a video while attaching a JPEG
   * and have every reader render it wrongly.
   */
  mediaIds: z.array(z.string().uuid()).max(env.MAX_POST_MEDIA).optional(),
});

const UpdatePostSchema = z.object({
  body: z.string().max(env.MAX_TEXT_LENGTH).nullish(),
  linkUrl: z.string().max(2048).nullish(),
  linkTitle: z.string().max(200).nullish(),
});

const UploadRequestSchema = z.object({
  contentType: z.string().min(3).max(120),
  byteSize: z.number().int().positive(),
  width: z.number().int().positive().optional(),
  height: z.number().int().positive().optional(),
  durationMs: z.number().int().positive().optional(),
});

const LoginSchema = z.object({
  email: z.string().min(3).max(254),
  password: z.string().min(1).max(200),
});

const RefreshSchema = z.object({ refreshToken: z.string().min(1).max(256) });

const CreateAdminSchema = z.object({
  displayName: z.string().min(1).max(80),
  email: z.string().min(3).max(254),
  password: z.string().min(1).max(200),
  channelId: z.string().uuid(),
});

const AdminStatusSchema = z.object({ status: z.enum(['active', 'disabled']) });

const PageQuerySchema = z.object({
  limit: z.string().max(10).optional(),
  cursor: z.string().max(512).optional(),
});

const AuditQuerySchema = z.object({ limit: z.string().max(10).optional() });

/**
 * Creating a creator's first channel (§16).
 *
 * Deliberately NOT a field on [CreateChannelSchema]: this form has one job — name
 * the channel — because the account it belongs to already exists as an identity.
 * Everything the super-admin form can also set (a description, a category, an
 * image) is available afterwards through the ordinary edit route, which the
 * creator's own scope check already permits.
 */
const CreateCreatorChannelSchema = z.object({
  name: z.string().min(1).max(env.MAX_CHANNEL_NAME_LENGTH),
  description: z.string().max(env.MAX_CHANNEL_DESCRIPTION_LENGTH).nullish(),
  categorySlug: z.string().max(60).nullish(),
  countryCode: z.string().max(2).nullish(),
});

/** Where a verified Firebase identity is parked for the route that needs it. */
interface CreatorRequest extends Request {
  creator?: VerifiedIdentity;
}

/**
 * Require a verified Firebase identity, and that it is not an anonymous one.
 *
 * Two refusals worth telling apart, and they are told apart by the verifier's
 * own state: a deployment with no `FIREBASE_PROJECT_ID` answers
 * `auth_unavailable` (503 — the operator has something to fix), while a caller
 * who presents a bad token gets `invalid_token` (401 — retrying changes
 * nothing). An anonymous reader is refused with `creator_required` rather than
 * `invalid_token`, because their token IS valid; §3's sign-in is one call away
 * for every install, so accepting it here would hand self-service channel
 * creation to anything that can reach `/api/v1`.
 */
function requireCreator(verifier: IdentityVerifier): RequestHandler {
  return async (req: Request, _res: Response, next: NextFunction) => {
    const header = req.header('authorization') ?? '';
    const token = header.startsWith('Bearer ') ? header.slice('Bearer '.length).trim() : '';
    if (!token) {
      next(unauthorized('missing_token', 'A Firebase ID token is required.'));
      return;
    }

    const identity = await verifier.verify(token);
    if (identity.anonymous) {
      next(forbidden('creator_required', 'This sign-in is not linked to an account.'));
      return;
    }

    (req as CreatorRequest).creator = identity;
    next();
  };
}

function creatorOf(req: Request): VerifiedIdentity {
  const identity = (req as CreatorRequest).creator;
  if (!identity) throw new Error('[admin] creatorOf() called on a route without requireCreator');
  return identity;
}

export function buildAdminRouter(
  database: Queryable,
  store: ObjectStore,
  limiter: FixedWindowRateLimiter,
  rateLimits: RateLimitConfig,
  verifier: IdentityVerifier = createFirebaseVerifier(env.FIREBASE_PROJECT_ID),
  /**
   * How a publish reaches a reader's phone (§8). Injected so the whole "who is
   * notified" rule is testable without a Firebase credential — and defaulted to
   * the unconfigured sender so a deployment without push behaves exactly as it
   * did before this existed.
   */
  push: PushSender = createPushSender()
): Router {
  const router = Router();
  const loginLimit = rateLimit(limiter, rateLimits.auth);
  const write = rateLimit(limiter, rateLimits.write);

  // ── Sign-in (§16) ────────────────────────────────────────────────────
  // The one unauthenticated route on this prefix, and the only place a password
  // crosses this boundary. It carries the tighter `auth` rule rather than the
  // global one.
  router.post('/auth/login', loginLimit, async (req, res) => {
    const body = parseBody(LoginSchema, req.body);
    const session = await loginAdmin(database, {
      email: body.email,
      password: body.password,
      ipHash: hashIp(req.ip ?? 'unknown'),
      userAgent: req.header('user-agent') ?? null,
    });

    res.status(200).json({
      accessToken: session.accessToken,
      refreshToken: session.refreshToken,
      expiresInSeconds: session.expiresInSeconds,
      admin: session.admin,
      permissions: capabilitiesOf(session.admin.role),
    });
  });

  // ── Creator sign-in (§16) ────────────────────────────────────────────
  //
  // The second and last unauthenticated-by-session route on this prefix. The
  // credential is a Firebase ID token rather than a password, and the answer is
  // one of exactly two things: a session, for a creator who already runs a
  // channel, or `needs_channel`, for one who is about to create it. The latter
  // carries no session, because an account that does not exist yet has nothing
  // to authenticate as.
  router.post('/auth/firebase', loginLimit, requireCreator(verifier), async (req, res) => {
    const result = await signInCreator(database, {
      identity: creatorOf(req),
      ipHash: hashIp(req.ip ?? 'unknown'),
      userAgent: req.header('user-agent') ?? null,
    });

    if (result.kind === 'needs_channel') {
      res.status(200).json({ needsChannel: true, email: result.email });
      return;
    }

    res.status(200).json({
      accessToken: result.session.accessToken,
      refreshToken: result.session.refreshToken,
      expiresInSeconds: result.session.expiresInSeconds,
      admin: result.session.admin,
      permissions: capabilitiesOf(result.session.admin.role),
    });
  });

  /**
   * A creator's first, and only, channel (§16).
   *
   * Authenticated by the Firebase identity itself rather than by a session,
   * because there is no session yet: this call is what brings the account into
   * existence. It answers with a signed-in session and the new channel, so the
   * app never sees the moment between "created" and "signed in" — those are the
   * same transaction here, and a client that had to sign in again afterwards
   * could fail in between and strand a channel with no way to reach it.
   */
  router.post('/creator/channel', loginLimit, write, requireCreator(verifier), async (req, res) => {
    const body = parseBody(CreateCreatorChannelSchema, req.body);
    const created = await createCreatorChannel(database, {
      identity: creatorOf(req),
      name: body.name,
      description: body.description ?? undefined,
      categorySlug: body.categorySlug ?? undefined,
      countryCode: body.countryCode ?? undefined,
      ipHash: hashIp(req.ip ?? 'unknown'),
      userAgent: req.header('user-agent') ?? null,
    });

    res.status(201).json({
      accessToken: created.session.accessToken,
      refreshToken: created.session.refreshToken,
      expiresInSeconds: created.session.expiresInSeconds,
      admin: created.session.admin,
      permissions: capabilitiesOf(created.session.admin.role),
      channel: await signedChannel(store, created.channel),
    });
  });

  router.post('/auth/refresh', async (req, res) => {
    const body = parseBody(RefreshSchema, req.body);
    const session = await refreshAdminSession(
      database,
      body.refreshToken,
      hashIp(req.ip ?? 'unknown')
    );
    res.status(200).json({
      accessToken: session.accessToken,
      refreshToken: session.refreshToken,
      expiresInSeconds: session.expiresInSeconds,
      admin: session.admin,
      permissions: capabilitiesOf(session.admin.role),
    });
  });

  router.post('/auth/logout', async (req, res) => {
    const body = parseBody(RefreshSchema, req.body);
    res.status(200).json({ signedOut: await logoutAdmin(database, body.refreshToken) });
  });

  /** Who am I, and what may I do? The app's only source of capabilities. */
  router.get('/auth/me', requireAdmin(database, 'channels.read'), async (req, res) => {
    const context = adminOf(req);
    res.status(200).json({
      admin: context.account,
      permissions: capabilitiesOf(context.role),
    });
  });

  // ── Channels (§17, §18, §19, §20) ────────────────────────────────────

  /**
   * The channels this account may publish to.
   *
   * A super administrator sees all of them; a channel administrator sees the
   * one they are bound to. The restriction is in the WHERE clause of the query,
   * so it is not a filter a route could forget.
   */
  router.get('/channels', requireAdmin(database, 'channels.read'), async (req, res) => {
    const context = adminOf(req);
    res.status(200).json({
      channels: await listChannelsForAdmin(database, store, scopeOf(context)),
    });
  });

  router.get('/channels/:channelId', requireAdmin(database, 'channels.read'), async (req, res) => {
    const context = adminOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    const channel = await requireChannelScope(database, scopeOf(context), channelId);
    res.status(200).json({
      channel: mapChannel(channel, true, await signObjectUrl(store, channel.icon_object_key)),
    });
  });

  /**
   * Create a channel, and in the same transaction the login that runs it (§20).
   *
   * The order is deliberate: the administrator row references the channel, so
   * the channel must exist first, and both must succeed together — a channel
   * with no administrator would be one nobody could publish to, and an
   * administrator bound to no channel could sign in and do nothing.
   */
  router.post('/channels', requireAdmin(database, 'channels.create'), write, async (req, res) => {
    const context = adminOf(req);
    const body = parseBody(CreateChannelSchema, req.body);

    const hasEmail = Boolean(body.adminEmail?.trim());
    const hasPassword = Boolean(body.adminPassword?.trim());
    if (hasEmail !== hasPassword) {
      throw badRequest(
        'admin_credentials_incomplete',
        'Provide both an administrator email and password, or neither.'
      );
    }

    const created = await database.transaction(async (tx) => {
      const channel = await createChannel(tx, {
        name: body.name,
        description: body.description ?? undefined,
        categorySlug: body.categorySlug ?? undefined,
        countryCode: body.countryCode ?? undefined,
      });

      let admin: AdminAccount | null = null;
      if (hasEmail) {
        admin = await createAdmin(tx, {
          displayName: body.adminDisplayName?.trim() || channel.name,
          email: body.adminEmail ?? '',
          role: 'channel_admin',
          password: body.adminPassword ?? '',
          channelId: channel.id,
          createdByAdminId: context.adminId,
        });
      }

      // The image is claimed inside the SAME transaction as the channel's
      // creation: a channel that exists because its form was submitted, but
      // without the image that was chosen on it, is not what the administrator
      // asked for. The claim can only replace an icon that already exists, so
      // the returned key is always null here — a brand-new channel has none.
      let replacedObjectKey: string | null = null;
      if (body.iconMediaId) {
        replacedObjectKey = await applyChannelIcon(
          tx,
          context.adminId,
          channel.id,
          body.iconMediaId
        );
      }

      return { channel, admin, replacedObjectKey };
    });

    await removeObjectQuietly(store, created.replacedObjectKey);

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'channel.create',
      targetType: 'channel',
      targetId: created.channel.id,
      metadata: {
        slug: created.channel.slug,
        adminEmail: created.admin?.email ?? null,
      },
      ipHash: hashIp(req.ip ?? 'unknown'),
    });

    // The administrator's password is never echoed, and the hash is not part of
    // AdminAccount either.
    res.status(201).json({
      channel: await signedChannel(store, created.channel),
    });
  });

  router.patch('/channels/:channelId', requireAdmin(database, 'channels.update'), write, async (req, res) => {
    const context = adminOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    await requireChannelScope(database, scopeOf(context), channelId);

    const body = parseBody(UpdateChannelSchema, req.body);

    // One transaction across the profile fields and the image. An edit that
    // changed a name and then failed on the image would leave the administrator
    // looking at a form that half-applied, and no way to tell which half.
    const { channel, replacedObjectKey, passwordAdminId } = await database.transaction(
      async (tx) => {
        const row = await updateChannel(tx, channelId, {
          name: body.name,
          description: body.description,
          categorySlug: body.categorySlug,
          countryCode: body.countryCode,
        });

        // Absent means "leave the image alone"; null means "remove it"; a uuid
        // means "adopt this upload". Distinguished here rather than by a separate
        // endpoint, so the form has one Save that does the whole edit.
        let replaced: string | null = null;
        if (body.iconMediaId === null) {
          replaced = await clearChannelIconIn(tx, channelId);
        } else if (typeof body.iconMediaId === 'string') {
          replaced = await applyChannelIcon(tx, context.adminId, channelId, body.iconMediaId);
        }

        // The password changes IN the same transaction as the rest of the edit,
        // for the same reason the image does: a form that said it saved and left
        // the password on the old value is worse than one that failed.
        const changedAdminId =
          body.adminPassword === undefined
            ? null
            : await changeChannelAdminPassword(tx, {
                channelId,
                password: body.adminPassword,
                actorAdminId: context.adminId,
                actorIsSuperAdmin: context.role === 'super_admin',
              });

        return { channel: row, replacedObjectKey: replaced, passwordAdminId: changedAdminId };
      }
    );

    // After the commit, and never fatal — see [setChannelIcon].
    await removeObjectQuietly(store, replacedObjectKey);

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'channel.update',
      targetType: 'channel',
      targetId: channelId,
      // The image is recorded as a CHANGE rather than as its value: §30 forbids
      // logging anything that could be a credential, and an object key is the
      // sort of thing that ends up in a bug report.
      metadata: {
        fields: Object.keys(body),
        iconChanged: body.iconMediaId !== undefined,
        // Recorded as a CHANGE, never as a value (§30).
        adminPasswordChanged: passwordAdminId !== null,
      },
      ipHash: hashIp(req.ip ?? 'unknown'),
    });

    res.status(200).json({
      channel: await signedChannel(store, await loadChannelRow(database, { by: 'id', value: channel.id })),
    });
  });

  /**
   * Set or remove a channel's profile image on its own (§21).
   *
   * The form uses PATCH; this exists for a tap on the image itself, and so the
   * two operations have an obvious place to live when a client wants one and
   * not the other. The body is the same field, with the same three meanings.
   */
  router.put('/channels/:channelId/icon', requireAdmin(database, 'channels.update'), write, async (req, res) => {
    const context = adminOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    await requireChannelScope(database, scopeOf(context), channelId);

    const body = parseBody(ChannelIconSchema, req.body);

    if (body.iconMediaId === undefined || body.iconMediaId === null) {
      await clearChannelIcon(database, store, channelId);
    } else {
      await setChannelIcon(database, store, context.adminId, channelId, body.iconMediaId);
    }

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: body.iconMediaId ? 'channel.icon.set' : 'channel.icon.clear',
      targetType: 'channel',
      targetId: channelId,
      ipHash: hashIp(req.ip ?? 'unknown'),
    });

    res.status(200).json({
      channel: await signedChannel(store, await loadChannelRow(database, { by: 'id', value: channelId })),
    });
  });

  /** Switch a channel off, or back on (§17). Super administrators only. */
  router.post('/channels/:channelId/status', requireAdmin(database, 'channels.status'), write, async (req, res) => {
    const context = adminOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    const body = parseBody(z.object({ status: z.enum(['active', 'suspended']) }), req.body);

    const channel = await setChannelStatus(database, channelId, body.status);
    await writeAudit(database, {

      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: `channel.${body.status}`,
      targetType: 'channel',
      targetId: channelId,
      ipHash: hashIp(req.ip ?? 'unknown'),
    });

    res.status(200).json({ channel: await signedChannel(store, channel) });
  });

  /**
   * Delete a channel, with its posts, its media and the login created to run it
   * (§17). Super administrators only: a channel administrator holds no
   * `channels.delete`, so the account that runs a channel cannot destroy its
   * history.
   */
  router.delete('/channels/:channelId', requireAdmin(database, 'channels.delete'), write, async (req, res) => {
    const context = adminOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');

    // The rows go first, in one transaction; the bucket objects are named by the
    // keys this returns. The order matters and is not interchangeable — see
    // [deleteChannel].
    const objectKeys = await deleteChannel(database, channelId);
    for (const key of objectKeys) await removeObjectQuietly(store, key);

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'channel.delete',
      targetType: 'channel',
      targetId: channelId,
      ipHash: hashIp(req.ip ?? 'unknown'),
      // A count rather than the keys, for the same reason the icon change logs a
      // change and not its value: an object key is the sort of thing that ends
      // up pasted into a bug report.
      metadata: { objectsRemoved: objectKeys.length },
    });

    res.status(200).json({ deleted: true, channelId });
  });

  // ── Posts (§17, §18, §21) ────────────────────────────────────────────

  /**
   * A channel's posts, for the administrator who runs it.
   *
   * Deliberately not the public read: this answers for a suspended channel too,
   * so an administrator whose channel was switched off can still see what they
   * are being asked to fix.
   */
  router.get('/channels/:channelId/posts', requireAdmin(database, 'posts.read'), async (req, res) => {
    const context = adminOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    await requireChannelScope(database, scopeOf(context), channelId);

    const query = parseBody(PageQuerySchema, req.query);
    res.status(200).json(await listChannelPostsForAdmin(database, store, channelId, query));
  });

  /**
   * Publish a post (§21).
   *
   * Text, image, video and link posts are all this one route: the type follows
   * from what is attached, and a media-less post needs no bucket at all — which
   * is what keeps S3 optional (§22).
   */
  router.post('/channels/:channelId/posts', requireAdmin(database, 'posts.create'), write, async (req, res) => {
    const context = adminOf(req);
    const channelId = pathIdParam(req.params.channelId, 'invalid_channel_id');
    await requireChannelScope(database, scopeOf(context), channelId);

    const body = parseBody(PostInputSchema, req.body);
    const post = await publishPost(database, store, context.adminId, channelId, {
      body: body.body,
      linkUrl: body.linkUrl,
      linkTitle: body.linkTitle,
      mediaIds: body.mediaIds,
    });

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'post.create',
      targetType: 'post',
      targetId: post.id,
      metadata: { channelId, type: post.type, mediaCount: post.media.length },
      ipHash: hashIp(req.ip ?? 'unknown'),
    });

    res.status(201).json({ post });

    // The readers who follow this channel, told about it (§8).
    //
    // Deliberately AFTER the response and deliberately not awaited. The post is
    // committed and the publisher has been answered — a notification that cannot
    // reach a phone must never turn a successful publish into a failed request,
    // and a fan-out of a few hundred sends is not something a creator should
    // watch a spinner for. Nothing is swallowed silently: a failure is logged and
    // the app's own periodic check is the fallback.
    void announcePost(database, push, {
      channelId,
      postId: post.id,
      body: post.body,
      publishedAt: post.createdAt,
    }).catch((err: unknown) => {
      console.error('[push] announce failed:', (err as Error).message);
    });
  });

  /**
   * Edit a post's text or link (§21).
   *
   * The post's channel is resolved first and the scope checked against it, so a
   * channel administrator cannot reach another channel's post by naming its id.
   */
  router.patch('/posts/:postId', requireAdmin(database, 'posts.update'), write, async (req, res) => {
    const context = adminOf(req);
    const postId = pathIdParam(req.params.postId, 'invalid_post_id');
    const existing = await loadPostRow(database, postId);
    await requireChannelScope(database, scopeOf(context), existing.channel_id);

    const body = parseBody(UpdatePostSchema, req.body);
    const post = await updatePost(database, store, postId, {
      body: body.body,
      linkUrl: body.linkUrl,
      linkTitle: body.linkTitle,
    });

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'post.update',
      targetType: 'post',
      targetId: postId,
      metadata: { channelId: existing.channel_id },
      ipHash: hashIp(req.ip ?? 'unknown'),
    });

    res.status(200).json({ post });
  });

  /**
 * How many posts one multi-select delete may carry.
 *
 * The phone's gesture is "pick some, remove them", and a number here is what
 * stops that becoming an unbounded query in one request. A hundred is far past
 * what a person can select by hand and far below anything that would hurt.
 */
const BULK_DELETE_LIMIT = 100;

const BulkDeleteSchema = z.object({
  postIds: z.array(z.string().uuid()).min(1).max(BULK_DELETE_LIMIT)
});

/** Remove a post (§17). Soft, so it stays reversible. */
  router.delete('/posts/:postId', requireAdmin(database, 'posts.delete'), write, async (req, res) => {
    const context = adminOf(req);
    const postId = pathIdParam(req.params.postId, 'invalid_post_id');
    const existing = await loadPostRow(database, postId);
    await requireChannelScope(database, scopeOf(context), existing.channel_id);

    await deletePost(database, postId);

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'post.delete',
      targetType: 'post',
      targetId: postId,
      metadata: { channelId: existing.channel_id },
      ipHash: hashIp(req.ip ?? 'unknown'),
    });

    res.status(200).json({ deleted: true, postId });
  });

  /**
   * Remove several posts in one request (§17).
   *
   * The select-and-remove gesture is ONE action to the person doing it, and
   * doing it as N requests would make it N token checks, N audit rows, and a
   * half-applied selection the moment one of them failed — with no way to tell
   * which half. So the whole selection is checked and applied together, and a
   * post the caller may not touch refuses all of it rather than quietly skipping
   * the ones that were out of reach.
   *
   * The scope is proved per post against the caller's own channel binding, never
   * against a channel id from the body — the same rule the single delete follows,
   * because a bulk endpoint is not a way to ask a different question.
   */
  router.post('/posts/bulk-delete', requireAdmin(database, 'posts.delete'), write, async (req, res) => {
    const context = adminOf(req);
    const body = parseBody(BulkDeleteSchema, req.body);
    const scope = scopeOf(context);

    // Deduplicated first: a selection cannot legitimately name the same post
    // twice, and the audit row should count posts rather than taps.
    const postIds = [...new Set(body.postIds)];

    for (const postId of postIds) {
      const existing = await loadPostRow(database, postId);
      await requireChannelScope(database, scope, existing.channel_id);
    }

    await database.transaction(async (tx) => {
      for (const postId of postIds) await deletePost(tx, postId);
    });

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'post.delete_many',
      targetType: 'post',
      // No single target, so the row records the set it removed rather than
      // pretending one of them is the subject.
      targetId: postIds.join(','),
      metadata: { count: postIds.length },
      ipHash: hashIp(req.ip ?? 'unknown'),
    });

    res.status(200).json({ deleted: postIds.length, postIds });
  });

  // ── Media (§21, §22) ─────────────────────────────────────────────────

  /**
   * Ask for a presigned upload URL.
   *
   * Three steps, in the order the composer uses them: presign, PUT the file to
   * the bucket, confirm. No AWS credential crosses this boundary — only a
   * capability scoped to one object, one method and a short expiry — and the
   * object key is derived from a uuid the server generates, so a request cannot
   * choose where its bytes land.
   *
   * Answered with `media_unavailable` (503) on a deployment with no bucket,
   * which is a state the app words explicitly rather than a generic failure:
   * text and link posts still work there (§22).
   */
  router.post('/media/uploads', requireAdmin(database, 'media.upload'), write, async (req, res) => {
    const context = adminOf(req);
    const body = parseBody(UploadRequestSchema, req.body);
    const upload = await requestMediaUpload(database, store, context.adminId, body);
    res.status(201).json({ upload });
  });

  /**
   * Confirm the file arrived, by asking the bucket rather than the client.
   *
   * Idempotent: a client that retried after a dropped response is not punished
   * for having got through the first time. A row only becomes claimable once a
   * HEAD has found the object, so a publish can never attach an asset that was
   * never uploaded.
   */
  router.post('/media/uploads/:mediaId/confirm', requireAdmin(database, 'media.upload'), write, async (req, res) => {
    const context = adminOf(req);
    const mediaId = pathIdParam(req.params.mediaId, 'invalid_media_id');
    const media = await confirmMediaUpload(database, store, context.adminId, mediaId);
    res.status(200).json({ media });
  });

  // ── Administrators (§17, §27) ────────────────────────────────────────

  router.get('/admins', requireAdmin(database, 'admins.read'), async (_req, res) => {
    res.status(200).json({ items: await listAdmins(database) });
  });

  /**
   * Create a channel administrator (§18).
   *
   * Takes a channel id rather than a role: every account this route can create
   * is bound to one channel, which is the whole of §18. Super administrators
   * come from the deployment's configuration (§17), never from here.
   */
  router.post('/admins', requireAdmin(database, 'admins.manage'), write, async (req, res) => {
    const context = adminOf(req);
    const body = parseBody(CreateAdminSchema, req.body);

    // The channel must exist before it can be administered.
    await loadChannelRow(database, { by: 'id', value: body.channelId });

    const created = await createAdmin(database, {
      displayName: body.displayName,
      email: body.email,
      role: 'channel_admin',
      password: body.password,
      channelId: body.channelId,
      createdByAdminId: context.adminId,
    });

    await writeAudit(database, {
      adminId: context.adminId,
      adminEmail: context.account.email,
      actorRole: context.role,
      action: 'admin.create',
      targetType: 'admin',
      targetId: created.id,
      metadata: { role: created.role, email: created.email, channelId: body.channelId },
      ipHash: hashIp(req.ip ?? 'unknown'),
    });

    res.status(201).json({ admin: created });
  });

  /**
   * Disable or re-enable an administrator (§27).
   *
   * Disabling yourself is refused: it is not moderation, it is a mistake, and
   * with the last super administrator it is unrecoverable.
   */
  router.post('/admins/:adminId/status', requireAdmin(database, 'admins.manage'), write, async (req, res) => {
    const context = adminOf(req);
    const adminId = pathIdParam(req.params.adminId, 'invalid_admin_id');
    const body = parseBody(AdminStatusSchema, req.body);

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
      ipHash: hashIp(req.ip ?? 'unknown'),
    });

    res.status(200).json(result);
  });

  router.post('/admins/:adminId/sessions/revoke', requireAdmin(database, 'admins.manage'), write, async (req, res) => {
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
      ipHash: hashIp(req.ip ?? 'unknown'),
    });

    res.status(200).json({ revoked });
  });

  // ── Audit and settings (§22, §29) ────────────────────────────────────

  router.get('/audit', requireAdmin(database, 'audit.read'), async (req, res) => {
    const query = parseBody(AuditQuerySchema, req.query);
    const limit = Number(query.limit ?? '100');
    res.status(200).json({
      items: await listAudit(database, Number.isFinite(limit) && limit > 0 ? Math.min(limit, 200) : 100),
    });
  });

  /**
   * Effective, NON-SECRET configuration.
   *
   * Read-only on purpose: every setting that matters here is an environment
   * variable on the host, and a dashboard that changed them would be a second,
   * unaudited source of truth for how the deployment behaves.
   */
  router.get('/settings', requireAdmin(database, 'channels.read'), async (req, res) => {
    const context = adminOf(req);
    res.status(200).json({
      settings: {
        editWindowDays: env.EDIT_WINDOW_DAYS,
        maxTextLength: env.MAX_TEXT_LENGTH,
        maxPostMedia: env.MAX_POST_MEDIA,
        defaultPageSize: env.DEFAULT_PAGE_SIZE,
        maxPageSize: env.MAX_PAGE_SIZE,
        maxChannelNameLength: env.MAX_CHANNEL_NAME_LENGTH,
        maxChannelDescriptionLength: env.MAX_CHANNEL_DESCRIPTION_LENGTH,
        // The app uses this to decide whether to offer the media picker at all,
        // and to word `media_unavailable` honestly when it is off (§22).
        mediaEnabled: store.configured,
        maxUploadBytes: env.S3_MAX_UPLOAD_BYTES,
        contactEmail: env.ADMIN_CONTACT_EMAIL,
      },
      role: context.role,
    });
  });

  return router;
}

/** Re-exported for the app: the media summary shape a confirm step returns. */
export type { MediaSummary };

/**
 * A channel as an administrator sees it, or a 404.
 *
 * Unused by the routes above — they reach [requireChannelScope] directly — and
 * kept because it is the documented entry point for a caller that has only an
 * id and wants the scope check in one step.
 */
export async function loadScopedChannel(
  database: Queryable,
  context: AdminContext,
  channelId: string
): Promise<ChannelRow> {
  return requireChannelScope(database, scopeOf(context), channelId);
}

/**
 * A channel row as a payload, with its image signed.
 *
 * One helper for the single-channel responses so none of them can come back
 * with an icon that a LIST response would have included: a channel whose avatar
 * appears after a refresh but not after a save is the kind of inconsistency that
 * gets blamed on the network.
 */
async function signedChannel(store: ObjectStore, row: ChannelRow) {
  return mapChannel(row, true, await signObjectUrl(store, row.icon_object_key));
}

/** A post's channel, for a caller that needs the scope check without the post. */
export async function channelOfPost(database: Queryable, postId: string): Promise<string> {
  const row = await loadPostRow(database, postId);
  if (!row) throw notFound('post_not_found');
  return row.channel_id;
}
