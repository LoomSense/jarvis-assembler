package com.agj.android.guide

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/*
 * Where the guidance goes, exactly (XR_NATIVE_SPEC §4.2–4.5, §4.10). Pure Kotlin, no Spatial SDK types: every number
 * the renderer draws from is computed here and JVM-tested against jobs/malm-drawer and jobs/knagglig
 * (GuidanceGeometryTest), which measures each one against the GLB's own geometry.
 *
 * The hole. The pipeline puts every hardware node's origin where its axis meets the surface it goes into (the head
 * seat, the lip of the hole; pipeline/blender/assemble.py draws its own ring at `REST[inst] + approach × 1.5 mm`), and
 * the manifest's `target_m` is that same point rounded to 0.1 mm. So:
 *   hole centre = the hardware node's final origin in the GLB (the pose the ghost is drawn from, so ring and ghost can
 *                 never disagree, and it is not rounded); `target_m` only when the node has no pose;
 *   hole normal = `from_dir` (the pipeline's approach vector: outward from the surface, towards the hand).
 * Measured on both jobs: 0.00 mm from the receiving surface, ≤ 0.05 mm from the hardware's axis. Before this file the
 * ring sat on the hardware's OUTER END (bounding-box centre + half its length): 27.5 mm above the surface for a cam
 * bolt, 13 mm inside the side panel for a dowel.
 *
 * All vectors are in the ASSEMBLY frame (glTF Y-up, metres) unless a name says WORLD.
 */

/** A hole lip: [center] ON the receiving surface, [normal] out of it (= `from_dir`), ring radius [radius] (m). */
data class Hole(val center: Vec3, val normal: Vec3, val radius: Float, val source: String = "node") {
  /** Where the lip ring is drawn: 0.5 mm off the surface along its normal (spec §4.4). */
  val ringCenter: Vec3
    get() = center + normal * Guidance.RING_LIFT
}

/** How the hardware is driven home (logged as `turn_sense`). */
enum class TurnSense(val wire: String) {
  /** Clockwise as seen from the head (from where the tool comes): every screw, cam bolt and cam lock tightens this way. */
  CW("cw"),
  /** No thread: pushed or tapped straight in (dowels, pins). No turning arrow. */
  NONE("none"),
}

/** The turning arrow of a screw-like action: centre on the axis, [into] = the direction the hardware advances. */
data class TurnArrow(val center: Vec3, val into: Vec3, val radius: Float)

/** "2 of 4": the action's 1-based place among the step's fasteners of the same code, and the holes still to do. */
data class Repeat(val index: Int, val of: Int, val remaining: List<GuideAction>)

/** A part's size about an axis through a point: radial reach, and how far it reaches out of / into the surface. */
data class AxisExtent(val radial: Float, val out: Float, val into: Float)

/** Where the label pill and its leader go, WORLD. [hideBand] = heights where path dots are not drawn (under the pill). */
data class LabelLayout(
    val leaderFrom: Vec3,
    val leaderLen: Float,
    val pill: Vec3,
    val leader: Boolean,
    val stacked: Boolean,
    val hideBand: ClosedFloatingPointRange<Float>?,
)

/**
 * TABLE ← ASSEMBLY while the assembly is turned over (a `turn` action), sampled from the GLB's `walkthrough` clip, so
 * the preview turns the same way round as the guide's own animation. Interpolating the two end poses instead leaves
 * the direction of a 180° flip to rounding (the two quaternions are perpendicular).
 */
class TurnTrack(val poses: List<Pose3>) {
  /** Cumulative travel of each sample, 0..1 (rotation angle plus translation, so `at` moves at an even speed). */
  private val cum: FloatArray

  /** Total rotation travelled, degrees. */
  val angleDeg: Float

  init {
    val c = FloatArray(poses.size)
    var a = 0f
    for (i in 1 until poses.size) {
      val da = Guidance.angleRad(poses[i - 1].q, poses[i].q)
      a += da
      c[i] = c[i - 1] + da * TRAVEL_RADIUS + (poses[i].t - poses[i - 1].t).length()
    }
    val total = c.lastOrNull() ?: 0f
    cum = if (total > 1e-9f) FloatArray(c.size) { c[it] / total } else FloatArray(c.size) { if (c.size > 1) it / (c.size - 1f) else 0f }
    angleDeg = Math.toDegrees(a.toDouble()).toFloat()
  }

