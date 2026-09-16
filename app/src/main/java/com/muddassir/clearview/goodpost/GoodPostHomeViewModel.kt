package com.muddassir.clearview.goodpost

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muddassir.clearview.goodpost.data.ChannelSort
import com.muddassir.clearview.goodpost.data.ChannelsResult
import com.muddassir.clearview.goodpost.data.GoodPostCategory
import com.muddassir.clearview.goodpost.data.GoodPostChannel
import com.muddassir.clearview.goodpost.data.GoodPostChannelCodec
import com.muddassir.clearview.goodpost.data.GoodPostChannelsRepository
import com.muddassir.clearview.goodpost.data.GoodPostMedia
import com.muddassir.clearview.goodpost.data.GoodPostPost
import com.muddassir.clearview.goodpost.data.GoodPostPostCodec
import com.muddassir.clearview.goodpost.data.GoodPostPostsRepository
import com.muddassir.clearview.goodpost.data.PostsResult
import com.muddassir.clearview.goodpost.data.uploadRequestFor
import kotlinx.coroutines.launch

/**
 * The parts of Good Post home (§4, §5).
 *
 * [Posts] is first because it is what a returning user opens: §4's two viewing
 * modes are the aggregated feed and the channel list, and Discover is the way
 * in for someone who has not followed anything yet.
 */
enum class GoodPostSection { Posts, Channels, Discover }

/**
 * State for the signed-in Good Post tab.
 *
 * A channel detail is an overlay rather than a third section: it is opened
 * from either list and returning must land back where the user was, which one
 * nullable field expresses without a navigation stack.
 */
data class GoodPostHomeUiState(
    val section: GoodPostSection = GoodPostSection.Channels,
    val following: List<GoodPostChannel> = emptyList(),
    val discover: List<GoodPostChannel> = emptyList(),
    val categories: List<GoodPostCategory> = emptyList(),
    val query: String = "",
    /** The query that produced [discover]; typing alone must not refetch. */
    val appliedQuery: String = "",
    val category: String? = null,
    val sort: ChannelSort = ChannelSort.Popular,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** Showing saved data because the server was unreachable (§36). */
    val stale: Boolean = false,
    val nextCursor: String? = null,
    val channel: GoodPostChannel? = null,
    /** Set while a follow/mute/block call is in flight, to disable its button. */
    val busyChannelId: String? = null,
    val creating: Boolean = false,
    val editing: Boolean = false,
    /** A backend code from an action, worded by the UI. */
    val messageCode: String? = null,
    /** The session died; the tab returns to the sign-in gate. */
    val signedOut: Boolean = false,

    // ── The aggregated feed (§4) ────────────────────────────────────────
    val feed: List<GoodPostPost> = emptyList(),
    val feedCursor: String? = null,
    /** Showing saved posts because the server was unreachable (§36). */
    val feedStale: Boolean = false,

    // ── One channel's history (§8) ──────────────────────────────────────
    val channelPosts: List<GoodPostPost> = emptyList(),
    /**
     * Which channel [channelPosts] belongs to.
     *
     * Carried explicitly so opening channel B before A's request returned
     * cannot paint A's posts under B's header. A bare list would leave that
     * race indistinguishable from a correct result.
     */
    val channelPostsChannelId: String? = null,
    val channelPostsStale: Boolean = false,

    // ── The composer (§8) ───────────────────────────────────────────────
    val composerOpen: Boolean = false,
    /** The channel the open composer posts to. */
    val composerChannelId: String? = null,
    val composerBody: String = "",
    val composerLink: String = "",
    val composerLinkTitle: String = "",
    /** Uploaded and confirmed, so all of these are attachable (§9). */
    val composerAttachments: List<GoodPostMedia> = emptyList(),
    val uploadingAttachment: Boolean = false,
    val publishing: Boolean = false,

    // ── Post actions ────────────────────────────────────────────────────
    /** The post whose edit form is open, or null. */
    val editingPostId: String? = null,
    val editingPostBody: String = "",
    /** The post whose row is waiting on the server, so its buttons are off. */
    val busyPostId: String? = null,
    /**
     * Media ids with a saved copy on this device (§10).
     *
     * Ids rather than files: the UI only needs to know whether to offer "save"
     * or to say it already has one, and holding `File` objects in Compose state
     * would make every recomposition compare paths.
     */
    val savedMediaIds: Set<String> = emptySet(),
    val savingMediaId: String? = null
)

