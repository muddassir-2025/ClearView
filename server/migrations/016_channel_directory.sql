-- 016_channel_directory.sql
--
-- The curated channel directory: a hand-picked list of YouTube, Instagram and X
-- channels, grouped into categories and subcategories, that every ClearView
-- reader can browse and jump to.
--
-- WHY THIS EXISTS
-- ---------------
-- ClearView already knows how to follow a channel INSIDE Good Post and how to
-- block one everywhere else. What it has never had is a way to say "here are the
-- channels worth following" — a list a platform administrator curates once and
-- every reader sees. That is this.
--
-- The channels are NOT ClearView channels. They are external accounts on other
-- platforms, identified by a HANDLE, and tapping one opens that platform's page
-- for it. Nothing here is posted, stored or moderated by ClearView — the row is a
-- bookmark with a name and an icon.
--
-- WHAT IS NOT HERE
-- ----------------
--  * No reader data. The directory is the same for everybody and there is no
--    per-reader row to join anything to.
--  * No fetched content. A channel's name and icon are copied from the platform
--    when it is added (best-effort, see `directory/enrich.ts`) and then stored;
--    nothing re-fetches them on a timer, and no post, video or follower count is
--    ever read from the platform.
--
-- IDENTITY
-- --------
-- A channel is (platform, handle) and nothing else. The name is a label that can
-- change and the icon is a URL that can rot; the unique index is on the pair, so
-- the same handle cannot be added twice to one platform and the same handle CAN
-- exist on two platforms (which is normal — people use the same one everywhere).

-- ── Categories ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS directory_categories (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name        text NOT NULL,
  -- Derived from the name (see the service), stable once created: it is the
  -- client's key for a tab, and a slug that moved when the name was corrected
  -- would reset every reader's selected category.
  slug        text NOT NULL,
  -- Ascending. Ties fall back to the name, so the order is always total.
  sort        integer NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT directory_categories_name_length CHECK (char_length(name) BETWEEN 1 AND 80),
  CONSTRAINT directory_categories_slug_shape CHECK (slug ~ '^[a-z0-9-]{1,80}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS directory_categories_slug_idx
  ON directory_categories (slug);

DROP TRIGGER IF EXISTS directory_categories_set_updated_at ON directory_categories;
CREATE TRIGGER directory_categories_set_updated_at
  BEFORE UPDATE ON directory_categories
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── Subcategories ────────────────────────────────────────────────────────
-- One level of nesting, deliberately. A directory a reader browses on a phone
-- has room for a category and one refinement of it; a third level is a tree
-- nobody navigates.
CREATE TABLE IF NOT EXISTS directory_subcategories (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  category_id uuid NOT NULL REFERENCES directory_categories (id) ON DELETE CASCADE,
  name        text NOT NULL,
  slug        text NOT NULL,
  sort        integer NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT directory_subcategories_name_length CHECK (char_length(name) BETWEEN 1 AND 80),
  CONSTRAINT directory_subcategories_slug_shape CHECK (slug ~ '^[a-z0-9-]{1,80}$')
);

-- Unique within its parent: two categories may each have a "Shorts", and the
-- slug only has to be unambiguous inside the category it belongs to.
CREATE UNIQUE INDEX IF NOT EXISTS directory_subcategories_slug_idx
  ON directory_subcategories (category_id, slug);

CREATE INDEX IF NOT EXISTS directory_subcategories_category_idx
  ON directory_subcategories (category_id, sort);

DROP TRIGGER IF EXISTS directory_subcategories_set_updated_at ON directory_subcategories;
CREATE TRIGGER directory_subcategories_set_updated_at
  BEFORE UPDATE ON directory_subcategories
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── Channels ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS directory_channels (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  platform       text NOT NULL,
  -- Normalised (no leading "@", lowercased) by the service before it is stored,
  -- so the unique index below is what actually stops "@Foo" and "foo" being two
  -- rows.
  handle         text NOT NULL,
  -- Copied from the platform when the channel is added, or set by the
  -- administrator. Display only — the handle is identity.
  name           text,
  icon_url       text,
  -- Both nullable and both SET NULL on delete: removing a category must not
  -- delete the channels filed under it, it must only unfile them.
  category_id    uuid REFERENCES directory_categories (id) ON DELETE SET NULL,
  subcategory_id uuid REFERENCES directory_subcategories (id) ON DELETE SET NULL,
  sort           integer NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT directory_channels_platform CHECK (platform IN ('youtube', 'instagram', 'x')),
  -- The same shape the platforms themselves allow, minus the case and the "@".
  CONSTRAINT directory_channels_handle_shape CHECK (handle ~ '^[a-z0-9._-]{1,100}$'),
  CONSTRAINT directory_channels_name_length CHECK (name IS NULL OR char_length(name) <= 200),
  CONSTRAINT directory_channels_icon_length CHECK (icon_url IS NULL OR char_length(icon_url) <= 1024)
);

-- One row per handle per platform.
CREATE UNIQUE INDEX IF NOT EXISTS directory_channels_handle_idx
  ON directory_channels (platform, handle);

-- The read is always "everything in this category", in sort order.
CREATE INDEX IF NOT EXISTS directory_channels_category_idx
  ON directory_channels (category_id, subcategory_id, sort);

DROP TRIGGER IF EXISTS directory_channels_set_updated_at ON directory_channels;
CREATE TRIGGER directory_channels_set_updated_at
  BEFORE UPDATE ON directory_channels
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── Seeded categories ────────────────────────────────────────────────────
-- A fresh deployment gets a shape to fill rather than an empty screen. The
-- administrator can rename, reorder or delete any of them; they are a starting
-- point, not a fixed schema.
INSERT INTO directory_categories (name, slug, sort) VALUES
  ('Islamic',     'islamic',     10),
  ('Education',   'education',   20),
  ('Technology',  'technology',  30),
  ('News',        'news',        40),
  ('Entertainment','entertainment', 50)
ON CONFLICT (slug) DO NOTHING;
