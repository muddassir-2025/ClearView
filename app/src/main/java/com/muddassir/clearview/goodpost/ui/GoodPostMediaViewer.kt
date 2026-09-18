package com.muddassir.clearview.goodpost.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostVideoCache
import kotlinx.coroutines.delay

/**
 * A post's media, opened in the app (§9, §15).
 *
 * ## Why this exists
 *
 * Tapping a video used to hand its URL to the device, which meant leaving
 * ClearView for a browser and landing on an S3 error page: a presigned URL is a
 * capability, not a page, and it is not interesting to any app except the one
 * that asked for it. Tapping an image did nothing at all. Both now open here —
 * a player with transport controls, or the image at full size — and both can be
 * kept on the device.
 *
 * ## What it is not
 *
 * Not a gallery, not a browser and not a share target: one asset, opened from a
 * post or from a channel's media strip, closed with Back. The list of everything
 * a channel has posted is a screen of its own (§13), because selecting and
 * deleting from a collection is a different job from looking at one thing.
 *
 * ## The signed URL is a lease
 *
 * Every URL here expires. Rather than hide that, an error from the player asks
 * the caller for a fresh one ([onExpired]) and the stage is re-keyed on the URL:
 * a new URL is a new player, which is the only way to be sure the old one's
 * failed state cannot leak into the new attempt. An image that fails is simply
 * drawn as unavailable, because re-reading a post to show a thumbnail again is
 * not worth a request.
 */
@Composable
internal fun MediaViewer(
    kind: String,
    url: String,
    contentType: String?,
    /** width / height, when the server measured it. Only a hint for layout. */
    aspect: Float?,
    onClose: () -> Unit,
    onExpired: () -> Unit,
    /** Whether the POST this media belongs to is starred (§9). */
    starred: Boolean = false,
    onToggleStar: () -> Unit = {},
    /**
     * The file was removed from this device.
     *
     * The caller closes the viewer, because what it is looking at no longer
     * exists here: leaving the stage open would keep drawing a picture that was
     * just deleted from the phone, from the decode still held in memory.
     */
    onDeleted: () -> Unit = {},
    /**
     * Where playback starts: the playhead the feed's card had reached.
     *
     * Zero for anything opened from a grid or a strip, which have nothing to
     * resume from. For a feed card it is the whole point: the reader was watching
     * that clip, and starting it again from the beginning is the difference
     * between "show me this properly" and "start over".
     */
    startAtMs: Long = 0L,
    /**
     * How long the clip is, when the caller already knows.
     *
     * The feed's card has been playing it and the gallery's tile was measured at
     * upload, so both know the length before the player does — and the transport
     * used to show a loading ellipsis for the total until the player had read the
     * file's index off the network, which is exactly the "the controls are still
     * loading" a reader sees over a video that has not started yet. The elapsed
     * side was already seeded this way (see [startAtMs]); this is the other half.
     *
     * Zero means unknown, which is still drawn honestly: the total is blank until
     * the player can answer.
     */
    knownDurationMs: Long = 0L,
    /**
     * The cache key of the card this was opened from, when there WAS one.
     *
     * Set by the feed, null from the gallery. On the way out, the position is
     * handed back to the card under that key — see [InlineVideoAudio.handBack] —
     * so closing a half-watched clip leaves the feed playing from where the
     * full-screen view stopped rather than from wherever the card was paused. It
     * is also what keeps that hand-off from being picked up by a card that was
     * never involved: a gallery open names no key, so nothing behind it moves.
     */
    resumeKey: String? = null
) {
    BackHandler { onClose() }

    // Whoever is on top owns the audio. Without this the feed's card keeps
    // playing underneath the viewer — same clip, two players — which is heard and
    // described as the video being out of sync.
    DisposableEffect(Unit) {
        InlineVideoAudio.setViewerOpen(true)
        onDispose { InlineVideoAudio.setViewerOpen(false) }
    }

    // Nothing behind this should be able to take focus or a touch.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        when (kind) {
            "image" -> ImageStage(url = url, aspect = aspect)
            else -> VideoStage(
                url = url,
                kind = kind,
                aspect = aspect,
                startAtMs = startAtMs,
                knownDurationMs = knownDurationMs,
                resumeKey = resumeKey,
                onExpired = onExpired
            )
        }

        ViewerBar(
            url = url,
            kind = kind,
            contentType = contentType,
            starred = starred,
            onToggleStar = onToggleStar,
            onDeleted = onDeleted,
            onClose = onClose
        )
    }
}

