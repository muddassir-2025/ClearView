package com.muddassir.clearview.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import com.muddassir.clearview.R
import com.muddassir.clearview.viewmodel.MainViewModel

/**
 * Block tab — the password-protected security dashboard.
 *
 * Cards (top to bottom):
 *  1. Protection toggle — reflects the Accessibility Service state, turns
 *     green when active, and opens the system settings to enable/disable it.
 *  2. Strict Mode — adds the curated risky-but-innocent discovery terms
 *     (bikini, lingerie, cleavage, ...) on top of the always-on adult terms.
 *  3. Block Shorts — blocks YouTube Shorts in Chrome and the YouTube app
 *     (no need to add "shorts" as a keyword).
 *  4. YouTube Chrome Test — Stage-1 experiment: Shorts + long-video blocking
 *     in Chrome (pause + protection overlay).
 *  5. YouTube Chrome Test Keywords — separate test-only keyword list.
 *  6. Blocked Items — dotted editable list card: add / view keywords and
 *     websites.
 *  7. DNS protection — network-level filtering.
 *  8. Advanced — device admin, uninstall protection, app lock.
 *
 * Every card carries an info (i) icon that expands the FULL context of what
 * that feature does — tap it on any card to see everything it covers. That is
 * what lets each on-card summary stay ONE short line.
 *
 * Locking: the filtering features (2-4) are enforced BY the Protection
 * service, so while it is off their switches are DISABLED and a single hint
 * above the group says why. DNS, the device-admin features and the Block-tab
 * password are independent of it and stay usable.
 */
