package com.muddassir.clearview.media.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.text.format.DateUtils
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import com.muddassir.clearview.R
import com.muddassir.clearview.media.data.MediaLibraryStore
import com.muddassir.clearview.media.data.UserPlaylistStore
import com.muddassir.clearview.media.data.VideoProgress
import com.muddassir.clearview.media.data.WatchProgressStore
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadStatus
import com.muddassir.clearview.media.model.InstagramMediaType
import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.UserPlaylist
import com.muddassir.clearview.media.playback.AudioPlayback
import com.muddassir.clearview.media.util.formatBytes
import com.muddassir.clearview.media.util.formatEtaRemaining
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * In-app video player built on the YouTube IFrame Player API.
 *
 * RENDERING: the WebView's video surface must never be covered by a sibling.
 * The player + its overlays live inside their OWN box that exactly matches the
 * video area (16:9 at the top in portrait, full screen in landscape); the
 * details panel sits BELOW that box in portrait, never overlapping it. The
 * player occupies the same composition slot in both orientations so it
 * survives rotation (playback continues).
 *
 * The UI reflects the player's REAL state from the IFrame API: a buffering
 * indicator while the video loads, the actual error card (by IFrame error
 * code) when playback fails, and a gentle hint when autoplay was blocked.
 *
 * Portrait shows a dedicated control panel BELOW the video (title, Continue
 * Watching / Watch Again, Share, Speed, Add to playlist, Hide, Mark as
 * watched, Download audio) — the video itself stays uncluttered. Vertical
 * fullscreen is a Shorts-style viewer: swipe up/down to navigate the
 * [shortsQueue] (when it has more than one item), exit via the on-screen
 * button or back.
 */
