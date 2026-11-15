using System;
using System.Threading.Tasks;
using AG.Core;
using UnityEngine;

namespace AG.Vision
{
    /// <summary>
    /// Quest 3/3S passthrough RGB camera through MRUK's <c>Meta.XR.PassthroughCameraAccess</c> component (MRUK v81+,
    /// Horizon OS v74+; see quest-unity/RESEARCH.md §1). Needs <c>horizonos.permission.HEADSET_CAMERA</c> and
    /// OVRManager "Enabled Passthrough Camera Access".
    ///
    /// The component must exist in the scene (SetupProject adds one under "PassthroughCamera"); this class only reads
    /// it. Intrinsics come for the SENSOR resolution and are scaled to CurrentResolution here (UNVERIFIED: check
    /// against the sample's CameraToWorld scene). Owner: Vision agent.
    /// </summary>
    public sealed class PcaCameraSource : ICameraSource
    {
#if AG_HAS_MRUK
        readonly Meta.XR.PassthroughCameraAccess _pca;
        Texture2D _cpu;
#endif
        CameraFrame _latest;
        bool _hasLatest;
        long _index;

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

        public async Task<bool> StartAsync()
        {
#if AG_HAS_MRUK
            if (_pca == null) { AgLog.Error("PcaCameraSource", "no PassthroughCameraAccess component"); return false; }
            // permission: HEADSET_CAMERA is requested by the component / OVRManager (UNVERIFIED which one);
            // TODO(Vision agent): request explicitly with UnityEngine.Android.Permission if the prompt does not show.
            _pca.enabled = true;
            for (int i = 0; i < 100 && !_pca.IsPlaying; i++) await Task.Delay(50);
            IsRunning = _pca.IsPlaying;
            if (!IsRunning) AgLog.Error("PcaCameraSource", "camera did not start (permission? Horizon OS < v74?)");
            return IsRunning;
#else
            await Task.CompletedTask;
            AgLog.Error("PcaCameraSource", "MRUK not installed");
            return false;
#endif
        }

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
            if (!IsRunning || !_pca.IsUpdatedThisFrame) return;
            var res = _pca.CurrentResolution;
            var k = _pca.Intrinsics;
            // TODO(Vision agent): confirm field types (RESEARCH.md says FocalLength/PrincipalPoint/SensorResolution).
            var sensor = new CameraIntrinsics
            {
                fx = k.FocalLength.x, fy = k.FocalLength.y, cx = k.PrincipalPoint.x, cy = k.PrincipalPoint.y,
                width = (int)k.SensorResolution.x, height = (int)k.SensorResolution.y,
            };
            Intrinsics = sensor.width == res.x ? sensor : sensor.Scaled(res.x, res.y);
            var f = new CameraFrame
            {
                Index = _index++,
                TimeS = Time.realtimeSinceStartupAsDouble, // TODO: map _pca.Timestamp (DateTime) to this clock
                Width = res.x, Height = res.y,
                Texture = _pca.GetTexture(),
                Intrinsics = Intrinsics,
                CameraPose = _pca.GetCameraPose(),
                HasPose = true,
            };
            if (CpuPixels)
            {
                var colors = _pca.GetColors();   // NativeArray<Color32>, do not keep
                f.Pixels = colors.ToArray();
            }
            _latest = f;
            _hasLatest = true;
            FrameArrived?.Invoke(f);
#endif
        }
    }
}
