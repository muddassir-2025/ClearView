package com.muddassir.clearview.media.data

import android.content.Context

/**
 * Snapshot of a video's watch state: the watched fraction (0..1, the
 * long-standing card indicator) plus the real resume position and total
 * duration in seconds (used by Continue Watching).
 */
data class VideoProgress(
    val fraction: Float,
    val positionSeconds: Long,
    val durationSeconds: Long
)

/**
 * Persists how much of each video the user has watched (a fraction 0..1), so
 * the Media tab can render a progress bar on the thumbnail and a "Watched"
 * badge for completed videos.
 *
 * Keyed by the YouTube video id in a single SharedPreferences file; values are
 * plain floats (fraction of the total duration) plus the resume position and
 * duration (seconds). Cheap synchronous reads are fine — the Media tab reads
 * one value per card per composition.
 *
 * Written by [com.muddassir.clearview.media.ui.VideoPlayerScreen] from the
 * player's real `getCurrentTime()/getDuration()` reports (every ~5 s while
 * playing, plus once on pause/end).
 */
class WatchProgressStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The stored fraction (0..1) for [videoId], or null if never watched. */
    fun get(videoId: String): Float? =
        if (prefs.contains(videoId)) prefs.getFloat(videoId, -1f).takeIf { it >= 0f } else null

    /** Persists [fraction] (clamped to 0..1). Negative values are ignored. */
    fun set(videoId: String, fraction: Float) {
        if (fraction < 0f) return
        prefs.edit()
            .putFloat(videoId, fraction.coerceIn(0f, 1f))
            .remove(CONTINUE_DISMISSED_PREFIX + videoId)
            .apply()
        bump()
    }

    /** The full watch snapshot for [videoId], or null if never watched. */
    fun getProgress(videoId: String): VideoProgress? {
        val fraction = get(videoId) ?: return null
        return VideoProgress(
            fraction = fraction,
            positionSeconds = prefs.getLong(POS_PREFIX + videoId, 0L),
            durationSeconds = prefs.getLong(DUR_PREFIX + videoId, 0L)
        )
    }

    /** Persists fraction + resume position + duration in one write. */
    fun setProgress(
        videoId: String,
        fraction: Float,
        positionSeconds: Long,
        durationSeconds: Long
    ) {
        if (fraction < 0f) return
        prefs.edit()
            .putFloat(videoId, fraction.coerceIn(0f, 1f))
            .putLong(POS_PREFIX + videoId, positionSeconds.coerceAtLeast(0L))
            .putLong(DUR_PREFIX + videoId, durationSeconds.coerceAtLeast(0L))
            .remove(CONTINUE_DISMISSED_PREFIX + videoId)
            .apply()
        bump()
    }

    /** True once ~90% or more of the video was watched. */
    fun isWatched(videoId: String): Boolean = (get(videoId) ?: 0f) >= WATCHED_THRESHOLD

    fun remove(videoId: String) {
        prefs.edit()
            .remove(videoId)
            .remove(POS_PREFIX + videoId)
            .remove(DUR_PREFIX + videoId)
            .remove(CONTINUE_DISMISSED_PREFIX + videoId)
            .apply()
        bump()
    }

    /** Hides a partially watched item from Continue Watching without changing progress. */
    fun dismissFromContinueWatching(videoId: String) {
        prefs.edit().putBoolean(CONTINUE_DISMISSED_PREFIX + videoId, true).apply()
        bump()
    }

    /** True when the user explicitly removed this item from Continue Watching. */
    fun isDismissedFromContinueWatching(videoId: String): Boolean =
        prefs.getBoolean(CONTINUE_DISMISSED_PREFIX + videoId, false)

    /**
     * Empties Continue Watching: every video that was started but not finished
     * loses its resume position, so the row starts again from nothing.
     *
     * FINISHED videos are deliberately left alone. Their progress is not what
     * the row shows — it is the "Watched" badge and the full progress bar on
     * the card itself — so clearing it would silently undo the one piece of
     * state the reader did not ask to touch. Dismissals go too: they are a
     * hide-without-erasing, and once the thing being hidden is gone the marker
     * is just dead state.
     *
     * Returns how many items were cleared, so the caller can say so rather than
     * leaving the reader to guess whether the tap did anything.
     */
    fun clearContinueWatching(): Int {
        // prefs.all() is a snapshot, so the keys can be inspected while the
        // edit below is being built.
        val partial = prefs.all.keys.filter { key ->
            !key.startsWith(POS_PREFIX) &&
                !key.startsWith(DUR_PREFIX) &&
                !key.startsWith(CONTINUE_DISMISSED_PREFIX) &&
                when (val value = prefs.all[key]) {
                    is Float -> value >= 0f && value < WATCHED_THRESHOLD
                    else -> false
                }
        }
        val dismissed = prefs.all.keys.filter { it.startsWith(CONTINUE_DISMISSED_PREFIX) }
        if (partial.isEmpty() && dismissed.isEmpty()) return 0

        val editor = prefs.edit()
        partial.forEach { id ->
            editor.remove(id)
            editor.remove(POS_PREFIX + id)
            editor.remove(DUR_PREFIX + id)
            editor.remove(CONTINUE_DISMISSED_PREFIX + id)
        }
        dismissed.forEach { editor.remove(it) }
        editor.apply()
        bump()
        return partial.size
    }

    companion object {
        const val PREFS_NAME = "media_watch_progress"
        const val WATCHED_THRESHOLD = 0.9f
        const val POS_PREFIX = "pos_"
        const val DUR_PREFIX = "dur_"
        private const val CONTINUE_DISMISSED_PREFIX = "continue_dismissed_"
        private val _revisionFlow = kotlinx.coroutines.flow.MutableStateFlow(0)
        val revisionFlow: kotlinx.coroutines.flow.StateFlow<Int> = _revisionFlow
        fun bumpRevision() { _revisionFlow.value++ }
    }
    private fun bump() { bumpRevision() }
}
