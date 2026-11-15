using System;
using System.Threading.Tasks;
using AG.Core;
using UnityEngine;
using UnityEngine.Rendering;

namespace AG.Vision
{
    /// <summary>
    /// Quest 3/3S passthrough RGB camera through MRUK's <c>Meta.XR.PassthroughCameraAccess</c> (MRUK 209, Horizon OS
    /// v74+; quest-unity/RESEARCH.md §1). Needs <c>horizonos.permission.HEADSET_CAMERA</c> (asked here if missing) and
    /// OVRManager "Enabled Passthrough Camera Access". The component must exist in the scene ("PassthroughCamera").
    ///
    /// What MRUK 209's own code says (PassthroughCameraAccess.cs, read 15 Nov 2026):
    /// <list type="bullet">
    /// <item>Intrinsics are for <c>SensorResolution</c>, principal point with v DOWN; <c>CurrentResolution</c> is a
    /// CENTRED CROP of the sensor to the requested aspect, then scaled (CalcSensorCropRegion). So
    /// fx' = fx s, cx' = (cx - cropX) s, s = current.x / crop.width; plain scaling is wrong when the aspects differ
    /// (e.g. a square sensor read at 1280x960).</item>
    /// <item>The texture is flipped to Unity order (row 0 = bottom), so the CPU copy matches <see cref="CameraFrame.Pixels"/>.</item>
    /// <item><c>GetCameraPose()</c> is the head pose at the latest image's timestamp plus the lens offset: the
    /// camera pose AT CAPTURE, read here when the new image is seen and carried with the frame.</item>
    /// <item><c>GetColors()</c> blocks on a GPU readback; this class uses a non-blocking <c>AsyncGPUReadback</c>
    /// (one in flight, at most <see cref="CpuMaxHz"/>), so CPU frames arrive 1-3 render frames later with their own
    /// capture pose and time.</item>
    /// </list>
    /// Logs <c>camera</c> once at start with every raw and derived value (first device run: check them).
    /// Owner: Vision agent.
    /// </summary>
    public sealed class PcaCameraSource : ICameraSource
    {
        public const string CameraPermission = "horizonos.permission.HEADSET_CAMERA";

        /// <summary>Requested size; MRUK falls back to the nearest smaller supported one. Read <see cref="Intrinsics"/>.</summary>
        public Vector2Int RequestedResolution = new Vector2Int(1280, 960);
        /// <summary>Camera rate (MRUK default 60). Set before StartAsync.</summary>
        public int MaxFramerate = 30;
        /// <summary>Upper bound for CPU readbacks (tracking rate).</summary>
        public float CpuMaxHz = 30f;

#if AG_HAS_MRUK
        readonly Meta.XR.PassthroughCameraAccess _pca;
#endif
        CameraFrame _latest;
        bool _hasLatest;
        long _index;
        DateTime _lastTs;
        bool _readbackBusy;
        double _lastReadbackAt = -1;
        Color32[] _pixels;
        bool _loggedFirst;
        int _readbackErrors;
        bool _blocking;

        public PcaCameraSource(Component passthroughCameraAccess)
        {
#if AG_HAS_MRUK
            _pca = passthroughCameraAccess as Meta.XR.PassthroughCameraAccess;
#endif
        }

        public string Name => "pca";
        public bool IsRunning { get; private set; }
        public CameraIntrinsics Intrinsics { get; private set; }
        public bool CpuPixels { get; set; }
        public event Action<CameraFrame> FrameArrived;

