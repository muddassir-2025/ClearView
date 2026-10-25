package com.muddassir.clearview.media.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.playback.AudioPlayback
import com.muddassir.clearview.media.util.formatBytes
import com.muddassir.clearview.media.util.formatDownloadDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the audio player is playing.
 *
 * The player itself is one screen by design: a downloaded track and a video the
 * reader asked to hear only (Audio Mode) are the same thing to them — the same
 * backdrop, transport row, seek bar, speed and sleep timer. Only WHERE the bytes
 * come from differs, and [AudioPlayback] already serves both, so the screen
 * takes a source rather than a download.
 */
sealed class AudioPlayerSource {

    abstract val videoId: String
    abstract val title: String
    abstract val channel: String

    /** The downloaded audio file of a video. */
    data class Download(val item: DownloadItem) : AudioPlayerSource() {
        override val videoId: String get() = item.videoId
        override val title: String get() = item.title
        override val channel: String get() = item.channelName.ifBlank { "YouTube" }
    }

    /**
     * A video's audio, streamed in the background (the headphones / Audio Mode
     * control in the video player). [startAtMs] carries the position the video
     * was at, so switching modes does not start the audio over.
     */
    data class Video(val video: MediaVideo, val startAtMs: Long = 0L) : AudioPlayerSource() {
        override val videoId: String get() = video.videoId
        override val title: String get() = video.title
        override val channel: String get() = video.channelName.ifBlank { video.channelId }
    }
}

/**
 * Podcast-style audio player, designed after the Spotify now-playing screen: a
 * blurred album-art backdrop, large rounded artwork, a bold left-aligned
 * title/channel, an elegant seek slider (elapsed left, time remaining right), a
 * clean transport row (previous / ‑10s / play‑pause / +10s / next) and a
 * playback-speed pill pinned to the bottom corner.
 *
 * It plays either the locally downloaded file of an item or a video's audio-only
 * stream ([AudioPlayerSource]) through [AudioPlayback] — the same engine either
 * way — so Audio Mode on a video lands on exactly this screen, and no part of
 * the player is duplicated. Both sources keep playing with the app
 * backgrounded or the screen off (foreground service + media notification).
 *
 * A sleep-timer pill sits beside the speed pill: set here, enforced by the
 * playback service, so it keeps counting with the screen off — which is the
 * only condition it is ever used in.
 *
 * [hasPrevious] / [hasNext] reflect the CURRENT queue the audio was started
 * from (a playlist, the Downloads list, or the whole offline library); the
 * buttons disable at either end instead of wrapping around. A video's audio has
 * no queue, so both are off.
 */
