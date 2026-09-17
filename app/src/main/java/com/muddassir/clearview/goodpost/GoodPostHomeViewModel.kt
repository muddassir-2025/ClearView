package com.muddassir.clearview.goodpost

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.AccentChoice
import com.muddassir.clearview.goodpost.data.BackdropChoice
import com.muddassir.clearview.goodpost.data.ChannelSort
import com.muddassir.clearview.goodpost.data.EditState
import com.muddassir.clearview.goodpost.data.EditableBitmap
import com.muddassir.clearview.goodpost.data.ChannelsResult
import com.muddassir.clearview.goodpost.data.EngagementResult
import com.muddassir.clearview.goodpost.data.GoodPostAnalytics
import com.muddassir.clearview.goodpost.data.GoodPostCategory
import com.muddassir.clearview.goodpost.data.GoodPostChannel
import com.muddassir.clearview.goodpost.data.GoodPostChannelCodec
import com.muddassir.clearview.goodpost.data.GoodPostChannelsRepository
import com.muddassir.clearview.goodpost.data.GoodPostConversation
import com.muddassir.clearview.goodpost.data.GoodPostEngagement
import com.muddassir.clearview.goodpost.data.GoodPostEngagementCodec
import com.muddassir.clearview.goodpost.data.GoodPostEngagementRepository
import com.muddassir.clearview.goodpost.data.GoodPostInboxRepository
import com.muddassir.clearview.goodpost.data.GoodPostMedia
import com.muddassir.clearview.goodpost.data.GoodPostMessage
import com.muddassir.clearview.goodpost.data.MediaUploadRequest
import com.muddassir.clearview.goodpost.data.GoodPostNotice
import com.muddassir.clearview.goodpost.data.GoodPostNotification
import com.muddassir.clearview.goodpost.data.GoodPostPoll
import com.muddassir.clearview.goodpost.data.GoodPostPost
import com.muddassir.clearview.goodpost.data.GoodPostPostCodec
import com.muddassir.clearview.goodpost.data.GoodPostPostsRepository
import com.muddassir.clearview.goodpost.data.GoodPostReportTarget
import com.muddassir.clearview.goodpost.data.GlassChoice
import com.muddassir.clearview.goodpost.data.GoodPostReportTargetRef
import com.muddassir.clearview.goodpost.data.GoodPostTheme
import com.muddassir.clearview.goodpost.data.GoodPostThemeStore
import com.muddassir.clearview.goodpost.data.GoodPostPushTokens
import com.muddassir.clearview.goodpost.data.InboxResult
import com.muddassir.clearview.goodpost.data.PollDraft
import com.muddassir.clearview.goodpost.data.PostsResult
import com.muddassir.clearview.goodpost.data.isoFromMillis
import com.muddassir.clearview.goodpost.data.uploadRequestFor
import com.muddassir.clearview.goodpost.data.uploadRequestForFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The parts of Good Post home (§4, §5).
 *
 * [Posts] is first because it is what a returning user opens: §4's two viewing
 * modes are the aggregated feed and the channel list, and Discover is the way
 * in for someone who has not followed anything yet.
 */
enum class GoodPostSection(@get:StringRes val labelRes: Int) {
    Channels(R.string.goodpost_section_channels),

    /**
     * The channels this account follows plus their posts, as one stream.
     *
     * Named "Updates" rather than "Posts" because that is what it is: the
     * second segment of a channels screen in a messaging app shows what arrived
     * since the user last looked, not a read-only archive of everything.
     */
    Posts(R.string.goodpost_section_posts),

    /** Search, reached from the bar's magnifier. */
    Discover(R.string.goodpost_find_channels),

    /** Mail, reached from the bar's bell. */
    Inbox(R.string.goodpost_section_inbox)
}

/**
 * The three things that arrive for the account (§16, §17, §26).
 *
 * One section with three tabs rather than three sections, because they are the
 * same kind of thing — mail, not content — and a fourth segment would make the
 * section bar unreadable on a phone.
 */
