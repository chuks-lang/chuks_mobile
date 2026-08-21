# Native capabilities roadmap

Status: foundations done; capabilities shipping tier by tier. Goal: give Chuks
Mobile access to the device (sensors, media, connectivity, storage, system) across
all three engines (uikit, swiftui, android), while the app stays 100% Chuks.

### Snapshot (updated 2026-08-21)

Done, verified on all three engines:
- Foundations F1 (Platform/DeviceInfo), F2 (Permissions), F3 (async + stream bridge).
- Tier A: Clipboard, Linking, Share, Haptics, Brightness, Torch, and the
  **Battery / Network / App-state** change streams.
- Tier B: File system, Audio playback, Text-to-speech, Local notifications,
  Secure storage (Android verified; iOS Keychain needs real signing).

Pending (not built yet):
- **Event streams** still to do: **Location updates** (Tier B), **Motion sensors**
  (accelerometer/gyro/magnetometer, Tier C), **Mic level / recording** (Tier C),
  **BLE scan** (Tier C). The stream *mechanism* (F3) is done and proven; these are
  the remaining sensor/connectivity data sources that ride it.
- Tier B one-shots: **Image / media picker**, **System camera (intent)**, **Biometrics**.
- Tier A: **DeviceInfo** detail fields, **Orientation** (current + lock + rotation stream).
- Tier C (heavy): Camera preview (live), Bluetooth (BLE), NFC, Push notifications, IAP.
- System helpers: Orientation lock, status/nav-bar color extensions, deep links.

Each pending item keeps its full entry (with the intended API + platform APIs) in the
tier sections below; this snapshot is just the at-a-glance status.

This is the sibling of `docs/primitives-roadmap.md`. That one grows the UI *view*
vocabulary (things that render). This one grows the *capability* vocabulary — things
that mostly DON'T render: they're **async**, **permission-gated**, and several
**stream events**. That difference drives the whole design, so the three foundations
below come before any single capability.

---

## Why capabilities are different from primitives

A UI primitive (Button, Map, Canvas) is a synchronous request→diff: an event comes
in, a mutation stream goes out, the host draws. Device capabilities break that shape:

- **Async results.** "Take a photo" / "record 5s of audio" / "read a BLE characteristic"
  resolve *later*, not in the same turn.
- **Permissions.** Camera / Mic / Location / BLE / Notifications must be requested,
  the OS prompts the user, and the answer comes back async — and can be denied.
- **Event streams.** Mic level, BLE scan results, accelerometer, location updates push
  MANY values over time, host→app, with no matching inbound event. (The push MECHANISM
  is F3, done; the streams shipping on it today are Battery / Network / App-state. The
  four named here are still pending: location = Tier B, the rest Tier C. See Snapshot.)

So the ABI needs a host→engine push channel with a callback registry — the app can't
just "return a diff." That's foundation #3.

---

## The 3 foundations (build these first, in order)

### F1 — `getPlatform()` / `Platform` + DeviceInfo  ✅ DONE (uikit · swiftui · android)
A host global exposing the running platform + basic device info. RN's `Platform.OS`.
- `core/platform.chuks`: `Platform` enum { iOS="ios", Android="android" }; `getPlatform()`,
  `isIOS()`/`isAndroid()`, `osVersion()`, `deviceModel()`, `isTablet()`, `select(ios, android)`.
- Wiring: entry export `setPlatform(os, version, model, isTablet)` in `app/_entry.chuks`
  (same pattern as `setColorScheme`/`setInsets`), called once at launch by each host —
  iOS `UIDevice`, Android `Build.VERSION.RELEASE`/`Build.MODEL`. Synchronous, no async bridge.
- **Gotcha (fixed):** a new Android entry export needs a matching stub in the hand-written
  JNI bridge `android/jni.cpp` (`Java_com_chuks_app_N_setPlatform`) — without it the app
  crashes at launch with `UnsatisfiedLinkError`. The AOT exports `chuks_setPlatform` into
  `libapp.h`; the C++ bridge must forward to it.
- **Verified on all 3**: same Chuks shows Platform.iOS / iOS 18.4 / iPhone on the simulator
  and Platform.Android / 16 / sdk_gphone64_arm64 on the emulator. Documented as the "Platform"
  concept page. Test screen `app/views/ex_platform.chuks`.

