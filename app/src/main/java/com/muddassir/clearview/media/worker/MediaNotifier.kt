package com.muddassir.clearview.media.worker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R
import com.muddassir.clearview.media.model.MediaChannelUpdate

/**
 * Posts the media (channel-update) notifications: one "… has an update"
 * notification per channel that uploaded something new. There is deliberately
 * NO group summary ("N channels have updates") — each update stands on its own
 * in the shade, so a burst of uploads shows one notification per channel, not
 * an extra generic one. Tapping one opens the app (the home page shows the
 * Latest Updates feed with the same channels).
 */
object MediaNotifier {

    const val CHANNEL_ID = "media_updates"
    // Id of the group-summary notification OLD builds posted ("N channels have
    // updates"). No new summary is ever posted, but this id is still cancelled
    // on app-close of the update feed so a leftover from a previous version
    // doesn't linger in the shade.
    private const val SUMMARY_ID = 0

    /** Idempotent — creates the notification channel on first use. */
    fun ensureChannel(context: Context) {
        // Channels only exist on API 26+; below that the platform ignores the
        // channel id entirely, so never touch the channel APIs there (calling
        // them on API 24/25 would crash with a verification error).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.media_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.media_notification_channel_desc)
                }
            )
        }
    }

    /**
     * Posts one standalone notification per channel in [updates] — no group
     * summary. Returns the number of channels notified (0 when notifications
     * are disabled by the system — e.g. the user denied the permission).
     *
     * The notify() calls below are guarded at runtime by areNotificationsEnabled()
     * (false on Android 13+ whenever POST_NOTIFICATIONS is not granted), so the
     * MissingPermission lint error is a false positive.
     */
    @SuppressLint("MissingPermission")
    fun notifyUpdates(context: Context, updates: List<MediaChannelUpdate>): Int {
        if (updates.isEmpty()) return 0
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        // The worker checks our own toggle, but the OS-level permission (13+)
        // can still be off — silently skip instead of throwing.
        if (!manager.areNotificationsEnabled()) return 0

        // A notification the user already swiped away must never be re-posted,
        // duplicated or stacked by a later run. The dismissal is keyed by
        // (channel, video), so a genuinely NEW upload still notifies.
        val postable = updates.filterNot { update ->
            NotificationStateStore.isDismissed(context, update.channelId, update.latestVideoId)
        }
        // Do not cap by platform or source: every saved channel update is
        // posted. Updates are already collapsed to one newest item per channel
        // by the worker, so this cannot duplicate a channel in one run.
        postable.forEach { update ->
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_clearview)
                .setContentTitle(
                    context.getString(R.string.media_has_update, update.channelName)
                )
                .setContentText(update.latestVideoTitle)
                .setContentIntent(openAppIntent(context, update.channelId))
                // Swiping the notification away records the dismissal so it can
                // never come back.
                .setDeleteIntent(dismissIntent(context, update.channelId, update.latestVideoId))
                .setAutoCancel(true)
                .build()
            // Deterministic per-channel id: dismissing an update in the app can
            // cancel exactly the notification that was posted for it. The TAG
            // is the channel id — invisible in the shade, but it is what lets
            // [cancelChannelNotifications] sweep every notification a channel
            // posted when the user mutes it (the numeric id is per post, so a
            // channel that published three times owns three of them).
            manager.notify(
                update.channelId,
                postNotificationId(update.channelId, update.latestVideoId),
                notification
            )
        }
        return postable.size
    }

    /**
     * Delete intent attached to every update notification: fires
     * [NotificationDismissReceiver] when the user swipes the notification out
     * of the shade, recording the dismissal in [NotificationStateStore].
     */
    private fun dismissIntent(
        context: Context,
        channelId: String,
        videoId: String
    ): PendingIntent {
        val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
            action = NotificationDismissReceiver.ACTION_DISMISS
            putExtra(NotificationDismissReceiver.EXTRA_CHANNEL_ID, channelId)
            putExtra(NotificationDismissReceiver.EXTRA_VIDEO_ID, videoId)
        }
        return PendingIntent.getBroadcast(
            context,
            "dismiss-$channelId|$videoId".hashCode() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Opens the app from a notification; a fresh task if it isn't running. */
    private fun openAppIntent(context: Context, channelId: String): PendingIntent {
        val intent = Intent(context, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // Per-channel request codes keep the PendingIntents distinct even
        // though they all just open the app.
        return PendingIntent.getActivity(
            context,
            channelId.hashCode() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Cancels the notification posted for [channelId] (called when the user
     * dismisses that channel's update in the app — the shade and the launcher
     * badge follow the in-app feed).
     */
    fun cancelChannelNotification(context: Context, channelId: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(channelNotificationId(channelId))
    }

    fun cancelPostNotification(context: Context, channelId: String, videoId: String) {
        NotificationManagerCompat.from(context)
            .cancel(channelId, postNotificationId(channelId, videoId))
    }

    /**
     * Removes EVERY update notification [channelId] has in the shade.
     *
     * Called when the user mutes a channel: leaving already-posted "… has an
     * update" lines behind after they asked for silence is the exact behaviour
     * the toggle exists to prevent. Notifications are posted under the channel
     * id as their tag, so the channel's own notifications can be identified and
     * cancelled without touching any other channel's. The legacy untagged
     * per-channel id is cleared too, for a leftover from an older build.
     */
    fun cancelChannelNotifications(context: Context, channelId: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.activeNotifications
            .filter { it.tag == channelId }
            .forEach { posted ->
                runCatching { manager.cancel(posted.tag, posted.id) }
            }
        manager.cancel(channelNotificationId(channelId))
    }

    /**
     * Cancels any leftover "N channels have updates" summary notification that
     * an OLD build posted (new builds never post one). Called when the in-app
     * update feed is emptied, so a stale shade entry from a previous version
     * doesn't linger.
     */
    fun cancelSummary(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(SUMMARY_ID)
    }

    /** Stable per-post notification id, so one channel can show every update. */
    private fun postNotificationId(channelId: String, videoId: String): Int =
        ("$channelId|$videoId".hashCode() and 0x7FFFFFFF) % 100_000 + 1

    /** Legacy per-channel id retained for cancelling notifications from older builds. */
    private fun channelNotificationId(channelId: String): Int =
        (channelId.hashCode() and 0x7FFFFFFF) % 100_000 + 1
}
