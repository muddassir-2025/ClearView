package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.ReminderConfig
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** A todo on [day], optionally with a strict 9:00–10:00 window. */
private fun todo(
    day: LocalDate,
    strict: Boolean = false,
    timeStart: Int? = if (strict) 9 * 60 else null,
    timeEnd: Int? = if (strict) 10 * 60 else null,
    completions: Map<Long, Long> = emptyMap()
): TodoItem = TodoItem(
    id = "t1",
    title = "t1",
    type = TodoType.TEMPORARY,
    startDateEpochDay = day.toEpochDay(),
    endDateEpochDay = day.toEpochDay(),
    timeStartMinutes = timeStart,
    timeEndMinutes = timeEnd,
    strictInterval = strict,
    reminder = ReminderConfig(listOf(9 * 60), repeat = true),
    completions = completions
)

private fun at(day: LocalDate, minutes: Int): Long =
    day.atTime(minutes / 60, minutes % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * What every Complete tap is judged by.
 *
 * Two rules live here and they are deliberately different. The IN-APP paths (the
 * checkbox, the day dialog) lock a day whose window has closed as "can't redo".
 * A REMINDER being answered is always allowed for its own occurrence: the
 * app asked, the reader is answering, and refusing that was the reported bug.
 */
class CompletionRulesTest {

    @Test
    fun `an open window on today is completable`() {
        val day = LocalDate.of(2026, 9, 20)
        val item = todo(day, strict = true)
        assertNull(TodoCodec.completionRefusal(item, day, at(day, 9 * 60 + 30), today = day))
    }

    @Test
    fun `the in-app checkbox still refuses a window that has closed`() {
        // The lock the checkbox keeps: a missed day is not retroactively
        // completable from inside the app.
        val day = LocalDate.of(2026, 9, 20)
        val item = todo(day, strict = true)
        assertEquals(
            TodoCodec.CompletionRefusal.WINDOW_CLOSED,
            TodoCodec.completionRefusal(item, day, at(day, 10 * 60 + 5), today = day)
        )
    }

    @Test
    fun `a snoozed reminder past its window close still completes`() {
        // THE reported bug: the alarm was snoozed, the strict window ran out
        // while it was ringing, and Complete then did nothing. Answering a
        // reminder is not a redo — it is the answer the reminder was waiting
        // for — so the window lock must not apply to it.
        val day = LocalDate.of(2026, 9, 20)
        val item = todo(day, strict = true)
        assertNull(TodoCodec.reminderCompletionRefusal(item, day, today = day))
    }

    @Test
    fun `a reminder answered the next morning completes its own occurrence`() {
        // Snoozed from 11:55 PM across midnight: the occurrence is still
        // yesterday, and yesterday is the day the completion belongs to.
        // Rolling it forward to today is what pushed a one-off todo outside the
        // days it applies on and refused the completion outright.
        val day = LocalDate.of(2026, 9, 20)
        val item = todo(day)
        assertNull(
            TodoCodec.reminderCompletionRefusal(item, day, today = day.plusDays(1))
        )
    }

    @Test
    fun `a reminder still refuses a day the todo does not apply on`() {
        val day = LocalDate.of(2026, 9, 20)
        val item = todo(day)
        assertEquals(
            TodoCodec.CompletionRefusal.NOT_ACTIVE_DAY,
            TodoCodec.reminderCompletionRefusal(item, day.plusDays(1), today = day.plusDays(1))
        )
    }

    @Test
    fun `a reminder still refuses the future and the already-done`() {
        val day = LocalDate.of(2026, 9, 20)
        val item = todo(day)
        assertEquals(
            TodoCodec.CompletionRefusal.FUTURE_DAY,
            TodoCodec.reminderCompletionRefusal(item, day.plusDays(1), today = day)
        )
        assertEquals(
            TodoCodec.CompletionRefusal.ALREADY_COMPLETED,
            TodoCodec.reminderCompletionRefusal(
                todo(day, completions = mapOf(day.toEpochDay() to 1L)),
                day,
                today = day
            )
        )
    }

    @Test
    fun `the other refusals are named too`() {
        val day = LocalDate.of(2026, 9, 20)
        val item = todo(day)
        assertEquals(
            TodoCodec.CompletionRefusal.FUTURE_DAY,
            TodoCodec.completionRefusal(item, day.plusDays(1), at(day, 12 * 60), today = day)
        )
        assertEquals(
            TodoCodec.CompletionRefusal.NOT_ACTIVE_DAY,
            TodoCodec.completionRefusal(item, day.minusDays(1), at(day, 12 * 60), today = day)
        )
        assertEquals(
            TodoCodec.CompletionRefusal.ALREADY_COMPLETED,
            TodoCodec.completionRefusal(
                todo(day, completions = mapOf(day.toEpochDay() to 1L)),
                day,
                at(day, 12 * 60),
                today = day
            )
        )
    }
}
