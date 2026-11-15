using UnityEngine;

namespace AG.Guide
{
    /// <summary>How one part is drawn.</summary>
    public enum PartVisualState
    {
        /// <summary>Not drawn (later steps).</summary>
        Hidden,
        /// <summary>Already assembled: depth-only over the tracked real object (hides the ghost behind it), faint glass when untracked.</summary>
        Placed,
        /// <summary>The current action's part: translucent ghost at its final place, insertion path from from_dir.</summary>
        Ghost,
        /// <summary>Next action's part. Not drawn by GhostRenderer (spec §1.2: nothing of the next action is visible).</summary>
        Upcoming,
    }

    /// <summary>Perception verdict on the current action (XR_NATIVE_SPEC §4.7-4.9).</summary>
    public enum GuideFeedback { None, Correct, Wrong, Unsure }

    /// <summary>Where the assembly pose comes from, as the renderer needs to know it (spec §4.12).</summary>
    public enum GhostTracking
    {
        /// <summary>Never tracked: the anchor is a preview placement; the step pose (assemblyRotationDeg) is applied.</summary>
        Untracked,
        /// <summary>The anchor follows the real object: no step pose, placed parts are depth-only.</summary>
        Tracked,
        /// <summary>Was tracked, now lost: ghost frozen at 30 %, dashed.</summary>
        Lost,
    }

    /// <summary>
    /// Draws the guide ON the real object: everything is parented under <c>assemblyAnchor</c>, a Transform whose
    /// world pose AppRoot keeps equal to the tracked assembly pose (Vision), so this renderer works purely in the
    /// assembly frame and never reads tracking itself.
    ///
    /// Look: docs/design/XR_NATIVE_SPEC.md §4 (ghost with fresnel rim and edges, dotted insertion path with a
    /// travelling dash, turning arrow for fasten/tighten, hole ripple, label pill with leader line
    /// "Name · code [2 of 4]", correct/wrong/unsure, turn preview, completion rings).
    ///
    /// Threading: main thread only. Units: metres. Owner: Guide agent (Assets/AG/Guide/).
    /// </summary>
    public interface IGhostRenderer
    {
        /// <summary>Attach a loaded guide. Re-parents <see cref="AssemblyGuide.Root"/> under <paramref name="assemblyAnchor"/>.</summary>
        void Bind(AssemblyGuide guide, Transform assemblyAnchor, string lang);

        /// <summary>
        /// Show step <paramref name="step"/> (guide number), action <paramref name="actionIndex"/> (0-based):
        /// nodes of earlier steps and earlier actions are Placed, the current action's node is Ghost, the rest Hidden.
        /// Applies the step's <see cref="GuideStep.AssemblyRotation"/> only while <see cref="GhostTracking.Untracked"/>.
        /// </summary>
        void ShowAction(int step, int actionIndex);

        /// <summary>Override one node's state (e.g. highlight a wrong part).</summary>
        void SetPartState(string node, PartVisualState state);

        /// <summary>Correct / wrong / unsure on the current action. <paramref name="observedPartId"/>: what the camera saw (wrong).</summary>
        void ShowFeedback(GuideFeedback kind, string observedPartId = null);

        /// <summary>Tracking state of the anchor (spec §4.12).</summary>
        void SetTracking(GhostTracking state);

        /// <summary>The finished-assembly sequence (spec §4.11).</summary>
        void ShowComplete();

        /// <summary>Bounds of the whole assembly in world space (for the panel's placement); false before Bind.</summary>
        bool TryGetWorldBounds(out Bounds worldBounds);

        /// <summary>Labels with part names / hardware codes on or off.</summary>
        bool LabelsVisible { get; set; }

        /// <summary>Freeze every loop (spec §7).</summary>
        bool ReducedMotion { get; set; }

        /// <summary>Hide everything (between flows).</summary>
        void Clear();
    }
}
