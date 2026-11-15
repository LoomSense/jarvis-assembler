using System.Collections.Generic;
using UnityEngine;
#if AG_HAS_TMP
using TMPro;
#endif

namespace AG.Guide
{
    /// <summary>Token colours of docs/design/tokens.css (sRGB hex; Unity converts to linear for materials).</summary>
    public static class Tokens
    {
        public static readonly Color Accent = Hex(0x9ecfff);
        public static readonly Color AccentDeep = Hex(0x274a6b);
        public static readonly Color Success = Hex(0x6fdc9b);
        public static readonly Color Error = Hex(0xff8a7a);
        public static readonly Color Unsure = Hex(0xe8c14b);
        public static readonly Color Warn = Hex(0xffa95c);
        public static readonly Color Surface = Hex(0x0c0c0c);
        public static Color Hex(int rgb, float a = 1f) => new Color(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, a);
        public static Color A(this Color c, float a) { c.a = a; return c; }
        /// <summary>Render queues, back to front (spec §4 drawing order).</summary>
        public const int QGhost = 3000, QMarks = 3005, QGlass = 3010, QPill = 3015, QText = 3020, QBadge = 3025;
    }

    /// <summary>Shader lookup and material factories. Shaders live in Resources/AG/ so they are always in the build.</summary>
    public static class GuideMaterials
    {
        static readonly Dictionary<string, Shader> Cache = new Dictionary<string, Shader>();

        public static Shader FindShader(string name)
        {
            if (Cache.TryGetValue(name, out var s) && s != null) return s;
            s = Resources.Load<Shader>(name) ?? Shader.Find(name);
            if (s == null) s = Shader.Find("Universal Render Pipeline/Unlit") ?? Shader.Find("Unlit/Color");
            Cache[name] = s;
            return s;
        }

        public static Material Ghost(Color fill, Color rim, float rimStrength = 0.65f, bool depthPrime = true)
        {
            var m = new Material(FindShader("AG/Ghost")) { name = "AG ghost", renderQueue = Tokens.QGhost };
            m.SetColor("_Color", fill);
            m.SetColor("_RimColor", rim);
            m.SetFloat("_RimStrength", rimStrength);
            m.SetFloat("_DepthPrime", depthPrime ? 1 : 0);
            return m;
        }

        public static Material Flat(Color c, int queue = Tokens.QMarks, float shape = 0, float halo = 0)
        {
            var m = new Material(FindShader("AG/Flat")) { name = "AG flat", renderQueue = queue };
            m.SetColor("_Color", c);
            m.SetFloat("_Shape", shape);
            m.SetFloat("_Halo", halo);
            return m;
        }

        public static Material Glass(int queue = Tokens.QGlass)
        {
            var m = new Material(FindShader("AG/Glass")) { name = "AG glass", renderQueue = queue };
            return m;
        }

        /// <summary>Depth-only material: placed parts hide the ghost behind them without drawing colour.</summary>
        public static Material DepthOnly()
        {
            var m = Ghost(new Color(0, 0, 0, 0), new Color(0, 0, 0, 0), 0f, true);
            m.name = "AG depth only";
            m.renderQueue = Tokens.QGhost - 10;
            return m;
        }
    }

    /// <summary>Procedural meshes for the guidance marks (built once, shared).</summary>
    public static class GuideMeshes
    {
        static Mesh _quad, _cone;

        /// <summary>Unit quad in XY centred at the origin, facing -Z (towards a viewer looking along +Z).</summary>
        public static Mesh Quad
        {
            get
            {
                if (_quad != null) return _quad;
                _quad = new Mesh { name = "AG quad" };
                _quad.vertices = new[] { new Vector3(-0.5f, -0.5f), new Vector3(0.5f, -0.5f), new Vector3(-0.5f, 0.5f), new Vector3(0.5f, 0.5f) };
                _quad.uv = new[] { new Vector2(0, 0), new Vector2(1, 0), new Vector2(0, 1), new Vector2(1, 1) };
                _quad.triangles = new[] { 0, 2, 1, 2, 3, 1 };
                _quad.RecalculateNormals();
                _quad.RecalculateBounds();
                return _quad;
            }
        }

        /// <summary>Cone along +Z, base at z=0 (diameter 1), tip at z=1.</summary>
        public static Mesh Cone
        {
            get
            {
                if (_cone != null) return _cone;
                const int n = 20;
                var v = new List<Vector3> { new Vector3(0, 0, 1), Vector3.zero };
                var uv = new List<Vector2> { new Vector2(0.5f, 1), new Vector2(0.5f, 0) };
                var tri = new List<int>();
                for (int i = 0; i <= n; i++)
                {
                    float a = i * Mathf.PI * 2 / n;
                    v.Add(new Vector3(Mathf.Cos(a) * 0.5f, Mathf.Sin(a) * 0.5f, 0));
                    uv.Add(new Vector2((float)i / n, 0));
                }
                for (int i = 0; i < n; i++) { tri.Add(0); tri.Add(2 + i); tri.Add(3 + i); tri.Add(1); tri.Add(3 + i); tri.Add(2 + i); }
                _cone = new Mesh { name = "AG cone" };
                _cone.SetVertices(v);
                _cone.SetUVs(0, uv);
                _cone.SetTriangles(tri, 0);
                _cone.RecalculateNormals();
                return _cone;
            }
        }

