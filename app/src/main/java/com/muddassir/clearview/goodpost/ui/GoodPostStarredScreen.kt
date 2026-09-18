package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostStarredEntry
import com.muddassir.clearview.goodpost.data.parseIsoMillis

/**
 * Every message this reader has starred, for one channel (§9, §11).
 *
 * ## Why this is a screen rather than a section
 *
 * The information page used to list them inline, which put a reader's private
 * bookmarks under a channel's description and gave the section whatever height
 * the list happened to need: two stars made a quiet footer, forty turned the
 * page into something else. It also had no room for the thing a list of
 * bookmarks most needs, which is a way to find one. The page now says how many
 * there are; they are read here.
 *
 * ## What a row can do
 *
 * Tap opens the channel the message came from, which is the same answer the
 * information page gave and the only honest one: a star outlives the post it
 * points at (the server keeps thirty days, the star keeps the words), so a deep
 * link is a link that 404s. The star itself is tappable in place, so a list of
 * bookmarks can be pruned without leaving it, and the row disappears the moment
 * it is unstarred rather than on the next visit.
 *
 * ## The search
 *
 * Device-local and instant — the entries are already on the phone, so this is a
 * filter over a list in memory, not a request. It matches the message text and
 * the channel's name, and highlights what matched, because a bookmark list is
 * read by skimming.
 */
@Composable
internal fun GoodPostStarredScreen(
    state: GoodPostUiState,
    channelId: String,
    viewModel: GoodPostViewModel
) {
    var query by remember { mutableStateOf("") }

    val entries = remember(state.starred, query) {
        val term = query.trim().lowercase()
        if (term.isEmpty()) {
            state.starred
        } else {
            state.starred.filter { entry ->
                entry.body?.lowercase()?.contains(term) == true ||
                    entry.channelName.lowercase().contains(term)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WaTopBar(
            title = stringResource(R.string.goodpost_starred),
            // The count is the subtitle rather than a badge: it describes the
            // list, and it is the number the information page promised.
            subtitle = if (state.starred.isEmpty()) {
                null
            } else {
                pluralStringResource(
                    R.plurals.goodpost_starred_count,
                    state.starred.size,
                    state.starred.size
                )
            },
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    onClick = { viewModel.back() }
                )
            },
            // Only worth a search box once there is something to search. On an
            // empty list the field would be the only thing on screen.
            showDivider = state.starred.isNotEmpty()
        )

        if (state.starred.isEmpty()) {
            WaEmptyState(
                title = stringResource(R.string.goodpost_starred),
                note = stringResource(R.string.goodpost_starred_none)
            )
            return@Column
        }

        WaSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.goodpost_starred_search),
            onClear = { query = "" }
        )

        if (entries.isEmpty()) {
            WaEmptyState(
                title = stringResource(R.string.goodpost_starred_no_matches),
                note = stringResource(R.string.goodpost_starred_no_matches_note)
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp)
        ) {
            items(entries, key = { it.postId }) { entry ->
                StarredRow(
                    entry = entry,
                    highlight = query,
                    onOpen = { viewModel.openChannel(channelId) },
                    onUnstar = { viewModel.unstarPost(entry.postId) }
                )
            }
        }
    }
}

/**
 * One starred message.
 *
 * A row rather than a card: it is a message somebody chose to keep, and the
 * channel feed already draws messages as bubbles — a second, prettier frame for
 * the same words would be the beginning of a second design language.
 */
@Composable
private fun StarredRow(
    entry: GoodPostStarredEntry,
    highlight: String,
    onOpen: () -> Unit,
    onUnstar: () -> Unit
) {
    val text = entry.body?.takeIf { it.isNotBlank() }
        ?: stringResource(starredMediaLabel(entry.kind))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = Wa.Accent,
            modifier = Modifier.padding(top = 2.dp).size(16.dp)
        )
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightStarred(text, highlight),
                color = Wa.Text,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = waListStamp(parseIsoMillis(entry.createdAt)),
                color = Wa.TextDim,
                fontSize = 12.sp
            )
        }

        // The way back off the list. An icon rather than a menu: there is exactly
        // one thing to do with a bookmark besides open it.
        IconButton(onClick = onUnstar) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = stringResource(R.string.goodpost_unstar),
                tint = Wa.TextDim,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** The word a media-only starred message is described by. */
private fun starredMediaLabel(kind: String): Int = when (kind) {
    "image" -> R.string.goodpost_posted_photo
    "video" -> R.string.goodpost_posted_video
    "link" -> R.string.goodpost_posted_link
    else -> R.string.goodpost_posted_something
}

/**
 * The row's words with the search term picked out.
 *
 * Case-insensitive, and every occurrence rather than the first: a term that
 * appears three times in a paragraph is the reason the reader searched for it.
 * A blank or numeric term matches reference-shaped queries, which have no words
 * to mark, so nothing is highlighted.
 */
private fun highlightStarred(text: String, query: String): AnnotatedString {
    val term = query.trim()
    if (term.isEmpty()) return AnnotatedString(text)

    val lowerText = text.lowercase()
    val lowerTerm = term.lowercase()
    return buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val match = lowerText.indexOf(lowerTerm, index)
            if (match < 0) {
                append(text, index, text.length)
                break
            }
            append(text, index, match)
            withStyle(SpanStyle(color = Wa.Accent, fontWeight = FontWeight.Bold)) {
                append(text, match, match + term.length)
            }
            index = match + term.length
        }
    }
}
