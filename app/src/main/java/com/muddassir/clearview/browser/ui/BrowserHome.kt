package com.muddassir.clearview.browser.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.browser.BrowserAddress
import com.muddassir.clearview.R
import com.muddassir.clearview.browser.BrowserSettings
import com.muddassir.clearview.browser.BrowserShortcutInput
import com.muddassir.clearview.browser.ShortcutIconLinks

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

        Spacer(Modifier.height(22.dp))

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
            // ClearView's own shortcuts are removable like any other: the ✕
            // hides the tile (persisted) and never touches the site, its
            // cookies or the browsing history.
            settings.visibleBuiltInShortcuts.forEach { shortcut ->
                ShortcutTile(
                    label = builtInShortcutLabel(shortcut.name),
                    icon = null,
                    iconRes = builtInShortcutIcon(shortcut.url),
                    onClick = { open(shortcut.url) },
                    onLongClick = { pendingDelete = shortcut.url },
                    onRemove = { pendingDelete = shortcut.url }
                )
            }
            settings.shortcuts.forEach { shortcut ->
                ShortcutTile(
                    label = shortcut.name,
                    icon = null,
                    logo = rememberShortcutLogo(shortcut.url),
                    letter = shortcut.name.take(1).uppercase(),
                    onClick = { open(shortcut.url) },
                    onLongClick = { pendingDelete = shortcut.url },
                    // The cross is the visible way out: a long press is not a
                    // thing the reader can discover on a tile.
                    onRemove = { pendingDelete = shortcut.url }
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
        // A removed tile can be one the reader added OR one ClearView ships
        // (Google / YouTube / Instagram). Both are confirmed the same way; only
        // the store that forgets them differs.
        val shortcut = settings.shortcuts.firstOrNull { it.url == url }
            ?: BrowserSettings.BUILT_IN_SHORTCUTS.firstOrNull { it.url == url }
        if (shortcut != null) {
            val isBuiltIn = settings.isBuiltInShortcut(shortcut)
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.browser_shortcut_remove_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.browser_shortcut_remove_body,
                            if (isBuiltIn) builtInShortcutLabel(shortcut.name)
                            else shortcut.name
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (isBuiltIn) settings.removeBuiltInShortcut(shortcut)
                            else settings.removeShortcut(shortcut)
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

/**
 * The shipped brand mark for a built-in shortcut, keyed by its address.
 *
 * Keyed by URL rather than by position: the list is data, and a reordered list
 * must not silently paint Instagram's mark on Google's tile.
 */
private fun builtInShortcutIcon(url: String): Int? = when (url) {
    "https://www.google.com/" -> R.drawable.ic_google_logo
    "https://www.youtube.com/" -> R.drawable.ic_youtube_logo
    "https://www.instagram.com/" -> R.drawable.ic_instagram_logo
    else -> null
}

/** Localised name of a built-in shortcut (its stored name is the fallback). */
@Composable
private fun builtInShortcutLabel(name: String): String = when (name) {
    "Google" -> stringResource(R.string.browser_shortcut_google)
    "YouTube" -> stringResource(R.string.browser_shortcut_youtube)
    "Instagram" -> stringResource(R.string.browser_shortcut_instagram)
    else -> name
}

/**
 * The site's own logo for a shortcut tile.
 *
 * Read once per host and kept in memory: the start page recomposes on every
 * keystroke in the search box, and re-fetching an icon there would put network
 * work on the UI path. A miss is remembered too, so a site without an icon does
 * not retry on every recomposition — the tile falls back to its initial.
 */
@Composable
private fun rememberShortcutLogo(url: String): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val host = remember(url) { BrowserAddress.host(url)?.removePrefix("www.") }
    var logo by remember(host) { mutableStateOf(ShortcutLogos.remembered(context, host)) }
    LaunchedEffect(host) {
        if (host.isNullOrBlank() || ShortcutLogos.isCached(context, host)) return@LaunchedEffect
        logo = ShortcutLogos.load(context, host)
    }
    return logo
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
    logo: ImageBitmap? = null,
    isAdd: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
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
        Box(modifier = Modifier.size(56.dp)) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = when {
                    isAdd -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    // Real app marks keep their own colour on a white tile; on a
                    // dark surface the Google G's negative space would disappear.
                    iconRes != null -> Color.White
                    // A site's own logo needs the same white bed: most are drawn
                    // for a light page and a transparent one would vanish here.
                    logo != null -> Color.White
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
                    } else if (logo != null) {
                        Image(
                            bitmap = logo,
                            contentDescription = null,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
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
            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-4).dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.browser_shortcut_remove_action),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
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

/**
 * Fetches and caches the logo a website uses on its own tab.
 *
 * The page's own `<link rel="icon">` is what a browser uses, and it is the
 * only source that gives the real mark: `/favicon.ico` is an ICO file Android
 * cannot decode, and the favicon service answers with a generic globe for the
 * sites it does not know. So the home page is read first (a few KB of HTML),
 * then every icon it declares is tried until one decodes; the plain favicon
 * paths stay as the fallback for sites that declare nothing.
 *
 * Only the site's own home page is requested, with no cookies and no session,
 * so adding a shortcut cannot leak the reader's history or logged-in state.
 * Everything is best-effort: any failure returns null and the tile keeps its
 * initial.
 */
private object ShortcutLogos {

    private const val TIMEOUT_MS = 6_000
    private const val MAX_IMAGE_BYTES = 512 * 1024
    private const val MAX_PAGE_CHARS = 256 * 1024

    /**
     * How long a downloaded logo is trusted before it is fetched again.
     *
     * Sites change their mark rarely; a month is long enough that the tiles open
     * instantly every time, and short enough that a rebrand eventually lands.
     */
    private const val LOGO_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * How long the "this site has no icon" answer is trusted.
     *
     * Shorter than a hit: a site that had no icon when it was added is exactly
     * the one likely to gain one, and the miss costs one request, not a broken
     * tile.
     */
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** Decoded logos for this process — the first (instant) tier. */
    private val memory = HashMap<String, ImageBitmap?>()

    /**
     * The logo for [host] if this process already has it.
     *
     * Reads the in-memory tier only: it runs during composition, so it must not
     * touch the disk. A missing in-memory entry is what sends the caller to
     * [load], which reads the on-disk tier before the network.
     */
    fun remembered(context: android.content.Context, host: String?): ImageBitmap? {
        if (host.isNullOrBlank()) return null
        return memory[host] ?: readFromDisk(context, host)?.also { memory[host] = it }
    }

    /**
     * True once an answer exists — in memory, or on disk and still fresh.
     *
     * This is what stops the start page from re-downloading a logo on every
     * visit: the disk cache survives the tab being recreated and the app being
     * killed, which the memory map alone did not.
     */
    fun isCached(context: android.content.Context, host: String): Boolean {
        if (memory.containsKey(host)) return true
        return readFromDisk(context, host) != null || isFreshMiss(context, host)
    }

    suspend fun load(context: android.content.Context, host: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (memory.containsKey(host)) return@withContext memory[host]
            // Disk before network: a logo fetched on an earlier visit is already
            // on this device, so the tile paints with no request at all.
            readFromDisk(context, host)?.let { cached ->
                memory[host] = cached
                return@withContext cached
            }
            if (isFreshMiss(context, host)) {
                memory[host] = null
                return@withContext null
            }
            val page = fetchText("https://$host/")
            var ready: ImageBitmap? = null
            var bytes: ByteArray? = null
            for (icon in ShortcutIconLinks.candidates(page, host)) {
                val raw = fetchImageBytes(icon) ?: continue
                val bitmap = runCatching {
                    android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                }.getOrNull() ?: continue
                ready = bitmap.asImageBitmap()
                bytes = raw
                break
            }
            memory[host] = ready
            writeToDisk(context, host, bytes)
            ready
        }

    // ── The on-disk tier ──────────────────────────────────────────────

    private fun directory(context: android.content.Context): java.io.File =
        java.io.File(context.cacheDir, "shortcut-logos").apply { mkdirs() }

    /** A host is not a filename: `example.com` is fine, a port or `..` is not. */
    private fun keyFor(host: String): String =
        host.lowercase().replace(Regex("[^a-z0-9._-]"), "_")

    private fun logoFile(context: android.content.Context, host: String): java.io.File =
        java.io.File(directory(context), keyFor(host) + ".png")

    private fun missFile(context: android.content.Context, host: String): java.io.File =
        java.io.File(directory(context), keyFor(host) + ".none")

    private fun isFresh(file: java.io.File, ttlMs: Long): Boolean =
        file.isFile && System.currentTimeMillis() - file.lastModified() < ttlMs

    private fun isFreshMiss(context: android.content.Context, host: String): Boolean =
        isFresh(missFile(context, host), MISS_TTL_MS)

    private fun readFromDisk(context: android.content.Context, host: String): ImageBitmap? {
        val file = logoFile(context, host)
        if (!isFresh(file, LOGO_TTL_MS)) return null
        return runCatching {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    /**
     * Stores the answer for [host]: the image, or an empty marker that says
     * "this site has no icon", so the next visit does not ask again.
     */
    private fun writeToDisk(
        context: android.content.Context,
        host: String,
        png: ByteArray?
    ) {
        runCatching {
            if (png == null) {
                missFile(context, host).writeText("")
                logoFile(context, host).delete()
            } else {
                logoFile(context, host).writeBytes(png)
                missFile(context, host).delete()
            }
        }
    }

    private fun fetchText(address: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(address, "text/html,application/xhtml+xml")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8 * 1024)
                val html = StringBuilder()
                while (html.length < MAX_PAGE_CHARS) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    html.append(buffer, 0, read)
                }
                html.toString()
            }
        } catch (_: Exception) {
            null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) { /* ignore */ }
        }
    }

    private fun open(address: String, accept: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            // Some sites serve no icon to an unknown client at all.
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", accept)
        }

    /**
     * The icon's own bytes, not a decoded bitmap.
     *
     * The bytes are what the disk cache stores, so the next visit decodes from a
     * local file instead of asking the site again — decoding twice is cheap,
     * re-downloading on every open was the problem.
     */
    private fun fetchImageBytes(address: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(address, "image/*")
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (buffer.size() < MAX_IMAGE_BYTES) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
            bytes.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            // A shortcut with no icon is normal, not an error worth surfacing.
            null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) { /* ignore */ }
        }
    }
}
