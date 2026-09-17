package com.muddassir.clearview.goodpost.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R

/**
 * Receives Good Post channel notifications (§17).
 *
 * Notification-only, and deliberately so: the server writes the durable row
 * BEFORE it pushes, so this service's job is to ring the phone and nothing
 * else. If it never runs — the app is force-stopped, the device is offline, the
 * user denied the notification permission — the inbox is still correct, which
 * is the property that makes notifications a feature rather than a delivery
 * mechanism.
 *
 * It also does no network work of its own. `onNewToken` stores the token; the
 * registration call happens from the signed-in session, where there is a
 * session to register it against. A service that tried to POST here would have
 * to hold a session outside the app's own lifetime, which is how a background
 * component ends up with a credential it should not have.
 */
class GoodPostMessagingService : FirebaseMessagingService() {

    /**
     * A token was issued or rotated.
     *
     * Stored rather than registered: there may be no session yet, and a
     * background service is the wrong place to invent one.
     */
    override fun onNewToken(token: String) {
        GoodPostPushTokens(this).save(token)
    }

    /**
     * A message arrived.
     *
     * Reads the `data` payload first and falls back to the notification block:
     * a data-only message is what lets the app word and route its own
     * notification, and the server sends routing ids that the client already has
     * access to (§38) and no private field.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"] ?: message.notification?.title ?: return
        val body = message.data["body"] ?: message.notification?.body ?: ""
        val kind = message.data["kind"].orEmpty()

        // Never for a platform notice on a build with no session: the notice
        // itself is in the user's inbox, and the inbox is where it can be read
        // in context.
        if (title.isBlank()) return

        post(title, body, kind)
    }

    private fun post(title: String, body: String, kind: String) {
        val manager = NotificationManagerCompat.from(this)
        // Android 13+ drops the notification outright without this, so it is
        // checked rather than assumed — the inbox still holds the row.
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "notification skipped: notifications are disabled for this app")
            return
        }

        ensureChannel()

        val open = Intent(this, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_GOODPOST, true)
        }
        val pending = PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clearview)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        // One id per notification: a channel that publishes three times must
        // produce three entries, which collapsing them onto one id would not.
        // The server's dedupe key is what stops the same post arriving twice.
        val id = (kind + title + body).hashCode() and 0x7FFFFFFF
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Only reachable when the permission was revoked between the check
            // and the post. Logged, never fatal — this is not the user's thread.
            Log.w(TAG, "notification dropped: permission revoked mid-post")
        }
    }

    /** Idempotent: the channel is created once and never rebuilt. */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.goodpost_notification_channel),
                // Default rather than high: a channel post is not an alarm, and
                // a channel that rings like one is how notifications get turned
                // off for the whole app.
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.goodpost_notification_channel_desc)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "goodpost_updates"

        /** Read by the app to open the Good Post tab from a tapped notification. */
        const val EXTRA_OPEN_GOODPOST = "open_goodpost"

        private const val REQUEST_CODE = 0x0D01

        private const val TAG = "GoodPostPush"
    }
}
