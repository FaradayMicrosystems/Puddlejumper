# Google Play publishing plan

Status (2026-11-23): **planned, not started.** Owner asked for the guide on 2026-11-17; changes wait for
the owner's go-ahead. Step-by-step guide: https://claude.ai/artifact/Pkhb287y7H6WxJU5N7VcFh

## Code changes needed before the first upload
1. **Target API 36**: set `compileSdk` and `targetSdk` to 36. Upgrade AGP from 8.7.3 to a version that
   supports API 36 (and the Gradle wrapper with it). Required for new apps and updates since 2026-08-31.
2. **Signed release bundle (`.aab`)**: configure the `release` build type with the existing `puddlejumper`
   key as the **upload key**; use Play App Signing with a Google-generated app signing key. Add a CI step
   for `bundleRelease`.
3. **Play product flavor without self-updates**: Play forbids apps updating themselves outside Play.
   The Play flavor must drop `Updater.kt`, the update UI, the `FileProvider`, and
   `REQUEST_INSTALL_PACKAGES`. Keep the GitHub sideload flavor as it is.
4. **versionCode** must increase on every Play upload (share the counter with GitHub releases).
5. **Privacy policy page** (public URL). The app uses precise location, including with the screen off
   (location foreground service; no background-location permission) and sends coordinates to OSRM/Mapbox, Nominatim, Photon, Open-Meteo, chargers APIs.
6. **Store assets**: 512 × 512 icon, 1024 × 500 feature graphic, phone screenshots, short and full description.
7. **Declarations in Play Console**: foreground service type `location` (with a short video of navigation),
   data safety form, content rating, target audience.

## Testing tracks
- **Internal testing**: up to 100 testers, no minimum test period. Fastest route for the owner and friends.
- **Closed testing → production** for a new personal developer account: at least 12 testers opted in for
  14 days before production access can be requested.

## Before a public (production) release
- Replace public demo services with production ones (see [known-issues.md](known-issues.md)).
- Resolve the icon/name trademark risk.
- Add licence credits (OSM, Open Charge Map, Open-Meteo, VCTK) to an About screen.
