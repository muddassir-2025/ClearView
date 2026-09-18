package com.muddassir.clearview.goodpost.data

import android.util.Log
import com.muddassir.clearview.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The outcome of a backend call.
 *
 * [Failed] and [Unreachable] are deliberately distinct. "The server said no" and
 * "we never reached the server" need different words in front of a reader, and
 * a screen must never claim a success it was not told about.
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** The server answered with a machine-readable error code. */
    data class Failed(val status: Int, val code: String) : ApiResult<Nothing>
    /** No usable answer: offline, DNS, TLS, timeout, or an unparseable body. */
    data object Unreachable : ApiResult<Nothing>
}

/**
 * Maps a non-2xx response to the error code the UI branches on.
 *
 * Pure, so it is unit-tested directly. Two rules: a body carrying an `error`
 * code always wins over a guess from the status line, and when there is no body
 * the status is still turned into something meaningful rather than an empty
 * string that would silently match no branch.
 */
internal fun errorCodeFrom(status: Int, rawBody: String?): String {
    if (!rawBody.isNullOrBlank()) {
        try {
            val code = JSONObject(rawBody).optString("error")
            if (code.isNotBlank()) return code
        } catch (e: Exception) {
            // Not JSON — a proxy error page, an HTML 502, and so on.
        }
    }
    return when (status) {
        400 -> "invalid_request"
        401 -> "unauthorized"
        403 -> "forbidden"
        404 -> "not_found"
        408 -> "timeout"
        413 -> "payload_too_large"
        429 -> "rate_limited"
        else -> if (status >= 500) "server_error" else "http_error"
    }
}

/**
 * The Good Post transport (§35: `HttpURLConnection` + coroutines + `org.json`,
 * the pattern the rest of ClearView already uses).
 *
 * No Retrofit, OkHttp or serialization library is introduced. One transport
 * serves every endpoint, so a new call cannot arrive with different timeouts,
 * different error extraction, or its own way of forgetting the dispatcher.
 *
 * **The read half carries no credential, and that is the product.** Browsing,
 * searching, opening a channel and reading its posts are anonymous calls, which
 * is what makes Good Post usable with no signup, no login and no account (§1).
 *
 * **The write half is the admin surface only**, and it is a different path
 * prefix with a different token: `/admin/api`. A reader's device holds no token
 * at all, so there is nothing on it that could be used to publish.
 *
 * Media is the one thing that does NOT travel through this API on its way out:
 * the bytes go straight to the bucket with a presigned URL, so a hundred-
 * megabyte video never passes through the backend process at all (§22).
 */
