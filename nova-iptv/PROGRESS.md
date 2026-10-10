# Nova IPTV – progress

**State (2026-10-10):** MVP complete, builds and passes all tests in CI, launches and plays a test
stream in an Android TV emulator. See `FINAL_REPORT.md` for verified results and `NEXT_TASKS.md` for
what is left.

## How to continue
- Local (pure Kotlin modules only): `cd nova-iptv && ./gradlew -PjvmOnly=true test`
- Full Android build + emulator tests: push to the branch; GitHub Actions runs
  `.github/workflows/nova-iptv-android.yml` (build job + Android TV emulator job).
- With Android Studio locally: open `nova-iptv/`, run `./gradlew assembleDebug`.

## Environment limits of the cloud session
- `dl.google.com` / `maven.google.com` blocked by the environment network policy → no local Android build.
- No `/dev/kvm` → no local emulator. Both are available on GitHub-hosted runners.
