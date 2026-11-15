using System;
using System.Threading;
using System.Threading.Tasks;
using AG.Core;
using Newtonsoft.Json;
using UnityEngine;

namespace AG.Guide
{
    /// <summary>
    /// <see cref="IJobRepository"/> over the server's /files route: manifest + walkthrough.glb, then
    /// <see cref="GuideLoader"/> (final transforms from the walkthrough clip, glTFast meshes).
    /// Job ids starting with <c>local:</c> (e.g. <c>local:malm-drawer</c>) load from Resources instead
    /// (<see cref="ResourcesJobRepository"/>), for offline demos.
    /// </summary>
    public sealed class HttpJobRepository : IJobRepository
    {
        readonly string _server, _key;
        readonly ResourcesJobRepository _local = new ResourcesJobRepository();

        public HttpJobRepository(string server, string key) { _server = (server ?? "").TrimEnd('/'); _key = key; }

        string Url(string jobId, string rel) =>
            Http.WithKey($"{_server}/files/{Uri.EscapeDataString(jobId)}/{rel}", _key);

        static bool IsLocal(string jobId) => jobId != null && jobId.StartsWith(ResourcesJobRepository.Prefix);

        public async Task<ManifestDto> FetchManifestAsync(string jobId, CancellationToken ct = default)
        {
            if (IsLocal(jobId)) return await _local.FetchManifestAsync(jobId, ct);
            var text = await Http.GetTextAsync(Url(jobId, "manifest.json"), _key, ct);
            return JsonConvert.DeserializeObject<ManifestDto>(text);
        }

        public Task<byte[]> FetchFileAsync(string jobId, string relPath, CancellationToken ct = default)
            => IsLocal(jobId) ? _local.FetchFileAsync(jobId, relPath, ct) : Http.GetBytesAsync(Url(jobId, relPath), _key, ct, 120);

        public async Task<Texture2D> FetchTextureAsync(string jobId, string relPath, CancellationToken ct = default)
        {
            var bytes = await FetchFileAsync(jobId, relPath, ct);
            var tex = new Texture2D(2, 2, TextureFormat.RGBA32, false);
            tex.LoadImage(bytes);
            return tex;
        }

        public async Task<AssemblyGuide> LoadGuideAsync(string jobId, Transform parent, CancellationToken ct = default)
        {
            if (IsLocal(jobId)) return await _local.LoadGuideAsync(jobId, parent, ct);
            var manifest = await FetchManifestAsync(jobId, ct);
            // the uncompressed GLB; glbPacked (meshopt) only once verified on device
            var glb = await FetchFileAsync(jobId, string.IsNullOrEmpty(manifest.Glb) ? "walkthrough.glb" : manifest.Glb, ct);
            return await GuideLoader.LoadAsync(jobId, manifest, glb, parent, new Uri($"{_server}/files/{Uri.EscapeDataString(jobId)}/"), ct);
        }
    }

    /// <summary>
    /// Jobs bundled in Resources: <c>Resources/&lt;name&gt;/manifest.json</c> and <c>walkthrough.glb.bytes</c>
    /// (the MALM test job lives in Assets/AG/Tests/Resources/malm-drawer/). Job id <c>local:&lt;name&gt;</c>.
    /// Crops and thumbnails are not bundled (FetchFileAsync throws 404 for them).
    /// </summary>
    public sealed class ResourcesJobRepository : IJobRepository
    {
        public const string Prefix = "local:";
        static string Name(string jobId) => jobId.StartsWith(Prefix) ? jobId.Substring(Prefix.Length) : jobId;

        public Task<ManifestDto> FetchManifestAsync(string jobId, CancellationToken ct = default)
        {
            var ta = Resources.Load<TextAsset>(Name(jobId) + "/manifest");
            if (ta == null) throw new HttpError(404, "no bundled job " + jobId, null);
            return Task.FromResult(JsonConvert.DeserializeObject<ManifestDto>(ta.text));
        }

        public Task<byte[]> FetchFileAsync(string jobId, string relPath, CancellationToken ct = default)
        {
            // Resources paths drop the last extension: walkthrough.glb.bytes -> "walkthrough.glb"
            var path = Name(jobId) + "/" + relPath;
            var ta = Resources.Load<TextAsset>(path) ?? Resources.Load<TextAsset>(System.IO.Path.ChangeExtension(path, null));
            if (ta == null) throw new HttpError(404, relPath + " not bundled for " + jobId, null);
            return Task.FromResult(ta.bytes);
        }

        public async Task<Texture2D> FetchTextureAsync(string jobId, string relPath, CancellationToken ct = default)
        {
            var tex = new Texture2D(2, 2);
            tex.LoadImage(await FetchFileAsync(jobId, relPath, ct));
            return tex;
        }

        public async Task<AssemblyGuide> LoadGuideAsync(string jobId, Transform parent, CancellationToken ct = default)
        {
            var m = await FetchManifestAsync(jobId, ct);
            var glb = await FetchFileAsync(jobId, string.IsNullOrEmpty(m.Glb) ? "walkthrough.glb" : m.Glb, ct);
            return await GuideLoader.LoadAsync(jobId, m, glb, parent, null, ct);
        }
    }
}
