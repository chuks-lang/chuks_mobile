#!/usr/bin/env bash
# iOS host build, provided by @chuks/mobile. Run from a Chuks project root
# (`bash chuks_packages/@chuks/mobile/ios/build.sh`, or via the chuks.json "ios"
# script). Reads the Swift host + Yoga from the installed package, AOT-compiles
# YOUR app (chuks.json entry) through the package, links a native host, and
# installs on the simulator (booting one if none is running). iOS renders through
# the UIKit host + Yoga.
set -euo pipefail
PKGDIR="$(cd "$(dirname "$0")" && pwd)"       # chuks_packages/@chuks/mobile/ios (host source)
SDKROOT="$(cd "$PKGDIR/.." && pwd)"           # chuks_packages/@chuks/mobile
PROJDIR="$(pwd)"                              # consumer project root
[ -f "$PROJDIR/chuks.json" ] || { echo "run from a Chuks project root (no chuks.json here)"; exit 1; }
ENTRY="$(sed -n 's/.*"entry"[^"]*"\([^"]*\)".*/\1/p' "$PROJDIR/chuks.json" | head -1)"
ENTRY="$PROJDIR/${ENTRY:-.chuks/entry.chuks}"
[ -n "${CHUKS_ENTRY:-}" ] && ENTRY="$PROJDIR/$CHUKS_ENTRY"   # build an arbitrary entry (docshots / examples)
[ -f "$ENTRY" ] || { echo "no entry module at $ENTRY"; exit 1; }

export CHUKS_NO_WARNINGS=1
# App identity from chuks.json: name (bundle/executable), displayName (home-screen
# label), bundleId (CFBundleIdentifier). Sensible fallbacks derive from name.
pj() { sed -n "s/.*\"$1\"[^\"]*\"\([^\"]*\)\".*/\1/p" "$PROJDIR/chuks.json" | head -1; }
NAME_RAW="$(pj name)"; NAME_RAW="${NAME_RAW:-chuksapp}"
APPNAME="$(printf '%s' "$NAME_RAW" | tr -cd '[:alnum:]')"; APPNAME="${APPNAME:-ChuksApp}"
DISPLAY="$(pj displayName)"; DISPLAY="${DISPLAY:-$NAME_RAW}"
BID="$(pj bundleId)"; BID="${BID:-com.chuks.$(printf '%s' "$NAME_RAW" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]')}"
# app.json (RN/Expo-style): the app's own identity + native config. Supersedes chuks.json
# for name/displayName/bundleId, and is the SOLE source for version, URL schemes, and the
# permission usage strings. Optional — every field falls back to a default.
AJ() { chuks run "$SDKROOT/appconfig.chuks" "$PROJDIR" "$1" 2>/dev/null; }
_ajn="$(AJ name)";        [ -n "$_ajn" ] && { NAME_RAW="$_ajn"; APPNAME="$(printf '%s' "$_ajn" | tr -cd '[:alnum:]')"; }
_ajd="$(AJ displayName)"; [ -n "$_ajd" ] && DISPLAY="$_ajd"
_ajb="$(AJ ios-bundle)";  [ -n "$_ajb" ] && BID="$_ajb"
APP_VERSION="$(AJ version)"; APP_VERSION="${APP_VERSION:-1.0}"
APP_BUILD="$(AJ build)";     APP_BUILD="${APP_BUILD:-1}"
# Chuks Preview: the generic runtime host (Expo Go for Chuks). Reuses this project's
# entry only to supply the engine symbols (never called — Preview always talks to a dev
# server), overrides the app identity, registers the chuks:// URL scheme, and compiles
# the Preview Swift host (ChuksPreview.swift) with its connect/scan screen.
PREVIEW="${PREVIEW:-0}"
if [ "$PREVIEW" = "1" ]; then
    DEV=1
    APPNAME="ChuksPreview"; DISPLAY="Chuks Preview"; BID="com.chuks.preview"
    # Preview links no app of its own; a tiny package-local stub supplies the engine
    # symbols (never called in DEV). This decouples the build from the consumer app.
    ENTRY="$PKGDIR/preview-stub.chuks"
