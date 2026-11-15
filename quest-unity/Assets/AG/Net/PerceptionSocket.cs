using System;
using System.Collections.Generic;
using System.Text;
using System.Threading.Tasks;
using AG.Core;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace AG.Net
{
    /// <summary>
    /// <see cref="IPerceptionSocket"/> on <see cref="WsTransport"/> (System.Net.WebSockets.ClientWebSocket, worker-thread
    /// send/receive, nothing blocks the main thread). Protocol: server/perception/README.md.
    ///
    /// - Limits from <c>hello.limits</c> (max_unacked, max_hz, ack_timeout_ms, long_edge_px, jpeg_quality).
    /// - Reconnect with backoff 0.5, 1, 2, 4, 8 s after any non-fatal close; on reopen the last start/step/action is
    ///   sent again (after the new hello) and the un-acked window is cleared.
    /// - AGQ <c>perception</c> per event: {event, kind, ms (server latency_ms: frame receipt -> event), rtt_ms (client:
    ///   frame sent -> event arrived), step, action, p, seq, reason}; <c>perception_ack</c> once per 5 s with the ack
    ///   round trip p50 and drop counts; <c>perception_conn</c> on open/close/retry.
    /// </summary>
    public sealed class PerceptionSocket : IPerceptionSocket
    {
        readonly string _server, _key;
        WsTransport _t;
        string _job;
        bool _wantOpen;
        int _attempt;
        float _retryAt = -1f;

        // session state to replay after a reconnect
        bool _started;
        int _step, _action;

        readonly Dictionary<int, float> _sentAt = new Dictionary<int, float>();       // un-acked: seq -> realtime s
        readonly Dictionary<int, float> _sentAtAll = new Dictionary<int, float>();    // last ~64 frames for event rtt
        readonly Queue<int> _sentOrder = new Queue<int>();
        float _lastFrameAt = -999f;
        int _seq, _handsSeq;
        readonly List<int> _rtts = new List<int>();
        readonly Dictionary<string, int> _drops = new Dictionary<string, int>();
        float _ackLogAt;

        public PerceptionSocket(string server, string key) { _server = (server ?? "").TrimEnd('/'); _key = key; }

        public bool Connected => _t != null && _t.IsOpen && State == "open";
        public string State { get; private set; } = "idle";
        public Limits Limits { get; private set; } = new Limits();
        public int Unacked => _sentAt.Count;
        public int LastAckRttMs { get; private set; } = -1;
        public bool CanSendFrame => Connected && _started && Unacked < Limits.MaxUnacked
                                    && Time.realtimeSinceStartup - _lastFrameAt >= 1f / Mathf.Max(0.1f, Limits.MaxHz);

        public event Action<Hello> HelloReceived;
        public event Action<PerceptionStatus> StatusReceived;
        public event Action<Ack> AckReceived;
        public event Action<PerceptionEvent> EventReceived;
        public event Action<ServerError> ErrorReceived;
        public event Action<int, string> Closed;
        public event Action<PerceptionEvent> StepComplete;
        public event Action<PerceptionEvent> WrongHardware;
        public event Action<PerceptionEvent> FlipNeeded;
        public event Action<PerceptionEvent> WrongPart;
        public event Action<bool> ConnectionChanged;

        public string Url(string jobId)
        {
            var http = _server.StartsWith("https") ? "https" : "http";
            var ws = (http == "https" ? "wss" : "ws") + _server.Substring(http.Length);
            return Http.WithKey($"{ws}/ws/perception/{Uri.EscapeDataString(jobId)}?role=display&source=headset&device=quest-unity", _key);
        }

        /// <summary>Opens the socket; resolves when open or after 8 s (then keeps retrying in the background).</summary>
        public async Task ConnectAsync(string jobId)
        {
            _job = jobId;
            _wantOpen = true;
            _attempt = 0;
            Open();
            for (int i = 0; i < 160 && !(_t != null && _t.IsOpen); i++)
            {
                await Task.Delay(50);
                if (!Application.isPlaying) Tick();     // EditMode tests: no player loop
            }
            if (_t == null || !_t.IsOpen) AgLog.Error("PerceptionSocket", "connect timeout (retrying)");
        }

        void Open()
        {
            _t?.Dispose();
            _t = new WsTransport();
            State = _attempt == 0 ? "connecting" : "reconnecting";
            _ = _t.ConnectAsync(new Uri(Url(_job)));
        }

        public async Task CloseAsync()
        {
            _wantOpen = false;
            State = "closed";
            if (_t != null) await _t.CloseAsync();
        }

        void SendJson(object o)
        {
            if (_t != null && _t.IsOpen) _t.SendText(JsonConvert.SerializeObject(o));
        }

        public void Start(int step, int action) { _started = true; _step = step; _action = action; _seq = 0; SendJson(new { type = "start", step, action }); }
        public void SetStep(int step, int action) { _started = true; _step = step; _action = action; _seq = 0; SendJson(new { type = "step", n = step, action }); }
        public void SetAction(int index) { _action = index; SendJson(new { type = "action", index }); }
        public void Stop() { _started = false; SendJson(new { type = "stop" }); }
        public void ShowPart(string expected = null) => SendJson(new { type = "show_part", expected });
        public void Mute(string evt = null) => SendJson(evt == null ? (object)new { type = "mute" } : new { type = "mute", @event = evt });
        public void Unmute(string evt = null) => SendJson(evt == null ? (object)new { type = "unmute" } : new { type = "unmute", @event = evt });

        public void SendHands(HandsMsg hands)
        {
            if (hands.Seq <= 0) hands.Seq = ++_handsSeq;
            if (_t == null || !_t.IsOpen) return;
            SendJson(hands);
            _handsSent++;
            float now = Time.realtimeSinceStartup;
            if (now - _handsLogAt >= 1f)
            {
                // 1 Hz AGQ summary of the type:"hands" messages actually sent (R6 evidence: hz ~10)
                AgLog.Ev("hands", new { type = "hands", hz = _handsSent / Mathf.Max(0.001f, now - _handsLogAt), present = hands.Present,
                                        hand_at_target = hands.HandAtTarget, still = hands.Still, raised = hands.Raised });
                _handsSent = 0;
                _handsLogAt = now;
            }
        }

        int _handsSent;
        float _handsLogAt;

        public int SendFrame(byte[] jpeg, int step, double captureMs, string hint = "medium")
        {
            ExpireAcks();
            if (!CanSendFrame || jpeg == null || jpeg.Length == 0 || jpeg.Length > Limits.MaxFrameBytes) return -1;
            int seq = ++_seq;
            var packet = Pack(new FrameHeader { Seq = seq, Step = step, Ts = captureMs, Hint = hint }, jpeg);
            if (!_t.SendBinary(packet)) return -1;
            float now = Time.realtimeSinceStartup;
            _sentAt[seq] = now;
            _sentAtAll[seq] = now;
            _sentOrder.Enqueue(seq);
            while (_sentOrder.Count > 64) _sentAtAll.Remove(_sentOrder.Dequeue());
            _lastFrameAt = now;
            return seq;
        }

        /// <summary><c>[uint32 big-endian header_len][header JSON UTF-8][JPEG]</c>.</summary>
        public static byte[] Pack(FrameHeader header, byte[] jpeg)
        {
            var head = Encoding.UTF8.GetBytes(JsonConvert.SerializeObject(header));
            var buf = new byte[4 + head.Length + jpeg.Length];
            buf[0] = (byte)(head.Length >> 24); buf[1] = (byte)(head.Length >> 16); buf[2] = (byte)(head.Length >> 8); buf[3] = (byte)head.Length;
            Buffer.BlockCopy(head, 0, buf, 4, head.Length);
            Buffer.BlockCopy(jpeg, 0, buf, 4 + head.Length, jpeg.Length);
            return buf;
        }

        public void Tick()
        {
            if (_t != null)
                while (_t != null && _t.Inbox.TryDequeue(out var m))
                    Handle(m);
            ExpireAcks();
            float now = Time.realtimeSinceStartup;
            if (_wantOpen && _retryAt > 0 && now >= _retryAt) { _retryAt = -1; Open(); }
            if (now - _ackLogAt >= 5f && _rtts.Count > 0)
            {
                _rtts.Sort();
                AgLog.Ev("perception_ack", new { n = _rtts.Count, rtt_p50_ms = _rtts[_rtts.Count / 2], rtt_max_ms = _rtts[_rtts.Count - 1], dropped = new Dictionary<string, int>(_drops) });
                _rtts.Clear(); _drops.Clear();
                _ackLogAt = now;
            }
        }

        void Handle(WsTransport.Msg m)
        {
            switch (m.Kind)
            {
                case WsTransport.Kind.Open:
                    State = "open";
                    _sentAt.Clear();
                    AgLog.Ev("perception_conn", new { state = "open", attempt = _attempt });
                    _attempt = 0;
                    ConnectionChanged?.Invoke(true);
                    break;
                case WsTransport.Kind.Text: Route(m.Text); break;
                case WsTransport.Kind.Error: AgLog.Error("PerceptionSocket", m.Reason); break;
                case WsTransport.Kind.Closed:
                    bool wasOpen = State == "open";
                    _sentAt.Clear();
                    bool fatal = m.CloseCode == 4400 || m.CloseCode == 4401 || m.CloseCode == 4404 || m.CloseCode == 4409;
                    Closed?.Invoke(m.CloseCode, m.Reason);
                    if (wasOpen) ConnectionChanged?.Invoke(false);
                    if (_wantOpen && !fatal)
                    {
                        float delay = Mathf.Min(8f, 0.5f * Mathf.Pow(2f, _attempt));
                        _attempt++;
                        _retryAt = Time.realtimeSinceStartup + delay;
                        State = "reconnecting";
                        AgLog.Ev("perception_conn", new { state = "reconnecting", code = m.CloseCode, reason = m.Reason, retry_s = delay, attempt = _attempt });
                    }
                    else
                    {
                        State = "closed";
                        AgLog.Ev("perception_conn", new { state = "closed", code = m.CloseCode, reason = m.Reason, fatal });
                    }
                    break;
            }
        }

        void ExpireAcks()
        {
            if (_sentAt.Count == 0) return;
            float now = Time.realtimeSinceStartup, limit = Limits.AckTimeoutMs / 1000f;
            List<int> old = null;
            foreach (var kv in _sentAt) if (now - kv.Value > limit) (old ??= new List<int>()).Add(kv.Key);
            if (old == null) return;
            foreach (var s in old) _sentAt.Remove(s);
            Count("client_timeout", old.Count);
        }

        void Count(string k, int n = 1) => _drops[k] = (_drops.TryGetValue(k, out var v) ? v : 0) + n;

        void Route(string text)
        {
            JObject o;
            try { o = JObject.Parse(text); }
            catch (Exception e) { AgLog.Error("PerceptionSocket.parse", e.Message); return; }
            switch ((string)o["type"])
            {
                case "hello":
                    var h = o.ToObject<Hello>();
                    if (h.Limits != null) Limits = h.Limits;
                    AgLog.Ev("perception_hello", new { h.Vision, h.Reason, h.Session, steps = h.Steps?.Count, limits = Limits, resume = _started });
                    HelloReceived?.Invoke(h);
                    if (_started) SendJson(new { type = "start", step = _step, action = _action });   // resume after reconnect
                    break;
                case "status": StatusReceived?.Invoke(o.ToObject<PerceptionStatus>()); break;
                case "ack":
                    var a = o.ToObject<Ack>();
                    if (a.Seq.HasValue && _sentAt.TryGetValue(a.Seq.Value, out var t0))
                    {
                        LastAckRttMs = (int)((Time.realtimeSinceStartup - t0) * 1000);
                        _rtts.Add(LastAckRttMs);
                        _sentAt.Remove(a.Seq.Value);
                    }
                    if (!string.IsNullOrEmpty(a.Dropped)) Count(a.Dropped);
                    AckReceived?.Invoke(a);
                    break;
                case "event":
                    var ev = o.ToObject<PerceptionEvent>();
                    int rtt = ev.Seq.HasValue && _sentAtAll.TryGetValue(ev.Seq.Value, out var ts) ? (int)((Time.realtimeSinceStartup - ts) * 1000) : -1;
                    AgLog.Ev("perception", new
                    {
                        @event = ev.Event, kind = ev.Event, ms = ev.LatencyMs, rtt_ms = rtt, step = ev.Step, action = ev.Action,
                        p = ev.P, seq = ev.Seq, reason = ev.Reason, votes = ev.Votes, sim = ev.Sim,
                    });
                    EventReceived?.Invoke(ev);
                    switch (ev.Event)
                    {
                        case "step_complete": StepComplete?.Invoke(ev); break;
                        case "wrong_hardware": WrongHardware?.Invoke(ev); break;
                        case "flip_needed": FlipNeeded?.Invoke(ev); break;
                        case "wrong_part": WrongPart?.Invoke(ev); break;
                    }
                    break;
                case "error":
                    var err = o.ToObject<ServerError>();
                    AgLog.Error("perception." + err.Code, err.Message);
                    ErrorReceived?.Invoke(err);
                    break;
            }
        }
    }
}
