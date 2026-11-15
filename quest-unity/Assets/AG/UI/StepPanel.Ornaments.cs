using System.Collections.Generic;
using AG.Guide;
using UnityEngine;
#if AG_HAS_TMP
using TMPro;
#endif

namespace AG.UI
{
    /// <summary>
    /// Ornaments on the window's outer edge (the edge away from the object, spec §2.5): the camera preview
    /// (12 x 9 cm image, last perception box, "camera live · 30 fps") on top and the parts card (0.14 x 0.18 m,
    /// "PICK UP", a turntable copy of the part's mesh at 20 deg/s, name, code and count) below. Both are children of
    /// the window, so they are world-locked and fade with it. Plus the microphone glyph and state of the toolbar.
    /// </summary>
    public sealed partial class StepPanel
    {
        static readonly Vector2 PartsSize = new Vector2(0.14f, 0.18f), PreviewSize = new Vector2(0.14f, 0.125f);
        const float OrnGap = 0.012f;

        GlassQuad _partsCard, _previewCard;
        Transform _turnRoot, _turnPivot, _previewImg, _previewDot;
        readonly Transform[] _boxBars = new Transform[4];
        Material _previewMat, _boxMat, _dotMat, _modelMat, _modelEdgeMat;
        GameObject _modelSrc;
        bool _partsOn, _previewOn;
        float _previewAspect = 4f / 3f;
        Rect? _box;
#if AG_HAS_TMP
        TextMeshPro _partsLabel, _partsName, _partsCode, _previewCaption;
#endif

        /// <summary>Camera preview ornament on/off (launch param <c>hud</c>, long-press on Repeat, key P).</summary>
        public bool PreviewVisible { get; set; } = true;

        float Outer => LeftHanded ? -1f : 1f;

        void BuildPartsCard()
        {
            _partsCard = new GlassQuad(_content, "parts_card", PartsSize, 0.018f, Tokens.QGlass, 0.016f);
            _turnRoot = new GameObject("turntable").transform;
            _turnRoot.SetParent(_partsCard.Go.transform.parent, false);
            _turnPivot = new GameObject("pivot").transform;
            _turnPivot.SetParent(_turnRoot, false);
            _modelMat = GuideMaterials.Ghost(new Color(1, 1, 1, 0.20f), Tokens.Accent, 0.75f, true);
            _modelMat.renderQueue = Tokens.QText - 2;
            _modelEdgeMat = GuideMaterials.Flat(Color.Lerp(Tokens.Accent, Color.white, 0.3f).A(0.9f), Tokens.QText - 1);
#if AG_HAS_TMP
            _partsLabel = Txt.Make(_content, "parts_label", _font, Txt.EmM(0.85f, D), new Color(1, 1, 1, 0.68f), 0.12f, 0.02f);
            _partsLabel.characterSpacing = 6f;
            _partsName = Txt.Make(_content, "parts_name", _font, Txt.EmM(1.0f, D), Color.white, 0.12f, 0.03f, TextAlignmentOptions.TopLeft, Tokens.QText, true);
            _partsName.maxVisibleLines = 2;
            _partsCode = Txt.Make(_content, "parts_code", _font, Txt.EmM(0.85f, D), new Color(1, 1, 1, 0.68f), 0.12f, 0.02f);
#endif
        }

        void BuildPreview()
        {
            _previewCard = new GlassQuad(_content, "camera_card", PreviewSize, 0.016f, Tokens.QGlass, 0.016f);
            _previewMat = GuideMaterials.Flat(Color.white, Tokens.QGlass + 2);
            _previewMat.SetFloat("_Image", 1);
            _previewImg = Quad("camera_image", _previewMat);
            _boxMat = GuideMaterials.Flat(Tokens.Accent, Tokens.QGlass + 3);
            for (int i = 0; i < 4; i++) _boxBars[i] = Quad("camera_box", _boxMat);
            _dotMat = GuideMaterials.Flat(Tokens.Success, Tokens.QText, 1);
            _previewDot = Quad("camera_dot", _dotMat);
#if AG_HAS_TMP
            _previewCaption = Txt.Make(_content, "camera_caption", _font, Txt.EmM(0.85f, D), new Color(1, 1, 1, 0.68f), 0.12f, 0.02f, TextAlignmentOptions.MidlineLeft);
            _previewCaption.rectTransform.pivot = new Vector2(0, 0.5f);
            _previewCaption.enableWordWrapping = false;
#endif
            SetCameraPreview(null, 0, null);
        }

