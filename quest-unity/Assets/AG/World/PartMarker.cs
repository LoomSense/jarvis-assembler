using AG.Guide;
using UnityEngine;
#if AG_HAS_TMP
using TMPro;
#endif

namespace AG.World
{
    /// <summary>
    /// "Pick this one" cue on the table (spec §4.6): an azure ellipse under the part (2 mm stroke, 10 % fill, 2 cm
    /// larger than the part), a small chevron 6 cm above it bobbing ±1 cm at 0.5 Hz, and a glass pill with
    /// "ΠΑΡΕ / PICK UP" and the part's name. World-anchored (the pile does not move with the assembly).
    /// Only AG/Flat and AG/Glass materials and procedural meshes: no primitive with a default material.
    /// </summary>
    public sealed class PartMarker
    {
        const float RefDist = 0.8f, ChevronGapM = 0.06f, ChevronH = 0.020f, ChevronW = 0.022f, PillLiftM = 0.115f;

        public bool Visible => _root.gameObject.activeSelf;
        public bool ReducedMotion;
        /// <summary>Table point under the part, world.</summary>
        public Vector3 Position { get; private set; }
        /// <summary>Time.realtimeSinceStartup of the last <see cref="Show"/>.</summary>
        public float ShownAt { get; private set; }

        readonly Transform _root, _ring, _fill, _chevron, _pill;
        readonly Material _ringMat;
        readonly GlassQuad _glass;
        Vector3 _ellipse = Vector3.one;
#if AG_HAS_TMP
        readonly TextMeshPro _text;
#endif

        public PartMarker(Object font)
        {
            _root = new GameObject("PartMarker").transform;
            _ringMat = GuideMaterials.Flat(Tokens.Accent.A(0.95f), Tokens.QMarks, 2);
            _ring = Quad("ring", _ringMat);
            _fill = Quad("fill", GuideMaterials.Flat(Tokens.Accent.A(0.10f), Tokens.QMarks - 1, 1));
            _chevron = MeshGo("chevron", GuideMeshes.Cone, GuideMaterials.Flat(Tokens.Accent.A(0.97f), Tokens.QMarks));
            _chevron.rotation = Quaternion.LookRotation(Vector3.down);      // the cone's tip (+Z) points down at the part
            _pill = new GameObject("pill").transform;
            _pill.SetParent(_root, false);
            _glass = new GlassQuad(_pill, "glass", new Vector2(0.12f, 0.028f), 0.014f, Tokens.QPill, 0.008f);
            _glass.Pill();
#if AG_HAS_TMP
            _text = Txt.Make(_pill, "name", font as TMP_FontAsset, Txt.EmM(1.2f, RefDist), Color.white, 0.5f, 0.03f,
                TextAlignmentOptions.MidlineLeft, Tokens.QText, true);
            _text.rectTransform.pivot = new Vector2(0, 0.5f);
            _text.enableWordWrapping = false;
            _text.richText = true;
#endif
            Hide();
        }

        Transform Quad(string name, Material m) => MeshGo(name, GuideMeshes.Quad, m);

        Transform MeshGo(string name, Mesh mesh, Material m)
        {
            var go = new GameObject(name);
            go.transform.SetParent(_root, false);
            go.AddComponent<MeshFilter>().sharedMesh = mesh;
            var r = go.AddComponent<MeshRenderer>();
            r.sharedMaterial = m;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            r.receiveShadows = false;
            return go.transform;
        }

        /// <param name="tablePoint">Where the part lies, on the table plane, world.</param>
        /// <param name="sizeM">The part's extent on the table (x along <paramref name="across"/>); zero when unknown.</param>
        /// <param name="across">Horizontal world direction of the ellipse's x axis.</param>
        /// <param name="prefix">"Pick up" / "Πάρε" (shown uppercase in azure).</param>
        /// <param name="name">The part's display name, e.g. "Cam lock · 120076".</param>
        public void Show(Vector3 tablePoint, Vector2 sizeM, Vector3 across, string prefix, string name)
        {
            Position = tablePoint;
            ShownAt = Time.realtimeSinceStartup;
            _root.gameObject.SetActive(true);

            across = Vector3.ProjectOnPlane(across, Vector3.up);
            across = across.sqrMagnitude > 1e-6f ? across.normalized : Vector3.right;
            float w = Mathf.Clamp((sizeM.x > 0 ? sizeM.x : 0.06f) + 0.02f, 0.05f, 0.50f);
            float d = Mathf.Clamp((sizeM.y > 0 ? sizeM.y : 0.06f) + 0.02f, 0.05f, 0.50f);
            _ellipse = new Vector3(w, d, 1f);
            // flat on the table, facing up, local x along `across`
            var flat = Quaternion.LookRotation(Vector3.down, Vector3.Cross(Vector3.down, across));
            var onTable = tablePoint + Vector3.up * 0.002f;
            _ring.SetPositionAndRotation(onTable, flat);
            _fill.SetPositionAndRotation(onTable, flat);
            _ringMat.SetFloat("_RingWidth", 0.001f / Mathf.Min(w, d));       // ring is 2 * width wide in uv: 2 mm on the short axis

#if AG_HAS_TMP
            const float pad = 0.0112f;
            float h = Txt.EmM(2.0f, RefDist);
            string head = string.IsNullOrEmpty(prefix) ? "" : "<color=#9ecfff>" + Txt.UpperNoTonos(prefix) + "</color>  ";
            _text.text = head + (name ?? "");
            float tw = _text.GetPreferredValues(_text.text).x * _text.transform.localScale.x;
            float total = pad + tw + pad;
            _glass.SetSize(new Vector2(total, h));
            _glass.Mat.SetFloat("_RadiusM", h / 2);
            _text.transform.localPosition = new Vector3(-total / 2 + pad, 0, -0.0005f);
#endif
            Tick(null);
        }

        public void Hide() => _root.gameObject.SetActive(false);

        /// <summary>Every frame while visible: appear, bob, keep the pill facing the viewer (turning about up only).</summary>
        public void Tick(Transform viewer)
        {
            if (!Visible) return;
            float age = Time.realtimeSinceStartup - ShownAt;
            float k = ReducedMotion ? 1f : AG.Core.Ease.OutCubic(Mathf.Clamp01(age / 0.25f));
            _ring.localScale = _fill.localScale = new Vector3(_ellipse.x * k, _ellipse.y * k, 1f);

            float dist = viewer != null ? Vector3.Distance(viewer.position, Position) : RefDist;
            float s = Mathf.Clamp(dist / RefDist, 0.75f, 1.5f);
            float bob = ReducedMotion ? 0f : Mathf.Sin(age * 2f * Mathf.PI * 0.5f) * 0.01f;
            _chevron.localScale = new Vector3(ChevronW * s, ChevronW * s, ChevronH * s);
            _chevron.position = Position + Vector3.up * (ChevronGapM + bob + ChevronH * s);   // base above, tip 6 cm over the part

            var pillPos = Position + Vector3.up * (PillLiftM * s + _glass.Size.y * 0.5f * s);
            _pill.position = pillPos;
            _pill.localScale = Vector3.one * s;
            if (viewer != null)
            {
                var fwd = Vector3.ProjectOnPlane(pillPos - viewer.position, Vector3.up);
                if (fwd.sqrMagnitude > 1e-6f) _pill.rotation = Quaternion.LookRotation(fwd, Vector3.up);
            }
        }

        public void Destroy()
        {
            if (_root != null) Object.Destroy(_root.gameObject);
        }
    }
}
