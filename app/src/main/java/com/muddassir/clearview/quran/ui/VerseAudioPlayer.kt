package com.muddassir.clearview.quran.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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
 */
@Stable
internal class VerseAudioPlayer {

    private var player: MediaPlayer? = null

    var status by mutableStateOf(VerseAudioStatus.IDLE)
        private set

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
                    it.start()
                    status = VerseAudioStatus.PLAYING
                }
                setOnCompletionListener { status = VerseAudioStatus.ENDED }
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
        status = VerseAudioStatus.PAUSED
    }

    /** Resume a paused recitation; a no-op unless it is actually paused. */
    fun resume() {
        if (status != VerseAudioStatus.PAUSED) return
        runCatching { player?.start() }
        status = VerseAudioStatus.PLAYING
    }

    /** Replay the loaded verse from its first second. */
    fun restart() {
        val mp = player ?: return
        if (status == VerseAudioStatus.IDLE || status == VerseAudioStatus.LOADING) return
        runCatching {
            mp.seekTo(0)
            if (!mp.isPlaying) mp.start()
        }
        status = VerseAudioStatus.PLAYING
    }

    /** Stop and free everything; the next play re-fetches from the start. */
    fun release() {
        val mp = player
        player = null
        runCatching { mp?.reset() }
        runCatching { mp?.release() }
        status = VerseAudioStatus.IDLE
    }
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
