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
# App identity from chuks.json: name (bundle/executable), displayName (home-screen
# label), bundleId (CFBundleIdentifier). Sensible fallbacks derive from name.
pj() { sed -n "s/.*\"$1\"[^\"]*\"\([^\"]*\)\".*/\1/p" "$PROJDIR/chuks.json" | head -1; }
NAME_RAW="$(pj name)"; NAME_RAW="${NAME_RAW:-chuksapp}"
APPNAME="$(printf '%s' "$NAME_RAW" | tr -cd '[:alnum:]')"; APPNAME="${APPNAME:-ChuksApp}"
DISPLAY="$(pj displayName)"; DISPLAY="${DISPLAY:-$NAME_RAW}"
BID="$(pj bundleId)"; BID="${BID:-com.chuks.$(printf '%s' "$NAME_RAW" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]')}"
OUT="$PROJDIR/.chuks/ios-out"; APP="$OUT/$APPNAME.app"
rm -rf "$OUT"; mkdir -p "$OUT"
OUTABS="$(cd "$OUT" && pwd)"   # absolute; the c-archive is compiled inside the cache dir, so its -o must be absolute
# Cold build wipes the AOT cache for reproducibility; FAST=1 (the dev loop) keeps it.
[ "${FAST:-0}" = "1" ] || rm -rf ~/.chuks/cache/builds/*
SWIFT_OPT="-O"; [ "${FAST:-0}" = "1" ] && SWIFT_OPT="-Onone"
DEV_FLAG=""; [ "${DEV:-0}" = "1" ] && DEV_FLAG="-D DEV"
BENCH_FLAG=""; [ "${BENCHMARK:-0}" = "1" ] && BENCH_FLAG="-D BENCHMARK"

# Target: the booted simulator (default) or a paired physical device (IOS_TARGET=device).
# A device build compiles against the iphoneos SDK and is code-signed before install.
IOS_TARGET="${IOS_TARGET:-sim}"
if [ "$IOS_TARGET" = "device" ]; then
    SDKPATH="$(xcrun --sdk iphoneos --show-sdk-path)"
    CLANG="$(xcrun --sdk iphoneos --find clang)"
    TRIPLE="arm64-apple-ios15.0"
    PLATLABEL="arm64 device"
else
    SDKPATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
    CLANG="$(xcrun --sdk iphonesimulator --find clang)"
    TRIPLE="arm64-apple-ios15.0-simulator"
    PLATLABEL="arm64 simulator"
fi
# Yoga (UIKit host only): the simulator and device archives are separate arm64 builds.
YOGA="$PKGDIR/yoga"; [ "$IOS_TARGET" = "device" ] && YOGA="$PKGDIR/yoga-device"
YOGA_INC="$SDKROOT/core/yoga/include"     # shared Yoga headers (in the package)

echo "1. Compiling your Chuks app to native (via @chuks/mobile)"
( cd "$PROJDIR" && chuks build --c-archive "$ENTRY" -o "$OUT/e" >/dev/null )
BD="$(ls -dt "$HOME"/.chuks/cache/builds/*/ | head -1)"   # generated sources live under ~/.chuks/cache

echo "2. Building the iOS engine ($PLATLABEL)"
( cd "$BD" && CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="$CLANG -isysroot $SDKPATH -target $TRIPLE" CGO_CFLAGS="-isysroot $SDKPATH -target $TRIPLE" \
    go build -buildmode=c-archive -tags ios -o "$OUTABS/libapp.a" . )   # emits $OUT/libapp.a + $OUT/libapp.h

# Pick the iOS render engine: env IOS_ENGINE wins, else chuks.json "iosEngine", else uikit.
ENGINE="${IOS_ENGINE:-$(sed -n 's/.*"iosEngine"[^"]*"\([a-z]*\)".*/\1/p' "$PROJDIR/chuks.json" | head -1)}"
[ -z "$ENGINE" ] && ENGINE="uikit"

if [ "$ENGINE" = "swiftui" ]; then
    echo "3. Building the SwiftUI host"
    printf '#include "libapp.h"\n' > "$OUT/app_bridge.h"
    swiftc "$PKGDIR/ChuksAppSwiftUI.swift" -sdk "$SDKPATH" -target "$TRIPLE" \
        -import-objc-header "$OUT/app_bridge.h" -I "$OUT" \
        "$OUT/libapp.a" -lc++ \
        -Xclang-linker -Wno-incompatible-sysroot \
        -framework SwiftUI -framework UIKit -framework Foundation -framework AVKit -parse-as-library $SWIFT_OPT $DEV_FLAG $BENCH_FLAG \
        -o "$OUT/$APPNAME"
