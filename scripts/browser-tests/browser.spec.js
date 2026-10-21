import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const script = fs.readFileSync(process.env.CLEARVIEW_SCRIPT || path.join(root, 'app/src/main/assets/browser/clearview_browser.js'), 'utf8');
const early = fs.readFileSync(path.join(root, 'app/src/main/assets/browser/clearview_early.js'), 'utf8');
const css = `<style>
body { margin:0; font:16px Arial; } button { min-height:40px; }
ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-compact-video-renderer,
ytm-playlist-video-renderer, ytm-reel-shelf-renderer, ytm-slim-owner-renderer,
ytm-menu-popup-renderer, ytm-menu-service-item-renderer {display:block;}
.card {min-height:220px;margin-bottom:10px;} .media-item-thumbnail-container {display:block;width:240px;height:130px;position:relative;background:#aaa;}
</style>`;
function card(id, handle = '@allowed', extra = '') {
  return `<ytm-video-with-context-renderer class="card" id="${id}">
    <a class="media-item-thumbnail-container" href="/watch?v=${id}video"><img alt="Video" /></a>
    <h3 class="media-item-headline">Video ${id}</h3><a href="/${handle}">${handle}</a>
    <ytm-menu-renderer><button aria-label="Menu">⋮</button></ytm-menu-renderer>${extra}
  </ytm-video-with-context-renderer>`;
}
async function boot(page, { html = '', url = 'https://m.youtube.com/', hideShorts = false, removeBlocked = false, blocked = [] } = {}) {
  await page.route('**/*', route => route.fulfill({ status: 200, contentType: 'text/html', body: css + html }));
  await page.addInitScript(({ hideShorts, removeBlocked, blocked, early, script }) => {
    window.__policy = { hideShorts, removeBlocked, blockAds: true };
    window.__clearviewEarlyPolicy = window.__policy;
    window.__calls = [];
    const blocks = new Set(JSON.parse(localStorage.getItem('blocks') || JSON.stringify(blocked)));
    window.ClearViewBridge = {
      policy: () => JSON.stringify(window.__policy),
      evaluateCard: (_, payload) => {
        const data = JSON.parse(payload); window.__calls.push(data);
        return JSON.stringify({ blocked: blocks.has(data.channelHandle) || blocks.has(data.channelId), item: data.channelHandle });
      },
      evaluateVideo: (_, payload) => {
        const data = JSON.parse(payload);
        return JSON.stringify({ blocked: blocks.has(data.channelHandle), item: data.channelHandle });
      },
      evaluateInstagram: () => '{"blocked":false}',
      evaluateSearch: () => '{"blocked":false}',
      onNavigated: () => '{"blocked":false}',
      onBlocked: () => { window.__nativeBlocked = true; },
      onContentAllowed: () => { window.__nativeBlocked = false; },
      onTap: () => {},
      channelState: (_, p) => JSON.stringify({ blocked: blocks.has(JSON.parse(p).channelHandle) }),
      blockChannel: (_, p) => {
        const data = JSON.parse(p); blocks.add(data.channelHandle);
        localStorage.setItem('blocks', JSON.stringify([...blocks]));
        window.__blockCalls = (window.__blockCalls || 0) + 1;
        return '{"blocked":true}';
      },
      unblockChannel: (_, p) => { blocks.delete(JSON.parse(p).channelHandle); return '{"unblocked":true}'; }
    };
    (0, eval)(early); (0, eval)(script);
  }, { hideShorts, removeBlocked, blocked, early, script });
  await page.goto(url);
}
async function openMenu(page, id) {
  await page.locator(`#${id} button`).click();
  await page.evaluate(() => {
    let sheet = document.querySelector('ytm-menu-popup-renderer');
    if (!sheet) { sheet = document.createElement('ytm-menu-popup-renderer'); document.body.append(sheet); }
    sheet.innerHTML = '<ytm-menu-service-item-renderer><button>Save to Watch Later</button></ytm-menu-service-item-renderer>';
  });
  await expect(page.locator('[data-cv-menu-item]')).toBeVisible();
}

test('Home menu blocks on the first click and invalidates every allowed card', async ({ page }) => {
  await boot(page, { html: card('first', '@same') + card('second', '@same') });
  await expect.poll(() => page.evaluate(() => window.__calls.length)).toBeGreaterThanOrEqual(2);
  await openMenu(page, 'first');
  await page.locator('[data-cv-menu-item]').click();
  await expect(page.locator('#first')).toBeHidden();
  await expect(page.locator('#second .clearview-thumb-overlay')).toHaveCount(1);
  expect(await page.evaluate(() => window.__blockCalls)).toBe(1);
  await page.reload();
  await expect(page.locator('#second .clearview-thumb-overlay')).toBeVisible();
});

