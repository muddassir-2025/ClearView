package com.muddassir.clearview.goodpost.data

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.muddassir.clearview.R

/**
 * An update arriving from the server, right now (§8, §12).
 *
 * ## The other half of the notification path
 *
 * [GoodPostUpdateWorker] asks the backend what has changed every fifteen minutes.
 * This receives the same news the moment it happens, sent by the deployment when
 * a channel publishes (see the backend's `announcePost`). Both end in the same
 * two calls — [GoodPostNotifications.lastSeen] and
 * [GoodPostNotifications.notifyUpdate] — which is what makes them interchangeable:
 * whichever arrives first sets the watermark, and the other finds nothing new. A
 * reader with push working gets the notification in seconds; a reader whose phone
 * refused to register a token, or whose message was delayed by Doze, gets it at
 * the next check. Neither gets it twice.
 *
 * ## Why the message carries no text for the system to draw
 *
 * The push is data-only, so nothing appears unless this code decides it should.
 * That is deliberate: the reader's master switch, the channel's mute and the
 * \"already announced this one\" rule all live in this app, and a notification
 * drawn by the system from the payload would bypass every one of them. A message
 * that arrives after the switch was turned off is dropped here, by the same
 * function that has always dropped it.
 *
 * ## What it does not do
 *
 * It does not fetch anything. The payload already carries the channel's name, its
 * slug and the first line of what it published, so an arrival costs no request —
 * which is the difference between push that is cheap and push that is a wake-up
 * followed by four reads.
 */
class GoodPostMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        // One project can carry more than one feature's messages. Anything that
        // is not a Good Post publish is not this class's business.
        if (data[KEY_TYPE] != TYPE_POST) return

        val channelId = data[KEY_CHANNEL_ID]?.takeIf { it.isNotBlank() } ?: return
        val publishedAt = parseIsoMillis(data[KEY_PUBLISHED_AT]) ?: return

        val unseen = GoodPostNotifications.lastSeen(this, channelId) == 0L
        if (unseen) {
            // First news of this channel on this device. Adopted, announced
            // nothing: a reader who has just turned notifications on should not be
            // told about everything that was already there. The periodic check
            // does the same thing for the same reason.
            GoodPostNotifications.setLastSeen(this, channelId, publishedAt)
            return
        }

        when (
            pushDecisionOf(
                notificationsEnabled = GoodPostNotifications.isEnabled(this),
                publishedAt = publishedAt,
                lastSeenAt = GoodPostNotifications.lastSeen(this, channelId)
            )
        ) {
            PushDecision.Announce -> Unit

            PushDecision.IgnoreDisabled,
            PushDecision.IgnoreAlreadySeen,
            PushDecision.IgnoreUnusable -> return
        }

        // Recorded BEFORE the notification is attempted, so a delivery that cannot
        // be drawn — notifications disabled at the system level, a channel the
        // reader switched off — does not re-announce the same post on the next
        // arrival.
        GoodPostNotifications.setLastSeen(this, channelId, publishedAt)

        GoodPostNotifications.notifyUpdate(
            context = this,
            channelId = channelId,
            channelName = data[KEY_CHANNEL_NAME].orEmpty().ifBlank { getString(R.string.goodpost_channels) },
            // The slug is what a tap opens. Without one there is nothing to open,
            // and the notification is still better than silence for a reader who
            // asked to be told.
            slug = data[KEY_CHANNEL_SLUG].orEmpty().ifBlank { channelId },
            body = data[KEY_PREVIEW]
        )
    }

    /**
     * Firebase rotated this install's routing address.
     *
     * The token is what the server addresses, so a rotation that is not reported
     * means this phone stops receiving — silently, and until the next app start.
     * It is re-registered here if the reader still wants notifications, and
     * deliberately NOT if they do not: an unregistered device should stay
     * unregistered even when Firebase decides to rename it.
     */
    override fun onNewToken(token: String) {
        GoodPostPush.rememberToken(token)
        if (!GoodPostNotifications.isEnabled(this)) return
        // No coroutine scope of this class's own: this is a short, best-effort
        // hand-off whose failure costs nothing but the periodic check's latency.
        // `sync` reads the switch and the token itself, so the argument is unused
        // beyond giving it an application context.
        kotlinx.coroutines.runBlocking { GoodPostPush.sync(applicationContext) }
    }

    private companion object {
        const val KEY_TYPE = "type"
        const val KEY_CHANNEL_ID = "channelId"
        const val KEY_CHANNEL_SLUG = "channelSlug"
        const val KEY_CHANNEL_NAME = "channelName"
        const val KEY_PUBLISHED_AT = "publishedAt"
        const val KEY_PREVIEW = "preview"
        const val TYPE_POST = "goodpost.post"
    }
}
