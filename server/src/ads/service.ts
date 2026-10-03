import type { Queryable } from '../db.js';
import { isoOrNull } from '../db.js';
import { badRequest, notFound } from '../http/errors.js';
import { isUuid } from '../channels/cursor.js';
import { signObjectUrl } from '../media/service.js';
import type { ObjectStore } from '../media/store.js';

/**
 * Administrator-controlled advertisement cards (§9–§17).
 *
 * These are NOT Google Ads and no third-party SDK is involved: an advertisement
 * is a row the platform's super administrator creates and fully controls, shown
 * at the top of Channels and Explore in the Good Post tab.
 *
 * ## One shape, two audiences
 *
 * The admin surface sees every row (enabled or not, active or not) so it can
 * manage them. The public surface sees only the ones that are CURRENTLY ACTIVE
 * for the requested placement, filtered in SQL — never in the client — so an
 * expired or disabled card cannot be shown because a client forgot a rule.
 *
 * ## The image is the existing media lifecycle
 *
 * An image card's bytes are uploaded through the SAME handshake as a post
 * attachment or a channel icon (request → confirm → claim). `create`/`update`
 * therefore validate and CLAIM a media row rather than trusting a client-supplied
 * URL, which is what keeps one upload path and one cleanup sweep for the whole
 * product.
 */

export type AdPlacement = 'channels' | 'explore';

export interface AdvertisementRow {
  readonly id: string;
  readonly content_type: 'image' | 'text';
  readonly image_object_key: string | null;
  readonly text_content: string | null;
  readonly target_url: string | null;
  readonly show_in_channels: boolean;
  readonly show_in_explore: boolean;
  readonly enabled: boolean;
  readonly starts_at: unknown;
  readonly expires_at: unknown;
  readonly priority: number;
  readonly created_at: unknown;
  readonly updated_at: unknown;
}

/** What an administrator (and, for active rows, a reader) receives. */
export interface AdvertisementSummary {
  readonly id: string;
  readonly contentType: 'image' | 'text';
  /** A freshly signed, expiring URL, or null for a text card / no bucket. */
  readonly imageUrl: string | null;
  readonly text: string | null;
  readonly targetUrl: string | null;
  readonly showInChannels: boolean;
  readonly showInExplore: boolean;
  readonly enabled: boolean;
  readonly startsAt: string | null;
  readonly expiresAt: string | null;
  readonly priority: number;
}

const AD_COLUMNS = `id, content_type, image_object_key, text_content, target_url,
  show_in_channels, show_in_explore, enabled, starts_at, expires_at, priority,
  created_at, updated_at`;

async function toSummary(store: ObjectStore, row: AdvertisementRow): Promise<AdvertisementSummary> {
  return {
    id: row.id,
    contentType: row.content_type,
    imageUrl: await signObjectUrl(store, row.image_object_key),
    text: row.text_content,
    targetUrl: row.target_url,
    showInChannels: row.show_in_channels,
    showInExplore: row.show_in_explore,
    enabled: row.enabled,
    startsAt: isoOrNull(row.starts_at),
    expiresAt: isoOrNull(row.expires_at),
    priority: row.priority,
  };
}

/** Every advertisement, for the admin list — active or not. */
export async function listAdsForAdmin(
  database: Queryable,
  store: ObjectStore
): Promise<AdvertisementSummary[]> {
  const rows = await database.query<AdvertisementRow>(
    `SELECT ${AD_COLUMNS} FROM advertisements ORDER BY priority, created_at DESC`
  );
  return Promise.all(rows.map((row) => toSummary(store, row)));
}

/** Load one row, or null. */
export async function loadAdRow(
  database: Queryable,
  id: string
): Promise<AdvertisementRow | null> {
  if (!isUuid(id)) return null;
  return database.queryOne<AdvertisementRow>(
    `SELECT ${AD_COLUMNS} FROM advertisements WHERE id = $1`,
    [id]
  );
}

/** One advertisement as a summary, or null. */
export async function getAdvertisement(
  database: Queryable,
  store: ObjectStore,
  id: string
): Promise<AdvertisementSummary | null> {
  const row = await loadAdRow(database, id);
  return row ? toSummary(store, row) : null;
}

export interface AdvertisementInput {
  readonly contentType: 'image' | 'text';
  readonly text: string | null;
  readonly targetUrl: string | null;
  readonly showInChannels: boolean;
  readonly showInExplore: boolean;
  readonly enabled: boolean;
  readonly startsAt: Date | null;
  readonly expiresAt: Date | null;
  readonly priority: number;
  /** A confirmed, owned image upload to claim. Required for an image card. */
  readonly mediaId: string | null;
}

