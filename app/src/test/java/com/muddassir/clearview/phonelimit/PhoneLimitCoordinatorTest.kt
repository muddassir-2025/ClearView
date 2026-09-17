package com.muddassir.clearview.phonelimit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure Phone Limit logic — the countdown formatter
 * ([PhoneLimitCoordinator.format]). The stateful parts (store, service,
 * alarm, lock) are Android-bound and covered by manual/device testing.
 *
 * A duration PARSER used to be tested here too: it read free text like
 * `1h:20m:10s`, and its only caller was the removed home-screen widget. The
 * in-app sheet asks for hours, minutes and seconds as three number fields and
 * does its own arithmetic, so the parser had no sender left and went with it —
 * along with these tests, which would otherwise have pinned the behaviour of a
 * function nothing calls.
 */
class PhoneLimitCoordinatorTest {

    @Test
    fun `format counts down as H MM SS`() {
        assertEquals("1:20:10", PhoneLimitCoordinator.format(4_810_000L))
        assertEquals("45:30", PhoneLimitCoordinator.format(2_730_000L))
        assertEquals("01:30", PhoneLimitCoordinator.format(90_000L))
        assertEquals("00:00", PhoneLimitCoordinator.format(0L))
        assertEquals("00:00", PhoneLimitCoordinator.format(-5L))
        // Sub-second remainder is truncated, never rounded up.
        assertEquals("00:01", PhoneLimitCoordinator.format(1_999L))
    }

    @Test
    fun `format rounds down to the second that has actually elapsed`() {
        // The countdown is shown to a user deciding whether to wait, so it must
        // never claim more time is left than there is. A rounded-up "00:02" here
        // would be the app telling them there is time they do not have.
        assertEquals("00:01", PhoneLimitCoordinator.format(1_000L))
        assertEquals("00:01", PhoneLimitCoordinator.format(1_999L))
        assertEquals("00:02", PhoneLimitCoordinator.format(2_000L))
    }
}
