import type { NextFunction, Request, RequestHandler, Response } from 'express';
import { env } from '../env.js';

/**
 * Per-IP rate limiting.
 *
 * The knobs are RATE_LIMIT_MAX, AUTH_RATE_LIMIT_MAX and WRITE_RATE_LIMIT_MAX,
 * and `describeActiveLimits()` reports at boot exactly which are in force and
 * which are still reserved — so an operator's log can never imply protection
 * that is absent. A limit that is configured and not enforced is worse than no
 * limit at all, because it is believed.
 *
 * Three limits are applied:
 *
 *  - `global` — every request to either surface, reader or administrator, from
 *    RATE_LIMIT_MAX. Mounted in `app.ts` so the health endpoints are exempt:
 *    Render polls them, and a rate-limited health check reads as a dead service
 *    and triggers a restart loop.
 *  - `auth`   — the administrator sign-in routes specifically, from
 *    AUTH_RATE_LIMIT_MAX. Tighter because it is the one place a password can be
 *    guessed.
 *  - `write`  — every state-changing administrator route: publishing, editing,
 *    deleting, uploading. From WRITE_RATE_LIMIT_MAX.
 *
 * /health is deliberately NOT limited. Render polls it to decide whether the
 * service is alive, so rate limiting it would turn a burst of legitimate
 * health checks into failed checks, and a failed health check into a restart
 * loop. That is a self-inflicted outage, and it is the reason the global
 * limiter is mounted on the API path rather than app-wide.
 *
 * Known limitation, stated plainly: buckets live in this process's memory, so
 * the limit is per instance. On Render's single `starter` instance that is the
 * whole truth. If this ever scales past one instance, each instance would
 * allow the full allowance and the effective limit multiplies by the instance
 * count — at which point this needs a shared counter (the database or a Redis
 * instance) rather than a Map.
 */

export interface RateLimitRule {
  /** Namespace for the bucket, so two rules never share a counter. */
  readonly name: string;
  readonly windowMs: number;
  readonly max: number;
}

export interface RateLimitConfig {
  readonly global: RateLimitRule;
  /**
   * Signing in (§16). Tighter than the global rule, and the one limit that
   * protects the only credential in the product: an administrator's password.
   * There is no per-viewer authentication left to limit.
   */
  readonly auth: RateLimitRule;
  /**
   * Every state-changing administrator route: publishing, editing, deleting and
   * the media upload handshake.
   */
  readonly write: RateLimitRule;
}

export interface RateLimitDecision {
  readonly allowed: boolean;
  readonly remaining: number;
  /** Seconds until the window resets — the `Retry-After` value. */
  readonly retryAfterSeconds: number;
}

interface Bucket {
  hits: number;
  resetAt: number;
}

/**
 * A fixed-window counter per (rule, IP).
 *
 * Fixed rather than sliding on purpose: a sliding-window log is exact but
 * stores a timestamp per request, which is attacker-controllable memory. A
 * fixed window is two numbers per key. The cost is that a client can make up
 * to 2× the nominal allowance by straddling a window boundary, which for an
 * abuse ceiling is immaterial — the limit exists to stop a flood, not to
 * meter a subscription.
 *
 * The clock is injectable so the suite can test window rollover without
 * sleeping for a real minute.
 */
export class FixedWindowRateLimiter {
  private readonly buckets = new Map<string, Bucket>();

  constructor(
    private readonly maxEntries: number = 50_000,
    private readonly now: () => number = Date.now
  ) {}

  /** Live bucket count — asserted by tests to prove memory stays bounded. */
  get size(): number {
    return this.buckets.size;
  }

  check(key: string, rule: RateLimitRule): RateLimitDecision {
    const at = this.now();
    this.evictIfNeeded(at);

    const bucketKey = `${rule.name}:${key}`;
    let bucket = this.buckets.get(bucketKey);

    // A missing bucket and an expired one are the same thing: both mean this
    // client has no live count.
    if (bucket === undefined || bucket.resetAt <= at) {
      if (bucket !== undefined) this.buckets.delete(bucketKey);
      bucket = { hits: 0, resetAt: at + rule.windowMs };
    }

    bucket.hits += 1;

    // Re-insert so insertion order tracks recency of use, which is what the
    // eviction fallback below relies on.
    this.buckets.delete(bucketKey);
    this.buckets.set(bucketKey, bucket);

    return {
      allowed: bucket.hits <= rule.max,
      remaining: Math.max(0, rule.max - bucket.hits),
      retryAfterSeconds: Math.max(1, Math.ceil((bucket.resetAt - at) / 1000)),
    };
  }

