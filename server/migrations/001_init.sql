-- 001_init.sql
--
-- The whole Good Post schema, in one file.
--
-- WHY ONE FILE
-- ------------
-- This replaces the sixteen-file chain (001_identity … 016_drop_dead_columns)
-- that built this schema. That chain is an accurate history of a product that
-- changed shape three times — an account-based social app with follows,
-- reactions, polls, reports, push tokens and mobile-number identity (001–011),
-- then a broadcast system with no reader accounts at all (012), then two
-- administrator roles and a managed channel image (013–016). Thirteen of those
-- files created things a later one dropped, so reading the chain to learn what
-- the database contains means reading the removal of nine tables, one account
-- model and one role model first. The database was rebuilt rather than migrated
-- (the deployment that used it no longer exists), so the history is not needed
-- to interpret any surviving row, and the chain is replaced by the schema it
-- ended at.
--
-- To rebuild a database from nothing:
--
--     npm run db:reset     # drop schema public, then migrate
--
-- `npm run migrate` applies this file like any migration: inside one
-- transaction, with its checksum recorded in `schema_migrations`. The runner is
-- forward-only and refuses to re-run a file whose contents have changed, so a
-- schema fix is a new file, not an edit to this one.
--
-- WHAT IS IN IT
-- -------------
--   channel_categories  what a channel is about; a table, not an enum, so
--                       adding a category is data and not a migration
--   channels            the unit a reader opens and follows nothing to
--   posts               a channel's updates: text, image, video or link
--   post_media          the metadata of an S3 object — a post's attachment or
--                       a channel's icon. Bytes are in the bucket, never here
--   admin_users         the only two identities that exist: the super
--                       administrator and one administrator per channel
--   admin_sessions      an administrator's refresh-token session
--   admin_audit_logs    append-only record of administrative actions
--
-- The shape that follows from §1 of the product brief: a READER HAS NO ROW
-- ANYWHERE. There is no account table, no reader session, no follower, no
-- unread marker and no device token, because a reader is anonymous by design —
-- they open the app, browse channels and download an image without ever
-- identifying themselves. The only credentials the database holds are the ones
-- that publish.
--
-- No BEGIN/COMMIT here on purpose: src/migrate.ts wraps each file in its own
-- transaction and records the checksum, so a migration is all-or-nothing.

-- ── Enums ───────────────────────────────────────────────────────────────

-- A channel is moderated independently of the administrator who runs it:
-- suspending a channel must not require disabling an account, and disabling an
-- account must not silently publish or erase a channel's history.
DO $$ BEGIN
  CREATE TYPE channel_status AS ENUM ('active', 'suspended', 'banned');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- §17's model: exactly two roles.
--
--   super_admin    everything — every channel, every post, every administrator.
--   channel_admin  one channel, and only the one `channel_id` names.
--
-- The type also carries the labels of two roles this product used to have
-- ('admin', 'moderator'). PostgreSQL cannot remove a label from a type, and
-- rebuilding the type to shed two strings would mean rewriting every index that
-- depends on it for no behavioural gain. `admin_users_role_check` below is what
-- actually prevents one being stored, and it is the check the schema shows.
DO $$ BEGIN
  CREATE TYPE admin_role AS ENUM ('super_admin', 'admin', 'moderator', 'channel_admin');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- `disabled` rather than `deleted`: an administrator's access is switched off
-- without erasing who they were, because the audit log names them.
DO $$ BEGIN
  CREATE TYPE admin_status AS ENUM ('active', 'disabled');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- What an audited action did. `denied` is recorded, not just `failed`: a
-- refusal is the interesting signal in a permission system, and a log that only
-- holds successes cannot answer "did anyone try?".
DO $$ BEGIN
  CREATE TYPE audit_outcome AS ENUM ('success', 'denied', 'failed');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- Deliberately separate from post_type: "which post kinds exist" and "which
-- media kinds can be stored" are different questions, and sharing one type
-- would mean an edit to one silently changed the other.
--
-- 'audio' is a label from the earlier product. An audio post was never
-- shippable (the composer has no such shape, `admin/routes.ts` refuses an audio
-- content type, and the Android client refuses it again), so nothing writes it;
-- the CHECK on `posts` is what stops a row claiming it.
DO $$ BEGIN
  CREATE TYPE media_kind AS ENUM ('image', 'video', 'audio');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- `pending` = an upload URL was issued and the object is not yet confirmed.
