package com.muddassir.clearview.goodpost.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostAuthMethod
import com.muddassir.clearview.goodpost.GoodPostHomeViewModel
import com.muddassir.clearview.goodpost.GoodPostPhase
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel

/**
 * The Good Post tab (§4).
 *
 * This is the app's ONLY authentication gate (§2): the Quran, Media, Feed and
 * Block tabs never call into anything here, so a user who ignores Good Post is
 * never asked to create an account. The gate is one `when` over an explicit
 * phase, which is the whole reason the phase is a type rather than a set of
 * booleans.
 *
 * The steps are laid out like a messaging app's registration: one question per
 * screen, a green action at the bottom of the content, and the privacy note
 * where someone will actually read it — under the field, not in a menu.
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

        GoodPostPhase.NotConfigured -> NotConfiguredStep(onRetry = viewModel::refreshSession)

        GoodPostPhase.Entry -> EntryStep(state, viewModel, activity)

        GoodPostPhase.Code -> CodeStep(state, viewModel, activity)

        GoodPostPhase.EmailRegister -> EmailRegisterStep(state, viewModel)

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
                    // Unregistered FIRST, while a session still exists to
                    // authenticate the call. A device that keeps this account's
                    // push address after a sign-out would ring for the previous
                    // user, which is a privacy leak and not merely noise (§17).
                    home.unregisterPushToken()
                    home.clearCaches()
                    viewModel.signOut()
                },
                viewModel = home
            )
        }
    }
}

// ── Steps ───────────────────────────────────────────────────────────────

/**
 * The way in: a mobile number or an email address.
 *
 * One screen for both because they are the same step — claim a code, then type
 * it — and only the field differs. Two near-identical screens would be two
 * places for the toggle, the busy state and the error line to drift apart.
 */
@Composable
private fun EntryStep(
    state: GoodPostUiState,
    viewModel: GoodPostViewModel,
    activity: android.app.Activity?
) {
    val byEmail = state.authMethod == GoodPostAuthMethod.Email
    val canSubmit = !state.busy &&
        activity != null &&
        if (byEmail) state.signInEmail.isNotBlank() else state.phone.isNotBlank()

    // One address, one code, and — if that address has no account — one more
    // question on the next screen. The button says "continue" rather than
    // "sign in", because the app genuinely does not know yet which of the two
    // it is about to do, and a label that guesses would be wrong half the time.

    WaStep(
        title = stringResource(
            if (byEmail) R.string.goodpost_email_title else R.string.goodpost_phone_title
        ),
        note = stringResource(
            if (byEmail) R.string.goodpost_email_note else R.string.goodpost_phone_note
        ),
        errorCode = state.messageCode,
        busy = state.busy
    ) {
        // ── Mobile method: PAUSED ───────────────────────────────────────
        //
        // The whole phone path — Firebase, the SMS challenge, the phone-hash
        // ban identity — is still wired up and still used by the ViewModel. The
        // entry screen just does not offer it while Good Post is email-first.
        // Setting GoodPostViewModel.ENTRY_METHOD back to Mobile is the entire
        // change; this toggle and the field below are what return.
        if (ENTRY_METHOD_SELECTION_ENABLED) {
            MethodToggle(state.authMethod, viewModel::onAuthMethodChange)
            Spacer(Modifier.height(20.dp))
        }

        if (byEmail) {
            WaField(
                value = state.signInEmail,
                onValueChange = viewModel::onSignInEmailChange,
                label = stringResource(R.string.goodpost_signin_email_label),
                placeholder = stringResource(R.string.goodpost_signin_email_placeholder),
                enabled = !state.busy,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done,
                onDone = { if (!state.busy) viewModel.startEmailVerification() }
            )
        } else {
            WaField(
                value = state.phone,
                onValueChange = viewModel::onPhoneChange,
                label = stringResource(R.string.goodpost_phone_label),
                placeholder = stringResource(R.string.goodpost_phone_placeholder),
                enabled = !state.busy,
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
                onDone = { activity?.let(viewModel::startVerification) }
            )
        }

        Spacer(Modifier.height(24.dp))

        WaPrimaryButton(
            text = stringResource(
                if (byEmail) R.string.goodpost_send_code_email else R.string.goodpost_send_code
            ),
            enabled = canSubmit,
            // Firebase needs a host Activity for reCAPTCHA; without one the
            // request would fail deep inside the SDK, so it is blocked here.
            onClick = {
                if (byEmail) viewModel.startEmailVerification()
                else activity?.let(viewModel::startVerification)
            }
        )

        if (byEmail) {
            Spacer(Modifier.height(16.dp))
            // Said up front because the same field now does both jobs: a first
            // time visitor should not have to guess whether they are supposed
            // to be signing in or signing up, and the honest answer is "both".
            Text(
                text = stringResource(R.string.goodpost_email_only_note),
                color = Wa.TextDim,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(20.dp))
        PrivacyNote()
    }
}

/**
 * Whether the entry screen offers a choice of method.
 *
 * Derived from the ViewModel's one constant rather than set here, so the view
 * cannot pick a method the ViewModel would refuse to use.
 */
private val ENTRY_METHOD_SELECTION_ENABLED =
    GoodPostViewModel.ENTRY_METHOD != GoodPostAuthMethod.Email

/**
 * Two ways in, one selected.
 *
 * Drawn as pills rather than a segmented control because a segmented control's
 * heavy outline is the one Material shape that would stand out on this surface,
 * and the choice between "a number" and "an address" is a small one.
 */
@Composable
private fun MethodToggle(
    selected: GoodPostAuthMethod,
    onSelect: (GoodPostAuthMethod) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WaFilterPill(
            label = stringResource(R.string.goodpost_method_mobile),
            selected = selected == GoodPostAuthMethod.Mobile,
            onClick = { onSelect(GoodPostAuthMethod.Mobile) }
        )
        WaFilterPill(
            label = stringResource(R.string.goodpost_method_email),
            selected = selected == GoodPostAuthMethod.Email,
            onClick = { onSelect(GoodPostAuthMethod.Email) }
        )
    }
}

