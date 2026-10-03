package com.muddassir.clearview.media.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import com.muddassir.clearview.media.model.InstagramMediaType
import com.muddassir.clearview.ui.ContentHubState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muddassir.clearview.R
import com.muddassir.clearview.media.data.MediaLibraryStore
import com.muddassir.clearview.media.data.MediaRepository
import com.muddassir.clearview.media.data.MediaSourceStatusStore
import com.muddassir.clearview.media.data.UserPlaylistStore
import com.muddassir.clearview.media.data.WatchProgressStore
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.download.DownloadStatus
import com.muddassir.clearview.media.model.DownloadSourceFilter
import com.muddassir.clearview.media.model.FeedContentFilter
import com.muddassir.clearview.media.model.FeedFilter
import com.muddassir.clearview.media.model.FeedSourceFilter
import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.PlaylistTypeFilter
import com.muddassir.clearview.media.model.SavedChannel
import com.muddassir.clearview.media.worker.MediaNotifier
import com.muddassir.clearview.media.model.SavedPlaylist
import com.muddassir.clearview.media.model.UserPlaylist
import com.muddassir.clearview.media.util.DEVICE_SOURCE_LABEL
import com.muddassir.clearview.media.util.MediaVideos
import com.muddassir.clearview.media.util.applyFeedFilter
import com.muddassir.clearview.media.util.continueWatchingResetMessage
import com.muddassir.clearview.media.util.feedFilterSummary
import com.muddassir.clearview.media.util.formatEtaRemaining
import com.muddassir.clearview.media.util.matchesDownloadSource
import com.muddassir.clearview.media.util.formatViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Label for the device-import source shown in playlist contexts. */
/**
 * Vertical gap between playlist editor rows. Shared by the LazyColumn's
 * arrangement and the drag-to-reorder maths, so one slot is exactly
 * (row height + this gap) and the two can never drift apart.
 */
private val PLAYLIST_ROW_GAP = 8.dp

