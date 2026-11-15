using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading.Tasks;
using AG.Core;
using UnityEngine;

namespace AG.Vision
{
    /// <summary>
    /// AprilTag tagStandard41h12 on the drawer front (the dual marker strip, see <see cref="MarkerSetup"/>) ->
    /// assembly pose in world.
    ///
    /// Per frame with <see cref="CameraFrame.Pixels"/> (set the source's CpuPixels = true):
    /// 1. grayscale (row 0 at the top) on the main thread, then AprilTag detection + AprilTag's own pose estimate with
    ///    the frame's real fx, fy, cx, cy on a worker thread (<see cref="Async"/>; the newest finished result is used,
    ///    frames that arrive while it runs are skipped);
    /// 2. gates: id, hamming, decision margin, reprojection error, and a jump gate (a pose far from the current one is
    ///    accepted only after <see cref="JumpConfirmFrames"/> agreeing detections: a moved drawer re-locks, a single
    ///    bad detection or a PnP flip does not);
    /// 3. tag in the Unity camera frame -> world with the frame's camera pose AT CAPTURE TIME -> assembly =
    ///    tagWorld * inverse(TagInAssembly) -> <see cref="IPoseFilter"/>;
    /// 4. hidden marker: the last pose is held (AssemblyInWorld keeps it); Tracked stays true for <see cref="HoldS"/>,
    ///    then false (AppRoot then leaves the anchor where it was: the ghost stays put).
    ///
    /// Row order: if no tag is found for <see cref="AutoFlipAfter"/> frames, the source rows are tried the other way
    /// up (a mirrored image never decodes); the working order is kept and logged (<c>track_rows</c>).
    ///
    /// Logs <c>track</c> at 1 Hz (fps = calls per second ~ render rate, tracked, pos_m = STRIP CENTRE in Unity world,
    /// yaw_deg of the strip's +X about world up, reproj_px, source, plus asm_m, det_hz, det_ms, margin, held, age_ms).
    /// With <see cref="LogEveryDetection"/> (replay) one <c>track</c> per processed frame with <c>frame</c> and
    /// <c>q</c> (strip rotation in Unity world, [x,y,z,w], Unity strip axes) for tools/quest/replay/score_track.py --unity.
    /// </summary>
    public sealed class MarkerObjectTracker : IObjectTracker, IDisposable
    {
        readonly IPoseFilter _filter;
        MarkerConfig _marker;
        Pose _assemblyFromMarker = Pose.identity;   // inverse(TagInAssembly)
        Pose _stripInAssembly = Pose.identity;

        public bool Async = true;
        public float Decimation = 2f;
        public int DetectorThreads = 2;
        public int MaxHamming = 1;
        public float MinMargin = 25f;
        public float MaxReprojPx = 3f;
        public float HoldS = 0.5f;
        public float JumpM = 0.04f, JumpDeg = 15f;
        public int JumpConfirmFrames = 3;
        public int AutoFlipAfter = 45;
        public bool LogEveryDetection;
        /// <summary>Optional sink for track lines (tests write their own log file); null = AgLog.</summary>
        public Action<Dictionary<string, object>> TrackSink;

        AprilTagDetector _det;
        byte[] _gray;
        bool _flipRows;
        bool _rowsLocked;
        int _missesSinceStart;

        Task<Result> _job;
        Meta _jobMeta;
        long _lastSubmitted = -1;

        int _jumpCount;
        Pose _jumpLast;
        double _lastSeenT = double.NegativeInfinity;
        bool _hasPose;
        Pose _raw;

        int _calls, _dets, _processed;
        double _detMsSum;
        double _nextLog;
        float _lastMargin;

        struct Meta
        {
            public long Index; public double TimeS; public Pose Cam; public bool HasPose; public int ReplayFrame;
            public CameraIntrinsics K;
        }

        struct Result
        {
            public bool Found; public TagDetection Det; public double Ms; public int Count;
        }

        public MarkerObjectTracker(IPoseFilter filter) { _filter = filter; }

        public TrackSource Source => TrackSource.Marker;
        public TrackResult Latest { get; private set; }
        /// <summary>Unfiltered assembly pose of the last accepted detection.</summary>
        public Pose RawAssemblyInWorld => _raw;
        public event Action<TrackResult> Updated;

