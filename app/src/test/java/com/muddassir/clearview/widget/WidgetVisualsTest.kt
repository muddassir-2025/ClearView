package com.muddassir.clearview.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The To-Do widget's square, which is measured from a cell rather than written
 * into the layout.
 *
 * These numbers are the ones that came off the launcher: on the device this was
 * developed against a 1x1 cell reports 82dp of width and 121dp of height, and the
 * card that filled that cell was the tall rectangle the ring looked wrong in.
 */
class WidgetVisualsTest {

    @Test
    fun `side follows the narrower dimension of the cell`() {
        // The measured phone cell: wider than it is tall is NOT the case here —
        // a launcher's cells are taller than they are wide, so the width binds.
        assertEquals(80, todoSquareSideDp(widthDp = 82, heightDp = 121))
        // The other way round must give the same answer: whichever is smaller.
        assertEquals(80, todoSquareSideDp(widthDp = 121, heightDp = 82))
    }

    @Test
    fun `side grows with the cell`() {
        val small = todoSquareSideDp(widthDp = 60, heightDp = 100)
        val large = todoSquareSideDp(widthDp = 100, heightDp = 160)
        assertTrue("$small should be smaller than $large", small < large)
    }

    @Test
    fun `a cell too narrow for the margin still leaves a legible square`() {
        // The floor exists so a ring with a fraction inside it cannot be shrunk
        // into an unreadable smudge; overflowing a very tight cell is the
        // deliberate side of that trade.
        assertEquals(40, todoSquareSideDp(widthDp = 30, heightDp = 100))
        assertEquals(40, todoSquareSideDp(widthDp = 41, heightDp = 41))
    }

    @Test
    fun `a roomy cell cannot turn the chip into a panel`() {
        assertEquals(120, todoSquareSideDp(widthDp = 400, heightDp = 600))
    }

    @Test
    fun `an unmeasured cell falls back to the floor rather than a zero side`() {
        // getAppWidgetOptions returns 0 for a key the launcher did not set, and a
        // zero side would be an invisible widget — worse than a small one.
        assertEquals(40, todoSquareSideDp(widthDp = 0, heightDp = 0))
        assertEquals(40, todoSquareSideDp(widthDp = -1, heightDp = 121))
        assertEquals(40, todoSquareSideDp(widthDp = 82, heightDp = 0))
    }
}
