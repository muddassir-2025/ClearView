package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.ChannelSection
import com.muddassir.clearview.goodpost.data.ChannelSortChip
import com.muddassir.clearview.goodpost.data.GoodPostChannel

/**
 * The Channels screen (§7) — what exists, in sections.
 *
 * Replaces the old Explore-as-a-list-of-one: a page whose only content was a
 * search box and a category strip answered "what did I type" before it answered
 * "what is here". This screen answers the second question first, in sections a
 * reader can see the shape of, and leaves searching to the screen that is named
 * for it.
 *
 * One vertical scroll rather than a chip row per section, because the sections
 * ARE the navigation here: "Explore channels" is the mixed, most-active list,
 * each category is its own strip, and "More channels" is what has just arrived. A
 * category strip on top of that would be a second, competing axis of navigation
 * on a page whose job is to show what exists.
 *
 * Every section is loaded at once, from ONE request per section, and a section
 * with nothing in it draws nothing — a heading over an empty list is a claim that
 * there is something there.
 */
@Composable
internal fun GoodPostDiscover(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        WaTopBar(
            title = stringResource(R.string.goodpost_channels),
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    onClick = { viewModel.back() }
                )
            },
            actions = {
                WaIconAction(
                    icon = Icons.Filled.Search,
                    description = stringResource(R.string.goodpost_search),
                    onClick = { viewModel.openExplore() }
                )
            }
        )

        if (state.discoverError != null && !state.discoverLoading && state.discoverSections.isEmpty()) {
            WaErrorNotice(state.discoverError)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // §12: the Explore placement, above the first section and below
                // the app bar. Drawn from the cache on the first frame, so it is
                // there before the sections are.
                if (state.exploreAds.isNotEmpty()) {
                    item(key = "ads") { AdCarousel(ads = state.exploreAds) }
                }

                // The loading state is a SECTION IN THE LIST, not a layer over
                // it: two placeholder sections where the real ones will be, so
                // the carousel above stays visible and the page does not jump
                // when the rows land.
                if (state.discoverLoading && state.discoverSections.values.all { it.isEmpty() }) {
                    items(2) { index ->
                        WaDiscoverSectionSkeleton(rows = if (index == 0) 4 else 3)
                    }
                }

                ChannelSection.entries.forEach { section ->
                    val channels = state.discoverSections[section].orEmpty()

                    // EVERY section gets its heading, including one the server
                    // returned nothing for.
                    //
                    // This was the other way round for a while — an empty section
                    // drew nothing at all — on the theory that a heading over no
                    // rows looks broken. It does not: it looks like a catalogue
                    // with a gap in it, which is what it is, and the reader can
                    // see that "Sports" exists and has nothing in it yet. Hiding
                    // them hid the shape of the product.
                    item(key = "sec-${section.name}") {
                        SectionHeader(
                            title = stringResource(section.titleRes()),
                            onSeeAll = { viewModel.openSection(section) }
                        )
                    }

                    if (channels.isEmpty()) {
                        item(key = "none-${section.name}") { SectionEmptyNote() }
                    }

                    items(channels, key = { "${section.name}-${it.id}" }) { channel ->
                        DiscoverRow(
                            channel = channel,
                            following = channel.id in state.followedIds,
                            busy = state.followBusyId == channel.id,
                            onFollow = { viewModel.toggleFollow(channel.id) },
                            onClick = { viewModel.openChannel(channel.id) }
                        )
                    }
                }

                if (state.discoverSections.isEmpty() &&
                    !state.discoverLoading && state.discoverError == null
                ) {
                    item(key = "empty") {
                        WaEmptyState(
                            title = stringResource(R.string.goodpost_empty_explore_title),
                            note = stringResource(R.string.goodpost_empty_explore_note),
                            actionLabel = stringResource(R.string.goodpost_search),
                            onAction = { viewModel.openExplore() }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One section in full (§7), behind its "See all".
 *
 * The full list rather than a truncated one — that is the whole reason the button
 * exists — with the four orders as chips. The app bar keeps the section's name
 * because that is still where the reader is; the chips are how they have chosen
 * to read it since, which is why the title does not change when one is tapped.
 *
 * The next page is fetched as the end of the list comes into view rather than by
 * a button. A "Load more" control at the bottom of a long list is a second thing
 * to find after the thing you were looking for, and the reason the button was
 * there — not fetching everything at once — is already answered by the cursor.
 * The trigger is the last item entering the viewport, not the list's last index,
 * so a short first page does not immediately ask for a second.
 */
@Composable
internal fun GoodPostSection(
    state: GoodPostUiState,
    section: ChannelSection,
    viewModel: GoodPostViewModel
) {
    val listState = rememberLazyListState()

    // `derivedStateOf` so a scroll does not recompose this screen: the value only
    // changes when the answer changes, not on every pixel.
    val atEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 3
        }
    }

    LaunchedEffect(atEnd, state.sectionCursor, state.sectionLoading) {
        if (atEnd && state.sectionCursor != null && !state.sectionLoading) {
            viewModel.loadMoreSection()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        WaTopBar(
            title = stringResource(section.titleRes()),
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    onClick = { viewModel.back() }
                )
            },
            actions = {
                WaIconAction(
                    icon = Icons.Filled.Search,
                    description = stringResource(R.string.goodpost_search),
                    onClick = { viewModel.openExplore() }
                )
            }
        )

        WaSearchField(
            value = state.sectionQuery,
            onValueChange = viewModel::onSectionQueryChange,
            placeholder = stringResource(R.string.goodpost_search_channels),
            onSearch = viewModel::loadSection,
            onClear = {
                viewModel.onSectionQueryChange("")
                viewModel.loadSection()
            }
        )

        WaFilterRow {
            ChannelSortChip.entries.forEach { chip ->
                WaFilterPill(
                    label = stringResource(chip.labelRes()),
                    selected = state.sectionSort == chip.sort,
                    onClick = { viewModel.selectSectionSort(chip.sort) }
                )
            }
        }

        if (state.sectionError != null && !state.sectionLoading && state.sectionItems.isEmpty()) {
            WaErrorNotice(state.sectionError)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                if (state.sectionLoading && state.sectionItems.isEmpty()) {
                    items(6) { WaDiscoverRowSkeleton() }
                }

                items(state.sectionItems, key = { it.id }) { channel ->
                    DiscoverRow(
                        channel = channel,
                        modifier = Modifier.animateItem(),
                        following = channel.id in state.followedIds,
                        busy = state.followBusyId == channel.id,
                        onFollow = { viewModel.toggleFollow(channel.id) },
                        onClick = { viewModel.openChannel(channel.id) }
                    )
                }

                // §7: the cursor is what makes this the FULL list. The footer is
                // the spinner only — the fetch is driven by the list reaching its
                // end, so this is a statement that more is coming, not a button.
                if (state.sectionCursor != null && state.sectionLoading) {
                    item(key = "more") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Wa.Accent
                            )
                        }
                    }
                }

                if (state.sectionItems.isEmpty() && !state.sectionLoading) {
                    item(key = "empty") {
                        WaEmptyState(
                            title = stringResource(R.string.goodpost_empty_explore_title),
                            note = stringResource(R.string.goodpost_empty_explore_note),
                            actionLabel = null,
                            onAction = {}
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Nothing here yet", under a section that has no channels.
 *
 * A quiet line rather than a card or an illustration: the section is not an
 * error and not an invitation, it is simply empty, and the heading above it has
 * already said which category it is.
 */
@Composable
private fun SectionEmptyNote() {
    Text(
        text = stringResource(R.string.goodpost_section_empty),
        color = Wa.TextDim,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)
    )
}

/**
 * A section's heading, with its "See all" (§7).
 *
 * The pill is the same control the Channels header already uses, so "See all"
 * reads as a way in rather than as a second kind of button.
 */
@Composable
private fun SectionHeader(title: String, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Wa.Text,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        WaPillButton(
            text = stringResource(R.string.goodpost_see_all),
            onClick = onSeeAll
        )
    }
}

/**
 * One channel on a discovery screen (§7).
 *
 * Avatar, name, follower count and Follow — the four things a reader decides on
 * when they are looking for something to follow, and nothing else. It is
 * deliberately not [WaChannelRow]: that row describes what a channel last SAID,
 * which is the right question on the home list of channels already followed and
 * the wrong one here, where the channel is unknown.
 *
 * The name wraps to two lines rather than ellipsizing: a channel's name is what
 * it is called, and "Daily Quran Reci…" is not a shorter version of a name, it is
 * a different one.
 */
@Composable
private fun DiscoverRow(
    channel: GoodPostChannel,
    following: Boolean,
    busy: Boolean,
    onFollow: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .waTappable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WaAvatar(name = channel.name, size = 49.dp, url = channel.iconUrl)

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    color = Wa.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // A verified channel is one the platform runs, so the badge is
                // drawn from the server's own flag rather than guessed from a
                // follower count — a number a channel can earn, and a badge it
                // cannot.
                if (channel.verified) {
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        Icons.Filled.Verified,
                        contentDescription = stringResource(R.string.goodpost_verified),
                        tint = Wa.Accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (channel.followerCount > 0) {
                    // A PLURALS resource, so it needs `pluralStringResource`: the
                    // two differ by resource type, and passing a plurals id to
                    // the string one throws `Resources$NotFoundException` the
                    // moment a row with followers is drawn — which is why this
                    // screen closed as soon as it had anything to show.
                    pluralStringResource(
                        R.plurals.goodpost_follower_count,
                        channel.followerCount,
                        waCompactCount(channel.followerCount)
                    )
                } else {
                    // A brand-new channel has no followers, and "No followers
                    // yet" reads as a fault rather than as a beginning. Its
                    // category is what it is FOR, which is the useful thing to
                    // say on a discovery row.
                    channel.categoryLabel
                        ?: stringResource(R.string.goodpost_no_followers)
                },
                color = Wa.TextDim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(10.dp))

        WaFollowAction(following = following, busy = busy, onClick = onFollow)
    }
}

/** The section's own title, as a resource. */
private fun ChannelSection.titleRes(): Int = when (this) {
    ChannelSection.Explore -> R.string.goodpost_section_explore
    ChannelSection.Entertainment -> R.string.goodpost_section_entertainment
    ChannelSection.Sports -> R.string.goodpost_section_sports
    ChannelSection.News -> R.string.goodpost_section_news
    ChannelSection.Lifestyle -> R.string.goodpost_section_lifestyle
    ChannelSection.People -> R.string.goodpost_section_people
    ChannelSection.Business -> R.string.goodpost_section_business
    ChannelSection.Organizations -> R.string.goodpost_section_organizations
    ChannelSection.More -> R.string.goodpost_section_more
}

/** A sort chip's label, as a resource. */
private fun ChannelSortChip.labelRes(): Int = when (this) {
    ChannelSortChip.Trending -> R.string.goodpost_sort_trending
    ChannelSortChip.Active -> R.string.goodpost_sort_active
    ChannelSortChip.New -> R.string.goodpost_sort_new
    ChannelSortChip.Name -> R.string.goodpost_sort_name
}
