# Route Spoofer — Build & Setup

Route Spoofer is a standalone Android app that injects a scripted, **moving mock
GPS location** into the whole device. You drop waypoints on a map, set a speed,
emit interval and loop mode, hit Play — and every *other* app on the phone
(Google Maps, a driver app, anything reading device location) sees the phone
"driving" your route.

The playback clock and the OS mock-location injection live in a native Kotlin
**foreground service** (`MockLocationService`) so injection keeps running while
the app is in the background. The web UI is preview + control only.

There are two ways to get an installable APK. **You do not need a local Android
SDK for the cloud path.**

---

## 1. Cloud build (recommended — zero local Android SDK)

A GitHub Actions workflow (`.github/workflows/android.yml`) builds a
debug-signed APK on every push and on manual dispatch.

1. Create a new **empty** GitHub repository (e.g. `route-spoofer`).
2. Add it as a remote and push (the project is already a local git repo):

   ```bash
   git remote add origin git@github.com:<you>/route-spoofer.git
   git branch -M main
   git push -u origin main
   ```

3. Open the repo's **Actions** tab → the **Android Debug APK** run.
   (If it didn't auto-trigger, use **Run workflow** / `workflow_dispatch`.)
4. When it's green, open the run → **Artifacts** → download
   **`route-spoofer-debug`**. Inside is **`app-debug.apk`**.
5. Sideload it onto your phone (see **Phone setup** below).

The GitHub-hosted `ubuntu-latest` runner already ships the Android SDK, so the
workflow produces a downloadable, installable APK with nothing installed locally.

---

## 2. Local build (Android Studio or Gradle CLI)

Requires the Android SDK. Node 20+ is also needed for the Capacitor sync step.

```bash
npm ci                       # install Capacitor + deps
npx cap sync android         # copy web assets + plugins into android/
```

**Android Studio:** `npx cap open android`, let Gradle sync, then
**Run** on a USB-connected device, or **Build → Build APK(s)**.

**Gradle CLI:**

```bash
cd android
./gradlew assembleDebug
# → android/app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected device with `adb install -r app-debug.apk`.

> Toolchain note: this project pins **Capacitor 7** (supports Node 20, requires
> **JDK 21**). The CI workflow uses Node 20 + Temurin JDK 21 to match. Use JDK 21
> for local Gradle builds too (Android Studio Koala+ bundles a compatible JDK).

---

## 3. One-time phone setup

To let the app actually drive device location you must select it as the system
mock-location app and grant runtime permissions.

1. **Enable Developer options:** Settings → About phone → tap **Build number**
   7 times.
2. **Sideload the APK:** copy `app-debug.apk` to the phone and open it. You may
   need to allow **Install unknown apps** for your file manager / browser.
3. **Select the mock app:** Settings → **Developer options** →
   **Select mock location app** → **Route Spoofer**.
4. **Launch Route Spoofer** and grant the prompts:
   - **Location** (Allow / While using the app)
   - **Notifications** (Android 13+) — required for the foreground service.

The app's in-app **"Enable mock GPS"** card checks both of these and gives you
shortcut buttons (**Grant permissions**, **Open Developer options**, **Re-check**).
When both rows show ✓, tap the big play button.

### Using it
- Tap the map to drop waypoints A → B → C…
- **Lock route** to guard against accidental edits — while locked, map taps and
  point drags are ignored so a stray tap can't add or move points. Tap again to
  unlock. (Routes start unlocked; locking is never automatic.)
- Set **Speed** (slider/presets), **Emit every** (interval), and **Loop**
  (Off / Restart / Ping-pong).
- Press **Play**. Open Google Maps (or any consumer app) — it now follows the
  fake route. The persistent notification shows the mock GPS is active.
- **Stop** removes the test providers and resets to the start.

---

## 4. Customisation

### Map provider (single constant)
In `www/index.html`, near the top of the script, change these to switch
basemaps — it's a one-line edit:

```js
const MAP_TILE_URL   = 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png';
const MAP_TILE_OPTS  = { maxZoom:20, subdomains:'abcd' };
const MAP_ATTRIBUTION = '&copy; OpenStreetMap contributors &copy; CARTO';
```

The default is the free, key-less OSM/CARTO dark basemap. After editing,
re-run `npx cap sync android` (or rebuild) so the change reaches the app.

### App id / name
Rename in **two** places, then `npx cap sync`:
- `capacitor.config.json` → `appId` (`com.routespoofer.app`) and `appName`
  (`Route Spoofer`).
- `android/app/build.gradle` → `namespace` + `applicationId`, and
  `android/app/src/main/res/values/strings.xml` (`app_name`,
  `title_activity_main`, `package_name`, `custom_url_scheme`).

If you change the Java/Kotlin package, also move the files under
`android/app/src/main/java/<package path>/` and update their `package`
declarations + the `<service>`/activity names in `AndroidManifest.xml`.

---

## Project layout

```
fake-gps/
├─ www/index.html                # web UI (preview + control), single map constant
├─ capacitor.config.json         # appId / appName / webDir
├─ package.json                  # Capacitor 7 deps
├─ android/                      # native project (generated by cap, customised)
│  └─ app/src/main/
│     ├─ AndroidManifest.xml     # perms + <service> (foregroundServiceType=location)
│     └─ java/com/routespoofer/app/
│        ├─ MainActivity.java         # registers the FakeGps plugin
│        ├─ FakeGpsPlugin.kt          # Capacitor bridge (start/pause/.../ensureReady)
│        └─ MockLocationService.kt    # playback clock + GPS/NETWORK test-provider injection
├─ .github/workflows/android.yml # cloud APK build
└─ html-example/fake-gps.html    # original UX reference
```
