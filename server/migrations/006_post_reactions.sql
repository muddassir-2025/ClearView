-- 006_post_reactions.sql
--
-- What a reader thinks of a post (§9, §22).
--
-- WHY THIS FILE EXISTS
-- --------------------
-- 002 listed reactions as a reader-scoped thing that would arrive "later", and
-- 001 and 002 both say the reader surface returns no engagement numbers. This
-- file changes the second part, on purpose and at the owner's request: the
-- product is a channel messenger, and a reader who cannot react to a message is
-- missing the interaction they expect from one. The first part is unchanged —
-- a reaction hangs off the reader, exactly like a follow does.
--
-- WHAT IT IS NOT
-- --------------
-- No likes-with-a-public-list, no per-reader reaction history to browse, no
-- "who reacted" to anybody but the channel's own administrator. A reaction is
-- one emoji, one reader, one post.
--
-- ONE ROW PER READER PER POST, which is the primary key rather than a rule the
-- API remembers to enforce. A reader has one reaction to a post — pressing a
-- second emoji replaces the first — so changing your mind is an UPDATE of one
-- row and never a growing list. The alternative (a row per reaction) makes
-- un-reacting a search for the right row, and makes "did this reader react?"
-- a question about multiple rows with no single answer.
--
-- WHY A TABLE AND NOT A COUNTER
-- -----------------------------
-- `view_count` is a bare integer because nothing needs to know which reader
-- read what, and a table would have meant storing that. A reaction is the
-- opposite: the reader REPLACES their own reaction, so the server has to know
-- which row is theirs. That is one uuid per (reader, post), it is only written
-- when a reader deliberately reacts, and it is what makes the count an
-- aggregate over real rows rather than a number anybody can add to.
--
-- WHAT GOES IN IT
-- ---------------
--   post_reactions   one emoji per reader per post
--
-- No BEGIN/COMMIT here: src/migrate.ts wraps each file in its own transaction.

CREATE TABLE IF NOT EXISTS post_reactions (
  -- CASCADE both ways, and neither is bookkeeping:
  --
  --  * A reaction to a deleted post is a row for something no reader can see,
  --    which is the same reason the post is deleted rather than flagged.
  --  * A reaction belongs to a reader who has no account, so a reader whose uid
  --    is reclaimed takes their reactions with them; leaving them behind would
  --    attribute a reaction to a stranger when the uid is reissued.
  post_id     uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  reader_id   uuid NOT NULL REFERENCES readers(id) ON DELETE CASCADE,
  emoji       text NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 8),
  created_at  timestamptz NOT NULL DEFAULT now(),
  -- The moment of the LAST change, so "when did this start being a heart" is
  -- answerable without a second table of history.
  updated_at  timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (post_id, reader_id)
);

-- The whole closed vocabulary.
--
-- WhatsApp Channels offers these six, and a server that accepts anything would
-- be a database of whatever a modified client decided to send: a paragraph, a
-- script tag, or a different spelling of the same reaction (which would then
-- never aggregate with it). 8 characters allows the multi-codepoint emoji below
-- (a heart with a variation selector is two) while refusing text.
--
-- Adding a seventh emoji is a migration, and that is the right weight for it:
-- the number is part of the API's contract, not a preference.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'post_reactions_emoji_known'
  ) THEN
    ALTER TABLE post_reactions
      ADD CONSTRAINT post_reactions_emoji_known
      CHECK (emoji IN ('👍', '❤️', '😂', '😮', '😢', '🙏'));
  END IF;
END
$$;

-- Counting a post's reactions is the read that happens most: every post in a
-- page asks for its own. Keyed on the post, it is an index-only lookup of a
-- handful of rows.
--
-- The reader side needs the opposite direction — "this reader's reactions in
-- this channel" — which is a lookup by reader and then a filter by post, so it
-- gets its own index rather than a scan of everybody's.
CREATE INDEX IF NOT EXISTS post_reactions_post_idx ON post_reactions (post_id);
CREATE INDEX IF NOT EXISTS post_reactions_reader_idx ON post_reactions (reader_id);

COMMENT ON TABLE post_reactions IS
  'One emoji per reader per post. Replaced in place; deleted with the post or the reader.';
COMMENT ON COLUMN post_reactions.emoji IS
  'One of the six offered reactions, enforced by post_reactions_emoji_known.';
