# Geneo Toolbox — Floating Overlay App

> **Update:** added a battery-optimization exemption step (Step 3 in the app)
> so Android doesn't pause/kill the overlay in the background, fixed a bug
> that could crash/freeze the calculator (see "What was wrong with the
> calculator" below), and hardened every WindowManager call in the service so
> a device-specific quirk can no longer take down the whole floating toolbox.

A floating "chat-head" style toolbox for Geneo smart boards. Shows a small
draggable bubble on top of every app; tapping it pops open an animated menu
with **Stopwatch**, **Timer**, and **Calculator**; each tool opens as its own
small draggable window with a close (✕) button. The bubble auto-starts on
every boot once you've completed setup a single time.

## What's included
- Full Android Studio (Kotlin) project, ready to open and build.
- No paid dependencies — only AndroidX + Material.

## How to build the APK

1. Install **Android Studio** (Hedgehog/Iguana or newer — free from
   developer.android.com).
2. Open Android Studio → **Open** → select the `GeneoOverlay` folder (the one
   containing `settings.gradle`).
3. Let Gradle sync (first sync downloads the Gradle 8.4 + AGP 8.2 toolchain —
   needs internet once).
4. Build the APK:
   - Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
   - Or in a terminal inside the project folder: `./gradlew assembleDebug`
5. The APK appears at:
   `app/build/outputs/apk/debug/app-debug.apk`
6. Copy that APK to the Geneo smart board (USB, ADB, or a file-share app)
   and install it (enable "install unknown apps" for that source first).

To produce a signed **release** APK for wider deployment, use
**Build → Generate Signed Bundle / APK**, create/select a keystore, and
build the `release` variant.

## First-time setup on the board (one-time only)
1. Open the **Geneo Toolbox** app.
2. Tap **"Allow display over other apps"** → grant the permission for Geneo
   Toolbox in the system settings screen that opens → go back to the app.
3. Tap **"Enable Floating Toolbox"**.
4. Tap **"Allow Background Activity"** → confirm on the system dialog. This
   exempts the app from Android's battery optimization so the bubble doesn't
   get paused/killed in the background.
5. The bubble appears on screen immediately, and will now also reappear
   automatically after every future reboot — no need to open the app again.

## Boot-start reliability, especially on locked-down boards
Two independent mechanisms bring the bubble back after a reboot:
- **`BootReceiver`** — listens for Android's standard `BOOT_COMPLETED`
  broadcast and starts the service directly. This is instant, but some
  heavily customized board firmwares (a custom launcher, no real Settings
  app, no visible "Autostart" toggle) suppress or delay boot broadcasts to
  third-party apps in ways no in-app permission can override.
- **`OverlayWatchdogWorker`** — a WorkManager job that checks every 15
  minutes (the platform's minimum interval for periodic work) whether the
  toolbox *should* be running and restarts it if it isn't. WorkManager
  reschedules its own periodic jobs after every reboot automatically, via a
  mechanism independent of `BootReceiver`, so this acts as a self-healing
  safety net — even if the boot broadcast is suppressed, the bubble should
  come back within 15 minutes of boot rather than staying down all day.
  Scheduled automatically whenever the toolbox is enabled; canceled when
  disabled.

If a board still won't auto-start even with both of these in place, that
almost always means the OEM firmware is actively blocking third-party
background starts at a level neither mechanism can reach from inside the
app. Things worth checking on the device itself:
- A hidden "admin" or "engineer" mode in the custom launcher (a repeated
  tap on a corner, a long-press, or a code entered somewhere) that unlocks
  the full stock Android Settings, where a real per-app Autostart/battery
  control usually lives.
- If you have ADB access to the board, `adb shell dumpsys deviceidle
  whitelist +com.geneo.smartboard.overlay` adds it to Android's standard
  Doze whitelist directly (redundant with the in-app "Allow Background
  Activity" button if that already worked, but worth trying if it didn't).
- Contacting the board manufacturer's support — as the firmware vendor,
  they'll know their own whitelisting mechanism for third-party apps, if
  one exists.

## What was wrong with the calculator (fixed)
The calculator used to format results with thousands-separator commas
(e.g. `1,234`). That formatted string was then fed straight back into the
number parser on the very next button press — parsing `"1,234"` as a number
fails, which left the calculator stuck/unresponsive, and an unguarded
overlay-window call elsewhere in the service could turn that into a full
crash of the floating toolbox on some devices. Fixed by:
- Formatting numbers without grouping separators, so every result can be
  parsed straight back into the next calculation.
- Wrapping every calculator button action so a bad edge case (divide by
  zero, an unparsable value, etc.) resets to a clean state instead of
  throwing.
- Wrapping every `WindowManager` call in the overlay service (adding,
  removing, repositioning the bubble/menu/tool windows) so a device-specific
  failure can no longer crash the whole service — it now just logs a warning
  and skips that one action.

This wasn't a tablet-only bug — the fix removes the actual root cause, so it
applies (and was needed) on any device, tablet or board alike.

## Using it
- **Single tap** the bubble → menu of 5 tools pops open with a bounce
  animation: Stopwatch, Timer, Calculator, Books (NCERT), Pen.
- **Tap the bubble again** → menu closes with a matching animation.
- **Drag** the bubble anywhere; release and it snaps to the nearest screen
  edge.
- Tap **Stopwatch / Timer / Calculator / Books** → opens as its own floating
  card (the menu auto-closes). Each has a **header you can drag** to
  reposition it, a **bottom-right corner you can drag to resize** it freely,
  and an **✕ button** to close it. Multiple can be open at once.
- Tap **Books** → browse Subject → Chapter → opens that chapter's PDF in a
  resizable viewer with page-by-page navigation (see "NCERT books" below —
  needs a one-time import first).
- Tap **Pen** → draws directly on top of whatever is currently on screen
  (any app, or the PDF viewer above) — the canvas itself is fully
  transparent, only your strokes are visible. A small draggable toolbar
  (drag its grip icon to move it) gives you 3 pen presets (Pen/Marker/
  Highlighter), 5 colors, size +/- (separate size for pen vs eraser),
  eraser, clear-all, and close. While Pen is open it captures all touches
  on screen (so it can be drawn on everywhere) — close it to interact with
  apps underneath again.

## NCERT Class 10 books
Chapters aren't bundled into the app (that would make the APK enormous —
NCERT PDFs alone run into hundreds of MB). Instead:
1. Get NCERT Class 10 chapter PDFs organized like:
   `Science/ch1.pdf … Math/ch1.pdf … SSt/Eco|Geo|His|Pol Sc/ch1.pdf …
   English/First flight|FWF/ch1.pdf … Hindi/Kshitij|Kritika/ch1.pdf`
2. Copy that folder onto the board (USB, SD card, or downloaded there).
3. In the Geneo Toolbox app → **Manage NCERT Library** → **Select NCERT
   Folder** → pick the top-level folder once.
4. The app scans it, matches folder names automatically, and remembers
   every chapter's location (PDFs stay exactly where they are — nothing is
   copied). Re-run the import any time to refresh if you add/replace files.
5. Chapters now open from the bubble's **Books** menu, rendered fully
   offline via Android's built-in PDF renderer (no internet needed at
   lesson time).