/**
 * Channels and discovery (§5, §6, §7, §12).
 *
 * §35's separation holds: this holds state and calls the repository, and the
 * composables call this. No HTTP, no JSON and no persistence logic lives in
 * the UI, and no Compose import reaches the data layer.
 *
 * Every mutation is applied only after the server confirms it (§36). The UI
 * disables the control it is waiting on rather than optimistically pretending,
 * because a follow that silently failed is worse than a follow that visibly
 * took a moment.
 */
class GoodPostHomeViewModel : ViewModel() {

    private var repository: GoodPostChannelsRepository? = null
    private var postsRepository: GoodPostPostsRepository? = null

    /**
     * Application context, held for the one job the UI cannot do for it: turning
     * a picked `content://` Uri into bytes to upload. It is the application
     * context, so holding it for the ViewModel's lifetime leaks nothing.
     */
    private var appContext: Context? = null

    var uiState by mutableStateOf(GoodPostHomeUiState())
        private set

    private val repo: GoodPostChannelsRepository? get() = repository
    private val posts: GoodPostPostsRepository? get() = postsRepository

    /** Idempotent: the tab can be left and re-entered without refetching. */
    fun initialize(context: Context) {
        if (repository != null) return
        appContext = context.applicationContext
        repository = GoodPostChannelsRepository(context.applicationContext)
        postsRepository = GoodPostPostsRepository(context.applicationContext)
        loadCategories()
        loadFollowing()
        loadFeed()
        // A share link can arrive before the tab has composed — a cold start
        // delivers it with the launch intent — so anything still waiting for
        // the repository is drained here rather than dropped.
        openPendingChannel()
    }

    // ── Sections ────────────────────────────────────────────────────────

    fun selectSection(section: GoodPostSection) {
        if (uiState.section == section) return
        uiState = uiState.copy(section = section, messageCode = null)

        // Discover is loaded on first open rather than at startup: an
        // unauthenticated-looking empty tab costs a request nobody asked for.
        if (section == GoodPostSection.Discover && uiState.discover.isEmpty()) {
            search()
        }
        // The feed, unlike Discover, is loaded at startup — it is the tab a
        // returning user lands on — so entering it only refetches when the
        // first attempt failed or there is nothing to show.
        if (section == GoodPostSection.Posts && uiState.feed.isEmpty()) {
            loadFeed()
        }
    }

    // ── Channels list (§4) ──────────────────────────────────────────────

    fun refresh() {
        when (uiState.section) {
            GoodPostSection.Posts -> {
                loadFeed()
                // Refreshing the feed while a channel is open also refreshes
                // that channel, so the two cannot show different post sets.
                if (uiState.channel != null) loadChannelPosts()
            }

            GoodPostSection.Channels -> loadFollowing()
            GoodPostSection.Discover -> search()
        }
    }

    private fun loadFollowing() {
        val source = repo ?: return
        viewModelScope.launch {
            uiState = uiState.copy(loading = true, messageCode = null)

            when (val result = source.following()) {
                is ChannelsResult.Ok -> uiState = uiState.copy(
                    following = result.value.items,
                    loading = false,
                    stale = false,
                    nextCursor = null
                )

                is ChannelsResult.Stale -> uiState = uiState.copy(
                    following = result.value.items,
                    loading = false,
                    stale = true
                )

                is ChannelsResult.Failed ->
                    uiState = uiState.copy(loading = false, messageCode = result.code)

                ChannelsResult.SignedOut -> uiState = uiState.copy(
                    loading = false,
                    signedOut = true
                )
            }
        }
    }

    private fun loadCategories() {
        val source = repo ?: return
        viewModelScope.launch {
            val categories = source.categories()
            if (categories.isNotEmpty()) {
                uiState = uiState.copy(categories = categories)
            }
        }
    }

    // ── Discover (§5) ───────────────────────────────────────────────────

    fun onQueryChange(value: String) {
        uiState = uiState.copy(query = value, messageCode = null)
    }

