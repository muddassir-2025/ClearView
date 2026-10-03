package com.muddassir.clearview.todo.ui

import android.os.Bundle
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.data.TodoAlarmService
import com.muddassir.clearview.todo.data.TodoCodec
import com.muddassir.clearview.todo.data.TodoNotifier
import com.muddassir.clearview.todo.data.TodoCompletion
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.todo.data.TodoStore
import com.muddassir.clearview.todo.data.completionRefusalMessage
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.ui.theme.UrlblockerTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val ALARM_BG = Color(0xFF101418)
private val ALARM_GREEN = Color(0xFF43A047)

/**
 * The REAL full-screen alarm for alarm-style todos. The system shows this
 * activity over the lock screen via the notification's full-screen intent
 * (the sanctioned alarm pattern — launching it directly from a background
 * alarm is blocked by the background-activity-launch restriction).
 *
 * [TodoAlarmService] plays the looping ringtone (one full minute, even if
 * this screen is dismissed without acting); this screen is the visible alarm
 * with Complete / Snooze / Dismiss. All three cancel the day's notification,
 * which also stops the ringing service.
 *
 * Note: the reminder receiver already did the bookkeeping (consumed the fired
 * record, chained the next occurrence) when the alarm broadcast arrived.
 */
class TodoAlarmActivity : ComponentActivity() {

    private var todoId: String = ""
    private var reminderIndex: Int = 0
    private var epochDay: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen and wake the device (FLAG_* works on all
        // APIs; showWhenLocked/turnScreenOn in the manifest cover API 27+).
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        // Make sure the looping ringtone is running (the receiver usually
        // already started it; start() is idempotent for the same occurrence).
        todoId = intent.getStringExtra(TodoNotifier.EXTRA_TODO_ID) ?: run {
            finish()
            return
        }
        reminderIndex = intent.getIntExtra(TodoNotifier.EXTRA_REMINDER_INDEX, 0)
        epochDay = intent.getLongExtra(
            TodoNotifier.EXTRA_EPOCH_DAY,
            LocalDate.now().toEpochDay()
        )
        val store = TodoStore(this)
        val item = store.getItems().firstOrNull { it.id == todoId }
        val day = LocalDate.ofEpochDay(epochDay)
        // A deleted todo, or a day already completed, must never ring.
        if (item == null || TodoCodec.completedOn(item, day)) {
            TodoAlarmService.stop(this)
            finish()
            return
        }
        TodoAlarmService.start(this, todoId, reminderIndex, epochDay)

        setContent {
            UrlblockerTheme {
                AlarmScreen(
                    item = item,
                    onComplete = {
                        complete(item, day)
                        finish()
                    },
                    onSnoozeChoice = { minutes ->
                        // Snooze happens ENTIRELY inside the alarm overlay (§4):
                        // the chosen delay is saved here, the ringing
                        // notification is cancelled and the replacement alarms
                        // are scheduled — the reader never has to unlock, open
                        // ClearView, or navigate to To-Dos.
                        TodoAlarmService.stop(this)
                        TodoScheduler.snoozeFromNotification(
                            this, todoId, reminderIndex, epochDay, minutes
                        )
                        TodoNotifier.cancelDayNotification(this, todoId, epochDay)
                        finish()
                    }
                )
            }
        }
    }

    private fun complete(item: TodoItem, occurrenceDay: LocalDate) {
        // ONE authoritative completion (TodoCompletion): the alarm answers for
        // ITS occurrence with the same rules, persistence and reminder
        // cancellation as the notification Complete action and the in-app
        // checkbox. See TodoCodec.reminderCompletionRefusal for why the day is
        // never rolled forward, and why a snoozed alarm is not locked out by a
        // window that closed while it was ringing.
        val day = occurrenceDay
        when (val outcome = TodoCompletion.completeFromReminder(this, item.id, day)) {
            is TodoCompletion.Outcome.Refused -> {
                // SAY SO. Degrading to a silent dismiss is what made this look
                // like a broken button.
                Toast.makeText(
                    this,
                    completionRefusalMessage(this, outcome.refusal),
                    Toast.LENGTH_LONG
                ).show()
            }

            is TodoCompletion.Outcome.Completed,
            is TodoCompletion.Outcome.NotFound,
            is TodoCompletion.Outcome.Uncompleted -> Unit
        }
        // Stop the ringing notification either way.
        TodoNotifier.cancelDayNotification(this, todoId, occurrenceDay.toEpochDay())
    }
}

/** The full-screen alarm UI: big clock, todo, and Complete / Snooze. There is
 * deliberately NO Dismiss — an alarm is either completed or snoozed; the
 * ringing service still stops on its own after a minute either way.
 *
 * Snooze opens the SAME picker the notification uses ([SnoozePickerContent])
 * INLINE, right here in the overlay (§4) — tapping Snooze never navigates the
 * reader into the app. */
@Composable
private fun AlarmScreen(
    item: TodoItem,
    onComplete: () -> Unit,
    onSnoozeChoice: (Long) -> Unit
) {
    var snoozeOpen by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000)
        }
    }
    val today = LocalDate.now()
    Surface(modifier = Modifier.fillMaxSize(), color = ALARM_BG) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.todo_alarm_title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.6f),
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = DateTimeFormatter.ofPattern("h:mm").format(now),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 84.sp
            )
            Text(
                text = DateTimeFormatter.ofPattern("EEEE, MMMM d · a").format(now),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(40.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.details.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = TodoCodec.scheduleLabel(item, today),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f)
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(24.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                if (snoozeOpen) {
                    // The snooze options live inside the alarm overlay itself.
                    SnoozePickerContent(
                        onSnooze = onSnoozeChoice,
                        onDismiss = { snoozeOpen = false }
                    )
                } else {
                    Button(
                        onClick = onComplete,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ALARM_GREEN)
                    ) {
                        Text(
                            text = stringResource(R.string.todo_notification_complete),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { snoozeOpen = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text(
                            text = stringResource(R.string.todo_notification_snooze),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