class GoodPostApi(
    private val baseUrl: () -> String = { BuildConfig.GOODPOST_BASE_URL }
) {

    private companion object {
        const val TAG = "GoodPostApi"

        /**
         * Anonymous reads (§24).
         *
         * The root of the versioned API, not a `/public` sub-prefix: with the
         * reader-facing surface the only thing under `/api/v1`, those paths ARE
         * the product's own and `/api/v1/channels` is the shortest honest URL
         * for a phone to fetch on cold start (§26).
         */
        const val PUBLIC_PATH = "/api/v1"

        /** The administrator surface, on its own prefix and its own token. */
        const val ADMIN_PATH = "/admin/api"

        /**
         * A READER's own state, under the public prefix but behind a token (§3).
         *
         * The same versioned API as the anonymous reads, because it is the same
         * client and the same product — what separates it is that every path
         * under it needs a verified Firebase token, while every other `/api/v1`
         * path needs nothing. Reads stay token-free so Good Post keeps working
         * with no identity at all (§1); only what is *theirs* needs one.
         */
        const val READER_PATH = "$PUBLIC_PATH/readers"

        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 512_000

        /**
         * Uploads get a longer budget than a JSON call, because the body is a
         * file rather than a few hundred bytes. Still bounded: a stalled upload
         * must fail with something the composer can word, not hang forever
         * behind a spinner.
         */
        const val UPLOAD_CONNECT_TIMEOUT_MS = 15_000
        const val UPLOAD_READ_TIMEOUT_MS = 120_000
    }

    /** True when a backend URL has been configured for this build. */
    val isConfigured: Boolean get() = baseUrl().isNotBlank()

    // ── Public reads (§3–§13) ───────────────────────────────────────────

    /**
     * Channel list and search (§3, §7).
     *
     * One endpoint for both: browsing is a search with an empty term, and the
     * list screen renders the same rows either way.
     */
    suspend fun channels(
        query: String? = null,
        category: String? = null,
        sort: String? = null,
        cursor: String? = null
    ): ApiResult<GoodPostPage<GoodPostChannel>> =
        parsedGet(channelsPath(query, category, sort, cursor), GoodPostCodec::channelPage)

    /** The categories Explore can filter by. */
    suspend fun categories(): ApiResult<List<GoodPostCategory>> =
        parsedGet("$PUBLIC_PATH/categories", GoodPostCodec::categories)

    // ── A reader's own state (§3–§6) ────────────────────────────────────
    //
    // The token is a parameter rather than something this class fetches: the
    // transport stays a transport, and the identity is resolved once per call in
    // `GoodPostRepository`. It also keeps the one thing that must never happen
    // expressible — there is no path from these methods to a request that is
    // sent with somebody else's credential, because the only token they can send
    // is the one they were handed.

    /**
     * The channels this reader follows, most recently active first (§4, §5).
     *
     * The home list, and the only place `unreadCount` and `notificationsMuted`
     * appear on a channel.
     */
    suspend fun following(
        token: String,
        cursor: String? = null
    ): ApiResult<GoodPostPage<GoodPostChannel>> =
        parsedCall(
            "GET",
            "$READER_PATH/me/following" + pageQuery(cursor),
            null,
            token,
            GoodPostCodec::channelPage
        )

    /** Follow a channel (§4). Idempotent: following twice is one follow. */
    suspend fun follow(token: String, idOrSlug: String): ApiResult<GoodPostFollow> =
        parsedCall(
            "POST",
            "$READER_PATH/me/following/${encode(idOrSlug)}",
            null,
            token,
            ::followBody
        )

    /** Stop following a channel (§4). */
    suspend fun unfollow(token: String, idOrSlug: String): ApiResult<GoodPostFollow> =
        parsedCall(
            "DELETE",
            "$READER_PATH/me/following/${encode(idOrSlug)}",
            null,
            token,
            ::followBody
        )

    /** Mute or unmute a followed channel's notifications (§6). */
    suspend fun setChannelMuted(
        token: String,
        idOrSlug: String,
        muted: Boolean
    ): ApiResult<GoodPostFollow> =
        parsedCall(
            "PATCH",
            "$READER_PATH/me/following/${encode(idOrSlug)}",
            JSONObject().put("muted", muted),
            token,
            ::followBody
        )

    /**
     * Clear a channel's unread badge (§5): the reader has opened it.
     *
     * No timestamp is sent — the server's clock decides whether a post came
     * before or after the reader looked, and a device clock that is wrong would
     * otherwise clear a badge for posts that had not arrived yet.
     */
    suspend fun markChannelRead(token: String, idOrSlug: String): ApiResult<GoodPostFollow> =
        parsedCall(
            "POST",
            "$READER_PATH/me/following/${encode(idOrSlug)}/read",
            null,
            token,
            ::followBody
        )

    /**
     * A `{ follow: {...} }` body, or a contract break.
     *
     * The server always names the channel it acted on, so a body without one is
     * not "no follow" — it is a response this client cannot read, and saying so
     * is better than reporting a success whose effect is unknown.
     */
    private fun followBody(body: JSONObject): GoodPostFollow =
        GoodPostCodec.follow(body) ?: throw ContractBreak()

    /** One channel, by uuid or by the slug a share link carries (§6). */
    suspend fun channel(idOrSlug: String): ApiResult<GoodPostChannel> =
        parsedGet("$PUBLIC_PATH/channels/${encode(idOrSlug)}", { body ->
            GoodPostCodec.singleChannel(body) ?: throw ContractBreak()
        })

    /**
     * A channel's posts, newest first, optionally narrowed by a search term
     * (§9).
     *
     * One call for the feed and for a search inside the channel, because the
     * server answers both from one endpoint: a separate search path would be a
     * second payload shape and a second paging contract for the same list.
     */
    suspend fun channelPosts(
        idOrSlug: String,
        cursor: String? = null,
        query: String? = null
    ): ApiResult<GoodPostPage<GoodPostPost>> =
        parsedGet(
            "$PUBLIC_PATH/channels/${encode(idOrSlug)}/posts" +
                queryParams(cursor, query?.takeIf { it.isNotBlank() }?.let { "q" to it }),
            GoodPostCodec::postPage
        )

    /**
     * Tell the server which posts this reader has just seen (§9).
     *
     * The one write on the public surface, and it needs no credential: a read is
     * not an account, and asking somebody to sign in before their read could be
     * counted would make the count a census of accounts rather than of readers.
     *
     * Fire-and-forget from the caller's point of view — nothing in the UI waits
     * on it, and a failure is not reported, because "we could not count your
     * read" is not something a reader can act on. The ids are the server's own,
     * so the worst a wrong batch can do is count a post that was on screen.
     */
    suspend fun reportPostViews(idOrSlug: String, postIds: List<String>): ApiResult<Int> =
        parsedCall(
            method = "POST",
            path = "$PUBLIC_PATH/channels/${encode(idOrSlug)}/posts/views",
            body = JSONObject().apply { put("ids", JSONArray(postIds)) },
            bearer = null,
            parse = { body -> body.optInt("counted", 0) }
        )

    /** A channel's images and videos, for the profile gallery (§13). */
    suspend fun channelMedia(
        idOrSlug: String,
        cursor: String? = null
    ): ApiResult<GoodPostPage<GoodPostMediaItem>> =
        parsedGet(
            "$PUBLIC_PATH/channels/${encode(idOrSlug)}/media" + pageQuery(cursor),
            GoodPostCodec::mediaPage
        )

    /** One post, reachable on its own from a link or a share (§9). */
    suspend fun post(postId: String): ApiResult<GoodPostPost> =
        parsedGet("$PUBLIC_PATH/posts/${encode(postId)}", { body ->
            GoodPostCodec.singlePost(body) ?: throw ContractBreak()
        })

    // ── Administrator sign-in (§16) ─────────────────────────────────────

    /**
     * Sign in as an administrator.
     *
     * This is NOT a viewer account and creates nothing: the addresses that work
     * here were provisioned by the deployment, never by the app. The one message
     * for every refusal comes from the server (`invalid_credentials`), so the
     * screen cannot reveal whether an address exists.
     */
    suspend fun adminLogin(email: String, password: String): ApiResult<AdminSession> =
        parsedCall(
            method = "POST",
            path = "$ADMIN_PATH/auth/login",
            body = JSONObject().apply {
                put("email", email)
                put("password", password)
            },
            bearer = null,
            parse = ::adminSessionFrom
        )

    /**
     * Sign in as a creator with a Firebase ID token (§16).
     *
     * The token is the CREDENTIAL here, not a session: it goes in the
     * `Authorization` header of a route that no bearer token of ours can call,
     * because a creator who has not created a channel yet has no account for a
     * session to name.
     *
     * Two answers, and the difference is the whole flow. A [`CreatorSignIn`]
     * session means they already run a channel; `NeedsChannel` means this is
     * their first time and the next screen is the one that names their channel.
     */
    suspend fun creatorLogin(idToken: String): ApiResult<CreatorSignIn> =
        parsedCall(
            method = "POST",
            path = "$ADMIN_PATH/auth/firebase",
            body = null,
            bearer = idToken,
            parse = { body ->
                if (body.optBoolean("needsChannel", false)) {
                    CreatorSignIn.NeedsChannel(body.nullableString("email"))
                } else {
                    CreatorSignIn.Session(adminSessionFrom(body))
                }
            }
        )

    /**
     * A creator's first, and only, channel (§16).
     *
     * Authenticated by the Firebase token for the same reason as above: there is
     * no session yet, and this call is what creates the account a session would
     * name. The reply is a full sign-in, so the app never sits in the state
     * between "channel created" and "signed in" — the server answers both at
     * once and the client either has a session or does not.
     */
    suspend fun creatorCreateChannel(idToken: String, name: String): ApiResult<AdminSession> =
        parsedCall(
            method = "POST",
            path = "$ADMIN_PATH/creator/channel",
            body = JSONObject().apply { put("name", name) },
            bearer = idToken,
            parse = ::adminSessionFrom
        )

    /**
     * Exchange the session's long-lived half for a new pair (§16).
     *
     * Sent with no bearer token on purpose: the whole point of this call is that
     * the access token it would carry has expired. The reply is the same shape as
     * a sign-in, including a NEW refresh token — the server rotates it, so the one
     * used here stops working immediately after.
     */
    suspend fun adminRefresh(refreshToken: String): ApiResult<AdminSession> =
        parsedCall(
            method = "POST",
            path = "$ADMIN_PATH/auth/refresh",
            body = JSONObject().apply { put("refreshToken", refreshToken) },
            bearer = null,
            parse = ::adminSessionFrom
        )

    /**
     * Revoke the session on the server.
     *
     * Best effort by design: the caller has already forgotten the session locally
     * and must not be held on the screen by a network failure. What it prevents is
     * a refresh token outliving the device it was issued to.
     */
    suspend fun adminSignOut(refreshToken: String): ApiResult<Unit> =
        parsedCall(
            method = "POST",
            path = "$ADMIN_PATH/auth/logout",
            body = JSONObject().apply { put("refreshToken", refreshToken) },
            bearer = null,
            parse = { }
        )

    /**
     * The session payload, shared by sign-in and renewal.
     *
     * Both routes answer with this shape, and a body missing either token is a
     * contract break rather than a signed-in administrator: an access token with
     * nothing to renew it with would work for ten minutes and then fail with no way
     * back, which is precisely the state this client was in.
     */
    private fun adminSessionFrom(body: JSONObject): AdminSession {
        val token = body.optString("accessToken")
        val refresh = body.optString("refreshToken")
        if (token.isBlank() || refresh.isBlank()) throw ContractBreak()

        val admin = body.optJSONObject("admin")
        return AdminSession(
            token = token,
            refreshToken = refresh,
            role = admin?.optString("role").orEmpty(),
            email = admin?.optString("email").orEmpty(),
            channelId = admin?.nullableString("channelId")
        )
    }

    /** The channels this administrator may publish to (§17, §18). */
    suspend fun adminChannels(token: String): ApiResult<List<GoodPostChannel>> =
        parsedCall(
            method = "GET",
            path = "$ADMIN_PATH/channels",
            body = null,
            bearer = token,
            parse = { body ->
                val items = body.optJSONArray("channels") ?: JSONArray()
                val parsed = ArrayList<GoodPostChannel>(items.length())
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.let { GoodPostCodec.channel(it)?.let(parsed::add) }
                }
                parsed
            }
        )

    /** Create a channel, and the administrator account that runs it (§20). */
    suspend fun adminCreateChannel(token: String, body: JSONObject): ApiResult<GoodPostChannel> =
        parsedCall(
            method = "POST",
            path = "$ADMIN_PATH/channels",
            body = body,
            bearer = token,
            parse = { GoodPostCodec.singleChannel(it) ?: throw ContractBreak() }
        )

    /** Change a channel's name, description or category (§19). */
    suspend fun adminUpdateChannel(
        token: String,
        channelId: String,
        body: JSONObject
    ): ApiResult<GoodPostChannel> =
        parsedCall(
            method = "PATCH",
            path = "$ADMIN_PATH/channels/${encode(channelId)}",
            body = body,
            bearer = token,
            parse = { GoodPostCodec.singleChannel(it) ?: throw ContractBreak() }
        )

    /**
     * Delete a channel, with its posts, its media and the login that ran it (§17).
     *
     * There is no undo on the other end, so the app asks first — the confirmation
     * is a UI decision, but its existence is why this returns nothing useful:
     * there is nothing to render from a channel that no longer exists.
     */
    suspend fun adminDeleteChannel(token: String, channelId: String): ApiResult<Unit> =
        parsedCall(
            method = "DELETE",
            path = "$ADMIN_PATH/channels/${encode(channelId)}",
            body = null,
            bearer = token,
            parse = { }
        )

    /** Publish a post (§21). The type follows from what is attached. */
    suspend fun adminCreatePost(
        token: String,
        channelId: String,
        body: JSONObject
    ): ApiResult<GoodPostPost> =
        parsedCall(
            method = "POST",
            path = "$ADMIN_PATH/channels/${encode(channelId)}/posts",
            body = body,
            bearer = token,
            parse = { GoodPostCodec.singlePost(it) ?: throw ContractBreak() }
        )

    /** Edit a post's text or link (§21). */
    suspend fun adminUpdatePost(
        token: String,
        postId: String,
        body: JSONObject
    ): ApiResult<GoodPostPost> =
        parsedCall(
            method = "PATCH",
            path = "$ADMIN_PATH/posts/${encode(postId)}",
            body = body,
            bearer = token,
            parse = { GoodPostCodec.singlePost(it) ?: throw ContractBreak() }
        )

    /** Remove a post (§17). Soft on the server, so it stays reversible. */
    suspend fun adminDeletePost(token: String, postId: String): ApiResult<Unit> =
        parsedCall(
            method = "DELETE",
            path = "$ADMIN_PATH/posts/${encode(postId)}",
            body = null,
            bearer = token,
            parse = { Unit }
        )

    /**
     * Remove several posts in one request (§17).
     *
     * One call for a whole selection, because the server applies it atomically:
     * a partial delete would be a list with some rows missing and no way to know
     * which, and re-sending would be the only way to find out.
     */
    suspend fun adminDeletePosts(token: String, postIds: List<String>): ApiResult<Int> =
        parsedCall(
            method = "POST",
            path = "$ADMIN_PATH/posts/bulk-delete",
            body = JSONObject().apply {
                put("postIds", JSONArray().apply { postIds.forEach { put(it) } })
            },
            bearer = token,
            parse = { body -> body.optInt("deleted", 0) }
        )

    // ── Media uploads (§21, §22) ────────────────────────────────────────

    /**
     * Ask for a place to put one file.
     *
     * The response carries a presigned URL, the headers that go with it, and a
     * media id. What it deliberately does NOT carry is the object key: the
     * server derives that from a uuid it generates, so no request this app makes
     * can choose where its bytes land. The file's content type and size are
     * declared here because the URL SIGNs both — the bucket refuses an upload
     * that differs, which puts the authoritative check at the bucket rather than
     * in our own validation.
     *
     * A deployment with no storage answers `media_unavailable` (503), which the
     * composer words explicitly rather than as a generic failure: text and link
     * posts still work there (§22).
     */
    suspend fun adminRequestUpload(
        token: String,
        contentType: String,
        byteSize: Long,
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null
    ): ApiResult<GoodPostUpload> =
        parsedCall(
            method = "POST",
            path = "$ADMIN_PATH/media/uploads",
            body = JSONObject().apply {
                put("contentType", contentType)
                put("byteSize", byteSize)
                width?.let { put("width", it) }
                height?.let { put("height", it) }
                durationMs?.let { put("durationMs", it) }
            },
            bearer = token,
            parse = { body ->
                val upload = body.optJSONObject("upload") ?: throw ContractBreak()
                val mediaId = upload.optString("mediaId")
                val url = upload.optString("uploadUrl")
                if (mediaId.isBlank() || url.isBlank()) throw ContractBreak()

                GoodPostUpload(
                    mediaId = mediaId,
                    uploadUrl = url,
                    uploadHeaders = headersOf(upload.optJSONObject("uploadHeaders")),
                    kind = upload.optString("kind")
                )
            }
        )

    /**
     * Tell the server the bytes are there, and let it check.
     *
     * Idempotent on the server side, so a retry after a dropped response is not
     * punished. Only after this does the media become claimable by a post.
     */
    suspend fun adminConfirmUpload(token: String, mediaId: String): ApiResult<GoodPostMedia> =
        parsedCall(
            method = "POST",
            path = "$ADMIN_PATH/media/uploads/${encode(mediaId)}/confirm",
            body = null,
            bearer = token,
            parse = { body ->
                val media = body.optJSONObject("media") ?: throw ContractBreak()
                GoodPostCodec.media(media) ?: throw ContractBreak()
            }
        )

    /**
     * PUT the file to the presigned URL.
     *
     * Streams, and never buffers the file: a video is potentially a hundred
     * megabytes, and reading one into a byte array to hand to a socket is how a
     * mid-range phone runs out of heap while publishing (§26).
     *
     * The URL is absolute and goes to the bucket, not to our API — which is the
     * point of presigning. It carries its own signature, so no credential of
     * ours is involved and the token that authorised it is not sent anywhere.
     * A non-2xx answer (an expired signature, a size the bucket refused) is
     * reported as a failure with the status, so the composer can say something
     * true rather than claiming the upload worked.
     */
    suspend fun putFile(
        uploadUrl: String,
        headers: Map<String, String>,
        byteSize: Long,
        open: () -> java.io.InputStream?
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = UPLOAD_CONNECT_TIMEOUT_MS
                readTimeout = UPLOAD_READ_TIMEOUT_MS
                // Fixed length rather than chunked: it is what the signature
                // signed, so the bucket can reject a truncated send instead of
                // storing half a file.
                setFixedLengthStreamingMode(byteSize)
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }

            val input = open() ?: return@withContext ApiResult.Unreachable
            input.use { source ->
                conn.outputStream.use { sink -> source.copyTo(sink, DEFAULT_BUFFER_SIZE * 16) }
            }

            val status = conn.responseCode
            if (status in 200..299) ApiResult.Ok(Unit)
            else ApiResult.Failed(status, errorCodeFrom(status, null))
        } catch (e: Exception) {
            // The URL is a signed capability and is never logged; neither is the
            // exception's message, which can quote the URL back.
            Log.d(TAG, "PUT to storage failed: ${e.javaClass.simpleName}")
            ApiResult.Unreachable
        } finally {
            conn?.disconnect()
        }
    }

    /** The signable headers for an upload, as the server listed them. */
    private fun headersOf(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = json.optString(key)
        }
        return out
    }

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * A read that never carries a token, decoded into a domain shape.
     *
     * Kept separate from [parsedCall] so no read call site can acquire a
     * credential by accident: an anonymous request is not a call with a null
     * token, it is a call whose signature has nowhere to put one.
     */
    private suspend fun <T> parsedGet(
        path: String,
        parse: (JSONObject) -> T
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        when (val result = callBlocking("GET", path, null, null)) {
            is ApiResult.Ok -> decode(result.value, parse)
            is ApiResult.Failed -> result
            ApiResult.Unreachable -> ApiResult.Unreachable
        }
    }

    private suspend fun <T> parsedCall(
        method: String,
        path: String,
        body: JSONObject?,
        bearer: String?,
        parse: (JSONObject) -> T
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        when (val result = callBlocking(method, path, body, bearer)) {
            is ApiResult.Ok -> decode(result.value, parse)
            is ApiResult.Failed -> result
            ApiResult.Unreachable -> ApiResult.Unreachable
        }
    }

    /**
     * Run the parser, turning a contract break into a retryable state.
     *
     * A 2xx whose body does not fit the shape is not a success — storing or
     * rendering half of it would put a channel with no name on screen. Reported
     * as unreachable so the caller retries, which is the only sane response to
     * "the server said something we do not understand".
     */
    private fun <T> decode(body: JSONObject, parse: (JSONObject) -> T): ApiResult<T> =
        try {
            ApiResult.Ok(parse(body))
        } catch (e: Exception) {
            Log.d(TAG, "unparseable 2xx body: ${e.javaClass.simpleName}")
            ApiResult.Unreachable
        }

    /** Marks a body that parsed as JSON but not as the expected shape. */
    private class ContractBreak : Exception("unexpected payload shape")

    private fun channelsPath(
        query: String?,
        category: String?,
        sort: String?,
        cursor: String?
    ): String {
        val params = buildList {
            query?.takeIf { it.isNotBlank() }?.let { add("q=" + encode(it)) }
            category?.takeIf { it.isNotBlank() }?.let { add("category=" + encode(it)) }
            sort?.takeIf { it.isNotBlank() }?.let { add("sort=" + encode(it)) }
            cursor?.takeIf { it.isNotBlank() }?.let { add("cursor=" + encode(it)) }
        }
        val suffix = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return "$PUBLIC_PATH/channels$suffix"
    }

    private fun pageQuery(cursor: String?): String = queryParams(cursor)

    /**
     * The query string a paged read carries: an optional cursor, and at most one
     * more named pair.
     *
     * Built as a list rather than by concatenation so a call that carries both
     * cannot produce `?cursor=...?q=...` — the second `?` would end the path and
     * the term would arrive as part of the cursor.
     */
    private fun queryParams(cursor: String?, vararg extra: Pair<String, String>?): String {
        val params = buildList {
            cursor?.takeIf { it.isNotBlank() }?.let { add("cursor=" + encode(it)) }
            extra.forEach { pair ->
                pair?.let { (name, value) -> add("$name=" + encode(value)) }
            }
        }
        return if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    /**
     * Percent-encode a path or query component.
     *
     * A cursor is base64url, which can contain `-` and `_`, and is still encoded
     * defensively: a raw `&` or `#` reaching the URL would silently truncate the
     * request and look like an empty page rather than like a bug.
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * The blocking transport. Call it only from a coroutine on the IO
     * dispatcher — [parsedGet] and [parsedCall] are the only callers and both
     * dispatch.
     */
    private fun callBlocking(
        method: String,
        path: String,
        body: JSONObject?,
        bearer: String?
    ): ApiResult<JSONObject> {
        val root = baseUrl().trimEnd('/')
        if (root.isBlank()) return ApiResult.Unreachable

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(root + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ClearView-Android")
                bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }

            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }

            val status = conn.responseCode
            if (status in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                    .take(MAX_RESPONSE_BYTES)
                return ApiResult.Ok(JSONObject(text))
            }

            // Read the error body so the server's code wins over a status guess.
            val errorText = conn.errorStream?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?.take(MAX_RESPONSE_BYTES)

            val code = errorCodeFrom(status, errorText)
            // Logged because one sentence on screen covers many conditions: a
            // report of "something went wrong" cannot be told apart from a
            // rejected token, a name collision or a rate limit by reading the
            // UI. This line is what names the condition, and it is the only
            // record of it — the server keeps its own, and the two can be lined
            // up by time.
            //
            // The code and the status ONLY. Never the body, for the reason in
            // the catch below: on the admin surface it can echo a password.
            Log.w(TAG, "$method $path → $status $code")
            return ApiResult.Failed(status, code)
        } catch (e: Exception) {
            // Never log a request body: on the admin surface it carries a
            // password, and on the read surface it carries a search term.
            Log.d(TAG, "$method $path failed: ${e.javaClass.simpleName}")
            return ApiResult.Unreachable
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * A presigned upload, plus the media id every later step refers to (§21).
 *
 * [uploadUrl] is a capability scoped to one object, one method and a short
 * expiry. It is held only for the duration of the PUT and never persisted: it
 * expires, and a cached one would be a stale key rather than a saved round trip.
 */
data class GoodPostUpload(
    val mediaId: String,
    val uploadUrl: String,
    /** `Content-Type`, and anything else the signature covers. */
    val uploadHeaders: Map<String, String>,
    /** `image`, `video` or `audio`. */
    val kind: String
)

/**
 * The two answers a creator sign-in can have (§16).
 *
 * A sealed pair rather than a nullable session, because "no channel yet" is a
 * step in the flow and not a failure: a session of null would have to be told
 * apart from a sign-in that failed, and those need completely different screens.
 */
sealed interface CreatorSignIn {

    /** This creator already runs a channel. */
    data class Session(val session: AdminSession) : CreatorSignIn

    /**
     * No account yet. The address is what Firebase knows, shown so the next
     * screen can say WHICH identity is about to own a channel.
     */
    data class NeedsChannel(val email: String?) : CreatorSignIn
}

/** A signed-in administrator, as the app holds it (§16). */
data class AdminSession(
    val token: String,
    /**
     * The long-lived half of the session.
     *
     * Kept because the access token is deliberately short — ten minutes, since it
     * can publish and delete on a channel's behalf — while the session behind it
     * lasts a week. Throwing this away is what made an expired token look like an
     * empty account: with nothing to renew with, every refused call became a
     * failure the dashboard rendered as "you have no channels".
     *
     * The server ROTATES it on every renewal: the new value is the only one that
     * works for more than one further attempt, because the previous one is kept
     * solely to survive a response that was lost in flight. So the newest pair is
     * written back after every refresh, and two renewals must never run at once.
     */
    val refreshToken: String,
    /** `super_admin` or `channel_admin`. The server decides; the app words it. */
    val role: String,
    val email: String,
    /** The channel a `channel_admin` is confined to; null for a super admin. */
    val channelId: String?
) {
    val isSuperAdmin: Boolean get() = role == "super_admin"
}

/** A value that is absent or JSON-null reads as null, not as "". */
private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
