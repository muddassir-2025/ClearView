package com.muddassir.clearview.goodpost.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * The outcome of a posts call.
 *
 * [Ok] and [Stale] are distinct for the same reason they are in
 * [ChannelsResult]: §36 requires the app not to present saved content as
 * though it were live, so the distinction has to survive as far as the UI.
 */
sealed interface PostsResult<out T> {
    /** Fresh from the server. */
    data class Ok<T>(val value: T) : PostsResult<T>

    /** The server could not be reached; this is a saved copy (§36). */
    data class Stale<T>(val value: T) : PostsResult<T>

    /** The server refused, with a code the UI words. */
    data class Failed(val code: String) : PostsResult<Nothing>

    /** No usable session — the sign-in gate takes over. */
    data object SignedOut : PostsResult<Nothing>
}

/**
 * One file to upload (§9).
 *
 * Deliberately not an Android `Uri`: the caller opens the stream, so this
 * repository never touches a `ContentResolver`, and the upload lifecycle —
 * which is the part that can be wrong — is drivable in a unit test without a
 * device. [open] is a factory so the stream's lifetime belongs to whoever
 * created it.
 */
data class MediaUploadRequest(
    val contentType: String,
    val byteSize: Long,
    val open: () -> InputStream
)

/**
 * Posts, the feed and media (§4, §7, §8, §9, §10).
 *
 * Owns the same three things [GoodPostChannelsRepository] owns, for the same
 * reasons: the session (so an expired token is refreshed rather than surfacing
 * as a 401 the user cannot act on), the offline fallback (so a previously
 * fetched feed stays readable), and cache maintenance (so the offline copy
 * reflects the last thing the server confirmed).
 *
 * Two rules it holds to:
 *
 *  * **A write is reported only after the server confirms it** (§36). Nothing
 *    here claims a post was published, edited or removed on the strength of
 *    local intent — a post the user believes is live and is not is the worst
 *    failure this feature has.
 *  * **Nothing is downloaded by itself** (§10). Media is fetched to disk only
 *    when [saveMedia] is called from an explicit user action.
 */
