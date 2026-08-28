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

echo "== Android: libapp.so (VM + JNI + Yoga c-shared) =="
NDK="$HOME/Library/Android/sdk/ndk/27.1.12297006"; BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
STAGE="$CHUKS_REPO/cmd/cmr"
cp "$PKGDIR/android/jni.cpp" "$PKGDIR/android/cgo_android.go" "$STAGE/"
mkdir -p "$STAGE/yoga"; cp "$PKGDIR/android/yoga/libyoga.a" "$STAGE/yoga/"; cp -r "$PKGDIR/core/yoga/include" "$STAGE/yoga/"
cleanup() { rm -f "$STAGE/jni.cpp" "$STAGE/cgo_android.go"; rm -rf "$STAGE/yoga"; }
trap cleanup EXIT
mkdir -p "$PKGDIR/android/cmr/arm64-v8a"
( cd "$CHUKS_REPO" && CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
    CC="$BIN/aarch64-linux-android24-clang" CXX="$BIN/aarch64-linux-android24-clang++" CGO_CXXFLAGS="-DCMR_BUILD" \
    go build -buildmode=c-shared -ldflags="-s -w" -o "$PKGDIR/android/cmr/arm64-v8a/libapp.so" ./cmd/cmr )
echo "   arm64-v8a $(du -h "$PKGDIR/android/cmr/arm64-v8a/libapp.so" | cut -f1)"
echo "done. Commit the updated cmr/ prebuilts."
