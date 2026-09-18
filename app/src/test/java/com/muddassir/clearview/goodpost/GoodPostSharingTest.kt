package com.muddassir.clearview.goodpost

import com.muddassir.clearview.goodpost.data.PushDecision
import com.muddassir.clearview.goodpost.data.GoodPostVideoCache
import com.muddassir.clearview.goodpost.data.pushDecisionOf
import com.muddassir.clearview.goodpost.ui.mimeTypeFor
import com.muddassir.clearview.goodpost.ui.mimeTypeForAll
import org.junit.Assert.assertEquals
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
