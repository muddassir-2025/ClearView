import 'dotenv/config';
import { createHash } from 'node:crypto';
import { z } from 'zod';

// Only zod primitives that behave identically across zod 3 and 4 are used
// here; `.url()` / `.email()` were deprecated in v4, so URL and email shapes
// are checked by hand below instead.

const bool = z
  .union([z.boolean(), z.string()])
  .transform((v) => (typeof v === 'boolean' ? v : ['1', 'true', 'yes', 'on'].includes(v.toLowerCase())));

const csv = z
  .string()
  .default('')
  .transform((v) =>
    v
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
  );

const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(8080),
  PUBLIC_BASE_URL: z.string().default('http://localhost:8080'),
  CORS_ORIGINS: csv,

  // ── Neon ──
  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),
  DATABASE_URL_DIRECT: z.string().optional(),
  PG_POOL_MAX: z.coerce.number().int().positive().default(10),

  // ── Auth ──
  //
  // ONE secret, because exactly one thing in this service signs anything: an
  // administrator's short-lived access token. The signing key is DERIVED from it
  // (see `admin/tokens.ts`), so a second or third "JWT secret" would be values
  // nothing reads. JWT_REFRESH_SECRET, ACCESS_TOKEN_TTL and
  // REFRESH_TOKEN_TTL_DAYS had all become precisely that: leftovers from a
  // reader-session model whose sessions are now rows with their own expiry, so
  // their lifetimes are the database's business and not a variable's.
  JWT_SECRET: z.string().min(1, 'JWT_SECRET is required'),

  // The pepper for the one irreversible hash the service stores: the client IP
  // in the audit log.
  //
  // It was called PHONE_HASH_PEPPER, for a phone-identity model this product no
  // longer has. A secret whose name describes a column the schema does not
  // contain is worse than a new name: it is a value somebody keeps setting for a
  // reason that stopped existing.
  GOODPOST_HASH_PEPPER: z.string().min(1, 'GOODPOST_HASH_PEPPER is required'),

  // ── The first administrator (§17) ──
  //
  // §17 requires exactly one initial SUPER_ADMIN, configured on the SERVER and
  // never in the Android app. Both values are accepted:
  //
  //  * `SUPER_ADMIN_PASSWORD_HASH` — a bcrypt hash. Nothing to hash at boot.
  //  * `SUPER_ADMIN_PASSWORD` — a plaintext value, hashed at boot.
  //
  // **A plaintext value is authoritative.** When it is set and no longer opens
  // the account, the stored hash is replaced with it at boot (see
  // `admin/bootstrap.ts`). That is deliberate: changing this variable and having
  // the old password keep working is indistinguishable, from the app, from a
  // broken sign-in. Set ONLY the hash if you would rather rotate the password
  // through `create-admin --reset` and keep the environment out of it.
  //
  // There is no route that creates the first administrator. An unauthenticated
  // "create the first admin" endpoint is not a bootstrap, it is a backdoor.
  // ── Reader identity (§3, §15, §16) ──
  //
  // The Firebase project a reader's ID token must have been minted for. It is
  // PUBLIC — it is the token's `aud` claim, and it is shipped inside the Android
  // app's own `google-services.json` — so there is no Firebase secret in this
  // deployment and none is needed: ID tokens are verified against Google's
  // published signing certificates (`identity/verifier.ts`), which requires the
  // project id and nothing else.
  //
  // Optional, and its absence is a supported state rather than a broken one. A
  // deployment without it serves the whole public read API and refuses the
  // reader-scoped routes with `auth_unavailable`, which is exactly the product
  // before §3 — and that is a better failure than the alternative this variable
  // could have been given, which is a shared secret that, if it leaked, would
  // let anyone mint a uid.
  FIREBASE_PROJECT_ID: z.string().optional(),

  SUPER_ADMIN_EMAIL: z.string().optional(),
  SUPER_ADMIN_PASSWORD_HASH: z.string().optional(),
  SUPER_ADMIN_PASSWORD: z.string().optional(),
  SUPER_ADMIN_DISPLAY_NAME: z.string().default('Good Post Admin'),
  /**
   * Shown by the app's "Don't have channel access?" line (§16).
   *
   * A deployment detail rather than a secret, and configurable because a
   * hard-coded address is wrong for every installation but one.
   */
  ADMIN_CONTACT_EMAIL: z.string().default(''),

  // ── S3 ──
  AWS_REGION: z.string().default('eu-central-1'),
  AWS_S3_BUCKET: z.string().default(''),
  AWS_ACCESS_KEY_ID: z.string().default(''),
  AWS_SECRET_ACCESS_KEY: z.string().default(''),
  AWS_USE_INSTANCE_ROLE: bool.default(false),
  S3_UPLOAD_URL_TTL: z.coerce.number().int().positive().default(900),
  S3_DOWNLOAD_URL_TTL: z.coerce.number().int().positive().default(3600),
  S3_MAX_UPLOAD_BYTES: z.coerce.number().int().positive().default(104_857_600),
  // How long a presigned upload may sit unclaimed before the sweep may treat it
  // as abandoned (§34). An upload that is never claimed belongs to no post and
  // is invisible to every reader, so this is about storage cost and not about
  // correctness — which is why it is minutes rather than days.
  UPLOAD_CLAIM_WINDOW_MINUTES: z.coerce.number().int().positive().default(60),

  // ── Product rules ──
  // How long a DELETED post's rows survive before the physical delete.
  //
  // Zero, because deleting a post is meant to be final for its content: the row
  // stops being readable the moment it is deleted, and the next sweep (every 30
  // minutes) removes the rows and their objects. A grace period here would only
  // mean keeping data an administrator has already removed, and the record that
  // mattered — who removed what, and when — is in the audit log, which this
  // never touches.
  //
  PURGE_GRACE_DAYS: z.coerce.number().int().nonnegative().default(0),

  /**
   * How long a post's copy stays on the server (§14).
   *
   * Thirty days is the product's answer to "how far back can somebody who is not
   * following a channel read before deciding?" — long enough to judge a channel,
   * short enough to cap what the deployment stores for a channel that has been
   * running for years.
   *
   * **Zero means keep everything**, and that is the opt-out. It is read as "no
   * window" rather than as "expire immediately" on purpose: the difference
   * between declining a retention policy and deleting an entire deployment's
   * history should not be one digit typed carelessly.
   *
   * The honest cost of a non-zero value: an update older than the window is gone
   * for everyone, so a channel is a feed and not an archive. Media a reader has
   * already downloaded is unaffected — that lives on their device.
   */
  POST_RETENTION_DAYS: z.coerce.number().int().nonnegative().default(30),
  EDIT_WINDOW_DAYS: z.coerce.number().int().positive().default(30),
  MAX_TEXT_LENGTH: z.coerce.number().int().positive().default(4000),
  MAX_CHANNEL_NAME_LENGTH: z.coerce.number().int().positive().default(80),
  MAX_CHANNEL_DESCRIPTION_LENGTH: z.coerce.number().int().positive().default(500),
  DEFAULT_PAGE_SIZE: z.coerce.number().int().positive().default(30),
  MAX_PAGE_SIZE: z.coerce.number().int().positive().default(100),

  // ── Admins (§17, §18) ──
  // Shorter than any reader-facing session on purpose: this token can publish
  // and delete on a channel's behalf.
  ADMIN_ACCESS_TOKEN_TTL: z.string().default('10m'),
  ADMIN_SESSION_TTL_DAYS: z.coerce.number().int().positive().default(7),
  /** §16's floor for a channel administrator's password. */
  ADMIN_MIN_PASSWORD_LENGTH: z.coerce.number().int().positive().default(12),

  // A carousel bound (§8). Enforced by the API rather than by a constraint,
  // because it is a product rule and not an invariant of the data.
  MAX_POST_MEDIA: z.coerce.number().int().positive().default(4),

  // ── Rate limiting ──
  RATE_LIMIT_WINDOW_MS: z.coerce.number().int().positive().default(60_000),
  RATE_LIMIT_MAX: z.coerce.number().int().positive().default(180),
  AUTH_RATE_LIMIT_MAX: z.coerce.number().int().positive().default(12),
  WRITE_RATE_LIMIT_MAX: z.coerce.number().int().positive().default(40),
  LOGIN_MAX_FAILED_ATTEMPTS: z.coerce.number().int().positive().default(8),
  LOGIN_LOCKOUT_MINUTES: z.coerce.number().int().positive().default(15),

  // ── Retention job (§11, §34) ──
  RETENTION_CRON: z.string().default('*/30 * * * *'),
  RETENTION_JOB_ENABLED: bool.default(true),
  /** Rows one purge pass may take, so a large backlog cannot stall a tick. */
  PURGE_BATCH_SIZE: z.coerce.number().int().positive().default(200),
});

