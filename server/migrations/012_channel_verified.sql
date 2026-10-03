-- 012_channel_verified.sql
--
-- A channel the platform itself runs, marked as such on discovery rows.
--
-- A stored flag rather than a derived one, and deliberately not a follower
-- threshold: a badge that can be earned by being followed enough is a popularity
-- number wearing a verification tick, which is the opposite of what a tick means.
-- Only a super administrator can set this — see `updateChannel` and the admin
-- route's permission — so the badge always means "the platform vouches for this".
--
-- Defaults to false, so every existing channel is unverified and nothing changes
-- until an operator says otherwise.

ALTER TABLE channels
  ADD COLUMN IF NOT EXISTS verified boolean NOT NULL DEFAULT false;

-- The discovery rows that show a badge are read with the public list, which
-- already filters on `status`; this is not worth an index on its own, but the
-- catalogue is small enough that a partial index would be dead weight. Nothing to
-- add here.