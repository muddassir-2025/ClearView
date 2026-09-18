import { createHash } from 'node:crypto';

/**
 * A short, stable fingerprint of everything a preview card shows about a
 * channel: its name, its description, its category and its picture.
 *
 * ## What it is for
 *
 * WhatsApp, Telegram and every other unfurler CACHE the card built from a URL —
 * for days, and there is no header, no purge endpoint and no `Cache-Control`
 * that asks them to look again. That is fine until the channel is renamed or
 * given a new picture: the owner updates it, shares a link, and the recipient
 * sees the channel it used to be. Nothing the page says about itself can fix
 * that, because the page is never fetched again.
 *
 * So the LINK has to change when the identity does. [channelIdentityVersion] is
 * appended to the share URL (`/c/<slug>?v=<version>`) and to the preview image
 * behind it, which means a renamed channel's next share is a URL no crawler has
 * ever seen — and, the other half of the same rule, that a channel nobody has
 * edited keeps one stable URL that crawlers can go on serving from their cache
 * forever.
 *
 * ## What it deliberately does NOT include
 *
 * Counters: followers, posts, views. They change constantly, and folding them
 * in would give every channel a new share URL every day for a card that looks
 * the same. The version is about the channel's IDENTITY, not its activity.
 *
 * The picture is identified by whatever string names the current object — the
 * storage key server-side, or the object path taken out of a signed URL, where
 * a signature is what is available. Both change when the picture is replaced,
 * which is all this needs; the two spellings do not have to agree with each
 * other, and they are not compared.
 */
export function channelIdentityVersion(parts: {
  readonly name: string;
  readonly description: string | null;
  readonly categorySlug: string | null;
  readonly iconIdentity: string | null;
}): string {
  const material = [
    parts.name,
    parts.description ?? '',
    parts.categorySlug ?? '',
    parts.iconIdentity ?? '',
  ].join('\u0000');

  return createHash('sha256').update(material).digest('hex').slice(0, 8);
}
