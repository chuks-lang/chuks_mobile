#!/usr/bin/env bash
# Simulator bootstrap shared by the iOS build scripts (build.sh, build-cmr.sh,
# dev.sh). Sourced, not executed.
#
# Running an app should work from a cold Mac, so rather than stopping with
# "no booted simulator" we boot one: the already-booted device when there is
# one, otherwise IOS_SIM (a device name or UDID) and finally the newest iPhone
# the machine has installed. The Simulator window is brought up too, so the app
# is visible instead of running headless.

# UDID of a booted simulator, empty when none is running.
chuks_booted_sim() {
    xcrun simctl list devices 2>/dev/null | awk -F'[()]' '/\(Booted\)/{print $2; exit}'
}

# UDID of the simulator to boot. With IOS_SIM set (a UDID or a device name), the
# device matching it, preferring an exact name over a longer name that merely
# contains it ("iPhone 17 Pro" is not "iPhone 17 Pro Max") and the newest runtime
# when several match. Otherwise the first iPhone of the newest runtime with one.
chuks_pick_sim() {
    xcrun simctl list devices available 2>/dev/null | awk -F'[()]' -v want="${IOS_SIM:-}" '
        want != "" {
            if (NF > 1) {
                name = $1; sub(/^ +/, "", name); sub(/ +$/, "", name)
                if (name == want || $2 == want) exact = $2
                else if (index($0, want))       loose = $2
            }
            next
        }
        /^-- /                  { picked = 0; next }
        /^ *iPhone/ && !picked  { udid = $2; picked = 1 }
        END { print (want != "" ? (exact != "" ? exact : loose) : udid) }'
}

# Device name for a UDID, for log lines.
chuks_sim_name() {
    xcrun simctl list devices 2>/dev/null | awk -F'[()]' -v u="$1" '
        index($0, u) { sub(/^ +/, "", $1); sub(/ +$/, "", $1); print $1; exit }'
}

# Leaves a booted simulator in $UDID. Returns 1 (with an explanation) when the
# machine has no simulator to boot.
chuks_ensure_sim() {
    # An explicit IOS_SIM wins over whatever happens to be booted.
    UDID=""
    [ -n "${IOS_SIM:-}" ] || UDID="$(chuks_booted_sim)"
    if [ -z "$UDID" ]; then
        UDID="$(chuks_pick_sim)"
        if [ -z "$UDID" ]; then
            if [ -n "${IOS_SIM:-}" ]; then
                echo "no simulator matching IOS_SIM='$IOS_SIM'."
            else
                echo "no iOS simulator installed."
            fi
            echo "   pick one from: xcrun simctl list devices available"
            echo "   (install a runtime in Xcode > Settings > Components)"
            return 1
        fi
        xcrun simctl list devices | grep -q "$UDID) (Booted)" ||
            echo "   booting the simulator ($(chuks_sim_name "$UDID"))…"
        # bootstatus -b boots the device if needed and waits until it is ready.
        xcrun simctl bootstatus "$UDID" -b >/dev/null 2>&1 || {
            echo "could not boot the simulator ($UDID)"; return 1; }
    fi
    # Bring the window up (no-op when it is already showing this device).
    open -a Simulator --args -CurrentDeviceUDID "$UDID" >/dev/null 2>&1 || true
    return 0
}
