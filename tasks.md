# homebase-tv — Tasks

Android TV launcher (Jetpack Compose for TV) + FastAPI backend.
Git repo: `https://github.com/subarude15/homebase-tv` · branch: `main` · working tree clean.

Generated: 2026-10-29 · app v0.1.0 (code 1) · backend v0.4.0 · HA 2026.10.4

---

## Architecture at a glance

**Frontend (Android TV app, Kotlin/Compose, package `com.homebase.tv`):**
- `MainActivity` → `HomebaseRoot` (tab row: Home/Movies/Shows/Apps/Settings)
- `ViewModels.kt` → launchers, settings, backend state
- `data/apps/` — installed-apps scan + favorite app reordering (5-test suite)
- `data/backend/` — Retrofit client for `http://192.168.1.145:8770`
- `data/settings/` — favorites, reordering, toggles
- `ui/` — Compose-for-TV cards, rows, focus/back handling, `BackHandler`
  that keeps Back inside Homebase (never resumes the app underneath)
- `ui/theme/Theme.kt` — charcoal theme, default `slate-ember` variant

**Backend (FastAPI, Python, runs on the NAS via docker-compose):**
- `backend/app/main.py` — `/api/home` (cached 60s, stale-while-refresh), auth via `HOMEBASE_API_TOKEN`
- `integrations/emby.py` — Emby client (session detection, playback, images)
- `integrations/iptv.py` — Xtream channel lookup (logos only)
- `integrations/trakt.py` — Trakt OAuth + Continue Watching / Watchlist
- `integrations/catalog.py` — Nuvio trending TMDB catalog
- `db.py` — `homebase.db` (SQLite, git-ignored, lives in `./data/`)

**Key design choices:**
- Home screen `Back` stops at Homebase — never leaves to an underlying app
- Focus memory (`FocusMemory`) — restores focus when returning to a tab
- Move Mode — long-press to reorder apps on the Apps tab
- Dashboard deploy: dry-run only (`DEPLOY_ENABLED = false`); full drift-detection / reference-check pipeline

---

## How to run things

### Backend (local dev)

```powershell
cd C:\Users\imalo\dev\homebase-tv\backend
.venv\Scripts\python -m pytest -q              # 23 tests, 7s
.venv\Scripts\python -m app.db                  # optional: init/clear SQLite (if supported)
.venv\Scripts\python -m uvicorn app.main:app --reload --port 8770
```

### Backend (Docker / NAS)

```powershell
cd C:\Users\imalo\dev\homebase-tv
copy .env.example .env                        # NEVER commit .env
# → edit: HOMEBASE_API_TOKEN, HOMEBASE_EMBY_API_KEY, HOMEBASE_TRAKT_CLIENT_ID, etc.
docker compose up -d                           # port = $HOMEBASE_PORT (default 8770)
docker compose logs -f homebase-backend
```

### Android app (on device over Wi-Fi)

```powershell
cd C:\Users\imalo\dev\homebase-tv\android
.\dev.ps1 [-Device 192.168.1.171:5555] [-Screenshot]
```
The script sets `JAVA_HOME`, `ANDROID_HOME`, runs `./gradlew.bat installDebug`, then
`adb shell am start -W -n com.homebase.tv/.MainActivity`. Requires Android Studio JBR
at `C:\Program Files\Android\Android Studio\jbr` and the SDK in `LOCALAPPDATA`.

### Signing a release APK

```powershell
cd C:\Users\imalo\dev\homebase-tv\android
.\sign-release.ps1                             # expects unsigned APK at app/build/.../app-release-unsigned.apk
```
Signing materials live outside the repo in `$env:HOMEBASE_SIGNING_DIR` (default
`~/.homebase-signing`): `homebase-release.p12`, `homebase-release.pass.dpapi`
(DPAPI-encrypted), `debug-to-release.lineage`. Outputs `homebase-release.apk` and
runs `apksigner verify`.

---

## Test status

### Backend (pytest) — ✅ passing
```
====================== 23 passed, 1 warning in 7.37s ======================
```
Suites: `test_catalog.py`, `test_health.py`, `test_home.py`, `test_iptv.py`, `test_trakt.py`
The one warning is a Starlette/fastapi deprecation about `httpx` TestClient (harmless).

