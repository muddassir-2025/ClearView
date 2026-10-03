package com.muddassir.clearview.quran.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the Quran Arabic integrity rules (§1).
 *
 * The strings are the REAL authoritative text for Yaseen 36:68 from the two
 * editions the app consumes — IndoPak and Uthmani Hafs — so the checks are
 * proven against text that actually carries zabar/zer/pesh, shadda, sukoon and
 * the small high marks, not a made-up sample.
 */
class QuranTextIntegrityTest {

    private val indoPak =
        "وَمَنۡ نُّعَمِّرۡهُ نُنَكِّسۡهُ فِي الۡخَلۡقِؕ اَفَلَا يَعۡقِلُوۡنَ"

    private val uthmani =
        "وَمَن نُّعَمِّرۡهُ نُنَكِّسۡهُ فِي ٱلۡخَلۡقِۚ أَفَلَا يَعۡقِلُونَ"

    @Test
    fun `a marked verse passes the integrity check`() {
        assertNull(QuranTextIntegrity.problemWith(indoPak))
        assertNull(QuranTextIntegrity.problemWith(uthmani))
    }

    @Test
    fun `a marked verse counts its combining marks`() {
        assertTrue(QuranTextIntegrity.combiningMarkCount(indoPak) > 10)
        assertTrue(QuranTextIntegrity.combiningMarkCount(uthmani) > 10)
        assertFalse(QuranTextIntegrity.looksUnmarked(indoPak))
    }

    @Test
    fun `a decoded-failure replacement is flagged`() {
        assertEquals(
            "contains U+FFFD (decoding failure)",
            QuranTextIntegrity.problemWith("وَمَن\uFFFD نُّعَمِّرْهُ")
        )
    }

    @Test
    fun `an empty string is not corruption`() {
        assertNull(QuranTextIntegrity.problemWith(""))
        assertNull(QuranTextIntegrity.problemWith("   "))
    }

    @Test
    fun `unmarked Arabic is detectable but not an error`() {
        // A diacritics-stripping bug looks like this; asking the question must
        // not itself fail rendering.
        assertTrue(QuranTextIntegrity.looksUnmarked("محمد"))
        assertNull(QuranTextIntegrity.problemWith("محمد"))
    }
}
