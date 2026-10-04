-- ── The false-positive queue ────────────────────────────────────────────────
--
-- brainrot_reports was designed as a bare COUNT behind a rule: an operator saw
-- "12 reports" on a rule and had no way to see WHICH term was reported, by whom
-- it was reached, or to act on it. The count is still the count — nothing here
-- changes what `reports` means — but a report now also has a life of its own as
-- an item somebody can resolve.
--
-- A report is raised from the block overlay's "Report false positive" option, so
-- every row here is a user saying "this block was wrong". The queue lists them
-- grouped by target; an operator either keeps the rule (the report was wrong) or
-- removes it (the report was right).
--
-- `resolution` is null while a report is still waiting, which is the only state
-- the queue reads. `kept` and `removed` are terminal — an operator can change
-- their mind about a RULE at any time from the Rules tab, but the report about it
-- has been answered, and re-answering it would make the queue a place that never
-- empties.
--
-- Resolving is per TARGET (kind + value) rather than per row: two hundred devices
-- reporting the same term is one decision, not two hundred. The columns live on
-- each row so the count and its outcome stay in one table.

ALTER TABLE brainrot_reports
  ADD COLUMN IF NOT EXISTS resolution text,
  ADD COLUMN IF NOT EXISTS resolved_at timestamptz,
  -- Who decided, for the audit trail. SET NULL for the same reason
  -- brainrot_submissions.reviewed_by_admin_id is: a decision outlives the
  -- account that made it.
  ADD COLUMN IF NOT EXISTS resolved_by_admin_id uuid REFERENCES admin_users (id) ON DELETE SET NULL;

-- 'removed' when the rule was switched off because of the reports, 'kept' when
-- the operator judged the reports wrong. No default: null means "still waiting".
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'brainrot_reports_resolution'
  ) THEN
    ALTER TABLE brainrot_reports
      ADD CONSTRAINT brainrot_reports_resolution
      CHECK (resolution IS NULL OR resolution IN ('kept', 'removed'));
  END IF;
END $$;

-- The queue's own read: unresolved reports, grouped by target. Partial, because
-- resolved rows are the history and are far more numerous than the open ones.
CREATE INDEX IF NOT EXISTS brainrot_reports_open_idx
  ON brainrot_reports (kind, value)
  WHERE resolution IS NULL;
