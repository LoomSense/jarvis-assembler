using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net.WebSockets;
using System.Threading;
using System.Threading.Tasks;

namespace AG.Net
{
    /// <summary>
    /// Minimal WebSocket client on <see cref="ClientWebSocket"/> with its own send and receive loops on worker threads,
    /// so the main thread never blocks on the network (NativeWebSocket 1.x sends synchronously on the caller's thread
    /// and can reorder text vs binary). Messages are queued in send order; everything received goes to
    /// <see cref="Inbox"/> for the owner to drain on the main thread. One connection per instance.
    /// </summary>
    public sealed class WsTransport : IDisposable
    {
        public enum Kind { Open, Text, Binary, Closed, Error }

        public struct Msg
        {
            public Kind Kind;
            public string Text;
            public byte[] Data;
            public int CloseCode;
            public string Reason;
        }

        readonly ClientWebSocket _ws = new ClientWebSocket();
        readonly CancellationTokenSource _cts = new CancellationTokenSource();
        readonly BlockingCollection<(byte[] data, bool text)> _out = new BlockingCollection<(byte[], bool)>(new ConcurrentQueue<(byte[], bool)>());
        public readonly ConcurrentQueue<Msg> Inbox = new ConcurrentQueue<Msg>();
        int _closedSignalled;

        public bool IsOpen => _ws.State == WebSocketState.Open;
        /// <summary>Messages queued and not yet handed to the socket.</summary>
        public int Pending => _out.Count;

        /// <summary>Connects (with timeout) and starts the loops. Never throws: failures arrive as Error + Closed.</summary>
        public async Task ConnectAsync(Uri uri, int timeoutMs = 8000)
        {
            try
            {
                _ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(15);
                using (var t = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token))
                {
                    t.CancelAfter(timeoutMs);
                    await _ws.ConnectAsync(uri, t.Token).ConfigureAwait(false);
                }
                Inbox.Enqueue(new Msg { Kind = Kind.Open });
                _ = Task.Run(SendLoop);
                _ = Task.Run(ReceiveLoop);
            }
            catch (Exception e)
            {
                Inbox.Enqueue(new Msg { Kind = Kind.Error, Reason = e.GetBaseException().Message });
                SignalClosed(1006, "connect failed");
            }
        }

        public bool SendText(string s) => Enqueue(System.Text.Encoding.UTF8.GetBytes(s), true);
        public bool SendBinary(byte[] b) => Enqueue(b, false);

        bool Enqueue(byte[] b, bool text)
        {
            if (_out.IsAddingCompleted) return false;
            try { _out.Add((b, text)); return true; } catch (InvalidOperationException) { return false; }
        }

        async Task SendLoop()
        {
            try
            {
                foreach (var (data, text) in _out.GetConsumingEnumerable(_cts.Token))
                {
                    if (_ws.State != WebSocketState.Open) break;
                    await _ws.SendAsync(new ArraySegment<byte>(data), text ? WebSocketMessageType.Text : WebSocketMessageType.Binary, true, _cts.Token).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception e)
            {
                Inbox.Enqueue(new Msg { Kind = Kind.Error, Reason = "send: " + e.GetBaseException().Message });
                SignalClosed(1006, "send failed");
            }
        }

        async Task ReceiveLoop()
        {
            var buf = new byte[64 * 1024];
            var ms = new MemoryStream();
            try
            {
                while (!_cts.IsCancellationRequested)
                {
                    var r = await _ws.ReceiveAsync(new ArraySegment<byte>(buf), _cts.Token).ConfigureAwait(false);
                    if (r.MessageType == WebSocketMessageType.Close)
                    {
                        SignalClosed((int)(r.CloseStatus ?? WebSocketCloseStatus.NormalClosure), r.CloseStatusDescription);
                        try { await _ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "", CancellationToken.None).ConfigureAwait(false); } catch (Exception) { }
                        return;
                    }
                    ms.Write(buf, 0, r.Count);
                    if (!r.EndOfMessage) continue;
                    var bytes = ms.ToArray();
                    ms.SetLength(0);
                    Inbox.Enqueue(r.MessageType == WebSocketMessageType.Text
                        ? new Msg { Kind = Kind.Text, Text = System.Text.Encoding.UTF8.GetString(bytes) }
                        : new Msg { Kind = Kind.Binary, Data = bytes });
                }
            }
            catch (OperationCanceledException) { SignalClosed(1000, "closed"); }
            catch (Exception e)
            {
                Inbox.Enqueue(new Msg { Kind = Kind.Error, Reason = "receive: " + e.GetBaseException().Message });
                SignalClosed(1006, "network");
            }
        }

        void SignalClosed(int code, string reason)
        {
            if (Interlocked.Exchange(ref _closedSignalled, 1) == 1) return;
            _out.CompleteAdding();
            Inbox.Enqueue(new Msg { Kind = Kind.Closed, CloseCode = code, Reason = reason });
        }

        public async Task CloseAsync()
        {
            try
            {
                if (_ws.State == WebSocketState.Open)
                {
                    using (var t = new CancellationTokenSource(1500))
                        await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", t.Token).ConfigureAwait(false);
                }
            }
            catch (Exception) { }
            Dispose();
        }

        public void Dispose()
        {
            SignalClosed(1000, "disposed");
            try { _cts.Cancel(); } catch (Exception) { }
            try { _ws.Abort(); } catch (Exception) { }
            _ws.Dispose();
        }
    }
}
