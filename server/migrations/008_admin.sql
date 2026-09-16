-- 008_admin.sql
--
-- The platform administration system (§20–§30).
--
-- Design notes that matter:
--
--  * **Administrators are a SEPARATE identity from Good Post users.** Their own
--    table, their own roles, their own sessions. §48 is explicit that a channel
--    owner is not an administrator and that no user registration flow may
--    create one; a shared `users` table with an `is_admin` flag is exactly the
--    shape where a bug in one write path grants the other's powers.
--
--  * **An administrator's mobile number is hashed, like everyone else's.** §21
--    requires sign-in by email OR mobile, which needs a lookup — and a lookup
--    works on `phone_hash` just as well as on a stored number. Keeping the raw
--    value out of the database means a dump of this table is not a list of
--    staff phone numbers, and the same HMAC pepper as `users` is used so one
--    hashing rule exists rather than two.
--
--  * **The audit log is append-only in the DATABASE, not by convention.** A
--    trigger rejects UPDATE and DELETE outright. §29 asks for a log
--    "protected from ordinary admin modification"; a rule that lives in the
--    application is a rule that the next application does not follow, and this
--    is a table whose whole value is that it cannot be edited.
--
--  * **`admin_audit_logs.admin_id` is ON DELETE SET NULL, and the actor's email
--    is snapshotted.** Removing an administrator must not remove what they did,
--    and a later rename must not rewrite history. Attribution is preserved by
--    the snapshot rather than by a foreign key that could change.
--
--  * **Official messages are readable state per person.** §26 sends a message
--    to a user or to a channel; a channel has several admins, so "has this been
--    read" is per reader. A single `read_at` column could not answer it.
--
--  * **The two foreign keys deferred from earlier migrations land here.** 007
--    noted that `reports.resolved_by_admin_id` could not reference a table that
--    did not exist yet, and 001 left `banned_identities.banned_by_admin_id`
--    unattached for the same reason. Both are attached below, now that the
--    target exists — which is why they are late rather than absent.
--
-- No BEGIN/COMMIT here on purpose: src/migrate.ts wraps each file in its own
-- transaction and records the checksum, so a migration is all-or-nothing.

-- ── Enums ───────────────────────────────────────────────────────────────

-- §28's three roles. Named with an `admin_` prefix in the type to keep them
-- visibly distinct from `channel_role`, which governs one channel and confers
-- no platform power at all.
DO $$ BEGIN
  CREATE TYPE admin_role AS ENUM ('super_admin', 'admin', 'moderator');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- `disabled` rather than `deleted`: §27 wants an administrator's access turned
-- off without erasing who they were, because the audit log refers to them.
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

-- ── admin_users ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS admin_users (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  display_name         text NOT NULL
                         CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 80),

  email                text NOT NULL,
  -- Lower-cased copy for uniqueness and lookup, the same convention `users`
  -- uses. The original casing is kept for display.
  email_normalized     text NOT NULL UNIQUE,

  -- See the header note. HMAC of the E.164 number, never the number.
  phone_hash           text NOT NULL UNIQUE,
  phone_hash_version   smallint NOT NULL DEFAULT 1,

  -- bcrypt. Never a plaintext password, and §30 forbids logging this column.
  password_hash        text NOT NULL,

  role                 admin_role NOT NULL,
  status               admin_status NOT NULL DEFAULT 'active',

  -- Who created this administrator (§27). NULL for the bootstrap account,
  -- which nobody created. SET NULL so removing a creator does not cascade to
  -- the people they invited.
  created_by_admin_id  uuid REFERENCES admin_users(id) ON DELETE SET NULL,

  -- §30's per-identity failed-login limiting. Counted here rather than in
  -- memory so a restart does not reset an attacker's allowance — the same
  -- reasoning that put OTP attempts in the database.
  failed_login_count   smallint NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
  locked_until         timestamptz,

  last_login_at        timestamptz,
  password_changed_at  timestamptz,
  disabled_at          timestamptz,

  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS admin_users_role_idx ON admin_users (role, status);

-- ── admin_sessions ──────────────────────────────────────────────────────
-- Deliberately a separate table from `user_sessions`: a session minted for the
-- user surface must not be usable on the admin surface, and two tables make
-- that a fact about the data rather than a check someone has to remember.
CREATE TABLE IF NOT EXISTS admin_sessions (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_id            uuid NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,

  -- SHA-256 of the refresh token. The access token is a short-lived JWT and is
  -- never stored, exactly as on the user side.
  refresh_token_hash  text NOT NULL,

  -- The hash a rotation moved away from (migration 002 does the same for user
  -- sessions). Keeping it is what makes REPLAY detectable: a token presented
  -- twice means a copy exists, and that is only visible if the superseded hash
  -- is still on record. Without it, "rotate in place" silently turns every
  -- reuse into an ordinary invalid token.
  previous_refresh_token_hash text,

  ip_hash             text,
  user_agent          text CHECK (user_agent IS NULL OR char_length(user_agent) <= 400),

  created_at          timestamptz NOT NULL DEFAULT now(),
  last_used_at        timestamptz,
  expires_at          timestamptz NOT NULL,
  revoked_at          timestamptz,
  revoked_reason      text
);

-- Session validation on every admin request, which is what makes revocation
-- and disabling an administrator take effect immediately (§30).
CREATE INDEX IF NOT EXISTS admin_sessions_live_idx
  ON admin_sessions (admin_id)
  WHERE revoked_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS admin_sessions_token_idx
  ON admin_sessions (refresh_token_hash);

