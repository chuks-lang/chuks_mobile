#!/usr/bin/env bash
# iOS CMR build: instead of AOT-compiling the app, this links the Chuks Mobile
# Runtime (libcmr — the VM behind the chuks_* C ABI) and bakes a chukspack
# source bundle into the app. The VM interprets it on-device. Run from a Chuks
# project root:  bash chuks_packages/@chuks/mobile/ios/build-cmr.sh
# Env: CHUKS_REPO (path to the chuks source repo), CMR_APP_ENTRY (default app/app.chuks).
set -euo pipefail
PKGDIR="$(cd "$(dirname "$0")" && pwd)"
SDKROOT="$(cd "$PKGDIR/.." && pwd)"
PROJDIR="$(pwd)"
[ -f "$PROJDIR/chuks.json" ] || { echo "run from a Chuks project root"; exit 1; }
CHUKS_REPO="${CHUKS_REPO:-/Users/chukwuemekaigbokwe/Box/Code/Chuks}"
APP_ENTRY="${CMR_APP_ENTRY:-$PROJDIR/app/app.chuks}"
[ -f "$APP_ENTRY" ] || { echo "no app entry at $APP_ENTRY (needs to export createRoot)"; exit 1; }
[ -d "$CHUKS_REPO/cmd/cmr" ] || { echo "CHUKS_REPO=$CHUKS_REPO has no cmd/cmr"; exit 1; }
export CHUKS_NO_WARNINGS=1

# ---- app identity (same as build.sh) ----
pj() { sed -n "s/.*\"$1\"[^\"]*\"\([^\"]*\)\".*/\1/p" "$PROJDIR/chuks.json" | head -1; }
NAME_RAW="$(pj name)"; NAME_RAW="${NAME_RAW:-chuksapp}"
APPNAME="$(printf '%s' "$NAME_RAW" | tr -cd '[:alnum:]')"; APPNAME="${APPNAME:-ChuksApp}"
DISPLAY="$(pj displayName)"; DISPLAY="${DISPLAY:-$NAME_RAW}"
BID="$(pj bundleId)"; BID="${BID:-com.chuks.$(printf '%s' "$NAME_RAW" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]')}"
AJ() { chuks run "$SDKROOT/appconfig.chuks" "$PROJDIR" "$1" 2>/dev/null; }
_ajn="$(AJ name)";        [ -n "$_ajn" ] && { NAME_RAW="$_ajn"; APPNAME="$(printf '%s' "$_ajn" | tr -cd '[:alnum:]')"; }
_ajd="$(AJ displayName)"; [ -n "$_ajd" ] && DISPLAY="$_ajd"
_ajb="$(AJ ios-bundle)";  [ -n "$_ajb" ] && BID="$_ajb"
APP_VERSION="$(AJ version)"; APP_VERSION="${APP_VERSION:-1.0}"
APP_BUILD="$(AJ build)";     APP_BUILD="${APP_BUILD:-1}"

OUT="$PROJDIR/.chuks/ios-cmr-out"; APP="$OUT/$APPNAME.app"
rm -rf "$OUT"; mkdir -p "$OUT"; OUTABS="$(cd "$OUT" && pwd)"

# ---- simulator toolchain ----
SDKPATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
CLANG="$(xcrun --sdk iphonesimulator --find clang)"
TRIPLE="arm64-apple-ios15.0-simulator"
YOGA="$PKGDIR/yoga"; YOGA_INC="$SDKROOT/core/yoga/include"

echo "1. Building CMR (libcmr.a) for the simulator"
( cd "$CHUKS_REPO" && CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="$CLANG -isysroot $SDKPATH -target $TRIPLE" CGO_CFLAGS="-isysroot $SDKPATH -target $TRIPLE" \
    go build -buildmode=c-archive -tags ios -o "$OUTABS/libcmr.a" ./cmd/cmr )

echo "2. Packing the source bundle (chukspack)"
chuks pack "$APP_ENTRY" -o "$OUTABS/cmr.bundle"
mkdir -p "$APP"; cp "$OUTABS/cmr.bundle" "$APP/cmr.bundle"
echo "   bundle: $(grep -c '^--- module:' "$APP/cmr.bundle") modules, $(wc -c < "$APP/cmr.bundle") bytes"

# CMR dev hot reload (DEV=1): point the app at `chukspack serve` so it fetches the
# source bundle over HTTP and re-boots the on-device VM on each .chuks change. The
# baked bundle above stays as a first-launch fallback if the server is down. The
# simulator reaches the Mac at localhost; a device needs the Mac's LAN IP (IOS_DEV_HOST).
if [ "${DEV:-0}" = "1" ]; then
    DEVHOST="${IOS_DEV_HOST:-localhost:7799}"
    printf '%s' "$DEVHOST" > "$APP/cmr-dev.txt"
    echo "   CMR dev: app will fetch the bundle from $DEVHOST (first run: chuks dev)"
fi

echo "3. Building the UIKit host (CMR mode)"
printf '#include "libcmr.h"\n#include <yoga/Yoga.h>\n' > "$OUT/app_bridge.h"
swiftc "$PKGDIR/ChuksApp.swift" "$PKGDIR/ChuksEffects.swift" -sdk "$SDKPATH" -target "$TRIPLE" \
    -import-objc-header "$OUT/app_bridge.h" -I "$OUT" -I "$YOGA_INC" \
    "$OUT/libcmr.a" "$YOGA/libyoga.a" -lc++ \
    -Xclang-linker -Wno-incompatible-sysroot \
    -framework UIKit -framework Foundation -parse-as-library -Onone -D CMR \
    -o "$OUT/$APPNAME"
cp "$OUT/$APPNAME" "$APP/$APPNAME"   # the .app's executable (CFBundleExecutable)

echo "4. Assembling the app (+ assets)"
IOS_PLIST_EXTRA="$(AJ ios-plist)"
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
  <key>CFBundleName</key><string>$APPNAME</string>
  <key>CFBundleDisplayName</key><string>$DISPLAY</string>
  <key>CFBundleExecutable</key><string>$APPNAME</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleVersion</key><string>$APP_BUILD</string>
  <key>CFBundleShortVersionString</key><string>$APP_VERSION</string>
  <key>LSRequiresIPhoneOS</key><true/>
  <key>MinimumOSVersion</key><string>15.0</string>
$IOS_PLIST_EXTRA
  <key>UIDeviceFamily</key><array><integer>1</integer></array>
  <key>UISupportedInterfaceOrientations</key>
  <array><string>UIInterfaceOrientationPortrait</string></array>
  <key>UILaunchScreen</key><dict/>
  <key>UIAppFonts</key><array>$FONT_PLIST</array>
</dict></plist>
PLIST

echo "5. Installing + launching (CMR — the VM runs on the device)"
UDID="$(xcrun simctl list devices | awk -F'[()]' '/Booted/{print $2; exit}')"
[ -z "$UDID" ] && { echo "no booted simulator"; exit 1; }
xcrun simctl terminate "$UDID" "$BID" 2>/dev/null || true
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch "$UDID" "$BID"
echo "   launched $BID on $UDID"
