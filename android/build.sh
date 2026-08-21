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
[ -f "$ENTRY" ] || { echo "no entry module at $ENTRY"; exit 1; }

SDK="$HOME/Library/Android/sdk"; NDK="$SDK/ndk/27.1.12297006"
BT="$SDK/build-tools/35.0.0"; AJAR="$SDK/platforms/android-35/android.jar"
BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
CC="$BIN/aarch64-linux-android24-clang"; CXX="$BIN/aarch64-linux-android24-clang++"
ADB="$SDK/platform-tools/adb"
PKG="com.chuks.app"; OUT="$PROJDIR/.chuks/android-out"
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

echo "3. Building the Android host"
kotlinc "$PKGDIR/MainActivity.kt" -cp "$AJAR" -include-runtime -d "$OUT/app.jar" 2>&1 | grep -iE "error|warning: unable" | head || true

echo "4. Preparing app classes"
"$BT/d8" --min-api 24 --lib "$AJAR" --output "$OUT" "$OUT/app.jar"

echo "5. Linking resources"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJAR" --manifest "$PKGDIR/AndroidManifest.xml" \
    --min-sdk-version 24 --target-sdk-version 34

echo "6. Bundling the app (+ your assets)"
mkdir -p "$OUT/lib/arm64-v8a"; cp "$OUT/libapp.so" "$OUT/lib/arm64-v8a/"
CXXSHARED="$BIN/../sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
cp "$CXXSHARED" "$OUT/lib/arm64-v8a/"
# Discover + bundle icon fonts + media from the PROJECT's assets/ and installed packages.
mkdir -p "$OUT/assets"
# -L: follow symlinks so fonts/media inside symlinked packages (local dev) are found.
for f in $(find -L "$PROJDIR/assets" "$PROJDIR/chuks_packages" -name "*.ttf" 2>/dev/null); do cp "$f" "$OUT/assets/"; done
for f in $(find -L "$PROJDIR/assets" -name "*.mp4" -o -name "*.wav" -o -name "*.mp3" -o -name "*.m4a" 2>/dev/null); do cp "$f" "$OUT/assets/"; done
( cd "$OUT" && zip -qj base.apk classes.dex && zip -q base.apk lib/arm64-v8a/libapp.so lib/arm64-v8a/libc++_shared.so \
    && for tf in assets/*.ttf; do [ -e "$tf" ] && zip -q base.apk "$tf" || true; done \
    && for mv in assets/*.mp4 assets/*.wav assets/*.mp3 assets/*.m4a; do [ -e "$mv" ] && zip -0 -q base.apk "$mv" || true; done )   # -0: store media uncompressed so MediaPlayer.openFd hands back a seekable descriptor; || true so a non-matching glob doesn't trip set -e
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/chuks.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" \
    --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android "$OUT/chuks.apk"

echo "7. Installing + launching"
"$ADB" install -r "$OUT/chuks.apk"
"$ADB" shell am force-stop "$PKG" || true
"$ADB" shell am start -n "$PKG/.MainActivity"