### Android (JUnit) — ⚠️ not run here (needs Android toolchain)
Test files exist at `android/app/src/test/java/com/homebase/tv/`:
- `FavoritesTest.kt` — 8 tests covering favorite-row ordering, toggle add/remove, reordering persistence
- `HomeContractTest.kt` — guards the `/api/home` JSON contract (rows, shape, media item fields, null safety)
- `MoveModeTest.kt` — app-reorder Move Mode state machine

**To run:** `./gradlew test` from the `android/` directory (requires Android Studio + SDK).

---

## Project map

| Path | Description |
|---|---|
| `android/app/src/main/java/com/homebase/tv/HomebaseRoot.kt` | Tab host, BackHandler, focus memory, row rendering |
| `android/app/src/main/java/com/homebase/tv/ui/ViewModels.kt` | LauncherSettings, SettingsViewModel, LauncherViewModel |
| `android/app/src/main/java/com/homebase/tv/data/apps/InstalledAppsRepository.kt` | TV-launched app scan + favorites |
| `android/app/src/main/java/com/homebase/tv/data/backend/BackendRepository.kt` | Retrofit client for home API |
| `android/app/src/main/java/com/homebase/tv/data/settings/SettingsRepository.kt` | favorites/order/toggle persistence |
| `backend/app/main.py` | FastAPI entrypoint: `/api/home`, auth, caching |
| `backend/app/config.py` | `pydantic-settings` with `HOMEBASE_` prefix; see `.env.example` |
| `backend/app/db.py` | SQLite cache layer |
| `backend/app/integrations/emby.py` | Emby client |
| `backend/app/integrations/trakt.py` | Trakt OAuth + watchlist/continue |
| `backend/app/integrations/catalog.py` | Nuvio TMDB catalog |
| `backend/app/integrations/iptv.py` | Xtream channel logo lookup |
| `android/dev.ps1` | Build + install + launch on TV over Wi-Fi |
| `android/sign-release.ps1` | v3 APK signing + verification |
| `docker-compose.yml` | Backend container (port 8770, `./data` volume for db) |
| `.env.example` | Template for backend config (rename to `.env`) |

---

## Completed (recent work)

### Home screen UX (`4ceef90`)
- ✅ Added favorite app reordering on the Home screen
- ✅ Simplified Home screen layout
- ✅ Both done in `AppCard.kt`, `HomebaseRoot.kt`, `Screens.kt`, `ViewModels.kt`
- ✅ Co-authored by Cursor

### Bar Mode + Closing Time scripts (`55297d4`, `81db808`)
- ✅ Added bar lighting scripts (Bar Mode, Closing Time)
- ✅ With read-only validation tests (51 bar-script tests)

### Nuvio discovery (`f4fe443`, `5aefe73`)
- ✅ Added Nuvio trending rows
- ✅ Organized Movies and Shows discovery via Trakt
- ✅ Continue Watching + Watchlist via Trakt (`Phase 2D`)

### Favorite Channels (`41c88a1`)
- ✅ Added Favorite Channels jump list that opens TiviMate (`Phase 2E`)

### Media card behavior (`b237b7f`)
- ✅ Media cards play/resume or open in Emby
- ✅ Tracks Android data package
- ✅ Local adb dev script

### Phase 1 (`277fddb`, `5c6c61b`, `c6afa1e`)
- ✅ FastAPI backend with health/auth
- ✅ Emby home rows, SQLite stale cache, image proxy
- ✅ Compose for TV launcher scaffold

---

## Open tasks

### ✅ Recently completed

