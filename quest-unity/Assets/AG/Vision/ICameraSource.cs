using System;
using System.Threading.Tasks;
using UnityEngine;

namespace AG.Vision
{
    /// <summary>
    /// Pinhole intrinsics in PIXELS of the frame they come with (already scaled from the sensor resolution to
    /// <see cref="Width"/> x <see cref="Height"/>). OpenCV convention: origin at the TOP-LEFT pixel corner, u right,
    /// v down. Same fields as replay/intrinsics.json: {fx, fy, cx, cy, width, height}.
    /// </summary>
    [Serializable]
    public struct CameraIntrinsics
    {
        public float fx, fy, cx, cy;
        public int width, height;

        public bool IsValid => fx > 0 && fy > 0 && width > 0 && height > 0;

        /// <summary>Vertical field of view in radians (what jp.keijiro.apriltag's ProcessImage expects, per its code).</summary>
        public float VerticalFovRad => 2f * Mathf.Atan(height * 0.5f / fy);

        /// <summary>Same camera at another resolution (e.g. 1280x960 -> 768x576 JPEG for the server).</summary>
        public CameraIntrinsics Scaled(int w, int h)
        {
            float sx = (float)w / width, sy = (float)h / height;
            return new CameraIntrinsics { fx = fx * sx, fy = fy * sy, cx = cx * sx, cy = cy * sy, width = w, height = h };
        }

        /// <summary>
        /// Pixel (u, v), top-left origin -> direction in the CAMERA's Unity local frame (+Z forward, +Y up, +X right),
        /// not normalised. World ray: <c>cameraPose.rotation * dir</c> from <c>cameraPose.position</c>.
        /// </summary>
        public Vector3 PixelToCameraDir(float u, float v) => new Vector3((u - cx) / fx, -(v - cy) / fy, 1f);
    }

    /// <summary>
    /// One camera image. Valid until the source delivers the next frame (do not keep <see cref="Texture"/> or
    /// <see cref="Pixels"/>; copy what you need).
    /// </summary>
    public struct CameraFrame
    {
        /// <summary>Increasing per source, starting at 0.</summary>
        public long Index;
        /// <summary>Capture time, seconds on <c>Time.realtimeSinceStartupAsDouble</c>'s clock.</summary>
        public double TimeS;
        public int Width, Height;
        /// <summary>GPU image (may be a RenderTexture). Unity convention: row 0 at the BOTTOM.</summary>
        public Texture Texture;
        /// <summary>
        /// Optional CPU copy, RGBA32, row 0 at the BOTTOM (Texture2D.GetPixels32 order). Null unless requested. The array
        /// may be reused by the source for the next frame: copy what you keep.
        /// </summary>
        public Color32[] Pixels;
        /// <summary>Optional original JPEG bytes (replay supplies them; send as-is to the server).</summary>
        public byte[] Jpeg;
        public CameraIntrinsics Intrinsics;
        /// <summary>Camera pose in Unity WORLD space at <see cref="TimeS"/> (+Z = optical axis). Valid when <see cref="HasPose"/>.</summary>
        public Pose CameraPose;
        public bool HasPose;
        /// <summary>Replay only: the dataset frame number (poses.jsonl <c>frame</c>, else the file's position); -1 live.</summary>
        public int ReplayFrame;
    }

    /// <summary>
    /// A camera that can see the object: Quest passthrough RGB camera (PcaCameraSource, needs
    /// <c>horizonos.permission.HEADSET_CAMERA</c>), or a recorded replay (ReplayCameraSource).
    ///
    /// Threading: main thread only. Frames arrive at most once per Unity frame; <see cref="FrameArrived"/> is raised
    /// from Update. Owner: Vision agent (Assets/AG/Vision/).
    /// </summary>
    public interface ICameraSource
    {
        /// <summary>"pca", "replay", ...: goes into logs.</summary>
        string Name { get; }
        bool IsRunning { get; }
        /// <summary>Intrinsics of the frames this source delivers; invalid until running.</summary>
        CameraIntrinsics Intrinsics { get; }
        /// <summary>When true, frames carry <see cref="CameraFrame.Pixels"/> (costs a GPU readback on device).</summary>
        bool CpuPixels { get; set; }

        /// <summary>Asks permission if needed and starts. Returns false (and logs <c>error</c>) when it cannot.</summary>
        Task<bool> StartAsync();
        void Stop();

        /// <summary>The newest frame, if any arrived since start.</summary>
        bool TryGetLatest(out CameraFrame frame);

        event Action<CameraFrame> FrameArrived;

        /// <summary>Must be called every frame by its owner (AppRoot) to poll the camera.</summary>
        void Tick();
    }
}
