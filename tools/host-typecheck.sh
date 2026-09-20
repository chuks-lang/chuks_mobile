#!/usr/bin/env bash
# Typecheck the iOS host in BOTH of its builds: the CMR dev build (-D CMR, what every
# simulator session runs) and the release build (what `chuks ios`, `ios:device` and
# `ios:release` compile). A symbol that exists only under `#if CMR` and is used outside
# it breaks the release build alone, which nothing ran for four days once. No app, no
# simulator, no install: `swiftc -typecheck` against the package's own headers (the CMR
# header carries the whole host C ABI, so the release variant checks against it too).
#
#   bash tools/host-typecheck.sh      (runs from tests/run.sh on macOS)
set -euo pipefail
PKGDIR="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$PKGDIR/ios"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
printf '#include "libcmr.h"\n#include <yoga/Yoga.h>\n' > "$OUT/app_bridge.h"
cat > "$OUT/ChuksPackageModules.swift" <<'S'
func chuksPackageModules() -> [ChuksNativeModule.Type] { [] }
func chuksPackageViews() -> [ChuksNativeView.Type] { [] }
S
SDKPATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
TRIPLE="arm64-apple-ios15.0-simulator"
check() {
    local label="$1"; shift
    if swiftc -typecheck "$IOS/ChuksApp.swift" "$IOS/ChuksEffects.swift" "$IOS/ChuksModule.swift" "$OUT/ChuksPackageModules.swift" \
        -sdk "$SDKPATH" -target "$TRIPLE" -import-objc-header "$OUT/app_bridge.h" -I "$IOS/cmr" -I "$PKGDIR/core/yoga/include" \
        -parse-as-library "$@" > "$OUT/$label.log" 2>&1; then
        echo "  ok   iOS host typechecks ($label)"
    else
        echo "  FAIL iOS host does not typecheck ($label):"; grep -m5 "error:" "$OUT/$label.log" | sed 's/^/       /'; return 1
    fi
}
rc=0
check "release" || rc=1
check "cmr dev" -D CMR || rc=1
exit $rc
