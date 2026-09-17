-- 014_channel_icons.sql
--
-- A channel's profile image becomes a real, managed asset: uploaded through the
-- same presigned handshake as post media, claimed by the channel, and replaced
-- or removed without leaving an orphan in the bucket.
--
-- Why `post_media` and not a table of its own
-- -------------------------------------------
-- The upload lifecycle is already here and it is the part that can be wrong:
-- presign → PUT → confirm (a HEAD, not a client's word) → claim. A profile image
-- needs every one of those steps and nothing else. A second `channel_icons`
-- table would duplicate the status machine, the object-key derivation, the
-- size/type validation and the abandoned-upload sweep — four things that would
-- then be able to disagree with their twins.
--
-- What it needs beyond a post attachment is one fact: the asset belongs to a
-- CHANNEL rather than to a post. That is the column below.
--
-- No BEGIN/COMMIT here: src/migrate.ts wraps each file in its own transaction.

-- ── 1. An asset may belong to a channel ─────────────────────────────────
--
-- Nullable, and NULL for every existing row: a post attachment belongs to a
-- post. `ON DELETE CASCADE` because an asset owned by a channel that no longer
-- exists is exactly the row the sweep would otherwise keep forever.
ALTER TABLE post_media
  ADD COLUMN IF NOT EXISTS channel_id uuid REFERENCES channels(id) ON DELETE CASCADE;

-- ── 2. One profile image per channel ────────────────────────────────────
--
-- At most one row per channel with an unset `post_id`, which is what "this is the
-- channel's icon" means. Enforced in the database rather than by the write path,
-- because the write path is the thing that could forget to release the previous
-- one — and a channel with two live icon rows has no defined answer to "which
-- object is the icon", including for the code that deletes it.
--
-- Partial, so post attachments (`post_id IS NOT NULL`) are unaffected and a
-- channel may hold any number of those.
CREATE UNIQUE INDEX IF NOT EXISTS post_media_channel_icon_idx
  ON post_media (channel_id)
  WHERE channel_id IS NOT NULL AND post_id IS NULL;

-- The sweep's own scan: "claimed by nobody at all" is now a question about two
-- columns, so the index that answers it names both.
CREATE INDEX IF NOT EXISTS post_media_abandoned_idx
  ON post_media (created_at)
  WHERE post_id IS NULL AND channel_id IS NULL;

-- ── 3. An icon is not a post attachment ─────────────────────────────────
--
-- The existing constraint says a CLAIMED row must be ready, which is what stops
-- a post attaching an upload that was never confirmed. The same must hold for an
-- icon, and the check is on the row rather than on the caller.
DO $$ BEGIN
  ALTER TABLE post_media
    ADD CONSTRAINT post_media_claimed_channel_is_ready
    CHECK (channel_id IS NULL OR status = 'ready');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── 4. A channel administrator may set their own channel's image ────────
--
-- Nothing to add here: `channels.update` already covers it and is held by both
-- roles, which is the intended reading of §18 ("change channel image"). This
-- note exists so the next reader does not go looking for a permission that was
-- never needed.
