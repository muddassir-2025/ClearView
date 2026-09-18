package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostMediaItem
import com.muddassir.clearview.goodpost.data.parseIsoMillis

/**
 * A channel's information page (§11–§14).
 *
 * The structure is the one a channel profile has: a large circular picture with
 * the name under it, one neutral label saying what this is, three actions, the
 * description with the rest of it behind "Read more", when the channel started,
 * a strip of what it has published, and its notification switch.
 *
 * The label is "Public channel" rather than a follower count (§11), and that is
 * not a placeholder-to-be. Good Post has no accounts, so it has no follower
 * accounts to count — a number there would be invented, and an invented number
 * under a channel's name is the single most dishonest thing this screen could
 * do.
 */
@Composable
internal fun GoodPostChannelInfo(
    state: GoodPostUiState,
    channelId: String,
    viewModel: GoodPostViewModel
) {
    val context = LocalContext.current
    val channel = state.channel

    LaunchedEffect(channelId) { viewModel.loadMedia(channelId) }

    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        WaTopBar(
            title = "",
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    onClick = { viewModel.back() }
                )
            },
            actions = {
                channel?.let { known ->
                    WaOverflowMenu(
                        items = buildList {
                            add(
                                WaMenuItem(
                                    label = stringResource(R.string.goodpost_share),
                                    onClick = { shareChannel(context, known.name, known.shareLink) }
                                )
                            )
                            // Only where the account may actually change it.
                            // Deleting is deliberately NOT here: an irreversible
                            // action does not belong a slip away from Share, and
                            // the list's selection actions are where it lives.
                            if (state.canManage(known.id)) {
                                add(
                                    WaMenuItem(
                                        label = stringResource(R.string.goodpost_edit_channel),
                                        onClick = { viewModel.startEditChannel(known) }
                                    )
                                )
                            }
                        }
                    )
                }
            }
        )

        if (channel == null) {
            CenteredProgress()
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(28.dp))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                WaAvatar(name = channel.name, size = 116.dp, url = channel.iconUrl)
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = channel.name,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                color = Wa.Text,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            // §11: "Public channel" label, and the one number that belongs
            // beside it (§9).
            //
            // A follower count is not the social metric §11 refused: that was a
            // number invented because the space under a name looked empty. This
            // one is a count of follows the server can point at, and it is the
            // only measure on this page that a reader can act on — it is what
            // tells them whether anybody else is here.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.goodpost_public_channel),
                    color = Wa.TextDim,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    text = " · ",
                    color = Wa.TextDim,
                    fontSize = 14.sp
                )
                Text(
                    text = if (channel.followerCount > 0) {
                        pluralStringResource(
                            R.plurals.goodpost_follower_count,
                            channel.followerCount,
                            waCompactCount(channel.followerCount)
                        )
                    } else {
                        stringResource(R.string.goodpost_no_followers)
                    },
                    color = Wa.TextDim,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // §9: follow and unfollow, from the channel's own page.
                //
                // This was a Forward arrow, which was the wrong thing twice
                // over: it duplicated the Share control beside it, and it put
                // the one action a reader actually takes on a channel — keeping
                // it — behind a trip back to Explore. Following from here is
                // also the only place it can be undone without hunting for the
                // channel again.
                val following = channel.id in state.followedIds
                ChannelAction(
                    icon = if (following) Icons.Filled.Check else Icons.Filled.Add,
                    label = if (following) stringResource(R.string.goodpost_following)
                    else stringResource(R.string.goodpost_follow),
                    tint = if (following) Wa.Accent else Wa.Text,
                    busy = state.followBusyId == channel.id,
                    onClick = { viewModel.toggleFollow(channel.id) }
                )
                ChannelAction(
                    icon = Icons.Filled.Share,
                    label = stringResource(R.string.goodpost_share),
                    onClick = { shareChannel(context, channel.name, channel.shareLink) }
                )
                // §9: searches THIS channel's posts, not the channel list.
                //
                // It used to open Explore pre-filled with the channel's name,
                // which searched the catalogue for a channel the reader was
                // already looking at — a search screen whose answer is the thing
                // you arrived from. "Search" on a channel means what the channel
                // said.
                ChannelAction(
                    icon = Icons.Filled.Search,
                    label = stringResource(R.string.goodpost_search),
                    onClick = { viewModel.openChannelSearch(channel.id) }
                )
            }

            Spacer(Modifier.height(28.dp))

            ChannelDescription(
                description = channel.description,
                expanded = state.descriptionExpanded,
                onToggle = viewModel::toggleDescription
            )

            Spacer(Modifier.height(16.dp))

            val createdAt = parseIsoMillis(channel.createdAt)
            if (createdAt != null) {
                Text(
                    text = stringResource(R.string.goodpost_created_on, waShortDate(createdAt)),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = Wa.TextDim,
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(24.dp))

            MediaAndLinks(
                items = state.media,
                loading = state.mediaLoading,
                onOpen = { item -> openMediaUrl(context, item.url) }
            )

            Spacer(Modifier.height(24.dp))

            NotificationsRow()

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * One of the channel actions: an icon in a round control with its label
 * underneath (§11, Screenshot 3).
 *
 * [busy] is the follow control's own state while the server is being asked: the
 * control stays where it is and spins, rather than disappearing, so the row does
 * not reflow under the reader's thumb for the length of a round trip.
 */
@Composable
private fun ChannelAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Wa.Text,
    busy: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Wa.Bar)
                .clickable(enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Wa.Accent
                )
            } else {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = if (busy) Wa.TextDim else tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * The channel's description, with the rest of it behind "Read more" (§12).
 *
 * Collapsed by line count rather than by character count: what has to fit is the
 * screen, and a character budget that looks right on one device is two lines on
 * another and five on a third.
 */
@Composable
private fun ChannelDescription(description: String?, expanded: Boolean, onToggle: () -> Unit) {
    val text = description?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.goodpost_no_description)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            text = stringResource(R.string.goodpost_channel_description),
            color = Wa.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = text,
            color = Wa.TextDim,
            fontSize = 14.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis
        )
        if (!expanded && description != null && description.length > 80) {
            Text(
                text = stringResource(R.string.goodpost_read_more),
                color = Wa.StampRecent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(top = 4.dp)
            )
        }
    }
}

