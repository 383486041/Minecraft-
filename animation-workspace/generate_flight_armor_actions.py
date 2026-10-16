import bpy
import json
import math
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT / "animation-workspace"
TEMPLATE = WORKSPACE / "bettercombat_animation_template.blend"
SOURCE = ROOT / "src/client/java/dev/codex/flightarmor/client/animation/AttackPoseController.java"
OUTPUT = WORKSPACE / "generated-actions"
EXPORTER = WORKSPACE / "export_player_animation_blender52.py"

FIELDS = (
    "bodyPitch", "bodyRoll", "headPitch", "headRoll",
    "attackArmPitch", "attackArmYaw", "attackArmRoll", "attackElbow",
    "supportArmPitch", "supportArmYaw", "supportArmRoll", "supportElbow",
    "attackLegPitch", "attackLegYaw", "attackLegRoll", "attackKnee",
    "supportLegPitch", "supportLegYaw", "supportLegRoll", "supportKnee",
)

PARTS = (
    "head", "torso", "rightArm", "leftArm", "rightLeg", "leftLeg",
    "rightArm_bend", "leftArm_bend", "rightLeg_bend", "leftLeg_bend",
)

FRAME_KEYS = ((8, 0), (11, 1), (14, 2))
BEAT_NAMES = ("LOAD", "HIT", "FOLLOW")


def method_block(text: str, name: str, next_name: str) -> str:
    start = text.index(f"private static BladeAngles {name}")
    match = re.search(
        rf"private (?:static )?BladeAngles {re.escape(next_name)}",
        text[start + 1:],
    )
    if not match:
        raise RuntimeError(f"cannot locate method following {name}: {next_name}")
    end = start + 1 + match.start()
    return text[start:end]


def parse_method(text: str, name: str, next_name: str):
    block = method_block(text, name, next_name)
    poses = []
    for raw in re.findall(r"new BladeAngles\((.*?)\)", block, flags=re.S):
        values = [
            float(token.rstrip("FfDd"))
            for token in re.findall(r"[-+]?(?:\d+\.\d+|\d+)(?:[FfDd])?", raw)
        ]
        if len(values) != len(FIELDS):
            raise RuntimeError(f"{name}: expected 20 values, got {len(values)}")
        poses.append(dict(zip(FIELDS, values)))
    if len(poses) != 9:
        raise RuntimeError(f"{name}: expected 9 authored poses, got {len(poses)}")
    return [poses[index:index + 3] for index in range(0, 9, 3)]


def parse_actions():
    text = SOURCE.read_text(encoding="utf-8")
    return {
        "vanguard": parse_method(text, "vanguardCombo", "sentinelHeavyCombo"),
        "sentinel": parse_method(text, "sentinelHeavyCombo", "swiftDualCombo"),
        "swift": parse_method(text, "swiftDualCombo", "stylize"),
    }


def radians(value):
    return math.radians(value)


def reset_parts():
    for name in PARTS:
        obj = bpy.data.objects[name]
        obj.rotation_euler = (0.0, 0.0, 0.0)


def assign_rotation(name, pitch, yaw, roll, frame):
    obj = bpy.data.objects[name]
    # Better Combat template uses XZY. Its exporter negates player-part rotation.
    obj.rotation_euler.x = radians(-pitch)
    obj.rotation_euler.y = radians(-roll)
    obj.rotation_euler.z = radians(-yaw)
    obj.keyframe_insert(data_path="rotation_euler", frame=frame, group=name)


def assign_torso(pitch, roll, frame):
    obj = bpy.data.objects["torso"]
    # Torso pitch is the only exported player rotation that is not negated.
    obj.rotation_euler.x = radians(pitch)
    obj.rotation_euler.y = radians(-roll)
    obj.rotation_euler.z = 0.0
    obj.keyframe_insert(data_path="rotation_euler", frame=frame, group="torso")


def assign_bend(name, degrees, frame):
    obj = bpy.data.objects[name]
    obj.rotation_euler.x = radians(-degrees)
    obj.rotation_euler.y = 0.0
    obj.rotation_euler.z = 0.0
    obj.keyframe_insert(data_path="rotation_euler", frame=frame, group=name)


