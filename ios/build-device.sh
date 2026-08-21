#!/usr/bin/env bash
# Device variant of build.sh: cross-compiles the Chuks engine + UIKit host for a
# REAL iOS device (arm64, iphoneos SDK), code-signs the hand-assembled .app with
# a CLESET-team wildcard development profile, and installs it on iPhone Lex.
# Used for the on-device performance head-to-head against the RN feed.
set -euo pipefail
cd "$(dirname "$0")"

export CHUKS_NO_WARNINGS=1
OUT=".out-device"; APP="$OUT/ChuksDashboard.app"; BID="com.chuks.dashboard"
TEAM="JG5JPFALCG"
IDENTITY="Apple Development: Chukwuemeka Igbokwe (SC6MW5T5S7)"
PROFILE="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles/9a6f0d09-9f7c-4389-89db-708860bd247f.mobileprovision"
DEVICE="00008101-000570C214E9001E"    # iPhone Lex
rm -rf "$OUT"; mkdir -p "$OUT"
OUTABS="$(cd "$OUT" && pwd)"   # absolute; the c-archive is compiled inside the cache dir, so its -o must be absolute
[ "${FAST:-0}" = "1" ] || rm -rf ~/.chuks/cache/builds/*
SWIFT_OPT="-O"; [ "${FAST:-0}" = "1" ] && SWIFT_OPT="-Onone"

SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
CC="$(xcrun --sdk iphoneos --find clang)"
TRIPLE="arm64-apple-ios15.0"          # device (no -simulator suffix)
YOGA="$(pwd)/yoga-device"             # iphoneos arm64 libyoga.a
YOGA_INC="$(cd ../core/yoga/include && pwd)"

echo "1. chuks AOT -> Go (generated Go stays in the cache, never in the project)"
chuks build --c-archive ../core/entry.chuks -o "$OUT/e" >/dev/null
BD="$(ls -dt "$HOME"/.chuks/cache/builds/*/ | head -1)"   # generated Go lives here, under ~/.chuks/cache

echo "2. c-archive for iOS device (compiled in place in the cache; only artifacts land in $OUT)"
( cd "$BD" && CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="$CC -isysroot $SDK -target $TRIPLE" CGO_CFLAGS="-isysroot $SDK -target $TRIPLE" \
    go build -buildmode=c-archive -tags ios -o "$OUTABS/libapp.a" . )   # emits $OUT/libapp.a + $OUT/libapp.h

echo "3. swiftc UIKit host (+ Yoga flexbox engine)"
printf '#include "libapp.h"\n#include <yoga/Yoga.h>\n' > "$OUT/app_bridge.h"
swiftc ChuksApp.swift -sdk "$SDK" -target "$TRIPLE" \
    -import-objc-header "$OUT/app_bridge.h" -I "$OUT" -I "$YOGA_INC" \
    "$OUT/libapp.a" "$YOGA/libyoga.a" -lc++ \
    -Xclang-linker -Wno-incompatible-sysroot \
    -framework UIKit -framework Foundation -parse-as-library $SWIFT_OPT \
    -o "$OUT/ChuksDashboard"

echo "4. assemble app"
mkdir -p "$APP"; cp "$OUT/ChuksDashboard" "$APP/ChuksDashboard"
FONT_PLIST=""
for f in $(find ../assets ../chuks_packages -name "*.ttf" 2>/dev/null); do
    bn="$(basename "$f")"; cp "$f" "$APP/$bn"; FONT_PLIST="$FONT_PLIST<string>$bn</string>"
done
for f in $(find ../assets -name "*.mp4" -o -name "*.png" -o -name "*.jpg" 2>/dev/null); do cp "$f" "$APP/$(basename "$f")"; done
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
  <key>UIDeviceFamily</key><array><integer>1</integer></array>
  <key>UILaunchScreen</key><dict/>
  <key>UIAppFonts</key><array>$FONT_PLIST</array>
</dict></plist>
PLIST

echo "5. code sign for device"
cp "$PROFILE" "$APP/embedded.mobileprovision"
cat > "$OUT/entitlements.plist" <<ENT
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>application-identifier</key><string>$TEAM.$BID</string>
  <key>com.apple.developer.team-identifier</key><string>$TEAM</string>
  <key>get-task-allow</key><true/>
</dict></plist>
ENT
codesign --force --sign "$IDENTITY" --entitlements "$OUT/entitlements.plist" --timestamp=none "$APP"
codesign --verify --verbose "$APP"

echo "6. install + launch on iPhone Lex"
xcrun devicectl device install app --device "$DEVICE" "$APP"
xcrun devicectl device process launch --device "$DEVICE" "$BID" || true
echo "done."
