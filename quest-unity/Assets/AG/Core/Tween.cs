using System;
using System.Collections.Generic;
using UnityEngine;

namespace AG.Core
{
    /// <summary>Easing curves (t in 0..1). Motion rules: docs/design/XR_NATIVE_SPEC.md when it lands.</summary>
    public static class Ease
    {
        public static float Linear(float t) => t;
        public static float OutCubic(float t) { t = 1 - t; return 1 - t * t * t; }
        public static float InOutCubic(float t) => t < 0.5f ? 4 * t * t * t : 1 - Mathf.Pow(-2 * t + 2, 3) / 2;
        public static float OutBack(float t) { const float c1 = 1.70158f, c3 = c1 + 1; return 1 + c3 * Mathf.Pow(t - 1, 3) + c1 * Mathf.Pow(t - 1, 2); }
        /// <summary>Gentle spring settle (visionOS-like), no overshoot beyond ~4 %.</summary>
        public static float Spring(float t) => 1 - Mathf.Exp(-6f * t) * Mathf.Cos(5f * t);
    }

    /// <summary>
    /// Tiny tween runner, no dependencies. Main thread. Example:
    /// <c>Tween.Run(0.35f, Ease.OutCubic, t =&gt; panel.localScale = Vector3.one * Mathf.Lerp(0.9f, 1f, t));</c>
    /// Cancel with the returned handle; tweens bound to a destroyed <paramref name="owner"/> stop by themselves.
    /// </summary>
    public static class Tween
    {
        public sealed class Handle
        {
            internal float Start, Duration;
            internal Func<float, float> Curve;
            internal Action<float> Step;
            internal Action Done;
            internal UnityEngine.Object Owner;
            internal bool HasOwner;
            public bool Running { get; internal set; } = true;
            public void Cancel() => Running = false;
        }

        static readonly List<Handle> Active = new List<Handle>();
        static Runner _runner;

        public static Handle Run(float duration, Func<float, float> curve, Action<float> step, Action done = null, UnityEngine.Object owner = null)
        {
            EnsureRunner();
            var h = new Handle { Start = Time.unscaledTime, Duration = Mathf.Max(1e-4f, duration), Curve = curve ?? Ease.Linear, Step = step, Done = done, Owner = owner, HasOwner = owner != null };
            Active.Add(h);
            step?.Invoke(h.Curve(0));
            return h;
        }

        /// <summary>Frame-rate independent exponential approach (for following a moving target): a in 0..1 per call.</summary>
        public static float Damp(float halfLifeS, float dt) => 1f - Mathf.Pow(0.5f, dt / Mathf.Max(1e-4f, halfLifeS));

        static void EnsureRunner()
        {
            if (_runner != null) return;
            var go = new GameObject("AG.Tween") { hideFlags = HideFlags.HideInHierarchy };
            UnityEngine.Object.DontDestroyOnLoad(go);
            _runner = go.AddComponent<Runner>();
        }

        sealed class Runner : MonoBehaviour
        {
            void Update()
            {
                float now = Time.unscaledTime;
                for (int i = Active.Count - 1; i >= 0; i--)
                {
                    var h = Active[i];
                    if (!h.Running || (h.HasOwner && h.Owner == null)) { Active.RemoveAt(i); continue; }
                    float t = Mathf.Clamp01((now - h.Start) / h.Duration);
                    try { h.Step?.Invoke(h.Curve(t)); }
                    catch (Exception e) { AgLog.Error("Tween", e.Message); h.Running = false; }
                    if (t >= 1f)
                    {
                        h.Running = false;
                        Active.RemoveAt(i);
                        try { h.Done?.Invoke(); } catch (Exception e) { AgLog.Error("Tween.done", e.Message); }
                    }
                }
            }
        }
    }
}
