package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.TodoBehavior
import com.muddassir.clearview.todo.model.TodoEvent
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Monday, 2026-08-10 — "today" for every month-insights assertion. */
private val MONTH_TODAY: LocalDate = LocalDate.of(2026, 8, 10)

/** Epoch millis for [date] at [hour]:00 in the system zone. */
private fun monthAt(date: LocalDate, hour: Int): Long =
    date.atStartOfDay(ZoneId.systemDefault()).plusHours(hour.toLong()).toInstant().toEpochMilli()

private fun monthItem(
    id: String,
    start: LocalDate,
    end: LocalDate? = null,
    type: TodoType = TodoType.PERMANENT,
    behavior: TodoBehavior = TodoBehavior.NORMAL,
    completions: Map<Long, Long> = emptyMap(),
    events: List<TodoEvent> = emptyList()
): TodoItem = TodoItem(
    id = id,
    title = id,
    type = type,
    startDateEpochDay = start.toEpochDay(),
    endDateEpochDay = end?.toEpochDay(),
    behavior = behavior,
    events = events,
    completions = completions
)

class TodoMonthInsightsTest {

    private val aug1 = LocalDate.of(2026, 8, 1)
    private val aug10 = MONTH_TODAY

    /** A daily permanent todo from Aug 1, completed on the 1st and today. */
    private val daily = monthItem(
        "daily",
        start = aug1,
        completions = mapOf(
            aug1.toEpochDay() to monthAt(aug1, 8),
            aug10.toEpochDay() to monthAt(aug10, 9)
        )
    )

    @Test
    fun `month insights count only the days that have arrived`() {
        val insights = TodoStats.monthInsights(listOf(daily), MONTH_TODAY)
        assertEquals(TodoStats.TodoPeriod.MONTH, insights.period)
        // Aug 1..10: ten applicable days, two of them completed.
        assertEquals(10, insights.due)
        assertEquals(2, insights.completed)
        assertEquals(20, insights.percent)
        assertEquals(10, insights.daysWithDue)
        assertEquals(2, insights.activeDays)
        assertEquals(10, insights.activeDaysWindow)
        // Today is due and already completed.
        assertEquals(1, insights.todayDue)
        assertEquals(1, insights.todayCompleted)
        assertEquals(0, insights.remainingToday)
        assertNotNull(insights.score)
        assertTrue(insights.score!! in 1..100)
        // No earlier month had anything due, so there is no pace to compare.
        assertNull(insights.paceBaseline)
        assertNull(insights.pacePercent)
        // Consistency with the calendar's own monthly numbers.
        assertEquals(
            TodoStats.monthStats(listOf(daily), YearMonth.from(MONTH_TODAY), MONTH_TODAY).due,
            insights.due
        )
    }

    @Test
    fun `month insights are empty and unscoreable without any due todo`() {
        assertNull(TodoStats.monthInsights(emptyList(), MONTH_TODAY).score)
        val insights = TodoStats.monthInsights(emptyList(), MONTH_TODAY)
        assertEquals(0, insights.due)
        assertEquals(0, insights.completed)
        assertEquals(0, insights.percent)
        assertNull(insights.baselineCompleted)
    }

    @Test
    fun `month bars are the Monday-based weeks clipped to the month`() {
        val bars = TodoStats.monthInsights(listOf(daily), MONTH_TODAY).bars
        // Aug 2026 is Sat Aug 1 … Mon Aug 31, so six Monday-based weeks touch it.
        assertEquals(6, bars.size)
        assertEquals("first bar starts on the 1st", aug1, bars.first().start)
        assertEquals("second bar starts on the first Monday", LocalDate.of(2026, 8, 3), bars[1].start)
        assertEquals("last bar starts on Aug 31", LocalDate.of(2026, 8, 31), bars.last().start)
        assertFalse(bars.first().isFuture)
        assertTrue("the final week is entirely in the future", bars.last().isFuture)
        // Week 1 (Aug 1..2) has two applicable days, one completed.
        assertEquals(2, bars.first().due)
        assertEquals(1, bars.first().completed)
        // Week 3 (Aug 10..16) has arrived only through today.
        assertEquals(1, bars[2].due)
        assertEquals(1, bars[2].completed)
        assertTrue(bars[2].isToday)
        // A future week carries no due, so it can never render as progress.
        assertEquals(0, bars.last().due)
        assertEquals(0, bars.last().completed)
    }

    @Test
    fun `a future todo never counts towards the month`() {
        val future = monthItem("future", start = MONTH_TODAY.plusDays(1))
        val insights = TodoStats.monthInsights(listOf(future), MONTH_TODAY)
        assertEquals(0, insights.due)
        assertEquals(0, insights.completed)
        assertNull(insights.score)
    }

