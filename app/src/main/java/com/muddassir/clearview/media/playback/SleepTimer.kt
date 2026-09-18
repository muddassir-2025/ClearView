package com.muddassir.clearview.media.playback

/**
 * The sleep timer as arithmetic, rather than as UI or as a field on a service.
 *
 * Both ends of it need the same two answers — how long is left, and how loud
 * should it be right now — and they need them to agree: the countdown is shown
 * on a pill while the volume is being lowered by the player, and a second
 * between them is a second of audio at the wrong level.
 *
 * Kept free of Android so the awkward half is testable. The fade in particular
 * is the kind of thing that is wrong in a way nobody notices until they are
 * lying in the dark listening to something that will not stop.
 */
object SleepTimer {

    /**
     * The choices offered, in minutes.
     *
     * Five through an hour in the steps people actually want: the short end is
     * "one more video", the long end is a whole talk or a night's reading. They
     * are choices rather than a free field because a sleep timer is set in the
     * dark, by feel, and a number pad there is a worse tool than four taps.
     */
    val MINUTES = listOf(5, 10, 15, 30, 45, 60)

    /**
     * How long the volume takes to reach silence at the end.
     *
     * Long enough to be a fade rather than a cut — audio stopping dead is a
     * small shock in a quiet room, which is the opposite of the point — and
     * short enough that nobody is awake waiting for it.
     */
    const val FADE_MS = 3_000L

    /**
     * "12:30", or "1:02:30" past an hour.
     *
     * ROUNDED UP to the whole second, which matters more than it looks: a timer
     * set to five minutes holds 299,999 ms a millisecond after it is set, and
     * truncating would show "4:59" the instant the reader chose "5 minutes" —
     * a control that appears to have ignored them. Rounding up means the first
     * thing drawn is the time they asked for, and the last second still reads
     * "0:01" rather than "0:00".
     */
    fun formatRemaining(ms: Long): String {
        // Integer ceiling division, written as `(n-1)/d + 1` rather than
        // `(n+999)/1000`: the additive form overflows to a NEGATIVE total for an
        // absurdly large remaining value, and a countdown that suddenly reads
        // "-1:-51" is worse than one that is merely wrong.
        val totalSeconds = if (ms <= 0L) 0L else (ms - 1L) / 1000L + 1L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /**
     * The volume multiplier for [remainingMs] left on the timer: 1 until the
     * last [FADE_MS], falling linearly to 0 at the deadline.
     *
     * A deadline the ticker overshoots yields a negative remainder on the way
     * out (the player checks the same number for expiry a moment later), and
     * that has to read as silence rather than as a negative volume.
     */
    fun fadeVolume(remainingMs: Long): Float {
        if (remainingMs <= 0L) return 0f
        if (remainingMs >= FADE_MS) return 1f
        return remainingMs.toFloat() / FADE_MS.toFloat()
    }

    /**
     * The pill's label: the countdown, or "Off".
     *
     * [remainingMs] of 0 means no timer is set — the same thing the service
     * reports, so there is one definition of "off" rather than two.
     */
    fun labelFor(remainingMs: Long): String =
        if (remainingMs > 0L) formatRemaining(remainingMs) else "Off"
}
