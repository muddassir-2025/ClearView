package com.muddassir.clearview.goodpost.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.FeedEntry
import com.muddassir.clearview.goodpost.GoodPostScreen
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostAttachment
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostMedia
import com.muddassir.clearview.goodpost.data.GoodPostPost
import com.muddassir.clearview.goodpost.data.GoodPostUploadState
import com.muddassir.clearview.goodpost.data.parseIsoMillis
import com.muddassir.clearview.goodpost.data.readGoodPostAttachment
import com.muddassir.clearview.goodpost.withDateSeparators

/**
 * A channel's feed (§8, §9, §10).
 *
 * The posts are the whole screen: a scrolling column of rounded dark containers
 * under a bar that names the channel, with small centred date pills breaking the
 * column at each day boundary. That is what a channel looks like when it is
 * opened, and it is deliberately NOT a card feed — a post is a message from the
 * channel, and the only thing attached to it is when it was sent.
 *
 * Nothing counts anything here either: no view total, no reaction row, no reply
 * button (§9). What a post carries is its text, up to one image or video, an
 * optional link, and a timestamp.
 *
 * [editable] is true only when an administrator opened it from their own
 * dashboard, which is what puts the compose button and the post menu on screen.
 */
@Composable
internal fun GoodPostFeed(
    state: GoodPostUiState,
    channelId: String,
    editable: Boolean,
    viewModel: GoodPostViewModel
) {
    val context = LocalContext.current
    val channel = state.channel
    val entries = remember(state.posts) { withDateSeparators(state.posts) }

    Box(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        // WhatsApp dark doodle chat wallpaper background (Screenshot 1 & Screenshot 4)
        Image(
            painter = painterResource(id = R.drawable.goodpost_chat_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 1.0f
        )

        Column(modifier = Modifier.fillMaxSize()) {
            if (state.postSelectionActive) {
                PostSelectionBar(
                    state = state,
                    channelName = channel?.name.orEmpty(),
                    editable = editable,
                    viewModel = viewModel
                )
            } else {
            WaTopBar(
                title = channel?.name ?: stringResource(R.string.goodpost_channel),
                subtitle = channel?.name?.let { stringResource(R.string.goodpost_public_channel) },
                navigation = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 2.dp)
                    ) {
                        WaIconAction(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            description = stringResource(R.string.goodpost_back),
                            onClick = { viewModel.back() }
                        )
                        if (channel != null) {
                            WaAvatar(
                                name = channel.name,
                                size = 36.dp,
                                url = channel.iconUrl,
                                modifier = Modifier.clickable {
                                    viewModel.open(GoodPostScreen.ChannelInfo(channelId))
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                },
                // Tapping the channel's name opens its information page (§11).
                onTitleClick = { viewModel.open(GoodPostScreen.ChannelInfo(channelId)) },
                actions = {
                    channel?.let { known ->
                        WaIconAction(
                            icon = Icons.Filled.Link,
                            description = stringResource(R.string.goodpost_share),
                            onClick = { shareChannel(context, known.name, known.shareLink) }
                        )
                    }
                    WaOverflowMenu(
                        items = buildList {
                            add(
                                WaMenuItem(
                                    label = stringResource(R.string.goodpost_channel_info),
                                    onClick = { viewModel.open(GoodPostScreen.ChannelInfo(channelId)) }
                                )
                            )
                            channel?.let { known ->
                                add(
                                    WaMenuItem(
                                        label = stringResource(R.string.goodpost_share),
                                        onClick = { shareChannel(context, known.name, known.shareLink) }
                                    )
                                )
                            }
                        }
                    )
                }
            )
            }

            if (state.postsStale) WaStaleBanner(onRetry = { viewModel.loadPosts(channelId) })

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 6.dp,
                        // Room for the compose button if editable
                        bottom = if (editable) 96.dp else 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.postsError?.let { code ->
                        item(key = "error") { WaErrorNotice(code) }
                    }

                    items(entries, key = { it.key }) { entry ->
                        when (entry) {
                            is FeedEntry.Separator -> WaDatePill(entry.label)
                            is FeedEntry.Post -> PostItem(
                                channelName = channel?.name.orEmpty(),
                                post = entry.post,
                                selected = state.selectedPostIds.contains(entry.post.id),
                                selectionActive = state.postSelectionActive,
                                onClick = {
                                    // In selection mode a tap adds to the
                                    // selection; otherwise a post has no tap
                                    // action at all. Nothing a post does is
                                    // hidden behind a tap (§5).
                                    if (state.postSelectionActive) {
                                        viewModel.togglePostSelected(entry.post.id)
                                    }
                                },
                                onLongClick = { viewModel.togglePostSelected(entry.post.id) }
                            )
                        }
                    }

                    if (state.posts.isEmpty() && state.postsLoading) {
                        item(key = "loading") { CenteredProgress(Modifier.height(200.dp)) }
                    }

                    if (state.posts.isEmpty() && !state.postsLoading && state.postsError == null) {
                        item(key = "empty") {
                            // This screen is ONE channel's history, so it says so.
                            // The string it used to show told the reader that posts
                            // from the channels they follow appear here, and to go
                            // find some in Discover — following, and Discover, are
                            // both things this product removed (§1).
                            WaEmptyState(
                                title = stringResource(R.string.goodpost_empty_feed_title),
                                note = stringResource(R.string.goodpost_empty_channel_posts)
                            )
                        }
                    }

                    if (state.postsCursor != null) {
                        item(key = "more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.postsLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Wa.Accent
                                    )
                                } else {
                                    WaTextAction(
                                        text = stringResource(R.string.goodpost_more),
                                        onClick = viewModel::loadMorePosts
                                    )
                                }
                            }
                        }
                    }
                }

            }

            if (editable) {
                ChannelInputBar(state = state, viewModel = viewModel)
            }
        }
    }
}

