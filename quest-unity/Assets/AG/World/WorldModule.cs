using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using AG.Core;
using AG.Guide;
using AG.Vision;
using UnityEngine;

namespace AG.World
{
    /// <summary>Answer of <see cref="WorldModule.FindPartAsync"/>.</summary>
    public struct PartHit
    {
        public bool Found;
        public string Id, Label;
        /// <summary>The server's sentence ("The cam lock is at the top left."), for the voice if wanted.</summary>
        public string Message;
        /// <summary>Where the part lies on the table, world. Valid when <see cref="Found"/>.</summary>
        public Vector3 World;
        /// <summary>Extent of the server's box on the table (x along <see cref="Across"/>), metres.</summary>
        public Vector2 SizeM;
        /// <summary>World direction of the picture's left-to-right on the table.</summary>
        public Vector3 Across;
        public float Confidence;
        /// <summary>The server's box as sent: [ymin, xmin, ymax, xmax], 0-1000, y down. Null when not seen.</summary>
        public int[] Box2d;
        /// <summary>Why not, when not found: busy, no_server, local_job, no_frame, no_pose, no_jpeg, not_seen, simulated, no_plane_hit, http_NNN, cancelled, error.</summary>
        public string Reason;
        public long Ms;
        public int ServerMs;
        public bool Simulated;
    }

    /// <summary>
    /// The world layer: where the table is, where the assembly anchor goes when no marker tracks the real object, and
    /// where a wanted part lies in the pile. With no printed marker this is the app's registration.
    ///
    /// Use (from AppRoot): construct once; <see cref="Tick"/> every frame AFTER the tracker has had its say;
    /// <see cref="OnAction"/> whenever the coach's step or action may have changed; <see cref="RequestRealign"/> for
    /// an explicit "align again"; <see cref="OnObjectPose"/> for any other pose source (markerless, manual).
    /// Main thread only.
    ///
    /// Who owns the anchor, strongest first: a live marker / two-corner track (the <c>tracked</c> flag: this module
    /// then touches nothing) > <c>manual</c> > <c>markerless</c> (<see cref="OnObjectPose"/>) > <c>server_box</c> (the
    /// camera finds the base part through /parts and the anchor is moved so the ghost of that part lies on it) >
    /// <c>mruk_table</c> / <c>head_fallback</c> (table from the scene model, else 0.55 m ahead and 0.35 m below the eyes).
    ///
    /// No jitter: before the user starts the anchor follows the head quietly; at the start it is placed once; a
    /// stronger source's first pose is taken at once; after that a pose moves the anchor only when it disagrees by
    /// more than 10 cm / 20 degrees and keeps saying so for 3 s (<see cref="PoseGate"/>), or on request. The camera
    /// looks for the base part every 5 s until it is found, then every 15 s. A table / head
    /// placement is redone only when left more than 1.5 m behind for 3 s or out of view for 6 s, and never once the
    /// anchor sits on the real object. Logs <c>scene</c>, <c>place</c> (source: mruk_table, head_fallback, server_box,
    /// manual, markerless) and <c>parts</c>.
    /// </summary>
    public sealed class WorldModule
    {
        public const string SourceTable = "mruk_table", SourceHead = "head_fallback", SourceBox = "server_box",
            SourceManual = "manual", SourceMarkerless = "markerless", SourceTrack = "track", SourceNone = "none";

        /// <summary>Where the anchor's current pose comes from.</summary>
        public string PlaceSource { get; private set; } = SourceNone;
        /// <summary>Height of the work surface, world metres: the scene table, the tracked object's underside, or the fallback guess.</summary>
        public float TableY { get; private set; }
        public bool HasTableY { get; private set; }
        public PlacementPolicy Policy { get; } = new PlacementPolicy();
        public PoseGate Gate { get; } = new PoseGate();
        public MrukTables Scene { get; } = new MrukTables();
        public BaseOutline Outline => _outline;

        /// <summary>
        /// The pick-up cue to draw (spec §4.6): one target on the table for the part the action needs, or null to clear
        /// it. Wire to <c>AppRoot.ShowPickTargets</c> (the ghost renderer draws it; this module draws no marker itself).
        /// </summary>
        public event Action<IReadOnlyList<PickTarget>> PickTargets;
        /// <summary>The server's last box on the camera picture as image UV with y up, or null. Wire to <c>AppRoot.PreviewBox</c>.</summary>
        public event Action<Rect?> PreviewBox;

