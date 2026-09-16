-- 004_posts.sql
--
-- Posts, their media, and the retention window (§8, §9, §10, §11).
--
-- Design notes that matter:
--
--  * Media BYTES never live here (§9). `post_media` holds an S3 object key plus
--    the metadata needed to render a placeholder, to cap an upload, and to tell
--    whether the object can be trusted. Neon stores a few dozen bytes per asset;
--    the bucket stores the asset.
--
--  * An object key is DERIVED from the media row's own uuid, never accepted from
--    a client. A client-chosen key is how one account ends up writing over, or
--    claiming, another account's object — and there is no upside to letting a
--    client pick a path it cannot see.
--
--  * `post_media.post_id` is NULLABLE on purpose. The flow is presign → upload →
--    publish: the row is created when the upload URL is issued and the publish
--    step CLAIMS it. A row still holding `post_id IS NULL` after the confirm
--    window is an abandoned upload, which is what the sweep prunes (§34).
--
--  * Posts are SOFT-deleted. §25 needs an admin to remove a post and §18 models
--    moderation as reversible, so a hard delete would make restoration
--    impossible; M4's reactions and views would also be left pointing at a
--    missing row.
--
--  * The retention window (§11) is NOT stored per row. It is
--    `created_at < now() - GOODPOST_HISTORY_DAYS`, computed by the sweep, so
--    changing the window stays a configuration change rather than a data
--    migration. `posts_retention_idx` supports exactly that scan.
--
--  * No polls table. Polls are §14 and M4's feature; creating their tables now
--    would leave unexercised schema in the database, the same reasoning that
--    kept `channel_notifications` out of M2. §8's "links" are likewise columns
--    on `posts` rather than a 1:1 table, because a link IS the post's content
--    and a join for three columns would earn nothing.
--
-- No BEGIN/COMMIT here on purpose: src/migrate.ts wraps each file in its own
-- transaction and records the checksum, so a migration is all-or-nothing.

-- ── Enums ───────────────────────────────────────────────────────────────

-- The five post kinds in §8. `link` is a text post that carries a URL, kept
-- distinct so M4's view/engagement analytics can tell the two apart.
DO $$ BEGIN
  CREATE TYPE post_type AS ENUM ('text', 'image', 'video', 'audio', 'link');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- Deliberately separate from post_type: a typed value saying which post kinds
-- exist and a typed value saying which media kinds can be stored are different
-- questions, and linking them would mean an edit to one silently changed the
-- other.
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

-- ── posts ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS posts (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- CASCADE: posts belong to the channel and have no meaning without it. The
  -- channel itself is never hard-deleted while it owns history (channels.id is
  -- RESTRICTed from users in 003), so this only fires on a deliberate purge.
  channel_id      uuid NOT NULL REFERENCES channels(id) ON DELETE CASCADE,

  -- The channel admin who published it. Read for §7 authorization and kept for
  -- the §29 audit trail. NEVER mapped into a payload: who posts inside a
  -- channel is not part of its public surface, exactly as follower identity is
  -- not (§12, §38).
  --
  -- RESTRICT rather than CASCADE: deleting an account must not silently erase
  -- the posts it made for other people's channels (§37 requires an explicit
  -- policy for that, not a side effect).
  author_id       uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

  type            post_type NOT NULL,

  -- The caption, and for a text post the whole content. Named `body` rather
  -- than `text` so the column and the Postgres type do not read alike in a
  -- query. Nullable because a media post needs no caption.
  body            text,

  -- §8's link posts. The CHECK is a backstop, not the validation: the API
  -- rejects a bad URL with a code the client can word, and this only ensures no
  -- other writer can store one that is not a URL at all.
  link_url        text CHECK (link_url IS NULL OR link_url ~* '^https?://[^[:space:]]+$'),
  link_title      text,

  created_at      timestamptz NOT NULL DEFAULT now(),

  -- §7 edit window (EDIT_WINDOW_DAYS). Kept as the fact of the last edit, not
  -- as the deadline: the deadline is configuration and is computed by the API.
  edited_at       timestamptz,

  -- Soft delete, with the reason a moderator saw fit to record. Attribution for
  -- the deletion belongs to the §29 audit log, which arrives with the admin
  -- system in M6 — a `deleted_by` column here would have to point at either
  -- users or admin_users and could not honestly point at both.
  deleted_at      timestamptz,
  deleted_reason  text,

  -- A post must carry whatever its own type promises. Media types are NOT
  -- checked here because their content lives in post_media, which this
  -- constraint cannot see; the API enforces that a media post has media.
  CONSTRAINT posts_content_matches_type CHECK (
    CASE type
      WHEN 'text' THEN body IS NOT NULL AND btrim(body) <> ''
      WHEN 'link' THEN link_url IS NOT NULL
      ELSE TRUE
    END
  ),

  -- A generous backstop for MAX_TEXT_LENGTH, which is configurable and enforced
  -- by the API. Duplicating the configured value here would make an operator's
  -- increase fail as a constraint violation instead of just working.
  CONSTRAINT posts_body_length CHECK (body IS NULL OR char_length(body) <= 10000),

  -- A deleted post must not also claim an edit after deletion.
  CONSTRAINT posts_edited_before_delete CHECK (
    edited_at IS NULL OR deleted_at IS NULL OR edited_at <= deleted_at
  )
);