@Composable
fun VideoPlayerScreen(
    video: MediaVideo,
    isLandscape: Boolean,
    fullscreenVertical: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    /** Called after the user hides this video (the player should close). */
    onExit: () -> Unit = {},
    /** Plays the downloaded audio instead of the video (podcast-style). */
    onPlayOffline: () -> Unit = {},
    /** Ordered Shorts list for the vertical viewer (empty for long videos). */
    shortsQueue: List<MediaVideo> = emptyList(),
    /** Index of [video] within [shortsQueue], or -1. */
    shortsIndex: Int = -1,
    /** Swipe navigation: +1 = next Short, -1 = previous. */
    onNavigateShorts: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    // All player state is keyed on the current videoId so it resets
    // SYNCHRONOUSLY the moment the source changes (opening a video, swiping
    // through Shorts). The blurred loading placeholder is gated on this state:
    // a stale PLAYING/sourceStarted carried over from the previous video would
    // leave a 1-frame gap where the embed's own grey play button flashes while
    // the next video loads. Keying on the videoId closes that gap.
    var playerState by remember(video.videoId) { mutableStateOf(YtState.UNSTARTED) }
    var errorCode by remember(video.videoId) { mutableStateOf<Int?>(null) }
    var timedOut by remember(video.videoId) { mutableStateOf(false) }
    var retryToken by remember { mutableStateOf(0) }
    // Whether the current source has EVER started PLAYING (not merely become
    // ready): see onPlayerState — a ready-but-paused source (autoplay blocked)
    // keeps this false so the blurred placeholder stays up for Shorts. The
    // placeholder shows only until that first start — buffering caused by a
    // forward/backward seek must NOT re-blur the video.
    var sourceStarted by remember(video.videoId) { mutableStateOf(false) }

    // ── Blurred-loading backdrop (smooth video transitions) ─────────
    // While a NEW source loads (opening a video, swiping through Shorts), the
    // loading placeholder must never flash blank. It shows the blurred
    // thumbnail of the video we JUST LEFT — already in the in-memory cache,
    // so it renders instantly — until the new video's own thumbnail has
    // loaded, then crossfades to it. The thumbnails are tracked manually
    // (SideEffect runs after every recomposition): when [video] changes,
    // lastThumbnailUrl still holds the OLD video's thumbnail, so it is
    // promoted to previousThumbnailUrl before the new URL takes over.
    var lastThumbnailUrl by remember { mutableStateOf(video.thumbnailUrl) }
    var previousThumbnailUrl by remember { mutableStateOf<String?>(null) }
    // True once THIS video's thumbnail bitmap is ready (or already cached).
    // Reset on every video change so the previous thumbnail leads the way.
    var currentThumbnailReady by remember(video.videoId) { mutableStateOf(false) }
    SideEffect {
        if (lastThumbnailUrl != video.thumbnailUrl) {
            previousThumbnailUrl = lastThumbnailUrl
            lastThumbnailUrl = video.thumbnailUrl
        }
    }
    // The backdrop URL to show: the new thumbnail once it's loaded, otherwise
    // the previous video's (cached → instant, no blank frame).
    val placeholderThumbnail = if (currentThumbnailReady) video.thumbnailUrl
        else previousThumbnailUrl ?: video.thumbnailUrl
    // Prefetch the NEW video's thumbnail while the previous one is on screen:
    // the placeholder only ever DISPLAYS one URL at a time, so without this
    // warm-up the new thumbnail would never load and the backdrop could never
    // crossfade to it (it would show the previous video's blur until playback
    // starts). Warm the shared cache so the crossfade fires the moment the new
    // thumb is ready. A failed fetch just keeps the previous blurred backdrop.
    LaunchedEffect(video.videoId, video.thumbnailUrl) {
        val loaded = if (video.thumbnailUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                ThumbnailCache.get(context, video.thumbnailUrl, BACKDROP_WIDTH_PX) != null
            }
        } else {
            false
        }
        if (loaded) currentThumbnailReady = true
    }
    // Prefetch the NEIGHBORING Shorts' thumbnails while the current one is on
    // screen, so swiping to the next/previous short lands on an already-warm
    // cache: the blurred backdrop crossfades to that poster the instant the
    // source changes instead of waiting on a fresh network fetch. A failed
    // fetch is harmless — the previous video's blur stays up until playback
    // starts. Re-keys on every swipe (and on a queue swap, so the new pair is
    // always the one warmed).
    LaunchedEffect(video.videoId, shortsQueue) {
        if (shortsIndex !in shortsQueue.indices) return@LaunchedEffect
        val neighbors = buildList {
            if (shortsIndex + 1 in shortsQueue.indices) add(shortsQueue[shortsIndex + 1])
            if (shortsIndex - 1 >= 0) add(shortsQueue[shortsIndex - 1])
        }
        withContext(Dispatchers.IO) {
            neighbors.forEach { n ->
                if (n.thumbnailUrl.isNotBlank()) {
                    ThumbnailCache.get(context, n.thumbnailUrl, BACKDROP_WIDTH_PX)
                }
            }
        }
    }
    // The "Open in YouTube app" option appears only after an in-app Retry has
    // already failed (per spec: "if the error persists after retrying once").
    var hasRetried by remember { mutableStateOf(false) }

    // Watch progress: the player reports the real playback position (every
    // ~5 s while playing + on pause/end); we persist position + duration so
    // the Media tab can show progress bars, a "Watched" badge, and Continue
    // Watching can resume from the exact position.
    val progressStore = remember { WatchProgressStore(context.applicationContext) }
    val libraryStore = remember { MediaLibraryStore(context.applicationContext) }
    // Offline audio: initialize once, then observe this video's download state
    // (snapshot state — the panel recomposes when the download progresses).
    LaunchedEffect(Unit) { AudioDownloads.initialize(context.applicationContext) }
    val downloadStatus = AudioDownloads.statusFor(video.videoId)
    val isOffline = AudioDownloads.isDownloaded(video.videoId)
    // A manually added video can be removed from the library right here — the
    // feed cards offer it too, so the player's ⋮ menu should match. Cached per
    // video: the player recomposes rapidly during playback (progress ticks),
    // and the lookup decodes the library prefs each time.
    val isManual = remember(video.videoId) { libraryStore.isManuallyAdded(video.videoId) }
    var confirmRemoveManual by remember(video.videoId) { mutableStateOf(false) }
    var lastProgressSavedAt by remember { mutableStateOf(0L) }
    // Runtime live signal: the IFrame API reports a NON-finite duration
    // (Infinity) for a live broadcast, and the JS bridge only forwards
    // progress when the duration is finite. Some live streams report the
    // stream's ELAPSED time as a finite, growing duration instead — those are
    // caught by the monotonic-growth check in onProgress. sawFiniteDuration
    // therefore only means "a bounded duration was seen at least once"; the
    // reliable live guards are the onLive bridge signal + duration growth +
    // sawPartialPlayback. `video.isLive` (thumbnail heuristic) is only a hint.
    var sawFiniteDuration by remember(video.videoId) { mutableStateOf(false) }
    // Runtime live signal from the JS bridge: the page reports an infinite
    // duration for a live broadcast (see youtube_player.html). This is the
    // RELIABLE live detection — `video.isLive` (thumbnail heuristic) rarely
    // fires for RSS feeds, so a live stream from a saved channel would
    // otherwise pass every non-live guard below and get marked watched the
    // moment the user leaves the player.
    var isLiveRuntime by remember(video.videoId) { mutableStateOf(false) }
    // Combined live state: the runtime bridge signal OR the feed's hint.
    val isLiveNow = video.isLive || isLiveRuntime
    // Duration-growth live detection: a NORMAL video reports the SAME fixed
    // duration on every progress tick, but a live broadcast that reports the
    // stream's ELAPSED time as its "duration" grows it on every tick (e.g.
    // 100s → 105s → 110s). Monotonic growth across two consecutive reports is
    // the runtime signature of a live stream that never reports the Infinity
    // the onLive bridge is keyed on — catching it here keeps such streams out
    // of watched/continue state.
    var lastReportedDuration by remember(video.videoId) { mutableDoubleStateOf(0.0) }
    // Consecutive reports where the duration GREW. Reset on any non-growing
    // report, so an in-stream ad (a one-off duration switch on a normal video)
    // can never accumulate to the live threshold.
    var growingReports by remember(video.videoId) { mutableIntStateOf(0) }
    // True once this source showed a genuinely PARTIAL position (< 98% of a
    // stable duration). A bounded video always passes through partial
    // positions while playing; an elapsed-time live stream reads ≈100% from
    // its very first report — so a near-complete fraction is only trusted
    // once this is set. This is what blocks the fast-exit path (a live stream
    // exited within seconds of opening must not be marked watched).
    var sawPartialPlayback by remember(video.videoId) { mutableStateOf(false) }
    // Bumped when progress is persisted / marked watched so the control panel
    // reflects the latest watch state (e.g. Continue Watching → Watch Again).
    var progressRevision by remember { mutableIntStateOf(0) }

    // One-shot transition into live for THIS source: purge any stale progress
    // (persisted by older builds that misclassified live streams as watched)
    // and pin isLiveRuntime so every non-live guard below switches off. The
    // bridge re-fires onLive every ~5 s while a stream plays; the guard makes
    // the purge a one-shot per video.
    fun markSourceLive() {
        if (!isLiveRuntime) {
            isLiveRuntime = true
            progressStore.remove(video.videoId)
            progressRevision++
        }
    }

    // Continue Watching state for THIS video (re-read on every revision).
    val savedProgress: VideoProgress? =
        remember(video.videoId, progressRevision) { progressStore.getProgress(video.videoId) }
    // A live broadcast has no finite duration to complete — never present it
    // as watched or resumable, even if an older build left stale progress for
    // it (misclassified live → watched bug).
    val isWatched = !isLiveNow && (savedProgress?.fraction ?: 0f) >= 0.9f
    // Continue Watching / resume applies ONLY to long videos — Shorts always
    // play from the beginning (they're watched in one sitting). Progress is
    // still tracked for Shorts (cards show the % / Watched badge).
    val hasPartialProgress =
        !isLiveNow && !video.isShortsEntry && !isWatched &&
            (savedProgress?.fraction ?: 0f) >= 0.02f
    // Auto-resume from the saved position unless the video was completed
    // (completed → start over).
    val resumeFromSeconds =
        if (hasPartialProgress) savedProgress!!.positionSeconds.toDouble() else 0.0

    // Continue Watching / Watch Again re-seek (no reload) — "resume here",
    // so it seeks AND plays.
    var seekToken by remember { mutableIntStateOf(0) }
    var seekToSeconds by remember { mutableStateOf(0.0) }
    val requestSeek: (Double) -> Unit = { target ->
        seekToSeconds = target
        seekToken++
    }
    // Timeline scrubbing from the app's own transport bar — "move the
    // playhead", so a paused video STAYS paused (the two channels are
    // deliberately distinct). Instagram honours this through its existing
    // seek channel: MediaPlayer.seekTo() never changes the play state either.
    var scrubToken by remember { mutableIntStateOf(0) }
    var scrubToSeconds by remember { mutableStateOf(0.0) }
    val requestScrub: (Double) -> Unit = { target ->
        scrubToSeconds = target
        scrubToken++
    }

    // Playback speed, persisted across restarts.
    val playerPrefs = remember {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var playbackRate by remember {
        mutableStateOf(playerPrefs.getFloat(KEY_PLAYBACK_RATE, 1f).toDouble())
    }
    val setPlaybackRate: (Double) -> Unit = { rate ->
        playbackRate = rate
        playerPrefs.edit().putFloat(KEY_PLAYBACK_RATE, rate.toFloat()).apply()
    }

    // User playlists (shared with the Media tab's local library). Re-read
    // fresh whenever the picker opens — the player is the only screen on top,
    // so an edit made here is picked up by the Media tab on its next
    // composition. Playlists replaced the old Bookmark feature.
    val userPlaylistStore = remember { UserPlaylistStore(context.applicationContext) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    // Same picker in AUDIO mode: the ⋮ menu's "Add audio to playlist…" seeds
    // an audio entry (MediaVideo.isOfflineAudio) instead of the video entry,
    // so the playlist gains "audio of this video" — tapping it plays the
    // downloaded file rather than opening the player.
    var showAudioPlaylistPicker by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    // True while the create dialog was opened from the AUDIO picker, so its
    // seed is the audio entry, not the video entry.
    var creatingAudioPlaylist by remember { mutableStateOf(false) }
    // Re-read whenever EITHER picker opens (the audio picker never flips
    // showPlaylistPicker, so keying on it alone would hand the audio sheet an
    // empty list unless the video picker had been opened first).
    val playerPlaylists = remember(showPlaylistPicker, showAudioPlaylistPicker) {
        if (showPlaylistPicker || showAudioPlaylistPicker) {
            userPlaylistStore.getPlaylists()
        } else {
            emptyList()
        }
    }
    // A user playlist containing the current video — when one exists, the ⋮
    // menu offers removing the video from it (mirroring the Media tab's
    // playlist-feed cards). Bumped after any playlist edit made here so the
    // lookup stays fresh (a video added to a playlist from this screen gets
    // its Remove entry immediately).
    var playlistRevision by remember { mutableIntStateOf(0) }
    val containingPlaylist = remember(userPlaylistStore, video.videoId, playlistRevision) {
        userPlaylistStore.getPlaylists().firstOrNull { p ->
            // The VIDEO entry only — a playlist may hold this video's
            // downloaded AUDIO (isOfflineAudio) as a separate entry, and this
            // ⋮ entry removes the VIDEO. The audio entry is removed from the
            // playlist itself (or via the Delete download flow).
            p.videos.any { !it.isOfflineAudio && it.videoId == video.videoId }
        }
    }
    // A playlist awaiting "remove from playlist" confirmation — captured at
    // menu-tap time (like MediaTab's pendingVideoRemove) so the dialog never
    // depends on later state.
    var pendingRemovePlaylist by remember(video.videoId) { mutableStateOf<UserPlaylist?>(null) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showHideConfirm by remember { mutableStateOf(false) }
    // The ⋮ menu's "Delete download" asks first (never deletes by accident).
    var confirmDeleteDownload by remember { mutableStateOf(false) }

    // Player commands for the Shorts viewer (play/pause/mute). Each command is
    // a token + label pair: bumping the token re-sends the command to the page.
    var commandToken by remember { mutableIntStateOf(0) }
    var command by remember { mutableStateOf("") }
    val sendCommand: (String) -> Unit = { cmd ->
        command = cmd
        commandToken++
    }

    // ── Shared transport state (the seek bar BELOW the action row) ──────
    // Both platforms drive the SAME bar through their own REAL player:
    //  - YouTube: the IFrame API reports its position every second while the
    //    video moves (onTimeline) and a coarse position for watch-progress
    //    persistence (onProgress); the bar sends play/pause/±10 s/seek straight
    //    back through the bridge.
    //  - Instagram: the native MediaPlayer is polled every 400 ms and accepts
    //    the same play/pause/±10 s/seek commands, so its bar is equally real.
    // Nothing here is a decorative control: every action maps to a supported
    // player call, and the times shown are the player's own values.
    var isPlaying by remember(video.videoId) { mutableStateOf(false) }
    var isMediaBuffering by remember(video.videoId) { mutableStateOf(true) }
    var transportPosition by remember(video.videoId) { mutableDoubleStateOf(0.0) }
    var transportDuration by remember(video.videoId) { mutableDoubleStateOf(0.0) }
    // The media's OWN aspect ratio (w/h) once the player reports its size — 0
    // until then. Instagram Reels are not all 9:16 (4:5 and 1:1 are common), so
    // the video box follows the real ratio instead of a hardcoded frame.
    var mediaAspect by remember(video.videoId) { mutableFloatStateOf(0f) }
    // Muted state: starts from the PERSISTED preference (default true) and is
    // remembered across videos, so muting/unmuting one Short carries to every
    // Short you swipe to. The JS bridge reports the player's real state and
    // the toggle applies + persists the change immediately.
    var isMuted by remember {
        mutableStateOf(playerPrefs.getBoolean(KEY_MUTED, true))
    }

    // ── Listen mode (background audio) ──────────────────────────────
    // Whether the app's ONE background audio player is holding THIS video. The
    // same question whether it is playing a downloaded file or a resolved audio
    // stream: the reader asked to hear this video, and which of the two it
    // turned out to be is not their business.
    val isListening = AudioPlayback.playingVideoId.value == video.videoId
    // True while a stream this device has never resolved is being looked up.
    // That is a real network round trip, and a button that appears to do
    // nothing for three seconds reads as broken.
    var listenPreparing by remember(video.videoId) { mutableStateOf(false) }
    val listenScope = rememberCoroutineScope()

    /**
     * Starts listening, or stops it when this video is already what is playing.
     *
     * The in-screen player is paused FIRST, and that is the whole reason this is
     * one function rather than a line at the button: listening and watching the
     * same video are mutually exclusive, and two copies of the same audio a few
     * hundred milliseconds apart is the one failure mode of this feature that is
     * worse than not having it.
     */
    fun toggleListen() {
        if (isListening) {
            AudioPlayback.stop()
            return
        }
        sendCommand("pause")
        listenPreparing = true
        listenScope.launch {
            val started = AudioPlayback.playVideo(
                context = context.applicationContext,
                video = video,
                // Carry on from where the video is: handing over mid-lecture and
                // starting again at zero throws away the position the reader
                // was on.
                startAtMs = (transportPosition * 1000.0).toLong()
            )
            listenPreparing = false
            if (!started) {
                Toast.makeText(
                    context,
                    "Couldn't start listening to this video",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Play/pause from any transport, with the same exclusivity rule from the
     * other side: resuming the video stops listening to it, so the two can
     * never be heard at once.
     */
    fun togglePlayback() {
        if (isPlaying) {
            sendCommand("pause")
        } else {
            if (AudioPlayback.playingVideoId.value == video.videoId) AudioPlayback.stop()
            sendCommand("play")
        }
    }

    fun shareVideo() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Watch: ${video.title}\n${shareUrlFor(video)}")
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "Share video"))
        }
    }

    // Reset retry history when a different video is selected (NOT on retry).
    // NOTE: the mute preference is deliberately NOT reset here — the user's
    // choice carries across videos (all Shorts stay muted/unmuted).
    LaunchedEffect(video.videoId) {
        hasRetried = false
    }

    // Reset state when a different video is selected.
    LaunchedEffect(video.videoId, retryToken) {
        playerState = YtState.UNSTARTED
        errorCode = null
        timedOut = false
        sourceStarted = false
        // Safety net: if the IFrame API never delivers any event (e.g. the
        // api script can't load — no network), surface an error instead of
        // an eternal spinner.
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            if (playerState == YtState.PLAYING ||
                playerState == YtState.PAUSED ||
                errorCode != null
            ) {
                return@LaunchedEffect
            }
        }
        if (playerState != YtState.PLAYING && playerState != YtState.PAUSED &&
            errorCode == null
        ) {
            timedOut = true
        }
    }

    // Loading: no real state yet — the placeholder (blurred thumbnail) is
    // shown until the player actually starts or is paused. CUED is included:
    // a cued-but-still-buffering video would otherwise show the embed's own
    // oversized play graphic over a black surface.
    val isBuffering = errorCode == null && !timedOut &&
        (playerState == YtState.UNSTARTED ||
            playerState == YtState.BUFFERING ||
            playerState == YtState.CUED)
    // Ready but paused (incl. muted-autoplay blocked): a small centered play
    // button, shown ONLY once the video is actually ready.
    val showPlayOverlay = errorCode == null && !timedOut && !isBuffering &&
        playerState == YtState.PAUSED
    // Ready-but-paused BEFORE this source ever started playing (autoplay
    // blocked — e.g. Android data-saver). The blurred placeholder must stay up
    // here too so the embed's grey play button can never show; the app's own
    // centered play overlay (Shorts only) is the play affordance on top. Long
    // videos keep their reachable embed controls, so they are NOT blurred in
    // this state. Guarded against errors/timeouts so the blur can never cover
    // an error card.
    val pausedNotStarted = errorCode == null && !timedOut &&
        video.isShortsEntry && playerState == YtState.PAUSED

    Column(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        // ── Video area: player + its overlays, nothing else in this box ──
        // Exactly matches the WebView's bounds so no sibling can cover it.
        // Vertical fullscreen (Shorts style) fills the whole portrait screen;
        // landscape is naturally full screen; otherwise a 16:9 box at the top.
        val isInstagram = video.isInstagram
        // Portrait Instagram gets an ADAPTIVE, full-width box that matches the
        // media's OWN aspect ratio (reported by the native player the moment it
        // is prepared) instead of a hardcoded 16:9 or 9:16 frame — Reels come
        // in 9:16, 4:5 and 1:1, and none of them may be stretched or cropped.
        val instagramPortraitSize = instagramPortraitBox(video, mediaAspect)
        Box(
            modifier = when {
                isLandscape || fullscreenVertical -> Modifier.fillMaxSize()
                isInstagram -> instagramPortraitSize
                else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            },
            // The media is letterboxed INSIDE this box at its own aspect ratio
            // (see InstagramPlayer's SurfaceView), so the box itself is black.
            contentAlignment = Alignment.Center
        ) {
            if (isInstagram) {
                InstagramPlayer(
                    video = video,
                    muted = isMuted,
                    playbackRate = playbackRate,
                    resumeFromSeconds = resumeFromSeconds,
                    seekToken = seekToken,
                    seekToSeconds = seekToSeconds,
                    commandToken = commandToken,
                    command = command,
                    onProgress = { currentSeconds, durationSeconds ->
                        // Persist real playback progress so Instagram cards get
                        // a progress bar / Watched badge and Continue Watching
                        // resumes from the exact position. The same report feeds
                        // the app-side transport bar (this player is polled
                        // every 400 ms, which is plenty for a draggable bar).
                        if (durationSeconds > 0) {
                            transportPosition = currentSeconds
                            transportDuration = durationSeconds
                            val now = System.currentTimeMillis()
                            val fraction = (currentSeconds / durationSeconds)
                                .toFloat().coerceIn(0f, 1f)
                            if (fraction >= 0.98f || now - lastProgressSavedAt >= 5_000L) {
                                progressStore.setProgress(
                                    video.videoId,
                                    fraction,
                                    currentSeconds.toLong(),
                                    durationSeconds.toLong()
                                )
                                lastProgressSavedAt = now
                            }
                        }
                    },
                    onPlayerState = { playing, ended ->
                        isPlaying = playing && !ended
                        if (ended) {
                            progressStore.set(video.videoId, 1f)
                            progressRevision++
                        }
                    },
                    onBuffering = { buffering -> isMediaBuffering = buffering },
                    onVideoSize = { width, height ->
                        if (width > 0 && height > 0) {
                            mediaAspect = width.toFloat() / height.toFloat()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                YoutubePlayer(
                    videoId = video.videoId,
                    retryToken = retryToken,
                    resumeFromSeconds = resumeFromSeconds,
                    playbackRate = playbackRate,
                    seekToken = seekToken,
                    seekToSeconds = seekToSeconds,
                    scrubToken = scrubToken,
                    scrubToSeconds = scrubToSeconds,
                    commandToken = commandToken,
                    command = command,
                    muted = isMuted,
                    modifier = Modifier.fillMaxSize(),
                    onMuteState = { muted -> isMuted = muted },
                    onPlayerState = { s ->
                        // Late connection: the source is now usable — drop any
                        // timeout card.
                        if (s == YtState.PLAYING || s == YtState.PAUSED) {
                            timedOut = false
                        }
                        // sourceStarted flips ONLY on PLAYING: a ready-but-paused
                        // video (autoplay blocked before it ever started) must keep
                        // the blurred placeholder + the app's own play overlay
                        // (Shorts) instead of exposing the embed's grey play
                        // button. A mid-playback pause still has sourceStarted =
                        // true, so it never re-blurs.
                        if (s == YtState.PLAYING) {
                            sourceStarted = true
                        }
                        playerState = s
                        // Transport bar: the real state of the real player.
                        isPlaying = s == YtState.PLAYING
                        isMediaBuffering = s == YtState.UNSTARTED ||
                            s == YtState.BUFFERING || s == YtState.CUED
                        // Video finished: the whole thing counts as watched. Live
                        // broadcasts can report ENDED when the user simply leaves
                        // the player — a live stream has no finite duration to
                        // complete, so it must never be marked watched. The runtime
                        // live signal (isLiveNow) catches live streams even when
                        // the thumbnail-based isLive hint missed them, and
                        // sawPartialPlayback requires the source to have actually
                        // played through partial positions first (an elapsed-time
                        // live stream reads ≈100% from the start, so it never
                        // satisfies this).
                        if (s == YtState.ENDED && !isLiveNow &&
                            sawFiniteDuration && sawPartialPlayback
                        ) {
                            progressStore.set(video.videoId, 1f)
                            progressRevision++
                        }
                    },
                    onLive = {
                        // First time THIS source reports live: purge any stale
                        // progress persisted by older builds (which misclassified
                        // live streams as watched) so the Watched badge and
                        // Continue-Watching state clear immediately.
                        markSourceLive()
                    },
                    onProgress = { currentSeconds, durationSeconds ->
                        // A finite duration report is the runtime proof this is a
                        // bounded (non-live) video — live streams never report one
                        // (they report Infinity or the growing elapsed time, both
                        // handled here).
                        if (durationSeconds > 0) {
                            // Elapsed-time live detection: a broadcast that reports
                            // the stream's elapsed time as its duration GROWS it on
                            // every tick, monotonically. Two consecutive growing
                            // reports = live, even when the Infinity signal never
                            // fires. Any non-growing report (stable VOD duration, or
                            // an in-stream ad's one-off duration switch) resets the
                            // counter, so ads can't trigger a false positive.
                            if (lastReportedDuration > 0.0) {
                                if (durationSeconds - lastReportedDuration > 1.0) {
                                    growingReports++
                                    if (growingReports >= 2) {
                                        markSourceLive()
                                        return@YoutubePlayer
                                    }
                                } else {
                                    growingReports = 0
                                }
                            }
                            lastReportedDuration = durationSeconds
                            sawFiniteDuration = true
                        }
                        // Live streams are excluded from progress tracking entirely:
                        // the IFrame API reports a live/unknown duration, which
                        // would render a fake progress bar and could pin the video
                        // as watched. Once the stream ends and becomes a VOD it is
                        // re-parsed as a normal video and tracked normally.
                        if (durationSeconds > 0) {
                            transportDuration = durationSeconds
                            transportPosition = currentSeconds
                        }
                        if (!isLiveNow && durationSeconds > 0) {
                            // While a resume seek is still landing, early reports
                            // can read ~0 and would overwrite the saved position.
                            // Skip until the position actually reaches the target.
                            if (resumeFromSeconds > 3.0 &&
                                currentSeconds < resumeFromSeconds - 3.0
                            ) {
                                return@YoutubePlayer
                            }
                            val fraction = (currentSeconds / durationSeconds)
                                .toFloat().coerceIn(0f, 1f)
                            if (fraction < 0.98f) sawPartialPlayback = true
                            // A near-complete fraction is only trusted once the
                            // source showed genuinely partial playback — an
                            // elapsed-time live stream reads ≈100% from the very
                            // first report, so trusting it here would mark the
                            // stream watched even before the growth check runs.
                            if (fraction >= 0.98f && !sawPartialPlayback) {
                                return@YoutubePlayer
                            }
                            val now = System.currentTimeMillis()
                            if (fraction >= 0.98f || now - lastProgressSavedAt >= 5_000L) {
                                progressStore.setProgress(
                                    video.videoId,
                                    fraction,
                                    currentSeconds.toLong(),
                                    durationSeconds.toLong()
                                )
                                lastProgressSavedAt = now
                            }
                        }
                    },
                    onTimeline = { currentSeconds, durationSeconds ->
                        // 1 s-resolution position for the seek bar (the 5 s
                        // channel above stays responsible for persistence).
                        transportPosition = currentSeconds
                        transportDuration = durationSeconds
                    },
                    onPlayerError = { code ->
                        // Log ONCE per error code (the bridge dedups repeats) with the
                        // video id, then update the UI state once.
                        isPlaying = false
                        isMediaBuffering = false
                        Log.w(TAG, "YouTube player error code = $code videoId = ${video.videoId}")
                        timedOut = false
                        errorCode = code
                    }
                )
            }

            // ── Shorts vertical swipe navigation (full-screen viewer) ──
            // A transparent layer ABOVE the WebView translates vertical drags
            // into next/previous Shorts. Taps pass through to the player's
            // own controls (only drags are consumed). Drawn below the
            // fullscreen button + error overlays so those stay tappable.
            if (fullscreenVertical && shortsQueue.size > 1) {
                val swipeThreshold = with(LocalDensity.current) { 60.dp.toPx() }
                // CRITICAL: pointerInput(Unit) launches its gesture block ONCE
                // and never restarts, so plain captures of shortsIndex / queue
                // size would be frozen at the moment the layer first appeared
                // (the first short opened). The "previous" guard (index > 0)
                // would then stay permanently false and swiping back would
                // never work. rememberUpdatedState keeps both values live.
                val currentIndex by rememberUpdatedState(shortsIndex)
                val currentQueueSize by rememberUpdatedState(shortsQueue.size)
                var swipeAccum by remember { mutableStateOf(0f) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    swipeAccum += dragAmount
                                },
                                onDragEnd = {
                                    when {
                                        swipeAccum <= -swipeThreshold &&
                                            currentIndex < currentQueueSize - 1 ->
                                            onNavigateShorts(1)
                                        swipeAccum >= swipeThreshold && currentIndex > 0 ->
                                            onNavigateShorts(-1)
                                    }
                                    swipeAccum = 0f
                                },
                                onDragCancel = { swipeAccum = 0f }
                            )
                        }
                )
                // Position counter pill (e.g. "3 / 12").
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = "${(shortsIndex + 1).coerceAtLeast(1)} / ${shortsQueue.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // ── Shorts viewer transport: the SAME control set as the portrait
            // panel (time, seek, play/pause, ±10 s, volume, speed) plus the
            // queue arrows — the embed's own controls sit behind the swipe
            // layer, so this is the only chrome the viewer shows. Drawn ABOVE
            // the swipe layer so the buttons stay tappable, and it is the last
            // child of the video box so nothing can cover it. Shown ONLY for
            // Shorts: a long video in vertical fullscreen has no swipe layer,
            // so its embed controls stay reachable as before.
            if (fullscreenVertical && shortsQueue.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    VideoTransportControls(
                        state = TransportState(
                            positionSeconds = transportPosition,
                            durationSeconds = transportDuration,
                            isPlaying = isPlaying,
                            isBuffering = isMediaBuffering,
                            isMuted = isMuted,
                            canSeek = transportDuration > 0.0 && !isLiveNow,
                            show = true
                        ),
                        onTogglePlay = { togglePlayback() },
                        onSeekBack10 = { sendCommand("back10") },
                        onSeekForward10 = { sendCommand("fwd10") },
                        onSeek = { target -> requestScrub(target) },
                        onToggleMute = {
                            val target = !isMuted
                            isMuted = target
                            playerPrefs.edit().putBoolean(KEY_MUTED, target).apply()
                            sendCommand(if (target) "mute" else "unmute")
                        },
                        onDark = true,
                        playbackRate = playbackRate,
                        onSelectRate = { setPlaybackRate(it) },
                        onPrevious = { onNavigateShorts(-1) },
                        onNext = { onNavigateShorts(1) },
                        canGoPrevious = shortsIndex > 0,
                        canGoNext = shortsIndex < shortsQueue.size - 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // ── Loading placeholder: a blurred thumbnail of the actual video
            // (or a plain dark box) that fades away when playback first starts.
            // No icon, no spinner, no text — the video area stays clean.
            // ALWAYS composed (never disposed on source change) so the blur
            // can't "pop" off the instant PLAYING fires: it fades OUT over
            // 400ms, keeping the screen covered while the new video's first
            // real frame renders — closing the 1-frame gap where the embed's
            // own grey play button would flash during Shorts swipes (the JS
            // #loading-cover in youtube_player.html backstops it too). Only
            // visible until the source has started once (sourceStarted):
            // buffering during a forward/backward seek re-uses the real
            // frames and must never be covered by it. The ready-but-paused
            // state (autoplay blocked before start) is covered too, so the
            // embed's grey play button never shows there either.
            LoadingPlaceholderOverlay(
                visible = !isInstagram && !sourceStarted && (isBuffering || pausedNotStarted),
                thumbnailUrl = placeholderThumbnail,
                onThumbnailLoaded = { if (video.thumbnailUrl == placeholderThumbnail) {
                    currentThumbnailReady = true
                } },
                modifier = Modifier.fillMaxSize()
            )

            // ── Ready-but-paused: a single centered play button (only shown
            // once the video is ready, so there's never a play graphic during
            // the loading phase). Tapping it resumes playback. Shown ONLY for
            // Shorts — long videos keep the embed's own on-video controls
            // (which are reachable without the swipe layer), so a duplicate
            // overlay button is unnecessary there.
            if (!isInstagram && showPlayOverlay && video.isShortsEntry) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(68.dp)
                        .clickable { togglePlayback() },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Speed badge: shows a non-default playback rate on the video so the
            // current speed is obvious at a glance (Instagram has its own badge
            // inside its controls overlay).
            if (!isInstagram && playbackRate != 1.0) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = formatRate(playbackRate),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Fullscreen toggle (top-right, over the video): in portrait it
            // switches to a YouTube Shorts-style vertical fullscreen (video
            // fills the whole screen, bars hide); tapping again (or back)
            // returns to the normal 16:9 layout. In landscape the video is
            // already fullscreen, so the button rotates back to portrait.
            // Rotation never restarts playback — the activities declare
            // configChanges, so the WebView survives it.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.4f)
            ) {
                IconButton(
                    onClick = {
                        if (isLandscape) {
                            // Leaving landscape fullscreen returns to the
                            // normal portrait layout: also clear any vertical
                            // fullscreen that was active before the rotation,
                            // otherwise the video stays fullscreen after
                            // rotating back.
                            if (fullscreenVertical) onToggleFullscreen()
                            activity?.requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            onToggleFullscreen()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (isLandscape || fullscreenVertical)
                            Icons.Filled.FullscreenExit
                        else
                            Icons.Filled.Fullscreen,
                        contentDescription = if (isLandscape || fullscreenVertical)
                            "Exit fullscreen"
                        else
                            "Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (!isInstagram && timedOut) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.media_player_timeout),
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    PlayerErrorActions(
                        videoId = video.videoId,
                        showOpenInYoutube = hasRetried,
                        onRetry = {
                            hasRetried = true
                            retryToken++
                        }
                    )
                }
            }

            if (!isInstagram && errorCode != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = stringResource(errorMessageRes(errorCode!!)),
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    PlayerErrorActions(
                        videoId = video.videoId,
                        showOpenInYoutube = hasRetried,
                        onRetry = {
                            hasRetried = true
                            retryToken++
                        }
                    )
                }
            }
        }

        // ── Control panel: BELOW the video area in portrait — it can never
        // cover the player. Hidden in landscape and in vertical fullscreen
        // (the video fills the screen).
        if (!isLandscape && !fullscreenVertical) {
            PlayerControlPanel(
                video = video,
                isLive = isLiveNow,
                progress = savedProgress,
                isWatched = isWatched,
                hasPartialProgress = hasPartialProgress,
                playbackRate = playbackRate,
                showSpeedMenu = showSpeedMenu,
                downloadStatus = downloadStatus,
                isOffline = isOffline,
                isManual = isManual,
                onDownloadAudio = {
                    AudioDownloads.download(video, AudioDownloads.sourceFor(video))
                },
                onPlayOffline = onPlayOffline,
                onDeleteDownload = { confirmDeleteDownload = true },
                onRemoveManual = { confirmRemoveManual = true },
                onDownloadMenuAction = {
                    if (downloadStatus is DownloadStatus.Preparing ||
                        downloadStatus is DownloadStatus.Downloading
                    ) {
                        AudioDownloads.cancel(video.videoId)
                    } else {
                        AudioDownloads.download(video, AudioDownloads.sourceFor(video))
                    }
                },
                onAddToPlaylist = { showPlaylistPicker = true },
                onAddAudioToPlaylist = { showAudioPlaylistPicker = true },
                onRemoveFromPlaylist = { pendingRemovePlaylist = containingPlaylist },
                containingPlaylistName = containingPlaylist?.name,
                onSpeedMenuToggle = { showSpeedMenu = !showSpeedMenu },
                onSpeedSelect = { setPlaybackRate(it); showSpeedMenu = false },
                onContinue = { requestSeek(resumeFromSeconds) },
                onWatchAgain = { requestSeek(0.0) },
                onShare = { shareVideo() },
                onHide = { showHideConfirm = true },
                // Listen mode lives in the transport row; these two give it
                // words in the ⋮ menu and own the sleep timer, which only means
                // anything while the background player holds this video.
                isListening = isListening,
                listenPreparing = listenPreparing,
                onListen = { toggleListen() },
                onSleepTimer = { minutes ->
                    AudioPlayback.setSleepTimer(context.applicationContext, minutes)
                },
                onSleepEndOfTrack = {
                    AudioPlayback.setSleepAtEndOfTrack(context.applicationContext, true)
                },
                onMarkWatched = {
                    progressStore.set(video.videoId, 1f)
                    progressRevision++
                    Toast.makeText(context, "Marked as watched", Toast.LENGTH_SHORT).show()
                },
                transport = TransportState(
                    positionSeconds = transportPosition,
                    durationSeconds = transportDuration,
                    isPlaying = isPlaying,
                    isBuffering = isMediaBuffering,
                    isMuted = isMuted,
                    canSeek = transportDuration > 0.0 && !isLiveNow,
                    // A live broadcast is not scrubbable, and a still Instagram
                    // post has no playback at all — no bar for either.
                    show = !isLiveNow && !video.isInstagramImage
                ),
                onTogglePlay = { togglePlayback() },
                onSeekBack10 = { sendCommand("back10") },
                onSeekForward10 = { sendCommand("fwd10") },
                // YouTube scrubs through the dedicated "move the playhead"
                // channel (paused stays paused); Instagram's seekTo already
                // behaves that way, so it reuses its own seek channel.
                onSeek = { target -> if (isInstagram) requestSeek(target) else requestScrub(target) },
                onToggleMute = {
                    val target = !isMuted
                    isMuted = target
                    playerPrefs.edit().putBoolean(KEY_MUTED, target).apply()
                    sendCommand(if (target) "mute" else "unmute")
                },
                // Instagram's details area scrolls (its portrait video can be
                // tall); YouTube keeps the existing fixed layout.
                modifier = if (isInstagram) Modifier.weight(1f) else Modifier,
                scrollable = isInstagram
            )
        }
    }

    // ── Hide confirmation ───────────────────────────────────────────
    if (showHideConfirm) {
        AlertDialog(
            onDismissRequest = { showHideConfirm = false },
            title = { Text("Hide this video?") },
            text = { Text("This video will be removed from your feeds.") },
            confirmButton = {
                TextButton(onClick = {
                    libraryStore.hideVideo(video)
                    showHideConfirm = false
                    Toast.makeText(context, "Video hidden", Toast.LENGTH_SHORT).show()
                    onExit()
                }) { Text("Hide", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showHideConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // ── Remove manually added video confirmation (⋮ menu → Remove) ──
    if (confirmRemoveManual) {
        AlertDialog(
            onDismissRequest = { confirmRemoveManual = false },
            title = { Text("Remove this video?") },
            text = { Text("\"${video.title}\" was added manually. Removing it deletes it from your library.") },
            confirmButton = {
                TextButton(onClick = {
                    libraryStore.removeManuallyAdded(video.videoId)
                    confirmRemoveManual = false
                    Toast.makeText(context, "Video removed", Toast.LENGTH_SHORT).show()
                    onExit()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveManual = false }) { Text("Cancel") }
            }
        )
    }

    // ── Remove from playlist confirmation (⋮ menu → Remove from …) ──
    // Removing a video from a playlist never touches the video itself, so the
    // player stays open — the menu entry simply disappears afterwards.
    pendingRemovePlaylist?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingRemovePlaylist = null },
            title = { Text("Remove video?") },
            text = { Text("Remove \"${video.title}\" from \"${playlist.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    userPlaylistStore.removeVideo(playlist.id, video.videoId)
                    playlistRevision++
                    pendingRemovePlaylist = null
                    Toast.makeText(
                        context,
                        "Removed from ${playlist.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemovePlaylist = null }) { Text("Cancel") }
            }
        )
    }

    // ── Delete offline audio confirmation (⋮ menu → Delete download) ──
    if (confirmDeleteDownload) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDownload = false },
            title = { Text("Delete download?") },
            text = { Text("Delete the offline audio of \"${video.title}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    AudioDownloads.delete(video.videoId)
                    confirmDeleteDownload = false
                    Toast.makeText(context, "Download deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDownload = false }) { Text("Cancel") }
            }
        )
    }

    // ── Add to playlist (Playlist button / ⋮ menu) ──────────────────
    // Reuses the Media tab's picker + name dialog (same package). Adding a
    // video here is instantly visible in the Media tab's playlist feed.
    if (showPlaylistPicker) {
        AddToPlaylistSheet(
            video = video,
            playlists = playerPlaylists,
            onAdd = { playlist ->
                userPlaylistStore.addVideos(playlist.id, listOf(video))
                playlistRevision++
                showPlaylistPicker = false
                Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
            },
            onCreateNew = {
                showPlaylistPicker = false
                showCreatePlaylistDialog = true
            },
            onDismiss = { showPlaylistPicker = false }
        )
    }
    if (showAudioPlaylistPicker) {
        // Audio mode of the same picker: seeds an AUDIO entry so the playlist
        // holds "audio of this video" (plays the downloaded file). Only
        // reachable while the audio is downloaded (⋮ menu gates on isOffline).
        AddToPlaylistSheet(
            video = video.copy(isOfflineAudio = true),
            title = "Add audio to playlist",
            newLabel = "New playlist with this audio",
            playlists = playerPlaylists,
            onAdd = { playlist ->
                userPlaylistStore.addVideos(
                    playlist.id,
                    listOf(video.copy(isOfflineAudio = true))
                )
                playlistRevision++
                showAudioPlaylistPicker = false
                Toast.makeText(
                    context,
                    "Added audio to ${playlist.name}",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onCreateNew = {
                showAudioPlaylistPicker = false
                creatingAudioPlaylist = true
                showCreatePlaylistDialog = true
            },
            onDismiss = { showAudioPlaylistPicker = false }
        )
    }
    if (showCreatePlaylistDialog) {
        PlaylistNameDialog(
            initial = "",
            title = "New playlist",
            confirmLabel = "Create",
            onSubmit = { name ->
                val seed =
                    if (creatingAudioPlaylist) listOf(video.copy(isOfflineAudio = true))
                    else listOf(video)
                creatingAudioPlaylist = false
                userPlaylistStore.createPlaylist(name, seed)
                playlistRevision++
                showCreatePlaylistDialog = false
                Toast.makeText(
                    context,
                    if (seed.first().isOfflineAudio) {
                        "Created \"$name\" with this audio"
                    } else {
                        "Created \"$name\" with this video"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDismiss = {
                creatingAudioPlaylist = false
                showCreatePlaylistDialog = false
            }
        )
    }
}

/**
 * The dedicated controls area BELOW the video (portrait): title + channel,
 * the primary Continue Watching / Watch Again action, and a four-button
 * action row (⋮ More with Add to playlist / Download audio / Hide / Mark as
 * watched, Share, Speed, Playlist). Keeps every secondary action off the
 * video itself.
 */
@Composable
private fun PlayerControlPanel(
    video: MediaVideo,
    isLive: Boolean,
    progress: VideoProgress?,
    isWatched: Boolean,
    hasPartialProgress: Boolean,
    playbackRate: Double,
    showSpeedMenu: Boolean,
    downloadStatus: DownloadStatus?,
    isOffline: Boolean,
    /** Whether this video was manually added by URL (its ⋮ menu offers Remove). */
    isManual: Boolean,
    onDownloadAudio: () -> Unit,
    onPlayOffline: () -> Unit,
    onDeleteDownload: () -> Unit,
    /** ⋮ menu download entry: cancel while active, else start/retry. */
    onDownloadMenuAction: () -> Unit,
    onAddToPlaylist: () -> Unit,
    /** ⋮ menu → Add audio to playlist (only while the audio is downloaded). */
    onAddAudioToPlaylist: () -> Unit,
    /** ⋮ menu → Remove from playlist (only when [containingPlaylistName] is set). */
    onRemoveFromPlaylist: () -> Unit,
    /** Name of the user playlist holding this video, or null (no entry shown). */
    containingPlaylistName: String?,
    onSpeedMenuToggle: () -> Unit,
    onSpeedSelect: (Double) -> Unit,
    onContinue: () -> Unit,
    onWatchAgain: () -> Unit,
    onShare: () -> Unit,
    onHide: () -> Unit,
    /** True while the background audio player holds THIS video (listen mode). */
    isListening: Boolean,
    /** True while a stream is being resolved to start listen mode. */
    listenPreparing: Boolean,
    /** Start or stop listen mode (background audio) for this video. */
    onListen: () -> Unit,
    /** ⋮ menu → Sleep timer, in minutes from now; null turns it off. */
    onSleepTimer: (Int?) -> Unit,
    /** ⋮ menu → Sleep timer → stop when this track ends. */
    onSleepEndOfTrack: () -> Unit,
    /** ⋮ menu → Remove (manually added). */
    onRemoveManual: () -> Unit,
    onMarkWatched: () -> Unit,
    /** The app-side transport bar (time, seek, play/pause, ±10 s, mute). */
    transport: TransportState,
    onTogglePlay: () -> Unit,
    onSeekBack10: () -> Unit,
    onSeekForward10: () -> Unit,
    onSeek: (Double) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Instagram only: the details area takes the leftover height and scrolls,
     * so a tall portrait video can never push the actions or the transport bar
     * off-screen (and nothing is ever clipped or overlapped).
     */
    scrollable: Boolean = false
) {
    val context = LocalContext.current
    var showMoreMenu by remember { mutableStateOf(false) }
    // The sleep timer is the background player's, so the panel only offers it
    // while the background player is holding this video — a timer set on a
    // session that is not running would do nothing and say nothing.
    var showSleepDialog by remember { mutableStateOf(false) }
    val sleepRemainingMs = AudioPlayback.sleepRemainingMs.longValue
    val sleepEndOfTrack = AudioPlayback.sleepEndOfTrack.value
    val sleepChoiceMinutes = AudioPlayback.sleepChoiceMinutes.value
    // Custom playback speed (0.25×–5×, 0.05 steps) — opened from the speed menu.
    var showCustomSpeedDialog by remember { mutableStateOf(false) }
    var customSpeed by remember { mutableStateOf(playbackRate) }
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        // Title + channel · time.
        Text(
            text = video.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildString {
                append(video.channelName.ifBlank { video.channelId })
                if (video.publishedAtEpochMillis > 0L) {
                    append(" · ").append(
                        DateUtils.getRelativeTimeSpanString(
                            video.publishedAtEpochMillis,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString()
                    )
                }
                val duration = progress?.durationSeconds ?: 0L
                if (duration > 0L) {
                    append(" · ").append(formatPosition(duration))
                }
                if (duration > 0L && !isWatched && hasPartialProgress) {
                    append(" · ").append("${((progress?.fraction ?: 0f) * 100).toInt()}%")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Primary action: Continue Watching / Watch Again.
        if (hasPartialProgress) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Continue Watching · " +
                        formatPosition(progress?.positionSeconds ?: 0L)
                )
            }
        } else if (isWatched) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onWatchAgain,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Watch Again")
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(4.dp))

        // ── Action row ──
        Row(modifier = Modifier.fillMaxWidth()) {
            // ⋮ More: Hide video / Mark as watched.
            Box(modifier = Modifier.weight(1f)) {
                PanelAction(
                    icon = Icons.Filled.MoreVert,
                    label = "More",
                    onClick = { showMoreMenu = true },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    // The headline action of the player, in words: the
                    // headphones button in the transport row is the fast path,
                    // and this is the one that explains itself.
                    DropdownMenuItem(
                        text = {
                            Text(if (isListening) "Stop listening" else "Listen in background")
                        },
                        enabled = !listenPreparing,
                        leadingIcon = {
                            Icon(Icons.Filled.Headphones, contentDescription = null)
                        },
                        onClick = {
                            showMoreMenu = false
                            onListen()
                        }
                    )
                    if (isListening) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (sleepTimerArmed(sleepRemainingMs, sleepEndOfTrack)) {
                                        "Sleep timer · ${sleepTimerLabel(sleepRemainingMs, sleepEndOfTrack)}"
                                    } else {
                                        "Sleep timer…"
                                    }
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                showSleepDialog = true
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Add to playlist…") },
                        onClick = {
                            showMoreMenu = false
                            onAddToPlaylist()
                        }
                    )
                    // The downloaded audio can ALSO be added to a playlist — as
                    // its own AUDIO entry (plays the offline file) next to the
                    // video entry above. Only while the audio exists.
                    if (isOffline) {
                        DropdownMenuItem(
                            text = { Text("Add audio to playlist…") },
                            onClick = {
                                showMoreMenu = false
                                onAddAudioToPlaylist()
                            }
                        )
                    }
                    if (containingPlaylistName != null) {
                        DropdownMenuItem(
                            text = { Text("Remove from \"$containingPlaylistName\"") },
                            onClick = {
                                showMoreMenu = false
                                onRemoveFromPlaylist()
                            }
                        )
                    }
                    if (isOffline) {
                        DropdownMenuItem(
                            text = { Text("Delete download") },
                            onClick = {
                                showMoreMenu = false
                                onDeleteDownload()
                            }
                        )
                    }
                    // Audio download with its stateful label, mirroring the feed
                    // cards' ⋮ menu (Download audio / Cancel / Retry). Hidden once
                    // offline (Play Offline owns that state), for live streams, and
                    // for Instagram items.
                    if (!isOffline && !isLive && video.platform != MediaPlatform.INSTAGRAM) {
                        DropdownMenuItem(
                            text = { Text(downloadMenuLabel(downloadStatus, false)) },
                            onClick = {
                                showMoreMenu = false
                                onDownloadMenuAction()
                            }
                        )
                    }
                    if (video.platform == MediaPlatform.INSTAGRAM && video.instagramUrl != null) {
                        DropdownMenuItem(
                            text = { Text("Open on Instagram") },
                            onClick = {
                                showMoreMenu = false
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.instagramUrl)))
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "Can't open link", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    if (isManual) {
                        DropdownMenuItem(
                            text = { Text("Remove (manually added)") },
                            onClick = {
                                showMoreMenu = false
                                onRemoveManual()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Hide video") },
                        onClick = {
                            showMoreMenu = false
                            onHide()
                        }
                    )
                    // A live broadcast has no finite duration to complete, so
                    // "Mark as watched" is meaningless for it.
                    if (!isLive) {
                        DropdownMenuItem(
                            text = { Text("Mark as watched") },
                            onClick = {
                                showMoreMenu = false
                                onMarkWatched()
                            }
                        )
                    }
                }
            }
            PanelAction(
                icon = Icons.Filled.Share,
                label = "Share",
                onClick = onShare,
                modifier = Modifier.weight(1f)
            )
            // Speed hosts its own dropdown menu.
            Box(modifier = Modifier.weight(1f)) {
                PanelAction(
                    icon = Icons.Filled.Speed,
                    label = "Speed",
                    onClick = onSpeedMenuToggle,
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = onSpeedMenuToggle
                ) {
                    SPEED_OPTIONS.forEach { rate ->
                        DropdownMenuItem(
                            text = { Text(formatRate(rate)) },
                            trailingIcon = if (rate == playbackRate) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                            onClick = { onSpeedSelect(rate) }
                        )
                    }
                    // Custom speed: up to 5× with 0.05 precision.
                    DropdownMenuItem(
                        text = { Text("Custom speed…") },
                        onClick = {
                            onSpeedMenuToggle()
                            customSpeed = playbackRate
                            showCustomSpeedDialog = true
                        }
                    )
                }
            }
            PanelAction(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                label = "Playlist",
                onClick = onAddToPlaylist,
                modifier = Modifier.weight(1f)
            )
        }

        // ── Transport controls, directly BELOW the action icons (never over
        // the video). Shown whenever there is real playback to control: a
        // YouTube video, or an Instagram video that is still loading (the bar
        // shows the buffering spinner) or playing. Hidden for live broadcasts
        // (no scrubbable timeline) and still image posts (no playback).
        if (transport.show) {
            Spacer(Modifier.height(10.dp))
            VideoTransportControls(
                state = transport,
                onTogglePlay = onTogglePlay,
                onSeekBack10 = onSeekBack10,
                onSeekForward10 = onSeekForward10,
                onSeek = onSeek,
                onToggleMute = onToggleMute,
                onListen = onListen,
                isListening = isListening,
                listenPreparing = listenPreparing
            )
        }

        if (showSleepDialog) {
            SleepTimerDialog(
                chosenMinutes = sleepChoiceMinutes,
                endOfTrack = sleepEndOfTrack,
                onCountdown = onSleepTimer,
                onEndOfTrack = onSleepEndOfTrack,
                onDismiss = { showSleepDialog = false }
            )
        }

        // ── Offline audio: the Download Audio button with its full state flow
        // (Download → Preparing… → Downloading NN% → Downloaded → Play offline).
        // Live broadcasts can't be downloaded, so the button is hidden for
        // them — and so is a still Instagram post (a photo has no audio).
        // Instagram VIDEOS are downloadable like YouTube ones: the Reel's mp4
        // is resolved on-device and saved, and its audio track is what plays.
        // The resolved audio size (≈ X MB) appears as soon as the stream is
        // resolved — before any bytes are downloaded.
        if (!isLive && !video.isInstagramImage) {
            val audioSize = AudioDownloads.pendingSizes[video.videoId]
            val sizeSuffix = if (audioSize != null && audioSize > 0L)
                " · ≈ ${formatBytes(audioSize)}" else ""
            Spacer(Modifier.height(10.dp))
            when {
                isOffline -> FilledTonalButton(
                    onClick = onPlayOffline,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Play Offline")
                }
                downloadStatus is DownloadStatus.Preparing -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Preparing…$sizeSuffix")
                }
                downloadStatus is DownloadStatus.Downloading -> Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (downloadStatus.progress >= 0f)
                                "Downloading ${(downloadStatus.progress.coerceIn(0f, 1f) * 100).toInt()}%$sizeSuffix"
                            else
                                "Downloading…$sizeSuffix"
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Estimated time remaining, once the downloader has enough
                    // history to compute it ("~2m 30s left").
                    val etaText = formatEtaRemaining(downloadStatus.etaSeconds)
                    if (etaText.isNotEmpty()) {
                        Text(
                            text = etaText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (downloadStatus.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { downloadStatus.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                    }
                }
                downloadStatus is DownloadStatus.Error -> Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onDownloadAudio,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Retry download")
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = downloadStatus.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2
                    )
                }
                // Tonal (not primary-filled like Continue Watching) so the audio
                // action reads as a separate, secondary step.
                else -> FilledTonalButton(
                    onClick = onDownloadAudio,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Download Audio")
                }
            }
        }

    if (showCustomSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showCustomSpeedDialog = false },
            title = { Text("Playback speed") },
            text = {
                Column {
                    Text(
                        text = "Current: ${formatRate(customSpeed)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = customSpeed.toFloat(),
                        onValueChange = { customSpeed = it.toDouble() },
                        valueRange = MIN_CUSTOM_SPEED.toFloat()..MAX_CUSTOM_SPEED.toFloat(),
                        steps = (((MAX_CUSTOM_SPEED - MIN_CUSTOM_SPEED) / CUSTOM_SPEED_STEP).toInt() - 1)
                            .coerceAtLeast(0)
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("0.25×", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        Text("5×", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSpeedSelect((Math.round(customSpeed * 100.0) / 100.0))
                    showCustomSpeedDialog = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomSpeedDialog = false }) { Text("Cancel") }
            }
        )
    }
    }
}

/**
 * Fades the loading placeholder in/out. Wrapped in its own composable so the
 * [AnimatedVisibility] call resolves to the top-level overload (inside the
 * video Box the ColumnScope extension would otherwise be ambiguous).
 *
 * [thumbnailUrl] switches between the previous video's thumbnail (while the
 * new one loads) and the new one (once ready) — a [Crossfade] inside makes the
 * swap a smooth blur-to-blur transition instead of a blank/flashing frame.
 *
 * Enter is INSTANT: the moment a new source is opened or swiped to, the blur
 * must be fully opaque on the very first frame — a fade-in window would let
 * YouTube's grey play-button poster show through underneath. Exit stays slow
 * (400ms): when PLAYING fires the video's first frame may not be composited
 * for another frame or two, so the blur eases away and the transition lands
 * on real video, never on the grey play button.
 */
@Composable
private fun LoadingPlaceholderOverlay(
    visible: Boolean,
    thumbnailUrl: String?,
    onThumbnailLoaded: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(0)),
        exit = fadeOut(animationSpec = tween(400)),
        modifier = modifier
    ) {
        Crossfade(
            targetState = thumbnailUrl,
            animationSpec = tween(300),
            label = "loading-placeholder-blur"
        ) { url ->
            VideoLoadingPlaceholder(
                thumbnailUrl = url.orEmpty(),
                onLoaded = onThumbnailLoaded
            )
        }
    }
}

/**
 * The loading placeholder shown over the player until the video is ready: a
 * blurred, darkened thumbnail of the video poster (no icon, no spinner,
 * no text) or a plain dark gradient when no thumbnail is available. The video
 * fades in beneath it, so there's no layout shift.
 */
@Composable
private fun VideoLoadingPlaceholder(
    thumbnailUrl: String,
    onLoaded: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black)) {
        if (thumbnailUrl.isNotBlank()) {
            RemoteImage(
                url = thumbnailUrl,
                showLoadingSpinner = false,
                onLoaded = onLoaded,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.15f) // cover the blur's soft edges
                    .blur(18.dp)
            )
            // Darken so it reads as a placeholder, not the actual poster.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        } else {
            // No thumbnail yet — a soft dark gradient instead of a flat,
            // jarring black screen.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xFF141414),
                            1f to Color(0xFF000000)
                        )
                    )
            )
        }
    }
}

/** One cell of the action row: icon over a small label, tap target ~48dp. */
@Composable
private fun PanelAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * The transport bar's state, always built from the REAL player (see the
 * per-platform state block in [VideoPlayerScreen]).
 *
 * [canSeek] is false while no finite duration is known — a live broadcast has
 * no scrubbable timeline, so the bar simply doesn't pretend to have one.
 */
private data class TransportState(
    val positionSeconds: Double,
    val durationSeconds: Double,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isMuted: Boolean,
    val canSeek: Boolean,
    /** False for a still post / live broadcast (nothing to scrub). */
    val show: Boolean
)

/**
 * The app's own playback controls. There is exactly ONE implementation: the
 * portrait control panel (below the ⋮ / Share / Speed / Playlist row) and the
 * fullscreen Shorts viewer both render this, so the two can never drift apart.
 *
 *    0:32 ──────────●─────── 3:45
 *    ⇧  ◀10s   ▶/❚❚   10s▶  🔊  1.25x
 *
 * Every control drives the REAL running player — the YouTube IFrame API
 * (`playVideo` / `pauseVideo` / `seekBy` / `scrubTo`) or the native MediaPlayer
 * used for Instagram (start / pause / seekTo) — and the position/duration shown
 * are the player's own reports. Nothing here is a decorative control.
 *
 * The layout is deliberately responsive: the time labels use a MINIMUM width
 * (so "1:25:46" is never clipped) and the slider takes the remaining space, so
 * the row cannot overflow horizontally at any width, and the button row simply
 * centres however many buttons apply.
 *
 * @param onDark white-on-video styling for the fullscreen Shorts viewer. The
 *   panel sits on a surface, where theme colours are the only readable choice
 *   in a light theme (the previous hardcoded white icons were invisible there).
 * @param playbackRate when non-null, a speed entry is offered (Shorts viewer);
 *   the panel omits it because its action row already owns a Speed button with
 *   the full preset list, and two speed menus on one screen would be noise.
 * @param onPrevious / [onNext] queue navigation (the Shorts queue). Omitting
 *   both removes the arrows — a stand-alone video has no queue to walk.
 */
@Composable
private fun VideoTransportControls(
    state: TransportState,
    onTogglePlay: () -> Unit,
    onSeekBack10: () -> Unit,
    onSeekForward10: () -> Unit,
    onSeek: (Double) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
    playbackRate: Double? = null,
    onSelectRate: ((Double) -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    canGoPrevious: Boolean = true,
    canGoNext: Boolean = true,
    /**
     * Listen mode (background audio). Omitted by callers that do not offer it —
     * the vertical Shorts viewer, where swiping is the whole interaction.
     */
    onListen: (() -> Unit)? = null,
    isListening: Boolean = false,
    listenPreparing: Boolean = false
) {
    // While the user drags, the slider follows the finger instead of the
    // player's periodic position reports; the seek is committed on release.
    var dragTo by remember { mutableStateOf<Float?>(null) }
    var showRateMenu by remember { mutableStateOf(false) }
    val max = if (state.canSeek) state.durationSeconds.toFloat() else 0f
    val position = dragTo
        ?: state.positionSeconds.toFloat().coerceIn(0f, if (max > 0f) max else 0f)
    // Over the video: white. On the panel's surface: theme colours (visible in
    // both light and dark themes).
    val labelColor = if (onDark) Color.White.copy(alpha = 0.9f)
        else MaterialTheme.colorScheme.onSurfaceVariant
    val iconTint = if (onDark) Color.White else MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatPosition((dragTo ?: position).toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                // A minimum rather than a fixed width: "1:25:46" must never be
                // clipped, and the slider absorbs the difference.
                modifier = Modifier.widthIn(min = 48.dp),
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Start
            )
            Slider(
                value = position,
                onValueChange = { dragTo = it },
                onValueChangeFinished = {
                    dragTo?.let { onSeek(it.toDouble()) }
                    dragTo = null
                },
                valueRange = 0f..(if (max > 0f) max else 1f),
                enabled = state.canSeek,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            Text(
                text = if (max > 0f) formatPosition(max.toLong()) else "--:--",
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                modifier = Modifier.widthIn(min = 48.dp),
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous / next (the Shorts queue) — only when the caller has a
            // queue to walk. Up/down matches the swipe direction in the viewer.
            onPrevious?.let {
                TransportIconButton(
                    icon = Icons.Filled.KeyboardArrowUp,
                    label = "Previous short",
                    enabled = canGoPrevious,
                    onClick = it,
                    tint = iconTint
                )
            }
            TransportIconButton(
                icon = Icons.Filled.Replay10,
                label = "Back 10 seconds",
                enabled = true,
                onClick = onSeekBack10,
                tint = iconTint
            )
            // Play / pause, ALWAYS present — with the wait drawn as a ring around
            // it rather than in its place.
            //
            // This slot used to BE the loading indicator while a stream buffered,
            // which meant the one control a reader reaches for vanished for
            // exactly as long as the wait lasted: the bar read as "the controls
            // are still loading" over a video that was merely fetching, and a
            // pause was impossible for the same stretch. A ring says the same
            // thing without taking the control away.
            Box(contentAlignment = Alignment.Center) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(46.dp),
                        strokeWidth = 2.dp,
                        color = iconTint.copy(alpha = 0.5f)
                    )
                }
                TransportIconButton(
                    icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = if (state.isPlaying) "Pause" else "Play",
                    enabled = true,
                    onClick = onTogglePlay,
                    emphasized = true,
                    tint = iconTint
                )
            }
            TransportIconButton(
                icon = Icons.Filled.Forward10,
                label = "Forward 10 seconds",
                enabled = true,
                onClick = onSeekForward10,
                tint = iconTint
            )
            TransportIconButton(
                icon = if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                label = if (state.isMuted) "Unmute" else "Mute",
                enabled = true,
                onClick = onToggleMute,
                tint = iconTint
            )
            onListen?.let { listen ->
                Box(contentAlignment = Alignment.Center) {
                    // Armed, a filled disc sits behind the icon. A tint change
                    // alone would be invisible in the control panel, where the
                    // resting tint is ALREADY the accent colour — the one place
                    // this state most needs to be legible.
                    if (isListening) {
                        Surface(
                            shape = CircleShape,
                            color = (if (onDark) Color.White else MaterialTheme.colorScheme.primary)
                                .copy(alpha = 0.18f),
                            modifier = Modifier.size(44.dp)
                        ) {}
                    }
                    // The wait is drawn around the button, never in its place:
                    // resolving an audio stream can take a few seconds, and the
                    // reader must still be able to tap it again to change their
                    // mind.
                    if (listenPreparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(46.dp),
                            strokeWidth = 2.dp,
                            color = iconTint.copy(alpha = 0.5f)
                        )
                    }
                    TransportIconButton(
                        icon = Icons.Filled.Headphones,
                        label = if (isListening) "Stop listening" else "Listen in background",
                        enabled = !listenPreparing,
                        onClick = listen,
                        tint = if (isListening) MaterialTheme.colorScheme.primary else iconTint
                    )
                }
            }
            // Speed — the SAME real player call the panel's Speed action uses
            // (setPlaybackRate through the IFrame API / setPlaybackParams for
            // Instagram). Offered here because the fullscreen Shorts viewer has
            // no control panel to reach the Speed action in.
            if (playbackRate != null && onSelectRate != null) {
                Box {
                    TransportIconButton(
                        icon = Icons.Filled.Speed,
                        label = "Playback speed ${formatRate(playbackRate)}",
                        enabled = true,
                        onClick = { showRateMenu = true },
                        tint = iconTint
                    )
                    DropdownMenu(
                        expanded = showRateMenu,
                        onDismissRequest = { showRateMenu = false }
                    ) {
                        SPEED_OPTIONS.forEach { rate ->
                            DropdownMenuItem(
                                text = { Text(formatRate(rate)) },
                                trailingIcon = if (rate == playbackRate) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    showRateMenu = false
                                    onSelectRate(rate)
                                }
                            )
                        }
                    }
                }
            }
            onNext?.let {
                TransportIconButton(
                    icon = Icons.Filled.KeyboardArrowDown,
                    label = "Next short",
                    enabled = canGoNext,
                    onClick = it,
                    tint = iconTint
                )
            }
        }
    }
}