        /// <summary>
        /// Latest camera image (call at ~10 Hz; the texture is only referenced). <paramref name="box01"/>: the last
        /// perception / identify box in image UV (x right, y up from the bottom), or null. Texture null = "no camera".
        /// </summary>
        public void SetCameraPreview(Texture tex, float fps, Rect? box01)
        {
            _previewMat.SetTexture("_MainTex", tex);
            if (tex != null && tex.height > 0) _previewAspect = (float)tex.width / tex.height;
            _box = box01;
            _previewOn = PreviewVisible;
#if AG_HAS_TMP
            _previewCaption.text = tex != null ? L($"camera live · {Mathf.RoundToInt(fps)} fps", $"κάμερα ζωντανά · {Mathf.RoundToInt(fps)} fps")
                                               : L("no camera image", "χωρίς εικόνα κάμερας");
#endif
            _dotMat.SetColor("_Color", tex != null ? Tokens.Success : Tokens.Unsure);
            _previewImg.gameObject.SetActive(tex != null);
            LayoutOrnaments(_compact ? Compact : Full, _compact);
        }

        void SetPartsCard(string name, string code, string count, GameObject model)
        {
            _partsOn = !string.IsNullOrEmpty(name);
#if AG_HAS_TMP
            _partsLabel.text = Txt.UpperNoTonos(L("Pick up", "Πάρε"));
            Txt.SetEllipsized(_partsName, name ?? "");
            _partsCode.text = code != null && count != null ? $"{code} · {count}" : code ?? count ?? "";
#endif
            if (model != _modelSrc) BuildTurntable(model);
            _turnRoot.gameObject.SetActive(_partsOn && _modelSrc != null);
        }

        void BuildTurntable(GameObject src)
        {
            for (int i = _turnPivot.childCount - 1; i >= 0; i--) Destroy(_turnPivot.GetChild(i).gameObject);
            _modelSrc = src;
            if (src == null) return;
            // the part as it sits in the assembly (rotation and scale, not position), centred and fitted to 9 cm
            var node = src.transform;
            var orient = Matrix4x4.TRS(Vector3.zero, node.localRotation, node.localScale) * node.worldToLocalMatrix;
            var parts = new List<(Mesh mesh, Matrix4x4 m)>();
            var b = new Bounds();
            bool first = true;
            foreach (var mf in src.GetComponentsInChildren<MeshFilter>(true))
            {
                if (mf.sharedMesh == null || mf.GetComponent<MeshRenderer>() == null || mf.sharedMesh.GetTopology(0) != MeshTopology.Triangles) continue;
                var m = orient * mf.transform.localToWorldMatrix;
                var mb = mf.sharedMesh.bounds;
                for (int c = 0; c < 8; c++)
                {
                    var p = m.MultiplyPoint3x4(mb.center + Vector3.Scale(mb.extents, new Vector3((c & 1) == 0 ? -1 : 1, (c & 2) == 0 ? -1 : 1, (c & 4) == 0 ? -1 : 1)));
                    if (first) { b = new Bounds(p, Vector3.zero); first = false; } else b.Encapsulate(p);
                }
                parts.Add((mf.sharedMesh, m));
            }
            if (parts.Count == 0) { _modelSrc = null; return; }
            float k = 0.085f / Mathf.Max(1e-4f, Mathf.Max(b.size.x, Mathf.Max(b.size.y, b.size.z)));
            foreach (var (mesh, m) in parts)
            {
                var full = Matrix4x4.Scale(Vector3.one * k) * Matrix4x4.Translate(-b.center) * m;
                var go = new GameObject("model");
                go.transform.SetParent(_turnPivot, false);
                go.transform.localPosition = full.GetColumn(3);
                go.transform.localRotation = full.rotation;
                go.transform.localScale = full.lossyScale;
                go.AddComponent<MeshFilter>().sharedMesh = mesh;
                var r = go.AddComponent<MeshRenderer>();
                r.sharedMaterial = _modelMat;
                r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                var em = GuideMeshes.HardEdges(mesh);
                if (em == null) continue;
                var e = new GameObject("edges");
                e.transform.SetParent(go.transform, false);
                e.AddComponent<MeshFilter>().sharedMesh = em;
                e.AddComponent<MeshRenderer>().sharedMaterial = _modelEdgeMat;
            }
        }

