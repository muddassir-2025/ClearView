import type { z } from 'zod';
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
