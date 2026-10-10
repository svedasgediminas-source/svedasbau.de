# Nova IPTV – next tasks

Ordered by priority. "Review" = findings from the internal security/correctness review.

## Before any public release
1. **Test on a real Android TV / Google TV device** (remote control, 4K output, low-RAM box such as
   a 1–2 GB Android TV stick). The emulator does not prove decoder or remote behaviour on real hardware.
2. **Trademark check for the name "Nova IPTV"** – unverified.
3. **Privacy policy** (Google Play requires one): data stays on device, no analytics, credentials
   encrypted with Android Keystore, no server.
4. **Release signing**: create an upload keystore (never commit it) and replace the debug signing
   config in `app/build.gradle.kts`.
5. **Google Play policy review for IPTV players**: the store listing must make clear that the app ships
   no content (the in-app onboarding already says so).

## Robustness / performance (review findings still open)
~~**Stream the M3U import into the database** (review H1, M3U part): the parser still returns the full~~ – DONE
   channel list in memory before saving. Give `M3uParser` a per-channel/batch callback and insert in
   transactions of 500. Xtream catalogues are already stream-decoded.
~~**Paging for huge lists** (review H2, L6): Live TV, Home "Now on TV" and Search load whole channel~~ – DONE
   lists (mapping is now off the main thread). Use Room LIMIT queries / Paging 3 for 50k+ channels.
~~**M3U URLs with embedded credentials** (review M2): `get.php?username=…&password=…` playlist URLs and~~ – DONE
   their stream URLs are stored as given (app-private DB, excluded from backup). Offer conversion to an
   Xtream source so credentials go to the encrypted store.
~~**Run imports in an application scope** (review M3): Back is currently blocked during import; a~~ – DONE
   background import with progress notification would be friendlier.
~~**Share one OkHttpClient** between player and data layer (review L1).~~ – DONE
11. **Reject XMLTV with internal DTD entities** (review L5, billion-laughs hardening on the platform parser).
~~**EPG keep-window for catch-up with unknown day count**: EPG retention uses declared days only; channels~~ – DONE
    with catch-up but no day count get 1 day of past EPG although the UI allows 7.

## Features (P1/P2 from the brief)
13. Live mini-preview in Live TV (second lightweight player).
~~EPG reminders; Guide group filter.~~ – DONE
~~Manual EPG channel mapping UI (repository method exists: `EpgRepository.setManualMapping`).~~ – DONE
16. Free/Premium: Google Play Billing (only after the player is proven stable), QR login, profiles,
    multi-view, sync – all P2.
