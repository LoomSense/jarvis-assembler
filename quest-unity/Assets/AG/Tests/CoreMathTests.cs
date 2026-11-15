using System.Collections.Generic;
using AG.Core;
using AG.Guide;
using Newtonsoft.Json.Linq;
using NUnit.Framework;
using UnityEngine;

namespace AG.Tests
{
    /// <summary>FrameConvert (Blender Z-up -> Unity), the glTF -> Unity rules glTFast uses, AgLog's JSON line format, GLB parsing basics.</summary>
    public sealed class CoreMathTests
    {
        static void Near(Vector3 expected, Vector3 actual, float tol = 1e-5f) =>
            Assert.Less(Vector3.Distance(expected, actual), tol, $"expected {expected:F5} got {actual:F5}");

        [Test]
        public void BlenderPointToUnity_IsMinusX_Z_MinusY()
        {
            Near(new Vector3(-1, 3, -2), FrameConvert.BlenderPointToUnity(new[] { 1f, 2f, 3f }));
            Near(Vector3.up, FrameConvert.BlenderPointToUnity(new[] { 0f, 0f, 1f }));      // Blender up -> Unity up
            Near(Vector3.back, FrameConvert.BlenderPointToUnity(new[] { 0f, 1f, 0f }));    // Blender +Y -> Unity -Z
            Near(Vector3.zero, FrameConvert.BlenderPointToUnity(null));
        }

        [Test]
        public void BlenderDirToUnity_IsNormalised()
        {
            var d = FrameConvert.BlenderDirToUnity(new[] { 0f, 3f, 4f });
            Assert.AreEqual(1f, d.magnitude, 1e-5f);
            Near(new Vector3(0, 0.8f, -0.6f), d);
        }

        [Test]
        public void BlenderEuler_ConjugatesByTheBasisChange()
        {
            // For any Blender rotation R and point p: convert(R p) == R_unity * convert(p)
            var rnd = new System.Random(3);
            for (int k = 0; k < 20; k++)
            {
                var e = new[] { (float)rnd.NextDouble() * 360 - 180, (float)rnd.NextDouble() * 360 - 180, (float)rnd.NextDouble() * 360 - 180 };
                var p = new[] { (float)rnd.NextDouble() - 0.5f, (float)rnd.NextDouble() - 0.5f, (float)rnd.NextDouble() - 0.5f };
                var rb = BlenderRot(e);
                var rp = rb.MultiplyVector(new Vector3(p[0], p[1], p[2]));
                var lhs = FrameConvert.BlenderPointToUnity(new[] { rp.x, rp.y, rp.z });
                var rhs = FrameConvert.BlenderEulerDegToUnity(e) * FrameConvert.BlenderPointToUnity(p);
                Near(lhs, rhs, 1e-4f);
            }
        }

        [Test]
        public void BlenderEuler_90AboutZ()
        {
            // Blender: +90 about Z takes +X to +Y. Unity: (-1,0,0) must go to (0,0,-1).
            var q = FrameConvert.BlenderEulerDegToUnity(new[] { 0f, 0f, 90f });
            Near(new Vector3(0, 0, -1), q * new Vector3(-1, 0, 0), 1e-4f);
        }

        [Test]
        public void GltfToUnity_MatchesGltfastRules()
        {
            Near(new Vector3(-1, 2, 3), FrameConvert.GltfPointToUnity(1, 2, 3));
            var q = FrameConvert.GltfRotationToUnity(0.1f, 0.2f, 0.3f, 0.9f);
            Assert.AreEqual(0.1f, q.x, 1e-6f); Assert.AreEqual(-0.2f, q.y, 1e-6f); Assert.AreEqual(-0.3f, q.z, 1e-6f); Assert.AreEqual(0.9f, q.w, 1e-6f);
            // a glTF rotation applied to a glTF vector, converted == converted rotation applied to the converted vector
            var qg = Quaternion.Normalize(new Quaternion(0.1f, 0.2f, 0.3f, 0.9f));
            var v = new Vector3(0.3f, -0.2f, 0.5f);
            var rg = RightHanded(qg, v);
            Near(FrameConvert.GltfPointToUnity(rg.x, rg.y, rg.z), FrameConvert.GltfRotationToUnity(qg.x, qg.y, qg.z, qg.w) * FrameConvert.GltfPointToUnity(v.x, v.y, v.z), 1e-4f);
        }

        [Test]
        public void AgLog_WritesOneJsonObjectPerLine()
        {
            var lines = new List<string>();
            void Handler(string msg, string stack, LogType t) { if (msg.StartsWith(AgLog.Tag + " ")) lines.Add(msg.Substring(AgLog.Tag.Length + 1)); }
            Application.logMessageReceived += Handler;
            try
            {
                AgLog.Init();
                AgLog.Ev("step", new { step = 3, action = 1, by = "button" });
                AgLog.Error("test", "boom \"quoted\"\nnewline");
            }
            finally { Application.logMessageReceived -= Handler; }
            Assert.AreEqual(2, lines.Count);
            foreach (var l in lines) Assert.IsFalse(l.Contains("\n"), "one line per event");
            var o = JObject.Parse(lines[0]);
            Assert.AreEqual("unity", (string)o["app"]);
            Assert.AreEqual("step", (string)o["ev"]);
            Assert.AreEqual(3, (int)o["step"]);
            Assert.AreEqual("button", (string)o["by"]);
            Assert.IsTrue(o["t"].Type == JTokenType.Integer && (long)o["t"] >= 0);
            var e = JObject.Parse(lines[1]);
            Assert.AreEqual("error", (string)e["ev"]);
            Assert.AreEqual("test", (string)e["where"]);
            Assert.AreEqual("boom \"quoted\"\nnewline", (string)e["message"]);
        }

        [Test]
        public void GlbAnimation_RejectsNonGlb()
        {
            Assert.Throws<System.FormatException>(() => GlbAnimation.Parse(new byte[40], "walkthrough"));
        }

        [Test]
        public void GreekUppercase_DropsTonos()
        {
            Assert.AreEqual("ΒΗΜΑ 3 ΑΠΟ 7 · ΣΕΛΙΔΑ 5", Txt.UpperNoTonos("Βήμα 3 από 7 · σελίδα 5"));
        }

        // Blender XYZ Euler as a right-handed matrix R = Rz Ry Rx
        static Matrix4x4 BlenderRot(float[] deg)
        {
            float rx = deg[0] * Mathf.Deg2Rad, ry = deg[1] * Mathf.Deg2Rad, rz = deg[2] * Mathf.Deg2Rad;
            Matrix4x4 X = Matrix4x4.identity, Y = Matrix4x4.identity, Z = Matrix4x4.identity;
            X.m11 = Mathf.Cos(rx); X.m12 = -Mathf.Sin(rx); X.m21 = Mathf.Sin(rx); X.m22 = Mathf.Cos(rx);
            Y.m00 = Mathf.Cos(ry); Y.m02 = Mathf.Sin(ry); Y.m20 = -Mathf.Sin(ry); Y.m22 = Mathf.Cos(ry);
            Z.m00 = Mathf.Cos(rz); Z.m01 = -Mathf.Sin(rz); Z.m10 = Mathf.Sin(rz); Z.m11 = Mathf.Cos(rz);
            return Z * Y * X;
        }

        // standard (right-handed math) quaternion rotation of a vector; Unity's operator uses the same algebra
        static Vector3 RightHanded(Quaternion q, Vector3 v) => q * v;
    }
}