@Composable
fun BlockTab(
    viewModel: MainViewModel,
    deviceAdminLauncher: ActivityResultLauncher<Intent>
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Every feature below is enforced BY the Protection service, so while
        // it is off their switches are locked (one shared hint says why,
        // instead of repeating it on each card).
        val protectionOn = viewModel.isAccessibilityEnabled

        // What this screen protects, and where — stated once, up front, so the
        // page explains itself before the user reads a single switch.
        item { BlockHeader() }

        item { ProtectionCard(viewModel, context) }

        // ── Content Protection ─────────────────────────────────────
        item { SectionHeading(R.string.block_content_protection) }
        if (!protectionOn) {
            item { LockedGroupHint() }
        }
        item { StrictModeCard(viewModel, context, locked = !protectionOn) }
        item { BlockShortsCard(viewModel, locked = !protectionOn) }
        item { BrainRotProtectionCard(viewModel, locked = !protectionOn) }
        item { BrainRotKeywordsCard(viewModel) }
        item { BrainRotChannelsCard(viewModel) }
        item { GlobalRulesCard(viewModel) }
        item { BlockedItemsCard(viewModel) }

        // ── Activity ───────────────────────────────────────────────
        item { SectionHeading(R.string.block_activity_title) }
        item { ActivityCard(viewModel) }

        // ── Privacy ────────────────────────────────────────────────
        item { PrivacyCard() }

        item { DnsCard(viewModel, context) }

        item {
            Text(
                text = "ADVANCED",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
            )
        }
        item { DeviceAdminCard(viewModel, context, deviceAdminLauncher) }
        item { UninstallProtectionCard(viewModel, context) }
        item { AppLockCard(viewModel) }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

// ── Shared: page header + section headings ───────────────────────

/**
 * The page's opening line: what this tab is, and the scope of protection.
 * The scope is deliberately plain — "websites in Chrome and Google Search" —
 * because a user who cannot tell WHAT is protected cannot trust any of it.
 */
@Composable
private fun BlockHeader() {
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(
            text = stringResource(R.string.block_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.block_scope_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** A quiet all-caps heading that groups the cards below it. */
@Composable
private fun SectionHeading(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
    )
}

// ── Shared: info icon + full-context details expander ────────────

/**
 * Small info (i) icon button on a card header. Tapping it toggles the card's
 * expanded FULL-CONTEXT details section (FeatureDetailBlock) — everything that
 * feature covers, since the one-line summaries can't hold it all.
 */
@Composable
private fun InfoToggleButton(expanded: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (expanded) Icons.Filled.Info else Icons.Outlined.Info,
            contentDescription = if (expanded) "Hide details" else "Show full context",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** The expanded body shown under a card when its info icon is tapped. */
@Composable
private fun FeatureDetailBlock(bullets: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        bullets.forEach { bullet ->
            Text(
                text = "• $bullet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── 1. Protection toggle ──────────────────────────────────────────

@Composable
private fun ProtectionCard(viewModel: MainViewModel, context: Context) {
    val isEnabled = viewModel.isAccessibilityEnabled
    val activeGreen = Color(0xFF2E7D32)
    // Prominent disclosure + consent for the Accessibility Service (Google Play
    // policy for non-accessibility-tool apps): shown whenever the user attempts
    // to ENABLE protection. The service is described, its on-device nature is
    // stated, and consent is explicit — system Settings is only opened after
    // the user taps Continue. Disabling stays one tap away as before.
    var showDisclosure by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) activeGreen else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = if (isEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Protection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isEnabled) "Active — monitoring Chrome & Google" else "Off — tap to enable",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isEnabled) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { _ ->
                        if (!isEnabled) {
                            // Enabling: require explicit consent via the
                            // prominent-disclosure dialog before the user is
                            // sent to system Settings to turn the service on.
                            showDisclosure = true
                        } else {
                            // Disabling: no disclosure needed — straight to
                            // settings. The service can't be toggled
                            // programmatically.
                            viewModel.openAccessibilitySettings(context)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF1B5E20),
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.block_protection_summary),
                style = MaterialTheme.typography.bodySmall,
                color = if (isEnabled) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(visible = showDetails) {
                FeatureDetailBlock(
                    bullets = listOf(
                        "Always-on adult filter — explicit websites, searches and video content are blocked in Chrome and the Google app.",
                        "Incognito mode — detected and closed automatically, so blocked content can never be reached privately.",
                        "YouTube Shorts — short-form videos are paused and covered with a protection overlay (toggle below).",
                        "Long YouTube videos — the real title and description are checked on the watch page; blocked videos are paused exactly once and covered with a dark overlay and a \"Go to YouTube Home\" button.",
                        "Pattern blocking — innocent words like women, girl, hot or beach only block when combined with adult terms (e.g. \"women bikini\"), so everyday browsing is never blocked.",
                        "Custom keywords & websites — everything under Blocked Items is enforced in every monitored app.",
                        "100% on-device — screen text is processed instantly on your phone and never leaves it."
                    )
                )
            }
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("Enable ClearView protection?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ClearView uses the Accessibility service to read the text currently shown on your screen inside Chrome and the Google app — only to block adult content, your blocked keywords and websites, and incognito mode.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• Screen text is processed instantly on your device\n" +
                            "• It is never stored, logged, or sent anywhere\n" +
                            "• No personal data is collected\n" +
                            "• You can turn it off anytime in Settings → Accessibility",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisclosure = false
                        viewModel.openAccessibilitySettings(context)
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) {
                    Text("Not now")
                }
            }
        )
    }
}

// ── Shared feature row (Strict Mode / Shorts / YouTube test) ──────
//
// These three features do NOTHING on their own: they are enforced by the
// always-on Protection service (the Accessibility service that reads the
// screen). So they share ONE compact row — icon, title, a single short line,
// the switch and the (i) context expander — and when Protection is OFF their
// switch is DISABLED. A switch that looks on while nothing is watching would
// claim protection that isn't running.
//
// The full context still lives behind the (i), which is what lets every
// summary stay one short line.

/** Shown once above the locked group (kept out of every card: less text). */
@Composable
private fun LockedGroupHint() {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.block_locked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    locked: Boolean,
    details: List<String>,
    onToggle: () -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (checked)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
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
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = { if (!locked) onToggle() },
                    enabled = !locked
                )
                InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
            }
            AnimatedVisibility(visible = showDetails) {
                FeatureDetailBlock(bullets = details)
            }
        }
    }
}

// ── 2. Strict Mode ────────────────────────────────────────────────

@Composable
private fun StrictModeCard(viewModel: MainViewModel, context: Context, locked: Boolean) {
    FeatureCard(
        icon = Icons.Outlined.Warning,
        title = "Strict Mode",
        summary = "Also blocks discovery words like bikini, lingerie or cleavage.",
        checked = viewModel.isStrictMode,
        locked = locked,
        details = listOf(
            "Adds a curated list of risky-but-innocent discovery terms (bikini, lingerie, cleavage, beach, hot, ...) on top of the always-on adult filter.",
            "Gender and family words — women, female, girl, transgender, mom, wife, sister, daughter and more — are never blocked alone; they only block when combined with an adult term.",
            "Pattern matching applies everywhere: Chrome (every search tab), the Google app, YouTube, and your blocked list.",
            "When Strict Mode is off, only the always-on adult terms block — the discovery terms are ignored."
        ),
        onToggle = { viewModel.toggleStrictMode(context) }
    )
}

