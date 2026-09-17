-- 003_creator_accounts.sql
--
-- Creators who sign themselves up (§16): an administrator account that owns no
-- channel yet, and that has no password at all.
--
-- WHY THIS FILE EXISTS
-- --------------------
-- 001 and 002 assumed every administrator was either provisioned from the
-- environment (the super admin) or minted by a super admin together with the
-- channel it runs. §16 replaces that with self-service: anybody can sign in with
-- Google or an email/password Firebase account, and the next thing they do is
-- create the channel they will run.
--
-- Two columns were in the way, and both changes are statements about identities
-- rather than permission hacks.
--
--   * **`password_hash` had to become nullable.** A creator who signs in with
--     Google has no password, and storing a random one would be a credential
--     nobody knows sitting in the one column where a mistake is unrecoverable.
--     NULL says what is true: this account authenticates with a Firebase
--     identity and cannot sign in with a password. The login path already
--     compares against a dummy hash when there is no hash, so it refuses such an
--     account without any change in behaviour — and `password_matches` returning
--     false is the correct answer, not an error.
--
--     The COMMENT is part of the change: a NULL here is deliberate and readable,
--     and somebody seeing it without this note would reasonably call it a bug.
--
--   * **`firebase_uid` is what a creator is found by.** The email is UNIQUE
--     already, and matching on it would work until somebody changes their Google
--     address — at which point they would silently become a second account with
--     no channel, and their channel would look abandoned. The uid is stable for
--     the life of the identity, so it is the key. It is nullable because the
--     accounts that predate this migration, and the super administrator created
--     from the environment, have no Firebase identity at all.
--
-- The UNIQUE index is PARTIAL — `WHERE firebase_uid IS NOT NULL` — because a
-- plain unique index would allow only one NULL in some engines and, more to the
-- point, would be indexing a column that is NULL for most rows here.
--
-- Nothing is granted by any of this. A creator account reaches exactly the
-- routes it reached before: it owns one channel, and the scope checks that
-- already exist keep it out of every other.

ALTER TABLE admin_users ALTER COLUMN password_hash DROP NOT NULL;

COMMENT ON COLUMN admin_users.password_hash IS
  'bcrypt. NULL for a creator who signs in with Firebase (§16) — such an account '
  'has no password and cannot sign in with one; the login path compares against a '
  'dummy hash and refuses. Never NULL for an account that can use the password form.';

ALTER TABLE admin_users ADD COLUMN IF NOT EXISTS firebase_uid text;

COMMENT ON COLUMN admin_users.firebase_uid IS
  'The Firebase uid of the identity that signed this account up (§16). NULL for '
  'the super administrator (provisioned from the environment) and for accounts '
  'minted by a super admin.';

-- Partial: the super admin and every super-admin-minted account leave this NULL,
-- and only identities that exist need to be unique.
CREATE UNIQUE INDEX IF NOT EXISTS admin_users_firebase_uid_key
  ON admin_users (firebase_uid)
  WHERE firebase_uid IS NOT NULL;
