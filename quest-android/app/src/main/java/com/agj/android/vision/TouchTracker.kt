package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.TrackSource
import com.agj.android.core.Vec3
import kotlin.math.atan2
import kotlin.math.hypot

/** Result of the two-corner solve (port of `solveSnap` in client/src/xr/register.ts). */
data class SnapResult(
    /** WORLD ← ASSEMBLY: yaw about +Y plus translation, metres. */
    val worldFromAssembly: Pose3,
    val yawRad: Float,
    /** touched distance and model distance between the two corners, metres */
    val measuredM: Float,
    val modelM: Float,
) {
  /** register.ts accepts measured/model in [0.6, 1.6]. */
  val plausible: Boolean
    get() = modelM > 0f && (measuredM / modelM) in 0.6f..1.6f
}

/**
 * Two-corner fingertip registration: the wearer touches the front-left, then the front-right bottom corner of the
 * real assembly with the index fingertip (a corner counts when the tip stays within 1 cm for 0.8 s, as in
 * register.ts). The pose is static after the snap (no tracking): `source = "touch"`.
 *
 * Phase 1: [solveSnap] is real and unit-tested; fingertip input (Spatial SDK hand joints) is TODO(phase 2): feed
 * [onCorner] with WORLD fingertip positions once a hold is detected.
 *
 * Threading: [onCorner] from the main thread; [latest] from any thread.
 */
class TouchTracker(private val modelCorners: Pair<Vec3, Vec3>) : ObjectTracker {
  override val source = TrackSource.TOUCH

  @Volatile
  override var latest: TrackResult? = null
    private set

  private var first: Vec3? = null

  /** Feed a held fingertip position (WORLD, metres). Returns the solve after the second corner, else null. */
  fun onCorner(p: Vec3, nowNs: Long): SnapResult? {
    val a = first
    if (a == null) {
      first = p
      return null
    }
    if ((p - a).length() < 0.08f) return null // MIN_APART_M in register.ts
    first = null
    val r = solveSnap(modelCorners.first, modelCorners.second, a, p)
    if (r.plausible) latest = TrackResult(true, r.worldFromAssembly, null, source, nowNs)
    return r
  }

  /** Touch registration does not use camera frames; returns the held pose. */
  override fun onFrame(frame: CameraFrame): TrackResult = latest ?: TrackResult.none(frame.timestampNs)

  override fun reset() {
    first = null
    latest = null
  }

  companion object {
    /** Angle of a horizontal vector, three.js rotateY convention (rotateY(a) turns (1,0,0) to angle a). */
    private fun angleOf(x: Float, z: Float) = atan2(-z, x)

    /**
     * The ASSEMBLY pose that puts model points [a] (front-left) and [b] (front-right) on world points [p] and [q]:
     * yaw from the two directions, position from the midpoints, height so the corners' height matches.
     */
    fun solveSnap(a: Vec3, b: Vec3, p: Vec3, q: Vec3): SnapResult {
      val yaw = angleOf(q.x - p.x, q.z - p.z) - angleOf(b.x - a.x, b.z - a.z)
      val midL = (a + b) * 0.5f
      val midW = (p + q) * 0.5f
      val rot = Quat.yaw(yaw)
      val turned = rot.rotate(Vec3(midL.x, 0f, midL.z))
      val pos = Vec3(midW.x - turned.x, midW.y - midL.y, midW.z - turned.z)
      return SnapResult(Pose3(pos, rot), yaw, hypot(q.x - p.x, q.z - p.z), hypot(b.x - a.x, b.z - a.z))
    }
  }
}
