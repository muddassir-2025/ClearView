package com.muddassir.clearview.todo.data

import android.content.Context
import com.muddassir.clearview.todo.model.TodoItem
import java.time.LocalDate

/**
 * THE authoritative completion operation for a To-Do occurrence (§2).
 *
 * Before this existed, four places completed (or un-completed) a todo and each
 * one carried its own copy of the rules AND its own copy of the side effects:
 * the in-app checkbox in `TodoScreen`, the home-screen widget
 * ([TodoToggler]), the reminder notification's Complete action
 * ([TodoReminderReceiver]) and the full-screen alarm ([com.muddassir.clearview
 * .todo.ui.TodoAlarmActivity]). Any rule that changed in one of them could
 * silently disagree with the others — which is exactly how a notification's
 * Complete could look like it worked while the persisted state went somewhere
 * else.
 *
 * There are two entry points because the two DIRECTIONS are different, and the
 * difference is deliberate:
 *
 *  - [toggle] — the interactive checkbox: it completes, and completing it again
 *    un-completes. The strict-window lock applies BOTH ways ("can't redo").
 *  - [completeFromReminder] — the answer to an alarm the app itself raised:
 *    one-way, and the strict-window lock does NOT apply, because the reader was
 *    reminded and is answering now (see [TodoCodec.reminderCompletionRefusal]).
 *
 * Everything AFTER the rules is shared: one persistence call, one
 * reminder-cancellation rule, one reschedule. A caller only decides how to
 * surface a [Outcome.Refused] (a toast in the app, a rewritten notification in
 * the background) — it can no longer re-implement the state change.
 */
internal object TodoCompletion {

    /** What a completion attempt did. */
    sealed interface Outcome {
        /** The day this attempt concerned. */
        val day: LocalDate

        /** The occurrence is now marked done (or was already). */
        data class Completed(override val day: LocalDate) : Outcome

        /** The interactive toggle removed the completion. */
        data class Uncompleted(override val day: LocalDate) : Outcome

        /** A rule refused the attempt; [refusal] says which. */
        data class Refused(
            val refusal: TodoCodec.CompletionRefusal,
            override val day: LocalDate
        ) : Outcome

        /** No todo with that id exists any more. */
        data class NotFound(override val day: LocalDate) : Outcome
    }

    /**
     * Interactive tick / untick — the in-app Today checkbox and the widget.
     * Refuses a day the todo is not scheduled for, a future day, a completion
     * while a strict window is closed, and an un-completion once that window has
     * closed.
     */
    fun toggle(
        context: Context,
        todoId: String,
        day: LocalDate,
        now: Long = System.currentTimeMillis()
    ): Outcome {
        val store = TodoStore(context)
        val item = store.getItems().firstOrNull { it.id == todoId }
            ?: return Outcome.NotFound(day)

        interactiveRefusal(item, day, now)?.let { return Outcome.Refused(it, day) }

        val (updated, nowCompleted) = TodoCodec.toggled(store.getItems(), todoId, day, now)
        persist(context, store, updated, todoId, day, nowCompleted)
        return if (nowCompleted) Outcome.Completed(day) else Outcome.Uncompleted(day)
    }

    /**
     * Strict, one-way completion — the reminder notification's Complete action
     * and the full-screen alarm's Complete button. The occurrence is judged with
     * [TodoCodec.reminderCompletionRefusal] (the answer-to-a-reminder rules), so
     * a snoozed alarm that rang past its window can still be completed, and the
     * completion always lands on the occurrence's own day.
     */
    fun completeFromReminder(
        context: Context,
        todoId: String,
        day: LocalDate,
        now: Long = System.currentTimeMillis()
    ): Outcome {
        val store = TodoStore(context)
        val item = store.getItems().firstOrNull { it.id == todoId }
            ?: return Outcome.NotFound(day)

        TodoCodec.reminderCompletionRefusal(item, day)?.let { return Outcome.Refused(it, day) }

        val updated = TodoCodec.completed(store.getItems(), todoId, day, now)
        persist(context, store, updated, todoId, day, nowCompleted = true)
        return Outcome.Completed(day)
    }

    /**
     * The interactive rule set. A completed day can only be un-completed while a
     * strict window is still open; an uncompleted day goes through the same
     * [TodoCodec.completionRefusal] the checkbox's enabled state is drawn from.
     */
    private fun interactiveRefusal(
        item: TodoItem,
        day: LocalDate,
        now: Long
    ): TodoCodec.CompletionRefusal? {
        if (!TodoCodec.isActiveOn(item, day)) return TodoCodec.CompletionRefusal.NOT_ACTIVE_DAY
        return if (TodoCodec.completedOn(item, day)) {
            if (TodoCodec.intervalEnded(item, day, now)) {
                TodoCodec.CompletionRefusal.WINDOW_CLOSED
            } else {
                null
            }
        } else {
            TodoCodec.completionRefusal(item, day, now)
        }
    }

    /**
     * Persist FIRST, then fix the alarms — so a concurrent reschedule can never
     * revive an alarm for a day that is already completed. [TodoStore.saveItems]
     * also redraws the home-screen widget, so the card and the stored state can
     * never disagree.
     */
    private fun persist(
        context: Context,
        store: TodoStore,
        updated: List<TodoItem>,
        todoId: String,
        day: LocalDate,
        nowCompleted: Boolean
    ) {
        store.saveItems(updated)
        if (nowCompleted) {
            TodoScheduler.cancelAllRemindersForTodo(context, todoId, day.toEpochDay())
        }
        // Re-arm everything else that is still pending. For a completion this
        // re-schedules the remaining future occurrences (the completed day is
        // skipped by the scheduler); for an un-completion it brings the day's
        // reminder back.
        TodoScheduler.rescheduleAll(context)
    }
}
