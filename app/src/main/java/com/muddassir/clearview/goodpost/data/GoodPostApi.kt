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
 * [Failed] and [Unreachable] are deliberately distinct. "The server said no"
 * and "we never reached the server" need different words in front of a user,
 * and §36 requires the app not to claim success it has not been told about —
 * which starts with not conflating a refusal with an outage.
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
 * Pure, so it is unit-tested directly. Two rules it enforces: a body carrying
 * an `error` code is always preferred over a guess from the status line, and
 * when there is no body the status is still turned into something meaningful
 * rather than an empty string that would silently match no branch.
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
 * Good Post authentication client (§35: `HttpURLConnection` + coroutines +
 * `org.json`, the pattern already used by `ClearViewBackendClient`,
 * `MediaRepository` and `QuranApi`).
 *
 * No Retrofit, OkHttp or serialization library is introduced — §35 asks for
 * the existing stack, and this endpoint set is small enough that the shared
 * convention costs nothing.
 *
 * Targets `/api/v1/...` on its OWN base URL, not the Block tab's moderation
 * backend: the two are separate services with separate deploy lifecycles, and
 * the version prefix keeps the two meanings of "channel" from colliding.
 *
 * One transport serves every Good Post endpoint. `call` takes an absolute API
 * path, so M2's channels and discovery routes reuse the same timeouts, the
 * same error-code extraction and the same "never log the request body" rule
 * instead of a second HTTP implementation drifting from this one.
 */
