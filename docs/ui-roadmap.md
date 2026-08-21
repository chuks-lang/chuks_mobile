# Chuks Mobile UI/UX Roadmap

The plan to make Chuks Mobile a framework developers *love* to build apps with.
Guiding bet: Chuks renders **real native views** (like React Native), not a
self-drawn engine (Flutter), so it feels native by default. The work below is the
lovable layer on top of that foundation. Check items off as they land.

**Architecture (locked):** everything is a **function returning `Node`** (like
RN's `JSX.Element`), screens and components alike; no page/component base class to
extend. **State is just a variable** (module-level), since the framework
re-renders the whole tree on every event, so no hooks or store are needed for most
cases. Routing (`NavStack`) maps names to screen functions `() -> Node`. A typed,
optional store covers large-app global state.

Status legend: `[x]` done · `[~]` in progress · `[ ]` todo

---

## Phase 1 — Design tokens + component kit  ✅

The foundation: design against semantic roles, get light/dark for free.

- [x] Semantic token system — `core/theme.chuks` (roles: bg/surface/surface2/text/muted/subtle/primary/border/success/warning/danger/info + soft variants, in parallel light + dark palettes; `setTheme`, `toggleTheme`, `themeMode`, `tk`, `tkSpace`)
- [x] `tw()` resolves semantic tokens + named spacing — `core/tw.chuks` (`bg-surface`, `text-muted`, `p-md`)
- [x] Content kit — `core/kit.chuks`: Button (primary/secondary/ghost/danger), Card, Input, Badge (tones), Chip, Avatar, Divider, SectionHeader, ListItem, Switch
- [x] Living gallery with a one-tap Light/Dark toggle — `app/views/showcase.chuks`
- [x] Verified on iOS (sim) + Android (emulator), identical from one source

## Phase 2 — Structural / navigation layer  ~

The app skeleton. You can't build a real app without these.

- [x] **NavBar** (app bar / header): title + leading/trailing icon actions + hairline — `core/nav.chuks`
- [x] **TabBar** (bottom tabs): active/inactive states, Lucide icons + labels — `core/nav.chuks`
- [x] **Tabs navigator** (Expo Router `(tabs)/_layout` model): `tabs/` folder, one function-screen per file, `_layout` registers them; switches on tap — `core/nav.chuks` + `app/tabs/`
- [x] Screens are **functions** `() -> Node` (not classes) — sidesteps the AOT base-typed virtual-dispatch limitation; state lives at module scope
- [ ] **BottomSheet**: slide-up panel for actions/details
- [ ] **Modal / Dialog**: centered confirm/alert with scrim
- [ ] **Toast / Snackbar**: transient feedback
- [ ] Animated stack transitions in NavStack (push/pop with a native curve)

## Phase 3 — Motion & gestures

The RN "feel." Most valuable applied to the Phase 2 transitions.

- [ ] Spring/timing animation primitive on the native reconciler
- [ ] Transitions: sheet slide-up, modal present, tab crossfade, toast ease-in, screen push
- [ ] Gesture handling: tap ripple/press states, swipe, drag-to-dismiss, pan

## Phase 4 — Styling ergonomics

The everyday DX developers touch constantly.

- [ ] Expand `tw()` coverage (min/max sizing, per-edge padding, more utilities)
- [ ] Per-edge padding in the Style model (px/py) across both native hosts
- [ ] Token-based styling helpers / variants API

## Phase 5 — Showcase + docs

Make it visible, copyable, adoptable.

- [ ] Full component gallery (every component + variants)
- [ ] Getting-started guide (install → hello world → first screen)
- [ ] Component reference (see `components.md`)
- [ ] Feed into the chuks-mobile-website

---

## Done log

- Design token system + content kit shipped, verified on both platforms with a live theme toggle.
