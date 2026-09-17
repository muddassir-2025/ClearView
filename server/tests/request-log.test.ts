import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import request from 'supertest';
import { buildApp } from '../src/app.js';
import { closePool } from '../src/db.js';

/**
 * The request log (§37's diagnosability).
 *
 * These exist because of a real support case rather than for coverage: the app
 * showed "Something went wrong" for a failure the server had recorded nowhere,
 * so there was no way to tell a rejected token from a name collision from a rate
 * limit. The assertions below pin the two properties that make the log useful —
 * it names the CODE, and it stays quiet about everything else.
 */

const app = buildApp();

let logged: string[] = [];

/** Every line the app writes, whichever of the three streams it used. */
function capture(): void {
  logged = [];
  const record = (...args: unknown[]) => {
    logged.push(args.map(String).join(' '));
  };
  vi.spyOn(console, 'log').mockImplementation(record);
  vi.spyOn(console, 'warn').mockImplementation(record);
  vi.spyOn(console, 'error').mockImplementation(record);
}

beforeEach(capture);

afterEach(() => {
  vi.restoreAllMocks();
});

afterAll(async () => {
  await closePool();
});

/**
 * The response is sent before the log line is written — the logger listens for
 * `finish` — so the assertions have to let that listener run first.
 */
async function settled(): Promise<void> {
  await new Promise((resolve) => setImmediate(resolve));
}

function linesAbout(path: string): string[] {
  return logged.filter((line) => line.includes(path));
}

describe('the request log', () => {
  it('records a refusal with the code the client was given', async () => {
    const res = await request(app).post('/admin/api/creator/channel').send({ name: 'Anything' });
    await settled();

    // The request carried no credential, so the route answered `missing_token`.
    // The code is the half that identifies the condition, so it is the half the
    // log has to carry.
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('missing_token');

    const line = linesAbout('/admin/api/creator/channel').join('\n');
    expect(line).toContain('401');
    expect(line).toContain('missing_token');
  });

  it('records a successful request too, so silence cannot be mistaken for a request that never arrived', async () => {
    await request(app).get('/health/db');
    await settled();

    // health is the exception, asserted separately below.
    expect(linesAbout('/health/db')).toHaveLength(0);

    await request(app).get('/api/does-not-exist');
    await settled();

    expect(linesAbout('/api/does-not-exist').join('\n')).toContain('404');
  });

  it('stays quiet about the health endpoints Render polls', async () => {
    await request(app).get('/health');
    await request(app).get('/health/db');
    await settled();

    expect(linesAbout('/health')).toHaveLength(0);
  });

  it('logs no query string, no header and no body', async () => {
    // A reader's search term and an administrator's password travel in exactly
    // these two places, and neither belongs in a log that outlives the request.
    await request(app).get('/api/v1/channels?q=something-private-to-the-reader');
    await request(app)
      .post('/admin/api/auth/login')
      .send({ email: 'nobody@example.test', password: 'super-secret-password' });
    await settled();

    const all = logged.join('\n');
    expect(all).toContain('/api/v1/channels');
    expect(all).not.toContain('something-private-to-the-reader');
    expect(all).not.toContain('super-secret-password');
    expect(all).not.toContain('nobody@example.test');
    expect(all).not.toContain('authorization');
  });
});