-- The replay lookup: "has this token already been rotated away from?".
CREATE INDEX IF NOT EXISTS admin_sessions_previous_token_idx
  ON admin_sessions (previous_refresh_token_hash)
  WHERE previous_refresh_token_hash IS NOT NULL;

-- ── admin_audit_logs ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS admin_audit_logs (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- NO ACTION, deliberately, and it is a consequence of the append-only
  -- trigger below rather than a stylistic choice: `ON DELETE SET NULL` would
  -- require an UPDATE of this table when an administrator is deleted, and the
  -- trigger refuses every UPDATE. Deletion is therefore refused outright, which
  -- is the honest outcome — §27 disables an administrator, it never deletes
  -- one, and a log that silently lost its actor would be worse than an error.
  --
  -- `admin_email` is the snapshot that keeps a row readable; it is not a
  -- fallback for a missing id but a record of the address at the time, since
  -- an administrator's email can be changed later.
  admin_id      uuid REFERENCES admin_users(id),
  admin_email   text,
  actor_role    admin_role,

  -- Free text rather than an enum on purpose: §29 lists a dozen actions and
  -- explicitly says "examples". An enum would make adding one a migration, and
  -- the set of auditable things grows with the product.
  action        text NOT NULL CHECK (char_length(btrim(action)) BETWEEN 1 AND 80),

  -- What was acted on. `target_id` is text rather than uuid because a target
  -- can be a composite (`<user>/<session>`) or a non-uuid key (an email, a
  -- phone hash), and a column that cannot hold those would push the detail
  -- into `metadata`, where it cannot be indexed or queried honestly.
  target_type   text CHECK (target_type IS NULL OR char_length(target_type) <= 40),
  target_id     text CHECK (target_id IS NULL OR char_length(target_id) <= 200),

  outcome       audit_outcome NOT NULL DEFAULT 'success',

  -- Extra detail — old and new status, a reason, a resolution note. NEVER
  -- credentials: §30 forbids logging passwords, tokens and OTP codes, and this
  -- column is the one place that rule could be broken by accident.
  metadata      jsonb,

  -- §29: request information only where appropriate and justified, and hashed
  -- so the log itself is not a record of who was where.
  ip_hash       text,

  created_at    timestamptz NOT NULL DEFAULT now()
);

-- The audit view (§22, §29): newest first, filtered by actor or target.
CREATE INDEX IF NOT EXISTS admin_audit_logs_created_idx ON admin_audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_logs_admin_idx ON admin_audit_logs (admin_id, created_at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_logs_target_idx
  ON admin_audit_logs (target_type, target_id, created_at DESC);

-- Append-only, enforced by the database. UPDATE and DELETE raise; TRUNCATE is
-- covered too, because "append-oriented" that a `TRUNCATE` can empty is not a
-- log at all.
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

-- ── admin_messages (§26) ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS admin_messages (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- SET NULL: a departing administrator's messages stay, attributed by the
  -- audit row that recorded sending them.
  admin_id           uuid REFERENCES admin_users(id) ON DELETE SET NULL,

  target_user_id     uuid REFERENCES users(id) ON DELETE CASCADE,
  target_channel_id  uuid REFERENCES channels(id) ON DELETE CASCADE,

  subject            text NOT NULL CHECK (char_length(btrim(subject)) BETWEEN 1 AND 200),
  body               text NOT NULL CHECK (char_length(btrim(body)) BETWEEN 1 AND 8000),

  created_at         timestamptz NOT NULL DEFAULT now(),

  -- §26's "a specific user, a specific channel owner, or a channel" — the
  -- channel case is what the reads table below exists for, since a channel has
  -- more than one admin and each must be able to have read it.
  CONSTRAINT admin_messages_one_target CHECK (
    (target_user_id IS NOT NULL AND target_channel_id IS NULL)
    OR (target_user_id IS NULL AND target_channel_id IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS admin_messages_user_idx
  ON admin_messages (target_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS admin_messages_channel_idx
  ON admin_messages (target_channel_id, created_at DESC);

CREATE TABLE IF NOT EXISTS admin_message_reads (
  admin_message_id  uuid NOT NULL REFERENCES admin_messages(id) ON DELETE CASCADE,
  user_id           uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  read_at           timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (admin_message_id, user_id)
);

-- ── Deferred foreign keys from 001 and 007 ──────────────────────────────
-- Both columns were created before the table they point at existed. Now that
-- it does, the references are attached: a banned identity records WHICH
-- administrator banned it, and a resolved report records who resolved it,
-- without either being able to become a dangling id.

DO $$ BEGIN
  ALTER TABLE banned_identities
    ADD CONSTRAINT banned_identities_admin_fk
    FOREIGN KEY (banned_by_admin_id) REFERENCES admin_users(id) ON DELETE SET NULL;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
  ALTER TABLE reports
    ADD CONSTRAINT reports_resolved_by_admin_fk
    FOREIGN KEY (resolved_by_admin_id) REFERENCES admin_users(id) ON DELETE SET NULL;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── updated_at maintenance ──────────────────────────────────────────────
-- Reuses set_updated_at() from 001.
DROP TRIGGER IF EXISTS admin_users_set_updated_at ON admin_users;
CREATE TRIGGER admin_users_set_updated_at
  BEFORE UPDATE ON admin_users
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
