-- 012_channels_only.sql
--
-- Good Post becomes a broadcast system with NO users: readers are anonymous and
-- only administrators publish. This migration removes everything that existed
-- to serve an authenticated reader, and re-points the two tables that outlive it
-- at the administrators who now author them.
--
-- Why a migration rather than a fresh schema: `channels` and `posts` are the
-- product, and they hold content that should survive the redesign. Dropping the
-- accounts must not drop the channels they published, so the order below is
-- deliberate — authorship is detached FIRST, while the rows still exist.
--
-- What is removed, and why each one has no place left:
--
--   users, user_sessions,            There is no viewer account and no viewer
--   phone_verifications,             session. Good Post opens with no signup,
--   email_verifications              no login, no code and no password, so the
--                                    identity tables have no writers.
--
--   banned_identities                Bans attached to a mobile identity. With
--                                    no accounts there is no identity to ban.
--
--   channel_followers,               Follow, mute, block and per-user unread are
--   channel_blocks, channel_admins    all per-account state. "Channel admin" is
--                                    now `admin_users.channel_id`, so the
--                                    user-scoped roles table goes too.
--
--   channel_conversations,           Follower↔channel private messaging (§16).
--   channel_messages                 Not part of a broadcast channel.
--
--   polls, poll_options, poll_votes, Reactions, polls and view counts were
--   post_reactions, post_views       engagement. §1 excludes all of it, and an
--                                    engagement table with no endpoint is a
--                                    table that only ever grows.
--
--   reports, user_blocks             User-filed reports and user-to-user blocks.
--
--   notifications, device_tokens     Push to a user's inbox. There are no user
--                                    inboxes, and notification delivery is a
--                                    device-local preference until real push
--                                    infrastructure exists.
--
--   admin_messages,                  Official platform messages INTO a user's
--   admin_message_reads              inbox. The inbox is gone; a channel
--                                    broadcasts through its own posts.
--
-- What survives: channels, channel_categories, posts, post_media, admin_users,
-- admin_sessions and admin_audit_logs. That is the whole product.
--
-- No BEGIN/COMMIT here: src/migrate.ts wraps each file in its own transaction
-- and records the checksum, so a migration is all-or-nothing.

-- ── 1. Authorship moves from users to administrators ─────────────────────
--
-- Detached rather than remapped. There is no honest mapping from a user id to
-- an administrator id, and inventing one — attributing content to whoever
-- happens to hold a similar position — would be a lie the audit trail repeats
-- forever. NULL says "published before the redesign", which is true.
--
-- The columns become NULLABLE for the same reason: a row whose author is
-- unknown is still a post, and refusing to store it would delete the history
-- this migration exists to keep.

ALTER TABLE posts ALTER COLUMN author_id DROP NOT NULL;
ALTER TABLE posts DROP CONSTRAINT IF EXISTS posts_author_id_fkey;
UPDATE posts SET author_id = NULL WHERE author_id IS NOT NULL;

DO $$ BEGIN
  ALTER TABLE posts
    ADD CONSTRAINT posts_author_id_fkey
    FOREIGN KEY (author_id) REFERENCES admin_users(id) ON DELETE SET NULL;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

ALTER TABLE post_media ALTER COLUMN owner_id DROP NOT NULL;
ALTER TABLE post_media DROP CONSTRAINT IF EXISTS post_media_owner_id_fkey;
UPDATE post_media SET owner_id = NULL WHERE owner_id IS NOT NULL;

DO $$ BEGIN
  ALTER TABLE post_media
    ADD CONSTRAINT post_media_owner_id_fkey
    FOREIGN KEY (owner_id) REFERENCES admin_users(id) ON DELETE SET NULL;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── 2. A channel is run by administrators, not owned by a user ───────────
--
-- `admin_users.channel_id` (below) is the binding now. Keeping `owner_id` would
-- leave a second, contradictory answer to "who runs this channel" — and its
-- NOT NULL REFERENCES users made it the one thing that could not survive.
ALTER TABLE channels DROP COLUMN IF EXISTS owner_id;

-- ── 3. The follower concept goes with the accounts ───────────────────────
--
-- `follower_count` had no other reader, and a column holding a number that
-- must never be displayed is a number someone will eventually display.
-- `allow_follower_messages` toggled a feature that no longer exists.
ALTER TABLE channels DROP COLUMN IF EXISTS follower_count CASCADE;
ALTER TABLE channels DROP COLUMN IF EXISTS allow_follower_messages;
ALTER TABLE channels DROP COLUMN IF EXISTS post_count;

-- ── 4. Drop the tables that had no purpose left ───────────────────────────
--
-- Ordered children-first for readability; CASCADE is what actually makes the
-- order irrelevant, because several of these are referenced from each other
-- and from the tables above.
DROP TABLE IF EXISTS
  admin_message_reads,
  admin_messages,
  notifications,
  device_tokens,
  reports,
  user_blocks,
  channel_messages,
  channel_conversations,
  poll_votes,
  poll_options,
  polls,
  post_reactions,
  post_views,
  channel_blocks,
  channel_followers,
  channel_admins,
  banned_identities,
  email_verifications,
  phone_verifications,
  user_sessions,
  users
CASCADE;

-- ── 5. Administrators are email + password, and scoped to a channel ──────
--
-- `phone_hash` was NOT NULL UNIQUE because the old admin surface authenticated
-- with a number as well as an address. §16 asks for email and password only, so
-- the column becomes optional and its uniqueness goes with it — a NULL is not
-- an identity, and a UNIQUE index that tolerates many NULLs would be an
-- invariant that looks enforced and is not.
ALTER TABLE admin_users ALTER COLUMN phone_hash DROP NOT NULL;
ALTER TABLE admin_users DROP CONSTRAINT IF EXISTS admin_users_phone_hash_key;
ALTER TABLE admin_users DROP COLUMN IF EXISTS phone_hash_version;

-- NULL for a super administrator, set for a channel administrator. This is what
-- makes "a channel admin can only publish to their own channel" a fact about
-- the data rather than a check every route has to remember.
ALTER TABLE admin_users
  ADD COLUMN IF NOT EXISTS channel_id uuid REFERENCES channels(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS admin_users_channel_idx ON admin_users (channel_id);

-- 'channel_admin' is ADDED here and deliberately NOT used here: PostgreSQL
-- refuses to use a new enum value inside the transaction that created it, so
-- the constraint that mentions it lives in 013.
DO $$ BEGIN
  ALTER TYPE admin_role ADD VALUE IF NOT EXISTS 'channel_admin';
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
