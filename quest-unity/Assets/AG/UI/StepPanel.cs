using System;
using System.Collections.Generic;
using AG.Core;
using AG.Guide;
using UnityEngine;
#if AG_HAS_TMP
using TMPro;
#endif

namespace AG.UI
{
    /// <summary>
    /// The step window (XR_NATIVE_SPEC §2.4-2.6, §3.1, §5.2): dark glass 0.52 x 0.34 m with continuous corners, rim
    /// light and sheen (shader AG/Glass); left column status line + instruction (max 2 lines) + body + chips, right
    /// column the guide drawing as white line art; a 1.7 mm step progress bar; a toolbar ornament (glass capsule,
    /// 14 mm in front, overlapping the bottom edge) with Back, Repeat, Where am I and the one primary Next; a grab
    /// bar; a status pill above. Coach cards use the compact 0.36 x 0.20 m window.
    ///
    /// Placement: beside the tracked object on the user's right (opposite the default parts pile), inner edge 0.10 m
    /// beyond the object's bounds, centre at object top + 0.12 m clamped to 10-20 deg below eye level, 0.6-0.95 m
    /// from the eyes, yaw only + 8 deg back tilt; follows with a critically damped spring (0.6 s) and a 3 cm dead
    /// band; when the user walks around (> 35 deg) it fades out, moves and fades in. Never head-locked.
    ///
    /// Input: <see cref="Pointer"/> with a ray and the pinch state (AppRoot feeds hands/controllers/mouse).
    /// Hover 1.03, press 0.97, tick at pinch down, action at release.
    /// </summary>
    public sealed class StepPanel : MonoBehaviour, IStepPanel
    {
        public UiTheme Theme;
        public string Lang = "en";
        public bool LeftHanded;
        public AudioSource Audio;

        public event Action<PanelButton> Pressed;

        const float D = 0.8f;                       // design distance for angular sizes
        static readonly Vector2 Full = new Vector2(0.52f, 0.34f), Compact = new Vector2(0.36f, 0.20f);
        const float Pad = 0.022f, ToolbarH = 0.056f, BtnH = 0.040f, BtnGap = 0.012f, OrnamentZ = -0.014f;

        Transform _content;
        GlassQuad _window, _toolbar, _pill, _well, _grab;
        Transform _progressTrack, _progressFill, _statusShape, _pillShape, _crop;
        Material _progressTrackMat, _progressFillMat, _statusMat, _pillShapeMat, _cropMat, _grabMat;
        readonly List<Btn> _buttons = new List<Btn>();
        readonly List<GlassQuad> _chips = new List<GlassQuad>();
        bool _compact;
        float _alpha = 1f, _fade = 1f;
#if AG_HAS_TMP
        TextMeshPro _status, _title, _body, _page, _pillText;
        readonly List<TextMeshPro> _chipTexts = new List<TextMeshPro>();
        TMP_FontAsset _font;
#endif

        sealed class Btn
        {
            public PanelButton Id;
            public GlassQuad Glass;
            public Vector2 Size;
            public bool Primary, Hover, Down;
            public float Scale = 1f, ScaleVel;
            public float HoverAt;
#if AG_HAS_TMP
            public TextMeshPro Text;
#endif
        }

        // ------------------------------------------------------------------ build

