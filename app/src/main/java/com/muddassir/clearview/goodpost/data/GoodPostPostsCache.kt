package com.muddassir.clearview.goodpost.data

import android.content.Context

/**
 * A cached page of posts, with the time it was stored.
 *
 * The timestamp is kept so the UI can say the list is saved rather than live
 * (§36). Age is deliberately not used to expire it: §11's retention window is a
 * server-side policy, and a post that is still worth reading offline should not
 * vanish from someone's phone because the cache crossed an arbitrary age.
 */
data class CachedPostPage(val page: GoodPostPostPage, val savedAtMs: Long)

/**
 * Offline cache for the feed and channel history (§36, §10).
 *
 * `SharedPreferences` + JSON strings, matching how the rest of ClearView
 * persists things — no Room, no DataStore (§35 forbids introducing them).
 *
 * What is cached is text and metadata. No URLs: a presigned link is a
 * short-lived capability, so storing one would leave the app showing an expired
 * address as though it were an asset. No media bytes either — those live in
 * [GoodPostMediaStore], only ever because the user asked for them (§10).
 *
 * The size is capped because this is written from server-provided text: without
 * a cap, paging through a busy feed would append forever, and an unbounded
 * `SharedPreferences` entry is read into memory on every access.
 */
internal class GoodPostPostsCache(context: Context) {

    private companion object {
        const val PREFS = "goodpost_posts_cache"
        const val KEY_FEED = "feed"
        const val HISTORY_PREFIX = "history:"
        const val KEY_SAVED_AT = "saved_at"

        /** Enough to read a feed offline; small enough to stay off the heap. */
        const val MAX_POSTS = 100
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** §4 the aggregated feed, cached as a single list. */
    fun saveFeed(page: GoodPostPostPage, nowMs: Long) =
        write(KEY_FEED, page, nowMs)

    fun loadFeed(): CachedPostPage? = read(KEY_FEED)

    /**
     * §8 one channel's history.
     *
     * Keyed per channel rather than single-slot: a user who opens channel A,
     * then B, then reopens A offline must still see A's posts. One slot would
     * have thrown A away to make room for B.
     */
    fun saveHistory(channelId: String, page: GoodPostPostPage, nowMs: Long) =
        write(HISTORY_PREFIX + channelId, page, nowMs)

    fun loadHistory(channelId: String): CachedPostPage? = read(HISTORY_PREFIX + channelId)

    /**
     * Drop everything. Called on sign-out, with the channel cache.
     *
     * Only metadata: saved media survives, because it belongs to the person who
     * downloaded it rather than to the session that listed it (§10).
     */
    fun clear() {
        prefs.edit().clear().apply()
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun write(key: String, page: GoodPostPostPage, nowMs: Long) {
        prefs.edit()
            .putString(key, GoodPostPostCodec.encodePage(page))
            .putLong(key + KEY_SAVED_AT, nowMs)
            .apply()
    }

    private fun read(key: String): CachedPostPage? {
        val decoded = GoodPostPostCodec.decodePage(prefs.getString(key, null)) ?: return null
        val capped = if (decoded.items.size <= MAX_POSTS) {
            decoded
        } else {
            decoded.copy(items = decoded.items.take(MAX_POSTS))
        }
        return CachedPostPage(capped, prefs.getLong(key + KEY_SAVED_AT, 0L))
    }
}