def set_pose(pose, frame, attacking_left):
    side = -1.0 if attacking_left else 1.0
    attack_arm = "leftArm" if attacking_left else "rightArm"
    support_arm = "rightArm" if attacking_left else "leftArm"
    attack_leg = "leftLeg" if attacking_left else "rightLeg"
    support_leg = "rightLeg" if attacking_left else "leftLeg"

    assign_torso(pose["bodyPitch"], pose["bodyRoll"] * side, frame)
    assign_rotation("head", pose["headPitch"], 0.0, pose["headRoll"] * -side, frame)
    assign_rotation(
        attack_arm,
        pose["attackArmPitch"],
        pose["attackArmYaw"] * side,
        pose["attackArmRoll"] * side,
        frame,
    )
    assign_rotation(
        support_arm,
        pose["supportArmPitch"],
        pose["supportArmYaw"] * -side,
        pose["supportArmRoll"] * -side,
        frame,
    )
    assign_rotation(
        attack_leg,
        pose["attackLegPitch"],
        pose["attackLegYaw"] * side,
        pose["attackLegRoll"] * side,
        frame,
    )
    assign_rotation(
        support_leg,
        pose["supportLegPitch"],
        pose["supportLegYaw"] * -side,
        pose["supportLegRoll"] * -side,
        frame,
    )
    assign_bend(f"{attack_arm}_bend", pose["attackElbow"], frame)
    assign_bend(f"{support_arm}_bend", pose["supportElbow"], frame)
    assign_bend(f"{attack_leg}_bend", pose["attackKnee"], frame)
    assign_bend(f"{support_leg}_bend", pose["supportKnee"], frame)


def all_fcurves(action):
    if hasattr(action, "fcurves"):
        return list(action.fcurves)
    curves = []
    for layer in action.layers:
        for strip in layer.strips:
            for channelbag in strip.channelbags:
                curves.extend(channelbag.fcurves)
    return curves


def style_keys():
    for obj in bpy.data.objects:
        action = obj.animation_data.action if obj.animation_data else None
        if not action:
            continue
        for curve in all_fcurves(action):
            for key in curve.keyframe_points:
                frame = round(key.co.x)
                key.interpolation = "BEZIER"
                key.handle_left_type = "AUTO_CLAMPED"
                key.handle_right_type = "AUTO_CLAMPED"
                if frame == 11:
                    key.easing = "EASE_IN"
                elif frame == 14:
                    key.easing = "EASE_OUT"
                else:
                    key.easing = "AUTO"


def add_markers():
    scene = bpy.context.scene
    scene.timeline_markers.clear()
    scene.timeline_markers.new("LOAD", frame=8)
    scene.timeline_markers.new("HIT", frame=11)
    scene.timeline_markers.new("FOLLOW", frame=14)
    scene.timeline_markers.new("END", frame=20)


def install_blender52_exporter():
    legacy = bpy.data.texts.get("Export emote")
    if legacy:
        bpy.data.texts.remove(legacy)
    exporter = bpy.data.texts.get("Export Player Animation 5.2")
    if exporter:
        bpy.data.texts.remove(exporter)
    exporter = bpy.data.texts.new("Export Player Animation 5.2")
    exporter.write(EXPORTER.read_text(encoding="utf-8"))

