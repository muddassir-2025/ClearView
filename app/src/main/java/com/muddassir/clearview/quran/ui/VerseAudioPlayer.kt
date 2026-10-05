package com.muddassir.clearview.quran.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Where a single verse's recitation is in its lifecycle.
 *
 * There is no DOWNLOADING state and no progress: this streams one short file,
 * so "loading" is the gap between the tap and the first sound, not a transfer
 * a reader would ever watch.
 */
internal enum class VerseAudioStatus { IDLE, LOADING, PLAYING, PAUSED, ENDED, FAILED }

/**
 * The reciter whose per-ayah files the Listen button streams.
 *
 * everyayah.com publishes one MP3 per ayah, named `<surah><ayah>` with each
 * number zero-padded to three digits (`001001.mp3` = Al-Faatiha, ayah 1), which
 * is exactly the granularity Listen needs: one verse, one file, no playlist and
 * nothing kept on the device. 128 kbps keeps an ayah around a hundred kilobytes
 * while still sounding like a recitation rather than a phone call.
 */
private const val RECITER_BASE = "https://everyayah.com/data/Alafasy_128kbps"

private const val TAG = "VerseAudioPlayer"

/** How often the playhead is sampled while a recitation is playing. */
private const val PROGRESS_TICK_MS = 250L

/** The per-ayah MP3 URL for one verse. */
internal fun verseAudioUrl(surahNumber: Int, ayahNumber: Int): String =
    "%s/%03d%03d.mp3".format(RECITER_BASE, surahNumber, ayahNumber)

/**
 * Streams ONE verse's recitation.
 *
 * Deliberately not the app's background audio service (which exists for
 * downloaded media with lock-screen controls): listening to a verse is a few
 * seconds long, started from a screen the reader is looking at, and should end
 * when they leave it. Nothing is written to disk — the file is fetched as it
 * plays and dropped when the verse changes — so the feature costs no storage at
 * all, which is the whole reason it streams instead of downloading.
 *
 * The state is Compose state so the button can follow it, and it is a plain
 * class rather than a composable so the MediaPlayer lifetime is explicit:
 * [release] is called when the verse changes, never left to garbage collection.
 *
 * [positionMs] and [durationMs] are exposed for the seek bar, and are sampled
 * from the player on a 250ms tick rather than read on every recomposition:
 * `currentPosition` is a binder call, and asking it for every frame of a scroll
 * would make the whole page jank for a number only the bar needs.
 */
@Stable
internal class VerseAudioPlayer {

    private var player: MediaPlayer? = null

    var status by mutableStateOf(VerseAudioStatus.IDLE)
        private set

    /** How far into the loaded recitation playback is, in milliseconds. */
    var positionMs by mutableStateOf(0)
        private set

    /** The loaded recitation's full length in milliseconds, or 0 until known. */
    var durationMs by mutableStateOf(0)
        private set

    private val handler = Handler(Looper.getMainLooper())

    /** Refreshes [positionMs]/[durationMs] and keeps itself alive while playing. */
    private val progressTicker = object : Runnable {
        override fun run() {
            syncProgress()
            if (status == VerseAudioStatus.PLAYING) {
                handler.postDelayed(this, PROGRESS_TICK_MS)
            }
        }
    }