        void Awake()
        {
#if AG_HAS_TMP
            _font = Theme != null ? Theme.FontAsset as TMP_FontAsset : null;
#endif
            if (Audio == null) { Audio = gameObject.AddComponent<AudioSource>(); Audio.spatialBlend = 0.8f; Audio.playOnAwake = false; }
            _content = new GameObject("content").transform;
            _content.SetParent(transform, false);
            _window = new GlassQuad(_content, "window", Full, 0.024f, Tokens.QGlass, 0.024f);
            _progressTrackMat = GuideMaterials.Flat(new Color(1, 1, 1, 0.12f), Tokens.QGlass + 1);
            _progressFillMat = GuideMaterials.Flat(Tokens.Accent, Tokens.QGlass + 2);
            _progressTrack = Quad("progress_track", _progressTrackMat);
            _progressFill = Quad("progress_fill", _progressFillMat);
            _statusMat = GuideMaterials.Flat(Tokens.Accent, Tokens.QText, 1);
            _statusShape = Quad("status_shape", _statusMat);
            _well = new GlassQuad(_content, "image_well", new Vector2(0.18f, 0.24f), 0.014f, Tokens.QGlass + 1, 0f);
            _well.Mat.SetFloat("_Fill", 0.04f); _well.Mat.SetFloat("_Sheen", 0); _well.Mat.SetFloat("_Shadow", 0);
            _well.Mat.SetFloat("_AlphaCentre", 0.35f); _well.Mat.SetFloat("_AlphaEdge", 0.35f); _well.Mat.SetFloat("_RimTop", 0.10f); _well.Mat.SetFloat("_RimBottom", 0.05f);
            _cropMat = GuideMaterials.Flat(Color.white, Tokens.QGlass + 3);
            _cropMat.SetFloat("_LineArt", 1);
            _crop = Quad("crop", _cropMat);
#if AG_HAS_TMP
            _status = Txt.Make(_content, "status", _font, Txt.EmM(0.85f, D), new Color(1, 1, 1, 0.68f), 0.3f, 0.02f);
            _status.characterSpacing = 6f;   // +0.06 em
            _title = Txt.Make(_content, "title", _font, Txt.EmM(2.0f, D), Color.white, 0.29f, 0.075f, TextAlignmentOptions.TopLeft, Tokens.QText, true);
            _title.enableAutoSizing = true;
            _title.fontSizeMax = Txt.EmM(2.0f, D) * 100f;
            _title.fontSizeMin = Txt.EmM(1.25f, D) * 100f;
            _title.lineSpacing = -8f;
            _title.maxVisibleLines = 2;
            _body = Txt.Make(_content, "body", _font, Txt.EmM(1.25f, D), new Color(1, 1, 1, 0.88f), 0.29f, 0.05f);
            _body.maxVisibleLines = 2;
            _page = Txt.Make(_content, "page", _font, Txt.EmM(0.85f, D), new Color(1, 1, 1, 0.68f), 0.1f, 0.02f);
#endif
            _toolbar = new GlassQuad(_content, "toolbar", new Vector2(0.3f, ToolbarH), ToolbarH / 2, Tokens.QPill, 0.016f);
            _toolbar.Pill();
            _grab = new GlassQuad(_content, "grab_bar", new Vector2(0.06f, 0.006f), 0.003f, Tokens.QPill, 0f);
            _grab.Mat.SetColor("_Tint", new Color(1, 1, 1, 1)); _grab.Mat.SetFloat("_AlphaCentre", 0.35f); _grab.Mat.SetFloat("_AlphaEdge", 0.35f);
            _grab.Mat.SetFloat("_Sheen", 0); _grab.Mat.SetFloat("_RimTop", 0); _grab.Mat.SetFloat("_RimBottom", 0); _grab.Mat.SetFloat("_Shadow", 0);
            _pill = new GlassQuad(_content, "status_pill", new Vector2(0.2f, 0.028f), 0.014f, Tokens.QPill, 0.008f);
            _pill.Pill();
            _pillShapeMat = GuideMaterials.Flat(Tokens.Unsure, Tokens.QText, 4);
            _pillShape = Quad("pill_shape", _pillShapeMat);
#if AG_HAS_TMP
            _pillText = Txt.Make(_content, "pill_text", _font, Txt.EmM(0.85f, D), Color.white, 0.4f, 0.03f, TextAlignmentOptions.MidlineLeft);
            _pillText.rectTransform.pivot = new Vector2(0, 0.5f);
            _pillText.enableWordWrapping = false;
#endif
            SetStatusPill(null);
            SetButtons(new[] { PanelButton.Back, PanelButton.Repeat, PanelButton.WhereAmI, PanelButton.Next }, PanelButton.Next);
            Layout(false);
            _placed = false;
        }

        Transform Quad(string name, Material m, Transform parent = null)
        {
            var go = new GameObject(name);
            go.transform.SetParent(parent != null ? parent : _content, false);
            go.AddComponent<MeshFilter>().sharedMesh = GuideMeshes.Quad;
            var r = go.AddComponent<MeshRenderer>();
            r.sharedMaterial = m;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            return go.transform;
        }

        string L(string en, string el) => Lang == "el" ? el : en;

