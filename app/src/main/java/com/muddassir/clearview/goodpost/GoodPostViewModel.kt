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

    /** The administrator's channel list (§19). */
    data object AdminHome : GoodPostScreen

    /** One channel, as its administrator sees it (§19, §21). */
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
    /** Channels this device has opened, so the row's dot is honest (§4). */
    val openedChannelIds: Set<String> = emptySet(),
    val mutedChannelIds: Set<String> = emptySet(),

    // ── A channel's feed (§8, §9, §10) ───────────────────────────────────
    val channel: GoodPostChannel? = null,
    val posts: List<GoodPostPost> = emptyList(),
    val postsLoading: Boolean = false,
    val postsStale: Boolean = false,
    val postsCursor: String? = null,
    val postsError: String? = null,
    val postsLoadingMore: Boolean = false,

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
    val adminChannels: List<GoodPostChannel> = emptyList(),
    val adminChannelsLoading: Boolean = false,

    /** A message code from the last failed action, worded by [goodPostErrorFor]. */
    val messageCode: String? = null,

    // ── The composer (§21) ───────────────────────────────────────────────
    val composerOpen: Boolean = false,
    val composerBody: String = "",
    val composerLink: String = "",
    val composerBusy: Boolean = false,
    /** Set while editing an existing post rather than creating one. */
    val editingPostId: String? = null,
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
                    channelsError = null,
                    openedChannelIds = uiState.openedChannelIds,
                    mutedChannelIds = repo.mutedChannelIds()
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

    /** Open a channel's feed. Fetches the channel if it is not already known. */
    fun openChannel(channelId: String) {
        uiState = uiState.copy(
            channel = uiState.channels.firstOrNull { it.id == channelId },
            posts = emptyList(),
            postsCursor = null,
            postsError = null,
            postsStale = false,
            messageCode = null
        )
        open(GoodPostScreen.Channel(channelId))

        // The dot is cleared locally the moment the channel is opened. There is
        // no server-side read state to clear, because no reader has an account.
        uiState = uiState.copy(openedChannelIds = uiState.openedChannelIds + channelId)
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

    /**
     * Mute or unmute a channel's notifications (§14).
     *
     * Stored on this device only, and the screen says so: a reader has no account
     * to hang the preference on, and there is no push infrastructure behind it
     * yet. Recording the intent locally is the honest version of the switch —
     * inventing a server-side follow would be inventing the account this product
     * deliberately does not have.
     */
    fun toggleMuted(channelId: String, muted: Boolean) {
        repository?.setChannelMuted(channelId, muted)
        val next = uiState.mutedChannelIds.toMutableSet()
        if (muted) next.add(channelId) else next.remove(channelId)
        uiState = uiState.copy(mutedChannelIds = next)
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

    fun openAdmin() {
        // Straight to the channel list when a session is already stored, so an
        // administrator does not retype a password every time they open the tab.
        open(if (uiState.admin != null) GoodPostScreen.AdminHome else GoodPostScreen.AdminLogin)
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
                    uiState = uiState.copy(
                        admin = result.value,
                        adminPassword = "",
                        adminBusy = false,
                        messageCode = null,
                        backStack = listOf(GoodPostScreen.Home, GoodPostScreen.AdminHome)
                    )
                    loadAdminChannels()
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
        repository?.adminSignOut()
        GoodPostImages.clear()
        uiState = uiState.copy(
            admin = null,
            adminEmail = "",
            adminPassword = "",
            adminChannels = emptyList(),
            backStack = listOf(GoodPostScreen.Home)
        )
        refreshChannels()
    }

    fun loadAdminChannels() {
        val repo = repository ?: return
        val token = uiState.admin?.token ?: return

        uiState = uiState.copy(adminChannelsLoading = true)
        viewModelScope.launch {
            val result = repo.adminChannels(token)
            uiState = if (result is ApiResult.Ok) {
                uiState.copy(adminChannels = result.value, adminChannelsLoading = false)
            } else {
                uiState.copy(adminChannelsLoading = false)
            }
        }
    }

    fun openAdminChannel(channelId: String) {
        uiState = uiState.copy(
            channel = uiState.adminChannels.firstOrNull { it.id == channelId }
                ?: uiState.channels.firstOrNull { it.id == channelId },
            posts = emptyList(),
            postsCursor = null
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
        val token = uiState.admin?.token ?: return

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
            when (val result = repo.adminUploadMedia(token, attachment)) {
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
        val token = uiState.admin?.token ?: return
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
                repo.adminCreateChannel(token, body)
            } else {
                repo.adminUpdateChannel(token, editingId, body)
            }

            uiState = when (result) {
                is ApiResult.Ok -> uiState.copy(
                    adminBusy = false,
                    channelFormOpen = false,
                    channelFormIcon = null,
                    channelFormIconRemoved = false
                )
                is ApiResult.Failed -> uiState.copy(adminBusy = false, messageCode = result.code)
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
            composerLink = "",
            composerAttachments = emptyList(),
            editingPostId = null,
            messageCode = null
        )
    }

    /**
     * Edit an existing post's text or link (§21).
     *
     * Its media is shown, and cannot be changed: the server has no route that
     * swaps a post's files, and offering one that silently did nothing would be
     * worse than not offering it.
     */
    fun startEditPost(post: GoodPostPost) {
        uiState = uiState.copy(
            composerOpen = true,
            composerBody = post.body.orEmpty(),
            composerLink = post.linkUrl.orEmpty(),
            composerAttachments = emptyList(),
            editingPostId = post.id,
            messageCode = null
        )
    }

    fun onComposerBodyChange(value: String) {
        uiState = uiState.copy(composerBody = value)
    }

    fun onComposerLinkChange(value: String) {
        uiState = uiState.copy(composerLink = value)
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
        val token = uiState.admin?.token ?: return

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
            when (val result = repo.adminUploadMedia(token, attachment)) {
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

    fun cancelCompose() {
        // Attachments are dropped with the dialog. Their uploaded objects are
        // never claimed by a post, and the server's own sweep removes an
        // unclaimed upload after the configured window — the composer does not
        // need a delete call to leave nothing behind.
        uiState = uiState.copy(
            composerOpen = false,
            composerAttachments = emptyList(),
            messageCode = null
        )
    }

    fun publish() {
        val repo = repository ?: return
        val token = uiState.admin?.token ?: return
        val channelId = when (val screen = uiState.screen) {
            is GoodPostScreen.AdminChannel -> screen.channelId
            is GoodPostScreen.Channel -> screen.channelId
            else -> return
        }

        val body = uiState.composerBody.trim()
        val link = uiState.composerLink.trim()
        val editingId = uiState.editingPostId
        val attachments = uiState.composerAttachments

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

        if (body.isEmpty() && link.isEmpty() && attachments.isEmpty()) {
            uiState = uiState.copy(messageCode = "empty_post")
            return
        }

        val payload = JSONObject().apply {
            put("body", body)
            if (link.isNotEmpty()) put("linkUrl", link)
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
                repo.adminCreatePost(token, channelId, payload)
            } else {
                repo.adminUpdatePost(token, editingId, payload)
            }

            uiState = when (result) {
                is ApiResult.Ok -> uiState.copy(
                    composerBusy = false,
                    composerOpen = false,
                    composerAttachments = emptyList()
                )
                // The code the SERVER gave, so `media_not_ready`,
                // `media_already_used` and `unknown_media` each get their own
                // sentence instead of a generic failure.
                is ApiResult.Failed -> uiState.copy(composerBusy = false, messageCode = result.code)
                ApiResult.Unreachable -> uiState.copy(composerBusy = false, messageCode = "unreachable")
            }
            // The feed is reloaded either way on success, so a published post
            // appears immediately and an edit is visible without a pull (§21).
            if (result is ApiResult.Ok) loadPosts(channelId)
        }
    }

    fun deletePost(post: GoodPostPost) {
        val repo = repository ?: return
        val token = uiState.admin?.token ?: return

        viewModelScope.launch {
            val result = repo.adminDeletePost(token, post.id)
            if (result is ApiResult.Ok) {
                // Removed locally first, so the row leaves the list at the speed
                // of the tap rather than of the round trip.
                uiState = uiState.copy(posts = uiState.posts.filterNot { it.id == post.id })
                loadPosts(post.channelId)
            } else if (result is ApiResult.Failed) {
                uiState = uiState.copy(messageCode = result.code)
            }
        }
    }

    /** Dismiss the last message, so it cannot be read twice. */
    fun clearMessage() {
        uiState = uiState.copy(messageCode = null)
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
