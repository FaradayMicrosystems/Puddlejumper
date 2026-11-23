# Known issues, risks and open work

## Waiting on the owner
| Item | Status | Next step |
|---|---|---|
| Google Play publishing changes | Plan and guide written 2026-11-17 (in the "Puddlejumper v0.3" project thread). Waiting for the owner's go-ahead. | On "go": do the changes in [play-store.md](play-store.md). |
| `MAPBOX_ACCESS_TOKEN` secret | Not set (v0.7.4 notes). Routes use OSRM, no live traffic. | Owner creates a free Mapbox account and adds a public `pk.` token. Optional. |
| `NREL_API_KEY` secret | Not set. Only matters if the charger source is switched to NREL. | Optional. |
| Old OCM key in git history (`41b2…`, v0.6.1) | Owner was told to rotate it. Not confirmed. | Confirm the key in `OCM_API_KEY` is a new one, and the old one is revoked on openchargemap.org. |
| Signing key backup | Unknown whether an offline backup exists. | Owner confirms a backup of the keystore and password. |

## Limitations (by design or not yet done)
- **Range model is simple**: full range in km × reserve %. No battery %, elevation, temperature, speed or
  load. (Terrain-aware energy existed in v0.7.1 and was removed for speed.)
- **Road range areas are estimates**: 64 samples per stop, interpolated between bearings. Caps at coasts
  only apply near the sampled range (not to 2× / 3× multi-jump rings).
- **No live charger occupancy**; status is operational / down / planned only (owner decision).
- **Public free servers**: OSRM demo, OSM tiles, Nominatim and Photon have no SLA and usage policies that
  do not allow public-scale traffic. Fine for owner and friends; must change for a public Play release
  (own OSRM/Valhalla server, or Mapbox; a tile provider such as OpenFreeMap or MapTiler).
- **Mapbox traffic** only for routes with up to 3 waypoints; longer trips use the normal driving profile.
  Max 25 waypoints.
- **Updater** calls the GitHub API unauthenticated (60 requests/hour per IP). Fine at this scale.
- **APK size**: about 80 MB extra for the offline voice. Only ARM CPUs get the voice.
- **No app-module tests**; only `core` is unit-tested. No UI/instrumentation tests.
- **`release` build type is not configured for signing**; CI ships the signed *debug* build. Play needs a
  signed release `.aab`.
- `targetSdk` is 35; Google Play now requires 36 for new apps and updates.
- `README.md` "Next steps" predates turn-by-turn; see this folder for current state.

## Risks
- **Trademark**: the launcher icon (ring gate with nine chevrons) and naming may be read as Stargate
  imagery. Matters for a public Play listing.
- **Licences to credit in a public release**: OSM data (ODbL), VCTK voice (CC BY 4.0, already credited
  in-app), Open Charge Map and Open-Meteo (check each one's current attribution terms; Open-Meteo's free
  tier is non-commercial only).
- **Signing key loss** forces every user to uninstall once.

## Ideas raised but not started
- Range area painted on the map's own roads: needs MapLibre + vector tiles (OpenFreeMap). Owner rejected
  road tinting on 2026-11-17, so do not start without asking.
- Live charger availability via TomTom or Google: declined 2026-11-14.
- Foreground service exists; Android Auto / CarPlay-style projection does not.