6. **Import is a one-time snapshot, not a live sync.** If you add a new
   chapter PDF to the folder later, re-run step 3 (re-select the same
   folder) to pick it up — the app doesn't watch the folder for changes.
   Chapter numbers come from each file's own name (`ch5.pdf` → "Chapter 5"),
   not a hardcoded sequence, so re-importing after adding a missing chapter
   slots it into the right position automatically.

## PDF viewer extras

**Minimize** — the `−` button in the PDF header hides the window without
closing the chapter; the renderer, page cache, and your exact scroll/zoom
position all stay alive in memory. A small round icon docks near the bubble
(wherever the bubble was *at the moment you minimized* — it won't follow if
you drag the bubble afterward) — tap it to bring the exact same page and
zoom level straight back.

**Word meaning lookup** — the magnifying-glass button in the header lets you
drag a rectangle over a word/phrase on the page (shown as a green highlight).
This requires internet and a one-time setup, since our PDF pages are plain
rendered images with no text layer to select from directly:
1. Sign up for a free API key at **ocr.space/ocrapi** — no Google account,
   no card, just an email address, and it takes under a minute.
2. Paste it into the Geneo Toolbox app under **Step 5 · Word meaning
   lookup** and tap Save.
3. In any chapter, tap the lookup icon, drag over a word (or a whole
   phrase — it picks the most meaningful word out of it, skipping short
   words like "the"/"is"/"a"), and release. The app screenshots that
   region, sends it to OCR.space to read the text, looks up the definition
   via the free dictionaryapi.dev, and adds a Hindi translation of the word
   and each definition via MyMemory (also free, no key, no Google account)
   — shown as a card with word, phonetic spelling, translation, part of
   speech, numbered definitions with their own translations, and example
   sentences. No audio/pronunciation button by design.

