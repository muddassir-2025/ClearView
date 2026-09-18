package com.muddassir.clearview.goodpost.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostChannel
import kotlinx.coroutines.launch

/**
 * Country / Region item for the WhatsApp Channels region selection sheet.
 */
internal data class RegionItem(val name: String, val code: String?)

private val DEFAULT_REGIONS = listOf(
    RegionItem("All", null),
    RegionItem("India", "IN"),
    RegionItem("Afghanistan", "AF"),
    RegionItem("Åland Islands", "AX"),
    RegionItem("Albania", "AL"),
    RegionItem("Algeria", "DZ"),
    RegionItem("American Samoa", "AS"),
    RegionItem("Andorra", "AD"),
    RegionItem("Angola", "AO"),
    RegionItem("Argentina", "AR"),
    RegionItem("Australia", "AU"),
    RegionItem("Austria", "AT"),
    RegionItem("Bahrain", "BH"),
    RegionItem("Bangladesh", "BD"),
    RegionItem("Belgium", "BE"),
    RegionItem("Brazil", "BR"),
    RegionItem("Canada", "CA"),
    RegionItem("China", "CN"),
    RegionItem("Egypt", "EG"),
    RegionItem("France", "FR"),
    RegionItem("Germany", "DE"),
    RegionItem("Indonesia", "ID"),
    RegionItem("Italy", "IT"),
    RegionItem("Japan", "JP"),
    RegionItem("Kuwait", "KW"),
    RegionItem("Malaysia", "MY"),
    RegionItem("Mexico", "MX"),
    RegionItem("Netherlands", "NL"),
    RegionItem("New Zealand", "NZ"),
    RegionItem("Nigeria", "NG"),
    RegionItem("Oman", "OM"),
    RegionItem("Pakistan", "PK"),
    RegionItem("Philippines", "PH"),
    RegionItem("Qatar", "QA"),
    RegionItem("Saudi Arabia", "SA"),
    RegionItem("Singapore", "SG"),
    RegionItem("South Africa", "ZA"),
    RegionItem("Spain", "ES"),
    RegionItem("Sweden", "SE"),
    RegionItem("Switzerland", "CH"),
    RegionItem("Turkey", "TR"),
    RegionItem("United Arab Emirates", "AE"),
    RegionItem("United Kingdom", "GB"),
    RegionItem("United States", "US")
)

private val SEARCH_CATEGORIES = listOf(
    "Entertainment",
    "Sports",
    "News & Information",
    "Lifestyle",
    "People",
    "Business",
    "Organizations"
)

