package com.muddassir.clearview.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ClearView's shared visual language.
 *
 * The iOS app reads as more polished than this one for reasons that are not the
 * palette — the two apps already share it — but the surface treatment: cards sit
 * a hair above the page instead of on it, bars float rather than stack, corners
 * agree across screens, and a tap answers immediately. This file is the Android
 * home for those decisions, so a screen styles itself by naming a token instead
 * of spelling out a corner radius and three colours again.
 *
 * ## Why translucency instead of a blur library
 *
 * True backdrop blur (a surface sampling what is behind it) has no Compose
 * primitive: `Modifier.blur` blurs the node's OWN content, and a real backdrop
 * filter needs a third-party effect layer that recomputes a captured region on
 * every frame — which is exactly the cost that turns a scrolling media feed into
 * a stuttering one on mid-range hardware. The depth here is therefore built the
 * way the iOS reference actually looks: a translucent fill over the page's own
 * gradient, a hairline light border, and a soft shadow. Nothing samples the
 * frame behind it, so nothing gets slower, and the effect degrades to plain
 * opaque surfaces wherever a device renders translucency poorly.
 */
object CvTokens {

    /** The spacing ramp. Every gap in a refined screen is one of these. */
    object Space {
        val xxs = 2.dp
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 20.dp
        val xxl = 28.dp
        val xxxl = 36.dp
    }

    /** The corner radii. A card is [md]/[lg]; a bar or a sheet is [xl]/[bar]. */
    object Radius {
        val sm = 12.dp
        val md = 16.dp
        val lg = 20.dp
        val xl = 26.dp
        val bar = 30.dp
    }

    /** Icon and touch-target sizes, so a row's glyphs line up on every screen. */
    object Size {
        val glyph = 22.dp
        val glyphSmall = 18.dp
        val touch = 48.dp
        val glyphBox = 44.dp
    }

    /**
     * How much of the page a translucent surface lets through.
     *
     * A bar keeps more of the page than a card: it floats over scrolling content
     * and has to stay legible while things move beneath it.
     */
    object Alpha {
        const val bar = 0.86f
        const val barStrong = 0.94f
        const val card = 0.72f
        const val hairline = 0.30f
    }
}

/**
 * The page's own backdrop: the scheme's background with one very slight accent
 * wash, so a tall screen is not a single flat rectangle.
 *
 * It is a gradient rather than a picture and costs one draw call, which is what
 * keeps it safe behind a long Quran page or a media feed.
 */
@Composable
fun cvPageBrush(): Brush {
    val scheme = MaterialTheme.colorScheme
    // One accent wash, wide enough to see and narrow enough not to tint the
    // reading text: a 22% mix of the scheme's own primary container, brightest
    // a third of the way down (under the top bar, where a page's eye lands
    // first) and gone by the bottom of a long scroll.
    val wash = lerp(scheme.background, scheme.primaryContainer, 0.22f)
    val deep = lerp(scheme.background, scheme.surfaceContainerLow, 0.55f)
    return Brush.verticalGradient(
        0f to wash,
        0.34f to deep,
        1f to scheme.background
    )
}

/** A full-size page background, with [content] drawn over it. */
@Composable
fun CvBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize().background(cvPageBrush()), content = content)
}

/**
 * A translucent, hairline-edged surface: the glass look used by the navigation
 * bar, floating controls and selected rows.
 *
 * Two details are what make it read as glass rather than as a faint rectangle,
 * and both matter most in the dark scheme, where the page is nearly black:
 *
 *  - the fill is the scheme's RAISED container, not its `surface` (a surface
 *    tinted at 74% over a page of the same colour is invisible), and
 *  - the border is slightly brighter than the surface, so the edge catches the
 *    light the way a highlight does on the reference app.
 */
@Composable
fun Modifier.cvGlassSurface(
    shape: Shape = RoundedCornerShape(CvTokens.Radius.lg),
    tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    alpha: Float = CvTokens.Alpha.bar,
    elevation: Dp = 10.dp,
    borderAlpha: Float = 0.55f
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.20f),
        spotColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.26f)
    )
    .clip(shape)
    .background(tint.copy(alpha = alpha))
    .border(
        BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = borderAlpha)),
        shape
    )
    // A one-stop highlight down the top edge, the way light catches a real pane
    // of glass. Drawn as a gradient that fades within the first few pixels, so
    // it reads as a sheen rather than as a second border.
    .background(
        Brush.verticalGradient(
            0f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
            0.18f to Color.Transparent
        )
    )

/**
 * A card: the same translucent fill as the glass, at card weight.
 *
 * Used for content that sits IN the page rather than floating over it, so the
 * elevation is smaller and the fill more opaque.
 */
@Composable
fun Modifier.cvCardSurface(
    shape: Shape = RoundedCornerShape(CvTokens.Radius.lg),
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    alpha: Float = CvTokens.Alpha.card,
    elevation: Dp = 1.dp
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.10f),
        spotColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f)
    )
    .clip(shape)
    .background(container.copy(alpha = alpha))
    .border(
        BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
        shape
    )

/** The interaction source a pressable surface needs, remembered per call site. */
@Composable
fun rememberCvInteraction(): MutableInteractionSource =
    remember { MutableInteractionSource() }

/**
 * Press feedback: a card shrinks a touch under the finger.
 *
 * Deliberately small (3%) and spring-released — the point is that the surface
 * answers the touch, not that the reader watches an animation.
 */
fun Modifier.cvPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cvPressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * A tappable card with the app's press feedback.
 *
 * [onClick] is what makes it a control; the ripple is kept (it is the platform's
 * own acknowledgement) and the scale is layered on top of it.
 */
@Composable
fun CvCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape: Shape = RoundedCornerShape(CvTokens.Radius.lg),
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = CvTokens.Space.lg,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = rememberCvInteraction()
    Column(
        modifier = modifier
            .cvCardSurface(shape = shape, container = container)
            .then(if (onClick != null) Modifier.cvPressScale(interaction) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple(),
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(contentPadding),
        content = content
    )
}

/**
 * A section heading: the title, an optional one-line note under it, and an
 * optional trailing action aligned with the title rather than the note.
 *
 * The sizes come from the theme's type scale, so a heading looks the same in
 * every tab instead of each screen picking its own weight.
 */
@Composable
fun CvSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(Modifier.height(CvTokens.Space.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(CvTokens.Space.sm))
            trailing()
        }
    }
}
