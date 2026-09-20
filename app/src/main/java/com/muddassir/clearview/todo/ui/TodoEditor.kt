package com.muddassir.clearview.todo.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.data.AlarmSounds
import com.muddassir.clearview.todo.data.TodoCodec
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.todo.model.ReminderConfig
import com.muddassir.clearview.todo.model.TodoBehavior
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoPriority
import com.muddassir.clearview.todo.model.TodoType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Temporary-todo period presets. */
internal enum class PeriodChoice { TODAY, TOMORROW, THIS_WEEK, CUSTOM }

/** Time options in the editor: none / a single time / a start–end range. */
private enum class TimeChoice { NONE, SINGLE, RANGE }

/** How a set reminder fires: off, a notification, or a real system alarm. */
private enum class ReminderStyle { OFF, NOTIFICATION, ALARM }

/** What the time picker is editing right now. */
private sealed class TimeTarget {
    object Scheduled : TimeTarget()
    object RangeStart : TimeTarget()
    object RangeEnd : TimeTarget()
    data class Reminder(val index: Int) : TimeTarget()
}

/** Which date the date picker is editing (custom-range only). */
private enum class DateTarget { START, END }

/**
 * Which value-list picker is open, if any.
 *
 * One state instead of a boolean per picker: only one can be open at a time, and
 * "which one" is exactly what the editor needs to know to draw its dialog.
 */
private enum class EditorChoice { DATES, DURATION, RING }

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d")

