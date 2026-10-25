package com.muddassir.clearview.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.R
import com.muddassir.clearview.browser.BrowserSettings

/**
 * The browser's Protection Settings.
 *
 * A page of its own rather than a sheet: the controls are the same protection
 * the rest of ClearView enforces, and a list this size is easier to read with
 * room to scroll. The two shared controls (Strict Mode, Block Shorts) write to
 * the same BlockRepository the Protection dashboard uses, so the browser can
 * never disagree with the phone.
 */
@Composable
fun BrowserProtectionSettings(
    settings: BrowserSettings,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onOpenAppProtection: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    // Pick up a toggle changed in the Protection dashboard while the browser
    // was in the background.
    LaunchedEffect(Unit) { settings.refreshFromApp() }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Opaque: Settings is a page, not a sheet. Without this the WebView
            // showed through the gutters and around the cards, which read as a
            // rendering bug.
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.browser_back)
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.browser_settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.browser_settings_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsSection(title = stringResource(R.string.browser_settings_filtering)) {
                    ToggleRow(
                        icon = Icons.Outlined.Warning,
                        title = stringResource(R.string.browser_toggle_strict_title),
                        summary = stringResource(R.string.browser_toggle_strict_summary),
                        checked = settings.strictMode,
                        onCheckedChange = {
                            settings.applyStrictMode(it)
                            onChanged()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    ToggleRow(
                        icon = Icons.Filled.PlayArrow,
                        title = stringResource(R.string.browser_toggle_shorts_title),
                        summary = stringResource(R.string.browser_toggle_shorts_summary),
                        checked = settings.blockShorts,
                        onCheckedChange = {
                            settings.applyBlockShorts(it)
                            onChanged()
                        }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.browser_settings_feeds)) {
                    ToggleRow(
                        icon = Icons.Filled.CameraAlt,
                        title = stringResource(R.string.browser_toggle_reels_title),
                        summary = stringResource(R.string.browser_toggle_reels_summary),
                        checked = settings.hideInstagramReels,
                        onCheckedChange = {
                            settings.applyHideInstagramReels(it)
                            onChanged()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    ToggleRow(
                        icon = Icons.Outlined.Search,
                        title = stringResource(R.string.browser_toggle_insta_search_title),
                        summary = stringResource(R.string.browser_toggle_insta_search_summary),
                        checked = settings.hideInstagramSearch,
                        onCheckedChange = {
                            settings.applyHideInstagramSearch(it)
                            onChanged()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    ToggleRow(
                        icon = Icons.Outlined.Home,
                        title = stringResource(R.string.browser_toggle_insta_home_title),
                        summary = stringResource(R.string.browser_toggle_insta_home_summary),
                        checked = settings.hideInstagramHome,
                        onCheckedChange = {
                            settings.applyHideInstagramHome(it)
                            onChanged()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    ToggleRow(
                        icon = Icons.Filled.CameraAlt,
                        title = stringResource(R.string.browser_toggle_insta_suggested_title),
                        summary = stringResource(R.string.browser_toggle_insta_suggested_summary),
                        checked = settings.hideInstagramSuggestions,
                        onCheckedChange = {
                            settings.applyHideInstagramSuggestions(it)
                            onChanged()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    ToggleRow(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.browser_toggle_google_tabs_title),
                        summary = stringResource(R.string.browser_toggle_google_tabs_summary),
                        checked = settings.hideGoogleCategories,
                        onCheckedChange = {
                            settings.applyHideGoogleCategories(it)
                            onChanged()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    ToggleRow(
                        icon = Icons.Filled.RemoveCircleOutline,
                        title = stringResource(R.string.browser_toggle_remove_feed_title),
                        summary = stringResource(R.string.browser_toggle_remove_feed_summary),
                        checked = settings.removeBlockedFromFeed,
                        onCheckedChange = {
                            settings.applyRemoveBlockedFromFeed(it)
                            onChanged()
                        }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.browser_settings_youtube)) {
                    ToggleRow(
                        icon = Icons.Filled.VisibilityOff,
                        title = stringResource(R.string.browser_toggle_ads_title),
                        summary = stringResource(R.string.browser_toggle_ads_summary),
                        checked = settings.blockYouTubeAds,
                        onCheckedChange = {
                            settings.applyBlockYouTubeAds(it)
                            onChanged()
                        }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.browser_settings_reading)) {
                    ToggleRow(
                        icon = Icons.Filled.DarkMode,
                        title = stringResource(R.string.browser_toggle_dark_pages_title),
                        summary = stringResource(R.string.browser_toggle_dark_pages_summary),
                        checked = settings.darkPages,
                        onCheckedChange = {
                            settings.applyDarkPages(it)
                            onChanged()
                        }
                    )
                }
            }

            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.browser_settings_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onOpenAppProtection != null) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onOpenAppProtection,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.browser_settings_open_full))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
