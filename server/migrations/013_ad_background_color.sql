-- 013_ad_background_color.sql
--
-- The card's own surface.
--
-- A card is a poster and its background is part of the design; drawing every one
-- of them on the app's bar colour made them look like pieces of the interface
-- rather than like something placed on it. The default is exactly that old bar
-- colour, so every existing row — including the seeded default card — renders
-- unchanged and nothing needs a backfill.
--
-- `#RRGGBB` only, matching `text_color` and for the same reason: the Android
-- painter cannot read anything else, and an unparseable value would be a card
-- drawn on a colour nobody chose.

ALTER TABLE advertisements
  ADD COLUMN IF NOT EXISTS background_color text NOT NULL DEFAULT '#202C33'
    CHECK (background_color ~ '^#[0-9A-Fa-f]{6}$');
