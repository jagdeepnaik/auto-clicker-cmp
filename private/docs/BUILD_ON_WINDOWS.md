# Build AutoClicker on Windows, without Android

These steps build and run the desktop app on a Windows PC. You don't need Android Studio or the Android SDK.

**How far this has been tested:** the project was built and its desktop tests were run on a Mac with no Android SDK configured. The steps have not yet been run on a Windows PC, and the app itself has never run on Windows, so expect some rough edges.

## What you need

You need Windows 10 or 11 (64-bit), about 5 GB of free disk space, and an internet connection for the first build.

| Package | Needed for | Required? |
| --- | --- | --- |
| **Java JDK 21** (Eclipse Temurin) | Building and running the app. It must be the full JDK, not a JRE, because the installer step uses its `jpackage` tool | Yes |
| **Git** | Downloading the code | Yes |
| **Google Chrome** | The page helper. Routines work without it | For the page helper |
| **.NET Framework 3.5** | Building the MSI installer, if the WiX tools ask for it | Only if the MSI step fails |

**You don't need to install these.** The build downloads the first three by itself, and the last two aren't used:

- Gradle (the included `gradlew.bat` downloads it)
- Kotlin, Compose and the other libraries
- WiX, the installer tools
- Android Studio and the Android SDK
- Xcode

## Install the packages

### Install with one command (recommended)

Open **PowerShell** and run these. `winget` comes with current versions of Windows 10 and 11.

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK -e
winget install --id Git.Git -e
winget install --id Google.Chrome -e
```

Accept any prompts, then **close PowerShell and open a new window** so it picks up the new programs.

### Or install Java by hand

1. Open [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21).
2. Choose **Windows**, **x64**, **JDK**, and download the **.msi**.
3. Run it. On the **Custom Setup** screen, set **Set JAVA_HOME variable** to "Will be installed on local hard drive". It's off by default.
4. Install [Git](https://git-scm.com/download/win) and [Chrome](https://www.google.com/chrome/) the usual way.
5. Open a new PowerShell window.

### Set JAVA_HOME

Skip this if you turned on "Set JAVA_HOME variable" in the installer. Otherwise, run this once:

```powershell
$jdk = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-21*" | Select-Object -First 1).FullName
[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdk, "User")
```

Then **open a new PowerShell window**.

### Check everything

```powershell
java -version       # should say 21
jpackage --version  # should say 21
$env:JAVA_HOME      # should show a folder with jdk-21 in its name
git --version
```

If `java` isn't found, add it to your path, then open a new window:

```powershell
$path = [Environment]::GetEnvironmentVariable("Path", "User")
[Environment]::SetEnvironmentVariable("Path", "$path;$env:JAVA_HOME\bin", "User")
```

## 1. Get the code

Use a short path, because Windows can fail on very long file paths during a build.

```powershell
mkdir C:\src
cd C:\src
git clone https://github.com/jagdeepnaik/auto-clicker-cmp.git
cd auto-clicker-cmp
```

The repository is private, so Git will ask you to sign in to GitHub.

## 2. Run it

```powershell
.\gradlew.bat :composeApp:run
```

The first run downloads Gradle and the libraries, so it can take several minutes. The app window should then open. Close the window to stop it.

## 3. Build an installer (optional)

```powershell
.\gradlew.bat :composeApp:packageReleaseMsi
```

The installer is written to:

```text
composeApp\build\compose\binaries\main-release\msi\AutoClicker-1.0.0.msi
```

The build downloads the WiX installer tools by itself.

The installer isn't code-signed, so SmartScreen warns you. Choose **More info**, then **Run anyway**.

### Or build a version that needs no installing

```powershell
.\gradlew.bat :composeApp:createReleaseDistributable
```

Then run:

```text
composeApp\build\compose\binaries\main-release\app\AutoClicker\AutoClicker.exe
```

## 4. Run the tests (optional)

```powershell
.\gradlew.bat jvmTest verifyFeatureModuleBoundaries
```

These tests have only ever run on a Mac, so a failure here may be a real Windows problem. Please report any you see.

## Commands to avoid

These need the Android SDK and will fail without it:

- `.\gradlew.bat build`
- `.\gradlew.bat assemble`
- `.\gradlew.bat :composeApp:installDebug`
- Any task with `Android`, `Debug` or `Apk` in its name

If you open the project in an IDE, its sync may complain about the missing SDK. The commands above don't depend on that, so you can run them from PowerShell.

## 5. Set up Chrome

1. In the app, open **Settings**, and under **Chrome extension** press **Show extension folder**. It opens `%USERPROFILE%\.autoclicker\chrome-extension`.
2. In Chrome, open `chrome://extensions` and turn on **Developer mode**.
3. Choose **Load unpacked** and select that folder.
4. Refresh any page that's already open.

