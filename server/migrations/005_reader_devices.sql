-- 005_reader_devices.sql
--
-- Where a reader's phone is, when they have asked to be told about new posts
-- (§8, §12).
--
-- WHY THIS FILE EXISTS
-- --------------------
-- Until now, "notify me when a channel posts" was answered by the phone itself:
-- a periodic WorkManager job asked the backend what the reader's channels had
-- published and raised a local notification for anything new. That is honest and
-- cheap, and its floor is fifteen minutes — WorkManager will not run a periodic
-- job more often than that. Fifteen minutes is fine for a channel and wrong for
-- the feeling the product is going for: WhatsApp tells you when a channel posts,
-- and it does it in seconds.
--
-- Instant delivery needs a way for the SERVER to reach a specific phone, and
-- that is all a push token is. It is a routing address, not an identity: it says
-- "this install", it is minted and rotated by Firebase, and it is meaningless on
-- any other device.
--
-- WHAT IS DELIBERATELY NOT HERE
-- -----------------------------
--  * No device name, model, OS version or app version. Nothing reads them, and a
--    table of facts about a reader's phone is a privacy cost with no feature
--    behind it.
--  * No notification PREFERENCES. Whether a reader wants to be told is the
--    device's own switch (`GoodPostNotifications`), and whether they want to hear
--    from ONE channel is `channel_follows.notifications_muted`, which is already
--    a property of the follow and already survives a reinstall. A third place to
--    store "do not tell me" is a third place for the three to disagree.
--  * No delivery history. The client already de-duplicates by publish time, and
--    a table of what was sent to whom would be a record of a reader's reading
--    that no feature asks for.
--
-- WHO WRITES IT
-- -------------
-- The reader's own authenticated app, through
-- `POST /api/v1/readers/me/devices`, and nobody else: a token names a device, so
-- only the device that holds it may register it. The reader id comes from the
-- verified Firebase token, never from the request body.
--
-- WHY THE TOKEN IS UNIQUE AND NOT (reader, token)
-- ----------------------------------------------
-- A token belongs to one install, and an install has one signed-in reader at a
-- time. If a second reader signs in on the same phone, the row must MOVE rather
-- than be duplicated — otherwise the first reader keeps receiving the second
-- reader's channel notifications forever, on a phone they no longer use. So the
-- uniqueness is on the token alone and registration is an upsert that rewrites
-- the owner.
--
-- WHY THE CASCADE IS HERE
-- -----------------------
-- A reader row is deleted only by an operator or a cleanup, and when it is, the
-- tokens that pointed at it have nobody left to notify. `ON DELETE CASCADE`
-- makes that one statement rather than a sweep that has to remember this table.

CREATE TABLE IF NOT EXISTS reader_devices (
  id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  reader_id    uuid        NOT NULL REFERENCES readers (id) ON DELETE CASCADE,
  -- The Firebase Cloud Messaging registration token. Opaque, long-lived until
  -- it is rotated, and the one thing a send needs.
  token        text        NOT NULL,
  -- Only 'android' exists today. Noted as a column rather than assumed, because
  -- a second client is the reason a "platform" field ever appears, and adding it
  -- later means a migration on a table of live tokens.
  platform     text        NOT NULL DEFAULT 'android',
  created_at   timestamptz NOT NULL DEFAULT now(),
  -- Refreshed on every registration, so a stale row can be recognised without
  -- asking Firebase about it.
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reader_devices_token_not_blank CHECK (length(token) BETWEEN 16 AND 4096)
);

-- The upsert's conflict target, and the only column a send ever looks up.
CREATE UNIQUE INDEX IF NOT EXISTS reader_devices_token_idx
  ON reader_devices (token);

-- "Every device belonging to the readers who follow this channel" starts from
-- the follow rows, so this index is what keeps the fan-out proportional to the
-- followers rather than to the table.
CREATE INDEX IF NOT EXISTS reader_devices_reader_idx
  ON reader_devices (reader_id);
