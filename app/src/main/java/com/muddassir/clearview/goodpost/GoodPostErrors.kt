package com.muddassir.clearview.goodpost

/**
 * Every way a Good Post screen can fail, as a reader needs to understand it.
 *
 * Backend error codes (`channel_not_found`, `invalid_credentials`, …) are stable
 * and machine-readable, which is exactly why they must not be shown to a person.
 * This enum is the single translation point, kept pure so the mapping is
 * unit-tested rather than discovered in production.
 *
 * The list is deliberately short. Good Post has no accounts, no sessions, no
 * verification and no engagement, so the long tail of auth and interaction
 * failures that used to live here no longer exists — and a smaller vocabulary is
 * one a reader can actually act on.
 */
enum class GoodPostError {
    /** No usable answer: offline, DNS, TLS, timeout. Retrying may help. */
    Offline,

    /** This build has no backend URL configured at all (§41). */
    NotConfigured,

    /** A channel or post that is gone, mistyped, or was taken down. */
    NotFound,

    /**
     * The administrator sign-in was refused.
     *
     * One error for a wrong address and a wrong password, because the server
     * answers one way for both on purpose (§16): telling them apart would turn
     * the sign-in screen into a way to find out which addresses exist.
     */
    InvalidCredentials,

    /** Too many requests. The reader is asked to wait, not told off. */
    RateLimited,

    /** The administrator's token is not accepted for this channel (§18). */
    Forbidden,

    /** A 5xx. Not the reader's fault, and worth saying so. */
    ServerFault,

    /** A field the server would not accept — an empty post, a bad link. */
    InvalidInput,

    /** This deployment cannot store or serve media (§22). */
    MediaUnavailable,

    /** The administrator account is locked out or switched off (§16). */
    AdminLocked,

    /** The file is bigger than this deployment will store. */
    MediaTooLarge,

    /** A file type that cannot be posted — an SVG, a document, an archive. */
    UnsupportedMedia,

    /**
     * A file that has not finished uploading.
     *
     * Publishing is refused while one is in flight, and this is the answer to
     * "why does nothing happen" — the alternative would be a post published
     * without the picture the user attached.
     */
    AttachmentUploading,

    /** A file the server would not accept, or would not attach to this post. */
    AttachmentFailed,

    /**
     * This deployment has no administrator surface at all.
     *
     * A 404 on the admin sign-in route means the backend was built without it,
     * which is a different condition from a wrong password and must not be
     * worded as one — telling someone their credentials are bad when the server
     * has no login route would send them to reset a password that was never the
     * problem.
     */
    AdminUnavailable,

    /**
     * The administrator session is over and could not be renewed.
     *
     * Distinct from [InvalidCredentials] on purpose. They are the same 401 to the
     * server, but they call for different responses: "that password is wrong"
     * sends someone to check their typing, while this one means the session ran out
     * and the same password will work immediately. Word-for-word, the difference is
     * between a user doubting their credentials and a user signing in again.
     */
    SessionExpired,

    /** A code this client does not recognise. */
    Unknown
}

/**
 * Map a backend/transport code onto a [GoodPostError].
 *
 * Unknown codes deliberately fall through to [GoodPostError.Unknown] rather than
 * being treated as success or silently dropped: a code this client does not
 * recognise means the contract moved, and saying so is better than inventing a
 * meaning for it.
 */
fun goodPostErrorFor(code: String): GoodPostError = when (code) {
    // Transport and process states, raised by the client rather than the server.
    "unreachable", "timeout" -> GoodPostError.Offline
    "not_configured" -> GoodPostError.NotConfigured
    "server_error", "internal_error" -> GoodPostError.ServerFault

    // Reads.
    "not_found", "channel_not_found", "post_not_found" -> GoodPostError.NotFound
    "rate_limited" -> GoodPostError.RateLimited
    "invalid_cursor", "invalid_channel_id", "invalid_post_id", "invalid_request" ->
        GoodPostError.InvalidInput

    // Administrator sign-in and writes.
    "invalid_credentials", "unauthorized" -> GoodPostError.InvalidCredentials
    "session_expired" -> GoodPostError.SessionExpired
    "forbidden", "channel_forbidden", "post_forbidden" -> GoodPostError.Forbidden
    "admin_unavailable" -> GoodPostError.AdminUnavailable

    // Publishing.
    "empty_post", "empty_update", "invalid_link", "text_too_long",
    "invalid_category", "invalid_country" -> GoodPostError.InvalidInput
    "media_unavailable" -> GoodPostError.MediaUnavailable

    // Administrator access the server refused beyond a bad password.
    "admin_locked", "admin_disabled" -> GoodPostError.AdminLocked

    // Media, split three ways on purpose: "too big", "wrong type" and "not
    // ready yet" need three different actions from the person who attached the
    // file, and collapsing them into one message would leave them guessing.
    "media_too_large", "payload_too_large" -> GoodPostError.MediaTooLarge
    "unsupported_media_type" -> GoodPostError.UnsupportedMedia
    "attachment_uploading", "media_not_ready", "media_not_uploaded",
    "media_size_mismatch" -> GoodPostError.AttachmentUploading
    "attachment_failed", "unknown_media", "media_already_used",
    "too_many_media", "duplicate_media", "mixed_media" -> GoodPostError.AttachmentFailed

    else -> GoodPostError.Unknown
}

/**
 * Whether a failed sign-in means "this server speaks a different contract".
 *
 * The administrator sign-in route takes `{email, password}` and answers with a
 * token or `invalid_credentials`. Three answers cannot come from a server that
 * implements it, and every one of them was being reported as a wrong password:
 *
 *  * **404** — no route there at all. The backend was built without an
 *    administrator surface.
 *  * **405** — something is at that path, but not for this method.
 *  * **`invalid_request`** — the body was refused as malformed by a server that
 *    wanted a different body. The previous deployment's login also required a
 *    PHONE NUMBER, so it answered exactly this to a well-formed request from
 *    this app: a correct password came back as "invalid credentials" and sent
 *    whoever typed it off to reset something that was never wrong.
 *
 * The last rule only holds because this client cannot send a malformed body.
 * `adminSignIn` refuses an unparsable address or an empty password locally, and
 * the Continue button is disabled while either field is blank — so a 400 on this
 * route is never this app's own doing.
 */
internal fun signInHitAnotherContract(status: Int, code: String): Boolean =
    when {
        status == 404 || status == 405 -> true
        code == "invalid_request" -> true
        else -> false
    }

/**
 * Mirror of the server's email check, for the administrator sign-in field.
 *
 * Deliberately as loose as the backend's, because the authoritative test of an
 * address is that it exists, not that it matches a regex. Being stricter HERE
 * would reject input the server would have accepted, which is the one failure
 * mode a client-side check must never have.
 *
 * Trimmed and lowercased so the value shown back matches what will be looked up.
 * The server normalises regardless — this is a courtesy, never the authority.
 */
fun normalizeEmailInput(raw: String): String? {
    val normalized = raw.trim().lowercase()
    if (normalized.length > 254) return null
    return if (EMAIL.matches(normalized)) normalized else null
}

/** The server's rule, exactly: something, an `@`, then a dotted domain. */
private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
