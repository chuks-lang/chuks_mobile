#!/usr/bin/env bash
# Android CMR build: links the Chuks Mobile Runtime (libcmr — the VM behind the
# AOT-compatible chuks_* C ABI) instead of AOT-compiling the app, and bakes a
# chukspack source bundle the VM interprets on-device. Run from a Chuks project
# root:  bash chuks_packages/@chuks/mobile/android/build-cmr.sh
# Env: CMR_APP_ENTRY (default app/app.chuks). No Go or chuks source needed: the
# CMR runtime ships prebuilt in this package (cmr/) and bundling uses `chuks pack`.
set -euo pipefail
PKGDIR="$(cd "$(dirname "$0")" && pwd)"
SDKROOT="$(cd "$PKGDIR/.." && pwd)"
PROJDIR="$(pwd)"
[ -f "$PROJDIR/chuks.json" ] || { echo "run from a Chuks project root"; exit 1; }
APP_ENTRY="${CMR_APP_ENTRY:-$PROJDIR/app/app.chuks}"
[ -f "$APP_ENTRY" ] || { echo "no app entry at $APP_ENTRY (needs to export createRoot)"; exit 1; }
export CHUKS_NO_WARNINGS=1

SDK="$HOME/Library/Android/sdk"; NDK="$SDK/ndk/27.1.12297006"
BT="$SDK/build-tools/35.0.0"; AJAR="$SDK/platforms/android-35/android.jar"
BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
ADB="$SDK/platform-tools/adb"
# Match the connected device/emulator ABI (arm64 on real phones and Apple-Silicon
# emulators; x86_64 on emulators run on Intel Mac / Windows / Linux). Override with
# CMR_ABI. The prebuilt libapp.so + libc++_shared.so are picked for this ABI.
if [ -n "${CMR_ABI:-}" ]; then ABI="$CMR_ABI"; else
    _dev="$("$ADB" devices | awk '/\tdevice$/{print $1; exit}')"
    ABI="$("$ADB" -s "${_dev:-none}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
    ABI="${ABI:-arm64-v8a}"
fi
case "$ABI" in
    x86_64) CXXLIB="x86_64-linux-android" ;;
    *)      ABI="arm64-v8a"; CXXLIB="aarch64-linux-android" ;;
esac
echo "   target ABI: $ABI"

pj() { sed -n "s/.*\"$1\"[^\"]*\"\([^\"]*\)\".*/\1/p" "$PROJDIR/chuks.json" | head -1; }
NAME_RAW="$(pj name)"; NAME_RAW="${NAME_RAW:-chuksapp}"
DISPLAY="$(pj displayName)"; DISPLAY="${DISPLAY:-$NAME_RAW}"
CODEPKG="com.chuks.app"
APPID="$(pj bundleId)"; APPID="${APPID:-com.chuks.$(printf '%s' "$NAME_RAW" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]')}"
AJ() { chuks run "$SDKROOT/appconfig.chuks" "$PROJDIR" "$1" 2>/dev/null; }
_ajb="$(AJ ios-bundle)"; [ -n "$_ajb" ] && APPID="$_ajb"

OUT="$PROJDIR/.chuks/android-cmr-out"; rm -rf "$OUT"; mkdir -p "$OUT/assets"; OUTABS="$(cd "$OUT" && pwd)"

echo "1. Using the prebuilt CMR runtime shipped with the package (Android/$ABI)"
# The native layer (VM + JNI + Yoga = libapp.so) is app-independent, so it ships
# prebuilt per package version. No Go, no chuks source; only the Kotlin host below
# is compiled per build.
CMRLIB="$PKGDIR/cmr/$ABI/libapp.so"
[ -f "$CMRLIB" ] || { echo "prebuilt libapp.so missing at $CMRLIB (ABI $ABI; rebuild via tools/build-libcmr.sh)"; exit 1; }
cp "$CMRLIB" "$OUTABS/libapp.so"

echo "2. Packing the source bundle (chukspack) -> assets/cmr.bundle"
chuks pack "$APP_ENTRY" -o "$OUTABS/assets/cmr.bundle"
echo "   bundle: $(grep -c '^--- module:' "$OUT/assets/cmr.bundle") modules"