-- Only `ready` media is ever attached to a published post.
DO $$ BEGIN
  CREATE TYPE media_status AS ENUM ('pending', 'ready');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- §21's four composable shapes — text, image + text, video + text, link — plus
-- two labels from the earlier product.
--
-- The write path derives a post's type from its attachments rather than
-- trusting a client, so this enum is not the guard. `posts_type_is_renderable`
-- below is, and it lists the four: a `poll` or `audio` label can be cast but
-- never stored, which is exactly what it was added for. Labels cannot be
-- dropped from an existing type, and this type is created here only so that a
-- row written before the redesign — a poll with no body — is still readable.
DO $$ BEGIN
  CREATE TYPE post_type AS ENUM ('text', 'image', 'video', 'audio', 'link', 'poll');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── updated_at maintenance ──────────────────────────────────────────────
-- One trigger function for every table with an `updated_at`. The alternative —
-- letting the service write the column — is a column that is correct only while
-- every writer remembers, and a later writer that forgets leaves a timestamp
-- telling the wrong story.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ── channel_categories ──────────────────────────────────────────────────
-- §5 asks for categories to be database-driven: adding one must not require a
-- migration. `slug` is the stable identifier the Android client sends and
-- stores, so a label can be reworded freely.
CREATE TABLE IF NOT EXISTS channel_categories (
  slug        text PRIMARY KEY
                CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
  label       text NOT NULL CHECK (char_length(btrim(label)) BETWEEN 1 AND 60),
  sort_order  integer NOT NULL DEFAULT 100,
  is_active   boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- Idempotent seed, so replaying this file cannot duplicate a category, and
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
-- A channel is created by the super administrator and then run by whoever holds
-- the administrator account bound to it (`admin_users.channel_id`). There is no
-- owner column: ownership was a property of the reader accounts this product no
-- longer has, and two answers to "who runs this channel" is one too many.
CREATE TABLE IF NOT EXISTS channels (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Public, shareable deep-link identifier (§6). Distinct from `name`: channel
  -- names are display text and may repeat, a slug must not — a slug that
  -- collided would make a shared link ambiguous, so it is UNIQUE and it is
  -- never edited after creation (see `updateChannel`).
  slug            text NOT NULL UNIQUE
                    CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$'),

  name            text NOT NULL
                    CHECK (char_length(btrim(name)) BETWEEN 2 AND 120),
  description     text
                    CHECK (description IS NULL
                           OR char_length(description) <= 1000),

  -- S3 object key only, never bytes and never a URL: a stored URL expires, a
  -- key can be signed again. The row that proves the object exists is the
  -- `post_media` row with this channel's id and a NULL `post_id`.
  icon_object_key text
                    CHECK (icon_object_key IS NULL
                           OR char_length(icon_object_key) <= 512),

  -- SET NULL rather than RESTRICT: retiring a category must not block, and the
  -- channel simply becomes uncategorised.
  category_slug   text REFERENCES channel_categories(slug) ON DELETE SET NULL,

  -- ISO-3166-1 alpha-2, upper-case. Text rather than an enum so countries do
  -- not need a migration to add; the API validates against a fixed list.
  country_code    char(2) CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),

  status          channel_status NOT NULL DEFAULT 'active',

  -- The "recent activity" half of Explore's ranking. Nullable because a channel
  -- with no posts has never been active; the list orders on
  -- COALESCE(last_post_at, created_at) so a brand-new channel is not ranked
  -- last forever. Rewritten when a post is deleted or purged, so it cannot
  -- claim activity that no longer exists.
  last_post_at    timestamptz,

  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Explore's default ordering, and the index the keyset pagination walks.
-- Partial: a suspended channel must never be returned by a listing.
CREATE INDEX IF NOT EXISTS channels_activity_idx
  ON channels (COALESCE(last_post_at, created_at) DESC, id DESC)
  WHERE status = 'active';

-- Category browsing (`/api/v1/channels?category=…` and `/api/v1/categories`).
CREATE INDEX IF NOT EXISTS channels_category_idx
  ON channels (category_slug)
  WHERE status = 'active';

-- Search (§5). lower(name) rather than a trigram index: the match is a plain
-- case-insensitive substring, which is honest about what a few thousand
-- channels need, and no extension is required in any environment — including
-- the PGlite the test suite runs on. The migration to trigram is additive.
CREATE INDEX IF NOT EXISTS channels_name_lower_idx ON channels (lower(name));

DROP TRIGGER IF EXISTS channels_set_updated_at ON channels;
CREATE TRIGGER channels_set_updated_at
  BEFORE UPDATE ON channels
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── admin_users ─────────────────────────────────────────────────────────
-- The only identities in the product, and the only table a password reaches.
--
-- Administrators are NOT a flag on a reader account: there is no reader account
-- to flag. This is the whole of the admin model, which is what makes
-- "a channel administrator can only publish to their own channel" a fact about
-- the data rather than a rule every route has to remember.
CREATE TABLE IF NOT EXISTS admin_users (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  display_name         text NOT NULL
                         CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 80),

  email                text NOT NULL,
  -- Lower-cased copy for uniqueness and lookup. The original casing is kept for
  -- display.
  email_normalized     text NOT NULL UNIQUE,

  -- bcrypt, never a plaintext password. No route returns this column, no log
  -- line prints it, and `admin_users` is the one table where a mistake here
  -- would be unrecoverable rather than embarrassing.
  password_hash        text NOT NULL,

  role                 admin_role NOT NULL,
  status               admin_status NOT NULL DEFAULT 'active',

  -- The channel a channel administrator is bound to; NULL for a super
  -- administrator, who is bound to none. CASCADE because an administrator whose
  -- channel is deleted is an administrator of nothing — deleting a channel
  -- deletes the login created to run it, which is what makes §18's "manage
  -- channel administrators" survivable rather than leaving an orphan account.
  channel_id           uuid REFERENCES channels(id) ON DELETE CASCADE,

  -- Who created this administrator. NULL for the first one, which the
  -- deployment provisions from its environment and nobody created. SET NULL so
  -- removing a creator does not cascade to the people they invited.
  created_by_admin_id  uuid REFERENCES admin_users(id) ON DELETE SET NULL,

  -- Per-identity failed-login limiting. Counted in the database rather than in
  -- memory so a restart does not reset an attacker's allowance.
  failed_login_count   smallint NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
  locked_until         timestamptz,

  last_login_at        timestamptz,
  password_changed_at  timestamptz,
  disabled_at          timestamptz,

  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),

  -- Only the two roles may be stored. The enum keeps the labels of the roles
  -- this product used to have, so this constraint — not the type — is what
  -- refuses one.
  CONSTRAINT admin_users_role_check
    CHECK (role IN ('super_admin', 'channel_admin')),

  -- A channel administrator is bound to a channel, stated as an implication:
  -- any non-channel_admin row may leave `channel_id` unset.
  CONSTRAINT admin_users_channel_binding_check
    CHECK (role <> 'channel_admin' OR channel_id IS NOT NULL)
);

