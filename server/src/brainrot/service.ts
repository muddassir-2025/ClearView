import type { Queryable } from '../db.js';
import { isoOrNull } from '../db.js';
import { badRequest, notFound } from '../http/errors.js';
import { isUuid } from '../channels/cursor.js';

/**
 * The global Brain Rot protection repository.
 *
 * Three things live here, and keeping these three apart is the rule the rest of
 * the module is built on:
 *
 *  1. **The active rules.** Written only by an administrator. A phone fetches
 *     them and blocks on them. Nothing a user submits reaches this set without
 *     an explicit approval, which is the whole reason the spec insists that one
 *     accidental submission must not become everybody's rule.
 *
 *  2. **Submissions.** A suggestion that something should be global. Inert — no
 *     read path consults it to decide anything.
 *
 *  3. **Reports.** A count of distinct anonymous devices asking for a rule to
 *     exist. A signal for the reviewer, never a trigger.
 *
 * ## Why normalisation lives here and not in the database
 *
 * "AI", "ai" and " ai " are one keyword. The database has a unique index on the
 * normalised value, but the normalisation itself is a product decision (trim,
 * case-fold, strip a leading @ from a handle) and it belongs in one function
 * rather than at every call site. The unique index then backs the promise up
 * instead of being the only thing making it: a duplicate that slips past the
 * service is a conflict rather than a second rule that behaves differently.
 */

export type BrainRotTargetKind = 'keyword' | 'channel';

export interface BrainRotKeyword {
  readonly id: string;
  readonly keyword: string;
  readonly reason: string | null;
  readonly enabled: boolean;
  readonly reports: number;
  readonly createdAt: string | null;
}

export interface BrainRotChannel {
  readonly id: string;
  readonly handle: string;
  readonly displayName: string | null;
  readonly reason: string | null;
  readonly enabled: boolean;
  readonly reports: number;
  readonly createdAt: string | null;
}

export interface BrainRotRules {
  readonly keywords: BrainRotKeyword[];
  readonly channels: BrainRotChannel[];
}

export type BrainRotSubmissionStatus = 'pending' | 'approved' | 'rejected' | 'under_review';

/**
 * Where a submission came from.
 *
 * 'youtube_not_interested' is a decision the user made inside YouTube; 'app' is
 * one they typed here. A reviewer weighing a request is entitled to know which,
 * so the origin travels with the row rather than being guessed from the value.
 */
export type BrainRotSubmissionSource = 'youtube_not_interested' | 'app' | 'unknown';

export interface BrainRotSubmission {
  readonly id: string;
  readonly kind: BrainRotTargetKind;
  readonly value: string;
  /** The channel's name as the user saw it, for display. Null for keywords. */
  readonly displayName: string | null;
  readonly note: string | null;
  readonly source: BrainRotSubmissionSource;
  readonly status: BrainRotSubmissionStatus;
  /** Distinct devices that reported the same target. */
  readonly reports: number;
  /** Distinct devices that submitted this target, across every status. */
  readonly requesters: number;
  readonly createdAt: string | null;
}

// ── Normalisation ────────────────────────────────────────────────────────

/**
 * A keyword as it is stored and compared: trimmed, whitespace-collapsed and
 * case-folded.
 *
 * Folded with an explicit locale rather than the default one: a server whose
 * locale lower-cases a capital I to something else would otherwise produce rules
 * that behave differently depending on where it runs.
 */
export function normalizeKeyword(input: string): string {
  return input.trim().toLocaleLowerCase('en-US').replace(/\s+/g, ' ');
}

/**
 * A channel handle as it is stored and compared: one leading "@", folded.
 *
 * Returns null for anything that is not a plausible handle, so a pasted URL or a
 * bare "@" is a refused request rather than a rule that can never match.
 */
export function normalizeHandle(input: string): string | null {
  const withoutAt = input.trim().replace(/^@+/, '').trim();
  if (withoutAt === '') return null;
  const body = withoutAt.toLocaleLowerCase('en-US');
  if (!/^[a-z0-9._-]{2,100}$/.test(body)) return null;
  return `@${body}`;
}

