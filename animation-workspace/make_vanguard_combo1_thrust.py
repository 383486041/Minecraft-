import bpy
import math


PARTS = (
    "head", "torso", "rightArm", "leftArm", "rightLeg", "leftLeg",
    "rightArm_bend", "leftArm_bend", "rightLeg_bend", "leftLeg_bend",
)


def rad(value):
    return math.radians(value)


def rotation(name, pitch, yaw, roll, frame):
    obj = bpy.data.objects[name]
    obj.rotation_euler.x = rad(-pitch)
    obj.rotation_euler.y = rad(-roll)
    obj.rotation_euler.z = rad(-yaw)
    obj.keyframe_insert(data_path="rotation_euler", frame=frame, group=name)


def torso(pitch, roll, frame):
    obj = bpy.data.objects["torso"]
    obj.rotation_euler.x = rad(pitch)
    obj.rotation_euler.y = rad(-roll)
    obj.rotation_euler.z = 0.0
    obj.keyframe_insert(data_path="rotation_euler", frame=frame, group="torso")


def bend(name, value, frame):
    obj = bpy.data.objects[name]
    obj.rotation_euler.x = rad(-value)
    obj.rotation_euler.y = 0.0
    obj.rotation_euler.z = 0.0
    obj.keyframe_insert(data_path="rotation_euler", frame=frame, group=name)


def pose(frame, values):
    torso(values["body"][0], values["body"][1], frame)
    rotation("head", *values["head"], frame)
    rotation("rightArm", *values["rightArm"], frame)
    rotation("leftArm", *values["leftArm"], frame)
    rotation("rightLeg", *values["rightLeg"], frame)
    rotation("leftLeg", *values["leftLeg"], frame)
    bend("rightArm_bend", values["rightElbow"], frame)
    bend("leftArm_bend", values["leftElbow"], frame)
    bend("rightLeg_bend", values["rightKnee"], frame)
    bend("leftLeg_bend", values["leftKnee"], frame)


THRUST = {
    8: {
        "body": (-5, -8),
        "head": (-2, 0, 2),
        "rightArm": (-46, 18, 8),
        "leftArm": (-48, -28, -18),
        "rightLeg": (-15, 4, 5),
        "leftLeg": (10, -3, -4),
        "rightElbow": 82,
        "leftElbow": 55,
        "rightKnee": 38,
        "leftKnee": 24,
    },
    10: {
        "body": (-11, -3),
        "head": (-4, 0, 1),
        "rightArm": (-72, 8, 4),
        "leftArm": (-57, -5, -4),
        "rightLeg": (6, -2, -3),
        "leftLeg": (-12, 3, 4),
        "rightElbow": 38,
        "leftElbow": 48,
        "rightKnee": 24,
        "leftKnee": 39,
    },
    11: {
        "body": (-17, 2),
        "head": (-5, 0, -1),
        "rightArm": (-94, -3, 0),
        "leftArm": (-62, 20, 16),
        "rightLeg": (20, -5, -6),
        "leftLeg": (-22, 4, 6),
        "rightElbow": 8,
        "leftElbow": 42,
        "rightKnee": 18,
        "leftKnee": 48,
    },
    14: {
        # Keep the weapon on the frame-11 thrust axis. The follow-through is
        # driven by the torso and legs instead of lifting/twisting the arm
        # across the character's face.
        "body": (-19, 2),
        "head": (-5, 0, -1),
        "rightArm": (-94, -3, 0),
        "leftArm": (-58, 18, 13),
        "rightLeg": (24, -4, -5),
        "leftLeg": (-15, 5, 7),
        "rightElbow": 0,
        "leftElbow": 40,
        "rightKnee": 21,
        "leftKnee": 43,
    },
}


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


for frame, values in THRUST.items():
    bpy.context.scene.frame_set(frame)
    pose(frame, values)

for name in PARTS:
    action = bpy.data.objects[name].animation_data.action
    for curve in fcurves(action):
        for key in curve.keyframe_points:
            key.handle_left_type = "AUTO_CLAMPED"
            key.handle_right_type = "AUTO_CLAMPED"
            current = round(key.co.x)
            if current == 10:
                key.interpolation = "LINEAR"
            elif current == 11:
                key.interpolation = "BEZIER"
                key.easing = "EASE_IN"
            elif current == 14:
                key.interpolation = "BEZIER"
                key.easing = "EASE_OUT"

scene = bpy.context.scene
marker = scene.timeline_markers.get("DRIVE")
if marker:
    scene.timeline_markers.remove(marker)
scene.timeline_markers.new("DRIVE", frame=10)
scene.timeline_markers["HIT"].frame = 11
scene["flight_armor_attack_style"] = "right_arm_dorsal_thrust"
scene["flight_armor_hit_frame"] = 11
scene["flight_armor_upswing"] = 0.55
scene.frame_set(8)
bpy.ops.wm.save_as_mainfile(filepath=bpy.data.filepath)

exporter = bpy.data.texts.get("Export Player Animation 5.2")
if not exporter:
    raise RuntimeError("Blender 5.2 exporter is missing")
exec(exporter.as_string())
print("UPDATED Vanguard combo 1 to a right-arm thrust")
