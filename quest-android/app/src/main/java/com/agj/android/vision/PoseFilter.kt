package com.agj.android.vision

import com.agj.android.core.Pose3

/**
 * Smooths tracker poses for rendering (WORLD ← ASSEMBLY, metres) and holds the last good pose while the marker is
 * hidden.
 *
 * Threading: not thread-safe; call [update] and [current] from one thread (the render thread).
 */
interface PoseFilter {
  /**
   * Feed a measurement ([measured] null = no measurement this time) taken at [tNs]. Returns the filtered pose, or
   * null when nothing has been measured for longer than the hold time.
   */
  fun update(measured: Pose3?, tNs: Long): Pose3?

  fun current(): Pose3?

  fun reset()
}

/**
 * Phase 1: exponential smoothing (position lerp, rotation nlerp) with a hold. TODO(phase 2, vision owner): One-Euro
 * filter (speed-adaptive), outlier rejection on jumps, and prediction to display time.
 *
 * @param alpha weight of a new measurement, 0..1.
 * @param holdNs how long the last pose is kept without measurements.
 */
class ExpPoseFilter(private val alpha: Float = 0.35f, private val holdNs: Long = 1_500_000_000L) : PoseFilter {
  private var pose: Pose3? = null
  private var lastNs = 0L

  override fun update(measured: Pose3?, tNs: Long): Pose3? {
    if (measured != null) {
      val p = pose
      pose =
          if (p == null) measured
          else Pose3(p.t.lerp(measured.t, alpha), p.q.nlerp(measured.q, alpha))
      lastNs = tNs
    } else if (pose != null && tNs - lastNs > holdNs) {
      pose = null
    }
    return pose
  }

  override fun current() = pose

  override fun reset() {
    pose = null
  }
}
