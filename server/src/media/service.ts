import { randomUUID } from 'node:crypto';
import { env } from '../env.js';
import type { Queryable } from '../db.js';
import { badRequest, conflict, notFound } from '../http/errors.js';
import { isUuid } from '../channels/cursor.js';
import {
  extensionFor,
  kindFor,
  mediaObjectKey,
  mediaUnavailable,
  normalizeContentType,
  type MediaKind,
  type ObjectStore,
} from './store.js';

/**
 * The media lifecycle: request an upload, confirm it landed, and attach it to a
 * published post (§21, §22).
 *
 * Two properties make this design worth keeping rather than simplifying.
 *
 *  * **Storage is OPTIONAL.** Every entry point answers `media_unavailable`
 *    when no bucket is configured, and nothing else in Good Post consults this
 *    module — so a deployment with no S3 still publishes text and link posts
 *    (§22). That is not a degraded mode bolted on; it is the configuration the
 *    app is designed to run in.
 *
 *  * **Uploads are ADMINISTRATOR-owned.** `post_media.owner_id` references
 *    `admin_users`, which is what makes "only the account that requested this
 *    upload may confirm or attach it" a scoping rule rather than a convention.
 *    There are no reader-owned uploads any more, because there are no readers
 *    with accounts.
 *
 * The four states a row moves through:
 *
 *   pending  → a presigned URL was issued. Invisible to every reader, and the
 *              row's own uuid decides the object key, so nothing a client sends
 *              can name a path.
 *   ready    → the object was HEADed and is really there. Confirmed before it
 *              can be attached, because "the client says it uploaded" is not
 *              evidence.
 *   claimed  → `post_id IS NOT NULL`: attached to a post at publish time. Only
 *              the owner may claim, and only once.
 *   gone     → the row was deleted, with its object.
 *
 * Note which of those the enum actually stores: `media_status` holds only
 * `pending` and `ready`. Claiming is `post_id IS NOT NULL`.
 *
 * The store is a parameter rather than a module singleton so this whole
 * lifecycle is testable without an AWS account; the checks that matter are ours,
 * and a fake proves they hold.
 */

/** What a reader is told about one stored asset, never how to find it. */
export interface MediaSummary {
  readonly id: string;
  readonly kind: MediaKind;
  readonly contentType: string;
  readonly byteSize: number;
  readonly width: number | null;
  readonly height: number | null;
  readonly durationMs: number | null;
  readonly position: number;
  /**
   * A presigned, expiring read URL — or null when this deployment has no bucket.
   * Null rather than absent so the client has one shape to parse, and null
   * rather than a thrown error so a text post on a media-less deployment still
   * renders: a screen must not fail entirely because one asset is unservable.
   */
  readonly url: string | null;
}

/**
 * A row's columns, aliased `m` in every query that reads them.
 *
 * One constant rather than a per-query list, and never built from anything a
 * caller supplied: the alias is fixed so this can be interpolated safely, and a
 * column added to `post_media` is added in one place.
 */
const MEDIA_COLUMNS = `
  m.id, m.owner_id, m.post_id, m.channel_id, m.kind, m.object_key, m.content_type,
  m.byte_size, m.width, m.height, m.duration_ms, m.status, m.position
`;

export interface MediaRow {
  id: string;
  owner_id: string | null;
  post_id: string | null;
  /**
   * Set when this asset IS a channel's profile image (§21).
   *
   * Mutually exclusive with `post_id` in practice: one row is either attached to
   * a post or it is a channel's icon, never both, and migration 014's partial
   * unique index enforces the icon side of that.
   */
  channel_id: string | null;
  kind: MediaKind;
  object_key: string;
  content_type: string;
  byte_size: number;
  width: number | null;
  height: number | null;
  duration_ms: number | null;
  status: 'pending' | 'ready';
  position: number;
}

export interface RequestUploadInput {
  readonly contentType: string;
  readonly byteSize: number;
  readonly width?: number | undefined;
  readonly height?: number | undefined;
  readonly durationMs?: number | undefined;
}

