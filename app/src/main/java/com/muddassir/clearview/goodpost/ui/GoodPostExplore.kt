package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel

/**
 * Explore: every public channel (§7).
 */
@Composable
internal fun GoodPostExplore(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        WaTopBar(
            title = stringResource(R.string.goodpost_explore),
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    onClick = { viewModel.back() }
                )
            }
        )

        WaSearchField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            placeholder = stringResource(R.string.goodpost_search_channels),
            onSearch = viewModel::search,
            onClear = {
                viewModel.onQueryChange("")
                viewModel.search()
            }
        )



        if (state.categories.isNotEmpty()) {
            WaFilterRow {
                WaFilterPill(
                    label = stringResource(R.string.goodpost_all_categories),
                    selected = state.category == null,
                    onClick = { viewModel.selectCategory(null) }
                )
                state.categories.forEach { category ->
                    WaFilterPill(
                        label = category.label,
                        selected = state.category == category.slug,
                        onClick = { viewModel.selectCategory(category.slug) }
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(state.explore, key = { it.id }) { channel ->
                    WaChannelRow(
                        title = channel.name,
                        // §22: this list is filtered as the reader types and
                        // re-fetched as they browse, so rows enter and leave on
                        // almost every keystroke — they move instead of
                        // rebuilding the list under the reader's eye.
                        modifier = Modifier.animateItem(),
                        // A description is what a reader decides on here, so it
                        // is the second line — with a plain fallback rather than
                        // an empty one for a channel that has not written one.
                        preview = channel.description?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.goodpost_no_description),
                        onClick = { viewModel.openChannel(channel.id) },
                        avatar = {
                            WaAvatar(name = channel.name, size = 49.dp, url = channel.iconUrl)
                        },
                        // Follow, on the row (§4): the one per-reader thing
                        // Explore shows, and the reason it is here rather than
                        // only inside the channel is that deciding what to
                        // follow means comparing channels, which is what a list
                        // is for. Tapping the row still opens it — the control
                        // is a separate hit target, so browsing and subscribing
                        // are not the same gesture.
                        trailing = {
                            WaFollowAction(
                                following = channel.id in state.followedIds,
                                busy = state.followBusyId == channel.id,
                                onClick = { viewModel.toggleFollow(channel.id) }
                            )
                        }
                    )
                }

                if (state.exploreCursor != null) {
                    item(key = "more") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.exploreLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Wa.Accent
                                )
                            } else {
                                WaTextAction(
                                    text = stringResource(R.string.goodpost_more),
                                    onClick = viewModel::loadMoreChannels
                                )
                            }
                        }
                    }
                }

                if (state.explore.isEmpty() && !state.exploreLoading) {
                    item(key = "empty") {
                        WaEmptyState(
                            title = stringResource(R.string.goodpost_empty_explore_title),
                            note = stringResource(R.string.goodpost_empty_explore_note),
                            actionLabel = stringResource(R.string.goodpost_search),
                            onAction = viewModel::search
                        )
                    }
                }
            }

            if (state.exploreLoading && state.explore.isEmpty()) {
                CenteredProgress()
            }
        }
    }
}
