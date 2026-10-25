/*
 * ClearView Browser page script (Android WebView adaptation of the Chrome
 * extension's content scripts).
 *
 * The extension relied on chrome.storage and chrome.runtime; WebView has
 * neither. Those platform-specific parts are replaced by ClearViewBridge,
 * which delegates EVERY rule decision to the existing Android matcher,
 * keyword lists and channel repositories. This file only reads the DOM,
 * reports candidate content, and applies the resulting UI (search prevention,
 * Shorts/Reels hiding, Google category hiding, card covers/removal, ad
 * cleanup, video pause). It contains no blocked words or domains of its own.
 *
 * The native side injects it only on YouTube, Google and Instagram, and the
 * bridge rejects calls from any other host. Each surface is handled separately
 * because their DOMs have nothing in common:
 *
 *   - YouTube: keep the reader on the current/home page for a blocked search,
 *     remove (or cover) blocked feed cards, hide Shorts, refuse ads.
 *   - Google: never execute a blocked search; hide every result category
 *     except "All".
 *   - Instagram: hide Reels entry points and stop Reels links opening.
 */
(function () {
  'use strict';

  var bridge = window.ClearViewBridge;
  if (!bridge) return;
  if (window.__clearviewBrowserLoaded) return;
  window.__clearviewBrowserLoaded = true;

  // Per-session secret injected with this script. Calls without it are
  // rejected by the native bridge (e.g. from a cross-origin iframe).
  var token = window.__clearviewToken || '';

  var policy = {
    hideShorts: false,
    hideReels: false,
    hideGoogleCategories: false,
    removeBlocked: true,
    blockAds: true
  };
  var navBlocked = false;
  var contentBlocked = false;
  // The URL of the item that paused itself for a block. A Short swipe changes
  // the URL, and the next Short must be allowed to start even if the old cover
  // is still on screen for a few milliseconds.
  var blockedVideoUrl = '';
  var lastBlockedKey = '';
  var lastVideoKey = '';
  var lastHref = location.href;
  var timers = {};
  var youtubeSearchBound = false;
  var googleSearchBound = false;
  var reelsGuardBound = false;
  var menuActionBound = false;
  var tapToggleBound = false;

  // The card whose three-dot menu was opened. YouTube teleports the popup out
  // of the card, so the click on "Not interested" has no DOM path back to it;
  // this is the same tracking the extension uses.
  var lastMenuCard = null;
  var lastMenuAt = 0;
  var lastMenuVideo = null;
  // The control whose menu is open, and the identity captured when the
  // three-dot button was pressed (see rememberMenuCard).
  var lastMenuAnchor = null;
  var menuTargetInfo = null;
  // Menus only exist for a few seconds after the reader tapped a three-dot
  // button. Tracking that window keeps the menu scan out of the idle path.
  var menuOpenedAt = 0;
  // When the page-wide "is a sheet open?" probe last ran, so a page that
  // mutates continuously cannot make it run on every mutation.
  var lastMenuProbeAt = 0;
  // The reel whose caption is currently under evaluation, so a swipe does not
  // re-read the same DOM on every observer tick.
  var lastReelKey = '';

  function parse(value) {
    try { return JSON.parse(value || '{}') || {}; } catch (e) { return {}; }
  }

  // The Android JavascriptInterface methods must be invoked ON the bridge
  // object. Passing `bridge.someMethod` around and calling it detached throws
  // "Java bridge method can't be invoked on a non-injected object", which used
  // to turn every block decision into a silent {}. `call` therefore keeps the
  // receiver explicitly.
  function call(method, payload) {
    try {
      return parse(method.call(bridge, token, JSON.stringify(payload || {})));
    } catch (e) { return {}; }
  }

  function callText(method, text) {
    try {
      return parse(method.call(bridge, token, String(text == null ? '' : text)));
    } catch (e) { return {}; }
  }

  function debounce(name, fn, delay) {
    if (timers[name]) clearTimeout(timers[name]);
    timers[name] = setTimeout(fn, delay);
  }

  function hostIsYouTube() {
    var h = (location.hostname || '').toLowerCase();
    return h === 'youtube.com' || h.endsWith('.youtube.com');
  }

  function hostIsGoogle() {
    var h = (location.hostname || '').toLowerCase().replace(/^www\./, '');
    return h === 'google.com' || /^google\.[a-z]{2,3}(\.[a-z]{2})?$/.test(h);
  }

  function hostIsInstagram() {
    var h = (location.hostname || '').toLowerCase();
    return h === 'instagram.com' || h.endsWith('.instagram.com');
  }

  var policyLoaded = false;

  function refreshPolicy() {
    try {
      var next = parse(bridge.policy(token));
      // An unauthorised or not-yet-loaded document answers {}; keep the last
      // real policy instead of silently turning every filter off.
      if (typeof next.hideShorts !== 'boolean') return;
      policy = next;
      policyLoaded = true;
    } catch (e) { /* keep last */ }
  }

  var YOUTUBE_HOME = 'https://www.youtube.com/';
  var INSTAGRAM_HOME = 'https://www.instagram.com/';

  // ── Viewport repair ─────────────────────────────────────────────
  // Some WebView builds hand the document a zero-height layout viewport:
  // `100vh` computes to 0 and every `(min-height: …)` media query answers 0,
  // even though `window.innerHeight` is correct. YouTube's player sizing and
  // Instagram's sign-in screen are both laid out from those units, which is
  // why the player collapses to a letterbox and the auth screens paint blank.
  // Measuring it from the page (instead of trusting the platform version) lets
  // the native side repair the viewport on the document that suffers from it.
  var viewportProbeEl = null;
  var viewportReportsThisDoc = 0;

  function viewportHeightIsBroken() {
    if (!document.documentElement || !(window.innerHeight > 80)) return false;
    // A media query reports the broken layout-viewport height directly, with
    // no DOM of its own. The probe below is only a fallback for an engine
    // without matchMedia.
    try {
      if (window.matchMedia) {
        return window.matchMedia('(min-height: 1px)').matches === false;
      }
    } catch (e) { /* fall through to the probe */ }
    var probe = viewportProbeEl;
    if (!probe || !probe.parentNode) {
      probe = document.createElement('div');
      probe.style.cssText =
        'position:absolute!important;top:0;left:0;width:0;height:100vh;' +
        'visibility:hidden!important;pointer-events:none!important;';
      (document.body || document.documentElement).appendChild(probe);
      viewportProbeEl = probe;
    }
    return probe.getBoundingClientRect().height < 1;
  }

  function reportBrokenViewport() {
    // At most two attempts per document: each one asks the native side for a
    // real relayout (which is what makes Chromium rebuild the CSS viewport).
    // The native side NEVER reloads the page for this — a probe that reloads
    // the reader's page mid-scroll is the bug, not the repair. The height shim
    // below is what makes a zero-height layout viewport usable in the meantime.
    if (viewportReportsThisDoc >= 2 || !viewportHeightIsBroken()) return;
    viewportReportsThisDoc++;
    try {
      if (typeof bridge.onViewportBroken === 'function') {
        bridge.onViewportBroken(token);
      }
    } catch (e) { /* older bridge: nothing else to do */ }
    // Percentage heights that chain from html/body resolve against the same
    // zero-height viewport. `window.innerHeight` is correct even when `100vh`
    // is not, so give the document a real pixel height while the native side
    // relayouts the WebView.
    if (!document.getElementById('clearview-viewport-shim')) {
      var style = document.createElement('style');
      style.id = 'clearview-viewport-shim';
      style.textContent = 'html,body{min-height:' +
        Math.max(1, Math.round(window.innerHeight)) + 'px!important}';
      (document.head || document.documentElement).appendChild(style);
    }
    repairCollapsedScroller();
  }

  /**
   * Give a page shell that sizes itself from the broken viewport a real height.
   *
   * A stylesheet can raise html/body (the shim above), but a page whose own
   * SCROLLER is `height: 100vh` stays 0px tall and clips every child — the DOM
   * is complete, `getBoundingClientRect()` reports normal boxes, and yet the
   * screen is white and nothing can be tapped. That is exactly Instagram's new
   * sign-in/sign-up shell (`div[wbloks]` with `overflow:scroll`).
   *
   * The repair is deliberately narrow: only when the CSS viewport itself is
   * broken, only a clipped box that is 0px tall while holding real content, and
   * only its height — never a box that already has a height, and never more
   * than a handful of nodes per document.
   */
  var collapsedAttempts = 0;

  function repairCollapsedScroller() {
    // At most six attempts per document: the page may render its shell late,
    // but a page with nothing to repair must not keep paying for the scan.
    if (collapsedAttempts >= 6) return;
    if (!viewportHeightIsBroken()) return;
    var height = Math.round(window.innerHeight || 0);
    if (height < 120 || !document.body) return;
    var nodes = null;
    try { nodes = document.body.querySelectorAll('*'); } catch (e) { return; }
    collapsedAttempts++;
    var repaired = 0;
    for (var i = 0; i < nodes.length && i < 5000; i++) {
      var el = nodes[i];
      if (el.hasAttribute('data-cv-vh-repaired')) continue;
      if (el.clientHeight > 1 || el.scrollHeight < 40) continue;
      var cs = window.getComputedStyle(el);
      if (cs.display === 'none' || cs.display === 'inline') continue;
      if (!/(auto|scroll|hidden|clip)/.test(cs.overflowY)) continue;
      el.setAttribute('data-cv-vh-repaired', '1');
      try { el.style.setProperty('min-height', height + 'px', 'important'); } catch (e) { /* ignore */ }
      repaired++;
      if (repaired >= 3) break;
    }
  }

  // ── Shorts: hide before first paint ─────────────────────────────
  // The scan-based removal ran after YouTube had already painted the shelf,
  // which is the flash the reader saw on every return to Home. A stylesheet
  // keyed off a class on <html> hides the same containers the scan hides, but
  // at style-recalculation time, so the shelf never becomes visible. The scan
  // still runs for the label-only navigation entries.
  var SHORTS_CSS_ID = 'clearview-shorts-css';
  var SHORTS_HAS_CSS_ID = 'clearview-shorts-css-has';

  function ensureShortsCss() {
    if (!hostIsYouTube()) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    if (!document.getElementById(SHORTS_CSS_ID)) {
      var style = document.createElement('style');
      style.id = SHORTS_CSS_ID;
      style.textContent =
        'html.cv-hide-shorts ytd-rich-shelf-renderer[is-shorts],' +
        'html.cv-hide-shorts ytd-reel-shelf-renderer,' +
        'html.cv-hide-shorts ytm-reel-shelf-renderer,' +
        'html.cv-hide-shorts ytd-reel-item-renderer,' +
        'html.cv-hide-shorts ytm-reel-item-renderer,' +
        'html.cv-hide-shorts ytm-shorts-lockup-view-model' +
        '{display:none!important}';
      root.appendChild(style);
    }
    // `:has()` lives in its own stylesheet: if a WebView cannot parse it, that
    // rule is dropped without invalidating the container selectors above.
    // WebView 105+ (the current minimum on Android 13+) supports it.
    if (!document.getElementById(SHORTS_HAS_CSS_ID)) {
      var has = document.createElement('style');
      has.id = SHORTS_HAS_CSS_ID;
      has.textContent =
        'html.cv-hide-shorts ytd-rich-item-renderer:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts ytm-rich-item-renderer:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts ytd-video-renderer:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts ytd-grid-video-renderer:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts ytd-playlist-video-renderer:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts ytd-playlist-panel-video-renderer:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts ytm-playlist-panel-video-renderer:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts ytd-compact-video-renderer:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts yt-lockup-view-model:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts ytm-playlist-video-renderer:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts ytm-compact-video-renderer:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts ytm-video-with-context-renderer:has(a[href^="/shorts/"]),' +
        'html.cv-hide-shorts ytm-video-with-context-renderer:has(a[href*="/shorts/"])' +
        '{display:none!important}';
      root.appendChild(has);
    }
  }

  function applyShortsCss() {
    if (!hostIsYouTube() || !policyLoaded) return;
    ensureShortsCss();
    var root = document.documentElement;
    if (!root || !root.classList) return;
    if (policy.hideShorts) root.classList.add('cv-hide-shorts');
    else root.classList.remove('cv-hide-shorts');
  }

  // ── Blocked vertical feeds ──────────────────────────────────────
  // A Short/Reel is blocked as a surface, not as a video: covering one item in
  // a queue that can be swiped forever is not blocking, it is painting over
  // the first one. Navigation is redirected instead, so the queue is never
  // entered — including links from playlists and direct URLs.
  var feedRedirectAt = 0;
  var feedRedirectFrom = '';

  function leaveBlockedFeed(home) {
    var current = location.href;
    if (Date.now() - feedRedirectAt < 3000) return;
    feedRedirectAt = Date.now();
    // Never bounce twice from the same URL: if the home page itself is what
    // redirected here, stay put instead of looping.
    if (feedRedirectFrom === current) return;
    feedRedirectFrom = current;
    try { window.location.replace(home); } catch (e) { /* ignore */ }
  }

  /**
   * YouTube's player height is `padding-bottom: 56.25%`, but one of its rules
   * subtracts 96px inside a landscape media query. WebView builds with a
   * broken viewport-height implementation report every screen as landscape, so
   * that rule wins and the video becomes a small letterboxed rectangle. The
   * aspect box is width-based, so pinning it to 56.25% restores the normal
   * full-width player without touching the rest of YouTube's layout.
   */
  function ensureYouTubeSizing() {
    if (!hostIsYouTube()) return;
    if (document.getElementById('clearview-yt-sizing')) return;
    var style = document.createElement('style');
    style.id = 'clearview-yt-sizing';
    style.textContent =
      'html:not([faux-fullscreen]) .player-container .player-size,' +
      'html:not([faux-fullscreen]) ytm-watch .player-size{padding-bottom:56.25% !important}' +
      'body.page-shorts .player-container .player-size,' +
      'body.page-shorts ytm-watch .player-size{padding-bottom:0 !important}';
    (document.head || document.documentElement).appendChild(style);
  }

  // ── Shared DOM helpers ──────────────────────────────────────────
  function hideNode(node, kind) {
    if (!node || node === document.body || node === document.documentElement) return;
    if (node.nodeType !== 1) return;
    if (node.getAttribute('data-cv-hidden')) return;
    node.setAttribute('data-cv-hidden', kind || '1');
    try { node.style.setProperty('display', 'none', 'important'); } catch (e) { /* ignore */ }
  }

  function restoreHidden() {
    var nodes = document.querySelectorAll(
      '[data-cv-hidden], [data-cv-hidden-card], [data-cv-covered]'
    );
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      node.removeAttribute('data-cv-hidden');
      node.removeAttribute('data-cv-hidden-card');
      try { node.style.removeProperty('display'); } catch (e) { /* ignore */ }
      if (node.getAttribute('data-cv-covered')) uncoverCard(node);
    }
  }

  // ── Video pause guard ───────────────────────────────────────────
  function pauseAll() {
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      try {
        vids[i].pause();
        vids[i].muted = true;
      } catch (e) { /* detached player */ }
    }
  }
  window.__clearviewPause = pauseAll;

  // Even if YouTube starts playback again, blocked content stays silent while
  // the overlay is up.
  document.addEventListener('play', function (event) {
    if (!contentBlocked || !event.target || event.target.tagName !== 'VIDEO') return;
    if (blockedVideoUrl && blockedVideoUrl !== location.href) return;
    try {
      event.target.pause();
      event.target.muted = true;
    } catch (e) { /* ignore */ }
  }, true);

  function showContentBlock(verdict, key) {
    contentBlocked = true;
    pauseAll();
    if (lastBlockedKey === key) return;
    lastBlockedKey = key;
    try {
      bridge.onBlocked(token, JSON.stringify({
        item: verdict.item || 'Blocked content',
        type: verdict.type || 'KEYWORD'
      }));
    } catch (e) { /* bridge unavailable */ }
  }

  function clearContentBlock() {
    if (navBlocked) return;
    contentBlocked = false;
    blockedVideoUrl = '';
    lastBlockedKey = '';
    try { bridge.onContentAllowed(token); } catch (e) { /* ignore */ }
  }

  // ── URL helpers ─────────────────────────────────────────────────
  function isWatchPage() {
    return /^\/watch\/?$/.test(location.pathname) &&
      new URLSearchParams(location.search).has('v');
  }

  function isShortsPage() {
    return location.pathname.startsWith('/shorts/');
  }

  function isVideoPage() {
    return isWatchPage() || isShortsPage();
  }

  function videoIdFromUrl() {
    var v = new URLSearchParams(location.search).get('v');
    if (v) return v;
    var m = location.pathname.match(/^\/shorts\/([\w-]{6,20})/);
    return m ? m[1] : null;
  }

  // ── Shorts hiding (existing Block Shorts toggle) ────────────────
  // Hiding is reversible: the node is kept and restored when the switch is
  // turned off, so a policy refresh does not need a page reload.
  var SHORTS_CONTAINERS = [
    'ytd-rich-shelf-renderer',
    'ytd-reel-shelf-renderer',
    'ytd-rich-item-renderer',
    'ytd-video-renderer',
    'ytd-grid-video-renderer',
    'ytd-compact-video-renderer',
    'ytd-reel-item-renderer',
    'ytd-playlist-video-renderer',
    'ytd-guide-entry-renderer',
    'ytd-mini-guide-entry-renderer',
    'ytm-pivot-bar-item-renderer',
    'yt-chip-cloud-chip-renderer',
    'yt-tab-shape',
    'ytm-video-with-context-renderer',
    'ytm-compact-video-renderer',
    'ytm-shorts-lockup-view-model',
    'ytm-reel-item-renderer',
    'ytm-reel-shelf-renderer',
    'ytm-rich-item-renderer',
    'ytm-video-renderer'
  ];

  function hideShorts() {
    if (!hostIsYouTube()) return;
    applyShortsCss();
    if (!policy.hideShorts) return;

    var shelves = document.querySelectorAll(
      'ytd-rich-shelf-renderer[is-shorts], ytd-reel-shelf-renderer, ' +
      'ytm-reel-shelf-renderer, ytm-shorts-lockup-view-model'
    );
    for (var s = 0; s < shelves.length; s++) hideNode(shelves[s], 'shorts');

    var links = document.querySelectorAll(
      'a[href*="/shorts/"], a[href="/shorts"], a[href^="/shorts?"], ' +
      'a[title="Shorts"], a[aria-label="Shorts"]'
    );
    for (var i = 0; i < links.length; i++) {
      var node = links[i];
      var target = node;
      for (var c = 0; c < SHORTS_CONTAINERS.length; c++) {
        var candidate = node.closest ? node.closest(SHORTS_CONTAINERS[c]) : null;
        if (candidate) { target = candidate; break; }
      }
      hideNode(target, 'shorts');
    }

    // The rail/pivot item is sometimes a plain tab whose label is the only
    // signal; keep the match inside navigation shapes so ordinary text is
    // never hidden.
    var navItems = document.querySelectorAll(
      'ytd-guide-entry-renderer, ytd-mini-guide-entry-renderer, ' +
      'ytm-pivot-bar-item-renderer, yt-tab-shape'
    );
    for (var n = 0; n < navItems.length; n++) {
      var label = (navItems[n].textContent || '').trim().toLowerCase()
        .replace(/\s+/g, ' ');
      if (label === 'shorts') hideNode(navItems[n], 'shorts');
    }
  }

  // ── YouTube ads ─────────────────────────────────────────────────
  var AD_SLOT_SELECTORS = [
    'ytd-ad-slot-renderer',
    'ytd-in-feed-ad-layout-renderer',
    'ytd-promoted-sparkles-web-renderer',
    'ytd-promoted-video-renderer',
    'ytd-display-ad-renderer',
    'ytd-banner-promo-renderer',
    'ytd-statement-banner-renderer',
    'ytd-companion-slot-renderer',
    'ytd-action-companion-ad-renderer',
    '#masthead-ad',
    '#player-ads',
    '#panels ytd-ads-engagement-panel-content-renderer'
  ];

  function hideAds() {
    if (!policy.blockAds || !hostIsYouTube()) return;
    for (var s = 0; s < AD_SLOT_SELECTORS.length; s++) {
      var slots = document.querySelectorAll(AD_SLOT_SELECTORS[s]);
      for (var i = 0; i < slots.length; i++) hideNode(slots[i], 'ad');
    }
    // The ad's own countdown and badges; the skip button stays clickable.
    var adUi = document.querySelectorAll(
      '.ytp-ad-progress-list, .ytp-ad-text, .ytp-ad-badge, ' +
      '.ytp-ad-image-overlay, .ytp-ad-overlay-container, .ytp-ad-simple-ad-badge'
    );
    for (var j = 0; j < adUi.length; j++) hideNode(adUi[j], 'ad');
  }

  // ── YouTube "Open App" promotion ───────────────────────────────
  // The mobile web page pushes its native app with a topbar button and, on
  // some pages, a full-width banner. Both cover the page the reader asked for
  // and neither is content, so they go away. Matched by structure first and by
  // exact label second: hiding every link to the Play Store would also hide a
  // creator's own link in a description.
  var APP_PROMO_SELECTORS = [
    'ytm-app-promo',
    'ytm-app-promo-renderer',
    'ytd-app-promo-renderer',
    'ytd-app-promo',
    'ytm-upsell-dialog-layout',
    '#app-promo',
    '.mobile-promo'
  ];

  function hideAppPromos() {
    if (!hostIsYouTube()) return;
    for (var s = 0; s < APP_PROMO_SELECTORS.length; s++) {
      var nodes = document.querySelectorAll(APP_PROMO_SELECTORS[s]);
      for (var i = 0; i < nodes.length; i++) hideNode(nodes[i], 'app-promo');
    }

    // The topbar "Open App" is a button whose only text is that label. Hide
    // the button, not the whole header: search and the page title stay. Only
    // button renderers are scanned (a handful), not every link on the page.
    var controls = document.querySelectorAll(
      'ytm-mobile-topbar-renderer ytm-button-renderer, ' +
      'header.mobile-topbar-header ytm-button-renderer, ' +
      'ytm-button-renderer, ytd-button-renderer'
    );
    for (var j = 0; j < controls.length; j++) {
      var control = controls[j];
      var text = (control.textContent || '').trim().toLowerCase()
        .replace(/\s+/g, ' ');
      var aria = (control.getAttribute('aria-label') || '').trim().toLowerCase()
        .replace(/\s+/g, ' ');
      var isOpenApp = function (label) {
        return label === 'open app' || label === 'open in app';
      };
      if (!isOpenApp(text) && !isOpenApp(aria)) continue;
      var button = control.closest && control.closest(
        'ytm-button-renderer, ytd-button-renderer, button'
      );
      hideNode(button || control, 'app-promo');
    }
  }

  function restoreAdVideo(video) {
    if (!video) return;
    try {
      if (video.__cvAdMuted) {
        video.muted = !!video.__cvAdWasMuted;
        delete video.__cvAdMuted;
        delete video.__cvAdWasMuted;
      }
      if (video.__cvAdRateSaved) {
        video.playbackRate = video.__cvAdRate || 1;
        delete video.__cvAdRateSaved;
        delete video.__cvAdRate;
      }
    } catch (e) { /* ignore */ }
  }

  /** True when the reader paused playback themselves (YouTube's own state). */
  function playerPausedByUser(player) {
    try { return player.classList.contains('paused-mode'); } catch (e) { return false; }
  }

  // Set when an ad was being accelerated, so the transition back to the real
  // video can be finished (a skipped ad can leave the content video paused on
  // its first frame, which is the grey player with a pause badge).
  var adAccelerated = false;
  var adHandledAt = 0;

  /**
   * Finish a video ad without disturbing the video that replaces it.
   *
   * The ad and the real video share ONE <video> element. The previous version
   * seeked that element to `duration - 0.1` while `ad-showing` was set; when
   * the class was removed a tick late, the seek landed on the real video and
   * left it ended/paused on a grey frame — a state only a reload cleared.
   * Accelerating the ad at 16x (the extension's behaviour) needs no seek and
   * cannot touch the next video, so the transition is always clean.
   */
  function chaseAds() {
    if (!policy.blockAds || !hostIsYouTube()) return;
    var player = document.querySelector('#movie_player, ytm-player, .html5-video-player');
    if (!player) return;
    var adShowing = false;
    try {
      adShowing = player.classList.contains('ad-showing') ||
        player.classList.contains('ad-interrupting');
    } catch (e) { /* ignore */ }
    var video = player.querySelector('video');

    if (!adShowing) {
      restoreAdVideo(video);
      if (adAccelerated) {
        adAccelerated = false;
        adHandledAt = Date.now();
      }
      // Resume once, shortly after the ad, and only when the player is not
      // showing its own paused state. This is the recovery for a player that
      // came back from an ad paused; it never overrides a reader's pause.
      if (video && video.paused && !video.ended && video.readyState >= 2 &&
          !playerPausedByUser(player) && adHandledAt &&
          Date.now() - adHandledAt < 3000) {
        try {
          var resumed = video.play();
          if (resumed && resumed.catch) resumed.catch(function () { /* refused */ });
        } catch (e) { /* ignore */ }
      }
      return;
    }

    adAccelerated = true;
    var skip = document.querySelector(
      '.ytp-ad-skip-button, .ytp-skip-ad-button, ' +
      '.ytp-ad-skip-button-modern, button.ytp-ad-skip-button-container, ' +
      '.ytp-ad-skip-button-slot button, .ytp-ad-overlay-close-button'
    );
    if (skip) {
      try { skip.click(); } catch (e) { /* ignore */ }
    }

    if (!video) return;
    try {
      if (!video.__cvAdMuted) {
        video.__cvAdWasMuted = video.muted;
        video.__cvAdMuted = true;
      }
      video.muted = true;
      if (!video.__cvAdRateSaved) {
        video.__cvAdRate = video.playbackRate;
        video.__cvAdRateSaved = true;
      }
      if (video.playbackRate < 8) video.playbackRate = 16;
      // A refused ad request can leave the ad element paused with no skip
      // button; running it is what lets the player move on.
      if (video.paused && !video.ended) {
        var playing = video.play();
        if (playing && playing.catch) playing.catch(function () { /* ignore */ });
      }
    } catch (e) { /* ignore */ }
  }

  // ── Watch page: title / description / channel ──────────────────
  function textOf(selector) {
    var el = document.querySelector(selector);
    var text = el ? (el.textContent || '').trim() : '';
    return text || null;
  }

  function channelInfo() {
    var info = { channelId: null, channelHandle: null, channelName: null };
    // Owner-scoped first: on a Shorts page the first channel link in the
    // document is often a recommended video's channel, not the one playing.
    var scopes = [
      '#owner',
      'ytd-reel-player-header-renderer',
      'ytm-reel-player-header-renderer',
      'ytm-slim-owner-renderer',
      'ytm-slim-video-information-renderer',
      'ytm-reel-player-overlay-renderer',
      'ytm-reel-player-renderer',
      'ytm-watch-metadata-renderer',
      '#above-the-fold',
      '#player-container-id'
    ];
    for (var s = 0; s < scopes.length; s++) {
      var scope = document.querySelector(scopes[s]);
      if (!scope) continue;
      readChannelLinks(scope, info);
      if (!info.channelHandle) {
        var scopedHandle = handleFromScope(scope);
        if (scopedHandle) info.channelHandle = scopedHandle;
      }
      // Keep looking while only an id was found: a later, owner-scoped header
      // may spell the handle that a bare /channel/UC link cannot provide.
      if (info.channelHandle) break;
    }
    if (!info.channelHandle) {
      // A bare channel id is not enough: the page action needs an @handle.
      // Shorts often has no owner anchor at all, only a Subscribe button whose
      // aria-label is "Subscribe to @name." — so scan the document text before
      // giving up, then fall back to the first channel link.
      info.channelHandle = handleFromScope(document.body);
      if (!info.channelHandle) readChannelLinks(document, info);
    }
    if (!info.channelName) {
      info.channelName = textOf('#owner #channel-name') ||
        textOf('#owner ytd-channel-name') ||
        textOf('ytd-reel-player-header-renderer #channel-name') ||
        textOf('ytm-channel-name') ||
        textOf('ytm-slim-owner-renderer');
    }
    return info;
  }

  function readChannelLinks(root, info) {
    var links = root.querySelectorAll('a[href*="/@"], a[href*="/channel/UC"]');
    for (var i = 0; i < links.length; i++) {
      var href = links[i].getAttribute('href') || '';
      var handle = href.match(/\/@([\w.-]{1,100})/);
      var id = href.match(/\/channel\/(UC[\w-]{10,})/);
      if (!info.channelHandle && handle) info.channelHandle = '@' + handle[1];
      if (!info.channelId && id) info.channelId = id[1];
      // A /channel/UC link carries the id but not the handle; the avatar's
      // aria-label usually does ("Go to channel @name").
      if (!info.channelHandle) {
        var aria = links[i].getAttribute('aria-label') || '';
        var fromAria = aria.match(/@[\w.-]{2,100}/);
        if (fromAria && aria.toLowerCase().indexOf('go to channel') !== -1) {
          info.channelHandle = fromAria[0].replace(/[._-]+$/, '');
        }
      }
      if (!info.channelName) {
        var name = (links[i].textContent || '').trim();
        if (name && name.length <= 60) info.channelName = name;
      }
      if (info.channelHandle && info.channelId && info.channelName) return;
    }
  }

  /**
   * A handle from the text YouTube renders, when there is no usable link.
   *
   * Mobile Shorts and some watch layouts replace the owner anchor with a
   * header component, but still spell the handle in a label or a line of text.
   * This follows the same precedence as the app's YouTubeNodeRules:
   *   1. "Go to channel @name" (unambiguous);
   *   2. a label that is exactly one handle;
   *   3. a handle inside a short label.
   * Long text is ignored so an @mention in a description is never mistaken for
   * the channel being watched.
   */
  function channelHandleFromLabels(labels) {
    for (var j = 0; j < labels.length; j++) {
      var goTo = (labels[j] || '').trim();
      if (goTo.toLowerCase().indexOf('go to channel @') === 0) {
        var handle = goTo.substring(goTo.indexOf('@'))
          .match(/^@[A-Za-z0-9._-]{2,100}/);
        if (handle) return handle[0].replace(/[._-]+$/, '');
      }
    }
    for (var k = 0; k < labels.length; k++) {
      var exact = (labels[k] || '').trim();
      if (exact.length <= 100 && /^@[A-Za-z0-9._-]{2,100}$/.test(exact)) {
        return exact.replace(/[._-]+$/, '');
      }
    }
    for (var m = 0; m < labels.length; m++) {
      var text = (labels[m] || '').trim();
      if (text.length > 60) continue;
      var match = text.match(/@[A-Za-z0-9._-]{2,100}/);
      if (match) return match[0].replace(/[._-]+$/, '');
    }
    return null;
  }

  function handleFromScope(scope) {
    if (!scope || !scope.querySelectorAll) return null;
    var labels = [];
    var nodes = scope.querySelectorAll('a, [aria-label], span, div, yt-formatted-string');
    for (var i = 0; i < nodes.length && labels.length < 300; i++) {
      var aria = nodes[i].getAttribute('aria-label');
      if (aria && aria.length <= 120) labels.push(aria);
      if (nodes[i].children.length === 0) {
        var text = (nodes[i].textContent || '').trim();
        if (text && text.length <= 120) labels.push(text);
      }
    }
    var inner = (scope.innerText || '').split('\n');
    for (var n = 0; n < inner.length && labels.length < 500; n++) {
      var line = inner[n].trim();
      if (line && line.length <= 120) labels.push(line);
    }
    return channelHandleFromLabels(labels);
  }

  function collectVideo() {
    var info = channelInfo();
    // Last-resort identity: YouTube's own bootstrap data names the owner of
    // the video being watched. It does not carry the @handle, but a channel id
    // is enough to enforce a backend/global block and to name the channel in
    // the block card.
    try {
      var player = window.ytInitialPlayerResponse;
      var details = player && player.videoDetails;
      if (details) {
        if (!info.channelId && details.channelId) info.channelId = details.channelId;
        if (!info.channelName && details.author) info.channelName = details.author;
      }
    } catch (e) { /* bootstrap data unavailable */ }
    info.videoId = videoIdFromUrl();
    info.title = textOf('#title h1 yt-formatted-string') ||
      textOf('h1.ytd-watch-metadata') ||
      textOf('yt-shorts-video-title-view-model') ||
      textOf('ytm-slim-video-information-renderer .yt-core-attributed-string') ||
      textOf('ytm-slim-video-information-renderer h1') ||
      textOf('ytm-slim-video-information-renderer h2') ||
      textOf('ytm-watch-metadata-renderer h1') ||
      textOf('h1.media-item-headline') ||
      textOf('#title h1') ||
      (document.title || '').replace(/\s*-\s*YouTube\s*$/, '') || null;
    info.description = textOf('#description-inline-expander') ||
      textOf('#description') ||
      textOf('ytm-expandable-video-description-body-renderer') ||
      textOf('.slim-video-information-description');
    if (info.description && info.description.length > 2000) {
      info.description = info.description.slice(0, 2000);
    }
    return info;
  }

  function evaluateVideo() {
    if (!hostIsYouTube() || !isVideoPage()) return;
    // A Short is a whole blocked surface, so it is left before it can render.
    // Covering one item of an endless queue was the "swipe past the overlay"
    // hole: the next Short started playing underneath.
    if (isShortsPage() && policy.hideShorts) {
      leaveBlockedFeed(YOUTUBE_HOME);
      return;
    }
    var info = collectVideo();
    if (!info.videoId && !info.title) return;
    var key = [info.videoId, info.title, info.channelId, info.channelHandle].join('|');
    if (key === lastVideoKey) return;
    lastVideoKey = key;
    var verdict = call(bridge.evaluateVideo, info);
    if (verdict && verdict.blocked) {
      if (isShortsPage()) {
        // A Short lives in a vertical queue. The native full-screen overlay
        // would swallow the swipe that moves to the next one, so the page
        // paints an opaque cover over the video instead and leaves every
        // touch where it is: swipe up/down still changes the Short.
        contentBlocked = true;
        lastBlockedKey = key;
        blockedVideoUrl = location.href;
        pauseAll();
        showVideoCover('Blocked by ClearView\n' +
          (verdict.item || 'Blocked content'));
      } else {
        blockedVideoUrl = location.href;
        showContentBlock(verdict, key);
      }
    } else if (isShortsPage()) {
      contentBlocked = false;
      blockedVideoUrl = '';
      lastBlockedKey = '';
      clearVideoCover();
    } else if (contentBlocked) {
      // The page was re-evaluated after late metadata; the same video is now
      // known to be allowed (e.g. a title changed during load).
      clearContentBlock();
    }
  }

  // ── Feed / search cards ─────────────────────────────────────────
  // Every card is checked by the native bridge against the EXISTING feed-card
  // policy (title, thumbnail description and channel weighting). The page only
  // paints the result: a blocked card is removed from the feed (default) or
  // covered in place, and its links are intercepted either way.
  var CARD_SELECTORS = [
    'ytd-video-renderer',
    'ytd-rich-item-renderer',
    'ytd-grid-video-renderer',
    'ytd-compact-video-renderer',
    'ytd-playlist-video-renderer',
    'ytd-reel-item-renderer',
    'yt-lockup-view-model',
    'ytm-video-with-context-renderer',
    'ytm-compact-video-renderer',
    'ytm-shorts-lockup-view-model',
    'ytm-reel-item-renderer',
    'ytm-rich-item-renderer',
    // Community posts: text and images by a channel, not a video. They live on
    // a channel's Posts tab and in the Home feed, and the reader's blocked
    // channels and keywords apply to them exactly as they do to a video.
    'ytm-backstage-post-renderer',
    'ytm-backstage-post-thread-renderer',
    'ytd-backstage-post-thread-renderer',
    'ytd-backstage-post-renderer'
  ];

  var THUMBNAIL_SELECTORS = [
    'a#thumbnail',
    'ytd-thumbnail a',
    'ytd-playlist-thumbnail a',
    'yt-thumbnail-view-model',
    'a.media-item-thumbnail',
    '.media-item-thumbnail-container',
    'ytd-thumbnail',
    '#thumbnail'
  ];

  /** True for a community post (text and/or images), not a video card. */
  function isPostCard(card) {
    var tag = card && card.tagName ? card.tagName.toLowerCase() : '';
    // A tag-name test, not Element.matches: this runs once per card per scan.
    return tag.indexOf('backstage-post') !== -1;
  }

  /**
   * The text a post was written with.
   *
   * Only the post's own content node is read: falling back to the whole
   * renderer would include the author's name, the vote count and the menu
   * labels, and a keyword match on those would block the wrong thing.
   */
  function postText(card) {
    // Mobile web YouTube renders a post's body as a `<truncated-text>` inside
    // `.ytmBackstagePostRendererHostContentText`; the older desktop/mweb shape
    // is `#content-text`. The inner attributed-string span is read first so the
    // "...Read more" button text never reaches the keyword matcher.
    var el = deepQuery(card, 'truncated-text-content .ytAttributedStringHost');
    if (!el) el = deepQuery(card, '#content-text, yt-formatted-string#content-text');
    if (!el) el = deepQuery(card, '.ytmBackstagePostRendererHostContentText');
    var text = el ? (el.textContent || '').trim() : '';
    if (!text) return null;
    return text.replace(/\s*\.\.\.Read more\s*$/i, '').trim().slice(0, 1400) || null;
  }

  /** A post has no thumbnail link; the image's alt text is its description. */
  function postImageAlt(card) {
    var images = card.querySelectorAll('img[alt]');
    for (var i = 0; i < images.length && i < 4; i++) {
      var alt = (images[i].getAttribute('alt') || '').trim();
      if (alt && alt.length <= 300) return alt;
    }
    return null;
  }

  /**
   * Element query that also looks inside one level of open shadow roots.
   *
   * YouTube's newer `yt-*-view-model` cards and action bars render their link,
   * title and channel name inside a shadow root. `querySelector` cannot see
   * through it, so a card built that way looked like it had no id, no title and
   * no channel — it was never judged, which is why a blocked channel's videos
   * stayed on the Home feed while the watch page (light DOM) blocked them.
   * The light-DOM query runs first, so an ordinary card pays nothing.
   */
  function deepQuery(root, selector) {
    if (!root) return null;
    var found = null;
    try { found = root.querySelector(selector); } catch (e) { found = null; }
    if (found) return found;
    // The card itself may be the shadow host (yt-lockup-view-model).
    var own = root.shadowRoot;
    if (own) {
      try { found = own.querySelector(selector); } catch (e) { found = null; }
      if (found) return found;
    }
    var hosts = null;
    try { hosts = root.querySelectorAll('*'); } catch (e) { return null; }
    for (var i = 0; i < hosts.length && i < 400; i++) {
      var shadow = hosts[i].shadowRoot;
      if (!shadow) continue;
      try { found = shadow.querySelector(selector); } catch (e) { found = null; }
      if (found) return found;
    }
    return null;
  }

  /** First element in the click's real path (crosses shadow boundaries). */
  /** `closest` that keeps walking after crossing a shadow boundary. */
  function closestDeep(node, selector) {
    var el = node;
    for (var depth = 0; el && depth < 12; depth++) {
      var found = el.closest ? el.closest(selector) : null;
      if (found) return found;
      var root = el.getRootNode ? el.getRootNode() : null;
      if (!root || !root.host) return null;
      el = root.host;
    }
    return null;
  }

  function firstInPath(event, selector) {
    var path = null;
    try { path = event.composedPath ? event.composedPath() : null; } catch (e) { path = null; }
    if (path) {
      for (var i = 0; i < path.length; i++) {
        var node = path[i];
        if (node && node.nodeType === 1 && node.matches && node.matches(selector)) {
          return node;
        }
      }
    }
    var target = event.target;
    return target && target.closest ? target.closest(selector) : null;
  }

  /**
   * Every card element on the page, containers removed.
   *
   * YouTube nests cards: a Home grid item (`ytm-rich-item-renderer`) wraps the
   * lockup that renders one video, and a shelf wraps several of them. Judged as
   * a card, a container is attributed with the FIRST child's thumbnail, title
   * and channel, and the cover then paints over every item in it — the reader
   * sees a black panel labelled with a channel that only one of the items
   * belongs to. Only the element that owns a single item is kept.
   */
  function allCards() {
    var seen = new Set();
    var cards = [];
    for (var s = 0; s < CARD_SELECTORS.length; s++) {
      var found = document.querySelectorAll(CARD_SELECTORS[s]);
      for (var i = 0; i < found.length; i++) {
        if (seen.has(found[i])) continue;
        seen.add(found[i]);
        cards.push(found[i]);
      }
    }
    // Cheap ancestor test instead of a per-card subtree query: walking up at
    // most four parents is far cheaper than running a 16-selector query inside
    // every card on the page.
    var present = new Set(cards);
    var containers = new Set();
    for (var c = 0; c < cards.length; c++) {
      var node = cards[c].parentElement;
      for (var up = 0; up < 4 && node; up++) {
        if (present.has(node)) {
          containers.add(node);
          break;
        }
        node = node.parentElement;
      }
    }
    return cards.filter(function (card) { return !containers.has(card); });
  }

  /**
   * The card's video id from the class YouTube stamps on it.
   *
   * Mobile lockup cards carry `content-id-<videoId>` on the card itself, which
   * is present even when no `/watch?v=` anchor is rendered yet (the newer
   * `yt-lockup-view-model` shape). Without it such a card had no id at all, so
   * it could not be looked up in the page data and was judged on its name only.
   */
  function stampedVideoId(el) {
    if (!el || !el.getAttribute) return null;
    var cls = el.getAttribute('class');
    if (cls) {
      var m = String(cls).match(/\bcontent-id-([A-Za-z0-9_-]{6,20})\b/);
      if (m) return m[1];
    }
    var data = el.getAttribute('data-video-id') || el.getAttribute('data-content-id');
    if (data && /^[A-Za-z0-9_-]{6,20}$/.test(data)) return data;
    return null;
  }

  function videoIdFromCardClass(card) {
    // The stamp is on the card in some builds and on its `ytLockupViewModelHost`
    // child in others, and the card selector can match either node.
    var direct = stampedVideoId(card);
    if (direct) return direct;
    var child = deepQuery(card, '[class*="content-id-"], [data-video-id]');
    if (child) {
      var fromChild = stampedVideoId(child);
      if (fromChild) return fromChild;
    }
    var el = card.parentElement;
    for (var depth = 0; el && depth < 4; depth++) {
      var fromParent = stampedVideoId(el);
      if (fromParent) return fromParent;
      el = el.parentElement;
    }
    return null;
  }

  function videoIdFromCard(card) {
    var stamped = videoIdFromCardClass(card);
    if (stamped) return stamped;
    var a = deepQuery(
      card,
      'a[href*="/watch?v="], a[href*="/shorts/"], a[href*="youtu.be/"]'
    );
    if (!a) return null;
    var href = a.getAttribute('href') || '';
    var m = href.match(/[?&]v=([\w-]{6,20})/) ||
      href.match(/\/shorts\/([\w-]{6,20})/) ||
      href.match(/youtu\.be\/([\w-]{6,20})/);
    return m ? m[1] : null;
  }

  function cardTitle(card) {
    // Explicit title elements first. A selector LIST would return whichever
    // matching element comes first in document order — usually the thumbnail
    // anchor, whose aria-label is a thumbnail caption, not the title.
    var explicit = deepQuery(
      card,
      '#video-title, a#video-title, yt-formatted-string#video-title, ' +
      '.media-item-headline, h3 a, h3'
    );
    var text = explicit ? (explicit.textContent || '').trim() : '';
    if (!text && explicit) {
      text = (explicit.getAttribute('aria-label') || '').trim();
    }
    if (!text) {
      var link = deepQuery(card, 'a[aria-label][href*="/watch"]');
      text = link ? (link.getAttribute('aria-label') || '').trim() : '';
    }
    return text && text.length <= 300 ? text : null;
  }

  /**
   * videoId → {handle, id, name} from the page's own bootstrap data.
   *
   * The mobile Home card carries no channel link at all: the title is a
   * JS-handled <a> without href, and the channel is only a display name in the
   * metadata row plus an avatar label. The handle therefore cannot be read from
   * the card DOM — but YouTube's `ytInitialData` holds it for every feed item
   * (`browseEndpoint.canonicalBaseUrl`), keyed by the video's contentId. That
   * is the same identity the watch page uses, so a channel blocked from a video
   * now matches its Home cards exactly instead of by display name.
   */
  var channelIndex = null;
  var channelIndexForData = null;

  function channelFromBrowseEndpoint(ep, out) {
    if (!ep) return;
    if (ep.canonicalBaseUrl) {
      var handle = String(ep.canonicalBaseUrl).match(/^\/@([A-Za-z0-9._-]{1,100})/);
      if (handle && !out.channelHandle) out.channelHandle = '@' + handle[1];
    }
    if (ep.browseId && !out.channelId) out.channelId = String(ep.browseId);
  }

  function channelFromLockup(lockup) {
    var meta = lockup && lockup.metadata && lockup.metadata.lockupMetadataViewModel;
    if (!meta) return null;
    var out = { channelHandle: null, channelId: null, channelName: null };
    try {
      var avatar = meta.image && meta.image.decoratedAvatarViewModel;
      var command = avatar && avatar.rendererContext &&
        avatar.rendererContext.commandContext &&
        avatar.rendererContext.commandContext.onTap &&
        avatar.rendererContext.commandContext.onTap.innertubeCommand;
      channelFromBrowseEndpoint(command && command.browseEndpoint, out);
      if (avatar && avatar.a11yLabel) {
        out.channelName = String(avatar.a11yLabel)
          .replace(/^Go to channel\s+/i, '').trim() || null;
      }
      if (!out.channelName) {
        var rows = meta.metadata && meta.metadata.contentMetadataViewModel &&
          meta.metadata.contentMetadataViewModel.metadataRows;
        var part = rows && rows[0] && rows[0].metadataParts && rows[0].metadataParts[0];
        if (part && part.text && part.text.content) {
          out.channelName = String(part.text.content).trim() || null;
        }
      }
    } catch (e) { /* malformed entry */ }
    if (!out.channelHandle && !out.channelId) return null;
    return out;
  }

  function channelFromRendererOwner(renderer) {
    var owner = renderer.ownerText || renderer.longBylineText || renderer.shortBylineText;
    var run = owner && owner.runs && owner.runs[0];
    var out = { channelHandle: null, channelId: null, channelName: null };
    try {
      channelFromBrowseEndpoint(run && run.navigationEndpoint &&
        run.navigationEndpoint.browseEndpoint, out);
      if (run && run.text) out.channelName = String(run.text).trim() || null;
    } catch (e) { /* ignore */ }
    if (!out.channelHandle && !out.channelId) return null;
    return out;
  }

  function buildChannelIndex(data) {
    var index = {};
    var visited = 0;
    function walk(node, depth) {
      if (!node || typeof node !== 'object' || depth > 32 || visited > 40000) return;
      visited++;
      if (node.contentId && node.metadata) {
        var lockup = channelFromLockup(node);
        if (lockup) index[node.contentId] = lockup;
      } else if (node.videoId && typeof node.videoId === 'string') {
        var renderer = channelFromRendererOwner(node);
        if (renderer) index[node.videoId] = renderer;
      }
      for (var key in node) {
        if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
        var value = node[key];
        if (value && typeof value === 'object') walk(value, depth + 1);
      }
    }
    try { walk(data, 0); } catch (e) { /* keep what was indexed */ }
    return index;
  }

  function channelIndexFor() {
    var data = window.ytInitialData;
    if (!data) return {};
    if (channelIndex && channelIndexForData === data) return channelIndex;
    channelIndexForData = data;
    channelIndex = buildChannelIndex(data);
    return channelIndex;
  }

  /**
   * videoId → handle from YouTube's continuation responses.
   *
   * `ytInitialData` only describes the FIRST page. Cards loaded by scrolling
   * (Home feed, related, search) come from `/youtubei/v1/browse|next`
   * responses that the page never exposes as an object, and those cards carry
   * no handle in their markup at all — which is exactly why a blocked channel
   * stayed on the Home feed while the watch page blocked its videos.
   *
   * The responses are kept as raw text (a small, bounded ring) and searched on
   * demand by the card's contentId. An entry's own channel sits inside that
   * entry, so the search stops at the next contentId and cannot pick up a
   * neighbouring card's channel.
   */
  var PAGE_CHUNK_LIMIT = 6;
  var PAGE_CHUNK_MAX_CHARS = 1500000;
  var PAGE_CHUNK_TOTAL_CHARS = 3000000;
  var pageChunks = [];
  var pageChunkChars = 0;
  var pageLookup = {};

  function rememberPageData(text) {
    if (typeof text !== 'string') return;
    if (text.length < 200 || text.length > PAGE_CHUNK_MAX_CHARS) return;
    if (text.indexOf('"contentId"') === -1) return;
    pageChunks.push(text);
    pageChunkChars += text.length;
    while (pageChunks.length > PAGE_CHUNK_LIMIT || pageChunkChars > PAGE_CHUNK_TOTAL_CHARS) {
      var dropped = pageChunks.shift();
      if (!dropped) break;
      pageChunkChars -= dropped.length;
    }
    // New data can answer a lookup that already missed.
    pageLookup = {};
  }

  function lookupChannelInPageText(text, videoId) {
    var needle = '"contentId":"' + videoId + '"';
    var at = text.indexOf(needle);
    if (at < 0) return null;
    var next = text.indexOf('"contentId":"', at + needle.length);
    var segment = text.slice(at, next > at ? next : at + 4000);
    // The entry's own channel: `"canonicalBaseUrl":"/@handle"` (some builds
    // escape the slash). Read it as text so regex escaping cannot get it wrong.
    var base = segment.indexOf('"canonicalBaseUrl":"');
    if (base < 0) return null;
    // `"canonicalBaseUrl":"` is 20 characters, so the value starts right after.
    var tail = segment.slice(base + 20, base + 20 + 120);
    var start = tail.indexOf('/@');
    if (start < 0) return null;
    var name = '';
    for (var j = start + 2; j < tail.length; j++) {
      var ch = tail.charAt(j);
      if (!/[A-Za-z0-9._-]/.test(ch)) break;
      name += ch;
    }
    if (!name) return null;
    return { channelHandle: '@' + name, channelId: null, channelName: null };
  }

  function channelFromPageData(videoId) {
    if (!videoId) return null;
    if (Object.prototype.hasOwnProperty.call(pageLookup, videoId)) return pageLookup[videoId];
    var found = null;
    for (var i = pageChunks.length - 1; i >= 0 && !found; i--) {
      found = lookupChannelInPageText(pageChunks[i], videoId);
    }
    pageLookup[videoId] = found;
    return found;
  }

  /** Keep the raw text of YouTube's data responses, so [channelFromPageData] can read it. */
  function installPageDataCapture() {
    if (!hostIsYouTube() || window.__cvPageCapture) return;
    window.__cvPageCapture = true;
    try {
      var originalFetch = window.fetch;
      if (typeof originalFetch === 'function') {
        window.fetch = function () {
          var result = originalFetch.apply(this, arguments);
          try {
            if (result && typeof result.then === 'function') {
              result.then(function (response) {
                try {
                  var url = (response && response.url) || '';
                  if (url.indexOf('/youtubei/v1/') === -1) return;
                  response.clone().text().then(function (text) {
                    rememberPageData(text);
                  }, function () { /* body already consumed */ });
                } catch (e) { /* unreadable body: the DOM path still applies */ }
              }, function () { /* failed request */ });
            }
          } catch (e) { /* never break the page's own request */ }
          return result;
        };
      }
    } catch (e) { /* frozen fetch */ }
    try {
      var OriginalXhr = window.XMLHttpRequest;
      if (OriginalXhr && OriginalXhr.prototype) {
        var open = OriginalXhr.prototype.open;
        var send = OriginalXhr.prototype.send;
        // Declared with the real arity so a caller that inspects `open.length`
        // still sees a standard XMLHttpRequest.
        OriginalXhr.prototype.open = function (method, url, async, user, password) {
          try { this.__cvUrl = url; } catch (e) { /* ignore */ }
          return open.apply(this, arguments);
        };
        OriginalXhr.prototype.send = function () {
          var xhr = this;
          try {
            xhr.addEventListener('load', function () {
              try {
                var url = String(xhr.__cvUrl || xhr.responseURL || '');
                if (url.indexOf('/youtubei/v1/') === -1) return;
                if (xhr.responseType === 'json') {
                  rememberPageData(JSON.stringify(xhr.response));
                } else if (!xhr.responseType || xhr.responseType === 'text') {
                  rememberPageData(xhr.responseText);
                }
              } catch (e) { /* ignore */ }
            });
          } catch (e) { /* ignore */ }
          return send.apply(this, arguments);
        };
      }
    } catch (e) { /* frozen XMLHttpRequest */ }
  }

  function cardChannel(card, videoId) {
    // `/c/` and `/user/` are the legacy channel URLs a community post can use
    // for a channel that never took an @handle. They carry no handle, but they
    // do carry the display name the block list also matches on. Only posts get
    // the wider selector: a video card never links a channel that way, and this
    // runs once per card per scan.
    var link = deepQuery(
      card,
      isPostCard(card)
        ? 'a[href*="/@"], a[href*="/channel/UC"], a[href^="/c/"], a[href^="/user/"]'
        : 'a[href*="/@"], a[href*="/channel/UC"]'
    );
    var info = { channelId: null, channelHandle: null, channelName: null };
    if (link) {
      var href = link.getAttribute('href') || '';
      var handle = href.match(/\/@([\w.-]{1,100})/);
      var id = href.match(/\/channel\/(UC[\w-]{10,})/);
      if (handle) info.channelHandle = '@' + handle[1];
      if (id) info.channelId = id[1];
      var name = (link.textContent || '').trim();
      if (name && name.length <= 60) info.channelName = name;
    }
    // The avatar's label carries the handle even when the card renders the
    // channel name as plain text instead of a /@ link.
    if (!info.channelHandle) {
      var labelled = deepQuery(card, '[aria-label*="Go to channel"]');
      if (labelled) {
        var labelledHandle = (labelled.getAttribute('aria-label') || '')
          .match(/@[A-Za-z0-9._-]{2,100}/);
        if (labelledHandle) {
          info.channelHandle = labelledHandle[0].replace(/[._-]+$/, '');
        }
      }
    }
    if (!info.channelName) {
      var nameEl = deepQuery(
        card,
        '#channel-name, ytd-channel-name, .ytd-channel-name, .ytm-channel-name, ' +
        'ytm-channel-name, a[href^="/@"]' +
        (isPostCard(card) ? ', a[href^="/c/"], a[href^="/user/"]' : '')
      );
      if (nameEl) {
        var text = (nameEl.textContent || '').trim();
        if (text && text.length <= 60) info.channelName = text;
      }
    }
    // Mobile lockup cards: the channel name is the first attributed string of
    // the metadata row, and the avatar label spells it as "Go to channel X".
    if (!info.channelName) {
      var metaText = deepQuery(
        card,
        'yt-content-metadata-view-model .ytContentMetadataViewModelMetadataText'
      );
      if (metaText) {
        var channelText = (metaText.textContent || '').trim();
        if (channelText && channelText.length <= 60) info.channelName = channelText;
      }
    }
    if (!info.channelName) {
      var goTo = deepQuery(card, '[aria-label^="Go to channel "]');
      if (goTo) {
        var label = (goTo.getAttribute('aria-label') || '')
          .replace(/^Go to channel\s+/i, '').trim();
        if (label && label.length <= 60) info.channelName = label;
      }
    }
    // Last name source, and the only one the newest mobile card offers: its
    // caption spells the channel out as "<title> by <channel> 57,02,395 views".
    if (!info.channelName) {
      var described = deepQuery(card, '[aria-label*=" by "]');
      if (described) {
        var caption = described.getAttribute('aria-label') || '';
        var by = caption.match(/\sby\s(.+?)\s[\d.,]+\s(?:views?|watching)\b/i);
        if (by) {
          var named = by[1].trim();
          if (named && named.length <= 60) info.channelName = named;
        }
      }
    }
    // Exact identity from YouTube's own page data when the card itself has
    // none. This is the handle the watch page reports, so a block made from a
    // video matches the Home card instead of relying on a display name.
    if (!info.channelHandle && !info.channelId && videoId) {
      var known = channelIndexFor()[videoId];
      if (known) {
        if (known.channelHandle) info.channelHandle = known.channelHandle;
        if (known.channelId) info.channelId = known.channelId;
        if (!info.channelName && known.channelName) info.channelName = known.channelName;
      }
      if (!info.channelHandle && !info.channelId) {
        var fromResponse = channelFromPageData(videoId);
        if (fromResponse) {
          if (fromResponse.channelHandle) info.channelHandle = fromResponse.channelHandle;
          if (fromResponse.channelId) info.channelId = fromResponse.channelId;
        }
      }
    }
    // Compact cards sometimes render the owner as text rather than a link.
    // Without this a blocked channel's card could not be recognised, which is
    // exactly the "my Chrome blocks do not work here" case.
    if (!info.channelHandle) {
      var fromCard = handleFromScope(card);
      if (fromCard) info.channelHandle = fromCard;
    }
    return info;
  }

  function cardThumbnailDescription(card) {
    for (var s = 0; s < THUMBNAIL_SELECTORS.length; s++) {
      var el = deepQuery(card, THUMBNAIL_SELECTORS[s]);
      if (!el) continue;
      var label = (el.getAttribute('aria-label') || '').trim();
      if (!label) {
        var img = el.querySelector('img') || (el.tagName === 'IMG' ? el : null);
        if (img) label = (img.getAttribute('alt') || '').trim();
      }
      if (label && label.length <= 300) return label;
    }
    return null;
  }

  function thumbnailTarget(card) {
    for (var s = 0; s < THUMBNAIL_SELECTORS.length; s++) {
      var el = card.querySelector(THUMBNAIL_SELECTORS[s]);
      if (el) return el;
    }
    return card;
  }

  function suppressCardPreview(card) {
    var videos = card.querySelectorAll('video');
    for (var i = 0; i < videos.length; i++) {
      try {
        videos[i].pause();
        videos[i].muted = true;
        videos[i].removeAttribute('autoplay');
      } catch (e) { /* ignore */ }
    }
    var previews = card.querySelectorAll(
      'ytd-moving-thumbnail-renderer, #mouseover-overlay, .ytp-inline-preview-ui'
    );
    for (var p = 0; p < previews.length; p++) hideNode(previews[p], 'preview');
  }

  function removableCard(card) {
    var wrapper = card.closest ? card.closest(
      'ytd-rich-item-renderer, ytd-video-renderer, ytd-grid-video-renderer, ' +
      'ytd-compact-video-renderer, ytd-playlist-video-renderer, ytd-reel-item-renderer'
    ) : null;
    return wrapper || card;
  }

  function removeCard(card) {
    uncoverCard(card);
    var target = removableCard(card);
    if (target.getAttribute('data-cv-hidden-card')) return;
    target.setAttribute('data-cv-hidden-card', '1');
    try { target.style.setProperty('display', 'none', 'important'); } catch (e) { /* ignore */ }
    suppressCardPreview(target);
  }

  function restoreCard(card) {
    if (!card.getAttribute('data-cv-hidden-card')) return;
    card.removeAttribute('data-cv-hidden-card');
    try { card.style.removeProperty('display'); } catch (e) { /* ignore */ }
  }

  function coverCard(card, label, key) {
    // Stamped before the early return so a cover painted for the same label on
    // a recycled card still records which item it is covering.
    card.setAttribute('data-cv-cover-key', key || '');
    if (card.getAttribute('data-cv-covered') === label) return;
    uncoverCard(card);
    card.setAttribute('data-cv-covered', label || 'Blocked by ClearView');
    card.setAttribute('data-cv-cover-key', key || '');

    // A post renders its text and images instead of a thumbnail link; an
    // overlay inside one of those fragments would cover a corner of the post.
    var target = isPostCard(card) ? card : thumbnailTarget(card);
    try {
      target.style.position = 'relative';
      // An inline anchor cannot size an absolutely-positioned cover reliably.
      if (window.getComputedStyle && window.getComputedStyle(target).display === 'inline') {
        target.style.display = 'block';
      }
    } catch (e) { /* ignore */ }

    var cover = document.createElement('div');
    cover.className = 'clearview-thumb-overlay';
    cover.setAttribute('aria-label', label || 'Blocked by ClearView');
    cover.style.cssText =
      'position:absolute;inset:0;z-index:20;display:flex;flex-direction:column;' +
      'align-items:center;justify-content:center;gap:6px;' +
      'background:#000;color:#fff;text-align:center;' +
      'padding:10px;border-radius:10px;pointer-events:auto;' +
      'font:600 12px/1.3 Roboto,Arial,sans-serif;';
    var badge = document.createElement('div');
    badge.textContent = 'CV';
    badge.style.cssText =
      'width:26px;height:26px;border-radius:50%;' +
      'border:1.5px solid rgba(255,255,255,0.75);display:flex;' +
      'align-items:center;justify-content:center;font:700 10px/1 Roboto,Arial,sans-serif;';
    var title = document.createElement('div');
    title.textContent = 'Blocked by ClearView';
    cover.appendChild(badge);
    cover.appendChild(title);
    var extra = label && label !== 'Blocked by ClearView'
      ? label.replace(/^Blocked by ClearView\s*/i, '')
      : '';
    if (extra) {
      var reason = document.createElement('div');
      reason.textContent = extra;
      reason.style.cssText = 'font-weight:500;font-size:11px;opacity:0.72;max-width:100%;' +
        'overflow:hidden;text-overflow:ellipsis;display:-webkit-box;' +
        '-webkit-line-clamp:2;-webkit-box-orient:vertical;';
      cover.appendChild(reason);
    }
    target.appendChild(cover);

    suppressCardPreview(card);

    // A blocked card must not navigate on tap, click, middle-click or Enter.
    var blockNav = function (event) {
      event.preventDefault();
      event.stopImmediatePropagation();
      suppressCardPreview(card);
    };
    card.__cvBlockNav = blockNav;
    card.addEventListener('click', blockNav, true);
    card.addEventListener('auxclick', blockNav, true);
    card.__cvBlockKey = function (event) {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    };
    card.addEventListener('keydown', card.__cvBlockKey, true);
  }

  function uncoverCard(card) {
    if (!card.hasAttribute('data-cv-covered')) return;
    card.removeAttribute('data-cv-covered');
    card.removeAttribute('data-cv-cover-key');
    var cover = card.querySelector('.clearview-thumb-overlay');
    if (cover) cover.remove();
    if (card.__cvBlockNav) {
      card.removeEventListener('click', card.__cvBlockNav, true);
      card.removeEventListener('auxclick', card.__cvBlockNav, true);
      delete card.__cvBlockNav;
    }
    if (card.__cvBlockKey) {
      card.removeEventListener('keydown', card.__cvBlockKey, true);
      delete card.__cvBlockKey;
    }
  }

  // ── In-page item cover (Shorts, Reels, Instagram posts) ─────────
  // These surfaces are full-screen vertical players. A native overlay would
  // put a Compose layer over the WebView and eat the swipe that moves to the
  // next item, and redirecting to the feed home is worse: it throws the reader
  // out of the queue they were scrolling. The cover below is painted by the
  // page: opaque black over the blocked item, pointer-events none on a player,
  // so swiping up/down still reaches the page underneath.
  var ITEM_COVER_ID = 'clearview-item-cover';
  var coverTarget = null;

  /** The video the reader is actually looking at (largest on-screen area). */
  function activeVideo() {
    var videos = document.querySelectorAll('video');
    var best = null;
    var bestArea = 0;
    var height = window.innerHeight || 800;
    for (var i = 0; i < videos.length; i++) {
      var rect = videos[i].getBoundingClientRect();
      if (rect.width < 60 || rect.height < 60) continue;
      var visible = Math.max(0, Math.min(rect.bottom, height) - Math.max(rect.top, 0));
      var area = rect.width * visible;
      if (area > bestArea) {
        bestArea = area;
        best = videos[i];
      }
    }
    return best;
  }

  function currentShortVideo() {
    return activeVideo();
  }

  function paintCover(message, pointerEvents) {
    var cover = document.getElementById(ITEM_COVER_ID);
    if (!cover) {
      cover = document.createElement('div');
      cover.id = ITEM_COVER_ID;
      (document.body || document.documentElement).appendChild(cover);
    }
    cover.style.cssText =
      'position:fixed;z-index:2147483600;display:flex;flex-direction:column;' +
      'align-items:center;justify-content:center;gap:10px;background:#000;' +
      'color:#fff;text-align:center;padding:20px;box-sizing:border-box;' +
      'font:600 14px/1.4 Roboto,Arial,sans-serif;pointer-events:' +
      (pointerEvents ? 'auto' : 'none') + ';';

    var text = String(message || 'Blocked by ClearView');
    if (cover.getAttribute('data-cv-cover-text') !== text) {
      cover.setAttribute('data-cv-cover-text', text);
      cover.textContent = '';
      var badge = document.createElement('div');
      badge.textContent = 'CV';
      badge.style.cssText =
        'width:38px;height:38px;border-radius:50%;border:2px solid #fff;' +
        'display:flex;align-items:center;justify-content:center;' +
        'font:700 13px/1 Roboto,Arial,sans-serif;';
      var body = document.createElement('div');
      body.textContent = text;
      body.style.cssText = 'max-width:88%;white-space:pre-line;';
      var note = document.createElement('div');
      note.textContent = 'Swipe for the next one · ClearView';
      note.style.cssText =
        'font-weight:500;font-size:11px;letter-spacing:1px;opacity:0.6;';
      cover.appendChild(badge);
      cover.appendChild(body);
      cover.appendChild(note);
    }
    positionCover();
  }

  function positionCover() {
    var cover = document.getElementById(ITEM_COVER_ID);
    if (!cover || !coverTarget || !coverTarget.getBoundingClientRect) return;
    var rect = coverTarget.getBoundingClientRect();
    if (!rect || rect.width < 10 || rect.height < 10) return;
    var width = window.innerWidth || 360;
    var height = window.innerHeight || 800;
    cover.style.left = Math.max(0, rect.left) + 'px';
    cover.style.top = Math.max(0, rect.top) + 'px';
    cover.style.width = Math.min(rect.width, width) + 'px';
    cover.style.height = Math.min(rect.height, height) + 'px';
  }

  function showVideoCover(message) {
    var video = activeVideo();
    if (video) coverTarget = video;
    // No video has been rendered yet: a fixed cover with no anchor would paint
    // a stray black box, so wait for the next evaluation instead.
    if (!coverTarget) return;
    paintCover(message, false);
    startCoverTracking();
  }

  function clearVideoCover() {
    var cover = document.getElementById(ITEM_COVER_ID);
    if (cover) cover.remove();
    coverTarget = null;
    coverTracking = false;
  }

  // A Short/Reel is swiped, not scrolled: the player slides the next item in
  // with a transform, so a cover positioned once at paint time stayed over the
  // OLD item while the reader was already watching the next one. While a cover
  // is up it is re-anchored to whichever video currently occupies the screen.
  // Only one element is measured per frame, so this is cheaper than the
  // observer scans it replaces.
  var coverTracking = false;
  function startCoverTracking() {
    if (coverTracking) return;
    coverTracking = true;
    window.requestAnimationFrame(trackCover);
  }
  function trackCover() {
    if (!coverTracking) return;
    var cover = document.getElementById(ITEM_COVER_ID);
    if (!cover) {
      coverTracking = false;
      return;
    }
    var video = activeVideo();
    if (video && video !== coverTarget) coverTarget = video;
    positionCover();
    window.requestAnimationFrame(trackCover);
  }

  /**
   * Take a stale cover off a recycled card.
   *
   * YouTube reuses card elements while scrolling. A covered element that is
   * recycled for a different video used to keep its black cover (and the tap
   * blocker painted with it), so the overlay sat over a channel the reader
   * never blocked. Every cover is stamped with the identity it was painted for;
   * anything that no longer matches is uncovered here. Off-screen cards are
   * included on purpose: that is where YouTube recycles them, and the card can
   * be scrolled back into view before the next viewport-limited scan.
   */
  function reconcileCovers() {
    if (!hostIsYouTube()) return;
    var covered = document.querySelectorAll('[data-cv-covered]');
    for (var i = 0; i < covered.length; i++) {
      var card = covered[i];
      var id = isPostCard(card) ? null : videoIdFromCard(card);
      var title = isPostCard(card) ? postText(card) : cardTitle(card);
      var channel = cardChannel(card, id);
      var description = isPostCard(card) ? postImageAlt(card) : cardThumbnailDescription(card);
      var key = [id, title, channel.channelId, channel.channelHandle, description].join('|');
      if (card.getAttribute('data-cv-cover-key') === key) continue;
      uncoverCard(card);
      delete card.__cvAllowedKey;
      card.__cvKey = key;
    }
  }

  /**
   * Minimum time between card passes.
   *
   * A watch page mutates its DOM continuously (progress, captions, related
   * cards), and every mutation batch used to start a full document scan. The
   * work is cheap per pass but not free, and doing it several times a second
   * while a video plays is exactly the scroll/playback stutter the reader
   * reported. A deferred pass is scheduled instead of dropping the request, so
   * nothing is ever left unjudged.
   */
  var SCAN_MIN_INTERVAL_MS = 550;
  var lastCardScanAt = 0;

  function scanCards() {
    if (!hostIsYouTube()) return;
    // A Shorts page IS the player: removing the current item would break the
    // vertical queue. Every other YouTube surface (including the suggestions
    // beside a watch page) is a list of ordinary cards and is scanned.
    if (isShortsPage()) return;
    var now = Date.now();
    if (now - lastCardScanAt < SCAN_MIN_INTERVAL_MS) {
      debounce('cards', scanCards, SCAN_MIN_INTERVAL_MS - (now - lastCardScanAt));
      return;
    }
    lastCardScanAt = now;
    reconcileCovers();
    var cards = allCards();
    var checked = 0;
    for (var i = 0; i < cards.length && checked < 60; i++) {
      var card = cards[i];
      // Off-screen cards are skipped before any metadata is read: a feed holds
      // far more cards than are visible, and reading each one's title, channel
      // and description was the bulk of the scan's cost.
      var rect = card.getBoundingClientRect();
      if (rect.bottom < -260 || rect.top > (window.innerHeight || 800) + 260) continue;
      // A post is not a video: it has no id, its author is the channel that
      // wrote it, and its text is what the keywords judge. Reading an id from
      // a video the post merely links to would attribute the post to that
      // video's channel instead of its author's.
      var post = isPostCard(card);
      var id = post ? null : videoIdFromCard(card);
      var title = post ? postText(card) : cardTitle(card);
      var channel = cardChannel(card, id);
      var description = post ? postImageAlt(card) : cardThumbnailDescription(card);
      if (!id && !title && !channel.channelId && !channel.channelHandle) continue;
      checked++;

      // YouTube recycles card elements while scrolling, so the cached key
      // includes the title/channel actually shown now. A hidden card that was
      // recycled to a different video is restored and judged again; otherwise
      // it would stay invisible for an allowed video.
      var key = [id, title, channel.channelId, channel.channelHandle, description].join('|');
      if (card.getAttribute('data-cv-hidden-card') && card.__cvKey !== key) {
        restoreCard(card);
      }
      if (card.getAttribute('data-cv-hidden-card')) continue;
      if (card.__cvKey === key && card.hasAttribute('data-cv-covered')) {
        // The removal policy can flip while a covered card is on screen.
        if (policy.removeBlocked) removeCard(card);
        continue;
      }
      // An allowed card is not re-sent over the JNI bridge on every scan. The
      // key changes only when YouTube recycles the element or the metadata
      // changes, which is exactly when it has to be judged again.
      if (card.__cvAllowedKey === key) continue;
      card.__cvKey = key;

      var verdict = call(bridge.evaluateCard, {
        videoId: id,
        title: title,
        thumbnailDescription: description,
        channelId: channel.channelId,
        channelHandle: channel.channelHandle,
        channelName: channel.channelName
      });
      if (verdict && verdict.blocked) {
        delete card.__cvAllowedKey;
        var label = verdict.item
          ? 'Blocked by ClearView\n' + verdict.item
          : 'Blocked by ClearView';
        if (policy.removeBlocked) {
          removeCard(card);
        } else {
          coverCard(card, label, key);
        }
      } else {
        card.__cvAllowedKey = key;
        uncoverCard(card);
      }
    }
  }

  // ── In-page notice (never a blocking overlay) ───────────────────
  // Used when a search is stopped before it runs. The browser also shows a
  // small chrome banner, but this keeps the explanation next to the search
  // box the reader just used.
  var PAGE_NOTICE_ID = 'clearview-inline-notice';
  var pageNoticeTimer = null;

  function showPageNotice(message) {
    var existing = document.getElementById(PAGE_NOTICE_ID);
    if (existing) existing.remove();
    if (pageNoticeTimer) clearTimeout(pageNoticeTimer);

    var box = document.createElement('div');
    box.id = PAGE_NOTICE_ID;
    box.textContent = message;
    box.style.cssText =
      'margin:10px 0;padding:10px 12px;border-radius:10px;' +
      'background:rgba(28,28,30,0.96);color:#fff;' +
      'font:500 13px/1.4 Roboto,Arial,sans-serif;' +
      'box-shadow:0 2px 10px rgba(0,0,0,0.25);position:relative;z-index:30;';

    var anchor = null;
    if (hostIsGoogle()) {
      anchor = document.querySelector('#searchform') ||
        document.querySelector('#main') ||
        document.querySelector('#tsf');
    } else if (hostIsYouTube()) {
      anchor = document.querySelector('#search-form') ||
        document.querySelector('ytd-searchbox') ||
        document.querySelector('#masthead-container');
    } else if (hostIsInstagram()) {
      anchor = document.querySelector('nav') || document.querySelector('main');
    }

    if (anchor && anchor.parentNode) {
      var insertAfter = hostIsGoogle() ||
        (anchor.id === 'search-form') ||
        anchor.tagName === 'YTD-SEARCHBOX' ||
        anchor.tagName === 'YT-SEARCHBOX' ||
        anchor.tagName === 'NAV';
      if (insertAfter) {
        anchor.parentNode.insertBefore(box, anchor.nextSibling);
      } else {
        anchor.appendChild(box);
      }
    } else {
      box.style.position = 'fixed';
      box.style.top = '12px';
      box.style.left = '12px';
      box.style.right = '12px';
      (document.body || document.documentElement).appendChild(box);
    }

    pageNoticeTimer = setTimeout(function () {
      if (box.parentNode) box.remove();
    }, 6000);
  }

  function isBlockedSearch(query) {
    if (!query || !String(query).trim()) return false;
    var verdict = callText(bridge.evaluateSearch, String(query).trim());
    return !!(verdict && verdict.blocked);
  }

  // ── Google: categories + search prevention ──────────────────────
  var GOOGLE_CATEGORY_LABELS = /^(all|search|images|videos|news|shopping|books|maps|flights|finance|web|short videos|trending|tools|more)$/;

  function googleTabLabel(node) {
    var text = (node.textContent || '').trim().toLowerCase()
      .replace(/\s+/g, ' ');
    if (!text) text = (node.getAttribute && node.getAttribute('aria-label') || '')
      .trim().toLowerCase().replace(/\s+/g, ' ');
    return text;
  }

  function hideGoogleCategories() {
    if (!policy.hideGoogleCategories || !hostIsGoogle()) return;

    var tabs = document.querySelectorAll(
      '#hdtb a, #hdtb .hdtb-mitem, #hdtb div[role="tab"], ' +
      '#hdtb div[role="listitem"], #hdtb-msb a, #hdtb-msb .hdtb-mitem, ' +
      '#hdtb-msb div[role="tab"], #hdtb-msb div[role="listitem"], ' +
      '[role="tablist"] a, [role="tablist"] [role="tab"], ' +
      '[role="tablist"] [role="listitem"], ' +
      'div[role="navigation"] a[href*="tbm="], ' +
      'div[role="navigation"] a[href*="udm="]'
    );
    for (var i = 0; i < tabs.length; i++) {
      var tab = tabs[i];
      var label = googleTabLabel(tab);
      if (!label) continue;
      // Keep the All tab even if the URL happens to carry a mode parameter,
      // and never hide a container that holds the search box itself.
      if (/^(all|search)$/.test(label)) continue;
      var target = (tab.closest && (
        tab.closest('.hdtb-mitem') ||
        tab.closest('div[role="tab"]') ||
        tab.closest('div[role="listitem"]') ||
        tab.closest('li')
      )) || tab;
      if (target === document.body || target.id === 'hdtb-msb') continue;
      hideNode(target, 'google-tab');
    }

    // Mobile Google renders the same modes as bare anchors in a horizontal
    // scroller whose container has no role or id. The mode parameter is the
    // reliable signal; hide the tab-shaped ancestor, not the whole bar.
    var modeLinks = document.querySelectorAll(
      'a[href*="tbm="], a[href*="udm="]'
    );
    for (var m = 0; m < modeLinks.length; m++) {
      var link = modeLinks[m];
      var linkLabel = googleTabLabel(link);
      if (!linkLabel || !GOOGLE_CATEGORY_LABELS.test(linkLabel)) continue;
      if (linkLabel === 'all' || linkLabel === 'search') continue;
      var tabTarget = link.closest && (
        link.closest('[role="tab"]') ||
        link.closest('[role="listitem"]') ||
        link.closest('.hdtb-mitem')
      );
      if (!tabTarget) {
        // A category link in a real header/nav bar: hide the link. Do NOT
        // climb to an arbitrary <li>, which can be an ordinary result row.
        var bar = link.closest && (
          link.closest('#hdtb') ||
          link.closest('[role="tablist"]') ||
          link.closest('[role="navigation"]') ||
          link.closest('header') ||
          link.closest('[role="banner"]')
        );
        if (bar) {
          tabTarget = link;
        } else {
          // Last structural signal: a group of two or more mode links is a
          // category bar even when mobile omitted the usual ids/roles.
          var group = link.parentElement;
          for (var depth = 0; depth < 3 && group && !tabTarget; depth++) {
            if (group.querySelectorAll &&
                group.querySelectorAll('a[href*="tbm="], a[href*="udm="]').length >= 2) {
              tabTarget = link;
            }
            group = group.parentElement;
          }
          if (!tabTarget) continue;
        }
      }
      if (tabTarget === document.body) continue;
      hideNode(tabTarget, 'google-tab');
    }

    hideGoogleInlineMedia();
  }

  /**
   * Google's "Trending searches" widget (home page and results footer).
   *
   * It is a recommendation block, not a search result, so it is removed even
   * when the category-tabs toggle is off. Matching is on the heading text and
   * a bounded ancestor with links; hiding an ancestor that reaches #rso/#main
   * would take the real results with it.
   */
  /**
   * Google's home page ends with a language line ("Google offered in: …") and
   * then its trending-search list. The reader asked for everything after that
   * line to go, so the line and every following sibling in the same main
   * column are removed. The global footer (Settings/Privacy) is outside that
   * column and stays.
   */
  function hideGoogleOfferedInTail() {
    if (!hostIsGoogle()) return;

    // This line is the last real content on Google's HOME page; on a results
    // page the same line sits in a footer above nothing. Only the home page's
    // tail is the reader's complaint, and limiting the scan there keeps a
    // results page (which can hold thousands of nodes) untouched.
    var path = location.pathname || '/';
    var onHome = path === '/' || path === '/webhp' || path === '/index.html';
    if (!onHome) return;

    var match = document.getElementById('SIvCob');
    if (!match) {
      var scope = document.querySelector('#main') || document.body;
      var candidates = scope.querySelectorAll('div, span, p');
      for (var i = 0; i < candidates.length && i < 1500; i++) {
        var text = (candidates[i].textContent || '').trim().toLowerCase();
        if (text.indexOf('google offered in') !== 0 || text.length > 300) continue;
        // Keep the deepest matching element. An ancestor also starts with the
        // same text but contains the whole page, and hiding it would remove
        // the search box too.
        var deeper = candidates[i].querySelector('span, div, p');
        while (deeper && (deeper.textContent || '').trim().toLowerCase()
            .indexOf('google offered in') === 0) {
          candidates[i] = deeper;
          deeper = deeper.querySelector('span, div, p');
        }
        match = candidates[i];
        break;
      }
    }
    if (!match) return;

    // Remove the line and EVERY following sibling, then repeat one level up.
    // Mobile Google puts the trending list in a sibling of the language block
    // on one layout and inside a later sibling on another; walking the chain
    // removes both shapes. Ancestors themselves are never hidden, only their
    // following siblings, so the search form above the line is untouched.
    hideNode(match, 'google-offered');
    var node = match;
    for (var depth = 0; depth < 8 && node && node !== document.body; depth++) {
      var sibling = node.nextElementSibling;
      while (sibling) {
        var tag = (sibling.tagName || '').toLowerCase();
        var keep = tag === 'footer' ||
          (sibling.id && /^(search|rso|tsf|botstuff)$/.test(sibling.id)) ||
          (sibling.querySelector && sibling.querySelector('#tsf, form[role="search"]'));
        if (!keep) hideNode(sibling, 'google-offered-tail');
        sibling = sibling.nextElementSibling;
      }
      var parent = node.parentElement;
      if (!parent || parent === document.body) break;
      node = parent;
    }
  }

  function hideGoogleTrending() {
    if (!hostIsGoogle()) return;

    var headings = document.querySelectorAll(
      'h2, h3, [role="heading"], [aria-label*="Trending" i]'
    );
    var seen = [];
    for (var i = 0; i < headings.length; i++) {
      var el = headings[i];
      var text = (el.getAttribute && el.getAttribute('aria-label') || '')
        .trim().toLowerCase().replace(/\s+/g, ' ');
      if (!text) text = (el.textContent || '').trim().toLowerCase()
        .replace(/\s+/g, ' ');
      if (text !== 'trending searches' && text !== 'trending now' &&
          text !== 'top searches') continue;

      var section = null;
      var node = el.parentElement;
      for (var d = 0; d < 7 && node; d++) {
        if (node === document.body || node.id === 'rso' ||
            node.id === 'main' || node.id === 'search' ||
            node.id === 'botstuff') break;
        if (node.querySelectorAll('a').length > 0 &&
            (node.textContent || '').length < 4000) {
          section = node;
          break;
        }
        node = node.parentElement;
      }
      if (section && seen.indexOf(section) === -1) {
        seen.push(section);
        hideNode(section, 'google-trending');
      }
    }

    // Some layouts use a plain div for the heading inside a semantic section.
    var sections = document.querySelectorAll(
      'g-section-with-header, section, [role="region"]'
    );
    for (var s = 0; s < sections.length; s++) {
      var sectionText = (sections[s].innerText || '').toLowerCase();
      if (sectionText.length > 4000) continue;
      if (!/(^|\n)\s*(trending searches|trending now|top searches)\s*(\n|$)/
        .test(sectionText)) continue;
      if (sections[s].querySelectorAll('a').length === 0) continue;
      hideNode(sections[s], 'google-trending');
    }

    // The home-page carousel is sometimes labelled only by aria-label. The
    // selector above already limited that pass to labels mentioning
    // "Trending"; hide the labelled carousel itself when it has links.
    var labelled = document.querySelectorAll('[aria-label*="Trending" i]');
    for (var l = 0; l < labelled.length; l++) {
      if (labelled[l].querySelectorAll('a').length === 0) continue;
      var target = labelled[l].closest('g-section-with-header, section') ||
        labelled[l];
      if (target === document.body) continue;
      hideNode(target, 'google-trending');
    }
  }

  function hideGoogleInlineMedia() {
    var blocks = document.querySelectorAll('#rso > div, #rso > g-section-with-header, .MjjYud');
    for (var i = 0; i < blocks.length; i++) {
      var block = blocks[i];
      var mediaLink = block.querySelector(
        'a[href*="tbm=isch"], a[href*="&udm=2"], a[href*="?udm=2"], ' +
        'a[href*="tbm=vid"], a[href*="&udm=3"], a[href*="?udm=3"]'
      );
      var mediaHeading = false;
      var headings = block.querySelectorAll('h2, h3, div[role="heading"]');
      for (var h = 0; h < headings.length; h++) {
        var text = (headings[h].textContent || '').trim().toLowerCase();
        if (text === 'images' || text === 'videos' || text === 'visual matches') {
          mediaHeading = true;
          break;
        }
      }
      var ytLinks = block.querySelectorAll(
        'a[href*="youtube.com/watch"], a[href*="youtube.com/shorts"]'
      );
      if (mediaLink || mediaHeading || ytLinks.length >= 2) {
        hideNode(block, 'google-media');
      }
    }
  }

  function googleQueryField(el) {
    if (!el) return false;
    if (el.matches && el.matches('input[name="q"], textarea[name="q"]')) return true;
    return !!(el.closest && el.closest('form[role="search"], #tsf, #searchform'));
  }

  function googleQuery() {
    var field = document.querySelector('textarea[name="q"], input[name="q"]');
    return field ? field.value : '';
  }

  function bindGoogleSearch() {
    if (googleSearchBound) return;
    googleSearchBound = true;

    document.addEventListener('keydown', function (event) {
      if (!hostIsGoogle() || event.key !== 'Enter') return;
      if (!googleQueryField(event.target)) return;
      var query = event.target.value || googleQuery();
      if (isBlockedSearch(query)) {
        event.preventDefault();
        event.stopImmediatePropagation();
        showPageNotice('Search blocked by ClearView');
      }
    }, true);

    document.addEventListener('submit', function (event) {
      if (!hostIsGoogle()) return;
      var form = event.target;
      if (!form || !form.querySelector) return;
      if (!form.querySelector('input[name="q"], textarea[name="q"]')) return;
      var query = googleQuery();
      if (isBlockedSearch(query)) {
        event.preventDefault();
        event.stopImmediatePropagation();
        showPageNotice('Search blocked by ClearView');
      }
    }, true);

    document.addEventListener('click', function (event) {
      if (!hostIsGoogle()) return;
      var target = event.target;
      var button = target && target.closest
        ? target.closest('button[type="submit"], input[type="submit"], #tsf button')
        : null;
      if (!button) return;
      var query = googleQuery();
      if (isBlockedSearch(query)) {
        event.preventDefault();
        event.stopImmediatePropagation();
        showPageNotice('Search blocked by ClearView');
      }
    }, true);
  }

  // ── YouTube: search prevention ──────────────────────────────────
  function youtubeSearchField(el) {
    if (!el) return false;
    if (el.id === 'search' || el.name === 'search_query') return true;
    return !!(el.closest && (
      el.closest('#search-form') ||
      el.closest('ytd-searchbox') ||
      el.closest('yt-searchbox')
    ));
  }

  function youtubeSearchQuery() {
    var field = document.querySelector(
      'input#search, input[name="search_query"], ytd-searchbox input, yt-searchbox input'
    );
    return field ? field.value : '';
  }

  function bindYouTubeSearch() {
    if (youtubeSearchBound) return;
    youtubeSearchBound = true;

    document.addEventListener('keydown', function (event) {
      if (!hostIsYouTube() || event.key !== 'Enter') return;
      if (!youtubeSearchField(event.target)) return;
      var query = event.target.value || youtubeSearchQuery();
      if (isBlockedSearch(query)) {
        event.preventDefault();
        event.stopImmediatePropagation();
        showPageNotice('Search blocked by ClearView');
      }
    }, true);

    document.addEventListener('click', function (event) {
      if (!hostIsYouTube()) return;
      var target = event.target;
      var button = target && target.closest
        ? target.closest('#search-icon-legacy, ytd-searchbox button, ' +
            'yt-searchbox button, button[aria-label*="Search" i]')
        : null;
      if (!button) return;
      var query = youtubeSearchQuery();
      if (isBlockedSearch(query)) {
        event.preventDefault();
        event.stopImmediatePropagation();
        showPageNotice('Search blocked by ClearView');
      }
    }, true);

    document.addEventListener('submit', function (event) {
      if (!hostIsYouTube()) return;
      var form = event.target;
      if (!form || (form.id !== 'search-form' && !(form.querySelector &&
          form.querySelector('input[name="search_query"]')))) return;
      var query = youtubeSearchQuery();
      if (isBlockedSearch(query)) {
        event.preventDefault();
        event.stopImmediatePropagation();
        showPageNotice('Search blocked by ClearView');
      }
    }, true);
  }

  // ── Instagram: Reels hiding ─────────────────────────────────────
  function instagramReelLink(node) {
    return node && node.closest
      ? node.closest('a[href*="/reels/"], a[href*="/reel/"]')
      : null;
  }

  function navReelContainer(link) {
    var node = link;
    for (var depth = 0; depth < 7 && node && node.parentElement; depth++) {
      var parent = node.parentElement;
      if (parent.matches && parent.matches('nav, [role="navigation"]')) return node;
      if (parent === document.body) break;
      node = parent;
    }
    return null;
  }

  /**
   * The small control that owns [node], or null when it lives in a pane.
   *
   * Instagram's nav is a column of buttons, but several pages (the DM inbox,
   * settings, profile edit) put their own controls inside elements that also
   * match `role=navigation`. Climbing to the navigation container there hid a
   * whole panel — that is the blank Instagram screen. A nav item is small;
   * anything bigger is left alone, and only the tap guard applies to it.
   */
  function navItemContainer(node) {
    if (!node || node === document.body) return null;
    var best = null;
    var current = node;
    for (var depth = 0; depth < 6 && current && current !== document.body; depth++) {
      var rect = current.getBoundingClientRect();
      // Zero-size boxes are normal here: several of Instagram's nav icons sit
      // in wrappers that report no box at all. Only a box that is clearly a
      // panel (rather than a nav item) stops the climb.
      if (rect.width > 240 || rect.height > 160) break;
      best = current;
      current = current.parentElement;
    }
    return best;
  }

  function hideReels() {
    if (!policy.hideReels || !hostIsInstagram()) return;

    var links = document.querySelectorAll(
      'a[href*="/reels/"], a[href*="/reel/"]'
    );
    for (var i = 0; i < links.length; i++) {
      // Never remove the whole post/article: a reel link can appear in a
      // comment or a profile card. Only a nav-item-sized control is hidden.
      var control = navItemContainer(links[i]);
      if (control) hideNode(control, 'reels');
    }

    // The nav item sometimes has an icon whose aria-label says "Reels" while
    // the anchor itself wraps a different subtree.
    var labels = document.querySelectorAll('svg[aria-label="Reels"], [aria-label="Reels"]');
    for (var j = 0; j < labels.length; j++) {
      var label = labels[j];
      if (/^(INPUT|TEXTAREA)$/.test(label.tagName || '')) continue;
      var owner = (label.closest && label.closest('[role="button"], [role="link"], button')) ||
        label;
      var container = navItemContainer(owner);
      if (container) hideNode(container, 'reels');
    }
  }

  /** A nav entry that opens Instagram's search/explore page. */
  function instagramSearchLink(node) {
    if (!node || !node.closest) return null;
    return node.closest('a[href="/explore/"], a[href^="/explore/?"], a[href="/explore"]');
  }

  /**
   * Hide Instagram's search (explore) entry point.
   *
   * Hiding Reels alone left the magnifier one tap away from a page of
   * recommendations, so the same pass removes the icon. Only nav-like
   * containers are touched: an input, and a link inside a post's caption, are
   * left exactly where Instagram drew them.
   */
  function hideInstagramSearch() {
    if (!policy.hideInstagramSearch || !hostIsInstagram()) return;

    var links = document.querySelectorAll(
      'a[href="/explore/"], a[href^="/explore/?"], a[href="/explore"]'
    );
    for (var i = 0; i < links.length; i++) {
      // Never hide the whole page: an explore link can also appear in a post.
      var control = navItemContainer(links[i]);
      if (control) hideNode(control, 'insta-search');
    }

    // The nav item's icon carries the label when the anchor wraps a different
    // subtree, or when the build renders the entry as a button. The label is
    // "Explore" on the current build; only that spelling is trusted, because
    // the DM inbox has its own button labelled "Search" and hiding it took the
    // whole conversation list with it (the blank messages page).
    var labels = document.querySelectorAll(
      'svg[aria-label="Explore"], svg[aria-label="Search and explore"], ' +
      '[role="button"][aria-label="Explore"], [role="link"][aria-label="Explore"], ' +
      '[aria-label="Search and explore"]'
    );
    for (var j = 0; j < labels.length; j++) {
      var label = labels[j];
      if (/^(INPUT|TEXTAREA)$/.test(label.tagName || '')) continue;
      var owner = (label.closest && label.closest('[role="button"], [role="link"], button')) ||
        label;
      var container = navItemContainer(owner);
      if (container) hideNode(container, 'insta-search');
    }
  }

  /**
   * Hide Instagram's Home tab.
   *
   * The nav item is the front door; the URL is closed by the native side and by
   * the guard in [onNavigate], so hiding the icon is only the visible half. The
   * Instagram logo also points at `/` — it is left alone, because removing the
   * site's own header would look like a broken page and it is not a tab.
   */
  function hideInstagramHomeTab() {
    if (!policy.hideHome || !hostIsInstagram()) return;
    // The feed itself is not shown while the tab is hidden; the reader lands on
    // their own profile instead. Retried on every scan because the nav (which
    // carries the profile link) is drawn after the feed on a cold load.
    if (instagramHomePage()) leaveHomeForProfile();
    var links = document.querySelectorAll('a[href="/"], a[href="/home/"], a[href="/home"]');
    for (var i = 0; i < links.length; i++) {
      var control = navItemContainer(links[i]);
      if (control) hideNode(control, 'insta-home');
    }
    var labels = document.querySelectorAll(
      '[role="button"][aria-label="Home"], [role="link"][aria-label="Home"], ' +
      'svg[aria-label="Home"]'
    );
    for (var j = 0; j < labels.length; j++) {
      var owner = (labels[j].closest && labels[j].closest(
        '[role="button"], [role="link"], a[href]')) || labels[j];
      var container = navItemContainer(owner);
      if (container) hideNode(container, 'insta-home');
    }
  }

  /** The reader's own profile path, from the profile picture in the nav. */
  function instagramOwnProfilePath() {
    var picture = document.querySelector('img[alt$="\'s profile picture"]');
    if (!picture) return null;
    var link = picture.closest ? picture.closest('a[href]') : null;
    var href = link ? (link.getAttribute('href') || '') : '';
    return /^\/[A-Za-z0-9._]{2,30}\/$/.test(href) ? href : null;
  }

  /** Leave the hidden home feed for the reader's own profile. */
  function leaveHomeForProfile() {
    var own = instagramOwnProfilePath();
    if (!own) return false;
    leaveBlockedFeed(own);
    return true;
  }

  function bindReelsGuard() {
    if (reelsGuardBound) return;
    reelsGuardBound = true;
    document.addEventListener('click', function (event) {
      if (!hostIsInstagram()) return;
      if (policy.hideReels) {
        var link = instagramReelLink(event.target);
        if (link) {
          event.preventDefault();
          event.stopImmediatePropagation();
          showPageNotice('Reels are hidden by ClearView');
          return;
        }
      }
      if (policy.hideInstagramSearch) {
        var search = instagramSearchLink(event.target);
        if (search) {
          event.preventDefault();
          event.stopImmediatePropagation();
          showPageNotice('Search is hidden by ClearView');
        }
      }
    }, true);
  }

  // ── Instagram: Brain Rot blocking ───────────────────────────────
  // Instagram has the same two shapes as YouTube: a feed of posts and a
  // full-screen vertical Reels player. Captions (and the author's handle) are
  // sent to the SAME native matcher YouTube content uses, so the reader's
  // Brain Rot keywords and blocked channels apply here without a second list.
  var instagramScanBound = false;

  /** Auth pages are never touched: a stray cover there would break sign-in. */
  function instagramAuthPage() {
    return /^\/accounts(\/|$)/.test(location.pathname || '');
  }

  function instagramReelsPage() {
    return /^\/(reels?)(\/|$)/.test(location.pathname || '');
  }

  /** The home feed, the only place where the caught-up divider lives. */
  function instagramHomePage() {
    var path = location.pathname || '/';
    return path === '/' || /^\/(home|feed)(\/|$)/.test(path);
  }

  function instagramAuthor(node) {
    if (!node || !node.querySelectorAll) return { handle: null, name: null };
    var links = node.querySelectorAll('a[href^="/"]');
    for (var i = 0; i < links.length && i < 40; i++) {
      var href = links[i].getAttribute('href') || '';
      var match = href.match(/^\/([A-Za-z0-9._]{2,40})\/?$/);
      if (!match) continue;
      if (/^(explore|reels?|stories|accounts|direct|p|tv|about|legal|api)$/i
        .test(match[1])) continue;
      var name = (links[i].textContent || '').trim();
      return {
        handle: '@' + match[1].toLowerCase(),
        name: name && name.length <= 60 ? name : null
      };
    }
    return { handle: null, name: null };
  }

  /** Caption text: the explicit caption node first, image alt second. */
  function instagramCaption(node) {
    if (!node) return '';
    var el = node.querySelector('h1');
    var text = el ? (el.textContent || '').trim() : '';
    if (!text) {
      var img = node.querySelector('img[alt]');
      text = img ? (img.getAttribute('alt') || '').trim() : '';
    }
    if (!text && node.innerText) text = (node.innerText || '').trim();
    return text ? text.slice(0, 1400) : '';
  }

  function instagramVisible(node) {
    var rect = node.getBoundingClientRect();
    var height = window.innerHeight || 800;
    return rect.bottom > -260 && rect.top < height + 260;
  }

  function coverPost(post, message) {
    if (post.getAttribute('data-cv-insta-cover') === message) return;
    uncoverPost(post);
    post.setAttribute('data-cv-insta-cover', message);
    try {
      // Absolute positioning needs a positioned ancestor; relative is the one
      // value that cannot move a post that was already static.
      if (!post.style.position || post.style.position === 'static') {
        post.style.position = 'relative';
      }
    } catch (e) { /* ignore */ }
    var cover = document.createElement('div');
    cover.className = 'clearview-post-cover';
    cover.textContent = message;
    cover.style.cssText =
      'position:absolute;inset:0;z-index:30;background:#000;color:#fff;' +
      'display:flex;align-items:center;justify-content:center;' +
      'text-align:center;padding:18px;font:600 13px/1.45 Roboto,Arial,sans-serif;' +
      'white-space:pre-line;pointer-events:auto;box-sizing:border-box;';
    post.appendChild(cover);
  }

  function uncoverPost(post) {
    if (!post.hasAttribute('data-cv-insta-cover')) return;
    post.removeAttribute('data-cv-insta-cover');
    var covers = post.querySelectorAll('.clearview-post-cover');
    for (var i = 0; i < covers.length; i++) covers[i].remove();
  }

  function scanInstagramFeed() {
    var posts = document.querySelectorAll('article');
    for (var i = 0; i < posts.length; i++) {
      var post = posts[i];
      if (!instagramVisible(post)) continue;
      var author = instagramAuthor(post);
      var caption = instagramCaption(post);
      if (!caption && !author.handle) continue;
      var key = [author.handle, caption.slice(0, 180)].join('|');
      if (post.__cvInstaKey === key) continue;
      post.__cvInstaKey = key;

      var verdict = call(bridge.evaluateInstagram, {
        caption: caption,
        channelHandle: author.handle,
        channelName: author.name
      });
      if (verdict && verdict.blocked) {
        var label = 'Blocked by ClearView\n' + (verdict.item || 'Blocked content');
        // Same policy as YouTube's feed cards: remove the post when the reader
        // wants blocked videos gone, otherwise cover it in place. A covered
        // post still occupies the feed, which is why removal is the default.
        if (policy.removeBlocked) removeInstagramPost(post, label);
        else coverPost(post, label);
      } else {
        restoreInstagramPost(post);
        uncoverPost(post);
      }
    }
  }

  function removeInstagramPost(post, label) {
    if (post.getAttribute('data-cv-insta-removed') === '1') return;
    post.setAttribute('data-cv-insta-removed', '1');
    post.setAttribute('data-cv-insta-removed-for', label || 'Blocked by ClearView');
    try { post.style.setProperty('display', 'none', 'important'); } catch (e) { /* ignore */ }
    pauseScopeVideos(post);
  }

  function restoreInstagramPost(post) {
    if (post.getAttribute('data-cv-insta-removed') !== '1') return;
    post.removeAttribute('data-cv-insta-removed');
    post.removeAttribute('data-cv-insta-removed-for');
    try { post.style.removeProperty('display'); } catch (e) { /* ignore */ }
  }

  function pauseScopeVideos(scope) {
    if (!scope || !scope.querySelectorAll) return;
    var videos = scope.querySelectorAll('video');
    for (var i = 0; i < videos.length; i++) {
      try {
        videos[i].pause();
        videos[i].muted = true;
      } catch (e) { /* detached player */ }
    }
  }

  function restoreRemovedInstagramPosts() {
    var posts = document.querySelectorAll('[data-cv-insta-removed]');
    for (var i = 0; i < posts.length; i++) restoreInstagramPost(posts[i]);
  }

  function scanInstagramReels() {
    var video = activeVideo();
    if (!video) return;

    // Climb from the video to the reel's own on-screen container. The reel's
    // caption and author live in that container, not in a feed article.
    var scope = video;
    for (var depth = 0; depth < 9 && scope.parentElement; depth++) {
      var rect = scope.getBoundingClientRect();
      if (rect.width >= (window.innerWidth || 360) * 0.9 &&
          rect.height >= (window.innerHeight || 800) * 0.55) break;
      scope = scope.parentElement;
    }

    var author = instagramAuthor(scope);
    var caption = instagramCaption(scope);
    var key = [location.pathname, author.handle, caption.slice(0, 140)].join('|');
    if (key === lastReelKey) {
      positionCover();
      return;
    }
    lastReelKey = key;

    if (policy.hideReels) {
      showVideoCover('Instagram Reels are hidden by ClearView');
      return;
    }
    var verdict = call(bridge.evaluateInstagram, {
      caption: caption,
      channelHandle: author.handle,
      channelName: author.name
    });
    if (verdict && verdict.blocked) {
      showVideoCover('Blocked by ClearView\n' + (verdict.item || 'Blocked content'));
    } else {
      clearVideoCover();
    }
  }

  // ── Instagram: profile grid and search results ──────────────────
  // A blocked channel's POSTS were blocked in the feed, but opening the
  // channel itself (or finding it in search) showed the whole grid untouched:
  // a grid tile carries no author link, so nothing on it identified the
  // channel. On a profile page the owner is the URL; in search results the
  // tile's own link is the username.
  var INSTAGRAM_RESERVED_PATHS = /^(explore|reels?|stories|direct|accounts|p|tv|about|legal|api|developer|session|emails|challenge|web|your_activity|nominate|blog|careers|help|press|privacy|terms|search|locations)$/i;
  var instagramBlockedProfileNotice = '';

  /** The profile the reader is on, when the URL is a profile page. */
  function instagramProfileHandle() {
    var path = location.pathname || '';
    var parts = path.split('/').filter(function (part) { return !!part; });
    if (parts.length !== 1) return null;
    var name = parts[0];
    if (!/^[A-Za-z0-9._]{2,30}$/.test(name)) return null;
    if (INSTAGRAM_RESERVED_PATHS.test(name)) return null;
    return '@' + name.toLowerCase();
  }

  /** Search results, where the results themselves are profiles. */
  function instagramSearchSurface() {
    var path = location.pathname || '';
    return /^\/(explore|web)\/search/.test(path) || /^\/search\//.test(path);
  }

  /** `@handle` for a link that points at a profile, or null. */
  function instagramProfileHandleOfLink(link) {
    var href = link && link.getAttribute ? (link.getAttribute('href') || '') : '';
    var match = href.match(/^\/([A-Za-z0-9._]{2,30})\/?$/);
    if (!match) return null;
    if (INSTAGRAM_RESERVED_PATHS.test(match[1])) return null;
    return '@' + match[1].toLowerCase();
  }

  /** The grid cell / result row a link belongs to, so the whole tile is covered. */
  function instagramTileFor(link) {
    var node = link;
    for (var depth = 0; depth < 4 && node && node.parentElement; depth++) {
      var rect = node.getBoundingClientRect();
      if (rect.width >= 80 && rect.height >= 80 && node.querySelector('img')) return node;
      node = node.parentElement;
    }
    return link;
  }

  function applyInstagramTile(node, handle) {
    if (!node || !handle) return;
    if (node.__cvInstaTileKey === handle) return;
    node.__cvInstaTileKey = handle;
    var verdict = call(bridge.evaluateInstagram, {
      caption: '',
      channelHandle: handle,
      channelName: null
    });
    if (verdict && verdict.blocked) {
      var label = 'Blocked by ClearView\n' + (verdict.item || handle);
      // Same switch as YouTube's feed cards, so "remove instead of overlay" is
      // one setting for every surface.
      if (policy.removeBlocked) removeInstagramPost(node, label);
      else coverPost(node, label);
      pauseScopeVideos(node);
      return;
    }
    restoreInstagramPost(node);
    uncoverPost(node);
  }

  /** Posts on a profile the reader has blocked, and blocked profiles in search. */
  // ── Instagram: whole-profile cover ─────────────────────────────
  // A blocked channel's profile is one blocked thing. Covering thirty grid
  // tiles one by one left the header, the bio and the tab bar as a working
  // profile you could tap through; one cover over the page says the same thing
  // once and is cheaper to paint.
  var PROFILE_COVER_ID = 'clearview-profile-cover';
  var profileCoverHandle = '';
  var profileVerdict = { handle: '', blocked: false, at: 0 };

  function clearInstagramProfileCover() {
    if (!profileCoverHandle) return;
    profileCoverHandle = '';
    var cover = document.getElementById(PROFILE_COVER_ID);
    if (cover) cover.remove();
  }

  function coverInstagramProfile(handle, reason) {
    if (profileCoverHandle === handle) return;
    clearInstagramProfileCover();
    profileCoverHandle = handle;
    var cover = document.createElement('div');
    cover.id = PROFILE_COVER_ID;
    cover.setAttribute('data-cv-profile-cover', handle);
    cover.textContent = 'Blocked by ClearView\n' + (reason || handle) +
      '\nThis channel is on your block list.';
    cover.style.cssText =
      'position:fixed;inset:0;z-index:2147482000;background:#000;color:#fff;' +
      'display:flex;align-items:center;justify-content:center;text-align:center;' +
      'padding:28px;font:600 15px/1.6 Roboto,Arial,sans-serif;' +
      'white-space:pre-line;pointer-events:auto;box-sizing:border-box;';
    document.body.appendChild(cover);
  }

  /** Cached profile verdict: one bridge call per handle per few seconds. */
  function instagramProfileBlocked(handle) {
    var now = Date.now();
    if (profileVerdict.handle === handle && now - profileVerdict.at < 4000) {
      return profileVerdict.blocked;
    }
    var verdict = call(bridge.evaluateInstagram, {
      caption: '',
      channelHandle: handle,
      channelName: null
    });
    profileVerdict = {
      handle: handle,
      blocked: !!(verdict && verdict.blocked),
      at: now,
      item: (verdict && verdict.item) || handle
    };
    return profileVerdict.blocked;
  }

  function scanInstagramTiles() {
    var profile = instagramProfileHandle();
    var searching = instagramSearchSurface();
    if (!profile && !searching) {
      clearInstagramProfileCover();
      return;
    }

    var tiles = [];
    if (profile) {
      if (instagramProfileBlocked(profile)) {
        coverInstagramProfile(profile, profileVerdict.item);
        return;
      }
      clearInstagramProfileCover();
    }
    if (searching) {
      var users = document.querySelectorAll('a[href^="/"][href$="/"]');
      for (var j = 0; j < users.length && tiles.length < 80; j++) {
        var link = users[j];
        if (!instagramVisible(link) || !link.querySelector('img')) continue;
        var handle = instagramProfileHandleOfLink(link);
        if (!handle) continue;
        tiles.push({ node: instagramTileFor(link), handle: handle });
      }
    }
    for (var t = 0; t < tiles.length; t++) {
      applyInstagramTile(tiles[t].node, tiles[t].handle);
    }
  }

  /**
   * "Suggested for you" units on the Instagram home feed.
   *
   * They are the one part of the feed the reader did not choose to follow, and
   * on a page-blocking product they are the main source of new channels. Feed
   * units are found first (bounded, visible) and checked for the label, so this
   * never scans the whole document for a string.
   */
  // ── Instagram: everything after the "caught up" divider ─────────
  // The home feed does not stop at the accounts you follow. It prints
  // "You're all caught up / You've seen all new posts from the past N days"
  // and then continues with a "Suggested posts" section that loads forever.
  // That whole tail is what the reader means by suggested posts, so it is cut
  // at the divider instead of unit by unit.
  var CAUGHT_UP_LABEL = /(you'?re all caught up|you'?ve seen all new posts|seen all new posts|all caught up)/i;
  var lastCaughtUpScanAt = 0;
  // The divider is remembered: hiding it gives it a zero-size box, so a later
  // text/rect scan could not find it again and the tail would stop being cut.
  var caughtUpAnchor = null;

  /**
   * The divider label elements, shallowest-first is wrong here: only the leaf
   * labels are returned ("You're all caught up" and "You've seen all new posts
   * from the past 3 days.").
   */
  function caughtUpLabels() {
    var labels = [];
    var scope = document.querySelector('main') || document.body;
    if (!scope) return labels;
    var nodes = scope.querySelectorAll('span, div, h2, h3');
    for (var i = 0; i < nodes.length && i < 900; i++) {
      var el = nodes[i];
      if (el.children.length > 1) continue;
      var text = (el.textContent || '').trim();
      if (!text || text.length > 140) continue;
      if (!CAUGHT_UP_LABEL.test(text)) continue;
      var rect = el.getBoundingClientRect();
      // A label is a line of text, not a container.
      if (rect.height > 160 || rect.width === 0) continue;
      labels.push(el);
      if (labels.length >= 3) break;
    }
    return labels;
  }

  /**
   * Hide a label together with its small wrapper.
   *
   * The wrapper is only taken when it holds one or two children and is a line
   * or two tall: a divider container disappears cleanly, while a container
   * that also holds feed posts is never touched.
   */
  function hideLabelWithWrapper(el) {
    var target = el;
    var parent = el.parentElement;
    if (parent && parent !== document.body && parent.children.length <= 2) {
      var rect = parent.getBoundingClientRect();
      var own = el.getBoundingClientRect();
      if (rect.height > 0 && rect.height <= Math.max(160, own.height * 3)) target = parent;
    }
    hideNode(target, 'insta-suggested');
  }

  /**
   * Remove the suggested tail that starts after the "caught up" divider.
   *
   * Only leaf labels and individual `<article>` units are hidden — never a
   * wrapper. An earlier version climbed to the divider's parent and hid it with
   * every following sibling, which on a build that has no `[role="feed]`
   * resolved to the feed's own wrapper and blanked the home page.
   */
  function hideCaughtUpTail() {
    if (!policy.hideInstagramSuggestions || !hostIsInstagram()) return;
    var now = Date.now();
    // The label is found by a text scan, so it is not run on every mutation.
    if (now - lastCaughtUpScanAt < 1500) return;
    lastCaughtUpScanAt = now;

    if (!caughtUpAnchor || !document.contains(caughtUpAnchor)) {
      var labels = caughtUpLabels();
      if (!labels.length) return;
      caughtUpAnchor = labels[0];
      for (var l = 0; l < labels.length; l++) hideLabelWithWrapper(labels[l]);
    }

    var marker = caughtUpAnchor;
    hideNode(marker, 'insta-suggested');
    var units = document.querySelectorAll('article');
    var hidden = 0;
    for (var i = 0; i < units.length && hidden < 120; i++) {
      var unit = units[i];
      // DOCUMENT_POSITION_FOLLOWING: the unit comes after the divider.
      if (!(marker.compareDocumentPosition(unit) & 4)) continue;
      // Everything under the divider is the tail: older posts and the endless
      // suggested stream. The followed channels' new posts are the ones above
      // it, which is exactly the part the reader asked to keep.
      // A hidden unit's player kept running behind the cover — the black screen
      // with reels flickering while the reader scrolled.
      pauseScopeVideos(unit);
      hideNode(unit, 'insta-suggested');
      hidden++;
    }

    // The "Suggested posts" heading that introduces the tail.
    var heads = document.querySelectorAll('span, h2, h3');
    for (var j = 0; j < heads.length && j < 300; j++) {
      var head = heads[j];
      if (head.children.length) continue;
      if (!/^suggested (posts|reels|for you)$/i.test((head.textContent || '').trim())) continue;
      if (marker.compareDocumentPosition(head) & 4) hideLabelWithWrapper(head);
    }

  }

  // ── Instagram: end the feed at the last followed post ─────────────
  var FEED_LOCK_ID = 'clearview-insta-feed-lock';

  /** Undo the feed clamp (toggle off, navigation, or a page with no feed). */
  function clearFeedLock() {
    var style = document.getElementById(FEED_LOCK_ID);
    if (style) style.remove();
    detachScrollGuard();
    var locked = document.querySelectorAll('[data-cv-feed-lock]');
    for (var i = 0; i < locked.length; i++) {
      locked[i].removeAttribute('data-cv-feed-lock');
      try {
        locked[i].style.removeProperty('overflow');
        locked[i].style.removeProperty('max-height');
      } catch (e) { /* detached */ }
    }
  }

  /** Cap the feed at the last followed post, or release it when nothing is cut. */
  function syncFeedLock() {
    if (!hostIsInstagram() || instagramAuthPage()) return;
    // Profiles, explore and reels scroll normally; the cut is a home-feed rule.
    if (!instagramHomePage() || instagramReelsPage()) {
      clearFeedLock();
      return;
    }
    if (policy.hideInstagramSuggestions &&
        document.querySelectorAll('[data-cv-hidden]').length) {
      clampFeedToLastPost();
    } else {
      clearFeedLock();
    }
  }

  /** The element that really scrolls the feed (Instagram uses an inner one). */
  function feedScroller(node) {
    var fallback = null;
    var el = node;
    while (el && el !== document.body && el !== document.documentElement) {
      var style = null;
      try { style = window.getComputedStyle(el); } catch (e) { style = null; }
      if (style && (style.overflowY === 'auto' || style.overflowY === 'scroll')) {
        if (el.scrollHeight > el.clientHeight + 4) return el;
        if (!fallback) fallback = el;
      }
      el = el.parentElement;
    }
    return fallback;
  }

  // A scroll guard is the only thing Instagram cannot override: its own
  // stylesheets re-assert overflow after ours, and its router restores scroll
  // position. The guard puts the page back where the feed was capped.
  var scrollGuard = null;

  function attachScrollGuard(limit) {
    detachScrollGuard();
    scrollGuard = function () {
      // Reading downward is fine until the end of the last followed post; past
      // that there is nothing but hidden units, and the page is put back.
      var maxTop = Math.max(0, limit - (window.innerHeight || 0));
      if ((window.scrollY || 0) > maxTop) {
        window.scrollTo(0, maxTop);
      }
      var locked = document.querySelectorAll('[data-cv-feed-lock]');
      for (var i = 0; i < locked.length; i++) {
        var el = locked[i];
        var box = el.getBoundingClientRect();
        var elMax = Math.max(0, limit - box.top - el.clientHeight);
        if (el.scrollTop > elMax) el.scrollTop = elMax;
      }
    };
    window.addEventListener('scroll', scrollGuard, { passive: true });
    document.addEventListener('scroll', scrollGuard, { passive: true, capture: true });
  }

  function detachScrollGuard() {
    if (!scrollGuard) return;
    window.removeEventListener('scroll', scrollGuard);
    document.removeEventListener('scroll', scrollGuard, true);
    scrollGuard = null;
  }

  /**
   * Stop the document at the last followed post.
   *
   * Hiding the suggested units leaves Instagram's feed container with empty
   * space under them, so the reader could still scroll into an endless blank
   * tail. The document is capped at the bottom of the last visible post: the
   * post itself stays fully readable, and there is nothing past it to scroll
   * into.
   */
  function clampFeedToLastPost() {
    var articles = document.querySelectorAll('article');
    var last = null;
    for (var i = 0; i < articles.length; i++) {
      if (articles[i].getAttribute('data-cv-hidden')) continue;
      last = articles[i];
    }
    if (!last) return;
    var rect = last.getBoundingClientRect();
    // Measured off-screen (the reader scrolled past it): keep the existing
    // clamp rather than shrinking the page to a guess.
    if (rect.bottom < 0) return;
    var bottom = rect.bottom;
    // The remembered posts sit after the last one Instagram drew: the page ends
    // below them, not above them.
    var kept = document.getElementById(INSTA_KEPT_ID);
    if (kept && kept.firstChild) {
      var keptRect = kept.getBoundingClientRect();
      if (keptRect.height > 0 && keptRect.bottom > bottom) bottom = keptRect.bottom;
    }
    var limit = Math.ceil(bottom + (window.scrollY || 0) + 16);
    if (limit < 200) return;
    var style = document.getElementById(FEED_LOCK_ID);
    if (!style) {
      style = document.createElement('style');
      style.id = FEED_LOCK_ID;
      (document.head || document.documentElement).appendChild(style);
    }
    // Instagram's feed scrolls an inner element, not the window, so clamping
    // html/body alone left the page scrollable (the black screen with reels
    // flickering behind it). That element is tagged so the guard can clamp its
    // scrollTop as well.
    var scroller = feedScroller(last);
    if (scroller && scroller !== document.documentElement && scroller !== document.body) {
      scroller.setAttribute('data-cv-feed-lock', '1');
    }
    style.textContent =
      'html,body{max-height:' + limit + 'px !important;height:auto !important;' +
      'overflow-y:hidden !important;overscroll-behavior-y:none !important;}';
    attachScrollGuard(limit);
  }

  /**
   * True when a feed unit is content the reader did not ask for.
   *
   * A post from a followed account never offers "Follow" in its header, so a
   * Follow control is the one reliable signal on the mobile build; the label is
   * checked too for units that print "Suggested for you".
   */
  function isSuggestedUnit(unit) {
    var head = (unit.textContent || '').slice(0, 240);
    if (/suggested for you|suggested posts?|suggested reels?/i.test(head)) return true;
    return hasFollowButton(unit);
  }

  /** True when a feed unit offers "Follow": it is not an account you follow. */
  function hasFollowButton(unit) {
    var buttons = unit.querySelectorAll('button, [role="button"]');
    for (var i = 0; i < buttons.length && i < 6; i++) {
      var text = (buttons[i].textContent || '').trim();
      var label = (buttons[i].getAttribute('aria-label') || '').trim();
      if (/^follow$/i.test(text) || /^follow$/i.test(label)) return true;
      if (/^follow\s/i.test(label) && label.length <= 24) return true;
    }
    return false;
  }

  function hideInstagramSuggestions() {
    if (!policy.hideInstagramSuggestions || !hostIsInstagram()) return;
    hideCaughtUpTail();
    // Units only: an `<article>` (or role=article) is one post. Hiding a
    // `[role=feed] > div` could take a wrapper holding many posts with it.
    var units = document.querySelectorAll('article, [role="article"]');
    for (var i = 0; i < units.length && i < 60; i++) {
      var unit = units[i];
      if (!instagramVisible(unit)) continue;
      if (!isSuggestedUnit(unit)) continue;
      // A feed unit must never take the page with it.
      var rect = unit.getBoundingClientRect();
      if (rect.height > 1400) continue;
      hideNode(unit, 'insta-suggested');
    }
  }

  /**
   * Hide a suggested unit the moment Instagram inserts it.
   *
   * The debounced scan lands 0.5–2.5 s after an insert, which was long enough
   * for the unit to paint and autoplay between two followed posts — the random
   * reel that flashed while scrolling the home feed. Mutation records are
   * delivered before the next paint, so the same test here means the unit is
   * never shown at all.
   */
  function hideNewInstagramUnits(nodes) {
    if (!policy.hideInstagramSuggestions || !hostIsInstagram()) return;
    for (var n = 0; n < nodes.length; n++) {
      var node = nodes[n];
      if (!node || node.nodeType !== 1) continue;
      var units;
      if (node.tagName === 'ARTICLE' || node.getAttribute('role') === 'article') {
        units = [node];
      } else if (node.querySelectorAll) {
        units = node.querySelectorAll('article, [role="article"]');
      } else {
        continue;
      }
      for (var i = 0; i < units.length && i < 12; i++) {
        var unit = units[i];
        if (unit.getAttribute('data-cv-hidden')) continue;
        if (!isSuggestedUnit(unit)) continue;
        // A feed unit must never take the page with it (same bound as the scan).
        if (unit.getBoundingClientRect().height > 1400) continue;
        pauseScopeVideos(unit);
        hideNode(unit, 'insta-suggested');
      }
    }
  }

  // ── Instagram: the story viewer's height ──────────────────────────
  // The story sheet is a fixed shell sized `100vh`. On a WebView whose layout
  // viewport reports 0 (see [viewportHeightIsBroken]) that height becomes 0, so
  // the sheet's centred story hangs half a screen above the top edge and only
  // the bottom of the photo or reel is on screen. The shell is given the height
  // it asked the viewport for; nothing else about the viewer is touched.
  /** The largest video/image on the page — the story itself. */
  function storyMedia() {
    var nodes = document.querySelectorAll('video, img');
    var best = null;
    var bestArea = 0;
    for (var i = 0; i < nodes.length && i < 400; i++) {
      var rect = nodes[i].getBoundingClientRect();
      var area = rect.width * rect.height;
      if (area > bestArea) {
        bestArea = area;
        best = nodes[i];
      }
    }
    return best;
  }

  function repairStoryShell() {
    if (!hostIsInstagram()) return;
    if (!/^\/stories\//.test(location.pathname || '')) return;
    var height = Math.round(window.innerHeight || 0);
    if (height < 120) return;
    var media = storyMedia();
    if (!media) return;
    var node = media;
    for (var depth = 0; depth < 30 && node && node !== document.body; depth++) {
      var position = '';
      try { position = getComputedStyle(node).position; } catch (e) { return; }
      if (position !== 'fixed') {
        node = node.parentElement;
        continue;
      }
      // A shell that already fills the screen is what the page wanted.
      if (node.getBoundingClientRect().height >= height * 0.9) return;
      if (node.getAttribute('data-cv-story-shell') === String(height)) return;
      node.setAttribute('data-cv-story-shell', String(height));
      try { node.style.setProperty('height', height + 'px', 'important'); } catch (e) { /* ignore */ }
      return;
    }
  }

  // ── Instagram: posts that stay ────────────────────────────────────
  // Instagram's home feed is a fresh sample on every load: reload, or come
  // back to Home, and the posts are reordered and the older ones are simply
  // gone. YouTube does not do that — what the reader scrolled past is still
  // below them. Each post is remembered as it renders (author, caption,
  // thumbnail, permalink) and the ones missing from the current feed are put
  // back under it, newest first.
  // v2: v1 stored the author's avatar as the post picture.
  var INSTA_MEMORY_KEY = 'clearview.insta.memory.v2';
  var INSTA_MEMORY_MAX = 80;
  var INSTA_KEPT_ID = 'clearview-insta-kept';
  var INSTA_KEPT_CSS_ID = 'clearview-insta-kept-css';
  var INSTA_KEPT_MAX = 24;
  var instaMemory = null;

  function instaMemoryAll() {
    if (instaMemory) return instaMemory;
    var raw = null;
    try { raw = window.localStorage.getItem(INSTA_MEMORY_KEY); } catch (e) { raw = null; }
    var parsed = null;
    try { parsed = raw ? JSON.parse(raw) : null; } catch (e) { parsed = null; }
    instaMemory = parsed && parsed.posts instanceof Array ? parsed.posts : [];
    return instaMemory;
  }

  function instaMemorySave() {
    try {
      window.localStorage.setItem(
        INSTA_MEMORY_KEY,
        JSON.stringify({ posts: instaMemory.slice(0, INSTA_MEMORY_MAX) })
      );
    } catch (e) { /* storage disabled */ }
  }

  /**
   * The post's permalink, as this build writes it: `/p/<code>/`,
   * `/reel/<code>/`, or the profile form `/<handle>/p/<code>/`.
   */
  function instaPostLink(article) {
    var links = article.querySelectorAll('a[href]');
    for (var i = 0; i < links.length && i < 40; i++) {
      var href = links[i].getAttribute('href') || '';
      if (/^\/(?:[A-Za-z0-9._]+\/)?(?:p|reels?)\/[A-Za-z0-9_-]{5,}\/?$/.test(href)) return href;
    }
    return null;
  }

  /** The `<code>` that identifies the post, from its permalink. */
  function instaPostCode(link) {
    var match = String(link || '').match(/(?:p|reels?)\/([A-Za-z0-9_-]{5,})/);
    return match ? match[1] : null;
  }

  /**
   * The post's own picture.
   *
   * A feed article holds several images and the FIRST one is the author's
   * avatar, which is what an earlier version remembered — the kept cards showed
   * a row of profile pictures instead of the posts. The avatar is the one image
   * inside a link to the author's profile; the media is the largest of the rest.
   */
  function instaPostImage(article) {
    var best = '';
    var bestArea = 0;
    var images = article.querySelectorAll('img');
    for (var i = 0; i < images.length && i < 12; i++) {
      var image = images[i];
      var link = image.closest ? image.closest('a[href]') : null;
      var href = link ? (link.getAttribute('href') || '') : '';
      if (/^\/[A-Za-z0-9._]{2,30}\/$/.test(href)) continue;
      var src = image.getAttribute('src') || '';
      if (!src) continue;
      var rect = image.getBoundingClientRect();
      var area = Math.max(rect.width * rect.height, image.naturalWidth * image.naturalHeight);
      if (area <= bestArea) continue;
      bestArea = area;
      best = src;
    }
    if (!best) {
      var video = article.querySelector('video[poster]');
      if (video) best = video.getAttribute('poster') || '';
    }
    return best;
  }

  function instaPostRecord(article) {
    var link = instaPostLink(article);
    var code = instaPostCode(link);
    if (!code) return null;
    var author = instagramAuthor(article);
    var time = article.querySelector('time');
    return {
      key: code,
      path: link,
      handle: author.handle || '',
      caption: instagramCaption(article).slice(0, 200),
      image: instaPostImage(article),
      ts: time ? (time.getAttribute('datetime') || '') : ''
    };
  }

  /** Store the posts the reader can see now; a post is stored only once. */
  function rememberInstagramPosts() {
    var articles = document.querySelectorAll('article');
    var list = instaMemoryAll();
    var known = {};
    for (var i = 0; i < list.length; i++) known[list[i].key] = true;
    var fresh = [];
    for (var j = 0; j < articles.length && j < 60; j++) {
      if (articles[j].getAttribute('data-cv-hidden')) continue;
      var record = instaPostRecord(articles[j]);
      if (!record || known[record.key]) continue;
      known[record.key] = true;
      fresh.push(record);
    }
    if (!fresh.length) return;
    // The batch keeps the feed's own order, and goes above what was stored
    // before: on a reload the new posts are the newest ones.
    instaMemory = fresh.concat(list).slice(0, INSTA_MEMORY_MAX);
    instaMemorySave();
  }

  function ensureKeptStyles() {
    if (document.getElementById(INSTA_KEPT_CSS_ID)) return;
    var style = document.createElement('style');
    style.id = INSTA_KEPT_CSS_ID;
    style.textContent =
      '#' + INSTA_KEPT_ID + '{display:block;padding-bottom:12px;}' +
      '#' + INSTA_KEPT_ID + ' .cv-kept-title{padding:14px 12px 4px;font-size:11px;' +
      'letter-spacing:.08em;text-transform:uppercase;opacity:.55;}' +
      '#' + INSTA_KEPT_ID + ' .cv-kept-card{display:block;margin:10px 12px;border-radius:12px;' +
      'overflow:hidden;border:1px solid rgba(128,128,128,.28);text-decoration:none;color:inherit;}' +
      '#' + INSTA_KEPT_ID + ' .cv-kept-head{padding:9px 11px;font-size:13px;font-weight:600;}' +
      '#' + INSTA_KEPT_ID + ' .cv-kept-img{display:block;width:100%;height:auto;}' +
      '#' + INSTA_KEPT_ID + ' .cv-kept-cap{padding:9px 11px 11px;font-size:13px;line-height:1.35;' +
      'opacity:.8;word-break:break-word;}';
    (document.head || document.documentElement).appendChild(style);
  }

  function keptCard(record) {
    var card = document.createElement('a');
    card.className = 'cv-kept-card';
    card.setAttribute('href', record.path || ('/p/' + record.key + '/'));
    card.setAttribute('data-cv-kept', record.key);
    var head = document.createElement('div');
    head.className = 'cv-kept-head';
    head.textContent = record.handle || 'Instagram';
    card.appendChild(head);
    if (record.image) {
      var img = document.createElement('img');
      img.className = 'cv-kept-img';
      img.setAttribute('alt', '');
      // Instagram's CDN links expire; a card without its photo still reads.
      img.addEventListener('error', function () { if (img.parentNode) img.parentNode.removeChild(img); });
      img.setAttribute('src', record.image);
      card.appendChild(img);
    }
    if (record.caption) {
      var caption = document.createElement('div');
      caption.className = 'cv-kept-cap';
      caption.textContent = record.caption;
      card.appendChild(caption);
    }
    return card;
  }

  /** The posts Instagram is rendering right now, by permalink code. */
  function instaRenderedCodes() {
    var codes = {};
    var articles = document.querySelectorAll('article');
    for (var i = 0; i < articles.length && i < 80; i++) {
      var code = instaPostCode(instaPostLink(articles[i]));
      if (code) codes[code] = true;
    }
    return codes;
  }

  function lastShownArticle() {
    var articles = document.querySelectorAll('article');
    var last = null;
    for (var i = 0; i < articles.length; i++) {
      if (articles[i].getAttribute('data-cv-hidden')) continue;
      last = articles[i];
    }
    return last;
  }

  /** Put the remembered posts the feed no longer shows back under it. */
  function renderKeptInstagramPosts() {
    var rendered = instaRenderedCodes();
    var stored = instaMemoryAll();
    var missing = [];
    for (var i = 0; i < stored.length && missing.length < INSTA_KEPT_MAX; i++) {
      if (!rendered[stored[i].key]) missing.push(stored[i]);
    }
    var section = document.getElementById(INSTA_KEPT_ID);
    if (!missing.length) {
      if (section && section.parentNode) section.parentNode.removeChild(section);
      return;
    }
    var last = lastShownArticle();
    if (!last || !last.parentNode) return;
    if (!section) {
      section = document.createElement('section');
      section.id = INSTA_KEPT_ID;
    }
    // Last child of the list that holds the posts, so it scrolls with the feed
    // and stays under everything Instagram draws. Instagram inserts its own
    // posts before it, which is exactly the order wanted.
    var container = last.parentElement;
    if (section.parentNode !== container || container.lastElementChild !== section) {
      container.appendChild(section);
    }
    var signature = '';
    for (var s = 0; s < missing.length; s++) signature += missing[s].key + ',';
    if (section.getAttribute('data-cv-signature') === signature) return;
    section.setAttribute('data-cv-signature', signature);
    ensureKeptStyles();
    while (section.firstChild) section.removeChild(section.firstChild);
    var title = document.createElement('div');
    title.className = 'cv-kept-title';
    title.textContent = 'Earlier — kept by ClearView';
    section.appendChild(title);
    for (var c = 0; c < missing.length; c++) section.appendChild(keptCard(missing[c]));
  }

  // ── Instagram: coming back to the feed ───────────────────────────
  // Instagram's router puts the feed back at the top when the reader returns
  // from a profile or a story. The position they left is restored instead, so
  // the posts they were reading are still where they left them.
  var instaHomeScroll = 0;
  var instaHomeLeftAt = 0;
  var instaHomeRestores = 0;

  function trackInstagramHomeScroll() {
    if (hostIsInstagram() && instagramHomePage()) instaHomeScroll = window.scrollY || 0;
  }

  function rememberInstagramHomeScroll() {
    instaHomeLeftAt = Date.now();
    instaHomeRestores = 0;
  }

  function restoreInstagramFeedScroll() {
    if (instaHomeRestores >= 3) return;
    if (!instaHomeLeftAt || Date.now() - instaHomeLeftAt > 20000) return;
    // The reader is somewhere already, or never went far in the first place.
    if ((window.scrollY || 0) > 40) return;
    if (instaHomeScroll < 200) return;
    instaHomeRestores++;
    try { window.scrollTo(0, instaHomeScroll); } catch (e) { /* ignore */ }
  }

  function scanInstagram() {
    if (!hostIsInstagram()) return;
    if (instagramAuthPage()) {
      uncoverAllPosts();
      clearVideoCover();
      clearInstagramProfileCover();
      clearFeedLock();
      return;
    }
    hideInstagramSuggestions();
    hideInstagramHomeTab();
    repairStoryShell();
    if (instagramReelsPage()) {
      scanInstagramReels();
      return;
    }
    if (instagramHomePage()) {
      rememberInstagramPosts();
      renderKeptInstagramPosts();
      restoreInstagramFeedScroll();
    }
    // Posts load in as the reader scrolls; the cap has to follow the newest
    // followed post or reading would stop at whatever was rendered first.
    syncFeedLock();
    clearVideoCover();
    scanInstagramFeed();
    scanInstagramTiles();
  }

  function uncoverAllPosts() {
    var posts = document.querySelectorAll('[data-cv-insta-cover]');
    for (var i = 0; i < posts.length; i++) uncoverPost(posts[i]);
  }

  // ── Instagram: block the author ────────────────────────────────
  // YouTube gets an explicit "Block channel with ClearView" row inside its own
  // three-dot menu. Instagram is the same act: the reader taps the post's
  // "More options", ClearView adds its row to the sheet that opens, and the
  // block lands in the same list as a YouTube block — tagged as coming from
  // Instagram. When a build renders no sheet this script can reach, a small
  // in-page action is offered instead, so the feature never silently vanishes.
  var INSTAGRAM_MENU_SELECTORS = [
    'div[role="dialog"]',
    '[role="dialog"]',
    '[role="menu"]',
    'div[aria-modal="true"]'
  ];
  var instagramMenuInfo = null;
  var instagramMenuAt = 0;
  var instagramFallbackTimer = null;

  /** The post/reel a tapped control belongs to. */
  function instagramScopeFor(node) {
    if (!node) return null;
    var post = node.closest ? node.closest('article') : null;
    if (post) return post;
    var video = activeVideo();
    if (!video) return null;
    var scope = video;
    for (var depth = 0; depth < 9 && scope.parentElement; depth++) {
      var rect = scope.getBoundingClientRect();
      if (rect.width >= (window.innerWidth || 360) * 0.9 &&
          rect.height >= (window.innerHeight || 800) * 0.55) return scope;
      scope = scope.parentElement;
    }
    return null;
  }

  function rememberInstagramMenu(event) {
    if (!hostIsInstagram()) return false;
    var button = firstInPath(
      event,
      'button[aria-label*="More" i], [role="button"][aria-label*="More" i], ' +
      '[aria-label="More options"], [aria-label="More options" i]'
    );
    if (!button) return false;
    var scope = instagramScopeFor(button);
    if (!scope) return false;
    var author = instagramAuthor(scope);
    if (!author.handle) return false;
    var stale = document.querySelectorAll('[data-cv-insta-menu-item]');
    for (var i = 0; i < stale.length; i++) stale[i].remove();
    instagramMenuInfo = { handle: author.handle, name: author.name, scope: scope };
    instagramMenuAt = Date.now();
    if (instagramFallbackTimer) clearTimeout(instagramFallbackTimer);
    instagramFallbackTimer = setTimeout(function () {
      instagramFallbackTimer = null;
      if (!instagramMenuInfo) return;
      // The sheet never appeared (or is not reachable): offer the action in the
      // page instead of dropping the reader's intent.
      var dialog = visibleInstagramMenu();
      if (dialog && dialog.querySelector('[data-cv-insta-menu-item]')) return;
      showInstagramAction(instagramMenuInfo);
    }, 900);
    return true;
  }

  function visibleInstagramMenu() {
    for (var s = 0; s < INSTAGRAM_MENU_SELECTORS.length; s++) {
      var menus = document.querySelectorAll(INSTAGRAM_MENU_SELECTORS[s]);
      for (var i = 0; i < menus.length; i++) {
        if ((menus[i].textContent || '').length > 1200) continue;
        if (!elementIsVisible(menus[i])) continue;
        return menus[i];
      }
    }
    return null;
  }

  function injectInstagramMenuAction() {
    if (!hostIsInstagram()) return;
    var dialog = visibleInstagramMenu();
    if (!dialog || dialog.querySelector('[data-cv-insta-menu-item]')) return;
    var info = instagramMenuInfo;
    if (!info || Date.now() - instagramMenuAt > 10000) return;
    // Clone one of Instagram's own rows so the injected item keeps the sheet's
    // typography, height, ripple and theme.
    var rows = dialog.querySelectorAll('button, [role="menuitem"], [role="button"]');
    var donor = null;
    for (var r = 0; r < rows.length; r++) {
      var rect = rows[r].getBoundingClientRect();
      if (rect.height < 20 || !(rows[r].textContent || '').trim()) continue;
      donor = rows[r];
      break;
    }
    if (!donor || !donor.parentNode) return;

    var state = call(bridge.channelState, {
      channelHandle: info.handle,
      surface: 'instagram'
    });
    var label = state && state.blocked
      ? 'Unblock @' + info.handle.replace(/^@/, '') + ' with ClearView'
      : 'Block @' + info.handle.replace(/^@/, '') + ' with ClearView';

    var clone = donor.cloneNode(true);
    clone.removeAttribute('id');
    var ids = clone.querySelectorAll('[id]');
    for (var d = 0; d < ids.length; d++) ids[d].removeAttribute('id');
    clone.setAttribute('data-cv-insta-menu-item', '1');
    clone.setAttribute('aria-label', label);
    var leaves = clone.querySelectorAll('*');
    var painted = null;
    for (var l = 0; l < leaves.length; l++) {
      if (leaves[l].children.length === 0 && (leaves[l].textContent || '').trim()) {
        painted = leaves[l];
        break;
      }
    }
    if (painted) painted.textContent = label;
    else clone.textContent = label;
    (function (target) {
      clone.addEventListener('click', function (event) {
        event.preventDefault();
        event.stopImmediatePropagation();
        toggleInstagramBlock(target);
      }, true);
    })(info);
    donor.parentNode.insertBefore(clone, donor);
  }

  /** The in-page fallback when no sheet could be reached. */
  function showInstagramAction(info) {
    var existing = document.querySelector('[data-cv-insta-action]');
    if (existing) existing.remove();
    var state = call(bridge.channelState, {
      channelHandle: info.handle,
      surface: 'instagram'
    });
    var blocked = !!(state && state.blocked);
    var button = document.createElement('button');
    button.setAttribute('data-cv-insta-action', '1');
    button.textContent = (blocked ? 'Unblock ' : 'Block ') + info.handle +
      ' by ClearView';
    button.style.cssText =
      'position:fixed;left:50%;transform:translateX(-50%);bottom:96px;z-index:2147483000;' +
      'background:#111;color:#fff;border:1px solid rgba(255,255,255,.35);' +
      'border-radius:22px;padding:11px 18px;font:600 13px Roboto,Arial,sans-serif;' +
      'box-shadow:0 6px 18px rgba(0,0,0,.45);';
    button.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopImmediatePropagation();
      button.remove();
      toggleInstagramBlock(info);
    }, true);
    document.body.appendChild(button);
    setTimeout(function () {
      if (button.parentNode) button.remove();
    }, 8000);
  }

  function instagramPostMatches(post, info) {
    var author = instagramAuthor(post);
    return !!(author.handle && info.handle &&
      author.handle.toLowerCase() === info.handle.toLowerCase());
  }

  function applyInstagramBlock(info, blocked) {
    var posts = document.querySelectorAll('article');
    for (var i = 0; i < posts.length; i++) {
      if (!instagramPostMatches(posts[i], info)) continue;
      if (blocked) {
        var label = 'Blocked by ClearView\n' + info.handle;
        if (policy.removeBlocked) removeInstagramPost(posts[i], label);
        else coverPost(posts[i], label);
      } else {
        restoreInstagramPost(posts[i]);
        uncoverPost(posts[i]);
        delete posts[i].__cvInstaKey;
      }
    }
    if (blocked && info.scope && instagramPostMatches(info.scope, info)) {
      if (policy.removeBlocked) removeInstagramPost(info.scope, 'Blocked by ClearView\n' + info.handle);
      else coverPost(info.scope, 'Blocked by ClearView\n' + info.handle);
    }
    if (!blocked) {
      lastReelKey = '';
      clearVideoCover();
      scanInstagram();
    }
  }

  function toggleInstagramBlock(info) {
    if (!info || !info.handle) {
      showPageNotice('ClearView could not identify this account');
      return;
    }
    var payload = {
      channelHandle: info.handle,
      channelName: info.name,
      surface: 'instagram'
    };
    var state = call(bridge.channelState, payload);
    if (state && state.blocked) {
      var removed = call(bridge.unblockChannel, payload);
      if (!removed || !removed.unblocked) {
        showPageNotice('ClearView could not unblock this account');
        return;
      }
      showPageNotice('Account unblocked by ClearView');
      instagramMenuInfo = null;
      applyInstagramBlock(info, false);
      return;
    }
    var response = call(bridge.blockChannel, payload);
    if (!response || !response.blocked) {
      showPageNotice('ClearView could not block this account');
      return;
    }
    showPageNotice('Account blocked by ClearView');
    instagramMenuInfo = null;
    applyInstagramBlock(info, true);
    // Every later post is judged by the scan with the new list.
    lastReelKey = '';
    scanInstagram();
  }

  function bindInstagramActions() {
    if (instagramActionsBound) return;
    instagramActionsBound = true;
    document.addEventListener('click', function (event) {
      rememberInstagramMenu(event);
    }, true);
  }

  var instagramActionsBound = false;

  // ── Three-dot menu: "Not interested" ────────────────────────────
  // The app already treats YouTube's "Not interested" as a decision about a
  // channel (NotInterestedCoordinator). The browser does the same: the click
  // is captured before YouTube's menu is torn down, the card whose menu was
  // opened supplies the identity, and the native side stores the block in the
  // same Brain Rot list. YouTube's own action is left to run as well.
  var MENU_BUTTON_SELECTOR =
    'button[aria-label*="More" i], button[aria-label*="Action" i], ' +
    'yt-icon-button[aria-label*="More" i], ytd-menu-renderer button, ' +
    '[role="button"][aria-label*="More" i], [role="button"][aria-label*="Action" i], ' +
    '.ytp-more-videos-button, button.ytp-button[aria-label*="More" i]';

  var PLAYER_SELECTOR =
    '#movie_player, #player, .html5-video-player, #player-container-id, ytm-player';

  function elementIsVisible(el) {
    try {
      var rect = el.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0;
    } catch (e) { return false; }
  }

  function infoFromCard(card) {
    var videoId = videoIdFromCard(card);
    var channel = cardChannel(card, videoId);
    return {
      card: card,
      channelHandle: channel.channelHandle,
      channelId: channel.channelId,
      channelName: channel.channelName,
      title: cardTitle(card),
      videoId: videoId
    };
  }

  /**
   * Identity that belongs to the page itself: the channel whose page is open,
   * or the video being watched.
   *
   * A channel page's video cards usually have no channel link of their own (all
   * of them belong to that channel), which is why blocking from one of those
   * menus used to report "could not identify" and do nothing. The URL is the
   * page's own identity and cannot be stale.
   */
  function channelIdentityFromUrl() {
    var path = location.pathname || '';
    var handle = path.match(/^\/@([A-Za-z0-9._-]{2,100})/);
    var id = path.match(/^\/channel\/(UC[\w-]{10,})/);
    if (!handle && !id) return null;
    var info = channelInfo();
    return {
      card: null,
      channelHandle: handle ? '@' + handle[1] : info.channelHandle,
      channelId: id ? id[1] : info.channelId,
      channelName: info.channelName,
      title: null,
      videoId: null
    };
  }

  /**
   * The identity a menu belongs to, resolved from the control that opened it.
   *
   * YouTube marks the open menu's button `aria-expanded`, but not every build
   * does; the clicked control also keeps focus on Android WebView. When a card
   * is found but carries no channel of its own (a channel page), the page URL
   * supplies the channel instead of reporting failure.
   */
  function menuInfoFromAnchor(anchor) {
    if (!anchor) return null;
    var card = closestDeep(anchor, CARD_SELECTORS.join(','));
    if (card) {
      var info = infoFromCard(card);
      if (info.channelHandle || info.channelId) return info;
      var pageInfo = channelIdentityFromUrl();
      if (pageInfo) {
        pageInfo.card = card;
        return pageInfo;
      }
      return info;
    }
    if (closestDeep(anchor, PLAYER_SELECTOR)) {
      var videoInfo = collectVideo();
      videoInfo.card = null;
      return videoInfo;
    }
    return null;
  }

  function liveMenuAnchor() {
    var marked = document.querySelectorAll('[aria-expanded="true"]');
    var found = null;
    for (var i = 0; i < marked.length; i++) {
      if (!elementIsVisible(marked[i])) continue;
      if (menuInfoFromAnchor(marked[i])) found = marked[i];
    }
    // The most recently opened control wins: a menu that YouTube has not
    // finished tearing down can leave an older `aria-expanded` behind.
    if (found) return found;
    // The control that opened a menu keeps focus on Android WebView. Only a
    // control that declares a popup is accepted: a focused Subscribe button on
    // a different card must never be mistaken for the menu's owner.
    var active = document.activeElement;
    if (active && active !== document.body && active !== document.documentElement &&
        active.matches && elementIsVisible(active) &&
        active.matches('[aria-haspopup], [aria-expanded], .ytp-more-videos-button')) {
      return active;
    }
    return null;
  }

  /** The first open, visible menu container (a sheet or popup). */
  /** Joined once, on first use: the selector array is declared further down. */
  var menuContainerList = null;

  function visibleMenuContainer() {
    if (!menuContainerList) menuContainerList = MENU_CONTAINER_SELECTORS.join(',');
    // One query for the whole list: this runs on the mutation path, and a
    // query per selector (eleven document-wide lookups) was measurable on a
    // page that mutates while a video plays.
    var menus;
    try { menus = document.querySelectorAll(menuContainerList); } catch (e) { return null; }
    for (var i = 0; i < menus.length; i++) {
      if (menus[i].querySelector('[data-cv-menu-item]')) continue;
      if (elementIsVisible(menus[i])) return menus[i];
    }
    return null;
  }

  /**
   * Capture the identity the just-opened menu belongs to.
   *
   * Done at click time because the sheet is teleported out of the card and the
   * card list can be re-rendered before the reader picks the injected row. The
   * captured identity is only trusted for a few seconds: the old 60-second
   * window meant a menu opened by a control this listener did not recognise
   * reused the PREVIOUS menu's channel, which is how the wrong channel got
   * blocked.
   */
  function rememberMenuCard(event) {
    var target = event.target;
    if (!target) return false;
    // composedPath crosses shadow boundaries: a three-dot rendered inside a
    // view-model's shadow root retargets to its host, so `closest` alone would
    // never see the control or the card it belongs to.
    var button = firstInPath(event, MENU_BUTTON_SELECTOR);
    if (!button) {
      button = firstInPath(
        event,
        'button[aria-haspopup], [role="button"][aria-haspopup], [aria-haspopup]'
      );
    }
    if (!button) return false;

    // A reopened menu can reuse its DOM node; an item injected for the previous
    // card would otherwise survive and act on the new menu's channel.
    var stale = document.querySelectorAll('[data-cv-menu-item]');
    for (var i = 0; i < stale.length; i++) stale[i].remove();

    lastMenuAnchor = button;
    menuTargetInfo = menuInfoFromAnchor(button);
    lastMenuCard = menuTargetInfo ? menuTargetInfo.card : null;
    lastMenuVideo = (!lastMenuCard && isVideoPage())
      ? (menuTargetInfo || collectVideo())
      : null;
    lastMenuAt = Date.now();
    menuOpenedAt = Date.now();
    return true;
  }

  /**
   * The channel the currently-open menu belongs to.
   *
   * Order matters: the menu's live anchor is the most precise source, the click
   * capture covers builds that do not mark the anchor, and the page itself is
   * the fallback for watch/channel pages. If none of them can name a channel
   * this returns null and no action is injected — better no option than an
   * option that blocks the wrong channel.
   */
  function currentMenuInfo() {
    var live = menuInfoFromAnchor(liveMenuAnchor());
    if (live && (live.channelHandle || live.channelId)) return live;

    if (menuTargetInfo && Date.now() - lastMenuAt < 8000) {
      if (menuTargetInfo.channelHandle || menuTargetInfo.channelId) {
        return menuTargetInfo;
      }
    }
    if (lastMenuAnchor && document.contains(lastMenuAnchor) &&
        Date.now() - lastMenuAt < 8000) {
      var fromAnchor = menuInfoFromAnchor(lastMenuAnchor);
      if (fromAnchor && (fromAnchor.channelHandle || fromAnchor.channelId)) {
        return fromAnchor;
      }
    }
    if (isVideoPage()) {
      var info = collectVideo();
      if (info.channelHandle || info.channelId) {
        info.card = null;
        return info;
      }
    }
    return channelIdentityFromUrl();
  }

  function resetMenuIdentity() {
    lastMenuCard = null;
    lastMenuVideo = null;
    lastMenuAnchor = null;
    menuTargetInfo = null;
    lastMenuAt = 0;
    menuOpenedAt = 0;
  }

  /** Re-open every card that was hidden/tinted so changed rules apply. */
  function rejudgeCards() {
    var cards = allCards();
    for (var i = 0; i < cards.length; i++) {
      var card = cards[i];
      restoreCard(card);
      restoreCard(removableCard(card));
      uncoverCard(card);
      uncoverCard(removableCard(card));
      delete card.__cvKey;
      delete card.__cvAllowedKey;
    }
    clearContentBlock();
    lastVideoKey = '';
    setTimeout(function () {
      evaluateVideo();
      scanCards();
    }, 150);
  }

  // ── Injected "Block / Unblock" menu action ──────────────────────
  // YouTube's own "Not interested" is a recommendation nudge, not a block, so
  // the browser adds an explicit action to the same menu. The item is cloned
  // from a real YouTube menu row, which keeps its height, ripple, typography
  // and dark/light theme without shipping a second stylesheet.
  var MENU_CONTAINER_SELECTORS = [
    'ytm-sheet-renderer',
    'ytm-sheet-view-model',
    'yt-sheet-view-model',
    'ytm-bottom-sheet-view-model',
    'yt-bottom-sheet-view-model',
    'ytm-menu-popup-renderer',
    'bottom-sheet-container',
    'ytd-menu-popup-renderer',
    'tp-yt-iron-dropdown'
  ];
  var MENU_ITEM_SELECTOR =
    'ytm-menu-service-item-renderer, ytd-menu-service-item-renderer, ' +
    'tp-yt-paper-item, yt-list-item-view-model, ytm-list-item-view-model, ' +
    'yt-sheet-view-model yt-list-item-view-model, [role="menuitem"]';

  // Labels that identify a real action row when the sheet renders plain
  // buttons instead of menu-item elements (some mobile YouTube builds do).
  var MENU_ROW_LABELS =
    /not interested|save to watch later|save to playlist|send feedback|report|share|download|don't recommend/i;

  function menuRows(menu) {
    var rows = Array.prototype.slice.call(menu.querySelectorAll(MENU_ITEM_SELECTOR));
    if (rows.length) return rows;
    var buttons = menu.querySelectorAll('button');
    for (var i = 0; i < buttons.length; i++) {
      var text = (buttons[i].textContent || '').trim();
      if (!text || text.length > 60) continue;
      if (!MENU_ROW_LABELS.test(text)) continue;
      rows.push(buttons[i]);
    }
    return rows;
  }

  /** The account/settings sheet is not a content menu. */
  function isAccountMenu(menu) {
    var text = (menu.textContent || '').toLowerCase();
    return (text.indexOf('sign in') !== -1 || text.indexOf('your data in youtube') !== -1) &&
      text.indexOf('not interested') === -1 &&
      text.indexOf('save to watch later') === -1;
  }

  function setMenuLabel(root, text) {
    var all = root.querySelectorAll('*');
    var leaf = null;
    var fallback = null;
    for (var i = 0; i < all.length; i++) {
      var node = all[i];
      if (node.children.length !== 0) continue;
      if (!(node.textContent || '').trim()) continue;
      if (!fallback) fallback = node;
      var rect = node.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        leaf = node;
        break;
      }
    }
    if (!leaf) leaf = fallback;
    if (leaf) leaf.textContent = text;
    else root.textContent = text;
    root.setAttribute('aria-label', text);
    root.setAttribute('title', text);
  }

  function injectMenuAction() {
    if (!hostIsYouTube()) return;
    // Cheap exits: no menu can be open if no three-dot button was tapped and
    // no menu-shaped element is on screen.
    var recent = menuOpenedAt && Date.now() - menuOpenedAt <= 10000;
    if (!recent && !visibleMenuContainer()) return;
    for (var s = 0; s < MENU_CONTAINER_SELECTORS.length; s++) {
      var menus = document.querySelectorAll(MENU_CONTAINER_SELECTORS[s]);
      for (var i = 0; i < menus.length; i++) {
        var menu = menus[i];
        if (menu.querySelector('[data-cv-menu-item]')) continue;
        if ((menu.textContent || '').length > 1500) continue;
        if (isAccountMenu(menu)) continue;

        var rows = menuRows(menu);
        var item = null;
        for (var r = 0; r < rows.length && !item; r++) {
          // The custom element that hosts a mobile sheet can have no layout
          // box of its own (height 0); the item inside it is what is drawn.
          var rect = rows[r].getBoundingClientRect();
          if (rect.height >= 10 && rect.width >= 40) item = rows[r];
        }
        if (!item || !item.parentNode) continue;

        var info = currentMenuInfo();
        // Blocking needs the @handle (that is the app's channel identity), so
        // an id-only guess would only produce an option that cannot work.
        if (!info || !info.channelHandle) continue;
        var state = call(bridge.channelState, {
          channelHandle: info.channelHandle,
          channelId: info.channelId
        });
        var blocked = !!(state && state.blocked);
        var label = blocked
          ? 'Unblock channel with ClearView'
          : 'Block channel with ClearView';

        var clone = item.cloneNode(true);
        clone.removeAttribute('id');
        var ids = clone.querySelectorAll('[id]');
        for (var d = 0; d < ids.length; d++) ids[d].removeAttribute('id');
        clone.setAttribute('data-cv-menu-item', '1');
        clone.setAttribute('role', 'menuitem');
        setMenuLabel(clone, label);
        // The identity is frozen into THIS menu's item. If the reader opens a
        // different card's menu, that menu gets its own item with its own
        // channel, so the old item can never act on the new menu.
        (function (targetInfo) {
          clone.addEventListener('click', function (event) {
            event.preventDefault();
            event.stopImmediatePropagation();
            toggleBlockFromMenu(targetInfo);
          }, true);
        })(info);
        item.parentNode.insertBefore(clone, item);
      }
    }
  }

  /** True when a card shows the channel that was just blocked. */
  function cardMatchesChannel(card, info) {
    if (!info) return false;
    var cardInfo = cardChannel(card, videoIdFromCard(card));
    if (info.channelId && cardInfo.channelId === info.channelId) return true;
    if (info.channelHandle && cardInfo.channelHandle &&
        info.channelHandle.toLowerCase() === cardInfo.channelHandle.toLowerCase()) {
      return true;
    }
    // Name-only cards exist (channel pages and compact shelves). A name is a
    // weaker identity, so it is only compared exactly.
    if (!cardInfo.channelId && !cardInfo.channelHandle &&
        info.channelName && cardInfo.channelName &&
        info.channelName.trim().toLowerCase() === cardInfo.channelName.trim().toLowerCase()) {
      return true;
    }
    return false;
  }

  /** Take every visible card of the blocked channel out of the current view. */
  function removeChannelCards(info) {
    var cards = allCards();
    for (var i = 0; i < cards.length; i++) {
      var card = cards[i];
      if (!cardMatchesChannel(card, info)) continue;
      if (policy.removeBlocked) {
        removeCard(card);
      } else {
        coverCard(card, 'Blocked by ClearView\n' +
          (info.channelHandle || info.channelName || ''));
      }
    }
  }

  /**
   * Block or unblock the channel behind one menu/watch action.
   *
   * [info] is the identity captured for that action, never re-resolved from
   * the live DOM: by the time the reader taps, YouTube may have recycled the
   * list, and re-reading it is what blocked an unrelated channel.
   */
  function toggleBlockFromMenu(info) {
    info = info || currentMenuInfo();
    if (!info || !info.channelHandle) {
      showPageNotice('ClearView could not identify this channel');
      return;
    }

    var payload = {
      channelHandle: info.channelHandle,
      channelId: info.channelId,
      channelName: info.channelName
    };
    var state = call(bridge.channelState, payload);
    var wasBlocked = !!(state && state.blocked);

    if (wasBlocked) {
      var removed = call(bridge.unblockChannel, payload);
      if (!removed || !removed.unblocked) {
        showPageNotice('ClearView could not unblock this channel');
        return;
      }
      showPageNotice('Channel unblocked by ClearView');
      resetMenuIdentity();
      rejudgeCards();
      lastWatchActionAt = 0;
      updateWatchAction();
      return;
    }

    var response = call(bridge.blockChannel, payload);
    if (!response || !response.blocked) {
      showPageNotice('ClearView could not block this channel');
      return;
    }
    showPageNotice('Channel blocked by ClearView');

    var cards = allCards();
    for (var i = 0; i < cards.length; i++) {
      delete cards[i].__cvKey;
      delete cards[i].__cvAllowedKey;
    }
    // Remove the whole channel straight away, not just the card whose menu was
    // used: on a feed the same channel can own several visible cards.
    removeChannelCards(info);
    if (info.card) removeCard(info.card);

    resetMenuIdentity();
    clearContentBlock();
    lastVideoKey = '';
    evaluateVideo();
    scanCards();
    lastWatchActionAt = 0;
    updateWatchAction();
  }

  // ── Watch page: explicit channel action ────────────────────────
  // Some watch layouts (mobile YouTube in particular) hide the three-dot that
  // carries the menu action. The reader asked for the block to be reachable
  // where they watch a video, so the action is also added to the page's own
  // action row when that row exists.
  var WATCH_ACTION_ATTR = 'data-cv-watch-action';
  var WATCH_ACTION_HOSTS =
    'ytm-slim-video-action-bar-renderer, #top-level-buttons-computed, ' +
    'ytd-watch-metadata #actions, ytm-watch-metadata-renderer #actions';

  function removeWatchAction() {
    var existing = document.querySelectorAll('[' + WATCH_ACTION_ATTR + ']');
    for (var i = 0; i < existing.length; i++) existing[i].remove();
  }

  var lastWatchActionAt = 0;

  function updateWatchAction() {
    if (!hostIsYouTube() || !isWatchPage()) {
      removeWatchAction();
      return;
    }
    // collectVideo() reads several DOM scopes; the mutation observer can fire
    // many times per second on a watch page, so this runs at most once a
    // second and only after an action that could change the label bypasses it
    // by resetting lastWatchActionAt.
    if (Date.now() - lastWatchActionAt < 1000) return;
    lastWatchActionAt = Date.now();
    var host = document.querySelector(WATCH_ACTION_HOSTS);
    if (!host) return;
    var info = collectVideo();
    if (!info.channelHandle) {
      removeWatchAction();
      return;
    }
    var state = call(bridge.channelState, {
      channelHandle: info.channelHandle,
      channelId: info.channelId
    });
    var blocked = !!(state && state.blocked);
    var label = blocked ? 'Unblock channel' : 'Block channel';

    var existing = host.querySelector('[' + WATCH_ACTION_ATTR + ']');
    if (existing) {
      if (existing.getAttribute('data-cv-state') === label) return;
      existing.setAttribute('data-cv-state', label);
      existing.textContent = 'ClearView · ' + label;
      existing.setAttribute('aria-label', label + ' with ClearView');
      return;
    }
    removeWatchAction();

    var button = document.createElement('button');
    button.setAttribute(WATCH_ACTION_ATTR, '1');
    button.setAttribute('data-cv-state', label);
    button.type = 'button';
    button.textContent = 'ClearView · ' + label;
    button.setAttribute('aria-label', label + ' with ClearView');
    // Deliberately neutral: it inherits the page's text colour so it reads as
    // part of the action row in both YouTube themes without a second theme.
    button.style.cssText =
      'display:inline-flex;align-items:center;justify-content:center;' +
      'height:36px;margin:0 8px 0 0;padding:0 14px;border-radius:18px;' +
      'border:1px solid rgba(128,128,128,0.55);background:transparent;' +
      'color:inherit;font:500 12px/1 Roboto,Arial,sans-serif;cursor:pointer;' +
      'white-space:nowrap;flex:0 0 auto;';
    button.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      var current = collectVideo();
      if (!current.channelHandle) return;
      toggleBlockFromMenu(current);
    }, true);
    host.appendChild(button);
  }

  function bindMenuActions() {
    if (menuActionBound) return;
    menuActionBound = true;
    document.addEventListener('click', function (event) {
      if (!hostIsYouTube()) return;
      if (!rememberMenuCard(event)) return;
      // YouTube's "Not interested" is left entirely to YouTube: it is a
      // recommendation nudge, not a ClearView block. The explicit
      // "Block channel with ClearView" item is the only thing that blocks.
      // YouTube paints the popup after the click handler returns; injecting on
      // the next frame catches that first paint. The observer covers the rest.
      setTimeout(injectMenuAction, 60);
      setTimeout(injectMenuAction, 300);
    }, true);
  }

  // ── Tap to reveal/hide the app footer ───────────────────────────
  function isInteractiveTarget(el) {
    if (!el || !el.closest) return false;
    return !!el.closest(
      'a, button, input, textarea, select, [role="button"], [role="link"], ' +
      '[role="menuitem"], [role="tab"], [contenteditable="true"], [contenteditable=""]'
    );
  }

  function bindTapToggle() {
    if (tapToggleBound) return;
    tapToggleBound = true;
    var startX = 0;
    var startY = 0;
    var startAt = 0;
    var started = false;

    document.addEventListener('touchstart', function (event) {
      if (!event.touches || event.touches.length !== 1) {
        started = false;
        return;
      }
      started = true;
      startX = event.touches[0].clientX;
      startY = event.touches[0].clientY;
      startAt = Date.now();
    }, true);

    document.addEventListener('touchend', function (event) {
      if (!started) return;
      started = false;
      if (Date.now() - startAt > 450) return;
      var touch = event.changedTouches && event.changedTouches[0];
      var dx = touch ? touch.clientX - startX : 0;
      var dy = touch ? touch.clientY - startY : 0;
      if (Math.sqrt(dx * dx + dy * dy) > 12) return;
      if (isInteractiveTarget(event.target)) return;
      try { bridge.onTap(token); } catch (e) { /* bridge unavailable */ }
    }, true);

    if (!('ontouchstart' in window)) {
      document.addEventListener('click', function (event) {
        if (isInteractiveTarget(event.target)) return;
        try { bridge.onTap(token); } catch (e) { /* bridge unavailable */ }
      }, true);
    }
  }

  // ── Navigation (all three sites are SPAs) ───────────────────────
  /**
   * The page-wide hide passes, run at most a few times a second.
   *
   * Every one of these queries the whole document, and applySurface runs after
   * each mutation batch — on a playing video that is several times a second,
   * for a DOM that has not changed in any way that matters. Throttling them
   * keeps the sheet handling instant (that path is event-driven) while taking
   * the repeating sweep off the scroll path.
   */
  var HIDE_SWEEP_MIN_MS = 600;
  var lastHideSweepAt = 0;

  function runHideSweep() {
    var now = Date.now();
    if (now - lastHideSweepAt < HIDE_SWEEP_MIN_MS) return;
    lastHideSweepAt = now;
    applyShortsCss();
    hideShorts();
    hideAds();
    hideAppPromos();
  }

  function applySurface() {
    if (hostIsYouTube()) {
      runHideSweep();
      bindYouTubeSearch();
      // The card pass is deliberately NOT repeated here: onNavigate schedules
      // one, and the mutation observer schedules another when the page
      // actually renders. Running it from both places tripled the work on
      // every navigation.
      updateWatchAction();
    } else if (hostIsGoogle()) {
      hideGoogleCategories();
      hideGoogleOfferedInTail();
      hideGoogleTrending();
      bindGoogleSearch();
    } else if (hostIsInstagram()) {
      if (!instagramAuthPage()) {
        hideReels();
        hideInstagramSearch();
        if (!policy.hideInstagramSuggestions) clearFeedLock();
        hideInstagramSuggestions();
        syncFeedLock();
        bindReelsGuard();
        bindInstagramActions();
      }
    }
  }

  /** Re-judge the current video when YouTube swaps its player/metadata DOM. */
  function scheduleVideoCheck() {
    if (!hostIsYouTube() || !isVideoPage()) return;
    debounce('video', evaluateVideo, 350);
  }

  function onNavigate() {
    if (!hostIsYouTube() && !hostIsGoogle() && !hostIsInstagram()) return;
    refreshPolicy();
    // Instagram's sign-in/sign-up pages are left exactly as Instagram drew
    // them. Nothing ClearView does there (hiding, covers, search hooks) is
    // wanted, and an overlay over a redirect-in-progress is how a blank white
    // page happens.
    if (hostIsInstagram() && instagramAuthPage()) {
      uncoverAllPosts();
      restoreRemovedInstagramPosts();
      clearVideoCover();
      // A block made on the previous page must not follow the reader onto the
      // sign-in screen: the native content overlay would cover the form and
      // read as a blank/broken page.
      navBlocked = false;
      contentBlocked = false;
      blockedVideoUrl = '';
      lastBlockedKey = '';
      clearContentBlock();
      return;
    }
    // A blocked vertical feed is LEFT, not covered and scrolled into: one
    // black panel over item one still leaves the whole queue behind it. This
    // covers SPA navigation (a playlist row, a related Short, a direct link)
    // as well as a fresh document.
    if (hostIsYouTube() && isShortsPage() && policy.hideShorts) {
      leaveBlockedFeed(YOUTUBE_HOME);
      return;
    }
    if (hostIsInstagram() && instagramReelsPage() && policy.hideReels) {
      leaveBlockedFeed(INSTAGRAM_HOME);
      return;
    }
    // The hidden Explore tab is reachable by typing its URL, which is the one
    // door the nav hiding did not close. The native side refuses it as well;
    // this covers SPA navigations that never reach the WebView client.
    if (hostIsInstagram() && policy.hideInstagramSearch &&
        /^\/explore(\/|$)/.test(location.pathname || '')) {
      leaveBlockedFeed(INSTAGRAM_HOME);
      return;
    }
    // The Home tab is hidden: its tab is gone from the nav, and an SPA
    // navigation that lands on the feed (history, a logo tap) is sent to the
    // reader's own profile instead of opening it.
    if (hostIsInstagram() && policy.hideHome && instagramHomePage()) {
      // Nothing to land on yet (the nav has not drawn): the notice is shown and
      // the scan retries once the profile link exists.
      if (!leaveHomeForProfile()) showPageNotice('Instagram Home is hidden by ClearView');
      return;
    }
    if (hostIsInstagram() && !instagramHomePage()) rememberInstagramHomeScroll();
    // The profile cover is a fixed element on a page the site reuses.
    clearInstagramProfileCover();
    // A feed clamp belongs to the page it was measured on.
    clearFeedLock();
    // Leaving a vertical player (a Shorts feed -> YouTube Home through an SPA
    // navigation) must take the cover with it. A position:fixed element would
    // otherwise stay painted over the feed.
    clearVideoCover();
    navBlocked = false;
    contentBlocked = false;
    blockedVideoUrl = '';
    lastBlockedKey = '';
    lastVideoKey = '';
    lastReelKey = '';
    // A menu on the page we just left cannot describe the next page's card.
    lastMenuCard = null;
    lastMenuVideo = null;
    lastMenuAnchor = null;
    lastMenuAt = 0;
    menuOpenedAt = 0;

    var verdict = {};
    try { verdict = parse(bridge.onNavigated(token, location.href)); } catch (e) { /* ignore */ }

    if (verdict && verdict.blocked) {
      if (verdict.type === 'SHORTS') {
        leaveBlockedFeed(YOUTUBE_HOME);
        return;
      }
      if (verdict.type === 'REELS') {
        leaveBlockedFeed(INSTAGRAM_HOME);
        return;
      }
      navBlocked = true;
      contentBlocked = true;
      pauseAll();
      if (verdict.redirectTo) {
        setTimeout(function () {
          try { window.location.replace(verdict.redirectTo); } catch (e) { /* ignore */ }
        }, 300);
      }
      return;
    }

    ensureYouTubeSizing();
    clearContentBlock();
    bindYouTubeSearch();
    bindGoogleSearch();
    bindReelsGuard();
    applySurface();
    chaseAds();

    if (hostIsYouTube()) {
      setTimeout(evaluateVideo, 350);
      setTimeout(evaluateVideo, 1200);
      setTimeout(scanCards, 500);
      setTimeout(scanCards, 1800);
      setTimeout(injectMenuAction, 500);
      setTimeout(updateWatchAction, 600);
    }
    if (hostIsInstagram()) {
      setTimeout(scanInstagram, 400);
      setTimeout(scanInstagram, 1200);
    }
  }

  function bindNavigation() {
    // Scrolling reveals cards and Instagram posts that were outside the
    // bounded scan. Debounced, passive, and shared with every surface.
    window.addEventListener('scroll', function () {
      if (hostIsYouTube()) debounce('cards', scanCards, 350);
      if (hostIsInstagram()) debounce('insta', scanInstagram, 400);
    }, { passive: true });
    document.addEventListener('yt-navigate-finish', function () {
      debounce('navigate', onNavigate, 120);
    });
    window.addEventListener('popstate', function () {
      debounce('navigate', onNavigate, 120);
    });
    try {
      var push = history.pushState;
      history.pushState = function () {
        var result = push.apply(this, arguments);
        setTimeout(function () { debounce('navigate', onNavigate, 120); }, 0);
        return result;
      };
      var replace = history.replaceState;
      history.replaceState = function () {
        var result = replace.apply(this, arguments);
        setTimeout(function () { debounce('navigate', onNavigate, 120); }, 0);
        return result;
      };
    } catch (e) { /* frozen history object: the interval below covers it */ }
    // A cheap string compare, four times a second: a Shorts/Reels swipe can
    // change the URL without a history call this script can see, and the old
    // 700ms tick left the previous item's cover on screen long enough to look
    // like a stuck overlay.
    setInterval(function () {
      if (location.href !== lastHref) {
        lastHref = location.href;
        debounce('navigate', onNavigate, 120);
      }
    }, 250);
  }

  /**
   * Called by the native side when a Protection Settings switch changes.
   * Everything is reversible: hidden nodes are restored and re-evaluated under
   * the new policy, so turning a filter off does not need a page reload.
   */
  window.__clearviewRefresh = function () {
    restoreHidden();
    // A switch the reader just flipped must take effect now, not on the next
    // throttled sweep.
    lastHideSweepAt = 0;
    restoreRemovedInstagramPosts();
    uncoverAllPosts();
    refreshPolicy();
    var cards = allCards();
    for (var i = 0; i < cards.length; i++) {
      delete cards[i].__cvKey;
      delete cards[i].__cvAllowedKey;
    }
    var posts = document.querySelectorAll('article');
    for (var p = 0; p < posts.length; p++) delete posts[p].__cvInstaKey;
    var tiles = document.querySelectorAll('[data-cv-insta-removed], [data-cv-insta-cover]');
    for (var t = 0; t < tiles.length; t++) delete tiles[t].__cvInstaTileKey;
    lastReelKey = '';
    lastVideoKey = '';
    applyShortsCss();
    // Turning a filter back off must not leave the reader stuck on the home
    // page the filter redirected them to, so only the current item is
    // re-judged. Turning one on leaves the blocked surface immediately.
    if (policy.hideShorts && isShortsPage()) {
      leaveBlockedFeed(YOUTUBE_HOME);
      return;
    }
    if (policy.hideReels && instagramReelsPage()) {
      leaveBlockedFeed(INSTAGRAM_HOME);
      return;
    }
    if (policy.hideShorts || policy.hideReels) {
      // Re-evaluate the current item immediately under the new policy.
      evaluateVideo();
      scanInstagram();
    } else {
      clearVideoCover();
      uncoverAllPosts();
    }
    applySurface();
    hideAppPromos();
    scanCards();
    chaseAds();
    updateWatchAction();
  };

  // ── Init ────────────────────────────────────────────────────────
  function init() {
    ensureYouTubeSizing();
    refreshPolicy();
    applyShortsCss();
    reportBrokenViewport();
    bindNavigation();
    bindYouTubeSearch();
    bindGoogleSearch();
    bindReelsGuard();
    bindMenuActions();
    bindTapToggle();
    onNavigate();
    window.addEventListener('scroll', trackInstagramHomeScroll, { passive: true });
    setInterval(chaseAds, 1000);
    setInterval(function () {
      // Late metadata (title/channel/description) is the usual reason a
      // blocked video was not covered: the script judged it once, before
      // YouTube had filled the fields. Keyed cheaply, so an unchanged video
      // costs a string compare.
      scheduleVideoCheck();
      reportBrokenViewport();
      repairCollapsedScroller();
      // Instagram's feed mutates constantly (timers, progress bars), and every
      // mutation resets the debounced scan, so on that page the scan can be
      // starved for minutes. The home feed is re-read on a fixed beat instead:
      // that is what keeps the remembered posts and the cap in step with what
      // Instagram has drawn.
      if (hostIsInstagram() && instagramHomePage()) scanInstagram();
    }, 2500);
    try {
      var observer = new MutationObserver(function (mutations) {
        // Instagram's home feed is hidden unit by unit, and a unit that is
        // waited for shows itself first: hide the new ones before the frame
        // they would have been painted in.
        if (hostIsInstagram()) {
          for (var m = 0; m < mutations.length; m++) {
            var added = mutations[m].addedNodes;
            if (added && added.length) hideNewInstagramUnits(added);
          }
        }
        debounce('surface', applySurface, 400);
        debounce('cards', scanCards, 600);
        debounce('ads', chaseAds, 300);
        debounce('promos', hideAppPromos, 500);
        debounce('insta', scanInstagram, 500);
        debounce('video', scheduleVideoCheck, 500);
        debounce('watch-action', updateWatchAction, 700);
        // Menu scans are bounded: a short window after a three-dot tap, or a
        // visible sheet that needs its row. The sheet probe is throttled on its
        // own — YouTube mutates its DOM many times a second while a video plays,
        // and probing the whole document on every one of those mutations cost
        // far more than the menu scan it was meant to trigger.
        if (Date.now() - menuOpenedAt < 6000) {
          debounce('menu', injectMenuAction, 250);
        } else if (Date.now() - lastMenuProbeAt > 800) {
          lastMenuProbeAt = Date.now();
          if (visibleMenuContainer()) debounce('menu', injectMenuAction, 250);
        }
        if (instagramMenuInfo && Date.now() - instagramMenuAt < 10000) {
          debounce('insta-menu', injectInstagramMenuAction, 250);
        }
      });
      observer.observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) { /* unsupported: static scan still ran */ }
  }

  // The stylesheet must be in place before YouTube's first paint, not at
  // DOMContentLoaded: apply it (and the viewport check) as soon as the script
  // has a document to work with.
  refreshPolicy();
  applyShortsCss();
  // Installed before YouTube asks for its next feed page, so the responses that
  // carry scrolled-in cards are captured as they arrive.
  installPageDataCapture();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
