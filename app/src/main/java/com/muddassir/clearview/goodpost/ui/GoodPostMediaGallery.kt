package com.muddassir.clearview.goodpost.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostMediaItem
import com.muddassir.clearview.goodpost.data.GoodPostVideoPoster

/**
 * Everything a channel has posted as media (§13).
 *
 * The strip on the channel's information page is a preview of this: three or
 * four thumbnails that say "there is more here". This is the more, and it is a
 * screen because of what a collection needs that a preview does not — paging
 * back through a channel's history, opening one item large, and selecting
 * several to remove.
 *
 * ## Selection is the same gesture as everywhere else in the tab
 *
 * Tap opens, long press selects, and once something is selected a tap adds to
 * the selection instead. It is the gesture the channel list and the feed already
 * use (§5), so nothing here has to be learned twice. Delete appears only for an
 * account that may publish to this channel, and it is checked against the
 * server's own answer about the channel rather than against a button being
 * hidden.
 *
 * ## Why delete removes POSTS
 *
 * A file is not a row a reader can delete: media belongs to the update that
 * posted it, and the update is what the server can remove. Selecting two
 * attachments from one carousel therefore removes that one update, and the
 * confirmation says so, because "delete" on a thumbnail reading as "delete three
 * things" when it deletes one post would be a lie told by the UI.
 */
@Composable
internal fun GoodPostMediaGallery(
    state: GoodPostUiState,
    channelId: String,
    viewModel: GoodPostViewModel
) {
    val channel = state.channel
    val canManage = state.canManage(channelId)

    // The gallery's own page load. Keyed on the channel so arriving here always
    // asks for the current page, and so a second channel does not inherit this
    // one's grid — the same rule the feed follows.
    LaunchedEffect(channelId) { viewModel.loadMedia(channelId) }

    // Warm the first screenful, so the grid draws instead of filling in.
    LaunchedEffect(state.media) {
        GoodPostImages.prefetch(
            urls = state.media.filter { !it.isVideo }.mapNotNull { it.url },
            maxWidthPx = 360,
            limit = 9
        )
    }

    // Nothing in this grid is deleted on the tap (§5, §17). See the dialog below.
    var confirmingDelete by remember(channelId) { mutableStateOf(false) }

    // Which item the viewer is showing, by id. Held as an id rather than as the
    // item so a refreshed page hands the viewer a fresh signed URL instead of a
    // stale one copied at the moment of the tap.
    var viewing by remember(channelId) { mutableStateOf<String?>(null) }
    val viewingItem = state.media.firstOrNull { it.id == viewing }

    LaunchedEffect(viewing, viewingItem) {
        if (viewing != null && viewingItem == null) viewing = null
    }

    Box(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.selectedMediaIds.isEmpty()) {
                WaTopBar(
                    title = stringResource(R.string.goodpost_media_and_links),
                    subtitle = countLabel(state.media.size, canManage),
                    navigation = {
                        WaIconAction(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            description = stringResource(R.string.goodpost_back),
                            onClick = { viewModel.back() }
                        )
                    }
                )
            } else {
                // ONE delete, and a dialog behind it. It used to be two icons —
                // "empty this phone" and "remove the posts" — which is two
                // answers to one question, chosen by which icon a thumb landed on
                // (the reader's was the one on the left). The dialog asks the
                // question.
                WaSelectionBar(
                    count = state.selectedMediaIds.size,
                    onClose = viewModel::clearMediaSelection,
                    actions = {
                        WaIconAction(
                            icon = Icons.Filled.Delete,
                            description = stringResource(R.string.goodpost_delete),
                            tint = if (canManage) Wa.Danger else Wa.Text,
                            onClick = { confirmingDelete = true }
                        )
                    }
                )
            }

            if (state.mediaError != null && state.media.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    WaErrorNotice(state.mediaError)
                }
                return@Column
            }

            if (state.media.isEmpty() && state.mediaLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = Wa.Accent
                    )
                }
                return@Column
            }

            if (state.media.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    WaEmptyState(
                        title = stringResource(R.string.goodpost_no_media_title),
                        note = stringResource(R.string.goodpost_no_media_note)
                    )
                }
                return@Column
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.media, key = { it.id }) { item ->
                    MediaCell(
                        item = item,
                        // §22: deleting files from this device removes them from
                        // the grid, and a tile going away should close the gap
                        // rather than make the rest jump into it.
                        modifier = Modifier.animateItem(),
                        selected = state.selectedMediaIds.contains(item.id),
                        selectionActive = state.selectedMediaIds.isNotEmpty(),
                        onClick = {
                            if (state.selectedMediaIds.isNotEmpty()) {
                                viewModel.toggleMediaSelected(item.id)
                            } else {
                                viewing = item.id
                            }
                        },
                        onLongClick = { viewModel.toggleMediaSelected(item.id) }
                    )
                }

                if (state.mediaCursor != null) {
                    // One cell spanning the grid, because a "load more" that is
                    // one third of a row reads as an item rather than a control.
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.mediaLoadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Wa.Accent
                                )
                            } else {
                                WaTextAction(
                                    text = stringResource(R.string.goodpost_more),
                                    onClick = viewModel::loadMoreMedia
                                )
                            }
                        }
                    }
                }
            }
        }

        if (viewingItem != null) {
            MediaViewer(
                kind = viewingItem.kind,
                url = viewingItem.url.orEmpty(),
                contentType = null,
                aspect = aspectOf(viewingItem.width, viewingItem.height),
                // Known before the tap: the server measured the clip when it was
                // uploaded, so the transport can show its length from the first
                // frame instead of an ellipsis while the player reads the file's
                // index off the network (§9).
                knownDurationMs = viewingItem.durationMs ?: 0L,
                onClose = { viewing = null },
                // A gallery item knows the post it came from, so the same
                // re-read the feed does is available here.
                onExpired = { viewModel.refreshPost(viewingItem.postId) },
                // No resumeKey: nothing behind this is playing. A grid cell is a
                // thumbnail, so there is no playhead to hand back to it.
                onDeleted = { viewing = null }
            )
        }

        if (confirmingDelete) {
            val count = state.selectedMediaIds.size
            WaDeleteDialog(
                title = if (count == 1) {
                    stringResource(R.string.goodpost_delete_media_title)
                } else {
                    stringResource(R.string.goodpost_delete_media_title_many, count)
                },
                message = stringResource(
                    if (canManage) {
                        R.string.goodpost_delete_media_note_admin
                    } else {
                        R.string.goodpost_delete_media_note
                    }
                ),
                // Checked against the server's own answer about this channel
                // rather than against a button being visible: hiding an option is
                // not an authorization, and the request behind it is what the
                // server actually refuses.
                canDeleteForEveryone = canManage,
                onDismiss = { confirmingDelete = false },
                onDeleteForMe = {
                    confirmingDelete = false
                    viewModel.deleteSelectedMediaFromDevice()
                },
                onDeleteForEveryone = {
                    confirmingDelete = false
                    viewModel.deleteSelectedMedia()
                }
            )
        }
    }
}