-- ── post_media ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS post_media (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- NULL until claimed by the publish step, then owned by exactly one post.
  -- CASCADE because a media row is meaningless once its post is gone.
  post_id         uuid REFERENCES posts(id) ON DELETE CASCADE,

  -- The account that requested the upload. A pending row is only claimable by
  -- this account, so a leaked key cannot be attached to someone else's post.
  owner_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  kind            media_kind NOT NULL,

  -- Derived from `id` in the service, so this is a record of the key rather
  -- than an input. UNIQUE is what makes a double claim detectable.
  object_key      text NOT NULL UNIQUE,

  -- The type the object was signed for. Durable because a bucket's own metadata
  -- is not available when a listing is rebuilt from the database alone (the
  -- media a user downloaded is served from their device, §10).
  content_type    text NOT NULL,
  byte_size       bigint NOT NULL CHECK (byte_size > 0),

  -- Optional, and only ever a hint for layout. Nothing depends on it, so a
  -- client that cannot read a video's dimensions is not blocked from posting.
  width           integer CHECK (width IS NULL OR width > 0),
  height          integer CHECK (height IS NULL OR height > 0),
  duration_ms     integer CHECK (duration_ms IS NULL OR duration_ms > 0),

  status          media_status NOT NULL DEFAULT 'pending',

  -- How many media rows this post may hold is a product rule (MAX_POST_MEDIA)
  -- enforced by the API; the column below only orders them.
  position        integer NOT NULL DEFAULT 0 CHECK (position >= 0),

  created_at      timestamptz NOT NULL DEFAULT now(),
  confirmed_at    timestamptz,

  -- A claimed row must be ready: publishing cannot attach an object that was
  -- never confirmed, which is the failure that would ship a broken image.
  CONSTRAINT post_media_claimed_is_ready CHECK (
    post_id IS NULL OR status = 'ready'
  )
);

-- ── Indexes ─────────────────────────────────────────────────────────────

-- The §11 sweep: "every post older than the window", on a schedule.
CREATE INDEX IF NOT EXISTS posts_retention_idx
  ON posts (created_at)
  WHERE deleted_at IS NULL;

-- Channel history (§8), newest first — the channel screen's only ordering.
CREATE INDEX IF NOT EXISTS posts_channel_created_idx
  ON posts (channel_id, created_at DESC, id DESC)
  WHERE deleted_at IS NULL;

-- The aggregated feed (§4) reads across every channel the viewer follows and
-- orders by time alone, so it needs a time index that does not lead with
-- channel_id. Partial, because a deleted post is never in a feed.
CREATE INDEX IF NOT EXISTS posts_feed_idx
  ON posts (created_at DESC, id DESC)
  WHERE deleted_at IS NULL;

-- Assembly of a page's media: one lookup per post in a page, ordered as the
-- publisher arranged them. A partial unique index rather than a constraint so
-- that many PENDING rows (post_id IS NULL, position 0) can coexist — NULLs are
-- distinct in a unique index, which is exactly the behaviour needed here.
CREATE UNIQUE INDEX IF NOT EXISTS post_media_post_position_idx
  ON post_media (post_id, position)
  WHERE post_id IS NOT NULL;

-- Abandoned uploads: rows that were never claimed, found by age (§34).
CREATE INDEX IF NOT EXISTS post_media_unclaimed_idx
  ON post_media (created_at)
  WHERE post_id IS NULL;
