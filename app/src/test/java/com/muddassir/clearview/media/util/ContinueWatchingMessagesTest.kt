package com.muddassir.clearview.media.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingMessagesTest {

    @Test
    fun `reports how many resume positions were cleared`() {
        assertEquals("Cleared 1 resume position", continueWatchingResetMessage(1))
        assertEquals("Cleared 4 resume positions", continueWatchingResetMessage(4))
    }

    @Test
    fun `says so when there was nothing to reset`() {
        // 0 is what the store returns for an empty Continue Watching row; the
        // message must not claim it cleared anything.
        assertEquals("Continue Watching was already empty", continueWatchingResetMessage(0))
        // Defensive: a negative count can only mean "nothing was there".
        assertEquals("Continue Watching was already empty", continueWatchingResetMessage(-1))
    }
}