    /** Run the search now, rather than on every keystroke. */
    fun search() {
        val source = repo ?: return
        val query = uiState.query.trim()
        viewModelScope.launch {
            uiState = uiState.copy(loading = true, messageCode = null, appliedQuery = query)

            when (val result = source.discover(query.ifBlank { null }, uiState.category, uiState.sort)) {
                is ChannelsResult.Ok -> uiState = uiState.copy(
                    discover = result.value.items,
                    nextCursor = result.value.nextCursor,
                    loading = false,
                    stale = false
                )

                is ChannelsResult.Stale -> uiState = uiState.copy(
                    discover = result.value.items,
                    loading = false,
                    stale = true
                )

                is ChannelsResult.Failed ->
                    uiState = uiState.copy(loading = false, messageCode = result.code)

                ChannelsResult.SignedOut -> uiState = uiState.copy(
                    loading = false,
                    signedOut = true
                )
            }
        }
    }

    fun selectCategory(slug: String?) {
        uiState = uiState.copy(category = slug)
        search()
    }

    fun selectSort(sort: ChannelSort) {
        uiState = uiState.copy(sort = sort)
        search()
    }

    /**
     * Append the next page.
     *
     * Deduplicated on merge, so a channel that moved between pages while
     * paging cannot appear twice (§5's ranking changes as followers arrive).
     */
    fun loadMore() {
        val source = repo ?: return
        val cursor = uiState.nextCursor ?: return
        if (uiState.loadingMore) return

        viewModelScope.launch {
            uiState = uiState.copy(loadingMore = true)

            when (
                val result = source.discover(
                    query = uiState.appliedQuery.ifBlank { null },
                    category = uiState.category,
                    sort = uiState.sort,
                    cursor = cursor
                )
            ) {
                is ChannelsResult.Ok -> uiState = uiState.copy(
                    discover = GoodPostChannelCodec.mergePage(uiState.discover, result.value.items),
                    nextCursor = result.value.nextCursor,
                    loadingMore = false
                )

                // Paging is a continuation, not a fresh screen: a failure here
                // must not replace a good list with an error.
                is ChannelsResult.Failed -> uiState = uiState.copy(
                    loadingMore = false,
                    messageCode = result.code
                )

                is ChannelsResult.Stale -> uiState = uiState.copy(loadingMore = false)

                ChannelsResult.SignedOut -> uiState = uiState.copy(
                    loadingMore = false,
                    signedOut = true
                )
            }
        }
    }

    // ── Channel detail ──────────────────────────────────────────────────

    fun open(channelId: String) {
        val source = repo ?: return
        viewModelScope.launch {
            uiState = uiState.copy(loading = true, messageCode = null)

            when (val result = source.channel(channelId)) {
                is ChannelsResult.Ok -> adopt(result.value, stale = false, source = source)
                is ChannelsResult.Stale -> adopt(result.value, stale = true, source = source)

                is ChannelsResult.Failed ->
                    uiState = uiState.copy(loading = false, messageCode = result.code)

                ChannelsResult.SignedOut -> uiState = uiState.copy(
                    loading = false,
                    signedOut = true
                )
            }
        }
    }

    /**
     * Open a channel from a share link (§6).
     *
     * The request is HELD when the repository is not ready rather than dropped:
     * a cold start delivers the link in the launch intent, quite possibly before
     * this ViewModel has been initialised, and a link that only works when the
     * app happens to be warm is worse than no link at all.
     */
    fun requestOpenChannel(slug: String) {
        val trimmed = slug.trim()
        if (trimmed.isEmpty()) return
        pendingSlug = trimmed
        openPendingChannel()
    }

    /** A slug whose repository was not ready yet, if any. */
    private var pendingSlug: String? = null

    private fun openPendingChannel() {
        val slug = pendingSlug ?: return
        val source = repo ?: return
        // Cleared before the request so a second link arriving mid-flight
        // replaces this one instead of queueing behind it.
        pendingSlug = null

        viewModelScope.launch {
            uiState = uiState.copy(loading = true, messageCode = null)

            when (val result = source.channelBySlug(slug)) {
                is ChannelsResult.Ok -> adopt(result.value, stale = false, source = source)
                is ChannelsResult.Stale -> adopt(result.value, stale = true, source = source)

                // A dead link surfaces the backend's own code, which the UI
                // words as a dead link rather than as a generic failure.
                is ChannelsResult.Failed ->
                    uiState = uiState.copy(loading = false, messageCode = result.code)

                ChannelsResult.SignedOut -> uiState = uiState.copy(
                    loading = false,
                    signedOut = true
                )
            }
        }
    }

