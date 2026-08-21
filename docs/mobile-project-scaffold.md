# Chuks Mobile: project creation, scaffold, and the engine boundary

Design for how a developer creates and runs a Chuks Mobile app, and how the SDK
(this repo) relates to their project. Status: design agreed, implementation phased.

## Decisions

1. **Engine model: referenced, not vendored.** The `@chuks/mobile` SDK (framework +
   native engines) is a versioned dependency. A project's `ios/`/`android/` are thin,
   generated runner shells that link the SDK engine. App authors get engine fixes by
   bumping the SDK version, not by re-scaffolding. (The audio / stale-apk incidents
   showed how often the host engine changes; that churn must not land on app authors.)
2. **Creation: `chuks new <name> --template mobile`.** Templates generalize (web / cli /
   mobile) instead of a one-off `--mobile` flag.
3. **Dev loop: a `chuks mobile <verb>` namespace** — `dev`, `run ios|android`, `build`.
   A platform verb group is the ergonomic win (cf. `flutter <verb>`), separate from the
   generic create command.

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

## CLI implementation path (from `chuks` recon)

The CLI is one Go file, `Chuks/cmd/chuks/main.go`:

- **`chuks new <name> --template mobile`**: the `new` case (main.go:234) currently ignores
  args past the name and always calls `createProject(name)` (main.go:1102), which writes a
  fixed skeleton from inline Go strings. Add `--template` parsing + a template parameter,
  and scaffold the mobile tree — ideally via `//go:embed templates/mobile/*` (mirror
  `std/embed.go`) walked/copied into the project, rather than more inline strings.
- **`chuks mobile dev|run|build`**: no grouped-verb dispatcher exists; add `case "mobile":`
  to the switch at main.go:152 and dispatch on the next arg (the `kernel install` pattern,
  main.go:292), each verb wrapping the SDK's `dev.sh` / `build.sh`. Add a `printUsage` line
  (main.go:556).

## Phasing

1. **Foundations** — map the `chuks` CLI (new / templates / subcommands / import
   resolution); split `ios/`+`android/` into engine-runtime (SDK) vs runner shell.
2. **Scaffold** — the `--template mobile` project tree + `chuks.json`-driven manifest
   generation.
3. **CLI** — `chuks new --template mobile` + `chuks mobile dev|run|build`.
