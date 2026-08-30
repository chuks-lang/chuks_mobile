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
if [ "$fail" -ne 0 ]; then echo "TESTS FAILED"; exit 1; fi
echo "ALL TESTS PASSED"
