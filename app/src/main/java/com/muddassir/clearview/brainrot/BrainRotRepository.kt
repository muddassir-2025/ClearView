package com.muddassir.clearview.brainrot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * On-device storage for the Brain Rot dashboard: the protection events that
 * feed the Activity section, and the user's own blocked channels.
 *
 * Deliberately separate from [com.muddassir.clearview.repository.BlockRepository]
 * (the enforcement lists) for the same reason [com.muddassir.clearview.youtubetest.YoutubeTestKeywordRepository]
 * is: this is reporting and management state, not the matching hot path. It
 * lives in its own prefs file so a stats write can never race a keyword read,
 * and so the dashboard's data can be cleared without touching the rules.
 *
 * Every method is exception-safe and never throws: the accessibility service
 * records events from a background thread and a corrupt file must degrade to
 * "no stats" rather than crash protection.
 */
class BrainRotRepository(context: Context) {

    companion object {
        private const val TAG = "BrainRotRepository"
        private const val PREFS = "clearview_brainrot"
        private const val KEY_EVENTS = "events_json"
        private const val KEY_CHANNELS = "blocked_channels_json"

        /** Cap on stored channels — a management list, not an unbounded log. */
        const val MAX_CHANNELS = 500

        /**
         * Normalize a channel handle: trim, ensure a single leading "@",
         * lowercase. Returns null for anything that is not a plausible handle,
         * so "@", "@-" and a full URL never enter the list.
         */
        fun normalizeHandle(input: String?): String? {
            val trimmed = input?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            val withoutAt = trimmed.removePrefix("@").trim()
            if (withoutAt.isEmpty()) return null
            val lower = withoutAt.lowercase(Locale.ROOT)
            // YouTube handles: letters, digits, dot, dash, underscore.
            if (!lower.matches(Regex("[a-z0-9._-]{2,100}"))) return null
            return "@$lower"
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Protection events (Activity dashboard) ─────────────────────

    /** Newest events first is NOT stored; the list is chronological. */
    fun getEvents(): List<BlockEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                BlockEvent(
                    atMs = o.optLong("at", 0L),
                    category = o.optString("cat", ""),
                    keyword = o.optString("kw").takeIf { it.isNotBlank() },
                    channel = o.optString("ch").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getEvents parse error: ${e.message}")
            emptyList()
        }
    }

    /** Record one protection event, trimming to the newest [BrainRotStats.MAX_EVENTS]. */
    fun recordBlock(category: String, keyword: String? = null, channel: String? = null, atMs: Long = System.currentTimeMillis()) {
        try {
            val event = BlockEvent(atMs = atMs, category = category, keyword = keyword, channel = channel)
            persistEvents(BrainRotStats.append(getEvents(), event))
        } catch (e: Exception) {
            Log.e(TAG, "recordBlock error: ${e.message}")
        }
    }

    /** Wipe the recorded activity (the "reset stats" affordance). */
    fun clearEvents() {
        try {
            prefs.edit().remove(KEY_EVENTS).apply()
        } catch (e: Exception) {
            Log.e(TAG, "clearEvents error: ${e.message}")
        }
    }

    private fun persistEvents(events: List<BlockEvent>) {
        val arr = JSONArray()
        for (e in events) {
            arr.put(JSONObject().apply {
                put("at", e.atMs)
                put("cat", e.category)
                e.keyword?.let { put("kw", it) }
                e.channel?.let { put("ch", it) }
            })
        }
        prefs.edit().putString(KEY_EVENTS, arr.toString()).apply()
    }

    // ── User's blocked channels ────────────────────────────────────

    /**
     * A channel the user blocked. The HANDLE is the canonical identity because
     * it survives a rename of the displayed channel name (the spec calls this
     * out explicitly); the name is kept only for display.
     */
    data class BlockedChannel(
        val handle: String,
        val name: String? = null,
        val reason: String? = null,
        val addedAtMs: Long = 0L
    )

    fun getBlockedChannels(): List<BlockedChannel> {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val handle = o.optString("h").trim()
                if (handle.isBlank()) return@mapNotNull null
                BlockedChannel(
                    handle = handle,
                    name = o.optString("n").takeIf { it.isNotBlank() },
                    reason = o.optString("r").takeIf { it.isNotBlank() },
                    addedAtMs = o.optLong("t", 0L)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBlockedChannels parse error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Add a channel by handle. Normalizes to a leading "@" and lowercases, so
     * "@Foo", "foo" and " @FOO " are one entry rather than three. Idempotent:
     * re-adding updates the name/reason instead of duplicating.
     */
    fun addBlockedChannel(handle: String, name: String? = null, reason: String? = null): Boolean {
        val normalized = normalizeHandle(handle) ?: return false
        val current = getBlockedChannels().toMutableList()
        val existing = current.indexOfFirst { it.handle == normalized }
        val entry = BlockedChannel(
            handle = normalized,
            name = name?.trim()?.takeIf { it.isNotBlank() },
            reason = reason?.trim()?.takeIf { it.isNotBlank() },
            addedAtMs = if (existing >= 0) current[existing].addedAtMs else System.currentTimeMillis()
        )
        if (existing >= 0) current[existing] = entry else current.add(entry)
        // Newest first so a freshly added channel is visible without scrolling.
        current.sortByDescending { it.addedAtMs }
        persistChannels(current.take(MAX_CHANNELS))
        return true
    }

    /** True when this handle is already blocked (any spelling). */
    fun hasBlockedChannel(handle: String?): Boolean = isChannelBlocked(handle)

    fun removeBlockedChannel(handle: String) {
        val normalized = normalizeHandle(handle) ?: return
        persistChannels(getBlockedChannels().filterNot { it.handle == normalized })
    }

    /** True when this handle (in any spelling) is in the user's blocked list. */
    fun isChannelBlocked(handle: String?): Boolean {
        val normalized = normalizeHandle(handle) ?: return false
        return getBlockedChannels().any { it.handle == normalized }
    }

    private fun persistChannels(channels: List<BlockedChannel>) {
        val arr = JSONArray()
        for (c in channels) {
            arr.put(JSONObject().apply {
                put("h", c.handle)
                c.name?.let { put("n", it) }
                c.reason?.let { put("r", it) }
                put("t", c.addedAtMs)
            })
        }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

}
