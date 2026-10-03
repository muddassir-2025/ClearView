package com.muddassir.clearview.brainrot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-device copy of the global Brain Rot rule set.
 *
 * The accessibility hot path must never wait on a network call, so the rules are
 * fetched in the background and read from here. Two properties make that safe:
 *
 *  * **A failed fetch never weakens protection.** A refresh that fails leaves
 *    the previous snapshot exactly as it was, so the app blocks everything it
 *    blocked before. There is no path that empties the list because a request
 *    did not complete.
 *
 *  * **A first run with no network blocks nothing new, and nothing less.** The
 *    local rules are unaffected either way; this only ever ADDS global rules on
 *    top of them.
 *
 * Kept in its own prefs file, like [BrainRotRepository], so a rule refresh can
 * never race a stats write.
 */
class GlobalRulesStore(context: Context) {

    companion object {
        private const val TAG = "GlobalRulesStore"
        private const val PREFS = "clearview_brainrot_rules"
        private const val KEY_RULES = "rules_json"
        private const val KEY_VERSION = "version"
        private const val KEY_LAST_SYNC = "last_sync_ms"

        /** How long a cached snapshot is trusted before a background refresh. */
        const val TTL_MS = 6 * 60 * 60 * 1000L // 6h
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val client = BrainRotClient(context)

    /** Global keywords, already lower-cased for matching. */
    @Volatile
    var keywords: Set<String> = emptySet()
        private set

    /** Global channel handles, already normalised to a leading "@". */
    @Volatile
    var channelHandles: Set<String> = emptySet()
        private set

    /** Report counts by keyword, for the UI to show community context. */
    @Volatile
    var keywordReports: Map<String, Int> = emptyMap()
        private set

    /** Report counts by channel handle. */
    @Volatile
    var channelReports: Map<String, Int> = emptyMap()
        private set

    @Volatile
    private var syncing = false

    init {
        loadCached()
    }

    private fun loadCached() {
        try {
            val raw = prefs.getString(KEY_RULES, null) ?: return
            val json = JSONObject(raw)
            apply(json)
        } catch (e: Exception) {
            Log.e(TAG, "loadCached error: ${e.message}")
        }
    }

    private fun apply(json: JSONObject) {
        val keywords = LinkedHashSet<String>()
        val keywordReportMap = HashMap<String, Int>()
        json.optJSONArray("keywords")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val keyword = o.optString("keyword").trim().lowercase()
                if (keyword.isEmpty()) continue
                keywords.add(keyword)
                keywordReportMap[keyword] = o.optInt("reports", 0)
            }
        }

        val handles = LinkedHashSet<String>()
        val channelReportMap = HashMap<String, Int>()
        json.optJSONArray("channels")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val handle = BrainRotRepository.normalizeHandle(o.optString("handle")) ?: continue
                handles.add(handle)
                channelReportMap[handle] = o.optInt("reports", 0)
            }
        }

        this.keywords = keywords
        this.channelHandles = handles
        this.keywordReports = keywordReportMap
        this.channelReports = channelReportMap
    }

    /** True when a background refresh is worth attempting now. */
    fun isStale(): Boolean {
        val last = prefs.getLong(KEY_LAST_SYNC, 0L)
        return System.currentTimeMillis() - last >= TTL_MS
    }

    /**
     * Refresh the cached rules from the server.
     *
     * Returns true when the cache was replaced. False means "nothing changed,
     * or nothing could be fetched" — in both cases the previous snapshot stays
     * in force, which is the property that makes a dead backend harmless.
     */
    suspend fun sync(force: Boolean = false): Boolean {
        if (syncing) return false
        if (!force && !isStale()) return true
        if (!client.isConfigured()) return false
        syncing = true
        try {
            val rules = client.fetchRules() ?: return false
            val previous = prefs.getString(KEY_VERSION, "")
            // The version is a content fingerprint, so an unchanged rule set is
            // a no-op rather than a rewrite — and the caller can skip redrawing.
            if (!force && rules.version.isNotBlank() && rules.version == previous) {
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
                return true
            }

            val json = JSONObject().apply {
                put("keywords", JSONArray().apply {
                    rules.keywords.forEach { k ->
                        put(JSONObject().apply {
                            put("keyword", k.keyword)
                            put("reports", k.reports)
                            k.reason?.let { put("reason", it) }
                        })
                    }
                })
                put("channels", JSONArray().apply {
                    rules.channels.forEach { c ->
                        put(JSONObject().apply {
                            put("handle", c.handle)
                            put("reports", c.reports)
                            c.name?.let { put("name", it) }
                            c.reason?.let { put("reason", it) }
                        })
                    }
                })
            }
            prefs.edit()
                .putString(KEY_RULES, json.toString())
                .putString(KEY_VERSION, rules.version)
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .apply()
            apply(json)
            Log.i(TAG, "RULES_SYNCED keywords=${rules.keywords.size} channels=${rules.channels.size} version=${rules.version}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "sync error: ${e.message}")
            return false
        } finally {
            syncing = false
        }
    }

    /** Suggest a keyword for the global repository (queued for review). */
    suspend fun suggestKeyword(keyword: String): Boolean =
        client.submitSuggestion("keyword", keyword)

    /** Suggest a channel for the global repository (queued for review). */
    suspend fun suggestChannel(handle: String, name: String? = null): Boolean =
        client.submitSuggestion("channel", handle, name)

    /** Report a keyword. Returns the new report count, or null on failure. */
    suspend fun reportKeyword(keyword: String, detail: String? = null): Int? =
        client.report("keyword", keyword, detail)

    /** Report a channel. Returns the new report count, or null on failure. */
    suspend fun reportChannel(handle: String, detail: String? = null): Int? =
        client.report("channel", handle, detail)
}