### F2 — Permissions model  ✅ DONE (swiftui · uikit · android) — camera, microphone, location, notifications, photos
One primitive every gated capability reuses. Built ENTIRELY on the F3 bridge (no
new C-ABI): status rides the result channel, an unknown kind the error channel.
- `Permission.status(kind, onResult)` reads the current grant WITHOUT prompting;
  `Permission.request(kind, onResult, onError)` shows the OS dialog once and reports
  the outcome. `core/native.chuks`.
- Result: "granted" | "denied" | "undetermined" | "restricted" (Android reports
  granted/denied; it has no clean "undetermined"). onError fires for an unknown
  kind / platform failure.
- Hosts: iOS via AVCaptureDevice authorizationStatus/requestAccess (+ Info.plist
  NSCamera/NSMicrophoneUsageDescription); Android via checkSelfPermission +
  requestPermissions, resolved in an onRequestPermissionsResult override keyed by
  request code (+ CAMERA/RECORD_AUDIO in the manifest).
- Verified on all three engines with the real OS dialog: request → dialog → the
  grant/deny flows back (SwiftUI granted, UIKit denied, Android granted).
- **Kinds:** camera, microphone, location, notifications, photos. Adding a kind is
  HOST-ONLY (core Permission API is generic over the kind string): iOS maps each to
  its framework (AVCaptureDevice, PHPhotoLibrary, UNUserNotificationCenter,
  CLLocationManager delegate) + Info.plist strings; Android to its permission
  string via the same requestPermissions/onRequestPermissionsResult path + manifest.
  Location (the delegate path, most different) verified live on all three engines.

### F3 — Async host→engine bridge (the real new infrastructure)  ✅ DONE (swiftui · uikit · android)
A general mechanism so a native API can resolve/stream back into Chuks.
- **Shipped:** a capability call registers a `(string) -> void` under a fresh token
  and emits an `X|token|capability|args` command into the SAME drain stream the host
  already applies. The host runs the native work and calls the new C-ABI export
  `resolve(token, payload)`, which fires the registered closure and re-renders.
  Verified END-TO-END on iOS SwiftUI AND Android (clipboard copy→paste round-trip on
  the sim/emulator); the UIKit engine compiles + renders the same.
- Core lives in `core/ui.chuks` (Reconciler.command / fireToken + App.resolve +
  emitNativeCommand) and `core/entry.chuks` (`resolve` export). Each host adds the `X`
  case in its parse loop + `handleCommand` (run post-apply so a sync capability doesn't
  re-enter the parser): `ios/ChuksAppSwiftUI.swift`, `ios/ChuksApp.swift`, and
  `android/MainActivity.kt` (+ a `resolve` JNI stub in `android/jni.cpp`, + VIBRATE /
  FLASHLIGHT in the manifest). The dev server has a `/resolve` route for hot-reload.
- **Decisions settled:** payload is a raw string (JSON when structured); one-shot
  (fire then unregister); result arrives via the direct `resolve(...)` C call (not the
  newline stream, so multiline/large results are fine). Outbound `args` are single-line
  for now (base64 later for multiline set/share). Threading: host resolves on main.
- **Teardown / cancel ✅ (all three engines):** the leak-free lifecycle discipline.
  - `Cell.set` no-ops after unmount (has-guard), so a late async/stream fire into a
    swept component is harmless — the fire-during-unmount race lands here.
  - `command()` returns a `NativeSub`; `.cancel()` drops the token AND emits
    `X|token|__cancel__|` so the host tears down the native op/subscription.
    Streams are started in `useEffect` and cancelled in its cleanup, so `hookSweep`
    tears them down on unmount (no forgotten-cleanup leak).
  - Host→Chuks death: `cancelToken(token)` C-ABI export (user dismissed a picker).
  - Streams: a token flagged as repeating in `pending` (fires until cancelled).
  - Verified on SwiftUI + UIKit + Android with a fast `Pulse` stream: unmount
    mid-tick → ticks freeze, no crash, AND `Debug.activeStreams` reads 0 (the NATIVE
    subscription was released, not just Chuks unsubscribing).
- **Error channel ✅ (all three engines):** a request can carry an OPTIONAL
  `onErr` alongside its result callback (`emitNativeCommandE` / `emitNativeStream`
  with an error handler). The host reports a failure via the `fail(token, message)`
  C-ABI export (JNI `fail` stub on Android, `/fail` dev-server route), which fires
  `onErr` and forgets the token — distinct from a valid result, and terminal.
  Verified with `Debug.fail` on SwiftUI + UIKit + Android (onErr fires with the
  host message, onResult does not; no crash). This is what a denied permission /
  missing hardware / native op error rides on.