    private fun syncProgress() {
        val mp = player ?: return
        runCatching {
            positionMs = mp.currentPosition
            val d = mp.duration
            if (d > 0) durationMs = d
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(progressTicker)
        handler.post(progressTicker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(progressTicker)
    }

    /** Begin (or retry) playback of [url] from the start. */
    fun start(url: String) {
        release()
        status = VerseAudioStatus.LOADING
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener {
                    durationMs = it.duration.coerceAtLeast(0)
                    it.start()
                    status = VerseAudioStatus.PLAYING
                    startTicker()
                }
                setOnCompletionListener {
                    // Land the playhead on the end so the finished bar reads
                    // full rather than wherever the last tick happened to be.
                    syncProgress()
                    positionMs = durationMs
                    status = VerseAudioStatus.ENDED
                    stopTicker()
                }
                setOnErrorListener { _, _, _ ->
                    release()
                    status = VerseAudioStatus.FAILED
                    true
                }
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            // A malformed URL, no network, or a device with no audio output:
            // the button returns to "Listen" and says so, rather than sitting
            // on a loading state that can never finish.
            Log.w(TAG, "could not play verse audio: ${e.message}")
            release()
            status = VerseAudioStatus.FAILED
        }
    }

    /** Pause the loaded recitation; a no-op unless it is actually playing. */
    fun pause() {
        if (status != VerseAudioStatus.PLAYING) return
        runCatching { player?.pause() }
        syncProgress()
        status = VerseAudioStatus.PAUSED
        stopTicker()
    }

    /** Resume a paused recitation; a no-op unless it is actually paused. */
    fun resume() {
        if (status != VerseAudioStatus.PAUSED) return
        runCatching { player?.start() }
        status = VerseAudioStatus.PLAYING
        startTicker()
    }

    /** Replay the loaded verse from its first second. */
    fun restart() {
        val mp = player ?: return
        if (status == VerseAudioStatus.IDLE || status == VerseAudioStatus.LOADING) return
        runCatching {
            mp.seekTo(0)
            if (!mp.isPlaying) mp.start()
        }
        positionMs = 0
        status = VerseAudioStatus.PLAYING
        startTicker()
    }

    /**
     * Move the playhead to [ms].
     *
     * A no-op until the file is prepared, because there is nothing to seek in
     * yet. Seeking in a FINISHED recitation leaves it paused at the new spot
     * rather than ended, so the Listen button reads "Resume" and the reader can
     * pick the ayah up from where they dragged to.
     */
    fun seekTo(ms: Int) {
        val mp = player ?: return
        if (status == VerseAudioStatus.IDLE || status == VerseAudioStatus.LOADING) return
        val upper = if (durationMs > 0) durationMs else ms
        val target = ms.coerceIn(0, upper)
        runCatching { mp.seekTo(target) }
        positionMs = target
        if (status == VerseAudioStatus.ENDED) {
            status = VerseAudioStatus.PAUSED
            stopTicker()
        }
    }

    /** Stop and free everything; the next play re-fetches from the start. */
    fun release() {
        val mp = player
        player = null
        stopTicker()
        runCatching { mp?.reset() }
        runCatching { mp?.release() }
        status = VerseAudioStatus.IDLE
        positionMs = 0
        durationMs = 0
    }
}

/**
 * A seekable progress bar for the verse loaded in [player].
 *
 * Drawn only once the file is prepared (its duration is known), because a bar
 * that cannot be dragged to a meaningful place is worse than none. The elapsed
 * and total times sit under it, so a reader can see where in the ayah they are
 * and drag to the part they want — an ayah is short, but a long one still takes
 * a reciter half a minute.
 */
@Composable
internal fun VerseAudioProgressBar(player: VerseAudioPlayer, modifier: Modifier = Modifier) {
    val duration = player.durationMs
    val loaded = duration > 0 &&
        (player.status == VerseAudioStatus.PLAYING ||
            player.status == VerseAudioStatus.PAUSED ||
            player.status == VerseAudioStatus.ENDED)
    if (!loaded) return

    // While the thumb is held, show the dragged value rather than the ticking
    // playhead — otherwise every 250ms tick would yank the thumb back under the
    // finger. The player is only told to seek once the drag finishes.
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val position = (scrubbing ?: player.positionMs.toFloat())
        .coerceIn(0f, duration.toFloat())

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = position,
            onValueChange = { scrubbing = it },
            onValueChangeFinished = {
                scrubbing?.let { player.seekTo(it.toInt()) }
                scrubbing = null
            },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatAudioTime(position.toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatAudioTime(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** `m:ss` for a millisecond position, as an audio player shows it. */
private fun formatAudioTime(ms: Int): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * A player scoped to one verse.
 *
 * It is released when the verse changes (New Verse, Previous, Next) as well as
 * when the screen leaves composition, so a recitation is never left playing
 * behind the verse actually on screen.
 */
@Composable
internal fun rememberVerseAudioPlayer(surahNumber: Int, ayahNumber: Int): VerseAudioPlayer {
    val player = remember { VerseAudioPlayer() }
    DisposableEffect(surahNumber, ayahNumber) {
        onDispose { player.release() }
    }
    return player
}
