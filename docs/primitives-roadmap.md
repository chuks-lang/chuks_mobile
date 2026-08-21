# Primitive expansion roadmap

Status: not started. Goal: grow Chuks Mobile's UI primitive vocabulary toward
React Native / SwiftUI parity for general apps, across all three engines
(uikit, swiftui, android), while the app stays 100% Chuks.

Gap analysis (rendered): `docs/previews/primitive-coverage.html`.

## The test surface: the Components tab

The **Components** tab (`app/tabs/kit.chuks`) is a live gallery of every primitive +
kit component, organized by section and scrollable, verified on all three engines
(SwiftUI + UIKit + Android). **When you add a primitive, add a row here** so it stays
testable everywhere. It exercises: Buttons (all variants), native Switch (on/off),
Text input (state-bound), Badges (all tones), Chips (selectable), Avatars, ListItems,
Card, Typography, Row-justify layout, and useState counters. Backed by a new public
`Scroll({}, kids)` primitive (kind "Scroll", grows to fill, children stretch full
width) — the first bounded-scroll container (distinct from the virtualized `List`).

Known minor cross-host nuance: a long `Text` wraps on SwiftUI but truncates to one
line on UIKit/Android — worth reconciling when polishing Text.

## Host primitives today (8)

`View` · `Text` · `Image` · `Button` · `Input` · `Scroll` · `List` · `Video`
(plus `NavStack` navigation, and composed kit components: Card, Badge, Chip,
Avatar, Switch, Divider, ListItem, NavBar, TabBar).

## Component API convention (props dataType)

Components take a single **props `dataType`**, so callers set only what they need
by name (no positional nulls). Two hard rules learned building this:

- **Required content is a PLAIN field; optional config MUST be nullable (`T?`).**
  A plain (non-optional) field cannot be omitted from a partial literal (the type
  checker errors "Missing required property"). So anything a caller may skip is
  `Node?` / `function?(): void` / `string?` / `bool?`, coerced in the body with
  `?? default`. Use the PROPER nullable type, never `any?`.
- **`class` is a reserved keyword** — it parses as a dataType field name but fails
  as a map-literal KEY (`unexpected token 'CLASS'`). The tw-append field is named
  **`extra`**, not `class`/`className`.

Verified VM = AOT.

```chuks
export dataType NavBarProps {
    title: string,
    leading: Node?,               // icon node, not `any`
    onLeading: function?(): void, // callback, not `any`
    trailing: Node?,
    onTrailing: function?(): void,
}
export function NavBar(p: NavBarProps): Node { ... p.title, p.onTrailing ... }

// call sites — set only what you need:
NavBar({ title: "Home" })
NavBar({ title: "Profile", trailing: icon, onTrailing: toggle })
```

This is the RN/SwiftUI props model. **DONE:** the whole kit is migrated —
`Button` (ButtonProps), `Card` (CardProps, `children: []Node`), `Input`,
`Badge`, `Chip`, `Avatar`, `ListItem`, `Switch` all take a single props dataType;
`Divider()` / `SectionHeader(text)` stay argument-free (no placeholders to remove).
Every new primitive below adopts the convention from the start.

> Gotcha: two exported functions with the SAME name collide in AOT resolution. The
> kit `Button` and the low-level `ui.Button` both existed; once the kit `Button`
> switched to a 1-arg `ButtonProps`, the primitive's call site mis-resolved. Fixed
> by having the app use the kit `Button` everywhere (only the demo toolbar used the
> primitive). Keep public kit names unique against the `ui.chuks` primitives.

## The three kinds of gap (how each is filled)

- **① Native view** — needs a real host view (native feel / accessibility). Add a
  node `kind` + its style/prop keys, then map it in EACH engine. Three impls.
- **② Composable** — buildable now from existing Chuks primitives as a kit
  component. One impl in Chuks, no host change.
- **③ Host API** — renders nothing itself; a host hook / global. Per host, small.

## Contract for a new native primitive (①)

1. `core/ui.chuks`: a builder (e.g. `Switch(...)`) that emits a `Node` with the new
   `kind` and any new style/prop keys (serialized into the `S|id|...` stream).
2. `ios/ChuksApp.swift` (UIKit): map the kind to a UIView + Yoga.
3. `ios/ChuksAppSwiftUI.swift` (SwiftUI): map the kind in `NodeView.render`.
4. `android/.../MainActivity.kt`: map the kind to an Android view.
5. Events (value changes) flow back via `dispatch` / `dispatchInput` like Input.
6. A test route/screen; verify on both iOS engines + Android.

