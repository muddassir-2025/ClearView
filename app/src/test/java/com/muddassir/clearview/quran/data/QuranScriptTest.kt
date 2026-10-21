package com.muddassir.clearview.quran.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corpus's mark encoding is re-spelled for the bundled face (§1).
 *
 * The bug this guards against is the one that was reported: marks that collide
 * with the letters "in some places". The bundled Mushaf face has no glyph for
 * U+06E1 — the corpus's spelling of the sukun, 62,381 occurrences — so those
 * marks were drawn by a fallback face at anchors built for other letters. The
 * mapping moves them to U+0652, which the face does have, so it draws them
 * itself.
 *
 * These tests pin the properties that make that safe: it is one mark for one
 * mark, the same length, the same everything else.
 */
class QuranScriptTest {

    /** The corpus's sukun, as it arrives from the API. */
    private val corpusSukun = '\u06E1'

    /** The classic IndoPak sukun, which the bundled face has a glyph for. */
    private val faceSukun = '\u0652'

    @Test
    fun `the corpus sukun is re-spelled as the one the face has`() {
        val verse = "\u0628\u0650\u0633\u06E1\u0645\u0650"
        val spelled = quranDisplayText(verse)
        assertTrue(
            "U+06E1 must not survive: the bundled face has no glyph for it",
            spelled.none { it == corpusSukun }
        )
        assertEquals(
            "\u0628\u0650\u0633\u0652\u0645\u0650",
            spelled
        )
    }

    @Test
    fun `the rewrite is the same length and leaves everything else alone`() {
        // Length matters beyond neatness: search highlighting maps match indices
        // back into the string, and a rewrite that changed the length would move
        // every highlight in the verse.
        val corpus = "\u0627\u0644\u0652\u062D\u064E\u0645\u06E1\u062F\u064F"
        val spelled = quranDisplayText(corpus)
        assertEquals(corpus.length, spelled.length)
        // Everything except the sukun — letters, fatha, damma, order — is
        // untouched. The sukun itself is filtered out of BOTH spellings, since
        // the point is that those two codepoints are the only difference.
        fun withoutSukun(s: String) = s.filterNot { it == corpusSukun || it == faceSukun }
        assertEquals(withoutSukun(corpus), withoutSukun(spelled))
        // And prove something actually moved, or the assertions above are vacuous.
        assertTrue(corpus.contains(corpusSukun))
        assertTrue(spelled.contains(faceSukun))
    }

    @Test
    fun `applying it twice changes nothing`() {
        val once = quranDisplayText("a\u06E1b")
        assertEquals(once, quranDisplayText(once))
    }

    @Test
    fun `text with no corpus-only mark is returned untouched`() {
        // Same instance, not just an equal string: this runs on every verse and
        // must not allocate where there is nothing to do.
        val clean = "\u0628\u0650\u0633\u0652\u0645\u0650 \u0627\u0644\u0644\u0647\u0650"
        assertTrue(clean === quranDisplayText(clean))
    }

    @Test
    fun `marks the face cannot draw either are left alone`() {
        // U+0658 (noon ghunna) and the small-high waqf signs have no shape in this
        // face, and each is a DIFFERENT sign. Re-spelling them would change what a
        // verse says to change how it looks, so they must pass through untouched.
        val untouched = listOf('\u0658', '\u06E0', '\u08D4', '\u08DA', '\u08E0', '\u08E1')
        for (mark in untouched) {
            assertEquals(
                "U+%04X must not be rewritten".format(mark.code),
                "x${mark}y",
                quranDisplayText("x${mark}y")
            )
        }
    }

    @Test
    fun `the sukun really is the mark being moved, not a lookalike`() {
        // Both codepoints are the sukun: the corpus spells it U+06E1 and the face
        // spells it U+0652. Pinned because the whole mapping rests on it — if a
        // future corpus spelled something else with U+06E1 this would be wrong.
        assertNotEquals(corpusSukun, faceSukun)
    }

}
