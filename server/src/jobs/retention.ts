import cron from 'node-cron';
import { db, type Queryable } from '../db.js';
import { env } from '../env.js';
import { createObjectStore, type ObjectStore } from '../media/store.js';
import { sweepAbandonedUploads } from '../media/service.js';
import { expireOldPosts, purgeExpiredPosts } from '../retention/purge.js';

/**
 * The scheduled cleanup (§11, §34).
 *
 * Two passes share this schedule, and they are deliberately different:
 *
 *  1. **Abandoned uploads** — a composer session that asked for an upload URL
 *     and never published leaves an object in the bucket, with no row's
 *     lifecycle pointing at it. Nothing else would ever collect it.
 *
 *  2. **Post-history retention** — posts older than `GOODPOST_HISTORY_DAYS`
 *     stop being readable, and are physically removed only after
 *     `PURGE_GRACE_DAYS`, with their objects deleted by reference count rather
 *     than by row so an object another row still names is never removed (§34).
 *
 * Two rules the sweep honours by construction:
 *
 *  * It prunes SERVER-SIDE data only. Media a reader has already seen is their
 *    device's business; the server has no handle on it.
 *  * It never deletes an object another row still references (§34), which is why
 *    the abandoned-upload pass only selects rows with `post_id IS NULL`: a
 *    claimed object belongs to a post and is not its to remove.
 */
export function startRetentionJob(
  database: Queryable = db,
  store: ObjectStore = createObjectStore()
): void {
  const task = cron.schedule(env.RETENTION_CRON, () => {
    // Not awaited: the cron callback must not hold a tick open, and the sweep
    // reports its own failures.
    void runRetentionSweep(database, store);
  });

  console.log(
    `[retention] scheduled "${env.RETENTION_CRON}" ` +
      `(history window ${env.GOODPOST_HISTORY_DAYS}d, purge grace ${env.PURGE_GRACE_DAYS}d, ` +
      `abandoned uploads after ${env.UPLOAD_CLAIM_WINDOW_MINUTES}m, batch ${env.PURGE_BATCH_SIZE})`
  );

  // Cron tasks hold the event loop open; unref so a SIGTERM shutdown is not
  // blocked by a pending tick.
  (task as unknown as { unref?: () => void }).unref?.();
}

/**
 * One tick. Never throws: a failed sweep must not take the process down, and
 * the next tick retries whatever was left behind.
 */
export interface SweepResult {
  /**
   * Mutable by design: one tick is several independent passes, each of which may
   * fail without stopping the others, so the result is accumulated pass by pass
   * rather than assembled at the end. Guarding each pass separately is what
   * keeps an unreachable bucket from stopping the database-side retention §11
   * actually requires.
   */
  removed: number;
  failed: number;
  /** Posts that stopped being readable this tick (§11's window). */
  expired: number;
  /** Posts physically deleted this tick (§11's grace period). */
  purged: number;
  objectsRemoved: number;
}

export async function runRetentionSweep(
  database: Queryable = db,
  store: ObjectStore = createObjectStore()
): Promise<SweepResult> {
  const result: SweepResult = {
    removed: 0,
    failed: 0,
    expired: 0,
    purged: 0,
    objectsRemoved: 0,
  };

  // Each pass is guarded separately: a bucket that is briefly unreachable must
  // not stop the database-side retention, which is the part §11 actually
  // requires. A failure in one is logged and the next tick retries it.
  try {
    const uploads = await sweepAbandonedUploads(
      database,
      store,
      env.UPLOAD_CLAIM_WINDOW_MINUTES
    );
    if (uploads.removed > 0 || uploads.failed > 0) {
      console.log(
        `[retention] abandoned uploads: removed ${uploads.removed}, failed ${uploads.failed}`
      );
    }
    Object.assign(result, { removed: uploads.removed, failed: uploads.failed });
  } catch (err) {
    console.error('[retention] abandoned-upload sweep failed:', (err as Error).message);
  }

  try {
    result.expired = await expireOldPosts(database);
    if (result.expired > 0) {
      console.log(`[retention] expired ${result.expired} post(s) past the history window`);
    }
  } catch (err) {
    console.error('[retention] history-window pass failed:', (err as Error).message);
  }

  try {
    const purged = await purgeExpiredPosts(database, store);
    result.purged = purged.purged;
    result.objectsRemoved = purged.objectsRemoved;
    if (purged.purged > 0) {
      console.log(
        `[retention] purged ${purged.purged} post(s), removed ${purged.objectsRemoved} object(s), ${purged.objectsFailed} failed`
      );
    }
  } catch (err) {
    console.error('[retention] purge pass failed:', (err as Error).message);
  }

  return result;
}
