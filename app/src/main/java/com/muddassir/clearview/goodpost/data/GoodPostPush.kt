package com.muddassir.clearview.goodpost.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Instant delivery, through Firebase Cloud Messaging (§8, §12).
 *
 * ## What changed, and what did not
 *
 * Channel updates used to be found by the app itself: a WorkManager job asked
 * the backend what the reader's channels had published, every fifteen minutes,
 * and raised a local notification for anything new. Fifteen minutes is the floor
 * WorkManager offers, and it is the wrong floor for the feeling this is going
 * for — WhatsApp tells you when a channel posts, and it does it in seconds.
 *
 * So the SERVER now sends. What did not change is where the notification is
 * built and decided: still [GoodPostNotifications], still on this device, still
 * against the same "have I already announced this channel's latest publish"
 * watermark. The push carries no text for the system to draw (see the backend's
 * sender), so a message arriving while the reader has notifications switched off,
 * or from a channel they muted, is dropped by the same code that has always
 * dropped it — and a push that lands twice announces once.
 *
 * ## Two jobs, one function
 *
 * [sync] is the whole public surface: read the switch, make the server agree.
 * Called when the switch is flipped, and once per app start, because a token can
 * rotate while the app is closed and the server has no way to notice.
 *
 * ## What this deliberately tolerates
 *
 * A failure to register is not shown to anybody. The reader asked for
 * notifications and got the switch they asked for; whether the token reached the
 * server is a delivery detail, and the periodic check — which stays, as the
 * catch-up path — is what covers a device that never registered. The alternative,
 * refusing to let the switch move because a request failed, would be a switch
 * that lies about what it did.
 */
internal object GoodPostPush {

    private const val TAG = "GoodPostPush"

    /**
     * Make the server's idea of this device match the reader's switch.
     *
     * On: get the token (Firebase's, cached by the SDK after the first call) and
     * register it. Off: unregister it, so a phone that has stopped wanting
     * notifications is not a row the fan-out pays for on every publish.
     *
     * Safe to call at any time, and safe to call twice: registration is an
     * upsert keyed on the token, and a duplicate is one row.
     */
    suspend fun sync(context: Context) {
        val app = context.applicationContext
        val repository = GoodPostRepository(app)

        if (!GoodPostNotifications.isEnabled(app)) {
            // The token is read from DISK as well as from memory, and that is the
            // part that matters: a reader who turns the switch off after a restart
            // would otherwise have no token to name, the row would survive, and
            // the server would keep paying for a phone that no longer listens on
            // every publish. Delivery was already being dropped on arrival —
            // [pushDecisionOf] is asked at the moment of delivery — but "not
            // shown" and "not sent" are different bills.
            val token = cachedToken ?: registeredToken(app) ?: return
            cachedToken = null
            // The stored copy is dropped once the server has given an answer,
            // and kept only when there was none: an unreachable server is a
            // request to retry on the next start, whereas a refusal means this
            // token is never going to be unregistered and asking again would be a
            // request per launch forever.
            when (repository.unregisterDevice(token)) {
                is ApiResult.Ok,
                is ApiResult.Failed -> forgetRegisteredToken(app)

                ApiResult.Unreachable -> Log.d(TAG, "unregistration did not reach the server")
            }
            return
        }

        val token = token() ?: return
        when (val result = repository.registerDevice(token)) {
            is ApiResult.Ok -> {
                cachedToken = token
                rememberRegisteredToken(app, token)
            }

            is ApiResult.Failed -> Log.d(TAG, "registration refused: ${result.code}")
            // Unreachable: the next app start tries again, and the periodic check
            // delivers in the meantime.
            ApiResult.Unreachable -> Log.d(TAG, "registration did not reach the server")
        }
    }

    private const val PREFS = "goodpost_push"
    private const val KEY_REGISTERED = "registered_token"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The token this install last told the server about, if any. */
    private fun registeredToken(context: Context): String? =
        prefs(context).getString(KEY_REGISTERED, null)?.takeIf { it.isNotBlank() }

    private fun rememberRegisteredToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_REGISTERED, token).apply()
    }

    private fun forgetRegisteredToken(context: Context) {
        prefs(context).edit().remove(KEY_REGISTERED).apply()
    }

    /**
     * This install's FCM token.
     *
     * Held in memory for the length of the process, because the two callers are
     * "the switch was flipped" and "a message arrived for a token we no longer
     * want" — and the second can only unregister what the first registered.
     * Nothing is written to disk: a token is a routing address the SDK will hand
     * back at any time, and a stored copy is one more thing that can disagree
     * with Firebase about which token is current.
     */
    @Volatile
    private var cachedToken: String? = null

    /** The current token, or null when Firebase cannot be reached. */
    suspend fun token(): String? = try {
        FirebaseMessaging.getInstance().token.await()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        // No network, no Firebase project, a build without google-services.json:
        // every one of them means "no push", and none of them is a crash.
        Log.d(TAG, "no push token: ${e.javaClass.simpleName}")
        null
    }

    /** Remember the token an [onNewToken] callback was handed. */
    fun rememberToken(token: String) {
        cachedToken = token
    }

    /** The SDK's Task, as a coroutine — the shape both Firebase calls need. */
    private suspend fun <T> Task<T>.await(): T? =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { continuation.resume(it) }
            addOnFailureListener { continuation.resumeWithException(it) }
        }
}

/**
 * Whether an arriving update should be announced (§8).
 *
 * A pure function, and unit-tested as one, because this is the rule that decides
 * whether a reader is buzzed: everything behind it — the switch, the payload, the
 * notification — is plumbing, and this is the only place the answer can be wrong
 * in a way a reader would notice.
 *
 * The four ways an update is dropped:
 *
 *  * **Notifications are off.** The reader's switch, checked at the moment of
 *    delivery rather than at the moment the message was queued: a switch turned
 *    off while a message was in flight still wins.
 *  * **The payload names no publish time.** Without one there is nothing to
 *    compare against the watermark, and announcing it could buzz for something
 *    weeks old.
 *  * **This channel has already been announced at or after this publish.** The
 *    duplicate rule. It is also what makes push and the catch-up check
 *    interchangeable: whichever arrives first sets the watermark, and the other
 *    finds nothing new.
 *  * **A channel's own mute** is already applied by the server before sending, so
 *    it is not repeated here — but the master switch is, because a message can
 *    arrive after the switch was flipped off.
 */
internal enum class PushDecision { Announce, IgnoreDisabled, IgnoreAlreadySeen, IgnoreUnusable }

internal fun pushDecisionOf(
    notificationsEnabled: Boolean,
    /** The post's own publish time, epoch millis, or null when unparseable. */
    publishedAt: Long?,
    /** The newest publish already announced for this channel. */
    lastSeenAt: Long
): PushDecision = when {
    !notificationsEnabled -> PushDecision.IgnoreDisabled
    publishedAt == null -> PushDecision.IgnoreUnusable
    publishedAt <= lastSeenAt -> PushDecision.IgnoreAlreadySeen
    else -> PushDecision.Announce
}