        public void Configure(MarkerConfig marker)
        {
            _marker = marker;
            _assemblyFromMarker = MarkerSetup.Inverse(marker.TagInAssembly);
            Reset();
        }

        /// <summary>Strip placement, used only to report the strip centre in <c>track.pos_m</c> (truth of the replay sets).</summary>
        public void SetStripInAssembly(Pose stripInAssembly) => _stripInAssembly = stripInAssembly;

        public TrackResult Process(in CameraFrame frame)
        {
            _calls++;
            if (_job != null && _job.IsCompleted) Consume();
            if (frame.Pixels != null && frame.Index != _lastSubmitted && _job == null && AprilTagDetector.Available && _marker.TagSizeM > 0)
            {
                Submit(frame);
                if (!Async && _job != null) { _job.Wait(); Consume(); }
            }
            UpdateHold(frame.TimeS);
            if (!LogEveryDetection) LogTrack(frame.TimeS, -1);
            return Latest;
        }

        void Submit(in CameraFrame f)
        {
            if (_det == null || _det.Width != f.Width || _det.Height != f.Height)
            {
                _det?.Dispose();
                _det = new AprilTagDetector(f.Width, f.Height, Decimation, DetectorThreads);
                _gray = new byte[f.Width * f.Height];
                AgLog.Ev("track_init", new { w = f.Width, h = f.Height, decimation = Decimation, tag_size_m = _marker.TagSizeM, id = _marker.TagId });
            }
            _lastSubmitted = f.Index;
            AprilTagDetector.ToGray(f.Pixels, _gray, f.Width, f.Height, _flipRows);
            _jobMeta = new Meta { Index = f.Index, TimeS = f.TimeS, Cam = f.CameraPose, HasPose = f.HasPose, ReplayFrame = f.ReplayFrame, K = f.Intrinsics };
            var det = _det; var gray = _gray; var k = f.Intrinsics; float size = _marker.TagSizeM; int id = _marker.TagId;
            Func<Result> run = () =>
            {
                var sw = Stopwatch.StartNew();
                var list = det.Detect(gray, k, size, id);
                var r = new Result { Ms = sw.Elapsed.TotalMilliseconds, Count = list.Count };
                foreach (var d in list)
                    if (!r.Found || d.Margin > r.Det.Margin) { r.Det = d; r.Found = true; }
                return r;
            };
            _job = Async ? Task.Run(run) : Task.FromResult(run());
        }

        void Consume()
        {
            var job = _job; _job = null;
            if (job.IsFaulted) { AgLog.Error("MarkerObjectTracker", job.Exception?.GetBaseException().Message); return; }
            var r = job.Result;
            var m = _jobMeta;
            _processed++;
            _detMsSum += r.Ms;
            bool ok = r.Found && r.Det.Hamming <= MaxHamming && r.Det.Margin >= MinMargin && r.Det.ReprojPx <= MaxReprojPx;
            if (r.Found) _lastMargin = r.Det.Margin;
            if (!ok)
            {
                if (!_rowsLocked && ++_missesSinceStart >= AutoFlipAfter)
                {
                    _flipRows = !_flipRows; _missesSinceStart = 0;
                    AgLog.Ev("track_rows", new { flip = _flipRows, locked = false });
                }
                if (LogEveryDetection) LogTrack(m.TimeS, m.ReplayFrame);
                return;
            }
            if (!_rowsLocked) { _rowsLocked = true; AgLog.Ev("track_rows", new { flip = _flipRows, locked = true }); }
            _dets++;

            var tagInCam = r.Det.InUnityCamera();
            var cam = m.HasPose ? m.Cam : Pose.identity;
            var tagWorld = MarkerSetup.Mul(cam, tagInCam);
            var asm = MarkerSetup.Mul(tagWorld, _assemblyFromMarker);

            if (_hasPose && !Accept(asm)) { if (LogEveryDetection) LogTrack(m.TimeS, m.ReplayFrame); return; }
            _raw = asm;
            _hasPose = true;
            var filtered = _filter != null ? _filter.Filter(asm, m.TimeS) : asm;
            _lastSeenT = m.TimeS;
            float conf = Mathf.Clamp01(r.Det.Margin / 80f) * Mathf.Clamp01(1f - r.Det.ReprojPx / (2f * MaxReprojPx));
            Latest = new TrackResult
            {
                Tracked = true, AssemblyInWorld = filtered, TimeS = m.TimeS, Source = TrackSource.Marker,
                ReprojPx = r.Det.ReprojPx, Confidence = conf,
            };
            Updated?.Invoke(Latest);
            if (LogEveryDetection) LogTrack(m.TimeS, m.ReplayFrame);
        }

