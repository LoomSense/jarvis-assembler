using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using AG.Core;
using UnityEngine;
#if AG_HAS_GLTFAST
using GLTFast;
using GLTFast.Logging;
using GLTFast.Materials;
#endif

namespace AG.Guide
{
    /// <summary>
    /// manifest.json + walkthrough.glb bytes -> <see cref="AssemblyGuide"/>. Shared by every <see cref="IJobRepository"/>
    /// (HTTP, local Resources) and the EditMode tests.
    ///
    /// 1. <see cref="GuideBuilder.FromManifest"/> (manifest vectors converted with <see cref="FrameConvert"/>).
    /// 2. <see cref="GlbAnimation.Parse"/>: last keyframe of the <c>walkthrough</c> clip read from the samplers -> each
    ///    <see cref="PartNode"/>'s final local transform under <c>assembly</c>.
    /// 3. glTFast instantiates the meshes (no animation, no glTF materials: the ghost renderer owns the look), the
    ///    <c>assembly</c> node becomes <see cref="AssemblyGuide.Root"/> with identity local pose (assembly frame ==
    ///    parent frame), every part node gets its final transform, inactive until a renderer binds it.
    /// Main thread.
    /// </summary>
    public static class GuideLoader
    {
        /// <summary>Steps 1-2 only: no GameObjects. Pure, any thread; used by tests and for a fast first panel.</summary>
        public static AssemblyGuide BuildModel(string jobId, ManifestDto m, byte[] glb)
        {
            var guide = GuideBuilder.FromManifest(jobId, m);
            if (glb == null) return guide;
            var anim = GlbAnimation.Parse(glb, m.Walkthrough?.Clip ?? "walkthrough");
            guide.WalkthroughEndS = anim.DurationS;
            string root = string.IsNullOrEmpty(m.RootNode) ? "assembly" : m.RootNode;
            foreach (var kv in guide.Nodes)
            {
                if (!anim.Final.TryGetValue(kv.Key, out var trs)) continue;
                if (anim.Parent.TryGetValue(kv.Key, out var parent) && parent != root)
                    AgLog.Error("guide.load", $"node {kv.Key} is not a direct child of {root}");
                kv.Value.FinalLocalPosition = trs.Position;
                kv.Value.FinalLocalRotation = trs.Rotation;
                kv.Value.FinalLocalScale = trs.Scale;
            }
            return guide;
        }

        public static async Task<AssemblyGuide> LoadAsync(string jobId, ManifestDto m, byte[] glb, Transform parent, Uri baseUri, CancellationToken ct)
        {
            var sw = Stopwatch.StartNew();
            var guide = BuildModel(jobId, m, glb);
#if AG_HAS_GLTFAST
            var gltf = new GltfImport(null, new UninterruptedDeferAgent(), new PlainMaterialGenerator(), new ConsoleLogger());
            var settings = new ImportSettings { AnimationMethod = AnimationMethod.None, GenerateMipMaps = false };
            bool ok = await gltf.Load(glb, baseUri, settings, ct);
            if (!ok) throw new InvalidOperationException("glTFast could not load the GLB");
            var holder = new GameObject("glb_" + jobId).transform;
            holder.SetParent(parent, false);
            holder.gameObject.SetActive(false);
            ok = await gltf.InstantiateMainSceneAsync(holder, ct);
            if (!ok) throw new InvalidOperationException("glTFast could not instantiate the GLB");
            string rootName = string.IsNullOrEmpty(m.RootNode) ? "assembly" : m.RootNode;
            var root = FindDeep(holder, rootName);
            if (root == null) throw new InvalidOperationException($"GLB has no '{rootName}' node");
            root.SetParent(parent, false);
            root.localPosition = Vector3.zero;
            root.localRotation = Quaternion.identity;
            root.localScale = Vector3.one;
            root.gameObject.SetActive(false);
            DestroyAny(holder.gameObject);   // cameras and the scene node
            guide.Root = root.gameObject;

            bool any = false;
            var all = new Bounds();
            foreach (var kv in guide.Nodes)
            {
                var t = FindDeep(root, kv.Key);
                if (t == null) { AgLog.Error("guide.load", "GLB node missing: " + kv.Key); continue; }
                var n = kv.Value;
                t.localPosition = n.FinalLocalPosition;
                t.localRotation = n.FinalLocalRotation;
                t.localScale = n.FinalLocalScale;
                n.Instance = t.gameObject;
                n.FinalBounds = LocalBounds(t, root, out bool has);
                if (has) { if (!any) all = n.FinalBounds; else all.Encapsulate(n.FinalBounds); any = true; }
            }
            var marker = FindDeep(root, string.IsNullOrEmpty(m.MarkerNode) ? "marker" : m.MarkerNode);
            if (marker != null) marker.gameObject.SetActive(false);
            guide.FinalBounds = all;
#else
            await Task.Yield();
            AgLog.Error("guide.load", "glTFast missing: guide without meshes");
#endif
            AgLog.Ev("job_loaded", new { job = jobId, steps = guide.Steps.Count, actions = guide.ActionCount, nodes = guide.Nodes.Count, ms = sw.ElapsedMilliseconds });
            return guide;
        }

        public static void DestroyAny(UnityEngine.Object o)
        {
            if (o == null) return;
            if (Application.isPlaying) UnityEngine.Object.Destroy(o); else UnityEngine.Object.DestroyImmediate(o);
        }

        public static Transform FindDeep(Transform t, string name)
        {
            if (t.name == name) return t;
            for (int i = 0; i < t.childCount; i++)
            {
                var r = FindDeep(t.GetChild(i), name);
                if (r != null) return r;
            }
            return null;
        }

        /// <summary>Bounds of every mesh under <paramref name="node"/>, in <paramref name="frame"/>'s local space.</summary>
        public static Bounds LocalBounds(Transform node, Transform frame, out bool has)
        {
            has = false;
            var b = new Bounds();
            foreach (var mf in node.GetComponentsInChildren<MeshFilter>(true))
            {
                if (mf.sharedMesh == null) continue;
                var mb = mf.sharedMesh.bounds;
                var m = frame.worldToLocalMatrix * mf.transform.localToWorldMatrix;
                for (int i = 0; i < 8; i++)
                {
                    var c = mb.center + Vector3.Scale(mb.extents, new Vector3((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1));
                    var p = m.MultiplyPoint3x4(c);
                    if (!has) { b = new Bounds(p, Vector3.zero); has = true; } else b.Encapsulate(p);
                }
            }
            return b;
        }
    }

#if AG_HAS_GLTFAST
    /// <summary>
    /// glTFast material generator that never touches glTFast's shader graphs (they would need to be in the build):
    /// one plain material for everything; <see cref="GhostRenderer"/> replaces it.
    /// </summary>
    sealed class PlainMaterialGenerator : IMaterialGenerator
    {
        static Material _mat;
        static Material Mat()
        {
            if (_mat != null) return _mat;
            var sh = GuideMaterials.FindShader("AG/Ghost");
            _mat = new Material(sh) { name = "AG glb placeholder" };
            return _mat;
        }
        public Material GetDefaultMaterial(bool pointsSupport = false) => Mat();
        public Material GenerateMaterial(GLTFast.Schema.MaterialBase gltfMaterial, IGltfReadable gltf, bool pointsSupport = false) => Mat();
        public void SetLogger(ICodeLogger logger) { }
    }
#endif
}
