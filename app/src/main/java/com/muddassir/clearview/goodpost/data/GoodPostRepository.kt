package com.muddassir.clearview.goodpost.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Good Post's data layer (§26, §27).
 *
 * Two rules shape it:
 *
 *  * **Reads never need a session.** Every browsing call goes straight to the
 *    anonymous `/api/v1/public` surface, so a reader who has just installed the
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
    private val api: GoodPostApi = GoodPostApi()
) {

    private val cache = GoodPostCache(context)
    private val tokens = AdminTokenStore(context)

    /** True when a backend URL has been configured for this build. */
    val isConfigured: Boolean get() = api.isConfigured

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

    /** A channel's images and videos, for the profile gallery (§13). */
    suspend fun channelMedia(
        channelId: String,
        cursor: String? = null
    ): ApiResult<GoodPostPage<GoodPostMediaItem>> = api.channelMedia(channelId, cursor)

    // ── A device-local notification preference (§14) ────────────────────
    //
    // There is nowhere else this could live: a reader has no account, and no
    // push infrastructure exists behind it yet.

    /** Channels this device has muted. */
    fun mutedChannelIds(): Set<String> = cache.mutedChannelIds()

    fun setChannelMuted(channelId: String, muted: Boolean) {
        cache.setChannelMuted(channelId, muted)
    }

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
     * Forget the administrator session.
     *
     * The cached content is dropped with it. A cached list is a view of public
     * content, so leaving it would leak nothing — but the cache is also the
     * administrator's working set, and a fresh sign-in should not start from
     * someone else's screen.
     */
    fun adminSignOut() {
        tokens.clear()
        cache.clear()
    }

    suspend fun adminChannels(token: String): ApiResult<List<GoodPostChannel>> =
        api.adminChannels(token)

    suspend fun adminCreateChannel(token: String, body: JSONObject): ApiResult<GoodPostChannel> =
        api.adminCreateChannel(token, body)

    suspend fun adminUpdateChannel(
        token: String,
        channelId: String,
        body: JSONObject
    ): ApiResult<GoodPostChannel> = api.adminUpdateChannel(token, channelId, body)

    suspend fun adminCreatePost(
        token: String,
        channelId: String,
        body: JSONObject
    ): ApiResult<GoodPostPost> = api.adminCreatePost(token, channelId, body)

    suspend fun adminUpdatePost(
        token: String,
        postId: String,
        body: JSONObject
    ): ApiResult<GoodPostPost> = api.adminUpdatePost(token, postId, body)

    suspend fun adminDeletePost(token: String, postId: String): ApiResult<Unit> =
        api.adminDeletePost(token, postId)

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
        token: String,
        attachment: GoodPostAttachment
    ): ApiResult<GoodPostMedia> {
        val upload = when (
            val presign = api.adminRequestUpload(
                token = token,
                contentType = attachment.contentType,
                byteSize = attachment.byteSize,
                width = attachment.width,
                height = attachment.height,
                durationMs = attachment.durationMs
            )
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
        return api.adminConfirmUpload(token, upload.mediaId)
    }
}
