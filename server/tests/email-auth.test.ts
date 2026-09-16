import type { Express } from 'express';
import request from 'supertest';
import type { PGlite } from '@electric-sql/pglite';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { closePool, type Queryable } from '../src/db.js';
import { env, hashEmailCode } from '../src/env.js';
import { unauthorized } from '../src/http/errors.js';
import { providerErrorName, type OutboundMail, type Mailer } from '../src/email/sender.js';
import type { PhoneIdentityVerifier } from '../src/auth/firebase.js';
import { applyAllMigrations, asQueryable, freshDatabase, resetData } from './helpers/database.js';

/**
 * Email sign-in (§2, §3, §19, §38, §39).
 *
 * The point of this file is that email sign-in must not become a second, weaker
 * identity system. §19 anchors the abuse identity on the mobile number, so an
 * email address may open an existing account and may never create one — the
 * tests below assert that from both directions: that the flow works, and that
 * it cannot be used to register.
 *
 * Driven through the real Express app against a real Postgres (PGlite), with the
 * code read back from an injected mailer — so the assertions are about what was
 * genuinely delivered and stored, not about which function was called.
 */

const EMAIL = 'ayesha@example.test';
const PHONE = '+923001234567';

/**
 * Firebase stand-in. Identical in spirit to the one in auth.test.ts: it rejects
 * everything it does not recognise, so no test passes by having a lenient
 * double.
 */
function fakeVerifier(): PhoneIdentityVerifier {
  return {
    kind: 'firebase',
    async verifyIdToken(idToken: string) {
      const phone = idToken.startsWith('test:') ? idToken.slice('test:'.length) : '';
      if (!/^\+[1-9]\d{6,14}$/.test(phone)) {
        throw unauthorized('invalid_id_token', 'The supplied identity token is not valid.');
      }
      return { phoneE164: phone, firebaseUid: `uid:${phone}` };
    },
  };
}

/**
 * A mailer that records what it was asked to send.
 *
 * Reading the code out of the delivered message is what makes the rest of these
 * tests possible: there is no other way to obtain it, which is itself the
 * property under test — the database holds only an HMAC.
 */
class CapturingMailer implements Mailer {
  readonly kind = 'console' as const;
  readonly sent: OutboundMail[] = [];
  /** Set to throw, to exercise the delivery-failure path. */
  failure: Error | null = null;

  async send(mail: OutboundMail): Promise<void> {
    if (this.failure) throw this.failure;
    this.sent.push(mail);
  }

  get last(): OutboundMail | undefined {
    return this.sent[this.sent.length - 1];
  }

  /** The 6-digit code from the most recent message. */
  code(): string {
    const match = /(\d{6})/.exec(this.last?.text ?? '');
    if (!match?.[1]) throw new Error('no code found in the delivered message');
    return match[1];
  }
}

let pglite: PGlite;
let database: Queryable;
let mailer: CapturingMailer;
let app: Express;

beforeAll(async () => {
  pglite = await freshDatabase();
  await applyAllMigrations(pglite);
  database = asQueryable(pglite);
  mailer = new CapturingMailer();
  app = buildApp({ database, verifier: fakeVerifier(), mailer });
});

beforeEach(async () => {
  await resetData(pglite);
  mailer.sent.length = 0;
  mailer.failure = null;
});

afterAll(async () => {
  await pglite.close();
  await closePool();
});

// ── Helpers ─────────────────────────────────────────────────────────────

const requestCode = (email = EMAIL) =>
  request(app).post('/api/v1/auth/email/otp').send({ email });

const submitCode = (code: string, email = EMAIL) =>
  request(app).post('/api/v1/auth/email/signin').send({ email, code });

/** Registers through the real phone flow, so the account is a real one. */
async function registeredUser(email = EMAIL, phone = PHONE) {
  await request(app).post('/api/v1/auth/otp/request').send({ phone, purpose: 'register' });
  const res = await request(app)
    .post('/api/v1/auth/register')
    .send({ idToken: `test:${phone}`, displayName: 'Ayesha', email });
  expect(res.status, 'the phone flow should register in a fixture').toBe(201);
  return res.body as { user: { id: string } };
}

// ── Issuing a code ──────────────────────────────────────────────────────

