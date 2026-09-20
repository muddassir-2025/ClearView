package com.muddassir.clearview.goodpost.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostAttachment
import com.muddassir.clearview.goodpost.data.GoodPostFormat
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostUploadState
import com.muddassir.clearview.goodpost.data.applyGoodPostFormat
import com.muddassir.clearview.goodpost.data.readGoodPostAttachment

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
 * The strip under a channel's feed (§21) — WhatsApp's own message bar.
 *
 * ## Two ways to write, and what each is for
 *
 *  * **The field itself.** Tap it and type: a line, a link, a quick notice. The
 *    send control is inside the field's right edge, where a thumb already is,
 *    and it appears only once there is something to send — a disabled circle in
 *    the message bar of every channel is furniture. A picture can be attached
 *    from here too, and its preview sits above the field, so **the page is not
 *    required to send one**: a photo with a line under it is the commonest post
 *    a channel makes, and making it take two screens was the wrong tax.
 *  * **The pencil beside it.** The posting PAGE (§21): the whole screen for a
 *    paragraph, with the formatting bar, the clipboard actions and the
 *    attachment previews that a one-line strip has no room for.
 *
 * ## One draft, two surfaces
 *
 * The field edits the composer's own body, not a copy of it, so the page opens
 * with whatever the strip already holds and the strip comes back with whatever
 * the page left. Closing the page without publishing keeps that draft (see
 * [GoodPostViewModel.cancelCompose]) rather than emptying the field behind it.
 */
@Composable
internal fun ChannelComposeBar(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    val context = LocalContext.current
    val body = state.composerBody
    val attachments = state.composerAttachments

    // The same way to a file the posting page uses, and the same reader it hands
    // them to: an attachment picked here is an attachment of the same draft, so
    // the page would open with it already there.
    val openAttach = rememberAttachmentPicker(
        room = (COMPOSER_MAX_ATTACHMENTS - attachments.size).coerceAtLeast(0),
        onAttached = viewModel::attachMedia,
        onUnsupported = viewModel::reportUnsupportedMedia
    )

    // The picker's own count is what caps a post, so it is not offered once the
    // draft is already full — a picker that can only refuse is a control that lies.
    val canAttach = state.composerMediaAvailable &&
        attachments.size < COMPOSER_MAX_ATTACHMENTS

    // A file still on its way, or one that failed, has no media id — so sending
    // now would either drop it or post a file the server never stored.
    val canSend = (body.isNotBlank() || attachments.isNotEmpty()) &&
        !state.composerBusy &&
        attachments.all { it.mediaId != null }

    /** Whether the reader has written or picked anything that could be sent. */
    val hasDraft = body.isNotBlank() || attachments.isNotEmpty()

    /** A file of the draft is still uploading — the send waits for every one. */
    val uploading = attachments.any { it.state is GoodPostUploadState.Uploading }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Wa.Canvas)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // What is about to be sent, above the field, as the pictures themselves.
        // The posting page's own previews at a smaller size, because this is one
        // bar rather than a screen: the reader sees the photo they picked, its
        // upload state and the way to drop it without leaving the feed.
        if (attachments.isNotEmpty()) {
            ComposerMediaStrip(
                attachments = attachments,
                onRemove = viewModel::removeMedia,
                onRetry = viewModel::retryMedia,
                tileSize = STRIP_TILE
            )
            Spacer(Modifier.height(6.dp))
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        // Bottom, not centre: the field grows upward as the reader types, and a
        // row that re-centred its own controls on every new line looks like it is
        // sliding out from under the thumb.
        verticalAlignment = Alignment.Bottom
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Wa.Bar),
            verticalAlignment = Alignment.Bottom
        ) {
            BasicTextField(
                value = body,
                // Straight to the ViewModel: this field IS the composer's body,
                // so there is no second copy to keep in step.
                onValueChange = viewModel::onComposerBodyChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                textStyle = TextStyle(
                    color = Wa.Text,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontFamily = WaPostFont
                ),
                cursorBrush = SolidColor(Wa.Accent),
                // A long line wraps instead of scrolling sideways: the strip is
                // for a sentence or two, and past five lines the reader wants the
                // page anyway.
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                ),
                decorationBox = { field ->
                    Box {
                        if (body.isEmpty()) {
                            Text(
                                text = stringResource(R.string.goodpost_write_update),
                                color = Wa.TextDim,
                                fontSize = 15.sp,
                                fontFamily = WaPostFont,
                                maxLines = 1
                            )
                        }
                        field()
                    }
                }
            )

            // The way to a file, inside the field's own edge, where the
            // platform's messaging apps put it. No disc under it: the bar has ONE
            // filled circle and this is not it.
            //
            // A paperclip rather than a photograph, because the button is not a
            // promise about pictures: it is the way to whatever the post is
            // carrying, and an icon of one file type reads as a limit that is not
            // there. The picker behind it decides what can be chosen.
            if (canAttach) {
                Box(
                    modifier = Modifier
                        .padding(end = 2.dp, bottom = 4.dp)
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable(onClick = openAttach),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.goodpost_add_media),
                        tint = Wa.TextDim,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }

        }

        Spacer(Modifier.width(8.dp))

        // ONE circle, and it is whatever the moment calls for (§21).
        //
        // Empty, it is the way to the posting page — the pencil, where a chat puts
        // its microphone. The moment there is a draft to send, it becomes Send: a
        // bar does not need a write button beside a send button, and two filled
        // circles side by side is the look that produced this rule. The page is
        // still one tap away, from the same place, whenever the field is empty.
        when {
            !hasDraft -> Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Wa.StampRecent)
                    .clickable { viewModel.openFullComposer() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.goodpost_open_composer),
                    tint = Wa.OnAccent,
                    modifier = Modifier.size(21.dp)
                )
            }

            else -> Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    // A fill that still says "not yet" while a file is uploading:
                    // the circle is in its sending colour only when a tap would
                    // actually send something.
                    .background(if (canSend) Wa.StampRecent else Wa.Bar)
                    .clickable(enabled = canSend) { viewModel.publish() },
                contentAlignment = Alignment.Center
            ) {
                if (uploading) {
                    // The one thing that would otherwise be missing from the bar:
                    // a picture is on its way and the send is not available yet.
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Wa.TextDim
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.goodpost_send),
                        tint = if (canSend) Wa.OnAccent else Wa.TextDim,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
    }
}

