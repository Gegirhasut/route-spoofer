# Route Spoofer

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

🌐 **Landing page:** <https://gegirhasut.github.io/route-spoofer/>

A standalone Android app that injects a scripted, moving mock GPS location into the whole device, so any other app reads the fake route as real. Useful for testing location-based apps (e.g. driver/navigation apps) without physically moving.

## Features

- Draw a multi-point route on a map (A→B→C…)
- Set speed (km/h) and emit interval
- Play / Pause / Stop controls
- Loop modes: off, restart, ping-pong
- Live telemetry HUD + emit log
- Route saved locally
- Native foreground service keeps emitting while the app is backgrounded

## How it works

A Capacitor web UI handles route control and preview, while a native Kotlin foreground service injects the location into the Android GPS and NETWORK test providers. The app must be selected as the system mock-location app for this to work.

## Build

Two paths are supported — see [BUILD.md](BUILD.md) for full details.

- **Cloud build (GitHub Actions):** push the repo and let the workflow build it, then download the `app-debug.apk` artifact from the run.
- **Local build (Android Studio):** open the project with JDK 21 and the Android SDK installed, then build the debug APK.

Toolchain note: this project targets the Capacitor 7 / JDK 21 toolchain.

## Phone setup

1. Enable **Developer options**: tap **Settings → About phone → Build number** seven times.
2. Allow installing from unknown sources and sideload the APK.
3. Go to **Developer options → Select mock location app → Route Spoofer**.
4. Launch the app and grant **Location** and **Notifications** permissions.
5. Wait for the readiness card, then press **Play**.

## Localization

The UI and the service notification are available in 8 languages:

- English (source / fallback)
- Русский (Russian)
- Deutsch (German)
- Español (Spanish)
- Português (Brazilian Portuguese)
- Français (French)
- 中文 (Simplified Chinese)
- हिन्दी (Hindi)

The language auto-detects from the system locale on first launch and can be changed
anytime from the **Language** selector at the bottom of the control deck (choose
**Auto** to follow the system again). English is the source of truth and the fallback
for any missing string. Chinese and Hindi are machine translations pending native
review; corrections are welcome.

## Platform support

Android only. iOS is not supported, as it has no public mock-location API.

## License

MIT — © 2026 Maxim Popelnitskiy. See [LICENSE](LICENSE).

## Author

Maxim Popelnitskiy
