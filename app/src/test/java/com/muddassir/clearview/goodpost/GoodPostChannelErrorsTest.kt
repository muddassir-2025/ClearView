package com.muddassir.clearview.goodpost

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The M2 addition to the error vocabulary (§7, §12).
 *
 * Each of these codes is real — they are what the channel routes return — and
 * each one needs a different sentence in front of a user. A code that fell
 * through to `Unknown` would tell someone "something went wrong" when the
 * server had in fact told us exactly what happened and what to do about it.
 */
class GoodPostChannelErrorsTest {

    @Test
    fun `maps the channel lifecycle codes`() {
        assertEquals(GoodPostError.ChannelLimit, goodPostErrorFor("channel_limit_reached"))
        assertEquals(GoodPostError.ChannelNotFound, goodPostErrorFor("channel_not_found"))
        assertEquals(GoodPostError.ChannelForbidden, goodPostErrorFor("channel_forbidden"))
        assertEquals(GoodPostError.ChannelUnavailable, goodPostErrorFor("channel_unavailable"))
        assertEquals(GoodPostError.ChannelBlocked, goodPostErrorFor("channel_blocked"))
    }

    @Test
    fun `maps the follow and mute codes`() {
        assertEquals(
            GoodPostError.CannotFollowOwnChannel,
            goodPostErrorFor("cannot_follow_own_channel")
        )
        assertEquals(GoodPostError.NotFollowing, goodPostErrorFor("not_following"))
    }

    @Test
    fun `maps the input codes that a client fix can resolve`() {
        // These are all "the value or the cursor was wrong", which is one
        // instruction to the user, so they share one entry.
        assertEquals(GoodPostError.InvalidInput, goodPostErrorFor("invalid_category"))
        assertEquals(GoodPostError.InvalidInput, goodPostErrorFor("invalid_country"))
        assertEquals(GoodPostError.InvalidInput, goodPostErrorFor("empty_update"))
        assertEquals(GoodPostError.InvalidInput, goodPostErrorFor("invalid_channel_id"))
        assertEquals(GoodPostError.InvalidInput, goodPostErrorFor("invalid_cursor"))
    }

    @Test
    fun `still maps the identity codes it always did`() {
        // A regression guard: the channel codes were added to the same `when`,
        // and an earlier branch is easy to displace by accident.
        assertEquals(GoodPostError.PhoneBanned, goodPostErrorFor("phone_banned"))
        assertEquals(GoodPostError.AccountBanned, goodPostErrorFor("account_banned"))
        assertEquals(GoodPostError.AccountSuspended, goodPostErrorFor("account_suspended"))
        assertEquals(GoodPostError.RateLimited, goodPostErrorFor("rate_limited"))
        assertEquals(GoodPostError.VerificationFailed, goodPostErrorFor("otp_required"))
        assertEquals(GoodPostError.ServerError, goodPostErrorFor("http_error"))
    }

    @Test
    fun `does not invent a meaning for a code it does not know`() {
        // The backend moving ahead of this client is a real possibility. Saying
        // so is better than guessing, and far better than treating it as
        // success.
        assertEquals(GoodPostError.Unknown, goodPostErrorFor("some_new_code"))
        assertEquals(GoodPostError.Unknown, goodPostErrorFor(""))
    }

    @Test
    fun `keeps the transport codes distinct from server refusals`() {
        assertEquals(GoodPostError.Unreachable, goodPostErrorFor("unreachable"))
        assertEquals(GoodPostError.Unreachable, goodPostErrorFor("timeout"))
        assertEquals(GoodPostError.NotConfigured, goodPostErrorFor("not_configured"))
    }
}
