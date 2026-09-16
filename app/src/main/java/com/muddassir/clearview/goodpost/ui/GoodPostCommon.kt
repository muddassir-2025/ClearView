package com.muddassir.clearview.goodpost.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostError
import com.muddassir.clearview.goodpost.goodPostErrorFor

/**
 * Building blocks shared by the sign-in screens and the signed-in Good Post
 * screens.
 *
 * These live here rather than in either screen file so there is exactly one
 * error mapping. Every [GoodPostError] must be worded somewhere, and two
 * copies would eventually disagree — one of them quietly wrong for the case
 * nobody tested.
 */

/**
 * Every way Good Post can fail, as a resource id.
 *
 * Exhaustive `when` with no `else`: adding an enum value becomes a compile
 * error here, which is the point. A missing branch would otherwise fall
 * through to a generic message and hide the fact that a new backend code was
 * never given wording.
 */
@StringRes
internal fun goodPostErrorMessage(error: GoodPostError): Int = when (error) {
    GoodPostError.InvalidPhone -> R.string.goodpost_error_invalid_phone
    GoodPostError.InvalidEmail -> R.string.goodpost_error_invalid_email
    GoodPostError.EmailNotRegistered -> R.string.goodpost_error_email_not_registered
    GoodPostError.EmailUnavailable -> R.string.goodpost_error_email_unavailable
    GoodPostError.PhoneBanned -> R.string.goodpost_error_phone_banned
    GoodPostError.AccountBanned -> R.string.goodpost_error_account_banned
    GoodPostError.AccountSuspended -> R.string.goodpost_error_account_suspended
    GoodPostError.RateLimited -> R.string.goodpost_error_rate_limited
    GoodPostError.OtpLocked -> R.string.goodpost_error_otp_locked
    GoodPostError.InvalidCode -> R.string.goodpost_error_invalid_code
    GoodPostError.VerificationExpired -> R.string.goodpost_error_verification_expired
    GoodPostError.NumberRejected -> R.string.goodpost_error_number_rejected
    GoodPostError.VerificationUnavailable -> R.string.goodpost_error_verification_unavailable
    GoodPostError.SmsQuota -> R.string.goodpost_error_sms_quota
    GoodPostError.VerificationFailed -> R.string.goodpost_error_verification_failed
    GoodPostError.EmailTaken -> R.string.goodpost_error_email_taken
    GoodPostError.PhoneTaken -> R.string.goodpost_error_phone_taken
    GoodPostError.AccountExists -> R.string.goodpost_error_account_exists
    GoodPostError.Unreachable -> R.string.goodpost_error_unreachable
    GoodPostError.NotConfigured -> R.string.goodpost_not_configured_title
    GoodPostError.ServerError -> R.string.goodpost_error_server
    GoodPostError.ChannelLimit -> R.string.goodpost_error_channel_limit
    GoodPostError.ChannelNotFound -> R.string.goodpost_error_channel_not_found
    GoodPostError.ChannelForbidden -> R.string.goodpost_error_channel_forbidden
    GoodPostError.ChannelUnavailable -> R.string.goodpost_error_channel_unavailable
    GoodPostError.ChannelBlocked -> R.string.goodpost_error_channel_blocked
    GoodPostError.CannotFollowOwnChannel -> R.string.goodpost_error_follow_own
    GoodPostError.NotFollowing -> R.string.goodpost_error_not_following
    GoodPostError.InvalidInput -> R.string.goodpost_error_invalid_input
    GoodPostError.PostForbidden -> R.string.goodpost_error_post_forbidden
    GoodPostError.PostNotFound -> R.string.goodpost_error_post_not_found
    GoodPostError.PostEmpty -> R.string.goodpost_error_post_empty
    GoodPostError.PostTooLong -> R.string.goodpost_error_post_too_long
    GoodPostError.PostBadLink -> R.string.goodpost_error_post_bad_link
    GoodPostError.PostEditClosed -> R.string.goodpost_error_post_edit_closed
    GoodPostError.PostMediaRejected -> R.string.goodpost_error_post_media
    GoodPostError.MediaUnavailable -> R.string.goodpost_error_media_unavailable
    GoodPostError.FileUnreadable -> R.string.goodpost_error_file_unreadable
    GoodPostError.Unknown -> R.string.goodpost_error_unknown
}

/**
 * Render a backend code as something a person can act on.
 *
 * The code itself is never shown. It is deliberately machine-readable, and
 * `channel_unavailable` teaches a user nothing; [goodPostErrorFor] decides
 * which explanation applies, and an unrecognised code lands on a generic
 * message rather than a confidently wrong one.
 */
@Composable
internal fun ErrorNotice(code: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(goodPostErrorMessage(goodPostErrorFor(code))),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
internal fun CenteredProgress() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** A full-screen explanation with a single action — empty states and failures. */
@Composable
internal fun Notice(
    icon: @Composable () -> Unit,
    title: String,
    note: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon()
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}
