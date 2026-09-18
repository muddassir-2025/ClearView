package com.muddassir.clearview.media.playback

import android.content.Context
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.muddassir.clearview.media.data.AudioStreamResolver
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.model.MediaVideo

/**
 * App-facing facade for the app's ONE background audio player.
 *
 * The actual [android.media.MediaPlayer] lives inside [AudioPlaybackService] — a
 * foreground media service — so audio keeps playing when the app is
 * backgrounded or the screen is off, with a media notification (play/pause/
 * stop, lock-screen controls) attached. All state stays Compose state, so every
 * screen observes it exactly as before; every command is forwarded to the
 * service through intents.
 *
 * ## Two sources, one engine
 *
 * It plays the downloaded audio of a video ([play]) and the audio-only STREAM
 * of one that was never downloaded ([playVideo]). Both are the same player, the
 * same notification and the same sleep timer, which is the point: "background
 * audio" had briefly threatened to become two features with two notifications
 * and two ways to stop, and a reader who is listening does not care whether the
 * bytes came off the disk or off the network.
 *
 * It was called `OfflineAudioPlayer` while downloads were the only thing it
 * could play. The name is now the one thing about it that would have been a lie.
 */
object AudioPlayback {

    /** videoId of the loaded audio, or null when nothing is loaded. */
    val playingVideoId = mutableStateOf<String?>(null)
    val isPlaying = mutableStateOf(false)

    /**
     * True while the player is waiting for bytes it has not got.
     *
     * Only ever set for a stream — a local file is ready the moment it is asked
     * for — and the UI draws it as a ring around the play button rather than in
     * the button's place, so pausing is never impossible for exactly as long as
     * the wait lasts.
     */
    val buffering = mutableStateOf(false)

    val positionMs = mutableLongStateOf(0L)
    val durationMs = mutableLongStateOf(0L)

    /** Current playback speed (1.0 = normal), persisted across sessions. */
    val speed = mutableStateOf(1f)

    /**
     * Milliseconds left on the sleep timer, or 0 when no timer is set.
     *
     * Written by the service's ticker, so it counts down with the screen off —
     * which is the only condition this feature is ever used in.
     */
    val sleepRemainingMs = mutableLongStateOf(0L)

    /** True when the timer is set to stop at the end of what is playing. */
    val sleepEndOfTrack = mutableStateOf(false)

    /**
     * The countdown the reader CHOSE, in minutes, or null for no countdown.
     *
     * Held next to the deadline rather than re-derived from it, so the dialog's
     * check mark stays on the row that was picked instead of drifting off it as
     * the remaining time rounds down.
     */
    val sleepChoiceMinutes = mutableStateOf<Int?>(null)

    private var appContext: Context? = null