describe('requesting an email code', () => {
  it('stores only an HMAC of the code, never the code itself', async () => {
    const res = await requestCode();
    expect(res.status).toBe(200);

    const code = mailer.code();
    const rows = await pglite.query<{ code_hash: string }>(
      `SELECT code_hash FROM email_verifications WHERE email_normalized = $1`,
      [EMAIL]
    );
    const stored = rows.rows[0]?.code_hash;

    expect(stored, 'a challenge should have been written').toBeTruthy();
    // The two properties that matter: the row does not contain the code, and it
    // is what the verifier will compare against.
    expect(stored).not.toBe(code);
    expect(stored).toBe(hashEmailCode(EMAIL, code));
    expect(stored).toMatch(/^[0-9a-f]{64}$/);
  });

  it('answers identically whether or not the address has an account', async () => {
    // No account exists at all here. This must look the same as it would for a
    // registered address: "is this email registered?" is not a question an
    // unauthenticated caller gets to ask.
    const res = await requestCode();
    expect(res.status).toBe(200);
    expect(Object.keys(res.body).sort()).toEqual(['expiresAt', 'sendsRemaining']);
  });

  it('leaves no challenge behind when delivery fails', async () => {
    // A user hunting for a code that was never sent is the worst outcome, and
    // the row would also have silently spent their hourly allowance.
    mailer.failure = new Error('provider down');

    const res = await requestCode();
    expect(res.status).toBe(503);
    expect(res.body.error).toBe('email_unavailable');

    const rows = await pglite.query<{ n: number }>(
      `SELECT count(*)::int AS n FROM email_verifications`
    );
    expect(rows.rows[0]?.n).toBe(0);
  });

  it('refuses a malformed address with a wordable code', async () => {
    const res = await requestCode('not-an-address');
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_email');
    expect(mailer.sent).toHaveLength(0);
  });

  it('enforces the hourly allowance', async () => {
    for (let i = 0; i < env.EMAIL_OTP_MAX_SENDS_PER_HOUR; i += 1) {
      expect((await requestCode()).status).toBe(200);
    }

    const res = await requestCode();
    expect(res.status).toBe(429);
    expect(res.body.error).toBe('otp_rate_limited');
    // Refused BEFORE a send, so the allowance cannot be exceeded by racing.
    expect(mailer.sent).toHaveLength(env.EMAIL_OTP_MAX_SENDS_PER_HOUR);
  });
});

// ── Exchanging a code for a session ─────────────────────────────────────

describe('signing in with an email code', () => {
  it('issues a session for the account that owns the address', async () => {
    const account = await registeredUser();
    await requestCode();
    const code = mailer.code();

    const res = await submitCode(code);
    expect(res.status).toBe(200);
    expect(res.body.user.id).toBe(account.user.id);
    expect(res.body.accessToken).toBeTruthy();
    expect(res.body.refreshToken).toBeTruthy();

    // The token has to be a real one, checked by the same middleware every
    // protected route uses — otherwise this only proves JSON was returned.
    const me = await request(app)
      .get('/api/v1/auth/me')
      .set({ Authorization: `Bearer ${res.body.accessToken}` });
    expect(me.status).toBe(200);
    expect(me.body.user.id).toBe(account.user.id);
  });

  it('spends the code, so it cannot be replayed', async () => {
    await registeredUser();
    await requestCode();
    const code = mailer.code();

    expect((await submitCode(code)).status).toBe(200);

    const replay = await submitCode(code);
    expect(replay.status).toBe(400);
    expect(replay.body.error).toBe('otp_required');
  });

  it('rejects a wrong code and PERSISTS the attempt', async () => {
    // Persisting is the interesting half. A counter incremented inside the
    // transaction that throws would roll back, and the attempt limit would be
    // unreachable — the same trap the phone flow documents.
    await registeredUser();
    await requestCode();

    const res = await submitCode('000000');
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('invalid_code');

    const rows = await pglite.query<{ attempts: number }>(
      `SELECT attempts FROM email_verifications WHERE email_normalized = $1`,
      [EMAIL]
    );
    expect(rows.rows[0]?.attempts).toBe(1);
  });

  it('locks the challenge after the attempt limit', async () => {
    await registeredUser();
    await requestCode();

    for (let i = 0; i < env.EMAIL_OTP_MAX_ATTEMPTS; i += 1) {
      expect((await submitCode('000000')).status).toBe(401);
    }

    // Even the CORRECT code is refused once the challenge is locked: the limit
    // is on guessing, and a brute-force run that is allowed to finish on the
    // attempt that happens to succeed is no limit at all.
    const res = await submitCode(mailer.code());
    expect(res.status).toBe(429);
    expect(res.body.error).toBe('otp_locked');
  });

  it('refuses a code that was never requested', async () => {
    await registeredUser();

    const res = await submitCode('123456');
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('otp_required');
  });

  it('refuses a code whose challenge has expired', async () => {
    await registeredUser();
    await requestCode();
    const code = mailer.code();

    // Both timestamps move: a challenge that expired before it was issued is
    // not a state the schema allows (email_verifications_window), and forging
    // one would test a row the application can never create.
    await pglite.query(
      `UPDATE email_verifications
          SET created_at = now() - interval '20 minutes',
              expires_at = now() - interval '10 minutes'
        WHERE email_normalized = $1`,
      [EMAIL]
    );

    const res = await submitCode(code);
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('otp_required');
  });

  it('tells the proven owner when no account uses the address', async () => {
    // Safe only AFTER the code was verified, which is why this is a 404 rather
    // than a generic refusal: the caller has proved control of the inbox.
    await requestCode();
    const code = mailer.code();

    const res = await submitCode(code);
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('email_not_registered');
  });

  it('does not spend the code when the account is banned or suspended', async () => {
    const account = await registeredUser();
    await pglite.query(`UPDATE users SET status = 'suspended' WHERE id = $1`, [account.user.id]);
    await requestCode();
    const code = mailer.code();

    const suspended = await submitCode(code);
    expect(suspended.status).toBe(403);
    expect(suspended.body.error).toBe('account_suspended');

    // The refusal is counted against the challenge rather than consuming it, so
    // a status change mid-flow does not strand the user with a burnt code.
    const rows = await pglite.query<{ consumed_at: string | null; attempts: number }>(
      `SELECT consumed_at, attempts FROM email_verifications WHERE email_normalized = $1`,
      [EMAIL]
    );
    expect(rows.rows[0]?.consumed_at).toBeNull();
    expect(rows.rows[0]?.attempts).toBe(1);
  });

  it('treats a soft-deleted account as not registered', async () => {
    // §37: deletion must be indistinguishable from never having registered.
    const account = await registeredUser();
    await pglite.query(`UPDATE users SET deleted_at = now() WHERE id = $1`, [account.user.id]);
    await requestCode();
    const code = mailer.code();

    const res = await submitCode(code);
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('email_not_registered');
  });
});

