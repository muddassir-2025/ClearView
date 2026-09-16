import { Router } from 'express';
import { z } from 'zod';
import { hashIp } from '../env.js';
import type { Queryable } from '../db.js';
import { parseBody } from '../http/validate.js';
import { createMailer, type Mailer } from '../email/sender.js';
import { emailSignIn, requestEmailOtp } from './email.js';
import { createPhoneVerifier, type PhoneIdentityVerifier } from './firebase.js';
import { authOf, requireAuth } from './middleware.js';
import {
  loadOwnAccount,
  refreshSession,
  register,
  requestOtp,
  revokeAllSessions,
  revokeSessionByToken,
  signIn,
} from './service.js';

/**
 * Good Post authentication API (§3, §32), mounted at /api/v1/auth.
 *
 * The versioned prefix keeps this apart from the pre-existing moderation API
 * (`/api/rules`, `/api/channels/check`) that the Block tab already calls.
 * "Channel" means a moderated YouTube channel there and a broadcast feed here;
 * separate route trees stop the two meanings from colliding.
 *
 * Handlers are `async` and simply throw `ApiError`s. Express 5 forwards a
 * rejected handler promise into the central error handler, which is what turns
 * an `ApiError` into the documented `{ error: <code> }` body — so no handler
 * needs a try/catch, and no route can swallow an error by forgetting one.
 */

const OtpRequestSchema = z.object({
  phone: z.string().min(7).max(20),
  // Defaults to signin so a client that just wants to log in does not have to
  // know about purposes; registration passes 'register' explicitly.
  purpose: z.enum(['register', 'signin', 'recover']).default('signin'),
});

const RegisterSchema = z.object({
  idToken: z.string().min(16).max(8192),
  displayName: z.string().min(1).max(60),
  email: z.string().min(3).max(254),
});

const SignInSchema = z.object({
  idToken: z.string().min(16).max(8192),
});

const EmailOtpSchema = z.object({
  email: z.string().min(3).max(254),
});

/**
 * The code is bounded loosely on purpose: the digit count is the server's
 * business, and rejecting a pasted `"123 456"` here would be a validation
 * error the user cannot act on. `emailSignIn` strips non-digits and compares.
 */
const EmailSignInSchema = z.object({
  email: z.string().min(3).max(254),
  code: z.string().min(4).max(12),
});

const RefreshSchema = z.object({
  refreshToken: z.string().min(16).max(512),
});

const LogoutSchema = z.object({
  refreshToken: z.string().min(16).max(512).optional(),
  /** Revoke every device, not just this one. */
  all: z.boolean().optional().default(false),
});

/**
 * A human-readable device name for the session list.
 *
 * Sanitised because it is attacker-controlled text that will be rendered in
 * the account's own device list: control characters are stripped and the
 * length is capped, so a label cannot smuggle terminal escapes or blow up a
 * future admin view.
 */
function deviceLabelOf(headerValue: string | undefined): string | null {
  if (!headerValue) return null;
  const cleaned = headerValue.replace(/[\p{Cc}\p{Cf}]/gu, '').trim().slice(0, 64);
  return cleaned.length > 0 ? cleaned : null;
}

export function buildAuthRouter(
  database: Queryable,
  verifier: PhoneIdentityVerifier = createPhoneVerifier(),
  /**
   * Injected like [verifier], so the email flow can be exercised without a
   * provider account and without a single message leaving the process.
   */
  mailer: Mailer = createMailer()
): Router {
  const router = Router();
  const requireSession = requireAuth(database);

  const clientContext = (req: { header: (n: string) => string | undefined; ip?: string }) => ({
    deviceLabel: deviceLabelOf(req.header('x-device-label')),
    // Hashed, never raw: §29 permits device/request info "only where
    // appropriate and legally justified", and a hash supports abuse
    // correlation without retaining a readable IP log.
    ipHash: req.ip ? hashIp(req.ip) : null,
  });

  /**
   * Step 1 of every flow. The server records the intent and enforces the
   * resend limits; Firebase then sends the SMS. Register/sign-in refuse to
   * proceed without the challenge this creates.
   */
  router.post('/otp/request', async (req, res) => {
    const body = parseBody(OtpRequestSchema, req.body);
    const result = await requestOtp(database, {
      phone: body.phone,
      purpose: body.purpose,
      ipHash: req.ip ? hashIp(req.ip) : null,
    });
    res.status(200).json(result);
  });

  router.post('/register', async (req, res) => {
    const body = parseBody(RegisterSchema, req.body);
    const session = await register(database, verifier, {
      ...body,
      ...clientContext(req),
    });
    res.status(201).json(session);
  });

  router.post('/signin', async (req, res) => {
    const body = parseBody(SignInSchema, req.body);
    const session = await signIn(database, verifier, {
      ...body,
      ...clientContext(req),
    });
    res.status(200).json(session);
  });

  /**
   * The email half of the same step 1. Same contract as `/otp/request`: record
   * the intent, enforce the limits, and — for email — deliver the code.
   *
   * Returns 200 whether or not the address has an account. See the note in
   * `requestEmailOtp`: the answer to "is this address registered?" is not this
   * endpoint's to give.
   */
  router.post('/email/otp', async (req, res) => {
    const body = parseBody(EmailOtpSchema, req.body);
    const result = await requestEmailOtp(database, { email: body.email }, mailer);
    res.status(200).json(result);
  });

  /** Step 2 for email. Proves the inbox, then issues the same device session. */
  router.post('/email/signin', async (req, res) => {
    const body = parseBody(EmailSignInSchema, req.body);
    const session = await emailSignIn(database, {
      ...body,
      ...clientContext(req),
    });
    res.status(200).json(session);
  });

  router.post('/refresh', async (req, res) => {
    const body = parseBody(RefreshSchema, req.body);
    const session = await refreshSession(database, {
      ...body,
      ...clientContext(req),
    });
    res.status(200).json(session);
  });

  /**
   * Log out. Revoking by refresh token rather than by session id means a
   * caller can only ever destroy a credential it already holds.
   */
  router.post('/logout', requireSession, async (req, res) => {
    const auth = authOf(req);
    const body = parseBody(LogoutSchema, req.body);

    const revoked = body.all
      ? await revokeAllSessions(database, auth.userId, 'logout_all')
      : body.refreshToken
        ? Number(await revokeSessionByToken(database, body.refreshToken, 'logout'))
        : 0;

    res.status(200).json({ ok: true, revoked });
  });

  /**
   * The gate the Android client checks before showing Good Post: a stored
   * token is only worth keeping if this still answers with the account.
   */
  router.get('/me', requireSession, async (req, res) => {
    const auth = authOf(req);
    const account = await loadOwnAccount(database, auth.userId);
    if (!account) {
      // Raced with a deletion between middleware and here; treat as signed out.
      res.status(401).json({ error: 'invalid_token' });
      return;
    }
    res.status(200).json({ user: account, sessionId: auth.sessionId });
  });

  return router;
}
