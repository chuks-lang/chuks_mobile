#!/usr/bin/env python3
"""
Host parity: compare the view-tree dumps tests/host writes on iOS and Android.

Three checks, in the order the bugs happen:

  1. DRIFT (per host). A line carries `host=... DRIFT` (Android) or `yoga=... DRIFT`
     (iOS) when the platform's frame for a view differs from the frame the engine laid
     out. Yoga is what the app asked for and the View is what the user gets; a gap is a
     host bug (a ScrollView that measured its content for itself and dropped the
     content's padding, so a page that just fit would not scroll). Any DRIFT fails.

  2. PARITY (across hosts). The same screen, inside the same 360x640 box, must resolve
     to the same frames on both hosts, node for node: a node present on one host only,
     hidden or zero-sized on one host only, or a frame off by more than the tolerance
     fails. Text nodes get a wider tolerance, since the two platforms measure the same
     string with different fonts (a few points either way is the fonts, more is a wrap
     that happened on one side only).

  3. SCROLL RANGE (per host). A scroll's line carries `scroll=WxH` (or `hscroll=WxH`
     for a sideways one): what the host lets it scroll over. It must cover the content
     node's frame (the scroll's first child) along the scroll axis. A range cut short is
     a page that will not scroll: the live list's content height got pinned to its own
     height when a horizontal Scroll elsewhere on the screen flipped a host-wide flag,
     after the screen was rebuilt. Both hosts must also agree on the range.

Usage:
  python3 tools/host_parity.py <dir-with-ios-and-android-subdirs>
Exit 0 when every screen is clean, 1 otherwise.
"""
import os, re, sys

# Tolerances in points, after the 1pt integer truncation each dump already applies.
TOL_BOX = 2      # any non-text node: position and size
TOL_TEXT_W = 12  # a label's width: font metrics differ
TOL_TEXT_H = 6   # a label's height: one line of a different font, never a second line
TEXT_CLASSES = ("TextView", "UILabel", "SelectableLabel", "EditText", "UITextField", "UITextView", "ChuksTextField")

# Subtrees whose vertical placement follows the DEVICE, not the host: a floating tab bar
# is as tall as its content plus the bottom safe-area inset, and the simulator (34pt
# home indicator) and the emulator (gesture bar) do not share one. Their x and width
# are still compared; y and height are not.
INSET_DEPENDENT = {"tabs": ("app.0.0.2",)}

LINE = re.compile(r'^(\s*)(\S+)\s+(\S+)\s+(-?\d+),(-?\d+)\s+(-?\d+)x(-?\d+)(.*)$')
SCROLL = re.compile(r'\b(h?scroll)=(\d+)x(\d+)')

def parse(path):
    """id -> dict(cls, x, y, w, h, hidden, zero, drift, text). Stops at the detached marker."""
    nodes = {}
    with open(path) as f:
        for raw in f:
            line = raw.rstrip("\n")
            if line.startswith("-- not attached"):
                break
            m = LINE.match(line)
            if not m:
                continue
            _, nid, cls, x, y, w, h, rest = m.groups()
            sm = SCROLL.search(rest)
            nodes[nid] = dict(cls=cls, x=int(x), y=int(y), w=int(w), h=int(h),
                              hidden="  hidden" in rest, zero="ZERO-SIZED" in rest,
                              drift="DRIFT" in rest, rest=rest.strip(),
                              scroll=(sm.group(1), int(sm.group(2)), int(sm.group(3))) if sm else None)
    # A hidden node hides its subtree: nothing under a closed sheet is drawn, and where
    # the host parks it is the host's business.
    for nid in sorted(nodes):
        parent = nid.rsplit(".", 1)[0] if "." in nid else None
        if parent in nodes and nodes[parent]["hidden"]:
            nodes[nid]["hidden"] = True
    return nodes

def scroll_range_problems(host, nodes):
    """A visible scroll's range must cover its content node along its axis."""
    out = []
    for nid, n in nodes.items():
        if not n["scroll"] or n["hidden"] or not in_box(nid):
            continue
        kind, sw, sh = n["scroll"]
        c = nodes.get(nid + ".0")
        if not c or c["hidden"]:
            continue
        short = sw < c["w"] - TOL_BOX or (kind == "scroll" and sh < c["h"] - TOL_BOX)
        if short:
            out.append(f"{host}: {nid} {n['cls']} scroll range {sw}x{sh} is short of its content {c['w']}x{c['h']}: it will not scroll over all of it")
    return out