class GoodPostPostsRepository(
    context: Context,
    private val auth: GoodPostAuthRepository = GoodPostAuthRepository(context),
    private val api: GoodPostApi = GoodPostApi()
) {

    private val cache = GoodPostPostsCache(context)
    private val media = GoodPostMediaStore(context)

    /** False when this build has no Good Post backend URL configured. */
    val isConfigured: Boolean get() = api.isConfigured

    // ── Reads ────────────────────────────────────────────────────────────

    /** §4 the aggregated feed: posts from every channel the caller follows. */
    suspend fun feed(cursor: String? = null): PostsResult<GoodPostPostPage> {
        val session = auth.validSession() ?: return PostsResult.SignedOut

        return when (val result = api.feed(session.accessToken, cursor)) {
            is ApiResult.Ok -> {
                val page = GoodPostPostCodec.page(result.value)
                // Only the first page is cached. Writing page two over page one
                // would leave the offline view starting mid-list.
                if (cursor.isNullOrBlank()) {
                    cache.saveFeed(page, System.currentTimeMillis())
                }
                PostsResult.Ok(page)
            }

            is ApiResult.Failed -> {
                if (result.status == 401) return PostsResult.SignedOut
                val saved = cache.loadFeed()
                // A 5xx is the server having a bad moment rather than a
                // refusal of this request, so a saved list beats an error — and
                // it is reported as stale, never as fresh.
                if (result.status >= 500 && saved != null) PostsResult.Stale(saved.page)
                else PostsResult.Failed(result.code)
            }

            ApiResult.Unreachable ->
                cache.loadFeed()?.let { PostsResult.Stale(it.page) }
                    ?: PostsResult.Failed("unreachable")
        }
    }

    /** §8 one channel's history, newest first. */
    suspend fun channelPosts(
        channelId: String,
        cursor: String? = null
    ): PostsResult<GoodPostPostPage> {
        val session = auth.validSession() ?: return PostsResult.SignedOut

        return when (val result = api.channelPosts(session.accessToken, channelId, cursor)) {
            is ApiResult.Ok -> {
                val page = GoodPostPostCodec.page(result.value)
                if (cursor.isNullOrBlank()) {
                    cache.saveHistory(channelId, page, System.currentTimeMillis())
                }
                PostsResult.Ok(page)
            }

            is ApiResult.Failed -> {
                if (result.status == 401) return PostsResult.SignedOut
                val saved = cache.loadHistory(channelId)
                if (result.status >= 500 && saved != null) PostsResult.Stale(saved.page)
                else PostsResult.Failed(result.code)
            }

            ApiResult.Unreachable ->
                cache.loadHistory(channelId)?.let { PostsResult.Stale(it.page) }
                    ?: PostsResult.Failed("unreachable")
        }
    }

    // ── Writes (§7, §8) ──────────────────────────────────────────────────

    /**
     * Publish a post.
     *
     * The `type` is NOT sent. The server derives it from the media (or from the
     * presence of a link), so a client cannot describe its own post wrongly and
     * have every reader render it wrongly.
     */
    suspend fun publish(
        channelId: String,
        body: String? = null,
        linkUrl: String? = null,
        linkTitle: String? = null,
        mediaIds: List<String> = emptyList()
    ): PostsResult<GoodPostPost> {
        val session = auth.validSession() ?: return PostsResult.SignedOut

        val payload = JSONObject().apply {
            body?.trim()?.takeIf { it.isNotEmpty() }?.let { put("body", it) }
            linkUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { put("linkUrl", it) }
            linkTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { put("linkTitle", it) }
            if (mediaIds.isNotEmpty()) {
                put("mediaIds", org.json.JSONArray(mediaIds))
            }
        }

        return when (val result = api.publishPost(session.accessToken, channelId, payload)) {
            is ApiResult.Ok ->
                GoodPostPostCodec.single(result.value)
                    ?.let { PostsResult.Ok(it) }
                    // A 2xx we cannot parse is a contract break, not a success.
                    ?: PostsResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> PostsResult.Failed("unreachable")
        }
    }

    /**
     * Edit a post's text or link (§7).
     *
     * `null` clears a field; omitting the argument leaves it alone. The backend
     * distinguishes the two, so the client must too — otherwise "fix the
     * caption" and "remove the caption" would be the same request.
     */
    suspend fun edit(
        channelId: String,
        postId: String,
        body: String? = null,
        linkUrl: String? = null,
        linkTitle: String? = null
    ): PostsResult<GoodPostPost> {
        val session = auth.validSession() ?: return PostsResult.SignedOut

        val payload = JSONObject().apply {
            if (body != null) put("body", body.trim())
            if (linkUrl != null) put("linkUrl", linkUrl.trim())
            if (linkTitle != null) put("linkTitle", linkTitle.trim())
        }

        return when (val result = api.updatePost(session.accessToken, channelId, postId, payload)) {
            is ApiResult.Ok ->
                GoodPostPostCodec.single(result.value)
                    ?.let { PostsResult.Ok(it) }
                    ?: PostsResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> PostsResult.Failed("unreachable")
        }
    }

    /**
     * Remove a post (§7).
     *
     * The server soft-deletes it, so this is reversible on the server's side —
     * which is why the UI can offer it without a "this cannot be undone" trap,
     * and why the post is only dropped from the screen after the server agrees.
     */
    suspend fun remove(channelId: String, postId: String): PostsResult<Unit> {
        val session = auth.validSession() ?: return PostsResult.SignedOut

        return when (val result = api.deletePost(session.accessToken, channelId, postId)) {
            is ApiResult.Ok -> PostsResult.Ok(Unit)
            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> PostsResult.Failed("unreachable")
        }
    }

    // ── Media (§9, §10) ──────────────────────────────────────────────────

    /**
     * The whole upload lifecycle: presign → PUT → confirm (§9).
     *
     * Four steps, and the fourth is the one that matters: the row is only usable
     * by a publish once the server has confirmed the object is really in the
     * bucket. A client that skipped confirmation would produce a post whose
     * image 404s, with nothing to explain why.
     *
     * A failure at any step leaves nothing claimed. A presigned-but-unuploaded
     * row is invisible to every reader and is collected by the server's sweep
     * (§34), so an abandoned upload costs the user nothing but their time.
     */
    suspend fun uploadMedia(request: MediaUploadRequest): PostsResult<GoodPostMedia> {
        val session = auth.validSession() ?: return PostsResult.SignedOut

        val requested = JSONObject().apply {
            put("contentType", request.contentType)
            put("byteSize", request.byteSize)
        }

        val upload = when (val result = api.requestUpload(session.accessToken, requested)) {
            is ApiResult.Ok -> result.value.optJSONObject("upload")
            is ApiResult.Failed -> return failure(result.status, result.code)
            ApiResult.Unreachable -> return PostsResult.Failed("unreachable")
        } ?: return PostsResult.Failed("unreachable")

        val mediaId = upload.optString("mediaId")
        val uploadUrl = upload.optString("uploadUrl")
        if (mediaId.isBlank() || uploadUrl.isBlank()) return PostsResult.Failed("unreachable")

        // Straight to the bucket with the signed capability. No credential of
        // ours is involved, and the URL is scoped to this one object, this one
        // method and a few minutes.
        val sent = media.upload(uploadUrl, request.contentType, request.byteSize, request.open)
        if (!sent) return PostsResult.Failed("upload_failed")

        return when (val result = api.confirmUpload(session.accessToken, mediaId)) {
            is ApiResult.Ok ->
                GoodPostPostCodec.media(result.value)
                    ?.let { PostsResult.Ok(it) }
                    ?: PostsResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> PostsResult.Failed("unreachable")
        }
    }

    /**
     * A fresh presigned read URL (§10).
     *
     * Requested on demand rather than reused from a post payload, because that
     * URL was minted when the payload was fetched and may already be past its
     * expiry — a page open for an hour would otherwise hold dead links.
     */
    suspend fun freshMediaUrl(mediaId: String): PostsResult<String> {
        val session = auth.validSession() ?: return PostsResult.SignedOut

        return when (val result = api.mediaUrl(session.accessToken, mediaId)) {
            is ApiResult.Ok ->
                result.value.optString("url").takeIf { it.isNotBlank() }
                    ?.let { PostsResult.Ok(it) }
                    ?: PostsResult.Failed("media_unavailable")

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> PostsResult.Failed("unreachable")
        }
    }

    /**
     * Save one asset to the device (§10).
     *
     * Only ever called from an explicit user action. An existing copy is
     * returned as-is, so tapping "save" twice does not re-download a video.
     */
    suspend fun saveMedia(item: GoodPostMedia): PostsResult<File> {
        media.localFile(item.id, item.extension)
            .takeIf { it.isFile && it.length() > 0 }
            ?.let { return PostsResult.Ok(it) }

        val url = when (val result = freshMediaUrl(item.id)) {
            is PostsResult.Ok -> result.value
            is PostsResult.Failed -> return PostsResult.Failed(result.code)
            // A media URL is never served from a cache, so there is nothing to
            // be stale about — treated as unreachable rather than pretending
            // otherwise, so the state stays honest.
            is PostsResult.Stale -> return PostsResult.Failed("unreachable")
            PostsResult.SignedOut -> return PostsResult.SignedOut
        }

        return media.download(item.id, item.extension, url)
            ?.let { PostsResult.Ok(it) }
            ?: PostsResult.Failed("download_failed")
    }

    /** The saved copy of an asset, without touching the network. */
    fun localCopy(item: GoodPostMedia): File? =
        media.localFile(item.id, item.extension).takeIf { it.isFile && it.length() > 0 }

    /** Bytes used by saved media. */
    fun savedBytes(): Long = media.usedBytes()

    /** Forget cached feed and history. Called on sign-out, with the token store. */
    fun clearCache() {
        cache.clear()
    }

    // ── Internals ────────────────────────────────────────────────────────

    /**
     * Turn a refusal into a result.
     *
     * 401 means the session died between the refresh check and this call, so
     * the gate takes over. Everything else is passed through as a code for the
     * UI to word — 403 is a ban, a suspension, or a channel the caller may not
     * post in, and each of those needs an explanation rather than a sign-in
     * screen. `edit_window_closed` and `media_not_ready` arrive the same way.
     */
    private fun failure(status: Int, code: String): PostsResult<Nothing> =
        if (status == 401) PostsResult.SignedOut else PostsResult.Failed(code)
}
