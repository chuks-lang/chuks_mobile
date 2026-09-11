#!/usr/bin/env python3
"""
Style reset-coverage guard (the "never again" invariant for the reused-node
stale-state bug class; see docs/ui-update-model-vs-rn.md).

The reconciler reuses a native view by tree position and sends a COMPLETE style
string that omits default-valued props (core/ui.chuks Style.str()). So every wire
key must have a defined reset story on the host, or a reused node inherits the
previous role's value for that key. We hit that three times (Yoga geometry, then
paint, then text alignment): one bug class.

This guard makes the invariant enforceable instead of hoped-for:

  1. It extracts the authoritative set of wire keys from Style.str() in
     core/ui.chuks (the single source of truth for what crosses to the host).
  2. It requires every key to be classified in MANIFEST below with HOW it is kept
     from going stale on a reused node.
  3. It FAILS if any wire key is unclassified (a new prop added to str() without
     a reset story), or if the count of known "deferred" gaps grows past the
     ratchet baseline.

So adding a style prop forces a reviewed decision about its reset. That is the
structural guarantee RN gets from typed prop structs; we get it from this check.

Run:  python3 tests/style_reset_coverage.py     (exits non-zero on failure)
      (also invoked by tests/run.sh)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI = os.path.join(ROOT, "core", "ui.chuks")

# ── Reset dispositions ────────────────────────────────────────────────────────
# Every wire key is one of these. The disposition documents WHY a reused node
# cannot strand a stale value for that key.
#
#   reset       Reset to its default in the host reset path (iOS resetLayoutStyle
#               / resetPaintStyle, Android yResetStyle + the style() reset block)
#               before the incoming style is applied. Safe on both platforms.
#   reapplied   The host re-applies it from a local default on EVERY style() pass
#               (e.g. font size/weight), so an absent key already yields the
#               default. Cannot go stale.
#   persistent  Re-emitted every render while active by protocol (Video keys and
#               controlled widgets: a removed key would strand the control, so the
#               reconciler always emits it when set). Not a reuse hazard.
#   global      App-singleton state (status bar), not per-node, so node reuse
#               never carries it.
#   imperative  A one-shot action (seek/focus/present), not persistent visual
#               state on the node.
#   deferred    KNOWN reset gap, tracked for Option A. Ratcheted below so the set
#               can only shrink, never silently grow.
RESET, REAPPLIED, PERSISTENT, GLOBAL, IMPERATIVE, DEFERRED = (
    "reset", "reapplied", "persistent", "global", "imperative", "deferred")

# key -> (disposition, note). Keep in lockstep with the host reset paths.
MANIFEST = {
    # ---- Layout: fully reset by resetLayoutStyle (iOS) / yResetStyle (Android) --
    "d": (RESET, "flex-direction"), "j": (RESET, "justify"), "a": (RESET, "align-items"),
    "g": (RESET, "flex-grow"), "basis": (RESET, "flex-basis"), "w": (RESET, "width"),
    "h": (RESET, "height"), "p": (RESET, "padding"), "px": (RESET, "padding-x"),
    "py": (RESET, "padding-y"), "pt": (RESET, "padding-top"), "pr": (RESET, "padding-right"),
    "pb": (RESET, "padding-bottom"), "pl": (RESET, "padding-left"), "mt": (RESET, "margin-top"),
    "mr": (RESET, "margin-right"), "mb": (RESET, "margin-bottom"), "ml": (RESET, "margin-left"),
    "minw": (RESET, "min-width"), "maxw": (RESET, "max-width"), "minh": (RESET, "min-height"),
    "maxh": (RESET, "max-height"), "wpct": (RESET, "width-percent"), "hpct": (RESET, "height-percent"),
    "bottom": (RESET, "position bottom"), "aspect": (RESET, "aspect-ratio"), "gap": (RESET, "gap"),
    "pos": (RESET, "position-type"), "top": (RESET, "position top"), "left": (RESET, "position left"),
    "right": (RESET, "position right"), "self": (RESET, "align-self"), "wrap": (RESET, "flex-wrap"),
    "hidden": (RESET, "display:none -> isHidden + Yoga display both reset"),

    # ---- Paint / geometry: reset in resetPaintStyle (iOS) + Android reset block --
    "bg": (RESET, "background color"), "bc": (RESET, "border color"), "bw": (RESET, "border width"),
    "bwt": (RESET, "border top"), "bwr": (RESET, "border right"), "bwb": (RESET, "border bottom"),
    "bwl": (RESET, "border left"), "bstyle": (RESET, "border style (dashed/dotted layers)"),
    "r": (RESET, "corner radius"), "rtl": (RESET, "radius TL"), "rtr": (RESET, "radius TR"),
    "rbr": (RESET, "radius BR"), "rbl": (RESET, "radius BL"), "shadow": (RESET, "shadow/elevation"),
    "glass": (RESET, "frosted panel"), "overflow": (RESET, "clip"), "z": (RESET, "z-order"),
    # Gradient + backdrop blur: both are decoration views held per id, and both host reset
    # paths drop the view and its cached spec (iOS resetPaintStyle, Android's style reset),
    # so a reused node cannot keep the previous role's fill or material.
    "grad": (RESET, "gradient colors"), "gradang": (RESET, "gradient angle"),
    "gradstop": (RESET, "gradient stops"),
    "bkblur": (RESET, "backdrop blur strength"), "bktint": (RESET, "backdrop blur tint"),
    "opacity": (RESET, "alpha (post-loop else-branch)"),
    "tx": (RESET, "translate-x (post-loop else-branch)"), "ty": (RESET, "translate-y (post-loop else-branch)"),
    "rot": (RESET, "rotation (post-loop else-branch)"), "sc": (RESET, "scale (post-loop else-branch)"),
    "dis": (RESET, "disabled dim + gate"), "press": (RESET, "pressable active alpha"),
    "ldelay": (RESET, "long-press delay"), "hitslop": (RESET, "enlarged tap area (iOS)"),
    "ta": (RESET, "text alignment / gravity"), "nlines": (RESET, "max lines"),
    "ellip": (RESET, "truncation mode"), "tint": (RESET, "image tint"), "filt": (RESET, "image filter"),
    "blur": (RESET, "image blur"), "rmode": (RESET, "image scale mode"),
    "deco": (RESET, "text decoration (iOS dict cleared / Android paintFlags re-set)"),
    "txform": (RESET, "text transform (iOS dict cleared / Android re-applied)"),
    "tracking": (RESET, "letter spacing (iOS dict cleared / Android re-applied)"),

    # ---- Always re-applied from a default every pass -> cannot go stale ---------
    "fs": (REAPPLIED, "font size (local default 14)"), "fw": (REAPPLIED, "font weight"),
    "font": (REAPPLIED, "font family"), "fontfam": (REAPPLIED, "font family"),
    "italic": (REAPPLIED, "italic trait folded into font"),

    # ---- Persistent-by-protocol: re-emitted every render while active ----------
    "vid": (PERSISTENT, "video source"), "vplay": (PERSISTENT, "video playing"),
    "vloop": (PERSISTENT, "video loop"), "vmute": (PERSISTENT, "video mute"),
    "vfit": (PERSISTENT, "video fit"), "vvol": (PERSISTENT, "video volume"),
    "vrate": (PERSISTENT, "video rate"), "vctrl": (PERSISTENT, "video native controls kind"),
    "on": (PERSISTENT, "switch state (controlled)"), "prog": (PERSISTENT, "progress (controlled)"),
    "slv": (PERSISTENT, "slider value (controlled)"), "slmin": (PERSISTENT, "slider min (controlled)"),
    "slmax": (PERSISTENT, "slider max (controlled)"), "slstep": (PERSISTENT, "slider step (controlled)"),
    "rfsh": (PERSISTENT, "pull-to-refresh (controlled)"), "seli": (PERSISTENT, "select index (controlled)"),
    "mvis": (PERSISTENT, "modal visible (driven every render)"), "mpos": (PERSISTENT, "modal position"),
    "swtc": (PERSISTENT, "switch thumb color (with the switch)"), "paging": (PERSISTENT, "scroll paging (with the scroll)"),
    "stick": (PERSISTENT, "scroll stick-bottom (with the scroll)"), "horiz": (PERSISTENT, "horizontal list (with the scroll)"),
    "dp": (PERSISTENT, "date-picker mode"), "dpd": (PERSISTENT, "date-picker display"),
    "img": (PERSISTENT, "systemName icon (with the image)"),
    "sel": (RESET, "text selectable (iOS reset; Android sets each present)"),

    # ---- App-singleton (status bar): not per-node reuse state -------------------
    "sbh": (GLOBAL, "status bar hidden"), "sbstyle": (GLOBAL, "status bar style"),
    "sbcolor": (GLOBAL, "status bar color"), "sbnavcolor": (GLOBAL, "nav bar color"),

    # ---- One-shot imperative actions -------------------------------------------
    "seek": (IMPERATIVE, "video seek"), "afoc": (IMPERATIVE, "auto-focus input"),
    "avis": (IMPERATIVE, "present/dismiss alert"), "spin": (IMPERATIVE, "image loading spinner"),
    "anim": (IMPERATIVE, "animation duration (transient)"), "ez": (IMPERATIVE, "animation easing (transient)"),

    # ---- Closed by Option A: reset on both platforms now -----------------------
    "fg": (RESET, "text color: iOS resetPaintStyle; Android resets to defaultTextColor + control tints"),
    "leading": (RESET, "line height: iOS dict cleared; Android setLineSpacing(0,1)"),
    "kbt": (RESET, "input keyboard type: iOS + Android EditText reset to TYPE_CLASS_TEXT"),
    "ret": (RESET, "input return key: iOS + Android EditText reset to IME_ACTION_DONE"),
    "edit": (RESET, "input editable: iOS + Android EditText reset to enabled/focusable"),
    "acap": (RESET, "input autocapitalization: iOS + Android EditText inputType reset"),
    "acor": (RESET, "input autocorrect: iOS + Android EditText inputType reset"),
    "maxlen": (RESET, "input max length: iOS + Android EditText filters cleared"),
    "sec": (RESET, "input secure: iOS + Android EditText transformationMethod reset (isSecure keeps mask)"),
}

# Ratchet: the deferred set can only shrink. All Option A gaps closed -> 0.
DEFERRED_BASELINE = 0


def wire_keys_from_str(src: str) -> set:
    """Extract every key emitted by Style.str() -> the authoritative wire-key set."""
    # Body of str() only, so we don't pick up keys mentioned elsewhere.
    m = re.search(r"public str\(\)\s*:\s*string\s*\{(.*?)\n    \}", src, re.S)
    body = m.group(1) if m else src
    # Keys are emitted as:  s = s + "key=" ...
    return set(re.findall(r'\+\s*"([a-z][a-z0-9]*)=', body))


def main() -> int:
    src = open(UI, encoding="utf-8").read()
    keys = wire_keys_from_str(src)
    if not keys:
        print("FAIL: could not extract any wire keys from core/ui.chuks str()")
        return 1

    missing = sorted(k for k in keys if k not in MANIFEST)   # in str(), unclassified
    stale = sorted(k for k in MANIFEST if k not in keys)     # in manifest, no longer emitted
    deferred = sorted(k for k in keys if MANIFEST.get(k, (None,))[0] == DEFERRED)

    counts = {}
    for k in keys:
        d = MANIFEST.get(k, ("?",))[0]
        counts[d] = counts.get(d, 0) + 1

    print(f"style reset-coverage: {len(keys)} wire keys")
    for d in (RESET, REAPPLIED, PERSISTENT, GLOBAL, IMPERATIVE, DEFERRED):
        if counts.get(d):
            print(f"  {counts[d]:>3}  {d}")

    ok = True
    if missing:
        ok = False
        print("\nFAIL: wire keys with NO reset story (classify each in MANIFEST):")
        for k in missing:
            print(f"  - {k}")
        print("  A reused node would inherit the previous role's value for these.")
    if stale:
        print("\nWARN: MANIFEST entries no longer emitted by str() (remove them):")
        for k in stale:
            print(f"  - {k}")
    if len(deferred) > DEFERRED_BASELINE:
        ok = False
        print(f"\nFAIL: deferred reset gaps grew to {len(deferred)} (baseline {DEFERRED_BASELINE}).")
        print("  New props must be reset, not deferred. Deferred gaps (Option A targets):")
        for k in deferred:
            print(f"  - {k}: {MANIFEST[k][1]}")
    elif deferred:
        print(f"\n  {len(deferred)} deferred gap(s) within baseline {DEFERRED_BASELINE} (Option A targets):")
        for k in deferred:
            print(f"    - {k}: {MANIFEST[k][1]}")

    print("\n" + ("style reset-coverage: OK" if ok else "style reset-coverage: FAILED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
