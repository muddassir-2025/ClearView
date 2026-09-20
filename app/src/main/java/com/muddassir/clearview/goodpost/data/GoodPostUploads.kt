package com.muddassir.clearview.goodpost.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Attaching a file to a post (§21, §22).
 *
 * The composer's half of the media lifecycle. Three steps, in this order and no
 * other:
 *
 *   1. **Ask the server for a place to put it** — the server answers with a
 *      presigned URL and a media id, and it chooses the object key. No client
 *      ever names a path in the bucket.
 *   2. **PUT the bytes** straight to the bucket, streaming from the picked file.
 *   3. **Confirm**, which makes the server HEAD the object before it may be
 *      attached. "The client says it uploaded" is not evidence, which is why a
 *      post can never carry an asset that was never really there.
 *
 * Two rules this file keeps:
 *
 *  * **A whole video is never held in memory.** The PUT streams with a fixed
 *    content length, so a 100 MB clip costs a buffer, not 100 MB of heap — the
 *    difference between publishing a video and being killed by the OOM killer
 *    on a mid-range phone (§26).
 *
 *  * **The allow-list is mirrored, not invented.** [GOODPOST_ACCEPTED_TYPES] is
 *    the same set the server accepts, so a file it would refuse is refused here
 *    with a sentence instead of a round trip. The server is still the authority;
 *    this only makes the common refusal instant.
 */

/** How far an attachment has got. */
sealed interface GoodPostUploadState {
    /** Presigned, PUT in flight, or awaiting confirmation. */
    data object Uploading : GoodPostUploadState

    /**
     * Confirmed by the server and claimable.
     *
     * The media id is what the publish call attaches; holding it here rather
     * than in a separate map is what makes "the composer's ready files" and
     * "the ids sent" the same list by construction.
     */
    data class Ready(val mediaId: String) : GoodPostUploadState

    /**
     * Refused, with the server's own code — or `unsupported_type` for a file
     * this app will not even send. Worded by [com.muddassir.clearview.goodpost.goodPostErrorFor].
     */
    data class Failed(val code: String) : GoodPostUploadState
}

/**
 * One file the composer is holding.
 *
 * [uri] is the local pick, kept as a string so the state survives the process
 * being recreated; the bytes themselves are never copied, because the picker's
 * URI is already a readable handle on the file.
 */
data class GoodPostAttachment(
    val uri: String,
    /** `image`, `video` or `document`. */
    val kind: String,
    val contentType: String,
    val byteSize: Long,
    /** Hints for layout only; the server stores them and nothing depends on them. */
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    /**
     * What the file was called where it was picked.
     *
     * Sent with the upload and stored by the server for a DOCUMENT, because that
     * name is the only thing its card has to show — a PDF draws no preview. For
     * an image or a clip it is kept here for the preview's own sake and is not
     * sent.
     */
    val fileName: String? = null,
    val state: GoodPostUploadState = GoodPostUploadState.Uploading
) {
    val isImage: Boolean get() = kind == "image"
    val isVideo: Boolean get() = kind == "video"
    val isDocument: Boolean get() = kind == "document"

    /** Ready to be attached to a post. */
    val mediaId: String?
        get() = (state as? GoodPostUploadState.Ready)?.mediaId
}

/**
 * The content types the backend will store.
 *
 * An allow-list rather than a deny-list, mirrored from the server's own, and it
 * exists for the same reason there: what is stored under a URL anyone can fetch
 * is what a browser will later run. `image/svg+xml` is absent deliberately — an
 * SVG served from our bucket domain is a stored-XSS primitive.
 *
 * The audio types went with audio posts: §21 lists text, image, video, document
 * and link updates, so the server does not store an audio kind and the picker must
 * not act as though it does.
 *
 * `application/pdf` is the one document type, matching the server's list exactly
 * (see `ALLOWED_CONTENT_TYPES`): a file this app would send and the server would
 * refuse is a round trip that ends in a refusal the composer could have worded
 * before it started.
 */
val GOODPOST_ACCEPTED_TYPES: Set<String> = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
    "video/mp4",
    "video/quicktime",
    "video/webm",
    "application/pdf"
)