        string Label(PanelButton b, bool isLast) => b switch
        {
            PanelButton.Next => isLast ? L("Finish", "Τέλος") : L("Next  ›", "Επόμενο  ›"),
            PanelButton.Back => L("‹  Back", "‹  Πίσω"),
            PanelButton.Repeat => L("Repeat", "Ξανά"),
            PanelButton.WhereAmI => L("Where am I", "Πού είμαι"),
            PanelButton.Retry => L("Retry", "Ξανά"),
            PanelButton.Cancel => L("Cancel", "Άκυρο"),
            PanelButton.ShowPart => L("Show part", "Δείξε"),
            _ => L("Rescan", "Σάρωση"),
        };

        public void SetButtons(PanelButton[] buttons, PanelButton? primary = null)
        {
            foreach (var b in _buttons) { Destroy(b.Glass.Go); }
#if AG_HAS_TMP
            foreach (var b in _buttons) if (b.Text != null) Destroy(b.Text.gameObject);
#endif
            _buttons.Clear();
            if (buttons == null || buttons.Length == 0) { _toolbar.Go.SetActive(false); _grab.Go.SetActive(!_compact); return; }
            _toolbar.Go.SetActive(true);
            foreach (var id in buttons)
            {
                if (_buttons.Count >= 4) break;   // spec: at most 4 items
                bool prim = primary.HasValue && primary.Value == id;
                var btn = new Btn { Id = id, Primary = prim };
                btn.Glass = new GlassQuad(_content, "btn_" + id, new Vector2(0.06f, BtnH), BtnH / 2, Tokens.QPill + 1, 0f);
                btn.Glass.Pill(0.16f, 0.08f, 0.001f);
                if (prim) { btn.Glass.Mat.SetColor("_Tint", Tokens.AccentDeep); btn.Glass.Mat.SetFloat("_AlphaCentre", 1f); btn.Glass.Mat.SetFloat("_AlphaEdge", 1f); }
                else { btn.Glass.Mat.SetFloat("_Fill", 0.10f); btn.Glass.Mat.SetFloat("_AlphaCentre", 0.0f); btn.Glass.Mat.SetFloat("_AlphaEdge", 0.0f); }
                btn.Glass.Mat.SetFloat("_Sheen", 0.03f); btn.Glass.Mat.SetFloat("_Shadow", 0);
#if AG_HAS_TMP
                btn.Text = Txt.Make(_content, "btn_text_" + id, _font, Txt.EmM(1.0f, D), Color.white, 0.12f, BtnH, TextAlignmentOptions.Center, Tokens.QText + 2, true);
                btn.Text.rectTransform.pivot = new Vector2(0.5f, 0.5f);
                btn.Text.enableWordWrapping = false;
                btn.Text.text = Label(id, false);
                float w = btn.Text.GetPreferredValues(btn.Text.text).x * btn.Text.transform.localScale.x + 0.024f;
#else
                float w = 0.07f;
#endif
                btn.Size = new Vector2(Mathf.Max(prim ? 0.08f : 0.06f, w), prim ? 0.044f : BtnH);
                btn.Glass.SetSize(btn.Size);
                btn.Glass.Mat.SetFloat("_RadiusM", btn.Size.y / 2);
                _buttons.Add(btn);
            }
            Layout(_compact);
        }

