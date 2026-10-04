package com.muddassir.clearview.media.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Dependency-free network image loader (the app has no image library on
 * purpose), now with a full thumbnail pipeline:
 *
 *  - **Lazy by construction**: the bitmap is only requested once the composable
 *    has actually been laid out (its measured width drives the decode), so a
 *    LazyColumn's off-screen rows never start a download.
 *  - **Downsampled decode**: the image is decoded at the size it will actually
 *    be drawn at (down to the nearest power-of-two sample), instead of pulling
 *    a 1080 px Instagram JPEG into a 320 px card — the single biggest cost on
 *    the Media feed.
 *  - **No duplicate downloads**: concurrent requests for the same URL share ONE
 *    in-flight download (see [ThumbnailCache]); a URL already in memory never
 *    touches the network at all.
 *  - **Two-level cache**: an in-memory LRU sized in BYTES, backed by a disk
 *    cache so scrolling back, reopening the tab, or restarting the app paints
 *    from local bytes.
 *  - **Real failure state**: a URL that isn't an image (e.g. the
 *    `instagram.com/p/<code>/media?size=l` permalink some feeds put in an image
 *    slot) or that fails to load settles into a neutral placeholder — never an
 *    infinite spinner.
 *
 * A device-orientation change re-lays the image and asks for the new width;
 * that is served from the disk cache instantly.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** When false, the loading state is a plain surface box (no spinner). */
    showLoadingSpinner: Boolean = true,
    /** Fired once the bitmap for [url] is ready (or instantly when cached). */
    onLoaded: (() -> Unit)? = null,
    /** Fired when the image could NOT be loaded (missing/invalid URL). */
    onFailed: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    // The laid-out size drives the decode: loading at (roughly) the display
    // width is what keeps large Instagram/YouTube thumbnails cheap. Starts at
    // UNKNOWN so the first request waits for a real measurement instead of
    // fetching a full-size bitmap the card will only shrink.
    var measured by remember(url) { mutableStateOf(IntSize.Zero) }

    // One frame with an unmeasured size: the placeholder shows, then the real
    // request fires. (onSizeChanged always reports a laid-out size before any
    // pixels are needed, so this never delays a visible image by more than a
    // frame.)
    val targetWidth = measured.width.takeIf { it > 0 }

    LaunchedEffect(url, targetWidth) {
        val target = targetWidth ?: return@LaunchedEffect
        bitmap = null
        failed = false
        val loaded = withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) null else ThumbnailCache.get(context, url, target)
        }
        bitmap = loaded
        failed = loaded == null
    }

    // Report the load outcome exactly once per URL — used by callers that want
    // to swap in the real thumbnail the moment it's available.
    LaunchedEffect(url, bitmap != null, failed) {
        if (bitmap != null) onLoaded?.invoke() else if (failed) onFailed?.invoke()
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale
        )
    } else if (showLoadingSpinner && !failed) {
        Box(
            modifier = modifier
                .onSizeChanged { measured = it }
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp
            )
        }
    } else {
        // Loaded-but-failed (or spinner explicitly disabled): a quiet surface
        // box. Never a spinner that can spin forever.
        Box(
            modifier = modifier
                .onSizeChanged { measured = it }
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

/**
 * Thumbnail pipeline shared by every card in the Media tab and the player.
 *
 * Cache keys are `url@<targetWidthPx>`: the same URL rendered in a small feed
 * card and in the (much larger) player backdrop decodes twice, while a second
 * card at the same width reuses the first decode. The raw bytes live in ONE
 * disk entry per URL, so a width change (rotation) never re-downloads.
 */
object ThumbnailCache {

    /** ~1/8 of the app's available heap, measured in real bitmap bytes. */
    private val memory = object : LruCache<String, Bitmap>(
        max((Runtime.getRuntime().maxMemory() / 8).toInt(), 8 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** In-flight downloads, so N cards showing the same URL make 1 request. */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var diskDir: File? = null

    /**
     * Recently failed URLs (epoch ms). A short cool-down stops a broken URL
     * from being re-requested by every recomposition while still allowing a
     * genuine retry after a transient network failure.
     */
    private val failedAt = ConcurrentHashMap<String, Long>()

    private const val RETRY_AFTER_MS = 60_000L

    /**
     * The bitmap for [url] decoded for a display width of [targetWidthPx]
     * (0/negative = a sensible default). Null when the URL is missing, is not
     * an image, or cannot be fetched — callers show their placeholder.
     */
    suspend fun get(context: Context, url: String, targetWidthPx: Int = 0): Bitmap? {
        if (url.isBlank()) return null
        if (isNonImageUrl(url)) return null
        failedAt[url]?.let { at ->
            if (System.currentTimeMillis() - at < RETRY_AFTER_MS) return null
        }
        val width = if (targetWidthPx > 0) targetWidthPx else DEFAULT_WIDTH
        val key = "$url@$width"
        memory.get(key)?.let { return it }

        // Coalesce concurrent requests for the same (url, width).
        val pending = CompletableDeferred<Bitmap?>()
        val existing = inFlight.putIfAbsent(key, pending)
        if (existing != null) return existing.await()

        try {
            val bitmap = fetch(context, url, width)
            if (bitmap == null) {
                failedAt[url] = System.currentTimeMillis()
            } else {
                failedAt.remove(url)
                memory.put(key, bitmap)
            }
            pending.complete(bitmap)
            return bitmap
        } catch (e: Exception) {
            pending.complete(null)
            return null
        } finally {
            inFlight.remove(key, pending)
        }
    }

    /** Fire-and-forget warm-up (Shorts neighbours, the player's own poster). */
    fun prefetch(context: Context, url: String?, targetWidthPx: Int = DEFAULT_WIDTH) {
        val u = url?.takeIf { it.isNotBlank() } ?: return
        val key = "$u@$targetWidthPx"
        if (memory.get(key) != null || inFlight.containsKey(key)) return
        scope.launch { get(context, u, targetWidthPx) }
    }

    private suspend fun fetch(context: Context, url: String, targetWidth: Int): Bitmap? {
        // 1. Local bytes first (disk cache) — no network on a cache hit.
        diskFile(context, url)?.let { file ->
            decodeFile(file, targetWidth)?.let { return it }
            // A corrupt/undecodable cache entry: drop it and re-download once.
            runCatching { file.delete() }
        }
        // 2. Download once, keep the bytes on disk, then decode locally.
        val file = downloadToDisk(context, url) ?: return null
        return decodeFile(file, targetWidth)
    }

    /**
     * Decodes [file] downsampled to about [targetWidth] px wide. Two passes
     * (bounds, then pixels) so a huge JPEG is never fully decoded just to be
     * shrunk — the Android-recommended way, with no third-party dependency.
     */
    private fun decodeFile(file: File, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth)
            // JPEGs carry no alpha channel, so decoding one into an opaque
            // 16-bit bitmap halves the memory it holds for the same picture on
            // screen — the single biggest per-image cost in a scrolling feed.
            // PNG/WebP keep the default ARGB_8888 because they CAN carry
            // transparency, which a 16-bit decode would flatten to black.
            if (isOpaqueFormat(file)) inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    /** True when [file] starts with a JPEG SOI marker (FF D8): it is opaque. */
    private fun isOpaqueFormat(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 &&
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
        }
    } catch (e: Exception) {
        false
    }

    /** Largest power-of-two sample that keeps the width ≥ the target. */
    private fun sampleSize(width: Int, height: Int, targetWidth: Int): Int {
        var sample = 1
        var longest = max(width, height)
        // Cap the decode at ~2× the target so a very wide image still samples
        // down without ever going below the display size (which would blur).
        val budget = max(targetWidth, 1) * 2
        while (longest / 2 >= budget) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private suspend fun downloadToDisk(context: Context, url: String): File? {
        val target = diskFile(context, url) ?: return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
            }
            if (conn.responseCode !in 200..299) return null
            // Content-type gate: an HTML permalink / JSON error body can never
            // be a bitmap, and sniffing it here avoids a pointless decode (and
            // a 15 s stall) on a URL that is known to be a web page.
            val type = conn.contentType?.lowercase().orEmpty()
            if (type.startsWith("text/") || type.contains("json")) return null
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            // Write to a temp file and rename: a half-written file must never
            // be treated as a valid cache entry by the next read.
            val temp = File(target.parentFile, target.name + ".part")
            conn.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            target
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun diskDir(context: Context): File {
        diskDir?.let { return it }
        val dir = File(context.cacheDir, "image_cache").apply { if (!exists()) mkdirs() }
        diskDir = dir
        return dir
    }

    /** `image_cache/<sha1(url)>` — a stable, filesystem-safe name per URL. */
    private fun diskFile(context: Context, url: String): File? = runCatching {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        File(diskDir(context), name)
    }.getOrNull()

    /**
     * True for a URL that cannot possibly decode as an image, so it is rejected
     * before any request is made. The rules live in
     * [com.muddassir.clearview.media.data.InstagramEmbedPayload] so they are
     * covered by unit tests; a GIF is additionally treated as an image here.
     */
    internal fun isNonImageUrl(url: String): Boolean {
        if (url.lowercase().contains(".gif")) return false
        return com.muddassir.clearview.media.data.InstagramEmbedPayload.isNonImageUrl(url)
    }

    /** Test/diagnostic hook: drop every cached bitmap. */
    fun clearMemory() {
        memory.evictAll()
    }

    private const val DEFAULT_WIDTH = 480
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"
}