test('watch page exposes an owner-scoped block action without a YouTube menu', async ({ page }) => {
  await boot(page, { url: 'https://m.youtube.com/watch?v=longvideo01',
    html: '<ytm-slim-owner-renderer><a href="/@owner">Owner</a></ytm-slim-owner-renderer>' + card('recommend', '@other') });
  await page.locator('#clearview-watch-action').click();
  await expect.poll(() => page.evaluate(() => window.__nativeBlocked)).toBe(true);
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('blocks')))).toEqual(['@owner']);
});

test('channel page menu uses its own URL and works on the first click', async ({ page }) => {
  await boot(page, { url: 'https://m.youtube.com/@actual/videos',
    html: '<header id="header"><button aria-label="More actions">⋮</button></header>' + card('unrelated', '@other') });
  await openMenu(page, 'header');
  await page.locator('[data-cv-menu-item]').click();
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('blocks')))).toEqual(['@actual']);
});

test('reused menu never retains the previous card identity', async ({ page }) => {
  await boot(page, { html: card('one', '@one') + card('two', '@two') });
  await openMenu(page, 'one');
  await openMenu(page, 'two');
  await page.locator('[data-cv-menu-item]').click();
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('blocks')))).toEqual(['@two']);
});

test('cards beyond the first sixty are judged when scrolled into view', async ({ page }) => {
  await boot(page, { html: Array.from({ length: 75 }, (_, i) => card('row' + i, '@blocked')).join(''), blocked: ['@blocked'] });
  await page.locator('#row70').scrollIntoViewIfNeeded();
  await expect(page.locator('#row70 .clearview-thumb-overlay')).toBeVisible();
});

test('thumbnail replacement is re-covered once and recycled allowed cards restore', async ({ page }) => {
  await boot(page, { html: '<ytm-rich-item-renderer>' + card('recycle', '@blocked') + '</ytm-rich-item-renderer>', blocked: ['@blocked'] });
  await expect(page.locator('.clearview-thumb-overlay')).toHaveCount(1);
  await page.evaluate(() => {
    const old = document.querySelector('.media-item-thumbnail-container');
    old.replaceWith(old.cloneNode(false));
  });
  await expect(page.locator('.clearview-thumb-overlay')).toHaveCount(1);
  await page.evaluate(() => { const a = document.querySelector('a[href="/@blocked"]'); a.href = '/@allowed'; a.textContent = '@allowed'; });
  await expect(page.locator('.clearview-thumb-overlay')).toHaveCount(0);
});

test('cover stays inside thumbnail geometry and menu stays usable', async ({ page }) => {
  await boot(page, { html: card('geometry', '@blocked'), blocked: ['@blocked'] });
  const overlay = page.locator('.clearview-thumb-overlay');
  await expect(overlay).toBeVisible();
  const cover = await overlay.boundingBox();
  const thumb = await page.locator('.media-item-thumbnail-container').boundingBox();
  expect(cover).toEqual(thumb);
  await openMenu(page, 'geometry');
});

test('Shorts shelves are hidden by CSS immediately and policy changes restore them', async ({ page }) => {
  await boot(page, { hideShorts: true, html: '<ytm-reel-shelf-renderer id="short">Shorts</ytm-reel-shelf-renderer>' });
  expect(await page.locator('#short').evaluate(el => getComputedStyle(el).display)).toBe('none');
  await page.evaluate(() => {
    const shelf = document.createElement('ytm-reel-shelf-renderer'); shelf.id = 'dynamic-short'; shelf.textContent = 'New Shorts';
    document.body.appendChild(shelf);
    window.__firstDisplay = getComputedStyle(shelf).display;
  });
  expect(await page.evaluate(() => window.__firstDisplay)).toBe('none');
  await page.evaluate(() => { window.__policy.hideShorts = false; window.__clearviewRefresh(); });
  await expect(page.locator('#short')).toBeVisible();
  await expect(page.locator('#dynamic-short')).toBeVisible();
});

test('playlist Shorts with watch URLs and SHORTS metadata are hidden', async ({ page }) => {
  await boot(page, { hideShorts: true, html: '<ytm-playlist-video-renderer id="playlist-row"><a href="/watch?v=shortvideo01">Video</a></ytm-playlist-video-renderer>' });
  await page.evaluate(() => {
    document.querySelector('#playlist-row').data = { thumbnailOverlays: [{ thumbnailOverlayTimeStatusRenderer: { style: 'SHORTS' } }] };
    document.querySelector('#playlist-row a').textContent = 'Updated';
  });
  await expect(page.locator('#playlist-row')).toBeHidden();
  await page.evaluate(() => history.pushState({}, '', '/watch?v=shortvideo01&list=PLexample'));
  await expect(page).toHaveURL('https://m.youtube.com/');
  await expect(page.locator('#clearview-item-cover')).toHaveCount(0);
});

