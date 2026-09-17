-- 004_post_views.sql
--
-- How many times a post has been read (§9).
--
-- WHY THIS FILE EXISTS
-- --------------------
-- The channel card on the reader's home tab and a channel's information page
-- both show numbers now: the card carries the view count of the channel's newest
-- update, and the information page carries how many readers follow it. The
-- follower number needs no column — it is a COUNT over `channel_follows`, which
-- is the truth and cannot drift from it — but a view count has nothing to count.
-- Nothing records a read: a post is served to an anonymous reader with no
-- session and no row anywhere, which is what makes the reader surface as cheap
-- and as private as it is.
--
-- So this adds one integer to `posts`, and NOT a `post_views` table keyed by
-- reader. A table would need a reader id per view, which means storing who read
-- what — the opposite of a product whose readers have no accounts — and it would
-- grow with readers × posts rather than with posts. A counter answers the only
-- question the UI asks.
--
-- WHO INCREMENTS IT
-- -----------------
-- The CLIENT reports the posts it displayed, in one batched request per screen
-- (`POST /api/v1/channels/:id/posts/views`), and the server increments those rows
-- in a single statement scoped to that channel. Server-side counting of reads
-- was the alternative and was rejected: a feed read is paged, refreshed and
-- prefetched, so counting requests would count a reader's scrolling rather than
-- a reader, and every refresh would inflate every post on screen.
--
-- The consequence is stated rather than hidden: **the number is approximate.**
-- A reader who never reports (offline, or a client that predates this) is not
-- counted, and a client that lies is believed. It is a view count in the sense
-- every messaging product means it — a soft signal of reach — and not an
-- analytic. Nothing in the product is permitted to depend on it for access,
-- ranking or authorisation.
--
-- No BEGIN/COMMIT here: src/migrate.ts wraps each file in its own transaction.

-- The default and the NOT NULL are the point: every post that exists has a
-- count, so no read has to handle an absent one, and the counter never has to
-- be initialised by whoever touches the row first.
ALTER TABLE posts ADD COLUMN IF NOT EXISTS view_count integer NOT NULL DEFAULT 0;

-- A negative count can only come from a bug or a direct write, and both are
-- better refused at the database than rendered as "-3 views". Named after the
-- same convention as the other column constraints here.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'posts_view_count_non_negative'
  ) THEN
    ALTER TABLE posts
      ADD CONSTRAINT posts_view_count_non_negative CHECK (view_count >= 0);
  END IF;
END
$$;

COMMENT ON COLUMN posts.view_count IS
  'Approximate reads, incremented by the client''s batched view report. A soft reach signal; never used for access or ordering.';
