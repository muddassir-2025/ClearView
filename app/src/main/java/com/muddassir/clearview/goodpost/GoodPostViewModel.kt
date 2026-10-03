package com.muddassir.clearview.goodpost

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muddassir.clearview.BuildConfig
import com.muddassir.clearview.goodpost.data.AD_DEFAULT_BACKGROUND_COLOR
import com.muddassir.clearview.goodpost.data.AD_DEFAULT_TEXT_COLOR
import com.muddassir.clearview.goodpost.data.AdDuration
import com.muddassir.clearview.goodpost.data.AdminSession
import com.muddassir.clearview.goodpost.data.ChannelSection
import com.muddassir.clearview.goodpost.data.ApiResult
import com.muddassir.clearview.goodpost.data.CachedChannels
import com.muddassir.clearview.goodpost.data.CachedPosts
import com.muddassir.clearview.goodpost.data.GoodPostAd
import com.muddassir.clearview.goodpost.data.GoodPostAdPlacement
import com.muddassir.clearview.goodpost.data.GoodPostAttachment
import com.muddassir.clearview.goodpost.data.GoodPostMedia
import com.muddassir.clearview.goodpost.data.GoodPostCategory
import com.muddassir.clearview.goodpost.data.GoodPostChannel
import com.muddassir.clearview.goodpost.data.GoodPostHidden
import com.muddassir.clearview.goodpost.data.GoodPostCodec
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostMediaItem
import com.muddassir.clearview.goodpost.data.GoodPostNotifications
import com.muddassir.clearview.goodpost.data.GoodPostPage
import com.muddassir.clearview.goodpost.data.GoodPostPost
import com.muddassir.clearview.goodpost.data.GoodPostReaction
import com.muddassir.clearview.goodpost.data.GoodPostPush
import com.muddassir.clearview.goodpost.data.GoodPostStarred
import com.muddassir.clearview.goodpost.data.GoodPostStarredEntry
import com.muddassir.clearview.goodpost.data.GoodPostUpdateScheduler
import com.muddassir.clearview.goodpost.data.GoodPostVideoCache
import com.muddassir.clearview.goodpost.data.GoodPostVideoPoster
import com.muddassir.clearview.goodpost.data.GoodPostViewed
import android.app.Activity
import com.muddassir.clearview.goodpost.data.CreatorSignIn
import com.muddassir.clearview.goodpost.data.GoodPostRepository
import com.muddassir.clearview.goodpost.data.GoogleSignIn
import com.muddassir.clearview.goodpost.data.GoodPostUploadState
import com.muddassir.clearview.goodpost.data.isoFromLocalStart
import com.muddassir.clearview.goodpost.data.nowMillis
import com.muddassir.clearview.goodpost.data.parseIsoMillis
import com.muddassir.clearview.goodpost.ui.waDayLabel
import com.muddassir.clearview.goodpost.ui.waSameDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * How many post ids this device remembers having had counted (§9).
 *
 * High enough that a reader paging through a channel's thirty days never sees a
 * post counted twice, low enough that the set cannot be the largest thing the
 * tab holds. The same ceiling is the store's own cap, so the memory mirror and
 * the file on disk evict together rather than drifting apart.
 */
private const val VIEW_REPORT_LIMIT = 500

/**
 * How many starred messages one pass looks up the picture for (§11).
 *
 * The list is drawn from the device, so opening it is instant; this is the part
 * that costs a request, and it is deliberately a few at a time rather than one per
 * row. A reader scrolling forty bookmarks fetches the ones they can see and the
 * ones just below, and the rest arrive as the list moves.
 */
private const val STARRED_MEDIA_PER_PASS = 6

/**
 * How long typing has to stop before a search is sent (§7).
 *
 * Short enough to feel like the list is following the reader and long enough that
 * a word typed at speed is one request rather than six. It is a pause and not a
 * threshold: no term is ever "too short to search for", because a channel called
 * `P` is a channel somebody is looking for.
 */
private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * How many channels a discovery section previews (§7).
 *
 * Five, which is enough to show the shape of a section and short enough that two
 * sections and their headers fit a phone without scrolling past the point of the
 * screen. "See all" is what the full list is for.
 */
private const val SECTION_PREVIEW = 5

/**
 * A page of the See-all list (§7).
 *
 * Larger than a section preview because this screen exists to be scrolled, and
 * smaller than the server's maximum because a phone does not need a hundred rows
 * in one reply — the cursor is what carries the rest.
 */
private const val SECTION_PAGE = 30

/**
 * Where the tab is.
 *
 * A stack rather than a set of booleans, because Good Post's screens nest —
 * channel → information → back to the channel — and a boolean per screen cannot
 * express "back to where I came from" without a second mechanism to keep them
 * consistent.
 *
 * There is no `SignedIn` state and no gate. A reader opens the tab and is on the
 * channel list, which is the whole of §1.
 */
sealed interface GoodPostScreen {
    data object Home : GoodPostScreen
    data object Explore : GoodPostScreen

    /**
     * The sectioned discovery screen (§7) — the Channels page behind Explore.
     *
     * A screen of its own rather than the top of [Explore]: Explore is a search
     * result, and a page whose job is to show what exists cannot also be the page
     * that shows what matched. The two answer different questions and page
     * differently, so they are two destinations.
     */
    data object Discover : GoodPostScreen

    /**
     * One section in full, behind its "See all" (§7).
     *
     * Carries the section so the screen can title itself and know which order to
     * open in; the order the reader may then switch to lives in the state.
     */
    data class Section(val section: ChannelSection) : GoodPostScreen

    /** A channel's feed (§9). */
    data class Channel(val channelId: String) : GoodPostScreen

    /** A channel's information page (§11–§14). */
    data class ChannelInfo(val channelId: String) : GoodPostScreen

    /**
     * Search inside ONE channel (§9).
     *
     * A screen of its own rather than a mode of the feed, because it carries
     * state the feed must not: a term, the results for that term, and the note
     * that the results are not the channel's history. It sits on the stack, so
     * Back returns to the channel exactly where it was.
     */
    data class ChannelSearch(val channelId: String) : GoodPostScreen

    /**
     * Everything a channel has posted as media (§13).
     *
     * A screen rather than an expansion of the strip on the information page:
     * the strip is a preview, and the things a reader does to a collection —
     * select several, delete them, walk back through a channel's history — need
     * the whole screen and a selection bar.
     */
    data class ChannelMedia(val channelId: String) : GoodPostScreen

    /**
     * Every message this reader starred, for one channel (§9, §11).
     *
     * The information page shows how many there are and nothing else: it is a
     * channel's public face, and a list of one reader's private bookmarks sitting
     * open on it was both the wrong place and the wrong size — a hundred stars
     * turned the page into a scroll. This screen is where they are read, which is
     * also what makes searching them possible.
     */
    data class Starred(val channelId: String) : GoodPostScreen

    /** The administrator way in (§16). */
    data object AdminLogin : GoodPostScreen


    /**
     * Advertisement cards, as their manager sees them (§15, §16).
     *
     * A destination rather than an overlay: it is a list an administrator goes to,
     * works through and leaves, and editing one card is a step over THIS screen —
     * the same shape the channel list has with its channel form.
     */
    data object Ads : GoodPostScreen

    /**
     * One channel, as its administrator sees it (§19, §21).
     *
     * There is no separate administrator home screen any more. An account's
     * channels ARE the tab — the list is built from what the account may publish
     * to — so a second list of the same channels would be a dashboard beside the
     * product rather than part of it (§5).
     */
    data class AdminChannel(val channelId: String) : GoodPostScreen
}

/**
 * Everything the Good Post tab renders.
 *
 * One object rather than a StateFlow per concern, matching the rest of the app:
 * the screens here are a handful of lists and one form, and a single immutable
 * state is what makes "render the cache, then replace it with the network"
 * expressible as one transition rather than two that can disagree.
 */
data class GoodPostUiState(
    /** False when this build has no backend URL, which is a stated state (§41). */
    val configured: Boolean = true,
    val backStack: List<GoodPostScreen> = listOf(GoodPostScreen.Home),

    // ── The channel list (§3, §4) ────────────────────────────────────────
    val channels: List<GoodPostChannel> = emptyList(),
    val channelsLoading: Boolean = true,
    /** True while what is on screen came from the cache (§27). */
    val channelsStale: Boolean = false,
    val channelsError: String? = null,
    /**
     * The channels this reader follows, by id (§4).
     *
     * Held as ids as well as as rows because the two screens ask different
     * questions of it: the home list renders the rows, and Explore asks "is this
     * one of mine?" for a channel that came from the public list and carries no
     * state of its own. A set answers that in constant time per row, which is
     * what keeps a list of two hundred search results from being a scan of the
     * reader's follows for each one.
     */
    val followedIds: Set<String> = emptySet(),
    /** A follow or unfollow this view model is waiting on, for the row's state. */
    val followBusyId: String? = null,

    /**
     * The rows currently selected, by long press (§5).
     *
     * Non-empty means selection mode: the bar above the list becomes an action
     * bar, a tap toggles instead of opening, and the actions offered are the ones
     * that apply to what is selected. One set per list rather than one shared
     * set, because the two lists can be on screen in different states.
     */
    val selectedChannelIds: Set<String> = emptySet(),

    // ── A channel's feed (§8, §9, §10) ───────────────────────────────────
    val channel: GoodPostChannel? = null,
    val posts: List<GoodPostPost> = emptyList(),
    val postsLoading: Boolean = false,
    val postsStale: Boolean = false,
    val postsCursor: String? = null,
    val postsError: String? = null,
    val postsLoadingMore: Boolean = false,

    /** Posts selected by long press in a channel's feed (§5). */
    val selectedPostIds: Set<String> = emptySet(),

    /**
     * True while a bulk action on a selection is on its way to the server (§5).
     *
     * The selection bar draws a spinner and withholds its actions while this is
     * set, which is the difference between "the tap landed and something is
     * happening" and a bar that looks frozen: a delete of several posts is a
     * round trip, and during it the bar is exactly where the reader's thumb is.
     * It also makes a second tap on Delete impossible, so one selection cannot
     * send two bulk requests.
     */
    val selectionBusy: Boolean = false,

    // ── Search inside one channel (§9) ───────────────────────────────────
    /** What is in the search box. */
    val channelSearchQuery: String = "",
    /**
     * The term the results on screen are actually FOR.
     *
     * Kept apart from [channelSearchQuery] because the two disagree for as long
     * as a search is in flight — and the empty state has to describe the term it
     * searched rather than the text somebody is halfway through typing.
     */
    val channelSearchTerm: String = "",
    val channelSearchResults: List<GoodPostPost> = emptyList(),
    val channelSearchLoading: Boolean = false,
    val channelSearchLoadingMore: Boolean = false,
    val channelSearchCursor: String? = null,
    val channelSearchError: String? = null,

    // ── The information page (§11–§14) ───────────────────────────────────
    val media: List<GoodPostMediaItem> = emptyList(),
    val mediaLoading: Boolean = false,
    val mediaCursor: String? = null,
    val mediaLoadingMore: Boolean = false,
    val mediaError: String? = null,
    /**
     * The media rows picked in the gallery (§13).
     *
     * Kept apart from [selectedPostIds] because a selection is only meaningful
     * against the list it was made in: the two screens can both be in the back
     * stack, and one set would let a selection made in the feed arm the
     * gallery's Delete.
     */
    val selectedMediaIds: Set<String> = emptySet(),
    val descriptionExpanded: Boolean = false,

    /**
     * The posts this DEVICE has starred (§9).
     *
     * Ids, for the same reason the followed set is ids: the star is drawn on
     * rows that come from anywhere — the feed, a search result, the media
     * viewer — and each of those has to answer "is this mine?" without a scan.
     * The rows themselves are in [starred].
     */
    val starredPostIds: Set<String> = emptySet(),
    /** The starred rows belonging to the channel whose information page is open. */
    val starred: List<GoodPostStarredEntry> = emptyList(),
    /**
     * The picture behind a starred message, keyed by post id (§11).
     *
     * Fetched, not stored. A star is written down on the device and outlives the
     * post it points at — the server keeps a post's assets for a bounded window,
     * and a signed URL expires in an hour — so the one thing a bookmark could not
     * usefully keep is a URL. What it keeps is the post id, and this is where a
     * fresh URL for it lands while the list is on screen.
     *
     * A post that is gone, or one whose asset could not be signed, simply has no
     * entry: the row then shows the label it always did.
     */
    val starredMedia: Map<String, GoodPostMedia> = emptyMap(),

    /**
     * The emoji this deployment offers (§9).
     *
     * Empty until fetched, and the picker draws nothing while it is: the app
     * deliberately does not carry its own copy of the list, because a seventh
     * emoji added on the server must not need an app release to appear — and an
     * app that guessed would eventually offer one the server refuses.
     */
    val reactionEmoji: List<String> = emptyList(),

    /**
     * The post a reaction has been sent for and not yet answered (§9).
     *
     * A SET of ids rather than one, because reacting is also a selection action: a
     * reader can pick several updates and put the same emoji on all of them, and
     * those sends are genuinely in flight together. Two reactions to the SAME post
     * are still impossible, which is the property that mattered — the answer
     * carries a count, and applying the second answer of one post twice would move
     * its chip twice. The id is what keeps that from happening, and a set keeps it
     * without also forbidding the selection.
     */
    val reactionBusyIds: Set<String> = emptySet(),

    /**
     * Whether this device wants a notification when a followed channel posts (§8).
     *
     * Off until asked for, and stored on the device rather than on the account:
     * a reader who has never chosen should not be buzzed, and a permission prompt
     * on first launch is how that choice gets made for them.
     */
    val notificationsEnabled: Boolean = false,

    /**
     * The followed channels whose notifications are muted, by id (§6).
     *
     * The server's own `notifications_muted` on the follow row, mirrored here so
     * the switch on a channel's information page draws in the right state without
     * a request of its own.
     */
    val mutedChannelIds: Set<String> = emptySet(),

    // ── Explore (§7) ─────────────────────────────────────────────────────
    val query: String = "",
    val categories: List<GoodPostCategory> = emptyList(),
    val explore: List<GoodPostChannel> = emptyList(),
    val exploreLoading: Boolean = false,
    val exploreCursor: String? = null,
    val exploreError: String? = null,

    // ── Discover (§7) ────────────────────────────────────────────────────
    //
    // The Channels screen's own state, held apart from [explore] rather than
    // shared with it. The two screens ask the server the same question with
    // different orders, and one list field would mean opening a section wiped the
    // other screen's rows — or, worse, left a stale page of them under a new
    // heading. They are also paged independently, so a "load more" in a section
    // must not append to the search results underneath it.

    /**
     * Every section's preview, keyed by the section itself.
     *
     * A map rather than one field per section: the sections are data now (see
     * [ChannelSection]), and a field per category would mean every new category
     * is a state field, a branch in the loader and a branch in the screen. A
     * section with no entry, or an empty list, is simply not drawn.
     */
    val discoverSections: Map<ChannelSection, List<GoodPostChannel>> = emptyMap(),
    val discoverLoading: Boolean = false,
    val discoverError: String? = null,

    /**
     * The full list behind a section's "See all", or null when none is open.
     *
     * Non-null is what makes the See-all screen exist: it carries the section's
     * own order, which is also what the app bar is titled with.
     */
    val section: ChannelSection? = null,
    /**
     * The order the See-all list is currently in.
     *
     * Separate from the section it was opened from, because the chips re-sort the
     * list in place: the section says which heading the reader arrived under, and
     * this says how they have since chosen to read it.
     */
    val sectionSort: String = "active",
    val sectionItems: List<GoodPostChannel> = emptyList(),
    val sectionLoading: Boolean = false,
    val sectionCursor: String? = null,
    val sectionError: String? = null,

    /**
     * A section's search term, kept per section so switching chips does not lose
     * the term the reader typed.
     */
    val sectionQuery: String = "",

    // ── Advertisements a reader sees (§12) ───────────────────────────────
    //
    // Two lists rather than one, because the two surfaces ask the server for
    // different placements and a card can be placed on either, both or neither.
    // Held apart so a card taken off Channels cannot linger above Explore until
    // the next reload, and so a failure on one screen cannot blank the other.
    //
    // Nothing is cached and nothing is reported as an error: an advertisement is
    // supplementary, and a screen with no card is still a working screen.

    /** Active cards for the top of the Channels list (§12). */
    val ads: List<GoodPostAd> = emptyList(),
    /** Active cards for the top of Explore (§12). */
    val exploreAds: List<GoodPostAd> = emptyList(),

    // ── Administrator (§16, §19, §21) ────────────────────────────────────
    val admin: AdminSession? = null,
    val adminEmail: String = "",
    val adminPassword: String = "",
    val adminBusy: Boolean = false,

    /**
     * A creator has signed in but has no channel yet (§16).
     *
     * A step rather than a failure: it is the state every creator is in exactly
     * once, and the screen answers it with the one field that finishes the job
     * instead of an error. Nothing is stored on a session-less sign-in, so
     * abandoning this step leaves no trace on the server.
     */
    val creatorNeedsChannel: Boolean = false,
    /** The address the pending creator account is under, shown on that step. */
    val creatorEmail: String? = null,
    /** The channel name being typed on that step. */
    val creatorChannelName: String = "",

    /**
     * True from the moment "Continue with Google" is tapped until the session
     * comes back, or the attempt is over (§16).
     *
     * Separate from [adminBusy] because it is not the same wait. [adminBusy] is
     * every action that must not be tapped twice — a sign-in, a save, a post
     * being published — while this one is specifically the exchange that leaves
     * the app entirely: the account picker, then Google, then Firebase, then the
     * backend. Nothing on the screen can advance it, so the screen says so
     * instead of leaving a greyed-out card looking like a refusal.
     */
    val creatorGoogleBusy: Boolean = false,

    /**
     * The address of a creator who signed in to an account that already existed
     * (§16), so the screen can say welcome back rather than nothing.
     *
     * Null in every other case: a new creator is told nothing because there is
     * nothing to tell, and a super administrator signing in with a password is
     * not a returning CREATOR.
     */
    val welcomeEmail: String? = null,

    /**
     * Whether the "Other ways" panel on the sign-in screen is open (§16).
     *
     * Closed until it is asked for. It holds the two fields a deployment-
     * provisioned administrator types into, and putting them on screen for
     * everyone would make the sign-in page look like a password form to a creator
     * who only ever needs the Google card above it.
     *
     * Screen state rather than [creatorNeedsChannel]-style flow state: it changes
     * nothing about what the app will do, only what is visible, so nothing reads
     * it but the screen.
     */
    val otherWaysOpen: Boolean = false,
    /**
     * The channels this account may work with: all of them for a super
     * administrator, exactly one for a channel administrator.
     *
     * Decided by the server, and it IS the tab's list when signed in — see
     * [tabChannels].
     */
    val adminChannels: List<GoodPostChannel> = emptyList(),
    val adminChannelsLoading: Boolean = false,

    // ── Advertisements, administrator (§15, §16) ────────────────────────
    //
    // The list is every card, active or not, so the manager can see and fix the
    // ones that are currently doing nothing — which is the whole reason the admin
    // read exists beside the public one.

    /** Every card, enabled or not, for the management list (§15). */
    val adminAds: List<GoodPostAd> = emptyList(),
    val adminAdsLoading: Boolean = false,
    /**
     * True from the moment a card is drawn over the list until it is closed (§16).
     *
     * An overlay rather than a destination, matching the channel form: editing one
     * card is a step over the manager list, not a place in the back stack.
     */
    val adFormOpen: Boolean = false,
    /**
     * The card being edited, or null while a new one is being written.
     *
     * Null is the CREATE case and not "no form": [adFormOpen] is what says a form
     * is on screen, so a new card and a failed load of an existing one cannot be
     * confused with each other.
     */
    val adFormId: String? = null,
    /** `text` or `image`. */
    val adFormContentType: String = "text",
    val adFormText: String = "",
    val adFormTargetUrl: String = "",
    val adFormShowInChannels: Boolean = true,
    val adFormShowInExplore: Boolean = true,
    val adFormEnabled: Boolean = true,
    /** Kept as text so a half-typed number is a field not yet finished. */
    val adFormPriority: String = "100",
    /**
     * When the card starts, as ISO-8601.
     *
     * A timestamp rather than a typed date: the editor offers a duration ("for
     * a week") or a calendar picker, and this is what either resolves to before
     * it is sent. Null means "now", which is what the server does with an
     * omitted start.
     */
    val adFormStartsAt: String? = null,
    /** When the card stops, as ISO-8601, or null for "no expiry". */
    val adFormExpiresAt: String? = null,
    /**
     * The duration the editor is showing, or [AdDuration.Custom] once a specific
     * date has been chosen.
     *
     * The form's own state rather than something derived from the two timestamps:
     * a reader who picks "a week" and then opens the calendar to adjust the end
     * date is in the custom case, and deriving it would flip the pill back to
     * "a week" and lose what they did.
     */
    val adFormDuration: AdDuration = AdDuration.NoExpiry,
    /**
     * The picture being uploaded for this card.
     *
     * The same attachment shape the composer and the channel form use, so the
     * upload handshake is the existing one — request, PUT, confirm — and no third
     * way to move bytes into the product exists.
     */
    val adFormImage: GoodPostAttachment? = null,
    /** The image an existing card already has, for the form's preview. */
    val adFormExistingImageUrl: String? = null,
    /** A text card's ink (§12). */
    val adFormTextColor: String = AD_DEFAULT_TEXT_COLOR,
    /** The card's own surface (§12). */
    val adFormBackgroundColor: String = AD_DEFAULT_BACKGROUND_COLOR,
    /** An image card's fit: `cover` fills the card, `contain` shows it whole. */
    val adFormImageFit: String = "cover",
    /**
     * Where the crop is centred, 0..1 on each axis.
     *
     * Held on the form rather than baked into the upload: the picture is stored
     * once, and the rectangle the reader sees is a property of the CARD — so an
     * administrator can change their mind about the framing without uploading the
     * same photograph again.
     */
    val adFormFocusX: Float = 0.5f,
    val adFormFocusY: Float = 0.5f,

    /** A message code from the last failed action, worded by [goodPostErrorFor]. */
    val messageCode: String? = null,

    // ── The composer (§21) ───────────────────────────────────────────────
    val composerOpen: Boolean = false,
    val composerBody: String = "",
    val composerBusy: Boolean = false,
    /** Set while editing an existing post rather than creating one. */
    val editingPostId: String? = null,
    /**
     * The channel the post being edited belongs to.
     *
     * Editing state is per-channel, and this is what makes that true rather than
     * hopeful. The composer is one field on one screen, but the screen it belongs
     * to can change underneath it — and a bare [editingPostId] then describes a
     * post in a channel the reader is no longer looking at. The feed asks
     * [isEditingIn] before it draws the editing strip, so a stale id cannot leak
     * across a channel switch even if a reset was missed.
     */
    val editingPostChannelId: String? = null,
    /**
     * Files attached to the post being written, in the order they were picked.
     *
     * Held here rather than in the screen because it is a VALUE — the publish
     * call attaches exactly the ids in this list, and the upload state of each
     * one decides whether publishing is allowed at all.
     */
    val composerAttachments: List<GoodPostAttachment> = emptyList(),
    /**
     * Whether this deployment can store media at all (§22).
     *
     * Optimistic until the server says otherwise: storage is optional, so the
     * composer offers the picker and words a `media_unavailable` refusal honestly
     * rather than pre-emptively hiding a feature that usually works. Set to
     * false only by an answer that actually says so.
     */
    val composerMediaAvailable: Boolean = true,

    // ── The channel form (§19, §20) ──────────────────────────────────────
    val channelFormOpen: Boolean = false,
    val channelFormId: String? = null,
    val channelFormName: String = "",
    val channelFormDescription: String = "",
    val channelFormCategory: String? = null,
    /**
     * The platform's own badge (§7).
     *
     * Only a super administrator may set it, and the server drops the field for
     * anyone else — so a channel administrator's form carries the value it was
     * opened with and the save leaves it alone.
     */
    val channelFormVerified: Boolean = false,
    /** Set only when a super admin is creating a channel plus its admin (§20). */
    val channelFormAdminEmail: String? = null,
    val channelFormAdminPassword: String? = null,
    /**
     * The image being uploaded for this channel, or the one already on it.
     *
     * Two different things in one field, distinguished by state: an `Uploading`
     * or `Ready` attachment is a NEW image the form is about to save, while
     * [channelFormExistingIconUrl] is what the channel has now. Keeping them
     * apart is what makes "no change" distinguishable from "remove" — the
     * difference the PATCH endpoint's three-way `iconMediaId` encodes.
     */
    val channelFormIcon: GoodPostAttachment? = null,
    val channelFormExistingIconUrl: String? = null,
    /** True when the administrator has asked to remove the existing image. */
    val channelFormIconRemoved: Boolean = false
) {
    /** The screen on top of the stack. */
    val screen: GoodPostScreen get() = backStack.last()

    val isAdmin: Boolean get() = admin != null

    /**
     * The channels the tab lists (§1, §3, §4).
     *
     * A reader sees the channels THEY FOLLOW — what `refreshChannels` put in
     * [channels], which is their own list and not the catalogue. The catalogue
     * is Explore's job, one tap away; a tab that listed every channel would make
     * following pointless, because there would be no visible difference between
     * a channel somebody chose and one they have never seen.
     *
     * The ONE case where [channels] holds the catalogue for a reader is a build
     * with no reader identity at all (`GoodPostRepository.identifiesReaders`),
     * where there is no list of follows to fetch and never will be.
     *
     * A signed-in account sees the channels it has access to instead — the whole
     * product for a super administrator, exactly one channel for an account
     * created for one. That replacement is the point: an account made for a
     * channel must not be handed the rest of Good Post, and its list therefore
     * carries only the controls it is entitled to.
     */
    val tabChannels: List<GoodPostChannel>
        get() = if (admin != null) {
            // Signed in, the tab is the reader's channels AND the one(s) they run.
            //
            // It used to be only the latter, and that was wrong in a way only a
            // creator notices: following a channel from Explore while signed in
            // put it nowhere at all — the follow succeeded, the tab was showing
            // the account's channels, and the channel the reader had just chosen
            // was invisible. Running a channel does not stop you being a reader;
            // WhatsApp does not hide your follows when you own one either. The
            // account's own channels come first because those are the rows with
            // something to do on them, and the followed ones follow, deduped by
            // id so a channel that is both is listed once.
            val own = adminChannels
            val ownIds = own.mapTo(mutableSetOf()) { it.id }
            own + channels.filterNot { it.id in ownIds }
        } else {
            channels
        }

    /**
     * Whether the account may create a channel, and therefore whether the tab
     * offers the way in (§15, §17).
     *
     * True for a reader — the way in is the administrator sign-in — and for a
     * super administrator, who is the only role that can create one. A channel
     * administrator holds no `channels.create`, so offering it would be offering
     * a button that always fails.
     */
    val canCreateChannel: Boolean get() = admin == null || admin.isSuperAdmin

    /**
     * The one channel this account runs, when it runs exactly one.
     *
     * What the tab's menu offers as "My Channel" (§15). `singleOrNull` rather
     * than `firstOrNull` on purpose: a super administrator who runs five channels
     * has no "my channel" for a menu item to open — the tab already lists every
     * one of them — and a label that silently picked the first would be a
     * different channel on a different day.
     */
    val ownChannel: GoodPostChannel?
        get() = if (admin == null) null else adminChannels.singleOrNull()

    /**
     * True while the tab's list is still on its way.
     *
     * Which list that is depends on who is asking, and the two are fetched
     * separately — so a screen that watched only `channelsLoading` showed the
     * empty state for the moment between the public read finishing and the
     * account's own list arriving. That reads as "you have no channels", which
     * is a statement about the account rather than a loading state, and a false
     * one.
     */
    val tabLoading: Boolean
        get() = if (admin != null) adminChannelsLoading else channelsLoading

    /** True while a selection is active in the channel list (§5). */
    val channelSelectionActive: Boolean get() = selectedChannelIds.isNotEmpty()

    /** True while a selection is active in a feed (§5). */
    val postSelectionActive: Boolean get() = selectedPostIds.isNotEmpty()

    /** Whether a channel may be edited or deleted by this account. */
    fun canManage(channelId: String): Boolean =
        admin?.let { session -> session.isSuperAdmin || session.channelId == channelId } == true

    /**
     * This state with a channel search's term and results discarded (§9).
     *
     * The rule lives on the state rather than inside the ViewModel because it is
     * a fact about the fields — which of them belong to a search — and because
     * three transitions need it: closing the screen, backing out of it, and
     * clearing the box. A half-cleared search is how results for one term end up
     * under the label of another, so there is one definition of "forget it all"
     * and every exit goes through it.
     */
    internal fun clearedOfChannelSearch(): GoodPostUiState = copy(
        // The box goes too. It is on the screen that is being left, so nothing
        // can read it afterwards — which is exactly why leaving it set is a
        // latent leak rather than a visible one, and why it is cleared here by
        // the same call that clears everything else.
        channelSearchQuery = "",
        channelSearchResults = emptyList(),
        channelSearchCursor = null,
        channelSearchLoading = false,
        channelSearchLoadingMore = false,
        channelSearchError = null,
        channelSearchTerm = ""
    )

    /**
     * Whether the composer is editing a post that belongs to [channelId].
     *
     * Both halves are required. A non-null [editingPostId] alone only says that
     * SOME post is being edited; it does not say whose, and the feed of another
     * channel reading only that is exactly how an editing strip appeared over a
     * channel the administrator had never edited anything in.
     */
    fun isEditingIn(channelId: String): Boolean =
        editingPostId != null && editingPostChannelId == channelId
}

