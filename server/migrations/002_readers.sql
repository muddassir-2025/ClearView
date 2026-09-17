-- 002_readers.sql
--
-- The reader side of Good Post: who a reader is, what they follow, and how far
-- they have read (§3–§6).
--
-- WHY THIS FILE EXISTS
-- --------------------
-- 001 deliberately had no reader at all — "a READER HAS NO ROW ANYWHERE" — and
-- that was right for a broadcast product with no reader-side state. §3 changes
-- the requirement rather than reversing the reasoning: a reader still signs up
-- for nothing and types no email, password or phone number, but the app now
-- signs them in ANONYMOUSLY with Firebase, and that anonymous uid becomes the
-- key their own state hangs off:
--
--     Anonymous Firebase UID
--       ├── followed channels        (§4, §5)
--       ├── read position / unread   (§5)
--       ├── notification mute        (§6)
--       └── (later) reactions, favourites, votes, messages
--
-- So this adds the smallest set of tables that state needs. What has NOT
-- changed is that none of it is an ACCOUNT: there is no email column, no
-- password, no verification step and no way for a reader to be contacted. A row
-- in `readers` is a uid that has been seen, and deleting it would cost the
-- reader their follows and nothing else.
--
-- WHAT IS IN IT
-- -------------
--   readers          one row per anonymous Firebase uid the API has verified
--   channel_follows  a reader's relationship to a channel: followed, muted,
--                    and how much of it they have read
--
-- ONE TABLE FOR FOLLOW + MUTE + READ POSITION, on purpose. They are three facts
-- about one relationship, and splitting them would make "the reader's channels,
-- with their unread counts" a three-way join to answer a question the Android
-- home screen asks on every open (§5). It also settles what unfollowing means:
-- the relationship ends, so the read position and the mute end with it, which is
-- the behaviour a reader expects and the alternative — a follow row plus a
-- separate mute that outlives the follow — does not.
--
-- No BEGIN/COMMIT here: src/migrate.ts wraps each file in its own transaction.

-- ── readers ─────────────────────────────────────────────────────────────

-- The uid is the identity; `id` is the key everything else references.
--
-- A surrogate uuid rather than using the Firebase uid as the primary key, for
-- two reasons that both cost nothing now and are expensive to change later:
--
--  * The uid is EXTERNAL. Rebuilds and imports are a normal part of a Firebase
--    project's life, and a foreign key aimed at a value we do not mint turns
--    every one of them into a migration of every reader-scoped table.
--  * `id` is what a URL or a payload would carry. The uid is a bearer-adjacent
--    identifier — anyone holding it can impersonate the reader to this API
--    until the token expires — so it stays out of responses and out of logs
--    (§30), and is compared only where it is looked up.
--
-- `last_seen_at` is written on each authenticated request. It is the only
-- liveness signal this product has (a reader never signs in in a way we can
-- observe), and it is what makes "is this uid still in use?" answerable later.
--
-- No email column on purpose: §3 says a normal reader provides none, and a
-- column that could only ever be NULL is a column somebody will later fill from
-- a source that was not supposed to have one.
CREATE TABLE IF NOT EXISTS readers (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  firebase_uid  text NOT NULL UNIQUE
                  CHECK (char_length(firebase_uid) BETWEEN 1 AND 128),
  created_at    timestamptz NOT NULL DEFAULT now(),
  last_seen_at  timestamptz NOT NULL DEFAULT now()
);

-- Nothing queries readers by age today, and this exists for the one operation
-- that will: reclaiming rows that have not been seen in a very long time, which
-- needs an index to be a cheap bounded delete rather than a full scan.
CREATE INDEX IF NOT EXISTS readers_last_seen_idx ON readers (last_seen_at);

-- ── channel_follows ─────────────────────────────────────────────────────

-- The relationship between a reader and a channel (§4).
--
-- FOLLOWING IS NOT PERMISSION. Nothing here gates a read: every channel a
-- reader can see is readable without following it (§2, §7 — a reader who does
-- not follow a channel can still find it and read back through the retention
-- window). Following changes only what the reader's own home screen shows and
-- what they are notified about. That is why there is no visibility flag here to
-- get out of step with the channel's own status.
--
-- `ON DELETE CASCADE` in both directions, because a follow is a link and not a
-- record: deleting a channel (§17) should take its follows with it, and there
-- is no audit value in a follow whose channel is gone — every read filters on
-- the channel being active, so an orphaned follow could only ever render as a
-- channel the reader cannot open. The administrator audit log is where the
-- record of a deletion lives, and nothing here duplicates it.
--
-- `notifications_muted` rather than a `muted_until`: §6 offers mute/unmute, and
-- a mute with an expiry is a feature neither the UI nor the notification path
-- has — it would be a column every reader has to reason about to answer "am I
-- muted?".
--
-- `last_read_at` is the unread indicator (§5). NULL means "followed, and has
-- seen nothing yet", which is deliberately different from `followed_at`: it is
-- what makes a just-followed channel's existing history count as unread —
-- clamped by the API to posts published since the follow, so a channel with a
-- thousand old posts does not open with a thousand unread (§5's badge is a
-- nudge, not a backlog).
CREATE TABLE IF NOT EXISTS channel_follows (
  reader_id             uuid NOT NULL REFERENCES readers (id) ON DELETE CASCADE,
  channel_id            uuid NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
  notifications_muted   boolean NOT NULL DEFAULT false,
  followed_at           timestamptz NOT NULL DEFAULT now(),
  last_read_at          timestamptz,
  PRIMARY KEY (reader_id, channel_id)
);

-- The home screen's query: this reader's channels, most recently active first.
-- `channel_id` makes it an index-only scan of the reader's own follows rather
-- than a scan of the whole table (§5 opens this list on every app start).
CREATE INDEX IF NOT EXISTS channel_follows_reader_idx
  ON channel_follows (reader_id, followed_at DESC);

-- The fan-out direction, which the notification path (§8) needs: every reader
-- who follows THIS channel, filtered to the ones who have not muted it. A
-- partial index, because the muted rows are exactly the ones a fan-out must
-- skip and there is no query that wants them.
CREATE INDEX IF NOT EXISTS channel_follows_channel_unmuted_idx
  ON channel_follows (channel_id)
  WHERE notifications_muted = false;
