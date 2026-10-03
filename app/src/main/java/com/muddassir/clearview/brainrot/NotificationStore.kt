package com.muddassir.clearview.brainrot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The in-app notification centre.
 *
 * Two kinds of thing arrive here, and they are the same kind of thing to the
 * person reading them: something ClearView did on their behalf, or something
 * that happened to a request they made.
 *
 *  1. **A block confirmation, immediately.** When a user taps "Not interested"
 *     on a Short and ClearView blocks that channel, they are told so — there and
 *     then, not when an administrator eventually looks at it. Personal blocks
 *     take effect instantly (that is the product), so the confirmation is a
 *     receipt for something that has already happened.
 *
 *  2. **A decision on a global request.** Approved or rejected, whenever the
 *     answer arrives. Polled rather than pushed, because there is no account and
 *     therefore nothing for a push service to address.
 *
 * ## Why not a system notification
 *
 * These are kept in-app on purpose. A system notification for "we blocked a
 * channel for you" would compete with the user's real notifications, and the
 * centre is where somebody goes to check what ClearView has been doing — which
 * is also why the Blocking tab's bell shows an unread count.
 *
 * Stored newest-first, capped, and every method is exception-safe.
 */
class NotificationStore(context: Context) {

    companion object {
        private const val TAG = "NotificationStore"
        private const val PREFS = "clearview_brainrot_notifications"
        private const val KEY_ITEMS = "items_json"

        /** How many notifications are kept. An unbounded log is not a feed. */
        const val MAX_ITEMS = 200
    }

    /**
     * What a notification is about, so the UI can group and word it.
     *
     * Stored as the wire string, so an old notification keeps its meaning when a
     * new kind is added rather than being re-read as the wrong one.
     */
    enum class Kind(val wire: String) {
        /** A personal block took effect (the "Not interested" receipt). */
        BLOCKED("blocked"),

        /** A global request was approved. */
        GLOBAL_APPROVED("global_approved"),

        /** A global request was rejected. */
        GLOBAL_REJECTED("global_rejected"),

        /** A global request is queued for review. */
        GLOBAL_SUBMITTED("global_submitted");

        companion object {
            fun fromWire(value: String?): Kind =
                entries.firstOrNull { it.wire == value } ?: BLOCKED
        }
    }

    data class Item(
        val id: String,
        val kind: Kind,
        /** The keyword or `@handle` this is about. */
        val value: String,
        /** The channel's display name, when it is a channel. */
        val displayName: String?,
        /** A human-readable sentence, already assembled for display. */
        val message: String,
        val atMs: Long,
        val read: Boolean = false
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Newest first. */
    fun getAll(): List<Item> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Item(
                    id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    kind = Kind.fromWire(o.optString("kind")),
                    value = o.optString("value"),
                    displayName = o.optString("name").takeIf { it.isNotBlank() },
                    message = o.optString("msg"),
                    atMs = o.optLong("at", 0L),
                    read = o.optBoolean("read", false)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAll parse error: ${e.message}")
            emptyList()
        }
    }

    /** The unread count, for the bell's badge. */
    fun unreadCount(): Int = getAll().count { !it.read }

    /**
     * Record a notification, newest first.
     *
     * `id` is supplied by the caller so a poll that re-sees the same decision
     * cannot create a second notification for it. That idempotency is the whole
     * reason the id is a parameter rather than generated here.
     */
    fun add(
        id: String,
        kind: Kind,
        value: String,
        displayName: String?,
        message: String,
        atMs: Long = System.currentTimeMillis()
    ) {
        try {
            val current = getAll()
            if (current.any { it.id == id }) return
            val item = Item(
                id = id,
                kind = kind,
                value = value,
                displayName = displayName,
                message = message,
                atMs = atMs,
                read = false
            )
            persist((listOf(item) + current).take(MAX_ITEMS))
        } catch (e: Exception) {
            Log.e(TAG, "add error: ${e.message}")
        }
    }

    /** Mark one notification read (tapped, or the whole list opened). */
    fun markRead(id: String) {
        try {
            persist(getAll().map { if (it.id == id) it.copy(read = true) else it })
        } catch (e: Exception) {
            Log.e(TAG, "markRead error: ${e.message}")
        }
    }

    /** Mark everything read. */
    fun markAllRead() {
        try {
            persist(getAll().map { it.copy(read = true) })
        } catch (e: Exception) {
            Log.e(TAG, "markAllRead error: ${e.message}")
        }
    }

    /** Clear the feed. */
    fun clear() {
        try {
            prefs.edit().remove(KEY_ITEMS).apply()
        } catch (e: Exception) {
            Log.e(TAG, "clear error: ${e.message}")
        }
    }

    private fun persist(items: List<Item>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("kind", item.kind.wire)
                put("value", item.value)
                item.displayName?.let { put("name", it) }
                put("msg", item.message)
                put("at", item.atMs)
                put("read", item.read)
            })
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
