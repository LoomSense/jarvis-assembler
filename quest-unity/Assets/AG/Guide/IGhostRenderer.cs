using UnityEngine;

namespace AG.Guide
{
    /// <summary>How one part is drawn.</summary>
    public enum PartVisualState
    {
        /// <summary>Not drawn (later steps).</summary>
        Hidden,
        /// <summary>Already assembled: drawn faintly or not at all over the real object (occlusion only).</summary>
        Placed,
        /// <summary>The current action's part: translucent ghost at its final place, insertion loop from from_dir.</summary>
        Ghost,
        /// <summary>Next action's part: outline only.</summary>
        Upcoming,
    }

    /// <summary>
    /// Draws the guide ON the real object: everything is parented under <c>assemblyAnchor</c>, a Transform whose
    /// world pose AppRoot keeps equal to the tracked assembly pose (Vision), so this renderer works purely in the
    /// assembly frame and never reads tracking itself.
    ///
    /// Visuals to port from client/src/xr/ghost.ts and stepGuides.ts: ghost of the current part at its final place,
    /// insertion arrow along <see cref="GuideAction.FromDir"/>, turning arrow for fasten/tighten (screw) and for
    /// turn actions (<see cref="GuideAction.Turn"/>), hole rings at <see cref="GuideAction.Target"/>, a label with the
    /// part name or hardware code (e.g. "118331 ×2"). Colours and fonts: docs/design/BRAND.md / tokens.css.
    ///
    /// Threading: main thread only. Units: metres. Owner: Guide agent (Assets/AG/Guide/).
    /// </summary>
    public interface IGhostRenderer
    {
        /// <summary>Attach a loaded guide. Re-parents <see cref="AssemblyGuide.Root"/> under <paramref name="assemblyAnchor"/>.</summary>
        void Bind(AssemblyGuide guide, Transform assemblyAnchor, string lang);

        /// <summary>
        /// Show step <paramref name="step"/> (guide number), action <paramref name="actionIndex"/> (0-based):
        /// nodes of earlier steps and earlier actions are Placed, the current action's node is Ghost, the next one
        /// Upcoming, the rest Hidden. Applies the step's <see cref="GuideStep.AssemblyRotation"/> to the ghost only
        /// when the real object is not tracked (tracking already gives the real pose).
        /// </summary>
        void ShowAction(int step, int actionIndex);

        /// <summary>Override one node's state (e.g. highlight a wrong part).</summary>
        void SetPartState(string node, PartVisualState state);

        /// <summary>Labels with part names / hardware codes on or off.</summary>
        bool LabelsVisible { get; set; }

        /// <summary>Hide everything (lost tracking, between flows).</summary>
        void Clear();
    }
}
