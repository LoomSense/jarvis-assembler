using System.Threading;
using System.Threading.Tasks;

namespace AG.Net
{
    /// <summary>
    /// The server's REST API (docs/HANDOFF.md §2.1). Every call sends the demo key as <c>X-Demo-Key</c>.
    /// Images are JPEG bytes (long edge 768, q70; see Vision.FrameJpeg); base64 is done inside.
    ///
    /// Threading: call from the main thread; tasks complete on the main thread.
    /// Errors: <see cref="AG.Core.HttpError"/> with Status (403 key, 404 unknown, 409 ambiguous/not built,
    /// 429 rate, 502/503/504 model). Each call logs its event (identify, locate) with ms.
    /// Owner: Net agent (Assets/AG/Net/).
    /// </summary>
    public interface IServerApi
    {
        /// <summary>POST /api/identify: which product is in view. ~3-4 s.</summary>
        Task<IdentifyResponse> IdentifyAsync(byte[] jpeg, string lang, CancellationToken ct = default);

        /// <summary>POST /api/catalog/open {q}: open or start building a guide by name / article / URL.</summary>
        Task<CatalogOpenResponse> CatalogOpenAsync(string query, CancellationToken ct = default);

        /// <summary>GET /api/jobs/{id}: build progress. Poll every 2 s until Done or Failed.</summary>
        Task<JobStatus> JobStatusAsync(string jobId, CancellationToken ct = default);

        /// <summary>POST /api/perception/{job}/locate: which step the real assembly is at. ~1 s.</summary>
        Task<LocateResponse> LocateAsync(string jobId, byte[] jpeg, string lang, CancellationToken ct = default);

        /// <summary>POST /api/tts {text} (1-400 chars): WAV bytes (16-bit mono PCM, usually 24 kHz). EN or EL text.</summary>
        Task<byte[]> TtsAsync(string text, CancellationToken ct = default);

        /// <summary>Server reachable and key accepted (GET /api/health or similar); for the HUD.</summary>
        Task<bool> PingAsync(CancellationToken ct = default);
    }
}
