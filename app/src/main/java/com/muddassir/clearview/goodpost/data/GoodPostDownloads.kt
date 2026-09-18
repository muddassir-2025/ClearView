package com.muddassir.clearview.goodpost.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeping a post's media on the device (§15).
 *
 * A reader who taps Save gets the original bytes — not the decode the screen was
 * drawn from, which is scaled for a 720 px column and would be a thumbnail in
 * anybody's gallery. The file lands in the system gallery on Android 10+, where
 * no permission is needed because MediaStore owns the write; on 9 and below,
 * where a gallery insert would need WRITE_EXTERNAL_STORAGE, the caller is told to
 * ask the user where to put it instead and the same bytes go to the URI they
 * pick. Neither path stores anything in the app's own directory, because a file
 * only this app can see is not a download.
 *
 * Deliberately NOT a `DownloadManager` request. DownloadManager wants a
 * notification of its own, retries on its own schedule and hands back a URI
 * rather than a name — three behaviours this screen would have to suppress.
 * A signed URL is valid long enough to stream one file, which is all this does.
 *
 * Nothing here records the download anywhere. §15 is explicit that a read is not
 * followed: there is no table of who kept what, and the server is not told.
 */
internal object GoodPostDownloads {

    /** What happened, as something the screen can say out loud. */
    sealed interface Outcome {
        /** Written to the gallery. */
        data object Saved : Outcome

        /**
         * This device needs a destination chosen first (Android 9 and below).
         *
         * Not a failure: the caller opens the system's save dialog and calls
         * [copyTo] with what comes back.
         */
        data object NeedsDestination : Outcome

        /** Nothing was written. The screen says so; there is nothing to retry. */
        data object Failed : Outcome
    }