def in_box(nid):
    """Only what the fixture drew inside its box: the root and the caption differ by device."""
    return nid.startswith("app.0.") or nid == "app.0"

def is_text(n):
    return any(n["cls"].endswith(c) for c in TEXT_CLASSES)

def compare(name, ios, android):
    problems = []
    for host, nodes in (("ios", ios), ("android", android)):
        for nid, n in nodes.items():
            if n["drift"] and in_box(nid):
                problems.append(f"{host}: {nid} {n['cls']} platform frame differs from the engine's: {n['rest']}")
        problems += scroll_range_problems(host, nodes)
    ids_i = {k for k in ios if in_box(k)}
    ids_a = {k for k in android if in_box(k)}
    for nid in sorted(ids_i - ids_a):
        problems.append(f"parity: {nid} exists on iOS only ({ios[nid]['cls']})")
    for nid in sorted(ids_a - ids_i):
        problems.append(f"parity: {nid} exists on Android only ({android[nid]['cls']})")
    for nid in sorted(ids_i & ids_a):
        a, b = ios[nid], android[nid]
        if a["hidden"] != b["hidden"]:
            problems.append(f"parity: {nid} hidden on {'iOS' if a['hidden'] else 'Android'} only")
            continue
        if a["zero"] != b["zero"]:
            problems.append(f"parity: {nid} zero-sized on {'iOS' if a['zero'] else 'Android'} only")
            continue
        if a["hidden"]:
            continue
        text = is_text(a) or is_text(b)
        tol_pos = TOL_BOX
        tol_w = TOL_TEXT_W if text else TOL_BOX
        tol_h = TOL_TEXT_H if text else TOL_BOX
        dx, dy, dw, dh = abs(a["x"] - b["x"]), abs(a["y"] - b["y"]), abs(a["w"] - b["w"]), abs(a["h"] - b["h"])
        if any(nid == p or nid.startswith(p + ".") for p in INSET_DEPENDENT.get(name, ())):
            dy, dh = 0, 0
        # A text node's position shifts by its neighbours' text heights; allow the height
        # tolerance on its y too. Its containers are held to the box tolerance.
        tol_y = tol_h if text else tol_pos
        if dx > tol_pos or dy > tol_y or dw > tol_w or dh > tol_h:
            problems.append(f"parity: {nid} iOS {a['x']},{a['y']} {a['w']}x{a['h']}  Android {b['x']},{b['y']} {b['w']}x{b['h']}"
                            + (f"  ({a['cls']} / {b['cls']})" if text else ""))
        # The scroll range along the scroll axis; the cross axis is each platform's own
        # (iOS pins a sideways scroll's content height, Android measures the child).
        if a["scroll"] and b["scroll"]:
            (ka, aw, ah), (kb, bw, bh) = a["scroll"], b["scroll"]
            if ka != kb:
                problems.append(f"parity: {nid} scrolls sideways on {'iOS' if ka == 'hscroll' else 'Android'} only")
            elif abs(aw - bw) > TOL_BOX or (ka == "scroll" and abs(ah - bh) > TOL_BOX):
                problems.append(f"parity: {nid} scroll range iOS {aw}x{ah}  Android {bw}x{bh}")
    return problems

def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    root = sys.argv[1]
    idir, adir = os.path.join(root, "ios"), os.path.join(root, "android")
    names = sorted(f[:-4] for f in os.listdir(idir) if f.endswith(".txt"))
    missing = [n for n in names if not os.path.exists(os.path.join(adir, n + ".txt"))]
    if missing:
        print("android dumps missing for: " + ", ".join(missing)); sys.exit(1)
    bad = 0
    for name in names:
        ios = parse(os.path.join(idir, name + ".txt"))
        android = parse(os.path.join(adir, name + ".txt"))
        n_nodes = len([k for k in ios if in_box(k)])
        problems = compare(name, ios, android)
        if problems:
            bad += 1
            print(f"  FAIL {name:14} {n_nodes} nodes")
            for p in problems[:12]:
                print("       " + p)
            if len(problems) > 12:
                print(f"       ... {len(problems) - 12} more")
        else:
            print(f"  ok   {name:14} {n_nodes} nodes, frames agree, no drift")
    print(f"host parity: {len(names) - bad}/{len(names)} screens clean" + ("" if bad == 0 else f", {bad} FAILING"))
    sys.exit(0 if bad == 0 else 1)

if __name__ == "__main__":
    main()