    @Test
    fun `month insights carry the attempted and logged-time partial credit`() {
        val attempted = monthItem(
            "attempted",
            start = aug1,
            end = aug10,
            type = TodoType.TEMPORARY,
            behavior = TodoBehavior.ATTEMPTED,
            events = listOf(TodoEvent.Attempted(monthAt(aug10, 10), aug10.toEpochDay()))
        )
        val insights = TodoStats.monthInsights(listOf(attempted), MONTH_TODAY)
        assertEquals(0, insights.completed)
        assertEquals(1, insights.partialOccurrences)
        assertTrue("an attempt lifts the ring", insights.hasPartialCredit)
        assertTrue(insights.creditRate > insights.rate)
        // The bar of the week containing today carries the attempt.
        assertTrue(insights.bars.any { it.isToday && it.attempted == 1 })
    }

    @Test
    fun `week insights mirror the weekly stats for the same day`() {
        val week = TodoStats.weekInsights(listOf(daily), MONTH_TODAY)
        val stats = TodoStats.weekStats(listOf(daily), MONTH_TODAY)
        assertEquals(TodoStats.TodoPeriod.WEEK, week.period)
        assertEquals(7, week.bars.size)
        assertEquals(stats.due, week.due)
        assertEquals(stats.completed, week.completed)
        assertEquals(stats.score, week.score)
        assertEquals(7, week.activeDaysWindow)
        assertEquals(0, week.remainingToday)
        assertEquals(1, week.todayDue)
        assertEquals(1, week.todayCompleted)
        assertTrue(week.bars.any { it.isToday })
        // Only days that have arrived carry a plan.
        assertTrue(week.bars.filter { it.isFuture }.all { it.due == 0 })
    }

    @Test
    fun `month pace compares against the same point in past months`() {
        // A daily todo since May, completed by day 10 in each earlier month:
        // May 2, June 1, July 1 → a 4/3 month-to-date baseline. August has 2 so
        // far, so this month is running 50% ahead of the reader's own pace.
        val paced = monthItem(
            "paced",
            start = LocalDate.of(2026, 5, 1),
            completions = listOf(
                LocalDate.of(2026, 5, 2),
                LocalDate.of(2026, 5, 5),
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 7, 3),
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 9)
            ).associate { it.toEpochDay() to monthAt(it, 9) }
        )
        val insights = TodoStats.monthInsights(listOf(paced), MONTH_TODAY)
        assertEquals(2, insights.completed)
        assertEquals(4f / 3f, insights.paceBaseline!!, 0.01f)
        assertEquals(50, insights.pacePercent)
    }

    @Test
    fun `month pace is behind when this month is slower than usual`() {
        val paced = monthItem(
            "paced",
            start = LocalDate.of(2026, 5, 1),
            completions = listOf(
                LocalDate.of(2026, 5, 2),
                LocalDate.of(2026, 6, 4),
                LocalDate.of(2026, 7, 6)
            ).associate { it.toEpochDay() to monthAt(it, 9) }
        )
        val insights = TodoStats.monthInsights(listOf(paced), MONTH_TODAY)
        assertEquals("nothing yet this month", 0, insights.completed)
        assertEquals(1f, insights.paceBaseline!!, 0.01f)
        assertEquals(-100, insights.pacePercent)
    }

    @Test
    fun `the twelve-month trend ends with the current month and counts only arrived days`() {
        val bars = TodoStats.monthBars(listOf(daily), MONTH_TODAY)
        assertEquals("always twelve months", 12, bars.size)
        assertEquals("oldest first", YearMonth.of(2025, 9), bars.first().month)
        assertEquals("the current month is last", YearMonth.from(MONTH_TODAY), bars.last().month)
        assertTrue(bars.last().isCurrent)
        assertFalse(bars.first().isCurrent)
        // The current month contributes only the days that have arrived.
        assertEquals(10, bars.last().due)
        assertEquals(2, bars.last().completed)
        // Months before the todo existed carry no plan at all.
        assertEquals(0, bars.first().due)
        assertEquals(0, bars.first().completed)
    }

    @Test
    fun `month bars carry the attempted outcome that tints their column`() {
        val attempted = monthItem(
            "attempted",
            start = aug1,
            end = MONTH_TODAY,
            type = TodoType.TEMPORARY,
            behavior = TodoBehavior.ATTEMPTED,
            events = listOf(TodoEvent.Attempted(monthAt(MONTH_TODAY, 10), MONTH_TODAY.toEpochDay()))
        )
        val current = TodoStats.monthBars(listOf(attempted), MONTH_TODAY).last()
        assertEquals(0, current.completed)
        assertEquals(1, current.attempted)
        assertEquals(10, current.due)
    }

    @Test
    fun `a baseline of zero is not a pace`() {
        // July had a plan but nothing was ever completed by day 10 in it, so a
        // percentage against it would be a division by nothing.
        val idle = monthItem("idle", start = LocalDate.of(2026, 7, 1))
        val insights = TodoStats.monthInsights(listOf(idle), MONTH_TODAY)
        assertNull(insights.paceBaseline)
        assertNull(insights.pacePercent)
    }
}
