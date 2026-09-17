package com.muddassir.clearview.todo.data

import android.content.Context
import java.time.LocalDate

/**
 * Tick a to-do off — or back on — for today (§1).
 *
 * The home-screen widget can toggle a task without the app being open, so the
 * rule that decides whether a toggle is ALLOWED has to live somewhere both can
 * reach. This is that place.
 *
 * The guard is not decoration. A to-do is only completable on its own
 * applicable day, and a strict-interval to-do whose window has already closed is
 * LOCKED as missed — "can't redo" — so its checkbox is refused rather than
 * quietly recording a completion for a window that no longer exists. A widget
 * that skipped these checks would be a way around the app's own rules, which is
 * the kind of hole that only shows up in the statistics months later.
 *
 * The rules are copied from the two existing call sites rather than invented:
 * `TodoScreen`'s checkbox and `TodoReminderReceiver`'s Complete action. Those
 * two keep their own copies because each does something more — the screen also
 * refreshes its state, the notification also dismisses the shade — so unifying
 * all three is a refactor of working code, not part of adding a widget.
 *
 * @return true when the to-do's state changed, false when the day refuses it.
 */
internal object TodoToggler {

    fun toggleToday(context: Context, todoId: String): Boolean {
        val today = LocalDate.now()
        val now = System.currentTimeMillis()

        val store = TodoStore(context)
        val items = store.getItems()
        val item = items.firstOrNull { it.id == todoId } ?: return false

        if (!TodoCodec.isActiveOn(item, today)) return false

        if (TodoCodec.completedOn(item, today)) {
            // Un-completing: refused once a strict window has closed, so a day
            // locked as done cannot be reopened.
            if (TodoCodec.intervalEnded(item, today, now)) return false
        } else if (!TodoCodec.canCompleteOn(item, today, now)) {
            return false
        }

        val (updated, nowCompleted) = TodoCodec.toggled(items, todoId, today, now)
        // Persisted FIRST, then the reminders are dealt with — so a concurrent
        // reschedule can never revive an alarm for a day already completed.
        // `saveItems` is also what redraws the widget, so the card on screen and
        // the state in storage cannot disagree.
        store.saveItems(updated)
        if (nowCompleted) {
            TodoScheduler.cancelAllRemindersForTodo(context, todoId, today.toEpochDay())
        } else {
            // Un-ticked: the day is actionable again, so its reminder may return.
            TodoScheduler.rescheduleAll(context)
        }
        return true
    }
}
