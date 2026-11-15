using System;
using System.Collections.Generic;
using UnityEngine;

namespace AG.Vision
{
    /// <summary>One tagStandard41h12 detection with its pose in the OpenCV camera frame.</summary>
    public struct TagDetection
    {
        public int Id, Hamming;
        /// <summary>AprilTag decision margin (higher = more certain; ~30+ is a clean decode).</summary>
        public float Margin;
        /// <summary>Corners in pixels of the detector image, top-left origin, AprilTag order (p[0..3]).</summary>
        public Vector2 C0, C1, C2, C3;
        /// <summary>
        /// Tag pose in the OpenCV camera frame (x right, y down, z forward), AprilTag tag axes (x right, y DOWN the
        /// tag, z INTO the tag). Row-major 3x3 rotation and translation in metres.
        /// </summary>
        public double R00, R01, R02, R10, R11, R12, R20, R21, R22, Tx, Ty, Tz;
        /// <summary>RMS corner reprojection error of that pose, pixels.</summary>
        public float ReprojPx;

        /// <summary>
        /// Tag pose in the Unity camera frame (x right, y up, z forward) with Unity tag axes: +X to the tag's right,
        /// +Y to its TOP edge, +Z INTO the face (the printed face's normal is -Z, like a Unity Quad). Both frames are
        /// the OpenCV ones mirrored in y, so R' = S R S and t' = S t with S = diag(1, -1, 1).
        /// </summary>
        public Pose InUnityCamera()
        {
            var pos = new Vector3((float)Tx, (float)-Ty, (float)Tz);
            // columns of S R S: tag axes expressed in the Unity camera frame
            var y = new Vector3((float)-R01, (float)R11, (float)-R21);   // column 1 of S R S
            var z = new Vector3((float)R02, (float)-R12, (float)R22);    // column 2 of S R S
            return new Pose(pos, Quaternion.LookRotation(z, y));
        }
    }

    /// <summary>
    /// Thin wrapper over jp.keijiro.apriltag's interop layer (the package's TagDetector assumes the principal point at
    /// the image centre and fx = fy; this one passes the real fx, fy, cx, cy to AprilTag's own <c>estimate_tag_pose</c>).
    /// Input is an 8-bit grayscale image, row 0 at the TOP. Not thread-safe; one call at a time, any thread.
    ///
    /// Pixel convention: AprilTag puts pixel centres at +0.5 (corner origin); OpenCV-style intrinsics (replay
    /// datasets, PCA) put them at integer coordinates. <see cref="PixelCentreOffset"/> (default 0.5) is added to cx, cy.
    /// </summary>
    public sealed class AprilTagDetector : IDisposable
    {
        public readonly int Width, Height;
        public float PixelCentreOffset = 0.5f;
#if AG_HAS_APRILTAG
        AprilTag.Interop.Detector _det;
        AprilTag.Interop.Family _family;
        AprilTag.Interop.ImageU8 _img;
#endif
        readonly List<TagDetection> _out = new List<TagDetection>();

        public static bool Available
        {
            get
            {
#if AG_HAS_APRILTAG
                return true;
#else
                return false;
#endif
            }
        }

        /// <param name="decimation">quad_decimate: 2 halves the resolution for quad search (corners are refined at full res).</param>
        /// <param name="threads">worker threads inside the detector.</param>
        public AprilTagDetector(int width, int height, float decimation = 2f, int threads = 2)
        {
            Width = width;
            Height = height;
#if AG_HAS_APRILTAG
            _det = AprilTag.Interop.Detector.Create();
            _family = AprilTag.Interop.Family.CreateTagStandard41h12();
            _img = AprilTag.Interop.ImageU8.Create(width, height);
            _det.ThreadCount = Mathf.Max(1, threads);
            _det.QuadDecimate = Mathf.Max(1f, decimation);
            _det.AddFamily(_family);
#endif
        }

        public float Decimation
        {
#if AG_HAS_APRILTAG
            get => _det.QuadDecimate; set => _det.QuadDecimate = Mathf.Max(1f, value);
#else
            get => 1f; set { }
#endif
        }

