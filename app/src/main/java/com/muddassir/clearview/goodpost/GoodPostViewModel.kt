package com.muddassir.clearview.goodpost

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muddassir.clearview.goodpost.data.AdminSession
import com.muddassir.clearview.goodpost.data.ApiResult
import com.muddassir.clearview.goodpost.data.CachedChannels
import com.muddassir.clearview.goodpost.data.CachedPosts
import com.muddassir.clearview.goodpost.data.GoodPostAttachment
import com.muddassir.clearview.goodpost.data.GoodPostCategory
import com.muddassir.clearview.goodpost.data.GoodPostChannel
import com.muddassir.clearview.goodpost.data.GoodPostCodec
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostMediaItem
import com.muddassir.clearview.goodpost.data.GoodPostPage
import com.muddassir.clearview.goodpost.data.GoodPostPost
import com.muddassir.clearview.goodpost.data.GoodPostRepository
import com.muddassir.clearview.goodpost.data.GoodPostUploadState
import com.muddassir.clearview.goodpost.data.parseIsoMillis
import com.muddassir.clearview.goodpost.ui.waDayLabel
import com.muddassir.clearview.goodpost.ui.waSameDay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Where the tab is.
 *
 * A stack rather than a set of booleans, because Good Post's screens nest —
 * channel → information → back to the channel — and a boolean per screen cannot
 * express "back to where I came from" without a second mechanism to keep them
 * consistent.
 *
 * There is no `SignedIn` state and no gate. A reader opens the tab and is on the
 * channel list, which is the whole of §1.
 */
sealed interface GoodPostScreen {
    data object Home : GoodPostScreen
    data object Explore : GoodPostScreen

    /** A channel's feed (§9). */
    data class Channel(val channelId: String) : GoodPostScreen

    /** A channel's information page (§11–§14). */
    data class ChannelInfo(val channelId: String) : GoodPostScreen

    /** The administrator way in (§16). */
    data object AdminLogin : GoodPostScreen

    /**
     * One channel, as its administrator sees it (§19, §21).
     *
     * There is no separate administrator home screen any more. An account's
     * channels ARE the tab — the list is built from what the account may publish
     * to — so a second list of the same channels would be a dashboard beside the
     * product rather than part of it (§5).
     */
    data class AdminChannel(val channelId: String) : GoodPostScreen
}

/**
 * Everything the Good Post tab renders.
 *
 * One object rather than a StateFlow per concern, matching the rest of the app:
 * the screens here are a handful of lists and one form, and a single immutable
 * state is what makes "render the cache, then replace it with the network"
 * expressible as one transition rather than two that can disagree.
 */
