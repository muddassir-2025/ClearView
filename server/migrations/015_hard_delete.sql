-- 015_hard_delete.sql
--
-- Deleting a channel means deleting it.
--
-- The previous version flagged a channel with `deleted_at` and filtered it out of
-- every read, which left the rows, the login bound to it and the media objects in
-- place forever. Good Post has one delete, so the flag goes and the four things
-- below make a real delete correct rather than merely possible.
--
-- No BEGIN/COMMIT here: src/migrate.ts wraps each file in its own transaction
-- and records the checksum, so a migration is all-or-nothing.

-- ── 1. An audit row outlives the account it names ───────────────────────
--
-- This is the one link that blocked the delete. `posts.channel_id`,
-- `post_media.channel_id` and `admin_users.channel_id` all CASCADE from
-- `channels`, so removing the row already takes the posts, the attachments and
-- the login created to run it — but `admin_audit_logs.admin_id` is NO ACTION, so
-- the first administrator with any history (everyone who ever signed in) made
-- the delete fail with a foreign-key violation.
--
-- The constraint is REMOVED rather than changed, and the reason is a conflict
-- that is worth writing down: the obvious fix is SET NULL, and SET NULL cannot
-- work here. `admin_audit_logs` is immutable by trigger (008), so the UPDATE that
-- a cascade of SET NULL performs is refused by the database — the delete would
-- still fail, with a different error. The only alternatives were to weaken the
-- trigger or to delete the rows, and both trade away the one property this table
-- has: that it is a record nothing can rewrite, including us.
--
-- So `admin_id` becomes what it always was in substance — an identifier of who
-- acted, not a live reference to an account that must exist. After a channel is
-- deleted, the id of the administrator who ran it stops resolving while the row
-- that carries it is untouched, and `admin_email` is snapshotted on every row, so
-- "who did this" is still answerable from the log alone. A trail that is
-- rewritten as its subjects come and go is a trail you cannot trust.
ALTER TABLE admin_audit_logs DROP CONSTRAINT IF EXISTS admin_audit_logs_admin_id_fkey;

-- ── 2. The posts the previous product left behind ───────────────────────
--
-- Migration 012 dropped the tables behind polls and audio, but kept the posts:
-- rows with no body, no media and a type nothing can render. They were being
-- filtered out at read time, so they were invisible to readers and visible to
-- writers of every affected query. Deleted here instead, so the invariant below
-- can be stated once in the database rather than re-checked on every read.
--
-- `post_media` rows for these posts cascade away with them. There are none in
-- practice; a poll had no attachment, and an audio post never shipped.
DELETE FROM posts WHERE type IN ('poll', 'audio');

-- ── 3. A post is one of the four shapes the composer produces ───────────
--
-- Enum labels cannot be dropped from an existing type without rebuilding the
-- type and every dependent index, and none of that buys anything here. A CHECK
-- states the same invariant, is enforced by the database, and costs one line.
--
-- The four shapes are what §21 asks for: text, image + text, video + text, link.
-- The write path already derives the type from the attachments rather than
-- trusting a client, so this is a second lock on a door that is also locked —
-- which is the point, because the first lock is the one a future edit removes.
ALTER TABLE posts DROP CONSTRAINT IF EXISTS posts_type_is_renderable;
ALTER TABLE posts
  ADD CONSTRAINT posts_type_is_renderable
  CHECK (type IN ('text', 'image', 'video', 'link'));

-- ── 4. A channel is deleted, not flagged ────────────────────────────────
--
-- The partial index has to be restated rather than altered: it was filtered on
-- `deleted_at IS NULL AND status = 'active'`, and dropping the column takes the
-- index with it. `status = 'active'` stays — a suspended channel is the one
-- non-public state left, and it must not be paged through by Explore.
DROP INDEX IF EXISTS channels_activity_idx;
ALTER TABLE channels DROP COLUMN IF EXISTS deleted_at;
CREATE INDEX IF NOT EXISTS channels_activity_idx
  ON channels (COALESCE(last_post_at, created_at) DESC, id DESC)
  WHERE status = 'active';
