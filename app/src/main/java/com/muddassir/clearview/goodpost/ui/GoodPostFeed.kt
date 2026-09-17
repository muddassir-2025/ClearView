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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
            alpha = 0.55f
        )

        Column(modifier = Modifier.fillMaxSize()) {
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
                                post = entry.post,
                                channelName = channel?.name ?: "",
                                editable = editable,
                                onEdit = { viewModel.startEditPost(entry.post) },
                                onDelete = { viewModel.deletePost(entry.post) }
                            )
                        }
                    }

                    if (state.posts.isEmpty() && state.postsLoading) {
                        item(key = "loading") { CenteredProgress(Modifier.height(200.dp)) }
                    }

                    if (state.posts.isEmpty() && !state.postsLoading && state.postsError == null) {
                        item(key = "empty") {
                            WaEmptyState(
                                title = stringResource(R.string.goodpost_empty_feed_title),
                                note = stringResource(R.string.goodpost_empty_feed_note)
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

                // The compose action, only where an administrator may publish
                if (editable) {
                    WaFab(
                        icon = Icons.Filled.Edit,
                        description = stringResource(R.string.goodpost_compose),
                        onClick = viewModel::startCompose,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp)
                    )
                }
            }
        }
    }
}

/** One post, as a WhatsApp olive-green message bubble (§9, Screenshot 4). */
@Composable
private fun PostItem(
    post: GoodPostPost,
    channelName: String,
    editable: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        WaPostContainer {
            post.media.forEach { asset ->
                if (asset.isImage) {
                    RemoteImage(
                        url = asset.url,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 400.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                } else if (asset.isVideo) {
                    VideoTile(asset = asset, onOpen = { openMedia(context, asset.url) })
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (!post.body.isNullOrBlank()) {
                Text(
                    text = post.body,
                    color = Wa.BubbleText,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            if (post.isLink) {
                if (!post.body.isNullOrBlank()) Spacer(Modifier.height(6.dp))
                LinkChip(
                    label = post.linkTitle?.takeIf { it.isNotBlank() } ?: post.linkUrl.orEmpty(),
                    url = post.linkUrl.orEmpty(),
                    onOpen = { openMedia(context, post.linkUrl) }
                )
            }

            Spacer(Modifier.height(3.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (editable) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.goodpost_edit_post),
                        tint = Wa.TextDim,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(onClick = onEdit)
                    )
                    Spacer(Modifier.width(14.dp))
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.goodpost_delete_post),
                        tint = Wa.TextDim,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(onClick = onDelete)
                    )
                }
                Spacer(Modifier.weight(1f))
                WaTimeLabel(
                    text = waClock(parseIsoMillis(post.createdAt)),
                    color = Wa.BubbleTime
                )
            }
        }

        // WhatsApp quick forward action underneath each bubble on the left (Screenshot 4)
        Box(
            modifier = Modifier
                .padding(top = 4.dp, start = 4.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Wa.ForwardBg)
                .clickable {
                    val shareText = buildString {
                        if (!post.body.isNullOrBlank()) append(post.body)
                        if (!post.linkUrl.isNullOrBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append(post.linkUrl)
                        }
                    }
                    shareChannel(context, channelName, shareText)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Forward,
                contentDescription = stringResource(R.string.goodpost_forward),
                tint = Wa.TextDim,
                modifier = Modifier.size(16.dp)
            )
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
            modifier = modifier.clip(WaMediaShape)
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
 * The composer (§21).
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
internal fun ComposerDialog(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    val context = LocalContext.current
    val editing = state.editingPostId != null

    /**
     * The system photo picker, with the app's own fallback for older devices.
     *
     * `PickMultipleVisualMedia` is used rather than a raw `ACTION_OPEN_DOCUMENT`
     * because it hands back a URI the app may read without a storage permission,
     * and it degrades to the document picker where the photo picker is absent —
     * one launcher, no permission prompt, no `READ_MEDIA_*` request.
     */
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(COMPOSER_MAX_ATTACHMENTS)
    ) { uris ->
        uris.forEach { uri ->
            // Metadata is read here, where the picker's grant on the URI is
            // valid. A file this app will not send — a document, an SVG, one it
            // cannot size — is refused with a sentence instead of a round trip.
            val attachment = readGoodPostAttachment(context, uri)
            if (attachment == null) {
                viewModel.reportUnsupportedMedia()
            } else {
                viewModel.attachMedia(attachment)
            }
        }
    }

    Dialog(onDismissRequest = viewModel::cancelCompose) {
        Column(
            modifier = Modifier
                .background(Wa.Bar, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(
                    if (editing) R.string.goodpost_edit_post else R.string.goodpost_create_post
                ),
                color = Wa.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            WaField(
                value = state.composerBody,
                onValueChange = viewModel::onComposerBodyChange,
                label = stringResource(R.string.goodpost_post_text),
                placeholder = stringResource(R.string.goodpost_post_text_hint),
                enabled = !state.composerBusy,
                singleLine = false,
                minHeight = 110.dp
            )

            Spacer(Modifier.height(12.dp))

            WaField(
                value = state.composerLink,
                onValueChange = viewModel::onComposerLinkChange,
                label = stringResource(R.string.goodpost_post_link),
                placeholder = stringResource(R.string.goodpost_post_link_hint),
                enabled = !state.composerBusy
            )

            Spacer(Modifier.height(12.dp))

            if (editing) {
                // No picker while editing: the server has no route that swaps a
                // published post's files, and offering one that silently did
                // nothing would be worse than not offering it.
                Text(
                    text = stringResource(R.string.goodpost_media_edit_note),
                    color = Wa.TextDim,
                    fontSize = 12.sp
                )
            } else if (state.composerMediaAvailable) {
                ComposerAttachments(
                    attachments = state.composerAttachments,
                    busy = state.composerBusy,
                    full = state.composerAttachments.size >= COMPOSER_MAX_ATTACHMENTS,
                    onPick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    onRemove = viewModel::removeMedia
                )
            } else {
                // `media_unavailable` is an answer about the DEPLOYMENT (§22), so
                // the picker is withdrawn and the reason is stated rather than
                // left as a button that fails every time.
                Text(
                    text = stringResource(R.string.goodpost_error_media_unavailable),
                    color = Wa.TextDim,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                WaTextAction(
                    text = stringResource(R.string.goodpost_cancel),
                    enabled = !state.composerBusy,
                    onClick = viewModel::cancelCompose
                )
                Spacer(Modifier.weight(1f))
                Box(modifier = Modifier.width(150.dp)) {
                    WaPrimaryButton(
                        text = stringResource(
                            if (state.editingPostId == null) R.string.goodpost_publish
                            else R.string.goodpost_save
                        ),
                        enabled = !state.composerBusy &&
                            (editing || state.composerAttachments.all { it.mediaId != null }),
                        busy = state.composerBusy,
                        onClick = viewModel::publish
                    )
                }
            }
        }
    }
}

/**
 * The files attached to the post being written (§21).
 *
 * A horizontal strip of tiles, each with the state of its own upload. The
 * attachment's picture is NOT previewed: decoding a picked file means a second
 * image pipeline (another decode path, another cache, another place to get the
 * sample size wrong), and the tile already says everything the user needs — what
 * kind of file it is, and whether it has got there yet.
 */
@Composable
private fun ComposerAttachments(
    attachments: List<GoodPostAttachment>,
    busy: Boolean,
    full: Boolean,
    onPick: () -> Unit,
    onRemove: (String) -> Unit
) {
    if (attachments.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(attachments, key = { it.uri }) { attachment ->
                AttachmentTile(
                    attachment = attachment,
                    enabled = !busy,
                    onRemove = { onRemove(attachment.uri) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WaMediaShape)
            .clickable(enabled = !busy && !full, onClick = onPick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.AddPhotoAlternate,
            contentDescription = null,
            tint = if (busy || full) Wa.TextDim else Wa.Accent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(
                    if (full) R.string.goodpost_media_limit else R.string.goodpost_add_media
                ),
                color = if (busy || full) Wa.TextDim else Wa.Text,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.goodpost_add_media_note),
                color = Wa.TextDim,
                fontSize = 12.sp
            )
        }
    }
}

/** One attached file, with the state of its own upload. */
@Composable
private fun AttachmentTile(
    attachment: GoodPostAttachment,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(WaMediaShape)
                .background(Wa.Pressed),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (attachment.kind) {
                    "image" -> Icons.Filled.Image
                    "video" -> Icons.Filled.PlayArrow
                    else -> Icons.Filled.MusicNote
                },
                contentDescription = stringResource(
                    when (attachment.kind) {
                        "image" -> R.string.goodpost_attachment_image
                        "video" -> R.string.goodpost_attachment_video
                        else -> R.string.goodpost_attachment_audio
                    }
                ),
                tint = Wa.TextDim,
                modifier = Modifier.size(26.dp)
            )

            // The remove control is a tap on the tile's corner rather than a
            // long press: a file that failed to upload has to be removable in
            // one obvious gesture, or the post can never be published.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Wa.Canvas)
                    .clickable(enabled = enabled, onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(
                        R.string.goodpost_remove_media,
                        stringResource(R.string.goodpost_attachment_image)
                    ),
                    tint = Wa.TextDim,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = when (attachment.state) {
                is GoodPostUploadState.Ready -> stringResource(R.string.goodpost_attachment_ready)
                is GoodPostUploadState.Failed -> stringResource(R.string.goodpost_attachment_failed)
                GoodPostUploadState.Uploading -> stringResource(R.string.goodpost_attachment_uploading)
            },
            color = when (attachment.state) {
                is GoodPostUploadState.Ready -> Wa.Accent
                is GoodPostUploadState.Failed -> Wa.TextDim
                GoodPostUploadState.Uploading -> Wa.TextDim
            },
            fontSize = 11.sp
        )
    }
}

/**
 * Share a channel (§6).
 *
 * `EXTRA_TEXT` is the link ALONE, not the name plus the link: the receiving app
 * decides what to do with the body, and prose mixed with a URI is what turns a
 * working link into a dead string the moment it is pasted. The name travels as
 * the subject, which is what a mail client actually uses.
 */
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