  val start: Pose3
    get() = poses.first()

  val end: Pose3
    get() = poses.last()

  /** Pose at fraction [u] of the travel (0 = before the turn, 1 = after). */
  fun at(u: Float): Pose3 {
    if (poses.size == 1) return poses[0]
    val x = u.coerceIn(0f, 1f)
    var i = 0
    while (i < poses.size - 2 && cum[i + 1] < x) i++
    val span = cum[i + 1] - cum[i]
    val k = if (span > 1e-9f) ((x - cum[i]) / span).coerceIn(0f, 1f) else 1f
    return Pose3(poses[i].t.lerp(poses[i + 1].t, k), poses[i].q.nlerp(poses[i + 1].q, k))
  }

  companion object {
    /** Metres of travel one radian of rotation counts for (about the half size of the assemblies). */
    const val TRAVEL_RADIUS = 0.2f
  }
}

object Guidance {
  /** Ring radius = the hardware's radius about its axis + 2 mm (spec §4.4), never under 5 mm. */
  const val RING_MARGIN = 0.002f
  const val RING_LIFT = 0.0005f
  const val RING_MIN_R = 0.005f
  const val RING_MAX_R = 0.030f

  /**
   * Stroke of the unit ring mesh as a fraction of its radius (the ring entity is scaled to the hole radius):
   * 0.24 × the 5 mm minimum = the spec's 1.2 mm; larger holes get a proportionally heavier ring. Was 0.2 × a 4 mm
   * minimum (0.8 mm), and 0.12 for the ripples (0.6 mm ≈ one display pixel at 0.8 m).
   */
  const val RING_STROKE = 0.24f

  /** The spec's stroke (§4.4), metres. */
  const val RING_STROKE_MIN = 0.0012f

  /** Turning arrow (spec §4.3): radius = part radius + 10 mm in 18..40 mm, 6 mm in front of the hardware's outer end. */
  const val ARROW_MARGIN = 0.010f
  const val ARROW_MIN_R = 0.018f
  const val ARROW_MAX_R = 0.040f
  const val ARROW_LIFT = 0.006f

  /** Path: hardware and small parts come straight in over 10 cm; boards from 25 cm; the dots stop 2 cm short. */
  const val PATH_STRAIGHT = 0.10f
  const val PATH_BOARD = 0.25f
  const val PATH_GAP = 0.02f
  const val PATH_SPACING = 0.012f

  /** Label (spec §4.5): leader 9 cm, pill 28 mm high in a 32 mm panel, at most 0.32 m wide. */
  const val LABEL_LIFT = 0.09f
  const val LABEL_STACK_LIFT = 0.03f
  const val LABEL_STACK_MAX = 0.14f
  const val LABEL_PANEL_H = 0.032f
  const val LABEL_HALF_W = 0.16f

  /**
   * The pill's text is laid out at 1 dp = 1 mm with the name at 17 sp (ui/Panels.kt `TextSize.labelName`); the step
   * window's body is 17.5 sp. Scaling the pill by 17.5 / 17 makes the name the window's body size at 0.8 m.
   */
  const val LABEL_NAME_MM = 17f
  const val BODY_MM = 17.5f
  const val LABEL_TEXT_SCALE = BODY_MM / LABEL_NAME_MM

  /** Hardware kinds (manifest `hardware[].kind`) without a thread: straight dotted line, no turning arrow (§4.2). */
  val PUSH_KINDS = setOf("dowel", "pin", "nail", "plug", "peg", "cap", "rivet")

  private val UP = Vec3(0f, 1f, 0f)

  // ---------------------------------------------------------------- hole

  /** Does this action go into a hole (screw-like or pressed in)? */
  fun hasHole(a: GuideAction): Boolean = a.kind.twists || a.kind == ActionKind.PRESS

  /**
   * The hole of a fasten / tighten / press action: centre = the hardware node's final origin (else `target_m`), on the
   * receiving surface; normal = `from_dir`; radius = the hardware's reach about that axis + 2 mm. Null for other kinds
   * or without a direction or a position.
   */
  fun holeOf(a: GuideAction): Hole? {
    if (!hasHole(a)) return null
    val dir = a.fromDir?.takeIf { it.length() > 1e-6f } ?: return null
    val n = Shapes.norm(dir)
    val c = a.finalPose?.t ?: a.target ?: return null
    val radial = a.geometry?.let { extentAbout(it, c, n).radial } ?: (RING_MIN_R - RING_MARGIN)
    return Hole(c, n, (radial + RING_MARGIN).coerceIn(RING_MIN_R, RING_MAX_R), if (a.finalPose != null) "node" else "target_m")
  }

