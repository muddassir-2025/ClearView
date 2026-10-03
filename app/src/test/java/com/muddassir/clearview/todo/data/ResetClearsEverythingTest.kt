package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.ReminderConfig
import com.muddassir.clearview.todo.model.TodoBehavior
import com.muddassir.clearview.todo.model.TodoEvent
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The bottom "Reset" is the one destructive action in the feature, so what it
 * promises has to be true for EVERY surface that reads progress — not just the
 * tick marks. It is a FULL reset: it deletes the todos themselves, so the
 * feature is left in exactly the state a fresh install starts in — no plans, no
 * completions, no attempts or logged time, no history, nothing for the progress
 * bar, calendar, bar graph or heatmap to draw, and no "13 due / 2 left today"
 * left over from todos the reader just wiped.
 *
 * These assertions are the contract: after a reset, every count, score, streak,
 * calendar day and heatmap square reads zero because there is nothing left to
 * count.
 */
class ResetClearsEverythingTest {

    private val start = LocalDate.of(2026, 9, 1)
    private val day2 = start.plusDays(1)
    private val day3 = start.plusDays(2)
    private val today = start.plusDays(3)

    private fun millis(day: LocalDate, hour: Int): Long =
        day.atStartOfDay(ZoneId.systemDefault()).plusHours(hour.toLong()).toInstant().toEpochMilli()

    /** A todo with EVERY kind of progress record: completions, attempts, time. */
    private val items = listOf(
        TodoItem(
            id = "plain",
            title = "plain",
            type = TodoType.PERMANENT,
            startDateEpochDay = start.toEpochDay(),
            reminder = ReminderConfig(listOf(9 * 60), repeat = true),
            completions = mapOf(
                start.toEpochDay() to millis(start, 9),
                day2.toEpochDay() to millis(day2, 9),
                today.toEpochDay() to millis(today, 9)
            ),
            events = listOf(
                TodoEvent.Completed(millis(start, 9), start.toEpochDay()),
                TodoEvent.Completed(millis(day2, 9), day2.toEpochDay()),
                TodoEvent.Completed(millis(today, 9), today.toEpochDay())
            )
        ),
        TodoItem(
            id = "attempted",
            title = "attempted",
            type = TodoType.PERMANENT,
            startDateEpochDay = start.toEpochDay(),
            behavior = TodoBehavior.ATTEMPTED,
            events = listOf(TodoEvent.Attempted(millis(day3, 10), day3.toEpochDay()))
        ),
        TodoItem(
            id = "timed",
            title = "timed",
            type = TodoType.PERMANENT,
            startDateEpochDay = start.toEpochDay(),
            behavior = TodoBehavior.TIME,
            targetDurationMinutes = 60,
            events = listOf(TodoEvent.TimeAdded(millis(day2, 11), day2.toEpochDay(), 30))
        ),
        TodoItem(
            id = "oneoff",
            title = "oneoff",
            type = TodoType.TEMPORARY,
            startDateEpochDay = day2.toEpochDay(),
            endDateEpochDay = day2.toEpochDay(),
            completions = mapOf(day2.toEpochDay() to millis(day2, 20))
        )
    )

    private fun before(): ResetSnapshot {
        val days = start.datesUntil(today.plusDays(1)).toList()
        return ResetSnapshot(
            items = items,
            week = TodoStats.weekStats(items, today, millis(today, 23)),
            heatmap = TodoStats.heatmapSummary(
                TodoStats.productivityHeatmap(items, days, millis(today, 23))
            ),
            month = TodoStats.monthStats(items, YearMonth.from(today), today),
            historyCompleted = TodoCodec.historyCompleted(items, today, millis(today, 23)),
            historyMissed = TodoCodec.historyMissed(items, today, millis(today, 23)),
            completedFilter = TodoCodec.filter(items, TodoFilter.COMPLETED, today, millis(today, 23))
        )
    }

    private data class ResetSnapshot(
        val items: List<TodoItem>,
        val week: TodoStats.WeekStats,
        val heatmap: TodoStats.HeatmapSummary,
        val month: TodoStats.MonthStats,
        val historyCompleted: List<TodoCodec.HistoryEntry>,
        val historyMissed: List<TodoCodec.HistoryEntry>,
        val completedFilter: List<TodoItem>
    )