# CMR dev hot reload (DEV=1): point the app at `chukspack serve` so it fetches the
# source bundle over HTTP and re-boots the on-device VM on each .chuks change. The
# baked bundle above stays as a fallback for the first launch if the server is down.
if [ "${DEV:-0}" = "1" ]; then
    DEV_ID="$("$ADB" devices | awk '/\tdevice$/{print $1; exit}')"
    # Prefer an `adb reverse` tunnel (works for emulators AND USB devices): the app
    # then talks to localhost, which handles the held-open /hmr long-poll reliably.
    # The emulator's 10.0.2.2 NAT stalls long-polls, so use it only as a fallback.
    if "$ADB" -s "$DEV_ID" reverse tcp:7799 tcp:7799 >/dev/null 2>&1; then
        DEV_HOST="localhost"
    else
        case "$DEV_ID" in
            emulator-*) DEV_HOST="10.0.2.2" ;;
            *)          DEV_HOST="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo 10.0.2.2)" ;;
        esac
    fi
    printf '%s:7799' "$DEV_HOST" > "$OUTABS/assets/cmr-dev.txt"
    echo "   CMR dev: app will fetch the bundle from $DEV_HOST:7799 (first run: chuks dev)"
fi

echo "3. Building the Android host (Kotlin)"
kotlinc "$PKGDIR/MainActivity.kt" "$PKGDIR/ChuksEffects.kt" -cp "$AJAR" -include-runtime -d "$OUT/app.jar" > "$OUT/kotlinc.log" 2>&1 \
    || { echo "  kotlin build failed:"; grep -iE "error:" "$OUT/kotlinc.log" | head -20; exit 1; }

echo "4. Dexing"
"$BT/d8" --min-api 24 --lib "$AJAR" --output "$OUT" "$OUT/app.jar" > "$OUT/d8.log" 2>&1 \
    || { echo "  dex failed:"; grep -viE "Metadata|kotlin|ForkJoin|^\s+at " "$OUT/d8.log" | tail -20; exit 1; }

echo "5. Linking resources"
cp "$PKGDIR/AndroidManifest.xml" "$OUT/AndroidManifest.xml"
sed -i '' "s#android:label=\"Chuks\"#android:label=\"$DISPLAY\"#" "$OUT/AndroidManifest.xml"
chuks run "$SDKROOT/appconfig.chuks" "$PROJDIR" patch-manifest "$OUT/AndroidManifest.xml" 2>/dev/null || true
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJAR" --manifest "$OUT/AndroidManifest.xml" \
    --rename-manifest-package "$APPID" --min-sdk-version 24 --target-sdk-version 34

echo "6. Bundling (+ assets, libcmr as libapp.so)"
mkdir -p "$OUT/lib/$ABI"; cp "$OUT/libapp.so" "$OUT/lib/$ABI/"
cp "$BIN/../sysroot/usr/lib/$CXXLIB/libc++_shared.so" "$OUT/lib/$ABI/"
# Fonts are referenced by family name, so they flatten. Images keep their path
# relative to assets/, so you can organize them in subfolders and reference them as
# src:"sub/dir/name.png" (basenames no longer collide across folders).
for f in $(find -L "$PROJDIR/assets" "$PROJDIR/chuks_packages" -name "*.ttf" 2>/dev/null); do cp "$f" "$OUT/assets/"; done
find -L "$PROJDIR/assets" \( -name "*.png" -o -name "*.jpg" \) 2>/dev/null | while IFS= read -r f; do
    rel="${f#"$PROJDIR/assets/"}"; mkdir -p "$OUT/assets/$(dirname "$rel")"; cp "$f" "$OUT/assets/$rel"
done
( cd "$OUT" && zip -qj base.apk classes.dex \
    && zip -q base.apk "lib/$ABI/libapp.so" "lib/$ABI/libc++_shared.so" \
    && zip -q base.apk assets/cmr.bundle \
    && { [ -e assets/cmr-dev.txt ] && zip -q base.apk assets/cmr-dev.txt || true; } \
    && for tf in assets/*.ttf; do [ -e "$tf" ] && zip -q base.apk "$tf" || true; done \
    && find assets \( -name "*.png" -o -name "*.jpg" \) -type f | while IFS= read -r im; do zip -q base.apk "$im"; done )
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/chuks.apk" > "$OUT/zipalign.log" 2>&1

echo "7. Signing + installing + launching (CMR — the VM runs on the device)"
[ -f "$HOME/.android/debug.keystore" ] || keytool -genkey -v -keystore "$HOME/.android/debug.keystore" \
    -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android "$OUT/chuks.apk" >/dev/null 2>&1
DEV_ID="$("$ADB" devices | awk '/\tdevice$/{print $1; exit}')"
[ -z "$DEV_ID" ] && { echo "no android device/emulator (adb devices)"; exit 1; }
"$ADB" -s "$DEV_ID" install -r "$OUT/chuks.apk"
"$ADB" -s "$DEV_ID" shell am start -n "$APPID/$CODEPKG.MainActivity"
echo "   launched $APPID on $DEV_ID"
