package com.muddassir.clearview.quran.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.muddassir.clearview.R
import com.muddassir.clearview.quran.data.QuranRepository
import com.muddassir.clearview.quran.ui.QuranVerseActivity
import com.muddassir.clearview.quran.worker.QuranWorkScheduler

/**
 * Home-screen widget showing the current English Quran verse.
 *
 * **The verse is the whole card.** There is no title, no citation line and no
 * Copy/Refresh chips: each was a band across a short widget, and the bands were
 * what the verse paid for. What is left is the text, centred, on a card the
 * reader can resize.
 *
 * ## What the widget no longer does, and why that is not a loss
 *
 * Copy and Refresh were two chips on a 110dp card. Both still exist where they
 * belong: the verse screen has Copy and Share ([QuranShare]),
 * and tapping this card opens that screen — one tap further for an action that
 * was costing the verse a line of height on every glance. The refresh schedule
 * is unaffected: [QuranWorkScheduler] still runs it, and the widget redraws
 * through [refreshAllWidgets] when a new verse is stored.
 *
 * ## Truncation is the layout's job now
 *
 * The verse is set verbatim. The TextView shrinks it to fit (`autoSizeTextType`)
 * and ellipsizes it if even the smallest size has no room, which is what makes
 * the card safe to resize: a reader who makes it small gets a shortened verse
 * rather than a clipped one, and a reader who makes it bigger gets more of it.
 * The previous version cut the string in Kotlin to a fixed character budget,
 * which could only ever be right for one card size — and this widget is no longer
 * one card size.
 *
 * Rendering is instant and offline: it reads the persisted current verse, with no
 * parsing, no network and no coroutine, which is what makes it safe to redraw on
 * every launcher tick. The verse itself is refreshed on the user's schedule
 * (default 6 hours) by the worker; this provider only reflects what is stored.
 */
class QuranReminderWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Ensure the download/refresh schedule exists the moment the widget is
        // added, even if the app was never opened.
        QuranWorkScheduler.ensureScheduled(context)
        appWidgetIds.forEach { widgetId ->
            renderWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onEnabled(context: Context) {
        QuranWorkScheduler.ensureScheduled(context)
    }

    companion object {

        /**
         * Re-renders every active widget instance. Called after a verse refresh
         * so the new verse shows up without the user touching the widget.
         */
        fun refreshAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, QuranReminderWidgetProvider::class.java)
            )
            ids.forEach { widgetId ->
                renderWidget(context, manager, widgetId)
            }
        }

        private fun renderWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quran_reminder)
            val verse = QuranRepository(context).getCurrentVerse()

            // A verse that has not been downloaded yet shows the placeholder
            // rather than an empty card, because an empty card reads as a failure
            // and this one is only ever a few seconds of first-run.
            views.setTextViewText(
                R.id.widget_verse_text,
                verse?.text ?: context.getString(R.string.widget_quran_loading)
            )

            // The card's single action: the verse screen, which is where Copy,
            // Share and the citation live now.
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, QuranVerseActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            manager.updateAppWidget(widgetId, views)
        }
    }
}