  /** The hardware's own axis from its final pose (the pipeline models hardware along local +Y), pointing into the hole. */
  fun hardwareAxis(a: GuideAction): Vec3? {
    val q = a.finalPose?.q ?: return null
    val axis = q.rotate(UP)
    val dir = a.fromDir ?: return axis
    return if (axis.dot(dir) > 0f) axis * -1f else axis
  }

  /** [geo]'s reach about the line through [center] along unit [normal]. */
  fun extentAbout(geo: PartGeometry, center: Vec3, normal: Vec3): AxisExtent {
    var radial = 0f
    var out = 0f
    var into = 0f
    val p = geo.positions
    for (i in p.indices step 3) {
      val dx = p[i] - center.x
      val dy = p[i + 1] - center.y
      val dz = p[i + 2] - center.z
      val along = dx * normal.x + dy * normal.y + dz * normal.z
      val rx = dx - along * normal.x
      val ry = dy - along * normal.y
      val rz = dz - along * normal.z
      val r2 = rx * rx + ry * ry + rz * rz
      if (r2 > radial) radial = r2
      if (along > out) out = along
      if (-along > into) into = -along
    }
    return AxisExtent(sqrt(radial), out, into)
  }

  // ---------------------------------------------------------------- turning

  /** Screws, cam bolts and cam locks turn clockwise seen from the head; dowels and pins go straight in. */
  fun turnSense(a: GuideAction): TurnSense =
      when {
        a.kind == ActionKind.TIGHTEN -> TurnSense.CW
        a.kind == ActionKind.FASTEN && a.hardwareKind?.lowercase() !in PUSH_KINDS -> TurnSense.CW
        else -> TurnSense.NONE
      }

  /** The turning arrow, or null when the hardware is not turned. */
  fun turnArrowOf(a: GuideAction): TurnArrow? {
    if (turnSense(a) != TurnSense.CW) return null
    val h = holeOf(a) ?: return null
    val ext = a.geometry?.let { extentAbout(it, h.center, h.normal) }
    val radius = ((ext?.radial ?: 0.004f) + ARROW_MARGIN).coerceIn(ARROW_MIN_R, ARROW_MAX_R)
    return TurnArrow(h.center + h.normal * ((ext?.out ?: 0f) + ARROW_LIFT), h.normal * -1f, radius)
  }

  /**
   * Entity pose for [Shapes.turnArrow]: the mesh's arc runs counter-clockwise seen from its +Y, so +Y goes INTO the
   * hole and the wearer, on the head side, sees the arrowhead at the clockwise end.
   */
  fun turnArrowPose(t: TurnArrow): Pose3 = Pose3(t.center, Shapes.fromTo(UP, t.into))

  // ---------------------------------------------------------------- insertion path

  /**
   * Dots from where the part comes from (`from_dir`) towards its place, far end first. Hardware: a straight line on
   * the hole's axis from 10 cm beyond the hardware's outer end to 2 cm before it. Boards: an arc from 25 cm out
   * ([Shapes.insertionPath]); small parts straight. [up] = world up in ASSEMBLY coordinates.
   */
  fun insertionPath(a: GuideAction, up: Vec3, maxDots: Int = 48): List<Vec3> {
    val geo = a.geometry ?: return emptyList()
    val dir = a.fromDir?.takeIf { it.length() > 1e-6f } ?: return emptyList()
    val hole = holeOf(a)
    val pts =
        if (hole != null) {
          val out = extentAbout(geo, hole.center, hole.normal).out
          Shapes.insertionPath(hole.center + hole.normal * out, hole.normal, up, straight = true, PATH_SPACING, PATH_GAP)
        } else {
          val u = Shapes.norm(dir)
          val small = maxOf(geo.size.x, geo.size.y, geo.size.z) < 0.06f
          Shapes.insertionPath(geo.center + u * (geo.extentAlong(u) / 2f), u, up, straight = small, PATH_SPACING, PATH_GAP)
        }
    // Too many dots: thin them evenly, keeping both ends (never cut the end nearest the part).
    if (pts.size <= maxDots) return pts
    return List(maxDots) { pts[(it * (pts.size - 1f) / (maxDots - 1f)).toInt().coerceIn(0, pts.size - 1)] }
  }

