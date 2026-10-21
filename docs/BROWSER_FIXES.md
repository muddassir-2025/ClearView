# Browser fixes: scope and verification

The browser is an Android `WebView` owned by `BrowserSession`, displayed through
`BrowserTab`. URL checks and the synchronous, token/host-gated JS bridge delegate
to `BrowserRules`, the existing matcher, `BlockAction`, and the shared channel
repositories. `clearview_browser.js` reads page metadata and applies decisions.
Personal blocks still go through `BlockAction`; ID aliases store identity only.
Document-start injection uses the feature-gated
[AndroidX WebKit API](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E)).
The authentication fallback uses the browser session described in
[Android Custom Tabs guidance](https://developer.chrome.com/docs/android/custom-tabs/howto-custom-tab-from-webview).

## Findings and changes

| Reported issue | Root cause found in the implementation | Change | Verification |
| --- | --- | --- | --- |
| Playback stalls after ads | The skip handler continued operating on its previously captured video after clicking Skip, even if Skip had already installed content. Repeated `duration - .1` seeks never reached the ad's end and 16x rate/mute could survive media replacement. `/get_midroll_info` was also denied despite being part of player scheduling. | Return immediately after Skip. Use one end seek per loaded ad session, no rate changes, restore only the mute value we changed. Track element/source/URL changes and suppress further seeking until the stale ad class clears. Resume once only after an interrupted ended transition, respecting pause/block/background state. Leave midroll scheduling requests to the player; limit path filtering to YouTube hosts. | Fixture tests: synchronous Skip source switch, ended transition, pause, stale ad class across repeated scans, and disabling the setting. Real pre/mid/post-roll playback remains a device check. |
| Shorts accessible through playlists and indirect routes | Filtering only followed Shorts anchors/shelves. Mobile playlist rows were omitted, and native navigation explicitly allowed blocked Shorts to load under an overlay. | Include mobile playlist rows and newer Shorts lockups. Recognize reel endpoints and SHORTS thumbnail metadata, remember identified IDs in this document, and reject their watch/playlist playback. Native URL checks and page history/play guards return blocked Shorts to Home. | Direct URL, SPA navigation, mixed playlist watch URL/SHORTS metadata, playlist preview, and playback-route fixtures. Unknown watch IDs without Shorts metadata remain a limitation. |
| Home and channel-page menus missing/nonfunctional; watch action absent | Mobile menu controls/rows were omitted. Channel-page header menus had no owner source. Reused sheets could retain a row with stale state, and ID-only owners could not be persisted as handles. Cloning YouTube custom rows risked retaining platform behavior. | Capture the clicked card's identity, use the current channel URL for header menus, recreate a plain menu button on each open, and add an owner-scoped watch action. Resolve ID-only identities from owner metadata on explicit action, cache concurrent lookups, and bound failed requests. Never invent handles from names or use recommended channels as watch owners. | Home, watch, channel header, reused popup and ID-only lookup fixtures; one successful block call. Native persistence still needs Android verification. |
| Blocks take effect only after another interaction | Successful blocks cleared `__cvKey` but retained `__cvAllowedKey`; already allowed cards were skipped indefinitely. | Invalidate both caches, immediately rejudge the current video/cards, remove the selected card, and refresh decisions on navigation/return to the tab. Persist channel-ID-to-handle aliases for later ID-only cards while retaining shared rule decisions. | Both removal and cover modes, same-channel siblings, reload, and SPA rule-change fixtures. Reload uses mocked bridge persistence; native aliases are not exercised by these tests. |
| Inconsistent/missing overlays | Scans stopped after the first 60 candidate elements before viewport filtering. Nested mobile renderers were each covered, and a replacement thumbnail left a cached covered marker behind. | Use one outer card owner, inspect viewport candidates throughout the feed, detect a missing/replaced cover, and restore covers/styles when a card is recycled. Target real thumbnails, including mobile containers; missing thumbnails fall back to hiding the blocked card. Leave menu controls usable. | Card 70, nested renderers, thumbnail replacement, recycled allowed identity, exact thumbnail bounds and usable overflow menu fixtures. |
| Shorts overlays interfere with scrolling/navigation | A body-level fixed cover used maximum z-index and clamped height independently of the video's clipped top/bottom. It could outlive the media node. | Disabled Shorts now leave the feed entirely, so there is no swipe-through blocking overlay. For supported vertical feeds when enabled, clip to the visible video bounds and bottom pivot navigation, lower stacking, hide detached covers, follow nested scrolling and media replacement, and preserve pointer gestures. | Disabled-Shorts routes assert no item overlay. Remaining enabled-feed gesture/rotation behavior needs a physical device. |
| Shorts flash on Home | Protection began at `DOMContentLoaded` or a WebView load callback, then debounced DOM hiding. Those callbacks do not guarantee execution before paint. | Install a small origin-scoped, top-frame-only stylesheet at WebView document start using AndroidX WebKit. It hides structural Shorts shelves and matching playlist/cards immediately, including later inserts. Refresh policy without reload. Retain callback injection for older providers. The early script makes no synchronous bridge calls. | Synchronous computed-style checks immediately after dynamic insertion, playlist preview, and toggle restoration. Actual document-start support and first paint on Android remain a provider/device check. |
| Instagram login/sign-up blank screen | The auth-route early return cleared page covers but left blocking/pause flags and native content state behind. This is a concrete state-leak path; the supplied screenshot alone does not establish why Instagram itself rendered blank. | Clear stale in-page and native content state on account routes, keep authentication DOM untouched, and provide a visible native Continue in browser action using Custom Tabs. Explain that login stays in that browser and ClearView in-page filters do not apply there. No cookie copying, authentication bypass, or forced CSS visibility changes. | Rendered auth-form fixture accepts input/clicks and clears stale state. Real Instagram login, signup, challenge and external-browser handling are unverified. In-WebView blank-screen compatibility is **not claimed fixed**. |

## Checks performed locally

- `npm test --prefix scripts/browser-tests`: **21 passed**, installed desktop Chrome,
  390 × 780 viewport. Native bridge and media state are controlled fixtures.
- Five targeted cases were also run against the original `clearview_browser.js`
  from commit `5cff242`: **all five failed** (missing watch action, channel-page
  menu, card 70, duplicate overlays, and seeking after Skip). They pass with the
  changes. This comparison retained the new early CSS; it does not prove the
  original first-paint behavior.
- Both page assets pass `node --check`; `git diff --check` passes.
- `./gradlew :app:testDebugUnitTest --tests 'com.muddassir.clearview.browser.*'`
  stopped before compilation with **SDK location not found**. There is no Android
  SDK or emulator configured on this Mac. Kotlin changes, Android dependency
  integration, native persistence, and the new account-route unit test were
  **not compiled or executed locally**.
- A Chromium regression workflow is included for the PR. Its remote outcome is
  separate from the local checks above.

## Remaining limits

A watch URL is not intrinsically identified as a Short: long and short videos
both use `/watch?v=...`. The implementation uses structural Shorts links,
renderer metadata and known IDs, rather than blocking all short-duration videos
or all playlists. A standalone watch URL with no such signal cannot be reliably
classified by this script. YouTube DOM/player variants and future changes still
require live verification. Older WebView providers without document-start script
support retain callback filtering and cannot guarantee flash-free initial paint.

Custom Tabs is a usable escape from a broken embedded account screen, not a
WebView login bridge. It intentionally does not import the browser's cookies or
claim to sign the embedded session in. Neither actual Instagram authentication
nor the full original blank-screen cause has been reproduced on Android here.

## Android acceptance before release

Build with SDK 37, run browser unit tests and install a debug APK on a phone.
With ad blocking on, watch pre-, mid- and post-roll transitions; verify continued
content, user pause, mute/speed preferences, fullscreen and back navigation.
Test returning Home, search, channel tabs, infinite scrolling and mixed playlists
with Block Shorts on and off, including direct `/shorts`, playlist autoplay,
watch links to previously identified Shorts, and new IDs with no metadata.

Block from Home, search, a channel header, a watch page and an ID-only card.
Verify one-click feedback, immediate same-channel sibling filtering, both feed
removal/cover settings, app restart persistence, global-rule unblock refusal and
unblocking from the shared list. Scroll well past 60 cards and rotate the screen;
check navigation and touch behavior around any remaining vertical-video covers.

Open Instagram Login and Sign Up from logged-out Home and after a Reel. Verify
visible form fields, keyboard, submit, challenge/2FA and back navigation. If the
embedded page is blank, verify Continue in browser opens the account flow and
that the session boundary is clear. Confirm ordinary YouTube playback, search,
subscriptions, normal playlists and browser-tab switching still work.