-- "Which administrators run this channel?" and the reverse lookup used to
-- authorize an administrative request.
CREATE INDEX IF NOT EXISTS admin_users_channel_idx ON admin_users (channel_id);
CREATE INDEX IF NOT EXISTS admin_users_role_idx ON admin_users (role, status);

-- One channel administrator per channel, so "which administrator runs this
-- channel" has exactly one answer. A super administrator's NULL is excluded by
-- the partial predicate: PostgreSQL treats NULLs as distinct, and a plain
-- UNIQUE would have allowed any number of unbound administrators.
CREATE UNIQUE INDEX IF NOT EXISTS admin_users_one_per_channel_idx
  ON admin_users (channel_id)
  WHERE channel_id IS NOT NULL;

DROP TRIGGER IF EXISTS admin_users_set_updated_at ON admin_users;
CREATE TRIGGER admin_users_set_updated_at
  BEFORE UPDATE ON admin_users
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── admin_sessions ──────────────────────────────────────────────────────
-- A refresh-token session. The access token is a short-lived JWT and is never
-- stored; only the SHA-256 of the refresh token is, so a database dump cannot
-- be replayed against the API. `revoked_at` is how disabling an administrator
-- and signing out everywhere invalidate every device at once.
--
-- The superseded hash is kept, which is what makes REPLAY detectable: a token
-- presented twice means a copy exists, and that is only visible while the hash
-- it rotated away from is still on record (see `refreshAdminSession`).
CREATE TABLE IF NOT EXISTS admin_sessions (
  id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_id                    uuid NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,

  refresh_token_hash          text NOT NULL,
  previous_refresh_token_hash text,

  -- Hashed, never raw: request metadata is kept only where it is justified, and
  -- a hash supports abuse correlation without retaining a readable IP log.
  ip_hash                     text,
  user_agent                  text CHECK (user_agent IS NULL OR char_length(user_agent) <= 400),

  created_at                  timestamptz NOT NULL DEFAULT now(),
  last_used_at                timestamptz,
  expires_at                  timestamptz NOT NULL,
  revoked_at                  timestamptz,
  revoked_reason              text
);

