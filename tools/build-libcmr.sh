#!/usr/bin/env bash
# Maintainer-only: (re)build the prebuilt CMR runtime that ships in this package,
# so app developers never need Go or the chuks source to run `chuks dev:ios` /
# `chuks dev:android`. Run this whenever the VM (chuks cmd/cmr) or the native host
# (ios/ChuksApp.swift is compiled per-build, but android/jni.cpp + cgo_android.go
# are baked into the Android .so here) changes, then commit the updated cmr/ libs.
#
#   CHUKS_REPO=/path/to/chuks bash tools/build-libcmr.sh
#
# Prereqs: Go, Xcode (iOS), Android NDK. Outputs:
#   ios/cmr/{sim,device}/libcmr.a + ios/cmr/libcmr.h   (pure VM archive; Swift links it)
#   android/cmr/<abi>/libapp.so                          (VM + JNI + Yoga; app-independent)
set -euo pipefail
PKGDIR="$(cd "$(dirname "$0")/.." && pwd)"
CHUKS_REPO="${CHUKS_REPO:?set CHUKS_REPO to your chuks source checkout}"
[ -d "$CHUKS_REPO/cmd/cmr" ] || { echo "CHUKS_REPO=$CHUKS_REPO has no cmd/cmr"; exit 1; }
STRIP='-ldflags=-s -w'

echo "== iOS: libcmr.a (pure VM c-archive) =="
SIM_SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"; SIM_CLANG="$(xcrun --sdk iphonesimulator --find clang)"
DEV_SDK="$(xcrun --sdk iphoneos --show-sdk-path)"; DEV_CLANG="$(xcrun --sdk iphoneos --find clang)"
mkdir -p "$PKGDIR/ios/cmr/sim" "$PKGDIR/ios/cmr/device"
( cd "$CHUKS_REPO" && CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="$SIM_CLANG -isysroot $SIM_SDK -target arm64-apple-ios15.0-simulator" \
    CGO_CFLAGS="-isysroot $SIM_SDK -target arm64-apple-ios15.0-simulator" \
    go build -buildmode=c-archive -tags ios -ldflags="-s -w" -o "$PKGDIR/ios/cmr/sim/libcmr.a" ./cmd/cmr )
cp "$PKGDIR/ios/cmr/sim/libcmr.h" "$PKGDIR/ios/cmr/libcmr.h"; rm -f "$PKGDIR/ios/cmr/sim/libcmr.h"
( cd "$CHUKS_REPO" && CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="$DEV_CLANG -isysroot $DEV_SDK -target arm64-apple-ios15.0" \
    CGO_CFLAGS="-isysroot $DEV_SDK -target arm64-apple-ios15.0" \
    go build -buildmode=c-archive -tags ios -ldflags="-s -w" -o "$PKGDIR/ios/cmr/device/libcmr.a" ./cmd/cmr )
rm -f "$PKGDIR/ios/cmr/device/libcmr.h"
echo "   sim $(du -h "$PKGDIR/ios/cmr/sim/libcmr.a" | cut -f1), device $(du -h "$PKGDIR/ios/cmr/device/libcmr.a" | cut -f1)"

echo "== Android: libapp.so (VM + JNI + Yoga c-shared), per ABI =="
NDK="$HOME/Library/Android/sdk/ndk/27.1.12297006"; BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
STAGE="$CHUKS_REPO/cmd/cmr"
cleanup() { rm -f "$STAGE/jni.cpp" "$STAGE/cgo_android.go"; rm -rf "$STAGE/yoga"; }
trap cleanup EXIT
# ABI  GOARCH  clang-prefix          yoga-dir (prebuilt libyoga.a for that ABI)
android_abi() {
    local ABI="$1" GOARCH="$2" PREFIX="$3" YOGADIR="$4"
    cp "$PKGDIR/android/jni.cpp" "$PKGDIR/android/cgo_android.go" "$STAGE/"
    rm -rf "$STAGE/yoga"; mkdir -p "$STAGE/yoga"
    cp "$PKGDIR/$YOGADIR/libyoga.a" "$STAGE/yoga/"; cp -r "$PKGDIR/core/yoga/include" "$STAGE/yoga/"
    mkdir -p "$PKGDIR/android/cmr/$ABI"
    ( cd "$CHUKS_REPO" && CGO_ENABLED=1 GOOS=android GOARCH="$GOARCH" \
        CC="$BIN/${PREFIX}-clang" CXX="$BIN/${PREFIX}-clang++" CGO_CXXFLAGS="-DCMR_BUILD" \
        go build -buildmode=c-shared -ldflags="-s -w" -o "$PKGDIR/android/cmr/$ABI/libapp.so" ./cmd/cmr )
    echo "   $ABI $(du -h "$PKGDIR/android/cmr/$ABI/libapp.so" | cut -f1)"
}
android_abi arm64-v8a arm64 aarch64-linux-android24 android/yoga
android_abi x86_64     amd64 x86_64-linux-android24  android/yoga-x86_64
echo "done. Commit the updated cmr/ prebuilts."
