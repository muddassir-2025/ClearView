package com.muddassir.clearview.goodpost.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Downsampled image loading, with a memory cache and a DISK cache (§26).
 *
 * The project deliberately has no image-loading library — §35 asks for the stack
 * it already has, and a Good Post post carries at most a handful of images, so
 * one small loader is cheaper than a dependency.
 *
 * What makes this worth its own file is the rules it enforces, all of them
 * performance requirements rather than conveniences:
 *
 *  * **Nothing is decoded at full resolution.** A 4000px photo drawn into a
 *    240dp bubble would allocate ~48 MB for a few hundred pixels on screen,
 *    which is how a scrolling feed starts dropping frames and then gets killed.
 *    [load] reads the header first and decodes to the requested width.
 *
 *  * **Nothing is fetched twice in a session.** Both the decode and the network
 *    read go through one LRU keyed by URL, so scrolling back up a channel reuses
 *    the bitmap instead of downloading it again.
 *
 *  * **Nothing is fetched twice ACROSS sessions either.** That was the missing
 *    half: the memory cache dies with the process, so every cold open of a
 *    channel re-downloaded every image at full resolution and then threw most of
 *    each one away in the decoder. Bytes now land in `cacheDir` first, which is
 *    what makes re-opening a channel instant on the second visit.
 *
 * The disk key is the URL's PATH, not the URL. A presigned URL's query is a
 * credential and a timestamp — it changes every time the server is asked for a
 * page, so keying on the whole URL would mean a cache that never hits after the
 * first minute and a directory full of the same photo written forty times. The
 * path is the object key, which is the identity of the bytes.
 */
internal object GoodPostImages {

    /** A fraction of the heap, which is what a bitmap cache may safely use. */
    private const val CACHE_FRACTION = 8

    /** How long a downloaded image may be reused before it is re-read. */
    private const val DISK_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** What the on-disk cache is allowed to occupy before the oldest goes. */
    private const val DISK_LIMIT_BYTES = 80L * 1024 * 1024