    /**
     * Binds the facade to the process.
     *
     * Cheap and idempotent, and worth calling from app startup: without it every
     * command issued before something has been played in this process is a no-op
     * (there would be no Context to forward with), which is visible as a dead
     * pause button on a player that is audibly running.
     */
    fun initialize(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * Loads and starts background playback of the downloaded [item].
     *
     * No-op when [item] is already the loaded audio: with playback continuing
     * in the background after the player screen closes, re-opening the screen
     * must NOT restart the track — it simply shows the ongoing playback.
     */
    fun play(context: Context, item: DownloadItem) {
        appContext = context.applicationContext
        syncSpeed(context)
        if (playingVideoId.value == item.videoId) return
        AudioPlaybackService.play(context, item)
    }

    /**
     * Starts LISTEN mode for [video]: audio only, in the background, from the
     * downloaded file when there is one and from a resolved audio stream when
     * there is not.
     *
     * Suspends because resolving a stream the device has never resolved before
     * is a real network round trip — the caller is expected to show that wait,
     * not to pretend the audio started. Returns false when there is no audio to
     * be had (a photo post, an age-restricted video, no connection), so the
     * caller can say so instead of leaving a spinner running forever.
     *
     * [startAtMs] is where to come in — handing over from the video player is
     * "keep going where I am", and starting from zero there would throw away the
     * position the reader just chose.
     */
    suspend fun playVideo(
        context: Context,
        video: MediaVideo,
        startAtMs: Long = 0L
    ): Boolean {
        appContext = context.applicationContext
        syncSpeed(context)

        // Already the loaded track: continue it rather than re-resolving and
        // restarting (the watchdog in play() has the same rule, one level down).
        if (playingVideoId.value == video.videoId) return true

        // Prefer what is already on the device: no resolution, no network, and
        // it starts now. A download also has no expiry to run into.
        val downloaded = AudioDownloads.itemFor(video.videoId)
        if (downloaded != null && AudioDownloads.audioFile(context, downloaded).exists()) {
            AudioPlaybackService.play(context, downloaded)
            return true
        }

        val url = AudioStreamResolver.audioUrlFor(context, AudioStreamResolver.requestFor(video))
        if (url.isNullOrBlank()) return false

        AudioPlaybackService.playStream(
            context,
            AudioPlaybackService.NowPlaying(
                videoId = video.videoId,
                title = video.title,
                channel = video.channelName.ifBlank { video.channelId },
                artworkUrl = video.thumbnailUrl.takeIf { it.isNotBlank() },
                streamUrl = url,
                streamRequest = AudioStreamResolver.requestFor(video),
                startAtMs = startAtMs.coerceAtLeast(0L)
            )
        )
        return true
    }

    fun toggle() {
        val ctx = appContext ?: return
        if (playingVideoId.value == null) return
        if (isPlaying.value) {
            AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_PAUSE)
        } else {
            AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_RESUME)
        }
    }

    fun pause() {
        val ctx = appContext ?: return
        if (playingVideoId.value == null) return
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_PAUSE)
    }

    fun resume() {
        val ctx = appContext ?: return
        if (playingVideoId.value == null) return
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_RESUME)
    }

    fun seekTo(ms: Long) {
        val ctx = appContext ?: return
        if (playingVideoId.value == null) return
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_SEEK, ms.coerceAtLeast(0L))
    }

    /** Stops playback; the service tears down its notification and itself. */
    fun stop() {
        val ctx = appContext ?: return
        if (playingVideoId.value == null) return
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_STOP)
    }

    /**
     * Changes the playback speed. Persisted so it survives restarts and is
     * applied to the live player by [AudioPlaybackService]; the Compose state
     * updates immediately so the player screen and notification follow.
     */
    fun setSpeed(context: Context, rate: Float) {
        val ctx = context.applicationContext
        appContext = ctx
        speed.value = rate
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SPEED, rate).apply()
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_SET_SPEED, speed = rate)
    }

    // ── Sleep timer ────────────────────────────────────────────────

    /**
     * Sets the sleep timer to [minutes] from now, or turns it off when
     * [minutes] is null or not positive.
     *
     * The deadline is the service's, and it is an absolute instant rather than a
     * countdown of playing time: the question this answers is "stop by the time
     * I fall asleep", so pausing to listen to something else, or taking a call,
     * must not buy the audio more minutes.
     */
    fun setSleepTimer(context: Context, minutes: Int?) {
        val ctx = context.applicationContext
        appContext = ctx
        val chosen = minutes?.takeIf { it > 0 }
        sleepChoiceMinutes.value = chosen
        val ms = (chosen ?: 0).toLong() * 60_000L
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_SET_SLEEP, sleepMs = ms)
    }

    /**
     * Sets the timer to stop when what is playing ends, instead of at a time.
     *
     * The other half of the same control for the other shape of listening: one
     * more video, or this recitation and then sleep. Mutually exclusive with a
     * countdown, because "stop in ten minutes and also when this ends" is two
     * timers and one of them will surprise the reader.
     */
    fun setSleepAtEndOfTrack(context: Context, enabled: Boolean) {
        val ctx = context.applicationContext
        appContext = ctx
        sleepChoiceMinutes.value = null
        AudioPlaybackService.send(
            ctx,
            AudioPlaybackService.ACTION_SET_SLEEP,
            sleepEndOfTrack = enabled
        )
    }

    /** Turns the timer off, whichever kind it is. */
    fun clearSleepTimer(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        sleepChoiceMinutes.value = null
        AudioPlaybackService.send(ctx, AudioPlaybackService.ACTION_SET_SLEEP, sleepMs = 0L)
    }

    // ── Internals ──────────────────────────────────────────────────

    /**
     * Sync the persisted speed into Compose state so the player screen and the
     * notification reflect the saved rate even after a restart (the service
     * applies it when the new player is prepared).
     */
    private fun syncSpeed(context: Context) {
        speed.value = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1f)
    }

    private const val PREFS_NAME = "offline_audio_player"
    private const val KEY_SPEED = "playback_speed"
}
