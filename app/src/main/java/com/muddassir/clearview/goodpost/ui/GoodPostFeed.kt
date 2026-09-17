package com.muddassir.clearview.goodpost.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostHomeUiState
import com.muddassir.clearview.goodpost.data.GoodPostMedia
import com.muddassir.clearview.goodpost.data.GoodPostPost
import com.muddassir.clearview.media.ui.RemoteImage
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Movie

/**
 * The aggregated feed (§4), post bubbles, and the composer (§8).
 *
 * Everything here is presentation: it reads state and calls the ViewModel. No
 * HTTP, no JSON and no file handling reaches this file (§35), which is why the
 * media blocks take what to show rather than what to fetch.
 *
 * A post is drawn as a bubble because that is what it is — one thing a channel
 * said, at a time — and the bubble is where the timestamp, the reactions and
 * the "who said this" header live, exactly as they do in a message.
 */
@Composable
internal fun PostsSection(
    state: GoodPostHomeUiState,
    onOpenChannel: (String) -> Unit,
    onEditPost: (GoodPostPost) -> Unit,
    onDeletePost: (GoodPostPost) -> Unit,
    onSaveMedia: (GoodPostMedia) -> Unit,
    onRevealMedia: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onReact: (GoodPostPost, String) -> Unit,
    onVote: (GoodPostPost, List<String>) -> Unit,
    onSeen: (String) -> Unit,
    onReport: (GoodPostPost) -> Unit
) {
    if (state.feed.isEmpty() && !state.loading) {
        Box(
            modifier = Modifier.fillMaxSize().background(Wa.Canvas),
            contentAlignment = Alignment.Center
        ) {
            WaEmptyState(
                title = stringResource(R.string.goodpost_empty_feed_title),
                note = stringResource(R.string.goodpost_empty_feed_note),
                actionLabel = stringResource(R.string.goodpost_refresh),
                onAction = onRetry
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Wa.Canvas),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        items(state.feed, key = { it.id }) { post ->
            PostBubble(
                post = post,
                // The feed spans channels, so each bubble names its own.
                showChannel = true,
                savedMediaIds = state.savedMediaIds,
                savingMediaId = state.savingMediaId,
                revealedMediaIds = state.revealedMediaIds,
                busy = state.busyPostId == post.id,
                reactionBusy = state.busyReactionPostId == post.id,
                voteBusy = state.votingPollId == post.engagement.poll?.id,
                onOpenChannel = onOpenChannel,
                onEdit = { onEditPost(post) },
                onDelete = { onDeletePost(post) },
                onSaveMedia = onSaveMedia,
                onRevealMedia = onRevealMedia,
                onReact = { reaction -> onReact(post, reaction) },
                onVote = { options -> onVote(post, options) },
                onSeen = { onSeen(post.id) },
                onReport = { onReport(post) }
            )
        }

        if (state.feedCursor != null) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.loadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Wa.Accent
                        )
                    } else {
                        WaTextAction(
                            text = stringResource(R.string.goodpost_more),
                            onClick = onLoadMore
                        )
                    }
                }
            }
        }
    }
}

/**
 * One post, as a bubble.
 *
 * Shared with the channel screen, so a post renders identically in the feed and
 * in the channel it came from — a second implementation would be a place for
 * the two to disagree about what a post looks like.
 *
 * The bubble is nearly the full width rather than being sized to its text,
 * which is the difference between a channel and a conversation: a channel
 * speaks in announcements, and an announcement that wraps at half the screen
 * while the next one is short reads as a mess.
 */
