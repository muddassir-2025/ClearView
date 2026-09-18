package com.muddassir.clearview.goodpost.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.InsertEmoticon
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GoodPostAttachment
import com.muddassir.clearview.goodpost.data.readGoodPostAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Modes of the media editor
 */
internal enum class EditorTool {
    NONE,
    CROP,
    DRAW,
    TEXT,
    STICKER
}

internal enum class BrushType {
    PEN,
    HIGHLIGHTER,
    BLUR
}

internal enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("Free", null),
    SQUARE("1:1", 1.0f),
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f)
}

/**
 * A single stroke drawn on the canvas
 */
internal data class StrokeItem(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
    val brushType: BrushType
)

/**
 * A text overlay placed on the image
 */
internal data class TextOverlay(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var color: Color = Color.White,
    var bgMode: Int = 0, // 0 = transparent, 1 = semi-black, 2 = solid color
    var offset: Offset = Offset.Zero,
    var scale: Float = 1.0f,
    var rotation: Float = 0.0f
)

/**
 * A sticker/emoji placed on the image
 */
internal data class StickerOverlay(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    var offset: Offset = Offset.Zero,
    var scale: Float = 1.0f,
    var rotation: Float = 0.0f
)

/**
 * WhatsApp-style Rainbow Palette
 */
internal val WA_PALETTE = listOf(
    Color(0xFFFFFFFF), // White
    Color(0xFFFF0000), // Red
    Color(0xFFFF9800), // Orange
    Color(0xFFFFEB3B), // Yellow
    Color(0xFF4CAF50), // Green
    Color(0xFF00A884), // WhatsApp Teal
    Color(0xFF00BCD4), // Cyan
    Color(0xFF2196F3), // Blue
    Color(0xFF9C27B0), // Purple
    Color(0xFFE91E63), // Pink
    Color(0xFF000000)  // Black
)

/**
 * Common WhatsApp-style emoji categories
 */
internal val WA_EMOJIS = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
    "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
    "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩",
    "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣",
    "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬",
    "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗",
    "🤔", "🤭", "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯",
    "👍", "👎", "👌", "🤌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈",
    "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐", "🖖", "👋", "👏",
    "🙌", "👐", "🤲", "🤝", "🙏", "✍️", "💅", "🤳", "💪", "🦾",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
    "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "🔥",
    "✨", "🌟", "⭐", "🎉", "🎊", "🏆", "🥇", "⚽", "🏀", "🚀"
)

/**
 * WhatsApp-Style Media Editor for images and videos
 */
