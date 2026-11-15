using System;
using System.Threading.Tasks;

namespace AG.Net
{
    /// <summary>
    /// <c>WS {server}/ws/perception/{job}?role=display&amp;source=headset&amp;key=KEY</c>, protocol in
    /// server/perception/README.md. One socket does frames, controls and hand summaries.
    ///
    /// Order: Connect -> (server: hello, status) -> Start(step, action) -> SendFrame at most hello.limits.max_hz with
    /// at most max_unacked un-acked (a slot frees on ack or after ack_timeout_ms) -> SetAction / SetStep as the guide
    /// moves -> Stop. After step_complete the server ignores frames until SetStep.
    ///
    /// Threading: main thread. Messages are dispatched from <see cref="Tick"/> (call every frame); all events fire
    /// on the main thread. Logs <c>perception</c> {event, ms} for every event.
    /// Owner: Net agent.
    /// </summary>
    public interface IPerceptionSocket
    {
        bool Connected { get; }
        /// <summary>From the last hello; defaults until then.</summary>
        Limits Limits { get; }
        /// <summary>Frames sent and not yet acked (or timed out).</summary>
        int Unacked { get; }
        /// <summary>True when a frame may be sent now (rate and un-acked limits).</summary>
        bool CanSendFrame { get; }

        Task ConnectAsync(string jobId);
        Task CloseAsync();

        void Start(int step, int action);
        void SetStep(int step, int action);
        void SetAction(int index);
        void Stop();
        void ShowPart(string expected = null);
        void Mute(string evt = null);
        void Unmute(string evt = null);

        /// <summary>Send one JPEG for <paramref name="step"/>; returns the seq, or -1 if not allowed now.</summary>
        int SendFrame(byte[] jpeg, int step, double captureMs, string hint = "medium");

        /// <summary>~10 Hz from the hand tracker.</summary>
        void SendHands(HandsMsg hands);

        event Action<Hello> HelloReceived;
        event Action<PerceptionStatus> StatusReceived;
        event Action<Ack> AckReceived;
        event Action<PerceptionEvent> EventReceived;
        event Action<ServerError> ErrorReceived;
        /// <summary>Close code and reason (4401 key, 4404 unknown job, 4409 not built, 1006 network).</summary>
        event Action<int, string> Closed;

        void Tick();
    }
}