@Composable
private fun TransportIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    tint: Color = Color.White
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(if (emphasized) 60.dp else 52.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(if (emphasized) 34.dp else 28.dp)
        )
    }
}

/** "12:34", or "1:02:34" past an hour. */
private fun formatPosition(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, sec)
    } else {
        "%02d:%02d".format(m, sec)
    }
}

/**
 * "1x", "1.25x", … — rounded to 2 decimals so a custom slider value never
 * renders floating-point noise ("1.3500000000000001x"), and whole values drop
 * the trailing decimals.
 */
internal fun formatRate(rate: Double): String {
    val rounded = Math.round(rate * 100.0) / 100.0
    val text = if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        // Locale.US: the speed label always uses a dot ("1.35x"), never a
        // locale decimal comma, so it matches the preset menu everywhere.
        String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
    }
    return "${text}x"
}

/** The playback-speed presets offered by the players (up to 5×). */
internal val SPEED_OPTIONS =
    listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0, 5.0)

/** Bounds for the custom playback-speed picker. */
internal const val MIN_CUSTOM_SPEED = 0.25
internal const val MAX_CUSTOM_SPEED = 5.0
internal const val CUSTOM_SPEED_STEP = 0.05

/**
 * The canonical SHARE URL for [video].
 *
 * Instagram items share their own clean permalink (`/reel/<shortcode>/` or
 * `/p/<shortcode>/`), with all tracking query parameters stripped — never the
 * old hardcoded YouTube fallback. Everything else shares the standard YouTube
 * watch URL.
 */
