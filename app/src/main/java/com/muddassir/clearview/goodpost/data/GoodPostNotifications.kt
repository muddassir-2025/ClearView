package com.muddassir.clearview.goodpost.data

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R

/**
 * Channel updates as notifications (§8, §17).
 *
 * ## What this is NOT
 *
 * There is no push service here. Nothing is delivered while the app is closed
 * *by a server*: a periodic background check asks the backend what the reader's
 * channels have published and posts a local notification for anything new. That
 * is a deliberate cost decision rather than a shortcut — a push service needs a
 * device token, a sender, a project and a bill, and the honest alternative costs
 * one small indexed request every fifteen minutes, which is also the shortest
 * interval WorkManager will run (see `GoodPostUpdateScheduler`).
 *
 * ## Two switches, and they mean different things
 *
 * * **The master switch** is this device's, stored here. Off means no Good Post
 *   notifications at all, whatever any channel is set to.
 * * **A channel's bell** is the reader's, stored on the SERVER (`notifications_muted`
 *   on the follow row), because it is a property of "I follow this channel" and
 *   it should survive a reinstall the way the follow itself does.
 *
 * A notification is posted only when both say yes, and the check is done where
 * the notification is about to be shown rather than where the switch is flipped,
 * so a switch turned off after a check was queued still wins.
 */
internal object GoodPostNotifications {

    /** One channel, so muting Good Post does not silence the rest of the app. */
    const val CHANNEL_ID = "goodpost_updates"

    private const val PREFS = "goodpost_notifications"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_SEEN = "last_seen_at"

    private const val NOTIFICATION_ID = 0x60D5

    /** Whether this device wants Good Post notifications. Off until asked for. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * The newest publish time already announced for one channel.
     *
     * The FIRST check after enabling writes every channel's current time here and
     * announces nothing at all: notifications for updates that were already
     * there before the reader asked for them would be a page of notifications
     * about the past, which is how a permission gets revoked.
     */
    fun lastSeen(context: Context, channelId: String): Long =
        prefs(context).getLong("$KEY_LAST_SEEN.$channelId", 0L)

    fun setLastSeen(context: Context, channelId: String, at: Long) {
        prefs(context).edit().putLong("$KEY_LAST_SEEN.$channelId", at).apply()
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Post one channel's update.
     *
     * `channelSlug` travels as the app's own deep link rather than the https
     * share link: a tap has to land inside the app on that channel, and the
     * share link's job is to open a page for somebody who does not have it. The
     * activity already handles this scheme (`MainActivity`'s share-link path), so
     * a notification and a shared link arrive at the same screen.
     */
    @SuppressLint("MissingPermission")
    fun notifyUpdate(
        context: Context,
        channelId: String,
        channelName: String,
        slug: String,
        body: String?
    ) {
        if (!isEnabled(context)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)

        val intent = Intent(context, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("clearview://goodpost/channel/$slug")
        }
        val pending = PendingIntent.getActivity(
            context,
            channelId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = body?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.goodpost_posted_something)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clearview)
            .setContentTitle(channelName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        // One id for every channel, so a reader with ten channels gets one line
        // rather than ten: the newest update is the one worth a look, and Android
        // would collapse them into a group anyway.
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
                    context.getString(R.string.goodpost_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.goodpost_notification_channel_desc)
                }
            )
        }
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
