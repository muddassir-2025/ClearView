package com.muddassir.clearview.goodpost.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * The look of Good Post, as data rather than as constants (§ UI).
 *
 * "A mini, dreamy, customizable messaging app" needs three things this file
 * provides:
 *
 *  1. **A small, honest set of choices.** Two accent families, four backdrops,
 *     two bubble styles. Not a colour picker with 16 million answers: every
 *     option here has to work against the dark canvas, hold up as a button, and
 *     stay legible for a bubble's text — which a free picker cannot promise.
 *  2. **One place the choice lives.** The UI reads colours from `Wa`, and `Wa`
 *     reads them from [GoodPostTheme.current]. Nothing else in the app needs to
 *     know a theme exists, so no screen can be half-themed.
 *  3. **Persistence that matches the rest of Good Post** — `SharedPreferences`
 *     with a JSON string, like every other cache in this package.
 *
 * The theme is DEVICE state, not account state: it is how this phone wants to
 * look, it is never sent to the server, and no other user can observe it.
 */
data class GoodPostTheme(
    /** Which accent family: buttons, badges, the send button, the underline. */
    val accent: AccentChoice = AccentChoice.Teal,
    /** What the canvas behind everything is made of. */
    val backdrop: BackdropChoice = BackdropChoice.Dream,
    /** How translucent the panes above that canvas are. */
    val glass: GlassChoice = GlassChoice.Balanced
) {
    companion object {
        val Default = GoodPostTheme()
    }
}

/**
 * The accent colours offered.
 *
 * Each one is a pair — the fill and its pressed state — because a single colour
 * cannot be both, and a button that does not visibly react to a press is the
 * cheapest way to make an app feel unfinished.
 */
enum class AccentChoice(
    val label: String,
    val fill: Long,
    val pressed: Long
) {
    Teal("Teal", 0xFF00A884, 0xFF008069),

    /** A softer green — the same family, one shade calmer. */
    Mint("Mint", 0xFF4FD1A5, 0xFF2FA37C),

    /** Violet, for the dreamiest of the set. */
    Lilac("Lilac", 0xFF9C6ADE, 0xFF7B4FC7),

    /** Indigo, which sits closest to the blue side of the backdrop. */
    Indigo("Indigo", 0xFF5B7CFA, 0xFF3F5FD9),

    /** Rose, warm against the cool canvas. */
    Rose("Rose", 0xFFE5679A, 0xFFC44A7C),

    /** Amber, the only warm-to-cool opposite in the list. */
    Amber("Amber", 0xFFECB22E, 0xFFC9911A)
}

/**
 * What the canvas is painted with.
 *
 * All four are gradients of two dark stops rather than images: a gradient costs
 * nothing to draw, works at any density, and cannot arrive as a broken asset.
 * The difference between them is hue and warmth, which is exactly what changes
 * the mood without touching the text colours the app was designed around.
 */
enum class BackdropChoice(val label: String, val top: Long, val bottom: Long) {
    /** Teal glow over near-black blue. The default. */
    Dream("Dream", 0xFF0E1B24, 0xFF0A1116),

    /** Purple, for the Lilac accent to sit in. */
    Twilight("Twilight", 0xFF17132A, 0xFF0B0A14),

    /** Navy, the coldest of the four. */
    Ocean("Ocean", 0xFF0B1A2A, 0xFF070F18),

    /** Warm charcoal, for Amber and Rose. */
    Ember("Ember", 0xFF221A16, 0xFF120E0C),

    /** Plain dark, for anyone who wants the chroma gone. */
    Mono("Mono", 0xFF12181C, 0xFF0A0E11)
}

/**
 * How strong the glass is.
 *
 * Not a free alpha: a translucent bar has to stay readable over a photograph
 * and over a blank canvas, and only a few values do both. These three are those
 * values, named by what they are for.
 */
enum class GlassChoice(
    val label: String,
    /** The panel's own colour, alpha included. */
    val fill: Long,
    /** A panel stacked on another panel. */
    val high: Long,
    /** How far the backdrop is blurred behind it, in dp. */
    val blur: Int
) {
    /** Nearly solid. Chosen when the content under the bar is noisy. */
    Subtle("Subtle", 0xF01F2C34, 0xF52A3942, 8),

    /** The default: clearly translucent, still readable. */
    Balanced("Balanced", 0xB31F2C34, 0xCC2A3942, 18),

    /** The most see-through, with the strongest backdrop blur. */
    Airy("Airy", 0x7A1F2C34, 0x992A3942, 26)
}

/**
 * Where the theme is stored, and the one copy every screen reads.
 *
 * [current] is a Compose `MutableState`, which is the mechanism that makes the
 * whole app re-colour when the user picks a new accent: every `Wa.Accent` read
 * inside a composable registers as a read of this state, so changing it
 * invalidates exactly the composables that used it — no restart, no manual
 * recomposition, no theme passed down through twenty parameters.
 */
object GoodPostThemeStore {

    private const val PREFS = "goodpost_theme"
    private const val KEY = "theme"

    /** The theme in force. Starts at the default so a cold read is never null. */
    var current by mutableStateOf(GoodPostTheme.Default)
        private set

    /**
     * Load the saved theme, if any.
     *
     * Every value is looked up through the enum's own `valueOf`-style match
     * rather than trusting the stored name: a name that no longer exists (an
     * option removed in a later version) must fall back to the default instead
     * of throwing on startup.
     */
    fun load(context: Context) {
        val raw = prefs(context).getString(KEY, null) ?: return
        current = decode(raw)
    }

    /** Save a choice, and put it in force immediately. */
    fun save(context: Context, theme: GoodPostTheme) {
        current = theme
        prefs(context).edit().putString(KEY, encode(theme)).apply()
    }

    /** Parse a stored theme. Public so it can be unit-tested without Android. */
    fun decode(raw: String): GoodPostTheme = try {
        val json = JSONObject(raw)
        GoodPostTheme(
            accent = enumOr(json.optString("accent"), AccentChoice.Teal),
            backdrop = enumOr(json.optString("backdrop"), BackdropChoice.Dream),
            glass = enumOr(json.optString("glass"), GlassChoice.Balanced)
        )
    } catch (_: Exception) {
        // Corrupt or truncated JSON: the default look is a safe answer, and
        // failing to open the app because of a stored preference is not.
        GoodPostTheme.Default
    }

    fun encode(theme: GoodPostTheme): String = JSONObject().apply {
        put("accent", theme.accent.name)
        put("backdrop", theme.backdrop.name)
        put("glass", theme.glass.name)
    }.toString()

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
