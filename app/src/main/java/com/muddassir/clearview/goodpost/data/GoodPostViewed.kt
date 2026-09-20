package com.muddassir.clearview.goodpost.data

import android.content.Context

/**
 * The posts this device has already had counted as read (§9).
 *
 * ## Why this exists
 *
 * Reporting a view is a write — `POST .../posts/views` — and the client used to
 * keep its own dedupe set in memory only. That works for the length of one run
 * and fails the moment the app is closed: the next cold start has an empty set,
 * every post on the first page looks unseen, and the counter on a post that
 * nobody new has opened goes up by one simply because the phone was restarted.
 * A reader watching their own channel sees a view count that climbs on its own.
 *
 * A view is a fact about a READER rather than about a run, so the record of it
 * belongs somewhere that outlives the process. There is no reader account to hang
 * it from — a reader of Good Post has no account at all (§3) — so the honest
 * place is this device, exactly as [GoodPostHidden] and [GoodPostStarred] are.
 *
 * ## What this deliberately is not
 *
 * It is not a read receipt and not a per-reader view table. The server counts
 * views and nothing asks WHO read an update; this is only the client keeping
 * itself from asking the same question twice. It also never reports a retroactive
 * correction: forgetting an id (the cap below) can cost one extra count on a post
 * somebody reads again much later, never a count that was not earned.
 *
 * The set is bounded because a Preferences file is not a database: the newest
 * [LIMIT] ids are kept and the oldest fall off. An id is 36 bytes, so the cap is a
 * few tens of kilobytes rather than a growing file.
 */
internal class GoodPostViewed(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every post id this device has already had counted. */
    fun ids(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    /** Whether this device has already counted [postId]. */
    fun seen(postId: String): Boolean = postId in ids()

    /**
     * Record ids as counted, newest first, dropping the oldest past [LIMIT].
     *
     * Ordering matters the same way it does for hidden posts: when the cap is
     * reached it should be the most recent reads that survive, because those are
     * the posts most likely to be opened again in the same session.
     */
    fun mark(postIds: Collection<String>) {
        if (postIds.isEmpty()) return
        val merged = LinkedHashSet<String>()
        merged.addAll(postIds)
        merged.addAll(ids())
        val capped = merged.toList().take(LIMIT).toSet()
        prefs.edit().putStringSet(KEY, capped).apply()
    }

    /** Everything is unseen again. Nothing calls it yet; it is here for a reset. */
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "goodpost_viewed"
        const val KEY = "post_ids"
        const val LIMIT = 1000
    }
}
