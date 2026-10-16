#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线装甲网格预览器。

把 resources/assets/flight_armor/models/armor/*.obj 按原版 HumanoidModel 的部件
偏移装配成完整人形，再做正交多视角渲染，输出 PNG。

分组配色与 FlightObjArmorRenderer#colorFor / variantBaseColor 保持一致，
因此这张图能代表游戏里看到的装甲样式（不含动画、特效和自发光叠加）。

用法：
    python tools/preview_armor_mesh.py                    # 全部变体
    python tools/preview_armor_mesh.py --variant swift
    python tools/preview_armor_mesh.py --out dir --no-outline
"""

from __future__ import annotations

import argparse
import math
import os
import sys
from dataclasses import dataclass

from PIL import Image, ImageDraw

PROJECT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(
    PROJECT_DIR, "src", "main", "resources", "assets", "flight_armor", "models", "armor"
)

VARIANTS = {
    "vanguard": "flight_armor_vanguard.obj",
    "swift": "flight_armor_swift.obj",
    "sentinel": "flight_armor_sentinel.obj",
}

# 与 FlightObjArmorRenderer 的静态色板一致。
TITANIUM = (166, 182, 190)
LIGHT_TITANIUM = (198, 211, 216)
GUNMETAL = (55, 70, 79)
SHADOW = (24, 32, 38)
ACCENT = (92, 115, 126)
VISOR = (42, 73, 88)
LED = (80, 226, 255)

# 变体整体调色（variantBaseColor）。
VARIANT_TINT = {
    "swift": {"dark": (34, 26, 53), "light": (135, 115, 170), "dark_mix": 0.66, "light_mix": 0.56},
    "sentinel": {"dark": (24, 32, 40), "light": (76, 94, 108), "dark_mix": 0.84, "light_mix": 0.78},
}

# 部件局部坐标 -> 整体人形坐标（原版 HumanoidModel 的 PartPose.offset）。
# 模型空间：+X 为模型左侧、+Y 向下、-Z 为正面。
PART_OFFSETS = (
    ("arm_left_", (5.0, 2.0, 0.0)),
    ("arm_right_", (-5.0, 2.0, 0.0)),
    ("leg_left_", (1.9, 12.0, 0.0)),
    ("leg_right_", (-1.9, 12.0, 0.0)),
    ("boot_left_", (1.9, 12.0, 0.0)),
    ("boot_right_", (-1.9, 12.0, 0.0)),
)


@dataclass
class Face:
    points: list[tuple[float, float, float]]
    color: tuple[int, int, int]
    emissive: bool


def is_led(name: str) -> bool:
    return (
        name.endswith("_led")
        or "_led_" in name
        or name.startswith("led_")
        or "emissive" in name
        or "_glow" in name
        or name.startswith("glow_")
    )


def color_for(name: str) -> tuple[int, int, int]:
    if is_led(name):
        return LED
    if "visor" in name or "glass" in name:
        return VISOR
    if "shadow" in name or "under" in name or "black" in name:
        return SHADOW
    if "dark" in name or "gunmetal" in name or "frame" in name or "joint" in name:
        return GUNMETAL
    if "accent" in name or "trim" in name:
        return ACCENT
    if "light" in name or "silver" in name:
        return LIGHT_TITANIUM
    return TITANIUM


def is_dark_name(name: str) -> bool:
    return any(
        token in name
        for token in ("shadow", "under", "black", "dark", "gunmetal", "frame", "joint")
    )


def mix(a, b, t: float):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def apply_variant_tint(variant: str, name: str, base: tuple[int, int, int]):
    tint = VARIANT_TINT.get(variant)
    if tint is None:
        return base
    dark = is_dark_name(name)
    target = tint["dark"] if dark else tint["light"]
    strength = tint["dark_mix"] if dark else tint["light_mix"]
    return mix(base, target, strength)


def part_offset(name: str):
    for prefix, offset in PART_OFFSETS:
        if name.startswith(prefix):
            return offset
    return (0.0, 0.0, 0.0)


def parse_obj(path: str, variant: str) -> list[Face]:
    faces: list[Face] = []
    vertices: list[tuple[float, float, float]] = []
    group_vertices: list[list[int]] = []
    offset = (0.0, 0.0, 0.0)
    color = TITANIUM
    emissive = False

    with open(path, "r", encoding="utf-8") as handle:
        for raw in handle:
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            tokens = line.split()
            tag = tokens[0]
            if tag == "v":
                vertices.append((float(tokens[1]), float(tokens[2]), float(tokens[3])))
            elif tag in ("g", "o"):
                name = tokens[1].lower() if len(tokens) > 1 else ""
                offset = part_offset(name)
                color = apply_variant_tint(variant, name, color_for(name))
                emissive = is_led(name)
                group_vertices = []
            elif tag == "f":
                indices = [int(token.split("/")[0]) - 1 for token in tokens[1:]]
                points = []
                for index in indices:
                    if index < 0 or index >= len(vertices):
                        raise ValueError(f"顶点索引越界: {path} {index}")
                    base = vertices[index]
                    points.append(
                        (base[0] + offset[0], base[1] + offset[1], base[2] + offset[2])
                    )
                if len(points) < 3:
                    continue
                faces.append(Face(points, color, emissive))
    return faces


VIEWS = (
    ("front", 0.0, 0.0),
    ("front34", 34.0, 6.0),
    ("side", 90.0, 0.0),
    ("back", 180.0, 4.0),
)

WIDTH = 340
HEIGHT = 430
MODEL_TOP = -8.6
MODEL_BOTTOM = 24.4


def render_view(faces: list[Face], yaw: float, pitch: float, outline: bool) -> Image.Image:
    image = Image.new("RGB", (WIDTH, HEIGHT), (238, 240, 243))
    draw = ImageDraw.Draw(image)

    scale = (HEIGHT * 0.90) / (MODEL_BOTTOM - MODEL_TOP)
    center_z = 0.0
    mid_y = (MODEL_TOP + MODEL_BOTTOM) * 0.5

    yaw_rad = math.radians(yaw)
    pitch_rad = math.radians(pitch)
    cos_y, sin_y = math.cos(yaw_rad), math.sin(yaw_rad)
    cos_p, sin_p = math.cos(pitch_rad), math.sin(pitch_rad)

    # 视图空间光方向：左上前。
    light = (-0.42, -0.62, -0.66)
    length = math.sqrt(sum(component * component for component in light))
    light = tuple(component / length for component in light)

    def project(point):
        x = point[0]
        y = point[1] - mid_y
        z = point[2] - center_z
        x1 = x * cos_y + z * sin_y
        z1 = -x * sin_y + z * cos_y
        y2 = y * cos_p - z1 * sin_p
        z2 = y * sin_p + z1 * cos_p
        return (
            WIDTH * 0.5 + x1 * scale,
            HEIGHT * 0.5 + y2 * scale,
            z2,
            (x1, y2, z2),
        )

    projected = []
    for face in faces:
        pts = [project(point) for point in face.points]
        depth = sum(entry[2] for entry in pts) / len(pts)
        projected.append((depth, pts, face))

    # 画家算法：远面先画。
    projected.sort(key=lambda entry: -entry[0])

    for _, pts, face in projected:
        if len(pts) < 3:
            continue
        view_points = [entry[3] for entry in pts]
        edge1 = tuple(view_points[1][i] - view_points[0][i] for i in range(3))
        edge2 = tuple(view_points[2][i] - view_points[0][i] for i in range(3))
        normal = (
            edge1[1] * edge2[2] - edge1[2] * edge2[1],
            edge1[2] * edge2[0] - edge1[0] * edge2[2],
            edge1[0] * edge2[1] - edge1[1] * edge2[0],
        )
        normal_length = math.sqrt(sum(component * component for component in normal))
        if normal_length < 1e-9:
            continue
        normal = tuple(component / normal_length for component in normal)

        if face.emissive:
            shade = 1.0
        else:
            lambert = abs(sum(normal[i] * light[i] for i in range(3)))
            shade = 0.34 + 0.72 * lambert

        color = tuple(min(255, max(0, round(channel * shade))) for channel in face.color)
        polygon = [(entry[0], entry[1]) for entry in pts]
        if outline:
            draw.polygon(polygon, fill=color, outline=tuple(round(c * 0.72) for c in color))
        else:
            draw.polygon(polygon, fill=color)

    return image


def build_sheet(faces: list[Face], title: str, outline: bool) -> Image.Image:
    columns = 2
    rows = 2
    sheet = Image.new("RGB", (WIDTH * columns, HEIGHT * rows + 30), (250, 250, 252))
    draw = ImageDraw.Draw(sheet)
    draw.text((10, 8), title, fill=(30, 34, 40))

    for index, (name, yaw, pitch) in enumerate(VIEWS):
        column = index % columns
        row = index // columns
        view = render_view(faces, yaw, pitch, outline)
        sheet.paste(view, (column * WIDTH, 30 + row * HEIGHT))
        draw.text((column * WIDTH + 8, 30 + row * HEIGHT + 6), name, fill=(90, 98, 108))

    return sheet


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", default="all", choices=["all", *VARIANTS.keys()])
    parser.add_argument("--out", default=os.path.join(PROJECT_DIR, "tools", "preview"))
    # 和 check_armor_contracts.py 一样支持指定 OBJ 目录，方便对 A/B 两份几何出图比对。
    parser.add_argument("--model-dir", default=MODEL_DIR)
    parser.add_argument("--no-outline", action="store_true")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    variants = list(VARIANTS) if args.variant == "all" else [args.variant]
    outline = not args.no_outline

    for variant in variants:
        path = os.path.join(args.model_dir, VARIANTS[variant])
        faces = parse_obj(path, variant)
        sheet = build_sheet(faces, f"{variant}  ({len(faces)} faces)", outline)
        target = os.path.join(args.out, f"{variant}_views.png")
        sheet.save(target)
        print(f"{variant}: {len(faces)} faces -> {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
