#!/usr/bin/env bash
# The real Chuks Mobile demo: a memoized reconciler driving real UIViews via
# minimal diffs, live on the iOS simulator. Cross-compiles the engine for the
# iOS simulator, links a UIKit host, installs + launches on the booted sim.
set -euo pipefail
cd "$(dirname "$0")"

export CHUKS_NO_WARNINGS=1
OUT=".out"; APP="$OUT/ChuksDashboard.app"; BID="com.chuks.dashboard"
rm -rf "$OUT"; mkdir -p "$OUT"
OUTABS="$(cd "$OUT" && pwd)"   # absolute; the c-archive is compiled inside the cache dir, so its -o must be absolute
# Cold build wipes the AOT cache for reproducibility; FAST=1 (the dev loop) keeps
# it so the Go/stdlib recompile is incremental -- seconds instead of a cold pass.
# FAST also drops swiftc optimization (-Onone), the biggest single cost (~2.1s -> ~0.7s).
[ "${FAST:-0}" = "1" ] || rm -rf ~/.chuks/cache/builds/*
SWIFT_OPT="-O"; [ "${FAST:-0}" = "1" ] && SWIFT_OPT="-Onone"
# DEV=1 builds the host in hot-reload mode: it fetches the mutation stream from
# the Chuks VM dev server over HTTP instead of the AOT-linked engine.
DEV_FLAG=""; [ "${DEV:-0}" = "1" ] && DEV_FLAG="-D DEV"
# BENCHMARK=1 compiles the host's auto-scroll + per-frame fps harness (off in normal apps).
BENCH_FLAG=""; [ "${BENCHMARK:-0}" = "1" ] && BENCH_FLAG="-D BENCHMARK"

SIMSDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
SIMCC="$(xcrun --sdk iphonesimulator --find clang)"
TRIPLE="arm64-apple-ios15.0-simulator"
YOGA="$(pwd)/yoga"                       # iOS-sim arm64 libyoga.a (platform-specific)
YOGA_INC="$(cd ../core/yoga/include && pwd)"   # shared Yoga headers (in core/)

echo "1. chuks AOT -> Go (--c-archive emits the chuks_* C-ABI bridge; generated Go stays in the cache, never in the project)"
chuks build --c-archive ../core/entry.chuks -o "$OUT/e" >/dev/null
BD="$(ls -dt "$HOME"/.chuks/cache/builds/*/ | head -1)"   # generated Go lives here, under ~/.chuks/cache

echo "2. c-archive for iOS simulator (compiled in place in the cache; only artifacts land in $OUT)"
( cd "$BD" && CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="$SIMCC -isysroot $SIMSDK -target $TRIPLE" CGO_CFLAGS="-isysroot $SIMSDK -target $TRIPLE" \
    go build -buildmode=c-archive -tags ios -o "$OUTABS/libapp.a" . )   # emits $OUT/libapp.a + $OUT/libapp.h

# Pick the iOS render engine: env IOS_ENGINE wins, else chuks.json "iosEngine",
# else uikit. Both hosts consume the same C ABI + mutation stream; only rendering
# differs. SwiftUI host needs no Yoga (SwiftUI does its own layout).
ENGINE="${IOS_ENGINE:-$(sed -n 's/.*"iosEngine"[^"]*"\([a-z]*\)".*/\1/p' ../chuks.json | head -1)}"
[ -z "$ENGINE" ] && ENGINE="uikit"

if [ "$ENGINE" = "swiftui" ]; then
    echo "3. swiftc SwiftUI host (native layout; no Yoga) [iosEngine=swiftui]"
    printf '#include "libapp.h"\n' > "$OUT/app_bridge.h"
    swiftc ChuksAppSwiftUI.swift -sdk "$SIMSDK" -target "$TRIPLE" \
        -import-objc-header "$OUT/app_bridge.h" -I "$OUT" \
        "$OUT/libapp.a" -lc++ \
        -Xclang-linker -Wno-incompatible-sysroot \
        -framework SwiftUI -framework UIKit -framework Foundation -framework AVKit -parse-as-library $SWIFT_OPT $DEV_FLAG $BENCH_FLAG \
        -o "$OUT/ChuksDashboard"
else
    echo "3. swiftc UIKit host (+ Yoga flexbox engine) [iosEngine=uikit]"
    printf '#include "libapp.h"\n#include <yoga/Yoga.h>\n' > "$OUT/app_bridge.h"
    swiftc ChuksApp.swift -sdk "$SIMSDK" -target "$TRIPLE" \
        -import-objc-header "$OUT/app_bridge.h" -I "$OUT" -I "$YOGA_INC" \
        "$OUT/libapp.a" "$YOGA/libyoga.a" -lc++ \
        -Xclang-linker -Wno-incompatible-sysroot \
        -framework UIKit -framework Foundation -parse-as-library $SWIFT_OPT $DEV_FLAG $BENCH_FLAG \
        -o "$OUT/ChuksDashboard"
fi

echo "4. assemble app"
mkdir -p "$APP"; cp "$OUT/ChuksDashboard" "$APP/ChuksDashboard"
# Discover + bundle icon fonts from local assets/ and any installed package
# (chuks_packages/**/assets/*.ttf), and register each in UIAppFonts.
FONT_PLIST=""
for f in $(find ../assets ../chuks_packages -name "*.ttf" 2>/dev/null); do
    bn="$(basename "$f")"; cp "$f" "$APP/$bn"; FONT_PLIST="$FONT_PLIST<string>$bn</string>"
done
# bundle media assets (videos/images/audio) next to the executable
for f in $(find ../assets -name "*.mp4" -o -name "*.png" -o -name "*.jpg" -o -name "*.wav" -o -name "*.mp3" -o -name "*.m4a" 2>/dev/null); do cp "$f" "$APP/$(basename "$f")"; done
cat > "$APP/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>$BID</string>
  <key>CFBundleName</key><string>ChuksDashboard</string>
  <key>CFBundleDisplayName</key><string>Chuks Dashboard</string>
  <key>CFBundleExecutable</key><string>ChuksDashboard</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>LSRequiresIPhoneOS</key><true/>
  <key>MinimumOSVersion</key><string>15.0</string>
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>
  <key>NSLocalNetworkUsageDescription</key><string>Chuks dev-server hot reload.</string>
  <key>NSCameraUsageDescription</key><string>Demo: requesting camera permission (F2).</string>
  <key>NSMicrophoneUsageDescription</key><string>Demo: requesting microphone permission (F2).</string>
  <key>NSLocationWhenInUseUsageDescription</key><string>Demo: requesting location permission (F2).</string>
  <key>NSPhotoLibraryUsageDescription</key><string>Demo: requesting photo library permission (F2).</string>
  <key>UIDeviceFamily</key><array><integer>1</integer></array>
  <key>UILaunchScreen</key><dict/>
  <key>UIAppFonts</key><array>$FONT_PLIST</array>
</dict></plist>
PLIST

UDID="$(xcrun simctl list devices | awk -F'[()]' '/Booted/{print $2; exit}')"
[ -z "$UDID" ] && { echo "no booted simulator"; exit 1; }
echo "5. install + launch on $UDID"
xcrun simctl terminate "$UDID" "$BID" 2>/dev/null || true
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch "$UDID" "$BID"
