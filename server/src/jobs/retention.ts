import cron from 'node-cron';
import { db, type Queryable } from '../db.js';
import { env } from '../env.js';
import { createObjectStore, type ObjectStore } from '../media/store.js';
import { sweepAbandonedUploads } from '../media/service.js';
import { purgeAgedPosts, purgeExpiredPosts } from '../retention/purge.js';

/**
 * The scheduled cleanup (§34).
 *
 * Three passes share this schedule, and they are deliberately different:
 *
 *  1. **Abandoned uploads** — a composer session that asked for an upload URL
 *     and never published leaves an object in the bucket, with no row's lifecycle
 *     pointing at it. Nothing else would ever collect it.
 *
 *  2. **Deleted posts** — a post whose `deleted_at` is set is already unreadable,
 *     and this removes the row and its objects for real once the grace period has
 *     passed (§34's reference count, so an object another row still names is never
 *     removed).
 *
 *  3. **Aged-out posts** — the retention window (§14). A post older than
 *     `POST_RETENTION_DAYS` is removed along with its media, whether or not anyone
 *     deleted it. This is the one pass that shortens a channel's history without
 *     anybody asking it to, which is why it is off at `POST_RETENTION_DAYS=0` and
 *     why it reports its own count.
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
      `(post history ${env.POST_RETENTION_DAYS}d, ` +
      `purge grace ${env.PURGE_GRACE_DAYS}d, ` +
      `abandoned uploads after ${env.UPLOAD_CLAIM_WINDOW_MINUTES}m, batch ${env.PURGE_BATCH_SIZE})`
  );

  // Said out loud at boot, because it is the one scheduled behaviour that
  // removes content nobody asked to remove. An operator reading the deploy log
  // should not have to infer it from the number above.
  if (env.POST_RETENTION_DAYS > 0) {
    console.log(
      `[retention] posts older than ${env.POST_RETENTION_DAYS} days are deleted from the server (§14). ` +
        'Set POST_RETENTION_DAYS=0 to keep a channel\u2019s history indefinitely.'
    );
  } else {
    console.log('[retention] POST_RETENTION_DAYS=0 — channel history is kept indefinitely.');
  }

  // Cron tasks hold the event loop open; unref so a SIGTERM shutdown is not
  // blocked by a pending tick.
  (task as unknown as { unref?: () => void }).unref?.();
}

/**
 * One tick. Never throws: a failed sweep must not take the process down, and the
 * next tick retries whatever was left behind.
 */
export interface SweepResult {
  /**
   * Mutable by design: one tick is several independent passes, each of which may
   * fail without stopping the others, so the result is accumulated pass by pass
   * rather than assembled at the end. Guarding each pass separately is what keeps
   * an unreachable bucket from stopping the database-side cleanup §34 requires.
   */
  removed: number;
  failed: number;
  /** Posts physically deleted this tick. */
  purged: number;
  objectsRemoved: number;
  /** Of [purged], how many left because the retention window closed (§14). */
  agedOut: number;
}

export async function runRetentionSweep(
  database: Queryable = db,
  store: ObjectStore = createObjectStore()
): Promise<SweepResult> {
  const result: SweepResult = {
    removed: 0,
    failed: 0,
    purged: 0,
    objectsRemoved: 0,
    agedOut: 0,
  };

  // Each pass is guarded separately: a bucket that is briefly unreachable must
  // not stop the database-side cleanup. A failure in one is logged and the next
  // tick retries it.
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
    const purged = await purgeExpiredPosts(database, store);
    result.purged = purged.purged;
    result.objectsRemoved = purged.objectsRemoved;
    if (purged.purged > 0) {
      console.log(
        `[retention] purged ${purged.purged} deleted post(s), removed ${purged.objectsRemoved} object(s), ${purged.objectsFailed} failed`
      );
    }
  } catch (err) {
    console.error('[retention] purge pass failed:', (err as Error).message);
  }

  try {
    const aged = await purgeAgedPosts(database, store);
    result.agedOut = aged.purged;
    result.purged += aged.purged;
    result.objectsRemoved += aged.objectsRemoved;
    if (aged.purged > 0) {
      console.log(
        `[retention] ${env.POST_RETENTION_DAYS}-day window: removed ${aged.purged} post(s), ${aged.objectsRemoved} object(s), ${aged.objectsFailed} failed`
      );
    }
  } catch (err) {
    console.error('[retention] retention pass failed:', (err as Error).message);
  }

  return result;
}