/**
 * Add / edit a Todo — a full-screen form (like the Dhikr settings screen):
 * name + optional details, Temporary vs Permanent, the date/period (Today /
 * Tomorrow / This week / custom range, or repeat weekdays for permanent), an
 * optional scheduled time (none / one time / a start–end range) and priority.
 * Reminders are AUTOMATIC, derived from the time choice: no time → none, a
 * single time → one notification at that time, a range → notifications spread
 * across it (a chosen count, or your own specific times). Only the name is
 * required.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodoEditorDialog(
    initial: TodoItem?,
    onSave: (TodoItem) -> Unit,
    onDismiss: () -> Unit
) {
    val today = LocalDate.now()
    val context = LocalContext.current

    var title by remember { mutableStateOf(initial?.title ?: "") }
    var details by remember { mutableStateOf(initial?.details ?: "") }
    var titleError by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(initial?.type ?: TodoType.TEMPORARY) }
    var period by remember {
        mutableStateOf(
            if (initial == null) PeriodChoice.TODAY
            else when {
                initial.type == TodoType.PERMANENT -> PeriodChoice.CUSTOM
                initial.startDateEpochDay == initial.endDateEpochDay -> {
                    val d = LocalDate.ofEpochDay(initial.startDateEpochDay)
                    when (d) {
                        today -> PeriodChoice.TODAY
                        today.plusDays(1) -> PeriodChoice.TOMORROW
                        else -> PeriodChoice.CUSTOM
                    }
                }
                else -> PeriodChoice.CUSTOM
            }
        )
    }
    var startDate by remember {
        mutableStateOf(initial?.startDateEpochDay?.let(LocalDate::ofEpochDay) ?: today)
    }
    var endDate by remember {
        mutableStateOf(initial?.endDateEpochDay?.let(LocalDate::ofEpochDay) ?: today)
    }
    var selectedDays by remember {
        mutableStateOf(
            initial?.scheduledDays?.takeIf { it.isNotEmpty() } ?: (1..7).toSet()
        )
    }
    var timeMinutes by remember { mutableStateOf(initial?.timeMinutes) }
    var timeStartMinutes by remember { mutableStateOf(initial?.timeStartMinutes ?: 9 * 60) }
    var timeEndMinutes by remember { mutableStateOf(initial?.timeEndMinutes ?: 20 * 60) }
    var timeChoice by remember {
        mutableStateOf(
            when {
                initial?.timeStartMinutes != null && initial?.timeEndMinutes != null -> TimeChoice.RANGE
                initial?.timeMinutes != null -> TimeChoice.SINGLE
                else -> TimeChoice.NONE
            }
        )
    }
    // How many reminders to spread across the range (2..6); the generated times
    // land in [reminderTimes] and stay manually editable afterwards.
    var rangeReminderCount by remember {
        mutableStateOf(initial?.reminder?.timesMinutes?.size?.coerceIn(2, 6) ?: 3)
    }
    var reminderTimes by remember {
        mutableStateOf(initial?.reminder?.timesMinutes ?: emptyList())
    }
    // Off / Notification / Alarm — how the automatic reminder fires. Alarm
    // rings the system alarm clock (exact, full-screen, Clock-app entry);
    // Notification posts the usual in-app notification.
    var reminderStyle by remember {
        mutableStateOf(
            when {
                initial?.reminder == null -> ReminderStyle.NOTIFICATION
                !initial.reminder.enabled -> ReminderStyle.OFF
                initial.reminder.asAlarm -> ReminderStyle.ALARM
                else -> ReminderStyle.NOTIFICATION
            }
        )
    }
    // ALARM style only: how long it rings, and which sound it rings with.
    // [alarmUri] is null for the system alarm; a device pick is a persisted
    // content:// URI the service plays directly.
    var alarmMinutes by remember {
        mutableStateOf(
            initial?.reminder?.alarmMinutes
                ?.coerceIn(1, ReminderConfig.MAX_ALARM_MINUTES)
                ?: ReminderConfig.DEFAULT_ALARM_MINUTES
        )
    }
    var alarmUri by remember { mutableStateOf(initial?.reminder?.alarmUri) }
    var priority by remember { mutableStateOf(initial?.priority ?: TodoPriority.NORMAL) }
    var behavior by remember { mutableStateOf(initial?.behavior ?: TodoBehavior.NORMAL) }
    var targetDurationMinutes by remember { mutableStateOf(initial?.targetDurationMinutes ?: 60) }
    // Strict interval: a RANGE todo is only completable INSIDE its start–end
    // window. When the window ends uncompleted, that day is locked as missed
    // ("can't redo") — the checkbox disables and the notification Complete
    // action is rejected. Only meaningful (and shown) for RANGE todos.
    var strictInterval by remember { mutableStateOf(initial?.strictInterval ?: false) }
    var choice by remember { mutableStateOf<EditorChoice?>(null) }
    var dateTarget by remember { mutableStateOf<DateTarget?>(null) }
    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }

    // Android 13+ runtime permission: a Notification-style reminder is silently
    // dropped without POST_NOTIFICATIONS, so request it right where the style
    // is chosen (same pattern as the Settings toggles).
    val notificationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied: no extra action — the UI just reflects it */ }

    // Picking the alarm sound: OpenDocument (not GetContent) because its URIs can
    // be kept — the alarm rings days later, from a service that was not the
    // activity that opened the picker, so a one-shot grant would be worthless.
    val pickAlarmSound = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { picked ->
        picked ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                picked,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        alarmUri = picked.toString()
    }

    fun effectiveStart(): LocalDate = when (period) {
        PeriodChoice.TODAY -> today
        PeriodChoice.TOMORROW -> today.plusDays(1)
        PeriodChoice.THIS_WEEK -> today
        PeriodChoice.CUSTOM -> startDate
    }

    fun effectiveEnd(): LocalDate? = when {
        type == TodoType.PERMANENT -> null
        period == PeriodChoice.TODAY -> today
        period == PeriodChoice.TOMORROW -> today.plusDays(1)
        period == PeriodChoice.THIS_WEEK -> today.plusDays(7 - today.dayOfWeek.value.toLong())
        period == PeriodChoice.CUSTOM -> if (endDate < startDate) startDate else endDate
        else -> endDate
    }

    /** Regenerates the reminder times spread evenly across the range. */
    fun setRangeCount(count: Int) {
        rangeReminderCount = count.coerceIn(2, 6)
        reminderTimes = TodoCodec.rangeTimes(timeStartMinutes, timeEndMinutes, rangeReminderCount)
    }

    /** Clamps a start/end pair to a sane ≥60-minute window (no overnight wrap). */
    fun adjustRange(start: Int, end: Int): Pair<Int, Int> {
        var s = start
        var e = end
        if (e <= s) {
            e = (s + 60).coerceAtMost(1439)
            s = (e - 60).coerceAtLeast(0)
        }
        return s to e
    }

    /** Adds the next sensible free reminder time inside the range. */
    fun addRangeReminderTime() {
        if (reminderTimes.size >= 6) return
        val base = reminderTimes.lastOrNull() ?: (timeStartMinutes - 60)
        var candidate = (base + 60).coerceAtMost(timeEndMinutes)
        if (candidate < timeStartMinutes) candidate = timeStartMinutes
        while (candidate in reminderTimes && candidate > timeStartMinutes) candidate -= 30
        if (candidate !in reminderTimes) {
            reminderTimes = (reminderTimes + candidate).sorted()
        }
    }

    fun submit() {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            titleError = true
            return
        }
        val start = effectiveStart()
        val end = effectiveEnd()
        val days = if (type == TodoType.PERMANENT) {
            if (selectedDays.size == 7) null else selectedDays
        } else null
        val singleTime = if (timeChoice == TimeChoice.SINGLE) timeMinutes else null
        val rangeStart = if (timeChoice == TimeChoice.RANGE) timeStartMinutes else null
        val rangeEnd = if (timeChoice == TimeChoice.RANGE) timeEndMinutes else null
        // The reminder is AUTOMATIC, derived from the time choice: no time → no
        // reminder; a single time → one reminder at that time; a range →
        // reminders at the range times (your count or your own picks), repeating
        // on every active day. [reminderStyle] chooses HOW it fires: Off (no
        // reminder at all), a Notification, or a real system Alarm.
        val styleOn = reminderStyle != ReminderStyle.OFF
        val reminder = when (timeChoice) {
            TimeChoice.NONE -> null
            TimeChoice.SINGLE -> ReminderConfig(
                timesMinutes = listOf(timeMinutes ?: 20 * 60),
                repeat = true,
                enabled = styleOn,
                asAlarm = reminderStyle == ReminderStyle.ALARM,
                alarmMinutes = alarmMinutes,
                alarmUri = alarmUri
            )
            TimeChoice.RANGE -> ReminderConfig(
                timesMinutes = reminderTimes.ifEmpty {
                    TodoCodec.rangeTimes(rangeStart!!, rangeEnd!!, rangeReminderCount)
                }.distinct(),
                repeat = true,
                enabled = styleOn,
                asAlarm = reminderStyle == ReminderStyle.ALARM,
                alarmMinutes = alarmMinutes,
                alarmUri = alarmUri
            )
        }
        onSave(
            TodoItem(
                id = initial?.id ?: "",
                title = trimmed,
                details = details.trim(),
                type = type,
                startDateEpochDay = start.toEpochDay(),
                endDateEpochDay = end?.toEpochDay(),
                scheduledDays = days,
                timeMinutes = singleTime,
                timeStartMinutes = rangeStart,
                timeEndMinutes = rangeEnd,
                reminder = reminder,
                priority = priority,
                strictInterval = strictInterval && timeChoice == TimeChoice.RANGE,
                behavior = behavior,
                targetDurationMinutes = if (behavior == TodoBehavior.TIME) targetDurationMinutes else null,
                events = initial?.events ?: emptyList(),
                isDeleted = initial?.isDeleted ?: false
            )
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                EditorTopBar(
                    title = stringResource(
                        if (initial == null) R.string.todo_add else R.string.todo_edit
                    ),
                    subtitle = stringResource(
                        if (initial == null) R.string.todo_new_subtitle
                        else R.string.todo_edit_subtitle
                    ),
                    onBack = onDismiss
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 6.dp, bottom = 12.dp)
                ) {
                    SectionHeader(
                        icon = Icons.Filled.EditNote,
                        title = stringResource(R.string.todo_section_what),
                        first = true
                    )
                    // ── Name + details ──
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it; titleError = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.todo_title_label)) },
                        singleLine = true,
                        isError = titleError
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.todo_details_label)) },
                        minLines = 2
                    )

                    // A two-way choice is a segmented control, not two floating
                    // boxes: it reads as ONE setting with two states, which is
                    // what it is.
                    Spacer(Modifier.height(16.dp))
                    FieldLabel(stringResource(R.string.todo_type))
                    Spacer(Modifier.height(8.dp))
                    EditorSegments(
                        options = listOf(
                            stringResource(R.string.todo_type_temporary),
                            stringResource(R.string.todo_type_permanent)
                        ),
                        selectedIndex = if (type == TodoType.TEMPORARY) 0 else 1,
                        onSelect = { type = if (it == 0) TodoType.TEMPORARY else TodoType.PERMANENT }
                    )
                    Spacer(Modifier.height(6.dp))
                    HintText(
                        stringResource(
                            if (type == TodoType.TEMPORARY) R.string.todo_type_temporary_note
                            else R.string.todo_type_permanent_note
                        )
                    )

                    Spacer(Modifier.height(18.dp))
                    FieldLabel("Task variety")
                    Spacer(Modifier.height(8.dp))
                    EditorSegments(
                        options = TodoBehavior.entries.map(::behaviorLabel),
                        selectedIndex = TodoBehavior.entries.indexOf(behavior),
                        onSelect = { behavior = TodoBehavior.entries[it] }
                    )
                    Spacer(Modifier.height(6.dp))
                    HintText(behaviorNote(behavior))
                    // Only meaningful for a duration tracker — and then it is one
                    // row showing the current target rather than a row of six
                    // boxes to guess from.
                    if (behavior == TodoBehavior.TIME) {
                        Spacer(Modifier.height(10.dp))
                        EditorChoiceRow(
                            label = "Target duration",
                            value = durationLabel(targetDurationMinutes),
                            onClick = { choice = EditorChoice.DURATION }
                        )
                    }

                    SectionHeader(icon = Icons.Filled.Event, title = stringResource(R.string.todo_section_when))
                    if (type == TodoType.TEMPORARY) {
                        // One row that names the current period and opens the
                        // four options — instead of four chips permanently on
                        // screen, three of which are not chosen.
                        EditorChoiceRow(
                            label = stringResource(R.string.todo_date),
                            value = periodLabel(period, startDate, endDate),
                            onClick = { choice = EditorChoice.DATES }
                        )
                        if (period == PeriodChoice.CUSTOM) {
                            EditorChoiceRow(
                                label = stringResource(R.string.todo_date_range_start, ""),
                                value = DATE_FORMAT.format(startDate),
                                onClick = { dateTarget = DateTarget.START }
                            )
                            EditorChoiceRow(
                                label = stringResource(R.string.todo_date_range_end, ""),
                                value = DATE_FORMAT.format(endDate),
                                onClick = { dateTarget = DateTarget.END }
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FieldLabel(
                                text = stringResource(R.string.todo_active_days),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { selectedDays = (1..7).toSet() }) {
                                Text(stringResource(R.string.todo_every_day))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        DayToggleRow(
                            selectedDays = selectedDays,
                            onToggle = { dow ->
                                selectedDays = if (dow in selectedDays) selectedDays - dow
                                else selectedDays + dow
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        // Live summary of the current selection — clicking the day
                        // chips updates this instantly (previously only a static
                        // "Every day" label was shown, so the change was invisible).
                        Text(
                            text = if (selectedDays.isEmpty() || selectedDays.size == 7) {
                                stringResource(R.string.todo_every_day)
                            } else {
                                selectedDays.sorted().joinToString(" • ") { DAY_NAMES[it - 1] }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    FieldLabel(stringResource(R.string.todo_time))
                    Spacer(Modifier.height(8.dp))
                    // How the day is timed is a MODE, so it is one segmented
                    // control: no time, one time, or a window.
                    EditorSegments(
                        options = listOf(
                            stringResource(R.string.todo_no_time),
                            stringResource(R.string.todo_at_time),
                            stringResource(R.string.todo_time_range)
                        ),
                        selectedIndex = when (timeChoice) {
                            TimeChoice.NONE -> 0
                            TimeChoice.SINGLE -> 1
                            TimeChoice.RANGE -> 2
                        },
                        onSelect = { index ->
                            timeChoice = TimeChoice.entries[index]
                            if (timeChoice == TimeChoice.RANGE && reminderTimes.isEmpty()) {
                                reminderTimes = TodoCodec.rangeTimes(
                                    timeStartMinutes,
                                    timeEndMinutes,
                                    rangeReminderCount
                                )
                            }
                        }
                    )
                    when (timeChoice) {
                        TimeChoice.NONE -> {}
                        TimeChoice.SINGLE -> {
                            Spacer(Modifier.height(8.dp))
                            EditorChoiceRow(
                                label = stringResource(R.string.todo_set_time),
                                value = timeMinutes?.let { TodoCodec.timeLabel(it) }
                                    ?: stringResource(R.string.todo_set_time),
                                onClick = {
                                    if (timeMinutes == null) timeMinutes = 20 * 60
                                    timeTarget = TimeTarget.Scheduled
                                }
                            )
                        }
                        TimeChoice.RANGE -> {
                            Spacer(Modifier.height(8.dp))
                            EditorChoiceRow(
                                label = stringResource(R.string.todo_time_from, ""),
                                value = TodoCodec.timeLabel(timeStartMinutes),
                                onClick = { timeTarget = TimeTarget.RangeStart }
                            )
                            EditorChoiceRow(
                                label = stringResource(R.string.todo_time_to, ""),
                                value = TodoCodec.timeLabel(timeEndMinutes),
                                onClick = { timeTarget = TimeTarget.RangeEnd }
                            )
                            Spacer(Modifier.height(4.dp))
                            // Strict interval: completion only inside the window.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { strictInterval = !strictInterval }
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = strictInterval,
                                    onCheckedChange = { strictInterval = it }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.todo_strict_interval),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(1.dp))
                                    Text(
                                        text = stringResource(R.string.todo_strict_interval_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            // Reminders are automatic — N notifications spread
                            // across the window (or your own specific times).
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.todo_remind_times_in_range),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(1.dp))
                                    Text(
                                        text = stringResource(R.string.todo_remind_times_in_range_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { setRangeCount(rangeReminderCount - 1) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Remove,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "$rangeReminderCount",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(26.dp),
                                    textAlign = TextAlign.Center
                                )
                                IconButton(
                                    onClick = { setRangeCount(rangeReminderCount + 1) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            reminderTimes.forEachIndexed { index, minutes ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        onClick = { timeTarget = TimeTarget.Reminder(index) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(TodoCodec.timeLabel(minutes), modifier = Modifier.weight(1f))
                                    }
                                    IconButton(onClick = {
                                        reminderTimes = reminderTimes.filterIndexed { i, _ -> i != index }
                                    }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.todo_remove_time),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                            if (reminderTimes.size < 6) {
                                TextButton(
                                    onClick = { addRangeReminderTime() },
                                    modifier = Modifier.align(Alignment.Start)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.todo_reminder_add_time))
                                }
                            }
                        }
                    }

                    // ── Reminder ──
                    // One reminder is derived from the time choice (above), so
                    // how it FIRES is its own concern — and one place, rather
                    // than repeated inside both time branches.
                    if (timeChoice == TimeChoice.RANGE ||
                        (timeChoice == TimeChoice.SINGLE && timeMinutes != null)
                    ) {
                        SectionHeader(
                            icon = Icons.Filled.NotificationsActive,
                            title = stringResource(R.string.todo_section_remind)
                        )
                        if (timeChoice == TimeChoice.SINGLE &&
                            reminderStyle != ReminderStyle.OFF
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.todo_reminder_at_label,
                                    TodoCodec.timeLabel(timeMinutes!!)
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        ReminderStyleRow(
                            reminderStyle,
                            { reminderStyle = it },
                            { notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        )
                        AlarmTuning(
                            style = reminderStyle,
                            minutes = alarmMinutes,
                            uri = alarmUri,
                            onPick = { pickAlarmSound.launch(arrayOf("audio/*")) },
                            onReset = { alarmUri = null },
                            onRingChoice = { choice = EditorChoice.RING }
                        )
                    }

                    SectionHeader(
                        icon = Icons.Filled.Flag,
                        title = stringResource(R.string.todo_section_organise)
                    )
                    EditorSegments(
                        options = listOf(
                            stringResource(R.string.todo_priority_low),
                            stringResource(R.string.todo_priority_normal),
                            stringResource(R.string.todo_priority_high)
                        ),
                        selectedIndex = TodoPriority.entries.indexOf(priority),
                        onSelect = { priority = TodoPriority.entries[it] }
                    )

                }

                // The save action sits OUTSIDE the scroll: this form is long
                // enough that a button at the end of it is off-screen for most
                // of the time it is being filled in.
                EditorSaveBar(onSave = ::submit)
            }
        }
    }

    // ── Value-list pickers (the rows that open one) ──
    when (choice) {
        EditorChoice.DATES -> ChoiceDialog(
            title = stringResource(R.string.todo_date),
            options = listOf(
                PeriodChoice.TODAY to stringResource(R.string.todo_today),
                PeriodChoice.TOMORROW to stringResource(R.string.todo_tomorrow),
                PeriodChoice.THIS_WEEK to stringResource(R.string.todo_this_week),
                PeriodChoice.CUSTOM to stringResource(R.string.todo_custom)
            ),
            selected = period,
            onSelect = { period = it; choice = null },
            onDismiss = { choice = null }
        )

        EditorChoice.DURATION -> ChoiceDialog(
            title = "Target duration",
            options = DURATION_CHOICES.map { it to durationLabel(it) },
            selected = targetDurationMinutes,
            onSelect = { targetDurationMinutes = it; choice = null },
            onDismiss = { choice = null }
        )

        EditorChoice.RING -> ChoiceDialog(
            title = stringResource(R.string.todo_alarm_ring_for),
            options = ReminderConfig.ALARM_MINUTE_CHOICES.map {
                it to stringResource(R.string.todo_alarm_minutes, it)
            },
            selected = alarmMinutes,
            onSelect = { alarmMinutes = it; choice = null },
            onDismiss = { choice = null }
        )

        null -> {}
    }

    // ── Date picker (custom range) ──
    dateTarget?.let { target ->
        val initialDate = when (target) {
            DateTarget.START -> startDate
            DateTarget.END -> endDate
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initialDate.toEpochDay() * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { dateTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        when (target) {
                            DateTarget.START -> {
                                startDate = date
                                if (endDate < startDate) endDate = startDate
                            }
                            DateTarget.END -> {
                                endDate = date
                                if (endDate < startDate) startDate = endDate
                            }
                        }
                    }
                    dateTarget = null
                }) { Text(stringResource(R.string.todo_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { dateTarget = null }) {
                    Text(stringResource(R.string.todo_cancel))
                }
            }
        ) {
            DatePicker(state = state)
        }
    }

    // ── Time picker ──
    timeTarget?.let { target ->
        val initialMinutes = when (target) {
            TimeTarget.Scheduled -> timeMinutes ?: 20 * 60
            TimeTarget.RangeStart -> timeStartMinutes
            TimeTarget.RangeEnd -> timeEndMinutes
            is TimeTarget.Reminder -> reminderTimes.getOrNull(target.index) ?: 20 * 60
        }
        val state = rememberTimePickerState(
            initialHour = initialMinutes / 60,
            initialMinute = initialMinutes % 60,
            is24Hour = false
        )
        TimePickerDialog(
            onDismissRequest = { timeTarget = null },
            title = { Text(stringResource(R.string.todo_time)) },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = state.hour * 60 + state.minute
                    when (target) {
                        TimeTarget.Scheduled -> timeMinutes = minutes
                        TimeTarget.RangeStart -> {
                            val (s, e) = adjustRange(minutes, timeEndMinutes)
                            timeStartMinutes = s
                            timeEndMinutes = e
                            reminderTimes = TodoCodec.rangeTimes(s, e, rangeReminderCount)
                        }
                        TimeTarget.RangeEnd -> {
                            val (s, e) = adjustRange(timeStartMinutes, minutes)
                            timeStartMinutes = s
                            timeEndMinutes = e
                            reminderTimes = TodoCodec.rangeTimes(s, e, rangeReminderCount)
                        }
                        is TimeTarget.Reminder -> {
                            reminderTimes = if (target.index < reminderTimes.size) {
                                reminderTimes.mapIndexed { i, m -> if (i == target.index) minutes else m }
                            } else {
                                reminderTimes + minutes
                            }
                        }
                    }
                    timeTarget = null
                }) { Text(stringResource(R.string.todo_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { timeTarget = null }) {
                    Text(stringResource(R.string.todo_cancel))
                }
            }
        ) {
            TimePicker(state = state)
        }
    }
}

/**
 * The name of a field inside a section: what the control below it chooses.
 *
 * Deliberately lighter than a [SectionHeader]: a section heading is a place in
 * the form, a field label is a property of the todo.
 */
@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

/** The one-line explanation under a control. */
@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * A MODE: two to three mutually exclusive options, drawn as one segmented
 * control.
 *
 * Segmented buttons rather than chips because this is a switch, not a set of
 * tags — and because a row of separate chips makes every form look like it is
 * asking eight unrelated questions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSegments(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * A VALUE: the current choice and a chevron, with the list of alternatives
 * behind a tap.
 *
 * No container and no outline — a settings row, not a button — because the
 * point of these rows is that the form stops looking like a wall of boxes.
 */
@Composable
private fun EditorChoiceRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    supporting: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (supporting != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * The picker an [EditorChoiceRow] opens: the options as rows, the current one
 * marked.
 *
 * The selection is shown the quiet way — a tinted row and coloured label —
 * rather than with a trailing tick: the value is already spelled out in the row
 * that opened this dialog, so a tick adds a second "chosen" mark with nothing
 * new to say, and on a short list it reads as decoration.
 *
 * A dialog rather than a sheet: it is short, it is a decision the reader makes
 * and leaves, and it must sit above the editor — which is itself a dialog.
 */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    val isSelected = value == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else Color.Transparent
                            )
                            .clickable { onSelect(value) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.todo_cancel)) }
        }
    )
}

