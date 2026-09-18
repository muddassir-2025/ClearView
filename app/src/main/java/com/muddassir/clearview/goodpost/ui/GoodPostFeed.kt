package com.muddassir.clearview.goodpost.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
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
import com.muddassir.clearview.goodpost.data.GoodPostDownloads
import com.muddassir.clearview.goodpost.data.GoodPostFormat
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostMedia
import com.muddassir.clearview.goodpost.data.GoodPostPost
import com.muddassir.clearview.goodpost.data.GoodPostReaction
import com.muddassir.clearview.goodpost.data.GoodPostUploadState
import com.muddassir.clearview.goodpost.data.GoodPostVideoCache
import com.muddassir.clearview.goodpost.data.applyGoodPostFormat
import com.muddassir.clearview.goodpost.data.parseIsoMillis
import com.muddassir.clearview.goodpost.data.readGoodPostAttachment
import com.muddassir.clearview.goodpost.withDateSeparators
import kotlinx.coroutines.launch

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

    // **Oldest at the top, newest at the bottom** — the order a conversation
    // reads in, and the order WhatsApp Channels uses.
    //
    // The server pages newest-first, which is right for the wire: a cursor walks
    // backwards through history, and the first page is the page anybody wants
    // first. The SCREEN is the other way round, and the reversal lives here
    // rather than in the API because it is a presentation decision — a second
    // client could reasonably want the feed inverted, and it would still get one
    // consistent payload.
    //
    // Reversing also puts the date pills where they belong. They are drawn when
    // the day changes relative to the previous post in list order, so in this
    // order a pill sits above the first update of each day rather than below the
    // last — which is the only placement that reads as "everything under this
    // heading is that day".
    val entries = remember(state.posts) { withDateSeparators(state.posts.reversed()) }
    val listState = rememberLazyListState()

    // Which attachment the viewer is showing, by id — not by URL. A signed URL
    // expires, and a post re-read for a fresh one has to reach the player; an id
    // looked up in state on every recomposition does that, a copied URL does not.
    var viewerPostId by remember { mutableStateOf<String?>(null) }
    var viewerMediaId by remember { mutableStateOf<String?>(null) }
    // Where the viewer's playback starts. Carried here rather than inside the
    // viewer because the position belongs to the CARD that was playing, and the
    // viewer is told about it once, on the way in.
    var viewerFromMs by remember { mutableStateOf(0L) }

    val viewing = state.posts
        .firstOrNull { it.id == viewerPostId }
        ?.media
        ?.firstOrNull { it.id == viewerMediaId }

    // The id pair survives a configuration change; the resolved media does not
    // have to, because it is derived from state that also survives.
    LaunchedEffect(state.posts.size, viewing) {
        if (viewerMediaId != null && viewing == null && !state.postsLoading) {
            viewerPostId = null
            viewerMediaId = null
        }
    }

    // §24: warm what is about to be scrolled to. Only pictures — a video is
    // streamed on demand and cached by the player, so fetching one here would be
    // a download the reader did not ask for.
    LaunchedEffect(state.posts) {
        GoodPostImages.prefetch(
            urls = state.posts.takeLast(PREFETCH_POSTS)
                .flatMap { post -> post.media.filter { it.isImage }.mapNotNull { it.url } },
            maxWidthPx = 720,
            limit = PREFETCH_LIMIT
        )
    }

    // Open at the newest update rather than at the top of the history, and
    // follow a published update to the bottom while the reader is already there.
    //
    // The two effects are deliberately separate. The first is per channel and
    // happens once: arriving in a channel means arriving at what was just said.
    // The second is per growth and is conditional — a reader who has scrolled up
    // into old updates must NOT be yanked back to the bottom because somebody
    // published, which is the behaviour that makes a chat app feel hostile.
    var openedAt by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(channelId, entries.isNotEmpty()) {
        if (entries.isNotEmpty() && openedAt != channelId) {
            listState.scrollToItem(entries.lastIndex)
            openedAt = channelId
        }
    }

    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            ?: return@LaunchedEffect
        // Near the bottom, so the new update is what the reader is looking for.
        if (lastVisible >= entries.size - 3) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    // §19 Bug 1: the composer is the bottom row of this screen, and the screen is
    // hosted inside the app's Scaffold, which reserves room for the navigation bar
    // but knows nothing about the keyboard. `imePadding()` is what makes the IME's
    // height part of the layout instead of a panel drawn over the input bar —
    // without it, every keystroke in a channel's editor hid the row being typed
    // into. It is applied to the whole feed rather than to the bar alone so the
    // post list shrinks with it and the last post stays reachable by scrolling.
    Box(modifier = Modifier.fillMaxSize().background(Wa.Canvas).imePadding()) {
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
                            // §9: finding a message in a channel you are already
                            // reading. Here rather than only on the information
                            // page because this is where the reader is when they
                            // want it — WhatsApp puts it in the same menu.
                            add(
                                WaMenuItem(
                                    label = stringResource(R.string.goodpost_search_in_channel),
                                    onClick = { viewModel.openChannelSearch(channelId) }
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
                    state = listState,
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

                    // Older updates are at the TOP in this order: the list grows
                    // upwards into history, which is what scrolling up in a
                    // conversation is for. A "load more" at the bottom would sit
                    // under the newest update with nothing below it.
                    if (state.postsCursor != null) {
                        item(key = "older") {
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
                                        text = stringResource(R.string.goodpost_older),
                                        onClick = viewModel::loadMorePosts
                                    )
                                }
                            }
                        }
                    }

                    items(entries, key = { it.key }) { entry ->
                        when (entry) {
                            is FeedEntry.Separator -> WaDatePill(entry.label)
                            is FeedEntry.Post -> PostItem(
                                post = entry.post,
                                selected = state.selectedPostIds.contains(entry.post.id),
                                starred = entry.post.id in state.starredPostIds,
                                // §9: tapping a chip on the card does what the
                                // picker does for one post — sets this reader's
                                // emoji on it, or takes it off when it is
                                // already theirs.
                                onReact = { emoji -> viewModel.react(entry.post, emoji) },
                                reacting = entry.post.id in state.reactionBusyIds,
                                onClick = {
                                    // In selection mode a tap adds to the
                                    // selection; otherwise a post has no tap
                                    // action at all. Nothing a post does is
                                    // hidden behind a tap (§5).
                                    if (state.postSelectionActive) {
                                        viewModel.togglePostSelected(entry.post.id)
                                    }
                                },
                                onLongClick = { viewModel.togglePostSelected(entry.post.id) },
                                onOpenMedia = { asset ->
                                    // In selection mode the tap belongs to the
                                    // selection: opening a video while picking
                                    // posts to delete would lose the picks.
                                    if (!state.postSelectionActive) {
                                        viewerPostId = entry.post.id
                                        viewerMediaId = asset.id
                                        viewerFromMs = 0L
                                    }
                                },
                                onOpenMediaAt = { asset, from ->
                                    if (!state.postSelectionActive) {
                                        viewerPostId = entry.post.id
                                        viewerMediaId = asset.id
                                        // Resume where the card had got to.
                                        viewerFromMs = from
                                    }
                                },
                                onRefreshMedia = { viewModel.refreshPost(entry.post.id) }
                            )
                        }
                    }

                    if (state.posts.isEmpty() && state.postsLoading) {
                        item(key = "loading") { CenteredProgress(Modifier.height(200.dp)) }
                    }

                    if (state.posts.isEmpty() && !state.postsLoading && state.postsError == null) {
                        item(key = "empty") {
                            // This screen is ONE channel's history, so the empty
                            // state says that and nothing more — it does not offer
                            // to "find channels", because the reader is already
                            // inside one and the list is one tap back (§9).
                            WaEmptyState(
                                title = stringResource(R.string.goodpost_empty_feed_title),
                                note = stringResource(R.string.goodpost_empty_channel_posts)
                            )
                        }
                    }

                }

            }

            if (editable) {
                ChannelInputBar(state = state, channelId = channelId, viewModel = viewModel)
            }
        }

        if (viewing != null) {
            MediaViewer(
                kind = viewing.kind,
                url = viewing.url.orEmpty(),
                contentType = viewing.contentType,
                aspect = aspectOf(viewing.width, viewing.height),
                onClose = {
                    viewerPostId = null
                    viewerMediaId = null
                    viewerFromMs = 0L
                },
                // A fresh signature, through the one row this viewer is showing.
                onExpired = { viewerPostId?.let { viewModel.refreshPost(it) } },
                startAtMs = viewerFromMs,
                // A clip opened from a PLAYING card hands the playhead back on the
                // way out, so the feed does not jump backwards to wherever the card
                // was paused. A picture, or a card that was not playing, names no
                // key and nothing behind the viewer moves.
                resumeKey = viewing.url
                    ?.takeIf { viewing.isVideo }
                    ?.let { GoodPostVideoCache.cacheKeyFor(it) },
                // The star follows the POST into the viewer, so the same bookmark
                // can be set from the picture rather than only from the list it
                // was found in.
                starred = viewerPostId in state.starredPostIds,
                onToggleStar = {
                    state.posts.firstOrNull { it.id == viewerPostId }?.let(viewModel::toggleStar)
                },
                // Deleting the file from the viewer closes the viewer: the thing
                // it was showing is no longer on this device.
                onDeleted = {
                    viewerPostId = null
                    viewerMediaId = null
                    viewerFromMs = 0L
                }
            )
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
    editable: Boolean,
    viewModel: GoodPostViewModel
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val selected = state.posts.filter { state.selectedPostIds.contains(it.id) }
    // "Star" or "remove star" for the SELECTION, which of course can be mixed.
    // Every one of them starred is the only state that reads as "remove".
    val allStarred = selected.isNotEmpty() &&
        selected.all { it.id in state.starredPostIds }

    // Deleting is not a thing that happens on a tap any more (§5, §17).
    var confirmingDelete by remember { mutableStateOf(false) }

    // §9: the emoji picker is open. Local, like the confirmation above and for
    // the same reason — it is a step inside one gesture rather than a fact about
    // the selection, and it must not outlive the bar it belongs to. Leaving the
    // selection (or acting on it) discards this composable, and with it the flag.
    var pickingReaction by remember { mutableStateOf(false) }

    Box {
    WaSelectionBar(
        count = state.selectedPostIds.size,
        onClose = viewModel::clearPostSelection,
        actions = {
            // §9: one emoji for the WHOLE selection. Offered before the editor
            // because it is the one action here that needs no permission:
            // anybody who can read a channel can put a reaction on what it
            // published, and the star beside it is the same kind of thing.
            //
            // Held back until the server has named its emoji (§9): the app keeps
            // no copy of the list, so a control drawn before the answer arrives
            // could not say what it does.
            if (state.reactionEmoji.isNotEmpty()) {
                WaIconAction(
                    icon = Icons.Filled.AddReaction,
                    description = stringResource(R.string.goodpost_react),
                    onClick = { pickingReaction = !pickingReaction }
                )
            }
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
            // A star belongs to the READER, so it is offered to everyone —
            // including an account that may publish nothing. It is a bookmark
            // kept on this device (§9), and the list it writes to is the one the
            // channel's information page shows.
            WaIconAction(
                icon = if (allStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                description = stringResource(
                    if (allStarred) R.string.goodpost_unstar else R.string.goodpost_star
                ),
                onClick = viewModel::starSelectedPosts
            )
            // Share hands over the POST — its picture or clip when it has one,
            // and its words either way (§15). The channel's link is the fallback
            // rather than the default: a share of a photograph that arrives as a
            // web page is not the share the reader asked for.
            val link = state.channel?.shareLink.orEmpty()
            val channelName = state.channel?.name.orEmpty()
            if (link.isNotBlank() || selected.any { it.media.isNotEmpty() }) {
                WaIconAction(
                    icon = Icons.Filled.Share,
                    // "Share", not "Share link": what leaves depends on the post.
                    // A picture or a clip goes as the file, a text-only update goes
                    // as its words and the channel's link — and a label promising
                    // one of those over an action that does the other is how a
                    // reader ends up forwarding a URL and expecting a photo.
                    description = stringResource(R.string.goodpost_share_file),
                    onClick = {
                        val text = selected.joinToString("\n\n") { it.copyText() }
                        val files = selected
                            .flatMap { it.media }
                            .mapNotNull { asset ->
                                asset.url?.let { Shareable(it, asset.kind, asset.contentType) }
                            }
                            .take(SHARE_FILE_LIMIT)

                        if (files.isEmpty()) {
                            shareChannel(context, channelName, link, text)
                        } else {
                            scope.launch {
                                val uris = files.mapNotNull { file ->
                                    GoodPostDownloads.shareFile(
                                        context = context,
                                        url = file.url,
                                        kind = file.kind,
                                        contentType = file.contentType
                                    )
                                }
                                // A file that would not materialise is not a
                                // failure worth a sentence: the signature may have
                                // expired or the network may be gone, and the
                                // channel's link still shares the post. Saying
                                // nothing at all would be the only wrong answer.
                                if (uris.isEmpty()) {
                                    if (link.isNotBlank()) {
                                        shareChannel(context, channelName, link, text)
                                    } else {
                                        showToast(context, R.string.goodpost_save_failed)
                                    }
                                } else {
                                    shareFiles(
                                        context = context,
                                        uris = uris,
                                        type = mimeTypeForAll(files.map { it.kind to it.contentType }),
                                        text = text
                                    )
                                }
                            }
                        }
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
                    onClick = { confirmingDelete = true }
                )
            }
        }
    )

        // §9: the emoji row, on its own line above the action bar. It grows the
        // bar rather than floating over the feed, so the update the reader just
        // picked is never covered by the control that reacts to it.
        if (pickingReaction && state.reactionEmoji.isNotEmpty()) {
            ReactionPicker(
                emoji = state.reactionEmoji,
                onPick = { chosen ->
                    // Closing first: the send clears the selection, which removes
                    // this bar — and a flag written after that would be a write
                    // into a composable that no longer exists.
                    pickingReaction = false
                    viewModel.reactToSelectedPosts(chosen)
                },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        if (confirmingDelete) {
            val count = state.selectedPostIds.size
            WaDeleteDialog(
                title = if (count == 1) {
                    stringResource(R.string.goodpost_delete_post_title)
                } else {
                    stringResource(R.string.goodpost_delete_posts_title, count)
                },
                message = stringResource(
                    if (editable) {
                        R.string.goodpost_delete_post_note_admin
                    } else {
                        R.string.goodpost_delete_post_note
                    }
                ),
                // A reader gets one answer and an administrator two, and it is the
                // SESSION that decides which — not a button that happens to be on
                // screen. The server re-checks it either way.
                canDeleteForEveryone = editable,
                onDismiss = { confirmingDelete = false },
                onDeleteForMe = {
                    confirmingDelete = false
                    viewModel.hideSelectedPosts()
                },
                onDeleteForEveryone = {
                    confirmingDelete = false
                    viewModel.deleteSelectedPosts()
                }
            )
        }
    }
}

/**
 * One file to hand over: where it is, and what it is.
 *
 * A `Triple` would say the same thing with three fields named `first`, `second`
 * and `third`, which is how a share ends up sending a wildcard content type for a
 * video.
 */
private data class Shareable(val url: String, val kind: String, val contentType: String?)

/**
 * What readers have put on one post (§9).
 *
 * A single row of chips, the reader's own drawn differently: an emoji with no
 * indication of whose it is leaves the reader tapping a control to find out what
 * they already chose. Tapping a chip toggles that emoji, which is the same
 * gesture the picker performs — one post at a time instead of a selection.
 *
 * No empty state: a post nobody has reacted to draws nothing at all, because a
 * "no reactions yet" line under every update in a channel is furniture that has
 * to be scrolled past to read the update above it.
 */
@Composable
private fun ReactionRow(
    reactions: List<GoodPostReaction>,
    mine: String?,
    enabled: Boolean,
    onReact: ((String) -> Unit)?
) {
    val shown = reactions.filterNot { it.isEmpty }
    if (shown.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        shown.forEach { reaction ->
            ReactionChip(
                emoji = reaction.emoji,
                count = reaction.count,
                mine = reaction.emoji == mine,
                enabled = enabled && onReact != null,
                onClick = { onReact?.invoke(reaction.emoji) }
            )
        }
    }
}

/**
 * One emoji and its count (§9).
 *
 * Rounded to a pill rather than a square chip, so it reads as an attachment to
 * the post the way a chat's reaction does. The count is compacted
 * ([waCompactCount]) like every other number in the tab: "1.2K" fits beside an
 * emoji where "1243" does not.
 */
@Composable
private fun ReactionChip(
    emoji: String,
    count: Int,
    mine: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)

    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (mine) Wa.Accent.copy(alpha = 0.18f) else Wa.Pressed)
            .border(1.dp, if (mine) Wa.Accent else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = emoji, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = waCompactCount(count),
            color = if (mine) Wa.Accent else Wa.BubbleTime,
            fontSize = 11.sp,
            fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * The emoji a reader may choose from (§9).
 *
 * The list comes from the server, which is the only place it is defined: the app
 * deliberately carries no copy, so a seventh emoji added to the API appears here
 * without an app release. The shape is a bar rather than a dialog because this is
 * a one-tap gesture on the thing behind it, and it sits above the action row
 * instead of replacing it so the reader can see what they are reacting to.
 */
@Composable
private fun ReactionPicker(
    emoji: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Wa.Bar)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        emoji.forEach { face ->
            Text(
                text = face,
                fontSize = 22.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onPick(face) }
                    .padding(8.dp)
            )
        }
    }
}

/**
 * How many files one share may carry.
 *
 * Not a technical limit — the intent can hold more — but past a handful the
 * chooser's own previews stop being readable and the receiving app starts writing
 * a folder rather than a message. Ten posts' worth of media is already more than
 * anybody shares in one gesture.
 */
private const val SHARE_FILE_LIMIT = 10

/**
 * The box a post's picture is drawn in — width / height.
 *
 * Fixed rather than taken from each file, so every post with an image is the
 * same shape (§18). 4:3 shows a whole typical photo without letterboxing it into
 * a strip, and the crop loses only the edges of a portrait shot, whose full frame
 * is one tap away in the viewer.
 */
private const val POST_PHOTO_ASPECT = 4f / 3f

/** The same for video: 16:9, what a phone or a channel actually shoots in. */
private const val POST_VIDEO_ASPECT = 16f / 9f

/**
 * How strongly a selected post is tinted.
 *
 * Measured against a dark bubble and a bright photo: light enough that the
 * content underneath stays readable (the reader still needs to know WHICH post
 * they picked), strong enough to be obvious at a glance in a scrolling list.
 */
internal const val WaSelectionScrim = 0.22f

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
 * The actions are a long press rather than a row of icons under every bubble: an
 * action attached to every message is an action attached to no message in
 * particular, and the selection bar that appears is what tells the reader which
 * one they are acting on.
 *
 * The body is deliberately NOT a `SelectionContainer`. It was one, so the text
 * could be selected and copied with a long press — and that made the gesture mean
 * two things at once: the platform's own select/copy/read-aloud toolbar rose over
 * the bubble at the same moment the app was putting the post into its selection
 * state. The bar that appears now is the app's, and Copy is one of its actions,
 * so nothing was lost by making the long press mean exactly one thing.
 */
@Composable
internal fun PostItem(
    post: GoodPostPost,
    selected: Boolean,
    /**
     * Whether this reader has starred the post (§9).
     *
     * Drawn on the card rather than only in the bar that acts on a selection: a
     * star is a bookmark, and a bookmark that cannot be seen from the list it
     * was made in is one the reader has to remember. Defaulted so a screen with
     * no reader context — an administrator's own feed — draws nothing new.
     */
    starred: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /**
     * Reacts to this post with one emoji (§9).
     *
     * Null on a screen with no reader to attribute a reaction to — the starred
     * list, an administrator's own preview — where the chips are drawn as plain
     * counts instead of as controls that could only fail when tapped.
     */
    onReact: ((String) -> Unit)? = null,
    /** True while a reaction for this post is on its way to the server (§9). */
    reacting: Boolean = false,
    /** Opens one of this post's attachments in the app (§9). */
    onOpenMedia: (GoodPostMedia) -> Unit,
    /**
     * The same, for a video that was already playing in the card, with the
     * playhead it had reached.
     *
     * Defaulted to [onOpenMedia] so a screen with no inline player — a search
     * result — does not have to know this exists.
     */
    onOpenMediaAt: (GoodPostMedia, Long) -> Unit = { asset, _ -> onOpenMedia(asset) },
    /**
     * Re-reads this post, for a media URL whose signature has run out (§24).
     *
     * A feed can sit open past a URL's lifetime, and a video that was fine when
     * the page loaded will refuse to play later. Asking for the post again is
     * the only retry that can succeed, so the card asks rather than failing.
     */
    onRefreshMedia: () -> Unit = {}
) {
    val context = LocalContext.current

    // The selection fill is on this wrapper rather than inside the bubble: the
    // bubble paints its own background over whatever it is given, so a highlight
    // passed inwards would be covered by the very container it is meant to mark.
    // That is also why the selection LAYER is painted in `drawWithContent` below
    // instead of being a tint behind the content — behind it is invisible.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Wa.Selected else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .drawWithContent {
                drawContent()
                // §5: the layer a selected post gets, like a selected message in
                // a chat. Over the whole card — bubble, photo, video poster — so
                // "this one is picked" is visible from the picture alone.
                if (selected) drawRect(color = Wa.Accent.copy(alpha = WaSelectionScrim))
            }
    ) {
        WaPostContainer {
            post.media.forEach { asset ->
                if (asset.isImage) {
                    RemoteImage(
                        url = asset.url,
                        // A fixed box, not the file's own proportions (§18). Every
                        // post that carries a picture is then the same height, so
                        // the feed does not step up and down it as the reader
                        // scrolls, and one post's photo can never push the next
                        // post's text off the screen. Cropped to fill, like the
                        // preview in a chat; the full frame is one tap away.
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(POST_PHOTO_ASPECT)
                            // One radius for every attachment in the tab — the
                            // same shape the video card and the link chip wear.
                            // A photo used to round itself at 8dp, its own loader
                            // clipped again at 11dp, and a clip beside it was
                            // 10dp: three corners, one kind of thing (§6).
                            .clip(WaMediaShape)
                            // A photo opens full size in the app, and can be kept
                            // from there (§15). It used to do nothing at all,
                            // which read as a broken image rather than a picture
                            // that simply is not interactive.
                            //
                            // Holding it selects the post, because the picture is
                            // the biggest part of it to aim at: a tap it answers
                            // itself, a hold it passes back up to the bubble.
                            .combinedClickable(
                                enabled = !asset.url.isNullOrBlank(),
                                onClick = { onOpenMedia(asset) },
                                onLongClick = onLongClick
                            )
                    )
                    Spacer(Modifier.height(4.dp))
                } else {
                    InlineVideoCard(
                        url = asset.url.orEmpty(),
                        // Fixed as well, and letterboxed rather than cropped when
                        // the file disagrees: cropping a portrait clip hides the
                        // half of it the channel shot.
                        aspect = POST_VIDEO_ASPECT,
                        onOpen = { from -> onOpenMediaAt(asset, from) },
                        onRefreshUrl = onRefreshMedia,
                        // §5: the card paints its own selection layer — see
                        // the sheets inside [InlineVideoCard].
                        selected = selected,
                        onLongPress = onLongClick
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            val bodyText = post.body
            if (!bodyText.isNullOrBlank()) {
                // §5: holding the bubble is the app's own gesture, and this is
                // what keeps Android's select/copy menu from answering it too.
                WaNoTextSelection {
                    Text(
                        // §17: the stored markers become spans here, and only
                        // here. The body itself is plain text the server never
                        // parsed; nothing is rendered as markup, so a post that
                        // contains `<b>` simply says `<b>`.
                        text = parseGoodPostText(bodyText),
                        color = Wa.BubbleText,
                        fontSize = 15.sp,
                        // Looser than the default on purpose: a channel's update is
                        // a paragraph, and a paragraph set solid is the difference
                        // between a message and a block of text.
                        lineHeight = 21.sp,
                        // No inset of its own — the bubble owns the padding now, so
                        // the first line starts where the picture above it starts.
                        // A media caption gets one small gap instead.
                        modifier = if (post.media.isNotEmpty()) {
                            Modifier.padding(top = 3.dp)
                        } else {
                            Modifier
                        }
                    )
                }
            }

            if (post.isLink) {
                if (!post.body.isNullOrBlank()) Spacer(Modifier.height(4.dp))
                LinkChip(
                    label = post.linkTitle?.takeIf { it.isNotBlank() } ?: post.linkUrl.orEmpty(),
                    url = post.linkUrl.orEmpty(),
                    // A LINK is the one thing here that does belong to the
                    // browser: it is a page, unlike a signed URL to a file.
                    onOpen = { openLink(context, post.linkUrl) }
                )
            }

            // §9: what readers have put on this update. Inside the bubble and
            // above the timestamp, which is where a chat shows a reaction — it
            // belongs to the post it is on, not to the list around it.
            if (post.reactions.isNotEmpty()) {
                ReactionRow(
                    reactions = post.reactions,
                    mine = post.myReaction,
                    enabled = onReact != null && !reacting,
                    onReact = onReact
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(
                // No horizontal inset of its own: the bubble owns the padding, so
                // the time and the view count line up with the last line of the
                // text above them rather than floating 6dp further in (§6).
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // A post that has been edited says so, because a reader who saw
                // it before should not have to wonder whether they misremembered
                // it. There is no other furniture in this row: what a post can do
                // is reached by holding it (§5).
                if (starred) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.goodpost_starred),
                        tint = Wa.BubbleTime,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (post.editedAt != null) {
                    Text(
                        text = stringResource(R.string.goodpost_edited),
                        color = Wa.BubbleTime,
                        fontSize = 11.sp
                    )
                }
                if (selected) {
                    // The tick sits in the meta row, where a reader looks for
                    // "what happened to this message" — the same place a chat
                    // puts read receipts. The layer above already says it is
                    // picked; this says it without relying on colour alone.
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.goodpost_selected),
                        tint = Wa.Accent,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Spacer(Modifier.weight(1f))

                // §9: how many readers have opened THIS update, inside the
                // update it belongs to, next to its own timestamp. On the card
                // in the channel list the number described a different post on
                // every row; here it is unambiguous, and it is where a channel
                // owner looks for it.
                if (post.views > 0) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = stringResource(R.string.goodpost_views_of_latest),
                        tint = Wa.BubbleTime,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = waCompactCount(post.views),
                        color = Wa.BubbleTime,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                }

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
            // No clip of its own: the caller decides the shape, because the
            // caller is the one that knows what it is drawing (§6).
            modifier = modifier
        )
    } else {
        // The same box the picture will occupy, so the load is a fade rather
        // than a resize — the placeholder is the caller's modifier, sizing and
        // all, not a size of its own.
        WaMediaPlaceholder(
            modifier = modifier,
            icon = Icons.Filled.Link,
            label = if (url.isNullOrBlank()) {
                stringResource(R.string.goodpost_media_unavailable)
            } else null
        )
    }
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
/**
 * How much of a freshly-loaded page is fetched in advance (§24).
 *
 * The newest [PREFETCH_POSTS] updates are the ones a reader is about to scroll
 * into — the feed opens at the bottom — so their pictures are worth fetching
 * before they are needed. Bounded twice, by post and by image, because a page of
 * thirty updates with four attachments each would otherwise be a hundred and
 * twenty requests fired at the bucket the moment a channel opened.
 */
private const val PREFETCH_POSTS = 8
private const val PREFETCH_LIMIT = 8

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
internal fun ChannelInputBar(
    state: GoodPostUiState,
    channelId: String,
    viewModel: GoodPostViewModel
) {
    val context = LocalContext.current
    // Scoped to the channel this bar belongs to, rather than to a bare "is
    // anything being edited" (§19 Bug 3): the latter let one channel's editing
    // strip appear over another channel's feed.
    val editing = state.isEditingIn(channelId)

    // The caret and anchor live here, while the text itself lives in the
    // ViewModel. That split is what lets the formatting toolbar act on exactly
    // what is highlighted without keeping a second copy of the body in step.
    val body = state.composerBody
    var selection by remember { mutableStateOf(TextRange(body.length)) }

    // Clamped on every read: the body can shrink under the caret (a format
    // toggle, a publish that clears it), and a TextRange past the end of the
    // text is an exception rather than a caret at the end.
    val selectionStart = selection.start.coerceIn(0, body.length)
    val selectionEnd = selection.end.coerceIn(0, body.length)
    val selectedText = body.substring(
        minOf(selectionStart, selectionEnd),
        maxOf(selectionStart, selectionEnd)
    )
    // The body is parsed for display as it is typed, so a format shows up where
    // the reader applied it instead of only after the post is published.
    //
    // This matters most for monospace, which is the one format whose whole point
    // is how it LOOKS: a reader who taps it and sees nothing change cannot tell
    // the control from a broken one. The stored string is untouched — the markers
    // are still in it, which is what keeps the round trip lossless — and the
    // spans are rebuilt from that string on every edit.
    val fieldValue = TextFieldValue(
        annotatedString = parseGoodPostText(body),
        selection = TextRange(selectionStart, selectionEnd)
    )

    /**
     * Whether anything is actually highlighted (§7).
     *
     * A caret is a zero-width selection, so a collapsed range is the "nothing
     * selected" case — which is what gates the formatting controls now that they
     * are not permanent.
     */
    val hasSelection = selectionStart != selectionEnd

    /**
     * What Android's selection menu would have offered (§7).
     *
     * Compose hands these to a [TextToolbar] when it wants to SHOW that menu.
     * Our toolbar captures them instead of displaying anything, and this bar
     * invokes them, so Cut / Copy / Paste / Select all still work while the
     * platform's own bubble is gone. Null until the platform first offers them —
     * which is also the state a field with no clipboard content is in, and the
     * reason the clipboard row hides itself rather than drawing dead buttons.
     */
    var clipboardActions by remember { mutableStateOf<List<GoodPostContextMenuAction>>(emptyList()) }

    // Remembered so the field is not given a new provider on every recomposition:
    // Compose compares the local's identity, and a fresh instance each frame would
    // install itself again mid-selection.
    val selectionMenu = remember {
        GoodPostTextContextMenuProvider { offered -> clipboardActions = offered }
    }

    // Opening an edit puts the caret at the end of what was published, which is
    // where someone adding a correction wants it.
    LaunchedEffect(state.editingPostId) {
        selection = TextRange(body.length)
    }

    fun applyFormat(format: GoodPostFormat) {
        val edit = applyGoodPostFormat(body, selectionStart, selectionEnd, format)
        viewModel.onComposerBodyChange(edit.text)
        selection = TextRange(edit.selectionStart, edit.selectionEnd)
    }

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

        // §7: the controls appear WITH a selection, and only then.
        //
        // They used to be on screen whenever the field had focus, which made a
        // row of buttons a permanent part of the composer whether or not anybody
        // was formatting anything — a control strip you look past on every
        // message. Now the gesture is the one people already know from WhatsApp:
        // highlight a word, the controls for it appear, collapse the selection
        // and they are gone. The composer is otherwise just an input field.
        //
        // Android's own menu — Cut / Copy / Paste / Select all / Read aloud — is
        // SUPPRESSED here rather than left alone (see [GoodPostSelectionToolbar]).
        // It was a light bubble over the line being edited, in an app that is
        // otherwise dark, and it appeared for every selection including the ones
        // this bar exists for. Its operations are not lost: the callbacks it
        // would have invoked are captured and offered by the second half of this
        // bar, in this app's own colours and in the layout rather than on top of
        // the text.
        //
        // The bar is in the layout rather than floating over the feed, so it can
        // never cover the line being edited. The post list gives up its height
        // for as long as a selection exists, which is the only moment it shows.
        if (hasSelection) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Wa.Canvas)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GoodPostFormatMenu(
                    // A selection is the only thing an entry can act on, so its
                    // active state is a fact about the highlighted text: the
                    // entry reads as a toggle that is already on, not as an
                    // action still waiting to happen.
                    isActive = { format -> format.wraps(selectedText) },
                    onToggle = ::applyFormat
                )

                GoodPostMenuDivider()

                // No weight here: the row scrolls, so "fill the rest" has no
                // rest to fill — the clipboard actions sit after the words and
                // move into view with them.
                GoodPostClipboardBar(clipboardActions)
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
                // The field itself is where Compose installs the selection
                // menu, so the replacement has to be provided around it (§7).
                //
                // Both locals, because Compose picks between them by gesture:
                // a long press and a right click are different providers, and
                // overriding only one would leave the other gesture raising
                // Android's own bubble.
                CompositionLocalProvider(
                    LocalTextContextMenuToolbarProvider provides selectionMenu,
                    LocalTextContextMenuDropdownProvider provides selectionMenu
                ) {
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { updated ->
                            selection = updated.selection
                            viewModel.onComposerBodyChange(updated.text)
                        },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(
                            color = Wa.Text,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(Wa.StampRecent),
                        maxLines = 5,
                        decorationBox = { innerTextField ->
                            if (body.isEmpty()) {
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
                }

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

            val canSubmit = (body.isNotBlank() || state.composerAttachments.isNotEmpty()) &&
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
internal fun shareChannel(
    context: Context,
    name: String,
    link: String,
    /**
     * The update being shared, when there is one.
     *
     * WhatsApp shares what was SAID together with where it came from, and the
     * difference is not cosmetic: a bare URL in a chat is a link somebody has to
     * decide whether to tap, while the text beside it is the thing they came for.
     * The link still travels, because the link is what makes the share real — see
     * the channel's own `shareLink`, which is the page a recipient without the
     * app lands on.
     */
    message: String? = null
) {
    if (link.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_TEXT,
            if (message.isNullOrBlank()) link else "$message\n\n$link"
        )
        putExtra(Intent.EXTRA_SUBJECT, name)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/**
 * Hand a LINK to whatever the device opens it with.
 *
 * Links only. Media used to come through here too, which is how a tap on a video
 * ended up in a browser looking at an S3 denial: the URL it was given is signed
 * for this app, not for a page.
 */
private fun openLink(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        // No app can handle it. Nothing to say: the user tapped and the device
        // has no opinion, and a toast about a missing handler helps nobody.
    }
}
