-- 009_advertisements.sql
--
-- Administrator-controlled promotional cards (§9–§17). These are NOT Google
-- Ads and no ad SDK is involved: each row is created, edited and removed by the
-- platform's super administrator, and shown at the top of Channels and Explore
-- in the Good Post tab.
--
-- Reuses the existing object-storage lifecycle for a card's picture. An image
-- ad's bytes are a `post_media` row (request → confirm → claim) exactly like a
-- post attachment or a channel icon, so there is ONE upload path, one signature
-- and one abandoned-upload sweep instead of three that could disagree.
-- `post_media.advertisement_id` is what marks a row as an ad's image, and the
-- sweep excludes it for the same reason it excludes a channel icon.

DO $$ BEGIN
  CREATE TYPE ad_content_type AS ENUM ('image', 'text');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS advertisements (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  content_type     ad_content_type NOT NULL,

  -- S3 OBJECT KEY only, never bytes and never a URL: a stored URL expires, a key
  -- can be signed again. Mirrors `channels.icon_object_key`; the bytes live in
  -- the bucket, the `post_media` row with this ad's id and a NULL `post_id`
  -- proves the object exists.
  image_object_key text
                     CHECK (image_object_key IS NULL
                            OR char_length(image_object_key) <= 512),

  -- For a text ad: the whole card. Multiline, so the admin can lay out an
  -- offer and its contact line.
  text_content     text
                     CHECK (text_content IS NULL OR char_length(text_content) <= 1000),

  -- Optional destination. `https`/`http` opens in a browser; `mailto:` opens the
  -- device's email composer (the default card uses this). A card with no URL
  -- simply does not navigate anywhere.
  target_url       text
                     CHECK (target_url IS NULL
                            OR target_url ~* '^https?://[^[:space:]]+$'
                            OR target_url ~* '^mailto:[^[:space:]]+$'),

  -- Two INDEPENDENT placements (§11). Both may be on, one on, or neither —
  -- "neither" is allowed but warned about in the editor, because an ad that
  -- appears nowhere is almost always a mistake.
  show_in_channels boolean NOT NULL DEFAULT true,
  show_in_explore  boolean NOT NULL DEFAULT true,

  -- A disabled ad is kept but never publicly returned, so an admin can pause one
  -- without deleting it.
  enabled          boolean NOT NULL DEFAULT true,

  -- The active window (§11). `expires_at` NULL means "no expiry". An expired or
  -- not-yet-started ad is filtered out of every public read, so no manual
  -- cleanup is ever required.
  starts_at        timestamptz NOT NULL DEFAULT now(),
  expires_at       timestamptz,

  -- Carousel ordering, lower first. Ties break on creation time.
  priority         integer NOT NULL DEFAULT 100,

  -- Who created it, for the audit trail. NULLABLE/SET NULL for the same reason
  -- `posts.author_id` is: content outlives its author.
  created_by_admin_id uuid REFERENCES admin_users(id) ON DELETE SET NULL,

  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),

  -- A card must carry what its own type promises: an image ad has an object, a
  -- text ad has words.
  CONSTRAINT advertisements_content_check CHECK (
    (content_type = 'image' AND image_object_key IS NOT NULL)
    OR (content_type = 'text' AND text_content IS NOT NULL AND btrim(text_content) <> '')
  ),

  -- An expiry that is not after the start is a window that can never open.
  CONSTRAINT advertisements_expiry_after_start CHECK (
    expires_at IS NULL OR expires_at > starts_at
  )
);

-- The public read: active, newest-priority first.
CREATE INDEX IF NOT EXISTS advertisements_active_idx
  ON advertisements (priority, created_at DESC);

-- The placement filter.
CREATE INDEX IF NOT EXISTS advertisements_placement_idx
  ON advertisements (show_in_channels, show_in_explore, enabled);

DROP TRIGGER IF EXISTS advertisements_set_updated_at ON advertisements;
CREATE TRIGGER advertisements_set_updated_at
  BEFORE UPDATE ON advertisements
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── A card's image, on the existing media lifecycle ─────────────────────
ALTER TABLE post_media
  ADD COLUMN IF NOT EXISTS advertisement_id uuid
    REFERENCES advertisements(id) ON DELETE CASCADE;

-- At most one image per ad, enforced by the database rather than the write path
-- (the same shape as `post_media_channel_icon_idx`).
CREATE UNIQUE INDEX IF NOT EXISTS post_media_advertisement_image_idx
  ON post_media (advertisement_id)
  WHERE advertisement_id IS NOT NULL;

-- ── The default editable card (§13) ─────────────────────────────────────
-- A NORMAL row, never hardcoded UI: the admin can edit it, disable it, delete
-- it, change its text, image, destination, placement and duration. Tapping it
-- opens the device's email composer (the `mailto:` target). Seeded idempotently.
INSERT INTO advertisements (
  id, content_type, text_content, target_url,
  show_in_channels, show_in_explore, enabled, priority
) VALUES (
  '00000000-0000-4000-8000-000000000001',
  'text',
  E'YOUR AD HERE FOR $2\n\nContact: studymuddassir@gmail.com',
  'mailto:studymuddassir@gmail.com?subject=Advertisement%20on%20Good%20Post',
  true, true, true, 10
)
ON CONFLICT (id) DO NOTHING;
