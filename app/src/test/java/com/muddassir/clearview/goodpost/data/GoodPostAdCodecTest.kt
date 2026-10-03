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

    @Test
    fun `a card's styling defaults when the payload omits it`() {
        val ad = GoodPostCodec.ad(textAd())!!
        assertEquals(AD_DEFAULT_TEXT_COLOR, ad.textColor)
        assertEquals("cover", ad.imageFit)
        assertEquals(0.5f, ad.imageFocusX, 0.0001f)
        assertEquals(0.5f, ad.imageFocusY, 0.0001f)
    }

    @Test
    fun `a card's styling is read back, and an unknown fit becomes cover`() {
        val ad = GoodPostCodec.ad(
            textAd(
                mapOf(
                    "textColor" to "#25D366",
                    "imageFit" to "contain",
                    "imageFocusX" to 0.2,
                    "imageFocusY" to 0.9
                )
            )
        )!!
        assertEquals("#25D366", ad.textColor)
        assertEquals("contain", ad.imageFit)
        assertEquals(0.2f, ad.imageFocusX, 0.0001f)
        assertEquals(0.9f, ad.imageFocusY, 0.0001f)

        // A fit the painter does not know is not a third mode, it is a typo.
        assertEquals("cover", GoodPostCodec.ad(textAd(mapOf("imageFit" to "stretch")))!!.imageFit)
        // And a focus outside the frame is clamped rather than drawn off-screen.
        assertEquals(1f, GoodPostCodec.ad(textAd(mapOf("imageFocusX" to 4.0)))!!.imageFocusX, 0.0001f)
    }

    @Test
    fun `a cached card's window is checked against the clock`() {
        val now = 1_700_000_000_000L
        val open = GoodPostCodec.ad(textAd())!!
        assertTrue(open.isActiveAt(now))

        val future = GoodPostCodec.ad(
            textAd(mapOf("startsAt" to "2030-01-01T00:00:00.000Z"))
        )!!
        assertFalse(future.isActiveAt(now))

        val expired = GoodPostCodec.ad(
            textAd(mapOf("expiresAt" to "2020-01-01T00:00:00.000Z"))
        )!!
        assertFalse(expired.isActiveAt(now))
    }

    @Test
    fun `a card survives the cache round trip without its signed image url`() {
        val original = GoodPostCodec.ad(
            textAd(mapOf("textColor" to "#FFD166", "imageFit" to "contain"))
        )!!
        val restored = GoodPostCodec.decodeAds(GoodPostCodec.encodeAds(listOf(original)))
        assertEquals(1, restored.size)
        assertEquals(original.id, restored.first().id)
        assertEquals("#FFD166", restored.first().textColor)
        assertEquals("contain", restored.first().imageFit)
        assertEquals("YOUR AD HERE", restored.first().text)
        // The URL is a short-lived capability, so it is deliberately not written.
        assertNull(restored.first().imageUrl)
    }

    @Test
    fun `an empty cache value decodes to nothing rather than throwing`() {
        assertTrue(GoodPostCodec.decodeAds(null).isEmpty())
        assertTrue(GoodPostCodec.decodeAds("").isEmpty())
        assertTrue(GoodPostCodec.decodeAds("not json").isEmpty())
    }
}
