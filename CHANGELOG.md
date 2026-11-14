# Changelog

## v0.7.2 — 2026-11-14

- Navigation prompts are spoken in a Scottish male voice, built into the app and working offline.
- Settings > Navigation voice: choose from six Scottish voices (tap one to hear it), or the phone's own voice.
- If the built-in voice can't start on a phone, the phone's own voice is used instead.
- The APK is larger (about 80 MB more) because the voice is inside it.
- Voice: CSTR VCTK Corpus, University of Edinburgh (CC BY 4.0), via Piper and sherpa-onnx.

## v0.7.1 — 2026-11-14

- Range tree replaces the range areas around stops. Yellow roads grow out from your location in 24
  directions, each drawn as far as your battery (above the reserve) gets you. It follows your location
  marker and rebuilds after you move 2 km.
- Terrain: heights from Open-Meteo Elevation (free, no key). Climbs cost energy, descents give some back.
- New in Settings: usable battery (kWh), average consumption (kWh/100 km), car weight (kg), and
  "Count battery down as I drive". The main slider is now the battery % you have now.
- Trip range on arrival now starts from your current battery, not a full charge.
- Removed the multi-jump rings (they belonged to the old range areas).

## v0.7.0 — 2026-11-14

- Turn-by-turn navigation: plan a route, then tap Start. A banner shows the next turn with an arrow and the
  distance in m/km; the bar below shows km and time left, arrival time and the next stop.
- Voice guidance with Android's built-in text-to-speech, metric only. Music is turned down while it speaks.
  The speaker button mutes it.
- Automatic rerouting: leave the route and a new one is fetched from where you are to the stops you haven't reached.
- Routes come from Mapbox (live traffic for trips with up to 3 stops) when the build has the
  `MAPBOX_ACCESS_TOKEN` repository secret. Without it, the OSRM test server is still used.
- The screen stays on while navigating.
- Guidance keeps going with the screen off or another app open: voice prompts continue and a notification
  shows the next turn and km left. Tap it to return to the map; its End button stops navigation.

## v0.6.9 — 2026-11-14

- Range circles are now road-accurate range areas. Each stop's green and orange areas (and the
  multi-jump rings) follow real driving distance: they shrink where roads wind and stop at coasts and
  lakes. Uses one OSRM request per stop (free, no key). The slider still sets the full-charge range,
  and the area rescales instantly; a new lookup runs only when the range changes by more than 25%.
- Until the road data loads, or when offline, a stop shows the old straight-line circle.

## v0.6.8 — 2026-11-14

- Removed the Miles option from Settings. All distances are in km only.

## v0.6.7 — 2026-11-14

- Your stops are saved and come back after the app is closed. "My location" is still added from GPS.
- GitHub builds check the NREL key (if the `NREL_API_KEY` secret is set) and stop if NREL rejects it.
- README: steps to get a free NREL key and add it; weather section describes the 3-day badges and 5-day detail.

## v0.6.6 — 2026-10-29

- In-app updates: when a newer version is released, a bar at the bottom offers **Update**.
  Settings also has **Check for updates**. Puddlejumper downloads the new APK and Android installs
  it over the current app, keeping your settings. Android asks once to allow Puddlejumper to install apps.
- Every build is now signed with one fixed key, so new versions install over old ones.
- One-time step: uninstall your current Puddlejumper before installing v0.6.6 (older builds used a
  different key). From v0.6.6 on, updates install directly.

## v0.6.5 — 2026-10-28

- Weather badges now show 3 days in short form (symbol and high/low °C for each day).
- Tapping a badge shows the full 5-day forecast.
- The tick box is now called "Weather".

## v0.6.4 — 2026-10-28

- Weather layer: tick "Weather (3 days)" (lower left). A badge with today's conditions and high/low °C
  appears at the map centre and above every stop. Tap a badge for the 3-day forecast: conditions,
  °C, rain in mm with chance, and maximum wind in km/h. The centre badge follows the map as you pan.
- Forecasts come from Open-Meteo (free, no key needed).

## v0.6.3 — 2026-10-28

- Chargers refresh by themselves: after you tap Chargers once, panning or zooming the map loads
  chargers for the new view (up to 250 km around the centre). Clear turns this off.

## v0.6.2 — 2026-10-26

- The Open Charge Map key is no longer in the source code. GitHub builds read it from the
  `OCM_API_KEY` repository secret. Without it, the app uses NREL charger data.

## v0.6.1 — 2026-10-26

- Open Charge Map key is built into the app, and Open Charge Map is now the default charger source.
- API key fields removed from Settings.
- The charger message says when the shared NREL demo key is in use.
- Release notes state which API keys each build contains. Re-running the build from the Actions tab
  replaces the APK on the current release.

## v0.6 — 2026-10-25

- "Multi-jump view" tick box (lower left): adds dashed rings at 2 and 3 full charges around each stop,
  so you can see how far you get in up to 3 hops when charging to full at each one.

## v0.4.2 — 2026-10-25

- Your current location is added as stop 1 automatically when the app opens (after you allow location access).
- Clear keeps your current location as stop 1.

## v0.4.1 — 2026-10-25

- Search autofill: suggestions appear as you type (after 3 letters), nearest to the map view first.
  Pick one to add it as a stop. Suggestions come from Photon (OpenStreetMap data, no key).

## v0.4 — 2026-10-25

- Tap and hold anywhere on the map to drop a stop, including inside range circles. The phone vibrates,
  and the stop is named by its street address once it is looked up.
- New app icon: a ring gate with nine chevrons and a blue event horizon (adaptive icon, with a themed monochrome version).

## v0.3 — 2026-10-25

- Metric by default: distances in km, starting full-charge range 400 km. Miles still available in Settings.
- Builds read the NREL and Open Charge Map API keys from GitHub repository secrets, when they are set.
- Every push to main publishes the newest debug APK to the `latest` pre-release.
- A new version in this changelog plus `versionName` publishes a full GitHub Release with the APK.

## v0.1.0 — 2026-10-25

- First prototype.
- OpenStreetMap map, your location, search to add stops, long-press to add stops.
- Green full-charge range circle at every stop; orange circle for range left on arrival.
- Driving route through all stops with per-leg distance and range on arrival; warning below reserve.
- "Charge to full at each stop" switch and arrival reserve setting.
- Charger layer from NREL AFDC (US and Canada) or Open Charge Map: speed, kW, ports, connectors,
  network, status (operational, down / maintenance, planned), hours, pricing.
- Turn-by-turn directions list.
