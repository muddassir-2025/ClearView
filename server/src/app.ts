import express, { type NextFunction, type Request, type Response } from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { env, isProduction } from './env.js';
import { db, pingDatabase, type Queryable } from './db.js';
import { buildAuthRouter } from './auth/routes.js';
import { buildChannelsRouter, buildDiscoverRouter } from './channels/routes.js';
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
 */
function errorHandler(err: unknown, _req: Request, res: Response, _next: NextFunction): void {
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
}

export function buildApp(deps: AppDeps = {}): express.Express {
  const database = deps.database ?? db;
  const verifier = deps.verifier ?? createPhoneVerifier();
  const rateLimits = deps.rateLimits ?? rateLimitConfigFromEnv();

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

  app.use('/api/v1/auth', buildAuthRouter(database, verifier));

  // Channels and discovery (M2). Both take the same limiter instance, so the
  // `write` rule shares one bounded bucket structure with the other rules
  // instead of each router carrying its own window for the same rule name.
  app.use('/api/v1/channels', buildChannelsRouter(database, limiter, rateLimits));
  app.use('/api/v1/discover', buildDiscoverRouter(database));

  app.use(notFound);
  app.use(errorHandler);

  return app;
}
