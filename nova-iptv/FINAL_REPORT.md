# Nova IPTV – final report (MVP, 2026-10-10)

## BUILD STATUS: PASS

Built and tested on GitHub Actions (this cloud container cannot download the Android SDK; see
"Environment" below). Reference run with full results:
https://github.com/svedasgediminas-source/svedasbau.de/actions/runs/38022658965 (build + emulator jobs: success, lint: 0 errors, no crash/ANR)

## APK PATH

Artifact **`nova-iptv-apk`** of the workflow run (Actions → run → Artifacts), containing:

| File | Size | Notes |
|---|---|---|
| `app/build/outputs/apk/release/app-release.apk` | 2.99 MB | minified (R8), **signed with the debug key** – fine for sideloading, not for Google Play |
| `app/build/outputs/apk/debug/app-debug.apk` | 25.6 MB | debug build |

Application id `app.novaiptv.tv`, version 0.1.0-mvp, minSdk 26 (Android 8.0), targetSdk 36, compileSdk 37.

## AGENTS USED: 18 (+ coordinator)

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
| Live TV | DONE | emulator UI tests (LiveTvScreenTest): group counts, D-pad group filter, current programme in rows, MENU options, favourite via MENU and long-press OK, Fix TV guide dialog |
| TV Guide | DONE | emulator UI tests (GuideScreenTest): seeded programmes in the grid, LIVE badge, group chips filter rows, OK on a future programme offers "Remind me" |
| M3U (URL) | DONE | unit tests + emulator end-to-end: 3 channels / 2 groups imported from a local server |
| M3U (file) | DONE – NOT TESTED | needs a TV with a document picker; handled gracefully when none exists |
| Xtream | DONE – PARTIAL | 32 unit tests against a mock server (login, wrong password, expired, odd data); NOT TESTED against a real provider |
| EPG | DONE | unit tests (incl. DST, gzip, 200k programmes) + emulator end-to-end (now/next correct) |
| Catch-up | DONE – PARTIAL | URL building unit-tested for all catch-up types; NOT TESTED against a real archive server |
| Movies | DONE – NOT TESTED in UI | logic unit-tested (resume, formatting, filters) |
| Series | DONE – NOT TESTED in UI | logic unit-tested (season/episode order, next episode) |
| Search | DONE | ranking/merging unit-tested; emulator UI test (SearchScreenTest): channel and programme results, no-results state |
| Favorites | DONE | persistence across app restart tested in emulator |
| Settings | DONE | opened via top bar in emulator UI test; Settings > Playlists lists the seeded playlist as Active, manager opens with D-pad (PlaylistsSettingsTest) |
| Player | DONE | emulator: Apple public HLS test stream plays (Playing after 2.3 s, position advances); broken stream stops after 3 retries (no loop); UI test from Live TV: overlay name/LIVE/now, MENU panel, CH+, INFO controls, BACK to Live TV without crash, error panel (PlayerOverlayTest) |
| Languages | DONE – NOT TESTED | en / lt / de / ru strings complete; switching not UI-tested |
| Remote control | PARTIAL | D-pad sequences in emulator without crash; NOT TESTED on a physical TV |

## TEST RESULTS (run 38021873025, real numbers)

https://github.com/svedasgediminas-source/svedasbau.de/actions/runs/38021873025 – build: success, emulator: success.

| Suite | Run | Passed | Failed | Not run |
|---|---|---|---|---|
| Unit tests (JVM + Android unit) | 304 | 304 | 0 | 0 |
| Instrumented tests on Android TV emulator (API 34, leanback) | 26 | 26 | 0 | 0 |

Unit tests per module: app 81, core/common 19, core/data 36, core/domain 20, core/player 50, data/epg 20, data/playlist 46, data/xtream 32.
Instrumented per module: app 21, core/data 5. The app suite has Compose UI tests driven with D-pad / MENU /
long-press key events on a playlist seeded from a MockWebServer on the device (10 channels, 3 groups, XMLTV
around "now"), imported through the app's own DataGraph: LiveTvScreenTest (4), GuideScreenTest (3),
SearchScreenTest (2), PlaylistsSettingsTest (1), PlayerOverlayTest (3), plus MainActivitySmokeTest (3),
EndToEndDataTest (3), PlaybackTest (2).

