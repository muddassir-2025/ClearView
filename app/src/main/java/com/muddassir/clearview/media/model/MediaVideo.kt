package com.muddassir.clearview.media.model

/**
 * A single YouTube video from a channel's RSS feed.
 *
 * @property videoId        YouTube video id (the "v=" parameter).
 * @property title          Video title.
 * @property channelId      The channel that published it.
 * @property channelName    Display name of that channel.
 * @property publishedAtEpochMillis  When the video was published (epoch ms).
 * @property thumbnailUrl   Thumbnail URL (i.ytimg.com). May be empty on parse failure.
 * @property viewCount      View count from the feed's media:statistics element
 *                          (0 when the feed omits it or the cache predates it).
 * @property isShort        True when this upload is a Short. Set by the parser
 *                          from the `#shorts` hashtag OR the channel's /shorts
 *                          tab; persisted in the cache (old caches default to
 *                          false until the next refresh).
 * @property isLive         True when this upload is a LIVE broadcast (the RSS
 *                          feed has no duration, so the parser keys off the
 *                          live thumbnail `…/hqdefault_live.jpg`). Live
 *                          broadcasts are never Shorts and are excluded from
 *                          watch-progress tracking (a live stream has no
 *                          finite duration to mark watched).
 * @property durationSeconds The video's length in seconds, or 0 when unknown.
 *                          The RSS feed omits durations, so this is resolved
 *                          per-video from the watch page (see
 *                          VideoDurationResolver) for the newest uploads and
 *                          persisted in the cache; used for the time badge on
 *                          feed cards.
 * @property isOfflineAudio True when this entry represents the DOWNLOADED
 *                          AUDIO of the video (added to a user playlist via
 *                          "Add audio to playlist…"), not the video itself.
 *                          Such an entry keeps the same [videoId], so a
 *                          playlist can hold BOTH the video and its offline
 *                          audio side by side; tapping one opens the player,
 *                          tapping the other plays the local audio file.
 *                          Persisted only in user-playlist JSON — feed videos
 *                          are always false (default).
 */
data class MediaVideo(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val publishedAtEpochMillis: Long,
    val thumbnailUrl: String,
    val viewCount: Long = 0L,
    val isShort: Boolean = false,
    val isLive: Boolean = false,
    val durationSeconds: Long = 0L,
    val isOfflineAudio: Boolean = false,
    val platform: MediaPlatform = MediaPlatform.YOUTUBE,
    val instagramType: InstagramMediaType? = null,
    val mediaUrl: String? = null,
    val instagramUrl: String? = null,
    /** Original permalink for non-YouTube sources such as X. */
    val sourceUrl: String? = null,
    /**
     * The post's FULL text — the whole tweet body or Instagram caption, never
     * truncated. [title] is only the short label a feed card shows, so a post
     * used to be readable up to that label and no further. Empty for videos,
     * which have a title instead.
     */
    val bodyText: String = ""
) {

    // ── Platform / content-type helpers ───────────────────────────────
    // Instagram items are recognised by their platform tag OR the legacy
    // `ig_<shortcode>` id prefix (older caches / manually added posts).

    /** True for any Instagram item (Reel, video or image post). */
    val isInstagram: Boolean
        get() = platform == MediaPlatform.INSTAGRAM || videoId.startsWith("ig_")

    /**
     * True for an Instagram item that carries moving pictures — a Reel or a
     * video post. These play in the native player exactly like a YouTube
     * video; only image/carousel posts are rendered as stills.
     *
     * The TYPE decides, and it decides both ways: a post that merely CONTAINS
     * a video clip (a carousel with one video slide) is still a post, because
     * the Videos section is for Reels and videos only. Only when the source
     * gave no type at all (older caches, manually added posts) is a progressive
     * video URL accepted as evidence.
     */
    val isInstagramVideo: Boolean
        get() {
            if (!isInstagram) return false
            return when (instagramType) {
                InstagramMediaType.REEL, InstagramMediaType.VIDEO -> true
                InstagramMediaType.IMAGE, InstagramMediaType.CAROUSEL -> false
                null -> mediaUrl?.contains(".mp4") == true
            }
        }

    /** A still Instagram post (photo or carousel) — shown as a square tile. */
    val isInstagramImage: Boolean
        get() = isInstagram && !isInstagramVideo

    /** True when [mediaUrl] is a directly playable progressive stream. */
    val hasPlayableVideo: Boolean
        get() {
            val url = mediaUrl ?: return false
            val lower = url.lowercase()
            return lower.contains(".mp4") || lower.contains(".m3u8") ||
                lower.contains(".webm") || lower.contains(".m4v")
        }

    /**
     * A text/photo POST rather than playable media: an X tweet with no video
     * attached, or an Instagram photo/carousel. Posts render as post cards and
     * open the full-post viewer; everything else opens a player.
     *
     * Rendering every non-YouTube item as a video card is what made X and
     * Instagram posts look like videos with a broken thumbnail.
     */
    val isPost: Boolean
        get() = when {
            isInstagramImage -> true
            platform == MediaPlatform.X -> !hasPlayableVideo
            else -> false
        }

    /**
     * True when this item belongs in the Shorts row / viewer. Shorts are a
     * YouTube concept: an Instagram Reel is a normal video here (it gets the
     * same feed rows, resume and Continue Watching as any long video). Older
     * caches stored `isShort = true` for Reels, hence the platform guard.
     */
    val isShortsEntry: Boolean
        get() = isShort && !isInstagram

    companion object {
        /**
         * Title-only Short signal: the #shorts hashtag that YouTube appends to
         * Shorts uploads. Word-bounded so titles like "#shortsy" or
         * "#shortsfortruth" don't false-positive, and "#short" alone (a common
         * keyword in long-video titles) never matches. Plain string logic
         * (deliberately NO regex — a raw-string word boundary can be mangled by
         * escaping layers when files are written programmatically).
         */
        fun isShortsTitle(title: String): Boolean {
            val tag = "#shorts"
            var i = title.indexOf(tag, ignoreCase = true)
            while (i >= 0) {
                val after = i + tag.length
                // A real #shorts tag sits at the end of the title or is followed
                // by a non-word character (space, punctuation, end).
                if (after >= title.length ||
                    !(title[after].isLetterOrDigit() || title[after] == '_')
                ) {
                    return true
                }
                i = title.indexOf(tag, i + 1, ignoreCase = true)
            }
            return false
        }
    }
}