/**
 * Media tab — a Subscriptions-style feed:
 *
 *  1. Channel avatar strip on top (tap an avatar to filter to that channel,
 *     tap again or tap "All" to show everything; "+" adds a channel).
 *  2. Shorts section (vertical cards, newest first).
 *  3. Long-video list (YouTube-style rows) — each thumbnail carries a red
 *     watch-progress bar and a "Watched" badge once ≥90% was viewed (progress
 *     comes from the in-app player's real getCurrentTime/getDuration).
 *
 * The feed aggregates EVERY saved channel (cached data first for an instant
 * first paint, then a background refresh of all feeds merged newest-first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTab(
    hubState: ContentHubState? = null,
    onPlayVideo: (MediaVideo, List<MediaVideo>, Int) -> Unit,
    /** Plays the downloaded audio for [video] instead of the video (offline). */
    onPlayOffline: (MediaVideo) -> Unit = {},
    /** Opens the podcast-style audio player for a downloaded item. */
    onPlayAudio: (DownloadItem, List<DownloadItem>) -> Unit = { _, _ -> },
    /** Fired when the Media tab is shown (marks channel updates as seen). */
    onMediaOpened: () -> Unit = {},
    /** Opens the Makkah &amp; Madinah live broadcasts (Haramayn Live shortcut). */
    onOpenHaramaynLive: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { MediaRepository(context.applicationContext) }
    val progressStore = remember { WatchProgressStore(context.applicationContext) }
    val libraryStore = remember { MediaLibraryStore(context.applicationContext) }
    // Offline audio downloads: idempotent init (cheap after the first call);
    // the cards and the Downloads section read the state directly below.
    AudioDownloads.initialize(context.applicationContext)
    // Bumped whenever the library changes (hide / manual add / playlist edit)
    // so the merged feed and the Continue Watching row recompute.
    var libraryRevision by remember { mutableIntStateOf(0) }

    var channels by remember { mutableStateOf(repository.getSavedChannels()) }
    val filterChannelIdState = remember(hubState) {
        object : androidx.compose.runtime.MutableState<String?> {
            private var _local by mutableStateOf<String?>(hubState?.mediaFilterChannelId)
            override var value: String?
                get() = hubState?.mediaFilterChannelId ?: _local
                set(v) {
                    _local = v
                    hubState?.mediaFilterChannelId = v
                }
            override fun component1() = value
            override fun component2(): (String?) -> Unit = { value = it }
        }
    }
    var filterChannelId by filterChannelIdState
    var videos by remember { mutableStateOf<List<MediaVideo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showingCached by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Pull-to-refresh: bumping the token re-runs the feed load. The load effect
    // is keyed on channelIdsKey (channel add/remove) + this token, so a manual
    // refresh never needs the channels to change.
    var refreshToken by remember { mutableIntStateOf(0) }
    // When the last successful refresh completed (epoch ms) — drives the
    // "Updated Xm ago" hint under the feed header. Seeded from the persisted
    // value so the hint survives a tab switch (this composable is torn down
    // whenever the user leaves the Media tab).
    var lastRefreshedAt by remember { mutableStateOf(repository.lastFeedRefreshAt()) }
    // Bumped every minute while a refresh time exists so the "Updated Xm ago"
    // hint re-renders over time instead of freezing at "0 minutes ago".
    var clockTick by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAllChannelsSheet by rememberSaveable { mutableStateOf(false) }
    var channelDirectoryQuery by rememberSaveable { mutableStateOf("") }
    var channelDirectoryPlatform by rememberSaveable { mutableStateOf<MediaPlatform?>(null) }
    var showAddVideoDialog by remember { mutableStateOf(false) }
    var showHiddenDialog by remember { mutableStateOf(false) }
    var showHiddenChannelsDialog by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<SavedChannel?>(null) }
    // A downloaded video awaiting "delete offline audio" confirmation — the
    // card's ⋮ menu "Delete download" no longer deletes without asking.
    var pendingDeleteDownload by remember { mutableStateOf<MediaVideo?>(null) }
    // Downloads view source filter (All / By URL / By RSS / From device) — hoisted
    // here so the header's result count matches the section's list. Only used
    // while the Downloads content filter is active.
    var downloadsSourceFilter by remember { mutableStateOf(DownloadSourceFilter.ALL) }
    // Feed search: a live title/channel filter applied on top of the current
    // feed (All Feed or a selected channel) — never refetches.
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // ── Imported YouTube playlists (added by URL) ─────────────────
    var playlists by remember { mutableStateOf(repository.getSavedPlaylists()) }
    val selectedPlaylistIdState = remember(hubState) {
        object : androidx.compose.runtime.MutableState<String?> {
            private var _local by mutableStateOf<String?>(hubState?.mediaSelectedPlaylistId)
            override var value: String?
                get() = hubState?.mediaSelectedPlaylistId ?: _local
                set(v) {
                    _local = v
                    hubState?.mediaSelectedPlaylistId = v
                }
            override fun component1() = value
            override fun component2(): (String?) -> Unit = { value = it }
        }
    }
    var selectedPlaylistId by selectedPlaylistIdState
    var playlistVideos by remember { mutableStateOf<List<MediaVideo>>(emptyList()) }
    var playlistLoading by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    var playlistRefreshedAt by remember { mutableStateOf(0L) }
    var showAddPlaylistDialog by remember { mutableStateOf(false) }
    var pendingPlaylistRemove by remember { mutableStateOf<SavedPlaylist?>(null) }
    // Video awaiting "remove from playlist" confirmation — playlist + video
    // captured at tap time so the dialog never depends on later state.
    var pendingVideoRemove by remember { mutableStateOf<Pair<UserPlaylist, MediaVideo>?>(null) }
    // A user playlist awaiting "delete playlist" confirmation (deletes the
    // whole playlist, not just one video) — captured at tap time.
    var pendingUserPlaylistDelete by remember { mutableStateOf<UserPlaylist?>(null) }

    // ── User-created playlists (local library) ────────────────────
    val userPlaylistStore = remember { UserPlaylistStore(context.applicationContext) }
    // Bumped whenever a user playlist is created / renamed / deleted / edited.
    var playlistRevision by remember { mutableIntStateOf(0) }
    var showPlaylistsSheet by rememberSaveable { mutableStateOf(false) }
    val selectedUserPlaylistIdState = remember(hubState) {
        object : androidx.compose.runtime.MutableState<String?> {
            private var _local by mutableStateOf<String?>(hubState?.mediaSelectedUserPlaylistId)
            override var value: String?
                get() = hubState?.mediaSelectedUserPlaylistId ?: _local
                set(v) {
                    _local = v
                    hubState?.mediaSelectedUserPlaylistId = v
                }
            override fun component1() = value
            override fun component2(): (String?) -> Unit = { value = it }
        }
    }
    var selectedUserPlaylistId by selectedUserPlaylistIdState

    // Back handling within the Media tab: returns from channel/playlist back to All feed
    BackHandler(
        enabled = filterChannelId != null || selectedPlaylistId != null || selectedUserPlaylistId != null || showPlaylistsSheet || showAllChannelsSheet || searchActive
    ) {
        when {
            searchActive -> {
                searchActive = false
                searchQuery = ""
            }
            showPlaylistsSheet -> showPlaylistsSheet = false
            showAllChannelsSheet -> showAllChannelsSheet = false
            selectedUserPlaylistId != null -> selectedUserPlaylistId = null
            selectedPlaylistId != null -> selectedPlaylistId = null
            filterChannelId != null -> filterChannelId = null
        }
    }
    // The feed filter is persisted per context (survives restarts): the All
    // Feed filter when no channel is selected, each channel's own filter when
    // one is, and each playlist's own filter while it's open (imported by URL
    // or user-created) — so a playlist's filter choices never leak into the
    // All Feed (or back), and the All Feed's never leak into a playlist.
    val feedFilterSlot: String? = when {
        selectedUserPlaylistId != null -> "user-playlist-" + selectedUserPlaylistId
        selectedPlaylistId != null -> "imported-playlist-" + selectedPlaylistId
        else -> filterChannelId
    }
    var feedFilter by remember(feedFilterSlot) {
        mutableStateOf(repository.getFeedFilter(feedFilterSlot))
    }
    var showFilterSheet by remember { mutableStateOf(false) }
    val saveFilter: (FeedFilter) -> Unit = { f ->
        feedFilter = f
        repository.setFeedFilter(f, feedFilterSlot)
    }
    var showPlaylistEditor by remember { mutableStateOf(false) }
    var showAddVideosPicker by remember { mutableStateOf(false) }
    // Name dialog: null target = create a new playlist, non-null = rename it.
    var showPlaylistNameDialog by remember { mutableStateOf(false) }
    var playlistNameTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    // A video the user tapped "Add to playlist…" on in a card's ⋮ menu.
    var pendingAddToPlaylist by remember { mutableStateOf<MediaVideo?>(null) }
    // A video the user chose to seed a NEW playlist with (via the ⋮ menu's
    // "New playlist with this video" — routed through the name dialog).
    var pendingCreateWithVideo by remember { mutableStateOf<MediaVideo?>(null) }
    // The Instagram post expanded from the grid (null = nothing expanded);
    // tapping a tile opens it full screen.
    var expandedPost by remember { mutableStateOf<MediaVideo?>(null) }
    val userPlaylists = remember(playlistRevision) { userPlaylistStore.getPlaylists() }
    val selectedUserPlaylist = remember(userPlaylists, selectedUserPlaylistId) {
        userPlaylists.firstOrNull { it.id == selectedUserPlaylistId }
    }
    val saveUserPlaylists: () -> Unit = { playlistRevision++ }

    // "Add from device": picks video/audio files from the device (multi-select
    // through the modern DocumentsUI picker — no storage permissions needed)
    // and imports them into the offline library (Downloads), so they play like
    // any downloaded audio. Inside a user playlist the picked files are ALSO
    // added to that playlist (where the menu item originally lived); in the
    // All Feed / a channel feed they land in Downloads only.
    val addSystemVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        // Cancelled (or nothing picked) — nothing to do.
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val target = selectedUserPlaylist
        AudioDownloads.importFromDevice(
            context = context,
            uris = uris,
            onResult = { imported ->
                val noun = if (imported == 1) "file" else "files"
                Toast.makeText(
                    context,
                    when {
                        imported == 0 -> "Couldn't import those files."
                        imported == uris.size && target != null -> "Added $imported $noun to ${target.name}"
                        imported == uris.size -> "Added $imported $noun to Downloads"
                        else -> "Added $imported of ${uris.size} $noun"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            },
            onImported = { items ->
                target?.let { p ->
                    userPlaylistStore.addVideos(p.id, items.map { it.toMediaVideo() })
                    saveUserPlaylists()
                }
                // Without a playlist the items already landed in the offline
                // library — importFromDevice calls AudioDownloads.refresh(),
                // which repopulates the Downloads section automatically.
            }
        )
    }

    // The user opened the Media tab — its channel updates count as seen.
    LaunchedEffect(Unit) { onMediaOpened() }

    // ── Per-source health (the header's status line) ───────────────
    // The providers and the repository record why a source is not answering.
    // Only the providers behind the channels IN VIEW can explain an empty feed
    // here, so an X throttle is never blamed on a YouTube-only feed.
    val sourceStatuses by MediaSourceStatusStore.statuses.collectAsState()
    val sourceIssue: String? = remember(sourceStatuses, channels, filterChannelId, clockTick) {
        val platforms = if (filterChannelId == null) {
            channels.map { it.platform }.toSet()
        } else {
            channels.firstOrNull { it.channelId == filterChannelId }
                ?.let { setOf(it.platform) }
                .orEmpty()
        }
        val now = System.currentTimeMillis()
        MediaSourceStatusStore.issues(now)
            .firstOrNull { it.platform in platforms }
            ?.let { MediaSourceStatusStore.describe(it, now) }
    }

    // Keep the "Updated Xm ago" hint and the status line's countdown fresh:
    // every minute (while either is on screen) bump clockTick, which recomposes
    // the header text.
    LaunchedEffect(lastRefreshedAt, sourceIssue) {
        while (lastRefreshedAt > 0L || sourceIssue != null) {
            delay(60_000L)
            clockTick++
        }
    }

    // Stable key: the channel IDS only. The avatar backfill below replaces the
    // list object with the same ids (avoids keying the feed load on the list
    // identity — which would re-fetch every feed whenever an avatar landed).
    val channelIdsKey = channels.map { it.channelId }.sorted().joinToString(",")

    // Warm the YouTube player while the feed is on screen. Every open used to
    // pay for the WebView renderer spawning and the IFrame API being fetched at
    // tap time, for the video the user was still deciding on; doing it here
    // means the tap goes straight to starting that video. No-op on low-RAM
    // devices and only while there is something to watch.
    LaunchedEffect(channelIdsKey) {
        if (channels.isNotEmpty()) {
            YoutubePlayerPrewarm.warm(context.applicationContext)
        }
    }

    // Aggregate load. It paints in three stages so the tab never sits on a
    // skeleton for data it already has:
    //   1. the in-memory feed from the last visit (instant after a tab switch),
    //   2. the per-channel caches on disk,
    //   3. the network — and only when a refresh is actually due.
    // The network step runs once per FEED_REFRESH_INTERVAL_MS, on a
    // pull-to-refresh, and when a channel is added or removed (both bump
    // refreshToken). Merely re-entering the tab, or switching between channels,
    // never re-fetches anything — that repeated storm is what left the feed
    // spinning on every tab switch and got X's syndication endpoint to
    // rate-limit the install.
    LaunchedEffect(channelIdsKey, refreshToken) {
        if (channels.isEmpty()) {
            videos = emptyList()
            isLoading = false
            errorMessage = null
            return@LaunchedEffect
        }
        // Paint what we already have before touching the network. The memo can
        // still hold a channel the user just removed (removing only rewrites
        // the saved list), so keep just the channels that are saved right now.
        val savedChannelIds = channels.map { it.channelId }.toSet()
        val memo: List<MediaVideo>? = repository.getMemoizedAllFeed()
            ?.filter { it.channelId in savedChannelIds }
            ?.takeIf { it.isNotEmpty() }
        val cached = memo
            ?: withContext(Dispatchers.IO) { repository.getAllCachedVideos(channels) }
        if (cached.isNotEmpty()) {
            videos = cached
            // The in-memory feed came from this session's refresh, so it is not
            // "cached" data — only a disk-only paint gets the hint.
            showingCached = memo == null
        }
        val explicitRefresh = refreshToken > 0
        if (!explicitRefresh && !repository.isFeedRefreshDue()) {
            isLoading = false
            errorMessage = null
            return@LaunchedEffect
        }
        isLoading = true
        errorMessage = null
        val fresh = repository.refreshAllVideos(channels)
        if (fresh != null) {
            // Merge, don't replace: a channel whose refresh failed this round
            // keeps its cached videos. MediaVideos.merge keeps the FIRST list's
            // copy of an id, so fresh goes first (RSS is authoritative for
            // overlap — it carries this round's enriched durations and shorts
            // classification); cached then only fills in the channels that
            // fresh is missing. Without this, a freshly added channel could
            // vanish from the feed entirely just because its very first
            // refresh hit a transient failure — and every reload would repeat
            // the same replacement.
            videos = MediaVideos.merge(fresh, cached)
            showingCached = false
            lastRefreshedAt = System.currentTimeMillis()
            repository.markFeedRefreshed(lastRefreshedAt)
            repository.memoizeAllFeed(videos)
        } else if (cached.isEmpty()) {
            errorMessage = "Couldn't load videos. Check your internet connection."
        }
        isLoading = false
    }

    // Best-effort avatar backfill (separate effect so an avatar landing never
    // re-triggers the feed load). Runs on open and after add/remove; the
    // updated list keeps the same channel ids so the feed effect stays put.
    LaunchedEffect(channelIdsKey) {
        if (channels.isEmpty()) return@LaunchedEffect
        val withAvatars = withContext(Dispatchers.IO) { repository.fillMissingAvatars(channels) }
        if (withAvatars != null) channels = withAvatars
    }

    // Playlist load: the selected imported playlist's cached videos first
    // (instant), then a background refresh of the playlist page. Runs when the
    // selection changes or the pull-to-refresh token bumps.
    LaunchedEffect(selectedPlaylistId, refreshToken) {
        val id = selectedPlaylistId ?: return@LaunchedEffect
        playlistLoading = true
        playlistError = null
        try {
            val cached = withContext(Dispatchers.IO) { repository.getCachedPlaylistVideos(id) }
            if (cached?.first?.isNotEmpty() == true) playlistVideos = cached.first
            val fresh = repository.refreshPlaylistVideos(id)
            when {
                fresh == null && cached?.first?.isNotEmpty() != true ->
                    playlistError = "Couldn't load this playlist. Check your connection."
                fresh != null && fresh.isEmpty() && cached?.first?.isNotEmpty() != true ->
                    playlistError = "This playlist is private or unavailable."
                fresh != null && fresh.isNotEmpty() -> {
                    playlistVideos = fresh
                    playlistRefreshedAt = System.currentTimeMillis()
                }
            }
        } finally { playlistLoading = false }
    }

    // The feed plus the user's library (manually added videos — which can be
    // older than the RSS window), merged by id (RSS wins). Hidden videos are
    // filtered out everywhere EXCEPT the Hidden manager.
    val manualVideos = remember(libraryRevision) { libraryStore.getManuallyAddedVideos() }
    val hiddenVideos = remember(libraryRevision) { libraryStore.getHiddenVideos() }
    val hiddenChannelIds = remember(libraryRevision) { libraryStore.getHiddenChannelIds() }
    // Hidden videos are scoped to the current feed context: in a channel feed
    // only THAT channel's hidden videos are shown in the manager; in the All
    // Feed every channel's hidden videos appear.
    val contextHiddenVideos = remember(hiddenVideos, filterChannelId) {
        if (filterChannelId == null) hiddenVideos
        else hiddenVideos.filter { it.channelId == filterChannelId }
    }
    val hiddenIds = remember(hiddenVideos) { hiddenVideos.map { it.videoId }.toSet() }
    val mergedVideos = remember(videos, manualVideos) {
        MediaVideos.merge(videos, manualVideos)
    }
    // Channel hiding is an All Feed preference only. A hidden channel remains
    // subscribed, stays in the avatar row, and remains fully visible when its
    // avatar is opened directly.
    val visibleVideos = remember(mergedVideos, hiddenIds, hiddenChannelIds, filterChannelId) {
        mergedVideos.filterNot {
            it.videoId in hiddenIds ||
                (filterChannelId == null && it.channelId in hiddenChannelIds)
        }
    }

    // Feed source: a selected imported YouTube playlist, a selected user
    // playlist, or the normal channel feed (All Feed / a single channel).
    // Only one context can be active at a time.
    val feedIsPlaylist = selectedPlaylistId != null
    val feedIsUserPlaylist = selectedUserPlaylistId != null
    val playlistContextVideos = remember(playlistVideos, hiddenIds) {
        playlistVideos.filterNot { it.videoId in hiddenIds }
    }
    val userPlaylistVideos = selectedUserPlaylist?.videos.orEmpty()
    val baseVideos = when {
        feedIsUserPlaylist -> userPlaylistVideos
        feedIsPlaylist -> playlistContextVideos
        else -> visibleVideos
    }
    // Channel strip filter + the All Feed filters are applied locally to the
    // already-loaded videos — no refetching. Playlist contexts bypass the
    // channel strip entirely.
    val channelVideos = remember(baseVideos, filterChannelId, feedIsPlaylist, feedIsUserPlaylist) {
        when {
            feedIsPlaylist || feedIsUserPlaylist -> baseVideos
            // The All Feed includes EVERY source; each item then renders as the
            // card its own type calls for (see the unified feed list below).
            filterChannelId == null -> baseVideos
            else -> baseVideos.filter { it.channelId == filterChannelId }
        }
    }
    // Id-set behind the Source filter: videos the user added manually by URL.
    val manualIds = remember(manualVideos) { manualVideos.map { it.videoId }.toSet() }
    // A device-imported audio's id is prefixed "device-".
    val isDeviceAudio: (String) -> Boolean = { it.startsWith("device-") }
    // User playlists keep their hand-picked ORDER — only the Source (By URL /
    // From device) and Type (Video / Audio) filters apply there, and only then
    // does the Type filter see the Source-filtered list; search still on top.
    // Imported YouTube playlists are treated the same way: a curated list that
    // ALWAYS shows every one of its videos in playlist order — the All Feed's
    // date / content / watch / sort filters never apply to it (different
    // context, different behavior).
    val watchRev by WatchProgressStore.revisionFlow.collectAsState()
    val displayed = remember(
        channelVideos, feedFilter, manualIds, feedIsUserPlaylist, feedIsPlaylist, watchRev
    ) {
        when {
            feedIsUserPlaylist -> {
                val bySource = when (feedFilter.source) {
                    FeedSourceFilter.ALL -> channelVideos
                    FeedSourceFilter.BY_URL -> channelVideos.filter { it.videoId in manualIds }
                    // "From device": audio imported from the system into the playlist.
                    FeedSourceFilter.SYSTEM -> channelVideos.filter { isDeviceAudio(it.videoId) }
                    // "By RSS": YouTube videos that came from a saved channel's
                    // feed — not added by URL and not device audio.
                    FeedSourceFilter.BY_RSS -> channelVideos.filter {
                        it.videoId !in manualIds && !isDeviceAudio(it.videoId)
                    }
                }
                when (feedFilter.playlistType) {
                    PlaylistTypeFilter.ALL -> bySource
                    // Audio entries: device imports (device- ids) OR the
                    // downloaded audio of a YouTube video (isOfflineAudio).
                    PlaylistTypeFilter.VIDEO -> bySource.filterNot {
                        it.isOfflineAudio || isDeviceAudio(it.videoId)
                    }
                    PlaylistTypeFilter.AUDIO -> bySource.filter {
                        it.isOfflineAudio || isDeviceAudio(it.videoId)
                    }
                }
            }
            // Imported playlist: no filtering at all — every playlist video is
            // shown, in the order YouTube provides it.
            feedIsPlaylist -> channelVideos
            else -> applyFeedFilter(
                channelVideos,
                feedFilter,
                progressOf = { progressStore.get(it) },
                isManual = { it in manualIds }
            )
        }
    }
    // Feed search: matches titles and channel names (case-insensitive) over
    // the channel + feed-filtered list; a blank query leaves the feed alone.
    // The shorts queue built from `shorts` below inherits the search scope, so
    // swiping through results stays within the matches.
    val searchResults = remember(displayed, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) displayed
        else displayed.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.channelName.contains(q, ignoreCase = true)
        }
    }
    // Shorts are YouTube Shorts only — an Instagram Reel is a NORMAL video
    // here (same card, resume and Continue Watching as any YouTube video).
    // The three splits below are REMEMBERED: the feed recomposes on every
    // watch-progress revision, download tick and playback state change, and
    // without a remember each one re-scanned the whole feed every time.
    val shorts = remember(searchResults) { searchResults.filter { it.isShortsEntry } }
    // The Videos section: everything playable — YouTube long videos, Instagram
    // Reels/videos and an X tweet that carries a clip. Instagram items sort
    // after the YouTube ones so each source stays grouped, newest first within
    // its group (a stable sort keeps the incoming order).
    val longs = remember(searchResults) {
        searchResults.filterNot { it.isShortsEntry || it.isPost }
            .sortedBy { if (it.isInstagram) 1 else 0 }
    }
    // The Posts grid: still posts — an Instagram photo/carousel and an X tweet
    // with no clip attached — as small square tiles AFTER the video rows.
    val posts = remember(searchResults) { searchResults.filter { it.isPost } }

    // The offline tracks behind the feed's audio entries, and the queue a
    // playlist context plays them with (its own audio entries, in order) — this
    // is what the audio player's Next / Previous buttons walk.
    val downloadedItems = AudioDownloads.items.value
    val playlistAudioQueue = remember(searchResults, downloadedItems) {
        searchResults.mapNotNull { v -> downloadedItems.firstOrNull { it.videoId == v.videoId } }
    }
    val isSearching = searchActive && searchQuery.isNotBlank()
    val unreadChannelCounts = remember(channels, libraryRevision, showAllChannelsSheet) {
        val history = repository.getUpdatesHistory()
        val unreadIds = repository.unreadUpdateIds(history)
        history.filter { it.latestVideoId in unreadIds }
            .groupingBy { it.channelId }
            .eachCount()
    }
    val selectedChannel = remember(channels, filterChannelId) {
        channels.firstOrNull { it.channelId == filterChannelId }
    }
    val matchingChannels = remember(channels, searchQuery, isSearching) {
        if (!isSearching) emptyList()
        else {
            val q = searchQuery.trim()
            channels.filter {
                it.displayName.contains(q, ignoreCase = true) ||
                    it.sourceRef.contains(q, ignoreCase = true)
            }
        }
    }

    // ── Continue Watching: partially watched LONG videos in the current
    // channel context, capped so the section stays compact (Shorts never
    // appear here — they're meant to be watched in one sitting). Hidden in
    // playlist contexts — those are curated, ordered lists.
    val continueWatching = remember(
        channelVideos, hiddenIds, libraryRevision, feedIsPlaylist, feedIsUserPlaylist, watchRev
    ) {
        if (feedIsPlaylist || feedIsUserPlaylist) emptyList()
        else channelVideos
            .filter { v ->
                !v.isShortsEntry && !v.isLive &&
                    !progressStore.isDismissedFromContinueWatching(v.videoId) &&
                    (progressStore.get(v.videoId)?.let {
                        it >= 0.02f && it < 0.9f
                    } ?: false)
            }
            .sortedByDescending { it.publishedAtEpochMillis }
            .take(6)
    }
    // Tapping a card ALWAYS opens the normal (WebView) video player — even for
    // downloaded videos, so watching online is never taken away by a download.
    // The offline audio stays available as an explicit choice: the card's ⋮
    // menu → "Play audio offline", and the player's below-video button.
    val playShort: (MediaVideo) -> Unit = { video ->
        val idx = shorts.indexOfFirst { it.videoId == video.videoId }
        onPlayVideo(video, shorts, idx)
    }
    val playLong: (MediaVideo) -> Unit = { video ->
        // A POST (an X tweet without a video, an Instagram photo/carousel)
        // opens in the in-app post viewer; everything with playable media goes
        // to the player. Neither path leaves the app.
        if (video.isPost) {
            expandedPost = video
        } else {
            onPlayVideo(video, emptyList(), -1)
        }
    }
    // Inside a playlist the feed shows ONLY the playlist's own videos — the
    // channel-feed states (no channels / loading / errors) never apply there.
    val inPlaylistContext = feedIsUserPlaylist || feedIsPlaylist
    // The Downloads content filter swaps the feed for the Downloads section.
    // Never while a playlist is open — a leftover Downloads filter from the
    // All Feed must not replace the playlist's own videos.
    val downloadsFilter = !inPlaylistContext && feedFilter.content == FeedContentFilter.DOWNLOADS
    // Entering the Downloads view resets any stale feed-search query, so the
    // offline-audio list never filters for an invisible reason (the header
    // search toggle is hidden there — only the section's own field searches).
    LaunchedEffect(downloadsFilter) {
        if (downloadsFilter) {
            searchActive = false
            searchQuery = ""
        }
    }

    // The hub top bar's chevron (beside the "Media" title) hides the channel
    // strip, so the feed gets its height. The feed's own actions — search, ⋮ and
    // the filter button — stay put: they are how the feed is used, not just how
    // it is navigated.
    val channelStripHidden = hubState?.mediaChannelStripHidden == true

    Column(modifier = modifier.fillMaxSize()) {

        // ── Channel avatar strip (Subscriptions-style) ─────────────
        // Hidden inside a playlist — the playlist shows only its own videos
        // (leave it via the ✕ in the header instead of the channel strip) — and
        // foldable from the top bar when the feed needs the space.
        if (!inPlaylistContext && !channelStripHidden) {
        // WhatsApp-Channels-style subscription header: the row is clearly a
        // channel surface, while View all remains the single directory entry.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Channels",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showAllChannelsSheet = true }) {
                Text("View all")
            }
        }
        // Channel-strip order, by ACTIVITY: the channel that last posted
        // anything leads the row, so the strip re-shapes itself on every feed
        // refresh. Volume only breaks ties, and a freshly added channel with
        // nothing yet sorts by when it was added — it must not be buried.
        //
        // Computed OUT here: a LazyRow's content scope is not @Composable, so
        // nothing that calls remember()/collectAsState() may live inside it.
        val activeChannels = remember(channels, videos) {
            val latestByChannel = HashMap<String, Long>()
            val countByChannel = HashMap<String, Int>()
            videos.forEach { video ->
                latestByChannel[video.channelId] =
                    maxOf(latestByChannel[video.channelId] ?: 0L, video.publishedAtEpochMillis)
                countByChannel[video.channelId] = (countByChannel[video.channelId] ?: 0) + 1
            }
            channels.sortedWith(
                compareByDescending<SavedChannel> { latestByChannel[it.channelId] ?: 0L }
                    .thenByDescending { countByChannel[it.channelId] ?: 0 }
                    .thenByDescending { it.addedAtEpochMillis }
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            item(key = "all") {
                AllAvatar(
                    selected = filterChannelId == null,
                    onClick = {
                        filterChannelId = null
                        // Leaving a playlist context: the channel strip always wins.
                        selectedPlaylistId = null
                        selectedUserPlaylistId = null
                    }
                )
            }
            item(key = "playlists") {
                PlaylistsAvatar(
                    // Only ever visible from the feed — inside a playlist the
                    // strip is hidden, so this stays in its feed-home state.
                    selected = feedIsUserPlaylist || feedIsPlaylist,
                    onClick = { showPlaylistsSheet = true }
                )
            }
            // Haramayn Live: Makkah & Madinah in-app broadcasts, moved off the
            // old bottom Live tab into this shortcut.
            item(key = "haramayn") {
                HaramaynLiveAvatar(onClick = onOpenHaramaynLive)
            }
            // Show every saved subscription; latest activity only controls
            // ordering, never whether a channel disappears from the row.
            items(activeChannels, key = { it.channelId }) { channel ->
                ChannelAvatar(
                    channel = channel,
                    selected = filterChannelId == channel.channelId,
                    onClick = {
                        filterChannelId =
                            if (filterChannelId == channel.channelId) null else channel.channelId
                        // Leaving a playlist context: the channel strip always wins.
                        selectedPlaylistId = null
                        selectedUserPlaylistId = null
                    },
                    onRemove = { pendingRemove = channel }
                )
            }
            item(key = "add") {
                AddAvatar(onClick = { showAddDialog = true })
            }
            // View all is presented in the WhatsApp-style header above; keep
            // the row focused on subscriptions and the add action.
        }
        }

        /* Imported playlist chips intentionally stay in the Playlists manager.
           They are not rendered in the All Feed: this keeps the feed header and
           bottom navigation compact while preserving imported playlists. */
        Spacer(Modifier.height(4.dp))

        // ── All Feed header: filter button (highlighted when active) + summary ──
        // Shown whenever there is a feed at all — including when a filter hides
        // every video (so it can always be reset), in playlist contexts, and
        // whenever a provider issue has to be explained: an empty feed caused
        // by a throttled source is exactly when this header is needed most.
        // Hidden only during the no-channels / loading states.
        if (videos.isNotEmpty() || feedIsPlaylist || feedIsUserPlaylist || sourceIssue != null) {
            // "Updated Xm ago" comes from whichever source feeds the screen:
            // the channel feeds in normal mode, the playlist page otherwise.
            val sourceRefreshedAt = if (feedIsPlaylist) playlistRefreshedAt else lastRefreshedAt
            val sourceLoading = if (feedIsPlaylist) playlistLoading else isLoading
            val headerTitle = when {
                feedIsUserPlaylist -> selectedUserPlaylist?.name ?: "My Playlist"
                feedIsPlaylist -> playlists.firstOrNull { it.playlistId == selectedPlaylistId }
                    ?.title ?: "Playlist"
                downloadsFilter -> "Downloads"
                else -> "All Feed"
            }
            // The header's result count mirrors what the section shows: the
            // channel + search-scoped downloads in Downloads view, the feed
            // search results otherwise.
            val resultCount = if (downloadsFilter) {
                // Remembered: this re-scans the whole download library with
                // string matching, and the feed recomposes constantly (watch
                // progress ticks, download state, playback state). None of that
                // changes the count, so there is no reason to recompute it.
                val downloads = AudioDownloads.items.value
                remember(
                    downloads, searchQuery, filterChannelId, channels, downloadsSourceFilter
                ) {
                    val q = searchQuery.trim()
                    val channelName =
                        channels.firstOrNull { it.channelId == filterChannelId }?.displayName
                    downloads.count { item ->
                        val inChannel = filterChannelId == null ||
                            item.channelId == filterChannelId ||
                            (item.channelId.isBlank() && channelName != null &&
                                item.channelName.equals(channelName, ignoreCase = true))
                        inChannel &&
                            (q.isEmpty() || item.title.contains(q, ignoreCase = true) ||
                                item.channelName.contains(q, ignoreCase = true)) &&
                            matchesDownloadSource(item, downloadsSourceFilter)
                    }
                }
            } else searchResults.size
            FeedHeader(
                title = headerTitle,
                filter = feedFilter,
                resultCount = resultCount,
                hiddenCount = contextHiddenVideos.size,
                hiddenChannelCount = hiddenChannelIds.size,
                // "Add video by URL" is available in the All Feed, when a
                // channel is selected, and when viewing one of the user's own
                // playlists (the added video then lands in that playlist too).
                // It stays off inside an imported YouTube playlist — a by-URL
                // video can't be appended to YouTube's own playlist.
                canAddVideo = !feedIsPlaylist,
                canEditPlaylist = feedIsUserPlaylist,
                // Feed-only ⋮ menu entries stay off while inside a playlist.
                isPlaylistContext = inPlaylistContext,
                // An imported YouTube playlist always shows every video — no
                // filter button or filter summary there (it's not the All Feed).
                filterEnabled = !feedIsPlaylist,
                searchActive = searchActive && !downloadsFilter,
                searchQuery = searchQuery,
                searchEnabled = !downloadsFilter,
                // "Updated Xm ago" — recomputed whenever a refresh lands OR
                // the minute ticker bumps (clockTick read forces recompose).
                // Why a provider is not answering ("X is rate-limiting …"), so
                // an empty feed is never a silent mystery.
                sourceStatus = sourceIssue,
                updatedAgo = if (sourceRefreshedAt > 0L && !sourceLoading && clockTick >= 0) {
                    val ago = DateUtils.getRelativeTimeSpanString(
                        sourceRefreshedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString()
                    if (ago.startsWith("0 min")) "Updated just now" else "Updated $ago"
                } else null,
                onClose = if (feedIsPlaylist || feedIsUserPlaylist) {
                    {
                        selectedPlaylistId = null
                        selectedUserPlaylistId = null
                    }
                } else null,
                onSearchQueryChange = { searchQuery = it },
                onToggleSearch = {
                    searchActive = !searchActive
                    searchQuery = ""
                },
                onOpenFilter = { showFilterSheet = true },
                onReset = { saveFilter(FeedFilter()) },
                onOpenHidden = { showHiddenDialog = true },
                onResetContinueWatching = {
                    // Say how much went: the row just disappears otherwise, and
                    // "already empty" is worth knowing.
                    val cleared = progressStore.clearContinueWatching()
                    Toast.makeText(
                        context,
                        continueWatchingResetMessage(cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onOpenHiddenChannels = { showHiddenChannelsDialog = true },
                onOpenChannels = { showAllChannelsSheet = true },
                showHiddenChannelsManager = filterChannelId == null,
                onAddVideo = { showAddVideoDialog = true },
                onAddPlaylist = { showAddPlaylistDialog = true },
                onOpenMyPlaylists = { showPlaylistsSheet = true },
                onEditPlaylist = { showPlaylistEditor = true },
                // "Add from device": pick video/audio files from the phone via
                // the modern DocumentsUI picker (no storage permissions needed).
                // Available in the All Feed, a channel feed, and inside the
                // user's own playlists (where the picked files also land in the
                // open playlist).
                onAddFromSystem = {
                    addSystemVideoLauncher.launch(arrayOf("video/*", "audio/*"))
                },
                // Rename the playlist you're viewing (⋮ menu).
                onRenamePlaylist = if (feedIsUserPlaylist) {
                    {
                        playlistNameTarget = selectedUserPlaylist
                        showPlaylistNameDialog = true
                    }
                } else null,
                // Inside a playlist the summary shows just the type + source + count
                // (date/watch parts of the shared filter don't apply there).
                summaryOverride = if (feedIsUserPlaylist) {
                    val type = if (feedFilter.playlistType == PlaylistTypeFilter.ALL) ""
                    else " · ${feedFilter.playlistType.label}"
                    val src = when (feedFilter.source) {
                        FeedSourceFilter.ALL -> ""
                        FeedSourceFilter.BY_URL -> " · By URL"
                        FeedSourceFilter.BY_RSS -> " · By RSS"
                        FeedSourceFilter.SYSTEM -> " · $DEVICE_SOURCE_LABEL"
                    }
                    "All$type$src · $resultCount ${if (resultCount == 1) "item" else "items"}"
                } else null
            )
        }

        // ── Feed ───────────────────────────────────────────────────
        // Pull-to-refresh wraps the whole feed (including skeleton + empty
        // states — each is scrollable so the gesture always engages).
        PullToRefreshBox(
            isRefreshing = isLoading || playlistLoading,
            onRefresh = { refreshToken++ },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            when {
                // Downloads filter → the Downloads section (storage card +
                // searchable, per-channel offline audio list). Replaces the
                // feed entirely; search and the channel strip scope its list.
                downloadsFilter -> DownloadsSection(
                    channelId = filterChannelId,
                    channelName = channels.firstOrNull { it.channelId == filterChannelId }?.displayName,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    sourceFilter = downloadsSourceFilter,
                    onSourceFilterChange = { downloadsSourceFilter = it },
                    // Keep the header's "In playlists" count fresh when a
                    // download is added to a playlist from inside Downloads.
                    onPlaylistsChanged = { playlistRevision++ },
                    onPlayAudio = onPlayAudio
                )
                // Imported playlist: loading / error / empty states.
                feedIsPlaylist && playlistLoading && playlistVideos.isEmpty() -> SkeletonFeed()
                feedIsPlaylist && playlistError != null && playlistVideos.isEmpty() ->
                    ErrorCard(playlistError!!)
                feedIsPlaylist && playlistVideos.isEmpty() && !playlistLoading -> ErrorCard(
                    playlistError ?: "No videos in this playlist yet."
                )
                // User playlist: empty state (videos get added via Edit).
                feedIsUserPlaylist && userPlaylistVideos.isEmpty() ->
                    ErrorCard("This playlist is empty. Tap Edit to add videos.")
                // User playlist with items hidden by the Source / Type filters.
                feedIsUserPlaylist && userPlaylistVideos.isNotEmpty() && displayed.isEmpty() ->
                    ErrorCard("No videos match these filters.")
                // Imported playlist: empty. The feed filter never applies to a
                // playlist (it's a curated list that always shows every video),
                // so this is simply an empty playlist.
                feedIsPlaylist && displayed.isEmpty() ->
                    ErrorCard("No videos in this playlist yet.")
                // Playlist (user or imported) with a search that matches none.
                inPlaylistContext && isSearching && searchResults.isEmpty() ->
                    ErrorCard("No videos match your search.")
                // First load: no channels → nothing to load (and nothing to
                // refresh). Shown before the skeleton so a channel-less feed
                // doesn't shimmer forever. (Playlist contexts are handled by
                // their own branches above — these channel-feed states never
                // apply inside a playlist.)
                !inPlaylistContext && channels.isEmpty() && videos.isEmpty() && !isLoading ->
                    ErrorCard("No channels saved. Tap + Add to save one.")
                // First load with channels: shimmer placeholders while the
                // feeds fetch (never a blank screen).
                !inPlaylistContext && isLoading && videos.isEmpty() -> SkeletonFeed()
                !inPlaylistContext && errorMessage != null && videos.isEmpty() ->
                    ErrorCard(errorMessage!!)
                !inPlaylistContext && videos.isEmpty() && !isLoading ->
                    ErrorCard("No videos yet for your channels.")
                !inPlaylistContext && (videos.isNotEmpty() || channels.isNotEmpty()) &&
                    searchResults.isEmpty() && matchingChannels.isEmpty() && isSearching && !isLoading ->
                    ErrorCard("No videos or channels match your search.")
                // A selected channel with nothing to show says exactly that,
                // and points at the one thing that changes it. Whether the
                // provider is throttling is answered by the header's own
                // status line (see MediaSourceStatusStore), so this stays a
                // plain, short message rather than a paragraph of diagnosis.
                !inPlaylistContext && videos.isNotEmpty() && displayed.isEmpty() && !isLoading ->
                    ErrorCard(
                        when {
                            feedFilter.isActive -> "No videos match your filters."
                            selectedChannel != null ->
                                "No posts from this channel yet. Try another filter."
                            else -> "No videos for this channel yet. Try another filter."
                        }
                    )
                else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Matching channels in search results
                    if (matchingChannels.isNotEmpty() && isSearching) {
                        item(key = "matching-channels-header") {
                            SectionHeader(
                                title = "Channels",
                                isLoading = false,
                                showingCached = false
                            )
                        }
                        items(matchingChannels, key = { "match-${it.channelId}" }) { ch ->
                            SearchChannelCard(
                                channel = ch,
                                onClick = {
                                    filterChannelId = ch.channelId
                                    searchActive = false
                                    searchQuery = ""
                                }
                            )
                        }
                    }
                    // Section headers reflect whichever source loads the feed.
                    val feedLoading = if (feedIsPlaylist) playlistLoading else isLoading
                    val feedCached = !feedIsPlaylist && !feedIsUserPlaylist && showingCached
                    // ── Continue Watching: partially watched videos, in the
                    // current channel context (All Feed OR a selected channel).
                    // Auto-hides when none remain — and while a search is
                    // active, so results stay focused on the matches.
                    if (continueWatching.isNotEmpty() && !isSearching) {
                        item(key = "continue-header") {
                            SectionHeader(
                                title = "Continue Watching",
                                isLoading = false,
                                showingCached = false
                            )
                        }
                        item(key = "continue-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(continueWatching, key = { it.videoId }) { video ->
                                    ContinueWatchingCard(
                                        video = video,
                                        progressStore = progressStore,
                                        downloadStatus = AudioDownloads.statusFor(video.videoId),
                                        onClick = { playLong(video) },
                                        onDismiss = {
                                            progressStore.dismissFromContinueWatching(video.videoId)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    // ── Shorts: a horizontal rail of portrait cards, at the
                    // top of the feed where they have always been (YouTube
                    // Shorts only — an Instagram Reel is a normal video and
                    // belongs with the videos below).
                    if (shorts.isNotEmpty() && !feedIsUserPlaylist) {
                        item(key = "shorts-header") {
                            SectionHeader(
                                title = "Shorts",
                                isLoading = feedLoading,
                                showingCached = feedCached
                            )
                        }
                        item(key = "shorts-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(shorts, key = { it.videoId }) { video ->
                                    ShortCard(
                                        video = video,
                                        progressStore = progressStore,
                                        isManual = libraryStore.isManuallyAdded(video.videoId),
                                        downloadStatus = AudioDownloads.statusFor(video.videoId),
                                        isOffline = AudioDownloads.isDownloaded(video.videoId),
                                        onPlayOffline = { onPlayOffline(video) },
                                        onClick = { playShort(video) },
                                        onDownload = {
                                            AudioDownloads.download(video, AudioDownloads.sourceFor(video))
                                        },
                                        onCancelDownload = { AudioDownloads.cancel(video.videoId) },
                                        onDeleteDownload = { pendingDeleteDownload = video },
                                        onHide = {
                                            libraryStore.hideVideo(video)
                                            libraryRevision++
                                        },
                                        onRemoveManual = {
                                            libraryStore.removeManuallyAdded(video.videoId)
                                            libraryRevision++
                                        },
                                        onAddToPlaylist = { pendingAddToPlaylist = video },
                                        onHideChannel = if (filterChannelId == null) {
                                            {
                                                libraryStore.hideChannel(video.channelId)
                                                libraryRevision++
                                            }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                    // ── Videos: full-width rows, newest first. YouTube long
                    // videos lead, then Instagram Reels/videos and an X tweet
                    // that carries a clip — all in the SAME card, because a
                    // Reel IS a video here (same resume, same Continue Watching)
                    // and never a post.
                    if (longs.isNotEmpty() && !feedIsUserPlaylist) {
                        item(key = "videos-header") {
                            SectionHeader(
                                title = "Videos",
                                isLoading = feedLoading,
                                showingCached = feedCached
                            )
                        }
                        items(longs, key = { it.videoId }) { video ->
                            LongVideoCard(
                                video = video,
                                progressStore = progressStore,
                                isManual = libraryStore.isManuallyAdded(video.videoId),
                                downloadStatus = AudioDownloads.statusFor(video.videoId),
                                isOffline = AudioDownloads.isDownloaded(video.videoId),
                                onPlayOffline = { onPlayOffline(video) },
                                onClick = { playLong(video) },
                                onDownload = {
                                    AudioDownloads.download(video, AudioDownloads.sourceFor(video))
                                },
                                onCancelDownload = { AudioDownloads.cancel(video.videoId) },
                                onDeleteDownload = { pendingDeleteDownload = video },
                                onHide = {
                                    libraryStore.hideVideo(video)
                                    libraryRevision++
                                },
                                onHideChannel = if (filterChannelId == null) {
                                    {
                                        libraryStore.hideChannel(video.channelId)
                                        libraryRevision++
                                    }
                                } else null,
                                onRemoveManual = {
                                    libraryStore.removeManuallyAdded(video.videoId)
                                    libraryRevision++
                                },
                                onAddToPlaylist = { pendingAddToPlaylist = video }
                            )
                        }
                    }
                    // ── Posts: a horizontal rail of portrait cards, shaped
                    // exactly like the Shorts row above — an Instagram
                    // photo/carousel, or an X tweet with no clip attached.
                    // Tapping a card opens the reader with the post's FULL text.
                    if (posts.isNotEmpty() && !feedIsUserPlaylist) {
                        item(key = "posts-header") {
                            SectionHeader(
                                title = "Posts",
                                isLoading = feedLoading,
                                showingCached = feedCached
                            )
                        }
                        item(key = "posts-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(posts, key = { it.videoId }) { post ->
                                    PostCard(
                                        video = post,
                                        onClick = { expandedPost = post }
                                    )
                                }
                            }
                        }
                    }
                    // ── User playlist: the hand-picked ORDER matters, so every
                    // video renders as one ordered row list (no feed filters).
                    if (feedIsUserPlaylist && searchResults.isNotEmpty()) {
                        item(key = "playlist-videos-header") {
                            SectionHeader(
                                title = "Videos",
                                isLoading = false,
                                showingCached = false
                            )
                        }
                        // key: (videoId, audio flag) — a playlist can hold BOTH
                        // a video and its downloaded audio, and LazyColumn keys
                        // must stay unique.
                        items(
                            searchResults,
                            key = { it.videoId + if (it.isOfflineAudio) "#audio" else "#video" }
                        ) { video ->
                            // Audio entries — device imports (device- ids) or
                            // the downloaded audio of a YouTube video
                            // (isOfflineAudio) — are offline tracks, not YouTube
                            // videos: they render as the SHARED audio card and
                            // tapping plays the audio instead of opening the
                            // player. If that audio was deleted since the entry
                            // was added, fall back to the video card rather than
                            // a dead tap.
                            val isAudioEntry =
                                video.isOfflineAudio || video.videoId.startsWith("device-")
                            val audioItem = if (isAudioEntry) {
                                downloadedItems.firstOrNull { it.videoId == video.videoId }
                            } else {
                                null
                            }
                            if (audioItem != null) {
                                MiniAudioCard(
                                    item = audioItem,
                                    onPlay = { onPlayAudio(audioItem, playlistAudioQueue) },
                                    onAddToPlaylist = { pendingAddToPlaylist = video },
                                    onDelete = { pendingDeleteDownload = video }
                                )
                            } else {
                            LongVideoCard(
                                video = video,
                                progressStore = progressStore,
                                isManual = libraryStore.isManuallyAdded(video.videoId),
                                downloadStatus = AudioDownloads.statusFor(video.videoId),
                                isOffline = isAudioEntry || AudioDownloads.isDownloaded(video.videoId),
                                onPlayOffline = { onPlayOffline(video) },
                                onClick = {
                                    if (isAudioEntry && AudioDownloads.isDownloaded(video.videoId)) {
                                        onPlayOffline(video)
                                    } else {
                                        playLong(video)
                                    }
                                },
                                onDownload = {
                                    AudioDownloads.download(video, AudioDownloads.sourceFor(video))
                                },
                                onCancelDownload = { AudioDownloads.cancel(video.videoId) },
                                onDeleteDownload = { pendingDeleteDownload = video },
                                onHide = {
                                    libraryStore.hideVideo(video)
                                    libraryRevision++
                                },
                                onRemoveManual = {
                                    libraryStore.removeManuallyAdded(video.videoId)
                                    libraryRevision++
                                },
                                onAddToPlaylist = { pendingAddToPlaylist = video },
                                // A video shown in this playlist's feed is by
                                // definition in it — offer removing it straight
                                // from the card (the feed updates instantly).
                                onRemoveFromPlaylist = {
                                    selectedUserPlaylist?.let { p -> pendingVideoRemove = p to video }
                                },
                                // Feed-only ⋮ entries (Add to playlist…, Hide
                                // video) stay off inside the playlist.
                                inPlaylist = true
                            )
                            }
                        }
                    }
                }
            }
        }
        }
    }

    // ── All saved channels directory ───────────────────────────────
    if (showAllChannelsSheet) {
        ChannelDirectorySheet(
            channels = channels,
            query = channelDirectoryQuery,
            platform = channelDirectoryPlatform,
            unreadCounts = unreadChannelCounts,
            onQueryChange = { channelDirectoryQuery = it },
            onPlatformChange = { channelDirectoryPlatform = it },
            onSelect = { channel ->
                filterChannelId = channel.channelId
                showAllChannelsSheet = false
            },
            onRemove = { pendingRemove = it },
            // Mute / unmute ONE channel's notifications. Persisted on the
            // channel itself (so it survives a restart and applies to the
            // background worker), and muting also sweeps whatever that channel
            // already posted into the shade — silence has to be immediate, not
            // "from the next upload onwards".
            onToggleNotifications = { channel ->
                val muted = !channel.notificationsMuted
                repository.setChannelNotificationsMuted(channel.channelId, muted)
                    ?.let { channels = it }
                if (muted) {
                    MediaNotifier.cancelChannelNotifications(context, channel.channelId)
                }
            },
            onDismiss = { showAllChannelsSheet = false }
        )
    }

    // ── Add channel dialog ─────────────────────────────────────────
    if (showAddDialog) {
        AddChannelDialog(
            repository = repository,
            onAdded = { addedList ->
                showAddDialog = false
                channels = repository.getSavedChannels()
                filterChannelId = addedList.lastOrNull()?.channelId
                // A new channel must be fetched now, not at the next interval.
                refreshToken++
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // ── Filter bottom sheet ────────────────────────────────────────
    if (showFilterSheet) {
        FilterSheet(
            filter = feedFilter,
            // Inside a user playlist only the Source (By URL / From device / By RSS) and
            // Type (Video / Audio) filters apply — playlists keep their own
            // order, so the sheet shows just those two sections there.
            isPlaylistContext = feedIsUserPlaylist,
            onApply = { applied ->
                saveFilter(applied)
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false }
        )
    }

    // ── Hidden videos manager ──────────────────────────────────────
    // Lists only the current context's hidden videos (channel feed → that
    // channel's; All Feed → everything). "Unhide all" follows the same scope:
    // inside a channel it only unhides that channel's hidden videos.
    if (showHiddenDialog) {
        HiddenVideosDialog(
            libraryStore = libraryStore,
            hiddenVideos = contextHiddenVideos,
            onUnhideAll = {
                if (filterChannelId == null) {
                    libraryStore.unhideAll()
                } else {
                    contextHiddenVideos.forEach { libraryStore.unhideVideo(it.videoId) }
                }
                libraryRevision++
            },
            onChanged = { libraryRevision++ },
            onDismiss = { showHiddenDialog = false }
        )
    }

    // ── Hidden channels manager (All Feed only) ───────────────────
    if (showHiddenChannelsDialog) {
        HiddenChannelsDialog(
            channels = channels.filter { it.channelId in hiddenChannelIds },
            onUnhide = { channelId ->
                libraryStore.unhideChannel(channelId)
                libraryRevision++
            },
            onUnhideAll = {
                libraryStore.unhideAllChannels()
                libraryRevision++
            },
            onDismiss = { showHiddenChannelsDialog = false }
        )
    }

    // ── Add video by URL ───────────────────────────────────────────
    if (showAddVideoDialog) {
        AddVideoDialog(
            repository = repository,
            libraryStore = libraryStore,
            channel = channels.firstOrNull { it.channelId == filterChannelId },
            // While viewing one of your playlists, a by-URL video also lands
            // in that playlist — that's the context the menu item appears in.
            targetLabel = selectedUserPlaylist?.let { "your playlist \"${it.name}\"" },
            onResolved = { video ->
                selectedUserPlaylist?.let { p ->
                    userPlaylistStore.addVideos(p.id, listOf(video))
                    saveUserPlaylists()
                    Toast.makeText(context, "Added to ${p.name}", Toast.LENGTH_SHORT).show()
                }
            },
            onAdded = { libraryRevision++ },
            onDismiss = { showAddVideoDialog = false }
        )
    }

    // ── Add YouTube playlist by URL ────────────────────────────────
    if (showAddPlaylistDialog) {
        AddPlaylistDialog(
            repository = repository,
            onAdded = { playlist ->
                showAddPlaylistDialog = false
                playlists = repository.getSavedPlaylists()
                // Jump straight into the new playlist's feed.
                selectedPlaylistId = playlist.playlistId
                selectedUserPlaylistId = null
                filterChannelId = null
                playlistVideos = emptyList()
                playlistError = null
            },
            onDismiss = { showAddPlaylistDialog = false }
        )
    }

    // ── Remove playlist confirmation ───────────────────────────────
    pendingPlaylistRemove?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingPlaylistRemove = null },
            title = { Text("Remove playlist?") },
            text = { Text("Remove \"${playlist.title}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.removePlaylist(playlist.playlistId)
                    playlists = repository.getSavedPlaylists()
                    if (selectedPlaylistId == playlist.playlistId) {
                        selectedPlaylistId = null
                        playlistVideos = emptyList()
                        playlistError = null
                    }
                    pendingPlaylistRemove = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPlaylistRemove = null }) { Text("Cancel") }
            }
        )
    }

    // ── Remove video from playlist confirmation ─────────────────────
    pendingVideoRemove?.let { (playlist, video) ->
        AlertDialog(
            onDismissRequest = { pendingVideoRemove = null },
            title = { Text("Remove video?") },
            text = { Text("Remove \"${video.title}\" from \"${playlist.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    // Remove exactly the entry tapped (video OR its audio — a
                    // playlist can hold both, and removing one must never
                    // remove the other).
                    userPlaylistStore.removeVideo(playlist.id, video.videoId, video.isOfflineAudio)
                    saveUserPlaylists()
                    pendingVideoRemove = null
                    Toast.makeText(
                        context,
                        "Removed from ${playlist.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingVideoRemove = null }) { Text("Cancel") }
            }
        )
    }

    // ── Playlists manager (user-created + imported by URL) ─────────
    // Opened from the Playlists entry beside "All". Both kinds of playlists
    // live here: create a local one, import a YouTube one by URL, open or
    // remove either.
    if (showPlaylistsSheet) {
        PlaylistsSheet(
            userPlaylists = userPlaylists,
            repository = repository,
            importedPlaylists = playlists,
            onOpen = { p ->
                showPlaylistsSheet = false
                selectedUserPlaylistId = p.id
                selectedPlaylistId = null
                // Keep the tab context (All Feed vs the selected channel): the
                // playlist's Edit → Add videos picker then suggests the videos
                // of whichever tab you opened the playlist from. Closing the
                // playlist (✕) also lands you right back on that tab.
            },
            onCreate = {
                showPlaylistsSheet = false
                playlistNameTarget = null
                showPlaylistNameDialog = true
            },
            onRename = { p ->
                playlistNameTarget = p
                showPlaylistNameDialog = true
            },
            onDelete = { p -> pendingUserPlaylistDelete = p },
            // "Add playlist by URL": imports a YouTube playlist (same dialog
            // as the imported-playlist strip's + chip).
            onAddByUrl = {
                showPlaylistsSheet = false
                showAddPlaylistDialog = true
            },
            onOpenImported = { p ->
                showPlaylistsSheet = false
                selectedPlaylistId = p.playlistId
                selectedUserPlaylistId = null
                filterChannelId = null
                playlistVideos = emptyList()
                playlistError = null
            },
            onRemoveImported = { p ->
                showPlaylistsSheet = false
                pendingPlaylistRemove = p
            },
            onDismiss = { showPlaylistsSheet = false }
        )
    }

    // ── Delete user playlist confirmation ──────────────────────────
    pendingUserPlaylistDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingUserPlaylistDelete = null },
            title = { Text("Delete playlist?") },
            text = { Text("Delete \"${playlist.name}\"? This removes it and all its videos.") },
            confirmButton = {
                TextButton(onClick = {
                    userPlaylistStore.deletePlaylist(playlist.id)
                    if (selectedUserPlaylistId == playlist.id) selectedUserPlaylistId = null
                    saveUserPlaylists()
                    pendingUserPlaylistDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUserPlaylistDelete = null }) { Text("Cancel") }
            }
        )
    }

    // ── Playlist name dialog (create / rename) ─────────────────────
    if (showPlaylistNameDialog) {
        PlaylistNameDialog(
            initial = playlistNameTarget?.name ?: "",
            title = if (playlistNameTarget == null) "New playlist" else "Rename playlist",
            confirmLabel = if (playlistNameTarget == null) "Create" else "Rename",
            onSubmit = { name ->
                if (playlistNameTarget == null) {
                    // Creating fresh — optionally seeded with the video the
                    // user picked from a card's ⋮ menu.
                    val seed = pendingCreateWithVideo?.let { listOf(it) } ?: emptyList()
                    userPlaylistStore.createPlaylist(name, seed)
                    if (pendingCreateWithVideo != null) {
                        Toast.makeText(
                            context,
                            "Created \"$name\" with this video",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    userPlaylistStore.renamePlaylist(playlistNameTarget!!.id, name)
                }
                pendingCreateWithVideo = null
                showPlaylistNameDialog = false
                saveUserPlaylists()
            },
            onDismiss = {
                pendingCreateWithVideo = null
                showPlaylistNameDialog = false
            }
        )
    }

    // ── Playlist editor (rename / reorder / remove / add videos) ────
    if (showPlaylistEditor && selectedUserPlaylist != null) {
        PlaylistEditorSheet(
            playlist = selectedUserPlaylist!!,
            onRename = {
                // Close the editor, then open the name dialog for this playlist.
                showPlaylistEditor = false
                playlistNameTarget = selectedUserPlaylist
                showPlaylistNameDialog = true
            },
            onMove = { from, to ->
                userPlaylistStore.moveVideo(selectedUserPlaylist!!.id, from, to)
                saveUserPlaylists()
            },
            onRemove = { video ->
                selectedUserPlaylist?.let { p -> pendingVideoRemove = p to video }
            },
            onAddVideos = { showAddVideosPicker = true },
            onDismiss = { showPlaylistEditor = false }
        )
    }

    // ── Add-videos picker for the open user playlist ───────────────
    // The suggested candidates follow the tab the playlist was opened from:
    // All Feed → every video; a channel tab → just that channel's videos.
    val addVideosCandidates = remember(visibleVideos, filterChannelId) {
        if (filterChannelId == null) visibleVideos
        else visibleVideos.filter { it.channelId == filterChannelId }
    }
    if (showAddVideosPicker && selectedUserPlaylist != null) {
        PlaylistAddVideosSheet(
            candidates = addVideosCandidates,
            // Only VIDEO entries block a candidate — the playlist may hold
            // this video's downloaded AUDIO (isOfflineAudio) without the video
            // itself, and the picker must still offer the video.
            alreadyIn = selectedUserPlaylist!!.videos
                .filterNot { it.isOfflineAudio }
                .map { it.videoId }
                .toSet(),
            onAdd = { videos ->
                userPlaylistStore.addVideos(selectedUserPlaylist!!.id, videos)
                saveUserPlaylists()
                showAddVideosPicker = false
            },
            onDismiss = { showAddVideosPicker = false }
        )
    }

    // ── A post expanded from the feed (tapping a post card) ─────────
    expandedPost?.let { post ->
        // The reader pages LEFT/RIGHT through the same list the grid rendered,
        // so "next" is the next tile the user would have tapped — no going back
        // to the grid for each post. The list is snapshotted per open, so hiding
        // a post never renumbers the pages under the user's finger.
        val readerPosts = remember(post) {
            posts.ifEmpty { listOf(post) }
        }
        val initialIndex = readerPosts.indexOfFirst { it.videoId == post.videoId }
            .takeIf { it >= 0 } ?: 0
        PostViewer(
            posts = readerPosts,
            initialIndex = initialIndex,
            progressStore = progressStore,
            onHide = { hidden ->
                libraryStore.hideVideo(hidden)
                libraryRevision++
            },
            onDismiss = { expandedPost = null }
        )
    }

    // ── Add-to-playlist picker (from a card's ⋮ menu) ──────────────
    pendingAddToPlaylist?.let { video ->
        AddToPlaylistSheet(
            video = video,
            playlists = userPlaylists,
            onAdd = { playlist ->
                userPlaylistStore.addVideos(playlist.id, listOf(video))
                saveUserPlaylists()
                Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
                pendingAddToPlaylist = null
            },
            onCreateNew = {
                // Route through the name dialog so the new playlist gets a
                // real name (seeded with this video on submit).
                pendingCreateWithVideo = video
                pendingAddToPlaylist = null
                playlistNameTarget = null
                showPlaylistNameDialog = true
            },
            onDismiss = { pendingAddToPlaylist = null }
        )
    }

    // ── Remove channel confirmation ────────────────────────────────
    pendingRemove?.let { channel ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove channel?") },
            text = { Text("Remove \"${channel.displayName}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.removeChannel(channel.channelId)
                    channels = repository.getSavedChannels()
                    if (filterChannelId == channel.channelId) filterChannelId = null
                    // Re-merge the feed so the removed channel's videos leave it.
                    refreshToken++
                    pendingRemove = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            }
        )
    }

    // ── Delete offline audio confirmation (⋮ menu → Delete download) ──
    pendingDeleteDownload?.let { video ->
        AlertDialog(
            onDismissRequest = { pendingDeleteDownload = null },
            title = { Text("Delete download?") },
            text = { Text("Delete the offline audio of \"${video.title}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    AudioDownloads.delete(video.videoId)
                    pendingDeleteDownload = null
                    Toast.makeText(context, "Download deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteDownload = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    isLoading: Boolean,
    showingCached: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Accent bar — the app's brand color, echoing the player's progress accent.
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        if (showingCached && !isLoading) {
            Text(
                text = "cached",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "All" avatar at the head of the strip — shows every channel's feed. */
@Composable
private fun AllAvatar(selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "All",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "All",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A saved channel: circular avatar (or initials) with its name below. The
 * small ✕ badge in the avatar's corner removes the channel (the inner
 * clickable consumes the tap, so it never also triggers the filter).
 */
@Composable
private fun ChannelAvatar(
    channel: SavedChannel,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.size(52.dp)) {
            val isInstagram = channel.platform == MediaPlatform.INSTAGRAM
            val isX = channel.platform == MediaPlatform.X
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(
                        width = if (selected) 2.dp else if (isInstagram || isX) 1.5.dp else 0.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                                else if (isInstagram) Color(0xFFE1306C)
                                else if (isX) Color.Black
                                else Color.Transparent,
                        shape = CircleShape
                    )
            ) {
                if (channel.avatarUrl != null) {
                    RemoteImage(
                        url = channel.avatarUrl,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials(channel.displayName),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // Remove badge (✕) in the corner.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove ${channel.displayName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(11.dp)
                )
            }
            // Silenced badge, diagonally OPPOSITE the ✕ so "notifications off"
            // and "unsubscribed" can never be mistaken for each other. A muted
            // channel still refreshes and still appears in the feed — only its
            // notifications are silenced — so the state has to be visible right
            // on the avatar, without opening "View all".
            if (channel.notificationsMuted) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.NotificationsOff,
                        contentDescription = "Notifications muted for ${channel.displayName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = channel.displayName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchChannelCard(
    channel: SavedChannel,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isInstagram = channel.platform == MediaPlatform.INSTAGRAM
            val isX = channel.platform == MediaPlatform.X
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (isInstagram || isX) 1.5.dp else 0.dp,
                        color = if (isInstagram) Color(0xFFE1306C)
                        else if (isX) Color.Black else Color.Transparent,
                        shape = CircleShape
                    )
            ) {
                if (channel.avatarUrl != null) {
                    RemoteImage(
                        url = channel.avatarUrl,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials(channel.displayName),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when (channel.platform) {
                        MediaPlatform.INSTAGRAM -> "Instagram Profile"
                        MediaPlatform.X -> "X Profile"
                        MediaPlatform.YOUTUBE -> "YouTube Channel"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open channel",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Playlists entry in the strip (next to "All") — opens the My Playlists
 * manager. A colorful gradient makes it stand out from the channel avatars.
 */
@Composable
private fun PlaylistsAvatar(selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF8E24AA), Color(0xFFEC407A))
                    )
                )
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = "Playlists",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Playlists",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * "Haramayn Live" shortcut at the start of the channel strip: opens the
 * Makkah &amp; Madinah in-app live broadcasts (the functionality that used to
 * live in the bottom Live tab).
 */
@Composable
private fun HaramaynLiveAvatar(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF1B5E20), Color(0xFF43A047)))
                )
                .border(width = 2.dp, color = MaterialTheme.colorScheme.primary, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.LiveTv,
                contentDescription = stringResource(R.string.haramayn_live),
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.haramayn_live_short),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** Opens the full saved-channel directory. */
@Composable
private fun ViewAllChannelsChip(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "View all channels", modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("View all", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "+" entry at the end of the strip — opens the add-channel dialog. */
@Composable
private fun AddAvatar(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add channel",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Add",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Vertical Shorts card for the horizontal Shorts rail — the title and view
 * count are overlaid on the thumbnail over a bottom scrim (YouTube Shorts
 * style), with the overflow menu in the corner.
 */
@Composable
private fun ShortCard(
    video: MediaVideo,
    progressStore: WatchProgressStore,
    isManual: Boolean,
    downloadStatus: DownloadStatus?,
    isOffline: Boolean,
    /** Plays the downloaded audio (card ⋮ menu) — non-null only when offline. */
    onPlayOffline: (() -> Unit)? = null,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onHide: () -> Unit,
    onRemoveManual: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    onHideChannel: (() -> Unit)? = null
) {
    val fraction = remember(video.videoId) { progressStore.get(video.videoId) }
    // A live broadcast has no finite duration to complete — never show a
    // "Watched" badge or progress bar on it, even if stale progress was saved
    // by an older build that misclassified it as a bounded video.
    val watched = !video.isLive && (fraction ?: 0f) >= 0.9f
    val live = video.isLive
    Card(
        onClick = onClick,
        modifier = Modifier.width(158.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
        ) {
            RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
            // Bottom scrim keeps the overlay text legible on any thumbnail.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to Color.Black.copy(alpha = 0.35f),
                            1f to Color.Black.copy(alpha = 0.82f)
                        )
                    )
            )
            // Watch status: watched dims the card, partial shows a percentage
            // pill and a thin progress bar.
            if (watched) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                )
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "Watched",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            } else if (downloadStatus != null) {
                DownloadThumbOverlay(status = downloadStatus)
            } else if (fraction != null && fraction > 0.02f && !live) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = RoundedCornerShape(5.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            // Overflow menu (top-right): add to playlist / download / hide /
            // hide channel / remove manual.
            VideoCardMenu(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                isManual = isManual,
                downloadLabel = downloadMenuLabel(downloadStatus, isOffline),
                onDownloadAction = downloadMenuAction(
                    downloadStatus, isOffline, onDownload, onCancelDownload, onDeleteDownload
                ),
                onPlayOffline = if (isOffline) onPlayOffline else null,
                onHide = onHide,
                onRemoveManual = onRemoveManual,
                onAddToPlaylist = onAddToPlaylist,
                onHideChannel = onHideChannel
            )
            // Bottom-end stack: status pills + duration pill.
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isOffline) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                if (isManual) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "Manually added",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                DurationBadge(seconds = video.durationSeconds)
            }
            // Title + views overlaid at the bottom.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .padding(end = 10.dp)
            ) {
                Text(
                    text = video.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (video.viewCount > 0L) {
                        formatViews(video.viewCount) + " views"
                    } else {
                        video.channelName.ifBlank { "YouTube" }
                    },
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Width of one Posts-rail card. A little wider than a Shorts card: a post is
 * mostly its words, and four lines of text in a 158 dp column is cramped.
 */
private val POST_CARD_WIDTH = 186.dp

/**
 * One card in the Posts rail: the SAME portrait card the Shorts row uses, so a
 * photo or a text post sits in the feed as a piece of media rather than as a
 * tiny square tile. A post with a picture shows it; a post with none (an X
 * tweet of text only) shows its own text over the scrim, so it never renders as
 * an empty square. Tapping it opens the post's FULL text in [PostViewer].
 */
@Composable
private fun PostCard(
    video: MediaVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(POST_CARD_WIDTH),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
        ) {
            if (video.thumbnailUrl.isNotBlank()) {
                RemoteImage(
                    url = video.thumbnailUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // No picture at all: a soft, platform-tinted backdrop keeps the
                // card intentional and gives the post's own text a readable
                // surface. Deliberately NOT the music-note fallback — a text
                // post is not audio.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    platformAccent(video.platform).copy(alpha = 0.35f),
                                    MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        )
                )
            }
            // Bottom scrim keeps the overlay text legible on any picture.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.40f),
                            1f to Color.Black.copy(alpha = 0.85f)
                        )
                    )
            )
            // The source chip: this rail mixes Instagram posts and X posts, so
            // every card says which one it came from.
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                shape = RoundedCornerShape(5.dp),
                color = Color.Black.copy(alpha = 0.55f)
            ) {
                Text(
                    text = platformBadgeLabel(video.platform),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            // Carousel posts get the stacked-squares badge, like Instagram's grid.
            if (video.instagramType == InstagramMediaType.CAROUSEL) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    Icon(
                        Icons.Filled.Collections,
                        contentDescription = "Carousel",
                        tint = Color.White,
                        modifier = Modifier.padding(2.dp).size(14.dp)
                    )
                }
            }
            // The post's own words (the short card label for a picture post)
            // and its author, over the scrim — the same treatment as the Shorts
            // cards above. A post with NO picture has nothing for the words to
            // sit under, so they start from the middle of the card instead of
            // hanging off the bottom edge with empty space above them.
            Column(
                modifier = Modifier
                    .align(if (video.thumbnailUrl.isNotBlank()) Alignment.BottomStart else Alignment.Center)
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                Text(
                    text = video.bodyText.ifBlank { video.title },
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = video.channelName.ifBlank { platformBadgeLabel(video.platform) },
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


/** Source name for a card badge. */
private fun platformBadgeLabel(platform: MediaPlatform): String = when (platform) {
    MediaPlatform.YOUTUBE -> "YouTube"
    MediaPlatform.INSTAGRAM -> "Instagram"
    MediaPlatform.X -> "X"
}

/** The source's own brand colour, used to tint poster-less card backdrops. */
private fun platformAccent(platform: MediaPlatform): Color = when (platform) {
    MediaPlatform.YOUTUBE -> Color(0xFFD93025)
    MediaPlatform.INSTAGRAM -> Color(0xFFE1306C)
    MediaPlatform.X -> Color(0xFF1D9BF0)
}

/**
 * What a card shows when the item itself has no poster.
 *
 * The music note is reserved for entries that really ARE audio — a downloaded
 * track, a device import. Using it as the generic "no thumbnail" art put a
 * music icon behind a text post or a poster-less clip, which claims the item is
 * an audio file when it is not. Everything else gets a neutral, platform-tinted
 * backdrop with the source name instead.
 */
@Composable
private fun StillFallback(video: MediaVideo, modifier: Modifier = Modifier) {
    val isAudio = video.isOfflineAudio || video.videoId.startsWith("device-")
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    platformAccent(video.platform).copy(alpha = 0.28f),
                    MaterialTheme.colorScheme.surfaceVariant
                )
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        if (isAudio) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
        } else {
            Text(
                text = platformBadgeLabel(video.platform),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/** "3 hours ago" style label, empty when the time is unknown. */
private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
}

/**
 * YouTube-style long-video row: thumbnail with a red watch-progress bar and a
 * "Watched" badge, title + channel/date to the right.
 */
@Composable
private fun LongVideoCard(
    video: MediaVideo,
    progressStore: WatchProgressStore,
    isManual: Boolean,
    downloadStatus: DownloadStatus?,
    isOffline: Boolean,
    /** Plays the downloaded audio (card ⋮ menu) — non-null only when offline. */
    onPlayOffline: (() -> Unit)? = null,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onHide: () -> Unit,
    onRemoveManual: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    onHideChannel: (() -> Unit)? = null,
    /** When set (user-playlist feed), the ⋮ menu offers removing from it. */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** Inside a playlist the ⋮ menu drops the feed-only entries (Add to
     *  playlist…, Hide video) — the playlist's own Remove is already shown. */
    inPlaylist: Boolean = false
) {
    val fraction = remember(video.videoId) { progressStore.get(video.videoId) }
    // A live broadcast has no finite duration to complete — never show a
    // "Watched" badge or progress bar on it, even if stale progress was saved
    // by an older build that misclassified it as a bounded video.
    val watched = !video.isLive && (fraction ?: 0f) >= 0.9f
    val live = video.isLive

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row {
            // ── Thumbnail + progress overlay ──
            Box(
                modifier = Modifier
                    .width(176.dp)
                    .aspectRatio(16f / 9f)
            ) {
                if (video.thumbnailUrl.isBlank()) {
                    StillFallback(video, Modifier.fillMaxSize())
                } else {
                    RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
                }
                // Watched treatment: dim the whole thumbnail FIRST, so every
                // badge (LIVE, menu, duration) stays bright on top — matching
                // the ShortCard ordering.
                if (watched) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                    )
                }
                // Live indicator on the thumbnail.
                if (live) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFFD93025)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color.White, CircleShape)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "LIVE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
                // Overflow menu (add to playlist / download / hide / remove
                // manual / remove from playlist).
                VideoCardMenu(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    isManual = isManual,
                    downloadLabel = downloadMenuLabel(downloadStatus, isOffline),
                    onDownloadAction = downloadMenuAction(
                        downloadStatus, isOffline, onDownload, onCancelDownload, onDeleteDownload
                    ),
                    onPlayOffline = if (isOffline) onPlayOffline else null,
                    onHide = onHide,
                    onRemoveManual = onRemoveManual,
                    onAddToPlaylist = onAddToPlaylist,
                    onHideChannel = onHideChannel,
                    onRemoveFromPlaylist = onRemoveFromPlaylist,

                    inPlaylist = inPlaylist
                )
            // Live download progress renders on the thumbnail itself (a
            // status pill + bottom progress bar); the ⋮ menu keeps the
            // Cancel / Retry action. Bottom-end stack: status pills +
            // duration pill. Everything sits above the download/watch bar.
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isOffline) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                if (isManual) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "Manually added",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                DurationBadge(seconds = video.durationSeconds)
            }
                if (watched) {
                    // Watched badge (the dim is already applied above).
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "Watched",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                } else if (downloadStatus != null) {
                    DownloadThumbOverlay(status = downloadStatus)
                } else if (fraction != null && fraction > 0.02f && !live) {
                    // In-progress: YouTube-style thin progress bar + a small
                    // "NN%" pill (top-right) so the watched amount is visible
                    // at a glance.
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = Color.Black.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            // ── Title / channel · views · date ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp)
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append(video.channelName.ifBlank { "YouTube" })
                        if (video.viewCount > 0L) {
                            append(" · ").append(formatViews(video.viewCount)).append(" views")
                        }
                        if (video.publishedAtEpochMillis > 0L) {
                            append(" · ").append(
                                DateUtils.getRelativeTimeSpanString(
                                    video.publishedAtEpochMillis,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS
                                ).toString()
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The post reader, opened by tapping a tile in the Posts grid: a full-screen,
 * LEFT/RIGHT pager over the very list the grid rendered, each page a scrollable
 * view of one post's media, creator, FULL text and date, plus its actions
 * (mark watched / unwatched, hide post). Works for every source — X tweets and
 * Instagram photos/carousels both land here.
 *
 * Paging (swipe or the ‹ › buttons) is why a reader can now go from one post
 * straight to the next instead of closing back to the grid, tapping, and
 * scrolling to find where they were.
 */
@Composable
private fun PostViewer(
    posts: List<MediaVideo>,
    initialIndex: Int,
    progressStore: WatchProgressStore,
    onHide: (MediaVideo) -> Unit,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (posts.size - 1).coerceAtLeast(0)),
        pageCount = { posts.size }
    )
    val scope = rememberCoroutineScope()
    val current = posts.getOrNull(pagerState.currentPage) ?: posts.first()
    val hasPrevious = pagerState.currentPage > 0
    val hasNext = pagerState.currentPage < posts.lastIndex

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close post")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = current.channelName.ifBlank { "Post" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Where the reader is in the grid — the swipe is only
                        // discoverable if the count is visible.
                        Text(
                            text = "${pagerState.currentPage + 1} of ${posts.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Explicit ‹ › as well as the swipe: on a post that is mostly
                    // one long image it is not obvious that the reader is a pager.
                    IconButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                        enabled = hasPrevious
                    ) {
                        Icon(
                            Icons.Filled.ChevronLeft,
                            contentDescription = "Previous post",
                            tint = if (hasPrevious) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            }
                        )
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        enabled = hasNext
                    ) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = "Next post",
                            tint = if (hasNext) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            }
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { page ->
                    val post = posts.getOrNull(page) ?: return@HorizontalPager
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        PostView(
                            video = post,
                            onClick = {},
                            onHide = { onHide(post) },
                            progressStore = progressStore
                        )
                    }
                }
            }
        }
    }
}

/**
 * The post's own card (its media on top, creator / FULL text / date underneath,
 * with the ⋮ actions: mark watched / unwatched, hide post). Rendered inside
 * [PostViewer].
 */
@Composable
private fun PostView(
    video: MediaVideo,
    onClick: () -> Unit,
    onHide: () -> Unit,
    progressStore: WatchProgressStore,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val watchRev by WatchProgressStore.revisionFlow.collectAsState()
    val isWatched = remember(video.videoId, watchRev) {
        progressStore.isWatched(video.videoId)
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Instagram media is square; an X attachment keeps its
                    // 16:9 shape instead of being letterboxed into a square.
                    .aspectRatio(if (video.isInstagram) 1f else 16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (video.thumbnailUrl.isNotBlank()) {
                    RemoteImage(
                        url = video.thumbnailUrl,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    StillFallback(video, Modifier.fillMaxSize())
                }

                if (isWatched) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                    )
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "Watched",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                } else {
                    val typeLabel = when (video.instagramType) {
                        InstagramMediaType.REEL -> "Reel"
                        InstagramMediaType.VIDEO -> "Video"
                        InstagramMediaType.CAROUSEL -> "Carousel"
                        else -> if (video.isShort) "Reel" else "Post"
                    }
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = Color.Black.copy(alpha = 0.65f)
                    ) {
                        Text(
                            text = typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                var showMenu by remember { mutableStateOf(false) }
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Surface(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable { showMenu = true },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.45f)
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Post options",
                            tint = Color.White,
                            modifier = Modifier.padding(5.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isWatched) "Mark as unwatched" else "Mark as watched") },
                            onClick = {
                                showMenu = false
                                if (isWatched) {
                                    progressStore.remove(video.videoId)
                                } else {
                                    progressStore.set(video.videoId, 1.0f)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Hide post") },
                            onClick = {
                                showMenu = false
                                onHide()
                            }
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = video.channelName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // The WHOLE post, never cut off at the card's label: `title` is
                // only the short label a feed row shows, so a post used to be
                // readable up to those lines and no further.
                val fullText = video.bodyText.ifBlank { video.title }
                if (fullText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = fullText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (video.publishedAtEpochMillis > 0L) {
                    Spacer(Modifier.height(4.dp))
                    val relTime = remember(video.publishedAtEpochMillis) {
                        DateUtils.getRelativeTimeSpanString(
                            video.publishedAtEpochMillis,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE
                        ).toString()
                    }
                    Text(
                        text = relTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * The All Feed heading row: title + filter button (highlighted when active) +
 * an overflow menu (Hidden videos manager, Add video by URL) + active summary
 * and Reset.
 */
@Composable
private fun FeedHeader(
    /** The feed title: "All Feed", a playlist's title, or the search label. */
    title: String = "All Feed",
    filter: FeedFilter,
    resultCount: Int,
    hiddenCount: Int,
    /** Number of currently hidden channels, shown in the ⋮ menu (§6). */
    hiddenChannelCount: Int = 0,
    canAddVideo: Boolean,
    /** Shows a pencil icon to open the user-playlist editor. */
    canEditPlaylist: Boolean = false,
    /** Inside a playlist the ⋮ menu drops the feed-only entries (Hidden
     *  videos, My playlists, Add playlist by URL) — those belong to the feed. */
    isPlaylistContext: Boolean = false,
    /** When false (an imported YouTube playlist) the filter button and the
     *  active-filter summary are hidden — a playlist always shows every one
     *  of its videos, so there is nothing to filter (or reset). */
    filterEnabled: Boolean = true,
    searchActive: Boolean,
    searchQuery: String,
    /** When false the search toggle is hidden (e.g. the Downloads view, which
     *  owns its own search field over the offline audios). */
    searchEnabled: Boolean = true,
    /** "Updated Xm ago" text shown under the title row (null = hide). */
    updatedAgo: String? = null,
    /**
     * Why a provider behind this feed is not answering (null = none), e.g.
     * "X is rate-limiting this device — retrying in 8m". Shown under the
     * summary so an empty feed always has an explanation.
     */
    sourceStatus: String? = null,
    /** When set, an ✕ appears that exits the current playlist context. */
    onClose: (() -> Unit)? = null,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenFilter: () -> Unit,
    onReset: () -> Unit,
    onOpenHidden: () -> Unit,
    /** Empties Continue Watching from the ⋮ menu (confirmed first). */
    onResetContinueWatching: () -> Unit = {},
    onOpenHiddenChannels: () -> Unit = {},
    /** Opens the channel directory — the way into channels when the strip is folded away. */
    onOpenChannels: () -> Unit = {},
    showHiddenChannelsManager: Boolean = true,
    onAddVideo: () -> Unit,
    onAddPlaylist: (() -> Unit)? = null,
    onOpenMyPlaylists: (() -> Unit)? = null,
    onEditPlaylist: (() -> Unit)? = null,
    /** When set (user-playlist view), the ⋮ menu offers "Add from device". */
    onAddFromSystem: (() -> Unit)? = null,
    /** When set (user-playlist view), the ⋮ menu offers "Rename playlist". */
    onRenamePlaylist: (() -> Unit)? = null,
    /** When set, replaces the whole summary line (playlists show source only). */
    summaryOverride: String? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    var showResetContinueWatching by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    // Autofocus the search field the moment search mode opens.
    val searchFocus = remember { FocusRequester() }
    if (searchEnabled) {
        LaunchedEffect(searchActive) {
            if (searchActive) {
                searchFocus.requestFocus()
            } else {
                // The field leaves composition when search closes — dismiss the
                // keyboard too, otherwise it lingers over the feed.
                keyboardController?.hide()
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Accent bar — echoes the SectionHeader brand mark.
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (searchActive) "Search" else title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Edit playlist (user playlist context only).
            if (canEditPlaylist && onEditPlaylist != null) {
                IconButton(
                    onClick = onEditPlaylist,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit playlist",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            // Search toggle: a magnifier normally, an ✕ to close in search mode
            // (closing also clears the query via onToggleSearch). Hidden in the
            // Downloads view, which owns its own search field.
            if (searchEnabled) {
                IconButton(
                    onClick = onToggleSearch,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searchActive) "Close search" else "Search feed",
                        tint = if (searchActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More feed options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                        // Feed-only actions — hidden inside a playlist, where they
                    // don't apply (the ✕ button already exits the playlist).
                    if (!isPlaylistContext) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (hiddenCount > 0) "Hidden videos ($hiddenCount)"
                                    else "Hidden videos"
                                )
                            },
                            onClick = {
                                showMenu = false
                                onOpenHidden()
                            }
                        )
                        if (showHiddenChannelsManager) {
                            DropdownMenuItem(
                                // The count is read from the live hidden-channel
                                // collection, so it updates the moment a channel
                                // is hidden or unhidden (§6).
                                text = { Text("Hidden channels ($hiddenChannelCount)") },
                                onClick = {
                                    showMenu = false
                                    onOpenHiddenChannels()
                                }
                            )
                        }
                        // The strip's own "View all", in the menu: folding the
                        // strip away must never strand the channel directory.
                        DropdownMenuItem(
                            text = { Text("All channels") },
                            onClick = {
                                showMenu = false
                                onOpenChannels()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("My playlists") },
                            enabled = onOpenMyPlaylists != null,
                            onClick = {
                                showMenu = false
                                onOpenMyPlaylists?.invoke()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add playlist by URL") },
                            enabled = onAddPlaylist != null,
                            onClick = {
                                showMenu = false
                                onAddPlaylist?.invoke()
                            }
                        )
                    }
                    // Offered in every context — a playlist included. Continue
                    // Watching is fed by whatever you watch anywhere, so the
                    // menu item that empties it must not depend on where you
                    // happen to be standing. It asks first: this throws away
                    // resume positions, and a ⋮ menu is one tap from any card.
                    DropdownMenuItem(
                        text = { Text("Reset Continue Watching") },
                        onClick = {
                            showMenu = false
                            showResetContinueWatching = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add video by URL") },
                        enabled = canAddVideo,
                        onClick = {
                            showMenu = false
                            onAddVideo()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add from device") },
                        enabled = onAddFromSystem != null,
                        onClick = {
                            showMenu = false
                            onAddFromSystem?.invoke()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename playlist") },
                        enabled = onRenamePlaylist != null,
                        onClick = {
                            showMenu = false
                            onRenamePlaylist?.invoke()
                        }
                    )
                }
            }
            // Reset Continue Watching asks first: a ⋮ menu is one careless tap
            // from any card, and this throws away resume positions.
            if (showResetContinueWatching) {
                ResetContinueWatchingDialog(
                    onConfirm = {
                        showResetContinueWatching = false
                        onResetContinueWatching()
                    },
                    onDismiss = { showResetContinueWatching = false }
                )
            }
            if (filterEnabled) {
                IconButton(
                    onClick = onOpenFilter,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.FilterList,
                        contentDescription = "Filter feed",
                        tint = if (filter.isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            // Exit the playlist context (imported or user playlist).
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close playlist",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        // Search field — shown only while search mode is active.
        if (searchActive) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocus),
                placeholder = { Text("Search videos & channels") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
            )
            if (searchQuery.isNotBlank()) {
                Text(
                    text = "$resultCount result${if (resultCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }
        }
        if (filterEnabled && (filter.isActive || summaryOverride != null)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = summaryOverride ?: feedFilterSummary(filter, resultCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onReset,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Reset", style = MaterialTheme.typography.labelMedium)
                }
            }
        } else if (updatedAgo != null && !searchActive) {
            // "Updated Xm ago" — shown in place of the filter summary when no
            // filter is active, so the feed's freshness is always visible.
            Text(
                text = updatedAgo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
        if (sourceStatus != null && !searchActive) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = sourceStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}


/**
 * Continue Watching card for the horizontal row: thumbnail with a progress
 * bar + the resume position ("12:34 / 42:10") so the user knows exactly where
 * they left off.
 */
@Composable
private fun ContinueWatchingCard(
    video: MediaVideo,
    progressStore: WatchProgressStore,
    downloadStatus: DownloadStatus? = null,
    onClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val watchRev by WatchProgressStore.revisionFlow.collectAsState()
    val progress = remember(video.videoId, watchRev) { progressStore.getProgress(video.videoId) }
    var showMenu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.width(210.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Continue Watching options",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Remove from Continue Watching") },
                            onClick = {
                                showMenu = false
                                onDismiss()
                            }
                        )
                    }
                }
                if (downloadStatus != null) {
                    // A re-download in flight: the thumbnail shows the live
                    // download pill + bar instead of the watch progress.
                    DownloadThumbOverlay(status = downloadStatus)
                } else {
                    val fraction = (progress?.fraction ?: 0f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = Color.Black.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = formatResumeLabel(progress?.positionSeconds, progress?.durationSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = video.channelName.ifBlank { "YouTube" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** "12:34 / 42:10", or "Resume" when the duration isn't known yet. */
private fun formatResumeLabel(positionSeconds: Long?, durationSeconds: Long?): String {
    val pos = positionSeconds ?: return "Resume"
    val dur = durationSeconds ?: return "Resume"
    if (dur <= 0L) return "Resume"
    return "${formatClock(pos)} / ${formatClock(dur)}"
}

/**
 * Live download progress overlaid on a feed thumbnail while a download is in
 * flight: a status pill (spinner + "Preparing…" / "NN%") in the top corner
 * and a bottom progress bar — determinate while bytes flow, an infinite
 * sweep while preparing or the total size is unknown. BoxScope so both parts
 * can align within the thumbnail's Box.
 */
@Composable
private fun BoxScope.DownloadThumbOverlay(
    status: DownloadStatus
) {
    // Only an in-flight download gets the thumbnail animation — a failed one
    // is surfaced by the ⋮ menu's Retry action instead of an endless sweep.
    if (status !is DownloadStatus.Preparing && status !is DownloadStatus.Downloading) return
    val known = status is DownloadStatus.Downloading && status.progress >= 0f
    val fraction = (status as? DownloadStatus.Downloading)?.progress?.coerceIn(0f, 1f) ?: 0f
    // Estimated time remaining ("~2m 30s left") once the downloader has
    // enough history to estimate it — rendered as its own small pill BELOW the
    // % pill (so the % pill stays compact on narrow thumbnails).
    val etaText = formatEtaRemaining((status as? DownloadStatus.Downloading)?.etaSeconds ?: -1L)
    val label = when {
        status is DownloadStatus.Preparing -> "Preparing…"
        known -> "${(fraction * 100).toInt()}%"
        else -> "Downloading…"
    }

    Column(
        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(5.dp),
            color = Color.Black.copy(alpha = 0.65f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
        if (etaText.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = Color.Black.copy(alpha = 0.65f)
            ) {
                Text(
                    text = etaText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 168.dp)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }

    // Bottom progress bar: real fraction while downloading, an animated sweep
    // while preparing (or when the total size is unknown).
    val sweep = rememberInfiniteTransition(label = "download-sweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "download-sweep-value"
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(3.dp)
            .background(Color.Black.copy(alpha = 0.45f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (known) fraction else sweep.value)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * YouTube-style duration pill for card thumbnails — "12:34" (or
 * "1:02:34" past an hour). Hidden entirely when the duration isn't known
 * (live broadcasts, or a video that hasn't been enriched yet).
 */
@Composable
private fun DurationBadge(
    seconds: Long,
    modifier: Modifier = Modifier
) {
    if (seconds <= 0L) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(5.dp),
        color = Color.Black.copy(alpha = 0.7f)
    ) {
        Text(
            text = formatClock(seconds),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** "12:34", or "1:02:34" past an hour. */
private fun formatClock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/**
 * Overflow menu on feed cards: Add to playlist, Play audio offline (when the
 * audio is downloaded), Download audio, Hide video, and (for manually added
 * videos) Remove. In a user-playlist feed a "Remove from playlist" entry is
 * offered too. Tapping any entry does NOT trigger the card's own onClick (the
 * inner clickable consumes the tap).
 */
@Composable
private fun VideoCardMenu(
    modifier: Modifier = Modifier,
    isManual: Boolean,
    downloadLabel: String? = null,
    onDownloadAction: (() -> Unit)? = null,
    /** When set (the video's audio is downloaded), the menu offers "Play audio offline". */
    onPlayOffline: (() -> Unit)? = null,
    onHide: () -> Unit,
    onRemoveManual: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onHideChannel: (() -> Unit)? = null,
    /** When set (a user-playlist feed), the menu also offers removing from it. */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** Inside a playlist the ⋮ menu drops the feed-only entries (Add to
     *  playlist…, Hide video) — the playlist's own Remove is already shown. */
    inPlaylist: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable { showMenu = true },
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.45f)
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Video options",
                tint = Color.White,
                modifier = Modifier.padding(5.dp)
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            if (!inPlaylist && onAddToPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("Add to playlist…") },
                    onClick = {
                        showMenu = false
                        onAddToPlaylist()
                    }
                )
            }
            if (onRemoveFromPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    onClick = {
                        showMenu = false
                        onRemoveFromPlaylist()
                    }
                )
            }
            if (onPlayOffline != null) {
                DropdownMenuItem(
                    text = { Text("Play audio offline") },
                    onClick = {
                        showMenu = false
                        onPlayOffline()
                    }
                )
            }
            if (downloadLabel != null && onDownloadAction != null) {
                DropdownMenuItem(
                    text = { Text(downloadLabel) },
                    onClick = {
                        showMenu = false
                        onDownloadAction()
                    }
                )
            }
            if (isManual) {
                DropdownMenuItem(
                    text = { Text("Remove (manually added)") },
                    onClick = {
                        showMenu = false
                        onRemoveManual()
                    }
                )
            }
            if (!inPlaylist) {
                DropdownMenuItem(
                    text = { Text("Hide video") },
                    onClick = {
                        showMenu = false
                        onHide()
                    }
                )
                if (onHideChannel != null) {
                    DropdownMenuItem(
                        text = { Text("Hide channel") },
                        onClick = {
                            showMenu = false
                            onHideChannel()
                        }
                    )
                }
            }
        }
    }
}

/** The ⋮ menu label for a card's download entry, by state. */
internal fun downloadMenuLabel(status: DownloadStatus?, isOffline: Boolean): String = when {
    isOffline -> "Delete download"
    status == null -> "Download audio"
    status is DownloadStatus.Preparing || status is DownloadStatus.Downloading -> "Cancel download"
    else -> "Retry download"
}

/** The ⋮ menu action for a card's download entry, by state. */
internal fun downloadMenuAction(
    status: DownloadStatus?,
    isOffline: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
): (() -> Unit)? = when {
    isOffline -> onDelete
    status == null -> onDownload
    status is DownloadStatus.Preparing || status is DownloadStatus.Downloading -> onCancel
    else -> onDownload
}

@Composable
private fun AddChannelDialog(
    repository: MediaRepository,
    onAdded: (List<SavedChannel>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf(MediaPlatform.YOUTUBE) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Add channel / profile") },
        text = {
            Column {
                Text(
                    text = "Choose a platform, then paste its @handle or URL. YouTube, Instagram and X are supported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = platform == MediaPlatform.YOUTUBE,
                        onClick = { platform = MediaPlatform.YOUTUBE; error = null },
                        label = { Text("YouTube") }
                    )
                    FilterChip(
                        selected = platform == MediaPlatform.INSTAGRAM,
                        onClick = { platform = MediaPlatform.INSTAGRAM; error = null },
                        label = { Text("Instagram") }
                    )
                    FilterChip(
                        selected = platform == MediaPlatform.X,
                        onClick = { platform = MediaPlatform.X; error = null },
                        label = { Text("X") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    singleLine = true,
                    enabled = !adding,
                    isError = error != null,
                    label = { Text("Handle or URL") }
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !adding,
                onClick = {
                    adding = true
                    scope.launch {
                        when (val result = repository.addChannel(input, platform)) {
                            is MediaRepository.AddChannelResult.Success -> onAdded(result.channels)
                            is MediaRepository.AddChannelResult.Error -> {
                                error = result.message
                                adding = false
                            }
                        }
                    }
                }
            ) { Text(if (adding) "Adding…" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !adding) { Text("Cancel") }
        }
    )
}

/**
 * Hidden-videos manager: lists the hidden videos of the current context (a
 * single channel, or every channel in the All Feed) with an Unhide action,
 * plus an "Unhide all" escape hatch (scoped to the shown list). Hidden
 * videos stay out of every feed until unhidden here.
 */
@Composable
private fun HiddenChannelsDialog(
    channels: List<SavedChannel>,
    onUnhide: (String) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden channels") },
        text = {
            if (channels.isEmpty()) {
                Text("No hidden channels. Channels hidden from All Feed remain available in the channel row.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(channels, key = { it.channelId }) { channel ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = channel.displayName,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = { onUnhide(channel.channelId) }) {
                                Text("Unhide")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (channels.isNotEmpty()) TextButton(onClick = onUnhideAll) { Text("Unhide all") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun HiddenVideosDialog(
    libraryStore: MediaLibraryStore,
    hiddenVideos: List<MediaVideo>,
    onUnhideAll: () -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden videos") },
        text = {
            if (hiddenVideos.isEmpty()) {
                Text(
                    text = "No hidden videos. When you hide a video it will be listed here so you can bring it back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(hiddenVideos, key = { it.videoId }) { video ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 64.dp, height = 36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                ) {
                                    RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = video.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    libraryStore.unhideVideo(video.videoId)
                                    onChanged()
                                }) { Text("Unhide") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (hiddenVideos.isNotEmpty()) {
                TextButton(onClick = {
                    onUnhideAll()
                }) { Text("Unhide all") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// ── Skeleton loaders ──────────────────────────────────────────────────
// Shimmer placeholders shown during the first feed load (never a blank
// screen). The layout mirrors the real feed: a section header, a row of
// vertical Shorts cards, another header, then long-video rows.

@Composable
private fun SkeletonFeed() {
    val brush = rememberShimmerBrush()
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        // The skeleton is a scrollable surface so pull-to-refresh can engage
        // even while the first load is still in flight.
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "sk-header-shorts") {
            SkeletonSectionHeader(brush = brush)
        }
        item(key = "sk-row-shorts") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(3) {
                    SkeletonBlock(
                        brush = brush,
                        modifier = Modifier
                            .width(150.dp)
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(16.dp))
                    )
                }
            }
        }
        item(key = "sk-header-videos") {
            SkeletonSectionHeader(brush = brush)
        }
        items(4) {
            SkeletonVideoRow(brush = brush)
        }
    }
}

/** Shimmer section-title placeholder (accent bar + text bar). */
@Composable
private fun SkeletonSectionHeader(brush: Brush) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkeletonBlock(
            brush = brush,
            modifier = Modifier
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        SkeletonBlock(
            brush = brush,
            modifier = Modifier
                .width(120.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    }
}

/** Shimmer long-video row: thumbnail block + two text lines. */
@Composable
private fun SkeletonVideoRow(brush: Brush) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SkeletonBlock(
            brush = brush,
            modifier = Modifier
                .width(160.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            SkeletonBlock(
                brush = brush,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(8.dp))
            SkeletonBlock(
                brush = brush,
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }
}

/** One shimmer placeholder block (animated sweeping gradient). */
@Composable
private fun SkeletonBlock(
    brush: Brush,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(brush))
}

/**
 * A single shared animated shimmer brush (one infinite transition for the
 * whole skeleton, passed down to every block so they sweep in unison).
 */
@Composable
private fun rememberShimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val highlight = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    val transition = rememberInfiniteTransition(label = "feedShimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "feedShimmerX"
    )
    // Brush offsets are in PIXELS of the drawn block (not normalized), so the
    // sweep band must be sized in real pixels. A ~300px highlight band moving
    // across a ~1000px sweep travels over every block width used in the
    // skeleton (150dp shorts cards ≈ 450px @3x density, 160dp thumbnails ≈
    // 480px). Blocks narrower than the band still get a visible shimmer.
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x * 500f - 200f, 0f),
        end = Offset(x * 500f + 100f, 0f)
    )
}

@Composable
private fun ErrorCard(message: String) {
    // Scrollable (even though the card fits) so the pull-to-refresh gesture
    // can engage from an empty / error state — a stuck feed is exactly when
    // the user reaches for a manual refresh.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📡", fontSize = 36.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * "Add video by URL": pastes a YouTube URL, resolves it via the channel's
 * feed or oEmbed, and saves it as a manually added video in the library
 * (marked "Manually added", never treated as new, never notified).
 */
@Composable
private fun AddVideoDialog(
    repository: MediaRepository,
    libraryStore: MediaLibraryStore,
    channel: SavedChannel?,
    /** Where the video will go, e.g. "your playlist \"X\"" — drives the copy. */
    targetLabel: String? = null,
    /** Fired with the resolved video once it's been added to the library. */
    onResolved: (MediaVideo) -> Unit = {},
    onAdded: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Add video by URL") },
        text = {
            Column {
                Text(
                    text = "Paste a YouTube link. It will be added to " +
                        (targetLabel ?: channel?.displayName ?: "your feed") +
                        " as a manually added video.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; status = null },
                    singleLine = true,
                    enabled = !adding,
                    isError = status?.startsWith("Couldn't") == true,
                    label = { Text("YouTube URL") }
                )
                if (status != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = status!!,
                        color = if (status!!.startsWith("Couldn't")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !adding,
                onClick = {
                    adding = true
                    status = null
                    scope.launch {
                        status = when (val result = repository.resolveVideoByUrl(input, channel)) {
                            is MediaRepository.ResolveVideoResult.Success -> {
                                if (libraryStore.addManuallyAdded(result.video)) {
                                    onResolved(result.video)
                                    onAdded()
                                    onDismiss()
                                    null
                                } else {
                                    "That video is already in your library."
                                }
                            }
                            is MediaRepository.ResolveVideoResult.AlreadyExists ->
                                "That video is already in this channel's feed."
                            is MediaRepository.ResolveVideoResult.Error -> result.message
                        }
                        adding = false
                    }
                }
            ) { Text(if (adding) "Adding…" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !adding) { Text("Cancel") }
        }
    )
}

/** Two-letter initials for the avatar fallback ("Safina Society" → "SS", "@maherzain" → "M"). */
private fun initials(name: String): String {
    val clean = name.removePrefix("@").trim()
    val parts = clean.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    return parts
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifBlank { clean.take(1).uppercase() }
}

// ══════════════════════════════════════════════════════════════════════
//  Imported YouTube playlists (added by URL)
// ══════════════════════════════════════════════════════════════════════

/**
 * "Add playlist by URL": pastes a YouTube playlist link, resolves + fetches
 * all its videos, and saves it. Invalid URLs, private playlists and network
 * failures each show a friendly inline error instead of failing silently.
 */
@Composable
private fun AddPlaylistDialog(
    repository: MediaRepository,
    onAdded: (SavedPlaylist) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val isError = status != null &&
        (status!!.startsWith("Couldn't") ||
            status!!.startsWith("This playlist") ||
            status!!.startsWith("That") ||
            status!!.startsWith("Enter"))

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Add playlist by URL") },
        text = {
            Column {
                Text(
                    text = "Paste a YouTube playlist link. All its videos will be fetched and shown like a normal feed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; status = null },
                    singleLine = true,
                    enabled = !adding,
                    isError = isError,
                    label = { Text("Playlist URL or id") }
                )
                if (status != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = status!!,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !adding,
                onClick = {
                    adding = true
                    status = null
                    scope.launch {
                        status = when (val result = repository.addPlaylist(input)) {
                            is MediaRepository.AddPlaylistResult.Success -> {
                                onAdded(result.playlist)
                                null
                            }
                            is MediaRepository.AddPlaylistResult.Error -> result.message
                        }
                        adding = false
                    }
                }
            ) { Text(if (adding) "Adding…" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !adding) { Text("Cancel") }
        }
    )
}

// ══════════════════════════════════════════════════════════════════════
//  User-created playlists (local library)
// ══════════════════════════════════════════════════════════════════════

/**
 * The "Playlists" manager opened from the Playlists entry beside "All":
 * create a local playlist, ADD A YOUTUBE PLAYLIST BY URL, and open / rename /
 * delete either kind. User playlists are the replacement for the removed
 * Bookmark feature — a curated, locally-saved list in an order the user
 * controls; imported playlists mirror a YouTube playlist wholesale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistsSheet(
    userPlaylists: List<UserPlaylist>,
    repository: MediaRepository,
    importedPlaylists: List<SavedPlaylist>,
    onOpen: (UserPlaylist) -> Unit,
    onCreate: () -> Unit,
    onRename: (UserPlaylist) -> Unit,
    onDelete: (UserPlaylist) -> Unit,
    /** Opens the "Add playlist by URL" dialog (imports a YouTube playlist). */
    onAddByUrl: () -> Unit,
    onOpenImported: (SavedPlaylist) -> Unit,
    onRemoveImported: (SavedPlaylist) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Playlists",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            // Create a LOCAL playlist — the primary action.
            item {
                Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New playlist")
                }
            }
            // Import a YouTube playlist by URL — the second way to add one.
            item {
                OutlinedButton(onClick = onAddByUrl, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Add playlist by URL")
                }
            }
            if (importedPlaylists.isNotEmpty()) {
                item {
                    Text(
                        text = "Imported from YouTube",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(importedPlaylists, key = { it.playlistId }) { p ->
                    ImportedPlaylistRow(
                        playlist = p,
                        videoCount = repository.getCachedPlaylistVideos(p.playlistId)
                            ?.first?.size ?: 0,
                        onOpen = { onOpenImported(p) },
                        onRemove = { onRemoveImported(p) }
                    )
                }
            }
            item {
                Text(
                    text = "My playlists",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (userPlaylists.isEmpty()) {
                item {
                    Text(
                        text = "No playlists yet. Create one to curate your own video list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                items(userPlaylists, key = { it.id }) { p ->
                    UserPlaylistRow(
                        playlist = p,
                        onOpen = { onOpen(p) },
                        onRename = { onRename(p) },
                        onDelete = { onDelete(p) }
                    )
                }
            }
        }
    }
}

/** One imported YouTube playlist row: title + count, tap to open, ✕ to remove. */
@Composable
private fun ImportedPlaylistRow(
    playlist: SavedPlaylist,
    videoCount: Int,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (videoCount > 0) "$videoCount videos" else "Loading…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove ${playlist.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** One playlist row in the manager: leading thumbnail, name + count, rename / delete. */
@Composable
private fun UserPlaylistRow(
    playlist: UserPlaylist,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    // NOTE: deliberately NOT Card(onClick = onOpen). A clickable Card swallowed
    // the ⋮ beside it — the row opened the playlist instead of the menu — so the
    // open target is the content area only, and the menu is its sibling.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Open playlist") { onOpen() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    val first = playlist.videos.firstOrNull()
                    if (first != null) {
                        RemoteImage(url = first.thumbnailUrl, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistPlay,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${playlist.videos.size} video${if (playlist.videos.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // ONE overflow per row instead of a pencil + a bin repeated down
            // the list: with several playlists those two icons were the same
            // glyph over and over, which read as visual noise. Rename and
            // Delete now live behind this single ⋮ (their taps are consumed by
            // the menu, so the row's own click still just opens the playlist).
            Box {
                var menuOpen by remember(playlist.id) { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Playlist options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuOpen = false; onRename() },
                        leadingIcon = {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

/** Create / rename dialog for a user playlist (blank names are rejected). */
@Composable
internal fun PlaylistNameDialog(
    initial: String,
    title: String,
    confirmLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text("Playlist name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (name.isNotBlank()) {
                        onSubmit(name)
                        keyboard?.hide()
                    } }
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSubmit(name) },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * The playlist editor: every video in order with move-up / move-down / remove
 * controls, plus an "Add videos" button that opens the picker. All edits save
 * locally through the store and re-render instantly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistEditorSheet(
    playlist: UserPlaylist,
    /** Opens the rename dialog for this playlist. */
    onRename: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (video: MediaVideo) -> Unit,
    onAddVideos: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Title + rename pencil: the playlist name, with an edit action
            // right next to it so renaming is reachable from the editor itself.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Rename playlist",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${playlist.videos.size} video${if (playlist.videos.size == 1) "" else "s"} · drag the grip to reorder",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (playlist.videos.isEmpty()) {
                Text(
                    text = "No videos yet. Tap Add videos to fill this playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PLAYLIST_ROW_GAP)
                ) {
                    // key: (videoId, audio flag) — a playlist can hold BOTH a
                    // video and its downloaded audio; LazyColumn keys must be
                    // unique, so the two same-id entries get distinct keys.
                    itemsIndexed(
                        playlist.videos,
                        key = { _, v -> v.videoId + if (v.isOfflineAudio) "#audio" else "#video" }
                    ) { index, video ->
                        PlaylistEditorRow(
                            video = video,
                            index = index,
                            count = playlist.videos.size,
                            onMove = onMove,
                            onRemove = { onRemove(video) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAddVideos,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add videos")
            }
        }
    }
}

/**
 * One ordered row in the playlist editor: thumbnail, title, drag grip + remove.
 *
 * Reordering is direct manipulation rather than two arrow buttons: dragging
 * the grip lifts the row off the list (it follows the finger, drawn above its
 * neighbours) and dropping it commits a single move to the slot the finger is
 * over. The move is applied on drop, not continuously, so the row is never
 * translated and re-laid-out in the same frame it is being dragged — that
 * keeps the finger-to-row mapping exact and makes the drop deterministic.
 */
@Composable
private fun PlaylistEditorRow(
    video: MediaVideo,
    index: Int,
    count: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: () -> Unit
) {
    // The index this row *currently* occupies (a swap above it shifts it).
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)

    var dragging by remember { mutableStateOf(false) }
    // Measured, not assumed: rows are two-line titles at some font scales.
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    // Index the row was at when the drag started — the "from" of the move.
    var startIndex by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    // Rows are spaced by ROW_GAP in the LazyColumn, so one slot is the row
    // height plus that gap; the finger must cover a whole slot to move one step.
    val slotPx = rowHeightPx + with(density) { PLAYLIST_ROW_GAP.toPx() }

    // The grip is the gesture target and it must NOT move while dragging:
    // translating the node that reads the pointer would feed the translation
    // back into the next drag delta. The translation lives on the inner Surface
    // instead, so deltas stay exactly 1:1 with the finger.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .pointerInput(video.videoId, video.isOfflineAudio) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        startIndex = currentIndex
                        dragPx = 0f
                    },
                    onDragEnd = {
                        val last = currentCount - 1
                        val target = if (slotPx > 0f) {
                            (startIndex + (dragPx / slotPx).roundToInt()).coerceIn(0, last)
                        } else startIndex
                        dragging = false
                        dragPx = 0f
                        if (target != startIndex) onMove(startIndex, target)
                    },
                    onDragCancel = {
                        dragging = false
                        dragPx = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (slotPx > 0f) {
                            // Keep the row inside the list: it cannot be dragged
                            // above the first or below the last slot.
                            val down = (currentCount - 1 - startIndex) * slotPx
                            val up = -startIndex * slotPx
                            dragPx = (dragPx + amount.y).coerceIn(up, down)
                        }
                    }
                )
            }
    ) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (dragging) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shadowElevation = if (dragging) 8.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { if (rowHeightPx == 0f) rowHeightPx = it.height.toFloat() }
            .graphicsLayer { translationY = dragPx }
    ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Audio entries (downloaded audio / device imports) get a
                        // small note so "audio of X" is distinguishable from
                        // "video X" — a playlist can hold both.
                        if (video.isOfflineAudio || video.videoId.startsWith("device-")) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = "${index + 1} · ${video.channelName.ifBlank { "YouTube" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // The grip is the only reorder control left: the arrow pair it
                // replaced needed one tap per position and gave each row two
                // more small tap targets.
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = "Drag to reorder",
                        tint = if (dragging) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove from playlist",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Multi-select picker of candidate videos to add into the open playlist — the
 * candidates follow the tab context (All Feed → every video, a channel tab →
 * that channel's). Only videos not already in the playlist are listed; already
 * present ones can never be duplicated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistAddVideosSheet(
    candidates: List<MediaVideo>,
    alreadyIn: Set<String>,
    onAdd: (List<MediaVideo>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val available = remember(candidates, alreadyIn) {
        candidates.filterNot { it.videoId in alreadyIn }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Add videos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${available.size} video${if (available.size == 1) "" else "s"} available · ${selectedIds.size} selected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (available.isEmpty()) {
                Text(
                    text = "Nothing left to add — every known video is already in this playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(available, key = { it.videoId }) { video ->
                        val isSelected = video.videoId in selectedIds
                        Surface(
                            onClick = {
                                selectedIds = if (isSelected) selectedIds - video.videoId
                                else selectedIds + video.videoId
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                ) {
                                    RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = video.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = video.channelName.ifBlank { "YouTube" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else Color.Transparent
                                        )
                                        .border(
                                            1.5.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(6.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onAdd(available.filter { it.videoId in selectedIds }) },
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Add ${selectedIds.size} video${if (selectedIds.size == 1) "" else "s"}"
                )
            }
        }
    }
}

/**
 * "Add to playlist…" picker opened from a video card's ⋮ menu (or the video
 * player's ⋮ / Playlist button): tap a playlist to append the video (deduped —
 * it can't be added twice), or create a new playlist seeded with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    video: MediaVideo,
    playlists: List<UserPlaylist>,
    onAdd: (UserPlaylist) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
    /** Sheet heading — "Add to playlist" for a video, "Add audio to
     *  playlist" when the entry is downloaded audio (isOfflineAudio). */
    title: String = "Add to playlist",
    /** Label of the create-new button, mirroring [title]. */
    newLabel: String = "New playlist with this video"
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            if (playlists.isEmpty()) {
                Text(
                    text = "No playlists yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // heightIn keeps the list bounded inside the sheet (a plain
                // LazyColumn must never measure against infinite height).
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 380.dp)
                ) {
                    items(playlists, key = { it.id }) { p ->
                        Surface(
                            onClick = { onAdd(p) },
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.PlaylistPlay,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = p.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${p.videos.size} video${if (p.videos.size == 1) "" else "s"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCreateNew,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(newLabel)
            }
        }
    }
}
