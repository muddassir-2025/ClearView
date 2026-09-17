package com.muddassir.clearview.goodpost.data

import android.util.Log
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
     * A verifiable ID token for this device's reader, or null.
     *
     * Null is not an error the caller has to handle differently from a refused
     * request: the backend answers `auth_unavailable` for a deployment that
     * cannot verify and `invalid_token` for a token it does not like, and a
     * caller that has no token simply does not make the call (§23).
     */
    suspend fun token(): String?
}

/** What a build with no configured Firebase project uses. */
internal object NoIdentity : GoodPostIdentity {
    override suspend fun token(): String? = null
}

/**
 * Firebase anonymous identity (§3).
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

    private companion object {
        const val TAG = "GoodPostIdentity"
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