/**
 * Recent images and videos (§13, Screenshot 3).
 */
@Composable
private fun MediaAndLinks(
    items: List<GoodPostMediaItem>,
    loading: Boolean,
    onOpen: (GoodPostMediaItem) -> Unit
) {
    if (items.isEmpty() && !loading) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.goodpost_media_and_links),
                color = Wa.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (items.isNotEmpty()) {
                Text(
                    text = "${items.size} >",
                    color = Wa.TextDim,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.id }) { item ->
                MediaThumbnail(item = item, onClick = { onOpen(item) })
            }
        }
    }
}

/** One square thumbnail in the strip. */
@Composable
private fun MediaThumbnail(item: GoodPostMediaItem, onClick: () -> Unit) {
    var bitmap by remember(item.url) { mutableStateOf(GoodPostImages.peek(item.url)) }

    LaunchedEffect(item.url) {
        if (bitmap == null) bitmap = GoodPostImages.load(item.url, maxWidthPx = 240)
    }

    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Wa.Pressed)
            .clickable(enabled = !item.url.isNullOrBlank(), onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (item.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * Where a channel's notification setting belongs (§14).
 *
 * A statement rather than a switch: this app has no push infrastructure, so a
 * toggle would record a preference nothing can act on. Saying so is the honest
 * version of the same section, and it becomes the switch when delivery exists.
 */
@Composable
private fun NotificationsRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Notifications,
            contentDescription = null,
            tint = Wa.TextDim,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.goodpost_notifications),
                color = Wa.Text,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.goodpost_notifications_note),
                color = Wa.TextDim,
                fontSize = 13.sp
            )
        }
    }
}

/** Hand a URL to whatever the device opens it with. */
private fun openMediaUrl(context: android.content.Context, url: String?) {
    if (url.isNullOrBlank()) return
    try {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            )
        )
    } catch (e: Exception) {
        // Nothing on the device can open it. Silent on purpose: the tap simply
        // has no handler, which is not worth an interruption.
    }
}