        /// <summary>
        /// RGBA (row 0 at the BOTTOM, Unity order) -> grayscale (row 0 at the TOP). <paramref name="flipRows"/> = true
        /// means the source rows are already top-down. Uses the green channel plus a quarter of red and blue (cheap luma).
        /// </summary>
        public static void ToGray(Color32[] src, byte[] dst, int w, int h, bool flipRows = false)
        {
            for (int y = 0; y < h; y++)
            {
                int s = (flipRows ? y : h - 1 - y) * w, d = y * w;
                for (int x = 0; x < w; x++)
                {
                    var c = src[s + x];
                    dst[d + x] = (byte)((c.r + 2 * c.g + c.b) >> 2);
                }
            }
        }

        /// <summary>
        /// Detect tags in <paramref name="grayTopDown"/> (Width x Height) and estimate each pose with intrinsics
        /// <paramref name="k"/> (pixels of this image) and the tag size in metres (AprilTag convention: edge of the
        /// detected border square; for tagStandard41h12 that is 5 of its 9 modules). The returned list is reused.
        /// </summary>
        public List<TagDetection> Detect(byte[] grayTopDown, in CameraIntrinsics k, float tagSizeM, int onlyId = -1)
        {
            _out.Clear();
#if AG_HAS_APRILTAG
            var buf = _img.Buffer;
            int stride = _img.Stride;
            for (int y = 0; y < Height; y++)
                new ReadOnlySpan<byte>(grayTopDown, y * Width, Width).CopyTo(buf.Slice(y * stride, Width));
            double fx = k.fx, fy = k.fy, cx = k.cx + PixelCentreOffset, cy = k.cy + PixelCentreOffset;
            using var tags = _det.Detect(_img);
            for (int i = 0; i < tags.Length; i++)
            {
                ref var t = ref tags[i];
                if (onlyId >= 0 && t.ID != onlyId) continue;
                var info = new AprilTag.Interop.DetectionInfo(ref t, tagSizeM, fx, fy, cx, cy);
                var d = new TagDetection
                {
                    Id = t.ID, Hamming = t.Hamming, Margin = t.DecisionMargin,
                    C0 = V(t.Corner1), C1 = V(t.Corner2), C2 = V(t.Corner3), C3 = V(t.Corner4),
                };
                using (var pose = new AprilTag.Interop.Pose(ref info))
                {
                    ref var r = ref pose.R;
                    ref var tr = ref pose.t;
                    d.R00 = r.e00; d.R01 = r.e01; d.R02 = r.e02;
                    d.R10 = r.e10; d.R11 = r.e11; d.R12 = r.e12;
                    d.R20 = r.e20; d.R21 = r.e21; d.R22 = r.e22;
                    d.Tx = tr.e0; d.Ty = tr.e1; d.Tz = tr.e2;
                }
                d.ReprojPx = Reproj(d, tagSizeM, fx, fy, cx, cy);
                _out.Add(d);
            }
#endif
            return _out;
        }

        static Vector2 V((double x, double y) p) => new Vector2((float)p.x, (float)p.y);

        /// <summary>AprilTag object points: p[0] (-s/2, +s/2), p[1] (+s/2, +s/2), p[2] (+s/2, -s/2), p[3] (-s/2, -s/2).</summary>
        static float Reproj(in TagDetection d, double s, double fx, double fy, double cx, double cy)
        {
            double h = s / 2, sum = 0;
            sum += Err(d, -h, h, d.C0, fx, fy, cx, cy);
            sum += Err(d, h, h, d.C1, fx, fy, cx, cy);
            sum += Err(d, h, -h, d.C2, fx, fy, cx, cy);
            sum += Err(d, -h, -h, d.C3, fx, fy, cx, cy);
            return (float)Math.Sqrt(sum / 4);
        }

        static double Err(in TagDetection d, double x, double y, Vector2 c, double fx, double fy, double cx, double cy)
        {
            double X = d.R00 * x + d.R01 * y + d.Tx, Y = d.R10 * x + d.R11 * y + d.Ty, Z = d.R20 * x + d.R21 * y + d.Tz;
            if (Z <= 1e-6) return 1e6;
            double u = fx * X / Z + cx - c.x, v = fy * Y / Z + cy - c.y;
            return u * u + v * v;
        }

        public void Dispose()
        {
#if AG_HAS_APRILTAG
            _det?.RemoveFamily(_family);
            _det?.Dispose();
            _family?.Dispose();
            _img?.Dispose();
            _det = null; _family = null; _img = null;
#endif
        }
    }
}