Emulator smoke run: install → launch → D-pad DOWN/RIGHT/CENTER/BACK/UP/RIGHT×8/CENTER → force-stop →
relaunch: app running at every step, **no FATAL EXCEPTION / ANR** in logcat. 9 screenshots in artifact
`nova-iptv-tv-screenshots` (not inspected by me – artifact download is blocked in this container).

Not done: tests on a physical TV, tests with a real Xtream provider, UI tests for Movies / Series /
Favorites screen / Home content rails, performance profiling on a low-RAM device, language-switch UI test.

### Acceptance criteria (brief §14)
1. Compiles – **yes**. 2. Real APK – **yes**. 3. Launches in Android TV emulator – **yes**.
4. Main screens by remote – **verified in the emulator** for Live TV, TV Guide, Search, Settings > Playlists and the player (Movies/Series not UI-tested). 5. Add M3U – **yes** (emulator E2E).
6. Channels in categories – **yes** (data level). 7. Legal test stream plays – **yes** (Apple HLS sample).
8. Favourites survive restart – **yes**. 9. Invalid data does not crash – **yes** (garbage/empty/404/invalid URLs).
10. No known critical bugs in the main flow – **none known**; open items below.

## PLAYBACK ROBUSTNESS (2026-10-19, real-provider report: list imports, video does not play / buffers forever)

Green run: https://github.com/svedasgediminas-source/svedasbau.de/actions/runs/39220237925 –
319 unit tests (core/player 65), 31 instrumented tests on the Android TV emulator, 0 failures, no crash/ANR.
APK: https://github.com/svedasgediminas-source/svedasbau.de/releases/download/nova-iptv-latest/nova-iptv.apk

- **Format probe** for extension-less URLs (`/user/pass/123`, `.php?…`): short ranged GET (same UA/headers,
  redirects, 8 s, retried without Range on 403/416) decides HLS/DASH/TS from first bytes, Content-Type and
  final URL; the ORIGINAL URL is replayed (tokens may be single-use), only the MIME type is used; cached per URL.
- **Recovery ladder** (bounded, ≤ 8 attempts, never loops): parse error → other container; 401/403 or no data →
  next User-Agent; live Xtream-style `/u/p/ID(.ts)` also tries `/live/u/p/ID.m3u8` and `/u/p/ID.m3u8`.
- **Buffering watchdog**: no byte in 10 s / no start in 20 s → next attempt, then an error instead of an endless
  spinner; live stream stuck 15 s mid-way → bounded reconnect.
- **Stream User-Agent** setting (Settings → Player): Automatic (default: app → VLC → Media3 default), Nova IPTV,
  VLC, ExoPlayer default, Custom. Streams with their own #EXTVLCOPT agent keep it. Downloads unchanged.
- HTTP/1.1, cross-protocol redirects, TS flags (non-IDR keyframes, AU detection, HDMV DTS), decoder fallback.
- Error screen shows a technical hint (e.g. `ERROR_CODE_IO_BAD_HTTP_STATUS · HTTP 403 · TS`, `NO_DATA_TIMEOUT · TS`)
  and a User-Agent tip – never the URL. Logcat tag `NovaPlayer`: HTTP status / Content-Type / bytes per attempt.
- Xtream: all play paths (live, catch-up, timeshift, movies, episodes, CH+/−, number entry, retry) resolve
  credential placeholders; live output is m3u8 when allowed_output_formats allows it, else ts.
- Emulator tests (PlaybackRobustnessTest): HLS at extension-less path via Content-Type and via first bytes,
  UA-gated server with VLC setting and with Automatic, silent server → `NO_DATA_TIMEOUT` error after ~12 s
  with all three agents tried, no further state change.

## REMAINING ISSUES

- M3U import holds the parsed channel list in memory before saving (very large lists on 1 GB boxes).
- Huge lists (50k+ channels) are loaded whole into Live TV / Search (mapping is off the main thread, no paging yet).
- M3U playlist URLs that embed username/password are stored as given (app-private, excluded from backup);
  only Xtream logins go to the encrypted store.
- Back is blocked while an import runs (instead of continuing in the background).
- Lint: 0 errors (both NonObservableLocale errors fixed); warnings not yet triaged.
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
