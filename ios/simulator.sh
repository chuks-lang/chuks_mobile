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

# Entitlements for a simulator build. A simulator app needs no identity or profile,
# but a capability that is an entitlement on a device is one here too: Sign in with
# Apple answers error 1000 on the simulator exactly as on a phone whose App ID lacks
# it. The simulator does not read entitlements from the signature (an app signed
# with any lands as "Launch failed"); it reads them from two sections the linker
# writes into the binary, the plist in __TEXT,__entitlements and its DER form in
# __TEXT,__ents_der, which is what Xcode does for every simulator build. Every
# entitlement the app asks for is granted, there being no profile to filter against,
# and Info.plist says so. Prints the swiftc flags that link the sections; empty when
# the app declares nothing. Reads SDKROOT, PROJDIR, OUT, APP, BID from the caller.
chuks_ios_sim_entitlement_flags() {
    local want ent_key ent_val extra="" team
    rm -f "$OUT/sim.xcent" "$OUT/sim.der"   # a build that dropped a kind must not keep last time's
    want="$(chuks run "$SDKROOT/appconfig.chuks" "$PROJDIR" ios-entitlements 2>/dev/null)"
    [ -n "$want" ] || return 0
    # The identifier prefix is the team when the Mac has a development identity, the
    # way Xcode writes it; the simulator does not check it against an App ID.
    team="$(security find-certificate -c "Apple Development" -p 2>/dev/null | openssl x509 -noout -subject 2>/dev/null | sed -n 's/.*OU *= *\([A-Z0-9]*\).*/\1/p' | head -1)"
    team="${team:-SIMULATOR}"
    while IFS="$(printf '\t')" read -r ent_key ent_val; do
        [ -n "$ent_key" ] || continue
        extra="$extra  <key>$ent_key</key>$ent_val
"
    done <<WANTENT
$want
WANTENT
    cat > "$OUT/sim.xcent" <<ENT
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>application-identifier</key><string>$team.$BID</string>
$extra</dict></plist>
ENT
    xcrun derq query -f xml -i "$OUT/sim.xcent" -o "$OUT/sim.der" --raw 2>/dev/null || return 0
    printf -- '-Xlinker -sectcreate -Xlinker __TEXT -Xlinker __entitlements -Xlinker %s ' "$OUT/sim.xcent"
    printf -- '-Xlinker -sectcreate -Xlinker __TEXT -Xlinker __ents_der -Xlinker %s' "$OUT/sim.der"
}

# Record in Info.plist which entitlements a simulator build carries (see above), so
# the host can tell "not signed for it" from "unavailable" the way it does on device.
chuks_ios_sim_note_entitlements() {
    [ -f "$OUT/sim.xcent" ] || return 0
    plutil -replace ChuksGrantedEntitlements -json '[]' "$APP/Info.plist" >/dev/null 2>&1
    sed -n 's/^  <key>\(.*\)<\/key>.*/\1/p' "$OUT/sim.xcent" | grep -v '^application-identifier$' | while read -r granted; do
        [ -n "$granted" ] && plutil -insert ChuksGrantedEntitlements -string "$granted" -append "$APP/Info.plist" >/dev/null 2>&1
    done
    echo "   entitlements: $(sed -n 's/^  <key>\(.*\)<\/key>.*/\1/p' "$OUT/sim.xcent" | grep -v '^application-identifier$' | tr '\n' ' ')"
}
