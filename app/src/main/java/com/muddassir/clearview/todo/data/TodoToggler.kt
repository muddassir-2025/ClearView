package com.muddassir.clearview.todo.data

import android.content.Context

/**
 * Tick a to-do off — or back on — for today (§1).
 *
 * The home-screen widget can toggle a task without the app being open, so the
 * rule that decides whether a toggle is ALLOWED has to live somewhere both can
 * reach. [TodoCompletion] is that place now: this is a thin adapter that keeps
 * the widget's own call site unchanged while sharing the SAME rules and the
 * SAME persistence/alarm side effects as the in-app checkbox, the reminder
 * notification and the alarm screen — so the four can no longer drift apart.
 *
 * @return true when the to-do's state changed, false when the day refuses it.
 */
internal object TodoToggler {

    fun toggleToday(context: Context, todoId: String): Boolean =
        when (TodoCompletion.toggle(context, todoId, java.time.LocalDate.now())) {
            is TodoCompletion.Outcome.Completed,
            is TodoCompletion.Outcome.Uncompleted -> true

            is TodoCompletion.Outcome.Refused,
            is TodoCompletion.Outcome.NotFound -> false
        }
}