CREATE UNIQUE INDEX IF NOT EXISTS admin_sessions_token_idx
  ON admin_sessions (refresh_token_hash);

-- Session validation on every administrative request, which is what makes
-- revocation and disabling take effect immediately.
CREATE INDEX IF NOT EXISTS admin_sessions_live_idx
  ON admin_sessions (admin_id)
  WHERE revoked_at IS NULL;

-- The replay lookup: "has this token already been rotated away from?".
-- Partial: only a row that has actually rotated is searchable.
CREATE INDEX IF NOT EXISTS admin_sessions_previous_token_idx
  ON admin_sessions (previous_refresh_token_hash)
  WHERE previous_refresh_token_hash IS NOT NULL;

-- ── admin_audit_logs ────────────────────────────────────────────────────
-- Append-only in the DATABASE, not by convention. A rule that lives in the
-- application is a rule the next application does not follow, and this is a
-- table whose entire value is that it cannot be edited.
CREATE TABLE IF NOT EXISTS admin_audit_logs (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Deliberately NOT a foreign key. The obvious shape is a reference with
  -- ON DELETE SET NULL, and that cannot work here: this table is immutable by
  -- trigger, so the UPDATE a SET NULL cascade performs is refused by the
  -- database, which would make deleting a channel with any administrative
  -- history fail. The row's actor is therefore an identifier rather than a live
  -- reference — what it always was in substance.
  --
  -- `admin_email` is a snapshot taken when the action happened, not a fallback
  -- for a missing id: an administrator's email can be changed later, and a log
  -- that renames its actors is a log that rewrites its own history. Together
  -- the two columns keep "who did this" answerable, including after the account
  -- and its channel are gone.
  admin_id      uuid,
  admin_email   text,
  actor_role    admin_role,

  -- Free text rather than an enum on purpose: the set of auditable actions
  -- grows with the product, and an enum would make adding one a migration.
  action        text NOT NULL CHECK (char_length(btrim(action)) BETWEEN 1 AND 80),

  -- What was acted on. `target_id` is text rather than uuid because a target
  -- can be a composite (`<channel>/<media>`) or a non-uuid key (a slug), and a
  -- column that could not hold those would push the detail into `metadata`,
  -- where it cannot be indexed or queried honestly.
  target_type   text CHECK (target_type IS NULL OR char_length(target_type) <= 40),
  target_id     text CHECK (target_id IS NULL OR char_length(target_id) <= 200),

  outcome       audit_outcome NOT NULL DEFAULT 'success',

  -- Extra detail: an old and new value, a reason, a resolution note. NEVER
  -- credentials — passwords, tokens and codes must not reach this column, which
  -- is the one place that rule could be broken by accident.
  metadata      jsonb,

  ip_hash       text,

  created_at    timestamptz NOT NULL DEFAULT now()
);

