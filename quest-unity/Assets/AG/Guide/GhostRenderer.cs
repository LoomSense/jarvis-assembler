using System;
using System.Collections.Generic;
using AG.Core;
using UnityEngine;
#if AG_HAS_TMP
using TMPro;
#endif

namespace AG.Guide
{
    /// <summary>
    /// The real <see cref="IGhostRenderer"/> (XR_NATIVE_SPEC §4). Everything lives under the GLB's <c>assembly</c>
    /// node, which sits under the tracked <c>AssemblyAnchor</c>, so all marks ride with the real object.
    /// One action at a time: one ghost, one path or turning arrow, one label, at most one badge.
    /// Loops: ghost breathe 0.42 Hz, path dash 0.45 Hz, ripple 0.5 Hz, turn sweep 0.8 Hz, wrong pulse 0.83 Hz x 3.
    /// Add with <c>AddComponent&lt;GhostRenderer&gt;()</c>, set <see cref="Font"/> (TMP_FontAsset with Greek).
    /// </summary>
    public sealed class GhostRenderer : MonoBehaviour, IGhostRenderer
    {
        /// <summary>TMP_FontAsset (typed Object so callers need no TMP reference).</summary>
        public UnityEngine.Object Font;
        /// <summary>Head transform for billboards and dynamic label scale; Camera.main when null.</summary>
        public Transform Viewer;

        public bool LabelsVisible { get; set; } = true;
        public bool ReducedMotion { get; set; }

        const float RefDist = 0.8f;

        AssemblyGuide _g;
        Transform _anchor, _root, _marks;
        string _lang = "en";
        GhostTracking _tracking = GhostTracking.Untracked;
        GuideStep _step;
        GuideAction _action;
        int _stepIdx = -1;
        string _ghostNode;
        float _shownAt, _feedbackAt;
        GuideFeedback _feedback;

        sealed class NodeVis
        {
            public PartNode Node;
            public Renderer[] Rends;
            public GameObject Edges;
            public PartVisualState State = PartVisualState.Hidden;
        }
        readonly Dictionary<string, NodeVis> _nodes = new Dictionary<string, NodeVis>();
        readonly Dictionary<Mesh, Mesh> _edgeCache = new Dictionary<Mesh, Mesh>();

        Material _ghostMat, _placedMat, _depthMat, _edgeMat, _dotMat, _headMat, _arcMat, _arcHaloMat, _ringMat, _rippleMatA, _rippleMatB, _ringDimMat, _previewMat;
        // marks
        readonly List<Transform> _dots = new List<Transform>();
        readonly List<float> _dotBaseA = new List<float>();
        MaterialPropertyBlock _mpb;
        Transform _head, _arc, _ring, _rippleA, _rippleB, _preview, _turnArrow;
        readonly List<Transform> _dimRings = new List<Transform>();
        float _ringR;
        Quaternion _previewTo = Quaternion.identity;
        Vector3 _previewPivot;
        float _previewAt = -1;
        Label _label, _note;
        Badge _badge;
        float _alpha = 1f;   // master alpha for lost tracking

        // ---------------------------------------------------------------- IGhostRenderer

        public void Bind(AssemblyGuide guide, Transform assemblyAnchor, string lang)
        {
            Clear();
            _g = guide;
            _anchor = assemblyAnchor;
            _lang = lang ?? "en";
            _mpb = new MaterialPropertyBlock();
            MakeMaterials();
            _root = guide.Root != null ? guide.Root.transform : new GameObject("assembly").transform;
            _root.SetParent(assemblyAnchor, false);
            _root.localPosition = Vector3.zero;
            _root.localRotation = Quaternion.identity;
            _root.gameObject.SetActive(true);
            _marks = new GameObject("guide_marks").transform;
            _marks.SetParent(_root, false);
            _nodes.Clear();
            foreach (var kv in guide.Nodes)
            {
                var vis = new NodeVis { Node = kv.Value, Rends = kv.Value.Instance != null ? kv.Value.Instance.GetComponentsInChildren<Renderer>(true) : new Renderer[0] };
                _nodes[kv.Key] = vis;
                if (kv.Value.Instance != null) kv.Value.Instance.SetActive(false);
            }
            _label = new Label(_marks, Font, "label");
            _note = new Label(_marks, Font, "note");
            _badge = new Badge(_marks, Font);
            AgLog.Ev("ghost_bind", new { job = guide.JobId, nodes = _nodes.Count });
        }

        public void ShowAction(int step, int actionIndex)
        {
            if (_g == null) return;
            int si = _g.Steps.FindIndex(s => s.N == step);
            if (si < 0) { Clear(); return; }
            var s = _g.Steps[si];
            bool stepChanged = si != _stepIdx;
            var prevStep = _stepIdx >= 0 && _stepIdx < _g.Steps.Count ? _g.Steps[_stepIdx] : null;
            _stepIdx = si;
            _step = s;
            _action = actionIndex >= 0 && actionIndex < s.Actions.Count ? s.Actions[actionIndex] : null;
            _feedback = GuideFeedback.None;
            _badge.Hide();
            _note.Hide();

            // node states: earlier steps + earlier actions placed, current ghost, rest hidden
            var placed = new HashSet<string>();
            for (int i = 0; i < si; i++) foreach (var a in _g.Steps[i].Actions) if (a.Node != null) placed.Add(a.Node);
            for (int i = 0; i < actionIndex && i < s.Actions.Count; i++) if (s.Actions[i].Node != null) placed.Add(s.Actions[i].Node);
            _ghostNode = _action?.Node;
            foreach (var kv in _nodes)
            {
                var st = kv.Key == _ghostNode ? PartVisualState.Ghost : placed.Contains(kv.Key) ? PartVisualState.Placed : PartVisualState.Hidden;
                Apply(kv.Value, st);
            }
            _root.localRotation = _tracking == GhostTracking.Untracked ? s.AssemblyRotation : Quaternion.identity;

            BuildMarks(stepChanged ? prevStep : null);
            _shownAt = Time.time;
            AgLog.Ev("ghost", new { step, action = actionIndex, node = _ghostNode, type = _action?.Type.ToString() });
        }

        public void SetPartState(string node, PartVisualState state)
        {
            if (node != null && _nodes.TryGetValue(node, out var v)) Apply(v, state);
        }

        public void ShowFeedback(GuideFeedback kind, string observedPartId = null)
        {
            if (_g == null) return;
            _feedback = kind;
            _feedbackAt = Time.time;
            var anchor = LabelAnchorLocal();
            switch (kind)
            {
                case GuideFeedback.Correct:
                    _ghostMat.SetColor("_RimColor", Tokens.Success);
                    _badge.Show(Badge.Kind.Check, _root.TransformPoint(anchor) + Vector3.up * 0.04f);
                    SetMarksVisible(false);
                    _label.Hide();
                    break;
                case GuideFeedback.Wrong:
                    BuildWrongOutline();
                    _badge.Show(Badge.Kind.Cross, _root.TransformPoint(anchor) + Vector3.up * 0.13f);
                    if (observedPartId != null && _g.Parts.TryGetValue(observedPartId, out var info) && info.Nodes.Count > 0
                        && _nodes.TryGetValue(info.Nodes[0], out var other) && other.Node.Node != _ghostNode)
                    {
                        // "DR goes here": short azure label on the observed part's own place
                        var b = other.Node.FinalBounds;
                        string name = Cap(info.Names?.Get(_lang) ?? observedPartId);
                        _note.Set(_lang == "el" ? $"{name} · πάει εδώ" : $"{name} goes here", null, null, null, Tokens.Accent);
                        _note.Place(_root, new Vector3(b.center.x, b.max.y, b.center.z), 0.06f, ViewerT());
                    }
                    break;
                case GuideFeedback.Unsure:
                    _ghostMat.SetColor("_RimColor", Tokens.Unsure);
                    _badge.Show(Badge.Kind.Diamond, _root.TransformPoint(anchor) + Vector3.up * 0.13f);
                    break;
                default:
                    _ghostMat.SetColor("_RimColor", Tokens.Accent);
                    _badge.Hide();
                    break;
            }
        }

