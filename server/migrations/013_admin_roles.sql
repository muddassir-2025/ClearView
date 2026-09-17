-- 013_admin_roles.sql
--
-- The administrator model becomes exactly two roles:
--
--   super_admin    — everything: every channel, every post, every administrator.
--   channel_admin  — one channel, and only that channel.
--
-- A separate file from 012 for one mechanical reason: PostgreSQL will not let a
-- transaction USE an enum value it added in the same transaction, and the
-- constraint below names 'channel_admin'. Splitting it is the honest fix rather
-- than rephrasing the constraint into a text comparison that would hide the
-- rule from anyone reading the schema.
--
-- No BEGIN/COMMIT here: src/migrate.ts wraps each file in its own transaction.

-- ── 1. Retire the roles that no longer exist ─────────────────────────────
--
-- ADMIN and MODERATOR were the previous model's middle ranks. They are DISABLED
-- rather than re-roled: promoting a legacy moderator to super_admin would grant
-- a permission nobody ever granted them, and demoting to channel_admin is
-- impossible because they are bound to no channel. Access is switched off and
-- the account says who it was, which is what `disabled` already means here.
--
-- The audit log refers to these administrators by id, so the rows must stay.
UPDATE admin_users
   SET status = 'disabled',
       disabled_at = COALESCE(disabled_at, now())
 WHERE role NOT IN ('super_admin', 'channel_admin');

-- ── 2. Only the two roles may be stored ─────────────────────────────────
--
-- The enum keeps its legacy labels — PostgreSQL cannot remove an enum value —
-- so this CHECK is what actually stops one being written. The service validates
-- too; the constraint is what makes it true whatever the service does.
DO $$ BEGIN
  ALTER TABLE admin_users
    ADD CONSTRAINT admin_users_role_check
    CHECK (role IN ('super_admin', 'channel_admin'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── 3. A channel administrator is bound to a channel ────────────────────
--
-- Phrased as an implication rather than a pairing: `role <> 'channel_admin' OR
-- channel_id IS NOT NULL`. A legacy disabled row holding 'admin' therefore
-- still satisfies it, which is what lets this constraint exist at all while
-- those rows do.
DO $$ BEGIN
  ALTER TABLE admin_users
    ADD CONSTRAINT admin_users_channel_binding_check
    CHECK (role <> 'channel_admin' OR channel_id IS NOT NULL);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── 4. One channel administrator per channel ────────────────────────────
--
-- A channel has exactly one login, so "which administrator runs this channel"
-- has one answer. A super administrator's NULL is excluded by the partial
-- predicate, because PostgreSQL treats NULLs as distinct and a plain UNIQUE
-- would otherwise allow any number of unbound administrators.
CREATE UNIQUE INDEX IF NOT EXISTS admin_users_one_per_channel_idx
  ON admin_users (channel_id)
  WHERE channel_id IS NOT NULL;
