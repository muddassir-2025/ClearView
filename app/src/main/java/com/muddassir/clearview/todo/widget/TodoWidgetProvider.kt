package com.muddassir.clearview.todo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.muddassir.clearview.MainActivity
import com.muddassir.clearview.R
import com.muddassir.clearview.todo.data.TodoNotifier
import com.muddassir.clearview.todo.data.TodoStats
import com.muddassir.clearview.todo.data.TodoStore
import com.muddassir.clearview.widget.WidgetVisuals
import com.muddassir.clearview.widget.progressFraction
import com.muddassir.clearview.widget.todoSquareSideDp
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
 * **The card is a square, and it is sized from the cell.** `match_parent` would
 * make it a tall rectangle, because a launcher's cells are taller than they are
 * wide; [squareCardToCell] therefore pins the side to the smaller of the two
 * cell dimensions. Android 12+ can read the cell it was actually placed in, so
 * the square grows to fill a wide grid and stays inside a narrow one; older
 * versions keep the 64dp the layout specifies.
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

    /**
     * The last instance was removed, or a resize broadcast arrived.
     *
     * `onAppWidgetOptionsChanged` matters here more than usual: this widget's
     * size is derived from its cell, so a launcher that reflows its grid without
     * the user touching the widget still has to be re-rendered at the new one.
     * Without it the card would keep the square it was given on the grid it used
     * to be on.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
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

        /**
         * A square card sized to the cell this instance is placed in.
         *
         * `setViewLayoutWidth`/`setViewLayoutHeight` are Android 12 and later —
         * on anything older the call would be a `NoSuchMethodError`, not a
         * fallback — so the layout's own 64dp stands in there rather than this
         * being the only path.
         */
        private fun squareCardToCell(context: Context, manager: AppWidgetManager, widgetId: Int, views: RemoteViews) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val options = manager.getAppWidgetOptions(widgetId)
            val side = todoSquareSideDp(
                widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            )
            views.setViewLayoutWidth(R.id.widget_todo_card, side.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.widget_todo_card, side.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        }

        /** One widget's content, from the persisted to-dos. */
        private fun buildViews(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_todo)
            squareCardToCell(context, AppWidgetManager.getInstance(context), widgetId, views)

            val day = TodoStats.dayStats(TodoStore(context).getItems(), LocalDate.now())
            val due = day.due
            val completed = day.completed

            views.setImageViewBitmap(
                R.id.widget_todo_ring,
                WidgetVisuals.progressRing(
                    percent = if (due > 0) completed * 100 / due else 0,
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
            views.setOnClickPendingIntent(
                R.id.widget_todo_root,
                PendingIntent.getActivity(
                    context,
                    widgetId,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(TodoNotifier.EXTRA_OPEN_TODO, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            return views
        }
    }
}