/**
 * The weekday picker: seven small circles, filled on the active days.
 *
 * A circle per day rather than seven chips: the letters are one character, and
 * a chip sized for "Wed" wastes most of the row it sits in — the whole week
 * fits on one line this way, which is what makes it readable at a glance.
 */
@Composable
private fun DayToggleRow(selectedDays: Set<Int>, onToggle: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        (1..7).forEach { dow ->
            val active = dow in selectedDays
            Surface(
                shape = CircleShape,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable { onToggle(dow) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = DAY_LETTERS[dow - 1],
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** The label of a Task-variety option. */
private fun behaviorLabel(behavior: TodoBehavior): String = when (behavior) {
    TodoBehavior.NORMAL -> "Normal"
    TodoBehavior.ATTEMPTED -> "Attempted"
    TodoBehavior.TIME -> "Time-based"
}

/** One line saying what the chosen Task variety does. */
private fun behaviorNote(behavior: TodoBehavior): String = when (behavior) {
    TodoBehavior.NORMAL -> "Simple checkbox: directly mark as completed"
    TodoBehavior.ATTEMPTED -> "3-step progress: Not started → Attempted → Completed"
    TodoBehavior.TIME -> "Duration tracker: log completed time against a target"
}

/** The offered target durations for a time-tracking todo, in minutes. */
internal val DURATION_CHOICES = listOf(15, 30, 45, 60, 120, 180)

/**
 * "15m", "1h", "2h 30m" — a duration as a reader would say it.
 *
 * Never "90m": past an hour the hours count, because the point of a target is
 * to be judged at a glance.
 */
internal fun durationLabel(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    if (safe < 60) return "${safe}m"
    val hours = safe / 60
    val rest = safe % 60
    return if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
}

/** "Today" / "Tomorrow" / "This week" / "Aug 12 – Aug 16" for the period row. */
internal fun periodLabel(choice: PeriodChoice, start: LocalDate, end: LocalDate): String = when (choice) {
    PeriodChoice.TODAY -> "Today"
    PeriodChoice.TOMORROW -> "Tomorrow"
    PeriodChoice.THIS_WEEK -> "This week"
    PeriodChoice.CUSTOM -> if (start == end) {
        DATE_FORMAT.format(start)
    } else {
        "${DATE_FORMAT.format(start)} – ${DATE_FORMAT.format(end)}"
    }
}

/**
 * The header of the editor: back, what the screen is, and a line saying what it
 * is for.
 *
 * The subtitle is not decoration — "only the name is required" is the one thing
 * a reader needs before deciding how much of a long form to fill in, and the
 * form's Save button is reachable from anywhere now that it is pinned.
 */
@Composable
private fun EditorTopBar(title: String, subtitle: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.todo_back)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    }
}

/**
 * A section heading: a small tinted badge with the section's icon, its name,
 * and a divider separating it from the section above.
 *
 * The form is long, and a heading that is only slightly bolder than the labels
 * under it does not separate anything — the icon and the rule are what make it
 * skimmable, so a reader looking for the reminder style can find it without
 * reading the whole page.
 */
@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    first: Boolean = false
) {
    if (first) {
        Spacer(Modifier.height(6.dp))
    } else {
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(16.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
    }
    Spacer(Modifier.height(10.dp))
}

/**
 * The pinned Save action at the bottom of the editor.
 *
 * Pinned rather than last: this form scrolls for several screens, and a save
 * button at the end of it is invisible for most of the time the reader spends
 * filling it in — which is how a form ends up feeling as if it has no way
 * forward.
 */
@Composable
private fun EditorSaveBar(onSave: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(48.dp)
            ) {
                Text(stringResource(R.string.todo_save))
            }
        }
    }
}

