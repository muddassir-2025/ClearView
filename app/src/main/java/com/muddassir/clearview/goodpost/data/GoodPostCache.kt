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
        const val KEY_MUTED = "muted_channels"
        const val POSTS_PREFIX = "posts:"

        /** Enough to browse offline; small enough to stay off the heap. */
        const val MAX_CHANNELS = 200
        const val MAX_POSTS_PER_CHANNEL = 60
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

    // ── Notification preference (§14) ────────────────────────────────────
    //
    // Device-local, and deliberately so. A reader of Good Post has no account,
    // so there is nowhere else this preference could live — and there is no push
    // infrastructure behind it yet, which the channel page says on the switch
    // itself rather than pretending otherwise.

    fun mutedChannelIds(): Set<String> =
        prefs.getStringSet(KEY_MUTED, emptySet())?.toSet() ?: emptySet()

    fun setChannelMuted(channelId: String, muted: Boolean) {
        val next = mutedChannelIds().toMutableSet()
        if (muted) next.add(channelId) else next.remove(channelId)
        // A copy is written rather than the mutated instance: `SharedPreferences`
        // keeps the set it was given by reference, so handing it one that is
        // still being mutated is a documented way to lose the edit.
        prefs.edit().putStringSet(KEY_MUTED, next.toSet()).apply()
    }

    // ── Categories (§7) ──────────────────────────────────────────────────

    fun saveCategories(categories: List<GoodPostCategory>) {
        prefs.edit()
            .putString(KEY_CATEGORIES, GoodPostCodec.encodeCategories(categories))
            .apply()
    }

    fun loadCategories(): List<GoodPostCategory> =
        GoodPostCodec.decodeCategories(prefs.getString(KEY_CATEGORIES, null))

    /**
     * Drop everything.
     *
     * Called when an administrator signs out, and when the install's data is
     * reset: a cached list belongs to whoever was last reading, and leaving it
     * for the next person to open the tab would show them someone else's view
     * without any indication that it is stale.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
