-- 003_channels.sql
--
-- Channels, membership, follows, and blocks (§6, §7, §12).
--
-- Design notes that matter:
--
--  * `follower_count` is DENORMALISED, and that is deliberate. Discovery ranks
--    by popularity × recent activity (§5) and sorts on every search keystroke;
--    a `COUNT(*)` over channel_followers per row would turn each of those into
--    a scan. The counter is maintained inside the same transaction as the
--    follow/unfollow that changes it, so the two cannot drift.
--
--  * Channel-level roles live in `channel_admins`, NOT in `users`. A channel
--    owner holds no platform privilege: owning a channel must never be a path
--    to admin power (§48). The platform-admin system is a separate table that
--    arrives in migration 006.
--
--  * Follower identity is private (§12). `channel_followers` records who
--    follows what because follow/unfollow/mute are per-user state, but no
--    query here is ever exposed as a follower list — the API returns counts
--    and the viewer's own row, never another user's.
--
--  * `channel_categories` is a table rather than an enum or a CHECK list
--    because §5 asks for categories to be database-driven: adding one must not
--    require a migration.
--
--  * `icon_object_key` holds an S3 key, not bytes and not a URL. M2 has no
--    upload path (S3 is configured in M3) so this column stays null through
--    M2 — a channel created now gets an icon once M3 lands, and the API shape
--    does not change when that happens.
--
--  * No `pg_trgm` / full-text extension. Search is a plain case-insensitive
--    prefix/substring match, which is honest about what a few thousand
--    channels need. Adding a GIN trigram index later is additive and needs no
--    data migration; pulling in an extension now would couple every
--    environment (including the PGlite the suite runs on) to its presence.
--
-- No BEGIN/COMMIT here on purpose: src/migrate.ts wraps each file in its own
-- transaction and records the checksum, so a migration is all-or-nothing.

-- ── Enums ───────────────────────────────────────────────────────────────
-- Mirrors account_status from 001. A channel is moderated independently of
-- its owner's account: suspending a channel must not require banning a user,
-- and suspending a user must not silently erase their channel's history (§7).
DO $$ BEGIN
  CREATE TYPE channel_status AS ENUM ('active', 'suspended', 'banned');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- Roles INSIDE one channel. Named distinctly from the platform admin roles in
-- §28 (SUPER_ADMIN / ADMIN / MODERATOR) so a future reader cannot confuse a
-- channel moderator with a platform moderator.
DO $$ BEGIN
  CREATE TYPE channel_role AS ENUM ('owner', 'editor', 'responder');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── channel_categories ──────────────────────────────────────────────────
-- The §5 example list is seeded below. `slug` is the stable identifier the
-- Android client sends and stores, so a label can be reworded freely.
CREATE TABLE IF NOT EXISTS channel_categories (
  slug        text PRIMARY KEY
                CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
  label       text NOT NULL CHECK (char_length(btrim(label)) BETWEEN 1 AND 60),
  sort_order  integer NOT NULL DEFAULT 100,
  is_active   boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- Idempotent seed: re-running the migration cannot duplicate a category, and
-- ON CONFLICT DO NOTHING leaves an operator's later edits (label, sort_order)
-- untouched rather than resetting them.
INSERT INTO channel_categories (slug, label, sort_order) VALUES
  ('technology',    'Technology',    10),
  ('programming',   'Programming',   20),
  ('education',     'Education',     30),
  ('news',          'News',          40),
  ('sports',        'Sports',        50),
  ('entertainment', 'Entertainment', 60),
  ('islamic',       'Islamic',       70),
  ('local',         'Local',         80),
  ('other',         'Other',        999)
ON CONFLICT (slug) DO NOTHING;

-- ── channels ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS channels (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- RESTRICT, not CASCADE: deleting a user must never silently take a channel
  -- and its post history with it. §37 requires an explicit ownership-transfer
  -- or deletion policy, so the database refuses the shortcut.
  owner_id        uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

  -- Public, shareable deep-link identifier (§6). Distinct from `name`:
  -- channel names are display text and may repeat, a slug must not.
  slug            text NOT NULL UNIQUE
                    CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$'),

  name            text NOT NULL
                    CHECK (char_length(btrim(name)) BETWEEN 2 AND 120),
  description     text
                    CHECK (description IS NULL
                           OR char_length(description) <= 1000),

  -- S3 object key only. Null throughout M2 (no upload path until M3).
  icon_object_key text
                    CHECK (icon_object_key IS NULL
                           OR char_length(icon_object_key) <= 512),

  -- SET NULL rather than RESTRICT: retiring a category must not block, and
  -- the channel simply becomes uncategorised.
  category_slug   text REFERENCES channel_categories(slug) ON DELETE SET NULL,

  -- ISO-3166-1 alpha-2, upper-case. Kept as text rather than an enum so
  -- countries do not need a migration to add; the API validates against a
  -- fixed list rather than the database.
  country_code    char(2) CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),

  status          channel_status NOT NULL DEFAULT 'active',

  -- See the denormalisation note at the top of this file.
  follower_count  integer NOT NULL DEFAULT 0 CHECK (follower_count >= 0),

  -- Maintained in the same transaction as a post insert (M3). Present now
  -- because discovery ranks on it and the index below depends on the column
  -- existing; M2 simply writes nothing to it.
  post_count      integer NOT NULL DEFAULT 0 CHECK (post_count >= 0),

  -- §16: the channel owner decides whether followers may send private
  -- messages. Default false — messages are opt-in, so a channel created and
  -- forgotten cannot silently accept DMs.
  allow_follower_messages boolean NOT NULL DEFAULT false,

  -- The "recent activity" half of the §5 ranking. Nullable because a channel
  -- with no posts has never been active; discovery COALESCEs it to created_at
  -- so a brand-new channel is not ranked last forever.
  last_post_at    timestamptz,

  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),

  -- Soft deletion (§37). Nothing in M2 sets this; it exists so the eventual
  -- account-deletion policy has somewhere to land without a schema change.
  deleted_at      timestamptz
);

