package com.muddassir.clearview.goodpost.data

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Serialises refresh-token rotation across the whole process.
 *
 * A file-level value rather than a field, because there is more than one
 * [GoodPostAuthRepository]: the channels, posts, engagement and inbox data
 * layers each build their own, and they all rotate the SAME stored refresh
 * token. A per-instance lock would leave exactly the race it exists to stop.
 */
private val sessionRefreshMutex = Mutex()

/** Outcome of starting phone verification. */
sealed interface StartResult {
    /** An SMS is on its way; the user must type the code. */
    data class CodeSent(val verificationId: String) : StartResult

    /**
     * Instant verification: Google confirmed ownership without a code.
     * Common on a device holding the SIM, and it must still go through the
     * backend — a credential is not a session.
     */
    data class AutoVerified(val credential: PhoneAuthCredential) : StartResult

    data class Failed(val code: String) : StartResult
}

/**
 * Outcome of asking for an emailed code.
 *
 * A separate type from [StartResult] rather than two more variants on it: an
 * emailed code has no verification id and can never be auto-verified, because
 * the server holds the whole challenge. Sharing one type would put two
 * unreachable branches in front of every caller and invite a caller to read a
 * variant that cannot arrive.
 */
sealed interface EmailStartResult {
    /** The code is on its way. Says nothing about whether an account exists. */
    object Sent : EmailStartResult

    data class Failed(val code: String) : EmailStartResult
}

/** Outcome of exchanging a credential for a Good Post session. */
sealed interface AuthResult {
    data class SignedIn(val session: GoodPostSession) : AuthResult

    /** The number is verified but has no account yet — collect a name/email. */
    data class NeedsRegistration(val idToken: String) : AuthResult

    /**
     * The address is proven but has no account yet — collect a name.
     *
     * Separate from [NeedsRegistration] on purpose: the phone step needs a
     * token to finish with, the email step needs the code the user already
     * typed, and collapsing the two would mean carrying whichever one happens
     * to be null and hoping the UI works out which flow it is in.
     */
    data class NeedsEmailRegistration(val email: String) : AuthResult

    /**
     * [code] is a server error code (`phone_banned`, `otp_rate_limited`, …),
     * `unreachable` for a transport failure, or `not_configured` when the build
     * has no backend URL.
     */
    data class Failed(val code: String) : AuthResult
}

/**
 * Good Post authentication (§2, §3, §19).
 *
 * The division of labour, stated once so it is not re-derived per screen:
 *
 *  - **Firebase** proves the user controls the mobile number. Nothing else.
 *  - **The ClearView backend** decides whether that number may have an account,
 *    issues the session, and is the only authority on bans.
 *  - **This class** glues them together and never treats a local success as a
 *    real one: §36 forbids reporting follow/react/post/upload as succeeded
 *    before the server confirms, and the same rule starts here — a Firebase
 *    credential alone signs nobody in.
 */
