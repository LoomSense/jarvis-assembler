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
    /// to swap an implementation, change it here. Also: tracking state -> ghost + status pill (spec §4.12), window
    /// placement beside the object (§2.6), pointer input (hands, controllers, mouse) and keyboard shortcuts
    /// (N next, B back, R repeat, W where am I, M reduced motion, T toggle fake tracking in the editor).
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
        StepPanel _panel;
        StepPanelStub _hud;
        VoicePlayer _voice;
        GhostRenderer _ghost;
        PinchRayInput _input;
        AudioSource _sfx;
        readonly CancellationTokenSource _cts = new CancellationTokenSource();

        int _frames;
        float _frameLogAt, _hudAt, _pingAt;
        bool _serverOk;
        string _lastEvent = "";
        int _lastEventMs;
        bool _everTracked, _lost, _fakeTracked, _cardPlaced;
        float _lastTrackedAt;

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

            var panelGo = new GameObject("StepPanel");
            panelGo.SetActive(false);   // fields before Awake
            _panel = panelGo.AddComponent<StepPanel>();
            _panel.Theme = Theme;
            _panel.Lang = _p.Lang;
            panelGo.SetActive(true);
            _hud = new GameObject("DebugHud").AddComponent<StepPanelStub>();
            _hud.Theme = Theme;
            _hud.IsHud = true;
            _hud.SetVisible(_p.Hud);
            _voice = new GameObject("Voice").AddComponent<VoicePlayer>();
            _voice.transform.SetParent(_panel.transform, false);
            _voice.Init(_api);
            _sfx = AssemblyAnchor.gameObject.AddComponent<AudioSource>();
            _sfx.playOnAwake = false;
            _sfx.spatialBlend = 0.8f;

            var ghostGo = new GameObject("GhostRenderer");
            ghostGo.SetActive(false);
            _ghost = ghostGo.AddComponent<GhostRenderer>();
            _ghost.Font = Theme != null ? Theme.FontAsset : null;
            ghostGo.SetActive(true);
            _input = new GameObject("PinchRayInput").AddComponent<PinchRayInput>();

            _coach = new CoachFlow();
            _panel.Pressed += b =>
            {
                switch (b)
                {
                    case PanelButton.Next: _coach.Next(); break;
                    case PanelButton.Back: _coach.Back(); break;
                    case PanelButton.Repeat: _coach.Repeat(); break;
                    case PanelButton.Rescan: _coach.Rescan(); break;
                    case PanelButton.WhereAmI: _coach.WhereAmI(); break;
                    case PanelButton.Retry: _coach.Retry(); break;
                    case PanelButton.Cancel: _coach.Cancel(); break;
                }
            };
        }

        async void Start()
        {
            // until tracked: put the assembly frame 0.6 m ahead and 0.5 m below the eyes (about table height)
            var cam = Head();
            var fwd = Vector3.ProjectOnPlane(cam.forward, Vector3.up).normalized;
            if (fwd.sqrMagnitude < 1e-4f) fwd = Vector3.forward;
            AssemblyAnchor.SetPositionAndRotation(cam.position + fwd * 0.6f + Vector3.down * 0.5f, Quaternion.LookRotation(fwd, Vector3.up));

            bool camOk = await _camera.StartAsync();
            if (!camOk) AgLog.Error("AppRoot", "camera source " + _camera.Name + " did not start");
            await _coach.RunAsync(new CoachContext
            {
                Lang = _p.Lang, JobId = string.IsNullOrEmpty(_p.Job) ? null : _p.Job,
                Api = _api, Socket = _socket, Jobs = new HttpJobRepository(_p.Server, _p.Key),
                Camera = _camera, Tracker = _tracker, Ghost = _ghost,
                Panel = _panel, Voice = _voice, AssemblyAnchor = AssemblyAnchor, GuideParent = AssemblyAnchor,
                Sfx = _sfx,
            }, _cts.Token);
        }

        Transform Head() => Camera.main != null ? Camera.main.transform : transform;

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
            UpdateTracking();

            var head = Head();
            // window: coach cards ahead of the user until there is an object; then beside the object
            bool haveObject = _coach.Stage >= CoachStage.Register && _coach.Stage != CoachStage.Failed && _ghost.TryGetWorldBounds(out _);
            if (haveObject && _ghost.TryGetWorldBounds(out var wb)) { _panel.PlaceBeside(wb, head.position); _cardPlaced = false; }
            else if (!_cardPlaced && Camera.main != null) { _panel.PlaceInFront(head); _cardPlaced = true; }

            Pointers();
            Keys();

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
                _hud.transform.SetPositionAndRotation(head.position + head.forward * 0.8f - head.right * 0.25f - head.up * 0.2f, Quaternion.LookRotation(head.forward, Vector3.up));
                _hud.Set(new HudState
                {
                    Fps = 1f / Mathf.Max(1e-4f, Time.smoothDeltaTime), ServerOk = _serverOk,
                    Tracker = t.Tracked ? $"{TrackResult.SourceName(t.Source)} {t.ReprojPx:0.0} px" : (_fakeTracked ? "fake" : "none"),
                    LastEvent = _lastEvent, LastEventMs = _lastEventMs, Step = _coach.Step, Action = _coach.Action,
                    Camera = _camera.IsRunning ? $"{_camera.Name} {_camera.Intrinsics.width}x{_camera.Intrinsics.height}" : _camera.Name + " off",
                });
            }
        }

        /// <summary>Tracked / predicted (0.5 s hold) / lost -> ghost state + status pill + one "lost" cue (spec §4.12).</summary>
        void UpdateTracking()
        {
            bool tracked = _tracker.Latest.Tracked || _fakeTracked;
            float now = Time.realtimeSinceStartup;
            if (tracked)
            {
                _lastTrackedAt = now;
                if (!_everTracked || _lost)
                {
                    _everTracked = true;
                    if (_lost) _panel.SetStatusPill(null);
                    _lost = false;
                    _ghost.SetTracking(GhostTracking.Tracked);
                }
            }
            else if (_everTracked && !_lost && now - _lastTrackedAt > 0.5f && _coach.Stage == CoachStage.Guide)
            {
                _lost = true;
                _ghost.SetTracking(GhostTracking.Lost);
                _panel.SetStatusPill(_p.Lang == "el" ? "Χάθηκε το συρτάρι · δείξ' το ξανά" : "Lost the drawer · show it again", StatusKind.Unsure);
                UiSounds.Play(_sfx, Cue.Lost);
                _ = _voice.SayAsync(_p.Lang == "el" ? "Δεν βλέπω το συρτάρι. Κοίτα το ξανά." : "I can't see the drawer. Look at it again.");
            }
        }

        void Pointers()
        {
            _input.Poll();
            PinchRayInput.PointerState? active = null;
            Vector3? hitAt = null;
            foreach (var p in _input.Pointers)
                if (_panel.Raycast(p.Ray, out var hit)) { active = p; hitAt = hit; break; }
            if (active.HasValue) _panel.Pointer(active.Value.Ray, active.Value.Pinch, out _);
            else _panel.Pointer(new Ray(new Vector3(0, -100, 0), Vector3.down), false, out _);   // releases hover/press
            _input.Draw(active, hitAt);
        }

        void Keys()
        {
            if (Input.GetKeyDown(KeyCode.N)) _panel.Press(PanelButton.Next);
            if (Input.GetKeyDown(KeyCode.B)) _panel.Press(PanelButton.Back);
            if (Input.GetKeyDown(KeyCode.R)) _panel.Press(PanelButton.Repeat);
            if (Input.GetKeyDown(KeyCode.W)) _panel.Press(PanelButton.WhereAmI);
            if (Input.GetKeyDown(KeyCode.M)) { _ghost.ReducedMotion = !_ghost.ReducedMotion; AgLog.Ev("reduced_motion", new { on = _ghost.ReducedMotion }); }
#if UNITY_EDITOR
            if (Input.GetKeyDown(KeyCode.T)) _fakeTracked = !_fakeTracked;
            if (Input.GetKeyDown(KeyCode.Alpha1)) _ghost.ShowFeedback(GuideFeedback.Correct);
            if (Input.GetKeyDown(KeyCode.Alpha2)) _ghost.ShowFeedback(GuideFeedback.Wrong, "drawer_side_right");
            if (Input.GetKeyDown(KeyCode.Alpha3)) _ghost.ShowFeedback(GuideFeedback.Unsure);
#endif
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