-- Discovery's default ordering (§5: popularity, then activity). Partial,
-- because every discovery query filters on it — a suspended or deleted
-- channel must never be returned.
CREATE INDEX IF NOT EXISTS channels_discovery_idx
  ON channels (follower_count DESC, id DESC)
  WHERE deleted_at IS NULL AND status = 'active';

-- The "new" and "active" tabs. Same partial predicate for the same reason.
CREATE INDEX IF NOT EXISTS channels_activity_idx
  ON channels (COALESCE(last_post_at, created_at) DESC, id DESC)
  WHERE deleted_at IS NULL AND status = 'active';

-- Category and country browsing.
CREATE INDEX IF NOT EXISTS channels_category_idx
  ON channels (category_slug, follower_count DESC)
  WHERE deleted_at IS NULL AND status = 'active';

CREATE INDEX IF NOT EXISTS channels_country_idx
  ON channels (country_code, follower_count DESC)
  WHERE deleted_at IS NULL AND status = 'active';

-- Case-insensitive substring search (§5). lower(name) rather than a trigram
-- index: see the extension note at the top. The leading-wildcard ILIKE cannot
-- use this index, so search is a scan over the active partial set — acceptable
-- at channel-count scale, and the migration to trigram is additive.
CREATE INDEX IF NOT EXISTS channels_name_lower_idx ON channels (lower(name));

-- "My channels" (§7) — the management view, which must include suspended and
-- soft-deleted channels so an owner can see what happened to them.
CREATE INDEX IF NOT EXISTS channels_owner_idx ON channels (owner_id);

-- ── channel_admins ──────────────────────────────────────────────────────
-- Delegated management inside one channel (§7). The owner row is inserted
-- with the channel, in the same transaction, so "a channel always has an
-- owner" is an invariant rather than a convention.
CREATE TABLE IF NOT EXISTS channel_admins (
  channel_id          uuid NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  user_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role                channel_role NOT NULL,

  -- Who granted this. Nullable for the owner row, which no one granted.
  granted_by_user_id  uuid REFERENCES users(id) ON DELETE SET NULL,

  created_at          timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (channel_id, user_id)
);

-- Exactly one owner per channel. The PRIMARY KEY allows a user to hold two
-- roles on a channel only if the id differs, so this partial unique index is
-- what actually prevents a second owner row.
CREATE UNIQUE INDEX IF NOT EXISTS channel_admins_single_owner
  ON channel_admins (channel_id)
  WHERE role = 'owner';

-- "Which channels may I manage?" — the authorization lookup on every
-- management request (§32).
CREATE INDEX IF NOT EXISTS channel_admins_user_idx ON channel_admins (user_id);

-- ── channel_followers ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS channel_followers (
  channel_id            uuid NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  user_id               uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  -- §17 mute/unmute. Mute is per-follow rather than a separate table because
  -- it only has meaning while the follow exists, and unfollowing should
  -- discard it.
  --
  -- NO DEFAULT on purpose. The product default is a configuration value
  -- (DEFAULT_NOTIFICATIONS_ENABLED), and a SQL default here would silently
  -- override it — the insert must state the intent explicitly, so the service
  -- is the single place that decides.
  notifications_enabled boolean NOT NULL,

  followed_at           timestamptz NOT NULL DEFAULT now(),

  -- §4 unread state. Compared against the channel's last_post_at rather than
  -- stored as a counter: a counter needs updating on every post for every
  -- follower, which is a write amplification the read-side comparison avoids.
  last_read_at          timestamptz,
  muted_at              timestamptz,

  PRIMARY KEY (channel_id, user_id)
);

-- The Channels view (§4): "everything I follow, most recently active first".
CREATE INDEX IF NOT EXISTS channel_followers_user_idx
  ON channel_followers (user_id, followed_at DESC);

-- ── channel_blocks ──────────────────────────────────────────────────────
-- A user blocking a channel (§12). Separate from following so the block
-- survives an unfollow, and separate from the moderation suspension in §18:
-- one is a personal choice, the other is a platform decision.
CREATE TABLE IF NOT EXISTS channel_blocks (
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  channel_id  uuid NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),

  -- Blocking also removes the follow, in the same transaction, because a
  -- blocked-but-followed channel is a state with no sensible meaning: it
  -- would still appear in the Channels view and still be counted as a
  -- follower.
  PRIMARY KEY (user_id, channel_id)
);

-- "Has this viewer blocked any of the channels in this page?" — asked once per
-- discovery page rather than per channel.
CREATE INDEX IF NOT EXISTS channel_blocks_channel_idx ON channel_blocks (channel_id);

-- ── updated_at maintenance ──────────────────────────────────────────────
-- Reuses set_updated_at() from 001.
DROP TRIGGER IF EXISTS channels_set_updated_at ON channels;
CREATE TRIGGER channels_set_updated_at
  BEFORE UPDATE ON channels
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
