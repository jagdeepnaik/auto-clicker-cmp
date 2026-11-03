// Links the page to the AutoClicker app on this computer. It never clicks by itself: its clicks only
// follow a key press the app reports, and nothing is sent anywhere else. During a scroll and click
// run it reports where the page is (never its contents), and the app does the clicking.
const BASE = "http://127.0.0.1:47613";
const STALE_MILLIS = 3000;

let command = null;
let polling = false;
let lastReported;
let lastPage = null; // what the app last heard about the page being scrolled
const frames = new Map(); // "tabId:frameId" -> { tabId, frameId, rank, label, area, at }

const sleep = (millis) => new Promise((resolve) => setTimeout(resolve, millis));

async function pollApp() {
  if (polling) return;
  polling = true;
  let version = -1;
  for (;;) {
    try {
      const response = await fetch(`${BASE}/state?after=${version}`);
      if (!response.ok) throw new Error(String(response.status));
      const body = await response.json();
      version = body.version;
      applyCommand(body.state);
    } catch {
      // App closed. Forget the key count, so presses from before reconnecting never fire later.
      command = null;
      lastReported = undefined;
      lastPage = null;
      version = -1;
      await sleep(3000);
    }
  }
}

function applyCommand(next) {
  const previous = command;
  command = next;
  if (!next || !next.autoRun) lastPage = null;
  if (previous && next && next.advance > previous.advance) clickSelected();
}

async function activeTabId() {
  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  return tab ? tab.id : null;
}

function frameEntries(tabId) {
  const now = Date.now();
  return [...frames.values()].filter((f) => f.tabId === tabId && now - f.at < STALE_MILLIS);
}

// A video in any frame holds up the whole page: it may well sit in an iframe of its own.
function videoState(tabId) {
  const states = frameEntries(tabId).map((f) => f.video);
  if (states.includes("playing")) return "playing";
  return states.includes("needsPlay") ? "needsPlay" : "none";
}

function selectedFrame(tabId) {
  const found = frameEntries(tabId).filter((f) => f.rank !== null);
  found.sort((a, b) => a.rank - b.rank || a.frameId - b.frameId);
  return found[0] || null;
}

function scrollerFrame(tabId) {
  const scrollable = frameEntries(tabId).filter((f) => f.area > 0);
  scrollable.sort((a, b) => b.area - a.area || a.frameId - b.frameId);
  return scrollable[0] || null;
}

async function clickSelected() {
  const tabId = await activeTabId();
  const frame = tabId === null ? null : selectedFrame(tabId);
  if (frame) chrome.tabs.sendMessage(tabId, { type: "click" }, { frameId: frame.frameId }).catch(() => {});
}

function report(event) {
  fetch(`${BASE}/event`, { method: "POST", headers: { "Content-Type": "text/plain" }, body: JSON.stringify(event) }).catch(() => {});
}

function reportPage(page) {
  lastPage = page;
  report({ type: "page", ...page });
}

// Only the frame being scrolled speaks for the page; the top frame does when nothing scrolls.
async function reportPageFrom(frameId, scroller, reading, video) {
  if (!reading || frameId !== (scroller ? scroller.frameId : 0)) return;
  const window = await chrome.windows.getLastFocused();
  const page = { focused: window.focused, atBottom: reading.atBottom, signature: reading.signature, video };
  if (JSON.stringify(page) !== JSON.stringify(lastPage)) reportPage(page);
}

async function onTick(message, sender) {
  const tabId = sender.tab?.id;
  const frameId = sender.frameId ?? 0;
  const active = tabId !== undefined && tabId === (await activeTabId());
  if (!command || !command.enabled || !active) return { enabled: false };
  frames.set(`${tabId}:${frameId}`, { tabId, frameId, rank: message.rank, label: message.label, area: message.area, video: message.video, at: Date.now() });
  const selected = selectedFrame(tabId);
  const label = selected ? selected.label : null;
  if (label !== lastReported) {
    lastReported = label;
    report({ type: "found", label });
  }
  const scroller = scrollerFrame(tabId);
  const video = command.autoRun ? videoState(tabId) : "none";
  if (command.autoRun) await reportPageFrom(frameId, scroller, message.reading, video);
  return {
    enabled: true,
    words: command.words,
    scrolling: command.scrolling && scroller?.frameId === frameId && video === "none",
    autoRun: !!command.autoRun,
    watchVideos: !!command.watchVideos,
    pixelsPerSecond: command.pixelsPerSecond,
    selected: selected?.frameId === frameId,
  };
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message.type === "tick") {
    onTick(message, sender).then(respond, () => respond({ enabled: false }));
    return true;
  }
  if (message.type === "videoEnded") {
    activeTabId().then((tabId) => {
      if (command?.enabled && sender.tab?.id === tabId) report({ type: "videoEnded" });
    });
  }
  return false;
});

// Pages report only every so often, and the app must hear at once when Chrome leaves the front.
chrome.windows.onFocusChanged.addListener((windowId) => {
  if (lastPage) reportPage({ ...lastPage, focused: windowId !== chrome.windows.WINDOW_ID_NONE });
});

// Chrome stops an idle worker after about 30 seconds, and a waiting fetch doesn't count as activity.
// Any extension API call does, so this keeps the link to the app alive between key presses.
setInterval(() => chrome.runtime.getPlatformInfo(() => {}), 20000);

// If Chrome stops this worker anyway, the alarm brings it back so the app link recovers.
chrome.alarms.create("keep-link", { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener(pollApp);
chrome.runtime.onStartup.addListener(pollApp);
chrome.runtime.onInstalled.addListener(pollApp);
pollApp();
