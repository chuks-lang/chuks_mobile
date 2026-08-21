# iOS engine: SwiftUI renderer

Status: in progress. Goal: a second, complete iOS renderer so a developer can pick
their engine in `chuks.json`, and get a first-class result either way. They still
write only Chuks Mobile; the engine is the native layer underneath.

```jsonc
// chuks.json
{ "iosEngine": "uikit" }    // current: mutation stream -> UIViews + Yoga flexbox
{ "iosEngine": "swiftui" }  // new: same stream -> native SwiftUI
```

## Why this is clean

The Chuks side never touches UIKit. The reconciler emits a host-agnostic mutation
stream (`C`/`S`/`P`/`T`/`I`/`R`) through the `chuks_*` cgo bridge. The UIKit host is
just one consumer of that stream. A SwiftUI host consumes the **same** stream and
the **same** bridge; only the rendering differs. This is React's multi-renderer
model (react-dom / react-native): one reconciler, many hosts. Nothing above the
stream changes: app code, reconciler, useState, the store, all identical.

## Design (native SwiftUI, "Option A")

The stream is imperative (create/insert/remove by id); SwiftUI is declarative. Bridge:

1. **Apply the stream to an observable node model.** A `Scene: ObservableObject`
   holds `nodes: [id: NodeData]` (kind, text, parsed style, child ids, action tag).
   `C/S/P/T/I/R` mutate this dictionary.
2. **Render the model recursively.** A `NodeView(id)` reads its node from the Scene,
   switches on `kind`, applies the style as SwiftUI modifiers, and recurses into
   children. `@Published` change -> SwiftUI re-renders (it diffs internally).
3. **Native layout.** The flexbox `Style` maps to SwiftUI: `d=row`->HStack /
   `d=col`->VStack, `gap`->spacing, `a`->stack alignment, `j`->Spacers + fill,
   `g`->`.frame(maxWidth/maxHeight:.infinity)`, `w/h`->`.frame`, `p`->`.padding`,
   `bg`->`.background`, `r`->`.cornerRadius`, etc. No Yoga.
4. **Events + loop.** Button/tap -> `chuks_dispatch(tag)` -> apply the returned diff.
   A timer drives `chuks_tick`. Input -> `chuks_dispatchInput`. Same bridge as UIKit.

## Phases

- [x] **P1 — Engine selection + core render.** `chuks.json "iosEngine"` (+ `IOS_ENGINE`
  env override); `build.sh` picks the host (SwiftUI links SwiftUI/AVKit, no Yoga).
  `ChuksAppSwiftUI.swift`: Scene (observable node model) applies the C/S/P/T/I/R
  stream; `NodeView` renders View/Text/Button/Image/Input/Scroll recursively with
  native SwiftUI layout (HStack/VStack, gap, align, justify via Spacers, grow via
  frame, padding/bg/radius/border/shadow/opacity); Button/tap → `chuks_dispatch`.
  SwiftUI `@main App`, no AppDelegate, no tick loop (events drive re-render).
  **Verified on the simulator:** the Home tab renders natively — heading, store-driven
  "3 alerts need attention", bordered card, full-width Continue button, and the tab
  bar with lucide icons (custom font). Built + launched clean on first pass.
  Known layout gaps for P2: NavBar title centers (should be leading); align-stretch
  default and justify between/around need parity work.
- [x] **P2 — Layout parity + View taps.** Home, Kit, and Alerts render at near-pixel
  parity with the UIKit engine (verified on the simulator). Fixed: grown text/leaf
  frames align leading (per `ta`), not centered (NavBar title). Added `TapAction`: any
  actioned non-Button/Input node (tab-bar item, chip, tappable card/row) gets a tap
  gesture → `chuks_dispatch`, matching the UIKit host. Interactivity wiring is standard
  SwiftUI (Button/onTapGesture → dispatch → @Published re-render); tap-driven flows
  (tab switch, toggle) need a device hand-test (no `idb` for headless taps here).
  Full layout model now correct (verified Home + Kit + Alerts pixel-close to UIKit):
  root fills height (screen fills, tab bar pins to bottom); align-items:stretch
  default (columns fill children to full width — cards, rows); flex-start packing
  via a trailing Spacer (NOT frame alignment, which collapses grow distribution —
  the SwiftUI gotcha that had the tab bar clustered left); justify center/end/between;
  grow children distribute evenly (tab bar). Remaining polish: justify `around`,
  toolbar button label centering — minor.
- [x] **P3 — Input + keyboard.** `Input` -> `ChuksInput` (a `TextField` that owns its
  text via `@State`, reports each change via `chuks_dispatchInput`, placeholder =
  node.text). Verified: the Login screen renders (fields, styling, justify-center
  layout). Tap-anywhere-to-dismiss keyboard added as a root `.simultaneousGesture`
  resigning the first responder (matches the UIKit host; needs a device tap-test).
- [x] **P4 — Scroll + List virtualization (renders; scroll-recycle needs device test).**
  `ChuksScroll` = ScrollView + a `ScrollOffsetKey` preference reporting the window to
  `chuks_setViewport` (direct, per scroll change). Content node whose children are all
  `pos:abs` renders as a `ZStack(topLeading)` with a full-height `Color.clear` ANCHOR
  (without it, `.offset` rows were clipped to a single row's bounds -> invisible; that
  was the bug). `BoxStyle` abs path: fill width + fixed height FIRST, then bg, then
  inset by left/right, then offset by top. Verified on the simulator: the 200-row
  `listtest` renders rows 0..N with correct positions/gaps. TODO: (1) confirm scroll
  mounts/recycles smoothly on device (was reported showing only the first window when
  the report was async-coalesced; now direct). (2) abs rows center their single-child
  content — real feed rows fill, but the plain test row shows centered text; make the
  row's inner stack fill width.
- [x] **P5 — Video.** `Video` -> `ChuksVideo` (a `UIViewRepresentable` over an
  `AVPlayerLayer`, muted + looping + resizeAspectFill, driven by its SwiftUI frame —
  matches the UIKit host's VideoView). Verified: `videotest` plays sample.mp4 in a
  rounded 300pt box. (Pooling/visibility-gating for a heavy feed is a later perf pass.)
- [ ] **P6 — Parity pass.** Render the same app on both engines; diff and close gaps.

## Primitive coverage (next: expand for BOTH engines + Android)

Current vocabulary — View, Text, Button, Image, Input, Scroll, Video — covers the
standard app UI but is not exhaustive. Planned additions (do for uikit + swiftui +
android): **Pressable** (modern touch responder, replaces Touchable*), **SafeAreaView**
(inset from notch/home indicator), **StatusBar** (OS status bar style), and more
(Switch/Slider/Picker/ActivityIndicator/Modal/KeyboardAvoidingView, etc.). Each new
kind is a node-kind -> host-view mapping in every engine; the app stays 100% Chuks.

## Verification bar

Each phase: the SwiftUI host builds and the tabs app renders + is interactive on the
simulator. The UIKit engine keeps working unchanged (default). Nothing committed
until hand-tested (per project rule).

## Notes / open questions

- SwiftUI re-renders the whole observed subtree on a `@Published` change; fine for
  app screens. If a heavy screen needs it, scope observation per-node later.
- List: SwiftUI has its own virtualization (`LazyVStack`); our reconciler also
  virtualizes. P4 decides whether the SwiftUI host honors our window protocol
  (keeps VM/AOT parity) or hands SwiftUI a lazy stack (more native, diverges from
  the UIKit host's item ids). Leaning: honor the protocol first, revisit.
- `-parse-as-library` + a SwiftUI `@main App` for the SwiftUI host (no AppDelegate).
