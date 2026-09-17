package com.muddassir.clearview.goodpost.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post renderer (§17).
 *
 * The body the server stores is plain text with WhatsApp's markers still in it, so
 * this parser is the only thing standing between a stored string and what a reader
 * sees. It is tested here rather than on screen because its two failure modes are
 * both silent: a rule that is too eager bolds half a paragraph, and one that is too
 * shy shows the markers as punctuation — neither throws, and neither is obvious in
 * a screenshot.
 *
 * Every case below is a rule from the parser's own documentation, spelled out as
 * an example, including the ones that must NOT match.
 */
class GoodPostFormattingTest {

    private fun AnnotatedString.bold(): List<Pair<Int, Int>> =
        spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { it.start to it.end }

    private fun AnnotatedString.italic(): List<Pair<Int, Int>> =
        spanStyles.filter { it.item.fontStyle == FontStyle.Italic }.map { it.start to it.end }

    private fun AnnotatedString.struck(): List<Pair<Int, Int>> =
        spanStyles.filter { it.item.textDecoration == TextDecoration.LineThrough }
            .map { it.start to it.end }

    private fun AnnotatedString.mono(): List<Pair<Int, Int>> =
        spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }.map { it.start to it.end }

    @Test
    fun `plain text is left exactly as it is`() {
        val out = parseGoodPostText("Salam, this is a normal update.")
        assertEquals("Salam, this is a normal update.", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `an empty body renders as nothing rather than throwing`() {
        assertEquals("", parseGoodPostText("").text)
    }

    @Test
    fun `each marker produces its own span, and the markers themselves disappear`() {
        val bold = parseGoodPostText("*strong*")
        assertEquals("strong", bold.text)
        assertEquals(listOf(0 to 6), bold.bold())

        val italic = parseGoodPostText("_slanted_")
        assertEquals("slanted", italic.text)
        assertEquals(listOf(0 to 7), italic.italic())

        val struck = parseGoodPostText("~removed~")
        assertEquals("removed", struck.text)
        assertEquals(listOf(0 to 7), struck.struck())

        val mono = parseGoodPostText("```code```")
        assertEquals("code", mono.text)
        assertEquals(listOf(0 to 4), mono.mono())
    }

    @Test
    fun `a marker inside a word is arithmetic, not formatting`() {
        // The rule that keeps `2*3*4` and file_names from turning into spans.
        val arithmetic = parseGoodPostText("2*3*4")
        assertEquals("2*3*4", arithmetic.text)
        assertTrue(arithmetic.bold().isEmpty())

        val url = parseGoodPostText("see https://example.com/a_b_c now")
        assertEquals("see https://example.com/a_b_c now", url.text)
        assertTrue(url.italic().isEmpty())
    }

    @Test
    fun `an unclosed marker renders literally`() {
        // Someone writing a footnote asterisk must get an asterisk.
        val out = parseGoodPostText("*see the note below")
        assertEquals("*see the note below", out.text)
        assertTrue(out.bold().isEmpty())
    }

    @Test
    fun `content may not begin or end with whitespace`() {
        val out = parseGoodPostText("* padded *")
        assertEquals("* padded *", out.text)
        assertTrue(out.bold().isEmpty())
    }

    @Test
    fun `a marker spans a line break`() {
        val out = parseGoodPostText("*first\nsecond*")
        assertEquals("first\nsecond", out.text)
        assertEquals(listOf(0 to 12), out.bold())
    }

    @Test
    fun `several formats in one body are all applied`() {
        val out = parseGoodPostText("*bold* and _italic_ and ~gone~")
        assertEquals("bold and italic and gone", out.text)
        assertEquals(1, out.bold().size)
        assertEquals(1, out.italic().size)
        assertEquals(1, out.struck().size)
        // The spans point at the RIGHT runs, not merely at some run.
        assertEquals("bold", out.text.substring(out.bold().first().first, out.bold().first().second))
        assertEquals(
            "italic",
            out.text.substring(out.italic().first().first, out.italic().first().second)
        )
    }

    @Test
    fun `the outer marker wins when markers are nested`() {
        // The composer can produce this by wrapping already-formatted text, and it
        // must render predictably rather than throwing or rendering nothing.
        val out = parseGoodPostText("*_both_*")
        assertEquals("_both_", out.text)
        assertEquals(listOf(0 to 6), out.bold())
        assertTrue("nesting is not interpreted", out.italic().isEmpty())
    }

    @Test
    fun `a body that looks like markup is displayed as text`() {
        // The safety property in one assertion: there is no HTML path, so a body
        // containing tags is a body containing tags.
        val out = parseGoodPostText("<b>not bold</b> & <script>alert(1)</script>")
        assertEquals("<b>not bold</b> & <script>alert(1)</script>", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `a triple backtick run is read as monospace, not as a stray pair`() {
        val out = parseGoodPostText("```a*b*c```")
        assertEquals("a*b*c", out.text)
        assertEquals(listOf(0 to 5), out.mono())
    }
}
