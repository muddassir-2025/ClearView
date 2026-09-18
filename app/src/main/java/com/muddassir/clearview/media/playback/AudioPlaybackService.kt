package com.muddassir.clearview.media.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.muddassir.clearview.LauncherActivity
import com.muddassir.clearview.R
import com.muddassir.clearview.media.data.AudioStreamResolver
import com.muddassir.clearview.media.data.WatchProgressStore
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.ui.ThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground media service that owns the app's ONE background audio
 * [MediaPlayer], so audio keeps playing when the app is backgrounded or the
 * screen is off — with a media notification (play / pause / stop, plus
 * lock-screen and quick-settings controls through [MediaSessionCompat]).
 *
 * [AudioPlayback] is the app-facing facade: every call forwards here via
 * intents, and the service writes the observable state back through
 * [AudioPlayback]'s Compose state, so every player screen, the Downloads list
 * and the notification all stay in sync no matter where playback is controlled
 * from.
 *
 * ## Two sources
 *
 * A downloaded file (a path) or an audio-only STREAM (a URL plus the
 * [AudioStreamResolver.Request] it came from, so a URL that dies mid-session can
 * be resolved again and resumed rather than just ending the session). They are
 * one player on purpose: a second engine for streamed audio would mean a second
 * notification and a second way to stop, and the reader is doing the same thing
 * either way.
 *
 * ## The sleep timer
 *
 * Lives here rather than in a screen, because it has to keep counting with the
 * screen off and the phone face down — which is the only situation it is ever
 * used in. A deadline is an absolute instant ([SystemClock.elapsedRealtime]),
 * not a total of playing time: the question it answers is "stop by the time I
 * fall asleep", so pausing must not quietly buy the audio more minutes. The last
 * [SleepTimer.FADE_MS] are faded out, because audio stopping dead in a quiet
 * room is the opposite of what this control is for.
 */
class AudioPlaybackService : Service() {

    companion object {
        private const val TAG = "AudioPlaybackService"
        const val CHANNEL_ID = "audio_playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.muddassir.clearview.action.PLAY"
        const val ACTION_TOGGLE = "com.muddassir.clearview.action.TOGGLE"
        const val ACTION_PAUSE = "com.muddassir.clearview.action.PAUSE"
        const val ACTION_RESUME = "com.muddassir.clearview.action.RESUME"
        const val ACTION_SEEK = "com.muddassir.clearview.action.SEEK"
        const val ACTION_SET_SPEED = "com.muddassir.clearview.action.SET_SPEED"
        const val ACTION_SET_SLEEP = "com.muddassir.clearview.action.SET_SLEEP"
        const val ACTION_STOP = "com.muddassir.clearview.action.STOP"

        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_THUMB = "thumb_path"
        const val EXTRA_SEEK_MS = "seek_ms"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_ART_URL = "art_url"
        const val EXTRA_START_POSITION_MS = "start_position_ms"
        const val EXTRA_IS_INSTAGRAM = "is_instagram"
        const val EXTRA_INSTAGRAM_URL = "instagram_url"
        const val EXTRA_MEDIA_URL = "media_url"
        const val EXTRA_SLEEP_MS = "sleep_ms"
        const val EXTRA_SLEEP_END_OF_TRACK = "sleep_end_of_track"

        /** Playback-speed bounds (PlaybackParams accepts 0.5x–2.0x). */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2f

        /** Starts background playback of the downloaded audio [item]. */
        fun play(context: Context, item: DownloadItem) {
            val file = AudioDownloads.audioFile(context, item)
            if (!file.exists()) return
            start(
                context,
                NowPlaying(
                    videoId = item.videoId,
                    title = item.title,
                    channel = item.channelName.ifBlank { "YouTube" },
                    filePath = file.absolutePath
                )
            )
        }

        /** Starts background playback of an audio-only [nowPlaying.streamUrl]. */
        fun playStream(context: Context, nowPlaying: NowPlaying) {
            if (nowPlaying.streamUrl.isNullOrBlank()) return
            start(context, nowPlaying)
        }

        private fun start(context: Context, nowPlaying: NowPlaying) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_VIDEO_ID, nowPlaying.videoId)
                putExtra(EXTRA_TITLE, nowPlaying.title)
                putExtra(EXTRA_CHANNEL, nowPlaying.channel)
                nowPlaying.filePath?.let { putExtra(EXTRA_FILE_PATH, it) }
                nowPlaying.streamUrl?.let { putExtra(EXTRA_STREAM_URL, it) }
                nowPlaying.artworkUrl?.let { putExtra(EXTRA_ART_URL, it) }
                if (nowPlaying.startAtMs > 0L) {
                    putExtra(EXTRA_START_POSITION_MS, nowPlaying.startAtMs)
                }
                // Only a stream needs its origin recorded — a file cannot go
                // stale, so there is nothing for a retry to resolve.
                nowPlaying.streamRequest?.let { request ->
                    putExtra(EXTRA_IS_INSTAGRAM, request.isInstagram)
                    request.instagramUrl?.let { putExtra(EXTRA_INSTAGRAM_URL, it) }
                    request.mediaUrl?.let { putExtra(EXTRA_MEDIA_URL, it) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Sends a lightweight command (pause/resume/seek/stop/speed/sleep) to
         * the service.
         *
         * [sleepMs] is negative when the caller is not touching the countdown:
         * 0 means "no timer", a positive value means "that many milliseconds
         * from now", so the absent case has to be distinguishable from both.
         */
        fun send(
            context: Context,
            action: String,
            seekMs: Long = 0L,
            speed: Float = 1f,
            sleepMs: Long = -1L,
            sleepEndOfTrack: Boolean = false
        ) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                this.action = action
                if (seekMs > 0L) putExtra(EXTRA_SEEK_MS, seekMs)
                if (action == ACTION_SET_SPEED) putExtra(EXTRA_SPEED, speed)
                if (action == ACTION_SET_SLEEP) {
                    putExtra(EXTRA_SLEEP_MS, sleepMs)
                    putExtra(EXTRA_SLEEP_END_OF_TRACK, sleepEndOfTrack)
                }
            }
            context.startService(intent)
        }