fi
OUT="$PROJDIR/.chuks/ios-out"; APP="$OUT/$APPNAME.app"
rm -rf "$OUT"; mkdir -p "$OUT"
OUTABS="$(cd "$OUT" && pwd)"   # absolute; the c-archive is compiled inside the cache dir, so its -o must be absolute
# shellcheck source=../buildcache.sh
source "$SDKROOT/buildcache.sh"
# Cold build wipes the AOT cache for reproducibility; FAST=1 (the dev loop) keeps it.
[ "${FAST:-0}" = "1" ] || chuks_clear_build_cache
SWIFT_OPT="-O"; [ "${FAST:-0}" = "1" ] && SWIFT_OPT="-Onone"
DEV_FLAG=""; [ "${DEV:-0}" = "1" ] && DEV_FLAG="-D DEV"
BENCH_FLAG=""; [ "${BENCHMARK:-0}" = "1" ] && BENCH_FLAG="-D BENCHMARK"
SAN_FLAG=""; [ "${ASAN:-0}" = "1" ] && SAN_FLAG="-sanitize=address -g"   # AddressSanitizer diagnostic build

# Target: the simulator (default) or a paired physical device (IOS_TARGET=device).
# A device build compiles against the iphoneos SDK and is code-signed before install.
# shellcheck source=simulator.sh
source "$PKGDIR/simulator.sh"
IOS_TARGET="${IOS_TARGET:-sim}"
# Boot the simulator up front so it is ready by the time the build finishes.
[ "$IOS_TARGET" = "device" ] || chuks_ensure_sim || exit 1
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
BDSTAMP="$OUT/.build-started"; : > "$BDSTAMP"   # only cache dirs newer than this are ours
( cd "$PROJDIR" && chuks build --c-archive "$ENTRY" -o "$OUT/e" >/dev/null )
BD="$(chuks_latest_build_dir "$BDSTAMP")"        # generated sources, under ~/.chuks/cache
[ -n "$BD" ] && [ -f "$BD/go.mod" ] || {
    echo "  the Chuks build produced no Go sources in $CHUKS_BUILD_CACHE"
    echo "  (rerun without >/dev/null on the chuks build above to see why)"; exit 1; }

echo "2. Building the iOS engine ($PLATLABEL)"
( cd "$BD" && CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="$CLANG -isysroot $SDKPATH -target $TRIPLE" CGO_CFLAGS="-isysroot $SDKPATH -target $TRIPLE" \
    go build -buildmode=c-archive -tags ios -o "$OUTABS/libapp.a" . )   # emits $OUT/libapp.a + $OUT/libapp.h

# iOS renders through the UIKit host (ChuksApp.swift) + Yoga.
PREVIEW_LABEL=""; [ "$PREVIEW" = "1" ] && PREVIEW_LABEL=" (Chuks Preview)"
echo "3. Building the UIKit host$PREVIEW_LABEL"
printf '#include "libapp.h"\n#include <yoga/Yoga.h>\n' > "$OUT/app_bridge.h"
# Preview adds its connect/scan entry; CHUKS_PREVIEW_UIKIT selects the UIKit render gate
# (drops the per-app @main, hands the connect screen off to CardsVC). The connect/scan
# chrome is plain UIKit; the scanner links AVKit for the camera capture stack.
PREVIEW_SRC=""; PREVIEW_FLAG=""; PREVIEW_FW=""
[ "$PREVIEW" = "1" ] && { PREVIEW_SRC="$PKGDIR/ChuksPreview.swift"; PREVIEW_FLAG="-D CHUKS_PREVIEW -D CHUKS_PREVIEW_UIKIT"; PREVIEW_FW="-framework AVKit"; }

# Native capabilities from installed packages. A package declares its Swift in its own
# chuks.json (see appconfig.chuks); the sources compile into the app beside the host, and
# a generated registry hands them the commands whose namespace they claim. This is the
# whole of "autolinking" here, because the build calls swiftc directly: there is no
# CocoaPods or Xcode project to teach.
PKG_SRC="$(chuks run "$SDKROOT/appconfig.chuks" "$PROJDIR" mobile-sources ios 2>/dev/null | tr '\n' ' ')"
{
    echo "// GENERATED by ios/build.sh. Every installed package that ships a native"
    echo "// capability for iOS, so the registry can route commands to it. An app with no"
    echo "// such package gets an empty list and pays nothing."
    echo "func chuksPackageModules() -> [ChuksNativeModule.Type] { ["
    chuks run "$SDKROOT/appconfig.chuks" "$PROJDIR" mobile-modules ios 2>/dev/null |
        while IFS="$(printf '\t')" read -r ns cls; do [ -n "$cls" ] && echo "    $cls.self,"; done
    echo "] }"
} > "$OUT/ChuksPackageModules.swift"
[ -n "$PKG_SRC" ] && echo "   native packages: $(echo $PKG_SRC | wc -w | tr -d ' ') source file(s)"

