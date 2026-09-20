package com.muddassir.clearview.media.data

import com.muddassir.clearview.media.model.MediaPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Health of one upstream media provider, for the Media tab's status line. */
enum class MediaSourceState { OK, THROTTLED, FAILING }

/**
 * Why a provider is currently not answering.
 *
 * @property detail what the provider said (kept short and user-facing).
 * @property sinceEpochMillis when this state was first recorded.
 * @property retryAtEpochMillis when a THROTTLED provider should be readable
 *                              again (0 = unknown).
 */
data class MediaSourceStatus(
    val platform: MediaPlatform,
    val state: MediaSourceState,
    val detail: String,
    val sinceEpochMillis: Long,
    val retryAtEpochMillis: Long = 0L
)

/**
 * Remembers why a provider is not answering, so the Media tab can say so instead
 * of silently showing an empty feed ("I added the channel and see nothing").
 *
 * Process-wide and observable: the sources and the repository write to it from
 * IO threads while the tab reads it during composition. It holds the LAST
 * outcome per platform — a success clears the entry, a throttle is kept while
 * its window is open, and an old failure is ignored so a single blip cannot
 * leave a permanent warning on screen.
 */
object MediaSourceStatusStore {

    /**
     * How long a plain failure keeps being shown. A refresh runs every few
     * minutes, so a failure that is still relevant refreshes its own timestamp;
     * anything older than this has been superseded and must not linger.
     */
    private const val FAILURE_TTL_MS = 30 * 60 * 1000L

    private val _statuses = MutableStateFlow<Map<MediaPlatform, MediaSourceStatus>>(emptyMap())

    /** The current outcome per provider (empty = everything is healthy). */
    val statuses: StateFlow<Map<MediaPlatform, MediaSourceStatus>> = _statuses.asStateFlow()

    /** The provider answered — it is healthy, so drop any warning about it. */
    fun markOk(platform: MediaPlatform) {
        _statuses.update { current -> if (platform in current) current - platform else current }
    }

    /**
     * The provider is rate-limiting the install; it should answer again at
     * [retryAtEpochMillis]. A throttle outranks a plain failure, so a later
     * "nothing came back" never downgrades it into a vague error.
     */
    fun markThrottled(
        platform: MediaPlatform,
        retryAtEpochMillis: Long,
        detail: String = "${platform.displayName()} is rate-limiting this device"
    ) {
        _statuses.update { current ->
            val existing = current[platform]
            if (existing?.state == MediaSourceState.THROTTLED &&
                existing.retryAtEpochMillis >= retryAtEpochMillis
            ) {
                // Keep the window we already published (same or longer).
                current
            } else {
                current + (
                    platform to MediaSourceStatus(
                        platform = platform,
                        state = MediaSourceState.THROTTLED,
                        detail = detail,
                        sinceEpochMillis = System.currentTimeMillis(),
                        retryAtEpochMillis = retryAtEpochMillis
                    )
                )
            }
        }
    }

    /** Nothing came back and no rate limit was reported. */
    fun markFailing(platform: MediaPlatform, detail: String = "${platform.displayName()} isn't responding") {
        _statuses.update { current ->
            val existing = current[platform]
            // A live throttle is more precise than "it failed" — keep it until
            // its window closes.
            if (existing?.state == MediaSourceState.THROTTLED &&
                existing.retryAtEpochMillis > System.currentTimeMillis()
            ) {
                current
            } else {
                current + (
                    platform to MediaSourceStatus(
                        platform = platform,
                        state = MediaSourceState.FAILING,
                        detail = detail,
                        sinceEpochMillis = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /** Forgets every warning (used by tests, and when nothing is subscribed). */
    fun clear() {
        _statuses.value = emptyMap()
    }

    /** The issues still worth showing at [now], most explanatory first. */
    fun issues(now: Long = System.currentTimeMillis()): List<MediaSourceStatus> =
        _statuses.value.values
            .filter { status ->
                when (status.state) {
                    MediaSourceState.OK -> false
                    MediaSourceState.THROTTLED -> true
                    MediaSourceState.FAILING -> now - status.sinceEpochMillis < FAILURE_TTL_MS
                }
            }
            .sortedWith(
                compareBy(
                    { it.state != MediaSourceState.THROTTLED },
                    { it.platform.ordinal }
                )
            )

    /**
     * The single line the Media header shows for [status]: a throttle says how
     * long the wait is, a failure says what the user can do about it.
     */
    fun describe(status: MediaSourceStatus, now: Long = System.currentTimeMillis()): String =
        when (status.state) {
            MediaSourceState.OK -> ""
            MediaSourceState.THROTTLED -> {
                val remaining = status.retryAtEpochMillis - now
                when {
                    remaining <= 0L -> "${status.platform.displayName()} is rate-limiting this device — retrying now"
                    remaining < 60_000L -> "${status.platform.displayName()} is rate-limiting this device — retrying shortly"
                    else -> "${status.platform.displayName()} is rate-limiting this device — retrying in ${remaining / 60_000L}m"
                }
            }
            MediaSourceState.FAILING -> "${status.platform.displayName()} isn't responding — pull to refresh to retry"
        }
}

/** The provider's name as it appears in the status line. */
fun MediaPlatform.displayName(): String = when (this) {
    MediaPlatform.YOUTUBE -> "YouTube"
    MediaPlatform.INSTAGRAM -> "Instagram"
    MediaPlatform.X -> "X"
}
