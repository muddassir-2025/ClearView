package com.muddassir.clearview.todo.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.data.TodoCodec
import com.muddassir.clearview.todo.data.TodoFilter
import com.muddassir.clearview.todo.data.TodoSort
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.todo.data.TodoStats
import com.muddassir.clearview.todo.data.TodoStore
import com.muddassir.clearview.todo.data.completionRefusalMessage
import com.muddassir.clearview.todo.model.TodoBehavior
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoType
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DONE_GREEN = Color(0xFF43A047)
private val MISSED_RED = Color(0xFFE53935)

/** Amber accent for ATTEMPTED occurrences (bar segment, legend, badges). */
private val ATTEMPT_AMBER = Color(0xFFF9A825)
// Card accents: Temporary = warm amber (it will expire), Permanent = cool blue
// (it stays) — a thin left bar on each card instead of a text pill. Upcoming =
// violet (scheduled, not yet actionable), a small dot before the title.
private val TEMP_AMBER = Color(0xFFB26A00)
private val PERM_BLUE = Color(0xFF1565C0)
private val UPCOMING_VIOLET = Color(0xFF6A1B9A)
// Locale-aware formatters: built per locale (from the observable Compose
// configuration) instead of frozen to the app's locale at class-load time, so
// they react to locale changes while the app runs (ConstantLocale fix).
private fun historyDateFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d", locale)

private fun monthNameFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy", locale)

/** Short month name for the heatmap's month labels ("Aug", "Sep"). */
private fun monthShortFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM", locale)

/**
 * The filter chips, in display order, with their label resources. Declared once
 * so the chip row can never drift out of sync with the filter enum (a new
 * filter that is forgotten here is also caught by the exhaustive `when` in
 * [ListHeader]).
 */
private val FILTER_CHIPS = listOf(
    TodoFilter.TODAY to R.string.todo_filter_today,
    TodoFilter.UPCOMING to R.string.todo_filter_upcoming,
    TodoFilter.COMPLETED to R.string.todo_filter_completed,
    TodoFilter.HISTORY to R.string.todo_filter_history,
    TodoFilter.ALL to R.string.todo_filter_all,
    TodoFilter.TEMPORARY to R.string.todo_filter_temporary,
    TodoFilter.PERMANENT to R.string.todo_filter_permanent
)

/** A request to open the day dialog for one day. */
private data class DayDialogRequest(val day: LocalDate)

/**
 * The Todo screen — a calm productivity dashboard with a clean hierarchy:
 *
 *   Today → Upcoming → History → All → Temporary → Permanent
 *
 * Today is the completion tab: todos due today carry a checkbox — the ONLY
 * place completion happens and the only interactive thing on the tab.
 * Tapping ticks it; tapping again un-completes (a strict-interval day is
 * locked either way once its window closes — "can't redo"). The ⋮ menu
 * (Edit / Snooze / Delete) lives in the Temporary and Permanent lists; a
 * future todo can never be completed early. Upcoming groups future todos by
 * their required date, purely informational. History separates completed and
 * missed past todos with destructive-action confirmations.
 * Below the list: the daily target + weekly progress strip, the explainable
 * Weekly Score (tap for the full breakdown), Weekly Insights, Statistics and
 * a month calendar that works together with the weekly system.
 *
 * Opened from the Quran settings sheet (Todo card); rendered as a full-screen
 * dialog like the other hub screens.
 */
@Composable
fun TodoScreen(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            TodoScreenContent(onDismiss = onDismiss)
        }
    }
}

