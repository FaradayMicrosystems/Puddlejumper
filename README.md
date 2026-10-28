# Puddlejumper

Android navigation prototype for electric vehicles. Every stop on the map shows a circle for your
car's full-charge range, so you can see at a glance how far you can get from each hop.

## What works in this first version

- OpenStreetMap map with pinch zoom. Your current location becomes stop 1 automatically.
- Add stops by tapping and holding anywhere on the map (the pin is named by its street address), searching a place, or tapping a charger and choosing **Add as stop**.
- **Range circles** at every stop:
  - Green circle: the distance you can drive from that stop on a full charge (set with the slider).
  - Orange circle: range left when you arrive at that stop (shown after you tap **Route**).
- **Multi-jump view** tick box: dashed rings at 2 and 3 full charges around each stop (up to 3 hops).
- **Route**: driving route through all stops, total distance and time, and range on arrival per leg.
  Legs that drop below your reserve are marked with ⚠.
- **Charge to full at each stop** switch. Off means range carries over from stop to stop.
- **Chargers**: loads charging stations along the route (or around the visible map). Tap one for
  speed (DC fast / Level 2 / Level 1, kW where known), ports, connectors, network, status
  (operational, down / maintenance, planned), hours, pricing, and last-confirmed date.
- **Steps**: turn-by-turn directions as a list.
- Settings: km (default) or miles, arrival reserve %, charger data source.

Marker legend: purple **F** = DC fast, blue **2** = Level 2, light blue **1** = Level 1,
red = down or under maintenance, gray = planned, orange = in use (live sources only).

## Data sources

| Need | Prototype uses | Notes |
|---|---|---|
| Map tiles | OpenStreetMap | No key. Fine for testing, not for a published app. |
| Routing | OSRM public demo server | No key. Testing only. |
| Search suggestions | Photon (OSM) | No key. |
| Weather (5-day forecast) | Open-Meteo | No key. Metric (°C, mm, km/h). |
| Search on Enter, pin names | Nominatim (OSM) | No key. Max 1 request per second. |
| Chargers | NREL AFDC (US and Canada) | Works with `DEMO_KEY` (low hourly limit). Free key at developer.nrel.gov. |
| Chargers (default when keyed) | Open Charge Map (worldwide) | Key from the `OCM_API_KEY` secret, never in the code. |

**PlugShare:** its API needs a paid commercial license; there is no personal license.
NREL and Open Charge Map do not report live plug availability (free or occupied). For live
availability, add PlugShare (licensed) or TomTom EV Charging Stations Availability as another
`ChargerSource` in `core/`.

## Build and run

1. Install Android Studio (it includes the Android SDK and JDK 17+).
2. Open this folder in Android Studio and let Gradle sync.
3. Optional: add keys to `local.properties` (this file is not committed):
   ```
   NREL_API_KEY=your_nrel_key
   OCM_API_KEY=your_open_charge_map_key
   ```
   For the GitHub-built APK, add them as repository secrets named `NREL_API_KEY` and `OCM_API_KEY`
   (Settings > Secrets and variables > Actions). Keys built into an APK can be extracted from it,
   so use only free keys here.
4. Run the `app` configuration on a phone or emulator (Android 8.0 / API 26 or newer).

Command line: `./gradlew :app:assembleDebug` (APK in `app/build/outputs/apk/debug/`),
and `./gradlew :core:test` for the unit tests.

## Downloads and releases

- Newest build of main: https://github.com/FaradayMicrosystems/Puddlejumper/releases/download/latest/puddlejumper-debug.apk
- Versioned releases: raise `versionName` in `app/build.gradle.kts`, add a `## vX.Y` section to
  `CHANGELOG.md`, and push to main. The build publishes "Puddlejumper vX.Y" with the APK.

## Project layout

- `core/` — plain Kotlin, no Android: range math, geodesic circles, route and charger parsing. Unit-tested.
- `app/` — Android app: map screen, overlays, settings.

## Next steps

- Real turn-by-turn guidance (voice, rerouting, traffic): move the map and routing to the
  Mapbox Navigation SDK v3. Range circles and charger markers keep working through `core/`.
- Live charger availability: PlugShare license or TomTom.
- Range circles are straight-line distance. A road-network "reachable area" (isochrone) is more accurate.
