# Changelog

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
