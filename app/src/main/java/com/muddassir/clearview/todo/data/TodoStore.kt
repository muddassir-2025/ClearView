package com.muddassir.clearview.todo.data

import android.content.Context
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.widget.TodoWidgetProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Local persistence for the Todo feature: the whole [TodoItem] list as JSON
 * in one SharedPreferences file (the same pattern as DhikrStore). Reads are
 * instant, so the UI can save on every mutation (add / edit / complete /
 * delete) without any jank. The completion history lives inside the items, so
 * it survives app restarts and temporary-todo archiving.
 *
 * [itemsFlow] is a process-wide reactive mirror of the persisted list: every
 * [saveItems] (from the UI, the reminder receiver or the snooze activity)
 * publishes the new list, so an open Todo screen stays in lock-step with
 * notification actions that complete or reschedule todos in the background.
 */
class TodoStore(context: Context) {

    /**
     * The application context, kept for the one thing that needs a Context of its
     * own: telling the home-screen widget to redraw. The application context
     * rather than the caller's, so a store built from an activity cannot keep one
     * alive.
     */
    private val appContext = context.applicationContext

    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Every todo with its completion history; empty on first launch / corrupt data. */
    fun getItems(): List<TodoItem> =
        TodoCodec.decode(prefs.getString(KEY_ITEMS, null))

    /** Persists the full todo list and publishes it to every open screen. */
    fun saveItems(items: List<TodoItem>) {
        prefs.edit().putString(KEY_ITEMS, TodoCodec.encode(items)).apply()
        TodoStore.itemsFlow.value = items
        // The home-screen widget is a second view of this same list, and it has
        // no subscription of its own: a widget cannot observe anything while the
        // app is not running. Telling it here means it is redrawn by the write
        // that changed the data — including writes from a notification action or
        // the snooze activity, which is where a stale card would be most visible.
        TodoWidgetProvider.refreshAllWidgets(appContext)
    }

    /** Marks a todo attempted on [day] (for ATTEMPTED behavior). */
    fun markAttempted(id: String, day: LocalDate = LocalDate.now()) {
        val current = getItems()
        val updated = TodoCodec.attempted(current, id, day, System.currentTimeMillis())
        saveItems(updated)
    }

    /**
     * Clears a todo's ATTEMPTED state on [day] (for ATTEMPTED behavior) — the
     * "Unattempted" half of the toggle shown in the card's ⋮ menu.
     */
    fun markUnattempted(id: String, day: LocalDate = LocalDate.now()) {
        val current = getItems()
        val updated = TodoCodec.unattempted(current, id, day, System.currentTimeMillis())
        saveItems(updated)
    }

    /** Adds time to a todo on [day] (for TIME behavior). */
    fun addTime(id: String, minutes: Int, day: LocalDate = LocalDate.now()) {
        val current = getItems()
        val updated = TodoCodec.timeAdded(current, id, day, System.currentTimeMillis(), minutes)
        saveItems(updated)
    }

    /** Reactive view of the persisted list (null until the first [saveItems]). */
    val items: StateFlow<List<TodoItem>?> get() = TodoStore.itemsFlow

    /** The user's daily completion target (default 5; 1..50). */
    fun getDailyTarget(): Int =
        prefs.getInt(KEY_DAILY_TARGET, DEFAULT_DAILY_TARGET).coerceIn(1, 50)

    /** Persists the daily completion target. */
    fun setDailyTarget(target: Int) {
        prefs.edit().putInt(KEY_DAILY_TARGET, target.coerceIn(1, 50)).apply()
    }