        /// <summary>
        /// Tube along an arc in the XY plane about +Z: radius <paramref name="r"/>, from 0 to <paramref name="sweepDeg"/>
        /// counter-clockwise seen from +Z (negate the sweep for clockwise). uv.x runs 0..1 along the arc.
        /// </summary>
        public static Mesh ArcTube(float r, float tubeRadius, float sweepDeg, int seg = 48, int sides = 8)
        {
            var v = new List<Vector3>();
            var uv = new List<Vector2>();
            var tri = new List<int>();
            for (int i = 0; i <= seg; i++)
            {
                float t = (float)i / seg, a = sweepDeg * Mathf.Deg2Rad * t;
                var c = new Vector3(Mathf.Cos(a) * r, Mathf.Sin(a) * r, 0);
                var radial = c.normalized;
                for (int j = 0; j <= sides; j++)
                {
                    float b = j * Mathf.PI * 2 / sides;
                    v.Add(c + (radial * Mathf.Cos(b) + Vector3.forward * Mathf.Sin(b)) * tubeRadius);
                    uv.Add(new Vector2(t, (float)j / sides));
                }
            }
            int row = sides + 1;
            for (int i = 0; i < seg; i++)
                for (int j = 0; j < sides; j++)
                {
                    int a0 = i * row + j, a1 = a0 + 1, b0 = a0 + row, b1 = b0 + 1;
                    tri.Add(a0); tri.Add(b0); tri.Add(a1); tri.Add(a1); tri.Add(b0); tri.Add(b1);
                }
            var m = new Mesh { name = "AG arc" };
            m.SetVertices(v);
            m.SetUVs(0, uv);
            m.SetTriangles(tri, 0);
            m.RecalculateNormals();
            return m;
        }

        /// <summary>
        /// Hard edges (dihedral above <paramref name="minDeg"/>, plus open borders) of a mesh as a line-topology mesh,
        /// welded by position. Capped at <paramref name="maxEdges"/> (detailed hardware keeps only its silhouette-ish edges).
        /// </summary>
        public static Mesh HardEdges(Mesh src, float minDeg = 30f, int maxEdges = 1500)
        {
            if (src == null || !src.isReadable) return null;
            var verts = src.vertices;
            var tris = src.triangles;
            var weld = new Dictionary<Vector3Int, int>();
            var pos = new List<Vector3>();
            int Id(Vector3 p)
            {
                var k = new Vector3Int(Mathf.RoundToInt(p.x * 1e4f), Mathf.RoundToInt(p.y * 1e4f), Mathf.RoundToInt(p.z * 1e4f));
                if (!weld.TryGetValue(k, out int id)) { id = pos.Count; pos.Add(p); weld[k] = id; }
                return id;
            }
            var edges = new Dictionary<long, (Vector3 n0, int count, bool hard)>();
            for (int t = 0; t + 2 < tris.Length; t += 3)
            {
                int a = Id(verts[tris[t]]), b = Id(verts[tris[t + 1]]), c = Id(verts[tris[t + 2]]);
                var n = Vector3.Cross(pos[b] - pos[a], pos[c] - pos[a]);
                if (n.sqrMagnitude < 1e-16f) continue;
                n.Normalize();
                Add(a, b, n); Add(b, c, n); Add(c, a, n);
            }
            void Add(int a, int b, Vector3 n)
            {
                long k = a < b ? ((long)a << 32) | (uint)b : ((long)b << 32) | (uint)a;
                if (edges.TryGetValue(k, out var e)) edges[k] = (e.n0, e.count + 1, e.hard || Vector3.Angle(e.n0, n) > minDeg);
                else edges[k] = (n, 1, false);
            }
            var idx = new List<int>();
            foreach (var kv in edges)
            {
                if (!(kv.Value.hard || kv.Value.count == 1)) continue;
                idx.Add((int)(kv.Key >> 32));
                idx.Add((int)(kv.Key & 0xffffffff));
                if (idx.Count / 2 >= maxEdges) break;
            }
            var m = new Mesh { name = src.name + " edges" };
            if (pos.Count > 65000) m.indexFormat = UnityEngine.Rendering.IndexFormat.UInt32;
            m.SetVertices(pos);
            m.SetIndices(idx, MeshTopology.Lines, 0);
            return m;
        }
    }

    /// <summary>A quad with the AG/Glass material sized in metres (window, ornament, pill, button).</summary>
    public sealed class GlassQuad
    {
        public readonly GameObject Go;
        public readonly Material Mat;
        public Vector2 Size { get; private set; }
        float _halo;

