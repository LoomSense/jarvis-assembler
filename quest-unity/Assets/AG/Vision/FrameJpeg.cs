using UnityEngine;

namespace AG.Vision
{
    /// <summary>
    /// Camera frame -> JPEG for the server (identify, locate, perception socket): long edge 768 px, quality 70
    /// (the socket's <c>hello.limits.long_edge_px</c> / <c>jpeg_quality</c>). Main thread; one GPU blit + readback.
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
    }
}
