package com.muddassir.clearview.todo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.muddassir.clearview.todo.data.TodoAlarmService
import com.muddassir.clearview.todo.data.TodoNotifier
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.ui.theme.UrlblockerTheme
import java.time.LocalDate

/**
 * The notification Snooze picker: a tiny dialog-styled activity (themed as a
 * dialog window, launched from the notification's Snooze action) offering
 * **10 minutes, 30 minutes, 1 hour, or a CUSTOM number of minutes**. Selecting
 * one re-schedules that exact reminder occurrence (same request code → no
 * duplicates) and dismisses the original notification. Exported=false — only
 * the app's own PendingIntent opens it.
 *
 * The options themselves live in [SnoozePickerContent] so the full-screen alarm
 * overlay offers the exact same choices inline (§4).
 */
class TodoSnoozeActivity : ComponentActivity() {

    @Composable
    private fun Picker(onDone: () -> Unit) {
        UrlblockerTheme {
            SnoozePickerContent(
                onSnooze = { minutes ->
                    TodoScheduler.snoozeFromNotification(
                        this@TodoSnoozeActivity, todoId, index, epochDay, minutes
                    )
                    // The original notification is handled: it leaves the shade,
                    // and the fresh reminder posts when it fires.
                    TodoNotifier.cancelDayNotification(this@TodoSnoozeActivity, todoId, epochDay)
                    onDone()
                },
                onDismiss = { onDone() }
            )
        }
    }

    private var todoId: String = ""
    private var index: Int = 0
    private var epochDay: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        todoId = intent.getStringExtra(TodoNotifier.EXTRA_TODO_ID) ?: run {
            finish()
            return
        }
        index = intent.getIntExtra(TodoNotifier.EXTRA_REMINDER_INDEX, 0)
        epochDay = intent.getLongExtra(
            TodoNotifier.EXTRA_EPOCH_DAY,
            LocalDate.now().toEpochDay()
        )
        // The user chose Snooze: pause the looping ringtone NOW. The re-armed
        // alarm rings again (full screen + audio) when it fires.
        TodoAlarmService.stop(this)
        setContent {
            // The activity window is already dialog-themed (Theme.Urlblocker.Dialog),
            // so the card is rendered directly — no nested Dialog window.
            Picker { finish() }
        }
    }
}