swiftc "$PKGDIR/ChuksApp.swift" "$PKGDIR/ChuksEffects.swift" "$PKGDIR/ChuksModule.swift" "$OUT/ChuksPackageModules.swift" $PKG_SRC $PREVIEW_SRC -sdk "$SDKPATH" -target "$TRIPLE" \
    -import-objc-header "$OUT/app_bridge.h" -I "$OUT" -I "$YOGA_INC" \
    "$OUT/libapp.a" "$YOGA/libyoga.a" -lc++ \
    -Xclang-linker -Wno-incompatible-sysroot \
    -framework UIKit -framework Foundation $PREVIEW_FW -parse-as-library $SWIFT_OPT $SAN_FLAG $DEV_FLAG $BENCH_FLAG $PREVIEW_FLAG \
    -o "$OUT/$APPNAME"

echo "4. Assembling the app (+ your assets)"
mkdir -p "$APP"; cp "$OUT/$APPNAME" "$APP/$APPNAME"
# DEV hot reload: the host reads the dev server address from this file. The simulator
# reaches it at localhost; a real device reaches the Mac over Wi-Fi at its LAN IP (auto-
# detected, overridable with IOS_DEV_HOST).
# Preview picks its server at runtime (scan/enter), so it bundles no fixed host.
if [ "${DEV:-0}" = "1" ] && [ "$PREVIEW" != "1" ]; then
    if [ -n "${IOS_DEV_HOST:-}" ]; then DEVHOST="$IOS_DEV_HOST"
    elif [ "$IOS_TARGET" = "device" ]; then DEVHOST="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo localhost):7799"
    else DEVHOST="localhost:7799"; fi
    printf '%s' "$DEVHOST" > "$APP/chuks-dev.txt"
    echo "   hot reload: app will fetch from $DEVHOST"
fi
# Per-app plist keys: permission usage strings + URL schemes. A real build gets them from
# app.json (via appconfig.chuks); Chuks Preview overrides with its scanner copy + chuks:// scheme.
# The launch assets an app declares in app.json. Preview keeps using the packaged logo,
# since it IS the framework's own app rather than someone's project.
APP_ICON=""; SPLASH_IMG=""; SPLASH_BG=""; SPLASH_BG_DARK=""
if [ "$PREVIEW" != "1" ]; then
    APP_ICON="$(AJ icon)"
    SPLASH_IMG="$(AJ splash-image)"
    SPLASH_BG="$(AJ splash-bg)"
    SPLASH_BG_DARK="$(AJ splash-bg-dark)"
fi
ICON_SRC="$APP_ICON"
[ "$PREVIEW" = "1" ] && ICON_SRC="$PKGDIR/preview-icon.png"

# Background tasks: iOS refuses to register an identifier that is not declared here, and
# refuses to run one at all without the matching UIBackgroundModes. Both come from the
# app.json list, so an app declares its tasks once.
RESTORE_WINDOW="$(AJ state-restore-window)"; [ -n "$RESTORE_WINDOW" ] || RESTORE_WINDOW=1800
BGTASK_PLIST=""
BG_IDS="$(AJ background-tasks)"
if [ -n "$BG_IDS" ]; then
    BG_ARRAY=""
    for t in $BG_IDS; do BG_ARRAY="$BG_ARRAY<string>$t</string>"; done
    BGTASK_PLIST="  <key>BGTaskSchedulerPermittedIdentifiers</key><array>$BG_ARRAY</array>
  <key>UIBackgroundModes</key><array><string>fetch</string><string>processing</string></array>"
fi

