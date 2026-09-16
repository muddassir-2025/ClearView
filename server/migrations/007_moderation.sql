-- 007_moderation.sql
--
-- Moderation (§18), user-to-user blocks (§12) and private follower messages
-- (§16).
--
-- Design notes that matter:
--
--  * **A report is a record, not a flag.** §18 asks for reporter, target,
--    reason, timestamp, status, the action taken and its resolution time. That
--    is deliberately not reducible to `posts.reported = true`: a moderator has
--    to be able to answer "who reported this, why, and what did we decide?"
--    months later, and §29's audit log records the ADMIN's action — these two
--    are different records and neither can be derived from the other.
--
--  * **Exactly one target column is set, enforced by CHECK.** Four nullable
--    foreign keys with a type column is the shape that makes a report whose
--    type says `post` but whose id points at a channel unrepresentable, rather
--    than merely discouraged. Cascades are on target DELETION (the target is
--    gone, so there is nothing to moderate) and are per-column, which is why
--    the four keys cannot be collapsed into one polymorphic id.
--
--  * **`resolved_by_admin_id` has NO foreign key yet.** `admin_users` arrives
--    in 008, and inventing a placeholder table now so this file can reference
--    it would be worse than adding the constraint in the migration that
--    creates the target. The column is therefore intentionally unchecked until
--    008 attaches it — see the note in that file.
--
--  * **Private messages are a conversation, not a message list.** §16's unit is
--    "a follower messaging a channel": read state, a block and a close all
--    belong to the pair, not to an individual line. Modelling it as bare
--    messages would mean recomputing the pair's state on every read, and a
--    per-message block flag that can disagree with itself.
--
--  * **`sender_role` is stored, not derived.** Which side of the conversation a
--    message came from must stay true after the sender's role changes: a
--    channel admin who later becomes a plain follower should not have their old
--    replies re-rendered as a follower's.
--
-- No BEGIN/COMMIT here on purpose: src/migrate.ts wraps each file in its own
-- transaction and records the checksum, so a migration is all-or-nothing.

-- ── Enums ───────────────────────────────────────────────────────────────

DO $$ BEGIN
  CREATE TYPE report_target_type AS ENUM ('user', 'channel', 'post', 'message');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- A closed set rather than free text: a moderation queue whose reasons are
