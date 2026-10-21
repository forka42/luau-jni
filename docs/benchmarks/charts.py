"""The README's benchmark charts, light and dark SVGs (GitHub's surfaces), beside this script: python charts.py"""
import sys
from pathlib import Path

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent

THEMES = {
    "light": dict(surface="#ffffff", ink="#1f2328", muted="#59636e", grid="#d1d9e0", jni="#2a78d6", java="#eb6834"),
    "dark": dict(surface="#0d1117", ink="#f0f6fc", muted="#9198a1", grid="#3d444d", jni="#3987e5", java="#d95926"),
}
FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans', Helvetica, Arial, sans-serif"

# (group, [(series, value)]): series "jni" or "java".
CHARTS = {
    "call-time": dict(
        title="Time per call from Luau into Java and back",
        subtitle="Microseconds per call, lower is better (a property read in a game engine's script bridge)",
        unit=" µs", fmt=lambda v: f"{v:.2f}" if v < 10 else f"{v:.1f}", max=60, ticks=[0, 20, 40, 60],
        groups=[("GraalVM native image", [("jni", 1.15), ("java", 58.8)]),
                ("JVM (HotSpot)", [("jni", 1.33), ("java", 1.39)])]),
    "steps": dict(
        title="Simulation steps per second, 300 Luau threads",
        subtitle="Each thread wakes on one event every step and moves a part; higher is better (the engine aims for 60)",
        unit="", fmt=lambda v: f"{v:.1f}", max=70, ticks=[0, 20, 40, 60],
        groups=[("GraalVM native image", [("jni", 60.7), ("java", 17.3)]),
                ("JVM (HotSpot)", [("jni", 60.8), ("java", 60.9)])]),
}
NAMES = {"jni": "luau-jni (JNI)", "java": "luau-java (FFM)"}

W = 720
LEFT, RIGHT = 180, 64            # (group labels; room for a value at the longest bar's tip)
BAR, GAP, GROUP_GAP = 22, 2, 26
TOP = 104                        # (title, subtitle, legend)


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def bar(x0, y, w, h, r, fill):
    # Square at the baseline, 4px rounded at the data end.
    r = min(r, w, h / 2)
    return (f'<path d="M{x0},{y} H{x0 + w - r} Q{x0 + w},{y} {x0 + w},{y + r} V{y + h - r} '
            f'Q{x0 + w},{y + h} {x0 + w - r},{y + h} H{x0} Z" fill="{fill}"/>')


def chart(spec, t):
    plot_w = W - LEFT - RIGHT
    sx = lambda v: LEFT + v / spec["max"] * plot_w
    group_h = 2 * BAR + GAP
    plot_h = len(spec["groups"]) * group_h + (len(spec["groups"]) - 1) * GROUP_GAP
    H = TOP + plot_h + 44
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
         f'font-family="{FONT}" role="img" aria-label="{esc(spec["title"])}">',
         f'<rect width="{W}" height="{H}" rx="6" fill="{t["surface"]}"/>',
         f'<text x="16" y="30" font-size="16" font-weight="600" fill="{t["ink"]}">{esc(spec["title"])}</text>',
         f'<text x="16" y="52" font-size="12.5" fill="{t["muted"]}">{esc(spec["subtitle"])}</text>']
    # Legend: a swatch beside each name (identity from the mark, never from coloured text).
    lx = 16
    for s in ("jni", "java"):
        o.append(f'<rect x="{lx}" y="70" width="12" height="12" rx="2" fill="{t[s]}"/>')
        o.append(f'<text x="{lx + 18}" y="80.5" font-size="12.5" fill="{t["ink"]}">{esc(NAMES[s])}</text>')
        lx += 18 + len(NAMES[s]) * 7.2 + 22
    # Gridlines and ticks: hairline, solid, recessive.
    bottom = TOP + plot_h
    for v in spec["ticks"]:
        x = sx(v)
        o.append(f'<line x1="{x}" y1="{TOP - 6}" x2="{x}" y2="{bottom + 6}" stroke="{t["grid"]}" stroke-width="1"/>')
        o.append(f'<text x="{x}" y="{bottom + 24}" font-size="12" fill="{t["muted"]}" text-anchor="middle">{v}{spec["unit"]}</text>')
    y = TOP
    for name, bars in spec["groups"]:
        o.append(f'<text x="{LEFT - 14}" y="{y + group_h / 2 + 4.5}" font-size="13" fill="{t["ink"]}" text-anchor="end">{esc(name)}</text>')
        for i, (s, v) in enumerate(bars):
            by = y + i * (BAR + GAP)
            o.append(bar(LEFT, by, sx(v) - LEFT, BAR, 4, t[s]))
            label = spec["fmt"](v) + spec["unit"]
            o.append(f'<text x="{sx(v) + 6}" y="{by + BAR / 2 + 4.5}" font-size="12.5" font-weight="600" fill="{t["ink"]}">{esc(label)}</text>')
        y += group_h + GROUP_GAP
    o.append("</svg>")
    return "\n".join(o) + "\n"


OUT.mkdir(parents=True, exist_ok=True)
for key, spec in CHARTS.items():
    for mode, t in THEMES.items():
        (OUT / f"{key}-{mode}.svg").write_text(chart(spec, t), encoding="utf-8")
        print(OUT / f"{key}-{mode}.svg")
