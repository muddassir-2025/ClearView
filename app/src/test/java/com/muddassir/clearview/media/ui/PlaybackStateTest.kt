package com.muddassir.clearview.media.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native (Instagram/X) player reports its state to the transport by POLLING
 * — MediaPlayer has no per-frame callback — so the rule deciding "is this news
 * to the UI?" is what keeps the play/pause icon honest.
 *
 * The regression this locks down: the poll used to report only a resume. Pausing
 * an Instagram or X clip therefore stopped the audio but left the bar showing
 * "Pause"; the next tap read the stale flag and sent *another* pause, so the
 * button looked frozen. YouTube never had this because its IFrame API pushes
 * every state change.
 */
class PlaybackStateTest {

    @Test
    fun `a pause is reported, not just a resume`() {
        // Playing, and the player has gone quiet: the UI must be told.
        assertTrue(playbackStateChanged(reported = true, playing = false))
    }

    @Test
    fun `a resume is reported`() {
        assertTrue(playbackStateChanged(reported = false, playing = true))
    }

    @Test
    fun `no news is not reported`() {
        // Silence on every poll tick would spam the parent with state writes.
        assertFalse(playbackStateChanged(reported = true, playing = true))
        assertFalse(playbackStateChanged(reported = false, playing = false))
    }

    @Test
    fun `pause then resume is reported in both directions`() {
        // The exact sequence a reader produces: tap pause, tap play. Each tap
        // must reach the transport, whichever way it goes.
        var reported = true
        assertTrue(playbackStateChanged(reported, playing = false).also { reported = false })
        assertTrue(playbackStateChanged(reported, playing = true))
    }
}
