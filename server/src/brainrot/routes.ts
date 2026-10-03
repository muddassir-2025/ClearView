import { Router } from 'express';
import { z } from 'zod';
import type { Queryable } from '../db.js';
import { badRequest } from '../http/errors.js';
import { parseBody } from '../http/validate.js';
import { getGlobalRules, reportTarget, submitSuggestion, type BrainRotRules } from './service.js';

/**
 * The anonymous Brain Rot surface, mounted at `/api/v1/brainrot`.
 *
 * ## Why this needs no account
 *
 * The Blocking tab's privacy promise is that protection needs no signup, and a
 * reader should not be able to tell the difference in how it works offline vs.
 * Anonymous means the phone generates its own random id, and nothing about a
 * person is required to fetch a keyword list or report a channel.
 *
 * ## What a device may and may not do
 *
 * It may:
 *   * fetch the active global rules, to cache and block on;
 *   * report a keyword or a channel;
 *   * suggest one for the global repository.
 *
 * It may NOT:
 *   * write a rule. The routes that do are on the admin prefix, behind a token.
 *
 * That split is the point of the module. A submission and a report are both
 * records of opinion; only an administrator turns an opinion into a rule, and
 * that is what stops one device from deciding what everyone else can watch.
 *
 * ## Rate limiting
 *
 * Deliberately none of its own: `/api/v1` already carries the global rule,
 * mounted once in `app.ts`. A second limiter here would either double-count the
 * same request or need its own namespace to describe the same allowance twice.
 */

/** A device-generated id. A channel `uuid` is a uuid is a uuid, so the shape is all there is. */
const AnonymousId = z.string().uuid();

const SubmitSchema = z
  .object({
    kind: z.enum(['keyword', 'channel']),
    value: z.string().trim().min(1).max(120),
    note: z.string().trim().max(500).nullable().optional(),
    anonymousId: AnonymousId,
  })
  .strict();

const ReportSchema = z
  .object({
    kind: z.enum(['keyword', 'channel']),
    value: z.string().trim().min(1).max(120),
    detail: z.string().trim().max(500).nullable().optional(),
    anonymousId: AnonymousId,
  })
  .strict();

/**
 * Parse a body, but report a bad DEVICE ID as `invalid_anonymous_id`.
 *
 * `parseBody` reports every shape failure as `invalid_request` with the field
 * names, which is right for a body in general. The device id is different: it is
 * the one field the app generates rather than the user, so "this install sent a
 * malformed id" is a distinct condition the client can act on — regenerate the
 * id and retry — and it is the code the service raises when its own check runs.
 * Both paths have to agree, or the same bad id would produce two different codes
 * depending on which check happened to catch it first.
 */
function parseDeviceBody<T>(schema: z.ZodType<T>, body: unknown): T {
  const result = schema.safeParse(body);
  if (!result.success) {
    const issues = result.error.issues;
    if (issues.some((issue) => issue.path.includes('anonymousId'))) {
      throw badRequest('invalid_anonymous_id', 'That device id is not a valid identifier.');
    }
  }
  return parseBody(schema, body);
}

/**
 * A short, stable fingerprint of the rule set, for a client to compare.
 *
 * Derived from the content rather than stored: a stored counter would need a
 * trigger on two tables to stay honest, and this list changes a handful of times
 * a year. Cheap to compute and impossible to drift from the rules it describes.
 */
function rulesVersion(rules: BrainRotRules): string {
  const parts = [
    ...rules.keywords.map((k) => `k:${k.keyword}`),
    ...rules.channels.map((c) => `c:${c.handle}`),
  ].sort();
  // djb2 — small, deterministic, and all this needs to be. It is a cache
  // comparison key, not a security primitive.
  let hash = 5381;
  for (const part of parts) {
    for (let i = 0; i < part.length; i += 1) {
      hash = ((hash * 33) ^ part.charCodeAt(i)) >>> 0;
    }
  }
  return `${parts.length}-${hash.toString(16)}`;
}

export function buildBrainRotPublicRouter(database: Queryable): Router {
  const router = Router();

  /**
   * The active global rules.
   *
   * One call rather than two, because a phone on a cold start wants the whole
   * rule set before it fetches, and `GET /rules`, `/keywords and `/channels`
   * would be three round trips for one snapshot that has to be internally
   * consistent.
   */
  router.get('/rules', async (_req, res) => {
    const rules = await getGlobalRules(database);
    res.status(200).json({
      keywords: rules.keywords.map((k) => ({
        keyword: k.keyword,
        reason: k.reason,
        reports: k.reports,
      })),
      channels: rules.channels.map((c) => ({
        handle: c.handle,
        name: c.displayName,
        reason: c.reason,
        reports: c.reports,
      })),
      // A version the client can compare to skip re-parsing an unchanged list.
      // Derived from the content rather than stored: a counter would need a
      // trigger to maintain, and this list changes rarely.
      version: rulesVersion(rules),
    });
  });

  /** Suggest a keyword or a channel for the global repository. Inert until approved. */
  router.post('/suggestions', async (req, res) => {
    const body = parseDeviceBody(SubmitSchema, req.body);
    const result = await submitSuggestion(database, body.anonymousId, body.kind, body.value, body.note ?? null);
    res.status(202).json(result);
  });

  /** Report a keyword or a channel. Counted, never acted on automatically. */
  router.post('/reports', async (req, res) => {
    const body = parseDeviceBody(ReportSchema, req.body);
    const result = await reportTarget(database, body.anonymousId, body.kind, body.value, body.detail ?? null);
    res.status(200).json(result);
  });

  return router;
}