        void LayoutOrnaments(Vector2 size, bool compact)
        {
            if (_partsCard == null || _previewCard == null) return;
            float x = Outer * (size.x / 2 + OrnGap + PartsSize.x / 2), top = size.y / 2;
            bool parts = _partsOn && !compact, preview = _previewOn;
            _partsCard.Go.SetActive(parts);
            _turnRoot.gameObject.SetActive(parts && _modelSrc != null);
#if AG_HAS_TMP
            _partsLabel.gameObject.SetActive(parts); _partsName.gameObject.SetActive(parts); _partsCode.gameObject.SetActive(parts);
            _previewCaption.gameObject.SetActive(preview);
#endif
            _previewCard.Go.SetActive(preview);
            _previewImg.gameObject.SetActive(preview && _previewMat.GetTexture("_MainTex") != null);
            _previewDot.gameObject.SetActive(preview);
            // preview on top (top-aligned with the window; centred beside a compact card), parts card below
            float py = compact ? 0 : top - PreviewSize.y / 2;
            var pc = new Vector3(x, py, OrnamentZ);
            _previewCard.Go.transform.localPosition = pc;
            float imgW = 0.12f, imgH = imgW / Mathf.Max(0.5f, _previewAspect);
            if (imgH > 0.09f) { imgH = 0.09f; imgW = imgH * _previewAspect; }
            var ic = pc + new Vector3(0, PreviewSize.y / 2 - 0.01f - 0.045f, -0.0004f);
            _previewImg.localPosition = ic;
            _previewImg.localScale = new Vector3(imgW, imgH, 1);
            bool box = preview && _box.HasValue && _previewImg.gameObject.activeSelf;
            for (int i = 0; i < 4; i++) _boxBars[i].gameObject.SetActive(box);
            if (box)
            {
                var r = _box.Value;
                float x0 = ic.x - imgW / 2 + r.xMin * imgW, x1 = ic.x - imgW / 2 + r.xMax * imgW;
                float y0 = ic.y - imgH / 2 + r.yMin * imgH, y1 = ic.y - imgH / 2 + r.yMax * imgH;
                const float t = 0.0012f;
                void Bar(int i, float cx, float cy, float w, float h) { _boxBars[i].localPosition = new Vector3(cx, cy, ic.z - 0.0003f); _boxBars[i].localScale = new Vector3(w, h, 1); }
                Bar(0, (x0 + x1) / 2, y1, x1 - x0, t); Bar(1, (x0 + x1) / 2, y0, x1 - x0, t);
                Bar(2, x0, (y0 + y1) / 2, t, y1 - y0); Bar(3, x1, (y0 + y1) / 2, t, y1 - y0);
            }
            float capY = pc.y - PreviewSize.y / 2 + 0.014f;
            _previewDot.localPosition = new Vector3(x - PreviewSize.x / 2 + 0.014f, capY, OrnamentZ - 0.0006f);
            _previewDot.localScale = Vector3.one * 0.006f;
#if AG_HAS_TMP
            _previewCaption.transform.localPosition = new Vector3(x - PreviewSize.x / 2 + 0.022f, capY, OrnamentZ - 0.0006f);
#endif
            float partsY = compact ? 0 : preview ? top - PreviewSize.y - OrnGap - PartsSize.y / 2 : 0;
            var c = new Vector3(x, partsY, OrnamentZ);
            _partsCard.Go.transform.localPosition = c;
            float left = x - PartsSize.x / 2 + 0.012f, ctop = partsY + PartsSize.y / 2;
#if AG_HAS_TMP
            _partsLabel.transform.localPosition = new Vector3(left, ctop - 0.010f, OrnamentZ - 0.0006f);
            _partsName.transform.localPosition = new Vector3(left, ctop - 0.118f, OrnamentZ - 0.0006f);
            _partsCode.transform.localPosition = new Vector3(left, ctop - 0.150f, OrnamentZ - 0.0006f);
#endif
            _turnRoot.localPosition = new Vector3(x, ctop - 0.070f, OrnamentZ - 0.05f);
        }

