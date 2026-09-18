package com.muddassir.clearview.media.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep timer's arithmetic, away from any player.
 *
 * Two things are worth pinning here and both are the sort of mistake that only
 * shows up in a dark room at midnight: a countdown that reads one second short
 * the instant it is set (so it looks like the choice was ignored), and a fade
 * that reaches silence at the wrong moment — either cutting the audio dead or
 * never finishing the job.
 */
class SleepTimerTest {

    private val minute = 60_000L

    @Test
    fun `the choices are ascending, positive and inside an hour`() {
        assertTrue("at least a handful of choices", SleepTimer.MINUTES.size >= 4)
        assertTrue("no non-positive choice", SleepTimer.MINUTES.all { it > 0 })
        assertEquals(
            "choices must be strictly ascending",
            SleepTimer.MINUTES.sorted(),
            SleepTimer.MINUTES
        )
        assertEquals("choices must be distinct", SleepTimer.MINUTES.distinct(), SleepTimer.MINUTES)
        assertTrue("60 minutes must be on offer", SleepTimer.MINUTES.contains(60))
    }

    @Test
    fun `a countdown reads as the time that was chosen, not a second less`() {
        // The bug this pins: a timer set to five minutes holds 299,999 ms a
        // millisecond later, and truncating shows "4:59" immediately.
        assertEquals("5:00", SleepTimer.formatRemaining(5 * minute))
        assertEquals("5:00", SleepTimer.formatRemaining(5 * minute - 1L))
        assertEquals("4:59", SleepTimer.formatRemaining(5 * minute - 1_000L))
        assertEquals("10:00", SleepTimer.formatRemaining(10 * minute))
        assertEquals("15:00", SleepTimer.formatRemaining(15 * minute))
        assertEquals("45:00", SleepTimer.formatRemaining(45 * minute))
    }

    @Test
    fun `the final second still reads as a second`() {
        assertEquals("0:01", SleepTimer.formatRemaining(1L))
        assertEquals("0:01", SleepTimer.formatRemaining(1_000L))
        assertEquals("0:02", SleepTimer.formatRemaining(1_001L))
        assertEquals("0:00", SleepTimer.formatRemaining(0L))
    }

    @Test
    fun `past an hour the label gains an hours field`() {
        assertEquals("1:00:00", SleepTimer.formatRemaining(60 * minute))
        assertEquals("1:02:30", SleepTimer.formatRemaining(62 * minute + 30_000L))
    }

    @Test
    fun `a nonsensical clock never renders as negative time`() {
        // A deadline the ticker overshoots, or a stale value read after a clock
        // adjustment, must read as zero rather than as "-0:03".
        assertEquals("0:00", SleepTimer.formatRemaining(-1L))
        assertEquals("0:00", SleepTimer.formatRemaining(-60_000L))
    }

    @Test
    fun `an absurdly large remaining value does not overflow into nonsense`() {
        // The additive form of ceiling division ((n + 999) / 1000) overflows to a
        // negative total for Long.MAX_VALUE, and renders as "-1:-51".
        val formatted = SleepTimer.formatRemaining(Long.MAX_VALUE)
        assertTrue("must not be negative: $formatted", !formatted.contains('-'))
    }

    @Test
    fun `an unset timer reads as Off`() {
        assertEquals("Off", SleepTimer.labelFor(0L))
        assertEquals("Off", SleepTimer.labelFor(-1L))
        assertEquals("5:00", SleepTimer.labelFor(5 * minute))
    }

    @Test
    fun `volume is untouched until the fade begins`() {
        assertEquals(1f, SleepTimer.fadeVolume(SleepTimer.FADE_MS), 0f)
        assertEquals(1f, SleepTimer.fadeVolume(SleepTimer.FADE_MS * 10), 0f)
        assertEquals(1f, SleepTimer.fadeVolume(Long.MAX_VALUE), 0f)
    }

    @Test
    fun `the fade reaches silence exactly at the deadline`() {
        assertEquals(0.5f, SleepTimer.fadeVolume(SleepTimer.FADE_MS / 2), 0.01f)
        assertEquals(0f, SleepTimer.fadeVolume(0L), 0f)
        // Overshot: still silence, never a negative volume.
        assertEquals(0f, SleepTimer.fadeVolume(-5_000L), 0f)
    }

    @Test
    fun `the fade only ever moves toward silence`() {
        // Monotonic, so the volume cannot swell back up mid-fade.
        val samples = generateSequence(SleepTimer.FADE_MS) { it - 250L }
            .takeWhile { it >= 0L }
            .map { SleepTimer.fadeVolume(it) }
            .toList()
        assertEquals(
            "volume must be non-increasing across the fade",
            samples.sortedDescending(),
            samples
        )
        assertTrue("the fade must actually reach zero", samples.last() == 0f)
    }
}