/**
 * Which kind a content type belongs to, or null when it is not accepted.
 *
 * Pure, so it is unit-tested directly — this decides what the composer lets a
 * user pick, and a bug here is a file that fails only after it has been
 * uploaded.
 */
fun goodPostKindFor(contentType: String?): String? {
    val base = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (base.isEmpty() || base !in GOODPOST_ACCEPTED_TYPES) return null
    return when {
        base.startsWith("image/") -> "image"
        base.startsWith("video/") -> "video"
        // Named rather than "anything else in the set": the set is a list of
        // TYPES, and a type added to it for some later purpose must not silently
        // become a kind the server derives a post's shape from.
        base == "application/pdf" -> "document"
        else -> null
    }
}

/**
 * Read a picked file's metadata, or null when it cannot be posted.
 *
 * Null means "do not offer this file": an unreadable type, a size that cannot
 * be read, or a stream the picker has already revoked. Every failure is the
 * same answer to the user — the file was not attached — because a partially
 * attached file is worse than an honest refusal.
 *
 * The dimensions and duration are BEST EFFORT. They are layout hints; the
 * server stores them but nothing depends on them, so a device that cannot read
 * them attaches the file anyway rather than refusing it over a nice-to-have.
 */
fun readGoodPostAttachment(context: Context, uri: Uri): GoodPostAttachment? {
    val resolver = context.contentResolver
    val fileName = readDisplayName(context, uri)

    // What the provider SAID the file is, which for a document is often wrong:
    // Google Drive, Files and several OEM pickers answer
    // `application/octet-stream` — or nothing at all — for a PDF they are handing
    // over perfectly well. Refused on that alone, a file the reader can see in
    // front of them comes back as "that file type cannot be posted", which is the
    // app being wrong about something it can simply check.
    var contentType = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
    var kind = goodPostKindFor(contentType)

    if (kind == null) {
        val sniffed = sniffContentType(context, uri, fileName)
        if (sniffed != null && goodPostKindFor(sniffed) != null) {
            contentType = sniffed
            kind = goodPostKindFor(sniffed)
        }
    }

    if (kind == null) return null

    val byteSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else null
    } ?: return null

    // A zero-length file would be signed, uploaded and confirmed as nothing;
    // refusing it here saves the round trip and the confusing empty post.
    if (byteSize <= 0L) return null

    // A document's name is the whole of its card, so one whose provider will not
    // name it is refused HERE with the same sentence as any other file this app
    // will not send — rather than uploaded and then refused by the server, which
    // would spend the transfer to learn what is already known.
    if (kind == "document" && fileName.isNullOrBlank()) return null

    val dimensions = measure(context, uri, kind)

    return GoodPostAttachment(
        uri = uri.toString(),
        kind = kind,
        contentType = contentType.orEmpty(),
        byteSize = byteSize,
        width = dimensions.first,
        height = dimensions.second,
        durationMs = dimensions.third,
        fileName = fileName
    )
}

/**
 * The name the file has where it was picked, or null.
 *
 * Best effort like the dimensions: a provider that will not answer costs the
 * document its name, and a document with no name is refused by the server — so the
 * composer asks this BEFORE it starts an upload it cannot finish. `OpenableColumns`
 * is the one column set every document provider answers, and the query is bounded
 * to a single row because a provider that returns more is a provider that cannot
 * be trusted to have a name at all.
 */
/**
 * What a file is, when the provider would not say.
 *
 * Two answers, in the order of how much they can be trusted:
 *
 *  1. **The bytes.** A PDF begins `%PDF-`, always, and that is a fact about the
 *     file rather than a claim about it. It is checked first because it cannot be
 *     spoofed by a name and cannot be forgotten by a provider.
 *  2. **The name.** A `.pdf`, `.jpg` or `.mp4` is what the file says it is, which
 *     is the same thing a file manager believes and the same thing the receiving
 *     app will believe. Used only when the header could not be read at all — a
 *     provider that denies the bytes is exactly the provider that gives a generic
 *     type — and the result still has to be a type this app and the server both
 *     accept, so a name cannot talk it into storing something the allow-list
 *     refuses.
 *
 * Null means neither answered, which keeps the honest refusal: a file this app
 * cannot identify is one it should not send.
 */
