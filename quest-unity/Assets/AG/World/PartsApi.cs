using System;
using System.Collections.Generic;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using AG.Core;
using Newtonsoft.Json;

namespace AG.World
{
    /// <summary>Request body of <c>POST /api/perception/{job}/parts</c> (server/perception/routes.py, PartsBody).</summary>
    [UnityEngine.Scripting.Preserve]
    public sealed class PartsBody
    {
        /// <summary>Base64 JPEG, no <c>data:</c> prefix, at most 2 MB decoded.</summary>
        [JsonProperty("image")] public string Image;
        /// <summary>Part ids and hardware codes (at most 12). Null: the server takes the items of <see cref="Step"/>.</summary>
        [JsonProperty("want", NullValueHandling = NullValueHandling.Ignore)] public List<string> Want;
        [JsonProperty("step", NullValueHandling = NullValueHandling.Ignore)] public int? Step;
        [JsonProperty("lang")] public string Lang = "en";
    }

    [UnityEngine.Scripting.Preserve]
    public sealed class PartsFound
    {
        [JsonProperty("id")] public string Id;
        /// <summary>"part" | "hardware".</summary>
        [JsonProperty("kind")] public string Kind;
        /// <summary>[ymin, xmin, ymax, xmax], 0-1000 over the picture, y down from the top edge.</summary>
        [JsonProperty("box_2d")] public int[] Box2d;
        [JsonProperty("confidence")] public float Confidence;
        /// <summary>Display name in the request's language: "Left side panel", "Cam lock · 120076".</summary>
        [JsonProperty("label")] public string Label;
    }

    /// <summary>Response of the parts endpoint (server/perception/pile.py, <c>answer</c> + <c>find</c>).</summary>
    [UnityEngine.Scripting.Preserve]
    public sealed class PartsResponse
    {
        [JsonProperty("found")] public List<PartsFound> Found = new List<PartsFound>();
        [JsonProperty("missing")] public List<string> Missing = new List<string>();
        [JsonProperty("message")] public string Message;
        [JsonProperty("step")] public int? Step;
        [JsonProperty("latency_ms")] public int LatencyMs;
        [JsonProperty("model")] public string Model;
        /// <summary>True when the server has no vision model: the box is a placeholder in the middle of the picture.</summary>
        [JsonProperty("simulated")] public bool Simulated;
    }

    /// <summary>The one REST call of the world layer. Main thread; the demo key goes in <c>X-Demo-Key</c> (AG.Core.Http).</summary>
    public sealed class PartsApi
    {
        readonly string _server, _key;

        public PartsApi(string server, string key) { _server = (server ?? "").TrimEnd('/'); _key = key; }

        public bool HasServer => !string.IsNullOrEmpty(_server);

        /// <summary>Throws <see cref="HttpError"/>: 404 unknown job, 409 no manifest, 422 bad image/item/step, 429/502/503 model.</summary>
        public async Task<PartsResponse> FindAsync(string jobId, byte[] jpeg, IEnumerable<string> want, int? step, string lang, CancellationToken ct = default)
        {
            var body = new PartsBody { Image = Convert.ToBase64String(jpeg), Step = step, Lang = lang == "el" ? "el" : "en" };
            if (want != null)
            {
                body.Want = new List<string>();
                foreach (var w in want)
                    if (!string.IsNullOrWhiteSpace(w)) body.Want.Add(w.Trim());
                if (body.Want.Count == 0) body.Want = null;
            }
            var bytes = await Http.PostJsonAsync($"{_server}/api/perception/{Uri.EscapeDataString(jobId)}/parts",
                JsonConvert.SerializeObject(body), _key, ct, 40);
            return JsonConvert.DeserializeObject<PartsResponse>(Encoding.UTF8.GetString(bytes)) ?? new PartsResponse();
        }
    }
}
