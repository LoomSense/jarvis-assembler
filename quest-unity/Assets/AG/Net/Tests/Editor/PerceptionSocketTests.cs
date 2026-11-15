using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using AG.Core;
using Newtonsoft.Json.Linq;
using NUnit.Framework;
using UnityEngine;
using UnityEngine.TestTools;

namespace AG.Net.Tests
{
    public class PerceptionSocketTests
    {
        [Test]
        public void Pack_BigEndianHeader()
        {
            var jpeg = new byte[] { 0xFF, 0xD8, 0xFF, 1, 2 };
            var b = PerceptionSocket.Pack(new FrameHeader { Seq = 7, Step = 2, Ts = 1 }, jpeg);
            int n = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3];
            var head = JObject.Parse(Encoding.UTF8.GetString(b, 4, n));
            Assert.AreEqual(7, (int)head["seq"]);
            Assert.AreEqual(2, (int)head["step"]);
            Assert.AreEqual(0xFF, b[4 + n]);
            Assert.AreEqual(b.Length, 4 + n + jpeg.Length);
        }

        [Test]
        public void Hands_AtTargetStillRaised()
        {
            var h = new HandsSummarizer();
            var head = new Vector3(0, 1.6f, 0);
            var target = new Vector3(0, 0.8f, 0.5f);
            for (int i = 0; i < 30; i++) h.Update(1 / 30f, null, null, target + new Vector3(0.02f, 0, 0), target + new Vector3(0.03f, 0, 0), head, target);
            Assert.IsTrue(h.Present); Assert.IsTrue(h.AtTarget); Assert.IsTrue(h.Still); Assert.IsFalse(h.Raised);
            for (int i = 0; i < 5; i++) h.Update(1 / 30f, null, null, head + new Vector3(0, 0.1f + i * 0.05f, 0.2f), null, head, target);
            Assert.IsTrue(h.Raised); Assert.IsFalse(h.AtTarget); Assert.IsFalse(h.Still);
            var m = h.TryEmit(1.0, 1000);
            Assert.IsNotNull(m); Assert.IsNull(h.TryEmit(1.05, 1050));
        }

        /// <summary>
        /// Local mock of the server (HttpListener WebSocket): hello with limits -> start -> binary frame -> ack +
        /// step_complete event; then the server drops the connection; the client must reconnect by itself and re-send
        /// start (resume) after the new hello.
        /// </summary>
        [UnityTest]
        public IEnumerator Socket_FrameAckEventReconnectResume()
        {
            AgLog.Init();
            int port = 18000 + new System.Random().Next(1000);
            var server = new MockServer(port);
            server.Start();
            var sock = new PerceptionSocket($"http://127.0.0.1:{port}", "testkey");
            int stepComplete = 0, opens = 0, closes = 0;
            Hello hello = null;
            sock.StepComplete += e => stepComplete++;
            sock.HelloReceived += h => hello = h;
            sock.ConnectionChanged += up => { if (up) opens++; else closes++; };
            var connect = sock.ConnectAsync("malm-drawer");
            yield return Pump(sock, () => hello != null, 5f);
            Assert.IsNotNull(hello, "no hello");
            Assert.AreEqual(50f, sock.Limits.MaxHz);
            sock.Start(2, 0);
            yield return Pump(sock, () => server.Texts.Count >= 1, 3f);
            yield return Pump(sock, () => sock.CanSendFrame, 2f);
            int seq = sock.SendFrame(new byte[] { 0xFF, 0xD8, 0xFF, 0, 1, 2 }, 2, 123);
            Assert.Greater(seq, 0);
            yield return Pump(sock, () => stepComplete == 1, 3f);
            Assert.AreEqual(1, stepComplete, "step_complete event");
            Assert.AreEqual(0, sock.Unacked);
            Assert.GreaterOrEqual(sock.LastAckRttMs, 0);

            server.DropCurrent();
            yield return Pump(sock, () => closes >= 1, 3f);
            yield return Pump(sock, () => opens >= 2 && server.Connections >= 2, 6f);
            Assert.GreaterOrEqual(server.Connections, 2, "no reconnect");
            yield return Pump(sock, () => server.StartsReceived >= 2, 3f);
            Assert.AreEqual(2, server.StartsReceived, "start not re-sent after reconnect");
            Assert.AreEqual("open", sock.State);
            Assert.IsTrue(server.LastQuery.Contains("key=testkey") && server.LastQuery.Contains("role=display"));
            var close = sock.CloseAsync();
            yield return Pump(sock, () => close.IsCompleted, 2f);
            server.Stop();
            _ = connect;
        }