  // ---------------------------------------------------------------- repeats

  /** The action's place among the step's fasteners of the same code (holes only); null when it has no hole or code. */
  fun repeatOf(step: GuideStep, a: GuideAction): Repeat? {
    val code = a.code ?: return null
    if (holeOf(a) == null) return null
    val same = step.guided.filter { it.code == code && holeOf(it) != null }
    val i = same.indexOfFirst { it.index == a.index }
    if (i < 0) return null
    return Repeat(i + 1, same.size, same.drop(i + 1))
  }

  /** The most dim rings any action of the guide needs at once. */
  fun maxRemaining(g: Guide): Int = g.steps.maxOfOrNull { s -> s.guided.maxOfOrNull { repeatOf(s, it)?.remaining?.size ?: 0 } ?: 0 } ?: 0

  // ---------------------------------------------------------------- label

  /** Dynamic scale (spec §2.2) × the factor that brings the name up to the window's body size. */
  fun labelScale(distM: Float): Float = (distM / 0.8f).coerceIn(0.75f, 1.5f) * LABEL_TEXT_SCALE

  /** The name's font size in degrees of visual angle at [distM]. */
  fun labelNameDeg(distM: Float): Double =
      Math.toDegrees(2.0 * kotlin.math.atan((LABEL_NAME_MM / 1000.0 * labelScale(distM)) / (2.0 * distM)))

  /** The step window's body size in degrees at its design distance (0.8 m). */
  val bodyDeg: Double = Math.toDegrees(2.0 * kotlin.math.atan((BODY_MM / 1000.0) / (2.0 * 0.8)))

  /**
   * The pill above the part, WORLD. Normally a 9 cm leader straight up from [anchor] (top centre of the ghost). When
   * the part comes from above, the dotted path runs up that same line and the pill would sit on it:
   *  - short path (hardware): the leader starts at the path's far end and is 3 cm long, so the wearer reads
   *    pill → dots → hole in one line;
   *  - long path (a board lowered from 25 cm): the pill stays near the part, the dots under it are not drawn and the
   *    white leader is dropped (the dots are the leader).
   * [pathWorld] is the insertion path, far end first.
   */
  fun labelLayout(anchor: Vec3, pathWorld: List<Vec3>, scale: Float): LabelLayout {
    val half = LABEL_PANEL_H / 2f * scale
    val lift = LABEL_LIFT * scale
    val normal = LabelLayout(anchor, lift, anchor + UP * (lift + half), leader = true, stacked = false, hideBand = null)
    if (pathWorld.size < 2) return normal
    val top = pathWorld.first()
    val near = pathWorld.last()
    val d = top - near
    val len = d.length()
    if (len < 1e-4f || d.y / len < VERTICAL_COS) return normal
    // Does the path cross the pill's place? (Within the pill's half width of the anchor's vertical, above the anchor.)
    val column = LABEL_HALF_W * scale
    val crosses = pathWorld.any { hypot(it.x - anchor.x, it.z - anchor.z) < column && it.y > anchor.y + 0.005f }
    if (!crosses) return normal
    if (top.y - anchor.y <= LABEL_STACK_MAX * scale) {
      val l = LABEL_STACK_LIFT * scale
      return LabelLayout(top, l, top + UP * (l + half), leader = true, stacked = true, hideBand = null)
    }
    val lo = anchor.y + lift
    return LabelLayout(anchor, lift, normal.pill, leader = false, stacked = false, hideBand = (lo - 0.01f)..(lo + LABEL_PANEL_H * scale + 0.01f))
  }

  /** A path is "from above" when it rises within 35° of the vertical. */
  val VERTICAL_COS: Float = kotlin.math.cos(Math.toRadians(35.0)).toFloat()

  // ---------------------------------------------------------------- math

  /** Angle between two rotations, radians. */
  fun angleRad(a: Quat, b: Quat): Float {
    val d = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w).coerceAtMost(1f)
    return 2f * acos(d)
  }

  /** Angle between two directions, degrees. */
  fun angleDeg(a: Vec3, b: Vec3): Double {
    val d = (Shapes.norm(a).dot(Shapes.norm(b))).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(d.toDouble()))
  }
}
