using System;
using System.Collections.Generic;
using System.Text;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace AG.Guide
{
    /// <summary>Local TRS of one glTF node, already in Unity's frame (glTFast's conversion: X negated).</summary>
    public struct NodeTrs
    {
        public Vector3 Position;
        public Quaternion Rotation;
        public Vector3 Scale;
        public static NodeTrs Identity => new NodeTrs { Position = Vector3.zero, Rotation = Quaternion.identity, Scale = Vector3.one };
    }

    /// <summary>
    /// Reads a GLB's node rest transforms and the LAST keyframe of one animation straight from the glTF samplers
    /// (no Animation component, no frame stepping, no glTFast needed). This is how the guide gets each part's final
    /// transform: parts rest hidden at scale 1e-4 and only reach their place at the end of the <c>walkthrough</c> clip.
    /// Pure, any thread. Handles float accessors, LINEAR/STEP/CUBICSPLINE, interleaved buffer views.
    /// </summary>
    public sealed class GlbAnimation
    {
        public readonly Dictionary<string, NodeTrs> Rest = new Dictionary<string, NodeTrs>();
        /// <summary>Node name -> rest TRS overridden by the animation's last keyframe for every animated path.</summary>
        public readonly Dictionary<string, NodeTrs> Final = new Dictionary<string, NodeTrs>();
        /// <summary>Node name -> parent node name (null for scene roots).</summary>
        public readonly Dictionary<string, string> Parent = new Dictionary<string, string>();
        public readonly List<string> AnimationNames = new List<string>();
        public float DurationS;
        public bool UsesMeshopt;

        public static GlbAnimation Parse(byte[] glb, string clip)
        {
            if (glb == null || glb.Length < 20 || BitConverter.ToUInt32(glb, 0) != 0x46546C67) throw new FormatException("not a GLB");
            int jsonLen = BitConverter.ToInt32(glb, 12);
            var json = JObject.Parse(Encoding.UTF8.GetString(glb, 20, jsonLen));
            int binOff = 20 + jsonLen;
            ArraySegment<byte> bin = default;
            if (binOff + 8 <= glb.Length)
            {
                int binLen = BitConverter.ToInt32(glb, binOff);
                bin = new ArraySegment<byte>(glb, binOff + 8, Math.Min(binLen, glb.Length - binOff - 8));
            }
            var r = new GlbAnimation();
            var used = json["extensionsUsed"] as JArray;
            r.UsesMeshopt = used != null && used.ToString().Contains("meshopt");

            var nodes = (JArray)json["nodes"] ?? new JArray();
            var names = new string[nodes.Count];
            for (int i = 0; i < nodes.Count; i++)
            {
                var n = (JObject)nodes[i];
                names[i] = (string)n["name"] ?? "node_" + i;
                var trs = NodeTrs.Identity;
                if (n["translation"] is JArray t) trs.Position = FrameConvert.GltfPointToUnity((float)t[0], (float)t[1], (float)t[2]);
                if (n["rotation"] is JArray q) trs.Rotation = FrameConvert.GltfRotationToUnity((float)q[0], (float)q[1], (float)q[2], (float)q[3]);
                if (n["scale"] is JArray s) trs.Scale = new Vector3((float)s[0], (float)s[1], (float)s[2]);
                r.Rest[names[i]] = trs;
            }
            for (int i = 0; i < nodes.Count; i++)
                if (nodes[i]["children"] is JArray ch)
                    foreach (var c in ch) r.Parent[names[(int)c]] = names[i];
            foreach (var n in names) if (!r.Parent.ContainsKey(n)) r.Parent[n] = null;
            foreach (var kv in r.Rest) r.Final[kv.Key] = kv.Value;

            var anims = (JArray)json["animations"] ?? new JArray();
            JObject anim = null;
            foreach (JObject a in anims)
            {
                r.AnimationNames.Add((string)a["name"]);
                if ((string)a["name"] == clip) anim = a;
            }
            if (anim == null) return r;
            var accessors = (JArray)json["accessors"];
            var views = (JArray)json["bufferViews"];
            var samplers = (JArray)anim["samplers"];
            foreach (JObject ch in (JArray)anim["channels"])
            {
                var target = (JObject)ch["target"];
                if (target["node"] == null) continue;
                string node = names[(int)target["node"]];
                var smp = (JObject)samplers[(int)ch["sampler"]];
                string interp = (string)smp["interpolation"] ?? "LINEAR";
                var input = ReadFloats(accessors, views, bin, (int)smp["input"], out _);
                if (input.Length > 0) r.DurationS = Mathf.Max(r.DurationS, input[input.Length - 1]);
                var output = ReadFloats(accessors, views, bin, (int)smp["output"], out int comps);
                int keys = input.Length;
                if (keys == 0 || comps == 0) continue;
                // CUBICSPLINE stores (in-tangent, value, out-tangent) per key
                int idx = interp == "CUBICSPLINE" ? ((keys - 1) * 3 + 1) * comps : (keys - 1) * comps;
                if (idx + comps > output.Length) continue;
                var trs = r.Final[node];
                switch ((string)target["path"])
                {
                    case "translation": trs.Position = FrameConvert.GltfPointToUnity(output[idx], output[idx + 1], output[idx + 2]); break;
                    case "rotation": trs.Rotation = FrameConvert.GltfRotationToUnity(output[idx], output[idx + 1], output[idx + 2], output[idx + 3]); break;
                    case "scale": trs.Scale = new Vector3(output[idx], output[idx + 1], output[idx + 2]); break;
                }
                r.Final[node] = trs;
            }
            return r;
        }

        static float[] ReadFloats(JArray accessors, JArray views, ArraySegment<byte> bin, int accessorIndex, out int comps)
        {
            var a = (JObject)accessors[accessorIndex];
            comps = ((string)a["type"]) switch { "SCALAR" => 1, "VEC2" => 2, "VEC3" => 3, "VEC4" => 4, _ => 0 };
            int count = (int)a["count"];
            int ctype = (int)a["componentType"];
            bool normalized = (bool?)a["normalized"] ?? false;
            var result = new float[count * comps];
            if (a["bufferView"] == null || bin.Array == null) return result;
            var v = (JObject)views[(int)a["bufferView"]];
            int compSize = ctype == 5126 || ctype == 5125 ? 4 : ctype == 5122 || ctype == 5123 ? 2 : 1;
            int stride = (int?)v["byteStride"] ?? compSize * comps;
            int start = bin.Offset + ((int?)v["byteOffset"] ?? 0) + ((int?)a["byteOffset"] ?? 0);
            var b = bin.Array;
            for (int k = 0; k < count; k++)
                for (int c = 0; c < comps; c++)
                {
                    int o = start + k * stride + c * compSize;
                    float f;
                    switch (ctype)
                    {
                        case 5126: f = BitConverter.ToSingle(b, o); break;
                        case 5122: f = BitConverter.ToInt16(b, o); if (normalized) f = Mathf.Max(f / 32767f, -1f); break;
                        case 5123: f = BitConverter.ToUInt16(b, o); if (normalized) f /= 65535f; break;
                        case 5120: f = (sbyte)b[o]; if (normalized) f = Mathf.Max(f / 127f, -1f); break;
                        case 5121: f = b[o]; if (normalized) f /= 255f; break;
                        default: f = BitConverter.ToUInt32(b, o); break;
                    }
                    result[k * comps + c] = f;
                }
            return result;
        }
    }
}
