using System;
using AG.Core;
using UnityEngine;

namespace AG.Vision
{
    /// <summary>
    /// Fallback registration: the user touches two known corners of the real object with the index fingertip
    /// (port of client/src/xr/register.ts). Yaw comes from the corner pair, "up" is world up (object on a table),
    /// position from the midpoint of the pair (halves the error of each touch). Holds the pose until the next snap.
    ///
    /// Two ways to feed it:
    /// - <see cref="Snap"/>(a, b) with two world points you already have;
    /// - the fingertip flow: <see cref="BeginCapture"/>, then <see cref="Process"/>/<see cref="TickCapture"/> every frame
    ///   with <see cref="HandJointSource"/>; a touch is taken when the fingertip has stayed within
    ///   <see cref="DwellRadiusM"/> for <see cref="DwellS"/> (a deliberate hold, not a pass-by). <see cref="CaptureStage"/>
    ///   tells the UI what to ask for (0 = corner A, 1 = corner B, 2 = done).
    /// Logs <c>touch</c> per corner and <c>track</c> (source touch) on snap. Owner: Vision agent.
    /// </summary>
    public sealed class TwoCornerTracker : IObjectTracker
    {
        Vector3 _aAsm, _bAsm;
        HandJointSource _hands;
        Vector3 _touchA;
        Vector3 _dwellAt;
        float _dwellFor;
        bool _hasDwell;
        Vector3 _lastTouch;

        public float DwellS = 0.8f, DwellRadiusM = 0.012f;
        /// <summary>The second touch must be at least this far from the first.</summary>
        public float MinSeparationM = 0.10f;

        public TrackSource Source => TrackSource.Touch;
        public TrackResult Latest { get; private set; }
        public event Action<TrackResult> Updated;
        /// <summary>0 = waiting for corner A, 1 = waiting for corner B, 2 = done / idle.</summary>
        public int CaptureStage { get; private set; } = 2;
        /// <summary>Raised with the stage after each accepted touch (UI: chime, next prompt).</summary>
        public event Action<int> CornerCaptured;

        /// <summary>Which two corners the coach asks for, in the assembly frame (e.g. both ends of the drawer front's inside top edge).</summary>
        public void SetCorners(Vector3 cornerAInAssembly, Vector3 cornerBInAssembly) { _aAsm = cornerAInAssembly; _bAsm = cornerBInAssembly; }

        /// <summary>
        /// MALM drawer default: the two ends of the drawer front's inside face along the edge away from the groove
        /// (assembly y = 0.115, z = 0; see MarkerSetup for the frame). UNVERIFIED on a real drawer.
        /// </summary>
        public void SetMalmCorners() => SetCorners(new Vector3(-0.19f, 0.115f, 0f), new Vector3(0.19f, 0.115f, 0f));

        public void BeginCapture(HandJointSource hands)
        {
            _hands = hands;
            CaptureStage = 0;
            _hasDwell = false;
            _dwellFor = 0;
        }

        public void CancelCapture() => CaptureStage = 2;

        /// <summary>Solve from the two touched world points (yaw only, gravity-aligned).</summary>
        public TrackResult Snap(Vector3 touchedAWorld, Vector3 touchedBWorld, double timeS)
        {
            Vector3 dAsm = Vector3.ProjectOnPlane(_bAsm - _aAsm, Vector3.up);
            Vector3 dWorld = Vector3.ProjectOnPlane(touchedBWorld - touchedAWorld, Vector3.up);
            if (dAsm.sqrMagnitude < 1e-6f || dWorld.sqrMagnitude < 1e-6f) return Latest;
            float yaw = Vector3.SignedAngle(dAsm.normalized, dWorld.normalized, Vector3.up);
            var rot = Quaternion.AngleAxis(yaw, Vector3.up);
            var midAsm = (_aAsm + _bAsm) * 0.5f;
            var midWorld = (touchedAWorld + touchedBWorld) * 0.5f;
            var pos = midWorld - rot * midAsm;
            float err = Mathf.Abs((_bAsm - _aAsm).magnitude - (touchedBWorld - touchedAWorld).magnitude);   // metres
            Latest = new TrackResult
            {
                Tracked = true, AssemblyInWorld = new Pose(pos, rot), TimeS = timeS, Source = TrackSource.Touch,
                ReprojPx = -1, Confidence = Mathf.Clamp01(1f - err / 0.05f),
            };
            AgLog.Ev("track", new
            {
                fps = (object)null, tracked = true, pos_m = new[] { pos.x, pos.y, pos.z }, yaw_deg = yaw, reproj_px = -1,
                source = "touch", length_err_mm = err * 1000f,
            });
            Updated?.Invoke(Latest);
            return Latest;
        }

        /// <summary>Fingertip dwell capture; call every frame while <see cref="CaptureStage"/> &lt; 2.</summary>
        public void TickCapture(float dt, double timeS)
        {
            if (CaptureStage >= 2 || _hands == null) return;
            var tip = _hands.AnyIndexTip;
            if (!tip.HasValue) { _hasDwell = false; _dwellFor = 0; return; }
            if (!_hasDwell || Vector3.Distance(tip.Value, _dwellAt) > DwellRadiusM)
            {
                _dwellAt = tip.Value; _hasDwell = true; _dwellFor = 0; return;
            }
            _dwellAt = Vector3.Lerp(_dwellAt, tip.Value, 0.2f);
            _dwellFor += dt;
            if (_dwellFor < DwellS) return;
            if (CaptureStage == 1 && Vector3.Distance(_dwellAt, _touchA) < MinSeparationM) return;   // still on corner A
            if (CaptureStage == 0 && Vector3.Distance(_dwellAt, _lastTouch) < 0.02f && _lastTouch != Vector3.zero) return;
            _lastTouch = _dwellAt;
            AgLog.Ev("touch", new { corner = CaptureStage == 0 ? "A" : "B", pos_m = new[] { _dwellAt.x, _dwellAt.y, _dwellAt.z } });
            if (CaptureStage == 0) { _touchA = _dwellAt; CaptureStage = 1; _hasDwell = false; CornerCaptured?.Invoke(1); return; }
            CaptureStage = 2;
            Snap(_touchA, _dwellAt, timeS);
            CornerCaptured?.Invoke(2);
        }

        public void Configure(MarkerConfig marker) { }

        public TrackResult Process(in CameraFrame frame)
        {
            TickCapture(Time.deltaTime, frame.TimeS);
            return Latest;
        }

        public void Reset() { Latest = default; CaptureStage = 2; }
    }
}
