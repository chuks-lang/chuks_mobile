# Layout & Concurrency: Yoga lessons from React Native, and how Chuks goes further

Status: living document. Started 2026-08-30 on branch `fix/walksocials-findings`.

Chuks Mobile lays out with **Yoga**, the same flexbox engine React Native uses. RN has
spent years hardening its Yoga integration in production, and their scars are our free
lessons. This document captures what we learned from their source, issues, and RFCs,
turns it into a prioritized roadmap, and states plainly why Chuks should end up **faster
than RN, not just on par**.

The thesis in one line: **RN's JavaScript is single-threaded, so escaping the main
thread cost them a multi-year C++ shadow-tree rewrite (Fabric). Chuks is genuinely
multi-threaded at the language level, so the thing RN had to bolt on, we get natively,
and can push past.**

---

## 1. Where we are today

- **Reconciler → mutation ops.** App code (Chuks) renders a tree; the reconciler diffs it
  and emits mutation lines (`C` create, `S` style, `I` insert, `R` remove, plus a style
  string). The native hosts, iOS UIKit (`ios/ChuksApp.swift`) and Android
  (`android/MainActivity.kt`), apply those ops to **real** `UIView` / `android.view.View`
  laid out by Yoga.
- **The VM is multi-threaded.** `spawn` launches a real Go goroutine (`pkg/vm/vm.go`),
  there is **no GIL**, and the language ships `lock` / `rlock` (`OP_LOCK`) + `@threadSafe`
  to guard shared state. Parallelism is a first-class language feature, not a runtime
  afterthought.
- **The render path is serialized on the UI thread.** On device the native host drives
  render / dispatch / tick passes one at a time on the UI thread (the CMR instance's driver
  entry points are guarded by `gMu`). Background goroutines marshal state changes back to
  that render thread via `dispatchAsync`, a queue drained on the render thread, and wake it
  with the **host-wake bridge** (`std/host` `host_wake` → `vm.SetHostWakeHook` →
  `cmd/cmr` `chuks_set_wake` C trampoline → the host's UI-thread tick).
- **Yoga layout runs on the UI thread today**, inside those host-driven passes. That is a
  property of the *current construction*, not a limit the runtime forces.

Recently fixed (this branch): border props are now **paint-only** (a side border no longer
calls `setNeedsLayout`, which was spinning an infinite relayout→render loop), and the
host-wake bridge now lets background `spawn`/`time.sleep`/`await` work reach the screen.

---

## 2. The core rules (RN's hard-won invariants)

These are non-negotiable and cheap. Several we already follow; the point is to make them
*explicit and enforced* so a future prop or feature can't quietly break them.

1. **Split every style prop into a `layout` bucket or a `paint` bucket.**
   - *Layout* (width/height/margin/padding/**borderWidth**/flex\*/gap/position/aspect/min/max):
     write to the Yoga node, dirty the tree, schedule a layout pass.
   - *Paint* (backgroundColor/borderColor/borderRadius/opacity/transform/shadow/clip):
     write straight to the view. **Never** touch Yoga, **never** schedule layout, **never**
     call Android `requestLayout()`.
   - Guard with a test that asserts *no paint prop dirties the tree*. Our border freeze was
     exactly this rule being violated.
2. **Border-box sizing.** Borders and padding live *inside* the node's box (Yoga's default),
   so adding a border never resizes the node or its ancestors. Keep this invariant.
3. **Frame-delta gate on layout callbacks.** Fire `onLayout` / viewport callbacks only when
   the computed frame actually changed vs the last committed frame. Coalesce N children
   resizing in one pass into one dispatch (RN batched this in 0.74).
4. **Round to the pixel grid exactly once, at the end of the pass**, via
   `YGConfigSetPointScaleFactor` (2 or 3 per host density). **Never** feed a rounded value
   back into a measure func, that is RN's progressive text-shrink loop (Yoga #824).
5. **Measure funcs are pure**: text/attributes in, size out. No reading a live view, no
   mutating style, no reading back computed layout. (Becomes a *thread-safety* requirement
   once layout moves off the UI thread, see §5.)
6. **Mount atomically on the UI thread only.** Compute the whole batch, then apply all frame
   writes in one sweep. Never interleave apply→layout→apply, and never present a tree where
   some views moved and others have not.

