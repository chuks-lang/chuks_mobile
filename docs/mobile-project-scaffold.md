# Chuks Mobile: project creation, scaffold, and the engine boundary

Design for how a developer creates and runs a Chuks Mobile app, and how the SDK
(this repo) relates to their project. Status: design agreed, implementation phased.

## Decisions

1. **Engine model: referenced, not vendored.** The `@chuks/mobile` SDK (framework +
   native engines) is a versioned dependency. A project's `ios/`/`android/` are thin,
   generated runner shells that link the SDK engine. App authors get engine fixes by
   bumping the SDK version, not by re-scaffolding. (The audio / stale-apk incidents
   showed how often the host engine changes; that churn must not land on app authors.)
2. **Creation: `chuks new <name> --template mobile`.** `mobile` is the friendly template
   name; the CLI resolves it to the `@chuks/mobile` package and FETCHES the template from
   the registry (like `npx react-native init` / `npx expo`) rather than embedding a mobile
   skeleton in the `chuks` binary. The name→package alias is a trivial stable mapping;
   Chuks Mobile still grows on its own in the package, decoupled from CLI releases.
3. **Dev loop rides the existing `chuks.json` scripts.** `chuks` already runs a project's
   `scripts` (that is how `chuks ios` / `chuks dev` work). The scaffold points `dev` /
   `run` / `build` at the installed `@chuks/mobile` tooling — so the mobile toolchain
   evolves in the package, not the CLI. A `chuks mobile <verb>` alias is optional sugar.

## The engine / app boundary (already drawn in the code)

The seam exists today: `core/entry.chuks` (the SDK's C-ABI entry module) imports the
user's app by convention:

```
// core/entry.chuks
import { createRoot } from "../app/app.chuks";
```

So the split is clean:

| Belongs to the SDK (`@chuks/mobile`) | Belongs to the app project |
| --- | --- |
| `core/` — framework: reconciler, state, ui, nav, theme, tw, kit, platform, **native API**, and `entry.chuks` (the C-ABI module) | `app/app.chuks` — root, exports `createRoot` |
| `ios/` — SwiftUI + UIKit host runtime, C-ABI bridge, Yoga, build/dev scripts | `app/views/`, `app/components/` — screens + UI |
| `android/` — MainActivity host runtime, JNI bridge, Yoga, build script | `assets/` — images, fonts, media |
| The `chuks_*` host contract + mutation-stream protocol | `chuks.json` — name, bundle id, `@chuks/mobile` version, `iosEngine`, and the permissions/capabilities the app declares |

Per-app knobs the runner shell must carry (today hardcoded in `ios/build.sh` /
`android/build.sh`, tomorrow generated from `chuks.json`): app display name, bundle id,
`Info.plist` usage strings + `AndroidManifest` permissions (derived from which
capabilities the app uses), font/asset bundling, and the app entry path.

## Scaffold a new project produces

```
myapp/
  chuks.json            # name, bundle id, @chuks/mobile version, iosEngine, capabilities
  app/                  # the ONLY dir normally edited
    app.chuks           # root: exports createRoot; routes/nav
    views/              # screens
    components/         # shared UI
  assets/               # images, fonts, media
  ios/    android/      # thin runner shells (generated; link the SDK engine)
  .gitignore  README.md
```

## Dev loop (wraps what exists)

We already have `ios/dev.sh` (hot reload: the host fetches the mutation stream from
`core/devserver.chuks` over HTTP) and `ios/build.sh` / `android/build.sh` (AOT). The
`chuks mobile` verbs wrap these:

- `chuks mobile dev` — boot the dev server + install the hot-reload host on sim/device
- `chuks mobile run ios|android` — AOT build + install
- `chuks mobile build` — release artifact

## Import resolution (resolved)

Chuks already resolves `pkg/<name>/...` imports to installed packages in
`chuks_packages/` (that is how app code imports `pkg/@chuks/mobile/native.chuks`). So the
referenced split needs no new language feature:

- The engine ships as the installed package `@chuks/mobile`; app code and the runner
  import it via `pkg/@chuks/mobile/...`.
- The C-ABI **entry becomes a small per-project generated file** (e.g. `.chuks/entry.chuks`),
  NOT part of the SDK. It imports the engine glue from `pkg/@chuks/mobile/...` and the
  user's root from `./app/app.chuks`, and re-exports the `chuks_*` C ABI. This flips the
  one SDK→app import (`core/entry.chuks` → `../app/app.chuks`) into a project-local file
  where both sides resolve.

## The CLI is a thin launcher (NOT a place to embed mobile)

Chuks Mobile grows on its own, like React Native / Expo: `react-native` and `expo` are
packages, and `npx react-native init` / `npx expo` merely FETCH and LAUNCH them. The
global tool stays thin so the framework's growth is not chained to the CLI's release
cadence. So: **nothing mobile-specific is embedded in the `chuks` binary** (no
`go:embed` of a template or the SDK).

Where things live:

- **`@chuks/mobile`** (registry package, versions independently) owns EVERYTHING mobile:
  the framework, the iOS/Android native hosts, the build/dev tooling, and the **starter
  template**.
- **The `chuks` CLI** already runs `chuks.json` `scripts` (the default case in
  `main.go` calls `runScript` when a `chuks.json` exists — that is how `chuks ios` /
  `chuks dev` work today). A scaffolded project's `chuks.json` simply points its
  `dev` / `run` / `build` scripts at the installed `@chuks/mobile` tooling. **No mobile
  logic in the CLI, and no `chuks mobile` verb group strictly required** — the run/build
  loop rides the existing scripts mechanism.

### The one generic CLI capability to add

`chuks new <name> --template mobile`: teach `new` (main.go:234 / `createProject`
main.go:1102) to accept `--template <name>` and, instead of writing the fixed inline
skeleton, resolve the template name to its package (`mobile → @chuks/mobile`), **fetch it
from the registry** (reusing the existing `add` / `install` / `fetch` machinery), scaffold
from it, and install `@chuks/mobile` as a dependency. The template machinery is generic
(any friendly name → package); the mobile template + all its growth stay in
`@chuks/mobile`.

Optional later polish: a `chuks mobile <verb>` alias that forwards to the project scripts
for discoverability — but it is sugar over `chuks <script>`, not the mechanism.

## Phasing

1. **Package `@chuks/mobile`** — turn the SDK (this repo) into a registry package:
   manifest (`"name": "@chuks/mobile"`, cf. `@chuks/lucide`), `core/ → src/`, ship the
   iOS/Android hosts + Yoga as package native assets, externalize the C-ABI entry into a
   generated per-project file. Verify the current app still builds through the package on
   all three engines. (Cascading, all-or-nothing.)
2. **Starter template (inside `@chuks/mobile`)** — the project tree (`app/` + `assets/` +
   `chuks.json` whose `scripts` delegate to the SDK tooling + generated runner shells).
   Grows in the package, never in the CLI.
3. **One generic CLI capability** — `chuks new <name> --template mobile`: resolve the
   friendly template name to its package, fetch it from the registry (reuse `add`/`install`)
   and scaffold from it. The name→package resolution is generic, not mobile-specific.
4. **Verify** — `chuks new myapp --template mobile` produces a project that builds + runs
   on SwiftUI, UIKit, and Android.
