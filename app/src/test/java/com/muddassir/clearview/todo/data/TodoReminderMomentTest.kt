package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.ReminderConfig
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * When a todo's reminder is due — the answer the notification centre's To Do
 * section is built from.
 *
 * The fixtures are copied from a real `todo_store.xml` on a device, because the
 * interesting case is not a todo with a plain time: it is the one this app
 * actually has, a permanent prayer todo that reminds through a *window*
 * (4:55–5:20) with `time` left null. A rule written against `time` alone finds
 * nothing to say about it, which is exactly the bug this pins.
 */
class TodoReminderMomentTest {

    private val day = LocalDate.of(2026, 9, 18)

    /** The stored shape of a permanent todo with a reminder window. */
    private fun windowTodo(enabled: Boolean = true) = TodoItem(
        id = "fajr",
        title = "Salah Al-Fajr with congregation",
        type = TodoType.PERMANENT,
        startDateEpochDay = day.minusDays(1).toEpochDay(),
        timeStartMinutes = 295,
        timeEndMinutes = 320,
        strictInterval = true,
        reminder = ReminderConfig(timesMinutes = listOf(295, 308, 320), repeat = true, enabled = enabled)
    )

    private fun atTime(minutes: Int) = TodoItem(
        id = "time",
        title = "DSA leetcode",
        type = TodoType.PERMANENT,
        startDateEpochDay = day.toEpochDay(),
        timeMinutes = minutes,
        reminder = ReminderConfig(timesMinutes = listOf(minutes), repeat = false)
    )

    @Test
    fun `a reminder window is due from the start of the window, not midnight`() {
        assertEquals(
            TodoCodec.dayTimeMillis(day, 295),
            TodoCodec.reminderMomentMillis(windowTodo(), day)
        )
    }

    @Test
    fun `a reminder with its own time is due at that time`() {
        assertEquals(
            TodoCodec.dayTimeMillis(day, 15 * 60),
            TodoCodec.reminderMomentMillis(atTime(15 * 60), day)
        )
    }

    @Test
    fun `a reminder with no time at all is due from the start of the day`() {
        val noTime = TodoItem(
            id = "anytime",
            title = "Read a page",
            type = TodoType.PERMANENT,
            startDateEpochDay = day.toEpochDay(),
            reminder = ReminderConfig(timesMinutes = emptyList(), repeat = true)
        )

        assertEquals(
            day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            TodoCodec.reminderMomentMillis(noTime, day)
        )
    }

    @Test
    fun `nothing is due for a todo that declines to interrupt`() {
        // No reminder at all.
        assertNull(TodoCodec.reminderMomentMillis(windowTodo().copy(reminder = null), day))
        // A reminder switched off.
        assertNull(TodoCodec.reminderMomentMillis(windowTodo(enabled = false), day))
        // A reminder for a day the todo is not active: this one starts tomorrow.
        assertNull(
            TodoCodec.reminderMomentMillis(
                windowTodo().copy(startDateEpochDay = day.plusDays(1).toEpochDay()),
                day
            )
        )
    }

    @Test
    fun `a completed reminder is still due — it is the caller who drops it`() {
        // The rule answers a question about the SCHEDULE, so completing today's
        // todo does not change it; the notification builder filters completions
        // separately, and keeping the two apart is what lets this stay a pure
        // function of the todo and the day.
        val done = windowTodo()
        assertEquals(
            TodoCodec.dayTimeMillis(day, 295),
            TodoCodec.reminderMomentMillis(done, day)
        )
    }

    @Test
    fun `a window and a single time an hour apart land at different moments`() {
        // The window reminds at 4:55; the plain todo at 5:55. If the window case
        // had quietly fallen back to midnight (or to the window's END), these two
        // would be the same moment and this would fail.
        val window = TodoCodec.reminderMomentMillis(windowTodo(), day)!!
        val plain = TodoCodec.reminderMomentMillis(atTime(355), day)!!

        assertEquals(60 * 60 * 1000L, plain - window)
        assertEquals(
            TodoCodec.dayTimeMillis(day, 295),
            window
        )
    }
}
