using System;
using System.IO;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.Networking;

namespace AG.Core
{
    /// <summary>
    /// Launch parameters shared with the Android/Kotlin client (same names, same meaning).
    ///
    /// Sources, later ones override earlier ones:
    /// <list type="number">
    /// <item><c>StreamingAssets/local.config.json</c> (git-ignored; baked into a dev APK; editor default)</item>
    /// <item><c>Application.persistentDataPath/config.json</c> (on device:
    ///       <c>/sdcard/Android/data/com.agj.unity/files/config.json</c>, push with adb)</item>
    /// <item>Android intent extras, e.g.
    ///       <c>adb shell am start -n com.agj.unity/com.unity3d.player.UnityPlayerGameActivity
    ///       --es server https://host --es key K --es job malm-drawer --es lang el --ez replay true --ez hud true</c>
    ///       (booleans may also come as strings "true"/"1").</item>
    /// </list>
    /// Never commit a key: <c>config.example.json</c> is the only committed config.
    /// </summary>
    [Serializable]
    public sealed class LaunchParams
    {
        /// <summary>Server base URL, no trailing slash, e.g. <c>https://host</c>.</summary>
        [JsonProperty("server")] public string Server = "http://localhost:8000";
        /// <summary>Demo key, sent as <c>X-Demo-Key</c> (REST) and <c>?key=</c> (WebSocket, /files).</summary>
        [JsonProperty("key")] public string Key = "";
        /// <summary>Optional job id to open directly (skips identify), e.g. <c>malm-drawer</c>.</summary>
        [JsonProperty("job")] public string Job = "";
        /// <summary><c>el</c> or <c>en</c>.</summary>
        [JsonProperty("lang")] public string Lang = "en";
        /// <summary>Use <c>ReplayCameraSource</c> instead of the passthrough camera.</summary>
        [JsonProperty("replay")] public bool Replay;
        /// <summary>Show the debug HUD.</summary>
        [JsonProperty("hud")] public bool Hud;
        /// <summary>Editor only: folder with replay frames (defaults to persistentDataPath/replay).</summary>
        [JsonProperty("replay_dir")] public string ReplayDir = "";

        public static LaunchParams Current { get; private set; }

        /// <summary>Folder the replay camera reads: <c>replay_dir</c> or <c>persistentDataPath/replay</c>.</summary>
        public string ReplayFolder => string.IsNullOrEmpty(ReplayDir)
            ? Path.Combine(Application.persistentDataPath, "replay") : ReplayDir;

        /// <summary>Main thread, once at startup. Blocks briefly to read StreamingAssets on Android.</summary>
        public static LaunchParams Load()
        {
            var p = new LaunchParams();
            Merge(p, ReadStreamingAsset("local.config.json"), "streaming");
            var persistent = Path.Combine(Application.persistentDataPath, "config.json");
            if (File.Exists(persistent)) Merge(p, File.ReadAllText(persistent), "persistent");
            ReadIntentExtras(p);
            p.Server = (p.Server ?? "").TrimEnd('/');
            if (p.Lang != "el") p.Lang = "en";
            Current = p;
            return p;
        }

        /// <summary>For the <c>start</c> log event: same fields, key redacted.</summary>
        public object Redacted() => new
        {
            server = Server, key = string.IsNullOrEmpty(Key) ? "" : "***", job = Job, lang = Lang, replay = Replay, hud = Hud,
        };

        static void Merge(LaunchParams p, string json, string source)
        {
            if (string.IsNullOrWhiteSpace(json)) return;
            try { JsonConvert.PopulateObject(json, p); }
            catch (Exception e) { AgLog.Error("LaunchParams." + source, e.Message); }
        }

        static string ReadStreamingAsset(string name)
        {
            var path = Path.Combine(Application.streamingAssetsPath, name);
#if UNITY_ANDROID && !UNITY_EDITOR
            // inside the APK (jar:file://...): only UnityWebRequest can read it
            using var req = UnityWebRequest.Get(path);
            var op = req.SendWebRequest();
            var until = DateTime.UtcNow.AddSeconds(2);
            while (!op.isDone && DateTime.UtcNow < until) { }
            return req.result == UnityWebRequest.Result.Success ? req.downloadHandler.text : null;
#else
            return File.Exists(path) ? File.ReadAllText(path) : null;
#endif
        }

        static void ReadIntentExtras(LaunchParams p)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                using var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
                using var activity = player.GetStatic<AndroidJavaObject>("currentActivity");
                using var intent = activity.Call<AndroidJavaObject>("getIntent");
                if (intent == null) return;
                p.Server = Str(intent, "server") ?? p.Server;
                p.Key = Str(intent, "key") ?? p.Key;
                p.Job = Str(intent, "job") ?? p.Job;
                p.Lang = Str(intent, "lang") ?? p.Lang;
                p.Replay = Bool(intent, "replay") ?? p.Replay;
                p.Hud = Bool(intent, "hud") ?? p.Hud;
            }
            catch (Exception e) { AgLog.Error("LaunchParams.intent", e.Message); }
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        static string Str(AndroidJavaObject intent, string name)
        {
            if (!intent.Call<bool>("hasExtra", name)) return null;
            using var extras = intent.Call<AndroidJavaObject>("getExtras");
            using var v = extras.Call<AndroidJavaObject>("get", name);
            return v?.Call<string>("toString");
        }

        static bool? Bool(AndroidJavaObject intent, string name)
        {
            var s = Str(intent, name);   // works for --ez (Boolean) and --es (String)
            if (s == null) return null;
            s = s.Trim().ToLowerInvariant();
            return s == "true" || s == "1" || s == "yes";
        }
#endif
    }
}
