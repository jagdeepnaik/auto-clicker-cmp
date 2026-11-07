# AutoClicker

AutoClicker clicks for you, on Chrome pages and in any other app. Save a routine that says where to click, how often, and when to stop, then start and stop it from any app with a hot key.

Built with Kotlin Multiplatform and Compose Multiplatform. macOS and Windows come first.

## Features

- **Routines:** click wherever the pointer is, or at a list of specific points. Left, right or middle button, single or double click.
- **Pick on screen:** hover over the target in the other app and press F7 to record each point.
- **Timing:** any interval from 10 ms up to 24 hours, with an optional random extra wait, and a start delay.
- **Stop conditions:** when you stop it, after a number of clicks, or after some time.
- **Only in one app:** pause automatically unless a chosen app, such as Google Chrome, is in front.
- **Activity:** what ran, how many clicks it made, and why it stopped.
- **One page for every routine:** the Routines page holds click routines, scroll and click routines and page helper routines. Choose the kind with **New routine**. Only one routine runs at a time.
- **Developer mode:** a Settings switch that turns Activity into a live log of what the app does, including every message to and from Chrome. Copy it to report a problem.
- **Keep the display on:** an optional Settings switch that stops the display sleeping while something is running.

## Page helper (Chrome)

For pages you're working through yourself, in your normal Chrome profile. It removes the repetitive parts while you're at the screen:

- **Auto-scroll:** scrolls the open tab at the speed you set. Press **F9** in Chrome or use the app's button.
- **Next step:** highlights the first visible button matching your words, such as Next or Play. Disabled buttons are skipped.
- **One key, one click:** press **F8** to click the highlighted button. It never clicks without a key press.
- **Video alerts:** plays a sound when a video ends.

### Scroll and click

For long feeds, "load more" lists and paged documents, the helper can also run by itself: it scrolls to the bottom, clicks a spot you picked, waits for the page to change, and repeats. This is the one mode that clicks with no key press, and it's off until you start it.

With **Stop for videos** on, it stops at each video it reaches and plays it so you can watch, at the speed the page set, pressing the video's play button if it has one. When the video ends, it scrolls the rest of the way and clicks. If Chrome won't start a video by itself, or you pause one, it waits for you to press play. Looping and silent autoplaying videos are skipped, and it can't see players that don't use a standard video element.

1. On the Routines page, press **New routine** and choose **Scroll and click**.
2. Press **Pick on screen**, hold the pointer over the spot in Chrome, and press **F7**. Set the number of rounds and save.
3. Select the routine and press **Start**. Chrome comes to the front by itself. Clicking pauses whenever another app is in front, so leave Chrome there.

After adding or reloading the extension, refresh any page that's already open.

It stops after the last round, or if a click leaves the page unchanged for 15 seconds. The usual stops below also work, and each run appears in Activity. The spot is a position on the screen, so keep the Chrome window where it was when you picked it.

A **Page helper** routine highlights the next button while it runs; press **F8** to click it and **F9** to scroll.

Set up the extension once from **Settings > Chrome extension**: press **Show extension folder**, open `chrome://extensions`, turn on Developer mode, choose **Load unpacked**, and select that folder. The extension talks only to the app on this computer.

Some pages ignore scripted clicks, or use a player the helper can't read, such as a canvas. On those you click as usual.

## Stopping a run

- Press **F6** in any app. On a Mac keyboard you may need to hold fn. It stops a scroll and click run too.
- Push the pointer into any **corner of a screen**.
- Move the pointer by hand, for a routine with specific points or a scroll and click run that has made its first click.
- A click that would land on AutoClicker's own focused window waits instead, so Stop stays reachable.

The hot keys and both pointer stops can be changed in Settings.

## Platform status

| Platform | Status | Notes |
| --- | --- | --- |
| macOS (Apple Silicon) | Tested | Needs Accessibility permission, which the app asks for. |
| Windows | Not tested yet | The code compiles, but it hasn't run on Windows. Apps running as administrator can't be clicked. |
| Linux | Not supported yet | The app opens but can't click. |
| Android, iOS | Not supported yet | The app opens and says it's desktop-only. |

Clicks go to a position on the screen, so the target must be visible. If a page scrolls or a window moves, a saved point keeps clicking the same spot on the screen.

On macOS, a run from the IDE or a terminal uses that app's Accessibility permission, while a packaged app asks for its own.

## Requirements

- JDK 21 (Gradle provisions it; Android Studio's bundled JDK can launch Gradle)
- Android Studio with the Kotlin Multiplatform plugin, Android SDK 37
- Xcode 16.2 or later for iOS

## Build and run

Use `.\gradlew.bat` on Windows.

| Target | Command |
| --- | --- |
| Desktop | `./gradlew :composeApp:run` |
| Android (debug) | `./gradlew :composeApp:installDebug` |
| iOS | Open [`iosApp`](./iosApp) in Xcode and run |

## Tests

```shell
./gradlew jvmTest verifyFeatureModuleBoundaries
```

Three extras are opt-in:

- `UI_TEST_SHOT_DIR=/some/dir` saves what each UI test rendered.
- `AUTOCLICKER_LIVE_INPUT_TEST=1 ./gradlew :core:automation:jvmTest --tests '*LiveInputTest*' --rerun` sends real clicks and a key press to its own window. It moves your pointer, so keep your hands off while it runs.
- `AUTOCLICKER_LIVE_CHROME_TEST="<path to Chrome>" ./gradlew :composeApp:jvmTest --tests '*LiveChromeExtensionTest*' --rerun` runs the extension's scripts in headless Chrome with a throwaway profile.

## Release builds

Version name and code live in [`gradle/libs.versions.toml`](./gradle/libs.versions.toml).

| Platform | Built on | Command | Output |
| --- | --- | --- | --- |
| macOS DMG (Apple Silicon) | Mac | `./gradlew :composeApp:packageReleaseDmg` | `composeApp/build/compose/binaries/main-release/dmg/` |
| Windows MSI | GitHub Actions | `gh workflow run desktop-packages.yml` | Run artifacts |
| Linux DEB, macOS DMG (Intel) | GitHub Actions | `gh workflow run desktop-packages.yml -f platforms=all` | Run artifacts |

The installers are not code-signed yet, so macOS Gatekeeper and Windows SmartScreen warn on first launch.

## Project structure

```text
composeApp/        App shell: App.kt, navigation host, platform entry points, desktop packaging
core/automation    Clicks, global hot keys, front app, input permission (macOS, Windows)
core/localbridge   Loopback link to a trusted local client, such as the Chrome extension
core/common        Platform and time helpers (template)
core/logger        Logging (template)
core/ui            Design system: theme and palettes, shared components, navigation (template)
core/persistence   Preference storage and Room (template)
core/network       Template module, not used by this app
core/notifications Template module, not used by this app
core/transfer      Template module, not used by this app
feature/home       Routines page: every kind of routine, the editor, and their runners
feature/activity   Run history
feature/settings   Hot keys, safety, permission, appearance
feature/shared     Contracts shared by more than one feature
iosApp/            iOS entry point (Xcode project)
```

Features depend only on core modules and `feature:shared`, never on each other. [How it works](private/docs/HOW_IT_WORKS.md) explains every step, including how the app talks to Chrome. Coding and architecture conventions are in [AGENTS.md](./AGENTS.md).