        /// <summary>
        /// Intrinsics of a <paramref name="current"/>-sized image cut from a sensor of <paramref name="sensor"/> pixels
        /// (focal/principal in sensor pixels, v down) the way MRUK does: centred crop to the current aspect, then scale.
        /// </summary>
        public static CameraIntrinsics CropScale(Vector2 focal, Vector2 principal, Vector2Int sensor, Vector2Int current)
        {
            var sf = new Vector2((float)current.x / sensor.x, (float)current.y / sensor.y);
            sf /= Mathf.Max(sf.x, sf.y);
            float cropW = sensor.x * sf.x, cropH = sensor.y * sf.y;
            float cropX = sensor.x * (1f - sf.x) * 0.5f, cropY = sensor.y * (1f - sf.y) * 0.5f;
            float s = current.x / cropW;   // == current.y / cropH
            return new CameraIntrinsics
            {
                fx = focal.x * s, fy = focal.y * s, cx = (principal.x - cropX) * s, cy = (principal.y - cropY) * s,
                width = current.x, height = current.y,
            };
        }

        public async Task<bool> StartAsync()
        {
#if AG_HAS_MRUK
            if (_pca == null) { AgLog.Error("PcaCameraSource", "no PassthroughCameraAccess component"); return false; }
            bool supported = false;
            try { supported = Meta.XR.PassthroughCameraAccess.IsSupported; } catch (Exception e) { AgLog.Error("PcaCameraSource.IsSupported", e.Message); }
            if (!await EnsurePermission()) { AgLog.Error("PcaCameraSource", "HEADSET_CAMERA permission denied"); return false; }
            if (_pca.enabled) _pca.enabled = false;           // resolution/framerate only apply while disabled
            _pca.RequestedResolution = RequestedResolution;
            try { _pca.MaxFramerate = MaxFramerate; } catch (Exception e) { AgLog.Error("PcaCameraSource.MaxFramerate", e.Message); }
            _pca.enabled = true;
            for (int i = 0; i < 200 && !_pca.IsPlaying; i++) await Task.Delay(50);
            IsRunning = _pca.IsPlaying;
            if (!IsRunning)
            {
                AgLog.Error("PcaCameraSource", $"camera did not start in 10 s (supported={supported}; permission? Horizon OS < v74? OVRManager 'Enabled Passthrough Camera Access'?)");
                return false;
            }
            var k = _pca.Intrinsics;
            var cur = _pca.CurrentResolution;
            Intrinsics = CropScale(k.FocalLength, k.PrincipalPoint, k.SensorResolution, cur);
            var naive = new CameraIntrinsics
            {
                fx = k.FocalLength.x, fy = k.FocalLength.y, cx = k.PrincipalPoint.x, cy = k.PrincipalPoint.y,
                width = k.SensorResolution.x, height = k.SensorResolution.y,
            }.Scaled(cur.x, cur.y);
            AgLog.Ev("camera", new
            {
                source = Name, supported, position = _pca.CameraPosition.ToString(),
                requested = new[] { RequestedResolution.x, RequestedResolution.y }, current = new[] { cur.x, cur.y },
                sensor = new[] { k.SensorResolution.x, k.SensorResolution.y },
                focal_px = new[] { k.FocalLength.x, k.FocalLength.y }, principal_px = new[] { k.PrincipalPoint.x, k.PrincipalPoint.y },
                lens_pos_m = new[] { k.LensOffset.position.x, k.LensOffset.position.y, k.LensOffset.position.z },
                lens_euler = new[] { k.LensOffset.rotation.eulerAngles.x, k.LensOffset.rotation.eulerAngles.y, k.LensOffset.rotation.eulerAngles.z },
                fx = Intrinsics.fx, fy = Intrinsics.fy, cx = Intrinsics.cx, cy = Intrinsics.cy, Intrinsics.width, Intrinsics.height,
                scaling = "centred crop + scale (MRUK CalcSensorCropRegion)",
                naive_scaled = new[] { naive.fx, naive.fy, naive.cx, naive.cy },
                hfov_deg = 2f * Mathf.Atan(Intrinsics.width * 0.5f / Intrinsics.fx) * Mathf.Rad2Deg,
                vfov_deg = 2f * Mathf.Atan(Intrinsics.height * 0.5f / Intrinsics.fy) * Mathf.Rad2Deg,
                max_fps = MaxFramerate, cpu_max_hz = CpuMaxHz,
            });
            return true;
#else
            await Task.CompletedTask;
            AgLog.Error("PcaCameraSource", "MRUK not installed");
            return false;
#endif
        }

#if AG_HAS_MRUK
        static async Task<bool> EnsurePermission()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (UnityEngine.Android.Permission.HasUserAuthorizedPermission(CameraPermission)) return true;
            UnityEngine.Android.Permission.RequestUserPermission(CameraPermission);
            for (int i = 0; i < 600; i++)        // the user may take a while to answer the prompt
            {
                await Task.Delay(100);
                if (UnityEngine.Android.Permission.HasUserAuthorizedPermission(CameraPermission)) return true;
            }
            return false;
#else
            await Task.CompletedTask;
            return true;
#endif
        }
#endif

