import cron from 'node-cron';
import { db, type Queryable } from '../db.js';
import { env } from '../env.js';
import { createObjectStore, type ObjectStore } from '../media/store.js';
import { sweepAbandonedUploads } from '../media/service.js';

/**
 * The scheduled cleanup (§11, §34).
 *
 * Two jobs share this schedule and they are deliberately different:
 *
 *  1. **Abandoned uploads** — implemented here. A composer session that
 *     requested an upload URL and never published leaves an object in the
 *     bucket; nothing else will ever collect it, because no row's lifecycle
 *     points at it. This is the one leak M3 could create, so M3 closes it.
 *
 *  2. **Post-history retention** — still to come in M7. It prunes posts older
 *     than `GOODPOST_HISTORY_DAYS`, and it must remove their S3 objects by
 *     reference count rather than by row, so an object still named by another
 *     row is never deleted (§34). That ordering is the whole difficulty of it,
 *     which is why it is not being rushed in alongside this.
 *
 * Two rules the sweep honours by construction:
 *
 *  * It prunes SERVER-SIDE data only. Media a user has already downloaded lives
 *    on their device and is never touched (§10) — the server cannot and must
 *    not reach into it.
 *  * It never deletes an S3 object another row still references (§34), which is
 *    why the abandoned-upload sweep only ever selects rows with `post_id IS
 *    NULL`: a claimed object belongs to a post and is not its to remove.
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
      `(history window ${env.GOODPOST_HISTORY_DAYS}d, grace ${env.PURGE_GRACE_DAYS}d, ` +
      `abandoned uploads after ${env.UPLOAD_CLAIM_WINDOW_MINUTES}m)`
  );

  // Cron tasks hold the event loop open; unref so a SIGTERM shutdown is not
  // blocked by a pending tick.
  (task as unknown as { unref?: () => void }).unref?.();
}

/**
 * One tick. Never throws: a failed sweep must not take the process down, and
 * the next tick retries whatever was left behind.
 */
export async function runRetentionSweep(
  database: Queryable = db,
  store: ObjectStore = createObjectStore()
): Promise<{ readonly removed: number; readonly failed: number }> {
  try {
    const result = await sweepAbandonedUploads(
      database,
      store,
      env.UPLOAD_CLAIM_WINDOW_MINUTES
    );

    // Logged only when something happened, so a healthy deployment every 30
    // minutes does not fill the log with zeroes. Never logs an object key: a
    // key is a capability for anyone who also has a bucket credential.
    if (result.removed > 0 || result.failed > 0) {
      console.log(
        `[retention] abandoned uploads: removed ${result.removed}, failed ${result.failed}`
      );
    }
    return result;
  } catch (err) {
    console.error('[retention] sweep failed:', (err as Error).message);
    return { removed: 0, failed: 0 };
  }
}