@Composable
private fun TodoScreenContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TodoStore(context.applicationContext) }
    var items by remember { mutableStateOf(store.getItems()) }
    var filter by remember { mutableStateOf(TodoFilter.TODAY) }
    var sort by remember { mutableStateOf(TodoSort.SMART) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TodoItem?>(null) }
    var pendingDelete by remember { mutableStateOf<TodoItem?>(null) }
    var snoozing by remember { mutableStateOf<TodoItem?>(null) }
    var dayDialog by remember { mutableStateOf<DayDialogRequest?>(null) }
    // The month opened from the twelve-month chart (MonthStatsDialog).
    var monthDialog by remember { mutableStateOf<YearMonth?>(null) }
    var showTargetDialog by remember { mutableStateOf(false) }
    var showScoreDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    // Shareable Progress Card generator (Statistics section header button).
    var showProgressCard by remember { mutableStateOf(false) }
    var dailyTarget by remember { mutableStateOf(store.getDailyTarget()) }
    var calendarMonth by remember { mutableStateOf(YearMonth.from(LocalDate.now())) }
    // \"Today\" refreshes at midnight while the screen stays open, so the list,
    // day strip, calendar and stats always reflect the actual current day.
    var today by remember { mutableStateOf(LocalDate.now()) }
    // Bumped after an in-app snooze so the card's snoozed window updates
    // immediately; nowMinute refreshes every minute so a snoozed window also
    // disappears right after its alarm fires.
    var snoozeTick by remember { mutableStateOf(0) }
    var nowMinute by remember { mutableStateOf(System.currentTimeMillis() / 60_000L) }
    // nowMillis refreshes every minute too — it drives strict-interval rules
    // (a window closing today locks that todo as missed within a minute) and
    // the history / stats that depend on it.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            today = LocalDate.now()
            nowMillis = System.currentTimeMillis()
            nowMinute = nowMillis / 60_000L
        }
    }

    // Single source of truth: notification actions (Complete / snooze) and any
    // other screen publish the persisted list through the store's flow, so
    // this screen stays in lock-step with them.
    LaunchedEffect(Unit) {
        store.items.collect { latest -> if (latest != null) items = latest }
    }

    // Reminders may be stale after a restart; re-sync cheaply on open.
    LaunchedEffect(Unit) { TodoScheduler.rescheduleAll(context) }

    fun save(newItems: List<TodoItem>) {
        items = newItems
        store.saveItems(newItems)
        TodoScheduler.rescheduleAll(context)
    }

    fun toggle(item: TodoItem, day: LocalDate = today) {
        // Date rule: a todo can only be toggled on a day it is applicable on
        // (for the list that is always today — future todos show no
        // checkbox). Strict-interval todos add a second rule: completion is
        // only allowed while the window is OPEN, and a COMPLETED day is
        // equally locked once the window closes ("can't redo" works both
        // ways) — so un-completing is only possible while the window is still
        // open. nowMillis (refreshed every minute) matches the checkbox state
        // exactly, so the visible affordance and the enforcement can never
        // disagree. The notification Complete path enforces the same rule
        // with the real clock.
        if (!TodoCodec.isActiveOn(item, day)) {
            Toast.makeText(
                context,
                completionRefusalMessage(context, TodoCodec.CompletionRefusal.NOT_ACTIVE_DAY),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (TodoCodec.completedOn(item, day)) {
            // Un-complete — blocked once a strict window has closed (the day
            // is locked as done, mirroring "can't redo" for missed days).
            if (TodoCodec.intervalEnded(item, day, nowMillis)) {
                Toast.makeText(
                    context,
                    completionRefusalMessage(context, TodoCodec.CompletionRefusal.WINDOW_CLOSED),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        } else {
            // A refused completion says WHY. Silently doing nothing is what made
            // a snoozed reminder answered after its window closed look like a
            // broken checkbox.
            TodoCodec.completionRefusal(item, day, nowMillis)?.let { refusal ->
                Toast.makeText(
                    context,
                    completionRefusalMessage(context, refusal),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }
        val (updated, nowCompleted) = TodoCodec.toggled(items, item.id, day, nowMillis)
        // Persist the completion FIRST, then cancel EVERY reminder for this
        // occurrence (all index offsets) — so a concurrent reschedule can never
        // revive an alarm for a day that is already completed.
        save(updated)
        if (nowCompleted) {
            TodoScheduler.cancelAllRemindersForTodo(context, item.id, day.toEpochDay())
        }
    }

    val filtered = remember(items, filter, sort, query, today, nowMillis) {
        val q = query.trim().lowercase()
        val matches = { it: TodoItem ->
            q.isEmpty() || it.title.lowercase().contains(q) || it.details.lowercase().contains(q)
        }
        if (filter == TodoFilter.HISTORY) {
            TodoCodec.historySorted(items, today, nowMillis).map { it.item }.filter(matches)
        } else {
            TodoCodec.sorted(
                TodoCodec.filter(items, filter, today, nowMillis).filter(matches),
                sort,
                today
            )
        }
    }
    // History sections honor the search query exactly like the header count,
    // so the list, the sections and the header can never disagree.
    val q = query.trim().lowercase()
    val historyCompleted = remember(items, today, q, nowMillis) {
        TodoCodec.historyCompleted(items, today, nowMillis).filter {
            q.isEmpty() || it.item.title.lowercase().contains(q) ||
                it.item.details.lowercase().contains(q)
        }
    }
    val historyMissed = remember(items, today, q, nowMillis) {
        TodoCodec.historyMissed(items, today, nowMillis).filter {
            q.isEmpty() || it.item.title.lowercase().contains(q) ||
                it.item.details.lowercase().contains(q)
        }
    }
    val dueToday = remember(items, today) { items.count { TodoCodec.isActiveOn(it, today) } }
    val completedToday = remember(items, today) {
        items.count { TodoCodec.isActiveOn(it, today) && TodoCodec.completedOn(it, today) }
    }
    // The dashboard's period: the WEEK (default) or the MONTH, chosen right on
    // the insights card. Both sets are built from the same items, so switching
    // the toggle can never surface a number the other period disagrees with.
    var statsPeriod by remember { mutableStateOf(TodoStats.TodoPeriod.WEEK) }
    val weekInsights = remember(items, today, nowMillis) {
        TodoStats.weekInsights(items, today, nowMillis)
    }
    val monthInsights = remember(items, today, nowMillis) {
        TodoStats.monthInsights(items, today, nowMillis)
    }
    val insights =
        if (statsPeriod == TodoStats.TodoPeriod.WEEK) weekInsights else monthInsights
    // The month view's chart: a trailing twelve months, so its shape is the
    // year and any column can be opened for that month's statistics.
    val monthBars = remember(items, today, nowMillis) {
        TodoStats.monthBars(items, today, nowMillis)
    }
    val monthStats = remember(items, today) { TodoStats.monthStats(items, today) }
    val calendarStats = remember(items, calendarMonth, today) {
        TodoStats.monthStats(items, calendarMonth, today)
    }
    // Upcoming todos (grouped by required date) for the Upcoming tab AND the
    // \"all done for today\" mini section on the Today tab.
    val upcomingList = remember(items, today) {
        TodoCodec.filter(items, TodoFilter.UPCOMING, today)
            .sortedBy { TodoCodec.nextActiveDate(it, today) }
    }
    val upcomingGroups = remember(items, today) {
        val list = TodoCodec.filter(items, TodoFilter.UPCOMING, today)
            .sortedBy { TodoCodec.nextActiveDate(it, today) }
        list.groupBy { TodoCodec.nextActiveDate(it, today)!! }
            .toSortedMap(compareBy { it })
    }
    val todayAllDone = dueToday > 0 && completedToday == dueToday
    // How many todos each filter would show — rendered inside the chip so the
    // user can see where things are without tapping through every filter.
    val filterCounts = remember(items, today, nowMillis) {
        FILTER_CHIPS.associate { (option, _) ->
            option to if (option == TodoFilter.HISTORY) {
                TodoCodec.historySorted(items, today, nowMillis).size
            } else {
                TodoCodec.filter(items, option, today, nowMillis).size
            }
        }
    }
    // Snoozed reminders, per todo: "1:58 PM → 2:08 PM" (original reminder time
    // → new snoozed fire time). Shown on the card's meta line until the
    // snoozed alarm fires (or the snooze is cancelled).
    val snoozedWindows = remember(items, today, snoozeTick, nowMinute) {
        val snoozed = store.getSnoozedReminders()
        if (snoozed.isEmpty()) return@remember emptyMap()
        val now = System.currentTimeMillis()
        buildMap<String, String> {
            items.forEach { item ->
                val reminder = item.reminder ?: return@forEach
                var best: Pair<Long, String>? = null
                reminder.timesMinutes.indices.forEach { index ->
                    val rec = snoozed["${item.id}#$index"] ?: return@forEach
                    if (rec.fireAtMillis <= now) return@forEach
                    val window = TodoCodec.timeLabel(reminder.timesMinutes[index]) +
                        " → " + TodoCodec.timeLabelFromMillis(rec.fireAtMillis)
                    if (best == null || rec.fireAtMillis < best!!.first) {
                        best = rec.fireAtMillis to window
                    }
                }
                best?.let { put(item.id, it.second) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── Top bar: back · title · search · sort ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.todo_back)
                )
            }
            Text(
                text = stringResource(R.string.todo_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.todo_search),
                    tint = if (showSearch) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                var sortMenuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(
                        Icons.Filled.Sort,
                        contentDescription = stringResource(R.string.todo_sort_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    Text(
                        text = stringResource(R.string.todo_sort_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    listOf(
                        TodoSort.SMART to R.string.todo_sort_smart,
                        TodoSort.TIME to R.string.todo_sort_time,
                        TodoSort.PRIORITY to R.string.todo_sort_priority,
                        TodoSort.CREATED to R.string.todo_sort_created,
                        TodoSort.STATUS to R.string.todo_sort_status
                    ).forEach { (option, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(label),
                                    fontWeight = if (sort == option) FontWeight.Bold else null
                                )
                            },
                            onClick = {
                                sort = option
                                sortMenuOpen = false
                            },
                            leadingIcon = {
                                if (sort == option) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        // ── Filter chips: Today · Upcoming · Completed · History · All ·
        //    Temporary · Permanent, each with its live count ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FILTER_CHIPS.forEach { (option, label) ->
                // The count is part of the label so screen readers announce it
                // too ("Today · 3"); it always matches the list below because
                // both come from the same TodoCodec call.
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = {
                        Text(
                            text = stringResource(
                                R.string.todo_chip_with_count,
                                stringResource(label),
                                filterCounts[option] ?: 0
                            )
                        )
                    }
                )
            }
        }

        // ── Search ──
        if (showSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.todo_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                EmptyState(onAdd = { adding = true })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        ListHeader(
                            filter = filter,
                            completed = completedToday,
                            due = dueToday,
                            target = dailyTarget,
                            shown = filtered.size,
                            onEditTarget = { showTargetDialog = true }
                        )
                    }
                    when (filter) {
                        TodoFilter.HISTORY -> {
                            item {
                                HistorySection(
                                    completed = historyCompleted,
                                    missed = historyMissed,
                                    // "Clear" only hides the cards from the
                                    // History view — the completion data stays
                                    // (today's count / progress / stats keep
                                    // counting it). Reset is the destructive
                                    // one, gated by a typed confirmation.
                                    onClearCompleted = {
                                        save(TodoCodec.removeCompletedHistory(items, today, nowMillis))
                                    },
                                    onClearMissed = {
                                        save(TodoCodec.removeMissedHistory(items, today, nowMillis))
                                    },
                                    onClearAll = {
                                        save(TodoCodec.clearHistory(items, today, nowMillis))
                                    },
                                    onReset = { showResetDialog = true }
                                )
                            }
                        }

                        TodoFilter.UPCOMING -> {
                            if (upcomingGroups.isEmpty()) {
                                item {
                                    EmptyLine(stringResource(R.string.todo_upcoming_empty))
                                }
                            } else {
                                upcomingGroups.forEach { (date, list) ->
                                    item {
                                        UpcomingGroupHeader(date = date, today = today)
                                    }
                                    items(list, key = { it.id }) { todo ->
                                        TodoCard(
                                            item = todo,
                                            isDueToday = false,
                                            completedToday = false,
                                            canComplete = false,
                                            missedToday = false,
                                            snoozeWindow = snoozedWindows[todo.id],
                                            // Upcoming is a view-only list — the manage-actions
                                            // (Edit / Snooze / Delete) live in the Temporary /
                                            // Permanent lists.
                                            showActions = false,
                                            onToggle = {},
                                            onEdit = { editing = todo },
                                            onDelete = { pendingDelete = todo },
                                            onSnooze = { snoozing = todo }
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            if (filtered.isEmpty()) {
                                item {
                                    // "Completed" gets its own nudge — nothing done
                                    // yet is a normal state, not a failed search.
                                    EmptyLine(
                                        stringResource(
                                            if (filter == TodoFilter.COMPLETED) {
                                                R.string.todo_completed_none
                                            } else {
                                                R.string.todo_no_match
                                            }
                                        )
                                    )
                                }
                            } else {
                                items(filtered, key = { it.id }) { todo ->
                                    TodoCard(
                                        item = todo,
                                        isDueToday = TodoCodec.isActiveOn(todo, today),
                                        // A completion only counts as "completed today" when the todo
                                        // is actually due TODAY — a stale completion on a day the new
                                        // plan doesn't apply to (e.g. a tomorrow todo that was completed
                                        // before being moved) must never strike it through or tint it.
                                        completedToday = TodoCodec.isActiveOn(todo, today) &&
                                            TodoCodec.completedOn(todo, today),
                                        // The completion checkbox is ONLY for Today — the one
                                        // place a todo is actionable. Temporary / Permanent
                                        // cards manage the plan (⋮ menu) but never complete;
                                        // All stays a strictly view-only aggregate.
                                        canComplete = filter == TodoFilter.TODAY &&
                                            TodoCodec.canCompleteOn(todo, today, nowMillis),
                                        // A todo completed today keeps its checkmark ONLY on the
                                        // Today tab — the same card in the other lists shows the
                                        // completion through styling, never a checkbox.
                                        showCheckbox = filter == TodoFilter.TODAY,
                                        // A strict-interval todo whose window closed today
                                        // uncompleted is LOCKED as missed — shown at a glance.
                                        missedToday = TodoCodec.isActiveOn(todo, today) &&
                                            !TodoCodec.completedOn(todo, today) &&
                                            TodoCodec.intervalEnded(todo, today, nowMillis),
                                        // A completed strict-interval todo whose window closed
                                        // is equally locked (as done) — its checkbox shows
                                        // ticked but cannot be un-completed.
                                        toggleLocked = TodoCodec.completedOn(todo, today) &&
                                            TodoCodec.intervalEnded(todo, today, nowMillis),
                                        snoozeWindow = snoozedWindows[todo.id],
                                        // Temporary and Permanent are the manageable lists
                                        // (⋮ menu: Edit / Snooze / Delete) — Today is purely
                                        // checkbox-based, and All stays a view-only aggregate.
                                        showActions = filter == TodoFilter.TEMPORARY ||
                                            filter == TodoFilter.PERMANENT ||
                                            filter == TodoFilter.TODAY,
                                        onToggle = { toggle(todo) },
                                        onEdit = { editing = todo },
                                        onDelete = { pendingDelete = todo },
                                        onSnooze = { snoozing = todo },
                                        onAttempt = { store.markAttempted(todo.id, today) },
                                        onUnattempt = { store.markUnattempted(todo.id, today) },
                                        onAddTime = { mins -> store.addTime(todo.id, mins, today) }
                                    )
                                }
                            }
                            // Today all done → naturally surface what's next.
                            if (filter == TodoFilter.TODAY && todayAllDone && upcomingList.isNotEmpty()) {
                                item {
                                    Spacer(Modifier.height(8.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(12.dp))
                                }
                                item {
                                    Text(
                                        text = stringResource(R.string.todo_today_all_done),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.todo_upcoming_section),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                upcomingList.take(5).forEach { todo ->
                                    item(key = "up-$todo.id") {
                                        TodoCard(
                                            item = todo,
                                            isDueToday = false,
                                            completedToday = false,
                                            canComplete = false,
                                            missedToday = false,
                                            snoozeWindow = snoozedWindows[todo.id],
                                            // Today is purely checkbox-based — these "up next"
                                            // teasers are view-only (not due today, so no
                                            // completion checkbox and no ⋮ menu).
                                            showActions = false,
                                            onToggle = {},
                                            onEdit = { editing = todo },
                                            onDelete = { pendingDelete = todo },
                                            onSnooze = { snoozing = todo }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))
                    }
                    // Consolidated Productivity dashboard: streaks, weekly
                    // score + week-over-week trend, the weekly strip, insights,
                    // statistics, the attempted-aware bar graph and the heatmap
                    // — one coherent surface instead of scattered sections.
                    item {
                        ProductivityDashboard(
                            insights = insights,
                            period = statsPeriod,
                            onPeriodChange = { statsPeriod = it },
                            monthBars = monthBars,
                            items = items,
                            today = today,
                            nowMillis = nowMillis,
                            onDayTap = { dayDialog = DayDialogRequest(it) },
                            onMonthTap = { monthDialog = it },
                            onScoreClick = { showScoreDialog = true },
                            onShareProgress = { showProgressCard = true }
                        )
                    }
                    item {
                        CalendarSection(
                            stats = calendarStats,
                            month = calendarMonth,
                            today = today,
                            onPrevMonth = { calendarMonth = calendarMonth.minusMonths(1) },
                            onNextMonth = { calendarMonth = calendarMonth.plusMonths(1) },
                            onDayTap = { dayDialog = DayDialogRequest(it) }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }

            // ── Add Todo ──
            FloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.todo_add))
            }
        }
    }

    // ── Add / edit ──
    if (adding) {
        TodoEditorDialog(
            initial = null,
            onSave = { item ->
                save(TodoCodec.added(items, item))
                adding = false
            },
            onDismiss = { adding = false }
        )
    }
    editing?.let { item ->
        TodoEditorDialog(
            initial = item,
            onSave = { updated ->
                // Editing a completed todo gives it a fresh start: its
                // completion record is cleared, so it becomes a new, actionable
                // todo and leaves Completed history ("it becomes new").
                save(
                    if (item.completions.isEmpty()) TodoCodec.updated(items, updated)
                    else TodoCodec.editedAsNew(items, updated)
                )
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    // ── Delete confirmation (with an explicit history choice) ──
    pendingDelete?.let { item ->
        DeleteTodoDialog(
            item = item,
            onConfirm = { deleteHistory ->
                // Cancel its alarms first — after removal the store no longer
                // knows this todo.
                TodoScheduler.cancelTodo(context, item)
                save(
                    if (deleteHistory) {
                        TodoCodec.removedWithHistory(items, item.id)
                    } else {
                        // Default: keep the history so streaks, the heatmap and
                        // the weekly score never lose a completed day by
                        // accident.
                        TodoCodec.removed(items, item.id)
                    }
                )
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    // ── Snooze (in-app) ──
    val snoozedMsg = stringResource(R.string.todo_snoozed)
    snoozing?.let { item ->
        SnoozeSheet(
            onSnooze = { minutes ->
                TodoScheduler.snoozeNext(context, item.id, minutes)
                snoozeTick++
                Toast.makeText(context, snoozedMsg, Toast.LENGTH_SHORT).show()
                snoozing = null
            },
            onDismiss = { snoozing = null }
        )
    }

    // ── Day dialog (weekly strip / calendar / bar graph) ──
    dayDialog?.let { request ->
        DayTodosDialog(
            day = request.day,
            items = items,
            today = today,
            nowMillis = nowMillis,
            onToggle = { toggle(it, request.day) },
            onDismiss = { dayDialog = null }
        )
    }

    // ── Month statistics (a bar of the twelve-month chart) ──
    monthDialog?.let { month ->
        MonthStatsDialog(
            month = month,
            items = items,
            today = today,
            nowMillis = nowMillis,
            onDismiss = { monthDialog = null }
        )
    }

    // ── Daily completion target ──
    if (showTargetDialog) {
        TargetDialog(
            current = dailyTarget,
            onSave = { target ->
                dailyTarget = target
                store.setDailyTarget(target)
                showTargetDialog = false
            },
            onDismiss = { showTargetDialog = false }
        )
    }

    // ── Weekly score breakdown ──
    if (showScoreDialog) {
        insights.breakdown?.let {
            ScoreBreakdownDialog(insights = insights, onDismiss = { showScoreDialog = false })
        }
    }

    // ── Shareable Progress Card ──
    if (showProgressCard) {
        ProgressCardDialog(
            items = items,
            today = today,
            nowMillis = nowMillis,
            store = store,
            onDismiss = { showProgressCard = false }
        )
    }

    // ── History Reset (DESTRUCTIVE — typed confirmation) ──
    if (showResetDialog) {
        ResetHistoryDialog(
            onConfirm = {
                // A full reset deletes EVERY todo, so the reminder bookkeeping
                // that lives outside the items goes first: every scheduled
                // alarm is cancelled by its own request code and the records
                // (scheduled alarms, pending snoozes, seen markers) are
                // forgotten. After that the item list is wiped, which leaves
                // the feature in exactly the state a fresh install starts in
                // — so save() re-schedules nothing.
                TodoScheduler.cancelAllReminders(context)
                save(TodoCodec.resetEverything())
                showResetDialog = false
            },
            onDismiss = { showResetDialog = false }
        )
    }
}

/** Header above the list: filter name + a filter-specific subtitle line. */
@Composable
private fun ListHeader(
    filter: TodoFilter,
    completed: Int,
    due: Int,
    target: Int,
    shown: Int,
    onEditTarget: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            text = stringResource(
                when (filter) {
                    TodoFilter.TODAY -> R.string.todo_today_title
                    TodoFilter.UPCOMING -> R.string.todo_upcoming_title
                    TodoFilter.ALL -> R.string.todo_all_title
                    TodoFilter.COMPLETED -> R.string.todo_completed_title
                    TodoFilter.HISTORY -> R.string.todo_history_title
                    TodoFilter.TEMPORARY -> R.string.todo_temporary_title
                    TodoFilter.PERMANENT -> R.string.todo_permanent_title
                }
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        when (filter) {
            TodoFilter.TODAY -> {
                if (due > 0) {
                    val pct = (completed.toFloat() / target * 100).toInt().coerceIn(0, 100)
                    Text(
                        text = stringResource(
                            R.string.todo_today_target_progress,
                            completed, target, pct
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                } else {
                    Text(
                        text = stringResource(R.string.todo_today_none),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pluralStringResource(R.plurals.todo_daily_target_label, target, target),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = onEditTarget, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.todo_daily_target_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            else -> Text(
                text = pluralStringResource(R.plurals.todo_count, shown, shown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** A short centered empty line for a filter with no matches. */
@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        textAlign = TextAlign.Center
    )
}

/** A group header for the Upcoming list: \"Tomorrow\" / \"Wednesday\" / \"Mon, Aug 12\". */
@Composable
private fun UpcomingGroupHeader(date: LocalDate, today: LocalDate) {
    val locale = LocalConfiguration.current.locales[0]
    val label = when (date) {
        today.plusDays(1) -> stringResource(R.string.todo_tomorrow)
        else -> if (date <= today.plusDays(7)) {
            date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        } else {
            historyDateFormat(locale).format(date)
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

/**
 * One todo card: checkbox (only in Today, when due today) — ticking it
 * completes, tapping it again un-completes; a completed strict-interval todo
 * whose window closed shows it ticked but DISABLED (locked as done, "can't
 * redo" both ways). Title, details, schedule meta, and — only when
 * [showActions] — the ⋮ menu with Edit / Snooze / Delete. A strict-interval
 * todo whose window closed today uncompleted shows a red Missed chip and NO
 * checkbox — it is locked (\"can't redo\"). When a snooze is pending,
 * [snoozeWindow] (\"1:58 PM → 2:08 PM\") replaces the reminder portion.
 */
@Composable
private fun TodoCard(
    item: TodoItem,
    isDueToday: Boolean,
    completedToday: Boolean,
    canComplete: Boolean,
    missedToday: Boolean,
    /**
     * True only in the Today view. Completion checkboxes are exclusive to
     * Today: outside it [completedToday] still styles the card (strike-through
     * + tint), but a checkbox is never rendered — a todo completed today in
     * Temporary / Permanent / All is presented as done, not toggleable.
     */
    showCheckbox: Boolean = false,
    /**
     * A completed strict-interval todo whose window has closed: its checkbox
     * renders ticked but disabled — the day is locked as done ("can't redo"
     * works both ways), so it cannot be un-completed.
     */
    toggleLocked: Boolean = false,
    snoozeWindow: String?,
    /** False in the Today / All / Upcoming views, which are view-only. */
    showActions: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSnooze: () -> Unit,
    onAttempt: (() -> Unit)? = null,
    /** Clears the ATTEMPTED state (the toggle's other direction). */
    onUnattempt: (() -> Unit)? = null,
    onAddTime: ((Int) -> Unit)? = null
) {
    val today = LocalDate.now()
    var menuOpen by remember(item.id) { mutableStateOf(false) }
    // Declared up here (not in the text column) because BOTH the state line and
    // the ⋮ menu need them: the menu offers the attempted toggle, the line shows
    // the points the current state has actually banked.
    val isAttempted = remember(item, today) { TodoCodec.isAttemptedOn(item, today) }
    val timeSpent = remember(item, today) { TodoCodec.timeSpentOn(item, today) }
    // Points banked for this occurrence — the same TodoStats.occurrenceScore
    // every other surface uses, so a partial state (attempted / part of the
    // time logged) is visibly worth something instead of silently earning 0.
    val points = remember(item, today) { TodoStats.occurrenceScore(item, today) }
    val meta = remember(item, today, snoozeWindow, missedToday) {
        if (snoozeWindow != null) {
            // "Today: 1:58 PM → 2:08 PM" — the day + the snoozed window.
            "${TodoCodec.scheduleLabel(item, today)}: $snoozeWindow"
        } else {
            val scheduled = TodoCodec.scheduledTimeLabel(item)
            val reminders = TodoCodec.reminderLabel(item)
            buildString {
                append(TodoCodec.scheduleLabel(item, today))
                scheduled?.let { append(" • ").append(it) }
                // Reminders are automatic now — skip them when they are just the
                // scheduled time itself (avoids \"8:00 PM · 8:00 PM\" on the card).
                if (reminders != null && reminders != scheduled) {
                    append(" · ").append(reminders)
                }
            }
        }
    }
    // Type accent for the left bar: amber = Temporary, blue = Permanent.
    val typeAccent = (if (item.type == TodoType.TEMPORARY) TEMP_AMBER else PERM_BLUE)
        .let { if (completedToday) it.copy(alpha = 0.45f) else it }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (completedToday) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Thin colored bar — the type indicator (no more pill chips).
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(typeAccent)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (missedToday) {
                    // Strict window closed uncompleted → LOCKED as missed. No
                    // checkbox, ever — "can't redo". A warning icon marks it.
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MISSED_RED,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else if (showCheckbox && isDueToday && (canComplete || completedToday)) {
                    // Today-only: a completed todo keeps its checkbox — ticked,
                    // and tapping it again un-completes. Only a strict-interval
                    // todo whose window already closed renders it DISABLED
                    // (locked as done, "can't redo" both ways). The title is
                    // part of the description because the checkbox is the only
                    // thing a screen reader sees here.
                    val toggleDesc = stringResource(R.string.todo_checkbox_desc, item.title)
                    Checkbox(
                        checked = completedToday,
                        onCheckedChange = { onToggle() },
                        enabled = !toggleLocked,
                        modifier = Modifier.semantics { contentDescription = toggleDesc }
                    )
                } else {
                    // No completion checkbox here (not the Today view, not
                    // applicable, or the window hasn't opened): a green tick
                    // marks an already-completed todo, a calendar marks a
                    // scheduled one.
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (completedToday) Icons.Filled.CheckCircle else Icons.Filled.DateRange,
                            contentDescription = null,
                            tint = if (completedToday) DONE_GREEN
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // A small violet dot marks "scheduled, not yet due".
                        if (!isDueToday && !missedToday) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(UPCOMING_VIOLET)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (completedToday) TextDecoration.LineThrough else null,
                            color = if (completedToday || missedToday) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        if (missedToday) {
                            MissedChip()
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    if (item.details.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (item.behavior == TodoBehavior.ATTEMPTED && !completedToday) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (isAttempted) {
                                "State: Attempted \u2713  ·  +${points.roundToInt()} pts"
                            } else {
                                "State: Not started"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isAttempted) Color(0xFF00897B) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (item.behavior == TodoBehavior.TIME) {
                        Spacer(Modifier.height(2.dp))
                        val target = item.targetDurationMinutes ?: 60
                        Text(
                            text = "Time: ${timeSpent}m / ${target}m  ·  +${points.roundToInt()} pts",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                timeSpent >= target || completedToday -> DONE_GREEN
                                timeSpent > 0 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                // The ⋮ menu (Edit / Snooze / Delete) shows in the manageable
                // lists: Temporary and Permanent (Today / All / Upcoming are
                // view-only). Missed (locked "can't redo") cards offer Delete
                // ONLY — they are history. Completed cards keep Edit + Delete
                // (editing restarts the todo as new, clearing its completion).
                // Active cards get Edit / Snooze / Delete. Completion is the
                // checkbox's job — and the checkbox only appears in Today.
                if (showActions) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!missedToday) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.todo_edit)) },
                                    onClick = { menuOpen = false; onEdit() },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                            }
                            if (!completedToday && !missedToday) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.todo_snooze)) },
                                    onClick = { menuOpen = false; onSnooze() },
                                    // Only when a reminder actually fires — a
                                    // todo whose reminders are switched OFF
                                    // (reminderStyle Off) must not offer a
                                    // Snooze that would silently re-arm an
                                    // alarm the user disabled.
                                    enabled = item.reminder?.enabled == true,
                                    leadingIcon = {
                                        Icon(Icons.Filled.Snooze, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                            }
                            // ATTEMPTED and TIME todos: a two-way toggle.
                            // Marking it attempted banks partial points (for a
                            // TIME todo, a 50% floor for a session that was
                            // worked on but not logged); marking it unattempted
                            // returns it to "Not started" and takes them back,
                            // so the state is never one-way. A NORMAL todo stays
                            // a plain done/not-done checkbox and gets no toggle.
                            if (item.behavior != TodoBehavior.NORMAL && !completedToday && !missedToday &&
                                (if (isAttempted) onUnattempt else onAttempt) != null
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (isAttempted) "Mark Unattempted" else "Mark Attempted") },
                                    onClick = {
                                        menuOpen = false
                                        if (isAttempted) onUnattempt?.invoke() else onAttempt?.invoke()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isAttempted) Icons.Filled.Close else Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }
                            if (item.behavior == TodoBehavior.TIME && !completedToday && !missedToday && onAddTime != null) {
                                DropdownMenuItem(
                                    text = { Text("+15 min") },
                                    onClick = { menuOpen = false; onAddTime(15) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("+30 min") },
                                    onClick = { menuOpen = false; onAddTime(30) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.todo_delete),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = { menuOpen = false; onDelete() },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Small red \"Missed\" chip on a strict-interval card whose window closed
 * today uncompleted — locked, cannot be redone. (Type is shown by the card's
 * left accent bar; upcoming by the violet dot before the title.)
 */
@Composable
private fun MissedChip() {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = MISSED_RED.copy(alpha = 0.15f)
    ) {
        Text(
            text = stringResource(R.string.todo_missed_chip),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MISSED_RED,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/**
 * History: Completed + Incomplete/Missed sections with History-only cleanup
 * actions. Each section has a **Clear** (hides that section's cards from the
 * History view — completion data stays, so progress/statistics keep counting
 * it). The bottom row offers **Clear All** (hides every card, same non-
 * destructive semantics) and **Reset** (the genuinely destructive one — wipes
 * the progress/history/statistics data, gated behind a typed confirmation).
 * The buttons are always visible (disabled while their section is empty) so
 * the user always knows the options exist.
 */
@Composable
private fun HistorySection(
    completed: List<TodoCodec.HistoryEntry>,
    missed: List<TodoCodec.HistoryEntry>,
    onClearCompleted: () -> Unit,
    onClearMissed: () -> Unit,
    onClearAll: () -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.todo_history_completed_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClearCompleted, enabled = completed.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.todo_history_clear),
                    color = if (completed.isNotEmpty()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        if (completed.isEmpty()) {
            Text(
                text = stringResource(R.string.todo_history_completed_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            HistoryEntryList(completed)
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.todo_history_missed_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClearMissed, enabled = missed.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.todo_history_clear),
                    color = if (missed.isNotEmpty()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        if (missed.isEmpty()) {
            Text(
                text = stringResource(R.string.todo_history_missed_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            HistoryEntryList(missed)
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onClearAll,
                enabled = completed.isNotEmpty() || missed.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.todo_history_clear_all))
            }
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.todo_history_reset),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * The DESTRUCTIVE Reset confirmation: the user must type exactly "RESET"
 * before the button enables — a plain Yes/No is never enough for this one.
 */
@Composable
private fun ResetHistoryDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var typed by remember { mutableStateOf("") }
    val matches = typed == "RESET"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.todo_history_reset_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.todo_history_reset_text),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.todo_history_reset_type_hint)) },
                    singleLine = true,
                    isError = typed.isNotEmpty() && !matches,
                    supportingText = if (typed.isNotEmpty() && !matches) {
                        {
                            Text(
                                stringResource(R.string.todo_history_reset_type_error),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else null
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = matches) {
                Text(
                    text = stringResource(R.string.todo_history_reset_button),
                    color = if (matches) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.todo_cancel)) }
        }
    )
}

/**
 * Delete confirmation. The two outcomes are genuinely different, so the choice
 * is explicit instead of hiding behind one destructive button:
 *  - toggle OFF (default): the todo leaves your lists, but its history stays —
 *    streaks, the heatmap and the weekly score keep every day already earned;
 *  - toggle ON: the todo AND its history go, and every statistic drops it.
 *
 * The explanation below the toggle always describes the CURRENT choice, and
 * the toggle is only offered when there actually is history to lose (a todo
 * with no recorded days has nothing to choose between).
 */
@Composable
private fun DeleteTodoDialog(
    item: TodoItem,
    onConfirm: (deleteHistory: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var deleteHistory by remember(item.id) { mutableStateOf(false) }
    val completedDays = item.completions.size
    val hasHistory = completedDays > 0 || item.events.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.todo_delete_confirm_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.todo_delete_confirm_text, item.title),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!hasHistory) return@Column
                Spacer(Modifier.height(10.dp))
                if (completedDays > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.todo_delete_history_days,
                            completedDays,
                            completedDays
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // The whole row is tappable — an obvious target on a phone.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { deleteHistory = !deleteHistory },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = deleteHistory,
                        onCheckedChange = { deleteHistory = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.todo_delete_history_toggle),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = stringResource(
                        if (deleteHistory) R.string.todo_delete_history_on
                        else R.string.todo_delete_history_off
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (deleteHistory) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteHistory) }) {
                Text(
                    text = stringResource(R.string.todo_delete_confirm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.todo_cancel)) }
        }
    )
}

/**
 * History entries GROUPED by their last occurrence date — a clean date header
 * followed by that day's cards, newest date first.
 */
@Composable
private fun HistoryEntryList(entries: List<TodoCodec.HistoryEntry>) {
    val locale = LocalConfiguration.current.locales[0]
    val fmt = historyDateFormat(locale)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.groupBy { it.lastOccurrence }
            .toSortedMap(compareByDescending { it })
            .forEach { (date, list) ->
                Text(
                    text = fmt.format(date),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
                list.forEach { HistoryRow(it) }
            }
    }
}

/** One history row: an item with its completed / missed past-occurrence counts. */
@Composable
private fun HistoryRow(entry: TodoCodec.HistoryEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (entry.missedCount > 0) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MISSED_RED,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = DONE_GREEN,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val locale = LocalConfiguration.current.locales[0]
                val parts = mutableListOf<String>()
                if (entry.completedCount > 0) {
                    parts.add(stringResource(R.string.todo_history_completed, entry.completedCount))
                }
                if (entry.attemptedCount > 0) {
                    parts.add(stringResource(R.string.todo_history_attempted, entry.attemptedCount))
                }
                if (entry.missedCount > 0) {
                    parts.add(stringResource(R.string.todo_history_missed, entry.missedCount))
                }
                // Time badge: "1h 30m / 2h" for a TIME todo with a target,
                // otherwise just the logged total when there is any.
                val target = entry.item.targetDurationMinutes
                if (entry.item.behavior == TodoBehavior.TIME && target != null && target > 0) {
                    parts.add(
                        stringResource(
                            R.string.todo_history_time_of,
                            formatMinutesLabel(entry.totalMinutes),
                            formatMinutesLabel(target)
                        )
                    )
                } else if (entry.totalMinutes > 0) {
                    parts.add(
                        stringResource(R.string.todo_history_time, formatMinutesLabel(entry.totalMinutes))
                    )
                }
                parts.add(stringResource(R.string.todo_history_last, historyDateFormat(locale).format(entry.lastOccurrence)))
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The WEEK at a glance: one bar per day (Monday first) whose fill shows how much
 * of that day's plan was completed, with the weekday and the completed count
 * underneath and today clearly marked. Tapping a day opens its todos.
 *
 * The bars are scaled against the WEEK'S busiest day (never against an arbitrary
 * maximum), so the shape of the week is readable at a glance; a day that missed
 * its plan keeps an empty track and a red-tinted count, and future days are drawn
 * as empty outlines because a schedule is information, never progress.
 */
@Composable
private fun WeekProgressSection(
    insights: TodoStats.PeriodInsights,
    onDayTap: (LocalDate) -> Unit
) {
    val maxDue = insights.bars.maxOfOrNull { it.due }?.coerceAtLeast(1) ?: 1
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.todo_progress_period,
                    periodAdjective(insights.period)
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${insights.percent}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            insights.bars.forEach { bar ->
                val letter = DAY_LETTERS[bar.start.dayOfWeek.value - 1]
                val missed = !bar.isToday && !bar.isFuture && bar.due > 0 && bar.completed == 0
                val fraction = if (bar.due > 0) {
                    (bar.completed.toFloat() / maxDue).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val fillColor = if (missed) MISSED_RED else DONE_GREEN
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onDayTap(bar.start) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (bar.isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else trackColor
                            ),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (fraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height((46.dp * fraction).coerceAtLeast(6.dp))
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(fillColor)
                            )
                        } else if (missed) {
                            // A missed day shows a thin marker so it can never
                            // read as "nothing was scheduled".
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(MISSED_RED.copy(alpha = 0.7f))
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = letter,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (bar.isToday) FontWeight.Bold else FontWeight.Medium,
                        color = if (bar.isToday) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = bar.completed.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            bar.completed > 0 -> DONE_GREEN
                            missed -> MISSED_RED
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(
                    R.plurals.todo_weekly_count,
                    insights.due,
                    insights.completed,
                    insights.due
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { insights.rate },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
        )
    }
}

/** \"How your score was calculated\" — every component with its contribution. */
@Composable
private fun ScoreBreakdownDialog(insights: TodoStats.PeriodInsights, onDismiss: () -> Unit) {
    val b = insights.breakdown ?: return
    val daysWithDue = insights.daysWithDue
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.todo_score_how_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.todo_score_how_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ScoreRow(
                    label = stringResource(R.string.todo_score_component_completion),
                    value = b.completion,
                    max = b.completionMax,
                    explanation = stringResource(
                        R.string.todo_score_expl_completion_weighted,
                        b.doneWeight.roundToInt(),
                        b.dueWeight.roundToInt()
                    )
                )
                ScoreRow(
                    label = stringResource(R.string.todo_score_component_consistency),
                    value = b.consistency,
                    max = b.consistencyMax,
                    explanation = stringResource(
                        R.string.todo_score_expl_consistency, insights.activeDays, daysWithDue
                    )
                )
                ScoreRow(
                    label = stringResource(R.string.todo_score_component_streak),
                    value = b.streak,
                    max = b.streakMax,
                    explanation = stringResource(R.string.todo_score_expl_streak, b.streakDays)
                )
                ScoreRow(
                    label = stringResource(R.string.todo_score_component_timeliness),
                    value = b.timeliness,
                    max = b.timelinessMax,
                    // Excluded (nothing closed yet) is informative, not a loss:
                    // being "on track" on a not-yet-due todo is not an achievement.
                    explanation = if (b.closedItems == 0) {
                        stringResource(R.string.todo_score_expl_timeliness_none)
                    } else {
                        val onTime = (b.closedItems - b.overdueCount - b.missedCount).coerceAtLeast(0)
                        stringResource(
                            R.string.todo_score_expl_timeliness, onTime, b.closedItems
                        )
                    }
                )
                ScoreRow(
                    label = stringResource(R.string.todo_score_component_volume),
                    value = b.volume,
                    max = b.volumeMax,
                    // Volume is measured against the user's OWN recent baseline,
                    // so it rewards doing more than usual without ever rewarding
                    // simply creating more todos.
                    explanation = if (b.baselineCompleted == null) {
                        stringResource(R.string.todo_score_expl_volume_none)
                    } else {
                        stringResource(
                            R.string.todo_score_expl_volume,
                            insights.completed,
                            b.baselineCompleted.roundToInt()
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.todo_score_total),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.todo_score_value, b.total),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.todo_ok)) }
        }
    )
}

/** One score-breakdown row: label + explanation + earned value. */
@Composable
private fun ScoreRow(
    label: String,
    value: Int,
    max: Int,
    explanation: String,
    minusZero: Boolean = false
) {
    val isPenalty = value < 0 || minusZero
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = when {
                isPenalty -> if (value == 0) "−0" else value.toString()
                max > 0 -> stringResource(R.string.todo_score_plus_of, value, max)
                else -> "+$value"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isPenalty) MISSED_RED
            else if (value == max && max > 0) DONE_GREEN
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * The consolidated PRODUCTIVITY DASHBOARD. A single merged "Weekly/Monthly
 * Insights & Statistics" card carries every number worth reading — completion
 * rate, completed vs pending, progress, volume against the user's own
 * baseline, both streaks, the score (tap for the full breakdown), best day and
 * most productive window — followed by the attempted-aware bar graph and the
 * month-organised heatmap.
 *
 * The Week / Month switch in the header chooses the period. Every value comes
 * from the SAME [TodoStats.PeriodInsights] the other surfaces read, so no two
 * numbers on this screen can disagree — and switching the period changes the
 * numbers, never the layout.
 */
@Composable
private fun ProductivityDashboard(
    insights: TodoStats.PeriodInsights,
    period: TodoStats.TodoPeriod,
    onPeriodChange: (TodoStats.TodoPeriod) -> Unit,
    monthBars: List<TodoStats.MonthBar>,
    items: List<TodoItem>,
    today: LocalDate,
    nowMillis: Long,
    onDayTap: (LocalDate) -> Unit,
    onMonthTap: (YearMonth) -> Unit,
    onScoreClick: () -> Unit,
    onShareProgress: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.todo_dashboard_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            // The Week / Month switch lives here as ONE arrow beside Share:
            // the period is a lens on the same card, not a second section, so
            // it costs no row and cannot be mistaken for another chart.
            PeriodMenu(period = period, onPeriodChange = onPeriodChange)
            IconButton(onClick = onShareProgress, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = stringResource(R.string.todo_insight_share),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        InsightsCard(
            insights = insights,
            onDayTap = { onDayTap(it) },
            onScoreClick = onScoreClick
        )
        // Both periods chart their own unit in the SAME stacked language: seven
        // days for the week, twelve scrollable months for the month.
        if (insights.period == TodoStats.TodoPeriod.WEEK) {
            PeriodBarGraph(insights = insights, onBarTap = onDayTap)
        } else {
            MonthTrendChart(bars = monthBars, onBarTap = onMonthTap)
        }
        TodoProductivityHeatmap(items = items, today = today, nowMillis = nowMillis)
    }
}

/**
 * The Week / Month switch: ONE compact control beside the share button,
 * showing the period the dashboard is currently reading with a drop-down
 * arrow. Replaces the two-chip row — the dashboard header stays a title and
 * two icons, and the arrow still says "there is another period to pick".
 */
@Composable
private fun PeriodMenu(
    period: TodoStats.TodoPeriod,
    onPeriodChange: (TodoStats.TodoPeriod) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { menuOpen = true },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = periodAdjective(period),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.todo_period_menu),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            listOf(TodoStats.TodoPeriod.WEEK, TodoStats.TodoPeriod.MONTH).forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(periodLabel(option))) },
                    leadingIcon = if (option == period) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        onPeriodChange(option)
                        menuOpen = false
                    }
                )
            }
        }
    }
}

/** The resource of a period's own label ("Weekly" / "Monthly"). */
private fun periodLabel(period: TodoStats.TodoPeriod): Int =
    if (period == TodoStats.TodoPeriod.WEEK) R.string.todo_period_week
    else R.string.todo_period_month

/** The period's noun ("week" / "month") for the card's labels. */
@Composable
private fun periodNoun(period: TodoStats.TodoPeriod): String =
    stringResource(
        if (period == TodoStats.TodoPeriod.WEEK) R.string.todo_period_week_noun
        else R.string.todo_period_month_noun
    )

/** The period's adjective ("Weekly" / "Monthly") for the card's headings. */
@Composable
private fun periodAdjective(period: TodoStats.TodoPeriod): String =
    stringResource(
        if (period == TodoStats.TodoPeriod.WEEK) R.string.todo_period_week
        else R.string.todo_period_month
    )

/**
 * ONE card holding the selected period's insights AND statistics — a
 * scannable productivity dashboard rather than a wall of label/value rows.
 *
 * WHAT IT SHOWS (and why each figure earns its place):
 *  1. A completion RING — the period's completion percentage, the single
 *     number that answers "how much did I get done?". Inside it sits the raw
 *     ratio ("34 of 50"), so the percentage is never abstract.
 *  2. The TREND against the period before ("↑ 12% vs last week/month"), i.e.
 *     the improving/falling-behind signal — dormant (with an explanation) in
 *     the first period, where there is nothing honest to compare against.
 *  3. TODAY's remaining/complete state — the actual current workload, which is
 *     what the user can still act on right now.
 *  4. The week's Mon..Sun strip (WEEK view only — the month's breakdown is the
 *     twelve-month chart below the card), each day tappable for its todos.
 *  5. Four tiles: completed today, current streak, active days (consistency)
 *     and volume against the user's OWN recent baseline (throughput).
 *  6. Secondary facts (longest streak, best day, most productive time, logged
 *     time) and the explainable score.
 *
 * Deliberately NOT shown: "todos created", cumulative incomplete counts and
 * other vanity figures that cannot change a decision. Every value comes from
 * the same [TodoStats.PeriodInsights], so no two numbers on this screen can
 * disagree, and everything is either real data or explicitly absent ("No
 * history yet").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InsightsCard(
    insights: TodoStats.PeriodInsights,
    onDayTap: (LocalDate) -> Unit,
    onScoreClick: () -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val noun = periodNoun(insights.period)
    val baseline = insights.baselineCompleted
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = stringResource(
                    if (insights.period == TodoStats.TodoPeriod.WEEK) {
                        R.string.todo_insights_title
                    } else {
                        R.string.todo_insights_title_month
                    }
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            if (insights.due <= 0) {
                // Nothing to measure: a short prompt instead of a wall of
                // zeroes and dashes.
                Text(
                    text = stringResource(
                        if (insights.period == TodoStats.TodoPeriod.WEEK) {
                            R.string.todo_insights_empty
                        } else {
                            R.string.todo_insights_empty_month
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            // ── 1+2+3. Hero: completion ring, trend, what's left today ──
            InsightHero(insights = insights, noun = noun)
            Spacer(Modifier.height(16.dp))

            // ── 4. The week at a glance. Only the WEEK has a strip here: the
            // month's breakdown is the twelve-month chart below, so a second,
            // coarser week-by-week view of the same month would say it twice. ──
            if (insights.period == TodoStats.TodoPeriod.WEEK) {
                WeekProgressSection(insights = insights, onDayTap = onDayTap)
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // ── 5. Key figures as evenly sized tiles. Two per row on a phone,
            // four on a tablet — nothing is squeezed to one letter per line. ──
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val tileModifier = if (wide) {
                    Modifier.weight(1f)
                } else {
                    Modifier.fillMaxWidth(0.48f)
                }
                StatTile(
                    icon = Icons.Filled.CheckCircle,
                    value = stringResource(
                        R.string.todo_card_tile_today_value,
                        insights.todayCompleted,
                        insights.todayDue
                    ),
                    label = stringResource(R.string.todo_card_tile_today_label),
                    tint = DONE_GREEN,
                    modifier = tileModifier
                )
                StatTile(
                    icon = Icons.Filled.LocalFireDepartment,
                    value = if (insights.streak > 0) {
                        pluralStringResource(
                            R.plurals.todo_stats_days_value,
                            insights.streak,
                            insights.streak
                        )
                    } else {
                        stringResource(R.string.todo_card_none)
                    },
                    label = stringResource(R.string.todo_stats_streak_label),
                    tint = if (insights.streak > 0) ATTEMPT_AMBER
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = tileModifier
                )
                StatTile(
                    icon = Icons.Filled.EventAvailable,
                    value = stringResource(
                        R.string.todo_card_tile_active_days_value,
                        insights.activeDays,
                        insights.activeDaysWindow
                    ),
                    label = stringResource(R.string.todo_card_tile_active_days_label_period, noun),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = tileModifier
                )
                StatTile(
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    value = baseline?.let {
                        stringResource(R.string.todo_card_tile_volume_value, insights.completed)
                    } ?: stringResource(R.string.todo_card_none),
                    // The comparison is stated in the LABEL so the tile never
                    // shows a bare number with no context — and a missing
                    // baseline says so instead of implying a zero.
                    label = baseline?.let {
                        stringResource(
                            R.string.todo_card_tile_volume_label_period,
                            it.roundToInt(),
                            noun
                        )
                    } ?: stringResource(R.string.todo_insight_volume_none),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = tileModifier
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // ── 6. Secondary facts + the score, each on a cleanly aligned row
            // (label hard-left, value hard-right, one shared value edge). ──
            // PACe leads when there is one: of everything below, "am I ahead of
            // my OWN usual pace?" is the figure a reader can act on today, and
            // the label states the baseline ("usual 6 by day 10") so the
            // comparison is never a bare percentage.
            insights.paceBaseline?.let { baseline ->
                val pace = insights.pacePercent ?: 0
                InsightRow(
                    label = stringResource(
                        R.string.todo_stats_pace_label,
                        baseline.roundToInt(),
                        insights.today.dayOfMonth
                    ),
                    value = when {
                        pace > 0 -> stringResource(R.string.todo_stats_pace_ahead, pace)
                        pace < 0 -> stringResource(R.string.todo_stats_pace_behind, -pace)
                        else -> stringResource(R.string.todo_stats_pace_on)
                    },
                    valueColor = when {
                        pace > 0 -> DONE_GREEN
                        pace < 0 -> MISSED_RED
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            InsightRow(
                label = stringResource(R.string.todo_stats_longest_label),
                value = pluralStringResource(
                    R.plurals.todo_stats_days_value,
                    insights.longestStreak,
                    insights.longestStreak
                )
            )
            insights.bestDay?.let { best ->
                InsightRow(
                    label = stringResource(R.string.todo_stats_best_day_label),
                    value = stringResource(
                        R.string.todo_stats_best_day,
                        // A week has one of each weekday, so the weekday name is
                        // unambiguous there; a month has four or five of them,
                        // so the month view names the actual DATE.
                        if (insights.period == TodoStats.TodoPeriod.WEEK) {
                            best.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                        } else {
                            historyDateFormat(locale).format(best.date)
                        },
                        (best.rate * 100).toInt()
                    )
                )
            }
            insights.mostProductiveWindow?.let { (start, end) ->
                InsightRow(
                    label = stringResource(R.string.todo_stats_productive_label),
                    value = stringResource(
                        R.string.todo_stats_productive_time,
                        TodoCodec.timeLabel(start * 60),
                        TodoCodec.timeLabel(end * 60)
                    )
                )
            }
            if (insights.productiveMinutes > 0) {
                InsightRow(
                    label = stringResource(R.string.todo_stats_productive_minutes_label),
                    value = formatMinutesLabel(insights.productiveMinutes)
                )
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            ScoreSummaryRow(insights = insights, noun = noun, onClick = onScoreClick)
        }
    }
}

/**
 * The card's hero: the week's PROGRESS as a ring, the raw completed ratio
 * beside it, the week-over-week trend and today's actionable remainder.
 *
 * The ring draws [TodoStats.WeekStats.creditRate], not the raw completion
 * rate: marking a todo attempted or logging part of a time target is real
 * effort, and it has to move the number at the top of the dashboard (it used
 * to move only the 100-point score, so partial work looked like nothing had
 * happened). When partial credit is what lifted the ring, a line says so — so
 * "progress" is never mistaken for "completed".
 *
 * Every value is real: creditRate/rate, [TodoStats.PeriodInsights.remainingToday]
 * and [TodoStats.PeriodInsights.improvementPoints]; the trend is replaced by an
 * explanation instead of a fabricated number.
 */
@Composable
private fun InsightHero(insights: TodoStats.PeriodInsights, noun: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProgressRing(
            fraction = insights.creditRate,
            percentText = "${insights.creditPercent}%",
            captionText = stringResource(
                R.string.todo_card_ring_caption,
                insights.completed,
                insights.due
            ),
            modifier = Modifier.size(94.dp)
        )
        Spacer(Modifier.width(18.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.todo_card_period_label, noun),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    R.string.todo_card_completed_of,
                    insights.completed,
                    insights.due
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            // Partial credit is real progress — say so when it is what lifted
            // the ring, so the percentage can never be read as "completed".
            if (insights.hasPartialCredit) {
                Text(
                    text = pluralStringResource(
                        R.plurals.todo_card_partial_credit,
                        insights.partialOccurrences,
                        insights.partialOccurrences
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = ATTEMPT_AMBER
                )
            }
            // Today's workload — the part that is still actionable.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val allDone = insights.remainingToday == 0
                Icon(
                    imageVector = if (allDone) Icons.Filled.CheckCircle
                    else Icons.Filled.HourglassEmpty,
                    contentDescription = null,
                    tint = if (allDone) DONE_GREEN else ATTEMPT_AMBER,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (allDone) {
                        stringResource(R.string.todo_card_all_done_today)
                    } else {
                        stringResource(R.string.todo_card_left_today, insights.remainingToday)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (allDone) DONE_GREEN else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(2.dp))
            TrendChip(insights = insights, noun = noun)
        }
    }
}

/**
 * The period-over-period trend: completion-rate points against the same period
 * one step back (last week or last month), and a plain explanation when there
 * is genuinely nothing to compare yet. Never a made-up percentage.
 */
@Composable
private fun TrendChip(insights: TodoStats.PeriodInsights, noun: String) {
    val delta = insights.improvementPoints
    val (text, color, icon) = when {
        delta != null && delta > 0 ->
            Triple(
                stringResource(R.string.todo_stats_delta_up_period, delta, noun),
                DONE_GREEN,
                Icons.AutoMirrored.Filled.TrendingUp
            )
        delta != null && delta < 0 ->
            Triple(
                stringResource(R.string.todo_stats_delta_down_period, -delta, noun),
                MISSED_RED,
                Icons.AutoMirrored.Filled.TrendingDown
            )
        delta != null ->
            Triple(
                stringResource(R.string.todo_stats_delta_flat_period, noun),
                MaterialTheme.colorScheme.onSurfaceVariant,
                Icons.AutoMirrored.Filled.TrendingFlat
            )
        insights.firstPeriod ->
            Triple(
                stringResource(R.string.todo_card_first_period, noun),
                MaterialTheme.colorScheme.onSurfaceVariant,
                Icons.Filled.Timeline
            )
        else -> Triple(
            stringResource(R.string.todo_stats_delta_flat_period, noun),
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.AutoMirrored.Filled.TrendingFlat
        )
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 2
            )
        }
    }
}

/**
 * A circular completion indicator: a full track with the completed fraction
 * drawn over it, starting at 12 o'clock. The percentage sits in the middle with
 * the raw ratio beneath it, so the ring is readable without a legend. The arc
 * animates to its value (a short tween) so a data refresh is visible rather
 * than an instant jump.
 */
@Composable
private fun ProgressRing(
    fraction: Float,
    percentText: String,
    captionText: String,
    modifier: Modifier = Modifier
) {
    val target = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 600),
        label = "completion-ring"
    )
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val progressColor = if (target >= 1f) DONE_GREEN else MaterialTheme.colorScheme.primary
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.11f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (animated > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = percentText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = captionText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * One dashboard figure: a tinted icon + value on the first line, its label
 * underneath. Fixed layout (never a wrapping label squeezing the value) so a
 * row of tiles stays aligned at every width.
 */
@Composable
private fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * A label/value row with GUARANTEED alignment: the label takes the space it
 * needs and the value is right-aligned against the card's own right edge, so
 * every value in the card lines up on one vertical axis and the list scans like
 * a table. (The previous version let both columns flex, which made each value
 * start at a different x and read as ragged.) The value drops to a second line
 * before it would ever squeeze the label into one-letter-per-line columns.
 */
@Composable
private fun InsightRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The period's score + period-over-period trend. Tapping it opens the
 * explainable breakdown (the row is not clickable when nothing is due).
 */
@Composable
private fun ScoreSummaryRow(
    insights: TodoStats.PeriodInsights,
    noun: String,
    onClick: () -> Unit
) {
    val score = insights.score
    val delta = insights.improvementPoints
    // The score's plain-language band, so a raw number still means something at
    // a glance (Excellent / Good / Fair / Keep going).
    val band = score?.let {
        stringResource(
            when {
                it >= 80 -> R.string.todo_score_excellent
                it >= 60 -> R.string.todo_score_good
                it >= 40 -> R.string.todo_score_fair
                else -> R.string.todo_score_low
            }
        )
    }
    val trendColor = when {
        delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
        delta >= 0 -> DONE_GREEN
        else -> MISSED_RED
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = score != null, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.todo_score_title_period, noun),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = when {
                    delta == null -> stringResource(R.string.todo_stats_delta_flat_period, noun)
                    delta >= 0 -> stringResource(R.string.todo_stats_delta_up_period, delta, noun)
                    else -> stringResource(
                        R.string.todo_stats_delta_down_period,
                        -delta,
                        noun
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = trendColor
            )
        }
        if (band != null) {
            Text(
                text = band,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = score?.let { stringResource(R.string.todo_score_value, it) }
                ?: stringResource(R.string.todo_score_empty_period, noun),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            maxLines = 1
        )
        if (score != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.todo_score_tap_hint),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * The productivity heatmap card: ONE continuous GitHub-style contribution
 * graph — a square per day, a column per week, Monday at the top of every
 * column — with the month names above the week columns they begin and the
 * weekday letters down the left.
 *
 * The card leads with the numbers the graph adds up to (how much was completed
 * over the window, how many days were worked on, the longest run of them) and a
 * menu for choosing WHICH window: the rolling past year, or any calendar year
 * the reader has data in. The graph itself is the same either way.
 *
 * Shading follows the day's earned score (5 levels: nothing, then four greens);
 * tapping a square shows that day's completed / attempted counts, productive
 * time and score beneath the grid.
 */
@Composable
private fun TodoProductivityHeatmap(
    items: List<TodoItem>,
    today: LocalDate,
    nowMillis: Long
) {
    val locale = LocalConfiguration.current.locales[0]
    var range by remember { mutableStateOf<HeatmapRange>(HeatmapRange.PastYear) }
    var menuOpen by remember { mutableStateOf(false) }
    // Keyed on today: the menu is rebuilt as the clock advances, so next year's
    // entry appears on its own (see [heatmapRangeOptions]).
    val rangeOptions = remember(items, today, range) { heatmapRangeOptions(items, today, range) }
    val columns = remember(range, today) { heatmapColumns(range, today) }
    val monthLabels = remember(columns, range, locale) {
        heatmapMonthLabels(columns, range) { date -> monthShortFormat(locale).format(date) }
    }
    val days = remember(columns) { heatmapDays(columns) }
    val data = remember(items, days, nowMillis) {
        TodoStats.productivityHeatmap(items, days, nowMillis)
    }
    val summary = remember(data) { TodoStats.heatmapSummary(data) }
    val byDate = remember(data) { data.associateBy { it.date } }
    // Keyed on the range: switching the window drops a day the reader picked in
    // the last one, rather than leaving a panel about a date no longer shown.
    var selected by remember(range) { mutableStateOf<TodoStats.DayProductivity?>(null) }
    val levelColors = listOf(
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
        DONE_GREEN.copy(alpha = 0.30f),
        DONE_GREEN.copy(alpha = 0.50f),
        DONE_GREEN.copy(alpha = 0.72f),
        DONE_GREEN
    )
    val cellDescFmt = stringResource(R.string.todo_heatmap_cell_desc)
    val rangeMenuDesc = stringResource(R.string.todo_heatmap_range_menu)
    val cellDescription = { date: LocalDate, day: TodoStats.DayProductivity ->
        String.format(
            Locale.US,
            cellDescFmt,
            historyDateFormat(locale).format(date),
            day.completed,
            (day.ratio * 100).toInt()
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // ── Headline: what the window adds up to, and which window it is ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (val shown = range) {
                        is HeatmapRange.PastYear -> stringResource(
                            R.string.todo_heatmap_total_past_year,
                            summary.completed
                        )

                        is HeatmapRange.Year -> stringResource(
                            R.string.todo_heatmap_total_year,
                            summary.completed,
                            shown.year
                        )
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { menuOpen = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .semantics {
                                contentDescription = rangeMenuDesc
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = heatmapRangeLabel(range),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        rangeOptions.forEach { option ->
                            val isSelected = option == range
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = heatmapRangeLabel(option),
                                        fontWeight = if (isSelected) FontWeight.Bold
                                        else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    range = option
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.todo_heatmap_active_days, summary.activeDays),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.todo_heatmap_max_streak, summary.maxStreak),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            HeatmapGrid(
                columns = columns,
                byDate = byDate,
                today = today,
                monthLabels = monthLabels,
                levelColors = levelColors,
                onSelect = { selected = it },
                cellDescription = cellDescription,
                anchorColumn = heatmapAnchorColumn(columns, range, today)
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.todo_heatmap_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                levelColors.forEach { c ->
                    Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c))
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    text = stringResource(R.string.todo_heatmap_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            selected?.let { day ->
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = historyDateFormat(LocalConfiguration.current.locales[0]).format(day.date),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.todo_heatmap_day_completed, day.completed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.todo_heatmap_day_attempted, day.attempted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.todo_heatmap_day_time,
                        formatMinutesLabel(day.productiveMinutes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.todo_heatmap_day_score,
                        (day.ratio * 100).toInt()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The menu's own name for a window: "Past year" or the year itself. */
@Composable
private fun heatmapRangeLabel(range: HeatmapRange): String = when (range) {
    is HeatmapRange.PastYear -> stringResource(R.string.todo_heatmap_range_past_year)
    is HeatmapRange.Year -> range.year.toString()
}

/** "45m", "1h", "1h 30m" for a minutes count. */
private fun formatMinutesLabel(minutes: Int): String {
    if (minutes <= 0) return "0m"
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h <= 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/** Month calendar: activity dots per day, tap a day for its todos. */
@Composable
private fun CalendarSection(
    stats: TodoStats.MonthStats,
    month: YearMonth,
    today: LocalDate,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayTap: (LocalDate) -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DateRange,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = monthNameFormat(locale).format(month.atDay(1)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onPrevMonth, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.todo_calendar_prev),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onNextMonth, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.todo_calendar_next),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (stats.due > 0 || stats.futureScheduled > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.todo_calendar_month_progress, stats.percent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                DAY_LETTERS.forEach { letter ->
                    Text(
                        text = letter,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // Leading blanks before the 1st, then a padded 7-column grid.
            val leading = (month.atDay(1).dayOfWeek.value - 1) % 7
            val cells: List<TodoStats.MonthDayStats?> = buildList {
                repeat(leading) { add(null) }
                stats.days.forEach { add(it) }
                while (size % 7 != 0) add(null)
            }
            cells.chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        CalendarCell(
                            day = day,
                            today = today,
                            onClick = { onDayTap(it) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CalendarLegend(color = DONE_GREEN, label = stringResource(R.string.todo_calendar_done))
                Spacer(Modifier.width(12.dp))
                CalendarLegend(color = MISSED_RED, label = stringResource(R.string.todo_calendar_missed))
                Spacer(Modifier.width(12.dp))
                CalendarLegend(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    label = stringResource(R.string.todo_calendar_scheduled)
                )
            }
        }
    }
}

/** One calendar day cell: number + activity dot. Null = leading/trailing blank. */
@Composable
private fun RowScope.CalendarCell(
    day: TodoStats.MonthDayStats?,
    today: LocalDate,
    onClick: (LocalDate) -> Unit
) {
    if (day == null) {
        Spacer(Modifier.weight(1f).height(38.dp))
        return
    }
    val isToday = day.date == today
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isToday) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable { onClick(day.date) }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        val dotColor = when {
            day.completed > 0 -> DONE_GREEN
            day.due > 0 && day.completed == 0 && !day.isFuture -> MISSED_RED
            day.due > 0 -> null // scheduled (outline dot)
            else -> Color.Transparent
        }
        Box(
            modifier = Modifier.size(6.dp).clip(CircleShape).background(dotColor ?: Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (dotColor == null && day.due > 0) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                )
            }
        }
    }
}

@Composable
private fun CalendarLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The WEEK's bar graph: one bar per day, each STACKED from what actually
 * happened — green completed, amber attempted, red still incomplete, all
 * measured against the week's busiest day. There is no metric to switch
 * between: the stacked bar answers "how did this day go?" in one look, and
 * tapping one opens that day's todos ([DayTodosDialog]).
 */
@Composable
private fun PeriodBarGraph(
    insights: TodoStats.PeriodInsights,
    onBarTap: (LocalDate) -> Unit
) {
    // One shared scale for the whole week: the busiest day is the full bar.
    val max = insights.bars.maxOfOrNull { it.due }?.coerceAtLeast(1) ?: 1
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.todo_graph_total),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        BarLegend()
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            insights.bars.forEach { bar ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onBarTap(bar.start) }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        StackedBar(
                            due = bar.due,
                            completed = bar.completed,
                            attempted = bar.attempted,
                            max = max
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = DAY_LETTERS[bar.start.dayOfWeek.value - 1],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * The MONTH view's bar graph: one bar per month over a trailing TWELVE months —
 * the monthly counterpart of the week's seven day bars. Twelve columns cannot
 * fit a phone, so the row scrolls horizontally, oldest on the left and the
 * current month last, and the current month's label is emphasised. It OPENS
 * scrolled to the end, so the current month is what the reader sees first and
 * reaching back through the year is a deliberate move rather than the only way
 * to get to today. Every bar stacks the same three outcomes as the weekly graph
 * and is scaled against the busiest month in the window, so the chart's shape
 * is the year rather than an arbitrary maximum. Tapping a bar opens that
 * month's statistics ([MonthStatsDialog]).
 */
@Composable
private fun MonthTrendChart(
    bars: List<TodoStats.MonthBar>,
    onBarTap: (YearMonth) -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    // One shared scale across all twelve months: the busiest month is full.
    val max = bars.maxOfOrNull { it.due }?.coerceAtLeast(1) ?: 1
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.todo_graph_months),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        BarLegend()
        Spacer(Modifier.height(10.dp))
        // The row OPENS on the CURRENT month — its last bar — so today is what
        // the reader sees without having to drag the year past.
        //
        // The offset is computed rather than discovered: every column is a fixed
        // [BAR_WIDTH] plus one [MONTH_GAP], so the distance that hides the
        // earlier months is exactly (content − viewport) before the first frame
        // is drawn. That is deliberately NOT "scroll to the end once something
        // is measured" — a state seeded at Int.MAX_VALUE has to wait for a
        // measure pass to clamp it, and waiting for a measure pass to then
        // scroll is a frame of the wrong month. A window with nothing to hide
        // resolves to 0 and simply does not scroll.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val hidden = (BAR_WIDTH * bars.size + MONTH_GAP * (bars.size - 1) - maxWidth)
                .coerceAtLeast(0.dp)
            val density = LocalDensity.current
            val scrollState = rememberScrollState(initial = with(density) { hidden.roundToPx() })
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(MONTH_GAP),
                verticalAlignment = Alignment.Bottom
            ) {
                bars.forEach { bar ->
                    Column(
                        modifier = Modifier
                            .width(BAR_WIDTH)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onBarTap(bar.month) }
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            StackedBar(
                                due = bar.due,
                                completed = bar.completed,
                                attempted = bar.attempted,
                                max = max
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = monthShortFormat(locale).format(bar.month.atDay(1)),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (bar.isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (bar.isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** The bar graphs' single height, so the week and month charts line up. */
private val BAR_HEIGHT = 64.dp

/** One month's column width — wide enough for its three-letter label. */
private val BAR_WIDTH = 34.dp

/** The space between two columns of a bar chart. */
private val MONTH_GAP = 6.dp

/** Names the three outcomes once, above whichever chart is showing. */
@Composable
private fun BarLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalendarLegend(color = DONE_GREEN, label = stringResource(R.string.todo_calendar_done))
        Spacer(Modifier.width(12.dp))
        CalendarLegend(
            color = ATTEMPT_AMBER,
            label = stringResource(R.string.todo_filter_attempted)
        )
        Spacer(Modifier.width(12.dp))
        CalendarLegend(
            color = MISSED_RED.copy(alpha = 0.7f),
            label = stringResource(R.string.todo_filter_incomplete)
        )
    }
}

/**
 * ONE stacked column: red (still incomplete) under amber (attempted) under green
 * (completed), scaled to the chart's [max]. A column with nothing due draws the
 * neutral stub instead, so "nothing was scheduled" can never read as "nothing
 * was done".
 */
@Composable
private fun StackedBar(due: Int, completed: Int, attempted: Int, max: Int) {
    if (due <= 0) {
        BarStub()
        return
    }
    val incomplete = (due - completed - attempted).coerceAtLeast(0)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)),
        verticalArrangement = Arrangement.Bottom
    ) {
        if (incomplete > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT * incomplete / max)
                    .background(MISSED_RED.copy(alpha = 0.7f))
            )
        }
        if (attempted > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT * attempted / max)
                    .background(ATTEMPT_AMBER)
            )
        }
        if (completed > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT * completed / max)
                    .background(DONE_GREEN)
            )
        }
    }
}

/** The tiny neutral stub shown for a day with nothing to measure. */
@Composable
private fun BarStub() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
    )
}

/**
 * A month's statistics, opened from its bar in the twelve-month chart: the very
 * outcomes that bar is stacked from — completed, attempted and not completed —
 * spelled out, plus how many days were worked on, the best day and the logged
 * time. Every figure comes from [TodoStats], so the dialog can never disagree
 * with the column that was tapped, and only days that have ARRIVED are counted
 * (the same date rule as the chart, the card and the calendar).
 */
@Composable
private fun MonthStatsDialog(
    month: YearMonth,
    items: List<TodoItem>,
    today: LocalDate,
    nowMillis: Long,
    onDismiss: () -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val stats = remember(items, month, today) { TodoStats.monthStats(items, month, today) }
    val counts = remember(items, month, today, nowMillis) {
        TodoStats.behaviorCounts(items, month.atDay(1), month.atEndOfMonth(), today, nowMillis)
    }
    val elapsedDays = stats.days.count { !it.isFuture }
    val activeDays = stats.days.count { !it.isFuture && it.completed > 0 }
    val bestDay = stats.days
        .filter { !it.isFuture && it.due > 0 && it.completed > 0 }
        .maxWithOrNull(
            compareBy<TodoStats.MonthDayStats>(
                { it.completed * 100 / it.due },
                { it.completed }
            )
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(monthNameFormat(locale).format(month.atDay(1))) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (stats.due == 0) {
                    // Nothing was due: a sentence, not a wall of zeroes.
                    Text(
                        text = stringResource(R.string.todo_month_dialog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    return@Column
                }
                InsightRow(
                    label = stringResource(R.string.todo_month_dialog_completed_label),
                    value = stringResource(
                        R.string.todo_month_dialog_completed_value,
                        stats.completed,
                        stats.due,
                        stats.percent
                    ),
                    valueColor = DONE_GREEN
                )
                InsightRow(
                    label = stringResource(R.string.todo_month_dialog_attempted_label),
                    value = counts.attempted.toString(),
                    valueColor = if (counts.attempted > 0) ATTEMPT_AMBER
                    else MaterialTheme.colorScheme.onSurface
                )
                InsightRow(
                    label = stringResource(R.string.todo_month_dialog_incomplete_label),
                    value = counts.incomplete.toString(),
                    valueColor = if (counts.incomplete > 0) MISSED_RED
                    else MaterialTheme.colorScheme.onSurface
                )
                InsightRow(
                    label = stringResource(R.string.todo_month_dialog_active_days_label),
                    value = stringResource(
                        R.string.todo_month_dialog_active_days_value,
                        activeDays,
                        elapsedDays
                    )
                )
                bestDay?.let { best ->
                    InsightRow(
                        label = stringResource(R.string.todo_stats_best_day_label),
                        value = stringResource(
                            R.string.todo_stats_best_day,
                            historyDateFormat(locale).format(best.date),
                            best.completed * 100 / best.due
                        )
                    )
                }
                if (counts.productiveMinutes > 0) {
                    InsightRow(
                        label = stringResource(R.string.todo_stats_productive_minutes_label),
                        value = formatMinutesLabel(counts.productiveMinutes)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.todo_ok)) }
        }
    )
}

/**
 * Tapping a calendar / weekly-strip / bar-graph day shows that day's todos.
 * Only TODAY is interactive: past days are history (read-only), future days
 * show their SCHEDULED todos (read-only) — a future todo is informational and
 * never appears as incomplete.
 */
@Composable
private fun DayTodosDialog(
    day: LocalDate,
    items: List<TodoItem>,
    today: LocalDate,
    nowMillis: Long,
    onToggle: (TodoItem) -> Unit,
    onDismiss: () -> Unit
) {
    val active = items.filter { TodoCodec.isActiveOn(it, day) }
    val isFuture = day.isAfter(today)
    // Same date rule as the list: only today is editable, and a strict-interval
    // todo whose window has already closed today is LOCKED ("can't redo").
    val editable = day == today
    // The day's whole plan, never a slice of it: the bar graph shows every
    // outcome, so the dialog behind it has to as well.
    val list = active
    val locale = LocalConfiguration.current.locales[0]
    val modeLabel = if (isFuture) stringResource(R.string.todo_calendar_scheduled)
    else stringResource(R.string.todo_filter_all)
    val title = day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.todo_day_title, title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.todo_day_mode,
                        list.size,
                        modeLabel,
                        list.size
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            if (list.isEmpty()) {
                Text(
                    text = stringResource(R.string.todo_day_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    list.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (editable && TodoCodec.isActiveOn(item, day)) {
                                val done = TodoCodec.completedOn(item, day)
                                val lockedMissed = !done && TodoCodec.intervalEnded(item, day, nowMillis)
                                when {
                                    // Strict window closed today uncompleted → locked.
                                    lockedMissed -> {
                                        Icon(
                                            Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = MISSED_RED,
                                            modifier = Modifier.size(20.dp).padding(start = 6.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    // Same toggle semantics as the Today list: a ticked
                                    // completed day can be un-completed unless its strict
                                    // window already closed (locked both ways), and an
                                    // uncompleted day only shows a checkbox while the rules
                                    // allow completing — a strict window that hasn't opened
                                    // yet falls through to the DateRange marker, matching
                                    // the list.
                                    done || TodoCodec.canCompleteOn(item, day, nowMillis) -> {
                                        Checkbox(
                                            checked = done,
                                            onCheckedChange = { onToggle(item) },
                                            enabled = !(done && TodoCodec.intervalEnded(item, day, nowMillis))
                                        )
                                    }
                                    else -> {
                                        Icon(
                                            Icons.Filled.DateRange,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(18.dp).padding(start = 6.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    }
                                }
                            } else {
                                Icon(
                                    Icons.Filled.DateRange,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp).padding(start = 6.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                textDecoration = if (TodoCodec.completedOn(item, day)) {
                                    TextDecoration.LineThrough
                                } else null,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TodoCodec.scheduledTimeLabel(item)?.let {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.todo_ok)) }
        }
    )
}

/** Snooze options: 10 / 30 / 60 min or a custom number of minutes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnoozeSheet(
    onSnooze: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var customOpen by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.todo_snooze),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            listOf(
                10L to R.string.todo_snooze_10m,
                30L to R.string.todo_snooze_30m,
                60L to R.string.todo_snooze_1h
            ).forEach { (minutes, label) ->
                OutlinedButton(
                    onClick = { onSnooze(minutes) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(label), modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }
            TextButton(
                onClick = { customOpen = !customOpen },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.todo_snooze_custom), modifier = Modifier.weight(1f))
            }
            if (customOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { input ->
                            customText = input.filter { it.isDigit() }.take(4)
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.todo_snooze_custom_minutes)) },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            customText.toLongOrNull()?.takeIf { it > 0 }?.let { onSnooze(it) }
                        },
                        enabled = customText.toLongOrNull()?.let { it > 0 } == true
                    ) {
                        Text(stringResource(R.string.todo_ok))
                    }
                }
            }
        }
    }
}

/** Daily completion target editor: presets + a custom number. */
@Composable
private fun TargetDialog(
    current: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.todo_target_set_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.todo_target_set_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 8, 10).forEach { preset ->
                        FilterChip(
                            selected = value == preset.toString(),
                            onClick = { value = preset.toString() },
                            label = { Text("$preset") }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { input -> value = input.filter { it.isDigit() }.take(2) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.todo_target_label)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(value.toIntOrNull()?.coerceIn(1, 50) ?: current) },
                enabled = value.toIntOrNull()?.let { it in 1..50 } == true
            ) {
                Text(stringResource(R.string.todo_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.todo_cancel))
            }
        }
    )
}

/** Beautiful empty state when there are no todos at all. */
@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "🗒️", fontSize = 52.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.todo_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.todo_empty_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.todo_add))
        }
    }
}