/**
 * WhatsApp-style Explore Channels Screen (§7).
 *
 * Implements the full WhatsApp Channels interaction model:
 * - Clean top bar with search & region filter sheet actions
 * - Category filter pills ("Explore", "Most active", "Popular", "New", custom categories, and "🌐 Region")
 * - Section header ("Explore channels" with "See all" pill)
 * - Channel row with 49dp avatar, verified checkmark badge, follower counts, and dark green Follow pill
 * - Region selection bottom sheet with country search and radio list
 * - Empty state search illustration with green accent magnifying glass
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoodPostExplore(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    var isSearching by remember { mutableStateOf(state.query.isNotEmpty()) }
    var showRegionSheet by remember { mutableStateOf(false) }
    var selectedRegion by remember { mutableStateOf(DEFAULT_REGIONS.first()) }

    // Filter channels by selected region if a region is active
    val filteredChannels = remember(state.explore, selectedRegion) {
        if (selectedRegion.code == null) {
            state.explore
        } else {
            state.explore.filter { channel ->
                channel.countryCode?.equals(selectedRegion.code, ignoreCase = true) == true ||
                    channel.name.contains(selectedRegion.name, ignoreCase = true) ||
                    channel.description?.contains(selectedRegion.name, ignoreCase = true) == true
            }.ifEmpty { state.explore }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        if (isSearching) {
            // Search Top Bar with back arrow
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Wa.Bar)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    isSearching = false
                    viewModel.onQueryChange("")
                    viewModel.search()
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.goodpost_back),
                        tint = Wa.Text
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    WaSearchField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = stringResource(R.string.goodpost_search_hint),
                        onSearch = viewModel::search,
                        onClear = {
                            viewModel.onQueryChange("")
                            viewModel.search()
                        }
                    )
                }
            }

            // Quick Category Pills below search bar (matching image 2b312e24-f426-457e-abcd-bc21c23a162c.jpg)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SEARCH_CATEGORIES.forEach { categoryName ->
                    val isSelected = state.query.equals(categoryName, ignoreCase = true) ||
                        state.category?.equals(categoryName.lowercase().replace(" ", "-"), ignoreCase = true) == true
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) Wa.Accent.copy(alpha = 0.2f) else Wa.Bar)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Wa.Accent else Wa.Divider,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable {
                                if (isSelected) {
                                    viewModel.onQueryChange("")
                                    viewModel.selectCategory(null)
                                } else {
                                    viewModel.onQueryChange(categoryName)
                                    viewModel.search()
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = categoryName,
                            color = if (isSelected) Wa.Accent else Wa.Text,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        } else {
            // Normal Explore Top Bar
            WaTopBar(
                title = stringResource(R.string.goodpost_explore_channels),
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
                        onClick = { isSearching = true }
                    )
                    WaIconAction(
                        icon = Icons.Filled.Tune,
                        description = stringResource(R.string.goodpost_region),
                        onClick = { showRegionSheet = true }
                    )
                }
            )

            // WhatsApp Filter Pills Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isExploreSelected = state.sort == "recent" && state.category == null && selectedRegion.code == null
                WaFilterPill(
                    label = stringResource(R.string.goodpost_explore),
                    selected = isExploreSelected,
                    onClick = {
                        selectedRegion = DEFAULT_REGIONS.first()
                        viewModel.selectCategory(null)
                        viewModel.selectSort("recent")
                    }
                )

                WaFilterPill(
                    label = stringResource(R.string.goodpost_filter_most_active),
                    selected = state.sort == "active",
                    onClick = { viewModel.selectSort("active") }
                )

                WaFilterPill(
                    label = stringResource(R.string.goodpost_filter_popular),
                    selected = state.sort == "popular",
                    onClick = { viewModel.selectSort("popular") }
                )

                WaFilterPill(
                    label = stringResource(R.string.goodpost_filter_new),
                    selected = state.sort == "new",
                    onClick = { viewModel.selectSort("new") }
                )

                state.categories.forEach { category ->
                    WaFilterPill(
                        label = category.label,
                        selected = state.category == category.slug,
                        onClick = { viewModel.selectCategory(category.slug) }
                    )
                }

                // Region filter pill
                WaFilterPill(
                    label = if (selectedRegion.code == null) "🌐 ${stringResource(R.string.goodpost_region)}" else "🌐 ${selectedRegion.name}",
                    selected = selectedRegion.code != null,
                    onClick = { showRegionSheet = true }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // Section Header (when not searching)
                if (!isSearching && filteredChannels.isNotEmpty()) {
                    item(key = "section_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.goodpost_explore_channels),
                                color = Wa.Text,
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Wa.Bar)
                                    .clickable { viewModel.selectSort("popular") }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.goodpost_see_all),
                                    color = Wa.TextDim,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                items(filteredChannels, key = { it.id }) { channel ->
                    ExploreChannelRow(
                        channel = channel,
                        isFollowing = channel.id in state.followedIds,
                        isBusy = state.followBusyId == channel.id,
                        onClick = { viewModel.openChannel(channel.id) },
                        onToggleFollow = { viewModel.toggleFollow(channel.id) },
                        modifier = Modifier.animateItem()
                    )
                }

                if (state.exploreCursor != null) {
                    item(key = "more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
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

                // Empty State with WhatsApp Graphic
                if (filteredChannels.isEmpty() && !state.exploreLoading) {
                    item(key = "empty") {
                        if (isSearching && state.query.isBlank()) {
                            WaSearchIllustration(
                                text = stringResource(R.string.goodpost_search_channels),
                                modifier = Modifier.padding(top = 48.dp)
                            )
                        } else {
                            WaEmptyState(
                                title = stringResource(R.string.goodpost_empty_explore_title),
                                note = stringResource(R.string.goodpost_empty_explore_note),
                                actionLabel = stringResource(R.string.goodpost_search),
                                onAction = {
                                    isSearching = true
                                    viewModel.search()
                                }
                            )
                        }
                    }
                }
            }

            if (state.exploreLoading && filteredChannels.isEmpty()) {
                CenteredProgress()
            }
        }
    }

    // Region Selection Bottom Sheet (matching image 06dd16f9-c400-4445-b03a-1afb42ab3b9c.jpg)
    if (showRegionSheet) {
        RegionSelectionSheet(
            selectedRegion = selectedRegion,
            onSelectRegion = { region ->
                selectedRegion = region
                showRegionSheet = false
            },
            onDismiss = { showRegionSheet = false }
        )
    }
}

/**
 * Channel Row matching WhatsApp Explore Channels list:
 * - 49dp circular avatar
 * - Bold channel title with verified badge
 * - Formatted followers subtitle: "1.1M followers" or description
 * - Dark green pill "Follow" or outlined "Following" button vertically centered on right
 */
