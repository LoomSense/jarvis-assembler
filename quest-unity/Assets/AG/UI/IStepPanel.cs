using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using UnityEngine;

namespace AG.UI
{
    /// <summary>Status shown on the window's status line and status pill: always colour + shape + word (spec §4, §8 item 16).</summary>
    public enum StatusKind { None, Ok, Error, Unsure, Info }

    /// <summary>What the step panel shows. Strings already in the user's language.</summary>
    public struct StepView
    {
        public int Step, StepCount;          // guide numbers: Step is the printed number; StepCount the total
        public int StepIndex;                // 0-based index of Step (progress bar)
        public int Action, ActionCount;      // 0-based action within the step
        public string Title;                 // the current action's sentence (manifest action.text), max 2 lines
        public string Detail;                // secondary line (body), e.g. the step title or a perception caption
        public int? Page;                    // guide page for the status line
        public List<(string label, string code, bool primary)> Chips;   // part + hardware chips ("Πλαϊνό 2 από 2", "Καβίλια 101345")
        public List<(string code, int qty, Texture thumb)> Hardware;
        public Texture Crop;                 // the guide's drawing for this step (crops/step-NN.png), may be null
        public string Badge;                 // perception status, e.g. "Right part" / "Λάθος βίδα"; null = none
        public bool BadgeWarning;
        public StatusKind BadgeKind;
        public bool IsLast;                  // last action of the last step: Next becomes Finish
    }

    public enum PanelButton { Next, Back, Repeat, ShowPart, Rescan, WhereAmI, Retry, Cancel }

    /// <summary>
    /// World-space glass window BESIDE the object (never head-locked): AppRoot calls <see cref="PlaceBeside"/> with the
    /// assembly bounds; the window sits at the object's side facing the user (XR_NATIVE_SPEC §2.4-2.6) and follows
    /// the object with a critically damped spring and a 3 cm dead band, turning only about world up. Text: TMP SDF
    /// font with Greek, angular sizes per §2.2. Material: AG/Glass (§3.1). Buttons live in a toolbar ornament.
    ///
    /// Also used for coach cards (compact window) before a guide is loaded: <see cref="ShowMessage"/>.
    /// Threading: main thread. Owner: UI agent.
    /// </summary>
    public interface IStepPanel
    {
        void Show(StepView view);
        void ShowMessage(string title, string detail = null, float? progress01 = null);
        /// <summary>Coach card buttons (left to right; at most 4; <paramref name="primary"/> is filled).</summary>
        void SetButtons(PanelButton[] buttons, PanelButton? primary = null);
        /// <summary>Status pill above the window (tracking lost, vision paused, offline); null hides it.</summary>
        void SetStatusPill(string text, StatusKind kind = StatusKind.Unsure);
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
        /// <summary>Fetch and cache the audio for a line that will be said soon (next action), without playing it.</summary>
        void Prefetch(string text);
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
