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
    /** `image` or `video`. */
    val kind: String,
    val contentType: String,
    val byteSize: Long,
    /** Hints for layout only; the server stores them and nothing depends on them. */
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val state: GoodPostUploadState = GoodPostUploadState.Uploading
) {
    val isImage: Boolean get() = kind == "image"
    val isVideo: Boolean get() = kind == "video"

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
 * The audio types went with audio posts: §21 lists text, image, video and link
 * updates, so the server no longer stores a third kind and the picker must not
 * act as though it does.
 */
val GOODPOST_ACCEPTED_TYPES: Set<String> = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
    "video/mp4",
    "video/quicktime",
    "video/webm"
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
        // A type in the set that is neither an image nor a video is a mistake in
        // the set, not a third kind: refusing it is the honest answer, because
        // the server would refuse it too.
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
    val contentType = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
    val kind = goodPostKindFor(contentType) ?: return null

    val byteSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else null
    } ?: return null

    // A zero-length file would be signed, uploaded and confirmed as nothing;
    // refusing it here saves the round trip and the confusing empty post.
    if (byteSize <= 0L) return null

    val dimensions = measure(context, uri, kind)

    return GoodPostAttachment(
        uri = uri.toString(),
        kind = kind,
        contentType = contentType.orEmpty(),
        byteSize = byteSize,
        width = dimensions.first,
        height = dimensions.second,
        durationMs = dimensions.third
    )
}

/** Width, height and duration where the platform can tell us; nulls otherwise. */
private fun measure(context: Context, uri: Uri, kind: String): Triple<Int?, Int?, Long?> {
    if (kind == "image") {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val width = options.outWidth.takeIf { it > 0 }
            val height = options.outHeight.takeIf { it > 0 }
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
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.takeIf { it > 0 }
        Triple(width, height, duration)
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