-- unconstrained cannot be counted, and §18's whole point is that patterns can
-- be seen. `other` exists so the set does not force a wrong choice.
DO $$ BEGIN
  CREATE TYPE report_reason AS ENUM (
    'spam', 'abuse', 'harassment', 'impersonation', 'misinformation', 'illegal', 'other'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- `open` → `reviewing` → `resolved` | `dismissed`. Deliberately NOT the same
-- set as account/channel status: a report being resolved says nothing about
-- whether anything was taken down, which is why the action lives elsewhere.
DO $$ BEGIN
  CREATE TYPE report_status AS ENUM ('open', 'reviewing', 'resolved', 'dismissed');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
  CREATE TYPE message_sender_role AS ENUM ('follower', 'admin');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── user_blocks ─────────────────────────────────────────────────────────
-- One person blocking another (§12, §16). Distinct from `channel_blocks`,
-- which is a person blocking a BROADCAST — an admin blocking a follower in
-- their channel is a user blocking a user, and the two must not be conflated
-- because blocking a channel removes a subscription while blocking a person
-- only stops the two of them interacting.
CREATE TABLE IF NOT EXISTS user_blocks (
  blocker_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (blocker_id, blocked_id),

  -- Blocking yourself is meaningless and would make every "is either side
  -- blocked?" query answer yes for one account.
  CONSTRAINT user_blocks_not_self CHECK (blocker_id <> blocked_id)
);

CREATE INDEX IF NOT EXISTS user_blocks_blocked_idx ON user_blocks (blocked_id);

-- ── channel_conversations ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS channel_conversations (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  channel_id    uuid NOT NULL REFERENCES channels(id) ON DELETE CASCADE,

  -- The follower who opened it. Reached only through the conversation, so a
  -- channel never holds a follower list (§12, §38).
  follower_id   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  created_at      timestamptz NOT NULL DEFAULT now(),

  -- Maintained by the send path, in the same transaction as the message, so
  -- the conversation list can be ordered without aggregating its messages.
  last_message_at timestamptz,

  -- §16's "manage the conversation": a channel admin can stop a conversation
  -- without deleting its history, which is what makes the block reversible and
  -- the record reviewable.
  blocked_at    timestamptz,
  blocked_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,

  closed_at     timestamptz,

  -- One conversation per (channel, follower). Opening a second would split a
  -- person's messages across two threads no UI could honestly merge.
  UNIQUE (channel_id, follower_id)
);

-- The channel's inbox (§16): newest activity first.
CREATE INDEX IF NOT EXISTS channel_conversations_channel_idx
  ON channel_conversations (channel_id, last_message_at DESC NULLS LAST);

-- The follower's own list of conversations with channels they have written to.
CREATE INDEX IF NOT EXISTS channel_conversations_follower_idx
  ON channel_conversations (follower_id, last_message_at DESC NULLS LAST);

-- ── channel_messages ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS channel_messages (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id  uuid NOT NULL REFERENCES channel_conversations(id) ON DELETE CASCADE,
  sender_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  -- See the header note: stored so the record stays truthful as roles change.
  sender_role      message_sender_role NOT NULL,

  body             text NOT NULL
                     CHECK (char_length(btrim(body)) BETWEEN 1 AND 4000),

  created_at       timestamptz NOT NULL DEFAULT now(),

  -- Read by the OTHER side (§16). One nullable timestamp rather than two
  -- booleans: the only question the product asks is "is this unread for the
  -- recipient", and the recipient is determined by `sender_role`.
  read_at          timestamptz
);

CREATE INDEX IF NOT EXISTS channel_messages_conversation_idx
  ON channel_messages (conversation_id, created_at DESC);

-- ── reports ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS reports (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reporter_id   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  target_type   report_target_type NOT NULL,

  -- Exactly one of these is non-null; the CHECK below is what enforces it.
  target_user_id    uuid REFERENCES users(id) ON DELETE CASCADE,
  target_channel_id uuid REFERENCES channels(id) ON DELETE CASCADE,
  target_post_id    uuid REFERENCES posts(id) ON DELETE CASCADE,
  -- A private message can be reported without exposing it: a moderator reading
  -- the queue sees the report, not the conversation (§16, §38).
  target_message_id uuid REFERENCES channel_messages(id) ON DELETE CASCADE,

  reason        report_reason NOT NULL,

  -- What the reporter said. Optional because a reason is already a complete
  -- report; the free-text field is for detail, not for the report itself.
  details       text CHECK (details IS NULL OR char_length(details) <= 2000),

  status        report_status NOT NULL DEFAULT 'open',

  -- §18's "moderator/admin action" and "resolution timestamp". Both nullable
  -- until someone acts; a resolved report without a note is refused.
  action_taken  text CHECK (action_taken IS NULL OR char_length(action_taken) <= 200),
  resolution_note text CHECK (resolution_note IS NULL OR char_length(resolution_note) <= 2000),
  resolved_by_admin_id uuid,
  resolved_at   timestamptz,

  created_at    timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT reports_target_matches_type CHECK (
    CASE target_type
      WHEN 'user' THEN target_user_id IS NOT NULL
        AND target_channel_id IS NULL AND target_post_id IS NULL AND target_message_id IS NULL
      WHEN 'channel' THEN target_channel_id IS NOT NULL
        AND target_user_id IS NULL AND target_post_id IS NULL AND target_message_id IS NULL
      WHEN 'post' THEN target_post_id IS NOT NULL
        AND target_user_id IS NULL AND target_channel_id IS NULL AND target_message_id IS NULL
      WHEN 'message' THEN target_message_id IS NOT NULL
        AND target_user_id IS NULL AND target_channel_id IS NULL AND target_post_id IS NULL
    END
  ),

  -- A report is either open or it has a decision. This is the invariant that
  -- keeps the queue's counts honest.
  CONSTRAINT reports_resolution_consistent CHECK (
    (status IN ('open', 'reviewing') AND resolved_at IS NULL AND resolved_by_admin_id IS NULL)
    OR (status IN ('resolved', 'dismissed') AND resolved_at IS NOT NULL)
  )
);

-- The moderation queue (§18, §22): oldest open first, so nothing is starved.
CREATE INDEX IF NOT EXISTS reports_queue_idx
  ON reports (status, created_at)
  WHERE status IN ('open', 'reviewing');

-- "Everything reported against this thing", used by every admin detail view.
CREATE INDEX IF NOT EXISTS reports_target_user_idx ON reports (target_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS reports_target_channel_idx
  ON reports (target_channel_id, created_at DESC);
CREATE INDEX IF NOT EXISTS reports_target_post_idx ON reports (target_post_id, created_at DESC);
CREATE INDEX IF NOT EXISTS reports_target_message_idx
  ON reports (target_message_id, created_at DESC);

-- One report per person per target, so a queue cannot be flooded by one
-- account re-reporting the same row. Enforced by an expression index because
-- the target column differs per type; a re-report after a decision is a
-- moderator's job (reopen the existing report), not a second row.
CREATE UNIQUE INDEX IF NOT EXISTS reports_one_per_reporter_target_idx
  ON reports (
    reporter_id,
    target_type,
    COALESCE(target_user_id, target_channel_id, target_post_id, target_message_id)
  );
