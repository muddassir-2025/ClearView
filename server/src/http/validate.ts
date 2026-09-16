import { z } from 'zod';
import { badRequest } from './errors.js';

/**
 * Validate a body or query object, reporting FIELD NAMES ONLY on failure.
 *
 * The values are deliberately never echoed. A malformed request is the single
 * likeliest place for someone's `idToken`, phone number or refresh token to be
 * sitting, and reflecting it into a response body or an error log turns a bad
 * request into a credential leak (§30 forbids logging those exact things).
 *
 * Shared by every router rather than copied per module, so this one rule has
 * one implementation: a second copy is where the "echo the value for
 * debugging" temptation would eventually win.
 */
/**
 * A path segment that will be used as an identifier, bounded before use.
 *
 * The length cap is the point: this value reaches a SQL comparison, and an
 * unbounded one would let a request make the database evaluate an arbitrarily
 * long literal. The type code is the caller's, so each route keeps the error a
 * client already branches on (`invalid_channel_id`, `invalid_post_id`).
 */
export function pathIdParam(raw: unknown, type: string): string {
  const parsed = z.string().min(1).max(64).safeParse(raw);
  if (!parsed.success) throw badRequest(type, 'Invalid identifier.');
  return parsed.data;
}

export function parseBody<T>(schema: z.ZodType<T>, body: unknown): T {
  const result = schema.safeParse(body);
  if (!result.success) {
    const fields = Object.keys(result.error.flatten().fieldErrors);
    throw badRequest(
      'invalid_request',
      fields.length > 0 ? `Invalid fields: ${fields.join(', ')}` : 'Invalid request body.'
    );
  }
  return result.data;
}
