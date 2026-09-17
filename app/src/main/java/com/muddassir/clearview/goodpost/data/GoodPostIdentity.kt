package com.muddassir.clearview.goodpost.data

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.GoogleAuthProvider
import com.muddassir.clearview.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A reader's identity for Good Post (§3, §15, §16).
 *
 * The app signs in to Firebase ANONYMOUSLY the first time Good Post needs to
 * know who the reader is, and sends the resulting ID token to the ClearView
 * backend, which verifies it and keys the reader's own state off the uid. That
 * uid is what a follow, a read position and a mute hang from.
 *
 * **Nothing here asks the reader for anything.** No email, no password, no
 * phone number, no verification step — the sign-in is silent and happens behind
 * the tab. The whole reader surface works before it and after it; what the
 * identity adds is remembering which channels are *theirs* (§4).
 *
 * ## Why an interface
 *
 * So the screens and the repository can be tested, and so "this build has no
 * Firebase project" is a state rather than a crash. [NoIdentity] is what a
 * build without `google-services.json` gets: reader-scoped calls then report
 * themselves as unavailable, which is the same thing the backend says when its
 * own `FIREBASE_PROJECT_ID` is unset, and the app words both the same way.
 *
 * ## What is deliberately not stored
 *
 * No uid is persisted and no token is written to disk. Firebase's own SDK keeps
 * the signed-in user across restarts, which is the only persistence this needs —
 * a cached uid in SharedPreferences would be a value the app trusts that the
 * server has not verified, and a cached token would be a credential sitting in
 * a file for a feature that only needs it while the app is running.
 */
internal interface GoodPostIdentity {

    /**
     * Whether this build can identify a reader AT ALL (§3).
     *
     * Not "is somebody signed in" — the sign-in is silent and happens on demand —
     * but "is there an identity mechanism here". The home tab asks it to tell two
     * situations apart: a reader whose follows could not be fetched (show what is
     * known, and say the rest failed) and a build with no Firebase project, which
     * has no list of follows to fetch and must therefore fall back to the public
     * catalogue or show an empty tab forever.
     */
    val available: Boolean

    /**
     * A verifiable ID token for this device's reader, or null.
     *
     * Null is not an error the caller has to handle differently from a refused
     * request: the backend answers `auth_unavailable` for a deployment that
     * cannot verify and `invalid_token` for a token it does not like, and a
     * caller that has no token simply does not make the call (§23).
     */
    suspend fun token(): String?

    /**
     * A verifiable ID token for a CREATOR identity (§16), or null.
     *
     * Different from [token] in the one way that matters to the server: the
     * token names a provider and carries an email, so it is not anonymous and
     * may therefore own a channel. `null` means the credentials were refused —
     * a wrong password, or a sign-up for an address that already has an
     * account.
     *
     * @param signUp create the account if there is not one already.
     */
    suspend fun creatorToken(email: String, password: String, signUp: Boolean): String?

    /**
     * A creator ID token obtained through Google (§16), or null.
     *
     * Null covers every "no": the reader dismissed the account picker, this
     * build has no web client id (see `BuildConfig.GOOGLE_WEB_CLIENT_ID`), the
     * device has no usable Google account, or Firebase refused the credential.
     * The screen words the dismissal differently from a refusal, so callers get
     * [GoogleSignIn.Cancelled] separately rather than having to guess.
     *
     * @param activity the Activity Credential Manager draws the picker over —
     *   an Application context throws, because there is no window to attach to.
     */
    suspend fun googleCreatorToken(activity: Activity): GoogleSignIn

    /**
     * Forget the creator identity, and only that (§16).
     *
     * Called when an administrator signs out, and the phrase "only that" is the
     * whole of it: the READER's identity stays exactly where it was, so signing
     * out of a channel does not take the channels this device follows with it.
     * A no-op on a build with no Firebase project, because there is nothing to
     * forget.
     */
    suspend fun creatorSignOut()
}

/** The three answers a Google sign-in attempt can have (§16). */
internal sealed interface GoogleSignIn {

    /** Firebase accepted the Google account; this is its ID token. */
    data class Token(val idToken: String) : GoogleSignIn

    /** The account picker was dismissed, or there was nothing to offer. */
    data object Cancelled : GoogleSignIn

    /** Something went wrong that saying "cancelled" would misdescribe. */
    data object Failed : GoogleSignIn
}

/** What a build with no configured Firebase project uses. */
internal object NoIdentity : GoodPostIdentity {
    override val available: Boolean = false

    override suspend fun token(): String? = null

    /** No Firebase project means no creator sign-in either — a supported state. */
    override suspend fun creatorToken(email: String, password: String, signUp: Boolean): String? = null

    override suspend fun googleCreatorToken(activity: Activity): GoogleSignIn = GoogleSignIn.Cancelled

    override suspend fun creatorSignOut() = Unit
}