    /**
     * Whether Todo reminders post notifications (the global toggle, default
     * ON, surfaced as a settings row next to the media / Quran toggles).
     */
    fun getTodoNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_TODO_NOTIFICATIONS, true)

    /** Persists the global Todo-reminders toggle. */
    fun setTodoNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TODO_NOTIFICATIONS, enabled).apply()
    }

    /** The name the user last entered in the Progress Card generator ("" = never). */
    fun getProgressCardName(): String =
        prefs.getString(KEY_PROGRESS_CARD_NAME, "") ?: ""

    /** Caches the Progress Card name so returning users don't retype it. */
    fun setProgressCardName(name: String) {
        prefs.edit().putString(KEY_PROGRESS_CARD_NAME, name.trim()).apply()
    }

    // ── Scheduled-alarm tracking ──────────────────────────────────
    //
    // Android 12+ limits how many PendingIntents an app may create (roughly
    // 10,000). Brute-forcing a PendingIntent for every (todo × index × day)
    // combination — as a cancel-and-reschedule sweep does — blows through that
    // quota in one save and alarms silently stop being created. Instead, we
    // record EXACTLY which alarms are scheduled ("todoId#index" → epochDay) and
    // only create/cancel PendingIntents for those. A todo holds 1-3 alarms, not
    // hundreds.

    /** The currently-scheduled alarms: "todoId#index" → the epoch day of the occurrence. */
    fun getScheduledAlarms(): Map<String, Long> {
        val raw = prefs.getString(KEY_SCHEDULED_ALARMS, null)
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { key ->
                val v = o.optLong(key, -1L)
                if (v >= 0) key to v else null
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Records that [todoId]'s [index]-th reminder is scheduled for [epochDay]. */
    fun setScheduledAlarm(todoId: String, index: Int, epochDay: Long) {
        val map = getScheduledAlarms().toMutableMap()
        map["$todoId#$index"] = epochDay
        saveScheduledAlarms(map)
    }

    /** Removes the record for [todoId]'s [index]-th reminder (it fired / was cancelled). */
    fun clearScheduledAlarm(todoId: String, index: Int) {
        val map = getScheduledAlarms().toMutableMap()
        if (map.remove("$todoId#$index") != null) saveScheduledAlarms(map)
    }

    /** Removes every recorded alarm belonging to [todoId] (delete / full cancel). */
    fun clearScheduledAlarmsFor(todoId: String) {
        val map = getScheduledAlarms().toMutableMap()
        val sizeBefore = map.size
        map.keys.removeAll { it.startsWith("$todoId#") }
        if (map.size != sizeBefore) saveScheduledAlarms(map)
    }

    private fun saveScheduledAlarms(map: Map<String, Long>) {
        val o = JSONObject()
        map.forEach { (key, epochDay) -> o.put(key, epochDay) }
        prefs.edit().putString(KEY_SCHEDULED_ALARMS, o.toString()).apply()
    }

    // ── Reminder acknowledgements ──────────────────────────────────
    //
    // Which reminder moments the reader has already been SHOWN, so the count on
    // the notification bell can go away when the centre is opened. A todo cannot
    // be "read" the way a channel update can — the work is either done or it is
    // not — so the badge and the list have to mean different things: the list
    // keeps saying the todo is due today (it is), while the number stops counting
    // it once the reader has looked.
    //
    // The key is the todo AND the moment it spoke, not just the todo, which is
    // what makes it re-arm by itself: tomorrow's 4:55 is a different moment, so
    // tomorrow's reminder counts again without anything having to reset it.

    /** Reminder moments already shown: "todoId#momentMillis". */
    fun getSeenReminders(): Set<String> =
        prefs.getStringSet(KEY_SEEN_REMINDERS, emptySet()) ?: emptySet()

    /**
     * Records that the reader has now seen [keys].
     *
     * Prunes as it writes: a key's moment is part of the key, so anything from
     * before today can never be looked up again — keeping those would grow the set
     * by one entry per todo per day forever. Parsing a key back out is the price,
     * and it is paid once per open rather than once per read.
     */
    fun markRemindersSeen(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val kept = getSeenReminders().filterTo(mutableSetOf()) { key ->
            val moment = key.substringAfterLast('#').toLongOrNull()
            moment != null && moment >= startOfToday
        }
        kept.addAll(keys)
        prefs.edit().putStringSet(KEY_SEEN_REMINDERS, kept).apply()
    }

    // ── Snoozed-reminder tracking ──────────────────────────────────
    //
    // A snooze re-schedules an occurrence to now+delay with the SAME request
    // code. The snooze record keeps (fire time, occurrence day) so that
    // [TodoScheduler.scheduleItems] can re-arm a still-pending snooze after
    // [TodoScheduler.rescheduleAll] — snoozes survive app restarts and
    // unrelated todo edits instead of silently reverting to the normal
    // schedule. The UI also reads it to show "Today: 1:58 → 2:08" on the card.

    /** One pending snooze: when it fires, and which occurrence (day) it belongs to. */
    data class SnoozedReminder(val fireAtMillis: Long, val epochDay: Long)

    /** The pending snoozes: "todoId#index" → its new fire time + occurrence day. */
    fun getSnoozedReminders(): Map<String, SnoozedReminder> {
        val raw = prefs.getString(KEY_SNOOZED_REMINDERS, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { key ->
                val v = o.optJSONObject(key) ?: return@mapNotNull null
                SnoozedReminder(
                    fireAtMillis = v.optLong("at", -1L),
                    epochDay = v.optLong("day", -1L)
                ).takeIf { it.fireAtMillis >= 0 && it.epochDay >= 0 }?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Records that [todoId]'s [index]-th reminder is snoozed until [fireAtMillis]. */
    fun setSnoozedReminder(todoId: String, index: Int, fireAtMillis: Long, epochDay: Long) {
        val map = getSnoozedReminders().toMutableMap()
        map["$todoId#$index"] = SnoozedReminder(fireAtMillis, epochDay)
        saveSnoozedReminders(map)
    }

    /** Removes the snooze record for [todoId]'s [index]-th reminder (it fired / was cancelled). */
    fun clearSnoozedReminder(todoId: String, index: Int) {
        val map = getSnoozedReminders().toMutableMap()
        if (map.remove("$todoId#$index") != null) saveSnoozedReminders(map)
    }

    /** Removes every snooze record belonging to [todoId] (delete / full cancel). */
    fun clearSnoozedRemindersFor(todoId: String) {
        val map = getSnoozedReminders().toMutableMap()
        val sizeBefore = map.size
        map.keys.removeAll { it.startsWith("$todoId#") }
        if (map.size != sizeBefore) saveSnoozedReminders(map)
    }

    private fun saveSnoozedReminders(map: Map<String, SnoozedReminder>) {
        val o = JSONObject()
        map.forEach { (key, s) ->
            o.put(key, JSONObject().put("at", s.fireAtMillis).put("day", s.epochDay))
        }
        prefs.edit().putString(KEY_SNOOZED_REMINDERS, o.toString()).apply()
    }

    companion object {
        /** Process-wide mirror of the persisted list (see class doc). */
        val itemsFlow = MutableStateFlow<List<TodoItem>?>(null)

        const val DEFAULT_DAILY_TARGET = 5

        private const val PREFS_NAME = "todo_store"
        private const val KEY_ITEMS = "items"
        private const val KEY_TODO_NOTIFICATIONS = "todo_notifications_enabled"
        private const val KEY_DAILY_TARGET = "daily_target"
        private const val KEY_SCHEDULED_ALARMS = "scheduled_alarms"
        private const val KEY_SNOOZED_REMINDERS = "snoozed_reminders"
        private const val KEY_PROGRESS_CARD_NAME = "progress_card_name"
        private const val KEY_SEEN_REMINDERS = "seen_reminders"
    }
}
