package com.muddassir.clearview.goodpost.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a refused renewal ends an administrator's session (§19).
 *
 * This rule was wrong, and the consequence was reported from the app as
 * "Your session ended. Sign in again." while an administrator was part way
 * through creating a channel — after which the stored session was gone and the
 * password had to be typed again. The cause was not in this function but around
 * it: every failed renewal, including one that never reached a server, was
 * treated as the server refusing the credential.
 *
 * Only an answer ABOUT THE CREDENTIAL may end a session, so only these two
 * statuses qualify. Everything else leaves it alone — a 5xx is the server's
 * problem with itself, and no answer at all is not an answer.
 */
class GoodPostSessionTest {

    @Test
    fun `only a refusal of the credential itself ends the session`() {
        assertTrue(renewalEndsSession(401))
        assertTrue(renewalEndsSession(403))
    }

    @Test
    fun `a server fault does not end the session, because it says nothing about the token`() {
        assertFalse(renewalEndsSession(500))
        assertFalse(renewalEndsSession(502))
        assertFalse(renewalEndsSession(503))
        assertFalse(renewalEndsSession(504))
    }

    @Test
    fun `a successful or unexpected status never ends a session`() {
        // 200 cannot reach this function in practice; asserting it anyway pins
        // the rule as "deny by default" rather than "end unless it looks fine".
        assertFalse(renewalEndsSession(200))
        assertFalse(renewalEndsSession(400))
        assertFalse(renewalEndsSession(404))
        assertFalse(renewalEndsSession(429))
    }
}
