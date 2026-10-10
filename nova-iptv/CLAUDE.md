# Nova IPTV – working rules for Claude

## Autonomy (owner's explicit instruction)
- Work until the task is fully done. Do NOT stop to ask "should I continue?" and do not ask for
  approval of plans. If the next step is safe and reversible, do it.
- When an MVP/stage is done, immediately continue with `NEXT_TASKS.md` (top to bottom) without asking.
- Resolve uncertainty from the repo, docs, tests and existing code; pick the clearly better reversible
  option and note why in `PROGRESS.md`.
- Stop and ask ONLY for: passwords/API keys/secrets, payments, publishing to Google Play or any
  production action, deleting branches/history (force push, reset --hard), merging to `main`,
  or a choice between two equally valid product directions.
- Bugs, missing tests, lint, docs, UI issues, performance – fix them yourself.

## State
- Keep `PROGRESS.md` current with sections: Done · Remaining · Latest test results · Known issues ·
  Next concrete step. Update after every stage; small logical commits.
- Push only to the session's designated feature branch; never to `main`.

## Build facts
- Pure Kotlin modules: `./gradlew -PjvmOnly=true test` (works without the Android SDK).
- Android build, unit tests, lint and the Android TV emulator tests run in GitHub Actions
  (`.github/workflows/nova-iptv-android.yml`). In cloud sessions `dl.google.com` is blocked, so use CI.
- Read CI logs with the GitHub MCP `get_job_logs` (tail); jobs print compact summaries at the end.