---

## 3. Measurement & text (the expensive part)

Yoga calls a leaf's `YGMeasureFunc` **multiple times per layout pass** (a sizing pass under
`AtMost`, then a final pass under `Exactly`), and nesting multiplies it. Text shaping (glyph
runs, line breaking) is the single most expensive thing in a layout, so uncached nested text
goes O(measure-calls^depth). Two caches are mandatory:

- **Yoga's built-in per-node measure cache** (`YGCachedMeasurement`, keyed on
  `(availW, availH, widthMode, heightMode)`). It exists automatically, but is **thrown away
  when you recreate or dirty the node**, another reason to *mutate* nodes, not rebuild them.
- **Our own text-measurement cache above Yoga**, keyed `(text + font attributes + width +
  widthMode)`: cache `NSTextStorage`/`boundingRect` on UIKit and `StaticLayout` on Android.
  Dirty a text leaf with `YGNodeMarkDirty` **only** when its string/attributes change. This
  is RN's `TextLayoutManager` cached-spannable path.

Pitfalls: a measure func that returns `NaN`/negative/non-deterministic sizes breaks the cache
and can loop; rounding inside a measure func compounds (rule 4); `alignItems: "baseline"`
needs a real `YGBaselineFunc` or it should be rejected, not silently mis-aligned.

---

## 4. Common Yoga integration bugs RN hit, and the fixes (so we skip them)

