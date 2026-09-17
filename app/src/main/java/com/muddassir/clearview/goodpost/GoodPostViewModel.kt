package com.muddassir.clearview.goodpost

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muddassir.clearview.goodpost.data.AuthResult
import com.muddassir.clearview.goodpost.data.EmailStartResult
import com.muddassir.clearview.goodpost.data.GoodPostAccount
import com.muddassir.clearview.goodpost.data.GoodPostAuthRepository
import com.muddassir.clearview.goodpost.data.StartResult
import kotlinx.coroutines.launch

/**
 * Which screen the Good Post tab is on.
 *
 * Modelled as an explicit phase rather than a pile of booleans so an
 * impossible combination ("showing the code field with no verification id")
 * cannot be represented, and so the gate has one obvious place to be right.
 */
enum class GoodPostPhase {
    Checking,
    Entry,
    Code,

    /**
     * The email code was accepted but the address has no account yet: ask for
     * the one thing the server cannot know, the name to publish under.
     */
    EmailRegister,

    /** Kept for the paused mobile flow — see the note on [GoodPostAuthMethod]. */
    Register,
    SignedIn,
    NotConfigured
}

/**
 * How the user proves who they are (§2).
 *
 * Mobile is PAUSED, not removed: every path behind it — Firebase phone auth,
 * the SMS challenge, the phone-hash ban identity of §19 — still exists and
 * still typechecks, and the entry screen simply does not offer it. Flipping
 * [GoodPostViewModel.ENTRY_METHOD] back to [Mobile] restores the toggle, and
 * because the register path is unchanged that is genuinely all it takes.
 *
 * What replaced it is the email flow, which is now also able to REGISTER — see
 * [GoodPostPhase.EmailRegister]. That is the temporary trade: an address is a
 * weaker abuse identity than a number, so a ban on an email-only account is
 * enforced by the account status rather than by `banned_identities`.
 */
enum class GoodPostAuthMethod { Mobile, Email }

data class GoodPostUiState(
    val phase: GoodPostPhase = GoodPostPhase.Checking,
    val authMethod: GoodPostAuthMethod = GoodPostViewModel.ENTRY_METHOD,
    val phone: String = "",
    val code: String = "",
    val displayName: String = "",
    /** The address collected at REGISTRATION. Never the sign-in address. */
    val email: String = "",
    /**
     * The address used to SIGN IN, kept apart from [email] deliberately:
     * sharing one field would let a value typed during registration reappear in
     * the sign-in box (and the reverse), and the two are different questions
     * asked at different times.
     */
    val signInEmail: String = "",
    val account: GoodPostAccount? = null,
    /** Set when a session exists but the server could not be reached (§36). */
    val offline: Boolean = false,
    val busy: Boolean = false,
    /**
     * A backend code, not a sentence — mapped to wording by the UI. Keeping the
     * code here means the ViewModel stays free of resources and the mapping
     * stays unit-testable.
     */
    val messageCode: String? = null
)

/**
 * Good Post tab state (§35: composables hold no backend logic — UI → ViewModel
 * → repository → API/local storage).
 *
 * The unauthenticated gate lives here and is scoped to the Good Post tab only:
 * a user who never opens it is never asked to sign in, and nothing outside
 * this tab reads [GoodPostAuthRepository].
 */
class GoodPostViewModel : ViewModel() {

    companion object {
        /**
         * The one method the entry screen currently offers.
         *
         * Set to [GoodPostAuthMethod.Mobile] to put the number back in front of
         * the user. The screen reads this constant to decide which fields and
         * which toggle to show, so there is no second switch to keep in step.
         */
        val ENTRY_METHOD = GoodPostAuthMethod.Email
    }

    private var repository: GoodPostAuthRepository? = null
    private var verificationId: String? = null
    private var pendingIdToken: String? = null

    /**
     * The address whose code has been accepted but has no account behind it.
     *
     * Held here rather than in [GoodPostUiState] because it is a security
     * input, not something the screen renders: the registration call sends
     * THIS address, never whatever is currently in the text field.
     */
    private var pendingEmail: String? = null

    var uiState by mutableStateOf(GoodPostUiState())
        private set

    /** Idempotent: the tab can be left and re-entered without re-checking. */
    fun initialize(context: Context) {
        val existing = repository
        if (existing != null) return

        val repo = GoodPostAuthRepository(context.applicationContext)
        repository = repo

        if (!repo.isConfigured) {
            uiState = uiState.copy(phase = GoodPostPhase.NotConfigured)
            return
        }
        refreshSession()
    }

