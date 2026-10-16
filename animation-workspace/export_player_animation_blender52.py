import bpy
import json
import math
from pathlib import Path


def all_fcurves(action):
    if hasattr(action, "fcurves"):
        return list(action.fcurves)
    return [
        curve
        for layer in action.layers
        for strip in layer.strips
        for channelbag in strip.channelbags
        for curve in channelbag.fcurves
    ]


def easing_name(key):
    if key.interpolation == "CONSTANT":
        return "CONSTANT"
    if key.interpolation == "LINEAR":
        return "LINEAR"
    easing = "EASEINOUT" if key.easing == "AUTO" else key.easing.replace("_", "")
    if key.interpolation == "BEZIER":
        return easing + "QUAD"
    return easing + key.interpolation


def converted_value(name, channel, value, is_location, is_item):
    if name == "head" and channel == "y":
        value -= 3
    if is_location:
        if name != "torso":
            value *= 4
            if not is_item:
                value *= -1
        else:
            value *= 0.25
            if channel == "z":
                value *= -1
    elif not is_item and not (
        name == "torso" and channel not in {"roll", "bend"}
    ):
        value *= -1
    if channel == "y":
        if name in {"rightLeg", "leftLeg", "rightArm", "leftArm"}:
            value += 12
    if channel in {"z", "x"}:
        if name == "rightLeg":
            value += 0.1
        elif name == "leftLeg":
            value -= 0.1
    return value


def channel_name(curve, is_item):
    if curve.data_path == "location":
        if curve.array_index == 0:
            return "x"
        if (curve.array_index == 1) != is_item:
            return "z"
        return "y"
    if curve.array_index == 0:
        return "pitch"
    if (curve.array_index == 1) != is_item:
        return "roll"
    return "yaw"


def part_moves(name, is_item=False):
    obj = bpy.data.objects.get(name)
    action = obj.animation_data.action if obj and obj.animation_data else None
    if not action:
        return []
    moves = []
    for curve in all_fcurves(action):
        if curve.data_path not in {"location", "rotation_euler"}:
            continue
        is_location = curve.data_path == "location"
        channel = channel_name(curve, is_item)
        for key in curve.keyframe_points:
            tick = round(key.co.x)
            if tick == 0:
                continue
            value = converted_value(name, channel, key.co.y, is_location, is_item)
            moves.append(
                {
                    "tick": tick,
                    "easing": easing_name(key),
                    "turn": 0,
                    name: {channel: value},
                }
            )
    if name in {"head", "leftItem", "rightItem"}:
        return moves
    bend = bpy.data.objects.get(name + "_bend")
    bend_action = bend.animation_data.action if bend and bend.animation_data else None
    if bend_action:
        for curve in all_fcurves(bend_action):
            if curve.data_path != "rotation_euler" or curve.array_index != 0:
                continue
            for key in curve.keyframe_points:
                tick = round(key.co.x)
                if tick == 0:
                    continue
                value = converted_value(name, "bend", key.co.y, False, False)
                moves.append(
                    {
                        "tick": tick,
                        "easing": easing_name(key),
                        "turn": 0,
                        name: {"bend": value},
                    }
                )
    return moves


scene = bpy.context.scene
moves = []
for part in ("head", "torso", "rightArm", "leftArm", "rightLeg", "leftLeg"):
    moves.extend(part_moves(part))
for item in ("rightItem", "leftItem"):
    moves.extend(part_moves(item, True))
moves.sort(key=lambda move: move["tick"])

name = Path(bpy.data.filepath).stem
payload = {
    "name": name,
    "author": "Flight Armor",
    "description": f"{scene.get('flight_armor_variant', 'armor')} combo "
                   f"{scene.get('flight_armor_combo_step', 1)}",
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
output = Path(bpy.data.filepath).with_suffix(".json")
output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"Exported Player Animator JSON: {output}")