@Composable
internal fun GoodPostMediaEditor(
    uris: List<Uri>,
    channelName: String,
    onDismiss: () -> Unit,
    onSend: (List<GoodPostAttachment>, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (uris.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    var activeIndex by remember { mutableIntStateOf(0) }
    val currentUri = uris.getOrElse(activeIndex) { uris.first() }
    val isVideo = remember(currentUri) {
        context.contentResolver.getType(currentUri)?.startsWith("video/") == true
    }

    // Tools state
    var activeTool by remember { mutableStateOf(EditorTool.NONE) }
    var caption by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    // Video editing state
    var isMuted by remember { mutableStateOf(false) }
    var videoDurationMs by remember { mutableLongStateOf(0L) }
    var trimStartRatio by remember { mutableFloatStateOf(0.0f) }
    var trimEndRatio by remember { mutableFloatStateOf(1.0f) }

    // Drawing state
    val strokes = remember { mutableStateListOf<StrokeItem>() }
    val undoneStrokes = remember { mutableStateListOf<StrokeItem>() }
    var currentBrushType by remember { mutableStateOf(BrushType.PEN) }
    var currentBrushColor by remember { mutableStateOf(Color.White) }
    var currentBrushWidth by remember { mutableFloatStateOf(12f) }
    var currentPathPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    // Text state
    val textOverlays = remember { mutableStateListOf<TextOverlay>() }
    var editingTextId by remember { mutableStateOf<String?>(null) }
    var textInput by remember { mutableStateOf("") }
    var textColor by remember { mutableStateOf(Color.White) }
    var textBgMode by remember { mutableIntStateOf(0) }

    // Sticker state
    val stickerOverlays = remember { mutableStateListOf<StickerOverlay>() }
    var showEmojiPicker by remember { mutableStateOf(false) }

    // Crop / Rotate state
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var flipHorizontal by remember { mutableStateOf(false) }
    var cropAspect by remember { mutableStateOf(CropAspect.FREE) }

    // Original image bitmap
    var originalBitmap by remember(currentUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(currentUri) {
        if (!isVideo) {
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(currentUri)?.use { stream ->
                        originalBitmap = BitmapFactory.decodeStream(stream)
                    }
                } catch (e: Exception) {
                    originalBitmap = null
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, currentUri)
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    videoDurationMs = dur
                    retriever.release()
                } catch (e: Exception) {
                    videoDurationMs = 0L
                }
            }
        }
    }

    // Reset edits when switching media
    LaunchedEffect(activeIndex) {
        strokes.clear()
        undoneStrokes.clear()
        textOverlays.clear()
        stickerOverlays.clear()
        rotationAngle = 0f
        flipHorizontal = false
        activeTool = EditorTool.NONE
    }

    BackHandler {
        if (activeTool != EditorTool.NONE) {
            activeTool = EditorTool.NONE
        } else {
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .imePadding()
    ) {
        // Center Media Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 64.dp, bottom = 120.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isVideo) {
                VideoPreviewPlayer(
                    uri = currentUri,
                    isMuted = isMuted,
                    trimStartRatio = trimStartRatio,
                    trimEndRatio = trimEndRatio
                )
            } else {
                originalBitmap?.let { bmp ->
                    ImageEditorCanvas(
                        bitmap = bmp,
                        rotationAngle = rotationAngle,
                        flipHorizontal = flipHorizontal,
                        cropAspect = cropAspect,
                        strokes = strokes,
                        currentPathPoints = currentPathPoints,
                        currentBrushColor = currentBrushColor,
                        currentBrushWidth = currentBrushWidth,
                        currentBrushType = currentBrushType,
                        textOverlays = textOverlays,
                        stickerOverlays = stickerOverlays,
                        isDrawing = activeTool == EditorTool.DRAW,
                        onDrawPoint = { currentPathPoints = currentPathPoints + it },
                        onDrawEnd = {
                            if (currentPathPoints.isNotEmpty()) {
                                strokes.add(
                                    StrokeItem(
                                        points = currentPathPoints,
                                        color = currentBrushColor,
                                        strokeWidth = currentBrushWidth,
                                        brushType = currentBrushType
                                    )
                                )
                                undoneStrokes.clear()
                                currentPathPoints = emptyList()
                            }
                        },
                        onEditText = { overlay ->
                            editingTextId = overlay.id
                            textInput = overlay.text
                            textColor = overlay.color
                            textBgMode = overlay.bgMode
                            activeTool = EditorTool.TEXT
                        }
                    )
                } ?: run {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = Wa.Accent
                    )
                }
            }
        }

        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close / Cancel
            IconButton(
                onClick = {
                    if (activeTool != EditorTool.NONE) activeTool = EditorTool.NONE
                    else onDismiss()
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.goodpost_close),
                    tint = Color.White
                )
            }

            Spacer(Modifier.weight(1f))

            if (!isVideo) {
                // Crop / Rotate
                IconButton(
                    onClick = {
                        activeTool = if (activeTool == EditorTool.CROP) EditorTool.NONE else EditorTool.CROP
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.CropRotate,
                        contentDescription = stringResource(R.string.goodpost_crop_rotate),
                        tint = if (activeTool == EditorTool.CROP) Wa.Accent else Color.White
                    )
                }

                // Sticker / Emoji
                IconButton(
                    onClick = { showEmojiPicker = true }
                ) {
                    Icon(
                        imageVector = Icons.Filled.InsertEmoticon,
                        contentDescription = stringResource(R.string.goodpost_stickers),
                        tint = Color.White
                    )
                }

                // Text tool
                IconButton(
                    onClick = {
                        editingTextId = null
                        textInput = ""
                        textColor = Color.White
                        textBgMode = 0
                        activeTool = EditorTool.TEXT
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Title,
                        contentDescription = stringResource(R.string.goodpost_text),
                        tint = if (activeTool == EditorTool.TEXT) Wa.Accent else Color.White
                    )
                }

                // Pen / Drawing tool
                IconButton(
                    onClick = {
                        activeTool = if (activeTool == EditorTool.DRAW) EditorTool.NONE else EditorTool.DRAW
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Brush,
                        contentDescription = stringResource(R.string.goodpost_draw),
                        tint = if (activeTool == EditorTool.DRAW) Wa.Accent else Color.White
                    )
                }

                // Undo
                if (strokes.isNotEmpty() || textOverlays.isNotEmpty() || stickerOverlays.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            if (strokes.isNotEmpty()) {
                                val last = strokes.removeLast()
                                undoneStrokes.add(last)
                            } else if (textOverlays.isNotEmpty()) {
                                textOverlays.removeLast()
                            } else if (stickerOverlays.isNotEmpty()) {
                                stickerOverlays.removeLast()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Undo,
                            contentDescription = stringResource(R.string.goodpost_undo),
                            tint = Color.White
                        )
                    }
                }
            } else {
                // Video: Mute / Unmute
                IconButton(
                    onClick = { isMuted = !isMuted }
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) stringResource(R.string.goodpost_unmute_video) else stringResource(R.string.goodpost_mute_video),
                        tint = if (isMuted) Wa.Danger else Color.White
                    )
                }
            }
        }

        // Sub-tool panel: Crop & Rotate Controls
        AnimatedVisibility(
            visible = activeTool == EditorTool.CROP,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 60.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC111B21))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rotate 90 deg
                IconButton(onClick = { rotationAngle = (rotationAngle + 90f) % 360f }) {
                    Icon(Icons.Filled.RotateRight, contentDescription = stringResource(R.string.goodpost_rotate), tint = Color.White)
                }
                // Flip horizontal
                IconButton(onClick = { flipHorizontal = !flipHorizontal }) {
                    Icon(Icons.Filled.Flip, contentDescription = "Flip", tint = if (flipHorizontal) Wa.Accent else Color.White)
                }
                // Aspect ratios
                CropAspect.entries.forEach { aspect ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (cropAspect == aspect) Wa.Accent else Color(0xFF202C33))
                            .clickable { cropAspect = aspect }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = aspect.label,
                            color = if (cropAspect == aspect) Wa.OnAccent else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                // Done button
                IconButton(onClick = { activeTool = EditorTool.NONE }) {
                    Icon(Icons.Filled.Check, contentDescription = "Done", tint = Wa.Accent)
                }
            }
        }

        // Sub-tool panel: Drawing Color & Brush Options
        AnimatedVisibility(
            visible = activeTool == EditorTool.DRAW,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 60.dp)
                .align(Alignment.TopCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC111B21))
                    .padding(vertical = 8.dp)
            ) {
                // Brush types: Pen / Highlighter / Blur
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrushType.entries.forEach { bType ->
                        val selected = currentBrushType == bType
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) Wa.Accent else Color(0xFF202C33))
                                .clickable { currentBrushType = bType }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = when (bType) {
                                    BrushType.PEN -> stringResource(R.string.goodpost_pen)
                                    BrushType.HIGHLIGHTER -> stringResource(R.string.goodpost_highlighter)
                                    BrushType.BLUR -> stringResource(R.string.goodpost_blur)
                                },
                                color = if (selected) Wa.OnAccent else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Color picker palette
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(WA_PALETTE) { color ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (currentBrushColor == color) 2.5.dp else 1.dp,
                                    color = if (currentBrushColor == color) Color.White else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { currentBrushColor = color }
                        )
                    }
                }
            }
        }

        // Sub-tool panel: Video Trim Timeline Bar
        if (isVideo && videoDurationMs > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
                    .background(Color(0xCC111B21), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.goodpost_trim_video),
                        color = Wa.TextDim,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    val trimmedDur = ((trimEndRatio - trimStartRatio) * videoDurationMs).toLong()
                    Text(
                        text = formatDuration(trimmedDur) + " / " + formatDuration(videoDurationMs),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Start & End Sliders
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = trimEndRatio,
                        onValueChange = { if (it > trimStartRatio + 0.05f) trimEndRatio = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Wa.Accent,
                            activeTrackColor = Wa.Accent,
                            inactiveTrackColor = Color.DarkGray
                        )
                    )
                }
            }
        }

        // Bottom Controls: Multi-thumbnail tray + Caption + Send
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xE60B141A))
                .padding(vertical = 8.dp)
        ) {
            // Multiple images carousel
            if (uris.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uris.indices.toList()) { index ->
                        val uri = uris[index]
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (activeIndex == index) 2.5.dp else 1.dp,
                                    color = if (activeIndex == index) Wa.Accent else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { activeIndex = index }
                        ) {
                            ThumbnailPreview(uri = uri)
                        }
                    }
                }
            }

            // Caption input + Send button row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Wa.Bar)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.InsertEmoticon,
                        contentDescription = null,
                        tint = Wa.TextDim,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { showEmojiPicker = true }
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = caption,
                        onValueChange = { caption = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(Wa.Accent),
                        maxLines = 3,
                        decorationBox = { inner ->
                            if (caption.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.goodpost_caption_hint),
                                    color = Wa.TextDim,
                                    fontSize = 16.sp
                                )
                            }
                            inner()
                        }
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Green Send circular FAB
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Wa.Accent)
                        .clickable(enabled = !isProcessing) {
                            isProcessing = true
                            scope.launch {
                                val processed = withContext(Dispatchers.IO) {
                                    processMediaItems(
                                        context = context,
                                        uris = uris,
                                        activeIndex = activeIndex,
                                        originalBitmap = originalBitmap,
                                        rotationAngle = rotationAngle,
                                        flipHorizontal = flipHorizontal,
                                        cropAspect = cropAspect,
                                        strokes = strokes,
                                        textOverlays = textOverlays,
                                        stickerOverlays = stickerOverlays
                                    )
                                }
                                isProcessing = false
                                onSend(processed, caption)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Wa.OnAccent,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.goodpost_send),
                            tint = Wa.OnAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Target recipient chip (e.g. "> ClearView")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF182229))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "› $channelName",
                        color = Wa.TextDim,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Text Overlay Input Dialog
        if (activeTool == EditorTool.TEXT) {
            TextInputDialog(
                initialText = textInput,
                initialColor = textColor,
                initialBgMode = textBgMode,
                onDismiss = { activeTool = EditorTool.NONE },
                onDone = { text, color, bgMode ->
                    if (text.isNotBlank()) {
                        val existing = textOverlays.firstOrNull { it.id == editingTextId }
                        if (existing != null) {
                            existing.text = text
                            existing.color = color
                            existing.bgMode = bgMode
                        } else {
                            textOverlays.add(
                                TextOverlay(
                                    text = text,
                                    color = color,
                                    bgMode = bgMode
                                )
                            )
                        }
                    }
                    activeTool = EditorTool.NONE
                }
            )
        }

        // Emoji / Sticker Picker Dialog
        if (showEmojiPicker) {
            EmojiPickerBottomSheet(
                onSelectEmoji = { emoji ->
                    stickerOverlays.add(StickerOverlay(emoji = emoji))
                    showEmojiPicker = false
                },
                onDismiss = { showEmojiPicker = false }
            )
        }
    }
}

