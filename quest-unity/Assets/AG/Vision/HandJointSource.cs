using System.Collections.Generic;
using UnityEngine;
#if AG_HAS_XRHANDS
using UnityEngine.XR.Hands;
#endif

namespace AG.Vision
{
    /// <summary>
    /// Hand joints in Unity WORLD space from the Unity XR Hands subsystem (OpenXR hand tracking, which SetupProject
    /// enables; com.unity.xr.hands comes with Meta Core 209). Joint poses are relative to the XR origin, so they are
    /// mapped through <see cref="TrackingSpace"/> (OVRCameraRig/TrackingSpace = the main camera's parent).
    /// Call <see cref="Tick"/> once per frame (main thread), then read the nullable positions (null = not tracked).
    /// Feeds TwoCornerTracker (index fingertip touch) and AG.Net.HandsSummarizer (socket <c>hands</c>).
    /// </summary>
    public sealed class HandJointSource
    {
        public Transform TrackingSpace;

        public Vector3? LeftIndexTip { get; private set; }
        public Vector3? RightIndexTip { get; private set; }
        public Vector3? LeftPalm { get; private set; }
        public Vector3? RightPalm { get; private set; }
        public bool Available { get; private set; }

#if AG_HAS_XRHANDS
        XRHandSubsystem _sub;
        static readonly List<XRHandSubsystem> Subs = new List<XRHandSubsystem>();
#endif

        public HandJointSource(Transform trackingSpace = null) { TrackingSpace = trackingSpace; }

        public void Tick()
        {
            LeftIndexTip = RightIndexTip = LeftPalm = RightPalm = null;
#if AG_HAS_XRHANDS
            if (_sub == null || !_sub.running)
            {
                Subs.Clear();
                SubsystemManager.GetSubsystems(Subs);
                _sub = null;
                foreach (var s in Subs) if (s.running) { _sub = s; break; }
            }
            Available = _sub != null;
            if (_sub == null) return;
            if (TrackingSpace == null && Camera.main != null) TrackingSpace = Camera.main.transform.parent;
            Read(_sub.leftHand, out var lt, out var lp);
            Read(_sub.rightHand, out var rt, out var rp);
            LeftIndexTip = lt; LeftPalm = lp; RightIndexTip = rt; RightPalm = rp;
#endif
        }

        /// <summary>The fingertip of whichever hand is tracked (right preferred), or null.</summary>
        public Vector3? AnyIndexTip => RightIndexTip ?? LeftIndexTip;

#if AG_HAS_XRHANDS
        void Read(XRHand hand, out Vector3? tip, out Vector3? palm)
        {
            tip = palm = null;
            if (!hand.isTracked) return;
            if (hand.GetJoint(XRHandJointID.IndexTip).TryGetPose(out var t)) tip = ToWorld(t.position);
            if (hand.GetJoint(XRHandJointID.Palm).TryGetPose(out var p)) palm = ToWorld(p.position);
        }

        Vector3 ToWorld(Vector3 local) => TrackingSpace != null ? TrackingSpace.TransformPoint(local) : local;
#endif
    }
}
