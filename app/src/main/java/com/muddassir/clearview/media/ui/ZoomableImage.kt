package com.muddassir.clearview.media.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch

/**
 * An image the reader can pinch, double-tap and drag.
 *
 * ## Why a hand-rolled gesture loop instead of `transformable`
 *
 * The post reader is a `HorizontalPager` whose pages also scroll vertically, so a
 * zoom that claims every drag breaks BOTH: `Modifier.transformable` consumes
 * single-finger movement from the first pixel, which turns a swipe to the next
 * post into a pan of a picture that is not even zoomed. This loop therefore
 * consumes only what it actually uses:
 *
 *  - TWO fingers down → always a pinch/pan; consumed, so zooming never pages;
 *  - one finger while ZOOMED → pan of the enlarged image; consumed, so the page
 *    does not slide under the reader's finger while they inspect it;
 *  - one finger at 1× → not consumed, so the pager and the page's vertical
 *    scroll behave exactly as they did before zoom existed.
 *
 * Double-tap toggles the usual 1× ⇄ 2.5× pair, animated so it does not snap.
 *
 * The image is never re-decoded for a zoom level: [RemoteImage] decodes once at
 * the viewport width and the zoom is a `graphicsLayer` transform, so pinching
 * costs one matrix change per frame rather than a decode.
 */
@Composable
fun ZoomableImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    /** The furthest a pinch can enlarge the image. */
    maxScale: Float = 4f,
    /** The level a double-tap zooms to. */
    doubleTapScale: Float = 2.5f
) {
    if (url.isNullOrBlank()) return
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .pointerInput(url, maxScale) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        when {
                            // A pinch is unambiguous: take it, wherever the
                            // fingers landed.
                            pressed >= 2 -> {
                                scale = (scale * zoomChange).coerceIn(1f, maxScale)
                                offset = clampOffset(
                                    offset + panChange,
                                    scale,
                                    size.width.toFloat(),
                                    size.height.toFloat()
                                )
                                event.changes.forEach { it.consume() }
                            }
                            // One finger while enlarged: pan, but never past the
                            // image's own edge — that would show the backdrop and
                            // leave the picture somewhere unreachable.
                            scale > 1.001f -> {
                                offset = clampOffset(
                                    offset + panChange,
                                    scale,
                                    size.width.toFloat(),
                                    size.height.toFloat()
                                )
                                event.changes.forEach { it.consume() }
                            }
                            // At 1x, leave the event alone: pager + page scroll.
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(url) {
                detectTapGestures(
                    onDoubleTap = {
                        val target = if (scale > 1.05f) 1f else doubleTapScale
                        scope.launch {
                            animate(
                                initialValue = scale,
                                targetValue = target,
                                animationSpec = spring()
                            ) { value, _ -> scale = value }
                            if (target == 1f) offset = Offset.Zero
                        }
                    }
                )
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val shown = clampOffset(offset, scale, widthPx, heightPx)
        RemoteImage(
            url = url,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = shown.x
                    translationY = shown.y
                },
            contentScale = contentScale
        )
    }
}

/**
 * Keeps [offset] inside the part of the scaled image that hangs off the viewport.
 *
 * At 1× there is nothing to pan, so the answer is no movement at all; at 2× half
 * of the (now doubled) image hangs off each edge, which is the furthest the pan
 * may go.
 */
internal fun clampOffset(
    offset: Offset,
    scale: Float,
    width: Float,
    height: Float
): Offset {
    if (width <= 0f || height <= 0f) return Offset.Zero
    val maxX = (width * (scale - 1f)).coerceAtLeast(0f) / 2f
    val maxY = (height * (scale - 1f)).coerceAtLeast(0f) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY)
    )
}