- **Pixel-rounding shrink loop** (Yoga #824): height = `Round(bottom) - Round(top)` with a
  negative top and scale factor 2 loses 0.5px per pass, and the rounded height was fed back
  as next-pass input. → Round once, correct `pointScaleFactor`, never re-feed.
- **Non-layout children corrupt the index map** (RN 024a8dc): raw text / portals / overlays
  broke Yoga's index-based child mapping. → Keep a strict **1:1 view-child ↔ Yoga-child**
  index map and filter non-layout children out of the Yoga child list.
- **Per-node config, not the global default** (RN 0e5d54a): give every node an explicit
  `YGConfigRef` so scale factor / errata are deterministic per tree.
- **Percentage sizing vs parent resize** (Yoga #1626): percent margin/padding resolve against
  the *parent's* size, so a parent-size change must dirty children even if the child's own
  style is unchanged. Don't short-circuit dirtiness on the style string alone.
- **Legacy flexbox divergence**: Yoga <3 differed from web flexbox (`UseLegacyStretchBehaviour`).
  → Pin **modern Yoga (≥3.x)**, default `YGConfigSetErrata(None)` (W3C-conformant), confirm
  `gap`/`rowGap`/`columnGap` and `position: static` support. This skips RN's decade of
  "why is stretch different" bugs outright.
- **flexBasis/min-max/flex-grow edge cases**: test the matrix explicitly (§7).

---

## 5. Threading: the differentiator

**What RN separates.** Layout math is thread-agnostic and runs **off** the UI thread (old
arch: a serial shadow queue; Fabric: `ShadowTree::commit`, which runs `YGNodeCalculateLayout`,
is designed to be called from any thread). **View mutation is UI-thread-only**, the mount
phase always applies `ShadowViewMutation`s on the main thread, because `UIView`/Android `View`
are not thread-safe (`CalledFromWrongThreadException` on Android, UB on iOS).

RN needed all of this because **their JS engine is single-threaded**. The three mechanisms
that make their off-thread layout safe:

1. **Immutable / sealed shadow nodes + implicit double-buffer.** An update clones only the
   changed nodes plus the path to root and shares the rest; the "next tree" is computed on a
   background thread while the mounted "previous tree" stays valid. No reader/writer share a
   mutable node.
2. **Atomic, revision-tagged commit with stale-rejection.** `ShadowTree::commit` promotes
   next→mounted atomically; a commit computed against a superseded revision is retried or
   dropped. RN's unbounded retry has a real starvation bug (issue #51870, "attempts < 1024"),
   so the retry must be **coalesced/bounded**, one layout per frame, cancel-in-flight on a
   newer revision.
3. **Handoff via a coordinator queue drained on the UI thread** (`MountingCoordinator`).
   **Our `dispatchAsync` queue drained on the render thread is already this mechanism.**

**Why Chuks can go further.** We do not have RN's single-thread constraint. `spawn` +
`lock`/`@threadSafe` + the `dispatchAsync` UI-thread drain queue are exactly the primitives
RN had to reconstruct in C++. So:

- **App logic and layout can run concurrently** on separate goroutines without a JS bridge
  and without serializing across a thread boundary, RN pays bridge/serialization cost we
  simply don't have.
- **Layout can leave the UI thread** onto a dedicated layout goroutine, so a big text-heavy
  relayout stops being a dropped frame.
- **AOT** compiles the same Chuks source to native, so there is no interpreter overhead on the
  hot path when shipping.
- **Longer horizon**: independent trees/windows (or independent, snapshotted subtrees) can be
  laid out on *different* goroutines in parallel, something RN's single shadow-tree commit
  does not do. This is the real "faster than RN" ceiling.

**The one hard constraint we must respect** (same as RN): a Yoga tree is a mutable C
structure with an internal measure cache, so **one writer per tree**. Never lay out one tree
from two goroutines. Use a single dedicated layout goroutine (guarded by `lock`), ship
**immutable, revision-tagged frame snapshots** over `dispatchAsync`, and keep **all** view
mutation on the render thread. Concurrency across *independent* trees is the parallel win;
concurrent mutation of *one* tree is corruption.

---

## 6. Roadmap

### Phase 1, remove UI-thread layout cost (low risk, do first)

Each item: what · why · RN precedent · status.

1. **Incremental *apply*.** After each batched `YGNodeCalculateLayout`, write a view's frame
   only when its layout actually changed, then move on. · Yoga already dirty-skips *compute*;
   this stops us re-touching every view on every pass. · Yoga "Incremental layout". ·
   **✅ DONE (iOS + Android), verified on both.** iOS skips via Yoga's
   `YGNodeGetHasNewLayout`/`YGNodeSetHasNewLayout` (`ChuksApp.swift` `relayout()`); Android
   skips via a frame-value comparison against the last applied `[l,t,w,h]`
   (`MainActivity.kt` `relayout()`), since Android's JNI does not expose HasNewLayout. Both
   use a `needsFrame` guard so a freshly-created view always applies once.
2. **Reuse Yoga nodes across frames.** A `Style` op mutates the existing node; only genuine
   tree changes create/remove nodes. · Preserves Yoga's measure cache + enables dirty-skip. ·
   Fabric copy-on-write. · **✅ DONE (already so by design), verified.** The host's `style()`
   mutates `ynodes[id]` in place (never recreates); `YGNodeNew`/`FreeRecursive` happen only in
   create/remove; the reconciler emits a style op only when the serialized style actually
   changed (`if (ps != ns)`), and Yoga's setters no-op an unchanged value, so the measure
   cache survives.
3. **Text-measurement cache above Yoga** on both hosts, keyed `(text+font+width+widthMode)`. ·
   Avoids O(nⁿ) text shaping. · RN `TextLayoutManager`. · **⏸ DEFERRED, pending a benchmark.**
   Yoga's built-in per-node measure cache (`YGCachedMeasurement`, keyed on the constraint)
   already absorbs the hot path, and with incremental compute (#1/#2) unchanged text nodes are
   not re-measured. An app-level cache is a marginal win over that, adds real risk (a stale
   measurement clips or overflows text), and costs key-hashing on every short-text measure.
   Do it only once a text-heavy benchmark shows measurement is the bottleneck, then key it
   content-addressed (attributed-string hash + numberOfLines + lineBreak + width bucket +
   mode) so a text/font change is a natural miss.
4. **Formalize the layout-vs-paint prop table**, route ops by bucket, forbid paint ops from
   scheduling layout. · Generalizes the border fix. · RN prop bucketing. · **◑ In progress.**
   The border case is fixed (paint-only). A headless reconciler determinism/idempotency test
   is in (`tests/reconcile_test.chuks`, run via `tests/run.sh`): it asserts an unchanged tree
   re-reconciles to **0 mutations across 5 passes** (convergence, the property whose absence
   was our freeze), that a single change emits exactly one targeted mutation, and that style
   serialization is deterministic. Remaining: an explicit paint-vs-layout prop table + a
   host-level "a paint prop dirties no Yoga node / relayout converges" assertion (native, so
   currently covered by the device screenshot pass rather than a unit test).
5. **Batch each frame into one commit**: accumulate ops → one layout → one round → one atomic
   mount → coalesced `onLayout`. · Kills intra-frame thrash + callback storms. · Fabric
   "compute mount once after final commit" + RN 0.74 batched onLayout. · **✅ DONE, verified.**
   The host applies the whole mutation stream (`apply(s)`) and then calls `relayout()` once
   (one `YGNodeCalculateLayout`), never per op. (A viewport-sync pass may add one bounded
   extra relayout, gated by `pushViewport()`.)
6. **Round once via `pointScaleFactor`; never re-feed rounded values.** · Yoga #824. ·
   **✅ DONE, verified.** iOS sets `YGConfigSetPointScaleFactor(config, UIScreen.main.scale)`
   (rounds points to device pixels); Android works in whole pixels, where Yoga's default
   scale factor of 1 is the correct grid. No code re-feeds a rounded dimension into a measure.
7. **Pin modern Yoga, explicit per-node `YGConfig`.** · Skips legacy flexbox bugs. · Yoga 2.0
   Errata API. · **⚠ Intentional deviation.** Both hosts set `YGErrataAll` on purpose, it is
   load-bearing for Text auto-wrap (a measured Text re-wraps to its container width via the
   errata + measure-func pairing). So we have knowingly opted INTO classic Yoga behavior here.
   Revisit `Errata=None` only with a text-wrapping regression test in hand (see §7 test
   matrix); do not flip it blind.
8. **Enforce the 1:1 view-child ↔ Yoga-child index map**; filter non-layout children. · RN
   024a8dc. · **✅ DONE (already so by design), verified.** Non-layout decorations (the
   ImageBackground bg image, glass overlays, modal sheet chrome) are inserted into the *view*
   tree with an index offset (`base`) but are **not** given Yoga nodes, so `YGNodeInsertChild`
   indices stay 1:1 with the logical layout children.

### Phase 2, off-thread layout (high ceiling, medium risk — after Phase 1 is measured)

9. **Move Yoga layout onto a single dedicated background goroutine; keep all view mutation on
   the render thread.** Compute a batch off-thread, ship an immutable revision-tagged frame
   snapshot over `dispatchAsync`, the render thread applies it atomically (using #1). ·
   Layout is our only unbounded UI-thread cost, and we already have the primitives. · RN
   shadow queue / Fabric `ShadowTree::commit`. · Invariants: single-writer per Yoga tree;
   never touch a view off the render thread; measure funcs pure + thread-safe shaper; commit
   whole per-revision snapshots (no shared mutable frame map).
10. **Stale-commit rejection + coalescing.** Tag each layout job with its source tree
    revision; drop superseded results at the commit gate; bound retries; coalesce to one
    layout per frame, cancel in-flight on a newer revision. · Makes #9 safe under rapid
    updates. · RN commit retry + starvation bug #51870.

### Phase 2 concrete design (for our CMR host)

Today, one host tick does all of this on the UI thread: `runAsyncQueue` → `renderRoot`
(reconcile → op stream) → apply ops (create `UIView`/`View` + mutate the Yoga tree) →
`relayout()` (`YGNodeCalculateLayout` + measure) → write frames. The unbounded cost is
`YGNodeCalculateLayout` (which drives measurement); everything else is cheap. The plan moves
just that compute off the UI thread.

**Prerequisite (must land first): thread-safe measurement.** Our measure funcs are not
thread-safe today, iOS `measureText` calls `label.sizeThatFits(...)` (a live `UILabel`) and
Android measures a live `TextView`. Both are UI-thread-only, so `YGNodeCalculateLayout` can't
leave the UI thread until measurement is pure. Refactor measure to shape from an **immutable
text snapshot** (`NSAttributedString` + Core Text `CTFramesetterSuggestFrameSizeWithConstraints`
on iOS; a `StaticLayout`/`Layout.Builder` off an immutable `SpannableString` on Android),
never a mounted view. This also unlocks the content-addressed measure cache (#3) as a
by-product, so #3 stops being "deferred" and becomes free once this lands.

**The off-thread pipeline (single-writer, serialized, no shared mutable state):**

1. **UI thread** (as today): run the reconcile, then apply the op batch, create `UIView`/
   `View` (must be UI-thread) and mutate the Yoga tree (`YGNodeStyleSet*`/`InsertChild`). The
   Yoga tree has exactly one writer and it is here.
2. **Hand off** the just-mutated, now-frozen-for-this-frame Yoga root + viewport to the
   **single dedicated layout goroutine** (via `spawn` + the existing `dispatchAsync`/wake
   plumbing, `lock`-guarded). The UI thread does NOT mutate the tree again until the goroutine
   returns, that serialization is what keeps the single-writer invariant without a per-node
   lock.
3. **Layout goroutine**: run `YGNodeCalculateLayout` (calls the now-thread-safe measure funcs).
   Read every node's computed frame into an **immutable, revision-tagged frame snapshot**
   (`[id → l,t,w,h]` + the tree revision it was computed against). No view is touched here.
4. **Commit on the UI thread**: post the snapshot back over `dispatchAsync`; the render thread
   applies it atomically using the incremental `HasNewLayout`/frame-delta path from #1. If the
   tree revision advanced while layout was in flight, **drop the stale snapshot** and the next
   tick recomputes (bounded, coalesced, see #10), never mount frames for a dead revision.

**What this buys:** the UI thread keeps handling input, scrolling, and animation while a big
text-heavy `YGNodeCalculateLayout` runs on another core, layout stops being a dropped frame.
Because our VM is genuinely parallel, this needs no C++ shadow-tree, just one goroutine + the
`lock`/`dispatchAsync`/wake primitives we already have and already ship.

**Risks / invariants (do not violate):** one writer per Yoga tree (all mutation + the layout
pass serialized, never two goroutines on one tree); never touch a `UIView`/`View` off the UI
thread; measure funcs pure + thread-safe (the prerequisite); commit whole per-revision
snapshots atomically (no shared mutable frame map, or you get torn reads); coalesce and drop
stale layouts under rapid updates.

**Rollout:** (a) refactor measurement to be pure/thread-safe + add the cache, verify identical
rendering on both platforms; (b) introduce the layout goroutine behind a flag, keep the
synchronous path as fallback; (c) measure the win on a text-heavy screen; (d) make it default
once stable. Each step tested on iOS + Android.

### Phase 3, the parallel ceiling (research)

- Lay out **independent** trees/windows on different goroutines in parallel.
- Explore snapshotting independent subtrees for parallel layout + merge.
- Both are only possible because the VM is genuinely parallel; neither is something RN's
  single shadow-tree commit does.

---

## 7. Test matrix (add to the framework suite)

The historically flaky Yoga combinations + the assertions that catch our class of bugs:

- `flexGrow:1` + `maxWidth`/`maxHeight`
- `flexBasis:"auto"` + `minHeight`
- percentage padding/margin under a **resizing** parent
- nested text measured under `AtMost` then `Exactly`
- `position:absolute` with percentage insets
- a border **never** resizes its ancestors (border-box invariant)
- **idempotency**: laying out the same input tree 5× produces an identical result — this
  single assertion would have caught the side-border freeze, and will catch any future
  rounding/feedback loop. (Reconciler-level idempotency is now covered by
  `tests/reconcile_test.chuks`; the native Yoga-layout idempotency is still device-tested.)
- **paint-purity**: applying a paint prop dirties **no** Yoga node. (Native; TODO as a
  host-level assertion.)

---

## References

Yoga: Incremental layout, Configuring Yoga, External layout systems, Announcing Yoga 2.0;
issues #824 (rounding shrink), #334 (multi-measure), #1626 (percent vs parent), #999, #749.
React Native: Fabric render pipeline, Fabric renderer, RN 0.74 release notes; commits 024a8dc
(non-layoutable children), 0e5d54a (per-node config), 002396b/#44409 (cached spannable),
0a2dec1 (legacy absolute removal); PR #48303 (mount instructions once); issue #51870 (commit
starvation); `ReactCommon/react/renderer/mounting/ShadowTree.cpp`.