/** The stored value for a target, or a 400 naming what was wrong with it. */
export function normalizeTarget(kind: BrainRotTargetKind, raw: string): string {
  if (kind === 'keyword') {
    const keyword = normalizeKeyword(raw);
    if (keyword.length < 1 || keyword.length > 80) {
      throw badRequest('invalid_keyword', 'A keyword must be between 1 and 80 characters.');
    }
    return keyword;
  }
  const handle = normalizeHandle(raw);
  if (handle === null) {
    throw badRequest('invalid_channel_handle', 'Enter a channel handle like @examplechannel.');
  }
  return handle;
}

// ── Anonymous devices ────────────────────────────────────────────────────

/**
 * Remember a device that has contributed, and return its id.
 *
 * The id is generated by the app, so this is an upsert keyed on it: a device
 * that reports twice keeps one row and one `first_seen_at`. A malformed id is
 * refused rather than stored, because a column of arbitrary strings would make
 * the per-device uniqueness on reports and submissions meaningless.
 */
export async function touchDevice(database: Queryable, anonymousId: string): Promise<string> {
  if (!isUuid(anonymousId)) {
    throw badRequest('invalid_anonymous_id', 'That device id is not a valid identifier.');
  }
  await database.query(
    `INSERT INTO brainrot_devices (anonymous_id) VALUES ($1)
     ON CONFLICT (anonymous_id) DO UPDATE SET last_seen_at = now()`,
    [anonymousId]
  );
  return anonymousId;
}

// ── Global keywords ──────────────────────────────────────────────────────

/**
 * The report counts for a set of targets, keyed by value.
 *
 * One query for the whole list rather than one per row: a list of two hundred
 * keywords would otherwise be two hundred round trips for a number that is only
 * ever displayed.
 */
async function reportCounts(
  database: Queryable,
  kind: BrainRotTargetKind
): Promise<Map<string, number>> {
  const rows = await database.query<{ value: string; total: number }>(
    `SELECT value, COUNT(*)::int AS total FROM brainrot_reports
      WHERE kind = $1::brainrot_target_kind GROUP BY value`,
    [kind]
  );
  return new Map(rows.map((r) => [r.value, Number(r.total)]));
}

/**
 * How many distinct devices asked for each target GLOBALLY, keyed `kind:value`.
 *
 * A different number from [reportCounts] on purpose. A report is "this is bad"
 * (the report button); a submission is "everyone should block this" (the submit
 * button). The dashboard shows both, because "247 devices blocked it, 182 asked
 * for it globally" is the sentence an operator needs to weigh a queue.
 *
 * Counted over EVERY status, not just pending: a target that was already
 * approved and then asked for by four hundred more people is a strong signal,
 * and a count that reset on approval would hide it.
 */
async function requesterCounts(database: Queryable): Promise<Map<string, number>> {
  const rows = await database.query<{ kind: BrainRotTargetKind; value: string; total: number }>(
    `SELECT kind, value, COUNT(DISTINCT anonymous_id)::int AS total
       FROM brainrot_submissions GROUP BY kind, value`
  );
  return new Map(rows.map((r) => [`${r.kind}:${r.value}`, Number(r.total)]));
}

/** Every global keyword, for the phone to cache and the admin to manage. */
export async function listGlobalKeywords(
  database: Queryable,
  options: { readonly includeDisabled?: boolean } = {}
): Promise<BrainRotKeyword[]> {
  const rows = await database.query<{
    id: string;
    keyword: string;
    reason: string | null;
    enabled: boolean;
    created_at: unknown;
  }>(
    `SELECT id, keyword, reason, enabled, created_at FROM brainrot_keywords
      ${options.includeDisabled ? '' : 'WHERE enabled = true'}
      ORDER BY keyword`
  );
  const counts = await reportCounts(database, 'keyword');
  return rows.map((row) => ({
    id: row.id,
    keyword: row.keyword,
    reason: row.reason,
    enabled: row.enabled,
    reports: counts.get(row.keyword) ?? 0,
    createdAt: isoOrNull(row.created_at),
  }));
}

