package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import kotlin.math.PI
import kotlin.math.abs

/**
 * Smooths tracker poses for rendering (WORLD ← ASSEMBLY, metres) and holds the last good pose while the marker is
 * hidden.
 *
 * Threading: not thread-safe; call [update] and [current] from one thread (or under one lock).
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

/** Phase 1 filter, kept for comparison: exponential smoothing with a hold. */
class ExpPoseFilter(private val alpha: Float = 0.35f, private val holdNs: Long = 1_500_000_000L) : PoseFilter {
  private var pose: Pose3? = null
  private var lastNs = 0L

  override fun update(measured: Pose3?, tNs: Long): Pose3? {
    if (measured != null) {
      val p = pose
      pose = if (p == null) measured else Pose3(p.t.lerp(measured.t, alpha), p.q.nlerp(measured.q, alpha))
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

/** Tuning for [OneEuroPoseFilter]. Speeds in m/s and rad/s, cut-offs in Hz. */
data class OneEuroParams(
    val posMinCutoffHz: Double = 1.0,
    val posBeta: Double = 20.0,
    val rotMinCutoffHz: Double = 1.0,
    val rotBeta: Double = 2.0,
    val dCutoffHz: Double = 1.0,
    /** A measurement this far from the current estimate is an outlier candidate. */
    val jumpM: Double = 0.05,
    val jumpDeg: Double = 20.0,
    /** This many consecutive candidates that agree with each other mean the object really moved: re-lock on them. */
    val relockAfter: Int = 3,
    val relockAgreeM: Double = 0.02,
    val relockAgreeDeg: Double = 8.0,
    /** How long the last pose is held without measurements; Long.MAX_VALUE = forever (anchor). */
    val holdNs: Long = Long.MAX_VALUE,
)

/**
 * One-Euro filter (Casiez et al. 2012) on position (per axis) and rotation (angular speed → cut-off, nlerp), with
 * outlier rejection and a hold:
 *  - a measurement more than [OneEuroParams.jumpM] / [OneEuroParams.jumpDeg] away from the estimate is rejected,
 *    unless [OneEuroParams.relockAfter] such measurements in a row agree with each other (the object was moved):
 *    then the filter re-locks on the newest one at once;
 *  - without measurements the last pose is held for [OneEuroParams.holdNs] (forever by default: the marker is
 *    covered from MALM step 5 on, and the ghost must stay put).
 *
 * Pure Kotlin, unit-tested. Not thread-safe.
 */
class OneEuroPoseFilter(private val p: OneEuroParams = OneEuroParams()) : PoseFilter {
  private var pose: Pose3? = null
  private var lastNs = 0L
  private var lastMeasNs = 0L
  private var dx = DoubleArray(3)
  private var dRot = 0.0
  private val candidates = ArrayList<Pose3>()

  /** Measurements rejected as outliers since [reset]. */
  var rejected = 0
    private set

  /** Re-locks after a real move since [reset]. */
  var relocks = 0
    private set

  /** True when the last [update] had no accepted measurement and the pose is held. */
  var holding = false
    private set

  /** Last [update] rejected its measurement as an outlier. */
  var lastRejected = false
    private set

  override fun update(measured: Pose3?, tNs: Long): Pose3? {
    lastRejected = false
    val cur = pose
    if (measured == null) {
      if (cur != null && p.holdNs != Long.MAX_VALUE && tNs - lastMeasNs > p.holdNs) pose = null
      holding = pose != null
      return pose
    }
    if (cur == null) return lock(measured, tNs)
    val dist = (measured.t - cur.t).length().toDouble()
    val ang = Frames.angleDeg(measured.q, cur.q)
    if (dist > p.jumpM || ang > p.jumpDeg) {
      val prev = candidates.lastOrNull()
      if (prev != null &&
          ((measured.t - prev.t).length() > p.relockAgreeM || Frames.angleDeg(measured.q, prev.q) > p.relockAgreeDeg)) {
        candidates.clear()
      }
      candidates += measured
      if (candidates.size >= p.relockAfter) {
        relocks++
        return lock(measured, tNs)
      }
      rejected++
      lastRejected = true
      holding = true
      return pose
    }
    candidates.clear()
    holding = false
    val dt = ((tNs - lastNs) / 1e9).coerceIn(1e-4, 1.0)
    lastNs = tNs
    lastMeasNs = tNs

    // position, per axis
    val m = doubleArrayOf(measured.t.x.toDouble(), measured.t.y.toDouble(), measured.t.z.toDouble())
    val c = doubleArrayOf(cur.t.x.toDouble(), cur.t.y.toDouble(), cur.t.z.toDouble())
    val aD = alpha(p.dCutoffHz, dt)
    var speed2 = 0.0
    for (i in 0..2) {
      dx[i] = dx[i] + aD * ((m[i] - c[i]) / dt - dx[i])
      speed2 += dx[i] * dx[i]
    }
    val aP = alpha(p.posMinCutoffHz + p.posBeta * kotlin.math.sqrt(speed2), dt)
    val t = cur.t.lerp(measured.t, aP.toFloat())

    // rotation: angular speed of the raw change drives the cut-off
    val w = Math.toRadians(ang) / dt
    dRot += aD * (w - dRot)
    val aR = alpha(p.rotMinCutoffHz + p.rotBeta * abs(dRot), dt)
    val q: Quat = cur.q.nlerp(measured.q, aR.toFloat())
    pose = Pose3(t, q)
    return pose
  }

  private fun lock(m: Pose3, tNs: Long): Pose3 {
    pose = m
    lastNs = tNs
    lastMeasNs = tNs
    dx = DoubleArray(3)
    dRot = 0.0
    candidates.clear()
    holding = false
    return m
  }

  override fun current() = pose

  override fun reset() {
    pose = null
    candidates.clear()
    dx = DoubleArray(3)
    dRot = 0.0
    holding = false
    rejected = 0
    relocks = 0
  }

  private fun alpha(cutoffHz: Double, dt: Double): Double {
    val tau = 1.0 / (2 * PI * cutoffHz)
    return 1.0 / (1.0 + tau / dt)
  }
}
