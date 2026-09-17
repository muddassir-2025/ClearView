package com.muddassir.clearview.goodpost

import com.muddassir.clearview.goodpost.data.GoodPostChannel
import com.muddassir.clearview.goodpost.data.GoodPostPost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search inside one channel (§9).
 *
 * The scope is the feature: a term is answered by the channel the reader is in,
 * and the state that answers it belongs to that screen and nothing else. Two
 * things are pinned — that opening a search starts clean, and that leaving one
 * takes its results with it.
 *
 * The second is the one that matters. A term, results and a cursor left behind
 * on the way out are exactly how the NEXT channel's search opens showing the
 * previous channel's matches under a word nobody typed, which reads as a search
 * answering with somebody else's channel.
 */
class GoodPostChannelSearchTest {

    private fun channel(id: String) = GoodPostChannel(
        id = id,
        slug = id,
        name = id.uppercase(),
        description = null,
        categorySlug = null,
        categoryLabel = null,
        countryCode = null,
        iconUrl = null,
        createdAt = "2026-08-01T10:00:00.000Z",
        lastPostAt = null,
        lastPostType = null,
        lastPostPreview = null,
        shareLink = "clearview://goodpost/channel/$id"
    )

    private fun post(id: String, channelId: String = "a") = GoodPostPost(
        id = id,
        channelId = channelId,
        type = "text",
        body = "a match",
        linkUrl = null,
        linkTitle = null,
        media = emptyList(),
        createdAt = "2026-08-01T10:00:00.000Z"
    )

    /** The screen a search is opened from: the channel's feed. */
    private fun channelScreen(id: String) = GoodPostUiState(
        backStack = listOf(GoodPostScreen.Home, GoodPostScreen.Channel(id)),
        channel = channel(id)
    )

    @Test
    fun `forgetting a search forgets all of it`() {
        // The whole of the rule in one place: not just the results, but the term
        // they answer, the cursor that would fetch more of them and the error
        // that described fetching them. Any one of these left behind shows a
        // screen that disagrees with itself.
        val searched = GoodPostUiState(
            channelSearchQuery = "zak",
            channelSearchTerm = "zak",
            channelSearchResults = listOf(post("p1"), post("p2")),
            channelSearchCursor = "next-page",
            channelSearchError = "unreachable"
        ).clearedOfChannelSearch()

        assertEquals(emptyList<GoodPostPost>(), searched.channelSearchResults)
        assertEquals("", searched.channelSearchTerm)
        assertNull(searched.channelSearchCursor)
        assertNull(searched.channelSearchError)
        assertFalse(searched.channelSearchLoading)
        assertFalse(searched.channelSearchLoadingMore)
    }

    @Test
    fun `the stack keeps the channel under the search, so Back returns to the feed`() {
        val viewModel = GoodPostViewModel()
        viewModel.open(GoodPostScreen.Channel("a"))
        viewModel.openChannelSearch("a")

        // Sits ON the channel rather than replacing it: popping the search has
        // somewhere to return to, which is what makes the feed come back as it
        // was instead of the reader being dropped on the channel list.
        assertEquals(
            listOf(
                GoodPostScreen.Home,
                GoodPostScreen.Channel("a"),
                GoodPostScreen.ChannelSearch("a")
            ),
            viewModel.uiState.backStack
        )

        viewModel.back()
        assertEquals(GoodPostScreen.Channel("a"), viewModel.uiState.screen)
    }

    @Test
    fun `the search screen is reached from the channel it searches`() {
        val viewModel = GoodPostViewModel()
        // From the home list, a search belongs to a channel — so the caller
        // names one, and the screen carries it. There is no way to open a search
        // without saying which channel it is about.
        viewModel.openChannelSearch("a")

        assertEquals(GoodPostScreen.ChannelSearch("a"), viewModel.uiState.screen)
        assertEquals("", viewModel.uiState.channelSearchQuery)
        assertEquals("", viewModel.uiState.channelSearchTerm)
    }

    @Test
    fun `backing out of a search empties the box as well as the results`() {
        val viewModel = GoodPostViewModel()
        viewModel.openChannelSearch("a")
        viewModel.onChannelSearchQueryChange("zak")

        assertTrue(viewModel.back())

        val state = viewModel.uiState
        assertEquals(GoodPostScreen.Home, state.screen)
        assertEquals("", state.channelSearchQuery)
        assertEquals("", state.channelSearchTerm)
        assertEquals(emptyList<GoodPostPost>(), state.channelSearchResults)
    }

    @Test
    fun `a second search never inherits the first one's term`() {
        val viewModel = GoodPostViewModel()
        viewModel.openChannelSearch("a")
        viewModel.onChannelSearchQueryChange("zak")

        // Opening from inside the first search, which is what happens when a
        // reader goes from one channel to another without closing it.
        viewModel.openChannelSearch("b")

        val state = viewModel.uiState
        assertEquals(GoodPostScreen.ChannelSearch("b"), state.screen)
        assertEquals("", state.channelSearchQuery)
        assertEquals("", state.channelSearchTerm)
    }
}
