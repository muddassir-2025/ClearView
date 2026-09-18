package com.muddassir.clearview.goodpost.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * One still frame of a video, so a tile of one says WHICH video it is.
 *
 * ## Why this exists
 *
 * A channel's media strip and the "Media and links" grid draw a tile per file,
 * and a video's tile had nothing to draw: its bytes are not an image, so the
 * image loader could only fail on it, and the tile was a grey square wearing a
 * camera icon. Twelve grey squares with camera icons is a screen that tells a
 * reader nothing — not which clip is the one they were looking for, not which
 * they had already watched. A frame from the clip answers both at a glance.
 *
 * ## How the frame is obtained
 *
 * [MediaMetadataRetriever] reads an HTTP(S) URL directly, which is the only
 * option here: the file is on a bucket, the URL is a lease, and nothing has
 * downloaded the clip yet — this IS the download, and it is deliberately the
 * smallest read that produces a picture. A frame one second in, because frame
 * zero of a clip that opens on a fade is black, and the nearest sync frame
 * rather than an exact one, because seeking to an exact time can mean reading
 * most of the file to decode forward to it.
 *
 * ## Why it is cached twice
 *
 * The memory LRU makes scrolling back up the grid free. The disk copy is for
 * every visit after this one: a strip of twelve clips otherwise cost twelve
 * metadata reads per open, which is a screen that spins every time. Both are
 * keyed on the OBJECT path rather than the URL, for the same reason everything
 * else here is — a presigned query changes on every page load.
 *
 * Every failure is silent. A frame that cannot be read leaves the tile exactly
 * as it was before this file existed, which is a working screen.
 */
internal object GoodPostVideoPoster {

    private const val TAG = "GoodPostVideoPoster"

    /**
     * Where in the clip the still comes from: one second in.
     *
     * Not zero — a clip that opens on a fade, a title card or a black frame is
     * common enough that a strip of them would all look identical, which is the
     * problem this exists to solve.
     */
    private const val AT_US = 1_000_000L

    /** Enough for a screenful of tiles, and a few scrolled past. */
    private const val MEMORY_ENTRIES = 24

    /** What the on-disk cache may occupy. A frame is ~30 KB, so this is generous. */
    private const val DISK_LIMIT_BYTES = 24L * 1024 * 1024

    /** Longer than an image's: a frame of a clip does not change, ever. */
    private const val DISK_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** The widest a still is ever decoded to. A tile is ~130dp. */
    private const val MAX_WIDTH_PX = 480

    /**
     * How many retrievers may run at once.
     *
     * Each one opens its own connection and reads a video's header, so a grid of
     * twelve clips asking together would be twelve connections to the bucket on
     * the same screen — the burst that makes the whole grid look slow. Two at a
     * time fills a screen in a few hundred milliseconds and leaves the network to
     * the media the reader is actually looking at.
     */
    private const val PARALLEL = 2

    private val cache = LruCache<String, Bitmap>(MEMORY_ENTRIES)

    /** In-flight decodes, so two tiles for one clip produce one read. */
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    private val gate = Semaphore(PARALLEL)

    @Volatile
    private var diskDir: File? = null

