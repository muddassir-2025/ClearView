package com.muddassir.clearview.goodpost.data

import android.content.Context

/**
 * A cached list plus the time it was stored.
 *
 * The timestamp is kept so the UI can label saved content as saved rather than
 * live. Age is deliberately NOT used to expire anything: previously fetched
 * channels and posts staying readable offline is the requirement, and a channel
 * name does not become wrong by being a day old.
 */
data class CachedChannels(val channels: List<GoodPostChannel>, val savedAtMs: Long)

/** A cached post list for one channel. */
data class CachedPosts(val posts: List<GoodPostPost>, val savedAtMs: Long)

/** A cached advertisement list for one placement. */
data class CachedAds(val ads: List<GoodPostAd>, val savedAtMs: Long)

/**
 * Offline cache for Good Post (§27).
 *
 * `SharedPreferences` + JSON strings, matching how the rest of ClearView
 * persists things — no Room, no DataStore, no DI.
 *
 * What is cached is what a reader needs to see the tab open instantly (§26):
 * the channel list, the categories, and the posts of channels they have already
 * opened. Signed media URLs are NOT cached — see [GoodPostCodec.encodePosts] —
 * so a cached post renders its text and an honest placeholder rather than a
 * broken image.
 *
 * Sizes are capped because these entries are written from server-provided text:
 * without a cap, paging would append forever, and an unbounded
 * `SharedPreferences` value is read into memory on every access.
 */
internal class GoodPostCache(context: Context) {

    private companion object {
        const val PREFS = "goodpost_cache"
        const val KEY_CHANNELS = "channels"
        const val KEY_CATEGORIES = "categories"
        const val KEY_SAVED_AT = "_saved_at"
        const val POSTS_PREFIX = "posts:"
        const val ADS_PREFIX = "ads:"

        /** Enough to browse offline; small enough to stay off the heap. */
        const val MAX_CHANNELS = 200
        const val MAX_POSTS_PER_CHANNEL = 60
        const val MAX_ADS = 10
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Channels (§3) ────────────────────────────────────────────────────

    fun saveChannels(channels: List<GoodPostChannel>, nowMs: Long) {
        prefs.edit()
            .putString(KEY_CHANNELS, GoodPostCodec.encodeChannels(channels.take(MAX_CHANNELS)))
            .putLong(KEY_CHANNELS + KEY_SAVED_AT, nowMs)
            .apply()
    }

    fun loadChannels(): CachedChannels? {
        val decoded = GoodPostCodec.decodeChannels(prefs.getString(KEY_CHANNELS, null))
        if (decoded.isEmpty()) return null
        return CachedChannels(decoded, prefs.getLong(KEY_CHANNELS + KEY_SAVED_AT, 0L))
    }

    // ── Posts (§9) ───────────────────────────────────────────────────────

    fun savePosts(channelId: String, posts: List<GoodPostPost>, nowMs: Long) {
        val key = POSTS_PREFIX + channelId
        prefs.edit()
            .putString(
                key,
                GoodPostCodec.encodePosts(posts.take(MAX_POSTS_PER_CHANNEL))
            )
            .putLong(key + KEY_SAVED_AT, nowMs)
            .apply()
    }

    fun loadPosts(channelId: String): CachedPosts? {
        val key = POSTS_PREFIX + channelId
        val decoded = GoodPostCodec.decodePosts(prefs.getString(key, null))
        if (decoded.isEmpty()) return null
        return CachedPosts(decoded, prefs.getLong(key + KEY_SAVED_AT, 0L))
    }

    // ── Advertisements (§12) ─────────────────────────────────────────────

    /**
     * Cache the active cards for one placement.
     *
     * Keyed by placement, not shared: a card can run on Channels, on Explore, or
     * on both, and one entry would show a reader the other surface's card.
     */
    fun saveAds(placement: GoodPostAdPlacement, ads: List<GoodPostAd>, nowMs: Long) {
        val key = ADS_PREFIX + placement.wire
        prefs.edit()
            .putString(key, GoodPostCodec.encodeAds(ads.take(MAX_ADS)))
            .putLong(key + KEY_SAVED_AT, nowMs)
            .apply()
    }

    /**
     * The cached cards for one placement, already filtered by the clock.
     *
     * Filtered HERE rather than by the caller because a cached card is the one
     * kind of content whose validity can end while it sits on disk. The server
     * applies this rule to a live read; a card that expired since it was saved
     * would otherwise outlive its own campaign.
     */
    fun loadAds(placement: GoodPostAdPlacement, nowMs: Long): CachedAds? {
        val key = ADS_PREFIX + placement.wire
        val decoded = GoodPostCodec.decodeAds(prefs.getString(key, null))
        if (decoded.isEmpty()) return null
        val active = decoded.filter { it.isActiveAt(nowMs) }
        if (active.isEmpty()) return null
        return CachedAds(active, prefs.getLong(key + KEY_SAVED_AT, 0L))
    }

    // ── Following (§1, §2) ───────────────────────────────────────────────
    // ── Categories (§7) ──────────────────────────────────────────────────

    fun saveCategories(categories: List<GoodPostCategory>) {
        prefs.edit()
            .putString(KEY_CATEGORIES, GoodPostCodec.encodeCategories(categories))
            .apply()
    }

    fun loadCategories(): List<GoodPostCategory> =
        GoodPostCodec.decodeCategories(prefs.getString(KEY_CATEGORIES, null))

    /** Forget everything fetched. Called when an administrator signs out. */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
