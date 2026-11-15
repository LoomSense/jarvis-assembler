using System.Threading;
using System.Threading.Tasks;
using UnityEngine;

namespace AG.Guide
{
    /// <summary>
    /// Guide data from the server's job folder, <c>GET {server}/files/{job}/{path}?key={key}</c>
    /// (manifest.json, walkthrough.glb, crops/step-NN.png, renders/part-&lt;id&gt;-shaded.png).
    /// The demo key is also sent as the <c>X-Demo-Key</c> header.
    ///
    /// Threading: call from the main thread; tasks complete on the main thread (UnityWebRequest + Unity's
    /// SynchronizationContext). Errors: throws <see cref="System.IO.IOException"/> with the HTTP status in the
    /// message (404 unknown job or file, 403 bad key).
    /// Owner: Guide agent (Assets/AG/Guide/).
    /// </summary>
    public interface IJobRepository
    {
        /// <summary>Parsed manifest.json of a built job.</summary>
        Task<ManifestDto> FetchManifestAsync(string jobId, CancellationToken ct = default);

        /// <summary>Raw bytes of any file in the job folder (GLB, PNG). <paramref name="relPath"/> like "crops/step-01.png".</summary>
        Task<byte[]> FetchFileAsync(string jobId, string relPath, CancellationToken ct = default);

        /// <summary>PNG/JPEG file as a texture (crops, part thumbnails).</summary>
        Task<Texture2D> FetchTextureAsync(string jobId, string relPath, CancellationToken ct = default);

        /// <summary>
        /// Manifest + GLB -> <see cref="AssemblyGuide"/> with <see cref="AssemblyGuide.Root"/> instantiated under
        /// <paramref name="parent"/> (inactive), every part's final transform read at the end of the
        /// <c>walkthrough</c> clip, and the manifest vectors converted with <see cref="FrameConvert"/>.
        /// Loads the manifest's <c>glb</c> (walkthrough.glb, uncompressed); use <c>glbPacked</c> only once meshopt
        /// decompression is verified on device.
        /// Logs <c>job_loaded</c> {job, steps, actions, ms}.
        /// </summary>
        Task<AssemblyGuide> LoadGuideAsync(string jobId, Transform parent, CancellationToken ct = default);
    }
}
