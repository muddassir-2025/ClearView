package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.EditAspect
import com.muddassir.clearview.goodpost.data.EditFilter
import com.muddassir.clearview.goodpost.data.EditState
import com.muddassir.clearview.goodpost.data.EditableBitmap
import com.muddassir.clearview.goodpost.data.colorMatrixFor

/**
 * The editor, opened on a picture the user picked and closed on a finished file.
 *
 * Three trays, one per kind of change, because editing is a sequence of
 * decisions rather than a form: pick a shape, pick a mood, or nudge it by hand.
 * The preview applies exactly the same transform the export will (§ see
 * `colorMatrixFor`), so what is on screen at Done is what gets uploaded.
 *
 * Nothing here uploads, fetches, or knows about Good Post's API: it turns a
 * bitmap and a state into a file, and hands the file back.
 */
@Composable
internal fun ImageEditor(
    source: EditableBitmap,
    saving: Boolean,
    errorCode: String?,
    onCancel: () -> Unit,
    onDone: (EditState) -> Unit
) {
    var state by remember { mutableStateOf(EditState()) }
    var tray by remember { mutableStateOf(EditorTray.Filters) }

    WaFullScreen(onDismiss = onCancel, background = null) {
        WaTopBar(
            title = stringResource(R.string.goodpost_edit_image),
            navigation = {
                WaTextAction(
                    text = stringResource(R.string.goodpost_cancel),
                    enabled = !saving,
                    onClick = onCancel
                )
            },
            actions = {
                WaTextAction(
                    text = if (saving) stringResource(R.string.goodpost_working)
                    else stringResource(R.string.goodpost_done),
                    enabled = !saving,
                    onClick = { onDone(state) }
                )
            }
        )

        if (errorCode != null) {
            WaErrorNotice(errorCode)
            Spacer(Modifier.height(8.dp))
        }

        // The canvas takes whatever is left after the tray, so the picture is as
        // large as it can be without the controls scrolling it away.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            EditorPreview(source = source, state = state)
        }

        EditorTrayRow(selected = tray, onSelect = { tray = it })

        WaGlass(modifier = Modifier.fillMaxWidth(), color = Wa.Glass, blurBehind = Wa.GlassBlur) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                when (tray) {
                    EditorTray.Filters -> FilterRow(
                        selected = state.filter,
                        source = source,
                        onSelect = { state = state.copy(filter = it) }
                    )

                    EditorTray.Crop -> CropRow(
                        state = state,
                        onChange = { state = it }
                    )

                    EditorTray.Adjust -> AdjustTray(
                        state = state,
                        onChange = { state = it }
                    )
                }
            }
        }
    }
}

/** Which tray is open. */
private enum class EditorTray(val labelRes: Int) {
    Filters(R.string.goodpost_edit_filters),
    Crop(R.string.goodpost_edit_crop),
    Adjust(R.string.goodpost_edit_adjust)
}

/**
 * The live preview.
 *
 * The transform is applied by the RENDERER, not by re-encoding the bitmap: the
 * colour matrix is a `ColorFilter`, the rotation and flip are a graphics layer,
 * and the crop is a `ContentScale.Crop` inside a box of the right ratio. That
 * makes every slider and swatch cost one frame, which is what makes the editor
 * feel like a camera app rather than a batch job.
 */
@Composable
private fun EditorPreview(source: EditableBitmap, state: EditState) {
    val aspect = state.aspect.ratio ?: (source.width.toFloat() / source.height.toFloat())
    // A quarter turn swaps which way the frame is long, so the box the picture
    // is drawn in has to swap with it or a rotated portrait crop is letterboxed.
    val framed = if (state.turns % 2 == 1) 1f / aspect else aspect

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(framed.coerceIn(0.4f, 2.5f))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = source.bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.colorMatrix(
                androidx.compose.ui.graphics.ColorMatrix(colorMatrixFor(state).array)
            ),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    rotationZ = (state.turns % 4) * 90f,
                    scaleX = if (state.flipHorizontal) -1f else 1f
                )
        )
    }
}

