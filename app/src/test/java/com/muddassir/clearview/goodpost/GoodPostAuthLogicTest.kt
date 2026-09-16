package com.muddassir.clearview.goodpost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure Good Post auth logic.
 *
 * These two functions decide whether the app ever sends a request with a
 * usable number, and whether a user is told something true about why a step
 * failed. Both are cheap to test here and expensive to discover in production.
 */
class GoodPostAuthLogicTest {

    // ── normalizePhoneInput ─────────────────────────────────────────────

    @Test
    fun `accepts an already-canonical E164 number`() {
        assertEquals("+923001234567", normalizePhoneInput("+923001234567"))
    }

    @Test
    fun `strips the separators every country formats with`() {
        assertEquals("+923001234567", normalizePhoneInput("+92 300 123 4567"))
        assertEquals("+923001234567", normalizePhoneInput("+92-300-123-4567"))
        assertEquals("+923001234567", normalizePhoneInput("+92 (300) 123.4567"))
        assertEquals("+923001234567", normalizePhoneInput("  +923001234567  "))
    }

    @Test
    fun `refuses a national number with no country code`() {
        // Guessing a country here would mean texting a stranger.
        assertNull(normalizePhoneInput("03001234567"))
        assertNull(normalizePhoneInput("3001234567"))
    }

    @Test
    fun `refuses a leading zero after the plus`() {
        assertNull(normalizePhoneInput("+03001234567"))
    }

    @Test
    fun `enforces the E164 length bounds`() {
        // JUnit 4 takes the message FIRST — the reverse of JUnit 5. Getting
        // these the wrong way round still compiles (both are Objects) and
        // silently asserts nothing of value.
        assertNull("6 digits is below the minimum", normalizePhoneInput("+123456"))
        assertEquals("7 digits is the minimum", "+1234567", normalizePhoneInput("+1234567"))
        assertEquals(
            "15 digits is the maximum",
            "+123456789012345",
            normalizePhoneInput("+123456789012345")
        )
        assertNull("16 digits is above the maximum", normalizePhoneInput("+1234567890123456"))
    }

    @Test
    fun `refuses empty and plus-only input`() {
        assertNull(normalizePhoneInput(""))
        assertNull(normalizePhoneInput("   "))
        assertNull(normalizePhoneInput("+"))
        assertNull(normalizePhoneInput("+abc"))
    }

    // ── goodPostErrorFor ────────────────────────────────────────────────

    @Test
    fun `maps the codes the backend actually returns`() {
        assertEquals(GoodPostError.InvalidPhone, goodPostErrorFor("invalid_phone"))
        assertEquals(GoodPostError.PhoneBanned, goodPostErrorFor("phone_banned"))
        assertEquals(GoodPostError.AccountBanned, goodPostErrorFor("account_banned"))
        assertEquals(GoodPostError.AccountSuspended, goodPostErrorFor("account_suspended"))
        assertEquals(GoodPostError.RateLimited, goodPostErrorFor("otp_rate_limited"))
        assertEquals(GoodPostError.OtpLocked, goodPostErrorFor("otp_locked"))
        assertEquals(GoodPostError.InvalidCode, goodPostErrorFor("invalid_code"))
        assertEquals(GoodPostError.NumberRejected, goodPostErrorFor("invalid_phone_number"))
        assertEquals(
            GoodPostError.VerificationUnavailable,
            goodPostErrorFor("verification_unavailable")
        )
        assertEquals(GoodPostError.SmsQuota, goodPostErrorFor("sms_quota_exceeded"))
        assertEquals(GoodPostError.EmailTaken, goodPostErrorFor("email_already_registered"))
        assertEquals(GoodPostError.PhoneTaken, goodPostErrorFor("phone_already_registered"))
        assertEquals(GoodPostError.Unreachable, goodPostErrorFor("unreachable"))
    }

    @Test
    fun `keeps a rejected number distinct from a wrong code`() {
        // The regression this guards: Firebase raises one exception class both
        // for a malformed number and for a wrong code, so the two are told
        // apart by the phase, not the exception. Collapsing them sends a user
        // with a bad country code to look for an SMS that was never sent — the
        // exact wording bug found on a real device with +9999999999.
        assertNotEquals(
            goodPostErrorFor("invalid_phone_number"),
            goodPostErrorFor("invalid_code")
        )
        assertNotEquals(
            goodPostErrorFor("invalid_phone_number"),
            goodPostErrorFor("invalid_phone")
        )
    }

    @Test
    fun `does not tell a user to retry a number the project cannot reach`() {
        // Firebase 17006 means the project's SMS region policy does not cover
        // the number's region — the real-device failure with an Indian number.
        // Retrying cannot fix it and the number is not wrong, so it must read
        // differently from both a bad number and a generic failure. Reporting it
        // as either sends the user to re-type a number that was always correct.
        assertNotEquals(
            GoodPostError.VerificationUnavailable,
            goodPostErrorFor("invalid_phone_number")
        )
        assertNotEquals(
            GoodPostError.VerificationUnavailable,
            goodPostErrorFor("verification_failed")
        )
    }

    @Test
    fun `treats a challenge failure as needing another verification`() {
        // `otp_required` means the server has no live challenge, so the only
        // useful instruction is to start the verification again.
        assertEquals(GoodPostError.VerificationFailed, goodPostErrorFor("otp_required"))
        assertEquals(GoodPostError.VerificationFailed, goodPostErrorFor("invalid_id_token"))
    }

    @Test
    fun `never silently succeeds on an unknown code`() {
        // A code this client does not know means the contract moved. Landing on
        // Unknown keeps that visible instead of inventing a meaning.
        assertEquals(GoodPostError.Unknown, goodPostErrorFor("some_future_code"))
        assertEquals(GoodPostError.Unknown, goodPostErrorFor(""))
    }

    @Test
    fun `maps a transport failure and an unconfigured build differently`() {
        // These need different wording: one is retryable, the other needs a
        // different APK.
        assertEquals(GoodPostError.Unreachable, goodPostErrorFor("unreachable"))
        assertEquals(GoodPostError.NotConfigured, goodPostErrorFor("not_configured"))
    }
}
