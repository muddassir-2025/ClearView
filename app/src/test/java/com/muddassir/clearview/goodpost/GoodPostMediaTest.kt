package com.muddassir.clearview.goodpost

import com.muddassir.clearview.goodpost.data.GoodPostDownloads
import com.muddassir.clearview.goodpost.data.isUndrawableMediaType
import com.muddassir.clearview.goodpost.ui.aspectOf
import com.muddassir.clearview.goodpost.ui.clockOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the media viewer that are pure arithmetic.
 *
 * Worth testing because each one is a small answer to a question the server does
 * not answer for us — what a file is called, how long it is, how wide it is — and
 * each is wrong in a way that only shows up on a device: a saved video named
 * `.bin`, a duration reading `0:-1`, an image box that is backwards.
 */
class GoodPostMediaTest {

    // ── File names (§15) ────────────────────────────────────────────────

    @Test
    fun `a media type becomes the extension a person recognises`() {
        assertEquals("jpg", GoodPostDownloads.extensionFor("image", "image/jpeg"))
        assertEquals("png", GoodPostDownloads.extensionFor("image", "image/png"))
        assertEquals("mp4", GoodPostDownloads.extensionFor("video", "video/mp4"))
        assertEquals("mov", GoodPostDownloads.extensionFor("video", "video/quicktime"))
        assertEquals("3gp", GoodPostDownloads.extensionFor("video", "video/3gpp"))
        assertEquals("mp3", GoodPostDownloads.extensionFor("audio", "audio/mpeg"))
        assertEquals("mkv", GoodPostDownloads.extensionFor("video", "video/x-matroska"))
    }

    @Test
    fun `a media type that is not a file suffix falls back to the kind`() {
        // `svg+xml` and a vendor type are subtypes, not extensions: taking them
        // literally would produce `photo.svg+xml` and `sheet.vnd.ms-excel`.
        assertEquals("jpg", GoodPostDownloads.extensionFor("image", "image/svg+xml"))
        assertEquals("mp4", GoodPostDownloads.extensionFor("video", "video/vnd.dlna"))
        assertEquals("mp3", GoodPostDownloads.extensionFor("audio", "audio/x-ms-wma"))
        // `ogg` IS an extension, so parameters are stripped and the subtype is
        // kept — the fallback is for types that are not file suffixes, not for
        // every type the table does not name.
        assertEquals("ogg", GoodPostDownloads.extensionFor("audio", "audio/ogg; codecs=opus"))
    }

    @Test
    fun `a media type with parameters still yields its extension`() {
        assertEquals("mp4", GoodPostDownloads.extensionFor("video", "video/mp4; codecs=avc1"))
    }

    @Test
    fun `a missing media type falls back to the kind rather than to nothing`() {
        assertEquals("jpg", GoodPostDownloads.extensionFor("image", null))
        assertEquals("mp4", GoodPostDownloads.extensionFor("video", null))
        assertEquals("mp3", GoodPostDownloads.extensionFor("audio", null))
        assertEquals("bin", GoodPostDownloads.extensionFor("other", null))
    }

    @Test
    fun `a file name is unique per save and carries the extension`() {
        val first = GoodPostDownloads.fileName("image", "image/jpeg", at = 1_700_000_000_000)
        val second = GoodPostDownloads.fileName("image", "image/jpeg", at = 1_700_000_001_000)

        assertTrue(first.startsWith("GoodPost_"))
        assertTrue(first.endsWith(".jpg"))
        assertNotEquals(first, second)
    }

    // ── Durations and ratios ────────────────────────────────────────────

    @Test
    fun `a position reads as a player reads it`() {
        assertEquals("0:00", clockOf(0))
        assertEquals("0:07", clockOf(7_400))
        assertEquals("1:04", clockOf(64_000))
        assertEquals("1:02:03", clockOf(3_723_000))
    }

    @Test
    fun `a negative or unknown position never prints a minus sign`() {
        // ExoPlayer reports TIME_UNSET as a negative, and a video whose duration
        // is not known yet must not read "-1:-4".
        assertEquals("0:00", clockOf(-1))
        assertEquals("0:00", clockOf(-64_000))
    }

    @Test
    fun `an aspect ratio needs both numbers and both positive`() {
        assertEquals(2f, aspectOf(1000, 500)!!, 0.001f)
        assertEquals(0.5f, aspectOf(500, 1000)!!, 0.001f)
        assertNull(aspectOf(null, 500))
        assertNull(aspectOf(500, null))
        assertNull(aspectOf(0, 500))
        assertNull(aspectOf(500, 0))
    }

    // ── What the image loader will accept (§24) ─────────────────────────

    @Test
    fun `a clip's bytes are refused before they are downloaded`() {
        // The bug this pins: a gallery tile and a channel's media strip asked the
        // IMAGE loader for a video's URL, and the loader reads a response into a
        // ByteArray — so every clip on screen was downloaded in full, failed to
        // decode, and was thrown away, having spent the bandwidth the video the
        // reader was trying to open needed. One header makes it free.
        assertTrue(isUndrawableMediaType("video/mp4"))
        assertTrue(isUndrawableMediaType("video/quicktime"))
        assertTrue(isUndrawableMediaType("video/webm; codecs=vp9"))
        assertTrue(isUndrawableMediaType("AUDIO/MPEG"))
    }

    @Test
    fun `a picture is never refused, whatever the bucket calls it`() {
        // Refusing only the two kinds the decoder cannot draw is what keeps this
        // from becoming the opposite bug: an object served as
        // `application/octet-stream` is a photograph the bucket did not label.
        assertFalse(isUndrawableMediaType("image/jpeg"))
        assertFalse(isUndrawableMediaType("image/png; charset=binary"))
        assertFalse(isUndrawableMediaType("application/octet-stream"))
        assertFalse(isUndrawableMediaType(null))
        assertFalse(isUndrawableMediaType(""))
    }
}
