-- 010_email_accounts.sql
--
-- Accounts may now be created from an email address.
--
-- Why this exists, in one line: the product decision is that for now Good Post
-- is entered with an email address, so the registration flow has to be able to
-- finish with one. §19's mobile-number ban enforcement is untouched and still
-- the stronger identity when it is present — this migration only makes the
-- number OPTIONAL rather than absent.
--
-- What changes, and what deliberately does not:
--
--  * `users.phone_hash` loses NOT NULL. It keeps its unique index, and Postgres
--    treats NULLs as distinct there, so many phone-less accounts coexist while
--    a number can still only ever belong to one account. A phone-less account
--    is therefore invisible to `banned_identities.phone_hash` — which is the
--    honest consequence of signing up without a number, not a bypass that this
--    schema pretends to close. The ban path for an address is the account
--    status, which every request already checks.
--
--  * `email_verifications.purpose` gains 'register'. The CHECK exists so a
--    purpose cannot be invented by a caller; a registration challenge has to be
--    distinguishable from a sign-in one because only it may create an account,
--    and because it must never be usable to sign in.
--
--  * `users.phone_hash_version` keeps its NOT NULL and its default. It describes
--    the pepper used for a hash, and with no hash there is nothing for a version
--    to describe — but a row that will not be read for that column is not worth
--    a second ALTER, and the column is read only alongside phone_hash.
--
-- No BEGIN/COMMIT: src/migrate.ts wraps each file in its own transaction and
-- records the checksum, so a migration is all-or-nothing.

-- The purpose CHECK is dropped by name. It was declared inline in 005, so this
-- is the name Postgres generated for it, and IF EXISTS keeps the file runnable
-- against a database where a future migration renames it.
ALTER TABLE email_verifications
  DROP CONSTRAINT IF EXISTS email_verifications_purpose_check;

ALTER TABLE email_verifications
  ADD CONSTRAINT email_verifications_purpose_check
  CHECK (purpose IN ('signin', 'recover', 'register'));

ALTER TABLE users
  ALTER COLUMN phone_hash DROP NOT NULL;

-- Every read of an account by address already uses this index; the comment is
-- here because a phone-less account makes it the ONLY uniqueness guard that
-- matters for a registration, and it is a partial index (deleted rows are
-- excluded) so a deleted address can be registered again.
--   users_email_active_uniq ON users (email_normalized) WHERE deleted_at IS NULL
