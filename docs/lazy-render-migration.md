# Lazy top-down render: migration plan

Status: COMPLETE (P1–P7 done; P8 assessed as not needed). Owner: framework.
Last updated: 2026-08-19.

## Why

Today Chuks Mobile builds the `Node` tree **bottom-up and eagerly**: in
`Column({...}, [ Counter(), Counter() ])` the `Counter()` children finish
building before their parent exists, so a component never knows its position in
the tree. The `Reconciler` only assigns structural path ids (`app`, `app.0.1`)
*afterward*, in a separate top-down walk.

That one fact is the root cause of every state-management compromise we hit:

- no per-instance identity at render time, so local component state can't be
  automatic,
- `useState` had to fall back to call-order (React's "rules of hooks") or manual
  keys,
- no place to hang lifecycle (mount/unmount/effect),
- the whole visible tree is rebuilt eagerly even for off-screen work.

Every mature framework (React fiber, Flutter Element, SwiftUI identity node)
solves this the same way: components are **lazy descriptions the framework calls
top-down**, so each one knows its position *before* it runs. We are moving Chuks
Mobile to that model.

Proven first (scratch prototypes, VM + AOT identical): a lazy element tree walked
top-down gives automatic per-instance identity with **no keys and no ordering
rule**. This plan lands that in the real framework.

## The model

- **Host primitives stay eager.** `Row/Column/Text/Button/...` still return a
  `Node` immediately — they hold no state, so they need no identity.
- **Components become lazy.** A component is placed with `Comp(render)` where
  `render: function(): Node`. `Comp` does **not** call `render`; it stores it.
- **The reconciler expands components during its existing top-down walk.** When it
  reaches a `Comp` node at path `P`, it sets the render context to `P`, calls
  `render()` (now the component runs, knowing its identity), and reconciles the
  produced node at `P`. A `Comp` is transparent in the native tree (like a React
  component: it adds identity, not a host view).
- **State lives outside the component, keyed by identity.** `useState(initial)`
  reads the current path from the render context. No key argument. Any type.
- **Lists already provide keyed identity.** `List` keys each row by `key(rank)`,
  so a `Comp` inside a row inherits reorder-safe identity for free. `KeyedComp(key,
  render)` covers the rare hand-rolled loop.

### Author-facing before / after

```chuks
// before: eager call, no per-instance state possible
Column({...}, [ Counter(), Counter() ])

// after: lazy placement, each Counter gets automatic independent state
Column({...}, [ Comp(Counter), Comp(Counter) ])

// a component now just uses state, no key, any type:
function Counter(): Node {
    var count = useState(0)
    var n: int = count.get()
    return Button({ onPress: function(){ count.set(count.get() + 1) } , ... },
                  "taps: " + string(n))
}
```

Pure/controlled components (no state) may still be called directly; only stateful
components need `Comp`.

## Phases

- [x] **P1 — Hook store.** New `core/state.chuks`: render-context (`_path`,
  `_slot`, `_cells`), `hookEnter(path)`, `useState(initial)`, `useKeyedState(key,
  initial)`, `Cell` handle (`get()/set()`), plus `hookBegin`/`hookSweep`. Verified
  VM + AOT (persist across renders, unmount frees, keyed works). Note: `_seen`
  reset needs a typed empty literal (`var e: map[string]bool = {}`) or AOT rejects.
- [x] **P2 — Lazy element in core.** Added `comp`/`produced`/`ckey` to `Node`,
  `Comp(render)` and `KeyedComp(key, render)` builders. Reconciler `mountNode` +
  `reconcile` expand `Comp` nodes (set identity, call render, diff produced node
  against last produced; a Comp is transparent, reconciled at its own id).
  Backward compatible: node ids are unchanged, existing eager screens unaffected.
- [x] **P3 — Lazy root.** `NavStack.render()` returns `Comp(currentScreen)`; the
  App lifecycle methods bracket every render with `hookBegin`/`hookSweep`.
  `TabView` places the active screen via `KeyedComp("tab:"+active)` so each tab has
  its own state identity. App builds + launches on iOS.
- [x] **P4 — State cleanup.** `hookSweep()` after each render frees cells whose
  identity wasn't visited (React's dispose-on-unmount). Verified: an unmounted
  component's cell is freed and a remount starts fresh.
