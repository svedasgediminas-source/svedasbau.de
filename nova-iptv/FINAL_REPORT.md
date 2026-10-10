# Nova IPTV – final report (MVP, 2026-10-10)

## BUILD STATUS: PASS

Built and tested on GitHub Actions (this cloud container cannot download the Android SDK; see
"Environment" below). Reference run with full results:
https://github.com/svedasgediminas-source/svedasbau.de/actions/runs/38012767842 (build + emulator jobs: success; same numbers as run 38011972795)

## APK PATH

Artifact **`nova-iptv-apk`** of the workflow run (Actions → run → Artifacts), containing:

| File | Size | Notes |
|---|---|---|
| `app/build/outputs/apk/release/app-release.apk` | 2.99 MB | minified (R8), **signed with the debug key** – fine for sideloading, not for Google Play |
| `app/build/outputs/apk/debug/app-debug.apk` | 25.6 MB | debug build |

Application id `app.novaiptv.tv`, version 0.1.0-mvp, minSdk 26 (Android 8.0), targetSdk 36, compileSdk 37.

## AGENTS USED: 10 (+ coordinator)

Parallel subagents with disjoint file ownership; the coordinator owned Gradle, contracts, integration and commits.

| Agent | Area | Result |
|---|---|---|
| A06 | M3U parser, catch-up URL builder | done, 38 tests |
| A07 | Xtream client | done, 30 tests (+2 added later) |
| A09 | XMLTV EPG parser, channel matching | done, 20 tests |
| A11 | Room DB, DataStore, Keystore credential store, repositories | done, 26 unit + 5 instrumented tests |
| A08 | Media3 player engine + player screen | done, 24 tests |
| A04/A05/A16 | design system, TV navigation, app shell, Home, Settings, playlists | done |
| A12/A10 | Live TV, TV Guide, catch-up UI | done, logic tests |
| A13/A14/A15 | Movies, Series, Search, Favorites | done, logic tests |
| A19 | Android TV emulator QA in CI | done, 8 instrumented tests + smoke run |
| A17 | security / correctness review (read-only) | 14 findings, high ones fixed |

## FEATURE STATUS

| Feature | Status | How it was verified |
|---|---|---|
| Home | DONE | onboarding shown on clean install (emulator UI test); content rails NOT TESTED in UI |
| Live TV | DONE – NOT TESTED in UI | data path tested (import → channels/groups); screen compiled, logic unit-tested |
| M3U (URL) | DONE | unit tests + emulator end-to-end: 3 channels / 2 groups imported from a local server |
| M3U (file) | DONE – NOT TESTED | needs a TV with a document picker; handled gracefully when none exists |
| Xtream | DONE – PARTIAL | 32 unit tests against a mock server (login, wrong password, expired, odd data); NOT TESTED against a real provider |
| EPG | DONE | unit tests (incl. DST, gzip, 200k programmes) + emulator end-to-end (now/next correct) |
| Catch-up | DONE – PARTIAL | URL building unit-tested for all catch-up types; NOT TESTED against a real archive server |
| Movies | DONE – NOT TESTED in UI | logic unit-tested (resume, formatting, filters) |
| Series | DONE – NOT TESTED in UI | logic unit-tested (season/episode order, next episode) |
| Search | DONE – NOT TESTED in UI | ranking/merging unit-tested |
| Favorites | DONE | persistence across app restart tested in emulator |
| Settings | DONE | opened via top bar in emulator UI test; D-pad works inside |
| Player | DONE | emulator: Apple public HLS test stream plays (Playing after 2.3 s, position advances); broken stream stops after 3 retries (no loop) |
| Languages | DONE – NOT TESTED | en / lt / de / ru strings complete; switching not UI-tested |
| Remote control | PARTIAL | D-pad sequences in emulator without crash; NOT TESTED on a physical TV |

## TEST RESULTS (run 38011972795, real numbers)

| Suite | Run | Passed | Failed | Not run |
|---|---|---|---|---|
| Unit tests (JVM + Android unit) | 205 | 205 | 0 | 0 |
| Instrumented tests on Android TV emulator (API 34, leanback) | 13 | 13 | 0 | 0 |

Unit tests per module: app 55, core/common 10, core/data 26, core/player 24, data/epg 20, data/playlist 38, data/xtream 32.
Locally after the review fixes: 100 JVM tests, 0 failures.

Emulator smoke run: install → launch → D-pad DOWN/RIGHT/CENTER/BACK/UP/RIGHT×8/CENTER → force-stop →
relaunch: app running at every step, **no FATAL EXCEPTION / ANR** in logcat. 9 screenshots in artifact
`nova-iptv-tv-screenshots` (not inspected by me – artifact download is blocked in this container).

Not done: tests on a physical TV, tests with a real Xtream provider, UI tests for Live TV / Guide /
Movies / Series / Search, performance profiling on a low-RAM device, language-switch UI test.

### Acceptance criteria (brief §14)
1. Compiles – **yes**. 2. Real APK – **yes**. 3. Launches in Android TV emulator – **yes**.
4. Main screens by remote – **partially verified** (onboarding, top bar, Settings). 5. Add M3U – **yes** (emulator E2E).
6. Channels in categories – **yes** (data level). 7. Legal test stream plays – **yes** (Apple HLS sample).
8. Favourites survive restart – **yes**. 9. Invalid data does not crash – **yes** (garbage/empty/404/invalid URLs).
10. No known critical bugs in the main flow – **none known**; open items below.

## REMAINING ISSUES

- M3U import holds the parsed channel list in memory before saving (very large lists on 1 GB boxes).
- Huge lists (50k+ channels) are loaded whole into Live TV / Search (mapping is off the main thread, no paging yet).
- M3U playlist URLs that embed username/password are stored as given (app-private, excluded from backup);
  only Xtream logins go to the encrypted store.
- Back is blocked while an import runs (instead of continuing in the background).
- Lint: one remaining error to triage (the second one was fixed); see the latest CI "Lint" step.
- Release APK is signed with the debug key.
Full list with priorities: `NEXT_TASKS.md`.

## INSTALLATION (Android TV / Google TV)

1. Open the workflow run on GitHub → **Artifacts** → download `nova-iptv-apk`, unzip, take `app-release.apk`.
2. On the TV: Settings → Device preferences → About → click *Build* 7× (developer mode) →
   Developer options → enable **USB/ADB debugging** (Network debugging on Google TV).
3. From a computer on the same network: `adb connect <TV-IP>:5555` then `adb install app-release.apk`.
   Alternative without a computer: upload the APK somewhere reachable and install with the *Downloader* app
   (allow "install unknown apps" for it).
4. Nova IPTV appears in the TV's app row. Add your own M3U URL or Xtream login in the onboarding screen.

## NEXT STEPS to a commercial release

Real-device testing (remote, 4K, low-RAM boxes) → trademark check of the name → privacy policy →
release keystore and Play signing → Play policy review for IPTV players → fix the open performance
items → only then Free/Premium billing (P2). Details in `NEXT_TASKS.md`.

## Environment

The cloud session's network policy blocks `dl.google.com` (Android SDK, Google Maven) and the container has
no KVM, so all Android compilation, APK packaging and emulator runs happen in
`.github/workflows/nova-iptv-android.yml` on GitHub-hosted runners. Pure Kotlin modules build locally with
`./gradlew -PjvmOnly=true test`.
