package com.muddassir.clearview.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * The drawing the home-screen widgets share (§1).
 *
 * A widget is inflated by the launcher, so it cannot use Compose and it cannot
 * host a custom `View`: RemoteViews understands a fixed set of widgets and
 * draws what it is given. That rules out the one thing the To-Do card leans on
 * for its headline — an arc — because an arc is `Canvas.drawArc` and there is
 * no `Canvas` in a RemoteViews layout.
 *
 * The way through is to do the drawing HERE and hand the launcher a bitmap.
 *
 * ## Why the To-Do card is drawn whole, background included
 *
 * A launcher's cells are not square — on the device this was developed against a
 * 1x1 cell is 82dp wide and 121dp tall. A card that filled its cell was therefore
 * a tall rectangle, and RemoteViews has no aspect-ratio support to fix that with.
 *
 * Sizing the card from the cell's reported dimensions does not work either: the
 * launcher reports the CELL (98dp here), not the box the widget actually gets
 * (82dp), so a card cut to the reported width is wider than the space it is drawn
 * in and gets clipped. That was tried, and it rendered 215x251 — still a
 * rectangle, and a truncated one.
 *
 * Drawing the card as a square BITMAP sidesteps all of it. The image is square by
 * construction, and an `ImageView` with `scaleType="fitCenter"` scales it to the
 * largest square that fits its bounds — so the card is square on any launcher, at
 * any grid, with nothing to measure and no padding constant to guess. The surface
 * has to move into the bitmap with the arc, because a `background` would stretch
 * across the cell while the ring stayed square.
 *
 * Colours are passed in as ARGB ints rather than read from resources: the
 * provider already has a `Context`, and keeping this file free of it means the
 * geometry is a pure function of its inputs.
 */
internal object WidgetVisuals {

    /**
     * Resolution the card is drawn at, before the launcher scales it down.
     *
     * A 1x1 card lands in a box of roughly 215px on a 3x display, so 256px is
     * rendered slightly larger than it is shown and comes down smoothly instead
     * of being upscaled. It is also comfortably under the ~1MB binder limit for a
     * RemoteViews bitmap: 256x256 ARGB_8888 is about 262KB.
     */
    const val CARD_BITMAP_PX = 256

    /** Stroke width of the progress arc, as a fraction of the ring's own size. */
    private const val RING_STROKE_FRACTION = 0.11f

    /** How much of the ring's box the stroke keeps clear, so a round cap cannot clip. */
    private const val RING_INSET_FRACTION = 0.02f

    /**
     * The card's proportions, as fractions of its side.
     *
     * They are the dp values they replace over the 82dp card they were designed
     * on, so the card keeps its shape when the launcher gives it a different
     * size: a 12dp corner and a 1dp hairline, whatever the side works out at.
     */
    private const val CARD_CORNER_FRACTION = 12f / 82f
    private const val CARD_BORDER_FRACTION = 1f / 82f
    private const val CARD_RING_PADDING_FRACTION = 8f / 82f

    /**
     * Bitmaps for the (few) cards a widget is ever asked for.
     *
     * A widget redraws on follow, on toggle and on the launcher's own schedule,
     * and the same handful of percentages come round again. The drawing is a pure
     * function of its arguments, so caching it is free correctness-wise. Keyed by
     * everything that affects the picture, because a cache keyed on the
     * percentage alone would serve a teal ring where a gold one was asked for.
     */
    private val cache = HashMap<String, Bitmap>(16)

    /**
     * The whole To-Do card: rounded surface, hairline border, progress ring.
     *
     * Square by construction, and returned with no text on it — the fraction is a
     * real `TextView` laid over this image, so it stays crisp and follows the
     * user's font scale instead of scaling with the bitmap.
     *
     * A zero percent draws the TRACK ALONE and not a full circle: an empty ring
     * that looks exactly like a complete one would be a widget lying about a day
     * nobody has started. The arc runs from twelve o'clock clockwise, which is
     * the only direction a progress arc is ever drawn and so is not configurable.
     *
     * @param percent how much of the day's list is done, 0..100.
     * @param surface the card's fill colour.
     * @param border the card's hairline colour.
     * @param accent arc colour — the "done" colour.
     * @param track colour of the unlit remainder.
     */
    fun todoCard(percent: Int, surface: Int, border: Int, accent: Int, track: Int): Bitmap {
        val clamped = percent.coerceIn(0, 100)
        val key = "$clamped|$surface|$border|$accent|$track"
        cache[key]?.let { return it }

        val size = CARD_BITMAP_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val side = size.toFloat()

        // The card itself. The border is inset by half its own width so the
        // stroke sits inside the square rather than being clipped by it.
        val borderWidth = side * CARD_BORDER_FRACTION
        val card = RectF(
            borderWidth / 2f,
            borderWidth / 2f,
            side - borderWidth / 2f,
            side - borderWidth / 2f
        )
        val radius = side * CARD_CORNER_FRACTION

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = surface
        }
        canvas.drawRoundRect(card, radius, radius, cardPaint)

        cardPaint.style = Paint.Style.STROKE
        cardPaint.strokeWidth = borderWidth
        cardPaint.color = border
        canvas.drawRoundRect(card, radius, radius, cardPaint)

        // The ring, inside the card's padding.
        val padding = side * CARD_RING_PADDING_FRACTION
        val ringStroke = (side - padding * 2f) * RING_STROKE_FRACTION
        val ringInset = (side - padding * 2f) * RING_INSET_FRACTION
        val ringRadius = (side / 2f - padding) - ringStroke / 2f - ringInset
        val centre = side / 2f
        val ring = RectF(
            centre - ringRadius,
            centre - ringRadius,
            centre + ringRadius,
            centre + ringRadius
        )

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringStroke
        }

        ringPaint.color = track
        canvas.drawArc(ring, 0f, 360f, false, ringPaint)

        if (clamped > 0) {
            ringPaint.color = accent
            // A round cap on a sliver of progress reads as a deliberate dot; a
            // butt cap on the same sliver reads as a rendering fault.
            ringPaint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(ring, -90f, 360f * clamped / 100f, false, ringPaint)
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
 * The fraction drawn INSIDE a progress ring ("3/5").
 *
 * Exposed because the ring's centre is the one place a widget's primary number
 * goes, and both the ring's renderer and any test of it should agree on the
 * format. Nothing here pads the numbers: "9/12" and "10/12" differ in width by
 * one digit, and a widget whose headline jumps sideways as the day goes on
 * looks broken.
 */
internal fun progressFraction(done: Int, total: Int): String = "$done/$total"
