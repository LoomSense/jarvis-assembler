package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.TrackSource
import com.agj.android.core.Vec3
import kotlin.math.atan2
import kotlin.math.hypot

/** Result of the two-corner solve (port of `solveSnap` in client/src/xr/register.ts). */
data class SnapResult(
    /** WORLD ← PLATE (or ASSEMBLY when no plate offset is used): yaw about +Y plus translation, metres. */
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
 * Two-corner fingertip registration, the fallback when the marker is absent: the wearer touches the front-left, then
 * the front-right bottom corner of the real assembly with an index fingertip (a corner counts when the tip stays
 * within 1 cm for 0.8 s, [TipHold]). The pose is static after the snap: `source = "touch"`.
 *
 * The solve is yaw-only about world +Y, so the corners are given in a PLATE frame whose +Y is world up (register.ts
 * snaps its build plate). [plateFromAssembly] turns the result into WORLD ← ASSEMBLY: for the MALM drawer in step 1
 * the assembly lies inside face up (assembly −Z up), so PLATE ← ASSEMBLY is the step's display rotation
 * (`assemblyRotationDeg` in the manifest, owned by the guide module). [setModel] changes both per step.
 *
 * Input: [onHands] each frame with WORLD hand joints ([SpatialInput]), or [onCorner] with a held point directly.
 *
 * Threading: [onHands]/[onCorner] from one thread; [latest] from any thread.
 */
class TouchTracker(
    private var modelCorners: Pair<Vec3, Vec3>,
    private var plateFromAssembly: Pose3 = Pose3.IDENTITY,
) : ObjectTracker {
  override val source = TrackSource.TOUCH

  @Volatile
  override var latest: TrackResult? = null
    private set

  /** Called after the second corner with the solve (plausible or not). */
  var onSnap: ((SnapResult, Pose3?) -> Unit)? = null

  private var first: Vec3? = null
  private val hold = TipHold()

  /** 0 = waiting for the front-left corner, 1 = for the front-right one. */
  val corner: Int
    get() = if (first == null) 0 else 1

  /** Hold progress 0..1 for a ring at the fingertip. */
  val holdProgress: Float
    get() = hold.progress

  fun setModel(corners: Pair<Vec3, Vec3>, plateFromAssembly: Pose3 = Pose3.IDENTITY) {
    modelCorners = corners
    this.plateFromAssembly = plateFromAssembly
    reset()
  }

  /** Per frame with tracked hands. Returns the WORLD ← ASSEMBLY pose when a plausible snap completes. */
  fun onHands(f: HandsFrame): Pose3? {
    val a = first
    val p = hold.update(f) { tip -> a != null && (tip - a).length() < MIN_APART_M } ?: return null
    return onCorner(p, f.tNs)?.takeIf { it.plausible }?.let { latest?.worldFromAssembly }
  }

  /** Feed a held fingertip position (WORLD, metres). Returns the solve after the second corner, else null. */
  fun onCorner(p: Vec3, nowNs: Long): SnapResult? {
    val a = first
    if (a == null) {
      first = p
      return null
    }
    if ((p - a).length() < MIN_APART_M) return null
    first = null
    val r = solveSnap(modelCorners.first, modelCorners.second, a, p)
    val pose = if (r.plausible) r.worldFromAssembly * plateFromAssembly else null
    if (pose != null) latest = TrackResult(true, pose, null, source, nowNs)
    onSnap?.invoke(r, pose)
    return r
  }

  /** Touch registration does not use camera frames; returns the held pose. */
  override fun onFrame(frame: CameraFrame): TrackResult = latest ?: TrackResult.none(frame.timestampNs)

  override fun reset() {
    first = null
    latest = null
    hold.reset()
  }

  companion object {
    /** register.ts MIN_APART_M */
    const val MIN_APART_M = 0.08f

    /** Angle of a horizontal vector, three.js rotateY convention (rotateY(a) turns (1,0,0) to angle a). */
    private fun angleOf(x: Float, z: Float) = atan2(-z, x)

    /**
     * The PLATE pose that puts model points [a] (front-left) and [b] (front-right) on world points [p] and [q]:
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