@Composable
private fun ExploreChannelRow(
    channel: GoodPostChannel,
    isFollowing: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val followerText = if (channel.followerCount > 0) {
        "${waCompactCount(channel.followerCount)} followers"
    } else {
        channel.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.goodpost_no_description)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WaAvatar(name = channel.name, size = 49.dp, url = channel.iconUrl)

        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = channel.name,
                    color = Wa.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                // Verified checkmark badge for prominent channels
                if (channel.followerCount >= 10000 || channel.status == "active") {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Verified",
                        tint = Color(0xFF25D366),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            Text(
                text = followerText,
                color = Wa.TextDim,
                fontSize = 13.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(12.dp))

        WaFollowAction(
            following = isFollowing,
            busy = isBusy,
            onClick = onToggleFollow
        )
    }
}

/**
 * Reusable WhatsApp Search Illustration with Mint center, Emerald outer ring & angled handle.
 * Matches screenshot 2b312e24-f426-457e-abcd-bc21c23a162c.jpg.
 */
@Composable
internal fun WaSearchIllustration(
    text: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(68.dp)) {
                val strokeWidth = 5.dp.toPx()
                val radius = 22.dp.toPx()
                val center = Offset(size.width * 0.40f, size.height * 0.40f)

                // Soft mint fill
                drawCircle(
                    color = Color(0xFFD0F8CE),
                    radius = radius - strokeWidth / 2f,
                    center = center
                )

                // Emerald green ring
                drawCircle(
                    color = Color(0xFF25D366),
                    radius = radius,
                    center = center,
                    style = Stroke(width = strokeWidth)
                )

                // Handle pointing down-right
                val handleStart = Offset(
                    center.x + radius * 0.707f,
                    center.y + radius * 0.707f
                )
                val handleEnd = Offset(
                    handleStart.x + 16.dp.toPx(),
                    handleStart.y + 16.dp.toPx()
                )
                drawLine(
                    color = Color(0xFF25D366),
                    start = handleStart,
                    end = handleEnd,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = text,
            color = Wa.TextDim,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * WhatsApp Region Selection Modal Bottom Sheet.
 * Matches screenshot 06dd16f9-c400-4445-b03a-1afb42ab3b9c.jpg.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionSelectionSheet(
    selectedRegion: RegionItem,
    onSelectRegion: (RegionItem) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    val filteredRegions = remember(query) {
        if (query.isBlank()) {
            DEFAULT_REGIONS
        } else {
            DEFAULT_REGIONS.filter { it.name.contains(query, ignoreCase = true) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Wa.Canvas,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Wa.Divider, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Rounded dark search bar for region
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Wa.Bar)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = Wa.TextDim,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(Modifier.width(10.dp))

                Box(modifier = Modifier.weight(1f)) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Wa.Text,
                            fontSize = 15.5.sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.goodpost_search_region),
                            color = Wa.TextDim,
                            fontSize = 15.5.sp
                        )
                    }
                }

                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { query = "" },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear",
                            tint = Wa.TextDim,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                items(filteredRegions, key = { it.name }) { region ->
                    val isSelected = selectedRegion.name == region.name

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clickable {
                                coroutineScope.launch {
                                    sheetState.hide()
                                    onSelectRegion(region)
                                }
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // WhatsApp-style circular radio indicator on the left
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .border(
                                    width = 2.dp,
                                    color = if (isSelected) Wa.Accent else Wa.TextDim,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Wa.Accent, CircleShape)
                                )
                            }
                        }

                        Spacer(Modifier.width(18.dp))

                        Text(
                            text = region.name,
                            color = Wa.Text,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
