using UnityEngine;

namespace AG.Guide
{
    /// <summary>
    /// Placeholder <see cref="IGhostRenderer"/>: a translucent cube at the current action's target so the wiring can be
    /// seen on device. Replace with the real renderer (Guide agent); keep the interface.
    /// </summary>
    public sealed class GhostRendererStub : IGhostRenderer
    {
        AssemblyGuide _guide;
        Transform _anchor;
        GameObject _marker;

        public bool LabelsVisible { get; set; } = true;

        public void Bind(AssemblyGuide guide, Transform assemblyAnchor, string lang)
        {
            _guide = guide;
            _anchor = assemblyAnchor;
            if (guide.Root != null) guide.Root.transform.SetParent(assemblyAnchor, false);
        }

        public void ShowAction(int step, int actionIndex)
        {
            var s = _guide?.StepByNumber(step);
            if (s == null || actionIndex < 0 || actionIndex >= s.Actions.Count) { Clear(); return; }
            var a = s.Actions[actionIndex];
            if (_marker == null)
            {
                _marker = GameObject.CreatePrimitive(PrimitiveType.Cube);
                _marker.name = "GhostStub";
                Object.Destroy(_marker.GetComponent<Collider>());
                _marker.transform.localScale = Vector3.one * 0.03f;
            }
            _marker.transform.SetParent(_anchor, false);
            _marker.transform.localPosition = a.Target ?? Vector3.zero;
            _marker.SetActive(true);
        }

        public void SetPartState(string node, PartVisualState state) { }

        public void Clear() { if (_marker != null) _marker.SetActive(false); }
    }
}
