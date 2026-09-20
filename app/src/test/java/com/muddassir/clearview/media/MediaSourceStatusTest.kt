package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.MediaSourceState
import com.muddassir.clearview.media.data.MediaSourceStatus
import com.muddassir.clearview.media.data.MediaSourceStatusStore
import com.muddassir.clearview.media.data.displayName
import com.muddassir.clearview.media.model.MediaPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaSourceStatusTest {

    private val now = System.currentTimeMillis()

    @Before
    fun reset() {
        MediaSourceStatusStore.clear()
    }

    @Test
    fun showsAThrottleWithItsRemainingWait() {
        MediaSourceStatusStore.markThrottled(MediaPlatform.X, now + 8 * 60_000L)

        val issue = MediaSourceStatusStore.issues(now).single()
        assertEquals(MediaPlatform.X, issue.platform)
        assertEquals(MediaSourceState.THROTTLED, issue.state)
        assertEquals("X is rate-limiting this device — retrying in 8m", MediaSourceStatusStore.describe(issue, now))
    }

    @Test
    fun aSuccessClearsTheWarning() {
        MediaSourceStatusStore.markThrottled(MediaPlatform.INSTAGRAM, now + 60_000L)
        assertTrue(MediaSourceStatusStore.issues(now).isNotEmpty())

        MediaSourceStatusStore.markOk(MediaPlatform.INSTAGRAM)
        assertTrue(MediaSourceStatusStore.issues(now).isEmpty())
    }

    @Test
    fun reportsAPlainFailure() {
        MediaSourceStatusStore.markFailing(MediaPlatform.YOUTUBE)

        val issue = MediaSourceStatusStore.issues(now).single()
        assertEquals(MediaSourceState.FAILING, issue.state)
        assertEquals(
            "YouTube isn't responding — pull to refresh to retry",
            MediaSourceStatusStore.describe(issue, now)
        )
    }

    /** A rate limit is actionable and time-bounded — "it failed" must not replace it. */
    @Test
    fun aLiveThrottleOutranksAFailure() {
        MediaSourceStatusStore.markThrottled(MediaPlatform.X, now + 5 * 60_000L)
        MediaSourceStatusStore.markFailing(MediaPlatform.X)

        val issue = MediaSourceStatusStore.issues(now).single()
        assertEquals(MediaSourceState.THROTTLED, issue.state)
        assertEquals(now + 5 * 60_000L, issue.retryAtEpochMillis)
    }

    @Test
    fun keepsTheLongerThrottleWindow() {
        MediaSourceStatusStore.markThrottled(MediaPlatform.X, now + 5 * 60_000L)
        MediaSourceStatusStore.markThrottled(MediaPlatform.X, now + 60_000L)

        assertEquals(now + 5 * 60_000L, MediaSourceStatusStore.issues(now).single().retryAtEpochMillis)
    }

    @Test
    fun anElapsedThrottleCountsDownToNow() {
        val status = MediaSourceStatus(
            platform = MediaPlatform.X,
            state = MediaSourceState.THROTTLED,
            detail = "",
            sinceEpochMillis = now,
            retryAtEpochMillis = now
        )
        assertEquals("X is rate-limiting this device — retrying now", MediaSourceStatusStore.describe(status, now))
    }

    /** A blip must not leave a permanent warning on the header. */
    @Test
    fun staleFailuresStopBeingReported() {
        MediaSourceStatusStore.markFailing(MediaPlatform.YOUTUBE)
        assertTrue(MediaSourceStatusStore.issues(now).isNotEmpty())

        val muchLater = now + 31 * 60_000L
        assertTrue(MediaSourceStatusStore.issues(muchLater).isEmpty())
    }

    @Test
    fun throttlesSortBeforeFailures() {
        MediaSourceStatusStore.markFailing(MediaPlatform.YOUTUBE)
        MediaSourceStatusStore.markThrottled(MediaPlatform.X, now + 60_000L)

        assertEquals(
            listOf(MediaPlatform.X, MediaPlatform.YOUTUBE),
            MediaSourceStatusStore.issues(now).map { it.platform }
        )
    }

    @Test
    fun platformNamesReadAsProviders() {
        assertEquals("YouTube", MediaPlatform.YOUTUBE.displayName())
        assertEquals("Instagram", MediaPlatform.INSTAGRAM.displayName())
        assertEquals("X", MediaPlatform.X.displayName())
    }
}
