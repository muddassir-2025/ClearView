package com.muddassir.clearview.todo.data

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.media.AudioAttributes
import android.net.Uri
import android.util.Log
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.ui.TodoAlarmActivity
import com.muddassir.clearview.todo.ui.TodoSnoozeActivity

/**
 * Posts the Todo reminder notifications, reusing the app's existing
 * NotificationCompat infrastructure (same pattern as MediaNotifier /
 * QuranNotifier): one channel, idempotent channel creation, and a silent
 * skip when the OS notification permission is off.
 *
 * Each reminder notification carries three actions: **Complete** (marks the
 * todo completed for its due day — handled by [TodoReminderReceiver]),
 * **Snooze** (opens [TodoSnoozeActivity], a 10 / 30 / 60-minute picker) and
 * **Dismiss** (an ✕ icon that just clears the notification). Tapping the
 * notification opens the app.
 */
object TodoNotifier {

    private const val TAG = "TodoNotifier"

    const val CHANNEL_ID = "todo_reminders"
    /** Dedicated loud channel for alarm-style todos (alarm ringtone + vibration). */
    const val CHANNEL_ALARM_ID = "todo_alarms"

    const val ACTION_REMIND = "com.muddassir.clearview.action.TODO_REMIND"
    const val ACTION_COMPLETE = "com.muddassir.clearview.action.TODO_COMPLETE"
    const val ACTION_SNOOZE = "com.muddassir.clearview.action.TODO_SNOOZE"
    const val ACTION_DISMISS = "com.muddassir.clearview.action.TODO_DISMISS"
    const val EXTRA_TODO_ID = "todo_id"
    const val EXTRA_REMINDER_INDEX = "reminder_index"
    const val EXTRA_EPOCH_DAY = "epoch_day"
    /** Content-tap extra: opens the app straight into the Todo screen. */
    const val EXTRA_OPEN_TODO = "open_todo"

    /** Idempotent — creates both channels (reminders + loud alarms) on first use. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.todo_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.todo_notification_channel_desc)
                }
            )
        }
        // Alarm-style todos post on a separate MAX-importance channel that is
        // deliberately SILENT: the looping alarm ringtone (one full minute) is
        // played by TodoAlarmService — a channel sound would play once and die
        // with the notification, which is exactly what the user did not want.
        //
        // A silent tone is ATTEMPTED on the channel (some Android versions only
        // show a notification's full-screen intent for "alerting"
        // notifications, and a configured sound keeps the full screen
        // eligible); where the system stores it, great, and where it drops the
        // resource URI (observed on Android 15) the channel is simply silent —
        // which is fine too, because the alarm screen is ALSO launched directly
        // by TodoAlarmService while the app is in the foreground, and the
        // full-screen intent covers the locked/off-screen path. The channel is
        // created ONCE and otherwise never deleted: notification channels are
        // permanent user settings, and deleting+recreating them would wipe the
        // reader's customization.
        val silentUri = Uri.parse(
            "android.resource://${context.packageName}/${R.raw.todo_alarm_silent}"
        )
        val existingAlarm = manager.getNotificationChannel(CHANNEL_ALARM_ID)
        // Create ONLY when the channel is missing — NEVER delete one to migrate
        // it. Deleting a notification channel is forbidden while the app has a
        // ringing foreground service, and [TodoAlarmService] is running exactly
        // when the reader answers an alarm: the system threw
        // `SecurityException: Not allowed to delete channel todo_alarms with a
        // foreground service` out of the alarm receiver and the full-screen
        // alarm, killing the app on Complete (and, because the persisted list
        // was written with an async apply(), sometimes losing the completion
        // too — so the day then showed as missed). Channels are permanent user
        // settings anyway; createNotificationChannel updates an existing
        // channel in place, so a sound change does not need a delete.
        if (existingAlarm == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALARM_ID,
                    context.getString(R.string.todo_alarm_channel),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.todo_alarm_channel_desc)
                    // Silent tone + no vibration: the service supplies the real
                    // alarm sound (looping) and the vibration.
                    setSound(
                        silentUri,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    enableVibration(false)
                    setShowBadge(true)
                }
            )
        }
    }

    /**
     * Posts one reminder for [item] on [epochDay] (its [reminderIndex]-th
     * reminder time). No-op when notifications are disabled. The day is
     * passed along so Complete marks that exact day (not the tap day).
     */
    @SuppressLint("MissingPermission")
    fun postReminder(context: Context, item: TodoItem, epochDay: Long, reminderIndex: Int) {
        // Respect BOTH the global Todo-reminders toggle (the settings row next
        // to the media / Quran toggles) and this todo's own notification
        // switch — plus the OS permission.
        if (!TodoStore(context).getTodoNotificationsEnabled()) {
            Log.w(TAG, "Reminder skipped: global Todo reminders toggle is OFF (${item.title})")
            return
        }
        if (item.reminder?.enabled == false) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            // Android 13+: POST_NOTIFICATIONS not granted — the OS drops the
            // notification entirely. The Todo editor now asks for this
            // permission up front; this log documents the silent skip.
            Log.w(TAG, "Reminder skipped: notifications disabled for this app (${item.title})")
            return
        }
        ensureChannel(context)

