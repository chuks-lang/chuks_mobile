# Chuks Mobile

A mobile app written entirely in Chuks: the whole UI lives in `app/`, compiles to
native, and drives real iOS/Android platform widgets through one shared engine.

```
app/      your app: pages (views/), components/, constants/, and app.chuks (routes)
core/     the framework: ui.chuks (the toolkit), the VM dev server, shared Yoga headers
ios/      the iOS/UIKit host + build/dev scripts
android/  the Android host + build script
```

You write Chuks in `app/`. You never edit `core/ui.chuks` or the hosts.

## Running the app

The commands live in `chuks.json` under `scripts` (they are documentation — run the
shell command shown, `chuks` itself does not execute them). A booted simulator /
emulator must already be running.

### iOS — hot reload (recommended for development)

```
cd ios && ./dev.sh
```

One command: builds the DEV host once, starts the Chuks VM dev server on `:7799`,
launches the app, then watches `app/app.chuks`. On every save it snapshots state,
restarts the ~1s VM server, and the running app remounts with its state preserved
(scroll position and keyboard focus survive too). No rebuild, no reinstall.

Edited the Swift host itself? Re-run once with `FRESH=1 ./dev.sh` to rebuild it.

### iOS — AOT build + launch

```
cd ios && FAST=1 ./build.sh     # fast dev build (keeps the AOT cache, ~seconds)
cd ios && ./build.sh            # cold, optimized release build
```

This compiles the engine into the app (no dev server), the true native binary.

### Android — AOT build + launch

```
cd android && ./build.sh
```

The same `app/` engine, cross-compiled to an Android `.so` with a JNI + Kotlin host.

### Other

```
chuks check app/_entry.chuks              # typecheck the whole app
chuks build app/_entry.chuks -o .out/e    # AOT-compile the engine only
chuks run core/devserver.chuks            # start just the VM dev server on :7799
```

## How it fits together

`app/app.chuks` registers your pages (`View`s) in a `NavStack`. Each page's
`render()` returns a UI tree built from the declarative builders in
`core/ui.chuks` (`Screen`, `Row`, `Column`, `Text`, `Button`, `TextInput`,
`List`, ...). The `Reconciler` diffs that tree into a minimal mutation stream the
native host applies to real platform views. Hover any builder in your editor for
its signature and an example.
