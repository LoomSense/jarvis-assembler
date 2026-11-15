using System;
using System.Collections.Generic;
using System.Diagnostics;
using AG.Core;
using UnityEngine;
#if AG_HAS_MRUK
using Meta.XR.MRUtilityKit;
#endif

namespace AG.World
{
    /// <summary>
    /// The room's tables from the headset's scene model (MR Utility Kit), as <see cref="TableSurface"/>s.
    ///
    /// Creates its own MRUK object when the scene has none: scene load on startup OFF (this class asks for the
    /// permission and loads, and never starts Space Setup, which would take the user out of the app) and world lock
    /// OFF (MRUK would otherwise move the rig's TrackingSpace under the tracker and the camera poses).
    /// Does nothing in the editor. Every failure ends in a state name and an empty list: the caller falls back to
    /// the head placement. Main thread only.
    /// </summary>
    public sealed class MrukTables
    {
        public const string ScenePermission = "com.oculus.permission.USE_SCENE";

        /// <summary>off, editor, no_mruk, permission, loading, then MRUK's result: Success, NoScenePermission, NoRoomsFound, ... or error.</summary>
        public string State { get; private set; } = "off";
        /// <summary>How long the scene load took; -1 until it ended.</summary>
        public long LoadMs { get; private set; } = -1;
        public bool Loaded => State == "Success";

        readonly Stopwatch _sw = new Stopwatch();
        float _askedAt;
        bool _askedTwice;

        /// <summary>Ask for the scene permission if needed and load the scene model. Call once; safe to call again after a failure.</summary>
        public void Begin()
        {
#if AG_HAS_MRUK
            if (State == "permission" || State == "loading" || Loaded) return;
            if (Application.isEditor) { State = "editor"; return; }
            try
            {
                if (MRUK.Instance == null)
                {
                    var go = new GameObject("MRUK");
                    go.SetActive(false);                       // fields before Awake
                    var m = go.AddComponent<MRUK>();
                    m.SceneSettings = new MRUK.MRUKSettings { LoadSceneOnStartup = false };
                    m.EnableWorldLock = false;
                    go.SetActive(true);
                }
                _sw.Restart();
#if UNITY_ANDROID && !UNITY_EDITOR
                if (!UnityEngine.Android.Permission.HasUserAuthorizedPermission(ScenePermission))
                {
                    State = "permission";
                    _askedAt = Time.realtimeSinceStartup;
                    Ask();
                    return;
                }
#endif
                Load();
            }
            catch (Exception e) { Fail(e); }
#else
            State = "no_mruk";
#endif
        }

        /// <summary>Every frame: notices a permission granted without a callback (another dialog was open when we asked).</summary>
        public void Tick()
        {
#if AG_HAS_MRUK && UNITY_ANDROID && !UNITY_EDITOR
            if (State != "permission") return;
            if (UnityEngine.Android.Permission.HasUserAuthorizedPermission(ScenePermission)) { Load(); return; }
            if (!_askedTwice && Time.realtimeSinceStartup - _askedAt > 4f) { _askedTwice = true; Ask(); }
#endif
        }

#if AG_HAS_MRUK
        void Ask()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            var cb = new UnityEngine.Android.PermissionCallbacks();
            cb.PermissionGranted += _ => { if (State == "permission") Load(); };
            cb.PermissionDenied += _ => { if (State == "permission") End("NoScenePermission"); };
            UnityEngine.Android.Permission.RequestUserPermission(ScenePermission, cb);
#endif
        }

        async void Load()
        {
            State = "loading";
            try
            {
                var r = await MRUK.Instance.LoadSceneFromDevice(requestSceneCaptureIfNoDataFound: false);
                End(r.ToString());
            }
            catch (Exception e) { Fail(e); }
        }
#endif

        void End(string state)
        {
            State = state;
            LoadMs = _sw.ElapsedMilliseconds;
            var found = new List<TableSurface>();
            int n = Collect(found);
            AgLog.Ev("scene", new { state, ms = LoadMs, tables = n });
        }

        void Fail(Exception e)
        {
            State = "error";
            LoadMs = _sw.ElapsedMilliseconds;
            AgLog.Error("MrukTables", e.GetType().Name + ": " + e.Message);
        }

        /// <summary>Adds every table of every loaded room to <paramref name="into"/>; returns how many.</summary>
        public int Collect(List<TableSurface> into)
        {
            int n = 0;
#if AG_HAS_MRUK
            if (!Loaded || MRUK.Instance == null) return 0;
            try
            {
                foreach (var room in MRUK.Instance.Rooms)
                {
                    if (room == null) continue;
                    foreach (var a in room.Anchors)
                    {
                        if (a == null || !a.HasAnyLabel(MRUKAnchor.SceneLabels.TABLE)) continue;
                        Bounds local;
                        if (a.VolumeBounds.HasValue) local = a.VolumeBounds.Value;
                        else if (a.PlaneRect.HasValue)
                        {
                            var rect = a.PlaneRect.Value;
                            local = new Bounds(new Vector3(rect.center.x, rect.center.y, 0), new Vector3(rect.size.x, rect.size.y, 0));
                        }
                        else continue;
                        var pose = new Pose(a.transform.position, a.transform.rotation);
                        if (TableSurface.TryFromLocalBounds(pose, local, out var t)) { into.Add(t); n++; }
                    }
                }
            }
            catch (Exception e) { AgLog.Error("MrukTables", e.GetType().Name + ": " + e.Message); }
#endif
            return n;
        }
    }
}
