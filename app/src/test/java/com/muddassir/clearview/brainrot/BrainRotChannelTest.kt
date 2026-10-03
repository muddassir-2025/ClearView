package com.muddassir.clearview.brainrot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Blocked channels are stored by @handle, so the normalization is what makes
 * "@Foo", "foo" and " @FOO " one block rather than three — and what keeps a
 * pasted URL or a bare "@" out of the list entirely.
 */
class BrainRotChannelTest {

    @Test
    fun `a handle is normalized to one canonical spelling`() {
        listOf("@ExampleChannel", "examplechannel", "  @EXAMPLECHANNEL  ", "@examplechannel")
            .forEach { input ->
                assertEquals(input, "@examplechannel", BrainRotRepository.normalizeHandle(input))
            }
    }

    @Test
    fun `dots dashes and underscores are allowed in a handle`() {
        assertEquals("@a.b-c_d", BrainRotRepository.normalizeHandle("@a.b-c_d"))
    }

    @Test
    fun `an empty or punctuation-only handle is refused`() {
        listOf(null, "", "   ", "@", " @ ", "-", "@-").forEach { input ->
            assertNull("input=$input", BrainRotRepository.normalizeHandle(input))
        }
    }

    @Test
    fun `a pasted url is not a handle`() {
        assertNull(BrainRotRepository.normalizeHandle("https://youtube.com/@foo"))
        assertNull(BrainRotRepository.normalizeHandle("@foo bar"))
    }

    @Test
    fun `a single character handle is too short to be real`() {
        assertNull(BrainRotRepository.normalizeHandle("@a"))
    }
}