        /** Thumbnail width for the notification's large icon (dp-ish px). */
        private const val ART_WIDTH_PX = 256
    }

    /**
     * What to play, so callers describe a track rather than a pile of extras —
     * the translation into intents happens in one place, in the companion
     * above, so a new field cannot be added to one and forgotten in the other.
     */
    data class NowPlaying(
        val videoId: String,
        val title: String,
        val channel: String,
        val artworkUrl: String? = null,
        /** A downloaded file to play, or null for [streamUrl]. */
        val filePath: String? = null,
        /** An audio-only stream URL, or null for [filePath]. */
        val streamUrl: String? = null,
        /** What [streamUrl] was resolved from, so a retry can resolve again. */
        val streamRequest: AudioStreamResolver.Request? = null,
        val startAtMs: Long = 0L
    )

    private var player: MediaPlayer? = null
    private var session: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var currentTitle = "ClearView audio"
    private var currentChannel = "Offline audio"
    private var currentThumb: Bitmap? = null

    /** The videoId of what is loaded (also the key watch progress is filed under). */
    private var currentVideoId: String? = null

    /** True while playing a stream rather than a local file. */
    private var isStream = false

    /** Where the current stream came from, for a retry. Null for a file. */
    private var streamRequest: AudioStreamResolver.Request? = null

    /**
     * One re-resolve per load, NOT per error.
     *
     * A signed URL that has genuinely expired will fail, be re-resolved, and
     * then fail again if the replacement is bad too — and without this the
     * second failure would start a third resolution, and so on, waking the
     * network forever in the background. One retry is a recovery; a loop is a
     * battery drain that looks like a bug in whichever screen is open.
     */
    private var retriedForUrl = false

    /** The last position the player reported, used to resume after a retry. */
    private var lastPositionMs = 0L

    /** When the sleep timer fires ([SystemClock.elapsedRealtime]), or 0. */
    private var sleepDeadlineAt = 0L

    /** Whether the sleep timer is "stop when this ends" instead of a countdown. */
    private var sleepEndOfTrack = false

    private val handler = Handler(Looper.getMainLooper())

    /** For the one thing here that is genuinely asynchronous: a retry resolve. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 1 s position ticker: keeps Compose state, the media session, the sleep
     *  countdown and the notification progress in sync while playing. */
    private val ticker = object : Runnable {
        override fun run() {
            val mp = player
            if (mp != null) {
                val pos = try { mp.currentPosition.toLong().coerceAtLeast(0L) } catch (e: Exception) { 0L }
                AudioPlayback.positionMs.longValue = pos
                lastPositionMs = pos
            }
            setPlaybackState(AudioPlayback.isPlaying.value, AudioPlayback.positionMs.longValue)
            tickSleepTimer()
            // Rebuilt while PAUSED too, but only when a countdown is running: the
            // notification carries how long is left, and a timer whose visible
            // count freezes the moment the reader pauses reads as one that
            // stopped being a timer.
            if (AudioPlayback.isPlaying.value || sleepDeadlineAt > 0L) {
                notifyProgress(AudioPlayback.positionMs.longValue)
            }
            // Self-terminating: a ticker left running on a paused player is a
            // wakeup a second, forever. With a timer armed it keeps going even
            // while paused, because the countdown has to be able to end a
            // session nobody is listening to.
            if (AudioPlayback.isPlaying.value || sleepDeadlineAt > 0L) {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "ClearViewAudio").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onStop() { stopPlayback() }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                // startForegroundService grants ~5 s to post the foreground
                // notification — do it immediately, then set up the player.
                startForegroundCompat(buildNotification(playing = false))
                handlePlay(intent)
            }
            ACTION_TOGGLE -> {
                if (player == null) {
                    stopSelf()
                } else if (AudioPlayback.isPlaying.value) {
                    pause()
                } else {
                    resume()
                }
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_SEEK -> seekTo(intent.getLongExtra(EXTRA_SEEK_MS, 0L))
            ACTION_SET_SPEED -> {
                // A speed change with no loaded player is a no-op — stop the
                // service again so a stray command can't leave it idling.
                if (player != null) {
                    applySpeed(intent.getFloatExtra(EXTRA_SPEED, 1f))
                } else {
                    stopSelf()
                }
            }
            ACTION_SET_SLEEP -> {
                if (player != null) {
                    val ms = intent.getLongExtra(EXTRA_SLEEP_MS, -1L)
                    applySleepTimer(
                        countdownMs = if (ms >= 0L) ms else null,
                        atEndOfTrack = intent.getBooleanExtra(EXTRA_SLEEP_END_OF_TRACK, false)
                    )
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        persistStreamProgress(completed = false)
        stopTicker()
        runCatching { player?.stop() }
        player?.release()
        player = null
        session?.release()
        session = null
        super.onDestroy()
    }

    // ── Playback ───────────────────────────────────────────────────

    private fun handlePlay(intent: Intent) {
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val source = streamUrl ?: filePath ?: return

        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: "ClearView audio"
        currentChannel = intent.getStringExtra(EXTRA_CHANNEL) ?: "Offline audio"
        currentVideoId = videoId
        currentThumb = null

        // A new source starts a new session: the previous track's retry budget
        // and sleep timer do not belong to this one.
        retriedForUrl = false
        lastPositionMs = 0L
        clearSleepTimerState()

        openSource(
            source = source,
            isUrl = streamUrl != null,
            startAtMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L),
            request = if (streamUrl != null) {
                AudioStreamResolver.Request(
                    videoId = videoId,
                    isInstagram = intent.getBooleanExtra(EXTRA_IS_INSTAGRAM, false),
                    instagramUrl = intent.getStringExtra(EXTRA_INSTAGRAM_URL),
                    mediaUrl = intent.getStringExtra(EXTRA_MEDIA_URL)
                )
            } else {
                null
            }
        )

        // Notification large icon: the local thumbnail when the audio is a
        // download, otherwise the video's own artwork out of the app's shared
        // image cache (already fetched for the card the reader tapped, so this
        // is usually a memory hit rather than a second download).
        val thumbPath = intent.getStringExtra(EXTRA_THUMB) ?: ""
        val artUrl = intent.getStringExtra(EXTRA_ART_URL) ?: ""
        when {
            thumbPath.isNotBlank() -> loadLocalThumb(thumbPath)
            artUrl.isNotBlank() -> loadRemoteArt(artUrl)
        }
        updateNotification(playing = false)
    }

    /**
     * Builds the player for [source] and starts it, seeking to [startAtMs].
     *
     * Used both for a fresh PLAY and for a retry after a stream died, which is
     * why it takes everything it needs rather than reading the intent: the retry
     * has a new URL and the same everything else.
     */
    private fun openSource(
        source: String,
        isUrl: Boolean,
        startAtMs: Long,
        request: AudioStreamResolver.Request?
    ) {
        stopInternal()
        isStream = isUrl
        streamRequest = request

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // The String overload takes a filesystem path AND an http(s) URL,
            // which is what lets one player serve both sources.
            mp.setDataSource(source)
            mp.setOnPreparedListener { prepared ->
                AudioPlayback.durationMs.longValue = prepared.duration.toLong().coerceAtLeast(0L)
                AudioPlayback.positionMs.longValue = startAtMs.coerceAtLeast(0L)
                setBuffering(false)
                // Apply the persisted playback speed before playback starts (a
                // few OEMs only accept PlaybackParams once playing — the
                // fallback below retries right after start()).
                var speedApplied = false
                runCatching {
                    prepared.playbackParams = prepared.playbackParams.setSpeed(
                        AudioPlayback.speed.value.coerceIn(MIN_SPEED, MAX_SPEED)
                    )
                    speedApplied = true
                }
                // Handed over mid-listen ("keep going where I am") or resumed
                // after a retry: seek BEFORE starting, so the first frame of
                // audio is the right one.
                if (startAtMs > 0L) {
                    runCatching { prepared.seekTo(startAtMs.toInt()) }
                }
                prepared.start()
                AudioPlayback.isPlaying.value = true
                requestAudioFocus()
                startTicker()
                setPlaybackState(playing = true, positionMs = AudioPlayback.positionMs.longValue)
                updateNotification(playing = true)
                if (!speedApplied && AudioPlayback.speed.value != 1f) {
                    runCatching {
                        prepared.playbackParams = prepared.playbackParams.setSpeed(
                            AudioPlayback.speed.value.coerceIn(MIN_SPEED, MAX_SPEED)
                        )
                    }
                }
            }
            // A stream can stall mid-song; the transport should say so rather
            // than look identical to a player that has stopped responding.
            mp.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                        setBuffering(true)
                        true
                    }
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                        setBuffering(false)
                        true
                    }
                    else -> false
                }
            }
            mp.setOnCompletionListener { finishPlayback() }
            mp.setOnErrorListener { _, _, _ ->
                onPlaybackError()
                true
            }
            mp.prepareAsync()
            player = mp
            AudioPlayback.playingVideoId.value = currentVideoId
            setBuffering(isUrl)
        } catch (e: Exception) {
            Log.w(TAG, "SETUP_FAILED ${e.message}")
            runCatching { mp.release() }
            player = null
            AudioPlayback.playingVideoId.value = null
            setBuffering(false)
            stopSelf()
            return
        }
    }

    /**
     * A live [MediaPlayer] reported an error.
     *
     * For a stream that is very often a URL that outlived its signature (which
     * is why the resolver cached it with an expiry in the first place), so the
     * answer is to resolve a fresh one and carry on from where the listener was
     * — once. For a file, and for a stream that already used its retry, this is
     * a real failure and the session ends.
     */
    private fun onPlaybackError() {
        val request = streamRequest
        Log.w(TAG, "PLAYER_ERROR stream=$isStream retriedForUrl=$retriedForUrl")
        if (isStream && request != null && !retriedForUrl) {
            retriedForUrl = true
            retryStream(request)
            return
        }
        stopPlayback()
    }

    private fun retryStream(request: AudioStreamResolver.Request) {
        val resumeAt = lastPositionMs
        setBuffering(true)
        scope.launch {
            val url = AudioStreamResolver.audioUrlFor(
                context = this@AudioPlaybackService,
                request = request,
                fresh = true
            )
            handler.post {
                if (url.isNullOrBlank()) {
                    // Nothing to recover with — end it honestly rather than
                    // sitting on a spinner that will never resolve.
                    stopPlayback()
                } else {
                    // Keep the session's own bookkeeping: a retry is not a new
                    // track, so a second failure must NOT get another retry, and
                    // any sleep timer the reader set still applies.
                    openSource(source = url, isUrl = true, startAtMs = resumeAt, request = request)
                    updateNotification(playing = AudioPlayback.isPlaying.value)
                }
            }
        }
    }

    private fun pause() {
        player?.pause()
        AudioPlayback.isPlaying.value = false
        setBuffering(false)
        persistStreamProgress(completed = false)
        setPlaybackState(playing = false, positionMs = AudioPlayback.positionMs.longValue)
        updateNotification(playing = false)
        // The ticker stops itself on its next pass (nothing playing and no timer).
    }

    private fun resume() {
        val mp = player
        if (mp != null && AudioPlayback.playingVideoId.value != null) {
            runCatching { mp.start() }
            AudioPlayback.isPlaying.value = true
            startTicker()
            setPlaybackState(playing = true, positionMs = AudioPlayback.positionMs.longValue)
            updateNotification(playing = true)
        }
    }

    private fun seekTo(ms: Long) {
        val pos = ms.coerceAtLeast(0L)
        player?.seekTo(pos.toInt())
        AudioPlayback.positionMs.longValue = pos
        lastPositionMs = pos
        setPlaybackState(AudioPlayback.isPlaying.value, pos)
    }

    /** Applies a new playback speed to the live player (PlaybackParams, API 23+). */
    private fun applySpeed(rate: Float) {
        val mp = player ?: return
        try {
            mp.playbackParams = mp.playbackParams.setSpeed(rate.coerceIn(MIN_SPEED, MAX_SPEED))
        } catch (e: Exception) {
            Log.w(TAG, "SET_SPEED_FAILED ${e.message}")
        }
    }

    private fun setBuffering(value: Boolean) {
        if (AudioPlayback.buffering.value == value) return
        AudioPlayback.buffering.value = value
        updateNotification(playing = AudioPlayback.isPlaying.value)
    }

    private fun finishPlayback() {
        val id = AudioPlayback.playingVideoId.value
        stopTicker()
        // A stream that ran to the end was genuinely listened to; saying so is
        // the difference between the feed showing it as heard and showing it as
        // never opened, for a session the reader never had a screen on.
        persistStreamProgress(completed = true)
        // "Stop when this ends" is the whole point of that choice, so the track
        // does not stay loaded — it tears down like any other finished session.
        if (sleepEndOfTrack) {
            stopPlayback()
            if (id != null) AudioDownloads.markPlayed(id)
            return
        }
        // Otherwise keep the track loaded, paused at 0, so the notification /
        // player screen Play button replays it — a dead end-of-track button is
        // worse UX than leaving the track loaded.
        runCatching { player?.seekTo(0) }
        AudioPlayback.isPlaying.value = false
        AudioPlayback.buffering.value = false
        AudioPlayback.positionMs.longValue = 0L
        clearSleepTimerState()
        setPlaybackState(playing = false, positionMs = 0L)
        updateNotification(playing = false)
        if (id != null && !isStream) AudioDownloads.markPlayed(id)
    }

    private fun stopPlayback() {
        val wasLoaded = player != null || AudioPlayback.playingVideoId.value != null
        persistStreamProgress(completed = false)
        stopInternal()
        clearSleepTimerState()
        AudioPlayback.playingVideoId.value = null
        AudioPlayback.durationMs.longValue = 0L
        AudioPlayback.isPlaying.value = false
        AudioPlayback.buffering.value = false
        AudioPlayback.positionMs.longValue = 0L
        if (wasLoaded) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun stopInternal() {
        stopTicker()
        runCatching { player?.stop() }
        player?.release()
        player = null
        abandonAudioFocus()
    }

    private fun startTicker() {
        stopTicker()
        handler.post(ticker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    /**
     * Files a stream's position as watch progress, so a session listened to in
     * the background shows up in the feed exactly like one watched on screen —
     * progress bar, Continue Watching, and Watched at the end.
     *
     * Deliberately NOT written per tick: the ticker runs every second, and a
     * preference commit a second is a write that outlives the listening. It is
     * written at the three moments the position stops moving — paused, stopped,
     * finished — which is every moment the value is worth reading.
     *
     * Downloads are excluded: their progress belongs to the offline player's own
     * screen, and filing it under the video's id from here would fight the
     * player's writes.
     */
    private fun persistStreamProgress(completed: Boolean) {
        if (!isStream) return
        val id = currentVideoId ?: return
        val durationMs = AudioPlayback.durationMs.longValue
        if (durationMs <= 0L) return
        val positionMs = if (completed) durationMs else AudioPlayback.positionMs.longValue
        runCatching {
            WatchProgressStore(this).setProgress(
                videoId = id,
                fraction = if (completed) 1f
                else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                positionSeconds = positionMs / 1000L,
                durationSeconds = durationMs / 1000L
            )
        }
    }

    // ── Sleep timer ────────────────────────────────────────────────

    /**
     * Sets the timer to [countdownMs] from now, or to the end of the current
     * track, or turns it off.
     *
     * The two kinds are mutually exclusive — "in ten minutes and also when this
     * ends" is two timers, and one of them always surprises the reader. Passing
     * [countdownMs] wins when both arrive, because a countdown is the explicit
     * choice and [atEndOfTrack] rides along on the same intent.
     */
    private fun applySleepTimer(countdownMs: Long?, atEndOfTrack: Boolean) {
        if (countdownMs != null && countdownMs > 0L) {
            sleepDeadlineAt = SystemClock.elapsedRealtime() + countdownMs
            sleepEndOfTrack = false
            AudioPlayback.sleepEndOfTrack.value = false
            AudioPlayback.sleepRemainingMs.longValue = countdownMs
            // Count down even from a paused player: the deadline is an instant,
            // not a total of listening, so it has to be able to end a session
            // nobody is currently hearing.
            startTicker()
        } else if (atEndOfTrack) {
            sleepDeadlineAt = 0L
            sleepEndOfTrack = true
            AudioPlayback.sleepEndOfTrack.value = true
            AudioPlayback.sleepRemainingMs.longValue = 0L
        } else {
            clearSleepTimerState()
        }
        updateNotification(playing = AudioPlayback.isPlaying.value)
    }

    /** Clears the timer's state, here and in the UI. */
    private fun clearSleepTimerState() {
        sleepDeadlineAt = 0L
        sleepEndOfTrack = false
        AudioPlayback.sleepEndOfTrack.value = false
        AudioPlayback.sleepRemainingMs.longValue = 0L
        // The chosen option goes with it: a dialog still showing "15 minutes"
        // checked over a timer that has already fired is a control lying about
        // the past.
        AudioPlayback.sleepChoiceMinutes.value = null
        // Put the volume back: the fade that ends a session leaves the player
        // silent, and the next thing played must not inherit it.
        val mp = player
        if (mp != null) runCatching { mp.setVolume(1f, 1f) }
    }

    /**
     * One second of the sleep timer: report it, fade toward it, and end the
     * session when it arrives.
     */
    private fun tickSleepTimer() {
        if (sleepDeadlineAt == 0L) return
        val remaining = sleepDeadlineAt - SystemClock.elapsedRealtime()
        if (remaining <= 0L) {
            // Out of time. The fade has already brought the volume down over
            // the last few seconds; this is silence and then a clean teardown,
            // leaving nothing that could resume when the phone is picked up.
            Log.d(TAG, "SLEEP_TIMER_FIRED")
            stopPlayback()
            return
        }
        AudioPlayback.sleepRemainingMs.longValue = remaining
        val mp = player
        if (mp != null && AudioPlayback.isPlaying.value) {
            val volume = SleepTimer.fadeVolume(remaining)
            runCatching { mp.setVolume(volume, volume) }
        }
    }

    // ── Notification ───────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.audio_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.audio_notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(playing: Boolean): android.app.Notification {
        val playPauseAction = if (playing) {
            NotificationCompat.Action(
                R.drawable.ic_media_pause,
                getString(R.string.audio_notification_pause),
                pendingService(ACTION_PAUSE, 0)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_media_play,
                getString(R.string.audio_notification_play),
                pendingService(ACTION_RESUME, 1)
            )
        }
        val stopAction = NotificationCompat.Action(
            R.drawable.ic_audio_notification,
            getString(R.string.audio_notification_stop),
            pendingService(ACTION_STOP, 2)
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val durationSec = (AudioPlayback.durationMs.longValue / 1000L).toInt().coerceAtLeast(0)
        val posSec = (AudioPlayback.positionMs.longValue / 1000L).toInt().coerceAtLeast(0)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clearview)
            .setContentTitle(currentTitle)
            .setContentText(notificationSubtitle())
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setProgress(durationSec, posSec, durationSec <= 0)
            .setLargeIcon(currentThumb)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .addAction(playPauseAction)
            .addAction(stopAction)
            .build()
    }

    /**
     * The notification's second line: what is playing, and — when the reader
     * has one running — how long is left of it.
     *
     * The countdown belongs here as much as on the pill in the app: this is the
     * screen people look at when they set a sleep timer and put the phone down,
     * and a timer with nothing on screen counting it down is indistinguishable
     * from one that was never set.
     */
    private fun notificationSubtitle(): String = when {
        AudioPlayback.buffering.value -> getString(R.string.audio_notification_buffering)
        sleepEndOfTrack -> getString(
            R.string.audio_notification_sleep_end_of_track,
            currentChannel
        )
        sleepDeadlineAt > 0L && AudioPlayback.sleepRemainingMs.longValue > 0L -> getString(
            R.string.audio_notification_sleep_remaining,
            currentChannel,
            SleepTimer.formatRemaining(AudioPlayback.sleepRemainingMs.longValue)
        )
        else -> currentChannel
    }

    private fun pendingService(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AudioPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun updateNotification(playing: Boolean) {
        val notification = buildNotification(playing)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+): the foreground
            // service still runs; there is just no notification to update.
        }
    }

    /**
     * Progress-and-countdown refresh (throttled to the 1 s ticker, so never
     * rebuilt faster than that).
     *
     * The play/pause action is built from the REAL state rather than assumed:
     * this is called while paused too, whenever a sleep countdown is running,
     * and a paused player offering a Pause button is a control that does
     * nothing when tapped.
     */
    private fun notifyProgress(posMs: Long) {
        val durationSec = (AudioPlayback.durationMs.longValue / 1000L).toInt().coerceAtLeast(0)
        if (durationSec <= 0) return
        val notification = buildNotification(playing = AudioPlayback.isPlaying.value)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // no-op (see updateNotification)
        }
    }

    private fun loadLocalThumb(thumbPath: String) {
        Thread {
            val bmp = runCatching {
                val f = File(cacheDir, "thumbnails/$thumbPath")
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            }.getOrNull()
            if (bmp != null) {
                currentThumb = bmp
                handler.post { updateNotification(playing = AudioPlayback.isPlaying.value) }
            }
        }.start()
    }

    /**
     * The large icon for a stream, out of the app's shared image cache.
     *
     * The same URL the feed card already showed, so this is normally a memory
     * hit — the alternative (a private download in the service) would fetch the
     * bytes a second time for a picture already on screen a moment ago.
     */
    private fun loadRemoteArt(url: String) {
        scope.launch {
            val bmp = runCatching { ThumbnailCache.get(this@AudioPlaybackService, url, ART_WIDTH_PX) }
                .getOrNull() ?: return@launch
            handler.post {
                currentThumb = bmp
                updateNotification(playing = AudioPlayback.isPlaying.value)
            }
        }
    }

    // ── Media session state (lock screen / system media UI) ────────

    private fun setPlaybackState(playing: Boolean, positionMs: Long) {
        val state = when {
            AudioPlayback.buffering.value -> PlaybackStateCompat.STATE_BUFFERING
            playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                // Report the live speed so lock-screen / system media UI shows
                // the actual rate while playing.
                .setState(state, positionMs, AudioPlayback.speed.value)
                .build()
        )
    }

    // ── Audio focus (polite media playback) ────────────────────────

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            else -> Unit
        }
    }

    private fun requestAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }

    private fun abandonAudioFocus() {
        audioManager?.abandonAudioFocus(focusListener)
        audioManager = null
    }
}
