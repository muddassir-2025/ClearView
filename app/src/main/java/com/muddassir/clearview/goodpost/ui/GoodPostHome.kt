package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostChannel
import com.muddassir.clearview.goodpost.data.parseIsoMillis

/**
 * The Good Post home screen: the public channel list (§1, §3, §4, §15).
 *
 * A reader's list is the catalogue itself — every channel that is publishing —
 * because there is nothing to choose first: no account, no follow, no setup.
 * Opening the tab and finding the channels already there IS the product, and the
 * row answers the only question it needs to ("who is this, what did they last
 * say, and when") without a single counter beside it.
 *
 * Explore, one tap away, is the same list with a search box and category filters
 * in front of it; this screen is the short version for someone who just wants to
 * read what is new.
 *
 * When an administrator is signed in, the same list becomes the channels their
 * account has access to — every channel for a super administrator, the one they
 * run for a channel administrator (§3). There is no second, dashboard-shaped
 * screen: the tab IS the admin surface, which is what keeps this feeling like
 * WhatsApp rather than like a control panel.
 *
 * Rows are compact and carry three things: who the channel is, what it last said,
 * and when. Nothing is counted (§1, §5).
 */
@Composable
internal fun GoodPostHome(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.channelSelectionActive) {
                ChannelsSelectionBar(
                    state = state,
                    viewModel = viewModel,
                    onDelete = { confirmDelete = true }
                )
            } else {
                WaTopBar(
                    title = stringResource(R.string.goodpost_tab),
                    actions = {
                        WaIconAction(
                            icon = Icons.Filled.Search,
                            description = stringResource(R.string.goodpost_search),
                            onClick = { viewModel.openExplore() }
                        )
                        WaOverflowMenu(
                            items = buildList {
                                // Only a reader, or a super administrator, has any
                                // use for the way in: a channel administrator's
                                // channel is already on this list (§15, §17).
                                if (state.canCreateChannel) {
                                    add(
                                        WaMenuItem(
                                            label = stringResource(R.string.goodpost_create_channel),
                                            onClick = viewModel::openAdmin
                                        )
                                    )
                                }
                                add(
                                    WaMenuItem(
                                        label = stringResource(R.string.goodpost_refresh),
                                        onClick = viewModel::refreshChannels
                                    )
                                )
                                if (state.isAdmin) {
                                    add(
                                        WaMenuItem(
                                            label = stringResource(R.string.goodpost_sign_out),
                                            onClick = viewModel::adminSignOut,
                                            destructive = true
                                        )
                                    )
                                }
                            }
                        )
                    }
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item(key = "header") {
                    ChannelsHeader(onExplore = { viewModel.openExplore() })
                }

                if (state.channelsStale) {
                    item(key = "stale") {
                        WaStaleBanner(onRetry = viewModel::refreshChannels)
                    }
                }

                if (state.channels.isEmpty() && state.channelsError != null) {
                    item(key = "error") {
                        WaErrorNotice(state.channelsError)
                    }
                }

                items(state.tabChannels, key = { it.id }) { channel ->
                    // Only a channel this account may manage says so; every other
                    // row is the reader's view of it.
                    val manageable = state.canManage(channel.id)

                    ChannelRow(
                        channel = channel,
                        selected = state.selectedChannelIds.contains(channel.id),
                        selectionActive = state.channelSelectionActive,
                        manageable = manageable,
                        onClick = {
                            if (state.channelSelectionActive) {
                                viewModel.toggleChannelSelected(channel.id)
                            } else {
                                viewModel.openChannel(channel.id)
                            }
                        },
                        // Long press is offered only where there is something to
                        // do with it (§5): a reader has no action to take on a
                        // channel, so their list is simply a list of links.
                        onLongClick = if (manageable) {
                            { viewModel.toggleChannelSelected(channel.id) }
                        } else {
                            null
                        }
                    )
                }

                if (state.tabChannels.isEmpty() && state.tabLoading) {
                    item(key = "loading") { CenteredProgress(Modifier.height(160.dp)) }
                }

                if (state.tabChannels.isEmpty() && !state.tabLoading &&
                    state.channelsError == null
                ) {
                    item(key = "empty") {
                        EmptyChannels(state = state, viewModel = viewModel)
                    }
                }

                // §15: a small utility row at the very bottom, never above the
                // list, and only where there is a channel to create.
                if (state.canCreateChannel) {
                    item(key = "create") {
                        CreateChannelFooter(onClick = viewModel::openAdmin)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteChannelsDialog(
            state = state,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteSelectedChannels()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

/**
 * The bar a selection replaces the title with (§5).
 *
 * Which actions appear is the permission model, not a preference: a reader has no
 * action to take on a channel at all, so their list cannot even enter selection
 * mode, while an account that runs a channel may edit or delete it. The server
 * refuses the rest regardless, but offering a control that always fails is its
 * own kind of lie.
 */
@Composable
private fun ChannelsSelectionBar(
    state: GoodPostUiState,
    viewModel: GoodPostViewModel,
    onDelete: () -> Unit
) {
    val selectedIds = state.selectedChannelIds
    // A selection can only begin on a row this account manages, so both actions
    // here always apply to what is selected.
    val editable = selectedIds.size == 1

    WaSelectionBar(
        count = selectedIds.size,
        onClose = viewModel::clearChannelSelection,
        actions = {
            if (editable) {
                WaIconAction(
                    icon = Icons.Filled.Edit,
                    description = stringResource(R.string.goodpost_edit_channel),
                    onClick = {
                        state.tabChannels.firstOrNull { it.id == selectedIds.first() }
                            ?.let { viewModel.startEditChannel(it) }
                    }
                )
            }

            WaIconAction(
                icon = Icons.Filled.Delete,
                description = stringResource(R.string.goodpost_delete),
                tint = Wa.Danger,
                onClick = onDelete
            )
        }
    )
}

/**
 * The dialog before a channel is deleted (§17).
 *
 * The only confirmation in the app, because deleting a channel is the only
 * action that cannot be taken back: the channel goes from the server along with
 * its posts, its media and the login that ran it (§17). It names what will go,
 * since "delete" alone would not lead anyone to expect all of that.
 */
@Composable
private fun DeleteChannelsDialog(
    state: GoodPostUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val selected = state.selectedChannelIds

    if (selected.size == 1) {
        val name = state.tabChannels.firstOrNull { it.id == selected.first() }?.name.orEmpty()
        WaConfirmDialog(
            title = stringResource(R.string.goodpost_delete_channel_title),
            message = stringResource(R.string.goodpost_delete_channel_note, name),
            confirmLabel = stringResource(R.string.goodpost_delete),
            onConfirm = onConfirm,
            onDismiss = onDismiss
        )
    } else {
        WaConfirmDialog(
            title = stringResource(R.string.goodpost_delete_channels_title),
            message = stringResource(R.string.goodpost_delete_channels_note),
            confirmLabel = stringResource(R.string.goodpost_delete),
            onConfirm = onConfirm,
            onDismiss = onDismiss
        )
    }
}

/**
 * What the tab says when it has nothing to show (§1).
 *
 * Two different answers, because the two states are different: a reader looking
 * at an empty catalogue needs to be told where channels come from, and an account
 * with no channels needs the way to create one.
 */
@Composable
private fun EmptyChannels(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    if (state.isAdmin) {
        WaEmptyState(
            title = stringResource(R.string.goodpost_empty_admin_title),
            note = stringResource(R.string.goodpost_empty_admin_note),
            actionLabel = stringResource(R.string.goodpost_create_channel).takeIf {
                state.canCreateChannel
            },
            onAction = viewModel::openAdmin
        )
    } else {
        WaEmptyState(
            title = stringResource(R.string.goodpost_empty_channels_title),
            note = stringResource(R.string.goodpost_empty_channels_note),
            actionLabel = stringResource(R.string.goodpost_explore),
            onAction = { viewModel.openExplore() }
        )
    }
}

/** "Channels" on the left, the Explore pill on the right (§3). */
@Composable
private fun ChannelsHeader(onExplore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.goodpost_channels),
            color = Wa.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        WaPillButton(text = stringResource(R.string.goodpost_explore), onClick = onExplore)
    }
}

/**
 * One channel in the list (§4).
 *
 * Three things and nothing else: what it is called, what it last said, and when.
 * The preview falls back to the description only for a channel that has never
 * posted, where there is no preview to show and the description is the one useful
 * thing available.
 */
@Composable
private fun ChannelRow(
    channel: GoodPostChannel,
    selected: Boolean,
    selectionActive: Boolean,
    manageable: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val posted = channel.lastPostAt != null
    val postPreview = channel.lastPostPreview?.takeIf { it.isNotBlank() }
    // Whether the line below is a post's own text, which is the one case that
    // carries formatting markers. A suspended channel's notice, a media label and
    // a description are prose, not posts.
    val previewIsPostBody = channel.status != "suspended" && posted && postPreview != null
    val preview = when {
        channel.status == "suspended" -> stringResource(R.string.goodpost_suspended)
        // `?: ""` cannot happen (previewIsPostBody implies a non-blank
        // preview); it is here because the compiler cannot see that through a
        // boolean.
        previewIsPostBody -> postPreview ?: ""
        // A post with no text is still a post — a photo, a video, a link. Saying
        // what it is beats an empty line, and it is what the small icon beside it
        // cannot say on its own.
        posted -> stringResource(mediaLabel(channel.lastPostType))
        else -> channel.description?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.goodpost_no_description)
    }

    val at = parseIsoMillis(channel.lastPostAt)

    WaChannelRow(
        title = channel.name,
        preview = preview,
        previewIsPostBody = previewIsPostBody,
        timestamp = if (posted) waListStamp(at) else null,
        timestampRecent = waStampIsRecent(at),
        onClick = onClick,
        onLongClick = onLongClick,
        selected = selected,
        avatar = { WaAvatar(name = channel.name, size = 49.dp, url = channel.iconUrl) },
        previewIcon = mediaIcon(channel.lastPostType),
        trailing = {
            // Only while something is selected, and only on the rows it applies
            // to: a control on every row would be the icon soup §5 rules out.
            if (selectionActive && manageable) {
                Text(
                    text = stringResource(R.string.goodpost_edit),
                    color = Wa.TextDim,
                    fontSize = 12.sp
                )
            }
        }
    )
}

/**
 * The small media-type indicator beside a preview (§5).
 *
 * Null for a text post, which is the common case and the one that should stay
 * quiet: a row of icons on every line would say nothing.
 */
private fun mediaIcon(type: String?): ImageVector? = when (type) {
    "image" -> Icons.Filled.Image
    "video" -> Icons.Filled.Videocam
    "link" -> Icons.Filled.Link
    else -> null
}

/** The word a media-only post is described by, when it has no text. */
private fun mediaLabel(type: String?): Int = when (type) {
    "image" -> R.string.goodpost_posted_photo
    "video" -> R.string.goodpost_posted_video
    "link" -> R.string.goodpost_posted_link
    else -> R.string.goodpost_posted_something
}

/**
 * The footer's "Create a channel" (§15).
 *
 * Deliberately not a floating button and deliberately not above the list: it is
 * a utility, and the screens a reader uses must not be pushed down by a control
 * almost nobody needs.
 */
@Composable
private fun CreateChannelFooter(onClick: () -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(1.dp)
                .background(Wa.Divider)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Wa.Bar),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = Wa.Accent,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.goodpost_create_channel),
                color = Wa.TextDim,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
