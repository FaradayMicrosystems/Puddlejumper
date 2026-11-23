# Puddlejumper handover

Date: 2026-11-23. Owner: Aurora (GitHub `FaradayMicrosystems`).
Repo: https://github.com/FaradayMicrosystems/Puddlejumper (branch `main`).
Full documentation: [`docs/`](docs/README.md).

## 1. What it is
Android navigation app for electric vehicles. Every stop on a route shows how far the car can drive from
it on a full charge (green) and with the range left on arrival (orange), measured along real roads.
Personal app for the owner and friends. **Metric only.**

## 2. Current state
- `main` = **v0.7.4** (versionCode 21), released 2026-11-18.
- Install: https://github.com/FaradayMicrosystems/Puddlejumper/releases/download/latest/puddlejumper-debug.apk
- No open pull requests or issues.
- Works today: range rings per stop (road-accurate), multi-jump rings, route with range on arrival,
  turn-by-turn with rerouting and screen-off guidance, offline Scottish voice, Open Charge Map chargers,
  weather layer, saved stops, swipe-to-hide panel, in-app updates.
- Details: [docs/features.md](docs/features.md).

## 3. How it is built (30-second version)
- `core/`: pure Kotlin logic, unit-tested. `app/`: Android Views + osmdroid map.
- Free services: OSM tiles, OSRM routing and range sampling, Nominatim, Photon, Open-Meteo, Open Charge Map.
- Optional Mapbox routes with traffic (token not set yet).
- GitHub Actions builds every PR and publishes a release on every merge to `main`.
- Details: [docs/architecture.md](docs/architecture.md), [docs/data-sources.md](docs/data-sources.md).

## 4. How to ship a change
1. Branch from latest `main`.
2. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`. **Next: 0.7.5 / code 22.**
3. Add `## v0.7.5 — YYYY-MM-DD` to the top of `CHANGELOG.md`.
4. Open a PR; wait for CI green.
5. **Merge it yourself** (merge commit). Do not ask the owner to merge.
6. CI publishes "Puddlejumper v0.7.5"; phones get the Update bar. Send the owner the APK link.
- Details: [docs/build-and-release.md](docs/build-and-release.md).

## 5. Rules from the owner (do not break)
1. Metric units only. Never imperial.
2. Give an ETA before starting work. Keep instructions brief and explicit; say why.
3. Merge your own PRs once CI is green.
4. Never commit API keys. Keys live in repository secrets only.
5. Stay free: no paid charger/live-availability data unless the owner asks.
6. Range display = lightweight range rings. Do not bring back the range tree or road tinting.
7. Never change the signing key or its alias `puddlejumper`; keep release asset names
   (`puddlejumper-vX.Y.Z.apk`) so the updater finds them.
- Full log with reasons: [docs/decisions.md](docs/decisions.md).

## 6. Open items
| # | Item | Owner | Blocking? |
|---|---|---|---|
| 1 | Google Play changes (API 36, signed `.aab`, Play flavor without updater, privacy policy, store assets). Plan ready; waiting for owner's go-ahead. | Owner to approve, then dev | Blocks Play upload |
| 2 | Add `MAPBOX_ACCESS_TOKEN` secret for live-traffic routes. | Owner | No |
| 3 | Confirm the old OCM key from v0.6.1 is revoked. | Owner | No |
| 4 | Confirm an offline backup of the signing keystore and password exists. | Owner | No, but high risk if lost |
| 5 | Replace public demo servers (OSRM, OSM tiles, Nominatim) before any public release. | Dev | Only for public release |
| 6 | Check icon/name trademark risk (Stargate-like ring gate). | Owner | Only for public release |
- Details: [docs/known-issues.md](docs/known-issues.md), [docs/play-store.md](docs/play-store.md).

## 7. Access and secrets checklist
| Thing | Where | Status |
|---|---|---|
| GitHub repo | FaradayMicrosystems/Puddlejumper | Owner |
| `OCM_API_KEY` | Repo secret | Set |
| `PUDDLEJUMPER_KEYSTORE_B64`, `PUDDLEJUMPER_KEY_PASSWORD` | Repo secrets | Set |
| `MAPBOX_ACCESS_TOKEN` | Repo secret | Not set (optional) |
| `NREL_API_KEY` | Repo secret | Not set (optional) |
| Google Play Console account | Owner | Not yet used |

## 8. Gotchas
- The app module needs `bash scripts/fetch-voice.sh` once before it builds.
- Cloud (Claude) sessions cannot download the Android SDK: only `core` tests run there; trust CI for the APK.
- Local debug builds use a different key and cannot install over the GitHub build.
- Merging any change to `main` (even docs) rebuilds and replaces the current version's APK. Harmless.
- `README.md` at the repo root is the user-facing overview; `docs/` is the source of truth for developers.