private fun sniffContentType(context: Context, uri: Uri, fileName: String?): String? {
    val header = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val head = ByteArray(PDF_MAGIC_LENGTH)
            val read = stream.read(head)
            if (read == PDF_MAGIC_LENGTH) String(head, Charsets.US_ASCII) else null
        }
    } catch (e: Exception) {
        null
    }
    if (header == PDF_MAGIC) return "application/pdf"

    val extension = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return GOODPOST_TYPE_BY_EXTENSION[extension]
}

/** A PDF's first five bytes, which no other format shares. */
private const val PDF_MAGIC = "%PDF-"
private const val PDF_MAGIC_LENGTH = 5

/**
 * The types a file extension stands for, for the files this app may send.
 *
 * Only the accepted ones, and only the extensions that mean exactly one type: an
 * extension that stands for several kinds of file (a `.mov` that is really HEVC, a
 * `.webm` that is really audio) is not on this list, because guessing there would
 * declare a type the bucket then serves wrongly to every reader.
 */
private val GOODPOST_TYPE_BY_EXTENSION: Map<String, String> = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "webp" to "image/webp",
    "gif" to "image/gif",
    "mp4" to "video/mp4",
    "mov" to "video/quicktime",
    "webm" to "video/webm",
    "pdf" to "application/pdf"
)

/**
 * Whether the file's EXIF orientation turns it a quarter turn.
 *
 * `BitmapFactory` ignores this flag and Android's decoder is not asked to apply
 * it, so the flag is the ONLY difference between the buffer's shape and the
 * picture's: 5 and 6 and 7 and 8 are the four quarter turns, and the other five
 * values (`NORMAL`, `FLIP_HORIZONTAL`, `ROTATE_180`, `FLIP_VERTICAL`) keep the
 * axes as they are.
 *
 * Best effort, like everything else in [measure]: a file with no EXIF block, or
 * one whose block cannot be read, is measured as it is stored.
 */
private fun exifSwapsAxes(context: Context, uri: Uri): Boolean = try {
    val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
        android.media.ExifInterface(stream)
            .getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
    }
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_TRANSPOSE,
        android.media.ExifInterface.ORIENTATION_ROTATE_90,
        android.media.ExifInterface.ORIENTATION_TRANSVERSE,
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> true
        else -> false
    }
} catch (e: Exception) {
    false
}

private fun readDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
            }
    } catch (e: Exception) {
        null
    }
}

/** Width, height and duration where the platform can tell us; nulls otherwise. */
private fun measure(context: Context, uri: Uri, kind: String): Triple<Int?, Int?, Long?> {
    // A document is measured by nothing here: it has no preview to lay out, and
    // the retriever below would open a PDF's bytes to look for video metadata and
    // find none — a read with a cost and no answer.
    if (kind == "document") return Triple(null, null, null)

    if (kind == "image") {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            // The dims of the picture as it will be SEEN, not as the buffer is
            // stored — see [exifSwapsAxes]. A portrait photo is a landscape buffer
            // with a flag on it, and a card built from the buffer's dims is a wide
            // box around a tall picture: the bubble shows through the difference on
            // both sides, which is exactly the empty green that made this matter.
            val swap = exifSwapsAxes(context, uri)
            val rawWidth = options.outWidth.takeIf { it > 0 }
            val rawHeight = options.outHeight.takeIf { it > 0 }
            val width = if (swap) rawHeight else rawWidth
            val height = if (swap) rawWidth else rawHeight
            Triple(width, height, null)
        } catch (e: Exception) {
            Triple(null, null, null)
        }
    }

    // Video and audio: the retriever reads the container's own header rather
    // than decoding, so this costs a metadata read rather than a transcode.
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()?.takeIf { it > 0 }
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()?.takeIf { it > 0 }
        // A clip carries a rotation the same way a photo does — and the player
        // applies it, so a portrait video's box has to be the portrait shape or
        // the video is letterboxed inside a landscape one.
        val turned = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?.let { it == 90 || it == 270 }
            ?: false
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.takeIf { it > 0 }
        Triple(
            if (turned) height else width,
            if (turned) width else height,
            duration
        )
    } catch (e: Exception) {
        Triple(null, null, null)
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
            // A retriever that cannot be released is already unusable; there is
            // nothing to do and nothing to report.
        }
    }
}