/**
 * Image Editor Canvas with Drawing, Transformations, Text, and Stickers
 */
@Composable
private fun ImageEditorCanvas(
    bitmap: Bitmap,
    rotationAngle: Float,
    flipHorizontal: Boolean,
    cropAspect: CropAspect,
    strokes: List<StrokeItem>,
    currentPathPoints: List<Offset>,
    currentBrushColor: Color,
    currentBrushWidth: Float,
    currentBrushType: BrushType,
    textOverlays: List<TextOverlay>,
    stickerOverlays: List<StickerOverlay>,
    isDrawing: Boolean,
    onDrawPoint: (Offset) -> Unit,
    onDrawEnd: () -> Unit,
    onEditText: (TextOverlay) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isDrawing) {
                if (isDrawing) {
                    detectDragGestures(
                        onDragStart = { offset -> onDrawPoint(offset) },
                        onDrag = { change, _ ->
                            change.consume()
                            onDrawPoint(change.position)
                        },
                        onDragEnd = onDrawEnd,
                        onDragCancel = onDrawEnd
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Base image with transforms
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.rotationZ = rotationAngle
                    this.scaleX = if (flipHorizontal) -1f else 1f
                },
            contentScale = ContentScale.Fit
        )

        // Freehand Drawing Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw finalized strokes
            strokes.forEach { stroke ->
                if (stroke.points.size > 1) {
                    val path = Path().apply {
                        moveTo(stroke.points[0].x, stroke.points[0].y)
                        for (i in 1 until stroke.points.size) {
                            lineTo(stroke.points[i].x, stroke.points[i].y)
                        }
                    }
                    val strokeAlpha = when (stroke.brushType) {
                        BrushType.HIGHLIGHTER -> 0.45f
                        BrushType.BLUR -> 0.6f
                        BrushType.PEN -> 1.0f
                    }
                    drawPath(
                        path = path,
                        color = stroke.color.copy(alpha = strokeAlpha),
                        style = Stroke(
                            width = stroke.strokeWidth,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round
                        )
                    )
                }
            }

            // Draw current active stroke
            if (currentPathPoints.size > 1) {
                val path = Path().apply {
                    moveTo(currentPathPoints[0].x, currentPathPoints[0].y)
                    for (i in 1 until currentPathPoints.size) {
                        lineTo(currentPathPoints[i].x, currentPathPoints[i].y)
                    }
                }
                val alpha = if (currentBrushType == BrushType.HIGHLIGHTER) 0.45f else 1.0f
                drawPath(
                    path = path,
                    color = currentBrushColor.copy(alpha = alpha),
                    style = Stroke(
                        width = currentBrushWidth,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
        }

        // Draggable Text Overlays
        textOverlays.forEach { overlay ->
            var offset by remember { mutableStateOf(overlay.offset) }
            var scale by remember { mutableFloatStateOf(overlay.scale) }
            var rotation by remember { mutableFloatStateOf(overlay.rotation) }

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = offset.x
                        translationY = offset.y
                        scaleX = scale
                        scaleY = scale
                        rotationZ = rotation
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rot ->
                            offset += pan
                            scale *= zoom
                            rotation += rot
                            overlay.offset = offset
                            overlay.scale = scale
                            overlay.rotation = rotation
                        }
                    }
                    .clickable { onEditText(overlay) }
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when (overlay.bgMode) {
                            1 -> Color(0x99000000)
                            2 -> overlay.color.copy(alpha = 0.85f)
                            else -> Color.Transparent
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = overlay.text,
                    color = if (overlay.bgMode == 2) Color.Black else overlay.color,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Draggable Stickers & Emojis
        stickerOverlays.forEach { sticker ->
            var offset by remember { mutableStateOf(sticker.offset) }
            var scale by remember { mutableFloatStateOf(sticker.scale) }
            var rotation by remember { mutableFloatStateOf(sticker.rotation) }

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = offset.x
                        translationY = offset.y
                        scaleX = scale
                        scaleY = scale
                        rotationZ = rotation
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rot ->
                            offset += pan
                            scale *= zoom
                            rotation += rot
                            sticker.offset = offset
                            sticker.scale = scale
                            sticker.rotation = rotation
                        }
                    }
            ) {
                Text(
                    text = sticker.emoji,
                    fontSize = 48.sp
                )
            }
        }
    }
}

