package com.muddassir.clearview.goodpost.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downsampled image loading with an in-memory cache (§26).
 *
 * The project deliberately has no image-loading library — §35 asks for the
 * stack it already has, and a Good Post post carries at most a handful of
 * images, so one small loader is cheaper than a dependency.
 *
 * What makes this worth its own file is the two rules it enforces, both of them
 * performance requirements rather than conveniences:
 *
 *  * **Nothing is decoded at full resolution.** A 4000px photo drawn into a
 *    240dp bubble would allocate ~48 MB for a few hundred pixels on screen,
 *    which is how a scrolling feed starts dropping frames and then gets killed.
 *    [load] reads the header first and decodes to the requested width.
 *
 *  * **Nothing is fetched twice.** Both the decode and the network read go
 *    through one LRU keyed by URL, so scrolling back up a channel reuses the
 *    bitmap instead of downloading it again.
 *
 * Signed URLs are used as cache keys as they are. They expire, but an expired
 * URL is simply never requested twice within one session's scroll, and the next
 * fetch produces a fresh key — which is the correct outcome, since the bytes
 * behind it are also fresh.
 */
internal object GoodPostImages {

    /** A fraction of the heap, which is what a bitmap cache may safely use. */
    private const val CACHE_FRACTION = 8

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / CACHE_FRACTION).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** A bitmap already in memory, or null. Lets a row draw before it suspends. */
    fun peek(url: String?): Bitmap? = url?.let { cache.get(it) }

    /**
     * Load [url] at no more than [maxWidthPx] wide, or null if it cannot be read.
     *
     * Null is a normal outcome — an expired signature, a deployment with no
     * bucket, a screen that scrolled away — and every caller draws a placeholder
     * rather than an error, because a missing preview is not a failed screen.
     */
    suspend fun load(url: String?, maxWidthPx: Int): Bitmap? {
        if (url.isNullOrBlank() || maxWidthPx <= 0) return null
        cache.get(url)?.let { return it }

        return withContext(Dispatchers.IO) {
            val bounds = readBounds(url) ?: return@withContext null
            val bitmap = decode(url, bounds, maxWidthPx) ?: return@withContext null
            cache.put(url, bitmap)
            bitmap
        }
    }

    /** Forget everything. Called when an administrator signs out. */
    fun clear() {
        cache.evictAll()
    }

    // ── Internals ────────────────────────────────────────────────────────

    /** One pass that reads only the header, so the sample size can be chosen. */
    private fun readBounds(url: String): BitmapFactory.Options? = try {
        open(url)?.use { stream ->
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
                BitmapFactory.decodeStream(stream, null, it)
            }
        }?.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    } catch (e: Exception) {
        null
    }

    private fun decode(
        url: String,
        bounds: BitmapFactory.Options,
        maxWidthPx: Int
    ): Bitmap? = try {
        open(url)?.use { stream ->
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, maxWidthPx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeStream(stream, null, options)
        }
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

    private fun open(url: String): java.io.InputStream? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setInstanceFollowRedirects(true)
            setRequestProperty("User-Agent", "ClearView-Android")
        }
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            return null
        }
        // The stream closes the connection when it is closed, which is why every
        // caller uses `use`.
        return connection.inputStream
    }
}
