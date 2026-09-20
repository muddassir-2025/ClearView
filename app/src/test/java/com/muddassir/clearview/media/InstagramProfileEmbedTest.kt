package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.InstagramProfileEmbedParser
import com.muddassir.clearview.media.model.InstagramMediaType
import com.muddassir.clearview.media.model.MediaPlatform
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile-embed payload is a JSON-encoded STRING nested inside the page's
 * JSON, so these tests cover the unwrapping plus the post mapping.
 */
class InstagramProfileEmbedTest {

    private val expiringCdnUrl =
        "https://instagram.fhyd2-2.fna.fbcdn.net/v/t51.71878-15/796497594_n.jpg?oh=abc123&oe=6AB59F3E"
    private val stableCdnUrl =
        "https://instagram.fhyd2-2.fna.fbcdn.net/v/t51.82787-15/815798049_n.jpg"

    private val contextJson = """
        {"context":{"username":"nasa","full_name":"NASA","profile_pic_url":"https://scontent.cdninstagram.com/v/t51.2885-19/29090066_n.jpg","followers_count":104352634,"graphql_media":[
          {"shortcode_media":{"__typename":"GraphVideo","id":"3990280000289002834","shortcode":"DdgUoTTklVS","is_video":true,"display_url":"$expiringCdnUrl","taken_at_timestamp":1758000000,"edge_media_to_caption":{"edges":[{"node":{"text":"A caption first line\nsecond line"}}]},"edge_liked_by":{"count":42}}},
          {"shortcode_media":{"__typename":"GraphImage","id":"3988961919403244082","shortcode":"Ddbo7s0lzoy","is_video":false,"display_url":"$stableCdnUrl","taken_at_timestamp":1757900000,"edge_liked_by":{"count":7}}},
          {"shortcode_media":{"__typename":"GraphSidecar","id":"3988274761411540133","shortcode":"DdZMsPElzSl","is_video":false,"display_url":"$stableCdnUrl"}}
        ]}}
    """.trimIndent()

    /** The page nests that JSON as a string value, exactly like Instagram does. */
    private fun embedPage(context: String): String =
        """<!DOCTYPE html><html><body><script>{"contextJSON":""" +
            JSONObject.quote(context) +
            ""","request_id":"abc"}</script></body></html>"""

    @Test
    fun parsesProfileAndItsPosts() {
        val result = InstagramProfileEmbedParser.parse(embedPage(contextJson), "nasa")
            ?: error("expected the embed payload to parse")

        assertEquals("nasa", result.username)
        assertEquals("NASA", result.fullName)
        assertTrue(result.avatarUrl!!.contains("cdninstagram.com"))
        assertEquals(3, result.items.size)
    }

    @Test
    fun mapsVideoImageAndCarouselPosts() {
        val items = InstagramProfileEmbedParser.parse(embedPage(contextJson), "nasa")!!.items

        val video = items[0]
        // The card label is the first line; bodyText keeps the WHOLE caption
        // (the post viewer used to stop at the label, hiding the rest).
        assertEquals("A caption first line\nsecond line", video.bodyText)
        assertEquals("ig_DdgUoTTklVS", video.videoId)
        assertEquals("ig_nasa", video.channelId)
        assertEquals("NASA", video.channelName)
        assertEquals(MediaPlatform.INSTAGRAM, video.platform)
        assertEquals(InstagramMediaType.REEL, video.instagramType)
        assertTrue(video.isInstagramVideo)
        assertEquals("A caption first line", video.title)
        assertEquals(1758000000_000L, video.publishedAtEpochMillis)
        assertEquals(42L, video.viewCount)
        assertEquals("https://www.instagram.com/p/DdgUoTTklVS/", video.instagramUrl)

        val image = items[1]
        assertEquals(InstagramMediaType.IMAGE, image.instagramType)
        assertTrue(image.isInstagramImage)

        val carousel = items[2]
        assertEquals(InstagramMediaType.CAROUSEL, carousel.instagramType)
        assertTrue(carousel.isInstagramImage)
        // A post without a timestamp still lands in the feed (newest first),
        // never at epoch 0 — which the date filter would drop.
        assertTrue(carousel.publishedAtEpochMillis > 0L)
    }

    /**
     * The embed hands out CDN URLs signed with a short-lived grant. Those 403
     * after they expire (blank tiles days later), so the item must carry the
     * stable media endpoint instead.
     */
    @Test
    fun replacesExpiringCdnThumbnailsWithTheStableEndpoint() {
        val items = InstagramProfileEmbedParser.parse(embedPage(contextJson), "nasa")!!.items
        assertEquals(
            "https://www.instagram.com/p/DdgUoTTklVS/media/?size=l",
            items[0].thumbnailUrl
        )
        // A non-expiring CDN still is kept as-is (no extra redirect).
        assertEquals(stableCdnUrl, items[1].thumbnailUrl)
    }

    /** A handle Instagram cannot resolve answers with an empty payload. */
    @Test
    fun returnsNullWhenThePageCarriesNoProfile() {
        assertNull(InstagramProfileEmbedParser.parse(embedPage(""), "ghost"))
        assertNull(InstagramProfileEmbedParser.parse("<html><body>nope</body></html>", "ghost"))
    }
}