/** The three trays, as a row of pills. */
@Composable
private fun EditorTrayRow(selected: EditorTray, onSelect: (EditorTray) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        EditorTray.entries.forEach { tray ->
            val icon = when (tray) {
                EditorTray.Filters -> Icons.Filled.AutoFixHigh
                EditorTray.Crop -> Icons.Filled.Crop
                EditorTray.Adjust -> Icons.Filled.Tune
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (tray == selected) Wa.Accent else Wa.Glass)
                    .border(
                        width = 1.dp,
                        color = if (tray == selected) Wa.Accent else Wa.GlassBorder,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .clickable { onSelect(tray) }
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (tray == selected) Wa.Canvas else Wa.Text,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(tray.labelRes),
                    color = if (tray == selected) Wa.Canvas else Wa.Text,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Filter swatches, each rendered as the picture itself under that filter.
 *
 * A row of coloured dots would be easier and useless: what a user is choosing
 * between is how their OWN photo looks, so the swatch is a thumbnail of that
 * photo with the matrix applied.
 */
@Composable
private fun FilterRow(
    selected: EditFilter,
    source: EditableBitmap,
    onSelect: (EditFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        EditFilter.entries.forEach { filter ->
            val active = filter == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = source.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix(
                            colorMatrixFor(EditState(filter = filter)).array
                        )
                    ),
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) Wa.Accent else Wa.GlassBorder,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelect(filter) }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = filter.label,
                    color = if (active) Wa.Accent else Wa.TextDim,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

/** Crop: the shapes, plus the two transforms that belong with them. */
@Composable
private fun CropRow(state: EditState, onChange: (EditState) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EditorAction(
            icon = Icons.Filled.Rotate90DegreesCw,
            label = stringResource(R.string.goodpost_edit_rotate),
            onClick = { onChange(state.copy(turns = (state.turns + 1) % 4)) }
        )
        EditorAction(
            icon = Icons.Filled.Flip,
            label = stringResource(R.string.goodpost_edit_flip),
            active = state.flipHorizontal,
            onClick = { onChange(state.copy(flipHorizontal = !state.flipHorizontal)) }
        )

        EditAspect.entries.forEach { aspect ->
            val active = aspect == state.aspect
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) Wa.Accent else Wa.Bar)
                    .clickable { onChange(state.copy(aspect = aspect)) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = aspect.label,
                    color = if (active) Wa.Canvas else Wa.Text,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/** Brightness, contrast and saturation, in the order they are usually wanted. */
@Composable
private fun AdjustTray(state: EditState, onChange: (EditState) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        AdjustSlider(
            label = stringResource(R.string.goodpost_edit_brightness),
            value = state.brightness,
            onChange = { onChange(state.copy(brightness = it)) }
        )
        AdjustSlider(
            label = stringResource(R.string.goodpost_edit_contrast),
            value = state.contrast,
            onChange = { onChange(state.copy(contrast = it)) }
        )
        AdjustSlider(
            label = stringResource(R.string.goodpost_edit_saturation),
            value = state.saturation,
            onChange = { onChange(state.copy(saturation = it)) }
        )

        Spacer(Modifier.height(4.dp))

        WaTextAction(
            text = stringResource(R.string.goodpost_edit_reset),
            enabled = !state.isPristine,
            onClick = { onChange(EditState()) }
        )
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Wa.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.width(84.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            // 0.5–1.5 is the range where an adjustment still looks like a
            // photograph; further out the sliders are a way to destroy a picture
            // rather than to improve one.
            valueRange = 0.5f..1.5f,
            colors = SliderDefaults.colors(
                thumbColor = Wa.Accent,
                activeTrackColor = Wa.Accent,
                inactiveTrackColor = Wa.Pressed
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${(value * 100).toInt()}",
            color = Wa.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.width(34.dp)
        )
    }
}

/** A labelled icon action inside a tray. */
@Composable
private fun EditorAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Wa.Accent else Wa.Bar)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) Wa.Canvas else Wa.Text,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (active) Wa.Canvas else Wa.Text,
            fontSize = 13.sp
        )
    }
}
