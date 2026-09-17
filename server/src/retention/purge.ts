import { env } from '../env.js';
import type { Queryable } from '../db.js';
import type { ObjectStore } from '../media/store.js';

/**
 * Server-side retention (§11) and media cleanup (§34).
 *
 * Three passes, in this order and no other:
 *
 *  1. **Expire** — posts older than `GOODPOST_HISTORY_DAYS` stop being readable.
 *     This is a soft delete (`deleted_at = now()`), which is what §11 means by a
 *     retention WINDOW rather than a retention deadline: the row is unreadable
 *     immediately, and the data is still there during the grace period.
 *
 *  2. **Purge** — rows that have been unreadable for `PURGE_GRACE_DAYS` are
 *     deleted for real, and their object keys are collected BEFORE the delete,
 *     because after it there is nothing left to tell us which objects to remove.
 *
 *  3. **Collect** — each collected object is removed only if no media row still
 *     refers to it. That check is the whole of §34: "media deletion must not
 *     accidentally delete a shared object still referenced elsewhere". Today an
 *     object key is derived from its own media row's uuid, so sharing cannot
 *     happen — but the check is written as a reference count rather than as
 *     "always safe", so the day something does share an object, the GC is
 *     already correct.
 *
 * What this explicitly does NOT do, and must never do: touch media a user has
 * downloaded to their own device (§10). That media lives in the app's files
 * directory on the phone; the server has no handle on it, and its absence from
 * this file is the guarantee. A post disappearing from the server leaves every
 * offline copy exactly where it was.
 */

export interface RetentionReport {
  readonly expired: number;
  readonly purged: number;
  readonly objectsRemoved: number;
  readonly objectsFailed: number;
}

/**
 * Stage 1: expire posts past the history window.
 *
 * One statement, bounded by `PURGE_BATCH_SIZE` so a large backlog cannot hold a
 * long transaction or blow up the log. `deleted_reason` records WHY, which is
 * what lets an owner tell a moderator removal from the retention sweep (§7).
 *
 * The window is read from configuration every run rather than stored per row, so
 * changing `GOODPOST_HISTORY_DAYS` is a configuration change and not a data
 * migration — the reason 004 kept the window out of the table.
 */
export async function expireOldPosts(database: Queryable): Promise<number> {
  const rows = await database.query(
    `UPDATE posts
        SET deleted_at = now(), deleted_reason = 'retention_window'
      WHERE id IN (
        SELECT id FROM posts
         WHERE deleted_at IS NULL
           AND created_at < now() - ($1::int * interval '1 day')
         ORDER BY created_at ASC
         LIMIT $2
      )
      RETURNING id`,
    [env.GOODPOST_HISTORY_DAYS, env.PURGE_BATCH_SIZE]
  );
  return rows.length;
}

/**
 * Stage 2 + 3: physically remove long-expired posts and their objects.
 *
 * The object keys are read inside the same transaction that deletes the rows,
 * so the set of keys to remove is exactly the set the delete removed. Doing the
 * SELECT first and the DELETE afterwards would leave a window in which a
 * concurrent publish could attach a key that this pass then deletes.
 *
 * Objects are removed AFTER the commit. That order is deliberate: a crash
 * between the two leaves unreferenced objects in the bucket, which costs a
 * little money and is fixable by a later pass, whereas removing objects first
 * and then failing to commit would leave rows pointing at nothing — a broken
 * post, which is the failure §25 and §34 both warn about.
 */
export async function purgeExpiredPosts(
  database: Queryable,
  store: ObjectStore
): Promise<{ purged: number; objectsRemoved: number; objectsFailed: number }> {
  const collected = await database.transaction(async (tx) => {
    const rows = await tx.query<{ id: string }>(
      `SELECT id FROM posts
        WHERE deleted_at IS NOT NULL
          AND deleted_at < now() - ($1::int * interval '1 day')
        ORDER BY deleted_at ASC
        LIMIT $2
        FOR UPDATE SKIP LOCKED`,
      [env.PURGE_GRACE_DAYS, env.PURGE_BATCH_SIZE]
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

  if (collected.postIds.length === 0) {
    return { purged: 0, objectsRemoved: 0, objectsFailed: 0 };
  }

  if (!store.configured) {
    // The rows are gone either way; only the bucket objects are left behind.
    // Reported once per pass rather than once per object, and NOT counted as
    // failures — an unconfigured deployment is a known state, not an error.
    console.warn(
      `[retention] purged ${collected.postIds.length} post(s) but left ${collected.keys.length} object(s) in place: no object storage is configured`
    );
    return { purged: collected.postIds.length, objectsRemoved: 0, objectsFailed: 0 };
  }

  let removed = 0;
  let failed = 0;

  for (const key of collected.keys) {
    // §34's reference count. The rows that named this key were just deleted, so
    // this is normally zero — but it is ASKED rather than assumed.
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

  return { purged: collected.postIds.length, objectsRemoved: removed, objectsFailed: failed };
}

/**
 * One full retention pass.
 *
 * Child posts of a channel are removed by cascade when the channel row goes, so
 * there is no separate channel pass here; what this returns is what actually
 * happened, for the job to log without inventing numbers.
 */
export async function runRetentionPass(
  database: Queryable,
  store: ObjectStore
): Promise<RetentionReport> {
  const expired = await expireOldPosts(database);
  const { purged, objectsRemoved, objectsFailed } = await purgeExpiredPosts(database, store);

  return { expired, purged, objectsRemoved, objectsFailed };
}