internal fun shareUrlFor(video: MediaVideo): String {
    val isInstagram = video.platform == MediaPlatform.INSTAGRAM ||
        video.videoId.startsWith("ig_")
    if (!isInstagram) return "https://www.youtube.com/watch?v=${video.videoId}"
    val source = video.instagramUrl ?: video.videoId
    val shortcode =
        com.muddassir.clearview.media.data.InstagramStreamResolver.extractShortcode(source)
    val kind = if (video.instagramType == InstagramMediaType.REEL) "reel" else "p"
    return when {
        shortcode.isNotBlank() -> "https://www.instagram.com/$kind/$shortcode/"
        !video.instagramUrl.isNullOrBlank() -> {
            val base = video.instagramUrl!!.substringBefore('?').substringBefore('#')
            base.trimEnd('/') + "/"
        }
        else -> "https://www.instagram.com/"
    }
}

/**
 * The portrait box for an Instagram item: ALWAYS the full available width —
 * an Instagram video must never sit in a narrow letterbox — at the media's own
 * aspect ratio once the player has reported it (Reels are not all 9:16; 4:5
 * and 1:1 are just as common), falling back to the post type's ratio until
 * then.
 *
 * The box height follows that ratio (so a square post is full-width AND
 * square), capped at [MAX_PORTRAIT_VIDEO_FRACTION] of the screen so the action
 * row + transport bar below always keep room — and the media is letterboxed
 * INSIDE the box (never stretched, never cropped), with the details area
 * scrolling, so no layout can be broken by an extreme ratio.
 */
