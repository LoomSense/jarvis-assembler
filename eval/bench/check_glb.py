"""python check_glb.py FILE.glb -> prints animation names/channel counts from the GLB JSON chunk."""
import json, struct, sys
b = open(sys.argv[1], "rb").read()
magic, ver, length = struct.unpack("<4sII", b[:12]); clen, ctype = struct.unpack("<I4s", b[12:20])
assert magic == b"glTF" and ctype == b"JSON"
j = json.loads(b[20:20 + clen])
anims = j.get("animations", [])
print(json.dumps({"bytes": length, "nodes": len(j["nodes"]), "meshes": len(j["meshes"]), "n_animations": len(anims),
                  "animations": {a["name"]: len(a["channels"]) for a in anims}}))
