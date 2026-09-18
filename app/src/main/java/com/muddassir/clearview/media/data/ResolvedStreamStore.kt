package com.muddassir.clearview.media.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The stream URLs this device has already resolved, kept across app restarts.
 *
 * ## Why this exists
 *
 * Resolving a Reel is the slowest thing in the Media tab and it used to happen
 * on EVERY open. [InstagramStreamResolver] first scrapes up to four embed pages
 * over HTTP — each with its own connect and read timeout — and, since the embed
 * page is client-rendered now, that usually finds nothing, so
 * [InstagramEmbedResolver] renders the public embed in a WebView and reads the
 * `<video>` src out of the DOM. The answer is a plain progressive `.mp4` on
 * Meta's CDN: it does not change between two taps of the same Reel, so paying
 * for the lookup twice was two waits for one fact.
 *
 * The WebView half caches in memory for twenty minutes ([InstagramEmbedResolver]);
 * nothing survives a restart, and the scrape that runs before it was never cached
 * at all. This is the whole answer, on disk, including the case the reader is
 * most likely to hit — the same Reel opened again after closing the app.
 *
 * ## Bounded, and dumb on purpose
 *
 * Forty entries, oldest evicted first: a signed CDN URL is ~1.5 KB, so this is a
 * few tens of kilobytes of preferences, not a growing file. Which entries are
 * still WORTH using is [InstagramStreamResolver.isStreamUsable]'s decision — a
 * URL's own expiry is in its query string, and the policy that reads it belongs
 * with the resolver that understands it rather than with the storage.
 *
 * One JSON array under one key rather than a preference per Reel: eviction means
 * rewriting a list either way, and a key per Reel would leave the old entries
 * behind forever.
 */
class ResolvedStreamStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** One stored resolution: when it happened, and what it found. */
    data class CachedStream(val atMillis: Long, val stream: InstagramStreamResolver.ResolvedStream)

    /** The cached resolution for [shortcode], or null when there is none. */
    fun get(shortcode: String): CachedStream? {
        if (shortcode.isBlank()) return null
        val entry = read()[shortcode] ?: return null
        return CachedStream(entry.atMillis, entry.stream)
    }

    /** Remember a resolution, replacing any older one for the same [shortcode]. */
    fun put(shortcode: String, stream: InstagramStreamResolver.ResolvedStream, atMillis: Long) {
        if (shortcode.isBlank()) return
        val entries = read()
        entries[shortcode] = CachedStream(atMillis, stream)
        write(entries)
    }

    /** Drop one entry, for a Retry that must not be answered from the cache. */
    fun forget(shortcode: String) {
        if (shortcode.isBlank()) return
        val entries = read()
        if (entries.remove(shortcode) == null) return
        write(entries)
    }

    // ── The file itself ──────────────────────────────────────────────────

    /**
     * Every entry, newest first.
     *
     * A body that cannot be parsed is treated as an empty cache rather than as an
     * error: this holds nothing the app cannot fetch again, and throwing here
     * would turn one corrupt line into a video that never opens.
     */
    private fun read(): LinkedHashMap<String, CachedStream> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return LinkedHashMap()
        return runCatching {
            val array = JSONArray(raw)
            val entries = LinkedHashMap<String, CachedStream>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val code = item.optString(KEY_SHORTCODE)
                val url = item.optString(KEY_URL)
                if (code.isBlank() || url.isBlank()) continue
                entries[code] = CachedStream(
                    atMillis = item.optLong(KEY_AT, 0L),
                    stream = InstagramStreamResolver.ResolvedStream(
                        videoUrl = url,
                        posterUrl = item.optString(KEY_POSTER).takeIf { it.isNotBlank() }
                    )
                )
            }
            entries
        }.getOrElse { LinkedHashMap() }
    }

    /**
     * Write the entries back, keeping the newest [MAX_ENTRIES].
     *
     * The whole value goes in one `commit`-free `apply()`: this is written from a
     * player that is starting, and a synchronous disk write in that path is the
     * kind of thing that shows up as a stutter on a slow phone.
     */
    private fun write(entries: LinkedHashMap<String, CachedStream>) {
        val newestFirst = entries.entries.sortedByDescending { it.value.atMillis }.take(MAX_ENTRIES)
        val array = JSONArray()
        newestFirst.forEach { (code, cached) ->
            array.put(
                JSONObject().apply {
                    put(KEY_SHORTCODE, code)
                    put(KEY_URL, cached.stream.videoUrl)
                    put(KEY_POSTER, cached.stream.posterUrl ?: "")
                    put(KEY_AT, cached.atMillis)
                }
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "resolved_streams"
        const val KEY_ENTRIES = "entries"
        const val KEY_SHORTCODE = "c"
        const val KEY_URL = "u"
        const val KEY_POSTER = "p"
        const val KEY_AT = "t"
        const val MAX_ENTRIES = 40
    }
}