- [x] **P5 — Migrate components.** Migrated all remaining local state off module
  vars to `useState`: `Kit` (switch/chips, verified on iOS), `Login` (email/password
  draft fields), and the `Toolbar` demo. Added a live "Local state (useState)" card
  to the Kit gallery with two independent `Comp(Toolbar)` instances (headless VM+AOT:
  #0→2, #1→1 independent). `home`/`profile`/`alerts` have no local state (stateless).
  No module-var local state remains in `app/`. iOS visual check confirmed: the Kit
  "Local state (useState)" card renders both `Comp(Toolbar)` instances on device.
- [x] **P6 — Class components (SwiftUI/React style).** Added a `Component` base
  class (`extends Node`, kind "Comp") with an overridable `render(): Node`, and a
  unified `Node.render()` so the reconciler dispatches: a `Comp(fn)` node runs its
  closure, a `Component` subclass runs its overridden `render()` (virtual dispatch).
  `new Counter(props)` is a lazy placeable node (construction != render), placed
  DIRECTLY in a `[]Node` children list — no wrapper — with `.key(id)` chaining.
  Both styles coexist: function components use `Comp(fn)`, class components use
  `new`. Enabled by the compiler AOT fix (subclass-in-base-slice; committed
  faed8a0). Migrated `Toolbar` to a class; Kit places `new Toolbar()` x2; verified
  on iOS + headless VM≡AOT (independent per-instance state).
- [x] **P7 — useEffect / lifecycle.** `useEffect(fn, deps)` in `core/state.chuks`:
  runs after the render commits — on mount, and whenever a value in `deps` changes
  (`[]` = once on mount). `fn` returns an optional cleanup (or null) that runs
  before a re-run and on unmount. Effects are keyed by identity + effect-slot;
  `runEffects()` runs the queued effects after `renderRoot` in every App lifecycle
  method, and `hookSweep()` runs cleanups for unmounted components. Verified VM≡AOT:
  mount effect fires once, deps effect re-fires on change (mount effect does not),
  cleanup fires on unmount. App builds clean.
- [x] **P8 — Lazy off-screen: NOT NEEDED (assessed, closed).** The off-screen work
  this targeted is already avoided: `List` virtualizes scrollable repeated content
  (reconcileList builds only the viewport window r0..r1), `NavStack` renders only
  the current route, and `TabView` renders only the active tab. Skipping *arbitrary*
  off-screen `Comp`s isn't feasible anyway — Yoga does layout on the native side, so
  the reconciler doesn't know a Comp's pixel position at reconcile time (except in a
  List, from rowHeight). Residual per-screen non-list content is bounded and cheap to
  rebuild+diff (same as React/Flutter/SwiftUI). Use `List` for many repeated items.

## Verified so far (VM + AOT identical)

- Two identical `Comp(Counter)` with no keys → independent, persistent state; the
  reconciler emits only the one changed line (`P|app.1|count=1`) on a tap.
- `hookSweep` frees an unmounted component's state; remount starts fresh.
- Real app: `chuks build app/_entry.chuks` clean (no warnings); iOS build launches;
  migrated `Kit` tab renders and holds its switch/chip state through hooks.

## Verification bar

Every phase: `chuks build app/_entry.chuks` clean, and the iOS simulator build
launches and renders. Nothing committed until hand-tested on device (per project
rule). Scratch proofs live outside the repo.

## Risks / notes

- Hook store is module-level in `core/state.chuks` (one App per process on mobile,
  so acceptable; mirrors the existing module-level `NAV`). Revisit if multi-App.
- `Comp` transparency: the produced node is reconciled at the `Comp`'s own id, so
  components add no native view — verify event ids and List item ids still line up.
- Keyed diffing for hand-rolled loops is deferred; use `List` (already keyed) for
  dynamic collections.