/**
 * The posting page (§21), redesigned around WhatsApp's own composer.
 *
 * A full screen rather than a strip under the feed, and the reason is the one
 * thing the strip got wrong: writing a channel update is not a chat reply. It is
 * usually a paragraph, it often carries a picture, and it has four formats and
 * the clipboard available for the words being written. Every one of those was
 * competing for the height of a single row at the bottom of a scrolling list,
 * which is why editing a long update meant typing into a window five lines tall.
 *
 * ## What it borrows from WhatsApp
 *
 *  * **A screen of its own**, entered from the feed and left by publishing,
 *    saving or closing — never a mode of the list underneath.
 *  * **The text IS the page.** The field fills the surface, scrolls with the
 *    writer, and the media being attached sits above it as a preview rather than
 *    as a row of thumbnails squeezed under an input bar.
 *  * **Attachments are previewed**, not named: the picture the reader picked is
 *    the picture on screen, with its upload state drawn on the tile.
 *  * **One send control**, at the bottom right, where a thumb already looks.
 *
 * ## What it keeps from this app
 *
 * The formatting bar still appears only WITH a selection, the clipboard actions
 * are still the platform's own words offered in our layout, and a publish is
 * still refused while a file is uploading or has failed — the honest behaviour
 * rather than a convenience.
 */
