package com.muddassir.clearview.goodpost.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import java.util.Locale
import kotlin.math.roundToInt

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
    // How long that clip is, so the viewer's transport can show a real total from
    // its first frame. The server measured every upload, so this is known before
    // anybody opens anything — it just was not being handed over (§9).
    var viewerDurationMs by remember { mutableStateOf(0L) }

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

            // §18: the list owns the room its cards are drawn in. It is measured
            // rather than inferred from the window, because the list is what
            // knows — its own insets make a card narrower than the screen, and a
            // tablet, a split screen and a landscape window are all just different
            // numbers here.
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val feedWidth = maxWidth
                val feedHeight = maxHeight
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
                            is FeedEntry.Separator -> WaDatePill(
                                text = entry.label,
                                // The day a post belongs to arrives and leaves with
                                // it, so the pill animates like any other row
                                // rather than blinking into a gap.
                                modifier = Modifier.animateItem()
                            )

                            is FeedEntry.Post -> PostItem(
                                post = entry.post,
                                // §18: the room this card gets. The width is what
                                // the list's own insets leave, and the height is
                                // the feed's — the two numbers a media card's size
                                // is worked out from.
                                availableWidth = feedWidth - POST_LIST_INSET * 2,
                                availableHeight = feedHeight,
                                // §5: a tap on a document opens the file, unless
                                // the reader is picking posts instead.
                                selectionActive = state.postSelectionActive,
                                // §12, §22: a post that arrives over the poll, or
                                // that is deleted while the feed is open, moves
                                // its neighbours instead of snapping them.
                                modifier = Modifier.animateItem(),
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
                                        viewerDurationMs = asset.durationMs ?: 0L
                                    }
                                },
                                onOpenMediaAt = { asset, from ->
                                    if (!state.postSelectionActive) {
                                        viewerPostId = entry.post.id
                                        viewerMediaId = asset.id
                                        // Resume where the card had got to.
                                        viewerFromMs = from
                                        viewerDurationMs = asset.durationMs ?: 0L
                                    }
                                },
                                onRefreshMedia = { viewModel.refreshPost(entry.post.id) }
                            )
                        }
                    }

                    if (state.posts.isEmpty() && state.postsLoading) {
                        // §22: bubbles the size of the ones that are coming, so
                        // opening a channel shows the conversation it is about to
                        // become instead of an empty screen and a spinner.
                        item(key = "loading") { WaPostFeedSkeleton() }
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
                // §21: the strip opens the posting page rather than being it. The
                // editor needs the whole screen — a paragraph is not a chat reply.
                ChannelComposeBar(state = state, viewModel = viewModel)
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
                    viewerDurationMs = 0L
                },
                // A fresh signature, through the one row this viewer is showing.
                onExpired = { viewerPostId?.let { viewModel.refreshPost(it) } },
                startAtMs = viewerFromMs,
                knownDurationMs = viewerDurationMs,
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
                // And so does the caption, so sharing a clip from full screen
                // sends what sharing its post from the feed sends (§15).
                shareCaption = state.shareCaptionFor(viewerPostId),
                onToggleStar = {
                    state.posts.firstOrNull { it.id == viewerPostId }?.let(viewModel::toggleStar)
                },
                // Deleting the file from the viewer closes the viewer: the thing
                // it was showing is no longer on this device.
                onDeleted = {
                    viewerPostId = null
                    viewerMediaId = null
                    viewerFromMs = 0L
                    viewerDurationMs = 0L
                },
                // ...and the tab is told, not just the cache (§6, §13). The feed
                // has no media page loaded, so the post and the URL travel with
                // the id — they are what the row is found by.
                onDeleteFromDevice = {
                    val postId = viewerPostId
                    val mediaId = viewerMediaId
                    if (postId != null && mediaId != null) {
                        viewModel.deleteMediaFromDevice(postId, mediaId, viewing.url)
                    }
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

    // A Column, not a Box: the emoji row is composited UNDER the bar so it can
    // never cover the count and the actions the reader is reaching for. As a Box
    // child aligned to the top centre it sat ON the bar, hiding the very controls
    // it belongs to.
    Column {
    WaSelectionBar(
        count = state.selectedPostIds.size,
        onClose = viewModel::clearPostSelection,
        busy = state.selectionBusy,
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
                                    // The same words AND the same link a text-only
                                    // share sends: attaching a file is no reason to
                                    // drop the half that lets the recipient find
                                    // the channel (§15).
                                    shareFiles(
                                        context = context,
                                        uris = uris,
                                        type = mimeTypeForAll(files.map { it.kind to it.contentType }),
                                        text = sharedPostText(text, link)
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
                    // Withheld while the previous batch is in flight, so the bar
                    // cannot open a second confirmation over a request that has
                    // not been answered yet.
                    enabled = !state.selectionBusy,
                    onClick = { confirmingDelete = true }
                )
            }
        }
    )

        // §9: the emoji row, on its own line BELOW the action bar. It grows the
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
                modifier = Modifier.align(Alignment.End).padding(end = 8.dp)
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
 * The post's reactions, drawn the way a chat draws them (§9): one small pill of
 * emoji and counts, hung on the BUBBLE'S BOTTOM EDGE rather than sitting inside
 * it as a row of chips.
 *
 * ## Why the edge
 *
 * A reaction is not part of what the post says — it is what readers did to it,
 * and the one thing its position has to say is which post it belongs to. Inside
 * the bubble, above the timestamp, a row of chips read as another line the
 * channel had written. On the edge, straddling the bubble's border, it reads as
 * something stuck on that message and on no other, which is exactly what
 * WhatsApp's does and why nothing here has to explain it.
 *
 * ## Why the pill wears the list's colour, not the bubble's
 *
 * The bubble is the same colour as the pill's container would be if it borrowed
 * it, and a pill the colour of what it covers is invisible. Painting it in the
 * list's own background (with one hairline edge) is what makes it look laid OVER
 * the bubble — the same trick the chat wallpaper plays on a reaction there.
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
    onReact: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val shown = reactions.filterNot { it.isEmpty }
    if (shown.isEmpty()) return

    // §22: reactions arrive from OTHER phones (§12), so a pill appearing between
    // two glances at the same post should look like it was added rather than like
    // the row was redrawn.
    WaAppear(enter = WaMotion.chipEnter()) {
        Row(
            // Half the pill hangs below the bubble, so the row is offset up by
            // half its own height: it stays in the normal flow of the card (the
            // bubble keeps its own size and the next post keeps its distance)
            // while DRAWING over the bubble's border. Only the y offset moves, so
            // nothing is measured twice.
            modifier = modifier
                .fillMaxWidth()
                .offset(y = -REACTION_OVERLAP)
                .padding(start = 14.dp),
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
}

/** How far the pill hangs over the bubble's bottom edge. Half of its height. */
private val REACTION_OVERLAP = 11.dp

/**
 * One emoji and its count (§9).
 *
 * Small, flat and fully rounded, the shape a chat uses: the emoji at 12sp with
 * the count beside it, and the count shown only once more than one reader has
 * chosen that emoji — "👍 1" on a single reaction is the number restating the
 * emoji.
 *
 * The reader's OWN emoji is marked with the accent on the count and the border
 * rather than with a filled background, so a post with four reactions reads as
 * four equal pills with one of them edged in the app's colour.
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
            .background(Wa.List)
            .border(1.dp, if (mine) Wa.Accent else Wa.Divider, shape)
            // §22: a chip is the smallest thing a finger has to hit in this tab,
            // so the press is answered on the chip itself.
            .waTappable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = emoji, fontSize = 12.sp)
        if (count > 1) {
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
/**
 * The box a post's picture gets when the server never measured the file.
 *
 * A file uploaded before the API carried dimensions, and nothing else. Everything
 * uploaded since reports its own width and height, and those are what the box
 * uses — see [postMediaAspect].
 */
private const val POST_PHOTO_ASPECT = 4f / 3f

/**
 * The gap between a post's words and its media, and between one item of media and
 * the next.
 *
 * One value for both, because there is no reason for the reader to find two: the
 * words end and the picture begins at the same distance whether the picture is
 * followed by another picture or by the meta row.
 */
private val MEDIA_GAP = 4.dp

/**
 * The tallest a media card may be, as a share of the room the feed has.
 *
 * This is the number that makes a card SMALL without cutting the picture. A chat
 * does not draw a portrait photo as wide as the screen — that is a card taller than
 * the phone, which is what a 9:16 photo becomes at full width. It caps the HEIGHT
 * and lets the width follow from the file's own ratio, so a tall photo becomes a
 * narrow card holding the whole frame: smaller, and still the entire picture.
 *
 * A share of the viewport rather than a fixed count of dp, so the same post looks
 * right on a small phone, a tablet, and in landscape.
 */
internal const val POST_MEDIA_MAX_HEIGHT_FRACTION = 0.6f

/**
 * An absolute ceiling on the above, for a viewport tall enough that a share of it
 * stops being a thumbnail: a foldable's inner screen, or a desktop window.
 */
internal val POST_MEDIA_MAX_HEIGHT = 460.dp

/**
 * A post list's own horizontal inset, which is what the card width is measured from.
 *
 * Shared with the channel search, so its results are sized by the same rule as the
 * feed they were found in — a result and the post it came from are the same card.
 */
internal val POST_LIST_INSET = 10.dp

/**
 * How wide one post's media column is.
 *
 * ## The rule
 *
 * As wide as the room inside the bubble — unless that would make the tallest
 * attachment of the post taller than [POST_MEDIA_MAX_HEIGHT_FRACTION] of the feed,
 * in which case the width is pulled back exactly as far as it has to be.
 *
 *  * A landscape photo is wide: its ratio allows the full width inside the cap, so
 *    a 16:9 still fills the card, as it should.
 *  * A portrait photo is NOT: a 9:16 at full width is half again as tall as the
 *    phone, so its width is pulled back to what the cap allows and the card gets
 *    smaller around the whole picture.
 *  * A square sits in between and usually keeps the full width.
 *
 * One number for the whole post, not one per attachment: several photos in one post
 * are then a column of equal width, and the tightest of them decides it — so none of
 * them is narrower than the bubble (which would show the bubble beside it) and none
 * sticks out of it. The post's other content is measured against the same width, so
 * the bubble can be made exactly as wide as this and no wider.
 *
 * ## The exception: several photos
 *
 * A SET of photos is drawn as an album rather than as a column of cards (§18), and
 * an album is a grid: its tiles are cropped to their cells, so the pictures' own
 * shapes no longer decide anything. Sizing it from them would only make it small for
 * a reason that no longer applies — the grid gets the whole column, which is how a
 * chat lays out the same set.
 */
internal fun postMediaWidth(
    media: List<GoodPostMedia>,
    contentWidth: Dp,
    viewportHeight: Dp
): Dp {
    if (media.isEmpty()) return contentWidth

    // Several PHOTOS are a grid, and a grid is as wide as the column (§18).
    if (media.size >= 2 && media.all { it.isImage }) return contentWidth

    // No viewport to measure against (a preview outside a list) means no cap: the
    // media fills the bubble, which is how every card was drawn before this.
    val cap: Dp? = if (viewportHeight == Dp.Unspecified || viewportHeight <= 0.dp) {
        null
    } else {
        (viewportHeight * POST_MEDIA_MAX_HEIGHT_FRACTION).coerceAtMost(POST_MEDIA_MAX_HEIGHT)
    }

    return media.minOf { asset ->
        // A file the server never measured has no ratio to keep, so it is not
        // allowed to shrink the column: it fills the width and is cropped to a
        // guess, exactly as it always was.
        val ratio = aspectOf(asset.width, asset.height) ?: return@minOf contentWidth
        (cap?.let { it * ratio } ?: contentWidth).coerceAtMost(contentWidth)
    }
}

/**
 * The proportions one attachment is drawn at: the FILE's own.
 *
 * ## What this replaced, and why it was the bug
 *
 * This used to be a single fixed box (4:3) that every photo was cropped to fill,
 * and then — once that was reported — the same fixed box with the picture FITTED
 * inside it, held inside a 3:4 … 16:9 band.
 *
 * Both were wrong in the same way: they decided the shape of a picture from the
 * layout instead of from the picture. The crop threw away most of a portrait, and
 * the band drew a 9:16 photo into a 3:4 box and left the difference over as two
 * columns of the bubble's own green — the empty green areas that produced this
 * fix. No amount of tuning the box could have removed them, because as long as the
 * box disagrees with the file, SOMETHING has to fill the difference.
 *
 * Taking the file's own numbers ends the whole class of bug: a 16:9 clip is wide,
 * a 9:16 photo is tall, a square is square. The box IS the picture's shape, so
 * there is nothing to crop, nothing to stretch, and no leftover area for the
 * bubble to show through — and the bubble hugs the media, because the media is
 * what gives it its size.
 *
 * Cards therefore differ in height, which is what a chat does too: a post is as
 * tall as the thing that was posted. [POST_PHOTO_ASPECT] is the only guess left,
 * for a file the server never measured.
 */
internal fun postMediaAspect(asset: GoodPostMedia, fallback: Float): Float =
    aspectOf(asset.width, asset.height) ?: fallback

/**
 * One attachment of a post, at the file's own proportions (§18).
 *
 * The width is whatever the bubble gives it (the bubble's width less its own
 * padding — the same inset the words are aligned against, so a media post and a
 * text post line up); the height follows from the ratio, so a portrait photo is
 * tall and narrow-beside-nothing, and a panorama is a strip. Nothing is cropped:
 * `Fit` into a box of the picture's own shape has nothing left over to letterbox,
 * and it cannot crop even if a file's real dimensions disagree with the server's.
 *
 * ## The one guess
 *
 * A file the server never measured — an upload from before the picker started
 * sending dimensions — has no shape to preserve, so its box is a guess (4:3) and
 * its picture is CROPPED to fill it, which is what this tab has always done with
 * those rows. The alternative was a letterboxed picture showing the bubble through
 * the leftover, and every empty green area in this feed came from exactly that.
 *
 * Taps open the file full size; holds select the post, because the picture is the
 * biggest part of a post to aim at.
 */
@Composable
private fun PostMedia(
    asset: GoodPostMedia,
    fallbackAspect: Float,
    /** The media column's width for this post — see [postMediaWidth]. */
    width: Dp,
    onOpenMedia: (GoodPostMedia) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Measured by the server, i.e. there is a real shape to keep. Null means the
    // box below is a guess, and see above for what a guess is filled with.
    val measured = aspectOf(asset.width, asset.height) != null

    // The shape the picture turns out to HAVE, once it has been decoded — which is
    // the only shape that cannot leave a gap.
    //
    // The number above is metadata: it was measured somewhere else, by a version of
    // this app that may have read a JPEG's orientation flag differently, or by a
    // path that never read it at all. `Fit` into a box built from a wrong number
    // draws the picture at its own proportions and shows the bubble through the
    // difference — on both sides, which is exactly the "empty green beside the
    // photo" this whole layout exists to remove. The decoded bitmap is ground
    // truth; the metadata is a hint about how big to draw it before it arrives.
    var trueAspect by remember(asset.id) { mutableStateOf<Float?>(null) }

    RemoteImage(
        url = asset.url,
        contentScale = if (measured) ContentScale.Fit else ContentScale.Crop,
        onDecoded = { decodedWidth, decodedHeight ->
            // Null for a file that never loaded, which leaves the metadata's answer
            // in place — that row keeps the shape it always had.
            if (decodedWidth > 0 && decodedHeight > 0) {
                trueAspect = decodedWidth.toFloat() / decodedHeight.toFloat()
            }
        },
        // The picture sits against the LEFT edge of its card, not in the middle of
        // it. `Fit` on its own centres, which is what split any leftover into two
        // columns — one either side of a portrait photo — instead of leaving the
        // picture where the post starts and the room it did not need on the side
        // the text runs out towards.
        //
        // A chat does the same thing for the same reason: a post is read from its
        // left edge, so that is the edge its content is anchored to.
        alignment = Alignment.CenterStart,
        modifier = modifier
            // A set width rather than `fillMaxWidth`, because the width is the
            // number that was chosen for this post: the card is as wide as its
            // media, so this is what the bubble was sized around (§18).
            //
            // `Unspecified` is a screen that never told the card how much room it
            // has (a preview outside a list): the picture fills the bubble there,
            // which is how every card was drawn before sizing existed. It has to
            // be `fillMaxWidth` rather than a width of nothing — `Dp.Unspecified`
            // measured out is zero, which would collapse the picture entirely.
            .then(if (width == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier.width(width))
            // The decoded shape when it is known, the measured one until then: the
            // card may settle by a hair as the frame lands, and in exchange nothing
            // is cropped and no bubble shows beside the picture.
            .aspectRatio(trueAspect ?: postMediaAspect(asset, fallbackAspect))
            // One radius for every attachment in the tab — the same shape the
            // video card and the link chip wear. A photo used to round itself at
            // 8dp, its own loader clipped again at 11dp, and a clip beside it was
            // 10dp: three corners, one kind of thing (§6).
            .clip(WaMediaShape)
            // A photo opens full size in the app, and can be kept from there
            // (§15). It used to do nothing at all, which read as a broken image
            // rather than a picture that simply is not interactive.
            //
            // Holding it selects the post, because the picture is the biggest part
            // of it to aim at: a tap it answers itself, a hold it passes back up
            // to the bubble.
            .combinedClickable(
                enabled = !asset.url.isNullOrBlank(),
                onClick = { onOpenMedia(asset) },
                onLongClick = onLongClick
            )
    )
}

/**
 * How many tiles an album shows before it starts counting the rest.
 *
 * Four, because four is the grid: the block is a square of two rows by two, and a
 * fifth picture would make one of them the wrong size. WhatsApp stops at four and
 * writes the remainder on the last tile for the same reason — the number is the
 * part that matters, and every picture is one tap away in the viewer.
 */
private const val MEDIA_GRID_TILES = 4

/** The seam between two tiles of an album. The bubble shows through it. */
private val MEDIA_SEAM = 2.dp

/**
 * The shape of an album's block, for a given number of pictures.
 *
 *  * Two — two SQUARES side by side, so the block is twice as wide as it is tall.
 *  * Three or four — a square: a 2x2 grid of squares, or a tall picture beside two
 *    squares, which is what a chat draws for three.
 *
 * Extracted and tested because it is the half of the layout that can be wrong
 * without a picture looking wrong: a block of the wrong shape crops every tile in
 * it, and it crops them all in the same direction.
 */
internal fun postAlbumAspect(count: Int): Float = if (count == 2) 2f else 1f

/** How many tiles an album of [count] pictures draws: at most [MEDIA_GRID_TILES]. */
internal fun postAlbumTileCount(count: Int): Int = count.coerceAtMost(MEDIA_GRID_TILES)

/**
 * Several photos in one post, as one block (§18).
 *
 * ## What this replaced, and why
 *
 * Each photo used to be its own card at its own proportions, stacked. That is right
 * for ONE picture — a portrait is tall and narrow, and the card should be shaped
 * like it — but wrong for a SET: five photos became five full-height cards, which
 * is five screens of scrolling for one update, and nothing on screen said the five
 * belonged together. The set is the message, so the set is drawn as one thing.
 *
 * ## The layout
 *
 * ```
 *   two             three            four (or more)
 *   +-----+-----+   +-----+-----+    +-----+-----+
 *   |     |     |   |     |  b  |    |  a  |  b  |
 *   |  a  |  b  |   |  a  +-----+    +-----+-----+
 *   |     |     |   |     |  c  |    |  c  |  d  |
 *   +-----+-----+   +-----+-----+    +-----+-----+
 * ```
 *
 * Tiles are CROPPED to fill their cell, and that is deliberate rather than a
 * leftover: a grid whose tiles each kept their own shape could not be a grid — it
 * would be a mosaic with holes in it, which is the column of full-height cards this
 * replaced. The whole frame of any tile is one tap away in the viewer, and the
 * viewer is where a picture is meant to be looked at properly.
 *
 * The block is as wide as the bubble's content, so an album needs no width rule of
 * its own: [postMediaWidth] gives a post with several pictures the whole column.
 */
@Composable
private fun PostMediaAlbum(
    media: List<GoodPostMedia>,
    width: Dp,
    onOpenMedia: (GoodPostMedia) -> Unit,
    onLongClick: () -> Unit
) {
    val tiles = media.take(MEDIA_GRID_TILES)
    val hidden = media.size - tiles.size

    // `Unspecified` is a caller with no constraints to give (a preview outside a
    // list): the album then fills what it is given, like every other card there.
    val sizing = if (width == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier.width(width)

    val tile: @Composable (Int, Modifier) -> Unit = { index, cellModifier ->
        AlbumTile(
            asset = tiles[index],
            // Written on the fourth tile only, and only when there is something
            // left over — see [MEDIA_GRID_TILES].
            moreCount = if (index == tiles.lastIndex) hidden else 0,
            modifier = cellModifier,
            onOpenMedia = onOpenMedia,
            onLongClick = onLongClick
        )
    }

    Column(modifier = sizing.aspectRatio(postAlbumAspect(tiles.size))) {
        when (tiles.size) {
            2 -> Row(modifier = Modifier.fillMaxSize()) {
                tile(0, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(MEDIA_SEAM))
                tile(1, Modifier.weight(1f).fillMaxHeight())
            }

            3 -> Row(modifier = Modifier.fillMaxSize()) {
                // One tall beside two squares: the shape that fills a square
                // without a hole, and the one a chat draws for three.
                tile(0, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(MEDIA_SEAM))
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    tile(1, Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(MEDIA_SEAM))
                    tile(2, Modifier.weight(1f).fillMaxWidth())
                }
            }

            else -> Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    tile(0, Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(MEDIA_SEAM))
                    tile(1, Modifier.weight(1f).fillMaxHeight())
                }
                Spacer(Modifier.height(MEDIA_SEAM))
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    tile(2, Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(MEDIA_SEAM))
                    tile(3, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/**
 * One cell of an album.
 *
 * Cropped square-ish to whatever its cell is, because the cell is the shape the
 * grid decided on; [moreCount] paints the "+n" a chat puts on the last tile when
 * the set is bigger than the grid.
 */
@Composable
private fun AlbumTile(
    asset: GoodPostMedia,
    moreCount: Int,
    modifier: Modifier,
    onOpenMedia: (GoodPostMedia) -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = modifier
            // A small radius per tile rather than the one media shape: tiles this
            // size wearing a 10dp corner read as four separate cards, which is the
            // look the block exists to avoid.
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(
                enabled = !asset.url.isNullOrBlank(),
                onClick = { onOpenMedia(asset) },
                onLongClick = onLongClick
            )
    ) {
        RemoteImage(
            url = asset.url,
            // Crop, and only here: an album tile is a cell of a grid, and a cell
            // that kept its picture's own shape could not be one.
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )

        if (moreCount > 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+$moreCount",
                    color = Color.White,
                    fontFamily = WaPostFont,
                    fontSize = 18.sp
                )
            }
        }
    }
}

/**
 * A document on a post: its name, how big it is, and a way to open it (§21).
 *
 * ## Why this is not a preview
 *
 * An image card shows the picture because the picture IS what was sent. A PDF is
 * the opposite: its contents are a page or two hundred of somebody else's layout,
 * and what a reader needs before tapping is the NAME — "Timetable 2026" says
 * whether to open it, and a rendered first page at bubble size says nothing. So the
 * card is a file row, the same shape the link chip wears, and the file opens in
 * whatever the device has registered for it.
 *
 * ## Why the tap downloads first
 *
 * The URL is a signed capability addressed to this app, so handing it to somebody
 * else's viewer would give them a link that refuses them. The bytes are fetched
 * into the app's own cache and passed over through the FileProvider, under a read
 * grant for that one URI — the same path Share uses, for the same reason.
 */
@Composable
private fun PostDocumentCard(
    asset: GoodPostMedia,
    openable: Boolean,
    onLongClick: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A local flag rather than a spinner driven from state: opening is a few tens
    // of milliseconds of download, and a second tap during it would fetch the file
    // twice.
    var opening by remember(asset.id) { mutableStateOf(false) }
    val name = asset.fileName?.takeIf { it.isNotBlank() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WaMediaShape)
            .background(Wa.Pressed)
            .combinedClickable(
                enabled = openable && asset.url != null,
                onClick = {
                    val url = asset.url ?: return@combinedClickable
                    if (opening) return@combinedClickable
                    opening = true
                    scope.launch {
                        val uri = GoodPostDownloads.shareFile(
                            context = context,
                            url = url,
                            kind = asset.kind,
                            contentType = asset.contentType,
                            // The sender's own name, so what lands in the other
                            // app is what the card said it was.
                            name = name
                        )
                        val opened = uri != null &&
                            GoodPostDownloads.openMediaFileExternally(context, uri, asset.contentType)
                        // Nothing opened: either the bytes could not be fetched —
                        // an expired lease, most likely — or no app on this device
                        // claims a PDF. The former is fixed by a fresh URL and the
                        // latter by nothing, so the retry is asked for either way
                        // and costs one request.
                        if (!opened) onRefresh()
                        opening = false
                    }
                },
                onLongClick = onLongClick
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (opening) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Wa.Accent
            )
        } else {
            Icon(
                Icons.Filled.PictureAsPdf,
                contentDescription = stringResource(R.string.goodpost_document_open),
                tint = Wa.Accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name ?: stringResource(R.string.goodpost_document),
                color = Wa.Text,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = waFileSize(asset.byteSize),
                color = Wa.BubbleTime,
                fontSize = 11.sp
            )
        }
        Icon(
            Icons.Filled.OpenInNew,
            contentDescription = null,
            tint = Wa.TextDim,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * A byte count as a person reads it.
 *
 * Binary units (KB = 1024 B), because that is what a file manager shows for the
 * same file — a card that said "1.0 MB" for something the device calls 1.2 MB
 * would look like the wrong file.
 */
internal fun waFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB" to 1024.0, "MB" to 1024.0 * 1024, "GB" to 1024.0 * 1024 * 1024)
    for ((suffix, scale) in units) {
        val value = bytes / scale
        // Above 10 the tenth does not help anybody; below it, a missing one makes
        // 1 KB and 1.9 KB look identical.
        if (value < 1024 || suffix == "GB") {
            return if (value < 10) String.format(Locale.US, "%.1f %s", value, suffix)
            else "${value.roundToInt()} $suffix"
        }
    }
    return "${bytes / (1024.0 * 1024 * 1024)} GB"
}

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
    /**
     * Where this card sits in its list (§22).
     *
     * The feed passes `Modifier.animateItem()`, which is what makes a post that
     * arrives while the screen is open — the one thing a channel does without the
     * reader asking (§12) — slide in and settle rather than appear mid-scroll
     * under a thumb that is still moving. Defaulted so a preview of a post, which
     * is not in a list, has nothing to pass.
     */
    modifier: Modifier = Modifier,
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
    onRefreshMedia: () -> Unit = {},
    /**
     * The room this post is drawn in, from the list that owns it (§18).
     *
     * The width a card gets, and the height the feed has — which is what a media
     * card's size is worked out from. Passed in rather than read from the window
     * because the list is what knows: its own insets make the card narrower than
     * the screen, and a tablet, a split screen and a landscape window are all just
     * different numbers here.
     */
    availableWidth: Dp = Dp.Unspecified,
    availableHeight: Dp = Dp.Unspecified,
    /**
     * Whether the screen is picking posts rather than reading them (§5).
     *
     * Read by one thing on this card — a document's tap, which would otherwise
     * open a file in the middle of a selection. Pictures and clips are told the
     * same thing by their own call site, through the `onOpenMedia` lambda they are
     * given; a document does its own opening, so it is told here.
     */
    selectionActive: Boolean = false
) {
    val context = LocalContext.current

    // §22: the selection states are animated rather than switched. A long press
    // is a deliberate gesture, and a card that jumps to a different colour makes
    // the press feel like it landed on something else; a fifth of a second makes
    // it read as the card answering. Both values are driven from `selected`, so
    // the tint and the layer always arrive together — a half-applied selection is
    // the one look that would be worse than no animation at all.
    val selectionFill by animateColorAsState(
        targetValue = if (selected) Wa.Selected else Color.Transparent,
        animationSpec = tween(durationMillis = WaMotion.SELECT_MS),
        label = "wa-selected-fill"
    )
    val scrim by animateFloatAsState(
        targetValue = if (selected) WaSelectionScrim else 0f,
        animationSpec = tween(durationMillis = WaMotion.SELECT_MS),
        label = "wa-selected-layer"
    )

    // The selection fill is on this wrapper rather than inside the bubble: the
    // bubble paints its own background over whatever it is given, so a highlight
    // passed inwards would be covered by the very container it is meant to mark.
    // That is also why the selection LAYER is painted in `drawWithContent` below
    // instead of being a tint behind the content — behind it is invisible.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(selectionFill)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .drawWithContent {
                drawContent()
                // §5: the layer a selected post gets, like a selected message in
                // a chat. Over the whole card — bubble, photo, video poster — so
                // "this one is picked" is visible from the picture alone.
                if (scrim > 0f) drawRect(color = Wa.Accent.copy(alpha = scrim))
            }
    ) {
        // How wide this post's media is, and therefore how wide the bubble is.
        //
        // `Unspecified` means the caller had no constraints to give (a preview that
        // is not in a list): the media then fills the bubble, which is how every
        // card was drawn before media sizing existed.
        val mediaWidth = if (availableWidth == Dp.Unspecified) {
            Dp.Unspecified
        } else {
            postMediaWidth(
                media = post.media,
                contentWidth = (availableWidth - WaPostPadding * 2).coerceAtLeast(0.dp),
                viewportHeight = availableHeight
            )
        }

        WaPostContainer(
            // The bubble is as wide as its media and no wider (§18). This is what
            // makes a portrait photo a small card: the photo keeps the whole of
            // itself, the card shrinks around it, and the green of the bubble is
            // never on show beside a picture that could not fill it.
            modifier = if (mediaWidth == Dp.Unspecified) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.width(mediaWidth + WaPostPadding * 2)
            }
        ) {
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
                        // §17: every post's words are monospace, and the size is a
                        // touch under the old proportional one because a monospace
                        // glyph is wider — 15sp here wrapped most paragraphs a
                        // line earlier for no gain in legibility.
                        fontFamily = WaPostFont,
                        fontSize = 14.sp,
                        // Looser than the default on purpose: a channel's update is
                        // a paragraph, and a paragraph set solid is the difference
                        // between a message and a block of text.
                        lineHeight = 20.sp,
                        // No inset of its own — the bubble owns the padding now,
                        // so the first line of a post and the media under it are
                        // aligned against the same inset, which is what makes a
                        // text post and a photo post line up in the feed.
                        modifier = Modifier
                    )
                }
            }

            // The media, under the words.
            //
            // Inside the bubble's padding rather than edge-to-edge, for the reason
            // the padding exists: the words and the pictures then start on the same
            // line. A post carries one KIND of media (§17 — the server refuses a
            // mix), so a clip and a photo never both appear here.
            //
            // Several photos are ONE album rather than a column of cards (§18): a
            // grid is what a chat does with a set of pictures, and it is also the
            // honest shape for a post whose pictures are a set — “here are four
            // shots of the same thing” reads as one block, while four full-height
            // cards read as four updates.
            val album = post.media.size >= 2 && post.media.all { it.isImage }
            if (album) {
                PostMediaAlbum(
                    media = post.media,
                    width = mediaWidth,
                    onOpenMedia = onOpenMedia,
                    onLongClick = onLongClick
                )
                Spacer(Modifier.height(MEDIA_GAP))
            } else {
                post.media.forEach { asset ->
                    if (asset.isImage) {
                        PostMedia(
                            asset = asset,
                            fallbackAspect = POST_PHOTO_ASPECT,
                            width = mediaWidth,
                            onOpenMedia = onOpenMedia,
                            onLongClick = onLongClick
                        )
                    } else if (asset.isDocument) {
                        PostDocumentCard(
                            asset = asset,
                            // A tap in selection mode belongs to the selection, the
                            // same rule the pictures and clips follow: opening a
                            // file while picking posts to delete would lose the
                            // picks.
                            openable = !selectionActive,
                            onLongClick = onLongClick,
                            // The signature on the URL has run out. Asking for the
                            // post again is the only retry that can succeed — the
                            // file is fine, the lease on reading it is not.
                            onRefresh = onRefreshMedia
                        )
                    } else {
                        InlineVideoCard(
                            url = asset.url.orEmpty(),
                            // The clip's OWN proportions, straight from the
                            // server's measurement. A 16:9 box around a portrait
                            // clip is the same empty side areas the photos had; a
                            // null (a file it never measured) is the one case the
                            // card already knows how to draw, in its own
                            // fixed-height box.
                            aspect = aspectOf(asset.width, asset.height),
                            onOpen = { from -> onOpenMediaAt(asset, from) },
                            onRefreshUrl = onRefreshMedia,
                            // §5: the card paints its own selection layer — see
                            // the sheets inside [InlineVideoCard].
                            selected = selected,
                            onLongPress = onLongClick
                        )
                    }
                    // One gap, whatever the media is: the words end, the picture
                    // starts, and the next picture or the meta row follows the
                    // same distance later.
                    Spacer(Modifier.height(MEDIA_GAP))
                }
            }


            if (post.isLink) {
                Spacer(Modifier.height(MEDIA_GAP))
                LinkChip(
                    label = post.linkTitle?.takeIf { it.isNotBlank() } ?: post.linkUrl.orEmpty(),
                    url = post.linkUrl.orEmpty(),
                    // A LINK is the one thing here that does belong to the
                    // browser: it is a page, unlike a signed URL to a file.
                    onOpen = { openLink(context, post.linkUrl) }
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

        // §9: the reactions hang off the bubble's bottom edge (see ReactionRow).
        // A SIBLING of the bubble and not a child of it: the bubble clips to its
        // own rounded rect, so a pill drawn inside it could never cross the
        // border it exists to be stuck to.
        if (post.reactions.isNotEmpty()) {
            ReactionRow(
                reactions = post.reactions,
                mine = post.myReaction,
                enabled = onReact != null && !reacting,
                onReact = onReact
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
 *
 * The placeholder is drawn UNDER the picture only until the picture exists, and
 * then it is gone. It used to stay for the length of the fade, which was right
 * while every picture filled its box — but a picture whose proportions the box
 * does not share (a story frame, a file the server never measured) is fitted
 * inside it, and the part left over was showing the placeholder's grey as a
 * frame around the photo. With the box now taking the FILE's proportions, the
 * leftover is at most a sliver, and that sliver shows the bubble instead.
 */
@Composable
private fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    /**
     * How the picture fills the box the caller gave it.
     *
     * `Fit` from the feed, because a post is a picture someone chose to send and
     * the whole of it is the message: a 4:3 box with a portrait photo CROPPED to
     * fill it shows the middle of a face and calls it the post. Fitted, the same
     * box holds the entire frame, letterboxed against the placeholder colour —
     * the card keeps one height for every post (which is what stops the feed
     * stepping up and down as the reader scrolls) and no picture loses an edge.
     */
    contentScale: ContentScale = ContentScale.Crop,
    /**
     * Where the picture sits inside the box, when the two are not the same shape.
     *
     * Centred by default — what a letterboxed video wants — and set to the start
     * by the feed, where a post's content is read from its left edge (§18).
     */
    alignment: Alignment = Alignment.Center,
    /**
     * The decoded frame's real pixel size, once there is one.
     *
     * Handed out so a caller can size its box from the picture rather than from
     * metadata about it — see [PostMedia]. Fired for a cached frame and a fetched
     * one alike, and once per frame rather than once per recomposition.
     */
    onDecoded: ((width: Int, height: Int) -> Unit)? = null
) {
    var bitmap by remember(url) { mutableStateOf(GoodPostImages.peek(url)) }

    LaunchedEffect(url) {
        if (bitmap == null) bitmap = GoodPostImages.load(url, maxWidthPx = 720)
    }

    val current = bitmap

    LaunchedEffect(current) {
        current?.let { onDecoded?.invoke(it.width, it.height) }
    }

    // ONE call site for the fade, above the branch, and that is the whole reason
    // this is a Box rather than an if/else (§22): a composable that is replaced
    // by another one starts its animation from the new value, so a picture that
    // swapped places with its own placeholder would arrive instantly. Keeping
    // both children composed and animating one opacity is what turns "the photo
    // is there now" into a fade.
    val alpha = waImageFade(loaded = current != null)

    Box(modifier = modifier) {
        // Empty until the frame has been decoded — which is what the placeholder
        // is for, and the one moment the box would otherwise be blank.
        if (current == null) {
            WaMediaPlaceholder(
                modifier = Modifier.matchParentSize(),
                icon = Icons.Filled.Link,
                label = if (url.isNullOrBlank()) {
                    stringResource(R.string.goodpost_media_unavailable)
                } else null
            )
        }

        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                // No clip of its own: the caller decides the shape, because the
                // caller is the one that knows what it is drawing (§6).
                modifier = Modifier
                    .matchParentSize()
                    .alpha(alpha)
            )
        }
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
    // The same composition a media share uses, so the two cannot drift apart.
    val shared = sharedPostText(message, link) ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shared)
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