// ── 3. Block Shorts ───────────────────────────────────────────────

@Composable
private fun BlockShortsCard(viewModel: MainViewModel, locked: Boolean) {
    FeatureCard(
        icon = Icons.Outlined.PlayCircle,
        title = "Block Shorts",
        summary = "Pauses YouTube Shorts in Chrome and the YouTube app.",
        checked = viewModel.blockShorts,
        locked = locked,
        details = listOf(
            "Blocks YouTube Shorts in Chrome and the YouTube app — no need to add \"shorts\" as a keyword.",
            "Blocked Shorts are paused and covered with a protection overlay, so taps can never reveal the controls or resume the video.",
            "Vertical swipes still work, so you can move between Shorts normally.",
            "Works together with the always-on adult filter and your custom keywords."
        ),
        onToggle = { viewModel.toggleBlockShorts() }
    )
}

// ── 3b. Brain Rot Protection (was "YouTube Chrome Test") ─────────

@Composable
private fun BrainRotProtectionCard(viewModel: MainViewModel, locked: Boolean) {
    FeatureCard(
        icon = Icons.Outlined.Psychology,
        title = stringResource(R.string.block_brainrot_title),
        summary = stringResource(R.string.block_brainrot_summary),
        checked = viewModel.youTubeChromeTest,
        locked = locked,
        details = listOf(
            "Blocks content using your keywords and blocked channels — on YouTube Shorts and long videos in Chrome.",
            "Shorts are detected on-screen, matched, paused once and covered with a protection overlay; swipes between Shorts still work.",
            "Long videos — the real title AND description are extracted from the watch page and matched against your keywords (browser strings like \"Share\", \"Subscribe\" or \"New tab\" are ignored).",
            "A blocked long video is paused exactly once, then protected by a dark overlay with a \"Go to YouTube Home\" button.",
            "Detection interrupts the content rather than warning about it — a blocked video cannot simply be dismissed and continued.",
            "Allowed videos keep playing untouched — no overlay, no pause."
        ),
        onToggle = { viewModel.toggleYouTubeChromeTest() }
    )
}

// ── 3c. Brain Rot: blocked keywords ──────────────────────────────