---

## Tier 1 — core UX (build first)

> **Tier 1 COMPLETE** — all 8 primitives shipped + verified live on UIKit, SwiftUI, and Android.

- [x] **Pressable** (①) — press-state feedback. uikit [x] · swiftui [x] · android [x]
      **DONE.** Chose a HYBRID over the pure-composed plan: the composed "pressed
      flag" needs press-in/out events (a host change anyway) AND round-trips each
      dim through Chuks (a frame of lag). Instead the Chuks side is a trivial
      composed wrapper and each host applies the dim NATIVELY on touch-down (instant,
      like TouchableOpacity). Verified dim-while-held + fire-on-release on SwiftUI +
      Android (screenshotted mid-hold); UIKit built + renders (same pattern).
      - `ui.chuks`: `Pressable(props, kids)` → a View with `press=<activeOpacity 0-100>`
        (Style.press, default 60) + onPress. No new events, no state.
      - SwiftUI: `PressAction` modifier — `DragGesture(minimumDistance:0)` dims on
        onChanged (touch-down), fires on onEnded. UIKit: `UILongPressGestureRecognizer`
        (`minimumPressDuration=0`) — .began dims, .ended restores + fires if inside.
        Android: `setOnTouchListener` — ACTION_DOWN dims, ACTION_UP restores + fires
        if inside, ACTION_CANCEL restores.
      - Future: add `scale` press feedback + a native ripple option on Android.
- [x] **Switch / Toggle** (①) — native toggle. uikit [x] · swiftui [x] · android [x]
      (replaces the composed kit Switch). **DONE + verified on all three hosts**
      (SwiftUI + UIKit + Android emulator, toggles both directions, primary track /
      white thumb). This is the TEMPLATE native primitive —
      the pattern below is what every ① follows:
      - `ui.chuks`: `Toggle({on, onPress, bg})` emits kind `"Switch"`; Style gained a
        `swOn` field serialized as `on=1/0`; `bg` is the on-tint. Named `Toggle` (not
        `Switch`) so it never collides with the kit `Switch`, which now wraps it.
      - Value flows BACK as a discrete dispatch (like a tap), NOT a value string: the
        host fires the node's action on change; Chuks' onPress flips the parent state;
        the re-render syncs the control (`on=…`). Controlled, never drifts.
      - UIKit: `UISwitch` + `.valueChanged`→`handleSwitch`→dispatch; `bg`→`onTintColor`;
        Yoga sized from `intrinsicContentSize`. SwiftUI: native `Toggle` with a
        dispatch-only binding, `.tint(bg)`, `bg` stripped before BoxStyle so no box is
        painted behind it. Android: `Switch` + `setOnClickListener` (NOT
        OnCheckedChange — programmatic setChecked would loop); `bg`→`trackTintList` +
        white `thumbTintList`; `background=null`/`isFocusable=false` (no ripple box).
      - Android host bug fixed along the way: per-id visual state (bgColor / radius /
        borderW / borderC) wasn't cleared when a node id is REUSED across a screen
        swap, leaving a stale border/bg under the new element (a stray outline around
        the switch row). `style()` now clears it first and drops backgrounds when the
        new style has none. (Same "reset on node reuse" class as the reconciler T| fix.)
- [x] **Spinner / ActivityIndicator** (①). uikit [x] · swiftui [x] · android [x]
      **DONE.** Display-only (no event flow-back) — the simplest native primitive.
      `ui.chuks`: `Spinner({ size, color })` → kind "Spinner"; reuses `w`/`h` for the
      diameter (default 24) and `fg` for the tint (no new Style field; `size` is set
      AFTER applyLayout so it means diameter, not font size). UIKit:
      `UIActivityIndicatorView` (.medium, transform-scaled to `w`), `fg`→`.color`.
      SwiftUI: `ProgressView().tint(fg).scaleEffect(w/20)`. Android: `ProgressBar`
      (indeterminate, Yoga-sized), `fg`→`indeterminateTintList`. Verified rendering +
      animating on SwiftUI + Android (sizes/colors correct); UIKit built (stock control).
