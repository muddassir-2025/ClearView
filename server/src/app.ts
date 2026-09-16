import express, { type NextFunction, type Request, type Response } from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { env, isProduction } from './env.js';
import { db, pingDatabase, type Queryable } from './db.js';
import { ApiError } from './http/errors.js';
import { buildAuthRouter } from './auth/routes.js';
import { createMailer, type Mailer } from './email/sender.js';
import { buildChannelsRouter, buildDiscoverRouter } from './channels/routes.js';
import { buildChannelPostsRouter, buildPostsRouter } from './posts/routes.js';
import { buildMediaRouter } from './media/routes.js';
import { buildEngagementRouter } from './engagement/routes.js';
import { buildModerationRouter } from './moderation/routes.js';
import { buildNotificationsRouter } from './notifications/routes.js';
import { createPushSender, type PushSender } from './notifications/push.js';
import { buildAdminRouter } from './admin/routes.js';
import { buildDashboardRouter } from './admin/dashboard.js';
import { createObjectStore, type ObjectStore } from './media/store.js';
import { createPhoneVerifier, type PhoneIdentityVerifier } from './auth/firebase.js';
import {
  FixedWindowRateLimiter,
  rateLimit,
  rateLimitConfigFromEnv,
  type RateLimitConfig,
} from './http/rateLimit.js';

/**
 * The Express app, built but not listening.
 *
 * Kept separate from `index.ts` so tests can drive it with supertest without
 * binding a port, and so route modules added in later milestones mount in one
 * obvious place.
 */

/**
 * Deliberately small. Post media goes straight to S3 with a presigned URL, so
 * nothing binary ever passes through this process — only JSON. A 256 KB cap
 * means a malicious client cannot make the API buffer megabytes per request.
 */
export const JSON_BODY_LIMIT = '256kb';

type HttpError = Error & { status?: number; statusCode?: number; type?: string };

function notFound(_req: Request, res: Response): void {
  res.status(404).json({ error: 'not_found' });
}

/**
 * Central error handler.
 *
 * Anything thrown by a route (including JSON-parse failures from the body
 * parser, which arrive here as errors carrying a `status`) lands here, so no
 * stack trace and no internal message ever reaches a client in production.
 *
 * The status is honoured rather than flattened to 500: a malformed body is a
 * 400 and an oversized one is a 413, and reporting those as 500 would both
 * mislead the client and pollute error monitoring with self-inflicted faults.
 * Anything outside 4xx/5xx is clamped, so a bad `status` value on an injected
 * error cannot produce a nonsensical response code.
 *
 * A thrown [ApiError] keeps its own `type` even for a 5xx. Those are raised
 * deliberately — `media_unavailable` is "this deployment has no bucket",
 * `auth_unavailable` is "verification is switched off" — and the Android client
 * branches on the code to word each one. Flattening them to `internal_error`
 * would turn a condition the user can be told about into a generic server
 * fault. The message is still withheld in production; only the code is kept.
 */
function errorHandler(err: unknown, _req: Request, res: Response, _next: NextFunction): void {
  if (err instanceof ApiError) {
    if (err.status >= 500) console.error(`[api] ${err.type}: ${err.message}`);
    res.status(err.status).json({
      error: err.type,
      ...(isProduction ? {} : { detail: err.message }),
    });
    return;
  }

  const error = err as HttpError;
  const raw = error?.status ?? error?.statusCode ?? 500;
  const status = raw >= 400 && raw <= 599 ? raw : 500;
  const isServerFault = status >= 500;

  if (isServerFault) {
    console.error('[api] unhandled error:', error?.message);
  }

  res.status(status).json({
    error: isServerFault ? 'internal_error' : (error?.type ?? 'invalid_request'),
    // Detail is a development aid only. In production a server fault must not
    // describe the internals it failed in.
    ...(isProduction || isServerFault ? {} : { detail: error?.message }),
  });
}

/**
 * Overridable collaborators. Production passes nothing and gets the real pool
 * and the real Firebase verifier; the suite passes a PGlite database and a
 * fake verifier so the auth flow can be driven end-to-end without a network
 * call or a service account.
 */
export interface AppDeps {
  readonly database?: Queryable;
  readonly verifier?: PhoneIdentityVerifier;
  /** Overridable so the suite can assert limiting with tiny windows. */
  readonly rateLimits?: RateLimitConfig;
  /**
   * Object storage for post media. Overridable so the whole upload lifecycle
   * (presign → upload → confirm → claim) can be driven against a fake — the
   * checks that matter are ours, and requiring an AWS account to test them
   * would mean they were never tested.
   */
  readonly store?: ObjectStore;
  /**
   * Outbound email for email sign-in. Overridable for the same reason as the
   * verifier: the flow's own rules (hashed codes, attempt limits, no
   * enumeration) must be testable without a provider account and without a
   * single message leaving the process.
   */
  readonly mailer?: Mailer;
  /**
   * Push delivery (§17). Overridable for the same reason as the store: the
   * rules that matter — who is notified, who is excluded, what is deduped — are
   * ours, and a suite that needed a device to test them would not test them.
   */
  readonly push?: PushSender;
}