        float _turnDeg = 35f;
        void TickOrnaments(float dt)
        {
            if (_turnRoot != null && _turnRoot.gameObject.activeInHierarchy)
            {
                if (!ReducedMotion) _turnDeg = (_turnDeg + 20f * dt) % 360f;   // 20 deg/s turntable
                _turnRoot.localRotation = Quaternion.Euler(-18f, 0, 0);       // seen a little from above
                _turnPivot.localRotation = Quaternion.Euler(0, _turnDeg, 0);
                float a = _alpha * _fade;
                _modelMat.SetFloat("_Alpha", a);
                _modelEdgeMat.SetFloat("_Alpha", a);
            }
            if (_micGlyph != null)
            {
                var c = _mic == MicState.Listening ? Tokens.Error : _mic == MicState.Thinking ? Tokens.Unsure : _mic == MicState.Off ? new Color(1, 1, 1, 0.4f) : Color.white;
                _micMat.SetColor("_Color", c);
                // level ring: scale with the voice level (driven by speech, not a timed loop)
                float lvl = _mic == MicState.Listening ? Mathf.Clamp01(_micLevel * 8f) : 0f;
                _micRing.gameObject.SetActive(_mic == MicState.Listening);
                _micRing.localScale = Vector3.one * (0.014f + 0.008f * lvl);
            }
        }

        // ---------------------------------------------------------------- microphone

        MicState _mic = MicState.Idle;
        float _micLevel;
        Transform _micGlyph, _micRing;
        Material _micMat, _micRingMat;

        string MicLabel() => _mic switch
        {
            MicState.Listening => L("Listening", "Ακούω"),
            MicState.Thinking => L("One sec", "Μισό"),
            _ => L("Speak", "Μίλα"),
        };

        string LongestMicLabel() => Lang == "el" ? "Ακούω" : "Listening";

        public void SetMicState(MicState state, float level = 0)
        {
            _micLevel = level;
            if (state == _mic) return;
            _mic = state;
#if AG_HAS_TMP
            foreach (var b in _buttons) if (b.Id == PanelButton.Mic && b.Text != null) b.Text.text = MicLabel();
#endif
        }

        void MakeMicGlyph(Btn btn)
        {
            if (_micMat == null)
            {
                _micMat = GuideMaterials.Flat(Color.white, Tokens.QText + 2, 1);
                _micRingMat = GuideMaterials.Flat(Tokens.Error.A(0.7f), Tokens.QText + 1, 2);
                _micRingMat.SetFloat("_RingWidth", 0.06f);
            }
            var g = new GameObject("mic_glyph").transform;
            g.SetParent(_content, false);
            var body = Quad("mic_body", _micMat, g);   // capsule head: an ellipse
            body.localPosition = new Vector3(0, 0.0022f, 0);
            body.localScale = new Vector3(0.0048f, 0.0078f, 1);
            var barMat = GuideMaterials.Flat(Color.white, Tokens.QText + 2);
            var stem = Quad("mic_stem", barMat, g);
            stem.localPosition = new Vector3(0, -0.0034f, 0);
            stem.localScale = new Vector3(0.0009f, 0.0030f, 1);
            var foot = Quad("mic_foot", barMat, g);
            foot.localPosition = new Vector3(0, -0.0050f, 0);
            foot.localScale = new Vector3(0.0045f, 0.0009f, 1);
            _micRing = Quad("mic_level", _micRingMat, g);
            _micRing.localPosition = new Vector3(0, 0, 0.0002f);
            _micGlyph = g;
            btn.Glyph = g;
        }
    }
}
