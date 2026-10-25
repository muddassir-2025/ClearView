package com.muddassir.clearview.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.ui.theme.CvTokens
import com.muddassir.clearview.ui.theme.cvGlassSurface
import com.muddassir.clearview.ui.theme.rememberCvInteraction

/**
 * One destination in the footer bar.
 *
 * [badge] is an unseen count, not a decoration: Media's channel updates are the
 * only thing in the app worth interrupting a tab row for.
 */
data class CvFooterDestination(
    val label: String,
    /** The glyph drawn when this destination is NOT the current one. */
    val icon: ImageVector,
    val selected: Boolean,
    val badge: Int = 0,
    val onClick: () -> Unit,
    /**
     * The glyph drawn when it IS the current one.
     *
     * A filled mark for the current tab and an outlined one for the rest is the
     * convention the reference app uses, and it says "you are here" with shape
     * rather than colour alone — which survives a colour-blind reader and a
     * bright screen. Defaults to [icon] for destinations with no second form.
     */
    val selectedIcon: ImageVector = icon
)

/**
 * The app's footer navigation: one floating glass card with every destination
 * centred in it.
 *
 * ## Why not `NavigationBar`
 *
 * Material's bar is a full-bleed slab with a fixed 80dp height, its own window
 * insets and items pinned toward the middle of a taller container, so it cannot
 * be a floating card: giving it a rounded shape and a shadow still leaves its
 * content caught between a top-aligned icon row and an inset-padded bottom. This
 * composable owns the whole measurement instead — a fixed 62dp interior, one
 * equal-width slot per destination, and the icon + label block centred in its
 * slot both vertically and horizontally, so no venue, font scale or inset
 * changes where an item lands.
 *
 * ## What makes it feel premium rather than merely translucent
 *
 * Three small behaviours, all of them fast:
 *  - the selected destination gets a soft accent pill behind the glyph, which
 *    animates its size and colour instead of snapping between tabs;
 *  - the glyph lifts a hair (scale 1.0 → 1.06) and its label thickens when
 *    selected, so selection is legible without reading the colour;
 *  - the whole row keeps its own 62dp rhythm regardless of the label, so a tab
 *    with a long name does not push the icons out of line (labels ellipsize
 *    instead).
 *
 * The card itself is [cvGlassSurface]: a translucent raised fill, a hairline
 * edge and a sheen, which is what makes it read as glass over the page rather
 * than as the page's last band. `navigationBarsPadding` is applied OUTSIDE the
 * card so the gesture area stays clear and the card still floats above it.
 */
@Composable
fun CvFooterBar(
    destinations: List<CvFooterDestination>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = CvTokens.Space.md, vertical = CvTokens.Space.sm)
            .height(62.dp)
            .cvGlassSurface(
                shape = RoundedCornerShape(CvTokens.Radius.bar),
                // The page’s OWN background, not a raised grey: the card has to
                // look like a pane of the page lifted off it. A lighter container
                // fill read as a white strip pasted under the tabs, which is the
                // one thing a glass bar must never look like. Definition comes
                // from the hairline edge, the sheen and the shadow instead.
                tint = MaterialTheme.colorScheme.background,
                alpha = CvTokens.Alpha.bar,
                elevation = 18.dp,
                borderAlpha = 0.55f
            )
            .padding(horizontal = CvTokens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        destinations.forEach { destination ->
            CvFooterItem(
                destination = destination,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** One slot: centred glyph over its label, both inside the accent pill. */
@Composable
private fun CvFooterItem(
    destination: CvFooterDestination,
    modifier: Modifier = Modifier
) {
    val interaction = rememberCvInteraction()
    val selected = destination.selected
    val tint by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cvFooterTint"
    )
    val pillWidth by animateDpAsState(
        targetValue = if (selected) 50.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "cvFooterPillWidth"
    )
    val glyphScale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cvFooterGlyphScale"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(CvTokens.Radius.md))
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                role = Role.Tab,
                onClick = destination.onClick
            )
            .padding(vertical = CvTokens.Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.height(32.dp),
            contentAlignment = Alignment.Center
        ) {
            // The selection pill: zero-width when unselected, so the glyph never
            // shifts position as the reader moves between tabs.
            Box(
                modifier = Modifier
                    .width(pillWidth)
                    .height(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            )
            Icon(
                imageVector = if (selected) destination.selectedIcon else destination.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = glyphScale
                        scaleY = glyphScale
                    }
            )
            if (destination.badge > 0) {
                Badge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 26.dp, bottom = 12.dp)
                ) {
                    Text(destination.badge.coerceAtMost(99).toString())
                }
            }
        }
        Spacer(Modifier.height(CvTokens.Space.xxs))
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
