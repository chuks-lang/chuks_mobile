# @chuks/mobile

The Chuks Mobile SDK. Write your whole app in Chuks; it compiles to native and drives
real iOS and Android widgets through one shared engine, with two iOS render engines
(SwiftUI and UIKit) behind the same code.

> **Requires Chuks v0.1.1 or newer.** The referenced-package build (a project consuming
> `@chuks/mobile` from `chuks_packages/`) and the native host build scripts rely on
> toolchain behavior available in Chuks v0.1.1+.

## What's in the box

- **UI toolkit** — `core/ui.chuks` (Column, Row, Text, Pressable, List, …), `core/kit.chuks`
- **State + effects** — `core/state.chuks` (`useState`, `useEffect`, keyed state)
- **Navigation** — `core/nav.chuks` (`NavStack`)
- **Theming** — `core/theme.chuks` (semantic tokens, light/dark), `core/tw.chuks`
- **Platform info** — `core/platform.chuks` (`getPlatform`, `isIOS`, device info)
- **Native capabilities** — `core/native.chuks`: permissions, file system, secure storage,
  audio playback, text-to-speech, local notifications, and battery / network / app-state
  streams, each working on all three engines
- **Native hosts + build** — `ios/` (SwiftUI + UIKit), `android/` (JNI), and the build
  scripts that compile your app through the package

## Using it

Chuks has no re-export, so import framework modules granularly:

```chuks
import { Node, Column, Text, Pressable, Comp } from "pkg/@chuks/mobile/core/ui.chuks";
import { useState, useEffect, Cell }           from "pkg/@chuks/mobile/core/state.chuks";
import { NavStack }                            from "pkg/@chuks/mobile/core/nav.chuks";
import { tk }                                  from "pkg/@chuks/mobile/core/theme.chuks";
import { Permission, FileSystem, SecureStore, Audio, Tts, Notifications,
         Battery, Network, AppState }          from "pkg/@chuks/mobile/core/native.chuks";
```

Your app root (`app/app.chuks`) exports `createRoot()`. A generated per-project entry
(`.chuks/entry.chuks`) bridges it to the native host C-ABI — you don't write that file.

## Running

The package provides the host build scripts; run them from your project root (they read
the native host out of `chuks_packages/@chuks/mobile/` and compile your app through the
package):

```
bash chuks_packages/@chuks/mobile/ios/build.sh       # iOS simulator (iosEngine in chuks.json)
bash chuks_packages/@chuks/mobile/android/build.sh   # Android device / emulator
```

The streamlined path — `chuks new <name> --template mobile` to scaffold a project, then
project `chuks.json` scripts to build — is being wired up in the CLI.

## Engines

Your Chuks code is identical across all three. `iosEngine` in your project's `chuks.json`
selects `swiftui` (native SwiftUI layout) or `uikit` (UIViews + Yoga flexbox); Android is
a single JNI host.
