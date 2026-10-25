package com.muddassir.clearview.browser

import android.content.Context
import com.muddassir.clearview.backend.ChannelBlockRepository
import com.muddassir.clearview.brainrot.BlockAction
import com.muddassir.clearview.brainrot.BlockedItemMeta
import com.muddassir.clearview.brainrot.BrainRotRepository
import com.muddassir.clearview.brainrot.GlobalRulesStore
import com.muddassir.clearview.extractor.ContentExtractor
import com.muddassir.clearview.matching.ContentSnapshot
import com.muddassir.clearview.matching.FeedVideoCard
import com.muddassir.clearview.matching.KeywordMatcher
import com.muddassir.clearview.matching.MatchResult
import com.muddassir.clearview.repository.BlockRepository
import com.muddassir.clearview.youtubetest.YoutubeTestKeywordRepository
import org.json.JSONObject
import java.util.Locale

/**
 * A block decision for the Browser tab.
 *
 * [item] is the matched rule (keyword, domain, channel handle, ...) and [type]
 * tells the overlay how to explain it. Nothing here introduces a new rule set:
 * every decision is delegated to the EXISTING Android enforcement code.
 */
data class BrowserBlock(
    val item: String,
    val type: Type,
    val isContent: Boolean = false
) {
    enum class Type {
        /** A blocked website / adult domain. */
        DOMAIN,

        /** A built-in or user keyword matched the URL, query or content. */
        KEYWORD,

        /** A channel on the user's, global or backend blocklist. */
        CHANNEL,

        /** YouTube Shorts (the existing Block Shorts toggle). */
        SHORTS,

        /** Instagram Reels (the browser's Hide Reels toggle). */
        REELS,

        /** A blocked search query (Google or YouTube). */
        SEARCH,

        /** A tab the reader has hidden (Instagram Explore/Search, Home). */
        SURFACE
    }

    /** One-line reason shown under the overlay heading. */
    fun message(): String = when (type) {
        Type.DOMAIN -> "This website has been blocked"
        Type.CHANNEL -> "This channel is blocked"
        Type.SHORTS -> "YouTube Shorts are blocked"
        Type.REELS -> "Instagram Reels are blocked"
        Type.SEARCH -> "This search is blocked"
        Type.SURFACE -> "This tab is hidden by ClearView"
        Type.KEYWORD -> "This content contains blocked keywords"
    }

    /** JSON understood by the injected page script. */
    fun toJson(): String = JSONObject().apply {
        put("blocked", true)
        put("item", item)
        put("type", type.name)
    }.toString()
}

/**
 * The single bridge between the Browser tab and ClearView's existing
 * protection.
 *
 * It deliberately constructs the SAME repositories and matcher the
 * accessibility service uses, and feeds them snapshots in the shapes they
 * already understand:
 *
 *  - URL checks run through [KeywordMatcher.check] with the Chrome package, so
 *    blocked domains, URL keywords, Google/YouTube search queries and the
 *    Block Shorts toggle behave exactly as they do in Chrome.
 *  - Watch pages use [KeywordMatcher.checkLongVideoContent], the existing
 *    long-video policy (built-in + user + YouTube-only keywords).
 *  - Feed cards are handed to the existing feed-card risk model as
 *    [FeedVideoCard]s.
 *  - Channels are checked against [BrainRotRepository] (the user's own list
 *    plus the global rules pushed into it) and [ChannelBlockRepository]'s
 *    cached backend list.
 *
 * No keyword list, domain list, preference or matcher is duplicated here.
 */
