using System.Collections.Generic;
using UnityEngine;

namespace AG.UI
{
    public enum Cue { Tick, Place, Success, Step, Warning, Unsure, Complete, Lost }

    /// <summary>
    /// The spec's sound family (XR_NATIVE_SPEC §6.2), synthesised at start-up: soft glassy tones (sine + a little
    /// 2nd/3rd harmonic, fast attack, exponential decay), peak about -14 dBFS. No audio files to license.
    /// Play with <see cref="Play"/> on a spatialised source at the window or the part.
    /// </summary>
    public static class UiSounds
    {
        const int Rate = 44100;
        const float Peak = 0.2f;   // -14 dBFS
        static readonly Dictionary<Cue, AudioClip> Clips = new Dictionary<Cue, AudioClip>();

        static float Note(string n)
        {
            // A4 = 440; names like "E6", "G#6"
            string[] names = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };
            string p = n.Substring(0, n.Length - 1);
            int oct = n[n.Length - 1] - '0';
            int idx = System.Array.IndexOf(names, p);
            int midi = (oct + 1) * 12 + idx;
            return 440f * Mathf.Pow(2f, (midi - 69) / 12f);
        }

        public static AudioClip Get(Cue c)
        {
            if (Clips.TryGetValue(c, out var clip) && clip != null) return clip;
            switch (c)
            {
                case Cue.Tick: clip = Click(0.025f); break;
                case Cue.Place: clip = Tones("place", new[] { 180f }, 0.06f, 0.06f, 0.02f, 0.5f); break;
                case Cue.Success: clip = Tones("success", new[] { Note("E6"), Note("G#6") }, 0.12f, 0.35f, 0.09f); break;
                case Cue.Step: clip = Tones("step", new[] { Note("C6"), Note("E6"), Note("G6") }, 0.13f, 0.6f, 0.12f); break;
                case Cue.Warning: clip = Tones("warning", new[] { Note("A4"), Note("G#4") }, 0.13f, 0.3f, 0.1f, 0.8f); break;
                case Cue.Unsure: clip = Bend("unsure", Note("D5"), 1.04f, 0.25f); break;
                case Cue.Complete: clip = Tones("complete", new[] { Note("C6"), Note("E6"), Note("G6"), Note("C7") }, 0.14f, 1.4f, 0.45f); break;
                default: clip = Tones("lost", new[] { Note("D4") }, 0.2f, 0.2f, 0.08f, 0.8f); break;
            }
            Clips[c] = clip;
            return clip;
        }

        public static void Play(AudioSource src, Cue c, float volume = 1f)
        {
            if (src == null) return;
            src.PlayOneShot(Get(c), volume);
        }

        static AudioClip Tones(string name, float[] freqs, float stepS, float lengthS, float decayS, float gain = 1f)
        {
            int n = Mathf.CeilToInt(lengthS * Rate);
            var data = new float[n];
            for (int k = 0; k < freqs.Length; k++)
            {
                int start = Mathf.RoundToInt(k * stepS * Rate);
                float f = freqs[k];
                bool last = k == freqs.Length - 1;
                float dec = last ? Mathf.Max(decayS, lengthS - k * stepS) * 0.45f : decayS;
                for (int i = start; i < n; i++)
                {
                    float t = (i - start) / (float)Rate;
                    float env = Mathf.Min(1f, t / 0.004f) * Mathf.Exp(-t / dec);
                    float s = Mathf.Sin(2 * Mathf.PI * f * t) + 0.18f * Mathf.Sin(4 * Mathf.PI * f * t) + 0.06f * Mathf.Sin(6 * Mathf.PI * f * t);
                    data[i] += s * env;
                }
            }
            return Make(name, data, gain);
        }

        static AudioClip Bend(string name, float f0, float ratio, float lengthS)
        {
            int n = Mathf.CeilToInt(lengthS * Rate);
            var data = new float[n];
            float phase = 0;
            for (int i = 0; i < n; i++)
            {
                float t = i / (float)Rate;
                float f = f0 * Mathf.Lerp(1f, ratio, t / lengthS);
                phase += 2 * Mathf.PI * f / Rate;
                data[i] = Mathf.Sin(phase) * Mathf.Min(1f, t / 0.006f) * Mathf.Exp(-t / 0.1f);
            }
            return Make(name, data, 0.9f);
        }

        static AudioClip Click(float lengthS)
        {
            int n = Mathf.CeilToInt(lengthS * Rate);
            var data = new float[n];
            var rnd = new System.Random(7);
            for (int i = 0; i < n; i++)
            {
                float t = i / (float)Rate;
                float tone = Mathf.Sin(2 * Mathf.PI * 3000f * t);
                float noise = (float)(rnd.NextDouble() * 2 - 1) * 0.3f;
                data[i] = (tone + noise) * Mathf.Exp(-t / 0.004f);
            }
            return Make("tick", data, 0.7f);
        }

        static AudioClip Make(string name, float[] data, float gain)
        {
            float max = 1e-4f;
            foreach (var v in data) max = Mathf.Max(max, Mathf.Abs(v));
            for (int i = 0; i < data.Length; i++) data[i] = data[i] / max * Peak * gain;
            var clip = AudioClip.Create("ag_" + name, data.Length, 1, Rate, false);
            clip.SetData(data, 0);
            return clip;
        }
    }
}