ICONNAME_PLIST=""
[ -n "$ICON_SRC" ] && [ -f "$ICON_SRC" ] && ICONNAME_PLIST='<key>CFBundleIconName</key><string>AppIcon</string>'
if [ "$PREVIEW" = "1" ]; then
    IOS_PLIST_EXTRA='  <key>NSCameraUsageDescription</key><string>Scan a Chuks dev-server QR code to run your app.</string>
  <key>NSBluetoothAlwaysUsageDescription</key><string>Scan for nearby Bluetooth devices in previewed apps.</string>
  <key>NFCReaderUsageDescription</key><string>Read NFC tags in previewed apps.</string>
  <key>CFBundleURLTypes</key><array><dict><key>CFBundleURLName</key><string>com.chuks.preview</string><key>CFBundleURLSchemes</key><array><string>chuks</string></array></dict></array>'
else
    IOS_PLIST_EXTRA="$(AJ ios-plist)"
fi
# -L: follow symlinks so fonts/media inside symlinked packages (local dev) are found.
FONT_PLIST=""
for f in $(find -L "$PROJDIR/assets" "$PROJDIR/chuks_packages" -name "*.ttf" 2>/dev/null); do
    bn="$(basename "$f")"; cp "$f" "$APP/$bn"; FONT_PLIST="$FONT_PLIST<string>$bn</string>"
done
# Media assets keep their path relative to assets/ (organize in subfolders, reference
# as src:"sub/dir/name.ext"); the host resolves them against the .app bundle path.
find -L "$PROJDIR/assets" \( -name "*.mp4" -o -name "*.png" -o -name "*.jpg" -o -name "*.wav" -o -name "*.mp3" -o -name "*.m4a" \) 2>/dev/null | { while IFS= read -r f; do
    rel="${f#"$PROJDIR/assets/"}"; mkdir -p "$APP/$(dirname "$rel")"; cp "$f" "$APP/$rel"
done; } || true   # a project with no assets/ dir is fine: find exits non-zero, not fatal
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
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>
  <key>NSLocalNetworkUsageDescription</key><string>Chuks dev-server hot reload.</string>
$IOS_PLIST_EXTRA
  <key>UIDeviceFamily</key><array><integer>1</integer></array>
  <key>UISupportedInterfaceOrientations</key>
  <array>
    <string>UIInterfaceOrientationPortrait</string>
    <string>UIInterfaceOrientationLandscapeLeft</string>
    <string>UIInterfaceOrientationLandscapeRight</string>
  </array>
  <key>UILaunchScreen</key><dict/>
  <key>ChuksStateRestoreWindow</key><integer>$RESTORE_WINDOW</integer>
$BGTASK_PLIST
  $ICONNAME_PLIST
  <key>UIAppFonts</key><array>$FONT_PLIST</array>
  <!-- Which entitlement-bearing capabilities this build is actually signed for. The
       device branch below fills it in from the provisioning profile; a simulator build
       is signed for none, and the host says so rather than guessing. -->
  <key>ChuksGrantedEntitlements</key><array/>
</dict></plist>
PLIST

# The home-screen icon and the launch screen, compiled into one asset catalog.
#
# iOS wants a single 1024 source and generates every idiom from it, so an app declares
# one file and gets the whole set. The launch screen is a plist dict rather than a
# storyboard (iOS 14+), which is why the image and the colour both have to live in the
# catalog: UILaunchScreen refers to them BY NAME, and a name that is not in the catalog
# silently produces a blank launch instead of an error.
[ "$PREVIEW" = "1" ] && [ -f "$PKGDIR/preview-logo.png" ] && cp "$PKGDIR/preview-logo.png" "$APP/ChuksLogo.png"   # transparent logo for the connect screen
if [ -n "$ICON_SRC" ] && [ -f "$ICON_SRC" ] || [ -n "$SPLASH_IMG" ] || [ -n "$SPLASH_BG" ]; then
    mkdir -p "$OUT/Assets.xcassets"
    printf '{"info":{"author":"xcode","version":1}}' > "$OUT/Assets.xcassets/Contents.json"
    if [ -n "$ICON_SRC" ] && [ -f "$ICON_SRC" ]; then
        ICONSET="$OUT/Assets.xcassets/AppIcon.appiconset"
        mkdir -p "$ICONSET"
        # actool wants a square source; sips normalises whatever the app supplied so a
        # non-1024 or non-square icon is a warning in the log rather than a broken build.
        sips -z 1024 1024 "$ICON_SRC" --out "$ICONSET/icon.png" >/dev/null 2>&1 || cp "$ICON_SRC" "$ICONSET/icon.png"
        printf '{"images":[{"filename":"icon.png","idiom":"universal","platform":"ios","size":"1024x1024"}],"info":{"author":"xcode","version":1}}' > "$ICONSET/Contents.json"
    fi
    if [ -n "$SPLASH_IMG" ] && [ -f "$SPLASH_IMG" ]; then
        SPLASHSET="$OUT/Assets.xcassets/SplashImage.imageset"
        mkdir -p "$SPLASHSET"
        cp "$SPLASH_IMG" "$SPLASHSET/splash.png"
        printf '{"images":[{"filename":"splash.png","idiom":"universal","scale":"1x"},{"idiom":"universal","scale":"2x"},{"idiom":"universal","scale":"3x"}],"info":{"author":"xcode","version":1}}' > "$SPLASHSET/Contents.json"
    fi
    if [ -n "$SPLASH_BG" ]; then
        COLORSET="$OUT/Assets.xcassets/SplashBackground.colorset"
        mkdir -p "$COLORSET"
        # A colorset carries both appearances, so the launch screen follows dark mode
        # without the app running: the OS picks before a single line of ours executes.
        python3 - "$COLORSET/Contents.json" "$SPLASH_BG" "${SPLASH_BG_DARK:-$SPLASH_BG}" <<'PYCOLOR'