- [x] **Password field** (①) — `secure` flag on the `Input` node.
      uikit [x] · swiftui [x] · android [x] **DONE.** Extends the existing Input (no
      new kind): `Input({ secure: true })` → `sec=1` in the style (Style.sec).
      UIKit: `isSecureTextEntry`. SwiftUI: `SecureField` vs `TextField` (Group +
      shared modifiers). Android: `inputType` PASSWORD + `PasswordTransformationMethod`.
      Verified masking end-to-end on Android (typed "secret123" → dots); iOS built
      clean with the platform's stock secure-entry fields.
- [x] **SafeAreaView** (②/③) — safe-area insets. uikit [x] · swiftui [x] · android [x]
      **DONE.** Since the app root is ALREADY safe-inset, this exposes the device
      insets to Chuks (rather than a redundant wrapper): each host reports them and
      `safeTop()`/`safeBottom()`/`safeLeft()`/`safeRight()` read them; `SafeAreaView(
      {edges}, kids)` composes spacers from them (for edge-to-edge content / modals).
      - Entry export `setInsets(t,r,b,l)` → `setSafeInsets` (C-ABI chuks_setInsets).
      - UIKit: `view.safeAreaInsets` in `viewDidLayoutSubviews` (points).
      - SwiftUI: a top-level `GeometryReader` that RESPECTS the safe area — its
        `geo.safeAreaInsets` ARE the device insets (do NOT `.ignoresSafeArea()` it, or
        they collapse to 0). Reported on appear + onChange.
      - Android: `rootWindowInsets.systemWindowInset*` converted px→dp (Chuks lengths
        are dp on Android), on layout + an apply-insets listener.
      Verified live values: SwiftUI top 62 / bottom 34, Android 24 / 24.
- [x] **StatusBar** (③) — style (light/dark) + hidden. uikit [x] · swiftui [x] · android [x]
      **DONE.** `StatusBar({ hidden?, style? })` — a host-global directive that renders
      NOTHING (kind "StatusBar", 0x0; emits `sbh`, `sbstyle`). Put it anywhere in a
      screen. `style` "" (default) = follow theme; "light"/"dark" forces the content.
      - UIKit: `prefersStatusBarHidden` + `preferredStatusBarStyle` (override ?? theme-
        derived), `setNeedsStatusBarAppearanceUpdate()`.
      - SwiftUI: `.statusBarHidden(sbHidden)`; style folded into `preferredColorScheme`
        ("light" content → .dark scheme). Config read in `apply()` (not a view body).
      - Android: window `SYSTEM_UI_FLAG_FULLSCREEN` (hidden) + `LIGHT_STATUS_BAR` (dark
        icons). A removed StatusBar reverts to defaults (SwiftUI).
      Verified hide end-to-end on Android (switch → status bar hides, content fills up);
      iOS built with the stock status-bar APIs on the same flag.