/**
 * The bar a post selection replaces the channel title with (§5).
 *
 * This is what a long press is for: the actions that apply to the posts picked,
 * in the place the channel's own controls were. Copy and Forward work on any
 * number of posts; Delete appears only where the account may publish, and asks
 * for nothing further — a removed post is soft on the server, so it is the one
 * destructive action here that stays reversible.
 */
@Composable
private fun PostSelectionBar(
    state: GoodPostUiState,
    channelName: String,
    editable: Boolean,
    viewModel: GoodPostViewModel
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val selected = state.posts.filter { state.selectedPostIds.contains(it.id) }

    WaSelectionBar(
        count = state.selectedPostIds.size,
        onClose = viewModel::clearPostSelection,
        actions = {
            if (editable && selected.size == 1) {
                WaIconAction(
                    icon = Icons.Filled.Edit,
                    description = stringResource(R.string.goodpost_edit),
                    onClick = {
                        viewModel.startEditPost(selected.first())
                        viewModel.clearPostSelection()
                    }
                )
            }
            WaIconAction(
                icon = Icons.Filled.ContentCopy,
                description = stringResource(R.string.goodpost_copy),
                onClick = {
                    clipboard.setText(AnnotatedString(selected.joinToString("\n\n") { it.copyText() }))
                    viewModel.clearPostSelection()
                }
            )
            if (editable) {
                WaIconAction(
                    icon = Icons.Filled.Delete,
                    description = stringResource(R.string.goodpost_delete),
                    tint = Wa.Danger,
                    onClick = viewModel::deleteSelectedPosts
                )
            }
        }
    )
}

/**
 * A post's text, as something worth copying.
 *
 * The body plus its link, because those are the two things a post carries that
 * can be put somewhere else: media is a URL that expires, so copying one would
 * hand the recipient a link that stops working.
 */
private fun GoodPostPost.copyText(): String = buildString {
    if (!body.isNullOrBlank()) append(body)
    if (!linkUrl.isNullOrBlank()) {
        if (isNotEmpty()) append("\n\n")
        append(linkUrl)
    }
}

/**
 * One post, as a WhatsApp olive-green message bubble (§9, Screenshot 4).
 *
 * Two things changed here from the version with a row of icons under every
 * bubble. The icons went, because an action attached to every message is an
 * action attached to no message in particular — and the body is now a
 * `SelectionContainer`, so text can be selected with a long press and copied
 * the way text is copied everywhere else on the phone.
 */
