package com.muddassir.clearview.goodpost.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The tab's motion vocabulary (§22, §23).
 *
 * Every animation in Good Post comes from here rather than from a number typed at
 * the call site, and the reason is consistency rather than tidiness: motion that
 * varies per screen reads as the app being assembled out of parts, and a reader
 * notices a screen that slides faster than the one before it long before they
 * notice any single animation.
 *
 * The other half of the rule is that this file stays SHORT. §23 asks for a
 * messaging app, and the way a messaging app feels fast is that almost nothing
 * moves: a transition tells the reader where they went, and a list item arriving
 * tells them something happened while they were looking. Anything beyond that —
 * cards that bounce, rows that fly in one at a time, parallax — is an animation
 * the reader has to wait for.
 *
 * So there are exactly four kinds of motion in this tab:
 *
 *  1. **A screen change** ([WaMotion.screenChange]) — a shallow slide plus a
 *     fade, so a push and a pop are told apart without either one being a
 *     performance.
 *  2. **An item arriving or leaving a list** (`Modifier.animateItem()`, applied
 *     at the call sites) — this is what makes a channel's update land the moment
 *     it arrives, which is the whole visible point of the server's realtime work
 *     (§12).
 *  3. **A control being pressed** ([waTappable]) — a small scale, which is
 *     feedback on the thing under the finger rather than motion on the screen.
 *  4. **A picture arriving** ([waImageFade]) — a fade in place of a pop, in the
 *     box that was already the right size, so nothing re-lays itself out.
 *
 * Everything here animates a value Compose is recomposing for anyway. No bitmap
 * is transformed, no layout is measured twice, and nothing keeps running after it
 * has finished — which is what keeps this free on a mid-range phone and invisible
 * on the battery (§24).
 */
internal object WaMotion {

    /**
     * How long a screen change takes (§22).
     *
     * 200ms, and the number is a ceiling rather than a target: a navigation that
     * has not finished by the time the reader's next tap arrives is a navigation
     * in the way, and this one fires on every route through the tab.
     * `FastOutSlowInEasing` is the platform's own curve, so this matches what
     * Android does around it.
     */
    private const val SCREEN_MS = 200

    /**
     * How far a pushed screen travels, as a divisor of its width.
     *
     * An eighth. A full-width slide is a carousel — the reader's eye has to
     * follow the whole screen across — and at this distance the motion reads as
     * direction and nothing else. Deliberately a fraction rather than a fixed
     * number of dp: it is the same gesture on a phone and on a tablet.
     */
    private const val SCREEN_SLIDE = 8

    /**
     * How long a selection tint takes to arrive (§5).
     *
     * Shorter than a screen change: this is one card answering a press, not a
     * change of place. Long enough to be seen, short enough that a row of taps
     * down a list still feels like taps rather than like waiting.
     */
    internal const val SELECT_MS = 150

    /**
     * How long a picture takes to appear once its bytes are decoded.
     *
     * The shortest of the three, because it reports nothing: the box is already
     * the right size and the fade only exists so a bitmap that finishes loading
     * mid-scroll does not flash. Under 150ms it is a pop; over 250ms the reader is
     * watching an image load, which is a thing to be avoided in the first place
     * (§13).
     */
    internal const val IMAGE_MS = 130

    /**
     * The transition between two screens on this tab's stack.
     *
     * [forward] means a push, [sameDepth] means the two screens are neither a
     * push nor a pop — two destinations at the same level (the channel list for
     * Explore, one screen of a channel for another) have no direction to report,
     * so they cross-fade and nothing else.
     *
     * The outgoing screen only fades, and for half the time. Sliding both is how
     * a transition turns into a wipe — and a wipe across a list of posts redraws
     * every row on the way past, which is exactly the kind of cost §24 rules out.
     */
    internal fun screenChange(forward: Boolean, sameDepth: Boolean): ContentTransform {
        val enter = if (sameDepth) {
            fadeIn(tween(durationMillis = SCREEN_MS, easing = FastOutSlowInEasing))
        } else {
            val from = if (forward) SCREEN_SLIDE else -SCREEN_SLIDE
            fadeIn(tween(durationMillis = SCREEN_MS, easing = FastOutSlowInEasing)) +
                slideInHorizontally(
                    animationSpec = tween(durationMillis = SCREEN_MS, easing = FastOutSlowInEasing),
                    initialOffsetX = { width -> width / from }
                )
        }

        return enter togetherWith fadeOut(tween(durationMillis = SCREEN_MS / 2))
    }

