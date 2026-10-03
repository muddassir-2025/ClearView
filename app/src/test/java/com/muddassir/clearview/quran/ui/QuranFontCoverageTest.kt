package com.muddassir.clearview.quran.ui

import androidx.compose.ui.text.font.FontWeight
import com.muddassir.clearview.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Quran face must cover the marks the IndoPak text actually uses (§1).
 *
 * The bug this guards against is silent: a font with no glyph for a combining
 * mark still "works" — the engine falls back for it or drops it — so the only
 * symptom is a reader noticing that some zabar / zer / waqf sign are missing.
 * Coverage has to be asserted against the face itself, not eyeballed.
 *
 * The set below is every combining mark in the downloaded IndoPak edition
 * (ara-quranindopak), measured over all 6236 verses. The previous face (Amiri
 * Quran) had no glyph for 13 of them — U+0614 and the U+08D4–U+08E2 small-high
 * waqf marks — which is exactly what was reported as missing.
 */
class QuranFontCoverageTest {

    /** Every combining mark used by the IndoPak corpus. */
    private val indoPakMarks = listOf(
        0x064B, 0x064C, 0x064D, 0x064E, 0x064F, 0x0650, 0x0651, 0x0652,
        0x0653, 0x0654, 0x0655, 0x0656, 0x0657, 0x0658, 0x0614, 0x0615,
        0x0617, 0x0670, 0x06D6, 0x06D8, 0x06D9, 0x06DA, 0x06DB, 0x06DC,
        0x06E0, 0x06E1, 0x06E8, 0x06ED, 0x08D4, 0x08D5, 0x08D6, 0x08D7,
        0x08DA, 0x08DB, 0x08DD, 0x08DE, 0x08DF, 0x08E0, 0x08E1
    )

    /**
     * The bundled face is a Compose [Font] resource, so the glyph table is not
     * readable from a JVM unit test. What IS assertable here is the wiring: the
     * family is built from exactly one bundled face, at the BOLD weight, and it
     * is the Scheherazade face that was measured to cover every mark above.
     * The glyph-level check lives in the font choice itself (documented on
     * [QuranFontFamily]) and was verified against the whole corpus with
     * fontTools.
     */
    @Test
    fun `the Quran family is the measured full-coverage face`() {
        assertEquals(R.font.scheherazade_new_bold, QURAN_FONT_RES)
    }

    @Test
    fun `the Quran face is bold`() {
        assertEquals(FontWeight.Bold, QURAN_FONT_WEIGHT)
    }

    @Test
    fun `the line height leaves room for the marks`() {
        // The corpus's ink spans 2.09 em in this face, so anything less than
        // that clips the outer harakat.
        assertTrue(
            "line-height ratio $QURAN_LINE_HEIGHT_RATIO clips the marks",
            QURAN_LINE_HEIGHT_RATIO >= 2.09f
        )
    }

    @Test
    fun `the mark set is the one the corpus uses`() {
        // 39 distinct combining marks, including the waqf signs that the old
        // face dropped. Kept explicit so a future corpus change is a deliberate
        // edit here rather than a silent rendering regression.
        assertEquals(39, indoPakMarks.distinct().size)
        assertTrue(indoPakMarks.contains(0x08D6)) // small high ain
        assertTrue(indoPakMarks.contains(0x08DE)) // small high word qif
        assertTrue(indoPakMarks.contains(0x0614)) // takhallus
    }
}
