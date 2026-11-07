// Runs in each frame. Finds the next-step button, highlights it, scrolls, and notices videos ending.
// It clicks only when told to after a key press, and skips anything disabled.
// During a scroll and click run it also reports whether the page has reached the bottom, clicks a
// button by its text when the app asks (each request once), and starts
// each video it reaches, pressing its play button if it has one. That's the only click a page gets
// here without a key press, and only in a run the user started. Videos play as the page set them:
// never faster, and never skipped ahead.
(() => {
  if (window.__autoClickerHelper) return;
  window.__autoClickerHelper = true;

  const ACTIVE_TICK_MILLIS = 700;
  const IDLE_TICK_MILLIS = 2000;
  const MAX_LABEL_LENGTH = 40;
  const BOTTOM_TOLERANCE_PIXELS = 2;
  const MAX_HASHED_CHARACTERS = 200000;
  const VIDEO_END_TOLERANCE_SECONDS = 0.5;
  // Some pages report more content than they let you scroll to, so no movement counts as the end.
  const CANNOT_SCROLL_MILLIS = 3000;
  const MAX_SCROLL_DRIFT_PIXELS = 50;
  // A page that never finishes loading, such as one with a stuck iframe, mustn't hold a run forever.
  const MAX_LOAD_WAIT_MILLIS = 15000;
  // Older course pages often use links without an href, or plain elements with an onclick handler.
  const CLICKABLE = 'button, a, summary, [onclick], [role="button"], [role="link"], input[type="button"], input[type="submit"]';

  // Many players show a picture and a button, and only create the video once it's pressed.
  const PLAY_CONTROL_CANDIDATES = `${CLICKABLE}, [class*="play" i], [id*="play" i], [aria-label*="play" i], [title*="play" i]`;
  const PLAY_CONTROL = /(^|[\s_-])play([\s_-]|$)|play[-_]?(button|btn|icon|overlay)/i;
  const NOT_PLAY_CONTROL = /pause|playlist|\b(stop|next|prev|previous|again|all)\b/i;
  const MIN_PLAY_CONTROL_PIXELS = 16;
  const PLAY_CONTROL_WAIT_MILLIS = 8000;

  let settings = { enabled: false };
  let chosen = null;
  let savedOutline = null;
  let scroller = null;
  let lastFrameTime = null;
  let carry = 0;
  let scrollTarget = 0;
  let lastScrollTop = 0;
  let lastMovedAt = 0;
  let cannotScroll = false;
  const startedAt = Date.now();
  const watchedVideos = new WeakSet();
  const videosInView = new WeakSet();
  const videosReached = new WeakSet();
  const videosStarted = new WeakSet();
  const videosFinished = new WeakSet();
  const videosRefused = new WeakSet();
  const pressedPlayControls = new WeakSet();
  let playControlPressedAt = 0;
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

  // A page still loading doesn't count as the bottom, and nothing to scroll does. Content is a set
  // of hashes, so the app can tell the page changed without receiving its address, text or images.
  function readPage() {
    const loaded = document.readyState === "complete" || Date.now() - startedAt >= MAX_LOAD_WAIT_MILLIS;
    const reachedEnd = !scroller || cannotScroll || scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - BOTTOM_TOLERANCE_PIXELS;
    const text = document.body ? document.body.textContent : "";
    const images = [...document.images].map((image) => image.currentSrc).join();
    const height = document.documentElement.scrollHeight;
    return { atBottom: loaded && reachedEnd, content: `${hash(location.href)}:${height}:${text.length}:${hash(text)}:${hash(images)}` };
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
    return settings.enabled && ((settings.autoRun && settings.watchVideos) || (settings.course && courseKind() === "lesson"));
  }

  // Course helper: only on the Sagar Mein Yog course site (and this computer's own test pages).
  const COURSE_SITE = /(^|\.)sagarmeinyog\.com$/;

  // "dashboard" on the list of courses, "course" on a course's page of parts, "lesson" on a part
  // being watched, otherwise null.
  function courseKind() {
    if (!COURSE_SITE.test(location.hostname) && location.hostname !== "127.0.0.1") return null;
    const parts = location.pathname.split("/").filter(Boolean);
    if (parts[0] !== "dashboard") return null;
    if (parts.length === 1 || (parts.length === 2 && parts[1] === "courses")) return "dashboard";
    if (parts[1] !== "courses") return null;
    if (parts.length === 3) return "course";
    return parts.length >= 4 ? "lesson" : null;
  }

  // A course card: the nearest container showing the course's pass mark and duration.
  function cardOf(element) {
    let node = element;
    for (let i = 0; i < 8 && node; i++) {
      const text = node.innerText || "";
      if (/min\.?\s*pass/i.test(text) && /duration/i.test(text)) return node;
      node = node.parentElement;
    }
    return null;
  }

  function looksLikePlay(element) {
    const text = [element.getAttribute("aria-label"), element.getAttribute("title"), element.getAttribute("class"), element.id].filter(Boolean).join(" ");
    return /play/i.test(text) || (!labelOf(element) && element.querySelector("svg") !== null);
  }

  // The next course card that doesn't show as completed; its play button, else whatever on it opens it.
  function findNextCourse(roots) {
    const cards = new Map();
    for (const root of roots) {
      for (const element of root.querySelectorAll(CLICKABLE)) {
        if (!isUsable(element)) continue;
        const card = cardOf(element);
        if (!card) continue;
        if (!cards.has(card)) cards.set(card, []);
        cards.get(card).push(element);
      }
    }
    for (const [card, controls] of cards) {
      if (isDone(card.innerText || "")) continue;
      const element = controls.find(looksLikePlay) || controls[controls.length - 1];
      const title = card.querySelector("h1, h2, h3, h4");
      const name = (title ? title.textContent : "").replace(/\s+/g, " ").trim();
      return { element, label: ("Open " + (name || "the next course")).slice(0, MAX_LABEL_LENGTH), rank: 0 };
    }
    return null;
  }

  // The part's row: the nearest container that also shows its status.
  function rowOf(element) {
    let node = element;
    for (let i = 0; i < 6 && node.parentElement; i++) {
      node = node.parentElement;
      if (/not started|in progress|completed/i.test(node.innerText || "")) return node;
    }
    return null;
  }

  // Pages often run a row's cells together ("VIDEOCompletedWatch video"), so no word breaks are relied on.
  function isDone(text) {
    return /completed/i.test(text) && !/not started|in progress|not completed|incomplete/i.test(text);
  }

  // A part's name from its first two cells, such as "1.2 Part:2".
  function partLabel(container, fallback) {
    if (!container) return fallback;
    const cells = [...container.querySelectorAll("*")]
      .filter((element) => element.children.length === 0)
      .map((element) => (element.textContent || "").replace(/\s+/g, " ").trim())
      .filter(Boolean);
    return (cells.slice(0, 2).join(" ") || fallback).slice(0, MAX_LABEL_LENGTH);
  }

  // What the next key press should open: never a quiz or an assessment.
  function findCourseTarget(roots) {
    const kind = courseKind();
    if (kind === "course") {
      for (const root of roots) {
        for (const element of root.querySelectorAll(CLICKABLE)) {
          if (!/^watch video$/i.test(labelOf(element)) || !isUsable(element)) continue;
          const row = rowOf(element);
          if (!isDone(row ? row.innerText : "")) return { element, label: partLabel(row, "Next part"), rank: 0 };
        }
      }
      // The next part may sit in a closed chapter; its header opens it.
      for (const root of roots) {
        for (const element of root.querySelectorAll('[aria-expanded="false"]')) {
          if (!isUsable(element) || isDone(element.innerText || "")) continue;
          return { element, label: ("Open " + partLabel(element, "the next chapter")).slice(0, MAX_LABEL_LENGTH), rank: 1 };
        }
      }
      return null;
    }
    if (kind === "lesson") return findButton(["Back to Course"], roots);
    if (kind === "dashboard") return findNextCourse(roots);
    return null;
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

  function isPlayControl(element) {
    const text = [element.getAttribute("aria-label"), element.getAttribute("title"), element.getAttribute("class"), element.id, labelOf(element)]
      .filter(Boolean).join(" ");
    return PLAY_CONTROL.test(text) && !NOT_PLAY_CONTROL.test(text);
  }

  function isOnScreen(element) {
    const box = element.getBoundingClientRect();
    return box.width >= MIN_PLAY_CONTROL_PIXELS && box.height >= MIN_PLAY_CONTROL_PIXELS
      && box.bottom > 0 && box.right > 0 && box.top < window.innerHeight && box.left < window.innerWidth;
  }

  // Each control is pressed once, so a video that has ended isn't started over.
  function pressPlayControl(roots) {
    for (const root of roots) {
      for (const element of root.querySelectorAll(PLAY_CONTROL_CANDIDATES)) {
        if (pressedPlayControls.has(element) || !isOnScreen(element) || !isUsable(element) || !isPlayControl(element)) continue;
        pressedPlayControls.add(element);
        playControlPressedAt = Date.now();
        element.scrollIntoView({ block: "center" });
        element.click();
        return true;
      }
    }
    return false;
  }

  // "playing", "needsPlay" or "none". Once started, a pause is left alone: it's the viewer's choice.
  function checkVideos(roots) {
    if (!isWatchingVideos()) return "none";
    const course = settings.course;
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
    if (needsPlay) return "needsPlay";
    // The course helper only starts real videos: a play button is pressed by the user's own key.
    if (course) return "none";
    // Holds the page while the player loads. A control that starts nothing doesn't hold it for long.
    if (Date.now() - playControlPressedAt < PLAY_CONTROL_WAIT_MILLIS) return "playing";
    return pressPlayControl(roots) ? "playing" : "none";
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
      cannotScroll = false;
      return;
    }
    if (lastFrameTime === null) {
      scrollTarget = scroller.scrollTop;
      lastScrollTop = scroller.scrollTop;
      lastMovedAt = time;
    } else {
      // Checked before moving, so a page that snaps back doesn't look like it moved.
      if (scroller.scrollTop !== lastScrollTop) {
        lastScrollTop = scroller.scrollTop;
        lastMovedAt = time;
        cannotScroll = false;
      } else if (time - lastMovedAt >= CANNOT_SCROLL_MILLIS) {
        cannotScroll = true;
      }
      carry += (settings.pixelsPerSecond * (time - lastFrameTime)) / 1000;
      const whole = Math.floor(carry);
      if (whole > 0) {
        // A running target, because a browser zoom can round away a small step every time, and
        // instant, because a page's smooth scrolling would restart on every step.
        if (Math.abs(scrollTarget - scroller.scrollTop) > MAX_SCROLL_DRIFT_PIXELS) scrollTarget = scroller.scrollTop;
        scrollTarget += whole;
        scroller.scrollTo({ top: scrollTarget, behavior: "instant" });
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
    let textMatch = null;
    if (settings.enabled) {
      const roots = allRoots();
      found = settings.course ? findCourseTarget(roots) : findButton(settings.words || [], roots);
      watchVideos(roots);
      const scroll = findScroller();
      scroller = scroll.element;
      area = scroll.area;
      if (settings.autoRun) reading = readPage();
      video = checkVideos(roots);
      if (settings.clickText) textMatch = findButton([settings.clickText], roots);
    }
    let response;
    try {
      response = await chrome.runtime.sendMessage({ type: "tick", rank: found ? found.rank : null, label: found ? found.label : null, area, reading, video, textRank: textMatch ? textMatch.rank : null });
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

  chrome.runtime.onMessage.addListener((message, sender, respond) => {
    if (message.type === "clickText") {
      // Found again at the moment of clicking, so it's still there and still enabled.
      const match = findButton([message.text], allRoots());
      if (!match) {
        respond({ clicked: null });
        return;
      }
      match.element.scrollIntoView({ block: "center" });
      match.element.click();
      respond({ clicked: match.label });
      return;
    }
    if (message.type !== "click" || !chosen || !chosen.isConnected || !isUsable(chosen)) return;
    chosen.scrollIntoView({ block: "center" });
    chosen.click();
  });

  tick();
})();
