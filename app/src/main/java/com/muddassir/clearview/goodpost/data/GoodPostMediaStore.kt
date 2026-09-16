package com.muddassir.clearview.goodpost.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Media bytes: upload to a presigned URL, and keep a downloaded copy (§9, §10).
 *
 * Two rules from the spec are structural here rather than aspirational:
 *
 *  * **Nothing is downloaded automatically.** The feed shows a preview from the
 *    presigned URL, and a file is only written to disk when the user asks for
 *    it. §10 is explicit that media is not fetched *en masse* — the cost of
 *    ignoring that is somebody's data plan.
 *
 *  * **A saved copy is never deleted because the server's copy expired.** §10 is
 *    equally explicit: retention on the server is the server's business, and a
 *    file the user deliberately saved is theirs. [clear] therefore exists only
 *    for an explicit user action, and is deliberately NOT wired to sign-out.
 *
 * Uploads stream rather than buffering: a video can be a hundred megabytes, and
 * reading that into a `ByteArray` to hand it to a connection would be a
 * needless copy that also fails on a large file.
 */
internal class GoodPostMediaStore(context: Context) {

    private companion object {
        const val TAG = "GoodPostMedia"

        // Longer than the API client's, because these move large bodies over a
        // phone connection: a 15-second read timeout would abort a perfectly
        // healthy video download.
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000

        const val PART_SUFFIX = ".part"
    }

    /**
     * External app storage where available, internal otherwise.
     *
     * External because media is large and internal storage is small; the
     * app-specific directory needs no permission on any supported API level and
     * is removed with the app, which is the right lifetime for a cache that can
     * always be re-fetched.
     */
    private val root = File(
        context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir,
        "goodpost/media"
    )

    /** Where a saved copy of one asset lives, whether or not it exists yet. */
    fun localFile(mediaId: String, extension: String): File =
        File(root, "$mediaId.$extension")

    /** True when this asset is already saved on the device. */
    fun hasLocalCopy(mediaId: String, extension: String): Boolean =
        localFile(mediaId, extension).let { it.isFile && it.length() > 0 }

    /**
     * Fetch an asset to local storage (§10).
     *
     * Written to a `.part` file and renamed on success, so an interrupted
     * download can never be mistaken for a complete one — a half-written JPEG
     * that renders as a grey box is worse than a file that is simply absent.
     *
     * Existing copies are returned unchanged: the caller's flow is "save this",
     * and re-downloading megabytes to overwrite an identical file is the
     * behaviour §10 asks to avoid.
     */
    suspend fun download(
        mediaId: String,
        extension: String,
        url: String
    ): File? = withContext(Dispatchers.IO) {
        val target = localFile(mediaId, extension)
        if (target.isFile && target.length() > 0) return@withContext target

        val part = File(root, "$mediaId.$extension$PART_SUFFIX")
        var conn: HttpURLConnection? = null
        try {
            root.mkdirs()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }

            if (conn.responseCode !in 200..299) {
                // Never log the URL: it is a signed capability, and a log line
                // is a place a credential leaks from.
                Log.d(TAG, "download $mediaId failed: HTTP ${conn.responseCode}")
                return@withContext null
            }

            conn.inputStream.use { input ->
                part.outputStream().use { output -> input.copyTo(output) }
            }

            if (!part.renameTo(target)) {
                part.delete()
                return@withContext null
            }
            target
        } catch (e: Exception) {
            part.delete()
            Log.d(TAG, "download $mediaId failed: ${e.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * PUT an object to a presigned upload URL.
     *
     * The `Content-Type` header must be EXACTLY the value the server signed: S3
     * validates it against the signature, so a mismatch is rejected by the
     * bucket rather than quietly accepted with the wrong type. The declared
     * length is signed too, which is why it is set as a fixed length instead of
     * using chunked encoding.
     *
     * [open] is a factory rather than a stream so the caller owns the lifecycle
     * of whatever it opened (a `ContentResolver` stream, typically) and this
     * function never closes something it did not open.
     */
    suspend fun upload(
        url: String,
        contentType: String,
        byteSize: Long,
        open: () -> InputStream
    ): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", contentType)
                setFixedLengthStreamingMode(byteSize)
            }

            open().use { input ->
                conn.outputStream.use { output -> input.copyTo(output) }
            }

            val status = conn.responseCode
            if (status in 200..299) return@withContext true

            Log.d(TAG, "upload failed: HTTP $status")
            false
        } catch (e: Exception) {
            // A short read or an oversized body surfaces here, because S3
            // rejects a body that does not match the signed length.
            Log.d(TAG, "upload failed: ${e.javaClass.simpleName}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** Bytes used by saved media, for a storage line in the UI. */
    fun usedBytes(): Long =
        root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * Remove every saved copy.
     *
     * Only ever called from an explicit user action. It is NOT called on
     * sign-out: a file the user saved belongs to them, not to the session that
     * fetched it (§10).
     */
    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }
}

/**
 * Turn a picked `content://` Uri into something the upload lifecycle can send.
 *
 * Lives here, next to the byte transfer, so the Uri handling stays in the data
 * layer: the ViewModel asks for an upload request and never touches a
 * `ContentResolver`, which is what keeps the lifecycle itself testable.
 *
 * The size is REQUIRED, not optional. The server signs `Content-Length` into
 * the upload URL, so a client that does not know the size cannot ask for one —
 * and guessing would produce an upload the bucket rejects with no explanation.
 * A provider that cannot report a size therefore yields null, and the UI says
 * so, rather than starting an upload that is certain to fail.
 *
 * The MIME type is passed through as the provider reports it and validated by
 * the server's allow-list, deliberately: duplicating that list here would leave
 * two copies to keep in step, and the server is the one that decides what a
 * bucket may serve.
 */
internal fun uploadRequestFor(context: Context, uri: Uri): MediaUploadRequest? {
    val resolver = context.contentResolver
    val contentType = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: return null

    val size = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()?.takeIf { it > 0 }
        ?: querySize(resolver, uri)
        ?: return null

    return MediaUploadRequest(
        contentType = contentType,
        byteSize = size,
        // Opened per attempt rather than once: the stream must still be fresh
        // when the PUT begins, and the presign step happens in between.
        open = { resolver.openInputStream(uri) ?: error("stream unavailable") }
    )
}

/** The size column a document provider publishes, if it publishes one. */
private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long? {
    val cursor = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
    }.getOrNull() ?: return null

    return cursor.use {
        if (!it.moveToFirst()) return null
        val index = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (index < 0 || it.isNull(index)) null else it.getLong(index).takeIf { size -> size > 0 }
    }
}
