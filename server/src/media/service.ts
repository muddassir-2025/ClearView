import { randomUUID } from 'node:crypto';
import { env } from '../env.js';
import type { Queryable } from '../db.js';
import { badRequest, conflict, forbidden, notFound } from '../http/errors.js';
import { isUuid } from '../channels/cursor.js';
import { contentVisibleTo } from '../channels/visibility.js';
import type { ChannelStatus } from '../channels/service.js';
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
 * The media lifecycle: request an upload, confirm it landed, and hand out a
 * URL to read it back (§9, §10, §34).
 *
 * The four states a media row moves through, and why each exists:
 *
 *   pending  → a presigned URL was issued. Invisible to every reader, and the
 *              row's own uuid decides the object key, so nothing a client sends
 *              can name a path.
 *   ready    → the object was HEADed and is really there. Confirmed before it
 *              can be attached, because "the client says it uploaded" is not
 *              evidence — an unattached confirmed row is the only version of
 *              this that cannot ship a broken image into a published post.
 *   claimed  → attached to a post at publish time. Only the owner may claim,
 *              and only once (the UNIQUE object_key and the single-row UPDATE
 *              are what make that true under concurrency).
 *   swept    → never confirmed and never claimed, pruned by age (§34).
 *
 * The store is a parameter rather than a module singleton so this whole
 * lifecycle is testable without an AWS account; the checks that matter are
 * ours, and a fake proves they hold.
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
   * A presigned, expiring read URL — or null when this deployment has no
   * bucket. Null rather than absent so the client has one shape to parse, and
   * null rather than a thrown error so a text post on a media-less deployment
   * still renders: a feed must not fail entirely because one asset is
   * unservable.
   */
  readonly url: string | null;
}

/**
 * A row's columns, aliased `m` in every query that reads them.
 *
 * One constant rather than a per-query list, and never built from anything a
 * caller supplied: the alias is fixed so this can be interpolated safely, and
 * a column added to `post_media` is added in one place.
 */
const MEDIA_COLUMNS = `
  m.id, m.owner_id, m.post_id, m.kind, m.object_key, m.content_type,
  m.byte_size, m.width, m.height, m.duration_ms, m.status, m.position
`;

