package com.muddassir.clearview.media.util

/**
 * What to say after emptying Continue Watching ([cleared] is the count the store
 * reports). The row simply disappears, so without a word the tap reads as
 * ambiguous — and "already empty" is worth saying rather than leaving the reader
 * to wonder whether it worked at all.
 */
fun continueWatchingResetMessage(cleared: Int): String = when {
    cleared <= 0 -> "Continue Watching was already empty"
    cleared == 1 -> "Cleared 1 resume position"
    else -> "Cleared $cleared resume positions"
}
