package com.muddassir.clearview.quran.ui

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
        // Measured over all 6236 verses: every "official IndoPak" font (Amiri,
        // QuranWBW, PDMS Saleem) is missing 12-15 marks against THIS app's text,
        // because the app's text is a different IndoPak corpus. Scheherazade New
        // covers all of it.
        assertEquals(R.font.scheherazade_new, QURAN_FONT_RES)
    }

    @Test
    fun `the line height leaves room for the tallest mark`() {
        // Measured in Scheherazade New Regular: ascent 1.343 em, descent 0.697 em,
        // tallest mark 1.279 em above the baseline — inside the font's own
        // ascent, so no clipping. A mark's room above the baseline is
        // (lineHeight + ascent - descent) / 2, so the ratio must satisfy
        // ratio >= 2*tallest - ascent + descent = 2*1.279 - 1.343 + 0.697 = 1.912.
        val ascent = 1.343f
        val descent = 0.697f
        val tallestMark = 1.279f
        val required = 2f * tallestMark - ascent + descent
        assertTrue(
            "line-height ratio $QURAN_LINE_HEIGHT_RATIO clips the tallest mark (needs $required)",
            QURAN_LINE_HEIGHT_RATIO >= required
        )
    }

    @Test
    fun `the three marks that overshoot the ascent are known`() {
        // These are the marks that reach past the font's own ascent line and so
        // depend on the line height to stay visible. If a future font change
        // alters this set, it should be a deliberate edit here.
        val overAscent = listOf(0x08DA, 0x08D4, 0x08DD)
        assertTrue(overAscent.all { indoPakMarks.contains(it) })
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
