package com.muddassir.clearview.todo.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.muddassir.clearview.todo.model.TodoItem
import java.time.LocalDate

/**
 * Handles everything the Todo reminders need outside the UI process:
 *
 *  - [TodoNotifier.ACTION_REMIND] — an alarm fired: post the reminder
 *    notification (skipped when the todo is no longer due, or already
 *    completed on that day — a completed day must never nag) and schedule the
 *    following occurrence for recurring todos, so reminders never stop.
 *  - [TodoNotifier.ACTION_COMPLETE] — mark the todo completed for the
 *    notification's due day. STRICT: the day must be an applicable day of the
 *    todo and must not be in the future — a future todo can never be
 *    completed through a notification, exactly like in the UI.
 *  - [TodoNotifier.ACTION_DISMISS] — the ✕ action: just clears the
 *    notification; the todo itself is untouched.
 *  - [Intent.ACTION_BOOT_COMPLETED] — after a reboot the OS loses all
 *    alarms, so every pending reminder is re-scheduled.
 *
 * Exported=false: all of these are either system broadcasts or the app's own
 * explicit PendingIntents. (The Snooze notification action does not arrive
 * here — it opens [TodoSnoozeActivity], which reschedules the reminder.)
 */
class TodoReminderReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "TodoReminderReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> TodoScheduler.rescheduleAll(context)

            TodoNotifier.ACTION_REMIND -> {
                val todoId = intent.getStringExtra(TodoNotifier.EXTRA_TODO_ID) ?: return
                val index = intent.getIntExtra(TodoNotifier.EXTRA_REMINDER_INDEX, 0)
                val epochDay = intent.getLongExtra(
                    TodoNotifier.EXTRA_EPOCH_DAY,
                    LocalDate.now().toEpochDay()
                )
                val item: TodoItem? = TodoStore(context).getItems()
                    .firstOrNull { it.id == todoId }
                if (item == null) return
                val day = LocalDate.ofEpochDay(epochDay)
                // A reminder that no longer applies (rescheduled day, edited
                // todo, archived temporary) must never nag.
                if (!TodoCodec.isActiveOn(item, day)) return
                if (TodoCodec.completedOn(item, day)) return
                // The alarm has fired: consume its tracking record (a new one
                // is recorded by scheduleFollowing if a next occurrence exists).
                TodoScheduler.markFired(context, todoId, index)
                // Chain recurring reminders: schedule the following occurrence
                // so a permanent todo keeps reminding without the app reopening
                // (for BOTH styles).
                TodoScheduler.scheduleFollowing(context, item, index, epochDay)
                // Regular reminders post a channel notification (with sound).
                // Alarm style instead starts [TodoAlarmService]: the LOUD
                // looping ringtone (one full minute, even in the background)
                // plus the same full-screen-intent notification over the lock
                // screen. The alarm channel is silent, so there is never
                // double audio. Respect the global toggle + per-todo switch.
                if (item.reminder?.asAlarm == true) {
                    if (TodoStore(context).getTodoNotificationsEnabled() &&
                        item.reminder?.enabled != false
                    ) {
                        TodoAlarmService.start(context, todoId, index, epochDay)
                    }
                } else {
                    TodoNotifier.postReminder(context, item, epochDay, index)
                }
            }

            TodoNotifier.ACTION_COMPLETE -> {
                val todoId = intent.getStringExtra(TodoNotifier.EXTRA_TODO_ID) ?: return
                val epochDay = intent.getLongExtra(
                    TodoNotifier.EXTRA_EPOCH_DAY,
                    LocalDate.now().toEpochDay()
                )
                val occurrenceDay = LocalDate.ofEpochDay(epochDay)
                // The reminder is answered for ITS occurrence; see
                // TodoCodec.reminderCompletionRefusal for why the day is never
                // rolled forward, and why a snoozed reminder is not locked out
                // by a window that closed while it was ringing.
                val day = occurrenceDay
                val store = TodoStore(context)
                val item = store.getItems().firstOrNull { it.id == todoId } ?: return
                // The rules that remain are the todo's own. A refused completion
                // is REPORTED rather than swallowed: a background receiver
                // cannot toast, so the notification is rewritten with the reason.
                val refusal = TodoCodec.reminderCompletionRefusal(item, day)
                if (refusal != null) {
                    Log.w(TAG, "Complete refused for ${item.title}: $refusal")
                    TodoNotifier.postCompletionRefused(
                        context,
                        item,
                        epochDay,
                        completionRefusalMessage(context, refusal)
                    )
                    return
                }
                val now = System.currentTimeMillis()
                store.saveItems(TodoCodec.completed(store.getItems(), todoId, day, now))
                // Completed FIRST (saveItems above), then cancel EVERY reminder
                // for this occurrence — so a range-based todo can never ring
                // again today — and re-schedule the remaining future ones.
                TodoScheduler.cancelAllRemindersForTodo(context, todoId, epochDay)
                TodoScheduler.rescheduleAll(context)
                // Visible feedback: the notification leaves the shade — done.
                TodoNotifier.cancelDayNotification(context, todoId, epochDay)
            }

            TodoNotifier.ACTION_DISMISS -> {
                val todoId = intent.getStringExtra(TodoNotifier.EXTRA_TODO_ID) ?: return
                val epochDay = intent.getLongExtra(
                    TodoNotifier.EXTRA_EPOCH_DAY,
                    LocalDate.now().toEpochDay()
                )
                // Just clears the notification — the todo and its reminder
                // stay untouched.
                TodoNotifier.cancelDayNotification(context, todoId, epochDay)
            }
        }
    }
}