        public void Stop()
        {
#if AG_HAS_MRUK
            if (_pca != null) _pca.enabled = false;
#endif
            IsRunning = false;
        }

        public bool TryGetLatest(out CameraFrame frame) { frame = _latest; return _hasLatest; }

        public void Tick()
        {
#if AG_HAS_MRUK
            if (!IsRunning || !_pca.IsPlaying) return;
            var ts = _pca.Timestamp;
            if (ts == _lastTs) return;                       // no new image (independent of script order)
            _lastTs = ts;
            double now = Time.realtimeSinceStartupAsDouble;
            double age = (DateTime.UtcNow - ts).TotalSeconds; // Timestamp = Unix epoch + camera microseconds
            double capture = age >= 0 && age < 0.5 ? now - age : now;
            var res = _pca.CurrentResolution;
            var f = new CameraFrame
            {
                Index = _index++, TimeS = capture, Width = res.x, Height = res.y, Texture = _pca.GetTexture(),
                Intrinsics = Intrinsics, CameraPose = _pca.GetCameraPose(), HasPose = true, ReplayFrame = -1,
            };
            if (!_loggedFirst)
            {
                _loggedFirst = true;
                AgLog.Ev("camera_first", new { age_ms = age * 1000, clock = age >= 0 && age < 0.5 ? "utc" : "unmapped", tex = f.Texture != null ? f.Texture.GetType().Name : null });
            }
            if (!CpuPixels || f.Texture == null) { Deliver(f); return; }
            if (_readbackBusy || now - _lastReadbackAt < 1.0 / Mathf.Max(1f, CpuMaxHz)) return;
            _lastReadbackAt = now;
            if (_blocking)                                   // fallback: MRUK's own blocking readback
            {
                var colors = _pca.GetColors();
                if (!colors.IsCreated || colors.Length == 0) return;
                if (_pixels == null || _pixels.Length != colors.Length) _pixels = new Color32[colors.Length];
                colors.CopyTo(_pixels);
                f.Pixels = _pixels;
                Deliver(f);
                return;
            }
            _readbackBusy = true;
            AsyncGPUReadback.Request(f.Texture, 0, TextureFormat.RGBA32, req =>
            {
                _readbackBusy = false;
                if (req.hasError && ++_readbackErrors >= 3 && !_blocking)
                {
                    _blocking = true;
                    AgLog.Error("PcaCameraSource", "AsyncGPUReadback fails on the PCA texture; using GetColors (blocking)");
                }
                if (req.hasError || !IsRunning) return;
                var data = req.GetData<Color32>();
                if (_pixels == null || _pixels.Length != data.Length) _pixels = new Color32[data.Length];
                data.CopyTo(_pixels);
                var g = f;
                g.Pixels = _pixels;
                Deliver(g);
            });
#endif
        }

        void Deliver(in CameraFrame f)
        {
            _latest = f;
            _hasLatest = true;
            FrameArrived?.Invoke(f);
        }
    }
}