        /// <summary>Raised after every logged placement with its source (for a cue, a voice line, the status pill).</summary>
        public event Action<string> Placed;

        /// <summary>Look for the base part with the camera and move the anchor onto it (source server_box).</summary>
        public bool AutoAlign = true;
        /// <summary>Seconds between camera looks for the base part until it is found; grows to 20 s while it is not seen.</summary>
        public float RecheckEveryS = 5f;
        /// <summary>Seconds between looks once the anchor is on the part (only to notice that the part was moved).</summary>
        public float AlignedRecheckEveryS = 15f;
        /// <summary>Draw the base part's footprint on the table while no marker tracks the object.</summary>
        public bool ShowOutline = true;
        /// <summary>Poses offered through <see cref="OnObjectPose"/> below this confidence are ignored.</summary>
        public float MinConfidence = 0.5f;
        /// <summary>Use a simulated server answer too (its box is a placeholder: development only).</summary>
        public bool ShowSimulated;
        /// <summary>Set when the server pictures turn out upside down on the device (flips the box's y).</summary>
        public bool FlipV;
        /// <summary>The pick-up cue is cleared after this long.</summary>
        public float MarkerForS = 25f;

        /// <summary>True while the outline is the user's cue: started, no live track. Show <see cref="CoachLine"/> then.</summary>
        public bool WantsOutlineHint { get; private set; }
        /// <summary>"Place the drawer front inside the blue outline." in the app's language, with the base part's own name when known.</summary>
        public string CoachLine => WorldText.PlaceInOutline(_lang, BaseName());
        public string CoachPill => WorldText.PlaceInOutlinePill(_lang);

        readonly Transform _anchor;
        readonly ICameraSource _camera;
        readonly PartsApi _api;
        readonly BaseOutline _outline;
        readonly string _lang;
        readonly List<TableSurface> _tables = new List<TableSurface>();
        readonly Vector3[] _corners = new Vector3[8];
        float _t0 = -1f, _provisionalAt, _snapTriedForLoadMs = -2f, _alignAt, _alignEvery;
        bool _sceneBegun, _busy, _forceAlign, _tracked, _started;
        int _step = -1, _action = -1;
        string _lastWant, _shownId, _pendingWant;
        AssemblyGuide _guide;
        PartNode _base;
        float _pickAt;
        bool _pickShown;
        Vector3 _outlineAt;
        Quaternion _outlineRot;
        float _outlineY = float.NaN;
        CancellationToken _ct;

        public WorldModule(string server, string key, Transform assemblyAnchor, ICameraSource camera, string lang, CancellationToken ct = default)
        {
            _anchor = assemblyAnchor;
            _camera = camera;
            _api = new PartsApi(server, key);
            _lang = lang == "el" ? "el" : "en";
            _outline = new BaseOutline();
            _alignEvery = RecheckEveryS;
            _ct = ct;
        }

        /// <summary>
        /// Explicit "align again": the anchor is put back in front of the user (table or head placement) and the next
        /// camera answer for the base part is taken at once. Ignored while a marker track is live.
        /// </summary>
        public void RequestRealign()
        {
            Policy.Request();
            _forceAlign = true;
            _alignAt = 0f;
        }

        /// <summary>
        /// Plug for any other pose source. <paramref name="worldFromAssembly"/> is written to the assembly anchor as it
        /// is (the pose of the GLB's <c>assembly</c> frame in Unity world). <paramref name="source"/> goes into the
        /// <c>place</c> event: "markerless", "manual", ... "manual" is always taken at once; others pass the same
        /// no-jitter gate as the camera box. Ignored while a marker track is live or below <see cref="MinConfidence"/>.
        /// Returns true when the anchor moved.
        /// </summary>
        public bool OnObjectPose(Pose worldFromAssembly, float confidence, string source)
        {
            if (_anchor == null || _tracked) return false;
            if (string.IsNullOrEmpty(source)) source = SourceMarkerless;
            bool manual = source == SourceManual;
            if (!manual && confidence < MinConfidence) return false;
            float now = Time.realtimeSinceStartup;
            bool stronger = Rank(source) > Rank(PlaceSource);
            var cur = new Pose(_anchor.position, _anchor.rotation);
            bool first = !Gate.HasPose;
            if (!Gate.Offer(cur, worldFromAssembly, now, manual || stronger || _forceAlign)) return false;
            string reason = manual ? "manual" : _forceAlign ? "request" : first || stronger ? "first" : "disagree";
            _forceAlign = false;
            SetPose(worldFromAssembly, source, reason, confidence, 0);
            return true;
        }

