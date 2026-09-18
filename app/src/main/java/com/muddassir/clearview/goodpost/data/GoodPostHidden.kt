package com.muddassir.clearview.goodpost.data

import android.content.Context

/**
 * The updates this reader deleted FOR THEMSELVES (§5, §9).
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
 * ## Why the ids and not the posts
 *
 * A hidden post is not a bookmark and needs none of its text: nothing draws it.
 * The set is bounded because a Preferences file is not a database, and an id is
 * 36 bytes — a thousand of them is 36 KB, which is a rounding error next to one
 * cached photo.
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

    private companion object {
        const val PREFS = "goodpost_hidden"
        const val KEY = "post_ids"
        const val LIMIT = 1000
    }
}