@Composable
fun AudioPlayerScreen(
    source: AudioPlayerSource,
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    val context = LocalContext.current
    var fileMissing by remember(source.videoId) { mutableStateOf(false) }
    // Audio Mode only: the stream could not be resolved (no connection, a photo
    // post, an age-restricted video). Saying so is better than a silent spinner.
    var streamUnavailable by remember(source.videoId) { mutableStateOf(false) }

    // Load the source once for this track. Playback runs through the foreground
    // [AudioPlaybackService], so it keeps playing when the app is backgrounded
    // or the screen is off (media notification attached).
    LaunchedEffect(source.videoId) {
        when (source) {
            is AudioPlayerSource.Download -> {
                val file = AudioDownloads.audioFile(context, source.item)
                if (file.exists()) {
                    AudioPlayback.play(context, source.item)
                    AudioDownloads.markPlayed(source.videoId)
                } else {
                    fileMissing = true
                }
            }

            is AudioPlayerSource.Video -> {
                // Already the loaded track (the reader came back from the video
                // player, or from the notification): keep it playing instead of
                // resolving and restarting it.
                if (AudioPlayback.playingVideoId.value != source.videoId) {
                    val started = AudioPlayback.playVideo(
                        context = context.applicationContext,
                        video = source.video,
                        startAtMs = source.startAtMs
                    )
                    streamUnavailable = !started
                }
            }
        }
    }

    val isPlaying = AudioPlayback.isPlaying.value
    val positionMs = AudioPlayback.positionMs.longValue
    val durationMs = AudioPlayback.durationMs.longValue
    val speed = AudioPlayback.speed.value
    var showSpeedMenu by remember { mutableStateOf(false) }
    // The sleep timer: set here, enforced by the service (so it keeps counting
    // with the screen off — the only condition it is ever used in).
    val sleepRemainingMs = AudioPlayback.sleepRemainingMs.longValue
    val sleepEndOfTrack = AudioPlayback.sleepEndOfTrack.value
    val sleepChoiceMinutes = AudioPlayback.sleepChoiceMinutes.value
    var showSleepDialog by remember { mutableStateOf(false) }

    if (fileMissing) {
        AudioUnavailable("This download is no longer available on the device.")
        return
    }
    if (streamUnavailable) {
        AudioUnavailable("This video's audio couldn't be played. Check your connection and try again.")
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // ── Spotify-style blurred album-art backdrop ──
        AudioBackdrop(source = source)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Bias the artwork slightly above the vertical center, leaving the
            // bottom free for the controls.
            Spacer(Modifier.weight(0.6f))

            // ── Album art ──
            Box(
                modifier = Modifier
                    .size(252.dp)
                    .shadow(18.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
            ) {
                AudioArtwork(source = source, modifier = Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(30.dp))

            // ── Title + channel (left-aligned, Spotify style) ──
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = source.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = source.channel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(22.dp))

            // ── Seek slider: elapsed on the left, remaining on the right ──
            if (durationMs > 0L) {
                val positionSeconds = (positionMs / 1000f)
                val durationSeconds = (durationMs / 1000f)
                var dragFraction by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragFraction ?: (positionSeconds / durationSeconds).coerceIn(0f, 1f),
                    onValueChange = { dragFraction = it },
                    onValueChangeFinished = {
                        dragFraction?.let { AudioPlayback.seekTo((it * durationMs).toLong()) }
                        dragFraction = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
                    )
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatClock(positionMs / 1000L),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "-${formatClock(((durationMs - positionMs) / 1000L).coerceAtLeast(0L))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── Transport controls: previous · ‑10s · play/pause · +10s · next ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = onPrevious,
                    enabled = hasPrevious,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous audio",
                        tint = if (hasPrevious) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(
                    onClick = { AudioPlayback.seekTo((positionMs - 10_000L).coerceAtLeast(0L)) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.Replay10,
                        contentDescription = "Back 10 seconds",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Surface(
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(10.dp, CircleShape)
                        .clip(CircleShape),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    IconButton(onClick = { AudioPlayback.toggle() }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                IconButton(
                    onClick = { AudioPlayback.seekTo(positionMs + 10_000L) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.Forward10,
                        contentDescription = "Forward 10 seconds",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(
                    onClick = onNext,
                    enabled = hasNext,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next audio",
                        tint = if (hasNext) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Bottom row: track info (left) · speed pill (right) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sourceFooter(source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    Surface(
                        onClick = { showSpeedMenu = !showSpeedMenu },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                Icons.Filled.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = formatRate(speed.toDouble()),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    // The same speed BAR the video player shows: one slider,
                    // applied as it moves so the new rate is heard while
                    // dragging it. A list of presets made the reader guess at
                    // the number they wanted and then hunt for "Custom".
                    if (showSpeedMenu) {
                        Popup(
                            alignment = Alignment.BottomCenter,
                            onDismissRequest = { showSpeedMenu = false },
                            properties = PopupProperties(focusable = true)
                        ) {
                            Surface(
                                modifier = Modifier.width(280.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 6.dp,
                                shadowElevation = 8.dp
                            ) {
                                SpeedBar(
                                    rate = speed.toDouble(),
                                    onRateChange = { rate ->
                                        AudioPlayback.setSpeed(context, rate.toFloat())
                                    },
                                    // Speech, not film: past 3× a podcast is
                                    // noise, and below 0.5× it drags.
                                    minRate = MIN_AUDIO_SPEED,
                                    maxRate = MAX_AUDIO_SPEED
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box {
                    SleepTimerPill(
                        remainingMs = sleepRemainingMs,
                        endOfTrack = sleepEndOfTrack,
                        onClick = { showSleepDialog = true }
                    )
                    if (showSleepDialog) {
                        SleepTimerDialog(
                            chosenMinutes = sleepChoiceMinutes,
                            endOfTrack = sleepEndOfTrack,
                            // "Off" arrives as a null countdown, which is the
                            // same call that set the timer — one entry point, so
                            // the timer cannot be set through a path that does
                            // not also cancel it.
                            onCountdown = { minutes ->
                                AudioPlayback.setSleepTimer(context, minutes)
                            },
                            onEndOfTrack = {
                                AudioPlayback.setSleepAtEndOfTrack(context, true)
                            },
                            onDismiss = { showSleepDialog = false }
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** A quiet full-screen message for audio that cannot be played at all. */
@Composable
private fun AudioUnavailable(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp)
        )
    }
}

/** The artwork: the downloaded thumbnail file, or the video's own poster. */
@Composable
private fun AudioArtwork(source: AudioPlayerSource, modifier: Modifier = Modifier) {
    when (source) {
        is AudioPlayerSource.Download -> OfflineThumbnail(item = source.item, modifier = modifier)
        is AudioPlayerSource.Video -> RemoteImage(
            url = source.video.thumbnailUrl.takeIf { it.isNotBlank() },
            modifier = modifier
        )
    }
}

/**
 * Full-bleed blurred backdrop made from the track's own artwork (Spotify
 * style): the local thumbnail (or, for a streamed video, the poster already in
 * the shared image cache) is decoded once, scaled up and heavily blurred behind
 * the player, with a theme-colored scrim on top so the foreground text stays
 * readable in both light and dark mode. Falls back to a brand-tinted gradient
 * while the artwork loads (or when there is none).
 */
@Composable
private fun AudioBackdrop(source: AudioPlayerSource) {
    val context = LocalContext.current
    var bitmap by remember(source.videoId) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(source.videoId) {
        bitmap = withContext(Dispatchers.IO) {
            when (source) {
                is AudioPlayerSource.Download -> {
                    if (source.item.thumbnailPath.isBlank()) null
                    else runCatching {
                        val f = File(context.cacheDir, "thumbnails/${source.item.thumbnailPath}")
                        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                    }.getOrNull()
                }
                is AudioPlayerSource.Video -> {
                    val url = source.video.thumbnailUrl.takeIf { it.isNotBlank() }
                    if (url == null) null else ThumbnailCache.get(context, url, BACKDROP_WIDTH_PX)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.35f) // keep the blur's soft edges off-screen
                    .blur(40.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
            )
        }
        // Scrim: blend the artwork toward the theme surface so text keeps its
        // contrast whether the art is bright or dark.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        )
                    )
                )
        )
    }
}

/** The bottom-left line: download details, or how Audio Mode is playing. */
private fun sourceFooter(source: AudioPlayerSource): String = when (source) {
    is AudioPlayerSource.Download -> buildString {
        // Real track length for imported device audio (from
        // MediaMetadataRetriever at import time).
        val item = source.item
        if (item.durationSeconds > 0L) {
            append(formatClock(item.durationSeconds)).append(" · ")
        }
        append("Downloaded ")
            .append(formatDownloadDate(item.downloadedAt))
            .append(" · ")
            .append(formatBytes(item.fileSize))
    }

    // Audio Mode: the bytes are a stream and there is no download to describe.
    // The video's own length is shown while it is known; the line then says
    // what the reader is hearing rather than pretending it is saved.
    is AudioPlayerSource.Video -> buildString {
        val duration = source.video.durationSeconds
        if (duration > 0L) append(formatClock(duration)).append(" · ")
        append("Audio only")
    }
}

/**
 * Bounds for the AUDIO speed bar.
 *
 * Narrower than the video bar on purpose: this player is for speech — a
 * podcast at 5× is noise, and below 0.5× it drags. Both ends are whole
 * multiples of [CUSTOM_SPEED_STEP], so the slider's stops stay on the 0.05
 * grid the video bar uses.
 */
internal const val MIN_AUDIO_SPEED = 0.5
internal const val MAX_AUDIO_SPEED = 3.0

/** Poster width decoded for the blurred backdrop. */
private const val BACKDROP_WIDTH_PX = 480

/** "12:34", or "1:02:34" past an hour. */
private fun formatClock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
