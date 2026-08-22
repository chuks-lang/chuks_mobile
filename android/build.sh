#!/usr/bin/env bash
# Android host build, provided by @chuks/mobile. Run from a Chuks project root
# (`bash chuks_packages/@chuks/mobile/android/build.sh`, or via the chuks.json
# "android" script). It reads the Kotlin/JNI host + Yoga from the installed
# package, AOT-compiles YOUR app (chuks.json entry) through the package, and
# assembles + installs an APK. No Gradle (the JDK here is newer than AGP supports).
set -euo pipefail
PKGDIR="$(cd "$(dirname "$0")" && pwd)"       # chuks_packages/@chuks/mobile/android (host source)
SDKROOT="$(cd "$PKGDIR/.." && pwd)"           # chuks_packages/@chuks/mobile
PROJDIR="$(pwd)"                              # consumer project root
[ -f "$PROJDIR/chuks.json" ] || { echo "run from a Chuks project root (no chuks.json here)"; exit 1; }
ENTRY="$(sed -n 's/.*"entry"[^"]*"\([^"]*\)".*/\1/p' "$PROJDIR/chuks.json" | head -1)"
ENTRY="$PROJDIR/${ENTRY:-.chuks/entry.chuks}"
[ -n "${CHUKS_ENTRY:-}" ] && ENTRY="$PROJDIR/$CHUKS_ENTRY"   # build an arbitrary entry (docshots / examples)
[ -f "$ENTRY" ] || { echo "no entry module at $ENTRY"; exit 1; }

SDK="$HOME/Library/Android/sdk"; NDK="$SDK/ndk/27.1.12297006"
BT="$SDK/build-tools/35.0.0"; AJAR="$SDK/platforms/android-35/android.jar"
BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
CC="$BIN/aarch64-linux-android24-clang"; CXX="$BIN/aarch64-linux-android24-clang++"
ADB="$SDK/platform-tools/adb"
# App identity from chuks.json: displayName (launcher label), bundleId (applicationId).
# CODEPKG is the host's fixed Kotlin package; --rename-manifest-package keeps components
# resolving to it while the app installs under APPID.
pj() { sed -n "s/.*\"$1\"[^\"]*\"\([^\"]*\)\".*/\1/p" "$PROJDIR/chuks.json" | head -1; }
NAME_RAW="$(pj name)"; NAME_RAW="${NAME_RAW:-chuksapp}"
DISPLAY="$(pj displayName)"; DISPLAY="${DISPLAY:-$NAME_RAW}"
CODEPKG="com.chuks.app"
APPID="$(pj bundleId)"; APPID="${APPID:-com.chuks.$(printf '%s' "$NAME_RAW" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]')}"
# app.json (RN/Expo-style) supersedes chuks.json for name/displayName and is the source of
# version, deep-link schemes, and which permissions the manifest declares. Optional.
AJ() { chuks run "$SDKROOT/appconfig.chuks" "$PROJDIR" "$1" 2>/dev/null; }
_ajn="$(AJ name)";        [ -n "$_ajn" ] && NAME_RAW="$_ajn"
_ajd="$(AJ displayName)"; [ -n "$_ajd" ] && DISPLAY="$_ajd"
_ajbid="$(AJ ios-bundle)"; [ -n "$_ajbid" ] && APPID="$_ajbid"   # single app id across platforms
# Chuks Preview: the generic runtime host. ConnectActivity is the launcher; a package-local
# stub supplies the engine symbols (never called — Preview always talks to a dev server).
PREVIEW="${PREVIEW:-0}"
LAUNCH_ACTIVITY="MainActivity"
if [ "$PREVIEW" = "1" ]; then
    DISPLAY="Chuks Preview"; APPID="com.chuks.preview"; LAUNCH_ACTIVITY="ConnectActivity"
    ENTRY="$SDKROOT/ios/preview-stub.chuks"
