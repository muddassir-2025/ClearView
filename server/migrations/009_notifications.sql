-- 009_notifications.sql
--
-- Channel notifications (§17) and device registration for push (§17, §30).
--
-- Design notes that matter:
--
--  * **Two tables, because there are two different things.** A `notifications`
--    row is the durable record — it is what an inbox shows, and it survives a
--    device being lost, reinstalled or offline for a week. A `device_tokens`
--    row is a delivery address, which is worthless the moment the app is
--    uninstalled and must be revocable without touching anyone's history.
--    Collapsing them would mean either losing the inbox when a token dies or
--    keeping delivery addresses forever.
--
--  * **Notifications are written even when push is unavailable.** §17 asks for
--    notifications, and a deployment with `FCM_ENABLED=false` should still fill
--    the inbox rather than silently dropping the feature. Push is a delivery
--    mechanism, not the feature.
--
--  * **`dedupe_key` is UNIQUE per user.** A fan-out that runs twice — a retried
--    job, a publish that is retried by a client — must not double-notify. The
--    key is derived from the cause (`post:<id>`), so the database refuses the
--    second one rather than the code having to remember.
--
--  * **NO notification is stored for a muted channel.** Muting is per-follow
--    state (§17), and the fan-out filters on it. Marking a delivered
--    notification "muted" after the fact would leave the row and the state
--    disagreeing, and a client that showed the row would be right to complain.
--
--  * **`device_tokens.token` is globally UNIQUE, not per user.** A device that
--    signs into a second account must move its token, not duplicate it —
--    otherwise the previous account keeps receiving that device's pushes, which
--    is a privacy leak as well as a battery drain.
--
-- No BEGIN/COMMIT here on purpose: src/migrate.ts wraps each file in its own
-- transaction and records the checksum, so a migration is all-or-nothing.

-- ── Enums ───────────────────────────────────────────────────────────────

-- What a notification is about. Kept small and explicit: a client switches on
-- this to decide where tapping it goes, and free text would make that guesswork.
DO $$ BEGIN
  CREATE TYPE notification_kind AS ENUM ('channel_post', 'channel_message', 'platform_notice');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
  CREATE TYPE device_platform AS ENUM ('android', 'ios', 'web');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── device_tokens ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS device_tokens (
  token        text PRIMARY KEY
                 CHECK (char_length(token) BETWEEN 10 AND 400),

  -- The account currently signed in on this device. ON DELETE CASCADE: an
  -- account that is gone has no business holding delivery addresses.
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  platform     device_platform NOT NULL DEFAULT 'android',

  created_at   timestamptz NOT NULL DEFAULT now(),

  -- Refreshed on every register call, so a sweep can tell a live install from
  -- one that was uninstalled without unregistering (which Android never
  -- promises to do).
  last_seen_at timestamptz NOT NULL DEFAULT now(),

  -- Set when FCM reports the token is gone. The row is kept (it explains a
  -- delivery failure) but it is not sent to again.
  disabled_at  timestamptz
);

-- "Every live device for this account" — the fan-out lookup.
CREATE INDEX IF NOT EXISTS device_tokens_user_idx
  ON device_tokens (user_id)
  WHERE disabled_at IS NULL;

-- The staleness sweep (§34's sibling for delivery addresses).
CREATE INDEX IF NOT EXISTS device_tokens_seen_idx ON device_tokens (last_seen_at);

-- ── notifications ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notifications (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  kind          notification_kind NOT NULL,

  -- What it is about. CASCADE on the channel and nullable on the post: a
  -- removed post must not leave a notification pointing at content that no
  -- longer exists, and the notification itself is still a true record that the
  -- channel published something.
  channel_id    uuid REFERENCES channels(id) ON DELETE CASCADE,
  post_id       uuid REFERENCES posts(id) ON DELETE CASCADE,
  admin_message_id uuid REFERENCES admin_messages(id) ON DELETE CASCADE,

  title         text NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 200),
  body          text NOT NULL CHECK (char_length(btrim(body)) <= 500),

  -- See the header note. See [fanOutPostNotification] for how it is derived.
  dedupe_key    text NOT NULL CHECK (char_length(dedupe_key) BETWEEN 1 AND 200),

  created_at    timestamptz NOT NULL DEFAULT now(),
  read_at       timestamptz,

  -- A notification must point at something. Without this a row could be written
  -- that no client can render or act on.
  CONSTRAINT notifications_has_subject CHECK (
    channel_id IS NOT NULL OR post_id IS NOT NULL OR admin_message_id IS NOT NULL
  ),

  UNIQUE (user_id, dedupe_key)
);

-- The inbox (§17): newest first, one account at a time.
CREATE INDEX IF NOT EXISTS notifications_inbox_idx
  ON notifications (user_id, created_at DESC);

-- "What is unread?" — the badge, asked on every app start.
CREATE INDEX IF NOT EXISTS notifications_unread_idx
  ON notifications (user_id)
  WHERE read_at IS NULL;

-- The retention sweep's own scan (§11 applies to notifications too: an inbox
-- that grows forever is a table nobody prunes).
CREATE INDEX IF NOT EXISTS notifications_retention_idx ON notifications (created_at);
