using System;
using System.Text;
using AG.Guide;
using AG.UI;
using Newtonsoft.Json;
using NUnit.Framework;
using UnityEngine;

namespace AG.Tests
{
    /// <summary>Push-to-talk voice commands: WAV encoding for POST /api/voice and the reply -> app action mapping.</summary>
    public class VoiceTests
    {
        [Test]
        public void Wav_HeaderAndSamples()
        {
            var s = new[] { 0f, 1f, -1f, 0.5f, 2f };
            var wav = WavEncoder.Encode(s, s.Length, 16000);
            Assert.AreEqual(44 + 10, wav.Length);
            Assert.AreEqual("RIFF", Encoding.ASCII.GetString(wav, 0, 4));
            Assert.AreEqual(36 + 10, BitConverter.ToInt32(wav, 4));
            Assert.AreEqual("WAVE", Encoding.ASCII.GetString(wav, 8, 4));
            Assert.AreEqual(1, BitConverter.ToInt16(wav, 20));        // PCM
            Assert.AreEqual(1, BitConverter.ToInt16(wav, 22));        // mono
            Assert.AreEqual(16000, BitConverter.ToInt32(wav, 24));
            Assert.AreEqual(32000, BitConverter.ToInt32(wav, 28));    // byte rate
            Assert.AreEqual(16, BitConverter.ToInt16(wav, 34));
            Assert.AreEqual("data", Encoding.ASCII.GetString(wav, 36, 4));
            Assert.AreEqual(10, BitConverter.ToInt32(wav, 40));
            Assert.AreEqual(0, BitConverter.ToInt16(wav, 44));
            Assert.AreEqual(32767, BitConverter.ToInt16(wav, 46));
            Assert.AreEqual(-32767, BitConverter.ToInt16(wav, 48));
            Assert.AreEqual(16384, BitConverter.ToInt16(wav, 50));
            Assert.AreEqual(32767, BitConverter.ToInt16(wav, 52));    // clipped
            // round trip through the app's own WAV reader (the one TTS uses)
            var clip = Wav.ToClip(wav, "t");
            Assert.AreEqual(16000, clip.frequency);
            Assert.AreEqual(5, clip.samples);
        }

        [Test]
        public void Resample_48kTo16k()
        {
            var src = new float[4800];
            for (int i = 0; i < src.Length; i++) src[i] = Mathf.Sin(2 * Mathf.PI * 440 * i / 48000f);
            var dst = WavEncoder.Resample(src, src.Length, 48000, 16000);
            Assert.AreEqual(1600, dst.Length);
            for (int i = 0; i < dst.Length; i += 97) Assert.AreEqual(Mathf.Sin(2 * Mathf.PI * 440 * i / 16000f), dst[i], 1e-3f);
            Assert.AreEqual(0.7071f, WavEncoder.Rms(src, src.Length, src.Length), 1e-3f);
        }

        static AssemblyGuide Guide() => GuideBuilder.FromManifest("malm-drawer",
            JsonConvert.DeserializeObject<ManifestDto>(Resources.Load<TextAsset>("malm-drawer/manifest").text));

        [Test]
        public void Reply_NextMapsToTargetStep()
        {
            var g = Guide();
            var r = JsonConvert.DeserializeObject<VoiceReply>(
                "{\"transcript\":\"επόμενο\",\"intent\":\"next\",\"answer\":\"Πάμε στο βήμα 3, σελίδα 5. Βάλε την κλειδαριά.\",\"target\":{\"step\":3,\"action\":1},\"actions\":[],\"latency_ms\":0,\"model\":\"keywords\",\"simulated\":false}");
            var p = VoiceReplyMap.Map(r, g);
            Assert.AreEqual(VoiceIntent.Next, p.Intent);
            Assert.AreEqual(2, p.StepIndex);                 // step 3 is the third step
            Assert.AreEqual(1, p.Action);
            StringAssert.StartsWith("Πάμε στο βήμα 3", p.Say);
        }

        [Test]
        public void Reply_EndsAskNoneAndUnknownStep()
        {
            var g = Guide();
            // next at the last step: target null, the answer says it is the last step; stay
            var last = VoiceReplyMap.Map(JsonConvert.DeserializeObject<VoiceReply>("{\"intent\":\"next\",\"answer\":\"Αυτό είναι το τελευταίο βήμα.\",\"target\":null}"), g);
            Assert.AreEqual(-1, last.StepIndex);
            Assert.IsNotNull(last.Say);
            // ask with a highlight and a go_to_step action
            var ask = VoiceReplyMap.Map(JsonConvert.DeserializeObject<VoiceReply>(
                "{\"intent\":\"ask\",\"answer\":\"Step 2 uses 2 x wooden dowel 101345.\",\"target\":null,\"actions\":[{\"type\":\"highlight_hardware\",\"code\":\"101345\"},{\"type\":\"go_to_step\",\"step\":2}]}"), g);
            Assert.AreEqual(VoiceIntent.Ask, ask.Intent);
            Assert.AreEqual("101345", ask.Highlight);
            Assert.AreEqual(1, ask.StepIndex);
            Assert.AreEqual(0, ask.Action);
            // none: say nothing, move nowhere
            var none = VoiceReplyMap.Map(JsonConvert.DeserializeObject<VoiceReply>("{\"intent\":\"none\",\"answer\":\"\",\"target\":{\"step\":2,\"action\":0}}"), g);
            Assert.AreEqual(VoiceIntent.None, none.Intent);
            Assert.IsNull(none.Say);
            Assert.AreEqual(-1, none.StepIndex);
            // a target the guide does not have is ignored; an out-of-range action is clamped
            Assert.AreEqual(-1, VoiceReplyMap.Map(new VoiceReply { Intent = "back", Answer = "x", Target = new VoiceTarget { Step = 99 } }, g).StepIndex);
            Assert.AreEqual(1, VoiceReplyMap.Map(new VoiceReply { Intent = "back", Answer = "x", Target = new VoiceTarget { Step = 4, Action = 9 } }, g).Action);
        }

        [Test]
        public void Request_JsonFieldNames()
        {
            var json = JsonConvert.SerializeObject(new VoiceRequest { Audio = "AAA", Mime = "audio/wav", Lang = "el", Step = 2, Action = 1, Speak = true });
            StringAssert.Contains("\"audio\":\"AAA\"", json);
            StringAssert.Contains("\"mime\":\"audio/wav\"", json);
            StringAssert.Contains("\"step\":2", json);
            StringAssert.Contains("\"action\":1", json);
            StringAssert.Contains("\"speak\":true", json);
            StringAssert.Contains("\"rate\":16000", json);
            StringAssert.DoesNotContain("\"text\"", json);
            Assert.AreEqual(VoiceIntent.WhereAmI, VoiceReplyMap.Parse("where_am_i"));
        }
    }
}
