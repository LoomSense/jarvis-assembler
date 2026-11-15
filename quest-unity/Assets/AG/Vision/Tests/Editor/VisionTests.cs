using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using AG.Core;
using Newtonsoft.Json;
using NUnit.Framework;
using UnityEngine;

namespace AG.Vision.Tests
{
    public class VisionTests
    {
        static string Repo => Path.GetFullPath(Path.Combine(Application.dataPath, "..", ".."));

        [Test]
        public void MarkerJson_StripSpec()
        {
            var path = Path.Combine(Repo, "tools/quest/marker/marker.json");
            if (!File.Exists(path)) Assert.Inconclusive("no marker.json");
            var s = MarkerSetup.FromMarkerJson(File.ReadAllText(path));
            Assert.AreEqual("marker.json", s.Origin);
            Assert.AreEqual(0, s.TagId);
            Assert.AreEqual(0.0571429f, s.TagSizeM, 1e-6f);
            Assert.AreEqual(0.05f, s.TagInStrip.position.x, 1e-6f);
        }

        [Test]
        public void CropScale_SquareSensorTo4x3()
        {
            // 1280x1280 sensor, principal point at the centre, read at 1280x960: crop 1280x960 centred, scale 1.
            var k = PcaCameraSource.CropScale(new Vector2(800, 800), new Vector2(640, 640), new Vector2Int(1280, 1280), new Vector2Int(1280, 960));
            Assert.AreEqual(800, k.fx, 1e-3); Assert.AreEqual(640, k.cx, 1e-3); Assert.AreEqual(480, k.cy, 1e-3);
            // same aspect: pure scale
            var k2 = PcaCameraSource.CropScale(new Vector2(800, 800), new Vector2(640, 480), new Vector2Int(1280, 960), new Vector2Int(640, 480));
            Assert.AreEqual(400, k2.fx, 1e-3); Assert.AreEqual(320, k2.cx, 1e-3); Assert.AreEqual(240, k2.cy, 1e-3);
        }

        [Test]
        public void ReplayPose_CanonicalMatchesLegacyOpenXr()
        {
            // cam_q (OpenCV axes) and cam_q_xr must give the same Unity camera.
            var cv = new Quaternion(0.7720977f, -0.2277292f, 0.4287281f, 0.410118f);
            var xr = new Quaternion(-0.410118f, -0.4287281f, -0.2277292f, 0.7720977f);
            var a = Newtonsoft.Json.Linq.JObject.Parse("{\"cam_p\":[1,2,3],\"cam_q\":[" + cv.x + "," + cv.y + "," + cv.z + "," + cv.w + "]}");
            var b = Newtonsoft.Json.Linq.JObject.Parse("{\"cam_p\":[1,2,3],\"cam_q_xr\":[" + xr.x + "," + xr.y + "," + xr.z + "," + xr.w + "]}");
            Assert.IsTrue(ReplayCameraSource.TryParsePose(a, out var pa));
            Assert.IsTrue(ReplayCameraSource.TryParsePose(b, out var pb));
            Assert.Less(Quaternion.Angle(pa.rotation, pb.rotation), 0.01f);
            Assert.AreEqual(new Vector3(1, 2, -3), pb.position);
        }

        [Test]
        public void TwoCorner_RecoversPose()
        {
            var t = new TwoCornerTracker();
            t.SetMalmCorners();
            var truth = new Pose(new Vector3(0.3f, 0.75f, 0.6f), Quaternion.Euler(0, 37, 0));
            var a = truth.position + truth.rotation * new Vector3(-0.19f, 0.115f, 0f);
            var b = truth.position + truth.rotation * new Vector3(0.19f, 0.115f, 0f);
            var r = t.Snap(a, b, 0);
            Assert.Less(Vector3.Distance(r.AssemblyInWorld.position, truth.position), 1e-4f);
            Assert.Less(Quaternion.Angle(r.AssemblyInWorld.rotation, truth.rotation), 0.01f);
        }

        [Test]
        public void OneEuro_SmoothsStillAndFollowsMoves()
        {
            var f = new SmoothingPoseFilter();
            var rng = new System.Random(1);
            Pose last = default;
            var errs = new List<float>();
            for (int i = 0; i < 300; i++)
            {
                var n = new Vector3((float)(rng.NextDouble() - 0.5), (float)(rng.NextDouble() - 0.5), (float)(rng.NextDouble() - 0.5)) * 0.004f;
                last = f.Filter(new Pose(n, Quaternion.identity), i / 30.0);
                if (i > 30) errs.Add(last.position.magnitude);
            }
            Assert.Less(errs.Average(), 0.0012f, "still noise of +-2 mm should shrink");
            // a 3 cm move is followed within 0.5 s
            for (int i = 300; i < 315; i++) last = f.Filter(new Pose(new Vector3(0.03f, 0, 0), Quaternion.identity), i / 30.0);
            Assert.Less(Mathf.Abs(last.position.x - 0.03f), 0.003f);
        }

