package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostMedia

/**
 * Search inside ONE channel (§9).
 *
 * The screen a reader reaches from a channel's overflow menu, or from Search on
 * its information page, and its whole point is the scope: the term is answered
 * by the channel they are in, so a match is something this channel said. That is
 * the difference between this and Explore, which searches channels, and the two
 * are easy to confuse — Explore is one tap away from here, and a reader who
 * searched the wrong one would be shown a list of channels when they were asking
 * what a channel said.
 *
 * Results are the channel's own posts, rendered exactly as the feed renders them
 * — a day pill, a bubble, a timestamp. Not a search-result card shape: a post is
 * a message in both places, and a second presentation for the same object is how
 * the two end up disagreeing about what a post looks like.
 *
 * No term means no query, not an empty result. The screen says what it is waiting
 * for instead of claiming the channel has said nothing.
 */
@Composable
internal fun GoodPostChannelSearch(
    state: GoodPostUiState,
    channelId: String,
    viewModel: GoodPostViewModel
) {
    val channel = state.channel
    val term = state.channelSearchTerm

    // The channel itself may not be loaded yet — a search reached from a share
    // link, or after a process death — and the title has to say which channel is
    // being searched rather than "Search".
    LaunchedEffect(channelId) {
        if (state.channel?.id != channelId) viewModel.loadChannel(channelId)
    }

    // A result carries its own attachments, and a reader who searched for a photo
    // should be able to open it here rather than hunting for the post in the
    // feed. Same id-based lookup as the feed, for the same reason: a re-read has
    // to be able to hand the player a fresh signature.
    var viewerPostId by remember { mutableStateOf<String?>(null) }
    var viewerMediaId by remember { mutableStateOf<String?>(null) }
    val viewing = state.channelSearchResults
        .firstOrNull { it.id == viewerPostId }
        ?.media
        ?.firstOrNull { it.id == viewerMediaId }
    val openMedia: (GoodPostMedia) -> Unit = { asset ->
        viewerPostId = state.channelSearchResults
            .firstOrNull { post -> post.media.any { it.id == asset.id } }
            ?.id
        viewerMediaId = asset.id
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        WaTopBar(
            title = channel?.name ?: stringResource(R.string.goodpost_channel),
            subtitle = stringResource(R.string.goodpost_search_in_channel),
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    onClick = { viewModel.back() }
                )
            }
        )

        // IME padding, so the list shrinks with the keyboard instead of being
        // covered by it — the same rule the composer follows (§19 Bug 1).
        Column(modifier = Modifier.fillMaxSize()) {
            WaSearchField(
                value = state.channelSearchQuery,
                onValueChange = viewModel::onChannelSearchQueryChange,
                placeholder = stringResource(R.string.goodpost_search_channel_hint),
                onSearch = viewModel::searchChannelPosts,
                onClear = {
                    viewModel.onChannelSearchQueryChange("")
                    viewModel.searchChannelPosts()
                }
            )

            if (state.channelSearchLoading && state.channelSearchResults.isEmpty()) {
                CenteredProgress(Modifier.height(160.dp))
                return@Column
            }

            state.channelSearchError?.let { code ->
                WaErrorNotice(code)
            }

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.channelSearchResults, key = { it.id }) { post ->
                        PostItem(
                            post = post,
                            // §22: results arrive per keystroke and per page.
                            modifier = Modifier.animateItem(),
                            selected = false,
                            // A result is read here and acted on in the feed, so
                            // it has no tap of its own. Inert rather than
                            // secretly navigating: a tap that lands somewhere
                            // else would lose the search the reader just ran.
                            onClick = {},
                            onLongClick = {},
                            onOpenMedia = openMedia
                        )
                    }

                    if (state.channelSearchCursor != null) {
                        item(key = "more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.channelSearchLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Wa.Accent
                                    )
                                } else {
                                    WaTextAction(
                                        text = stringResource(R.string.goodpost_more),
                                        onClick = viewModel::loadMoreChannelSearch
                                    )
                                }
                            }
                        }
                    }

                    if (state.channelSearchResults.isEmpty() &&
                        !state.channelSearchLoading &&
                        state.channelSearchError == null
                    ) {
                        item(key = "empty") {
                            if (term.isBlank()) {
                                WaEmptyState(
                                    title = stringResource(R.string.goodpost_search_channel_prompt_title),
                                    note = stringResource(R.string.goodpost_search_channel_prompt_note)
                                )
                            } else {
                                WaEmptyState(
                                    title = stringResource(R.string.goodpost_search_channel_empty_title),
                                    // The TERM, not the box's contents — see
                                    // `channelSearchTerm`. Naming text the server
                                    // was never asked about is how a search result
                                    // screen lies about what it searched.
                                    note = stringResource(
                                        R.string.goodpost_search_channel_empty_note,
                                        term
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

        if (viewing != null) {
            MediaViewer(
                kind = viewing.kind,
                url = viewing.url.orEmpty(),
                contentType = viewing.contentType,
                aspect = aspectOf(viewing.width, viewing.height),
                onClose = {
                    viewerPostId = null
                    viewerMediaId = null
                },
                onExpired = { viewerPostId?.let { viewModel.refreshPost(it) } },
                // The searched post's own words, out of this screen's results
                // rather than the channel's page, and the channel's link (§15).
                shareCaption = state.shareCaptionFor(viewerPostId)
            )
        }
    }
}