test('direct and SPA Shorts navigation returns home without a viewport overlay', async ({ page }) => {
  await boot(page, { hideShorts: true });
  await page.evaluate(() => history.pushState({}, '', '/shorts/shortvideo01'));
  await expect(page).toHaveURL('https://m.youtube.com/');
  await page.goto('https://m.youtube.com/shorts/shortvideo02');
  await expect(page).toHaveURL('https://m.youtube.com/');
  await expect(page.locator('#clearview-item-cover')).toHaveCount(0);
});

test('Instagram auth clears stale blocking and form remains visible and usable', async ({ page }) => {
  await boot(page, { url: 'https://www.instagram.com/reels/example/',
    html: '<form><input aria-label="Username"><input aria-label="Password" type="password"><button type="button" id="login">Log in</button></form>' });
  await page.evaluate(() => { window.__nativeBlocked = true; history.pushState({}, '', '/accounts/login/'); });
  await expect.poll(() => page.evaluate(() => window.__nativeBlocked)).toBe(false);
  await page.getByLabel('Username').fill('example');
  await page.getByLabel('Password').fill('test-password');
  await expect(page.locator('#login')).toBeVisible();
  await page.locator('#login').click();
  await expect(page.locator('#clearview-item-cover, .clearview-post-cover')).toHaveCount(0);
});

async function installAd(page, skip = false) {
  await page.evaluate(skip => {
    const player = document.createElement('div'); player.id = 'movie_player'; player.className = 'ad-showing';
    const video = document.createElement('video'); let current = 0;
    window.__media = { seeks: 0, plays: 0, source: 'ad.mp4', paused: false, ended: false };
    Object.defineProperties(video, {
      currentSrc: { get: () => window.__media.source }, duration: { get: () => 30 }, readyState: { get: () => 4 },
      currentTime: { get: () => current, set: v => { current = v; window.__media.seeks++; } },
      paused: { get: () => window.__media.paused }, ended: { get: () => window.__media.ended }
    });
    video.playbackRate = 1.5; video.muted = false;
    video.play = () => { window.__media.plays++; return Promise.resolve(); };
    player.append(video);
    if (skip) {
      const button = document.createElement('button'); button.className = 'ytp-ad-skip-button'; button.textContent = 'Skip';
      button.onclick = () => { window.__media.source = 'content.mp4'; player.className = ''; };
      player.append(button);
    }
    document.body.append(player);
  }, skip);
}

test('ad skip that switches source immediately never seeks or accelerates content', async ({ page }) => {
  await boot(page, { url: 'https://m.youtube.com/watch?v=longvideo01' });
  await installAd(page, true);
  await expect.poll(() => page.evaluate(() => window.__media.source)).toBe('content.mp4');
  expect(await page.evaluate(() => window.__media.seeks)).toBe(0);
  expect(await page.locator('video').evaluate(v => v.playbackRate)).toBe(1.5);
});

test('ad seeks once to ended, restores mute/rate, and resumes only the interrupted transition', async ({ page }) => {
  await boot(page, { url: 'https://m.youtube.com/watch?v=longvideo01' });
  await installAd(page);
  await expect.poll(() => page.evaluate(() => window.__media.seeks)).toBe(1);
  await page.evaluate(() => {
    const video = document.querySelector('video');
    window.__media.paused = true; window.__media.ended = true;
    video.dispatchEvent(new Event('ended'));
    document.querySelector('#movie_player').className = '';
  });
  await expect.poll(() => page.evaluate(() => window.__media.plays)).toBe(1);
  expect(await page.locator('video').evaluate(v => [v.muted, v.playbackRate])).toEqual([false, 1.5]);
  expect(await page.evaluate(() => window.__media.seeks)).toBe(1);
});

test('a paused ad or user pause never triggers automatic resume', async ({ page }) => {
  await boot(page, { url: 'https://m.youtube.com/watch?v=longvideo01' });
  await installAd(page);
  await expect.poll(() => page.evaluate(() => window.__media.seeks)).toBe(1);
  await page.evaluate(() => {
    window.__media.paused = true;
    document.querySelector('video').dispatchEvent(new Event('pause'));
    document.querySelector('video').dispatchEvent(new Event('ended'));
    document.querySelector('#movie_player').className = '';
  });
  await expect.poll(() => page.locator('video').evaluate(v => v.muted)).toBe(false);
  expect(await page.evaluate(() => window.__media.plays)).toBe(0);
});