    /**
     * Decide the gate: signed in, or ask for a number.
     *
     * A stored session whose server check fails transiently is kept rather
     * than discarded. §36 wants previously fetched content to stay readable
     * offline, and logging someone out because their train entered a tunnel
     * would throw away a perfectly good 30-day credential.
     */
    fun refreshSession() {
        val repo = repository ?: return
        viewModelScope.launch {
            uiState = uiState.copy(phase = GoodPostPhase.Checking, messageCode = null)

            val account = repo.restoreAccount()
            val session = repo.currentSession()

            uiState = when {
                account != null -> uiState.copy(
                    phase = GoodPostPhase.SignedIn,
                    account = account,
                    offline = false
                )

                session != null -> uiState.copy(
                    phase = GoodPostPhase.SignedIn,
                    account = GoodPostAccount(
                        id = session.userId,
                        displayName = session.displayName,
                        email = session.email,
                        status = "active"
                    ),
                    offline = true
                )

                else -> uiState.copy(phase = GoodPostPhase.Entry, account = null)
            }
        }
    }

    // ── Form input ──────────────────────────────────────────────────────

    fun onPhoneChange(value: String) {
        uiState = uiState.copy(phone = value, messageCode = null)
    }

    fun onCodeChange(value: String) {
        uiState = uiState.copy(code = value, messageCode = null)
    }

    fun onSignInEmailChange(value: String) {
        uiState = uiState.copy(signInEmail = value, messageCode = null)
    }

    /**
     * Switch which way the user is signing in.
     *
     * Clears the typed code and any message, and nothing else: a half-typed
     * number or address is kept, so looking at the other option does not throw
     * away what they already entered. A code must go, though — the digits typed
     * for an SMS mean nothing against an email challenge, and the server would
     * reject them anyway.
     */
    fun onAuthMethodChange(method: GoodPostAuthMethod) {
        // While the phone path is paused this is unreachable from the UI; the
        // guard keeps a future toggle from doing anything but the obvious.
        if (method == uiState.authMethod) return
        verificationId = null
        uiState = uiState.copy(
            authMethod = method,
            code = "",
            messageCode = null,
            busy = false
        )
    }

    fun onDisplayNameChange(value: String) {
        uiState = uiState.copy(displayName = value, messageCode = null)
    }

    fun onEmailChange(value: String) {
        uiState = uiState.copy(email = value, messageCode = null)
    }

    /** Back to the choice of method — the entry step, not necessarily the phone. */
    fun backToEntry() {
        verificationId = null
        pendingIdToken = null
        pendingEmail = null
        uiState = uiState.copy(
            phase = GoodPostPhase.Entry,
            code = "",
            displayName = "",
            messageCode = null,
            busy = false
        )
    }

    // ── Steps ───────────────────────────────────────────────────────────

    /**
     * Step 1. Needs the Activity because Firebase runs reCAPTCHA through it
     * when Play Integrity cannot confirm the app.
     */
    fun startVerification(activity: Activity) {
        if (uiState.authMethod == GoodPostAuthMethod.Email) {
            startEmailVerification()
            return
        }

        val repo = repository ?: return
        val phone = normalizePhoneInput(uiState.phone)
        if (phone == null) {
            uiState = uiState.copy(messageCode = "invalid_phone")
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(busy = true, messageCode = null, phone = phone)

            when (val result = repo.startVerification(activity, phone)) {
                is StartResult.CodeSent -> {
                    verificationId = result.verificationId
                    uiState = uiState.copy(
                        phase = GoodPostPhase.Code,
                        code = "",
                        busy = false,
                        messageCode = null
                    )
                }

                // Google confirmed the number without a code — but a credential
                // is not a session, so it still has to go through the backend.
                is StartResult.AutoVerified -> applyAuth(repo.exchangeCredential(result.credential))

                is StartResult.Failed ->
                    uiState = uiState.copy(busy = false, messageCode = result.code)
            }
        }
    }