fi
PKG="$APPID"; OUT="$PROJDIR/.chuks/android-out"
export CHUKS_NO_WARNINGS=1
rm -rf "$OUT" ~/.chuks/cache/builds/*; mkdir -p "$OUT"
OUTABS="$(cd "$OUT" && pwd)"   # absolute; the .so is compiled inside the cache dir, so its -o must be absolute

echo "1. Compiling your Chuks app to native (via @chuks/mobile)"
( cd "$PROJDIR" && chuks build --c-archive "$ENTRY" -o "$OUT/e" >/dev/null )   # --c-archive emits the chuks_* C-ABI bridge
BD="$(ls -dt "$HOME"/.chuks/cache/builds/*/ | head -1)"   # generated sources live here, under ~/.chuks/cache
# Stage the JNI bridge + cgo link flags + Yoga (from the PACKAGE) next to the generated Go.
cp "$PKGDIR/jni.cpp" "$PKGDIR/cgo_android.go" "$BD/"
mkdir -p "$BD/yoga"; cp "$PKGDIR/yoga/libyoga.a" "$BD/yoga/"; cp -r "$SDKROOT/core/yoga/include" "$BD/yoga/"

echo "2. Building the Android engine (arm64)"
( cd "$BD" && CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$CC" CXX="$CXX" \
    go build -buildmode=c-shared -o "$OUTABS/libapp.so" . )

echo "3. Building the Android host${PREVIEW:+ (Chuks Preview)}"
KT_SRC="$PKGDIR/MainActivity.kt"; KT_CP="$AJAR"; ZXING="$PKGDIR/libs/zxing-core.jar"
if [ "$PREVIEW" = "1" ]; then
    KT_SRC="$KT_SRC $PKGDIR/ConnectActivity.kt $PKGDIR/ScannerActivity.kt"   # + in-app QR scanner
    [ -f "$ZXING" ] && KT_CP="$AJAR:$ZXING"
fi
if ! kotlinc $KT_SRC -cp "$KT_CP" -include-runtime -d "$OUT/app.jar" > "$OUT/kotlinc.log" 2>&1; then
    echo "  Build failed:"; grep -iE "error:" "$OUT/kotlinc.log" | head -20; exit 1
fi

echo "4. Preparing app classes"
# The bundled dexer floods benign Kotlin-metadata warnings (its metadata parser is
# older than the compiler that produced the stdlib); the output is still valid, and
# it ran fine in every test. Capture it and only surface a genuine failure.
D8_EXTRA=""; [ "$PREVIEW" = "1" ] && [ -f "$ZXING" ] && D8_EXTRA="$ZXING"   # dex the ZXing scanner lib
if ! "$BT/d8" --min-api 24 --lib "$AJAR" --output "$OUT" "$OUT/app.jar" $D8_EXTRA > "$OUT/d8.log" 2>&1; then
    echo "  Failed to prepare app classes:"
    grep -viE "kotlin.?Metadata|Should never be called|ForkJoin|^[[:space:]]+at (com\.android|java\.base)|kotlinx-metadata|newer version of kotlin|rewriting of Kotlin" "$OUT/d8.log" | tail -20
    exit 1
fi

echo "5. Linking resources"
# Per-project manifest: override the launcher label; --rename-manifest-package sets the
# applicationId while keeping CODEPKG for component (.MainActivity) resolution.
RESZIP=""
if [ "$PREVIEW" = "1" ]; then
    cp "$PKGDIR/AndroidManifest-preview.xml" "$OUT/AndroidManifest.xml"
    # Launcher icon: the Chuks logo rendered at each density into res/mipmap-*.
    if [ -f "$PKGDIR/preview-icon.png" ]; then
        for d in "mdpi 48" "hdpi 72" "xhdpi 96" "xxhdpi 144" "xxxhdpi 192"; do
            set -- $d; mkdir -p "$OUT/res/mipmap-$1"
            sips -z "$2" "$2" "$PKGDIR/preview-icon.png" --out "$OUT/res/mipmap-$1/ic_launcher.png" >/dev/null 2>&1
        done
        "$BT/aapt2" compile --dir "$OUT/res" -o "$OUT/res.zip" >/dev/null 2>&1 && RESZIP="$OUT/res.zip"
    fi
else
    cp "$PKGDIR/AndroidManifest.xml" "$OUT/AndroidManifest.xml"
    sed -i '' "s#android:label=\"Chuks\"#android:label=\"$DISPLAY\"#" "$OUT/AndroidManifest.xml"
    # app.json -> fill the manifest's permission block, deep-link schemes, and version
    chuks run "$SDKROOT/appconfig.chuks" "$PROJDIR" patch-manifest "$OUT/AndroidManifest.xml" 2>/dev/null
fi
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJAR" --manifest "$OUT/AndroidManifest.xml" \
    $RESZIP --rename-manifest-package "$APPID" \
    --min-sdk-version 24 --target-sdk-version 34

echo "6. Bundling the app (+ your assets)"
mkdir -p "$OUT/lib/arm64-v8a"; cp "$OUT/libapp.so" "$OUT/lib/arm64-v8a/"
CXXSHARED="$BIN/../sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
cp "$CXXSHARED" "$OUT/lib/arm64-v8a/"
# Discover + bundle icon fonts + media from the PROJECT's assets/ and installed packages.
mkdir -p "$OUT/assets"
# Chuks Preview: the transparent logo shown on the connect screen.
[ "$PREVIEW" = "1" ] && [ -f "$PKGDIR/preview-logo.png" ] && cp "$PKGDIR/preview-logo.png" "$OUT/assets/ChuksLogo.png"
# DEV hot reload (DEV=1): point the app at the running dev server (chuks watch). An
# emulator reaches the host loopback via 10.0.2.2; a real device reaches the Mac over
# Wi-Fi at its LAN IP (needs the dev server bound to 0.0.0.0, which it is).
if [ "${DEV:-0}" = "1" ]; then
    DEV_ID="$("$ADB" devices | awk '/\tdevice$/{print $1; exit}')"
    case "$DEV_ID" in
        emulator-*) DEV_HOST="10.0.2.2" ;;   # emulator's alias for the host loopback
        *) # Real device: tunnel its localhost to the Mac over the adb (USB) link — no Wi-Fi,
           # LAN IP, or firewall needed. Fall back to the Mac's LAN IP if the tunnel fails.
           if "$ADB" reverse tcp:7799 tcp:7799 >/dev/null 2>&1; then DEV_HOST="localhost"
           else DEV_HOST="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo 10.0.2.2)"; fi ;;
    esac
    printf '%s:7799' "$DEV_HOST" > "$OUT/assets/chuks-dev.txt"
    echo "   hot reload: app will fetch from $DEV_HOST:7799"
