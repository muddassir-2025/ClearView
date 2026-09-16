-- 006_engagement.sql
--
-- Reactions (§13), post views (§15) and polls (§14).
--
-- Design notes that matter:
--
--  * **Every table here stores WHO did something, and no query returns it.**
--    §13 requires that a reaction count is visible and the reactor is not, §14
--    the same for voters, §15 for viewers. The user id is stored anyway, and
--    that is deliberate: it is the only way to make "one reaction per person",
--    "one vote per person per option" and "a repeat view is not a new view"
--    enforceable by the database rather than by a convention in the service.
--    The privacy rule therefore lives in the read shape (counts only), not in
--    the write shape — which is what makes it impossible to satisfy §13 while
--    accidentally double-counting a double-tap.
--
--  * **One reaction per user per post, enforced by the PRIMARY KEY.** §13's
--    counts are aggregate, so a user changing their mind is an UPDATE, not a
--    second row. A "reaction_counts" column on `posts` would have to be
--    maintained by every writer and would drift the first time one was
--    forgotten, so the count is computed from this table instead — the same
--    reasoning that put `follower_count` in a counter (hot, sorted on) and
--    reactions in rows (cold, only ever summed).
--
--  * **A view is ONE ROW PER (post, viewer).** §15 asks for dedupe "so
--    repeatedly scrolling over the same post does not generate unlimited
--    duplicate views". `view_count` counts *sessions* of looking rather than
--    individual scrolls: it is incremented only when the previous look is
--    outside VIEW_DEDUPE_WINDOW_MINUTES, so the number still means something
--    while a pull-to-refresh loop cannot inflate it. `unique_viewers` is the
--    count of rows and is the number the channel owner sees as "views".
--
--  * **A poll belongs to exactly one post** (UNIQUE on post_id). §14 models a
--    poll as a post's content, not as a separate object that can be attached
--    to several — one poll per post keeps the feed's rendering rule ("a poll
--    post shows its poll") unambiguous.
--
--  * **Poll options are ordered and immutable.** `position` is what the client
--    renders in order; the service refuses an edit that would reorder or
--    renumber them once a vote exists, because renumbering would silently
--    move votes from one option to another.
--
--  * **`poll_votes` cascades from BOTH the option and the poll.** Deleting a
--    poll has to take its votes with it, and so does deleting one option —
--    a vote naming a removed option is a count with nothing to count.
--
-- No BEGIN/COMMIT here on purpose: src/migrate.ts wraps each file in its own
-- transaction and records the checksum, so a migration is all-or-nothing.

-- ── Enums ───────────────────────────────────────────────────────────────

-- A small, fixed set. Deliberately NOT free text: an emoji column would let
-- two clients spell "like" two ways and split one reaction's count in two, and
-- the aggregate shape (§13) only works if the key space is closed.
DO $$ BEGIN
  CREATE TYPE reaction_kind AS ENUM ('like', 'love', 'laugh', 'wow', 'sad', 'angry');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- §8 lists polls among the post kinds, so `poll` joins `post_type` here.
--
-- Its absence from 004 was not an oversight: a post's type is DERIVED from
-- what it carries (004's comment says so), so the value could not be created
-- until the thing it describes existed.
--
-- Added rather than reused `text`: a poll post's content is its options, and a
-- reviewer asking "how many polls did this channel publish?" gets a wrong
-- answer if the two share a value. The `posts_content_matches_type` CHECK from
-- 004 needs no change — it is a CASE with `ELSE TRUE` for exactly this kind of
-- addition.
--
-- Inside this file's transaction that is safe: Postgres allows ADD VALUE in a
-- transaction since v12 as long as the new value is not USED before the
-- commit, and nothing here inserts a post. It is NOT wrapped in a DO block
-- with an exception handler like the enums below: those guard against a
-- hand-created type, whereas this one is guarded by `IF NOT EXISTS` and a
-- subtransaction would be a worse place to discover an enum quirk.
ALTER TYPE post_type ADD VALUE IF NOT EXISTS 'poll';

-- ── post_reactions ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS post_reactions (
  post_id     uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reaction    reaction_kind NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),

  -- One reaction per person (§13), which is also what makes the aggregate
  -- believable: "120 ❤️" cannot mean 120 taps by one account.
  PRIMARY KEY (post_id, user_id)
);

