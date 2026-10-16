import bpy

print("SCENE", bpy.context.scene.name)
print("FRAMES", bpy.context.scene.frame_start, bpy.context.scene.frame_end, bpy.context.scene.render.fps)

for obj in bpy.data.objects:
    if obj.type not in {"MESH", "EMPTY"}:
        continue
    action = obj.animation_data.action if obj.animation_data else None
    print(
        "OBJECT",
        obj.name,
        "parent=", obj.parent.name if obj.parent else None,
        "location=", tuple(round(v, 5) for v in obj.location),
        "rotation_mode=", obj.rotation_mode,
        "rotation=", tuple(round(v, 5) for v in obj.rotation_euler),
        "action=", action.name if action else None,
    )
    if action:
        print("  ACTION", action.name, "range=", tuple(action.frame_range))
        for curve in action.fcurves:
            keys = [
                (
                    round(point.co.x, 4),
                    round(point.co.y, 6),
                    point.interpolation,
                    point.easing,
                )
                for point in curve.keyframe_points
            ]
            print("   ", curve.data_path, curve.array_index, keys)

print("TEXTS", [text.name for text in bpy.data.texts])
for text in bpy.data.texts:
    print("TEXT", text.name, "lines=", len(text.lines))
    print(text.as_string()[:2000])