        void Layout(bool compact)
        {
            _compact = compact;
            var size = compact ? Compact : Full;
            _window.SetSize(size);
            _window.Mat.SetFloat("_RadiusM", compact ? 0.018f : 0.024f);
            float top = size.y / 2, left = -size.x / 2;
            // progress bar: 1.7 mm, inset by the corner radius
            float inset = 0.024f, barW = size.x - 2 * inset;
            _progressTrack.localPosition = new Vector3(0, top - 0.010f, -0.0004f);
            _progressTrack.localScale = new Vector3(barW, 0.0017f, 1);
            SetProgress(_progress01);
            float colW = compact ? size.x - 2 * Pad : size.x * 0.6f - 1.5f * Pad;
            float y = top - Pad - 0.004f;
            _statusShape.localPosition = new Vector3(left + Pad + 0.0045f, y - 0.0062f, -0.0006f);
            _statusShape.localScale = Vector3.one * 0.009f;
#if AG_HAS_TMP
            _status.rectTransform.sizeDelta = new Vector2((colW - 0.014f) * 10, 0.2f);
            _status.transform.localPosition = new Vector3(left + Pad + 0.014f, y, -0.0006f);
            y -= 0.022f;
            _title.rectTransform.sizeDelta = new Vector2(colW * 10, (compact ? 0.06f : 0.075f) * 10);
            _title.transform.localPosition = new Vector3(left + Pad, y, -0.0006f);
            y -= compact ? 0.062f : 0.077f;
            _body.rectTransform.sizeDelta = new Vector2(colW * 10, 0.05f * 10);
            _body.transform.localPosition = new Vector3(left + Pad, y, -0.0006f);
#endif
            // chips row
            float cy = -size.y / 2 + Pad + 0.016f + (compact ? 0 : 0.022f);
            float cx = left + Pad;
            for (int i = 0; i < _chips.Count; i++)
            {
                var c = _chips[i];
                c.Go.transform.localPosition = new Vector3(cx + c.Size.x / 2, cy, -0.0005f);
#if AG_HAS_TMP
                _chipTexts[i].transform.localPosition = new Vector3(cx + c.Size.x / 2, cy, -0.0008f);
#endif
                cx += c.Size.x + 0.008f;
            }
            // right column: image well
            bool well = !compact;
            _well.Go.SetActive(well);
            _crop.gameObject.SetActive(well && _cropMat.GetTexture("_MainTex") != null);
#if AG_HAS_TMP
            _page.gameObject.SetActive(well);
#endif
            if (well)
            {
                float wW = size.x * 0.4f - 1.5f * Pad, wH = size.y - 2 * Pad - 0.012f - 0.03f;
                var c = new Vector3(size.x / 2 - Pad - wW / 2, (top - Pad - 0.012f) - wH / 2, -0.0004f);
                _well.SetSize(new Vector2(wW, wH));
                _well.Go.transform.localPosition = c;
                var tex = _cropMat.GetTexture("_MainTex");
                if (tex != null)
                {
                    float aspect = (float)tex.width / Mathf.Max(1, tex.height), maxW = wW - 0.02f, maxH = wH - 0.035f;
                    float w = maxW, h = w / aspect;
                    if (h > maxH) { h = maxH; w = h * aspect; }
                    _crop.localPosition = c + new Vector3(0, 0.008f, -0.0003f);
                    _crop.localScale = new Vector3(w, h, 1);
                }
#if AG_HAS_TMP
                _page.transform.localPosition = new Vector3(c.x - wW / 2 + 0.012f, c.y - wH / 2 + 0.024f, -0.0006f);
#endif
            }
            // toolbar ornament: centred under the window, 14 mm in front, overlapping the bottom edge by 50 %
            float total = 0;
            foreach (var b in _buttons) total += b.Size.x;
            total += BtnGap * Mathf.Max(0, _buttons.Count - 1) + 0.016f * 2;
            total = Mathf.Min(total, size.x);
            float ty = -size.y / 2;
            _toolbar.SetSize(new Vector2(total, ToolbarH));
            _toolbar.Go.transform.localPosition = new Vector3(0, ty, OrnamentZ);
            float bx = -total / 2 + 0.016f;
            foreach (var b in _buttons)
            {
                var p = new Vector3(bx + b.Size.x / 2, ty, OrnamentZ - 0.001f);
                b.Glass.Go.transform.localPosition = p;
#if AG_HAS_TMP
                b.Text.transform.localPosition = p + new Vector3(0, 0, -0.0006f);
#endif
                bx += b.Size.x + BtnGap;
            }
            _grab.Go.transform.localPosition = new Vector3(0, ty - ToolbarH / 2 - 0.010f - 0.003f, OrnamentZ);
            _grab.Go.SetActive(!compact);
            // status pill above the top edge
            _pill.Go.transform.localPosition = new Vector3(0, top + 0.012f + 0.014f, -0.004f);
        }

        float _progress01;
        void SetProgress(float p)
        {
            _progress01 = Mathf.Clamp01(p);
            var size = _compact ? Compact : Full;
            float barW = size.x - 2 * 0.024f, w = barW * _progress01;
            _progressFill.localPosition = new Vector3(-barW / 2 + w / 2, size.y / 2 - 0.010f, -0.0006f);
            _progressFill.localScale = new Vector3(Mathf.Max(w, 1e-4f), 0.0017f, 1);
            _progressFill.gameObject.SetActive(_progress01 > 0.001f);
        }

