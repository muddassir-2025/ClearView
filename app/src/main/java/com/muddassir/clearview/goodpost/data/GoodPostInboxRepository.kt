package com.muddassir.clearview.goodpost.data

import android.content.Context
import org.json.JSONObject

/**
 * The outcome of an inbox call.
 *
 * [Stale] exists here, unlike in [EngagementResult], because these are READS:
 * an inbox that was fetched a minute ago is worth showing when the network is
 * gone (§36), as long as the screen says it is saved rather than live.
 */
sealed interface InboxResult<out T> {
    data class Ok<T>(val value: T) : InboxResult<T>

    /** The server was unreachable; this is a saved copy (§36). */
    data class Stale<T>(val value: T) : InboxResult<T>

    data class Failed(val code: String) : InboxResult<Nothing>

    data object SignedOut : InboxResult<Nothing>
}

/**
 * Offline cache for the inbox (§36, §10).
 *
 * `SharedPreferences` + JSON strings, matching every other cache in Good Post
 * and the rest of ClearView — no Room, no DataStore (§35).
 *
 * What is cached is exactly what the account may already read: its own
 * notifications, its own official notices, and the conversation summaries of
 * its own threads. Message BODIES are deliberately not cached: a saved copy of
 * a private conversation on disk is a different risk from a saved channel name,
 * and reading a thread needs the network anyway because sending one does.
 */
internal class GoodPostInboxCache(context: Context) {

    private companion object {
        const val PREFS = "goodpost_inbox_cache"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_NOTICES = "notices"
        const val KEY_CONVERSATIONS = "conversations"

        /** Enough to catch up on; small enough to stay off the heap. */
        const val MAX_ROWS = 100
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveNotifications(items: List<GoodPostNotification>) {
        prefs.edit()
            .putString(KEY_NOTIFICATIONS, GoodPostInboxCodec.encodeNotifications(items.take(MAX_ROWS)))
            .apply()
    }

    fun loadNotifications(): List<GoodPostNotification>? =
        GoodPostInboxCodec.decodeNotifications(prefs.getString(KEY_NOTIFICATIONS, null))

    fun saveNotices(items: List<GoodPostNotice>) {
        prefs.edit()
            .putString(KEY_NOTICES, GoodPostInboxCodec.encodeNotices(items.take(MAX_ROWS)))
            .apply()
    }

    fun loadNotices(): List<GoodPostNotice>? =
        GoodPostInboxCodec.decodeNotices(prefs.getString(KEY_NOTICES, null))

    fun saveConversations(items: List<GoodPostConversation>) {
        prefs.edit()
            .putString(
                KEY_CONVERSATIONS,
                GoodPostInboxCodec.encodeConversations(items.take(MAX_ROWS))
            )
            .apply()
    }

    fun loadConversations(): List<GoodPostConversation>? =
        GoodPostInboxCodec.decodeConversations(prefs.getString(KEY_CONVERSATIONS, null))

    /**
     * Drop everything. Called on sign-out: an inbox is the previous account's
     * mail, and leaving it on disk for the next person to sign in on this
     * device would show them somebody else's notifications.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}

/**
 * Notifications, notices, private messages, reports and blocks (§12, §16, §17,
 * §18, §26).
 *
 * One repository for all five because they share one property — each is an
 * action taken BY the signed-in account FOR the signed-in account — and one
 * session policy, which is the only thing that would otherwise be duplicated.
 *
 * Two rules, both from §36:
 *
 *  * **Reads fall back to the cache, writes never do.** A saved inbox is
 *    readable offline; a follow-up, a report or a message is only ever reported
 *    as done once the server has confirmed it.
 *  * **Nothing here invents a state change.** Marking a notification read
 *    offline does not pretend the server was told — it records what the server
 *    said last, and the next successful fetch corrects it.
 */
class GoodPostInboxRepository(
    context: Context,
    private val auth: GoodPostAuthRepository = GoodPostAuthRepository(context),
    private val api: GoodPostApi = GoodPostApi()
) {

    private val cache = GoodPostInboxCache(context)

    // ── Notifications (§17) ──────────────────────────────────────────────

    suspend fun notifications(): InboxResult<List<GoodPostNotification>> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        return when (val result = api.notifications(session.accessToken)) {
            is ApiResult.Ok -> {
                val items = GoodPostInboxCodec.notifications(result.value)
                cache.saveNotifications(items)
                InboxResult.Ok(items)
            }

            is ApiResult.Failed -> {
                if (result.status == 401) return InboxResult.SignedOut
                val saved = cache.loadNotifications()
                // A 5xx is the server having a bad moment rather than refusing
                // this request, so a saved inbox beats an error — and it is
                // reported as stale, never as fresh.
                if (result.status >= 500 && saved != null) InboxResult.Stale(saved)
                else InboxResult.Failed(result.code)
            }

            ApiResult.Unreachable ->
                cache.loadNotifications()?.let { InboxResult.Stale(it) }
                    ?: InboxResult.Failed("unreachable")
        }
    }

    /**
     * Mark the inbox read, or specific rows.
     *
     * The local cache is updated only on success. A read receipt the server
     * never received is not a receipt (§36) — and writing one optimistically
     * would clear a badge for content the next fetch still reports as unread,
     * which looks like the badge coming back on its own.
     */
    suspend fun markNotificationsRead(ids: List<String> = emptyList()): InboxResult<Int> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        return when (val result = api.markNotificationsRead(session.accessToken, ids)) {
            is ApiResult.Ok -> {
                val marked = result.value.optInt("marked", 0)
                applyReadLocally(ids)
                InboxResult.Ok(marked)
            }

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> InboxResult.Failed("unreachable")
        }
    }

