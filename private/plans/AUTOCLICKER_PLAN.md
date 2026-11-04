# AutoClicker plan and status

Started 2026-11-03. A desktop auto clicker for Chrome pages and other apps. macOS and Windows first.

## Decisions

- `core:*` copied from the Zynk template stay byte-identical (`diff -r private/zynk-cmp-main/core/<module> core/<module>`).
  Consequences: the root build pins core:ui's generated `Res` package; Room is not used (no new entities), so
  routines and history are JSON documents in DataStore, the template's sync pattern; `RouteTopLevel.Home` and
  `Activity` are reused and `Sync` is unused; `AppIcon` (the Zynk logo) is unused, the app passes its own.
- New `core:automation` is the one added core module: pointer, access, hot keys, front app.
- Clicks are OS-level at screen positions (AWT Robot), so they work in Chrome and any other app.
- Hot keys are sampled rather than hooked: no global keyboard hook, only F1-F12 are ever looked at.
- Front-app lookups on macOS use `lsappinfo`, which avoids an Objective-C bridge, and are cached for 250 ms.

- Page helper is hands-on by design: the extension clicks only after a key press, skips disabled controls, and
  doesn't answer questions or bypass course checks. It exists for a CBT course the owner is taking personally;
  unattended completion of recorded training was declined.
- Reaching the normal Chrome profile requires an extension (remote debugging can't). It ships inside the app in
  `composeApp/src/jvmMain/resources/chrome-extension`; the app controls it over `core:localbridge`. A fixed manifest
  key gives a fixed extension ID, and the link trusts only that Origin, so no pairing secret is stored.
- The tab reuses `RouteTopLevel.Sync` because core:ui stays an exact copy.
- Scroll and click (2026-11-03): scrolls to the bottom, clicks a picked screen position, repeats. Requested by the owner
  for uses other than the CBT course, which they confirmed it isn't for; the hands-on rule above still holds for that
  course. The extension still clicks only on a key press: it only reports position (focus, at bottom, a hash signature,
  never address or text), and the app clicks through the OS. F6 stops it, via `stopOtherRun` on ClickController.

## Status

- Done: routines, editor with on-screen picking, run engine, safety stops, Activity, Settings, packaging config.
- Verified on Apple Silicon macOS only. Windows code compiles but has never run: `WindowsNativeInput`, MSI, and
  mixed-DPI behaviour of the "moved the pointer" stop all need a real Windows machine.
- Page helper: extension scripts and link tested in headless Chrome with stand-in `chrome.*` APIs. Loading through
  chrome://extensions, iframes in a real extension, and the alert sound still need a person to try them.
- Stop for videos (2026-11-03): Scroll and click stops at each video, plays it at the page's speed for the owner to
  watch, and goes on when it ends. It never changes speed or seeks, and never resumes a video that was paused.
  It also presses a video's play button once (a scripted click), since many players create the video only then. That's
  the only click the extension makes without a key press, and only in a run the user started with the switch on.
- Scroll and click: logic covered by tests with fakes; the extension's bottom report checked in headless Chrome with
  stand-in `chrome.*` APIs. A real OS click on a real page through the loaded extension still needs a person to try it.
- Android and iOS: the shell runs and explains it is desktop-only.

## Possible next steps

- Chrome element targeting (a CSS selector over the DevTools protocol), which survives scrolling and layout changes
  but requires starting Chrome with remote debugging.
- Per-point delays; import and export of routines.
- Remove Zynk naming from the core modules, in the template first.