        // ------------------------------------------------------------------ content

        public void Show(StepView v)
        {
            bool relayout = _compact;
            _compact = false;
            SetStatus(v.BadgeKind != StatusKind.None && v.Badge != null ? v.BadgeKind : StatusKind.Info,
                v.Badge ?? StatusLine(v));
#if AG_HAS_TMP
            _title.text = v.Title ?? "";
            _body.text = v.Detail ?? "";
            _page.text = v.Page.HasValue ? L($"p. {v.Page}", $"σ. {v.Page}") : "";
#endif
            SetChips(v.Chips);
            SetCrop(v.Crop);
            foreach (var b in _buttons)
                if (b.Id == PanelButton.Next)
                {
#if AG_HAS_TMP
                    b.Text.text = Label(PanelButton.Next, v.IsLast);
#endif
                }
            SetProgress(v.StepCount > 0 ? (v.StepIndex + (v.ActionCount > 0 ? (float)v.Action / v.ActionCount : 0)) / v.StepCount : 0);
            if (relayout || _buttons.Count == 0 || _buttons[0].Id == PanelButton.Retry || _buttons[0].Id == PanelButton.Cancel)
                SetButtons(new[] { PanelButton.Back, PanelButton.Repeat, PanelButton.WhereAmI, PanelButton.Next }, PanelButton.Next);
            Layout(false);
            Pop();
        }

        string StatusLine(StepView v)
        {
            string s = Lang == "el" ? $"Βήμα {v.Step} από {v.StepCount}" : $"Step {v.Step} of {v.StepCount}";
            if (v.Page.HasValue) s += Lang == "el" ? $" · σελίδα {v.Page}" : $" · page {v.Page}";
            return s;
        }

        void SetStatus(StatusKind k, string text)
        {
            var c = k switch { StatusKind.Ok => Tokens.Success, StatusKind.Error => Tokens.Error, StatusKind.Unsure => Tokens.Unsure, _ => Tokens.Accent };
            _statusMat.SetColor("_Color", c);
            _statusMat.SetFloat("_Shape", k == StatusKind.Error ? 3 : k == StatusKind.Unsure ? 4 : 1);
#if AG_HAS_TMP
            _status.text = Txt.UpperNoTonos(text ?? "");
            _status.color = k == StatusKind.Error || k == StatusKind.Unsure || k == StatusKind.Ok ? c : new Color(1, 1, 1, 0.68f);
#endif
        }

        void SetChips(List<(string label, string code, bool primary)> chips)
        {
            foreach (var c in _chips) Destroy(c.Go);
            _chips.Clear();
#if AG_HAS_TMP
            foreach (var t in _chipTexts) Destroy(t.gameObject);
            _chipTexts.Clear();
            if (chips == null) return;
            float maxW = Full.x * 0.6f - 1.5f * Pad, used = 0;
            foreach (var (label, code, primary) in chips)
            {
                var t = Txt.Make(_content, "chip_text", _font, Txt.EmM(0.85f, D), Color.white, 0.3f, 0.03f, TextAlignmentOptions.Center, Tokens.QText + 1, primary);
                t.rectTransform.pivot = new Vector2(0.5f, 0.5f);
                t.enableWordWrapping = false;
                t.text = code != null ? $"{label}  <color=#FFFFFFAD>{code}</color>" : label;
                float w = t.GetPreferredValues(t.text).x * 0.1f + 0.02f, h = 0.026f;
                if (used + w > maxW) { Destroy(t.gameObject); break; }
                used += w + 0.008f;
                var g = new GlassQuad(_content, "chip", new Vector2(w, h), h / 2, Tokens.QGlass + 2, 0f);
                g.Mat.SetFloat("_Sheen", 0); g.Mat.SetFloat("_Shadow", 0);
                if (primary) { g.Mat.SetColor("_Tint", Tokens.AccentDeep); g.Mat.SetFloat("_AlphaCentre", 1); g.Mat.SetFloat("_AlphaEdge", 1); }
                else { g.Mat.SetFloat("_Fill", 0.08f); g.Mat.SetFloat("_AlphaCentre", 0); g.Mat.SetFloat("_AlphaEdge", 0); }
                _chips.Add(g);
                _chipTexts.Add(t);
            }
#endif
        }

