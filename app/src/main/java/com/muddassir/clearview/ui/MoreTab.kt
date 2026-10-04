package com.muddassir.clearview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.ui.theme.ThemeMode

/**
 * The More tab (§6–§8): ClearView's everyday utilities, and the way into
 * Protection.
 *
 * ## Why these three and not the whole security dashboard
 *
 * Protection is the old Block tab, kept whole and unchanged — this tab does not
 * re-implement it, it opens it. What sits beside it is the things that used to be
 * hidden one level down inside the Quran tab's menu: a reader looking for their
 * todo list had to guess that it lived under the Quran, and guess again that the
 * ⋮ held it. They are ClearView features that have nothing to do with reciting,
 * so they belong on a tab of their own.
 *
 * ## Appearance is a top-bar action now
 *
 * The Theme card that used to head this list is gone. The scheme is app-wide, so
 * it is not a place the reader visits — it is a switch they throw — and the
 * top-bar icon is the honest control for it. Settings sits beside it.
 *
 * ## Cards, not a dashboard
 *
 * A row each, one line apiece: a tinted glyph, a title, a one-line note, a
 * chevron. No counts, no charts, no shortcuts — the tab's job is to hand the
 * reader to the feature, and a card that shows a summary of a screen one tap away
 * only invents a second place for it to be stale.
 */
@Composable
internal fun MoreTab(
    onOpenTodo: () -> Unit,
    onOpenPhoneLimit: () -> Unit,
    onOpenZikr: () -> Unit,
    onOpenProtection: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionLabel(stringResource(R.string.more_section_tools))
        }
        item {
            MoreCard(
                icon = Icons.Outlined.CheckCircle,
                title = stringResource(R.string.todo_card_title),
                note = stringResource(R.string.todo_card_note),
                onClick = onOpenTodo
            )
        }
        item {
            MoreCard(
                icon = Icons.Filled.Timer,
                title = stringResource(R.string.phone_limit_menu_title),
                note = stringResource(R.string.phone_limit_menu_note),
                onClick = onOpenPhoneLimit
            )
        }
        item {
            // The Dhikr counter's glyph is an emoji rather than a Material icon
            // (it has no suitable one), which is how the settings sheet drew it
            // too — kept so the feature looks the same as it always did.
            MoreCard(
                emoji = "📿",
                title = stringResource(R.string.dhikr_counter_card_title),
                note = stringResource(R.string.dhikr_counter_card_note),
                onClick = onOpenZikr
            )
        }

        item {
            SectionLabel(stringResource(R.string.more_section_protection))
        }
        item {
            // Highlighted, because it is the one card here that leads to a
            // locked surface: the tab's whole reason for existing per §8, and the
            // only row a reader has to be able to find without searching.
            MoreCard(
                icon = Icons.Filled.Shield,
                title = stringResource(R.string.more_protection_title),
                note = stringResource(R.string.more_protection_note),
                onClick = onOpenProtection,
                highlight = true
            )
        }
    }
}

/**
 * The app-wide scheme picker, opened from the top bar.
 *
 * Lifted out of the tab so the top-bar icon can open it: the setting lives in
 * the bar now, and a dialog that belonged to a card that no longer exists would
 * be a dialog with no way in.
 */
@Composable
internal fun ThemePickerDialog(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_dialog_title)) },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onThemeModeChange(mode)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The whole row selects, not just the dot — a radio
                        // button on its own is a 20dp tap target.
                        RadioButton(
                            selected = mode == themeMode,
                            onClick = {
                                onThemeModeChange(mode)
                                onDismiss()
                            }
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(mode.labelRes()),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

/** The string for each scheme's label, shared by the picker. */
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_option_system
    ThemeMode.LIGHT -> R.string.theme_option_light
    ThemeMode.DARK -> R.string.theme_option_dark
}

/** The small uppercase heading that separates the tab's groups. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
    )
}

/**
 * One utility: a tinted glyph, a title, a one-line note, a chevron.
 *
 * The glyph sits in a rounded, tinted square rather than loose on the card,
 * which is what gives the row its shape and stops a tall glyph and a short one
 * from setting different heights. [highlight] is used by exactly one card —
 * Protection — and only ever changes the colours, never the layout, so the list
 * still reads as one family.
 */
@Composable
private fun MoreCard(
    title: String,
    note: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    emoji: String? = null,
    highlight: Boolean = false
) {
    val container = if (highlight) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val ink = if (highlight) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlight) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (highlight) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    icon != null -> Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (highlight) ink else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    emoji != null -> Text(text = emoji, fontSize = 22.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ink
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlight) ink.copy(alpha = 0.78f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = if (highlight) ink else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
