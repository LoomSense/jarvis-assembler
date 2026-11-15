using UnityEngine;

namespace AG.Vision
{
    /// <summary>
    /// Exponential smoothing with a time constant, plus a jump reset (a moved object snaps instead of sliding).
    /// Simple on purpose; a One Euro filter is the natural upgrade (Vision agent).
    /// </summary>
    public sealed class SmoothingPoseFilter : IPoseFilter
    {
        /// <summary>Seconds to reach ~63 % of a step change.</summary>
        public float TimeConstantS = 0.08f;
        /// <summary>Jumps larger than this (metres or degrees) reset the filter.</summary>
        public float JumpM = 0.10f, JumpDeg = 25f;

        Pose _pose;
        double _t;
        bool _has;

        public Pose Filter(Pose m, double timeS)
        {
            if (!_has || Vector3.Distance(m.position, _pose.position) > JumpM || Quaternion.Angle(m.rotation, _pose.rotation) > JumpDeg)
            {
                _pose = m; _t = timeS; _has = true;
                return _pose;
            }
            float dt = Mathf.Max(0f, (float)(timeS - _t));
            float a = 1f - Mathf.Exp(-dt / Mathf.Max(1e-3f, TimeConstantS));
            _pose = new Pose(Vector3.Lerp(_pose.position, m.position, a), Quaternion.Slerp(_pose.rotation, m.rotation, a));
            _t = timeS;
            return _pose;
        }

        public Pose Predict(double timeS) => _pose;

        public void Reset() { _has = false; }
    }
}