/** Add a global keyword. Idempotent on the normalised value. */
export async function addGlobalKeyword(
  database: Queryable,
  rawKeyword: string,
  reason: string | null = null
): Promise<BrainRotKeyword> {
  const keyword = normalizeTarget('keyword', rawKeyword);
  const row = await database.queryOne<{
    id: string;
    keyword: string;
    reason: string | null;
    enabled: boolean;
    created_at: unknown;
  }>(
    `INSERT INTO brainrot_keywords (keyword, reason) VALUES ($1, $2)
     ON CONFLICT (keyword) DO UPDATE SET reason = COALESCE(EXCLUDED.reason, brainrot_keywords.reason)
     RETURNING id, keyword, reason, enabled, created_at`,
    [keyword, reason?.trim() || null]
  );
  if (!row) throw new Error('[brainrot] keyword insert returned no row');
  const counts = await reportCounts(database, 'keyword');
  return {
    id: row.id,
    keyword: row.keyword,
    reason: row.reason,
    enabled: row.enabled,
    reports: counts.get(row.keyword) ?? 0,
    createdAt: isoOrNull(row.created_at),
  };
}

/** Enable, disable or delete a global keyword. */
export async function setGlobalKeywordEnabled(
  database: Queryable,
  id: string,
  enabled: boolean
): Promise<void> {
  if (!isUuid(id)) throw notFound('keyword_not_found');
  const row = await database.queryOne<{ id: string }>(
    `UPDATE brainrot_keywords SET enabled = $2 WHERE id = $1 RETURNING id`,
    [id, enabled]
  );
  if (!row) throw notFound('keyword_not_found');
}

export async function deleteGlobalKeyword(database: Queryable, id: string): Promise<void> {
  if (!isUuid(id)) throw notFound('keyword_not_found');
  const row = await database.queryOne<{ id: string }>(
    `DELETE FROM brainrot_keywords WHERE id = $1 RETURNING id`,
    [id]
  );
  if (!row) throw notFound('keyword_not_found');
}

// ── Global channels ──────────────────────────────────────────────────────

export async function listGlobalChannels(
  database: Queryable,
  options: { readonly includeDisabled?: boolean } = {}
): Promise<BrainRotChannel[]> {
  const rows = await database.query<{
    id: string;
    handle: string;
    display_name: string | null;
    reason: string | null;
    enabled: boolean;
    created_at: unknown;
  }>(
    `SELECT id, handle, display_name, reason, enabled, created_at FROM brainrot_channels
      ${options.includeDisabled ? '' : 'WHERE enabled = true'}
      ORDER BY handle`
  );
  const counts = await reportCounts(database, 'channel');
  return rows.map((row) => ({
    id: row.id,
    handle: row.handle,
    displayName: row.display_name,
    reason: row.reason,
    enabled: row.enabled,
    reports: counts.get(row.handle) ?? 0,
    createdAt: isoOrNull(row.created_at),
  }));
}

/** Add a global channel. Idempotent on the normalised handle. */
export async function addGlobalChannel(
  database: Queryable,
  rawHandle: string,
  displayName: string | null = null,
  reason: string | null = null
): Promise<BrainRotChannel> {
  const handle = normalizeTarget('channel', rawHandle);
  const row = await database.queryOne<{
    id: string;
    handle: string;
    display_name: string | null;
    reason: string | null;
    enabled: boolean;
    created_at: unknown;
  }>(
    `INSERT INTO brainrot_channels (handle, display_name, reason) VALUES ($1, $2, $3)
     ON CONFLICT (handle) DO UPDATE SET
       display_name = COALESCE(EXCLUDED.display_name, brainrot_channels.display_name),
       reason = COALESCE(EXCLUDED.reason, brainrot_channels.reason)
     RETURNING id, handle, display_name, reason, enabled, created_at`,
    [handle, displayName?.trim() || null, reason?.trim() || null]
  );
  if (!row) throw new Error('[brainrot] channel insert returned no row');
  const counts = await reportCounts(database, 'channel');
  return {
    id: row.id,
    handle: row.handle,
    displayName: row.display_name,
    reason: row.reason,
    enabled: row.enabled,
    reports: counts.get(row.handle) ?? 0,
    createdAt: isoOrNull(row.created_at),
  };
}

export async function setGlobalChannelEnabled(
  database: Queryable,
  id: string,
  enabled: boolean
): Promise<void> {
  if (!isUuid(id)) throw notFound('channel_not_found');
  const row = await database.queryOne<{ id: string }>(
    `UPDATE brainrot_channels SET enabled = $2 WHERE id = $1 RETURNING id`,
    [id, enabled]
  );
  if (!row) throw notFound('channel_not_found');
}

