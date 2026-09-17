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

    /** The address is not usable as an email address. */
    InvalidEmail,

    /**
     * The code was right, but no account uses that address.
     *
     * Reported plainly because it is only ever told to whoever proved control
     * of the inbox — the server reveals nothing before the code is verified.
     * The wording has to send the user to the mobile flow, because email
     * sign-in opens existing accounts and never creates them (§19).
     */
    EmailNotRegistered,

    /**
     * This deployment cannot deliver email — no provider configured, or the
     * provider refused the send. Distinct from [Unreachable] because retrying
     * the same way will not help, and the user has a working alternative in
     * their mobile number.
     */
    EmailUnavailable,

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
     * The verification step itself is gone — the server's challenge expired
     * (its TTL elapsed) or Firebase's verification session did.
     *
     * Distinct from [InvalidCode] because the remedy is different and the two
     * must not be confused: re-typing the same digit string can never work
     * here, so the user has to ask for a new code. Reported generically before,
     * it read as "this number is bad", which sent people back to re-check a
     * number that was always fine.
     */
    VerificationExpired,

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

    // ── Engagement (M4) ─────────────────────────────────────────────────

    /** §13: a reaction name the server does not offer. */
    InvalidReaction,

    /** §15: only a channel's own admins may see its analytics. */
    AnalyticsForbidden,

    /** §14: the poll has closed, so a vote can no longer be recorded. */
    PollClosed,

    /** §14: this poll accepts one answer and more than one was selected. */
    PollSingleChoice,

    /**
     * A poll request the server refused on its merits: no option selected, an
     * option that belongs to another poll, or a poll that is gone. One wording
     * because the user has one action — select an available option, or move on.
     */
    PollRejected,

    // ── Moderation and messaging (M5) ───────────────────────────────────

    /** §16: the channel does not accept messages from followers. */
    MessagesDisabled,

    /** A channel cannot message itself, so an owner cannot open its inbox. */
    OwnChannel,

    /** §16: the channel has blocked this conversation. */
    ConversationBlocked,

    /** §16: the thread is closed and cannot take another message. */
    ConversationClosed,

    /** An empty or oversized message. */
    MessageInvalid,

    /** §18: the report itself was unusable — unknown reason or target. */
    ReportInvalid,

    // ── Notifications (M7) ──────────────────────────────────────────────

    /** §17: this account already has as many registered devices as it may. */
    DeviceLimit,

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
    "invalid_email" -> GoodPostError.InvalidEmail
    "email_not_registered" -> GoodPostError.EmailNotRegistered
    "email_unavailable" -> GoodPostError.EmailUnavailable
    "phone_banned" -> GoodPostError.PhoneBanned
    "account_banned" -> GoodPostError.AccountBanned
    "account_suspended" -> GoodPostError.AccountSuspended
    "otp_rate_limited", "rate_limited" -> GoodPostError.RateLimited
    "otp_locked" -> GoodPostError.OtpLocked
    "invalid_code" -> GoodPostError.InvalidCode
    "otp_required", "verification_expired" -> GoodPostError.VerificationExpired
    "invalid_phone_number" -> GoodPostError.NumberRejected
    "verification_unavailable" -> GoodPostError.VerificationUnavailable
    "sms_quota_exceeded" -> GoodPostError.SmsQuota
    "verification_failed", "invalid_id_token", "phone_unverified",
    "wrong_sign_in_provider" -> GoodPostError.VerificationFailed
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

    // ── Engagement (M4) ─────────────────────────────────────────────────
    "invalid_reaction" -> GoodPostError.InvalidReaction
    "analytics_forbidden" -> GoodPostError.AnalyticsForbidden
    "poll_closed" -> GoodPostError.PollClosed
    "single_choice_only" -> GoodPostError.PollSingleChoice
    "poll_not_found", "no_votes", "unknown_option", "invalid_poll",
    "too_few_options", "too_many_options", "invalid_option", "duplicate_option",
    "invalid_poll_window", "invalid_poll_id" -> GoodPostError.PollRejected

    // ── Moderation and messaging (M5) ───────────────────────────────────
    "messages_disabled" -> GoodPostError.MessagesDisabled
    "own_channel" -> GoodPostError.OwnChannel
    "conversation_blocked" -> GoodPostError.ConversationBlocked
    "conversation_closed" -> GoodPostError.ConversationClosed
    "invalid_message" -> GoodPostError.MessageInvalid
    "invalid_target", "invalid_target_type", "invalid_reason", "report_not_found",
    "cannot_block_self", "conversation_not_found", "invalid_conversation_id",
    "user_not_found", "invalid_user_id" -> GoodPostError.ReportInvalid

    // ── Notifications (M7) ──────────────────────────────────────────────
    "too_many_devices" -> GoodPostError.DeviceLimit
    "invalid_token", "notification_not_found", "device_not_found" ->
        GoodPostError.InvalidInput

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
/**
 * Mirror of the server's email check.
 *
 * Deliberately as loose as the backend's, because the authoritative test of an
 * address is that it is unique and deliverable, not that it matches a regex.
 * Being stricter HERE would reject input the server would have accepted, which
 * is the one failure mode a client-side check must never have.
 *
 * Trimmed and lowercased so the value shown back to the user matches what will
 * actually be looked up. The server normalises regardless — this is a courtesy,
 * never the authority.
 */
fun normalizeEmailInput(raw: String): String? {
    val normalized = raw.trim().lowercase()
    if (normalized.length > 254) return null
    return if (EMAIL.matches(normalized)) normalized else null
}

/** The server's rule, exactly: something, an `@`, then a dotted domain. */
private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

fun normalizePhoneInput(raw: String): String? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("+")) return null

    val digits = trimmed.filter { it.isDigit() }
    if (digits.isEmpty()) return null

    val candidate = "+$digits"
    return if (E164.matches(candidate)) candidate else null
}
