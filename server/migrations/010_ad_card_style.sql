-- 010_ad_card_style.sql
--
-- How an advertisement card LOOKS, as data rather than as a constant in the app.
--
-- A card is a poster, and the three things that make one are its ink, how its
-- picture fills the frame, and which part of the picture to keep when the frame
-- crops it. All three were previously decided by the client — one colour, one
-- fit, one centre — which meant an administrator could change the words and
-- nothing else. They are columns now so the person writing the card chooses.
--
-- Defaults are exactly the old behaviour, so every existing row (including the
-- seeded default card) renders unchanged and no backfill is needed.

ALTER TABLE advertisements
  -- `#RRGGBB` only. A text card's words are drawn in this colour, and the
  -- database is the backstop against a client writing `red` or an rgba() the
  -- Android painter cannot parse: an unparseable colour would be a card whose
  -- text is invisible.
  ADD COLUMN IF NOT EXISTS text_color text NOT NULL DEFAULT '#E9EDEF'
    CHECK (text_color ~ '^#[0-9A-Fa-f]{6}$'),

  -- How the picture fills the card: `cover` fills and crops, `contain` shows the
  -- whole image with the card's surface behind it. An enum rather than free text
  -- because the client switches on it and a third value would be a card that
  -- draws nothing.
  ADD COLUMN IF NOT EXISTS image_fit text NOT NULL DEFAULT 'cover'
    CHECK (image_fit IN ('cover', 'contain')),

  -- The point of the picture to keep when `cover` crops it, 0..1 on each axis.
  -- 0.5/0.5 is the centre, which is what the client used to hardcode. Stored on
  -- the CARD rather than applied to the upload, so reframing never requires
  -- uploading the same photograph again.
  ADD COLUMN IF NOT EXISTS image_focus_x double precision NOT NULL DEFAULT 0.5
    CHECK (image_focus_x >= 0 AND image_focus_x <= 1),
  ADD COLUMN IF NOT EXISTS image_focus_y double precision NOT NULL DEFAULT 0.5
    CHECK (image_focus_y >= 0 AND image_focus_y <= 1);
