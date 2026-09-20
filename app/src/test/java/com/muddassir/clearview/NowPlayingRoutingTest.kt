package com.muddassir.clearview

import android.content.Intent
import com.muddassir.clearview.ui.NowPlayingTarget
import com.muddassir.clearview.ui.nowPlayingTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tapping the media card must reopen the player for what is playing, not drop
 * the reader on the tab the app happens to open on.
 *
 * Two separate rules are locked here: what a tap ROUTES to (a downloaded track
 * to the audio player, a listen-mode stream to the video player), and that an
 * intent which is not one of ours is ignored rather than hijacking the launch.
 */
class NowPlayingRoutingTest {

    @Test
    fun `a downloaded track opens the audio player`() {
        assertEquals(
            NowPlayingTarget.DOWNLOADED_AUDIO,
            nowPlayingTarget(isStream = false, hasDownload = true, hasVideo = true)
        )
    }

    @Test
    fun `a listen-mode stream opens the video player`() {
        assertEquals(
            NowPlayingTarget.VIDEO,
            nowPlayingTarget(isStream = true, hasDownload = false, hasVideo = true)
        )
    }

    @Test
    fun `a stream wins over a download of the same id`() {
        // The service says these bytes are the video's audio, so the video is
        // what the reader asked for — even when a download exists too.
        assertEquals(
            NowPlayingTarget.VIDEO,
            nowPlayingTarget(isStream = true, hasDownload = true, hasVideo = true)
        )
    }

    @Test
    fun `a deleted download falls back to the video`() {
        // The audio came from a file that is gone: the video is still the thing
        // that was playing, so show that rather than nothing.
        assertEquals(
            NowPlayingTarget.VIDEO,
            nowPlayingTarget(isStream = false, hasDownload = false, hasVideo = true)
        )
    }

    @Test
    fun `a stream with nothing cached on this device opens nothing`() {
        // Opening an empty player would be worse than leaving the reader where
        // they were — and the audio is still playing either way.
        assertEquals(
            NowPlayingTarget.NONE,
            nowPlayingTarget(isStream = true, hasDownload = false, hasVideo = false)
        )
    }

    @Test
    fun `nothing known opens nothing`() {
        assertEquals(
            NowPlayingTarget.NONE,
            nowPlayingTarget(isStream = false, hasDownload = false, hasVideo = false)
        )
    }

    @Test
    fun `an ordinary launch intent is not a now-playing request`() {
        // Every launch goes through this check, so a false positive would mean
        // the app could never open on its own tab again.
        assertNull(nowPlayingFrom(Intent()))
    }
}
