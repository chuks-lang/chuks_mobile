#!/usr/bin/env bash
# The real "Chuks vs RN" number: the SAME memoized reconcile (2000 rows, ~100
# change/frame, 30000 frames) in Chuks (native), V8 (Node), and Hermes (the
# engine React Native actually ships). Checksums must match across all three.
#
# Needs: chuks, node, and a standalone hermes runtime. Get hermes with:
#   curl -sL https://github.com/facebook/hermes/releases/download/v0.13.0/hermes-cli-darwin.tar.gz | tar xz
# then set HERMES to the extracted ./hermes binary.
set -euo pipefail
cd "$(dirname "$0")"
export CHUKS_NO_WARNINGS=1
HERMES="${HERMES:-/tmp/hermes_cli/hermes}"
FRAMES=30000

chuks build recon.chuks -o /tmp/recon_aot >/dev/null
chuks build empty.chuks -o /tmp/empty_aot >/dev/null

echo "== Chuks (native, AOT) =="
/tmp/recon_aot          # times itself in-process, like the JS versions

echo "== V8 (Node) — NOT what RN ships =="; node recon.js
if [ -x "$HERMES" ]; then
  echo "== Hermes (RN's engine) =="; "$HERMES" recon.js
else
  echo "== Hermes: set HERMES to a hermes runtime (see header) =="
fi
