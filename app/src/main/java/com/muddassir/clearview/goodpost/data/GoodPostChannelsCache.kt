package com.muddassir.clearview.goodpost.data

import android.content.Context

/**
 * A cached page, with the time it was stored.
 *
 * The timestamp is kept so the UI can say the data is saved rather than live
 * (§36). Age is deliberately NOT used to expire the cache: previously fetched
 * channel metadata staying readable offline is the requirement, and a channel
 * name does not become wrong by being a day old.
 */
data class CachedChannelPage(val page: GoodPostChannelPage, val savedAtMs: Long)

/**
 * Offline cache for channel lists (§36, §10).
 *
 * `SharedPreferences` + JSON strings, matching how the rest of ClearView
 * persists things — no Room, no DataStore (§35 forbids introducing them).
 *
 * What is cached is metadata only: names, counts, categories and the viewer's
 * own follow/mute state. No posts, no media, and nothing belonging to another
 * user — the same restraint the API itself practises (§38), applied to disk.
 *
 * The size is capped because this is written from server-provided text: without
 * a cap, paging through Discover would append forever and an unbounded
 * `SharedPreferences` entry is read into memory on every access.
 */
internal class GoodPostChannelsCache(context: Context) {

    private companion object {
        const val PREFS = "goodpost_channels_cache"
        const val KEY_FOLLOWING = "following"
        const val KEY_CATEGORIES = "categories"
        const val KEY_SAVED_AT = "saved_at"
        const val DISCOVER_PREFIX = "discover:"

        /** Enough to browse offline; small enough to stay off the heap. */
        const val MAX_CHANNELS = 200
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Following (§4) ───────────────────────────────────────────────────

    fun saveFollowing(page: GoodPostChannelPage, nowMs: Long) {
        prefs.edit()
            .putString(KEY_FOLLOWING, GoodPostChannelCodec.encodePage(page))
            .putLong(KEY_FOLLOWING + KEY_SAVED_AT, nowMs)
            .apply()
    }

    fun loadFollowing(): CachedChannelPage? = read(KEY_FOLLOWING)

    // ── Discover (§5) ────────────────────────────────────────────────────

    /**
     * Cache a Discover result under a signature of the query that produced it.
     *
     * Keyed rather than single-slot on purpose: caching only the most recent
     * search would mean that searching A, then B, then reopening A offline
     * shows nothing. The signature is derived from the same filters the API
     * takes, so two different searches cannot collide.
     */
    fun saveDiscover(signature: String, page: GoodPostChannelPage, nowMs: Long) {
        val key = DISCOVER_PREFIX + signature
        prefs.edit()
            .putString(key, GoodPostChannelCodec.encodePage(page))
            .putLong(key + KEY_SAVED_AT, nowMs)
            .apply()
    }

    fun loadDiscover(signature: String): CachedChannelPage? =
        read(DISCOVER_PREFIX + signature)

    // ── Categories (§5) ──────────────────────────────────────────────────

    fun saveCategories(categories: List<GoodPostCategory>) {
        prefs.edit()
            .putString(KEY_CATEGORIES, GoodPostChannelCodec.encodeCategories(categories))
            .apply()
    }

    fun loadCategories(): List<GoodPostCategory> =
        GoodPostChannelCodec.decodeCategories(prefs.getString(KEY_CATEGORIES, null))

    /**
     * Drop everything. Called on sign-out: a cached list is the previous
     * account's follow state, and leaving it for the next person to sign in on
     * this device would show them someone else's channels.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun read(key: String): CachedChannelPage? {
        val decoded = GoodPostChannelCodec.decodePage(prefs.getString(key, null)) ?: return null
        val capped = if (decoded.items.size <= MAX_CHANNELS) {
            decoded
        } else {
            decoded.copy(items = decoded.items.take(MAX_CHANNELS))
        }
        return CachedChannelPage(capped, prefs.getLong(key + KEY_SAVED_AT, 0L))
    }
}
