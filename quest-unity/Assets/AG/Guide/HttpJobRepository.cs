using System;
using System.Diagnostics;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using AG.Core;
using Newtonsoft.Json;
using UnityEngine;

namespace AG.Guide
{
    /// <summary>
    /// <see cref="IJobRepository"/> over the server's /files route. Manifest and file fetches work;
    /// GLB loading is a TODO for the Guide agent (glTFast).
    /// </summary>
    public sealed class HttpJobRepository : IJobRepository
    {
        readonly string _server, _key;

        public HttpJobRepository(string server, string key) { _server = server.TrimEnd('/'); _key = key; }

        string Url(string jobId, string rel) =>
            Http.WithKey($"{_server}/files/{Uri.EscapeDataString(jobId)}/{rel}", _key);

        public async Task<ManifestDto> FetchManifestAsync(string jobId, CancellationToken ct = default)
        {
            var text = await Http.GetTextAsync(Url(jobId, "manifest.json"), _key, ct);
            return JsonConvert.DeserializeObject<ManifestDto>(text);
        }

        public Task<byte[]> FetchFileAsync(string jobId, string relPath, CancellationToken ct = default)
            => Http.GetBytesAsync(Url(jobId, relPath), _key, ct, 120);

        public async Task<Texture2D> FetchTextureAsync(string jobId, string relPath, CancellationToken ct = default)
        {
            var bytes = await FetchFileAsync(jobId, relPath, ct);
            var tex = new Texture2D(2, 2);
            tex.LoadImage(bytes);
            return tex;
        }

        public async Task<AssemblyGuide> LoadGuideAsync(string jobId, Transform parent, CancellationToken ct = default)
        {
            var sw = Stopwatch.StartNew();
            var manifest = await FetchManifestAsync(jobId, ct);
            var guide = GuideBuilder.FromManifest(jobId, manifest);
            // TODO(Guide agent): load the GLB with glTFast and fill node transforms.
            //   var glb = await FetchFileAsync(jobId, manifest.Glb ?? "walkthrough.glb", ct);
            //   var gltf = new GLTFast.GltfImport();   // ImportSettings { AnimationMethod = AnimationMethod.Legacy }
            //   await gltf.LoadGltfBinary(glb, new Uri(Url(jobId, "")), settings);
            //   await gltf.InstantiateMainSceneAsync(parent);
            //   find the "assembly" node -> guide.Root; for each node in guide.Nodes find its Transform;
            //   clip = gltf.GetAnimationClips() named manifest.Walkthrough.Clip; clip.legacy = true;
            //   clip.SampleAnimation(guide.Root, clip.length) -> read localPosition/Rotation/Scale into PartNode.Final*.
            //   Then check one action: FrameConvert.BlenderPointToUnity(target_m) ~= node FinalLocalPosition.
            AgLog.Ev("job_loaded", new { job = jobId, steps = guide.Steps.Count, actions = guide.ActionCount, ms = sw.ElapsedMilliseconds });
            return guide;
        }
    }
}