    /**
     * How long a bar or a chip takes to arrive (§5, §9).
     *
     * Shorter than a screen change, because nothing about where the reader is has
     * changed: this is one control replacing another in a place their eye is
     * already on.
     */
    private const val ARRIVE_MS = 170

    /**
     * A bar sliding down into the place of the one above it (§5).
     *
     * Downwards and from a quarter of its own height, not from off-screen: the
     * selection bar occupies the same strip as the title bar it replaces, and a
     * bar that travels the whole distance would read as a new screen rather than
     * as the same bar in a different mode.
     */
    internal fun barEnter(): EnterTransition =
        fadeIn(tween(durationMillis = ARRIVE_MS)) +
            slideInVertically(
                animationSpec = tween(durationMillis = ARRIVE_MS, easing = FastOutSlowInEasing),
                initialOffsetY = { height -> -height / 4 }
            )

    /**
     * A chip appearing on a post that was already on screen (§9).
     *
     * Lifted from just under full size, which is how a reaction added by somebody
     * else reads as an arrival rather than as the row having been redrawn.
     */
    internal fun chipEnter(): EnterTransition =
        fadeIn(tween(durationMillis = ARRIVE_MS)) +
            scaleIn(animationSpec = tween(durationMillis = ARRIVE_MS), initialScale = 0.86f)

    /**
     * The shortest arrival in the tab: one small value changing (§5).
     */
    internal fun badgeEnter(): EnterTransition =
        fadeIn(tween(durationMillis = ARRIVE_MS / 2))

    /**
     * The spring a pressed control settles on.
     *
     * `StiffnessMediumLow` rather than the default: this fires on every tap in the
     * tab, and a spring that overshoots turns a chip into something that wobbles.
     * Low stiffness settles without bouncing.
     */
    internal fun pressSpring() = spring<Float>(stiffness = Spring.StiffnessMediumLow)
}

/** How far a pressed control shrinks. Small on purpose — a shift, not a squish. */
private const val PRESS_SCALE = 0.95f

/**
 * A control that shrinks slightly while it is held (§22).
 *
 * One helper rather than a ripple-with-scale at each call site, and the
 * indication is deliberately dropped: on a chip this size a ripple covers the
 * thing the reader pressed, and the scale says the same thing without hiding it.
 * Applied to the small controls the tab is full of — emoji chips, pills, filters
 * — and not to rows or buttons that already change appearance when they are
 * touched.
 *
 * The scale is a `graphicsLayer`, so a press costs one layer and no re-layout:
 * the row it lives in does not resize because a finger landed on it.
 */
@Composable
internal fun Modifier.waTappable(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) PRESS_SCALE else 1f,
        animationSpec = WaMotion.pressSpring(),
        label = "wa-press"
    )

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * Content that animates in when it first appears (§5, §9, §22).
 *
 * The one idiom this file could not be written without, and the reason it is here
 * rather than being repeated: `AnimatedVisibility(visible = true)` does NOT
 * animate. A control that is present on its first composition is already in its
 * final state, so there is nothing to move — which is exactly the case for every
 * bar, badge and chip in this tab, all of which appear by being composed.
 *
 * A [MutableTransitionState] starting at `false` and immediately targeting `true`
 * gives the composable a previous state to animate FROM, without the caller
 * having to keep a flag, remember it, and then remember to flip it.
 *
 * There is no exit: every use of this is something that is removed by the screen
 * it lives on deciding it is gone — a selection cleared, a feed replaced — and an
 * exit animation on the way out would be motion in front of content that is
 * already leaving.
 */
@Composable
internal fun WaAppear(enter: EnterTransition, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = state, enter = enter) { content() }
}

/**
 * The opacity a picture is drawn at, faded in once it exists (§22).
 *
 * Called with "is there a bitmap yet", rather than following the load itself, so
 * what animates is the one property that cannot move anything: the caller's box
 * is the size it is going to be either way — that is what the placeholder is
 * holding open — so only what is painted there changes.
 */
@Composable
internal fun waImageFade(loaded: Boolean): Float {
    val alpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = tween(durationMillis = WaMotion.IMAGE_MS),
        label = "wa-image"
    )
    return alpha
}
