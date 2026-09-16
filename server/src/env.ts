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
  JWT_SECRET: z.string().min(1, 'JWT_SECRET is required'),
  JWT_REFRESH_SECRET: z.string().min(1, 'JWT_REFRESH_SECRET is required'),
  ACCESS_TOKEN_TTL: z.string().default('15m'),
  REFRESH_TOKEN_TTL_DAYS: z.coerce.number().int().positive().default(30),

  PHONE_HASH_PEPPER: z.string().min(1, 'PHONE_HASH_PEPPER is required'),
  PHONE_HASH_PEPPER_VERSION: z.coerce.number().int().positive().default(1),

  // ── Bootstrap owner (one-time; consumed by `npm run create-admin`) ──
  BOOTSTRAP_ADMIN_EMAIL: z.string().optional(),
  BOOTSTRAP_ADMIN_PASSWORD: z.string().optional(),
  BOOTSTRAP_ADMIN_PHONE: z.string().optional(),
  BOOTSTRAP_ADMIN_DISPLAY_NAME: z.string().default('ClearView Admin'),

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
  GOODPOST_HISTORY_DAYS: z.coerce.number().int().positive().default(90),
  PURGE_GRACE_DAYS: z.coerce.number().int().nonnegative().default(30),
  EDIT_WINDOW_DAYS: z.coerce.number().int().positive().default(30),
  BAN_PHONE_ENFORCED: bool.default(true),
  MAX_TEXT_LENGTH: z.coerce.number().int().positive().default(4000),
  MAX_CHANNEL_NAME_LENGTH: z.coerce.number().int().positive().default(80),
  MAX_CHANNEL_DESCRIPTION_LENGTH: z.coerce.number().int().positive().default(500),
  DEFAULT_PAGE_SIZE: z.coerce.number().int().positive().default(30),
  MAX_PAGE_SIZE: z.coerce.number().int().positive().default(100),
  DEFAULT_NOTIFICATIONS_ENABLED: bool.default(false),
  MAX_CHANNELS_PER_USER: z.coerce.number().int().positive().default(5),
  // A carousel bound (§8). Enforced by the API rather than by a constraint,
  // because it is a product rule and not an invariant of the data.
  MAX_POST_MEDIA: z.coerce.number().int().positive().default(4),

  // ── Rate limiting ──
  RATE_LIMIT_WINDOW_MS: z.coerce.number().int().positive().default(60_000),
  RATE_LIMIT_MAX: z.coerce.number().int().positive().default(180),
  AUTH_RATE_LIMIT_MAX: z.coerce.number().int().positive().default(12),
  WRITE_RATE_LIMIT_MAX: z.coerce.number().int().positive().default(40),
  REPORT_RATE_LIMIT_MAX: z.coerce.number().int().positive().default(10),
  LOGIN_MAX_FAILED_ATTEMPTS: z.coerce.number().int().positive().default(8),
  LOGIN_LOCKOUT_MINUTES: z.coerce.number().int().positive().default(15),
  OTP_MAX_SENDS_PER_HOUR: z.coerce.number().int().positive().default(5),
  OTP_MAX_ATTEMPTS: z.coerce.number().int().positive().default(5),
  OTP_TTL_MINUTES: z.coerce.number().int().positive().default(10),

  // ── Phone verification / Firebase ──
  PHONE_VERIFY_MODE: z.enum(['firebase', 'disabled']).default('firebase'),
  FIREBASE_PROJECT_ID: z.string().default(''),
  FIREBASE_CLIENT_EMAIL: z.string().default(''),
  // Service-account keys are stored with literal \n sequences (a real newline
  // cannot survive most secret stores or a .env line), so restore them here —
  // once, at the edge — rather than in every caller of the Admin SDK.
  FIREBASE_PRIVATE_KEY: z
    .string()
    .default('')
    .transform((v) => v.replace(/\\n/g, '\n')),
  FCM_ENABLED: bool.default(true),

  // ── Retention job ──
  RETENTION_CRON: z.string().default('*/30 * * * *'),
  RETENTION_JOB_ENABLED: bool.default(true),
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
  if (!value) return; // emptiness is reported per-capability (S3/Firebase) below
  if (PLACEHOLDER.test(value) || value.includes('PASSWORD@')) {
    throw new Error(`[env] ${name} is still a placeholder value. Set a real secret before running in production.`);
  }
}

if (isProduction) {
  for (const name of ['JWT_SECRET', 'JWT_REFRESH_SECRET', 'PHONE_HASH_PEPPER']) {
    assertReal(name, String((env as Record<string, unknown>)[name] ?? ''));
  }
  if (env.JWT_SECRET === env.JWT_REFRESH_SECRET) {
    throw new Error('[env] JWT_SECRET and JWT_REFRESH_SECRET must differ.');
  }
  if (env.JWT_SECRET.length < 32 || env.PHONE_HASH_PEPPER.length < 32) {
    throw new Error('[env] JWT_SECRET and PHONE_HASH_PEPPER must each be at least 32 characters.');
  }

  // Capabilities that exist for tests and staging, and that would be a silent
  // hole in production. Refusing to boot is the same policy the secrets above
  // follow: a service that starts with authentication disabled is far worse
  // than one that refuses to start and says why.
  if (env.PHONE_VERIFY_MODE === 'disabled') {
    throw new Error(
      '[env] PHONE_VERIFY_MODE=disabled is refused in production: it would accept a "phone:<E.164>" string as proof of number ownership.'
    );
  }
  if (!env.BAN_PHONE_ENFORCED) {
    throw new Error(
      '[env] BAN_PHONE_ENFORCED=false is refused in production: bans on a mobile identity would not be enforced (§19).'
    );
  }
  if (
    env.PHONE_VERIFY_MODE === 'firebase' &&
    (!env.FIREBASE_PROJECT_ID || !env.FIREBASE_CLIENT_EMAIL || !env.FIREBASE_PRIVATE_KEY)
  ) {
    throw new Error(
      '[env] PHONE_VERIFY_MODE=firebase requires FIREBASE_PROJECT_ID, FIREBASE_CLIENT_EMAIL and FIREBASE_PRIVATE_KEY.'
    );
  }
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
 * Privacy-preserving phone identity (§19 / §38).
 *
 * A phone number only ever exists in memory between Firebase verifying it and
 * this function hashing it. HMAC (not a bare hash) is used so that a leaked
 * `phone_hash` column cannot be reversed by brute-forcing the ~10^10 possible
 * E.164 numbers — without the pepper, that table is enumerable in minutes.
 */
export function hashPhone(e164: string): string {
  return createHash('sha256')
    .update(`${env.PHONE_HASH_PEPPER}:${e164}`)
    .digest('hex');
}

/** Hash a client IP the same way, so §29 correlation never keeps a raw IP. */
export function hashIp(ip: string): string {
  return createHash('sha256').update(`${env.PHONE_HASH_PEPPER}:ip:${ip}`).digest('hex');
}

/** SHA-256 of a refresh token — what actually gets stored. */
export function hashToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

/** Normalise an email for uniqueness/lookup. */
export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}
