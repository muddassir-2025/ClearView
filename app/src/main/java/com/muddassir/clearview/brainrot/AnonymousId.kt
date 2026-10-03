package com.muddassir.clearview.brainrot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.UUID

/**
 * The device's own anonymous identity for the global Brain Rot repository.
 *
 * This is a random UUID the app generates for itself on first use. It is
 * deliberately NOT derived from anything the device already carries — no
 * Android ID, no advertising id, no phone number, no account — so it is a value
 * that exists only because the app made one up, which is what lets the Blocking
 * tab describe the feature as anonymous rather than merely un-labelled.
 *
 * It can be regenerated at any time without losing anything: the id is how the
 * server de-duplicates a device's reports, not how it identifies a person, so
 * losing it costs at most a duplicate report.
 */
object AnonymousId {

    private const val TAG = "AnonymousId"
    private const val PREFS = "clearview_brainrot"
    private const val KEY = "anonymous_id"

    /** The stored id, generating and persisting one on first use. */
    fun get(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { existing ->
            if (isValid(existing)) return existing
        }
        val fresh = UUID.randomUUID().toString()
        try {
            prefs.edit().putString(KEY, fresh).apply()
        } catch (e: Exception) {
            Log.e(TAG, "could not persist anonymous id: ${e.message}")
        }
        return fresh
    }

    /** Replace the id, so the next contribution looks like a new device. */
    fun regenerate(context: Context): String {
        val fresh = UUID.randomUUID().toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, fresh).apply()
        return fresh
    }

    /**
     * True for a well-formed UUID. The server refuses anything else, so an id
     * that somehow got corrupted is replaced rather than sent to be rejected.
     */
    fun isValid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return try {
            // Parsing is the check: UUID.fromString accepts a few shapes that
            // are not canonical, so the canonical spelling is compared back.
            UUID.fromString(value).toString().equals(value, ignoreCase = true)
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