export function buildApp(deps: AppDeps = {}): express.Express {
  const database = deps.database ?? db;
  const verifier = deps.verifier ?? createPhoneVerifier();
  const rateLimits = deps.rateLimits ?? rateLimitConfigFromEnv();
  const store = deps.store ?? createObjectStore();
  const mailer = deps.mailer ?? createMailer();
  const push = deps.push ?? createPushSender();

  // One limiter for every rule: buckets are namespaced by rule name, so a
  // shared instance keeps one bounded structure instead of several.
  const limiter = new FixedWindowRateLimiter();

  const app = express();

  // Render terminates TLS and proxies to us, so the real client IP arrives in
  // X-Forwarded-For. Without this every request looks like it came from the
  // proxy and per-IP rate limiting collapses into one global bucket.
  app.set('trust proxy', 1);
  app.disable('x-powered-by');

  app.use(helmet());

  // The Android client sends no Origin header, so an empty allow-list is the
  // correct default: browsers are refused and the native app is unaffected.
  // The admin dashboard's origin is added via CORS_ORIGINS in M6.
  app.use(
    cors({
      origin: env.CORS_ORIGINS.length > 0 ? env.CORS_ORIGINS : false,
    })
  );

  app.use(express.json({ limit: JSON_BODY_LIMIT }));

  // ── Health ────────────────────────────────────────────────────────────
  // Two endpoints with distinct jobs. /health answers "is the process
  // serving?" for Render's health check; /health/db separates "the API is up
  // but the database is unreachable" from "the API is down".
  //
  // /health must NOT depend on Neon: Render restarts a service whose health
  // check fails, so tying it to the database would turn a brief Neon suspend
  // into a restart of a perfectly healthy process.
  app.get('/health', (_req: Request, res: Response) => {
    res.json({
      ok: true,
      service: 'clearview-goodpost',
      uptimeSeconds: Math.round(process.uptime()),
    });
  });

  app.get('/health/db', async (_req: Request, res: Response) => {
    const ok = await pingDatabase();
    res.status(ok ? 200 : 503).json({ ok, db: ok ? 'up' : 'down' });
  });

  // ── API routes ────────────────────────────────────────────────────────
  // Good Post auth (M1). Channels (M2), posts + media (M3), engagement (M4),
  // moderation (M5) and the admin API (M6) mount here alongside it.
  //
  // Versioned and namespaced so it cannot collide with the pre-existing
  // moderation API (`/api/rules`, `/api/channels/check`) the Block tab calls —
  // "channel" means a moderated YouTube channel there and a broadcast feed
  // here, and the two must never share a path.
  // Mounted on the API path rather than app-wide so the health endpoints are
  // exempt: Render polls them, and a rate-limited health check reads as a dead
  // service and triggers a restart loop.
  app.use('/api/v1', rateLimit(limiter, rateLimits.global));
  app.use('/api/v1/auth', rateLimit(limiter, rateLimits.auth));

  app.use('/api/v1/auth', buildAuthRouter(database, verifier, mailer));

  // Engagement (M4). Registered before the channel and post routers because it
  // answers paths under both prefixes (`/posts/:id/reactions`,
  // `/channels/:id/analytics`); matching first keeps a reaction from being
  // seen by the posts router, whose blanket `requireAuth` would then verify the
  // same session twice. The suite asserts neither router swallows the other's
  // paths rather than assuming it.
  app.use('/api/v1', buildEngagementRouter(database, limiter, rateLimits));

  // Moderation and private messaging (M5). Same shared prefix and the same
  // registration-order reasoning as engagement above: `/channels/:id/conversations`
  // belongs to this router even though it lives under the channel prefix.
  app.use('/api/v1', buildModerationRouter(database, limiter, rateLimits, push));

  // Notifications (M7/M9). The inbox and device registration sit on the same
  // prefix as everything else user-facing; the fan-out itself is triggered from
  // the publish, message and admin-message paths.
  app.use('/api/v1', buildNotificationsRouter(database, limiter, rateLimits));

  // ── Platform administration (M6, §20–§30) ─────────────────────────────
  //
  // A DIFFERENT PATH PREFIX from every user endpoint, and a different token
  // audience and signing key behind it (§48). No user token can satisfy these
  // routes and no admin token can satisfy `/api/v1`, so the two surfaces cannot
  // be reached from each other by guessing a path.
  //
  // The global rule applies here too — the admin API is not exempt from rate
  // limiting — and `/admin/api/auth/login` additionally takes the tighter auth
  // rule inside the router.
  app.use('/admin/api', rateLimit(limiter, rateLimits.global));
  app.use('/admin/api', buildAdminRouter(database, limiter, rateLimits, push));

  // The dashboard itself: server-rendered HTML, its own CSS and JS served from
  // this origin. Same process, same API, no second framework (§31), and no
  // CDN or inline script — helmet's default CSP then applies unmodified.
  app.use('/admin', buildDashboardRouter());

  // Channels and discovery (M2). Both take the same limiter instance, so the
  // `write` rule shares one bounded bucket structure with the other rules
  // instead of each router carrying its own window for the same rule name.
  // Posts and media (M3). Every router here is self-contained: each applies its
  // own `requireAuth`, so none can be mounted somewhere and silently lose
  // authentication.
  //
  // The channel-scoped post routes mount on the SAME `/api/v1/channels` prefix
  // as the channel router, registered BEFORE it. Express matches in order and
  // mounts no route for a two-segment `/x/posts` path, so a post request is
  // answered here without ever entering the channel router — which is what
  // keeps it to one session verification per request. The channel router's
  // blanket `requireSession` would otherwise run first for every post request
  // and verify the same token twice. The suite asserts that neither router
  // swallows the other's paths rather than assuming it.
  app.use('/api/v1/channels', buildChannelPostsRouter(database, store, limiter, rateLimits, push));
  app.use('/api/v1/channels', buildChannelsRouter(database, limiter, rateLimits));
  app.use('/api/v1/discover', buildDiscoverRouter(database));
  app.use('/api/v1/posts', buildPostsRouter(database, store));
  app.use('/api/v1/media', buildMediaRouter(database, store, limiter, rateLimits));

  app.use(notFound);
  app.use(errorHandler);

  return app;
}
