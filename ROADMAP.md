# Chuks Mobile: native app framework

Vision: **Chuks -> Chuks Bridge -> iOS / Android native render.** A React Native
style framework where the whole app (logic + UI) is written in Chuks, compiled
to a native library, and driven onto real platform widgets (UIKit `UIView`,
Android `View`) through a thin bridge, with the exact React DX so the RN mindset
transfers directly.

The DIFFERENTIATION vs React Native: Chuks is AOT-compiled and linked IN-PROCESS
(no separate JS VM), so the bridge is a same-address-space call, not a marshal
across runtimes. The payoff is a zero-copy / zero-serialization mutation path
(JSI/Fabric-class), which RN's architecture cannot do. STATUS: unproven. The
Phase 1 bridge is a serialized string protocol (RN's legacy bridge) chosen for
debuggability; the zero-copy path and the benchmark that would justify the claim
are Phase 1.5 below.

Test workspace: `Chuks-Projects/chuks-mobile`. Live code is `core/` (shared
engine) + `ios/` + `android/` (hosts) + `benchmarks/`. The Phase 0/1/1.5
prototype dirs (`proto/`, `bridge/`) were removed as superseded; those phases
below are kept as history (see "Repo layout" near the end).

Status legend: `[x]` done, `[~]` in progress, `[ ]` not started.

---

## Phase 0: prove the architecture in pure Chuks (DONE)

The whole RN-style pipeline, with a mock host that prints the mount protocol
instead of driving real views. Runs identically in VM and AOT.

- [x] Element model: `View(kind, props, kids)` + `El/VStack/Text/Button`
      factories (React's `createElement` shape, no JSX needed)
- [x] Component model: `class Counter extends Component` with inherited
      `setState` (clean inheritance DX, restored after the compiler fixes below)
- [x] Reconciler: diffs prev vs next tree, emits only the minimal mutations
      (a tap emits exactly `PROPS r.0 {text: ...}`, nothing else)
- [x] Mount protocol: the CREATE / PROPS / INSERT / REMOVE instruction stream a
      native host will consume
- [x] Verified: VM == AOT, minimal-mutation re-render confirmed

---

## Compiler foundation (DONE, shipped on branch v0.1.1)

Phase 0 forced out real AOT gaps in the Chuks compiler. All fixed, tested
(golden + fuzz), VM == AOT, full suite green. Commits:

- [x] `fix(aot): subclass/base polymorphism parity with the VM`
  - subclass -> base upcast at every position (argument, return, array element,
    push, recursive for nested slices), not just annotated assignment
  - function values (named or arrow) adopt the parameter's concrete return type
    instead of a hardcoded `interface{}`
  - virtual dispatch through a base-typed slice or field routes to the override
  - reflection coercer walks the embedded base pointer (nested base-typed
    literal divergence)
- [x] `fix(aot): make cross-module generic type placement deterministic`
  - deterministic (sorted) crt type hoisting
  - reference scan ignores comments and string literals (a runtime comment
    mentioning `*SomeType` was spuriously hoisting user types)
- [x] `test(aot): regression coverage for subclass/base polymorphism`
  - 3 golden tests + `polymorphic_upcast` fuzz fragment

### Known limitation (tracked, not blocking)
- [ ] Nested array **literal** with differing subclasses per inner slice
      (`[]([]Base) = [[Dog], [Cat]]`) still diverges in one narrow shape. The
      `.push()`-built equivalent works. Low priority; revisit if it shows up.

---

## Phase 1: native bridge (IN PROGRESS)

Goal: compile the Chuks app to a native library and drive the mount-instruction
stream onto real `UIView`s. Success = one real tap moves a real `UILabel`.

Proof so far lives in `bridge/` (run `bridge/build.sh`): a Chuks engine
(reconciler + Counter) compiled to a Go c-archive, linked into a plain C host
that receives the mount protocol across the C ABI. This validates the riskiest
assumption (Chuks runs inside a C/Swift process) before any Xcode work.

- [x] Compile the Chuks engine to a Go `c-archive` (`.a` + header) callable from
      C. Verified: C called a Chuks function and got its return value across the
      ABI, and received the full mount stream + a tap re-render (minimal mutation).
- [x] Mount protocol over the C boundary: newline-delimited `OP|args` wire
      format (`CREATE|id|kind`, `PROPS|id|k=v,...`, `INSERT|id|parent|idx`,
      `REMOVE|id`) returned as a C string. Real reconciler diff crosses intact.
      CLEAR-EYED: this is a SERIALIZED STRING protocol == React Native's legacy
      bridge (serialize -> ship -> parse). It is debuggable and proves the path,
      but it is NOT zero-copy and does NOT demonstrate any advantage over RN. The
      differentiation (below) is still unproven.
- [x] Host parses the command stream and mounts REAL native views. Proven on
      macOS/AppKit (`bridge/macos/`, run `build_macos.sh`): the stream builds a
      real `NSStackView` + `NSTextField` + `NSButton`. The parse/mount logic is
      platform-agnostic; UIKit is a view-class swap (NSStackView -> UIStackView,
      NSTextField -> UILabel, NSButton -> UIButton).
- [x] Event path back (verified headless): a tap calls `chuks_tap`, the engine
      re-renders and returns the minimal mutation, and the host applies it to the
      SAME live `NSTextField` object (identity confirmed) — a diff-driven update,
      not a re-mount.
- [x] Interactive shell (macOS): a real `NSWindow` + `NSApp.run`, with the
      `NSButton` wired via `target/action` to `chuks_tap`. Run
      `bridge/macos/build_macos_gui.sh` for the clickable window (or with
      `CHUKS_SMOKE=1` to auto-click once and quit). Verified: a click drives
      native event -> Chuks engine -> live `NSTextField` update via the diff.
- [x] iOS/UIKit port: `bridge/ios/` mirrors the AppKit host with UIKit classes
      (UIStackView / UILabel / UIButton) in a `UIViewController`. `build_ios.sh`
      cross-compiles the engine to an iOS-simulator c-archive, links a UIKit app,
      assembles a `.app` (no Xcode project needed), and installs + launches it in
      a booted simulator. Verified: real `UILabel` + `UIButton` render on screen
      (screenshot), and `--smoke` confirms tap -> chuks_tap -> live label update
      ("Count: 0" -> "Count: 1"). PHASE 1 COMPLETE.

### Bridge learnings (from the Phase 1 proof)
- A c-archive never runs `main()`, and Chuks assigns its globals INSIDE `main()`.
  The host must call an exported `chuks_init()` (which calls `main()`) once
  before any other entry point, or globals are nil. This is the key gotcha.
- Entry points are plain Chuks `export function`s; cgo `//export` wrappers (in
  `bridge_export.go`) convert Go strings to `*C.char` (with a `chuks_free`).
- Current bridge is pull-based (host calls in, gets a command buffer back). A
  push-based callback (engine calls a native fn per mutation) is an option later
  if streaming is needed; pull is simpler and fine for now.

## Phase 1.5: prove the differentiation (zero-copy) — THE THESIS

Phase 1 works but uses a serialized string protocol (RN's legacy bridge). This
phase replaced it with a binary shared-arena protocol and MEASURED it against
serialization. Result: MIXED, and stated honestly below. Reproduce with
`benchmarks/bench/` (harness kept; prototype removed).

- [x] Zero-copy write path proven: the host allocates a shared arena via
      `crt.Chuks_c_alloc`, hands the SAME handle to Chuks (so `c.writeInt32Slice`
      writes into it), and reads the records back by pointer. Verified the bytes
      land at the host-visible address (no copy, no serialize, no parse). This
      also resolves the Go-GC concern: the arena is libc-malloc'd, not Go-heap.
- [x] Benchmark, STRUCTURAL / NUMERIC mutations (ids, indices, enum kinds,
      numeric props): binary is ~17-18x faster and ~3x lower allocation than the
      serialized string protocol, and the gap widens with tree size (1k -> 50k
      nodes). Checksums match. THE THESIS HOLDS for this class of mutation, which
      is the bulk of a real stream (CREATE/INSERT/REMOVE + layout/numeric props).
- [x] Benchmark, TEXT values: binary is ~2.0x faster and ~2x lower allocation
      (checksums match). The right primitive already existed (`c.packStrings`:
      Arrow-style offsets + concatenated UTF-8, single pass) plus `c.offset` for
      sub-regions; no per-byte push needed. The win is smaller than numeric
      because both paths still build the N strings and bytes are bytes, but the
      binary path avoids the delimiter framing/parse and the `C.CString` copy.
- [x] Found + fixed a real AOT bug in the process: `chuks_c_pack_strings` and
      `chuks_c_strings_byte_length` only accepted `[]interface{}`, so a typed
      `[]string` (what AOT passes) silently wrote nothing — worked in the VM,
      no-op in AOT. Fixed in `go_transpiler_chukstoc.go` (accept typed slices).
- [ ] Honest ceiling: the final value is still copied into the platform view's
      own string storage (`UILabel.text`). "Zero-copy" means our pipeline adds no
      serialize/parse/intermediate copy on top of that OS-API copy, not that the
      OS never copies.

- [x] Realistic reconciler frame (2000-row list, subset updating per frame,
      `bench_frames`): the DIFF dominates a frame (~186-237 us for 2000 rows) and
      the bridge is a small fraction unless most rows change. Whole-frame speedup:
      ~1.0x (light churn) -> ~1.1x (100/2000) -> ~1.8x (all rows). The 17x bridge
      win is real in isolation but only a fraction of a real frame for typical
      updates. Bridge matters most for update-heavy screens; for typical frames,
      the reconciler diff (and, unmeasured, view application) dominate.

WHAT WE CAN CLAIM (measured): the binary shared-arena protocol encodes+decodes
~17x faster than the serialized string protocol in isolation (~2x for text),
checksums matching; in a realistic frame that yields ~1.0-1.8x whole-frame
speedup depending on churn.

WHAT WE CANNOT YET CLAIM: "Chuks's mobile bridge is zero-copy and faster than
React Native end-to-end." Two reasons, and two measurements that would settle it:
- [x] Real boundary crossing WITH view application (`bench/macos-frame/`,
      `build_frame_macos.sh`): a Swift/AppKit host decodes each protocol into
      actual NSTextField.stringValue mutations. Result (2000 rows): the 17x
      isolated-bridge win collapses to ~1.05x whole-frame for typical churn (100
      rows) and ~1.23x for all-rows-change, because REAL VIEW APPLICATION is a
      large SHARED cost (670-879 us to set 2000 labels) both protocols pay
      identically. Confirmed the caution: the bridge is real but a modest slice of
      a real frame; it matters only under heavy churn. (Sanity check: this mirrors
      the known RN reality that Fabric's win over the legacy bridge is real but
      modest for typical UIs, larger for heavy updates.) Scope: measures view-
      OBJECT mutation (stringValue setter + NSString storage), NOT layout/display
      — that is #2.
- [x] Real frame on the iOS simulator (`bridge/ios-bench/`, `build_ios_bench.sh`):
      a CADisplayLink-driven list of 120 UILabels, EVERY row updated every frame
      via the Chuks reconciler + string bridge + real UILabel.text apply. Result:
      ~1.06 ms/frame (max 1.16), holding 60 fps — 6% of the 16.7 ms budget, ~16x
      headroom, on the real UIKit/CoreAnimation pipeline. (Simulator, not a device:
      indicative, not final — a device would be somewhat higher but well within
      budget for this load.) CONCLUSION: the Chuks->iOS path is comfortably
      real-time for normal-to-heavy UIs; the bridge was never the bottleneck.
- [x] KEY iOS finding: chuksToC's `c.alloc` does NOT work under iOS (the sandbox
      blocks dlopen of libc malloc), so the binary shared-arena / "zero-copy"
      bridge is MACOS-ONLY — it can't run on the real target. iOS uses the plain
      string/cgo bridge (proven: 60 fps above). This retires the zero-copy effort:
      wrong lever AND unavailable where it matters.
These two are the measurements that actually settle the thesis. Until then: the
bridge is faster in isolation (proven), its whole-frame impact is churn-dependent
and modest for typical updates (measured), and end-to-end/on-device is unproven.

## Phase 1.6: the real "vs RN" number — reconcile, memoized, vs Hermes

The zero-copy bridge work was removed (macOS-only, wrong lever). The real
question — is native logic faster than RN's JS? — needed a REAL memoizing
reconciler (View tree, memoized Row components with referential bailout,
recursive tree-diff — NOT a flat loop) benchmarked against Hermes (RN's actual
engine, a bytecode interpreter), not V8. Done (`benchmarks/bench/recon/recon2.chuks` +
`recon2.js`), all in Chuks AOT, checksums match (1,000,000 mutations):

  Chuks AOT (native)             37 us/frame
  Hermes (RN's engine)          186 us/frame   -> Chuks 5.0x FASTER
  V8 (Node; RN does NOT use it)  16 us/frame   -> Chuks 2.3x slower

- [x] With a REAL tree-diffing memoized reconciler (node allocation + traversal
      included), Chuks reconciles ~5.0x faster than RN's real engine. The real
      reconciler slowed every engine ~3-4x vs a flat loop (allocation cost) but
      the ratios held (Chuks-vs-Hermes even rose 4.4x -> 5.0x). The earlier
      "slower than RN" was an artifact: comparing vs V8 (not what RN ships) with
      a strawman reconciler that rebuilt every row every frame.
- [x] Note on rigor: the FIRST recon bench was a flat int-array loop that only
      mimicked the memo cost profile — not a real reconciler. recon2 is the real
      thing (View nodes, memo bailout, recursive diff); the 5.0x is the honest
      number and it includes reconciler allocation/traversal.
### Closing the V8 gap (codegen, in progress — Chuks-core, not needed to beat RN)
- [x] Constant-divisor `%`/`/` inline: `x % 97` now emits native Go `%` instead
      of a __chuks_mod_i64 guard call (only for provably non-zero literals; a
      variable divisor keeps the guard, so divide-by-zero still panics). Numeric
      loop ~160 ms -> ~60 ms (now beats hand-Go and V8). Full suite green
      (Go + VM 392/0 + AOT 392/0), no regression. Ready to commit to v0.1.1.
- [ ] `string(int)` fast path: currently `string(i)` compiles to
      `Chuks_string(i).(string)` — boxes the int64 to interface{} + routes
      through reflection/fmt (~92 ns/render-line). A direct `strconv.FormatInt`
      is byte-identical and ~2.3x faster (40 ns) — which is exactly the
      reconciler's V8 gap (37 vs 16 us), so this one change could close it.
      Higher risk (touches every string() in the language) -> surgical edit +
      full regression suite required.

Net for the whole performance thesis: the bridge is a non-issue (~1 ms/frame on
real iOS, 60 fps, 16x headroom); the durable win is native logic/reconcile,
which beats RN's Hermes ~4.4x. "Faster than RN" is now a measured result on the
part that's native-vs-JS, using RN's actual engine.

## Phase 1.7: unified end-to-end framework core (DONE)

The three proofs (memoized reconciler, mutation-emitting reconcile, iOS string
bridge) are now ONE working pipeline. `ios/` (run `ios/build.sh`, engine in `core/`): a live
"Chuks-driven dashboard" of 40 rows whose entire UIView tree is created AND
updated from the Chuks reconciler.

- [x] Memoized component tree (`Row` components cache their View node; unchanged
      rows return the same reference).
- [x] Reconciler emits the MINIMAL mutation stream: on each tick it bumps a
      rotating window of 5 rows and emits exactly ~5 PROPS (the unchanged rows
      bail via referential equality). Verified live on the iOS simulator: 40 real
      UILabels, header shows "5 mutations (only changed rows)", and the visible
      values prove it (only the touched rows advance).
- [x] Host applies the CREATE/PROPS/INSERT/REMOVE stream to real UIViews; a
      Timer drives ticks. This is the real component -> native pipeline, not the
      counter and not a microbenchmark.
- Next within this line: this is still Text-in-VStack only. Real screens need
  layout (Phase 2) and a wider widget/prop/event surface.

## Phase 2: real UI surface (IN PROGRESS)

Decision made and built: **adopt Yoga** (the exact flexbox engine RN ships)
rather than a homegrown layout engine. Rationale: performance between the two is
a wash (both run native; both beat RN's JS-driven layout, and layout isn't the
frame bottleneck), so the deciding factor is solidity, and Yoga is provably solid
on day one. Owning a flexbox engine is a smaller version of the Impeller trap
(deep infra where competitors quietly become toy). We rent Yoga like we rent the
renderer; we can swap in a native engine later if it ever proves a differentiator.

Working end-to-end on the iOS simulator (`ios/`, run `ios/build.sh`; engine in `core/`): a card-list
dashboard laid out by real Yoga flexbox. Each card is a ROW with a fixed 40x40
avatar, a flex-grow middle COLUMN (title + subtitle), and a trailing value that
ticks. That is layout a stack view cannot express.

- [x] **Layout engine: Yoga, statically linked + cross-compiled for iOS-sim**
      (208K arm64 archive, 0 errors). Chuks emits the tree + flexbox style; the
      Swift host maintains a parallel Yoga shadow tree, computes layout, and
      copies the parent-relative rects onto UIViews (Yoga coords map 1:1 to
      `UIView.frame`). This is RN's architecture with Chuks in place of JS.
      NOTE: Yoga is called from the native host, not from Chuks via chuksToC
      (chuksToC's runtime dlopen is blocked on iOS) so this sidesteps that limit.
- [x] **Real flexbox props**: flexDirection, justifyContent, alignItems,
      flexGrow, width/height, padding, gap, plus visual (bg, fg, fontSize,
      fontWeight, textAlign, cornerRadius). Serialized in the STYLE mutation.
- [x] **Text is first-class**: `YGNodeSetMeasureFunc` measures each UILabel's
      actual text, so text self-sizes (no manual widths/heights); text changes
      mark the node dirty so Yoga re-measures.
- [x] **Minimal diff survives layout**: memoized cards return their cached Node,
      so ~5 mutations/frame regardless of N; verified live (rotating window,
      only ticked cards update, rest sit still).
- [x] **Events (tap)**: a node carries an action tag (emitted as a T mutation);
      the host wires a UITapGestureRecognizer that sends the tag through
      `chuks_event` into `dispatch()`, which mutates real state (toggles card
      selection) and runs the SAME reconciler, so only the tapped card's changed
      bg flows back (~1 mutation). Verified: tapped cards recolor and the
      selection persists across timer ticks (real state, memoized re-render).
      This closes the "renderer vs framework" gap: it's now interactive.
- [x] **Virtualized list (FlatList-equivalent)**: cards are absolutely
      positioned by index (Yoga position:absolute + top/left/right), so the
      engine mounts only the visible window and recycles the rest. Keyed ids
      (`c<index>`) mean a card's views survive as the window shifts. The host is
      a UIScrollViewDelegate; each scroll reports the offset via `chuks_viewport`
      and only crossing a row boundary produces mutations. Verified: **1000
      cards, ~100 live views**, correct recycling when jumped to card 500, and
      offscreen value ticks don't mount (cards show current value on entry).
      Removal frees the whole Yoga subtree + drops both maps by id prefix +
      cleans up tap recognizers, so live-view count stays bounded.
- [x] **TextInput (native -> Chuks value)**: a native UITextField's
      `.editingChanged` sends each keystroke through `chuks_set_query` into
      `setQuery()`, which re-filters the 1000-card list (case-insensitive
      substring match implemented in Chuks) and re-renders from the top. Cards
      filtered out emit R; survivors reposition to their new display RANK (the
      list now positions by rank, not fixed index, so results repack
      contiguously). Verified: filtering to "7" shows 7/17/27/37... packed from
      the top, still virtualized (79 live views). This completes the input story:
      both discrete (tap) and continuous (text) native input drive Chuks state.
- [x] **Image primitive**: `Image` node -> UIImageView; style `img=<SF Symbol>`
      + `fg` tint. Avatars are now real images (bolt/cloud/gear/wifi/cpu/globe,
      cycling per card), not colored boxes.
- [x] **Button primitive**: `Button` node -> UIButton with real press states and
      its OWN action (touch-up), distinct from a View's tap recognizer. The
      trailing value is a Button whose `inc:<id>` action increments that card;
      the card body's `tap:<id>` selects. Verified both fire independently
      (button 1->4 on card 2, tap-select on card 4).
- [x] **Keyboard events**: return key dismisses (UITextFieldDelegate), and
      keyboard-avoidance insets the scroll (keyboardWillShow/Hide) so rows are
      never covered. `keyboardDismissMode = .onDrag` too. (Code is standard; not
      interactively re-verified because the sim has no CLI keyboard injection.)
- [x] **Cleanup**: `-Wincompatible-sysroot` silenced (spurious: the link IS
      using the sim sysroot; swift-driver passes GNU `--sysroot` to clang which
      warns pre-`--target`). Fixed with `-Xclang-linker -Wno-incompatible-sysroot`.
- [x] **ScrollView-as-node**: the list is now a Chuks `Scroll` node (maps to
      UIScrollView) holding a `content` node sized to the full filtered list.
      The host discovers the scroll from the tree (not hardcoded), drives its
      viewport back to Chuks (`chuks_viewport`), and sets contentSize from the
      content node. Key fix: the scroll needs `flex-basis: 0` (Yoga's default
      flex-shrink is 0, so otherwise it grows to its ~74000pt content and breaks
      virtualization). Verified: virtualization holds (76 live views @ 1000).

### The host is now a generic runtime (100% Chuks app)

DONE. The whole app UI is declared in `engine.chuks` as a node tree
(`app` column -> [`search` Input, `list` Scroll -> `content` -> cards]); the
Swift host creates NO app-specific widgets. It knows only a vocabulary of node
KINDS (View/Text/Image/Button/Input/Scroll) and renders whatever tree Chuks
emits, special-casing only structure: `Scroll` (viewport -> Chuks) and `Input`
(value -> Chuks, via `chuks_input`). The search bar is now a Chuks `Input` node,
verified filtering end-to-end. Only the diagnostic header + the tick timer remain
host-side (both are demo scaffolding, not app UI). "Developers write Chuks,
never Swift" is now true for this app.

### AOT compiler bug found (worked around, worth fixing upstream)

`[]Node?` (array of nullable elements) is valid and works. The AOT-only crash
("Internal type error: invalid operation: nil is not an interface") fires ONLY
when a bare `null` literal is assigned to a class-typed optional element at
RUNTIME: `arr.push(null)` or `arr[i] = null`. Literal-position nulls
(`[a, null]`), primitive-element optionals (`[]string?`), empty init, reading,
and `== null` are all fine; the VM runs it fine too, so it is a Go-codegen gap
(untyped nil emitted where the element's erased interface type is required).
Worked around by keeping `CARD_NODE: []Node` + a parallel `MOUNTED: []bool`,
because unmounting needs a runtime `= null` (the exact broken path). Minimal
repro set is in the session scratchpad (nulltest/a-h).

## Phase 3.5: RN/Compose-shaped component API (DONE)

The low-level engine got a declarative authoring layer on top (in `core/ui.chuks`),
so app code reads like Compose/Flutter/SwiftUI. The engine underneath (reconciler,
mutation stream, Yoga, hot reload, both hosts) is unchanged.

- [x] **Named-prop builders**: `Row`/`Column`/`Text`/`Icon`/`Button`/`Pill`/
      `TextInput`/`Screen` take `{ prop: value }` (identifier-keyed maps) + a
      children array (containers) or a content value (leaves). Rule, total:
      a node has children OR content, never both. Replaced the positional-arg
      builders. (Old lowercase primitives are now internal `mk*` fns -- exported
      lowercase + capitalized twins collide as the same Go symbol.)
- [x] **Closure events**: `onPress`/`onChange` are real closures. The Reconciler
      keeps a handler table keyed by node id; the closure lives Chuks-side and
      only the tag (the id) crosses the host boundary -- works for cgo AND HTTP.
      No more `"tap:"+id` string tags or `charCodeAt` parsing in app code.
      Verified: toolbar self-state, card select, pill increment all fire.
- [x] **`List` with explicit keys** (the correctness fix): `List({ count, key,
      item, rowHeight, gap, pad })`. `key(rank)->identity` and `item(rank,top)->
      Node` are callbacks; the framework (`Reconciler.reconcileList`) virtualizes.
      Identity is `key(rank)`, NOT the rank, so a row survives when a filter moves
      it. Verified: filtering to "23" repacks 23/123/223/... and Service 23 keeps
      its ticked value after moving rank 23 -> 0 (state + views survived).
- [x] **`Screen` + declarative root**: the screen is now `render()` returning
      `Screen({bg}, [ TextInput, toolbar, List ])`; the framework drives
      mount/scroll/tick/events via `Reconciler.renderRoot`. The hand-written
      `emit("C|app|View")` scaffold and the `renderWindow` math are GONE from
      `dashboard.chuks`.

- [x] **`App` base + Expo-style entry split**: `core/ui.chuks` has an `App` base
      that drives the lifecycle (mount/setViewport/tick/dispatch/dispatchInput/
      drain); a screen `extends App` and overrides hooks (setup/render/onTick/
      save/load). `dashboard.chuks` lost all the plumbing. The native contract
      CAN'T live in the framework (the transpiler tree-shakes exported fns no
      Chuks code calls; no re-export syntax; only the entry module keeps bare
      names for cgo), so it sits in `app/_entry.chuks` -- the `_`-prefixed
      platform glue you never edit (like Expo's generated entry / RN index.js).
      The build compiles `_entry.chuks`, which imports the CLEAN `app/app.chuks`:
        import { Dashboard } from "./views/dashboard.chuks";
        export function createRoot(): Dashboard { return new Dashboard() }
      That's the whole app root -- "register your page." `_entry.chuks` does
      `var APP = createRoot()` (type-INFERS the concrete Dashboard, so overridden
      hooks dispatch -- no base-typed-var gap) + 9 one-line forwarders.
      GOTCHA/upstream: `var APP: App = new Dashboard()` (explicit base type) calls
      the empty BASE method (no virtual dispatch through a base-typed var in AOT);
      inference to the concrete type avoids it. Recorded as a compiler gap.
      NEXT (the user's model): `app.chuks` registers ROUTES (multiple pages);
      each View is a page of components (Login.chuks, Dashboard.chuks); a NavStack
      / router picks the page. Not built yet.

Deferred (named, decided): the `this.` density (class components carry it;
function components + hooks are the path to bare names -- Phase 3 hooks item);
multi-conditional styling idiom (computed locals + a `variant(state, map)`
helper); container-instance-vs-builder-call mental model (documented rule:
"hold instances, call builders").

## Phase 3: DX parity with React Native (IN PROGRESS)

- [x] **`Component` base with automatic memoization**: a component now declares
      only `key()` (a string of everything its output depends on) and `render()`
      (build the Node tree). The base's `view()` caches the rendered node and
      re-renders only when `key()` changes, so the reconciler's referential
      bailout drops unchanged subtrees for free. `Card` dropped all its
      `last`/`cached` bookkeeping — a new component is now just those two methods
      plus state fields. Verified on iOS: identical render, 76 live views @ 1000,
      minimal diffs intact. Also let the engine drop the `[]Node` + `[]bool`
      virtualization workaround for a clean `[]Node?` (the AOT null fix landed).
- [x] **Declarative element builders**: `label`/`icon`/`column`/`pill`/`rowAt`
      (the start of a `@chuks/ui` vocabulary) so `render()` composes a tree
      instead of building Style objects field-by-field. `Card.render()` dropped
      from ~30 lines of `new Style(); s.x=...` to an 8-line composed tree that
      reads like Flutter/SwiftUI. Renders identically (verified iOS).
- [x] **Self-contained component state (setState)**: a persistent `Toolbar`
      component owns its own `count`; its `onTap()` mutates it and the framework
      re-renders ONLY its subtree (one diff), with the parent threading no props.
      Verified on iOS (tapping it goes 0->3, cards/search untouched). Two models
      now coexist cleanly through the same reconciler: store-driven props for the
      recycled cards (state must survive unmount), and component-local state for
      persistent components. This closes the core authoring story.
- [x] **Framework / app split** (`@chuks/ui`): the toolkit (`Style`, `Node`,
      builders, `Component`, and a `Reconciler` class that owns the mutation
      buffer) now lives in `core/ui.chuks`, authored once. The app -- `Card`,
      `Toolbar`, theme, data, the screen + lifecycle -- lives in `app/app.chuks`
      and `import`s the toolkit. This is where a user writes their app; they
      never edit `core/`. Multi-file compiles through AOT (verified: the c-archive
      built from `app/app.chuks` importing `../core/ui.chuks` renders identically,
      1000 cards / 77 live views) and through the VM dev server. The `Reconciler`
      being a class (not module state) is what makes the import boundary clean --
      no shared mutable state crosses it.
- [x] **Dev loop** (`ios/dev.sh`): watches `core/engine.chuks` + the host and,
      on save, rebuilds FAST (`build.sh FAST=1`: keeps the AOT cache + swiftc
      `-Onone`) and reinstalls/relaunches on the booted simulator (~3-4s
      edit-to-screen, down from a ~cold pass; swiftc `-O` at 2.1s was the tax).
      Honest ceiling: AOT on iOS links the engine in and iOS blocks arbitrary
      dlopen, so this is a fast COLD reload (app restarts), not in-place hot
      patching. True state-preserving hot reload needs a VM dev-server (interpret
      `engine.chuks`, stream the mutation protocol to a thin host over a socket)
      -- documented in `dev.sh`, not built.
- [x] **State-preserving hot reload via a VM dev-server (the real Fast Refresh)**.
      `core/devserver.chuks` runs the SAME engine in the Chuks VM and serves the
      mutation protocol over HTTP (`std/http`). The host, built ONCE with `DEV=1`,
      fetches frames over HTTP instead of the AOT-linked engine, so editing
      `core/engine.chuks` only restarts the ~1s VM server -- NO app
      rebuild/reinstall. The running app detects the restart and remounts; engine
      state (values/selection/filter/toolbar) is snapshotted to a file the new
      server restores on boot, and host UI state (scroll/focus) persists for free
      because the process stays alive. `ios/dev.sh` orchestrates it (build-once +
      server + watch → snapshot → restart). Verified end-to-end: edited the
      toolbar text in `engine.chuks`, restarted the server, and the running app
      showed "HOT-RELOADED (edit me)" with no rebuild. Ceiling honestly noted:
      this is the DEV path; release builds still AOT-link the engine (cgo).
- [ ] Bridge codegen so new native widgets are cheap to add

## Repo layout (reorganized)

- `app/`  -- **where the user writes their app**, organized like an RN/Flutter
  project (imports the toolkit from `core/ui.chuks`):
  - `app.chuks` -- thin entry: forwards the host contract to the screen.
  - `constants/theme.chuks` -- colors + layout tokens + `symbolFor`.
  - `components/card.chuks`, `components/toolbar.chuks` -- components.
  - `views/dashboard.chuks` -- the screen, a `Dashboard` class holding all state
    + lifecycle (so state is encapsulated, not module-global). Verified: this
    5-file, multi-folder app compiles through AOT and renders identically.
- `core/` -- the framework, authored once: `ui.chuks` (the `@chuks/ui` toolkit --
  Style/Node/builders/Component/Reconciler), the cgo bridge (`frame_export.go`),
  the VM hot-reload dev server (`devserver.chuks`), and the shared Yoga headers
  (`yoga/include/`, byte-identical across platforms).
- `ios/`  -- the iOS/UIKit host (`ChuksApp.swift`, `build.sh`, `dev.sh`) + the
  iOS-sim `yoga/libyoga.a` (platform-specific machine code; only the `.a` lives
  per host now, headers are shared from core).
- `android/` -- the Android host (`MainActivity.kt`, `jni.cpp`, `build.sh`) +
  the Android `yoga/libyoga.a`.
- `benchmarks/` -- the perf-proof harnesses kept from the old `bridge/`:
  `bench/recon/` (the memoized reconciler vs V8/Hermes) and `bench/bench.chuks`
  + `ios-bench/` (the frame / zero-copy benchmark).

NOTE: the Phase 0/1/1.5 PROTOTYPE code has been **removed** as superseded by
`core/ios/android` -- `proto/ui.chuks` (the original reconciler) and `bridge/`
(the c-archive bridge proof, the macOS/AppKit + iOS-counter hosts, the zero-copy
arena experiment). The Phase 0/1 sections below describe that work as history;
their `proto/`... and `bridge/`... paths no longer exist on disk. Only the
benchmark harnesses survived, in `benchmarks/`.

## Phase 4: Android (DONE — thesis proven)

The SAME `engine.chuks` runs on Android, driving real Android Views with real
Yoga layout. Built + verified on the emulator (`android/`, run `android/build.sh`).

- [x] **Android host consuming the same mount protocol**: `MainActivity.kt` is
      the Kotlin analogue of `ChuksApp.swift` — it mirrors the identical
      C/S/P/T/I/R stream into two lockstep trees (Android Views + a Yoga shadow
      tree), runs Yoga each frame, and copies the rects onto the views via
      FrameLayout margins. Verified: 1000-card list renders, **virtualized to
      ~106 live views**, and the Chuks `Input` node filters live (typing "223"
      -> just Service 223, 10 live views).
- [x] **Shared engine, platform-specific hosts only**: the engine is compiled
      unchanged (`chuks build ../core/engine.chuks`); only the host differs.
      Stack: Go `-buildmode=c-shared` -> Android arm64 `.so`; a JNI bridge
      (`jni.cpp`) exposing the same `chuks_*` exports (the iOS `frame_export.go`,
      reused verbatim) + a compact handle-based Yoga API; Yoga's C++ recompiled
      for Android arm64; a Kotlin host; assembled into an APK BY HAND
      (aapt2/d8/kotlinc/zipalign/apksigner) because the JDK here (26) is newer
      than the Android Gradle Plugin supports.
- Host differences (expected, hosts differ): avatars are plain circles (SF
  Symbols are iOS-only); text self-sizes eagerly in Kotlin (measure the
  TextView) instead of via a Yoga measure callback; must bundle
  `libc++_shared.so` (cgo links it despite `-lc++_static`).

---

## Open questions

- ~~Layout engine: adopt Yoga vs a minimal Chuks-side layout?~~ **ANSWERED:
  Yoga**, called from the native host (not Chuks, whose FFI is blocked on iOS).
- Threading model: engine on its own thread, host applies mutations on the UI thread
- Prop encoding across the C ABI: struct-per-node vs a single command buffer
- How much of the RN component API to mirror vs a Chuks-native API