/**
 * Good Post's one ViewModel.
 *
 * It owns navigation, the cached-then-live read pattern, and the administrator's
 * actions. Two rules it exists to keep:
 *
 *  * **Nothing here is per-account, because a reader has no account.** The only
 *    credential in the app is an administrator's, and every screen a reader sees
 *    works with none. What a reader does have is an anonymous identity (§3),
 *    which is what their followed channels hang from and nothing else.
 *
 *  * **Nothing blocks the first frame.** [initialize] renders the cache and then
 *    refreshes behind it, which is what makes the tab feel like it opens
 *    instantly (§26).
 */
class GoodPostViewModel : ViewModel() {

    private var repository: GoodPostRepository? = null

    /**
     * The stars on this device (§9), and the context the notification switch
     * lives in. Both are per-process state rather than per-screen, because both
     * outlive any one screen — a star made in a feed is shown on a channel's
     * information page days later.
     */
    private var starredStore: GoodPostStarred? = null

    /**
     * The updates this reader deleted FOR THEMSELVES (§5, §9).
     *
     * "Delete for me" on a post has no server half — a reader has no account and
     * a post is one row everybody reads — so its honest meaning is the one
     * WhatsApp gives it: it goes out of MY view, which is a fact about this
     * device. It is read when a list is APPLIED rather than when it is drawn, so
     * a hidden post cannot come back through the feed cache on the next cold
     * start, and it is read for media too: an item whose post is hidden is not in
     * the gallery either.
     */
    private var hiddenStore: GoodPostHidden? = null

    /**
     * The posts this device has already had counted (§9).
     *
     * Kept on disk rather than in memory for the reason it exists at all: a set
     * that starts empty on every cold start makes the app re-report every post it
     * opens, so a channel's view counts climb just because the reader restarted
     * their phone.
     */
    private var viewedStore: GoodPostViewed? = null
    private var appContext: Context? = null

    /**
     * A channel slug from a share link, held until the repository exists.
     *
     * The link can arrive before [initialize] has run — MainActivity hands it to
     * the tab on the first composition — so it is kept here rather than dropped,
     * which is what lets a shared link open its channel instead of the list.
     */
    private var pendingSlug: String? = null

    /**
     * The Firebase ID token of a creator who has signed in but has no channel
     * yet (§16). In memory only: see [creatorCreateChannel].
     */
    private var creatorIdToken: String? = null

    var uiState by mutableStateOf(GoodPostUiState())
        private set

    /** Idempotent, so a rotation does not refetch everything. */
    fun initialize(context: Context) {
        if (repository != null) return
        val repo = GoodPostRepository(context.applicationContext)
        repository = repo
        appContext = context.applicationContext
        val stars = GoodPostStarred(context.applicationContext)
        starredStore = stars
        hiddenStore = GoodPostHidden(context.applicationContext)
        // §9: what this device has already had counted. Primed before any read
        // goes out, so the first page of a cold start is not reported again.
        viewedStore = GoodPostViewed(context.applicationContext).also { store ->
            reportedViews.addAll(store.ids())
        }

        val cached = repo.cachedChannels()
        if (cached != null) showCachedChannels(cached)

        uiState = uiState.copy(
            configured = repo.isConfigured,
            categories = repo.cachedCategories(),
            admin = repo.adminSession(),
            // Both of these are the DEVICE's, read before any request goes out:
            // a reader who asked for notifications keeps them across a cold start
            // even with no network, and their stars are visible in the same tab
            // without one.
            notificationsEnabled = GoodPostNotifications.isEnabled(context),
            starredPostIds = stars.all().map { it.postId }.toSet()
        )

        refreshChannels()
        loadAds()
        // Always re-read, not only when the cache is empty.
        //
        // Categories are a list the SERVER owns and an operator changes by adding
        // one, and the cache is there so the form opens with something in it —
        // not to freeze the list at whatever it was the first time this install
        // ran. Fetching only when empty meant a category added later never
        // appeared on the channel form at all, which is how a correct server
        // response looked like a stale app.
        loadCategories()

        // The background check is enqueued unconditionally and reads the switch
        // itself, so a reader who turned notifications on in a previous install
        // keeps them without the tab having to be opened, and one who never did
        // pays for a worker that returns immediately.
        GoodPostUpdateScheduler.ensureScheduled(context)

        // …and the push registration is made to agree with the switch, once per
        // app start. A token can rotate while the app is closed and the server has
        // no way to notice, so a device that only ever registered once would stop
        // receiving silently — which is the failure nobody can see and nobody
        // reports.
        viewModelScope.launch { GoodPostPush.sync(context) }

        // A stored session decides what the tab lists, so its channels are
        // fetched with everything else. Without this the list came up empty with
        // "No channels. Create one to start publishing." — a statement about the
        // account rather than a loading state, and a false one.
        if (uiState.admin != null) loadAdminChannels()

        pendingSlug?.let { slug ->
            pendingSlug = null
            requestOpenChannel(slug)
        }
    }

