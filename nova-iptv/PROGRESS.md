# Nova IPTV – progress

## Done
- MVP: M3U (URL/file), Xtream (live + VOD + series), XMLTV EPG, catch-up, Home, Live TV, TV Guide,
  Movies, Series, Search, Favorites, Settings, Media3 player, 4 languages (en/lt/de/ru).
- Security review fixes; Xtream logins encrypted (Android Keystore); backups disabled.
- Large-catalogue work: Xtream catalogues stream-decoded; M3U imported in batches of 500 inside one
  transaction (refresh keeps old channels on failure); slim `ChannelListItem` lists for Live TV,
  Home (LIMIT 20) and Search (findByEpgIds).
- Imports continue in the background; M3U links with a provider login are offered as Xtream.
- Archive: 14-day default for declared catch-up without a day count, guide up to 30 days back,
  EPG kept for each channel's archive window. Playback speed 0.5x–2x, accelerated seeking,
  live rewind through the provider's archive (only channels that declare catch-up).
- CI: build + unit tests + lint + Android TV emulator job (instrumented tests, demo playlist,
  screenshots committed to `docs/screenshots/`).

## Remaining
See `NEXT_TASKS.md` (real-device test, trademark check, privacy policy, release signing,
UI tests for Movies / Series / Favorites / Home rails).

## Latest test results
- Run 38021873025 (green): 304 unit tests, 26 instrumented tests on the Android TV emulator
  (app 21, core/data 5), no FATAL EXCEPTION / ANR. New Compose UI tests: LiveTvScreenTest,
  GuideScreenTest, SearchScreenTest, PlaylistsSettingsTest, PlayerOverlayTest (seeded via
  SeededPlaylistRule, driven by NovaUiDriver with D-pad/MENU/long-press keys).
- The NetworkOnMainThreadException crash when leaving the player is verified fixed
  (PlayerOverlayTest: BACK from playing, loading and error states).
- UI tests found and fixed: BACK in the player needed two presses when a button was focused
  (track panel, controls, error panel) – Compose turned the first BACK into a focus exit.

## Known issues
- Archive of a programme still airing may lag; first live rewind step is at least 30 s.
- Rewind needs EPG data for the target moment.
- One import runs at a time (repository mutex).

## Next concrete step
Push 3ba6128, verify in CI emulator (build, unit tests, crash scan, screenshots), fix failures,
then review screenshots and polish the design.