/**
 * Looping Video Player for Media Editor Preview
 */
@Composable
private fun VideoPreviewPlayer(
    uri: Uri,
    isMuted: Boolean,
    trimStartRatio: Float,
    trimEndRatio: Float
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
        }
    }

    DisposableEffect(uri) {
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        onDispose {
            exoPlayer.release()
        }
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Text Overlay Input Modal
 */
@Composable
private fun TextInputDialog(
    initialText: String,
    initialColor: Color,
    initialBgMode: Int,
    onDismiss: () -> Unit,
    onDone: (String, Color, Int) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var color by remember { mutableStateOf(initialColor) }
    var bgMode by remember { mutableIntStateOf(initialBgMode) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xEE0B141A))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top controls: Done & Background mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
                    }

                    // Background mode toggle (A)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF202C33))
                            .clickable { bgMode = (bgMode + 1) % 3 }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = when (bgMode) {
                                0 -> "T"
                                1 -> "T▪"
                                else -> "■"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    TextButton(onClick = { onDone(text, color, bgMode) }) {
                        Text(text = "Done", color = Wa.Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Spacer(Modifier.height(32.dp))

                // Text input
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when (bgMode) {
                                1 -> Color(0x99000000)
                                2 -> color.copy(alpha = 0.85f)
                                else -> Color.Transparent
                            }
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(
                            color = if (bgMode == 2) Color.Black else color,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(Wa.Accent),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.goodpost_text),
                                    color = Color.Gray,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                            inner()
                        }
                    )
                }

                Spacer(Modifier.height(48.dp))

                // Color palette row
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(WA_PALETTE) { itemColor ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(itemColor)
                                .border(
                                    width = if (color == itemColor) 2.5.dp else 1.dp,
                                    color = if (color == itemColor) Color.White else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { color = itemColor }
                        )
                    }
                }
            }
        }
    }
}

