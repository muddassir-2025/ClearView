package com.muddassir.clearview.goodpost.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostHomeUiState
import com.muddassir.clearview.goodpost.GoodPostHomeViewModel
import com.muddassir.clearview.goodpost.GoodPostInboxTab
import com.muddassir.clearview.goodpost.GoodPostSection
import com.muddassir.clearview.goodpost.data.ChannelSort
import com.muddassir.clearview.goodpost.data.GoodPostCategory
import com.muddassir.clearview.goodpost.data.GoodPostChannel
import com.muddassir.clearview.goodpost.data.GoodPostMedia
import com.muddassir.clearview.goodpost.data.parseIsoMillis
import com.muddassir.clearview.goodpost.data.GoodPostPost
import com.muddassir.clearview.goodpost.data.GoodPostReportTarget

/**
 * Good Post home: the channel list, the aggregated feed, Discover and the inbox
 * (§4, §5).
 *
 * Presentation only. Every action delegates to [GoodPostHomeViewModel], where
 * the session, the network and the cache live (§35) — nothing here decides what
 * is true, and nothing here calls the API.
 *
 * A channel, a thread and the composer are full-screen sheets, which is how a
 * messaging app opens a conversation: it covers the tab bar the same way, and
 * Back returns to the list that opened it.
 */