class BrowserRules(
    context: Context,
    private val settings: BrowserSettings
) {

    companion object {
        /** How long the browser trusts its channel snapshot. */
        private const val CHANNEL_CACHE_MS = 2_000L

        /**
         * The Chrome package name is used as the "surface" for URL checks.
         * That is intentional: the Browser tab should apply the SAME web
         * policy Chrome does, and this is the existing entry point for it.
         */
        private const val WEB_SURFACE = "com.android.chrome"

        /**
         * The part of a handle a channel's DISPLAY NAME usually equals.
         *
         * YouTube derives a handle from the channel name with a trailing
         * number when the plain one is taken: channel "Beluga" gets
         * `@Beluga1`. A mobile feed card frequently renders only that display
         * name (no `/@` link, no handle anywhere in its markup), so the reader's
         * own block of `@Beluga1` could not recognise its own card — the watch
         * page blocked it, the Home card did not.
         *
         * Only handles that actually carry trailing digits produce a stem, so
         * a plain handle never gains a looser spelling. Returns null when there
         * is nothing to strip or the remainder is too short to be an identity.
         */
        fun handleStem(handle: String?): String? {
            val normalized = BrainRotRepository.normalizeHandle(handle) ?: return null
            val bare = normalized.removePrefix("@")
            val stem = bare.replace(Regex("[0-9]+$"), "").trimEnd('.', '_', '-')
            if (stem == bare || stem.length < MIN_STEM_LENGTH) return null
            return stem
        }

        /** Shortest handle stem trusted as a display name ("Ted" is enough). */
        private const val MIN_STEM_LENGTH = 3

        /** The page surface a browser block was made on. */
        const val SURFACE_YOUTUBE = "youtube"
        const val SURFACE_INSTAGRAM = "instagram"

        /** True when a URL belongs to a YouTube surface the page script handles. */
        fun isYouTubeSurface(url: String?): Boolean =
            ContentExtractor.isYouTubeDomain(url)

        /** True for a Google page the Google content script may run on. */
        fun isGoogleSurface(url: String?): Boolean = BrowserAddress.isGoogle(url)

        /** True for an Instagram page the Reels script may run on. */
        fun isInstagramSurface(url: String?): Boolean = BrowserAddress.isInstagram(url)

        /** Any page ClearView injects its in-page script into. */
        fun isProtectedSurface(url: String?): Boolean =
            isYouTubeSurface(url) || isGoogleSurface(url) || isInstagramSurface(url)
    }

    private val repository = BlockRepository(context.applicationContext)
    private val matcher = KeywordMatcher(repository)
    private val blockAction = BlockAction(context.applicationContext)
    private val youtubeKeywords = YoutubeTestKeywordRepository(context.applicationContext)
    private val brainRot = BrainRotRepository(context.applicationContext)
    private val channels = ChannelBlockRepository(context.applicationContext).apply {
        // A personal block must work with no backend and no network, exactly as
        // in the accessibility service.
        userChannelRepository = brainRot
    }

    init {
        // The accessibility service pushes the cached global rules into these
        // process-wide sets when it starts. A session that only ever uses the
        // Browser tab must do the same, otherwise community keywords/channels
        // would be silently absent even though the same snapshot is on disk.
        // This reads the existing cache only — no network request.
        val global = GlobalRulesStore(context.applicationContext)
        BlockRepository.setGlobalKeywords(global.keywords)
        BrainRotRepository.setGlobalChannels(global.channelHandles)
    }

    /** The existing "Block Shorts" toggle, read from its one and only store. */
    val hideShorts: Boolean
        get() = repository.blockShorts

    /**
     * URL-level check for EVERY website.
     *
     * This covers blocked domains, keywords in the URL, Google search queries
     * and YouTube search queries (the matcher has dedicated search handling),
     * plus the existing Shorts toggle for `/shorts/` URLs.
     */
    fun blockForUrl(url: String?): BrowserBlock? {
        if (!BrowserAddress.isWebUrl(url)) return null

        // Reels are a browser-only surface (the app has no Instagram player),
        // so this decision lives with the browser's own toggle rather than in
        // the shared matcher.
        if (settings.hideInstagramReels && BrowserAddress.isInstagramReelsUrl(url)) {
            return BrowserBlock("Instagram Reels", BrowserBlock.Type.REELS)
        }

        val snapshot = ContentSnapshot(
            packageName = WEB_SURFACE,
            url = url,
            query = null,
            title = null
        )
        val decision = blockFor(matcher.check(snapshot, WEB_SURFACE)) ?: return null

        // A block found inside a search URL is a blocked SEARCH, not a blocked
        // page: the browser keeps the reader where they are instead of drawing
        // a full-page overlay over a search they never see.
        val isSearch = BrowserAddress.isGoogleSearchUrl(url) ||
            BrowserAddress.isYouTubeSearchUrl(url)
        return if (isSearch && decision.type != BrowserBlock.Type.SHORTS) {
            decision.copy(type = BrowserBlock.Type.SEARCH)
        } else {
            decision
        }
    }

    /**
     * A YouTube watch/shorts page the page script has read from the DOM.
     *
     * Channels are checked first (the same precedence the extension uses, and
     * the fastest check); then the existing long-video content policy runs on
     * the title and description.
     */
    fun blockForVideo(
        title: String?,
        description: String?,
        channelId: String?,
        channelHandle: String?,
        channelName: String?
    ): BrowserBlock? {
        channelBlock(channelId, channelHandle, channelName, allowName = true)?.let { return it }
        if (title.isNullOrBlank() && description.isNullOrBlank()) return null
        return blockFor(
            matcher.checkLongVideoContent(
                title,
                description,
                youtubeKeywords.getKeywords()
            )
        )
    }

    /**
     * A single card on a YouTube feed/search page.
     *
     * Packaged as a [FeedVideoCard] and passed through the existing feed check,
     * so title, thumbnail description and channel-name weighting are the ones
     * ClearView already applies.
     */
    fun blockForCard(
        title: String?,
        thumbnailDescription: String?,
        channelName: String?,
        channelId: String?,
        channelHandle: String?
    ): BrowserBlock? {
        // A feed card often shows the channel's display name only: the handle
        // lives in a link the mobile layout may not render (or renders in an
        // avatar whose label the DOM scan could not reach). The watch page for
        // the same video identifies the channel from its owner link, which is
        // why a blocked channel was caught after opening a video but not on the
        // Home feed. Name-only matching against the reader's own block list is
        // the fallback for exactly that card shape.
        channelBlock(channelId, channelHandle, channelName, allowName = true)?.let { return it }
        if (title.isNullOrBlank() && thumbnailDescription.isNullOrBlank()) return null
        val snapshot = ContentSnapshot(
            packageName = WEB_SURFACE,
            url = null,
            query = null,
            title = null,
            feedCards = listOf(
                FeedVideoCard(
                    title = title.orEmpty(),
                    contentDesc = thumbnailDescription,
                    channel = channelName
                )
            )
        )
        return blockFor(matcher.check(snapshot, WEB_SURFACE))
    }

    /**
     * One Instagram post or reel, read from the page DOM.
     *
     * Instagram has no ClearView-specific list of its own: the caption and the
     * author are sent through exactly the same policies YouTube content uses —
     * the reader's blocked channels first, then the existing long-video content
     * matcher (built-in + everywhere keywords + the YouTube/Brain Rot list).
     * That is what makes a channel or keyword blocked in the app apply to
     * Instagram as well, with no second copy of the rules.
     */
    fun blockForInstagram(
        caption: String?,
        channelHandle: String?,
        channelName: String?
    ): BrowserBlock? {
        channelBlock(null, channelHandle, channelName)?.let { return it }
        val text = caption?.trim().orEmpty()
            .takeIf { it.isNotBlank() } ?: return null
        return blockFor(
            matcher.checkLongVideoContent(
                text.take(2_000),
                null,
                youtubeKeywords.getKeywords()
            )
        )
    }

    /**
     * A search query typed into YouTube's own search box.
     *
     * URL-based searches are already covered by [blockForUrl]; this exists for
     * SPA search pages whose URL does not carry the query. It uses the existing
     * YouTube-only + everywhere keyword policy, matching the extension's
     * YouTube search behaviour without touching custom Chrome rules.
     */
    fun blockForYouTubeSearch(query: String?): BrowserBlock? {
        if (query.isNullOrBlank()) return null
        val result = matcher.checkLongVideoContent(
            query,
            null,
            youtubeKeywords.getKeywords()
        )
        return blockFor(result)?.copy(type = BrowserBlock.Type.SEARCH)
    }

    /**
     * A query typed into Google's own search box.
     *
     * The existing Google-app path is used rather than a new matcher: it
     * checks the query with the same three tiers as a committed Google search,
     * including the context-combination rule ("woman" + "bikini"). [googleTab]
     * is "All" for a search-box submission because the browser hides the other
     * categories anyway; an already-open category URL can pass its real tab so
     * the stricter Image/Video set still applies.
     */
    fun blockForGoogleSearch(query: String?, googleTab: String? = null): BrowserBlock? {
        if (query.isNullOrBlank()) return null
        val snapshot = ContentSnapshot(
            packageName = ContentExtractor.GOOGLE_PACKAGE,
            url = null,
            query = query,
            title = null,
            googleTab = googleTab?.takeIf { it.isNotBlank() } ?: "All"
        )
        return blockFor(
            matcher.check(snapshot, ContentExtractor.GOOGLE_PACKAGE)
        )?.copy(type = BrowserBlock.Type.SEARCH)
    }

    /** Backwards-compatible name for the YouTube search check. */
    fun blockForSearch(query: String?): BrowserBlock? = blockForYouTubeSearch(query)

    /**
     * Persist a channel block made by the reader's "Not interested" action in
     * the YouTube page.
     *
     * This reuses the app's [BlockAction], so the block lands in the same
     * Brain Rot list the rest of ClearView reads, with provenance and the one
     * notification the action produces. A display name alone is not enough:
     * the app's identity model is a handle, and inventing one from a name is
     * how a block silently matches nothing.
     */
    fun blockChannel(
        handle: String?,
        name: String?,
        surface: String = SURFACE_YOUTUBE
    ): Boolean {
        val normalized = BrainRotRepository.normalizeHandle(handle) ?: return false
        val instagram = surface == SURFACE_INSTAGRAM
        val where = if (instagram) "the Instagram browser menu" else "the YouTube browser menu"
        val added = blockAction.blockChannel(
            handle = normalized,
            name = BrainRotRepository.cleanName(name),
            reason = "Blocked from $where",
            // Provenance is what the blocking list groups by: a block made on
            // Instagram has to say so, instead of presenting itself as YouTube.
            source = if (instagram) {
                BlockedItemMeta.Source.INSTAGRAM
            } else {
                BlockedItemMeta.Source.YOUTUBE_NOT_INTERESTED
            },
            notificationMessage = "Blocked $normalized from $where.",
            notificationId = "browser-ni:$normalized"
        )
        if (added) cachedChannels = null
        return added
    }

    /**
     * Whether the browser menu should offer Block or Unblock for this channel.
     */
    fun channelBlocked(handle: String?, channelId: String?): Boolean =
        isChannelBlocked(channelId, handle)

    /**
     * Remove a channel from the reader's own block lists. Returns false when
     * the channel is still blocked by something the reader cannot remove here
     * (a global/backend rule), so the page does not report a no-op as success.
     */
    fun unblockChannel(handle: String?, channelId: String?): Boolean {
        val normalized = BrainRotRepository.normalizeHandle(handle) ?: return false
        blockAction.unblockChannel(normalized)
        cachedChannels = null
        return !isChannelBlocked(channelId, normalized)
    }

    /**
     * True when this channel is blocked by the user, a global rule, or the
     * cached backend list. Keyed on id/handle — a display name alone is not
     * trusted as identity.
     *
     * The user/backend lists are read through a short-lived snapshot. A YouTube
     * feed can report dozens of cards per scan and
     * [BrainRotRepository.getBlockedChannels] parses stored JSON on every call;
     * a two-second snapshot keeps that off the per-card path while still
     * picking up a channel blocked in the Blocking tab almost immediately.
     * The global handle set is a process-wide immutable set and is read
     * directly.
     */
    fun isChannelBlocked(
        channelId: String?,
        handle: String?,
        name: String? = null,
        allowName: Boolean = false
    ): Boolean {
        val normalized = BrainRotRepository.normalizeHandle(handle)
        if (normalized != null && BrainRotRepository.isGlobalChannelBlocked(normalized)) {
            return true
        }
        val snapshot = channelSnapshot()
        if (normalized != null && normalized in snapshot.handles) return true
        if (channelId != null && channelId in snapshot.ids) return true
        // Display-name fallback: only when the page gave no id/handle at all,
        // and only for surfaces that asked for it. A name is a weaker identity,
        // so an exact (normalised) match against the reader's OWN blocked list
        // is required — never a substring and never the global keyword rules.
        if (!allowName || normalized != null || channelId != null) return false
        val wanted = normalizeChannelName(name) ?: return false
        // Either the display name the reader's block recorded, or the stem of
        // a blocked handle ("Beluga" for `@Beluga1`). Both are exact, whole
        // name matches — never a substring.
        return wanted in snapshot.names || wanted in snapshot.stems
    }

    /**
     * Normalised display name used only by the name-only fallback above.
     *
     * The bridge used to store the literal string "null" for a card that
     * reported no name, so that spelling is treated as "no name" here too.
     */
    private fun normalizeChannelName(name: String?): String? =
        BrainRotRepository.cleanName(name)
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }

    private data class ChannelSnapshot(
        val atMs: Long,
        val handles: Set<String>,
        val ids: Set<String>,
        val names: Set<String>,
        /** Normalised stems of the READER's blocked handles that end in digits. */
        val stems: Set<String>
    )

    @Volatile
    private var cachedChannels: ChannelSnapshot? = null

    private fun channelSnapshot(): ChannelSnapshot {
        val now = System.currentTimeMillis()
        cachedChannels?.let { cached ->
            if (now - cached.atMs < CHANNEL_CACHE_MS) return cached
        }
        val handles = LinkedHashSet<String>()
        val names = LinkedHashSet<String>()
        val stems = LinkedHashSet<String>()
        for (channel in brainRot.getBlockedChannels()) {
            val handle = BrainRotRepository.normalizeHandle(channel.handle)
            handle?.let(handles::add)
            handleStem(handle)?.let(stems::add)
            normalizeChannelName(channel.name)?.let(names::add)
        }
        // A stem is a weaker spelling, so it is taken from the reader's OWN
        // list only: a global/backend handle block stays keyed on its handle.
        val ids = LinkedHashSet<String>()
        for (channel in channels.blockedChannels) {
            BrainRotRepository.normalizeHandle(channel.channelHandle)?.let(handles::add)
            channel.channelId?.takeIf { it.isNotBlank() }?.let(ids::add)
            normalizeChannelName(channel.channelName)?.let(names::add)
        }
        val snapshot = ChannelSnapshot(now, handles, ids, names, stems)
        cachedChannels = snapshot
        return snapshot
    }

    private fun channelBlock(
        channelId: String?,
        handle: String?,
        name: String?,
        allowName: Boolean = false
    ): BrowserBlock? {
        if (!isChannelBlocked(channelId, handle, name, allowName)) return null
        val label = handle?.takeIf { it.isNotBlank() }
            ?: channelId?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: "Blocked channel"
        return BrowserBlock(label, BrowserBlock.Type.CHANNEL)
    }

    private fun blockFor(result: MatchResult): BrowserBlock? {
        if (result !is MatchResult.Blocked) return null
        val type = when {
            result.matchedItem.equals("YouTube Shorts", ignoreCase = true) ->
                BrowserBlock.Type.SHORTS
            result.matchSource == com.muddassir.clearview.matching.MatchSource.QUERY ->
                BrowserBlock.Type.SEARCH
            result.matchType == com.muddassir.clearview.matching.MatchType.DOMAIN ->
                BrowserBlock.Type.DOMAIN
            else -> BrowserBlock.Type.KEYWORD
        }
        return BrowserBlock(result.matchedItem, type)
    }

}
