// Runs in each frame. Finds the next-step button, highlights it, scrolls, and notices videos ending.
// It clicks only when told to after a key press, and skips anything disabled.
// During a scroll and click run it also reports whether the page has reached the bottom.
(() => {
  if (window.__autoClickerHelper) return;
  window.__autoClickerHelper = true;

  const ACTIVE_TICK_MILLIS = 700;
  const IDLE_TICK_MILLIS = 2000;
  const MAX_LABEL_LENGTH = 40;
  const BOTTOM_TOLERANCE_PIXELS = 2;
  const MAX_HASHED_CHARACTERS = 200000;
  // Older course pages often use links without an href, or plain elements with an onclick handler.
  const CLICKABLE = 'button, a, summary, [onclick], [role="button"], [role="link"], input[type="button"], input[type="submit"]';

  let settings = { enabled: false };
  let chosen = null;
  let savedOutline = null;
  let scroller = null;
  let lastFrameTime = null;
  let carry = 0;
  const watchedVideos = new WeakSet();

  function allRoots() {
    const roots = [document];
    for (let i = 0; i < roots.length; i++) {
      for (const element of roots[i].querySelectorAll("*")) {
        if (element.shadowRoot) roots.push(element.shadowRoot);
      }
    }
    return roots;
  }

  function isUsable(element) {
    if (element.disabled || element.getAttribute("aria-disabled") === "true") return false;
    const box = element.getBoundingClientRect();
    if (box.width <= 0 || box.height <= 0) return false;
    const style = getComputedStyle(element);
    return style.visibility !== "hidden" && style.display !== "none" && style.pointerEvents !== "none";
  }

  function labelOf(element) {
    const label = element.getAttribute("aria-label") || element.innerText || element.value || element.title || "";
    return label.replace(/\s+/g, " ").trim();
  }

  function wordRank(label, words) {
    const text = label.toLowerCase();
    for (let i = 0; i < words.length; i++) {
      const word = words[i].toLowerCase();
      if (text === word) return i * 2;
      const escaped = word.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      if (new RegExp(`(^|\\W)${escaped}($|\\W)`).test(text)) return i * 2 + 1;
    }
    return null;
  }

  function findButton(words, roots) {
    let best = null;
    for (const root of roots) {
      for (const element of root.querySelectorAll(CLICKABLE)) {
        const label = labelOf(element);
        if (!label || label.length > MAX_LABEL_LENGTH || !isUsable(element)) continue;
        const rank = wordRank(label, words);
        if (rank !== null && (!best || rank < best.rank)) best = { element, label, rank };
      }
    }
    return best;
  }

  function findScroller() {
    let best = null;
    let bestArea = 0;
    const page = document.scrollingElement;
    if (page && page.scrollHeight > page.clientHeight + 1) {
      best = page;
      bestArea = page.clientWidth * page.clientHeight;
    }
    for (const element of document.querySelectorAll("*")) {
      if (element.scrollHeight <= element.clientHeight + 1 || element.clientHeight < 100) continue;
      const overflow = getComputedStyle(element).overflowY;
      if (overflow !== "auto" && overflow !== "scroll") continue;
      const area = element.clientWidth * element.clientHeight;
      if (area > bestArea) {
        best = element;
        bestArea = area;
      }
    }
    return { element: best, area: bestArea };
  }

  function hash(text) {
    let value = 0;
    const length = Math.min(text.length, MAX_HASHED_CHARACTERS);
    for (let i = 0; i < length; i++) value = (value * 31 + text.charCodeAt(i)) | 0;
    return value;
  }

  // A page that's still loading never counts as the bottom. The signature is a set of hashes, so
  // the app can tell the page changed without ever receiving its address or text.
  function readPage() {
    const loaded = document.readyState === "complete";
    const atBottom = loaded && (!scroller || scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - BOTTOM_TOLERANCE_PIXELS);
    if (!atBottom) return { atBottom: false, signature: null };
    const height = scroller ? scroller.scrollHeight : 0;
    const text = document.body ? document.body.textContent : "";
    return { atBottom: true, signature: `${hash(location.href)}:${height}:${text.length}:${hash(text)}` };
  }

  function watchVideos(roots) {
    for (const root of roots) {
      for (const video of root.querySelectorAll("video")) {
        if (watchedVideos.has(video)) continue;
        watchedVideos.add(video);
        video.addEventListener("ended", () => chrome.runtime.sendMessage({ type: "videoEnded" }).catch(() => {}));
      }
    }
  }

  function clearHighlight() {
    if (chosen && savedOutline !== null) {
      chosen.style.outline = savedOutline.outline;
      chosen.style.outlineOffset = savedOutline.offset;
    }
    savedOutline = null;
  }

  function highlight(element) {
    if (savedOutline !== null && chosen === element) return;
    clearHighlight();
    chosen = element;
    savedOutline = { outline: element.style.outline, offset: element.style.outlineOffset };
    element.style.outline = "3px solid #ff6d00";
    element.style.outlineOffset = "2px";
  }

  function scrollStep(time) {
    if (!settings.enabled || !settings.scrolling || !scroller) {
      lastFrameTime = null;
      carry = 0;
      return;
    }
    if (lastFrameTime !== null) {
      carry += (settings.pixelsPerSecond * (time - lastFrameTime)) / 1000;
      const whole = Math.floor(carry);
      if (whole > 0) {
        scroller.scrollTop += whole;
        carry -= whole;
      }
    }
    lastFrameTime = time;
    requestAnimationFrame(scrollStep);
  }

  async function tick() {
    let found = null;
    let area = 0;
    let reading = null;
    if (settings.enabled) {
      const roots = allRoots();
      found = findButton(settings.words || [], roots);
      watchVideos(roots);
      const scroll = findScroller();
      scroller = scroll.element;
      area = scroll.area;
      if (settings.autoRun) reading = readPage();
    }
    let response;
    try {
      response = await chrome.runtime.sendMessage({ type: "tick", rank: found ? found.rank : null, label: found ? found.label : null, area, reading });
    } catch {
      if (!chrome.runtime?.id) {
        clearHighlight();
        return; // The extension was reloaded; the new copy takes over.
      }
    }
    const wasScrolling = settings.enabled && settings.scrolling;
    settings = response || { enabled: false };
    if (settings.selected && found) {
      highlight(found.element);
    } else {
      clearHighlight();
      chosen = null;
    }
    if (settings.enabled && settings.scrolling && !wasScrolling) requestAnimationFrame(scrollStep);
    setTimeout(tick, settings.enabled ? ACTIVE_TICK_MILLIS : IDLE_TICK_MILLIS);
  }

  chrome.runtime.onMessage.addListener((message) => {
    if (message.type !== "click" || !chosen || !chosen.isConnected || !isUsable(chosen)) return;
    chosen.scrollIntoView({ block: "center" });
    chosen.click();
  });

  tick();
})();
