// QuietNet: runs inside m.youtube.com before the page's own scripts.
// 1. Removes ads from YouTube's data before the player and feed read it.
// 2. Skips any ad that still starts, and hides ad boxes.
// 3. Keeps playback going when the screen is off or the app is in the background.
// 4. Reports playback to the app for the lock-screen and notification controls.
(() => {
  if (window.__qnLoaded) return;
  window.__qnLoaded = true;

  // ---------- 1. Data cleaning ----------

  const AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams', 'adBreakParams'];
  const AD_RENDERERS = new Set([
    'adSlotRenderer', 'promotedSparklesWebRenderer', 'promotedSparklesTextSearchRenderer',
    'promotedVideoRenderer', 'compactPromotedVideoRenderer', 'displayAdRenderer',
    'inFeedAdLayoutRenderer', 'bannerPromoRenderer', 'statementBannerRenderer',
    'brandVideoShelfRenderer', 'brandVideoSingletonRenderer', 'actionCompanionAdRenderer',
    'companionAdRenderer', 'adActionInterstitialRenderer', 'searchPyvRenderer',
    'primetimePromoRenderer', 'mealbarPromoRenderer', 'reelsAdRenderer', 'adBreakServiceRenderer',
  ]);

  const isAd = (item) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return false;
    for (const k in item) if (AD_RENDERERS.has(k)) return true;
    const content = item.richItemRenderer && item.richItemRenderer.content;
    if (content && isAd(content)) return true;
    const reel = item.command && item.command.reelWatchEndpoint;
    return !!(reel && reel.adClientParams);
  };

  const clean = (node, depth) => {
    if (!node || typeof node !== 'object' || depth > 48) return;
    if (Array.isArray(node)) {
      for (let i = node.length - 1; i >= 0; i--) {
        if (isAd(node[i])) node.splice(i, 1);
        else clean(node[i], depth + 1);
      }
      return;
    }
    for (const k of AD_KEYS) if (k in node) delete node[k];
    for (const k in node) {
      const v = node[k];
      if (v && typeof v === 'object') clean(v, depth + 1);
    }
  };

  // YouTube responses carry a responseContext; skip every other JSON the page parses.
  const looksLikeYouTube = (o) => o && typeof o === 'object' &&
    (o.responseContext || o.playerResponse || o.adPlacements ||
      (Array.isArray(o) && o.some((x) => x && (x.responseContext || x.playerResponse))));

  const safeClean = (o) => {
    try { if (looksLikeYouTube(o)) clean(o, 0); } catch (e) { /* never break the page */ }
    return o;
  };

  const parse = JSON.parse;
  JSON.parse = function () { return safeClean(parse.apply(this, arguments)); };

  const json = Response.prototype.json;
  Response.prototype.json = function () { return json.apply(this, arguments).then(safeClean); };

  // Data embedded in the first page load.
  for (const name of ['ytInitialPlayerResponse', 'ytInitialData', 'ytInitialReelWatchSequenceResponse']) {
    let value;
    try {
      Object.defineProperty(window, name, {
        configurable: true,
        get: () => value,
        set: (v) => { value = safeClean(v); },
      });
    } catch (e) { /* already defined */ }
  }

  // ---------- 2. Hiding and skipping ----------

  const css = `
    ytm-promoted-sparkles-web-renderer, ytm-promoted-sparkles-text-search-renderer,
    ytm-promoted-video-renderer, ytm-companion-ad-renderer, ytm-companion-slot,
    ad-slot-renderer, ytm-ad-slot-renderer, ytm-banner-promo-renderer, ytm-mealbar-promo-renderer,
    ytm-statement-banner-renderer, ytm-brand-video-shelf-renderer, ytm-merch-shelf-renderer,
    ytm-rich-item-renderer:has(ad-slot-renderer), ytm-item-section-renderer:has(ad-slot-renderer),
    ytm-reel-item-renderer:has(ad-slot-renderer), ytm-paid-content-overlay-renderer,
    .ytp-ad-module, .ytp-ad-overlay-container, .ytp-ad-player-overlay, .video-ads, #player-ads,
    ytd-enforcement-message-view-model, ytm-enforcement-message-view-model,
    tp-yt-paper-dialog:has(ytd-enforcement-message-view-model) { display: none !important; }
  `;
  const addStyle = () => {
    const root = document.head || document.documentElement;
    if (!root) return false;
    const s = document.createElement('style');
    s.textContent = css;
    root.appendChild(s);
    return true;
  };
  if (!addStyle()) document.addEventListener('DOMContentLoaded', addStyle, { once: true });

  let inAd = false;
  let savedMuted = false;
  setInterval(() => {
    const v = document.querySelector('video');
    const player = document.querySelector('.html5-video-player');
    const adShowing = !!(player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')));
    if (v && adShowing) {
      if (!inAd) { inAd = true; savedMuted = v.muted; }
      v.muted = true;
      if (isFinite(v.duration) && v.duration > 0) v.currentTime = Math.max(v.duration - 0.1, 0);
      document.querySelectorAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, button[class*="skip-ad"], button[class*="ad-skip"]')
        .forEach((b) => b.click());
    } else if (inAd && v) {
      inAd = false;
      v.muted = savedMuted;
      v.playbackRate = 1;
    }
    // "Video paused. Continue watching?" would stop background listening.
    document.querySelectorAll('ytm-dialog, tp-yt-paper-dialog, dialog, [role="dialog"]').forEach((d) => {
      if (/continue watching/i.test(d.textContent || '')) {
        const yes = [...d.querySelectorAll('button')].find((b) => /yes|continue/i.test(b.textContent || ''));
        if (yes) yes.click();
        if (v && v.paused) v.play().catch(() => {});
      }
    });
  }, 300);

  // ---------- 3. Background playback ----------

  try {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
    Object.defineProperty(document, 'webkitHidden', { configurable: true, get: () => false });
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
    Object.defineProperty(document, 'webkitVisibilityState', { configurable: true, get: () => 'visible' });
  } catch (e) { /* ignore */ }
  // Only page-level events; a text box losing focus must still work.
  const swallow = (e) => { if (e.target === window || e.target === document) e.stopImmediatePropagation(); };
  for (const type of ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze']) {
    window.addEventListener(type, swallow, true);
    document.addEventListener(type, swallow, true);
  }
  window.addEventListener('blur', swallow, true);

  // ---------- 4. Talking to the app ----------

  const videoId = () => {
    const u = new URL(location.href);
    const shorts = u.pathname.match(/^\/shorts\/([\w-]+)/);
    return u.searchParams.get('v') || (shorts && shorts[1]) || '';
  };
  const report = () => {
    const bridge = window.QuietNetBridge;
    const v = document.querySelector('video');
    if (!bridge || !v || inAd) return;
    const title = (document.title || '').replace(/\s*-\s*YouTube\s*$/, '');
    bridge.state(!v.paused && !v.ended, title, videoId(),
      isFinite(v.currentTime) ? v.currentTime : 0, isFinite(v.duration) ? v.duration : 0);
  };
  for (const type of ['play', 'playing', 'pause', 'ended', 'seeked', 'durationchange']) {
    document.addEventListener(type, (e) => { if (e.target && e.target.tagName === 'VIDEO') report(); }, true);
  }

  window.__qnControl = (cmd) => {
    const v = document.querySelector('video');
    if (!v) return;
    if (cmd === 'play') v.play().catch(() => {});
    else if (cmd === 'pause') v.pause();
    else if (cmd === 'forward') v.currentTime = Math.min(v.currentTime + 10, v.duration || v.currentTime + 10);
    else if (cmd === 'back') v.currentTime = Math.max(v.currentTime - 10, 0);
    else if (cmd.startsWith('seek:')) v.currentTime = parseFloat(cmd.slice(5)) || 0;
    report();
  };
})();