/** A presigned upload, plus the media id every later step refers to. */
export interface PendingUpload {
  readonly mediaId: string;
  readonly kind: MediaKind;
  readonly contentType: string;
  readonly byteSize: number;
  readonly uploadUrl: string;
  readonly uploadHeaders: Readonly<Record<string, string>>;
  readonly expiresInSeconds: number;
}

/**
 * Issue a presigned upload URL (§21).
 *
 * Note what is NOT read from the request: the object key. It is derived from a
 * uuid this function generates, so a request cannot choose where its bytes land
 * — the standard way one account overwrites or claims another's object. The
 * content type is validated here rather than trusted, because it is what the
 * bucket will later serve with.
 */
export async function requestMediaUpload(
  database: Queryable,
  store: ObjectStore,
  adminId: string,
  input: RequestUploadInput
): Promise<PendingUpload> {
  // Checked before the insert, so a deployment with no bucket does not
  // accumulate unclaimable pending rows for uploads it refused.
  if (!store.configured) throw mediaUnavailable();

  const contentType = normalizeContentType(input.contentType);
  const kind = kindFor(contentType);
  const extension = extensionFor(contentType);
  if (!kind || !extension) {
    throw badRequest(
      'unsupported_media_type',
      'That file type cannot be posted. Images, video and audio only.'
    );
  }

  if (input.byteSize > env.S3_MAX_UPLOAD_BYTES) {
    throw badRequest(
      'media_too_large',
      `That file is larger than the ${Math.floor(env.S3_MAX_UPLOAD_BYTES / 1_048_576)} MB limit.`
    );
  }

  const mediaId = randomUUID();
  const objectKey = mediaObjectKey(kind, mediaId, extension);

  await database.query(
    `INSERT INTO post_media
       (id, owner_id, kind, object_key, content_type, byte_size, width, height, duration_ms, status)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'pending')`,
    [
      mediaId,
      adminId,
      kind,
      objectKey,
      contentType,
      input.byteSize,
      input.width ?? null,
      input.height ?? null,
      input.durationMs ?? null,
    ]
  );

  // Ordering note: if this throws (expired key, denied bucket, network), the
  // pending row above is already committed. That is deliberate — it is
  // owner-only, invisible to every reader, and pruned by age. The alternative
  // (presign first, insert after) would need a key derived from a row that does
  // not exist yet, which is exactly the client-chosen-key shape this refuses.
  const upload = await store.presignUpload({
    key: objectKey,
    contentType,
    byteSize: input.byteSize,
  });

  return {
    mediaId,
    kind,
    contentType,
    byteSize: input.byteSize,
    uploadUrl: upload.url,
    uploadHeaders: upload.headers,
    expiresInSeconds: upload.expiresInSeconds,
  };
}

/**
 * Confirm that the object really arrived, and mark the row claimable.
 *
 * Idempotent: confirming twice is a success, not a conflict. A client that
 * retried after a dropped response must not have its upload rejected for having
 * got through the first time.
 *
 * The bucket is asked, not the client. A row only becomes `ready` after a HEAD
 * finds the object, so a publish can never attach an asset that was never
 * uploaded.
 */