-- "What are the counts for this page of posts?" — the feed's only read, and
-- the reason the key leads with post_id. (The PRIMARY KEY already covers this
-- prefix, so no separate index is declared; the comment records why.)
--
-- "Which posts did I react to, and how?" — needed so the feed can render the
-- viewer's own reaction highlighted without a second round trip per post.
CREATE INDEX IF NOT EXISTS post_reactions_user_idx ON post_reactions (user_id, post_id);

-- ── post_views ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS post_views (
  post_id         uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  -- A look, not a scroll. Distinct row per viewer, so `count(*)` is
  -- `unique_viewers` — the figure §15's owner-facing analytics reports.
  view_count      integer NOT NULL DEFAULT 1 CHECK (view_count > 0),

  first_viewed_at timestamptz NOT NULL DEFAULT now(),

  -- Drives the dedupe window: the service only increments `view_count` when
  -- this is older than VIEW_DEDUPE_WINDOW_MINUTES.
  last_viewed_at  timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (post_id, user_id)
);

-- §15's per-post analytics timeline: "views on this day". A view is counted
-- against `first_viewed_at`, so a post's view graph can never grow from an
-- older post being re-opened.
CREATE INDEX IF NOT EXISTS post_views_post_first_idx
  ON post_views (post_id, first_viewed_at);

-- Channel-level view totals (§15), newest activity first.
CREATE INDEX IF NOT EXISTS post_views_user_idx ON post_views (user_id);

-- ── polls ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS polls (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- UNIQUE: one poll per post. This is also what makes "attach a poll to an
  -- existing post" a single idempotent statement rather than a duplicate row.
  post_id         uuid NOT NULL UNIQUE REFERENCES posts(id) ON DELETE CASCADE,

  question        text NOT NULL
                    CHECK (char_length(btrim(question)) BETWEEN 1 AND 300),

  -- §14's "single or multiple choice". False (single) is the default because
  -- it is the stricter rule: a mistaken multi-select cannot be detected from
  -- the results, while a mistaken single-select merely rejects a second tap.
  allow_multiple  boolean NOT NULL DEFAULT false,

  -- Nullable: most polls do not close. Stored as an absolute instant rather
  -- than a duration so a closed poll stays closed whatever the operator later
  -- changes POLL_MAX_OPEN_DAYS to.
  closes_at       timestamptz,

  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS polls_closes_idx ON polls (closes_at) WHERE closes_at IS NOT NULL;

-- ── poll_options ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS poll_options (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  poll_id     uuid NOT NULL REFERENCES polls(id) ON DELETE CASCADE,

  -- Display order, and the thing that must not change once votes exist.
  position    integer NOT NULL CHECK (position >= 0),

  label       text NOT NULL CHECK (char_length(btrim(label)) BETWEEN 1 AND 120),

  -- Two options reading the same is almost always a client bug, and it makes
  -- the aggregate result ambiguous to read back. The database refuses it.
  UNIQUE (poll_id, position)
);

CREATE UNIQUE INDEX IF NOT EXISTS poll_options_label_idx
  ON poll_options (poll_id, lower(btrim(label)));

-- ── poll_votes ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS poll_votes (
  poll_id     uuid NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
  option_id   uuid NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),

  -- One row per person per option. A single-choice poll is kept single by the
  -- service deleting the viewer's other rows for the poll in the same
  -- transaction — a partial unique index on (poll_id, user_id) could not
  -- express "unless the poll allows multiple", because the index cannot see
  -- `polls.allow_multiple`.
  PRIMARY KEY (poll_id, option_id, user_id)
);

-- "Who has voted in this poll?" — the lookup that backs both the single-choice
-- rule and the "you have already voted" flag in the payload.
CREATE INDEX IF NOT EXISTS poll_votes_poll_user_idx ON poll_votes (poll_id, user_id);
