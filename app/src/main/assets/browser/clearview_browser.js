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
  // Identity captured when the three-dot button was pressed (see
  // rememberMenuCard). It is the only source used by the injected action.
  var menuTargetInfo = null;
  // Menus only exist for a few seconds after the reader tapped a three-dot
  // button. Tracking that window keeps the menu scan out of the idle path.
  var menuOpenedAt = 0;
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

  function refreshPolicy() {
    try { policy = parse(bridge.policy(token)); } catch (e) { /* keep last */ }
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
    if (!policy.hideShorts || !hostIsYouTube()) return;

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

  function chaseAds() {
    if (!policy.blockAds || !hostIsYouTube()) return;
    var player = document.querySelector('#movie_player');
    if (!player) return;
    var adShowing = false;
    try { adShowing = player.classList.contains('ad-showing'); } catch (e) { /* ignore */ }
    var video = player.querySelector('video');
    if (!adShowing) {
      restoreAdVideo(video);
      return;
    }

    var skip = document.querySelector(
      '.ytp-ad-skip-button, .ytp-skip-ad-button, ' +
      '.ytp-ad-skip-button-modern, button.ytp-ad-skip-button-container, ' +
      '.ytp-ad-skip-button-slot button'
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
      // The extension's approach: an unskippable ad is run out at 16x
      // rather than waited through. Everything is restored when it ends.
      video.playbackRate = 16;
      if (isFinite(video.duration) && video.duration > 0.5 &&
          video.currentTime < video.duration - 0.4) {
        video.currentTime = Math.max(0, video.duration - 0.1);
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
      if (policy.hideShorts) {
        showVideoCover('YouTube Shorts are blocked by ClearView');
      } else {
        clearVideoCover();
      }
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
    'ytm-rich-item-renderer'
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
    return cards;
  }

  function videoIdFromCard(card) {
    var a = card.querySelector(
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
    var explicit = card.querySelector(
      '#video-title, a#video-title, yt-formatted-string#video-title, ' +
      '.media-item-headline, h3 a, h3'
    );
    var text = explicit ? (explicit.textContent || '').trim() : '';
    if (!text && explicit) {
      text = (explicit.getAttribute('aria-label') || '').trim();
    }
    if (!text) {
      var link = card.querySelector('a[aria-label][href*="/watch"]');
      text = link ? (link.getAttribute('aria-label') || '').trim() : '';
    }
    return text && text.length <= 300 ? text : null;
  }

  function cardChannel(card) {
    var link = card.querySelector('a[href*="/@"], a[href*="/channel/UC"]');
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
    if (!info.channelName) {
      var nameEl = card.querySelector(
        '#channel-name, ytd-channel-name, .ytd-channel-name, .ytm-channel-name'
      );
      if (nameEl) {
        var text = (nameEl.textContent || '').trim();
        if (text && text.length <= 60) info.channelName = text;
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
      var el = card.querySelector(THUMBNAIL_SELECTORS[s]);
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

  function coverCard(card, label) {
    if (card.getAttribute('data-cv-covered') === label) return;
    uncoverCard(card);
    card.setAttribute('data-cv-covered', label || 'Blocked by ClearView');

    var target = thumbnailTarget(card);
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
    if (!video) return;
    coverTarget = video;
    paintCover(message, false);
  }

  function clearVideoCover() {
    var cover = document.getElementById(ITEM_COVER_ID);
    if (cover) cover.remove();
    coverTarget = null;
  }

  // Keep a fixed cover glued to the video it belongs to while the reader
  // swipes, scrolls or the player resizes. rAF throttling keeps this off the
  // scroll hot path.
  var coverFrame = 0;
  function scheduleCoverPosition() {
    if (coverFrame) return;
    coverFrame = window.requestAnimationFrame(function () {
      coverFrame = 0;
      positionCover();
    });
  }
  window.addEventListener('scroll', scheduleCoverPosition, { passive: true });
  window.addEventListener('resize', scheduleCoverPosition);

  function scanCards() {
    if (!hostIsYouTube()) return;
    // A Shorts page IS the player: removing the current item would break the
    // vertical queue. Every other YouTube surface (including the suggestions
    // beside a watch page) is a list of ordinary cards and is scanned.
    if (isShortsPage()) return;
    var cards = allCards();
    var checked = 0;
    for (var i = 0; i < cards.length && checked < 60; i++) {
      var card = cards[i];
      var id = videoIdFromCard(card);
      var title = cardTitle(card);
      var channel = cardChannel(card);
      var description = cardThumbnailDescription(card);
      if (!id && !title && !channel.channelId && !channel.channelHandle) continue;
      checked++;

      // YouTube recycles card elements while scrolling, so the cached key
      // includes the title/channel actually shown now. A hidden card that was
      // recycled to a different video is restored and judged again; otherwise
      // it would stay invisible for an allowed video.
      // Only cards at or near the viewport are judged. A home feed can hold
      // hundreds of recycled nodes; walking all of them on every mutation was
      // the main source of scroll jank. The scroll listener catches the rest.
      var rect = card.getBoundingClientRect();
      if (rect.bottom < -260 || rect.top > (window.innerHeight || 800) + 260) continue;

      var key = [id, title, channel.channelId, channel.channelHandle, description].join('|');
      if (card.getAttribute('data-cv-hidden-card') && card.__cvKey !== key) {
        restoreCard(card);
      }
      if (card.getAttribute('data-cv-hidden-card')) continue;
      if (card.__cvKey === key && card.hasAttribute('data-cv-covered')) continue;
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
          coverCard(card, label);
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

  function hideReels() {
    if (!policy.hideReels || !hostIsInstagram()) return;

    var links = document.querySelectorAll(
      'a[href*="/reels/"], a[href*="/reel/"]'
    );
    for (var i = 0; i < links.length; i++) {
      var link = links[i];
      var target = navReelContainer(link) ||
        (link.closest && link.closest('[role="button"], [role="link"]')) ||
        link;
      // Never remove the whole post/article: a reel link can appear in a
      // comment or a profile card. Hiding the control is enough.
      hideNode(target, 'reels');
    }

    // The nav item sometimes has an icon whose aria-label says "Reels" while
    // the anchor itself wraps a different subtree.
    var labels = document.querySelectorAll('svg[aria-label="Reels"], [aria-label="Reels"]');
    for (var j = 0; j < labels.length; j++) {
      var container = navReelContainer(labels[j]) ||
        (labels[j].closest && labels[j].closest('[role="button"], [role="link"]')) ||
        labels[j];
      hideNode(container, 'reels');
    }
  }

  function bindReelsGuard() {
    if (reelsGuardBound) return;
    reelsGuardBound = true;
    document.addEventListener('click', function (event) {
      if (!policy.hideReels || !hostIsInstagram()) return;
      var link = instagramReelLink(event.target);
      if (!link) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      showPageNotice('Reels are hidden by ClearView');
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
        coverPost(post, 'Blocked by ClearView\n' + (verdict.item || 'Blocked content'));
      } else {
        uncoverPost(post);
      }
    }
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

  function scanInstagram() {
    if (!hostIsInstagram()) return;
    if (instagramAuthPage()) {
      uncoverAllPosts();
      clearVideoCover();
      return;
    }
    if (instagramReelsPage()) {
      scanInstagramReels();
      return;
    }
    clearVideoCover();
    scanInstagramFeed();
  }

  function uncoverAllPosts() {
    var posts = document.querySelectorAll('[data-cv-insta-cover]');
    for (var i = 0; i < posts.length; i++) uncoverPost(posts[i]);
  }

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

  /**
   * Capture the identity the just-opened menu belongs to.
   *
   * This is done at click time, not at action time. The sheet is teleported out
   * of the card and YouTube can re-render the card list before the reader picks
   * the injected row; recomputing "the current card" then would block whichever
   * card happens to be first, which is exactly the wrong-channel bug. A card
   * button records that card; a player button records the playing video.
   */
  function rememberMenuCard(event) {
    var target = event.target;
    if (!target || !target.closest) return false;
    var button = target.closest(MENU_BUTTON_SELECTOR);
    if (!button) return false;

    var inPlayer = !!(button.closest && button.closest(
      '#movie_player, #player, .html5-video-player, #player-container-id, ytm-player'
    ));
    var card = (!inPlayer && button.closest)
      ? button.closest(CARD_SELECTORS.join(','))
      : null;

    var info = null;
    if (card) {
      var channel = cardChannel(card);
      info = {
        card: card,
        channelHandle: channel.channelHandle,
        channelId: channel.channelId,
        channelName: channel.channelName,
        title: cardTitle(card),
        videoId: videoIdFromCard(card)
      };
    } else if (inPlayer || isVideoPage()) {
      info = collectVideo();
      info.card = null;
    }

    lastMenuCard = card;
    lastMenuVideo = (!card && isVideoPage()) ? (info || collectVideo()) : null;
    menuTargetInfo = info;
    lastMenuAt = Date.now();
    menuOpenedAt = Date.now();
    return true;
  }

  /**
   * The channel the currently-open menu belongs to. The captured identity wins;
   * the live DOM is only a fallback for a menu opened without a button click.
   */
  function currentMenuInfo() {
    var fresh = Date.now() - lastMenuAt < 60000;
    if (fresh && menuTargetInfo) return menuTargetInfo;
    if (fresh && lastMenuCard && document.contains(lastMenuCard)) {
      var channel = cardChannel(lastMenuCard);
      return {
        card: lastMenuCard,
        channelHandle: channel.channelHandle,
        channelId: channel.channelId,
        channelName: channel.channelName,
        title: cardTitle(lastMenuCard),
        videoId: videoIdFromCard(lastMenuCard)
      };
    }
    if (fresh && lastMenuVideo) return lastMenuVideo;
    if (!isVideoPage()) return null;
    var info = collectVideo();
    info.card = null;
    return info;
  }

  function resetMenuIdentity() {
    lastMenuCard = null;
    lastMenuVideo = null;
    menuTargetInfo = null;
    lastMenuAt = 0;
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
    // No menu can be open if no three-dot button was tapped. This keeps a
    // page-wide menu query off the mutation path entirely.
    if (Date.now() - menuOpenedAt > 8000) return;
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
        if (!info || (!info.channelHandle && !info.channelId)) continue;
        var blocked = false;
        if (info && info.channelHandle) {
          var state = call(bridge.channelState, {
            channelHandle: info.channelHandle,
            channelId: info.channelId
          });
          blocked = !!(state && state.blocked);
        }
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
        clone.addEventListener('click', function (event) {
          event.preventDefault();
          event.stopImmediatePropagation();
          toggleBlockFromMenu();
        }, true);
        item.parentNode.insertBefore(clone, item);
      }
    }
  }

  function toggleBlockFromMenu() {
    var info = currentMenuInfo();
    if (!info || (!info.channelHandle && !info.channelId)) {
      showPageNotice('ClearView could not identify this channel');
      return;
    }
    if (!info.channelHandle) {
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

    if (wasBlocked && !info.channelHandle) {
      showPageNotice('This channel is blocked by a rule you cannot remove here');
      return;
    }

    if (wasBlocked) {
      var removed = call(bridge.unblockChannel, payload);
      if (!removed || !removed.unblocked) {
        showPageNotice('ClearView could not unblock this channel');
        return;
      }
      showPageNotice('Channel unblocked by ClearView');
      resetMenuIdentity();
      rejudgeCards();
      return;
    }

    var response = call(bridge.blockChannel, payload);
    if (!response || !response.blocked) {
      showPageNotice('ClearView could not block this channel');
      return;
    }
    showPageNotice('Channel blocked by ClearView');
    if (info.card) removeCard(info.card);
    resetMenuIdentity();
    clearContentBlock();
    lastVideoKey = '';
    var cards = allCards();
    for (var i = 0; i < cards.length; i++) delete cards[i].__cvKey;
    setTimeout(function () {
      evaluateVideo();
      scanCards();
    }, 150);
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
  function applySurface() {
    if (hostIsYouTube()) {
      hideShorts();
      hideAds();
      hideAppPromos();
      bindYouTubeSearch();
      scanCards();
    } else if (hostIsGoogle()) {
      hideGoogleCategories();
      hideGoogleOfferedInTail();
      hideGoogleTrending();
      bindGoogleSearch();
    } else if (hostIsInstagram()) {
      if (!instagramAuthPage()) {
        hideReels();
        bindReelsGuard();
        scanInstagram();
      }
    }
  }

  function onNavigate() {
    if (!hostIsYouTube() && !hostIsGoogle() && !hostIsInstagram()) return;
    // Instagram's sign-in/sign-up pages are left exactly as Instagram drew
    // them. Nothing ClearView does there (hiding, covers, search hooks) is
    // wanted, and an overlay over a redirect-in-progress is how a blank white
    // page happens.
    if (hostIsInstagram() && instagramAuthPage()) {
      uncoverAllPosts();
      clearVideoCover();
      return;
    }
    // Leaving a vertical player (a Shorts feed -> YouTube Home through an SPA
    // navigation) must take the cover with it. A position:fixed element would
    // otherwise stay painted over the feed.
    if (!(hostIsYouTube() && isShortsPage()) &&
        !(hostIsInstagram() && instagramReelsPage())) {
      clearVideoCover();
    }
    refreshPolicy();
    // A vertical feed paints the cover per item. Clear the old item's cover as
    // soon as the URL changes so a blocked Short cannot leave its black panel
    // over the next, allowed one; evaluateVideo/scanInstagram re-cover if the
    // new item is blocked too.
    if (hostIsYouTube() && isShortsPage()) {
      if (policy.hideShorts) {
        showVideoCover('YouTube Shorts are blocked by ClearView');
      } else {
        clearVideoCover();
      }
    }
    if (hostIsInstagram() && instagramReelsPage() && policy.hideReels) {
      showVideoCover('Instagram Reels are hidden by ClearView');
    }
    navBlocked = false;
    contentBlocked = false;
    blockedVideoUrl = '';
    lastBlockedKey = '';
    lastVideoKey = '';
    lastReelKey = '';
    // A menu on the page we just left cannot describe the next page's card.
    lastMenuCard = null;
    lastMenuVideo = null;
    lastMenuAt = 0;

    var verdict = {};
    try { verdict = parse(bridge.onNavigated(token, location.href)); } catch (e) { /* ignore */ }

    if (verdict && verdict.blocked) {
      if (verdict.type === 'SHORTS' || verdict.type === 'REELS') {
        // A vertical feed: cover the item instead of leaving it. The reader
        // can keep swiping up/down; each new item is judged on its own.
        contentBlocked = true;
        pauseAll();
        if (verdict.type === 'SHORTS') {
          showVideoCover('YouTube Shorts are blocked by ClearView');
        } else {
          showVideoCover('Instagram Reels are hidden by ClearView');
        }
        applySurface();
        if (hostIsYouTube()) setTimeout(evaluateVideo, 400);
        if (hostIsInstagram() && instagramReelsPage()) setTimeout(scanInstagram, 400);
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
    setInterval(function () {
      if (location.href !== lastHref) {
        lastHref = location.href;
        debounce('navigate', onNavigate, 120);
      }
    }, 700);
  }

  /**
   * Called by the native side when a Protection Settings switch changes.
   * Everything is reversible: hidden nodes are restored and re-evaluated under
   * the new policy, so turning a filter off does not need a page reload.
   */
  window.__clearviewRefresh = function () {
    restoreHidden();
    refreshPolicy();
    var cards = allCards();
    for (var i = 0; i < cards.length; i++) {
      delete cards[i].__cvKey;
      delete cards[i].__cvAllowedKey;
    }
    var posts = document.querySelectorAll('article');
    for (var p = 0; p < posts.length; p++) delete posts[p].__cvInstaKey;
    lastReelKey = '';
    lastVideoKey = '';
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
  };

  // ── Init ────────────────────────────────────────────────────────
  function init() {
    ensureYouTubeSizing();
    refreshPolicy();
    bindNavigation();
    bindYouTubeSearch();
    bindGoogleSearch();
    bindReelsGuard();
    bindMenuActions();
    bindTapToggle();
    onNavigate();
    setInterval(chaseAds, 1000);
    try {
      var observer = new MutationObserver(function () {
        debounce('surface', applySurface, 400);
        debounce('cards', scanCards, 600);
        debounce('ads', chaseAds, 300);
        debounce('promos', hideAppPromos, 500);
        debounce('insta', scanInstagram, 500);
        // The menu scan only runs inside the short window after a three-dot
        // click; a page-wide menu lookup on every mutation was pure overhead.
        if (Date.now() - menuOpenedAt < 4000) {
          debounce('menu', injectMenuAction, 250);
        }
      });
      observer.observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) { /* unsupported: static scan still ran */ }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