@Composable
internal fun PostBubble(
    post: GoodPostPost,
    showChannel: Boolean,
    savedMediaIds: Set<String>,
    savingMediaId: String?,
    /** Media the reader has tapped open this session, and so is no longer soft. */
    revealedMediaIds: Set<String> = emptySet(),
    busy: Boolean,
    reactionBusy: Boolean = false,
    voteBusy: Boolean = false,
    onOpenChannel: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSaveMedia: (GoodPostMedia) -> Unit,
    onRevealMedia: (String) -> Unit = {},
    onReact: (String) -> Unit = {},
    onVote: (List<String>) -> Unit = {},
    onSeen: () -> Unit = {},
    onReport: () -> Unit = {}
) {
    // §15: a post has been seen once it is on screen, and the server's own
    // dedupe window decides whether that look counts. Keyed by post id so
    // scrolling one post into view twice does not re-ping — the ViewModel keeps
    // that record, this only fires the effect once per row.
    //
    // Not pinged for a post this viewer manages: §13's interaction gate is
    // "must be following", and an owner cannot follow their own channel, so the
    // request would be a guaranteed refusal.
    LaunchedEffect(post.id) { if (!post.viewerCanManage) onSeen() }

    val reportLabel = stringResource(R.string.goodpost_report)
    val editLabel = stringResource(R.string.goodpost_post_edit)
    val deleteLabel = stringResource(R.string.goodpost_post_delete)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.94f)) {
            WaBubble(outgoing = false, modifier = Modifier.fillMaxWidth()) {
                if (showChannel && post.channel != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenChannel(post.channel.id) }
                            .padding(vertical = 2.dp, horizontal = 2.dp)
                    ) {
                        WaAvatar(name = post.channel.name, size = 22.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = post.channel.name,
                            color = Wa.NameTint,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        if (post.isEdited) {
                            Text(
                                text = stringResource(R.string.goodpost_post_edited),
                                color = Wa.TextDim,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                post.body?.let { body ->
                    Text(text = body, color = Wa.Text, fontSize = 15.sp)
                }

                post.linkUrl?.let { link ->
                    Spacer(Modifier.height(6.dp))
                    LinkPreview(url = link, title = post.linkTitle)
                }

                post.media.forEach { item ->
                    Spacer(Modifier.height(6.dp))
                    MediaBlock(
                        item = item,
                        saved = savedMediaIds.contains(item.id),
                        saving = savingMediaId == item.id,
                        // §10 taken literally: a preview is shown, but the full
                        // image stays soft until the reader asks for it by
                        // tapping — which is also the download.
                        blurred = !savedMediaIds.contains(item.id) &&
                            !revealedMediaIds.contains(item.id),
                        onReveal = { onRevealMedia(item.id) },
                        onSave = { onSaveMedia(item) }
                    )
                }

                // §14: a poll post's content IS its poll, so it renders inside
                // the bubble. It sits above the footer because the footer
                // belongs to the post that carries the poll, not to the poll.
                post.engagement.poll?.let { poll ->
                    Spacer(Modifier.height(6.dp))
                    PollCard(
                        poll = poll,
                        busy = voteBusy,
                        // §32: the server decides. An owner cannot follow their
                        // own channel, so a vote control of theirs could only be
                        // refused — they read their poll as a result instead.
                        interactive = !post.viewerCanManage,
                        onVote = onVote
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // §15's view count, in the footer where the reader is
                    // already looking for "how is this doing".
                    if (post.engagement.uniqueViewers > 0 || post.engagement.totalViews > 0) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = null,
                            tint = Wa.TextDim,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                R.string.goodpost_views,
                                post.engagement.uniqueViewers.toString()
                            ),
                            color = Wa.TextDim,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.width(10.dp))
                    }

                    if (!showChannel) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }

                    WaTimeLabel(text = waClock(post.createdAtMs))

                    if (busy) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = Wa.Accent
                        )
                    }

                    // §7 and §18. One menu rather than a row of buttons: the
                    // bubble is a message, and a message with three action
                    // labels under it stops reading as a message. The menu
                    // holds only what the server would accept for this viewer.
                    val items = buildList {
                        add(WaMenuItem(label = reportLabel, onClick = onReport))
                        if (post.viewerCanManage) {
                            add(WaMenuItem(label = editLabel, onClick = onEdit))
                            add(
                                WaMenuItem(
                                    label = deleteLabel,
                                    onClick = onDelete,
                                    destructive = true
                                )
                            )
                        }
                    }
                    WaOverflowMenu(items = items)
                }
            }

            // §13. Kept outside the bubble, the way a reaction sits on a
            // message's edge: the counts belong to the post, and a reader who
            // has not reacted should still see that other people have.
            ReactionBar(
                engagement = post.engagement,
                busy = reactionBusy,
                // §13's gate is "must be following", which an owner never is.
                interactive = !post.viewerCanManage,
                onReact = onReact
            )
        }
    }
}

