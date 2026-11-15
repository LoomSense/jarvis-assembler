using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using AG.Core;
using AG.Guide;
using Newtonsoft.Json;
using UnityEngine;

namespace AG.UI
{
    /// <summary>16-bit PCM mono WAV from float samples, and a linear resampler (mic rate -> 16 kHz). Pure, any thread.</summary>
    public static class WavEncoder
    {
        public static byte[] Encode(float[] samples, int count, int rate)
        {
            count = Mathf.Clamp(count, 0, samples?.Length ?? 0);
            using var ms = new MemoryStream(44 + count * 2);
            using var w = new BinaryWriter(ms);
            w.Write(Encoding.ASCII.GetBytes("RIFF"));
            w.Write(36 + count * 2);
            w.Write(Encoding.ASCII.GetBytes("WAVE"));
            w.Write(Encoding.ASCII.GetBytes("fmt "));
            w.Write(16);            // PCM chunk size
            w.Write((short)1);      // PCM
            w.Write((short)1);      // mono
            w.Write(rate);
            w.Write(rate * 2);      // byte rate
            w.Write((short)2);      // block align
            w.Write((short)16);     // bits
            w.Write(Encoding.ASCII.GetBytes("data"));
            w.Write(count * 2);
            for (int i = 0; i < count; i++)
                w.Write((short)Mathf.RoundToInt(Mathf.Clamp(samples[i], -1f, 1f) * 32767f));
            w.Flush();
            return ms.ToArray();
        }

        public static float[] Resample(float[] src, int count, int fromRate, int toRate)
        {
            if (fromRate == toRate || count == 0) { var c = new float[count]; Array.Copy(src, c, count); return c; }
            int n = (int)((long)count * toRate / fromRate);
            var dst = new float[n];
            double step = (double)fromRate / toRate;
            for (int i = 0; i < n; i++)
            {
                double x = i * step;
                int i0 = (int)x, i1 = Math.Min(i0 + 1, count - 1);
                float f = (float)(x - i0);
                dst[i] = src[i0] * (1 - f) + src[i1] * f;
            }
            return dst;
        }

        /// <summary>Root mean square of the last <paramref name="window"/> samples before <paramref name="end"/>.</summary>
        public static float Rms(float[] s, int end, int window)
        {
            int a = Math.Max(0, end - window);
            double acc = 0;
            for (int i = a; i < end; i++) acc += s[i] * s[i];
            return end > a ? (float)Math.Sqrt(acc / (end - a)) : 0f;
        }
    }

    // ---------------------------------------------------------------- POST /api/voice/{job} (server/perception/README.md)

    public sealed class VoiceRequest
    {
        [JsonProperty("audio", NullValueHandling = NullValueHandling.Ignore)] public string Audio;
        [JsonProperty("mime", NullValueHandling = NullValueHandling.Ignore)] public string Mime;
        [JsonProperty("text", NullValueHandling = NullValueHandling.Ignore)] public string Text;
        [JsonProperty("lang")] public string Lang;
        [JsonProperty("step")] public int Step;
        [JsonProperty("action")] public int Action;
        [JsonProperty("speak")] public bool Speak;
        [JsonProperty("rate")] public int Rate = 16000;
    }

    public sealed class VoiceTarget
    {
        [JsonProperty("step")] public int Step;
        [JsonProperty("action")] public int Action;
    }

    public sealed class VoiceScreenAction
    {
        [JsonProperty("type")] public string Type;
        [JsonProperty("code")] public string Code;
        [JsonProperty("id")] public string Id;
        [JsonProperty("step")] public int? Step;
    }

    public sealed class VoiceReply
    {
        [JsonProperty("transcript")] public string Transcript;
        [JsonProperty("intent")] public string Intent;
        [JsonProperty("answer")] public string Answer;
        [JsonProperty("target")] public VoiceTarget Target;
        [JsonProperty("actions")] public List<VoiceScreenAction> Actions;
        [JsonProperty("tts")] public string Tts;
        [JsonProperty("tts_error")] public string TtsError;
        [JsonProperty("latency_ms")] public int? LatencyMs;
        [JsonProperty("model")] public string Model;
        [JsonProperty("simulated")] public bool Simulated;
    }

    public enum VoiceIntent { None, Next, Back, Repeat, WhereAmI, Identify, Ask }

    /// <summary>What the app does with a <see cref="VoiceReply"/>. Pure, so it is unit-tested.</summary>
    public struct VoicePlan
    {
        public VoiceIntent Intent;
        /// <summary>Index into <see cref="AssemblyGuide.Steps"/> and action to show; -1 = stay.</summary>
        public int StepIndex, Action;
        /// <summary>Line to speak and caption; null = say nothing.</summary>
        public string Say;
        /// <summary>Part id / hardware code to highlight (ask's highlight_* action), or null.</summary>
        public string Highlight;
    }

    public static class VoiceReplyMap
    {
        public static VoiceIntent Parse(string intent) => intent switch
        {
            "next" => VoiceIntent.Next,
            "back" => VoiceIntent.Back,
            "repeat" => VoiceIntent.Repeat,
            "where_am_i" => VoiceIntent.WhereAmI,
            "identify" => VoiceIntent.Identify,
            "ask" => VoiceIntent.Ask,
            _ => VoiceIntent.None,
        };