def moves_for_pose(pose, tick, attacking_left, easing):
    side = -1.0 if attacking_left else 1.0
    attack_arm = "leftArm" if attacking_left else "rightArm"
    support_arm = "rightArm" if attacking_left else "leftArm"
    attack_leg = "leftLeg" if attacking_left else "rightLeg"
    support_leg = "rightLeg" if attacking_left else "leftLeg"
    values = {
        "torso": {
            "pitch": pose["bodyPitch"],
            "roll": pose["bodyRoll"] * side,
        },
        "head": {
            "pitch": pose["headPitch"],
            "roll": pose["headRoll"] * -side,
        },
        attack_arm: {
            "pitch": pose["attackArmPitch"],
            "yaw": pose["attackArmYaw"] * side,
            "roll": pose["attackArmRoll"] * side,
            "bend": pose["attackElbow"],
        },
        support_arm: {
            "pitch": pose["supportArmPitch"],
            "yaw": pose["supportArmYaw"] * -side,
            "roll": pose["supportArmRoll"] * -side,
            "bend": pose["supportElbow"],
        },
        attack_leg: {
            "pitch": pose["attackLegPitch"],
            "yaw": pose["attackLegYaw"] * side,
            "roll": pose["attackLegRoll"] * side,
            "bend": pose["attackKnee"],
        },
        support_leg: {
            "pitch": pose["supportLegPitch"],
            "yaw": pose["supportLegYaw"] * -side,
            "roll": pose["supportLegRoll"] * -side,
            "bend": pose["supportKnee"],
        },
    }
    moves = []
    for part, channels in values.items():
        for channel, value in channels.items():
            moves.append(
                {
                    "tick": tick,
                    "easing": easing,
                    "turn": 0,
                    part: {channel: math.radians(value)},
                }
            )
    return moves


def export_json(name, poses, attacking_left):
    moves = []
    for (frame, pose_index), easing in zip(
        FRAME_KEYS, ("EASEINOUTQUAD", "EASEINQUAD", "EASEOUTQUAD")
    ):
        moves.extend(moves_for_pose(poses[pose_index], frame, attacking_left, easing))
    neutral = {field: 0.0 for field in FIELDS}
    moves.extend(moves_for_pose(neutral, 20, attacking_left, "EASEINOUTQUAD"))
    payload = {
        "name": name,
        "author": "Flight Armor",
        "description": "Generated Blender source converted from Flight Armor combat poses",
        "emote": {
            "isLoop": "false",
            "returnTick": 0,
            "beginTick": 0,
            "endTick": 20,
            "stopTick": 30,
            "degrees": False,
            "moves": moves,
        },
    }
    (OUTPUT / f"{name}.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def create_file(variant, step, poses):
    bpy.ops.wm.open_mainfile(filepath=str(TEMPLATE))
    scene = bpy.context.scene
    scene.frame_start = 0
    scene.frame_end = 30
    scene.render.fps = 20
    # Blender 5.2 converts the template's old actions into read-only Legacy Slots.
    # Detach them and let keyframe_insert create native editable Action slots.
    scene.frame_set(0)
    reset_parts()
    for part_name in PARTS:
        part = bpy.data.objects[part_name]
        part.animation_data_clear()
        part.keyframe_insert(data_path="rotation_euler", frame=0, group=part_name)
        if part.animation_data and part.animation_data.action:
            part.animation_data.action.name = f"{variant}_combo_{step + 1}_{part_name}"
    attacking_left = variant == "swift" and step == 1
    for frame, pose_index in FRAME_KEYS:
        scene.frame_set(frame)
        set_pose(poses[pose_index], frame, attacking_left)
    scene.frame_set(20)
    reset_parts()
    for name in PARTS:
        bpy.data.objects[name].keyframe_insert(
            data_path="rotation_euler", frame=20, group=name
        )
    style_keys()
    add_markers()
    install_blender52_exporter()
    name = f"flight_armor_{variant}_combo_{step + 1}"
    scene["flight_armor_variant"] = variant
    scene["flight_armor_combo_step"] = step + 1
    scene["flight_armor_hit_frame"] = 11
    scene["flight_armor_upswing"] = 0.55
    scene.frame_set(8)
    bpy.ops.wm.save_as_mainfile(filepath=str(OUTPUT / f"{name}.blend"))
    export_json(name, poses, attacking_left)
    return name


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    actions = parse_actions()
    created = []
    for variant, combos in actions.items():
        for step, poses in enumerate(combos):
            created.append(create_file(variant, step, poses))
    (OUTPUT / "README.txt").write_text(
        "Flight Armor Blender actions\n"
        "Blender: 5.2 compatible\n"
        "Frames: LOAD=8, HIT=11, FOLLOW=14, END=20, STOP=30\n"
        "Open any .blend file and use the Animation workspace.\n\n"
        + "\n".join(created)
        + "\n",
        encoding="utf-8",
    )
    print("GENERATED", len(created), "actions in", OUTPUT)


main()
