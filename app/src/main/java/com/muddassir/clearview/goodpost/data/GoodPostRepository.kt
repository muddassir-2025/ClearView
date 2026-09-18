package com.muddassir.clearview.goodpost.data

import android.app.Activity
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Good Post's data layer (§26, §27).
 *
 * Two rules shape it:
 *
 *  * **Reads never need a session.** Every browsing call goes straight to the
 *    anonymous `/api/v1` surface, so a reader who has just installed the
 *    app sees channels on the first frame rather than a sign-in screen. There is
 *    no token to acquire, refresh or expire.
 *
 *  * **The cache is read first, and the network replaces it.** The ViewModel
 *    renders [cachedChannels] immediately and calls [channels] behind it, which
 *    is what makes the tab feel like it opens instantly (§26) and what keeps it
 *    usable offline (§27). The repository does not merge the two — that decision
 *    belongs where the screen is built, so "saved" and "live" can be labelled
 *    differently.
 *
 * Administrator writes carry a token, and it is the ONLY token the app holds.
 */
internal class GoodPostRepository(
    /**
     * The application context, kept for one reason: an upload streams from the
     * picker's URI, and only a `ContentResolver` can open that stream. It is the
     * application context rather than an activity, so holding it cannot outlive
     * a screen.
     */
    private val context: Context,
    private val api: GoodPostApi = GoodPostApi(),
    /**
     * Who this device's reader is (§3). Overridable so the follow path can be
     * tested without a Firebase project, and so a build with none injects
     * [NoIdentity] and degrades instead of crashing.
     */
    private val identity: GoodPostIdentity = FirebaseGoodPostIdentity()
) {

    private val cache = GoodPostCache(context)
    private val tokens = AdminTokenStore(context)

    /** True when a backend URL has been configured for this build. */
    val isConfigured: Boolean get() = api.isConfigured

    /**
     * True when this build can identify a reader at all (§3).
     *
     * Read by the home tab to decide what a failed follows fetch means — see
     * [GoodPostIdentity.available].
     */
    val identifiesReaders: Boolean get() = identity.available

    // ── Public reads ────────────────────────────────────────────────────

    /** Cached channels, for the first frame. Null when nothing is stored. */
    fun cachedChannels(): CachedChannels? = cache.loadChannels()

    fun cachedCategories(): List<GoodPostCategory> = cache.loadCategories()

    fun cachedPosts(channelId: String): CachedPosts? = cache.loadPosts(channelId)

    /** The channel list, or a search over it (§3, §7). */
    suspend fun channels(
        query: String? = null,
        category: String? = null,
        sort: String? = null,
        cursor: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): ApiResult<GoodPostPage<GoodPostChannel>> =
        api.channels(query, category, sort, cursor).also { result ->
            // Only a FIRST page is cached: paging appends to the list on screen,
            // and a cache holding pages two, three and four as separate entries
            // would be a cache no reader ever sees the rest of.
            if (result is ApiResult.Ok && cursor == null && query.isNullOrBlank() &&
                category.isNullOrBlank()
            ) {
                cache.saveChannels(result.value.items, nowMs)
            }
        }

    /** One channel, by id or slug (§8, §11). */
    suspend fun channel(idOrSlug: String): ApiResult<GoodPostChannel> = api.channel(idOrSlug)

    // ── A reader's own state (§3–§6) ────────────────────────────────────
    //
    // Every one of these resolves the identity first, and answers
    // `auth_unavailable` when there is none. That is the same code the backend
    // returns for a deployment that cannot verify anybody, so the screen words
    // "this build has no identity" and "the server cannot check one" the same
    // way — because to the reader they are the same situation: follows are not
    // available right now, and everything public still is.

    /**
     * The channels this reader follows (§4, §5).
     *
     * Registered in the channel cache exactly as the public list is: a follower
     * who opens Good Post offline should see the channels they care about, not
     * an empty tab.
     */
    suspend fun following(
        cursor: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): ApiResult<GoodPostPage<GoodPostChannel>> {
        val token = identity.token() ?: return unverified()
        return api.following(token, cursor).also { result ->
            if (result is ApiResult.Ok && cursor == null && result.value.items.isNotEmpty()) {
                cache.saveChannels(result.value.items, nowMs)
            }
        }
    }

    /** Follow a channel (§4). */
    suspend fun follow(idOrSlug: String): ApiResult<GoodPostFollow> {
        val token = identity.token() ?: return unverified()
        return api.follow(token, idOrSlug)
    }

    /** Stop following a channel (§4). */
    suspend fun unfollow(idOrSlug: String): ApiResult<GoodPostFollow> {
        val token = identity.token() ?: return unverified()
        return api.unfollow(token, idOrSlug)
    }

    /** Mute or unmute a followed channel (§6). */
    suspend fun setChannelMuted(idOrSlug: String, muted: Boolean): ApiResult<GoodPostFollow> {
        val token = identity.token() ?: return unverified()
        return api.setChannelMuted(token, idOrSlug, muted)
    }

    /** Clear a channel's unread badge (§5). */
    suspend fun markChannelRead(idOrSlug: String): ApiResult<GoodPostFollow> {
        val token = identity.token() ?: return unverified()
        return api.markChannelRead(token, idOrSlug)
    }

    /**
     * A reader-scoped call with nobody to make it as (§3).
     *
     * 503 and `auth_unavailable`, matching the backend's own answer for the same
     * condition, rather than 401: the reader is not signed out and has nothing
     * to sign in with — the deployment simply cannot verify anybody.
     */
    private fun <T> unverified(): ApiResult<T> = ApiResult.Failed(503, "auth_unavailable")

    /** A channel's posts, newest first (§9). */
    suspend fun channelPosts(
        channelId: String,
        cursor: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): ApiResult<GoodPostPage<GoodPostPost>> =
        api.channelPosts(channelId, cursor).also { result ->
            if (result is ApiResult.Ok && cursor == null) {
                cache.savePosts(channelId, result.value.items, nowMs)
            }
        }

    /**
     * Search inside one channel (§9).
     *
     * Deliberately NOT [channelPosts] with a term, and the difference is the
     * cache: a search result set is not the channel's history, so writing it
     * where the history lives would leave a reader who searched once opening the
     * channel offline later and seeing their search results as the feed.
     */
    suspend fun searchChannelPosts(
        channelId: String,
        term: String,
        cursor: String? = null
    ): ApiResult<GoodPostPage<GoodPostPost>> = api.channelPosts(channelId, cursor, term)

    /**
     * Report that this reader has seen these posts (§9).
     *
     * No identity is resolved here, deliberately: the write counts reads, not
     * readers. Requiring a token would make the number depend on the same
     * identity follows need, so a post would read as unviewed until somebody
     * signed in to look at it — and it would say nothing about the readers who
     * never do.
     */
    suspend fun reportPostViews(channelId: String, postIds: List<String>): ApiResult<Int> =
        api.reportPostViews(channelId, postIds)

    /** A channel's images and videos, for the profile gallery (§13). */
    suspend fun channelMedia(
        channelId: String,
        cursor: String? = null
    ): ApiResult<GoodPostPage<GoodPostMediaItem>> = api.channelMedia(channelId, cursor)

    /** Categories for the Explore filter (§7). */
    suspend fun categories(): ApiResult<List<GoodPostCategory>> =
        api.categories().also { result ->
            if (result is ApiResult.Ok) cache.saveCategories(result.value)
        }

    // ── Administrator session (§16) ─────────────────────────────────────

    /** The stored administrator session, or null. */
    fun adminSession(): AdminSession? = tokens.load()

    /** Sign in, and keep the session only if the server accepted it. */
    suspend fun adminLogin(email: String, password: String): ApiResult<AdminSession> =
        api.adminLogin(email, password).also { result ->
            if (result is ApiResult.Ok) tokens.save(result.value)
        }

    /**
     * The Firebase ID token for an email/password creator (§16), or null.
     *
     * Exposed through the repository rather than reached for directly, so the
     * screens keep talking to one object and `NoIdentity` — a build with no
     * `google-services.json` — remains a single substitution rather than a
     * condition at each call site.
     */
    suspend fun creatorToken(email: String, password: String, signUp: Boolean): String? =
        identity.creatorToken(email, password, signUp)

    /**
     * Forget the CREATOR identity, leaving the reader's alone (§16).
     *
     * Exposed here rather than reached for from the ViewModel so the whole of
     * Firebase stays behind this one object — see [GoodPostIdentity].
     */
    suspend fun creatorSignOut() = identity.creatorSignOut()

    /** The Google sign-in result (§16): a token, a dismissal, or a failure. */
    suspend fun googleCreatorToken(activity: Activity): GoogleSignIn =
        identity.googleCreatorToken(activity)

    /**
     * Sign in as a creator (§16), keeping the session only if there is one.
     *
     * Nothing is stored on a `NeedsChannel` answer, because there is nothing to
     * store: the account does not exist until the channel does. The Firebase
     * token that got this far is held by the ViewModel for the one call that
     * finishes the job, and never written to disk.
     */
    suspend fun creatorLogin(idToken: String): ApiResult<CreatorSignIn> =
        api.creatorLogin(idToken).also { result ->
            if (result is ApiResult.Ok && result.value is CreatorSignIn.Session) {
                tokens.save((result.value as CreatorSignIn.Session).session)
            }
        }

    /** A creator's first channel — which also creates the account it belongs to. */
    suspend fun creatorCreateChannel(idToken: String, name: String): ApiResult<AdminSession> =
        api.creatorCreateChannel(idToken, name).also { result ->
            if (result is ApiResult.Ok) tokens.save(result.value)
        }

    /**
     * Forget the administrator session on this device.
     *
     * The cached content goes with it. A cached list is a view of public content,
     * so leaving it would leak nothing — but the cache is also the administrator's
     * working set, and a fresh sign-in should not start from someone else's screen.
     */
    /**
     * Sign out: drop the credentials and everything fetched with them.
     *
     * Including the cached public content: a cached list belongs to whoever was
     * last reading, and leaving it for the next person to open the tab would show
     * them someone else's view without saying so.
     */
    fun forgetAdminSession() {
        tokens.clear()
        cache.clear()
    }

    /**
     * Revoke the session on the server, so a refresh token does not outlive the
     * device it was issued to. Best effort: the caller has already forgotten it
     * locally, and a network failure must not hold anyone on a screen.
     */
    suspend fun revokeAdminSession(refreshToken: String) {
        api.adminSignOut(refreshToken)
    }

    /**
     * Serialises renewal.
     *
     * The server rotates the refresh token and tolerates the previous one ONCE, for
     * a client whose response was lost; a second replay of it is treated as a
     * stolen token and revokes the entire session. Two admin calls that lapse
     * together — the dashboard's channels and a channel's posts, say — would refresh
     * with the same token at the same time, which is exactly that shape. One at a
     * time, and the loser retries with what the winner obtained.
     */
    private val renewal = Mutex()

    /**
     * Run an authorized call, renewing the session once if it has lapsed.
     *
     * The access token is short on purpose — ten minutes, because it can publish
     * and delete on a channel's behalf — while the session behind it lasts a week,
     * so a request refused with 401 is the ordinary state of a tab left open
     * rather than an error. Renewing in one place means no call site has to know:
     * doing it at each of them is how one gets missed, and a missed one reports a
     * refused call as a fact about the account — which is exactly how an expired
     * token came to be shown as "No channels. Create one to start publishing."
     *
     * A renewal the SERVER refused clears the session, so the caller asks for a
     * password instead of pretending. A renewal that never reached a server does
     * not: see the note on the failure branches below.
     */
    private suspend fun <T> authorized(call: suspend (String) -> ApiResult<T>): ApiResult<T> {
        val session = tokens.load() ?: return ApiResult.Failed(401, "unauthorized")

        val first = call(session.token)
        if (first !is ApiResult.Failed || first.status != 401) return first

        return renewal.withLock {
            // Re-read: another call may have renewed while this one waited, and
            // refreshing again with the token this call started with is the replay
            // the server revokes a session for.
            val current = tokens.load()
            if (current != null && current.token != session.token) {
                return@withLock call(current.token)
            }

            when (val renewed = api.adminRefresh(session.refreshToken)) {
                is ApiResult.Ok -> {
                    // Saved before the retry, not after: the pair is rotated, so a
                    // crash between here and the call would leave the stored one
                    // already spent.
                    tokens.save(renewed.value)
                    call(renewed.value.token)
                }

                // The server answered and refused THE CREDENTIAL: revoked,
                // expired, or the account is disabled. The session is genuinely
                // over, so it is forgotten and the caller asks for a password.
                //
                // Only 401 and 403 qualify. A 5xx is the server having a problem
                // with itself, and discarding the only copy of a session over it
                // would be the same mistake as a dropped connection — so it is
                // passed through and the session is kept.
                is ApiResult.Failed -> {
                    if (renewalEndsSession(renewed.status)) {
                        tokens.clear()
                        first
                    } else {
                        ApiResult.Failed(renewed.status, renewed.code)
                    }
                }

                // Nothing answered. This is NOT the server ending the session,
                // and treating it as one was a real bug: a dropped connection, a
                // request that timed out, or a host that was cold-starting all
                // arrived here, cleared the stored session — the only copy — and
                // then reported the 401 as "Your session ended. Sign in again."
                // An administrator filling in a channel form over a flaky
                // connection was signed out for the crime of being on a train.
                //
                // The session is kept and the failure is reported as what it is.
                // The next call renews again, so a connection that comes back
                // recovers by itself with nobody retyping a password.
                ApiResult.Unreachable -> ApiResult.Unreachable
            }
        }
    }

    suspend fun adminChannels(): ApiResult<List<GoodPostChannel>> =
        authorized { token -> api.adminChannels(token) }

    suspend fun adminCreateChannel(body: JSONObject): ApiResult<GoodPostChannel> =
        authorized { token -> api.adminCreateChannel(token, body) }

    suspend fun adminUpdateChannel(
        channelId: String,
        body: JSONObject
    ): ApiResult<GoodPostChannel> = authorized { token -> api.adminUpdateChannel(token, channelId, body) }

    suspend fun adminCreatePost(
        channelId: String,
        body: JSONObject
    ): ApiResult<GoodPostPost> = authorized { token -> api.adminCreatePost(token, channelId, body) }

    suspend fun adminUpdatePost(
        postId: String,
        body: JSONObject
    ): ApiResult<GoodPostPost> = authorized { token -> api.adminUpdatePost(token, postId, body) }

    suspend fun adminDeletePost(postId: String): ApiResult<Unit> =
        authorized { token -> api.adminDeletePost(token, postId) }

    /**
     * Remove a selection of posts in one request.
     *
     * One call rather than one per post: the server applies the whole selection
     * or none of it, so a failure cannot leave the list half-emptied with no way
     * to tell which half went.
     */
    suspend fun adminDeletePosts(postIds: List<String>): ApiResult<Int> =
        authorized { token -> api.adminDeletePosts(token, postIds) }

    /**
     * Delete a channel, with everything that belonged to it (§17).
     *
     * Hard on the server: the posts, the media rows, the objects in the bucket
     * and the login created to run the channel go with it. Offered to a super
     * administrator and refused to anyone else by the server, not by this call.
     */
    suspend fun adminDeleteChannel(channelId: String): ApiResult<Unit> =
        authorized { token -> api.adminDeleteChannel(token, channelId) }

    // ── Media (§21, §22) ────────────────────────────────────────────────

    /**
     * Upload one picked file, and return it once the server has confirmed it.
     *
     * The three steps live here rather than in the ViewModel so the sequencing
     * cannot be got wrong at a call site: a presign that is never confirmed, or
     * a confirm after a PUT that failed, would both leave an attachment the
     * composer believes is ready and the server refuses. Every failure exits the
     * same way — with the code that describes it — and nothing is left
     * half-claimed, because a media row is only attachable after a HEAD has
     * found its object.
     *
     * The bytes are streamed from the picker's URI with a fixed length, so a
     * large video costs a buffer rather than its own size in heap.
     */
    suspend fun adminUploadMedia(
        attachment: GoodPostAttachment
    ): ApiResult<GoodPostMedia> {
        // The two authenticated steps of the handshake renew independently; the
        // PUT between them is to the bucket with a pre-signed URL and needs no
        // session at all, so a retry after renewal cannot double-upload.
        val upload = when (
            val presign = authorized { token ->
                api.adminRequestUpload(
                    token = token,
                    contentType = attachment.contentType,
                    byteSize = attachment.byteSize,
                    width = attachment.width,
                    height = attachment.height,
                    durationMs = attachment.durationMs
                )
            }
        ) {
            is ApiResult.Ok -> presign.value
            // The server's own code, passed straight through: `media_unavailable`
            // is an answer about the deployment and the composer words it
            // differently from a failed transfer.
            is ApiResult.Failed -> return presign
            ApiResult.Unreachable -> return ApiResult.Unreachable
        }

        val uri = Uri.parse(attachment.uri)

        when (
            val put = api.putFile(
                uploadUrl = upload.uploadUrl,
                headers = upload.uploadHeaders,
                byteSize = attachment.byteSize
            ) {
                // Reopened per attempt rather than held: a stream the picker has
                // since revoked cannot be retried from the same handle.
                context.contentResolver.openInputStream(uri)
            }
        ) {
            is ApiResult.Ok -> Unit
            is ApiResult.Failed -> return put
            // Nothing was stored, so there is nothing to confirm. Left
            // unclaimed, the pending row is pruned by the server's own sweep.
            ApiResult.Unreachable -> return ApiResult.Unreachable
        }

        // The confirmation is what makes it attachable, so it is not optional
        // and its failure is the caller's answer: an upload the server has not
        // verified is an attachment that would fail at publish time instead.
        return authorized { token -> api.adminConfirmUpload(token, upload.mediaId) }
    }
}

/**
 * Whether a renewal the server REFUSED means the session is over (§19).
 *
 * The whole rule, in one place, because getting it wrong is not a cosmetic bug.
 * A renewal can fail in several ways and they call for opposite responses:
 *
 *  * **401 / 403** — the server looked at the refresh token and rejected it.
 *    Revoked, expired, or the account was disabled. The session is genuinely
 *    over and the only honest next step is to forget it and ask for a password.
 *
 *  * **A 5xx** — the server broke while answering. That says nothing about the
 *    credential, and discarding the session over it would be guessing.
 *
 *  * **Nothing at all** (offline, DNS, TLS, timeout, a host cold-starting) —
 *    handled by the caller, which keeps the session. A dropped connection is not
 *    the server ending a session.
 *
 * Only the first case may end a session. This is `internal` rather than private
 * so it is unit-tested directly instead of only through a running backend — the
 * mistake it exists to prevent was invisible from the app.
 */
internal fun renewalEndsSession(status: Int): Boolean = status == 401 || status == 403