        public void SetTracking(GhostTracking state)
        {
            if (state == _tracking) return;
            var prev = _tracking;
            _tracking = state;
            if (_root == null) return;
            if (state == GhostTracking.Lost)
            {
                Tween.Run(0.3f, Ease.OutCubic, t => { _alpha = Mathf.Lerp(1f, 0.3f, t); }, owner: this);
                _ghostMat.SetFloat("_Dash", 1);
                SetMarksVisible(false);
            }
            else
            {
                _ghostMat.SetFloat("_Dash", 0);
                Tween.Run(0.25f, Ease.OutCubic, t => { _alpha = Mathf.Lerp(0.3f, 1f, t); }, owner: this);
                if (_step != null) ShowAction(_step.N, _action?.Index ?? 0);   // re-place for the new frame (no slide)
            }
            AgLog.Ev("ghost_tracking", new { state = state.ToString(), from = prev.ToString() });
        }

        public void ShowComplete()
        {
            if (_g == null) return;
            foreach (var v in _nodes.Values) Apply(v, PartVisualState.Placed);
            ClearMarks();
            _label.Hide();
            // whole assembly rim glows green 0.5 -> 0 over 1.2 s
            var glow = GuideMaterials.Ghost(Tokens.Success.A(0), Tokens.Success, 0.5f, false);
            foreach (var v in _nodes.Values) { if (v.Node.Instance == null) continue; v.Node.Instance.SetActive(true); foreach (var r in v.Rends) r.sharedMaterial = glow; }
            Tween.Run(1.2f, Ease.OutCubic, t => glow.SetFloat("_RimStrength", Mathf.Lerp(0.5f, 0f, t)), () =>
            {
                foreach (var v in _nodes.Values) Apply(v, PartVisualState.Placed);
            }, this);
            // three green rings on the table around the footprint, 1.0x -> 1.4x, staggered 150 ms
            if (TryGetWorldBounds(out var wb))
            {
                float r0 = Mathf.Max(wb.extents.x, wb.extents.z) * 1.1f;
                for (int i = 0; i < 3; i++)
                {
                    var ring = Quad("done_ring", null, GuideMaterials.Flat(Tokens.Success, Tokens.QMarks, 2));
                    ring.position = new Vector3(wb.center.x, wb.min.y + 0.002f, wb.center.z);
                    ring.rotation = Quaternion.LookRotation(Vector3.down);
                    var mat = ring.GetComponent<Renderer>().sharedMaterial;
                    mat.SetFloat("_RingWidth", 0.006f);
                    float delay = i * 0.15f;
                    var tr = ring;
                    Tween.Run(1.2f + delay, Ease.Linear, t =>
                    {
                        float k = Mathf.Clamp01((t * (1.2f + delay) - delay) / 1.2f);
                        float e = Ease.OutCubic(k);
                        tr.localScale = Vector3.one * (2 * r0 * Mathf.Lerp(1f, 1.4f, e));   // all parents are unit-scaled
                        mat.SetFloat("_Alpha", k <= 0 ? 0 : 0.9f * (1 - e));
                    }, () => Destroy(tr.gameObject), this);
                }
            }
            AgLog.Ev("ghost_complete", new { job = _g.JobId });
        }

