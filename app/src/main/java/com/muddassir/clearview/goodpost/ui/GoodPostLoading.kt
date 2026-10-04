package com.muddassir.clearview.goodpost.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * What this tab shows while it is waiting (§22, §26).
 *
 * ## Why a skeleton rather than a spinner
 *
 * A spinner answers "is anything happening" and nothing else. The screens here are
 * lists whose SHAPE the reader already knows — a channel row is an avatar and two
 * lines, a post is a rounded bubble — so the honest thing to draw while the first
 * page is on its way is that shape, held open. It does two jobs at once: it says
 * the screen is coming rather than broken, and it means the list does not jump
 * into place when the data lands, because the space was already the size it was
 * going to be.
 *
 * ## The motion
 *
 * One slow sheen sweeping left to right across the placeholder blocks. It is
 * deliberately the only thing moving: a screen that pulses AND slides AND fades
 * makes the wait feel longer, and this one has to sit under a thumb that is
 * already scrolling.
 *
 * ## One sweep for the whole skeleton
 *
 * The sweep is created once per skeleton and shared by every block through
 * [LocalSkeletonSweep], and each block reads it inside its DRAW lambda. Both
 * halves matter. One animation per block meant a page of placeholders ran thirty
 * clocks and invalidated thirty times a frame; reading the value in composition
 * instead would recompose thirty blocks a frame. This way a frame of the sheen
 * is one value, one repaint per visible block, and no layout at all.
 */
@Composable
private fun rememberSkeletonSweep(): State<Float> =
    rememberInfiniteTransition(label = "wa-skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wa-skeleton-sweep"
    )

/** The skeleton's single sweep, read by its blocks and by nothing else. */
private val LocalSkeletonSweep = compositionLocalOf<State<Float>> {
    error("A skeleton block must be drawn inside Shimmer(...)")
}

/** The sweep as a brush, at one instant of it. Called only from a draw pass. */
private fun skeletonBrush(head: Float, base: Color, highlight: Color): Brush {
    // A fixed range rather than the block's own measured width: the sheen is a
    // texture, not a highlight anyone lines up, and measuring every block would
    // be a layout pass per frame for something nobody can see the edges of.
    val travel = 1200f
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(head - travel / 2f, 0f),
        end = Offset(head, 0f)
    )
}

/**
 * Wraps a placeholder in ONE sheen, shared by every block inside it.
 *
 * The value is provided rather than passed down so a skeleton's tree keeps the
 * shape it had before the sweep existed: only the leaves change.
 */
@Composable
private fun Shimmer(content: @Composable () -> Unit) {
    val sweep = rememberSkeletonSweep()
    CompositionLocalProvider(LocalSkeletonSweep provides sweep) { content() }
}

/** One placeholder block: a bar, a circle, a bubble — anything with a shape. */
@Composable
private fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp)
) {
    val sweep = LocalSkeletonSweep.current
    // The two tones the sheen moves between, taken from the theme so the
    // placeholder sits one step above the surface it is drawn on in either
    // scheme, rather than in a hardcoded dark pair.
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier = modifier
            .clip(shape)
            // `drawBehind` and not `background`: the sweep is read here, in the
            // draw phase, so a frame of it repaints this block and never
            // recomposes it or its parent.
            .drawBehind { drawRect(brush = skeletonBrush(sweep.value * 1200f, base, highlight)) }
    )
}

/**
 * A discovery section's shape, before it has any channels (§7).
 *
 * A heading with its "See all" pill and four rows, drawn where the real section
 * will be. This is a PLACEHOLDER INSIDE THE LIST rather than an overlay on top
 * of it: the previous version was a floating column painted over the LazyColumn,
 * which covered the advertisement carousel and any section that had already
 * arrived — the loading state hid the content it was loading, and it stayed in
 * place while the list behind it scrolled.
 *
 * Four rows rather than a full screen of them, because that is what one section
 * previews; the placeholder's job is to say what is coming, not to fill the page.
 */