@Composable
fun GoodPostHome(
    state: GoodPostHomeUiState,
    accountName: String,
    onSignOut: () -> Unit,
    viewModel: GoodPostHomeViewModel
) {
    /**
     * The two content kinds, in the order the segmented control shows them.
     *
     * Discover and Inbox are not here because a channel screen in a messaging
     * app does not have them as peers of the content: search is a button in the
     * bar, and mail is a second button beside it. Four equal tabs made all four
     * look like the same kind of thing, which is what made the screen read as a
     * settings page rather than a feed.
     */
    val sections = listOf(GoodPostSection.Channels, GoodPostSection.Posts)
    val contentIndex = sections.indexOf(state.section)
    val browsingContent = contentIndex >= 0

    // The dreamy canvas: everything below floats on a gradient rather than a
    // flat colour, which is what the glass bars and sheets are translucent
    // AGAINST. On a flat backdrop the same panels read as plain grey blocks.
    WaDreamyBackdrop {
    Column(modifier = Modifier.fillMaxSize()) {
        // The bar's title follows the screen, like a mail or chat app: the
        // channel list is titled Channels, search is titled Find channels, and
        // the inbox is titled with its unread count.
        val title = when (state.section) {
            GoodPostSection.Discover -> stringResource(R.string.goodpost_find_channels)
            GoodPostSection.Inbox -> stringResource(R.string.goodpost_section_inbox)
            else -> stringResource(R.string.goodpost_section_channels)
        }

        WaTopBar(
            title = title,
            navigation = {
                if (!browsingContent) {
                    // A back arrow out of the two pushed screens, so the gesture
                    // every Android user already knows works here too.
                    WaIconAction(
                        icon = Icons.Filled.ArrowBack,
                        description = stringResource(R.string.goodpost_cancel),
                        onClick = { viewModel.selectSection(GoodPostSection.Channels) }
                    )
                } else {
                    Box(modifier = Modifier.padding(start = 8.dp)) {
                        // The account's own initial, in the slot a messaging app
                        // puts the profile picture — this is who is signed in,
                        // and it is the only place that says so.
                        WaAvatar(name = accountName.ifBlank { "?" }, size = 36.dp)
                    }
                }
            },
            actions = {
                WaIconAction(
                    icon = Icons.Filled.Search,
                    description = stringResource(R.string.goodpost_find_channels),
                    enabled = state.section != GoodPostSection.Discover,
                    onClick = { viewModel.selectSection(GoodPostSection.Discover) }
                )

                // Mail, with the count on the button rather than on a tab. The
                // number is the one thing worth knowing before opening it.
                Box {
                    WaIconAction(
                        icon = Icons.Filled.Notifications,
                        description = stringResource(R.string.goodpost_section_inbox),
                        onClick = { viewModel.selectSection(GoodPostSection.Inbox) }
                    )
                    if (state.unreadCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 4.dp)
                        ) {
                            WaUnreadBadge(state.unreadCount)
                        }
                    }
                }

                WaOverflowMenu(
                    items = listOfNotNull(
                        // "New channel" lives in the menu rather than on a
                        // floating button, which is where a messaging app keeps
                        // it — and it disappears once this account owns one,
                        // because the rule is one channel per account.
                        if (state.canCreateChannel) {
                            WaMenuItem(
                                label = stringResource(R.string.goodpost_create_channel),
                                onClick = viewModel::startCreate
                            )
                        } else null,
                        WaMenuItem(
                            label = stringResource(R.string.goodpost_my_channel),
                            onClick = { state.ownChannels.firstOrNull()?.let { viewModel.open(it.id) } }
                        ),
                        WaMenuItem(
                            label = stringResource(R.string.goodpost_appearance),
                            onClick = viewModel::openAppearance
                        ),
                        WaMenuItem(
                            label = stringResource(R.string.goodpost_refresh),
                            onClick = viewModel::refresh
                        ),
                        WaMenuItem(
                            label = stringResource(R.string.goodpost_sign_out),
                            onClick = onSignOut,
                            destructive = true
                        )
                    )
                )
            }
        )

        // The segmented control from a messaging app's Updates screen: two
        // options, the selected one tinted, sitting on the bar.
        if (browsingContent) {
            WaSegmentedControl(
                segments = sections.map { stringResource(it.labelRes) },
                selectedIndex = contentIndex,
                onSelect = { viewModel.selectSection(sections[it]) }
            )
        }

        // §36: saved data is labelled as saved. Presenting a cached list as live
        // would be claiming a success the server never confirmed. The feed and
        // the channel lists are cached separately, so each carries its own flag.
        if (state.stale || state.feedStale || state.channelPostsStale) {
            WaStaleBanner(onRetry = viewModel::refresh)
        }

        val reportPostLabel = stringResource(R.string.goodpost_report_post)

        Box(modifier = Modifier.fillMaxSize()) {
            when (state.section) {
                GoodPostSection.Posts -> PostsSection(
                    state = state,
                    onOpenChannel = viewModel::open,
                    onEditPost = viewModel::startEditPost,
                    onDeletePost = viewModel::deletePost,
                    onSaveMedia = viewModel::saveMedia,
                    onRevealMedia = viewModel::revealMedia,
                    onLoadMore = viewModel::loadMoreFeed,
                    onRetry = viewModel::refresh,
                    onReact = viewModel::react,
                    onVote = viewModel::vote,
                    onSeen = viewModel::pingView,
                    onReport = { post ->
                        viewModel.startReport(
                            type = GoodPostReportTarget.POST,
                            id = post.id,
                            // The post's own opening words where there are any,
                            // so the dialog names the thing being reported. A
                            // callback is not a composable context, so the
                            // fallback is read once here.
                            label = post.body?.take(60)?.takeIf { it.isNotBlank() }
                                ?: reportPostLabel
                        )
                    }
                )

                GoodPostSection.Channels -> ChannelsSection(
                    state = state,
                    onOpen = viewModel::open,
                    onFind = { viewModel.selectSection(GoodPostSection.Discover) },
                    onRetry = viewModel::refresh
                )

                GoodPostSection.Discover -> DiscoverSection(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onSearch = viewModel::search,
                    onCategory = viewModel::selectCategory,
                    onSort = viewModel::selectSort,
                    onOpen = viewModel::open,
                    onFollow = viewModel::setFollowingFromList,
                    onLoadMore = viewModel::loadMore
                )

                // §16, §17, §26. Loaded when opened rather than at startup: it
                // is mail, and a returning user may have none.
                GoodPostSection.Inbox -> InboxSection(
                    tab = state.inboxTab,
                    notifications = state.notifications,
                    notices = state.notices,
                    conversations = state.conversations,
                    loading = state.inboxLoading,
                    onSelectTab = viewModel::selectInboxTab,
                    onMarkAllRead = {
                        when (state.inboxTab) {
                            GoodPostInboxTab.Notifications -> viewModel.markNotificationsRead()
                            // Notices are marked one at a time, on read: the
                            // server's route takes ids, and clearing the whole
                            // list would mark unread ones read silently.
                            GoodPostInboxTab.Notices -> state.notices
                                .filter { it.isUnread }
                                .forEach { viewModel.markNoticesRead(it.id) }

                            GoodPostInboxTab.Messages -> Unit
                        }
                    },
                    onOpenNotification = { item ->
                        viewModel.markNotificationsRead(item.id)
                        item.channelId?.let(viewModel::open)
                    },
                    onOpenNotice = { item -> viewModel.markNoticesRead(item.id) },
                    onOpenThread = { conversation -> viewModel.openThread(conversation.id) },
                    onRetry = viewModel::loadInbox
                )
            }

            // No floating button on this screen. Creating a channel is a
            // once-per-account action, not part of the loop of using one, so it
            // lives in the overflow menu with the other rare actions — which is
            // where a messaging app keeps it, and why a list of channels is not
            // half-covered by a button.

            if (state.loading || state.analyticsLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                    color = Wa.Accent
                )
            }
        }
    }
    }

    if (state.composerOpen) {
        ComposerDialog(
            state = state,
            onBodyChange = viewModel::onComposerBodyChange,
            onLinkChange = viewModel::onComposerLinkChange,
            onLinkTitleChange = viewModel::onComposerLinkTitleChange,
            // Picking a picture opens the editor; what gets uploaded is the
            // edited file, never the original.
            onPickFile = viewModel::beginEditImage,
            onRemoveAttachment = viewModel::removeAttachment,
            onPublish = viewModel::publish,
            onDismiss = viewModel::cancelCompose,
            onPollMode = viewModel::setComposerPollMode,
            onPollQuestion = viewModel::onPollQuestionChange,
            onPollOption = viewModel::onPollOptionChange,
            onAddPollOption = viewModel::addPollOption,
            onRemovePollOption = viewModel::removePollOption,
            onPollMultiple = viewModel::setPollMultiple
        )
    }

    state.editingPostId?.let { _ ->
        EditPostDialog(
            body = state.editingPostBody,
            busy = state.busyPostId != null,
            errorCode = state.messageCode,
            onBodyChange = viewModel::onEditPostBodyChange,
            onSave = viewModel::saveEditPost,
            onDismiss = viewModel::cancelEditPost
        )
    }

    state.channel?.let { channel ->
        ChannelDetail(
            channel = channel,
            posts = state.channelPosts,
            postsStale = state.channelPostsStale,
            savedMediaIds = state.savedMediaIds,
            savingMediaId = state.savingMediaId,
            revealedMediaIds = state.revealedMediaIds,
            busyPostId = state.busyPostId,
            busyReactionPostId = state.busyReactionPostId,
            votingPollId = state.votingPollId,
            onCompose = { viewModel.startCompose(channel.id) },
            onLoadPosts = { viewModel.loadChannelPosts() },
            onEditPost = viewModel::startEditPost,
            onDeletePost = viewModel::deletePost,
            onSaveMedia = viewModel::saveMedia,
            onRevealMedia = viewModel::revealMedia,
            onReact = viewModel::react,
            onVote = viewModel::vote,
            onSeen = viewModel::pingView,
            onMessage = { viewModel.openChannelConversation(channel.id) },
            onOpenInbox = { viewModel.openChannelInbox(channel.id) },
            onAnalytics = { viewModel.openAnalytics(channel.id) },
            onReport = {
                viewModel.startReport(
                    type = GoodPostReportTarget.CHANNEL,
                    id = channel.id,
                    label = channel.name
                )
            },
            busy = state.busyChannelId == channel.id,
            onDismiss = viewModel::closeDetail,
            onToggleFollow = viewModel::toggleFollow,
            onToggleMute = viewModel::toggleMute,
            onToggleBlock = viewModel::toggleBlock,
            onEdit = viewModel::startEdit
        )
    }

    // The four overlays §16–§18 need. Each is rendered from state, so a
    // dismissed dialog cannot leave a stale one behind.
    state.conversation?.let { conversation ->
        ThreadDialog(
            conversation = conversation,
            messages = state.messages,
            draft = state.messageDraft,
            sending = state.sendingMessage,
            unsent = state.unsentMessage,
            errorCode = state.messageCode,
            // The channel side can block a thread; a follower cannot (§16).
            canBlock = state.channelInboxId != null,
            // Which side of the thread the reader is on. It decides which
            // messages hug the right edge, and it is derived from the same
            // fact as [canBlock] — opening from a channel's inbox means the
            // reader is the channel.
            viewerIsChannelSide = state.channelInboxId != null,
            onDraftChange = viewModel::onMessageDraftChange,
            onSend = viewModel::sendMessage,
            onToggleBlock = viewModel::toggleConversationBlock,
            onDismiss = viewModel::closeThread
        )
    }

    // The editor, on top of the composer: it was opened FROM the composer and
    // closing it lands back there with the attachment already uploading.
    state.editingImage?.let { source ->
        ImageEditor(
            source = source,
            saving = state.editingImageSaving,
            errorCode = state.messageCode,
            onCancel = viewModel::cancelEditImage,
            onDone = viewModel::finishEditImage
        )
    }

    // The customization sheet (§ UI). Drawn last so it sits over everything:
    // every change it makes is visible on the screens behind it as it happens.
    if (state.appearanceOpen) {
        AppearanceDialog(
            theme = state.theme,
            onAccent = viewModel::selectAccent,
            onBackdrop = viewModel::selectBackdrop,
            onGlass = viewModel::selectGlass,
            onDismiss = viewModel::closeAppearance
        )
    }

    state.channelInbox?.let { conversations ->
        ChannelInboxDialog(
            channelName = state.channel?.name.orEmpty(),
            conversations = conversations,
            onOpenThread = { conversation -> viewModel.openThread(conversation.id) },
            onDismiss = viewModel::closeChannelInbox
        )
    }

    if (state.analyticsChannelId != null) {
        AnalyticsDialog(
            channelName = state.channel?.name.orEmpty(),
            analytics = state.analytics,
            loading = state.analyticsLoading,
            onDismiss = viewModel::closeAnalytics
        )
    }

    state.reportTarget?.let { target ->
        ReportDialog(
            target = target,
            busy = state.reporting,
            errorCode = state.messageCode,
            onSend = viewModel::submitReport,
            onDismiss = viewModel::cancelReport
        )
    }

    if (state.reportSent) {
        ReportSentDialog(onDismiss = viewModel::dismissReportSent)
    }

    if (state.creating) {
        ChannelFormDialog(
            title = stringResource(R.string.goodpost_create_title),
            confirmLabel = stringResource(R.string.goodpost_create),
            initialName = "",
            initialDescription = "",
            // A new channel starts closed to messages (§16's default), so the
            // create dialog does not offer the switch at all — the owner turns
            // it on when they are ready to answer.
            initialAllowMessages = null,
            categories = state.categories,
            errorCode = state.messageCode,
            onDismiss = viewModel::cancelCreate,
            onConfirm = { name, description, category, _ ->
                viewModel.createChannel(name, description, category)
            }
        )
    }

    if (state.editing && state.channel != null) {
        ChannelFormDialog(
            title = stringResource(R.string.goodpost_edit_title),
            confirmLabel = stringResource(R.string.goodpost_save),
            initialName = state.channel.name,
            initialDescription = state.channel.description.orEmpty(),
            initialAllowMessages = state.channel.allowFollowerMessages,
            categories = state.categories,
            errorCode = state.messageCode,
            onDismiss = viewModel::cancelEdit,
            onConfirm = { name, description, category, allowMessages ->
                viewModel.saveEdit(
                    name = name,
                    description = description,
                    // Blank means "remove it", which the backend distinguishes
                    // from "leave it alone" — so the intent is sent explicitly.
                    clearDescription = description.isNullOrBlank(),
                    categorySlug = category,
                    // §16: sent explicitly so the switch is a decision rather
                    // than a side effect of saving the name.
                    allowFollowerMessages = allowMessages
                )
            }
        )
    }
}

