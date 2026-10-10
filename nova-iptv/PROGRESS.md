# Nova IPTV – progress

## Environment blockers (cloud build container)
- `dl.google.com` (and therefore `maven.google.com`, which redirects to it) is **blocked by the
  environment network policy**. Without it, the Android Gradle Plugin, Android SDK and all AndroidX
  libraries (Compose, tv-material, Media3, Room, DataStore) cannot be downloaded, so Android modules
  cannot be compiled and no APK can be produced in this container.
- No `/dev/kvm`: an Android TV emulator cannot run here.
- Fix: add `dl.google.com` to the environment's allowed domains (cloud environment menu → Edit →
  Network access), or build locally with Android Studio.

## Build modes
- `./gradlew -PjvmOnly=true test` – pure Kotlin modules (model, common, domain, playlist, xtream, epg).
  Works without the Android SDK.
- `./gradlew assembleDebug` – full app; needs Android SDK (compileSdk 36, build-tools 36) and Google Maven.

## Status
See FINAL_REPORT.md for per-feature status once the integration pass is complete.