        static int Rank(string source)
        {
            switch (source)
            {
                case SourceNone: case SourceTable: case SourceHead: return 0;
                case SourceBox: return 1;
                case SourceManual: return 3;
                case SourceTrack: return 4;
                default: return 2;      // markerless and anything else that sees the real object
            }
        }

        /// <summary>
        /// Every frame, after the tracker. <paramref name="tracked"/>: a marker or two-corner pose is live (it owns the
        /// anchor). <paramref name="started"/>: the object is (about to be) shown, e.g. the coach reached Locate.
        /// <paramref name="ghostWorldBounds"/>: the assembly's world box when the renderer has one; used to rest it on
        /// the table and to learn the table height from a tracked object.
        /// </summary>
        public void Tick(Transform head, bool tracked, bool started, Bounds? ghostWorldBounds = null)
        {
            float now = Time.realtimeSinceStartup;
            if (_t0 < 0) _t0 = now;
            _tracked = tracked;
            _started = started;
            // the scene permission dialog must not collide with the camera's: ask once the camera runs (or after 8 s)
            if (!_sceneBegun && (_camera == null || _camera.IsRunning || now - _t0 > 8f)) { _sceneBegun = true; Scene.Begin(); }
            Scene.Tick();
            if (_pickShown && now - _pickAt > MarkerForS) HidePart();
            if (head == null || _anchor == null) return;

            var h = new Pose(head.position, head.rotation);
            var decision = Policy.Step(tracked, started, h, _anchor.position, Time.unscaledDeltaTime);
            if (decision == PlaceDecision.Provisional)
            {
                if (now >= _provisionalAt) { _provisionalAt = now + 0.5f; PlaceOnTableOrHead(h, null); }
            }
            else if (decision == PlaceDecision.Place || decision == PlaceDecision.Replace)
            {
                PlaceOnTableOrHead(h, Policy.Reason);
                Gate.Reset();                    // the next pose of the real object is taken at once
                _alignAt = 0f;
            }
            else if (tracked)
            {
                PlaceSource = SourceTrack;
                _forceAlign = false;
                if (ghostWorldBounds.HasValue) { TableY = ghostWorldBounds.Value.min.y; HasTableY = true; }
            }
            else
            {
                if (PlaceSource == SourceHead && Scene.Loaded && _snapTriedForLoadMs != Scene.LoadMs) SnapHeight();
                if (Rank(PlaceSource) <= 1 && PlaceSource != SourceNone && ghostWorldBounds.HasValue)
                {
                    // rest the assembly's box on the table (vertical only; the step pose changes which face is down)
                    float lift = TableY - ghostWorldBounds.Value.min.y;
                    if (Mathf.Abs(lift) > 0.005f) _anchor.position += Vector3.up * lift;
                }
            }

            WantsOutlineHint = started && !tracked && _base != null && HasTableY;
            DrawOutline();
            if (_busy) return;
            if (_pendingWant != null) { var w = _pendingWant; _pendingWant = null; _ = FindPartAsync(_guide?.JobId, _step, w, _ct); }
            else if (AutoAlign && started && !tracked && _base != null && Policy.Placed && now >= _alignAt
                     && (Rank(PlaceSource) <= 1 || _forceAlign))      // a stronger source owns the anchor until a request
            {
                _alignAt = now + _alignEvery;
                _ = AlignToBaseAsync(_ct);
            }
        }

        // ---- placement ------------------------------------------------------------------------------------

        void PlaceOnTableOrHead(Pose head, string reason)
        {
            var sw = Stopwatch.StartNew();
            _tables.Clear();
            int n = Scene.Collect(_tables);
            Pose p;
            string source;
            if (WorldMath.TryPlaceOnTables(_tables, head, out p, out int i)) { source = SourceTable; TableY = _tables[i].TopY; }
            else { p = WorldMath.HeadFallback(head); source = SourceHead; TableY = p.position.y; }
            HasTableY = true;
            if (reason == null)
            {
                // provisional: nothing is shown yet, so no event either
                PlaceSource = source;
                _anchor.SetPositionAndRotation(p.position, p.rotation);
                return;
            }
            SetPose(p, source, reason, 1f, sw.Elapsed.TotalMilliseconds, n, head.position);
        }