    /**
     * Whether the gallery can be written to without a picker.
     *
     * Exposed rather than hidden inside [saveToLibrary] because the caller has to
     * change the USER'S flow, not just this call: on 9 and below the tap opens a
     * save dialog instead of finishing.
     */
    val canWriteWithoutPicker: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * A name a person would recognise in their gallery.
     *
     * The extension comes from the media type the server stored rather than from
     * the URL, because a presigned URL's path ends in the object key and its query
     * is a credential — reading an extension out of it would sometimes produce
     * `image` as the suffix and sometimes a signature fragment. The timestamp
     * keeps two saves of the same post from colliding, which MediaStore would
     * otherwise resolve by appending "(1)" to a name nobody typed.
     */
    fun fileName(kind: String, contentType: String?, at: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(at))
        return "GoodPost_$stamp.${extensionFor(kind, contentType)}"
    }

    /** `jpg`, `mp4`, `mp3`… — never empty, never longer than five characters. */
    fun extensionFor(kind: String, contentType: String?): String {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val fromType = type.substringAfter('/', "").let { subtype ->
            when (subtype) {
                // The media type's own spelling is not a file extension: jpeg
                // saves as .jpg, mpeg as .mp3 or .mp4 depending on what it holds,
                // and 3gpp as .3gp.
                "jpeg" -> "jpg"
                "quicktime" -> "mov"
                "x-matroska" -> "mkv"
                "mpeg" -> if (kind == "audio") "mp3" else "mp4"
                "3gpp" -> "3gp"
                "plain" -> "txt"
                else -> subtype
            }
        }
        // A subtype is only an extension if it looks like one — `svg+xml` and
        // `vnd.ms-excel` do not, and neither does an empty string.
        val safe = fromType.takeIf { it.isNotEmpty() && it.length <= 4 && it.all { c -> c.isLetterOrDigit() } }
            ?: when (kind) {
                "image" -> "jpg"
                "video" -> "mp4"
                "audio" -> "mp3"
                else -> "bin"
            }
        return safe
    }

    /**
     * Stream a signed URL into the gallery (Android 10+), or say that a
     * destination is needed (9 and below).
     */
    suspend fun saveToLibrary(
        context: Context,
        url: String,
        kind: String,
        contentType: String?,
        name: String
    ): Outcome = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext Outcome.NeedsDestination
        if (writeToLibrary(context, url, kind, contentType, name)) Outcome.Saved else Outcome.Failed
    }

    /**
     * Put the file somewhere another app can read it, and hand back its URI.
     *
     * ## Why the file has to be materialised
     *
     * What is being shared is a file, not a link. The URL is a signed capability
     * that expires within the hour and is addressed to this app, so sending it to
     * somebody else would give them a link that refuses them — and the whole
     * point of Share is that the person on the other end gets the picture. So the
     * bytes are copied into the app's own cache and handed over through the
     * FileProvider declared in the manifest, which is the only way Android lets an
     * app pass a file it owns to another app on 7.0 and above.
     *
     * The copy lives in `cacheDir/shared` and is swept by the system under
     * storage pressure, which is right: it is a hand-off, not a download.
     */
    suspend fun shareFile(
        context: Context,
        url: String,
        kind: String,
        contentType: String?
    ): Uri? = withContext(Dispatchers.IO) {
        val name = fileName(kind, contentType)
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val target = java.io.File(dir, name)
        val written = try {
            target.outputStream().use { out ->
                streamInto(url) { input -> input.copyTo(out, DEFAULT_BUFFER) }
            }
        } catch (e: Exception) {
            false
        }
        if (!written) return@withContext null

        runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
        }.getOrNull()
    }

    /**
     * Stream the same URL into a destination the user chose.
     *
     * The pre-Android-10 path, and also what a "save as" that already has a URI
     * uses. Nothing is left behind on failure beyond the empty file the picker
     * created, which is the user's own document and not ours to delete.
     */
    suspend fun copyTo(context: Context, url: String, destination: Uri): Outcome =
        withContext(Dispatchers.IO) {
            val written = try {
                context.contentResolver.openOutputStream(destination)?.use { out ->
                    streamInto(url) { input -> input.copyTo(out, DEFAULT_BUFFER) }
                } ?: false
            } catch (e: Exception) {
                false
            }
            if (written) Outcome.Saved else Outcome.Failed
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToLibrary(
        context: Context,
        url: String,
        kind: String,
        contentType: String?,
        name: String
    ): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            contentType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it.substringBefore(';')) }
            put(MediaStore.MediaColumns.RELATIVE_PATH, directoryFor(kind))
            // The half-written file is invisible to the gallery until the bytes
            // are all there, so a scan running mid-download cannot publish a
            // truncated image.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val target = try {
            resolver.insert(collectionFor(kind), values)
        } catch (e: Exception) {
            null
        } ?: return false

        val written = try {
            resolver.openOutputStream(target)?.use { out ->
                streamInto(url) { input -> input.copyTo(out, DEFAULT_BUFFER) }
            } ?: false
        } catch (e: Exception) {
            false
        }

        if (!written) {
            // Leaving an IS_PENDING row behind would be an invisible file that
            // still occupies its bytes.
            runCatching { resolver.delete(target, null, null) }
            return false
        }

        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        return runCatching { resolver.update(target, done, null, null); true }.getOrDefault(true)
    }

    // Annotated because `MediaStore.Downloads` is API 29+, and its only caller is
    // the Q-and-above branch — which lint cannot see through an `if` on the
    // version alone.
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun collectionFor(kind: String): Uri = when (kind) {
        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }

    private fun directoryFor(kind: String): String = when (kind) {
        "image" -> Environment.DIRECTORY_PICTURES + "/ClearView"
        "video" -> Environment.DIRECTORY_MOVIES + "/ClearView"
        "audio" -> Environment.DIRECTORY_MUSIC + "/ClearView"
        else -> Environment.DIRECTORY_DOWNLOADS + "/ClearView"
    }

    /**
     * Copy a URL's body into a sink, once.
     *
     * The caller owns the sink and closes it; this owns the connection, including
     * the failure case, because a leaked HTTP connection against a bucket is a
     * dead socket per attempt.
     */
    private fun streamInto(url: String, sink: (InputStream) -> Unit): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) return false
            connection.inputStream.use(sink)
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val DEFAULT_BUFFER = 64 * 1024
}
