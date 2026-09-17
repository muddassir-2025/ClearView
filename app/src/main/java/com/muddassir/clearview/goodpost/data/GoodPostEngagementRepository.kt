package com.muddassir.clearview.goodpost.data

import android.content.Context

/**
 * The outcome of an engagement call.
 *
 * No `Stale` case, unlike the list repositories, and that is the point: a
 * reaction, a vote and a view are all WRITES. There is nothing saved to fall
 * back on — showing a saved reaction count next to a button the user just
 * tapped would be presenting the number as the result of their tap, which is
 * exactly the claim §36 forbids.
 */
sealed interface EngagementResult<out T> {
    data class Ok<T>(val value: T) : EngagementResult<T>

    /** The server refused, with a code the UI words. */
    data class Failed(val code: String) : EngagementResult<Nothing>

    /** No usable session — the sign-in gate takes over. */
    data object SignedOut : EngagementResult<Nothing>
}

/**
 * Reactions, polls, views and channel analytics (§13, §14, §15).
 *
 * Its own repository rather than more methods on [GoodPostPostsRepository],
 * because the session handling is the only thing the two share: reading posts
 * is cached and paged, while everything here is a single write whose answer
 * comes straight back. Mixing them would give one class two caching policies
 * and one place for the wrong one to be applied.
 *
 * Every method reports what the SERVER said. A reaction count is never
 * incremented locally — two people reacting at once would leave the number
 * wrong until the next refresh, and a view is only counted when the server's
 * dedupe window has actually elapsed.
 */
class GoodPostEngagementRepository(
    context: Context,
    private val auth: GoodPostAuthRepository = GoodPostAuthRepository(context),
    private val api: GoodPostApi = GoodPostApi()
) {

    /** §13 set, change or clear the caller's reaction. */
    suspend fun react(postId: String, reaction: String?): EngagementResult<GoodPostReactionState> {
        val session = auth.validSession() ?: return EngagementResult.SignedOut

        return when (val result = api.setReaction(session.accessToken, postId, reaction)) {
            is ApiResult.Ok -> EngagementResult.Ok(
                GoodPostEngagementCodec.reactionState(result.value)
            )

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> EngagementResult.Failed("unreachable")
        }
    }

    /** §14 vote, or change a vote. The fresh aggregate comes back. */
    suspend fun vote(pollId: String, optionIds: List<String>): EngagementResult<GoodPostPoll> {
        val session = auth.validSession() ?: return EngagementResult.SignedOut

        return when (val result = api.votePoll(session.accessToken, pollId, optionIds)) {
            is ApiResult.Ok ->
                GoodPostEngagementCodec.pollFrom(result.value)
                    ?.let { EngagementResult.Ok(it) }
                    // A 2xx we cannot parse is a contract break, not a success.
                    ?: EngagementResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> EngagementResult.Failed("unreachable")
        }
    }

    /**
     * §15 tell the server a post was opened.
     *
     * Best-effort by design: the caller ignores a failure, because a view that
     * could not be counted must not put an error in front of someone who was
     * only reading. [GoodPostViewState.counted] is how the caller knows whether
     * the look actually moved the number.
     */
    suspend fun recordView(postId: String): EngagementResult<GoodPostViewState> {
        val session = auth.validSession() ?: return EngagementResult.SignedOut

        return when (val result = api.recordView(session.accessToken, postId)) {
            is ApiResult.Ok -> EngagementResult.Ok(
                GoodPostEngagementCodec.viewState(result.value)
            )

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> EngagementResult.Failed("unreachable")
        }
    }

    /**
     * §15 a channel's own analytics.
     *
     * `analytics_forbidden` for a follower is the expected answer, not a bug:
     * the server decides from the caller's role in `channel_admins`, and the UI
     * only offers the screen where the server would accept it.
     */
    suspend fun analytics(channelId: String, days: Int? = null): EngagementResult<GoodPostAnalytics> {
        val session = auth.validSession() ?: return EngagementResult.SignedOut

        return when (val result = api.analytics(session.accessToken, channelId, days)) {
            is ApiResult.Ok ->
                GoodPostEngagementCodec.analytics(result.value)
                    ?.let { EngagementResult.Ok(it) }
                    ?: EngagementResult.Failed("unreachable")

            is ApiResult.Failed -> failure(result.status, result.code)
            ApiResult.Unreachable -> EngagementResult.Failed("unreachable")
        }
    }

    /**
     * 401 means the session died between the refresh check and this call, so
     * the gate takes over. Everything else is passed through as a code for the
     * UI to word — `not_following` and `poll_closed` each need their own
     * explanation rather than a sign-in screen.
     */
    private fun failure(status: Int, code: String): EngagementResult<Nothing> =
        if (status == 401) EngagementResult.SignedOut else EngagementResult.Failed(code)
}
