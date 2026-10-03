package com.muddassir.clearview.goodpost.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advertisement payload parsing (§12, §15).
 *
 * Pure, because this is the boundary where a field rename turns every card into
 * a blank rectangle: a card missing its id or type cannot be drawn, and one
 * missing its schedule must degrade to "none" rather than to 1970.
 */
class GoodPostAdCodecTest {

    private fun textAd(overrides: Map<String, Any?> = emptyMap()): JSONObject {
        val json = JSONObject()
            .put("id", "00000000-0000-4000-8000-000000000001")
            .put("contentType", "text")
            .put("text", "YOUR AD HERE")
            .put("targetUrl", "mailto:ads@example.com")
            .put("showInChannels", true)
            .put("showInExplore", false)
            .put("enabled", true)
            .put("priority", 100)
        overrides.forEach { (key, value) -> json.put(key, value) }
        return json
    }

    @Test
    fun `a text card parses with its placement and no image`() {
        val ad = GoodPostCodec.ad(textAd())!!
        assertEquals("text", ad.contentType)
        assertTrue(ad.isText)
        assertFalse(ad.isImage)
        assertEquals("YOUR AD HERE", ad.text)
        assertEquals("mailto:ads@example.com", ad.targetUrl)
        assertTrue(ad.showInChannels)
        assertFalse(ad.showInExplore)
        assertNull(ad.imageUrl)
        assertNull(ad.expiresAt)
    }

    @Test
    fun `an image card carries a signed image url and no text`() {
        val ad = GoodPostCodec.ad(
            JSONObject()
                .put("id", "abc")
                .put("contentType", "image")
                .put("imageUrl", "https://cdn.example/x.jpg")
                .put("showInChannels", true)
                .put("enabled", true)
        )!!
        assertTrue(ad.isImage)
        assertEquals("https://cdn.example/x.jpg", ad.imageUrl)
        assertNull(ad.text)
    }

    @Test
    fun `a card without an id or a type is dropped`() {
        assertNull(GoodPostCodec.ad(JSONObject().put("contentType", "text")))
        assertNull(GoodPostCodec.ad(JSONObject().put("id", "x")))
    }

    @Test
    fun `a listing drops unreadable rows and keeps the rest`() {
        val body = JSONObject().put(
            "ads",
            org.json.JSONArray()
                .put(textAd())
                .put(JSONObject().put("text", "no id here"))
        )
        val parsed = GoodPostCodec.adList(body)
        assertEquals(1, parsed.size)
        assertEquals("00000000-0000-4000-8000-000000000001", parsed.first().id)
    }

    @Test
    fun `an absent ads array is an empty list rather than a failure`() {
        assertTrue(GoodPostCodec.adList(JSONObject()).isEmpty())
    }

    @Test
    fun `a single-card body reads the ad wrapper`() {
        val body = JSONObject().put("ad", textAd())
        assertEquals("YOUR AD HERE", GoodPostCodec.singleAd(body)!!.text)
        assertNull(GoodPostCodec.singleAd(JSONObject()))
    }

    @Test
    fun `the placement enum sends the wire values the server expects`() {
        assertEquals("channels", GoodPostAdPlacement.Channels.wire)
        assertEquals("explore", GoodPostAdPlacement.Explore.wire)
    }
}