interface MediaClaimRow {
  readonly id: string;
  readonly object_key: string;
  readonly status: string;
  readonly kind: string;
  readonly post_id: string | null;
  readonly channel_id: string | null;
  readonly advertisement_id: string | null;
}

async function lockImageUpload(
  database: Queryable,
  adminId: string,
  mediaId: string
): Promise<MediaClaimRow> {
  if (!isUuid(mediaId)) {
    throw badRequest('unknown_media', 'That file is not available to attach.');
  }
  const row = await database.queryOne<MediaClaimRow>(
    `SELECT id, object_key, status, kind, post_id, channel_id, advertisement_id
       FROM post_media
      WHERE id = $1 AND owner_id = $2
      FOR UPDATE`,
    [mediaId, adminId]
  );
  if (!row) throw badRequest('unknown_media', 'That file is not available to attach.');
  if (row.status !== 'ready') {
    throw badRequest('media_not_ready', 'That file has not finished uploading yet.');
  }
  if (row.post_id !== null || row.channel_id !== null) {
    throw badRequest('media_already_used', 'That file is already used elsewhere.');
  }
  if (row.kind !== 'image') {
    throw badRequest('ad_image_must_be_image', 'An advertisement image has to be a picture.');
  }
  return row;
}

/**
 * Create an advertisement (§11).
 *
 * An image card CLAIMS its upload in the same transaction that inserts the row,
 * so an image advertisement can never exist without the object it points at.
 * A text card requires words.
 */
export async function createAdvertisement(
  database: Queryable,
  store: ObjectStore,
  adminId: string,
  input: AdvertisementInput
): Promise<AdvertisementSummary> {
  if (input.contentType === 'text' && (input.text === null || input.text.trim() === '')) {
    throw badRequest('ad_content_required', 'A text advertisement needs some text.');
  }
  if (input.contentType === 'image' && input.mediaId === null) {
    throw badRequest('ad_image_required', 'An image advertisement needs a picture.');
  }

  const id = await database.transaction(async (tx) => {
    let objectKey: string | null = null;
    let mediaId: string | null = null;

    if (input.contentType === 'image' && input.mediaId) {
      const media = await lockImageUpload(tx, adminId, input.mediaId);
      objectKey = media.object_key;
      mediaId = media.id;
    }

    const row = await tx.queryOne<{ id: string }>(
      `INSERT INTO advertisements
         (content_type, image_object_key, text_content, target_url,
          show_in_channels, show_in_explore, enabled, starts_at, expires_at,
          priority, created_by_admin_id)
       VALUES ($1, $2, $3, $4, $5, $6, $7,
               COALESCE($8::timestamptz, now()), $9::timestamptz, $10, $11)
       RETURNING id`,
      [
        input.contentType,
        objectKey,
        input.contentType === 'text' ? input.text : null,
        input.targetUrl,
        input.showInChannels,
        input.showInExplore,
        input.enabled,
        input.startsAt ? input.startsAt.toISOString() : null,
        input.expiresAt ? input.expiresAt.toISOString() : null,
        input.priority,
        adminId,
      ]
    );
    const inserted = row?.id;
    if (!inserted) throw new Error('[ads] insert returned no id');

    if (mediaId) {
      await tx.query(`UPDATE post_media SET advertisement_id = $2 WHERE id = $1`, [
        mediaId,
        inserted,
      ]);
    }
    return inserted;
  });

  const created = await loadAdRow(database, id);
  if (!created) throw new Error('[ads] created row could not be read back');
  return toSummary(store, created);
}

/**
 * Edit an advertisement (§11).
 *
 * The content type may change. Switching to text releases the image; switching
 * to image (or replacing the picture) claims the new upload and removes the one
 * it replaces. `previousObjectKey` is returned so the caller can delete the old
 * object AFTER the transaction commits — deleting it first would leave the card
 * pointing at nothing if the commit then failed.
 */
