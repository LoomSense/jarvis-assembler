using System;
using AG.Core;
using UnityEngine;
#if AG_HAS_TMP
using TMPro;
#endif

namespace AG.UI
{
    /// <summary>
    /// Placeholder world-space panel: a quad with the theme's glass material and one TextMeshPro block.
    /// Placed beside the object, follows it with damping, yaw-only billboard. UI agent: replace with the real glass
    /// panel per docs/design/XR_NATIVE_SPEC.md (buttons, crop image, hardware row, poke/pinch interaction).
    /// </summary>
    public sealed class StepPanelStub : MonoBehaviour, IStepPanel, IDebugHud
    {
        public UiTheme Theme;
        public bool IsHud;
#if AG_HAS_TMP
        TextMeshPro _text;
#else
        TextMesh _text;
#endif
        Vector3 _targetPos;
        Quaternion _targetRot = Quaternion.identity;
        bool _placed;

        public event Action<PanelButton> Pressed;

        void Awake()
        {
            var bg = GameObject.CreatePrimitive(PrimitiveType.Quad);
            Destroy(bg.GetComponent<Collider>());
            bg.transform.SetParent(transform, false);
            bg.transform.localScale = new Vector3(0.34f, 0.2f, 1f);
            bg.transform.localPosition = new Vector3(0, 0, 0.002f);
            if (Theme != null && Theme.GlassMaterial != null) bg.GetComponent<Renderer>().sharedMaterial = Theme.GlassMaterial;
            var go = new GameObject("Text");
            go.transform.SetParent(transform, false);
#if AG_HAS_TMP
            _text = go.AddComponent<TextMeshPro>();
            _text.fontSize = 0.18f;   // TMP world units: ~1.8 cm cap height at scale 1 (tune with UiTheme.DegToMetres)
            _text.rectTransform.sizeDelta = new Vector2(0.32f, 0.18f);
            _text.alignment = TextAlignmentOptions.TopLeft;
            if (Theme != null && Theme.FontAsset is TMP_FontAsset f) _text.font = f;
#else
            _text = go.AddComponent<TextMesh>();
            _text.characterSize = 0.01f;
            _text.anchor = TextAnchor.UpperLeft;
#endif
        }

        void SetText(string s) => _text.text = s;

        public void Show(StepView v)
        {
            SetText($"<b>{v.Step}/{v.StepCount}</b>  {v.Title}\n<size=70%>{v.Detail}</size>" + (v.Badge != null ? $"\n{v.Badge}" : ""));
            Pop();
        }

        public void ShowMessage(string title, string detail = null, float? progress01 = null)
        {
            SetText(title + (detail != null ? "\n<size=70%>" + detail + "</size>" : "") + (progress01.HasValue ? $"\n{Mathf.RoundToInt(progress01.Value * 100)} %" : ""));
            Pop();
        }

        void Pop() => Tween.Run(0.28f, Ease.OutCubic, t => transform.localScale = Vector3.one * Mathf.Lerp(0.96f, 1f, t), owner: this);

        public void PlaceBeside(Bounds b, Vector3 viewer)
        {
            var toViewer = Vector3.ProjectOnPlane(viewer - b.center, Vector3.up).normalized;
            if (toViewer.sqrMagnitude < 1e-4f) toViewer = Vector3.back;
            var right = Vector3.Cross(Vector3.up, toViewer).normalized;   // the viewer's left/right around the object
            float gap = Theme != null ? Theme.PanelGapM : 0.15f;
            _targetPos = b.center + right * (b.extents.magnitude + gap) + Vector3.up * 0.1f;
            _targetRot = Quaternion.LookRotation(-toViewer, Vector3.up);
            if (!_placed) { transform.SetPositionAndRotation(_targetPos, _targetRot); _placed = true; }
        }

        void Update()
        {
            if (!_placed) return;
            float a = Tween.Damp(0.12f, Time.deltaTime);
            transform.SetPositionAndRotation(Vector3.Lerp(transform.position, _targetPos, a), Quaternion.Slerp(transform.rotation, _targetRot, a));
        }

        public void SetVisible(bool visible) => gameObject.SetActive(visible);

        public void Set(HudState s) =>
            SetText($"{s.Fps:0} fps  cam {s.Camera}\ntrack {s.Tracker}\nstep {s.Step}.{s.Action}  {s.LastEvent} {s.LastEventMs} ms\nserver {(s.ServerOk ? "ok" : "unreachable")}");

        /// <summary>For tests / editor buttons.</summary>
        public void Press(PanelButton b) => Pressed?.Invoke(b);
    }
}
