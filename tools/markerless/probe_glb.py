"""Blender probe: list objects/actions of the walkthrough GLB (debug helper)."""
import sys
import bpy

glb = sys.argv[sys.argv.index("--") + 1]
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=glb)
print("ACTIONS", [(a.name, len(getattr(a, "slots", [])), tuple(a.frame_range)) for a in bpy.data.actions])
for o in bpy.data.objects:
    ad = o.animation_data
    print("OBJ", o.name, o.type, o.parent.name if o.parent else None,
          tuple(round(v, 4) for v in o.scale),
          ad.action.name if ad and ad.action else None,
          [(t.name, [s.action.name for s in t.strips]) for t in ad.nla_tracks] if ad else None)
print("fps", bpy.context.scene.render.fps)
