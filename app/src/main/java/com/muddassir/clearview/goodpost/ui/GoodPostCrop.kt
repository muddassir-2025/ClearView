package com.muddassir.clearview.goodpost.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GoodPostAttachment
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostUploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Cropping a picked image before it is posted (§21).
 *
 * WhatsApp's crop, and the same interaction: the picture sits still, fitted
 * inside the screen, and a CROP BOX is dragged over it — the corners and the
 * edges resize it, and dragging inside it moves it. The thirds grid under the
 * crop box is the one bit of guidance worth drawing; it is where a crop gets
 * decided.
 *
 * The box is what is adjustable, deliberately — a fixed frame the picture slides
 * under cannot frame a face at the edge of a photo, which is the whole reason to
 * crop at all.
 *
 * The screen is a self-contained full-window step. It reads the picked `uri`,
 * shows the picture, and hands back a fresh [GoodPostAttachment] pointing at a
 * cropped copy written to this app's cache — so nothing about the composer, the
 * upload handshake or the ViewModel has to know cropping happened.
 *
 * Deliberately NOT the olive post card: this is a tool, not a post, so it wears
 * the app's Material surface.
 */
@Composable
internal fun GoodPostCropScreen(
    imageUri: String,
    onCropped: (GoodPostAttachment) -> Unit,
    onCancel: () -> Unit,
    onFailed: () -> Unit,
    /** Open framing a square — the shape a channel's avatar is drawn in. */
    startSquare: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var bitmap by remember(imageUri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(imageUri) { mutableStateOf(false) }
    var busy by remember(imageUri) { mutableStateOf(false) }

    var aspect by remember(imageUri) {
        mutableStateOf(if (startSquare) CropAspect.Square else CropAspect.Free)
    }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // The crop box, in the crop area's own coordinates. Null until the picture
    // and the area are both known, so nothing is framed against a zero box.
    var cropRect by remember(imageUri) { mutableStateOf<Rect?>(null) }

    LaunchedEffect(imageUri) {
        val decoded = GoodPostImages.loadLocal(context, imageUri, CROP_MAX_PX)
        if (decoded == null) failed = true else bitmap = decoded
    }
    // A file this app cannot decode is a file it cannot post, and the caller
    // words that with the same refusal every other unsupported pick gets.
    LaunchedEffect(failed) { if (failed) onFailed() }

    val handleTouch = with(density) { HANDLE_TOUCH.toPx() }
    val minSize = with(density) { MIN_CROP.toPx() }

    val imageRect = remember(boxSize, bitmap, density) {
        val current = bitmap
        if (current == null || boxSize.width <= 0 || boxSize.height <= 0) Rect.Zero
        else fitImageRect(
            boxWidth = boxSize.width.toFloat(),
            boxHeight = boxSize.height.toFloat(),
            imageWidth = current.width,
            imageHeight = current.height
        )
    }

    // (Re)frame whenever the picture, the area or the chosen shape changes: a new
    // shape is a new framing decision, so it starts fresh rather than carrying a
    // box that no longer matches.
    LaunchedEffect(imageRect, aspect) {
        if (imageRect.width > 0f && imageRect.height > 0f) {
            cropRect = initialCropRect(imageRect, aspect.ratio(bitmap), minSize)
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { if (!busy) onCancel() }) {
                        Text(
                            text = stringResource(R.string.goodpost_cancel),
                            color = Wa.Text,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Text(
                        text = stringResource(R.string.goodpost_crop_title),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        color = Wa.Text,
                        style = MaterialTheme.typography.titleMedium
                    )
                    TextButton(
                        enabled = bitmap != null && !busy && cropRect != null,
                        onClick = {
                            val current = bitmap ?: return@TextButton
                            val box = cropRect ?: return@TextButton
                            busy = true
                            scope.launch {
                                val cropped = withContext(Dispatchers.Default) {
                                    val rect = cropSourceRect(imageRect, box, current.width, current.height)
                                    val piece = runCatching {
                                        Bitmap.createBitmap(current, rect[0], rect[1], rect[2], rect[3])
                                    }.getOrNull()
                                    piece?.let { writeCroppedImage(context, it) }
                                }
                                if (cropped == null) {
                                    busy = false
                                    onFailed()
                                } else {
                                    onCropped(cropped)
                                }
                            }
                        }
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Wa.Accent
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.goodpost_crop_done),
                                color = Wa.Accent,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { boxSize = it },
                    contentAlignment = Alignment.Center
                ) {
                    val current = bitmap
                    if (current == null) {
                        CircularProgressIndicator(color = Wa.Accent)
                    } else if (imageRect.width > 0f) {
                        CropStage(
                            bitmap = current,
                            imageRect = imageRect,
                            cropRect = cropRect ?: Rect.Zero,
                            handleTouch = handleTouch,
                            minSize = minSize,
                            onCropRectChange = { cropRect = it }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CropAspect.entries.forEachIndexed { index, option ->
                        if (index > 0) Spacer(Modifier.width(8.dp))
                        WaFilterPill(
                            label = stringResource(option.labelRes),
                            selected = aspect == option,
                            onClick = { aspect = option }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.goodpost_crop_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 16.dp),
                    textAlign = TextAlign.Center,
                    color = Wa.TextDim,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** The fitted picture with the adjustable crop box over it. */
@Composable
private fun CropStage(
    bitmap: Bitmap,
    imageRect: Rect,
    cropRect: Rect,
    handleTouch: Float,
    minSize: Float,
    onCropRectChange: (Rect) -> Unit
) {
    // The gesture's own memory: which handle it grabbed, the box as it was when
    // the drag began, and the drag since then. Held in a plain object rather
    // than in state — none of it should recompose anything.
    val drag = remember { DragState() }
    // The box as it is NOW. This is load-bearing: the pointer detector is keyed
    // on the picture area, not on the box, so its closure is created once and
    // would otherwise keep the box from the frame the drag started on — every
    // drag event would then be applied to that stale rectangle and the box
    // would never move.
    val liveRect = rememberUpdatedState(cropRect)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(imageRect) {
                detectDragGestures(
                    onDragStart = { start ->
                        drag.handle = hitHandle(start, liveRect.value, handleTouch)
                        drag.start = liveRect.value
                        drag.accumulated = Offset.Zero
                    },
                    onDragEnd = { drag.handle = Handle.None },
                    onDragCancel = { drag.handle = Handle.None },
                    onDrag = { change, delta ->
                        change.consume()
                        if (drag.handle == Handle.None) return@detectDragGestures
                        // Measured from the drag's own start, so the box follows
                        // the finger instead of drifting a step at a time.
                        drag.accumulated += delta
                        onCropRectChange(
                            dragCrop(
                                handle = drag.handle,
                                rect = drag.start,
                                dx = drag.accumulated.x,
                                dy = drag.accumulated.y,
                                bounds = imageRect,
                                minSize = minSize
                            )
                        )
                    }
                )
            }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Everything outside the crop box is dimmed, so the kept area reads
            // as the picture and the rest as what is being left out.
            val shade = Color.Black.copy(alpha = 0.55f)
            drawRect(shade, Offset(imageRect.left, imageRect.top), Size(imageRect.width, cropRect.top - imageRect.top))
            drawRect(shade, Offset(imageRect.left, cropRect.bottom), Size(imageRect.width, imageRect.bottom - cropRect.bottom))
            drawRect(shade, Offset(imageRect.left, cropRect.top), Size(cropRect.left - imageRect.left, cropRect.height))
            drawRect(shade, Offset(cropRect.right, cropRect.top), Size(imageRect.right - cropRect.right, cropRect.height))

            // Thirds inside the box.
            val grid = Color.White.copy(alpha = 0.55f)
            val stroke = 1.dp.toPx()
            for (i in 1..2) {
                val x = cropRect.left + cropRect.width * i / 3f
                val y = cropRect.top + cropRect.height * i / 3f
                drawLine(grid, Offset(x, cropRect.top), Offset(x, cropRect.bottom), stroke)
                drawLine(grid, Offset(cropRect.left, y), Offset(cropRect.right, y), stroke)
            }
            drawRect(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = Offset(cropRect.left, cropRect.top),
                size = Size(cropRect.width, cropRect.height),
                style = Stroke(2.dp.toPx())
            )

            // A knob at each corner — the four grab points, like WhatsApp.
            val knob = 4.dp.toPx()
            handlePositions(cropRect).forEach { point ->
                drawCircle(Color.White, radius = knob, center = point)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.4f),
                    radius = knob,
                    center = point,
                    style = Stroke(1.dp.toPx())
                )
            }
        }
    }
}

/** What a drag in progress is doing, kept across its pointer events. */
private class DragState {
    var handle: Handle = Handle.None
    var start: Rect = Rect.Zero
    var accumulated: Offset = Offset.Zero
}

/**
 * The grab points on the crop box: its four corners and its four sides.
 *
 * The sides are dragged on one axis each — left and right horizontally, top and
 * bottom vertically — which is what "adjust it" means when a crop is being
 * nudged rather than rethought. The corners, which move both axes at once, are
 * still there for the big move.
 */
internal enum class Handle { None, TopLeft, TopRight, BottomLeft, BottomRight, Top, Bottom, Left, Right }

/** Where the corner and side knobs sit, in the crop area's coordinates. */
private fun handlePositions(rect: Rect): List<Offset> = listOf(
    Offset(rect.left, rect.top),
    Offset(rect.right, rect.top),
    Offset(rect.left, rect.bottom),
    Offset(rect.right, rect.bottom),
    Offset(rect.center.x, rect.top),
    Offset(rect.center.x, rect.bottom),
    Offset(rect.left, rect.center.y),
    Offset(rect.right, rect.center.y)
)

/** Which handle a touch landed on, or [Handle.None]. Corners win over sides. */
internal fun hitHandle(point: Offset, rect: Rect, touch: Float): Handle {
    if (rect.width <= 0f || rect.height <= 0f) return Handle.None
    val nearLeft = abs(point.x - rect.left) <= touch
    val nearRight = abs(point.x - rect.right) <= touch
    val nearTop = abs(point.y - rect.top) <= touch
    val nearBottom = abs(point.y - rect.bottom) <= touch
    val withinX = point.x >= rect.left - touch && point.x <= rect.right + touch
    val withinY = point.y >= rect.top - touch && point.y <= rect.bottom + touch

    return when {
        nearLeft && nearTop -> Handle.TopLeft
        nearRight && nearTop -> Handle.TopRight
        nearLeft && nearBottom -> Handle.BottomLeft
        nearRight && nearBottom -> Handle.BottomRight
        nearTop && withinX -> Handle.Top
        nearBottom && withinX -> Handle.Bottom
        nearLeft && withinY -> Handle.Left
        nearRight && withinY -> Handle.Right
        else -> Handle.None
    }
}

/**
 * Apply a drag to the crop box.
 *
 * Pure, and the whole of the resize rule: it clamps to [bounds], keeps a minimum
 * size, and never lets an edge cross its opposite. Tested directly.
 */
internal fun dragCrop(
    handle: Handle,
    rect: Rect,
    dx: Float,
    dy: Float,
    bounds: Rect,
    minSize: Float
): Rect {
    if (handle == Handle.None || bounds.width <= 0f || bounds.height <= 0f) return rect

    var left = rect.left
    var top = rect.top
    var right = rect.right
    var bottom = rect.bottom

    when (handle) {
        Handle.TopLeft, Handle.Left, Handle.BottomLeft ->
            left = (left + dx).coerceIn(bounds.left, right - minSize)
        else -> {}
    }
    when (handle) {
        Handle.TopRight, Handle.Right, Handle.BottomRight ->
            right = (right + dx).coerceIn(left + minSize, bounds.right)
        else -> {}
    }
    when (handle) {
        Handle.TopLeft, Handle.Top, Handle.TopRight ->
            top = (top + dy).coerceIn(bounds.top, bottom - minSize)
        else -> {}
    }
    when (handle) {
        Handle.BottomLeft, Handle.Bottom, Handle.BottomRight ->
            bottom = (bottom + dy).coerceIn(top + minSize, bounds.bottom)
        else -> {}
    }
    return Rect(left, top, right, bottom)
}

/** The crop box's size options, and each one's name. */
private enum class CropAspect(val labelRes: Int) {
    Free(R.string.goodpost_crop_free),
    Original(R.string.goodpost_crop_original),
    Square(R.string.goodpost_crop_square),
    Portrait(R.string.goodpost_crop_portrait),
    Wide(R.string.goodpost_crop_wide);

    /** The width-to-height ratio this option frames, or null for the free box. */
    fun ratio(bitmap: Bitmap?): Float? = when (this) {
        Free -> null
        Original -> when {
            bitmap == null || bitmap.height <= 0 -> null
            else -> bitmap.width.toFloat() / bitmap.height
        }
        Square -> 1f
        Portrait -> 4f / 5f
        Wide -> 16f / 9f
    }
}

/**
 * Where a picture of [imageWidth] x [imageHeight] lands inside a
 * [boxWidth] x [boxHeight] area drawn with `ContentScale.Fit` — centred, whole,
 * and never stretched. Pure so the framing can be tested without a screen.
 */
internal fun fitImageRect(
    boxWidth: Float,
    boxHeight: Float,
    imageWidth: Int,
    imageHeight: Int
): Rect {
    if (boxWidth <= 0f || boxHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) return Rect.Zero
    val scale = minOf(boxWidth / imageWidth, boxHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (boxWidth - width) / 2f
    val top = (boxHeight - height) / 2f
    return Rect(left, top, left + width, top + height)
}

/**
 * The crop box a shape starts from: the largest rectangle of [ratio] inside
 * [imageRect], centred, and at least [minSize] on its short side where the
 * picture allows it. A null ratio — the free box — starts as the whole picture.
 */
internal fun initialCropRect(imageRect: Rect, ratio: Float?, minSize: Float): Rect {
    if (imageRect.width <= 0f || imageRect.height <= 0f) return Rect.Zero
    if (ratio == null || ratio <= 0f) return imageRect

    var width = imageRect.width
    var height = width / ratio
    if (height > imageRect.height) {
        height = imageRect.height
        width = height * ratio
    }
    // Never ask for more than the picture has; a ratio larger than the picture
    // simply fills it on the long side.
    if (width < minSize || height < minSize) {
        val grow = maxOf(minSize / width, minSize / height, 1f)
        val grownWidth = (width * grow).coerceAtMost(imageRect.width)
        val grownHeight = (height * grow).coerceAtMost(imageRect.height)
        width = grownWidth
        height = grownHeight
    }
    val left = imageRect.left + (imageRect.width - width) / 2f
    val top = imageRect.top + (imageRect.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

/**
 * The region of the source image the crop box selects, in image pixels.
 *
 * Returns `[x, y, width, height]`, clamped so a crop can never name pixels that
 * are not there. Pure, and the only place the crop box is turned into a
 * bitmap rectangle, so it is tested directly.
 */
internal fun cropSourceRect(imageRect: Rect, cropRect: Rect, imageWidth: Int, imageHeight: Int): IntArray {
    if (imageWidth <= 0 || imageHeight <= 0 || imageRect.width <= 0f) return intArrayOf(0, 0, 0, 0)
    val scale = imageRect.width / imageWidth

    val x0 = floor((cropRect.left - imageRect.left) / scale).toInt().coerceIn(0, imageWidth - 1)
    val y0 = floor((cropRect.top - imageRect.top) / scale).toInt().coerceIn(0, imageHeight - 1)
    val x1 = ceil((cropRect.right - imageRect.left) / scale).toInt().coerceIn(x0 + 1, imageWidth)
    val y1 = ceil((cropRect.bottom - imageRect.top) / scale).toInt().coerceIn(y0 + 1, imageHeight)
    return intArrayOf(x0, y0, x1 - x0, y1 - y0)
}

/**
 * Write a cropped bitmap to the app's cache and describe it as an attachment.
 *
 * The file is a `file://` URI, and that is deliberate: it is read back by this
 * app's own `contentResolver` for the preview and for the upload, never handed
 * to another app, so no provider is needed. JPEG at [CROP_QUALITY] is what every
 * camera already produces and what the server's allow-list takes.
 */
private fun writeCroppedImage(context: android.content.Context, bitmap: Bitmap): GoodPostAttachment? =
    runCatching {
        val dir = File(context.cacheDir, CROP_DIR).apply { mkdirs() }
        val file = File(dir, "crop-${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, CROP_QUALITY, out)
        }
        GoodPostAttachment(
            uri = Uri.fromFile(file).toString(),
            kind = "image",
            contentType = "image/jpeg",
            byteSize = file.length(),
            width = bitmap.width,
            height = bitmap.height,
            state = GoodPostUploadState.Uploading
        )
    }.getOrNull()

/** How wide a picture is decoded for cropping — enough to crop and re-encode. */
private const val CROP_MAX_PX = 2600

private const val CROP_QUALITY = 92
private const val CROP_DIR = "goodpost-crops"

/** The smallest a crop box may be dragged, so it can always still be grabbed. */
private val MIN_CROP = 72.dp

/** How far from a handle a touch still counts as that handle. */
private val HANDLE_TOUCH = 28.dp
