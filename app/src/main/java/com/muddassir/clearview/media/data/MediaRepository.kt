package com.muddassir.clearview.media.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.muddassir.clearview.media.model.FeedFilter
import com.muddassir.clearview.media.model.InstagramMediaType
import com.muddassir.clearview.media.model.MediaChannelUpdate
import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.SavedChannel
import com.muddassir.clearview.media.model.SavedPlaylist
import com.muddassir.clearview.media.util.MediaUpdates
import com.muddassir.clearview.media.util.PlaylistPageParser
import com.muddassir.clearview.media.util.decodeFeedFilter
import com.muddassir.clearview.media.util.encodeFeedFilter
import com.muddassir.clearview.media.util.extractYouTubePlaylistId
import com.muddassir.clearview.media.util.parseYouTubeRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Repository for the Media tab: manages saved channels (add/remove/select,
 * persisted in SharedPreferences) and the latest-videos feed (YouTube RSS,
 * cached to a file per channel so the tab loads instantly offline).
 *
 * All IO happens on [Dispatchers.IO]; the channel list reads are SharedPreferences
 * reads (safe on any thread).
 */
class MediaRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Preload the duration resolver's on-disk cache so resolved durations
        // survive restarts (no watch-page re-fetch on the first refresh).
        VideoDurationResolver.init(appContext)
    }

    // ── Saved channels ──────────────────────────────────────────────

    /**
     * Saved channels, oldest first. A new install is seeded with the default
     * channel (Safina Society) so the Media tab isn't empty — but ONLY on the
     * very first read (KEY_CHANNELS never written). If the user later removes
     * every channel, the empty list is respected: the default must NOT come
     * back.
     */
    fun getSavedChannels(): List<SavedChannel> {
        val json = prefs.getString(KEY_CHANNELS, null)
        if (json == null) {
            saveChannels(listOf(DEFAULT_CHANNEL))
            return listOf(DEFAULT_CHANNEL)
        }
        return parseChannels(json)
    }

    /** Result of adding a channel: success (with the channel/channels) or an error message. */
    sealed class AddChannelResult {
        data class Success(val channels: List<SavedChannel>) : AddChannelResult() {
            constructor(channel: SavedChannel) : this(listOf(channel))
            val channel: SavedChannel get() = channels.first()
        }
        data class Error(val message: String) : AddChannelResult()
    }

    /**
     * Resolves [input] (bare id, channel URL or @handle) and saves the channel.
     * Supports both YouTube channels and Instagram public profiles — if both
     * are found for the same handle/query, both are added.
     */
    suspend fun addChannel(
        input: String,
        platform: MediaPlatform = MediaPlatform.YOUTUBE
    ): AddChannelResult = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return@withContext AddChannelResult.Error("Enter a channel id, URL or @handle")
        }

        val existing = getSavedChannels()
        val toAdd = mutableListOf<SavedChannel>()

        // The UI chooses the platform for bare handles. Explicit URLs still
        // override the choice so a pasted Instagram URL cannot be sent to the
        // YouTube resolver (or vice versa).
        val explicitInstagram = trimmed.contains("instagram.com/", ignoreCase = true) ||
            trimmed.contains("instagr.am/", ignoreCase = true)
        val selectedPlatform = when {
            explicitInstagram -> MediaPlatform.INSTAGRAM
            trimmed.contains("youtube.com/", ignoreCase = true) ||
                trimmed.contains("youtu.be/", ignoreCase = true) -> MediaPlatform.YOUTUBE
            trimmed.contains("x.com/", ignoreCase = true) ||
                trimmed.contains("twitter.com/", ignoreCase = true) -> MediaPlatform.X
            else -> platform
        }

        // 1. Resolve exactly the selected platform.
        if (selectedPlatform == MediaPlatform.X) {
            // RSS bridges are optional content providers, not identity
            // resolvers. They can be temporarily unavailable or return an
            // empty feed for a valid/new account. Save the validated profile
            // immediately and let the normal refresh retry its posts later.
            val username = XSource.extractUsername(trimmed)
            if (username != null) {
                val channelId = "x_${username.lowercase()}"
                if (existing.none { it.channelId == channelId }) {
                    toAdd.add(
                        SavedChannel(
                            channelId = channelId,
                            displayName = "@$username",
                            sourceRef = trimmed,
                            addedAtEpochMillis = System.currentTimeMillis(),
                            platform = MediaPlatform.X
                        )
                    )
                }
            }
        } else if (selectedPlatform == MediaPlatform.YOUTUBE) {
            val ytChannelId = ChannelIdResolver.resolve(trimmed)
            if (ytChannelId != null && existing.none { it.channelId == ytChannelId } && toAdd.none { it.channelId == ytChannelId }) {
                toAdd.add(
                    SavedChannel(
                        channelId = ytChannelId,
                        displayName = channelDisplayName(trimmed),
                        sourceRef = trimmed,
                        addedAtEpochMillis = System.currentTimeMillis(),
                        platform = MediaPlatform.YOUTUBE
                    )
                )
            }
        }

        // 2. Resolve Instagram profile
        val igUsername = if (selectedPlatform == MediaPlatform.INSTAGRAM) {
            InstagramResolver.extractUsername(trimmed)
        } else null
        if (igUsername != null) {
            // Provider access is best-effort. Instagram frequently blocks
            // unauthenticated profile requests, but that must not prevent a
            // valid profile URL/handle from being subscribed. Save the exact
            // requested username now; the feed refresh can populate posts
            // whenever a provider becomes reachable.
            val resolvedUsername = igUsername
            val igChannelId = "ig_${resolvedUsername.lowercase()}"
            if (existing.none { it.channelId == igChannelId } && toAdd.none { it.channelId == igChannelId }) {
                toAdd.add(
                    SavedChannel(
                        channelId = igChannelId,
                        displayName = "@$resolvedUsername",
                        sourceRef = "@$resolvedUsername",
                        avatarUrl = null,
                        addedAtEpochMillis = System.currentTimeMillis(),
                        platform = MediaPlatform.INSTAGRAM
                    )
                )
            }
        }

        if (toAdd.isEmpty()) {
            val already = existing.any {
                it.sourceRef.equals(trimmed, ignoreCase = true) ||
                    it.channelId == ChannelIdResolver.extractChannelId(trimmed) ||
                    (igUsername != null && it.channelId == "ig_${igUsername.lowercase()}")
            }
            return@withContext if (already) {
                AddChannelResult.Error("That channel / profile is already saved")
            } else {
                AddChannelResult.Error(
                    "Couldn't find that channel or profile. Check the handle or paste the URL."
                )
            }
        }

        saveChannels(existing + toAdd)

        // Baseline notification guard
        for (channel in toAdd) {
            if (channel.platform == MediaPlatform.YOUTUBE) {
                runCatching {
                    refreshVideos(channel.channelId, enrich = false)?.let { fresh ->
                        if (fresh.isNotEmpty()) {
                            markVideosNotified(getNotifiedVideoIds() + fresh.map { it.videoId })
                            markNotificationBaselineComplete(channel.channelId)
                        }
                    }
                }
            }
        }

        AddChannelResult.Success(toAdd)
    }

    /**
     * Human-friendly channel name from what the user pasted: the @handle for
     * handle / handle-URL inputs (incl. Unicode handles like @الفلاح-هدف), the
     * input itself otherwise (bare ids, /channel/ URLs). Never the full pasted
     * URL for an /@ URL.
     */
    private fun channelDisplayName(input: String): String = when {
        input.contains("/@") ->
            input.substringAfterLast("/@").substringBefore('/').substringBefore('?').trim()
        input.startsWith("@") -> input.removePrefix("@")
        else -> input
    }

    fun removeChannel(channelId: String) {
        val channels = getSavedChannels().filterNot { it.channelId == channelId }
        saveChannels(channels)
        if (getSelectedChannelId() == channelId) {
            setSelectedChannel(channels.firstOrNull()?.channelId)
        }
    }

    fun getSelectedChannelId(): String? =
        prefs.getString(KEY_SELECTED_CHANNEL, null)

    fun setSelectedChannel(channelId: String?) {
        prefs.edit().putString(KEY_SELECTED_CHANNEL, channelId).apply()
    }

    private fun saveChannels(channels: List<SavedChannel>) {
        val arr = JSONArray()
        channels.forEach { c ->
            arr.put(
                JSONObject()
                    .put("channelId", c.channelId)
                    .put("displayName", c.displayName)
                    .put("sourceRef", c.sourceRef)
                    .put("avatarUrl", c.avatarUrl ?: "")
                    .put("addedAt", c.addedAtEpochMillis)
                    .put("platform", c.platform.name)
                    .put("instagramType", c.instagramType?.name ?: JSONObject.NULL)
                    .put("notificationsMuted", c.notificationsMuted)
            )
        }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    private fun parseChannels(json: String): List<SavedChannel> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val platform = runCatching {
                    MediaPlatform.valueOf(o.optString("platform", "YOUTUBE"))
                }.getOrDefault(MediaPlatform.YOUTUBE)
                val rawAvatar = o.optString("avatarUrl", "").ifBlank { null }
                val cleanAvatar = if (platform == MediaPlatform.INSTAGRAM && !InstagramRssParser.isRealAvatarUrl(rawAvatar)) null else rawAvatar

                SavedChannel(
                    channelId = o.getString("channelId"),
                    displayName = o.optString("displayName", o.getString("channelId")),
                    sourceRef = o.optString("sourceRef", o.getString("channelId")),
                    avatarUrl = cleanAvatar,
                    // Missing on channels saved by older builds → 0 → the
                    // worker's subscription guard stays inactive for them.
                    addedAtEpochMillis = o.optLong("addedAt", 0L),
                    platform = platform,
                    instagramType = o.optString("instagramType", "").takeIf { it.isNotBlank() }?.let {
                        runCatching { InstagramMediaType.valueOf(it) }.getOrNull()
                    },
                    // Absent on channels saved by older builds → false → ON,
                    // which is exactly how they behaved before muting existed.
                    notificationsMuted = o.optBoolean("notificationsMuted", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches missing channel avatars (best-effort) and persists the updated
     * channel list. Returns the updated list when anything changed, else null.
     */
    suspend fun fillMissingAvatars(channels: List<SavedChannel>): List<SavedChannel>? {
        if (channels.none { it.avatarUrl == null }) return null
        var changed = false
        val updated = channels.map { c ->
            if (c.avatarUrl == null) {
                val url = when (c.platform) {
                    MediaPlatform.INSTAGRAM -> {
                        val user = c.channelId.removePrefix("ig_")
                        InstagramResolver.fetchProfile(user)?.avatarUrl
                            ?.takeIf { InstagramRssParser.isRealAvatarUrl(it) }
                    }
                    // An X channel id is "x_<handle>", so the prefix comes off
                    // before resolving — and the avatar comes from the profile
                    // API, never from youtube.com/channel/ (which only ever
                    // answered with an empty page for an X handle).
                    MediaPlatform.X -> XSource.fetchAvatar(c.channelId.removePrefix("x_"))
                    MediaPlatform.YOUTUBE -> ChannelAvatarResolver.fetchAvatar(c.channelId)
                }
                if (url != null) {
                    changed = true
                    c.copy(avatarUrl = url)
                } else {
                    c
                }
            } else {
                c
            }
        }
        if (!changed) return null
        saveChannels(updated)
        return updated
    }

    /**
     * Silences (or un-silences) [channelId]'s notifications.
     *
     * Works for EVERY source — YouTube, Instagram and X are all just saved
     * channels to this layer, so a channel is muted independently of which
     * platform it came from, and of every other channel. Returns the updated
     * list for the caller to drop into its state, or null when nothing changed
     * (an unknown channel, or the flag already held this value) so the caller
     * can skip a needless recomposition.
     *
     * Cancelling what is already in the shade is the CALLER's job (it holds the
     * Context and the notifier), which keeps this class free of any dependency
     * on the notification worker.
     */
    fun setChannelNotificationsMuted(channelId: String, muted: Boolean): List<SavedChannel>? {
        val channels = getSavedChannels()
        val index = channels.indexOfFirst { it.channelId == channelId }
        if (index < 0 || channels[index].notificationsMuted == muted) return null
        val updated = channels.toMutableList()
        updated[index] = updated[index].copy(notificationsMuted = muted)
        saveChannels(updated)
        return updated
    }

    // ── Manual videos (added by URL) ───────────────────────────────

    /** Result of resolving a pasted video URL into a [MediaVideo]. */
    sealed class ResolveVideoResult {
        /** Resolved metadata; the caller should save it as a manual video. */
        data class Success(val video: MediaVideo) : ResolveVideoResult()

        /** The video is already part of [channel]'s feed (no need to add). */
        data class AlreadyExists(val video: MediaVideo) : ResolveVideoResult()

        data class Error(val message: String) : ResolveVideoResult()
    }

    /**
     * Resolves a user-pasted YouTube URL for [fallbackChannel] (the channel
     * whose feed the user is adding into). Verifies the video against the
     * channel's cached/fresh RSS first (best-effort); when it isn't there,
     * fetches metadata via YouTube's public oEmbed endpoint (title, channel
     * name, thumbnail — no duration, no description). Publication time stays
     * 0 (unknown) so the video is never presented as newly published.
     */
    suspend fun resolveVideoByUrl(
        input: String,
        fallbackChannel: SavedChannel?
    ): ResolveVideoResult = withContext(Dispatchers.IO) {
        // ONE parser for every link form YouTube hands out — watch, youtu.be,
        // /shorts/, /embed/, /live/ (which the old regex set could not read at
        // all) and a bare id. The parsed KIND is kept: a /shorts/ link really is
        // a Short, so it opens as a vertical Short instead of being filed as a
        // long video (which is what the old hardcoded `isShort = false` did).
        val ref = parseYouTubeRef(input)
            ?: return@withContext ResolveVideoResult.Error(
                "That doesn't look like a YouTube video URL."
            )
        val videoId = ref.videoId
        // Already in this channel's cache? Reuse the RSS metadata.
        fallbackChannel?.let { channel ->
            getCachedVideos(channel.channelId)
                ?.first?.firstOrNull { it.videoId == videoId }
                ?.let { return@withContext ResolveVideoResult.AlreadyExists(it) }
        }
        // Already in the channel's LIVE feed (cache stale)? Same.
        fallbackChannel?.let { channel ->
            fetchFeedVideos(channel.channelId)
                ?.firstOrNull { it.videoId == videoId }
                ?.let { return@withContext ResolveVideoResult.AlreadyExists(it) }
        }
        // Not in the feed — fetch metadata from oEmbed (no API key needed).
        val meta = fetchOEmbedMetadata(videoId)
            ?: return@withContext ResolveVideoResult.Error(
                "Couldn't load video info. Check the URL and your connection."
            )
        ResolveVideoResult.Success(
            MediaVideo(
                videoId = videoId,
                title = meta.title,
                channelId = fallbackChannel?.channelId ?: "",
                channelName = fallbackChannel?.displayName ?: meta.authorName,
                publishedAtEpochMillis = 0L, // unknown date — never pretend it's new
                thumbnailUrl = meta.thumbnailUrl,
                viewCount = 0L,
                isShort = ref.isShort,
                // Same live-thumbnail signal as the RSS parser.
                isLive = meta.thumbnailUrl.contains("_live.", ignoreCase = true),
                // oEmbed omits duration, so resolve it from the watch page too
                // (best-effort; the card just shows no time badge on failure).
                durationSeconds = runCatching {
                    VideoDurationResolver.fetchDuration(videoId)
                }.getOrNull() ?: 0L
            )
        )
    }

    private data class OEmbedMeta(
        val title: String,
        val authorName: String,
        val thumbnailUrl: String
    )

    /** Fetches title / author / thumbnail via YouTube's public oEmbed endpoint. */
    private fun fetchOEmbedMetadata(videoId: String): OEmbedMeta? {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val apiUrl = "https://www.youtube.com/oembed?url=" +
            URLEncoder.encode(watchUrl, "UTF-8") + "&format=json"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(body)
            OEmbedMeta(
                title = o.optString("title", ""),
                authorName = o.optString("author_name", ""),
                thumbnailUrl = o.optString("thumbnail_url", "")
            )
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ── Latest videos (RSS + cache) ─────────────────────────────────

    /**
     * Reads the cached videos for [channelId] (never network). Returns null
     * when nothing is cached yet. Also surfaces the cache age so the UI can
     * show a "cached" hint.
     */
    fun getCachedVideos(channelId: String): Pair<List<MediaVideo>, Long>? =
        readVideosFile(cacheFile(channelId))

    /**
     * Fetches the channel's RSS feed, parses the latest videos, updates the
     * local cache and returns them. Returns null on any network/parse failure
     * so the UI can fall back to the cache and show an error hint.
     *
     * [enrich] skips the expensive watch-page duration enrichment (used by the
     * add-channel path so the dialog stays fast — the feed's own refresh
     * enriches on its next run).
     */
    suspend fun refreshVideos(
        channelId: String,
        enrich: Boolean = true
    ): List<MediaVideo>? = withContext(Dispatchers.IO) {
        if (channelId.startsWith("x_")) {
            val username = channelId.removePrefix("x_")
            val fresh = XSource.fetchProfile(username)
            val cached = getCachedVideos(channelId)?.first.orEmpty()
            if (fresh != null && fresh.isNotEmpty()) {
                val normalized = fresh.map { it.copy(channelId = channelId) }
                val merged = (normalized + cached.filter { old -> normalized.none { it.videoId == old.videoId } })
                    .sortedByDescending { it.publishedAtEpochMillis }
                writeCache(channelId, merged)
                return@withContext merged
            }
            return@withContext cached.ifEmpty { null }
        }
        if (channelId.startsWith("ig_")) {
            val username = channelId.removePrefix("ig_")
            val cachedRaw = getCachedVideos(channelId)?.first ?: emptyList()
            val cached = cachedRaw.filterNot { it.videoId.endsWith("_reels") || it.videoId.endsWith("_posts") }
            val profile = try { InstagramResolver.fetchProfile(username) } catch (e: Exception) { null }
            if (profile == null) {
                MediaSourceStatusStore.markFailing(MediaPlatform.INSTAGRAM)
            } else {
                MediaSourceStatusStore.markOk(MediaPlatform.INSTAGRAM)
            }
            if (profile != null && profile.posts.isNotEmpty()) {
                val cleanPosts = profile.posts
                    .filterNot { it.videoId.endsWith("_reels") || it.videoId.endsWith("_posts") }
                    // Providers sometimes build items with a feed title-derived
                    // id. The saved subscription id is authoritative, otherwise
                    // notifications and channel filtering can lose the post.
                    .map { it.copy(channelId = channelId) }
                // Carry over known durations first, then enrich this channel's
                // fresh Instagram videos too. The All Feed often already had a
                // duration from another refresh, while a direct channel view
                // exposed the missing value because the old Instagram path never
                // ran duration enrichment.
                val knownDurations = cached.associate { it.videoId to it.durationSeconds }
                val withKnownDurations = cleanPosts.map { post ->
                    knownDurations[post.videoId]?.takeIf { it > 0L }?.let {
                        post.copy(durationSeconds = it)
                    } ?: post
                }
                val enrichedPosts = if (enrich) enrichInstagramDurations(withKnownDurations) else withKnownDurations
                // Merge: fresh items take priority, then cached items not in fresh set.
                val freshIds = enrichedPosts.map { it.videoId }.toSet()
                val merged = enrichedPosts + cached.filter { it.videoId !in freshIds }
                val sorted = merged.sortedByDescending { it.publishedAtEpochMillis }
                writeCache(channelId, sorted)
                return@withContext sorted
            }
            // Fetch failed — return cached content (never empty on failure)
            return@withContext cached.ifEmpty { null }
        }

        val url = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/atom+xml")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                MediaSourceStatusStore.markFailing(MediaPlatform.YOUTUBE)
                return@withContext null
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            MediaSourceStatusStore.markOk(MediaPlatform.YOUTUBE)
            // Classify Shorts from the channel's /shorts tab (many Shorts omit
            // the #shorts hashtag, so title-only detection misses most of them).
            // Best-effort: an empty set degrades to hashtag detection.
            val shortsIds = ShortsIdResolver.fetchShortsIds(channelId)
            val fresh = YouTubeRssParser.parse(body, shortsIds)
            // Carry over already-resolved durations from the cache so a refresh
            // never re-fetches watch pages for videos we've enriched before
            // (the RSS feed itself carries no duration field).
            val knownDurations = getCachedVideos(channelId)?.first
                ?.associate { it.videoId to it.durationSeconds }
                ?.filterValues { it > 0L } ?: emptyMap()
            val withKnown = fresh.map { v ->
                val known = knownDurations[v.videoId]
                if (known != null) v.copy(durationSeconds = known) else v
            }
            // Then enrich the newest still-unknown videos with their duration
            // (best-effort: a failure just leaves the badge off). The add-channel
            // path skips enrichment so the dialog stays fast — the feed effect
            // enriches on its own refresh right after.
            val videos = if (enrich) enrichDurations(withKnown) else withKnown
            val cachedVideos = getCachedVideos(channelId)?.first.orEmpty()
            val freshIds = videos.map { it.videoId }.toSet()
            // YouTube RSS exposes a rolling latest window. Keep older entries
            // already learned so repeated refreshes build a growing local
            // channel history instead of throwing older uploads away.
            val accumulated = (videos + cachedVideos.filter { it.videoId !in freshIds })
                .sortedByDescending { it.publishedAtEpochMillis }
            // Trace every feed item straight from the parsed RSS so we can
            // confirm the videoId handed to the embedded player belongs to
            // this exact RSS entry (no stale/mixed/hardcoded ids).
            accumulated.forEach { v ->
                Log.d(
                    TAG,
                    "RSS_VIDEO channelId=$channelId videoId=${v.videoId} " +
                        "videoUrl=https://www.youtube.com/watch?v=${v.videoId} title=${v.title}"
                )
            }
            // A manually added video is never "new" — even when it later shows
            // up in RSS, it must not generate a notification (the user added it
            // deliberately as old content). Baseline it alongside the RSS items.
            val manualIds = MediaLibraryStore(appContext)
                .getManuallyAddedVideos().map { it.videoId }.toSet()
            val baseline = accumulated.filter { it.videoId in manualIds }.map { it.videoId }
            if (baseline.isNotEmpty()) {
                markVideosNotified(getNotifiedVideoIds() + baseline)
            }
            if (accumulated.isNotEmpty()) writeCache(channelId, accumulated)
            accumulated
        } catch (e: CancellationException) {
            // Never report a cancelled refresh as a channel failure — the caller
            // (the feed effect) restarts and refetches anyway. Rethrowing keeps
            // the cancellation propagating so a dropped channel can't poison the
            // merged feed with a bogus null.
            throw e
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Instagram providers frequently omit duration even for playable Reels.
     * Resolve the public stream only for the newest missing video posts, then
     * read the container duration locally. This is intentionally capped and
     * bounded: channel refresh should remain useful even when Meta is slow.
     */
    private suspend fun enrichInstagramDurations(
        videos: List<MediaVideo>,
        top: Int = 10,
        concurrency: Int = 2
    ): List<MediaVideo> {
        val missing = videos.filter {
            it.isInstagramVideo && it.durationSeconds <= 0L
        }.take(top)
        if (missing.isEmpty()) return videos

        val byId = videos.associateBy { it.videoId }.toMutableMap()
        missing.chunked(concurrency).forEach { batch ->
            val results = coroutineScope {
                batch.map { video ->
                    async(Dispatchers.IO) {
                        val stream = runCatching {
                            val direct = video.mediaUrl
                            if (InstagramStreamResolver.isPlayableVideoUrl(direct)) {
                                InstagramStreamResolver.ResolvedStream(direct!!, null)
                            } else {
                                val permalink = video.instagramUrl ?: video.videoId.removePrefix("ig_")
                                InstagramStreamResolver.resolvePlayableStream(
                                    appContext,
                                    permalink,
                                    fresh = false
                                )
                            }
                        }.getOrNull()
                        video.videoId to streamDurationSeconds(stream?.videoUrl)
                    }
                }.awaitAll()
            }
            results.forEach { (videoId, seconds) ->
                if (seconds > 0L) {
                    byId[videoId]?.let { byId[videoId] = it.copy(durationSeconds = seconds) }
                }
            }
        }
        return videos.map { byId[it.videoId] ?: it }
    }

    private fun streamDurationSeconds(url: String?): Long {
        if (url.isNullOrBlank()) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(
                url,
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Android)",
                    "Referer" to "https://www.instagram.com/"
                )
            )
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.div(1000L) ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Best-effort enrichment: fills [MediaVideo.durationSeconds] for the newest
     * [top] videos that don't have one yet. Fetching a watch page per video is
     * expensive, so it's capped to the head of the feed — the values persist in
     * the cache and are carried over on later refreshes. Never fails a refresh:
     * any fetch error leaves that video's badge off.
     *
     * Fetches run with BOUNDED parallelism ([concurrency] watch-page requests
     * in flight at once): a multi-channel refresh would otherwise stall on up
     * to 10 sequential ~1–3s requests per channel. The batches are awaited in
     * order so the result list keeps its original (newest-first) order.
     */
    private suspend fun enrichDurations(
        videos: List<MediaVideo>,
        top: Int = 10,
        concurrency: Int = 4
    ): List<MediaVideo> {
        val missing = videos.filter { it.durationSeconds <= 0L }.take(top)
        if (missing.isEmpty()) return videos
        val byId = videos.associateBy { it.videoId }.toMutableMap()
        missing.chunked(concurrency).forEach { batch ->
            val results = coroutineScope {
                batch.map { v ->
                    async {
                        v.videoId to runCatching {
                            VideoDurationResolver.fetchDuration(v.videoId)
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            for ((videoId, seconds) in results) {
                if (seconds != null && seconds > 0L) {
                    byId[videoId]?.let { original ->
                        byId[videoId] = original.copy(durationSeconds = seconds)
                    }
                }
            }
        }
        return videos.map { byId[it.videoId] ?: it }
    }

    /**
     * Fetches a channel's RSS and parses the latest videos WITHOUT the Shorts
     * classification and WITHOUT touching the cache — the lightweight check
     * used when resolving a user-pasted video URL (verifies membership in the
     * channel's real feed). Null on any network/parse failure.
     */
    private fun fetchFeedVideos(channelId: String): List<MediaVideo>? {
        val url = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/atom+xml")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            YouTubeRssParser.parse(body, emptySet())
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ── Media notifications (channel updates) ───────────────────────

    /**
     * Whether the app posts a notification when a saved channel uploads a new
     * video. Default ON; the toggle lives on the home (Quran) tab.
     */
    fun isMediaNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_MEDIA_NOTIFICATIONS_ENABLED, DEFAULT_MEDIA_NOTIFICATIONS_ENABLED)

    /** Persists the media-notifications toggle state. */
    fun setMediaNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    /** Video ids already notified about (dedup — never re-notify the same video). */
    fun getNotifiedVideoIds(): Set<String> =
        prefs.getStringSet(KEY_NOTIFIED_VIDEOS, emptySet()) ?: emptySet()

    /**
     * Whether the first successful refresh has baselined this subscription.
     * It is separate from the video-id set because the Media tab may refresh a
     * newly added channel before WorkManager gets its first notification check.
     */
    fun isNotificationBaselineComplete(channelId: String): Boolean =
        prefs.getStringSet(KEY_NOTIFICATION_BASELINED_CHANNELS, emptySet())?.contains(channelId) == true

    fun markNotificationBaselineComplete(channelId: String) {
        val current = prefs.getStringSet(KEY_NOTIFICATION_BASELINED_CHANNELS, emptySet()).orEmpty()
        prefs.edit()
            .putStringSet(KEY_NOTIFICATION_BASELINED_CHANNELS, current + channelId)
            .apply()
    }

    /** Persists the notified-video id set (capped so it can't grow unbounded). */
    fun markVideosNotified(ids: Set<String>) {
        val capped = ids.toList().takeLast(MAX_NOTIFIED_VIDEOS).toSet()
        prefs.edit().putStringSet(KEY_NOTIFIED_VIDEOS, capped).apply()
    }

    /**
     * Builds the home-page "Latest Updates" feed: the newest video per saved
     * channel (from cached feeds — never network), newest first. Empty when
     * nothing is cached yet.
     */
    fun buildChannelUpdates(videos: List<MediaVideo>): List<MediaChannelUpdate> =
        videos
            .groupBy { it.channelId }
            .map { (channelId, vs) ->
                val newest = vs.maxByOrNull { it.publishedAtEpochMillis }!!
                MediaChannelUpdate(
                    channelId = channelId,
                    channelName = newest.channelName.ifBlank { channelId },
                    latestVideoId = newest.videoId,
                    latestVideoTitle = newest.title,
                    publishedAtEpochMillis = newest.publishedAtEpochMillis
                )
            }
            .sortedByDescending { it.publishedAtEpochMillis }

    // ── Latest Updates feed (persisted history) ─────────────────────

    /**
     * The home tab's "Latest Updates" feed: the newest [MediaUpdates.MAX]
     * channel updates recorded by the background worker. Each entry matches a
     * notification the user received; dismissing one removes it permanently.
     */
    fun getUpdatesHistory(): List<MediaChannelUpdate> =
        MediaUpdates.decode(prefs.getString(KEY_UPDATES_HISTORY, null)).take(MediaUpdates.MAX)

    /**
     * Records freshly detected updates (called by the worker right after
     * notifying), merged into the existing history — deduped by video id,
     * newest first, capped at [MediaUpdates.MAX].
     */
    fun recordChannelUpdates(updates: List<MediaChannelUpdate>) {
        if (updates.isEmpty()) return
        val merged = MediaUpdates.merge(getUpdatesHistory(), updates)
        prefs.edit().putString(KEY_UPDATES_HISTORY, MediaUpdates.encode(merged)).apply()
    }

    /** Removes one update from the feed permanently (it won't re-appear). */
    fun dismissUpdate(latestVideoId: String) {
        val remaining = getUpdatesHistory().filterNot { it.latestVideoId == latestVideoId }
        prefs.edit().putString(KEY_UPDATES_HISTORY, MediaUpdates.encode(remaining)).apply()
    }

    /** Removes every update from the feed and the notification shade. */
    fun clearAllUpdates() {
        prefs.edit().putString(KEY_UPDATES_HISTORY, MediaUpdates.encode(emptyList())).apply()
    }

    /**
     * How many of [updates] the user hasn't seen yet (drives the Media-tab
     * badge). An update is "unread" until its id is in the seen set.
     */
    fun countUnreadUpdates(updates: List<MediaChannelUpdate>): Int =
        unreadUpdateIds(updates).size

    /** The ids in [updates] the user hasn't seen yet (unread indicator in the sheet). */
    fun unreadUpdateIds(updates: List<MediaChannelUpdate>): Set<String> {
        val seen = getSeenUpdateIds()
        return updates.map { it.latestVideoId }.filterNot { it in seen }.toSet()
    }

    /** Marks the given update ids as seen; returns how many were newly marked. */
    fun markUpdatesSeen(videoIds: List<String>): Int {
        if (videoIds.isEmpty()) return 0
        val seen = getSeenUpdateIds()
        val newly = videoIds.filter { it !in seen }.toSet()
        if (newly.isEmpty()) return 0
        val merged = (seen + newly).toList().takeLast(MAX_NOTIFIED_VIDEOS).toSet()
        prefs.edit().putStringSet(KEY_SEEN_UPDATE_IDS, merged).apply()
        return newly.size
    }

    private fun getSeenUpdateIds(): Set<String> =
        prefs.getStringSet(KEY_SEEN_UPDATE_IDS, emptySet()) ?: emptySet()

    // ── Feed filters (persisted across restarts, per context) ──────

    /**
     * The saved feed filter for [channelId], or the All Feed filter when
     * [channelId] is null. Every channel keeps its OWN filter, separate from
     * the All Feed one. Defaults when never set or corrupt.
     */
    fun getFeedFilter(channelId: String? = null): FeedFilter =
        decodeFeedFilter(prefs.getString(feedFilterKey(channelId), null)) ?: FeedFilter()

    /** Persists the feed filter for [channelId] (null = All Feed). */
    fun setFeedFilter(filter: FeedFilter, channelId: String? = null) {
        prefs.edit().putString(feedFilterKey(channelId), encodeFeedFilter(filter)).apply()
    }

    /** Per-context storage key: the All Feed key, or a per-channel key. */
    private fun feedFilterKey(channelId: String?): String =
        if (channelId == null) KEY_FEED_FILTER
        else KEY_FEED_FILTER + "_channel_" + channelId

    /**
     * One-time seed for a fresh install (or the first run after this feature
     * arrived): pre-fills the history from the cached feeds so the feed isn't
     * empty before the first background check. Only runs when the history key
     * has NEVER been written, so updates the user later dismisses can never be
     * resurrected by another seed.
     */
    fun ensureUpdatesHistorySeeded(channels: List<SavedChannel>) {
        if (prefs.contains(KEY_UPDATES_HISTORY)) return
        val seeded = buildChannelUpdates(
            channels.mapNotNull { getCachedVideos(it.channelId)?.first }.flatten()
        )
        if (seeded.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_UPDATES_HISTORY, MediaUpdates.encode(seeded.take(MediaUpdates.MAX)))
                .apply()
        }
    }

    // ── Aggregate feed (Subscriptions-style: every saved channel) ──

    /**
     * Merges the cached videos of ALL [channels] (never network), newest
     * first. Used for the instant first paint of the Media tab.
     */
    fun getAllCachedVideos(channels: List<SavedChannel>): List<MediaVideo> =
        channels
            .mapNotNull { getCachedVideos(it.channelId)?.first }
            .flatten()
            .sortedByDescending { it.publishedAtEpochMillis }

    /**
     * The last merged All Feed, kept in memory for the lifetime of the process.
     *
     * The Media tab is torn down when the user switches tabs, so every return
     * to it used to start from an empty list and sit on a skeleton while all
     * feeds were fetched again. This is what the tab paints from instead: the
     * feed reappears instantly and the network refresh happens behind it (and
     * only when [isFeedRefreshDue]).
     */
    fun getMemoizedAllFeed(): List<MediaVideo>? = memoizedFeed

    /** Records the merged All Feed shown to the user. */
    fun memoizeAllFeed(videos: List<MediaVideo>) {
        if (videos.isEmpty()) return
        memoizedFeed = videos
    }

    /**
     * Whether the merged All Feed is due for a network refresh. The Media tab
     * asks this when it opens, so re-entering the tab right after a refresh
     * shows the feed instead of re-fetching every channel; pull-to-refresh
     * bypasses the interval entirely.
     */
    fun isFeedRefreshDue(now: Long = System.currentTimeMillis()): Boolean =
        now - prefs.getLong(KEY_LAST_FEED_REFRESH, 0L) >= FEED_REFRESH_INTERVAL_MS

    /** Records that the merged All Feed was refreshed over the network. */
    fun markFeedRefreshed(atEpochMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_FEED_REFRESH, atEpochMillis).apply()
    }

    /**
     * When the merged All Feed was last refreshed (0 = never). Persisted, so
     * the Media tab's "Updated Xm ago" hint survives a tab switch and a
     * restart instead of resetting with the composable's own state.
     */
    fun lastFeedRefreshAt(): Long = prefs.getLong(KEY_LAST_FEED_REFRESH, 0L)

    /**
     * Refreshes EVERY channel's RSS feed and merges the results, newest
     * first. Channels are refreshed CONCURRENTLY with [concurrency] feeds in
     * flight at once (each channel's own duration enrichment is already
     * internally bounded), so a multi-channel feed loads much faster than a
     * strictly sequential pass. A [batchDelayMs] pause is inserted between
     * batches so the request bursts don't stack into one rate-limit window.
     * A channel whose refresh FAILED keeps its cached videos (if any), so a
     * transient failure never drops the channel from the merged feed — this is
     * what lets the background worker still see its content and what keeps the
     * Media tab's own merge effective. Returns null only when every channel
     * failed AND nothing is cached (callers then fall back to the cache / show
     * an error hint). A legitimately EMPTY feed (fetched fine, no entries) is
     * not a failure and contributes nothing.
     */
    suspend fun refreshAllVideos(
        channels: List<SavedChannel>,
        concurrency: Int = 3,
        batchDelayMs: Long = 500
    ): List<MediaVideo>? {
        var anySucceeded = false
        val merged = ArrayList<MediaVideo>()
        channels.chunked(concurrency).forEachIndexed { index, batch ->
            // Brief pause between batches (never before the first): each batch
            // can fire up to `concurrency` channels × (RSS + /shorts + watch
            // pages) at once, so spacing them keeps the peak request rate down.
            if (index > 0 && batchDelayMs > 0L) delay(batchDelayMs)
            // refreshVideos never throws except on cancellation (which must
            // propagate — a cancelled channel is NOT a failed channel), so the
            // batch awaits raw and lets awaitAll rethrow on cancel. A failed
            // refresh falls back to the channel's cached feed (read on IO, so
            // the file read never touches the caller's main thread) — the
            // channel isn't dropped from the merged result.
            val results = coroutineScope {
                batch.map { channel ->
                    async {
                        val channelId = channel.channelId
                        withContext(Dispatchers.IO) {
                            refreshVideos(channelId) ?: getCachedVideos(channelId)?.first
                        }
                    }
                }.awaitAll()
            }
            for (fresh in results) {
                if (fresh != null) {
                    anySucceeded = true
                    merged.addAll(fresh)
                }
            }
        }
        if (!anySucceeded) return null
        return merged.sortedByDescending { it.publishedAtEpochMillis }
    }

    private fun cacheFile(channelId: String): File =
        File(appContext.filesDir, "media_videos_$channelId.json")

    private fun writeCache(channelId: String, videos: List<MediaVideo>) {
        writeVideosFile(cacheFile(channelId), videos)
    }

    /** Shared cache reader for channel and playlist caches (same JSON layout). */
    private fun readVideosFile(file: File): Pair<List<MediaVideo>, Long>? {
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val savedAt = obj.optLong("savedAt", 0L)
            val videos = parseVideos(obj.optJSONArray("videos"))
                .filterNot { it.videoId.endsWith("_reels") || it.videoId.endsWith("_posts") }
            if (videos.isEmpty()) null else videos to savedAt
        } catch (e: Exception) {
            null
        }
    }

    /** Shared cache writer for channel and playlist caches (same JSON layout). */
    private fun writeVideosFile(file: File, videos: List<MediaVideo>) {
        val arr = JSONArray()
        videos.forEach { v ->
            arr.put(
                JSONObject()
                    .put("videoId", v.videoId)
                    .put("title", v.title)
                    .put("channelId", v.channelId)
                    .put("channelName", v.channelName)
                    .put("publishedAt", v.publishedAtEpochMillis)
                    .put("thumbnailUrl", v.thumbnailUrl)
                    .put("viewCount", v.viewCount)
                    .put("isShort", v.isShort)
                    .put("isLive", v.isLive)
                    .put("durationSeconds", v.durationSeconds)
                    .put("isOfflineAudio", v.isOfflineAudio)
                    .put("platform", v.platform.name)
                    .put("instagramType", v.instagramType?.name ?: JSONObject.NULL)
                    .put("mediaUrl", v.mediaUrl ?: JSONObject.NULL)
                    .put("instagramUrl", v.instagramUrl ?: JSONObject.NULL)
                    .put("sourceUrl", v.sourceUrl ?: JSONObject.NULL)
                    .put("bodyText", v.bodyText)
            )
        }
        val obj = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("videos", arr)
        file.writeText(obj.toString(), Charsets.UTF_8)
    }

    private fun parseVideos(arr: JSONArray?): List<MediaVideo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                val platformStr = o.optString("platform", "YOUTUBE")
                val platform = runCatching { MediaPlatform.valueOf(platformStr) }.getOrDefault(MediaPlatform.YOUTUBE)
                val igTypeStr = o.optString("instagramType", "").takeIf { it.isNotBlank() }
                val igType = igTypeStr?.let { runCatching { InstagramMediaType.valueOf(it) }.getOrNull() }
                val mediaUrl = o.optString("mediaUrl", "").takeIf { it.isNotBlank() }
                val instagramUrl = o.optString("instagramUrl", "").takeIf { it.isNotBlank() }
                val sourceUrl = o.optString("sourceUrl", "").takeIf { it.isNotBlank() }
                val bodyText = o.optString("bodyText", "")
                val videoId = o.getString("videoId")
                // Instagram thumbnails are RE-DERIVED on every read: a
                // feed-supplied CDN URL dies with its signature (403), so a
                // cached post would keep showing a blank tile forever. The
                // helper swaps it for the stable, self-renewing endpoint (and
                // fills in a poster when the stored one is empty).
                val isInstagram = platform == MediaPlatform.INSTAGRAM || videoId.startsWith("ig_")
                MediaVideo(
                    videoId = videoId,
                    title = o.optString("title", ""),
                    channelId = o.optString("channelId", ""),
                    channelName = o.optString("channelName", ""),
                    publishedAtEpochMillis = o.optLong("publishedAt", 0L),
                    thumbnailUrl = if (isInstagram) {
                        InstagramEmbedPayload.thumbnailFor(
                            videoId.removePrefix("ig_"),
                            o.optString("thumbnailUrl", "")
                        )
                    } else {
                        o.optString("thumbnailUrl", "")
                    },
                    viewCount = o.optLong("viewCount", 0L),
                    isShort = o.optBoolean("isShort", false),
                    isLive = o.optBoolean("isLive", false),
                    durationSeconds = o.optLong("durationSeconds", 0L),
                    isOfflineAudio = o.optBoolean("isOfflineAudio", false),
                    platform = platform,
                    instagramType = igType,
                    mediaUrl = mediaUrl,
                    instagramUrl = instagramUrl,
                    sourceUrl = sourceUrl,
                    bodyText = bodyText
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    // ── Saved playlists (imported by URL) ──────────────────────────

    /**
     * Saved YouTube playlists, oldest first. Unlike channels, a fresh install
     * starts with NO playlists — they're purely user-imported.
     */
    fun getSavedPlaylists(): List<SavedPlaylist> {
        val json = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return parsePlaylists(json)
    }

    /** Result of adding a playlist: success (with the playlist) or an error message. */
    sealed class AddPlaylistResult {
        data class Success(val playlist: SavedPlaylist) : AddPlaylistResult()
        data class Error(val message: String) : AddPlaylistResult()
    }

    /**
     * Resolves [input] (playlist URL or bare id), fetches the playlist's
     * videos, caches them and saves the playlist. Returns a friendly error for
     * non-playlist input, unreachable / invalid playlists, private playlists
     * and network failures.
     */
    suspend fun addPlaylist(input: String): AddPlaylistResult = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return@withContext AddPlaylistResult.Error("Enter a YouTube playlist URL or id")
        }
        val playlistId = extractYouTubePlaylistId(trimmed)
            ?: return@withContext AddPlaylistResult.Error(
                "That doesn't look like a YouTube playlist URL."
            )
        if (getSavedPlaylists().any { it.playlistId == playlistId }) {
            return@withContext AddPlaylistResult.Error("That playlist is already saved")
        }
        val info = fetchPlaylistInfo(playlistId)
            ?: return@withContext AddPlaylistResult.Error(
                "Couldn't load that playlist. Check the URL and your connection."
            )
        if (info.videos.isEmpty()) {
            return@withContext AddPlaylistResult.Error(
                "This playlist is private or unavailable."
            )
        }
        val playlist = SavedPlaylist(
            playlistId = playlistId,
            title = info.title.ifBlank { "YouTube Playlist" },
            sourceRef = trimmed
        )
        savePlaylists(getSavedPlaylists() + playlist)
        writeVideosFile(playlistCacheFile(playlistId), info.videos)
        AddPlaylistResult.Success(playlist)
    }

    fun removePlaylist(playlistId: String) {
        savePlaylists(getSavedPlaylists().filterNot { it.playlistId == playlistId })
        playlistCacheFile(playlistId).delete()
    }

    private fun savePlaylists(playlists: List<SavedPlaylist>) {
        val arr = JSONArray()
        playlists.forEach { p ->
            arr.put(
                JSONObject()
                    .put("playlistId", p.playlistId)
                    .put("title", p.title)
                    .put("sourceRef", p.sourceRef)
            )
        }
        prefs.edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }

    private fun parsePlaylists(json: String): List<SavedPlaylist> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                SavedPlaylist(
                    playlistId = o.getString("playlistId"),
                    title = o.optString("title", "YouTube Playlist"),
                    sourceRef = o.optString("sourceRef", o.getString("playlistId"))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Playlist videos (page scrape + RSS fallback + cache) ──────

    /** Cached videos for [playlistId] (never network). Null when nothing cached. */
    fun getCachedPlaylistVideos(playlistId: String): Pair<List<MediaVideo>, Long>? =
        readVideosFile(playlistCacheFile(playlistId))

    /**
     * Refetches the playlist's videos, updates its cache and returns them in
     * playlist order. Returns null on a network/parse failure (UI falls back
     * to the cache) and an EMPTY list when the playlist is definitively
     * private / has no visible videos (UI shows the friendly message).
     */
    suspend fun refreshPlaylistVideos(playlistId: String): List<MediaVideo>? =
        withContext(Dispatchers.IO) {
            val info = fetchPlaylistInfo(playlistId) ?: return@withContext null
            if (info.videos.isEmpty()) return@withContext emptyList()
            writeVideosFile(playlistCacheFile(playlistId), info.videos)
            info.videos
        }

    private fun playlistCacheFile(playlistId: String): File =
        File(appContext.filesDir, "media_playlist_$playlistId.json")

    /**
     * Fetches the playlist page and parses its `ytInitialData`, then follows
     * EVERY continuation page so the FULL playlist is imported. There are two
     * page formats to handle:
     *
     *  - CLASSIC: the initial HTML embeds the first batch of items
     *    (`playlistVideoRenderer`), the rest arrive via the
     *    `youtubei/v1/browse` endpoint (one POST per page of ~100).
     *  - MODERN (2025+): the initial HTML embeds ONLY playlist metadata — no
     *    video list at all. The full ordered list is served by the
     *    `youtubei/v1/next` endpoint's playlist panel (`playlistPanelVideoRenderer`
     *    items), paginated the same way.
     *
     * A successful response is authoritative — a private/unavailable playlist
     * returns an info with an empty video list (no RSS fallback, that's the
     * answer). Only when the page can't be fetched/parsed or the modern panel
     * fetch fails outright does the RSS feed get tried (it returns the newest
     * ~15 videos — a degraded fallback, never the primary path).
     */
    private fun fetchPlaylistInfo(playlistId: String): PlaylistPageParser.PlaylistInfo? {
        val pageHtml = fetchPlaylistPageHtml(playlistId)
        val first = pageHtml?.let { PlaylistPageParser.parsePage(it) }

        if (first != null && first.videos.isNotEmpty()) {
            // Classic format: the list is embedded. Walk the continuation
            // chain: every browse page yields the next batch of videos AND
            // the token for the page after it. A LinkedHashMap keeps the
            // playlist order while deduplicating (defensive — the pages never
            // actually repeat items). A failed page stops the walk (cache
            // what we have rather than failing the whole playlist).
            val apiKey = PlaylistPageParser.innertubeApiKey(pageHtml)
            val context = PlaylistPageParser.innertubeContext(pageHtml)
            val all = LinkedHashMap<String, MediaVideo>()
            first.videos.forEach { all[it.videoId] = it }

            var token = PlaylistPageParser.firstContinuationToken(pageHtml)
            var pages = 0
            while (token != null && pages < MAX_PLAYLIST_PAGES) {
                pages++
                val page = fetchPlaylistContinuation(token, apiKey, context) ?: break
                page.videos.forEach { v -> if (v.videoId !in all) all[v.videoId] = v }
                token = page.nextToken
            }
            return PlaylistPageParser.PlaylistInfo(first.title, all.values.toList())
        }

        if (first != null) {
            // Modern format: the page parsed fine (title + metadata) but
            // carries NO embedded video list — a PUBLIC playlist looks exactly
            // like this now, so this is NOT "private". Fetch the full list
            // from the /next endpoint's playlist panel. A reached panel with
            // an empty list is the authoritative private/unavailable answer;
            // only a failed panel fetch falls back to RSS.
            val apiKey = PlaylistPageParser.innertubeApiKey(pageHtml)
            val context = PlaylistPageParser.innertubeContext(pageHtml)
            if (context != null) {
                fetchPlaylistPanelViaNext(playlistId, apiKey, context)?.let { return it }
            }
        }

        // Page fetch or parse failed, or the modern panel fetch failed
        // outright — last resort: RSS (newest ~15 videos).
        return fetchPlaylistRss(playlistId)
    }

    /**
     * Modern playlist pages (2025+ page format) embed NO video list in the
     * initial HTML — only playlist metadata. The full ordered list is served
     * by the `/youtubei/v1/next` endpoint's playlist panel
     * (`playlistPanelVideoRenderer` items), which this walks to the end using
     * the same continuation pattern as the classic browse walk. Returns null
     * only when the endpoint can't be reached or the response has no playlist
     * panel; a reached panel with an empty list is authoritative (private /
     * unavailable playlist).
     */
    private fun fetchPlaylistPanelViaNext(
        playlistId: String,
        apiKey: String?,
        context: JSONObject?
    ): PlaylistPageParser.PlaylistInfo? {
        val first = fetchNextPanel(playlistId, apiKey, context, continuation = null)
            ?: return null
        val all = LinkedHashMap<String, MediaVideo>()
        first.videos.forEach { all[it.videoId] = it }

        var token = first.nextToken
        var pages = 0
        while (token != null && pages < MAX_PLAYLIST_PAGES) {
            pages++
            val page = fetchNextPanel(playlistId, apiKey, context, continuation = token) ?: break
            page.videos.forEach { v -> if (v.videoId !in all) all[v.videoId] = v }
            token = page.nextToken
        }
        return PlaylistPageParser.PlaylistInfo(first.title, all.values.toList())
    }

    /**
     * One `/youtubei/v1/next` request: the playlist's full video panel (when
     * [continuation] is null) or the panel's next page (when it isn't). Null
     * on any network/parse failure (the walk stops, keeping what it has).
     */
    private fun fetchNextPanel(
        playlistId: String,
        apiKey: String?,
        context: JSONObject?,
        continuation: String?
    ): PlaylistPageParser.PlaylistPanel? {
        val url = "https://www.youtube.com/youtubei/v1/next" +
            (if (apiKey != null) "?key=$apiKey" else "")
        val body = JSONObject()
            .put("context", context ?: JSONObject())
            .apply {
                if (continuation != null) put("continuation", continuation)
                else put("playlistId", playlistId)
            }
            .toString()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
                setRequestProperty("Referer", "https://www.youtube.com/")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val resp = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            PlaylistPageParser.parsePlaylistPanel(resp)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Fetches the playlist page HTML with a DESKTOP UA (full ytInitialData). */
    private fun fetchPlaylistPageHtml(playlistId: String): String? {
        val pageUrl = "https://www.youtube.com/playlist?list=$playlistId"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * One `youtubei/v1/browse` continuation request: POSTs the page's own
     * innertube context + token and parses the next batch. Null on any
     * network/parse failure (the walk stops, keeping what it has).
     */
    private fun fetchPlaylistContinuation(
        token: String,
        apiKey: String?,
        context: JSONObject?
    ): PlaylistPageParser.ContinuationPage? {
        val url = "https://www.youtube.com/youtubei/v1/browse" +
            (if (apiKey != null) "?key=$apiKey" else "")
        val body = JSONObject()
            .put("context", context ?: JSONObject())
            .put("continuation", token)
            .toString()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
                setRequestProperty("Referer", "https://www.youtube.com/")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val resp = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            PlaylistPageParser.parseContinuationPage(resp)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** RSS fallback for playlists: `feeds/videos.xml?playlist_id=` (newest ~15). */
    private fun fetchPlaylistRss(playlistId: String): PlaylistPageParser.PlaylistInfo? {
        val url = "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/atom+xml")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val videos = YouTubeRssParser.parse(body, emptySet())
            val title = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() }
            PlaylistPageParser.PlaylistInfo(title ?: "", videos)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "MediaRepository"
        const val PREFS_NAME = "media_prefs"
        const val KEY_CHANNELS = "saved_channels"
        const val KEY_SELECTED_CHANNEL = "selected_channel"
        const val KEY_PLAYLISTS = "saved_playlists"
        const val KEY_MEDIA_NOTIFICATIONS_ENABLED = "media_notifications_enabled"
        const val KEY_NOTIFIED_VIDEOS = "notified_video_ids"
        const val KEY_NOTIFICATION_BASELINED_CHANNELS = "notification_baselined_channels"
        const val KEY_UPDATES_HISTORY = "updates_history"
        const val KEY_SEEN_UPDATE_IDS = "seen_update_ids"
        const val KEY_FEED_FILTER = "feed_filter"
        const val KEY_LAST_FEED_REFRESH = "last_feed_refresh"
        const val MAX_NOTIFIED_VIDEOS = 200

        /**
         * Minimum gap between automatic All Feed refreshes. The Media tab is
         * opened and left constantly; without this every return re-fetched
         * every channel (which is also what got X's syndication endpoint to
         * rate-limit the install). Pull-to-refresh is never throttled.
         */
        const val FEED_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

        /**
         * Process-wide memo of the last merged All Feed. Static because the
         * repository instance itself lives (and dies) with the Media tab — the
         * one thing that must survive a tab switch is the feed it showed.
         */
        @Volatile
        private var memoizedFeed: List<MediaVideo>? = null
        // Safety cap on continuation pages (~100 videos each → up to 10 000
        // videos, far beyond YouTube's 5 000-video playlist maximum).
        const val MAX_PLAYLIST_PAGES = 100
        const val DEFAULT_MEDIA_NOTIFICATIONS_ENABLED = true

        // A DESKTOP UA gets the full server-rendered page (a mobile UA makes
        // YouTube serve a reduced page without the playlist's ytInitialData,
        // which silently fell back to the ~15-video RSS feed).
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

        val DEFAULT_CHANNEL = SavedChannel(
            channelId = "UC2cX3SmsdWsrRS8t_5zvzEw", // Safina Society (@SafinaSociety)
            displayName = "Safina Society",
            sourceRef = "@SafinaSociety"
        )
    }
}
