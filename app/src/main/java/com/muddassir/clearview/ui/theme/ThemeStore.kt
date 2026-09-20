package com.muddassir.clearview.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which colour scheme the app renders in.
 *
 * A single app-wide choice rather than a per-tab one: the tabs share one
 * MaterialTheme, so a per-tab setting could only ever be three different themes
 * fighting over the same screens. One setting, applied everywhere — including
 * the auxiliary activities (the Quran verse window, the todo alarm, the snooze
 * screen) that draw through [UrlblockerTheme] too.
 */
enum class ThemeMode {
    /** Follow the phone's own light/dark setting. */
    SYSTEM,

    /** Always light, whatever the phone says. */
    LIGHT,

    /** Always dark, whatever the phone says. */
    DARK
}

/**
 * Whether the app should render dark, given the chosen [mode] and what the
 * phone's own light/dark setting currently is.
 *
 * Split out as a plain function because this single line is the whole of the
 * feature's user-visible rule: an explicit choice beats the phone, and
 * [ThemeMode.SYSTEM] defers to it. Keeping it here means the rule can be tested
 * without a composition, a Context or a preferences file.
 */
internal fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Holds the chosen [ThemeMode], persisted in its own preferences file and
 * exposed as a [StateFlow] so switching the theme in the More tab repaints
 * every screen at once — including the ones that are already composed, rather
 * than only the next time they are opened.
 *
 * Process-wide and intentionally tiny: the preference is one string, and the
 * whole point of the object is that a screen deep in the tree (or an activity
 * that never touches MainActivity) can ask for the current scheme without
 * having the choice threaded down to it as a parameter.
 */
object ThemeStore {
    private const val PREFS_NAME = "appearance_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    /**
     * Dark unless the user decides otherwise.
     *
     * ClearView is a reading and viewing app — a dark scheme is the sane default
     * for a video feed, a Quran reader and an alarm that fires at Fajr — so the
     * app ships dark and [ThemeMode.SYSTEM] is there for anyone who would rather
     * it followed the phone.
     */
    private val _mode = MutableStateFlow(ThemeMode.DARK)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private var prefs: SharedPreferences? = null

    /**
     * Loads the persisted choice once per process. Idempotent, and safe to call
     * from a composition: the read happens on the caller's thread only the first
     * time, which is why [UrlblockerTheme] calls it directly rather than waiting
     * for an effect — a theme that arrives one frame late would flash the wrong
     * scheme on every cold start.
     */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        _mode.value = read(store)
    }

    /** The persisted mode, for callers that have no composition (activities). */
    fun current(context: Context): ThemeMode {
        ensureInitialized(context)
        return _mode.value
    }

    fun set(context: Context, mode: ThemeMode) {
        ensureInitialized(context)
        if (_mode.value == mode) return
        prefs?.edit()?.putString(KEY_THEME_MODE, mode.name)?.apply()
        _mode.value = mode
    }

    private fun read(store: SharedPreferences): ThemeMode {
        val raw = store.getString(KEY_THEME_MODE, null) ?: return ThemeMode.DARK
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.DARK)
    }
}