// ── The image ───────────────────────────────────────────────────────────────

/**
 * The image at the largest size that fits.
 *
 * Decoded wider than the feed's own copy (1440 px rather than 720) because this
 * is the one screen where a reader is looking at the picture rather than past
 * it, and the decode is thrown into the same LRU — so the feed's thumbnail is
 * what a second open costs.
 */
@Composable
private fun ImageStage(url: String, aspect: Float?) {
    var bitmap by remember(url) { mutableStateOf(GoodPostImages.peek(url)) }

    LaunchedEffect(url) {
        if (bitmap == null) bitmap = GoodPostImages.load(url, maxWidthPx = 1440)
    }

    val current = bitmap
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (current != null) {
            androidx.compose.foundation.Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base -> aspect?.let { base.aspectRatio(it) } ?: base }
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = Wa.Accent
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.goodpost_media_unavailable),
                    color = Wa.TextDim,
                    fontSize = 14.sp
                )
            }
        }
    }
}

// ── The video ───────────────────────────────────────────────────────────────

/**
 * The player, with Good Post's own transport rather than the library's.
 *
 * The bundled controller is a full-screen video player's: it draws its own
 * title bar, its own background and its own buffering spinner, none of which
 * match a channel feed. `useController = false` and the controls below are
 * Compose, reading the player's own state, which keeps one visual language
 * across the tab.
 *
 * Audio posts take the same path with no surface: the transport is the whole
 * screen, so a voice note from a channel is played the same way a video is.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoStage(
    url: String,
    kind: String,
    aspect: Float?,
    startAtMs: Long,
    knownDurationMs: Long,
    resumeKey: String?,
    onExpired: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(GoodPostVideoCache.dataSourceFactory(context))
            )
            .build()
            .apply {
                // The cache key is the OBJECT, not the URL (§6). A presigned URL's
                // query changes on every page load, so a cache keyed on it would
                // never hit — and "delete this video from my phone" needs a key
                // the app can recompute later, which only the object key is.
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setCustomCacheKey(GoodPostVideoCache.cacheKeyFor(url))
                        .build()
                )
                // Seeded BEFORE prepare, so the first frame drawn is the frame
                // the reader left: a seek after playback starts can be seen.
                if (startAtMs > 0L) seekTo(startAtMs)
                prepare()
                playWhenReady = true
            }
    }

    var playing by remember(player) { mutableStateOf(false) }
    var muted by remember(player) { mutableStateOf(false) }
    var buffering by remember(player) { mutableStateOf(true) }
    // Seeded from what the caller knows, so the transport shows a real total from
    // the first frame rather than an ellipsis until the player has parsed one.
    var duration by remember(player) { mutableStateOf(knownDurationMs.coerceAtLeast(0L)) }
    // Drawn from the first frame, so the transport never shows `0:00` over a
    // clip that is already seven seconds in.
    var position by remember(player) { mutableStateOf(startAtMs) }
    var failed by remember(player) { mutableStateOf(false) }
    var controlsVisible by remember(player) { mutableStateOf(true) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    // The player's own answer wins once it has one: a converted
                    // file, a seek, anything the caller's hint cannot know.
                    val measured = player.duration.coerceAtLeast(0L)
                    if (measured > 0L) duration = measured
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // The way out, in the order it has to happen: tell the card behind
            // where this stopped, THEN release. Read after release would be zero.
            if (resumeKey != null) {
                runCatching { InlineVideoAudio.handBack(resumeKey, player.currentPosition) }
            }
            player.release()
        }
    }

    // A video that keeps playing after the app is in the background is a bug
    // users report as "it won't stop". Paused rather than released: a reader who
    // comes straight back should not pay for a re-buffer.
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player, playing) {
        while (playing) {
            position = player.currentPosition
            delay(250)
        }
        position = player.currentPosition
    }

    LaunchedEffect(controlsVisible, playing) {
        if (controlsVisible && playing) {
            delay(3_500)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { controlsVisible = !controlsVisible }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (kind != "audio") {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base -> aspect?.let { base.aspectRatio(it) } ?: base.fillMaxSize() }
            )
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Wa.Accent,
                modifier = Modifier.size(96.dp)
            )
        }

        if (buffering && !failed) {
            CircularProgressIndicator(
                modifier = Modifier.size(44.dp),
                strokeWidth = 3.dp,
                color = Wa.Accent
            )
        }

        if (failed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.goodpost_media_failed),
                    color = Wa.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.goodpost_media_failed_note),
                    color = Wa.TextDim,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(14.dp))
                WaPillButton(
                    text = stringResource(R.string.goodpost_retry),
                    onClick = {
                        failed = false
                        buffering = true
                        // A fresh URL re-keys this whole stage; asking for one is
                        // the only retry that can succeed once a signature has
                        // expired. If the caller has nothing newer, re-preparing
                        // the same item is still the honest attempt.
                        onExpired()
                        runCatching { player.prepare() }
                    }
                )
            }
        }

        if (controlsVisible && !failed) {
            Transport(
                playing = playing,
                position = position,
                duration = duration,
                muted = muted,
                // Sound starts ON here, unlike the feed: a full-screen view is a
                // deliberate "play this", and a video that says nothing until a
                // second tap reads as broken. Inline cards are the ones that have
                // to keep quiet until asked.
                onToggleMute = {
                    muted = !muted
                    player.volume = if (muted) 0f else 1f
                    controlsVisible = true
                },
                onTogglePlay = {
                    if (player.isPlaying) player.pause() else player.play()
                    controlsVisible = true
                },
                onSeek = { target ->
                    position = target
                    player.seekTo(target)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

/** Play/pause, elapsed, a scrubber and the total. */
@Composable
private fun Transport(
    playing: Boolean,
    position: Long,
    duration: Long,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Wa.Accent)
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (playing) R.string.goodpost_pause_video else R.string.goodpost_play_video
                ),
                tint = Wa.OnAccent,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(10.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = clockOf(position),
                color = Wa.Text,
                fontSize = 12.sp,
                maxLines = 1
            )
            Slider(
                value = position.toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..(duration.coerceAtLeast(1L)).toFloat(),
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Wa.Accent,
                    activeTrackColor = Wa.Accent,
                    inactiveTrackColor = Wa.TextDim
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            // The real length the moment anything knows it, and an ellipsis only
            // while nothing does. It used to say "…" whenever the player was
            // buffering, which is the whole of the time a reader is waiting — so
            // the one control they look at said "still loading" about a clip whose
            // length the card behind it had known all along.
            Text(
                text = if (duration > 0L) clockOf(duration) else "…",
                color = Wa.Text,
                fontSize = 12.sp,
                maxLines = 1
            )
        }

        Spacer(Modifier.width(8.dp))

        ViewerButton(
            icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            description = stringResource(
                if (muted) R.string.goodpost_unmute else R.string.goodpost_mute
            ),
            onClick = onToggleMute
        )
    }
}