@Composable
internal fun WaDiscoverSectionSkeleton(rows: Int = 4) {
    Shimmer {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBlock(modifier = Modifier.width(150.dp).height(19.dp))
                Spacer(Modifier.weight(1f))
                SkeletonBlock(
                    modifier = Modifier.width(64.dp).height(28.dp),
                    shape = CircleShape
                )
            }

            repeat(rows) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkeletonBlock(modifier = Modifier.size(49.dp), shape = CircleShape)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(if (index % 2 == 0) 0.52f else 0.38f)
                                .height(14.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        SkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(if (index % 3 == 0) 0.30f else 0.22f)
                                .height(11.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    SkeletonBlock(
                        modifier = Modifier.width(74.dp).height(30.dp),
                        shape = CircleShape
                    )
                }
            }
        }
    }
}

/**
 * One discovery row's shape (§7).
 *
 * Avatar, name, follower line and a Follow pill — the same measurements the real
 * row uses, so a page of these and the page of channels that replaces them have
 * the same height and the list does not shift under the reader.
 */
@Composable
internal fun WaDiscoverRowSkeleton() {
    Shimmer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkeletonBlock(modifier = Modifier.size(49.dp), shape = CircleShape)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.46f).height(14.dp))
                Spacer(Modifier.height(8.dp))
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.26f).height(11.dp))
            }
            Spacer(Modifier.width(10.dp))
            SkeletonBlock(
                modifier = Modifier.width(74.dp).height(30.dp),
                shape = CircleShape
            )
        }
    }
}

/**
 * The channel list's own shape, before it has any channels (§4).
 *
 * Avatar, a bold line for the name and a dimmer one for the preview, with the
 * unread badge's slot left empty — exactly the row the data will draw, so the
 * list settles rather than reflows.
 */
@Composable
internal fun WaChannelListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 6
) {
    Shimmer {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(rows) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBlock(
                    modifier = Modifier.size(49.dp),
                    shape = CircleShape
                )
                Spacer(Modifier.width(15.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.46f else 0.34f)
                            .height(13.dp)
                    )
                    Spacer(Modifier.height(9.dp))
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(if (index % 3 == 0) 0.72f else 0.58f)
                            .height(11.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                SkeletonBlock(
                    modifier = Modifier.width(34.dp).height(10.dp)
                )
            }
        }
    }
    }
}

/**
 * A feed's own shape, before its first page lands (§9).
 *
 * Rounded bubbles of varying length, indented the way the real ones are, so
 * opening a channel shows the conversation it is about to become rather than an
 * empty screen with a spinner in the middle of it.
 */
@Composable
internal fun WaPostFeedSkeleton(
    modifier: Modifier = Modifier,
    count: Int = 5
) {
    Shimmer {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // The date pill a day boundary draws, so the placeholder includes the one
        // element that is not a bubble.
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            SkeletonBlock(
                modifier = Modifier.width(88.dp).height(18.dp),
                shape = RoundedCornerShape(8.dp)
            )
        }

        repeat(count) { index ->
            val lines = when (index % 3) {
                0 -> 4
                1 -> 2
                else -> 3
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth(if (index % 3 == 0) 0.86f else 0.72f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Wa.Bubble.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                repeat(lines) { line ->
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(
                                if (line == lines - 1) (if (index % 2 == 0) 0.5f else 0.66f)
                                else 1f
                            )
                            .height(11.dp)
                    )
                    if (line < lines - 1) Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    SkeletonBlock(modifier = Modifier.width(42.dp).height(9.dp))
                }
            }
        }
    }
    }
}

/**
 * A centred placeholder for a screen that has no list shape to hold open — a
 * channel's information page, a single record being fetched.
 *
 * Three dots, breathing in sequence rather than together: the point is that it is
 * moving, and staggered dots read as movement where a synchronised pulse reads as
 * a still image with a slow blink.
 */
@Composable
internal fun WaLoadingDots(modifier: Modifier = Modifier) {
    val wave: State<Float> = rememberInfiniteTransition(label = "wa-dots").animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wa-dots-wave"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            // Scaled, not resized (§22): a dot that grows by changing its SIZE
            // runs a measure and a layout pass per frame for all three. Scaling
            // it happens in the layer, and the wave is read there too, so nothing
            // above this Row recomposes while the dots breathe.
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        val lift = (wave.value - index).coerceIn(0f, 1f)
                        scaleX = 1f + 0.5f * lift
                        scaleY = 1f + 0.5f * lift
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            )
        }
    }
}
