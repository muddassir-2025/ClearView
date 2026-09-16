package com.muddassir.clearview.goodpost.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostHomeViewModel
import com.muddassir.clearview.goodpost.GoodPostPhase
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel

/**
 * The Good Post tab (§4).
 *
 * This is the app's ONLY authentication gate (§2): the Quran, Media, Feed and
 * Block tabs never call into anything here, so a user who ignores Good Post is
 * never asked to create an account. The gate is expressed as one `when` over
 * an explicit phase, which is the whole reason the phase is a type rather than
 * a set of booleans.
 *
 * Channels and Discover are behind the gate (M2); the aggregated posts feed
 * arrives in M3. The sign-in flow itself is what this file owns.
 */
@Composable
fun GoodPostTab(
    viewModel: GoodPostViewModel = viewModel(),
    /**
     * A channel slug carried by a share link (§6), or null.
     *
     * Owned by MainActivity and cleared through [onOpenChannelHandled] once the
     * home ViewModel has taken it, so nothing can replay the same link.
     */
    openChannelSlug: String? = null,
    onOpenChannelHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    LaunchedEffect(Unit) { viewModel.initialize(context) }

    val state = viewModel.uiState
    when (state.phase) {
        GoodPostPhase.Checking -> CenteredProgress()

        GoodPostPhase.NotConfigured -> Notice(
            icon = { Icon(Icons.Filled.CloudOff, contentDescription = null) },
            title = stringResource(R.string.goodpost_not_configured_title),
            note = stringResource(R.string.goodpost_not_configured_note),
            actionLabel = stringResource(R.string.goodpost_retry),
            onAction = viewModel::refreshSession
        )

        GoodPostPhase.Phone -> PhoneStep(state, viewModel, activity)

        GoodPostPhase.Code -> CodeStep(state, viewModel)

        GoodPostPhase.Register -> RegisterStep(state, viewModel)

        GoodPostPhase.SignedIn -> {
            // A second ViewModel owns the signed-in surface. The gate VM keeps
            // owning the session, so the two never disagree about who is signed
            // in, and the home screen cannot sign anyone out by accident.
            val home: GoodPostHomeViewModel = viewModel()

            LaunchedEffect(Unit) { home.initialize(context) }

            // A share link opens that channel. Requesting is safe regardless of
            // which of these two effects runs first, because the home ViewModel
            // holds a slug it cannot act on yet. The request is consumed
            // immediately so a rotation cannot open the channel twice.
            LaunchedEffect(openChannelSlug) {
                val slug = openChannelSlug ?: return@LaunchedEffect
                home.requestOpenChannel(slug)
                onOpenChannelHandled()
            }

            // A session revoked server-side (ban, forced logout, reuse
            // detection) is noticed by the first home request, which then asks
            // the gate to re-check rather than leaving the user on a screen
            // whose every action will fail.
            LaunchedEffect(home.uiState.signedOut) {
                if (home.uiState.signedOut) {
                    // The session is gone, so the cached lists are the previous
                    // account's follows and posts. Dropped here rather than on
                    // the next sign-in, so they do not sit on disk in between.
                    home.clearCaches()
                    viewModel.refreshSession()
                }
            }

            GoodPostHome(
                state = home.uiState,
                accountName = state.account?.displayName.orEmpty(),
                onSignOut = {
                    home.clearCaches()
                    viewModel.signOut()
                },
                viewModel = home
            )
        }
    }
}

// ── Steps ───────────────────────────────────────────────────────────────

@Composable
private fun PhoneStep(
    state: GoodPostUiState,
    viewModel: GoodPostViewModel,
    activity: android.app.Activity?
) {
    Step(
        title = stringResource(R.string.goodpost_phone_title),
        note = stringResource(R.string.goodpost_phone_note),
        error = state.messageCode,
        busy = state.busy
    ) {
        OutlinedTextField(
            value = state.phone,
            onValueChange = viewModel::onPhoneChange,
            label = { Text(stringResource(R.string.goodpost_phone_label)) },
            placeholder = { Text(stringResource(R.string.goodpost_phone_placeholder)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { activity?.let(viewModel::startVerification) }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { activity?.let(viewModel::startVerification) },
            // Firebase needs a host Activity for reCAPTCHA; without one the
            // request would fail deep inside the SDK, so block it here.
            enabled = !state.busy && activity != null && state.phone.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.goodpost_send_code))
        }

        Spacer(Modifier.height(12.dp))

        PrivacyNote()
    }
}

@Composable
private fun CodeStep(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    Step(
        title = stringResource(R.string.goodpost_code_title),
        note = stringResource(R.string.goodpost_code_note, state.phone),
        error = state.messageCode,
        busy = state.busy
    ) {
        OutlinedTextField(
            value = state.code,
            onValueChange = viewModel::onCodeChange,
            label = { Text(stringResource(R.string.goodpost_code_label)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.submitCode() }),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = viewModel::submitCode,
            enabled = !state.busy && state.code.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.goodpost_verify))
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = viewModel::backToPhone,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.goodpost_change_number))
        }
    }
}

@Composable
private fun RegisterStep(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    Step(
        title = stringResource(R.string.goodpost_register_title),
        note = stringResource(R.string.goodpost_register_note),
        error = state.messageCode,
        busy = state.busy
    ) {
        OutlinedTextField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            label = { Text(stringResource(R.string.goodpost_name_label)) },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = { Text(stringResource(R.string.goodpost_email_label)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.submitRegistration() }),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = viewModel::submitRegistration,
            enabled = !state.busy && state.displayName.isNotBlank() && state.email.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.goodpost_create_account))
        }

        Spacer(Modifier.height(12.dp))

        PrivacyNote()
    }
}

// ── Building blocks ─────────────────────────────────────────────────────

/** Scrollable title / note / content / error column shared by every step. */
@Composable
private fun Step(
    title: String,
    note: String,
    error: String?,
    busy: Boolean,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Campaign,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        if (note.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))

        content()

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            ErrorNotice(error)
        }

        if (busy) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.goodpost_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PrivacyNote() {
    Text(
        text = stringResource(R.string.goodpost_privacy_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