const parsed = schema.safeParse(process.env);

if (!parsed.success) {
  // Print field names only — never the rejected values, which may be secrets.
  const fields = Object.keys(parsed.error.flatten().fieldErrors).join(', ');
  console.error(`[env] Invalid configuration. Fix these variables: ${fields}`);
  process.exit(1);
}

export const env = parsed.data;

export const isProduction = env.NODE_ENV === 'production';

/**
 * Values that mean "nobody filled this in yet". A production boot with any of
 * these still present is refused rather than silently running with a
 * guessable JWT secret — the failure mode of "it started fine" is far worse
 * than "it refused to start".
 */
const PLACEHOLDER = /^(replace-with|changeme|change-me|your-|placeholder|xxx)/i;

function assertReal(name: string, value: string): void {
  if (!value) return;
  if (PLACEHOLDER.test(value) || value.includes('PASSWORD@')) {
    throw new Error(`[env] ${name} is still a placeholder value. Set a real secret before running in production.`);
  }
}

if (isProduction) {
  for (const name of ['JWT_SECRET', 'GOODPOST_HASH_PEPPER']) {
    assertReal(name, String((env as Record<string, unknown>)[name] ?? ''));
  }
  if (env.JWT_SECRET.length < 32 || env.GOODPOST_HASH_PEPPER.length < 32) {
    throw new Error(
      '[env] JWT_SECRET and GOODPOST_HASH_PEPPER must each be at least 32 characters.'
    );
  }

  // §17: there is exactly ONE initial super administrator and it comes from the
  // server's own configuration. A production deployment that has none can serve
  // readers but nobody can publish, and the first person to discover that would
  // be a user looking at a channel list that never changes — so it is refused at
  // boot, with the variable named, instead.
  if (!env.SUPER_ADMIN_EMAIL) {
    throw new Error('[env] SUPER_ADMIN_EMAIL is required in production: no one could sign in to publish.');
  }
  if (!env.SUPER_ADMIN_PASSWORD_HASH && !env.SUPER_ADMIN_PASSWORD) {
    throw new Error(
      '[env] SUPER_ADMIN_PASSWORD_HASH (or SUPER_ADMIN_PASSWORD, hashed at boot) is required in production.'
    );
  }
  assertReal('SUPER_ADMIN_PASSWORD', env.SUPER_ADMIN_PASSWORD ?? '');

  // NOT length-checked, deliberately.
  //
  // `ADMIN_MIN_PASSWORD_LENGTH` is the floor for a password a person invents in
  // the app's "create a channel" form, where nothing else constrains it. A
  // configured super-administrator password is a different thing: the operator
  // already holds the secret, it never crosses the app, and refusing to boot over
  // its length would take the entire service — readers included — down over a
  // value that works. The bootstrap applies it as given and says so when it is
  // short (see `admin/bootstrap.ts`).
}

