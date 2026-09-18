package com.muddassir.clearview.goodpost.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
    var homeMediaUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val homeMediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) homeMediaUris = uris
    }

    Box(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.channelSelectionActive) {
                ChannelsSelectionBar(
                    state = state,
                    viewModel = viewModel,
                    onDelete = { confirmDelete = true }
                )
            } else {
                WaTopBar(
                    title = stringResource(R.string.goodpost_nav_updates),
                    actions = {
                        WaIconAction(
                            icon = Icons.Filled.Search,
                            description = stringResource(R.string.goodpost_search),
                            onClick = { viewModel.openExplore() }
                        )
                        WaOverflowMenu(
                            items = buildList {
                                if (state.canCreateChannel) {
                                    add(
                                        WaMenuItem(
                                            label = stringResource(R.string.goodpost_create_channel),
                                            onClick = viewModel::openAdmin
                                        )
                                    )
                                }
                                state.ownChannel?.let {
                                    add(
                                        WaMenuItem(
                                            label = stringResource(R.string.goodpost_my_channel),
                                            onClick = viewModel::openMyChannel
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item(key = "status") {
                    StatusSection(
                        onAddStatus = {
                            homeMediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        }
                    )
                }

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
                    val manageable = state.canManage(channel.id)

                    ChannelRow(
                        channel = channel,
                        modifier = Modifier.animateItem(),
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

                if (state.canCreateChannel) {
                    item(key = "create") {
                        CreateChannelFooter(onClick = viewModel::openAdmin)
                    }
                }
            }

            WhatsAppBottomNav(selectedTab = "updates")
        }

        // Double Floating Action Buttons (pencil + camera) above Bottom Navigation Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Wa.Bar)
                    .clickable {
                        if (state.canCreateChannel) {
                            viewModel.openAdmin()
                        } else {
                            viewModel.openExplore()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Create channel update",
                    tint = Wa.Text,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Wa.Accent)
                    .clickable {
                        homeMediaPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = "Camera",
                    tint = Wa.OnAccent,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        if (homeMediaUris.isNotEmpty()) {
            GoodPostMediaEditor(
                mediaUris = homeMediaUris,
                onDismiss = { homeMediaUris = emptyList() },
                onSend = { attachments, caption ->
                    homeMediaUris = emptyList()
                    if (state.ownChannel != null) {
                        viewModel.openChannel(state.ownChannel.id)
                        attachments.forEach { viewModel.attachMedia(it) }
                        if (caption.isNotBlank()) viewModel.onComposerBodyChange(caption)
                        viewModel.publish()
                    } else if (state.canCreateChannel) {
                        viewModel.openAdmin()
                    }
                }
            )
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
 *
 * It also makes the administrator TYPE. A destructive button in a dialog is one
 * tap away from the row underneath it, and this list is a list of rows that look
 * alike: a slip of the thumb on a list of channels somebody else runs should not
 * remove one of them. Typing the channel's own name is what turns a tap into a
 * decision — it cannot be done without reading which channel it is.
 *
 * One channel asks for its name; a selection of several asks for the word, since
 * there is no single name to type and the point is still that it is deliberate.
 */
@Composable
private fun DeleteChannelsDialog(
    state: GoodPostUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val selected = state.selectedChannelIds
    val word = stringResource(R.string.goodpost_delete_confirm_word)

    if (selected.size == 1) {
        val name = state.tabChannels.firstOrNull { it.id == selected.first() }?.name.orEmpty()
        WaTypedConfirmDialog(
            title = stringResource(R.string.goodpost_delete_channel_title),
            message = stringResource(R.string.goodpost_delete_channel_note, name),
            expected = name,
            label = stringResource(R.string.goodpost_delete_type_name),
            confirmLabel = stringResource(R.string.goodpost_delete),
            onConfirm = onConfirm,
            onDismiss = onDismiss
        )
    } else {
        WaTypedConfirmDialog(
            title = stringResource(R.string.goodpost_delete_channels_title),
            message = stringResource(R.string.goodpost_delete_channels_note),
            expected = word,
            label = stringResource(R.string.goodpost_delete_type_word, word),
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
    modifier: Modifier = Modifier,
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
        modifier = modifier,
        preview = preview,
        previewIsPostBody = previewIsPostBody,
        timestamp = if (posted) waListStamp(at) else null,
        timestampRecent = waStampIsRecent(at),
        onClick = onClick,
        onLongClick = onLongClick,
        selected = selected,
        avatar = { WaAvatar(name = channel.name, size = 49.dp, url = channel.iconUrl) },
        previewIcon = mediaIcon(channel.lastPostType),
        // §5: what is waiting, under the time and nowhere else. The view count
        // is NOT here — it moved into the post it counts (§9), because a number
        // on a row can only describe the row's own preview, and on a row that
        // was showing a description it described nothing at all.
        unreadCount = if (channel.following) channel.unreadCount else 0,
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

/**
 * Status section matching WhatsApp Updates tab (Screenshot 1: 4ece930a-b3f8-46bd-9d7b-5325eb600bea.jpg).
 */
@Composable
private fun StatusSection(onAddStatus: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Status",
                color = Wa.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = null,
                tint = Wa.TextDim,
                modifier = Modifier.size(20.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAddStatus)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(50.dp)) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Wa.Bar),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PhotoCamera,
                        contentDescription = null,
                        tint = Wa.TextDim,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Wa.Accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add status",
                        tint = Wa.OnAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "My status",
                    color = Wa.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Tap to add status update",
                    color = Wa.TextDim,
                    fontSize = 13.5.sp
                )
            }
        }
    }
}

/**
 * WhatsApp Bottom Navigation Bar matching Screenshot 1 (Chats, Updates, Communities, Calls).
 */
@Composable
private fun WhatsAppBottomNav(
    selectedTab: String = "updates",
    onTabSelected: (String) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Wa.Canvas)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomNavItem(
            icon = Icons.Filled.ChatBubble,
            label = stringResource(R.string.goodpost_nav_chats),
            selected = selectedTab == "chats",
            onClick = { onTabSelected("chats") }
        )
        BottomNavItem(
            icon = Icons.Filled.DonutLarge,
            label = stringResource(R.string.goodpost_nav_updates),
            selected = selectedTab == "updates",
            onClick = { onTabSelected("updates") }
        )
        BottomNavItem(
            icon = Icons.Filled.Groups,
            label = stringResource(R.string.goodpost_nav_communities),
            selected = selectedTab == "communities",
            onClick = { onTabSelected("communities") }
        )
        BottomNavItem(
            icon = Icons.Filled.Call,
            label = stringResource(R.string.goodpost_nav_calls),
            selected = selectedTab == "calls",
            onClick = { onTabSelected("calls") }
        )
    }
}

@Composable
private fun BottomNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (selected) Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Wa.Accent.copy(alpha = 0.2f))
                        .padding(horizontal = 18.dp, vertical = 4.dp)
                    else Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) Wa.Accent else Wa.TextDim,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = if (selected) Wa.Text else Wa.TextDim,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
