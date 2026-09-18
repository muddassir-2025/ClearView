package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.InstagramStreamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Instagram embed pages carry the post's direct mp4 in several different
 * shapes depending on the page variant and the post type. Every shape must
 * resolve — this is what lets the player stream the video natively instead of
 * falling back to the (black-screen) embedded WebView.
 */
class InstagramStreamResolverTest {

    private val mp4 = "https://scontent.cdninstagram.com/o1/v/t16/f1/m86/reel.mp4"

    @Test
    fun `reads the video_url json field, unescaping slashes and ampersands`() {
        val html = """
            <script>
              window.__data = {"video_url":"https:\/\/scontent.cdninstagram.com\/o1\/v\/t16\/f1\/m86\/reel.mp4\u0026_nc_ht=ig","x":1};
            </script>
        """.trimIndent()
        assertEquals(mp4 + "&_nc_ht=ig", InstagramStreamResolver.findStreamInPayload(html))
    }

    @Test
    fun `reads the contentUrl json-ld field`() {
        val html = """{"@type":"VideoObject","contentUrl":"${mp4}?efg=1"}"""
        assertEquals("$mp4?efg=1", InstagramStreamResolver.findStreamInPayload(html))
    }

    @Test
    fun `reads an og video meta tag`() {
        val html = """<meta property="og:video" content="$mp4">"""
        assertEquals(mp4, InstagramStreamResolver.findStreamInPayload(html))
    }

    @Test
    fun `reads a video_versions style url field`() {
        val html = """{"video_versions":[{"type":101,"url":"${mp4}"}]}"""
        assertEquals(mp4, InstagramStreamResolver.findStreamInPayload(html))
    }

    @Test
    fun `reads a plain video or source tag`() {
        assertEquals(
            mp4,
            InstagramStreamResolver.findStreamInPayload("<video class=\"x\" src=\"$mp4\" controls></video>")
        )
        assertEquals(
            mp4,
            InstagramStreamResolver.findStreamInPayload("<video><source src='$mp4' type='video/mp4'></video>")
        )
    }

    @Test
    fun `falls back to a bare mp4 link`() {
        val html = """<div data-src="https:\/\/scontent.cdninstagram.com\/v\/bare.mp4"></div>"""
        assertEquals(
            "https://scontent.cdninstagram.com/v/bare.mp4",
            InstagramStreamResolver.findStreamInPayload(html)
        )
    }

    @Test
    fun `an image-only payload never resolves to a stream`() {
        val html = """
            <meta property="og:image" content="https://scontent.cdninstagram.com/photo.jpg">
            <img src="https://scontent.cdninstagram.com/photo2.jpg">
        """.trimIndent()
        assertNull(InstagramStreamResolver.findStreamInPayload(html))
    }

    @Test
    fun `a blob source is rejected so the bare-link fallback is not fooled`() {
        assertNull(InstagramStreamResolver.findStreamInPayload("<video src=\"blob:https://www.instagram.com/x\"></video>"))
        assertNull(InstagramStreamResolver.findStreamInPayload(""))
    }

    @Test
    fun `shortcode extraction handles every permalink shape and a bare shortcode`() {
        assertEquals(
            "CxYz123",
            InstagramStreamResolver.extractShortcode("https://www.instagram.com/reel/CxYz123/?igsh=abc")
        )
        assertEquals(
            "CxYz123",
            InstagramStreamResolver.extractShortcode("https://www.instagram.com/p/CxYz123/")
        )
        assertEquals(
            "CxYz123",
            InstagramStreamResolver.extractShortcode("https://instagr.am/p/CxYz123/")
        )
        // A stored id already carries the `ig_` prefix — the shortcode drops it.
        assertEquals("CxYz123", InstagramStreamResolver.extractShortcode("ig_CxYz123"))
    }

    // ── The remembered resolution (§24) ─────────────────────────────────

    @Test
    fun `a URL's own expiry is read from its oe parameter`() {
        // Meta states the expiry in the query string as hex seconds, which is the
        // only exact signal there is about when a signed URL stops working.
        assertEquals(
            0x68CD1F00L * 1000L,
            InstagramStreamResolver.expiresAtMillis(
                "https://scontent.cdninstagram.com/v/t16/reel.mp4?oe=68CD1F00&oh=abc"
            )
        )
        // No `oe` at all is not an error: the plain age limit decides instead.
        assertNull(InstagramStreamResolver.expiresAtMillis("https://example.com/reel.mp4"))
        assertNull(
            InstagramStreamResolver.expiresAtMillis("https://example.com/reel.mp4?oe=zzz")
        )
    }

    @Test
    fun `a remembered stream is reused only while it is still good`() {
        val now = 1_700_000_000_000L
        val farFuture = 1_800_000_000_000L
        val url = "https://cdn.example/reel.mp4?oe=${(farFuture / 1000L).toString(16)}"

        // Fresh, and signed well past now: the answer that makes the second open
        // of a Reel instant.
        assertTrue(InstagramStreamResolver.isStreamUsable(url, now, now))

        // The URL's own expiry has passed (or is about to), so it must not be
        // handed to a player: it would buffer and then fail, which is worse than
        // resolving again.
        val expired = "https://cdn.example/reel.mp4?oe=${((now / 1000L) - 60L).toString(16)}"
        assertFalse(InstagramStreamResolver.isStreamUsable(expired, now, now))

        // No expiry stated: age decides, and past the limit the resolution is
        // retaken rather than trusted forever.
        val undated = "https://cdn.example/reel.mp4"
        assertTrue(InstagramStreamResolver.isStreamUsable(undated, now, now))
        assertFalse(
            InstagramStreamResolver.isStreamUsable(undated, now, now + 7 * 60 * 60 * 1000L)
        )

        // A blank URL is never usable, whatever the clocks say.
        assertFalse(InstagramStreamResolver.isStreamUsable("", now, now))
    }

    // ── The other provider's spelling (listen mode) ─────────────────────

    @Test
    fun `a YouTube stream URL's expiry is read from its decimal expire parameter`() {
        // Google states it in DECIMAL seconds on the googlevideo URLs listen mode
        // plays — a different spelling AND a different base from Meta's `oe`, and
        // reading one as the other is how the audio cache hands the player a URL
        // that has already lapsed.
        assertEquals(
            1_758_300_000_000L,
            InstagramStreamResolver.expiresAtMillis(
                "https://r1.googlevideo.com/videoplayback?expire=1758300000&itag=140&sig=abc"
            )
        )
        // Decimal, not hex: the same digits read as hex would be ~4.3e9 seconds,
        // which is a century in the future and would keep a dead URL alive.
        assertEquals(
            1_000_000_000_000L,
            InstagramStreamResolver.expiresAtMillis("https://x/videoplayback?expire=1000000000")
        )
    }

    @Test
    fun `Meta's own expiry wins when a URL somehow carries both`() {
        assertEquals(
            0x68CD1F00L * 1000L,
            InstagramStreamResolver.expiresAtMillis(
                "https://x/reel.mp4?oe=68CD1F00&expire=1758300000"
            )
        )
    }

    @Test
    fun `a stale YouTube stream is not reused`() {
        val now = 1_700_000_000_000L
        val fresh = "https://r1.googlevideo.com/videoplayback?expire=${(now / 1000L) + 3600L}"
        val stale = "https://r1.googlevideo.com/videoplayback?expire=${(now / 1000L) - 60L}"
        assertTrue(InstagramStreamResolver.isStreamUsable(fresh, now, now))
        assertFalse(InstagramStreamResolver.isStreamUsable(stale, now, now))
    }
}
