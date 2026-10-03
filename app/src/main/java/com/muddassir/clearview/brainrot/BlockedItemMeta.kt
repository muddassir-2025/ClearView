package com.muddassir.clearview.brainrot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Why an item is blocked, and where the block came from.
 *
 * The product rule this exists to satisfy: a block must always be able to
 * explain itself. "Swipe Brain Rot" tells a user nothing they can act on, while
 * "Blocked by Brain Rot Protection — matched the keyword \"viral\"" tells them
 * exactly which rule fired and therefore which rule to change. A block with no
 * visible reason is one the user cannot correct, and a block they cannot correct
 * is one they will switch off.
 *
 * ## Where this lives, and why it is not in [BlockRepository]
 *
 * The enforcement lists (`BlockRepository`) are the matching hot path: they are
 * read on every scan and must stay tiny and cacheable. This is provenance —
 * a reason string and a source tag per item — which the matcher never needs.
 * Keeping it here means the hot path is untouched and a corrupt provenance file
 * degrades to "no reason shown", never to "protection stopped working".
 *
 * Keyed by the item's own identity: a lowercased keyword, or a normalised
 * `@handle`. That is the same identity the lists use, so there is no second
 * naming scheme to keep in sync.
 *
 * Every method is exception-safe and never throws.
 */
class BlockedItemMeta(context: Context) {

    /** Where a block came from. Persisted as the wire string, so it is stable. */
    enum class Source(val wire: String) {
        /** Added by hand in the Blocking tab. */
        USER("user"),

        /** Added from YouTube's "Not interested" action. */
        YOUTUBE_NOT_INTERESTED("youtube_not_interested"),

        /** Added by a global rule an administrator approved. */
        GLOBAL("global"),

        /** Recorded before this file existed, or by a path that named no source. */
        UNKNOWN("unknown");

        companion object {
            fun fromWire(value: String?): Source =
                entries.firstOrNull { it.wire == value } ?: UNKNOWN
        }
    }

    data class Meta(
        val reason: String,
        val source: Source,
        val addedAtMs: Long = 0L
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All recorded provenance, keyed by the item's canonical identity. */
    private fun load(): MutableMap<String, Meta> {
        val raw = prefs.getString(KEY, null) ?: return mutableMapOf()
        return try {
            val json = JSONObject(raw)
            val out = mutableMapOf<String, Meta>()
            json.keys().forEach { key ->
                val obj = json.optJSONObject(key) ?: return@forEach
                out[key] = Meta(
                    reason = obj.optString("reason"),
                    source = Source.fromWire(obj.optString("source")),
                    addedAtMs = obj.optLong("at", 0L)
                )
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "load error: ${e.message}")
            mutableMapOf()
        }
    }

    private fun save(all: Map<String, Meta>) {
        try {
            val json = JSONObject()
            all.forEach { (key, meta) ->
                json.put(key, JSONObject().apply {
                    put("reason", meta.reason)
                    put("source", meta.source.wire)
                    put("at", meta.addedAtMs)
                })
            }
            prefs.edit().putString(KEY, json.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "save error: ${e.message}")
        }
    }

    /**
     * Record why an item is blocked.
     *
     * An existing `addedAtMs` is kept, so re-recording a reason (a global rule
     * approved later, say) does not make an old item look newly added.
     */
    fun record(
        item: String,
        reason: String,
        source: Source,
        atMs: Long = System.currentTimeMillis()
    ) {
        val key = keyOf(item) ?: return
        val all = load()
        val existing = all[key]
        all[key] = Meta(
            reason = reason,
            source = source,
            addedAtMs = existing?.addedAtMs?.takeIf { it > 0 } ?: atMs
        )
        save(all)
    }

    /** The recorded provenance for an item, or null when none was recorded. */
    fun get(item: String): Meta? {
        val key = keyOf(item) ?: return null
        return load()[key]
    }

    /** Drop an item's provenance, called when the item itself is unblocked. */
    fun forget(item: String) {
        val key = keyOf(item) ?: return
        val all = load()
        if (all.remove(key) != null) save(all)
    }

    /**
     * Drop provenance for everything NOT in [live].
     *
     * The lists and this file are edited by different screens, so a stale entry
     * can outlive the block it described. Pruning against the live set on each
     * refresh is what keeps "why is this blocked" from answering for something
     * that is no longer blocked at all.
     */
    fun prune(live: Set<String>) {
        val keys = live.mapNotNull { keyOf(it) }.toSet()
        val all = load()
        val before = all.size
        all.keys.retainAll(keys)
        if (all.size != before) save(all)
    }

    companion object {
        private const val TAG = "BlockedItemMeta"
        private const val PREFS = "clearview_blocked_meta"
        private const val KEY = "meta_json"

        /**
         * The canonical key for an item: a normalised handle, or a lowercased
         * keyword. Returns null for a blank item so nothing is keyed on "".
         */
        fun keyOf(item: String?): String? {
            val trimmed = item?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("@")) {
                return BrainRotRepository.normalizeHandle(trimmed)
            }
            return trimmed.lowercase(java.util.Locale.ROOT)
        }
    }
}