    /**
     * Open the channel a share link names (§6).
     *
     * The slug is resolved through the same public read a tapped row uses, so a
     * link and a tap cannot produce different screens. A dead link reports
     * not-found rather than doing nothing, because a tap that appears to be
     * ignored is worse than an honest refusal.
     */
    fun requestOpenChannel(slug: String) {
        val repo = repository
        if (repo == null) {
            pendingSlug = slug
            return
        }

        viewModelScope.launch {
            when (val result = repo.channel(slug)) {
                is ApiResult.Ok -> openChannel(result.value.id)
                else -> uiState = uiState.copy(messageCode = "not_found")
            }
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────

    fun open(screen: GoodPostScreen) {
        uiState = uiState.copy(backStack = uiState.backStack + screen, messageCode = null)
    }

    /** Pop one screen, or report that there was nothing to pop. */
    fun back(): Boolean {
        val stack = uiState.backStack
        if (stack.size <= 1) return false

        // Leaving a search takes its state with it. Otherwise the next channel
        // searched would open with the previous one's term in the box — which is
        // both wrong and confusing: the results below it belong to a different
        // channel, and a reader who does not look at the field would read them
        // as this channel's.
        val leaving = stack.last()
        val popped = uiState.copy(backStack = stack.dropLast(1), messageCode = null)
        uiState = if (leaving is GoodPostScreen.ChannelSearch) {
            popped.clearedOfChannelSearch()
        } else {
            popped
        }
        return true
    }

    fun goHome() {
        uiState = uiState.copy(backStack = listOf(GoodPostScreen.Home), messageCode = null)
    }

    // ── The channel list (§3, §4) ────────────────────────────────────────

    private fun showCachedChannels(cached: CachedChannels) {
        uiState = uiState.copy(
            channels = cached.channels,
            channelsLoading = false,
            channelsStale = true
        )
    }

    /**
     * Refresh the home list, which is ONLY what this reader follows (§4).
     *
     * An answer of "no follows" is a real answer and it is shown as one: the tab
     * is the reader's own list, and filling it with the catalogue turned it into
     * a second Explore — where the entire list was channels they had chosen not
     * to follow. What is left for a reader with nothing on it is the way to find
     * some, which the empty state says out loud.
     *
     * The ONE case that still falls back to the catalogue is not being able to ask
     * the question at all: no identity, or a deployment that cannot verify one.
     * There is no list of follows in that state and never will be, so an empty tab
     * would be permanent and would be describing a build, not a reader (§23).
     */
    fun refreshChannels() {
        val repo = repository ?: return
        uiState = uiState.copy(channelsLoading = true, channelsError = null)

        viewModelScope.launch {
            when (val follows = repo.following()) {
                is ApiResult.Ok -> {
                    uiState = uiState.copy(
                        channels = follows.value.items,
                        followedIds = follows.value.items.map { it.id }.toSet(),
                        // The channel switches on the information page draw from
                        // the server's own answer, so they are right on a device
                        // that has never touched them.
                        mutedChannelIds = follows.value.items
                            .filter { it.notificationsMuted }
                            .map { it.id }
                            .toSet(),
                        channelsLoading = false,
                        channelsStale = false,
                        channelsError = null
                    )
                }

                // Nothing is known about what this reader follows, so nothing
                // is assumed. Two different situations arrive here and they are
                // answered differently.
                is ApiResult.Failed, ApiResult.Unreachable -> {
                    if (repo.identifiesReaders) {
                        // There IS a reader identity; the answer about what they
                        // follow simply did not arrive. The tab keeps what it
                        // already knows — the cache is written by this same call
                        // — and reports the failure, rather than filling itself
                        // with the catalogue. Listing every channel on the
                        // platform under the heading "Channels" is the one thing
                        // this list must never do: it is the reader's own list.
                        uiState = uiState.copy(
                            channelsLoading = false,
                            channelsStale = uiState.channels.isNotEmpty(),
                            channelsError = if (uiState.channels.isEmpty()) {
                                if (follows is ApiResult.Failed) follows.code else "unreachable"
                            } else {
                                null
                            }
                        )
                    } else {
                        // No identity in this build at all: there is no list of
                        // follows to have and never will be, so an empty tab
                        // would be permanent and would be describing a build
                        // rather than a reader (§23).
                        uiState = uiState.copy(followedIds = emptySet())
                        loadPublicChannels(repo)
                    }
                }
            }
        }
    }

    /**
     * Bring the tab's list up to date, if it is worth asking (§24).
     *
     * Called on a slow timer while the Home screen is on top and the app is in
     * front. Deliberately the CHEAPEST possible poll rather than a realtime
     * channel: the reader's own follows are one indexed query that returns a
     * handful of rows, and there is no socket to hold open, no connection to
     * re-establish after a tunnel change, and nothing running while the screen is
     * not being looked at.
     *
     * Four conditions, each of which would make the request pure waste:
     *
     *  * **No repository** — a build with no backend has nothing to ask.
     *  * **A request already in flight** — the answer on its way IS the update.
     *  * **Signed in** — the tab is then the administrator's channel list, so the
     *    list to refresh is that one and the reader's follows are irrelevant.
     *  * **No reader identity** — follows cannot exist, so the tab is showing the
     *    catalogue fallback and polling it would be polling somebody else's list.
     */
    /**
     * Re-read whichever channel list this account is looking at.
     *
     * One function because there are two lists in one tab — a reader's follows
     * and an administrator's channels — and a write has to update the one that is
     * actually on screen. Called after a publish, an edit and a delete, because
     * each of them changes the row the list draws: its preview, its time, or its
     * existence.
     */
    private fun refreshLists() {
        if (uiState.isAdmin) loadAdminChannels() else refreshChannels()
    }

    fun refreshIfIdle() {
        val repo = repository ?: return
        if (uiState.channelsLoading || uiState.adminChannelsLoading) return

        if (uiState.isAdmin) {
            loadAdminChannels()
            return
        }

        if (!repo.identifiesReaders) return
        refreshChannels()
    }

    /**
     * Follow or unfollow a channel (§4).
     *
     * Optimistic, because the control is a toggle and waiting on a round trip
     * before it moves reads as a broken button. The state is put back if the
     * server disagrees, and the home list is reloaded on success so the channel
     * appears — or disappears — with the ordering and unread count the server
     * decided rather than the ones this could have guessed.
     */
    fun toggleFollow(channelId: String) {
        val repo = repository ?: return
        if (uiState.followBusyId != null) return

        val wasFollowing = channelId in uiState.followedIds
        uiState = uiState.copy(
            followBusyId = channelId,
            followedIds = if (wasFollowing) uiState.followedIds - channelId
            else uiState.followedIds + channelId
        )

        viewModelScope.launch {
            val result = if (wasFollowing) repo.unfollow(channelId) else repo.follow(channelId)

            when (result) {
                is ApiResult.Ok -> {
                    // The server's answer is the truth, including the case where
                    // the unfollow was of a channel that was not followed.
                    val nowFollowing = result.value.following
                    uiState = uiState.copy(
                        followBusyId = null,
                        followedIds = if (nowFollowing) uiState.followedIds + channelId
                        else uiState.followedIds - channelId
                    )
                    // The follower COUNT is the server's, and it has just
                    // changed. Reloaded only when the page showing it is the one
                    // that was acted on, so following from Explore does not
                    // re-fetch a channel nobody is looking at.
                    if (uiState.channel?.id == channelId) loadChannel(channelId)
                    refreshChannels()
                }

                is ApiResult.Failed -> uiState = uiState.copy(
                    followBusyId = null,
                    followedIds = if (wasFollowing) uiState.followedIds + channelId
                    else uiState.followedIds - channelId,
                    messageCode = result.code
                )

                ApiResult.Unreachable -> uiState = uiState.copy(
                    followBusyId = null,
                    followedIds = if (wasFollowing) uiState.followedIds + channelId
                    else uiState.followedIds - channelId,
                    messageCode = "unreachable"
                )
            }
        }
    }

    /** The public channel list: discovery, and the fallback home list. */
    private suspend fun loadPublicChannels(repo: GoodPostRepository) {
        when (val result = repo.channels()) {
            is ApiResult.Ok -> uiState = uiState.copy(
                channels = result.value.items,
                channelsLoading = false,
                channelsStale = false,
                channelsError = null
            )

            is ApiResult.Failed -> uiState = uiState.copy(
                channelsLoading = false,
                // A refusal does not throw away saved channels (§27).
                channelsStale = uiState.channels.isNotEmpty(),
                channelsError = result.code
            )

            ApiResult.Unreachable -> uiState = uiState.copy(
                channelsLoading = false,
                channelsStale = uiState.channels.isNotEmpty(),
                channelsError = if (uiState.channels.isEmpty()) "unreachable" else null
            )
        }
    }

    // ── Advertisements a reader sees (§12) ───────────────────────────────

    /**
     * Fetch the active cards for the top of the Channels list (§12).
     *
     * The CACHE is drawn first and the network replaces it, exactly as the
     * channel list works (§26, §27). A card is the same card on every open, so
     * asking the server for it each time made the tab wait on a read whose answer
     * had not changed; the cached copy is filtered by the clock on the way out,
     * so a card that expired since it was saved is not shown.
     *
     * Still best-effort: a failure leaves the cached cards in place rather than
     * reporting an error over a list that works.
     */
    fun loadAds() {
        val repo = repository ?: return
        repo.cachedAds(GoodPostAdPlacement.Channels)?.let { cached ->
            uiState = uiState.copy(ads = cached.ads)
        }
        viewModelScope.launch {
            val result = repo.ads(GoodPostAdPlacement.Channels)
            if (result is ApiResult.Ok) uiState = uiState.copy(ads = result.value)
        }
    }

    /**
     * Fetch the active cards for the top of Explore (§12).
     *
     * A separate read from the Channels one because the placements are separate: a
     * card can run on one surface, the other, or both, and the server filters by
     * the placement it was asked for. Cached the same way, under its own key.
     */
    fun loadExploreAds() {
        val repo = repository ?: return
        repo.cachedAds(GoodPostAdPlacement.Explore)?.let { cached ->
            uiState = uiState.copy(exploreAds = cached.ads)
        }
        viewModelScope.launch {
            val result = repo.ads(GoodPostAdPlacement.Explore)
            if (result is ApiResult.Ok) uiState = uiState.copy(exploreAds = result.value)
        }
    }

    private fun loadCategories() {
        val repo = repository ?: return
        viewModelScope.launch {
            if (repo.categories() is ApiResult.Ok) {
                uiState = uiState.copy(categories = repo.cachedCategories())
            }
        }
    }

    // ── A channel (§8–§14) ───────────────────────────────────────────────

    /**
     * Open a channel's feed. Fetches the channel if it is not already known.
     *
     * A channel this account has access to opens as its administrator's view of
     * it — the compose button, the post actions — because that is what the
     * account may do with it. Everything else opens as a reader's. The decision
     * is made here rather than at each row, so no screen can offer a control the
     * account does not hold.
     */
    fun openChannel(channelId: String) {
        if (uiState.canManage(channelId)) {
            openAdminChannel(channelId)
            return
        }

        uiState = uiState.forChannel(uiState.channels.firstOrNull { it.id == channelId })
        open(GoodPostScreen.Channel(channelId))
        loadChannel(channelId)
        loadPosts(channelId)
        markReadIfFollowed(channelId)
    }

    /**
     * Clear this channel's unread badge, because the reader has just opened it
     * (§5).
     *
     * Called when a channel is opened rather than when its posts finish loading,
     * because the badge means "there is something here you have not looked at"
     * and opening the channel is the act that answers it — a load that fails
     * still leaves the reader having seen the screen, and leaving the badge up
     * for a failure would make it unclearable exactly when the app is broken.
     *
     * Only for a channel the reader follows: there is no badge on anybody else's,
     * and the request would be a round trip to say nothing. Done fire-and-forget
     * from the caller's point of view — the badge is cleared locally as soon as
     * the server confirms, and a failure leaves it up rather than lying about it.
     */
    private fun markReadIfFollowed(channelId: String) {
        val repo = repository ?: return
        if (channelId !in uiState.followedIds) return

        viewModelScope.launch {
            if (repo.markChannelRead(channelId) is ApiResult.Ok) {
                uiState = uiState.copy(
                    channels = uiState.channels.map {
                        if (it.id == channelId) it.copy(unreadCount = 0) else it
                    }
                )
            }
        }
    }

    /**
     * Fetch one channel's own record (name, description, image, share link).
     *
     * Public because more than one screen needs it for a channel it was opened
     * with rather than navigated to — a search reached from a share link, for
     * instance, has a channel id and no row behind it.
     */
    fun loadChannel(channelId: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            // Fetched once and branched on, rather than tested and then fetched
            // again: a second call could return a different answer, and the
            // first one would have been paid for twice.
            val result = repo.channel(channelId)
            if (result is ApiResult.Ok) {
                uiState = uiState.copy(channel = result.value)
            }
        }
    }

    /**
     * The posts of a page that this reader has not deleted on their own device.
     *
     * One filter, applied where a list ENTERS the state rather than where it is
     * drawn, so there is exactly one answer to "is this post hidden" and no
     * screen can disagree with another.
     */
    private fun List<GoodPostPost>.visibleToMe(): List<GoodPostPost> {
        val hidden = hiddenStore ?: return this
        return filterNot { hidden.isHidden(it.id) }
    }

    /**
     * The same rule for media: a file belongs to the post that carried it.
     *
     * A separate name rather than an overload, because the two erase to the same
     * JVM signature and an overload that only differs in the element type is not
     * one Kotlin will compile.
     */
    private fun List<GoodPostMediaItem>.visibleMediaToMe(): List<GoodPostMediaItem> {
        val hidden = hiddenStore ?: return this
        return filterNot { hidden.isHidden(it.postId) }
    }

    fun loadPosts(channelId: String) {
        val repo = repository ?: return

        if (uiState.posts.isEmpty()) {
            val cached = repo.cachedPosts(channelId)
            if (cached != null) showCachedPosts(cached)
        }

        uiState = uiState.copy(postsLoading = true, postsError = null)
        // The reader's own reactions, asked for alongside the posts (§9). The
        // public read cannot carry them (it takes no token), so they arrive as a
        // second, tiny answer and are merged onto whatever page is showing.
        loadMyReactions(channelId)
        loadReactionEmoji()
        viewModelScope.launch {
            when (val result = repo.channelPosts(channelId)) {
                is ApiResult.Ok -> {
                    val items = result.value.items.visibleToMe()
                    uiState = uiState.copy(
                        posts = applyMyReactions(items),
                        postsCursor = result.value.nextCursor,
                        postsLoading = false,
                        postsStale = false,
                        postsError = null
                    )
                    // Only what is on screen is a view. A hidden post is not on
                    // screen, so reporting it would count the reader as having
                    // read something they deleted.
                    reportViews(channelId, items)
                }

                is ApiResult.Failed -> uiState = uiState.copy(
                    postsLoading = false,
                    postsStale = uiState.posts.isNotEmpty(),
                    postsError = result.code
                )

                ApiResult.Unreachable -> uiState = uiState.copy(
                    postsLoading = false,
                    postsStale = uiState.posts.isNotEmpty(),
                    postsError = if (uiState.posts.isEmpty()) "unreachable" else null
                )
            }
        }
    }

    private fun showCachedPosts(cached: CachedPosts) {
        uiState = uiState.copy(
            posts = cached.posts.visibleToMe(),
            postsLoading = false,
            postsStale = true
        )
    }

    // ── Reactions (§9) ──────────────────────────────────────────────────

    /**
     * This reader's own reactions, by post id.
     *
     * A device cache of the server's answer for the channel currently open: the
     * posts arrive without it, and merging needs it on every page. Cleared when a
     * different channel is opened, so a reaction made in one channel can never be
     * drawn on a post in another (which cannot happen by id, but a stale map is a
     * bug waiting for an id collision to become visible).
     */
    private var myReactions: Map<String, String> = emptyMap()
    private var myReactionsChannel: String? = null

    /**
     * Fetches the reader's reactions for [channelId] and re-applies them.
     *
     * Failure is deliberately silent: a reader who cannot be told which of their
     * own reactions are theirs still sees everybody's counts, and an error banner
     * about a missing token would describe a feature they never asked for.
     */
    private fun loadMyReactions(channelId: String) {
        val repo = repository ?: return
        if (myReactionsChannel != channelId) {
            myReactions = emptyMap()
            myReactionsChannel = channelId
        }
        viewModelScope.launch {
            val map = repo.myReactions(channelId)
            // A late answer for a channel the reader has left must not be applied
            // to whatever is on screen now.
            if (myReactionsChannel != channelId) return@launch
            myReactions = map
            uiState = uiState.copy(posts = applyMyReactions(uiState.posts))
        }
    }

    /** The vocabulary, fetched once per process. */
    private fun loadReactionEmoji() {
        val repo = repository ?: return
        if (uiState.reactionEmoji.isNotEmpty()) return
        viewModelScope.launch {
            val result = repo.reactionEmoji()
            if (result is ApiResult.Ok && uiState.reactionEmoji.isEmpty()) {
                uiState = uiState.copy(reactionEmoji = result.value)
            }
        }
    }

    /** Merges this reader's own choices onto a list of posts. */
    private fun applyMyReactions(posts: List<GoodPostPost>): List<GoodPostPost> {
        if (myReactions.isEmpty()) return posts
        return posts.map { post ->
            val mine = myReactions[post.id]
            if (mine == post.myReaction) post else post.copy(myReaction = mine)
        }
    }

    /**
     * React to one post, or change the reaction already on it (§9).
     *
     * Optimistic, because a reaction is a one-tap gesture that must feel
     * immediate, and the wrong moment to be truthful is the half second before the
     * server has answered. The server's own count replaces the guess when it
     * arrives; on failure the guess is rolled back and the reason is shown, which
     * is the only honest thing to do with a tap that did not land.
     *
     * Tapping the reaction the reader already has takes it off — the same chip
     * both ways, which is how every messaging app does it and removes the need for
     * a separate "unreact" control.
     */
    fun react(post: GoodPostPost, emoji: String) {
        val repo = repository ?: return
        if (post.id in uiState.reactionBusyIds) return

        val previous = post.reactions
        val previousMine = post.myReaction
        val removing = previousMine == emoji

        uiState = uiState.copy(
            reactionBusyIds = uiState.reactionBusyIds + post.id,
            posts = uiState.posts.map { row ->
                if (row.id == post.id) row.withReaction(if (removing) null else emoji) else row
            }
        )

        viewModelScope.launch {
            val result = if (removing) repo.unreact(post.id, emoji) else repo.react(post.id, emoji)

            when (result) {
                is ApiResult.Ok -> {
                    val answer = result.value
                    myReactions = if (answer.emoji == null) myReactions - answer.postId
                    else myReactions + (answer.postId to answer.emoji)
                    uiState = uiState.copy(
                        reactionBusyIds = uiState.reactionBusyIds - answer.postId,
                        posts = uiState.posts.map { row ->
                            if (row.id != answer.postId) row
                            else row.copy(
                                myReaction = answer.emoji,
                                reactions = row.reactions.recounted(
                                    emoji = answer.emoji ?: emoji,
                                    count = answer.count
                                )
                            )
                        }
                    )
                }

                is ApiResult.Failed -> {
                    uiState = uiState.copy(
                        reactionBusyIds = uiState.reactionBusyIds - post.id,
                        posts = uiState.posts.map { row ->
                            if (row.id == post.id) {
                                row.copy(reactions = previous, myReaction = previousMine)
                            } else row
                        },
                        messageCode = if (result.code == "auth_unavailable") {
                            "reactions_unavailable"
                        } else {
                            "reaction_failed"
                        }
                    )
                }

                ApiResult.Unreachable -> uiState = uiState.copy(
                    reactionBusyIds = uiState.reactionBusyIds - post.id,
                    posts = uiState.posts.map { row ->
                        if (row.id == post.id) row.copy(reactions = previous, myReaction = previousMine)
                        else row
                    },
                    messageCode = "reaction_failed"
                )
            }
        }
    }

    /**
     * The post with reaction [emoji] added or taken away, counted locally.
     *
     * The +1/-1 here is a GUESS and is replaced by the server's own number the
     * moment it answers (see [react]). It exists so the chip moves under the
     * reader's thumb instead of after the round trip.
     */
    private fun GoodPostPost.withReaction(emoji: String?): GoodPostPost {
        val before = myReaction
        val without = reactions.mapNotNull { reaction ->
            when (reaction.emoji) {
                before -> (reaction.count - 1).takeIf { it > 0 }?.let {
                    reaction.copy(count = it)
                }
                else -> reaction
            }
        }
        val next = if (emoji == null) without else {
            without.map { if (it.emoji == emoji) it.copy(count = it.count + 1) else it }
                .ifEmpty { without + GoodPostReaction(emoji, 1) }
        }
        return copy(myReaction = emoji, reactions = next.sortedByDescending { it.count })
    }

    /**
     * [emoji] with the server's count, leaving every other emoji untouched.
     *
     * A count of zero drops the chip: the last reader taking their reaction off
     * leaves no reaction to show, and a "0" beside an emoji would be a number
     * about something that is not there.
     */
    private fun List<GoodPostReaction>.recounted(
        emoji: String,
        count: Int
    ): List<GoodPostReaction> {
        val without = filterNot { it.emoji == emoji }
        return (if (count > 0) without + GoodPostReaction(emoji, count) else without)
            .sortedByDescending { it.count }
    }

    /** The next page of a channel's history (§26: lazy, cursor-paged). */
    fun loadMorePosts() {
        val repo = repository ?: return
        val channelId = (uiState.screen as? GoodPostScreen.Channel)?.channelId ?: return
        val cursor = uiState.postsCursor ?: return
        if (uiState.postsLoadingMore) return

        uiState = uiState.copy(postsLoadingMore = true)
        viewModelScope.launch {
            when (val result = repo.channelPosts(channelId, cursor)) {
                is ApiResult.Ok -> {
                    val items = result.value.items.visibleToMe()
                    uiState = uiState.copy(
                        posts = applyMyReactions(
                            GoodPostCodec.merge(uiState.posts, items) { it.id }
                        ),
                        postsCursor = result.value.nextCursor,
                        postsLoadingMore = false
                    )
                    reportViews(channelId, items)
                }

                else -> uiState = uiState.copy(postsLoadingMore = false)
            }
        }
    }

    /**
     * Post ids this device has already had counted (§9).
     *
     * An in-memory mirror of [viewedStore], primed from it at [initialize]. The
     * mirror is what keeps a page load from touching Preferences on every post,
     * and the store is what makes the memory survive a cold start — the missing
     * half that used to make a restart re-count every post on the first page.
     *
     * Outside the UI state on purpose: nothing draws it, and putting it there
     * would make every render of a view count recompose the screen. It is scoped
     * to the DEVICE rather than to a channel because a post's identity is a
     * server id — the same post opened from a search and from the feed is one
     * read, not two.
     */
    private val reportedViews = mutableSetOf<String>()

    /**
     * Tell the server which of these posts the reader has just been shown (§9).
     *
     * Called after a successful page load rather than on each post as it scrolls
     * past, because "was shown a page" is the only thing this side can know
     * without a scroll listener that would have to guess at visibility, dwell
     * time and whether the screen was even on. One request per page is also the
     * difference between a read that costs one round trip and a read that costs
     * one per row.
     *
     * Failure is silent by design: a count that did not go up is not something a
     * reader can act on, and reporting it would put an error over a screen that
     * is working perfectly.
     */
    private fun reportViews(channelId: String, posts: List<GoodPostPost>) {
        val repo = repository ?: return
        val fresh = posts.map { it.id }.filter { reportedViews.add(it) }
        if (fresh.isEmpty()) return

        // Written to the device BEFORE the request goes out, so a report that is
        // in flight when the app is killed is not repeated on the next start.
        // The alternative — persist only after the server answers — re-counts
        // every time a launch happens to be interrupted.
        viewedStore?.mark(fresh)

        // The store caps its own file; the mirror follows it rather than clearing
        // outright, because clearing would make the next page load re-report
        // everything that was evicted-and-still-on-screen.
        if (reportedViews.size > VIEW_REPORT_LIMIT) {
            val retained = viewedStore?.ids()
            reportedViews.clear()
            if (retained != null) reportedViews.addAll(retained)
        }

        viewModelScope.launch { repo.reportPostViews(channelId, fresh) }
    }

    /** The channel information page's gallery (§13). */
    fun loadMedia(channelId: String) {
        // The stars on the information page come from this device, so they are
        // read here rather than fetched: opening the page must not cost a request
        // for something the phone already knows (§26).
        refreshStars(channelId)

        val repo = repository ?: return
        uiState = uiState.copy(mediaLoading = true, mediaError = null)

        viewModelScope.launch {
            when (val result = repo.channelMedia(channelId)) {
                is ApiResult.Ok -> uiState = uiState.copy(
                    media = result.value.items.visibleMediaToMe(),
                    mediaCursor = result.value.nextCursor,
                    mediaLoading = false,
                    mediaError = null
                )

                is ApiResult.Failed -> uiState = uiState.copy(
                    mediaLoading = false,
                    mediaError = result.code
                )

                ApiResult.Unreachable -> uiState = uiState.copy(
                    mediaLoading = false,
                    mediaError = "unreachable"
                )
            }
        }
    }

    /** The next page of a channel's media (§13: the same cursor walk as the feed). */
    fun loadMoreMedia() {
        val repo = repository ?: return
        val cursor = uiState.mediaCursor ?: return
        val channelId = (uiState.screen as? GoodPostScreen.ChannelMedia)?.channelId ?: return
        if (uiState.mediaLoadingMore) return

        uiState = uiState.copy(mediaLoadingMore = true)
        viewModelScope.launch {
            when (val result = repo.channelMedia(channelId, cursor)) {
                is ApiResult.Ok -> uiState = uiState.copy(
                    media = GoodPostCodec.merge(uiState.media, result.value.items.visibleMediaToMe()) {
                        it.id
                    },
                    mediaCursor = result.value.nextCursor,
                    mediaLoadingMore = false
                )

                else -> uiState = uiState.copy(mediaLoadingMore = false)
            }
        }
    }

    /**
     * Re-read ONE post, to replace the row that is on screen (§9).
     *
     * Used by the media viewer when a signature has expired: the answer is the
     * same post with a new URL, and the URL is what re-keys the player. A failure
     * is silent because the viewer has already said the video could not be played
     * and is showing its own Retry — two messages about one problem is noise.
     */
    fun refreshPost(postId: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            val result = repo.post(postId)
            if (result is ApiResult.Ok) {
                uiState = uiState.copy(
                    posts = uiState.posts.map { if (it.id == postId) result.value else it }
                )
            }
        }
    }

