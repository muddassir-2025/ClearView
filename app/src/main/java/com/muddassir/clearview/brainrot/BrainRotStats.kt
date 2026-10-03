package com.muddassir.clearview.brainrot

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * One recorded protection event: something was blocked, and why.
 *
 * Kept deliberately small and dumb — it is written from the accessibility hot
 * path on every block, and it is the only thing the Activity dashboard reads.
 * [category] is a short label ("Adult", "Brain Rot", "Strict Mode", "Custom
 * Website", "Search"); [keyword] and [channel] are the explanation shown back
 * to the user ("This content was blocked because it matched: X").
 */
data class BlockEvent(
    val atMs: Long,
    val category: String,
    val keyword: String? = null,
    val channel: String? = null
)

/**
 * What the Activity section shows. Every field is derived from the recorded
 * events plus the current time — nothing here is stored, so the numbers can
 * never drift out of sync with the events they summarise.
 */
data class BrainRotSummary(
    val totalBlocks: Int = 0,
    val todayBlocks: Int = 0,
    val weekBlocks: Int = 0,
    val topKeywords: List<Pair<String, Int>> = emptyList(),
    val topChannels: List<Pair<String, Int>> = emptyList(),
    val blocksByCategory: List<Pair<String, Int>> = emptyList(),
    /** Consecutive days (ending today or yesterday) with at least one block. */
    val streakDays: Int = 0
)

/**
 * Pure aggregation for the Activity dashboard.
 *
 * Everything is a function of (events, now, day boundaries) with no Android
 * dependency and no clock of its own, so the numbers the user sees can be
 * tested directly instead of only being trusted because they looked right on
 * one phone. The day boundary is a parameter because "today" is a local-time
 * concept and a test cannot rely on the machine's timezone.
 */
object BrainRotStats {

    /** How many events are kept. Enough for the dashboard, bounded on disk. */
    const val MAX_EVENTS = 500

    /** Top-N lists shown in the dashboard. */
    private const val TOP_N = 5

    /** Millis in a day — used for the week/streak windows. */
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Start-of-day index for [atMs] in [timeZone]. Two timestamps share a day
     * index exactly when they fall on the same local calendar day, which is
     * what makes "today" and the streak correct across DST changes.
     */
    fun dayIndex(atMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = atMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Summarise [events] as of [nowMs].
     *
     *  - today  = events on the same local day as now.
     *  - week   = events within the last 7 local days (including today).
     *  - streak = consecutive local days with at least one block, counted
     *    backwards from today. A day with no blocks yet does NOT break the
     *    streak — a user who has not browsed since yesterday is still on it.
     */
    fun summarise(
        events: List<BlockEvent>,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): BrainRotSummary {
        if (events.isEmpty()) return BrainRotSummary()

        val today = dayIndex(nowMs, timeZone)
        val weekStart = today - 6 * DAY_MS

        var todayCount = 0
        var weekCount = 0
        val keywordCounts = HashMap<String, Int>()
        val channelCounts = HashMap<String, Int>()
        val categoryCounts = HashMap<String, Int>()
        val activeDays = HashSet<Long>()

        for (e in events) {
            val day = dayIndex(e.atMs, timeZone)
            if (day == today) todayCount++
            if (day >= weekStart) weekCount++
            activeDays.add(day)

            e.keyword?.takeIf { it.isNotBlank() }?.let { kw ->
                val key = kw.lowercase(Locale.ROOT)
                keywordCounts[key] = (keywordCounts[key] ?: 0) + 1
            }
            e.channel?.takeIf { it.isNotBlank() }?.let { ch ->
                val key = ch.lowercase(Locale.ROOT)
                channelCounts[key] = (channelCounts[key] ?: 0) + 1
            }
            if (e.category.isNotBlank()) {
                categoryCounts[e.category] = (categoryCounts[e.category] ?: 0) + 1
            }
        }

        // Streak: walk back from today. Today not counting yet is allowed —
        // the first missing day ends the run only if it is not today.
        var streak = 0
        var cursor = today
        var first = true
        while (true) {
            if (activeDays.contains(cursor)) {
                streak++
            } else if (!first) {
                break
            }
            first = false
            cursor -= DAY_MS
            // Hard stop: no event can predate the oldest one.
            if (streak > MAX_EVENTS) break
        }

        return BrainRotSummary(
            totalBlocks = events.size,
            todayBlocks = todayCount,
            weekBlocks = weekCount,
            topKeywords = topN(keywordCounts),
            topChannels = topN(channelCounts),
            blocksByCategory = topN(categoryCounts),
            streakDays = streak
        )
    }

    /**
     * Highest-count entries first. Ties break alphabetically so the dashboard
     * never reshuffles between two identical scans.
     */
    private fun topN(counts: Map<String, Int>): List<Pair<String, Int>> =
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_N)
            .map { it.key to it.value }

    /**
     * Append [event] to [events], keeping only the newest [MAX_EVENTS]. Pure so
     * the trim rule is testable; the repository just stores the result.
     */
    fun append(events: List<BlockEvent>, event: BlockEvent): List<BlockEvent> {
        val out = ArrayList<BlockEvent>(events.size + 1)
        out.addAll(events)
        out.add(event)
        return if (out.size <= MAX_EVENTS) out else out.subList(out.size - MAX_EVENTS, out.size).toList()
    }
}