    /**
     * Adopt a channel the server just returned as the open one.
     *
     * Shared by the in-app tap and the share link because both mean the same
     * thing to the server: this channel is being read now. An offline open clears
     * the badge locally WITHOUT telling the server it was read — a receipt the
     * server cannot receive is not a receipt (§36).
     */
    private suspend fun adopt(
        channel: GoodPostChannel,
        stale: Boolean,
        source: GoodPostChannelsRepository
    ) {
        uiState = uiState.copy(channel = channel, loading = false, stale = stale)
        clearUnreadLocally(channel.id)
        if (!stale) source.markRead(channel.id)
    }

    fun closeDetail() {
        uiState = uiState.copy(channel = null, messageCode = null)
    }

    fun toggleFollow() {
        val channel = uiState.channel ?: return
        mutate(channel) { source ->
            when (val result = source.setFollowing(channel.id, !channel.isFollowing)) {
                is ChannelsResult.Ok -> {
                    val updated = channel.copy(
                        isFollowing = result.value.following,
                        followerCount = result.value.followerCount,
                        // Notifications only mean something while following.
                        notificationsEnabled = if (result.value.following) {
                            channel.notificationsEnabled
                        } else {
                            false
                        }
                    )
                    uiState = if (result.value.following) {
                        uiState.copy(
                            channel = updated,
                            following = GoodPostChannelCodec.mergePage(uiState.following, listOf(updated)),
                            messageCode = null
                        )
                    } else {
                        uiState.copy(
                            channel = updated,
                            following = GoodPostChannelCodec.remove(uiState.following, updated.id),
                            messageCode = null
                        )
                    }
                }

                is ChannelsResult.Failed -> uiState = uiState.copy(messageCode = result.code)
                is ChannelsResult.Stale -> Unit
                ChannelsResult.SignedOut -> uiState = uiState.copy(signedOut = true)
            }
        }
    }

    fun toggleMute() {
        val channel = uiState.channel ?: return
        if (!channel.isFollowing) {
            uiState = uiState.copy(messageCode = "not_following")
            return
        }

        mutate(channel) { source ->
            when (val result = source.setNotifications(channel.id, !channel.notificationsEnabled)) {
                is ChannelsResult.Ok -> {
                    val updated = channel.copy(notificationsEnabled = result.value)
                    uiState = uiState.copy(
                        channel = updated,
                        following = GoodPostChannelCodec.replace(uiState.following, updated),
                        messageCode = null
                    )
                }

                is ChannelsResult.Failed -> uiState = uiState.copy(messageCode = result.code)
                is ChannelsResult.Stale -> Unit
                ChannelsResult.SignedOut -> uiState = uiState.copy(signedOut = true)
            }
        }
    }

    /**
     * Block or unblock (§12).
     *
     * Blocking also ends the follow server-side, so the channel is dropped from
     * both lists here too — leaving it in the Channels list would show a
     * followed channel the server no longer considers followed.
     */
    fun toggleBlock() {
        val channel = uiState.channel ?: return
        mutate(channel) { source ->
            when (val result = source.setBlocked(channel.id, !channel.isBlocked)) {
                is ChannelsResult.Ok -> {
                    val blocked = result.value
                    val updated = channel.copy(
                        isBlocked = blocked,
                        isFollowing = if (blocked) false else channel.isFollowing,
                        followerCount = if (blocked) 0 else channel.followerCount
                    )
                    uiState = uiState.copy(
                        channel = updated,
                        following = GoodPostChannelCodec.remove(uiState.following, channel.id),
                        discover = GoodPostChannelCodec.remove(uiState.discover, channel.id),
                        messageCode = null
                    )
                }

                is ChannelsResult.Failed -> uiState = uiState.copy(messageCode = result.code)
                is ChannelsResult.Stale -> Unit
                ChannelsResult.SignedOut -> uiState = uiState.copy(signedOut = true)
            }
        }
    }

