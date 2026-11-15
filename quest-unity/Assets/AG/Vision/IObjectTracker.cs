using System;
using UnityEngine;

namespace AG.Vision
{
    /// <summary>Where the current assembly pose comes from. Log values: "marker", "touch", "none".</summary>
    public enum TrackSource { None, Marker, Touch }

    public struct TrackResult
    {
        /// <summary>True when <see cref="AssemblyInWorld"/> is fresh (seen in the last ~0.5 s) or held from a touch.</summary>
        public bool Tracked;
        /// <summary>Pose of the ASSEMBLY FRAME (GLB root node "assembly", Unity, metres) in Unity world space.</summary>
        public Pose AssemblyInWorld;
        /// <summary>Time of the frame it came from (CameraFrame.TimeS clock).</summary>
        public double TimeS;
        public TrackSource Source;
        /// <summary>Mean reprojection error of the marker corners in pixels; -1 when not applicable.</summary>
        public float ReprojPx;
        /// <summary>0..1.</summary>
        public float Confidence;

        public static string SourceName(TrackSource s) => s == TrackSource.Marker ? "marker" : s == TrackSource.Touch ? "touch" : "none";
    }

    /// <summary>Marker stuck on the base part: which tag, how big, where it sits in the assembly frame.</summary>
    [Serializable]
    public struct MarkerConfig
    {
        /// <summary>AprilTag family tagStandard41h12 (the only one jp.keijiro.apriltag detects), tag id.</summary>
        public int TagId;
        /// <summary>Edge length of the black square in metres (measure the printed tag).</summary>
        public float TagSizeM;
        /// <summary>Tag frame in the assembly frame (tag centre; +Z out of the tag face, +Y towards the tag's top edge).</summary>
        public Pose TagInAssembly;
    }

    /// <summary>
    /// 6-DoF pose of the real assembly in world space. First implementation: AprilTag of known size on the base
    /// part (detect in <see cref="CameraFrame"/>, solve PnP with its intrinsics, compose with
    /// <see cref="CameraFrame.CameraPose"/>, then assembly = tagWorld * inverse(TagInAssembly)). Fallback: two
    /// fingertip touches on known corners (TwoCornerTracker, port of client/src/xr/register.ts).
    ///
    /// Threading: <see cref="Process"/> on the main thread; implementations may run detection in a job/thread and
    /// return the latest finished result. Logs <c>track</c> at 1 Hz (fps, tracked, pos_m, yaw_deg, reproj_px, source).
    /// Owner: Vision agent.
    /// </summary>
    public interface IObjectTracker
    {
        TrackSource Source { get; }
        void Configure(MarkerConfig marker);
        /// <summary>Feed one frame; returns the newest result (may be from an earlier frame).</summary>
        TrackResult Process(in CameraFrame frame);
        TrackResult Latest { get; }
        /// <summary>Raised on the main thread whenever <see cref="Latest"/> changes.</summary>
        event Action<TrackResult> Updated;
        void Reset();
    }

    /// <summary>
    /// Smooths a noisy pose stream (marker jitter) and holds the last pose through short dropouts.
    /// Pure; main thread. Owner: Vision agent.
    /// </summary>
    public interface IPoseFilter
    {
        /// <summary>Add a measurement at <paramref name="timeS"/>; returns the filtered pose.</summary>
        Pose Filter(Pose measured, double timeS);
        /// <summary>Best estimate at <paramref name="timeS"/> without a new measurement (hold or extrapolate).</summary>
        Pose Predict(double timeS);
        void Reset();
    }
}
