using System.Collections.Generic;
using AG.Guide;
using UnityEngine;

namespace AG.World
{
    /// <summary>
    /// The base part's footprint drawn on the table as an azure outline with a faint wider line under it: "put the
    /// real part in here". Closed LineRenderers in world space with the AG/Flat material (no primitive, no default
    /// material). The module feeds it the footprint whenever the anchor or the step pose moves.
    /// </summary>
    public sealed class BaseOutline
    {
        public bool Visible => _root.gameObject.activeSelf;

        readonly Transform _root;
        readonly LineRenderer _line, _glow;

        public BaseOutline()
        {
            _root = new GameObject("BaseOutline").transform;
            _glow = Line("glow", 0.012f, GuideMaterials.Flat(Tokens.Accent.A(0.16f), Tokens.QMarks - 1));
            _line = Line("line", 0.003f, GuideMaterials.Flat(Tokens.Accent.A(0.95f), Tokens.QMarks));
            Hide();
        }

        LineRenderer Line(string name, float width, Material m)
        {
            var go = new GameObject(name);
            go.transform.SetParent(_root, false);
            var l = go.AddComponent<LineRenderer>();
            l.useWorldSpace = true;
            l.loop = true;
            l.widthMultiplier = width;
            l.numCornerVertices = 2;
            l.sharedMaterial = m;
            l.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            l.receiveShadows = false;
            l.positionCount = 0;
            return l;
        }

        /// <param name="footprint">Corners in world, in order around the outline (WorldMath.FootprintHull).</param>
        public void Show(IReadOnlyList<Vector3> footprint)
        {
            if (footprint == null || footprint.Count < 3) { Hide(); return; }
            _root.gameObject.SetActive(true);
            Fill(_glow, footprint);
            Fill(_line, footprint);
        }

        static void Fill(LineRenderer l, IReadOnlyList<Vector3> pts)
        {
            l.positionCount = pts.Count;
            for (int i = 0; i < pts.Count; i++) l.SetPosition(i, pts[i]);
        }

        public void Hide() => _root.gameObject.SetActive(false);

        public void Destroy()
        {
            if (_root != null) Object.Destroy(_root.gameObject);
        }
    }
}