    // ── Create / edit (§6, §7) ──────────────────────────────────────────

    fun startCreate() {
        uiState = uiState.copy(creating = true, messageCode = null)
    }

    fun cancelCreate() {
        uiState = uiState.copy(creating = false, messageCode = null)
    }

    fun createChannel(name: String, description: String?, categorySlug: String?) {
        val source = repo ?: return
        if (name.isBlank()) {
            uiState = uiState.copy(messageCode = "invalid_request")
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(loading = true, messageCode = null)

            when (val result = source.createChannel(name, description, categorySlug)) {
                is ChannelsResult.Ok -> {
                    uiState = uiState.copy(
                        creating = false,
                        loading = false,
                        channel = result.value,
                        messageCode = null
                    )
                    // A new channel starts in the Discover pool and in the
                    // owner's own list, so reflect it without a full refetch.
                    loadFollowing()
                }

                is ChannelsResult.Failed -> uiState = uiState.copy(
                    loading = false,
                    messageCode = result.code,
                    // The dialog stays open: the user's typed input is not
                    // thrown away over a rejected category or a name clash.
                    creating = true
                )

                is ChannelsResult.Stale -> uiState = uiState.copy(loading = false)
                ChannelsResult.SignedOut -> uiState = uiState.copy(
                    loading = false,
                    signedOut = true
                )
            }
        }
    }

    fun startEdit() {
        if (uiState.channel?.isOwner != true) {
            uiState = uiState.copy(messageCode = "channel_forbidden")
            return
        }
        uiState = uiState.copy(editing = true, messageCode = null)
    }

    fun cancelEdit() {
        uiState = uiState.copy(editing = false, messageCode = null)
    }

    fun saveEdit(name: String, description: String?, clearDescription: Boolean, categorySlug: String?) {
        val channel = uiState.channel ?: return
        val source = repo ?: return

        viewModelScope.launch {
            uiState = uiState.copy(loading = true, messageCode = null)

            val result = source.updateChannel(
                channelId = channel.id,
                name = name.takeIf { it.isNotBlank() },
                description = description,
                clearDescription = clearDescription,
                categorySlug = categorySlug
            )

            when (result) {
                is ChannelsResult.Ok -> {
                    uiState = uiState.copy(
                        channel = result.value,
                        following = GoodPostChannelCodec.replace(uiState.following, result.value),
                        discover = GoodPostChannelCodec.replace(uiState.discover, result.value),
                        editing = false,
                        loading = false,
                        messageCode = null
                    )
                }

                is ChannelsResult.Failed -> uiState = uiState.copy(
                    loading = false,
                    messageCode = result.code,
                    editing = true
                )

                is ChannelsResult.Stale -> uiState = uiState.copy(loading = false)
                ChannelsResult.SignedOut -> uiState = uiState.copy(
                    loading = false,
                    signedOut = true
                )
            }
        }
    }

    fun dismissMessage() {
        uiState = uiState.copy(messageCode = null)
    }

    /**
     * Forget every cached list.
     *
     * Called when the session ends — by the user signing out, or by the server
     * refusing a request. A saved channel list and a saved feed are the
     * previous account's follows and the previous account's posts, and leaving
     * them for the next person to sign in on this device would show them
     * somebody else's content.
     *
     * Downloaded MEDIA is deliberately kept: a file the user saved belongs to
     * them, not to the session that listed it (§10).
     */
    fun clearCaches() {
        repository?.clearCache()
        postsRepository?.clearCache()
    }

    // ── The aggregated feed (§4) ────────────────────────────────────────

    private fun loadFeed() {
        val source = posts ?: return
        viewModelScope.launch {
            uiState = uiState.copy(loading = true, messageCode = null)

            when (val result = source.feed()) {
                is PostsResult.Ok -> {
                    uiState = uiState.copy(
                        feed = result.value.items,
                        feedCursor = result.value.nextCursor,
                        feedStale = false,
                        loading = false
                    )
                    // Saved copies live on the filesystem, not in this state,
                    // so without this a restart would offer to download media
                    // the device already holds (§10).
                    refreshSavedMedia()
                }

                is PostsResult.Stale -> uiState = uiState.copy(
                    feed = result.value.items,
                    feedStale = true,
                    loading = false
                )

                is PostsResult.Failed ->
                    uiState = uiState.copy(loading = false, messageCode = result.code)

                PostsResult.SignedOut -> uiState = uiState.copy(
                    loading = false,
                    signedOut = true
                )
            }
        }
    }