/**
 * The extras of an ALARM-style reminder: how long it rings, and which sound.
 *
 * Shown only for the Alarm style, because a notification has neither — and
 * hidden entirely while the style is Off, so the two settings can never look
 * like they apply to a reminder that will not fire.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AlarmTuning(
    style: ReminderStyle,
    minutes: Int,
    uri: String?,
    onPick: () -> Unit,
    onReset: () -> Unit,
    onRingChoice: () -> Unit
) {
    if (style != ReminderStyle.ALARM) return
    val context = LocalContext.current
    val soundLabel = AlarmSounds.shortLabel(context, uri)

    Spacer(Modifier.height(16.dp))

    // ── Ring length ──
    // One row naming the current length, not six boxes: the reader changes it
    // a handful of times in the life of a todo, and six chips on screen cost
    // every reader every time they open the form.
    EditorChoiceRow(
        label = stringResource(R.string.todo_alarm_ring_for),
        value = stringResource(R.string.todo_alarm_minutes, minutes),
        supporting = stringResource(R.string.todo_alarm_ring_note),
        onClick = { onRingChoice() }
    )

    // ── Sound ──
    Spacer(Modifier.height(14.dp))
    FieldLabel(stringResource(R.string.todo_alarm_sound))
    Spacer(Modifier.height(6.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (soundLabel == null) Icons.Filled.Alarm else Icons.Filled.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = soundLabel
                        ?: stringResource(R.string.todo_alarm_sound_system),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        if (soundLabel == null) R.string.todo_alarm_sound_note
                        else R.string.todo_alarm_sound_device
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onPick) {
                Text(
                    stringResource(
                        if (soundLabel == null) R.string.todo_alarm_sound_choose
                        else R.string.todo_alarm_sound_change
                    )
                )
            }
        }
    }
    if (soundLabel != null) {
        TextButton(onClick = onReset, modifier = Modifier.align(Alignment.Start)) {
            Text(stringResource(R.string.todo_alarm_sound_reset))
        }
    }
}

/** How the automatic reminder fires: Off / Notification / real system Alarm. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderStyleRow(
    style: ReminderStyle,
    onSelect: (ReminderStyle) -> Unit,
    onRequestNotifications: () -> Unit
) {
    FieldLabel(stringResource(R.string.todo_reminder_style))
    Spacer(Modifier.height(8.dp))
    // Off / Notification / Alarm is a three-way MODE: one segmented control,
    // which is also what keeps the label, the choice and its explanation
    // reading as a single setting.
    EditorSegments(
        options = listOf(
            stringResource(R.string.todo_reminder_style_off),
            stringResource(R.string.todo_reminder_style_notification),
            stringResource(R.string.todo_reminder_style_alarm)
        ),
        selectedIndex = when (style) {
            ReminderStyle.OFF -> 0
            ReminderStyle.NOTIFICATION -> 1
            ReminderStyle.ALARM -> 2
        },
        onSelect = { onSelect(ReminderStyle.entries[it]) }
    )
    Spacer(Modifier.height(6.dp))
    HintText(
        stringResource(
            when (style) {
                ReminderStyle.OFF -> R.string.todo_reminder_style_off_note
                ReminderStyle.NOTIFICATION -> R.string.todo_reminder_style_notification_note
                ReminderStyle.ALARM -> R.string.todo_reminder_style_alarm_note
            }
        )
    )
    // Notification-style reminders are ALSO scheduled with exact alarms
    // (setExactAndAllowWhileIdle), so the exact-alarm permission matters for
    // both styles — without it, reminders silently fall back to inexact
    // timing and arrive minutes late. Explain and offer the one-tap
    // system-settings link for either style (not just Alarm).
    val context = LocalContext.current
    if (style != ReminderStyle.OFF) {
        if (!TodoScheduler.hasExactAlarmPermission(context)) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(
                        text = stringResource(R.string.todo_exact_alarm_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { openExactAlarmSettings(context) }) {
                        Text(stringResource(R.string.todo_exact_alarm_allow))
                    }
                }
            }
        }
    }
    // Android 13+: a Notification-style reminder is silently dropped when
    // POST_NOTIFICATIONS isn't granted — ask for it right here so the user
    // knows the reminder will actually appear.
    if (style == ReminderStyle.NOTIFICATION && needsNotificationPermission(context)) {
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                Text(
                    text = stringResource(R.string.todo_notification_permission_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onRequestNotifications) {
                    Text(stringResource(R.string.todo_notification_permission_allow))
                }
            }
        }
    }
}

/** True when the app still needs the Android 13+ notification permission. */
private fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
}

/** Opens the system page where the user can allow exact alarms (Android 12+). */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }
}
