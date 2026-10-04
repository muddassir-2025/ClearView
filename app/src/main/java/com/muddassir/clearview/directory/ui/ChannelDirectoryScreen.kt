package com.muddassir.clearview.directory.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.ApiResult
import com.muddassir.clearview.goodpost.data.DirectoryCategory
import com.muddassir.clearview.goodpost.data.DirectoryChannel
import com.muddassir.clearview.goodpost.data.DirectorySnapshot
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostRepository

/**
 * The public channel directory (§ new): the external channels a super
 * administrator has curated.
 *
 * Opened from the icon beside Haramayn Live in the Media tab's channel strip.
 * A card is an icon, a name and a copy button; a tap opens the channel on its own
 * platform. Nothing here is a ClearView channel and nothing here can be followed
 * inside the app — the whole point is a hand-picked list of good places to go.
 *
 * ## Read from disk first
 *
 * The cached snapshot is drawn before any request, so opening the screen is
 * instant and works with no connection. The network read replaces it behind the
 * draw; a refresh is the reader asking for it explicitly, and it is the only thing
 * that writes a new copy. There is no polling and no per-reader state: the list is
 * the same for everybody, which is why it can be cached whole.
 */
@Composable
fun ChannelDirectoryScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { GoodPostRepository(context.applicationContext) }
    val clipboard = LocalClipboardManager.current
    val copiedMessage = stringResource(R.string.channel_directory_copied)

    // The bitmap cache needs its directory attached. Idempotent and cheap after
    // the first call; the Good Post tab attaches it for the same reason.
    LaunchedEffect(Unit) { GoodPostImages.attach(context) }

    var snapshot by remember { mutableStateOf(DirectorySnapshot()) }
    var hasContent by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    var stale by remember { mutableStateOf(false) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    // Bumped by a refresh; the load effect is keyed on it so an explicit request
    // is the only thing that re-fetches.
    var refreshToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        repository.cachedDirectory()?.let { cached ->
            snapshot = cached.snapshot
            hasContent = true
            stale = true
        }
    }

    LaunchedEffect(refreshToken) {
        loading = true
        when (val result = repository.directory()) {
            is ApiResult.Ok -> {
                snapshot = result.value
                hasContent = true
                stale = false
                errorCode = null
            }
            is ApiResult.Failed -> errorCode = result.code
            ApiResult.Unreachable -> errorCode = "unreachable"
        }
        loading = false
    }

    BackHandler(onBack = onExit)

    val selectedCategory = snapshot.categories.firstOrNull { it.id == selectedCategoryId }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DirectoryToolbar(
            loading = loading,
            stale = stale,
            onRefresh = {
                errorCode = null
                refreshToken++
            }
        )

        // Only worth a strip when there is more than one place to file a channel
        // under; a single "All" pill would be a control that never changes a view.
        if (snapshot.categories.isNotEmpty()) {
            CategoryStrip(
                categories = snapshot.categories,
                selectedCategoryId = selectedCategoryId,
                onSelect = { selectedCategoryId = it }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                !hasContent && loading -> DirectoryLoading()

                !hasContent && errorCode != null -> DirectoryError(
                    code = errorCode!!,
                    onRetry = {
                        errorCode = null
                        refreshToken++
                    }
                )

                snapshot.isEmpty && !loading -> DirectoryEmpty()

                else -> DirectoryList(
                    snapshot = snapshot,
                    category = selectedCategory,
                    onOpen = { channel -> openChannelDirectoryUrl(context, channel.url) },
                    onCopy = { channel ->
                        clipboard.setText(AnnotatedString(channel.handle))
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

/** The row under the app bar: the saved/live note and the refresh action. */
@Composable
private fun DirectoryToolbar(
    loading: Boolean,
    stale: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.channel_directory_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (stale) {
            Text(
                text = stringResource(R.string.channel_directory_saved),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = onRefresh, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(stringResource(R.string.channel_directory_refresh))
        }
    }
}

/** "All" plus one pill per category. */
@Composable
private fun CategoryStrip(
    categories: List<DirectoryCategory>,
    selectedCategoryId: String?,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DirectoryPill(
            label = stringResource(R.string.channel_directory_all),
            selected = selectedCategoryId == null,
            onClick = { onSelect(null) }
        )
        categories.forEach { category ->
            DirectoryPill(
                label = category.name,
                selected = selectedCategoryId == category.id,
                onClick = { onSelect(category.id) }
            )
        }
    }
}

@Composable
private fun DirectoryPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

/**
 * The list itself.
 *
 * With no category chosen every channel is shown flat; with one chosen its own
 * channels come first, then each subcategory under a small heading. That mirrors
 * how the directory is filed on the server without making the reader learn the
 * nesting before they can use it.
 */
@Composable
private fun DirectoryList(
    snapshot: DirectorySnapshot,
    category: DirectoryCategory?,
    onOpen: (DirectoryChannel) -> Unit,
    onCopy: (DirectoryChannel) -> Unit
) {
    val rows = remember(snapshot, category) { buildDirectoryRows(snapshot, category) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is DirectoryRowItem.Header -> DirectoryHeaderRow(row.label)
                is DirectoryRowItem.Channel -> DirectoryRow(
                    channel = row.channel,
                    onOpen = onOpen,
                    onCopy = onCopy
                )
            }
        }
    }
}

/**
 * The rows to draw, in order, for the current selection.
 *
 * A pure function so the one non-obvious rule — that a subcategory heading is
 * only worth drawing when there is more than one group to tell apart — is
 * testable without a screen.
 */
internal fun buildDirectoryRows(
    snapshot: DirectorySnapshot,
    category: DirectoryCategory?
): List<DirectoryRowItem> {
    if (category == null) {
        return snapshot.allChannels.map { DirectoryRowItem.Channel(it) }
    }
    val rows = ArrayList<DirectoryRowItem>()
    category.channels.forEach { rows.add(DirectoryRowItem.Channel(it)) }
    category.subcategories.forEach { sub ->
        if (sub.channels.isEmpty()) return@forEach
        rows.add(DirectoryRowItem.Header(sub.name))
        sub.channels.forEach { rows.add(DirectoryRowItem.Channel(it)) }
    }
    return rows
}

/** One row of the directory list: a heading or a channel. */
internal sealed interface DirectoryRowItem {
    val key: String

    data class Header(val label: String) : DirectoryRowItem {
        override val key: String get() = "header:$label"
    }

    data class Channel(val channel: DirectoryChannel) : DirectoryRowItem {
        override val key: String get() = "channel:${channel.id}"
    }
}

@Composable
private fun DirectoryHeaderRow(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
    )
}

