package com.muddassir.clearview.todo.data

import android.content.Context
import com.muddassir.clearview.R

/**
 * What to say when a completion was refused.
 *
 * The refusal itself is a rule, and the rule is right — a strict window that has
 * closed cannot be reopened, a day the todo is not scheduled for has nothing to
 * complete. What was wrong was saying NOTHING: every completion surface treated
 * "refused" as a no-op, so a reader who snoozed an alarm past the end of its
 * window tapped Complete, saw nothing happen, and reasonably called it a bug.
 *
 * One mapping, used by the alarm screen, the in-app checkbox and the reminder
 * notification, so the same rule is explained in the same words wherever it
 * bites.
 */
internal fun completionRefusalMessage(
    context: Context,
    refusal: TodoCodec.CompletionRefusal
): String = context.getString(
    when (refusal) {
        TodoCodec.CompletionRefusal.FUTURE_DAY -> R.string.todo_complete_refused_future
        TodoCodec.CompletionRefusal.NOT_ACTIVE_DAY -> R.string.todo_complete_refused_inactive
        TodoCodec.CompletionRefusal.ALREADY_COMPLETED -> R.string.todo_complete_refused_done
        TodoCodec.CompletionRefusal.WINDOW_CLOSED -> R.string.todo_complete_refused_window
    }
)
