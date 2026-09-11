#!/usr/bin/env bash
# Signing and installing onto a paired iPhone, shared by every build path.
#
# Extracted so the CMR dev build can reach a real device. Sensor, permission and
# entitlement behaviour only reproduces on a phone: a motion coprocessor that batches, a
# HealthKit entitlement a wildcard profile cannot carry, an Android-style permission
# dialog held off by another activity. A hot reload that stops at the simulator stops
# exactly where it is needed most, and build-cmr.sh used to select the device SDK and
# then install to the simulator anyway.
#
# Callers must have SDKROOT, PROJDIR, OUT, APP and BID set.

chuks_ios_sign_device() {
# Signing identity (Apple Development) + its team.
IDENTITY="$(security find-identity -v -p codesigning 2>/dev/null | awk '/Apple Development/{print $2; exit}')"
[ -n "$IDENTITY" ] || { echo "no 'Apple Development' signing identity found (open Xcode > Settings > Accounts)"; exit 1; }
TEAM="$(security find-certificate -c 'Apple Development' -p 2>/dev/null | openssl x509 -noout -subject -nameopt multiline 2>/dev/null | awk -F'= ' '/organizationalUnit/{print $2}')"
# A development provisioning profile with a wildcard app id that covers this team,
# so any Chuks bundle id installs. Xcode maintains these under UserData.
PROFILE=""
for p in "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"/*.mobileprovision; do
    [ -e "$p" ] || continue
    pl="$(security cms -D -i "$p" 2>/dev/null)"
    [ "$(echo "$pl" | plutil -extract Entitlements.get-task-allow raw - 2>/dev/null)" = "true" ] || continue
    [ "$(echo "$pl" | plutil -extract TeamIdentifier.0 raw - 2>/dev/null)" = "$TEAM" ] || continue
    case "$(echo "$pl" | plutil -extract Entitlements.application-identifier raw - 2>/dev/null)" in
        *".*") PROFILE="$p"; break ;;
    esac
done
[ -n "$PROFILE" ] || { echo "no wildcard development provisioning profile for team $TEAM (build once in Xcode to create one)"; exit 1; }
# Entitlements the app's declared permissions imply: HealthKit for "health",
# the NFC reader formats for "nfc". Only those two kinds need one; everything
# else on the bus is a usage string.
#
# Each is filtered against what THIS profile actually carries, because codesign
# fails outright on an entitlement the profile lacks. A wildcard profile carries
# neither, so an app that declares nfc still builds and installs, and is told once
# that the capability will not work until it has an explicit App ID. Emitting them
# unconditionally would stop such an app reaching a device at all; emitting them
# never is how NFC came to be shipped, documented, and dead on every device.
PROF_ENT="$(security cms -D -i "$PROFILE" 2>/dev/null | plutil -extract Entitlements xml1 -o - - 2>/dev/null)"
WANT_ENT="$(chuks run "$SDKROOT/appconfig.chuks" "$PROJDIR" ios-entitlements 2>/dev/null)"
# No HEALTHKIT=1 switch any more: HealthKit lives in @chuks/health, and installing
# that package declares the permission, which is what produces the entitlement here.
EXTRA_ENT=""; DROPPED_ENT=""
while IFS="$(printf '\t')" read -r ent_key ent_val; do
    [ -n "$ent_key" ] || continue
    case "$PROF_ENT" in
        *"<key>$ent_key</key>"*) EXTRA_ENT="$EXTRA_ENT  <key>$ent_key</key>$ent_val
" ;;
        *) DROPPED_ENT="$DROPPED_ENT $ent_key" ;;
    esac
done <<WANTENT
$WANT_ENT
WANTENT
if [ -n "$DROPPED_ENT" ]; then
    echo "   note: this provisioning profile carries none of:$DROPPED_ENT"
    echo "         The app builds and installs, but those capabilities will not work"
    echo "         on device. A wildcard profile can never carry an entitlement: to"
    echo "         use them, register an explicit App ID for $BID at developer.apple.com"
    echo "         with the capability enabled, then build once in Xcode."
fi
# Tell the running app which of these it actually got. Without this the host has
# to guess: iOS reports NFC as simply unavailable when the entitlement is missing,
# which is indistinguishable at runtime from a device that has no NFC reader.
plutil -replace ChuksGrantedEntitlements -json '[]' "$APP/Info.plist" >/dev/null 2>&1
printf '%s' "$EXTRA_ENT" | sed -n 's/^  <key>\(.*\)<\/key>.*/\1/p' | while read -r granted; do
    [ -n "$granted" ] && plutil -insert ChuksGrantedEntitlements -string "$granted" -append "$APP/Info.plist" >/dev/null 2>&1
done
cp "$PROFILE" "$APP/embedded.mobileprovision"
cat > "$OUT/ent.plist" <<ENT
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>application-identifier</key><string>$TEAM.$BID</string>
  <key>com.apple.developer.team-identifier</key><string>$TEAM</string>
  <key>get-task-allow</key><true/>
$EXTRA_ENT</dict></plist>
ENT
if ! codesign --force --sign "$IDENTITY" --entitlements "$OUT/ent.plist" --generate-entitlement-der --timestamp=none "$APP" 2>"$OUT/codesign.log"; then
    echo "  Signing failed:"; sed 's/^/    /' "$OUT/codesign.log"; exit 1
fi
}

chuks_ios_install_device() {
# First connected+available device; exclude "unavailable" (substring match trap) and
# let IOS_DEVICE_ID override when more than one is attached.
DEVID="${IOS_DEVICE_ID:-$(xcrun devicectl list devices 2>/dev/null | awk '!/unavailable/ && /available/{for(i=1;i<=NF;i++) if($i ~ /^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-/){print $i; exit}}')}"
[ -n "$DEVID" ] || { echo "no available paired device (connect an iPhone, unlock it, and trust this Mac)"; exit 1; }
xcrun devicectl device install app --device "$DEVID" "$APP" >/dev/null
xcrun devicectl device process launch --device "$DEVID" "$BID" >/dev/null && echo "   launched on device"
}