export async function deleteGlobalChannel(database: Queryable, id: string): Promise<void> {
  if (!isUuid(id)) throw notFound('channel_not_found');
  const row = await database.queryOne<{ id: string }>(
    `DELETE FROM brainrot_channels WHERE id = $1 RETURNING id`,
    [id]
  );
  if (!row) throw notFound('channel_not_found');
}

// ── The combined snapshot ────────────────────────────────────────────────

/** The whole active rule set, in one call, for a phone to cache. */
export async function getGlobalRules(database: Queryable): Promise<BrainRotRules> {
  const [keywords, channels] = await Promise.all([
    listGlobalKeywords(database),
    listGlobalChannels(database),
  ]);
  return { keywords, channels };
}

// ── Submissions ──────────────────────────────────────────────────────────

/**
 * Suggest a keyword or a channel for the GLOBAL repository.
 *
 * Deliberately inert: this writes a row in the review queue and touches no rule.
 * A suggestion only becomes a rule through [reviewSubmission], which is an
 * administrator's action.
 *
 * Re-submitting the same value from the same device is a no-op rather than an
 * error: the app's button can be tapped twice, and a user seeing a failure for
 * something that worked the first time is worse than an idempotent success.
 */
export async function submitSuggestion(
  database: Queryable,
  anonymousId: string,
  kind: BrainRotTargetKind,
  rawValue: string,
  note: string | null = null,
  source: BrainRotSubmissionSource = 'unknown',
  displayName: string | null = null
): Promise<{ readonly status: 'pending'; readonly value: string }> {
  const value = normalizeTarget(kind, rawValue);
  await touchDevice(database, anonymousId);
  await database.query(
    `INSERT INTO brainrot_submissions (kind, value, note, anonymous_id, source, display_name)
     VALUES ($1::brainrot_target_kind, $2, $3, $4, $5, $6)
     ON CONFLICT (kind, value, anonymous_id) WHERE status IN ('pending', 'under_review') DO NOTHING`,
    [kind, value, note?.trim() || null, anonymousId, source, displayName?.trim() || null]
  );
  return { status: 'pending', value };
}

/**
 * The submissions one device has made, newest first.
 *
 * This is what lets the app show a user the fate of their own request —
 * "pending", "approved", "rejected" — without an account. The anonymous id IS
 * the identity here, which is why it is generated on the device and never
 * derived from anything about the phone.
 */
export async function listDeviceSubmissions(
  database: Queryable,
  anonymousId: string
): Promise<BrainRotSubmission[]> {
  if (!isUuid(anonymousId)) {
    throw badRequest('invalid_anonymous_id', 'That device id is not a valid identifier.');
  }
  const rows = await database.query<{
    id: string;
    kind: BrainRotTargetKind;
    value: string;
    display_name: string | null;
    note: string | null;
    source: BrainRotSubmissionSource;
    status: BrainRotSubmissionStatus;
    created_at: unknown;
  }>(
    `SELECT id, kind, value, display_name, note, source, status, created_at
       FROM brainrot_submissions
      WHERE anonymous_id = $1
      ORDER BY created_at DESC
      LIMIT 200`,
    [anonymousId]
  );
  const keywordCounts = await reportCounts(database, 'keyword');
  const channelCounts = await reportCounts(database, 'channel');
  const requesters = await requesterCounts(database);
  return rows.map((row) => ({
    id: row.id,
    kind: row.kind,
    value: row.value,
    displayName: row.display_name,
    note: row.note,
    source: row.source,
    status: row.status,
    reports: (row.kind === 'keyword' ? keywordCounts.get(row.value) : channelCounts.get(row.value)) ?? 0,
    requesters: requesters.get(`${row.kind}:${row.value}`) ?? 0,
    createdAt: isoOrNull(row.created_at),
  }));
}

