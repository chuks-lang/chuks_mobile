#!/usr/bin/env bash
# Framework tests: run every tests/*_test.chuks headlessly. A test prints its
# results and throws (non-zero exit) on any failing assertion, so this script
# fails fast and is CI-gateable.
#
#   bash tests/run.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export CHUKS_NO_WARNINGS=1
CHUKS="${CHUKS:-$HOME/chuks/bin/chuks}"

fail=0
for t in tests/*_test.chuks; do
    [ -e "$t" ] || continue
    echo "=== $t ==="
    if ! "$CHUKS" run "$t"; then fail=1; fi
done

# Both iOS host builds must typecheck: the CMR dev build every simulator session runs,
# and the release build (`chuks ios`, ios:device, ios:release) that nothing else runs
# until a device build. Seven seconds, needs Xcode; skipped where there is none.
if [ "$(uname -s)" = "Darwin" ] && xcrun --sdk iphonesimulator --show-sdk-path >/dev/null 2>&1; then
    echo "=== tools/host-typecheck.sh ==="
    if ! bash tools/host-typecheck.sh; then fail=1; fi
fi

# Static guard: every wire style key must have a documented reset story, so a new
# prop can't reintroduce the reused-node stale-state bug class (Stage 0). See
# tests/style_reset_coverage.py and docs/ui-update-model-vs-rn.md.
echo "=== tests/style_reset_coverage.py ==="
if ! python3 tests/style_reset_coverage.py; then fail=1; fi

# Host parity (HOST_PARITY=1): the fixture app in tests/host on the booted simulator
# and the running emulator, its view trees diffed against the engine's layout and
# against each other. Opt-in because it needs both devices up; see tools/host-parity.sh.
if [ "${HOST_PARITY:-0}" = "1" ]; then
    echo "=== tools/host-parity.sh ==="
    if ! bash tools/host-parity.sh; then fail=1; fi
fi

if [ "$fail" -ne 0 ]; then echo "TESTS FAILED"; exit 1; fi
echo "ALL TESTS PASSED"