/**
 * The code screen: the address it went to, six boxes, and the ways out.
 *
 * The boxes are drawn by this composable and one invisible field behind them
 * owns the text. Six real focused fields would need the focus to be moved by
 * hand on every keystroke and backspace — which is where the classic
 * "backspace does not work in the second box" bug comes from — and the code is
 * one value to the ViewModel either way.
 */
@Composable
private fun CodeStep(
    state: GoodPostUiState,
    viewModel: GoodPostViewModel,
    activity: android.app.Activity?
) {
    val target = if (state.authMethod == GoodPostAuthMethod.Email) state.signInEmail else state.phone
    val focus = androidx.compose.runtime.remember { FocusRequester() }

    // The keyboard is the whole point of this screen, so it is opened for the
    // user rather than waiting to be tapped for.
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    WaStep(
        title = stringResource(R.string.goodpost_code_title),
        // "Sent to …" must name whichever channel was actually used — showing
        // the mobile number to someone who asked for an email would be a
        // plausible-looking line about the wrong thing.
        note = stringResource(R.string.goodpost_code_note, target),
        errorCode = state.messageCode,
        busy = state.busy
    ) {
        Text(
            text = target,
            color = Wa.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(16.dp))

        CodeBoxes(
            code = state.code,
            enabled = !state.busy,
            focusRequester = focus,
            onChange = viewModel::onCodeChange
        )

        Spacer(Modifier.height(24.dp))

        WaPrimaryButton(
            text = stringResource(R.string.goodpost_verify),
            enabled = !state.busy && state.code.isNotBlank(),
            busy = state.busy,
            onClick = viewModel::submitCode
        )

        Spacer(Modifier.height(12.dp))

        // A new code is a first-class action, not a detour.
        //
        // Without it the only way out was "use a different number", which reads
        // as "your number is wrong" — while the actual reason a code can stop
        // working is that the verification it belongs to expired. Asking again
        // from here re-claims a challenge and restarts Firebase, which is
        // exactly the remedy, and it keeps the typed number.
        Row(verticalAlignment = Alignment.CenterVertically) {
            WaTextAction(
                text = stringResource(R.string.goodpost_resend_code),
                enabled = !state.busy && activity != null,
                onClick = { activity?.let(viewModel::startVerification) }
            )
            Spacer(Modifier.weight(1f))
            WaTextAction(
                text = stringResource(R.string.goodpost_change_target),
                enabled = !state.busy,
                onClick = viewModel::backToEntry
            )
        }
    }
}

/**
 * Six digit boxes with one field behind them.
 *
 * The field is transparent but still real: it keeps the caret, the keyboard and
 * paste working exactly as a normal field does, which is what makes a code from
 * a screenshot paste correctly.
 */
@Composable
private fun CodeBoxes(
    code: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onChange: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(6) { index ->
                val digit = code.getOrNull(index)?.toString().orEmpty()
                val active = enabled && code.length == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Wa.Bar)
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) Wa.Accent else Wa.Divider,
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = digit,
                        color = Wa.Text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        BasicTextField(
            value = code,
            onValueChange = { raw ->
                // Filtered here rather than in the ViewModel: a pasted
                // "Your code is 654321" must not become a rejected code, and
                // the rule belongs with the boxes that show the result.
                onChange(raw.filter { it.isDigit() }.take(6))
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .focusRequester(focusRequester)
        )
    }
}

/**
 * Registration by email: the code was already right, the address has no account.
 *
 * This is the one screen in the flow that is genuinely a *creation*, which is
 * why it is its own step rather than a flag on the code screen: the code screen
 * is about proving an inbox, and mixing "and also make me an account" into it
 * would make the two indistinguishable at the point where they differ most.
 *
 * The address is shown rather than re-asked. It is not editable here on
 * purpose — the code the server accepted was issued to THAT address, so
 * changing it would only produce a refusal.
 */
