-- 011_follower_messages_default.sql
--
-- Follower → channel messaging becomes the default rather than opt-in.
--
-- Why: §16 models private follower messages as something a channel may allow or
-- refuse, and the schema shipped with that switch OFF. In practice the switch
-- was the whole feature — a follower saw no way to write to a channel until its
-- owner had found a setting they had no reason to look for, and the owner saw an
-- empty inbox with no hint that anyone had tried. The product decision is now
-- the other way round: a new channel accepts messages, and an owner who does not
-- want them turns them off.
--
-- What this does NOT change:
--
--  * The switch itself stays. §16 requires the owner to be able to refuse, and
--    `allow_follower_messages = false` still makes `startConversation` refuse
--    with `messages_disabled`.
--  * Nothing about who may READ a conversation. Followers still cannot see each
--    other's threads, and none of this exposes a phone number or email (§12,
--    §38) — the inbox lists the follower as a private account, not as a
--    contact.
--
-- The backfill is a one-time product default, and it is stated plainly because
-- it can overwrite a deliberate OFF: there is no way to tell a channel that
-- chose OFF from one that simply never touched the default. Any owner who wants
-- it off can turn it off again, and this migration never runs a second time —
-- src/migrate.ts records each file's checksum.
--
-- No BEGIN/COMMIT: the runner wraps each file in its own transaction.

ALTER TABLE channels
  ALTER COLUMN allow_follower_messages SET DEFAULT true;

-- Existing channels: only ones that are still live. A suspended or banned
-- channel is not going to receive messages, and touching it would be editing
-- moderation state for a product default.
UPDATE channels
   SET allow_follower_messages = true
 WHERE allow_follower_messages = false
   AND status = 'active'
   AND deleted_at IS NULL;