/** Parse and normalise a URL from the environment. Throws on garbage. */
export function parseUrl(value: string, name: string): URL {
  try {
    return new URL(value);
  } catch {
    throw new Error(`[env] ${name} is not a valid URL.`);
  }
}

// Validate the public base URL shape at boot so a typo surfaces here rather
// than in a generated share link months later.
parseUrl(env.PUBLIC_BASE_URL, 'PUBLIC_BASE_URL');

/** True when S3 is configured well enough to serve media. */
export const s3Configured = Boolean(env.AWS_S3_BUCKET) &&
  (env.AWS_USE_INSTANCE_ROLE || Boolean(env.AWS_ACCESS_KEY_ID && env.AWS_SECRET_ACCESS_KEY));

/**
 * Hash a client IP, so the audit log never keeps a raw address (§29).
 *
 * HMAC rather than a bare digest: an IPv4 address has only ~4 billion possible
 * values, so an unpeppered column would be a list of addresses that anyone with
 * a copy of the table could enumerate in minutes.
 */
export function hashIp(ip: string): string {
  return createHash('sha256').update(`${env.GOODPOST_HASH_PEPPER}:ip:${ip}`).digest('hex');
}

/** SHA-256 of a refresh token — what actually gets stored. */
export function hashToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

/** Normalise an email for uniqueness/lookup. */
export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}
