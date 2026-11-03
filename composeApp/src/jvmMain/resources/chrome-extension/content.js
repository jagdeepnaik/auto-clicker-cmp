// Runs in each frame. Finds the next-step button, highlights it, scrolls, and notices videos ending.
// It clicks only when told to after a key press, and skips anything disabled.
// During a scroll and click run it also reports whether the page has reached the bottom, and starts
// each video it reaches. Videos play as the page set them: never faster, and never skipped ahead.
(() => {
  if (window.__autoClickerHelper) return;
  window.__autoClickerHelper = true;

  const ACTIVE_TICK_MILLIS = 700;
  const IDLE_TICK_MILLIS = 2000;
  const MAX_LABEL_LENGTH = 40;
  const BOTTOM_TOLERANCE_PIXELS = 2;
  const MAX_HASHED_CHARACTERS = 200000;
  const VIDEO_END_TOLERANCE_SECONDS = 0.5;
  // Older course pages often use links without an href, or plain elements with an onclick handler.
  const CLICKABLE = 'button, a, summary, [onclick], [role="button"], [role="link"], input[type="button"], input[type="submit"]';

  let settings = { enabled: false };
  let chosen = null;
  let savedOutline = null;
  let scroller = null;
  let lastFrameTime = null;
  let carry = 0;
  const watchedVideos = new WeakSet();
  const videosInView = new WeakSet();
  const videosReached = new WeakSet();
  const videosStarted = new WeakSet();
  const videosFinished = new WeakSet();
  const videosRefused = new WeakSet();
  const videoViewObserver = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) {
        videosInView.delete(entry.target);
        continue;
      }
      videosInView.add(entry.target);
      // Remembered, so a fast scroll past a video still stops for it.
      if (isWatchingVideos()) videosReached.add(entry.target);
    }
  }, { threshold: 0.25 });

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
        videoViewObserver.observe(video);
        video.addEventListener("ended", () => chrome.runtime.sendMessage({ type: "videoEnded" }).catch(() => {}));
      }
    }
  }

  function isWatchingVideos() {
    return settings.enabled && settings.autoRun && settings.watchVideos;
  }

  // Looping or silent autoplaying videos are decoration, and a live stream never ends.
  function isWorthWatching(video) {
    if (video.loop || (video.muted && video.autoplay) || video.duration === Infinity) return false;
    const box = video.getBoundingClientRect();
    if (box.width <= 0 || box.height <= 0) return false;
    const style = getComputedStyle(video);
    return style.visibility !== "hidden" && style.display !== "none";
  }

  function hasFinished(video) {
    if (video.ended || videosFinished.has(video)) return true;
    return Number.isFinite(video.duration) && video.duration > 0 && video.currentTime >= video.duration - VIDEO_END_TOLERANCE_SECONDS;
  }

  function startVideo(video) {
    videosStarted.add(video);
    video.scrollIntoView({ block: "center" });
    Promise.resolve(video.play()).catch((error) => {
      // Browsers can refuse to start sound by themselves. Nothing to play means nothing to wait for.
      if (error.name === "NotAllowedError") videosRefused.add(video);
      else if (error.name === "NotSupportedError") videosFinished.add(video);
    });
  }

  // "playing", "needsPlay" or "none". Once started, a pause is left alone: it's the viewer's choice.
  function checkVideos(roots) {
    if (!isWatchingVideos()) return "none";
    let needsPlay = false;
    for (const root of roots) {
      for (const video of root.querySelectorAll("video")) {
        if (!isWorthWatching(video)) continue;
        if (videosInView.has(video)) videosReached.add(video);
        if (!videosReached.has(video)) continue;
        // Before the finished check, because players reuse one element for several clips.
        if (!video.paused && !video.ended) return "playing";
        if (hasFinished(video)) continue;
        if (!videosStarted.has(video)) {
          startVideo(video);
          return "playing";
        }
        needsPlay = true;
      }
    }
    return needsPlay ? "needsPlay" : "none";
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
    let video = "none";
    if (settings.enabled) {
      const roots = allRoots();
      found = findButton(settings.words || [], roots);
      watchVideos(roots);
      const scroll = findScroller();
      scroller = scroll.element;
      area = scroll.area;
      if (settings.autoRun) reading = readPage();
      video = checkVideos(roots);
    }
    let response;
    try {
      response = await chrome.runtime.sendMessage({ type: "tick", rank: found ? found.rank : null, label: found ? found.label : null, area, reading, video });
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