@Composable
private fun instagramPortraitBox(video: MediaVideo, mediaAspect: Float): Modifier {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val ratio = mediaAspect.takeIf { it > 0.05f } ?: when (video.instagramType) {
        InstagramMediaType.REEL, InstagramMediaType.VIDEO -> 9f / 16f
        else -> 1f
    }
    val natural = screenWidth / ratio
    // Shorter of: the media's own natural height, the fraction ceiling, and
    // whatever still leaves [MIN_DETAILS_HEIGHT_DP] for the details area — so
    // the action row and transport bar below the video are on screen without
    // any scrolling on a normal phone (a 9:16 Reel used to eat ~72% of the
    // screen and pushed the controls into a sliver that had to be scrolled to).
    // The floor keeps the video itself from collapsing into a thin strip on a
    // very short screen.
    val height = minOf(
        natural,
        screenHeight * MAX_PORTRAIT_VIDEO_FRACTION,
        screenHeight - MIN_DETAILS_HEIGHT_DP
    ).coerceAtLeast(screenHeight * MIN_PORTRAIT_VIDEO_FRACTION)
    return Modifier.fillMaxWidth().height(height)
}

/** SharedPreferences holding the user's persisted playback rate. */
private const val PREFS_NAME = "media_player_prefs"
private const val KEY_PLAYBACK_RATE = "playback_rate"
private const val KEY_MUTED = "muted"