@Composable
private fun PostItem(
    channelName: String,
    post: GoodPostPost,
    selected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current

    // The selection fill is on this wrapper rather than inside the bubble: the
    // bubble paints its own background over whatever it is given, so a highlight
    // passed inwards would be covered by the very container it is meant to mark.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Wa.Selected else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        WaPostContainer {
            post.media.forEach { asset ->
                if (asset.isImage) {
                    RemoteImage(
                        url = asset.url,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 420.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                } else if (asset.isVideo) {
                    VideoTile(asset = asset, onOpen = { openMedia(context, asset.url) })
                    Spacer(Modifier.height(4.dp))
                }
            }

            if (!post.body.isNullOrBlank()) {
                SelectionContainer {
                    Text(
                        text = post.body,
                        color = Wa.BubbleText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(
                            horizontal = 6.dp,
                            vertical = if (post.media.isNotEmpty()) 4.dp else 2.dp
                        )
                    )
                }
            }

            if (post.isLink) {
                if (!post.body.isNullOrBlank()) Spacer(Modifier.height(4.dp))
                LinkChip(
                    label = post.linkTitle?.takeIf { it.isNotBlank() } ?: post.linkUrl.orEmpty(),
                    url = post.linkUrl.orEmpty(),
                    onOpen = { openMedia(context, post.linkUrl) }
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // A post that has been edited says so, because a reader who saw
                // it before should not have to wonder whether they misremembered
                // it. There is no other furniture in this row: what a post can do
                // is reached by holding it (§5).
                if (post.editedAt != null) {
                    Text(
                        text = stringResource(R.string.goodpost_edited),
                        color = Wa.BubbleTime,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                WaTimeLabel(
                    text = waClock(parseIsoMillis(post.createdAt)),
                    color = Wa.BubbleTime
                )
            }
        }
    }
}

/**
 * An image from a signed URL.
 *
 * Decoded at the width it is drawn at rather than at its stored resolution, and
 * reused between scrolls from an LRU (§26). While it loads, the space is held at
 * a fixed height so the list does not jump when the bitmap arrives — a feed that
 * re-lays itself out under the reader's thumb is the single most obvious way to
 * make a screen feel slow.
 */
@Composable
private fun RemoteImage(url: String?, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf(GoodPostImages.peek(url)) }

    LaunchedEffect(url) {
        if (bitmap == null) bitmap = GoodPostImages.load(url, maxWidthPx = 720)
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(11.dp))
        )
    } else {
        WaMediaPlaceholder(
            modifier = modifier.aspectRatio(1.4f),
            icon = Icons.Filled.Link,
            label = if (url.isNullOrBlank()) {
                stringResource(R.string.goodpost_media_unavailable)
            } else null
        )
    }
}

/**
 * A video's preview (§9).
 *
 * A play button over the placeholder rather than an embedded player: the app has
 * one player already, it belongs to the Media tab, and wiring a second one into
 * a channel feed would be a player with its own bugs and its own controls to
 * keep in step. Tapping hands the URL to whatever the device already plays video
 * with, which is what "minimal and native" means here.
 */
@Composable
private fun VideoTile(asset: GoodPostMedia, onOpen: () -> Unit) {
    WaMediaPlaceholder(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(WaMediaShape)
            .clickable(enabled = !asset.url.isNullOrBlank(), onClick = onOpen),
        content = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Wa.Canvas),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.goodpost_play_video),
                    tint = Wa.Text,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    )

    Spacer(Modifier.height(6.dp))
    WaChip(text = waDescribeBytes(stringResource(R.string.goodpost_video), asset.byteSize))
}

/** A link post's tappable row (§9). */
@Composable
private fun LinkChip(label: String, url: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WaMediaShape)
            .background(Wa.Pressed)
            .clickable(enabled = url.isNotBlank(), onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Link, contentDescription = null, tint = Wa.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = Wa.Text,
            fontSize = 14.sp,
            maxLines = 2
        )
    }
}

