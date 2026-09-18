package com.muddassir.clearview.ui

import com.muddassir.clearview.quran.model.QuranVerse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two rules of the continuous surah reader that are easy to get subtly wrong
 * and that no compiler or screenshot would catch (§4, §10).
 *
 * The first is the ayah marker: the number is drawn inside the `۝` ornament, and
 * a mushaf writes it in Arabic-Indic digits, so a reader who sees `...۝12` next to
 * a Latin `12` in the reference line is looking at the same number twice in two
 * scripts. The second is what a term typed into the field finds: inside a surah
 * the field narrows THAT surah, and it has to answer the same three ways the
 * tabbed search does — words in the translation, a `16:5`-style reference, or a
 * bare ayah number — or "why does it not find 2:255" comes back.
 */
class QuranSurahReaderTest {

    private fun verse(surah: Int, ayah: Int, text: String) = QuranVerse(
        surahNumber = surah,
        ayahNumber = ayah,
        surahName = "An-Nahl",
        surahTranslation = "The Bee",
        text = text,
        totalAyahs = 128
    )

    @Test
    fun `the ayah marker carries Arabic-Indic digits`() {
        assertEquals("\u0661", arabicDigits(1))
        assertEquals("\u0661\u0662\u0668", arabicDigits(128))
    }

    @Test
    fun `a term finds the translation, the reference and the ayah number`() {
        val verses = listOf(
            verse(16, 1, "The command of Allah is at hand"),
            verse(16, 2, "He sends down the angels with revelation"),
            verse(16, 128, "Indeed, the patient will be repaid without measure")
        )

        // A word in the translated text.
        assertEquals(listOf(1), matchingVerseIndices(verses, "angels"))
        // The same word in a different case: the field is not case sensitive.
        assertEquals(listOf(0), matchingVerseIndices(verses, "Allah"))
        // A surah:ayah reference, which is the form the field's hint advertises.
        assertEquals(listOf(2), matchingVerseIndices(verses, "16:128"))
        // A bare number is an AYAH inside a surah, not a surah: "16" here is the
        // sixteenth ayah of An-Nahl, not every verse of it — which is what a
        // substring match on "16:1" would have said.
        assertEquals(listOf(0), matchingVerseIndices(verses, "1"))
        assertEquals(listOf(2), matchingVerseIndices(verses, "128"))
        // A reference to a surah that is not open finds nothing: the reader stays
        // inside the text on screen.
        assertEquals(emptyList<Int>(), matchingVerseIndices(verses, "2:255"))
    }

    @Test
    fun `an empty field matches nothing, so the surah stays whole`() {
        val verses = listOf(verse(16, 1, "a"), verse(16, 2, "b"))
        assertEquals(emptyList<Int>(), matchingVerseIndices(verses, ""))
    }

    // ── Where the reader left off (§2) ──
    //
    // The saved position is an AYAH; the list is addressed by ROW INDEX, and the
    // basmala sits above the first verse. Getting that wrong by one opens a surah
    // a verse past where it was left — small enough to look like the reader's own
    // misremembering, which is exactly why it is pinned here.

    @Test
    fun `a saved ayah is restored to its row, not to its number`() {
        val verses = listOf(verse(16, 1, "a"), verse(16, 2, "b"), verse(16, 128, "c"))

        assertEquals(2, readingPositionIndex(verses, 2))
        assertEquals(3, readingPositionIndex(verses, 128))
        // Ayah 1 is the top of the surah, so the row is 1 — the basmala scrolled
        // just off. The store never saves this (ayah 1 clears the entry instead),
        // which is what keeps reopening at the beginning free of a jump.
        assertEquals(1, readingPositionIndex(verses, 1))
    }

    @Test
    fun `nothing to restore is nothing to scroll`() {
        val verses = listOf(verse(16, 1, "a"), verse(16, 2, "b"))

        // Never opened before.
        assertNull(readingPositionIndex(verses, null))
        // A surah that does not have that ayah — a position saved against another
        // edition, or against a verse count that has since changed.
        assertNull(readingPositionIndex(verses, 7))
    }

    @Test
    fun `a position is read back in the same terms it was saved`() {
        val verses = listOf(verse(16, 1, "a"), verse(16, 2, "b"))

        assertEquals(2, readingPositionAyah(verses, 2))
        // Row 0 is the basmala, which is not an ayah of this surah: having scrolled
        // it off is not having read verse 1, and must not be saved as such.
        assertNull(readingPositionAyah(verses, 0))
        // Past the end, which a list that has just been emptied reports.
        assertNull(readingPositionAyah(verses, 9))
    }

    @Test
    fun `the two conversions are inverses`() {
        val verses = listOf(verse(16, 1, "a"), verse(16, 2, "b"), verse(16, 3, "c"))

        for (saved in listOf(1, 2, 3)) {
            val row = readingPositionIndex(verses, saved)
            assertEquals(saved, readingPositionAyah(verses, row!!))
        }
    }
}