/** The link card inside a bubble, the shape WhatsApp gives a shared URL. */
@Composable
private fun LinkPreview(url: String, title: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Wa.List)
            .padding(10.dp)
    ) {
        if (title != null && title.isNotBlank()) {
            Text(
                text = title,
                color = Wa.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Link,
                contentDescription = null,
                tint = Wa.TextDim,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = url,
                color = Wa.TextDim,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * An image that arrives soft and sharpens when it is asked for.
 *
 * Three states, and the middle one is the point:
 *
 *  * **Blurred** — the preview is fetched (a thumbnail-sized thing on a
 *    presigned URL) and softened, with a download badge over it. Nothing
 *    full-size has been pulled, which is what §10 asks for: previews by
 *    default, the real file only on request.
 *  * **Revealed** — tapped, so the image is drawn sharp immediately rather than
 *    waiting on the download, because the URL was already loaded under the blur
 *    and the bytes are in memory.
 *  * **Saved** — the file is on the device, so there is nothing left to blur and
 *    no badge to show.
 *
 * The blur is on the IMAGE and not on a scrim, so the picture's own colours show
 * through it — a dreamy first impression rather than a grey box — while staying
 * unmistakably not-yet-yours.
 */
@Composable
private fun BlurredImage(
    url: String,
    blurred: Boolean,
    saving: Boolean,
    onTap: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(shape)
            .clickable(enabled = blurred, onClick = onTap)
    ) {
        RemoteImage(
            url = url,
            // 14dp is enough to erase detail without erasing the picture: at
            // 25dp a photo of a person becomes a colour field and the preview
            // stops being a preview.
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurred) Modifier.blur(14.dp) else Modifier),
            contentScale = ContentScale.Crop,
            showLoadingSpinner = true
        )

        if (blurred) {
            // A soft darkening under the badge, so the icon and the word stay
            // legible whatever the picture underneath happens to be.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x33000000))
            )

            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.dp,
                        color = Wa.Text
                    )
                } else {
                    Icon(
                        Icons.Filled.FileDownload,
                        contentDescription = null,
                        tint = Wa.Text,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = stringResource(
                        if (saving) R.string.goodpost_media_fetching
                        else R.string.goodpost_media_tap_to_view
                    ),
                    color = Wa.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * One attachment inside a bubble.
 *
 * An image renders from its presigned URL — the preview §10 asks for. Video and
 * audio do NOT auto-play: there is no player wired to S3 media yet, and
 * pretending otherwise would show a control that does nothing. What they offer
 * is what actually works: save the file, then open it with the device's own
 * player from the saved copy.
 */
@Composable
private fun MediaBlock(
    item: GoodPostMedia,
    saved: Boolean,
    saving: Boolean,
    /** True while the image is still the soft preview the reader has not asked for. */
    blurred: Boolean = false,
    onReveal: () -> Unit = {},
    onSave: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            item.isImage && item.url != null -> BlurredImage(
                url = item.url,
                // Tapping a blurred image IS the download: it reveals this
                // copy for the session and asks for the local file at the same
                // time, so the sharp version is the one the app will have
                // offline next launch.
                blurred = blurred,
                saving = saving,
                onTap = {
                    onReveal()
                    if (!saved) onSave()
                }
            )

            item.isImage -> MediaPlaceholder(
                icon = Icons.Filled.Image,
                label = stringResource(R.string.goodpost_post_media_unavailable)
            )

            item.isVideo -> MediaPlaceholder(
                icon = Icons.Filled.Movie,
                label = waDescribeBytes(waKindOf(item.kind), item.byteSize)
            )

            else -> MediaPlaceholder(
                icon = Icons.Filled.Audiotrack,
                label = waDescribeBytes(waKindOf(item.kind), item.byteSize)
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = waDescribeBytes(waKindOf(item.kind), item.byteSize),
                color = Wa.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )

            when {
                saving -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Wa.Accent
                )

                saved -> Text(
                    text = stringResource(R.string.goodpost_post_saved),
                    color = Wa.Accent,
                    fontSize = 12.sp
                )

                // §10: saving is a deliberate action, and nothing is fetched
                // until this is tapped. That is why the button is the only way a
                // media byte reaches the device.
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onSave)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = Wa.Accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.goodpost_post_save_media),
                        color = Wa.Accent,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaPlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Wa.List)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Wa.TextDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text = label, color = Wa.TextDim, fontSize = 13.sp)
    }
}

