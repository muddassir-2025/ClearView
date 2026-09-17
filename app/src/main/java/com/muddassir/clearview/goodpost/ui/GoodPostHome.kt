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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * The Good Post home screen: the channel list (§3, §4, §15).
 *
 * The hierarchy is the one a channel list uses: a bold page title with search
 * and an overflow menu, then a bold "Channels" section heading with an Explore
 * action beside it, then compact rows, and finally — at the very bottom, out of
 * the way — the way in for whoever runs a channel.
 *
 * Nothing here counts anything. A row says who a channel is, what it last said
 * and when; there is no follower number, no unread count and no reaction total,
 * because Good Post is a broadcast system and those numbers do not exist in it
 * (§1, §5).
 */
@Composable
internal fun GoodPostHome(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            WaTopBar(
                title = stringResource(R.string.goodpost_tab),
                actions = {
                    WaIconAction(
                        icon = Icons.Filled.Search,
                        description = stringResource(R.string.goodpost_search),
                        onClick = { viewModel.openExplore() }
                    )
                    WaOverflowMenu(
                        items = listOf(
                            // No "sign out": a reader has nothing to sign out of.
                            WaMenuItem(
                                label = stringResource(R.string.goodpost_create_channel),
                                onClick = viewModel::openAdmin
                            ),
                            WaMenuItem(
                                label = stringResource(R.string.goodpost_refresh),
                                onClick = viewModel::refreshChannels
                            )
                        )
                    )
                }
            )

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

                items(state.channels, key = { it.id }) { channel ->
                    ChannelRow(
                        channel = channel,
                        hasUnread = false,
                        muted = state.hasMuted(channel.id),
                        onClick = { viewModel.openChannel(channel.id) }
                    )
                }

                if (state.channels.isEmpty() && state.channelsLoading) {
                    item(key = "loading") { CenteredProgress(Modifier.height(160.dp)) }
                }

                if (state.channels.isEmpty() && !state.channelsLoading &&
                    state.channelsError == null
                ) {
                    item(key = "empty") {
                        WaEmptyState(
                            title = stringResource(R.string.goodpost_empty_channels_title),
                            note = stringResource(R.string.goodpost_empty_channels_note),
                            actionLabel = stringResource(R.string.goodpost_explore),
                            onAction = { viewModel.openExplore() }
                        )
                    }
                }

                // §15: a small utility row at the very bottom, never above the
                // list. Public readers come here to read; only the person who
                // runs a channel comes here to make one.
                item(key = "create") {
                    CreateChannelFooter(onClick = viewModel::openAdmin)
                }
            }
        }
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
 * posted, where there is no preview to show and the description is the one
 * useful thing available.
 */
@Composable
private fun ChannelRow(
    channel: GoodPostChannel,
    hasUnread: Boolean,
    muted: Boolean,
    onClick: () -> Unit
) {
    val posted = channel.lastPostAt != null
    val preview = when {
        posted && !channel.lastPostPreview.isNullOrBlank() -> channel.lastPostPreview
        // A post with no text is still a post — a photo, a video, a link. Saying
        // what it is beats an empty line, and it is what the small icon beside
        // it cannot say on its own.
        posted -> stringResource(mediaLabel(channel.lastPostType))
        else -> channel.description?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.goodpost_no_description)
    }

    val at = parseIsoMillis(channel.lastPostAt)

    WaChannelRow(
        title = channel.name,
        preview = preview,
        timestamp = if (posted) waListStamp(at) else null,
        timestampRecent = waStampIsRecent(at),
        onClick = onClick,
        avatar = { WaAvatar(name = channel.name, size = 49.dp, url = channel.iconUrl) },
        previewIcon = mediaIcon(channel.lastPostType),
        trailing = {
            if (hasUnread) WaUnreadDot()
            if (muted) {
                Icon(
                    Icons.Filled.NotificationsOff,
                    contentDescription = stringResource(R.string.goodpost_muted),
                    tint = Wa.TextDim,
                    modifier = Modifier.size(15.dp)
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
    "audio" -> R.string.goodpost_posted_audio
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
