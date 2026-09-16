/**
 * Channel slug generation (§6).
 *
 * A slug is the shareable identity of a channel, so it is derived from the
 * name once, at creation, and then never changes — editing a channel's name
 * must not break links that were already shared. `updateChannel` therefore
 * does not accept a slug.
 *
 * Kept free of database and Express imports so the rules below are directly
 * unit-testable: every one of them exists to satisfy the CHECK constraint on
 * `channels.slug` (`^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$`), and a slug that
 * fails that constraint is an unhandled 500 rather than a validation error.
 */

/** Must match `channels.slug`'s CHECK in migration 003. */
export const SLUG_MAX_LENGTH = 40;

const FALLBACK = 'channel';

/**
 * Fold a display name into a URL-safe slug.
 *
 * Unicode letters are decomposed and stripped of combining marks so that
 * "Café" becomes "cafe" rather than losing the character to a `\W` regex —
 * Arabic and other scripts have no ASCII form, so they collapse to nothing and
 * the caller's suffix (see [slugCandidate]) is what keeps the result valid.
 */
export function slugify(name: string): string {
  const ascii = name
    .normalize('NFKD')
    // Strip combining diacritical marks left behind by NFKD.
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase();

  const dashed = ascii
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, SLUG_MAX_LENGTH)
    // Slicing can leave a trailing dash, which the CHECK constraint rejects.
    .replace(/-+$/g, '');

  return dashed.length > 0 ? dashed : FALLBACK;
}

/**
 * A slug for the Nth attempt at a name.
 *
 * Attempt 0 returns the plain slug so the common case — a name nobody else has
 * used — produces the short, memorable link. Later attempts append a random
 * suffix, which is what makes collisions resolve without a race: two requests
 * creating "News" at the same instant generate different suffixes and one of
 * them simply succeeds on retry.
 *
 * The suffix is truncated to fit, keeping the total within the constraint even
 * when the base slug is already at the limit.
 */
export function slugCandidate(name: string, attempt: number, suffix: string): string {
  const base = slugify(name);
  if (attempt === 0) return base;

  // Room for the dash plus the suffix, and never zero-length so the trailing
  // alphanumeric the constraint demands is always present.
  const available = Math.max(1, SLUG_MAX_LENGTH - suffix.length - 1);
  const trimmed = base.slice(0, available).replace(/-+$/g, '') || FALLBACK.slice(0, available);
  return `${trimmed}-${suffix}`;
}
