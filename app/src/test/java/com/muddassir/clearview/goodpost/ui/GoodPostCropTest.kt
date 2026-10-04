package com.muddassir.clearview.goodpost.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The crop's geometry (§21).
 *
 * The screen cannot be tested off a device, but the numbers the crop actually
 * uses can, and they are the part that would silently produce a wrong rectangle
 * rather than an obviously broken screen: where the picture lands, what the crop
 * box starts as, how a corner drag moves it, and which pixels it finally covers.
 */
class GoodPostCropTest {

    // ── Where the picture lands (ContentScale.Fit) ──────────────────────

    @Test
    fun `a square picture in a wide area is centred`() {
        val rect = fitImageRect(boxWidth = 200f, boxHeight = 100f, imageWidth = 100, imageHeight = 100)
        assertEquals(Rect(50f, 0f, 150f, 100f), rect)
    }

    @Test
    fun `a square picture in a tall area is centred vertically`() {
        val rect = fitImageRect(boxWidth = 100f, boxHeight = 200f, imageWidth = 100, imageHeight = 100)
        assertEquals(Rect(0f, 50f, 100f, 150f), rect)
    }

    @Test
    fun `an empty area has no picture`() {
        assertEquals(Rect.Zero, fitImageRect(0f, 0f, 100, 100))
    }

    // ── The starting crop box ───────────────────────────────────────────

    @Test
    fun `a free crop starts as the whole picture`() {
        val image = Rect(0f, 0f, 100f, 50f)
        assertEquals(image, initialCropRect(image, ratio = null, minSize = 10f))
    }

    @Test
    fun `a square crop is fitted inside a landscape picture`() {
        val image = Rect(0f, 0f, 100f, 50f)
        assertEquals(Rect(25f, 0f, 75f, 50f), initialCropRect(image, ratio = 1f, minSize = 10f))
    }

    // ── Dragging a corner ───────────────────────────────────────────────

    private val bounds = Rect(0f, 0f, 100f, 100f)
    private val box = Rect(20f, 20f, 80f, 80f)

    @Test
    fun `dragging the top-left corner moves both its edges`() {
        val moved = dragCrop(Handle.TopLeft, box, dx = -10f, dy = -10f, bounds = bounds, minSize = 10f)
        assertEquals(Rect(10f, 10f, 80f, 80f), moved)
    }

    @Test
    fun `a corner cannot be dragged past the picture`() {
        val moved = dragCrop(Handle.TopLeft, box, dx = -500f, dy = -500f, bounds = bounds, minSize = 10f)
        assertEquals(Rect(0f, 0f, 80f, 80f), moved)
    }

    @Test
    fun `a corner cannot be dragged past its opposite edge`() {
        val moved = dragCrop(Handle.TopLeft, box, dx = 500f, dy = 500f, bounds = bounds, minSize = 10f)
        assertEquals(Rect(70f, 70f, 80f, 80f), moved)
    }

    @Test
    fun `the left side moves only horizontally`() {
        val moved = dragCrop(Handle.Left, box, dx = -10f, dy = 40f, bounds = bounds, minSize = 10f)
        assertEquals(Rect(10f, 20f, 80f, 80f), moved)
    }

    @Test
    fun `the top side moves only vertically`() {
        val moved = dragCrop(Handle.Top, box, dx = 40f, dy = -10f, bounds = bounds, minSize = 10f)
        assertEquals(Rect(20f, 10f, 80f, 80f), moved)
    }

    @Test
    fun `a touch near a side grabs that side, not the whole box`() {
        val onLeftEdge = hitHandle(Offset(20f, 50f), box, touch = 28f)
        assertEquals(Handle.Left, onLeftEdge)
        val inTheMiddle = hitHandle(Offset(50f, 50f), box, touch = 28f)
        assertEquals(Handle.None, inTheMiddle)
    }

    @Test
    fun `the bottom-right corner grows the box`() {
        val moved = dragCrop(Handle.BottomRight, box, dx = 10f, dy = 10f, bounds = bounds, minSize = 10f)
        assertEquals(Rect(20f, 20f, 90f, 90f), moved)
    }

    // ── The pixels finally cropped ──────────────────────────────────────

    @Test
    fun `an untouched box covers the whole image`() {
        val rect = cropSourceRect(
            imageRect = Rect(0f, 0f, 100f, 100f),
            cropRect = Rect(0f, 0f, 100f, 100f),
            imageWidth = 200,
            imageHeight = 200
        )
        assertEquals(listOf(0, 0, 200, 200), rect.toList())
    }

    @Test
    fun `a half-size box covers the middle half of the image`() {
        val rect = cropSourceRect(
            imageRect = Rect(0f, 0f, 100f, 100f),
            cropRect = Rect(25f, 25f, 75f, 75f),
            imageWidth = 200,
            imageHeight = 200
        )
        assertEquals(listOf(50, 50, 100, 100), rect.toList())
    }

    @Test
    fun `a crop can never name pixels outside the image`() {
        val rect = cropSourceRect(
            imageRect = Rect(0f, 0f, 100f, 100f),
            cropRect = Rect(-50f, -50f, 200f, 200f),
            imageWidth = 200,
            imageHeight = 120
        )
        assertEquals(true, rect[0] >= 0)
        assertEquals(true, rect[1] >= 0)
        assertEquals(true, rect[0] + rect[2] <= 200)
        assertEquals(true, rect[1] + rect[3] <= 120)
    }
}