@Composable
internal fun GoodPostComposer(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    val context = LocalContext.current
    val editing = state.editingPostId != null

    // Back closes the composer rather than closing the app or popping the feed
    // underneath it. Registered here, so it wins over the tab's own handler.
    BackHandler(enabled = true) { viewModel.cancelCompose() }

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
    val fieldValue = TextFieldValue(
        annotatedString = parseGoodPostText(body),
        selection = TextRange(selectionStart, selectionEnd)
    )
    val hasSelection = selectionStart != selectionEnd

    /**
     * What Android's selection menu would have offered (§7).
     *
     * Compose hands these to a `TextToolbar` when it wants to SHOW that menu. Our
     * toolbar captures them instead of displaying anything, and this screen
     * invokes them, so Cut / Copy / Paste / Select all still work while the
     * platform's own bubble is gone.
     */
    var clipboardActions by remember { mutableStateOf<List<GoodPostContextMenuAction>>(emptyList()) }
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

    val openAttach = rememberAttachmentPicker(
        room = (COMPOSER_MAX_ATTACHMENTS - state.composerAttachments.size).coerceAtLeast(0),
        onAttached = viewModel::attachMedia,
        onUnsupported = viewModel::reportUnsupportedMedia
    )

    val canSubmit = (body.isNotBlank() || state.composerAttachments.isNotEmpty()) &&
        !state.composerBusy &&
        (editing || state.composerAttachments.all { it.mediaId != null })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Wa.Canvas)
            .imePadding()
    ) {
        ComposerTopBar(
            title = stringResource(
                if (editing) R.string.goodpost_edit_post else R.string.goodpost_write_update
            ),
            onClose = viewModel::cancelCompose
        )

        // The words and the media scroll together, the way a note does: the field
        // is allowed to grow past the screen and the whole page moves.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (state.composerAttachments.isNotEmpty()) {
                ComposerMediaStrip(
                    attachments = state.composerAttachments,
                    onRemove = viewModel::removeMedia,
                    onRetry = viewModel::retryMedia
                )
                Spacer(Modifier.height(12.dp))
            }

            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp)) {
                if (body.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (editing) R.string.goodpost_edit_post
                            else R.string.goodpost_write_update
                        ),
                        color = Wa.TextDim,
                        fontSize = 16.sp,
                        fontFamily = WaPostFont,
                        lineHeight = 23.sp
                    )
                }
                // The field itself is where Compose installs the selection menu,
                // so the replacement has to be provided around it (§7). Both
                // locals, because Compose picks between them by gesture.
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
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            color = Wa.Text,
                            fontFamily = WaPostFont,
                            fontSize = 16.sp,
                            lineHeight = 23.sp
                        ),
                        cursorBrush = SolidColor(Wa.StampRecent)
                    )
                }
            }

            // §7: the controls appear WITH a selection, and only then. They are in
            // the page rather than floating over the line being edited.
            if (hasSelection) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GoodPostFormatMenu(
                        isActive = { format -> format.wraps(selectedText) },
                        onToggle = ::applyFormat
                    )
                    GoodPostMenuDivider()
                    GoodPostClipboardBar(clipboardActions)
                }
            }
        }

        ComposerBottomBar(
            editing = editing,
            mediaAvailable = state.composerMediaAvailable,
            attachmentCount = state.composerAttachments.size,
            uploading = state.composerAttachments.any { it.state is GoodPostUploadState.Uploading },
            failed = state.composerAttachments.any { it.state is GoodPostUploadState.Failed },
            canSubmit = canSubmit,
            busy = state.composerBusy,
            onAttach = openAttach,
            onSend = viewModel::publish
        )
    }
}

/** The composer's own bar: a close on the left, what is being written in the middle. */
@Composable
private fun ComposerTopBar(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(Wa.TopBar)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WaIconAction(
            icon = Icons.Filled.Close,
            description = stringResource(R.string.goodpost_cancel),
            onClick = onClose
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            color = Wa.Text,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * The attachment bar at the bottom of the posting page.
 *
 * Attach on the left, the state of what is attached beside it, and the one send
 * control on the right. The hint is not decoration: "a file is still uploading"
 * is the difference between a refused send the reader can act on and a Post
 * button that appears to be broken.
 */
@Composable
private fun ComposerBottomBar(
    editing: Boolean,
    mediaAvailable: Boolean,
    attachmentCount: Int,
    uploading: Boolean,
    failed: Boolean,
    canSubmit: Boolean,
    busy: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit
) {
    val sendFill by animateColorAsState(
        targetValue = if (canSubmit) Wa.StampRecent else Wa.Bar,
        animationSpec = tween(durationMillis = 150),
        label = "wa-send-fill"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Wa.Canvas)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!editing && mediaAvailable && attachmentCount < COMPOSER_MAX_ATTACHMENTS) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Wa.Bar)
                    .clickable(enabled = !busy, onClick = onAttach),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    // The same paperclip the bar under the feed wears, so the way
                    // to add a file looks the same in both places it exists.
                    Icons.Filled.AttachFile,
                    contentDescription = stringResource(R.string.goodpost_add_media),
                    tint = if (busy) Wa.TextDim else Wa.Accent,
                    modifier = Modifier.size(23.dp)
                )
            }
        } else {
            Spacer(Modifier.width(46.dp))
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = when {
                uploading -> stringResource(R.string.goodpost_attachment_uploading)
                failed -> stringResource(R.string.goodpost_attachment_failed)
                editing -> stringResource(R.string.goodpost_edit_post)
                else -> stringResource(R.string.goodpost_write_update)
            },
            modifier = Modifier.weight(1f),
            color = if (failed) Wa.Danger else Wa.TextDim,
            fontSize = 13.sp,
            maxLines = 1
        )

        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(sendFill)
                .clickable(enabled = canSubmit, onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
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
                    modifier = Modifier.size(23.dp)
                )
            }
        }
    }
}