enum class GoodPostInboxTab { Notifications, Notices, Messages }

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

    /**
     * Channels this account owns (§6).
     *
     * One is the current limit, so this doubles as the answer to "may I create
     * a channel?". Kept apart from [following] because owning a channel and
     * following one are different relationships, and a list that mixed them
     * could not tell the two actions apart.
     */
    val ownChannels: List<GoodPostChannel> = emptyList(),

    /**
     * Media the reader has tapped open this session (§10).
     *
     * An image arrives soft — a preview, so nothing full-size was pulled — and
     * a tap both sharpens it and starts the download. This records the sharp
     * part, which is deliberately SESSION-ONLY: a reveal is not a decision to
     * keep a file, so it is not persisted, and a restart returns every image to
     * its preview state unless it was actually saved.
     */
    val revealedMediaIds: Set<String> = emptySet(),

    // ── The editor (§ media editing) ────────────────────────────────────
    /**
     * The picture currently open in the editor.
     *
     * A decoded bitmap rather than a URI: the editor re-renders it on every
     * slider move, and re-decoding a 12-megapixel file per frame is what makes
     * an editor feel broken. Null means the editor is closed.
     */
    val editingImage: EditableBitmap? = null,
    val editingImageLoading: Boolean = false,
    val editingImageSaving: Boolean = false,

    // ── Appearance ──────────────────────────────────────────────────────
    /** Whether the appearance sheet is open. */
    val appearanceOpen: Boolean = false,

    /**
     * The theme in force, mirrored into state.
     *
     * [GoodPostThemeStore.current] is already the source of truth the UI colours
     * itself from, so this copy is not what paints anything. It is here so a
     * CHOICE has something to be compared against — a swatch has to know it is
     * the selected one — and so a change recomposes the sheet as well as the
     * screens behind it.
     */
    val theme: GoodPostTheme = GoodPostTheme.Default,

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
    /**
     * §14: the composer is writing a poll instead of a file post.
     *
     * A mode rather than extra fields, because a poll and a file cannot share a
     * post — the server refuses `poll_with_media` — and a UI that let both be
     * filled in would be building a request it knows will fail.
     */
    val composerPollMode: Boolean = false,
    val composerPollQuestion: String = "",
    /** Always at least two: a poll with one option is not a question. */
    val composerPollOptions: List<String> = listOf("", ""),
    val composerPollMultiple: Boolean = false,

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
    val savingMediaId: String? = null,

    // ── Engagement (§13, §14, §15) ──────────────────────────────────────
    /**
     * Posts this session has already pinged a view for.
     *
     * Kept so one scroll does not fire a request per recomposition. The server
     * dedupes as well — this only avoids the traffic, and the server's window
     * is still what decides whether a look is ever counted twice.
     */
    val viewedPostIds: Set<String> = emptySet(),
    /** The post whose reaction is in flight, so its buttons can be disabled. */
    val busyReactionPostId: String? = null,
    /** The poll whose vote is in flight. */
    val votingPollId: String? = null,
    val analytics: GoodPostAnalytics? = null,
    /** Which channel [analytics] belongs to, so the title cannot be wrong. */
    val analyticsChannelId: String? = null,
    val analyticsLoading: Boolean = false,

    // ── Inbox (§16, §17, §26) ───────────────────────────────────────────
    val inboxTab: GoodPostInboxTab = GoodPostInboxTab.Notifications,
    val notifications: List<GoodPostNotification> = emptyList(),
    val notices: List<GoodPostNotice> = emptyList(),
    val conversations: List<GoodPostConversation> = emptyList(),
    val inboxLoading: Boolean = false,
    val inboxStale: Boolean = false,

    // ── One private thread (§16) ────────────────────────────────────────
    val conversation: GoodPostConversation? = null,
    val messages: List<GoodPostMessage> = emptyList(),
    val messageDraft: String = "",
    val messagesLoading: Boolean = false,
    val sendingMessage: Boolean = false,
    /**
     * A message the server did not accept, kept so it can be retried.
     *
     * §36: the app must not claim a message was sent until the server says so,
     * and losing what someone typed because a send failed is worse than showing
     * it as unsent.
     */
    val unsentMessage: String? = null,
    /** The channel inbox, for a channel the viewer administers. */
    val channelInbox: List<GoodPostConversation>? = null,
    val channelInboxId: String? = null,

    // ── Reports (§18) ───────────────────────────────────────────────────
    val reportTarget: GoodPostReportTargetRef? = null,
    val reporting: Boolean = false,
    /** Shown once after the server accepted a report, then dismissed. */
    val reportSent: Boolean = false
) {
    /** The badge on the Inbox tab: how much of the account's mail is unread. */
    val unreadCount: Int
        get() = notifications.count { it.isUnread } +
            notices.count { it.isUnread } +
            conversations.sumOf { it.unreadCount }

    /**
     * Whether to offer a "create channel" action.
     *
     * One channel per account is the current rule (§6), so this is simply
     * "owns none". It stays true while [ownChannels] is still unknown, so a slow
     * or failed lookup never hides a feature — the server refuses a second
     * channel regardless, and that refusal is what actually enforces the limit.
     */
    val canCreateChannel: Boolean get() = ownChannels.isEmpty()
}

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

    private companion object {
        /**
         * The most options a poll may offer.
         *
         * A copy of the server's `POLL_MAX_OPTIONS`, and the only product limit
         * this client states for itself: stopping here keeps a user from
         * carefully writing an option the API will refuse. The server still
         * enforces it — this is a courtesy, never the authority.
         */
        const val MAX_POLL_OPTIONS = 10
    }

    private var repository: GoodPostChannelsRepository? = null
    private var postsRepository: GoodPostPostsRepository? = null
    private var engagementRepository: GoodPostEngagementRepository? = null
    private var inboxRepository: GoodPostInboxRepository? = null

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
    private val engagement: GoodPostEngagementRepository? get() = engagementRepository
    private val inbox: GoodPostInboxRepository? get() = inboxRepository

    /** Idempotent: the tab can be left and re-entered without refetching. */
    fun initialize(context: Context) {
        if (repository != null) return
        // Device preferences first: the very first frame should already be the
        // look this phone chose, rather than flashing the default accent.
        GoodPostThemeStore.load(context)
        uiState = uiState.copy(theme = GoodPostThemeStore.current)

        appContext = context.applicationContext
        repository = GoodPostChannelsRepository(context.applicationContext)
        postsRepository = GoodPostPostsRepository(context.applicationContext)
        engagementRepository = GoodPostEngagementRepository(context.applicationContext)
        inboxRepository = GoodPostInboxRepository(context.applicationContext)
        loadCategories()
        loadFollowing()
        loadOwnChannels()
        loadFeed()
        loadInbox()
        // A push token can exist before the account signs in — Firebase mints
        // one on first launch — so it is registered here rather than only from
        // the messaging callback, which may never fire again on this install.
        registerPushToken()
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
        // The inbox is loaded when it is opened rather than at startup: it is
        // mail, and a returning user may have none. `loadInbox` is idempotent
        // per tab, so re-entering does not refetch what is already on screen.
        if (section == GoodPostSection.Inbox) loadInbox()
    }

    // ── Inbox (§16, §17, §26) ────────────────────────────────────────────

    fun selectInboxTab(tab: GoodPostInboxTab) {
        if (uiState.inboxTab == tab) return
        uiState = uiState.copy(inboxTab = tab, messageCode = null)
        loadInbox()
    }

    /**
     * Load the open inbox tab.
     *
     * One entry point for all three so the section can call it on open, on
     * refresh and on a tab change without knowing which fetch that implies —
     * and so the loading flag is set in exactly one place.
     */
    fun loadInbox() {
        val source = inbox ?: return
        viewModelScope.launch {
            uiState = uiState.copy(inboxLoading = true, messageCode = null)

            when (uiState.inboxTab) {
                GoodPostInboxTab.Notifications -> adoptNotifications(source.notifications())
                GoodPostInboxTab.Notices -> adoptNotices(source.notices())
                GoodPostInboxTab.Messages -> adoptConversations(source.ownConversations())
            }
        }
    }

    private fun adoptNotifications(result: InboxResult<List<GoodPostNotification>>) {
        uiState = when (result) {
            is InboxResult.Ok -> uiState.copy(
                notifications = result.value,
                inboxLoading = false,
                inboxStale = false
            )

            is InboxResult.Stale -> uiState.copy(
                notifications = result.value,
                inboxLoading = false,
                inboxStale = true
            )

            is InboxResult.Failed -> uiState.copy(inboxLoading = false, messageCode = result.code)
            InboxResult.SignedOut -> uiState.copy(inboxLoading = false, signedOut = true)
        }
    }

    private fun adoptNotices(result: InboxResult<List<GoodPostNotice>>) {
        uiState = when (result) {
            is InboxResult.Ok -> uiState.copy(
                notices = result.value,
                inboxLoading = false,
                inboxStale = false
            )

            is InboxResult.Stale -> uiState.copy(
                notices = result.value,
                inboxLoading = false,
                inboxStale = true
            )

            is InboxResult.Failed -> uiState.copy(inboxLoading = false, messageCode = result.code)
            InboxResult.SignedOut -> uiState.copy(inboxLoading = false, signedOut = true)
        }
    }

    private fun adoptConversations(result: InboxResult<List<GoodPostConversation>>) {
        uiState = when (result) {
            is InboxResult.Ok -> uiState.copy(
                conversations = result.value,
                inboxLoading = false,
                inboxStale = false
            )

            is InboxResult.Stale -> uiState.copy(
                conversations = result.value,
                inboxLoading = false,
                inboxStale = true
            )

            is InboxResult.Failed -> uiState.copy(inboxLoading = false, messageCode = result.code)
            InboxResult.SignedOut -> uiState.copy(inboxLoading = false, signedOut = true)
        }
    }

    /**
     * Mark one notification read, or the whole inbox when [id] is null.
     *
     * The list is updated from the SERVER's answer rather than optimistically:
     * a badge cleared locally for a receipt the server never received would come
     * back on the next fetch, which looks like the app forgetting (§36).
     */
    fun markNotificationsRead(id: String? = null) {
        val source = inbox ?: return
        viewModelScope.launch {
            val ids = if (id == null) emptyList() else listOf(id)
            when (val result = source.markNotificationsRead(ids)) {
                is InboxResult.Ok -> {
                    // Stamped with a real time rather than a placeholder, so an
                    // "unread" test never depends on a sentinel value nobody
                    // documents.
                    val stamp = isoFromMillis(System.currentTimeMillis())
                    uiState = uiState.copy(
                        notifications = uiState.notifications.map { item ->
                            val targeted = ids.isEmpty() || ids.contains(item.id)
                            if (targeted) item.copy(readAt = item.readAt ?: stamp) else item
                        }
                    )
                }

                is InboxResult.Failed -> uiState = uiState.copy(messageCode = result.code)
                is InboxResult.Stale -> Unit
                InboxResult.SignedOut -> uiState = uiState.copy(signedOut = true)
            }
        }
    }

    fun markNoticesRead(id: String) {
        val source = inbox ?: return
        viewModelScope.launch {
            when (val result = source.markNoticesRead(listOf(id))) {
                is InboxResult.Ok -> {
                    val stamp = isoFromMillis(System.currentTimeMillis())
                    uiState = uiState.copy(
                        notices = uiState.notices.map { item ->
                            if (item.id == id) item.copy(readAt = item.readAt ?: stamp) else item
                        }
                    )
                }

                is InboxResult.Failed -> uiState = uiState.copy(messageCode = result.code)
                is InboxResult.Stale -> Unit
                InboxResult.SignedOut -> uiState = uiState.copy(signedOut = true)
            }
        }
    }

    // ── One private thread (§16) ────────────────────────────────────────

    /** Open the caller's thread with a channel, creating it on first use. */
    fun openChannelConversation(channelId: String) {
        val source = inbox ?: return
        viewModelScope.launch {
            uiState = uiState.copy(messagesLoading = true, messageCode = null)

            when (val result = source.openConversation(channelId)) {
                is InboxResult.Ok -> openThread(result.value.id)
                is InboxResult.Failed -> uiState = uiState.copy(
                    messagesLoading = false,
                    messageCode = result.code
                )

                is InboxResult.Stale -> uiState = uiState.copy(messagesLoading = false)
                InboxResult.SignedOut -> uiState = uiState.copy(
                    messagesLoading = false,
                    signedOut = true
                )
            }
        }
    }

    fun openThread(conversationId: String) {
        val source = inbox ?: return
        viewModelScope.launch {
            uiState = uiState.copy(
                messagesLoading = true,
                messageCode = null,
                unsentMessage = null
            )

            when (val result = source.messages(conversationId)) {
                is InboxResult.Ok -> uiState = uiState.copy(
                    conversation = result.value.conversation
                        ?: uiState.conversations.firstOrNull { it.id == conversationId },
                    messages = result.value.items,
                    messagesLoading = false
                )

                is InboxResult.Failed -> uiState = uiState.copy(
                    messagesLoading = false,
                    messageCode = result.code
                )

                is InboxResult.Stale -> uiState = uiState.copy(messagesLoading = false)
                InboxResult.SignedOut -> uiState = uiState.copy(
                    messagesLoading = false,
                    signedOut = true
                )
            }
        }
    }

    fun closeThread() {
        uiState = uiState.copy(
            conversation = null,
            messages = emptyList(),
            messageDraft = "",
            unsentMessage = null,
            messageCode = null
        )
        // The thread may have been read, so the list's unread counts are stale.
        if (uiState.inboxTab == GoodPostInboxTab.Messages) loadInbox()
    }

    fun onMessageDraftChange(value: String) {
        uiState = uiState.copy(messageDraft = value, unsentMessage = null, messageCode = null)
    }

    fun sendMessage() {
        val source = inbox ?: return
        val conversation = uiState.conversation ?: return
        val text = uiState.messageDraft.trim()
        if (text.isEmpty() || uiState.sendingMessage) return

        viewModelScope.launch {
            uiState = uiState.copy(sendingMessage = true, messageCode = null)

            when (val result = source.sendMessage(conversation.id, text)) {
                is InboxResult.Ok -> uiState = uiState.copy(
                    messages = uiState.messages + result.value,
                    messageDraft = "",
                    sendingMessage = false,
                    unsentMessage = null
                )

                is InboxResult.Failed -> uiState = uiState.copy(
                    sendingMessage = false,
                    // The draft is KEPT and reported as unsent, so a failed
                    // send never loses what the user wrote (§36).
                    unsentMessage = text,
                    messageCode = result.code
                )

                is InboxResult.Stale -> uiState = uiState.copy(sendingMessage = false)
                InboxResult.SignedOut -> uiState = uiState.copy(
                    sendingMessage = false,
                    signedOut = true
                )
            }
        }
    }

    /** §16: the channel side blocking or unblocking one thread. */
    fun toggleConversationBlock() {
        val source = inbox ?: return
        val conversation = uiState.conversation ?: return
        viewModelScope.launch {
            uiState = uiState.copy(sendingMessage = true, messageCode = null)

            val result = source.setConversationBlocked(conversation.id, !conversation.blocked)
            uiState = when (result) {
                is InboxResult.Ok -> uiState.copy(
                    conversation = result.value,
                    sendingMessage = false
                )

                is InboxResult.Failed -> uiState.copy(
                    sendingMessage = false,
                    messageCode = result.code
                )

                is InboxResult.Stale -> uiState.copy(sendingMessage = false)
                InboxResult.SignedOut -> uiState.copy(
                    sendingMessage = false,
                    signedOut = true
                )
            }
        }
    }

    // ── A channel's own inbox (§16) ─────────────────────────────────────

    fun openChannelInbox(channelId: String) {
        val source = inbox ?: return
        viewModelScope.launch {
            uiState = uiState.copy(inboxLoading = true, messageCode = null)

            when (val result = source.channelConversations(channelId)) {
                is InboxResult.Ok -> uiState = uiState.copy(
                    channelInbox = result.value,
                    channelInboxId = channelId,
                    inboxLoading = false
                )

                is InboxResult.Failed -> uiState = uiState.copy(
                    inboxLoading = false,
                    messageCode = result.code
                )

                is InboxResult.Stale -> uiState = uiState.copy(inboxLoading = false)
                InboxResult.SignedOut -> uiState = uiState.copy(
                    inboxLoading = false,
                    signedOut = true
                )
            }
        }
    }

    fun closeChannelInbox() {
        uiState = uiState.copy(channelInbox = null, channelInboxId = null)
    }

    /**
     * Put a channel's own analytics on screen.
     *
     * The window is left to the server's `ANALYTICS_WINDOW_DAYS`: it is a
     * deployment setting, and duplicating the number here would leave the two
     * disagreeing the day it is tuned.
     */
    fun openAnalytics(channelId: String) {
        val source = engagement ?: return
        viewModelScope.launch {
            uiState = uiState.copy(
                analyticsLoading = true,
                analyticsChannelId = channelId,
                messageCode = null
            )

            when (val result = source.analytics(channelId)) {
                is EngagementResult.Ok -> uiState = uiState.copy(
                    analytics = result.value,
                    analyticsLoading = false
                )

                is EngagementResult.Failed -> uiState = uiState.copy(
                    analyticsLoading = false,
                    analyticsChannelId = null,
                    messageCode = result.code
                )

                EngagementResult.SignedOut -> uiState = uiState.copy(
                    analyticsLoading = false,
                    signedOut = true
                )
            }
        }
    }

    fun closeAnalytics() {
        uiState = uiState.copy(analytics = null, analyticsChannelId = null)
    }

    // ── Reactions, polls and views (§13, §14, §15) ──────────────────────

    /**
     * React, change a reaction, or clear it by tapping the same one again.
     *
     * The counts come back from the server rather than being nudged locally, so
     * two people reacting at the same moment cannot leave the number wrong.
     */
    fun react(post: GoodPostPost, reaction: String) {
        val source = engagement ?: return
        if (uiState.busyReactionPostId != null) return

        val next = if (post.engagement.viewerReaction == reaction) null else reaction

        viewModelScope.launch {
            uiState = uiState.copy(busyReactionPostId = post.id, messageCode = null)

            when (val result = source.react(post.id, next)) {
                is EngagementResult.Ok -> uiState = uiState.copy(
                    feed = GoodPostPostCodec.replaceEngagement(
                        uiState.feed,
                        post.id,
                        GoodPostEngagementCodec.withReaction(post.engagement, result.value)
                    ),
                    channelPosts = GoodPostPostCodec.replaceEngagement(
                        uiState.channelPosts,
                        post.id,
                        GoodPostEngagementCodec.withReaction(post.engagement, result.value)
                    ),
                    busyReactionPostId = null
                )

                is EngagementResult.Failed -> uiState = uiState.copy(
                    busyReactionPostId = null,
                    messageCode = result.code
                )

                EngagementResult.SignedOut -> uiState = uiState.copy(
                    busyReactionPostId = null,
                    signedOut = true
                )
            }
        }
    }

    /** §14 vote, or change a vote. The fresh aggregate replaces the poll. */
    fun vote(post: GoodPostPost, optionIds: List<String>) {
        val source = engagement ?: return
        val poll = post.engagement.poll ?: return
        if (uiState.votingPollId != null || optionIds.isEmpty()) return
        if (poll.isClosed) {
            uiState = uiState.copy(messageCode = "poll_closed")
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(votingPollId = poll.id, messageCode = null)

            when (val result = source.vote(poll.id, optionIds)) {
                is EngagementResult.Ok -> uiState = uiState.copy(
                    feed = GoodPostPostCodec.replaceEngagement(
                        uiState.feed,
                        post.id,
                        GoodPostEngagementCodec.withPoll(post.engagement, result.value)
                    ),
                    channelPosts = GoodPostPostCodec.replaceEngagement(
                        uiState.channelPosts,
                        post.id,
                        GoodPostEngagementCodec.withPoll(post.engagement, result.value)
                    ),
                    votingPollId = null
                )

                is EngagementResult.Failed -> uiState = uiState.copy(
                    votingPollId = null,
                    messageCode = result.code
                )

                EngagementResult.SignedOut -> uiState = uiState.copy(
                    votingPollId = null,
                    signedOut = true
                )
            }
        }
    }

    /**
     * §15 tell the server a post was seen.
     *
     * Fire-and-forget by design: a view that could not be counted must not put
     * an error in front of someone who was only reading. The server's own
     * dedupe window decides whether the look moves the number, and the answer
     * is folded back in only when it does.
     */
    fun pingView(postId: String) {
        val source = engagement ?: return
        if (uiState.viewedPostIds.contains(postId)) return

        // Recorded BEFORE the request, so a recomposition cannot queue a second
        // ping for the same post while the first is still in flight.
        uiState = uiState.copy(viewedPostIds = uiState.viewedPostIds + postId)

        viewModelScope.launch {
            when (val result = source.recordView(postId)) {
                is EngagementResult.Ok -> {
                    if (!result.value.counted) return@launch
                    val current = uiState.feed.firstOrNull { it.id == postId }
                        ?: uiState.channelPosts.firstOrNull { it.id == postId }
                        ?: return@launch
                    uiState = uiState.copy(
                        feed = GoodPostPostCodec.replaceEngagement(
                            uiState.feed,
                            postId,
                            GoodPostEngagementCodec.withView(current.engagement, result.value)
                        ),
                        channelPosts = GoodPostPostCodec.replaceEngagement(
                            uiState.channelPosts,
                            postId,
                            GoodPostEngagementCodec.withView(current.engagement, result.value)
                        )
                    )
                }

                // Deliberately silent: a failed view ping is not the user's
                // problem, and the post stays readable either way.
                is EngagementResult.Failed -> Unit
                EngagementResult.SignedOut -> Unit
            }
        }
    }

    // ── Reports (§18) ───────────────────────────────────────────────────

    fun startReport(type: String, id: String, label: String) {
        if (!GoodPostReportTarget.isKnown(type)) {
            uiState = uiState.copy(messageCode = "invalid_target_type")
            return
        }
        uiState = uiState.copy(
            reportTarget = GoodPostReportTargetRef(type, id, label),
            reportSent = false,
            messageCode = null
        )
    }

    fun cancelReport() {
        uiState = uiState.copy(reportTarget = null, reporting = false)
    }

    fun dismissReportSent() {
        uiState = uiState.copy(reportSent = false)
    }

    /**
     * File the report (§18).
     *
     * The reason is the server's own wire value, chosen from a fixed list, so a
     * client cannot invent one the API refuses. Success is only reported once
     * the server has accepted it (§36).
     */
    fun submitReport(reason: String, details: String?) {
        val source = inbox ?: return
        val target = uiState.reportTarget ?: return
        if (uiState.reporting) return

        viewModelScope.launch {
            uiState = uiState.copy(reporting = true, messageCode = null)

            when (val result = source.report(target.type, target.id, reason, details)) {
                is InboxResult.Ok -> uiState = uiState.copy(
                    reporting = false,
                    reportTarget = null,
                    reportSent = true
                )

                is InboxResult.Failed -> uiState = uiState.copy(
                    reporting = false,
                    // The dialog stays open with its reason selected: losing that
                    // over a network blip would mean doing it again from scratch.
                    messageCode = result.code
                )

                is InboxResult.Stale -> uiState = uiState.copy(reporting = false)
                InboxResult.SignedOut -> uiState = uiState.copy(
                    reporting = false,
                    signedOut = true
                )
            }
        }
    }

    /** §12 block another account, which also ends any thread with them. */
    fun blockUser(userId: String) {
        val source = inbox ?: return
        viewModelScope.launch {
            when (val result = source.setUserBlocked(userId, true)) {
                is InboxResult.Ok -> {
                    uiState = uiState.copy(
                        conversation = null,
                        messages = emptyList()
                    )
                    loadInbox()
                }

                is InboxResult.Failed -> uiState = uiState.copy(messageCode = result.code)
                is InboxResult.Stale -> Unit
                InboxResult.SignedOut -> uiState = uiState.copy(signedOut = true)
            }
        }
    }

    // ── Push registration (§17) ─────────────────────────────────────────

    /**
     * Give the server this device's push token, if one exists yet.
     *
     * Best-effort: a device that cannot register simply does not ring, and the
     * inbox still fills. Failing the sign-in over it would make an optional
     * delivery address look essential.
     */
    fun registerPushToken() {
        val source = inbox ?: return
        val context = appContext ?: return
        val token = GoodPostPushTokens(context).current() ?: return

        viewModelScope.launch { source.registerDevice(token) }
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
            GoodPostSection.Inbox -> loadInbox()
        }
    }

    /**
     * Which channels this account already owns.
     *
     * Decides whether the home screen offers to create one. The server is the
     * authority (§32) and refuses a second channel anyway; this only keeps the
     * UI from offering an action it knows will be rejected.
     */
    private fun loadOwnChannels() {
        val source = repo ?: return
        viewModelScope.launch {
            when (val result = source.managed()) {
                is ChannelsResult.Ok -> uiState = uiState.copy(ownChannels = result.value)

                // A refusal here must not block the screen: the create action
                // stays available and the server decides. Silence is right for
                // everything except an outright signed-out answer.
                is ChannelsResult.Failed -> Unit

                is ChannelsResult.Stale -> Unit

                ChannelsResult.SignedOut -> uiState = uiState.copy(signedOut = true)
            }
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

    /**
     * Follow or unfollow from a LIST, without opening the channel first.
     *
     * A channel search shows a Follow button, which is the whole point of the
     * screen: someone browsing should be able to subscribe without a detour
     * through the channel page. Nothing is claimed until the server answers —
     * a rejected follow leaves the row exactly as it was, and [messageCode]
     * carries the reason.
     */
    fun setFollowingFromList(channel: GoodPostChannel, following: Boolean) {
        if (uiState.busyChannelId != null) return

        mutate(channel) { source ->
            when (val result = source.setFollowing(channel.id, following)) {
                is ChannelsResult.Ok -> {
                    val updated = channel.copy(
                        isFollowing = result.value.following,
                        followerCount = result.value.followerCount,
                        notificationsEnabled = if (result.value.following) {
                            channel.notificationsEnabled
                        } else {
                            false
                        }
                    )

                    uiState = uiState.copy(
                        // The search result keeps its place either way — it is a
                        // result of a query, not a membership list, so removing
                        // the row on unfollow would make the list jump under
                        // the finger that just tapped it.
                        discover = GoodPostChannelCodec.mergePage(uiState.discover, listOf(updated)),
                        following = if (result.value.following) {
                            GoodPostChannelCodec.mergePage(uiState.following, listOf(updated))
                        } else {
                            GoodPostChannelCodec.remove(uiState.following, updated.id)
                        },
                        channel = uiState.channel?.let { open ->
                            if (open.id == updated.id) updated else open
                        },
                        messageCode = null
                    )
                }

                is ChannelsResult.Failed -> uiState = uiState.copy(messageCode = result.code)
                is ChannelsResult.Stale -> Unit
                ChannelsResult.SignedOut -> uiState = uiState.copy(signedOut = true)
            }
        }
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
                    // owner's own list, so reflect it without a full refetch —
                    // and the account now owns one, which is what hides the
                    // create action from here on.
                    loadFollowing()
                    loadOwnChannels()
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

    fun saveEdit(
        name: String,
        description: String?,
        clearDescription: Boolean,
        categorySlug: String?,
        allowFollowerMessages: Boolean? = null
    ) {
        val channel = uiState.channel ?: return
        val source = repo ?: return

        viewModelScope.launch {
            uiState = uiState.copy(loading = true, messageCode = null)

            val result = source.updateChannel(
                channelId = channel.id,
                name = name.takeIf { it.isNotBlank() },
                description = description,
                clearDescription = clearDescription,
                categorySlug = categorySlug,
                allowFollowerMessages = allowFollowerMessages
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
        inboxRepository?.clearCache()
    }

    /**
     * Stop pushing to this device, then forget the session.
     *
     * The order matters: unregistering needs a live token, so it must happen
     * before the token store is cleared by sign-out. A failure here is ignored —
     * a device that keeps its address is far better than a sign-out that cannot
     * complete, and the next account to sign in on this handset moves the token
     * anyway (§17).
     */
    fun unregisterPushToken() {
        val source = inbox ?: return
        val context = appContext ?: return
        val token = GoodPostPushTokens(context).current() ?: return
        viewModelScope.launch { source.unregisterDevice(token) }
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
            composerPollMode = false,
            composerPollQuestion = "",
            composerPollOptions = listOf("", ""),
            composerPollMultiple = false,
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
            composerPollMode = false,
            composerPollQuestion = "",
            composerPollOptions = listOf("", ""),
            composerPollMultiple = false,
            messageCode = null
        )
    }

    /** Switch the composer between a file post and a poll (§14). */
    fun setComposerPollMode(enabled: Boolean) {
        uiState = uiState.copy(
            composerPollMode = enabled,
            // Turning the poll on drops any attachment, because the server
            // refuses a poll that also carries a file. Leaving it attached
            // would build a request that cannot succeed.
            composerAttachments = if (enabled) emptyList() else uiState.composerAttachments,
            messageCode = null
        )
    }

    fun onPollQuestionChange(value: String) {
        uiState = uiState.copy(composerPollQuestion = value, messageCode = null)
    }

    fun onPollOptionChange(index: Int, value: String) {
        val options = uiState.composerPollOptions.toMutableList()
        if (index !in options.indices) return
        options[index] = value
        uiState = uiState.copy(composerPollOptions = options, messageCode = null)
    }

    /** Add an option, up to the server's own ceiling. */
    fun addPollOption() {
        // The ceiling is the server's `POLL_MAX_OPTIONS`. Stopping here keeps
        // the user from filling in an option the API would reject, and the
        // number has to live somewhere — this is the only place a client-side
        // copy of a product limit is worth having.
        if (uiState.composerPollOptions.size >= MAX_POLL_OPTIONS) return
        uiState = uiState.copy(
            composerPollOptions = uiState.composerPollOptions + "",
            messageCode = null
        )
    }

    fun removePollOption(index: Int) {
        // Two is the floor: a single-option poll is not a question, and the
        // server refuses it (`too_few_options`).
        if (uiState.composerPollOptions.size <= 2 || index !in uiState.composerPollOptions.indices) return
        val options = uiState.composerPollOptions.toMutableList()
        options.removeAt(index)
        uiState = uiState.copy(composerPollOptions = options, messageCode = null)
    }

    fun setPollMultiple(enabled: Boolean) {
        uiState = uiState.copy(composerPollMultiple = enabled, messageCode = null)
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
        val context = appContext ?: return
        if (uiState.uploadingAttachment) return

        val request = uploadRequestFor(context, uri)
        if (request == null) {
            uiState = uiState.copy(messageCode = "file_unreadable")
            return
        }

        upload(request)
    }

    // ── The editor ──────────────────────────────────────────────────────

    /**
     * Open the editor on a picture the user just picked.
     *
     * The decode happens off the main thread and the result is held as a
     * BITMAP rather than a URI, because the editor renders that bitmap on every
     * slider move: re-decoding the file each time would make the sliders stutter
     * on exactly the photos people bother to edit.
     */
    fun beginEditImage(uri: android.net.Uri) {
        val context = appContext ?: return
        if (uiState.editingImage != null) return

        viewModelScope.launch {
            uiState = uiState.copy(editingImageLoading = true, messageCode = null)

            val editable = withContext(Dispatchers.IO) { EditableBitmap.decode(context, uri) }

            uiState = if (editable == null) {
                uiState.copy(editingImageLoading = false, messageCode = "file_unreadable")
            } else {
                uiState.copy(editingImage = editable, editingImageLoading = false)
            }
        }
    }

    /** Close the editor without uploading, and release the decoded bitmap. */
    fun cancelEditImage() {
        uiState.editingImage?.bitmap?.recycle()
        uiState = uiState.copy(editingImage = null, editingImageLoading = false)
    }

    /**
     * Render the edit and upload the result.
     *
     * The rendered file is what goes to S3 — the edit is not a recipe the server
     * is asked to replay (§ media). The original never leaves the device, and the
     * cache copy is the app's to reclaim.
     */
    fun finishEditImage(state: EditState) {
        val context = appContext ?: return
        val source = uiState.editingImage ?: return
        if (uiState.editingImageSaving) return

        viewModelScope.launch {
            uiState = uiState.copy(editingImageSaving = true, messageCode = null)

            val file = withContext(Dispatchers.IO) { source.render(context, state) }

            if (file == null) {
                uiState = uiState.copy(
                    editingImageSaving = false,
                    messageCode = "file_unreadable"
                )
                return@launch
            }

            // The editor closes before the upload runs, so the user is back in
            // the composer with the attachment's own progress line rather than
            // watching two spinners at once.
            source.bitmap.recycle()
            uiState = uiState.copy(editingImage = null, editingImageSaving = false)
            upload(uploadRequestForFile(file))
        }
    }

    private fun upload(request: MediaUploadRequest) {
        val source = posts ?: return
        if (uiState.uploadingAttachment) return

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
                mediaIds = uiState.composerAttachments.map { it.id },
                poll = if (!uiState.composerPollMode) null else PollDraft(
                    question = uiState.composerPollQuestion.trim(),
                    options = uiState.composerPollOptions.map { it.trim() }.filter { it.isNotEmpty() },
                    allowMultiple = uiState.composerPollMultiple
                )
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
                        composerPollMode = false,
                        composerPollQuestion = "",
                        composerPollOptions = listOf("", ""),
                        composerPollMultiple = false,
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
    // ── Appearance ──────────────────────────────────────────────────────

    fun openAppearance() {
        uiState = uiState.copy(appearanceOpen = true, theme = GoodPostThemeStore.current)
    }

    fun closeAppearance() {
        uiState = uiState.copy(appearanceOpen = false)
    }

    /**
     * Apply a theme change.
     *
     * Written straight to the store and mirrored into state in the same call, so
     * the swatch, the screen behind the sheet and the saved preference cannot
     * disagree — a preference that only takes effect next launch looks like a
     * button that did nothing.
     */
    private fun applyTheme(theme: GoodPostTheme) {
        val context = appContext ?: return
        GoodPostThemeStore.save(context, theme)
        uiState = uiState.copy(theme = theme)
    }

    fun selectAccent(accent: AccentChoice) = applyTheme(uiState.theme.copy(accent = accent))

    fun selectBackdrop(backdrop: BackdropChoice) =
        applyTheme(uiState.theme.copy(backdrop = backdrop))

    fun selectGlass(glass: GlassChoice) = applyTheme(uiState.theme.copy(glass = glass))

    /**
     * Mark a piece of media as looked at, so it stops being blurred.
     *
     * Idempotent, and never reaches the server: what the reader looked at is not
     * something the server needs, and §15's view tracking is about posts, not
     * about which picture someone opened.
     */
    fun revealMedia(mediaId: String) {
        if (mediaId.isEmpty() || uiState.revealedMediaIds.contains(mediaId)) return
        uiState = uiState.copy(revealedMediaIds = uiState.revealedMediaIds + mediaId)
    }

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