- **Next:** F2 — permissions (requestPermission/permissionStatus), now unblocked.

---

## Tier A — cheap, synchronous-ish, high value (after F1–F3)

Mostly one-shot host calls; little or no streaming. Fast wins. API in `core/native.chuks`.
Marked ✅ = Chuks API + ALL THREE hosts wired (iOS SwiftUI + UIKit + Android).

- [ ] **DeviceInfo** (part of F1) — os, version, model, isTablet, screen size/scale.
- [x] **Clipboard** ✅ — `Clipboard.set(text)` / `Clipboard.get(onResult)` (get is async).
- [x] **Linking** ✅ — `Linking.open(url)` (URL / tel / mailto / scheme); `canOpen(url, cb)`.
- [x] **Share sheet** ✅ — `Share.text(t)` / `Share.url(u)` → UIActivityViewController / ACTION_SEND.
- [x] **Haptics** ✅ — `Haptics.impact(style)`: light/medium/heavy/success/warning/error/selection.
- [x] **Battery** ✅ (swiftui · uikit · android) — `Battery.watch(cb)` streams "level,charging"
      (e.g. "87,1"). iOS UIDevice battery monitoring; Android ACTION_BATTERY_CHANGED receiver.
      Verified live update (emulator 100->42) + real values on a Galaxy S23 (5,1 while charging).
- [x] **Network status** ✅ (swiftui · uikit · android) — `Network.watch(cb)` streams
      "wifi"|"cellular"|"none"|"other". iOS NWPathMonitor; Android registerDefaultNetworkCallback.
- [x] **App state** ✅ (swiftui · uikit · android) — `AppState.watch(cb)` streams
      "active"|"inactive"|"background". iOS UIApplication notifications; Android Activity
      onResume/onPause. All three streams fire the current value on subscribe, then on change,
      and tear down to Debug.activeStreams==0 on unmount (comma, not "|", separates fields:
      "|" is the wire delimiter and truncates a result payload).
- [ ] **Orientation** — current + lock/unlock; rotation stream.
- [x] **Screen brightness** ✅ — `Brightness.set(level)` / `Brightness.keepAwake(on)`.
- [x] **Torch / flashlight** ✅ — `Torch.set(on)` (AVCaptureDevice; sim has no torch, test on device).

## Tier B — moderate async, needs permissions (F2/F3)

- [x] **Audio playback** ✅ (swiftui · uikit · android) — `Audio.play(src)/pause/resume/stop`,
      `position(cb)` -> "posMs/durMs". iOS AVPlayer + AVAudioSession(.playback); Android
      MediaPlayer + AudioAttributes(MEDIA). play() releases/replaces the prior track (no
      overlap). Verified the play/pause/resume state machine on all three: play advances,
      pause freezes the position, resume advances it. NOTE: the test asset MUST be audio-only
      (chime.wav) — a video-container mp4 has no audible track, and on Android a video track
      with no render surface stalls the clock at 0. Follow-ons: seek, volume, loop, a
      position stream. (Both build scripts now bundle .wav/.mp3/.m4a assets.)
