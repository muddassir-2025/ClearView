package com.muddassir.clearview.media.data

import android.content.Context
import android.util.Log
import com.muddassir.clearview.media.download.OnDeviceStreamExtractor
import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The AUDIO-ONLY URL for a video, resolved on this device.
 *
 * ## Why this exists
 *
 * A YouTube video in this app plays through the official IFrame player inside a
 * WebView. That is the right player for watching, and the wrong one for
 * listening: the embed stops when the app leaves the foreground, so "play it
 * and put the phone in your pocket" — which is most of what a long talk,
 * lecture or recitation is for — was impossible.
 *
 * Listening does not need the video track at all. YouTube publishes plain
 * progressive audio streams, and [OnDeviceStreamExtractor] already knows how to
 * find the right one for a download (original language, playable container,
 * highest bitrate). Listen mode asks it the same question and hands the answer
 * to the background player, so a 90-minute talk costs a fraction of the bytes a
 * video would and keeps playing with the screen off.
 *
 * ## One answer, two callers
 *
 * Both the tap that starts listening and the retry inside the playback service
 * after a stream dies come through here, deliberately: two copies of "how do I
 * listen to this video" is how the on-device and the retried URL end up
 * disagreeing about which language track to play.
 *
 * ## Cached, because the answer does not change
 *
 * These URLs are signed and valid for hours ([InstagramStreamResolver]
 * .isStreamUsable] reads the expiry out of the URL itself). Re-extracting on
 * every tap would mean a several-second innertube round trip to learn something
 * this device already wrote down — the same wait [ResolvedStreamStore] was built
 * to remove for Reels, so both share that one store, namespaced by key.
 */
object AudioStreamResolver {

    private const val TAG = "AudioStreamResolver"

    /**
     * What to resolve, so the answer survives being handed across the app.
     *
     * A video id alone is not enough — the two platforms resolve by completely
     * different routes — and the Instagram fields have to travel with it because
     * the resolved URL is all the service holds once playback starts, and a
     * retry then has nothing left to resolve FROM.
     */
    data class Request(
        val videoId: String,
        val isInstagram: Boolean,
        val instagramUrl: String?,
        val mediaUrl: String?
    )

    /**
     * The audio-only stream URL for [request], or null when there is none to be
     * had (age-restricted, a live broadcast, a photo post, no connection).
     *
     * [fresh] skips the cache and overwrites it — the retry path, where the
     * whole point is that the remembered URL is the one that just failed.
     */
    suspend fun audioUrlFor(
        context: Context,
        request: Request,
        fresh: Boolean = false
    ): String? {
        if (request.videoId.isBlank()) return null
        val key = cacheKey(request)
        val store = store(context)
        val now = System.currentTimeMillis()

        if (!fresh) {
            store.get(key)?.let { cached ->
                if (InstagramStreamResolver.isStreamUsable(cached.stream.videoUrl, cached.atMillis, now)) {
                    Log.d(TAG, "Reusing the audio stream resolved for ${request.videoId}")
                    return cached.stream.videoUrl
                }
            }
        } else {
            store.forget(key)
        }

        val url = if (request.isInstagram) {
            resolveInstagramAudio(context, request, fresh)
        } else {
            resolveYouTubeAudio(request.videoId)
        } ?: return null

        store.put(key, InstagramStreamResolver.ResolvedStream(url, null), System.currentTimeMillis())
        return url
    }

    /**
     * A Reel or video post: its own progressive `.mp4` is the audio source.
     *
     * No audio/video split exists to be had here — Meta publishes one muxed
     * file — so listen mode plays that file and simply never draws it. That is
     * still the win it is for YouTube: the clip keeps playing once the app is in
     * the background, which is exactly what the in-screen player cannot do.
     */
    private suspend fun resolveInstagramAudio(
        context: Context,
        request: Request,
        fresh: Boolean
    ): String? {
        val source = request.instagramUrl ?: request.videoId
        return runCatching {
            InstagramStreamResolver.resolvePlayableStream(context, source, fresh = fresh)?.videoUrl
        }.getOrElse { error ->
            Log.d(TAG, "No audio for ${request.videoId}: ${error.message}")
            null
        }
    }

    /**
     * A YouTube video: the highest-quality stream of the original-language
     * track, via the same extractor the downloader uses.
     *
     * Failures are returned as null rather than thrown. This is reached from a
     * button press and from an error handler inside a foreground service, and
     * neither of those has anywhere useful to put an exception — the caller
     * needs a yes or a no.
     */
    private suspend fun resolveYouTubeAudio(videoId: String): String? =
        withContext(Dispatchers.IO) {
            runCatching { OnDeviceStreamExtractor.extract(videoId).url }
                .getOrElse { error ->
                    Log.d(TAG, "No audio stream for $videoId: ${error.message}")
                    null
                }
        }

    /**
     * The store key for [request].
     *
     * Prefixed rather than bare, so an audio resolution can never be mistaken
     * for a Reel's video resolution in the shared store — they are both signed
     * CDN URLs and both answer to the same shortcode space, and handing a
     * listening session a video URL (or the reverse) would half-work, which is
     * worse than failing.
     */
    internal fun cacheKey(request: Request): String {
        val id = if (request.isInstagram) {
            InstagramStreamResolver.extractShortcode(request.instagramUrl ?: request.videoId)
        } else {
            request.videoId
        }
        return (if (request.isInstagram) "ig-audio:" else "yt-audio:") + id
    }

    /**
     * The one store for this process, built from an application context taken
     * here (never held on this singleton across a rotation).
     */
    @Volatile
    private var cachedStore: ResolvedStreamStore? = null

    private fun store(context: Context): ResolvedStreamStore {
        cachedStore?.let { return it }
        return synchronized(this) {
            cachedStore ?: ResolvedStreamStore(context.applicationContext).also { cachedStore = it }
        }
    }

    /**
     * The request for [video], so the mapping from the app's model to this
     * resolver's inputs is written once.
     */
    fun requestFor(video: MediaVideo): Request = Request(
        videoId = video.videoId,
        isInstagram = video.platform == MediaPlatform.INSTAGRAM ||
            video.videoId.startsWith("ig_"),
        instagramUrl = video.instagramUrl,
        mediaUrl = video.mediaUrl
    )
}