/**
 * The files attached so far, as pictures rather than names (§21).
 *
 * The picture the reader picked IS the preview: it is decoded from the local
 * `content://` handle with [GoodPostImages.loadLocal], so it appears the instant
 * it is chosen and does not wait for an upload or a signed URL. A clip cannot be
 * shown as a still without decoding a frame from it, which is why it wears a play
 * mark instead — a tile that says which of the picks is the video rather than a
 * guess at its content.
 *
 * Each tile also carries its own upload state, because that is what decides
 * whether the post can be sent: a quiet spinner while the bytes are on their way,
 * a red mark over a file the server refused, and a tick once it is confirmed.
 */
@Composable
private fun ComposerMediaStrip(
    attachments: List<GoodPostAttachment>,
    onRemove: (String) -> Unit,
    /** Sends one that failed again — see [GoodPostViewModel.retryMedia]. */
    onRetry: (GoodPostAttachment) -> Unit = {},
    /**
     * The tile's edge, so both surfaces that show this strip can size it.
     *
     * The posting page is a screen and gives each preview the room to be looked
     * at (104dp); the strip under the feed is a bar, where a picture is a reminder
     * of what is about to be sent rather than something to inspect, and 64dp holds
     * four of them inside the width of the field.
     */
    tileSize: Dp = COMPOSER_TILE
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            ComposerAttachmentTile(
                attachment = attachment,
                onRemove = { onRemove(attachment.uri) },
                onRetry = { onRetry(attachment) },
                size = tileSize
            )
        }
    }
}

/**
 * The document types the picker offers.
 *
 * One, matching the server's own allow-list exactly. A picker that offered more
 * would be offering files the upload endpoint refuses, and a sheet that offers a
 * refusal is worse than one with a single honest option.
 */
private val ATTACH_DOCUMENT_TYPES = arrayOf("application/pdf")

/**
 * The way to a file, shared by both surfaces that can attach one (§21, §22).
 *
 * ## Why there is a chooser at all
 *
 * Android's photo picker cannot show a PDF, and a document picker cannot show a
 * camera roll the way the photo picker does. Two launchers behind one button is
 * the only way to offer both without making the button lie about what it opens —
 * which is what the paperclip replaced: an icon of one file type reads as a
 * promise about what can be sent.
 *
 * ## What it returns
 *
 * A function that opens the chooser. Everything else — which picker runs, what a
 * picked URI becomes, and what happens to one this app will not send — is decided
 * here, so the strip under the feed and the posting page cannot drift apart on
 * any of it.
 *
 * [room] is the draft's remaining space. It is applied to what was picked rather
 * than left to the picker, whose ceiling is per LAUNCH: without it, a second trip
 * uploads files the server refuses with `too_many_media` only after every one of
 * them has been sent.
 */