export async function confirmMediaUpload(
  database: Queryable,
  store: ObjectStore,
  adminId: string,
  mediaId: string
): Promise<MediaSummary> {
  if (!isUuid(mediaId)) throw notFound('media_not_found');

  // Scoped to the owner in SQL: an upload id belonging to another administrator
  // is indistinguishable from one that does not exist, which is the answer a
  // prober should get.
  const row = await database.queryOne<MediaRow>(
    `SELECT ${MEDIA_COLUMNS} FROM post_media m WHERE m.id = $1 AND m.owner_id = $2`,
    [mediaId, adminId]
  );
  if (!row) throw notFound('media_not_found');

  if (row.status === 'ready') return toSummary(store, row);

  if (!store.configured) throw mediaUnavailable();

  const stored = await store.head(row.object_key);
  if (!stored) {
    throw badRequest('media_not_uploaded', 'The upload was not received. Try sending the file again.');
  }

  // ContentType and ContentLength were signed into the upload URL, so S3 itself
  // rejected any request that differed. A mismatch here means the object
  // arrived through some other path, which is worth refusing rather than
  // accepting an asset whose declared metadata is wrong.
  if (stored.byteSize !== row.byte_size) {
    throw conflict(
      'media_size_mismatch',
      'The uploaded file does not match the size that was declared.'
    );
  }

  await database.query(
    `UPDATE post_media SET status = 'ready', confirmed_at = now()
      WHERE id = $1 AND status = 'pending'`,
    [mediaId]
  );

  return toSummary(store, { ...row, status: 'ready' });
}

/**
 * The rows a publish step is about to claim, locked.
 *
 * Scoped to the caller in SQL and returned in the order the caller asked for, so
 * `position` follows the composer's ordering rather than whatever order the
 * database happened to return. Every row is locked `FOR UPDATE` because this is
 * called inside the publish transaction: two concurrent publishes must not both
 * be able to attach the same upload, and a lock is the only thing that makes
 * that true.
 *
 * Nothing is validated here beyond ownership. Which states are acceptable is a
 * post-composition rule, so the caller decides and this stays a read.
 */
export async function lockMediaForClaim(
  database: Queryable,
  adminId: string,
  mediaIds: readonly string[]
): Promise<MediaRow[]> {
  if (mediaIds.length === 0) return [];

  const placeholders = mediaIds.map((_, i) => `$${i + 2}`).join(', ');
  const rows = await database.query<MediaRow>(
    `SELECT ${MEDIA_COLUMNS}
       FROM post_media m
      WHERE m.id IN (${placeholders})
        AND m.owner_id = $1
        -- An asset claimed as a channel's profile image is not a post
        -- attachment. Without this a post could adopt a channel's avatar and
        -- the channel would be left pointing at an object that belongs to a
        -- post the administrator could then delete.
        AND m.channel_id IS NULL
      FOR UPDATE`,
    [adminId, ...mediaIds]
  );

  const byId = new Map(rows.map((r) => [r.id, r]));
  return mediaIds.map((id) => byId.get(id)).filter((r): r is MediaRow => r !== undefined);
}

/**
 * Attach claimed rows to a published post, in the caller's order.
 *
 * The row must be `ready` (migration 004's CHECK enforces it) and the caller must
 * already hold it locked from [lockMediaForClaim]; this only writes the position
 * and the owning post.
 */
export async function attachMediaToPost(
  database: Queryable,
  postId: string,
  mediaIds: readonly string[]
): Promise<void> {
  for (const [position, mediaId] of mediaIds.entries()) {
    await database.query(`UPDATE post_media SET post_id = $1, position = $2 WHERE id = $3`, [
      postId,
      position,
      mediaId,
    ]);
  }
}

/**
 * Every media row for a set of posts, in publish order, keyed by post id.
 *
 * One extra query per page rather than a join on the post query: a join would
 * repeat the post's columns once per asset, and assembling the rows in JS is
 * what keeps `media` a nested array in the payload instead of a flattened
 * cartesian product no client can group back.
 */
export async function mediaForPosts(
  database: Queryable,
  store: ObjectStore,
  postIds: readonly string[]
): Promise<Map<string, MediaSummary[]>> {
  const byPost = new Map<string, MediaSummary[]>();
  if (postIds.length === 0) return byPost;

  // Placeholders rather than an array parameter: the id list is bounded by
  // MAX_PAGE_SIZE, and an explicit list behaves identically on `pg` and on
  // PGlite, which is what the suite runs.
  const placeholders = postIds.map((_, i) => `$${i + 1}`).join(', ');
  const rows = await database.query<MediaRow>(
    `SELECT ${MEDIA_COLUMNS}
       FROM post_media m
      WHERE m.post_id IN (${placeholders})
      ORDER BY m.post_id, m.position, m.id`,
    postIds
  );

  const grouped = await Promise.all(
    rows.map(async (r) => ({ postId: r.post_id, summary: await toSummary(store, r) }))
  );
  for (const { postId, summary } of grouped) {
    if (postId === null) continue;
    const list = byPost.get(postId) ?? [];
    list.push(summary);
    byPost.set(postId, list);
  }
  return byPost;
}

