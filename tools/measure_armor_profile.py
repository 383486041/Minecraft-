#!/usr/bin/env python3
"""把 OBJ 里每个部件的 X/Z 尺寸按 Y 带 dump 出来，用来设计「比例 pass」的剖面。

为什么单独写：`silhouetteTransform` 里做的是顶点空间变形，改之前必须知道
每个 Y 带上外甲板到底有多宽、内衬（豁免组）有多宽 —— 压缩量超过内衬余量
就会让皮肤/内衬穿出来。凭感觉改是上一轮翻车的直接原因。

用法：
  python tools/measure_armor_profile.py sentinel body
  python tools/measure_armor_profile.py sentinel leg 0.5
  python tools/measure_armor_profile.py vanguard boot
参数：<variant> <前缀过滤(逗号分隔)> [带宽，默认 0.5]
"""

from __future__ import annotations

import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ARMOR_DIR = ROOT / "src/main/resources/assets/flight_armor/models/armor"


def load(path: Path):
    """返回 {group: [(x,y,z), ...]}。只吃 g/v，面不影响包围盒。"""
    groups: dict[str, list[tuple[float, float, float]]] = {}
    current = None
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.startswith("g "):
            current = raw[2:].strip()
            groups.setdefault(current, [])
        elif raw.startswith("v ") and current is not None:
            p = raw.split()
            groups[current].append((float(p[1]), float(p[2]), float(p[3])))
    return groups


def main() -> int:
    variant = sys.argv[1] if len(sys.argv) > 1 else "sentinel"
    prefixes = tuple(
        p for p in (sys.argv[2].split(",") if len(sys.argv) > 2 else ["body"]) if p
    )
    band = float(sys.argv[3]) if len(sys.argv) > 3 else 0.5

    groups = load(ARMOR_DIR / f"flight_armor_{variant}.obj")
    selected = {n: v for n, v in groups.items() if n.startswith(prefixes)}
    if not selected:
        print(f"no groups for prefixes {prefixes} in {variant}")
        return 1

    ys = [v[1] for vs in selected.values() for v in vs]
    y0, y1 = min(ys), max(ys)
    print(f"# {variant}  prefixes={prefixes}  Y range [{y0:.2f}, {y1:.2f}]  band={band}")

    # 每个 Y 带：所有被该带覆盖的组名 + 该带内顶点的 X/Z 极值
    n_bands = int((y1 - y0) / band) + 1
    print(f"{'Y band':>15} | {'X range':>15} | {'Z range':>15} | 最宽的组")
    for i in range(n_bands):
        lo = y0 + i * band
        hi = lo + band
        xs: list[float] = []
        zs: list[float] = []
        widest = ""
        best = -1.0
        for name, vs in selected.items():
            band_vs = [v for v in vs if lo - 1e-6 <= v[1] <= hi + 1e-6]
            if len(band_vs) < 2:
                continue
            bx = [v[0] for v in band_vs]
            bz = [v[2] for v in band_vs]
            xs += bx
            zs += bz
            w = max(bx) - min(bx)
            if w > best:
                best = w
                widest = name
        if not xs:
            continue
        print(
            f"[{lo:6.2f},{hi:6.2f}] | "
            f"[{min(xs):6.2f},{max(xs):6.2f}] | "
            f"[{min(zs):6.2f},{max(zs):6.2f}] | "
            f"{best:5.2f} {widest}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