  /**
   * Keep the map bounded.
   *
   * Without this, a flood from many source addresses — or a single attacker
   * forging `X-Forwarded-For` — would grow this Map until the process died,
   * turning a rate limiter into the very denial of service it exists to
   * prevent. Expired buckets go first; if that is not enough, the least
   * recently used are dropped, which resets those clients' counters. That is
   * a deliberate trade: a slightly weaker limit under extreme load beats
   * running out of memory.
   */
  private evictIfNeeded(at: number): void {
    if (this.buckets.size <= this.maxEntries) return;

    for (const [key, bucket] of this.buckets) {
      if (bucket.resetAt <= at) this.buckets.delete(key);
    }
    if (this.buckets.size <= this.maxEntries) return;

    const toDrop = Math.ceil(this.maxEntries * 0.1);
    let dropped = 0;
    for (const key of this.buckets.keys()) {
      this.buckets.delete(key);
      dropped += 1;
      if (dropped >= toDrop) break;
    }
  }
}

/**
 * The active rules, read from the environment.
 *
 * Every declared limit has a consumer. The report and official-message rules
 * that used to live here went with the moderation and notification modules —
 * a rule with no route to protect is the "config implies protection" gap this
 * module exists to prevent, and leaving it in place would report a limit that
 * nothing enforces.
 */
export function rateLimitConfigFromEnv(): RateLimitConfig {
  return {
    global: {
      name: 'global',
      windowMs: env.RATE_LIMIT_WINDOW_MS,
      max: env.RATE_LIMIT_MAX,
    },
    auth: {
      name: 'auth',
      windowMs: env.RATE_LIMIT_WINDOW_MS,
      max: env.AUTH_RATE_LIMIT_MAX,
    },
    write: {
      name: 'write',
      windowMs: env.RATE_LIMIT_WINDOW_MS,
      max: env.WRITE_RATE_LIMIT_MAX,
    },
  };
}

/**
 * Boot-time report of what is actually enforced.
 *
 * This exists so that "the env vars are set" can never again be mistaken for
 * "the limits are enforced" — the distinction that made this module necessary.
 */
export function describeActiveLimits(config: RateLimitConfig): string[] {
  const lines = [
    `[rate-limit] ACTIVE  ${config.global.name}: ${config.global.max} req / ${config.global.windowMs}ms per IP (/api/v1)`,
    `[rate-limit] NOTE: anonymous reads are covered by the global rule only — that is the whole reader-facing allowance, since Good Post has no accounts to limit (§24)`,
    `[rate-limit] ACTIVE  ${config.write.name}: ${config.write.max} req / ${config.write.windowMs}ms per IP (every administrator write, including the media handshake)`,
    `[rate-limit] ACTIVE  ${config.auth.name}: ${config.auth.max} req / ${config.auth.windowMs}ms per IP (/admin/api/auth/login)`,
    `[rate-limit] NOT LIMITED: /health and /health/db (Render polls these; limiting them causes restart loops)`,
    `[rate-limit] NOTE: rate limiting is by IP; the per-IDENTITY admin lockout is enforced in the service layer (LOGIN_MAX_FAILED_ATTEMPTS=${env.LOGIN_MAX_FAILED_ATTEMPTS}, LOGIN_LOCKOUT_MINUTES=${env.LOGIN_LOCKOUT_MINUTES})`,
    `[rate-limit] storage: in-process fixed window — per instance, not shared`,
  ];
  return lines;
}

/**
 * Middleware enforcing one rule against the caller's IP.
 *
 * Keyed on `req.ip`, which is meaningful only because `trust proxy` is set:
 * behind Render's proxy every request arrives from the proxy's address, so
 * without that every client would share a single bucket and the first busy
 * user would lock out everyone.
 */
export function rateLimit(limiter: FixedWindowRateLimiter, rule: RateLimitRule): RequestHandler {
  return (req: Request, res: Response, next: NextFunction) => {
    const decision = limiter.check(req.ip ?? 'unknown', rule);

    res.setHeader('X-RateLimit-Limit', String(rule.max));
    res.setHeader('X-RateLimit-Remaining', String(decision.remaining));

    if (decision.allowed) {
      next();
      return;
    }

    res.setHeader('Retry-After', String(decision.retryAfterSeconds));
    // Shaped like every other error in this API so the client's existing
    // error handling covers it, and so the Android error mapper already has a
    // wording for it.
    res.status(429).json({ error: 'rate_limited' });
  };
}