// ── Channels (§4) ───────────────────────────────────────────────────────

@Composable
private fun ChannelsSection(
    state: GoodPostHomeUiState,
    onOpen: (String) -> Unit,
    onFind: () -> Unit,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        // First row of the screen, and the only way to reach a channel this
        // account does not follow yet. It stays even when the list below is
        // empty — that is precisely when someone needs it.
        item(key = "find") {
            WaFindChannelsRow(onClick = onFind)
        }

        if (state.following.isEmpty()) {
            if (!state.loading) {
                item(key = "empty") {
                    // No retry button here: an empty follow list is not a
                    // failure, it is a new account. The way forward is to find
                    // a channel, which is the row directly above.
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(64.dp))
                        Text(
                            text = stringResource(R.string.goodpost_empty_following_title),
                            color = Wa.Text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.goodpost_empty_following_note),
                            color = Wa.TextDim,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            return@LazyColumn
        }

        item(key = "label") {
            // A channel whose newest post this account has not read is an
            // update, and updates come first — the same ordering rule a chat
            // list uses, for the same reason.
            WaSectionLabel(stringResource(R.string.goodpost_channels_you_follow))
        }

        items(
            state.following.sortedByDescending { it.hasUnread },
            key = { it.id }
        ) { channel ->
            ChannelRow(channel = channel, onClick = { onOpen(channel.id) })
        }
    }
}