    // ── The gallery's own selection (§13) ─────────────────────────────

    /**
     * Long press in the media grid: select the item, or drop it again.
     *
     * A separate set from [selectedPostIds] on purpose. The two screens cannot
     * be on screen at once, but they can both be in the back stack, and a
     * selection is only meaningful against the list it was made in — sharing one
     * set would let a selection made in the feed arm the gallery's Delete.
     */
    fun toggleMediaSelected(mediaId: String) {
        val next = uiState.selectedMediaIds.toMutableSet()
        if (!next.add(mediaId)) next.remove(mediaId)
        uiState = uiState.copy(selectedMediaIds = next, messageCode = null)
    }

    fun clearMediaSelection() {
        uiState = uiState.copy(
            selectedMediaIds = emptySet(),
            selectionBusy = false,
            messageCode = null
        )
    }

    /**
     * Drop the selected media from THIS DEVICE (§6, §13).
     *
     * The other half of [deleteSelectedMedia], and the one a reader can always
     * reach. It used to be the only option that existed and it was offered only
     * to an administrator — so a reader who selected photos in "Media and links"
     * and pressed Delete got nothing at all, which is precisely the complaint
     * that produced this function.
     *
     * Nothing here talks to the server, because nothing here is the server's:
     * the bytes on this phone are the reader's copy, and the channel's copy is
     * untouched. Both caches are keyed on the object key rather than the URL, so
     * a deletion found the files even though every page load hands out a new
     * signed URL (§26) — and a re-open simply streams them again rather than
     * being told they are gone.
     */
    fun deleteSelectedMediaFromDevice() {
        val selected = uiState.selectedMediaIds
        if (selected.isEmpty()) return
        val items = uiState.media.filter { selected.contains(it.id) }
        if (items.isEmpty()) return
        dropMediaFromDevice(
            ids = items.map { it.id }.toSet(),
            postIds = items.map { it.postId }.toSet(),
            urls = items.mapNotNull { it.url }
        )
    }

    /**
     * Drop ONE file from THIS DEVICE (§6, §13).
     *
     * The same act as [deleteSelectedMediaFromDevice], reached from the one place
     * a single file is deleted: the full-screen viewer, opened from either the
     * feed or the gallery. It used to be handled entirely inside that viewer's
     * menu — the menu deleted the cached files and closed the viewer, and nothing
     * told the tab. So the post behind it kept drawing the bitmap it had already
     * decoded, and the picture only left when the channel was closed and reopened
     * and every row was built again. Deleting has to go through the state the
     * lists are drawn from, not just the cache underneath them.
     *
     * [postId] and [url] are passed by the caller because a feed has no media
     * page loaded: the gallery owns that list, and a viewer opened from the feed
     * knows its post and its URL without one.
     */
    fun deleteMediaFromDevice(postId: String, mediaId: String, url: String? = null) {
        val known = uiState.media.firstOrNull { it.id == mediaId }
        dropMediaFromDevice(
            ids = setOf(mediaId),
            // The gallery's own record wins when there is one, because it is the
            // page the row actually belongs to.
            postIds = setOf(known?.postId?.takeIf { it.isNotBlank() } ?: postId),
            urls = listOfNotNull(known?.url ?: url?.takeIf { it.isNotBlank() })
        )
    }