/** One channel: icon, name, handle, a copy button, and a tap that opens it. */
@Composable
private fun DirectoryRow(
    channel: DirectoryChannel,
    onOpen: (DirectoryChannel) -> Unit,
    onCopy: (DirectoryChannel) -> Unit
) {
    Surface(
        onClick = { onOpen(channel) },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DirectoryIcon(channel)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "@${channel.handle} · ${platformLabel(channel.platform)}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onCopy(channel) }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.channel_directory_copy_handle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The channel's picture, or its initial until one exists. */
@Composable
private fun DirectoryIcon(channel: DirectoryChannel) {
    var bitmap by remember(channel.iconUrl) { mutableStateOf(GoodPostImages.peek(channel.iconUrl)) }

    LaunchedEffect(channel.iconUrl) {
        if (bitmap == null && !channel.iconUrl.isNullOrBlank()) {
            bitmap = GoodPostImages.load(channel.iconUrl, maxWidthPx = 192)
        }
    }

    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = channel.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DirectoryLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DirectoryEmpty() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = stringResource(R.string.channel_directory_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.channel_directory_empty_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DirectoryError(code: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = stringResource(R.string.channel_directory_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.channel_directory_error_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.channel_directory_refresh))
            }
        }
    }
}

/** A channel card's platform, in the words the platform uses for itself. */
internal fun platformLabel(platform: String): String = when (platform) {
    "youtube" -> "YouTube"
    "instagram" -> "Instagram"
    "x" -> "X"
    else -> platform
}

/** Open the channel's own page. A no-op when nothing can handle the URL. */
private fun openChannelDirectoryUrl(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
