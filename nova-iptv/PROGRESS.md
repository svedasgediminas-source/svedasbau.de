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
reminders, manual EPG mapping UI, Guide group filter, UI tests for remaining screens).

## Latest test results
- Last fully green CI run before the current batch: 38013915754 (205 unit, 13 instrumented, lint 0 errors).
- Run 38017141229: build green; emulator found a real crash when leaving the player
  (NetworkOnMainThreadException) – fixed in 3ba6128, re-verification pending.

## Known issues
- Archive of a programme still airing may lag; first live rewind step is at least 30 s.
- Rewind needs EPG data for the target moment.
- One import runs at a time (repository mutex).

## Next concrete step
Push 3ba6128, verify in CI emulator (build, unit tests, crash scan, screenshots), fix failures,
then review screenshots and polish the design.
