package com.muddassir.clearview.goodpost.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GoodPostDownloads
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostVideoCache
import kotlinx.coroutines.launch

/**
 * What can be done with a post's media, in one menu (§6, §9, §15).
 *
 * ## Why a menu and not a row of icons
 *
 * There used to be one icon: a download arrow. It was the only thing a reader
 * could do with a picture, and it is not the thing most readers want — sharing a
 * picture is more common than keeping one, and keeping a MESSAGE is a different
 * act from keeping its file. Four small icons over a photo would cover the photo;
 * a menu costs one tap and says what each action is in words.
 *
 * ## "Delete" means this device
 *
 * Every item here acts on the copy in the reader's hand. A star and a download
 * both belong to the phone, and `Delete from this device` drops what Good Post
 * has cached for that media. The channel's copy is untouched and the server is
 * not told, because a reader tidying their own storage is not an editorial act —
 * and a menu that silently removed something for everybody else would be the last
 * place this belonged.
 *
 * ## Share shares the FILE
 *
 * Not the URL. A presigned URL is a capability addressed to this app and expires
 * within the hour, so passing it on would send a link that refuses whoever opens
 * it. The bytes are materialised into the app's cache and handed over through the
 * FileProvider, which is what makes Share work with every app that accepts a
 * picture.
 */
@Composable
internal fun MediaActionMenu(
    url: String?,
    kind: String,
    contentType: String?,
    starred: Boolean,
    onToggleStar: () -> Unit,
    /** Called after the local copy was dropped, so a caller can react. */
    onDeleted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Deleting is the one item here that cannot be taken back, so it is the one
    // item that asks first — the menu says what will happen, the dialog says what
    // it will cost.
    var confirmingDelete by remember { mutableStateOf(false) }

    val mediaUrl = url?.takeIf { it.isNotBlank() }
    val name = remember(kind, contentType) { GoodPostDownloads.fileName(kind, contentType) }
    val keptLocally = remember(mediaUrl) {
        mediaUrl != null &&
            (GoodPostImages.diskBytesFor(listOf(mediaUrl)) > 0 ||
                GoodPostVideoCache.bytesFor(mediaUrl) > 0)
    }

    WaOverflowMenu(
        modifier = modifier,
        items = buildList {
            add(
                WaMenuItem(
                    label = stringResource(R.string.goodpost_share_file),
                    onClick = {
                        if (mediaUrl == null) return@WaMenuItem
                        scope.launch {
                            val uri = GoodPostDownloads.shareFile(context, mediaUrl, kind, contentType)
                            if (uri == null) {
                                showToast(context, R.string.goodpost_save_failed)
                            } else {
                                shareFile(context, uri, kind, contentType)
                            }
                        }
                    }
                )
            )

            add(
                WaMenuItem(
                    label = stringResource(R.string.goodpost_save_to_device),
                    onClick = {
                        if (mediaUrl == null) return@WaMenuItem
                        scope.launch {
                            val outcome = GoodPostDownloads.saveToLibrary(
                                context = context,
                                url = mediaUrl,
                                kind = kind,
                                contentType = contentType,
                                name = name
                            )
                            showToast(context, outcome)
                        }
                    }
                )
            )

            add(
                WaMenuItem(
                    label = stringResource(
                        if (starred) R.string.goodpost_unstar else R.string.goodpost_star
                    ),
                    onClick = onToggleStar
                )
            )

            // Offered only once there IS a local copy: a "delete from this
            // device" on something this device never kept would be a button whose
            // only possible outcome is a lie.
            if (keptLocally && mediaUrl != null) {
                add(
                    WaMenuItem(
                        label = stringResource(R.string.goodpost_delete_from_device),
                        destructive = true,
                        onClick = { confirmingDelete = true }
                    )
                )
            }
        }
    )

    if (confirmingDelete && mediaUrl != null) {
        WaDeleteDialog(
            title = stringResource(R.string.goodpost_delete_media_title),
            // A reader's delete is a device delete and nothing else, and saying so
            // is the whole job of this sentence: the channel's copy is not the
            // reader's to remove and the server is never told.
            message = stringResource(R.string.goodpost_delete_media_note),
            // The menu is shared with the viewer and the gallery, neither of which
            // knows whether this account may publish — and neither of which should
            // guess. "For everyone" is a post-level act offered from the post's own
            // selection bar, where the permission is known, not from a file.
            canDeleteForEveryone = false,
            onDismiss = { confirmingDelete = false },
            onDeleteForMe = {
                confirmingDelete = false
                scope.launch {
                    GoodPostImages.forget(listOf(mediaUrl))
                    GoodPostVideoCache.evict(mediaUrl)
                    showToast(context, R.string.goodpost_deleted_from_device)
                    onDeleted()
                }
            }
        )
    }
}

