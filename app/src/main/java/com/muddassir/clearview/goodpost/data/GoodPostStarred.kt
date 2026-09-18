package com.muddassir.clearview.goodpost.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The messages this device has starred (§9).
 *
 * ## Why this is local, and why it stores a COPY
 *
 * A star is one reader's private bookmark. The two honest places to keep it are
 * the server, keyed on the reader's Firebase uid, or this device. It is here for
 * a reason that shows up on the screen it is shown on: a starred message is
 * opened from the channel's information page, often days later, and a starred
 * list that held only post IDS would have to re-fetch each one to draw — thirty
 * round trips to render a page of bookmarks, most of them 404s once the server's
 * retention window has passed (§14).
 *
 * So the row is kept whole: what it said, when it said it, and which channel it
 * came from. That also makes a star survive the post it points at, which is the
 * behaviour a bookmark is supposed to have — the words are the point, not the
 * row.
 *
 * The URL of an attached image is deliberately NOT kept. A signed URL expires
 * within the hour, so a stored one is a broken image waiting to be drawn; the
 * starred row says what the post was and how to get back to it, and the channel
 * is one tap away.
 *
 * Bounded, because a Preferences file is not a database: the newest
 * [LIMIT] stars are kept and the oldest fall off. A list nobody scrolls that far
 * back through is not worth an unbounded file.
 */
internal class GoodPostStarred(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Everything starred, newest star first. */
    fun all(): List<GoodPostStarredEntry> = read()

    /** The stars that belong to one channel (§11's information page). */
    fun forChannel(channelId: String): List<GoodPostStarredEntry> =
        read().filter { it.channelId == channelId }

    fun isStarred(postId: String): Boolean = read().any { it.postId == postId }

    /**
     * Star the post, or take the star back. Returns the state afterwards.
     *
     * Idempotent in both directions: starring twice replaces the row rather than
     * producing two, and unstarring something that was never starred is a no-op.
     */
    fun toggle(entry: GoodPostStarredEntry): Boolean {
        val current = read()
        val existing = current.any { it.postId == entry.postId }
        val next = if (existing) {
            current.filterNot { it.postId == entry.postId }
        } else {
            (listOf(entry.copy(starredAt = System.currentTimeMillis())) + current)
                .take(LIMIT)
        }
        write(next)
        return !existing
    }

    fun remove(postId: String) {
        write(read().filterNot { it.postId == postId })
    }

    // ── Storage ─────────────────────────────────────────────────────────

    private fun read(): List<GoodPostStarredEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val postId = o.optString("postId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                GoodPostStarredEntry(
                    postId = postId,
                    channelId = o.optString("channelId"),
                    channelName = o.optString("channelName"),
                    body = o.optString("body").takeIf { it.isNotBlank() && it != "null" },
                    kind = o.optString("kind", "text"),
                    createdAt = o.optString("createdAt"),
                    starredAt = o.optLong("starredAt")
                )
            }
        } catch (e: Exception) {
            // A file written by an older build, or a partial write. Empty is the
            // right answer: a star list that cannot be read is not a reason to
            // fail the screen it is on, and the next star rewrites it.
            emptyList()
        }
    }

    private fun write(entries: List<GoodPostStarredEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("postId", entry.postId)
                    put("channelId", entry.channelId)
                    put("channelName", entry.channelName)
                    put("body", entry.body ?: "")
                    put("kind", entry.kind)
                    put("createdAt", entry.createdAt)
                    put("starredAt", entry.starredAt)
                }
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "goodpost_starred"
        const val KEY = "entries"
        const val LIMIT = 200
    }
}

/**
 * One starred message, as much of it as a list needs to draw it.
 *
 * Top level rather than nested inside [GoodPostStarred], and public rather than
 * internal, because it travels in the tab's UI state — which is public, and a
 * public type cannot expose an internal one. The store that writes it stays
 * internal: nothing outside this package has any business touching the file.
 */
data class GoodPostStarredEntry(
    val postId: String,
    val channelId: String,
    val channelName: String,
    val body: String?,
    val kind: String,
    val createdAt: String,
    val starredAt: Long
)
