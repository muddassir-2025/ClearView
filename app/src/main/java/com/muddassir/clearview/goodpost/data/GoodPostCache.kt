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

/** The cached external channel directory, and when it was stored. */
data class CachedDirectory(val snapshot: DirectorySnapshot, val savedAtMs: Long)

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

    internal companion object {
        const val PREFS = "goodpost_cache"
        const val KEY_CHANNELS = "channels"
        const val KEY_CATEGORIES = "categories"
        const val KEY_SAVED_AT = "_saved_at"
        const val POSTS_PREFIX = "posts:"
        const val PREFS_CHANNELS = "goodpost_channels"
        const val KEY_FOLLOWED_IDS = "followed_ids"
        const val KEY_FOLLOWED_SAVED_AT = "followed_saved_at"
        const val ADS_PREFIX = "ads:"

        /**
         * The external channel directory.
         *
         * Its own key rather than one per category: the read is a single
         * snapshot, so it is stored as one, and the screen draws the whole thing
         * from disk before any request is made.
         */
        const val DIRECTORY_KEY = "directory"

        /**
         * The administrator's full card list, kept under its own key.
         *
         * Separate from the per-placement public cache because it holds every
         * card, disabled ones included, and is not filtered by the clock — the
         * manager list is where an operator finds the card that has not started
         * or has finished. Its own key means a public placement's TTL rules
         * never touch it and vice versa.
         */
        const val ADMIN_ADS_KEY = "admin_ads"

        /** Enough to browse offline; small enough to stay off the heap. */
        const val MAX_CHANNELS = 200
        const val MAX_POSTS_PER_CHANNEL = 60
        const val MAX_ADS = 10

        /** The manager list is small and worth keeping whole. */
        const val MAX_ADMIN_ADS = 60

        /**
         * How long a saved channel list is trusted before the tab refetches it.
         *
         * This is the fix for "every time I open Good Post it fetches the
         * channels": a re-open within the window renders from disk and makes NO
         * request at all. It is a freshness window, not an expiry — a list older
         * than this is still shown, and is still shown offline; it is only
         * refetched in the background.
         */
        const val CHANNELS_TTL_MS = 15 * 60 * 1000L

        /**
         * A long-lived token for the reader's own channel list.
         *
         * Follows and their ids are pure device state — the ids change only when
         * the reader follows or unfollows something, which this class is told
         * about directly — so the identity of the list does not go stale the way
         * its contents do. Kept in its own prefs file so it can never be cleared
         * by a rules/ads cache reset.
         */
        const val FOLLOWED_TTL_MS = 24 * 60 * 60 * 1000L
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

    /** True while the saved channel list is fresh enough to skip a refetch. */
    fun channelsAreFresh(nowMs: Long): Boolean {
        val savedAt = prefs.getLong(KEY_CHANNELS + KEY_SAVED_AT, 0L)
        if (savedAt <= 0L) return false
        return nowMs - savedAt < CHANNELS_TTL_MS
    }

    // ── The reader's own channel list (follows) ──────────────────────────
    //
    // Kept apart from [loadChannels] on purpose. That entry is the PUBLIC
    // catalogue — the fallback list, cached only on an unfiltered first page —
    // and it is saved by a different call. Writing the reader's follows over it
    // is what left a cold start showing the wrong list, so the two now have
    // separate keys and separate lifetimes.

    private val followedPrefs =
        context.applicationContext.getSharedPreferences(PREFS_CHANNELS, Context.MODE_PRIVATE)

    fun saveFollowedChannels(channels: List<GoodPostChannel>, nowMs: Long) {
        followedPrefs.edit()
            .putString(KEY_CHANNELS, GoodPostCodec.encodeChannels(channels.take(MAX_CHANNELS)))
            .putLong(KEY_FOLLOWED_SAVED_AT, nowMs)
            .apply()
    }

    fun loadFollowedChannels(): CachedChannels? {
        val decoded = GoodPostCodec.decodeChannels(followedPrefs.getString(KEY_CHANNELS, null))
        if (decoded.isEmpty()) return null
        return CachedChannels(decoded, followedPrefs.getLong(KEY_FOLLOWED_SAVED_AT, 0L))
    }

    fun followedChannelsAreFresh(nowMs: Long): Boolean {
        val savedAt = followedPrefs.getLong(KEY_FOLLOWED_SAVED_AT, 0L)
        if (savedAt <= 0L) return false
        return nowMs - savedAt < FOLLOWED_TTL_MS
    }

    /**
     * Remember which channels this reader follows, so a cold start can restore
     * the follow switches before the server answers.
     */
    fun saveFollowedIds(ids: Set<String>) {
        followedPrefs.edit().putStringSet(KEY_FOLLOWED_IDS, ids).apply()
    }

    fun loadFollowedIds(): Set<String> =
        followedPrefs.getStringSet(KEY_FOLLOWED_IDS, emptySet()) ?: emptySet()

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

    /**
     * Cache the administrator's whole card list, active or not.
     *
     * The manager screen was the one place that waited on the network every
     * time it opened, which is what made it feel slow: the cards are the same
     * few rows on every visit, and a list that has not changed should not be
     * re-fetched to be drawn.
     */
    fun saveAdminAds(ads: List<GoodPostAd>, nowMs: Long) {
        prefs.edit()
            .putString(ADMIN_ADS_KEY, GoodPostCodec.encodeAds(ads.take(MAX_ADMIN_ADS)))
            .putLong(ADMIN_ADS_KEY + KEY_SAVED_AT, nowMs)
            .apply()
    }

    /** The cached card list, unfiltered by the clock. */
    fun loadAdminAds(): CachedAds? {
        val decoded = GoodPostCodec.decodeAds(prefs.getString(ADMIN_ADS_KEY, null))
        if (decoded.isEmpty()) return null
        return CachedAds(decoded, prefs.getLong(ADMIN_ADS_KEY + KEY_SAVED_AT, 0L))
    }

    // ── The external channel directory ───────────────────────────────────

    /**
     * Cache the whole directory.
     *
     * Written on every successful read, including a reader-triggered refresh, so
     * the next open is instant and the screen works offline. Age is not used to
     * expire it — the server list changes rarely, and stale-but-readable beats an
     * empty screen — the [GoodPostRepository] lets the UI label the saved copy.
     */
    fun saveDirectory(snapshot: DirectorySnapshot, nowMs: Long) {
        prefs.edit()
            .putString(DIRECTORY_KEY, DirectoryCodec.encode(snapshot))
            .putLong(DIRECTORY_KEY + KEY_SAVED_AT, nowMs)
            .apply()
    }

    fun loadDirectory(): CachedDirectory? {
        val decoded = DirectoryCodec.decode(prefs.getString(DIRECTORY_KEY, null))
        if (decoded == null || decoded.isEmpty) return null
        return CachedDirectory(decoded, prefs.getLong(DIRECTORY_KEY + KEY_SAVED_AT, 0L))
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
        followedPrefs.edit().clear().apply()
    }
}