Two honest limitations worth knowing: this feature needs a live internet
connection (unlike everything else in the app, which works fully offline),
and OCR accuracy depends on how clean the selected image region is — a
tightly-cropped single word works far better than a whole sloppily-dragged
sentence.

## Project structure
```
app/src/main/java/com/geneo/smartboard/overlay/
 ├─ MainActivity.kt          – one-time setup screen (permission + enable)
 ├─ BookLibraryActivity.kt   – one-time NCERT folder import + library view
 ├─ BootReceiver.kt          – restarts the overlay after every reboot
 ├─ OverlayService.kt        – foreground service: bubble, animated menu,
 │                             all tool windows, drag/resize/snap logic
 ├─ DragHelper.kt            – shared tap-vs-drag touch handling
 ├─ StopwatchController.kt   – stopwatch tick/lap logic
 ├─ TimerController.kt       – countdown timer logic + vibration on finish
 ├─ CalculatorController.kt  – 4-function calculator logic
 ├─ BookLibrary.kt           – subject/chapter data model + persistence
 ├─ BookImporter.kt          – scans a picked folder for NCERT chapter PDFs
 ├─ PdfChapterController.kt  – offline PDF page rendering (PdfRenderer)
 ├─ PenCanvasView.kt         – transparent freehand drawing/eraser canvas
 ├─ PenToolbarController.kt  – pen toolbar (color/type/size/eraser/drag)
 └─ Prefs.kt                 – remembers "setup completed" for auto-boot-start
```

## Get an APK automatically from GitHub (no Android Studio needed)

This repo includes a GitHub Actions workflow (`.github/workflows/build.yml`)
that builds the APK for you in the cloud every time you push.

1. Create a new **empty** repository on GitHub (don't initialize it with a
   README/license — this project already has one).
2. Push this project to it:
   ```bash
   cd GeneoOverlay
   git init
   git add .
   git commit -m "Initial commit — Geneo floating toolbox"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo>.git
   git push -u origin main
   ```
3. On GitHub, open the **Actions** tab. A workflow run called **"Build APK"**
   starts automatically — wait for the green checkmark (2–4 minutes).
4. Click into that run → scroll to **Artifacts** → download
   **`GeneoOverlay-debug-apk`**. It's a zip containing `app-debug.apk`,
   ready to install on the smart board.
   - A `GeneoOverlay-release-apk-unsigned` artifact is also produced; it's
     **unsigned**, so install the debug one unless you set up signing (see
     below).
5. Every future push to `main` rebuilds it automatically — just re-download
   the latest artifact from the newest run.

### Getting a downloadable Release instead of an Actions artifact
Artifacts expire after 90 days and require a GitHub login to download. For a
permanent link anyone can grab without logging in, push a version tag:
```bash
git tag v1.0
git push origin v1.0
```
This triggers the same workflow but also attaches the APKs to a proper
**GitHub Release** (visible on the repo's "Releases" sidebar) with a stable
download link.

### Signing the release APK (optional, for production rollout)
The `assembleRelease` build in CI is unsigned, so Android will refuse to
install it until it's signed. Easiest path: install the **debug APK** for
now (it works identically, just isn't optimized/signed) — fine for a
smart-board internal tool. To properly sign release builds later, generate a
keystore, add it as GitHub **Secrets**, and extend `app/build.gradle` with a
`signingConfigs` block referencing those secrets — ask if you'd like this
set up.

## Notes for customizing for Geneo branding
- Colors live in `app/src/main/res/values/colors.xml` (currently a blue/
  purple palette) — swap in Geneo's exact brand hex codes there.
- The launcher/bubble logo is a simple placeholder vector
  (`res/drawable/ic_launcher.xml`, `bg_bubble.xml` + `ic_bubble_grid.xml`) —
  replace with Geneo's actual logo asset for production.
- `applicationId` is `com.geneo.smartboard.overlay` in `app/build.gradle` —
  change to Geneo's real package name before publishing/deploying at scale.
