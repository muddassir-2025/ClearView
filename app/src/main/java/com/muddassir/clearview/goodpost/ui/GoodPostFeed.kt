package com.muddassir.clearview.goodpost.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostHomeUiState
import com.muddassir.clearview.goodpost.data.GoodPostMedia
import com.muddassir.clearview.goodpost.data.GoodPostPost
import com.muddassir.clearview.media.ui.RemoteImage

/**
 * The aggregated feed (§4), the post rows, and the composer (§8).
 *
 * Everything here is presentation: it reads state and calls the ViewModel. No
 * HTTP, no JSON and no file handling reaches this file (§35), which is why the
 * media rows take what to show rather than what to fetch.
 */
@Composable
internal fun PostsSection(
    state: GoodPostHomeUiState,
    onOpenChannel: (String) -> Unit,
    onEditPost: (GoodPostPost) -> Unit,
    onDeletePost: (GoodPostPost) -> Unit,
    onSaveMedia: (GoodPostMedia) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit
) {
    if (state.feed.isEmpty() && !state.loading) {
        Notice(
            icon = { Icon(Icons.Filled.Campaign, contentDescription = null) },
            title = stringResource(R.string.goodpost_empty_feed_title),
            note = stringResource(R.string.goodpost_empty_feed_note),
            actionLabel = stringResource(R.string.goodpost_refresh),
            onAction = onRetry
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.feed, key = { it.id }) { post ->
            PostRow(
                post = post,
                showChannel = true,
                savedMediaIds = state.savedMediaIds,
                savingMediaId = state.savingMediaId,
                busy = state.busyPostId == post.id,
                onOpenChannel = onOpenChannel,
                onEdit = { onEditPost(post) },
                onDelete = { onDeletePost(post) },
                onSaveMedia = onSaveMedia
            )
            HorizontalDivider()
        }

        if (state.feedCursor != null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.loadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(onClick = onLoadMore) {
                            Text(stringResource(R.string.goodpost_more))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One post: who published it, what it says, and what can be done with it.
 *
 * Shared with the channel screen, so a post renders identically in the feed and
 * in the channel it came from — a second row implementation would be a place
 * for the two to disagree about what a post looks like.
 */
@Composable
internal fun PostRow(
    post: GoodPostPost,
    showChannel: Boolean,
    savedMediaIds: Set<String>,
    savingMediaId: String?,
    busy: Boolean,
    onOpenChannel: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSaveMedia: (GoodPostMedia) -> Unit
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp)) {

        // The channel is only named when the list spans channels — in a
        // channel's own history it would repeat the header on every row.
        if (showChannel && post.channel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = post.channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = relativeTime(post.createdAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // A separate target from the name, so tapping the header to
                // read a channel cannot be confused with anything else.
                IconButton(onClick = { onOpenChannel(post.channel.id) }) {
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.goodpost_post_open_channel),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (post.isEdited) {
                    Text(
                        text = stringResource(R.string.goodpost_post_edited),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = relativeTime(post.createdAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        post.body?.let { body ->
            Spacer(Modifier.height(6.dp))
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }

        post.linkUrl?.let { link ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    post.linkTitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = link,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        post.media.forEach { item ->
            Spacer(Modifier.height(8.dp))
            MediaBlock(
                item = item,
                saved = savedMediaIds.contains(item.id),
                saving = savingMediaId == item.id,
                onSave = { onSaveMedia(item) }
            )
        }

        // §7: the controls are offered only where the server said this viewer
        // may manage the post. The UI never decides that for itself (§32).
        if (post.viewerCanManage) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onEdit, enabled = !busy) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.goodpost_post_edit))
                }
                TextButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.goodpost_post_delete))
                }
            }
        }
    }
}

/**
 * One attachment.
 *
 * An image renders from its presigned URL — a preview, which §10 asks for. Video
 * and audio do NOT auto-play: there is no player wired to S3 media yet, and
 * pretending otherwise would show a control that does nothing. What they offer
 * is what actually works: save the file, then open it with the device's own
 * player from the saved copy.
 */
