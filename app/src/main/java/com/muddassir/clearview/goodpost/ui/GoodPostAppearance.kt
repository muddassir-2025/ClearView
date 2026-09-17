package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.AccentChoice
import com.muddassir.clearview.goodpost.data.BackdropChoice
import com.muddassir.clearview.goodpost.data.GlassChoice
import com.muddassir.clearview.goodpost.data.GoodPostTheme

/**
 * The appearance sheet: a mini, dreamy messenager's own settings screen.
 *
 * Three choices and nothing else, each shown as the thing itself — an accent is
 * a dot of that accent, a backdrop is a thumbnail of that gradient, a glass
 * strength is a panel at that opacity. Nothing is described in words that the
 * user then has to imagine; what they tap is what they get, before they tap it.
 *
 * Every change applies IMMEDIATELY, to the screen behind the sheet as well as to
 * the sheet. That is the point of a customization screen: the preview is the
 * app.
 */
@Composable
internal fun AppearanceDialog(
    theme: GoodPostTheme,
    onAccent: (AccentChoice) -> Unit,
    onBackdrop: (BackdropChoice) -> Unit,
    onGlass: (GlassChoice) -> Unit,
    onDismiss: () -> Unit
) {
    WaFullScreen(onDismiss = onDismiss, background = null) {
        WaTopBar(
            title = stringResource(R.string.goodpost_appearance),
            subtitle = stringResource(R.string.goodpost_appearance_note),
            navigation = {
                WaIconAction(
                    icon = Icons.Filled.Close,
                    description = stringResource(R.string.goodpost_cancel),
                    onClick = onDismiss
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            WaSectionLabel(stringResource(R.string.goodpost_appearance_accent))
            AccentRow(selected = theme.accent, onSelect = onAccent)

            WaSectionLabel(stringResource(R.string.goodpost_appearance_backdrop))
            BackdropRow(selected = theme.backdrop, onSelect = onBackdrop)

            WaSectionLabel(stringResource(R.string.goodpost_appearance_glass))
            GlassRow(selected = theme.glass, onSelect = onGlass)

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** One dot per accent, filled with that accent and ringed when it is in use. */
@Composable
private fun AccentRow(selected: AccentChoice, onSelect: (AccentChoice) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AccentChoice.entries.forEach { choice ->
            val active = choice == selected
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(choice.fill))
                    .border(
                        width = if (active) 3.dp else 0.dp,
                        color = if (active) Wa.Text else Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable { onSelect(choice) },
                contentAlignment = Alignment.Center
            ) {
                if (active) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        // Drawn on the fill, so its colour has to be the one
                        // thing that contrasts with every accent in the list:
                        // the canvas, not the text colour.
                        tint = Wa.Canvas,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** A tile per backdrop, showing the gradient itself under a sample bubble. */
@Composable
private fun BackdropRow(selected: BackdropChoice, onSelect: (BackdropChoice) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BackdropChoice.entries.forEach { choice ->
            val active = choice == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(choice.top), Color(choice.bottom))
                        )
                    )
                    .border(
                        width = if (active) 2.dp else 1.dp,
                        color = if (active) Wa.Accent else Wa.GlassBorder,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelect(choice) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // A bubble and a line of text, so the tile shows not just the
                // colours but whether anything is still readable on them.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Wa.BubbleIn)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(choice.top))
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = choice.label,
                    color = Wa.Text,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

/** A row per glass strength, each a real panel at that opacity. */
@Composable
private fun GlassRow(selected: GlassChoice, onSelect: (GlassChoice) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        GlassChoice.entries.forEach { choice ->
            val active = choice == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(choice.fill))
                    .border(
                        width = if (active) 2.dp else 1.dp,
                        color = if (active) Wa.Accent else Wa.GlassBorder,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelect(choice) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = choice.label,
                    color = Wa.Text,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                if (active) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Wa.Accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
