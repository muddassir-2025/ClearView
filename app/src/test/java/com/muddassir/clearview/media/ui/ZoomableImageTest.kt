package com.muddassir.clearview.media.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pan bounds of a zoomed image.
 *
 * A wrong bound is not a cosmetic bug: too loose and a drag throws the picture
 * off-screen with white behind it, too tight and the corner of an enlarged image
 * can never be reached.
 */
class ZoomableImageTest {

    /** At 1× the whole image is on screen, so there is nothing to pan. */
    @Test
    fun atOriginalScaleNothingMoves() {
        val clamped = clampOffset(Offset(120f, -80f), scale = 1f, width = 1000f, height = 800f)
        assertEquals(0f, clamped.x, 0.001f)
        assertEquals(0f, clamped.y, 0.001f)
    }

    /** At 2× exactly half the image overhangs each edge — that is the limit. */
    @Test
    fun atDoubleScalePanIsLimitedToTheOverhang() {
        val clamped = clampOffset(Offset(9999f, -9999f), scale = 2f, width = 1000f, height = 800f)
        assertEquals(500f, clamped.x, 0.001f)
        assertEquals(-400f, clamped.y, 0.001f)
    }

    /** Inside the bounds the offset is the reader's own. */
    @Test
    fun insideTheBoundsThePanIsKept() {
        val wanted = Offset(120f, -90f)
        val clamped = clampOffset(wanted, scale = 2f, width = 1000f, height = 800f)
        assertEquals(wanted.x, clamped.x, 0.001f)
        assertEquals(wanted.y, clamped.y, 0.001f)
    }

    /** An unmeasured box (first frame) must not divide the offset by zero. */
    @Test
    fun unmeasuredBoxResetsThePan() {
        val clamped = clampOffset(Offset(50f, 50f), scale = 3f, width = 0f, height = 0f)
        assertEquals(Offset.Zero, clamped)
    }
}
