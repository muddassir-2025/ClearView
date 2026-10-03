-- 015_brainrot_sources.sql
--
-- Three changes to the global Brain Rot repository, all of them corrections of
-- assumptions made in 014.
--
--   1. The four seeded starter keywords are REMOVED. 014 seeded 'brainrot',
--      'viral', 'aesthetic' and 'sigma' so a fresh deployment had a repository
--      that did something visible. The product decision is the opposite: nothing
--      is blocked globally that a human did not ask for. A deployment with no
--      approved rules should block nothing globally, and the list should fill
--      from real submissions. Deleting them here (rather than only stopping the
--      seeding) is what fixes deployments that already ran 014.
--
--   2. A submission now records WHERE it came from and, for a channel, the name
--      as the user saw it. "YouTube Not interested" and "typed into the app" are
--      different kinds of evidence, and a reviewer weighing a request is entitled
--      to know which one they have. `source` is a bounded, enumerated string so
--      the dashboard can group by it without parsing free text.
--
--   3. `display_name` is stored beside the handle because the handle is the
--      identity (it survives a rename) while the name is what a person
--      recognises. It is optional everywhere.
--
-- A fourth change is deliberately NOT made: there is no `users_blocking` column.
-- "How many users blocked this" and "how many users requested it globally" are
-- two different numbers, and both are DERIVED — the first from the reports
-- table, the second from COUNT(*) over submissions. Materialising either would
-- create a second source of truth that can drift from the rows it counts.

-- ── 1. The seeded starter rules go ───────────────────────────────────────
--
-- Matched on the exact value AND the exact reason 014 wrote, so a deployment
-- where an operator has since edited one of these keywords (given it a real
-- reason, which is what editing one looks like) keeps it. Only the untouched
-- seed rows are removed.
DELETE FROM brainrot_keywords
 WHERE (keyword = 'brainrot'  AND reason = 'Low-value, repetitive short-form content.')
    OR (keyword = 'viral'     AND reason = 'Engagement-bait phrasing.')
    OR (keyword = 'aesthetic' AND reason = 'Repetitive lifestyle filler.')
    OR (keyword = 'sigma'     AND reason = 'Trend slang associated with low-value content.');

-- ── 2. Where a submission came from ──────────────────────────────────────

-- A bounded set of origins. 'unknown' rather than NOT NULL-without-default so
-- the column can be added to a populated table, and so an older client that
-- sends nothing is recorded honestly as unknown rather than rejected.
ALTER TABLE brainrot_submissions
  ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT 'unknown';

ALTER TABLE brainrot_submissions
  DROP CONSTRAINT IF EXISTS brainrot_submissions_source_check;
ALTER TABLE brainrot_submissions
  ADD CONSTRAINT brainrot_submissions_source_check
  CHECK (source IN ('youtube_not_interested', 'app', 'unknown'));

-- ── 3. The name a channel was shown under ────────────────────────────────
--
-- Display only. The handle remains the identity everywhere it matters.
ALTER TABLE brainrot_submissions
  ADD COLUMN IF NOT EXISTS display_name text;

ALTER TABLE brainrot_submissions
  DROP CONSTRAINT IF EXISTS brainrot_submissions_display_name_length;
ALTER TABLE brainrot_submissions
  ADD CONSTRAINT brainrot_submissions_display_name_length
  CHECK (display_name IS NULL OR char_length(display_name) BETWEEN 1 AND 200);

-- ── 4. "Under review" ────────────────────────────────────────────────────
--
-- The status vocabulary gains one value: an operator has picked a pending row
-- up but has not decided. It exists so a reviewer can mark "I am looking at
-- this" without either approving something they are unsure about or rejecting
-- it to get it out of the queue.
--
-- The existing CHECK is replaced rather than dropped-and-recreated blind: the
-- constraint name is 014's, and IF EXISTS keeps this runnable on a database
-- where it was already renamed.
ALTER TABLE brainrot_submissions
  DROP CONSTRAINT IF EXISTS brainrot_submissions_status;
ALTER TABLE brainrot_submissions
  ADD CONSTRAINT brainrot_submissions_status
  CHECK (status IN ('pending', 'approved', 'rejected', 'under_review'));

-- The one-pending-per-device index must not treat an under-review row as
-- decided: a device whose suggestion is being looked at still cannot open a
-- second identical one, which would be two rows for one request.
--
-- The index in 014 was defined on `status = 'pending'`; it is recreated here to
-- cover both non-final states. CREATE OR REPLACE is not available for indexes,
-- so it is dropped and rebuilt — safe because it is a uniqueness index over a
-- small queue and the rebuild is instant.
DROP INDEX IF EXISTS brainrot_submissions_pending_idx;
CREATE UNIQUE INDEX IF NOT EXISTS brainrot_submissions_open_idx
  ON brainrot_submissions (kind, value, anonymous_id)
  WHERE status IN ('pending', 'under_review');

-- ── 5. Indexes for the dashboard's group-bys ─────────────────────────────
--
-- The admin dashboard asks "which channels are most requested" and "which
-- keywords are most requested". Both are a GROUP BY over this table, so the
-- lookup key gets an index.
CREATE INDEX IF NOT EXISTS brainrot_submissions_target_idx
  ON brainrot_submissions (kind, value);
