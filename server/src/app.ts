import express, { type NextFunction, type Request, type Response } from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { env, isProduction } from './env.js';
import { db, pingDatabase, type Queryable } from './db.js';
import { ApiError } from './http/errors.js';
import { buildPublicRouter } from './public/routes.js';
import { buildShareRouter } from './public/share.js';
import { buildReadersRouter } from './readers/routes.js';
import { buildAdminRouter } from './admin/routes.js';
import { createFirebaseVerifier, type IdentityVerifier } from './identity/verifier.js';
import { createObjectStore, type ObjectStore } from './media/store.js';
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
 * binding a port, and so both route surfaces mount in one obvious place: the
 * anonymous reader API under `/api/v1` and the administrator API under
 * `/admin/api`.
 */

/**
 * Deliberately small. Post media goes straight to S3 with a presigned URL, so
 * nothing binary ever passes through this process — only JSON. A 256 KB cap
 * means a malicious client cannot make the API buffer megabytes per request.
 */
export const JSON_BODY_LIMIT = '256kb';

/**
 * One line per request, so a failure reported from a device can be found.
 *
 * This exists because of a real case: the app said "Something went wrong" and
 * there was nothing on the server to look at. The error handler logged 5xx
 * only, so the 4xx a client actually hits — `invalid_token`, `creator_required`,
 * `email_taken` — left no trace anywhere, and a client's own wording cannot
 * tell those apart from the outside.
 *
 * The CODE is what is logged rather than the message: it is the machine-readable
 * half, it is what the Android client branches on, and it is what names the
 * condition.
 *
 * Deliberately no query string, no body and no headers. A query can be a reader's
 * search term and an admin body carries a password, so neither belongs in a log;
 * the path and the code are enough to identify a failure. Successful requests
 * are logged too, because "did it even arrive?" is the first question and
 * silence cannot answer it.
 *
 * `/health` is skipped: Render polls it, and a line every 30 seconds is noise
 * that buries the ones worth reading.
 */
function requestLogger(req: Request, res: Response, next: NextFunction): void {
  if (req.path === '/health' || req.path === '/health/db') {
    next();
    return;
  }

  const startedAt = Date.now();
  // `originalUrl` rather than `path`: Express rewrites `req.url` while a
  // request is inside a mounted router, so `path` read at finish time can be
  // missing the mount prefix — a line reading `GET /channels` when the caller
  // asked for `/api/v1/channels` is worse than useless in a log that is only
  // ever read to match one against the other. The query string is cut off
  // explicitly, which is also what keeps a reader's search term out of it.
  const path = req.originalUrl.split('?')[0];
  res.on('finish', () => {
    const code = typeof res.locals.errorCode === 'string' ? ` ${res.locals.errorCode}` : '';
    const line = `[req] ${req.method} ${path} ${res.statusCode}${code} ${Date.now() - startedAt}ms`;
    if (res.statusCode >= 500) console.error(line);
    else if (res.statusCode >= 400) console.warn(line);
    else console.log(line);
  });

  next();
}

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
    // Recorded for the request log below, which is where a failure reported
    // from a device is looked for. Every code is recorded, not just the 5xx
    // ones: a 401 or a 409 is the answer a user is most likely to be asking
    // about, and those were previously leaving no trace at all.
    res.locals.errorCode = err.type;
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
  res.locals.errorCode = isServerFault ? 'internal_error' : (error?.type ?? 'invalid_request');

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
 * and the real S3 client; the suite passes a PGlite database (a genuine
 * Postgres compiled to WASM) and a fake object store, so both the schema and
 * the upload handshake can be driven end to end without a network call or an
 * AWS account.
 */
export interface AppDeps {
  readonly database?: Queryable;
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
   * Who is calling (§3). Overridable so the suite can verify against a key pair
   * it generated, and so the whole reader surface can be exercised end to end
   * without a Firebase project — including the routes that must refuse when
   * verification is unavailable.
   */
  readonly verifier?: IdentityVerifier;
}

