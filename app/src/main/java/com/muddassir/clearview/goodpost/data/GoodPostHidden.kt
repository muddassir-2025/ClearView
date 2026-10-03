package com.muddassir.clearview.goodpost.data

import android.content.Context
import org.json.JSONObject

/**
 * The updates this reader deleted FOR THEMSELVES (§5, §9) — and the CHANNELS
 * they hid (§8).
 *
 * ## What "delete for me" can mean here
 *
 * There is no per-reader copy of a post on the server to delete: a reader has no
 * account, and Good Post's posts are one row that everybody reads. So the honest
 * meaning of "delete for me" is the one WhatsApp gives it — it goes out of MY
 * view — and the only place that can be true is this device.
 *
 * That makes it a set of ids, applied when a list is drawn, and it is stored
 * rather than kept in memory for the case that matters: a feed cache means the
 * post comes back on the next cold start, and a deleted message that reappears
 * tomorrow is worse than not offering the button.
 *
 * ## Hiding a whole channel (§8)
 *
 * The same promise one level up: hiding a channel removes that channel and ALL
 * of its posts from this device's feeds, hides its future posts, and survives a
 * restart. It follows the Media app's channel-hiding behavior, and it is stored
 * here beside the hidden posts because both are "the reader chose not to see
 * this any more on this phone".
 *
 * The channel's NAME is kept with its id so the management surface can list a
 * hidden channel the reader no longer sees in their follows. It is a label for a
 * row the reader hid, not a copy of the channel.
 *
 * ## What it deliberately does not do
 *
 * It does not touch the server, and it cannot: a reader who hides an update has
 * changed nothing for anybody else, which is exactly the promise the wording
 * makes. Removing it for everyone is the administrator's action, and it is a
 * different button with a different confirmation.
 */
internal class GoodPostHidden(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every hidden post id on this device. */
    fun ids(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun isHidden(postId: String): Boolean = postId in ids()

    /**
     * Hide these posts, newest first, dropping the oldest past [LIMIT].
     *
     * Ordering matters for the same reason it does for stars: when the cap is
     * reached it should be the most recent deletions that survive, because those
     * are the ones the reader would notice coming back.
     */
    fun hide(postIds: Collection<String>) {
        if (postIds.isEmpty()) return
        val merged = LinkedHashSet<String>()
        merged.addAll(postIds)
        merged.addAll(ids())
        val capped = merged.toList().take(LIMIT).toSet()
        prefs.edit().putStringSet(KEY, capped).apply()
    }

    /** Forget one id. Used when a post turns out to be somebody else's to hide. */
    fun unhide(postId: String) {
        val next = ids() - postId
        prefs.edit().putStringSet(KEY, next).apply()
    }

    /** Everything is visible again. Offered in Settings; nothing calls it yet. */
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    // ── Hidden channels (§8) ─────────────────────────────────────────────

    /** Every hidden channel on this device: id → the name to show in the manager. */
    fun channels(): Map<String, String> {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { key -> o.optString(key, "") }
        }.getOrDefault(emptyMap())
    }

    fun isChannelHidden(channelId: String): Boolean = channelId in channels()

    /** Hide a channel, keeping its name so the manager can name it back. */
    fun hideChannel(channelId: String, name: String) {
        if (channelId.isBlank()) return
        val next = LinkedHashMap(channels())
        next[channelId] = name
        // Cap the store: a Preferences file is not a database, and only the most
        // recently hidden channels are likely to be wanted back.
        saveChannels(next.entries.toList().takeLast(LIMIT).map { it.key to it.value })
    }

    fun unhideChannel(channelId: String) {
        val next = channels() - channelId
        saveChannels(next.toList())
    }

    fun clearChannels() {
        prefs.edit().remove(KEY_CHANNELS).apply()
    }

    private fun saveChannels(entries: List<Pair<String, String>>) {
        val o = JSONObject()
        entries.forEach { (id, name) -> o.put(id, name) }
        prefs.edit().putString(KEY_CHANNELS, o.toString()).apply()
    }

    private companion object {
        const val PREFS = "goodpost_hidden"
        const val KEY = "post_ids"
        const val KEY_CHANNELS = "channel_ids"
        const val LIMIT = 1000
    }
}
