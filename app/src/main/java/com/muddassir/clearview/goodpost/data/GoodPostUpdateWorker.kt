package com.muddassir.clearview.goodpost.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The background check behind Good Post's notifications (§8, §24).
 *
 * ## What it asks, and why that is the whole cost
 *
 * One request: the channels this reader follows, each with the time it last
 * published. That is the reader's own list — an indexed lookup over a handful of
 * rows — and it is the only network traffic this feature generates. There is no
 * per-post fetch, no media download and no socket: a notification says WHO
 * published and shows the first line of what they said, both of which the
 * follows payload already carries.
 *
 * ## The noisiest thing it must not do
 *
 * Announce the past. The first run after the switch is turned on adopts every
 * channel's current publish time without notifying, so enabling Good Post
 * notifications puts nothing in the shade — from then on, only updates published
 * AFTER that moment are announced. A channel added later gets the same treatment
 * the first time it is seen.
 *
 * ## Failure is cheap by design
 *
 * A refused or unreachable answer returns success, not retry: the next tick is
 * fifteen minutes away, and a WorkManager backoff on a blip would run the same
 * request several times in the window that matters least. Nothing was announced,
 * so nothing is missed once the network is back — the timestamps it compares
 * against are publish times, not "seen" flags.
 */
class GoodPostUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!GoodPostNotifications.isEnabled(context)) return Result.success()

        val repository = GoodPostRepository(context)
        if (!repository.isConfigured) return Result.success()

        val follows = repository.following()
        if (follows !is ApiResult.Ok) return Result.success()

        follows.value.items.forEach { channel ->
            val published = parseIsoMillis(channel.lastPostAt) ?: return@forEach
            val seen = GoodPostNotifications.lastSeen(context, channel.id)

            // First sighting: adopt it, announce nothing. See the class comment.
            if (seen == 0L) {
                GoodPostNotifications.setLastSeen(context, channel.id, published)
                return@forEach
            }
            if (published <= seen) return@forEach

            // Recorded before the notification is attempted, so a muted channel
            // does not keep re-announcing the same post once it is unmuted.
            GoodPostNotifications.setLastSeen(context, channel.id, published)
            if (channel.notificationsMuted) return@forEach

            GoodPostNotifications.notifyUpdate(
                context = context,
                channelId = channel.id,
                channelName = channel.name,
                slug = channel.slug,
                body = channel.lastPostPreview
            )
        }

        return Result.success()
    }
}

/**
 * Schedules [GoodPostUpdateWorker].
 *
 * Fifteen minutes, and that number is not a preference: it is the shortest
 * periodic interval WorkManager will accept, and a shorter one asked for is
 * silently rounded up to it. It is also the interval a channel update deserves —
 * this is not a chat, and a reader who wants to know immediately opens the tab,
 * which polls on its own while they are looking at it.
 *
 * The work stays scheduled while the master switch is off, and the worker reads
 * the switch and returns. That is deliberate: cancelling and re-enqueueing on
 * every flip would lose the periodic slot each time, so turning notifications
 * back on would wait up to fifteen minutes before anything could arrive.
 */
object GoodPostUpdateScheduler {

    private const val PERIODIC_WORK = "goodpost_updates_periodic"
    private const val CHECK_NOW_WORK = "goodpost_updates_check_now"

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Enqueues the periodic check (idempotent). Called once at app startup. */
    fun ensureScheduled(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GoodPostUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .build()
        )
    }

    /**
     * Runs one check right now — used when the switch is turned on, so a reader
     * who has just enabled it does not have to wait for the first tick.
     *
     * It will announce nothing (the first run only adopts timestamps); what it
     * buys is that the SECOND check is already counting from now rather than
     * from fifteen minutes from now.
     */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CHECK_NOW_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<GoodPostUpdateWorker>()
                .setConstraints(networkConstraints)
                .build()
        )
    }
}
