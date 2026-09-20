package com.muddassir.clearview.media.worker

import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.SavedChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [isNotificationEligible] — the worker's rule for which
 * videos may generate a notification. Locks in the baseline semantics: an
 * already-notified video never re-notifies, and a video published before the
 * channel was subscribed never notifies (even when the add-time baseline
 * fetch failed), while legacy channels (addedAt == 0) admit everything.
 */
class NotificationEligibilityTest {

    private val notified = setOf("seen1", "seen2")

    @Test
    fun `already notified video is never eligible again`() {
        assertFalse(
            isNotificationEligible(
                videoId = "seen1",
                publishedAtEpochMillis = 5_000L,
                addedAtEpochMillis = 1_000L,
                alreadyNotified = notified
            )
        )
    }

    @Test
    fun `video published before subscription is not eligible`() {
        // The add-time baseline may have failed — the timestamp guard must
        // still keep the channel's pre-existing backlog silent.
        assertFalse(
            isNotificationEligible(
                videoId = "old1",
                publishedAtEpochMillis = 500L,
                addedAtEpochMillis = 1_000L,
                alreadyNotified = emptySet()
            )
        )
    }

    @Test
    fun `video published after subscription is eligible`() {
        assertTrue(
            isNotificationEligible(
                videoId = "new1",
                publishedAtEpochMillis = 2_000L,
                addedAtEpochMillis = 1_000L,
                alreadyNotified = emptySet()
            )
        )
    }

    @Test
    fun `legacy channel with zero addedAt admits everything`() {
        // Channels saved before the subscription-timestamp feature existed.
        assertTrue(
            isNotificationEligible("any", 0L, 0L, emptySet())
        )
        assertTrue(
            isNotificationEligible("any", 123L, 0L, emptySet())
        )
    }

    @Test
    fun `newly notified video is immediately excluded on the next cycle`() {
        // Simulates the same video appearing in a later polling cycle after it
        // was notified: the id is now in the set, so it can't re-notify.
        assertFalse(
            isNotificationEligible("new1", 2_000L, 1_000L, setOf("new1"))
        )
    }

    // ── Per-channel muting ──────────────────────────────────────────

    private fun channel(
        id: String,
        platform: MediaPlatform,
        muted: Boolean = false
    ) = SavedChannel(
        channelId = id,
        displayName = id,
        sourceRef = id,
        platform = platform,
        notificationsMuted = muted
    )

    @Test
    fun `a muted channel can never be notified, on any source`() {
        val ids = notificationChannelIds(
            listOf(
                channel("UC1", MediaPlatform.YOUTUBE),
                channel("ig_nasa", MediaPlatform.INSTAGRAM, muted = true),
                channel("x_openai", MediaPlatform.X, muted = true),
                channel("UC2", MediaPlatform.YOUTUBE, muted = true),
                channel("x_github", MediaPlatform.X)
            )
        )
        assertEquals(setOf("UC1", "x_github"), ids)
    }

    @Test
    fun `unmuting a channel restores it`() {
        val muted = listOf(channel("x_openai", MediaPlatform.X, muted = true))
        assertTrue(notificationChannelIds(muted).isEmpty())

        val unmuted = listOf(channel("x_openai", MediaPlatform.X, muted = false))
        assertEquals(setOf("x_openai"), notificationChannelIds(unmuted))
    }

    /** Channels saved by older builds carry no flag — they must stay ON. */
    @Test
    fun `a channel with no muting flag notifies`() {
        val legacy = SavedChannel(
            channelId = "UC9",
            displayName = "Legacy",
            sourceRef = "@legacy"
        )
        assertFalse(legacy.notificationsMuted)
        assertEquals(setOf("UC9"), notificationChannelIds(listOf(legacy)))
    }
}