class GoodPostApi(
    private val baseUrl: () -> String = { BuildConfig.GOODPOST_BASE_URL }
) {

    private companion object {
        const val TAG = "GoodPostApi"
        const val AUTH_PATH = "/api/v1/auth"
        const val CHANNELS_PATH = "/api/v1/channels"
        const val DISCOVER_PATH = "/api/v1/discover"
        const val POSTS_PATH = "/api/v1/posts"
        const val MEDIA_PATH = "/api/v1/media"
        const val POLLS_PATH = "/api/v1/polls"
        const val REPORTS_PATH = "/api/v1/reports"
        const val BLOCKS_PATH = "/api/v1/blocks"
        const val CONVERSATIONS_PATH = "/api/v1/conversations"
        const val NOTIFICATIONS_PATH = "/api/v1/notifications"
        const val NOTICES_PATH = "/api/v1/notices"
        const val DEVICES_PATH = "/api/v1/devices"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 512_000
    }

    /** True when a backend URL has been configured for this build. */
    val isConfigured: Boolean get() = baseUrl().isNotBlank()

    // ── Endpoints ───────────────────────────────────────────────────────

    /**
     * Register the intent to verify a number and take one slot out of the
     * hourly allowance. Called BEFORE Firebase sends the SMS, so a banned or
     * rate-limited number never costs an SMS.
     */
    suspend fun requestOtp(phone: String, purpose: String): ApiResult<Unit> {
        val body = JSONObject().apply {
            put("phone", phone)
            put("purpose", purpose)
        }
        return when (val result = call("POST", AUTH_PATH + "/otp/request", body)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Failed -> result
            ApiResult.Unreachable -> ApiResult.Unreachable
        }
    }

    /**
     * Step 1 by email — the same contract as [requestOtp].
     *
     * The server answers 200 whether or not the address has an account, on
     * purpose, so a success here must not be read as "this email is
     * registered". Nothing is known until the code comes back.
     */
    suspend fun requestEmailOtp(email: String, purpose: String? = null): ApiResult<Unit> {
        val body = JSONObject().apply {
            put("email", email)
            // "register" when the caller already knows this is a new account.
            // The server accepts either purpose at either step, so this only
            // ever saves a round trip — it never decides what may happen.
            if (purpose != null) put("purpose", purpose)
        }
        return when (val result = call("POST", AUTH_PATH + "/email/otp", body)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Failed -> result
            ApiResult.Unreachable -> ApiResult.Unreachable
        }
    }

    /** Step 2 by email. Issues the same device session as the phone flow. */
    suspend fun emailSignIn(
        email: String,
        code: String,
        deviceLabel: String?
    ): ApiResult<GoodPostSession> {
        val body = JSONObject().apply {
            put("email", email)
            put("code", code)
        }
        return authCall("/email/signin", body, deviceLabel)
    }

    /**
     * Step 2 for an address with no account: the same code, plus a name.
     *
     * Reached only after `/email/signin` answered `email_not_registered` — the
     * server re-checks that fact, so a client cannot register an address that
     * already has an account.
     */
    suspend fun emailRegister(
        email: String,
        code: String,
        displayName: String,
        deviceLabel: String?
    ): ApiResult<GoodPostSession> {
        val body = JSONObject().apply {
            put("email", email)
            put("code", code)
            put("displayName", displayName)
        }
        return authCall("/email/register", body, deviceLabel)
    }

    suspend fun signIn(idToken: String, deviceLabel: String?): ApiResult<GoodPostSession> {
        val body = JSONObject().apply { put("idToken", idToken) }
        return authCall("/signin", body, deviceLabel)
    }

    suspend fun register(
        idToken: String,
        displayName: String,
        email: String,
        deviceLabel: String?
    ): ApiResult<GoodPostSession> {
        val body = JSONObject().apply {
            put("idToken", idToken)
            put("displayName", displayName)
            put("email", email)
        }
        return authCall("/register", body, deviceLabel)
    }

    suspend fun refresh(refreshToken: String, deviceLabel: String?): ApiResult<GoodPostSession> {
        val body = JSONObject().apply { put("refreshToken", refreshToken) }
        return authCall("/refresh", body, deviceLabel)
    }

    /** Validate a stored token and read the account back. */
    suspend fun me(accessToken: String): ApiResult<GoodPostAccount> =
        withContext(Dispatchers.IO) {
            when (val result = call("GET", AUTH_PATH + "/me", null, accessToken)) {
                is ApiResult.Ok ->
                    GoodPostSessionCodec.accountFromResponse(result.value)
                        ?.let { ApiResult.Ok(it) }
                        ?: ApiResult.Unreachable
                is ApiResult.Failed -> result
                ApiResult.Unreachable -> ApiResult.Unreachable
            }
        }

    suspend fun logout(accessToken: String, refreshToken: String): ApiResult<Unit> {
        val body = JSONObject().apply {
            put("refreshToken", refreshToken)
            put("all", false)
        }
        return when (val result = call("POST", AUTH_PATH + "/logout", body, accessToken)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Failed -> result
            ApiResult.Unreachable -> ApiResult.Unreachable
        }
    }

    // ── Channels (§5, §6, §7, §12) ──────────────────────────────────────
    //
    // These return the parsed JSON body rather than a typed object: turning it
    // into [GoodPostChannel] is the codec's job, kept pure and unit-tested
    // separately. The API layer's contract stops at "the server said this".

    suspend fun channelCategories(accessToken: String): ApiResult<JSONObject> =
        get(CHANNELS_PATH + "/categories", accessToken)

    /** §4 Channels view. `cursor` resumes a previous page. */
    suspend fun followingChannels(accessToken: String, cursor: String? = null): ApiResult<JSONObject> =
        get(CHANNELS_PATH + "/following" + pageQuery(cursor), accessToken)

    /** §7 The channels the caller owns or helps run. */
    suspend fun managedChannels(accessToken: String): ApiResult<JSONObject> =
        get(CHANNELS_PATH + "/mine", accessToken)

    suspend fun channelDetail(accessToken: String, channelId: String): ApiResult<JSONObject> =
        get(CHANNELS_PATH + "/" + encode(channelId), accessToken)

    /**
     * §6 resolve a share link.
     *
     * The slug is the only identifier a shared link carries, so this is the one
     * call that turns a pasted `clearview://goodpost/channel/<slug>` back into a
     * channel. The route is a sibling of [channelDetail] rather than a query on
     * it, which is why the path segment differs.
     */
    suspend fun channelBySlug(accessToken: String, slug: String): ApiResult<JSONObject> =
        get(CHANNELS_PATH + "/by-slug/" + encode(slug), accessToken)

    /** §5 Discover. Blank filters are omitted rather than sent empty. */
    suspend fun discoverChannels(
        accessToken: String,
        query: String? = null,
        category: String? = null,
        country: String? = null,
        sort: ChannelSort = ChannelSort.Popular,
        cursor: String? = null
    ): ApiResult<JSONObject> {
        val params = buildList {
            query?.takeIf { it.isNotBlank() }?.let { add("q=" + encode(it)) }
            category?.takeIf { it.isNotBlank() }?.let { add("category=" + encode(it)) }
            country?.takeIf { it.isNotBlank() }?.let { add("country=" + encode(it)) }
            add("sort=" + encode(sort.wire))
            cursor?.takeIf { it.isNotBlank() }?.let { add("cursor=" + encode(it)) }
        }
        return get(DISCOVER_PATH + "/channels?" + params.joinToString("&"), accessToken)
    }

    suspend fun createChannel(accessToken: String, body: JSONObject): ApiResult<JSONObject> =
        call("POST", CHANNELS_PATH, body, accessToken)

    suspend fun updateChannel(
        accessToken: String,
        channelId: String,
        body: JSONObject
    ): ApiResult<JSONObject> =
        call("PATCH", CHANNELS_PATH + "/" + encode(channelId), body, accessToken)

    /** @param follow true to follow, false to unfollow. */
    suspend fun setFollow(
        accessToken: String,
        channelId: String,
        follow: Boolean
    ): ApiResult<JSONObject> {
        val method = if (follow) "POST" else "DELETE"
        return call(method, CHANNELS_PATH + "/" + encode(channelId) + "/follow", null, accessToken)
    }

    /** §17 mute (`enabled = false`) or unmute. */
    suspend fun setNotifications(
        accessToken: String,
        channelId: String,
        enabled: Boolean
    ): ApiResult<JSONObject> {
        val body = JSONObject().apply { put("enabled", enabled) }
        return call(
            "PUT",
            CHANNELS_PATH + "/" + encode(channelId) + "/notifications",
            body,
            accessToken
        )
    }

    /** §12 block, which also ends the follow, or unblock. */
    suspend fun setBlocked(
        accessToken: String,
        channelId: String,
        blocked: Boolean
    ): ApiResult<JSONObject> {
        val method = if (blocked) "POST" else "DELETE"
        return call(method, CHANNELS_PATH + "/" + encode(channelId) + "/block", null, accessToken)
    }

    /** §4 clear the unread flag. */
    suspend fun markRead(accessToken: String, channelId: String): ApiResult<JSONObject> =
        call("POST", CHANNELS_PATH + "/" + encode(channelId) + "/read", null, accessToken)

    // ── Posts (§4, §8) ──────────────────────────────────────────────────

    /** §4 the aggregated feed: posts from every channel the caller follows. */
    suspend fun feed(accessToken: String, cursor: String? = null): ApiResult<JSONObject> =
        get(POSTS_PATH + "/feed" + pageQuery(cursor), accessToken)

    /** §8 one channel's history, newest first. */
    suspend fun channelPosts(
        accessToken: String,
        channelId: String,
        cursor: String? = null
    ): ApiResult<JSONObject> =
        get(
            CHANNELS_PATH + "/" + encode(channelId) + "/posts" + pageQuery(cursor),
            accessToken
        )

    suspend fun publishPost(
        accessToken: String,
        channelId: String,
        body: JSONObject
    ): ApiResult<JSONObject> =
        call("POST", CHANNELS_PATH + "/" + encode(channelId) + "/posts", body, accessToken)

    suspend fun updatePost(
        accessToken: String,
        channelId: String,
        postId: String,
        body: JSONObject
    ): ApiResult<JSONObject> =
        call(
            "PATCH",
            CHANNELS_PATH + "/" + encode(channelId) + "/posts/" + encode(postId),
            body,
            accessToken
        )

    /** §7 remove a post. The server soft-deletes it, so it can be restored. */
    suspend fun deletePost(
        accessToken: String,
        channelId: String,
        postId: String
    ): ApiResult<JSONObject> =
        call(
            "DELETE",
            CHANNELS_PATH + "/" + encode(channelId) + "/posts/" + encode(postId),
            null,
            accessToken
        )

    // ── Media (§9, §10) ─────────────────────────────────────────────────

    /**
     * Ask for a presigned upload URL.
     *
     * The body carries a content type and a size and NOTHING ELSE. There is no
     * field for a destination: the server derives the object key from the media
     * row's own id, so no request can influence where its bytes land.
     */
    suspend fun requestUpload(accessToken: String, body: JSONObject): ApiResult<JSONObject> =
        call("POST", MEDIA_PATH + "/uploads", body, accessToken)

    /** Confirm the object arrived. The server verifies it against the bucket. */
    suspend fun confirmUpload(accessToken: String, mediaId: String): ApiResult<JSONObject> =
        call("POST", MEDIA_PATH + "/uploads/" + encode(mediaId) + "/confirm", null, accessToken)

    /**
     * A fresh presigned read URL for one asset (§10).
     *
     * Used for a manual download and for playback, because the URL embedded in
     * a post payload expires and a post cached from ten minutes ago holds a
     * dead link.
     */
    suspend fun mediaUrl(accessToken: String, mediaId: String): ApiResult<JSONObject> =
        get(MEDIA_PATH + "/" + encode(mediaId) + "/url", accessToken)

    // ── Engagement (§13, §14, §15) ──────────────────────────────────────

    /**
     * §13 react, change a reaction, or clear it with `reaction = null`.
     *
     * `null` is SENT as an explicit JSON null rather than omitted, because the
     * server reads an absent key as a client bug and an explicit null as
     * "clear mine" — a distinction that would silently delete reactions if this
     * collapsed the two.
     */
    suspend fun setReaction(
        accessToken: String,
        postId: String,
        reaction: String?
    ): ApiResult<JSONObject> {
        val body = JSONObject().apply { put("reaction", reaction ?: JSONObject.NULL) }
        return call(
            "POST",
            POSTS_PATH + "/" + encode(postId) + "/reactions",
            body,
            accessToken
        )
    }

    /**
     * §15 record a view.
     *
     * A POST, matching the server: a GET would be prefetched, cached and
     * retried by every intermediary, turning one look into several.
     */
    suspend fun recordView(accessToken: String, postId: String): ApiResult<JSONObject> =
        call("POST", POSTS_PATH + "/" + encode(postId) + "/views", null, accessToken)

    /** §14 vote, or change a vote. Aggregate results come back, never voters. */
    suspend fun votePoll(
        accessToken: String,
        pollId: String,
        optionIds: List<String>
    ): ApiResult<JSONObject> {
        val body = JSONObject().apply { put("optionIds", JSONArray(optionIds)) }
        return call("POST", POLLS_PATH + "/" + encode(pollId) + "/votes", body, accessToken)
    }

    /** §15 a channel's own numbers. Owner/editor only — the server decides. */
    suspend fun analytics(
        accessToken: String,
        channelId: String,
        days: Int? = null
    ): ApiResult<JSONObject> {
        val suffix = days?.let { "?days=" + encode(it.toString()) }.orEmpty()
        return get(CHANNELS_PATH + "/" + encode(channelId) + "/analytics" + suffix, accessToken)
    }

    // ── Reports and blocks (§12, §18) ───────────────────────────────────

    suspend fun createReport(accessToken: String, body: JSONObject): ApiResult<JSONObject> =
        call("POST", REPORTS_PATH, body, accessToken)

    /** The caller's own reports, so a reporter can see what happened (§18). */
    suspend fun ownReports(accessToken: String): ApiResult<JSONObject> =
        get(REPORTS_PATH + "/mine", accessToken)

    /** §12 block another account, or unblock. */
    suspend fun setUserBlocked(
        accessToken: String,
        userId: String,
        blocked: Boolean
    ): ApiResult<JSONObject> {
        val method = if (blocked) "POST" else "DELETE"
        return call(method, BLOCKS_PATH + "/" + encode(userId), null, accessToken)
    }

    // ── Private messages (§16) ──────────────────────────────────────────

    /** Open (or find) the caller's conversation with a channel. */
    suspend fun openConversation(accessToken: String, channelId: String): ApiResult<JSONObject> =
        call(
            "POST",
            CHANNELS_PATH + "/" + encode(channelId) + "/conversations",
            null,
            accessToken
        )

    /** The channel's inbox: the admin side of §16. */
    suspend fun channelConversations(
        accessToken: String,
        channelId: String
    ): ApiResult<JSONObject> =
        get(CHANNELS_PATH + "/" + encode(channelId) + "/conversations", accessToken)

    /** Conversations the caller opened as a follower. */
    suspend fun ownConversations(accessToken: String): ApiResult<JSONObject> =
        get(CONVERSATIONS_PATH, accessToken)

    suspend fun conversationMessages(
        accessToken: String,
        conversationId: String,
        cursor: String? = null
    ): ApiResult<JSONObject> =
        get(
            CONVERSATIONS_PATH + "/" + encode(conversationId) + "/messages" + pageQuery(cursor),
            accessToken
        )

    suspend fun sendConversationMessage(
        accessToken: String,
        conversationId: String,
        body: JSONObject
    ): ApiResult<JSONObject> =
        call(
            "POST",
            CONVERSATIONS_PATH + "/" + encode(conversationId) + "/messages",
            body,
            accessToken
        )

    suspend fun setConversationBlocked(
        accessToken: String,
        conversationId: String,
        blocked: Boolean
    ): ApiResult<JSONObject> {
        val body = JSONObject().apply { put("blocked", blocked) }
        return call(
            "POST",
            CONVERSATIONS_PATH + "/" + encode(conversationId) + "/block",
            body,
            accessToken
        )
    }

    // ── Notifications and notices (§17, §26) ────────────────────────────

    suspend fun notifications(accessToken: String): ApiResult<JSONObject> =
        get(NOTIFICATIONS_PATH, accessToken)

    /** Mark the whole inbox read, or just [ids] when they are given. */
    suspend fun markNotificationsRead(
        accessToken: String,
        ids: List<String>
    ): ApiResult<JSONObject> {
        val body = JSONObject().apply {
            if (ids.isNotEmpty()) put("ids", JSONArray(ids))
        }
        return call("POST", NOTIFICATIONS_PATH + "/read", body, accessToken)
    }

    suspend fun notices(accessToken: String): ApiResult<JSONObject> =
        get(NOTICES_PATH, accessToken)

    suspend fun markNoticesRead(
        accessToken: String,
        ids: List<String>
    ): ApiResult<JSONObject> {
        val body = JSONObject().apply { put("ids", JSONArray(ids)) }
        return call("POST", NOTICES_PATH + "/read", body, accessToken)
    }

    /** Tell the server where to push for this account (§17). */
    suspend fun registerDevice(accessToken: String, body: JSONObject): ApiResult<JSONObject> =
        call("POST", DEVICES_PATH, body, accessToken)

    /**
     * Stop pushing to this device, on sign-out.
     *
     * The token rides in the path because sign-out has already discarded the
     * rest of the session state by the time this runs — a request body built
     * from state that no longer exists is a request that never gets sent.
     */
    suspend fun unregisterDevice(accessToken: String, token: String): ApiResult<JSONObject> =
        call("DELETE", DEVICES_PATH + "/" + encode(token), null, accessToken)

    // ── Internals ───────────────────────────────────────────────────────

    private suspend fun get(path: String, accessToken: String): ApiResult<JSONObject> =
        withContext(Dispatchers.IO) { call("GET", path, null, accessToken) }

    private fun pageQuery(cursor: String?): String =
        cursor?.takeIf { it.isNotBlank() }?.let { "?cursor=" + encode(it) }.orEmpty()

    /**
     * Percent-encode a path or query component.
     *
     * A cursor is base64url, which can contain `-` and `_` but is still encoded
     * defensively: a raw `&` or `#` reaching the URL would silently truncate
     * the request and look like an empty page rather than a bug.
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private suspend fun authCall(
        path: String,
        body: JSONObject,
        deviceLabel: String?
    ): ApiResult<GoodPostSession> = withContext(Dispatchers.IO) {
        when (val result = call("POST", AUTH_PATH + path, body, null, deviceLabel)) {
            is ApiResult.Ok ->
                GoodPostSessionCodec.fromAuthResponse(result.value, System.currentTimeMillis())
                    ?.let { ApiResult.Ok(it) }
                    // A 2xx we cannot parse is a backend contract break, not a
                    // success. Reported as unreachable so the caller retries
                    // rather than storing a half-session.
                    ?: ApiResult.Unreachable
            is ApiResult.Failed -> result
            ApiResult.Unreachable -> ApiResult.Unreachable
        }
    }

    /**
     * Every network call goes through here, so the dispatcher cannot be
     * forgotten.
     *
     * This wrapper exists because of a real defect: [callBlocking] is blocking,
     * and a caller that invoked it straight from `viewModelScope` (which is
     * `Dispatchers.Main`) hit `NetworkOnMainThreadException` — which the
     * catch-all below swallowed, turning a main-thread bug into a permanent
     * "unreachable" that looked exactly like the user being offline. Phone
     * sign-in failed at step one with a misleading error.
     *
     * Dispatching inside the transport rather than at each call site means a
     * new endpoint cannot reintroduce it.
     */
    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        bearer: String? = null,
        deviceLabel: String? = null
    ): ApiResult<JSONObject> =
        withContext(Dispatchers.IO) { callBlocking(method, path, body, bearer, deviceLabel) }

    /** The blocking implementation. Call it only through [call]. */
    private fun callBlocking(
        method: String,
        path: String,
        body: JSONObject? = null,
        bearer: String? = null,
        deviceLabel: String? = null
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
                deviceLabel?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("X-Device-Label", it)
                }
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

            return ApiResult.Failed(status, errorCodeFrom(status, errorText))
        } catch (e: Exception) {
            // Never log the request body — it carries the ID token.
            Log.d(TAG, "$method $path failed: ${e.javaClass.simpleName}")
            return ApiResult.Unreachable
        } finally {
            conn?.disconnect()
        }
    }
}
