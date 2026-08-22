#!/usr/bin/env python3
"""Read a Chuks app's app.json and emit the per-platform native config the build
scripts need (version, URL schemes, permission strings / uses-permission entries).

    python3 appconfig.py <projdir> <what>

`app.json` (in the project root) is the app's identity + native config, like RN/Expo's
app.json. chuks.json stays the package/tooling manifest. app.json is optional — every
field falls back to a sensible default so existing projects keep building.

Schema (all fields optional):
{
  "name": "My App",
  "displayName": "My App",           # home-screen / launcher label (defaults to name)
  "version": "1.2.0",                # CFBundleShortVersionString / versionName
  "buildNumber": 3,                  # CFBundleVersion / versionCode
  "ios":     { "bundleId": "com.acme.app", "schemes": ["acme"] },
  "android": { "schemes": ["acme"] },
  "permissions": {                   # kind -> the human reason (iOS shows it; Android just needs the grant)
    "camera": "Take photos.", "microphone": "...", "location": "...",
    "photos": "...", "photosAdd": "...", "contacts": "...",
    "calendar": "...", "faceId": "...", "notifications": "..."
  }
}
"""
import json, sys, re

# permission kind -> iOS Info.plist usage-description key(s)
IOS_KEYS = {
    "camera": ["NSCameraUsageDescription"],
    "microphone": ["NSMicrophoneUsageDescription"],
    "location": ["NSLocationWhenInUseUsageDescription"],
    "photos": ["NSPhotoLibraryUsageDescription"],
    "photosAdd": ["NSPhotoLibraryAddUsageDescription"],
    "contacts": ["NSContactsUsageDescription"],
    "calendar": ["NSCalendarsUsageDescription", "NSCalendarsFullAccessUsageDescription"],
    "faceId": ["NSFaceIDUsageDescription"],
    "notifications": [],  # iOS notifications need no Info.plist string
}
# permission kind -> Android manifest uses-permission name(s)
ANDROID_PERMS = {
    "camera": ["CAMERA"],
    "microphone": ["RECORD_AUDIO"],
    "location": ["ACCESS_FINE_LOCATION"],
    "photos": ["READ_MEDIA_IMAGES"],
    "photosAdd": [],  # MediaStore save needs no permission on API 29+
    "contacts": ["READ_CONTACTS"],
    "calendar": ["READ_CALENDAR", "WRITE_CALENDAR"],
    "faceId": ["USE_BIOMETRIC"],
    "notifications": ["POST_NOTIFICATIONS"],
}
# always present on Android (the engine + built-in Tier A capabilities rely on them)
BASE_ANDROID = ["INTERNET", "ACCESS_NETWORK_STATE", "VIBRATE", "FLASHLIGHT"]


def load(projdir):
    try:
        with open(f"{projdir}/app.json") as f:
            return json.load(f)
    except (FileNotFoundError, ValueError):
        return {}


def xml_escape(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


def main():
    projdir, what = sys.argv[1], sys.argv[2]
    cfg = load(projdir)
    ios = cfg.get("ios", {}) or {}
    android = cfg.get("android", {}) or {}
    perms = cfg.get("permissions", {}) or {}

    if what == "version":
        print(cfg.get("version", "1.0"))
    elif what == "build":
        print(cfg.get("buildNumber", 1))
    elif what == "name":
        print(cfg.get("name", ""))
    elif what == "displayName":
        print(cfg.get("displayName", cfg.get("name", "")))
    elif what == "ios-bundle":
        print(ios.get("bundleId", ""))
    elif what == "ios-plist":
        # permission usage strings + a URL-types block for the app's schemes
        out = []
        for kind, reason in perms.items():
            for key in IOS_KEYS.get(kind, []):
                out.append(f"  <key>{key}</key><string>{xml_escape(reason)}</string>")
        schemes = ios.get("schemes", []) or []
        if schemes:
            items = "".join(f"<string>{xml_escape(s)}</string>" for s in schemes)
            out.append("  <key>CFBundleURLTypes</key>")
            out.append(f"  <array><dict><key>CFBundleURLSchemes</key><array>{items}</array></dict></array>")
        print("\n".join(out))
    elif what == "android-perms":
        names = list(BASE_ANDROID)
        for kind in perms:
            for p in ANDROID_PERMS.get(kind, []):
                if p not in names:
                    names.append(p)
        print("\n".join(
            f'    <uses-permission android:name="android.permission.{n}" />' for n in names))
    elif what == "android-schemes":
        # <data> lines for a VIEW intent-filter (empty if no schemes)
        schemes = android.get("schemes", []) or []
        print("\n".join(f'                <data android:scheme="{xml_escape(s)}" />' for s in schemes))
    else:
        sys.exit(f"unknown field: {what}")


if __name__ == "__main__":
    main()