private const val TAG = "VideoPlayerScreen"

/**
 * End-to-end budget for resolving an Instagram reel's stream (HTTP attempt +
 * the WebView embed render). After this the player shows a real error card with
 * Retry — the UI can never sit on "Loading…" indefinitely.
 */
private const val RESOLVE_TIMEOUT_MS = 25_000L

/** How long a stream may stay un-prepared before it is treated as failed. */
private const val PREPARE_TIMEOUT_MS = 20_000L

/**
 * Decode width for the player's blurred backdrop / poster. Big enough to look
 * sharp behind the blur, far smaller than the source image.
 */
private const val BACKDROP_WIDTH_PX = 720

/**
 * Ceiling for a portrait Instagram video box, as a fraction of the screen
 * height. The box stays FULL WIDTH (the media is letterboxed inside it, never
 * cropped), but it is deliberately kept to a bit over half the screen so the
 * details area below — action row + transport bar — stays visible without
 * scrolling. Only tall media (9:16 Reels) ever hit this cap; square and
 * landscape posts are shorter than it by their own ratio.
 */
private const val MAX_PORTRAIT_VIDEO_FRACTION = 0.55f

/**
 * How much of the screen the details area below an Instagram video must always
 * keep, regardless of the media's ratio. This is the guarantee behind the
 * fraction above: on a short screen the video gives up more height rather than
 * pushing the controls off-screen.
 */