/**
 * The composer (§8, §9), shaped like a status composer.
 *
 * The caption is the screen; everything else is a tray at the bottom. That
 * order is deliberate — writing is the common case, and a form that asks for a
 * link and a title before it will accept a sentence buries the thing most posts
 * are.
 *
 * Attachments upload on selection, not on publish: confirming the object takes
 * as long as the file does, and doing that after the user taps Post would make
 * the button look broken for the whole upload.
 */
@Composable
internal fun ComposerDialog(
    state: GoodPostHomeUiState,
    onBodyChange: (String) -> Unit,
    onLinkChange: (String) -> Unit,
    onLinkTitleChange: (String) -> Unit,
    onPickFile: (android.net.Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onPublish: () -> Unit,
    onDismiss: () -> Unit,
    onPollMode: (Boolean) -> Unit = {},
    onPollQuestion: (String) -> Unit = {},
    onPollOption: (Int, String) -> Unit = { _, _ -> },
    onAddPollOption: () -> Unit = {},
    onRemovePollOption: (Int) -> Unit = {},
    onPollMultiple: (Boolean) -> Unit = {}
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onPickFile) }

    // The link fields are folded away until they are wanted. A post is usually
    // a sentence, and two extra fields above the tray would make the common case
    // look like the rare one.
    var showLink by remember {
        mutableStateOf(state.composerLink.isNotBlank() || state.composerLinkTitle.isNotBlank())
    }

    val canPublish = !state.publishing &&
        !state.uploadingAttachment &&
        if (state.composerPollMode) {
            state.composerPollQuestion.isNotBlank() &&
                state.composerPollOptions.count { it.isNotBlank() } >= 2
        } else {
            state.composerBody.isNotBlank() ||
                state.composerLink.isNotBlank() ||
                state.composerAttachments.isNotEmpty()
        }

    WaFullScreen(onDismiss = onDismiss, background = Wa.Canvas) {
        WaTopBar(
            title = stringResource(R.string.goodpost_composer_title),
            navigation = {
                WaIconAction(
                    icon = Icons.Filled.Close,
                    description = stringResource(R.string.goodpost_cancel),
                    onClick = onDismiss
                )
            },
            actions = {
                if (!state.composerPollMode) {
                    WaIconAction(
                        icon = Icons.Filled.Link,
                        description = stringResource(R.string.goodpost_composer_link_hint),
                        onClick = { showLink = !showLink },
                        tint = if (showLink) Wa.Accent else Wa.Text
                    )
                }
            }
        )

        // Weighted rather than `fillMaxSize`, so the tray below keeps its place
        // at the bottom of the screen instead of being pushed off it by the
        // caption growing past one line.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            TextField(
                value = state.composerBody,
                onValueChange = onBodyChange,
                enabled = !state.publishing,
                minLines = 3,
                placeholder = {
                    Text(
                        text = stringResource(R.string.goodpost_composer_body_hint),
                        color = Wa.TextDim,
                        fontSize = 18.sp
                    )
                },
                textStyle = TextStyle(fontSize = 18.sp, color = Wa.Text),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = Wa.Accent
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (showLink && !state.composerPollMode) {
                Spacer(Modifier.height(8.dp))
                ComposerField(
                    value = state.composerLink,
                    onValueChange = onLinkChange,
                    placeholder = stringResource(R.string.goodpost_composer_link_hint),
                    enabled = !state.publishing
                )
                Spacer(Modifier.height(8.dp))
                ComposerField(
                    value = state.composerLinkTitle,
                    onValueChange = onLinkTitleChange,
                    placeholder = stringResource(R.string.goodpost_composer_link_title_hint),
                    enabled = !state.publishing
                )
            }

            // §14. Poll mode replaces the tray rather than adding to it: a poll
            // and a file cannot share a post (the server refuses
            // `poll_with_media`), and a control that could only build a refused
            // request should not be on screen.
            if (state.composerPollMode) {
                Spacer(Modifier.height(12.dp))
                ComposerField(
                    value = state.composerPollQuestion,
                    onValueChange = onPollQuestion,
                    placeholder = stringResource(R.string.goodpost_poll_question),
                    enabled = !state.publishing
                )

                Spacer(Modifier.height(8.dp))

                state.composerPollOptions.forEachIndexed { index, option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            ComposerField(
                                value = option,
                                onValueChange = { onPollOption(index, it) },
                                placeholder = stringResource(
                                    R.string.goodpost_poll_option,
                                    index + 1
                                ),
                                enabled = !state.publishing
                            )
                        }

                        // Removing is offered only above the floor of two: a
                        // one-option poll is not a question, and the server
                        // refuses it.
                        if (state.composerPollOptions.size > 2) {
                            WaIconAction(
                                icon = Icons.Filled.RemoveCircleOutline,
                                description = stringResource(R.string.goodpost_poll_remove_option),
                                enabled = !state.publishing,
                                onClick = { onRemovePollOption(index) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    WaTextAction(
                        text = stringResource(R.string.goodpost_poll_add_option),
                        enabled = !state.publishing,
                        onClick = onAddPollOption
                    )
                    Spacer(Modifier.weight(1f))
                    Checkbox(
                        checked = state.composerPollMultiple,
                        onCheckedChange = { onPollMultiple(it) },
                        enabled = !state.publishing
                    )
                    Text(
                        text = stringResource(R.string.goodpost_poll_multiple),
                        color = Wa.TextDim,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            state.composerAttachments.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.isImage && item.url != null) {
                        RemoteImage(
                            url = item.url,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            if (item.isVideo) Icons.Filled.Movie else Icons.Filled.Audiotrack,
                            contentDescription = null,
                            tint = Wa.TextDim,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = waDescribeBytes(waKindOf(item.kind), item.byteSize),
                        color = Wa.Text,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    WaIconAction(
                        icon = Icons.Filled.Close,
                        description = stringResource(R.string.goodpost_remove_attachment),
                        enabled = !state.publishing,
                        onClick = { onRemoveAttachment(item.id) }
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            if (state.uploadingAttachment) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Wa.Accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.goodpost_composer_uploading),
                        color = Wa.TextDim,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            state.messageCode?.let { code ->
                Spacer(Modifier.height(12.dp))
                WaErrorNotice(code)
            }
        }

        // The tray: three ways to add something, then the send button — the
        // order a status composer uses, with the send action last so it never
        // moves.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Wa.Bar)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!state.composerPollMode) {
                WaIconAction(
                    icon = Icons.Filled.AttachFile,
                    description = stringResource(R.string.goodpost_composer_attach),
                    enabled = !state.publishing && !state.uploadingAttachment,
                    onClick = { picker.launch("*/*") }
                )
            }

            WaIconAction(
                icon = Icons.Filled.Poll,
                description = stringResource(R.string.goodpost_composer_poll),
                tint = if (state.composerPollMode) Wa.Accent else Wa.Text,
                enabled = !state.publishing,
                onClick = { onPollMode(!state.composerPollMode) }
            )

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (canPublish) Wa.Accent else Wa.Pressed)
                    .clickable(enabled = canPublish, onClick = onPublish),
                contentAlignment = Alignment.Center
            ) {
                if (state.publishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = Wa.Canvas
                    )
                } else {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = stringResource(R.string.goodpost_publish),
                        tint = if (canPublish) Wa.Canvas else Wa.TextDim,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/** A one-line field inside the composer's dark surface. */
@Composable
private fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Wa.Bar)
            .border(1.dp, Wa.Divider, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp)
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(text = placeholder, color = Wa.TextDim, fontSize = 15.sp) },
            textStyle = TextStyle(fontSize = 15.sp, color = Wa.Text),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = Wa.Accent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The edit form for one post's text (§7). */
@Composable
internal fun EditPostDialog(
    body: String,
    busy: Boolean,
    errorCode: String?,
    onBodyChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    WaDialog(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.goodpost_post_edit),
            color = Wa.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(12.dp))

        ComposerField(
            value = body,
            onValueChange = onBodyChange,
            placeholder = stringResource(R.string.goodpost_composer_body_hint),
            enabled = !busy
        )

        if (errorCode != null) {
            Spacer(Modifier.height(12.dp))
            WaErrorNotice(errorCode)
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            WaTextAction(
                text = stringResource(R.string.goodpost_cancel),
                enabled = !busy,
                onClick = onDismiss
            )
            Spacer(Modifier.width(8.dp))
            WaTextAction(
                text = stringResource(R.string.goodpost_save),
                enabled = !busy,
                onClick = onSave
            )
        }
    }
}