- [x] **Modal / BottomSheet** (①) — a full-screen overlay layer in the host, driven by a
      Chuks-side `visible` flag. uikit [x] · swiftui [x] · android [x] **DONE, verified live on all 3.**
      `Modal({ visible, position, onDismiss }, kids)`: `position:"center"` = centered dialog,
      `position:"bottom"` = a draggable bottom sheet (@gorhom-style). The host owns the sheet
      chrome + gesture (the drag can't round-trip the mutation stream): host-drawn rounded-top
      surface + grab handle over a dimmed scrim, a **slide-up entrance** (verified mid-animation
      on all 3), and **drag-down / tap-outside to dismiss** (drag verified on UIKit pan + Android
      swipe; SwiftUI uses the same logic, real-finger only — synthetic sim drags don't fire it).
      - `ui.chuks`: `Modal`/`mkModal`, Style `mvis` (0/1) + `mpos` ("center"|"bottom"); `onDismiss`
        rides the node's `onPress` so the scrim tap reuses the `T|` bind path.
      - Each host: Modal is a separate full-screen Yoga root mounted on the window root (skipped in
        the app frame loop, placed explicitly on top). Sheet: `layoutSheetChrome(animateIn:)` slips a
        surface + handle behind the content and wires a pan; `shownSheet` tracks the open transition
        so the entrance runs once. Surface color = secondarySystemBackground / themed (auto dark).
- [x] **Dark-mode sync** (③) — read OS color scheme, drive the theme.
      uikit [x] · swiftui [x] · android [x] **DONE, verified live on all 3** (flipped
      the OS appearance → app followed dark↔light). The app follows the OS by default;
      a manual toggle (setTheme/toggleTheme) OVERRIDES and sticks (verified VM≡AOT).
      - `theme.chuks`: `syncSystemTheme(dark)` (no-op once `userPicked`), `followingSystem()`.
        Entry exports `setColorScheme(dark)` + `colorSchemeFollows()` (C-ABI: chuks_*).
      - UIKit: read `traitCollection.userInterfaceStyle` at launch (before mount) +
        `traitCollectionDidChange` live. Clean — UIKit never overrides the interface
        style, so the trait always reflects the OS.
      - Android: `resources.configuration.uiMode` at onCreate + `onConfigurationChanged`
        (manifest `configChanges="uiMode"` so we aren't recreated). JNI wrappers added.
      - SwiftUI: `@Environment(\.colorScheme)` at launch + onChange. Key subtlety:
        `preferredColorScheme` is `nil` WHILE following (so the env keeps reporting the
        real OS and live changes stay detectable), and only forced once overridden (so
        the status bar matches the manual choice) — gated on the `followSystem` flag.

## Tier 2 — common (next)

- [x] **Slider** (①) — native value slider. uikit [x] · swiftui [x] · android [x] **DONE.**
      Controlled like Switch: parent owns `value`, `onChange(v: string)` fires with the new
      value as the user drags (parse with `int(v)`). Integer range, `min` >= 0.
      `Slider({ value, min, max, color, onChange })`.
      - `ui.chuks`: `Slider`/`mkSlider`, Style `slv`/`slmin`/`slmax` (range travels together);
        `fg` = active-track/thumb tint. Reuses the Input value-dispatch path (fireValue).
      - Hosts: UIKit `UISlider` (isContinuous), SwiftUI `Slider` (dedicated `ChuksSlider` with
        seeded `@State` so the thumb drags smoothly), Android `SeekBar` (0-based, offset by min).
        Each guards the controlled write during an active drag (isTracking / isPressed) so the
        re-render never fights the user's thumb. **Value binding verified live on Android**
        (drag 35→85, label + track synced); renders correct on all 3; iOS drag is real-finger
        only (synthetic events don't drive UIControl/native-slider tracking, same as Switch).
- [x] **Picker / Select** (①) — native dropdown. uikit [x] · swiftui [x] · android [x] **DONE.**
      Controlled like Switch: parent owns the selected index (`value`) + passes `options`;
      `onChange(v: string)` fires with the picked index (parse with `int(v)`). The field shows
      the chosen label. `Select({ value, onChange, bg, color }, ["Low","Medium",...])`.
      - `ui.chuks`: `Select`/`mkSelect`, Style `seli` (index); options ride the node's **text**
        channel tab-joined (survives the stream's `|`/newline split — labels must not contain
        tab/`|`/newline). Options are a 2nd arg since the `Prop` union has no list type.
      - Hosts: UIKit `UIButton` + `UIMenu` (showsMenuAsPrimaryAction), SwiftUI `Menu`, Android
        `PopupMenu` off a Button. Each parses the tab-joined options, shows `options[seli]`, and
        dispatches the picked index. **Verified on Android** (popup → pick "Urgent" → value 3,
        label synced) **and SwiftUI** (menu changed the selection + binding). UIKit renders/sizes
        correctly; its UIMenu opens on a real tap only (synthetic events don't drive UIControl).
- [x] **Multiline TextEditor** (①) — `multiline` on `Input`. uikit [x] · swiftui [x] · android [x] **DONE.**
      `Input({ multiline: true, placeholder, onChange })` (or `TextInput`). Switches the native
      widget (`multiline` flips kind `Input` → `TextArea`); reports each edit like Input.
      - `ui.chuks`: `mkTextArea` (kind "TextArea", taller default h); TextInput branches on `multiline`.
      - Hosts: UIKit `UITextView` + a placeholder `UILabel` overlay (UITextView has no placeholder);
        SwiftUI a custom `MultilineTextView` UIViewRepresentable with a CLEAR bg (TextEditor's bg is
        opaque + unclearable on iOS 15, which painted a white box over the field) + placeholder overlay;
        Android multiline `EditText` (TYPE_TEXT_FLAG_MULTI_LINE, gravity top). **Verified on Android**
        (typed two lines via ENTER=newline, state echoed); renders + placeholder correct on both iOS engines.
      - Padding: multiline drops the kit's `p-md` (the native text inset would double up); single-line keeps it.
- [x] **List — high-performance (FlashList-class)** uikit [x] · swiftui [x] · android [x] **DONE.**
      The List is the workhorse for feeds (Instagram/TikTok/Cleset), so it's windowed AND recycled.
      - **Windowing** (already): only the on-screen window (± `buffer`) of the row range is mounted.
      - **View recycling** (`reconcileList`): a fixed POOL of cell ids ("<content>.cell<i>"). On scroll a
        leaving cell is REBOUND to the entering row — `reconcile()` diffs its old node vs the new and
        emits only changed text/position (S|/P|), reusing the native view. During a fling: ~zero
        Create/Remove, so no view + Yoga-node alloc churn. Verified: view count stayed STABLE (~196)
        through a deep 90-post fling on a 1000-row feed. (Trade, like FlashList: cells are recycled, so
        rows must be data-bound, not hold per-row state.)
      - **Scroll fix (Android)**: a `ScrollView` measures its child `UNSPECIFIED`, so the content
        FrameLayout sized to its windowed cells and the scroll range collapsed to ~6 rows — the List
        wouldn't scroll. Fixed with `minimumHeight` on styled-height views. iOS was fine (UIKit
        `contentSize`, SwiftUI fixed-height content frame). See the `timeline` tab (1000-post feed).
- [x] **SectionList** — virtualized sectioned list (`reconcileSectionList`): sections each contribute a
      header + rows, windowed the same way. Builder + reconciler done; benefits from the Android scroll
      fix. (Not yet wired to a demo screen.)
- [x] **Pull-to-refresh** (①) — on Scroll. uikit [x] · swiftui [x] · android [x] **DONE.**
      `Scroll({ onRefresh, refreshing }, kids)` — pulling down at the top fires `onRefresh`; the
      spinner ends after the (synchronous) refresh work (mobile can't async-clear a controlled flag
      from a background thread, so the host auto-ends after the dispatch; `refreshing`→`rfsh` can also
      drive it). Style `rfsh` (-1 none / 0 idle / 1 refreshing); onRefresh rides the node's `onPress`.
      - Hosts: UIKit `UIRefreshControl` (auto-ends after fire); SwiftUI `.refreshable` (dispatch + a
        brief sleep so the native spinner reads); Android is MANUAL (platform SDK has no
        SwipeRefreshLayout, build has no androidx) — a touch handler translates the ScrollView with the
        finger (rubber-band) + a spinner overlay, springs back on release, defers the re-render via
        `sc.post` so the touch stays smooth.
      **Verified on all 3** ("Pulled to refresh 1×" after a pull-down).
- [x] **Progress bar** (①) — native determinate bar. uikit [x] · swiftui [x] · android [x] **DONE.**
      `Progress({ value, max, color, h })` — display-only (for an indeterminate spinner use `Spinner`).
      Builder computes the fill percent into Style `prog` (0-100); `fg` is the tint.
      - Hosts: UIKit `UIProgressView` (scaleY-thickened + rounded), SwiftUI linear `ProgressView`,
        Android horizontal `ProgressBar` (fg → progressTintList vs the Spinner's indeterminateTintList).
      **Verified on all 3** (35% tied to the Slider state, 100%, 40% in primary/success/warning).
- [x] **Alert / Dialog** (③) — native OS alert. uikit [x] · swiftui [x] · android [x] **DONE.**
      Declarative like Modal: `Alert({ visible, title, message, confirmText, cancelText, onConfirm, onCancel })`
      presents the OS alert when `visible` flips true. Renders nothing inline.
      - `ui.chuks`: `Alert`/`mkAlert`, Style `avis` (0/1); title/message/labels ride the text channel
        tab-joined. The two buttons route through ONE dispatch — host reports "1" (confirm) / "0"
        (cancel), and the builder's `onChange` (via the `callOpt` helper) calls the right callback.
      - Hosts: UIKit `UIAlertController` (deferred present so the batch applies first), SwiftUI native
        `.alert` (isPresented reads `avis`), Android `AlertDialog`. **Verified on Android + SwiftUI**
        (tap Delete → native dialog → "You chose: Delete"); UIKit wired identically.
      - **Fixed a real compiler bug to keep the name `Alert`:** it collided with the app's
        `dataType Alert`. The AOT import-provenance collapse pass reasoned about functions only, so a
        genuine TYPE import got repointed to a same-named function's package ("undefined type"). Made
        the pass type-aware in `pkg/compiler/ir_splitter.go`; added a full cross-kind collision matrix
        unit test + multi-module fuzz coverage. See [[chuks-aot-name-collision-type-vs-function]].
- [x] **Remote / background image** — `Image` URL loading + `ImageBackground`. uikit [x] · swiftui [x] · android [x] **DONE.**
      `Image({ src, w, h, radius, resize })` async-loads + caches a network image; `resize` = cover
      (default) / contain / center. `ImageBackground({ src, ... }, kids)` paints a remote image behind
      content. (For a local SF Symbol use `Icon`/`name`.) URL rides the text channel (contains `=`/`&`,
      which break the style `k=v;` format); `rmode` style carries resize.
      - Hosts: UIKit `UIImageView` + `URLSession` (static session, cache, tag-guarded); SwiftUI
        `AsyncImage`; Android `ImageView` + a background-thread fetch (`INTERNET` permission added).
        ImageBackground = a box with a bg image view; children insert ABOVE it (insert-index offset so
        the bg stays at the back). Android also clips the ImageView to its rounded outline (a rounded
        `bg` drawable doesn't clip the foreground bitmap → radius 36 on 72×72 = a circle).
      - **Verified on Android** (dog + circular pug + ImageBackground with white overlay) **and SwiftUI**
        (real images loaded). UIKit: layout + URLSession path verified; a mid-session simulator network
        outage blocked the final image-load screenshot (loads on a real device / fresh sim).

## Tier 3 — advanced (later)

- [x] **Animation system** (③ host-driven) — uikit [x] · swiftui [x] · android [x] **DONE.**
      **Design:** transition-based implicit animation, driven NATIVELY (no per-frame round-trip,
      the thing that makes a JS-driver janky). A view gets an `anim` duration (ms); when its
      transform/opacity change, the host tweens the transition. Chuks just flips state + re-renders
      at event rate; Core Animation / SwiftUI `.animation` / Android `ViewPropertyAnimator` interpolate
      the frames on the GPU. Animatable props are the cheap ones (no layout): `opacity`, `tx`/`ty`
      (translate), `scale` (percent, 100 = 1×), `rotate` (deg). `easing`: "" ease | "linear" | "spring".
      - `ui.chuks`: Style `tx`/`ty`/`rot`/`scl`/`animMs`/`animEz`, serialized `tx=`/`ty=`/`rot=`/`sc=`/
        `anim=`/`ez=`; applyLayout reads `tx`/`ty`/`rotate`/`scale`/`anim`/`easing`.
      - Hosts collect the transform+opacity across the style loop and apply once after: UIKit
        `CGAffineTransform` in `UIView.animate` (spring via usingSpringWithDamping); SwiftUI transform
        modifiers + `.animation(_, value:)` keyed on the transform; Android a `ViewPropertyAnimator`
        (Overshoot interpolator for spring).
      - **UIKit bug found + fixed:** `relayout()` set `.frame` AFTER `style()` applied the transform, which
        corrupts a transformed view (frame is transform-affected) — the box vanished. Fixed: position
        transformed views via `bounds` + `center` (transform-safe) instead of `.frame`.
      - **Verified on all 3**: a box springs (scale 1.4 / rotate 45° / translate / fade to 55%) on tap,
        identical on SwiftUI + UIKit + Android; before/after captured. Test screen `app/views/ex_anim.chuks`.
      - Follow-ups: animating layout props (width/position), keyframes/sequences, repeat/loop, gesture-driven.
- [x] **Gesture system** (①) — swipe / double-tap / long-press beyond tap. uikit [x] · swiftui [x] · android [x] **DONE.**
      `Gesture({ onGesture }, kids)` recognizes discrete gestures and reports them through one
      `onGesture(g)` callback, `g` an encoded string: "swipe:left|right|up|down", "doubletap", "longpress".
      Discrete = one dispatch each, so it fits the event model with no per-frame round-trip.
      - Hosts: UIKit UISwipe×4 + UITap(2) + UILongPress recognizers; SwiftUI DragGesture (direction from
        translation) + onTapGesture(count:2) + onLongPressGesture; Android a `GestureDetector`
        (onFling→direction, onDoubleTap, onLongPress). All dispatch via the value channel (onChange).
        The Chuks `onGesture` prop is wired to `n.onChange`.
      - **Verified**: real events on Android drove "swipe:left" and "longpress" into state; iOS (SwiftUI)
        "longpress" verified via a synthetic drag; both iOS engines compile. double-tap shares the same
        (verified) dispatch path — only hard to trigger via automation timing. Test screen `app/views/ex_gesture.chuks`.
      - Gotcha (again): single-function map literal `{ onGesture: fn }` needs a `map[string]Prop` annotation.
      - Follow-ups: continuous pan (finger-following, native-tracked) + pinch-to-zoom.
- [x] **DatePicker** (①) — native date/time picker. uikit [x] · swiftui [x] · android [x] **DONE.**
      Controlled like Select/Slider: the parent owns `value` (an ISO string) and passes it;
      `onChange(v: string)` fires with the newly-picked ISO value. `mode`: "date" ("YYYY-MM-DD"),
      "time" ("HH:MM"), "datetime" ("YYYY-MM-DDTHH:MM"). Empty `value` starts at "now".
      `DatePicker({ value, mode, onChange, bg, color, radius, h })`.
      - `ui.chuks`: `DatePicker`/`mkDatePicker`, Style `dpmode` (serialized `dp=<mode>`); the ISO
        value rides the node's **text** channel (like Select/Image) — safe since ISO has no `|`/newline.
        Reuses the Input value-dispatch path (fireValue/dispatchInput).
      - **`display` prop** ("" compact | "inline" | "wheels"), Style `dpdisp` (`dpd=`); emits a distinct
        node kind `DatePickerInline` for the always-open displays so each host picks the view type at
        creation. iOS: `preferredDatePickerStyle`/`.datePickerStyle` = .compact/.inline(.graphical)/.wheels.
        Android has no idiomatic always-open date/time widget (the legacy `android.widget.DatePicker`
        calendar doesn't render without Material Components, which this build omits), so inline/wheels
        fall back to the same Material dialog — the platform pattern (matches RN's datetimepicker).
      - Hosts: UIKit `UIDatePicker` (.compact, `contentHorizontalAlignment=.leading`); SwiftUI a
        dedicated `ChuksDatePicker` with seeded `@State` (like ChuksSlider, so no spurious mount
        dispatch) + `BoxStyle(align:.leading)`; Android a `Button` (Gravity.START) that opens the
        native `DatePickerDialog` / `TimePickerDialog` seeded from the current ISO value. Each host
        parses/formats ISO with a fixed POSIX-style formatter so the string round-trips regardless
        of device locale. All three left-align the field for a consistent look.
      - **Verified on all 3**: renders in date/time/datetime on both iOS engines + Android; the
        native Android dialog opened on tap (seeded to "Tue, Jun 15 1993") and drove the state.
        Test screen `app/views/ex_datepicker.chuks` + a row in the Components tab (`kit.chuks`).
- [x] **Menu / context menu** (①) — native action menus. uikit [x] · swiftui [x] · android [x] **DONE.**
      Two builders, both dispatching the picked item's index via `onChange` (parse with `int(v)`):
      - `Menu({ label, onChange }, items)` — a trigger button that opens a pull-down action menu (no
        persistent selection, unlike Select). Reuses Select's UIMenu / SwiftUI Menu / Android PopupMenu
        plumbing. Text channel = `label \t item0 \t item1 …` (parts[0] is the label).
      - `ContextMenu({ onChange }, items, kids)` — long-press the wrapped `kids` to open the menu.
        UIKit `UIContextMenuInteraction` (delegate builds the UIMenu on demand), SwiftUI `.contextMenu`,
        Android `setOnLongClickListener` -> PopupMenu. Items ride the text channel tab-joined.
      - **Verified**: renders on both iOS engines; on Android (real events) the pull-down picked "Rename"
        (index 1 -> state) and the long-press context menu picked "Duplicate" (index 1 -> state). UIKit/
        SwiftUI menus open on a real tap only (synthetic sim events don't drive them, like Select).
        Test screen `app/views/ex_menu.chuks`.
      - Gotcha: a **single-function map literal** (`{ onChange: fn }`) infers a narrow map type that won't
        widen to `map[string]Prop`; annotate the local as `map[string]Prop` (or add another prop key).
- [x] **Grid** (② composed) — equal-column grid. uikit [x] · swiftui [x] · android [x] **DONE.**
      `Grid({ cols, gap }, items)` lays items into `cols` equal-width columns. Pure Chuks — composed
      from `Row`/`View`, so no host code and it works on every engine automatically. Chunks items into
      rows of `cols`; each cell is a `View({ grow: 1 }, [item])` (its child stretches to fill via
      align-items: stretch), and the last row is padded with empty cells to keep alignment. For a long,
      scrolling grid, wrap it in `Scroll`. Also exported a real **`View`** box primitive along the way
      (the base container; the docs had referenced it but it was never exported).
      - **Verified on all 3**: identical 3-column, 8-tile gallery on SwiftUI + UIKit + Android; the last
        row (2 tiles) stays left-aligned via the filler cells. Test screen `app/views/ex_grid.chuks`.
      - A virtualized "grid list" (windowed, like List) is a future follow-up; this covers galleries/tiles.
- [x] **Map** (①) — native map centered on a coordinate. uikit [x] · swiftui [x] · android [x] **DONE.**
      `Map({ lat, lng, zoom, grow })` — coords are strings (the Prop union has no float), zoom an integer.
      A pin marks the center. "lat,lng,zoom" rides the text channel (like WebView's URL).
      - Hosts: iOS UIKit `MKMapView` + SwiftUI a `ChuksMap` UIViewRepresentable wrapping MKMapView
        (MapKit, **no API key**; setRegion from a span = 360/2^zoom, plus an MKPointAnnotation). The
        MapKit map reskins light/dark with the OS. Android: an **OpenStreetMap web view** (reuses the
        WebView machinery) loading the OSM embed URL with a computed bbox + marker — no key, no new deps.
      - **Why not a native Android map:** the Google Maps SDK needs play-services-maps + an API key,
        which the minimal (no-Gradle-deps) build can't include, so Android uses the OSM web map. OSM's
        embed has no dark tile layer, so Android tiles stay light (only the app chrome reskins).
      - **Verified on all 3**: native Apple Maps of SF (light + dark) with a pin on both iOS engines;
        interactive OSM of SF with a marker + zoom controls on Android. Test screen `app/views/ex_map.chuks`.
- [x] **Canvas / SVG** (①) — a vector drawing surface. uikit [x] · swiftui [x] · android [x] **DONE.**
      `Canvas(p, shapes: []string)` draws a list of shapes natively — Core Graphics (UIKit), SwiftUI
      `Canvas` (GraphicsContext), and `android.graphics` (a custom `DrawCanvas` View). Declarative, not
      imperative: shape builders `Rect`/`Circle`/`Line`/`Path` each return a compact descriptor string;
      Canvas joins them with ";" into the text channel (fields comma-separated). `Path.d` is SVG path
      data (M/L/Z subset), space-separated so it never contains a "," or ";". Each host has a tiny
      shared-shape path parser. Android multiplies coords by density (dpf) so a canvas matches iOS points.
      - Shapes: `Rect({x,y,w,h,fill,stroke,strokeWidth,radius})`, `Circle({cx,cy,r,fill,stroke,strokeWidth})`,
        `Line({x1,y1,x2,y2,stroke,strokeWidth})`, `Path({d,fill,stroke,strokeWidth})`.
      - **Verified on all 3**: identical scene (filled + stroked rounded rects, a circle, a line, a filled
        and a stroked SVG-path triangle) on SwiftUI + UIKit + Android. Test screen `app/views/ex_canvas.chuks`.
      - Follow-ups: curves (C/Q path commands), gradients, and per-shape opacity/rotation.
- [x] **WebView** (①) — native web view. uikit [x] · swiftui [x] · android [x] **DONE.**
      `WebView({ url, w, h, grow, radius })` loads a URL. The URL rides the text channel (like
      `Image`), so it may not contain a newline or "|" — fine for normal URLs. No new Style field.
      - `ui.chuks`: `WebView` builder → kind "WebView"; URL in the node's text.
      - Hosts: UIKit `WKWebView` (setText loads the URLRequest); SwiftUI a `ChuksWeb`
        UIViewRepresentable; Android `android.webkit.WebView` (JS + DOM storage on; INTERNET
        permission already present from the remote-image work).
      - **Verified on all 3**: example.com rendered on the SwiftUI + UIKit simulator and the
        Android emulator, filling the region below the NavBar via `grow: 1`.
      - Follow-up: inline `html` (base64 over the text channel so newlines/"|" survive the stream)
        and a load/error/nav callback.

## Verification bar

Each native primitive: renders and is interactive on BOTH iOS engines
(uikit + swiftui) and Android; has a test route/screen; VM equals AOT for any
Chuks-side logic. Nothing committed until hand-tested (per project rule).

## Related docs

- `docs/previews/primitive-coverage.html` — this gap analysis, rendered.
- `docs/previews/state-and-rendering.html` — the state + rendering system.
- `docs/swiftui-engine.md` — the SwiftUI renderer (how a kind gets mapped).
- `docs/lazy-render-migration.md` · `docs/global-state-store.md`.
