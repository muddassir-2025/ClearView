/* Installed at document start. No synchronous native bridge calls here: the
 * WebView UI thread waits for this script. The regular script refreshes policy. */
(function () {
  'use strict';
  if (window.top !== window || !/(^|\.)youtube\.com$/i.test(location.hostname)) return;
  if (window.__clearviewApplyEarlyPolicy) {
    window.__clearviewApplyEarlyPolicy(window.__clearviewEarlyPolicy || {});
    return;
  }
  var shorts = new Set();
  window.__clearviewShortIds = shorts;
  window.__clearviewApplyEarlyPolicy = function (policy) {
    window.__clearviewEarlyPolicy = policy;
    var root = document.documentElement;
    if (!root) return;
    root.toggleAttribute('data-cv-hide-shorts', !!policy.hideShorts);
    if (document.getElementById('clearview-early-style')) return;
    var style = document.createElement('style');
    style.id = 'clearview-early-style';
    var prefix = 'html[data-cv-hide-shorts] ';
    var selectors = [
      'ytd-reel-shelf-renderer', 'ytm-reel-shelf-renderer',
      'ytd-rich-shelf-renderer[is-shorts]', 'ytm-shorts-lockup-view-model',
      'ytm-shorts-lockup-view-model-v2', 'yt-shorts-lockup-view-model',
      'ytd-reel-item-renderer', 'ytm-reel-item-renderer',
      '[data-cv-short]', 'a[href="/shorts"]', 'a[href^="/shorts/"]', 'a[href^="/shorts?"]'
    ];
    // :has hides the card/shelf itself before paint, including playlist rows.
    var cards = ['ytd-rich-item-renderer', 'ytd-video-renderer', 'yt-lockup-view-model',
      'ytd-playlist-renderer', 'ytm-playlist-renderer', 'ytd-playlist-video-renderer',
      'ytm-playlist-video-renderer', 'ytm-rich-item-renderer',
      'ytm-video-with-context-renderer', 'ytm-compact-video-renderer',
      'ytm-pivot-bar-item-renderer'];
    var basic = selectors.map(function (s) { return prefix + s; }).join(',');
    var relational = cards.map(function (s) {
      return prefix + s + ':has(a[href*="/shorts/"])';
    }).join(',');
    style.textContent = basic + '{display:none!important}' +
      '@supports selector(:has(a)){' + relational + '{display:none!important}}';
    root.appendChild(style);
  };
  function apply() {
    window.__clearviewApplyEarlyPolicy(window.__clearviewEarlyPolicy || {});
  }
  if (document.documentElement) apply();
  else {
    var observer = new MutationObserver(function () {
      if (document.documentElement) { observer.disconnect(); apply(); }
    });
    observer.observe(document, { childList: true });
  }
  // Block playback before the slower metadata/card pass, including playlist
  // autoplay of an ID already identified as a Short in this document.
  document.addEventListener('play', function (event) {
    var policy = window.__clearviewEarlyPolicy || {};
    var id = new URLSearchParams(location.search).get('v');
    if (policy.hideShorts && (/^\/shorts(?:\/|$)/.test(location.pathname) || shorts.has(id)) &&
        event.target && event.target.tagName === 'VIDEO') {
      event.target.pause();
    }
  }, true);
})();
