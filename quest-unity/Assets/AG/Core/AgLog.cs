using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading;
using Newtonsoft.Json;
using UnityEngine;
using Debug = UnityEngine.Debug;

namespace AG.Core
{
    /// <summary>
    /// Structured logs shared with the Android/Kotlin client and the tools in tools/quest/.
    /// One JSON object per line, logcat tag <c>AGQ</c>:
    /// <c>{"t": ms since start, "app": "unity", "ev": "...", ...fields}</c>.
    ///
    /// On device it writes through <c>android.util.Log.i("AGQ", line)</c>; in the editor through
    /// <c>Debug.Log("AGQ " + line)</c>. Read on a PC with <c>adb logcat -s AGQ</c>.
    ///
    /// Events every module must emit (field names are a contract, keep them exact):
    /// <list type="bullet">
    /// <item><c>start</c>: version, params (key redacted)</item>
    /// <item><c>job_loaded</c>: job, steps, actions, ms</item>
    /// <item><c>identify</c>: ms, product, confidence</item>
    /// <item><c>locate</c>: ms, next_step</item>
    /// <item><c>track</c> (1 Hz): fps, tracked, pos_m [x,y,z], yaw_deg, reproj_px, source "marker"|"touch"|"none"</item>
    /// <item><c>perception</c>: event, ms</item>
    /// <item><c>step</c>: step, action, by "auto"|"button"</item>
    /// <item><c>frame</c> (every 5 s): fps (render)</item>
    /// <item><c>error</c>: where, message</item>
    /// </list>
    ///
    /// Threading: <see cref="Ev"/> may be called from any thread. Lines from worker threads are queued and
    /// written on the main thread by <see cref="Pump"/> (called by AppRoot every frame), because JNI calls
    /// need an attached thread.
    /// </summary>
    public static class AgLog
    {
        public const string Tag = "AGQ";
        public const string App = "unity";

        static readonly Stopwatch Clock = Stopwatch.StartNew();
        static readonly ConcurrentQueue<string> Pending = new ConcurrentQueue<string>();
        static int _mainThreadId = -1;
#if UNITY_ANDROID && !UNITY_EDITOR
        static AndroidJavaClass _log;
#endif
        static readonly JsonSerializerSettings Json = new JsonSerializerSettings
        {
            NullValueHandling = NullValueHandling.Include,
            ReferenceLoopHandling = ReferenceLoopHandling.Ignore,
            Formatting = Formatting.None,
        };

        /// <summary>Milliseconds since the app started (the <c>t</c> field).</summary>
        public static long Ms => Clock.ElapsedMilliseconds;

        /// <summary>Call once from the main thread at startup (AppRoot.Awake).</summary>
        public static void Init()
        {
            _mainThreadId = Thread.CurrentThread.ManagedThreadId;
        }

        /// <summary>
        /// Log one event. <paramref name="fields"/> is an anonymous object or a dictionary; its members become
        /// top-level keys next to t/app/ev. Example: <c>AgLog.Ev("locate", new { ms = 812, next_step = 3 });</c>
        /// Vectors: pass arrays in metres, e.g. <c>pos_m = new[] { p.x, p.y, p.z }</c>.
        /// </summary>
        public static void Ev(string ev, object fields = null)
        {
            var o = new Dictionary<string, object> { ["t"] = Ms, ["app"] = App, ["ev"] = ev };
            if (fields != null)
            {
                IDictionary<string, object> extra = fields as IDictionary<string, object>
                    ?? Newtonsoft.Json.Linq.JObject.FromObject(fields).ToObject<Dictionary<string, object>>();
                foreach (var kv in extra) o[kv.Key] = kv.Value;
            }
            string line;
            try { line = JsonConvert.SerializeObject(o, Json); }
            catch (Exception e) { line = "{\"t\":" + Ms + ",\"app\":\"unity\",\"ev\":\"error\",\"where\":\"AgLog\",\"message\":" + JsonConvert.ToString(e.Message) + "}"; }
            if (Thread.CurrentThread.ManagedThreadId == _mainThreadId) Write(line);
            else Pending.Enqueue(line);
        }

        /// <summary>Shorthand for the <c>error</c> event.</summary>
        public static void Error(string where, string message) => Ev("error", new { where, message });

        /// <summary>Main thread, every frame: writes lines queued by worker threads.</summary>
        public static void Pump()
        {
            while (Pending.TryDequeue(out var line)) Write(line);
        }

        static void Write(string line)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                _log ??= new AndroidJavaClass("android.util.Log");
                _log.CallStatic<int>("i", Tag, line);
                return;
            }
            catch (Exception) { /* fall through to Debug.Log */ }
#endif
            Debug.Log(Tag + " " + line);
        }
    }
}
