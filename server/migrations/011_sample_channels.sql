-- 011_sample_channels.sql
--
-- A starting catalogue, so the discovery screens have something to discover.
--
-- A brand-new deployment had exactly one channel — whatever the operator created
-- first — and the Channels screen therefore rendered a heading, a single row and
-- a lot of empty space. That is not a loading state and not a bug: it is what an
-- empty catalogue looks like, and it made the screen impossible to judge.
--
-- These are ordinary rows, not fixtures. A super administrator can edit or delete
-- every one of them through the existing channel routes (§17), and the channel
-- ids are left to `gen_random_uuid()` precisely so nothing in the app can ever
-- special-case them — the moment a seed has a fixed id, some later code will find
-- a reason to check for it.
--
-- Idempotent on `slug`, which is UNIQUE: replaying the file cannot duplicate a
-- channel, and an operator's edits are left alone rather than reset. The category
-- seeds use ON CONFLICT DO NOTHING for the same reason.
--
-- No follower counts are seeded. A follower is a reader who made a decision, and
-- inventing readers to make a number look better would put a lie in the one
-- metric the product is careful about. The row shows the channel's category where
-- the count would be until somebody actually follows it.

-- ── The categories these channels live in ───────────────────────────────
-- `lifestyle`, `people`, `business` and `organization` are new; the rest already
-- exist from 001 and are re-stated here so this file is self-contained.
INSERT INTO channel_categories (slug, label, sort_order) VALUES
  ('news',          'News',          40),
  ('sports',        'Sports',        50),
  ('entertainment', 'Entertainment', 60),
  ('lifestyle',     'Lifestyle',     65),
  ('people',        'People',        68),
  ('business',      'Business',      72),
  ('organization',  'Organizations', 76)
ON CONFLICT (slug) DO NOTHING;

-- ── A few channels per category ─────────────────────────────────────────
INSERT INTO channels (slug, name, description, category_slug, country_code, last_post_at) VALUES
  ('cinema-circle',      'Cinema Circle',
   'Film news, release dates and the reviews worth reading.', 'entertainment', 'US', now() - interval '2 hours'),
  ('music-weekly',       'Music Weekly',
   'One album, one artist, one story — every week.', 'entertainment', 'GB', now() - interval '9 hours'),
  ('trailer-vault',      'Trailer Vault',
   'New trailers as they drop, with no commentary.', 'entertainment', 'US', now() - interval '1 day'),

  ('match-day',          'Match Day',
   'Fixtures, results and the goals you missed overnight.', 'sports', 'GB', now() - interval '40 minutes'),
  ('cricket-live',       'Cricket Live',
   'Ball-by-ball updates from every series in play.', 'sports', 'IN', now() - interval '3 hours'),
  ('football-digest',    'Football Digest',
   'Transfers, tactics and the table, twice a day.', 'sports', 'ES', now() - interval '7 hours'),

  ('morning-brief',      'Morning Brief',
   'The five things worth knowing before your first coffee.', 'news', 'US', now() - interval '30 minutes'),
  ('world-now',          'World Now',
   'Reporting from everywhere, updated through the day.', 'news', 'GB', now() - interval '5 hours'),
  ('tech-bulletin',      'Tech Bulletin',
   'What shipped, what broke, and what it means.', 'news', 'DE', now() - interval '11 hours'),

  ('daily-habits',       'Daily Habits',
   'Small routines that are actually worth keeping.', 'lifestyle', 'AU', now() - interval '4 hours'),
  ('kitchen-notes',      'Kitchen Notes',
   'Recipes with the mistakes already taken out.', 'lifestyle', 'IT', now() - interval '1 day'),
  ('travel-light',       'Travel Light',
   'Carry less, see more, and never queue for a suitcase.', 'lifestyle', 'PT', now() - interval '2 days'),

  ('voices-of-people',   'Voices of the People',
   'Interviews with the people doing the work.', 'people', 'NG', now() - interval '6 hours'),
  ('everyday-heroes',    'Everyday Heroes',
   'The quiet things people do that nobody reports.', 'people', 'KE', now() - interval '1 day'),

  ('market-watch',       'Market Watch',
   'Open, close and everything that moved in between.', 'business', 'US', now() - interval '1 hour'),
  ('startup-wire',       'Startup Wire',
   'Funding rounds, launches and the occasional failure.', 'business', 'SG', now() - interval '8 hours'),
  ('money-basics',       'Money Basics',
   'Plain explanations of the money questions nobody asks.', 'business', 'ZA', now() - interval '3 days'),

  ('open-source-org',    'Open Source Org',
   'Project news, releases and calls for contributors.', 'organization', 'US', now() - interval '5 hours'),
  ('community-trust',    'Community Trust',
   'What the trust is funding, and how to ask for it.', 'organization', 'GB', now() - interval '2 days')
ON CONFLICT (slug) DO NOTHING;
