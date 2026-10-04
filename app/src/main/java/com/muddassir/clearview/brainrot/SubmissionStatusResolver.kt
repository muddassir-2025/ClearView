package com.muddassir.clearview.brainrot

/**
 * How one of this device's requests to the global blocklist should be read.
 *
 * A submission's status is a record of the decision an administrator made; it is
 * not a claim about what is blocking anyone right now. A rule that was approved
 * and later removed keeps reporting "approved" from the server forever, so the
 * only way to tell "still in force" from "since removed" is to compare the
 * status against the live rule set — which arrives separately (the cached global
 * rules) and can be behind.
 *
 * That comparison is deliberately two pure functions rather than a branch buried
 * in the ViewModel: the rule is "approved but no longer global reads as removed",
 * it is the kind of thing that is easy to get subtly wrong, and a plain function
 * can be tested exhaustively without a device.
 *
 * `liveGlobally` must be FALSE only when we are sure: a request is live unless we
 * have a synced rules snapshot that does not contain it. The caller decides that
 * (an unsynced cache reports live), so a missed sync can never relabel a real
 * rule — at worst it shows a removed one as approved for a moment longer.
 */
object SubmissionStatusResolver {

    /** The wire/UI status a request gets once its rule is gone. */
    const val REMOVED = "removed"

    /**
     * The status to DISPLAY for a request row.
     *
     * An approved request whose rule is no longer global reads "removed" instead
     * of the stale "approved", so the screen never claims something is blocked
     * for everyone when it is not.
     */
    fun display(status: String, liveGlobally: Boolean): String =
        if (status == "approved" && !liveGlobally) REMOVED else status

    /**
     * The status to REASON about — where a request stands for the purpose of
     * offering the Send action again.
     *
     * Returns null (treated as "never sent") for a removed request, alongside the
     * existing rule that a rejected one is not a reason to stop asking: a term
     * pulled from the blocklist is exactly the kind of thing worth re-requesting.
     */
    fun effective(status: String, liveGlobally: Boolean): String? =
        if (status == "approved" && !liveGlobally) null else status

    /** True when a request was approved and has since been removed. */
    fun isRemoved(status: String, liveGlobally: Boolean): Boolean =
        status == "approved" && !liveGlobally
}