// ── The bar over both ───────────────────────────────────────────────────────

/**
 * Close on the left; everything else behind one menu on the right.
 *
 * The menu is shared with the gallery and the media strip ([MediaActionMenu]), so
 * Share, Save, Star and Delete mean the same thing and are in the same order
 * wherever media is opened. A bar of four icons over a photo would cover the
 * photo; this is one tap and a list of words.
 */
@Composable
private fun ViewerBar(
    url: String,
    kind: String,
    contentType: String?,
    starred: Boolean,
    onToggleStar: () -> Unit,
    onDeleted: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ViewerButton(
            icon = Icons.Filled.Close,
            description = stringResource(R.string.goodpost_close),
            onClick = onClose
        )

        Spacer(Modifier.weight(1f))

        // A dark disc behind the dots, because a menu that is invisible over a
        // white photo is a menu the reader never finds.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            MediaActionMenu(
                url = url,
                kind = kind,
                contentType = contentType,
                starred = starred,
                onToggleStar = onToggleStar,
                onDeleted = onDeleted
            )
        }
    }
}

@Composable
private fun ViewerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Wa.Text, modifier = Modifier.size(22.dp))
    }
}

/** `1:04` / `1:02:03`, which is how a player reads a position. */
internal fun clockOf(millis: Long): String {
    val total = (millis.coerceAtLeast(0L)) / 1000
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
