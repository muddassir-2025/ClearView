package com.muddassir.clearview.goodpost.data

import android.content.Context

/**
 * This device's FCM registration token (§17).
 *
 * It lives in `SharedPreferences` rather than in memory for one reason that
 * matters: Firebase mints a token on first launch and hands it to
 * `onNewToken`, which runs at whatever moment the system decides — often before
 * anyone has signed in. A token held only in a ViewModel field would be lost on
 * the next process death and, because `onNewToken` does not fire again for an
 * install that already has one, would never come back. The account would then
 * simply never be pushed to, with nothing on screen to explain it.
 *
 * Deliberately NOT cleared on sign-out: the token addresses the DEVICE, and the
 * server moves it to whichever account is signed in. Deleting it here would
 * mean a fresh token could not be registered until Firebase issued another.
 */
class GoodPostPushTokens(context: Context) {

    private companion object {
        const val PREFS = "goodpost_push"
        const val KEY_TOKEN = "registration_token"
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored token, or null when Firebase has not issued one yet. */
    fun current(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Remember a token Firebase just issued, replacing any previous one. */
    fun save(token: String) {
        if (token.isBlank()) return
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }
}