        public GlassQuad(Transform parent, string name, Vector2 sizeM, float radiusM, int queue = Tokens.QGlass, float haloM = 0.024f)
        {
            Go = new GameObject(name);
            Go.transform.SetParent(parent, false);
            Go.AddComponent<MeshFilter>().sharedMesh = GuideMeshes.Quad;
            Mat = GuideMaterials.Glass(queue);
            Go.AddComponent<MeshRenderer>().sharedMaterial = Mat;
            Mat.SetFloat("_RadiusM", radiusM);
            _halo = haloM;
            Mat.SetFloat("_HaloM", Mathf.Max(haloM, 1e-4f));
            if (haloM <= 0) Mat.SetFloat("_HaloAlpha", 0);
            SetSize(sizeM);
        }

        public void SetSize(Vector2 sizeM)
        {
            Size = sizeM;
            var quad = sizeM + Vector2.one * (_halo * 2);
            Go.transform.localScale = new Vector3(quad.x, quad.y, 1);
            Mat.SetVector("_SizeM", new Vector4(sizeM.x, sizeM.y, quad.x, quad.y));
        }

        public void Pill(float rimTop = 0.28f, float rimBottom = 0.10f, float rimM = 0.0012f)
        {
            Mat.SetFloat("_RimTop", rimTop);
            Mat.SetFloat("_RimBottom", rimBottom);
            Mat.SetFloat("_RimM", rimM);
            Mat.SetFloat("_EdgeBandM", 0.004f);
        }
    }

#if AG_HAS_TMP
    /// <summary>TextMeshPro helpers: sizes in degrees at a viewing distance (spec §2.2).</summary>
    public static class Txt
    {
        /// <summary>Font size (em) in metres for <paramref name="deg"/> at <paramref name="distM"/>.</summary>
        public static float EmM(float deg, float distM) => 2f * distM * Mathf.Tan(deg * 0.5f * Mathf.Deg2Rad);

        /// <summary>
        /// World-space TMP block. TMP's world fontSize 1 = 0.1 m em at scale 1, so the object is scaled by 0.1 and
        /// fontSize = em in metres * 100. Origin = pivot (top-left by default).
        /// </summary>
        public static TextMeshPro Make(Transform parent, string name, TMP_FontAsset font, float emM, Color color, float widthM,
            float heightM = 0.1f, TextAlignmentOptions align = TextAlignmentOptions.TopLeft, int queue = Tokens.QText, bool bold = false)
        {
            var go = new GameObject(name);
            go.transform.SetParent(parent, false);
            var t = go.AddComponent<TextMeshPro>();
            EnsureGlyphs(font);
            if (font != null) t.font = font;
            t.fontSize = emM * 100f;
            t.color = color;
            t.alignment = align;
            t.enableWordWrapping = true;
            t.overflowMode = TextOverflowModes.Ellipsis;
            t.rectTransform.pivot = new Vector2(0, 1);
            go.transform.localScale = Vector3.one * 0.1f;
            t.rectTransform.sizeDelta = new Vector2(widthM * 10f, heightM * 10f);
            t.fontStyle = bold ? FontStyles.Bold : FontStyles.Normal;
            t.extraPadding = true;
            var r = t.GetComponent<MeshRenderer>();
            if (r != null && t.fontSharedMaterial != null && t.fontSharedMaterial.renderQueue != queue)
            {
                // per-queue material instance shared by all texts of that queue
                t.fontSharedMaterial = QueueMat(t.fontSharedMaterial, queue);
            }
            return t;
        }

        static readonly HashSet<TMP_FontAsset> Ensured = new HashSet<TMP_FontAsset>();
        /// <summary>
        /// The ellipsis TMP uses for <see cref="TextOverflowModes.Ellipsis"/> and the few symbols the UI writes: added
        /// to the dynamic atlas once per font so truncation shows "…" (the saved asset has an empty character table).
        /// </summary>
        public const string UiGlyphs = "\u2026\u00B7\u00D7\u2013\u203A\u2039";
        public static bool EnsureGlyphs(TMP_FontAsset font)
        {
            if (font == null || !Ensured.Add(font)) return font != null;
            bool ok = font.TryAddCharacters(UiGlyphs, out string missing);
            if (!ok && !string.IsNullOrEmpty(missing)) Debug.LogWarning("AG font: missing glyphs " + missing + " in " + font.name);
            return ok;
        }

        static readonly Dictionary<(Material, int), Material> Mats = new Dictionary<(Material, int), Material>();
        static Material QueueMat(Material src, int queue)
        {
            if (Mats.TryGetValue((src, queue), out var m) && m != null) return m;
            m = new Material(src) { renderQueue = queue, name = src.name + " q" + queue };
            Mats[(src, queue)] = m;
            return m;
        }

        /// <summary>Greek-aware uppercase without tonos (spec §3.2).</summary>
        public static string UpperNoTonos(string s)
        {
            if (string.IsNullOrEmpty(s)) return s;
            var u = s.ToUpperInvariant();
            var sb = new System.Text.StringBuilder(u.Length);
            foreach (var ch in u.Normalize(System.Text.NormalizationForm.FormD))
                if (ch != '́') sb.Append(ch);   // drop tonos/acute, keep diaeresis
            return sb.ToString().Normalize(System.Text.NormalizationForm.FormC);
        }
    }
#endif
}
