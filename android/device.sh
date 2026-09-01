#!/usr/bin/env bash
# Device bootstrap shared by the Android build scripts (build.sh, build-cmr.sh).
# Sourced, not executed, after $SDK and $ADB are set.
#
# Running an app should work from a cold Mac, so rather than stopping with
# "no android device/emulator" we start one: the attached device or running
# emulator when there is one, otherwise ANDROID_AVD, otherwise the newest AVD
# installed. Boot is waited out (sys.boot_completed), so the ABI probe, the dev
# tunnel, and the install all see a ready device.
#
# ANDROID_SERIAL pins a specific device when several are attached; ANDROID_AVD
# pins which emulator to start.
EMULATOR="${EMULATOR:-$SDK/emulator/emulator}"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

# Serial of the first device in state `device` (ANDROID_SERIAL wins when set).
# Devices still booting report `offline`, and an untrusted USB phone reports
# `unauthorized`; neither counts as ready.
chuks_online_device() {
    "$ADB" devices 2>/dev/null | awk -v want="${ANDROID_SERIAL:-}" '
        /\tdevice$/ { if (want == "" || $1 == want) { print $1; exit } }'
}

# AVD to start: $ANDROID_AVD, else the highest API level installed, preferring a
# Pixel at that level (Google's reference device, and Android Studio's default).
chuks_pick_avd() {
    if [ -n "${ANDROID_AVD:-}" ]; then echo "$ANDROID_AVD"; return; fi
    "$EMULATOR" -list-avds 2>/dev/null | while IFS= read -r avd; do
        [ -n "$avd" ] || continue
        api="$(sed -n 's/^target=android-//p' "$AVD_HOME/$avd.ini" 2>/dev/null | head -1)"
        rank=1; case "$avd" in *[Pp]ixel*) rank=0 ;; esac
        printf '%s\t%s\t%s\n' "${api:-0}" "$rank" "$avd"
    done | sort -k1,1nr -k2,2n | head -1 | cut -f3
}

# Leaves a ready device/emulator serial in $DEV_ID. Returns 1 (with an
# explanation) when there is nothing to start.
chuks_ensure_device() {
    DEV_ID="$(chuks_online_device)"
    [ -n "$DEV_ID" ] && return 0

    local avd; avd="$(chuks_pick_avd)"
    if [ -z "$avd" ]; then
        echo "no android device or emulator."
        echo "   connect a phone (USB debugging on, this Mac trusted), or create an"
        echo "   emulator in Android Studio > Device Manager"
        return 1
    fi
    echo "   starting the $avd emulator…"
    ( nohup "$EMULATOR" -avd "$avd" -no-boot-anim >/tmp/chuks-emulator.log 2>&1 & )

    # A cold emulator takes a while: wait for adb to see it, then for the
    # framework itself to come up. Until boot_completed the install would fail.
    local i
    for i in $(seq 1 240); do
        DEV_ID="$(chuks_online_device)"
        if [ -n "$DEV_ID" ] &&
           [ "$("$ADB" -s "$DEV_ID" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
            return 0
        fi
        DEV_ID=""
        sleep 1
    done
    echo "the $avd emulator did not finish booting (log: /tmp/chuks-emulator.log)"
    return 1
}