/**
 * One channel in a list, drawn as a chat row.
 *
 * A channel row in a messaging app carries three things and nothing else: who
 * it is, when it last said something, and the start of what it said. So the
 * second line leads with the time and the newest post's opening words — and
 * falls back to the description only for a channel that has never posted, where
 * there is no preview to show and the description is the one useful thing
 * available.
 *
 * The follower count is deliberately gone from here. It is a statistic, not a
 * message, and putting it where a messaging app puts a preview is what made the
 * list read like a settings screen.
 */
@Composable
private fun ChannelRow(channel: GoodPostChannel, onClick: () -> Unit) {
    val posted = channel.lastPostAt != null
    val preview = when {
        posted && !channel.lastPostPreview.isNullOrBlank() -> channel.lastPostPreview
        // A post with no text is still a post — a photo, a file, a poll. Saying
        // so is better than leaving the line empty or repeating the time.
        posted -> stringResource(R.string.goodpost_channel_posted_media)
        else -> channel.description?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.goodpost_channel_no_description)
    }

    WaListRow(
        title = channel.name,
        preview = preview,
        // The stamp is prefixed onto the preview by the list row itself, so the
        // two never wrap apart into "3:45 PM" on one line and the text on the
        // next.
        timestamp = if (posted) waListStamp(parseIsoMillis(channel.lastPostAt)) else null,
        onClick = onClick,
        avatar = { WaAvatar(name = channel.name, size = 49.dp) },
        trailing = {
            if (channel.hasUnread) WaUnreadBadge(1)
            Spacer(Modifier.height(6.dp))
            if (channel.isMuted) {
                Icon(
                    Icons.Filled.NotificationsOff,
                    contentDescription = stringResource(R.string.goodpost_muted),
                    tint = Wa.TextDim,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    )
}

/**
 * The Follow / Following button a search result carries.
 *
 * Outlined while following and filled while not, which is the opposite of what
 * a "primary action" convention suggests and the right way round here: the
 * filled state is the invitation, and the outline is the receipt.
 */
@Composable
private fun WaFollowButton(following: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val accent = if (enabled) Wa.Accent else Wa.TextDim
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (following) Color.Transparent else accent)
            .border(
                width = 1.dp,
                color = accent,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = stringResource(
                if (following) R.string.goodpost_following else R.string.goodpost_follow
            ),
            color = if (following) accent else Wa.Canvas,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Discover (§5) ───────────────────────────────────────────────────────

@Composable
private fun DiscoverSection(
    state: GoodPostHomeUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCategory: (String?) -> Unit,
    onSort: (ChannelSort) -> Unit,
    onOpen: (String) -> Unit,
    onFollow: (GoodPostChannel, Boolean) -> Unit,
    onLoadMore: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        WaSearchField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.goodpost_search_hint),
            onSearch = onSearch,
            onClear = {
                onQueryChange("")
                onSearch()
            }
        )

        SortRow(selected = state.sort, onSelect = onSort)

        CategoryRow(
            categories = state.categories,
            selected = state.category,
            onSelect = onCategory
        )

        if (state.discover.isEmpty() && !state.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                WaEmptyState(
                    title = stringResource(R.string.goodpost_empty_discover_title),
                    note = stringResource(R.string.goodpost_empty_discover_note),
                    actionLabel = stringResource(R.string.goodpost_search),
                    onAction = onSearch
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            items(state.discover, key = { it.id }) { channel ->
                WaListRow(
                    title = channel.name,
                    // Discovery is where the follower count belongs: it is the
                    // one signal that says whether a channel is worth joining,
                    // and it is asked before the decision, not after.
                    preview = channel.description?.takeIf { it.isNotBlank() }
                        ?: stringResource(
                            R.string.goodpost_followers,
                            channel.followerCount.toString()
                        ),
                    onClick = { onOpen(channel.id) },
                    avatar = { WaAvatar(name = channel.name, size = 49.dp) },
                    trailing = {
                        if (channel.isOwner) {
                            // Following one's own channel is not a thing (§7),
                            // so the row says what it is instead.
                            Text(
                                text = stringResource(R.string.goodpost_my_channel),
                                color = Wa.TextDim,
                                fontSize = 13.sp
                            )
                        } else {
                            val busy = state.busyChannelId == channel.id
                            WaFollowButton(
                                following = channel.isFollowing,
                                enabled = !busy,
                                onClick = { onFollow(channel, !channel.isFollowing) }
                            )
                        }
                    }
                )
            }

            if (state.nextCursor != null) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Wa.Accent
                            )
                        } else {
                            WaTextAction(
                                text = stringResource(R.string.goodpost_more),
                                onClick = onLoadMore
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortRow(selected: ChannelSort, onSelect: (ChannelSort) -> Unit) {
    val options = listOf(
        ChannelSort.Popular to stringResource(R.string.goodpost_sort_popular),
        ChannelSort.Active to stringResource(R.string.goodpost_sort_active),
        ChannelSort.New to stringResource(R.string.goodpost_sort_new)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (sort, label) ->
            WaFilterPill(
                label = label,
                selected = selected == sort,
                onClick = { onSelect(sort) }
            )
        }
    }
}

@Composable
private fun CategoryRow(
    categories: List<GoodPostCategory>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    if (categories.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WaFilterPill(
            label = stringResource(R.string.goodpost_all_categories),
            selected = selected == null,
            onClick = { onSelect(null) }
        )
        categories.forEach { category ->
            WaFilterPill(
                label = category.label,
                selected = selected == category.slug,
                onClick = { onSelect(category.slug) }
            )
        }
    }
}

// ── Channel detail (§7, §8, §12, §15, §16) ──────────────────────────────

/**
 * One channel: who it is, what it has said, and what this viewer may do with it.
 *
 * The header is a channel profile — avatar, name, follower count, description,
 * follow button — and the posts follow it as bubbles on a chat canvas, which is
 * what a channel looks like when it is opened.
 *
 * Every control is offered only where the server would accept it (§32): follow
 * is hidden for an owner (the API refuses it), analytics only for the roles that
 * may read it, and the message button only when the channel has follower
 * messages on and this viewer follows.
 */
@Composable
private fun ChannelDetail(
    channel: GoodPostChannel,
    posts: List<GoodPostPost>,
    postsStale: Boolean,
    savedMediaIds: Set<String>,
    savingMediaId: String?,
    revealedMediaIds: Set<String>,
    busyPostId: String?,
    busyReactionPostId: String?,
    votingPollId: String?,
    onCompose: () -> Unit,
    onLoadPosts: () -> Unit,
    onEditPost: (GoodPostPost) -> Unit,
    onDeletePost: (GoodPostPost) -> Unit,
    onSaveMedia: (GoodPostMedia) -> Unit,
    onRevealMedia: (String) -> Unit,
    onReact: (GoodPostPost, String) -> Unit,
    onVote: (GoodPostPost, List<String>) -> Unit,
    onSeen: (String) -> Unit,
    onMessage: () -> Unit,
    onOpenInbox: () -> Unit,
    onAnalytics: () -> Unit,
    onReport: () -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
    onToggleFollow: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleBlock: () -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current

    // Idempotent for the channel already loaded, so opening the same channel
    // twice does not refetch what is already on screen.
    LaunchedEffect(channel.id) { onLoadPosts() }

    val canPost = channel.viewerRole == "owner" || channel.viewerRole == "editor"
    val canReadAnalytics = canPost
    val canAnswerMessages = canPost || channel.viewerRole == "responder"

    WaFullScreen(onDismiss = onDismiss, background = Wa.Canvas) {
        WaTopBar(
            title = channel.name,
            subtitle = stringResource(
                R.string.goodpost_followers,
                channel.followerCount.toString()
            ),
            navigation = {
                WaIconAction(
                    icon = Icons.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_cancel),
                    onClick = onDismiss
                )
            },
            actions = {
                // Muting is a follower's control. An owner receives no
                // notifications from their own channel, so offering it would be
                // offering a switch that does nothing.
                if (!channel.isOwner) {
                    WaIconAction(
                        icon = if (channel.isMuted) {
                            Icons.Filled.NotificationsOff
                        } else {
                            Icons.Filled.Notifications
                        },
                        description = stringResource(
                            if (channel.isMuted) R.string.goodpost_unmute
                            else R.string.goodpost_mute
                        ),
                        enabled = channel.isFollowing && !busy,
                        onClick = onToggleMute
                    )
                }

                WaOverflowMenu(
                    items = buildList {
                        // §6's link, shipped only now that it resolves: the slug
                        // route turns it back into this channel and the manifest
                        // filter opens the app from it.
                        add(
                            WaMenuItem(
                                label = stringResource(R.string.goodpost_share),
                                onClick = { shareChannel(context, channel) }
                            )
                        )
                        if (canReadAnalytics) {
                            add(
                                WaMenuItem(
                                    label = stringResource(R.string.goodpost_analytics),
                                    onClick = onAnalytics
                                )
                            )
                        }
                        if (canAnswerMessages) {
                            add(
                                WaMenuItem(
                                    label = stringResource(R.string.goodpost_channel_inbox),
                                    onClick = onOpenInbox
                                )
                            )
                        }
                        if (channel.isOwner) {
                            add(
                                WaMenuItem(
                                    label = stringResource(R.string.goodpost_edit_title),
                                    onClick = onEdit
                                )
                            )
                        }
                        // §18's report, open to anyone; §12's block, for anyone
                        // but the owner, who cannot block their own channel.
                        add(
                            WaMenuItem(
                                label = stringResource(R.string.goodpost_report_channel),
                                onClick = onReport
                            )
                        )
                        if (!channel.isOwner) {
                            add(
                                WaMenuItem(
                                    label = stringResource(
                                        if (channel.isBlocked) R.string.goodpost_unblock
                                        else R.string.goodpost_block
                                    ),
                                    onClick = onToggleBlock,
                                    destructive = !channel.isBlocked
                                )
                            )
                        }
                    }
                )
            }
        )

        if (postsStale) WaStaleBanner(onRetry = onLoadPosts)

        val showMessageBar = !channel.isOwner && channel.isFollowing && channel.allowFollowerMessages

        Box(modifier = Modifier.weight(1f)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                // Room for the compose button, and for the message bar when it
                // is there, so the last post is never trapped behind a control.
                bottom = if (canPost) 96.dp else 16.dp
            )
        ) {
            item {
                ChannelHeader(
                    channel = channel,
                    busy = busy,
                    onToggleFollow = onToggleFollow,
                    onEdit = onEdit
                )
            }

            if (posts.isEmpty()) {
                item {
                    WaEmptyState(
                        title = stringResource(R.string.goodpost_empty_channel_posts),
                        note = stringResource(R.string.goodpost_empty_feed_note)
                    )
                }
            }

            items(posts, key = { it.id }) { post ->
                PostBubble(
                    post = post,
                    // The channel is not named on its own screen: the header
                    // above already says which one this is.
                    showChannel = false,
                    savedMediaIds = savedMediaIds,
                    savingMediaId = savingMediaId,
                    revealedMediaIds = revealedMediaIds,
                    busy = busyPostId == post.id,
                    reactionBusy = busyReactionPostId == post.id,
                    voteBusy = votingPollId == post.engagement.poll?.id,
                    onOpenChannel = {},
                    onEdit = { onEditPost(post) },
                    onDelete = { onDeletePost(post) },
                    onSaveMedia = onSaveMedia,
                    onRevealMedia = onRevealMedia,
                    onReact = { reaction -> onReact(post, reaction) },
                    onVote = { options -> onVote(post, options) },
                    onSeen = { onSeen(post.id) },
                    onReport = onReport
                )
            }
        }

            // The compose action for a channel this viewer may publish to. A FAB
            // rather than a bar button because it is the one thing an owner
            // comes here to do.
            if (canPost) {
                WaFab(
                    icon = Icons.Filled.Edit,
                    description = stringResource(R.string.goodpost_compose),
                    onClick = onCompose,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = if (showMessageBar) 84.dp else 16.dp)
                )
            }
        }

        // §16. Only where the server would accept it: the channel has to have
        // follower messages on, and this viewer has to be following. A button
        // that can only fail is a button that should not be there.
        if (showMessageBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Wa.Bar)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable(onClick = onMessage)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Forum,
                        contentDescription = null,
                        tint = Wa.TextDim,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.goodpost_message_channel),
                        color = Wa.Text,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

