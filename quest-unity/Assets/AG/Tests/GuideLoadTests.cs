using System.Collections;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using AG.Guide;
using Newtonsoft.Json;
using NUnit.Framework;
using UnityEngine;
using UnityEngine.TestTools;

namespace AG.Tests
{
    /// <summary>
    /// The real MALM job (jobs/malm-drawer, copied to Assets/AG/Tests/Resources/malm-drawer/): counts, final
    /// transforms from the walkthrough clip vs the manifest's target_m after FrameConvert (this is the check of the
    /// Blender -> glTF -> Unity axis chain), from_dir vs the parts' rest offsets, and a full glTFast instantiate.
    /// Run: Unity -batchmode -projectPath quest-unity -runTests -testPlatform EditMode -testResults results.xml
    /// </summary>
    public sealed class GuideLoadTests
    {
        const string Job = "malm-drawer";
        const float Tol = 0.002f;   // 2 mm

        static ManifestDto Manifest() => JsonConvert.DeserializeObject<ManifestDto>(Resources.Load<TextAsset>(Job + "/manifest").text);
        static byte[] Glb() => Resources.Load<TextAsset>(Job + "/walkthrough.glb").bytes;

        [Test]
        public void ManifestCounts()
        {
            var g = GuideBuilder.FromManifest(Job, Manifest());
            Assert.AreEqual(7, g.Steps.Count, "steps");
            Assert.AreEqual(7 + 5, g.Parts.Count, "parts + hardware codes");
            Assert.AreEqual(7, g.Parts.Values.Count(p => !p.IsHardware), "boards");
            Assert.AreEqual(19, g.Nodes.Count, "GLB part nodes (7 boards + 12 hardware)");
            Assert.Greater(g.ActionCount, 19);
            foreach (var s in g.Steps)
                foreach (var a in s.Actions)
                {
                    Assert.IsNotNull(a.Text?.En, $"step {s.N} action {a.Index} en");
                    Assert.IsNotNull(a.Text?.El, $"step {s.N} action {a.Index} el");
                    if (a.Node != null) Assert.IsTrue(g.Nodes.ContainsKey(a.Node), a.Node);
                }
            var cam = g.Steps[1].Actions.FindAll(a => a.Code == "101345");
            Assert.AreEqual(2, cam.Count);
            Assert.AreEqual(2, cam[1].CountIndex);
            Assert.AreEqual(2, cam[1].CountTotal);
        }

        [Test]
        public void WalkthroughFinalPositionsMatchTargets()
        {
            var g = GuideLoader.BuildModel(Job, Manifest(), Glb());
            Assert.Greater(g.WalkthroughEndS, 40f, "walkthrough clip length");
            int checkedN = 0, nonZero = 0;
            foreach (var s in g.Steps)
                foreach (var a in s.Actions)
                {
                    if (a.Node == null || !a.Target.HasValue) continue;
                    var n = g.Nodes[a.Node];
                    float err = Vector3.Distance(n.FinalLocalPosition, a.Target.Value);
                    Assert.Less(err, Tol, $"step {s.N} {a.Node}: final {n.FinalLocalPosition:F4} vs target {a.Target.Value:F4}");
                    Assert.Greater(n.FinalLocalScale.x, 0.5f, $"{a.Node} still at rest scale");
                    checkedN++;
                    if (a.Target.Value.sqrMagnitude > 1e-4f) nonZero++;
                }
            Debug.Log($"AG test: {checkedN} final positions within 2 mm ({nonZero} away from the origin)");
            Assert.GreaterOrEqual(nonZero, 3);
            // spot values: cam bolt 1 at Blender (-0.166, 0, 0.055) -> Unity (0.166, 0.055, 0)
            var cb = g.Nodes["hw_118331_1"].FinalLocalPosition;
            Assert.Less(Vector3.Distance(cb, new Vector3(0.166f, 0.055f, 0f)), Tol);
        }