export async function updateAdvertisement(
  database: Queryable,
  store: ObjectStore,
  adminId: string,
  id: string,
  input: AdvertisementInput
): Promise<{ readonly ad: AdvertisementSummary; readonly removedObjectKey: string | null }> {
  const existing = await loadAdRow(database, id);
  if (!existing) throw notFound('ad_not_found');

  if (input.contentType === 'text' && (input.text === null || input.text.trim() === '')) {
    throw badRequest('ad_content_required', 'A text advertisement needs some text.');
  }
  const becomesImage = input.contentType === 'image';
  if (becomesImage && input.mediaId === null && existing.image_object_key === null) {
    throw badRequest('ad_image_required', 'An image advertisement needs a picture.');
  }

  const removedObjectKey = await database.transaction(async (tx) => {
    let objectKey = existing.image_object_key;
    let removed: string | null = null;

    if (becomesImage && input.mediaId) {
      const media = await lockImageUpload(tx, adminId, input.mediaId);
      // Drop the card's previous image row, and hand its object back for
      // post-commit removal.
      const previous = await tx.queryOne<{ id: string; object_key: string }>(
        `SELECT id, object_key FROM post_media
          WHERE advertisement_id = $1 AND id <> $2
          FOR UPDATE`,
        [id, media.id]
      );
      if (previous) {
        await tx.query(`DELETE FROM post_media WHERE id = $1`, [previous.id]);
        removed = previous.object_key;
      }
      await tx.query(`UPDATE post_media SET advertisement_id = $2, position = 0 WHERE id = $1`, [
        media.id,
        id,
      ]);
      objectKey = media.object_key;
    } else if (!becomesImage && existing.image_object_key !== null) {
      // Became a text card: its image is no longer part of it.
      const row = await tx.queryOne<{ id: string; object_key: string }>(
        `SELECT id, object_key FROM post_media WHERE advertisement_id = $1 FOR UPDATE`,
        [id]
      );
      if (row) {
        await tx.query(`DELETE FROM post_media WHERE id = $1`, [row.id]);
        removed = row.object_key;
      }
      objectKey = null;
    }

    await tx.query(
      `UPDATE advertisements
          SET content_type = $2,
              image_object_key = $3,
              text_content = $4,
              target_url = $5,
              show_in_channels = $6,
              show_in_explore = $7,
              enabled = $8,
              starts_at = COALESCE($9::timestamptz, starts_at),
              expires_at = $10::timestamptz,
              priority = $11
        WHERE id = $1`,
      [
        id,
        input.contentType,
        objectKey,
        input.contentType === 'text' ? input.text : null,
        input.targetUrl,
        input.showInChannels,
        input.showInExplore,
        input.enabled,
        input.startsAt ? input.startsAt.toISOString() : null,
        input.expiresAt ? input.expiresAt.toISOString() : null,
        input.priority,
      ]
    );
    return removed;
  });

  const updated = await loadAdRow(database, id);
  if (!updated) throw notFound('ad_not_found');
  return { ad: await toSummary(store, updated), removedObjectKey };
}

/** Delete an advertisement, returning the image object key to remove. */
export async function deleteAdvertisement(
  database: Queryable,
  id: string
): Promise<string | null> {
  if (!isUuid(id)) throw notFound('ad_not_found');
  return database.transaction(async (tx) => {
    const row = await tx.queryOne<{ id: string }>(
      `SELECT id FROM advertisements WHERE id = $1 FOR UPDATE`,
      [id]
    );
    if (!row) throw notFound('ad_not_found');
    const image = await tx.queryOne<{ object_key: string }>(
      `SELECT object_key FROM post_media WHERE advertisement_id = $1`,
      [id]
    );
    // The media row cascades with the advertisement; the object is removed by
    // the caller once this has committed.
    await tx.query(`DELETE FROM advertisements WHERE id = $1`, [id]);
    return image?.object_key ?? null;
  });
}

/**
 * The public read (§12).
 *
 * Only ACTIVE cards for the requested placement: enabled, started, not expired,
 * and placed there. Filtered in SQL so a client can never show a card it should
 * not, and so an expired advertisement disappears with no cleanup at all.
 */
export async function listActiveAdvertisements(
  database: Queryable,
  store: ObjectStore,
  placement: AdPlacement,
  limit = 10
): Promise<AdvertisementSummary[]> {
  const placementColumn = placement === 'channels' ? 'show_in_channels' : 'show_in_explore';
  const rows = await database.query<AdvertisementRow>(
    `SELECT ${AD_COLUMNS}
       FROM advertisements
      WHERE enabled = true
        AND ${placementColumn} = true
        AND starts_at <= now()
        AND (expires_at IS NULL OR expires_at > now())
      ORDER BY priority, created_at DESC
      LIMIT $1`,
    [limit]
  );
  return Promise.all(rows.map((row) => toSummary(store, row)));
}