        static IEnumerator Pump(PerceptionSocket s, Func<bool> until, float timeoutS)
        {
            var t0 = DateTime.UtcNow;
            while (!until() && (DateTime.UtcNow - t0).TotalSeconds < timeoutS)
            {
                s.Tick();
                yield return null;
            }
            s.Tick();
        }

        sealed class MockServer
        {
            // Raw TcpListener + manual RFC 6455 handshake + WebSocket.CreateFromStream (HttpListener's WebSocket
            // support does not accept connections under the editor's Mono).
            readonly System.Net.Sockets.TcpListener _tcp;
            readonly CancellationTokenSource _cts = new CancellationTokenSource();
            WebSocket _current;
            System.Net.Sockets.TcpClient _currentClient;
            public readonly ConcurrentQueue<string> Texts = new ConcurrentQueue<string>();
            public int Connections, StartsReceived;
            public string LastQuery = "";

            public MockServer(int port) { _tcp = new System.Net.Sockets.TcpListener(IPAddress.Loopback, port); }

            public void Start()
            {
                _tcp.Start();
                Task.Run(AcceptLoop);
            }

            async Task AcceptLoop()
            {
                while (!_cts.IsCancellationRequested)
                {
                    System.Net.Sockets.TcpClient c;
                    try { c = await _tcp.AcceptTcpClientAsync(); } catch (Exception) { return; }
                    var stream = c.GetStream();
                    var req = new StringBuilder();
                    var one = new byte[1];
                    while (!req.ToString().EndsWith("\r\n\r\n") && await stream.ReadAsync(one, 0, 1) == 1) req.Append((char)one[0]);
                    var lines = req.ToString().Split(new[] { "\r\n" }, StringSplitOptions.None);
                    var target = lines[0].Split(' ')[1];
                    LastQuery = target.Contains("?") ? target.Substring(target.IndexOf('?')) : "";
                    string key = null;
                    foreach (var l in lines)
                        if (l.StartsWith("Sec-WebSocket-Key:", StringComparison.OrdinalIgnoreCase)) key = l.Substring(18).Trim();
                    string accept;
                    using (var sha = System.Security.Cryptography.SHA1.Create())
                        accept = Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
                    var resp = Encoding.ASCII.GetBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
                    await stream.WriteAsync(resp, 0, resp.Length);
                    var ws = WebSocket.CreateFromStream(stream, true, null, TimeSpan.FromSeconds(30));
                    _current = ws;
                    _currentClient = c;
                    Interlocked.Increment(ref Connections);
                    _ = Task.Run(() => Serve(ws));
                }
            }

            static Task Send(WebSocket ws, string s) =>
                ws.SendAsync(new ArraySegment<byte>(Encoding.UTF8.GetBytes(s)), WebSocketMessageType.Text, true, CancellationToken.None);

            async Task Serve(WebSocket ws)
            {
                await Send(ws, "{\"type\":\"hello\",\"protocol\":1,\"job_id\":\"malm-drawer\",\"vision\":\"simulated\",\"limits\":{\"max_unacked\":5,\"max_hz\":50,\"ack_timeout_ms\":9000,\"long_edge_px\":768,\"jpeg_quality\":0.7}}");
                var buf = new byte[1 << 20];
                try
                {
                    while (ws.State == WebSocketState.Open)
                    {
                        var r = await ws.ReceiveAsync(new ArraySegment<byte>(buf), _cts.Token);
                        if (r.MessageType == WebSocketMessageType.Close) return;
                        if (r.MessageType == WebSocketMessageType.Text)
                        {
                            var text = Encoding.UTF8.GetString(buf, 0, r.Count);
                            Texts.Enqueue(text);
                            if ((string)JObject.Parse(text)["type"] == "start") Interlocked.Increment(ref StartsReceived);
                            continue;
                        }
                        int n = (buf[0] << 24) | (buf[1] << 16) | (buf[2] << 8) | buf[3];
                        var head = JObject.Parse(Encoding.UTF8.GetString(buf, 4, n));
                        int seq = (int)head["seq"];
                        await Send(ws, "{\"type\":\"ack\",\"seq\":" + seq + ",\"latency_ms\":12,\"used\":true,\"dropped\":null}");
                        await Send(ws, "{\"type\":\"event\",\"event\":\"step_complete\",\"id\":1,\"step\":2,\"seq\":" + seq + ",\"latency_ms\":40,\"say\":{\"en\":\"Step 2 done.\",\"el\":\"\"},\"speak\":true}");
                    }
                }
                catch (Exception) { }
            }

            public void DropCurrent()
            {
                try { _current?.Abort(); _currentClient?.Close(); } catch (Exception) { }
            }

            public void Stop()
            {
                _cts.Cancel();
                try { _tcp.Stop(); } catch (Exception) { }
            }
        }
    }
}