data class GoodPostUiState(
    /** False when this build has no backend URL, which is a stated state (§41). */
    val configured: Boolean = true,
    val backStack: List<GoodPostScreen> = listOf(GoodPostScreen.Home),

    // ── The channel list (§3, §4) ────────────────────────────────────────
    val channels: List<GoodPostChannel> = emptyList(),
    val channelsLoading: Boolean = true,
    /** True while what is on screen came from the cache (§27). */
    val channelsStale: Boolean = false,
    val channelsError: String? = null,

    /**
     * The rows currently selected, by long press (§5).
     *
     * Non-empty means selection mode: the bar above the list becomes an action
     * bar, a tap toggles instead of opening, and the actions offered are the ones
     * that apply to what is selected. One set per list rather than one shared
     * set, because the two lists can be on screen in different states.
     */
    val selectedChannelIds: Set<String> = emptySet(),

    // ── A channel's feed (§8, §9, §10) ───────────────────────────────────
    val channel: GoodPostChannel? = null,
    val posts: List<GoodPostPost> = emptyList(),
    val postsLoading: Boolean = false,
    val postsStale: Boolean = false,
    val postsCursor: String? = null,
    val postsError: String? = null,
    val postsLoadingMore: Boolean = false,

    /** Posts selected by long press in a channel's feed (§5). */
    val selectedPostIds: Set<String> = emptySet(),

    // ── The information page (§11–§14) ───────────────────────────────────
    val media: List<GoodPostMediaItem> = emptyList(),
    val mediaLoading: Boolean = false,
    val descriptionExpanded: Boolean = false,

    // ── Explore (§7) ─────────────────────────────────────────────────────
    val query: String = "",
    val category: String? = null,
    val sort: String = "recent",
    val categories: List<GoodPostCategory> = emptyList(),
    val explore: List<GoodPostChannel> = emptyList(),
    val exploreLoading: Boolean = false,
    val exploreCursor: String? = null,
    val exploreError: String? = null,

    // ── Administrator (§16, §19, §21) ────────────────────────────────────
    val admin: AdminSession? = null,
    val adminEmail: String = "",
    val adminPassword: String = "",
    val adminBusy: Boolean = false,
    /**
     * The channels this account may work with: all of them for a super
     * administrator, exactly one for a channel administrator.
     *
     * Decided by the server, and it IS the tab's list when signed in — see
     * [tabChannels].
     */
    val adminChannels: List<GoodPostChannel> = emptyList(),
    val adminChannelsLoading: Boolean = false,

    /** A message code from the last failed action, worded by [goodPostErrorFor]. */
    val messageCode: String? = null,

    // ── The composer (§21) ───────────────────────────────────────────────
    val composerOpen: Boolean = false,
    val composerBody: String = "",
    val composerBusy: Boolean = false,
    /** Set while editing an existing post rather than creating one. */
    val editingPostId: String? = null,
    /**
     * The channel the post being edited belongs to.
     *
     * Editing state is per-channel, and this is what makes that true rather than
     * hopeful. The composer is one field on one screen, but the screen it belongs
     * to can change underneath it — and a bare [editingPostId] then describes a
     * post in a channel the reader is no longer looking at. The feed asks
     * [isEditingIn] before it draws the editing strip, so a stale id cannot leak
     * across a channel switch even if a reset was missed.
     */
    val editingPostChannelId: String? = null,
    /**
     * Files attached to the post being written, in the order they were picked.
     *
     * Held here rather than in the screen because it is a VALUE — the publish
     * call attaches exactly the ids in this list, and the upload state of each
     * one decides whether publishing is allowed at all.
     */
    val composerAttachments: List<GoodPostAttachment> = emptyList(),
    /**
     * Whether this deployment can store media at all (§22).
     *
     * Optimistic until the server says otherwise: storage is optional, so the
     * composer offers the picker and words a `media_unavailable` refusal honestly
     * rather than pre-emptively hiding a feature that usually works. Set to
     * false only by an answer that actually says so.
     */
    val composerMediaAvailable: Boolean = true,

    // ── The channel form (§19, §20) ──────────────────────────────────────
    val channelFormOpen: Boolean = false,
    val channelFormId: String? = null,
    val channelFormName: String = "",
    val channelFormDescription: String = "",
    val channelFormCategory: String? = null,
    /** Set only when a super admin is creating a channel plus its admin (§20). */
    val channelFormAdminEmail: String? = null,
    val channelFormAdminPassword: String? = null,
    /**
     * The image being uploaded for this channel, or the one already on it.
     *
     * Two different things in one field, distinguished by state: an `Uploading`
     * or `Ready` attachment is a NEW image the form is about to save, while
     * [channelFormExistingIconUrl] is what the channel has now. Keeping them
     * apart is what makes "no change" distinguishable from "remove" — the
     * difference the PATCH endpoint's three-way `iconMediaId` encodes.
     */
    val channelFormIcon: GoodPostAttachment? = null,
    val channelFormExistingIconUrl: String? = null,
    /** True when the administrator has asked to remove the existing image. */
    val channelFormIconRemoved: Boolean = false
) {
    /** The screen on top of the stack. */
    val screen: GoodPostScreen get() = backStack.last()

    val isAdmin: Boolean get() = admin != null

    /**
     * The channels the tab lists (§1, §3).
     *
     * A reader sees the public catalogue, because that is what §3 asks the tab
     * to be: the channel list, opened straight into, with no account and nothing
     * to set up first.
     *
     * A signed-in account sees the channels it has access to instead — the whole
     * product for a super administrator, exactly one channel for an account
     * created for one. That replacement is the point: an account made for a
     * channel must not be handed the rest of Good Post, and its list therefore
     * carries only the controls it is entitled to.
     */
    val tabChannels: List<GoodPostChannel>
        get() = if (admin != null) {
            adminChannels
        } else {
            channels
        }

    /**
     * Whether the account may create a channel, and therefore whether the tab
     * offers the way in (§15, §17).
     *
     * True for a reader — the way in is the administrator sign-in — and for a
     * super administrator, who is the only role that can create one. A channel
     * administrator holds no `channels.create`, so offering it would be offering
     * a button that always fails.
     */
    val canCreateChannel: Boolean get() = admin == null || admin.isSuperAdmin

    /**
     * True while the tab's list is still on its way.
     *
     * Which list that is depends on who is asking, and the two are fetched
     * separately — so a screen that watched only `channelsLoading` showed the
     * empty state for the moment between the public read finishing and the
     * account's own list arriving. That reads as "you have no channels", which
     * is a statement about the account rather than a loading state, and a false
     * one.
     */
    val tabLoading: Boolean
        get() = if (admin != null) adminChannelsLoading else channelsLoading

    /** True while a selection is active in the channel list (§5). */
    val channelSelectionActive: Boolean get() = selectedChannelIds.isNotEmpty()

    /** True while a selection is active in a feed (§5). */
    val postSelectionActive: Boolean get() = selectedPostIds.isNotEmpty()

    /** Whether a channel may be edited or deleted by this account. */
    fun canManage(channelId: String): Boolean =
        admin?.let { session -> session.isSuperAdmin || session.channelId == channelId } == true

    /**
     * Whether the composer is editing a post that belongs to [channelId].
     *
     * Both halves are required. A non-null [editingPostId] alone only says that
     * SOME post is being edited; it does not say whose, and the feed of another
     * channel reading only that is exactly how an editing strip appeared over a
     * channel the administrator had never edited anything in.
     */
    fun isEditingIn(channelId: String): Boolean =
        editingPostId != null && editingPostChannelId == channelId
}

/**
 * Good Post's one ViewModel.
 *
 * It owns navigation, the cached-then-live read pattern, and the administrator's
 * actions. Two rules it exists to keep:
 *
 *  * **Nothing here is per-account, because there is no account.** The only
 *    credential in the app is an administrator's, and every screen a reader sees
 *    works with none.
 *
 *  * **Nothing blocks the first frame.** [initialize] renders the cache and then
 *    refreshes behind it, which is what makes the tab feel like it opens
 *    instantly (§26).
 */
class GoodPostViewModel : ViewModel() {

    private var repository: GoodPostRepository? = null

    /**
     * A channel slug from a share link, held until the repository exists.
     *
     * The link can arrive before [initialize] has run — MainActivity hands it to
     * the tab on the first composition — so it is kept here rather than dropped,
     * which is what lets a shared link open its channel instead of the list.
     */
    private var pendingSlug: String? = null

    var uiState by mutableStateOf(GoodPostUiState())
        private set

    /** Idempotent, so a rotation does not refetch everything. */
    fun initialize(context: Context) {
        if (repository != null) return
        val repo = GoodPostRepository(context.applicationContext)
        repository = repo

        val cached = repo.cachedChannels()
        if (cached != null) showCachedChannels(cached)

        uiState = uiState.copy(
            configured = repo.isConfigured,
            categories = repo.cachedCategories(),
            admin = repo.adminSession()
        )

        refreshChannels()
        if (uiState.categories.isEmpty()) loadCategories()

        // A stored session decides what the tab lists, so its channels are
        // fetched with everything else. Without this the list came up empty with
        // "No channels. Create one to start publishing." — a statement about the
        // account rather than a loading state, and a false one.
        if (uiState.admin != null) loadAdminChannels()

        pendingSlug?.let { slug ->
            pendingSlug = null
            requestOpenChannel(slug)
        }
    }

