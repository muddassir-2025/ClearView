-- The constraints that name the `document` label, in a transaction of their own.
--
-- See 007: PostgreSQL will not let a transaction USE an enum value it added, so the
-- two statements below could not live in the file that created the label. Run after
-- 007, which the runner guarantees by filename order.

-- ── A post of type `document` is a renderable shape ──────────────────────
--
-- 001 listed the four shapes §21 asked for and closed the enum so a `poll` or
-- `audio` label could be cast but never stored. `document` is the fifth shape, and
-- it is renderable: the client has a card for it (a file chip that opens the file),
-- which is the only test this constraint applies.
--
-- Rewritten rather than extended because a CHECK cannot be altered: it is dropped
-- and re-added with the fuller list. On a table this size the validation pass is
-- instantaneous, and it happens inside the migration's own transaction, so a reader
-- never sees a window without the constraint.
ALTER TABLE posts DROP CONSTRAINT IF EXISTS posts_type_is_renderable;
ALTER TABLE posts ADD CONSTRAINT posts_type_is_renderable
  CHECK (type IN ('text', 'image', 'video', 'link', 'document'));

-- ── A document has a name ────────────────────────────────────────────────
--
-- Enforced here and not only in the API because a nameless document is a row no
-- client can draw: the card's whole content is the name, byte size and an icon.
-- The upload endpoint refuses a document without one with a 400 the composer can
-- word; this is the same rule at the level that cannot be bypassed.
ALTER TABLE post_media DROP CONSTRAINT IF EXISTS post_media_document_has_name;
ALTER TABLE post_media ADD CONSTRAINT post_media_document_has_name
  CHECK (kind <> 'document' OR (file_name IS NOT NULL AND btrim(file_name) <> ''));

-- A stored name is bounded, whatever layer wrote it. The API truncates rather than
-- rejecting, so this is a backstop for the path that does not go through it.
ALTER TABLE post_media DROP CONSTRAINT IF EXISTS post_media_file_name_length;
ALTER TABLE post_media ADD CONSTRAINT post_media_file_name_length
  CHECK (file_name IS NULL OR char_length(file_name) BETWEEN 1 AND 255);