    /**
     * The one place a device-delete is carried out.
     *
     * ## Why the post goes with the file
     *
     * The post is what "deleted" means to a reader: a grid item whose post is
     * still in the feed has not gone anywhere, it has just stopped drawing a
     * thumbnail. Hiding the post is what makes the deletion visible in both
     * places, and it is the same set the feed reads.
     *
     * ## Why the cache is forgotten off this thread
     *
     * `GoodPostVideoCache.evict` removes a cache span and rewrites its index
     * synchronously, so a selection of several clips was a visible stall on the
     * screen the reader was still looking at.
     */
    private fun dropMediaFromDevice(ids: Set<String>, postIds: Set<String>, urls: List<String>) {
        if (ids.isEmpty()) return

        hiddenStore?.hide(postIds)
        forgetStars(postIds)

        // Everything the lists draw from moves in ONE state write, so a row and
        // its gallery tile can never disagree about whether it is still here.
        //
        // ALL of them, not just the two the reader deleted from: the channel
        // search keeps its own copy of the posts it found, and the bookmarks
        // keep their own list of the posts they point at. A copy left behind is
        // exactly how a deleted picture stayed on screen — in a screen nobody
        // had scrolled, until the channel was closed and opened again.
        uiState = uiState.copy(
            media = uiState.media.filterNot { ids.contains(it.id) },
            posts = uiState.posts.filterNot { postIds.contains(it.id) },
            // Re-asked with the same rule every list is filtered by, now that
            // the store knows these posts are gone.
            channelSearchResults = uiState.channelSearchResults.visibleToMe(),
            selectedMediaIds = uiState.selectedMediaIds - ids,
            messageCode = null
        )

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                GoodPostImages.forget(urls)
                // A clip's still is part of what this device holds for it: leaving
                // it behind would keep drawing the deleted video's frame in the
                // grid.
                GoodPostVideoPoster.forget(urls)
                urls.forEach { GoodPostVideoCache.evict(it) }
            }
            uiState = uiState.copy(messageCode = "deleted_from_device")
        }
    }

    /**
     * "Delete for me" on a selection of posts (§9).
     *
     * The reader's answer, and the only deletion a reader can perform: the posts
     * leave THIS device's view and stay on the server for everybody else. Nothing
     * is sent anywhere — there is no per-reader copy to remove, so the request
     * that would carry it does not exist and is not invented.
     *
     * The files behind them go too. A hidden post that has left its cached bytes
     * on the phone has not been deleted in any sense a reader would recognise, and
     * "delete" here is a promise about their own storage as much as about the
     * list.
     */
    fun hideSelectedPosts() {
        val selected = uiState.selectedPostIds
        if (selected.isEmpty()) return

        val posts = uiState.posts.filter { selected.contains(it.id) }
        if (posts.isEmpty()) return

        hiddenStore?.hide(posts.map { it.id })
        forgetStars(posts.map { it.id })

        viewModelScope.launch {
            // Every media URL of every hidden post, so the phone stops holding the
            // bytes as well as the row.
            val urls = posts.flatMap { post -> post.media.mapNotNull { it.url } }
            // Off the main thread for the same reason the media selection is:
            // evicting N videos is N synchronous cache-index writes, and N is as
            // large as the selection.
            withContext(Dispatchers.IO) {
                GoodPostImages.forget(urls)
                GoodPostVideoPoster.forget(urls)
                urls.forEach { GoodPostVideoCache.evict(it) }
            }
        }

        uiState = uiState.copy(
            posts = uiState.posts.filterNot { selected.contains(it.id) },
            media = uiState.media.filterNot { selected.contains(it.postId) },
            // The same sweep as a media delete: the search's copy of a post and
            // the bookmark pointing at it go when the post does.
            channelSearchResults = uiState.channelSearchResults.visibleToMe(),
            selectedPostIds = emptySet(),
            messageCode = "deleted_from_device"
        )
    }

    /**
     * Delete the posts that carry the selected media (§17).
     *
     * The media is not a thing that can be deleted on its own: a file belongs to
     * the update that posted it, and an update is what the server can remove. So
     * the selection is resolved to its posts, and the bulk delete does the rest.
     * The object itself is removed by the server's own sweep, which is why a
     * reader gets the same confirmation either way.
     */
    fun deleteSelectedMedia() {
        val repo = repository ?: return
        if (uiState.admin == null) return
        if (uiState.selectionBusy) return
        val selected = uiState.selectedMediaIds
        if (selected.isEmpty()) return

        val postIds = uiState.media
            .filter { selected.contains(it.id) }
            .map { it.postId }
            .distinct()
        if (postIds.isEmpty()) return

        uiState = uiState.copy(selectionBusy = true)
        viewModelScope.launch {
            when (val result = repo.adminDeletePosts(postIds)) {
                is ApiResult.Ok -> {
                    // Only once the server has agreed: a refused delete leaves the
                    // posts standing, and the stars pointing at them should too.
                    forgetStars(postIds)
                    uiState = uiState.copy(
                        media = uiState.media.filterNot { selected.contains(it.id) },
                        posts = uiState.posts.filterNot { postIds.contains(it.id) },
                        selectedMediaIds = emptySet(),
                        selectionBusy = false
                    )
                    // Reloaded rather than trusted: the gallery's page boundary
                    // moves when rows in the middle of it disappear, exactly as
                    // the feed's does.
                    currentMediaChannelId()?.let { loadMedia(it) }
                    currentChannelId()?.let { loadPosts(it) }
                    // And the LIST is told too. A delete moves the channel's
                    // preview and its timestamp — the newest post may be the one
                    // that just went — so a list left alone would keep advertising
                    // something the reader had removed.
                    refreshLists()
                }

                is ApiResult.Failed -> uiState = uiState.copy(
                    selectedMediaIds = emptySet(),
                    selectionBusy = false,
                    messageCode = adminFailureCode(result)
                )

                ApiResult.Unreachable -> uiState = uiState.copy(
                    selectedMediaIds = emptySet(),
                    selectionBusy = false,
                    messageCode = "unreachable"
                )
            }
        }
    }

    private fun currentMediaChannelId(): String? =
        (uiState.screen as? GoodPostScreen.ChannelMedia)?.channelId
            ?: (uiState.backStack.lastOrNull { it is GoodPostScreen.ChannelMedia }
                as? GoodPostScreen.ChannelMedia)?.channelId

    fun toggleDescription() {
        uiState = uiState.copy(descriptionExpanded = !uiState.descriptionExpanded)
    }

    // ── Selection (§5) ───────────────────────────────────────────────────

    /** Long press: select the row, or add it to what is already selected. */
    fun toggleChannelSelected(channelId: String) {
        val next = uiState.selectedChannelIds.toMutableSet()
        if (!next.add(channelId)) next.remove(channelId)
        uiState = uiState.copy(selectedChannelIds = next, messageCode = null)
    }

    fun clearChannelSelection() {
        uiState = uiState.copy(selectedChannelIds = emptySet(), messageCode = null)
    }

    /**
     * Delete the selected channels (§17).
     *
     * The administrator-side action, and the only irreversible one in the app:
     * the server removes the channel with its posts, its media and the login that
     * ran it. The confirmation is the screen's job — see `ConfirmDeleteDialog` —
     * and this is what runs once it has been given.
     */
    fun deleteSelectedChannels() {
        val repo = repository ?: return
        if (uiState.admin == null) return

        val selected = uiState.selectedChannelIds
        if (selected.isEmpty()) return

        // A channel administrator can reach this only for their own channel, and
        // the server is what enforces that; the selection is checked here as well
        // so the app never sends a request it knows will be refused.
        val permitted = selected.filter { uiState.canManage(it) }
        if (permitted.isEmpty()) {
            uiState = uiState.copy(messageCode = "admin_forbidden", selectedChannelIds = emptySet())
            return
        }

        viewModelScope.launch {
            var firstFailure: String? = null
            for (channelId in permitted) {
                val result = repo.adminDeleteChannel(channelId)
                if (result is ApiResult.Failed || result is ApiResult.Unreachable) {
                    firstFailure = if (result is ApiResult.Failed) adminFailureCode(result) else "unreachable"
                    break
                }
            }

            // A deleted channel takes its stars with it, by the same rule a
            // deleted post does: the rows name a channel that is no longer there.
            permitted.forEach { starredStore?.removeChannel(it) }

            // Removed locally whatever succeeded, then reloaded: the server is
            // the only thing that knows what is left, and guessing would leave a
            // row on screen for a channel that no longer exists.
            uiState = uiState.copy(
                starredPostIds = starredStore?.all()?.map { it.postId }?.toSet()
                    ?: uiState.starredPostIds,
                starred = uiState.starred.filterNot { permitted.contains(it.channelId) },
                adminChannels = uiState.adminChannels.filterNot { permitted.contains(it.id) },
                channels = uiState.channels.filterNot { permitted.contains(it.id) },
                selectedChannelIds = emptySet(),
                messageCode = firstFailure
            )
            if (uiState.admin != null) loadAdminChannels()
            refreshChannels()
        }
    }

    /** Long press a post: select it, or add it to what is already selected. */
    fun togglePostSelected(postId: String) {
        val next = uiState.selectedPostIds.toMutableSet()
        if (!next.add(postId)) next.remove(postId)
        uiState = uiState.copy(selectedPostIds = next, messageCode = null)
    }

    fun clearPostSelection() {
        uiState = uiState.copy(
            selectedPostIds = emptySet(),
            selectionBusy = false,
            messageCode = null
        )
    }

    /**
     * Star or unstar one post (§9).
     *
     * A star is a private bookmark, so it is written to this device and nowhere
     * else — see [GoodPostStarred] for why the row is kept whole rather than as an
     * id. The channel and its name travel with it so the bookmark can still be
     * read from the information page after the post has aged out of the server's
     * retention window (§14).
     */
    fun toggleStar(post: GoodPostPost) {
        val store = starredStore ?: return
        val channel = uiState.channel
        val starred = store.toggle(
            GoodPostStarredEntry(
                postId = post.id,
                channelId = channel?.id ?: "",
                channelName = channel?.name ?: "",
                body = post.body,
                kind = when {
                    post.media.any { it.isVideo } -> "video"
                    post.media.any { it.isImage } -> "image"
                    post.isLink -> "link"
                    else -> "text"
                },
                createdAt = post.createdAt,
                starredAt = System.currentTimeMillis()
            )
        )
        uiState = uiState.copy(
            starredPostIds = store.all().map { it.postId }.toSet(),
            starred = store.forChannel(channel?.id.orEmpty()),
            messageCode = if (starred) "starred" else "unstarred"
        )
    }

    /** Star every selected post, from the feed's action bar (§5, §9). */
    fun starSelectedPosts() {
        val selected = uiState.posts.filter { uiState.selectedPostIds.contains(it.id) }
        if (selected.isEmpty()) return
        selected.forEach { post ->
            if (post.id !in uiState.starredPostIds) toggleStar(post)
        }
        clearPostSelection()
    }

    /**
     * Put one emoji on every selected update (§9).
     *
     * The same rule the star uses, because a selection is one intent and has to
     * have one answer: if EVERY selected post already carries this emoji the
     * gesture takes it off all of them, and otherwise it puts it on the ones that
     * do not have it and leaves the rest alone. Sending the emoji to a post that
     * already holds it would toggle it OFF, which is how a mixed selection turns
     * into a scattered one — some set, some cleared, none of it what was asked.
     *
     * Each send is per post, and the picker offers exactly the emoji the server
     * named, so nothing here can invent one the API would refuse.
     */
    fun reactToSelectedPosts(emoji: String) {
        if (emoji.isBlank()) return
        val selected = uiState.posts.filter { uiState.selectedPostIds.contains(it.id) }
        if (selected.isEmpty()) return

        val everyOneHasIt = selected.all { it.myReaction == emoji }
        selected.forEach { post ->
            if (everyOneHasIt || post.myReaction != emoji) react(post, emoji)
        }
        clearPostSelection()
    }

    /**
     * Take the stars pointing at these posts off this device (§9).
     *
     * A star is a COPY of the row — see [GoodPostStarred] — and that copy is what
     * lets a bookmark outlive the server's thirty-day window (§14). It is also
     * what would let it outlive the reader's own deletion of the message, so every
     * path that deletes a post or its media comes through here and empties the
     * STORE, not just the list on screen. Filtering `uiState.starred` alone is the
     * bug this closes: [refreshStars] re-reads the store, so the star came
     * straight back the next time the starred list was opened.
     *
     * A star whose post the SERVER removed, or let age out, is deliberately left
     * alone: no device can tell that apart from a post it simply has not fetched
     * yet, and guessing would throw a bookmark away on a bad connection.
     */
    private fun forgetStars(postIds: Collection<String>) {
        if (postIds.isEmpty()) return
        val store = starredStore ?: return
        postIds.forEach { store.remove(it) }
        uiState = uiState.copy(
            starredPostIds = uiState.starredPostIds - postIds.toSet(),
            starred = uiState.starred.filterNot { postIds.contains(it.postId) }
        )
    }

    /** The stars belonging to one channel, for its information page (§11). */
    fun refreshStars(channelId: String) {
        val store = starredStore ?: return
        // A starred message the reader has since deleted for themselves stays
        // deleted: the star is a private bookmark, not a copy of the channel, and
        // letting one resurrect a post this device removed would be the two
        // answers contradicting each other on one screen (§5).
        val hidden = hiddenStore
        uiState = uiState.copy(
            starredPostIds = store.all().map { it.postId }.toSet(),
            starred = store.forChannel(channelId)
                .filterNot { entry -> hidden?.isHidden(entry.postId) == true }
        )
    }

    /**
     * Open the reader's stars for one channel (§9).
     *
     * The stars are re-read on the way in rather than trusted from whatever the
     * information page last loaded: this screen can be reached from an
     * information page that has been in the back stack while posts were starred
     * from a feed beneath it, and a bookmark list that is one edit stale is a
     * list that loses a bookmark.
     */
    fun openStarred(channelId: String) {
        refreshStars(channelId)
        open(GoodPostScreen.Starred(channelId))
        // Both cleared, so opening the list asks again rather than trusting URLs
        // signed during a previous visit: a signed URL is an hour's lease, and a
        // bookmark list opened tomorrow would otherwise draw broken pictures for
        // every row it had already fetched.
        starredMediaAsked.clear()
        uiState = uiState.copy(starredMedia = emptyMap())
        loadStarredMedia()
    }

    /**
     * The post ids whose media has already been asked for, successfully or not.
     *
     * Kept so a list of bookmarks cannot become a request on every recomposition,
     * and so a post the server no longer keeps is asked for once rather than every
     * time the list is scrolled. Reset with the list itself — see [refreshStars].
     */
    private val starredMediaAsked = mutableSetOf<String>()

    /**
     * Give the starred rows their pictures (§11).
     *
     * A bookmark keeps the post id, its words and what kind of file it was — not
     * a URL, which expires, and not a copy of the image, which would be a second
     * cache of the reader's own bookmarks. So a row that describes a photo is
     * given its photo here, by reading the post on its own (the same public read a
     * share link uses) and taking the asset's fresh signed URL.
     *
     * Bounded twice, because this is the reader's phone: a handful per pass and
     * once per post, so a list of forty bookmarks is a few small reads rather than
     * forty at once. A post that answers nothing — deleted, or past the retention
     * window — is simply marked as asked and keeps the label it had.
     */
    fun loadStarredMedia() {
        val repo = repository ?: return

        val wanted = uiState.starred
            .filter { it.kind == "image" || it.kind == "video" }
            .filterNot { starredMediaAsked.contains(it.postId) }
            .take(STARRED_MEDIA_PER_PASS)
        if (wanted.isEmpty()) return

        wanted.forEach { entry ->
            starredMediaAsked.add(entry.postId)
            viewModelScope.launch {
                val media = when (val result = repo.post(entry.postId)) {
                    is ApiResult.Ok -> result.value.media.firstOrNull()
                    else -> null
                }
                // Checked against the CURRENT screen as well as the result: the
                // reader may have left the list while this was in the air, and a
                // URL for a row nobody is looking at is a URL nobody needs.
                if (media != null && uiState.screen is GoodPostScreen.Starred) {
                    uiState = uiState.copy(
                        starredMedia = uiState.starredMedia + (entry.postId to media)
                    )
                }
            }
        }
    }

    /**
     * Remove one star, from a list that holds rows rather than posts (§9).
     *
     * [toggleStar] needs the post itself, because a star KEEPS the row; a screen
     * whose entire job is undoing stars already has the row in hand, so it hands
     * back the id. Both write through the same store, so the two lists can never
     * disagree about what is starred.
     */
    fun unstarPost(postId: String) {
        val store = starredStore ?: return
        store.remove(postId)
        val hidden = hiddenStore
        val channelId = (uiState.screen as? GoodPostScreen.Starred)?.channelId
        uiState = uiState.copy(
            starredPostIds = store.all().map { it.postId }.toSet(),
            starred = store.all()
                .filter { channelId == null || it.channelId == channelId }
                .filterNot { entry -> hidden?.isHidden(entry.postId) == true },
            // The row is gone, so its picture is too. Without this the map keeps a
            // URL for every message ever unstarred in the session.
            starredMedia = uiState.starredMedia - postId,
            messageCode = "unstarred"
        )
    }

    /**
     * Turn Good Post notifications on or off for this device (§8).
     *
     * Asking for a notification permission is part of the answer rather than a
     * separate step: the switch is the only place a reader's intent is known, and
     * a permission prompt at launch — before there is any reason to want one —
     * is the way that permission gets refused forever.
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        val context = appContext ?: return
        if (enabled) requestNotificationPermission?.invoke()
        GoodPostNotifications.setEnabled(context, enabled)
        // The schedule always exists and the worker reads the switch; turning it
        // on also runs one check, which adopts the current timestamps so the
        // first real notification is about a post that arrives after now.
        GoodPostUpdateScheduler.ensureScheduled(context)
        if (enabled) GoodPostUpdateScheduler.checkNow(context)
        // The same switch, on the server's side of it: on, this device's token is
        // registered so the next publish arrives in seconds; off, it is removed so
        // the fan-out stops paying for a phone that no longer listens.
        viewModelScope.launch { GoodPostPush.sync(context) }
        uiState = uiState.copy(notificationsEnabled = enabled)
    }

    /**
     * The Android 13+ permission request, supplied by the screen.
     *
     * A `var` holding a lambda rather than a call into the activity, because the
     * ViewModel must not hold one — and because this is the only moment the app
     * can ask with any hope of being answered yes.
     */
    var requestNotificationPermission: (() -> Unit)? = null

    /**
     * Mute or unmute ONE channel's notifications (§6).
     *
     * The server owns this flag: it lives on the follow row, so it survives a
     * reinstall the way the follow does, and the follower's phone is not the
     * place to keep a subscription's state. Optimistic, like every other toggle
     * in the tab, and put back if the server disagrees.
     */
    fun setChannelNotifications(channelId: String, enabled: Boolean) {
        val repo = repository ?: return
        val muted = !enabled
        val previous = uiState.mutedChannelIds
        uiState = uiState.copy(
            mutedChannelIds = if (muted) previous + channelId else previous - channelId
        )
        viewModelScope.launch {
            when (val result = repo.setChannelMuted(channelId, muted)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Failed -> uiState = uiState.copy(
                    mutedChannelIds = previous,
                    messageCode = result.code
                )

                ApiResult.Unreachable -> uiState = uiState.copy(
                    mutedChannelIds = previous,
                    messageCode = "unreachable"
                )
            }
        }
    }

    /**
     * Remove every selected post in one request (§17).
     *
     * One call rather than one per post: the server applies the selection
     * atomically, so a failure leaves the list whole instead of half-emptied.
     */
    fun deleteSelectedPosts() {
        val repo = repository ?: return
        if (uiState.admin == null) return
        // One batch at a time: a second tap while the first is in flight would
        // send the same ids again, and the server's answer to the second would
        // arrive after the list had already been emptied.
        if (uiState.selectionBusy) return

        val selected = uiState.selectedPostIds.toList()
        if (selected.isEmpty()) return

        // §5: the bar says it is working while the batch is in flight, and stops
        // accepting taps. Without this the bar looked untouched for the length of
        // the round trip — which is exactly what "the delete is stuck" looks
        // like — and a second tap could send a second batch of the same ids.
        uiState = uiState.copy(selectionBusy = true)
        viewModelScope.launch {
            when (val result = repo.adminDeletePosts(selected)) {
                is ApiResult.Ok -> {
                    // A deleted post takes its media and its bookmark with it: a
                    // starred row for something that no longer exists is a row
                    // that opens onto nothing — and the star on disk is the one
                    // that used to bring it back.
                    forgetStars(selected)
                    uiState = uiState.copy(
                        posts = uiState.posts.filterNot { selected.contains(it.id) },
                        media = uiState.media.filterNot { selected.contains(it.postId) },
                        selectedPostIds = emptySet(),
                        selectionBusy = false
                    )
                    // Reloaded rather than trusted: a page boundary can shift when
                    // rows in the middle of the list disappear.
                    currentChannelId()?.let { loadPosts(it) }
                    // The channel's preview and timestamp moved with the delete.
                    refreshLists()
                }

                is ApiResult.Failed -> uiState = uiState.copy(
                    selectedPostIds = emptySet(),
                    selectionBusy = false,
                    messageCode = adminFailureCode(result)
                )

                ApiResult.Unreachable -> uiState = uiState.copy(
                    selectedPostIds = emptySet(),
                    selectionBusy = false,
                    messageCode = "unreachable"
                )
            }
        }
    }

    /**
     * The state a channel's screen starts from, whichever list opened it.
     *
     * This is where per-channel state is scoped, and it is one function rather
     * than two `copy` calls for that reason: opening a feed is the moment every
     * channel-scoped field has to be decided, and when it was written out twice,
     * one copy was missing the selection and the other was missing the errors.
     *
     * What is cleared, and why each one is here:
     *
     *  * **The editor.** An edit belongs to one channel's post. Carrying
     *    `editingPostId` into another channel is the bug this exists to prevent,
     *    so it goes, along with the body, the attachments and the composer's own
     *    open flag — an editor left half-populated over a different channel is
     *    worse than one that was closed.
     *  * **The selection.** Post ids are only unambiguous within a channel, so a
     *    selection that survived a switch would name posts the new channel does
     *    not have, and the action bar would count rows that are not there.
     *  * **The feed and its paging.** Posts, the cursor, the error and the
     *    in-flight flag all describe the previous channel's history.
     *  * **The profile gallery.** `media` and `descriptionExpanded` belong to the
     *    information page of one channel.
     *
     * [loadChannel] and [loadPosts] fill the rest in behind this.
     */
    private fun GoodPostUiState.forChannel(channel: GoodPostChannel?): GoodPostUiState = copy(
        channel = channel,
        posts = emptyList(),
        postsCursor = null,
        postsLoading = false,
        postsLoadingMore = false,
        postsError = null,
        postsStale = false,
        selectedPostIds = emptySet(),
        selectionBusy = false,
        media = emptyList(),
        mediaLoading = false,
        mediaCursor = null,
        mediaLoadingMore = false,
        mediaError = null,
        selectedMediaIds = emptySet(),
        descriptionExpanded = false,
        // §19 Bug 2/3: the composer is closed AND emptied, so neither an editing
        // strip nor a half-typed body can appear over another channel.
        composerOpen = false,
        composerBody = "",
        composerBusy = false,
        editingPostId = null,
        editingPostChannelId = null,
        composerAttachments = emptyList(),
        messageCode = null
    )

    /** The channel the open feed is showing, whichever screen opened it. */
    private fun currentChannelId(): String? = when (val screen = uiState.screen) {
        is GoodPostScreen.Channel -> screen.channelId
        is GoodPostScreen.AdminChannel -> screen.channelId
        else -> null
    }

    // ── Explore (§7) ─────────────────────────────────────────────────────

    fun openExplore(query: String = "") {
        uiState = uiState.copy(query = query, explore = emptyList(), exploreCursor = null)
        open(GoodPostScreen.Explore)
        search()
        // §12: the Explore placement is fetched on the way in, alongside the
        // search. Best-effort, like the Channels one — see [loadExploreAds].
        loadExploreAds()
    }

    /**
     * Open the Channels screen (§7) — the sectioned discovery page.
     *
     * The sections are loaded on the way in and cached in the ViewModel, so
     * coming back from a channel does not refetch them: this screen is a way in,
     * and a list that reloads under the reader every time they return from a
     * channel would make browsing feel like a series of arrivals.
     */
    fun openDiscover() {
        open(GoodPostScreen.Discover)
        loadDiscover()
        loadExploreAds()
    }

    /**
     * The Channels screen's sections (§7).
     *
     * One request per section, run together: a section is a different FILTER and
     * ORDER over the same catalogue, and the server pages each independently. A
     * single request could not produce them all without the client re-sorting,
     * which would put the ordering rule in two places and only one of them right.
     *
     * They are fired concurrently and awaited together, so the screen arrives in
     * one piece rather than filling in row by row. A section that fails does not
     * blank the others: half a catalogue is still worth showing, and the note
     * above it says what is missing.
     */
    fun loadDiscover() {
        val repo = repository ?: return
        if (uiState.discoverLoading) return
        val generation = ++discoverGeneration

        uiState = uiState.copy(discoverLoading = true, discoverError = null)
        viewModelScope.launch {
            val sections = ChannelSection.entries
            val results = sections.map { section ->
                section to repo.channels(
                    category = section.category,
                    sort = section.sort,
                    limit = SECTION_PREVIEW
                )
            }

            // A newer load replaced this one while the requests were in flight;
            // its answer is the one that belongs on screen.
            if (generation != discoverGeneration) return@launch

            val loaded = LinkedHashMap<ChannelSection, List<GoodPostChannel>>()
            var error: String? = null
            results.forEach { (section, result) ->
                when (result) {
                    is ApiResult.Ok -> loaded[section] = result.value.items
                    is ApiResult.Failed -> if (error == null) error = result.code
                    ApiResult.Unreachable -> if (error == null) error = "unreachable"
                }
            }

            uiState = uiState.copy(
                // A section that succeeded replaces what is there; one that failed
                // keeps the rows it already had, so a retry that half-fails does
                // not empty a section the reader was looking at.
                discoverSections = uiState.discoverSections + loaded,
                discoverLoading = false,
                discoverError = error
            )
        }
    }

    /** Which discovery load is current; see [searchGeneration]. */
    private var discoverGeneration = 0

    /**
     * Open one section in full (§7).
     *
     * The section's order is carried into the screen, so the first thing drawn is
     * the same rows the preview showed — a See-all that opened in a different
     * order would look like it had gone somewhere else.
     */
    fun openSection(section: ChannelSection) {
        uiState = uiState.copy(
            section = section,
            sectionSort = section.sort,
            sectionItems = emptyList(),
            sectionCursor = null,
            sectionQuery = ""
        )
        open(GoodPostScreen.Section(section))
        loadSection()
    }

    /** The See-all screen's list, in the section's current order (§7). */
    fun loadSection() {
        val repo = repository ?: return
        if (uiState.section == null) return
        if (uiState.sectionLoading) return
        val generation = ++sectionGeneration

        uiState = uiState.copy(sectionLoading = true, sectionError = null)
        viewModelScope.launch {
            val result = repo.channels(
                query = uiState.sectionQuery.takeIf { it.isNotBlank() },
                // The section's own category, so "See all" shows every channel in
                // the section rather than every channel there is.
                category = uiState.section?.category,
                sort = uiState.sectionSort,
                limit = SECTION_PAGE
            )
            // Dropped if a newer request has replaced this one: tapping through
            // the chips quickly can leave two in flight, and the slower reply
            // must not overwrite the order the reader is now looking at.
            if (generation != sectionGeneration) return@launch
            uiState = when (result) {
                is ApiResult.Ok -> uiState.copy(
                    sectionItems = result.value.items,
                    sectionCursor = result.value.nextCursor,
                    sectionLoading = false,
                    sectionError = null
                )
                is ApiResult.Failed -> uiState.copy(
                    sectionLoading = false,
                    sectionError = result.code
                )
                ApiResult.Unreachable -> uiState.copy(
                    sectionLoading = false,
                    sectionError = "unreachable"
                )
            }
        }
    }

    /** Which section request is current; see [searchGeneration]. */
    private var sectionGeneration = 0

    /** A section's next page (§7), appended to what is already shown. */
    fun loadMoreSection() {
        val repo = repository ?: return
        if (uiState.section == null) return
        val cursor = uiState.sectionCursor ?: return
        if (uiState.sectionLoading) return

        uiState = uiState.copy(sectionLoading = true)
        viewModelScope.launch {
            val result = repo.channels(
                query = uiState.sectionQuery.takeIf { it.isNotBlank() },
                category = uiState.section?.category,
                sort = uiState.sectionSort,
                cursor = cursor,
                limit = SECTION_PAGE
            )
            uiState = if (result is ApiResult.Ok) {
                uiState.copy(
                    sectionItems = GoodPostCodec.merge(uiState.sectionItems, result.value.items) { it.id },
                    sectionCursor = result.value.nextCursor,
                    sectionLoading = false
                )
            } else {
                uiState.copy(sectionLoading = false)
            }
        }
    }

    /** A term typed on the See-all screen (§7), searched shortly after typing stops. */
    fun onSectionQueryChange(value: String) {
        uiState = uiState.copy(sectionQuery = value)
        sectionSearchJob?.cancel()
        if (repository == null) return
        sectionSearchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            loadSection()
        }
    }

    private var sectionSearchJob: Job? = null

    /**
     * Re-sort the See-all screen (§7).
     *
     * The section's order changes in place — the screen stays where it is and the
     * same list comes back in another order. The title keeps naming the section
     * it was opened from, because that is still where the reader is; only the
     * order under it has moved.
     */
    fun selectSectionSort(sort: String) {
        uiState = uiState.copy(
            sectionSort = sort,
            sectionItems = emptyList(),
            sectionCursor = null
        )
        loadSection()
    }

    /**
     * The term as it is typed, searched for shortly after typing stops (§7).
     *
     * Explore used to wait for the keyboard's search key, which made the screen
     * feel like a form: type, submit, look. §7 asks for the other behaviour — the
     * list answers while the reader is still narrowing the words — and the reason
     * a short pause is part of that is cost rather than taste: one request per
     * keystroke on a list that is cursor-paged would be several pages fetched and
     * thrown away for one term.
     */
    fun onQueryChange(value: String) {
        uiState = uiState.copy(query = value)
        scheduleSearch()
    }

    /** The pending debounced search, cancelled whenever a newer one replaces it. */
    private var searchJob: Job? = null

    private fun scheduleSearch() {
        searchJob?.cancel()
        // With nothing to search WITH there is nothing to wait for: the timer
        // exists to spare the server, and `search()` would return immediately
        // anyway. A build with no backend configured keeps its term in the box
        // and asks nothing.
        if (repository == null) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            search()
        }
    }


    /**
     * Search channels by name and description (§6, §7).
     *
     * The answer is checked against what is in the box before it is shown, and
     * the check is the point of doing this as a typed-ahead search: two requests
     * can be in flight at once (a slow "remi" and a fast "reminder"), and
     * whichever replies LAST would otherwise win. A list that answers "reminder"
     * with matches for "remi" looks like a broken search rather than a slow one.
     * Results for a term the reader has already moved on from are dropped, and
     * the loading flag is left alone because the newer request owns it.
     */
    fun search() {
        val repo = repository ?: return
        // An explicit search replaces any debounced one that has not fired yet.
        searchJob?.cancel()
        val query = uiState.query
        val generation = ++searchGeneration

        uiState = uiState.copy(exploreLoading = true, exploreError = null)
        viewModelScope.launch {
            // No `sort` and no `category`: this screen is a search result, and
            // its one order is the server's own default. The category strip that
            // used to send one is gone (§7) — discovery is the Channels screen
            // now, and a term plus a category was two filters answering a
            // question nobody had asked on a screen with no results yet.
            when (val result = repo.channels(query = query)) {
                is ApiResult.Ok -> {
                    if (generation != searchGeneration) return@launch
                    uiState = uiState.copy(
                        explore = result.value.items,
                        exploreCursor = result.value.nextCursor,
                        exploreLoading = false,
                        exploreError = null
                    )
                }

                is ApiResult.Failed -> {
                    if (generation != searchGeneration) return@launch
                    uiState = uiState.copy(
                        exploreLoading = false,
                        exploreError = result.code
                    )
                }

                ApiResult.Unreachable -> {
                    if (generation != searchGeneration) return@launch
                    uiState = uiState.copy(
                        exploreLoading = false,
                        exploreError = "unreachable"
                    )
                }
            }
        }
    }

    /**
     * Which search is current. Incremented on every request; a reply whose number
     * is no longer the latest is a reply to a question that has been replaced.
     */
    private var searchGeneration = 0

    fun loadMoreChannels() {
        val repo = repository ?: return
        val cursor = uiState.exploreCursor ?: return
        if (uiState.exploreLoading) return

        uiState = uiState.copy(exploreLoading = true)
        viewModelScope.launch {
            val result = repo.channels(
                query = uiState.query,
                cursor = cursor
            )
            uiState = if (result is ApiResult.Ok) {
                uiState.copy(
                    explore = GoodPostCodec.merge(uiState.explore, result.value.items) { it.id },
                    exploreCursor = result.value.nextCursor,
                    exploreLoading = false
                )
            } else {
                uiState.copy(exploreLoading = false)
            }
        }
    }

    // ── Search inside a channel (§9) ─────────────────────────────────────

    /**
     * Open the search screen for one channel, empty.
     *
     * Reset on the way in rather than carried over: a search belongs to the
     * channel it was run in, and arriving at a new one with the last channel's
     * term still in the box would show results that are not this channel's §9
     * read.
     */
    fun openChannelSearch(channelId: String) {
        uiState = uiState.copy(channelSearchQuery = "", messageCode = null)
            .clearedOfChannelSearch()
        open(GoodPostScreen.ChannelSearch(channelId))
    }

    /**
     * The in-channel term as it is typed (§9).
     *
     * Debounced for the same reason Explore's is, and stale answers are dropped
     * the same way: the results of a term that has been edited away must never
     * land over the results for the term actually in the box.
     */
    fun onChannelSearchQueryChange(value: String) {
        uiState = uiState.copy(channelSearchQuery = value, messageCode = null)

        channelSearchJob?.cancel()
        if (value.isBlank()) {
            // Clearing the box is not a search to run in a moment: the screen
            // goes back to waiting immediately, with no request at all.
            uiState = uiState.clearedOfChannelSearch()
            return
        }

        // Nothing to search with; see [scheduleSearch].
        if (repository == null) return

        channelSearchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            searchChannelPosts()
        }
    }

    /** The pending debounced in-channel search. */
    private var channelSearchJob: Job? = null

    /**
     * Search this channel's posts for what is in the box now.
     *
     * An empty term clears the results instead of asking the server for
     * everything: "no term" is not a search, and returning the whole channel
     * would make the box look like it had found something when the reader has
     * asked for nothing yet.
     */
    fun searchChannelPosts() {
        val repo = repository ?: return
        val channelId = (uiState.screen as? GoodPostScreen.ChannelSearch)?.channelId ?: return
        val term = uiState.channelSearchQuery.trim()

        if (term.isEmpty()) {
            uiState = uiState.clearedOfChannelSearch()
            return
        }

        channelSearchJob?.cancel()
        val generation = ++channelSearchGeneration

        uiState = uiState.copy(
            channelSearchTerm = term,
            channelSearchLoading = true,
            channelSearchError = null,
            channelSearchCursor = null
        )

        viewModelScope.launch {
            when (val result = repo.searchChannelPosts(channelId, term)) {
                is ApiResult.Ok -> {
                    if (generation != channelSearchGeneration) return@launch
                    uiState = uiState.copy(
                        channelSearchResults = result.value.items,
                        channelSearchCursor = result.value.nextCursor,
                        channelSearchLoading = false,
                        channelSearchError = null
                    )
                }

                is ApiResult.Failed -> {
                    if (generation != channelSearchGeneration) return@launch
                    uiState = uiState.copy(
                        channelSearchLoading = false,
                        channelSearchError = result.code
                    )
                }

                ApiResult.Unreachable -> {
                    if (generation != channelSearchGeneration) return@launch
                    uiState = uiState.copy(
                        channelSearchLoading = false,
                        channelSearchError = "unreachable"
                    )
                }
            }
        }
    }

    /** Which in-channel search is current; see [searchGeneration]. */
    private var channelSearchGeneration = 0

    /** The next page of a search (§26: lazy, cursor-paged, like every list). */
    fun loadMoreChannelSearch() {
        val repo = repository ?: return
        val channelId = (uiState.screen as? GoodPostScreen.ChannelSearch)?.channelId ?: return
        val cursor = uiState.channelSearchCursor ?: return
        val term = uiState.channelSearchTerm.takeIf { it.isNotBlank() } ?: return
        if (uiState.channelSearchLoadingMore) return

        uiState = uiState.copy(channelSearchLoadingMore = true)
        viewModelScope.launch {
            when (val result = repo.searchChannelPosts(channelId, term, cursor)) {
                is ApiResult.Ok -> uiState = uiState.copy(
                    channelSearchResults = GoodPostCodec.merge(
                        uiState.channelSearchResults,
                        result.value.items
                    ) { it.id },
                    channelSearchCursor = result.value.nextCursor,
                    channelSearchLoadingMore = false
                )

                else -> uiState = uiState.copy(channelSearchLoadingMore = false)
            }
        }
    }

    // ── Administrator (§16, §19, §21) ────────────────────────────────────

    /**
     * The tab's way in for whoever runs a channel (§15, §16).
     *
     * Signed out it is the sign-in; a super administrator goes straight to
     * creating a channel, because that is the only thing this control is for once
     * the account is known — their channels are already on the tab. A channel
     * administrator is not offered it at all ([GoodPostUiState.canCreateChannel]),
     * which the screen decides.
     */
    fun openAdmin() {
        if (uiState.admin == null) {
            open(GoodPostScreen.AdminLogin)
            return
        }
        startCreateChannel()
    }

    fun onAdminEmailChange(value: String) {
        uiState = uiState.copy(adminEmail = value, messageCode = null)
    }

    fun onAdminPasswordChange(value: String) {
        uiState = uiState.copy(adminPassword = value, messageCode = null)
    }

    fun onCreatorChannelNameChange(value: String) {
        uiState = uiState.copy(creatorChannelName = value, messageCode = null)
    }

    /** Dismiss the returning-creator greeting (§16). */
    fun clearWelcome() {
        uiState = uiState.copy(welcomeEmail = null)
    }

    /** Open or close the "Other ways" panel, which is a choice and not an error. */
    fun toggleOtherWays() {
        uiState = uiState.copy(otherWaysOpen = !uiState.otherWaysOpen, messageCode = null)
    }

    /**
     * Land a signed-in administrator on the screen their account implies.
     *
     * Extracted because three flows now end here — the password form, a Google
     * creator, and an email/password creator who finished naming their channel —
     * and the landing rule is not a screen choice: a channel administrator gets
     * the channel they run and nothing else (§3), while a super administrator
     * gets the tab, whose list IS every channel they may publish to. Three
     * copies of that would be three chances to get it subtly different.
     */
    private fun landAdminSession(session: AdminSession) {
        val destination = if (session.isSuperAdmin) {
            listOf(GoodPostScreen.Home)
        } else {
            session.channelId
                ?.let { listOf(GoodPostScreen.Home, GoodPostScreen.AdminChannel(it)) }
                ?: listOf(GoodPostScreen.Home)
        }

        uiState = uiState.copy(
            admin = session,
            adminPassword = "",
            adminBusy = false,
            messageCode = null,
            selectedChannelIds = emptySet(),
            creatorNeedsChannel = false,
            creatorEmail = null,
            creatorChannelName = "",
            // The sign-in screen is behind us; nothing of it should be waiting
            // open the next time it is reached.
            otherWaysOpen = false,
            backStack = destination
        )

        loadAdminChannels()
        destination.lastOrNull()?.let { screen ->
            if (screen is GoodPostScreen.AdminChannel) {
                loadChannel(screen.channelId)
                loadPosts(screen.channelId)
            }
        }
    }

    /**
     * Continue as a creator with Google (§16).
     *
     * The Activity is passed in rather than read from the application context,
     * because Credential Manager draws its account picker OVER a window: an
     * Application context has none, and the call throws.
     */
    fun creatorSignInWithGoogle(activity: Activity) {
        val repo = repository ?: return
        uiState = uiState.copy(adminBusy = true, creatorGoogleBusy = true, messageCode = null)

        viewModelScope.launch {
            try {
                when (val result = repo.googleCreatorToken(activity)) {
                    // Dismissing the picker is not a failure and gets no message.
                    GoogleSignIn.Cancelled -> uiState = uiState.copy(adminBusy = false)
                    GoogleSignIn.Failed -> uiState = uiState.copy(
                        adminBusy = false,
                        messageCode = "creator_signin_failed"
                    )

                    is GoogleSignIn.Token -> continueCreatorSignIn(repo, result.idToken)
                }
            } finally {
                // Every exit, including the one where `continueCreatorSignIn` threw
                // on a lost connection: the step is over, so whatever it put on the
                // screen comes off. A `finally` rather than four assignments,
                // because the state that matters here is the one where the overlay
                // is still covering the app and nothing will ever clear it.
                uiState = uiState.copy(creatorGoogleBusy = false)
            }
        }
    }

    /**
     * The sign-in screen's ONE action (§16).
     *
     * Two populations reach this form and nothing on the outside tells them
     * apart: an administrator provisioned by the deployment — the super
     * administrator from the environment, or a channel administrator a super
     * administrator created — holds a password that lives on the SERVER, while
     * a creator holds a Firebase account they made themselves. The screen used
     * to offer them as two buttons, which asked the person signing in to know
     * which kind of account they had, and left a super administrator who
     * pressed the wrong one reading "invalid credentials" about a password that
     * was correct.
     *
     * So the app finds out instead, in the order that costs the least:
     *
     *  1. The server's own credentials. That is the smaller population and the
     *     one whose answer is final, so a correct super-admin password signs in
     *     on the first call with nothing else tried.
     *  2. Anything else means "not a provisioned account", and the Firebase half
     *     runs — where a creator is signed up, or signed in if the address
     *     already has an account.
     *
     * Two refusals deliberately STOP rather than fall through, because the
     * Firebase attempt cannot succeed and would only delay the truth: an account
     * that exists and is locked or switched off, and a server that cannot be
     * reached (Firebase needs the network too, and a second timeout is a longer
     * wait for the same answer).
     */
    fun signIn() {
        val repo = repository ?: return
        val email = normalizeEmailInput(uiState.adminEmail)
        val password = uiState.adminPassword

        if (email == null || password.isEmpty()) {
            uiState = uiState.copy(messageCode = "invalid_credentials")
            return
        }

        uiState = uiState.copy(adminBusy = true, messageCode = null)
        viewModelScope.launch {
            when (val result = repo.adminLogin(email, password)) {
                // Where the account lands is the account's own shape rather
                // than a screen choice; see [landAdminSession].
                is ApiResult.Ok -> landAdminSession(result.value)

                is ApiResult.Failed -> {
                    // A refusal this contract cannot produce is not a credential
                    // problem: it means the backend is a different one. Naming
                    // that is the difference between "your password is wrong"
                    // and "this is not the server you think it is", and only one
                    // of those is worth acting on.
                    val code = if (signInHitAnotherContract(result.status, result.code)) {
                        "admin_unavailable"
                    } else {
                        result.code
                    }

                    if (adminRefusalIsFinal(goodPostErrorFor(code))) {
                        uiState = uiState.copy(adminBusy = false, messageCode = result.code)
                    } else {
                        // Not a provisioned account. Firebase decides the rest:
                        // sign up if the address is new, sign in if it is not.
                        creatorSignInWithEmail(repo, email, password, signUp = true)
                    }
                }

                ApiResult.Unreachable -> uiState = uiState.copy(
                    adminBusy = false,
                    messageCode = "unreachable"
                )
            }
        }
    }

    /**
     * The Firebase half of [signIn], with the credentials already validated.
     *
     * `signUp` decides which way round Firebase tries its two calls; it does not
     * decide whether an account exists, because only Firebase knows that.
     */
    private suspend fun creatorSignInWithEmail(
        repo: GoodPostRepository,
        email: String,
        password: String,
        signUp: Boolean
    ) {
        val token = repo.creatorToken(email, password, signUp)
        if (token == null) {
            // One message for a wrong password and for an address that
            // cannot be signed up: the distinction is a way to discover
            // which addresses exist.
            uiState = uiState.copy(adminBusy = false, messageCode = "invalid_credentials")
            return
        }
        continueCreatorSignIn(repo, token)
    }

    /**
     * Back from the name-a-channel step to the credentials above it (§16).
     *
     * The step is not a screen in the navigation stack, so it has no pop of its
     * own: without this, the arrow on it leaves the whole flow and the half-made
     * creator state is still set for the next visit, which re-opens the step
     * over a sign-in that never happened.
     *
     * The email and password are kept — they are what somebody returning here
     * came back to change, and the identity they signed in with is dropped so the
     * next attempt really does use whatever they type now.
     */
    fun backToSignIn() {
        creatorIdToken = null
        uiState = uiState.copy(creatorNeedsChannel = false, messageCode = null)
    }

    /** The shared second half: trade the Firebase token for a session or a step. */
    private suspend fun continueCreatorSignIn(repo: GoodPostRepository, idToken: String) {
        when (val result = repo.creatorLogin(idToken)) {
            is ApiResult.Ok -> when (val value = result.value) {
                is CreatorSignIn.Session -> {
                    creatorIdToken = null
                    // §16: `Session` (rather than `NeedsChannel`) means this
                    // account was already here, so the reader is greeted instead
                    // of dropped onto a list with no word about what happened.
                    // It is the same branch for both ways in — Google and the
                    // email/password form — because "the account already exists"
                    // is the same fact in both.
                    uiState = uiState.copy(welcomeEmail = value.session.email)
                    landAdminSession(value.session)
                }

                is CreatorSignIn.NeedsChannel -> {
                    // Held in memory, and only until the channel exists. It is a
                    // credential with minutes of life, so it is not written to
                    // disk — the session that replaces it is.
                    creatorIdToken = idToken
                    uiState = uiState.copy(
                        adminBusy = false,
                        creatorNeedsChannel = true,
                        creatorEmail = value.email,
                        creatorChannelName = uiState.creatorChannelName,
                        messageCode = null
                    )
                }
            }

            is ApiResult.Failed -> uiState = uiState.copy(
                adminBusy = false,
                messageCode = if (signInHitAnotherContract(result.status, result.code)) {
                    "admin_unavailable"
                } else {
                    result.code
                }
            )

            ApiResult.Unreachable -> uiState = uiState.copy(
                adminBusy = false,
                messageCode = "unreachable"
            )
        }
    }

    /**
     * Create the creator's channel, which is also what creates their account.
     *
     * The token is dropped either way: on success the session replaces it, and
     * on failure it is not reusable — the next attempt re-signs-in, which is
     * also what refreshes it.
     */
    fun creatorCreateChannel() {
        val repo = repository ?: return
        val token = creatorIdToken
        val name = uiState.creatorChannelName.trim()

        if (token == null) {
            uiState = uiState.copy(creatorNeedsChannel = false, messageCode = "invalid_credentials")
            return
        }
        if (name.isEmpty()) {
            uiState = uiState.copy(messageCode = "channel_name_required")
            return
        }

        uiState = uiState.copy(adminBusy = true, messageCode = null)
        viewModelScope.launch {
            when (val result = repo.creatorCreateChannel(token, name)) {
                is ApiResult.Ok -> {
                    creatorIdToken = null
                    landAdminSession(result.value)
                }

                is ApiResult.Failed -> {
                    // A token the server refused cannot be retried with: it is the
                    // same string on every attempt, so the reader would be left
                    // pressing Continue against a dead credential forever — which
                    // is exactly what "Something went wrong" on every retry was.
                    //
                    // The flow goes back to its first half instead, where the
                    // button that obtains a NEW token is, and the typed channel
                    // name is kept so signing in again does not cost them their
                    // typing.
                    //
                    // "Refused" is read from the ONE mapping of codes to meanings
                    // rather than from a second list of strings here: every code
                    // that words as "your session ended" is a credential this
                    // flow must stop holding, and a list of its own would be a
                    // second place to remember one.
                    val tokenRefused = goodPostErrorFor(result.code) == GoodPostError.SessionExpired
                    if (tokenRefused) creatorIdToken = null

                    uiState = uiState.copy(
                        adminBusy = false,
                        messageCode = result.code,
                        creatorNeedsChannel = !tokenRefused
                    )
                }

                ApiResult.Unreachable -> uiState = uiState.copy(adminBusy = false, messageCode = "unreachable")
            }
        }
    }

    fun adminSignOut() {
        val repo = repository
        // Read before the session is forgotten: revocation needs it, and the store
        // is about to be cleared.
        val refreshToken = uiState.admin?.refreshToken
        repo?.forgetAdminSession()
        if (refreshToken != null) {
            // Best effort. The screen changes now; the server hears about it after.
            viewModelScope.launch { repo?.revokeAdminSession(refreshToken) }
        }
        // The CREATOR identity goes, and the reader's stays (§16). Signing out
        // of a channel must not touch the uid this device's followed channels
        // hang from — see [GoodPostIdentity].
        viewModelScope.launch { repo?.creatorSignOut() }
        GoodPostImages.clear()
        uiState = uiState.copy(
            admin = null,
            adminEmail = "",
            adminPassword = "",
            otherWaysOpen = false,
            adminChannels = emptyList(),
            selectedChannelIds = emptySet(),
            selectedPostIds = emptySet(),
            backStack = listOf(GoodPostScreen.Home)
        ).clearedOfChannelSearch()
        // The editor belonged to an account that is no longer signed in, and the
        // channel it was aimed at may not even be in the list the tab now shows.
        resetComposer()
        refreshChannels()
    }

    fun loadAdminChannels() {
        val repo = repository ?: return
        // Signed in is the only precondition a caller can check. The token itself
        // belongs to the repository, which renews it when it has lapsed — a stale
        // copy held here is what made every call fail ten minutes in.
        if (uiState.admin == null) return

        uiState = uiState.copy(adminChannelsLoading = true)
        viewModelScope.launch {
            val result = repo.adminChannels()
            uiState = if (result is ApiResult.Ok) {
                uiState.copy(adminChannels = result.value, adminChannelsLoading = false)
            } else {
                // Never a silent empty list. A failed load used to leave
                // `adminChannels` empty, which the dashboard renders as "No
                // channels. Create one to start publishing." — telling an
                // administrator their channels are gone when the request simply
                // failed.
                uiState.copy(
                    adminChannelsLoading = false,
                    messageCode = adminFailureCode(result)
                )
            }
        }
    }

    // ── Advertisement management (§15, §16) ──────────────────────────────
    //
    // Only a super administrator holds `ads.read`/`ads.manage`, and the server
    // re-checks that on every one of these calls. The app gates the entry so a
    // channel administrator is never offered a screen that would fail, but the
    // authority is never the app's.

    /**
     * Open the advertisement manager (§15).
     *
     * Gated on the role this session reports, which is a convenience and not a
     * permission: the server refuses every ad route to anyone else regardless of
     * what this draws.
     */
    fun openAds() {
        if (uiState.admin?.isSuperAdmin != true) return
        open(GoodPostScreen.Ads)
        loadAdminAds()
    }


    /** Every card, active or not, for the manager list (§15). */
    fun loadAdminAds() {
        val repo = repository ?: return
        if (uiState.admin == null) return

        uiState = uiState.copy(adminAdsLoading = true)
        viewModelScope.launch {
            val result = repo.adminAds()
            uiState = when (result) {
                is ApiResult.Ok -> uiState.copy(
                    adminAds = result.value,
                    adminAdsLoading = false
                )
                // Never silently empty: an empty list here would read as "there
                // are no cards", which is a statement about the platform rather
                // than a failed request.
                else -> uiState.copy(
                    adminAdsLoading = false,
                    messageCode = adminFailureCode(result)
                )
            }
        }
    }

    /**
     * Open the editor for a card, or a blank one to create (§16).
     *
     * Everything the form draws comes from what is passed in, so tapping a row
     * never sends a second read to render the same data the list already holds.
     */
    fun openAdForm(ad: GoodPostAd?) {
        uiState = uiState.copy(
            adFormOpen = true,
            adFormId = ad?.id,
            adFormContentType = ad?.contentType ?: "text",
            adFormText = ad?.text.orEmpty(),
            adFormTargetUrl = ad?.targetUrl.orEmpty(),
            adFormShowInChannels = ad?.showInChannels ?: true,
            adFormShowInExplore = ad?.showInExplore ?: true,
            adFormEnabled = ad?.enabled ?: true,
            adFormPriority = (ad?.priority ?: 100).toString(),
            adFormStartsAt = ad?.startsAt,
            adFormExpiresAt = ad?.expiresAt,
            // An existing card's schedule is shown as custom unless it happens
            // to be "no expiry": there is no way to tell a one-week card from a
            // one-month one by looking at an end date, and guessing would relabel
            // what the administrator actually set.
            adFormDuration = if (ad?.expiresAt == null) {
                AdDuration.NoExpiry
            } else {
                AdDuration.Custom
            },
            adFormTextColor = ad?.textColor ?: AD_DEFAULT_TEXT_COLOR,
            adFormBackgroundColor = ad?.backgroundColor ?: AD_DEFAULT_BACKGROUND_COLOR,
            adFormImageFit = ad?.imageFit ?: "cover",
            adFormFocusX = ad?.imageFocusX ?: 0.5f,
            adFormFocusY = ad?.imageFocusY ?: 0.5f,
            adFormImage = null,
            adFormExistingImageUrl = ad?.imageUrl,
            messageCode = null
        )
    }

    /** A text card's colour, from the editor's palette (§12). */
    fun onAdTextColorChange(color: String) {
        uiState = uiState.copy(adFormTextColor = color, messageCode = null)
    }

    /** The card's own surface, from the editor's palette (§12). */
    fun onAdBackgroundColorChange(color: String) {
        uiState = uiState.copy(adFormBackgroundColor = color, messageCode = null)
    }

    /** How an image card's picture fills its card (§12). */
    fun onAdImageFitChange(fit: String) {
        uiState = uiState.copy(
            adFormImageFit = if (fit == "contain") "contain" else "cover",
            messageCode = null
        )
    }

    /** The crop's centre, from the editor's framing step (§12). */
    fun onAdFocusChange(x: Float, y: Float) {
        uiState = uiState.copy(
            adFormFocusX = x.coerceIn(0f, 1f),
            adFormFocusY = y.coerceIn(0f, 1f),
            messageCode = null
        )
    }

    fun onAdContentTypeChange(value: String) {
        uiState = uiState.copy(adFormContentType = value, messageCode = null)
    }

    fun onAdTextChange(value: String) {
        uiState = uiState.copy(adFormText = value, messageCode = null)
    }

    fun onAdTargetUrlChange(value: String) {
        uiState = uiState.copy(adFormTargetUrl = value, messageCode = null)
    }

    fun onAdPriorityChange(value: String) {
        // Digits only: a priority is a small non-negative integer, and letting
        // letters into the box would only be refused later at save time.
        uiState = uiState.copy(adFormPriority = value.filter { it.isDigit() }, messageCode = null)
    }

    /**
     * Set how long the card runs for (§11).
     *
     * The end is computed from NOW rather than from the start: a card that says
     * "one week" is one week from the moment it was set, which is what the person
     * choosing it means. The start is left null ("now") — a card that has been
     * scheduled has an explicit start from the calendar, and this is the ordinary
     * "start it as soon as I save" path.
     */
    fun onAdDurationChange(duration: AdDuration) {
        uiState = uiState.copy(
            adFormDuration = duration,
            adFormExpiresAt = duration.expiryFrom(nowMillis()),
            adFormStartsAt = null,
            messageCode = null
        )
    }

    /**
     * A start date chosen from the calendar, or null to clear it.
     *
     * Cleared rather than left as it was: the picker's dismiss button is a
     * decision, and a date field that cannot be emptied is one that can only be
     * changed by picking another.
     */
    fun onAdStartDatePicked(millis: Long?) {
        uiState = uiState.copy(
            adFormStartsAt = millis?.let(::isoFromLocalStart),
            messageCode = null
        )
    }

    /**
     * An end date chosen from the calendar.
     *
     * Sets [adFormDuration] to [AdDuration.Custom]: the pills describe a length
     * chosen in advance, and this one was not.
     */
    fun onAdExpiryDatePicked(millis: Long?) {
        uiState = uiState.copy(
            adFormDuration = AdDuration.Custom,
            adFormExpiresAt = millis?.let(::isoFromLocalStart),
            messageCode = null
        )
    }

    /** The platform's badge, on the channel form (§7). Super administrators only. */
    fun toggleChannelFormVerified() {
        if (uiState.admin?.isSuperAdmin != true) return
        uiState = uiState.copy(channelFormVerified = !uiState.channelFormVerified)
    }

    fun toggleAdShowInChannels() {
        uiState = uiState.copy(adFormShowInChannels = !uiState.adFormShowInChannels)
    }

    fun toggleAdShowInExplore() {
        uiState = uiState.copy(adFormShowInExplore = !uiState.adFormShowInExplore)
    }

    fun toggleAdEnabled() {
        uiState = uiState.copy(adFormEnabled = !uiState.adFormEnabled)
    }

    /**
     * Which pick the in-flight card-image upload belongs to — its uri.
     *
     * The same guard the channel form's avatar uses, for the same reason: two
     * picks can be travelling at once and one field holds the answer, so an older
     * upload's reply must not overwrite a newer choice.
     */
    private var adImagePick: String? = null

    /**
     * Attach a picture to the card being written (§11).
     *
     * Uploaded through the EXISTING handshake — request, PUT, confirm — exactly as
     * a channel icon or a post attachment is, so an advertisement image is not a
     * second way for bytes to enter the product. The kind check refuses a video:
     * the card draws a picture, and a video has no meaning in one.
     */
    fun onAdImagePicked(attachment: GoodPostAttachment) {
        val repo = repository ?: return
        if (uiState.admin == null) return

        if (attachment.kind != "image") {
            uiState = uiState.copy(messageCode = "unsupported_media_type")
            return
        }

        adImagePick = attachment.uri
        uiState = uiState.copy(adFormImage = attachment, messageCode = null)

        viewModelScope.launch {
            val result = repo.adminUploadMedia(attachment)
            val stillChosen = adImagePick == attachment.uri

            when (result) {
                is ApiResult.Ok -> if (stillChosen) {
                    uiState = uiState.copy(
                        adFormImage = uiState.adFormImage?.copy(
                            state = GoodPostUploadState.Ready(result.value.id)
                        )
                    )
                }

                is ApiResult.Failed -> uiState = uiState.copy(
                    composerMediaAvailable = uiState.composerMediaAvailable &&
                        result.code != "media_unavailable",
                    adFormImage = if (stillChosen) {
                        uiState.adFormImage?.copy(state = GoodPostUploadState.Failed(result.code))
                    } else {
                        uiState.adFormImage
                    }
                )

                ApiResult.Unreachable -> if (stillChosen) {
                    uiState = uiState.copy(
                        adFormImage = uiState.adFormImage?.copy(
                            state = GoodPostUploadState.Failed("unreachable")
                        )
                    )
                }
            }
        }
    }

    /** Drop the picture chosen for this card. */
    fun removeAdImage() {
        adImagePick = null
        uiState = uiState.copy(adFormImage = null, messageCode = null)
    }

    /** Leave the editor without saving. */
    fun cancelAdForm() {
        adImagePick = null
        uiState = uiState.copy(adFormOpen = false, adFormImage = null, messageCode = null)
    }

    /**
     * Save the card being written (§11).
     *
     * The same field checks the channel form makes, and for the same reason: a
     * card that cannot be valid is refused here in words rather than sent to be
     * refused by the server after a round trip.
     */
    fun submitAdForm() {
        val repo = repository ?: return
        if (uiState.admin == null) return

        val contentType = uiState.adFormContentType
        val text = uiState.adFormText.trim()
        if (contentType == "text" && text.isBlank()) {
            uiState = uiState.copy(messageCode = "ad_content_required")
            return
        }

        val image = uiState.adFormImage
        if (image != null && image.mediaId == null) {
            uiState = uiState.copy(
                messageCode = if (image.state is GoodPostUploadState.Failed) {
                    "attachment_failed"
                } else {
                    "attachment_uploading"
                }
            )
            return
        }
        // A new image card needs a picture; an EDIT may keep the one it has.
        if (contentType == "image" &&
            image?.mediaId == null &&
            uiState.adFormExistingImageUrl == null
        ) {
            uiState = uiState.copy(messageCode = "ad_image_required")
            return
        }

        val body = JSONObject().apply {
            put("contentType", contentType)
            put("text", if (contentType == "text") text else JSONObject.NULL)
            put("targetUrl", uiState.adFormTargetUrl.trim().ifBlank { null } ?: JSONObject.NULL)
            put("showInChannels", uiState.adFormShowInChannels)
            put("showInExplore", uiState.adFormShowInExplore)
            put("enabled", uiState.adFormEnabled)
            put("priority", uiState.adFormPriority.toIntOrNull() ?: 100)
            put("startsAt", uiState.adFormStartsAt ?: JSONObject.NULL)
            put("expiresAt", uiState.adFormExpiresAt ?: JSONObject.NULL)
            // How the card LOOKS. Sent for both types: the server stores all of
            // it, and a card that switched from text to image and back keeps the
            // colour it was given rather than resetting to the default.
            put("textColor", uiState.adFormTextColor)
            put("backgroundColor", uiState.adFormBackgroundColor)
            put("imageFit", uiState.adFormImageFit)
            put("imageFocusX", uiState.adFormFocusX.toDouble())
            put("imageFocusY", uiState.adFormFocusY.toDouble())
            // Sent only when a NEW picture is being claimed; omitted on an edit
            // that keeps the existing one, which the server reads as "leave it".
            image?.mediaId?.let { put("mediaId", it) }
        }

        val editingId = uiState.adFormId
        uiState = uiState.copy(adminBusy = true, messageCode = null)

        viewModelScope.launch {
            val result = if (editingId == null) {
                repo.adminCreateAd(body)
            } else {
                repo.adminUpdateAd(editingId, body)
            }

            uiState = when (result) {
                is ApiResult.Ok -> uiState.copy(
                    adminBusy = false,
                    adFormOpen = false,
                    adFormImage = null
                )
                is ApiResult.Failed -> uiState.copy(
                    adminBusy = false,
                    messageCode = adminFailureCode(result)
                )
                ApiResult.Unreachable -> uiState.copy(adminBusy = false, messageCode = "unreachable")
            }

            if (result is ApiResult.Ok) {
                adImagePick = null
                loadAdminAds()
                // The reader-facing carousels are refetched too, so a card an
                // administrator has just enabled or disabled is reflected without
                // leaving and reopening the tab.
                loadAds()
                loadExploreAds()
            }
        }
    }

    /** Delete a card, and its picture, for good (§11). */
    fun deleteAd(adId: String) {
        val repo = repository ?: return
        if (uiState.admin == null) return

        uiState = uiState.copy(adminBusy = true, messageCode = null)
        viewModelScope.launch {
            val result = repo.adminDeleteAd(adId)
            uiState = when (result) {
                is ApiResult.Ok -> uiState.copy(adminBusy = false)
                is ApiResult.Failed -> uiState.copy(
                    adminBusy = false,
                    messageCode = adminFailureCode(result)
                )
                ApiResult.Unreachable -> uiState.copy(adminBusy = false, messageCode = "unreachable")
            }
            if (result is ApiResult.Ok) {
                loadAdminAds()
                loadAds()
                loadExploreAds()
            }
        }
    }

    /**
     * Open the account's own channel, from the tab's menu (§15).
     *
     * The PROFILE page rather than the feed: it is the same screen a reader gets
     * for any channel — avatar, name, handle, description, followers, views, the
     * media strip — with the one addition an owner sees on it, which is the way to
     * the edit form (§11). Nothing about it is a second layout for one account.
     */
    fun openMyChannel() {
        val own = uiState.ownChannel ?: return
        openChannelInfo(own.id)
    }

    /**
     * A channel's information page (§11).
     *
     * The channel is re-read on the way in, because this page is where a change to
     * a channel's own profile — a new name, a new avatar — has to be visible: the
     * row it was reached from may have been loaded before that change, and a
     * profile page showing the previous picture is the bug this exists to fix
     * (§19).
     */
    fun openChannelInfo(channelId: String) {
        val known = uiState.adminChannels.firstOrNull { it.id == channelId }
            ?: uiState.channels.firstOrNull { it.id == channelId }
        if (known != null) uiState = uiState.forChannel(known)
        open(GoodPostScreen.ChannelInfo(channelId))
        loadChannel(channelId)
    }

    fun openAdminChannel(channelId: String) {
        uiState = uiState.forChannel(
            uiState.adminChannels.firstOrNull { it.id == channelId }
                ?: uiState.channels.firstOrNull { it.id == channelId }
        )
        open(GoodPostScreen.AdminChannel(channelId))
        loadChannel(channelId)
        loadPosts(channelId)
    }

    // ── The channel form (§19, §20) ──────────────────────────────────────

    fun startCreateChannel() {
        uiState = uiState.copy(
            channelFormOpen = true,
            channelFormId = null,
            channelFormName = "",
            channelFormDescription = "",
            channelFormCategory = null,
            channelFormVerified = false,
            // Only a super admin creates a channel together with the account that
            // runs it (§20). A channel admin editing their own channel has no
            // business minting credentials, and the server refuses it anyway.
            channelFormAdminEmail = if (uiState.admin?.isSuperAdmin == true) "" else null,
            channelFormAdminPassword = if (uiState.admin?.isSuperAdmin == true) "" else null,
            channelFormIcon = null,
            channelFormExistingIconUrl = null,
            channelFormIconRemoved = false,
            messageCode = null
        )
    }

    fun startEditChannel(channel: GoodPostChannel) {
        uiState = uiState.copy(
            channelFormOpen = true,
            channelFormId = channel.id,
            channelFormName = channel.name,
            channelFormDescription = channel.description.orEmpty(),
            channelFormCategory = channel.categorySlug,
            channelFormVerified = channel.verified,
            channelFormAdminEmail = null,
            // Empty rather than null on an edit (§20): the field is OFFERED here,
            // and blank is what "leave the password alone" looks like. A null
            // would hide the field entirely, which is how this form was unable
            // to do the one thing a forgotten channel password needs.
            channelFormAdminPassword = "",
            channelFormIcon = null,
            // Rendered from the same signed URL the list row uses, so the form
            // shows the image the channel actually has rather than a second
            // representation of it that could disagree.
            channelFormExistingIconUrl = channel.iconUrl,
            channelFormIconRemoved = false,
            messageCode = null
        )
    }

    /**
     * Attach an image to the channel being created or edited (§21).
     *
     * Uploaded at once, exactly as a post's media is: by the time Save is
     * tapped, all that is left is to send the media id. A profile image is an
     * image — the kind check refuses a video here, because the avatar is drawn
     * into a circle and a video has no meaning in one.
     */
    /**
     * Which pick the in-flight avatar upload belongs to — the picked file's uri.
     *
     * The form holds ONE image field, and two picks can be in the air at once: a
     * reader who chooses a picture, sees it is the wrong one and chooses again has
     * two uploads travelling and one field. Without this, the FIRST upload's answer
     * arrives second and writes its media id onto the SECOND pick — so the channel
     * is saved with an image nobody chose, which is exactly the "it does not
     * reliably change" this field exists to stop. An answer is applied only while
     * its own pick is still the one in the form.
     */
    private var channelIconPick: String? = null

    fun onChannelIconPicked(attachment: GoodPostAttachment) {
        val repo = repository ?: return
        // Signed in is the only precondition a caller can check. The token itself
        // belongs to the repository, which renews it when it has lapsed — a stale
        // copy held here is what made every call fail ten minutes in.
        if (uiState.admin == null) return

        if (attachment.kind != "image") {
            uiState = uiState.copy(messageCode = "unsupported_media_type")
            return
        }

        channelIconPick = attachment.uri
        uiState = uiState.copy(
            channelFormIcon = attachment,
            // Choosing a new image supersedes a pending removal: the last
            // decision the administrator made is the one that counts.
            channelFormIconRemoved = false,
            messageCode = null
        )

        viewModelScope.launch {
            val result = repo.adminUploadMedia(attachment)
            // Only the pick still in the form may be moved by this answer — see
            // [channelIconPick].
            val stillChosen = channelIconPick == attachment.uri

            when (result) {
                is ApiResult.Ok -> if (stillChosen) {
                    uiState = uiState.copy(
                        channelFormIcon = uiState.channelFormIcon?.copy(
                            state = GoodPostUploadState.Ready(result.value.id)
                        )
                    )
                }

                is ApiResult.Failed -> uiState = uiState.copy(
                    // The refusal is about STORAGE, so it counts even when this pick
                    // has been replaced: the next pick would be refused the same way.
                    composerMediaAvailable = uiState.composerMediaAvailable &&
                        result.code != "media_unavailable",
                    channelFormIcon = if (stillChosen) {
                        uiState.channelFormIcon?.copy(
                            state = GoodPostUploadState.Failed(result.code)
                        )
                    } else {
                        uiState.channelFormIcon
                    }
                )

                ApiResult.Unreachable -> if (stillChosen) {
                    uiState = uiState.copy(
                        channelFormIcon = uiState.channelFormIcon?.copy(
                            state = GoodPostUploadState.Failed("unreachable")
                        )
                    )
                }
            }
        }
    }

    /** Drop the image chosen for this channel, and ask for any existing one to go. */
    fun removeChannelIcon() {
        // The pick is gone, so an upload still travelling for it must not put a
        // media id back into a form that no longer holds that image.
        channelIconPick = null
        uiState = uiState.copy(
            channelFormIcon = null,
            // Only a saved channel has an image to remove. On the create form
            // there is nothing on the server yet, so this just clears the pick.
            channelFormIconRemoved = uiState.channelFormExistingIconUrl != null,
            messageCode = null
        )
    }

    /** Undo a pending removal, keeping the image the channel already has. */
    fun keepChannelIcon() {
        uiState = uiState.copy(channelFormIconRemoved = false, messageCode = null)
    }

    fun onChannelFormNameChange(value: String) {
        uiState = uiState.copy(channelFormName = value)
    }

    fun onChannelFormDescriptionChange(value: String) {
        uiState = uiState.copy(channelFormDescription = value)
    }

    fun onChannelFormCategoryChange(slug: String?) {
        uiState = uiState.copy(channelFormCategory = slug)
    }

    fun onChannelFormAdminEmailChange(value: String) {
        uiState = uiState.copy(channelFormAdminEmail = value)
    }

    fun onChannelFormAdminPasswordChange(value: String) {
        uiState = uiState.copy(channelFormAdminPassword = value)
    }

    fun cancelChannelForm() {
        // The picked image is dropped with the form. Its upload is never
        // claimed, so the server's own sweep collects the object — the same
        // reasoning as the post composer's attachments.
        uiState = uiState.copy(
            channelFormOpen = false,
            channelFormIcon = null,
            channelFormIconRemoved = false,
            messageCode = null
        )
    }

    fun submitChannelForm() {
        val repo = repository ?: return
        // Signed in is the only precondition a caller can check. The token itself
        // belongs to the repository, which renews it when it has lapsed — a stale
        // copy held here is what made every call fail ten minutes in.
        if (uiState.admin == null) return
        val name = uiState.channelFormName.trim()
        if (name.isBlank()) {
            uiState = uiState.copy(messageCode = "invalid_request")
            return
        }

        // The password being minted for the channel's administrator (§10),
        // checked HERE rather than only by the server.
        //
        // Not a second authority — the server still refuses a short one, and it
        // is the only one that can be trusted. What this buys is the answer
        // coming back with the RULE in it: the same message either way, but this
        // one can be produced without a round trip, and it is what stops a
        // correct-looking password being refused three times in a row by a form
        // that never said what it wanted. See [BuildConfig.ADMIN_MIN_PASSWORD_LENGTH].
        val mintedPassword = uiState.channelFormAdminPassword
        if (mintedPassword != null &&
            mintedPassword.isNotBlank() &&
            adminPasswordTooShort(mintedPassword, BuildConfig.ADMIN_MIN_PASSWORD_LENGTH)
        ) {
            uiState = uiState.copy(messageCode = "weak_password")
            return
        }

        // The image is part of the SAME save, and an upload that has not landed
        // blocks it — the alternative is a channel saved with the picture the
        // administrator chose silently missing.
        val icon = uiState.channelFormIcon
        // Captured before the save, because the answer below runs against a state
        // that has already been cleaned up: what this save CHANGED is a fact about
        // the form as it was submitted, not about the form afterwards.
        val iconChanged = icon != null || uiState.channelFormIconRemoved
        val previousIconUrl = uiState.channelFormExistingIconUrl
        if (icon != null && icon.mediaId == null) {
            uiState = uiState.copy(
                messageCode = if (icon.state is GoodPostUploadState.Failed) {
                    "attachment_failed"
                } else {
                    "attachment_uploading"
                }
            )
            return
        }

        val body = JSONObject().apply {
            put("name", name)
            put("description", uiState.channelFormDescription.trim().ifBlank { JSONObject.NULL })
            put("categorySlug", uiState.channelFormCategory ?: JSONObject.NULL)
            // Sent only by a super administrator; the server drops it from
            // anyone else, so sending it from their form would be noise.
            if (uiState.admin?.isSuperAdmin == true) {
                put("verified", uiState.channelFormVerified)
            }

            uiState.channelFormAdminEmail?.takeIf { it.isNotBlank() }
                ?.let { put("adminEmail", it.trim().lowercase()) }
            uiState.channelFormAdminPassword?.takeIf { it.isNotBlank() }
                ?.let { put("adminPassword", it) }

            // The three-way field, sent only when the image is actually being
            // changed: absent means "leave it alone", which is what renaming a
            // channel must mean. Sending a value unconditionally would re-claim
            // an upload that is already claimed on the server.
            val mediaId = icon?.mediaId
            when {
                mediaId != null -> put("iconMediaId", mediaId)
                uiState.channelFormIconRemoved -> put("iconMediaId", JSONObject.NULL)
            }
        }

        val editingId = uiState.channelFormId
        uiState = uiState.copy(adminBusy = true, messageCode = null)

        viewModelScope.launch {
            val result = if (editingId == null) {
                repo.adminCreateChannel(body)
            } else {
                repo.adminUpdateChannel(editingId, body)
            }

            uiState = when (result) {
                is ApiResult.Ok -> uiState
                    // The channel the server just saved, taken as the tab's own
                    // copy of it.
                    //
                    // This is what makes a new avatar appear the moment the save
                    // lands (§19). Reloading the two lists is not enough: the
                    // screen BEHIND the form — the feed, or the profile page —
                    // draws `state.channel`, which was read when it was opened and
                    // is not part of either list. It kept the old signed URL until
                    // the channel was closed and reopened, which reads as "my new
                    // picture did not save".
                    .withUpdatedChannel(result.value)
                    .copy(
                        adminBusy = false,
                        channelFormOpen = false,
                        channelFormIcon = null,
                        channelFormIconRemoved = false,
                        channelFormExistingIconUrl = result.value.iconUrl
                    )
                is ApiResult.Failed -> uiState.copy(
                    adminBusy = false,
                    messageCode = adminFailureCode(result)
                )
                ApiResult.Unreachable -> uiState.copy(adminBusy = false, messageCode = "unreachable")
            }
            if (result is ApiResult.Ok) {
                channelIconPick = null

                // The replaced picture is dropped from this device's cache.
                //
                // Not a guess about what changed: replacing a channel's icon
                // DELETES the object it replaced on the server, so the cached copy
                // under that key is a file that no longer exists anywhere — leaving
                // it would be kilobytes kept for an image nothing can point at. It
                // also cannot cause a stale avatar on its own (the new image is a
                // new object key, and therefore a new cache entry), which is why
                // the fix above is the state and not this.
                val replaced = if (iconChanged) previousIconUrl else null
                if (replaced != null) {
                    viewModelScope.launch { GoodPostImages.forget(listOf(replaced)) }
                }

                loadAdminChannels()
                // The public list is refetched too, so a channel an administrator
                // has just created is visible in the same session (§20).
                refreshChannels()
            }
        }
    }

    /**
     * This state with [channel] as the current copy of the row it identifies.
     *
     * One channel appears in up to three places in the tab — the account's own
     * list, the reader's followed list, and the open screen — and an edit that
     * updated some of them would leave the tab disagreeing with itself about a
     * name or an avatar. Rows that are not there stay absent: this replaces a
     * channel, it does not add one.
     */
    private fun GoodPostUiState.withUpdatedChannel(channel: GoodPostChannel): GoodPostUiState {
        fun List<GoodPostChannel>.replaced(): List<GoodPostChannel> =
            map { row -> if (row.id == channel.id) channel else row }

        return copy(
            channel = if (this.channel?.id == channel.id) channel else this.channel,
            adminChannels = adminChannels.replaced(),
            channels = channels.replaced()
        )
    }

    // ── The composer (§21) ───────────────────────────────────────────────

    fun startCompose() {
        uiState = uiState.copy(
            composerOpen = true,
            composerBody = "",
            composerAttachments = emptyList(),
            editingPostId = null,
            editingPostChannelId = null,
            composerBusy = false,
            messageCode = null
        )
    }

    /**
     * Open the posting page with the draft the feed's strip already holds (§21).
     *
     * The strip is an editable field now, so the pencil beside it is not a way IN
     * from nothing — it is the same draft given the whole screen. That is why this
     * is not [startCompose]: that one starts a NEW post and clears the body, and
     * tapping the pencil after typing a line would have deleted the line.
     */
    fun openFullComposer() {
        uiState = uiState.copy(
            composerOpen = true,
            editingPostId = null,
            editingPostChannelId = null,
            composerBusy = false,
            messageCode = null
        )
    }

    /**
     * Edit an existing post (§21).
     *
     * The body is the stored text with its formatting markers intact, which is
     * what makes an edit round-trip: the editor shows exactly what was published,
     * markers and all, because that string IS the post. Nothing is decoded on the
     * way in and re-encoded on the way out, so nothing can be lost between them.
     *
     * Its media is shown, and cannot be changed: the server has no route that
     * swaps a post's files, and offering one that silently did nothing would be
     * worse than not offering it.
     */
    fun startEditPost(post: GoodPostPost) {
        uiState = uiState.copy(
            composerOpen = true,
            composerBody = post.body.orEmpty(),
            composerAttachments = emptyList(),
            editingPostId = post.id,
            // Scoped to the post's OWN channel rather than to whichever feed is
            // open, so the edit cannot be published into the wrong one.
            editingPostChannelId = post.channelId,
            composerBusy = false,
            messageCode = null
        )
    }

    fun onComposerBodyChange(value: String) {
        uiState = uiState.copy(composerBody = value)
    }

    /**
     * Attach a file the user picked (§21).
     *
     * Uploaded IMMEDIATELY, in the background, rather than at publish time. Two
     * reasons: a large video uploads while the caption is still being typed, and
     * a failure — no storage on this deployment, a file the server will not
     * store, a dropped connection — is reported against the attachment it belongs
     * to instead of as a publish that mysteriously did nothing.
     *
     * The row appears at once in its `Uploading` state, so the tap has a visible
     * effect even when the network is slow.
     */
    fun attachMedia(attachment: GoodPostAttachment) {
        val repo = repository ?: return
        // Signed in is the only precondition a caller can check. The token itself
        // belongs to the repository, which renews it when it has lapsed — a stale
        // copy held here is what made every call fail ten minutes in.
        if (uiState.admin == null) return

        // One kind per post, matching the server's own rule: an image and a video
        // in one post would need a renderer per asset and a type that describes
        // neither. Refused here with a sentence rather than after an upload.
        val existing = uiState.composerAttachments.firstOrNull()
        if (existing != null && existing.kind != attachment.kind) {
            uiState = uiState.copy(messageCode = "mixed_media")
            return
        }

        uiState = uiState.copy(
            composerAttachments = uiState.composerAttachments + attachment,
            messageCode = null
        )

        startUpload(repo, attachment)
    }

    /**
     * Send a failed attachment again.
     *
     * A failed file leaves the draft unsendable — publishing would attach a file
     * the server never stored — and the only way out used to be to take it off and
     * pick it again. That is the wrong shape for the two ways an upload actually
     * fails: a presigned URL that expired while a large file was going up, and a
     * connection that dropped mid-PUT. Both are fixed by sending it again, and
     * neither is the sender's mistake.
     *
     * Only a FAILED attachment may be retried: a second attempt at one that is
     * still uploading would put two PUTs behind one draft slot, and the slower
     * answer would overwrite the faster one's media id.
     */
    fun retryMedia(attachment: GoodPostAttachment) {
        val repo = repository ?: return
        if (uiState.admin == null) return

        val current = uiState.composerAttachments.firstOrNull { it.uri == attachment.uri } ?: return
        if (current.state !is GoodPostUploadState.Failed) return

        uiState = uiState.copy(messageCode = null)
        updateAttachment(attachment.uri, GoodPostUploadState.Uploading)
        startUpload(repo, current)
    }

    /**
     * The upload itself: presign, PUT, confirm (see the repository).
     *
     * One implementation for the first attempt and for a retry, so a retry cannot
     * skip a step the first attempt had to pass.
     */
    private fun startUpload(repo: GoodPostRepository, attachment: GoodPostAttachment) {
        viewModelScope.launch {
            when (val result = repo.adminUploadMedia(attachment)) {
                is ApiResult.Ok -> {
                    updateAttachment(
                        attachment.uri,
                        GoodPostUploadState.Ready(result.value.id)
                    )
                }
                is ApiResult.Failed -> {
                    updateAttachment(attachment.uri, GoodPostUploadState.Failed(result.code))
                    // SAID, not just shown on the tile. A refusal used to leave a
                    // red square and a send button that never came back, with
                    // nothing anywhere saying why — and the two likeliest reasons
                    // (`unsupported_media_type` on a deployment that does not take
                    // this kind of file yet, `media_too_large` on one that does)
                    // need different things from the person holding the phone.
                    uiState = uiState.copy(messageCode = result.code)
                    // `media_unavailable` is an answer about the DEPLOYMENT rather
                    // than about the file, so the composer stops offering the
                    // picker for the rest of the session (§22).
                    if (result.code == "media_unavailable") {
                        uiState = uiState.copy(composerMediaAvailable = false)
                    }
                }
                ApiResult.Unreachable -> {
                    updateAttachment(attachment.uri, GoodPostUploadState.Failed("unreachable"))
                    uiState = uiState.copy(messageCode = "unreachable")
                }
            }
        }
    }

    /**
     * Report a file this app will not even send.
     *
     * A document, an SVG, a file whose size the picker will not disclose: the
     * server would refuse all of them, and saying so before an upload is the
     * difference between a sentence and a failed transfer of a large file.
     */
    fun reportUnsupportedMedia() {
        uiState = uiState.copy(messageCode = "unsupported_media_type")
    }

    /** Drop an attachment, by the URI it was picked with. */
    fun removeMedia(uri: String) {
        uiState = uiState.copy(
            composerAttachments = uiState.composerAttachments.filterNot { it.uri == uri },
            messageCode = null
        )
    }

    private fun updateAttachment(uri: String, state: GoodPostUploadState) {
        uiState = uiState.copy(
            composerAttachments = uiState.composerAttachments.map { attachment ->
                if (attachment.uri == uri) attachment.copy(state = state) else attachment
            }
        )
    }

    /**
     * Leave the composer, whether the post was published, saved or abandoned
     * (§19 Bug 2).
     *
     * ONE exit path, deliberately. The editing-information strip is drawn from
     * [GoodPostUiState.editingPostId], so a path that closed the composer without
     * clearing that id left the strip on screen — the editor appeared to still be
     * editing a post that had already been saved, and the next `Post` tap
     * re-opened an edit of it instead of publishing a new one.
     *
     * Attachments are dropped with it. Their uploaded objects are never claimed
     * by a post, and the server's own sweep removes an unclaimed upload after the
     * configured window — the composer does not need a delete call to leave
     * nothing behind.
     */
    fun cancelCompose() {
        // An edit is not a draft: its body is the post being edited, which the
        // strip under the feed never wrote. Discard it as before.
        if (uiState.editingPostId != null) {
            resetComposer()
            return
        }
        // A NEW post's words are kept. The strip under the feed draws the same
        // body, so closing the page on a half-written line and finding an empty
        // field would be the app eating what the reader typed.
        uiState = uiState.copy(
            composerOpen = false,
            composerBusy = false,
            editingPostChannelId = null,
            messageCode = null
        )
    }

    /**
     * Empty the composer back to "writing a new post in no channel".
     *
     * Every field the editor owns, so no caller has to remember the list: a
     * partial reset is what the three bugs in this area all had in common.
     */
    private fun resetComposer() {
        uiState = uiState.copy(
            composerOpen = false,
            composerBody = "",
            composerBusy = false,
            editingPostId = null,
            editingPostChannelId = null,
            composerAttachments = emptyList(),
            messageCode = null
        )
    }

    fun publish() {
        val repo = repository ?: return
        // Signed in is the only precondition a caller can check. The token itself
        // belongs to the repository, which renews it when it has lapsed — a stale
        // copy held here is what made every call fail ten minutes in.
        if (uiState.admin == null) return
        val channelId = when (val screen = uiState.screen) {
            is GoodPostScreen.AdminChannel -> screen.channelId
            is GoodPostScreen.Channel -> screen.channelId
            else -> return
        }

        val body = uiState.composerBody.trim()
        val editingId = uiState.editingPostId
        val attachments = uiState.composerAttachments

        // An edit is confined to the channel it was started in. `forChannel`
        // already clears it on a switch, so reaching here with a mismatched pair
        // would take a race — but refusing is still the right answer, because the
        // alternative is writing one channel's words into another's history.
        if (editingId != null && uiState.editingPostChannelId != channelId) {
            resetComposer()
            uiState = uiState.copy(messageCode = "not_found")
            return
        }

        // Refused BEFORE anything is sent. An upload still in flight, or one that
        // failed, has no media id to attach — so publishing now would either
        // publish the post without the file the user thinks they attached, or
        // fail at the server with a code about an id that was never real.
        if (editingId == null && attachments.any { it.mediaId == null }) {
            val failed = attachments.any { it.state is GoodPostUploadState.Failed }
            uiState = uiState.copy(
                messageCode = if (failed) "attachment_failed" else "attachment_uploading"
            )
            return
        }

        if (body.isEmpty() && attachments.isEmpty()) {
            uiState = uiState.copy(messageCode = "empty_post")
            return
        }

        // Text, plus whatever files are attached — and nothing else. There is
        // deliberately no link field (§16): a plain URL typed into a post's text
        // is already a link as far as a reader is concerned, and a separate
        // "add link" workflow was a second way to express the same thing, with
        // its own validation and its own failure modes.
        val payload = JSONObject().apply {
            put("body", body)
            if (editingId == null && attachments.isNotEmpty()) {
                put(
                    "mediaIds",
                    org.json.JSONArray(attachments.mapNotNull { it.mediaId })
                )
            }
        }

        uiState = uiState.copy(composerBusy = true, messageCode = null)

        viewModelScope.launch {
            val result = if (editingId == null) {
                repo.adminCreatePost(channelId, payload)
            } else {
                repo.adminUpdatePost(editingId, payload)
            }

            if (result is ApiResult.Ok) {
                // A successful publish or save ends the edit, so the editing strip
                // goes with it (§19 Bug 2). This is the SAME exit path Cancel
                // takes, which is what stops the two from drifting apart again.
                resetComposer()

                // The saved post is put on screen from the SERVER'S OWN answer,
                // before any re-read (§21).
                //
                // The write has already succeeded and the response already holds
                // the row — so waiting for a follow-up GET before showing it made
                // a published update appear to vanish: the write landed, the read
                // was slow or failed, and the reader was left looking at a feed
                // without the post they had just sent, under a banner saying the
                // data might be out of date. The refresh below still runs and is
                // still the authority; it just is not the thing standing between
                // the reader and their own post.
                val saved = result.value
                uiState = if (editingId == null) {
                    uiState.copy(
                        // Newest-first on the wire; the feed reverses it for
                        // display, so a new post goes to the FRONT here.
                        posts = GoodPostCodec.merge(listOf(saved), uiState.posts) { it.id },
                        postsStale = false,
                        postsError = null
                    )
                } else {
                    uiState.copy(
                        // An edit keeps its place in history: only the row that
                        // changed is replaced.
                        posts = uiState.posts.map { if (it.id == saved.id) saved else it },
                        postsStale = false,
                        postsError = null
                    )
                }

                // The channel's own preview and timestamp moved with the write,
                // so the list the reader goes back to is told too.
                refreshLists()
                loadPosts(channelId)
                return@launch
            }

            // The code the SERVER gave, so `media_not_ready`, `media_already_used`
            // and `unknown_media` each get their own sentence rather than a
            // generic failure.
            uiState = uiState.copy(
                composerBusy = false,
                messageCode = if (result is ApiResult.Failed) {
                    adminFailureCode(result)
                } else {
                    "unreachable"
                }
            )
        }
    }

    /** Dismiss the last message, so it cannot be read twice. */
    fun clearMessage() {
        uiState = uiState.copy(messageCode = null)
    }

    /**
     * The message for a failed administrator call, ending the session when the
     * failure means there is no session left.
     *
     * A 401 can only reach here after the repository's own renewal was refused —
     * it renews once, silently, before any call reports a failure — so it is not a
     * transient error worth retrying. The session is over, and leaving the
     * administrator on a screen whose every action would fail is worse than asking
     * for the password again.
     */
    private fun adminFailureCode(result: ApiResult<*>): String {
        if (result !is ApiResult.Failed) return "unreachable"
        if (result.status != 401) return result.code
        adminSignOut()
        return "session_expired"
    }

    /** Called when the tab is left, so a shared-page error does not persist. */
    fun onHidden() {
        uiState = uiState.copy(messageCode = null)
    }
}

/** A page of posts as the feed renders it: date separators, then the posts. */
internal sealed interface FeedEntry {
    val key: String

    data class Separator(val label: String, val at: Long) : FeedEntry {
        override val key: String get() = "sep-$at"
    }

    data class Post(val post: GoodPostPost) : FeedEntry {
        override val key: String get() = "post-${post.id}"
    }
}

/**
 * Group posts under date separators (§10).
 *
 * Pure, and separate from the composable that draws it, so the one rule that
 * matters — a separator appears when the DAY changes, not when the clock does —
 * is unit-testable without a screen.
 */
internal fun withDateSeparators(posts: List<GoodPostPost>): List<FeedEntry> {
    if (posts.isEmpty()) return emptyList()

    val entries = ArrayList<FeedEntry>(posts.size + 4)
    var previousDay: Long? = null

    posts.forEach { post ->
        val at = parseIsoMillis(post.createdAt)
        if (at != null && (previousDay == null || !waSameDay(previousDay, at))) {
            entries.add(FeedEntry.Separator(waDayLabel(at), at))
        }
        if (at != null) previousDay = at
        entries.add(FeedEntry.Post(post))
    }

    return entries
}