class GoodPostAuthRepository(
    context: Context,
    private val api: GoodPostApi = GoodPostApi()
) {

    private companion object {
        const val TAG = "GoodPostAuth"
        const val SMS_TIMEOUT_SECONDS = 60L

        /** Challenge purposes the backend accepts (mirrors its CHECK clause). */
        const val PURPOSE_SIGN_IN = "signin"
        const val PURPOSE_REGISTER = "register"

        /**
         * What the server must be told when signing in, so the account's device
         * list is readable. Not an identifier — just a manufacturer/model pair.
         */
        val DEVICE_LABEL: String =
            listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()
    }

    private val store = SecureTokenStore(context)
    private val firebaseAuth: FirebaseAuth get() = FirebaseAuth.getInstance()

    /** False when this build has no Good Post backend URL configured. */
    val isConfigured: Boolean get() = api.isConfigured

    fun currentSession(): GoodPostSession? = store.load()

    // ── Step 1: start verification ──────────────────────────────────────

    /**
     * Claim an OTP allowance, then let Firebase send the SMS.
     *
     * The order matters and is not an implementation detail: our endpoint runs
     * first so a banned or rate-limited number is refused BEFORE an SMS is
     * paid for, and so the server-side challenge exists by the time sign-in is
     * attempted. Firebase cannot see the platform ban; the backend cannot send
     * an SMS.
     */
    suspend fun startVerification(activity: Activity, phoneE164: String): StartResult {
        if (!api.isConfigured) return StartResult.Failed("not_configured")

        when (val otp = api.requestOtp(phoneE164, PURPOSE_SIGN_IN)) {
            is ApiResult.Ok -> Unit
            is ApiResult.Failed -> {
                // Logged because the screen shows only wording: a refusal here
                // (banned, throttled, expired) is otherwise indistinguishable
                // from a request that never arrived.
                Log.w(TAG, "OTP request refused: ${otp.code}")
                return StartResult.Failed(otp.code)
            }
            ApiResult.Unreachable -> return StartResult.Failed("unreachable")
        }

        return suspendCancellableCoroutine { continuation ->
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    if (continuation.isActive) {
                        continuation.resume(StartResult.AutoVerified(credential))
                    }
                }

                override fun onVerificationFailed(exception: FirebaseException) {
                    val code = classifyStartFailure(exception)
                    // The code, never the SDK's own message: Firebase's text
                    // embeds the phone number, and §38 treats that as private.
                    // Logging the message would copy the number into logcat and
                    // into any crash report built from it.
                    Log.w(
                        TAG,
                        "Phone verification failed: ${exception.javaClass.simpleName} " +
                            "code=${errorCodeOf(exception) ?: "none"} -> $code"
                    )
                    if (continuation.isActive) {
                        continuation.resume(StartResult.Failed(code))
                    }
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    // resume() is idempotent here because isActive is checked:
                    // the SDK can deliver completion and code-sent on the same
                    // flow, and a second resume would throw.
                    if (continuation.isActive) {
                        continuation.resume(StartResult.CodeSent(verificationId))
                    }
                }
            }

            val options = PhoneAuthOptions.newBuilder(firebaseAuth)
                .setPhoneNumber(phoneE164)
                .setTimeout(SMS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Required: reCAPTCHA runs in the Activity when Play Integrity
                // cannot confirm the app.
                .setActivity(activity)
                .setCallbacks(callbacks)
                .build()

            PhoneAuthProvider.verifyPhoneNumber(options)
        }
    }

    // ── Step 2: exchange the credential for a session ────────────────────

    /** Submit a typed code. */
    suspend fun submitCode(
        phoneE164: String,
        verificationId: String,
        code: String
    ): AuthResult {
        val credential = try {
            PhoneAuthProvider.getCredential(verificationId, code)
        } catch (e: Exception) {
            // Thrown for a blank or malformed verification id, not for a
            // wrong code — a wrong code produces a credential that fails later.
            Log.w(TAG, "Could not build a credential from the supplied code")
            return AuthResult.Failed("invalid_code")
        }
        return exchangeCredential(credential)
    }

    /** Complete an automatic verification that never needed a code. */
    suspend fun exchangeCredential(credential: PhoneAuthCredential): AuthResult {
        val idToken = when (val signIn = signInForToken(credential)) {
            is SignInResult.Verified -> signIn.idToken
            is SignInResult.Failed -> return AuthResult.Failed(signIn.code)
        }

        return when (val result = api.signIn(idToken, DEVICE_LABEL)) {
            is ApiResult.Ok -> persist(result.value)
            is ApiResult.Failed ->
                // An unknown number is not an error — it is the registration
                // prompt. Every other refusal (banned, suspended, throttled) is
                // passed through for the UI to explain.
                if (result.code == "account_not_found") {
                    AuthResult.NeedsRegistration(idToken)
                } else {
                    // The single most valuable line in this class when
                    // something is wrong: it is the ONLY place the server's
                    // own reason (`invalid_id_token`, `auth_unavailable`,
                    // `phone_unverified`, …) is recorded. Without it the user
                    // sees generic wording and every cause looks identical.
                    Log.w(TAG, "Backend refused sign-in: ${result.code} (http=${result.status})")
                    AuthResult.Failed(result.code)
                }
            ApiResult.Unreachable -> AuthResult.Failed("unreachable")
        }
    }

    /**
     * Create the account for a verified number.
     *
     * A second challenge is claimed with purpose `register` before the call.
     * No additional SMS is sent — the number is already verified — but the
     * registration endpoint requires a challenge of its own purpose, so the
     * ledger records one. It costs a slot of the hourly allowance and nothing
     * else.
     */
    suspend fun completeRegistration(
        phoneE164: String,
        idToken: String,
        displayName: String,
        email: String
    ): AuthResult {
        when (val otp = api.requestOtp(phoneE164, PURPOSE_REGISTER)) {
            is ApiResult.Ok -> Unit
            is ApiResult.Failed -> return AuthResult.Failed(otp.code)
            ApiResult.Unreachable -> return AuthResult.Failed("unreachable")
        }

        return when (val result = api.register(idToken, displayName.trim(), email.trim(), DEVICE_LABEL)) {
            is ApiResult.Ok -> persist(result.value)
            is ApiResult.Failed -> AuthResult.Failed(result.code)
            ApiResult.Unreachable -> AuthResult.Failed("unreachable")
        }
    }

    // ── Email sign-in (§2 second method) ─────────────────────────────────

    /**
     * Claim an email allowance, then let the server deliver the code.
     *
     * Note what is NOT here: no verification id, and no local knowledge of the
     * code. With a phone number Firebase holds the challenge and hands us an id
     * to submit against; with email the server owns the whole exchange, so the
     * client's only job is to carry the address and then the digits.
     */
    suspend fun startEmailVerification(
        email: String,
        signingUp: Boolean = false
    ): EmailStartResult {
        if (!api.isConfigured) return EmailStartResult.Failed("not_configured")

        // The sign-up screen says `register` up front; the sign-in screen says
        // nothing. Both get the same code either way, but declaring the intent
        // keeps the challenge ledger honest and lets a resend after
        // `email_not_registered` still be a single decision for the user.
        val purpose = if (signingUp) PURPOSE_REGISTER else null

        return when (val result = api.requestEmailOtp(email, purpose)) {
            is ApiResult.Ok -> EmailStartResult.Sent
            is ApiResult.Failed -> {
                Log.w(TAG, "Email OTP request refused: ${result.code}")
                EmailStartResult.Failed(result.code)
            }
            ApiResult.Unreachable -> EmailStartResult.Failed("unreachable")
        }
    }

    /**
     * Exchange an emailed code for a session.
     *
     * Signs in, or answers [AuthResult.NeedsEmailRegistration] when the address
     * is proven but has no account. Nothing is created here: the code has only
     * been spent on a *lookup*, and the account itself is [registerWithEmail]'s
     * job, which the server gates on the same challenge.
     */
    suspend fun submitEmailCode(email: String, code: String): AuthResult =
        when (val result = api.emailSignIn(email, code.trim(), DEVICE_LABEL)) {
            is ApiResult.Ok -> persist(result.value)
            is ApiResult.Failed -> {
                // Logged for the same reason as the phone path: the UI shows
                // wording, and the code is the only thing that says which of
                // half a dozen causes it actually was.
                Log.w(TAG, "Backend refused email sign-in: ${result.code} (http=${result.status})")

                // `email_not_registered` is the one refusal that is not an
                // error: the code was right, the inbox is proven, and the only
                // thing missing is an account. The ViewModel turns this into
                // the name step instead of a red message.
                if (result.code == "email_not_registered") {
                    AuthResult.NeedsEmailRegistration(email)
                } else {
                    AuthResult.Failed(result.code)
                }
            }
            ApiResult.Unreachable -> AuthResult.Failed("unreachable")
        }

    /**
     * Step 3 by email: the same proven code, plus the name to publish under.
     *
     * The server re-checks that the address is still free and consumes the
     * challenge inside one transaction, so this cannot be turned into a way to
     * take over an existing account.
     */
    suspend fun registerWithEmail(
        email: String,
        code: String,
        displayName: String
    ): AuthResult =
        when (val result = api.emailRegister(email, code.trim(), displayName.trim(), DEVICE_LABEL)) {
            is ApiResult.Ok -> persist(result.value)
            is ApiResult.Failed -> {
                Log.w(TAG, "Backend refused email registration: ${result.code} (http=${result.status})")
                AuthResult.Failed(result.code)
            }
            ApiResult.Unreachable -> AuthResult.Failed("unreachable")
        }

    // ── Session lifecycle ───────────────────────────────────────────────

    /**
     * A valid session, refreshing first if the access token is spent.
     *
     * Returns null when the user must sign in again. The critical distinction
     * is between the two failure kinds: a server refusal clears the stored
     * session (it is genuinely dead, and `refresh_token_reused` means it was
     * revoked for suspicion), whereas being offline must NOT — §36 wants
     * already-fetched content to stay readable with no network, and logging
     * someone out for entering a tunnel would discard a working credential.
     */
    suspend fun validSession(): GoodPostSession? {
        val stored = store.load() ?: return null
        if (!GoodPostSessionCodec.isExpired(stored, System.currentTimeMillis())) return stored

        // One refresh at a time, and the token is re-read INSIDE the lock.
        //
        // The server rotates the refresh token on every use and treats a
        // second presentation of a rotated token as a stolen credential —
        // revoking the session. Opening Good Post asks for a session from five
        // places at once (categories, follows, feed, inbox, push), so without
        // this the app would reliably sign itself out the moment an access
        // token expired: four of the five would replay the pre-rotation token.
        //
        // Re-reading under the lock is what makes the waiters cheap: whoever
        // loses the race finds the token the winner just stored and returns it
        // instead of rotating again.
        return sessionRefreshMutex.withLock {
            val current = store.load()
            if (current == null) return@withLock null
            if (!GoodPostSessionCodec.isExpired(current, System.currentTimeMillis())) {
                return@withLock current
            }

            when (val refreshed = api.refresh(current.refreshToken, DEVICE_LABEL)) {
                is ApiResult.Ok -> {
                    store.save(refreshed.value)
                    refreshed.value
                }
                is ApiResult.Failed -> {
                    Log.i(TAG, "Session refresh refused (${refreshed.code}); signing out")
                    store.clear()
                    null
                }
                ApiResult.Unreachable -> current
            }
        }
    }

    /**
     * The account behind the stored session, or null if it is gone.
     * This is what the Good Post gate calls on entry, so a session revoked
     * server-side (a ban, a forced logout, reuse detection) is noticed at once
     * instead of at the next write.
     */
    suspend fun restoreAccount(): GoodPostAccount? {
        val session = validSession() ?: return null

        return when (val result = api.me(session.accessToken)) {
            is ApiResult.Ok -> result.value
            is ApiResult.Failed -> {
                if (result.status == 401 || result.status == 403) {
                    store.clear()
                    null
                } else {
                    // 5xx or a proxy hiccup: keep the session and let the
                    // screen show a retryable error rather than logging out.
                    null
                }
            }
            ApiResult.Unreachable -> null
        }
    }

    /** Sign out locally, and tell the server so the session row is revoked. */
    suspend fun signOut() {
        val session = store.load()
        store.clear()
        if (session != null) {
            api.logout(session.accessToken, session.refreshToken)
        }
        try {
            firebaseAuth.signOut()
        } catch (e: Exception) {
            Log.d(TAG, "Firebase sign-out skipped: ${e.message}")
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun persist(session: GoodPostSession): AuthResult {
        store.save(session)
        return AuthResult.SignedIn(session)
    }

    /**
     * Sign in to Firebase with the credential, then read the ID token the
     * backend will verify. The Firebase user is intentionally left signed in:
     * it is the device's record that this number was verified, and the app's
     * own tokens remain the source of truth for everything else.
     */
    private suspend fun signInForToken(credential: PhoneAuthCredential): SignInResult = try {
        firebaseAuth.signInWithCredential(credential).await()
        val token = firebaseAuth.currentUser?.getIdToken(false)?.await()?.token
        if (token.isNullOrBlank()) SignInResult.Failed("verification_failed")
        else SignInResult.Verified(token)
    } catch (e: FirebaseException) {
        val code = errorCodeOf(e)
        val mapped = classifyCodeFailure(e, code)
        // The same shape as the number-entry log, and for the same reason: a
        // failure that only says "could not confirm" cannot be told apart from
        // a broken deployment without the code, and this is the phase where an
        // unexplained refusal costs the user the most.
        Log.w(
            TAG,
            "Firebase sign-in failed: ${e.javaClass.simpleName} " +
                "code=${code ?: "none"} -> $mapped"
        )
        SignInResult.Failed(mapped)
    } catch (e: Exception) {
        Log.w(TAG, "Firebase sign-in failed: ${e.javaClass.simpleName}")
        SignInResult.Failed("verification_failed")
    }

    /**
     * Turn a failure from the CODE-ENTRY phase into a code the UI can word.
     *
     * The counterpart of [classifyStartFailure], read the same way: the PHASE
     * decides what an exception means. There, no code existed yet, so a
     * credentials failure could only be a bad number. Here a code did exist, so
     * the same exception means the code was wrong — unless Firebase reports the
     * verification session itself expired, in which case no digit string could
     * ever be accepted and the only remedy is a new code.
     *
     * That case is why the code is consulted before the type. Firebase returns
     * `ERROR_SESSION_EXPIRED` (and the rejected-code case)
     * as a plain [FirebaseAuthException], and a type-only mapping dropped it
     * into a generic failure whose wording blames the number — the exact
     * dead end this exists to prevent.
     */
    private fun classifyCodeFailure(exception: FirebaseException, code: String?): String = when {
        code == null ->
            if (exception is FirebaseAuthInvalidCredentialsException) "invalid_code"
            else "verification_failed"

        code.contains("session-expired") -> "verification_expired"
        code.contains("invalid-verification-code") -> "invalid_code"
        code.contains("too-many-requests") -> "rate_limited"
        code.contains("quota") -> "sms_quota_exceeded"

        // The type is the fallback, not the decider: in this phase a
        // credentials failure means the code rather than the number.
        else ->
            if (exception is FirebaseAuthInvalidCredentialsException) "invalid_code"
            else "verification_failed"
    }

    /** What [signInForToken] learned, carrying why a failure failed. */
    private sealed interface SignInResult {
        data class Verified(val idToken: String) : SignInResult
        data class Failed(val code: String) : SignInResult
    }

    /**
     * Turn a failure from the NUMBER-ENTRY phase into a code the UI can word.
     *
     * The phase is load-bearing, not incidental. Firebase raises
     * [FirebaseAuthInvalidCredentialsException] for both a malformed number and
     * a wrong code, and the callback carries no field to tell the two apart — so
     * the same class must be READ differently depending on whether the user has
     * been given a code yet. In this phase they have not, which makes a
     * credentials failure an unusable number. Getting this backwards tells a
     * user with a bad country code to check an SMS that was never sent.
     *
     * Firebase's `errorCode` strings are never shown to users as-is: only the
     * distinction that changes the instruction is kept — a quota-exceeded number
     * cannot be retried, a region the project has not enabled cannot be retried
     * either — and anything ambiguous is reported generically rather than
     * leaking SDK detail.
     *
     * The code is consulted BEFORE the exception type, because the type is not
     * trustworthy on its own: the SDK wraps `ERROR_INVALID_APP_CREDENTIAL` — a
     * project misconfiguration a user can do nothing about — in the same
     * [FirebaseAuthInvalidCredentialsException] it uses for a malformed number.
     * Reading the type first would tell that user to check their country code.
     * The type is the fallback for when there is no usable code at all.
     */
    private fun classifyStartFailure(exception: FirebaseException): String {
        val code = errorCodeOf(exception)

        return when {
            code == null -> {
                // No code to go on. In THIS phase no verification code has been
                // typed, so a credentials failure can only be the number.
                if (exception is FirebaseAuthInvalidCredentialsException) "invalid_phone_number"
                else "verification_failed"
            }

            code.contains("invalid-phone-number") -> "invalid_phone_number"
            code.contains("quota") -> "sms_quota_exceeded"
            code.contains("too-many-requests") -> "rate_limited"

            // 17006 (region disabled for the project), 17028 (this app's package
            // or signing certificate is not registered) and 17004 (bad app
            // credential) all mean the PROJECT will not send, whatever the
            // number is. 17006 is what an Indian number hits out of the box,
            // because India is not in the default SMS region policy.
            code.contains("operation-not-allowed") ||
                code.contains("app-not-authorized") ||
                code.contains("invalid-app-credential") -> "verification_unavailable"

            else -> "verification_failed"
        }
    }

    /**
     * Firebase's error code, normalised for matching.
     *
     * Underscores become hyphens because the SDK spells codes
     * `ERROR_TOO_MANY_REQUESTS` while the checks above read as phrases. Before
     * this, `contains("too-many-requests")` could never match and that branch
     * was unreachable — a dead path that looked like working code.
     */
    private fun errorCodeOf(exception: FirebaseException): String? =
        (exception as? FirebaseAuthException)?.errorCode
            ?.lowercase()
            ?.replace('_', '-')

    /**
     * Bridge a Play Services [Task] into a suspend function.
     *
     * Hand-written rather than pulling in `kotlinx-coroutines-play-services`
     * for two call sites, and because the cancellation behaviour we want is
     * trivial here: these are one-shot calls that resolve quickly.
     */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val failure = task.exception
            when {
                failure != null -> continuation.resumeWithException(failure)
                task.result != null -> continuation.resume(task.result as T)
                else -> continuation.resumeWithException(IllegalStateException("empty result"))
            }
        }
    }
}