- [x] **CI release workflow** — `.github/workflows/release.yml` builds on `v*` tag push → JDK 25 → materialize
      keystore + rotation lineage → `assembleRelease` (unsigned) → `apksigner` sign v3 **with lineage** →
      verify → upload `homebase-release.apk` artifact.
      **Live and verified**: run `41112196210` (tag `v0.1.0`) succeeded in 2m42s; the CI-built APK is
      byte-identical to the locally signed APK (`efd25cc924cf9489...`) with signer
      `59550eb8fd0e75f52ff9f29e42ba0cdde534838c170c1f9d2ae294613c5bd172` — so CI installs upgrade
      in place over debug-signed and local-release installs.
      Repo secrets set: `HOMEBASE_KEYSTORE_BASE64`, `HOMEBASE_KEYSTORE_PASSWORD`, `HOMEBASE_KEY_ALIAS`,
      `HOMEBASE_KEY_PASSWORD`, `HOMEBASE_LINEAGE_BASE64`.
      Gradle no longer signs the release build (Gradle's `signingConfig` cannot express a lineage) —
      both CI and local sign via `apksigner`.

### 🟡 High priority

- [ ] **Verify the `docker-compose.yml` volume mount** — the comment says "Synology Container Manager won't create this; run `mkdir -p data` before the first `up`." Confirm this pre-exists on your NAS or add a one-shot init.

### 🟢 Medium priority

- [ ] **Backend deploy CI** — the release workflow only builds the APK. There is still no CI for the
      FastAPI backend. `origin/cursor/phase-1c-detail-play-ee00` exists but wasn't merged — decide whether
      to merge it or drop it.
- [ ] **Backend version drift** — `main.py` says `VERSION = "0.4.0"` but `pyproject.toml` says `0.1.0`. The pyproject version should bump to match.
- [ ] **`.env.example` has placeholder values** — `HOMEBASE_EMBY_API_KEY=` and `HOMEBASE_TRAKT_CLIENT_ID=` are empty. Confirm these are the only required ones; the README in `.env.example` says to sign in to Trakt via `docker exec -it` but that's not in the tasks.md.
- [ ] **TV-specific focus debugging** — Back handling (`backTarget`, `BackHandler`) is complex. Consider adding the `MoveModeTest` to run on device (instrumented test) rather than unit test, since it depends on actual D-pad key events.
- [ ] **README** — there's no `README.md` in this repo (only `.env.example`). At minimum document the dev workflow (`dev.ps1`, `sign-release.ps1`, `docker-compose up`) and the `.env` requirements.

### 🔵 Low priority / ideas

- [ ] **Publish the APK as a GitHub Release** — the workflow only uploads a workflow artifact (retained
      90 days). A `release.yml` job using `softprops/action-gh-release` would attach the APK to the tag
      itself, which survives and is installable from a stable URL.
- [ ] **Version bump automation** — `versionCode = 1` / `versionName = "0.1.0"` are hardcoded in
      `build.gradle.kts` while releases are tag-driven. Consider deriving the version from the tag.
- [ ] **Backend DB migrations** — `db.py` uses SQLite with no migration tooling. If the schema evolves, how will you handle upgrades?
- [ ] **Health endpoint** — `test_health.py` exists; make sure `/health` (if present in `main.py`) is wired into the docker-compose so Synology/HA can do liveness checks.
- [ ] **Document the Home key behavior** — `BackHandler` is carefully written to keep Back inside Homebase, but the Android Home button still leaves as usual. Make sure this is documented for users who ask "why doesn't Back exit Homebase."

---

## Environment / credentials

- **Backend URL (for app config):** `http://192.168.1.145:8770`
- **Backend port:** 8770 (from `.env.example` `HOMEBASE_PORT`, also in `docker-compose.yml`)
- **Emby:** `http://192.168.1.143:8096` (from `.env.example`)
- **TV target for dev:** `192.168.1.171:5555` (the `dev.ps1` default device)
- **Signing dir:** `~/.homebase-signing` (or `$env:HOMEBASE_SIGNING_DIR`)
- **Git identity:** `subarude15 <140115215+subarude55@users.noreply.github.com>` (matches commit `4ceef90`)

---

## Git state

- **Branch:** `main` (ahead of and even with `origin/main`)
- **Working tree:** clean (no untracked or modified files)
- **Recent commits:**
  1. `4ceef90` — feat: add favorite app reordering and simplify Home screen
  2. `55297d4` — build: add optimized signed Android release workflow
  3. `f4fe443` — fix: keep Back inside Homebase, stop focus restore stealing navigation
  4. `5aefe73` — feat: add Nuvio trending and organize Movies/Shows discovery
  5. `33ca6f8` — feat: simplify Homebase navigation and remove dynamic backdrops
  6. `41c88a1` — fix: prevent slow home refresh from blocking TV startup
- **Remote branch:** `origin/cursor/phase-1c-detail-play-ee00` (exists but not merged)