/**
 * Remove uploads that were presigned and then never claimed.
 *
 * Without this, every abandoned composer session leaves an object in the bucket
 * forever. The object is removed BEFORE the row: reversed, a crash between the
 * two would leave an object no row points at, which is unreachable and
 * unfindable; this way a crash leaves a row whose object is already gone, which
 * the next sweep removes. Both are retried, but only one of them converges.
 *
 * A failure to remove one object must not stop the sweep — otherwise a single
 * denied delete would freeze cleanup for every abandoned upload behind it. The
 * failures are counted, never logged by key.
 */
export async function sweepAbandonedUploads(
  database: Queryable,
  store: ObjectStore,
  windowMinutes: number,
  batchSize = 200
): Promise<{ readonly removed: number; readonly failed: number }> {
  if (!store.configured) return { removed: 0, failed: 0 };

  // `post_id IS NULL AND channel_id IS NULL` is what "claimed by nobody" means
  // after migration 014: a row is either attached to a post or it is a
  // channel's profile image, and only a row that is neither is abandoned.
  const rows = await database.query<{ id: string; object_key: string }>(
    `SELECT id, object_key
       FROM post_media
      WHERE post_id IS NULL
        AND channel_id IS NULL
        AND created_at < now() - ($1 || ' minutes')::interval
      ORDER BY created_at
      LIMIT $2`,
    [String(windowMinutes), batchSize]
  );

  let removed = 0;
  let failed = 0;

  for (const r of rows) {
    try {
      await store.remove(r.object_key);
      await database.query(
        `DELETE FROM post_media WHERE id = $1 AND post_id IS NULL AND channel_id IS NULL`,
        [r.id]
      );
      removed += 1;
    } catch {
      // The row stays, so the next tick retries it. Deleting the row here would
      // strand the object with nothing left referencing it.
      failed += 1;
    }
  }

  return { removed, failed };
}

/**
 * Claim a confirmed upload as a channel's profile image (§21).
 *
 * Runs inside the caller's transaction, for one reason that matters: the media
 * row and `channels.icon_object_key` must move together. Committing one without
 * the other would leave either a channel pointing at an object nobody owns, or
 * an owned object no channel points at — and the second is invisible until the
 * sweep deletes the avatar off a live channel.
 *
 * The previous icon's object key is RETURNED rather than removed here. Objects
 * are removed after the commit: the other order would delete an avatar and then
 * roll back, leaving a channel whose image is a row pointing at nothing.
 *
 * A channel's icon must be an asset the claiming administrator uploaded — the
 * owner check is in SQL — and it must be `ready`, which is a HEAD that already
 * happened. Migration 014's partial unique index is what makes "one icon per
 * channel" true even if two requests race here: the loser's `UPDATE` fails
 * rather than both writing.
 */
