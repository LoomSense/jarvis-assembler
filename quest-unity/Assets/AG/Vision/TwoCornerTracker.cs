using System;
using UnityEngine;

namespace AG.Vision
{
    /// <summary>
    /// Fallback registration: the user touches two known corners of the real object with the index fingertip
    /// (port of client/src/xr/register.ts). Yaw comes from the corner pair, "up" is world up (object on a table),
    /// position from the first corner. Holds the pose until the next snap (no camera needed).
    ///
    /// Inputs: corner positions in the ASSEMBLY frame (Configure), touched positions in WORLD (Snap), metres.
    /// Owner: Vision agent (the hand-tracking fingertip comes from the UI/App side, e.g. OVRSkeleton IndexTip).
    /// </summary>
    public sealed class TwoCornerTracker : IObjectTracker
    {
        Vector3 _aAsm, _bAsm;

        public TrackSource Source => TrackSource.Touch;
        public TrackResult Latest { get; private set; }
        public event Action<TrackResult> Updated;

        /// <summary>Which two corners the coach asks for, in the assembly frame (e.g. front-left, front-right on the base).</summary>
        public void SetCorners(Vector3 cornerAInAssembly, Vector3 cornerBInAssembly) { _aAsm = cornerAInAssembly; _bAsm = cornerBInAssembly; }

        /// <summary>Solve from the two touched world points (yaw only, gravity-aligned).</summary>
        public TrackResult Snap(Vector3 touchedAWorld, Vector3 touchedBWorld, double timeS)
        {
            Vector3 dAsm = Vector3.ProjectOnPlane(_bAsm - _aAsm, Vector3.up);
            Vector3 dWorld = Vector3.ProjectOnPlane(touchedBWorld - touchedAWorld, Vector3.up);
            if (dAsm.sqrMagnitude < 1e-6f || dWorld.sqrMagnitude < 1e-6f) return Latest;
            var rot = Quaternion.FromToRotation(dAsm.normalized, dWorld.normalized);
            rot = Quaternion.Euler(0, rot.eulerAngles.y, 0);                  // keep it level
            var pos = touchedAWorld - rot * _aAsm;
            float err = Mathf.Abs(dAsm.magnitude - dWorld.magnitude);          // metres, length mismatch
            Latest = new TrackResult
            {
                Tracked = true, AssemblyInWorld = new Pose(pos, rot), TimeS = timeS, Source = TrackSource.Touch,
                ReprojPx = -1, Confidence = Mathf.Clamp01(1f - err / 0.05f),
            };
            Updated?.Invoke(Latest);
            return Latest;
        }

        public void Configure(MarkerConfig marker) { }
        public TrackResult Process(in CameraFrame frame) => Latest;
        public void Reset() { Latest = default; }
    }
}