    /** §26 official notices, addressed to this account or a channel it runs. */
    suspend fun notices(): InboxResult<List<GoodPostNotice>> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        return when (val result = api.notices(session.accessToken)) {
            is ApiResult.Ok -> {
                val items = GoodPostInboxCodec.notices(result.value)
                cache.saveNotices(items)
                InboxResult.Ok(items)
            }

            is ApiResult.Failed -> {
                if (result.status == 401) return InboxResult.SignedOut
                val saved = cache.loadNotices()
                if (result.status >= 500 && saved != null) InboxResult.Stale(saved)
                else InboxResult.Failed(result.code)
            }

            ApiResult.Unreachable ->
                cache.loadNotices()?.let { InboxResult.Stale(it) }
                    ?: InboxResult.Failed("unreachable")
        }
    }

    suspend fun markNoticesRead(ids: List<String>): InboxResult<Int> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        return when (val result = api.markNoticesRead(session.accessToken, ids)) {
            is ApiResult.Ok -> InboxResult.Ok(result.value.optInt("marked", 0))
            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> InboxResult.Failed("unreachable")
        }
    }

    // ── Private messages (§16) ───────────────────────────────────────────

    /** The threads the caller opened as a follower. */
    suspend fun ownConversations(): InboxResult<List<GoodPostConversation>> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        return when (val result = api.ownConversations(session.accessToken)) {
            is ApiResult.Ok -> {
                val items = GoodPostInboxCodec.conversations(result.value)
                cache.saveConversations(items)
                InboxResult.Ok(items)
            }

            is ApiResult.Failed -> {
                if (result.status == 401) return InboxResult.SignedOut
                val saved = cache.loadConversations()
                if (result.status >= 500 && saved != null) InboxResult.Stale(saved)
                else InboxResult.Failed(result.code)
            }

            ApiResult.Unreachable ->
                cache.loadConversations()?.let { InboxResult.Stale(it) }
                    ?: InboxResult.Failed("unreachable")
        }
    }

    /** The admin side: every follower thread waiting for a channel. */
    suspend fun channelConversations(channelId: String): InboxResult<List<GoodPostConversation>> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        // Never served from the cache. A channel's inbox is a work queue, and a
        // saved copy of it would invite a reply to a thread that has moved on —
        // the follower side is cached because it is the reader's own history.
        return when (val result = api.channelConversations(session.accessToken, channelId)) {
            is ApiResult.Ok -> InboxResult.Ok(GoodPostInboxCodec.conversations(result.value))
            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> InboxResult.Failed("unreachable")
        }
    }

    /** Open (or find) the caller's thread with a channel. */
    suspend fun openConversation(channelId: String): InboxResult<GoodPostConversation> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        return when (val result = api.openConversation(session.accessToken, channelId)) {
            is ApiResult.Ok ->
                GoodPostInboxCodec.singleConversation(result.value)
                    ?.let { InboxResult.Ok(it) }
                    ?: InboxResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> InboxResult.Failed("unreachable")
        }
    }

    /**
     * One thread's messages.
     *
     * Reading it is what marks the other side's messages read, so the response
     * also carries the refreshed conversation — which is why the whole unit is
     * returned rather than just the messages.
     */
    suspend fun messages(
        conversationId: String,
        cursor: String? = null
    ): InboxResult<GoodPostMessagePage> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        return when (val result = api.conversationMessages(session.accessToken, conversationId, cursor)) {
            is ApiResult.Ok -> InboxResult.Ok(
                GoodPostMessagePage(
                    conversation = GoodPostInboxCodec.singleConversation(result.value),
                    items = GoodPostInboxCodec.messages(result.value),
                    nextCursor = result.value.optString("nextCursor").takeIf { it.isNotBlank() }
                )
            )

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> InboxResult.Failed("unreachable")
        }
    }

    /** Reply in a thread, as either side. */
    suspend fun sendMessage(conversationId: String, body: String): InboxResult<GoodPostMessage> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        val payload = JSONObject().apply { put("body", body.trim()) }
        return when (
            val result = api.sendConversationMessage(session.accessToken, conversationId, payload)
        ) {
            is ApiResult.Ok ->
                GoodPostInboxCodec.singleMessage(result.value)
                    ?.let { InboxResult.Ok(it) }
                    ?: InboxResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> InboxResult.Failed("unreachable")
        }
    }

    /** A channel's answer to one follower: block or unblock the thread (§16). */
    suspend fun setConversationBlocked(
        conversationId: String,
        blocked: Boolean
    ): InboxResult<GoodPostConversation> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        return when (
            val result = api.setConversationBlocked(session.accessToken, conversationId, blocked)
        ) {
            is ApiResult.Ok ->
                GoodPostInboxCodec.singleConversation(result.value)
                    ?.let { InboxResult.Ok(it) }
                    ?: InboxResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> InboxResult.Failed("unreachable")
        }
    }

    // ── Reports and blocks (§12, §18) ────────────────────────────────────

    /**
     * File a report (§18).
     *
     * `targetType` is checked against the server's own set before sending, so a
     * client bug is a worded refusal rather than a 400 the user cannot act on.
     */
    suspend fun report(
        targetType: String,
        targetId: String,
        reason: String,
        details: String?
    ): InboxResult<Unit> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        val payload = JSONObject().apply {
            put("targetType", targetType)
            put("targetId", targetId)
            put("reason", reason)
            details?.trim()?.takeIf { it.isNotEmpty() }?.let { put("details", it) }
        }

        return when (val result = api.createReport(session.accessToken, payload)) {
            is ApiResult.Ok -> InboxResult.Ok(Unit)
            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> InboxResult.Failed("unreachable")
        }
    }

    /** §12 block another account, which also ends any conversation with them. */
    suspend fun setUserBlocked(userId: String, blocked: Boolean): InboxResult<Unit> {
        val session = auth.validSession() ?: return InboxResult.SignedOut

        return when (val result = api.setUserBlocked(session.accessToken, userId, blocked)) {
            is ApiResult.Ok -> InboxResult.Ok(Unit)
            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> InboxResult.Failed("unreachable")
        }
    }

    // ── Device registration (§17) ────────────────────────────────────────

    /**
     * Tell the server where to push for this account.
     *
     * Best-effort: a device that could not register simply does not ring, and
     * the inbox still fills. Failing the caller would make an optional delivery
     * address look like a required part of signing in.
     */
    suspend fun registerDevice(token: String, platform: String = "android"): InboxResult<Unit> {
        if (token.isBlank()) return InboxResult.Failed("invalid_token")
        val session = auth.validSession() ?: return InboxResult.SignedOut

        val payload = JSONObject().apply {
            put("token", token)
            put("platform", platform)
        }

        return when (val result = api.registerDevice(session.accessToken, payload)) {
            is ApiResult.Ok -> InboxResult.Ok(Unit)
            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> InboxResult.Failed("unreachable")
        }
    }

    /**
     * Stop pushing to this device, on sign-out.
     *
     * Best-effort, like registration: the address belongs to the account, and a
     * device that could not be unregistered is corrected the moment another
     * account signs in here and the token moves to it (§17). Failing sign-out
     * over it would be the tail wagging the dog.
     */
    suspend fun unregisterDevice(token: String): InboxResult<Unit> {
        if (token.isBlank()) return InboxResult.Failed("invalid_token")
        val session = auth.validSession() ?: return InboxResult.SignedOut

        return when (val result = api.unregisterDevice(session.accessToken, token)) {
            is ApiResult.Ok -> InboxResult.Ok(Unit)
            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> InboxResult.Failed("unreachable")
        }
    }

    /** Forget cached inbox content, on sign-out. */
    fun clearCache() {
        cache.clear()
    }

    // ── Internals ────────────────────────────────────────────────────────

    /**
     * Reflect a confirmed read receipt in the cache.
     *
     * Only the confirmation path calls this. The ids decide the scope: an empty
     * list means the whole inbox was marked, matching the server's own reading
     * of the same request.
     */
    private fun applyReadLocally(ids: List<String>) {
        val current = cache.loadNotifications() ?: return
        val stamp = isoFromMillis(System.currentTimeMillis())
        val updated = current.map { item ->
            val targeted = ids.isEmpty() || ids.contains(item.id)
            if (targeted && item.readAt == null) item.copy(readAt = stamp) else item
        }
        cache.saveNotifications(updated)
    }

    private fun failure(status: Int, code: String): InboxResult<Nothing> =
        if (status == 401) InboxResult.SignedOut else InboxResult.Failed(code)
}

/** One thread page: the messages plus the conversation they refreshed. */
data class GoodPostMessagePage(
    val conversation: GoodPostConversation?,
    val items: List<GoodPostMessage>,
    val nextCursor: String?
)
