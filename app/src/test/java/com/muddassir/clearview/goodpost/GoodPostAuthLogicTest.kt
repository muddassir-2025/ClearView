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

    // ── normalizeEmailInput ─────────────────────────────────────────────

    @Test
    fun `accepts and canonicalises an email address`() {
        assertEquals("ayesha@example.com", normalizeEmailInput("ayesha@example.com"))
        assertEquals("ayesha@example.com", normalizeEmailInput("  Ayesha@Example.COM  "))
        // Addresses the SERVER accepts must never be rejected here. A stricter
        // client check is the one bug this function can have: it blocks input
        // the backend would have taken.
        assertEquals("first.last+tag@sub.domain.co.uk", normalizeEmailInput("first.last+tag@sub.domain.co.uk"))
        assertEquals("a@b.co", normalizeEmailInput("a@b.co"))
    }

    @Test
    fun `refuses input that is not an address`() {
        assertNull(normalizeEmailInput(""))
        assertNull(normalizeEmailInput("   "))
        assertNull(normalizeEmailInput("ayesha"))
        assertNull(normalizeEmailInput("ayesha@"))
        assertNull(normalizeEmailInput("@example.com"))
        // No dot in the domain: the server's rule requires one, so this is a
        // request that would be refused anyway — better said now.
        assertNull(normalizeEmailInput("ayesha@example"))
        assertNull(normalizeEmailInput("two addresses@example.com a@b.co"))
        assertNull(normalizeEmailInput("a b@example.com"))
    }

    @Test
    fun `refuses an address longer than the column allows`() {
        val local = "a".repeat(250)
        assertNull(normalizeEmailInput("$local@example.com"))
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
        // `otp_required` means the server has no LIVE challenge: it expired, or
        // it was already spent. Re-typing the same digits can never work, so the
        // instruction has to be "ask for a new code" — a different sentence from
        // "we could not confirm that number", which is what it used to say.
        assertEquals(GoodPostError.VerificationExpired, goodPostErrorFor("otp_required"))
        // Firebase reports its own expired verification session separately; it
        // must land on the same instruction, not a generic one.
        assertEquals(GoodPostError.VerificationExpired, goodPostErrorFor("verification_expired"))

        // Distinct from the three things it was previously flattened into. The
        // wording is what decides which control the user reaches for.
        assertNotEquals(GoodPostError.InvalidCode, goodPostErrorFor("otp_required"))
        assertNotEquals(GoodPostError.NumberRejected, goodPostErrorFor("otp_required"))
        assertNotEquals(GoodPostError.VerificationFailed, goodPostErrorFor("otp_required"))

        // A token the server could not verify is NOT an expired challenge: the
        // remedy there is to retry, not to re-verify the number.
        assertEquals(GoodPostError.VerificationFailed, goodPostErrorFor("invalid_id_token"))
    }

    @Test
    fun `keeps the two sign-in methods' failures distinct`() {
        // Email sign-in is sign-in only (§19): the address opens an account and
        // can never create one. "No account uses that email" therefore has to
        // point at the mobile flow rather than read as a generic refusal, or the
        // user retries the same address forever.
        assertEquals(GoodPostError.EmailNotRegistered, goodPostErrorFor("email_not_registered"))
        assertNotEquals(GoodPostError.VerificationFailed, goodPostErrorFor("email_not_registered"))
        assertNotEquals(GoodPostError.InvalidCode, goodPostErrorFor("email_not_registered"))

        assertEquals(GoodPostError.InvalidEmail, goodPostErrorFor("invalid_email"))

        // Being unable to send mail is not the same as being offline: retrying
        // identically will not help, and the user has a working alternative.
        assertEquals(GoodPostError.EmailUnavailable, goodPostErrorFor("email_unavailable"))
        assertNotEquals(GoodPostError.Unreachable, goodPostErrorFor("email_unavailable"))
    }

    @Test
    fun `does not blame the user for a capability the server does not have`() {
        // `auth_unavailable` is now what the backend returns when Firebase
        // Admin cannot even be initialised — a deployment or credential
        // problem. It must stay in the server-error family: wording it as an
        // unverifiable number sends the user through the same loop forever,
        // and that is exactly how a broken service account stays unnoticed.
        assertEquals(GoodPostError.ServerError, goodPostErrorFor("auth_unavailable"))
        assertNotEquals(GoodPostError.VerificationFailed, goodPostErrorFor("auth_unavailable"))
        assertNotEquals(GoodPostError.NumberRejected, goodPostErrorFor("auth_unavailable"))
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
