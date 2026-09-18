package com.muddassir.clearview.goodpost.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertEmoticon
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextContextMenuDropdownProvider
import androidx.compose.ui.platform.LocalTextContextMenuToolbarProvider
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostAttachment
import com.muddassir.clearview.goodpost.data.GoodPostFormat
import com.muddassir.clearview.goodpost.data.GoodPostPost
import com.muddassir.clearview.goodpost.data.applyGoodPostFormat
import com.muddassir.clearview.goodpost.data.readGoodPostAttachment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private const val COMPOSER_MAX_ATTACHMENTS = 4

/**
 * WhatsApp-Style Composer Input Bar
 */
@Composable
internal fun WhatsAppComposer(
    state: GoodPostUiState,
    channelId: String,
    viewModel: GoodPostViewModel,
    replyingTo: GoodPostPost? = null,
    onCancelReply: () -> Unit = {},
    onOpenMediaEditor: (List<Uri>) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editing = state.isEditingIn(channelId)

    val body = state.composerBody
    var selection by remember { mutableStateOf(TextRange(body.length)) }

    val selectionStart = selection.start.coerceIn(0, body.length)
    val selectionEnd = selection.end.coerceIn(0, body.length)
    val selectedText = body.substring(
        minOf(selectionStart, selectionEnd),
        maxOf(selectionStart, selectionEnd)
    )

    val fieldValue = TextFieldValue(
        annotatedString = parseGoodPostText(body),
        selection = TextRange(selectionStart, selectionEnd)
    )

    val hasSelection = selectionStart != selectionEnd
    var clipboardActions by remember { mutableStateOf<List<GoodPostContextMenuAction>>(emptyList()) }
    val selectionMenu = remember {
        GoodPostTextContextMenuProvider { offered -> clipboardActions = offered }
    }

    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }

    // Voice recording state
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordDurationSeconds by remember { mutableIntStateOf(0) }
    var isLockedRecording by remember { mutableStateOf(false) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioOutputFile by remember { mutableStateOf<File?>(null) }

    // Photo picker
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(COMPOSER_MAX_ATTACHMENTS)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onOpenMediaEditor(uris)
        }
    }

    // Camera capture
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraTempUri != null) {
            onOpenMediaEditor(listOf(cameraTempUri!!))
        }
    }

    // Document picker
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            readGoodPostAttachment(context, uri)?.let { viewModel.attachMedia(it) }
        }
    }

    // Audio recording timer loop
    LaunchedEffect(isRecordingAudio) {
        if (isRecordingAudio) {
            recordDurationSeconds = 0
            while (isRecordingAudio) {
                delay(1000L)
                recordDurationSeconds++
            }
        }
    }

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val file = File(context.cacheDir, "voice_note_${System.currentTimeMillis()}.m4a")
            audioOutputFile = file
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(64000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            isRecordingAudio = true
            isLockedRecording = false
            dragOffsetX = 0f
        } catch (e: Exception) {
            isRecordingAudio = false
            mediaRecorder = null
        }
    }

    fun stopRecording(send: Boolean) {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Ignore stop errors
        } finally {
            mediaRecorder = null
            isRecordingAudio = false
            isLockedRecording = false
            dragOffsetX = 0f

            if (send && audioOutputFile != null && audioOutputFile!!.exists() && audioOutputFile!!.length() > 0) {
                val uri = Uri.fromFile(audioOutputFile!!)
                readGoodPostAttachment(context, uri)?.let { viewModel.attachMedia(it) }
            } else {
                audioOutputFile?.delete()
            }
            audioOutputFile = null
        }
    }

    fun applyFormat(format: GoodPostFormat) {
        val edit = applyGoodPostFormat(body, selectionStart, selectionEnd, format)
        viewModel.onComposerBodyChange(edit.text)
        selection = TextRange(edit.selectionStart, edit.selectionEnd)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
    ) {
        // Replying to message preview banner
        if (replyingTo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1F2C34))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(36.dp)
                        .background(Wa.Accent, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.goodpost_reply),
                        color = Wa.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = replyingTo.body?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.goodpost_posted_something),
                        color = Wa.TextDim,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onCancelReply, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.goodpost_cancel_reply),
                        tint = Wa.TextDim,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Editing banner
        if (editing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Wa.Bar)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    tint = Wa.StampRecent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.goodpost_edit_post),
                    color = Wa.StampRecent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.goodpost_cancel),
                    tint = Wa.TextDim,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = viewModel::cancelCompose)
                )
            }
        }

        // Attachment thumbnail strip
        if (state.composerAttachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Wa.Bar)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.composerAttachments, key = { it.uri }) { attachment ->
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Wa.Pressed),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (attachment.kind == "video") Icons.Filled.PlayArrow else Icons.Filled.Image,
                            contentDescription = null,
                            tint = Wa.TextDim,
                            modifier = Modifier.size(22.dp)
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Wa.Canvas)
                                .clickable { viewModel.removeMedia(attachment.uri) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = null,
                                tint = Wa.TextDim,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        // Formatting controls when text selected
        if (hasSelection) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Wa.Canvas)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GoodPostFormatMenu(
                    isActive = { format -> format.wraps(selectedText) },
                    onToggle = ::applyFormat
                )
                GoodPostMenuDivider()
                GoodPostClipboardBar(clipboardActions)
            }
        }

        // Attachment Menu Popup
        AnimatedVisibility(
            visible = showAttachmentMenu,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
        ) {
            WhatsAppAttachmentGrid(
                onPickGallery = {
                    showAttachmentMenu = false
                    galleryPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                onPickCamera = {
                    showAttachmentMenu = false
                    try {
                        val file = File(context.cacheDir, "camera_snap_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        cameraTempUri = uri
                        cameraLauncher.launch(uri)
                    } catch (e: Exception) {
                        // Fallback to gallery
                        galleryPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                },
                onPickDocument = {
                    showAttachmentMenu = false
                    documentPicker.launch("*/*")
                },
                onDismiss = { showAttachmentMenu = false }
            )
        }

        // Voice Recording Bar or Standard Input Row
        if (isRecordingAudio) {
            // WhatsApp Voice Recording Live Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Wa.Bar)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pulsing Red Dot
                    val infiniteTransition = rememberInfiniteTransition(label = "mic-pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse-alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Wa.Danger.copy(alpha = pulseAlpha))
                    )

                    Spacer(Modifier.width(10.dp))

                    // Timer (0:05)
                    val minutes = recordDurationSeconds / 60
                    val seconds = recordDurationSeconds % 60
                    Text(
                        text = String.format("%02d:%02d", minutes, seconds),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(Modifier.width(16.dp))

                    // Slide to cancel
                    if (!isLockedRecording) {
                        Text(
                            text = "‹  " + stringResource(R.string.goodpost_voice_slide_cancel),
                            color = Wa.TextDim,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .offset { IntOffset(dragOffsetX.roundToInt(), 0) }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetX = (dragOffsetX + dragAmount.x).coerceAtMost(0f)
                                            if (dragOffsetX < -180f) {
                                                stopRecording(send = false)
                                            }
                                        },
                                        onDragEnd = {
                                            if (dragOffsetX >= -180f) dragOffsetX = 0f
                                        }
                                    )
                                }
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.goodpost_voice_recording),
                            color = Wa.TextDim,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Cancel button
                    IconButton(
                        onClick = { stopRecording(send = false) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.goodpost_cancel),
                            tint = Wa.Danger,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                // Send voice button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Wa.Accent)
                        .clickable { stopRecording(send = true) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.goodpost_send),
                        tint = Wa.OnAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        } else {
            // Standard WhatsApp Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Input Pill container
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Wa.Bar)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Emoji button
                    Icon(
                        Icons.Filled.InsertEmoticon,
                        contentDescription = "Emoji",
                        tint = Wa.TextDim,
                        modifier = Modifier
                            .size(26.dp)
                            .clickable { showEmojiSheet = !showEmojiSheet }
                    )

                    Spacer(Modifier.width(8.dp))

                    CompositionLocalProvider(
                        LocalTextContextMenuToolbarProvider provides selectionMenu,
                        LocalTextContextMenuDropdownProvider provides selectionMenu
                    ) {
                        BasicTextField(
                            value = fieldValue,
                            onValueChange = { updated ->
                                selection = updated.selection
                                viewModel.onComposerBodyChange(updated.text)
                            },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(color = Wa.Text, fontSize = 16.sp),
                            cursorBrush = SolidColor(Wa.StampRecent),
                            maxLines = 5,
                            decorationBox = { inner ->
                                if (body.isEmpty()) {
                                    Text(
                                        text = if (editing) stringResource(R.string.goodpost_edit_post)
                                        else stringResource(R.string.goodpost_write_update),
                                        color = Wa.TextDim,
                                        fontSize = 16.sp
                                    )
                                }
                                inner()
                            }
                        )
                    }

                    if (!editing && state.composerMediaAvailable) {
                        Spacer(Modifier.width(6.dp))

                        // Attachment paperclip
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = stringResource(R.string.goodpost_add_media),
                            tint = Wa.TextDim,
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { showAttachmentMenu = !showAttachmentMenu }
                        )

                        Spacer(Modifier.width(8.dp))

                        // Camera shortcut
                        Icon(
                            Icons.Filled.CameraAlt,
                            contentDescription = stringResource(R.string.goodpost_attach_camera),
                            tint = Wa.TextDim,
                            modifier = Modifier
                                .size(24.dp)
                                .clickable {
                                    galleryPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                }
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                val hasContent = body.isNotBlank() || state.composerAttachments.isNotEmpty()
                val canSubmit = hasContent && !state.composerBusy &&
                    (editing || state.composerAttachments.all { it.mediaId != null })

                // Right circular FAB: Mic when empty, Send arrow when has content
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (hasContent) Wa.Accent else Wa.Accent)
                        .clickable {
                            if (hasContent) {
                                if (canSubmit) viewModel.publish()
                            } else {
                                startRecording()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.composerBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Wa.OnAccent
                        )
                    } else if (editing) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.goodpost_save),
                            tint = Wa.OnAccent,
                            modifier = Modifier.size(24.dp)
                        )
                    } else if (hasContent) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.goodpost_publish),
                            tint = Wa.OnAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.goodpost_voice_record),
                            tint = Wa.OnAccent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * WhatsApp Attachment Menu (Document, Camera, Gallery, Audio, Location, Poll)
 */
