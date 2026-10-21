package com.muddassir.clearview.browser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.browser.BrowserSettings
import com.muddassir.clearview.browser.BrowserShortcutInput

/** The ClearView wordmark: the same Amatic SC lettering as the app's artwork. */
private val BrandFont = FontFamily(Font(R.font.amatic_sc))

/**
 * The Browser tab's custom start page.
 *
 * It is a Compose surface, not a bundled HTML page: the address bar and
 * WebView stay under the tab's own navigation controls, the enable/disable
 * state cannot be edited by a website, and the shortcuts are the reader's own
 * (persisted in [BrowserSettings]).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserHome(
    settings: BrowserSettings,
    onNavigate: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBackgroundTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val backgroundInteraction = remember { MutableInteractionSource() }

    fun submit() {
        val text = query.trim()
        if (text.isEmpty()) return
        keyboard?.hide()
        focusManager.clearFocus()
        onNavigate(text)
    }

    fun open(url: String) {
        keyboard?.hide()
        focusManager.clearFocus()
        onNavigate(url)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                    )
                )
            )
            // Empty space reveals the app footer; child buttons, fields and
            // shortcuts consume their own taps first, so this never steals an
            // action from a real control.
            .clickable(
                interactionSource = backgroundInteraction,
                indication = null,
                onClick = onBackgroundTap
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.browser_home_brand),
                    fontFamily = BrandFont,
                    fontSize = 58.sp,
                    lineHeight = 58.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.browser_home_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.browser_settings_open)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            contentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.browser_home_protection_active),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(30.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(30.dp),
            placeholder = { Text(stringResource(R.string.browser_home_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { submit() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.browser_go)
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() })
        )

        Spacer(Modifier.height(34.dp))

        Text(
            text = stringResource(R.string.browser_home_shortcuts),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(14.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 4
        ) {
            ShortcutTile(
                label = stringResource(R.string.browser_shortcut_google),
                icon = null,
                iconRes = R.drawable.ic_google_logo,
                onClick = { open(BrowserSettings.BUILT_IN_SHORTCUTS[0].url) }
            )
            ShortcutTile(
                label = stringResource(R.string.browser_shortcut_youtube),
                icon = null,
                iconRes = R.drawable.ic_youtube_logo,
                onClick = { open(BrowserSettings.BUILT_IN_SHORTCUTS[1].url) }
            )
            ShortcutTile(
                label = stringResource(R.string.browser_shortcut_instagram),
                icon = null,
                iconRes = R.drawable.ic_instagram_logo,
                onClick = { open(BrowserSettings.BUILT_IN_SHORTCUTS[2].url) }
            )
            settings.shortcuts.forEach { shortcut ->
                ShortcutTile(
                    label = shortcut.name,
                    icon = null,
                    letter = shortcut.name.take(1).uppercase(),
                    onClick = { open(shortcut.url) },
                    onLongClick = { pendingDelete = shortcut.url }
                )
            }
            ShortcutTile(
                label = stringResource(R.string.browser_home_add_shortcut),
                icon = Icons.Filled.Add,
                isAdd = true,
                onClick = { addOpen = true }
            )
        }

        Spacer(Modifier.height(36.dp))

        Text(
            text = stringResource(R.string.browser_home_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (addOpen) {
        AddShortcutDialog(
            settings = settings,
            onDismiss = { addOpen = false }
        )
    }

    pendingDelete?.let { url ->
        val shortcut = settings.shortcuts.firstOrNull { it.url == url }
        if (shortcut != null) {
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.browser_shortcut_remove_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.browser_shortcut_remove_body,
                            shortcut.name
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            settings.removeShortcut(shortcut)
                            pendingDelete = null
                        }
                    ) {
                        Text(stringResource(R.string.browser_shortcut_remove_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.browser_cancel))
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutTile(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    letter: String? = null,
    letterColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconRes: Int? = null,
    isAdd: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .width(78.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = when {
                isAdd -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                // Real app marks keep their own colour on a white tile; on a
                // dark surface the Google G's negative space would disappear.
                iconRes != null -> Color.White
                else -> MaterialTheme.colorScheme.surface
            },
            tonalElevation = if (isAdd) 0.dp else 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (iconRes != null) {
                    Image(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp)
                    )
                } else if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (isAdd) MaterialTheme.colorScheme.primary else iconTint,
                        modifier = Modifier.size(26.dp)
                    )
                } else {
                    Text(
                        text = letter.orEmpty(),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = letterColor
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AddShortcutDialog(
    settings: BrowserSettings,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val invalidMessage = stringResource(R.string.browser_shortcut_invalid)
    val fullMessage = stringResource(R.string.browser_shortcut_full)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_home_add_shortcut)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.browser_shortcut_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.browser_shortcut_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.browser_shortcut_url)) },
                    placeholder = { Text("example.com") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = BrowserShortcutInput.normalize(url)
                    error = when {
                        normalized == null -> invalidMessage
                        settings.shortcuts.size >= BrowserSettings.MAX_SHORTCUTS &&
                            settings.shortcuts.none {
                                it.url.equals(normalized, ignoreCase = true)
                            } -> fullMessage
                        !settings.addShortcut(name, url) -> fullMessage
                        else -> null
                    }
                    if (error == null) onDismiss()
                }
            ) {
                Text(stringResource(R.string.browser_shortcut_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.browser_cancel))
            }
        }
    )
}
