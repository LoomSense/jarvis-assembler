using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using AG.Core;
using AG.Net;
using UnityEngine;

namespace AG.UI
{
    /// <summary><see cref="IVoice"/> via POST /api/tts (WAV, 16-bit mono PCM) on an AudioSource. Queue + cache.</summary>
    public sealed class VoicePlayer : MonoBehaviour, IVoice
    {
        public AudioSource Source;
        IServerApi _api;
        readonly Dictionary<string, AudioClip> _cache = new Dictionary<string, AudioClip>();
        readonly Queue<AudioClip> _queue = new Queue<AudioClip>();

        public void Init(IServerApi api) { _api = api; if (Source == null) Source = gameObject.AddComponent<AudioSource>(); }

        public bool Speaking => Source != null && Source.isPlaying;

        public async Task SayAsync(string text, bool interrupt = false)
        {
            if (string.IsNullOrWhiteSpace(text) || _api == null) return;
            if (text.Length > 400) text = text.Substring(0, 400);
            try
            {
                if (!_cache.TryGetValue(text, out var clip))
                {
                    clip = Wav.ToClip(await _api.TtsAsync(text), "tts");
                    _cache[text] = clip;
                }
                if (interrupt) { _queue.Clear(); Source.Stop(); }
                _queue.Enqueue(clip);
            }
            catch (Exception e) { AgLog.Error("voice", e.Message); }
        }

        public void StopSpeaking() { _queue.Clear(); if (Source != null) Source.Stop(); }

        void Update()
        {
            if (Source != null && !Source.isPlaying && _queue.Count > 0) { Source.clip = _queue.Dequeue(); Source.Play(); }
        }
    }

    /// <summary>Minimal RIFF/WAVE (PCM16) parser.</summary>
    public static class Wav
    {
        public static AudioClip ToClip(byte[] wav, string name)
        {
            int channels = BitConverter.ToInt16(wav, 22), rate = BitConverter.ToInt32(wav, 24), pos = 12;
            while (pos + 8 <= wav.Length)
            {
                string id = System.Text.Encoding.ASCII.GetString(wav, pos, 4);
                int size = BitConverter.ToInt32(wav, pos + 4);
                if (id == "data")
                {
                    int n = Math.Min(size, wav.Length - pos - 8) / 2;
                    var data = new float[n];
                    for (int i = 0; i < n; i++) data[i] = BitConverter.ToInt16(wav, pos + 8 + i * 2) / 32768f;
                    var clip = AudioClip.Create(name, n / Math.Max(1, channels), Math.Max(1, channels), rate, false);
                    clip.SetData(data, 0);
                    return clip;
                }
                pos += 8 + size + (size & 1);
            }
            throw new FormatException("no data chunk in WAV");
        }
    }
}
