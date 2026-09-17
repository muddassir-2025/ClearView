package com.muddassir.clearview.goodpost.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The three things that arrive FOR the user rather than from them: channel
 * notifications (§17), official platform notices (§26) and private follower
 * messages (§16).
 *
 * They are modelled together because they share one screen and one property:
 * every payload here belongs to the signed-in account and to nobody else.
 * Nothing in this file has a field for another person's identity — a
 * conversation names the channel, and the follower on the other side is
 * described only by the public profile the server chose to send (§38).
 */

/**
 * One durable notification (§17).
 *
 * [kind] is what the client switches on to decide where a tap goes; it is
 * deliberately an enum-backed string rather than free text, because a tap
 * target derived from prose is a guess.
 */
data class GoodPostNotification(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val channelId: String?,
    val postId: String?,
    val createdAt: String,
    val readAt: String?
) {
    val isUnread: Boolean get() = readAt == null

    val createdAtMs: Long? get() = parseIsoMillis(createdAt)
}

/** One official platform notice (§26), as its recipient sees it. */
data class GoodPostNotice(
    val id: String,
    val subject: String,
    val body: String,
    val createdAt: String,
    val readAt: String?,
    val aboutChannelId: String?
) {
    val isUnread: Boolean get() = readAt == null

    val createdAtMs: Long? get() = parseIsoMillis(createdAt)
}

/**
 * A private conversation between one follower and one channel (§16).
 *
 * [followerDisplayName] is the follower's public name. It is shown to the
 * CHANNEL side only, where knowing who wrote is the point of the inbox; a
 * follower reading their own thread sees the channel's name and their own
 * messages, and the server does not send them anyone else's name.
 */
data class GoodPostConversation(
    val id: String,
    val channelId: String,
    val channelName: String,
    val channelSlug: String,
    val followerId: String?,
    val followerDisplayName: String?,
    val lastMessageAt: String?,
    val lastMessagePreview: String?,
    val blocked: Boolean,
    val closed: Boolean,
    val unreadCount: Int
) {
    val lastMessageAtMs: Long? get() = parseIsoMillis(lastMessageAt)

    val hasUnread: Boolean get() = unreadCount > 0

    /** True while the thread can still take a reply. */
    val isOpen: Boolean get() = !closed && !blocked
}

/** One message in a thread (§16). */
data class GoodPostMessage(
    val id: String,
    val conversationId: String,
    val body: String,
    val fromAdmin: Boolean,
    val createdAt: String,
    val readAt: String?
) {
    val createdAtMs: Long? get() = parseIsoMillis(createdAt)
}

/**
 * The reasons a report may cite (§18).
 *
 * The wire values are the server's own, so the client cannot invent a reason
 * the API rejects; [label] is what the picker shows.
 */
data class GoodPostReportReason(val wire: String, val label: String)

val GOODPOST_REPORT_REASONS: List<GoodPostReportReason> = listOf(
    GoodPostReportReason("spam", "Spam or advertising"),
    GoodPostReportReason("abuse", "Abuse or hate"),
    GoodPostReportReason("harassment", "Harassment"),
    GoodPostReportReason("impersonation", "Impersonation"),
    GoodPostReportReason("misinformation", "Misleading information"),
    GoodPostReportReason("illegal", "Illegal content"),
    GoodPostReportReason("other", "Something else")
)

/** What a report can be aimed at (§18), as the API spells each one. */
object GoodPostReportTarget {
    const val USER = "user"
    const val CHANNEL = "channel"
    const val POST = "post"
    const val MESSAGE = "message"

    /**
     * Whether the server accepts this target type.
     *
     * Checked before sending so a client-side mistake is a worded refusal
     * rather than a `400` the user cannot act on. The server checks again —
     * this is a courtesy, never the authority (§32).
     */
    fun isKnown(type: String): Boolean =
        type == USER || type == CHANNEL || type == POST || type == MESSAGE
}

/**
 * A thing the user asked to report, while the picker is open.
 *
 * [label] is what the dialog names, so the confirmation cannot describe a
 * different thing from the one the user tapped.
 */
data class GoodPostReportTargetRef(
    val type: String,
    val id: String,
    val label: String
)

/** What one of the account's open conversations carries, for a list row. */
data class GoodPostConversationPage(
    val items: List<GoodPostConversation>,
    val nextCursor: String?
)

/**
 * Parsing inbox payloads.
 *
 * Pure and unit-tested directly, exactly like the other codecs: this is the
 * boundary where a server-side rename would otherwise turn an inbox into a set
 * of blank rows. An unusable row is skipped rather than thrown, so a contract
 * break shows up as a shorter list instead of a crash.
 */
internal object GoodPostInboxCodec {

    fun notification(json: JSONObject): GoodPostNotification? {
        val id = json.optString("id")
        if (id.isBlank()) return null
        return GoodPostNotification(
            id = id,
            kind = json.optString("kind"),
            title = json.optString("title"),
            body = json.optString("body"),
            channelId = json.nullableString("channelId"),
            postId = json.nullableString("postId"),
            createdAt = json.optString("createdAt"),
            readAt = json.nullableString("readAt")
        )
    }

    /** Parse the `{ items }` body `/notifications` returns. */
    fun notifications(body: JSONObject): List<GoodPostNotification> =
        objects(body.optJSONArray("items")).mapNotNull(::notification)

    fun notice(json: JSONObject): GoodPostNotice? {
        val id = json.optString("id")
        if (id.isBlank()) return null
        return GoodPostNotice(
            id = id,
            subject = json.optString("subject"),
            body = json.optString("body"),
            createdAt = json.optString("createdAt"),
            readAt = json.nullableString("readAt"),
            aboutChannelId = json.nullableString("aboutChannelId")
        )
    }