private val MIN_DETAILS_HEIGHT_DP = 300.dp

/**
 * Floor for the Instagram video box, as a fraction of the screen height — an
 * extreme ratio (or a very short screen) must never shrink the video into an
 * unusable strip.
 */
private const val MIN_PORTRAIT_VIDEO_FRACTION = 0.3f

/** If no player event arrives within this window, surface an error card. */
private const val LOAD_TIMEOUT_MS = 25_000L

/**
 * How often the native Instagram player polls its position / duration and
 * play state (MediaPlayer has no per-frame callback).
 */
private const val PROGRESS_POLL_MS = 400L

/**
 * [MediaPlayer.seekTo] takes a 32-bit millisecond offset, while the player's
 * positions are seconds. Clamped so a bad duration can never overflow.
 */
private fun secondsToMillisInt(seconds: Double): Int =
    (seconds * 1000).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()

/**
 * Actions under a playback-failure card: retry in-app, plus an OPT-IN
 * "Open in YouTube app" — launched only when the user taps it (never
 * automatic), for the case where YouTube's embed restrictions can't be
 * worked around from the WebView (e.g. error 152).
 */
@Composable
private fun PlayerErrorActions(
    videoId: String,
    showOpenInYoutube: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.media_player_retry))
        }
        // Opt-in escape hatch, shown only after an in-app retry already failed.
        if (showOpenInYoutube) {
            TextButton(onClick = { openInYouTubeApp(context, videoId) }) {
                Text(stringResource(R.string.media_player_open_youtube))
            }
        }
    }
}

/**
 * Explicit user action: YouTube app first, any other handler (browser) as a
 * graceful fallback. Both startActivity calls are guarded — a device with
 * neither installed must not crash the app.
 */
private fun openInYouTubeApp(context: Context, videoId: String) {
    val watchUri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
    val ytApp = Intent(Intent.ACTION_VIEW, watchUri)
        .setPackage("com.google.android.youtube")
    try {
        context.startActivity(ytApp)
        return
    } catch (e: ActivityNotFoundException) {
        // YouTube app not installed — fall through to a generic handler.
    } catch (e: SecurityException) {
        // Ignore and fall through.
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, watchUri))
    } catch (e: Exception) {
        // No handler at all — nothing we can do; stay in-app.
    }
}

/** Maps a YouTube IFrame API error code to a user-facing message. */
private fun errorMessageRes(code: Int): Int = when (code) {
    // 152 is YouTube's player-config error, seen when the video cannot be
    // played in the embedded player (removed / region / age restrictions).
    YtError.VIDEO_UNAVAILABLE -> R.string.media_player_error_unavailable
    YtError.VIDEO_NOT_FOUND -> R.string.media_player_error_not_found
    YtError.EMBED_NOT_ALLOWED, YtError.EMBED_NOT_ALLOWED_2 ->
        R.string.media_player_error_embed_restricted
    YtError.INVALID_PARAMETER, YtError.HTML5_PLAYER -> R.string.media_player_error_playback
    else -> R.string.media_player_error_playback
}