import json, sys
out, light, dark = sys.argv[1], sys.argv[2], sys.argv[3]
def rgb(h):
    h = h.lstrip("#")
    if len(h) == 3: h = "".join(c * 2 for c in h)
    return {"red": f"0x{h[0:2]}", "green": f"0x{h[2:4]}", "blue": f"0x{h[4:6]}", "alpha": "1.000"}
def entry(hexv, appearances=None):
    e = {"idiom": "universal", "color": {"color-space": "srgb", "components": rgb(hexv)}}
    if appearances: e["appearances"] = appearances
    return e
doc = {"colors": [entry(light),
                  entry(dark, [{"appearance": "luminosity", "value": "dark"}])],
       "info": {"author": "xcode", "version": 1}}
json.dump(doc, open(out, "w"), indent=2)
PYCOLOR
    fi
    ACT_PLAT=iphonesimulator; [ "$IOS_TARGET" = "device" ] && ACT_PLAT=iphoneos
    ACT_ICON=""; [ -n "$ICON_SRC" ] && [ -f "$ICON_SRC" ] && ACT_ICON="--app-icon AppIcon"
    actool "$OUT/Assets.xcassets" --compile "$APP" $ACT_ICON \
        --output-partial-info-plist "$OUT/icon.plist" \
        --platform "$ACT_PLAT" --minimum-deployment-target 15.0 --target-device iphone >/dev/null 2>&1
    # actool emits the runtime icon PNGs + Assets.car into the bundle AND a partial plist
    # holding the CFBundleIcons dict (CFBundleIconFiles) that SpringBoard needs to find
    # them. Merge that into Info.plist — without it the app shows the default placeholder.
    if [ -f "$OUT/icon.plist" ] && ls "$APP"/AppIcon*.png >/dev/null 2>&1; then
        /usr/libexec/PlistBuddy -c "Merge $OUT/icon.plist" "$APP/Info.plist" >/dev/null 2>&1
        echo "   home-screen icon compiled (Assets.car + CFBundleIcons)"
    fi
    # The launch screen: a colour, an optional centred image, both named from the catalog.
    if [ -n "$SPLASH_BG" ] || [ -n "$SPLASH_IMG" ]; then
        LS_ENTRIES=""
        [ -n "$SPLASH_BG" ] && LS_ENTRIES="$LS_ENTRIES  <key>UIColorName</key><string>SplashBackground</string>"
        [ -n "$SPLASH_IMG" ] && [ -f "$SPLASH_IMG" ] && LS_ENTRIES="$LS_ENTRIES  <key>UIImageName</key><string>SplashImage</string><key>UIImageRespectsSafeAreaInsets</key><true/>"
        /usr/libexec/PlistBuddy -c "Delete :UILaunchScreen" "$APP/Info.plist" >/dev/null 2>&1
        cat > "$OUT/launch.plist" <<LSP
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>UILaunchScreen</key><dict>$LS_ENTRIES</dict>
</dict></plist>
LSP
        /usr/libexec/PlistBuddy -c "Merge $OUT/launch.plist" "$APP/Info.plist" >/dev/null 2>&1
        echo "   launch screen configured"
    fi
