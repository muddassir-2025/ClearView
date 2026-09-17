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
 *  * `active` — content is readable by ANYONE. Good Post's readers are
 *    anonymous and there is no session to require, so this single status is the
 *    whole public surface.
 *  * `suspended` — stopped broadcasting. It leaves the channel list, Explore and
 *    the public read entirely, but its own administrator keeps read access: the
 *    person being asked to fix it has to be able to see it.
 *  * `banned` — terminal. Nobody reads it, including its administrator.
 *
 * Deliberately NOT the same question as "may this administrator publish here",
 * which additionally requires the account to be in scope for this channel
 * (`requireChannelScope`), and not the same as "is it active", which publishing
 * needs regardless of who is asking.
 */
export function contentVisibleTo(status: ChannelStatus, isChannelAdmin: boolean): boolean {
  if (status === 'banned') return false;
  return status === 'active' || isChannelAdmin;
}