test('source replacement under a stale ad class stays untouched on subsequent scans', async ({ page }) => {
  await boot(page, { url: 'https://m.youtube.com/watch?v=longvideo01' });
  await installAd(page);
  await expect.poll(() => page.evaluate(() => window.__media.seeks)).toBe(1);
  await page.evaluate(() => {
    window.__media.source = 'content.mp4';
    document.querySelector('video').setAttribute('aria-label', 'New source');
  });
  await expect.poll(() => page.locator('video').evaluate(v => v.muted)).toBe(false);
  // A new mutation and another timer tick both used to re-enter the ad path.
  await page.evaluate(() => document.querySelector('video').setAttribute('aria-label', 'Playing'));
  await page.waitForTimeout(1100);
  expect(await page.evaluate(() => window.__media.seeks)).toBe(1);
  expect(await page.locator('video').evaluate(v => v.playbackRate)).toBe(1.5);
});

test('disabling ad blocking restores saved mute without resuming a paused video', async ({ page }) => {
  await boot(page, { url: 'https://m.youtube.com/watch?v=longvideo01' });
  await installAd(page);
  await expect.poll(() => page.locator('video').evaluate(v => v.muted)).toBe(true);
  await page.evaluate(() => { window.__media.paused = true; window.__policy.blockAds = false; window.__clearviewRefresh(); });
  expect(await page.locator('video').evaluate(v => v.muted)).toBe(false);
  expect(await page.evaluate(() => window.__media.plays)).toBe(0);
});

test('an id-only menu resolves its handle once and keeps the original card identity', async ({ page }) => {
  await boot(page, { html: card('idonly', 'channel/UCabcdefghijklmnopqrstuv') });
  let lookups = 0;
  await page.route('**/channel/UCabcdefghijklmnopqrstuv', route => {
    lookups++;
    return route.fulfill({ contentType: 'text/html', body: '<script type="application/json">{"channelMetadataRenderer":{"externalId":"UCabcdefghijklmnopqrstuv","vanityChannelUrl":"https://www.youtube.com/@resolved"}}</script>' });
  });
  await openMenu(page, 'idonly');
  await page.locator('[data-cv-menu-item]').click();
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('blocks') || '[]'))).toEqual(['@resolved']);
  expect(lookups).toBe(1);
});

test('blocked-card removal mode applies immediately to other cached cards too', async ({ page }) => {
  await boot(page, { html: card('removeone', '@same') + card('removetwo', '@same'), removeBlocked: true });
  await openMenu(page, 'removeone');
  await page.locator('[data-cv-menu-item]').click();
  await expect(page.locator('#removeone')).toBeHidden();
  await expect(page.locator('#removetwo')).toBeHidden();
});

test('CSS hides a playlist containing Shorts before the observer runs', async ({ page }) => {
  await boot(page, { hideShorts: true });
  const display = await page.evaluate(() => {
    const list = document.createElement('ytm-playlist-renderer');
    list.innerHTML = '<a href="/shorts/shortvideo01">Playlist preview</a>';
    document.body.appendChild(list);
    return getComputedStyle(list).display;
  });
  expect(display).toBe('none');
});

test('allowed-card decisions are refreshed on SPA navigation after rules change', async ({ page }) => {
  await boot(page, { html: card('cached', '@later') });
  await expect.poll(() => page.evaluate(() => window.__calls.length)).toBeGreaterThan(0);
  await page.evaluate(() => {
    window.ClearViewBridge.blockChannel('', JSON.stringify({ channelHandle: '@later' }));
    history.pushState({}, '', '/results?search_query=example');
  });
  await expect(page.locator('#cached .clearview-thumb-overlay')).toBeVisible();
});

test('initial filtering works even when the provider injects after DOMContentLoaded', async ({ page }) => {
  await page.route('**/*', route => route.fulfill({ contentType: 'text/html', body: css + card('late', '@blocked') }));
  await page.goto('https://m.youtube.com/');
  await page.evaluate(script => {
    window.ClearViewBridge = {
      policy: () => '{"hideShorts":false,"removeBlocked":false}',
      onNavigated: () => '{}', onContentAllowed: () => {},
      evaluateCard: () => '{"blocked":true,"item":"@blocked"}'
    };
    (0, eval)(script);
  }, script);
  await expect(page.locator('.clearview-thumb-overlay')).toBeVisible();
});
