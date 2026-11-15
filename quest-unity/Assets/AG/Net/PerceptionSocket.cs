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
    /// <see cref="IPerceptionSocket"/> on NativeWebSocket (git package com.endel.nativewebsocket, branch upm 1.x:
    /// needs <c>DispatchMessageQueue()</c> every frame, done in <see cref="Tick"/>). Skeleton: framing, limits and
    /// message routing are written; reconnect/backoff is a TODO for the Net agent. UNVERIFIED on Android IL2CPP.
    /// </summary>
    public sealed class PerceptionSocket : IPerceptionSocket
    {
        readonly string _server, _key;
#if AG_HAS_NATIVEWS
        NativeWebSocket.WebSocket _ws;
#endif
        readonly Dictionary<int, float> _sentAt = new Dictionary<int, float>();   // seq -> realtime s
        float _lastFrameAt = -999f;
        int _seq;

        public PerceptionSocket(string server, string key) { _server = server.TrimEnd('/'); _key = key; }

        public bool Connected { get; private set; }
        public Limits Limits { get; private set; } = new Limits();
        public int Unacked => _sentAt.Count;
        public bool CanSendFrame => Connected && Unacked < Limits.MaxUnacked && Time.realtimeSinceStartup - _lastFrameAt >= 1f / Mathf.Max(0.1f, Limits.MaxHz);

        public event Action<Hello> HelloReceived;
        public event Action<PerceptionStatus> StatusReceived;
        public event Action<Ack> AckReceived;
        public event Action<PerceptionEvent> EventReceived;
        public event Action<ServerError> ErrorReceived;
        public event Action<int, string> Closed;

        public async Task ConnectAsync(string jobId)
        {
            var http = _server.StartsWith("https") ? "https" : "http";
            var ws = (http == "https" ? "wss" : "ws") + _server.Substring(http.Length);
            var url = Http.WithKey($"{ws}/ws/perception/{Uri.EscapeDataString(jobId)}?role=display&source=headset&device=quest-unity", _key);
#if AG_HAS_NATIVEWS
            _ws = new NativeWebSocket.WebSocket(url);
            _ws.OnOpen += () => Connected = true;
            _ws.OnClose += code => { Connected = false; _sentAt.Clear(); Closed?.Invoke((int)code, code.ToString()); };
            _ws.OnError += e => AgLog.Error("PerceptionSocket", e);
            _ws.OnMessage += bytes => Route(Encoding.UTF8.GetString(bytes));
            _ = _ws.Connect();   // completes when the socket closes in NativeWebSocket 1.x, so do not await it
            for (int i = 0; i < 100 && !Connected; i++) await Task.Delay(50);
            if (!Connected) AgLog.Error("PerceptionSocket", "connect timeout");
#else
            await Task.CompletedTask;
            AgLog.Error("PerceptionSocket", "NativeWebSocket not installed; " + url.Split('?')[0]);
#endif
        }

        public async Task CloseAsync()
        {
#if AG_HAS_NATIVEWS
            if (_ws != null) await _ws.Close();
#else
            await Task.CompletedTask;
#endif
        }

        void SendJson(object o)
        {
#if AG_HAS_NATIVEWS
            if (Connected) _ = _ws.SendText(JsonConvert.SerializeObject(o));
#endif
        }

        public void Start(int step, int action) { _seq = 0; SendJson(new { type = "start", step, action }); }
        public void SetStep(int step, int action) { _seq = 0; SendJson(new { type = "step", n = step, action }); }
        public void SetAction(int index) => SendJson(new { type = "action", index });
        public void Stop() => SendJson(new { type = "stop" });
        public void ShowPart(string expected = null) => SendJson(new { type = "show_part", expected });
        public void Mute(string evt = null) => SendJson(evt == null ? (object)new { type = "mute" } : new { type = "mute", @event = evt });
        public void Unmute(string evt = null) => SendJson(evt == null ? (object)new { type = "unmute" } : new { type = "unmute", @event = evt });
        public void SendHands(HandsMsg hands) => SendJson(hands);

        public int SendFrame(byte[] jpeg, int step, double captureMs, string hint = "medium")
        {
            ExpireAcks();
            if (!CanSendFrame || jpeg == null) return -1;
            int seq = ++_seq;
            var packet = Pack(new FrameHeader { Seq = seq, Step = step, Ts = captureMs, Hint = hint }, jpeg);
#if AG_HAS_NATIVEWS
            _ = _ws.Send(packet);
#endif
            _sentAt[seq] = Time.realtimeSinceStartup;
            _lastFrameAt = Time.realtimeSinceStartup;
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
#if AG_HAS_NATIVEWS && (!UNITY_WEBGL || UNITY_EDITOR)
            _ws?.DispatchMessageQueue();
#endif
            ExpireAcks();
        }

        void ExpireAcks()
        {
            if (_sentAt.Count == 0) return;
            float now = Time.realtimeSinceStartup, limit = Limits.AckTimeoutMs / 1000f;
            List<int> old = null;
            foreach (var kv in _sentAt) if (now - kv.Value > limit) (old ??= new List<int>()).Add(kv.Key);
            if (old != null) foreach (var s in old) _sentAt.Remove(s);
        }

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
                    HelloReceived?.Invoke(h);
                    break;
                case "status": StatusReceived?.Invoke(o.ToObject<PerceptionStatus>()); break;
                case "ack":
                    var a = o.ToObject<Ack>();
                    if (a.Seq.HasValue) _sentAt.Remove(a.Seq.Value);
                    AckReceived?.Invoke(a);
                    break;
                case "event":
                    var ev = o.ToObject<PerceptionEvent>();
                    AgLog.Ev("perception", new { @event = ev.Event, ms = ev.LatencyMs, step = ev.Step, action = ev.Action, p = ev.P });
                    EventReceived?.Invoke(ev);
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
