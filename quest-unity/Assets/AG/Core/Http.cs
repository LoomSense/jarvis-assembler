using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using UnityEngine.Networking;

namespace AG.Core
{
    /// <summary>
    /// Minimal async HTTP on UnityWebRequest, shared by Guide and Net. Main thread only; the returned tasks
    /// complete on the main thread. Every call sends <c>X-Demo-Key</c> when a key is given.
    /// Failure: <see cref="HttpError"/> with the status code and the server's text (FastAPI's <c>detail</c>).
    /// </summary>
    public static class Http
    {
        public const int DefaultTimeoutS = 30;

        public static Task<byte[]> GetBytesAsync(string url, string key, CancellationToken ct = default, int timeoutS = DefaultTimeoutS)
            => Send(UnityWebRequest.Get(url), key, ct, timeoutS, r => r.downloadHandler.data);

        public static Task<string> GetTextAsync(string url, string key, CancellationToken ct = default, int timeoutS = DefaultTimeoutS)
            => Send(UnityWebRequest.Get(url), key, ct, timeoutS, r => r.downloadHandler.text);

        /// <summary>POST a JSON body; returns the response body as bytes (JSON text, or audio/wav for TTS).</summary>
        public static Task<byte[]> PostJsonAsync(string url, string json, string key, CancellationToken ct = default, int timeoutS = DefaultTimeoutS)
        {
            var req = new UnityWebRequest(url, UnityWebRequest.kHttpVerbPOST)
            {
                uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(json)),
                downloadHandler = new DownloadHandlerBuffer(),
            };
            req.SetRequestHeader("Content-Type", "application/json");
            return Send(req, key, ct, timeoutS, r => r.downloadHandler.data);
        }

        /// <summary>Append <c>?key=</c> (or <c>&amp;key=</c>) for routes reached without headers (WebSocket, media).</summary>
        public static string WithKey(string url, string key) =>
            string.IsNullOrEmpty(key) ? url : url + (url.Contains("?") ? "&" : "?") + "key=" + Uri.EscapeDataString(key);

        static Task<T> Send<T>(UnityWebRequest req, string key, CancellationToken ct, int timeoutS, Func<UnityWebRequest, T> read)
        {
            var tcs = new TaskCompletionSource<T>();
            if (!string.IsNullOrEmpty(key)) req.SetRequestHeader("X-Demo-Key", key);
            req.timeout = timeoutS;
            var reg = ct.Register(() => { try { req.Abort(); } catch (Exception) { } });
            req.SendWebRequest().completed += _ =>
            {
                reg.Dispose();
                try
                {
                    if (ct.IsCancellationRequested) tcs.TrySetCanceled();
                    else if (req.result == UnityWebRequest.Result.Success) tcs.TrySetResult(read(req));
                    else tcs.TrySetException(new HttpError((int)req.responseCode, req.error, req.downloadHandler?.text));
                }
                catch (Exception e) { tcs.TrySetException(e); }
                finally { req.Dispose(); }
            };
            return tcs.Task;
        }
    }

    public sealed class HttpError : IOException
    {
        public readonly int Status;
        public readonly string Body;
        public HttpError(int status, string error, string body)
            : base($"HTTP {status}: {error} {Truncate(body)}") { Status = status; Body = body; }
        static string Truncate(string s) => string.IsNullOrEmpty(s) ? "" : s.Length > 300 ? s.Substring(0, 300) : s;
    }
}