    /**
     * Step 1 by email: claim the allowance and have the server deliver a code.
     *
     * Nothing is known about whether the address has an account, and nothing
     * may be assumed: the endpoint answers the same way either way, so the UI
     * cannot and must not treat this success as "that email is registered".
     */
    fun startEmailVerification(signingUp: Boolean = false) {
        val repo = repository ?: return
        val email = normalizeEmailInput(uiState.signInEmail)
        if (email == null) {
            uiState = uiState.copy(messageCode = "invalid_email")
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(busy = true, messageCode = null, signInEmail = email)

            when (val result = repo.startEmailVerification(email, signingUp)) {
                // No verification id to keep: the server holds the challenge.
                is EmailStartResult.Sent -> uiState = uiState.copy(
                    phase = GoodPostPhase.Code,
                    code = "",
                    busy = false,
                    messageCode = null
                )

                is EmailStartResult.Failed ->
                    uiState = uiState.copy(busy = false, messageCode = result.code)
            }
        }
    }

    /** Step 2, for whichever method is in play. */
    fun submitCode() {
        val repo = repository ?: return
        if (uiState.code.isBlank()) return

        if (uiState.authMethod == GoodPostAuthMethod.Email) {
            val email = normalizeEmailInput(uiState.signInEmail)
            if (email == null) {
                uiState = uiState.copy(messageCode = "invalid_email")
                return
            }
            viewModelScope.launch {
                uiState = uiState.copy(busy = true, messageCode = null)
                applyAuth(repo.submitEmailCode(email, uiState.code))
            }
            return
        }

        val id = verificationId
        if (id == null) {
            // The process was recreated, or the user navigated back. Restart
            // the flow rather than sending a credential request with no id.
            backToEntry()
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(busy = true, messageCode = null)
            applyAuth(repo.submitCode(uiState.phone, id, uiState.code.trim()))
        }
    }

    /**
     * Step 3 for email, reached only from [GoodPostPhase.EmailRegister].
     *
     * The address and the code both come from state the server already agreed
     * with, so there is nothing new to validate except the name.
     */
    fun submitEmailRegistration() {
        val repo = repository ?: return
        val email = pendingEmail
        val code = uiState.code

        if (email == null || code.isBlank()) {
            // The process was recreated, or the user went back. Start over
            // rather than calling the API with half a flow.
            backToEntry()
            return
        }
        if (uiState.displayName.isBlank()) {
            uiState = uiState.copy(messageCode = "invalid_display_name")
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(busy = true, messageCode = null)
            applyAuth(repo.registerWithEmail(email, code, uiState.displayName))
        }
    }

    /** Step 3, only reached when the number has no account yet. */
    fun submitRegistration() {
        val repo = repository ?: return
        val idToken = pendingIdToken
        if (idToken == null) {
            backToEntry()
            return
        }
        if (uiState.displayName.isBlank() || uiState.email.isBlank()) {
            uiState = uiState.copy(messageCode = "invalid_request")
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(busy = true, messageCode = null)
            applyAuth(
                repo.completeRegistration(
                    phoneE164 = uiState.phone,
                    idToken = idToken,
                    displayName = uiState.displayName,
                    email = uiState.email
                )
            )
        }
    }

    fun signOut() {
        val repo = repository ?: return
        viewModelScope.launch {
            uiState = uiState.copy(busy = true)
            repo.signOut()
            verificationId = null
            pendingIdToken = null
            pendingEmail = null
            uiState = GoodPostUiState(phase = GoodPostPhase.Entry, account = null)
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun applyAuth(result: AuthResult) {
        uiState = when (result) {
            is AuthResult.SignedIn -> uiState.copy(
                phase = GoodPostPhase.SignedIn,
                account = GoodPostAccount(
                    id = result.session.userId,
                    displayName = result.session.displayName,
                    email = result.session.email,
                    status = "active"
                ),
                busy = false,
                offline = false,
                messageCode = null,
                code = ""
            )

            is AuthResult.NeedsRegistration -> {
                pendingIdToken = result.idToken
                uiState.copy(
                    phase = GoodPostPhase.Register,
                    busy = false,
                    messageCode = null,
                    code = ""
                )
            }

            is AuthResult.NeedsEmailRegistration -> {
                // The code was RIGHT — only the account was missing. The digits
                // are therefore kept: [submitEmailRegistration] has to hand the
                // server the same code to prove the inbox a second time, and
                // asking the user to retype it would be asking them to repeat
                // something the app already got right.
                pendingEmail = result.email
                uiState.copy(
                    phase = GoodPostPhase.EmailRegister,
                    busy = false,
                    messageCode = null,
                    displayName = ""
                )
            }

            is AuthResult.Failed ->
                uiState.copy(busy = false, messageCode = result.code)
        }
    }
}
