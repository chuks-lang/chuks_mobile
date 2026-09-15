#!/usr/bin/env bash
# Host parity: does each host draw what the engine laid out, and do the two hosts draw
# the same thing?
#
# Builds tests/host (a fixture app that renders a fixed set of screens inside a 360x640
# box and writes each screen's view tree to a file) onto the booted iOS simulator and
# the running Android emulator, waits for both to finish, pulls the dumps and runs
# tools/host_parity.py over them: a platform frame that drifts from Yoga's, or a frame
# that differs between the hosts, fails.
#
#   bash tools/host-parity.sh              # build, run, compare
#   SKIP_BUILD=1 bash tools/host-parity.sh # re-run the installed apps and compare
#   PARITY_KEEP=1 ...                       # keep the pulled dumps (path printed)
#
# Env: ANDROID_SERIAL (default emulator-5554); the iOS side uses the booted simulator.
# The Android app is built DEBUGGABLE so `adb shell run-as` can read its files.
set -euo pipefail
PKGDIR="$(cd "$(dirname "$0")/.." && pwd)"
FIX="$PKGDIR/tests/host"
BUNDLE="com.chuks.hostparity"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/chuks_parity_XXXXXX")"
mkdir -p "$OUT/ios" "$OUT/android"
GREEN='\033[32m'; RED='\033[31m'; DIM='\033[2m'; RESET='\033[0m'

# The fixture writes parity/DONE last; a run starts from a clean slate.
clear_ios()     { local c; c="$(xcrun simctl get_app_container booted "$BUNDLE" data 2>/dev/null || true)"; [ -n "$c" ] && rm -rf "$c/Documents/parity" || true; }
clear_android() { adb -s "$SERIAL" shell run-as "$BUNDLE" rm -rf files/parity >/dev/null 2>&1 || true; }

# The fixture is a project of its own. Its package link and its entry are not in the
# repository (a consumer's install strips tests/ anyway); make them here.
mkdir -p "$FIX/chuks_packages/@chuks" "$FIX/.chuks"
ln -sfn ../../../.. "$FIX/chuks_packages/@chuks/mobile"
cp "$PKGDIR/template/.chuks/entry.chuks" "$FIX/.chuks/entry.chuks"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
    echo "== building $FIX onto the simulator and $SERIAL =="
    clear_ios; clear_android
    ( cd "$FIX" && FAST=1 bash chuks_packages/@chuks/mobile/ios/build.sh > "$OUT/ios-build.log" 2>&1 ) \
        || { echo "iOS build failed:"; tail -20 "$OUT/ios-build.log"; exit 1; }
    ( cd "$FIX" && DEBUGGABLE=1 ANDROID_SERIAL="$SERIAL" bash chuks_packages/@chuks/mobile/android/build.sh > "$OUT/android-build.log" 2>&1 ) \
        || { echo "Android build failed:"; tail -20 "$OUT/android-build.log"; exit 1; }
else
    clear_ios; clear_android
    xcrun simctl terminate booted "$BUNDLE" >/dev/null 2>&1 || true
    xcrun simctl launch booted "$BUNDLE" >/dev/null
    adb -s "$SERIAL" shell am force-stop "$BUNDLE" >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell am start -n "$BUNDLE/com.chuks.app.MainActivity" >/dev/null 2>&1
fi

echo "== waiting for the fixture to finish on both hosts =="
IOS_DIR="$(xcrun simctl get_app_container booted "$BUNDLE" data)/Documents/parity"
for i in $(seq 1 60); do
    ios_done=0; and_done=0
    [ -f "$IOS_DIR/DONE" ] && ios_done=1
    adb -s "$SERIAL" shell run-as "$BUNDLE" test -f files/parity/DONE >/dev/null 2>&1 && and_done=1
    [ "$ios_done" = 1 ] && [ "$and_done" = 1 ] && break
    sleep 1
done
[ -f "$IOS_DIR/DONE" ] || { echo "iOS fixture did not finish (no $IOS_DIR/DONE)"; exit 1; }
adb -s "$SERIAL" shell run-as "$BUNDLE" test -f files/parity/DONE >/dev/null 2>&1 || { echo "Android fixture did not finish (no files/parity/DONE)"; exit 1; }

cp "$IOS_DIR"/*.txt "$OUT/ios/"
for f in $(adb -s "$SERIAL" shell run-as "$BUNDLE" ls files/parity | tr -d '\r' | grep '\.txt$'); do
    adb -s "$SERIAL" shell run-as "$BUNDLE" cat "files/parity/$f" | tr -d '\r' > "$OUT/android/$f"
done

echo "== comparing =="
rc=0
python3 "$PKGDIR/tools/host_parity.py" "$OUT" || rc=$?
if [ -n "${PARITY_KEEP:-}" ] || [ "$rc" -ne 0 ]; then
    echo -e "${DIM}dumps kept under $OUT${RESET}"
else
    rm -rf "$OUT"
fi
exit $rc