@Composable
private fun MediaBlock(
    item: GoodPostMedia,
    saved: Boolean,
    saving: Boolean,
    onSave: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            item.isImage && item.url != null -> {
                RemoteImage(
                    url = item.url,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                    showLoadingSpinner = true
                )
            }

            item.isImage -> MediaPlaceholder(
                icon = Icons.Filled.Campaign,
                label = stringResource(R.string.goodpost_post_media_unavailable)
            )

            item.isVideo -> MediaPlaceholder(
                icon = Icons.Filled.Movie,
                label = describe(item)
            )

            else -> MediaPlaceholder(
                icon = Icons.Filled.Audiotrack,
                label = describe(item)
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = describe(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )

            when {
                saving -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )

                saved -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.goodpost_post_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // §10: saving is a deliberate action. Nothing is fetched until
                // this is tapped, which is why the button is the only way a
                // media byte reaches the device.
                else -> TextButton(onClick = onSave) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.goodpost_post_save_media))
                }
            }
        }
    }
}

@Composable
private fun MediaPlaceholder(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A human description of an asset: its kind and size. */
@Composable
private fun describe(item: GoodPostMedia): String {
    val kind = when {
        item.isImage -> "Image"
        item.isVideo -> "Video"
        item.isAudio -> "Audio"
        else -> "File"
    }
    return if (item.byteSize > 0) "$kind · ${formatBytes(item.byteSize)}" else kind
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * A post's age, or an empty string when the server sent a date this build
 * cannot read.
 *
 * Empty rather than a guess: showing "just now" for an unparseable timestamp
 * would be inventing information, and showing 1970 would be worse.
 */
@Composable
private fun relativeTime(epochMs: Long?): String {
    if (epochMs == null) return ""
    val minutes = ((System.currentTimeMillis() - epochMs) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.goodpost_time_now)
        minutes < 60 -> stringResource(R.string.goodpost_time_minutes, minutes.toInt())
        minutes < 60 * 24 -> stringResource(R.string.goodpost_time_hours, (minutes / 60).toInt())
        else -> stringResource(R.string.goodpost_time_days, (minutes / (60 * 24)).toInt())
    }
}

/**
 * The composer (§8, §9).
 *
 * A full-screen dialog rather than a sheet, matching the channel detail screen
 * so the two feel like the same product.
 *
 * Attachments upload on selection, not on publish: confirming the object takes
 * as long as the file does, and doing that after the user taps Post would make
 * the button look broken for the entire upload.
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
    onDismiss: () -> Unit
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onPickFile) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.goodpost_cancel))
                    }
                    Text(
                        text = stringResource(R.string.goodpost_composer_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.composerBody,
                    onValueChange = onBodyChange,
                    label = { Text(stringResource(R.string.goodpost_composer_body_hint)) },
                    enabled = !state.publishing,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.composerLink,
                    onValueChange = onLinkChange,
                    label = { Text(stringResource(R.string.goodpost_composer_link_hint)) },
                    enabled = !state.publishing,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.composerLinkTitle,
                    onValueChange = onLinkTitleChange,
                    label = { Text(stringResource(R.string.goodpost_composer_link_title_hint)) },
                    enabled = !state.publishing,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                state.composerAttachments.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.isImage && item.url != null) {
                            RemoteImage(
                                url = item.url,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                if (item.isVideo) Icons.Filled.Movie else Icons.Filled.Audiotrack,
                                contentDescription = null
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = describe(item),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemoveAttachment(item.id) }, enabled = !state.publishing) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.goodpost_remove_attachment),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedButton(
                    onClick = { picker.launch("*/*") },
                    enabled = !state.publishing && !state.uploadingAttachment,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.goodpost_composer_attach))
                }

                if (state.uploadingAttachment) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.goodpost_composer_uploading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                state.messageCode?.let { code ->
                    Spacer(Modifier.height(16.dp))
                    ErrorNotice(code)
                }

                Spacer(Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss, enabled = !state.publishing) {
                        Text(stringResource(R.string.goodpost_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onPublish,
                        // Disabled on a completely empty post rather than
                        // letting the server answer `empty_post`: the rule is
                        // knowable here, and an error message for something the
                        // button could have prevented is noise.
                        enabled = !state.publishing &&
                            !state.uploadingAttachment &&
                            (state.composerBody.isNotBlank() ||
                                state.composerLink.isNotBlank() ||
                                state.composerAttachments.isNotEmpty())
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.goodpost_publish))
                    }
                }
            }
        }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goodpost_post_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = body,
                    onValueChange = onBodyChange,
                    enabled = !busy,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorCode != null) {
                    Spacer(Modifier.height(12.dp))
                    ErrorNotice(errorCode)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !busy) {
                Text(stringResource(R.string.goodpost_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.goodpost_cancel))
            }
        }
    )
}