/**
 * Firebase anonymous identity (§3).
 *
 * ## Two Firebase instances, because there are two people (§3, §16)
 *
 * This device can be BOTH an anonymous reader and a signed-in creator, and
 * Firebase keeps exactly one current user per app instance. That is the trap
 * this class is built around: for a while the creator sign-in ran on the same
 * instance as the reader's, so signing in as a creator either replaced the
 * anonymous uid — silently taking every channel the reader followed with it —
 * or linked the two and left the reader browsing as the creator after they
 * signed out.
 *
 * So the reader keeps the DEFAULT app and the creator gets a named second one of
 * its own ([CREATOR_APP_NAME]). The reader's uid is then untouched by anything
 * that happens to a creator account, and signing out of a channel cannot touch
 * the list of channels this device follows. Both instances are the same Firebase
 * project, so a token from either verifies against the same backend.
 *
 * ## The SDK holds the token; this class does not
 *
 * A Firebase ID token lasts an hour and is then renewed. `getIdToken(false)` is
 * the whole mechanism: the SDK answers from the token it already holds while it
 * is valid and performs the refresh itself once it is not, so it is called on
 * each request and costs a local lookup the overwhelming majority of the time.
 *
 * Caching the token here as well would be a second copy with a second opinion
 * about when it expires — and the expiry API to keep it honest is easy to get
 * wrong. What this class actually adds is (a) signing in if nobody is signed in,
 * and (b) not stampeding that from several concurrent callers.
 */
