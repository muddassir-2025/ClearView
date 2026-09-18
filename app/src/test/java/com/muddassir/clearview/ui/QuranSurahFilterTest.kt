package com.muddassir.clearview.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule both surah lists are filtered by — the browse list of all 114 and the
 * bookmarks screen's Surah tab (§2).
 *
 * It is one rule and one function on purpose: a term that finds a surah in the
 * browse list and not in the saved list reads as a lost bookmark, and vice versa.
 * The part worth pinning is the number case. "2" has to mean Surah 2 and not every
 * surah whose name or translation happens to contain a 2 — an over-eager substring
 * match on a numeral is the kind of bug that looks like a feature until a reader
 * types a number.
 */
class QuranSurahFilterTest {

    private val all = (1..114).toList()

    @Test
    fun `a blank term narrows nothing`() {
        assertEquals(all, filterSurahs(all, ""))
        assertEquals(all, filterSurahs(all, "   "))
    }

    @Test
    fun `a number is a surah number, exactly`() {
        assertEquals(listOf(2), filterSurahs(all, "2"))
        assertEquals(listOf(114), filterSurahs(all, "114"))
    }

    @Test
    fun `words match the name and the translation`() {
        assertEquals(listOf(2), filterSurahs(all, "baqara"))
        assertEquals(listOf(2), filterSurahs(all, "cow"))
        assertEquals(listOf(2), filterSurahs(all, "BAQARA"))
    }

    @Test
    fun `a saved surah is found by the same terms as an unsaved one`() {
        val saved = listOf(2, 18, 36)
        assertEquals(listOf(2), filterSurahs(saved, "cow"))
        assertEquals(listOf(18), filterSurahs(saved, "kahf"))
        assertEquals(listOf(36), filterSurahs(saved, "36"))
        assertEquals(emptyList<Int>(), filterSurahs(saved, "yusuf"))
    }
}
