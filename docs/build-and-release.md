# Build, CI and releases

## Workflow: `.github/workflows/android.yml` ("Android build")

Runs on: every push to `main`, every pull request, and manually (Actions > Android build > Run workflow).

Steps, in order:
1. Write `local.properties` from secrets `NREL_API_KEY`, `OCM_API_KEY`, `MAPBOX_ACCESS_TOKEN` (each optional).
2. **Check Open Charge Map key**: fails the build if set but rejected.
3. **Load signing key** from `PUDDLEJUMPER_KEYSTORE_B64` + `PUDDLEJUMPER_KEY_PASSWORD`. Fails if the
   key and password do not match. If unset, warns and signs with a random key.
4. **Check NREL key** and **Check Mapbox token** (must start with `pk.`): fail if set but rejected.
5. **Fetch navigation voice**: `scripts/fetch-voice.sh`.
6. `./gradlew :core:test`
7. `./gradlew :app:assembleDebug`, uploaded as artifact `puddlejumper-debug-apk`.
8. Only on `main` (push or manual):
   - **Publish latest APK**: deletes and recreates the `latest` pre-release with `puddlejumper-debug.apk`.
   - **Publish versioned release**: reads `versionName`. If tag `vX.Y.Z` does not exist, creates release
     "Puddlejumper vX.Y.Z" with `puddlejumper-vX.Y.Z.apk` and notes from `CHANGELOG.md`. If it exists,
     replaces its APK and notes (a rebuild).

Release notes end with which keys are built in and which signing key was used.

## Download links
- Newest main build: https://github.com/FaradayMicrosystems/Puddlejumper/releases/download/latest/puddlejumper-debug.apk
- Versioned: `https://github.com/FaradayMicrosystems/Puddlejumper/releases/download/vX.Y.Z/puddlejumper-vX.Y.Z.apk`

## Repository secrets (Settings > Secrets and variables > Actions)

| Secret | Status (v0.7.4 notes) | Purpose |
|---|---|---|
| `OCM_API_KEY` | Set | Open Charge Map chargers (default source). |
| `NREL_API_KEY` | Not set | NREL chargers; `DEMO_KEY` used otherwise. |
| `MAPBOX_ACCESS_TOKEN` | Not set | Mapbox routes with traffic; OSRM used otherwise. Public `pk.` token only. |
| `PUDDLEJUMPER_KEYSTORE_B64` | Set | Base64 PKCS12 keystore, alias `puddlejumper`, EC P-256 (~1388 chars, starts `MIIE`). |
| `PUDDLEJUMPER_KEY_PASSWORD` | Set | Store and key password. |

**The signing key exists only in the secret and with the owner.** It is not stored in the repo or project
files. Keep an offline backup. If it is lost, every installed phone must uninstall once to move to a new key,
and it would also be the planned Play upload key.

## How to release a new version
1. Branch from the latest `main`.
2. Bump in `app/build.gradle.kts`: `versionCode` +1 and `versionName` (next unused; **next is 0.7.5, code 22**).
   Check open PRs first so two PRs do not take the same number.
3. Add `## vX.Y.Z — YYYY-MM-DD` at the top of `CHANGELOG.md` with user-facing bullets. The heading must
   start with `## vX.Y.Z` exactly; the workflow extracts notes by it.
4. Open a PR. CI must be green.
5. Merge (merge commit). The push to `main` publishes the release and updates `latest`.
6. Phones on v0.6.6+ see the **Update** bar on next launch.

Owner picks version numbers; v0.5 was skipped on purpose.

A docs-only merge (no version bump) rebuilds and replaces the current version's APK. This is harmless.

## Rebuild without a commit
Actions > Android build > Run workflow on `main` (for example, after adding a secret). It replaces the
current version's APK.

## Version history
See [`../CHANGELOG.md`](../CHANGELOG.md). Releases: v0.1.0, v0.3, v0.4, v0.4.1, v0.4.2, v0.6, v0.6.1–v0.6.9,
v0.7.0–v0.7.4. v0.6.6 needed a one-time uninstall (move to the fixed signing key).