    /**
     * Point the disk cache at a directory.
     *
     * Idempotent, and called from the UI for the same reason [GoodPostImages]
     * is: this project has no Application subclass and a class that exists only
     * to hold a cache directory is not worth adding one for.
     */
    fun attach(context: Context) {
        if (diskDir != null) return
        val dir = File(context.applicationContext.cacheDir, "goodpost-posters")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "no poster cache directory; stills will be re-read each session")
            return
        }
        diskDir = dir
        Thread { trim(dir) }.apply { isDaemon = true }.start()
    }

    /** A still already decoded, or null. Lets a tile draw before it suspends. */
    fun peek(url: String?): Bitmap? {
        val key = url?.let { keyOf(it) } ?: return null
        return cache.get(key)
    }

    /**
     * The still for [url], decoded to at most [widthPx], or null when there is
     * none to be had. Null is a normal answer — an expired URL, a codec the
     * platform cannot read, a deployment with no bucket.
     */
    suspend fun load(url: String?, widthPx: Int): Bitmap? {
        if (url.isNullOrBlank() || widthPx <= 0) return null
        val key = keyOf(url) ?: return null
        cache.get(key)?.let { return it }

        val lock = inFlight.computeIfAbsent(key) { Mutex() }
        return try {
            lock.withLock {
                cache.get(key)?.let { return it }
                val width = widthPx.coerceIn(96, MAX_WIDTH_PX)
                withContext(Dispatchers.IO) {
                    // A stored still costs nothing and does not touch the gate: the
                    // limit exists for the reads that go to the bucket.
                    val stored = readFile(key, width)
                    if (stored != null) return@withContext stored
                    gate.withPermit { grab(url, width)?.also { store(key, width, it) } }
                }?.also { cache.put(key, it) }
            }
        } finally {
            inFlight.remove(key, lock)
        }
    }

    /** Drop the stills for these URLs, from memory and from disk. */
    suspend fun forget(urls: List<String>) = withContext(Dispatchers.IO) {
        urls.forEach { url ->
            val key = keyOf(url) ?: return@forEach
            cache.remove(key)
            filesFor(key).forEach { file -> runCatching { file.delete() } }
        }
    }

    /** How many bytes the stored stills occupy. */
    fun diskBytes(): Long =
        diskDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    // ── Internals ────────────────────────────────────────────────────────

    /** Read a stored still, if one is there and still fresh. */
    private fun readFile(key: String, widthPx: Int): Bitmap? {
        val dir = diskDir ?: return null
        val file = File(dir, "${hash(key)}.$widthPx.poster")
        if (!file.isFile || file.length() <= 0) return null
        if (System.currentTimeMillis() - file.lastModified() > DISK_TTL_MS) {
            runCatching { file.delete() }
            return null
        }
        return runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull()
    }

    private fun store(key: String, widthPx: Int, frame: Bitmap) {
        val dir = diskDir ?: return
        runCatching {
            java.io.ByteArrayOutputStream().use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, 78, out)
                File(dir, "${hash(key)}.$widthPx.poster").writeBytes(out.toByteArray())
            }
        }
    }

    /**
     * One frame, from the clip's own URL.
     *
     * Scaled by the platform when it can be (API 27+, using the video's own
     * dimensions so the frame is not distorted), and by hand when it cannot:
     * `getFrameAtTime` hands back a full-size frame — 8 MB for a 1080p clip —
     * which is a lot of memory to allocate per tile on a device that is also
     * playing one.
     */
    private fun grab(url: String, widthPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, emptyMap())
            scaledFrame(retriever, widthPx)
        } catch (e: Exception) {
            // The class, never the URL: a presigned query is a credential and log
            // lines travel.
            Log.w(TAG, "no still: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaledFrame(retriever: MediaMetadataRetriever, widthPx: Int): Bitmap? {
        val sourceWidth =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
        val sourceHeight =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && sourceWidth > 0 && sourceHeight > 0) {
            val width = minOf(widthPx, sourceWidth)
            val height = (sourceHeight.toLong() * width / sourceWidth).toInt().coerceAtLeast(1)
            retriever.getScaledFrameAtTime(
                AT_US,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                width,
                height
            )?.let { return it }
        }

        val frame = retriever.getFrameAtTime(AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        if (frame.width <= widthPx) return frame

        val width = widthPx
        val height = (frame.height.toLong() * width / frame.width).toInt().coerceAtLeast(1)
        val scaled = runCatching { Bitmap.createScaledBitmap(frame, width, height, true) }
            .getOrNull()
        if (scaled != null && scaled !== frame) frame.recycle()
        return scaled ?: frame
    }

    /** Every stored still of one clip, at any width. */
    private fun filesFor(key: String): List<File> {
        val dir = diskDir ?: return emptyList()
        val prefix = hash(key) + "."
        return dir.listFiles { file -> file.isFile && file.name.startsWith(prefix) }?.toList()
            ?: emptyList()
    }

    /** The object key inside the URL: stable while the signature is not. */
    private fun keyOf(url: String): String? = try {
        java.net.URL(url).path.takeIf { it.isNotBlank() } ?: url
    } catch (e: Exception) {
        url.takeIf { it.isNotBlank() }
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Age first, then size — the same policy the image cache uses. */
    private fun trim(dir: File) {
        try {
            val now = System.currentTimeMillis()
            dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                if (now - file.lastModified() > DISK_TTL_MS) runCatching { file.delete() }
            }

            var total = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
            if (total <= DISK_LIMIT_BYTES) return
            dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }?.forEach { file ->
                if (total <= DISK_LIMIT_BYTES) return
                val length = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) total -= length
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not trim the poster cache", e)
        }
    }
}