    /**
     * Append the next page of the feed.
     *
     * Merged by id, so a post that moved between pages while paging cannot
     * appear twice.
     */
    fun loadMoreFeed() {
        val source = posts ?: return
        val cursor = uiState.feedCursor ?: return
        if (uiState.loadingMore) return

        viewModelScope.launch {
            uiState = uiState.copy(loadingMore = true)

            when (val result = source.feed(cursor)) {
                is PostsResult.Ok -> uiState = uiState.copy(
                    feed = GoodPostPostCodec.mergePage(uiState.feed, result.value.items),
                    feedCursor = result.value.nextCursor,
                    loadingMore = false
                )

                is PostsResult.Stale -> uiState = uiState.copy(loadingMore = false)

                is PostsResult.Failed -> uiState = uiState.copy(
                    loadingMore = false,
                    messageCode = result.code
                )

                PostsResult.SignedOut -> uiState = uiState.copy(
                    loadingMore = false,
                    signedOut = true
                )
            }
        }
    }

    // ── One channel's history (§8) ──────────────────────────────────────

    /**
     * Load the open channel's posts.
     *
     * Idempotent for the channel already loaded, so the detail screen can call
     * this every time it appears without refetching what it just showed — and
     * keyed by channel id, so switching channels mid-request cannot paint one
     * channel's posts under another's header.
     */
    fun loadChannelPosts(force: Boolean = false) {
        val source = posts ?: return
        val channelId = uiState.channel?.id ?: return
        if (!force && uiState.channelPostsChannelId == channelId) return

        viewModelScope.launch {
            uiState = uiState.copy(channelPostsChannelId = channelId)

            when (val result = source.channelPosts(channelId)) {
                is PostsResult.Ok -> {
                    uiState = uiState.copy(
                        channelPosts = result.value.items,
                        channelPostsStale = false
                    )
                    refreshSavedMedia()
                }

                is PostsResult.Stale -> uiState = uiState.copy(
                    channelPosts = result.value.items,
                    channelPostsStale = true
                )

                is PostsResult.Failed ->
                    uiState = uiState.copy(messageCode = result.code)

                PostsResult.SignedOut -> uiState = uiState.copy(signedOut = true)
            }
        }
    }

    // ── The composer (§8, §9) ───────────────────────────────────────────

    fun startCompose(channelId: String) {
        uiState = uiState.copy(
            composerOpen = true,
            composerChannelId = channelId,
            composerBody = "",
            composerLink = "",
            composerLinkTitle = "",
            composerAttachments = emptyList(),
            messageCode = null
        )
    }

    fun cancelCompose() {
        uiState = uiState.copy(
            composerOpen = false,
            composerChannelId = null,
            composerAttachments = emptyList(),
            composerBody = "",
            composerLink = "",
            composerLinkTitle = "",
            messageCode = null
        )
    }

    fun onComposerBodyChange(value: String) {
        // No hard limit here: the server owns the length rule (`text_too_long`)
        // and duplicating the number in the UI would leave the two disagreeing
        // the day it is configured differently.
        uiState = uiState.copy(composerBody = value, messageCode = null)
    }

    fun onComposerLinkChange(value: String) {
        uiState = uiState.copy(composerLink = value, messageCode = null)
    }

    fun onComposerLinkTitleChange(value: String) {
        uiState = uiState.copy(composerLinkTitle = value, messageCode = null)
    }