        /// <summary>Jump gate: far from the current pose -> needs JumpConfirmFrames agreeing measurements.</summary>
        bool Accept(Pose asm)
        {
            var cur = Latest.AssemblyInWorld;
            bool near = Vector3.Distance(asm.position, cur.position) <= JumpM && Quaternion.Angle(asm.rotation, cur.rotation) <= JumpDeg;
            if (near) { _jumpCount = 0; return true; }
            bool agrees = _jumpCount > 0 && Vector3.Distance(asm.position, _jumpLast.position) <= 0.01f && Quaternion.Angle(asm.rotation, _jumpLast.rotation) <= 5f;
            _jumpCount = agrees ? _jumpCount + 1 : 1;
            _jumpLast = asm;
            if (_jumpCount < JumpConfirmFrames) return false;
            _jumpCount = 0;
            _filter?.Reset();
            AgLog.Ev("track_jump", new { m = Vector3.Distance(asm.position, cur.position), deg = Quaternion.Angle(asm.rotation, cur.rotation) });
            return true;
        }

        void UpdateHold(double now)
        {
            if (!Latest.Tracked || now - _lastSeenT <= HoldS) return;
            var r = Latest;
            r.Tracked = false;               // pose kept: consumers may hold it
            Latest = r;
            Updated?.Invoke(Latest);
        }

        public void Reset()
        {
            _filter?.Reset();
            Latest = default;
            _hasPose = false;
            _jumpCount = 0;
            _lastSeenT = double.NegativeInfinity;
        }

        void LogTrack(double t, int replayFrame)
        {
            bool perFrame = LogEveryDetection;
            if (!perFrame && t < _nextLog) return;
            var r = Latest;
            var strip = MarkerSetup.Mul(r.AssemblyInWorld, _stripInAssembly);
            var p = strip.position;
            var a = r.AssemblyInWorld.position;
            bool any = _hasPose;
            double age = any ? t - _lastSeenT : -1;
            var o = new Dictionary<string, object>
            {
                ["fps"] = perFrame ? (object)null : _calls,
                ["tracked"] = r.Tracked,
                ["pos_m"] = any ? new[] { R(p.x), R(p.y), R(p.z) } : null,
                ["yaw_deg"] = any ? (object)R(MarkerSetup.YawDeg(strip.rotation), 3) : null,
                ["reproj_px"] = any ? (object)R(r.ReprojPx, 3) : null,
                ["source"] = r.Tracked ? "marker" : "none",
                ["asm_m"] = any ? new[] { R(a.x), R(a.y), R(a.z) } : null,
                ["held"] = any && !r.Tracked,
                ["age_ms"] = any ? (object)(int)(age * 1000) : null,
                ["det_hz"] = perFrame ? (object)null : _processed,
                ["det_ms"] = _processed > 0 ? (object)R((float)(_detMsSum / _processed), 2) : null,
                ["margin"] = R(_lastMargin, 1),
                ["state"] = r.Tracked ? "tracked" : any ? "held" : "lost",
                ["method"] = "marker",
            };
            if (perFrame)
            {
                o["frame"] = replayFrame;
                o["q"] = any ? new[] { R(strip.rotation.x, 6), R(strip.rotation.y, 6), R(strip.rotation.z, 6), R(strip.rotation.w, 6) } : null;
                o["tracked"] = r.Tracked && age == 0;     // this frame's own detection
                o["source"] = (bool)o["tracked"] ? "marker" : "none";
                o["state"] = (bool)o["tracked"] ? "tracked" : any ? "held" : "lost";
            }
            if (TrackSink != null) TrackSink(o); else AgLog.Ev("track", o);
            _calls = 0; _processed = 0; _detMsSum = 0;
            _nextLog = t + 1.0;
        }

        static float R(float v, int d = 5) => (float)Math.Round(v, d);

        public void Dispose()
        {
            try { _job?.Wait(500); } catch (Exception) { }
            _det?.Dispose();
            _det = null;
        }
    }
}
