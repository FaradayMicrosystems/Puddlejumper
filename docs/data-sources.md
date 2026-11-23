# Data sources and keys

| Need | Service | Key | Where the key comes from | Fallback / notes |
|---|---|---|---|---|
| Map tiles | OpenStreetMap tile servers | None | – | Tile usage policy forbids heavy use. Fine for a personal app, not for public scale. |
| Routing | OSRM public demo (`router.project-osrm.org`) | None | – | Default. Testing-grade, no SLA. |
| Routing with traffic | Mapbox Directions API | Public `pk.` token | Secret `MAPBOX_ACCESS_TOKEN` | **Not set** in current builds. CI rejects `sk.` tokens. |
| Road range areas | OSRM `table` | None | – | Max 100 coordinates per request (uses 65). Straight-line circle on failure. |
| Search on Enter, pin names | Nominatim | None | – | Max 1 request/s. Autocomplete is forbidden, hence Photon. |
| Search suggestions | Photon (komoot) | None | – | – |
| Chargers (default) | Open Charge Map | Free key | Secret `OCM_API_KEY` | **Set.** CI checks it against the API before building. |
| Chargers (fallback) | NREL AFDC (US/Canada only) | Free key | Secret `NREL_API_KEY` | **Not set**; uses shared `DEMO_KEY` (low hourly limit). |
| Weather | Open-Meteo | None | – | Free for non-commercial use. |
| Voice model and engine | GitHub releases of k2-fsa/sherpa-onnx | None | – | Downloaded by `scripts/fetch-voice.sh`, pinned by SHA-256. |
| Updates | GitHub Releases API | None | – | Unauthenticated; 60 requests/h per IP. |

## Rules for keys
- Never commit a key. Keys go in `local.properties` (git-ignored) or repository secrets.
- Only free/public keys may be built in: anything in an APK can be extracted.
- An old Open Charge Map key (`41b2…`) is in public git history from v0.6.1. It was meant to be rotated;
  confirm the current secret is a different key.

## Not used, and why
- **PlugShare**: API is commercial-licence only; no personal licence exists.
- **TomTom / Google Places / HERE live availability**: paid or quota-limited. Owner chose to stay free
  (2026-11-14). See [decisions.md](decisions.md).
- **Isochrone APIs** (Mapbox, Valhalla): cap at 100 km and 200 km, too short for EV range.
