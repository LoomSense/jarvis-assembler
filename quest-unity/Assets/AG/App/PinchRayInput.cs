using System.Collections.Generic;
using AG.Guide;
using UnityEngine;

namespace AG.App
{
    /// <summary>
    /// Pointers for the window: hand rays (OVRPlugin hand state: system pointer pose + index pinch) and controller rays
    /// (Touch pose + index trigger), in world space via the OVRCameraRig's tracking space. In the editor: the mouse.
    /// Draws a faint ray and a cursor dot where the active pointer hits the window (spec §5.1: indirect pinch-ray).
    /// UNVERIFIED on device: OVRPlugin.GetHandState pointer pose with the OpenXR backend of Meta Core 209.
    /// </summary>
    public sealed class PinchRayInput : MonoBehaviour
    {
        public struct PointerState { public Ray Ray; public bool Pinch; public bool Valid; public string Source; }

        public readonly List<PointerState> Pointers = new List<PointerState>();
        Transform _trackingSpace;
        LineRenderer _ray;
        Transform _cursor;
        OVRPlugin.HandState _hand = new OVRPlugin.HandState();

        void Awake()
        {
            var lineGo = new GameObject("pointer_ray");
            lineGo.transform.SetParent(transform, false);
            _ray = lineGo.AddComponent<LineRenderer>();
            _ray.positionCount = 2;
            _ray.widthMultiplier = 0.0015f;
            _ray.sharedMaterial = GuideMaterials.Flat(new Color(1, 1, 1, 0.25f), Tokens.QBadge);
            _ray.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _ray.enabled = false;
            _cursor = new GameObject("pointer_cursor").transform;
            _cursor.SetParent(transform, false);
            _cursor.gameObject.AddComponent<MeshFilter>().sharedMesh = GuideMeshes.Quad;
            _cursor.gameObject.AddComponent<MeshRenderer>().sharedMaterial = GuideMaterials.Flat(new Color(1, 1, 1, 0.9f), Tokens.QBadge + 2, 1, 0.12f);
            _cursor.localScale = Vector3.one * 0.012f;
            _cursor.gameObject.SetActive(false);
        }

        Transform TrackingSpace()
        {
            if (_trackingSpace != null) return _trackingSpace;
            var rig = FindFirstObjectByType<OVRCameraRig>();
            if (rig != null) _trackingSpace = rig.trackingSpace;
            return _trackingSpace;
        }

        public void Poll()
        {
            Pointers.Clear();
#if UNITY_EDITOR
            var cam = Camera.main;
            if (cam != null) Pointers.Add(new PointerState { Ray = cam.ScreenPointToRay(Input.mousePosition), Pinch = Input.GetMouseButton(0), Valid = true, Source = "mouse" });
#endif
            var ts = TrackingSpace();
            AddHand(OVRPlugin.Hand.HandRight, ts, "hand_r");
            AddHand(OVRPlugin.Hand.HandLeft, ts, "hand_l");
            AddController(OVRInput.Controller.RTouch, ts, "ctrl_r");
            AddController(OVRInput.Controller.LTouch, ts, "ctrl_l");
        }

        void AddHand(OVRPlugin.Hand which, Transform ts, string name)
        {
            try
            {
                if (!OVRPlugin.GetHandState(OVRPlugin.Step.Render, which, ref _hand)) return;
                if ((_hand.Status & OVRPlugin.HandStatus.InputStateValid) == 0) return;
                if ((_hand.Status & OVRPlugin.HandStatus.SystemGestureInProgress) != 0) return;
                var pose = _hand.PointerPose.ToOVRPose();
                var pos = ts != null ? ts.TransformPoint(pose.position) : pose.position;
                var fwd = ts != null ? ts.TransformDirection(pose.orientation * Vector3.forward) : pose.orientation * Vector3.forward;
                Pointers.Add(new PointerState { Ray = new Ray(pos, fwd), Pinch = (_hand.Pinches & OVRPlugin.HandFingerPinch.Index) != 0, Valid = true, Source = name });
            }
            catch (System.Exception) { }
        }

        void AddController(OVRInput.Controller c, Transform ts, string name)
        {
            try
            {
                if (!OVRInput.IsControllerConnected(c)) return;
                var p = OVRInput.GetLocalControllerPosition(c);
                var r = OVRInput.GetLocalControllerRotation(c);
                var pos = ts != null ? ts.TransformPoint(p) : p;
                var fwd = ts != null ? ts.TransformDirection(r * Vector3.forward) : r * Vector3.forward;
                bool pinch = OVRInput.Get(OVRInput.Axis1D.PrimaryIndexTrigger, c) > 0.55f;
                Pointers.Add(new PointerState { Ray = new Ray(pos, fwd), Pinch = pinch, Valid = true, Source = name });
            }
            catch (System.Exception) { }
        }

        /// <summary>Show the ray and the cursor for the pointer that hits the window (or hide both).</summary>
        public void Draw(PointerState? p, Vector3? hit)
        {
            bool show = p.HasValue && hit.HasValue && p.Value.Source != "mouse";
            _ray.enabled = show;
            _cursor.gameObject.SetActive(hit.HasValue);
            if (hit.HasValue)
            {
                _cursor.position = hit.Value;
                var cam = Camera.main;
                if (cam != null) _cursor.rotation = Quaternion.LookRotation(hit.Value - cam.transform.position);
                _cursor.localScale = Vector3.one * (p.HasValue && p.Value.Pinch ? 0.010f : 0.014f);
            }
            if (show)
            {
                _ray.SetPosition(0, p.Value.Ray.origin);
                _ray.SetPosition(1, hit.Value);
            }
        }
    }
}