-- The audit view: newest first, filtered by actor or by target.
CREATE INDEX IF NOT EXISTS admin_audit_logs_created_idx ON admin_audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_logs_admin_idx ON admin_audit_logs (admin_id, created_at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_logs_target_idx
  ON admin_audit_logs (target_type, target_id, created_at DESC);

-- UPDATE and DELETE raise; TRUNCATE is covered too, because an "append-oriented"
-- table that a TRUNCATE can empty is not a log at all.
CREATE OR REPLACE FUNCTION admin_audit_logs_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'admin_audit_logs is append-only (% attempted)', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS admin_audit_logs_no_update ON admin_audit_logs;
CREATE TRIGGER admin_audit_logs_no_update
  BEFORE UPDATE ON admin_audit_logs
  FOR EACH ROW EXECUTE FUNCTION admin_audit_logs_immutable();

DROP TRIGGER IF EXISTS admin_audit_logs_no_delete ON admin_audit_logs;
CREATE TRIGGER admin_audit_logs_no_delete
  BEFORE DELETE ON admin_audit_logs
  FOR EACH ROW EXECUTE FUNCTION admin_audit_logs_immutable();

DROP TRIGGER IF EXISTS admin_audit_logs_no_truncate ON admin_audit_logs;
CREATE TRIGGER admin_audit_logs_no_truncate
  BEFORE TRUNCATE ON admin_audit_logs
  FOR EACH STATEMENT EXECUTE FUNCTION admin_audit_logs_immutable();

-- ── posts ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS posts (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- CASCADE: a post belongs to its channel and has no meaning without it.
  channel_id      uuid NOT NULL REFERENCES channels(id) ON DELETE CASCADE,

  -- The administrator who published it, kept for the audit trail. NULLABLE
  -- because content can outlive its author: deleting an administrator sets this
  -- to NULL rather than deleting what they published for other readers, and a
  -- post whose author is unknown is still a post.
  --
  -- NEVER mapped into a payload: who publishes inside a channel is not part of
  -- that channel's public surface.
  author_id       uuid REFERENCES admin_users(id) ON DELETE SET NULL,

  type            post_type NOT NULL,

  -- The caption, and for a text post the whole content. Named `body` rather
  -- than `text` so the column and the PostgreSQL type do not read alike in a
  -- query. Nullable because a media post needs no caption.
  body            text,

  -- Link posts. The CHECK is a backstop, not the validation: the API rejects a
  -- bad URL with a code the client can word, and this only ensures no other
  -- writer can store something that is not a URL at all.
  link_url        text CHECK (link_url IS NULL OR link_url ~* '^https?://[^[:space:]]+$'),
  link_title      text,

  created_at      timestamptz NOT NULL DEFAULT now(),

  -- The fact of the last edit, not the deadline: the window is configuration
  -- (EDIT_WINDOW_DAYS) and is computed by the API.
  edited_at       timestamptz,

  -- Deletion is SOFT, and the sweep finishes it. A delete disappears from every
  -- read immediately; `retention` then removes the row and its objects for
  -- real, which is why there is no `deleted_reason` here — the question "who
  -- removed what, and why" belongs to `admin_audit_logs`, which is append-only
  -- and outlives the row it describes.
  deleted_at      timestamptz,

  -- A post must carry whatever its own type promises. Media types are NOT
  -- checked here because their content lives in `post_media`, which this
  -- constraint cannot see; the API enforces that a media post has media.
  CONSTRAINT posts_content_matches_type CHECK (
    CASE type
      WHEN 'text' THEN body IS NOT NULL AND btrim(body) <> ''
      WHEN 'link' THEN link_url IS NOT NULL
      ELSE TRUE
    END
  ),

  -- The four shapes §21 asks for, and the reason the enum can keep the labels
  -- of the two it does not: a poll or audio post was invisible to readers and
  -- visible to every writer of an affected query, so the guarantee lives here,
  -- in the database, rather than in a predicate each read has to remember.
  CONSTRAINT posts_type_is_renderable
    CHECK (type IN ('text', 'image', 'video', 'link')),

  -- A generous backstop for MAX_TEXT_LENGTH, which is configurable and enforced
  -- by the API. Duplicating the configured value here would make an operator's
  -- increase fail as a constraint violation instead of just working.
  CONSTRAINT posts_body_length CHECK (body IS NULL OR char_length(body) <= 10000),

  -- A deleted post must not also claim an edit after deletion.
  CONSTRAINT posts_edited_before_delete CHECK (
    edited_at IS NULL OR deleted_at IS NULL OR edited_at <= deleted_at
  )
);

-- A channel's history: the channel screen's only ordering and the index its
-- keyset pagination walks. Partial, because a deleted post is never returned.
CREATE INDEX IF NOT EXISTS posts_channel_created_idx
  ON posts (channel_id, created_at DESC, id DESC)
  WHERE deleted_at IS NULL;

-- The sweep that finishes a delete, which is the only query in the service that
-- scans `posts` by time rather than by channel: "deleted, and deleted long
-- enough ago". Partial on the same predicate the query filters with, so the
-- index holds only the rows still waiting to be purged.
--
-- There is deliberately NO index on posts across every channel by created_at.
-- There used to be one, for a feed aggregated from the channels a reader
-- followed; readers have no accounts and there is no such feed, so it would be
-- write cost on every publish for a query the service cannot make.
CREATE INDEX IF NOT EXISTS posts_purge_idx
  ON posts (deleted_at)
  WHERE deleted_at IS NOT NULL;

-- ── post_media ──────────────────────────────────────────────────────────
-- The metadata of an S3 object. BYTES NEVER LIVE HERE: the bucket holds the
-- asset, this table holds the key plus what is needed to render a placeholder,
-- cap an upload and tell whether the object can be trusted.
--
-- One table for both kinds of asset — a post's attachment and a channel's icon
-- — because the upload lifecycle is identical and it is the part that can be
-- wrong: presign → PUT → confirm (a HEAD, not a client's word) → claim. A
-- second table would duplicate the status machine, the object-key derivation,
-- the size and type validation and the abandoned-upload sweep, and those four
-- would then be able to disagree with their twins. The only fact an icon needs
-- beyond an attachment is that it belongs to a channel, which is the
-- `channel_id` column below.
CREATE TABLE IF NOT EXISTS post_media (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- NULL until claimed by the publish step, then owned by exactly one post.
  -- CASCADE because a media row is meaningless once its post is gone.
  post_id         uuid REFERENCES posts(id) ON DELETE CASCADE,

  -- Set instead of `post_id` when the object is a channel's icon. CASCADE
  -- because an asset owned by a channel that no longer exists is exactly the
  -- row the sweep would otherwise keep forever.
  channel_id      uuid REFERENCES channels(id) ON DELETE CASCADE,

  -- The administrator who requested the upload. A pending row is only claimable
  -- by this account, so a leaked key cannot be attached to someone else's post.
  -- NULLABLE and SET NULL for the same reason `posts.author_id` is: deleting an
  -- administrator does not delete the media readers are looking at.
  owner_id        uuid REFERENCES admin_users(id) ON DELETE SET NULL,

  kind            media_kind NOT NULL,

  -- Derived from `id` in the service, never accepted from a client: a
  -- client-chosen key is how one account writes over, or claims, another's
  -- object. UNIQUE is what makes a double claim detectable.
  object_key      text NOT NULL UNIQUE,

  -- The type the object was signed for. Stored rather than read back from the
  -- bucket because a listing rebuilt from the database alone must know what it
  -- is describing.
  content_type    text NOT NULL,
  byte_size       bigint NOT NULL CHECK (byte_size > 0),

  -- Optional, and only ever a hint for layout. Nothing depends on it, so a
  -- client that cannot read a video's dimensions is not blocked from posting.
  width           integer CHECK (width IS NULL OR width > 0),
  height          integer CHECK (height IS NULL OR height > 0),
  duration_ms     integer CHECK (duration_ms IS NULL OR duration_ms > 0),

  status          media_status NOT NULL DEFAULT 'pending',

  -- How many media rows one post may hold is a product rule (MAX_POST_MEDIA)
  -- enforced by the API; this column only orders them.
  position        integer NOT NULL DEFAULT 0 CHECK (position >= 0),

  created_at      timestamptz NOT NULL DEFAULT now(),
  confirmed_at    timestamptz,

  -- A claimed row must be ready. Publishing cannot attach an object that was
  -- never confirmed, which is the failure that would ship a broken image — and
  -- the same must hold for an icon, so both claims are stated here rather than
  -- left to the caller.
  CONSTRAINT post_media_claimed_is_ready CHECK (
    post_id IS NULL OR status = 'ready'
  ),
  CONSTRAINT post_media_claimed_channel_is_ready CHECK (
    channel_id IS NULL OR status = 'ready'
  )
);

-- Assembly of a page's media: one lookup per post in a page, ordered as the
-- publisher arranged them. A partial unique index rather than a constraint so
-- that many PENDING rows (NULL post_id, position 0) can coexist — NULLs are
-- distinct in a unique index, which is exactly the behaviour needed.
CREATE UNIQUE INDEX IF NOT EXISTS post_media_post_position_idx
  ON post_media (post_id, position)
  WHERE post_id IS NOT NULL;

-- At most one row per channel with an unset `post_id`, which is what "this is
-- the channel's icon" means. Enforced here rather than by the write path,
-- because the write path is the thing that could forget to release the previous
-- one — and a channel with two live icon rows has no defined answer to which
-- object is the icon, including for the code that deletes it.
CREATE UNIQUE INDEX IF NOT EXISTS post_media_channel_icon_idx
  ON post_media (channel_id)
  WHERE channel_id IS NOT NULL AND post_id IS NULL;

-- Abandoned uploads (§34): a URL was issued and the client never confirmed or
-- claimed it. Found by age. Two indexes because "claimed by nobody" and
-- "claimed by no post" are different questions asked by different sweeps.
CREATE INDEX IF NOT EXISTS post_media_unclaimed_idx
  ON post_media (created_at)
  WHERE post_id IS NULL;

CREATE INDEX IF NOT EXISTS post_media_abandoned_idx
  ON post_media (created_at)
  WHERE post_id IS NULL AND channel_id IS NULL;