When the extension is connected, Settings shows **Connected to Chrome**.

## 6. Try it

Start with your display scale at **100%** (Settings > System > Display), because that's the simplest case. Then try these in order:

1. **Hot keys:** press F6 from another app. A routine should start, and F6 again should stop it.
2. **Clicking:** run a routine that makes 5 clicks at the pointer, on a blank Chrome tab.
3. **Picked spots:** pick a spot with F7 and check clicks land exactly there.
4. **Display scaling:** repeat step 3 at 125% or 150%, and with a second monitor if you have one. A spot may land in the wrong place, or the pointer-moved stop may fire by itself. This is the most likely thing to go wrong.
5. **Page helper:** turn it on, then press F9 to scroll and F8 to click a highlighted button.
6. **Scroll and click:** pick a spot and press **Start scroll and click**. Chrome should come to the front by itself. If it doesn't, click on it and leave it there.
7. **Keep the display on:** turn it on in Settings and start a run that lasts longer than your display's sleep time.

## How Windows differs from Mac

- **No Accessibility permission.** Windows doesn't ask for one.
- **Apps running as administrator can't be clicked.**
- **"Only in one app" uses a different name.** Chrome appears as `chrome`, not Google Chrome.
- **Your data** is in `%USERPROFILE%\.autoclicker`.

## If something goes wrong

| Problem | Try this |
| --- | --- |
| `JAVA_HOME is not set`, or `java` isn't found | Follow [Set JAVA_HOME](#set-java_home) and [Check everything](#check-everything), then open a new PowerShell window |
| `winget` isn't found | Install **App Installer** from the Microsoft Store, or install the packages by hand |
| `SDK location not found` | You ran a command that needs Android. Use the commands in steps 2 to 4 |
| A warning that iOS targets are disabled | Ignore it. iOS can't be built on Windows and isn't needed |
| `Failed to check JDK distribution` or `jpackage` not found | Install a full JDK 21, not a JRE |
| The MSI step fails on WiX (`light.exe` or `candle.exe`) | Install .NET Framework 3.5 by running `Enable-WindowsOptionalFeature -Online -FeatureName NetFx3 -All` in PowerShell as administrator, then run the command again. Or use `createReleaseDistributable`, which skips the installer |
| File path errors (`path too long`) | Move the project to a shorter path such as `C:\src` |
| The build runs out of memory | Close other apps. `gradle.properties` sets 4 GB for Gradle and 3 GB for Kotlin |
| "Can't reach Chrome", port in use | Another copy of AutoClicker is running. Close it and reopen the app |
| "Reading the page" stays on | Refresh the page in Chrome |
| Clicks land in the wrong place | Set the display scale to 100% to confirm, then report it with your scale and monitor setup |

## Report back

Note your Windows version, display scale and any error text. The Windows code hasn't been run before, so anything that fails is useful to know.

## Building without a Windows PC

A GitHub Actions workflow can also build the MSI, using GitHub's Windows machines:

```shell
gh workflow run desktop-packages.yml
```

Download the installer from that run's artifacts. This workflow hasn't been run yet either.