/** Hand a file to whatever the device shares with (§15). */
private fun shareFile(context: Context, uri: Uri, kind: String, contentType: String?) {
    shareFiles(context, listOf(uri), mimeTypeFor(kind, contentType), null)
}

/**
 * Hand one or more files over, with the post's own words attached.
 *
 * ## Why the FILE and not the link
 *
 * Sharing a post used to send its text and the channel's URL, which for a post
 * that is a picture means the person on the other end gets a web page instead of
 * the picture. The bytes are materialised into the app's cache and handed over
 * through the FileProvider — that is what makes Share open the gallery, the
 * messaging app, the editor, everything that takes a picture (§15).
 *
 * Multiple files go as `ACTION_SEND_MULTIPLE`, which is the same chooser with the
 * selection attached; a single one stays on `ACTION_SEND` because a one-item
 * multiple is a shape several apps handle poorly.
 */
internal fun shareFiles(context: Context, uris: List<Uri>, type: String, text: String?) {
    if (uris.isEmpty()) return

    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }

    // The post's words travel with the file where they fit :a caption is part of
    // what was posted, and handing over a picture with no context is a worse
    // share than the one the reader asked for.
    if (!text.isNullOrBlank()) send.putExtra(Intent.EXTRA_TEXT, text)

    // Without the read grant most apps receive a file they are not allowed to
    // open, which is the failure that looks like "sharing does nothing".
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    runCatching {
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }.onFailure { showToast(context, R.string.goodpost_save_failed) }
}

/**
 * The type a chooser is filtered by, from the files themselves when there is more
 * than one.
 *
 * Mixed media — a photograph and a clip in one post — has no single type, and
 * guessing `image/*` would hide every app that only takes video. `*/*` is the
 * honest answer for a mixed set, and it is what the system's own shares use.
 */
internal fun mimeTypeFor(kind: String, contentType: String?): String {
    contentType?.substringBefore(';')?.trim()?.takeIf { it.contains('/') }?.let { return it }
    return when (kind) {
        "image" -> "image/*"
        "video" -> "video/*"
        "audio" -> "audio/*"
        else -> "*/*"
    }
}

/** The one type that covers every file in a selection. */
internal fun mimeTypeForAll(kinds: List<Pair<String, String?>>): String {
    val types = kinds.map { (kind, contentType) -> mimeTypeFor(kind, contentType) }.distinct()
    return when {
        types.isEmpty() -> "*/*"
        types.size == 1 -> types.first()
        // A family in common is enough to keep every app that takes it available.
        types.map { it.substringBefore('/') }.distinct().size == 1 ->
            types.first().substringBefore('/') + "/*"

        else -> "*/*"
    }
}

/** One sentence, for an outcome a reader has to be told about. */
internal fun showToast(context: Context, message: Int) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** The download helper's own answer, as that sentence. */
internal fun showToast(context: Context, outcome: GoodPostDownloads.Outcome) {
    showToast(
        context,
        when (outcome) {
            GoodPostDownloads.Outcome.Saved -> R.string.goodpost_saved
            GoodPostDownloads.Outcome.Failed,
            GoodPostDownloads.Outcome.NeedsDestination -> R.string.goodpost_save_failed
        }
    )
}