        /// <summary>
        /// Offline accuracy: ReplayCameraSource + MarkerObjectTracker over tools/quest/replay/marker-synthetic (or
        /// AG_REPLAY_DATASET), writes tools/quest/runs/unity-editmode-&lt;dataset&gt;/agq.jsonl (filtered, what the app
        /// uses) and agq-raw.jsonl (no filter) for <c>score_track.py --unity</c>.
        /// </summary>
        [Test]
        public void MarkerTracker_ReplayAccuracy()
        {
            if (!AprilTagDetector.Available) Assert.Inconclusive("jp.keijiro.apriltag missing");
            var ds = Environment.GetEnvironmentVariable("AG_REPLAY_DATASET");
            if (string.IsNullOrEmpty(ds)) ds = Path.Combine(Repo, "tools/quest/replay/marker-synthetic");
            if (!File.Exists(Path.Combine(ds, "poses.jsonl")))
                Assert.Inconclusive("no dataset at " + ds + ": run tools/quest/replay/make_marker_synthetic.py");
            AgLog.Init();
            var markerJson = Path.Combine(Repo, "tools/quest/marker/marker.json");
            var spec = File.Exists(markerJson) ? MarkerSetup.FromMarkerJson(File.ReadAllText(markerJson)) : MarkerSetup.Default;

            var src = new ReplayCameraSource(ds) { CpuPixels = true, Loop = false };
            Assert.IsTrue(src.StartAsync().Result, "replay did not start");
            var lines = new List<string>();
            var raw = new List<string>();
            var start = JsonConvert.SerializeObject(new Dictionary<string, object> { ["t"] = 0, ["app"] = "unity", ["ev"] = "start", ["replay"] = true, ["job"] = Path.GetFileName(ds) });
            lines.Add(start); raw.Add(start);
            double t = 0;
            var tracker = new MarkerObjectTracker(new SmoothingPoseFilter()) { Async = false, LogEveryDetection = true };
            tracker.TrackSink = o =>
            {
                var d = new Dictionary<string, object> { ["t"] = (long)(500 + t * 1000), ["app"] = "unity", ["ev"] = "track" };
                foreach (var kv in o) d[kv.Key] = kv.Value;
                lines.Add(JsonConvert.SerializeObject(d));
                if (d["tracked"] is bool tr && tr)
                {
                    var p = tracker.RawAssemblyInWorld;
                    var r = new Dictionary<string, object>(d)
                    {
                        ["pos_m"] = new[] { p.position.x, p.position.y, p.position.z },
                        ["q"] = new[] { p.rotation.x, p.rotation.y, p.rotation.z, p.rotation.w },
                        ["yaw_deg"] = MarkerSetup.YawDeg(p.rotation),
                    };
                    raw.Add(JsonConvert.SerializeObject(r));
                }
                else raw.Add(JsonConvert.SerializeObject(d));
            };
            tracker.Configure(spec.ToConfig(Pose.identity));      // assembly = strip, so pos_m = asm_m
            tracker.SetStripInAssembly(Pose.identity);

            int frames = 0, tracked = 0;
            var err = new List<float>();
            var sw = System.Diagnostics.Stopwatch.StartNew();
            while (src.Step())
            {
                src.TryGetLatest(out var f);
                t = f.TimeS;
                var r = tracker.Process(f);
                frames++;
                if (r.Tracked && r.TimeS == f.TimeS) { tracked++; err.Add(tracker.RawAssemblyInWorld.position.magnitude * 1000f); }
            }
            tracker.Dispose();
            var outDir = Path.Combine(Repo, "tools/quest/runs", "unity-editmode-" + Path.GetFileName(ds));
            Directory.CreateDirectory(outDir);
            File.WriteAllLines(Path.Combine(outDir, "agq.jsonl"), lines);
            Directory.CreateDirectory(outDir + "-raw");
            File.WriteAllLines(Path.Combine(outDir + "-raw", "agq.jsonl"), raw);
            err.Sort();
            float p50 = err.Count > 0 ? err[err.Count / 2] : -1, p95 = err.Count > 0 ? err[(int)(err.Count * 0.95)] : -1;
            Debug.Log($"AGQ-TEST replay {Path.GetFileName(ds)}: {tracked}/{frames} frames tracked, raw |pos| p50 {p50:0.00} mm p95 {p95:0.00} mm, {sw.ElapsedMilliseconds / Mathf.Max(1, frames)} ms/frame (load+detect) -> {outDir}");
            Assert.GreaterOrEqual(tracked, (int)(frames * 0.9f), "tracked fraction");
            Assert.Less(p50, 5f, "position error p50 (mm)");
        }
    }
}
