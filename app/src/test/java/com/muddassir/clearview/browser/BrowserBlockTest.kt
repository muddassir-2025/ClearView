package com.muddassir.clearview.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
