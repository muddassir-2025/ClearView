package com.muddassir.clearview.goodpost.data

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * A disk cache for the video ExoPlayer streams (§24, §26).
 *
 * Without it, every open of a video re-downloads it from the bucket — and a
 * channel update is a file somebody sent to a phone, so "re-watching the same
 * clip twice costs the data twice" is the difference between a cheap product and
 * an expensive one. With it, a video plays from disk the second time, and a
 * partly-watched one resumes without re-fetching what it already has.
 *
 * The eviction policy is size-based rather than count-based: one 50 MB clip and
 * fifty 1 MB clips should occupy the same budget, and a count would let the first
 * one evict the other fifty.
 *
 * **One instance per directory, deliberately.** `SimpleCache` keeps an index of
 * the spans it has written, and two of them over one directory corrupt each
 * other's view of it. The instance is therefore held here for the process and
 * never closed: this app has no Application subclass to close it in, and the
 * index is journalled, so a process death mid-write is recoverable rather than
 * fatal — which is what the library's own documentation requires of a cache it
 * expects to be opened once.
 */
@OptIn(UnstableApi::class)
internal object GoodPostVideoCache {

    /** What the video cache may occupy. Small, because this is a cache. */
    private const val LIMIT_BYTES = 160L * 1024 * 1024

    private const val TAG = "GoodPostVideoCache"

    @Volatile
    private var cache: SimpleCache? = null

    /**
     * The data source a player should read through.
     *
     * Falls back to a plain upstream source if the cache cannot be created — a
     * read-only cache directory, a full disk — because a cache is an
     * optimisation and a video that streams without one is still a working
     * product. A cache that fails loudly would be the opposite.
     */
    @Synchronized
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        val upstream = DefaultDataSource.Factory(app)
        val existing = cache
        if (existing != null) return factoryOver(existing, upstream)

        val created = try {
            val dir = File(app.cacheDir, "goodpost-video")
            SimpleCache(dir, LeastRecentlyUsedCacheEvictor(LIMIT_BYTES), StandaloneDatabaseProvider(app))
        } catch (e: Exception) {
            Log.w(TAG, "no video cache; videos will stream every time", e)
            return upstream
        }

        cache = created
        pruneUnaddressableKeys(created)
        return factoryOver(created, upstream)
    }

    /**
     * Drop cached resources that this build can never address, and say how much
     * that freed.
     *
     * ## Why they exist
     *
     * An ExoPlayer cache key defaults to the media URI. This cache used to leave
     * the key alone, so a resource was stored under the WHOLE presigned URL —
     * query string, signature, expiry and all. Two consequences, both of them
     * bugs that were fixed by keying on the object instead ([cacheKeyFor]): the
     * cache never hit, because every page load signs a different query, and the
     * bytes on disk could not be named afterwards, because nobody can reconstruct
     * a signature that expired last week.
     *
     * ## Why they are still here
     *
     * Fixing the key changed the key, so the old entries were left behind as
     * orphans: unreachable by [bytesFor], invisible to [evict], and counted only
     * by the size cap. A phone that watched the same clip on three builds holds it
     * three times over, and "delete this video from my device" removes the one it
     * can name — which is exactly what "it is not getting deleted" looks like from
     * the outside. They would eventually age out under the LRU, but "eventually"
     * is not a size the storage screen can report honestly.
     *
     * ## What counts as addressable
     *
     * The shape [cacheKeyFor] produces: an object path. Anything holding a scheme
     * or a query — `https://…?X-Amz-Signature=…` — is from before the change and is
     * dropped once, on the first video this process plays, before anything reads a
     * size from this cache. Old bytes are re-downloaded only if the reader watches
     * that clip again, which is the same cost as a first watch.
     */
    private fun pruneUnaddressableKeys(simple: SimpleCache) {
        val stale = runCatching { simple.keys.filterNot(::isAddressable) }.getOrDefault(emptyList())
        if (stale.isEmpty()) return

        var freed = 0L
        stale.forEach { key ->
            freed += runCatching { simple.getCachedBytes(key, 0, Long.MAX_VALUE) }.getOrDefault(0L)
            runCatching { simple.removeResource(key) }
        }
        Log.i(TAG, "dropped ${stale.size} unreachable cache entrie(s), ${freed / 1024} KB")
    }

    /** True for a key that is an object path, which is the only shape this build
     * stores under. */
    private fun isAddressable(key: String): Boolean =
        key.startsWith("/") && !key.contains('?') && !key.contains("://")

    /** Bytes currently held on disk, for the channel's storage screen (§6). */
    fun bytesCached(): Long = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)

    /**
     * The cache key for a media URL.
     *
     * The object key, not the URL, for the same reason the image cache is: a
     * presigned URL's query is a credential that changes on every page load, so a
     * cache keyed on it would miss every time — the video would be re-downloaded
     * on each visit and the cache would fill with copies of one clip that nothing
     * can find again. It also has to be a value the app can compute LATER, which
     * is what makes "delete this video from my phone" possible at all.
     */
    fun cacheKeyFor(url: String): String = try {
        java.net.URL(url).path.ifBlank { url }
    } catch (e: Exception) {
        url
    }

    /** Bytes cached for one media item, for the storage row (§6). */
    fun bytesFor(url: String): Long = runCatching {
        val key = cacheKeyFor(url)
        cache?.getCachedBytes(key, 0, Long.MAX_VALUE)?.takeIf { it > 0 } ?: 0L
    }.getOrDefault(0L)

    /**
     * Drop one video's local copy (§6: delete from this device).
     *
     * The copy on the server is untouched — that is the whole distinction the
     * storage screen exists to make — and a reader who opens the video again
     * streams it once more rather than being told it is gone.
     */
    @Synchronized
    fun evict(url: String): Long {
        val simple = cache ?: return 0L
        val key = cacheKeyFor(url)
        val bytes = runCatching { simple.getCachedBytes(key, 0, Long.MAX_VALUE) }.getOrDefault(0L)
        runCatching { simple.removeResource(key) }
        return bytes
    }

    /** Drop everything. Called from "manage storage" (§6). */
    @Synchronized
    fun clear() {
        runCatching { cache?.keys?.forEach { key -> cache?.removeResource(key) } }
    }

    private fun factoryOver(
        cache: SimpleCache,
        upstream: DataSource.Factory
    ): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        // A corrupt or half-written span should cost a re-download, not a
        // playback failure: the reader asked to watch a video, not to hear about
        // a cache.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