fi

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
    # Entitlements the app's declared permissions imply: HealthKit for "health",
    # the NFC reader formats for "nfc". Only those two kinds need one; everything
    # else on the bus is a usage string.
    #
    # Each is filtered against what THIS profile actually carries, because codesign
    # fails outright on an entitlement the profile lacks. A wildcard profile carries
    # neither, so an app that declares nfc still builds and installs, and is told once
    # that the capability will not work until it has an explicit App ID. Emitting them
    # unconditionally would stop such an app reaching a device at all; emitting them
    # never is how NFC came to be shipped, documented, and dead on every device.
    PROF_ENT="$(security cms -D -i "$PROFILE" 2>/dev/null | plutil -extract Entitlements xml1 -o - - 2>/dev/null)"
    WANT_ENT="$(chuks run "$SDKROOT/appconfig.chuks" "$PROJDIR" ios-entitlements 2>/dev/null)"
    # No HEALTHKIT=1 switch any more: HealthKit lives in @chuks/health, and installing
    # that package declares the permission, which is what produces the entitlement here.
    EXTRA_ENT=""; DROPPED_ENT=""
    while IFS="$(printf '\t')" read -r ent_key ent_val; do
        [ -n "$ent_key" ] || continue
        case "$PROF_ENT" in
            *"<key>$ent_key</key>"*) EXTRA_ENT="$EXTRA_ENT  <key>$ent_key</key>$ent_val
" ;;
            *) DROPPED_ENT="$DROPPED_ENT $ent_key" ;;
        esac
    done <<WANTENT
$WANT_ENT
WANTENT
    if [ -n "$DROPPED_ENT" ]; then
        echo "   note: this provisioning profile carries none of:$DROPPED_ENT"
        echo "         The app builds and installs, but those capabilities will not work"
        echo "         on device. A wildcard profile can never carry an entitlement: to"
        echo "         use them, register an explicit App ID for $BID at developer.apple.com"
        echo "         with the capability enabled, then build once in Xcode."
    fi
    # Tell the running app which of these it actually got. Without this the host has
    # to guess: iOS reports NFC as simply unavailable when the entitlement is missing,
    # which is indistinguishable at runtime from a device that has no NFC reader.
    plutil -replace ChuksGrantedEntitlements -json '[]' "$APP/Info.plist" >/dev/null 2>&1
    printf '%s' "$EXTRA_ENT" | sed -n 's/^  <key>\(.*\)<\/key>.*/\1/p' | while read -r granted; do
        [ -n "$granted" ] && plutil -insert ChuksGrantedEntitlements -string "$granted" -append "$APP/Info.plist" >/dev/null 2>&1
    done
    cp "$PROFILE" "$APP/embedded.mobileprovision"
    cat > "$OUT/ent.plist" <<ENT
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>application-identifier</key><string>$TEAM.$BID</string>
  <key>com.apple.developer.team-identifier</key><string>$TEAM</string>
  <key>get-task-allow</key><true/>
$EXTRA_ENT</dict></plist>
ENT
    if ! codesign --force --sign "$IDENTITY" --entitlements "$OUT/ent.plist" --generate-entitlement-der --timestamp=none "$APP" 2>"$OUT/codesign.log"; then
        echo "  Signing failed:"; sed 's/^/    /' "$OUT/codesign.log"; exit 1
    fi
    echo "6. Installing + launching on your device"
    # First connected+available device; exclude "unavailable" (substring match trap) and
    # let IOS_DEVICE_ID override when more than one is attached.
    DEVID="${IOS_DEVICE_ID:-$(xcrun devicectl list devices 2>/dev/null | awk '!/unavailable/ && /available/{for(i=1;i<=NF;i++) if($i ~ /^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-/){print $i; exit}}')}"
    [ -n "$DEVID" ] || { echo "no available paired device (connect an iPhone, unlock it, and trust this Mac)"; exit 1; }
    xcrun devicectl device install app --device "$DEVID" "$APP" >/dev/null
    xcrun devicectl device process launch --device "$DEVID" "$BID" >/dev/null && echo "   launched on device"
else
    chuks_ensure_sim || exit 1
    echo "5. Installing + launching"
    xcrun simctl terminate "$UDID" "$BID" 2>/dev/null || true
    xcrun simctl install "$UDID" "$APP"
    xcrun simctl launch "$UDID" "$BID"
fi