    /**
     * Open the channel a share link names (§6).
     *
     * The slug is resolved through the same public read a tapped row uses, so a
     * link and a tap cannot produce different screens. A dead link reports
     * not-found rather than doing nothing, because a tap that appears to be
     * ignored is worse than an honest refusal.
     */
    fun requestOpenChannel(slug: String) {
        val repo = repository
        if (repo == null) {
            pendingSlug = slug
            return
        }

        viewModelScope.launch {
            when (val result = repo.channel(slug)) {
                is ApiResult.Ok -> openChannel(result.value.id)
                else -> uiState = uiState.copy(messageCode = "not_found")
            }
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────

    fun open(screen: GoodPostScreen) {
        uiState = uiState.copy(backStack = uiState.backStack + screen, messageCode = null)
    }

    /** Pop one screen, or report that there was nothing to pop. */
    fun back(): Boolean {
        val stack = uiState.backStack
        if (stack.size <= 1) return false
        uiState = uiState.copy(backStack = stack.dropLast(1), messageCode = null)
        return true
    }

    fun goHome() {
        uiState = uiState.copy(backStack = listOf(GoodPostScreen.Home), messageCode = null)
    }

    // ── The channel list (§3, §4) ────────────────────────────────────────

    private fun showCachedChannels(cached: CachedChannels) {
        uiState = uiState.copy(
            channels = cached.channels,
            channelsLoading = false,
            channelsStale = true
        )
    }

    fun refreshChannels() {
        val repo = repository ?: return
        uiState = uiState.copy(channelsLoading = true, channelsError = null)

        viewModelScope.launch {
            when (val result = repo.channels()) {
                is ApiResult.Ok -> uiState = uiState.copy(
                    channels = result.value.items,
                    channelsLoading = false,
                    channelsStale = false,
                    channelsError = null
                )

                is ApiResult.Failed -> uiState = uiState.copy(
                    channelsLoading = false,
                    // A refusal does not throw away saved channels (§27).
                    channelsStale = uiState.channels.isNotEmpty(),
                    channelsError = result.code
                )

                ApiResult.Unreachable -> uiState = uiState.copy(
                    channelsLoading = false,
                    channelsStale = uiState.channels.isNotEmpty(),
                    channelsError = if (uiState.channels.isEmpty()) "unreachable" else null
                )
            }
        }
    }

    private fun loadCategories() {
        val repo = repository ?: return
        viewModelScope.launch {
            if (repo.categories() is ApiResult.Ok) {
                uiState = uiState.copy(categories = repo.cachedCategories())
            }
        }
    }

    // ── A channel (§8–§14) ───────────────────────────────────────────────

    /**
     * Open a channel's feed. Fetches the channel if it is not already known.
     *
     * A channel this account has access to opens as its administrator's view of
     * it — the compose button, the post actions — because that is what the
     * account may do with it. Everything else opens as a reader's. The decision
     * is made here rather than at each row, so no screen can offer a control the
     * account does not hold.
     */
    fun openChannel(channelId: String) {
        if (uiState.canManage(channelId)) {
            openAdminChannel(channelId)
            return
        }

        uiState = uiState.forChannel(uiState.channels.firstOrNull { it.id == channelId })
        open(GoodPostScreen.Channel(channelId))
        loadChannel(channelId)
        loadPosts(channelId)
    }

    private fun loadChannel(channelId: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            // Fetched once and branched on, rather than tested and then fetched
            // again: a second call could return a different answer, and the
            // first one would have been paid for twice.
            val result = repo.channel(channelId)
            if (result is ApiResult.Ok) {
                uiState = uiState.copy(channel = result.value)
            }
        }
    }

    fun loadPosts(channelId: String) {
        val repo = repository ?: return

        if (uiState.posts.isEmpty()) {
            val cached = repo.cachedPosts(channelId)
            if (cached != null) showCachedPosts(cached)
        }

        uiState = uiState.copy(postsLoading = true, postsError = null)
        viewModelScope.launch {
            when (val result = repo.channelPosts(channelId)) {
                is ApiResult.Ok -> uiState = uiState.copy(
                    posts = result.value.items,
                    postsCursor = result.value.nextCursor,
                    postsLoading = false,
                    postsStale = false,
                    postsError = null
                )

                is ApiResult.Failed -> uiState = uiState.copy(
                    postsLoading = false,
                    postsStale = uiState.posts.isNotEmpty(),
                    postsError = result.code
                )

                ApiResult.Unreachable -> uiState = uiState.copy(
                    postsLoading = false,
                    postsStale = uiState.posts.isNotEmpty(),
                    postsError = if (uiState.posts.isEmpty()) "unreachable" else null
                )
            }
        }
    }

    private fun showCachedPosts(cached: CachedPosts) {
        uiState = uiState.copy(
            posts = cached.posts,
            postsLoading = false,
            postsStale = true
        )
    }

    /** The next page of a channel's history (§26: lazy, cursor-paged). */
    fun loadMorePosts() {
        val repo = repository ?: return
        val channelId = (uiState.screen as? GoodPostScreen.Channel)?.channelId ?: return
        val cursor = uiState.postsCursor ?: return
        if (uiState.postsLoadingMore) return

        uiState = uiState.copy(postsLoadingMore = true)
        viewModelScope.launch {
            when (val result = repo.channelPosts(channelId, cursor)) {
                is ApiResult.Ok -> uiState = uiState.copy(
                    posts = GoodPostCodec.merge(uiState.posts, result.value.items) { it.id },
                    postsCursor = result.value.nextCursor,
                    postsLoadingMore = false
                )

                else -> uiState = uiState.copy(postsLoadingMore = false)
            }
        }
    }

    /** The channel information page's gallery (§13). */
    fun loadMedia(channelId: String) {
        val repo = repository ?: return
        uiState = uiState.copy(mediaLoading = true)

        viewModelScope.launch {
            val result = repo.channelMedia(channelId)
            uiState = uiState.copy(
                media = if (result is ApiResult.Ok) result.value.items else uiState.media,
                mediaLoading = false
            )
        }
    }

    fun toggleDescription() {
        uiState = uiState.copy(descriptionExpanded = !uiState.descriptionExpanded)
    }

    // ── Selection (§5) ───────────────────────────────────────────────────

    /** Long press: select the row, or add it to what is already selected. */
    fun toggleChannelSelected(channelId: String) {
        val next = uiState.selectedChannelIds.toMutableSet()
        if (!next.add(channelId)) next.remove(channelId)
        uiState = uiState.copy(selectedChannelIds = next, messageCode = null)
    }

    fun clearChannelSelection() {
        uiState = uiState.copy(selectedChannelIds = emptySet(), messageCode = null)
    }

    /**
     * Delete the selected channels (§17).
     *
     * The administrator-side action, and the only irreversible one in the app:
     * the server removes the channel with its posts, its media and the login that
     * ran it. The confirmation is the screen's job — see `ConfirmDeleteDialog` —
     * and this is what runs once it has been given.
     */
    fun deleteSelectedChannels() {
        val repo = repository ?: return
        if (uiState.admin == null) return

        val selected = uiState.selectedChannelIds
        if (selected.isEmpty()) return

        // A channel administrator can reach this only for their own channel, and
        // the server is what enforces that; the selection is checked here as well
        // so the app never sends a request it knows will be refused.
        val permitted = selected.filter { uiState.canManage(it) }
        if (permitted.isEmpty()) {
            uiState = uiState.copy(messageCode = "admin_forbidden", selectedChannelIds = emptySet())
            return
        }

        viewModelScope.launch {
            var firstFailure: String? = null
            for (channelId in permitted) {
                val result = repo.adminDeleteChannel(channelId)
                if (result is ApiResult.Failed || result is ApiResult.Unreachable) {
                    firstFailure = if (result is ApiResult.Failed) adminFailureCode(result) else "unreachable"
                    break
                }
            }

            // Removed locally whatever succeeded, then reloaded: the server is
            // the only thing that knows what is left, and guessing would leave a
            // row on screen for a channel that no longer exists.
            uiState = uiState.copy(
                adminChannels = uiState.adminChannels.filterNot { permitted.contains(it.id) },
                channels = uiState.channels.filterNot { permitted.contains(it.id) },
                selectedChannelIds = emptySet(),
                messageCode = firstFailure
            )
            if (uiState.admin != null) loadAdminChannels()
            refreshChannels()
        }
    }

    /** Long press a post: select it, or add it to what is already selected. */
    fun togglePostSelected(postId: String) {
        val next = uiState.selectedPostIds.toMutableSet()
        if (!next.add(postId)) next.remove(postId)
        uiState = uiState.copy(selectedPostIds = next, messageCode = null)
    }

    fun clearPostSelection() {
        uiState = uiState.copy(selectedPostIds = emptySet(), messageCode = null)
    }

    /**
     * Remove every selected post in one request (§17).
     *
     * One call rather than one per post: the server applies the selection
     * atomically, so a failure leaves the list whole instead of half-emptied.
     */
    fun deleteSelectedPosts() {
        val repo = repository ?: return
        if (uiState.admin == null) return

        val selected = uiState.selectedPostIds.toList()
        if (selected.isEmpty()) return

        viewModelScope.launch {
            when (val result = repo.adminDeletePosts(selected)) {
                is ApiResult.Ok -> {
                    uiState = uiState.copy(
                        posts = uiState.posts.filterNot { selected.contains(it.id) },
                        selectedPostIds = emptySet()
                    )
                    // Reloaded rather than trusted: a page boundary can shift when
                    // rows in the middle of the list disappear.
                    currentChannelId()?.let { loadPosts(it) }
                }

                is ApiResult.Failed -> uiState = uiState.copy(
                    selectedPostIds = emptySet(),
                    messageCode = adminFailureCode(result)
                )

                ApiResult.Unreachable -> uiState = uiState.copy(
                    selectedPostIds = emptySet(),
                    messageCode = "unreachable"
                )
            }
        }
    }

    /**
     * The state a channel's screen starts from, whichever list opened it.
     *
     * This is where per-channel state is scoped, and it is one function rather
     * than two `copy` calls for that reason: opening a feed is the moment every
     * channel-scoped field has to be decided, and when it was written out twice,
     * one copy was missing the selection and the other was missing the errors.
     *
     * What is cleared, and why each one is here:
     *
     *  * **The editor.** An edit belongs to one channel's post. Carrying
     *    `editingPostId` into another channel is the bug this exists to prevent,
     *    so it goes, along with the body, the attachments and the composer's own
     *    open flag — an editor left half-populated over a different channel is
     *    worse than one that was closed.
     *  * **The selection.** Post ids are only unambiguous within a channel, so a
     *    selection that survived a switch would name posts the new channel does
     *    not have, and the action bar would count rows that are not there.
     *  * **The feed and its paging.** Posts, the cursor, the error and the
     *    in-flight flag all describe the previous channel's history.
     *  * **The profile gallery.** `media` and `descriptionExpanded` belong to the
     *    information page of one channel.
     *
     * [loadChannel] and [loadPosts] fill the rest in behind this.
     */
    private fun GoodPostUiState.forChannel(channel: GoodPostChannel?): GoodPostUiState = copy(
        channel = channel,
        posts = emptyList(),
        postsCursor = null,
        postsLoading = false,
        postsLoadingMore = false,
        postsError = null,
        postsStale = false,
        selectedPostIds = emptySet(),
        media = emptyList(),
        mediaLoading = false,
        descriptionExpanded = false,
        // §19 Bug 2/3: the composer is closed AND emptied, so neither an editing
        // strip nor a half-typed body can appear over another channel.
        composerOpen = false,
        composerBody = "",
        composerBusy = false,
        editingPostId = null,
        editingPostChannelId = null,
        composerAttachments = emptyList(),
        messageCode = null
    )

    /** The channel the open feed is showing, whichever screen opened it. */
    private fun currentChannelId(): String? = when (val screen = uiState.screen) {
        is GoodPostScreen.Channel -> screen.channelId
        is GoodPostScreen.AdminChannel -> screen.channelId
        else -> null
    }

    // ── Explore (§7) ─────────────────────────────────────────────────────

    fun openExplore(query: String = "") {
        uiState = uiState.copy(query = query, explore = emptyList(), exploreCursor = null)
        open(GoodPostScreen.Explore)
        search()
    }

    fun onQueryChange(value: String) {
        uiState = uiState.copy(query = value)
    }

    fun selectCategory(slug: String?) {
        uiState = uiState.copy(category = slug)
        search()
    }

    fun selectSort(sort: String) {
        uiState = uiState.copy(sort = sort)
        search()
    }

    /** Search channels by name and description (§6). */
    fun search() {
        val repo = repository ?: return
        val query = uiState.query
        val category = uiState.category
        val sort = uiState.sort

        uiState = uiState.copy(exploreLoading = true, exploreError = null)
        viewModelScope.launch {
            when (val result = repo.channels(query = query, category = category, sort = sort)) {
                is ApiResult.Ok -> uiState = uiState.copy(
                    explore = result.value.items,
                    exploreCursor = result.value.nextCursor,
                    exploreLoading = false,
                    exploreError = null
                )

                is ApiResult.Failed -> uiState = uiState.copy(
                    exploreLoading = false,
                    exploreError = result.code
                )

                ApiResult.Unreachable -> uiState = uiState.copy(
                    exploreLoading = false,
                    exploreError = "unreachable"
                )
            }
        }
    }

    fun loadMoreChannels() {
        val repo = repository ?: return
        val cursor = uiState.exploreCursor ?: return
        if (uiState.exploreLoading) return

        uiState = uiState.copy(exploreLoading = true)
        viewModelScope.launch {
            val result = repo.channels(
                query = uiState.query,
                category = uiState.category,
                sort = uiState.sort,
                cursor = cursor
            )
            uiState = if (result is ApiResult.Ok) {
                uiState.copy(
                    explore = GoodPostCodec.merge(uiState.explore, result.value.items) { it.id },
                    exploreCursor = result.value.nextCursor,
                    exploreLoading = false
                )
            } else {
                uiState.copy(exploreLoading = false)
            }
        }
    }

    // ── Administrator (§16, §19, §21) ────────────────────────────────────

    /**
     * The tab's way in for whoever runs a channel (§15, §16).
     *
     * Signed out it is the sign-in; a super administrator goes straight to
     * creating a channel, because that is the only thing this control is for once
     * the account is known — their channels are already on the tab. A channel
     * administrator is not offered it at all ([GoodPostUiState.canCreateChannel]),
     * which the screen decides.
     */
    fun openAdmin() {
        if (uiState.admin == null) {
            open(GoodPostScreen.AdminLogin)
            return
        }
        startCreateChannel()
    }

    fun onAdminEmailChange(value: String) {
        uiState = uiState.copy(adminEmail = value, messageCode = null)
    }

    fun onAdminPasswordChange(value: String) {
        uiState = uiState.copy(adminPassword = value, messageCode = null)
    }

    fun adminSignIn() {
        val repo = repository ?: return
        val email = normalizeEmailInput(uiState.adminEmail)
        val password = uiState.adminPassword

        if (email == null || password.isEmpty()) {
            uiState = uiState.copy(messageCode = "invalid_credentials")
            return
        }

        uiState = uiState.copy(adminBusy = true, messageCode = null)
        viewModelScope.launch {
            when (val result = repo.adminLogin(email, password)) {
                is ApiResult.Ok -> {
                    val session = result.value

                    // Where the account lands after signing in is the account's
                    // own shape, not a screen choice: a channel administrator
                    // gets the channel they run and nothing else (§3), while a
                    // super administrator gets the tab, whose list IS every
                    // channel they may publish to.
                    val destination = if (session.isSuperAdmin) {
                        listOf(GoodPostScreen.Home)
                    } else {
                        session.channelId
                            ?.let { listOf(GoodPostScreen.Home, GoodPostScreen.AdminChannel(it)) }
                            ?: listOf(GoodPostScreen.Home)
                    }

                    uiState = uiState.copy(
                        admin = session,
                        adminPassword = "",
                        adminBusy = false,
                        messageCode = null,
                        selectedChannelIds = emptySet(),
                        backStack = destination
                    )

                    loadAdminChannels()
                    destination.lastOrNull()?.let { screen ->
                        if (screen is GoodPostScreen.AdminChannel) {
                            loadChannel(screen.channelId)
                            loadPosts(screen.channelId)
                        }
                    }
                }

                // A refusal this contract cannot produce is not a credential
                // problem: it means the backend is a different one. Naming that
                // is the difference between "your password is wrong" and "this
                // is not the server you think it is", and only one of those is
                // worth acting on.
                is ApiResult.Failed -> uiState = uiState.copy(
                    adminBusy = false,
                    messageCode = if (signInHitAnotherContract(result.status, result.code)) {
                        "admin_unavailable"
                    } else {
                        result.code
                    }
                )

                ApiResult.Unreachable -> uiState = uiState.copy(
                    adminBusy = false,
                    messageCode = "unreachable"
                )
            }
        }
    }

    fun adminSignOut() {
        val repo = repository
        // Read before the session is forgotten: revocation needs it, and the store
        // is about to be cleared.
        val refreshToken = uiState.admin?.refreshToken
        repo?.forgetAdminSession()
        if (refreshToken != null) {
            // Best effort. The screen changes now; the server hears about it after.
            viewModelScope.launch { repo?.revokeAdminSession(refreshToken) }
        }
        GoodPostImages.clear()
        uiState = uiState.copy(
            admin = null,
            adminEmail = "",
            adminPassword = "",
            adminChannels = emptyList(),
            selectedChannelIds = emptySet(),
            selectedPostIds = emptySet(),
            backStack = listOf(GoodPostScreen.Home)
        )
        // The editor belonged to an account that is no longer signed in, and the
        // channel it was aimed at may not even be in the list the tab now shows.
        resetComposer()
        refreshChannels()
    }

    fun loadAdminChannels() {
        val repo = repository ?: return
        // Signed in is the only precondition a caller can check. The token itself
        // belongs to the repository, which renews it when it has lapsed — a stale
        // copy held here is what made every call fail ten minutes in.
        if (uiState.admin == null) return

        uiState = uiState.copy(adminChannelsLoading = true)
        viewModelScope.launch {
            val result = repo.adminChannels()
            uiState = if (result is ApiResult.Ok) {
                uiState.copy(adminChannels = result.value, adminChannelsLoading = false)
            } else {
                // Never a silent empty list. A failed load used to leave
                // `adminChannels` empty, which the dashboard renders as "No
                // channels. Create one to start publishing." — telling an
                // administrator their channels are gone when the request simply
                // failed.
                uiState.copy(
                    adminChannelsLoading = false,
                    messageCode = adminFailureCode(result)
                )
            }
        }
    }

    fun openAdminChannel(channelId: String) {
        uiState = uiState.forChannel(
            uiState.adminChannels.firstOrNull { it.id == channelId }
                ?: uiState.channels.firstOrNull { it.id == channelId }
        )
        open(GoodPostScreen.AdminChannel(channelId))
        loadChannel(channelId)
        loadPosts(channelId)
    }

    // ── The channel form (§19, §20) ──────────────────────────────────────

    fun startCreateChannel() {
        uiState = uiState.copy(
            channelFormOpen = true,
            channelFormId = null,
            channelFormName = "",
            channelFormDescription = "",
            channelFormCategory = null,
            // Only a super admin creates a channel together with the account that
            // runs it (§20). A channel admin editing their own channel has no
            // business minting credentials, and the server refuses it anyway.
            channelFormAdminEmail = if (uiState.admin?.isSuperAdmin == true) "" else null,
            channelFormAdminPassword = if (uiState.admin?.isSuperAdmin == true) "" else null,
            channelFormIcon = null,
            channelFormExistingIconUrl = null,
            channelFormIconRemoved = false,
            messageCode = null
        )
    }

    fun startEditChannel(channel: GoodPostChannel) {
        uiState = uiState.copy(
            channelFormOpen = true,
            channelFormId = channel.id,
            channelFormName = channel.name,
            channelFormDescription = channel.description.orEmpty(),
            channelFormCategory = channel.categorySlug,
            channelFormAdminEmail = null,
            channelFormAdminPassword = null,
            channelFormIcon = null,
            // Rendered from the same signed URL the list row uses, so the form
            // shows the image the channel actually has rather than a second
            // representation of it that could disagree.
            channelFormExistingIconUrl = channel.iconUrl,
            channelFormIconRemoved = false,
            messageCode = null
        )
    }

    /**
     * Attach an image to the channel being created or edited (§21).
     *
     * Uploaded at once, exactly as a post's media is: by the time Save is
     * tapped, all that is left is to send the media id. A profile image is an
     * image — the kind check refuses a video here, because the avatar is drawn
     * into a circle and a video has no meaning in one.
     */
    fun onChannelIconPicked(attachment: GoodPostAttachment) {
        val repo = repository ?: return
        // Signed in is the only precondition a caller can check. The token itself
        // belongs to the repository, which renews it when it has lapsed — a stale
        // copy held here is what made every call fail ten minutes in.
        if (uiState.admin == null) return

        if (attachment.kind != "image") {
            uiState = uiState.copy(messageCode = "unsupported_media_type")
            return
        }

        uiState = uiState.copy(
            channelFormIcon = attachment,
            // Choosing a new image supersedes a pending removal: the last
            // decision the administrator made is the one that counts.
            channelFormIconRemoved = false,
            messageCode = null
        )

        viewModelScope.launch {
            when (val result = repo.adminUploadMedia(attachment)) {
                is ApiResult.Ok -> uiState = uiState.copy(
                    channelFormIcon = uiState.channelFormIcon?.copy(
                        state = GoodPostUploadState.Ready(result.value.id)
                    )
                )
                is ApiResult.Failed -> {
                    uiState = uiState.copy(
                        channelFormIcon = uiState.channelFormIcon?.copy(
                            state = GoodPostUploadState.Failed(result.code)
                        ),
                        composerMediaAvailable = uiState.composerMediaAvailable &&
                            result.code != "media_unavailable"
                    )
                }
                ApiResult.Unreachable -> uiState = uiState.copy(
                    channelFormIcon = uiState.channelFormIcon?.copy(
                        state = GoodPostUploadState.Failed("unreachable")
                    )
                )
            }
        }
    }

    /** Drop the image chosen for this channel, and ask for any existing one to go. */
    fun removeChannelIcon() {
        uiState = uiState.copy(
            channelFormIcon = null,
            // Only a saved channel has an image to remove. On the create form
            // there is nothing on the server yet, so this just clears the pick.
            channelFormIconRemoved = uiState.channelFormExistingIconUrl != null,
            messageCode = null
        )
    }

    /** Undo a pending removal, keeping the image the channel already has. */
    fun keepChannelIcon() {
        uiState = uiState.copy(channelFormIconRemoved = false, messageCode = null)
    }

    fun onChannelFormNameChange(value: String) {
        uiState = uiState.copy(channelFormName = value)
    }

    fun onChannelFormDescriptionChange(value: String) {
        uiState = uiState.copy(channelFormDescription = value)
    }

    fun onChannelFormCategoryChange(slug: String?) {
        uiState = uiState.copy(channelFormCategory = slug)
    }

    fun onChannelFormAdminEmailChange(value: String) {
        uiState = uiState.copy(channelFormAdminEmail = value)
    }

    fun onChannelFormAdminPasswordChange(value: String) {
        uiState = uiState.copy(channelFormAdminPassword = value)
    }

    fun cancelChannelForm() {
        // The picked image is dropped with the form. Its upload is never
        // claimed, so the server's own sweep collects the object — the same
        // reasoning as the post composer's attachments.
        uiState = uiState.copy(
            channelFormOpen = false,
            channelFormIcon = null,
            channelFormIconRemoved = false,
            messageCode = null
        )
    }

    fun submitChannelForm() {
        val repo = repository ?: return
        // Signed in is the only precondition a caller can check. The token itself
        // belongs to the repository, which renews it when it has lapsed — a stale
        // copy held here is what made every call fail ten minutes in.
        if (uiState.admin == null) return
        val name = uiState.channelFormName.trim()
        if (name.isBlank()) {
            uiState = uiState.copy(messageCode = "invalid_request")
            return
        }

        // The image is part of the SAME save, and an upload that has not landed
        // blocks it — the alternative is a channel saved with the picture the
        // administrator chose silently missing.
        val icon = uiState.channelFormIcon
        if (icon != null && icon.mediaId == null) {
            uiState = uiState.copy(
                messageCode = if (icon.state is GoodPostUploadState.Failed) {
                    "attachment_failed"
                } else {
                    "attachment_uploading"
                }
            )
            return
        }

        val body = JSONObject().apply {
            put("name", name)
            put("description", uiState.channelFormDescription.trim().ifBlank { JSONObject.NULL })
            put("categorySlug", uiState.channelFormCategory ?: JSONObject.NULL)
            uiState.channelFormAdminEmail?.takeIf { it.isNotBlank() }
                ?.let { put("adminEmail", it.trim().lowercase()) }
            uiState.channelFormAdminPassword?.takeIf { it.isNotBlank() }
                ?.let { put("adminPassword", it) }

            // The three-way field, sent only when the image is actually being
            // changed: absent means "leave it alone", which is what renaming a
            // channel must mean. Sending a value unconditionally would re-claim
            // an upload that is already claimed on the server.
            val mediaId = icon?.mediaId
            when {
                mediaId != null -> put("iconMediaId", mediaId)
                uiState.channelFormIconRemoved -> put("iconMediaId", JSONObject.NULL)
            }
        }

        val editingId = uiState.channelFormId
        uiState = uiState.copy(adminBusy = true, messageCode = null)

        viewModelScope.launch {
            val result = if (editingId == null) {
                repo.adminCreateChannel(body)
            } else {
                repo.adminUpdateChannel(editingId, body)
            }

            uiState = when (result) {
                is ApiResult.Ok -> uiState.copy(
                    adminBusy = false,
                    channelFormOpen = false,
                    channelFormIcon = null,
                    channelFormIconRemoved = false
                )
                is ApiResult.Failed -> uiState.copy(
                    adminBusy = false,
                    messageCode = adminFailureCode(result)
                )
                ApiResult.Unreachable -> uiState.copy(adminBusy = false, messageCode = "unreachable")
            }
            if (result is ApiResult.Ok) {
                loadAdminChannels()
                // The public list is refetched too, so a channel an administrator
                // has just created is visible in the same session (§20).
                refreshChannels()
            }
        }
    }

    // ── The composer (§21) ───────────────────────────────────────────────

    fun startCompose() {
        uiState = uiState.copy(
            composerOpen = true,
            composerBody = "",
            composerAttachments = emptyList(),
            editingPostId = null,
            editingPostChannelId = null,
            composerBusy = false,
            messageCode = null
        )
    }

    /**
     * Edit an existing post (§21).
     *
     * The body is the stored text with its formatting markers intact, which is
     * what makes an edit round-trip: the editor shows exactly what was published,
     * markers and all, because that string IS the post. Nothing is decoded on the
     * way in and re-encoded on the way out, so nothing can be lost between them.
     *
     * Its media is shown, and cannot be changed: the server has no route that
     * swaps a post's files, and offering one that silently did nothing would be
     * worse than not offering it.
     */
    fun startEditPost(post: GoodPostPost) {
        uiState = uiState.copy(
            composerOpen = true,
            composerBody = post.body.orEmpty(),
            composerAttachments = emptyList(),
            editingPostId = post.id,
            // Scoped to the post's OWN channel rather than to whichever feed is
            // open, so the edit cannot be published into the wrong one.
            editingPostChannelId = post.channelId,
            composerBusy = false,
            messageCode = null
        )
    }

    fun onComposerBodyChange(value: String) {
        uiState = uiState.copy(composerBody = value)
    }

    /**
     * Attach a file the user picked (§21).
     *
     * Uploaded IMMEDIATELY, in the background, rather than at publish time. Two
     * reasons: a large video uploads while the caption is still being typed, and
     * a failure — no storage on this deployment, a file the server will not
     * store, a dropped connection — is reported against the attachment it belongs
     * to instead of as a publish that mysteriously did nothing.
     *
     * The row appears at once in its `Uploading` state, so the tap has a visible
     * effect even when the network is slow.
     */
    fun attachMedia(attachment: GoodPostAttachment) {
        val repo = repository ?: return
        // Signed in is the only precondition a caller can check. The token itself
        // belongs to the repository, which renews it when it has lapsed — a stale
        // copy held here is what made every call fail ten minutes in.
        if (uiState.admin == null) return

        // One kind per post, matching the server's own rule: an image and a video
        // in one post would need a renderer per asset and a type that describes
        // neither. Refused here with a sentence rather than after an upload.
        val existing = uiState.composerAttachments.firstOrNull()
        if (existing != null && existing.kind != attachment.kind) {
            uiState = uiState.copy(messageCode = "mixed_media")
            return
        }

        uiState = uiState.copy(
            composerAttachments = uiState.composerAttachments + attachment,
            messageCode = null
        )

        viewModelScope.launch {
            when (val result = repo.adminUploadMedia(attachment)) {
                is ApiResult.Ok -> updateAttachment(
                    attachment.uri,
                    GoodPostUploadState.Ready(result.value.id)
                )
                is ApiResult.Failed -> {
                    updateAttachment(attachment.uri, GoodPostUploadState.Failed(result.code))
                    // `media_unavailable` is an answer about the DEPLOYMENT rather
                    // than about the file, so the composer stops offering the
                    // picker for the rest of the session (§22).
                    if (result.code == "media_unavailable") {
                        uiState = uiState.copy(composerMediaAvailable = false)
                    }
                }
                ApiResult.Unreachable ->
                    updateAttachment(attachment.uri, GoodPostUploadState.Failed("unreachable"))
            }
        }
    }

    /**
     * Report a file this app will not even send.
     *
     * A document, an SVG, a file whose size the picker will not disclose: the
     * server would refuse all of them, and saying so before an upload is the
     * difference between a sentence and a failed transfer of a large file.
     */
    fun reportUnsupportedMedia() {
        uiState = uiState.copy(messageCode = "unsupported_media_type")
    }

    /** Drop an attachment, by the URI it was picked with. */
    fun removeMedia(uri: String) {
        uiState = uiState.copy(
            composerAttachments = uiState.composerAttachments.filterNot { it.uri == uri },
            messageCode = null
        )
    }

    private fun updateAttachment(uri: String, state: GoodPostUploadState) {
        uiState = uiState.copy(
            composerAttachments = uiState.composerAttachments.map { attachment ->
                if (attachment.uri == uri) attachment.copy(state = state) else attachment
            }
        )
    }

    /**
     * Leave the composer, whether the post was published, saved or abandoned
     * (§19 Bug 2).
     *
     * ONE exit path, deliberately. The editing-information strip is drawn from
     * [GoodPostUiState.editingPostId], so a path that closed the composer without
     * clearing that id left the strip on screen — the editor appeared to still be
     * editing a post that had already been saved, and the next `Post` tap
     * re-opened an edit of it instead of publishing a new one.
     *
     * Attachments are dropped with it. Their uploaded objects are never claimed
     * by a post, and the server's own sweep removes an unclaimed upload after the
     * configured window — the composer does not need a delete call to leave
     * nothing behind.
     */
    fun cancelCompose() {
        resetComposer()
    }

    /**
     * Empty the composer back to "writing a new post in no channel".
     *
     * Every field the editor owns, so no caller has to remember the list: a
     * partial reset is what the three bugs in this area all had in common.
     */
    private fun resetComposer() {
        uiState = uiState.copy(
            composerOpen = false,
            composerBody = "",
            composerBusy = false,
            editingPostId = null,
            editingPostChannelId = null,
            composerAttachments = emptyList(),
            messageCode = null
        )
    }

    fun publish() {
        val repo = repository ?: return
        // Signed in is the only precondition a caller can check. The token itself
        // belongs to the repository, which renews it when it has lapsed — a stale
        // copy held here is what made every call fail ten minutes in.
        if (uiState.admin == null) return
        val channelId = when (val screen = uiState.screen) {
            is GoodPostScreen.AdminChannel -> screen.channelId
            is GoodPostScreen.Channel -> screen.channelId
            else -> return
        }

        val body = uiState.composerBody.trim()
        val editingId = uiState.editingPostId
        val attachments = uiState.composerAttachments

        // An edit is confined to the channel it was started in. `forChannel`
        // already clears it on a switch, so reaching here with a mismatched pair
        // would take a race — but refusing is still the right answer, because the
        // alternative is writing one channel's words into another's history.
        if (editingId != null && uiState.editingPostChannelId != channelId) {
            resetComposer()
            uiState = uiState.copy(messageCode = "not_found")
            return
        }

        // Refused BEFORE anything is sent. An upload still in flight, or one that
        // failed, has no media id to attach — so publishing now would either
        // publish the post without the file the user thinks they attached, or
        // fail at the server with a code about an id that was never real.
        if (editingId == null && attachments.any { it.mediaId == null }) {
            val failed = attachments.any { it.state is GoodPostUploadState.Failed }
            uiState = uiState.copy(
                messageCode = if (failed) "attachment_failed" else "attachment_uploading"
            )
            return
        }

        if (body.isEmpty() && attachments.isEmpty()) {
            uiState = uiState.copy(messageCode = "empty_post")
            return
        }

        // Text, plus whatever files are attached — and nothing else. There is
        // deliberately no link field (§16): a plain URL typed into a post's text
        // is already a link as far as a reader is concerned, and a separate
        // "add link" workflow was a second way to express the same thing, with
        // its own validation and its own failure modes.
        val payload = JSONObject().apply {
            put("body", body)
            if (editingId == null && attachments.isNotEmpty()) {
                put(
                    "mediaIds",
                    org.json.JSONArray(attachments.mapNotNull { it.mediaId })
                )
            }
        }

        uiState = uiState.copy(composerBusy = true, messageCode = null)

        viewModelScope.launch {
            val result = if (editingId == null) {
                repo.adminCreatePost(channelId, payload)
            } else {
                repo.adminUpdatePost(editingId, payload)
            }

            if (result is ApiResult.Ok) {
                // A successful publish or save ends the edit, so the editing strip
                // goes with it (§19 Bug 2). This is the SAME exit path Cancel
                // takes, which is what stops the two from drifting apart again.
                resetComposer()
                // Reloaded on success, so a published post appears immediately and
                // an edit is visible without a pull (§21).
                loadPosts(channelId)
                return@launch
            }

            // The code the SERVER gave, so `media_not_ready`, `media_already_used`
            // and `unknown_media` each get their own sentence rather than a
            // generic failure.
            uiState = uiState.copy(
                composerBusy = false,
                messageCode = if (result is ApiResult.Failed) {
                    adminFailureCode(result)
                } else {
                    "unreachable"
                }
            )
        }
    }

    /** Dismiss the last message, so it cannot be read twice. */
    fun clearMessage() {
        uiState = uiState.copy(messageCode = null)
    }

    /**
     * The message for a failed administrator call, ending the session when the
     * failure means there is no session left.
     *
     * A 401 can only reach here after the repository's own renewal was refused —
     * it renews once, silently, before any call reports a failure — so it is not a
     * transient error worth retrying. The session is over, and leaving the
     * administrator on a screen whose every action would fail is worse than asking
     * for the password again.
     */
    private fun adminFailureCode(result: ApiResult<*>): String {
        if (result !is ApiResult.Failed) return "unreachable"
        if (result.status != 401) return result.code
        adminSignOut()
        return "session_expired"
    }

    /** Called when the tab is left, so a shared-page error does not persist. */
    fun onHidden() {
        uiState = uiState.copy(messageCode = null)
    }
}

/** A page of posts as the feed renders it: date separators, then the posts. */
internal sealed interface FeedEntry {
    val key: String

    data class Separator(val label: String, val at: Long) : FeedEntry {
        override val key: String get() = "sep-$at"
    }

    data class Post(val post: GoodPostPost) : FeedEntry {
        override val key: String get() = "post-${post.id}"
    }
}

/**
 * Group posts under date separators (§10).
 *
 * Pure, and separate from the composable that draws it, so the one rule that
 * matters — a separator appears when the DAY changes, not when the clock does —
 * is unit-testable without a screen.
 */
internal fun withDateSeparators(posts: List<GoodPostPost>): List<FeedEntry> {
    if (posts.isEmpty()) return emptyList()

    val entries = ArrayList<FeedEntry>(posts.size + 4)
    var previousDay: Long? = null

    posts.forEach { post ->
        val at = parseIsoMillis(post.createdAt)
        if (at != null && (previousDay == null || !waSameDay(previousDay, at))) {
            entries.add(FeedEntry.Separator(waDayLabel(at), at))
        }
        if (at != null) previousDay = at
        entries.add(FeedEntry.Post(post))
    }

    return entries
}
