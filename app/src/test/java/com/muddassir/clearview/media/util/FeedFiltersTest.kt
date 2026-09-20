package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.FeedContentFilter
import com.muddassir.clearview.media.model.FeedDateFilter
import com.muddassir.clearview.media.model.FeedFilter
import com.muddassir.clearview.media.model.FeedPlatformFilter
import com.muddassir.clearview.media.model.FeedSortOrder
import com.muddassir.clearview.media.model.FeedSourceFilter
import com.muddassir.clearview.media.model.FeedWatchStatus
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.PlaylistTypeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FeedFiltersTest {

    // Fixed reference time for the relative presets (only differences matter).
    private val now = 1_767_012_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun video(id: String, at: Long, isShort: Boolean = false) = MediaVideo(
        videoId = id,
        title = id,
        channelId = "c",
        channelName = "C",
        publishedAtEpochMillis = at,
        thumbnailUrl = "",
        viewCount = 0L,
        isShort = isShort
    )

    private fun videos(): List<MediaVideo> = listOf(
        video("today", now - 2 * 60 * 60 * 1000),             // 2h ago
        video("yesterday", now - day),                         // 1 day ago
        video("three", now - 3 * day),                         // 3 days ago
        video("eight", now - 8 * day),                         // 8 days ago
        video("forty", now - 40 * day)                         // 40 days ago
    )

    @Test
    fun `default filter returns the last 3 days newest first`() {
        val result = applyFeedFilter(videos(), FeedFilter(), now)
        // The default date preset is Last 3 days — older uploads are excluded.
        assertEquals(
            listOf("today", "yesterday", "three"),
            result.map { it.videoId }
        )
        assertFalse(FeedFilter().isActive)
    }

    @Test
    fun `today filter keeps only today's uploads`() {
        val result = applyFeedFilter(videos(), FeedFilter(date = FeedDateFilter.TODAY), now)
        assertEquals(listOf("today"), result.map { it.videoId })
    }

    @Test
    fun `last 7 days keeps uploads within a week`() {
        val result = applyFeedFilter(videos(), FeedFilter(date = FeedDateFilter.LAST_7_DAYS), now)
        assertEquals(
            listOf("today", "yesterday", "three"),
            result.map { it.videoId }
        )
    }

    @Test
    fun `last 30 days excludes uploads older than a month`() {
        val result = applyFeedFilter(videos(), FeedFilter(date = FeedDateFilter.LAST_30_DAYS), now)
        assertEquals(
            listOf("today", "yesterday", "three", "eight"),
            result.map { it.videoId }
        )
    }

    @Test
    fun `custom range is inclusive on both ends`() {
        val start = now - 8 * day
        val end = now - day
        val filter = FeedFilter(
            date = FeedDateFilter.CUSTOM,
            customStartEpochMillis = start,
            customEndEpochMillis = end
        )
        val result = applyFeedFilter(videos(), filter, now)
        // "eight" (== start) and "yesterday" (== end) are both kept.
        assertEquals(
            listOf("yesterday", "three", "eight"),
            result.map { it.videoId }
        )
    }

    @Test
    fun `content type filters shorts and videos separately`() {
        val all = listOf(
            video("s1", now - day, isShort = true),
            video("v1", now - 2 * day),
            video("s2", now - 3 * day, isShort = true)
        )
        val shorts = applyFeedFilter(all, FeedFilter(content = FeedContentFilter.SHORTS), now)
        assertEquals(listOf("s1", "s2"), shorts.map { it.videoId })
        val longs = applyFeedFilter(all, FeedFilter(content = FeedContentFilter.VIDEOS), now)
        assertEquals(listOf("v1"), longs.map { it.videoId })
    }

    @Test
    fun `oldest first reverses the order`() {
        val result = applyFeedFilter(
            videos(),
            FeedFilter(sort = FeedSortOrder.OLDEST_FIRST),
            now
        )
        // The default date preset (Last 3 days) still applies — the order
        // flips within the kept window.
        assertEquals(
            listOf("three", "yesterday", "today"),
            result.map { it.videoId }
        )
    }

    @Test
    fun `isActive is true when anything is non-default`() {
        assertTrue(FeedFilter(date = FeedDateFilter.TODAY).isActive)
        assertTrue(FeedFilter(content = FeedContentFilter.SHORTS).isActive)
        assertTrue(FeedFilter(sort = FeedSortOrder.OLDEST_FIRST).isActive)
    }

    // The feed header and the filter sheet describe a filter with ONE builder,
    // so the same filter can never read two different ways. The header always
    // names the window it is showing; both then name only what was narrowed.

    @Test
    fun `summary label includes date and count`() {
        val summary = feedFilterSummary(
            FeedFilter(date = FeedDateFilter.LAST_7_DAYS),
            resultCount = 3
        )
        assertEquals("Last 7 days · 3 videos", summary)
    }

    @Test
    fun `summary includes content type when not all`() {
        val summary = feedFilterSummary(
            FeedFilter(date = FeedDateFilter.TODAY, content = FeedContentFilter.SHORTS),
            resultCount = 1
        )
        assertEquals("Today · Shorts · 1 video", summary)
    }

    @Test
    fun `summary drops the watch status at its default`() {
        // Unwatched is the default, so naming it every time was noise: it is
        // only worth a word once the reader changes it.
        val summary = feedFilterSummary(
            FeedFilter(date = FeedDateFilter.TODAY),
            resultCount = 2
        )
        assertEquals("Today · 2 videos", summary)
    }

    @Test
    fun `summary names the platform and the sort too`() {
        // Both used to be dropped from the header entirely: a feed filtered to
        // Instagram, or sorted oldest-first, said nothing about it.
        assertEquals(
            "Last 3 days · Instagram · 2 videos",
            feedFilterSummary(FeedFilter(platform = FeedPlatformFilter.INSTAGRAM), 2)
        )
        assertEquals(
            "Last 3 days · Oldest first · 2 videos",
            feedFilterSummary(FeedFilter(sort = FeedSortOrder.OLDEST_FIRST), 2)
        )
    }

    @Test
    fun `feed filter encode decode round-trip`() {
        val filter = FeedFilter(
            date = FeedDateFilter.CUSTOM,
            content = FeedContentFilter.SHORTS,
            sort = FeedSortOrder.OLDEST_FIRST,
            customStartEpochMillis = 1_000L,
            customEndEpochMillis = 2_000L
        )
        assertEquals(filter, decodeFeedFilter(encodeFeedFilter(filter)))
    }

    @Test
    fun `default filter round-trips with no custom range`() {
        assertEquals(FeedFilter(), decodeFeedFilter(encodeFeedFilter(FeedFilter())))
    }

    @Test
    fun `decode feed filter handles missing or corrupt input`() {
        assertEquals(null, decodeFeedFilter(null))
        assertEquals(null, decodeFeedFilter(""))
        assertEquals(null, decodeFeedFilter("not json"))
        assertEquals(null, decodeFeedFilter("{\"date\":\"NOPE\"}"))
    }

    @Test
    fun `watch status filters watched unwatched and partial`() {
        val all = listOf(
            video("done", now - day),
            video("partial", now - 2 * day),
            video("never", now - 3 * day)
        )
        val progress: Map<String, Float> = mapOf(
            "done" to 1f,
            "partial" to 0.4f,
            "never" to 0f
        )
        val watched = applyFeedFilter(
            all, FeedFilter(watchStatus = FeedWatchStatus.WATCHED), now,
            progressOf = { progress[it] }
        )
        assertEquals(listOf("done"), watched.map { it.videoId })

        val partial = applyFeedFilter(
            all, FeedFilter(watchStatus = FeedWatchStatus.PARTIALLY_WATCHED), now,
            progressOf = { progress[it] }
        )
        assertEquals(listOf("partial"), partial.map { it.videoId })

        val never = applyFeedFilter(
            all, FeedFilter(watchStatus = FeedWatchStatus.UNWATCHED), now,
            progressOf = { progress[it] }
        )
        assertEquals(listOf("never"), never.map { it.videoId })
    }

    @Test
    fun `unwatched includes videos with no progress at all`() {
        val all = listOf(video("fresh", now - day), video("done", now - 2 * day))
        val result = applyFeedFilter(
            all, FeedFilter(watchStatus = FeedWatchStatus.UNWATCHED), now,
            progressOf = { if (it == "done") 1f else null }
        )
        assertEquals(listOf("fresh"), result.map { it.videoId })
    }

    @Test
    fun `date plus watch status combine`() {
        val all = listOf(
            video("today-watched", now - 2 * 60 * 60 * 1000),
            video("today-unwatched", now - 60 * 60 * 1000),
            video("old-watched", now - 5 * day)
        )
        val result = applyFeedFilter(
            all,
            FeedFilter(date = FeedDateFilter.TODAY, watchStatus = FeedWatchStatus.WATCHED),
            now,
            progressOf = { if (it == "today-watched" || it == "old-watched") 1f else 0f }
        )
        assertEquals(listOf("today-watched"), result.map { it.videoId })
    }

    @Test
    fun `persisted ALL_TIME date is preserved, not migrated`() {
        // "All time" is still a first-class option in the filter sheet, so a
        // persisted ALL_TIME is as likely to be the user's explicit choice as
        // an old default. Decoding must NOT rewrite it (the old migration
        // silently discarded the user's "All time" pick on every restart).
        // Fresh installs get the Last 3 days default via the constructor, not
        // by mangling stored user choices.
        val oldJson = "{\"date\":\"ALL_TIME\",\"content\":\"ALL\"," +
            "\"sort\":\"NEWEST_FIRST\",\"watchStatus\":\"UNWATCHED\"}"
        val decoded = decodeFeedFilter(oldJson)
        assertEquals(FeedDateFilter.ALL_TIME, decoded?.date)
        // And the preserved filter round-trips stably.
        assertEquals(decoded, decodeFeedFilter(encodeFeedFilter(decoded!!)))
    }

    @Test
    fun `persisted LIVE content filter is migrated to ALL`() {
        // The Live chip was removed from Filter → Content; a filter saved by an
        // older build could still hold LIVE, which would lock the feed to an
        // unreachable filter. Decoding must normalize it to ALL.
        val oldJson = "{\"date\":\"ALL_TIME\",\"content\":\"LIVE\"," +
            "\"sort\":\"NEWEST_FIRST\",\"watchStatus\":\"UNWATCHED\"," +
            "\"library\":\"ALL\"}"
        val decoded = decodeFeedFilter(oldJson)
        assertEquals(FeedContentFilter.ALL, decoded?.content)
        // And the normalized filter round-trips stably.
        assertEquals(decoded, decodeFeedFilter(encodeFeedFilter(decoded!!)))
    }

    @Test
    fun `old filter values without new keys decode with defaults`() {
        val oldJson = "{\"date\":\"TODAY\",\"content\":\"ALL\",\"sort\":\"NEWEST_FIRST\"}"
        val decoded = decodeFeedFilter(oldJson)
        assertEquals(FeedDateFilter.TODAY, decoded?.date)
        // New default: Unwatched (old persisted values without the key inherit it).
        assertEquals(FeedWatchStatus.UNWATCHED, decoded?.watchStatus)
    }

    @Test
    fun `summary includes watch status when set`() {
        val summary = feedFilterSummary(
            FeedFilter(
                date = FeedDateFilter.TODAY,
                watchStatus = FeedWatchStatus.ALL
            ),
            resultCount = 2
        )
        assertEquals("Today · All · 2 videos", summary)
    }

    @Test
    fun `isActive is true for watch status filters`() {
        assertTrue(FeedFilter(watchStatus = FeedWatchStatus.WATCHED).isActive)
    }

    @Test
    fun `feed filter round-trip keeps new fields`() {
        val filter = FeedFilter(
            date = FeedDateFilter.LAST_7_DAYS,
            watchStatus = FeedWatchStatus.PARTIALLY_WATCHED
        )
        assertEquals(filter, decodeFeedFilter(encodeFeedFilter(filter)))
    }

    @Test
    fun `source by URL keeps only manually added videos`() {
        val all = listOf(video("manual", now - day), video("auto", now - 2 * day))
        val result = applyFeedFilter(
            all,
            FeedFilter(source = FeedSourceFilter.BY_URL, watchStatus = FeedWatchStatus.ALL),
            now,
            isManual = { it == "manual" }
        )
        assertEquals(listOf("manual"), result.map { it.videoId })
    }

    @Test
    fun `source system keeps only channel-feed videos`() {
        val all = listOf(video("manual", now - day), video("auto", now - 2 * day))
        val result = applyFeedFilter(
            all,
            FeedFilter(source = FeedSourceFilter.SYSTEM, watchStatus = FeedWatchStatus.ALL),
            now,
            isManual = { it == "manual" }
        )
        assertEquals(listOf("auto"), result.map { it.videoId })
    }

    @Test
    fun `source by rss keeps only channel-feed videos`() {
        val all = listOf(video("manual", now - day), video("auto", now - 2 * day))
        val result = applyFeedFilter(
            all,
            FeedFilter(source = FeedSourceFilter.BY_RSS, watchStatus = FeedWatchStatus.ALL),
            now,
            isManual = { it == "manual" }
        )
        assertEquals(listOf("auto"), result.map { it.videoId })
    }

    @Test
    fun `source combines with watch status`() {
        val all = listOf(
            video("manual-watched", now - day),
            video("manual-fresh", now - 2 * day)
        )
        val result = applyFeedFilter(
            all,
            FeedFilter(source = FeedSourceFilter.BY_URL, watchStatus = FeedWatchStatus.WATCHED),
            now,
            isManual = { true },
            progressOf = { if (it == "manual-watched") 1f else null }
        )
        assertEquals(listOf("manual-watched"), result.map { it.videoId })
    }

    @Test
    fun `source filter round-trips through encode decode`() {
        val filter = FeedFilter(source = FeedSourceFilter.SYSTEM)
        assertEquals(filter, decodeFeedFilter(encodeFeedFilter(filter)))
    }

    @Test
    fun `persisted PLAYLIST source from an old build decodes to All`() {
        // The "In playlists" source option was removed; a filter saved by an
        // older build could still hold PLAYLIST. Decoding must normalize it to
        // All so the feed never locks to a hidden filter.
        val oldJson = "{\"date\":\"TODAY\",\"content\":\"ALL\"," +
            "\"sort\":\"NEWEST_FIRST\",\"watchStatus\":\"ALL\"," +
            "\"source\":\"PLAYLIST\"}"
        val decoded = decodeFeedFilter(oldJson)
        assertEquals(FeedSourceFilter.ALL, decoded?.source)
    }

    @Test
    fun `summary includes source label when not all`() {
        val summary = feedFilterSummary(
            FeedFilter(source = FeedSourceFilter.BY_URL),
            resultCount = 2
        )
        // The default date preset is Last 3 days.
        assertEquals("Last 3 days · By URL · 2 videos", summary)
    }

    @Test
    fun `header and sheet agree on the same filter`() {
        // The unification's whole point: one builder, two screens. Strip the
        // header's own additions (its default date + the count) and what is
        // left is exactly what the sheet shows.
        val filter = FeedFilter(
            date = FeedDateFilter.TODAY,
            platform = FeedPlatformFilter.INSTAGRAM,
            content = FeedContentFilter.REELS
        )
        assertEquals("Today · Instagram · Reels", filter.filterSummary())
        assertEquals("Today · Instagram · Reels · 2 videos", feedFilterSummary(filter, 2))
    }

    @Test
    fun `isActive is true for source filters`() {
        assertTrue(FeedFilter(source = FeedSourceFilter.SYSTEM).isActive)
    }

    @Test
    fun `old filter values without source key decode to the All default`() {
        val oldJson = "{\"date\":\"TODAY\",\"content\":\"ALL\"," +
            "\"sort\":\"NEWEST_FIRST\",\"watchStatus\":\"ALL\"}"
        val decoded = decodeFeedFilter(oldJson)
        assertEquals(FeedSourceFilter.ALL, decoded?.source)
    }

    @Test
    fun `persisted library key from an old build is ignored`() {
        // The Bookmark feature was removed (user playlists replaced it). A
        // filter saved by an older build still holds a "library" key — decoding
        // must ignore it so the feed never locks to a dead filter.
        val oldJson = "{\"date\":\"TODAY\",\"content\":\"ALL\"," +
            "\"sort\":\"NEWEST_FIRST\",\"watchStatus\":\"ALL\"," +
            "\"library\":\"BOOKMARKED\"}"
        val decoded = decodeFeedFilter(oldJson)
        assertEquals(FeedDateFilter.TODAY, decoded?.date)
        assertEquals(FeedWatchStatus.ALL, decoded?.watchStatus)
    }

    @Test
    fun `playlist type filter round-trips through encode decode`() {
        val filter = FeedFilter(playlistType = PlaylistTypeFilter.AUDIO)
        assertEquals(filter, decodeFeedFilter(encodeFeedFilter(filter)))
    }

    @Test
    fun `old filter values without playlist type key decode to the All default`() {
        val oldJson = "{\"date\":\"TODAY\",\"content\":\"ALL\"," +
            "\"sort\":\"NEWEST_FIRST\",\"watchStatus\":\"ALL\"}"
        val decoded = decodeFeedFilter(oldJson)
        assertEquals(PlaylistTypeFilter.ALL, decoded?.playlistType)
    }

    @Test
    fun `isActive is true for playlist type filters`() {
        assertTrue(FeedFilter(playlistType = PlaylistTypeFilter.VIDEO).isActive)
    }

    @Test
    fun `feed filter round-trip keeps playlist type`() {
        val filter = FeedFilter(
            source = FeedSourceFilter.SYSTEM,
            playlistType = PlaylistTypeFilter.VIDEO
        )
        assertEquals(filter, decodeFeedFilter(encodeFeedFilter(filter)))
    }

    // ── Filter sheet header ────────────────────────────────────────

    @Test
    fun `filter summary is empty at the defaults`() {
        assertEquals("", FeedFilter().filterSummary())
        assertEquals("", FeedFilter().filterSummary(playlistContext = true))
    }

    @Test
    fun `filter summary names only the changed sections`() {
        val filter = FeedFilter(
            date = FeedDateFilter.LAST_7_DAYS,
            platform = FeedPlatformFilter.YOUTUBE,
            content = FeedContentFilter.SHORTS,
            source = FeedSourceFilter.BY_URL,
            watchStatus = FeedWatchStatus.ALL,
            sort = FeedSortOrder.OLDEST_FIRST
        )
        assertEquals(
            "Last 7 days · YouTube · Shorts · By URL · All · Oldest first",
            filter.filterSummary()
        )
        // Nothing about this filter is a date change, so the date section is
        // left out of the summary entirely.
        assertEquals(
            "YouTube · Shorts",
            FeedFilter(
                platform = FeedPlatformFilter.YOUTUBE,
                content = FeedContentFilter.SHORTS
            ).filterSummary()
        )
    }

    @Test
    fun `filter summary shows the custom range's real dates`() {
        val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val filter = FeedFilter(
            date = FeedDateFilter.CUSTOM,
            customStartEpochMillis = now,
            customEndEpochMillis = now + 6 * day
        )
        val label = filter.filterSummary()
        // The range itself, not the words "Custom date range", so the header
        // states which days the feed is actually showing.
        assertEquals(
            "${fmt.format(Date(now))} – ${fmt.format(Date(now + 6 * day))}",
            label
        )
    }

    @Test
    fun `playlist summary covers just type and source`() {
        val filter = FeedFilter(
            // Set but irrelevant inside a playlist — not named there.
            date = FeedDateFilter.TODAY,
            platform = FeedPlatformFilter.X,
            source = FeedSourceFilter.SYSTEM,
            playlistType = PlaylistTypeFilter.AUDIO
        )
        assertEquals("Audio · From device", filter.filterSummary(playlistContext = true))
        // The caller supplies the wording for device audio, so the model never
        // hard-codes UI copy.
        assertEquals(
            "Audio · Device files",
            filter.filterSummary(playlistContext = true, deviceSourceLabel = "Device files")
        )
    }

    // ── Content types per platform ─────────────────────────────────

    @Test
    fun `content options follow the platform`() {
        assertFalse(contentOptionsFor(FeedPlatformFilter.YOUTUBE).contains(FeedContentFilter.REELS))
        assertTrue(contentOptionsFor(FeedPlatformFilter.YOUTUBE).contains(FeedContentFilter.SHORTS))
        assertFalse(contentOptionsFor(FeedPlatformFilter.INSTAGRAM).contains(FeedContentFilter.VIDEOS))
        assertTrue(contentOptionsFor(FeedPlatformFilter.INSTAGRAM).contains(FeedContentFilter.REELS))
        assertEquals(listOf(FeedContentFilter.ALL), contentOptionsFor(FeedPlatformFilter.X))
        // All platforms: everything except live (the Live tab owns live).
        assertFalse(contentOptionsFor(FeedPlatformFilter.ALL).contains(FeedContentFilter.LIVE))
    }

    @Test
    fun `switching platform drops a content type it does not offer`() {
        val reels = FeedFilter(
            platform = FeedPlatformFilter.INSTAGRAM,
            content = FeedContentFilter.REELS
        )
        // Instagram keeps Reels...
        assertEquals(FeedContentFilter.REELS, reels.normalizedForPlatform().content)
        // ...YouTube doesn't offer them, so the filter falls back to All rather
        // than staying on a selection with no chip and no results.
        assertEquals(
            FeedContentFilter.ALL,
            reels.copy(platform = FeedPlatformFilter.YOUTUBE).normalizedForPlatform().content
        )
        val shorts = FeedFilter(
            platform = FeedPlatformFilter.YOUTUBE,
            content = FeedContentFilter.SHORTS
        )
        // Shorts survive a switch to a platform that still offers them...
        assertEquals(
            FeedContentFilter.SHORTS,
            shorts.copy(platform = FeedPlatformFilter.ALL).normalizedForPlatform().content
        )
        // ...but not to X, which only offers All.
        assertEquals(
            FeedContentFilter.ALL,
            shorts.copy(platform = FeedPlatformFilter.X).normalizedForPlatform().content
        )
        // An already-valid selection is left untouched.
        assertEquals(shorts, shorts.normalizedForPlatform())
    }
}
