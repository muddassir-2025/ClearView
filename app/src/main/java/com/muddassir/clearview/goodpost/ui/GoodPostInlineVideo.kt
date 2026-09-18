package com.muddassir.clearview.goodpost.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GoodPostVideoCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

/**
 * Which card is allowed to make sound (§9, §24).
 *
 * One holder for the whole process, because "mute this one" is only meaningful
 * against "and unmute that one". A reader who taps the speaker on a video is
 * saying *this* clip, and two of them talking at once is not a state anybody
 * asked for — nor is the battery it costs.
 *
 * Muting is a *release*: the card that had sound gives it up and the feed is
 * silent again, which is what a reader expects when they tap the icon a second
 * time. Nothing is remembered across screens: coming back to the feed starts
 * muted, so a channel cannot ambush a reader in a quiet room.
 */
internal object InlineVideoAudio {

    private val _owner = MutableStateFlow<String?>(null)

    val owner: StateFlow<String?> = _owner.asStateFlow()

    /**
     * True while the full-screen viewer is on top.
     *
     * The inline cards have to stop, and this is why it is a flag rather than
     * something the viewer's own composable implies: the FEED is still on screen
     * behind an overlay — a full-size `Box` in the same window — so a card's own
     * "am I visible" check still says yes while the viewer plays. Two players over
     * one clip is what "it is not in sync" sounds like: the same audio twice, a
     * fraction of a second apart, and a picture that will not line up with either.
     */
    private val _viewerOpen = MutableStateFlow(false)

    val viewerOpen: StateFlow<Boolean> = _viewerOpen.asStateFlow()

    fun setViewerOpen(open: Boolean) {
        _viewerOpen.value = open
    }

    fun claim(key: String) {
        _owner.value = key
    }

    fun release(key: String) {
        if (_owner.value == key) _owner.value = null
    }

    /**
     * A playhead handed BACK from the full-screen viewer to the card behind it.
     *
     * The hand-off has to work in both directions or "it is not in sync" is
     * right: opening the viewer resumes where the card was, so closing it must
     * resume where the VIEWER was. Without this the card carried on from the
     * position it had when it was paused — which, on a clip watched to the end, is
     * the beginning of it.
     *
     * Keyed on the cache key (the object), because that is what the card behind
     * the viewer is keyed on and the only value both sides can compute.
     */
    private val _handedBack = MutableStateFlow<Pair<String, Long>?>(null)

    val handedBack: StateFlow<Pair<String, Long>?> = _handedBack.asStateFlow()

    fun handBack(key: String, positionMs: Long) {
        if (key.isBlank() || positionMs <= 0L) return
        _handedBack.value = key to positionMs
    }

    /** Claim the hand-off for [key], if it is still the one on offer. */
    fun takeHandBack(key: String): Long? {
        val offered = _handedBack.value ?: return null
        if (offered.first != key) return null
        _handedBack.value = null
        return offered.second
    }
}

/** Playback, as the card needs to draw it. */
private enum class InlineVideoState { Idle, Loading, Playing, Ended, Failed }

/**
 * A channel video, playing in place (§9).
 *
 * It used to be a black rectangle with a play glyph over it, and the tap handed
 * the signed URL to whatever app had registered for the scheme — which showed an
 * S3 access denial, because a presigned URL is a capability for the client that
 * asked for it rather than a page. The preview is now the video: muted, playing,
 * looping nowhere, and tapping it opens the app's own full-screen player.
 *
 * ## Why muted
 *
 * A feed scrolls past video, so an audible one is a thing that happens *to* a
 * reader rather than something they chose. Silent-until-asked is the rule
 * everywhere this pattern exists, and the speaker button on the card is by
 * construction the only way to change it.
 *
 * ## Why it only plays while visible
 *
 * A `LazyColumn` keeps items composed past the fold, so a player created per
 * item would happily stream a video the reader has already scrolled away from:
 * bandwidth, battery, and — the part that actually gets noticed — two clips
 * audible at once. Playback is gated on how much of the card is on screen, and
 * released outright when the app is backgrounded.
 */