@Composable
private fun WhatsAppAttachmentGrid(
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
    onPickDocument: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1F2C34))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AttachmentMenuItem(
                    icon = Icons.Filled.Description,
                    label = stringResource(R.string.goodpost_attach_document),
                    bgColor = Color(0xFF7F66FF),
                    onClick = onPickDocument
                )
                AttachmentMenuItem(
                    icon = Icons.Filled.CameraAlt,
                    label = stringResource(R.string.goodpost_attach_camera),
                    bgColor = Color(0xFFD3396D),
                    onClick = onPickCamera
                )
                AttachmentMenuItem(
                    icon = Icons.Filled.PhotoLibrary,
                    label = stringResource(R.string.goodpost_attach_gallery),
                    bgColor = Color(0xFFAC44CF),
                    onClick = onPickGallery
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AttachmentMenuItem(
                    icon = Icons.Filled.Audiotrack,
                    label = stringResource(R.string.goodpost_attach_audio),
                    bgColor = Color(0xFFE06D24),
                    onClick = onPickDocument
                )
                AttachmentMenuItem(
                    icon = Icons.Filled.PinDrop,
                    label = stringResource(R.string.goodpost_attach_location),
                    bgColor = Color(0xFF00A884),
                    onClick = onDismiss
                )
                AttachmentMenuItem(
                    icon = Icons.Filled.Poll,
                    label = stringResource(R.string.goodpost_attach_poll),
                    bgColor = Color(0xFF02A698),
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun AttachmentMenuItem(
    icon: ImageVector,
    label: String,
    bgColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(text = label, color = Wa.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
