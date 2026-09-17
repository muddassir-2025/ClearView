package com.muddassir.clearview.goodpost.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The outcome of a channels call.
 *
 * [Ok] and [Stale] are distinct because §36 requires the app not to claim
 * success it has not been told about: showing a saved list is fine, showing it
 * as though it were live is not, and the user is told which they are looking
 * at.
 */
sealed interface ChannelsResult<out T> {
    /** Fresh from the server. */
    data class Ok<T>(val value: T) : ChannelsResult<T>

    /** The server could not be reached; this is a saved copy (§36). */
    data class Stale<T>(val value: T) : ChannelsResult<T>

    /** The server refused, with a code the UI words. */
    data class Failed(val code: String) : ChannelsResult<Nothing>

    /** No usable session — the sign-in gate takes over. */
    data object SignedOut : ChannelsResult<Nothing>
}

/** What the server reports after a follow or unfollow. */
data class FollowState(val following: Boolean, val followerCount: Int)

/**
 * Channels and discovery (§5, §6, §7, §12).
 *
 * Sits between the ViewModel and the network, and owns three things the UI
 * must not:
 *
 *  - **The session.** Every call goes through [GoodPostAuthRepository.validSession],
 *    so an expired access token is refreshed rather than surfacing as a 401 the
 *    user cannot act on. A refused refresh resolves to [ChannelsResult.SignedOut]
 *    and the gate takes over.
 *  - **Offline fallback.** A failed read returns the cached copy when there is
 *    one, so previously fetched channels stay readable with no network (§36).
 *  - **Cache maintenance.** Writes update the cache as well as the screen, so
 *    the next offline open reflects the last thing the server confirmed.
 *
 * Every mutating call returns only after the server has confirmed it. Nothing
 * here reports a follow, mute or block as done on the strength of local intent
 * (§36).
 */
