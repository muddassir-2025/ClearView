-- 014_brainrot.sql
--
-- The global Brain Rot protection repository, plus the anonymous reporting that
-- feeds it.
--
-- WHY THIS FILE EXISTS
-- --------------------
-- ClearView's blocking has always been local: the keyword list and the blocked
-- channels live on the phone. That is the right default — it needs no account,
-- it works offline, and nothing about a person's browsing leaves the device.
-- What it cannot do is let a keyword that a hundred people have independently
-- decided is junk become a rule for the next person, and that is the whole
-- point of a GLOBAL repository.
--
-- The model has three distinct things in it, and the schema keeps them apart on
-- purpose because the spec is explicit that one user's mistake must not become
-- everyone's rule:
--
--   * brainrot_keywords / brainrot_channels   — the ACTIVE global rules. Only an
--     administrator writes these. A phone fetches them and blocks on them.
--   * brainrot_submissions                    — a user's SUGGESTION that
--     something should be global. It is inert: it blocks nothing anywhere until
--     an administrator approves it, at which point it becomes a row in one of
--     the two tables above.
--   * brainrot_reports                        — the community signal ("1,284
--     reports"). A report is a vote on an existing rule, and it is counted, not
--     acted on automatically.
--
-- ANONYMITY
-- ---------
-- There is no account here and there is deliberately nothing to make one from.
-- A device is identified by `anonymous_id`: a random UUID the app generates for
-- itself and can regenerate at any time. It is not derived from a phone number,
-- an Android ID, an advertising id, an email or anything else the device
-- already carries — it is a value that exists only because the app made one up,
-- which is what makes "anonymous" a description of the data rather than a
-- promise about how it is treated.
--
-- Uniqueness is per (anonymous_id, target) rather than global, so one device
-- cannot inflate a count by reporting the same thing repeatedly, while two
-- different devices genuinely add two votes.
--
-- WHAT IS DELIBERATELY NOT HERE
-- -----------------------------
--  * No user id, no email, no phone, no IP. There is nothing in this file to
--    join a report to a person, which is the property the Blocking tab's
--    Privacy card states.
--  * No "confidence" or AI-assigned score. The spec's first version is
--    transparent and explainable: something is blocked because it matched a
--    keyword or a channel, and the count of reports is the only ranking.
--  * No soft-delete on a global rule. A rule is either active or it is not;
--    `enabled` is that switch, and removing one is a delete with the audit trail
--    in `brainrot_submissions`/the admin log rather than a resurrection flag.

-- ── Anonymous devices ────────────────────────────────────────────────────
-- One row per app install that has ever contributed or reported. Kept so a
-- count cannot be inflated by one device, and so an operator can see how many
-- distinct devices stand behind a number.
CREATE TABLE IF NOT EXISTS brainrot_devices (
  anonymous_id uuid PRIMARY KEY,
  first_seen_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at  timestamptz NOT NULL DEFAULT now()
);

-- ── Global blocked keywords ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS brainrot_keywords (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  -- Stored already-normalised (trimmed, lowercased) by the service, so the
  -- unique index below is what actually prevents "AI" and "ai" being two rules.
  keyword     text NOT NULL,
  -- The user-facing explanation. Never null: a rule a reader cannot be told the
  -- reason for is a rule they cannot argue with.
  reason      text,
  enabled     boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT brainrot_keywords_length CHECK (char_length(keyword) BETWEEN 1 AND 80)
);

-- One rule per spelling of a keyword.
CREATE UNIQUE INDEX IF NOT EXISTS brainrot_keywords_keyword_idx
  ON brainrot_keywords (keyword);

DROP TRIGGER IF EXISTS brainrot_keywords_set_updated_at ON brainrot_keywords;
CREATE TRIGGER brainrot_keywords_set_updated_at
  BEFORE UPDATE ON brainrot_keywords
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── Global blocked channels ──────────────────────────────────────────────
-- Identified by HANDLE, not by name: a channel can rename itself at any time,
-- and a block that is keyed on the display name is lost the moment it does.
CREATE TABLE IF NOT EXISTS brainrot_channels (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  -- Normalised to a single leading "@", lowercased.
  handle       text NOT NULL,
  -- For display only. Never used to match.
  display_name text,
  reason       text,
  enabled      boolean NOT NULL DEFAULT true,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT brainrot_channels_handle_shape CHECK (handle ~ '^@[a-z0-9._-]{2,100}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS brainrot_channels_handle_idx
  ON brainrot_channels (handle);

DROP TRIGGER IF EXISTS brainrot_channels_set_updated_at ON brainrot_channels;
CREATE TRIGGER brainrot_channels_set_updated_at
  BEFORE UPDATE ON brainrot_channels
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── Community submissions ────────────────────────────────────────────────
-- A suggestion that something should be GLOBAL. Inert until approved: nothing
-- reads this table to decide whether to block anything, which is what stops one
-- user's accidental submission from affecting everybody.
--
-- `approved_*_id` points at the rule it became, so a submission's outcome is
-- recorded rather than being a row that was simply deleted.
CREATE TYPE brainrot_target_kind AS ENUM ('keyword', 'channel');

CREATE TABLE IF NOT EXISTS brainrot_submissions (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind            brainrot_target_kind NOT NULL,
  -- The normalised keyword, or the "@handle" for a channel.
  value           text NOT NULL,
  note            text,
  -- pending -> approved | rejected. Only an administrator moves it.
  status          text NOT NULL DEFAULT 'pending',
  anonymous_id    uuid REFERENCES brainrot_devices (anonymous_id) ON DELETE SET NULL,
  reviewed_at     timestamptz,
  -- Who decided, for the audit trail. Nullable/SET NULL for the same reason
  -- `posts.author_id` is: a decision outlives the account that made it.
  reviewed_by_admin_id uuid REFERENCES admin_users (id) ON DELETE SET NULL,
  approved_keyword_id uuid REFERENCES brainrot_keywords (id) ON DELETE SET NULL,
  approved_channel_id uuid REFERENCES brainrot_channels (id) ON DELETE SET NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT brainrot_submissions_status CHECK (status IN ('pending', 'approved', 'rejected')),
  CONSTRAINT brainrot_submissions_value_length CHECK (char_length(value) BETWEEN 1 AND 120)
);

-- One pending suggestion per device per value: a device that taps submit twice
-- does not create two entries in the review queue, while a hundred devices
-- asking for the same thing produce a hundred rows an operator can see and
-- weigh together.
CREATE UNIQUE INDEX IF NOT EXISTS brainrot_submissions_pending_idx
  ON brainrot_submissions (kind, value, anonymous_id)
  WHERE status = 'pending';

-- The review queue: oldest pending first, which is the order an operator works
-- through them.
CREATE INDEX IF NOT EXISTS brainrot_submissions_queue_idx
  ON brainrot_submissions (status, created_at);

-- ── Reports ──────────────────────────────────────────────────────────────
-- The community count behind a rule ("542 reports"). Counted, never acted on
-- automatically: a rule is enabled or disabled by an administrator, and this
-- table is what tells them how much demand there is.
--
-- One report per (device, target), enforced by the unique index, so the number
-- is a count of distinct devices and not of taps.
CREATE TABLE IF NOT EXISTS brainrot_reports (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind         brainrot_target_kind NOT NULL,
  -- The keyword value or the "@handle" being reported.
  value        text NOT NULL,
  anonymous_id uuid NOT NULL REFERENCES brainrot_devices (anonymous_id) ON DELETE CASCADE,
  -- What the reporter said the problem was. Free text, bounded, and never used
  -- to make a decision on its own.
  detail       text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT brainrot_reports_value_length CHECK (char_length(value) BETWEEN 1 AND 120)
);

CREATE UNIQUE INDEX IF NOT EXISTS brainrot_reports_once_idx
  ON brainrot_reports (kind, value, anonymous_id);

CREATE INDEX IF NOT EXISTS brainrot_reports_target_idx
  ON brainrot_reports (kind, value);

-- ── Seeded starter rules ─────────────────────────────────────────────────
-- The examples the product spec names, so a fresh deployment has a global
-- repository that does something visible instead of an empty list. Idempotent,
-- and an operator can disable or delete any of them.
INSERT INTO brainrot_keywords (keyword, reason) VALUES
  ('brainrot',  'Low-value, repetitive short-form content.'),
  ('viral',     'Engagement-bait phrasing.'),
  ('aesthetic', 'Repetitive lifestyle filler.'),
  ('sigma',     'Trend slang associated with low-value content.')
ON CONFLICT (keyword) DO NOTHING;
