using System.Threading;
using AG.Core;
using AG.Guide;
using AG.Net;
using AG.UI;
using AG.Vision;
using UnityEngine;

namespace AG.App
{
    /// <summary>
    /// Composition root: reads launch params, creates one implementation per interface, runs the coach, and moves
    /// <see cref="AssemblyAnchor"/> to the tracked pose every frame. The ONLY place that knows concrete classes:
    /// to swap an implementation, change it here. Owner: lead/integration (agents ask before editing).
    /// </summary>
    [DefaultExecutionOrder(-100)]
    public sealed class AppRoot : MonoBehaviour
    {
        [Tooltip("Object with MRUK's PassthroughCameraAccess (SetupProject creates 'PassthroughCamera').")]
        public GameObject PassthroughCamera;
        public UiTheme Theme;
        [Tooltip("Assembly frame in world: the ghost and its labels are children of this.")]
        public Transform AssemblyAnchor;

        LaunchParams _p;
        HttpServerApi _api;
        PerceptionSocket _socket;
        ICameraSource _camera;
        IObjectTracker _tracker;
        IPoseFilter _filter;
        CoachFlow _coach;
        StepPanelStub _panel, _hud;
        VoicePlayer _voice;
        readonly CancellationTokenSource _cts = new CancellationTokenSource();

        int _frames;
        float _frameLogAt, _hudAt, _pingAt;
        bool _serverOk;
        string _lastEvent = "";
        int _lastEventMs;

        void Awake()
        {
            AgLog.Init();
            _p = LaunchParams.Load();
            AgLog.Ev("start", new { version = Application.version, unity = Application.unityVersion, device = SystemInfo.deviceModel, @params = _p.Redacted() });

            if (AssemblyAnchor == null) AssemblyAnchor = new GameObject("AssemblyAnchor").transform;
            _api = new HttpServerApi(_p.Server, _p.Key);
            _socket = new PerceptionSocket(_p.Server, _p.Key);
            _socket.EventReceived += e => { _lastEvent = e.Event; _lastEventMs = e.LatencyMs ?? 0; };

            _camera = _p.Replay
                ? new ReplayCameraSource(_p.ReplayFolder)
                : new PcaCameraSource(PassthroughCamera != null ? PassthroughCamera.GetComponent("PassthroughCameraAccess") : null);
            _filter = new SmoothingPoseFilter();
            _tracker = new MarkerObjectTracker(_filter);

            _panel = new GameObject("StepPanel").AddComponent<StepPanelStub>();
            _panel.Theme = Theme;
            _hud = new GameObject("DebugHud").AddComponent<StepPanelStub>();
            _hud.Theme = Theme;
            _hud.IsHud = true;
            _hud.SetVisible(_p.Hud);
            _voice = new GameObject("Voice").AddComponent<VoicePlayer>();
            _voice.Init(_api);
            _coach = new CoachFlow();
            _panel.Pressed += b =>
            {
                if (b == PanelButton.Next) _coach.Next();
                else if (b == PanelButton.Back) _coach.Back();
                else if (b == PanelButton.Repeat) _coach.Repeat();
                else if (b == PanelButton.Rescan) _coach.Rescan();
            };
        }

        async void Start()
        {
            // until tracked: put the assembly frame 0.6 m ahead and 0.5 m below the eyes (about table height)
            var cam = Camera.main != null ? Camera.main.transform : transform;
            var fwd = Vector3.ProjectOnPlane(cam.forward, Vector3.up).normalized;
            AssemblyAnchor.SetPositionAndRotation(cam.position + fwd * 0.6f + Vector3.down * 0.5f, Quaternion.LookRotation(fwd, Vector3.up));

            bool camOk = await _camera.StartAsync();
            if (!camOk) AgLog.Error("AppRoot", "camera source " + _camera.Name + " did not start");
            await _coach.RunAsync(new CoachContext
            {
                Lang = _p.Lang, JobId = string.IsNullOrEmpty(_p.Job) ? null : _p.Job,
                Api = _api, Socket = _socket, Jobs = new HttpJobRepository(_p.Server, _p.Key),
                Camera = _camera, Tracker = _tracker, Ghost = new GhostRendererStub(),
                Panel = _panel, Voice = _voice, AssemblyAnchor = AssemblyAnchor, GuideParent = AssemblyAnchor,
            }, _cts.Token);
        }

        void Update()
        {
            AgLog.Pump();
            _camera?.Tick();
            if (_camera != null && _camera.TryGetLatest(out var frame))
            {
                var r = _tracker.Process(frame);
                if (r.Tracked) AssemblyAnchor.SetPositionAndRotation(r.AssemblyInWorld.position, r.AssemblyInWorld.rotation);
                if (_coach.Stage == CoachStage.Guide && _socket.CanSendFrame)
                    _socket.SendFrame(FrameJpeg.Encode(frame, _socket.Limits.LongEdgePx, Mathf.RoundToInt(_socket.Limits.JpegQuality * 100)),
                        _coach.Step, frame.TimeS * 1000.0);
            }
            _socket?.Tick();

            var cam = Camera.main != null ? Camera.main.transform : transform;
            _panel.PlaceBeside(new Bounds(AssemblyAnchor.position, Vector3.one * 0.3f), cam.position);

            _frames++;
            float now = Time.realtimeSinceStartup;
            if (now >= _frameLogAt + 5f)
            {
                if (_frameLogAt > 0) AgLog.Ev("frame", new { fps = _frames / (now - _frameLogAt) });
                _frames = 0;
                _frameLogAt = now;
            }
            if (now >= _pingAt) { _pingAt = now + 10f; Ping(); }
            if (_p.Hud && now >= _hudAt)
            {
                _hudAt = now + 0.25f;
                var t = _tracker.Latest;
                _hud.transform.SetPositionAndRotation(cam.position + cam.forward * 0.8f - cam.right * 0.25f - cam.up * 0.2f, Quaternion.LookRotation(cam.forward, Vector3.up));
                _hud.Set(new HudState
                {
                    Fps = 1f / Mathf.Max(1e-4f, Time.smoothDeltaTime), ServerOk = _serverOk,
                    Tracker = t.Tracked ? $"{TrackResult.SourceName(t.Source)} {t.ReprojPx:0.0} px" : "none",
                    LastEvent = _lastEvent, LastEventMs = _lastEventMs, Step = _coach.Step, Action = _coach.Action,
                    Camera = _camera.IsRunning ? $"{_camera.Name} {_camera.Intrinsics.width}x{_camera.Intrinsics.height}" : _camera.Name + " off",
                });
            }
        }

        async void Ping() => _serverOk = await _api.PingAsync();

        void OnDestroy()
        {
            _cts.Cancel();
            _camera?.Stop();
            _ = _socket?.CloseAsync();
        }
    }
}