- [x] **Text-to-speech** ✅ (swiftui · uikit · android) — `Tts.speak(text)/stop()`, `isSpeaking(cb)`
      -> "1"/"0". iOS AVSpeechSynthesizer; Android TextToSpeech (async engine init, a speak
      before onInit is queued + flushed when ready). text is base64 on the wire. Verified the
      speak -> isSpeaking "1" -> stop -> "0" state machine on all three. AUDIBILITY confirmed:
      iOS (sim) and Android on a REAL device (Galaxy S23) -- audible. The Android EMULATOR is
      silent for TTS (its fast-track audio path doesn't render), which is why device testing
      was needed. Routed via USAGE_MEDIA (same path as Audio). Follow-ons: rate/pitch/voice,
      a per-utterance done callback.
- [x] **Local notifications** ✅ (swiftui · uikit · android) — `Notifications.notify(title, body)`,
      permission-gated (F2 "notifications"). Title + body are base64 on the wire (arbitrary
      multiline text). iOS: UNUserNotificationCenter + a foreground-banner delegate (else iOS
      suppresses the banner for the active app); Android: NotificationManager + a "chuks"
      channel. Verified end-to-end on all three: request → granted round-trip, then a posted
      banner/shade entry with title "Chuks" and a multiline body. (Scheduling with a real
      delay + cancel, and tap → deep-link, are follow-ons. Push is Tier C.)
- [ ] **Biometrics** — Face ID / Touch ID / fingerprint auth. LAContext / BiometricPrompt
      (BiometricPrompt is androidx — may need a KeyguardManager fallback).
- [~] **Secure storage** — `SecureStore.set/get/delete`, encrypted key/value. Android ✅
      VERIFIED (Keystore AES-GCM: key never leaves secure hardware, ciphertext+iv in a
      private prefs; multiline round-trip). iOS: standard Keychain (SecItem*) code, but
      NOT verified on the sim — Keychain needs a team-signed provisioning profile the
      hand-assembled build lacks (SecItemAdd silently fails on the unsigned app; adhoc
      signing isn't enough). Works on a properly-signed app/device; revisit with real
      signing. Same base64 content-encoding as FileSystem.
- [x] **File system** ✅ (swiftui · uikit · android) — `FileSystem.write/read/list/delete`
      in the app's private dir. write() content is base64-encoded on the command wire
      (line-based) so arbitrary multiline text round-trips; read() returns it directly,
      a missing file -> onError. Verified multiline round-trip on all three engines.
      Established the pattern for Tier B capabilities that carry arbitrary content.
- [ ] **Image / media picker** — pick a photo/video from the library (returns a file/URI).
      PHPicker / ACTION_PICK. (Needs photos permission on iOS.)
- [ ] **Location / GPS** — one-shot position + updates stream; accuracy. CoreLocation /
      LocationManager (fused location is Google Play services — use platform LocationManager).
- [ ] **System camera (intent)** — launch the OS camera UI, get a photo back. Easier than a
      live preview and works on both engines. UIImagePickerController / ACTION_IMAGE_CAPTURE.

## Tier C — heavy: streaming, preview surfaces, or external infra

Honest caveats up front — the minimal Android build (no Gradle deps, no androidx, no
Google Play services) limits some of these.

- [ ] **Camera preview (live)** — a live camera view + capture. iOS AVFoundation preview
      layer is fine (a ① view). Android CameraX needs androidx (absent); Camera2 is raw and
      complex. **Plan:** ship the system-camera intent (Tier B) first; live preview later,
      likely gated on adding a camera dep or a Camera2 implementation.
- [ ] **Microphone recording + levels** — record to a file; live level stream (VU meter).
      AVAudioRecorder / MediaRecorder + an amplitude poll. Mic permission.
- [ ] **Bluetooth (BLE)** — scan / connect / read / write / notify. iOS CoreBluetooth (native);
      Android `android.bluetooth.le` is in the platform SDK (no androidx) but permission-heavy
      (BLUETOOTH_SCAN/CONNECT + location on older APIs). Doable, sizeable.
- [ ] **Motion sensors** — accelerometer / gyroscope / magnetometer / device motion, as streams.
      CoreMotion / SensorManager. (Barometer, proximity, ambient light similar.)
- [ ] **NFC** — read/write tags. CoreNFC / android.nfc. Entitlement (iOS) + hardware-gated.
- [ ] **Push notifications** — remote push. Needs APNs (Apple) + FCM (Google services) + a
      server. This is a project, not a primitive; local notifications (Tier B) cover most needs.
- [ ] **In-app purchases** — StoreKit + Play Billing. External store infra; heavy.

## Also worth having (system/platform helpers)

- [ ] **Keep-awake** (prevent sleep) · **Status/nav bar color** (have StatusBar; extend) ·
      **Locale / region** · **Contacts** · **Calendar / reminders** · **Photos library** (save) ·
      **Deep links / universal links** (inbound URL routing) · **Device haptics catalog** ·
      **App version / build** · **Open settings** (deep-link to the OS settings page).

---

## Verification bar (same as the primitives roadmap)

Each capability: works on BOTH iOS engines (uikit + swiftui) and Android; has a test
route/screen; async results/streams verified end-to-end; permissions handled (granted +
denied paths); honest platform caveats documented. VM equals AOT for any Chuks logic.
Nothing committed until hand-tested (per project rule).

## Related

- `docs/primitives-roadmap.md` — the UI primitive vocabulary (Tiers 1–3 complete).