        void SetCrop(Texture tex)
        {
            _cropMat.SetTexture("_MainTex", tex);
        }

        public void ShowMessage(string title, string detail = null, float? progress01 = null)
        {
            SetStatus(StatusKind.Info, _cardLabel ?? "");
#if AG_HAS_TMP
            _title.text = title ?? "";
            _body.text = detail ?? "";
#endif
            SetChips(null);
            SetCrop(null);
            SetProgress(progress01 ?? 0);
            Layout(true);
            Pop();
        }

        string _cardLabel;
        /// <summary>Uppercase label of the next coach card ("1 από 5"); also the status kind for errors.</summary>
        public void SetCardLabel(string label, StatusKind kind = StatusKind.Info)
        {
            _cardLabel = label;
            SetStatus(kind, label ?? "");
        }

        public void SetStatusPill(string text, StatusKind kind = StatusKind.Unsure)
        {
            bool on = !string.IsNullOrEmpty(text);
            _pill.Go.SetActive(on);
            _pillShape.gameObject.SetActive(on);
#if AG_HAS_TMP
            _pillText.gameObject.SetActive(on);
            if (!on) return;
            _pillText.text = text;
            float w = _pillText.GetPreferredValues(text).x * 0.1f;
            float total = 0.012f + 0.010f + 0.008f + w + 0.014f;
            _pill.SetSize(new Vector2(total, 0.028f));
            var p = _pill.Go.transform.localPosition;
            _pillShape.localPosition = p + new Vector3(-total / 2 + 0.012f + 0.005f, 0, -0.0005f);
            _pillShape.localScale = Vector3.one * 0.010f;
            _pillShapeMat.SetColor("_Color", kind == StatusKind.Error ? Tokens.Error : kind == StatusKind.Ok ? Tokens.Success : Tokens.Unsure);
            _pillShapeMat.SetFloat("_Shape", kind == StatusKind.Error ? 3 : kind == StatusKind.Ok ? 1 : 4);
            _pillText.transform.localPosition = p + new Vector3(-total / 2 + 0.012f + 0.010f + 0.008f, 0, -0.0006f);
#endif
        }

        void Pop()
        {
            // UI spring: rise 8 mm and settle (response 0.35 s, no visible overshoot)
            Tween.Run(0.35f, Ease.OutCubic, t => _content.localPosition = new Vector3(0, Mathf.Lerp(-0.008f, 0, t), 0), owner: this);
        }

        public void SetVisible(bool visible) => _content.gameObject.SetActive(visible);

        // ------------------------------------------------------------------ input

        PanelButton? _downOn;
        bool _wasPinch;

        /// <summary>
        /// One pointer per frame (the active hand or controller). Returns true when the ray hits a button or the window;
        /// <paramref name="hitWorld"/> is the hit point for a cursor.
        /// </summary>
        public bool Pointer(Ray ray, bool pinch, out Vector3 hitWorld)
        {
            bool hit = Raycast(ray, out hitWorld, out var hover);
            Process(hover, pinch);
            return hit;
        }

        /// <summary>Pure hit test (no state change): window or toolbar hit, and the button under the ray.</summary>
        public bool Raycast(Ray ray, out Vector3 hitWorld) => Raycast(ray, out hitWorld, out _);

        bool Raycast(Ray ray, out Vector3 hitWorld, out Btn hover)
        {
            hitWorld = default;
            bool hit = false;
            hover = null;
            if (_content.gameObject.activeInHierarchy && _fade > 0.5f)
            {
                // the toolbar plane
                var plane = new Plane(-transform.forward, transform.TransformPoint(new Vector3(0, 0, OrnamentZ)));
                if (plane.Raycast(ray, out float dist) && dist < 3f)
                {
                    var wp = ray.GetPoint(dist);
                    var lp = transform.InverseTransformPoint(wp);
                    foreach (var b in _buttons)
                    {
                        var c = b.Glass.Go.transform.localPosition;
                        if (Mathf.Abs(lp.x - c.x) <= b.Size.x / 2 + 0.004f && Mathf.Abs(lp.y - c.y) <= b.Size.y / 2 + 0.004f) { hover = b; break; }
                    }
                    var size = _compact ? Compact : Full;
                    hit = hover != null || (Mathf.Abs(lp.x) <= size.x / 2 && Mathf.Abs(lp.y) <= size.y / 2 + ToolbarH / 2);
                    if (hit) hitWorld = wp;
                }
            }
            return hit;
        }

