package com.muddassir.clearview.todo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.data.TodoNotifier
import com.muddassir.clearview.todo.data.TodoStats
import com.muddassir.clearview.todo.data.TodoStore
import com.muddassir.clearview.widget.WidgetVisuals
import com.muddassir.clearview.widget.progressFraction
import java.time.LocalDate

/**
 * The To-Do home-screen widget (§1): a 1x1 progress ring.
 *
 * One cell is a small card, so it shows one thing — the proportion of today's
 * list that is done, with the fraction inside the ring. What it deliberately does
 * NOT do is draw a checklist: two readable rows do not fit beside a ring in a
 * single cell, and a squeezed version would be less legible than the number it
 * replaced. The card taps through to the To-Do screen, which is where the list
 * lives.
 *
 * **The card is a square, whatever cell it lands in.** `match_parent` would make
 * it a tall rectangle, because a launcher's cells are taller than they are wide.
 * The provider therefore hands the launcher a square BITMAP — surface, hairline
 * and ring drawn together by [WidgetVisuals.todoCard] — and the layout scales it
 * with `fitCenter`, which fills the largest square its bounds allow. Nothing is
 * measured and no launcher padding is assumed; see `WidgetVisuals` for why both
 * of those alternatives failed on a real device.
 *
 * **Its arithmetic is the app's arithmetic.** The counts come from [TodoStats],
 * so a widget that did its own counting would be a second, wrong answer to a
 * question that already has one — and the home screen is exactly where a wrong
 * number gets believed.
 *
 * Rendering is instant and offline: a persisted list read, no network, no worker
 * and no coroutine, which is what makes it safe to redraw on every launcher
 * update. [refreshAllWidgets] is called from `TodoStore.saveItems`, so ticking a
 * to-do anywhere — in the app or from a reminder notification — redraws this
 * card.
 */
class TodoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            appWidgetManager.updateAppWidget(widgetId, buildViews(context, widgetId))
        }
    }

    override fun onDisabled(context: Context) {
        // The last instance has gone. Nothing will ask for a ring again until one
        // is placed, so the cached bitmaps are released rather than held by a
        // process the user has finished with.
        WidgetVisuals.clearCache()
    }

    companion object {

        /**
         * Redraw every placed widget.
         *
         * Cheap enough to call on every save: one preferences read and one
         * RemoteViews per instance, and a device has one or two of them. The
         * array lookup is a fast local binder call, and the common case — nobody
         * has added the widget — returns empty immediately.
         */
        fun refreshAllWidgets(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, TodoWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { widgetId ->
                manager.updateAppWidget(widgetId, buildViews(app, widgetId))
            }
        }

        /** One widget's content, from the persisted to-dos. */
        private fun buildViews(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_todo)

            val day = TodoStats.dayStats(TodoStore(context).getItems(), LocalDate.now())
            val due = day.due
            val completed = day.completed

            // The whole card in one image, so it stays square in a cell that is
            // not; `fitCenter` in the layout does the fitting.
            views.setImageViewBitmap(
                R.id.widget_todo_ring,
                WidgetVisuals.todoCard(
                    percent = if (due > 0) completed * 100 / due else 0,
                    surface = ContextCompat.getColor(context, R.color.widget_bg),
                    border = ContextCompat.getColor(context, R.color.widget_stroke),
                    accent = ContextCompat.getColor(context, R.color.widget_accent),
                    track = ContextCompat.getColor(context, R.color.widget_track)
                )
            )

            // "0/0" would be a fraction of nothing. A dash says the same thing
            // without pretending to be a measurement.
            views.setTextViewText(
                R.id.widget_todo_fraction,
                if (due > 0) {
                    progressFraction(completed, due)
                } else {
                    context.getString(R.string.widget_todo_dash)
                }
            )

            // The card's own tap, and the widget's only action: the ring has no
            // room for a checkbox, so tapping it opens the list. It uses the same
            // extra the reminder notifications send, so there is one way in
            // rather than a second one invented for the widget.
            //
            // The target is LauncherActivity, NOT MainActivity. MainActivity is
            // the `open` base class the two flavours subclass, and it is not in
            // the manifest — only LauncherActivity is — so an intent to it throws
            // ActivityNotFoundException in the launcher's process and the tap
            // silently does nothing. Every other entry point in the app (the
            // notifications, the scheduler, the audio service) already names
            // LauncherActivity for exactly this reason.
            views.setOnClickPendingIntent(
                R.id.widget_todo_root,
                PendingIntent.getActivity(
                    context,
                    widgetId,
                    Intent(context, LauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(TodoNotifier.EXTRA_OPEN_TODO, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            return views
        }
    }
}