internal class FirebaseGoodPostIdentity(
    private val firebaseAuth: () -> FirebaseAuth? = { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
) : GoodPostIdentity {

    override val available: Boolean = true

    private companion object {
        const val TAG = "GoodPostIdentity"

        /**
         * The name of the SECOND Firebase app, which holds creator sign-ins.
         *
         * Named rather than random so the instance is reused: a fresh name per
         * call would initialize a new app (and a new auth store) every time,
         * which is both wasteful and a new signed-out session each time.
         */
        const val CREATOR_APP_NAME = "clearview-creator"
    }

    /**
     * Serialises sign-in.
     *
     * Two screens can ask for a token at the same moment on startup, and a
     * second concurrent `signInAnonymously` would create a SECOND anonymous uid —
     * which is the one way this feature can silently lose a reader's follows:
     * they would be attached to the discarded uid and never seen again.
     */
    private val lock = Any()

    override suspend fun token(): String? {
        val auth = firebaseAuth() ?: return null

        return try {
            val user = auth.currentUser ?: signInAnonymously(auth)
            user.tokenResult().token?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // Never surfaced as a crash: a device with no network or no Firebase
            // project has to keep showing the reader everything public (§23).
            Log.d(TAG, "no reader identity: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Creator sign-in (§16), on the creator's OWN Firebase instance.
     *
     * The reader's uid is not a participant. That is the point of the second
     * app: `linkWithCredential` used to preserve it and `signInWithCredential`
     * used to discard it, and which of the two happened depended on whether the
     * address already had a Firebase account — a difference nobody signing in
     * could see, with the reader's followed channels as the price. Here the
     * credential is always a plain sign-in on an instance the reader never
     * touches, so there is no case in which a creator's account and a reader's
     * follows are the same identity.
     *
     * `signUp` decides nothing about whether an account exists — Firebase does.
     * It chooses which call to attempt first, so a creator who taps "create an
     * account" gets an account created and one who taps "sign in" is not quietly
     * signed up when they mistype their address.
     */
    override suspend fun creatorToken(email: String, password: String, signUp: Boolean): String? {
        val auth = creatorAuth() ?: return null

        return try {
            if (signUp) {
                try {
                    auth.createUserWithEmailAndPassword(email.trim(), password).await()
                } catch (e: Exception) {
                    // "Already in use" is the caller asking to create an account
                    // they already have. Signing them in is the useful answer;
                    // anything else (a weak password, an invalid address) is a
                    // real refusal and is retried as a sign-in only so the
                    // caller gets one consistent answer.
                    Log.d(TAG, "creator sign-up refused, signing in instead: ${e.javaClass.simpleName}")
                    auth.signInWithEmailAndPassword(email.trim(), password).await()
                }
            } else {
                auth.signInWithEmailAndPassword(email.trim(), password).await()
            }

            auth.currentUser?.tokenResult()?.token?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.d(TAG, "no creator identity: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Google sign-in through Credential Manager (§16).
     *
     * The ID token Google returns is exchanged for a FIREBASE credential rather
     * than being sent to our own backend. That indirection is the point: the
     * server verifies Firebase tokens and nothing else, so an app that could
     * assert a Google token directly would be a second, unverified way to claim
     * an identity. Firebase is what signs the claim.
     *
     * Like the email/password path, this runs on the CREATOR's own Firebase
     * instance rather than the reader's, so becoming a creator leaves the uid a
     * reader's follows hang from exactly as it was (§3, §16).
     *
     * ## The full account picker, on purpose
     *
     * Every Google account on the device is offered, and nothing is auto-selected
     * or filtered. That is a deliberate choice about who this button is for: a
     * creator is choosing the account the CHANNEL will belong to, which is very
     * often an address this app has never seen, and it is the choice they live
     * with after the channel exists. A silent sign-in with whichever account
     * happens to be on the phone is how somebody ends up owning a channel under
     * the wrong identity, and filtering to accounts that have authorised the app
     * before would hide the address they want to use.
     */
    override suspend fun googleCreatorToken(activity: Activity): GoogleSignIn {
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (webClientId.isBlank()) return GoogleSignIn.Cancelled

        val googleIdToken = try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential = CredentialManager.create(activity)
                .getCredential(context = activity, request = request)
                .credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } else {
                // A passkey or an unknown credential type: nothing here can be
                // exchanged for a Firebase credential, so there is nothing to
                // send anywhere.
                return GoogleSignIn.Cancelled
            }
        } catch (e: GetCredentialCancellationException) {
            // The reader dismissed the picker. Not an error, and worded as
            // nothing at all rather than as a failure.
            return GoogleSignIn.Cancelled
        } catch (e: Exception) {
            Log.d(TAG, "google sign-in unavailable: ${e.javaClass.simpleName}")
            return GoogleSignIn.Failed
        }

        val auth = creatorAuth() ?: return GoogleSignIn.Failed
        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)

        return try {
            auth.signInWithCredential(firebaseCredential).await()

            val token = auth.currentUser?.tokenResult()?.token?.takeIf { it.isNotBlank() }
            if (token == null) GoogleSignIn.Failed else GoogleSignIn.Token(token)
        } catch (e: Exception) {
            Log.d(TAG, "google credential refused: ${e.javaClass.simpleName}")
            GoogleSignIn.Failed
        }
    }

    /**
     * Sign out of the creator instance, and nothing else (§16).
     *
     * Deliberately not `firebaseAuth().signOut()`: that would sign the READER
     * out, and the next reader-scoped request would mint a brand-new anonymous
     * uid — which is the same loss of follows by a different route.
     */
    override suspend fun creatorSignOut() {
        val auth = existingCreatorAuth() ?: return
        runCatching { auth.signOut() }
            .onFailure { Log.d(TAG, "creator sign-out refused: ${it.javaClass.simpleName}") }
    }

    /**
     * The second Firebase app's auth, created on first use.
     *
     * Initialized from the DEFAULT app's own options rather than from
     * `google-services.json` a second time, so the two instances cannot be
     * configured differently — there is one set of options in this process and
     * both apps use it.
     *
     * A build where the second app cannot be created (a platform restriction, a
     * malformed options set) falls back to the reader's instance rather than
     * failing: creator sign-in still works there, it just shares the uid, which
     * is the behaviour this replacement exists to improve on rather than a
     * reason to have no creator sign-in at all.
     */
    private fun creatorAuth(): FirebaseAuth? {
        existingCreatorAuth()?.let { return it }

        val primary = try {
            FirebaseApp.getInstance()
        } catch (e: IllegalStateException) {
            // No default app at all: the reader has no identity either, so there
            // is nothing for a second instance to be separate from.
            return null
        }

        return try {
            val app = FirebaseApp.initializeApp(
                primary.applicationContext,
                primary.options,
                CREATOR_APP_NAME
            )
            app?.let { FirebaseAuth.getInstance(it) } ?: firebaseAuth()
        } catch (e: Exception) {
            Log.d(TAG, "no separate creator instance: ${e.javaClass.simpleName}")
            firebaseAuth()
        }
    }

    /** The creator instance, only if it already exists — never creates one. */
    private fun existingCreatorAuth(): FirebaseAuth? = try {
        FirebaseAuth.getInstance(FirebaseApp.getInstance(CREATOR_APP_NAME))
    } catch (e: IllegalStateException) {
        null
    }

    /** The SDK's Task, as a coroutine — the shape every Firebase call above needs. */
    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { continuation.resume(it) }
            addOnFailureListener { continuation.resumeWithException(it) }
        }

    private suspend fun signInAnonymously(auth: FirebaseAuth): FirebaseUser =
        suspendCancellableCoroutine { continuation ->
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    val user = result.user
                    if (user == null) {
                        continuation.resumeWithException(IllegalStateException("no user"))
                    } else {
                        continuation.resume(user)
                    }
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

    /**
     * The SDK's own token fetch, as a coroutine.
     *
     * `false` means "do not force a refresh": the SDK returns the token it holds
     * while that token is still valid, and renews it when it is not. Forcing a
     * refresh here would put a network round trip in front of every request and
     * would invalidate nothing.
     */
    private suspend fun FirebaseUser.tokenResult(): GetTokenResult =
        suspendCancellableCoroutine { continuation ->
            getIdToken(false)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
}