@Composable
private fun BrainRotKeywordsCard(viewModel: MainViewModel) {
    val context = LocalContext.current
    var showDetails by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.block_stat_keywords),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${viewModel.youtubeTestKeywords.size} keywords",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
            }
            AnimatedVisibility(visible = showDetails) {
                FeatureDetailBlock(
                    bullets = listOf(
                        "Words or phrases that block content when detected in a title or page text.",
                        "Matching Shorts are paused and covered instead of showing the normal ClearView block screen.",
                        "The same list is matched against long-video titles and descriptions on watch pages.",
                        "These are your own rules and are separate from the always-on adult filter."
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = viewModel.newYoutubeTestKeywordText,
                onValueChange = { viewModel.updateNewYoutubeTestKeyword(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Add a keyword to block") },
                placeholder = { Text("e.g., viral, aesthetic, sigma") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.addYoutubeTestKeyword() }),
                trailingIcon = {
                    IconButton(onClick = { viewModel.addYoutubeTestKeyword() }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add keyword")
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (viewModel.youtubeTestKeywords.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (keyword in viewModel.youtubeTestKeywords) {
                        BlockedChip(
                            label = keyword,
                            onDelete = { viewModel.removeYoutubeTestKeyword(keyword) }
                        )
                    }
                }
                // Contributing a keyword is a separate, deliberate action from
                // adding it locally. The copy says what actually happens — it is
                // reviewed, not applied — because a button labelled "block this
                // for everyone" that silently did nothing visible would be a lie
                // in the other direction.
                if (viewModel.globalRulesAvailable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.block_global_suggest_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            val keyword = viewModel.newYoutubeTestKeywordText.trim()
                            if (keyword.isNotEmpty()) {
                                viewModel.suggestGlobalKeyword(keyword) { ok ->
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            if (ok) R.string.block_global_suggest_queued
                                            else R.string.block_global_suggest_failed
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        enabled = viewModel.newYoutubeTestKeywordText.trim().isNotEmpty()
                    ) {
                        Text(stringResource(R.string.block_global_suggest), fontSize = 12.sp)
                    }
                }
            } else {
                Text(
                    text = "No keywords yet. Add one above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── 3d. Brain Rot: blocked channels ──────────────────────────────

@Composable
private fun BrainRotChannelsCard(viewModel: MainViewModel) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val channels = viewModel.filteredBrainRotChannels()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.block_stat_channels),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${viewModel.brainRotChannels.size} channels",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledIconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = if (expanded) "Close channel manager" else "Manage channels"
                    )
                }
                InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
            }

            AnimatedVisibility(visible = showDetails) {
                FeatureDetailBlock(
                    bullets = listOf(
                        "Block specific YouTube channels. Every video from a blocked channel is blocked, regardless of its title.",
                        "Channels are stored by their @handle, so a rename of the channel name never loses the block.",
                        "Add a channel manually, or block one straight from YouTube's \"Don't recommend this\" menu.",
                        "Your blocks are private and belong only to you."
                    )
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = viewModel.newChannelHandleText,
                        onValueChange = { viewModel.updateNewChannelHandle(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.block_channel_add_label)) },
                        placeholder = { Text(stringResource(R.string.block_channel_add_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            if (!viewModel.addBrainRotChannel()) {
                                Toast.makeText(context, context.getString(R.string.block_channel_invalid), Toast.LENGTH_SHORT).show()
                            }
                        }),
                        trailingIcon = {
                            IconButton(onClick = {
                                if (!viewModel.addBrainRotChannel()) {
                                    Toast.makeText(context, context.getString(R.string.block_channel_invalid), Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add channel")
                            }
                        }
                    )

                    if (viewModel.brainRotChannels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = viewModel.channelSearchText,
                            onValueChange = { viewModel.updateChannelSearch(it) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.block_channel_search_hint)) },
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (viewModel.channelSearchText.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.updateChannelSearch("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (channels.isEmpty()) {
                            Text(
                                text = "No channel matches that search.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            for (channel in channels) {
                                ChannelRow(
                                    channel = channel,
                                    onDelete = { viewModel.removeBrainRotChannel(channel.handle) },
                                    onSuggestGlobal = if (viewModel.globalRulesAvailable) {
                                        {
                                            viewModel.suggestGlobalChannel(channel.handle, channel.name) { ok ->
                                                Toast.makeText(
                                                    context,
                                                    context.getString(
                                                        if (ok) R.string.block_global_suggest_queued
                                                        else R.string.block_global_suggest_failed
                                                    ),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    } else null
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.block_channels_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.block_channel_handle_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/**
 * One blocked channel: handle, optional name, the "My block" badge, and an
 * optional action to suggest it to the global repository.
 *
 * The badge is always drawn, because the spec requires a user to be able to tell
 * at a glance whether something is blocked by their own rule or by the shared
 * one — and the two are managed in different places.
 */
@Composable
private fun ChannelRow(
    channel: com.muddassir.clearview.brainrot.BrainRotRepository.BlockedChannel,
    onDelete: () -> Unit,
    onSuggestGlobal: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.handle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.block_channel_my_block),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            channel.name?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        if (onSuggestGlobal != null) {
            TextButton(onClick = onSuggestGlobal) {
                Text(stringResource(R.string.block_global_suggest), fontSize = 11.sp)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Unblock ${channel.handle}",
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ── 3d-ii. Global (community) rules ──────────────────────────────

/**
 * The centrally-maintained rule set.
 *
 * Shown separately from the user's own rules on purpose: the spec requires a
 * person to always be able to tell whether something is blocked because of
 * their own rule or because of the global repository, and two clearly-labelled
 * cards is how that stays true.
 */
@Composable
private fun GlobalRulesCard(viewModel: MainViewModel) {
    var showDetails by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.block_global_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (viewModel.globalRulesAvailable) {
                            stringResource(
                                R.string.block_global_summary,
                                viewModel.globalKeywordCount,
                                viewModel.globalChannelCount
                            )
                        } else {
                            stringResource(R.string.block_global_unavailable)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
            }

            AnimatedVisibility(visible = showDetails) {
                FeatureDetailBlock(
                    bullets = listOf(
                        "A rule set maintained centrally and applied to every ClearView install automatically.",
                        "It works alongside your own keywords and channels — it never replaces or removes them.",
                        "Your suggestions are reviewed before they become a global rule, so one person's mistake cannot affect everyone.",
                        "Report counts show how many people have asked for a rule, without identifying any of them."
                    )
                )
            }

            if (viewModel.globalRulesAvailable) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { viewModel.syncGlobalRules() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !viewModel.globalRulesSyncing
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (viewModel.globalRulesSyncing) R.string.block_global_syncing
                            else R.string.block_global_sync
                        ),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

}

// ── 3e. Activity (protection statistics) ─────────────────────────

@Composable
private fun ActivityCard(viewModel: MainViewModel) {
    val summary = viewModel.brainRotSummary
    var showResetConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Protection activity",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showResetConfirm = true }) {
                    Text(stringResource(R.string.block_clear_activity), fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(stringResource(R.string.block_today), "${summary.todayBlocks}", Modifier.weight(1f))
                StatCell(stringResource(R.string.block_this_week), "${summary.weekBlocks}", Modifier.weight(1f))
                StatCell(stringResource(R.string.block_stat_blocks), "${summary.totalBlocks}", Modifier.weight(1f))
            }

            if (summary.streakDays > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = if (summary.streakDays == 1) {
                            stringResource(R.string.block_streak_one_day)
                        } else {
                            stringResource(R.string.block_streak_days, summary.streakDays)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (summary.topKeywords.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.block_most_triggered),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                for ((keyword, count) in summary.topKeywords) {
                    ActivityRow(keyword, "$count")
                }
            }

            if (summary.topChannels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.block_top_channel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                for ((channel, count) in summary.topChannels) {
                    ActivityRow(channel, "$count")
                }
            }

            if (summary.totalBlocks == 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.block_activity_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.block_clear_activity)) },
            text = { Text(stringResource(R.string.block_clear_activity_note)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    viewModel.clearBrainRotActivity()
                }) {
                    Text(stringResource(R.string.block_reset_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.block_cancel))
                }
            }
        )
    }
}

/** One big number with its label, for the activity counters. */
@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A label on the left, its count on the right. */
@Composable
private fun ActivityRow(label: String, count: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Text(
            text = count,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── 3f. Privacy ──────────────────────────────────────────────────

@Composable
private fun PrivacyCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.block_privacy_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.block_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── 4. Blocked Items (dotted, editable) ───────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockedItemsCard(viewModel: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    // Hoisted out of drawBehind: MaterialTheme is a composable read and cannot
    // be accessed inside the non-composable DrawScope lambda.
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val strokeWidth = 2.dp.toPx()
                val dash = floatArrayOf(strokeWidth * 5, strokeWidth * 5)
                drawRoundRect(
                    color = outlineColor,
                    style = Stroke(width = strokeWidth, pathEffect = PathEffect.dashPathEffect(dash)),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            },
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Blocked Items",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${viewModel.userKeywords.size} keywords · ${viewModel.blockedDomains.size} websites",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledIconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Filled.Add, contentDescription = if (expanded) "Close editor" else "Add blocked items")
                }
                InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
            }

            AnimatedVisibility(visible = showDetails) {
                FeatureDetailBlock(
                    bullets = listOf(
                        "Keywords block searches, video titles and page text across Chrome and the Google app.",
                        "Websites are blocked by domain — every page on that domain is blocked.",
                        "Matching uses word boundaries: \"button\" never matches \"butt\", \"brass\" never matches \"bra\".",
                        "Your own keywords are always blocked as exact words; the built-in pattern system only blocks innocent words when they are combined with an adult term.",
                        "Everything here is enforced alongside the always-on adult filter, Strict Mode, Shorts and long-video blocking."
                    )
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Add keyword
                    OutlinedTextField(
                        value = viewModel.newKeywordText,
                        onValueChange = { viewModel.updateNewKeyword(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Add a keyword to block") },
                        placeholder = { Text("e.g., instagram, tiktok") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { viewModel.addKeyword() }),
                        trailingIcon = {
                            IconButton(onClick = { viewModel.addKeyword() }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add keyword")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (viewModel.userKeywords.isNotEmpty()) {
                        Text(
                            text = "Keywords",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (keyword in viewModel.userKeywords) {
                                BlockedChip(
                                    label = keyword,
                                    onDelete = { viewModel.removeKeyword(keyword) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Add website
                    OutlinedTextField(
                        value = viewModel.newDomainText,
                        onValueChange = { viewModel.updateNewDomain(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Add a website to block") },
                        placeholder = { Text("e.g., youtube.com, reddit.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { viewModel.addDomain() }),
                        trailingIcon = {
                            IconButton(onClick = { viewModel.addDomain() }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add website")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (viewModel.blockedDomains.isNotEmpty()) {
                        Text(
                            text = "Websites",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (domain in viewModel.blockedDomains) {
                                BlockedChip(
                                    label = domain,
                                    onDelete = { viewModel.removeDomain(domain) }
                                )
                            }
                        }
                    }

                    if (viewModel.userKeywords.isEmpty() && viewModel.blockedDomains.isEmpty()) {
                        Text(
                            text = "Nothing blocked yet. Add keywords or websites above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedChip(label: String, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove $label",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ── 5. DNS protection ─────────────────────────────────────────────

@Composable
private fun DnsCard(viewModel: MainViewModel, context: Context) {
    var showSetupSheet by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val currentDns = viewModel.getPrivateDnsProvider(context)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "DNS Protection",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Blocks adult content at the network level — in every app, including incognito.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(visible = showDetails) {
                FeatureDetailBlock(
                    bullets = listOf(
                        "Network-level filtering that works on ALL apps.",
                        "Enforced automatically by ClearView using Device Owner policy.",
                        "Android Settings will be locked so it cannot be bypassed.",
                        "Works alongside ClearView's app-level blocking."
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showSetupSheet = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Select DNS Provider", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (currentDns != null) "Active: $currentDns" else "Not active",
                style = MaterialTheme.typography.bodySmall,
                color = if (currentDns != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontWeight = if (currentDns != null) FontWeight.Bold else FontWeight.Normal
            )
        }
    }

    if (showSetupSheet) {
        DnsSetupSheet(
            viewModel = viewModel,
            context = context,
            onDismiss = { showSetupSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsSetupSheet(
    viewModel: MainViewModel,
    context: Context,
    onDismiss: () -> Unit
) {
    var activeProvider by remember { mutableStateOf(viewModel.getPrivateDnsProvider(context)) }

    fun selectProvider(hostname: String) {
        val success = viewModel.setPrivateDnsProvider(context, hostname)
        if (success) {
            activeProvider = hostname
            Toast.makeText(context, "Private DNS set and locked", Toast.LENGTH_SHORT).show()
            onDismiss()
        } else {
            Toast.makeText(context, "Failed to set Private DNS. Check Device Owner status.", Toast.LENGTH_LONG).show()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Select DNS Provider",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Device Owner Enforcement",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Selecting a provider will enforce it system-wide and lock the Android Settings UI to prevent bypass.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DnsProviderCard(
                name = "Cloudflare Family (1.1.1.3)",
                description = "Blocks malware + adult content. Fast, free, no account.",
                hostname = viewModel.cloudflareFamilyHostname(),
                isActive = activeProvider == viewModel.cloudflareFamilyHostname(),
                onSelect = { selectProvider(it) }
            )

            DnsProviderCard(
                name = "CleanBrowsing Family Filter",
                description = "Blocks adult content + malware. Strict family filter.",
                hostname = viewModel.cleanBrowsingFamilyHostname(),
                isActive = activeProvider == viewModel.cleanBrowsingFamilyHostname(),
                onSelect = { selectProvider(it) }
            )
        }
    }
}

@Composable
private fun DnsProviderCard(
    name: String,
    description: String,
    hostname: String,
    isActive: Boolean,
    onSelect: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = { if (!isActive) onSelect(hostname) }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (isActive) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = hostname,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ── 6. Advanced: Device Admin ─────────────────────────────────────

@Composable
private fun DeviceAdminCard(
    viewModel: MainViewModel,
    context: Context,
    deviceAdminLauncher: ActivityResultLauncher<Intent>
) {
    val isAdmin = viewModel.isDeviceAdminEnabled
    var showDetails by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isAdmin)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.AdminPanelSettings,
                contentDescription = null,
                tint = if (isAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Device Admin",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isAdmin)
                        "Active — adds uninstall protection step"
                    else
                        "Inactive — app can be uninstalled freely",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isAdmin) {
                TextButton(
                    onClick = {
                        val intent = Intent(
                            android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
                        ).apply {
                            putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                android.content.ComponentName(
                                    context,
                                    com.muddassir.clearview.receiver.DeviceAdminReceiver::class.java
                                )
                            )
                            putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "Activating device admin adds a deactivation step before uninstall, " +
                                    "making it harder to accidentally remove the protection app."
                            )
                        }
                        try {
                            deviceAdminLauncher.launch(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("BlockTab", "Failed to launch Device Admin: ${e.message}")
                            val fallbackIntent = Intent(
                                android.provider.Settings.ACTION_SECURITY_SETTINGS
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(fallbackIntent)
                        }
                    }
                ) {
                    Text("Activate", fontSize = 13.sp)
                }
            }
            InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
        }
        AnimatedVisibility(visible = showDetails) {
            FeatureDetailBlock(
                bullets = listOf(
                    "Makes the app a Device Admin, which adds a confirmation step before the app can be uninstalled.",
                    "Required before Device Owner (full uninstall block) can be set.",
                    "Can be revoked anytime from Settings → Security → Device admin apps."
                )
            )
        }
    }
}

// ── 6. Advanced: Uninstall Protection (Device Owner) ──────────────

@Composable
private fun UninstallProtectionCard(viewModel: MainViewModel, context: Context) {
    val isOwner = viewModel.isDeviceOwner
    val isAdmin = viewModel.isDeviceAdminEnabled
    var showRemoveOwnerConfirm by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOwner && isAdmin)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isOwner && isAdmin) Icons.Filled.VerifiedUser else Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    tint = if (isOwner && isAdmin)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Uninstall Protection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = when {
                            isOwner && isAdmin -> "✅ Uninstall completely blocked — factory reset required"
                            isAdmin -> "Device Admin active — adds uninstall friction (1 extra step)"
                            else -> "No protection — app can be uninstalled freely"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
            }

            AnimatedVisibility(visible = showDetails) {
                FeatureDetailBlock(
                    bullets = listOf(
                        "Device Owner blocks uninstall completely — a factory reset is required to remove the app.",
                        "Set up via ADB (the steps appear on this card once Device Admin is active).",
                        "Use \"Remove Uninstall Protection\" when you need to update or uninstall — all your data is kept."
                    )
                )
            }

            if (isOwner && isAdmin) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Device Owner is active — the app cannot be uninstalled. " +
                        "Tap below to lift the lock (needed to update or remove the app). " +
                        "All your data is kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showRemoveOwnerConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove Uninstall Protection", fontSize = 13.sp)
                }
            }

            if (showRemoveOwnerConfirm) {
                AlertDialog(
                    onDismissRequest = { showRemoveOwnerConfirm = false },
                    title = { Text("Remove Uninstall Protection?") },
                    text = {
                        Text(
                            "The app will stop being Device Owner, so you can update or " +
                                "uninstall it normally again. Your blocked lists and settings are kept."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showRemoveOwnerConfirm = false
                            viewModel.removeUninstallProtection(context)
                        }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRemoveOwnerConfirm = false }) { Text("Cancel") }
                    }
                )
            }

            if (!isOwner && isAdmin) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Upgrade to Device Owner for full protection:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. Connect your phone to a computer with ADB\n" +
                        "2. Temporarily remove your Google account (Settings > Accounts)\n" +
                        "3. Run this command in terminal:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = viewModel.getDeviceOwnerAdbCommand(),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "4. Re-add your Google account\n" +
                        "5. Re-open the app — uninstall will be blocked permanently",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── 6. Advanced: App Lock ─────────────────────────────────────────

@Composable
private fun AppLockCard(viewModel: MainViewModel) {
    val hasPwd = viewModel.hasPassword
    var showDetails by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasPwd)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (hasPwd) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = if (hasPwd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Block Tab Password",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (hasPwd)
                        "Password set — Block tab locks when the app goes to background"
                    else
                        "No password — Block tab is open to anyone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hasPwd) {
                TextButton(onClick = { viewModel.clearAppPassword() }) {
                    Text("Remove", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = { viewModel.appLockTriggered = true }) {
                    Text("Set", fontSize = 13.sp)
                }
            }
            InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
        }
        AnimatedVisibility(visible = showDetails) {
            FeatureDetailBlock(
                bullets = listOf(
                    "Locks the Block tab whenever the app goes to the background.",
                    "The password protects the dashboard and its settings from being changed by anyone else.",
                    "No password = the tab is open to anyone."
                )
            )
        }
    }
}