        void Process(Btn hover, bool pinch)
        {
            foreach (var b in _buttons)
            {
                bool h = b == hover;
                if (h && !b.Hover) b.HoverAt = Time.time;
                b.Hover = h;
            }
            bool down = pinch && !_wasPinch, up = !pinch && _wasPinch;
            _wasPinch = pinch;
            if (down && hover != null)
            {
                _downOn = hover.Id;
                hover.Down = true;
                UiSounds.Play(Audio, Cue.Tick);
            }
            if (up)
            {
                foreach (var b in _buttons) b.Down = false;
                if (_downOn.HasValue && hover != null && hover.Id == _downOn.Value)
                {
                    AgLog.Ev("button", new { id = hover.Id.ToString() });
                    Pressed?.Invoke(hover.Id);
                }
                _downOn = null;
            }
        }

        /// <summary>For tests, keyboard and voice commands.</summary>
        public void Press(PanelButton b)
        {
            UiSounds.Play(Audio, Cue.Tick);
            Pressed?.Invoke(b);
        }

        // ------------------------------------------------------------------ placement

        Vector3 _target, _vel, _sideRef;
        bool _placed, _relocating;
        float _side = 1f;

        public void PlaceBeside(Bounds b, Vector3 viewer)
        {
            var toViewer = Vector3.ProjectOnPlane(viewer - b.center, Vector3.up);
            if (toViewer.sqrMagnitude < 1e-4f) toViewer = Vector3.back;
            toViewer.Normalize();
            if (!_placed) _sideRef = toViewer;
            // user walked around the object by > 35 deg: fade out, move, fade in (never slide across the work zone)
            if (_placed && !_relocating && Vector3.Angle(_sideRef, toViewer) > 35f)
            {
                _relocating = true;
                var newRef = toViewer;
                Tween.Run(0.15f, Ease.Linear, t => _fade = 1 - t, () =>
                {
                    _sideRef = newRef;
                    _target = Compute(b, viewer, newRef);
                    transform.position = _target;
                    transform.rotation = Facing(viewer, _target);
                    _vel = Vector3.zero;
                    Tween.Run(0.25f, Ease.OutCubic, t => _fade = t, () => _relocating = false, this);
                }, this);
                return;
            }
            var target = Compute(b, viewer, _sideRef);
            if (!_placed)
            {
                _target = target;
                transform.SetPositionAndRotation(target, Facing(viewer, target));
                _placed = true;
                return;
            }
            if (Vector3.Distance(target, _target) > 0.03f) _target = target;   // 3 cm dead band
            _viewer = viewer;
        }

        Vector3 _viewer;

        /// <summary>
        /// Coach cards before anything is tracked (spec §5.4): placed once 0.8 m ahead and 15 deg down, then
        /// world-locked. The next <see cref="PlaceBeside"/> snaps (no slide) to the object.
        /// </summary>
        public void PlaceInFront(Transform head)
        {
            var fwd = Vector3.ProjectOnPlane(head.forward, Vector3.up);
            if (fwd.sqrMagnitude < 1e-4f) fwd = Vector3.forward;
            fwd.Normalize();
            var p = head.position + fwd * (0.8f * Mathf.Cos(15f * Mathf.Deg2Rad)) + Vector3.down * (0.8f * Mathf.Sin(15f * Mathf.Deg2Rad));
            transform.SetPositionAndRotation(p, Facing(head.position, p));
            _placed = false;
            _vel = Vector3.zero;
        }