/**
 * The channel's profile block.
 *
 * Follow is drawn for everyone but the owner: the API refuses a self-follow, and
 * a button that always errors is worse than no button (§32).
 */
@Composable
private fun ChannelHeader(
    channel: GoodPostChannel,
    busy: Boolean,
    onToggleFollow: () -> Unit,
    onEdit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WaAvatar(name = channel.name, size = 92.dp)

        Spacer(Modifier.height(12.dp))

        Text(
            text = channel.name,
            color = Wa.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.goodpost_followers, channel.followerCount.toString()),
            color = Wa.TextDim,
            fontSize = 13.sp
        )

        if (channel.categoryLabel != null) {
            Spacer(Modifier.height(8.dp))
            WaFilterPill(label = channel.categoryLabel, selected = false, onClick = {})
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = channel.description
                ?: stringResource(R.string.goodpost_channel_no_description),
            color = Wa.TextDim,
            fontSize = 14.sp
        )

        if (channel.isBlocked) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Wa.Danger,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.goodpost_blocked),
                    color = Wa.Danger,
                    fontSize = 13.sp
                )
            }
        }

        if (!channel.isAvailable) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.goodpost_error_channel_unavailable),
                color = Wa.Danger,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        if (!channel.isOwner) {
            WaPrimaryButton(
                text = stringResource(
                    if (channel.isFollowing) R.string.goodpost_unfollow
                    else R.string.goodpost_follow
                ),
                enabled = channel.isAvailable && !channel.isBlocked,
                busy = busy,
                onClick = onToggleFollow
            )
        } else {
            // The owner's own entry point to the channel's settings, which is
            // where the §16 follower-message switch lives.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Wa.Accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.goodpost_owner_note),
                    color = Wa.Accent,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Offer a channel's deep link to another app (§6).
 *
 * `EXTRA_TEXT` is the link ALONE, not the name plus the link: the receiving app
 * decides what to do with the body, and prose mixed with a URI is what turns a
 * working link into a dead string the moment it is pasted. The name travels as
 * the subject, which is what a mail client actually uses.
 */
private fun shareChannel(context: Context, channel: GoodPostChannel) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, channel.shareLink)
        putExtra(Intent.EXTRA_SUBJECT, channel.name)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.goodpost_share_chooser))
    )
}

