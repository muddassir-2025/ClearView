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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R

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
 * Four rows, one line each, the same card the rest of ClearView draws: icon,
 * title, one-line note, chevron. No counts, no charts, no shortcuts — the tab's
 * job is to hand the reader to the feature, and a card that shows a summary of a
 * screen one tap away only invents a second place for it to be stale.
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
            MoreCard(
                icon = Icons.Filled.Shield,
                title = stringResource(R.string.more_protection_title),
                note = stringResource(R.string.more_protection_note),
                onClick = onOpenProtection
            )
        }
    }
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