        void SetPose(Pose p, string source, string reason, float confidence, double ms, int tables = -1, Vector3? head = null)
        {
            PlaceSource = source;
            _anchor.SetPositionAndRotation(p.position, p.rotation);
            if (Rank(source) >= 1) Policy.Pin();
            AgLog.Ev("place", new
            {
                source, pos_m = new[] { p.position.x, p.position.y, p.position.z }, yaw_deg = p.rotation.eulerAngles.y, ms, reason,
                confidence, table_y = HasTableY ? TableY : (float?)null, tables = tables >= 0 ? tables : (int?)null,
                scene = Scene.State, scene_ms = Scene.LoadMs,
                head_m = head.HasValue ? new[] { head.Value.x, head.Value.y, head.Value.z } : null,
            });
            try { Placed?.Invoke(source); }
            catch (Exception e) { AgLog.Error("WorldModule.Placed", e.GetType().Name + ": " + e.Message); }
        }

        /// <summary>
        /// The scene model arrived after a head-fallback placement: when a table lies under the anchor, take its height
        /// (vertical move only, the anchor does not slide).
        /// </summary>
        void SnapHeight()
        {
            _snapTriedForLoadMs = Scene.LoadMs;
            _tables.Clear();
            int n = Scene.Collect(_tables);
            var a = _anchor.position;
            foreach (var t in _tables)
            {
                if (!t.Contains(a) || Mathf.Abs(t.TopY - a.y) > 0.6f) continue;
                TableY = t.TopY;
                SetPose(new Pose(new Vector3(a.x, t.TopY, a.z), _anchor.rotation), SourceTable, "height_snap", 1f, 0, n);
                return;
            }
        }

        // ---- the base part: outline on the table, camera alignment ---------------------------------------------

        /// <summary>
        /// The part everything else is built on: the largest board no action places (the drawer front on malm-drawer),
        /// else the first board the guide places. Null when the guide has no boards.
        /// </summary>
        public static PartNode BasePart(AssemblyGuide guide)
        {
            if (guide == null) return null;
            PartNode best = null, first = null;
            foreach (var n in guide.Nodes.Values)
            {
                if (n == null || n.IsHardware) continue;
                if (n.PlacedInStep == 0)
                {
                    if (best == null || n.FinalBounds.size.sqrMagnitude > best.FinalBounds.size.sqrMagnitude) best = n;
                }
                else if (first == null || n.PlacedInStep < first.PlacedInStep) first = n;
            }
            return best ?? first;
        }

        string BaseName()
        {
            if (_base == null || _guide == null || !_guide.Parts.TryGetValue(_base.PartId ?? "", out var info) || info.Names == null) return null;
            return info.Names.Get(_lang);
        }

        Transform Root => _guide != null && _guide.Root != null ? _guide.Root.transform : _anchor;

