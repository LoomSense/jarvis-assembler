using UnityEngine;

namespace AG.Net
{
    /// <summary>
    /// Hand joints -> the socket's <c>hands</c> summary (server/perception/README.md: hand_at_target, still, raised,
    /// present; ~10 Hz). Pure and engine-light so it can be tested; positions in Unity world, metres.
    ///
    /// - present: any hand tracked.
    /// - hand_at_target: an index fingertip or palm within <see cref="AtTargetM"/> (5 cm) of the current ghost part's
    ///   world box (given by the caller), with hysteresis (leave at 1.5x). Port of client/src/xr/handChecks.ts.
    /// - still: the tracked palms stayed within <see cref="StillRadiusM"/> (1 cm) for <see cref="StillS"/> (0.5 s).
    /// - raised: a palm above the head minus <see cref="RaisedBelowHeadM"/> (a hand lifted to ask for attention).
    /// Call <see cref="Update"/> every frame; <see cref="TryEmit"/> returns a message at most every 1/<see cref="Hz"/> s.
    /// </summary>
    public sealed class HandsSummarizer
    {
        public float Hz = 10f;
        public float AtTargetM = 0.05f;
        public float StillRadiusM = 0.01f, StillS = 0.5f;
        public float RaisedBelowHeadM = 0.05f;

        Vector3 _lastL, _lastR;
        bool _hadL, _hadR;
        float _stillFor;
        bool _atTarget;
        double _lastEmit = double.NegativeInfinity;

        public bool Present { get; private set; }
        public bool AtTarget => _atTarget;
        public bool Still { get; private set; }
        public bool Raised { get; private set; }

        /// <param name="leftPalm">null when that hand is not tracked; likewise the others.</param>
        /// <param name="target">world position of the current target; null = none (hand_at_target false).</param>
        public void Update(float dt, Vector3? leftPalm, Vector3? leftIndexTip, Vector3? rightPalm, Vector3? rightIndexTip,
                           Vector3 head, Vector3? target)
            => Update(dt, leftPalm, leftIndexTip, rightPalm, rightIndexTip, head,
                      target.HasValue ? new Bounds(target.Value, Vector3.zero) : (Bounds?)null);

        /// <param name="targetBox">world box of the current ghost part (Renderer.bounds); distance is to the box surface.</param>
        public void Update(float dt, Vector3? leftPalm, Vector3? leftIndexTip, Vector3? rightPalm, Vector3? rightIndexTip,
                           Vector3 head, Bounds? targetBox)
        {
            Present = leftPalm.HasValue || rightPalm.HasValue;
            // still: every tracked palm stays within StillRadiusM of where it was when the window began, for StillS
            bool moved = !Present;
            if (leftPalm.HasValue) { if (!_hadL || (leftPalm.Value - _lastL).magnitude > StillRadiusM) { _lastL = leftPalm.Value; moved = true; } }
            if (rightPalm.HasValue) { if (!_hadR || (rightPalm.Value - _lastR).magnitude > StillRadiusM) { _lastR = rightPalm.Value; moved = true; } }
            _hadL = leftPalm.HasValue; _hadR = rightPalm.HasValue;
            _stillFor = moved ? 0f : _stillFor + dt;
            Still = Present && _stillFor >= StillS;
            Raised = (leftPalm.HasValue && leftPalm.Value.y > head.y - RaisedBelowHeadM)
                  || (rightPalm.HasValue && rightPalm.Value.y > head.y - RaisedBelowHeadM);
            float d = float.PositiveInfinity;
            if (targetBox.HasValue)
                foreach (var p in new[] { leftIndexTip, rightIndexTip, leftPalm, rightPalm })
                    if (p.HasValue) d = Mathf.Min(d, Vector3.Distance(p.Value, targetBox.Value.ClosestPoint(p.Value)));
            _atTarget = _atTarget ? d <= AtTargetM * 1.5f : d <= AtTargetM;
        }

        /// <summary>A message when one is due (<see cref="Hz"/>), else null. <paramref name="nowMs"/>: epoch or app ms.</summary>
        public HandsMsg TryEmit(double nowS, double nowMs)
        {
            if (nowS - _lastEmit < 1.0 / Mathf.Max(1f, Hz)) return null;
            _lastEmit = nowS;
            return new HandsMsg { HandAtTarget = _atTarget, Still = Still, Raised = Raised, Present = Present, Ts = nowMs };
        }
    }
}