/** The review queue. Oldest first, which is the order an operator works through it. */
export async function listSubmissions(
  database: Queryable,
  status: BrainRotSubmissionStatus | 'all' = 'pending'
): Promise<BrainRotSubmission[]> {
  const rows = await database.query<{
    id: string;
    kind: BrainRotTargetKind;
    value: string;
    display_name: string | null;
    note: string | null;
    source: BrainRotSubmissionSource;
    status: BrainRotSubmissionStatus;
    created_at: unknown;
  }>(
    `SELECT id, kind, value, display_name, note, source, status, created_at
       FROM brainrot_submissions
      ${status === 'all' ? '' : 'WHERE status = $1'}
      ORDER BY created_at ASC
      LIMIT 500`,
    status === 'all' ? [] : [status]
  );
  const counts = await reportCounts(database, 'keyword');
  const channelCounts = await reportCounts(database, 'channel');
  const requesters = await requesterCounts(database);
  return rows.map((row) => ({
    id: row.id,
    kind: row.kind,
    value: row.value,
    displayName: row.display_name,
    note: row.note,
    source: row.source,
    status: row.status,
    reports: (row.kind === 'keyword' ? counts.get(row.value) : channelCounts.get(row.value)) ?? 0,
    requesters: requesters.get(`${row.kind}:${row.value}`) ?? 0,
    createdAt: isoOrNull(row.created_at),
  }));
}

/**
 * Approve or reject a submission.
 *
 * Approving is the ONLY path from a user's suggestion to a global rule, and it
 * happens in one transaction with the rule it creates, so an approval can never
 * leave a submission marked approved whose rule was not written.
 */
export async function reviewSubmission(
  database: Queryable,
  submissionId: string,
  decision: 'approved' | 'rejected' | 'under_review',
  adminId: string | null
): Promise<{ readonly submission: BrainRotSubmission; readonly ruleId: string | null }> {
  if (!isUuid(submissionId)) throw notFound('submission_not_found');

  return database.transaction(async (tx) => {
    const row = await tx.queryOne<{
      id: string;
      kind: BrainRotTargetKind;
      value: string;
      display_name: string | null;
      note: string | null;
      source: BrainRotSubmissionSource;
      status: string;
    }>(
      `SELECT id, kind, value, display_name, note, source, status
         FROM brainrot_submissions WHERE id = $1 FOR UPDATE`,
      [submissionId]
    );
    if (!row) throw notFound('submission_not_found');
    // Only a FINAL decision is refused on a decided row. Marking an
    // already-under-review suggestion as under review again is a no-op, and a
    // reviewer changing their mind from under-review to approved or rejected is
    // the ordinary way a queue is worked.
    if ((row.status === 'approved' || row.status === 'rejected') && row.status !== decision) {
      throw badRequest('submission_already_reviewed', 'That suggestion has already been reviewed.');
    }

    let ruleId: string | null = null;
    if (decision === 'approved') {
      if (row.kind === 'keyword') {
        const created = await tx.queryOne<{ id: string }>(
          `INSERT INTO brainrot_keywords (keyword, reason) VALUES ($1, $2)
           ON CONFLICT (keyword) DO UPDATE SET reason = COALESCE(EXCLUDED.reason, brainrot_keywords.reason)
           RETURNING id`,
          [row.value, row.note]
        );
        ruleId = created?.id ?? null;
      } else {
        const created = await tx.queryOne<{ id: string }>(
          `INSERT INTO brainrot_channels (handle, reason) VALUES ($1, $2)
           ON CONFLICT (handle) DO UPDATE SET reason = COALESCE(EXCLUDED.reason, brainrot_channels.reason)
           RETURNING id`,
          [row.value, row.note]
        );
        ruleId = created?.id ?? null;
      }
    }

    await tx.query(
      `UPDATE brainrot_submissions
          SET status = $2,
              reviewed_at = now(),
              reviewed_by_admin_id = $3,
              approved_keyword_id = CASE WHEN $4 = 'keyword' THEN $5::uuid ELSE approved_keyword_id END,
              approved_channel_id = CASE WHEN $4 = 'channel' THEN $5::uuid ELSE approved_channel_id END
        WHERE id = $1`,
      [submissionId, decision, adminId, row.kind, ruleId]
    );

    return {
      submission: {
        id: row.id,
        kind: row.kind,
        value: row.value,
        displayName: row.display_name,
        note: row.note,
        source: row.source,
        status: decision,
        reports: 0,
        requesters: 0,
        createdAt: null,
      },
      ruleId,
    };
  });
}

// ── Dashboard ────────────────────────────────────────────────────────────

/** One target's demand, as the dashboard lists it. */
export interface BrainRotDemandRow {
  readonly kind: BrainRotTargetKind;
  readonly value: string;
  readonly displayName: string | null;
  /** Distinct devices that reported it (the "block" count). */
  readonly usersBlocking: number;
  /** Distinct devices that asked for it globally (the "request" count). */
  readonly globalRequests: number;
  /** The most recent status among its submissions. */
  readonly status: BrainRotSubmissionStatus | null;
}