export function buildApp(deps: AppDeps = {}): express.Express {
  const database = deps.database ?? db;
  const rateLimits = deps.rateLimits ?? rateLimitConfigFromEnv();
  const store = deps.store ?? createObjectStore();
  // Configured from a PUBLIC value — the Firebase project id, which is the
  // token's audience. There is no Firebase credential in this deployment: a
  // deployment that has not set the project id refuses reader-scoped routes
  // with `auth_unavailable` rather than trusting anything a client says.
  const verifier = deps.verifier ?? createFirebaseVerifier(env.FIREBASE_PROJECT_ID);

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
  // There is no browser client — no admin dashboard, no public web page — so
  // CORS_ORIGINS has nothing to allow unless one is added later.
  app.use(
    cors({
      origin: env.CORS_ORIGINS.length > 0 ? env.CORS_ORIGINS : false,
    })
  );

  app.use(express.json({ limit: JSON_BODY_LIMIT }));

  // Before every route, so its `finish` listener is attached to all of them.
  app.use(requestLogger);

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
  //
  // Two prefixes, and the split is the product (§24): `/api/v1` is everything a
  // reader may fetch without an account, `/admin/api` is everything an
  // administrator may change with one. There is no third surface, because there
  // is no viewer account to build one for.
  //
  // Mounted on the API path rather than app-wide so the health endpoints are
  // exempt: Render polls them, and a rate-limited health check reads as a dead
  // service and triggers a restart loop.
  app.use('/api/v1', rateLimit(limiter, rateLimits.global));

  // ── Public reads (§24) ────────────────────────────────────────────────
  // Anonymous, read-only, and the only surface a phone touches on cold start —
  // which is why it is mounted first (§26).
  app.use('/api/v1', buildPublicRouter(database, store));

  // ── Reader state (§3–§6) ──
  // The same prefix, because it is the same client and the same product, but
  // every route under it requires a verified Firebase token while the reads
  // above require nothing. Mounted after the public router so a static public
  // path can never be shadowed by a reader path of the same shape.
  app.use('/api/v1/readers', buildReadersRouter(database, store, verifier, limiter, rateLimits));

  // Mounted even when verification is unavailable, and the routes answer for
  // themselves: an unconfigured deployment returns a described 503 from each
  // one, where unmounting would return a 404 that reads like this API version
  // simply does not have those routes. Said out loud once at build time,
  // because the difference between "degraded" and "not deployed" is otherwise
  // only visible from inside the app.
  if (!verifier.configured) {
    console.warn(
      '[api] FIREBASE_PROJECT_ID is not set: reader sign-in cannot be verified, so follows, ' +
        'unread badges and mutes are unavailable. Public reading is unaffected.'
    );
  }

  // ── The shared-channel page (§6) ───────────────────────────────────────
  //
  // Deliberately NOT under `/api/v1`. This is the one thing here that a browser
  // — a recipient of a shared link, with no app installed and no idea what
  // ClearView is — is meant to open, so it gets a short path a human might type
  // and it answers with HTML rather than JSON.
  //
  // Rate limited by the same global rule as the API: the page runs two queries,
  // and an unfurled link is fetched by every messaging app it is pasted into.
  // `/health` stays exempt, for the reason it does everywhere else.
  app.use('/c', rateLimit(limiter, rateLimits.global));
  app.use('/c', buildShareRouter(database, store));

  // ── Platform administration (§16–§21, §25, §27) ───────────────────────
  //
  // A DIFFERENT PATH PREFIX from every reader endpoint, and a different token
  // audience and signing key behind it. No reader request can satisfy these
  // routes and no admin token can satisfy `/api/v1`, so the two surfaces cannot
  // be reached from each other by guessing a path.
  //
  // The global rule applies here too — the admin API is not exempt from rate
  // limiting — and `/admin/api/auth/login` additionally takes the tighter auth
  // rule inside the router.
  app.use('/admin/api', rateLimit(limiter, rateLimits.global));
  app.use('/admin/api', buildAdminRouter(database, store, limiter, rateLimits, verifier));

  app.use(notFound);
  app.use(errorHandler);

  return app;
}
