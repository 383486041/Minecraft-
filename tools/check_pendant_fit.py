"""挂件贴合自检：直接解析 ArmorCorePendantLayer 源码里的三处 box 调用，再与 OBJ 核心槽逐面对比。

**本脚本出现的每个几何数值都来自源码文本**，不是复算脚本自己的公式 —— 早先那版就是
把公式抄进脚本里，结果改源码它照样 PASS，等于假检查。

反向验证（改完挂件每次都该跑）：
  * 外壳余量 0.04 -> 0.00  → 必须报「共面」
  * CoreExtent 的 y0 改回 -8.6（头顶）→ 必须报「不在胸口区间」

用法: python tools/check_pendant_fit.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(
    ROOT, "src", "client", "java", "dev", "codex", "flightarmor", "client", "render", "obj",
    "ArmorCorePendantLayer.java")
OBJ_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "flight_armor", "models", "armor")
OBJ_OF = {
    "VANGUARD": "flight_armor_vanguard.obj",
    "SWIFT": "flight_armor_swift.obj",
    "SENTINEL": "flight_armor_sentinel.obj",
}
# 胸口在 body 局部空间的合理区间（y=0 是肩、y=-8 是头顶；核心枢轴实测 y=3.65）
CHEST_Y0_MIN, CHEST_Y1_MAX = 1.0, 8.0
SURFACE_CLEARANCE = 0.02  # 两个件的面距小于此值视为共面（会闪）

src = open(JAVA, encoding="utf-8").read()


def const(name):
    m = re.search(name + r" = ([\d.]+)F", src)
    if not m:
        sys.exit("源码里找不到常量 " + name)
    return float(m.group(1))


RIM_SCALE = const("RIM_SCALE")
CORE_SCALE = const("CORE_SCALE")

extents = {}
for variant, nums in re.findall(r"case (\w+) -> new CoreExtent\(([^)]+)\)", src):
    vals = [float(x.strip().rstrip("F")) for x in nums.split(",") if x.strip()]
    if len(vals) != 5:
        sys.exit("CoreExtent 应有 5 个参数（半宽/y0/y1/zFront/zBack），实际 %d" % len(vals))
    extents[variant] = vals
if len(extents) != 3:
    sys.exit("CoreExtent 表必须覆盖三个型号，实际 %d 个" % len(extents))


def box_of(pattern, label):
    m = re.search(pattern, src, re.S)
    if not m:
        sys.exit("找不到 %s 的 box 调用（正则不匹配，源码结构可能已变）" % label)
    parts = [p.strip() for p in m.group(1).split(",")]
    if len(parts) != 6:
        sys.exit("%s 的 box 参数应为 6 个，实际 %d" % (label, len(parts)))
    return parts


SHELL_EXPR = box_of(r"box\(shell, poseStack\.last\(\), packedLight,\s*SHELL,\s*(.*?)\);", "SHELL")
RIM_EXPR = box_of(r"box\(shell, poseStack\.last\(\), packedLight,\s*RIM,\s*(.*?)\);", "RIM")
CORE_EXPR = box_of(
    r"box\(emissive, poseStack\.last\(\), LightTexture\.FULL_BRIGHT,\s*coreColor,\s*(.*?)\);", "CORE")


def evaluate(expr, ctx):
    e = expr
    # 长名先替换，否则 core.zFront() 里的 "front" 会被先吃掉
    for key, value in sorted(ctx.items(), key=lambda kv: -len(kv[0])):
        e = e.replace(key, repr(value))
    e = re.sub(r"F\b", "", e)
    try:
        return float(eval(e, {"__builtins__": {}}, {}))
    except Exception as exc:  # noqa: BLE001 - 表达式来自源码，解析失败要显式报出来
        sys.exit("无法求值 box 参数 %r -> %r: %s" % (expr, e, exc))


def as_minmax(exprs, ctx):
    """box 的参数是 (x0, y0, z0, x1, y1, z1) 按轴分组；
    断言用的是 (xmin, xmax, ymin, ymax, zmin, zmax) 按 min/max 分组 —— 必须在这里换过来。"""
    x0, y0, z0, x1, y1, z1 = (evaluate(e, ctx) for e in exprs)
    return (min(x0, x1), max(x0, x1), min(y0, y1), max(y0, y1), min(z0, z1), max(z0, z1))


def obj_groups(path):
    groups, cur = {}, None
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.startswith("v "):
            p = line.split()
            groups.setdefault(cur, []).append((float(p[1]), float(p[2]), float(p[3])))
        elif line.startswith("g "):
            cur = line[2:].strip()
    return groups


def bound(groups, key):
    for name, vs in groups.items():
        if key in name:
            xs = [v[0] for v in vs]
            ys = [v[1] for v in vs]
            zs = [v[2] for v in vs]
            return (min(xs), max(xs), min(ys), max(ys), min(zs), max(zs))
    raise KeyError(key)


def grow_out(outer, inner, i):
    """outer 应包住 inner：min 端 outer 更小，max 端 outer 更大。返回余量。"""
    return (inner[i] - outer[i]) if i % 2 == 0 else (outer[i] - inner[i])


problems = []
for variant, (hw, y0, y1, z_front, z_back) in extents.items():
    ctx = {
        "core.halfWidth()": hw, "core.y0()": y0, "core.y1()": y1,
        "core.zFront()": z_front, "core.zBack()": z_back,
        "centerY": (y0 + y1) * 0.5, "halfHeight": (y1 - y0) * 0.5,
        "front": z_front, "RIM_SCALE": RIM_SCALE, "CORE_SCALE": CORE_SCALE,
    }
    shell = as_minmax(SHELL_EXPR, ctx)
    rim = as_minmax(RIM_EXPR, ctx)
    core = as_minmax(CORE_EXPR, ctx)

    groups = obj_groups(os.path.join(OBJ_DIR, OBJ_OF[variant]))
    cradle = bound(groups, "core_cradle")
    housing = bound(groups, "core_housing")
    reactor = bound(groups, "core_reactor")

    print("=" * 72)
    print("%s  槽半宽 %.2f  外壳 %s" % (variant, hw, "x[%.2f..%.2f] y[%.2f..%.2f] z[%.2f..%.2f]" % shell))
    print("   接缝 %s   发光 %s" % ("x[%.2f..%.2f] y[%.2f..%.2f] z[%.2f..%.2f]" % rim,
                                  "x[%.2f..%.2f] y[%.2f..%.2f] z[%.2f..%.2f]" % core))

    # 1. 挂件必须落在胸口（曾经整块写负、挂到头顶）
    if not (CHEST_Y0_MIN <= shell[2] and shell[3] <= CHEST_Y1_MAX):
        problems.append("%s 挂件 y[%.2f..%.2f] 不在胸口区间 [%.1f..%.1f]（大概率又写成了头顶）"
                        % (variant, shell[2], shell[3], CHEST_Y0_MIN, CHEST_Y1_MAX))

    # 2. 外壳罩住 cradle，各面留余量
    for i, name in enumerate(("x-", "x+", "y-", "y+", "z-", "z+")):
        gap = grow_out(shell, cradle, i)
        if gap < SURFACE_CLEARANCE:
            problems.append("%s 外壳 %s 面相对 core_cradle 只余 %.3f（< %.2f 会共面闪烁）"
                            % (variant, name, gap, SURFACE_CLEARANCE))

    # 3. 接缝在 housing 之外
    for i, name in enumerate(("x-", "x+", "y-", "y+")):
        gap = grow_out(rim, housing, i)
        if gap < SURFACE_CLEARANCE:
            problems.append("%s 接缝 %s 面相对 core_housing 只余 %.3f" % (variant, name, gap))

    # 4. 发光落在 reactor 的 x/y 之内
    for i, name in enumerate(("x-", "x+", "y-", "y+")):
        if grow_out(reactor, core, i) < 0.0:
            problems.append("%s 发光 %s 面超出 core_reactor" % (variant, name))

    # 5. 三层 z 从前往后依次是 发光 -> 接缝 -> 外壳
    if not (core[4] < core[5] <= rim[4] + 1e-6 and rim[5] <= shell[4] + 1e-6):
        problems.append("%s 三层 z 次序错乱：发光[%.2f..%.2f] 接缝[%.2f..%.2f] 外壳[%.2f..%.2f]"
                        % (variant, core[4], core[5], rim[4], rim[5], shell[4], shell[5]))
    if core[5] > reactor[4]:
        problems.append("%s 发光后端 %.2f 落在 core_reactor 前端 %.2f 之后，会被挡住"
                        % (variant, core[5], reactor[4]))

print("=" * 72)
if problems:
    for p in problems:
        print("FAIL:", p)
    sys.exit(1)
print("PASS: 三个型号的挂件都贴合各自核心槽（无共面 / 无撑破 / 层次正确 / 在胸口）")