    private const val TAG = "GoodPostImages"

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / CACHE_FRACTION).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** In-flight loads, so two rows asking for one image produce one download. */
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var diskDir: File? = null

    @Volatile
    private var trimmed = false

    /**
     * Point the disk cache at a directory, and trim it once per process.
     *
     * Called from the UI rather than from an `Application` because this project
     * has no Application subclass, and adding one to hold a cache directory would
     * be a class whose only job is to exist. Idempotent, and cheap after the
     * first call.
     */
    fun attach(context: Context) {
        if (diskDir != null) return
        val dir = File(context.applicationContext.cacheDir, "goodpost-media")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "no disk cache directory; images will be re-fetched each session")
            return
        }
        diskDir = dir
        if (!trimmed) {
            trimmed = true
            Thread { trim(dir) }.apply { isDaemon = true }.start()
        }
    }

    /** A bitmap already in memory, or null. Lets a row draw before it suspends. */
    fun peek(url: String?): Bitmap? = url?.let { cache.get(it) }

    /**
     * Load [url] at no more than [maxWidthPx] wide, or null if it cannot be read.
     *
     * Null is a normal outcome — an expired signature, a deployment with no
     * bucket, a screen that scrolled away — and every caller draws a placeholder
     * rather than an error, because a missing preview is not a failed screen.
     *
     * Memory, then disk, then the network. Concurrent callers for one URL wait on
     * one download rather than starting their own: a feed drawing the same
     * attachment in two rows is the normal case, not a rare one.
     */
    suspend fun load(url: String?, maxWidthPx: Int): Bitmap? {
        if (url.isNullOrBlank() || maxWidthPx <= 0) return null
        cache.get(url)?.let { return it }

        val lock = inFlight.computeIfAbsent(url) { Mutex() }
        return try {
            lock.withLock {
                // Another caller may have finished it while this one waited.
                cache.get(url)?.let { return it }
                withContext(Dispatchers.IO) { read(url, maxWidthPx) }?.also { cache.put(url, it) }
            }
        } finally {
            inFlight.remove(url, lock)
        }
    }

    /**
     * Warm the cache for images that are about to be on screen (§24).
     *
     * Called with a page's worth of URLs after the page arrives, so the bytes are
     * already local by the time a thumb reaches them. Bounded by [limit] and run
     * a few at a time: a prefetch that opens thirty connections to the bucket and
     * competes with the images actually being drawn would make the screen it is
     * meant to smooth out slower.
     */
    suspend fun prefetch(urls: List<String>, maxWidthPx: Int, limit: Int = 6) {
        val wanted = urls.asSequence()
            .filter { it.isNotBlank() && cache.get(it) == null }
            .distinct()
            .take(limit)
            .toList()

        wanted.chunked(PREFETCH_PARALLEL).forEach { chunk ->
            // A few at a time, and the next batch only when this one is done: a
            // prefetch that fires everything at once competes with the images the
            // reader is actually looking at.
            coroutineScope {
                chunk.map { url -> async { load(url, maxWidthPx) } }.forEach { it.await() }
            }
        }
    }

    /** Forget everything in memory. Called when an administrator signs out. */
    fun clear() {
        cache.evictAll()
    }

    /**
     * Drop one image from memory AND disk (§6's "delete from this device").
     *
     * What a reader is deleting when they say so is the copy on their phone, and
     * the copy on their phone is in two places: the decoded bitmap and the file
     * this loader keeps it in. Removing only the memory entry would leave the
     * bytes to be re-read on the next visit, which is exactly what the button was
     * meant to prevent.
     */
    suspend fun forget(urls: List<String>) = withContext(Dispatchers.IO) {
        urls.forEach { url ->
            cache.remove(url)
            filesFor(url).forEach { file -> runCatching { file.delete() } }
        }
    }

    /** How many bytes the files behind these URLs occupy, at every width. */
    fun diskBytesFor(urls: List<String>): Long = urls.sumOf { url ->
        filesFor(url).sumOf { file -> file.length() }
    }

    /**
     * Write a display copy to disk, and return it.
     *
     * The cache stores what a screen draws rather than what was uploaded. That is
     * the difference between a 4 MB photo and a 90 KB one, and it is the whole of
     * why a list of nine images does not re-download nine originals every time it
     * is opened on a new install. The ORIGINAL is still what Save and Share hand
     * over — this file is a drawing, not a document.
     */
    private fun storeDisplayCopy(file: File, bytes: ByteArray, decoded: Bitmap, maxWidthPx: Int) {
        runCatching {
            val smaller = decoded.width > maxWidthPx
            if (smaller) {
                java.io.ByteArrayOutputStream().use { out ->
                    decoded.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    file.writeBytes(out.toByteArray())
                }
            } else {
                file.writeBytes(bytes)
            }
        }
    }

    /** Forget everything on disk too (§6: manage storage). Returns the bytes freed. */
    suspend fun clearDisk(): Long = withContext(Dispatchers.IO) {
        val dir = diskDir ?: return@withContext 0L
        val freed = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        runCatching { dir.deleteRecursively() }
        cache.evictAll()
        freed
    }

    /** How many bytes this cache is holding on disk. */
    fun diskBytes(): Long =
        diskDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    // ── Internals ────────────────────────────────────────────────────────

    private fun read(url: String, maxWidthPx: Int): Bitmap? {
        val file = fileFor(url, maxWidthPx)
        if (file != null && file.isFile && file.length() > 0) {
            val fresh = System.currentTimeMillis() - file.lastModified() < DISK_TTL_MS
            if (fresh) {
                decodeFile(file, maxWidthPx)?.let { return it }
            }
            // A truncated or undecodable file is worse than no file: it would be
            // served forever. Drop it and fall through to the network.
            runCatching { file.delete() }
        }

        val bytes = download(url) ?: return null
        val decoded = decodeBytes(bytes, maxWidthPx)
        if (file != null && decoded != null) {
            storeDisplayCopy(file, bytes, decoded, maxWidthPx)
        }
        return decoded
    }

    /**
     * One pass that reads only the header, so the sample size can be chosen.
     *
     * `inJustDecodeBounds` is what keeps this honest for a file too: the decoder
     * reads the header and returns, so a 12 MB photo does not become a 12 MB
     * allocation just to measure it.
     */
    private fun boundsOf(source: (BitmapFactory.Options) -> Bitmap?): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source(options)
        return options.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }

    private fun decodeFile(file: File, maxWidthPx: Int): Bitmap? = try {
        val bounds = boundsOf { BitmapFactory.decodeFile(file.absolutePath, it) } ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, maxWidthPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
    } catch (e: Exception) {
        null
    }

    private fun decodeBytes(bytes: ByteArray, maxWidthPx: Int): Bitmap? = try {
        val bounds = boundsOf { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
            ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, maxWidthPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (e: Exception) {
        null
    }

    /**
     * The largest power of two that still leaves at least [maxWidthPx] pixels.
     *
     * Power-of-two is what the decoder actually honours; asking for anything
     * else is rounded down anyway, so computing it here keeps the estimate honest.
     */
    private fun sampleSize(sourceWidth: Int, maxWidthPx: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= maxWidthPx) sample *= 2
        return sample
    }

    private fun download(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ClearView-Android")
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * One stored drawing of a URL, at one width.
     *
     * The name is `<hash of the object key>.<width>.img` — and the width is a
     * separate dotted segment rather than part of the hash, so every copy of one
     * image can be found by prefix. That is what "delete from this device" needs,
     * and it is why the width is in the name at all: the same photo is drawn at
     * 360 px in the grid, 720 in the feed and 1440 full screen, and one stored
     * copy cannot serve all three without one of them being wrong. Sharing a
     * single entry would hand the grid a full-resolution file and the viewer a
     * blurred one, whichever asked first.
     */
    private fun fileFor(url: String, maxWidthPx: Int): File? {
        val dir = diskDir ?: return null
        return File(dir, "${hash(keyOf(url))}.$maxWidthPx.img")
    }

    /** Every stored drawing of one URL, at any width. */
    private fun filesFor(url: String): List<File> {
        val dir = diskDir ?: return emptyList()
        val prefix = hash(keyOf(url)) + "."
        return dir.listFiles { file -> file.isFile && file.name.startsWith(prefix) }?.toList()
            ?: emptyList()
    }

    /** The object key inside the URL: stable while the signature is not. */
    private fun keyOf(url: String): String = try {
        URL(url).path.ifBlank { url }
    } catch (e: Exception) {
        url
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Age first, then size: a stale entry is worth less than a fresh one. */
    private fun trim(dir: File) {
        try {
            val now = System.currentTimeMillis()
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            files.filter { now - it.lastModified() > DISK_TTL_MS }.forEach { it.delete() }

            var total = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
            if (total <= DISK_LIMIT_BYTES) return
            dir.listFiles()?.filter { it.isFile }
                ?.sortedBy { it.lastModified() }
                ?.forEach { file ->
                    if (total <= DISK_LIMIT_BYTES) return
                    val length = file.length()
                    if (file.delete()) total -= length
                }
        } catch (e: Exception) {
            Log.w(TAG, "could not trim the image cache", e)
        }
    }

    private const val PREFETCH_PARALLEL = 3
}
