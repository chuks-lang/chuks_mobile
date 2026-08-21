#!/usr/bin/env bash
# Chuks Mobile hot reload (true Fast Refresh for engine edits).
#
# The engine runs in the Chuks VM inside an HTTP dev server; the app is built ONCE
# in DEV mode and fetches the mutation stream over HTTP. Editing core/engine.chuks
# only restarts the ~1s VM server -- NO app rebuild/reinstall. The running app
# detects the restart, remounts, and its engine state is preserved across the
# reload (snapshotted to a file the new server restores on boot). The host process
# stays alive, so scroll position + keyboard focus persist for free.
#
# Editing the Swift host still needs a rebuild (re-run with FRESH=1).
set -uo pipefail
cd "$(dirname "$0")"
ENGINE="../app/app.chuks"
DEVSERVER="../core/devserver.chuks"
STATE="/tmp/chuks-mobile-state.txt"
PORT=7799
BID="com.chuks.dashboard"
UDID="$(xcrun simctl list devices | awk -F'[()]' '/Booted/{print $2; exit}')"
export CHUKS_NO_WARNINGS=1

sig() { stat -f '%m' "$ENGINE" 2>/dev/null; }
free_port() { lsof -ti :$PORT | xargs kill -9 2>/dev/null; pkill -9 -f devserver.chuks 2>/dev/null; }
start_server() { free_port; sleep 0.3; ( chuks run "$DEVSERVER" >/tmp/chuks-devserver.log 2>&1 & ); }
# Probe readiness with GET /state (idempotent). NB: do NOT use POST /mount here —
# mount() is one-shot (emits the full tree once), so probing it would consume the
# initial frame and the app host would receive an empty mount → blank screen.
wait_up() { for _ in $(seq 1 40); do curl -s -o /dev/null http://localhost:$PORT/state 2>/dev/null && return 0; sleep 0.1; done; }

# 1. build + install the DEV host ONCE (this is the only native build)
if [ "${FRESH:-0}" = "1" ] || ! xcrun simctl get_app_container "$UDID" "$BID" >/dev/null 2>&1; then
    echo "▸ building + installing the DEV host (one time)…"
    DEV=1 FAST=1 ./build.sh >/tmp/chuks-devhost.log 2>&1 || { echo "host build failed:"; tail -8 /tmp/chuks-devhost.log; exit 1; }
fi

# 2. start the VM dev server + (re)launch the app
start_server; wait_up
echo "▸ dev server up on :$PORT"
xcrun simctl launch "$UDID" "$BID" >/dev/null 2>&1
echo "▸ app launched (DEV mode, fetching over HTTP)"
echo "▸ edit $ENGINE and save — the app hot-reloads with state preserved. Ctrl-C to stop."

# 3. watch the engine: on save, snapshot state -> restart server (restores it)
last="$(sig)"
while true; do
    sleep 1
    now="$(sig)"
    if [ "$now" != "$last" ]; then
        echo "$(date +%H:%M:%S)  engine changed → hot reloading"
        curl -s localhost:$PORT/state > "$STATE" 2>/dev/null   # snapshot engine state
        start_server; wait_up                                  # restart w/ the edited engine
        echo "  ✓ reloaded (state preserved); the app remounts within ~1s"
        last="$now"
    fi
done
