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

    /**
     * The verifier refused the number itself, after it passed the local E.164
     * check — an unassigned country code, or a format the carrier will not
     * route. Distinct from [InvalidPhone], which is our own validation saying
     * the input was never well-formed to begin with.
     */
    NumberRejected,

    /**
     * The project refuses to send at all — the SMS region policy does not cover
     * the number's region, or this app's package/signing certificate is not
     * registered. Distinct from [VerificationFailed] because retrying cannot
     * help and the number is not the problem.
     */
    VerificationUnavailable,

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

    // ── Posts and media (M3) ────────────────────────────────────────────

    /** §7/§32: only a channel admin (owner or editor) may publish here. */
    PostForbidden,

    /** The post is gone, already removed, or belongs to another channel. */
    PostNotFound,

    /** A post needs text, a link or a file, and this one has none. */
    PostEmpty,

    /** The text is longer than the server allows. */
    PostTooLong,

    /** The link is not an http(s) address. */
    PostBadLink,

    /** §7: the edit window for this post has closed. */
    PostEditClosed,

    /**
     * The attached media cannot be used: the wrong type, too large, too many,
     * mixed kinds, not uploaded, already attached to another post, or someone
     * else's. One wording because the user has one action available — replace
     * the file, or remove it and post the text alone.
     */
    PostMediaRejected,

    /** This deployment has no media storage, or the asset is not available. */
    MediaUnavailable,

    /** The picked file could not be read, so its size is unknown. */
    FileUnreadable,

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
    "invalid_phone_number" -> GoodPostError.NumberRejected
    "verification_unavailable" -> GoodPostError.VerificationUnavailable
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
    "invalid_category", "invalid_country", "empty_update", "invalid_channel_id",
    "invalid_post_id", "invalid_media_id", "invalid_cursor", "incorrect_cursor" ->
        GoodPostError.InvalidInput

    // ── Posts and media (M3) ────────────────────────────────────────────
    // Content problems get their own wording, because "the post was refused" 
    // with no reason leaves the user with nothing to change. Failures that all
    // mean "this file cannot be attached" share one, because the fix is the
    // same action for every one of them: pick a different file.
    "post_forbidden" -> GoodPostError.PostForbidden
    "post_not_found" -> GoodPostError.PostNotFound
    "empty_post" -> GoodPostError.PostEmpty
    "text_too_long" -> GoodPostError.PostTooLong
    "invalid_link" -> GoodPostError.PostBadLink
    "edit_window_closed" -> GoodPostError.PostEditClosed
    "too_many_media", "duplicate_media", "mixed_media", "unknown_media",
    "media_not_ready", "media_already_used", "media_size_mismatch",
    "media_not_uploaded", "unsupported_media_type", "media_too_large" ->
        GoodPostError.PostMediaRejected
    "media_unavailable", "media_forbidden", "upload_failed", "download_failed" ->
        GoodPostError.MediaUnavailable
    "file_unreadable" -> GoodPostError.FileUnreadable

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
