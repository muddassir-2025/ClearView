package com.muddassir.clearview.browser

import com.muddassir.clearview.brainrot.BrainRotRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page script only understands this small JSON contract. */
class BrowserBlockTest {

    @Test
    fun blockedDecisionSerializesForThePageScript() {
        val json = JSONObject(
            BrowserBlock("badword", BrowserBlock.Type.KEYWORD).toJson()
        )
        assertTrue(json.getBoolean("blocked"))
        assertEquals("badword", json.getString("item"))
        assertEquals("KEYWORD", json.getString("type"))
    }

    @Test
    fun everyTypeHasAReason() {
        for (type in BrowserBlock.Type.entries) {
            val message = BrowserBlock("rule", type).message()
            assertTrue("$type reason must not be blank", message.isNotBlank())
        }
    }

    /**
     * Mobile feed cards often show only the channel's display name. YouTube
     * derives the handle from that name plus a trailing number ("Beluga" →
     * `@Beluga1`), so the block's handle is the only link between the two.
     */
    @Test
    fun aDigitSuffixedHandleYieldsTheChannelNameStem() {
        assertEquals("beluga", BrowserRules.handleStem("@Beluga1"))
        assertEquals("beluga", BrowserRules.handleStem("beluga1"))
        assertEquals("mrbeast", BrowserRules.handleStem("@MrBeast2"))
    }

    @Test
    fun aPlainHandleNeverGainsALooserSpelling() {
        assertNull(BrowserRules.handleStem("@beluga"))
        assertNull(BrowserRules.handleStem(null))
        assertNull(BrowserRules.handleStem("@a1"))
        assertNull(BrowserRules.handleStem("@"))
    }

    @Test
    fun trailingSeparatorsAreTrimmedFromTheStem() {
        assertEquals("chan", BrowserRules.handleStem("@chan_2"))
        assertEquals("some", BrowserRules.handleStem("@some.99"))
    }

    /**
     * The browser bridge used to store the literal string "null" for a card
     * with no channel name, which filled the name index with "null" and left
     * the real name empty.
     */
    @Test
    fun theLiteralNullNameIsNotAName() {
        assertNull(BrainRotRepository.cleanName("null"))
        assertNull(BrainRotRepository.cleanName(" NULL "))
        assertNull(BrainRotRepository.cleanName("undefined"))
        assertNull(BrainRotRepository.cleanName("   "))
        assertNull(BrainRotRepository.cleanName(null))
        assertEquals("Beluga", BrainRotRepository.cleanName(" Beluga "))
    }

    @Test
    fun shortsAndDomainHaveTheirOwnExplanations() {
        assertEquals(
            "YouTube Shorts are blocked",
            BrowserBlock("YouTube Shorts", BrowserBlock.Type.SHORTS).message()
        )
        assertEquals(
            "This website has been blocked",
            BrowserBlock("example.com", BrowserBlock.Type.DOMAIN).message()
        )
        assertEquals(
            "Instagram Reels are blocked",
            BrowserBlock("Instagram Reels", BrowserBlock.Type.REELS).message()
        )
    }
}
