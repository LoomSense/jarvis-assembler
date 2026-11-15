using System.Collections.Generic;
using Newtonsoft.Json;

namespace AG.Guide
{
    // Raw shape of /files/{job}/manifest.json (pipeline/README.md, PLAN §4.3), read with Newtonsoft.
    // Field names are the server's; do not rename. Every vector here is in BLENDER Z-UP coordinates
    // (metres / degrees); convert with FrameConvert before use. Unknown fields are ignored.

    public sealed class ManifestDto
    {
        [JsonProperty("title")] public string Title;
        [JsonProperty("guideId")] public string GuideId;
        [JsonProperty("glb")] public string Glb;                 // "walkthrough.glb"
        [JsonProperty("glbPacked")] public string GlbPacked;     // meshopt-compressed variant, optional
        [JsonProperty("units")] public string Units;             // "m"
        [JsonProperty("fps")] public float Fps;
        [JsonProperty("rootNode")] public string RootNode;       // "assembly"
        [JsonProperty("markerNode")] public string MarkerNode;   // "marker"
        [JsonProperty("overall_mm")] public OverallMm OverallMm;
        [JsonProperty("walkthrough")] public WalkthroughDto Walkthrough;
        [JsonProperty("parts")] public List<PartDto> Parts = new List<PartDto>();
        [JsonProperty("hardware")] public List<HardwareDto> Hardware = new List<HardwareDto>();
        [JsonProperty("steps")] public List<StepDto> Steps = new List<StepDto>();
    }

    public sealed class OverallMm
    {
        [JsonProperty("L")] public float L;
        [JsonProperty("W")] public float W;
        [JsonProperty("H")] public float H;
        [JsonProperty("estimated")] public bool Estimated;
    }

    public sealed class WalkthroughDto
    {
        [JsonProperty("clip")] public string Clip;               // "walkthrough"
        [JsonProperty("duration_s")] public float DurationS;
        [JsonProperty("steps")] public List<WalkStepDto> Steps = new List<WalkStepDto>();
    }

    public sealed class WalkStepDto
    {
        /// <summary>Step number, or the string "finish" for the closing segment of the walkthrough.</summary>
        [JsonProperty("n")] public Newtonsoft.Json.Linq.JToken N;
        [JsonProperty("text")] public LocalizedText Text;
        public int? Number => N != null && N.Type == Newtonsoft.Json.Linq.JTokenType.Integer ? (int)N : (int?)null;
        [JsonProperty("start_s")] public float StartS;
        [JsonProperty("end_s")] public float EndS;
    }

    public sealed class LocalizedText
    {
        [JsonProperty("en")] public string En;
        [JsonProperty("el")] public string El;
        public string Get(string lang) => lang == "el" && !string.IsNullOrEmpty(El) ? El : En;
    }

    public sealed class CiteDto
    {
        [JsonProperty("page")] public int? Page;
        [JsonProperty("label")] public string Label;
    }

    public sealed class PartDto
    {
        [JsonProperty("id")] public string Id;
        [JsonProperty("name")] public string Name;
        [JsonProperty("short")] public string Short;
        [JsonProperty("names")] public LocalizedText Names;
        [JsonProperty("count")] public int Count;
        [JsonProperty("nodes")] public List<string> Nodes = new List<string>();   // "part_<id>_<n>"
        [JsonProperty("thumb")] public string Thumb;                              // "renders/part-<id>-shaded.png"
        [JsonProperty("cite")] public CiteDto Cite;
        [JsonProperty("dimsEstimated")] public bool DimsEstimated;
    }

    public sealed class HardwareDto
    {
        [JsonProperty("code")] public string Code;               // e.g. "118331"
        [JsonProperty("name")] public string Name;
        [JsonProperty("short")] public string Short;
        [JsonProperty("names")] public LocalizedText Names;
        [JsonProperty("kind")] public string Kind;               // bolt, screw, dowel, cam, pin, ...
        [JsonProperty("count")] public int Count;
        [JsonProperty("color")] public float[] Color;            // linear RGB 0..1
        [JsonProperty("nodes")] public List<string> Nodes = new List<string>();   // "hw_<code>_<n>"
        [JsonProperty("thumb")] public string Thumb;
    }

    public sealed class HardwareQtyDto
    {
        [JsonProperty("code")] public string Code;
        [JsonProperty("qty")] public int Qty;
    }

    public sealed class StepDto
    {
        [JsonProperty("n")] public int N;
        [JsonProperty("clip")] public string Clip;               // "step_NN"
        [JsonProperty("title")] public string Title;
        [JsonProperty("pose")] public string Pose;               // free text, e.g. "drawer front lying face down"
        [JsonProperty("assemblyRotationDeg")] public float[] AssemblyRotationDeg;   // Blender XYZ Euler, degrees
        [JsonProperty("flipWholeAssembly")] public bool FlipWholeAssembly;
        [JsonProperty("actions")] public List<ActionDto> Actions = new List<ActionDto>();
        [JsonProperty("visible")] public List<string> Visible = new List<string>();
        [JsonProperty("moving")] public List<string> Moving = new List<string>();
        [JsonProperty("pickParts")] public List<string> PickParts = new List<string>();
        [JsonProperty("hardware")] public List<HardwareQtyDto> Hardware = new List<HardwareQtyDto>();
        [JsonProperty("cite")] public CiteDto Cite;
        [JsonProperty("crop")] public string Crop;               // "crops/step-NN.png"
        [JsonProperty("duration_s")] public float DurationS;
        [JsonProperty("walkthrough")] public WalkStepDto Walkthrough;
    }

    public sealed class ActionDto
    {
        [JsonProperty("type")] public string Type;               // place | fasten | turn | press | tighten | note
        [JsonProperty("text")] public LocalizedText Text;
        [JsonProperty("t0_s")] public float T0S;                 // in the step clip
        [JsonProperty("t1_s")] public float T1S;
        [JsonProperty("node")] public string Node;
        [JsonProperty("partId")] public string PartId;           // part id, or hardware code for fasten
        [JsonProperty("code")] public string Code;               // hardware code (fasten / press / tighten)
        [JsonProperty("target_m")] public float[] TargetM;       // Blender Z-up, metres, assembly frame
        [JsonProperty("from_dir")] public float[] FromDir;       // Blender Z-up, unit, target -> where the part comes from
        [JsonProperty("rotation_deg")] public float[] RotationDeg; // turn: Blender XYZ Euler, degrees
    }
}
