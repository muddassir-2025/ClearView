import type { Queryable } from '../db.js';
import type { ObjectStore } from '../media/store.js';
import { env } from '../env.js';

/**
 * Server-side cleanup (§14, §34).
 *
 * Two different reasons a post leaves, and they are not the same sweep:
 *
 *  1. **Deleted** — `deleted_at` is set, every read filters on it, and the row
 *     is already invisible. This finishes the job after `PURGE_GRACE_DAYS`.
 *
 *  2. **Aged out** — the post is older than `POST_RETENTION_DAYS`. Nothing was
 *     deleted by anybody; the retention window simply closed.
 *
 * Both hand their objects to the same collector, which is the part that matters
 * most: an object is removed only when no media row still references it (§34), so
 * neither pass can take an object another post is using.
 *
 * ## Why there is an age-based pass at all
 *
 * It is a product decision, not a storage accident. The server keeps roughly a
 * month of history so that someone who does NOT follow a channel can still
 * discover it and read back far enough to judge it (§14). Past that window the
 * copy is deleted, which caps what the deployment stores no matter how long a
 * channel has been running or how popular it becomes.
 *
 * The two consequences are worth stating plainly, because both are real:
 *
 *  * **A channel is not an archive.** An update older than the window is gone
 *    for everybody. Set `POST_RETENTION_DAYS=0` to keep everything instead —
 *    that is the supported way to opt out, and it is a single variable.
 *  * **A reader who downloaded an image keeps it.** Media already on a device is
 *    the device's business; the server has no handle on it and this never tries
 *    to reach back into one (§14).
 */

/** What one pass did, for the job's log line. */
export interface PurgeResult {
  readonly purged: number;
  readonly objectsRemoved: number;
  readonly objectsFailed: number;
}

const NOTHING: PurgeResult = { purged: 0, objectsRemoved: 0, objectsFailed: 0 };

/**
 * Physically remove posts that have been deleted, once the grace period is up.
 *
 * The object keys are read inside the same transaction that deletes the rows, so
 * the set of keys to remove is exactly the set the delete removed. Doing the
 * SELECT first and the DELETE afterwards would leave a window in which a
 * concurrent publish could attach a key that this pass then deletes.
 *
 * Objects are removed AFTER the commit. That order is deliberate: a crash between
 * the two leaves unreferenced objects in the bucket, which costs a little money
 * and is fixable by a later pass, whereas removing objects first and then failing
 * to commit would leave rows pointing at nothing — a broken post, which is the
 * failure §25 and §34 both warn about.
 */
export async function purgeExpiredPosts(
  database: Queryable,
  store: ObjectStore
): Promise<PurgeResult> {
  return purgePostsMatching(
    database,
    store,
    `deleted_at IS NOT NULL AND deleted_at < now() - ($1::int * interval '1 day')`,
    [env.PURGE_GRACE_DAYS, env.PURGE_BATCH_SIZE],
    'deleted'
  );
}

/**
 * Physically remove posts older than `POST_RETENTION_DAYS` (§14).
 *
 * Selected by AGE and not by deletion state: a post that is 40 days old has aged
 * out whether or not somebody had already deleted it, and a soft delete does not
 * need to be honoured for a row the window has already closed on.
 *
 * `POST_RETENTION_DAYS=0` disables the pass entirely and keeps everything — read
 * as "no window", not as "expire everything immediately", which is the difference
 * between opting out and catastrophically opting in.
 */
export async function purgeAgedPosts(
  database: Queryable,
  store: ObjectStore
): Promise<PurgeResult> {
  if (env.POST_RETENTION_DAYS <= 0) return NOTHING;

  return purgePostsMatching(
    database,
    store,
    `created_at < now() - ($1::int * interval '1 day')`,
    [env.POST_RETENTION_DAYS, env.PURGE_BATCH_SIZE],
    'aged out'
  );
}

/**
 * The one implementation both passes use.
 *
 * [where] names the rows, [params] bind the numbers in it, and [label] is what
 * the log calls them. Shared rather than duplicated because the object handling
 * is the part with the sharp edges — a second copy is a second chance to delete a
 * live object or to leave `last_post_at` describing a post that no longer exists.
 */
async function purgePostsMatching(
  database: Queryable,
  store: ObjectStore,
  where: string,
  params: readonly unknown[],
  label: string
): Promise<PurgeResult> {
  const collected = await database.transaction(async (tx) => {
    const rows = await tx.query<{ id: string }>(
      `SELECT id FROM posts
        WHERE ${where}
        ORDER BY created_at ASC
        LIMIT $${params.length}
        FOR UPDATE SKIP LOCKED`,
      params
    );

    const postIds = rows.map((row) => row.id);
    if (postIds.length === 0) return { postIds, keys: [] as string[] };

    const media = await tx.query<{ object_key: string }>(
      `SELECT object_key FROM post_media WHERE post_id = ANY($1::uuid[])`,
      [postIds]
    );

    // The channel each post belonged to, so `last_post_at` can be recomputed
    // rather than guessed at. That column is what the channel list sorts by and
    // what a row's timestamp describes, so a sweep that left it pointing at a
    // purged post would leave the list claiming activity that no longer exists.
    // On an age-based pass this matters more, not less: a channel whose every
    // post just expired should fall back down the list rather than sit at the
    // top of it advertising a post nobody can open.
    const channels = await tx.query<{ channel_id: string }>(
      `SELECT DISTINCT channel_id FROM posts WHERE id = ANY($1::uuid[])`,
      [postIds]
    );

    await tx.query(`DELETE FROM posts WHERE id = ANY($1::uuid[])`, [postIds]);

    for (const channel of channels) {
      await tx.query(
        `UPDATE channels
            SET last_post_at = (SELECT max(created_at) FROM posts WHERE channel_id = $1 AND deleted_at IS NULL)
          WHERE id = $1`,
        [channel.channel_id]
      );
    }

    return { postIds, keys: media.map((row) => row.object_key) };
  });

  if (collected.postIds.length === 0) return NOTHING;

  if (!store.configured) {
    // The rows are gone either way; only the bucket objects are left behind.
    // Reported once per pass rather than once per object, and NOT counted as
    // failures — an unconfigured deployment is a known state, not an error.
    console.warn(
      `[retention] purged ${collected.postIds.length} ${label} post(s) but left ${collected.keys.length} object(s) in place: no object storage is configured`
    );
    return { purged: collected.postIds.length, objectsRemoved: 0, objectsFailed: 0 };
  }

  const objects = await collectObjects(database, store, collected.keys);
  return { purged: collected.postIds.length, ...objects };
}

/**
 * Remove bucket objects that nothing references any more.
 *
 * §34's reference count: the rows that named each key were just deleted, so this
 * is normally zero — but it is ASKED rather than assumed, so the day something
 * shares an object the collector is already correct.
 */
async function collectObjects(
  database: Queryable,
  store: ObjectStore,
  keys: readonly string[]
): Promise<{ objectsRemoved: number; objectsFailed: number }> {
  let removed = 0;
  let failed = 0;

  for (const key of keys) {
    const stillReferenced = await database.queryOne(
      `SELECT 1 FROM post_media WHERE object_key = $1`,
      [key]
    );
    if (stillReferenced) continue;

    try {
      await store.remove(key);
      removed += 1;
    } catch (err) {
      // Never fatal. A bucket that refuses one deletion is a reason to try again
      // on the next tick, not a reason to stop the sweep or to leave the
      // database inconsistent.
      failed += 1;
      console.error(`[retention] could not remove an object: ${(err as Error).message}`);
    }
  }

  return { objectsRemoved: removed, objectsFailed: failed };
}
