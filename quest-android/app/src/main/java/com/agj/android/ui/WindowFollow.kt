package com.agj.android.ui

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where the step window goes (XR_NATIVE_SPEC §2.3, §2.6), pure Kotlin:
 *  - on the user's right of the tracked object (default right-handed, parts pile on the left), 0.8 m from the head,
 *    30° from the object's direction (its inner edge then stays 10° clear of the work); centre at the object's
 *    top + 0.12 m, clamped to 10°–20° below eye level, higher only when it would cover the object in view;
 *  - follows the object with a critically damped spring (response 0.6 s) and a 3 cm dead band (no jitter);
 *  - never head-locked: the head pose is only used to choose the side and the facing, both re-evaluated slowly.
 *
 * Threading: one thread (the render tick).
 */
class WindowFollow(
    private val response: Float = 0.6f,
    private val deadBand: Float = 0.03f,
    private val halfWidth: Float = 0.26f,
    /** Half the panel's height: status pill row above the window, toolbar ornament below it. */
    private val halfHeight: Float = 0.21f,
) {
  var position: Vec3? = null
    private set

  private var velocity = Vec3.ZERO
  private var goal: Vec3? = null

  companion object {
    /** Horizontal distance head → window centre (§2.3: 0.75–0.95 m from the eyes). */
    const val DIST = 0.80f
    /** Bearing of the window centre from the object's direction, seen from the head: 30°. */
    const val BEARING = 0.5236f
    /** Object margin (§2.3): the window keeps this many metres from the object's box in view. */
    const val MARGIN = 0.08f
  }

  /** Target for an object at [objectTop] (top centre, WORLD) seen from [head]; [objectHalfWidth] metres. */
  fun target(objectTop: Vec3, objectHalfWidth: Float, head: Vec3): Vec3 {
    val dx = objectTop.x - head.x
    val dz = objectTop.z - head.z
    val d = hypot(dx, dz).coerceAtLeast(0.2f)
    // the user's right, horizontally, as seen looking at the object
    val right = Vec3(-dz / d, 0f, dx / d)
    // Window zone (§2.3), not beside a wide near object (that was 45–52° off the work): [DIST] m from the head,
    // [BEARING] to the right of the object's direction. 30° is the most the zone allows and the least that keeps
    // the window's inner edge 10° clear of the work (the window is 36° wide at this distance).
    val flat = (Vec3(dx / d, 0f, dz / d) * cos(BEARING) + right * sin(BEARING)) * DIST
    // 10°–20° below eye level at the window's distance
    val yMax = head.y - DIST * 0.176f // tan 10°
    val yMin = head.y - DIST * 0.364f // tan 20°
    var y = (objectTop.y + 0.12f).coerceIn(yMin, yMax)
    // A near, wide object is still under the window's inner part in view: the window's lowest edge (the toolbar)
    // then stays [MARGIN] above the top of the object's box (seen from the head its far top corner is the highest
    // point), up to just under eye level.
    val inner = BEARING - atan(halfWidth / DIST)
    val beside = inner - atan2(objectHalfWidth, (d - objectHalfWidth).coerceAtLeast(0.05f)) >= atan(MARGIN / DIST)
    if (!beside) {
      val reach = if (objectTop.y < head.y) hypot(d + objectHalfWidth, objectHalfWidth) else (d - objectHalfWidth).coerceAtLeast(0.1f)
      val clear = head.y + DIST * (objectTop.y - head.y) / reach + MARGIN + halfHeight
      y = y.coerceAtLeast(clear.coerceAtMost(head.y - DIST * 0.035f)) // tan 2°
    }
    return Vec3(head.x + flat.x, y, head.z + flat.z)
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
 * Fade-relocate on top of [WindowFollow] (XR_NATIVE_SPEC §2.6 rules 5–6, checklist 3 and 32): small moves (a slid
 * drawer, ≤ [jump] m) follow with the spring; a bigger jump (the user walked round, the object was moved far, the
 * side rule changed) never slides across the work zone: it waits until the target has been still for [stillS], fades
 * out in [fadeOutS], snaps, and fades in over [fadeInS]. While [update]'s hold flag is set (a turn step: the object
 * rotates) the window does not move at all. Pure, one thread.
 */
class WindowRelocator(
    private val follow: WindowFollow = WindowFollow(),
    private val jump: Float = 0.25f,
    private val stillS: Float = 1.0f,
    private val fadeOutS: Float = 0.15f,
    private val fadeInS: Float = 0.25f,
    /** The head position used for the placement is read again only after the wearer moved this far (m). */
    private val headStep: Float = 0.10f,
) {
  enum class Phase { FOLLOW, FADE_OUT, FADE_IN }

  var phase = Phase.FOLLOW
    private set
  var alpha = 1f
    private set
  /** Number of fade-relocations so far (logged). */
  var relocations = 0
    private set

  private var lastTarget: Vec3? = null
  private var still = 0f
  private var pending: Vec3? = null

  val position: Vec3?
    get() = follow.position

  private var placedHead: Vec3? = null

  /** [WindowFollow.target] from a latched head position: the window is placed, not dragged along by small head moves. */
  fun target(objectTop: Vec3, objectHalfWidth: Float, head: Vec3): Vec3 {
    val h = placedHead?.takeIf { (it - head).length() <= headStep } ?: head.also { placedHead = it }
    return follow.target(objectTop, objectHalfWidth, h)
  }

  /** One frame. [target] null = keep the window where it is (lost). Returns the window position. */
  fun update(target: Vec3?, dtS: Float, hold: Boolean = false): Vec3? {
    val pos = follow.position
    if (target == null) return pos
    val lt = lastTarget
    still = if (lt == null || (target - lt).length() > 0.02f) 0f else still + dtS
    lastTarget = target
    when (phase) {
      Phase.FADE_OUT -> {
        alpha = (alpha - dtS / fadeOutS).coerceAtLeast(0f)
        if (alpha <= 0f) {
          follow.reset()
          follow.update(pending ?: target, dtS)
          pending = null
          phase = Phase.FADE_IN
          relocations++
        }
        return follow.position
      }
      Phase.FADE_IN -> {
        alpha = (alpha + dtS / fadeInS).coerceAtMost(1f)
        if (alpha >= 1f) phase = Phase.FOLLOW
        return follow.position
      }
      Phase.FOLLOW -> {
        if (pos == null) return follow.update(target, dtS)
        if (hold) return pos
        if ((target - pos).length() > jump) {
          if (still >= stillS) {
            pending = target
            phase = Phase.FADE_OUT
          }
          return pos
        }
        return follow.update(target, dtS)
      }
    }
  }

  fun reset() {
    follow.reset()
    phase = Phase.FOLLOW
    alpha = 1f
    lastTarget = null
    pending = null
    still = 0f
    placedHead = null
  }
}

/**
 * Placements relative to the head, pure, in right-handed WORLD (head pose from `scene.getViewerPose().toPose3()`,
 * which mirrors the SDK's left-handed pose; the head looks down its −Z). Unit-tested in Spatial axes too
 * (FrameConventionTest: the spot is in front of the wearer, not behind).
 */
object Placement {
  /** Back tilt of the step window and its side column: top away from the wearer, 8° (§2.6 rule 3). */
  const val WINDOW_TILT = 0.14f

  /**
   * WORLD ← PANEL for a panel at [pos] turned about the vertical to face [head], leaning back by [tilt] rad (a window
   * below eye level then looks up at the wearer). Local +Z is the readable side. Without tilt its `toSdk()` is the
   * SDK's `lookRotationAroundY(panel − head)` that `facingPose` builds (WindowPlacementTest pins both).
   */
  fun facing(pos: Vec3, head: Vec3, tilt: Float = WINDOW_TILT): Pose3 =
      Pose3(pos, Quat.yaw(atan2(head.x - pos.x, head.z - pos.z)) * Quat.axisAngle(Vec3(1f, 0f, 0f), -tilt))

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
