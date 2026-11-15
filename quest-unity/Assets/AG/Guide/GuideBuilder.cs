using System;
using UnityEngine;

namespace AG.Guide
{
    /// <summary>Manifest -> <see cref="AssemblyGuide"/> without the GLB (no node transforms). Pure, any thread.</summary>
    public static class GuideBuilder
    {
        public static AssemblyGuide FromManifest(string jobId, ManifestDto m)
        {
            var g = new AssemblyGuide
            {
                JobId = jobId, Title = m.Title, GuideId = m.GuideId, Manifest = m,
                OverallSizeM = m.OverallMm == null ? Vector3.zero : new Vector3(m.OverallMm.L, m.OverallMm.W, m.OverallMm.H) / 1000f,
            };
            foreach (var p in m.Parts)
            {
                g.Parts[p.Id] = new PartInfo { Id = p.Id, Names = p.Names, ThumbPath = p.Thumb, Nodes = p.Nodes };
                foreach (var n in p.Nodes) g.Nodes[n] = new PartNode { Node = n, PartId = p.Id };
            }
            foreach (var h in m.Hardware)
            {
                var c = h.Color != null && h.Color.Length >= 3 ? new Color(h.Color[0], h.Color[1], h.Color[2]) : Color.gray;
                g.Parts[h.Code] = new PartInfo { Id = h.Code, IsHardware = true, Names = h.Names, Kind = h.Kind, Color = c, ThumbPath = h.Thumb, Nodes = h.Nodes };
                foreach (var n in h.Nodes) g.Nodes[n] = new PartNode { Node = n, PartId = h.Code, IsHardware = true };
            }
            foreach (var s in m.Steps)
            {
                var step = new GuideStep
                {
                    N = s.N, Title = s.Title, PoseText = s.Pose, Page = s.Cite?.Page, CropPath = s.Crop,
                    AssemblyRotation = FrameConvert.BlenderEulerDegToUnity(s.AssemblyRotationDeg),
                    FlipWholeAssembly = s.FlipWholeAssembly,
                    WalkStartS = s.Walkthrough?.StartS ?? 0, WalkEndS = s.Walkthrough?.EndS ?? 0,
                };
                foreach (var h in s.Hardware) step.Hardware.Add((h.Code, h.Qty));
                for (int i = 0; i < s.Actions.Count; i++)
                {
                    var a = s.Actions[i];
                    step.Actions.Add(new GuideAction
                    {
                        Index = i, Type = Parse(a.Type), Text = a.Text, Node = a.Node, PartId = a.PartId, Code = a.Code,
                        Target = a.TargetM != null ? FrameConvert.BlenderPointToUnity(a.TargetM) : (Vector3?)null,
                        FromDir = a.FromDir != null ? FrameConvert.BlenderDirToUnity(a.FromDir) : (Vector3?)null,
                        Turn = a.RotationDeg != null ? FrameConvert.BlenderEulerDegToUnity(a.RotationDeg) : (Quaternion?)null,
                        T0S = a.T0S, T1S = a.T1S,
                    });
                    if (a.Node != null && g.Nodes.TryGetValue(a.Node, out var node) && node.PlacedInStep == 0) node.PlacedInStep = s.N;
                }
                g.Steps.Add(step);
            }
            return g;
        }

        public static ActionType Parse(string t) => t switch
        {
            "place" => ActionType.Place,
            "fasten" => ActionType.Fasten,
            "turn" => ActionType.Turn,
            "press" => ActionType.Press,
            "tighten" => ActionType.Tighten,
            _ => ActionType.Note,
        };
    }
}
