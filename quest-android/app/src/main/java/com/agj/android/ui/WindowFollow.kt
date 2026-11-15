package com.agj.android.ui

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the step window goes (XR_NATIVE_SPEC §2.3, §2.6), pure Kotlin. Two placements, chosen by how steeply the
 * wearer looks down at the object (the line head → object, not the head's orientation: never head-locked):
 *  - UI-FLOOR, steep (seated or crouching over parts on the floor, standing over a table): just beyond the object,
 *    straight over its far edge in view, [FLOOR_DIST] m from the eyes, like a tablet propped up behind the work. Its
 *    lower edge stays [FLOOR_GAP] above the object's highest point in view, so it never covers the object, and the
 *    hands (which come from the near side) never cross it. [Placement.facingEyes] turns it to the eyes.
 *  - shallow (a table at arm's length): on the user's right of the object, 0.8 m from the head, 30° from the object's
 *    direction (its inner edge then stays 10° clear of the work); centre at the object's top + 0.12 m, clamped to
 *    10°–20° below eye level, higher only when it would cover the object in view.
 *  - follows the object with a critically damped spring (response 0.6 s) and a 3 cm dead band (no jitter).
 *
 * Threading: one thread (the render tick).
 */
class WindowFollow(
    private val response: Float = 0.6f,
    private val deadBand: Float = 0.03f,
    private val halfWidth: Float = STEP_PANEL_W / 2,
    private val halfHeight: Float = STEP_PANEL_H / 2,
) {
  var position: Vec3? = null
    private set

  private var velocity = Vec3.ZERO
  private var goal: Vec3? = null
  /** UI-FLOOR: which placement is in use (null = not decided yet); switches with hysteresis, see [target]. */
  private var steep: Boolean? = null

  companion object {
    /** Horizontal distance head → window centre (§2.3: 0.75–0.95 m from the eyes). */
    const val DIST = 0.80f
    /** Bearing of the window centre from the object's direction, seen from the head: 30°. */
    const val BEARING = 0.5236f
    /** Object margin (§2.3): the window keeps this many metres from the object's box in view. */
    const val MARGIN = 0.08f

    // UI-FLOOR
    /** Eyes → window centre when the wearer looks down steeply at the work (§2.3 allows 0.75–0.95 m). */
    const val FLOOR_DIST = 0.80f
    /** The window's lower edge stays this far (rad, 3°) above the object's highest point in view. */
    const val FLOOR_GAP = 0.0524f
    /** The window centre is never higher than this below the horizon (rad, 8°). */
    const val FLOOR_TOP = 0.14f
    /** Depression of the line head → object (rad) at which the floor placement starts (40°) and ends (30°). */
    const val STEEP_ON = 0.698f
    const val STEEP_OFF = 0.524f
    /** First placement, no history: steep from 35°. */
    const val STEEP_FIRST = 0.611f
  }

  /**
   * UI-FLOOR: the window just beyond the object, seen from [head]: on the vertical plane through the head and the
   * object, [FLOOR_DIST] from the eyes, its lower edge [FLOOR_GAP] above the far top corner of the object's box (the
   * highest point of the object in view, whatever its yaw).
   */
  private fun floorTarget(objectTop: Vec3, objectHalfWidth: Float, head: Vec3, dx: Float, dz: Float, d: Float): Vec3 {
    val reach = d + objectHalfWidth * 1.4143f
    val top = atan2(objectTop.y - head.y, reach)
    val el = (top + FLOOR_GAP + atan(halfHeight / FLOOR_DIST)).coerceAtMost(-FLOOR_TOP)
    val flat = FLOOR_DIST * cos(el)
    return Vec3(head.x + dx / d * flat, head.y + FLOOR_DIST * sin(el), head.z + dz / d * flat)
  }

  /** Target for an object at [objectTop] (top centre, WORLD) seen from [head]; [objectHalfWidth] metres. */
  fun target(objectTop: Vec3, objectHalfWidth: Float, head: Vec3): Vec3 {
    val dx = objectTop.x - head.x
    val dz = objectTop.z - head.z
    val d = hypot(dx, dz).coerceAtLeast(0.2f)
    // UI-FLOOR: looking down steeply at the work → beyond the object, facing the eyes. The switch has hysteresis
    // (on at 40°, off at 30°), so leaning in and out over a table does not flip the window back and forth.
    val depression = atan2(head.y - objectTop.y, d)
    val s = when (steep) {
      null -> depression >= STEEP_FIRST
      true -> depression > STEEP_OFF
      false -> depression >= STEEP_ON
    }
    steep = s
    if (s) return floorTarget(objectTop, objectHalfWidth, head, dx, dz, d)
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

  /** Stay where it is now: the spring's goal is the current place and it has no speed left. */
  fun rest() {
    goal = position
    velocity = Vec3.ZERO
  }

  fun reset() {
    position = null
    goal = null
    velocity = Vec3.ZERO
    steep = null
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
    /**
     * UI-FLOOR: when only the wearer moved, the window stays put while they move. Once they have been still for
     * [stillS] in the new posture it takes its new place: not at all when that is within [headSlack] m, with the
     * spring up to [headJump] m (a short glide), with the fade beyond.
     */
    private val headJump: Float = 0.15f,
    private val headSlack: Float = 0.05f,
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
  private var placedObject: Vec3? = null
  /** UI-FLOOR: the target last changed because the wearer moved (not the object). */
  private var byHead = false

  /** [WindowFollow.target] from a latched head position: the window is placed, not dragged along by small head moves. */
  fun target(objectTop: Vec3, objectHalfWidth: Float, head: Vec3): Vec3 {
    val latched = placedHead?.takeIf { (it - head).length() <= headStep }
    val h = latched ?: head.also { placedHead = it }
    val o = placedObject
    if (o == null || (objectTop - o).length() > 0.03f) {
      placedObject = objectTop // the object moved: the window follows it (spring, or fade for a jump)
      byHead = false
    } else if (latched == null) byHead = true
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
        val off = (target - pos).length()
        if (off > jump || (byHead && off > headJump)) {
          if (still >= stillS) {
            pending = target
            phase = Phase.FADE_OUT
            byHead = false
          }
          return pos
        }
        if (byHead) {
          // UI-FLOOR: the wearer shifted: the window is placed, it does not swim after the head; it re-seats once they settle
          if (off <= headSlack || still < stillS) return pos.also { follow.rest() }
          byHead = false
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
    placedObject = null
    byHead = false
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

  // ---- UI-FLOOR ----

  /** The step window never lies flatter than this (rad, 80°): right under the head it still reads as a window. */
  const val MAX_TILT = 1.396f

  /**
   * [facing] with the lean taken from the geometry: the readable side looks straight at the eyes at [head], like a
   * tablet propped up on the floor (a window far below eye level otherwise shows itself foreshortened). Never less
   * than [WINDOW_TILT], so a window near eye level keeps its reading lean.
   */
  fun facingEyes(pos: Vec3, head: Vec3): Pose3 =
      facing(pos, head, atan2(head.y - pos.y, hypot(head.x - pos.x, head.z - pos.z)).coerceIn(WINDOW_TILT, MAX_TILT))

  /**
   * Where a coach card goes when it is first placed: 0.8 m ahead and 15° below eye level as before (§5.4), and when
   * the wearer is already looking down (at parts on the floor) 12° above the gaze, no lower than 45°: in view, clear
   * of the spot being looked at.
   */
  fun coachCard(head: Pose3): Vec3 {
    val f = head.q.rotate(Vec3(0f, 0f, -1f))
    val el = (asin(f.y.coerceIn(-1f, 1f)) + 0.21f).coerceIn(-0.785f, -0.262f)
    return if (el >= -0.262f) ahead(head, 0.8f, 0.21f) else ahead(head, 0.8f * cos(el), 0.8f * sin(-el))
  }

  /** Gaze pitch (rad, from the horizon) where a label's "up" starts to leave world up (25°) and where it is the head's up (50°). */
  const val UP_BLEND_FROM = 0.436f
  const val UP_BLEND_TO = 0.873f

  /** 0 = labels stand upright in the world, 1 = upright in the view; smooth in the gaze pitch (no spin near the horizon). */
  fun upBlend(head: Pose3): Float {
    val f = head.q.rotate(Vec3(0f, 0f, -1f))
    val pitch = abs(asin(f.y.coerceIn(-1f, 1f)))
    val t = ((pitch - UP_BLEND_FROM) / (UP_BLEND_TO - UP_BLEND_FROM)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  /**
   * WORLD ← PANEL for a label, pill or badge at [pos] (on a part, at the hand): the readable side (+Z) looks straight
   * at the eyes, and "up" (+Y) is the head's up when the wearer looks down steeply, world up when the gaze is near
   * the horizon ([upBlend]), projected into the panel. Seen from above, a pill turned about the vertical only reads
   * sideways and foreshortened (it faces the point under the head); this one reads upright in the view.
   */
  fun billboard(pos: Vec3, head: Pose3): Pose3 {
    val to = head.t - pos
    val len = to.length()
    val z = if (len < 1e-4f) head.q.rotate(Vec3(0f, 0f, 1f)) else to * (1f / len)
    val k = upBlend(head)
    val headUp = head.q.rotate(Vec3(0f, 1f, 0f))
    val up = Vec3(0f, 1f, 0f) * (1f - k) + headUp * k
    // "up" projected into the panel; when it points along the line of sight, the head's up, then its forward
    var y = up - z * up.dot(z)
    if (y.length() < 1e-3f) y = headUp - z * headUp.dot(z)
    if (y.length() < 1e-3f) y = head.q.rotate(Vec3(0f, 0f, -1f)).let { it - z * it.dot(z) }
    y = y * (1f / y.length())
    return Pose3(pos, basis(y.cross(z), y, z))
  }

  /** The rotation whose columns are the orthonormal, right-handed axes [x], [y], [z]. */
  internal fun basis(x: Vec3, y: Vec3, z: Vec3): Quat {
    val tr = x.x + y.y + z.z
    return when {
      tr > 0f -> sqrt(tr + 1f).let { s -> Quat((y.z - z.y) / (2f * s), (z.x - x.z) / (2f * s), (x.y - y.x) / (2f * s), s / 2f) }
      x.x > y.y && x.x > z.z ->
          sqrt(1f + x.x - y.y - z.z).let { s -> Quat(s / 2f, (y.x + x.y) / (2f * s), (z.x + x.z) / (2f * s), (y.z - z.y) / (2f * s)) }
      y.y > z.z ->
          sqrt(1f + y.y - x.x - z.z).let { s -> Quat((y.x + x.y) / (2f * s), s / 2f, (z.y + y.z) / (2f * s), (z.x - x.z) / (2f * s)) }
      else -> sqrt(1f + z.z - x.x - y.y).let { s -> Quat((z.x + x.z) / (2f * s), (z.y + y.z) / (2f * s), s / 2f, (x.y - y.x) / (2f * s)) }
    }.normalized()
  }

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
