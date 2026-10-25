# Changelog

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
