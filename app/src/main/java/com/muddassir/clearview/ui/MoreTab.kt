package com.muddassir.clearview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * ## Why these four and not the whole security dashboard
 *
 * Protection is the old Block tab, kept whole and unchanged — this tab does not
 * re-implement it, it opens it. What sits beside it is the three things that used
 * to be hidden one level down inside the Quran tab's menu: a reader looking for
 * their todo list had to guess that it lived under the Quran, and guess again
 * that the ⋮ held it. They are ClearView features that have nothing to do with
 * reciting, so they belong on a tab of their own.
 *
 * ## Cards, not a dashboard
 *
 * A row each, one line apiece, the same card the rest of ClearView draws: icon,
 * title, one-line note, chevron. The three groups are the utilities, Appearance
 * (which repaints the whole app, so it belongs on a tab of its own rather than
 * buried in a sub-screen) and Protection. No counts, no charts, no shortcuts —
 * the tab's
 * job is to hand the reader to the feature, and a card that shows a summary of a
 * screen one tap away only invents a second place for it to be stale.
 */
@Composable
internal fun MoreTab(
    /** The app-wide scheme currently in force, shown on the Theme card. */
    themeMode: ThemeMode,
    /** Switches the app-wide scheme; every tab repaints at once. */
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenTodo: () -> Unit,
    onOpenPhoneLimit: () -> Unit,
    onOpenZikr: () -> Unit,
    onOpenProtection: () -> Unit
) {
    var showThemeDialog by remember { mutableStateOf(false) }
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
            SectionLabel(stringResource(R.string.more_section_appearance))
        }
        item {
            // One line, like every other card: the current scheme, with the
            // picker one tap away. This is the only appearance setting the app
            // has — the scheme is app-wide, so there is nothing per tab to set.
            MoreCard(
                icon = Icons.Filled.DarkMode,
                title = stringResource(R.string.theme_card_title),
                note = stringResource(
                    R.string.theme_note_format,
                    stringResource(themeMode.labelRes())
                ),
                onClick = { showThemeDialog = true }
            )
        }

        item {
            SectionLabel(stringResource(R.string.more_section_protection))
        }
        item {
            MoreCard(
                icon = Icons.Filled.Shield,
                title = stringResource(R.string.more_protection_title),
                note = stringResource(R.string.more_protection_note),
                onClick = onOpenProtection
            )
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.theme_dialog_title)) },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onThemeModeChange(mode)
                                    showThemeDialog = false
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
                                    showThemeDialog = false
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
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

/** The string for each scheme's label, shared by the card's note and the picker. */
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_option_system
    ThemeMode.LIGHT -> R.string.theme_option_light
    ThemeMode.DARK -> R.string.theme_option_dark
}

/** The small uppercase heading that separates the tab's two groups. */
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
 * One utility: icon, title, one-line note, chevron.
 *
 * The same card for all four, so the tab reads as one list rather than four
 * designs, and the tap target is the whole row.
 */
@Composable
private fun MoreCard(
    title: String,
    note: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    emoji: String? = null
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                emoji != null -> Text(
                    text = emoji,
                    fontSize = 24.sp,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
