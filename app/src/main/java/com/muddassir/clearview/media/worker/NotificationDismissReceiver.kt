package com.muddassir.clearview.media.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by the system via a notification's `setDeleteIntent` whenever the user
 * SWIPES a media notification out of the tray. Records the exact notification
 * identity in [NotificationStateStore] as DISMISSED so the next worker run /
 * app open can never re-post it, duplicate it, or let dismissed updates stack
 * back up in the shade.
 *
 * Exported=false: the delete intent is the app's own explicit PendingIntent.
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: return
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return
        NotificationStateStore.markDismissed(context, channelId, videoId)
        // The system already removed the notification; cancel() is idempotent
        // and also clears the deterministic per-channel id should a stale copy
        // still be present.
        MediaNotifier.cancelPostNotification(context, channelId, videoId)
    }

    companion object {
        const val ACTION_DISMISS = "com.muddassir.clearview.media.NOTIFICATION_DISMISSED"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_VIDEO_ID = "video_id"
    }
}
