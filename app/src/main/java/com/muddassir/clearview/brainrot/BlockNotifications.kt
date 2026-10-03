package com.muddassir.clearview.brainrot

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

/**
 * The SYSTEM notification a block produces — the one that shows up in the
 * status bar, not just the in-app bell.
 *
 * A personal block made on the user's behalf (most notably from YouTube's
 * "Not interested" action, which ClearView turns into a channel block) has to be
 * visible outside the app: the user made a decision in YouTube, and the receipt
 * for what ClearView did with it belongs where every other update lands. The
 * in-app [NotificationStore] remains the full history; this is the heads-up.
 *
 * Its own channel ("clearview_blocking") so muting it never silences Good Post
 * or anything else, and vice versa. Mirrors [com.muddassir.clearview.goodpost.data.GoodPostNotifications].
 */
internal object BlockNotifications {

    const val CHANNEL_ID = "clearview_blocking"

    /** One id, so the newest block is the line shown rather than a stack. */
    private const val NOTIFICATION_ID = 0x810C

    /**
     * Post a block notification. Never throws: a notification that cannot be
     * shown (permission revoked, notifications disabled) must not break the
     * block itself, which has already happened by the time this is called.
     */
    @SuppressLint("MissingPermission")
    fun notifyBlocked(context: Context, title: String, body: String) {
        val manager = NotificationManagerCompat.from(context)
        // Android 13+ without POST_NOTIFICATIONS (or notifications switched off)
        // drops the notification; the block is unaffected.
        if (!manager.areNotificationsEnabled()) return

        ensureChannel(context)

        val intent = Intent(context, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clearview)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** Idempotent — creates the notification channel on first use. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.block_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.block_notification_channel_desc)
                }
            )
        }
    }
}