        /// <summary>Every action node of both jobs vs its target_m after FrameConvert, max error printed (judge U3: ≤ 1 mm).</summary>
        [TestCase("malm-drawer")]
        [TestCase("knagglig")]
        public void EveryNodeWithin1mmOfTarget(string job)
        {
            var m = JsonConvert.DeserializeObject<ManifestDto>(Resources.Load<TextAsset>(job + "/manifest").text);
            var g = GuideLoader.BuildModel(job, m, Resources.Load<TextAsset>(job + "/walkthrough.glb").bytes);
            float max = 0; int n = 0;
            foreach (var s in g.Steps)
                foreach (var a in s.Actions)
                {
                    if (a.Node == null || !a.Target.HasValue) continue;
                    float err = Vector3.Distance(g.Nodes[a.Node].FinalLocalPosition, a.Target.Value);
                    max = Mathf.Max(max, err);
                    n++;
                }
            Debug.Log($"AG test: job={job} nodes_checked={n} max_err_mm={max * 1000f:F4}");
            Assert.Greater(n, 10);
            Assert.LessOrEqual(max, 0.001f, $"{job} max_err_mm {max * 1000f:F3}");
        }

        [Test]
        public void FromDirPointsToWhereThePartRests()
        {
            var m = Manifest();
            var anim = GlbAnimation.Parse(Glb(), "walkthrough");
            var g = GuideBuilder.FromManifest(Job, m);
            int agree = 0, total = 0;
            foreach (var a in g.Steps.SelectMany(s => s.Actions))
            {
                if (a.Node == null || !a.FromDir.HasValue) continue;
                var off = anim.Rest[a.Node].Position - anim.Final[a.Node].Position;
                if (off.magnitude < 0.01f) continue;
                total++;
                if (Vector3.Dot(off.normalized, a.FromDir.Value) > 0.9f) agree++;
            }
            Debug.Log($"AG test: from_dir agrees with the rest offset for {agree} of {total} actions");
            Assert.GreaterOrEqual(agree, 3);
        }

        [Test]
        public void TurnAndStepPoseConvert()
        {
            // step 2: assemblyRotationDeg [90, 0, 0] (Blender XYZ Euler) must equal the assembly node's pose at the
            // end of the GLB's own step_02 clip (the pipeline animates the assembly into the step pose)
            var g = GuideBuilder.FromManifest(Job, Manifest());
            var q = g.Steps[1].AssemblyRotation;
            q.ToAngleAxis(out float ang, out Vector3 axis);
            Assert.AreEqual(90f, ang, 0.5f);
            Assert.Greater(Mathf.Abs(Vector3.Dot(axis, Vector3.right)), 0.99f);
            var anim = GlbAnimation.Parse(Glb(), "step_02");
            var glbPose = anim.Final["assembly"].Rotation;
            Assert.Less(Quaternion.Angle(glbPose, q), 1f, $"step pose {q.eulerAngles} vs GLB step_02 assembly {glbPose.eulerAngles}");
        }

        [UnityTest]
        public IEnumerator GltfastInstantiatesEveryPartAtItsFinalPlace()
        {
            var parent = new GameObject("test_parent").transform;
            var task = GuideLoader.LoadAsync(Job, Manifest(), Glb(), parent, null, CancellationToken.None);
            float t0 = Time.realtimeSinceStartup;
            while (!task.IsCompleted && Time.realtimeSinceStartup - t0 < 60f) yield return null;
            Assert.IsTrue(task.IsCompleted, "glTFast load timed out");
            if (task.IsFaulted) Assert.Fail(task.Exception?.ToString());
            var g = task.Result;
            Assert.IsNotNull(g.Root);
            Assert.AreEqual(parent, g.Root.transform.parent);
            int inst = g.Nodes.Values.Count(n => n.Instance != null);
            Assert.AreEqual(g.Nodes.Count, inst, "every part node instantiated");
            foreach (var n in g.Nodes.Values)
                Assert.Less(Vector3.Distance(n.Instance.transform.localPosition, n.FinalLocalPosition), 1e-5f, n.Node);
            var size = g.FinalBounds.size;
            Debug.Log($"AG test: assembly bounds {g.FinalBounds.center:F3} size {size:F3} (manifest overall {g.OverallSizeM:F3})");
            // MALM drawer: about 0.38 wide; within 25 % of the manifest's (estimated) overall size on its longest axis
            float longest = Mathf.Max(size.x, size.y, size.z), mLongest = Mathf.Max(g.OverallSizeM.x, g.OverallSizeM.y, g.OverallSizeM.z);
            Assert.Less(Mathf.Abs(longest - mLongest) / mLongest, 0.25f);
            Object.DestroyImmediate(parent.gameObject);
        }
    }
}
