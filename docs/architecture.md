# Architecture

## Overview

Two Gradle modules:

| Module | Language | Android? | Tested? | Purpose |
|---|---|---|---|---|
| `core/` | Kotlin (JVM) | No | Yes, `./gradlew :core:test` | All logic that does not need Android: range math, road reach, routing and parsing, navigation engine, chargers, weather, geocoding, update check. |
| `app/` | Kotlin, Android Views | Yes | No unit tests | Map screen, overlays, settings dialog, GPS, voice, foreground service, in-app updater. |

**Why this split:** `core` can be built and unit-tested anywhere (including cloud sessions that cannot
download the Android SDK). Every external service sits behind a small interface in `core`, so a provider
can be swapped (for example OSRM to Mapbox) without touching the screen code.

Stack: Kotlin 2.1.0, AGP 8.7.3, compileSdk/targetSdk 35, minSdk 26 (Android 8.0), JDK 17,
osmdroid 6.1.20 (OpenStreetMap raster tiles), OkHttp 4.12, kotlinx-serialization, coroutines,
sherpa-onnx 1.13.7 (offline voice, local AAR).

## `core/` (package `app.puddlejumper.core`)

| File | Contents |
|---|---|
| `Geo.kt` | `LatLon`, `BoundingBox`, `Geo` (haversine distance, destination point, bearing, circles, thinning). |
| `Range.kt` | `Km` helpers, `RangeSettings` (full range, reserve %, charge at each stop), `RangePlanner.plan()` gives range on arrival per leg. |
| `RoadReach.kt` | `RoadReachSampler` builds one OSRM `table` request (32 bearings x 2 rings = 64 points). `ReachProfile` stores per-bearing road/straight-line ratio and coast caps, and turns them into a polygon for any range. |
| `Routing.kt` | `Router` interface, `OsrmRouter`, `MapboxRouter` (traffic for up to 3 waypoints, max 25), `Route`/`RouteLeg`/`RouteStep`, `NominatimGeocoder` (search, reverse). |
| `Guidance.kt` | Builds spoken prompts and banners for routers that send none (OSRM): prompts at 2 km, 500 m and 60 m. Metric text. |
| `Navigation.kt` | `NavigationEngine`: snaps GPS to the route, finds next manoeuvre, picks prompts, detects off-route (50 m + accuracy, 3 fixes, or 250 m at once), arrival within 30 m. `RerouteGate` limits reroutes to one per 10 s. |
| `Chargers.kt` | `ChargingStation`, `Connector`, `ChargerStatus`, `ChargerSpeed`, `ChargerSource` interface. |
| `OpenChargeMapSource.kt` | Open Charge Map (worldwide). Maps OCM status ids to `ChargerStatus`. |
| `NrelSource.kt` | NREL AFDC (US and Canada). Supports nearby and along-route (corridor) queries. |
| `Photon.kt` | Photon search-as-you-type suggestions. |
| `Weather.kt` | Open-Meteo forecast URL and parser, WMO code to condition, badge/detail formatting. |
| `Updates.kt` | Parses GitHub releases, compares versions, returns the newest `vX.Y.Z` with an APK asset. |

Tests in `core/src/test/` use recorded JSON fixtures (`mapbox.json`, `nrel.json`, `ocm.json`,
`openmeteo.json`, `osrm.json`).

## `app/` (package `app.puddlejumper`)

| File | Contents |
|---|---|
| `MainActivity.kt` | The only screen. osmdroid map, overlays (range areas, rings, route, chargers, weather badges), search box, bottom panels, settings dialog, navigation UI. Renders from `MainViewModel` state. |
| `MainViewModel.kt` | UI state (`StateFlow`): stops, route, leg reports, chargers, reach profiles, forecasts. Fetches road reach (debounced, one request per stop), weather (cached 1 h, ~10 km grid), chargers, routes. Picks `MapboxRouter` when a token is built in, else `OsrmRouter`. |
| `NavSession.kt` | Process-wide navigation state (singleton). Holds `NavigationEngine`, handles reroutes, emits speech and messages. Survives the screen being off. |
| `NavigationService.kt` | Location foreground service. Feeds GPS into `NavSession`, speaks prompts, shows the next-turn notification with an End button. |
| `NavVoice.kt` | Speaks prompts: `NeuralVoice` unless Settings picks the phone voice or the model fails. Ducks music. |
| `NeuralVoice.kt` | sherpa-onnx engine running the Piper VCTK model from assets. Six Scottish male speakers; default `p255`. One background thread. |
| `Prefs.kt` | SharedPreferences: full range (default 400 km), reserve (default 10%), charge at each stop, multi-jump, weather, voice, charger source, saved stops. |
| `Updater.kt` | In-app update: checks GitHub releases, downloads APK, hands it to the installer via `FileProvider`. |
| `SwipeHideCardView.kt` | Bottom card that hides on a downward swipe and returns from a peek tab. |
| `Http.kt` | OkHttp wrapper with an identifying User-Agent (required by OSM, Nominatim and OSRM policies). |
| `Icons.kt` | Map markers drawn at runtime (stops, chargers, weather pills). No image assets. |

## Data flow

1. **Stops**: GPS fix becomes stop 1 ("My location"). User adds stops by long-press, search or a charger.
   Stops (except My location) are saved to `Prefs` on every change.
2. **Range areas**: when stops or the range change, `MainViewModel.refreshReach()` waits for the slider to
   settle, then fetches one OSRM `table` per stop that lacks a fitting `ReachProfile` (within 25% of the
   sampled range). Until it arrives, or when offline, a straight-line circle is drawn.
3. **Route**: `computeRoute()` calls the chosen `Router`, then `RangePlanner.plan()` gives range on
   arrival per leg. The orange inner area at each stop uses that arrival range.
4. **Navigation**: Start hands the route to `NavSession` and starts `NavigationService`. Each GPS fix goes
   through `NavigationEngine.update()`; off-route triggers a reroute from the current position to the
   remaining stops.
5. **Chargers/weather**: loaded for the route or visible map; refreshed after pans/zooms once enabled.

## Build-time configuration

`app/build.gradle.kts` writes these into `BuildConfig` from `local.properties` (CI writes that file from
repository secrets): `NREL_API_KEY` (default `DEMO_KEY`), `OCM_API_KEY`, `MAPBOX_ACCESS_TOKEN`,
`UPDATE_REPO` (`FaradayMicrosystems/Puddlejumper`). There are no key fields in Settings.

The build fails with "Voice engine missing" until `scripts/fetch-voice.sh` has run.
