package com.muddassir.clearview.brainrot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device's anonymous id.
 *
 * The server refuses anything that is not a canonical UUID, so an id this
 * validator accepted but the server rejected would look like a broken feature
 * rather than a corrupt value — the app would keep sending it and keep failing.
 */
class AnonymousIdTest {

    @Test
    fun `a canonical uuid is valid`() {
        assertTrue(AnonymousId.isValid("f47ac10b-58cc-4372-a567-0e02b2c3d479"))
    }

    @Test
    fun `case does not matter`() {
        assertTrue(AnonymousId.isValid("F47AC10B-58CC-4372-A567-0E02B2C3D479"))
    }

    @Test
    fun `a blank or missing value is not valid`() {
        assertFalse(AnonymousId.isValid(null))
        assertFalse(AnonymousId.isValid(""))
        assertFalse(AnonymousId.isValid("   "))
    }

    @Test
    fun `a non-uuid is not valid`() {
        assertFalse(AnonymousId.isValid("not-a-uuid"))
        assertFalse(AnonymousId.isValid("12345"))
        // A plausible-looking but truncated uuid.
        assertFalse(AnonymousId.isValid("f47ac10b-58cc-4372-a567-0e02b2c3d47"))
    }
}
