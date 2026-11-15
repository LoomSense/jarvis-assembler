using System;
using System.Collections.Generic;
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
    /// placement beside the object (§2.6), pointer input (hands, controllers, mouse), push-to-talk voice commands
    /// (POST /api/voice), the camera preview ornament, hand summaries to the socket, and keyboard shortcuts
    /// (N next, B back, R repeat, W where am I, M reduced motion, P camera preview, V hold to talk, T fake tracking).
    /// </summary>
    [DefaultExecutionOrder(-100)]
    public sealed class AppRoot : MonoBehaviour
    {
        [Tooltip("Object with MRUK's PassthroughCameraAccess (SetupProject creates 'PassthroughCamera').")]
        public GameObject PassthroughCamera;
        public UiTheme Theme;
        [Tooltip("Assembly frame in world: the ghost and its labels are children of this.")]
        public Transform AssemblyAnchor;

        /// <summary>Last server box for the current camera (identify / parts), image UV with y up; drawn on the camera preview.</summary>
        public Rect? PreviewBox;

        LaunchParams _p;
        HttpServerApi _api;
        PerceptionSocket _socket;
        ICameraSource _camera;
        IObjectTracker _tracker;
        IPoseFilter _filter;
        HandJointSource _handJoints;
        HandsSummarizer _hands;
        TwoCornerTracker _touch;
        CoachFlow _coach;
        StepPanel _panel;
        StepPanelStub _hud;
        VoicePlayer _voice;
        GhostRenderer _ghost;
        PinchRayInput _input;
        AudioSource _sfx;
        VoiceCommandClient _voiceApi;
        readonly MicRecorder _mic = new MicRecorder();
        readonly CancellationTokenSource _cts = new CancellationTokenSource();

        int _frames;
        float _frameLogAt, _hudAt, _pingAt, _previewAt;
        bool _serverOk, _jpegBusy;
        string _lastEvent = "";
        int _lastEventMs;
        bool _everTracked, _lost, _fakeTracked, _cardPlaced, _anchorPlaced;
        float _lastTrackedAt;
        // camera fps for the preview caption
        long _camIndex0 = -1;
        float _camT0;
        float _camFps;
        Texture2D _cpuPreview;
        // voice
        bool _micBusy, _tapMode;
        float _micStartedAt, _speechAt;

        void Awake()
        {
            AgLog.Init();
            _p = LaunchParams.Load();
            AgLog.Ev("start", new { version = Application.version, unity = Application.unityVersion, device = SystemInfo.deviceModel, @params = _p.Redacted() });

            if (AssemblyAnchor == null) AssemblyAnchor = new GameObject("AssemblyAnchor").transform;
            _api = new HttpServerApi(_p.Server, _p.Key);
            _voiceApi = new VoiceCommandClient(_p.Server, _p.Key);
            _socket = new PerceptionSocket(_p.Server, _p.Key);
            _socket.EventReceived += e => { _lastEvent = e.Event; _lastEventMs = e.LatencyMs ?? 0; };

            _camera = _p.Replay
                ? new ReplayCameraSource(_p.ReplayFolder)
                : new PcaCameraSource(PassthroughCamera != null ? PassthroughCamera.GetComponent("PassthroughCameraAccess") : null);
            _filter = new SmoothingPoseFilter();
            _tracker = new MarkerObjectTracker(_filter);
            // Vision round 1 wiring (README "Vision and Net"): CPU pixels for the tag detector, strip on the drawer front
            _camera.CpuPixels = true;
            var (markerCfg, stripInAsm) = MarkerSetup.LoadForApp();
            _tracker.Configure(markerCfg);
            var mt = (MarkerObjectTracker)_tracker;
            mt.SetStripInAssembly(stripInAsm);
            mt.LogEveryDetection = _p.Replay;
            _handJoints = new HandJointSource();
            _hands = new HandsSummarizer();
            _touch = new TwoCornerTracker();
            _touch.SetMalmCorners();
            _touch.Updated += r => AssemblyAnchor.SetPositionAndRotation(r.AssemblyInWorld.position, r.AssemblyInWorld.rotation);

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
            _coach.StageChanged += OnStage;
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
            _panel.MicHeld += held => { if (held) MicDown(); else MicUp(); };
            _panel.PreviewToggled += on => AgLog.Ev("camera_preview", new { on });
        }

        async void Start()
        {
            PlaceUntrackedAnchor();
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

        /// <summary>
        /// Until tracked: the assembly frame 0.55 m in front of the head and about 0.22 m below the eyes (table
        /// height for a seated user, inside the central 40 deg of view), facing the user. Re-done when the guide
        /// starts untracked, because at Start the head pose may not be known yet.
        /// </summary>
        void PlaceUntrackedAnchor()
        {
            var cam = Head();
            var fwd = Vector3.ProjectOnPlane(cam.forward, Vector3.up);
            if (fwd.sqrMagnitude < 1e-4f) fwd = Vector3.forward;
            fwd.Normalize();
            AssemblyAnchor.SetPositionAndRotation(cam.position + fwd * 0.55f + Vector3.down * 0.22f, Quaternion.LookRotation(fwd, Vector3.up));
            _anchorPlaced = cam.position.sqrMagnitude > 1e-6f || Camera.main != null;
        }

        void OnStage(CoachStage s)
        {
            if ((s == CoachStage.Register || s == CoachStage.Guide) && !_everTracked) PlaceUntrackedAnchor();
            // untracked, the window stays close to the object (both inside the central 40 deg); tracked: spec §2.6
            _panel.MaxSideDeg = _everTracked ? 0f : 24f;
        }

        public void ShowPickTargets(IReadOnlyList<PickTarget> targets) => _ghost.ShowPickTargets(targets);

        Transform Head() => Camera.main != null ? Camera.main.transform : transform;

        void Update()
        {
            AgLog.Pump();
            _camera?.Tick();
            _handJoints.Tick();
            _touch.TickCapture(Time.deltaTime, Time.realtimeSinceStartupAsDouble);
            var head = Head();
            if (_camera != null && _camera.TryGetLatest(out var frame))
            {
                var r = _tracker.Process(frame);
                if (r.Tracked) AssemblyAnchor.SetPositionAndRotation(r.AssemblyInWorld.position, r.AssemblyInWorld.rotation);
                if (_coach.Stage == CoachStage.Guide && _socket.CanSendFrame && !_jpegBusy) SendFrameAsync(frame, _coach.Step);
                CameraPreview(frame);
            }
            else if (Time.realtimeSinceStartup >= _previewAt + 1f) { _previewAt = Time.realtimeSinceStartup; _panel.SetCameraPreview(null, 0, null); }
            Bounds? target = _ghost.TryGetGhostWorldBounds(out var gb) ? gb : (Bounds?)null;
            _hands.Update(Time.deltaTime, _handJoints.LeftPalm, _handJoints.LeftIndexTip, _handJoints.RightPalm, _handJoints.RightIndexTip, head.position, target);
            if (_socket.Connected)
            {
                var hm = _hands.TryEmit(Time.realtimeSinceStartupAsDouble, AgLog.Ms);
                if (hm != null) _socket.SendHands(hm);
            }
            _socket?.Tick();
            UpdateTracking();
            Voice();

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

        async void SendFrameAsync(CameraFrame f, int step)
        {
            _jpegBusy = true;
            try
            {
                var jpeg = await FrameJpeg.EncodeAsync(f, _socket.Limits.LongEdgePx, Mathf.RoundToInt(_socket.Limits.JpegQuality * 100));
                if (jpeg != null) _socket.SendFrame(jpeg, step, f.TimeS * 1000.0);
            }
            catch (Exception e) { AgLog.Error("AppRoot.frame", e.Message); }
            finally { _jpegBusy = false; }
        }

        /// <summary>Camera preview ornament at ~10 Hz: the source's texture, else a copy of its CPU pixels.</summary>
        void CameraPreview(in CameraFrame f)
        {
            float now = Time.realtimeSinceStartup;
            if (_camIndex0 < 0 || now - _camT0 > 2f) { if (_camIndex0 >= 0 && now > _camT0) _camFps = (f.Index - _camIndex0) / (now - _camT0); _camIndex0 = f.Index; _camT0 = now; }
            if (now < _previewAt + 0.1f || !_panel.PreviewVisible) return;
            _previewAt = now;
            Texture tex = f.Texture;
            if (tex == null && f.Pixels != null && f.Width > 0 && f.Height > 0)
            {
                if (_cpuPreview == null || _cpuPreview.width != f.Width || _cpuPreview.height != f.Height)
                    _cpuPreview = new Texture2D(f.Width, f.Height, TextureFormat.RGBA32, false);
                _cpuPreview.SetPixels32(f.Pixels);
                _cpuPreview.Apply(false);
                tex = _cpuPreview;
            }
            _panel.SetCameraPreview(tex, _camFps, PreviewBox);
        }

        // ------------------------------------------------------------------ voice (push-to-talk, POST /api/voice)

        void MicDown()
        {
            if (_micBusy) return;
            if (_mic.Recording) { MicFinish(); return; }   // second tap in tap mode
            if (_coach.Stage != CoachStage.Guide || _coach.JobId == null) { _ = _voice.SayAsync(_p.Lang == "el" ? "Πρώτα να φορτώσει ο οδηγός." : "Wait for the guide to load."); return; }
            if (!_mic.Start()) { AgLog.Error("voice.mic", "could not start (permission?)"); return; }
            _voice.StopSpeaking();
            _micStartedAt = Time.realtimeSinceStartup;
            _speechAt = 0;
            _tapMode = false;
            _panel.SetMicState(MicState.Listening);
            AgLog.Ev("voice_listen", new { step = _coach.Step, action = _coach.Action });
        }

        void MicUp()
        {
            if (!_mic.Recording) return;
            // a short tap keeps listening until a pause after speech (or the next tap); a hold ends at release
            if (Time.realtimeSinceStartup - _micStartedAt < 0.35f) { _tapMode = true; return; }
            MicFinish();
        }

        void Voice()
        {
            if (!_mic.Recording) return;
            bool more = _mic.Tick();
            _panel.SetMicState(MicState.Listening, _mic.Level);
            float now = Time.realtimeSinceStartup;
            if (_mic.Level > 0.02f) _speechAt = now;
            if (!more || (_tapMode && ((_speechAt > 0 && now - _speechAt > 1.2f) || (_speechAt == 0 && now - _micStartedAt > 5f)))) MicFinish();
        }

        async void MicFinish()
        {
            var wav = _mic.Stop();
            if (wav == null) { _panel.SetMicState(MicState.Idle); return; }
            _micBusy = true;
            _panel.SetMicState(MicState.Thinking);
            try
            {
                var r = await _voiceApi.SendAsync(_coach.JobId, new VoiceRequest
                {
                    Audio = Convert.ToBase64String(wav), Mime = "audio/wav", Lang = _p.Lang,
                    Step = _coach.Step, Action = _coach.Action, Speak = true, Rate = MicRecorder.Rate,
                }, _cts.Token);
                string say = _coach.ApplyVoice(r);
                if (say != null)
                {
                    if (!string.IsNullOrEmpty(r.Tts)) _voice.PlayWav(Convert.FromBase64String(r.Tts), say);
                    else _ = _voice.SayAsync(say, true);
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception e)
            {
                AgLog.Error("voice", e.Message);
                _ = _voice.SayAsync(_p.Lang == "el" ? "Δεν σε άκουσα. Ξανά." : "I didn't catch that. Try again.", true);
            }
            finally { _micBusy = false; _panel.SetMicState(MicState.Idle); }
        }

        /// <summary>Tracked / predicted (0.5 s hold) / lost -> ghost state + status pill + one "lost" cue (spec §4.12).</summary>
        void UpdateTracking()
        {
            bool tracked = _tracker.Latest.Tracked || _touch.Latest.Tracked || _fakeTracked;
            float now = Time.realtimeSinceStartup;
            if (tracked)
            {
                _lastTrackedAt = now;
                if (!_everTracked || _lost)
                {
                    _everTracked = true;
                    _panel.MaxSideDeg = 0f;
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
            if (Input.GetKeyDown(KeyCode.V)) MicDown();
            if (Input.GetKeyUp(KeyCode.V)) MicUp();
            if (Input.GetKeyDown(KeyCode.P)) _panel.TogglePreview();
            if (Input.GetKeyDown(KeyCode.M))
            {
                _ghost.ReducedMotion = !_ghost.ReducedMotion;
                _panel.ReducedMotion = _ghost.ReducedMotion;
                AgLog.Ev("reduced_motion", new { on = _ghost.ReducedMotion });
            }
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
            _mic.Cancel();
            _camera?.Stop();
            (_tracker as IDisposable)?.Dispose();
            _ = _socket?.CloseAsync();
        }
    }
}
