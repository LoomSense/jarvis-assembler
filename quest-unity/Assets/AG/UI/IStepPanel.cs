using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using UnityEngine;

namespace AG.UI
{
    /// <summary>What the step panel shows. Strings already in the user's language.</summary>
    public struct StepView
    {
        public int Step, StepCount;          // guide numbers: Step is the printed number; StepCount the total
        public int Action, ActionCount;      // 0-based action within the step
        public string Title;                 // the current action's sentence (manifest action.text)
        public string Detail;                // secondary line, e.g. "Step 2 · page 5" or a perception message
        public List<(string code, int qty, Texture thumb)> Hardware;
        public Texture Crop;                 // the guide's drawing for this step (crops/step-NN.png), may be null
        public string Badge;                 // perception status, e.g. "Right part" / "Λάθος βίδα"; null = none
        public bool BadgeWarning;
    }

    public enum PanelButton { Next, Back, Repeat, ShowPart, Rescan }

    /// <summary>
    /// World-space glass panel BESIDE the object (never head-locked): AppRoot calls <see cref="PlaceBeside"/> with the
    /// assembly bounds; the panel sits at the object's side facing the user, ~0.15 m off the bounds, and follows
    /// the object smoothly (Tween.Damp), turning only about world up. Text: TextMeshPro SDF font with Greek,
    /// angular sizes per docs/design/XR_NATIVE_SPEC.md. Material: UiTheme.GlassMaterial slot.
    ///
    /// Also used for coach messages (stage text) before a guide is loaded: <see cref="ShowMessage"/>.
    /// Threading: main thread. Owner: UI agent.
    /// </summary>
    public interface IStepPanel
    {
        void Show(StepView view);
        void ShowMessage(string title, string detail = null, float? progress01 = null);
        /// <summary>Keep the panel next to <paramref name="assemblyBoundsWorld"/>, facing <paramref name="viewerWorld"/>.</summary>
        void PlaceBeside(Bounds assemblyBoundsWorld, Vector3 viewerWorld);
        void SetVisible(bool visible);
        event Action<PanelButton> Pressed;
    }

    /// <summary>
    /// Speech: server TTS (POST /api/tts, Gemini voice, EN and EL) played on an AudioSource near the panel.
    /// Queue by default; <c>interrupt</c> cuts the current line (warnings). Same text is cached.
    /// Threading: main thread. Owner: UI agent.
    /// </summary>
    public interface IVoice
    {
        bool Speaking { get; }
        Task SayAsync(string text, bool interrupt = false);
        void StopSpeaking();
    }

    /// <summary>Debug HUD state (launch param <c>hud=true</c>).</summary>
    public struct HudState
    {
        public float Fps;
        public string Tracker;               // e.g. "marker 3.1 px" / "touch" / "none"
        public string LastEvent;             // last perception event
        public int LastEventMs;
        public int Step, Action;
        public bool ServerOk;
        public string Camera;                // "pca 1280x960" / "replay 34/120"
    }

    /// <summary>Small world-space text block, lower left of view, for testing. Owner: UI agent.</summary>
    public interface IDebugHud
    {
        void Set(HudState s);
        void SetVisible(bool visible);
    }
}
