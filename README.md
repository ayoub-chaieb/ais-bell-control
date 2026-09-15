# Bell Control — Android background app

Native port of `bell-control-elementary.html` / `bell-control-middlehigh.html`,
running as an always-on foreground service with a real on-screen schedule.

## What's new in this version
- **First launch asks which classroom level the board is in** (Elementary,
  or Middle & High — they're treated as one level, same as the HTML files).
  The choice is saved on-device; change it any time from the in-app
  **Settings** link.
- **The visible screen now matches the HTML** — live clock, current period,
  progress bar, countdown to the next bell, and the full schedule table for
  whichever level this board was set to.
- **Dhuhr (prayer) time is calculated, not hardcoded**, using a fully
  offline astronomical formula (`PrayerTimes.kt`) for Riyadh — no API, no
  cost, no network call. It recalculates itself every day, so it silently
  tracks the real seasonal drift of solar noon.
  - Elementary and Middle & High resolve their Dhuhr time completely
    independently — they are never forced to pray together.
  - **Reflow rule:** Prayer never starts before Dhuhr has actually occurred.
    On the overwhelming majority of days that lands exactly on the school's
    normal fixed slot (12:00 Elementary / 12:55 Middle & High, same as the
    HTML). On the rare days real Dhuhr drifts later than that slot, Prayer —
    and every period after it — is pushed back by the same amount, so a
    lesson is never interrupted mid-period and Prayer is never held early.
  - **Manual override per level**, in Settings, for whenever staff want to
    pin Dhuhr to a specific time themselves instead of trusting the
    calculation.
- **No 2am auto-shutdown.** BenQ boards already have a built-in scheduled
  power-off in system settings — check `Settings → Power → Schedule` (menu
  path varies by BenQ model/firmware) on the board itself. That's more
  reliable than anything a regular Android app can do, since a third-party
  app can't power off hardware without root or Device Owner/MDM enrollment.

## Building it with zero local installs (GitHub Actions)
Everything below happens in a browser — no Android Studio, no SDK, nothing
on your machine.

1. Create a GitHub account if you don't have one (free).
2. **New repository** (e.g. `bell-control-app`) — public or private, either
   works.
3. On the repo page: **Add file → Upload files**, then drag in everything
   from this folder — `build.gradle`, `settings.gradle`,
   `gradle.properties`, the whole `app/` folder, and the `.github/` folder
   (most browsers support dragging folders straight into GitHub's uploader;
   if yours doesn't, upload file-by-file, keeping the same paths). Commit.
4. Go to the **Actions** tab. The `Build APK` workflow starts automatically
   on that push (or click **Run workflow** if it doesn't).
5. Wait ~3–5 minutes for the green checkmark, open that run, and download
   the **BellControl-debug-apk** artifact at the bottom — it's a zip
   containing `app-debug.apk`.
6. Unzip and sideload that APK on the smartboards exactly as in the rollout
   steps below.

This produces a **debug-signed** APK — perfectly fine for sideloading
internally, just not for the Play Store. If you later want it MDM-pushed
fleet-wide with a proper release signature, the workflow can be extended
with a keystore stored in GitHub Secrets — say the word and I'll add that.

## Why not just wrap the HTML in a WebView / PWA?
Faster to set up, but Android suspends background tabs and kills their audio
focus the moment the screen locks or another app comes forward — exactly the
failure mode you're trying to avoid. This project ports the same logic
natively so it keeps running, speaking, and notifying regardless of what's
on screen.

## Build steps (Android Studio)
1. **File → New → New Project → Empty Views Activity**, Kotlin, package
   `com.ascendant.bellcontrol`, min SDK 24. This gives you a working
   gradle wrapper + default launcher icons — much less fiddly than hand-rolling
   the wrapper.
2. Delete the generated `MainActivity.kt`, `activity_main.xml`,
   `strings.xml`, `themes.xml`.
3. Copy everything from this project into the new one:
   - `app/src/main/java/com/ascendant/bellcontrol/*.kt` (Schedule, Prefs,
     PrayerTimes, MainActivity, LevelSelectActivity, SettingsActivity,
     BellForegroundService, BootReceiver)
   - `app/src/main/res/layout/*.xml` (activity_main, activity_level_select,
     activity_settings)
   - `app/src/main/res/values/strings.xml`, `themes.xml`
   - Merge `AndroidManifest.xml` (keep the generated `<application>` icon/theme
     attributes if you like your wizard-generated launcher icon; add the
     `<service>`, `<receiver>`, and `<uses-permission>` entries from this one).
   - Merge `app/build.gradle` dependencies into the generated one.
4. **Build → Generate Signed Bundle / APK → APK** (use a real keystore, even
   a self-signed one — don't ship debug-signed to a fleet of boards).

## Rolling it out to all the smartboards
- **Fastest for a handful of boards:** enable "Install from unknown sources"
  once per board, copy the APK via USB stick, tap to install.
- **Faster for many boards on the same network:** `adb connect <board-ip>`
  then `adb install BellControl.apk` per board (most smartboard Android
  builds have ADB-over-network available in developer settings).
- **If your fleet has an MDM** (many smartboard brands — Promethean, BenQ,
  ViewSonic — support one): push the APK as a "required app" so it survives
  factory resets and reinstalls automatically.

## After install, on each board
- Open the app once — it'll ask which classroom level this board is (pick
  once, it's remembered).
- This also triggers the notification permission prompt (Android 13+) and
  the "ignore battery optimizations" prompt. Accept both.
- Check the board actually has a TTS engine with an English voice installed
  (Settings → Accessibility → Text-to-speech). Budget boards sometimes ship
  without one — sideload Google's TTS APK if `tts.speak()` stays silent.
- You do **not** need to keep the app in the foreground — it's a background
  service now, not a browser tab. The on-screen schedule is there for staff
  to glance at, not a requirement for the bells/announcements to work.
- If Dhuhr ever looks wrong for a specific board, open Settings on that
  board and set it manually — no reinstall needed.

## Note on the launcher icon
The manifest deliberately doesn't reference `@mipmap/ic_launcher` — there's
no icon resource in this project, and referencing a missing one would fail
the CI build. The app installs fine with Android's default icon. If you
want a custom one, add `app/src/main/res/mipmap-*/ic_launcher.png` files
and re-add `android:icon="@mipmap/ic_launcher"` to the `<application>` tag
in the manifest.

## What's intentionally different from the HTML version
- No manual "enable audio" click needed — TTS doesn't have the browser's
  autoplay-lock problem.
- No "chime toggle" or two-bell/repeat-announcement sequence — add back
  easily inside `announce()` in `BellForegroundService.kt` (play a tone via
  `SoundPool` before `tts.speak()`), same idea as the JS `playBell()`.
- Persistent notification shows "Next bell in N min" so staff can glance at
  it without opening the app.
- Prayer's clock time is calculated per day instead of fixed in the source —
  see `Schedule.kt` and `PrayerTimes.kt`.