        Vector3 Compute(Bounds b, Vector3 eye, Vector3 toViewer)
        {
            var size = _compact ? Compact : Full;
            // the user's right as they look at the object (left for left-handed users)
            var right = Vector3.Cross(Vector3.up, -toViewer).normalized * (LeftHanded ? -1f : 1f) * _side;
            float halfAlong = Mathf.Abs(right.x) * b.extents.x + Mathf.Abs(right.z) * b.extents.z;
            var p = b.center + right * (halfAlong + 0.10f + size.x / 2);
            p.y = b.max.y + 0.12f;
            // distance 0.6 - 0.95 m from the eyes (horizontal)
            var flat = Vector3.ProjectOnPlane(p - eye, Vector3.up);
            float h = flat.magnitude;
            if (h < 0.6f) { p += flat.normalized * (0.6f - h); h = 0.6f; }
            else if (h > 0.95f) { p -= flat.normalized * (h - 0.95f); h = 0.95f; }
            // centre 10 - 20 deg below eye level
            float yMax = eye.y - h * Mathf.Tan(10f * Mathf.Deg2Rad), yMin = eye.y - h * Mathf.Tan(20f * Mathf.Deg2Rad);
            p.y = Mathf.Clamp(p.y, yMin, yMax);
            return p;
        }

        static Quaternion Facing(Vector3 eye, Vector3 at)
        {
            var fwd = Vector3.ProjectOnPlane(at - eye, Vector3.up);
            if (fwd.sqrMagnitude < 1e-6f) fwd = Vector3.forward;
            return Quaternion.LookRotation(fwd, Vector3.up) * Quaternion.Euler(8f, 0, 0);   // 8 deg back tilt
        }

        void Update()
        {
            float dt = Mathf.Min(Time.deltaTime, 0.05f);
            if (_placed && !_relocating)
            {
                // critically damped follow spring, response 0.6 s
                float w = 2 * Mathf.PI / 0.6f;
                var x = transform.position;
                var acc = w * w * (_target - x) - 2 * w * _vel;
                _vel += acc * dt;
                x += _vel * dt;
                var rot = _viewer != default ? Facing(_viewer, x) : transform.rotation;
                transform.SetPositionAndRotation(x, Quaternion.Slerp(transform.rotation, rot, Tween.Damp(0.25f, dt)));
            }
            // buttons: hover 1.03 / press 0.97 with the press spring (response 0.25 s, zeta 0.9)
            foreach (var b in _buttons)
            {
                float target = b.Down ? 0.97f : b.Hover ? 1.03f : 1f;
                float w = 2 * Mathf.PI / 0.25f;
                b.ScaleVel += (w * w * (target - b.Scale) - 2 * 0.9f * w * b.ScaleVel) * dt;
                b.Scale += b.ScaleVel * dt;
                var s = new Vector3(b.Size.x * b.Scale, b.Size.y * b.Scale, 1);
                b.Glass.Go.transform.localScale = new Vector3(s.x + 0, s.y, 1);
                b.Glass.Mat.SetVector("_SizeM", new Vector4(s.x, s.y, s.x, s.y));
                b.Glass.Mat.SetFloat("_Fill", (b.Primary ? 0f : 0.10f) + (b.Down ? 0.16f : b.Hover ? 0.08f : 0f));
                b.Glass.Mat.SetFloat("_RimTop", b.Hover ? 0.35f : 0.16f);
#if AG_HAS_TMP
                b.Text.transform.localScale = Vector3.one * 0.1f * b.Scale;
#endif
            }
            float a = _alpha * _fade;
            if (Mathf.Abs(a - _lastA) > 1e-3f)
            {
                _lastA = a;
                foreach (var g in AllGlass()) g.Mat.SetFloat("_Alpha", a);
#if AG_HAS_TMP
                foreach (var t in _content.GetComponentsInChildren<TextMeshPro>()) t.alpha = a * BaseAlpha(t);
#endif
            }
        }

        float _lastA = 1f;

        IEnumerable<GlassQuad> AllGlass()
        {
            yield return _window; yield return _toolbar; yield return _pill; yield return _well; yield return _grab;
            foreach (var c in _chips) yield return c;
            foreach (var b in _buttons) yield return b.Glass;
        }

#if AG_HAS_TMP
        float BaseAlpha(TextMeshPro t) => t == _body ? 0.88f : t == _page ? 0.68f : (t == _status && _status.color.r > 0.99f && _status.color.g > 0.99f) ? 0.68f : 1f;
#endif
    }
}
