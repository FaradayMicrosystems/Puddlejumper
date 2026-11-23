# Decisions log

Each entry: what, when, why. Do not reopen these without the owner (Aurora) asking.

| Date | Decision | Why |
|---|---|---|
| 2026-10-25 | **Metric only** (km, m, kW, kWh, °C, mm, km/h). Miles option removed in v0.6.8. | Owner requirement; imperial defaults caused frustration. |
| 2026-10-25 | **Keyless prototype stack**: Kotlin + Views, osmdroid/OSM tiles, OSRM, Nominatim, free charger data. | Owner wanted something working without sign-ups; sign-ups need owner approval. |
| 2026-10-25 | **No PlugShare.** Chargers from Open Charge Map (default) and NREL. | PlugShare API is commercial-licence only. |
| 2026-10-25 | Builds and releases happen on **GitHub Actions**; owner installs from a release link. | Owner wants direct APK links, no local tooling. |
| 2026-10-25 | Photon for search suggestions. | Nominatim forbids autocomplete. |
| 2026-10-26 | **Keys only from repository secrets**, never in code; no key fields in Settings. | An OCM key was committed in v0.6.1 and is in public history. |
| 2026-10-26 | Personal app for the owner and friends; **no backend or key proxy**. | Scope. |
| 2026-10-28 | Weather from Open-Meteo: 3-day badges, 5-day detail. | Free, no key, metric. |
| 2026-10-29 | **In-app updates from GitHub releases + one fixed signing key** (modelled on the owner's Pinnr app). | Updates install over the old app and keep settings. |
| 2026-11-14 | Road-accurate range areas via OSRM `table` sampling (64 points per stop). | Isochrone APIs stop at 100–200 km, too short for EV range. |
| 2026-11-14 | Turn-by-turn: Mapbox Directions (if token) + own `NavigationEngine` in `core` + keep osmdroid. Not the Mapbox Navigation SDK. | Keeps the app keyless by default; logic stays testable. |
| 2026-11-14 | **Stay free: no live charger free/occupied data** (no TomTom, Google, HERE). | Owner's choice; those are paid or quota-limited. |
| 2026-11-14 | **Scottish male voice**, "not too deep": Piper VCTK via sherpa-onnx, offline, default speaker p255 (Galloway). | Android TTS has no Scottish voice. CC BY 4.0, credit shown. |
| 2026-11-14 | Stacked PRs got sequential versions (v0.6.7–v0.7.0). New work takes the next unused version. | Parallel PRs bumping the same version conflicted. |
| 2026-11-17 | **Range rings, not the range tree.** v0.7.1's terrain-aware "range tree" reverted in v0.7.3. No yellow road tinting. | Range tree was too slow; owner wants it very lightweight. Do not propose route-tree or road-mask approaches again. |
| 2026-11-18 | **Claude merges its own PRs once CI is green.** Do not ask the owner to merge. | Owner stated this firmly. |
