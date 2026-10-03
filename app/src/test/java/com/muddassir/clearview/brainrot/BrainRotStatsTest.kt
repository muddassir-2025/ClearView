package com.muddassir.clearview.brainrot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The Activity dashboard's numbers.
 *
 * These are the numbers a user judges their protection by, and they are the
 * kind of thing that is easy to get subtly wrong (a "today" that drifts by
 * timezone, a streak that resets on a quiet morning). Everything is a pure
 * function of (events, now, timezone), so it can be pinned here instead of
 * being checked by eye on one phone.
 */
class BrainRotStatsTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** Fixed reference: 2026-10-03 12:00:00 UTC. */
    private val noon = 1_790_000_000_000L.let { _ ->
        // Build it explicitly so the test does not depend on a magic number
        // being what it looks like.
        val cal = java.util.Calendar.getInstance(utc)
        cal.clear()
        cal.set(2026, java.util.Calendar.OCTOBER, 3, 12, 0, 0)
        cal.timeInMillis
    }

    private val dayMs = 24L * 60 * 60 * 1000

    private fun event(daysAgo: Int, category: String = "Brain Rot", keyword: String? = null, channel: String? = null) =
        BlockEvent(atMs = noon - daysAgo * dayMs, category = category, keyword = keyword, channel = channel)

    @Test
    fun `no events is an empty summary, not a crash`() {
        val s = BrainRotStats.summarise(emptyList(), noon, utc)
        assertEquals(0, s.totalBlocks)
        assertEquals(0, s.todayBlocks)
        assertEquals(0, s.streakDays)
        assertTrue(s.topKeywords.isEmpty())
    }

    @Test
    fun `today counts only the same local day`() {
        val events = listOf(
            event(0),                       // today
            event(0),
            event(1),                       // yesterday
            event(3)
        )
        val s = BrainRotStats.summarise(events, noon, utc)
        assertEquals(2, s.todayBlocks)
        assertEquals(4, s.weekBlocks)
    }

    @Test
    fun `week window is the last seven local days including today`() {
        // Day 6 is inside, day 7 is outside.
        val events = listOf(event(0), event(6), event(7), event(30))
        val s = BrainRotStats.summarise(events, noon, utc)
        assertEquals(2, s.weekBlocks)
        assertEquals(4, s.totalBlocks)
    }

    @Test
    fun `top keywords and channels are ranked and tie-broken alphabetically`() {
        val events = listOf(
            event(0, keyword = "ai"),
            event(0, keyword = "ai"),
            event(0, keyword = "viral"),
            event(0, keyword = "AI"),           // same keyword, different case
            event(1, channel = "@a"),
            event(1, channel = "@a"),
            event(1, channel = "@b")
        )
        val s = BrainRotStats.summarise(events, noon, utc)
        assertEquals(listOf("ai" to 3, "viral" to 1), s.topKeywords)
        assertEquals(listOf("@a" to 2, "@b" to 1), s.topChannels)
    }

    @Test
    fun `blocks by category are reported`() {
        val events = listOf(
            event(0, category = "Adult"),
            event(0, category = "Adult"),
            event(1, category = "Brain Rot"),
            event(2, category = "Strict Mode")
        )
        val s = BrainRotStats.summarise(events, noon, utc)
        assertEquals(listOf("Adult" to 2, "Brain Rot" to 1, "Strict Mode" to 1), s.blocksByCategory)
    }

    @Test
    fun `streak counts consecutive days and survives a quiet today`() {
        // Blocks yesterday, the day before, and three days ago — nothing today.
        // The streak must still be 3: a user who has not browsed yet today has
        // not broken it.
        val events = listOf(event(1), event(2), event(3))
        val s = BrainRotStats.summarise(events, noon, utc)
        assertEquals(3, s.streakDays)
    }

    @Test
    fun `a missed day breaks the streak`() {
        // Today, yesterday, then a gap, then more.
        val events = listOf(event(0), event(1), event(3), event(4))
        val s = BrainRotStats.summarise(events, noon, utc)
        assertEquals(2, s.streakDays)
    }

    @Test
    fun `a single block today is a one day streak`() {
        val s = BrainRotStats.summarise(listOf(event(0)), noon, utc)
        assertEquals(1, s.streakDays)
    }

    @Test
    fun `append keeps the newest events and trims the oldest`() {
        // Stored chronologically (oldest first), like the repository writes it.
        val full = (1..BrainRotStats.MAX_EVENTS)
            .map { event(BrainRotStats.MAX_EVENTS - it) }
            .sortedBy { it.atMs }
        val oldestBefore = full.first().atMs

        val added = BrainRotStats.append(full, event(0, keyword = "newest"))

        assertEquals(BrainRotStats.MAX_EVENTS, added.size)
        // The new event is present, the oldest was dropped.
        assertEquals("newest", added.last().keyword)
        assertTrue(added.first().atMs > oldestBefore)
    }

    @Test
    fun `day index ignores the time of day`() {
        val morning = noon - 5 * 60 * 60 * 1000L
        val evening = noon + 5 * 60 * 60 * 1000L
        assertEquals(BrainRotStats.dayIndex(morning, utc), BrainRotStats.dayIndex(evening, utc))
    }
}
