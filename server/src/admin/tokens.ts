import { createHmac, randomBytes } from 'node:crypto';
import jwt from 'jsonwebtoken';
import type { SignOptions } from 'jsonwebtoken';
import { env, hashToken } from '../env.js';

/**
 * Admin tokens (§30, §48).
 *
 * Two separations, and both matter:
 *
 *  1. **A different AUDIENCE and ISSUER** from user tokens, so a Good Post
 *     access token cannot satisfy an admin check even if an endpoint path were
 *     guessed — `jwt.verify` refuses it on the claim, before any database read.
 *
 *  2. **A different SIGNING KEY, derived from `JWT_SECRET`.** Believing the
 *     audience claim alone would make §48's separation one bug away from gone;
 *     deriving `HMAC-SHA256(JWT_SECRET, 'goodpost-admin/v1')` means a user token
 *     is not merely rejected, it is unverifiable — the key that would validate
 *     it is only ever used by this module. Deriving rather than adding a second
 *     secret is deliberate too: `render.yaml` generates `JWT_SECRET`, and
 *     requiring an operator to invent another secret is how one ends up pasted
 *     into a chat.
 *
 * There is deliberately no `verifyAdminAccessToken` that accepts a role as
 * authoritative: the role claim is included for logging and for the dashboard's
 * initial render, and every permission decision re-reads the row (§32).
 */

const ISSUER = 'clearview-goodpost-admin';
const AUDIENCE = 'clearview-admin';

/** See the header note. Computed once, never exported. */
const ADMIN_SIGNING_KEY = createHmac('sha256', env.JWT_SECRET)
  .update('goodpost-admin/v1')
  .digest('hex');

export interface AdminAccessTokenClaims {
  /** `admin_users.id`. */
  readonly sub: string;
  /** `admin_sessions.id` — the session this token belongs to. */
  readonly sid: string;
}

export function signAdminAccessToken(claims: AdminAccessTokenClaims, role: string): string {
  const options: SignOptions = {
    subject: claims.sub,
    issuer: ISSUER,
    audience: AUDIENCE,
    // Its own, shorter TTL than a user's (ADMIN_ACCESS_TOKEN_TTL, 10m): this
    // token can ban identities and read every report.
    expiresIn: env.ADMIN_ACCESS_TOKEN_TTL as SignOptions['expiresIn'],
  };
  return jwt.sign({ sid: claims.sid, role }, ADMIN_SIGNING_KEY, options);
}

/**
 * Verify an admin bearer token. Null for ANY problem — expired, wrong
 * signature, wrong issuer, wrong audience, malformed, or missing claims.
 * Callers never distinguish those cases to the client.
 */
export function verifyAdminAccessToken(token: string): AdminAccessTokenClaims | null {
  try {
    const decoded = jwt.verify(token, ADMIN_SIGNING_KEY, {
      issuer: ISSUER,
      audience: AUDIENCE,
    });
    if (typeof decoded === 'string') return null;

    const sub = decoded.sub;
    const sid = (decoded as { sid?: unknown }).sid;
    if (typeof sub !== 'string' || sub.length === 0) return null;
    if (typeof sid !== 'string' || sid.length === 0) return null;

    return { sub, sid };
  } catch {
    return null;
  }
}

/** A fresh admin refresh token and the hash that gets persisted. */
export function mintAdminRefreshToken(): { token: string; hash: string } {
  // 64 bytes rather than the user side's 48: this token unlocks the whole
  // administration surface, and the cost of the extra entropy is 20 characters.
  const token = randomBytes(64).toString('base64url');
  return { token, hash: hashToken(token) };
}

/** When an admin session minted now stops being accepted (§30). */
export function adminSessionExpiry(): Date {
  return new Date(Date.now() + env.ADMIN_SESSION_TTL_DAYS * 24 * 60 * 60 * 1000);
}
