using UnityEngine;

namespace AG.Vision
{
    /// <summary>
    /// One Euro filter (Casiez et al. 2012) on position and rotation: strong smoothing when the pose is still (kills
    /// marker jitter), little lag when it moves (cutoff rises with speed). Plus a jump reset: a measurement farther
    /// than <see cref="JumpM"/> / <see cref="JumpDeg"/> snaps instead of sliding (the tracker's outlier gate decides
    /// before that whether a jump is real). <see cref="Predict"/> holds the last pose.
    /// </summary>
    public sealed class SmoothingPoseFilter : IPoseFilter
    {
        /// <summary>Cutoff (Hz) when still. Lower = smoother.</summary>
        public float MinCutoffHz = 1.0f;
        /// <summary>Cutoff increase per m/s (position) or per rad/s (rotation). Higher = less lag when moving.</summary>
        public float BetaPos = 20f, BetaRot = 0.6f;
        /// <summary>Cutoff for the speed estimate.</summary>
        public float DerivCutoffHz = 1.0f;
        public float JumpM = 0.10f, JumpDeg = 25f;

        Vector3 _p, _dp;
        Quaternion _q;
        float _dq;
        double _t;
        bool _has;

        static float Alpha(float cutoffHz, float dt)
        {
            float tau = 1f / (2f * Mathf.PI * Mathf.Max(1e-3f, cutoffHz));
            return 1f / (1f + tau / Mathf.Max(1e-5f, dt));
        }

        public Pose Filter(Pose m, double timeS)
        {
            if (!_has || Vector3.Distance(m.position, _p) > JumpM || Quaternion.Angle(m.rotation, _q) > JumpDeg)
            {
                _p = m.position; _q = m.rotation; _dp = Vector3.zero; _dq = 0; _t = timeS; _has = true;
                return m;
            }
            float dt = (float)(timeS - _t);
            if (dt <= 1e-5f) dt = 1f / 60f;      // same timestamp (replay at fixed rate, duplicated frame): assume one frame
            _t = timeS;

            var v = (m.position - _p) / dt;
            _dp = Vector3.Lerp(_dp, v, Alpha(DerivCutoffHz, dt));
            _p = Vector3.Lerp(_p, m.position, Alpha(MinCutoffHz + BetaPos * _dp.magnitude, dt));

            float w = Quaternion.Angle(_q, m.rotation) * Mathf.Deg2Rad / dt;
            _dq = Mathf.Lerp(_dq, w, Alpha(DerivCutoffHz, dt));
            _q = Quaternion.Slerp(_q, m.rotation, Alpha(MinCutoffHz + BetaRot * _dq, dt));
            return new Pose(_p, _q);
        }

        public Pose Predict(double timeS) => new Pose(_p, _q);

        public void Reset() { _has = false; }
    }
}