    @Test
    fun `the surfaces really do hold data before the reset`() {
        // Guards the test itself: if the fixture ever stopped producing data, the
        // zero assertions below would pass for the wrong reason.
        val before = before()
        assertTrue("completions", before.heatmap.completed > 0)
        assertTrue("active days", before.heatmap.activeDays > 0)
        assertTrue("streak", before.heatmap.maxStreak > 0)
        assertTrue("week score", before.week.completed > 0)
        assertTrue("week due", before.week.due > 0)
        assertTrue("history", before.historyCompleted.isNotEmpty())
        assertTrue("month", before.month.completed > 0)
        assertTrue("completed filter", before.completedFilter.isNotEmpty())
    }

    @Test
    fun `reset deletes every todo — nothing survives to be counted`() {
        val reset = TodoCodec.resetEverything()
        assertTrue("no todo may survive a reset", reset.isEmpty())
        assertTrue(TodoCodec.visibleItems(reset, today).isEmpty())
        assertTrue(TodoCodec.historySorted(reset, today).isEmpty())
        assertTrue(TodoCodec.filter(reset, TodoFilter.TODAY, today).isEmpty())
        assertTrue(TodoCodec.filter(reset, TodoFilter.ALL, today).isEmpty())
    }

    @Test
    fun `every surface reads nothing after a reset`() {
        val reset = TodoCodec.resetEverything()
        val now = millis(today, 23)
        val days = start.datesUntil(today.plusDays(1)).toList()

        val week = TodoStats.weekStats(reset, today, now)
        assertEquals("weekly completions", 0, week.completed)
        // Nothing is due at all any more, so the week has nothing to measure.
        assertEquals("weekly due", 0, week.due)
        assertNull("weekly score", week.score)
        assertEquals("weekly streak", 0, week.streak)
        assertEquals("active days this week", 0, week.activeDays)
        assertEquals("left today", 0, week.remainingToday)

        val insights = TodoStats.weekInsights(reset, today, now)
        assertEquals("insight completions", 0, insights.completed)
        assertEquals("insight due", 0, insights.due)
        assertNull("insight score", insights.score)

        val monthInsights = TodoStats.monthInsights(reset, today, now)
        assertEquals("monthly insight completions", 0, monthInsights.completed)
        assertEquals("monthly insight due", 0, monthInsights.due)
        assertNull("monthly insight score", monthInsights.score)

        val heatmap = TodoStats.heatmapSummary(TodoStats.productivityHeatmap(reset, days, now))
        assertEquals("heatmap completions", 0, heatmap.completed)
        assertEquals("heatmap active days", 0, heatmap.activeDays)
        assertEquals("heatmap streak", 0, heatmap.maxStreak)
        // No square may be shaded: the grid shades a day it earned something on,
        // so a single nonzero level would mean a statistic survived the reset.
        TodoStats.productivityHeatmap(reset, days, now).forEach { day ->
            assertEquals("${day.date} is still shaded", 0, day.level)
        }

        assertEquals(
            "month completions",
            0,
            TodoStats.monthStats(reset, YearMonth.from(today), today).completed
        )
        assertEquals("history (completed)", 0, TodoCodec.historyCompleted(reset, today, now).size)
        assertEquals("history (missed)", 0, TodoCodec.historyMissed(reset, today, now).size)
        assertEquals(
            "completed filter",
            0,
            TodoCodec.filter(reset, TodoFilter.COMPLETED, today, now).size
        )
        assertEquals(
            "no earned points anywhere",
            0f,
            TodoStats.earnedPoints(reset, start, today),
            0.0001f
        )
    }

    @Test
    fun `a todo added after a reset is a genuine fresh start`() {
        // The reset is a clean slate, not a broken store: a todo created after
        // it behaves exactly like the very first one.
        val reset = TodoCodec.resetEverything()
        assertTrue(reset.isEmpty())
        val fresh = TodoCodec.added(
            reset,
            TodoItem(
                id = "fresh",
                title = "fresh",
                type = TodoType.PERMANENT,
                startDateEpochDay = today.toEpochDay()
            )
        )
        val done = TodoCodec.toggled(fresh, "fresh", today, millis(today, 8)).first
        val week = TodoStats.weekStats(done, today, millis(today, 23))
        assertEquals("the new todo's completion counts", 1, week.completed)
        assertEquals("and nothing else is due", 1, week.due)
        assertEquals("history shows only the new todo", 1, TodoCodec.historyCompleted(done, today).size)
    }
}