fi
# -L: follow symlinks so fonts/media inside symlinked packages (local dev) are found.
for f in $(find -L "$PROJDIR/assets" "$PROJDIR/chuks_packages" -name "*.ttf" 2>/dev/null); do cp "$f" "$OUT/assets/"; done
for f in $(find -L "$PROJDIR/assets" \( -name "*.mp4" -o -name "*.wav" -o -name "*.mp3" -o -name "*.m4a" -o -name "*.png" -o -name "*.jpg" \) 2>/dev/null); do cp "$f" "$OUT/assets/"; done
( cd "$OUT" && zip -qj base.apk classes.dex && zip -q base.apk lib/arm64-v8a/libapp.so lib/arm64-v8a/libc++_shared.so \
    && { [ -e assets/chuks-dev.txt ] && zip -q base.apk assets/chuks-dev.txt || true; } \
    && for tf in assets/*.ttf; do [ -e "$tf" ] && zip -q base.apk "$tf" || true; done \
    && for im in assets/*.png assets/*.jpg; do [ -e "$im" ] && zip -q base.apk "$im" || true; done \
    && for mv in assets/*.mp4 assets/*.wav assets/*.mp3 assets/*.m4a; do [ -e "$mv" ] && zip -0 -q base.apk "$mv" || true; done )   # -0 (media): seekable for MediaPlayer.openFd; images stay compressed   # -0: store media uncompressed so MediaPlayer.openFd hands back a seekable descriptor; || true so a non-matching glob doesn't trip set -e
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/chuks.apk" > "$OUT/zipalign.log" 2>&1 || { echo "  Alignment failed:"; cat "$OUT/zipalign.log"; exit 1; }
# apksigner emits benign JVM restricted-method warnings on newer JDKs; hide unless it fails.
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" \
    --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android "$OUT/chuks.apk" > "$OUT/apksigner.log" 2>&1 \
    || { echo "  Signing failed:"; cat "$OUT/apksigner.log"; exit 1; }

echo "7. Installing + launching"
"$ADB" install -r "$OUT/chuks.apk" > "$OUT/adb.log" 2>&1 || { echo "  Install failed:"; cat "$OUT/adb.log"; exit 1; }
"$ADB" shell am force-stop "$PKG" > /dev/null 2>&1 || true
"$ADB" shell am start -n "$PKG/$CODEPKG.$LAUNCH_ACTIVITY" > /dev/null 2>&1
echo "   Done."