/** One square in the grid. */
@Composable
private fun MediaCell(
    item: GoodPostMediaItem,
    modifier: Modifier = Modifier,
    selected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // A clip is never asked of the IMAGE loader: its bytes are not a picture, so
    // the decode could only fail — after the whole file had been downloaded into
    // memory, which is what made a grid of clips slow to scroll and slow to open
    // one (§24). A clip gets its own still instead (§22), so a tile says which
    // video it is rather than wearing a camera icon.
    var bitmap by remember(item.url) {
        mutableStateOf(
            if (item.isVideo) GoodPostVideoPoster.peek(item.url) else GoodPostImages.peek(item.url)
        )
    }

    LaunchedEffect(item.url, item.isVideo) {
        if (bitmap != null) return@LaunchedEffect
        bitmap = if (item.isVideo) {
            GoodPostVideoPoster.load(item.url, widthPx = 360)
        } else {
            GoodPostImages.load(item.url, maxWidthPx = 360)
        }
    }

    val current = bitmap
    // §22: a tile's picture fades in over the icon that stands for it, so a grid
    // filling in at scrolling speed does not flicker (§13).
    val alpha = waImageFade(loaded = current != null)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Wa.Pressed)
            // A long press on something that cannot be selected (a media strip
            // item the account may not manage) still has to do SOMETHING
            // predictable, and selecting it is the only honest option: the
            // selection bar simply offers no destructive action for it.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        // Drawn underneath the picture for the length of the fade, so the tile is
        // never briefly empty — and it stays as the whole of the tile for a clip
        // whose still cannot be read, which is the honest placeholder for one.
        Icon(
            imageVector = if (item.isVideo) Icons.Filled.Videocam else Icons.Filled.PlayArrow,
            contentDescription = stringResource(R.string.goodpost_media_unavailable),
            tint = Wa.TextDim,
            modifier = Modifier.size(22.dp)
        )

        if (current != null) {
            androidx.compose.foundation.Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(alpha)
            )
        }

        if (item.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                if (item.durationMs != null && item.durationMs > 0) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = clockOf(item.durationMs),
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }

        if (selected || selectionActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (selected) Wa.Accent.copy(alpha = 0.32f) else Color.Black.copy(alpha = 0.28f)
                    )
            )
        }

        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.goodpost_selected),
                tint = Wa.Accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
            )
        }
    }
}

/** The wire's two numbers as one ratio, or null when the server never measured. */
internal fun aspectOf(width: Int?, height: Int?): Float? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    return width.toFloat() / height.toFloat()
}

/** "12 items", or the count plus what the account may do with them. */
@Composable
private fun countLabel(count: Int, canManage: Boolean): String {
    if (count == 0) return stringResource(R.string.goodpost_no_media_title)
    val items = pluralStringResource(R.plurals.goodpost_media_count, count, count)
    return if (canManage) {
        stringResource(R.string.goodpost_media_count_manageable, items)
    } else {
        items
    }
}
