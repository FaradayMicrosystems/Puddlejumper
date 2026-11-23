# Developer setup

## Requirements
- Android Studio (current stable), which includes the Android SDK and a JDK. The project needs **JDK 17**.
- `bash`, `curl`, `sha256sum`, `tar` with bzip2 (for the voice download script).
- About 200 MB free disk for the voice engine and model.
- An Android 8.0+ (API 26) phone or emulator. The voice engine ships only `arm64-v8a` and
  `armeabi-v7a`, so use an ARM emulator image or a real phone to hear the Scottish voice
  (x86 emulators fall back to the phone voice).

## First build
1. Clone: `git clone https://github.com/FaradayMicrosystems/Puddlejumper.git`
2. Download the voice (once): `bash scripts/fetch-voice.sh`
   - Puts `app/libs/sherpa-onnx-1.13.7.aar` and `app/src/main/assets/voice/` in place (both git-ignored).
   - Checks SHA-256. **The app module will not build without this** ("Voice engine missing").
3. Optional keys: create `local.properties` in the repo root (git-ignored):
   ```
   OCM_API_KEY=your_open_charge_map_key
   NREL_API_KEY=your_nrel_key
   MAPBOX_ACCESS_TOKEN=pk.your_public_token
   ```
   Without them: chargers use NREL `DEMO_KEY` (US/Canada only, low limit) and routes use OSRM.
4. Open the folder in Android Studio, let Gradle sync, run the `app` configuration.

## Command line
| Task | Command |
|---|---|
| Unit tests (core) | `./gradlew :core:test` |
| Debug APK | `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |

Local debug builds are signed with your own debug key, so they **cannot install over** a GitHub-built
copy. Uninstall first, or test with the CI APK.

## Cloud (Claude) sessions
- `dl.google.com` is blocked, so the Android SDK and the `app` module cannot be built in cloud sessions.
  Only `core` can be tested there.
- The root build fails there too (Google Maven blocked). Use a scratch `settings.gradle.kts` that includes
  only `:core` (projectDir pointing at `core/`) with the Kotlin JVM and serialization 2.1.0 plugins.
  The first dependency resolve can flake; retry.
- `router.project-osrm.org` and `api.open-meteo.com` are blocked there, so live API calls cannot be tested.
- Rely on GitHub Actions for the real build: every PR runs it.

## Code conventions
- Metric only. No miles, no imperial. Format with `Km.format` / `Guidance` helpers.
- New logic that does not need Android goes in `core/` with a unit test and, for parsers, a JSON fixture
  in `core/src/test/resources/`.
- External services sit behind an interface (`Router`, `ChargerSource`) so they can be swapped.
- Send an identifying User-Agent (`Http.kt`) to OSM-family services.
- Comments and user-facing text: short, plain English.