// ── Create / edit dialog (§6, §7) ───────────────────────────────────────

/**
 * One dialog for create and edit.
 *
 * They collect the same fields and differ only in their labels and their
 * confirm action, so a second near-identical composable would only be a place
 * for the two to drift.
 */
@Composable
private fun ChannelFormDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialDescription: String,
    /** Null hides the §16 switch — set when editing an existing channel. */
    initialAllowMessages: Boolean?,
    categories: List<GoodPostCategory>,
    errorCode: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, String?, Boolean?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var category by remember { mutableStateOf<String?>(null) }
    var allowMessages by remember { mutableStateOf(initialAllowMessages ?: false) }

    WaDialog(onDismiss = onDismiss) {
        Text(
            text = title,
            color = Wa.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(16.dp))

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            ChannelField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.goodpost_channel_name_label)
            )

            Spacer(Modifier.height(10.dp))

            ChannelField(
                value = description,
                onValueChange = { description = it },
                placeholder = stringResource(R.string.goodpost_channel_description_label)
            )

            if (categories.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.goodpost_category_label),
                    color = Wa.TextDim,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { option ->
                        WaFilterPill(
                            label = option.label,
                            selected = category == option.slug,
                            onClick = {
                                // Tapping the selected chip clears it, so a
                                // category can be unchosen without a separate
                                // "none" control.
                                category = if (category == option.slug) null else option.slug
                            }
                        )
                    }
                }
            }

            if (initialAllowMessages != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = allowMessages,
                        onCheckedChange = { allowMessages = it }
                    )
                    Text(
                        text = stringResource(R.string.goodpost_allow_messages),
                        color = Wa.Text,
                        fontSize = 13.sp
                    )
                }
            }

            if (errorCode != null) {
                Spacer(Modifier.height(12.dp))
                WaErrorNotice(errorCode)
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            WaTextAction(
                text = stringResource(R.string.goodpost_cancel),
                onClick = onDismiss
            )
            Spacer(Modifier.width(8.dp))
            WaTextAction(
                text = confirmLabel,
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        name,
                        description.takeIf { it.isNotBlank() },
                        category,
                        // Null means "this dialog does not offer the switch",
                        // which the backend reads as "leave it alone".
                        initialAllowMessages?.let { allowMessages }
                    )
                }
            )
        }
    }
}

/** A one-line field on the channel form's dark card. */
@Composable
private fun ChannelField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Wa.List)
            .border(1.dp, Wa.Divider, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp)
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(text = placeholder, color = Wa.TextDim, fontSize = 15.sp) },
            textStyle = TextStyle(fontSize = 15.sp, color = Wa.Text),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Wa.Accent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
