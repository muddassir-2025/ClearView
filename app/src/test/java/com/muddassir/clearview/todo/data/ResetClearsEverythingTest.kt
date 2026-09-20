package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.ReminderConfig
import com.muddassir.clearview.todo.model.TodoBehavior
import com.muddassir.clearview.todo.model.TodoEvent
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The bottom "Reset everything" is the one destructive action in the feature, so
 * what it promises has to be true for EVERY surface that reads progress — not
 * just the tick marks. Resetting only the completions, for instance, left the
 * attempt and logged-time statistics (and so the heatmap's shading) alive.
 *
 * These assertions are the contract: after a reset, every count, score, streak,
 * calendar day and heatmap square reads zero, while the todos themselves are
 * untouched and still work the next day.
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
        assertTrue("history", before.historyCompleted.isNotEmpty())
        assertTrue("month", before.month.completed > 0)
        assertTrue("completed filter", before.completedFilter.isNotEmpty())
    }

    @Test
    fun `reset wipes every record — completions, attempts and logged time`() {
        val reset = TodoCodec.resetHistory(items, today)
        reset.forEach { item ->
            assertTrue("${item.id} kept completions", item.completions.isEmpty())
            assertTrue("${item.id} kept events", item.events.isEmpty())
            assertEquals(
                "${item.id} must hide its past completed days",
                today.toEpochDay() + 1,
                item.completedClearedBefore
            )
            assertEquals(
                "${item.id} must hide its past missed days",
                today.toEpochDay() + 1,
                item.missedClearedBefore
            )
        }
        // The plans themselves are NOT touched: resetting progress must not
        // delete or reschedule the reader's todos.
        assertEquals(items.size, reset.size)
        reset.zip(items).forEach { (after, original) ->
            assertEquals(original.id, after.id)
            assertEquals(original.title, after.title)
            assertEquals(original.startDateEpochDay, after.startDateEpochDay)
            assertEquals(original.behavior, after.behavior)
            assertEquals(original.reminder, after.reminder)
        }
    }

    @Test
    fun `every surface reads zero after a reset`() {
        val reset = TodoCodec.resetHistory(items, today)
        val now = millis(today, 23)
        val days = start.datesUntil(today.plusDays(1)).toList()

        val week = TodoStats.weekStats(reset, today, now)
        assertEquals("weekly completions", 0, week.completed)
        // The week still had things DUE (the plans survive), so its score is a
        // real zero rather than the "nothing to measure" null.
        assertEquals("weekly score", 0, week.score)
        assertEquals("weekly streak", 0, week.streak)
        assertEquals("active days this week", 0, week.activeDays)

        val heatmap = TodoStats.heatmapSummary(TodoStats.productivityHeatmap(reset, days, now))
        assertEquals("heatmap completions", 0, heatmap.completed)
        assertEquals("heatmap active days", 0, heatmap.activeDays)
        assertEquals("heatmap streak", 0, heatmap.maxStreak)
        // No square may be shaded: the grid shades a day it earned something on,
        // so a single nonzero level would mean a statistic survived the reset.
        TodoStats.productivityHeatmap(reset, days, now).forEach { day ->
            assertEquals("${day.date} is still shaded", 0, day.level)
        }

        assertEquals("month completions", 0, TodoStats.monthStats(reset, YearMonth.from(today), today).completed)
        assertEquals(
            "history (completed)",
            0,
            TodoCodec.historyCompleted(reset, today, now).size
        )
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
    fun `a reset todo still works the next day`() {
        // A reset is a fresh start, not a broken todo: tomorrow's completion
        // counts, and only the wiped days stay wiped.
        val reset = TodoCodec.resetHistory(items, today)
        val tomorrow = today.plusDays(1)
        val done = TodoCodec.toggled(reset, "plain", tomorrow, millis(tomorrow, 8)).first

        val week = TodoStats.weekStats(done, tomorrow, millis(tomorrow, 23))
        assertEquals("tomorrow's completion counts", 1, week.completed)
        assertTrue(
            "yesterday's wiped completions stay gone",
            TodoCodec.historyCompleted(done, tomorrow, millis(tomorrow, 23))
                .none { it.lastOccurrence.isBefore(today) }
        )
    }
}
