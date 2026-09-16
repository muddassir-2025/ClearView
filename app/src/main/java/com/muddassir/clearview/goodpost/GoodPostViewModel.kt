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
enum class GoodPostPhase { Checking, Entry, Code, Register, SignedIn, NotConfigured }

/**
 * How the user proves who they are (§2).
 *
 * Two ways in, one account. The mobile number is still the identity §19
 * anchors bans on; the email address is a second door into an account that
 * already exists, never a way to create one.
 */
enum class GoodPostAuthMethod { Mobile, Email }

data class GoodPostUiState(
    val phase: GoodPostPhase = GoodPostPhase.Checking,
    val authMethod: GoodPostAuthMethod = GoodPostAuthMethod.Mobile,
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

    private var repository: GoodPostAuthRepository? = null
    private var verificationId: String? = null
    private var pendingIdToken: String? = null

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
        uiState = uiState.copy(
            phase = GoodPostPhase.Entry,
            code = "",
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
    fun startEmailVerification() {
        val repo = repository ?: return
        val email = normalizeEmailInput(uiState.signInEmail)
        if (email == null) {
            uiState = uiState.copy(messageCode = "invalid_email")
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(busy = true, messageCode = null, signInEmail = email)

            when (val result = repo.startEmailVerification(email)) {
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
            uiState = GoodPostUiState(
                phase = GoodPostPhase.Entry,
                phone = "",
                account = null
            )
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

            is AuthResult.Failed ->
                uiState.copy(busy = false, messageCode = result.code)
        }
    }
}
