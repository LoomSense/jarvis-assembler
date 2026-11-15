using System;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.Experimental.Rendering;

namespace AG.Vision
{
    /// <summary>
    /// Camera frame -> JPEG for the server (identify, locate, perception socket): long edge 768 px, quality 70
    /// (the socket's <c>hello.limits.long_edge_px</c> / <c>jpeg_quality</c>).
    /// <see cref="Encode"/>: main thread, GPU blit + synchronous readback (simple, stalls the GPU a little).
    /// <see cref="EncodeAsync"/>: from <see cref="CameraFrame.Pixels"/> (the tracker's CPU copy, so no extra readback):
    /// one array copy on the main thread, resize + JPEG on a worker thread (ImageConversion is thread-safe).
    /// </summary>
    public static class FrameJpeg
    {
        static RenderTexture _rt;
        static Texture2D _read;

        public static byte[] Encode(in CameraFrame f, int longEdge = 768, int quality = 70)
        {
            if (f.Jpeg != null && Mathf.Max(f.Width, f.Height) <= longEdge) return f.Jpeg;
            if (f.Texture == null) return null;
            float s = Mathf.Min(1f, (float)longEdge / Mathf.Max(f.Width, f.Height));
            int w = Mathf.RoundToInt(f.Width * s), h = Mathf.RoundToInt(f.Height * s);
            if (_rt == null || _rt.width != w || _rt.height != h)
            {
                if (_rt != null) _rt.Release();
                _rt = new RenderTexture(w, h, 0, RenderTextureFormat.ARGB32);
                _read = new Texture2D(w, h, TextureFormat.RGB24, false);
            }
            var prev = RenderTexture.active;
            Graphics.Blit(f.Texture, _rt);
            RenderTexture.active = _rt;
            _read.ReadPixels(new Rect(0, 0, w, h), 0, 0, false);
            _read.Apply(false);
            RenderTexture.active = prev;
            return _read.EncodeToJPG(quality);
        }

        /// <summary>
        /// JPEG of the frame without touching the GPU when it has CPU pixels; falls back to <see cref="Encode"/>
        /// (synchronous) otherwise. Call on the main thread; await anywhere.
        /// </summary>
        public static Task<byte[]> EncodeAsync(in CameraFrame f, int longEdge = 768, int quality = 70)
        {
            if (f.Jpeg != null && Mathf.Max(f.Width, f.Height) <= longEdge) return Task.FromResult(f.Jpeg);
            if (f.Pixels == null) return Task.FromResult(Encode(f, longEdge, quality));
            var src = (Color32[])f.Pixels.Clone();          // the source reuses its array for the next frame
            int sw = f.Width, sh = f.Height;
            return Task.Run(() => EncodePixels(src, sw, sh, longEdge, quality));
        }

        /// <summary>Bilinear resize to the long edge, then JPEG. Rows bottom-up in and out (Unity order). Any thread.</summary>
        public static byte[] EncodePixels(Color32[] src, int sw, int sh, int longEdge, int quality)
        {
            float s = Mathf.Min(1f, (float)longEdge / Mathf.Max(sw, sh));
            int w = Mathf.Max(1, (int)Math.Round(sw * s)), h = Mathf.Max(1, (int)Math.Round(sh * s));
            Color32[] dst = src;
            if (w != sw || h != sh)
            {
                dst = new Color32[w * h];
                float kx = (float)sw / w, ky = (float)sh / h;
                for (int y = 0; y < h; y++)
                {
                    float fy = (y + 0.5f) * ky - 0.5f;
                    int y0 = Mathf.Clamp((int)fy, 0, sh - 1), y1 = Mathf.Min(y0 + 1, sh - 1);
                    float ty = Mathf.Clamp01(fy - y0);
                    for (int x = 0; x < w; x++)
                    {
                        float fx = (x + 0.5f) * kx - 0.5f;
                        int x0 = Mathf.Clamp((int)fx, 0, sw - 1), x1 = Mathf.Min(x0 + 1, sw - 1);
                        float tx = Mathf.Clamp01(fx - x0);
                        Color32 a = src[y0 * sw + x0], b = src[y0 * sw + x1], c = src[y1 * sw + x0], d = src[y1 * sw + x1];
                        dst[y * w + x] = new Color32(
                            Lerp2(a.r, b.r, c.r, d.r, tx, ty), Lerp2(a.g, b.g, c.g, d.g, tx, ty), Lerp2(a.b, b.b, c.b, d.b, tx, ty), 255);
                    }
                }
            }
            return ImageConversion.EncodeArrayToJPG(dst, GraphicsFormat.R8G8B8A8_UNorm, (uint)w, (uint)h, 0, quality);
        }

        static byte Lerp2(byte a, byte b, byte c, byte d, float tx, float ty)
        {
            float top = a + (b - a) * tx, bot = c + (d - c) * tx;
            return (byte)(top + (bot - top) * ty + 0.5f);
        }
    }
}
