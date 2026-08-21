#!/usr/bin/env bash
# Android host build: the SAME engine.chuks -> an Android .so, a JNI bridge +
# Yoga, and a Kotlin host, assembled into an APK by hand (no Gradle: the JDK here
# is newer than AGP supports). Installs + launches on the booted emulator.
set -euo pipefail
cd "$(dirname "$0")"

SDK="$HOME/Library/Android/sdk"; NDK="$SDK/ndk/27.1.12297006"
BT="$SDK/build-tools/35.0.0"; AJAR="$SDK/platforms/android-35/android.jar"
BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
CC="$BIN/aarch64-linux-android24-clang"; CXX="$BIN/aarch64-linux-android24-clang++"
ADB="$SDK/platform-tools/adb"
KSTDLIB="$(find /opt/homebrew -name kotlin-stdlib.jar 2>/dev/null | head -1)"
PKG="com.chuks.app"; OUT=".out"
export CHUKS_NO_WARNINGS=1
rm -rf "$OUT" ~/.chuks/cache/builds/*; mkdir -p "$OUT"
OUTABS="$(cd "$OUT" && pwd)"   # absolute; the .so is compiled inside the cache dir, so its -o must be absolute

echo "1. chuks AOT -> Go  (the SAME engine.chuks as the iOS host; generated Go stays in the cache, never in the project)"
chuks build --c-archive ../core/entry.chuks -o "$OUT/e" >/dev/null   # --c-archive emits the chuks_* C-ABI bridge
BD="$(ls -dt "$HOME"/.chuks/cache/builds/*/ | head -1)"   # generated Go lives here, under ~/.chuks/cache
# Stage the JNI bridge + cgo link flags + Yoga next to the generated Go, in the cache
# dir (cgo resolves ${SRCDIR} to this dir), so nothing Go-side ends up in the project.
cp jni.cpp cgo_android.go "$BD/"
mkdir -p "$BD/yoga"; cp yoga/libyoga.a "$BD/yoga/"; cp -r ../core/yoga/include "$BD/yoga/"

echo "2. engine + JNI + Yoga -> libapp.so (android arm64; compiled in place, only the .so lands in $OUT)"
( cd "$BD" && CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$CC" CXX="$CXX" \
    go build -buildmode=c-shared -o "$OUTABS/libapp.so" . )
file "$OUT/libapp.so" | cut -d, -f1-2

echo "3. kotlinc -> jar"
kotlinc MainActivity.kt -cp "$AJAR" -include-runtime -d "$OUT/app.jar" 2>&1 | grep -iE "error|warning: unable" | head || true

echo "4. d8 -> dex"
"$BT/d8" --min-api 24 --lib "$AJAR" --output "$OUT" "$OUT/app.jar"

echo "5. aapt2 link -> base.apk"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJAR" --manifest AndroidManifest.xml \
    --min-sdk-version 24 --target-sdk-version 34

echo "6. package dex + .so (+ libc++_shared) into the apk"
mkdir -p "$OUT/lib/arm64-v8a"; cp "$OUT/libapp.so" "$OUT/lib/arm64-v8a/"
CXXSHARED="$BIN/../sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
cp "$CXXSHARED" "$OUT/lib/arm64-v8a/"
# Discover + bundle icon fonts from local assets/ and installed packages.
mkdir -p "$OUT/assets"
for f in $(find ../assets ../chuks_packages -name "*.ttf" 2>/dev/null); do cp "$f" "$OUT/assets/"; done
for f in $(find ../assets -name "*.mp4" -o -name "*.wav" -o -name "*.mp3" -o -name "*.m4a" 2>/dev/null); do cp "$f" "$OUT/assets/"; done
( cd "$OUT" && zip -qj base.apk classes.dex && zip -q base.apk lib/arm64-v8a/libapp.so lib/arm64-v8a/libc++_shared.so \
    && for tf in assets/*.ttf; do [ -e "$tf" ] && zip -q base.apk "$tf"; done \
    && for mv in assets/*.mp4 assets/*.wav assets/*.mp3 assets/*.m4a; do [ -e "$mv" ] && zip -0 -q base.apk "$mv" || true; done )   # -0: store media uncompressed so MediaPlayer.openFd hands back a seekable descriptor; `|| true` so a non-matching glob doesn't trip set -e
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/chuks.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" \
    --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android "$OUT/chuks.apk"

echo "7. install + launch on the emulator"
"$ADB" install -r "$OUT/chuks.apk"
"$ADB" shell am force-stop "$PKG" || true
"$ADB" shell am start -n "$PKG/.MainActivity"
