package com.muddassir.clearview.quran.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reading-position codec (§2): one "surah:ayah" entry per surah, saying which
 * verse the reader left at the top of the page.
 *
 * Two rules are worth pinning, and neither is visible in a screenshot. The first
 * is that ayah 1 CLEARS the entry instead of storing it: a stored "16:1" means the
 * same thing, so it would have to be read on every open to find out whether the
 * reader had been there or never opened the surah at all — and the answer decides
 * whether the page scrolls. The second is that a surah holds at most ONE entry,
 * which is what lets the reader resolve a position with a single scan rather than
 * sorting entries by age.
 */
class QuranReadingPositionTest {

    @Test
    fun `a position is read back for its own surah`() {
        val positions = setOf("2:255", "16:40")

        assertEquals(255, readingPositionOf(positions, 2))
        assertEquals(40, readingPositionOf(positions, 16))
        assertNull(readingPositionOf(positions, 3))
    }

    @Test
    fun `a surah number that is a prefix of another does not collide`() {
        val positions = setOf("11:5")

        // Surah 1 must not find surah 11's position. The colon is what stops it.
        assertNull(readingPositionOf(positions, 1))
        assertEquals(5, readingPositionOf(positions, 11))
    }

    @Test
    fun `a damaged entry is not a position`() {
        assertNull(readingPositionOf(setOf("16"), 16))
        assertNull(readingPositionOf(setOf("16:"), 16))
        assertNull(readingPositionOf(setOf("16:x"), 16))
    }

    @Test
    fun `a surah holds at most one position`() {
        val after = withReadingPosition(setOf("16:40"), 16, 55)

        assertEquals(setOf("16:55"), after)
    }

    @Test
    fun `writing a position leaves every other surah alone`() {
        val before = setOf("2:255", "16:40")

        assertEquals(setOf("2:255", "16:41"), withReadingPosition(before, 16, 41))
    }

    @Test
    fun `returning to the first ayah clears the entry`() {
        val before = setOf("2:255", "16:40")

        assertEquals(setOf("2:255"), withReadingPosition(before, 16, 1))
        // A nonsense ayah clears too: the reader is at the top either way, and an
        // entry saying otherwise would send them somewhere that does not exist.
        assertEquals(setOf("2:255"), withReadingPosition(before, 16, 0))
        // Nothing to clear, and nothing added.
        assertEquals(setOf("2:255"), withReadingPosition(setOf("2:255"), 16, 1))
    }

    @Test
    fun `writing does not mutate the set it was handed`() {
        val before = setOf("2:255", "16:40")
        val snapshot = before.toSet()

        withReadingPosition(before, 16, 99)

        // SharedPreferences hands out its own instance; editing it in place would
        // be writing to their set behind their back.
        assertEquals(snapshot, before)
    }
}