        /// <summary>The base part's box corners in world, under the anchor and the renderer's current step pose.</summary>
        void BaseCorners()
        {
            var b = _base.FinalBounds;
            var root = Root;
            for (int i = 0; i < 8; i++)
                _corners[i] = root.TransformPoint(b.center + Vector3.Scale(b.extents, new Vector3((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1)));
        }

        void DrawOutline()
        {
            if (!ShowOutline || !WantsOutlineHint) { if (_outline.Visible) _outline.Hide(); _outlineY = float.NaN; return; }
            var root = Root;
            if (_outline.Visible && root.position == _outlineAt && root.rotation == _outlineRot && TableY == _outlineY) return;
            _outlineAt = root.position; _outlineRot = root.rotation; _outlineY = TableY;
            BaseCorners();
            _outline.Show(WorldMath.FootprintHull(_corners, TableY + 0.003f));
        }

        /// <summary>
        /// One camera look for the base part; the anchor moves onto it when the gate lets the result through (first
        /// time at once, later only after it disagreed by more than 10 cm for several seconds, or on request).
        /// </summary>
        async Task AlignToBaseAsync(CancellationToken ct)
        {
            var node = _base;
            var guide = _guide;
            if (node == null || guide == null) return;
            var hit = await LocateAsync(guide.JobId, _step, node.PartId, ct);
            bool moved = false;
            string why = hit.Reason;
            if (hit.Found && !_tracked && _guide == guide && _base == node)
            {
                var root = Root;
                var b = node.FinalBounds;
                int li = b.extents.x >= b.extents.y && b.extents.x >= b.extents.z ? 0 : b.extents.y >= b.extents.z ? 1 : 2;
                var axis = Vector3.zero;
                axis[li] = 1f;
                // footprint of the part as it lies now: the two largest sides of its box when it lies flat
                var e = b.size;
                float pLong = Mathf.Max(e.x, Mathf.Max(e.y, e.z)), pShort = e.x + e.y + e.z - pLong - Mathf.Min(e.x, Mathf.Min(e.y, e.z));
                bool sizeOk = WorldMath.PlausibleSize(hit.SizeM, pLong, pShort);
                var cur = new Pose(_anchor.position, _anchor.rotation);
                var cand = WorldMath.AlignAnchor(cur, root.TransformPoint(b.center), root.TransformDirection(axis), hit.World,
                    WorldMath.LongAxis(hit.SizeM, hit.Across));
                bool first = !Gate.HasPose, stronger = Rank(SourceBox) > Rank(PlaceSource);
                if (!sizeOk) why = "size";          // a box around the pile or a small piece must not move the ghost
                else if (Rank(PlaceSource) > Rank(SourceBox) && !_forceAlign) why = "weaker_source";
                else if (Gate.Offer(cur, cand, Time.realtimeSinceStartup, _forceAlign || stronger))
                {
                    string reason = _forceAlign ? "request" : first || stronger ? "first" : "disagree";
                    _forceAlign = false;
                    SetPose(cand, SourceBox, reason, hit.Confidence, hit.Ms);
                    moved = true;
                }
                else why = "held";
            }
            // next look: soon when a disagreement waits for a second opinion, rarely once on the part, backing off while unseen
            if (!hit.Found) _alignEvery = Mathf.Min(Mathf.Max(_alignEvery, RecheckEveryS) * 1.5f, 20f);
            else _alignEvery = Gate.HasPending ? Gate.HoldS + 1f : PlaceSource == SourceBox ? AlignedRecheckEveryS : RecheckEveryS;
            _alignAt = Time.realtimeSinceStartup + _alignEvery;
            LogParts(hit, "align", node.PartId, moved, why);
        }

        // ---- find a part in the pile ---------------------------------------------------------------------------

        public void HidePart()
        {
            _shownId = null;
            if (!_pickShown) return;
            _pickShown = false;
            Raise(null, null);
        }

        void Raise(IReadOnlyList<PickTarget> targets, Rect? box)
        {
            try { PickTargets?.Invoke(targets); PreviewBox?.Invoke(box); }
            catch (Exception e) { AgLog.Error("WorldModule.PickTargets", e.GetType().Name + ": " + e.Message); }
        }

        /// <summary>
        /// Call whenever the coach's guide, step or action may have changed (cheap when nothing did). Remembers the
        /// guide (base part, outline, alignment) and looks for the part or hardware the action needs; an action on the
        /// item the cue already shows keeps the cue and asks nothing.
        /// </summary>
        public void OnAction(AssemblyGuide guide, int step, int action)
        {
            if (guide != _guide)
            {
                _guide = guide;
                _base = BasePart(guide);
                _step = _action = -1;
                _alignAt = 0f;
                _outlineY = float.NaN;
            }
            if (guide == null || (step == _step && action == _action)) return;
            _step = step;
            _action = action;
            var s = guide.StepByNumber(step);
            var a = s != null && action >= 0 && action < s.Actions.Count ? s.Actions[action] : null;
            string want = a == null ? null : !string.IsNullOrEmpty(a.Code) ? a.Code : a.PartId;
            _pendingWant = null;
            if (string.IsNullOrEmpty(want)) { _lastWant = null; HidePart(); return; }
            if (want == _lastWant && _pickShown && _shownId == want) return;
            _lastWant = want;
            HidePart();
            if (_busy) _pendingWant = want;         // a camera look is in flight: Tick sends this one when it is back
            else _ = FindPartAsync(guide.JobId, step, want, _ct);
        }

        /// <summary>
        /// One camera frame -> <c>POST /api/perception/{job}/parts</c> -> the box centre as a world ray (the frame's
        /// intrinsics and camera pose) -> the table plane -> <see cref="PickTargets"/>. <paramref name="want"/>: a part id or
        /// hardware code; null lets the server take the step's items (the first one found is shown). Never throws;
        /// "not found" only clears the cue and logs <c>parts</c> with found false.
        /// </summary>
        public async Task<PartHit> FindPartAsync(string jobId, int step, string want = null, CancellationToken ct = default)
        {
            var hit = await LocateAsync(jobId, step, want, ct);
            if (hit.Found)
            {
                _shownId = want ?? hit.Id;
                _pickShown = true;
                _pickAt = Time.realtimeSinceStartup;
                Raise(new[] { new PickTarget
                {
                    WorldPosition = hit.World, FootprintM = WorldMath.WorldExtents(hit.SizeM, hit.Across), PartId = hit.Id,
                    Label = string.IsNullOrEmpty(hit.Label) ? null : hit.Label,
                } }, WorldMath.BoxToUvRect(hit.Box2d));
            }
            else if (hit.Reason != "busy" && _shownId == null) HidePart();
            LogParts(hit, "find", want, hit.Found, hit.Reason);
            return hit;
        }

        void LogParts(PartHit hit, string purpose, string want, bool used, string reason)
        {
            AgLog.Ev("parts", new
            {
                ms = hit.Ms, found = hit.Found, pos_m = hit.Found ? new[] { hit.World.x, hit.World.y, hit.World.z } : null,
                purpose, used, id = hit.Id, label = hit.Label, confidence = hit.Confidence, server_ms = hit.ServerMs,
                simulated = hit.Simulated, step = _step, want, reason,
                size_m = hit.Found ? new[] { hit.SizeM.x, hit.SizeM.y } : null,
                table_y = HasTableY ? TableY : (float?)null, table = PlaceSource,
            });
        }

        /// <summary>Frame -> server -> table point. One request at a time ("busy" otherwise). Never throws.</summary>
        async Task<PartHit> LocateAsync(string jobId, int step, string want, CancellationToken ct)
        {
            var hit = new PartHit { Id = want, ServerMs = -1, Across = Vector3.right };
            if (_busy) { hit.Reason = "busy"; return hit; }
            _busy = true;
            var sw = Stopwatch.StartNew();
            try
            {
                if (!_api.HasServer || string.IsNullOrEmpty(jobId)) hit.Reason = "no_server";
                else if (jobId.StartsWith("local:", StringComparison.Ordinal)) hit.Reason = "local_job";
                else if (_camera == null || !_camera.TryGetLatest(out var f) || !f.Intrinsics.IsValid) hit.Reason = "no_frame";
                else if (!f.HasPose) hit.Reason = "no_pose";
                else
                {
                    // everything that belongs to THIS frame is copied before the await (the source reuses the frame)
                    var k = f.Intrinsics;
                    var camPose = f.CameraPose;
                    var jpeg = await FrameJpeg.EncodeAsync(f, 1280, 80);    // CPU pixels when the frame has them: no GPU stall
                    if (jpeg == null) hit.Reason = "no_jpeg";
                    else
                    {
                        var r = await _api.FindAsync(jobId, jpeg, want != null ? new[] { want } : null, step > 0 ? step : (int?)null, _lang, ct);
                        hit.ServerMs = r.LatencyMs;
                        hit.Simulated = r.Simulated;
                        hit.Message = r.Message;
                        var found = r.Found != null && r.Found.Count > 0 ? r.Found[0] : null;
                        hit.Box2d = found?.Box2d;
                        if (found == null || found.Box2d == null || found.Box2d.Length != 4) hit.Reason = "not_seen";
                        else if (r.Simulated && !ShowSimulated) hit.Reason = "simulated";
                        else
                        {
                            hit.Id = found.Id;
                            hit.Label = found.Label;
                            hit.Confidence = found.Confidence;
                            var box = FlipV ? new[] { 1000 - found.Box2d[2], found.Box2d[1], 1000 - found.Box2d[0], found.Box2d[3] } : found.Box2d;
                            // the table height as known NOW (the scene model may have arrived during the request)
                            float tableY = HasTableY ? TableY : (_anchor != null ? _anchor.position.y : camPose.position.y - WorldMath.BelowEyeM);
                            if (WorldMath.BoxOnTable(box, k, camPose, tableY, out hit.World, out hit.SizeM, out hit.Across)) hit.Found = true;
                            else hit.Reason = "no_plane_hit";
                        }
                    }
                }
            }
            catch (HttpError e) { hit.Reason = "http_" + e.Status; }
            catch (OperationCanceledException) { hit.Reason = "cancelled"; }
            catch (Exception e) { hit.Reason = "error"; AgLog.Error("WorldModule.Locate", e.GetType().Name + ": " + e.Message); }
            finally { _busy = false; }
            hit.Ms = sw.ElapsedMilliseconds;
            return hit;
        }

        public void Dispose()
        {
            _outline.Destroy();
        }
    }
}