export async function claimChannelIcon(
  database: Queryable,
  adminId: string,
  channelId: string,
  mediaId: string
): Promise<{ readonly mediaId: string; readonly objectKey: string; readonly previousObjectKey: string | null }> {
  if (!isUuid(mediaId)) throw badRequest('unknown_media', 'That file is not available to attach.');

  const row = await database.queryOne<MediaRow>(
    `SELECT ${MEDIA_COLUMNS}
       FROM post_media m
      WHERE m.id = $1 AND m.owner_id = $2
      FOR UPDATE`,
    [mediaId, adminId]
  );

  // One answer for "no such row" and "somebody else's row": the upload id is a
  // capability, and a prober should learn nothing from the difference.
  if (!row) throw badRequest('unknown_media', 'That file is not available to attach.');
  if (row.status !== 'ready') {
    throw conflict('media_not_ready', 'That file has not finished uploading yet.');
  }
  if (row.post_id !== null) {
    throw conflict('media_already_used', 'That file is already part of a post.');
  }
  if (row.channel_id !== null && row.channel_id !== channelId) {
    throw conflict('media_already_used', 'That file is already another channel’s image.');
  }

  // The icon being replaced, if any. Read before the update so its key can be
  // handed back for removal once this transaction has committed.
  const previous = await database.queryOne<{ id: string; object_key: string }>(
    `SELECT id, object_key
       FROM post_media
      WHERE channel_id = $1 AND post_id IS NULL AND id <> $2
      FOR UPDATE`,
    [channelId, mediaId]
  );

  if (previous) {
    // The row goes now, in this transaction. The object it names goes after the
    // commit — see the header note.
    await database.query(`DELETE FROM post_media WHERE id = $1`, [previous.id]);
  }

  await database.query(
    `UPDATE post_media SET channel_id = $2, position = 0 WHERE id = $1`,
    [mediaId, channelId]
  );

  return {
    mediaId,
    objectKey: row.object_key,
    previousObjectKey: previous?.object_key ?? null,
  };
}

/**
 * Release a channel's profile image, returning the object to remove.
 *
 * Same transaction/removal split as [claimChannelIcon], and the row is deleted
 * rather than left dangling because a released icon has no purpose: nothing
 * else may adopt it (`channel_id` is what made it claimable) and the sweep would
 * only delete it later.
 */
export async function releaseChannelIcon(
  database: Queryable,
  channelId: string
): Promise<string | null> {
  const row = await database.queryOne<{ id: string; object_key: string }>(
    `SELECT id, object_key
       FROM post_media
      WHERE channel_id = $1 AND post_id IS NULL
      FOR UPDATE`,
    [channelId]
  );
  if (!row) return null;

  await database.query(`DELETE FROM post_media WHERE id = $1`, [row.id]);
  return row.object_key;
}

/**
 * Remove an object, and never fail because of it.
 *
 * Used for the objects these functions hand back. A bucket that refuses one
 * deletion is a reason to try again later, not a reason to report a failed
 * avatar change that actually succeeded — the database is already correct, and
 * the worst case is an unreferenced object that costs a little money.
 */
export async function removeObjectQuietly(store: ObjectStore, key: string | null): Promise<void> {
  if (!key || !store.configured) return;
  try {
    await store.remove(key);
  } catch (err) {
    console.error('[media] could not remove a replaced object:', (err as Error).message);
  }
}

/**
 * A signed, expiring read URL for an object key, or null.
 *
 * The ONE way an object key becomes something a client may fetch. A key on its
 * own is a permanent name for something meant to be temporary, and handing one
 * out would let it be used forever — which is why no payload anywhere carries
 * `icon_object_key`, only the URL derived from it.
 */
export async function signObjectUrl(
  store: ObjectStore,
  objectKey: string | null
): Promise<string | null> {
  if (!objectKey || !store.configured) return null;
  try {
    return await store.presignDownload(objectKey);
  } catch {
    // A configured store cannot fail to sign, but one unservable asset must
    // degrade to "no preview" rather than fail the screen it belongs to.
    return null;
  }
}

/**
 * A row as a reader sees it, with a freshly signed URL.
 *
 * An unconfigured deployment yields `url: null` rather than an error: a text post
 * must render on a server with no bucket, and one unservable asset must not fail
 * the whole page (§22).
 */
async function toSummary(store: ObjectStore, row: MediaRow): Promise<MediaSummary> {
  const url = await signObjectUrl(store, row.object_key);

  return {
    id: row.id,
    kind: row.kind,
    contentType: row.content_type,
    byteSize: row.byte_size,
    width: row.width,
    height: row.height,
    durationMs: row.duration_ms,
    position: row.position,
    url,
  };
}