/**
 * How many files one post may carry.
 *
 * Mirrors the server's own `MAX_POST_MEDIA` (4 by default), and is a client-side
 * courtesy rather than a rule: the server refuses a longer list with
 * `too_many_media`, which the composer words. Setting it here is what keeps the
 * picker from offering a selection that would be rejected at the end.
 */
private const val COMPOSER_MAX_ATTACHMENTS = 4

/**
 * The WhatsApp Channel input bar for publishing and editing updates (§21).
 *
 * Text, an optional link, and files attached to the post. A file is uploaded the
 * moment it is picked — see [GoodPostViewModel.attachMedia] — so by the time
 * "Post" is tapped there is nothing left to do but send the ids.
 *
 * Publishing is refused while a file is still uploading or has failed. That is
 * the honest behaviour rather than a convenience: the alternative is a post that
 * appears to have been published with a picture, and does not.
 */
@Composable
internal fun ChannelInputBar(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    val context = LocalContext.current
    val editing = state.editingPostId != null

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(COMPOSER_MAX_ATTACHMENTS)
    ) { uris ->
        uris.forEach { uri ->
            val attachment = readGoodPostAttachment(context, uri)
            if (attachment == null) {
                viewModel.reportUnsupportedMedia()
            } else {
                viewModel.attachMedia(attachment)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Wa.Canvas)
    ) {
        // If editing an existing post, show an "Editing update" header strip
        if (editing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Wa.Bar)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    tint = Wa.StampRecent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.goodpost_edit_post),
                    color = Wa.StampRecent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.goodpost_cancel),
                    tint = Wa.TextDim,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = viewModel::cancelCompose)
                )
            }
        }

        // If media attached, show thumbnail preview strip directly above the input bar
        if (state.composerAttachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Wa.Bar)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.composerAttachments, key = { it.uri }) { attachment ->
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Wa.Pressed),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (attachment.kind == "video") Icons.Filled.PlayArrow else Icons.Filled.Image,
                            contentDescription = null,
                            tint = Wa.TextDim,
                            modifier = Modifier.size(22.dp)
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Wa.Canvas)
                                .clickable { viewModel.removeMedia(attachment.uri) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = null,
                                tint = Wa.TextDim,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        // Input pill row + Send button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Wa.Bar)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = state.composerBody,
                    onValueChange = viewModel::onComposerBodyChange,
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(
                        color = Wa.Text,
                        fontSize = 16.sp
                    ),
                    cursorBrush = SolidColor(Wa.StampRecent),
                    maxLines = 5,
                    decorationBox = { innerTextField ->
                        if (state.composerBody.isEmpty()) {
                            Text(
                                text = if (editing) stringResource(R.string.goodpost_edit_post)
                                else stringResource(R.string.goodpost_write_update),
                                color = Wa.TextDim,
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                )

                if (!editing && state.composerMediaAvailable) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.goodpost_add_media),
                        tint = Wa.TextDim,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable(
                                enabled = !state.composerBusy && state.composerAttachments.size < COMPOSER_MAX_ATTACHMENTS,
                                onClick = {
                                    picker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                }
                            )
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            val canSubmit = (state.composerBody.isNotBlank() || state.composerAttachments.isNotEmpty()) &&
                !state.composerBusy &&
                (editing || state.composerAttachments.all { it.mediaId != null })

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canSubmit) Wa.StampRecent else Wa.Bar)
                    .clickable(enabled = canSubmit, onClick = viewModel::publish),
                contentAlignment = Alignment.Center
            ) {
                if (state.composerBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Wa.OnAccent
                    )
                } else if (editing) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.goodpost_save),
                        tint = if (canSubmit) Wa.OnAccent else Wa.TextDim,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.goodpost_publish),
                        tint = if (canSubmit) Wa.OnAccent else Wa.TextDim,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/** Hand a channel's link to whatever the device shares with (§11). */
internal fun shareChannel(context: Context, name: String, link: String) {
    if (link.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
        putExtra(Intent.EXTRA_SUBJECT, name)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** Hand a URL to whatever the device opens it with. */
private fun openMedia(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        // No app can handle it. Nothing to say: the user tapped and the device
        // has no opinion, and a toast about a missing handler helps nobody.
    }
}
