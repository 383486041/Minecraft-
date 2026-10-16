#!/usr/bin/env python3
"""离线复算装甲 OBJ 的几何契约（等价于 gradle verifyArmorJointBounds + verifySentinelDualFormMesh）。

为什么需要它：tools/build-mod.ps1 默认走 `remapJar` 快速增量，**不跑 `check`**，
所以 build.gradle 里那一长串 verify* 任务和 src/test 下的契约 main() 都不会执行。
改模型时必须先在这里过一遍，否则坏几何会静默进包。

用法： python tools/check_armor_contracts.py
退出码 0 = 全部通过；1 = 有违反。
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ARMOR_DIR = ROOT / "src/main/resources/assets/flight_armor/models/armor"
# 可选：第一个参数指定另一个 OBJ 目录（例如 .legacy-obj 基线对照）。
ARMOR_DIR = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else DEFAULT_ARMOR_DIR

VARIANTS = ("vanguard", "swift", "sentinel")


class Obj:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.groups: set[str] = set()
        self.verts: dict[str, list[tuple[float, float, float]]] = {}
        current: str | None = None
        for raw in path.read_text(encoding="utf-8").splitlines():
            if raw.startswith("g "):
                current = raw[2:].strip()
                self.groups.add(current)
                self.verts.setdefault(current, [])
            elif current is not None and raw.startswith("v "):
                parts = raw.split()
                self.verts[current].append(
                    (float(parts[1]), float(parts[2]), float(parts[3]))
                )

    def box(self, group: str):
        vs = self.verts.get(group)
        if not vs:
            return None
        xs = [v[0] for v in vs]
        ys = [v[1] for v in vs]
        zs = [v[2] for v in vs]
        return (min(xs), max(xs), min(ys), max(ys), min(zs), max(zs))


def load_all() -> dict[str, Obj]:
    return {v: Obj(ARMOR_DIR / f"flight_armor_{v}.obj") for v in VARIANTS}


# --------------------------------------------------------------------------
# SentinelVisualZone.fromGroup 的等价实现（必须与 java 版语义一致）
# --------------------------------------------------------------------------
def zone(name: str) -> str:
    if not name:
        return "NONE"
    if name in (
        "head_faceplate_gunmetal",
        "visor_lens_visor",
        "head_rear_left_gunmetal",
        "head_rear_right_gunmetal",
    ):
        return "HEAD"
    if "_sentinel_" not in name:
        return "NONE"
    if "sentinel_drone_pod" in name:
        return "DRONE_POD"
    if "sentinel_wing" in name:
        return "WING"
    if name.startswith("head_") or name.startswith("visor_"):
        return "HEAD"
    if name.startswith("body_") or name.startswith("core_"):
        return "TORSO"
    if name.startswith("arm_"):
        return "ARM"
    if name.startswith("leg_"):
        return "LEG"
    if name.startswith("boot_"):
        return "BOOT"
    return "NONE"


def main() -> int:
    fails: list[str] = []

    def check(ok: bool, message: str) -> None:
        if not ok:
            fails.append(message)

    objs = load_all()
    for variant, obj in objs.items():
        # ---------------- gradle: verifyArmorJointBounds ----------------
        for name in obj.groups:
            box = obj.box(name)
            if name.startswith(("arm_left_upper_", "arm_right_upper_")):
                check(box[3] <= 6.001, f"[{variant}] 上臂甲越过肘轴 Y=6: {name} max={box[3]}")
            if name.startswith(("arm_left_forearm_", "arm_right_forearm_")):
                check(box[2] >= 5.999, f"[{variant}] 前臂甲越过肘轴 Y=6: {name} min={box[2]}")

        coverage = {
            "body_underlayer_shadow": (-4.33, 4.33, -2.35, 2.35),
            "arm_left_upper_underlayer_shadow": (-1.35, 3.35, -2.35, 2.35),
            "arm_left_forearm_underlayer_shadow": (-1.35, 3.35, -2.35, 2.35),
            "arm_right_upper_underlayer_shadow": (-3.35, 1.35, -2.35, 2.35),
            "arm_right_forearm_underlayer_shadow": (-3.35, 1.35, -2.35, 2.35),
        }
        for side in ("left", "right"):
            coverage[f"leg_{side}_thigh_underlayer_shadow"] = (-2.35, 2.35, -2.35, 2.35)
            coverage[f"boot_{side}_underlayer_shadow"] = (-2.35, 2.35, -2.35, 2.35)
        for group, (min_x, max_x, min_z, max_z) in coverage.items():
            box = obj.box(group)
            if box is None:
                fails.append(f"[{variant}] 缺少立体软甲内衬: {group}")
                continue
            ok = box[0] <= min_x and box[1] >= max_x and box[4] <= min_z and box[5] >= max_z
            check(
                ok,
                f"[{variant}] 软甲内衬未覆盖皮肤第二层: {group} "
                f"X=[{box[0]},{box[1]}] Z=[{box[4]},{box[5]}] 需要 X<= {min_x}..{max_x} Z<= {min_z}..{max_z}",
            )

        for side in ("left", "right"):
            for joint in (
                f"arm_{side}_elbow_seal_shadow",
                f"leg_{side}_knee_seal_shadow",
            ):
                box = obj.box(joint)
                if box is None:
                    fails.append(f"[{variant}] 缺少肘膝软连接: {joint}")
                    continue
                check(
                    box[2] <= 5.75 and box[3] >= 6.25,
                    f"[{variant}] 肘膝软连接未跨过关节轴 Y=6: {joint} y=[{box[2]},{box[3]}]",
                )

        head_inner = obj.box("head_shell_shadow")
        if head_inner is None:
            fails.append(f"[{variant}] 缺少 head_shell_shadow")
        else:
            check(4.25 <= head_inner[5] <= 4.50,
                  f"[{variant}] 头部内衬后脑 Z 不在 [4.25,4.50]: {head_inner[5]}")

        faceplate = obj.box("head_faceplate_gunmetal")
        if faceplate is None:
            fails.append(f"[{variant}] 缺少 head_faceplate_gunmetal")
        else:
            check(faceplate[4] <= -4.50,
                  f"[{variant}] 面甲前向覆盖不足: minZ={faceplate[4]}")
            check(faceplate[5] <= -4.20,
                  f"[{variant}] 面甲内表退入玩家脸部: maxZ={faceplate[5]}")

        visor = obj.box("visor_lens_visor")
        if visor is None:
            fails.append(f"[{variant}] 缺少 visor_lens_visor")
        else:
            check(visor[5] <= -4.40, f"[{variant}] 面罩边缘退入玩家脸部: maxZ={visor[5]}")

        for name in ("head_rear_left_gunmetal", "head_rear_right_gunmetal"):
            box = obj.box(name)
            if box is None:
                fails.append(f"[{variant}] 缺少 {name}")
                continue
            check(box[4] >= 4.35, f"[{variant}] 后脑板覆盖不足: {name} minZ={box[4]}")

    # ---------------- java: SentinelDualFormMeshContractTest ----------------
    sentinel = objs["sentinel"]
    vanguard = objs["vanguard"]

    for group in (
        "head_faceplate_sentinel_crown_bastion_titanium",
        "body_sentinel_chest_bastion_titanium",
        "body_sentinel_abdominal_bulwark_titanium",
        "body_sentinel_waist_apron_titanium",
        "arm_left_sentinel_upper_bastion_titanium",
        "arm_right_sentinel_forearm_bulwark_titanium",
        "leg_left_sentinel_thigh_bastion_titanium",
        "leg_right_sentinel_knee_lock_titanium",
        "boot_left_sentinel_calf_bastion_titanium",
        "boot_right_sentinel_toe_ram_titanium",
        "core_reactor_sentinel_led",
    ):
        check(group in sentinel.groups, f"[sentinel] 丢失独立外壳组: {group}")

    weapon_parts = (
        "cover_outer_titanium",
        "cover_inner_titanium",
        "hand_shell_outer_titanium",
        "hand_shell_inner_titanium",
        "wrist_lock_gunmetal",
        "emitter_shutter_gunmetal",
        "emitter_track_led",
    )
    for part in weapon_parts:
        group = "arm_right_sentinel_forearm_" + part
        box = sentinel.box(group)
        if box is None:
            fails.append(f"[sentinel] 丢失右臂一体式武器壳组: {group}")
            continue
        check(box[2] >= 6.02, f"[sentinel] 武器壳越过肘轴: {group} minY={box[2]}")
        check(box[3] <= 12.40, f"[sentinel] 武器壳越出手部: {group} maxY={box[3]}")
        check(box[4] >= 2.50, f"[sentinel] 武器壳离开手背正 Z 侧: {group} minZ={box[4]}")
        check(zone(group) == "ARM", f"[sentinel] 武器壳未进入 ARM 分区: {group}")
        check(
            "arm_left_sentinel_forearm_" + part not in sentinel.groups,
            f"[sentinel] 重武器必须只在右臂: {part}",
        )
    check(
        "arm_right_sentinel_forearm_node_led" not in sentinel.groups,
        "[sentinel] 右臂仍保留已废弃的掌侧武器灯",
    )

    for expected, group in (
        ("HEAD", "head_faceplate_sentinel_crown_bastion_titanium"),
        ("HEAD", "head_faceplate_gunmetal"),
        ("HEAD", "visor_lens_visor"),
        ("HEAD", "head_rear_left_gunmetal"),
        ("HEAD", "head_rear_right_gunmetal"),
        ("TORSO", "body_sentinel_chest_bastion_titanium"),
        ("TORSO", "body_sentinel_waist_apron_titanium"),
        ("ARM", "arm_left_sentinel_upper_bastion_titanium"),
        ("ARM", "arm_right_sentinel_forearm_bulwark_titanium"),
        ("LEG", "leg_left_sentinel_thigh_bastion_titanium"),
        ("LEG", "leg_right_sentinel_knee_lock_titanium"),
        ("BOOT", "boot_left_sentinel_calf_bastion_titanium"),
        ("BOOT", "boot_right_sentinel_toe_ram_titanium"),
        ("WING", "vane_left_sentinel_wing_primary_shell_titanium"),
        ("DRONE_POD", "vane_right_sentinel_drone_pod_shell_titanium"),
    ):
        actual = zone(group)
        check(actual == expected,
              f"[sentinel] 分区不符: {group} expected={expected} actual={actual}")

    for forbidden in (
        "body_chest_titanium",
        "body_abdomen_titanium",
        "arm_left_upper_pauldron_titanium",
        "arm_right_forearm_plate_titanium",
        "leg_left_thigh_plate_titanium",
        "boot_right_calf_plate_titanium",
    ):
        check(forbidden not in sentinel.groups,
              f"[sentinel] 退化成可见的先锋外壳: {forbidden}")

    shared = sentinel.groups & vanguard.groups
    check(len(shared) <= 20,
          f"[sentinel] 与先锋共用可渲染组过多: {len(shared)} -> {sorted(shared)}")

    authored = sum(1 for n in sentinel.groups if "_sentinel_" in n)
    check(authored >= 90, f"[sentinel] 独立授权外壳组不足 90: {authored}")

    for side in ("left", "right"):
        for leaf, minimum in (
            ("root", 12.0),
            ("primary", 18.0),
            ("secondary", 20.0),
            ("tip", 22.0),
        ):
            group = f"vane_{side}_sentinel_wing_{leaf}_shell_titanium"
            box = sentinel.box(group)
            if box is None:
                fails.append(f"[sentinel] 缺少翼叶: {group}")
                continue
            span = box[3] - box[2]
            check(span >= minimum,
                  f"[sentinel] 翼叶过短: {group} span={span:.2f} 需要 >= {minimum}")
        for cannon in ("cannon_housing_titanium", "cannon_barrel_gunmetal", "cannon_muzzle_led"):
            group = f"vane_{side}_sentinel_wing_{cannon}"
            check(group in sentinel.groups, f"[sentinel] 缺少翼炮零件: {side}/{cannon}")

    # ---------------- 比例 pass 不变量（收腰 / 分腿） ----------------
    #
    # 这两条守的是「形体读数」，不是皮肤覆盖：甲板收进去、露出深色软层，读出腰和腿缝。
    # generate_armor_mesh.mjs 的 PROFILE_ZONES 一旦调松，这里会立刻报出来。
    LEG_OFFSET = 1.9  # 原版 HumanoidModel 的左右腿 x 偏移
    LEG_SEAM_MARGIN = 0.15  # 甲板内缘至少离中线这么远，两条腿才不会连成一片
    WAIST_MAX_HALF = 3.40  # 躯干腰段甲板半宽上限（软层 4.40 → 腰缝 >= 1.0）

    def is_soft(name: str) -> bool:
        """深色软层/关节软连接：皮肤覆盖由它们保证，比例 pass 不许动。"""
        return (
            "underlayer" in name
            or "_seal_" in name
            or "_shadow" in name
        )

    for variant, obj in objs.items():
        for side, sign in (("left", 1.0), ("right", -1.0)):
            depths: list[float] = []
            for name in obj.groups:
                if not name.startswith((f"leg_{side}", f"boot_{side}")):
                    continue
                if is_soft(name):
                    continue
                for x, _, _ in obj.verts[name]:
                    world = x + sign * LEG_OFFSET
                    depths.append(sign * world)
            if not depths:
                fails.append(f"[{variant}] {side} 腿没有可见甲板，无法判定腿缝")
                continue
            seam = min(depths)
            check(
                seam >= LEG_SEAM_MARGIN,
                f"[{variant}] {side} 腿甲板越过中线，腿缝消失: "
                f"内缘距中线 {seam:.3f} < {LEG_SEAM_MARGIN}",
            )

        waist = [
            abs(x)
            for name in obj.groups
            if name.startswith(("body_", "core_")) and not is_soft(name)
            for x, y, _ in obj.verts[name]
            if 5.5 <= y <= 9.0
        ]
        if not waist:
            fails.append(f"[{variant}] 躯干腰段没有可见甲板，无法判定收腰")
            continue
        half = max(waist)
        check(
            half <= WAIST_MAX_HALF,
            f"[{variant}] 腰段甲板过宽，收腰没生效: 半宽 {half:.2f} > {WAIST_MAX_HALF}",
        )

    # ---------------- 报告 ----------------
    if fails:
        print(f"FAIL: {len(fails)} 条契约违反\n")
        for line in fails:
            print("  - " + line)
        return 1
    print("PASS: 全部几何契约通过（gradle verifyArmorJointBounds + SentinelDualFormMeshContractTest）")
    for variant, obj in objs.items():
        print(f"  {variant}: {len(obj.groups)} groups")
    return 0


if __name__ == "__main__":
    sys.exit(main())
