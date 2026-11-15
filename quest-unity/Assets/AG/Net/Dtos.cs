using System.Collections.Generic;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace AG.Net
{
    // JSON shapes of the server, field names exactly as sent (server/identify.py, server/routes/*.py,
    // server/perception/locate.py, server/perception/README.md). Read with Newtonsoft; unknown fields are ignored,
    // so additive server changes do not break the client. Do not rename the JsonProperty strings.

    // ---------- REST ----------

    /// <summary>POST /api/identify and /api/perception/{job}/locate request: base64 JPEG without data: prefix.</summary>
    public sealed class ImageBody
    {
        [JsonProperty("image")] public string Image;
        [JsonProperty("lang")] public string Lang = "en";
    }

    public sealed class Seen
    {
        [JsonProperty("kind")] public string Kind;        // box | label | guide | product | unknown ...
        [JsonProperty("name")] public string Name;
        [JsonProperty("type")] public string Type;
        [JsonProperty("article")] public string Article;  // e.g. "902.891.72"
        [JsonProperty("text")] public string Text;
    }

    public sealed class JobMatch
    {
        [JsonProperty("job_id")] public string JobId;
        [JsonProperty("product")] public string Product;
        [JsonProperty("ready")] public bool Ready;
    }

    /// <summary>POST /api/identify response.</summary>
    public sealed class IdentifyResponse
    {
        [JsonProperty("seen")] public Seen Seen;
        /// <summary>A guide on this server; null when nothing matched (then use <see cref="Query"/> with catalog/open).</summary>
        [JsonProperty("match")] public JobMatch Match;
        /// <summary>What to give POST /api/catalog/open when <see cref="Match"/> is null or not ready.</summary>
        [JsonProperty("query")] public string Query;
        [JsonProperty("confidence")] public float Confidence;
        /// <summary>Ready-to-show sentence in the requested language.</summary>
        [JsonProperty("message")] public string Message;
        [JsonProperty("matched_by")] public string MatchedBy;
        [JsonProperty("latency_ms")] public int? LatencyMs;
        [JsonProperty("model")] public string Model;
        [JsonProperty("simulated")] public bool Simulated;
    }

    /// <summary>POST /api/catalog/open request.</summary>
    public sealed class CatalogOpenBody
    {
        [JsonProperty("q")] public string Q;
        [JsonProperty("size", NullValueHandling = NullValueHandling.Ignore)] public string Size;
    }

    /// <summary>POST /api/catalog/open response. 409 = several guides match (body.detail.items); 404 = none.</summary>
    public sealed class CatalogOpenResponse
    {
        [JsonProperty("job_id")] public string JobId;
        [JsonProperty("ready")] public bool Ready;
        [JsonProperty("product")] public string Product;
    }

    /// <summary>GET /api/jobs/{id}.</summary>
    public sealed class JobStatus
    {
        [JsonProperty("job_id")] public string JobId;
        /// <summary>ingest, extract_plan, await_approval, parts, place, steps, refs, export.</summary>
        [JsonProperty("stage")] public string Stage;
        /// <summary>queued, running, waiting_approval, done, error.</summary>
        [JsonProperty("status")] public string Status;
        [JsonProperty("progress")] public float Progress;   // 0..1
        [JsonProperty("last_seq")] public int? LastSeq;
        [JsonProperty("title")] public string Title;
        [JsonProperty("error")] public JToken Error;
        public bool Done => Status == "done";
        public bool Failed => Status == "error";
    }

    public sealed class LocateStep
    {
        [JsonProperty("n")] public int N;
        [JsonProperty("complete")] public bool Complete;
        [JsonProperty("p")] public float P;
        [JsonProperty("error")] public string Error;
    }

    /// <summary>POST /api/perception/{job}/locate response.</summary>
    public sealed class LocateResponse
    {
        [JsonProperty("next_step")] public int NextStep;
        [JsonProperty("action")] public int Action;
        [JsonProperty("done_through")] public int DoneThrough;   // 0 = nothing done
        [JsonProperty("confidence")] public float Confidence;
        [JsonProperty("steps")] public List<LocateStep> Steps = new List<LocateStep>();
        [JsonProperty("message")] public string Message;
        [JsonProperty("latency_ms")] public int? LatencyMs;
        [JsonProperty("simulated")] public bool Simulated;
    }

    /// <summary>POST /api/tts request (1-400 chars). Response: audio/wav, 16-bit mono PCM.</summary>
    public sealed class TtsBody
    {
        [JsonProperty("text")] public string Text;
    }

    // ---------- Perception socket: server -> client ----------

    public sealed class Limits
    {
        [JsonProperty("max_unacked")] public int MaxUnacked = 5;
        [JsonProperty("in_flight")] public int InFlight = 4;
        [JsonProperty("max_hz")] public float MaxHz = 4;
        [JsonProperty("ack_timeout_ms")] public int AckTimeoutMs = 9000;
        [JsonProperty("max_frame_bytes")] public int MaxFrameBytes = 1500000;
        [JsonProperty("long_edge_px")] public int LongEdgePx = 768;
        [JsonProperty("jpeg_quality")] public float JpegQuality = 0.7f;
    }

    public sealed class Hello
    {
        [JsonProperty("type")] public string Type;
        [JsonProperty("protocol")] public int Protocol;
        [JsonProperty("job_id")] public string JobId;
        [JsonProperty("session")] public string Session;
        [JsonProperty("role")] public string Role;
        [JsonProperty("source")] public string Source;
        [JsonProperty("vision")] public string Vision;    // live | simulated | unavailable
        [JsonProperty("reason")] public string Reason;
        [JsonProperty("title")] public string Title;
        [JsonProperty("steps")] public List<int> Steps;
        [JsonProperty("step")] public int? Step;
        [JsonProperty("action")] public int? Action;
        [JsonProperty("limits")] public Limits Limits = new Limits();
    }

    public sealed class Ack
    {
        [JsonProperty("type")] public string Type;
        [JsonProperty("seq")] public int? Seq;
        [JsonProperty("latency_ms")] public int? LatencyMs;
        [JsonProperty("used")] public bool Used;
        /// <summary>null when used; else busy, stale, rate, gated, dark, timeout, ... (README table).</summary>
        [JsonProperty("dropped")] public string Dropped;
        [JsonProperty("decision_ms")] public int? DecisionMs;
    }

    public sealed class Say
    {
        [JsonProperty("en")] public string En;
        [JsonProperty("el")] public string El;
        public string Get(string lang) => lang == "el" && !string.IsNullOrEmpty(El) ? El : En;
    }

    /// <summary>
    /// Guidance event. <see cref="Event"/>: wrong_part, flip_needed, wrong_hardware, out_of_order, step_complete,
    /// unsure_show_part, vision_paused, right_part, right_hardware, more_light, realign_camera, vision_resumed,
    /// show_part_result. Speak <see cref="SayText"/> when <see cref="Speak"/>.
    /// </summary>
    public sealed class PerceptionEvent
    {
        [JsonProperty("type")] public string Type;
        [JsonProperty("event")] public string Event;
        [JsonProperty("id")] public int Id;
        [JsonProperty("step")] public int? Step;
        [JsonProperty("action")] public int? Action;
        [JsonProperty("page")] public int? Page;
        [JsonProperty("expected")] public string Expected;
        [JsonProperty("observed")] public string Observed;
        [JsonProperty("expected_id")] public string ExpectedId;
        [JsonProperty("observed_id")] public string ObservedId;
        [JsonProperty("p")] public float? P;
        [JsonProperty("evidence")] public Dictionary<string, float> Evidence;
        [JsonProperty("say")] public Say SayText;
        [JsonProperty("perception")] public string Perception;
        [JsonProperty("speak")] public bool Speak;
        [JsonProperty("turn_complete")] public bool TurnComplete;
        [JsonProperty("repeat")] public int Repeat;
        [JsonProperty("seq")] public int? Seq;
        [JsonProperty("latency_ms")] public int? LatencyMs;
        [JsonProperty("votes")] public string Votes;
        [JsonProperty("reason")] public string Reason;
        [JsonProperty("message")] public string Message;
        [JsonProperty("match")] public bool? Match;
        [JsonProperty("ts")] public double Ts;
        [JsonProperty("sim")] public bool Sim;
    }

    public sealed class ExpectedItem
    {
        [JsonProperty("kind")] public string Kind;          // part | hardware | null
        [JsonProperty("id")] public string Id;
        [JsonProperty("name")] public string Name;
        [JsonProperty("action_type")] public string ActionType;
        [JsonProperty("actions")] public int Actions;
    }

    public sealed class Holding
    {
        [JsonProperty("kind")] public string Kind;          // part | hardware | none | unknown
        [JsonProperty("id")] public string Id;
        [JsonProperty("name")] public string Name;
        [JsonProperty("confidence")] public float Confidence;
    }

    /// <summary>Continuous state for UI (ring colour, badge), at most 2.5 Hz.</summary>
    public sealed class PerceptionStatus
    {
        [JsonProperty("type")] public string Type;
        [JsonProperty("step")] public int? Step;
        [JsonProperty("action")] public int? Action;
        [JsonProperty("page")] public int? Page;
        [JsonProperty("state")] public string State;        // IDLE, WAITING_FOR_PART, PART_IN_HAND, PLACING, STEP_COMPLETE
        [JsonProperty("expected")] public ExpectedItem Expected;
        [JsonProperty("holding")] public Holding Holding;
        [JsonProperty("verdict")] public string Verdict;    // right | wrong | none | unknown
        [JsonProperty("hands_present")] public bool? HandsPresent;
        [JsonProperty("view")] public string View;          // ok | blocked | poor | dark | moved | none
        [JsonProperty("vision")] public string Vision;      // live | simulated | paused | unavailable
        [JsonProperty("reason")] public string Reason;
        [JsonProperty("rate_hz")] public float? RateHz;
        [JsonProperty("muted")] public List<string> Muted;
    }

    public sealed class ServerError
    {
        [JsonProperty("type")] public string Type;
        [JsonProperty("code")] public string Code;
        [JsonProperty("message")] public string Message;
    }

    // ---------- Perception socket: client -> server ----------

    /// <summary>Binary frame header: <c>[uint32 BE header_len][header JSON UTF-8][JPEG]</c>.</summary>
    public sealed class FrameHeader
    {
        [JsonProperty("seq")] public int Seq;
        [JsonProperty("step")] public int Step;
        [JsonProperty("ts")] public double Ts;                 // capture time, ms
        [JsonProperty("hint")] public string Hint = "medium";  // low | medium | high
        [JsonProperty("source")] public string Source = "headset";
    }

    /// <summary>Hand summary, ~10 Hz (README "hands").</summary>
    public sealed class HandsMsg
    {
        [JsonProperty("type")] public string Type = "hands";
        [JsonProperty("seq")] public int Seq;
        [JsonProperty("hand_at_target")] public bool HandAtTarget;
        [JsonProperty("still")] public bool Still;
        [JsonProperty("raised")] public bool Raised;
        [JsonProperty("present")] public bool Present;
        [JsonProperty("ts")] public double Ts;                  // ms
    }
}