    /**
     * Upload a picked file, then attach it (§9).
     *
     * The whole presign → PUT → confirm lifecycle runs before anything is
     * attached, so a post can never reference an upload that does not exist.
     * The row is uploaded on selection rather than on publish because
     * confirming takes as long as the file does, and doing that after the user
     * taps Post would make the button look stuck.
     */
    fun attachMedia(uri: android.net.Uri) {
        val source = posts ?: return
        val context = appContext ?: return
        if (uiState.uploadingAttachment) return

        val request = uploadRequestFor(context, uri)
        if (request == null) {
            uiState = uiState.copy(messageCode = "file_unreadable")
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(uploadingAttachment = true, messageCode = null)

            when (val result = source.uploadMedia(request)) {
                is PostsResult.Ok -> uiState = uiState.copy(
                    composerAttachments = uiState.composerAttachments + result.value,
                    uploadingAttachment = false
                )

                is PostsResult.Failed -> uiState = uiState.copy(
                    uploadingAttachment = false,
                    messageCode = result.code
                )

                is PostsResult.Stale -> uiState = uiState.copy(uploadingAttachment = false)

                PostsResult.SignedOut -> uiState = uiState.copy(
                    uploadingAttachment = false,
                    signedOut = true
                )
            }
        }
    }

    /**
     * Drop an attachment from the composer.
     *
     * Local only, and that is honest rather than lazy: the upload was already
     * confirmed and belongs to no post, and the server's sweep collects an
     * unclaimed upload by age (§34). Telling the server to delete it would add
     * a failure mode to an action the user only sees as "unstick this".
     */
    fun removeAttachment(mediaId: String) {
        uiState = uiState.copy(
            composerAttachments = uiState.composerAttachments.filterNot { it.id == mediaId }
        )
    }

    /** Publish, and only report success once the server has confirmed it (§36). */
    fun publish() {
        val source = posts ?: return
        val channelId = uiState.composerChannelId ?: return
        if (uiState.publishing) return

        viewModelScope.launch {
            uiState = uiState.copy(publishing = true, messageCode = null)

            val result = source.publish(
                channelId = channelId,
                body = uiState.composerBody.ifBlank { null },
                linkUrl = uiState.composerLink.ifBlank { null },
                linkTitle = uiState.composerLinkTitle.ifBlank { null },
                mediaIds = uiState.composerAttachments.map { it.id }
            )

            when (result) {
                is PostsResult.Ok -> {
                    uiState = uiState.copy(
                        publishing = false,
                        composerOpen = false,
                        composerChannelId = null,
                        composerBody = "",
                        composerLink = "",
                        composerLinkTitle = "",
                        composerAttachments = emptyList(),
                        messageCode = null
                    )
                    // Reload rather than prepend: the new post's position and
                    // the channel's counters come from the server, and a local
                    // guess at either is the kind of optimism §36 rules out.
                    loadFeed()
                    loadChannelPosts(force = true)
                }

                is PostsResult.Failed -> uiState = uiState.copy(
                    publishing = false,
                    // The composer stays open with the text intact: losing a
                    // written post to a rejected link would be unforgivable.
                    messageCode = result.code
                )

                is PostsResult.Stale -> uiState = uiState.copy(publishing = false)
                PostsResult.SignedOut -> uiState = uiState.copy(
                    publishing = false,
                    signedOut = true
                )
            }
        }
    }

    // ── Editing and removing a post (§7) ────────────────────────────────

    fun startEditPost(post: GoodPostPost) {
        if (!post.viewerCanManage) return
        uiState = uiState.copy(
            editingPostId = post.id,
            editingPostBody = post.body.orEmpty(),
            messageCode = null
        )
    }

    fun cancelEditPost() {
        uiState = uiState.copy(editingPostId = null, editingPostBody = "")
    }

    fun onEditPostBodyChange(value: String) {
        uiState = uiState.copy(editingPostBody = value, messageCode = null)
    }

    fun saveEditPost() {
        val source = posts ?: return
        val postId = uiState.editingPostId ?: return
        val post = uiState.feed.firstOrNull { it.id == postId }
            ?: uiState.channelPosts.firstOrNull { it.id == postId }
            ?: return

        viewModelScope.launch {
            uiState = uiState.copy(busyPostId = postId, messageCode = null)

            when (val result = source.edit(post.channelId, postId, body = uiState.editingPostBody)) {
                is PostsResult.Ok -> uiState = uiState.copy(
                    feed = GoodPostPostCodec.replace(uiState.feed, result.value),
                    channelPosts = GoodPostPostCodec.replace(uiState.channelPosts, result.value),
                    editingPostId = null,
                    editingPostBody = "",
                    busyPostId = null
                )

                is PostsResult.Failed -> uiState = uiState.copy(
                    busyPostId = null,
                    messageCode = result.code
                )

                is PostsResult.Stale -> uiState = uiState.copy(busyPostId = null)
                PostsResult.SignedOut -> uiState = uiState.copy(
                    busyPostId = null,
                    signedOut = true
                )
            }
        }
    }

