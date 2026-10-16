import bpy
from pathlib import Path


root = Path(__file__).resolve().parent / "generated-actions"
required_parts = (
    "head", "torso", "rightArm", "leftArm", "rightLeg", "leftLeg",
    "rightArm_bend", "leftArm_bend", "rightLeg_bend", "leftLeg_bend",
)
required_frames = {0, 8, 11, 14, 20}


def fcurves(action):
    if hasattr(action, "fcurves"):
        return list(action.fcurves)
    return [
        curve
        for layer in action.layers
        for strip in layer.strips
        for channelbag in strip.channelbags
        for curve in channelbag.fcurves
    ]


errors = []
for blend in sorted(root.glob("flight_armor_*.blend")):
    bpy.ops.wm.open_mainfile(filepath=str(blend))
    for name in required_parts:
        obj = bpy.data.objects.get(name)
        action = obj.animation_data.action if obj and obj.animation_data else None
        if not action:
            errors.append(f"{blend.name}: {name} has no action")
            continue
        frames = {
            round(point.co.x)
            for curve in fcurves(action)
            if curve.data_path == "rotation_euler"
            for point in curve.keyframe_points
        }
        if not required_frames.issubset(frames):
            errors.append(
                f"{blend.name}: {name} frames={sorted(frames)}, "
                f"missing={sorted(required_frames - frames)}"
            )
    json_file = blend.with_suffix(".json")
    if not json_file.exists() or json_file.stat().st_size < 1000:
        errors.append(f"{blend.name}: missing or empty JSON")

if errors:
    print("VALIDATION FAILED")
    for error in errors:
        print(error)
    raise SystemExit(1)

print("VALIDATED", len(list(root.glob("flight_armor_*.blend"))), "Blender actions")
