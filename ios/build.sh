#!/usr/bin/env bash
# iOS host build, provided by @chuks/mobile. Run from a Chuks project root
# (`bash chuks_packages/@chuks/mobile/ios/build.sh`, or via the chuks.json "ios"
# script). Reads the Swift host + Yoga from the installed package, AOT-compiles
# YOUR app (chuks.json entry) through the package, links a native host, and
# installs on the booted simulator. Engine: chuks.json "iosEngine" (swiftui|uikit).
set -euo pipefail
PKGDIR="$(cd "$(dirname "$0")" && pwd)"       # chuks_packages/@chuks/mobile/ios (host source)
SDKROOT="$(cd "$PKGDIR/.." && pwd)"           # chuks_packages/@chuks/mobile
PROJDIR="$(pwd)"                              # consumer project root
[ -f "$PROJDIR/chuks.json" ] || { echo "run from a Chuks project root (no chuks.json here)"; exit 1; }
ENTRY="$(sed -n 's/.*"entry"[^"]*"\([^"]*\)".*/\1/p' "$PROJDIR/chuks.json" | head -1)"
ENTRY="$PROJDIR/${ENTRY:-.chuks/entry.chuks}"
[ -f "$ENTRY" ] || { echo "no entry module at $ENTRY"; exit 1; }

export CHUKS_NO_WARNINGS=1
OUT="$PROJDIR/.chuks/ios-out"; APP="$OUT/ChuksDashboard.app"; BID="com.chuks.dashboard"
rm -rf "$OUT"; mkdir -p "$OUT"
OUTABS="$(cd "$OUT" && pwd)"   # absolute; the c-archive is compiled inside the cache dir, so its -o must be absolute
# Cold build wipes the AOT cache for reproducibility; FAST=1 (the dev loop) keeps it.
[ "${FAST:-0}" = "1" ] || rm -rf ~/.chuks/cache/builds/*
SWIFT_OPT="-O"; [ "${FAST:-0}" = "1" ] && SWIFT_OPT="-Onone"
DEV_FLAG=""; [ "${DEV:-0}" = "1" ] && DEV_FLAG="-D DEV"
BENCH_FLAG=""; [ "${BENCHMARK:-0}" = "1" ] && BENCH_FLAG="-D BENCHMARK"

SIMSDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
SIMCC="$(xcrun --sdk iphonesimulator --find clang)"
TRIPLE="arm64-apple-ios15.0-simulator"
YOGA="$PKGDIR/yoga"                       # iOS-sim arm64 libyoga.a (in the package)
YOGA_INC="$SDKROOT/core/yoga/include"     # shared Yoga headers (in the package)

echo "1. Compiling your Chuks app to native (via @chuks/mobile)"
( cd "$PROJDIR" && chuks build --c-archive "$ENTRY" -o "$OUT/e" >/dev/null )
BD="$(ls -dt "$HOME"/.chuks/cache/builds/*/ | head -1)"   # generated sources live under ~/.chuks/cache

echo "2. Building the iOS engine (arm64 simulator)"
( cd "$BD" && CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="$SIMCC -isysroot $SIMSDK -target $TRIPLE" CGO_CFLAGS="-isysroot $SIMSDK -target $TRIPLE" \
    go build -buildmode=c-archive -tags ios -o "$OUTABS/libapp.a" . )   # emits $OUT/libapp.a + $OUT/libapp.h

# Pick the iOS render engine: env IOS_ENGINE wins, else chuks.json "iosEngine", else uikit.
ENGINE="${IOS_ENGINE:-$(sed -n 's/.*"iosEngine"[^"]*"\([a-z]*\)".*/\1/p' "$PROJDIR/chuks.json" | head -1)}"
[ -z "$ENGINE" ] && ENGINE="uikit"

if [ "$ENGINE" = "swiftui" ]; then
    echo "3. Building the SwiftUI host"
    printf '#include "libapp.h"\n' > "$OUT/app_bridge.h"
    swiftc "$PKGDIR/ChuksAppSwiftUI.swift" -sdk "$SIMSDK" -target "$TRIPLE" \
        -import-objc-header "$OUT/app_bridge.h" -I "$OUT" \
        "$OUT/libapp.a" -lc++ \
        -Xclang-linker -Wno-incompatible-sysroot \
        -framework SwiftUI -framework UIKit -framework Foundation -framework AVKit -parse-as-library $SWIFT_OPT $DEV_FLAG $BENCH_FLAG \
        -o "$OUT/ChuksDashboard"
else
    echo "3. Building the UIKit host"
    printf '#include "libapp.h"\n#include <yoga/Yoga.h>\n' > "$OUT/app_bridge.h"
    swiftc "$PKGDIR/ChuksApp.swift" -sdk "$SIMSDK" -target "$TRIPLE" \
        -import-objc-header "$OUT/app_bridge.h" -I "$OUT" -I "$YOGA_INC" \
        "$OUT/libapp.a" "$YOGA/libyoga.a" -lc++ \
        -Xclang-linker -Wno-incompatible-sysroot \
        -framework UIKit -framework Foundation -parse-as-library $SWIFT_OPT $DEV_FLAG $BENCH_FLAG \
        -o "$OUT/ChuksDashboard"
fi

echo "4. Assembling the app (+ your assets)"
mkdir -p "$APP"; cp "$OUT/ChuksDashboard" "$APP/ChuksDashboard"
# -L: follow symlinks so fonts/media inside symlinked packages (local dev) are found.
FONT_PLIST=""
for f in $(find -L "$PROJDIR/assets" "$PROJDIR/chuks_packages" -name "*.ttf" 2>/dev/null); do
    bn="$(basename "$f")"; cp "$f" "$APP/$bn"; FONT_PLIST="$FONT_PLIST<string>$bn</string>"
done
for f in $(find -L "$PROJDIR/assets" -name "*.mp4" -o -name "*.png" -o -name "*.jpg" -o -name "*.wav" -o -name "*.mp3" -o -name "*.m4a" 2>/dev/null); do cp "$f" "$APP/$(basename "$f")"; done
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
echo "5. Installing + launching"
xcrun simctl terminate "$UDID" "$BID" 2>/dev/null || true
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch "$UDID" "$BID"
