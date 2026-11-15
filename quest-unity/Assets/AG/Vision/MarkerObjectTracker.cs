using System;
using AG.Core;
using UnityEngine;

namespace AG.Vision
{
    /// <summary>
    /// STUB. AprilTag (tagStandard41h12) on the base part -> assembly pose in world.
    ///
    /// Plan (Vision agent):
    /// 1. <c>frame.Pixels</c> (set the camera source's CpuPixels = true), downscale if needed.
    /// 2. <c>new AprilTag.TagDetector(w, h, decimation: 2)</c>; <c>ProcessImage(pixels, frame.Intrinsics.VerticalFovRad, TagSizeM)</c>.
    ///    The package's pose assumes the principal point at the image centre; refine with your own PnP
    ///    (4 corners, IPPE/homography) using fx, fy, cx, cy if the error is visible. Mind row order (Pixels row 0 = bottom).
    /// 3. tagInCamera -> tagWorld = frame.CameraPose * tagInCamera; assembly = tagWorld * inverse(TagInAssembly).
    /// 4. Through IPoseFilter; hold the last pose for 0.5 s when the tag is hidden, then Tracked = false.
    /// 5. Log <c>track</c> at 1 Hz.
    /// </summary>
    public sealed class MarkerObjectTracker : IObjectTracker
    {
        readonly IPoseFilter _filter;
        MarkerConfig _marker;
        double _nextLog;
        int _frames;

        public MarkerObjectTracker(IPoseFilter filter) { _filter = filter; }

        public TrackSource Source => TrackSource.Marker;
        public TrackResult Latest { get; private set; }
        public event Action<TrackResult> Updated;

        public void Configure(MarkerConfig marker) { _marker = marker; Reset(); }

        public TrackResult Process(in CameraFrame frame)
        {
            _frames++;
            // TODO(Vision agent): detection + PnP. Until then: not tracked.
            var r = new TrackResult { Tracked = false, TimeS = frame.TimeS, Source = TrackSource.None, ReprojPx = -1 };
            Latest = r;
            Updated?.Invoke(r);
            LogTrack(frame.TimeS);
            return r;
        }

        public void Reset() { _filter?.Reset(); Latest = default; }

        void LogTrack(double t)
        {
            if (t < _nextLog) return;
            var r = Latest;
            var p = r.AssemblyInWorld.position;
            AgLog.Ev("track", new
            {
                fps = _frames, tracked = r.Tracked, pos_m = new[] { p.x, p.y, p.z },
                yaw_deg = r.AssemblyInWorld.rotation.eulerAngles.y, reproj_px = r.ReprojPx, source = TrackResult.SourceName(r.Tracked ? r.Source : TrackSource.None),
            });
            _frames = 0;
            _nextLog = t + 1.0;
        }
    }
}
