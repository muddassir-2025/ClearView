package com.muddassir.clearview.goodpost

/**
 * Every way a Good Post auth step can fail, as the user needs to understand it.
 *
 * Backend error codes (`phone_banned`, `otp_rate_limited`, …) are stable and
 * machine-readable, which is exactly why they must not be shown to a person.
 * This enum is the single translation point, kept pure so the mapping is
 * unit-tested rather than discovered in production.
 */
enum class GoodPostError {
    /** The number is not valid E.164. */
    InvalidPhone,

    /** §19: this mobile identity is banned from Good Post. */
    PhoneBanned,

    AccountBanned,
    AccountSuspended,

    /** Too many codes requested for this number this hour. */
    RateLimited,

    /** Too many failed attempts against the current challenge. */
    OtpLocked,

    /** The typed code was wrong. */
    InvalidCode,

    /** Firebase's own SMS quota for the project is exhausted. */
    SmsQuota,

    VerificationFailed,

    /** The email is already attached to a live account. */
    EmailTaken,

    /** This number already has an account — sign in instead. */
    PhoneTaken,

    AccountExists,

    /** No usable answer from the backend (offline, DNS, TLS, timeout). */
    Unreachable,

    /** This build has no Good Post backend URL configured. */
    NotConfigured,

    ServerError,

    // ── Channels and discovery (M2) ─────────────────────────────────────

    /** §7: the account already owns as many channels as it may. */
    ChannelLimit,

    /** The channel is gone, or the link never pointed at one. */
    ChannelNotFound,

    /** §7/§32: only the owner may edit this channel. */
    ChannelForbidden,

    /** §18: a moderator has suspended or banned the channel. */
    ChannelUnavailable,

    /** §12: the user blocked this channel and must unblock it first. */
    ChannelBlocked,

    /** An owner cannot follow their own channel. */
    CannotFollowOwnChannel,

    /** Mute was requested for a channel the user does not follow. */
    NotFollowing,

    /** A field the server rejected: bad category, bad country code, empty patch. */
    InvalidInput,

    Unknown
}

/**
 * Map a backend/transport code onto a [GoodPostError].
 *
 * Unknown codes deliberately fall through to [GoodPostError.Unknown] rather
 * than being treated as success or silently dropped: a code this client does
 * not recognise means the contract moved, and saying so is better than
 * inventing a meaning for it.
 */
fun goodPostErrorFor(code: String): GoodPostError = when (code) {
    "invalid_phone" -> GoodPostError.InvalidPhone
    "phone_banned" -> GoodPostError.PhoneBanned
    "account_banned" -> GoodPostError.AccountBanned
    "account_suspended" -> GoodPostError.AccountSuspended
    "otp_rate_limited", "rate_limited" -> GoodPostError.RateLimited
    "otp_locked" -> GoodPostError.OtpLocked
    "invalid_code" -> GoodPostError.InvalidCode
    "sms_quota_exceeded" -> GoodPostError.SmsQuota
    "verification_failed", "invalid_id_token", "phone_unverified",
    "wrong_sign_in_provider", "otp_required" -> GoodPostError.VerificationFailed
    "email_already_registered" -> GoodPostError.EmailTaken
    "phone_already_registered" -> GoodPostError.PhoneTaken
    "account_already_exists" -> GoodPostError.AccountExists

    // ── Channels and discovery (M2) ─────────────────────────────────────
    "channel_limit_reached" -> GoodPostError.ChannelLimit
    "channel_not_found" -> GoodPostError.ChannelNotFound
    "channel_forbidden" -> GoodPostError.ChannelForbidden
    "channel_unavailable" -> GoodPostError.ChannelUnavailable
    "channel_blocked" -> GoodPostError.ChannelBlocked
    "cannot_follow_own_channel" -> GoodPostError.CannotFollowOwnChannel
    "not_following" -> GoodPostError.NotFollowing
    "invalid_category", "invalid_country", "empty_update",
    "invalid_channel_id", "invalid_cursor", "incorrect_cursor" -> GoodPostError.InvalidInput

    "unreachable", "timeout" -> GoodPostError.Unreachable
    "not_configured" -> GoodPostError.NotConfigured
    "server_error", "auth_unavailable", "http_error", "payload_too_large" -> GoodPostError.ServerError
    else -> GoodPostError.Unknown
}

/** E.164: a leading +, no leading zero, 7–15 digits in total. */
private val E164 = Regex("^\\+[1-9]\\d{6,14}$")

/**
 * Normalise typed input to E.164, or null when it is not usable.
 *
 * A leading `+` is REQUIRED rather than guessed. Inferring a country code from
 * a bare national number would mean either asking the user to pick a country
 * (not built yet) or assuming one — and assuming wrong means texting a
 * stranger, or silently failing for every user outside the guessed region.
 * Requiring the explicit prefix fails honestly instead.
 *
 * Spaces, dashes, dots and parentheses are stripped, because every country
 * formats numbers with them and none of them are significant.
 */
fun normalizePhoneInput(raw: String): String? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("+")) return null

    val digits = trimmed.filter { it.isDigit() }
    if (digits.isEmpty()) return null

    val candidate = "+$digits"
    return if (E164.matches(candidate)) candidate else null
}