@Composable
private fun rememberAttachmentPicker(
    room: Int,
    onAttached: (GoodPostAttachment) -> Unit,
    onUnsupported: () -> Unit
): () -> Unit {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }

    val accept = remember(room, onAttached, onUnsupported) {
        { uris: List<Uri> ->
            uris.take(room.coerceAtLeast(0)).forEach { uri ->
                val attachment = readGoodPostAttachment(context, uri)
                if (attachment == null) onUnsupported() else onAttached(attachment)
            }
        }
    }

    val photos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(COMPOSER_MAX_ATTACHMENTS)
    ) { uris: List<Uri> -> accept(uris) }

    val documents = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> accept(uris) }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(text = stringResource(R.string.goodpost_attach_title)) },
            text = {
                Column {
                    GoodPostAttachOption(
                        icon = Icons.Filled.AddPhotoAlternate,
                        label = stringResource(R.string.goodpost_attach_photos),
                        onClick = {
                            choosing = false
                            photos.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                )
                            )
                        }
                    )
                    GoodPostAttachOption(
                        icon = Icons.Filled.PictureAsPdf,
                        label = stringResource(R.string.goodpost_attach_document),
                        onClick = {
                            choosing = false
                            documents.launch(ATTACH_DOCUMENT_TYPES)
                        }
                    )
                }
            },
            // The dialog is the choice; there is nothing to confirm. Dismissing
            // is the way out, so the buttons below the list are deliberately
            // absent rather than a Cancel that says the same thing.
            confirmButton = {}
        )
    }

    return { choosing = true }
}

/** One row of the attach chooser. */
@Composable
private fun GoodPostAttachOption(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Wa.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(text = label, color = Wa.Text, fontSize = 15.sp)
    }
}

/** The preview size on the posting page. */
private val COMPOSER_TILE = 104.dp

/** The preview size in the strip under the feed. */
private val STRIP_TILE = 64.dp

/** One attachment: the picture itself, its state, and the way to take it off. */
@Composable
private fun ComposerAttachmentTile(
    attachment: GoodPostAttachment,
    onRemove: () -> Unit,
    onRetry: () -> Unit = {},
    size: Dp = COMPOSER_TILE
) {
    val context = LocalContext.current

    // The decoded pick, keyed on the URI so choosing a second file replaces this
    // one's preview rather than showing the first file's picture twice. Nothing
    // is peeked: a picked file is a local handle rather than a signed URL, so
    // there is no cache to hit and the decode is a few milliseconds.
    var bitmap by remember(attachment.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(attachment.uri) {
        if (bitmap != null || !attachment.isImage) return@LaunchedEffect
        bitmap = GoodPostImages.loadLocal(context, attachment.uri, maxWidthPx = 320)
    }

    val current = bitmap
    val alpha = waImageFade(loaded = current != null)
    val failed = attachment.state is GoodPostUploadState.Failed
    val uploading = attachment.state is GoodPostUploadState.Uploading

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Wa.Pressed),
        contentAlignment = Alignment.Center
    ) {
        // Under the picture for the length of the fade, and the whole tile for a
        // file whose picture cannot be read — which is every document: there is no
        // frame to decode, so its tile IS the glyph.
        Icon(
            imageVector = when {
                attachment.isVideo -> Icons.Filled.PlayArrow
                attachment.isDocument -> Icons.Filled.PictureAsPdf
                else -> Icons.Filled.AddPhotoAlternate
            },
            contentDescription = null,
            tint = Wa.TextDim,
            modifier = Modifier.size(26.dp)
        )

        // What it is, under the glyph, for the one kind whose tile has nothing
        // else to show. A name at 64dp would be three letters and an ellipsis; the
        // extension is the part that says what will open it.
        if (attachment.isDocument) {
            Text(
                text = stringResource(R.string.goodpost_document),
                color = Wa.TextDim,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
            )
        }

        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(alpha)
            )
        }

        if (attachment.isVideo && current != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // A quiet scrim while the file is on its way: it says "not yet" without
        // covering the picture the reader chose to check.
        if (uploading) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
        }

        // A failed file, and the way to send it again.
        //
        // The whole tile is the retry, under the ✕ that takes it off: the two
        // things a person wants from a red square are "try that again" and "never
        // mind", and a glyph small enough to fit beside a name is too small to be
        // the only way to ask for the first of them.
        if (failed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Wa.Danger.copy(alpha = 0.30f))
                    .clickable(onClick = onRetry),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.goodpost_attachment_retry),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // The way off, top-right, over everything else so it is always reachable.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Wa.Canvas.copy(alpha = 0.82f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.goodpost_cancel),
                tint = Wa.Text,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}