@Composable
private fun EmailRegisterStep(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    WaStep(
        title = stringResource(R.string.goodpost_email_register_title),
        note = stringResource(R.string.goodpost_email_register_note, state.signInEmail),
        errorCode = state.messageCode,
        busy = state.busy
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            WaAvatar(name = state.displayName.ifBlank { "?" }, size = 96.dp)
        }

        Spacer(Modifier.height(24.dp))

        WaField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            label = stringResource(R.string.goodpost_name_label),
            placeholder = stringResource(R.string.goodpost_name_placeholder),
            enabled = !state.busy,
            imeAction = ImeAction.Done,
            onDone = { if (!state.busy) viewModel.submitEmailRegistration() }
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.goodpost_email_confirmed, state.signInEmail),
            color = Wa.TextDim,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(24.dp))

        WaPrimaryButton(
            text = stringResource(R.string.goodpost_create_account),
            enabled = !state.busy && state.displayName.isNotBlank(),
            busy = state.busy,
            onClick = viewModel::submitEmailRegistration
        )

        Spacer(Modifier.height(20.dp))
        PrivacyNote()
    }
}

/**
 * Registration: what name to put on the account (§3).
 *
 * The address is shown rather than asked for again when the server already has
 * it, but the field stays editable — an account made from a number has no
 * address yet, and §3 requires one on file for recovery.
 */
@Composable
private fun RegisterStep(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    WaStep(
        title = stringResource(R.string.goodpost_register_title),
        note = stringResource(R.string.goodpost_register_note),
        errorCode = state.messageCode,
        busy = state.busy
    ) {
        // The avatar previews what other people will see, which is the one
        // thing a name field cannot tell you.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            WaAvatar(name = state.displayName.ifBlank { "?" }, size = 96.dp)
        }

        Spacer(Modifier.height(24.dp))

        WaField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            label = stringResource(R.string.goodpost_name_label),
            placeholder = stringResource(R.string.goodpost_name_placeholder),
            enabled = !state.busy
        )

        Spacer(Modifier.height(12.dp))

        WaField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = stringResource(R.string.goodpost_email_label),
            placeholder = stringResource(R.string.goodpost_signin_email_placeholder),
            enabled = !state.busy,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            onDone = { viewModel.submitRegistration() }
        )

        Spacer(Modifier.height(24.dp))

        WaPrimaryButton(
            text = stringResource(R.string.goodpost_create_account),
            enabled = !state.busy && state.displayName.isNotBlank() && state.email.isNotBlank(),
            busy = state.busy,
            onClick = viewModel::submitRegistration
        )

        Spacer(Modifier.height(20.dp))
        PrivacyNote()
    }
}

/** The backend URL is unset or the service is unreachable at boot (§41). */
@Composable
private fun NotConfiguredStep(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Wa.List)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = Wa.TextDim,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.goodpost_not_configured_title),
            color = Wa.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.goodpost_not_configured_note),
            color = Wa.TextDim,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(8.dp))
        WaTextAction(text = stringResource(R.string.goodpost_retry), onClick = onRetry)
    }
}

// ── Building blocks ─────────────────────────────────────────────────────

/**
 * The shared column for every step: title, explanation, content, error, busy.
 *
 * One place, so a step cannot end up with a different title weight or a
 * different gap before the error than its neighbours.
 */
@Composable
private fun WaStep(
    title: String,
    note: String,
    errorCode: String?,
    busy: Boolean,
    content: @Composable () -> Unit
) {
    // The same dreamy canvas the signed-in screens use, so signing in looks
    // like the first screen of the same product rather than a form in front of
    // it.
    WaDreamyBackdrop {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp)
    ) {
        Text(
            text = title,
            color = Wa.Text,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold
        )

        if (note.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(text = note, color = Wa.TextDim, fontSize = 14.sp)
        }

        Spacer(Modifier.height(24.dp))

        content()

        if (errorCode != null) {
            Spacer(Modifier.height(16.dp))
            WaErrorNotice(errorCode)
        }

        if (busy) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Wa.Accent
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.goodpost_working),
                    color = Wa.TextDim,
                    fontSize = 13.sp
                )
            }
        }
    }
    }
}

/**
 * A field with its label above it, the way a registration form reads.
 *
 * `TextField` rather than `OutlinedTextField`: on a dark surface an outline
 * fights the fill, and every field here is on the same background anyway.
 */
@Composable
private fun WaField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = Wa.Accent, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Wa.Bar)
                .border(1.dp, Wa.Divider, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp)
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                // No Material label: the label is drawn above the field, so
                // the field itself stays a plain one-line box and the
                // placeholder cannot end up doubled.
                placeholder = {
                    Text(text = placeholder, color = Wa.TextDim, fontSize = 16.sp)
                },
                textStyle = TextStyle(fontSize = 16.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = Wa.Accent,
                    focusedTextColor = Wa.Text,
                    unfocusedTextColor = Wa.Text,
                    disabledTextColor = Wa.TextDim
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PrivacyNote() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Wa.Bar),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = Wa.Accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.goodpost_privacy_note),
            color = Wa.TextDim,
            fontSize = 13.sp
        )
    }
}