@OptIn(UnstableApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun InlineVideoCard(
    url: String,
    /** width / height from the server, when it measured the file. A layout hint. */
    aspect: Float?,
    /**
     * Open this clip full screen, at [positionMs] — where it had got to.
     *
     * The position travels because the reader was WATCHING: opening the viewer
     * used to start the same clip again from zero, which on a half-minute video is
     * indistinguishable from a bug and, on a longer one, is the reason nobody taps
     * the card at all.
     */
    onOpen: (positionMs: Long) -> Unit,
    /**
     * A signature that has expired. Asks the caller for a re-read of the post —
     * see [MediaViewer] for the same contract, and the same reason: the URL is a
     * lease, and the only honest retry is a newer one.
     */
    onRefreshUrl: () -> Unit = {},
    /**
     * Holding the clip selects the post, like holding any other part of it.
     *
     * A press on a clip is answered by the player inside the card rather than by
     * the bubble above it (see the sheets below), so without this, holding a
     * clip was the one place in the feed where the post could not be picked up.
     */
    onLongPress: () -> Unit = {},
    /**
     * The reader has picked this post (§5).
     *
     * Painted inside the card, above the player, rather than left to the bubble's
     * own layer — because a clip is drawn by a real Android view, which composites
     * above the Compose content around it. A tint the bubble paints over this card
     * is a tint nobody sees on the video itself.
     */
    selected: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (url.isBlank()) {
        // Nothing to play and nothing to try. The post is still worth showing —
        // it may carry text — so the card says what is missing and stays quiet.
        // The same fixed box a playable clip gets, so a broken one is not a
        // differently-shaped hole in the feed.
        WaMediaPlaceholder(
            modifier = modifier
                .fillMaxWidth()
                .let { base -> if (aspect != null) base.aspectRatio(aspect) else base.height(220.dp) },
            icon = Icons.Filled.PlayArrow,
            label = stringResource(R.string.goodpost_media_unavailable)
        )
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val key = remember(url) { GoodPostVideoCache.cacheKeyFor(url) }

    // Declared here, above the effects that read them: a `by` read after its own
    // declaration inside the same composable is fine, but reading one from a
    // LaunchedEffect that appears EARLIER in the body is not — that is a
    // reference to a local that does not exist yet.
    val audioOwner by InlineVideoAudio.owner.collectAsState()
    val viewerOpen by InlineVideoAudio.viewerOpen.collectAsState()
    val handedBack by InlineVideoAudio.handedBack.collectAsState()
    val muted = audioOwner != key

    var visible by remember(url) { mutableStateOf(false) }
    var state by remember(url) { mutableStateOf(InlineVideoState.Loading) }
    var duration by remember(url) { mutableStateOf(0L) }
    // The playhead. Ticked while playing rather than read on demand, because the
    // card DRAWS it: a time that only moves when something else happens is a time
    // that looks stuck.
    var position by remember(url) { mutableStateOf(0L) }
    var triedRefresh by remember(url) { mutableStateOf(false) }

    // Keyed on the URL, so a refresh lands on a *new* player: a player that has
    // already failed keeps that failure, and re-pointing a released one at a
    // second source is how the old error ends up drawn over the new attempt.
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(GoodPostVideoCache.dataSourceFactory(context))
            )
            .build()
            .apply {
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        // The object key, so a second open reads the disk rather
                        // than the bucket — a presigned query changes every load.
                        .setCustomCacheKey(key)
                        .build()
                )
                volume = 0f
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> state = InlineVideoState.Loading
                    Player.STATE_READY -> {
                        duration = player.duration.coerceAtLeast(0L)
                        state = if (player.playWhenReady) {
                            InlineVideoState.Playing
                        } else {
                            InlineVideoState.Idle
                        }
                    }

                    Player.STATE_ENDED -> state = InlineVideoState.Ended
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) state = InlineVideoState.Playing
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Almost always an expired signature on a feed the reader has
                // had open a while. One re-read per URL: a retry loop against a
                // dead object would spend data to fail again.
                if (!triedRefresh) {
                    triedRefresh = true
                    state = InlineVideoState.Loading
                    onRefreshUrl()
                } else {
                    state = InlineVideoState.Failed
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // Released rather than paused: the item is going away. The disk cache
            // is what makes coming back cheap, and it survives this.
            player.release()
        }
    }

    // Play only on screen, only in the foreground, and never underneath the
    // full-screen viewer (which is playing the same clip — see [InlineVideoAudio]).
    LaunchedEffect(player, visible, viewerOpen) {
        if (visible && !viewerOpen) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
        } else {
            player.playWhenReady = false
        }
    }

    // A quarter of a second, the same cadence the viewer's transport uses: fast
    // enough that the seconds look like they are counting rather than jumping,
    // slow enough that it costs a coroutine wake-up and nothing else.
    //
    // It reads the PLAYER's own `isPlaying` rather than this card's [state]. The
    // earlier version looped while `state == Playing`, which tied a clock to a
    // state machine: any transition the clock did not expect — a rebuffer that
    // reported READY before `onIsPlayingChanged`, a pause that left the state
    // alone — stopped the numbers where they were and never restarted them, which
    // is exactly what a reader describes as "the time is not updating". The
    // player already knows, and the loop lives and dies with it.
    LaunchedEffect(player) {
        while (true) {
            if (player.isPlaying) position = player.currentPosition
            delay(250)
        }
    }

    // The other half of the hand-off: the viewer was closed, and this is the
    // position it stopped at. Seeded before the pause below is released, so the
    // picture does not jump backwards through where it had already got to.
    LaunchedEffect(handedBack, player) {
        val stop = InlineVideoAudio.takeHandBack(key) ?: return@LaunchedEffect
        runCatching { player.seekTo(stop) }
        position = stop
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.playWhenReady = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player, muted) {
        player.volume = if (muted) 0f else 1f
    }

    val container = LocalWindowInfo.current.containerSize

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(WaMediaShape)
            .background(Color.Black)
            .let { base ->
                // A known shape holds its space while the first frame decodes, so
                // the feed does not jump under the reader's thumb.
                if (aspect != null) base.aspectRatio(aspect) else base.height(220.dp)
            }
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val window = container.height.toFloat()
                if (window <= 0f || bounds.height <= 0f) return@onGloballyPositioned
                val shown = (min(bounds.bottom, window) - max(bounds.top, 0f)).coerceAtLeast(0f)
                val mostly = shown / bounds.height >= 0.6f
                if (mostly != visible) visible = mostly
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // §5, §9: the selection layer and the card's own gestures, both on sheets
        // ABOVE the player.
        //
        // PlayerView is a real Android view inside this composition, and a real
        // view is composited over the Compose content drawn around it: a tint the
        // bubble paints over the card does not show on the video, and a press that
        // lands on the surface is answered by the player rather than by the bubble
        // above it. Drawn here — after the player, before the readouts — the tint
        // is visible over the clip and the sheets are asked first for a press
        // anywhere on the card, while the elapsed time and the speaker still sit
        // above them and keep their own taps.
        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Wa.Accent.copy(alpha = WaSelectionScrim))
            )
        }

        // The tap is "show me this properly", which is the whole card's job. The
        // hold is the selection gesture the rest of the post already answers to,
        // and it has to be here for the same reason the tint does.
        Box(
            modifier = Modifier
                .matchParentSize()
                .combinedClickable(
                    onClick = { onOpen(position) },
                    onLongClick = onLongPress
                )
        )

        when (state) {
            InlineVideoState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(38.dp),
                strokeWidth = 2.dp,
                color = Wa.Accent
            )

            InlineVideoState.Failed -> Text(
                text = stringResource(R.string.goodpost_media_unavailable),
                color = Wa.TextDim,
                fontSize = 13.sp
            )

            InlineVideoState.Ended, InlineVideoState.Idle -> PlayGlyph()

            InlineVideoState.Playing -> Unit
        }

        // Elapsed and total, once the duration is known: `0:07 / 0:32`. An
        // earlier version showed the total only, which told the reader how long
        // the clip was and never where they were in it — on a card that is
        // already playing, a frozen number reads as a stuck video.
        if (duration > 0 && state != InlineVideoState.Failed) {
            Text(
                text = "${clockOf(position)} / ${clockOf(duration)}",
                color = Wa.Text,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }

        // A hairline of progress along the bottom edge, so the card answers
        // "how far in am I" at a glance even when the numbers are too small to
        // read.
        if (duration > 0 && state == InlineVideoState.Playing) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.18f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(Wa.Accent)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpeakerButton(
                muted = muted,
                onToggle = {
                    if (muted) InlineVideoAudio.claim(key) else InlineVideoAudio.release(key)
                }
            )
        }
    }
}

/** The glyph a stopped video wears: it is a video, and it can be started. */
@Composable
private fun PlayGlyph() {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = stringResource(R.string.goodpost_play_video),
            tint = Wa.Text,
            modifier = Modifier.size(30.dp)
        )
    }
}

/** The one control the card carries, on a disc so it reads over any frame. */
@Composable
private fun SpeakerButton(muted: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (muted) {
                Icons.AutoMirrored.Filled.VolumeOff
            } else {
                Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = stringResource(
                if (muted) R.string.goodpost_unmute else R.string.goodpost_mute
            ),
            tint = Wa.Text,
            modifier = Modifier.size(19.dp)
        )
    }
}

