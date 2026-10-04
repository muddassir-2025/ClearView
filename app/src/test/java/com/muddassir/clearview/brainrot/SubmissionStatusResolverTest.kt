package com.muddassir.clearview.brainrot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read of a device's request to the global blocklist.
 *
 * The one rule under test is the correction for a rule that was approved and
 * later removed: the server keeps reporting "approved", so the app compares the
 * status against the live rule set and reads it as "removed" instead. Getting
 * this wrong in either direction is a real bug — "removed reads approved" tells a
 * user something is blocking for everyone when it is not, and "live reads
 * removed" does the reverse.
 */
class SubmissionStatusResolverTest {

    @Test
    fun `an approved request whose rule is still global reads approved`() {
        assertEquals("approved", SubmissionStatusResolver.display("approved", liveGlobally = true))
        assertEquals("approved", SubmissionStatusResolver.effective("approved", liveGlobally = true))
        assertFalse(SubmissionStatusResolver.isRemoved("approved", liveGlobally = true))
    }

    @Test
    fun `an approved request whose rule is gone reads removed`() {
        assertEquals(
            SubmissionStatusResolver.REMOVED,
            SubmissionStatusResolver.display("approved", liveGlobally = false)
        )
        assertTrue(SubmissionStatusResolver.isRemoved("approved", liveGlobally = false))
    }

    @Test
    fun `a removed request counts as never sent, so it can be asked for again`() {
        assertNull(SubmissionStatusResolver.effective("approved", liveGlobally = false))
    }

    @Test
    fun `a pending request is unaffected by the live rule set`() {
        assertEquals("pending", SubmissionStatusResolver.display("pending", liveGlobally = false))
        assertEquals("pending", SubmissionStatusResolver.effective("pending", liveGlobally = false))
        assertFalse(SubmissionStatusResolver.isRemoved("pending", liveGlobally = false))
    }

    @Test
    fun `an under-review request is unaffected by the live rule set`() {
        assertEquals(
            "under_review",
            SubmissionStatusResolver.display("under_review", liveGlobally = false)
        )
        assertEquals(
            "under_review",
            SubmissionStatusResolver.effective("under_review", liveGlobally = false)
        )
    }

    @Test
    fun `a rejected request is never rewritten as removed`() {
        // The live rule set says nothing about a rejection — only an approval can
        // become "removed".
        assertEquals("rejected", SubmissionStatusResolver.display("rejected", liveGlobally = false))
        assertEquals("rejected", SubmissionStatusResolver.effective("rejected", liveGlobally = false))
        assertFalse(SubmissionStatusResolver.isRemoved("rejected", liveGlobally = false))
    }
}