/**
 * WhatsApp-Style Emoji Picker Bottom Sheet
 */
@Composable
private fun EmojiPickerBottomSheet(
    onSelectEmoji: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(0xFF1F2C34))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.goodpost_stickers),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier.height(280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(WA_EMOJIS) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onSelectEmoji(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}

/**
 * Thumbnail for multi-media carousel
 */
@Composable
private fun ThumbnailPreview(uri: Uri) {
    val context = LocalContext.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use {
                    bmp = BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {
                bmp = null
            }
        }
    }
    bmp?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } ?: Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.DarkGray)
    )
}

/**
 * Helper to process media items into attachments ready for posting
 */
private suspend fun processMediaItems(
    context: Context,
    uris: List<Uri>,
    activeIndex: Int,
    originalBitmap: Bitmap?,
    rotationAngle: Float,
    flipHorizontal: Boolean,
    cropAspect: CropAspect,
    strokes: List<StrokeItem>,
    textOverlays: List<TextOverlay>,
    stickerOverlays: List<StickerOverlay>
): List<GoodPostAttachment> {
    val results = mutableListOf<GoodPostAttachment>()

    for (i in uris.indices) {
        val uri = uris[i]
        val isCurrentEdited = (i == activeIndex)

        if (isCurrentEdited && originalBitmap != null && (strokes.isNotEmpty() || textOverlays.isNotEmpty() || stickerOverlays.isNotEmpty() || rotationAngle != 0f || flipHorizontal)) {
            // Render composite bitmap with drawing and overlays
            val rendered = renderCompositeBitmap(
                original = originalBitmap,
                rotationAngle = rotationAngle,
                flipHorizontal = flipHorizontal,
                cropAspect = cropAspect,
                strokes = strokes,
                textOverlays = textOverlays,
                stickerOverlays = stickerOverlays
            )

            // Save to cache file
            val cacheFile = File(context.cacheDir, "gp_edited_${System.currentTimeMillis()}_$i.jpg")
            FileOutputStream(cacheFile).use { out ->
                rendered.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            val editedUri = Uri.fromFile(cacheFile)
            readGoodPostAttachment(context, editedUri)?.let { results.add(it) }
        } else {
            readGoodPostAttachment(context, uri)?.let { results.add(it) }
        }
    }

    return results
}

/**
 * Renders transformations, strokes, text, and stickers directly onto a bitmap
 */
private fun renderCompositeBitmap(
    original: Bitmap,
    rotationAngle: Float,
    flipHorizontal: Boolean,
    cropAspect: CropAspect,
    strokes: List<StrokeItem>,
    textOverlays: List<TextOverlay>,
    stickerOverlays: List<StickerOverlay>
): Bitmap {
    val matrix = Matrix().apply {
        if (rotationAngle != 0f) postRotate(rotationAngle)
        if (flipHorizontal) postScale(-1f, 1f)
    }

    val transformed = Bitmap.createBitmap(
        original,
        0,
        0,
        original.width,
        original.height,
        matrix,
        true
    )

    val mutableBitmap = transformed.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(mutableBitmap)

    val scaleFactor = mutableBitmap.width.toFloat() / 1000f

    // Draw strokes
    val paint = Paint().apply {
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    strokes.forEach { stroke ->
        if (stroke.points.size > 1) {
            paint.strokeWidth = stroke.strokeWidth * scaleFactor
            val alpha = when (stroke.brushType) {
                BrushType.HIGHLIGHTER -> 128
                BrushType.BLUR -> 160
                BrushType.PEN -> 255
            }
            val col = stroke.color
            paint.setARGB(
                alpha,
                (col.red * 255).toInt(),
                (col.green * 255).toInt(),
                (col.blue * 255).toInt()
            )

            val path = android.graphics.Path().apply {
                val startX = (stroke.points[0].x / 1000f) * mutableBitmap.width
                val startY = (stroke.points[0].y / 1000f) * mutableBitmap.height
                moveTo(startX, startY)
                for (pt in stroke.points) {
                    lineTo((pt.x / 1000f) * mutableBitmap.width, (pt.y / 1000f) * mutableBitmap.height)
                }
            }
            canvas.drawPath(path, paint)
        }
    }

    // Draw text overlays
    val textPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    textOverlays.forEach { overlay ->
        textPaint.textSize = 48f * scaleFactor * overlay.scale
        val col = overlay.color
        textPaint.setARGB(
            255,
            (col.red * 255).toInt(),
            (col.green * 255).toInt(),
            (col.blue * 255).toInt()
        )
        val posX = (mutableBitmap.width / 2f) + (overlay.offset.x * scaleFactor)
        val posY = (mutableBitmap.height / 2f) + (overlay.offset.y * scaleFactor)
        canvas.drawText(overlay.text, posX, posY, textPaint)
    }

    // Draw stickers / emojis
    val emojiPaint = Paint().apply {
        isAntiAlias = true
        textSize = 64f * scaleFactor
    }

    stickerOverlays.forEach { sticker ->
        emojiPaint.textSize = 64f * scaleFactor * sticker.scale
        val posX = (mutableBitmap.width / 2f) + (sticker.offset.x * scaleFactor)
        val posY = (mutableBitmap.height / 2f) + (sticker.offset.y * scaleFactor)
        canvas.drawText(sticker.emoji, posX, posY, emojiPaint)
    }

    return mutableBitmap
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
