using System;
using System.IO;
using AG.Core;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace AG.Vision
{
    /// <summary>
    /// Where the printed marker is, as numbers the tracker can use. The shared marker is a STRIP with two tags
    /// (tools/quest/marker/marker.json): AprilTag 36h11 id 0 for the OpenCV-based apps and tagStandard41h12 id 0 for
    /// this app (jp.keijiro.apriltag detects only 41h12). Ground truth and <c>track.pos_m</c> refer to the STRIP CENTRE.
    ///
    /// Frames (all Unity, left-handed, metres):
    /// <list type="bullet">
    /// <item>Marker/strip/tag axes, Unity convention: +X to the strip's right, +Y to its printed TOP edge, +Z INTO the
    /// printed face (face normal = -Z, like a Unity Quad). marker.json uses OpenCV ArUco axes (x right, y top, z OUT
    /// of the face): a marker.json offset (x, y, z) is (x, y, -z) here.</item>
    /// <item>Assembly frame: the GLB root node <c>assembly</c> after glTFast import. In the MALM drawer GLB the drawer
    /// front (the base part, never animated) spans x [-0.19, 0.19] (380 mm length), y [-0.015, 0.115] (130 mm),
    /// z [0, 0.016] (16 mm thick); the INSIDE face (up in step 1, with the groove) is z = 0 and assembly -Z is up.
    /// The bottom panel sits at y 0.012..0.015, so the groove runs along y ~ 0.0135.</item>
    /// </list>
    /// MALM placement (marker.json "placement", read from the GLB on 15 Nov 2026, UNVERIFIED on a real drawer):
    /// strip centre at (0, <see cref="MalmStripCentreY"/>, 0) on the inside face; its TOP edge points to the groove
    /// (assembly -Y), its face normal is assembly -Z (up), so its +X runs along assembly -X: rotation = Euler(0, 0, 180).
    /// </summary>
    public static class MarkerSetup
    {
        /// <summary>Halfway between the groove (y ~ 0.0135) and the opposite long edge (y = 0.115). Measure and adjust.</summary>
        public const float MalmStripCentreY = 0.064f;

        public static Pose MalmStripInAssembly => new Pose(new Vector3(0f, MalmStripCentreY, 0f), Quaternion.Euler(0f, 0f, 180f));

        /// <summary>The dual strip (tools/quest/marker/marker.json, commit 6029bd6): tag41h12_0 centred 50 mm to the strip's +x.</summary>
        public static StripSpec Default => new StripSpec
        {
            TagId = 0,
            TagSizeM = 0.0571429f,                 // inner edge of the 80 mm black ring (5 of 9 modules of 80/7 mm)
            TagInStrip = new Pose(new Vector3(0.05f, 0f, 0f), Quaternion.identity),
            Origin = "built-in default",
        };

        public struct StripSpec
        {
            public int TagId;
            /// <summary>AprilTag pose size: edge of the detected border square of the 41h12 tag, metres.</summary>
            public float TagSizeM;
            /// <summary>41h12 tag frame in the strip frame (Unity marker axes).</summary>
            public Pose TagInStrip;
            public string Origin;

            /// <summary>Tracker config for a strip placed at <paramref name="stripInAssembly"/>.</summary>
            public MarkerConfig ToConfig(Pose stripInAssembly) => new MarkerConfig
            {
                TagId = TagId, TagSizeM = TagSizeM, TagInAssembly = Mul(stripInAssembly, TagInStrip),
            };
        }

        /// <summary>
        /// Reads marker.json (tools/quest/marker/): the <c>tags[]</c> entry whose family/key mentions 41h12; its
        /// <c>id</c>, <c>size_m</c> (the DETECTOR edge, AprilTag pose convention: 0.0571429 for the strip) and
        /// <c>offset_m</c> (tag centre in strip axes, ArUco convention, z out of the face). Returns
        /// <see cref="Default"/> for what it cannot find.
        /// </summary>
        public static StripSpec FromMarkerJson(string json)
        {
            var spec = Default;
            try
            {
                var root = JObject.Parse(json);
                JObject tag = null;
                foreach (var key in new[] { "tags", "markers" })
                    if (root[key] is JArray arr)
                        foreach (var t in arr)
                            if (t is JObject o && Is41h12(o)) tag = o;
                if (tag == null) { spec.Origin = "marker.json has no 41h12 entry; default used"; return spec; }
                spec.TagId = tag.Value<int?>("id") ?? spec.TagId;
                spec.TagSizeM = tag.Value<float?>("size_m") ?? spec.TagSizeM;
                if (tag["offset_m"] is JArray v && v.Count >= 3)
                    spec.TagInStrip = new Pose(new Vector3((float)v[0], (float)v[1], -(float)v[2]), Quaternion.identity);
                spec.Origin = "marker.json";
            }
            catch (Exception e) { spec.Origin = "marker.json unreadable (" + e.Message + "); default used"; }
            return spec;
        }

        static bool Is41h12(JObject o)
        {
            var f = ((string)o["family"] ?? "") + " " + ((string)o["key"] ?? "");
            return f.IndexOf("41h12", StringComparison.OrdinalIgnoreCase) >= 0;
        }

        /// <summary>
        /// The marker config the app uses: <c>persistentDataPath/marker.json</c> when present (push it with adb to
        /// change the strip without a rebuild), else <see cref="Default"/>; placed with <see cref="MalmStripInAssembly"/>.
        /// Logs <c>marker</c> once.
        /// </summary>
        public static (MarkerConfig config, Pose stripInAssembly) LoadForApp(string markerJsonPath = null)
        {
            markerJsonPath ??= Path.Combine(Application.persistentDataPath, "marker.json");
            var spec = File.Exists(markerJsonPath) ? FromMarkerJson(File.ReadAllText(markerJsonPath)) : Default;
            var strip = MalmStripInAssembly;
            var cfg = spec.ToConfig(strip);
            AgLog.Ev("marker", new
            {
                family = "tagStandard41h12", id = spec.TagId, tag_size_m = spec.TagSizeM, origin = spec.Origin,
                tag_in_strip_m = Arr(spec.TagInStrip.position), strip_in_assembly_m = Arr(strip.position),
                strip_in_assembly_euler = Arr(strip.rotation.eulerAngles),
            });
            return (cfg, strip);
        }

        static float[] Arr(Vector3 v) => new[] { v.x, v.y, v.z };

        /// <summary>a * b (b expressed in a's frame).</summary>
        public static Pose Mul(Pose a, Pose b) => new Pose(a.position + a.rotation * b.position, a.rotation * b.rotation);

        public static Pose Inverse(Pose a)
        {
            var qi = Quaternion.Inverse(a.rotation);
            return new Pose(qi * -a.position, qi);
        }

        /// <summary>Heading of a marker/strip frame about world up: angle of its +X axis, 0 = along world +X.</summary>
        public static float YawDeg(Quaternion markerInWorld)
        {
            var x = markerInWorld * Vector3.right;
            return Mathf.Atan2(x.z, x.x) * Mathf.Rad2Deg;
        }

        /// <summary>
        /// Same rotation in the OpenXR world (right-handed, Y up) with OpenCV ArUco marker axes (z out of the face),
        /// the convention of the replay datasets' <c>marker_q</c>/<c>world_q</c>. Mirror-Z conjugation: (-x, -y, z, w).
        /// </summary>
        public static float[] ToOpenXrArucoQuat(Quaternion unityMarker) =>
            new[] { -unityMarker.x, -unityMarker.y, unityMarker.z, unityMarker.w };
    }
}