class GoodPostChannelsRepository(
    context: Context,
    private val auth: GoodPostAuthRepository = GoodPostAuthRepository(context),
    private val api: GoodPostApi = GoodPostApi()
) {

    private val cache = GoodPostChannelsCache(context)

    /** False when this build has no Good Post backend URL configured. */
    val isConfigured: Boolean get() = api.isConfigured

    // ── Reads ────────────────────────────────────────────────────────────

    /** §4: channels the caller follows, most recently active first. */
    suspend fun following(): ChannelsResult<GoodPostChannelPage> {
        val session = auth.validSession() ?: return ChannelsResult.SignedOut

        return when (val result = api.followingChannels(session.accessToken)) {
            is ApiResult.Ok -> {
                val page = GoodPostChannelCodec.page(result.value)
                cache.saveFollowing(page, System.currentTimeMillis())
                ChannelsResult.Ok(page)
            }

            is ApiResult.Failed -> {
                if (result.status == 401) return ChannelsResult.SignedOut
                val saved = cache.loadFollowing()
                // A 5xx is the server having a bad moment rather than a
                // refusal of this request, so a saved list beats an error. It
                // is reported as stale, never as fresh.
                if (result.status >= 500 && saved != null) ChannelsResult.Stale(saved.page)
                else ChannelsResult.Failed(result.code)
            }

            ApiResult.Unreachable ->
                cache.loadFollowing()?.let { ChannelsResult.Stale(it.page) }
                    ?: ChannelsResult.Failed("unreachable")
        }
    }

    /**
     * §6/§7: the channels this account OWNS, not the ones it follows.
     *
     * One channel per account is the current product rule, so the home screen
     * asks this before it offers a "create channel" action: an account that
     * already owns one is told so, rather than being walked through a composer
     * that the server would answer with `channel_limit_reached`.
     *
     * Deliberately not cached. It is a small response, it is what decides
     * whether a primary action is shown at all, and a stale "you own nothing"
     * is exactly the state that produces a pointless rejection.
     */
    suspend fun managed(): ChannelsResult<List<GoodPostChannel>> {
        val session = auth.validSession() ?: return ChannelsResult.SignedOut

        return when (val result = api.managedChannels(session.accessToken)) {
            is ApiResult.Ok ->
                ChannelsResult.Ok(GoodPostChannelCodec.page(result.value).items)

            is ApiResult.Failed ->
                if (result.status == 401) ChannelsResult.SignedOut
                else ChannelsResult.Failed(result.code)

            ApiResult.Unreachable -> ChannelsResult.Failed("unreachable")
        }
    }

    /**
     * §5: discovery, with search, category and sort filters.
     *
     * @param cursor null for the first page. Only the FIRST page is cached:
     *   the cache key is the query, so writing page two over page one would
     *   leave the offline view starting mid-list.
     */
    suspend fun discover(
        query: String? = null,
        category: String? = null,
        sort: ChannelSort = ChannelSort.Popular,
        cursor: String? = null
    ): ChannelsResult<GoodPostChannelPage> {
        val session = auth.validSession() ?: return ChannelsResult.SignedOut
        val signature = signatureOf(query, category, sort)

        return when (
            val result = api.discoverChannels(
                accessToken = session.accessToken,
                query = query,
                category = category,
                sort = sort,
                cursor = cursor
            )
        ) {
            is ApiResult.Ok -> {
                val page = GoodPostChannelCodec.page(result.value)
                if (cursor.isNullOrBlank()) {
                    cache.saveDiscover(signature, page, System.currentTimeMillis())
                }
                ChannelsResult.Ok(page)
            }

            is ApiResult.Failed -> {
                if (result.status == 401) return ChannelsResult.SignedOut
                val saved = cache.loadDiscover(signature)
                if (result.status >= 500 && saved != null) ChannelsResult.Stale(saved.page)
                else ChannelsResult.Failed(result.code)
            }

            ApiResult.Unreachable ->
                cache.loadDiscover(signature)?.let { ChannelsResult.Stale(it.page) }
                    ?: ChannelsResult.Failed("unreachable")
        }
    }

    /** §5: categories, cached because they change rarely and are needed offline. */
    suspend fun categories(): List<GoodPostCategory> {
        val session = auth.validSession() ?: return cache.loadCategories()

        return when (val result = api.channelCategories(session.accessToken)) {
            is ApiResult.Ok -> {
                val categories = GoodPostChannelCodec.categories(result.value)
                if (categories.isNotEmpty()) cache.saveCategories(categories)
                categories
            }

            // Categories are a nicety, not a gate: fall back silently rather
            // than turning the whole Discover screen into an error.
            is ApiResult.Failed -> cache.loadCategories()
            ApiResult.Unreachable -> cache.loadCategories()
        }
    }

    suspend fun channel(channelId: String): ChannelsResult<GoodPostChannel> {
        val session = auth.validSession() ?: return ChannelsResult.SignedOut

        return when (val result = api.channelDetail(session.accessToken, channelId)) {
            is ApiResult.Ok ->
                GoodPostChannelCodec.single(result.value)
                    ?.let { ChannelsResult.Ok(it) }
                    // A 2xx we cannot parse is a contract break, not a success.
                    ?: ChannelsResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)

            ApiResult.Unreachable -> {
                // Fall back to whatever is already cached so a channel opened
                // offline is not a blank screen.
                val cached = cachedChannel(channelId)
                if (cached != null) ChannelsResult.Stale(cached)
                else ChannelsResult.Failed("unreachable")
            }
        }
    }

    /**
     * §6 resolve a share link (`clearview://goodpost/channel/<slug>`).
     *
     * Offline, the followed channels already saved are searched by slug, so a
     * link to a channel the user follows still opens without a network. A slug
     * that was never fetched has nothing saved and reports `unreachable` rather
     * than inventing a channel (§36).
     */
    suspend fun channelBySlug(slug: String): ChannelsResult<GoodPostChannel> {
        val session = auth.validSession() ?: return ChannelsResult.SignedOut

        return when (val result = api.channelBySlug(session.accessToken, slug)) {
            is ApiResult.Ok ->
                GoodPostChannelCodec.single(result.value)
                    ?.let { ChannelsResult.Ok(it) }
                    ?: ChannelsResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)

            ApiResult.Unreachable -> {
                val cached = cachedChannelBySlug(slug)
                if (cached != null) ChannelsResult.Stale(cached)
                else ChannelsResult.Failed("unreachable")
            }
        }
    }

    // ── Writes ───────────────────────────────────────────────────────────
    // Each returns only after the server confirms (§36). The small response
    // bodies are read inline: they are one- or two-field confirmations, not
    // channel payloads, so they have no codec of their own.

    suspend fun createChannel(
        name: String,
        description: String?,
        categorySlug: String?,
        countryCode: String? = null
    ): ChannelsResult<GoodPostChannel> {
        val session = auth.validSession() ?: return ChannelsResult.SignedOut

        val body = org.json.JSONObject().apply {
            put("name", name.trim())
            description?.takeIf { it.isNotBlank() }?.let { put("description", it.trim()) }
            categorySlug?.takeIf { it.isNotBlank() }?.let { put("categorySlug", it) }
            countryCode?.takeIf { it.isNotBlank() }?.let { put("countryCode", it.uppercase()) }
        }

        return when (val result = api.createChannel(session.accessToken, body)) {
            is ApiResult.Ok ->
                GoodPostChannelCodec.single(result.value)
                    ?.let { ChannelsResult.Ok(it) }
                    ?: ChannelsResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> ChannelsResult.Failed("unreachable")
        }
    }

    suspend fun updateChannel(
        channelId: String,
        name: String? = null,
        description: String? = null,
        clearDescription: Boolean = false,
        categorySlug: String? = null,
        /**
         * §16's "allow follower messages" switch.
         *
         * A nullable Boolean rather than a defaulted one, because the server
         * distinguishes "leave it as it was" from "turn it off" — collapsing
         * the two would silently disable messages every time an owner fixed a
         * typo in their channel name.
         */
        allowFollowerMessages: Boolean? = null
    ): ChannelsResult<GoodPostChannel> {
        val session = auth.validSession() ?: return ChannelsResult.SignedOut

        val body = org.json.JSONObject().apply {
            name?.let { put("name", it.trim()) }
            // An explicit null clears the field; omitting it leaves it alone.
            // The backend distinguishes the two, so the client must too.
            if (clearDescription) put("description", org.json.JSONObject.NULL)
            else description?.let { put("description", it.trim()) }
            categorySlug?.let { put("categorySlug", it) }
            allowFollowerMessages?.let { put("allowFollowerMessages", it) }
        }

        return when (val result = api.updateChannel(session.accessToken, channelId, body)) {
            is ApiResult.Ok ->
                GoodPostChannelCodec.single(result.value)
                    ?.let { ChannelsResult.Ok(it) }
                    ?: ChannelsResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> ChannelsResult.Failed("unreachable")
        }
    }

    suspend fun setFollowing(channelId: String, follow: Boolean): ChannelsResult<FollowState> {
        val session = auth.validSession() ?: return ChannelsResult.SignedOut

        return when (val result = api.setFollow(session.accessToken, channelId, follow)) {
            is ApiResult.Ok -> ChannelsResult.Ok(
                FollowState(
                    following = result.value.optBoolean("following", follow),
                    followerCount = result.value.optInt("followerCount", 0)
                )
            )

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> ChannelsResult.Failed("unreachable")
        }
    }

    /** §17: returns the resulting state as the server confirmed it. */
    suspend fun setNotifications(channelId: String, enabled: Boolean): ChannelsResult<Boolean> {
        val session = auth.validSession() ?: return ChannelsResult.SignedOut

        return when (val result = api.setNotifications(session.accessToken, channelId, enabled)) {
            is ApiResult.Ok ->
                ChannelsResult.Ok(result.value.optBoolean("notificationsEnabled", enabled))

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> ChannelsResult.Failed("unreachable")
        }
    }

    suspend fun setBlocked(channelId: String, blocked: Boolean): ChannelsResult<Boolean> {
        val session = auth.validSession() ?: return ChannelsResult.SignedOut

        return when (val result = api.setBlocked(session.accessToken, channelId, blocked)) {
            is ApiResult.Ok -> ChannelsResult.Ok(result.value.optBoolean("blocked", blocked))
            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> ChannelsResult.Failed("unreachable")
        }
    }

    /**
     * §4: mark a channel read.
     *
     * Best-effort by design. The screen has already cleared the badge locally,
     * and a failure here means the badge returns next time — annoying, and far
     * better than an error dialog over a read receipt.
     */
    suspend fun markRead(channelId: String) {
        val session = auth.validSession() ?: return
        api.markRead(session.accessToken, channelId)
    }

    /** Forget cached channel data. Called on sign-out, with the token store. */
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
     * touch, and those need an explanation rather than a sign-in screen.
     *
     * Deliberately NOT generic over the cached value: the cache fallback for a
     * 5xx belongs at each call site, where the value's type is known. Doing it
     * here needed an `as T` that would throw the first time a caller passed a
     * cache of the wrong shape.
     */
    private fun failure(status: Int, code: String): ChannelsResult<Nothing> =
        if (status == 401) ChannelsResult.SignedOut else ChannelsResult.Failed(code)

    /**
     * Find a channel already saved locally, for an offline detail view.
     *
     * Only the follow list is searched. Discover results are stored under one
     * key per query and cannot be enumerated cheaply, and a channel the user
     * follows — the one they are most likely to reopen — is in here.
     */
    private fun cachedChannel(channelId: String): GoodPostChannel? =
        cache.loadFollowing()?.page?.items?.firstOrNull { it.id == channelId }

    /**
     * The same search as [cachedChannel], by slug.
     *
     * A share link is resolved OFFLINE against the followed list for the same
     * reason a tapped channel is: the cache is keyed by channel id, and the
     * slug is the only key a link carries.
     */
    private fun cachedChannelBySlug(slug: String): GoodPostChannel? =
        cache.loadFollowing()?.page?.items?.firstOrNull { it.slug == slug }

    /** A stable key for one Discover query, so two searches cannot collide. */
    private fun signatureOf(query: String?, category: String?, sort: ChannelSort): String =
        listOf(
            sort.wire,
            category.orEmpty().lowercase(),
            query.orEmpty().trim().lowercase()
        ).joinToString("|")
}