export interface MediaRow {
  id: string;
  owner_id: string;
  post_id: string | null;
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
  readonly width?: number;
  readonly height?: number;
  readonly durationMs?: number;
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
 * Issue a presigned upload URL (§9).
 *
 * Note what is NOT read from the request: the object key. It is derived from a
 * uuid this function generates, so a request cannot choose where its bytes
 * land — the standard way one account overwrites or claims another's object.
 * The content type is also validated here rather than trusted, because it is
 * what the bucket will later serve with.
 */
export async function requestMediaUpload(
  database: Queryable,
  userId: string,
  store: ObjectStore,
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
      userId,
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
  // owner-only, invisible to every reader, and the §34 sweep collects it by
  // age. The alternative (presign first, insert after) would need a key
  // derived from a row that does not exist yet, which is exactly the
  // client-chosen-key shape this module refuses.
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
 * Confirm that the object really arrived, and mark the row claimable (§34).
 *
 * Idempotent: confirming twice is a success, not a conflict. A client that
 * retried after a dropped response must not have its upload rejected for
 * having got through the first time.
 *
 * The bucket is asked, not the client. A row only becomes `ready` after a HEAD
 * finds the object, so a publish can never attach an asset that was never
 * uploaded.
 */
export async function confirmMediaUpload(
  database: Queryable,
  userId: string,
  store: ObjectStore,
  mediaId: string
): Promise<MediaSummary> {
  if (!isUuid(mediaId)) throw notFound('media_not_found');

  // Scoped to the owner in SQL: an upload id belonging to someone else is
  // indistinguishable from one that does not exist, which is the answer a
  // prober should get.
  const row = await database.queryOne<MediaRow>(
    `SELECT ${MEDIA_COLUMNS} FROM post_media m WHERE m.id = $1 AND m.owner_id = $2`,
    [mediaId, userId]
  );
  if (!row) throw notFound('media_not_found');

  if (row.status === 'ready') return toSummary(store, row);

  if (!store.configured) throw mediaUnavailable();

  const stored = await store.head(row.object_key);
  if (!stored) {
    throw badRequest(
      'media_not_uploaded',
      'The upload was not received. Try sending the file again.'
    );
  }

  // ContentType and ContentLength were signed into the upload URL, so S3
  // itself rejected any request that differed. A mismatch here means the
  // object arrived through some other path, which is worth refusing rather
  // than accepting an asset whose declared metadata is wrong.
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
 * A fresh presigned read URL for one asset (§10).
 *
 * This is the endpoint a manual download uses, because a URL embedded in a
 * post payload expires (S3_DOWNLOAD_URL_TTL) and a client that cached that
 * JSON would otherwise hold a dead link. Minting one on demand is cheap — it is
 * a local signature, not an AWS round trip.
 *
 * Authorization is the point of this function, so it is stated plainly:
 *
 *  * an unclaimed row is readable only by the account that requested it, which
 *    is what lets the composer preview an upload before publishing it;
 *  * a claimed row is readable by anyone who can read its post: the post not
 *    deleted, the channel not deleted, and the channel's content visible to this
 *    viewer. A soft-deleted post (§25, a moderator's removal) therefore stops
 *    serving its media immediately, which is the property that makes removal
 *    mean something — and so does a suspension, for everyone except the
 *    channel's own admins, through [contentVisibleTo];
 *  * everything else is `media_not_found`, including a row that exists but
 *    belongs to someone else.
 *
 * The visibility rule is IMPORTED rather than restated. Channel history asks
 * the same question against different tables, and a copied condition would
 * drift precisely in the direction that leaves a removed asset still being
 * served.
 */
export async function mediaDownloadUrl(
  database: Queryable,
  userId: string,
  store: ObjectStore,
  mediaId: string
): Promise<{ readonly url: string; readonly expiresInSeconds: number }> {
  if (!isUuid(mediaId)) throw notFound('media_not_found');
  if (!store.configured) throw mediaUnavailable();

  const row = await database.queryOne<
    MediaRow & {
      post_deleted_at: unknown;
      channel_deleted_at: unknown;
      channel_status: ChannelStatus;
      viewer_is_channel_admin: boolean;
    }
  >(
    `SELECT ${MEDIA_COLUMNS},
            p.deleted_at AS post_deleted_at,
            c.deleted_at AS channel_deleted_at,
            c.status AS channel_status,
            (a.user_id IS NOT NULL) AS viewer_is_channel_admin
       FROM post_media m
       LEFT JOIN posts p ON p.id = m.post_id
       LEFT JOIN channels c ON c.id = p.channel_id
       LEFT JOIN channel_admins a ON a.channel_id = c.id AND a.user_id = $2
      WHERE m.id = $1`,
    [mediaId, userId]
  );
  if (!row) throw notFound('media_not_found');

  const ownUnclaimed = row.post_id === null && row.owner_id === userId;
  const published =
    row.post_id !== null &&
    row.post_deleted_at == null &&
    row.channel_deleted_at == null &&
    contentVisibleTo(row.channel_status, row.viewer_is_channel_admin);

  if (!ownUnclaimed && !published) {
    // Not-found rather than forbidden for someone else's unclaimed upload:
    // telling them it exists but is not theirs is a probe for valid ids.
    if (row.post_id === null) throw notFound('media_not_found');
    throw forbidden('media_forbidden', 'This media is not available.');
  }

  const url = await store.presignDownload(row.object_key);
  return { url, expiresInSeconds: env.S3_DOWNLOAD_URL_TTL };
}

/**
 * The media rows a publish step is about to claim, locked.
 *
 * Scoped to the caller in SQL and returned in the order the caller asked for,
 * so `position` follows the composer's ordering rather than whatever order the
 * database happened to return. Every row is locked `FOR UPDATE` because this
 * is called inside the publish transaction: two concurrent publishes must not
 * both be able to attach the same upload, and a lock is the only thing that
 * makes that true.
 *
 * Nothing is validated here beyond ownership. Which states are acceptable is a
 * post-composition rule, so the caller decides and this stays a read.
 */
export async function lockMediaForClaim(
  database: Queryable,
  userId: string,
  mediaIds: readonly string[]
): Promise<MediaRow[]> {
  if (mediaIds.length === 0) return [];

  const placeholders = mediaIds.map((_, i) => `$${i + 2}`).join(', ');
  const rows = await database.query<MediaRow>(
    `SELECT ${MEDIA_COLUMNS}
       FROM post_media m
      WHERE m.id IN (${placeholders}) AND m.owner_id = $1
      FOR UPDATE`,
    [userId, ...mediaIds]
  );

  const byId = new Map(rows.map((row) => [row.id, row]));
  return mediaIds.map((id) => byId.get(id)).filter((row): row is MediaRow => row !== undefined);
}

/**
 * Attach claimed rows to a published post, in the caller's order.
 *
 * The row must be `ready` (migration 004's CHECK enforces it) and the caller
 * must already hold it locked from [lockMediaForClaim]; this only writes the
 * position and the owning post.
 */
export async function attachMediaToPost(
  database: Queryable,
  postId: string,
  mediaIds: readonly string[]
): Promise<void> {
  for (const [position, mediaId] of mediaIds.entries()) {
    await database.query(
      `UPDATE post_media SET post_id = $1, position = $2 WHERE id = $3`,
      [postId, position, mediaId]
    );
  }
}

/**
 * Remove uploads that were presigned and then never claimed (§34).
 *
 * Without this, every abandoned composer session leaves an object in the
 * bucket forever. The `UPLOAD_CLAIM_WINDOW_MINUTES` window is minutes rather
 * than days because an unclaimed upload is invisible to every reader — it is
 * storage cost, not correctness.
 *
 * The object is removed BEFORE the row. Reversed, a crash between the two
 * would leave an object no row points at, which is unreachable and unfindable;
 * this way a crash leaves a row whose object is already gone, which the next
 * sweep removes. Both are retried, but only one of them converges.
 *
 * A failure to remove one object must not stop the sweep — otherwise a single
 * denied delete would freeze cleanup for every abandoned upload behind it. The
 * failures are logged by count, never by key.
 */
export async function sweepAbandonedUploads(
  database: Queryable,
  store: ObjectStore,
  windowMinutes: number,
  batchSize = 200
): Promise<{ readonly removed: number; readonly failed: number }> {
  if (!store.configured) return { removed: 0, failed: 0 };

  const rows = await database.query<{ id: string; object_key: string }>(
    `SELECT id, object_key
       FROM post_media
      WHERE post_id IS NULL
        AND created_at < now() - ($1 || ' minutes')::interval
      ORDER BY created_at
      LIMIT $2`,
    [String(windowMinutes), batchSize]
  );

  let removed = 0;
  let failed = 0;

  for (const row of rows) {
    try {
      await store.remove(row.object_key);
      await database.query(`DELETE FROM post_media WHERE id = $1 AND post_id IS NULL`, [row.id]);
      removed += 1;
    } catch {
      // The row stays, so the next tick retries it. Deleting the row here
      // would strand the object with nothing left referencing it.
      failed += 1;
    }
  }

  return { removed, failed };
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

  // `post_id` is non-null for every row this query can return (the WHERE
  // clause requires it), but the column is nullable in the schema, so the
  // grouping carries the id alongside the summary rather than re-reading it.
  const grouped = await Promise.all(
    rows.map(async (row) => ({ postId: row.post_id, summary: await toSummary(store, row) }))
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
 * A row as a reader sees it, with a freshly signed URL.
 *
 * An unconfigured deployment yields `url: null` rather than an error: a text
 * post must render on a server with no bucket, and one unservable asset must
 * not fail the whole page.
 */
async function toSummary(store: ObjectStore, row: MediaRow): Promise<MediaSummary> {
  let url: string | null = null;
  if (store.configured) {
    try {
      url = await store.presignDownload(row.object_key);
    } catch {
      // Signing cannot fail for a configured store, but a failure here must
      // degrade to "no preview" rather than break the feed it belongs to.
      url = null;
    }
  }

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