        public bool TryGetWorldBounds(out Bounds b)
        {
            b = default;
            if (_g == null || _root == null) return false;
            var lb = _g.FinalBounds.size.sqrMagnitude > 0 ? _g.FinalBounds : new Bounds(Vector3.zero, Vector3.one * 0.3f);
            bool first = true;
            for (int i = 0; i < 8; i++)
            {
                var c = lb.center + Vector3.Scale(lb.extents, new Vector3((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1));
                var p = _root.TransformPoint(c);
                if (first) { b = new Bounds(p, Vector3.zero); first = false; } else b.Encapsulate(p);
            }
            return true;
        }

        /// <summary>World bounds of the current action's ghost (for the hands summary's hand_at_target); false when none.</summary>
        public bool TryGetGhostWorldBounds(out Bounds b)
        {
            b = default;
            if (_root == null || _ghostNode == null || !_nodes.TryGetValue(_ghostNode, out var v)) return false;
            if (v.Node.FinalBounds.size.sqrMagnitude <= 0) return false;
            var lb = v.Node.FinalBounds;
            for (int i = 0; i < 8; i++)
            {
                var p = _root.TransformPoint(lb.center + Vector3.Scale(lb.extents, new Vector3((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1)));
                if (i == 0) b = new Bounds(p, Vector3.zero); else b.Encapsulate(p);
            }
            return true;
        }

        public void Clear()
        {
            ShowPickTargets(null);
            foreach (var v in _nodes.Values) Apply(v, PartVisualState.Hidden);
            ClearMarks();
            _label?.Hide();
            _note?.Hide();
            _badge?.Hide();
        }

        // ---------------------------------------------------------------- materials and node states

        void MakeMaterials()
        {
            if (_ghostMat != null) return;
            _ghostMat = GuideMaterials.Ghost(Tokens.Accent.A(0.14f), Tokens.Accent);
            _placedMat = GuideMaterials.Ghost(new Color(1, 1, 1, 0.05f), new Color(1, 1, 1, 0.5f), 0.35f);
            _depthMat = GuideMaterials.DepthOnly();
            var edge = Color.Lerp(Tokens.Accent, Color.white, 0.08f).A(0.85f);
            _edgeMat = GuideMaterials.Flat(edge, Tokens.QGhost - 1);
            _previewMat = GuideMaterials.Flat(Tokens.Accent.A(0.8f), Tokens.QMarks);
            _dotMat = GuideMaterials.Flat(Tokens.Accent, Tokens.QMarks, 1, 0.16f);
            _headMat = GuideMaterials.Flat(Tokens.Accent.A(0.97f), Tokens.QMarks);
            _arcMat = GuideMaterials.Flat(Tokens.Accent.A(0.95f), Tokens.QMarks);
            _arcHaloMat = GuideMaterials.Flat(Tokens.Accent.A(0.16f), Tokens.QMarks - 1);
            _ringMat = GuideMaterials.Flat(Tokens.Accent.A(0.9f), Tokens.QMarks, 2);
            _rippleMatA = GuideMaterials.Flat(Tokens.Accent, Tokens.QMarks, 2);
            _rippleMatB = GuideMaterials.Flat(Tokens.Accent, Tokens.QMarks, 2);
            _ringDimMat = GuideMaterials.Flat(Tokens.Accent.A(0.35f), Tokens.QMarks, 2);
        }

        void Apply(NodeVis v, PartVisualState st)
        {
            v.State = st;
            var go = v.Node.Instance;
            if (go == null) return;
            bool visible = st == PartVisualState.Ghost || st == PartVisualState.Placed;
            go.SetActive(visible);
            if (!visible) { if (v.Edges != null) v.Edges.SetActive(false); return; }
            go.transform.localPosition = v.Node.FinalLocalPosition;
            go.transform.localRotation = v.Node.FinalLocalRotation;
            go.transform.localScale = v.Node.FinalLocalScale;
            Material m = st == PartVisualState.Ghost ? _ghostMat : _tracking == GhostTracking.Untracked ? _placedMat : _depthMat;
            foreach (var r in v.Rends)
            {
                if (r == null) continue;
                if (r.sharedMaterials.Length > 1) { var arr = new Material[r.sharedMaterials.Length]; for (int i = 0; i < arr.Length; i++) arr[i] = m; r.sharedMaterials = arr; }
                else r.sharedMaterial = m;
                r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                r.receiveShadows = false;
            }
            if (st == PartVisualState.Ghost) EnsureEdges(v);
            if (v.Edges != null) v.Edges.SetActive(st == PartVisualState.Ghost);
        }

        void EnsureEdges(NodeVis v)
        {
            if (v.Edges != null) return;
            v.Edges = new GameObject("edges");
            v.Edges.transform.SetParent(v.Node.Instance.transform, false);
            foreach (var mf in v.Node.Instance.GetComponentsInChildren<MeshFilter>(true))
            {
                if (mf.sharedMesh == null || mf.transform.IsChildOf(v.Edges.transform)) continue;
                if (!_edgeCache.TryGetValue(mf.sharedMesh, out var em)) { em = GuideMeshes.HardEdges(mf.sharedMesh); _edgeCache[mf.sharedMesh] = em; }
                if (em == null) continue;
                var e = new GameObject("edge_" + mf.name);
                e.transform.SetParent(v.Edges.transform, false);
                // same transform as the mesh, relative to the edges container (matrix, not lossyScale: the
                // assembly may be rotated 180 deg by the step pose, which makes lossyScale report flips)
                var m = v.Edges.transform.worldToLocalMatrix * mf.transform.localToWorldMatrix;
                e.transform.localPosition = m.GetColumn(3);
                e.transform.localRotation = m.rotation;
                e.transform.localScale = m.lossyScale;
                e.AddComponent<MeshFilter>().sharedMesh = em;
                e.AddComponent<MeshRenderer>().sharedMaterial = _edgeMat;
            }
        }

        // ---------------------------------------------------------------- marks

        void ClearMarks()
        {
            if (_marks == null) return;
            for (int i = _marks.childCount - 1; i >= 0; i--)
            {
                var c = _marks.GetChild(i);
                if (_label != null && c == _label.Root) continue;
                if (_note != null && c == _note.Root) continue;
                if (_badge != null && c == _badge.Root) continue;
                Destroy(c.gameObject);
            }
            _dots.Clear(); _dotBaseA.Clear(); _dimRings.Clear();
            _head = _arc = _ring = _rippleA = _rippleB = _preview = _turnArrow = null;
            _wrongDashes.Clear();
            _previewAt = -1;
        }

        void SetMarksVisible(bool on)
        {
            if (_marks == null) return;
            for (int i = 0; i < _marks.childCount; i++)
            {
                var c = _marks.GetChild(i);
                if ((_badge != null && c == _badge.Root) || (_note != null && c == _note.Root)) continue;
                c.gameObject.SetActive(on);
            }
        }

        Transform Quad(string name, Transform parent, Material m)
        {
            var go = new GameObject(name);
            go.transform.SetParent(parent != null ? parent : _marks, false);
            go.AddComponent<MeshFilter>().sharedMesh = GuideMeshes.Quad;
            var r = go.AddComponent<MeshRenderer>();
            r.sharedMaterial = m;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            return go.transform;
        }

        Transform MeshGo(string name, Mesh mesh, Material m)
        {
            var go = new GameObject(name);
            go.transform.SetParent(_marks, false);
            go.AddComponent<MeshFilter>().sharedMesh = mesh;
            var r = go.AddComponent<MeshRenderer>();
            r.sharedMaterial = m;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            return go.transform;
        }

        Bounds GhostBoundsLocal()
        {
            if (_ghostNode != null && _nodes.TryGetValue(_ghostNode, out var v) && v.Node.FinalBounds.size.sqrMagnitude > 0) return v.Node.FinalBounds;
            if (_action?.Target != null) return new Bounds(_action.Target.Value, Vector3.one * 0.01f);
            return _g.FinalBounds;
        }

        Vector3 UpLocal() => _root.InverseTransformDirection(Vector3.up).normalized;

        /// <summary>Top centre of the ghost (world up), in the root's local frame.</summary>
        Vector3 LabelAnchorLocal()
        {
            var b = GhostBoundsLocal();
            var up = UpLocal();
            float h = Mathf.Abs(up.x) * b.extents.x + Mathf.Abs(up.y) * b.extents.y + Mathf.Abs(up.z) * b.extents.z;
            return b.center + up * h;
        }

        void BuildMarks(GuideStep prevStep)
        {
            ClearMarks();
            _label.Hide();
            if (_action == null) return;
            var a = _action;
            var b = GhostBoundsLocal();
            var up = UpLocal();
            PartInfo info = a.PartId != null && _g.Parts.TryGetValue(a.PartId, out var pi) ? pi : null;
            string kind = info?.Kind ?? "";
            bool hardware = info != null && info.IsHardware;
            bool straight = kind == "dowel" || kind == "pin" || kind == "nail";
            string amount = null;

            if (a.Type == ActionType.Turn || (prevStep != null && Quaternion.Angle(prevStep.AssemblyRotation, _step.AssemblyRotation) > 5f && a.Index == 0))
                BuildTurnPreview(prevStep);

            if (a.FromDir.HasValue && a.Type != ActionType.Turn && a.Type != ActionType.Note)
            {
                var dir = a.FromDir.Value.normalized;
                float halfAlong = Mathf.Abs(dir.x) * b.extents.x + Mathf.Abs(dir.y) * b.extents.y + Mathf.Abs(dir.z) * b.extents.z;
                var end = (hardware && a.Target.HasValue ? a.Target.Value + dir * halfAlong : b.center + dir * halfAlong) + dir * 0.02f;
                bool turnArrow = (a.Type == ActionType.Fasten || a.Type == ActionType.Tighten) && hardware && !straight;
                if (turnArrow)
                {
                    BuildPathStraight(end, dir, 0.03f);
                    float partR = Mathf.Min(Mathf.Min(b.extents.x, b.extents.y), b.extents.z);
                    BuildTurnArrow(a.Target ?? b.center, dir, Mathf.Clamp(partR + 0.01f, 0.018f, 0.04f));
                    amount = kind == "cam" ? (_lang == "el" ? "½ στροφή ως να κλειδώσει" : "Half a turn until it locks")
                                           : (_lang == "el" ? "Ως μέσα" : "All the way in");
                }
                else if (hardware || straight) BuildPathStraight(end, dir, 0.10f);
                else BuildPathArc(end + dir * 0.25f + up * 0.0f, end, dir, up);
                if (hardware && a.Target.HasValue) BuildRipple(a.Target.Value, dir, Mathf.Clamp(Mathf.Min(b.extents.x, Mathf.Min(b.extents.y, b.extents.z)), 0.003f, 0.015f));
            }

            // label: name · code [2 of 4] (+ amount chip)
            if (LabelsVisible && info != null)
            {
                string name = Cap(info.Names?.Get(_lang) ?? info.Id);
                string code = hardware ? info.Id : null;
                string count = a.CountTotal > 1 ? (_lang == "el" ? $"{a.CountIndex} από {a.CountTotal}" : $"{a.CountIndex} of {a.CountTotal}") : null;
                _label.Set(name, code, count, amount, Tokens.AccentDeep);
                // a path or arrow that comes from above would run through the pill: move it to the side
                bool fromAbove = a.FromDir.HasValue && Vector3.Dot(_root.TransformDirection(a.FromDir.Value), Vector3.up) > 0.6f;
                _label.SideShift = fromAbove ? -0.09f : 0f;
                _label.Place(_root, LabelAnchorLocal(), 0.08f, ViewerT());
            }
        }

        void BuildPathArc(Vector3 start, Vector3 end, Vector3 dir, Vector3 up)
        {
            // cubic Bezier: c1 = start + 0.30 up (scaled down for short paths), c2 = end + 0.18 along from_dir + 0.12 up
            var c1 = start + up * 0.15f;
            var c2 = end + dir * 0.12f + up * 0.08f;
            var pts = new List<Vector3>();
            for (int i = 0; i <= 200; i++)
            {
                float t = i / 200f, u = 1 - t;
                pts.Add(u * u * u * start + 3 * u * u * t * c1 + 3 * u * t * t * c2 + t * t * t * end);
            }
            PlaceDots(pts);
        }

        void BuildPathStraight(Vector3 end, Vector3 dir, float len)
        {
            var pts = new List<Vector3>();
            for (int i = 0; i <= 50; i++) pts.Add(end + dir * len * (1 - i / 50f));
            PlaceDots(pts);
        }

        void PlaceDots(List<Vector3> pts)
        {
            const float spacing = 0.012f, dotD = 0.004f;
            float acc = 0, next = 0;
            var placed = new List<Vector3>();
            for (int i = 1; i < pts.Count; i++)
            {
                float seg = Vector3.Distance(pts[i - 1], pts[i]);
                while (next <= acc + seg && seg > 0)
                {
                    placed.Add(Vector3.Lerp(pts[i - 1], pts[i], (next - acc) / seg));
                    next += spacing;
                }
                acc += seg;
            }
            // leave the last 14 mm for the arrowhead
            int n = Mathf.Max(0, placed.Count - 1);
            for (int i = 0; i < n; i++)
            {
                var d = Quad("dot", null, _dotMat);
                d.localPosition = placed[i];
                d.localScale = Vector3.one * (dotD / 0.44f);
                _dots.Add(d);
                _dotBaseA.Add(Mathf.Lerp(0.35f, 0.9f, n <= 1 ? 1 : (float)i / (n - 1)));
            }
            var tip = pts[pts.Count - 1];
            var tangent = (pts[pts.Count - 1] - pts[pts.Count - 4]).normalized;
            _head = MeshGo("arrowhead", GuideMeshes.Cone, _headMat);
            _head.localPosition = tip - tangent * 0.014f;
            _head.localRotation = Quaternion.LookRotation(tangent, Mathf.Abs(Vector3.Dot(tangent, Vector3.up)) > 0.9f ? Vector3.forward : Vector3.up);
            _head.localScale = new Vector3(0.010f, 0.010f, 0.014f);
        }

        void BuildTurnArrow(Vector3 centre, Vector3 axis, float r)
        {
            var rot = Quaternion.LookRotation(axis, Mathf.Abs(Vector3.Dot(axis, Vector3.up)) > 0.9f ? Vector3.forward : Vector3.up);
            // clockwise seen from the tool side (+axis): negative sweep about local +Z (= axis)
            _arc = MeshGo("turn_arc", GuideMeshes.ArcTube(r, 0.00175f, -290f), _arcMat);
            _arc.localPosition = centre + axis * 0.006f;
            _arc.localRotation = rot;
            var halo = MeshGo("turn_arc_halo", GuideMeshes.ArcTube(r, 0.0055f, -290f), _arcHaloMat);
            halo.SetParent(_arc, false);
            float end = -290f * Mathf.Deg2Rad;
            var endP = new Vector3(Mathf.Cos(end) * r, Mathf.Sin(end) * r, 0);
            var tan = new Vector3(Mathf.Sin(end), -Mathf.Cos(end), 0);   // direction of decreasing angle
            var head = MeshGo("turn_head", GuideMeshes.Cone, _headMat);
            head.SetParent(_arc, false);
            head.localPosition = endP;
            head.localRotation = Quaternion.LookRotation(tan, Vector3.forward);
            head.localScale = new Vector3(0.009f, 0.009f, 0.012f);
        }

        void BuildRipple(Vector3 target, Vector3 normal, float holeR)
        {
            _ringR = holeR + 0.002f;
            var rot = Quaternion.LookRotation(-normal);
            var p = target + normal * 0.0005f;
            float size = 2 * (_ringR + 0.0006f);
            _ring = Quad("hole_ring", null, _ringMat);
            _ring.localPosition = p; _ring.localRotation = rot; _ring.localScale = Vector3.one * size;
            _ringMat.SetFloat("_RingWidth", 0.0006f / size);
            _rippleA = Quad("ripple_a", null, _rippleMatA);
            _rippleB = Quad("ripple_b", null, _rippleMatB);
            foreach (var t in new[] { _rippleA, _rippleB }) { t.localPosition = p; t.localRotation = rot; t.localScale = Vector3.one * size; }
            // remaining holes of the same code in this step: static dim rings
            for (int i = _action.Index + 1; i < _step.Actions.Count; i++)
            {
                var o = _step.Actions[i];
                if (o.Code == null || o.Code != _action.Code || !o.Target.HasValue) continue;
                var d = Quad("hole_ring_next", null, _ringDimMat);
                d.localPosition = o.Target.Value + (o.FromDir ?? normal) * 0.0005f;
                d.localRotation = Quaternion.LookRotation(-(o.FromDir ?? normal));
                d.localScale = Vector3.one * size;
                _dimRings.Add(d);
            }
            _ringDimMat.SetFloat("_RingWidth", 0.0006f / size);
        }

        void BuildTurnPreview(GuideStep prevStep)
        {
            // delta in the assembly frame: from the previous step's pose to this one (or the action's own turn)
            var from = prevStep != null ? prevStep.AssemblyRotation : Quaternion.identity;
            Quaternion delta = _action.Turn.HasValue
                ? Quaternion.Inverse(_step.AssemblyRotation) * _action.Turn.Value * _step.AssemblyRotation
                : Quaternion.Inverse(from) * _step.AssemblyRotation;
            if (Quaternion.Angle(delta, Quaternion.identity) < 5f) return;
            _preview = new GameObject("turn_preview").transform;
            _preview.SetParent(_marks, false);
            _previewPivot = _g.FinalBounds.center;
            _previewTo = delta;
            foreach (var v in _nodes.Values)
            {
                if (v.State != PartVisualState.Placed || v.Node.Instance == null) continue;
                foreach (var mf in v.Node.Instance.GetComponentsInChildren<MeshFilter>(true))
                {
                    if (mf.sharedMesh == null || mf.GetComponent<MeshRenderer>() == null || mf.sharedMaterial() == _edgeMat) continue;
                    if (!_edgeCache.TryGetValue(mf.sharedMesh, out var em)) { em = GuideMeshes.HardEdges(mf.sharedMesh); _edgeCache[mf.sharedMesh] = em; }
                    if (em == null) continue;
                    var e = new GameObject("outline");
                    e.transform.SetParent(_preview, false);
                    var m = _root.worldToLocalMatrix * mf.transform.localToWorldMatrix;
                    e.transform.localPosition = m.GetColumn(3);
                    e.transform.localRotation = m.rotation;
                    e.transform.localScale = m.lossyScale;
                    e.AddComponent<MeshFilter>().sharedMesh = em;
                    e.AddComponent<MeshRenderer>().sharedMaterial = _previewMat;
                }
            }
            delta.ToAngleAxis(out float ang, out Vector3 axis);
            float diag = _g.FinalBounds.size.magnitude;
            _turnArrow = MeshGo("turn_big_arrow", GuideMeshes.ArcTube(0.6f * diag * 0.5f, 0.002f, Mathf.Min(ang, 300f)), _arcMat);
            _turnArrow.localPosition = _previewPivot;
            _turnArrow.localRotation = Quaternion.LookRotation(axis, Mathf.Abs(Vector3.Dot(axis, Vector3.up)) > 0.9f ? Vector3.forward : Vector3.up);
            var head = MeshGo("turn_big_head", GuideMeshes.Cone, _headMat);
            head.SetParent(_turnArrow, false);
            float e2 = Mathf.Min(ang, 300f) * Mathf.Deg2Rad, rr = 0.6f * diag * 0.5f;
            head.localPosition = new Vector3(Mathf.Cos(e2) * rr, Mathf.Sin(e2) * rr, 0);
            head.localRotation = Quaternion.LookRotation(new Vector3(-Mathf.Sin(e2), Mathf.Cos(e2), 0), Vector3.forward);
            head.localScale = new Vector3(0.016f, 0.016f, 0.022f);
            _previewAt = Time.time;
        }

        // ---------------------------------------------------------------- per frame

        Transform ViewerT()
        {
            if (Viewer != null) return Viewer;
            var c = Camera.main;
            return c != null ? c.transform : null;
        }

        void LateUpdate()
        {
            if (_g == null || _root == null) return;
            float now = Time.time, since = now - _shownAt;
            var viewer = ViewerT();

            // ghost: appear 350 ms (2 cm along from_dir, ease-out-expo), breathe 0.42 Hz, lost -> 30 %
            if (_ghostNode != null && _nodes.TryGetValue(_ghostNode, out var gv) && gv.Node.Instance != null)
            {
                float k = Mathf.Clamp01(since / 0.35f), e = 1 - Mathf.Pow(2, -10 * k);
                var off = (_action?.FromDir ?? Vector3.zero) * 0.02f * (1 - e);
                gv.Node.Instance.transform.localPosition = gv.Node.FinalLocalPosition + off;
                float fill = ReducedMotion ? 0.14f : 0.14f + 0.05f * Mathf.Sin(2 * Mathf.PI * 0.42f * since);
                float alpha = e * _alpha;
                float rim = 0.65f;
                if (_feedback == GuideFeedback.Correct)
                {
                    float f = Mathf.Clamp01((now - _feedbackAt) / 0.6f);
                    rim = Mathf.Lerp(1f, 0f, Ease.OutCubic(f));
                    fill *= 1 - f;
                    if (f >= 1) gv.Node.Instance.SetActive(false);
                }
                _ghostMat.SetColor("_Color", (_feedback == GuideFeedback.Correct ? Tokens.Success : Tokens.Accent).A(fill));
                _ghostMat.SetFloat("_RimStrength", rim);
                _ghostMat.SetFloat("_Alpha", alpha);
                _edgeMat.SetFloat("_Alpha", alpha * (_feedback == GuideFeedback.Correct ? 1 - Mathf.Clamp01((now - _feedbackAt) / 0.6f) : 1));
            }
            _placedMat.SetFloat("_Alpha", _alpha);

            // path: dots camera-facing; travelling dash of 4 dots, 1.6 s run + 0.6 s pause (0.45 Hz)
            if (_dots.Count > 0)
            {
                float cyc = since % 2.2f;
                float headPos = ReducedMotion || cyc > 1.6f ? -10 : Ease.InOutCubic(cyc / 1.6f) * (_dots.Count + 4) - 2;
                for (int i = 0; i < _dots.Count; i++)
                {
                    var d = _dots[i];
                    if (viewer != null) d.rotation = Quaternion.LookRotation(d.position - viewer.position);
                    bool lit = Mathf.Abs(i - headPos) < 2f;
                    float a = (lit ? 1f : _dotBaseA[i]) * _alpha * Mathf.Clamp01(since / 0.35f);
                    _mpb.SetFloat("_Alpha", a);
                    d.GetComponent<MeshRenderer>().SetPropertyBlock(_mpb);
                    d.localScale = Vector3.one * ((lit ? 0.005f : 0.004f) / 0.44f);
                }
            }
            // turning arrow sweep: 60 deg band every 1.25 s (0.8 Hz)
            if (_arc != null)
            {
                _arcMat.SetFloat("_SweepGain", ReducedMotion ? 0 : 0.3f);
                _arcMat.SetFloat("_SweepWidth", 60f / 290f);
                _arcMat.SetFloat("_SweepPos", (since % 1.25f) / 1.25f * 1.2f - 0.1f);
            }
            // hole ripple: two rings lip -> 2.2x, alpha 0.6 -> 0 in 1.2 s, every 2.0 s (0.5 Hz)
            if (_rippleA != null)
            {
                Ripple(_rippleA, _rippleMatA, since % 2.0f);
                Ripple(_rippleB, _rippleMatB, (since + 1.7f) % 2.0f - 0.3f);
            }
            // turn preview: rotate 1.6 s ease-in-out, hold 0.8 s, at most 3 times, then stay at the end pose
            if (_preview != null)
            {
                float t = now - _previewAt;
                int cycle = Mathf.FloorToInt(t / 2.6f);
                float k = ReducedMotion || cycle >= 3 ? 1f : Mathf.Clamp01((t % 2.6f) / 1.6f);
                var q = Quaternion.Slerp(Quaternion.identity, _previewTo, Ease.InOutCubic(k));
                _preview.localRotation = q;
                _preview.localPosition = _previewPivot - q * _previewPivot;
            }
            _label?.Tick(viewer, _alpha);
            _note?.Tick(viewer, _alpha);
            TickWrongOutline(viewer, now);
            TickPickTargets(viewer, now);
            _badge?.Tick(viewer, now, ReducedMotion);
        }

        void Ripple(Transform t, Material m, float phase)
        {
            if (ReducedMotion || phase < 0 || phase > 1.2f) { m.SetFloat("_Alpha", 0); return; }
            float k = phase / 1.2f, e = Ease.OutCubic(k);
            float size = 2 * (_ringR * Mathf.Lerp(1f, 2.2f, e) + 0.0006f);
            t.localScale = Vector3.one * size;
            m.SetFloat("_RingWidth", 0.0006f / size);
            m.SetFloat("_Alpha", 0.6f * (1 - k) * _alpha);
        }

        static string Cap(string s) => string.IsNullOrEmpty(s) ? s : char.ToUpper(s[0]) + s.Substring(1);

        // ---------------------------------------------------------------- wrong part: red dashed outline (spec §4.8)

        readonly List<(Transform t, Vector3 a, Vector3 b)> _wrongDashes = new List<(Transform, Vector3, Vector3)>();
        Material _wrongMat;

        /// <summary>
        /// Red dashed edges (8 mm dash, 5 mm gap, 2 mm wide) where the wrong part sits: the current action's place
        /// (the user put the observed part there), drawn from that place's own mesh. Pulses 0.6-1.0 at 0.83 Hz x 3.
        /// </summary>
        void BuildWrongOutline()
        {
            foreach (var d in _wrongDashes) if (d.t != null) Destroy(d.t.gameObject);
            _wrongDashes.Clear();
            if (_ghostNode == null || !_nodes.TryGetValue(_ghostNode, out var v) || v.Node.Instance == null) return;
            if (_wrongMat == null) _wrongMat = GuideMaterials.Flat(Tokens.Error, Tokens.QMarks + 1);
            var parent = new GameObject("wrong_outline").transform;
            parent.SetParent(_marks, false);
            const float dash = 0.008f, gap = 0.005f;
            foreach (var mf in v.Node.Instance.GetComponentsInChildren<MeshFilter>(true))
            {
                if (mf.sharedMesh == null || mf.GetComponent<MeshRenderer>() == null || mf.sharedMaterial() == _edgeMat) continue;
                if (!_edgeCache.TryGetValue(mf.sharedMesh, out var em)) { em = GuideMeshes.HardEdges(mf.sharedMesh); _edgeCache[mf.sharedMesh] = em; }
                if (em == null) continue;
                var m = _root.worldToLocalMatrix * mf.transform.localToWorldMatrix;
                var vs = em.vertices;
                var idx = em.GetIndices(0);
                for (int i = 0; i + 1 < idx.Length && _wrongDashes.Count < 600; i += 2)
                {
                    Vector3 a = m.MultiplyPoint3x4(vs[idx[i]]), b = m.MultiplyPoint3x4(vs[idx[i + 1]]);
                    float len = Vector3.Distance(a, b);
                    if (len < 0.004f) continue;
                    var dir = (b - a) / len;
                    for (float x = 0; x < len && _wrongDashes.Count < 600; x += dash + gap)
                    {
                        float x1 = Mathf.Min(len, x + dash);
                        var q = Quad("dash", parent, _wrongMat);
                        _wrongDashes.Add((q, a + dir * x, a + dir * x1));
                    }
                }
            }
            TickWrongOutline(ViewerT(), Time.time);
        }

        void TickWrongOutline(Transform viewer, float now)
        {
            if (_wrongDashes.Count == 0 || _root == null) return;
            float t = now - _feedbackAt;
            float a = ReducedMotion || t > 3.6f ? 1f : 0.8f + 0.2f * Mathf.Cos(2 * Mathf.PI * 0.83f * t);
            if (_feedback != GuideFeedback.Wrong) a = 0;
            _wrongMat.SetFloat("_Alpha", a * _alpha);
            var eye = viewer != null ? viewer.position : _root.position + Vector3.back;
            foreach (var (q, la, lb) in _wrongDashes)
            {
                if (q == null) continue;
                Vector3 wa = _root.TransformPoint(la), wb = _root.TransformPoint(lb), c = (wa + wb) * 0.5f;
                var right = wb - wa;
                float len = right.magnitude;
                right /= Mathf.Max(len, 1e-6f);
                var fwd = Vector3.ProjectOnPlane(c - eye, right);
                if (fwd.sqrMagnitude < 1e-8f) fwd = Vector3.Cross(right, Vector3.up);
                fwd.Normalize();
                q.position = c;
                q.rotation = Quaternion.LookRotation(fwd, Vector3.Cross(fwd, right));
                q.localScale = new Vector3(len, 0.002f, 1);
            }
        }

        // ---------------------------------------------------------------- pick this part (spec §4.6)

        sealed class PickVis
        {
            public Transform Root, Chevron;
            public Vector3 Base;
            public Label Label;
        }
        readonly List<PickVis> _picks = new List<PickVis>();
        Material _pickFillMat, _pickStrokeMat, _pickChevronMat;

        public void ShowPickTargets(IReadOnlyList<PickTarget> targets)
        {
            foreach (var p in _picks) if (p.Root != null) Destroy(p.Root.gameObject);
            _picks.Clear();
            if (targets == null || targets.Count == 0) return;
            if (_pickFillMat == null)
            {
                _pickFillMat = GuideMaterials.Flat(Tokens.Accent.A(0.10f), Tokens.QMarks, 1);
                _pickStrokeMat = GuideMaterials.Flat(Tokens.Accent.A(0.9f), Tokens.QMarks + 1, 2);
                _pickChevronMat = GuideMaterials.Flat(Tokens.Accent.A(0.97f), Tokens.QMarks + 1);
            }
            for (int i = 0; i < targets.Count && i < 4; i++)
            {
                var t = targets[i];
                var root = new GameObject("pick_" + (t.PartId ?? i.ToString())).transform;
                root.SetParent(transform, false);
                root.position = t.WorldPosition;
                var fp = t.FootprintM.sqrMagnitude > 0 ? t.FootprintM : new Vector2(0.06f, 0.06f);
                var size = new Vector3(fp.x + 0.02f, fp.y + 0.02f, 1);   // 2 cm larger than the footprint
                var fill = Quad("pick_fill", root, _pickFillMat);
                fill.localPosition = Vector3.up * 0.001f;
                fill.localRotation = Quaternion.LookRotation(Vector3.down);
                fill.localScale = size;
                var stroke = Quad("pick_stroke", root, _pickStrokeMat);
                stroke.localPosition = Vector3.up * 0.0012f;
                stroke.localRotation = Quaternion.LookRotation(Vector3.down);
                stroke.localScale = size;
                _pickStrokeMat.SetFloat("_RingWidth", 0.002f / Mathf.Max(size.x, size.y));
                // chevron: 1.0 deg (14 mm) cone pointing down, 6 cm above the part
                var chev = MeshGo("pick_chevron", GuideMeshes.Cone, _pickChevronMat);
                chev.SetParent(root, false);
                chev.localRotation = Quaternion.LookRotation(Vector3.down, Vector3.forward);
                chev.localScale = new Vector3(0.014f, 0.014f, 0.012f);
                var pv = new PickVis { Root = root, Chevron = chev, Base = Vector3.up * 0.072f };
                chev.localPosition = pv.Base;
                // label "Pick up · name · code"
                string name = t.Label, code = null;
                if (name == null && t.PartId != null && _g != null && _g.Parts.TryGetValue(t.PartId, out var info))
                {
                    name = Cap(info.Names?.Get(_lang) ?? info.Id);
                    if (info.IsHardware) code = info.Id;
                }
                if (LabelsVisible && name != null && i < 2)
                {
                    pv.Label = new Label(root, Font, "pick_label");
                    pv.Label.Set((_lang == "el" ? "Πάρε · " : "Pick up · ") + name, code, null, null, Tokens.AccentDeep);
                    pv.Label.Place(root, Vector3.up * 0.085f, 0.05f, ViewerT());
                }
                _picks.Add(pv);
            }
            AgLog.Ev("pick_targets", new { n = targets.Count });
        }

        void TickPickTargets(Transform viewer, float now)
        {
            foreach (var p in _picks)
            {
                if (p.Root == null) continue;
                float bob = ReducedMotion ? 0 : 0.01f * Mathf.Sin(2 * Mathf.PI * 0.5f * now);   // +-1 cm at 0.5 Hz
                p.Chevron.localPosition = p.Base + Vector3.up * bob;
                p.Label?.Tick(viewer, 1f);
            }
        }

        // ---------------------------------------------------------------- label pill with leader line

        /// <summary>Glass pill "Name · code [2 of 4]" above the part, leader line + dot to its anchor (spec §4.5).</summary>
        sealed class Label
        {
            public readonly Transform Root;
            readonly Transform _pill;
            readonly GlassQuad _glass, _chip, _chip2;
            readonly LineRenderer _line;
            readonly Transform _dot;
            Transform _frame;
            Vector3 _anchorLocal;
            /// <summary>Metres to the viewer's right (negative: left), so the pill clears a path that comes from above.</summary>
            public float SideShift;
            float _lift;
            float _height;
#if AG_HAS_TMP
            readonly TextMeshPro _name, _code, _chipText, _chip2Text;
#endif
            public Label(Transform parent, UnityEngine.Object font, string name)
            {
                Root = new GameObject(name).transform;
                Root.SetParent(parent, false);
                _pill = new GameObject("pill").transform;
                _pill.SetParent(Root, false);
                _glass = new GlassQuad(_pill, "glass", new Vector2(0.1f, 0.028f), 0.014f, Tokens.QPill, 0.008f);
                _glass.Pill();
                _chip = new GlassQuad(_pill, "chip", new Vector2(0.04f, 0.02f), 0.01f, Tokens.QPill + 1, 0f);
                _chip.Mat.SetColor("_Tint", Tokens.AccentDeep);
                _chip.Mat.SetFloat("_RimTop", 0.1f); _chip.Mat.SetFloat("_RimBottom", 0.05f); _chip.Mat.SetFloat("_Sheen", 0); _chip.Mat.SetFloat("_Shadow", 0);
                _chip.Mat.SetFloat("_AlphaCentre", 1); _chip.Mat.SetFloat("_AlphaEdge", 1);
                _chip2 = new GlassQuad(_pill, "chip2", new Vector2(0.04f, 0.02f), 0.01f, Tokens.QPill + 1, 0f);
                _chip2.Mat.SetFloat("_Fill", 0.10f); _chip2.Mat.SetFloat("_Sheen", 0); _chip2.Mat.SetFloat("_Shadow", 0);
#if AG_HAS_TMP
                var f = font as TMP_FontAsset;
                _name = Txt.Make(_pill, "name", f, Txt.EmM(1.2f, RefDist), Color.white, 0.4f, 0.03f, TextAlignmentOptions.MidlineLeft, Tokens.QText, true);
                _code = Txt.Make(_pill, "code", f, Txt.EmM(0.85f, RefDist), new Color(1, 1, 1, 0.68f), 0.2f, 0.03f, TextAlignmentOptions.MidlineLeft);
                _chipText = Txt.Make(_pill, "count", f, Txt.EmM(0.85f, RefDist), Color.white, 0.2f, 0.03f, TextAlignmentOptions.Midline, Tokens.QText + 1);
                _chip2Text = Txt.Make(_pill, "amount", f, Txt.EmM(0.85f, RefDist), Color.white, 0.3f, 0.03f, TextAlignmentOptions.Midline, Tokens.QText + 1);
                foreach (var t in new[] { _name, _code, _chipText, _chip2Text }) { t.rectTransform.pivot = new Vector2(0, 0.5f); t.enableWordWrapping = false; }
#endif
                var lg = new GameObject("leader");
                lg.transform.SetParent(Root, false);
                _line = lg.AddComponent<LineRenderer>();
                _line.useWorldSpace = true;
                _line.positionCount = 2;
                _line.widthMultiplier = 0.0012f;
                _line.sharedMaterial = GuideMaterials.Flat(new Color(1, 1, 1, 0.7f), Tokens.QPill - 1);
                _line.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                _dot = new GameObject("dot").transform;
                _dot.SetParent(Root, false);
                _dot.gameObject.AddComponent<MeshFilter>().sharedMesh = GuideMeshes.Quad;
                _dot.gameObject.AddComponent<MeshRenderer>().sharedMaterial = GuideMaterials.Flat(Color.white, Tokens.QPill, 1);
                Hide();
            }

            public void Set(string name, string code, string count, string amount, Color chipTint)
            {
#if AG_HAS_TMP
                const float pad = 0.0112f, gap = 0.006f;
                _height = Txt.EmM(2.0f, RefDist);
                _name.text = name ?? "";
                _code.text = code != null ? "· " + code : "";
                _chipText.text = count ?? "";
                _chip2Text.text = amount ?? "";
                float wName = W(_name), wCode = code != null ? W(_code) : 0, wChip = count != null ? W(_chipText) + 0.012f : 0, wChip2 = amount != null ? W(_chip2Text) + 0.012f : 0;
                float total = pad + wName + (code != null ? gap + wCode : 0) + (count != null ? gap + wChip : 0) + (amount != null ? gap + wChip2 : 0) + pad * 0.6f;
                _glass.SetSize(new Vector2(total, _height));
                _glass.Mat.SetFloat("_RadiusM", _height / 2);
                float x = -total / 2 + pad;
                _name.transform.localPosition = new Vector3(x, 0, -0.0005f); x += wName + gap;
                if (code != null) { _code.transform.localPosition = new Vector3(x, -0.0005f, -0.0005f); x += wCode + gap; }
                float chipH = _height * 0.68f;
                _chip.Go.SetActive(count != null);
                if (count != null)
                {
                    _chip.SetSize(new Vector2(wChip, chipH)); _chip.Mat.SetFloat("_RadiusM", chipH / 2);
                    _chip.Mat.SetColor("_Tint", chipTint);
                    _chip.Go.transform.localPosition = new Vector3(x + wChip / 2, 0, -0.0003f);
                    _chipText.rectTransform.pivot = new Vector2(0.5f, 0.5f);
                    _chipText.transform.localPosition = new Vector3(x + wChip / 2, 0, -0.0006f);
                    x += wChip + gap;
                }
                _chip2.Go.SetActive(amount != null);
                if (amount != null)
                {
                    _chip2.SetSize(new Vector2(wChip2, chipH)); _chip2.Mat.SetFloat("_RadiusM", chipH / 2);
                    _chip2.Go.transform.localPosition = new Vector3(x + wChip2 / 2, 0, -0.0003f);
                    _chip2Text.rectTransform.pivot = new Vector2(0.5f, 0.5f);
                    _chip2Text.transform.localPosition = new Vector3(x + wChip2 / 2, 0, -0.0006f);
                }
#endif
            }

#if AG_HAS_TMP
            static float W(TextMeshPro t) => t.GetPreferredValues(t.text).x * t.transform.localScale.x;
#endif

            public void Place(Transform frame, Vector3 anchorLocal, float lift, Transform viewer)
            {
                _frame = frame; _anchorLocal = anchorLocal; _lift = lift;
                Root.gameObject.SetActive(true);
                Tick(viewer, 1f);
            }

            public void Hide() => Root.gameObject.SetActive(false);

            public void Tick(Transform viewer, float alpha)
            {
                if (!Root.gameObject.activeSelf || _frame == null) return;
                var anchor = _frame.TransformPoint(_anchorLocal);
                float dist = viewer != null ? Vector3.Distance(viewer.position, anchor) : RefDist;
                float s = Mathf.Clamp(dist / RefDist, 0.75f, 1.5f);
                var pillPos = anchor + Vector3.up * (_lift * s + _height * 0.5f * s);
                if (viewer != null && Mathf.Abs(SideShift) > 0)
                {
                    var f = Vector3.ProjectOnPlane(anchor - viewer.position, Vector3.up);
                    if (f.sqrMagnitude > 1e-6f) pillPos += Vector3.Cross(Vector3.up, f).normalized * SideShift * s;
                }
                _pill.position = pillPos;
                if (viewer != null)
                {
                    var fwd = Vector3.ProjectOnPlane(pillPos - viewer.position, Vector3.up);
                    if (fwd.sqrMagnitude > 1e-6f) _pill.rotation = Quaternion.LookRotation(fwd, Vector3.up);
                    _dot.rotation = Quaternion.LookRotation(anchor - viewer.position);
                }
                _pill.localScale = Vector3.one * s;   // parents are unit-scaled (never divide by lossyScale: rotations flip it)
                _dot.position = anchor;
                _dot.localScale = Vector3.one * 0.003f * s;
                _line.SetPosition(0, pillPos - Vector3.up * _height * 0.5f * s);
                _line.SetPosition(1, anchor);
                _glass.Mat.SetFloat("_Alpha", alpha);
            }
        }

        // ---------------------------------------------------------------- status badges

        /// <summary>Check (success circle), cross (error octagon), "?" (unsure diamond): 1.6 deg, colour + shape (spec §4.7-4.9).</summary>
        sealed class Badge
        {
            public enum Kind { Check, Cross, Diamond }
            public readonly Transform Root;
            readonly Transform _disc;
            readonly Material _mat, _barMat;
            readonly Transform _bar1, _bar2;
            Kind _kind;
            float _at = -10;
#if AG_HAS_TMP
            readonly TextMeshPro _q;
#endif
            public Badge(Transform parent, UnityEngine.Object font)
            {
                Root = new GameObject("badge").transform;
                Root.SetParent(parent, false);
                _disc = new GameObject("shape").transform;
                _disc.SetParent(Root, false);
                _disc.gameObject.AddComponent<MeshFilter>().sharedMesh = GuideMeshes.Quad;
                _mat = GuideMaterials.Flat(Tokens.Success, Tokens.QBadge, 1);
                _disc.gameObject.AddComponent<MeshRenderer>().sharedMaterial = _mat;
                _barMat = GuideMaterials.Flat(new Color(0.05f, 0.06f, 0.07f, 1f), Tokens.QBadge + 1);
                _bar1 = Bar("bar1"); _bar2 = Bar("bar2");
#if AG_HAS_TMP
                _q = Txt.Make(Root, "q", font as TMP_FontAsset, 0.016f, new Color(0.05f, 0.06f, 0.07f, 1f), 0.03f, 0.03f, TextAlignmentOptions.Center, Tokens.QBadge + 1, true);
                _q.rectTransform.pivot = new Vector2(0.5f, 0.5f);
                _q.text = "?";
                _q.transform.localPosition = new Vector3(0, 0, -0.001f);
#endif
                Hide();
            }

            Transform Bar(string n)
            {
                var t = new GameObject(n).transform;
                t.SetParent(Root, false);
                t.gameObject.AddComponent<MeshFilter>().sharedMesh = GuideMeshes.Quad;
                t.gameObject.AddComponent<MeshRenderer>().sharedMaterial = _barMat;
                return t;
            }

            public void Show(Kind k, Vector3 worldPos)
            {
                _kind = k;
                _at = Time.time;
                Root.position = worldPos;
                Root.gameObject.SetActive(true);
                const float d = 0.0223f;   // 1.6 deg at 0.8 m
                _disc.localScale = Vector3.one * d;
                _mat.SetColor("_Color", k == Kind.Check ? Tokens.Success : k == Kind.Cross ? Tokens.Error : Tokens.Unsure);
                _mat.SetFloat("_Shape", k == Kind.Check ? 1 : k == Kind.Cross ? 3 : 4);
                bool bars = k != Kind.Diamond;
                _bar1.gameObject.SetActive(bars); _bar2.gameObject.SetActive(bars);
#if AG_HAS_TMP
                _q.gameObject.SetActive(!bars);
#endif
                if (k == Kind.Check)
                {
                    // short stroke down-right, long stroke up-right
                    Place(_bar1, new Vector2(-0.0035f, -0.0010f), 0.0070f, -45f);
                    Place(_bar2, new Vector2(0.0022f, 0.0012f), 0.0120f, 50f);
                }
                else if (k == Kind.Cross)
                {
                    Place(_bar1, Vector2.zero, 0.0120f, 45f);
                    Place(_bar2, Vector2.zero, 0.0120f, -45f);
                }
            }

            static void Place(Transform t, Vector2 c, float len, float deg)
            {
                t.localPosition = new Vector3(c.x, c.y, -0.0005f);
                t.localRotation = Quaternion.Euler(0, 0, deg);
                t.localScale = new Vector3(len, 0.0022f, 1);
            }

            public void Hide() { Root.gameObject.SetActive(false); _at = -10; }

            public void Tick(Transform viewer, float now, bool reduced)
            {
                if (!Root.gameObject.activeSelf) return;
                float t = now - _at;
                if (viewer != null) Root.rotation = Quaternion.LookRotation(Root.position - viewer.position, Vector3.up);
                // UI spring pop 0.85 -> 1.0 (no visible overshoot)
                float pop = Mathf.Lerp(0.85f, 1f, Mathf.Min(1f, Ease.Spring(Mathf.Clamp01(t / 0.45f))));
                Root.localScale = Vector3.one * pop;
                float a = 1f;
                if (_kind == Kind.Check) { a = t < 1.2f ? 1f : 1f - Mathf.Clamp01((t - 1.2f) / 0.3f); if (t > 1.5f) { Hide(); return; } }
                else if (_kind == Kind.Cross && !reduced && t < 3.6f) a = 0.8f + 0.2f * Mathf.Cos(2 * Mathf.PI * 0.83f * t);   // 0.6..1.0 band, 3 cycles
                _mat.SetFloat("_Alpha", a);
                _barMat.SetFloat("_Alpha", a);
            }
        }
    }

    static class MeshFilterExt
    {
        public static Material sharedMaterial(this MeshFilter mf) { var r = mf.GetComponent<Renderer>(); return r != null ? r.sharedMaterial : null; }
    }
}