    fun notices(body: JSONObject): List<GoodPostNotice> =
        objects(body.optJSONArray("items")).mapNotNull(::notice)

    fun conversation(json: JSONObject): GoodPostConversation? {
        val id = json.optString("id")
        val channelId = json.optString("channelId")
        // Without both ids the row could not be opened or replied to, so it is
        // unusable rather than partially usable.
        if (id.isBlank() || channelId.isBlank()) return null

        val follower = json.optJSONObject("follower")
        return GoodPostConversation(
            id = id,
            channelId = channelId,
            channelName = json.optString("channelName"),
            channelSlug = json.optString("channelSlug"),
            followerId = follower?.nullableString("id"),
            followerDisplayName = follower?.nullableString("displayName"),
            lastMessageAt = json.nullableString("lastMessageAt"),
            lastMessagePreview = json.nullableString("lastMessagePreview"),
            blocked = json.optBoolean("blocked", false),
            closed = json.optBoolean("closed", false),
            unreadCount = json.optInt("unreadCount", 0)
        )
    }

    /** Parse a `{ items }` conversation list. */
    fun conversations(body: JSONObject): List<GoodPostConversation> =
        objects(body.optJSONArray("items")).mapNotNull(::conversation)

    /** Parse a `{ conversation: {...} }` body. */
    fun singleConversation(body: JSONObject): GoodPostConversation? =
        body.optJSONObject("conversation")?.let(::conversation)

    fun message(json: JSONObject): GoodPostMessage? {
        val id = json.optString("id")
        val conversationId = json.optString("conversationId")
        if (id.isBlank() || conversationId.isBlank()) return null
        return GoodPostMessage(
            id = id,
            conversationId = conversationId,
            body = json.optString("body"),
            fromAdmin = json.optBoolean("fromAdmin", false),
            createdAt = json.optString("createdAt"),
            readAt = json.nullableString("readAt")
        )
    }

    /**
     * Parse a thread page.
     *
     * The server returns newest first; the client shows oldest first, so the
     * order is reversed HERE rather than in the composable. A `LazyColumn`
     * told to reverse itself would break the moment the list is inside
     * anything scrollable, and an inverted list is a bug that looks like a
     * styling choice.
     */
    fun messages(body: JSONObject): List<GoodPostMessage> =
        objects(body.optJSONArray("items")).mapNotNull(::message).reversed()

    /** Parse a `{ message: {...} }` body. */
    fun singleMessage(body: JSONObject): GoodPostMessage? =
        body.optJSONObject("message")?.let(::message)

    private fun objects(array: JSONArray?): List<JSONObject> {
        if (array == null) return emptyList()
        val parsed = ArrayList<JSONObject>(array.length())
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let(parsed::add)
        }
        return parsed
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    // ── Cache encoding (§36) ─────────────────────────────────────────────
    //
    // Written with the SAME key names the parser reads, so one payload shape
    // covers both directions and a round-trip test can catch the drift that
    // would otherwise surface as blank rows after an app update.

    fun encodeNotifications(items: List<GoodPostNotification>): String =
        JSONObject().apply {
            put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("kind", item.kind)
                        put("title", item.title)
                        put("body", item.body)
                        put("channelId", item.channelId ?: JSONObject.NULL)
                        put("postId", item.postId ?: JSONObject.NULL)
                        put("createdAt", item.createdAt)
                        put("readAt", item.readAt ?: JSONObject.NULL)
                    })
                }
            })
        }.toString()

    /** Null for damage, so the caller can say it could not load rather than "empty". */
    fun decodeNotifications(raw: String?): List<GoodPostNotification>? =
        decode(raw) { notifications(it) }

    fun encodeNotices(items: List<GoodPostNotice>): String =
        JSONObject().apply {
            put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("subject", item.subject)
                        put("body", item.body)
                        put("createdAt", item.createdAt)
                        put("readAt", item.readAt ?: JSONObject.NULL)
                        put("aboutChannelId", item.aboutChannelId ?: JSONObject.NULL)
                    })
                }
            })
        }.toString()

    fun decodeNotices(raw: String?): List<GoodPostNotice>? =
        decode(raw) { notices(it) }

    fun encodeConversations(items: List<GoodPostConversation>): String =
        JSONObject().apply {
            put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("channelId", item.channelId)
                        put("channelName", item.channelName)
                        put("channelSlug", item.channelSlug)
                        put("lastMessageAt", item.lastMessageAt ?: JSONObject.NULL)
                        put("lastMessagePreview", item.lastMessagePreview ?: JSONObject.NULL)
                        put("blocked", item.blocked)
                        put("closed", item.closed)
                        put("unreadCount", item.unreadCount)
                        item.followerId?.let { id ->
                            put("follower", JSONObject().apply {
                                put("id", id)
                                put("displayName", item.followerDisplayName ?: JSONObject.NULL)
                            })
                        }
                    })
                }
            })
        }.toString()

    fun decodeConversations(raw: String?): List<GoodPostConversation>? =
        decode(raw) { conversations(it) }

    /**
     * Shared decode guard.
     *
     * A cached blob is only ever written by the encoders above, so anything
     * that does not carry an `items` ARRAY is damaged — and damage must be null
     * rather than an empty list, because an empty list is how "nothing to read"
     * gets shown to someone whose inbox held five rows a minute ago.
     */
    private fun <T> decode(raw: String?, parse: (JSONObject) -> List<T>): List<T>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            if (root.opt("items") !is JSONArray) return null
            parse(root)
        } catch (e: Exception) {
            null
        }
    }
}
