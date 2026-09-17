package com.muddassir.clearview.goodpost.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The formatting toolbar's rule (§17).
 *
 * Pure, because it is text editing arithmetic with an off-by-one at every edge:
 * a caret at zero, a caret at the very end, a reversed selection the platform
 * hands over as end-before-start, and a selection that is already wrapped. Those
 * are exactly the cases a screen would only be tested through by hand.
 */
class GoodPostTextTest {

    private fun toggle(
        text: String,
        start: Int,
        end: Int,
        format: GoodPostFormat = GoodPostFormat.Bold
    ) = applyGoodPostFormat(text, start, end, format)

    @Test
    fun `an empty selection plants the pair and stands between it`() {
        val edit = toggle("hello", 5, 5)
        assertEquals("hello**", edit.text)
        // The caret is INSIDE the pair, so the next characters typed are bold —
        // which is the only reading that makes a toolbar button useful on its own.
        assertEquals(6, edit.selectionStart)
        assertEquals(6, edit.selectionEnd)
    }

    @Test
    fun `an empty selection at the start plants the pair at the start`() {
        val edit = toggle("", 0, 0)
        assertEquals("**", edit.text)
        assertEquals(1, edit.selectionStart)
    }

    @Test
    fun `a selection is wrapped, and the whole wrapped run stays selected`() {
        val edit = toggle("make this bold", 5, 9)
        assertEquals("make *this* bold", edit.text)
        // The delimiters are inside the selection on purpose: keeping only the
        // inner text highlighted made every further tap add another pair.
        assertEquals("*this*", edit.text.substring(edit.selectionStart, edit.selectionEnd))
    }

    @Test
    fun `a pair typed by hand around the selection is taken off by the same button`() {
        // `*bold*` written out, then `bold` selected: the button has to recognise
        // markers it did not insert, or typing them by hand would be a trap.
        val edit = toggle("*bold*", 1, 5)
        assertEquals("bold", edit.text)
        assertEquals("bold", edit.text.substring(edit.selectionStart, edit.selectionEnd))
    }

    @Test
    fun `a second tap takes the wrap off again`() {
        val wrapped = toggle("make this bold", 5, 9)
        val unwrapped = toggle(
            wrapped.text,
            wrapped.selectionStart,
            wrapped.selectionEnd
        )
        assertEquals("make this bold", unwrapped.text)
        assertEquals("this", unwrapped.text.substring(unwrapped.selectionStart, unwrapped.selectionEnd))
    }

    @Test
    fun `toggling is stable however many times it is tapped`() {
        var text = "abc"
        var start = 1
        var end = 2
        repeat(6) {
            val edit = toggle(text, start, end)
            text = edit.text
            start = edit.selectionStart
            end = edit.selectionEnd
        }
        // Three on/off cycles, so we are back where we started — not six pairs of
        // markers stacked around one letter.
        assertEquals("abc", text)
    }

    @Test
    fun `a reversed selection is treated as a normal one`() {
        val edit = toggle("make this bold", 9, 5)
        assertEquals("make *this* bold", edit.text)
    }

    @Test
    fun `offsets outside the text are pulled back into it rather than throwing`() {
        val edit = toggle("hi", -5, 99)
        assertEquals("*hi*", edit.text)
        assertEquals("*hi*", edit.text.substring(edit.selectionStart, edit.selectionEnd))
    }

    @Test
    fun `each format uses its own marker`() {
        assertEquals("_i_", toggle("i", 0, 1, GoodPostFormat.Italic).text)
        assertEquals("~s~", toggle("s", 0, 1, GoodPostFormat.Strikethrough).text)
        assertEquals("```m```", toggle("m", 0, 1, GoodPostFormat.Monospace).text)
    }

    @Test
    fun `wrapping is refused for text that is only delimiters`() {
        // `*` selected on its own is not \"already bold\"; stripping it would leave
        // an empty body, so it must be treated as text to wrap instead.
        assertFalse(GoodPostFormat.Bold.wraps("*"))
        assertFalse(GoodPostFormat.Bold.wraps("**"))
        assertFalse(GoodPostFormat.Bold.wraps("***"))
        assertTrue(GoodPostFormat.Bold.wraps("*x*"))
    }

    @Test
    fun `a mixed selection is not considered already formatted`() {
        // Two bold runs selected whole are not one bold run. Un-wrapping would
        // corrupt them, so `wraps` says no and the toggle wraps instead.
        val mixed = "*a* and *b*"
        assertFalse(GoodPostFormat.Bold.wraps(mixed))
    }

}