else
    echo "3. Building the UIKit host"
    printf '#include "libapp.h"\n#include <yoga/Yoga.h>\n' > "$OUT/app_bridge.h"
    swiftc "$PKGDIR/ChuksApp.swift" -sdk "$SDKPATH" -target "$TRIPLE" \
        -import-objc-header "$OUT/app_bridge.h" -I "$OUT" -I "$YOGA_INC" \
        "$OUT/libapp.a" "$YOGA/libyoga.a" -lc++ \
        -Xclang-linker -Wno-incompatible-sysroot \
        -framework UIKit -framework Foundation -parse-as-library $SWIFT_OPT $DEV_FLAG $BENCH_FLAG \
        -o "$OUT/$APPNAME"
fi

echo "4. Assembling the app (+ your assets)"
mkdir -p "$APP"; cp "$OUT/$APPNAME" "$APP/$APPNAME"
# DEV hot reload: the host reads the dev server address from this file. The simulator
# reaches it at localhost; a real device reaches the Mac over Wi-Fi at its LAN IP (auto-
# detected, overridable with IOS_DEV_HOST).
if [ "${DEV:-0}" = "1" ]; then
    if [ -n "${IOS_DEV_HOST:-}" ]; then DEVHOST="$IOS_DEV_HOST"
    elif [ "$IOS_TARGET" = "device" ]; then DEVHOST="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo localhost):7799"
    else DEVHOST="localhost:7799"; fi
    printf '%s' "$DEVHOST" > "$APP/chuks-dev.txt"
    echo "   hot reload: app will fetch from $DEVHOST"
fi
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
  <key>CFBundleName</key><string>$APPNAME</string>
  <key>CFBundleDisplayName</key><string>$DISPLAY</string>
  <key>CFBundleExecutable</key><string>$APPNAME</string>
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

if [ "$IOS_TARGET" = "device" ]; then
    echo "5. Signing for your device"
    # Signing identity (Apple Development) + its team.
    IDENTITY="$(security find-identity -v -p codesigning 2>/dev/null | awk '/Apple Development/{print $2; exit}')"
    [ -n "$IDENTITY" ] || { echo "no 'Apple Development' signing identity found (open Xcode > Settings > Accounts)"; exit 1; }
    TEAM="$(security find-certificate -c 'Apple Development' -p 2>/dev/null | openssl x509 -noout -subject -nameopt multiline 2>/dev/null | awk -F'= ' '/organizationalUnit/{print $2}')"
    # A development provisioning profile with a wildcard app id that covers this team,
    # so any Chuks bundle id installs. Xcode maintains these under UserData.
    PROFILE=""
    for p in "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"/*.mobileprovision; do
        [ -e "$p" ] || continue
        pl="$(security cms -D -i "$p" 2>/dev/null)"
        [ "$(echo "$pl" | plutil -extract Entitlements.get-task-allow raw - 2>/dev/null)" = "true" ] || continue
        [ "$(echo "$pl" | plutil -extract TeamIdentifier.0 raw - 2>/dev/null)" = "$TEAM" ] || continue
        case "$(echo "$pl" | plutil -extract Entitlements.application-identifier raw - 2>/dev/null)" in
            *".*") PROFILE="$p"; break ;;
        esac
    done
    [ -n "$PROFILE" ] || { echo "no wildcard development provisioning profile for team $TEAM (build once in Xcode to create one)"; exit 1; }
    cp "$PROFILE" "$APP/embedded.mobileprovision"
    cat > "$OUT/ent.plist" <<ENT
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>application-identifier</key><string>$TEAM.$BID</string>
  <key>com.apple.developer.team-identifier</key><string>$TEAM</string>
  <key>get-task-allow</key><true/>
</dict></plist>
ENT
    codesign --force --sign "$IDENTITY" --entitlements "$OUT/ent.plist" --generate-entitlement-der --timestamp=none "$APP"
    echo "6. Installing + launching on your device"
    # First connected+available device; exclude "unavailable" (substring match trap) and
    # let IOS_DEVICE_ID override when more than one is attached.
    DEVID="${IOS_DEVICE_ID:-$(xcrun devicectl list devices 2>/dev/null | awk '!/unavailable/ && /available/{for(i=1;i<=NF;i++) if($i ~ /^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-/){print $i; exit}}')}"
    [ -n "$DEVID" ] || { echo "no available paired device (connect an iPhone, unlock it, and trust this Mac)"; exit 1; }
    xcrun devicectl device install app --device "$DEVID" "$APP" >/dev/null
    xcrun devicectl device process launch --device "$DEVID" "$BID" >/dev/null && echo "   launched on device"
else
    UDID="$(xcrun simctl list devices | awk -F'[()]' '/Booted/{print $2; exit}')"
    [ -z "$UDID" ] && { echo "no booted simulator"; exit 1; }
    echo "5. Installing + launching"
    xcrun simctl terminate "$UDID" "$BID" 2>/dev/null || true
    xcrun simctl install "$UDID" "$APP"
    xcrun simctl launch "$UDID" "$BID"
fi
