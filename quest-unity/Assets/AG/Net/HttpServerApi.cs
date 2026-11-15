using System;
using System.Diagnostics;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using AG.Core;
using Newtonsoft.Json;

namespace AG.Net
{
    /// <summary><see cref="IServerApi"/> over UnityWebRequest (AG.Core.Http). First working version; Net agent owns it.</summary>
    public sealed class HttpServerApi : IServerApi
    {
        readonly string _server, _key;

        public HttpServerApi(string server, string key) { _server = server.TrimEnd('/'); _key = key; }

        async Task<T> Post<T>(string path, object body, CancellationToken ct, int timeoutS = 30)
        {
            var bytes = await Http.PostJsonAsync(_server + path, JsonConvert.SerializeObject(body), _key, ct, timeoutS);
            return JsonConvert.DeserializeObject<T>(Encoding.UTF8.GetString(bytes));
        }

        public async Task<IdentifyResponse> IdentifyAsync(byte[] jpeg, string lang, CancellationToken ct = default)
        {
            var sw = Stopwatch.StartNew();
            var r = await Post<IdentifyResponse>("/api/identify", new ImageBody { Image = Convert.ToBase64String(jpeg), Lang = lang }, ct, 40);
            AgLog.Ev("identify", new { ms = sw.ElapsedMilliseconds, product = r.Match?.Product ?? r.Seen?.Name, confidence = r.Confidence, job = r.Match?.JobId });
            return r;
        }

        public Task<CatalogOpenResponse> CatalogOpenAsync(string query, CancellationToken ct = default)
            => Post<CatalogOpenResponse>("/api/catalog/open", new CatalogOpenBody { Q = query }, ct, 90);

        public async Task<JobStatus> JobStatusAsync(string jobId, CancellationToken ct = default)
        {
            var text = await Http.GetTextAsync($"{_server}/api/jobs/{Uri.EscapeDataString(jobId)}", _key, ct);
            return JsonConvert.DeserializeObject<JobStatus>(text);
        }

        public async Task<LocateResponse> LocateAsync(string jobId, byte[] jpeg, string lang, CancellationToken ct = default)
        {
            var sw = Stopwatch.StartNew();
            var r = await Post<LocateResponse>($"/api/perception/{Uri.EscapeDataString(jobId)}/locate",
                new ImageBody { Image = Convert.ToBase64String(jpeg), Lang = lang }, ct, 40);
            AgLog.Ev("locate", new { ms = sw.ElapsedMilliseconds, next_step = r.NextStep, done_through = r.DoneThrough, confidence = r.Confidence });
            return r;
        }

        public Task<byte[]> TtsAsync(string text, CancellationToken ct = default)
            => Http.PostJsonAsync(_server + "/api/tts", JsonConvert.SerializeObject(new TtsBody { Text = text }), _key, ct, 30);

        public async Task<bool> PingAsync(CancellationToken ct = default)
        {
            try { await Http.GetTextAsync(_server + "/healthz", _key, ct, 5); return true; }
            catch (Exception) { return false; }
        }
    }
}
