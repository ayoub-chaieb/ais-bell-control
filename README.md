# AIS Bell System

[![Build APK](https://github.com/ayoub-chaieb/ais-bell-control/actions/workflows/build.yml/badge.svg)](https://github.com/ayoub-chaieb/ais-bell-control/actions/workflows/build.yml)

A native Android background service that turns any smartboard into a school
bell system — spoken period announcements, on-screen live schedule, and a
centrally-managed timetable that every board picks up on its own, with no
backend server to run or pay for.

Built for Almanhal Schools, Riyadh. Runs on BenQ interactive displays as an
always-on background app rather than a browser tab, so it survives screen
locks, app switching, and reboots — the requirements a browser page can't
meet.

## What it does

- **Spoken bell announcements** via Android text-to-speech at every period
  boundary — start of lesson, breaks, Prayer, dismissal — for whichever
  classroom level the board is set to.
- **Three display modes**, chosen once per board on first launch:
  - **Elementary** — that level's own schedule, on screen and spoken.
  - **Middle & High School** — same, for the older level.
  - **All Levels** — a combined dashboard showing both schedules side by
    side (for an admin/office screen), still speaking both levels' bells.
- **Live on-screen schedule**: clock, current period, progress bar,
  countdown to the next bell, and the full day's table.
- **Centrally managed schedule.** Periods, breaks, and Prayer all live in
  one shared spreadsheet. Every board polls it in the background and
  applies changes automatically — no reinstalling, no touching individual
  boards.
- **Offline-resilient.** Each board caches the last schedule it
  successfully synced and keeps running normally through wifi drops; it
  simply won't see new edits until connectivity returns.
- **Survives reboots** via a boot receiver that restarts the background
  service automatically.

## Architecture

┌─────────────────────┐ polls every 30 min ┌──────────────────────┐
│ Google Sheet │ ───────────────────────────────▶ │ Android app (per │
│ (published as CSV) │ + on open + manual │ board), cached │
│ level, period, │ │ locally, offline-safe │
│ start, end │ └──────────────────────┘
└─────────────────────┘


The schedule "backend" is a Google Sheet published to the web as a CSV
endpoint — a free, always-on static file host with a spreadsheet UI for
non-technical editing. Each board's foreground service fetches it on a
timer, parses it, and caches the result in `SharedPreferences`. If a fetch
fails (no wifi, sheet temporarily unreachable), the board just keeps using
whatever it cached last — there's no hard dependency on connectivity at
the moment a bell is due.

Core components:
- `BellForegroundService` — the always-on service; ticks every second,
  fires TTS + notifications at period boundaries, re-syncs periodically.
- `RemoteConfig` — fetches/parses/caches the published sheet.
- `Schedule` — bundled default timetable, used until the first successful
  sync (and as the fallback shape/format for the remote data).
- `MainActivity` — live on-screen display; renders one or two schedule
  panels depending on the board's level.
- `LevelSelectActivity` / `SettingsActivity` — first-run level picker and
  a small admin screen (re-pick level, force a sync, see last-synced time).
- `BootReceiver` — restarts the service after a reboot.

## DevOps / engineering notes

This project doubled as a small CI/CD exercise: an Android app built and
packaged entirely through GitHub Actions, with no local Android Studio
install required for either development or distribution.

**Pipeline** (`.github/workflows/build.yml`):
- Triggers on every push to `main` (and manually via `workflow_dispatch`).
- Runs on a GitHub-hosted `ubuntu-latest` runner, which already ships an
  Android SDK — the pipeline accepts the SDK licenses and installs the one
  missing build-tools version it needs, rather than provisioning a full
  SDK from scratch. Early iterations used a third-party `setup-android`
  action; it turned out to reference a legacy SDK package Google removed
  from the repository, so the pipeline was simplified to lean on what the
  runner already provides instead — fewer moving parts, one less thing
  that can break upstream.
- Java 17 via `actions/setup-java`, Gradle 8.4 pinned via
  `gradle/actions/setup-gradle` (not the checked-in wrapper, to keep the
  repo lighter).
- `gradle assembleDebug` produces a debug-signed APK, named
  `ais-bell-system-debug.apk` via a custom `applicationVariants` output
  filename in `app/build.gradle` (Gradle's default `app-debug.apk` isn't
  something you want handing out to a client).
- The APK is uploaded as a build artifact (`actions/upload-artifact`),
  downloadable straight from the Actions run — no signing infrastructure
  or release pipeline needed for internal distribution to a handful of
  boards.

**Distribution config as a serverless "backend":** rather than standing up
and paying for a server to push schedule updates to every board, the
config layer is a published Google Sheet acting as a static CSV endpoint,
polled by each device. It's the same pattern as a feature-flag or
remote-config service, minus the infrastructure — appropriate for the
actual scale and budget here (a handful of school boards, no uptime SLA
needed beyond "eventually consistent within 30 minutes").

**Reliability choices for an unattended device:** a foreground service
(not a plain background service, which Android would kill) with its own
notification channel keeps the process alive; a `BOOT_COMPLETED` receiver
restarts it after power cycles or firmware updates; cached remote config
means a connectivity gap degrades gracefully instead of breaking the bell
schedule outright.

## Building it

No local Android Studio needed — the GitHub Actions workflow above builds
it. Push to `main`, open the **Actions** tab, download the
`ais-bell-system-debug-apk` artifact once the run goes green.

To build locally instead: open the project in Android Studio (min SDK 24,
compile/target as set in `app/build.gradle`) and run `Build → Build APK(s)`.

## Setting up the central schedule (one-time)

1. Create a Google Sheet with header row `level,period,start,end`.
2. **File → Import → Upload**, choose `bell-schedule-starter.csv` (included
   in this repo) as a starting point — it already matches the current
   schedule. **Replace current sheet** on import.
3. `level` is exactly `ELEMENTARY` or `MIDDLE_HIGH` (case-insensitive).
   Times are 24-hour `H:MM`, e.g. `6:45` or `13:10`.
4. **File → Share → Publish to web** → select the sheet → format **CSV** →
   **Publish**. Copy the resulting link.
5. Paste that link into `CONFIG_CSV_URL` in
   `app/src/main/java/com/ascendant/bellcontrol/RemoteConfig.kt`, commit
   (one final rebuild). From then on, editing the sheet is the only thing
   anyone needs to do — every board picks it up within 30 minutes, or
   immediately via the in-app "Sync now" button.

## Installing on a board

1. Enable "install from unknown sources" once per board.
2. Sideload the APK (USB, or `adb install` over the network).
3. Open it once — pick the classroom level, accept the notification and
   battery-optimization prompts.
4. Check the board has an English TTS voice installed (Settings →
   Accessibility → Text-to-speech) — some budget boards ship without one.
5. The app doesn't need to stay in the foreground; the bell service keeps
   running regardless of what's on screen.

## Known limitations

- Prayer time is manually set in the sheet, like every other period — it
  does not auto-calculate; adjust it there whenever the actual time
  shifts.
- No launcher icon is bundled (`android:icon` is intentionally omitted so
  CI doesn't fail on a missing resource) — the app installs with Android's
  default icon. Add `mipmap-*/ic_launcher.png` resources and re-add
  `android:icon` to customize.
- Debug-signed only; fine for internal sideloading, not for Play Store
  distribution.