        manager.notify(
            notificationId(item.id, epochDay),
            buildReminderNotification(context, item, epochDay, reminderIndex)
        )
    }

    /**
     * Builds the reminder notification for [item] on [epochDay] at its
     * [reminderIndex]-th reminder time. Used by [postReminder] (regular
     * reminders) and by [TodoAlarmService] as its foreground notification
     * (alarm style — where it also carries the full-screen intent).
     */
    fun buildReminderNotification(
        context: Context,
        item: TodoItem,
        epochDay: Long,
        reminderIndex: Int
    ): Notification {
        val timeText = item.reminder?.timesMinutes?.getOrNull(reminderIndex)
            ?.let { TodoCodec.timeLabel(it) }
        val text = when {
            item.details.isNotBlank() -> item.details
            timeText != null -> timeText
            else -> context.getString(R.string.todo_notification_text)
        }

        // Alarm-style todos use the dedicated SILENT-but-MAX alarm channel
        // (full-screen intent fires; the sound is the service's looping
        // ringtone). Regular reminders use the normal channel, whose sound
        // comes from DEFAULT_ALL below.
        val asAlarm = item.reminder?.asAlarm == true
        val builder = NotificationCompat.Builder(context, if (asAlarm) CHANNEL_ALARM_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clearview)
            .setContentTitle(item.title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (asAlarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
        if (!asAlarm) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }
        // Alarm-style reminders behave like a REAL alarm: the SYSTEM shows the
        // full-screen [TodoAlarmActivity] over the lock screen (full-screen
        // intents are exempt from the background-activity-launch restriction —
        // launching it directly from the alarm is blocked). The alarm screen
        // itself appears when the device is locked/off, or as a heads-up while
        // it is on. [TodoAlarmService] plays the actual ringing.
        if (asAlarm) {
            builder
                .setFullScreenIntent(
                    alarmScreenIntent(context, item.id, reminderIndex, epochDay),
                    true
                )
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }
        // Complete / Snooze / ✕ actions — the same for both styles.
        builder.addAction(
            R.drawable.ic_todo_done,
            context.getString(R.string.todo_notification_complete),
            actionIntent(context, ACTION_COMPLETE, item.id, reminderIndex, epochDay)
        )
        // Snooze opens a small picker (10 / 30 / 60 minutes / custom) instead
        // of using a fixed delay — see TodoSnoozeActivity.
        builder.addAction(
            R.drawable.ic_todo_snooze,
            context.getString(R.string.todo_notification_snooze),
            snoozeIntent(context, item.id, reminderIndex, epochDay)
        )
        builder.addAction(
            R.drawable.ic_todo_dismiss,
            context.getString(R.string.todo_notification_dismiss),
            actionIntent(context, ACTION_DISMISS, item.id, reminderIndex, epochDay)
        )
        return builder.build()
    }

    /** Stable request code for the content-tap intent (one per app, reused). */
    private const val OPEN_TODO_REQUEST_CODE = 0x0D00 // "todo"

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Tapping the notification card opens the Todo screen, not just the
            // app root (MainActivity reads this extra and shows the Todo UI).
            putExtra(EXTRA_OPEN_TODO, true)
        }
        return PendingIntent.getActivity(
            context,
            OPEN_TODO_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * The full-screen intent for alarm-style reminders: the system launches
     * [TodoAlarmActivity] with the occurrence extras (todo, reminder index,
     * epoch day) so the alarm screen knows what is ringing.
     */
    private fun alarmScreenIntent(
        context: Context,
        todoId: String,
        reminderIndex: Int,
        epochDay: Long
    ): PendingIntent {
        val intent = Intent(context, TodoAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TODO_ID, todoId)
            putExtra(EXTRA_REMINDER_INDEX, reminderIndex)
            putExtra(EXTRA_EPOCH_DAY, epochDay)
        }
        val code = ("alarm-screen" + todoId + reminderIndex + epochDay).hashCode() and 0x7FFFFFFF
        return PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Launches the Snooze picker activity for the reminder occurrence. */
    private fun snoozeIntent(
        context: Context,
        todoId: String,
        reminderIndex: Int,
        epochDay: Long
    ): PendingIntent {
        val intent = Intent(context, TodoSnoozeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            action = ACTION_SNOOZE
            putExtra(EXTRA_TODO_ID, todoId)
            putExtra(EXTRA_REMINDER_INDEX, reminderIndex)
            putExtra(EXTRA_EPOCH_DAY, epochDay)
        }
        val code = ("snooze-pick" + todoId + reminderIndex + epochDay).hashCode() and 0x7FFFFFFF
        return PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(
        context: Context,
        action: String,
        todoId: String,
        reminderIndex: Int,
        epochDay: Long
    ): PendingIntent {
        val intent = Intent(context, TodoReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TODO_ID, todoId)
            putExtra(EXTRA_REMINDER_INDEX, reminderIndex)
            putExtra(EXTRA_EPOCH_DAY, epochDay)
        }
        val code = actionRequestCode(action, todoId, reminderIndex, epochDay)
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Stable request code for a reminder's Complete/Dismiss action. It is keyed
     * on the FULL identity of the occurrence — action, todo, reminder index and
     * epoch day — so two notifications can never share one PendingIntent. A
     * code that stopped at (action, todo) let [PendingIntent.FLAG_UPDATE_CURRENT]
     * rewrite an older notification's extras whenever a later occurrence was
     * scheduled, which is how a Complete tap could answer for the wrong day.
     */
    internal fun actionRequestCode(
        action: String,
        todoId: String,
        reminderIndex: Int,
        epochDay: Long
    ): Int = ("todo-action" + action + todoId + reminderIndex + epochDay)
        .hashCode() and 0x7FFFFFFF

    /**
     * Stable per (todo, day) notification id so reminders never pile up for the
     * same day. Hashed over the WHOLE epoch day (not `epochDay % 31`, where two
     * occurrences exactly 31 days apart aliased onto the same id).
     */
    fun notificationId(todoId: String, epochDay: Long): Int =
        ("todo-notif" + todoId + epochDay).hashCode() and 0x7FFFFFFF

    /**
     * Rewrites a reminder's notification to say why its Complete action was
     * refused.
     *
     * A background receiver cannot show a toast (Android 12+ blocks them), so
     * for the notification path the notification itself is where the answer has
     * to live. It replaces the reminder's card — same id, same channel — keeps a
     * Dismiss action, and drops Complete/Snooze: both are answers to "do this
     * now", and the rule that refused the completion will refuse them too.
     */
    @SuppressLint("MissingPermission")
    fun postCompletionRefused(
        context: Context,
        item: TodoItem,
        epochDay: Long,
        reason: String
    ) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clearview)
            .setContentTitle(item.title)
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(notificationId(item.id, epochDay), builder.build())
        }
    }

    /**
     * Dismisses the reminder notification for [todoId] on [epochDay] (one per
     * day per todo). Used by the Complete / Snooze actions so they give visible
     * feedback — the notification leaves the shade instead of lingering.
     */
    fun cancelDayNotification(context: Context, todoId: String, epochDay: Long) {
        // Stop any ringing alarm service too — every "done with this reminder"
        // path (Complete / Snooze / Dismiss, in-app or from the notification)
        // silences the looping ringtone immediately. No-op when not ringing.
        TodoAlarmService.stop(context)
        NotificationManagerCompat.from(context).cancel(notificationId(todoId, epochDay))
    }
}