// ── The §19 guarantee ───────────────────────────────────────────────────

describe('email sign-in is not a registration path', () => {
  it('creates no account, however the email endpoint is used', async () => {
    // The whole reason this method exists in "sign-in only" form. If an
    // address could create an account, a banned mobile identity would be one
    // free mailbox away from returning (§19).
    await requestCode();
    const code = mailer.code();
    await submitCode(code);

    const users = await pglite.query<{ n: number }>(`SELECT count(*)::int AS n FROM users`);
    expect(users.rows[0]?.n, 'no account may be created by the email flow').toBe(0);

    const sessions = await pglite.query<{ n: number }>(
      `SELECT count(*)::int AS n FROM user_sessions`
    );
    expect(sessions.rows[0]?.n, 'and no session may be issued without one').toBe(0);
  });

  it('ignores a client that supplies its own phone or identity fields', async () => {
    // Nothing in the request body beyond email and code is read. A client that
    // posts extra fields must not be able to influence which account is used.
    await registeredUser();
    await requestCode();
    const code = mailer.code();

    const res = await request(app)
      .post('/api/v1/auth/email/signin')
      .send({ email: EMAIL, code, phoneE164: '+923110000000', role: 'SUPER_ADMIN' });

    expect(res.status).toBe(200);
    const me = await request(app)
      .get('/api/v1/auth/me')
      .set({ Authorization: `Bearer ${res.body.accessToken}` });
    // The account is still the one that owns the address — not the claimed one.
    expect(me.body.user.id).toBe(res.body.user.id);
    expect(me.body.user.email).toBe(EMAIL);
  });

  it('normalises case and whitespace, so one address is one challenge', async () => {
    await registeredUser();
    await requestCode('  AYESHA@Example.TEST  ');

    // The code was mailed to the canonical address; submitting it against a
    // differently-cased spelling must still work, and must not have created a
    // second challenge row.
    const rows = await pglite.query<{ n: number }>(
      `SELECT count(*)::int AS n FROM email_verifications`
    );
    expect(rows.rows[0]?.n).toBe(1);

    const res = await submitCode(mailer.code(), 'Ayesha@example.test');
    expect(res.status).toBe(200);
  });
});

/**
 * Naming a provider refusal without quoting it.
 *
 * These pin the shape a real refusal takes. The verbatim body below is what
 * Resend returns when an unverified account is asked to mail someone other than
 * its owner — a case that previously logged only `HTTP 403`, which is
 * indistinguishable from a dead key or a provider outage.
 */
describe('reading a provider refusal', () => {
  it('keeps the short identifier and drops everything else', async () => {
    const body = JSON.stringify({
      statusCode: 403,
      name: 'validation_error',
      message:
        'You can only send testing emails to your own email address (owner@example.test). ' +
        'To send emails to other recipients, please verify a domain.',
    });

    expect(await providerErrorName(new Response(body, { status: 403 }))).toBe('validation_error');
  });

  it('refuses to pass through anything that is not an identifier', async () => {
    // Each of these would be unreadable or worse in a log line: an address, a
    // free-text sentence, a nested object, an HTML error page, an empty body.
    for (const body of [
      JSON.stringify({ name: 'owner@example.test' }),
      JSON.stringify({ name: 'You can only send testing emails to your own address' }),
      JSON.stringify({ name: { nested: 'value' } }),
      JSON.stringify({ message: 'only a message, no name' }),
      '<html><body>502 Bad Gateway</body></html>',
      '',
    ]) {
      expect(await providerErrorName(new Response(body, { status: 502 }))).toBeNull();
    }
  });

  it('accepts a type when a provider names the field that way', async () => {
    const body = JSON.stringify({ type: 'rate_limit_exceeded' });
    expect(await providerErrorName(new Response(body, { status: 429 }))).toBe('rate_limit_exceeded');
  });
});
