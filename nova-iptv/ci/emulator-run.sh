#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner (working directory: nova-iptv/).
#  1. Instrumented tests (core:data Room DAO tests, app end-to-end/player/UI tests).
#  2. Manual smoke test of the installed debug APK: launch, D-pad input, screenshots.
#  3. Crash/ANR scan of logcat.
# Never stops early: every step runs, the exit code reflects all of them.
set -u
PKG=app.novaiptv.tv
ACTIVITY=app.novaiptv.MainActivity
OUT=build/emulator
mkdir -p "$OUT/screens"
status=0

adb shell getprop ro.build.version.sdk > "$OUT/device-sdk.txt" 2>&1
adb shell getprop ro.build.characteristics > "$OUT/device-characteristics.txt" 2>&1
adb shell pm list features | grep -i leanback > "$OUT/device-leanback.txt" 2>&1

# ---- 1. instrumented tests --------------------------------------------------------------
adb logcat -G 16M > /dev/null 2>&1; adb logcat -c
./gradlew :core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest --continue > "$OUT/connected.log" 2>&1
echo $? > "$OUT/connected.exit"
adb logcat -d -v threadtime > "$OUT/logcat-instrumented.txt"
[ "$(cat "$OUT/connected.exit")" = 0 ] || status=1

# ---- 2. smoke test of the real app --------------------------------------------------------
shot() { adb exec-out screencap -p > "$OUT/screens/$1.png"; }
key() { adb shell input keyevent "$1"; sleep 1.5; }
alive() { adb shell pidof "$PKG" > /dev/null 2>&1; }

adb uninstall "$PKG" > /dev/null 2>&1
adb install -r app/build/outputs/apk/debug/app-debug.apk > "$OUT/install.log" 2>&1 || { echo "install failed" >> "$OUT/smoke.txt"; status=1; }
adb logcat -G 16M > /dev/null 2>&1; adb logcat -c
adb shell am start -W -n "$PKG/$ACTIVITY" > "$OUT/am-start.txt" 2>&1
sleep 10
shot 01-launch-onboarding
alive && echo "after launch: running" >> "$OUT/smoke.txt" || echo "after launch: NOT RUNNING" >> "$OUT/smoke.txt"

key DPAD_DOWN
key DPAD_RIGHT
shot 02-dpad-focus-moved
key DPAD_CENTER          # opens "Add playlist" for the focused option
sleep 2
shot 03-after-center
key BACK
shot 04-after-back
key DPAD_UP              # into the top bar
key DPAD_UP
for _ in 1 2 3 4 5 6 7 8; do adb shell input keyevent DPAD_RIGHT; sleep 0.4; done
sleep 1
shot 05-topbar-right
key DPAD_CENTER          # opens the focused tab (Settings when the bar was traversed fully)
sleep 2
shot 06-tab-opened
key DPAD_DOWN
key DPAD_DOWN
shot 07-tab-dpad-down
key BACK
sleep 2
shot 08-after-back
alive && echo "after D-pad sequence: running" >> "$OUT/smoke.txt" || echo "after D-pad sequence: NOT RUNNING (may have exited via BACK on Home)" >> "$OUT/smoke.txt"

# Relaunch to make sure a second cold start works too.
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACTIVITY" >> "$OUT/am-start.txt" 2>&1
sleep 6
shot 09-relaunch
alive && echo "after relaunch: running" >> "$OUT/smoke.txt" || { echo "after relaunch: NOT RUNNING" >> "$OUT/smoke.txt"; status=1; }

adb logcat -d -v threadtime > "$OUT/logcat-smoke.txt"

# ---- 3. crash / ANR scan ----------------------------------------------------------------------
: > "$OUT/crashes.txt"
for log in "$OUT/logcat-smoke.txt" "$OUT/logcat-instrumented.txt"; do
  if grep -qE "FATAL EXCEPTION|ANR in app\.novaiptv" "$log"; then
    echo "### $(basename "$log")" >> "$OUT/crashes.txt"
    grep -E -A40 "FATAL EXCEPTION|ANR in app\.novaiptv" "$log" | grep -E "AndroidRuntime|ActivityManager|FATAL|ANR|Process:|Caused by|	at |^--" | head -120 >> "$OUT/crashes.txt"
  fi
done
if [ -s "$OUT/crashes.txt" ]; then
  echo "CRASH/ANR FOUND" > "$OUT/crash-status.txt"
  status=1
else
  echo "no FATAL EXCEPTION / ANR in logcat" > "$OUT/crash-status.txt"
fi
echo $status > "$OUT/status.txt"
exit $status
