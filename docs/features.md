# Features (v0.7.4)

All distances in km and m. Temperatures in °C, rain in mm, wind in km/h.

## Map and stops
- OpenStreetMap map (osmdroid). Current location becomes stop 1 automatically.
- Add a stop: long-press the map (vibrates, named by street address via Nominatim), search
  (Photon suggestions after 3 letters, nearest first), or tap a charger > **Add as stop**.
- Stops are saved and restored on next launch. **Clear** removes all except My location.

## Range rings (the core feature)
- **Green area**: where you can drive from each stop on a full charge. Set by the main slider
  (default 400 km).
- **Orange area**: range left on arrival at that stop (after **Route**).
- Areas follow real roads: one OSRM `table` request per stop samples 64 points (32 bearings, rings at
  45% and 80% of range). They shrink where roads wind and stop at coasts and lakes.
- Slider moves rescale instantly; a new lookup runs only when range changes by more than 25%.
- Offline or still loading: a straight-line circle.
- **Multi-jump view** tick box: dashed rings at 2x and 3x full charge (up to 3 hops).
- Settings: arrival reserve % (default 10%). Legs ending below reserve show ⚠.
- **Charge to full at each stop** switch: off means range carries over between stops.

## Route and turn-by-turn navigation
- **Route** plans through all stops: total km, time, range on arrival per leg. **Steps** lists turns.
- The same button then becomes **Start**: banner with next turn arrow and distance; bar with km and
  time left, arrival time, next stop.
- Router: Mapbox Directions (live traffic for up to 3 stops) when the build has `MAPBOX_ACCESS_TOKEN`;
  otherwise the OSRM public demo server. **Current builds have no Mapbox token, so OSRM is used.**
- Automatic rerouting when off route (max once per 10 s).
- Keeps running with the screen off: a foreground service and a notification with the next turn and an
  End button. Screen stays on while the app is in front.

## Navigation voice
- Offline Scottish male voice: Piper `en_GB-vctk-medium` model run by sherpa-onnx, bundled in the APK
  (adds about 80 MB).
- Settings > Navigation voice: six VCTK speakers (p255 Galloway default, p247 Argyll, p285, p272, p252
  Edinburgh, p241 Perth), tap to preview, or the phone's own voice.
- Falls back to Android TTS if the model cannot load. Music is ducked while speaking. Speaker button mutes.
- Licence: CC BY 4.0, credit shown in the app ("CSTR VCTK Corpus, University of Edinburgh").

## Chargers
- **Chargers** button loads stations along the route (within 8 km, NREL) or around the visible map.
  After the first tap, panning or zooming reloads chargers (up to 250 km around the centre).
- Detail: speed class (DC fast / Level 2 / Level 1), kW, ports, connectors, network, status
  (operational, down / maintenance, planned), hours, pricing, last confirmed.
- Markers: purple **F** DC fast, blue **2** Level 2, light blue **1** Level 1, red down/maintenance,
  grey planned.
- Source: Open Charge Map when the build has `OCM_API_KEY` (current builds do), else NREL.
- No live free/occupied status (decision: stay free). Note: the original goal mentioned PlugShare;
  PlugShare needs a paid commercial licence, so Open Charge Map/NREL are used instead.

## Weather layer
- **Weather** tick box: badge above each stop and at map centre with 3 days (symbol, high/low °C).
- Tap a badge for the 5-day forecast: conditions, °C, rain mm and chance, max wind km/h.
- Open-Meteo, no key. Cached 1 hour per ~10 km cell.

## Bottom panel
- Swipe down to hide; tap or swipe up on the tab to bring it back. The navigation trip bar behaves the same.

## In-app updates
- At launch the app checks GitHub releases for a newer `vX.Y.Z` and offers **Update**. Also in
  Settings > **Check for updates**. The "latest" pre-release is ignored.
- Android asks once to allow installs from Puddlejumper. Settings are kept.
- Requires every build to be signed with the same key (see [build-and-release.md](build-and-release.md)).
