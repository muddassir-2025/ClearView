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
import kotlinx.coroutines.launch

/** The two halves of Good Post home (§4, §5). */
enum class GoodPostSection { Channels, Discover }

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
    val signedOut: Boolean = false
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

    var uiState by mutableStateOf(GoodPostHomeUiState())
        private set

    private val repo: GoodPostChannelsRepository? get() = repository

    /** Idempotent: the tab can be left and re-entered without refetching. */
    fun initialize(context: Context) {
        if (repository != null) return
        repository = GoodPostChannelsRepository(context.applicationContext)
        loadCategories()
        loadFollowing()
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
    }

    // ── Channels list (§4) ──────────────────────────────────────────────

    fun refresh() {
        when (uiState.section) {
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
                is ChannelsResult.Ok -> {
                    uiState = uiState.copy(channel = result.value, loading = false, stale = false)
                    // Opening counts as reading: clear the badge locally and
                    // tell the server best-effort.
                    clearUnreadLocally(channelId)
                    source.markRead(channelId)
                }

                is ChannelsResult.Stale -> {
                    uiState = uiState.copy(channel = result.value, loading = false, stale = true)
                    clearUnreadLocally(channelId)
                }

                is ChannelsResult.Failed ->
                    uiState = uiState.copy(loading = false, messageCode = result.code)

                ChannelsResult.SignedOut -> uiState = uiState.copy(
                    loading = false,
                    signedOut = true
                )
            }
        }
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