@Composable
private fun InstagramPlayer(
    video: MediaVideo,
    muted: Boolean,
    playbackRate: Double,
    resumeFromSeconds: Double,
    seekToken: Int,
    seekToSeconds: Double,
    commandToken: Int,
    command: String,
    onProgress: (Double, Double) -> Unit,
    onPlayerState: (Boolean, Boolean) -> Unit,
    /** True while the media is resolving or preparing (transport bar spinner). */
    onBuffering: (Boolean) -> Unit,
    /** The prepared media's real pixel size (drives the box aspect ratio). */
    onVideoSize: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isVideo = video.isInstagramVideo

    val shortcode = remember(video.videoId, video.instagramUrl) {
        com.muddassir.clearview.media.data.InstagramStreamResolver.extractShortcode(
            video.instagramUrl ?: video.videoId
        )
    }

    // ── Stream resolution ─────────────────────────────────────────
    // The post's own media URL when the feed already carried a REAL video URL
    // (the WebProfile provider does), otherwise the direct .mp4 is resolved
    // on-device — no login, no cookies.
    //
    // The resolver first tries Meta's embed page over plain HTTP, and when that
    // yields nothing (the norm: the page is client-rendered now) it RENDERS the
    // same public embed in a hidden WebView and reads the real <video> src. A
    // feed "URL" that is not actually a video (an HTML permalink such as
    // instagram.com/p/<code>/media?size=l lands in the media slot from some RSS
    // bridges) is rejected up front — handing it to MediaPlayer could only ever
    // fail.
    //
    // Playback itself is ALWAYS native (MediaPlayer below, never a WebView):
    // the embed played audio with a BLACK picture on device and its own controls
    // were out of reach from the app. A MediaPlayer always renders.
    var streamUrl by remember(video.videoId) {
        mutableStateOf(
            video.mediaUrl?.takeIf {
                com.muddassir.clearview.media.data.InstagramStreamResolver.isPlayableVideoUrl(it)
            }
        )
    }
    // The post's own poster from the embed render (used as the loading still).
    var posterUrl by remember(video.videoId) { mutableStateOf<String?>(null) }
    var resolvingStream by remember(video.videoId) { mutableStateOf(false) }
    var streamFailed by remember(video.videoId) { mutableStateOf(false) }
    var resolveToken by remember(video.videoId) { mutableIntStateOf(0) }
    var playAttempt by remember(video.videoId) { mutableIntStateOf(0) }

    LaunchedEffect(video.videoId, streamUrl, resolveToken) {
        if (!isVideo || streamUrl != null) return@LaunchedEffect
        // Nothing to resolve from — the post carries no id we can look up.
        if (shortcode.isBlank()) {
            streamFailed = true
            onBuffering(false)
            return@LaunchedEffect
        }
        resolvingStream = true
        streamFailed = false
        onBuffering(true)
        // Bounded end-to-end: the HTTP attempt has its own timeouts and the
        // WebView render has its own 12 s budget; this is the outer guarantee
        // that the UI can never sit in "Loading…" forever.
        // `fresh` on every retry (resolveToken is bumped by the Retry button): the
        // remembered URL is the one that just failed, so answering from the cache
        // would make Retry a dead end — the bug the cache would otherwise
        // reintroduce (§18's lesson, one layer down).
        val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            com.muddassir.clearview.media.data.InstagramStreamResolver
                .resolvePlayableStream(context, shortcode, fresh = resolveToken > 0)
        }
        resolved?.let {
            posterUrl = it.posterUrl
            streamUrl = it.videoUrl
        }
        streamFailed = streamUrl == null
        if (streamFailed) onBuffering(false)
        resolvingStream = false
    }

    // ── Playback state (reported by the native player below) ───────
    var isPlaying by remember(video.videoId) { mutableStateOf(false) }
    var positionSeconds by remember(video.videoId) { mutableDoubleStateOf(0.0) }
    var durationSeconds by remember(video.videoId) { mutableDoubleStateOf(0.0) }
    var mutedState by remember(video.videoId) { mutableStateOf(muted) }
    var playbackFailed by remember(video.videoId) { mutableStateOf(false) }
    // The MediaPlayer and the surface it renders into arrive independently
    // (the surface from the view, the player from prepare), so each is handed
    // to the other as soon as both exist.
    var player by remember(video.videoId) { mutableStateOf<MediaPlayer?>(null) }
    var surface by remember(video.videoId) { mutableStateOf<Surface?>(null) }
    var prepared by remember(video.videoId) { mutableStateOf(false) }
    // True while the stream is resolving/preparing — reported to the transport
    // bar so it shows a spinner instead of a fake play button.
    LaunchedEffect(resolvingStream, streamUrl) {
        onBuffering(resolvingStream || (!streamUrl.isNullOrBlank() && !prepared))
    }
    var started by remember(video.videoId) { mutableStateOf(false) }
    var resumeApplied by remember(video.videoId) { mutableStateOf(false) }
    var reportedPlaying by remember(video.videoId) { mutableStateOf(false) }
    // The stream's own dimensions: the picture is letterboxed inside the
    // player box at the REAL aspect ratio (a 4:5 or square Reel must never be
    // stretched into the 9:16 frame).
    var videoWidth by remember(video.videoId) { mutableIntStateOf(0) }
    var videoHeight by remember(video.videoId) { mutableIntStateOf(0) }
    // Kept live so the long-lived player never calls a stale lambda.
    val progressCallback by rememberUpdatedState(onProgress)
    val stateCallback by rememberUpdatedState(onPlayerState)

    // Create the player for the resolved stream (a retry rebuilds it).
    DisposableEffect(video.videoId, streamUrl, playAttempt) {
        val url = streamUrl
        if (url.isNullOrBlank()) return@DisposableEffect onDispose { }
        playbackFailed = false
        prepared = false
        started = false
        resumeApplied = false
        reportedPlaying = false
        val mp = MediaPlayer()
        runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            // A remote CDN URL goes through the String overload. The
            // Context+Uri overload only handles content:// providers, so using
            // it for an https URL just logs "Error setting data source via
            // ContentResolver" before falling back — and the resolved CDN URL
            // is fully signed, so it needs no extra headers (verified against
            // Meta's CDN: HTTP 206, video/mp4, no Referer required).
            if (url.startsWith("content://")) {
                mp.setDataSource(context, Uri.parse(url))
            } else {
                mp.setDataSource(url)
            }
            mp.setOnPreparedListener { p ->
                prepared = true
                onBuffering(false)
                p.setVolume(if (mutedState) 0f else 1f, if (mutedState) 0f else 1f)
                val durationMs = runCatching { p.duration }.getOrDefault(0)
                if (durationMs > 0) durationSeconds = durationMs / 1000.0
                runCatching {
                    videoWidth = p.videoWidth
                    videoHeight = p.videoHeight
                    // The media's OWN aspect ratio drives the player box, so a
                    // 4:5 or square Reel is not letterboxed into a 9:16 frame.
                    if (p.videoWidth > 0 && p.videoHeight > 0) {
                        onVideoSize(p.videoWidth, p.videoHeight)
                    }
                }
            }
            mp.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
                if (width > 0 && height > 0) onVideoSize(width, height)
            }
            // Rebuffering mid-playback (a stalled CDN connection) is a REAL
            // state the bar should show.
            mp.setOnBufferingUpdateListener { _, percent ->
                onBuffering(percent < 100 && !runCatching { mp.isPlaying }.getOrDefault(false))
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) onBuffering(true)
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) onBuffering(false)
                false
            }
            mp.setOnCompletionListener {
                reportedPlaying = false
                isPlaying = false
                stateCallback(false, true)
            }
            mp.setOnErrorListener { _, _, _ ->
                reportedPlaying = false
                isPlaying = false
                playbackFailed = true
                onBuffering(false)
                stateCallback(false, false)
                true
            }
            mp.prepareAsync()
        }.onFailure {
            playbackFailed = true
        }
        player = mp
        onDispose {
            player = null
            prepared = false
            started = false
            runCatching { mp.release() }
        }
    }    // Preparation watchdog: a MediaPlayer that neither prepares nor errors
    // (a CDN that accepts the connection and then stalls) would otherwise leave
    // the player on "Loading…" forever. Bounded, and it hands the user a real
    // error card with Retry instead.
    LaunchedEffect(video.videoId, streamUrl, playAttempt) {
        if (streamUrl.isNullOrBlank()) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + PREPARE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            if (prepared || playbackFailed) return@LaunchedEffect
        }
        if (!prepared && !playbackFailed) {
            playbackFailed = true
            onBuffering(false)
            stateCallback(false, false)
        }
    }

        // Start once BOTH the player is prepared and the view has a surface — in
        // whichever order the two arrive.
        LaunchedEffect(video.videoId, prepared, surface) {
        val mp = player ?: return@LaunchedEffect
        val s = surface ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        runCatching { mp.setSurface(s) }
        if (started) return@LaunchedEffect
        started = true
        if (!resumeApplied && resumeFromSeconds > 5.0) {
            runCatching { mp.seekTo(secondsToMillisInt(resumeFromSeconds)) }
        }
        resumeApplied = true
        runCatching {
            if (playbackRate != 1.0) {
                mp.setPlaybackParams(mp.playbackParams.setSpeed(playbackRate.toFloat()))
            }
        }
        runCatching { mp.start() }
        reportedPlaying = true
        isPlaying = true
        stateCallback(true, false)
    }

    // Position + transport state. MediaPlayer has no per-frame callback, so the
    // position is polled while a stream is loaded — this is also how a pause or
    // resume caused by audio focus reaches the UI.
    LaunchedEffect(video.videoId, prepared) {
        if (!prepared) return@LaunchedEffect
        while (true) {
            val mp = player
            if (mp != null) {
                val durationMs = runCatching { mp.duration }.getOrDefault(0)
                val positionMs = runCatching { mp.currentPosition }.getOrDefault(0)
                if (durationMs > 0) {
                    durationSeconds = durationMs / 1000.0
                    positionSeconds = positionMs / 1000.0
                    progressCallback(positionSeconds, durationSeconds)
                }
                val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                if (playing != reportedPlaying) {
                    reportedPlaying = playing
                    isPlaying = playing
                    if (playing) stateCallback(true, false)
                }
            }
            delay(PROGRESS_POLL_MS)
        }
    }

    // Continue Watching / Watch Again re-seek from the parent.
    LaunchedEffect(seekToken) {
        if (seekToken <= 0) return@LaunchedEffect
        val mp = player ?: return@LaunchedEffect
        runCatching { mp.seekTo(secondsToMillisInt(seekToSeconds)) }
        positionSeconds = seekToSeconds
    }

    // Transport commands from the parent (the Shorts-style bar / play
    // overlay), so an Instagram clip answers the exact same commands a YouTube
    // video does.
    LaunchedEffect(commandToken) {
        if (commandToken <= 0) return@LaunchedEffect
        val mp = player ?: return@LaunchedEffect
        when (command) {
            "play" -> runCatching { mp.start() }
            "pause" -> runCatching { mp.pause() }
            "toggle" -> if (runCatching { mp.isPlaying }.getOrDefault(false)) {
                runCatching { mp.pause() }
            } else {
                runCatching { mp.start() }
            }
            "mute" -> runCatching { mp.setVolume(0f, 0f) }
            "unmute" -> runCatching { mp.setVolume(1f, 1f) }
            "back10" -> runCatching {
                mp.seekTo((mp.currentPosition - 10_000).coerceAtLeast(0))
            }
            "fwd10" -> runCatching {
                val total = mp.duration
                val target = mp.currentPosition + 10_000
                mp.seekTo(if (total > 0) target.coerceAtMost(total) else target)
            }
        }
    }

    // Apply the persisted playback rate (and re-apply it after a retry).
    LaunchedEffect(playbackRate, prepared) {
        val mp = player ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        val wasPlaying = runCatching { mp.isPlaying }.getOrDefault(false)
        runCatching {
            if (mp.playbackParams.speed != playbackRate.toFloat()) {
                mp.setPlaybackParams(mp.playbackParams.setSpeed(playbackRate.toFloat()))
            }
        }
        // setPlaybackParams can RESUME a paused player on some platforms.
        if (!wasPlaying) runCatching { mp.pause() }
    }

    // Keep the mute state in lock-step with the player preference.
    LaunchedEffect(muted, prepared) {
        val mp = player ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        mutedState = muted
        runCatching { mp.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) }
    }

    // Reopen the stream after a failure. Always re-resolve from scratch: a
    // resolved URL is time-signed and can go stale, and a feed-provided URL
    // that already failed once must not be retried unchanged (that is what
    // made Retry a dead end).
    val retryInstagramPlayback: () -> Unit = {
        playbackFailed = false
        streamFailed = false
        streamUrl = null
        resolveToken++
    }



    Box(modifier = modifier) {
        if (isVideo) {
            val url = streamUrl
            if (url != null) {
                // Native rendering surface. `key` gives every source its OWN
                // SurfaceView (and holder callback), so a fresh player can
                // never be handed a stale surface from the previous video.
                key(video.videoId, url, playAttempt) {
                    val aspect = if (videoHeight > 0) {
                        videoWidth.toFloat() / videoHeight.toFloat()
                    } else {
                        0f
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    holder.addCallback(
                                        object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(holder: SurfaceHolder) {
                                                surface = holder.surface
                                            }

                                            override fun surfaceChanged(
                                                holder: SurfaceHolder,
                                                format: Int,
                                                width: Int,
                                                height: Int
                                            ) {
                                                surface = holder.surface
                                            }

                                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                                surface = null
                                            }
                                        }
                                    )
                                }
                            },
                            modifier = if (aspect > 0f) {
                                Modifier.aspectRatio(aspect)
                            } else {
                                Modifier.fillMaxSize()
                            }
                        )
                    }
                }
            } else {
                // Still resolving the stream — or resolution failed. Show the
                // post's own still with a status line instead of a black box.
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val still = posterUrl ?: video.thumbnailUrl.takeIf { it.isNotBlank() }
                    if (still != null) {
                        RemoteImage(
                            url = still,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            showLoadingSpinner = false
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f))
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (resolvingStream) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Loading video…",
                                color = Color.White.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else if (streamFailed) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "Couldn't load this Reel. It may be private " +
                                        "or removed.",
                                    modifier = Modifier.padding(20.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { retryInstagramPlayback() }) {
                                Text(stringResource(R.string.media_player_retry))
                            }
                        }
                    }
                }
            }
        } else {
        // Image post or carousel — render native Compose RemoteImage
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val thumbnailUrl = video.thumbnailUrl.takeIf { it.isNotBlank() }
                ?: video.mediaUrl?.takeIf { it.isNotBlank() }

            if (thumbnailUrl != null) {
                RemoteImage(
                    url = thumbnailUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Media unavailable",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

        // ── Preparing: the poster + a spinner, so the slot is never a dead
        // black rectangle while the stream opens. The transport bar below the
        // action icons shows the same buffering state.
        if (isVideo && streamUrl != null && !prepared && !playbackFailed) {
            Box(
                modifier = Modifier.matchParentSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val still = posterUrl ?: video.thumbnailUrl.takeIf { it.isNotBlank() }
                if (still != null) {
                    RemoteImage(
                        url = still,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        showLoadingSpinner = false
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                    )
                }
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 3.dp
                )
            }
        }

        // ── Playback failed: reopen the stream (re-resolve + retry) ────
        if (isVideo && playbackFailed) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "Couldn't play this Reel. Tap Retry to resolve it again.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { retryInstagramPlayback() }) {
                    Text(stringResource(R.string.media_player_retry))
                }
            }
        }
    }
}

