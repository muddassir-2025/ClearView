import request from 'supertest';
import type { Express } from 'express';
import { afterAll, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { closePool } from '../src/db.js';
import {
  FixedWindowRateLimiter,
  rateLimitConfigFromEnv,
  describeActiveLimits,
  type RateLimitRule,
} from '../src/http/rateLimit.js';

// The app opens a pool on import; release it so vitest can exit.
afterAll(async () => {
  await closePool();
});

const RULE: RateLimitRule = { name: 'test', windowMs: 60_000, max: 3 };

/**
 * Rate limiting.
 *
 * A configured limit that is enforced by nothing is worse than no limit, so every
 * assertion here is about a claim that used to be false. The window is
 * exercised through an injected clock rather than by sleeping, so the suite
 * stays fast and deterministic.
 */
describe('FixedWindowRateLimiter', () => {
  it('allows exactly the configured number of requests, then refuses', () => {
    const limiter = new FixedWindowRateLimiter(100, () => 1_000);

    expect(limiter.check('1.2.3.4', RULE).allowed).toBe(true);
    expect(limiter.check('1.2.3.4', RULE).allowed).toBe(true);

    const third = limiter.check('1.2.3.4', RULE);
    expect(third.allowed).toBe(true);
    expect(third.remaining).toBe(0);

    const fourth = limiter.check('1.2.3.4', RULE);
    expect(fourth.allowed).toBe(false);
    expect(fourth.remaining).toBe(0);
  });

  it('counts each client separately', () => {
    const limiter = new FixedWindowRateLimiter(100, () => 1_000);

    for (let i = 0; i < 3; i += 1) limiter.check('a', RULE);

    // One client exhausting its allowance must not affect another — which is
    // the whole point of keying by IP rather than globally.
    expect(limiter.check('a', RULE).allowed).toBe(false);
    expect(limiter.check('b', RULE).allowed).toBe(true);
  });

  it('gives a fresh allowance once the window rolls over', () => {
    let now = 1_000;
    const limiter = new FixedWindowRateLimiter(100, () => now);

    for (let i = 0; i < 4; i += 1) limiter.check('a', RULE);
    expect(limiter.check('a', RULE).allowed).toBe(false);

    now += RULE.windowMs;
    expect(limiter.check('a', RULE).allowed).toBe(true);
  });

  it('reports a retry-after inside the window', () => {
    let now = 1_000;
    const limiter = new FixedWindowRateLimiter(100, () => now);

    expect(limiter.check('a', RULE).retryAfterSeconds).toBe(60);

    // Same limiter, later clock: the bucket was opened at t=1000 and expires
    // at t=61000, so halfway through there are 30 seconds left to wait.
    // A fresh limiter here would open a NEW window and correctly report 60,
    // which is what this assertion is not about.
    now += 30_000;
    expect(limiter.check('a', RULE).retryAfterSeconds).toBe(30);
  });

  it('never reports a retry-after of zero, which would invite an immediate retry', () => {
    const limiter = new FixedWindowRateLimiter(100, () => 1_000);
    expect(limiter.check('a', { ...RULE, windowMs: 1 }).retryAfterSeconds).toBeGreaterThanOrEqual(1);
  });

  it('keeps memory bounded when flooded with distinct clients', () => {
    // An attacker forging X-Forwarded-For must not be able to grow this map
    // until the process dies — a rate limiter that runs out of memory has
    // caused the denial of service it exists to prevent.
    const maxEntries = 100;
    const limiter = new FixedWindowRateLimiter(maxEntries, () => 1_000);

    for (let i = 0; i < 5_000; i += 1) limiter.check(`ip-${i}`, RULE);

    expect(limiter.size).toBeLessThanOrEqual(maxEntries + 1);
  });
});

describe('rate limit configuration', () => {
  it('reads the documented environment variables rather than hardcoded values', () => {
    const config = rateLimitConfigFromEnv();

    // The suite sets these to very high values (see vitest.config.ts) so that
    // ordinary tests are never limited; what matters here is that the config
    // comes from the environment at all.
    expect(config.global.max).toBe(100_000);
    expect(config.auth.max).toBe(100_000);
    expect(config.auth.windowMs).toBeGreaterThan(0);
  });

  it('reports every declared limit as enforced, and keeps reporting the exemptions', () => {
    // The failure this guards against is an operator reading .env, seeing a
    // limit name, and believing it protects something. The boot log must
    // therefore name each remaining rule as ACTIVE — and still admit what is
    // deliberately NOT limited.
    //
    // Every rule HERE has a route behind it. The report and official-message
    // rules that used to be reported went with the moderation and notification
    // modules, and a rule with no route to protect is exactly the gap this
    // module exists to prevent.
    const reported = describeActiveLimits(rateLimitConfigFromEnv()).join('\n');

    expect(reported).toContain('ACTIVE  global');
    expect(reported).toContain('ACTIVE  auth');
    expect(reported).toContain('ACTIVE  write');
    // No rule may still claim to be reserved: an unenforced limit must be
    // impossible to mistake for an enforced one.
    expect(reported).not.toContain('RESERVED');
    expect(reported).not.toContain('report');
    expect(reported).toContain('NOT LIMITED: /health');
  });
});

describe('rate limiting through the app', () => {
  /** A tiny allowance on one rule, so the limit is reachable in a test. */
  const appWith = (authMax: number, globalMax = 1_000) =>
    buildApp({
      rateLimits: {
        global: { name: 'global', windowMs: 60_000, max: globalMax },
        auth: { name: 'auth', windowMs: 60_000, max: authMax },
        // Generous by default so a test about the auth rule is not also
        // measuring the write rule.
        write: { name: 'write', windowMs: 60_000, max: 10_000 },
      },
    });

  /**
   * An anonymous request to the sign-in route.
   *
   * The body is deliberately invalid, so the handler REFUSES it with a 400
   * without a database read: the limiter is what is under test, and a route
   * that reached Postgres would be measuring the connection instead.
   */
  const hitLogin = (app: Express, ip?: string) => {
    const call = request(app).post('/admin/api/auth/login').send({});
    return ip ? call.set('X-Forwarded-For', ip) : call;
  };

  it('returns 429 with Retry-After once the auth allowance is spent', async () => {
    const app = appWith(3);

    for (let i = 0; i < 3; i += 1) {
      const res = await hitLogin(app);
      expect(res.status).toBe(400);
      expect(res.headers['x-ratelimit-remaining']).toBe(String(2 - i));
    }

    const blocked = await hitLogin(app);
    expect(blocked.status).toBe(429);
    expect(blocked.body).toEqual({ error: 'rate_limited' });
    expect(Number(blocked.headers['retry-after'])).toBeGreaterThan(0);
  });

  it('limits each client independently', async () => {
    const app = appWith(1);

    expect((await hitLogin(app, '203.0.113.9')).status).toBe(400);
    expect((await hitLogin(app, '203.0.113.9')).status).toBe(429);

    // A different address keeps its own allowance. If `trust proxy`
    // regressed, every client would share one bucket and this would be 429.
    expect((await hitLogin(app, '198.51.100.7')).status).toBe(400);
  });

  it('does NOT limit the health endpoints, which Render polls', async () => {
    const app = appWith(1);

    await hitLogin(app);
    await hitLogin(app);
    expect((await hitLogin(app)).status).toBe(429);

    // Rate limiting these would turn legitimate health checks into failures,
    // and a failing health check into a Render restart loop.
    expect((await request(app).get('/health')).status).toBe(200);
    expect((await request(app).get('/health/db')).status).toBe(503);
  });

  it('applies the global limit to the reader surface, which has no auth rule of its own', async () => {
    const app = appWith(1_000, 2);

    // Anonymous reads are limited by the global rule alone — there is no
    // per-reader allowance to spend, because Good Post has no reader accounts.
    expect((await request(app).get('/api/v1/does-not-exist')).status).toBe(404);
    expect((await request(app).get('/api/v1/does-not-exist')).status).toBe(404);

    const blocked = await request(app).get('/api/v1/does-not-exist');
    expect(blocked.status).toBe(429);
  });

  it('leaves the default suite unlimited', async () => {
    // Guards the guard: if the limiter were applied with production defaults
    // here, unrelated tests would start failing intermittently, and the cause
    // would look like flakiness rather than a misconfiguration.
    const app = buildApp();
    for (let i = 0; i < 20; i += 1) {
      expect((await hitLogin(app)).status).toBe(400);
    }
  });
});
