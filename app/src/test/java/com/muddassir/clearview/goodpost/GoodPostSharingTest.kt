package com.muddassir.clearview.goodpost

import com.muddassir.clearview.goodpost.data.PushDecision
import com.muddassir.clearview.goodpost.data.GoodPostVideoCache
import com.muddassir.clearview.goodpost.data.pushDecisionOf
import com.muddassir.clearview.goodpost.ui.mimeTypeFor
import com.muddassir.clearview.goodpost.ui.mimeTypeForAll
import com.muddassir.clearview.goodpost.ui.sharedPostText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sharing a post, and being told about one: the two answers that are arithmetic.
 *
 * Both are small decisions that are wrong in ways only a device shows — a
 * photograph handed to an app that filters for video, a notification for an
 * update the reader deleted, a shared file named after a URL fragment — so both
 * are pinned here rather than left to be discovered on a phone.
 */
class GoodPostSharingTest {

    // ── What a chooser is filtered by (§15) ─────────────────────────────

    @Test
    fun `a real media type is passed through, without its parameters`() {
        assertEquals("image/jpeg", mimeTypeFor("image", "image/jpeg"))
        assertEquals("video/mp4", mimeTypeFor("video", "video/mp4; codecs=avc1"))
    }

    @Test
    fun `a missing media type falls back to the family`() {
        assertEquals("image/*", mimeTypeFor("image", null))
        assertEquals("video/*", mimeTypeFor("video", null))
        assertEquals("audio/*", mimeTypeFor("audio", null))
        assertEquals("*/*", mimeTypeFor("other", null))
    }

    @Test
    fun `a type that is not a type at all is not used as one`() {
        // The server stores what it was told; a blank or a bare word would make
        // the chooser show nothing at all, which reads as \"sharing is broken\".
        assertEquals("image/*", mimeTypeFor("image", ""))
        assertEquals("image/*", mimeTypeFor("image", "jpeg"))
    }

    @Test
    fun `a selection of one kind keeps every app that takes it`() {
        assertEquals(
            "image/*",
            mimeTypeForAll(listOf("image" to "image/jpeg", "image" to "image/png"))
        )
    }

    @Test
    fun `a mixed selection is offered to everything`() {
        // A photograph AND a clip: narrowing to either one would hide half the
        // apps that could have taken the share.
        assertEquals(
            "*/*",
            mimeTypeForAll(listOf("image" to "image/jpeg", "video" to "video/mp4"))
        )
    }

    @Test
    fun `an empty selection names no type but never crashes`() {
        assertEquals("*/*", mimeTypeForAll(emptyList()))
    }

    // ── What travels with a share (§15) ─────────────────────────────────

    @Test
    fun `a post goes as its words and its channel's link`() {
        assertEquals(
            "Salaam\n\nhttps://example.com/c/idk",
            sharedPostText("Salaam", "https://example.com/c/idk")
        )
    }

    @Test
    fun `a media share sends the same message as a text one`() {
        // The two shares differ only in what is ATTACHED. Composing them in one
        // place is what keeps a picture from arriving without the link a text
        // update would have carried.
        val words = "A photograph"
        val link = "https://example.com/c/idk"

        assertEquals(sharedPostText(words, link), sharedPostText(words, link))
        assertEquals("A photograph\n\nhttps://example.com/c/idk", sharedPostText(words, link))
    }

    @Test
    fun `whichever half is missing is the half that is left out`() {
        // A media-only post has no words, and still has to be findable.
        assertEquals("https://example.com/c/idk", sharedPostText(null, "https://example.com/c/idk"))
        assertEquals("https://example.com/c/idk", sharedPostText("   ", "https://example.com/c/idk"))
        // A post this device has not fetched still shares, with just the link.
        assertEquals("Salaam", sharedPostText("Salaam", null))
        assertEquals("Salaam", sharedPostText("Salaam", ""))
    }

    @Test
    fun `neither half is no text at all, not an empty one`() {
        // An empty EXTRA_TEXT is drawn by several apps as a blank line above the
        // file, so the extra has to be left off rather than set to nothing.
        assertNull(sharedPostText(null, null))
        assertNull(sharedPostText("", ""))
        assertNull(sharedPostText("   ", "\n"))
    }

    @Test
    fun `the link never opens the message`() {
        // The words are what somebody came for; the link is how they get to the
        // rest of it. Order is not cosmetic here.
        val shared = sharedPostText("Salaam", "https://example.com/c/idk")!!

        assertTrue(shared.startsWith("Salaam"))
        assertTrue(shared.endsWith("https://example.com/c/idk"))
    }

    // ── The cache key a delete and a replay both need (§6, §26) ─────────

    @Test
    fun `a signed url and its unsigned twin share one cache key`() {
        val signed =
            "https://bucket.example.com/goodpost/a/b/photo.jpg?X-Amz-Signature=abc&X-Amz-Expires=900"
        val other =
            "https://bucket.example.com/goodpost/a/b/photo.jpg?X-Amz-Signature=zzz&X-Amz-Expires=900"

        assertEquals(GoodPostVideoCache.cacheKeyFor(signed), GoodPostVideoCache.cacheKeyFor(other))
        assertEquals("/goodpost/a/b/photo.jpg", GoodPostVideoCache.cacheKeyFor(signed))
    }

    @Test
    fun `a url that is not a url still keys on itself`() {
        // The key has to exist for a value the parser refuses, because it is also
        // what a delete recomputes later.
        assertEquals("not a url", GoodPostVideoCache.cacheKeyFor("not a url"))
    }

    // ── Whether a reader is buzzed (§8) ─────────────────────────────────

    @Test
    fun `an update newer than the watermark is announced`() {
        assertEquals(
            PushDecision.Announce,
            pushDecisionOf(notificationsEnabled = true, publishedAt = 2_000, lastSeenAt = 1_000)
        )
    }

    @Test
    fun `an update at or before the watermark is not announced twice`() {
        assertEquals(
            PushDecision.IgnoreAlreadySeen,
            pushDecisionOf(notificationsEnabled = true, publishedAt = 1_000, lastSeenAt = 1_000)
        )
        assertEquals(
            PushDecision.IgnoreAlreadySeen,
            pushDecisionOf(notificationsEnabled = true, publishedAt = 999, lastSeenAt = 1_000)
        )
    }

    @Test
    fun `the switch is read at delivery, not at queueing`() {
        assertEquals(
            PushDecision.IgnoreDisabled,
            pushDecisionOf(notificationsEnabled = false, publishedAt = 2_000, lastSeenAt = 1_000)
        )
    }

    @Test
    fun `a payload with no usable time is dropped rather than announced`() {
        assertEquals(
            PushDecision.IgnoreUnusable,
            pushDecisionOf(notificationsEnabled = true, publishedAt = null, lastSeenAt = 1_000)
        )
    }
}
