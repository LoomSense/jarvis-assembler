using System.Collections.Generic;
using UnityEngine;

namespace AG.Guide
{
    /// <summary>Action kinds of the manifest. <c>Note</c> is text only (no part, no target).</summary>
    public enum ActionType { Place, Fasten, Turn, Press, Tighten, Note }

    /// <summary>
    /// One guide ready for the headset: built by <see cref="IJobRepository.LoadGuideAsync"/> from manifest.json and
    /// walkthrough.glb. Read-only after loading; main thread only (holds GameObjects).
    ///
    /// FRAMES. "Assembly frame" = local frame of the GLB root node <c>assembly</c> (Unity, left-handed, Y up,
    /// metres). Every Vector3/Quaternion below is in that frame unless its name says World. The tracked pose of
    /// the real object (Vision's TrackResult.AssemblyInWorld) maps assembly frame -> world.
    /// </summary>
    public sealed class AssemblyGuide
    {
        public string JobId;
        public string Title;
        public string GuideId;
        /// <summary>Overall size in metres (L, W, H as the manifest gives them, unconverted axes).</summary>
        public Vector3 OverallSizeM;
        public List<GuideStep> Steps = new List<GuideStep>();
        /// <summary>By GLB node name (<c>part_&lt;id&gt;_&lt;n&gt;</c>, <c>hw_&lt;code&gt;_&lt;n&gt;</c>).</summary>
        public Dictionary<string, PartNode> Nodes = new Dictionary<string, PartNode>();
        /// <summary>Part id -> display info. Hardware is keyed by its code.</summary>
        public Dictionary<string, PartInfo> Parts = new Dictionary<string, PartInfo>();
        /// <summary>Instantiated GLB root (the <c>assembly</c> node), inactive until a renderer binds it. May be null in stubs.</summary>
        public GameObject Root;
        /// <summary>The raw manifest, for anything not modelled here.</summary>
        public ManifestDto Manifest;

        /// <summary>Bounds of the finished assembly in the assembly frame (all part nodes at their final transform).</summary>
        public Bounds FinalBounds;
        /// <summary>Last keyframe of the <c>walkthrough</c> clip in seconds (0 when the GLB was not loaded).</summary>
        public float WalkthroughEndS;

        public int ActionCount { get { int n = 0; foreach (var s in Steps) n += s.Actions.Count; return n; } }
        public GuideStep StepByNumber(int n) => Steps.Find(s => s.N == n);
    }

    public sealed class GuideStep
    {
        /// <summary>Step number as printed in the guide (1-based; not always contiguous).</summary>
        public int N;
        public string Title;
        public string PoseText;
        /// <summary>How the whole assembly stands during this step (from assemblyRotationDeg, converted).</summary>
        public Quaternion AssemblyRotation = Quaternion.identity;
        public bool FlipWholeAssembly;
        public int? Page;
        /// <summary>Relative path under /files/{job}/, e.g. crops/step-01.png.</summary>
        public string CropPath;
        public List<GuideAction> Actions = new List<GuideAction>();
        public List<(string code, int qty)> Hardware = new List<(string, int)>();
        /// <summary>Seconds into the walkthrough clip.</summary>
        public float WalkStartS, WalkEndS;
    }

    public sealed class GuideAction
    {
        /// <summary>0-based index within the step (the perception socket's <c>action</c>).</summary>
        public int Index;
        public ActionType Type;
        public LocalizedText Text;
        /// <summary>GLB node the action moves; null for turn/note.</summary>
        public string Node;
        public string PartId;
        public string Code;
        /// <summary>Final position of the node, assembly frame, metres (converted target_m).</summary>
        public Vector3? Target;
        /// <summary>Unit vector from Target towards where the part comes from (converted from_dir).</summary>
        public Vector3? FromDir;
        /// <summary>Turn actions: rotation applied to the whole assembly (converted rotation_deg).</summary>
        public Quaternion? Turn;
        /// <summary>Seconds in the step clip.</summary>
        public float T0S, T1S;
        /// <summary>1-based position among the step's actions on the same part id / hardware code ("2 of 4"), and that total.</summary>
        public int CountIndex = 1, CountTotal = 1;
    }

    /// <summary>One GLB node that is a part or a piece of hardware.</summary>
    public sealed class PartNode
    {
        public string Node;
        /// <summary>Part id, or hardware code when <see cref="IsHardware"/>.</summary>
        public string PartId;
        public bool IsHardware;
        /// <summary>
        /// Final local transform under <c>assembly</c>, read at the LAST frame of the <c>walkthrough</c> clip
        /// (parts rest hidden at scale 1e-4 before that). Metres.
        /// </summary>
        public Vector3 FinalLocalPosition;
        public Quaternion FinalLocalRotation = Quaternion.identity;
        public Vector3 FinalLocalScale = Vector3.one;
        /// <summary>Bounds in the assembly frame at the final transform.</summary>
        public Bounds FinalBounds;
        /// <summary>Step that places this node (first action that names it), or 0.</summary>
        public int PlacedInStep;
        public GameObject Instance;
    }

    public sealed class PartInfo
    {
        public string Id;
        public bool IsHardware;
        public LocalizedText Names;
        public string Kind;
        public Color Color = Color.white;
        /// <summary>Relative path under /files/{job}/ of the shaded render.</summary>
        public string ThumbPath;
        public List<string> Nodes = new List<string>();
    }
}
