package com.muddassir.clearview.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The drawing the home-screen widgets share (§1).
 *
 * A widget is inflated by the launcher, so it cannot use Compose and it cannot
 * host a custom `View`: RemoteViews understands a fixed set of widgets and
 * draws what it is given. That rules out the one thing the Progress card leans
 * on for its headline — an arc — because an arc is `Canvas.drawArc` and there is
 * no `Canvas` in a RemoteViews layout.
 *
 * The way through is to do the drawing HERE and hand the launcher a bitmap. A
 * ring is one arc, so a 192px square is a few microseconds of work on the rare
 * occasions a widget redraws, and the result scales cleanly down to the ~44dp
 * box it is shown in because it is rendered larger than it is displayed.
 *
 * Colours are passed in as ARGB ints rather than read from resources: the
 * provider already has a `Context`, and keeping this file free of it means the
 * geometry is a pure function of its inputs.
 */
internal object WidgetVisuals {

    /**
     * Resolution the ring is drawn at, before the launcher scales it down.
     *
     * 192px is ~4x the 44–48dp box it lands in, so the arc is supersampled
     * rather than merely large — which is what stops a 2px stroke looking like
     * a staircase on a 3x display. It is also comfortably under the ~1MB binder
     * limit for a RemoteViews bitmap: 192x192 ARGB_8888 is about 147KB.
     */
    const val RING_BITMAP_PX = 192

    /** Stroke width as a fraction of the ring's size. */
    private const val STROKE_FRACTION = 0.11f

    /** How much of the box the stroke keeps clear, so a round cap cannot clip. */
    private const val INSET_FRACTION = 0.02f

    /**
     * Bitmaps for the (few) percentages a widget is ever asked for.
     *
     * A widget redraws on follow, on toggle and on the launcher's own schedule,
     * and the same handful of percentages come round again. The ring is a pure
     * function of its arguments, so caching it is free correctness-wise. Keyed by
     * everything that affects the picture, because a cache keyed on the
     * percentage alone would serve a teal ring where a gold one was asked for.
     */
    private val cache = HashMap<String, Bitmap>(16)

    /**
     * A progress ring at [percent] (0..100), or an empty track when it is zero.
     *
     * Starting at twelve o'clock and going clockwise is the only direction a
     * progress arc is ever drawn, so it is not configurable. A zero percent
     * draws the TRACK ALONE and not a full circle: an empty ring that looks
     * exactly like a complete one would be a widget lying about a day nobody
     * has started.
     *
     * @param accent arc colour — the "done" colour.
     * @param track colour of the unlit remainder.
     */
    fun progressRing(percent: Int, accent: Int, track: Int): Bitmap {
        val clamped = percent.coerceIn(0, 100)
        val key = "$clamped|$accent|$track"
        cache[key]?.let { return it }

        val size = RING_BITMAP_PX
        val stroke = size * STROKE_FRACTION
        val inset = size * INSET_FRACTION
        val radius = (size - stroke) / 2f - inset
        val centre = size / 2f

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }

        val box = RectF(
            centre - radius,
            centre - radius,
            centre + radius,
            centre + radius
        )

        paint.color = track
        canvas.drawArc(box, 0f, 360f, false, paint)

        if (clamped > 0) {
            paint.color = accent
            // A round cap on a sliver of progress reads as a deliberate dot;
            // a butt cap on the same sliver reads as a rendering fault.
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(box, -90f, 360f * clamped / 100f, false, paint)
        }

        cache[key] = bitmap
        return bitmap
    }

    /**
     * Release the cached bitmaps.
     *
     * Called from the provider's [android.appwidget.AppWidgetProvider.onDisabled],
     * the one signal that the last widget instance has gone. Without it the cache
     * would be the only thing keeping a few hundred KB of bitmap alive in a
     * process the user has otherwise finished with.
     */
    fun clearCache() {
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
    }
}

/**
 * The side, in dp, of the square card the To-Do widget draws inside its cell.
 *
 * A launcher's cells are not square — on the device this was developed against a
 * column is 82dp wide while a row is 121dp tall — so a card that filled its cell
 * was a tall rectangle, which is what made the ring read as floating in a box
 * rather than as a dial. RemoteViews has no aspect-ratio support, so the square
 * has to be a fixed side length, and the only question is which one.
 *
 * `min` of the two cell dimensions is the answer: the width is the smaller of
 * them on every phone grid, so the square is as large as the cell's narrower
 * side allows and the leftover vertical space is left transparent. Subtracting
 * [CELL_MARGIN_DP] keeps the card's corners clear of the neighbouring cells, and
 * the clamps cover the two ends that matter — a cell so narrow that a square
 * would be unreadable, and a launcher generous enough to make a 1x1 cell
 * enormous.
 */
internal fun todoSquareSideDp(widthDp: Int, heightDp: Int): Int {
    if (widthDp <= 0 || heightDp <= 0) return MIN_TODO_SIDE_DP
    return (minOf(widthDp, heightDp) - CELL_MARGIN_DP).coerceIn(MIN_TODO_SIDE_DP, MAX_TODO_SIDE_DP)
}

/**
 * Breathing room between the square card and its cell's edges, in dp.
 *
 * Two dp, not four: a launcher already insets a widget's content from its cell
 * (the host view was 215px inside a 259px cell on the device this was tuned on),
 * so this only has to separate the card from its nearest edge — and every dp
 * spent here is a dp the ring loses. At four the card read as a small chip
 * floating in the cell rather than as the card itself.
 */
private const val CELL_MARGIN_DP = 2

/** Smallest side a ring with a fraction inside it stays legible at. */
private const val MIN_TODO_SIDE_DP = 40

/** Ceiling, so a roomy launcher cell cannot turn a glance-able chip into a panel. */
private const val MAX_TODO_SIDE_DP = 120

/**
 * The fraction drawn INSIDE a progress ring ("3/5").
 *
 * Exposed because the ring's centre is the one place a widget's primary number
 * goes, and both the ring's renderer and any test of it should agree on the
 * format. Nothing here pads the numbers: "9/12" and "10/12" differ in width by
 * one digit, and a widget whose headline jumps sideways as the day goes on
 * looks broken.
 */
internal fun progressFraction(done: Int, total: Int): String = "$done/$total"

/**
 * Bold typeface for a widget's headline number, or the default if the system
 * has no bold face — which no shipping device lacks, but a null would be a crash
 * on the one that does.
 */
internal fun widgetHeadlineTypeface(): Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

/** Transparent, for a widget state that draws nothing. */
internal val NO_COLOR: Int = Color.TRANSPARENT