        public static VoicePlan Map(VoiceReply r, AssemblyGuide g)
        {
            var p = new VoicePlan { Intent = Parse(r?.Intent), StepIndex = -1, Action = -1 };
            if (r == null) return p;
            p.Say = string.IsNullOrWhiteSpace(r.Answer) ? null : r.Answer.Trim();
            var t = r.Target;
            if (t != null && g != null)
            {
                int si = g.Steps.FindIndex(s => s.N == t.Step);
                if (si >= 0) { p.StepIndex = si; p.Action = Mathf.Clamp(t.Action, 0, Math.Max(0, g.Steps[si].Actions.Count - 1)); }
            }
            if (r.Actions != null)
                foreach (var a in r.Actions)
                {
                    if (a == null) continue;
                    if ((a.Type == "highlight_hardware" || a.Type == "highlight_part") && p.Highlight == null) p.Highlight = a.Code ?? a.Id;
                    if (a.Type == "go_to_step" && a.Step.HasValue && p.StepIndex < 0 && g != null)
                    {
                        int si = g.Steps.FindIndex(s => s.N == a.Step.Value);
                        if (si >= 0) { p.StepIndex = si; p.Action = 0; }
                    }
                }
            if (p.Intent == VoiceIntent.None) { p.Say = null; p.StepIndex = -1; }
            return p;
        }
    }

    /// <summary>POST /api/voice/{job} through AG.Core.Http (the demo key goes in X-Demo-Key). Logs <c>voice</c>.</summary>
    public sealed class VoiceCommandClient
    {
        readonly string _server, _key;
        public VoiceCommandClient(string server, string key) { _server = (server ?? "").TrimEnd('/'); _key = key; }

        public async Task<VoiceReply> SendAsync(string jobId, VoiceRequest req, CancellationToken ct = default)
        {
            var sw = Stopwatch.StartNew();
            var bytes = await Http.PostJsonAsync($"{_server}/api/voice/{Uri.EscapeDataString(jobId)}", JsonConvert.SerializeObject(req), _key, ct, 30);
            var r = JsonConvert.DeserializeObject<VoiceReply>(Encoding.UTF8.GetString(bytes));
            AgLog.Ev("voice", new { ms = sw.ElapsedMilliseconds, intent = r?.Intent, transcript = r?.Transcript, model = r?.Model, server_ms = r?.LatencyMs, tts = r?.Tts != null, simulated = r?.Simulated });
            return r;
        }
    }

    /// <summary>
    /// Push-to-talk recorder on <see cref="Microphone"/>: 16 kHz mono when the device allows it (else its rate,
    /// resampled), at most <see cref="MaxS"/> s. <see cref="Level"/> is the recent RMS for the mic button.
    /// </summary>
    public sealed class MicRecorder
    {
        public const int Rate = 16000;
        public float MaxS = 8f;
        AudioClip _clip;
        string _device;
        int _rate;
        float _startedAt;
        float[] _buf = new float[0];
        public bool Recording => _clip != null;
        public float Level { get; private set; }
        public float ElapsedS => Recording ? Time.realtimeSinceStartup - _startedAt : 0;

        public bool Start()
        {
            if (Recording) return true;
#if UNITY_ANDROID && !UNITY_EDITOR
            if (!UnityEngine.Android.Permission.HasUserAuthorizedPermission(UnityEngine.Android.Permission.Microphone))
            {
                UnityEngine.Android.Permission.RequestUserPermission(UnityEngine.Android.Permission.Microphone);
                return false;
            }
#endif
            if (Microphone.devices.Length == 0) { AgLog.Error("voice.mic", "no microphone"); return false; }
            _device = Microphone.devices[0];
            Microphone.GetDeviceCaps(_device, out int min, out int max);
            _rate = (min == 0 && max == 0) || (Rate >= min && Rate <= max) ? Rate : max;
            _clip = Microphone.Start(_device, false, Mathf.CeilToInt(MaxS) + 1, _rate);
            _startedAt = Time.realtimeSinceStartup;
            return _clip != null;
        }

        /// <summary>Call every frame while recording; returns false when the max length is reached.</summary>
        public bool Tick()
        {
            if (!Recording) return false;
            int pos = Microphone.GetPosition(_device);
            if (pos > 256)
            {
                if (_buf.Length < pos) _buf = new float[_clip.samples];
                _clip.GetData(_buf, 0);
                Level = Mathf.Lerp(Level, WavEncoder.Rms(_buf, pos, _rate / 20), 0.5f);
            }
            return ElapsedS < MaxS;
        }

        /// <summary>Stop and return the WAV (16 kHz mono PCM16), or null when shorter than 0.25 s.</summary>
        public byte[] Stop()
        {
            if (!Recording) return null;
            int pos = Microphone.GetPosition(_device);
            Microphone.End(_device);
            var clip = _clip;
            _clip = null;
            Level = 0;
            if (pos <= 0) pos = clip.samples;
            if (pos < _rate / 4) return null;
            var data = new float[clip.samples];
            clip.GetData(data, 0);
            var s16 = WavEncoder.Resample(data, pos, _rate, Rate);
            UnityEngine.Object.Destroy(clip);
            return WavEncoder.Encode(s16, s16.Length, Rate);
        }

        public void Cancel() { if (Recording) { Microphone.End(_device); _clip = null; Level = 0; } }
    }
}
