using System;
using System.Threading;
using System.Threading.Tasks;
using AG.Guide;
using AG.Net;
using AG.Vision;
using UnityEngine;

namespace AG.UI
{
    /// <summary>The coach's stages, in order (client/src/xr/scan.ts is the WebXR version).</summary>
    public enum CoachStage
    {
        /// <summary>"Look at the product or its box." Waiting for a usable camera frame.</summary>
        Look,
        /// <summary>POST /api/identify with one frame.</summary>
        Identify,
        /// <summary>catalog/open + poll /api/jobs/{id} until done; then load manifest + GLB.</summary>
        BuildGuide,
        /// <summary>POST /api/perception/{job}/locate: which step the real assembly is at.</summary>
        Locate,
        /// <summary>Marker found, or two-corner touch: the ghost sits on the real object.</summary>
        Register,
        /// <summary>Step by step with the perception socket and voice.</summary>
        Guide,
        Done,
        Failed,
    }

    /// <summary>Everything the coach drives. Filled by AppRoot; all interfaces, so modules stay swappable.</summary>
    public sealed class CoachContext
    {
        public string Lang = "en";
        /// <summary>Skip Look/Identify/BuildGuide when set (launch param <c>job</c>).</summary>
        public string JobId;
        public IServerApi Api;
        public IPerceptionSocket Socket;
        public IJobRepository Jobs;
        public ICameraSource Camera;
        public IObjectTracker Tracker;
        public IGhostRenderer Ghost;
        public IStepPanel Panel;
        public IVoice Voice;
        /// <summary>Transform kept at the tracked assembly pose (AppRoot updates it from Tracker).</summary>
        public Transform AssemblyAnchor;
        /// <summary>Parent for the loaded GLB.</summary>
        public Transform GuideParent;
        /// <summary>Set by the coach once loaded.</summary>
        public AssemblyGuide Guide;
        /// <summary>Spatialised source for guidance cues (success, warning, ...), near the object. Optional.</summary>
        public AudioSource Sfx;
        /// <summary>Seconds to wait for the tracker in the Register stage before guiding untracked.</summary>
        public float RegisterTimeoutS = 12f;
    }

    /// <summary>
    /// The whole flow look -> identify -> build guide -> locate -> register/track -> guide, as a state machine.
    /// Every stage shows one line on the panel and says it once (voice), offers "retry" on failure, and logs.
    /// The user can always advance by hand (pinch "Next"); perception step_complete advances automatically
    /// (log <c>step</c> {step, action, by:"auto"|"button"}).
    ///
    /// Threading: main thread; <see cref="RunAsync"/> returns when Done/Failed or cancelled.
    /// Owner: UI agent (Assets/AG/UI/).
    /// </summary>
    public interface ICoach
    {
        CoachStage Stage { get; }
        int Step { get; }
        int Action { get; }
        event Action<CoachStage> StageChanged;
        Task RunAsync(CoachContext ctx, CancellationToken ct);
        /// <summary>User commands from panel buttons, gestures or voice.</summary>
        void Next();
        void Back();
        void Repeat();
        void Rescan();
        /// <summary>"Where am I": a new /locate on the current frame, jump to the step it finds.</summary>
        void WhereAmI();
        /// <summary>Retry after a failed stage (the Failed card's button).</summary>
        void Retry();
    }
}
