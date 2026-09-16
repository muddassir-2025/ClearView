import type { ChannelStatus } from './service.js';

/**
 * Who may read a channel's published content (§4, §8, §18).
 *
 * Kept as its own module, and its own function, because TWO places ask this
 * question: the posts service when it serves channel history, and the media
 * service when it hands out a read URL for one asset (§10). Those two are
 * different queries against different tables, so a copied condition would
 * drift — and the drift would be silent in exactly the direction that matters:
 * an asset still served after its post was removed.
 *
 * The rule:
 *
 *  * `active` — content is readable by any signed-in user. Every Good Post
 *    endpoint requires a session, so this is the whole public surface.
 *  * `suspended` — stopped broadcasting. It leaves every feed and is refused to
 *    followers, but its own admins keep read access: a moderator's action must
 *    leave the owner able to see what they are being asked to review.
 *  * `banned` — terminal. Nobody reads it, including its admins.
 *
 * Deliberately NOT the same question as "may this user follow it", which M2
 * answers separately, and not the same as "may this user publish", which needs
 * `active` regardless of role.
 */
export function contentVisibleTo(status: ChannelStatus, isChannelAdmin: boolean): boolean {
  if (status === 'banned') return false;
  return status === 'active' || isChannelAdmin;
}