    /**
     * Remove a post (§7).
     *
     * The row disappears only after the server confirms, because a post that
     * vanished from the screen but still exists would reappear on the next
     * refresh with no explanation.
     */
    fun deletePost(post: GoodPostPost) {
        val source = posts ?: return
        if (uiState.busyPostId != null) return

        viewModelScope.launch {
            uiState = uiState.copy(busyPostId = post.id, messageCode = null)

            when (val result = source.remove(post.channelId, post.id)) {
                is PostsResult.Ok -> uiState = uiState.copy(
                    feed = GoodPostPostCodec.remove(uiState.feed, post.id),
                    channelPosts = GoodPostPostCodec.remove(uiState.channelPosts, post.id),
                    busyPostId = null
                )

                is PostsResult.Failed -> uiState = uiState.copy(
                    busyPostId = null,
                    messageCode = result.code
                )

                is PostsResult.Stale -> uiState = uiState.copy(busyPostId = null)
                PostsResult.SignedOut -> uiState = uiState.copy(
                    busyPostId = null,
                    signedOut = true
                )
            }
        }
    }

    // ── Media the user keeps (§10) ──────────────────────────────────────

    /**
     * Save one asset to the device.
     *
     * Only from an explicit tap. §10 is explicit that media is not fetched
     * automatically, and the id is remembered so the row can say it already has
     * a copy rather than offering to fetch it again.
     */
    fun saveMedia(item: GoodPostMedia) {
        val source = posts ?: return
        if (uiState.savingMediaId != null) return

        viewModelScope.launch {
            uiState = uiState.copy(savingMediaId = item.id, messageCode = null)

            when (val result = source.saveMedia(item)) {
                is PostsResult.Ok -> uiState = uiState.copy(
                    savingMediaId = null,
                    savedMediaIds = uiState.savedMediaIds + item.id
                )

                is PostsResult.Failed -> uiState = uiState.copy(
                    savingMediaId = null,
                    messageCode = result.code
                )

                is PostsResult.Stale -> uiState = uiState.copy(savingMediaId = null)
                PostsResult.SignedOut -> uiState = uiState.copy(
                    savingMediaId = null,
                    signedOut = true
                )
            }
        }
    }

    /**
     * Re-check which of the visible media already have a saved copy.
     *
     * Called when the feed appears: the copies live on the filesystem rather
     * than in this state, so a restart would otherwise make every saved asset
     * look unsaved and offer to download it again.
     */
    fun refreshSavedMedia() {
        val source = posts ?: return
        val visible = (uiState.feed + uiState.channelPosts).flatMap { it.media }
        if (visible.isEmpty()) return
        val saved = visible.filter { source.localCopy(it) != null }.map { it.id }.toSet()
        if (saved != uiState.savedMediaIds) {
            uiState = uiState.copy(savedMediaIds = saved)
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Run a mutation against the open channel with the busy flag set.
     *
     * The flag exists so the control that was tapped can show it is working and
     * refuse a second tap. Two rapid taps on Follow would otherwise race, and
     * the second response would land after the first and put the button back
     * where it started.
     */
    private fun mutate(
        channel: GoodPostChannel,
        block: suspend (GoodPostChannelsRepository) -> Unit
    ) {
        val source = repo ?: return
        if (uiState.busyChannelId != null) return

        viewModelScope.launch {
            uiState = uiState.copy(busyChannelId = channel.id, messageCode = null)
            block(source)
            uiState = uiState.copy(busyChannelId = null)
        }
    }

    /**
     * Clear the unread badge locally for a channel the user just opened.
     *
     * Local only — the server is told separately and best-effort, because a
     * read receipt that fails must not surface as an error.
     */
    private fun clearUnreadLocally(channelId: String) {
        val current = uiState.channel
        if (current != null && current.id == channelId && current.hasUnread) {
            uiState = uiState.copy(channel = current.copy(hasUnread = false))
        }
        uiState = uiState.copy(
            following = uiState.following.map {
                if (it.id == channelId) it.copy(hasUnread = false) else it
            }
        )
    }
}
