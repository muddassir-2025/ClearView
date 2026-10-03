package com.muddassir.clearview.brainrot

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tells the UI that a block was recorded outside it.
 *
 * A personal block can happen in two places that never see each other: the
 * Blocking tab (the user tapping Add) and the accessibility service (the user
 * tapping "Not interested" in YouTube). Both write to the same files, but only
 * the first one is holding the lists the UI is drawn from — so without this, a
 * channel blocked from YouTube would not appear until the tab was rebuilt, and
 * "the app did it and I did not see it" is exactly the confusion this whole
 * feature exists to remove.
 *
 * ## Why a bus rather than a broadcast
 *
 * The service and the UI are in the SAME process, so an in-process callback is
 * sufficient and has none of a system broadcast's problems: an implicit
 * broadcast leaks state across apps, can be observed by anything on the device,
 * and on modern Android needs a receiver registered for the right flags. None of
 * that is warranted to say "the list changed".
 *
 * Listeners are held weakly-equivalent (removed on teardown) and every call is
 * guarded, so a listener that throws cannot stop the others or disturb the block
 * that triggered it — reporting must never affect enforcement.
 */
object BrainRotRefreshBus {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Something changed; ask every listener to re-read its state. */
    fun notifyChanged() {
        listeners.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                // Swallowed on purpose: the block has already been written, and
                // a UI listener failing must not look like the block failing.
            }
        }
    }
}
