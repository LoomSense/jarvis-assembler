package com.agj.android.ui

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Where the step window goes (XR_NATIVE_SPEC §2.6), pure Kotlin:
 *  - beside the tracked object on the user's right (default right-handed, parts pile on the left), 0.10 m beyond the
 *    object's half-width as seen from the user; centre at the object's top + 0.12 m, clamped to 10°–20° below eye level;
 *  - follows the object with a critically damped spring (response 0.6 s) and a 3 cm dead band (no jitter);
 *  - never head-locked: the head pose is only used to choose the side and the facing, both re-evaluated slowly.
 *
 * Threading: one thread (the render tick).
 */
class WindowFollow(
    private val response: Float = 0.6f,
    private val deadBand: Float = 0.03f,
    private val halfWidth: Float = 0.26f,
) {
  var position: Vec3? = null
    private set

  private var velocity = Vec3.ZERO
  private var goal: Vec3? = null

  /** Target for an object at [objectTop] (top centre, WORLD) seen from [head]; [objectHalfWidth] metres. */
  fun target(objectTop: Vec3, objectHalfWidth: Float, head: Vec3): Vec3 {
    val dx = objectTop.x - head.x
    val dz = objectTop.z - head.z
    val d = hypot(dx, dz).coerceAtLeast(0.2f)
    // the user's right, horizontally, as seen looking at the object
    val right = Vec3(-dz / d, 0f, dx / d)
    val side = objectHalfWidth + 0.10f + halfWidth
    val p = objectTop + right * side + Vec3(0f, 0.12f, 0f)
    // 10°–20° below eye level at the window's distance
    val dist = hypot(p.x - head.x, p.z - head.z)
    val yMax = head.y - dist * 0.176f // tan 10°
    val yMin = head.y - dist * 0.364f // tan 20°
    return Vec3(p.x, p.y.coerceIn(yMin, yMax), p.z)
  }

  /** Move one step towards [target] (null = stay). Returns the new position. */
  fun update(target: Vec3?, dtS: Float): Vec3? {
    val cur = position
    if (target == null) return cur
    if (cur == null) {
      position = target
      goal = target
      return target
    }
    val g = goal
    if (g == null || (target - g).length() > deadBand) goal = target
    val gg = goal!!
    // critically damped spring: ω = 2π / response
    val w = (2.0 * Math.PI / response).toFloat()
    val x = cur - gg
    val e = exp(-w * dtS)
    val newX = (x + (velocity + x * w) * dtS) * e
    velocity = (velocity - (velocity + x * w) * (w * dtS)) * e
    position = gg + newX
    return position
  }

  fun reset() {
    position = null
    goal = null
    velocity = Vec3.ZERO
  }
}

/**
 * Placements relative to the head, pure, in right-handed WORLD (head pose from `scene.getViewerPose().toPose3()`,
 * which mirrors the SDK's left-handed pose; the head looks down its −Z). Unit-tested in Spatial axes too
 * (FrameConventionTest: the spot is in front of the wearer, not behind).
 */
object Placement {
  /** The head's forward direction, horizontal and unit length. */
  fun flatForward(head: Pose3): Vec3 {
    val f = head.q.rotate(Vec3(0f, 0f, -1f))
    val flat = Vec3(f.x, 0f, f.z)
    val n = flat.length()
    return if (n < 1e-3f) Vec3(0f, 0f, -1f) else flat * (1f / n)
  }

  /** [ahead] m in front of the head (horizontally), [right] m to its right, [down] m below it. */
  fun ahead(head: Pose3, ahead: Float, down: Float, right: Float = 0f): Vec3 {
    val f = flatForward(head)
    val r = Vec3(-f.z, 0f, f.x)
    return head.t + f * ahead + r * right + Vec3(0f, -down, 0f)
  }

  /**
   * WORLD ← TABLE for the demo spot when nothing is tracked: 0.55 m ahead of the head, 0.45 m below it, turned so
   * the assembly's front (+Z in glTF) faces the wearer.
   */
  fun fixedTableSpot(head: Pose3): Pose3 {
    val f = flatForward(head)
    val yaw = kotlin.math.atan2(-f.x, -f.z)
    return Pose3(ahead(head, 0.55f, 0.45f), Quat.yaw(yaw))
  }
}