/**
 * The counts and the demand list behind the admin dashboard.
 *
 * One call rather than five, because the dashboard draws them together and a
 * number that arrived separately could disagree with the list beside it.
 *
 * The two counts are deliberately different and both are DERIVED:
 *   * `usersBlocking` — distinct devices that reported the target.
 *   * `globalRequests` — distinct devices that submitted it for global review.
 * A materialised counter for either would be a second source of truth that can
 * drift from the rows it counts, and this is a number an operator acts on.
 */
export async function getDashboard(database: Queryable): Promise<{
  readonly totals: {
    readonly pending: number;
    readonly approved: number;
    readonly rejected: number;
    readonly underReview: number;
    readonly all: number;
  };
  readonly topChannels: BrainRotDemandRow[];
  readonly topKeywords: BrainRotDemandRow[];
}> {
  const statusRows = await database.query<{ status: string; total: number }>(
    `SELECT status, COUNT(*)::int AS total FROM brainrot_submissions GROUP BY status`
  );
  const byStatus = new Map(statusRows.map((r) => [r.status, Number(r.total)]));

  const demand = async (kind: BrainRotTargetKind): Promise<BrainRotDemandRow[]> => {
    const rows = await database.query<{
      value: string;
      display_name: string | null;
      requesters: number;
      reporters: number;
      status: string | null;
    }>(
      `SELECT s.value,
              MAX(s.display_name) AS display_name,
              COUNT(DISTINCT s.anonymous_id)::int AS requesters,
              COALESCE(r.reporters, 0)::int AS reporters,
              MAX(s.status) AS status
         FROM brainrot_submissions s
         LEFT JOIN (
           SELECT value, COUNT(DISTINCT anonymous_id)::int AS reporters
             FROM brainrot_reports WHERE kind = $1::brainrot_target_kind
            GROUP BY value
         ) r ON r.value = s.value
        WHERE s.kind = $1::brainrot_target_kind
        GROUP BY s.value, r.reporters
        ORDER BY requesters DESC, s.value
        LIMIT 50`,
      [kind]
    );
    return rows.map((row) => ({
      kind,
      value: row.value,
      displayName: row.display_name,
      usersBlocking: Number(row.reporters),
      globalRequests: Number(row.requesters),
      status: (row.status as BrainRotSubmissionStatus | null) ?? null,
    }));
  };

  return {
    totals: {
      pending: byStatus.get('pending') ?? 0,
      approved: byStatus.get('approved') ?? 0,
      rejected: byStatus.get('rejected') ?? 0,
      underReview: byStatus.get('under_review') ?? 0,
      all: statusRows.reduce((sum, r) => sum + Number(r.total), 0),
    },
    topChannels: await demand('channel'),
    topKeywords: await demand('keyword'),
  };
}

// ── Reports ──────────────────────────────────────────────────────────────

/**
 * Report a keyword or a channel.
 *
 * One report per device per target, enforced by a unique index, so the count is
 * a count of distinct devices rather than of taps. Reporting the same thing
 * twice from one device is an idempotent success: the number already includes
 * it, and a failure would only teach the user that the button is broken.
 *
 * A report never blocks anything. It is demand signal for whoever reviews the
 * rules, which is what keeps a coordinated group of reporters from being able
 * to censor a keyword on their own.
 */
export async function reportTarget(
  database: Queryable,
  anonymousId: string,
  kind: BrainRotTargetKind,
  rawValue: string,
  detail: string | null = null
): Promise<{ readonly reports: number }> {
  const value = normalizeTarget(kind, rawValue);
  await touchDevice(database, anonymousId);
  await database.query(
    `INSERT INTO brainrot_reports (kind, value, anonymous_id, detail)
     VALUES ($1::brainrot_target_kind, $2, $3, $4)
     ON CONFLICT (kind, value, anonymous_id) DO NOTHING`,
    [kind, value, anonymousId, detail?.trim() || null]
  );
  const row = await database.queryOne<{ total: number }>(
    `SELECT COUNT(*)::int AS total FROM brainrot_reports
      WHERE kind = $1::brainrot_target_kind AND value = $2`,
    [kind, value]
  );
  return { reports: Number(row?.total ?? 0) };
}
