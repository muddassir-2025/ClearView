package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostInboxTab
import com.muddassir.clearview.goodpost.data.GoodPostConversation
import com.muddassir.clearview.goodpost.data.GoodPostMessage
import com.muddassir.clearview.goodpost.data.GoodPostNotice
import com.muddassir.clearview.goodpost.data.GoodPostNotification

/**
 * The inbox (§16, §17, §26).
 *
 * Three tabs over one list, because the three things that arrive for an account
 * are the same kind of thing — mail — and differ only in what tapping one does.
 * Keeping them together means one screen, one loading state and one place the
 * unread badge is decided.
 *
 * Rows are drawn as conversations, which is what they are: a notification, a
 * notice from the platform and a private thread all have a source, something
 * that was said, and a time. Presentation only (§35) — nothing here reads a
 * token, a URL or a file.
 */
@Composable
internal fun InboxSection(
    tab: GoodPostInboxTab,
    notifications: List<GoodPostNotification>,
    notices: List<GoodPostNotice>,
    conversations: List<GoodPostConversation>,
    loading: Boolean,
    onSelectTab: (GoodPostInboxTab) -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenNotification: (GoodPostNotification) -> Unit,
    onOpenNotice: (GoodPostNotice) -> Unit,
    onOpenThread: (GoodPostConversation) -> Unit,
    onRetry: () -> Unit
) {
    val order = listOf(
        GoodPostInboxTab.Notifications,
        GoodPostInboxTab.Notices,
        GoodPostInboxTab.Messages
    )
    val tabs = listOf(
        WaTab(label = stringResource(R.string.goodpost_inbox_notifications)),
        WaTab(label = stringResource(R.string.goodpost_inbox_notices)),
        WaTab(label = stringResource(R.string.goodpost_inbox_messages))
    )

    Column(modifier = Modifier.fillMaxSize().background(Wa.List)) {
        WaTabRow(
            tabs = tabs,
            selectedIndex = order.indexOf(tab).coerceAtLeast(0),
            onSelect = { onSelectTab(order[it]) }
        )

        // Clearing the badge is offered only where the server accepts it:
        // notifications and notices have a read route, and a conversation is
        // marked read by reading it.
        if (tab != GoodPostInboxTab.Messages) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                WaTextAction(
                    text = stringResource(R.string.goodpost_inbox_mark_all_read),
                    onClick = onMarkAllRead
                )
            }
        }

        if (loading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = Wa.Accent
                )
            }
        }

        when (tab) {
            GoodPostInboxTab.Notifications -> NotificationList(
                items = notifications,
                onOpen = onOpenNotification,
                onRetry = onRetry
            )

            GoodPostInboxTab.Notices -> NoticeList(items = notices, onOpen = onOpenNotice)
            GoodPostInboxTab.Messages -> ConversationList(
                conversations = conversations,
                onOpen = onOpenThread,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun NotificationList(
    items: List<GoodPostNotification>,
    onOpen: (GoodPostNotification) -> Unit,
    onRetry: () -> Unit
) {
    if (items.isEmpty()) {
        WaEmptyState(
            title = stringResource(R.string.goodpost_inbox_empty_notifications),
            note = stringResource(R.string.goodpost_empty_feed_note),
            actionLabel = stringResource(R.string.goodpost_retry),
            onAction = onRetry
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            WaListRow(
                title = item.title,
                preview = item.body,
                onClick = { onOpen(item) },
                avatar = { WaAvatar(name = item.title, size = 48.dp) },
                trailing = {
                    Text(
                        text = waListStamp(item.createdAtMs),
                        color = if (item.isUnread) Wa.Accent else Wa.TextDim,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    if (item.isUnread) {
                        // A dot rather than a count: one notification is one
                        // notification, and a "1" badge would read as a number
                        // of unread messages instead of a marker.
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Wa.Accent)
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun NoticeList(items: List<GoodPostNotice>, onOpen: (GoodPostNotice) -> Unit) {
    if (items.isEmpty()) {
        WaEmptyState(title = stringResource(R.string.goodpost_inbox_empty_notices))
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            WaListRow(
                title = item.subject,
                preview = item.body,
                onClick = { onOpen(item) },
                avatar = {
                    // Notices come from the platform, so they carry the app's
                    // own name rather than an initial from the subject — §26
                    // wants an official message to be unmistakable.
                    WaAvatar(name = stringResource(R.string.goodpost_notification_channel), size = 48.dp)
                },
                trailing = {
                    Text(
                        text = waListStamp(item.createdAtMs),
                        color = if (item.isUnread) Wa.Accent else Wa.TextDim,
                        fontSize = 12.sp
                    )
                    if (item.isUnread) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.goodpost_inbox_unread, 1),
                            color = Wa.Accent,
                            fontSize = 11.sp
                        )
                    }
                }
            )
        }
    }
}

/** The private threads between a channel and this account (§16). */
@Composable
internal fun ConversationList(
    conversations: List<GoodPostConversation>,
    onOpen: (GoodPostConversation) -> Unit,
    modifier: Modifier = Modifier
) {
    if (conversations.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
            WaEmptyState(
                title = stringResource(R.string.goodpost_inbox_empty_messages),
                note = stringResource(R.string.goodpost_message_private)
            )
        }
        return
    }

    LazyColumn(modifier = modifier) {
        items(conversations, key = { it.id }) { conversation ->
            WaListRow(
                title = conversation.channelName,
                // The follower's own name is never shown to a channel's
                // audience, and the channel's name is what this list is about;
                // a preview says what was last said, nothing more (§12, §38).
                preview = conversation.lastMessagePreview
                    ?: stringResource(R.string.goodpost_inbox_empty_messages),
                onClick = { onOpen(conversation) },
                avatar = { WaAvatar(name = conversation.channelName, size = 48.dp) },
                trailing = {
                    Text(
                        text = waListStamp(conversation.lastMessageAtMs),
                        color = if (conversation.hasUnread) Wa.Accent else Wa.TextDim,
                        fontSize = 12.sp
                    )
                    if (conversation.hasUnread) {
                        Spacer(Modifier.height(4.dp))
                        WaUnreadBadge(conversation.unreadCount)
                    } else if (conversation.blocked) {
                        Spacer(Modifier.height(4.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Wa.TextDim,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            )
        }
    }
}

/**
 * One private thread (§16), drawn as a chat.
 *
 * §36: a message is only reported as sent once the server accepted it, so a
 * failed send keeps the text and says so — losing what someone wrote because a
 * request timed out is worse than showing it as unsent.
 */
@Composable
internal fun ThreadDialog(
    conversation: GoodPostConversation,
    messages: List<GoodPostMessage>,
    draft: String,
    sending: Boolean,
    unsent: String?,
    errorCode: String?,
    /** The follower side of the thread, which is the side that cannot block. */
    canBlock: Boolean,
    /**
     * True when the reader is the channel, false when they are the follower.
     *
     * [GoodPostMessage.fromAdmin] says who WROTE a message, not which side of
     * the screen it belongs on: the same message is "from the channel" to
     * both readers, so the side has to come from the reader. Deriving it from
     * `fromAdmin` alone put a follower's own words on the left, as though the
     * channel had said them.
     */
    viewerIsChannelSide: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleBlock: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()

    // A conversation opens at its newest message, which is the only part of a
    // long thread anyone is looking for.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    WaFullScreen(onDismiss = onDismiss, background = Wa.Canvas) {
        WaTopBar(
            title = conversation.channelName,
            subtitle = if (conversation.isOpen) {
                stringResource(R.string.goodpost_message_private)
            } else {
                stringResource(R.string.goodpost_conversation_closed)
            },
            navigation = {
                WaIconAction(
                    icon = Icons.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_cancel),
                    onClick = onDismiss
                )
            },
            actions = {
                // Only the channel side can block a thread (§16), so the action
                // is offered only where the server would accept it.
                if (canBlock) {
                    WaOverflowMenu(
                        items = listOf(
                            WaMenuItem(
                                label = stringResource(
                                    if (conversation.blocked) R.string.goodpost_message_unblock
                                    else R.string.goodpost_message_block
                                ),
                                onClick = onToggleBlock,
                                destructive = !conversation.blocked
                            )
                        )
                    )
                }
            }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                // A date pill whenever the day changes, which is the one piece
                // of context a chat list cannot infer from its neighbours.
                val stamp = message.createdAtMs
                val previous = messages.getOrNull(index - 1)?.createdAtMs
                if (stamp != null && (previous == null || !waSameDay(previous, stamp))) {
                    WaDatePill(text = waDayLabel(stamp))
                }
                MessageBubble(message = message, viewerIsChannelSide = viewerIsChannelSide)
            }
        }

        if (unsent != null || (errorCode != null && conversation.isOpen)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (unsent != null) {
                    Text(
                        text = stringResource(R.string.goodpost_message_failed),
                        color = Wa.Danger,
                        fontSize = 12.sp
                    )
                }
                if (errorCode != null) {
                    WaErrorNotice(errorCode)
                }
            }
        }

        if (conversation.isOpen) {
            WaInputBar(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = stringResource(R.string.goodpost_message_hint),
                onSend = onSend,
                sending = sending,
                enabled = !sending
            )
        } else {
            // A closed thread replaces the input rather than showing a disabled
            // one: a field that cannot accept a message is a field that lies.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Wa.Bar)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.goodpost_conversation_closed),
                    color = Wa.TextDim,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * One message in a thread, on the reader's own side or the other one.
 *
 * "Mine" is the question, and it is not the same question as "from the
 * channel": to a follower, the channel's words are the incoming ones, and to
 * the channel, that same message is the outgoing one. So the side is
 * `fromAdmin == viewerIsChannelSide` — the two agree exactly when the writer is
 * the reader's counterpart, and the bubble lands on the right only when the
 * reader wrote it.
 *
 * The date pill above a bubble is emitted by the LIST, from the message before
 * it, so the bubble itself stays a bubble and knows nothing about its
 * neighbours.
 */
@Composable
private fun MessageBubble(message: GoodPostMessage, viewerIsChannelSide: Boolean) {
    val mine = message.fromAdmin == viewerIsChannelSide
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        WaBubble(
            outgoing = mine,
            modifier = Modifier.fillMaxWidth(0.82f)
        ) {
            Text(text = message.body, color = Wa.Text, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            WaTimeLabel(
                text = waClock(message.createdAtMs),
                modifier = Modifier.align(Alignment.End),
                // A blue tick only where the server recorded a read: an
                // unread receipt invented by the UI would be a small lie that
                // matters when the other side is a stranger.
                read = message.readAt != null
            )
        }
    }
}

/**
 * A channel's own inbox: the private threads its followers started (§16).
 *
 * Its own screen rather than an inbox tab because it belongs to a CHANNEL, not
 * to the account: it is opened from the channel it belongs to, and the title
 * says which one so a reply can never be written to the wrong audience.
 */
@Composable
internal fun ChannelInboxDialog(
    channelName: String,
    conversations: List<GoodPostConversation>,
    onOpenThread: (GoodPostConversation) -> Unit,
    onDismiss: () -> Unit
) {
    WaFullScreen(onDismiss = onDismiss, background = Wa.List) {
        WaTopBar(
            title = stringResource(R.string.goodpost_channel_inbox),
            subtitle = channelName,
            navigation = {
                WaIconAction(
                    icon = Icons.Filled.Close,
                    description = stringResource(R.string.goodpost_cancel),
                    onClick = onDismiss
                )
            }
        )

        if (conversations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                WaEmptyState(
                    title = stringResource(R.string.goodpost_inbox_empty_messages),
                    note = stringResource(R.string.goodpost_message_private)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(conversations, key = { it.id }) { conversation ->
                    WaListRow(
                        // The follower's display name is the one identity a
                        // channel is allowed to see (§12); their number and
                        // address are never in this payload.
                        title = conversation.followerDisplayName
                            ?: stringResource(R.string.goodpost_dm_unknown_sender),
                        preview = conversation.lastMessagePreview
                            ?: stringResource(R.string.goodpost_inbox_empty_messages),
                        onClick = { onOpenThread(conversation) },
                        avatar = {
                            WaAvatar(
                                name = conversation.followerDisplayName
                                    ?: stringResource(R.string.goodpost_dm_unknown_sender),
                                size = 48.dp
                            )
                        },
                        trailing = {
                            Text(
                                text = waListStamp(conversation.lastMessageAtMs),
                                color = if (conversation.hasUnread) Wa.Accent else Wa.TextDim,
                                fontSize = 12.sp
                            )
                            if (conversation.hasUnread) {
                                Spacer(Modifier.height(4.dp))
                                WaUnreadBadge(conversation.unreadCount)
                            }
                        }
                    )
                }
            }
        }
    }
}

