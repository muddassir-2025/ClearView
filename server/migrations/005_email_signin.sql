-- 005_email_signin.sql
--
-- Email verification codes, so Good Post can be entered with an email address
-- as well as a mobile number.
--
-- Design notes that matter:
--
--  * This is a SIGN-IN method only. It does not create accounts. §19 anchors
--    the abuse identity on the mobile number, and every registered account
--    still has to prove one through Firebase — so a bounced user cannot come
--    back with a fresh email address, and `banned_identities.phone_hash` stays
--    the one signal that survives a rename, a reinstall and a data wipe.
--
--  * The CODE is never stored. Only an HMAC of it, under the server's pepper,
--    with a purpose-scoped context string. A leaked database therefore does not
--    hand an attacker a set of live sign-in codes, any more than
--    `user_sessions` hands over refresh tokens.
--
--  * `email_normalized` IS stored in the clear, unlike `phone_verifications`,
--    which keeps only a hash. That asymmetry is deliberate: a phone number is
--    only ever compared, whereas an address has to be DELIVERED to, and
--    `users.email_normalized` already holds the same value. Hashing it here
--    would protect nothing while making the row impossible to write mail from.
--
--  * A separate table rather than a `channel` column on `phone_verifications`:
--    the two carry different identifiers under different uniqueness rules, and
--    a half-null shared table is how a query later reads a phone row while
--    believing it is looking at an email row.
--
--  * Purposes are 'signin' and 'recover'. There is no 'register' — regstration
--    is mobile-only by design, and leaving the value out of the CHECK makes
--    that impossible to do by accident rather than merely discouraged.
--
-- No BEGIN/COMMIT here on purpose: src/migrate.ts wraps each file in its own
-- transaction and records the checksum, so a migration is all-or-nothing.

CREATE TABLE IF NOT EXISTS email_verifications (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Lowercased and trimmed by normalizeEmail() before it reaches this table,
  -- so one address cannot hold several challenge histories by changing case.
  email_normalized  text NOT NULL,

  purpose           text NOT NULL CHECK (purpose IN ('signin', 'recover')),

  -- HMAC-SHA256 hex of the delivered code. There is no plaintext column and
  -- there must never be one.
  code_hash         text NOT NULL,

  -- Mirrors phone_verifications: `send_count` drives the hourly allowance,
  -- `attempts` counts wrong codes against a single challenge.
  send_count        smallint NOT NULL DEFAULT 1,
  attempts          smallint NOT NULL DEFAULT 0,
  max_attempts      smallint NOT NULL DEFAULT 5,

  created_at        timestamptz NOT NULL DEFAULT now(),
  expires_at        timestamptz NOT NULL,
  verified_at       timestamptz,
  consumed_at       timestamptz,

  -- A challenge that expires before it was issued is a bug in the caller, not
  -- a state worth representing.
  CONSTRAINT email_verifications_window CHECK (expires_at > created_at),
  CONSTRAINT email_verifications_counts CHECK (attempts >= 0 AND send_count > 0)
);

-- Serves both hot reads: the hourly rate-limit sum, and the lookup of the
-- newest live challenge. `created_at DESC` means the first row is the one to
-- use, so no sort is needed either way.
CREATE INDEX IF NOT EXISTS email_verifications_email_idx
  ON email_verifications (email_normalized, created_at DESC);
