package com.agj.android.world

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One scene surface from the room model (MRUK), already in WORLD: the corners of a plane anchor's rectangle, or
 * the top face of a volume anchor. [labels] are MRUK label names (`TABLE`, `FLOOR`, …).
 */
data class SceneSurface(val id: String, val labels: Set<String>, val corners: List<Vec3>, val source: String)

/**
 * A horizontal rectangle in WORLD: centre [center] (its height is the surface), unit horizontal axes [u] and [v],
 * half sizes [halfU] ≥ [halfV] not required.
 */
data class TableSurface(
    val id: String,
    val label: String,
    val center: Vec3,
    val u: Vec3,
    val v: Vec3,
    val halfU: Float,
    val halfV: Float,
    /** Surface height above the floor, m. */
    val heightM: Float,
    /** `plane` or `volume` (which MRUK component gave it). */
    val source: String,
) {
  val y: Float
    get() = center.y

  val area: Float
    get() = 4f * halfU * halfV

  /** (width, depth) in metres, longer side first. */
  val sizeM: Pair<Float, Float>
    get() = maxOf(2 * halfU, 2 * halfV) to minOf(2 * halfU, 2 * halfV)

  /** Is [p] above/below the rectangle (ignoring height), grown by [marginM]? */
  fun contains(p: Vec3, marginM: Float = 0f): Boolean {
    val d = p - center
    return abs(d.dot(u)) <= halfU + marginM && abs(d.dot(v)) <= halfV + marginM
  }

  /** Horizontal distance from [p] to the rectangle (0 when above it). */
  fun flatDistance(p: Vec3): Float {
    val d = p - center
    val du = (abs(d.dot(u)) - halfU).coerceAtLeast(0f)
    val dv = (abs(d.dot(v)) - halfV).coerceAtLeast(0f)
    return sqrt(du * du + dv * dv)
  }
}

/** [table] chosen (null = none) and why. */
data class TableChoice(val table: TableSurface?, val reason: String, val candidates: Int, val horizontal: Int)

/**
 * Table placement without a marker (R3/R8). Pure, unit-tested:
 *  - [rect]: scene corners → horizontal rectangle (or null when the surface is not level);
 *  - [choose]: the `TABLE`-labelled surface in front of the user (nearest), else the largest level surface 0.6–1.0 m
 *    above the floor in front of the user, else any `TABLE` within 3 m;
 *  - [place]: WORLD ← TABLE for the assembly footprint on it: centred in front of the user at reach distance,
 *    clamped so the footprint stays on the table, yaw so the assembly front (+Z, glTF) faces the user. Same frame as
 *    `Placement.fixedTableSpot` (origin on the surface), so it can replace it in `setTablePose`.
 */
object TableFinder {
  const val LEVEL_TOLERANCE_M = 0.04f
  const val MIN_AREA_M2 = 0.06f
  private val NOT_A_TABLE = setOf("FLOOR", "CEILING", "WALL_FACE", "INVISIBLE_WALL_FACE", "INNER_WALL_FACE", "DOOR_FRAME",
      "WINDOW_FRAME", "WALL_ART", "SCREEN", "GLOBAL_MESH", "BED", "LAMP", "PLANT")

  /** Horizontal rectangle through ≥ 3 corners (any order); null when they are not level within [LEVEL_TOLERANCE_M]. */
  fun rect(s: SceneSurface, floorY: Float): TableSurface? {
    val cs = s.corners
    if (cs.size < 3) return null
    val c = cs.fold(Vec3.ZERO) { a, b -> a + b } * (1f / cs.size)
    if (cs.any { abs(it.y - c.y) > LEVEL_TOLERANCE_M }) return null
    // order by angle around the centre in the horizontal plane, then take two adjacent edges
    val sorted = cs.sortedBy { atan2((it.z - c.z).toDouble(), (it.x - c.x).toDouble()) }
    val e1 = flat(sorted[1] - sorted[0])
    val e2 = flat(sorted[sorted.size - 1] - sorted[0])
    val l1 = e1.length()
    val l2 = e2.length()
    if (l1 < 1e-3f || l2 < 1e-3f) return null
    val u = e1 * (1f / l1)
    // v perpendicular to u in the horizontal plane (a rectangle's adjacent edge; Gram-Schmidt for slight skew)
    val vRaw = e2 - u * e2.dot(u)
    val vl = vRaw.length()
    if (vl < 1e-3f) return null
    val v = vRaw * (1f / vl)
    val label = s.labels.firstOrNull { it == "TABLE" } ?: s.labels.firstOrNull() ?: "UNKNOWN"
    return TableSurface(s.id, label, c, u, v, l1 / 2, vl / 2, c.y - floorY, s.source)
  }

  fun choose(surfaces: List<SceneSurface>, head: Pose3, floorY: Float): TableChoice {
    val rects = surfaces.filter { s -> s.labels.none { it in NOT_A_TABLE } }.mapNotNull { rect(it, floorY) }
    val usable = rects.filter { it.area >= MIN_AREA_M2 }
    val fwd = flatForward(head)
    fun inFront(t: TableSurface): Boolean {
      val dist = t.flatDistance(head.t)
      if (dist > 2.0f) return false
      if (dist < 0.3f) return true
      val to = flat(t.center - head.t)
      val n = to.length()
      return n < 1e-3f || to.dot(fwd) / n >= cos(Math.toRadians(60.0)).toFloat()
    }
    val tables = usable.filter { it.label == "TABLE" && it.heightM in 0.3f..1.3f }
    tables.filter(::inFront).minByOrNull { it.flatDistance(head.t) }?.let {
      return TableChoice(it, "table_in_front", usable.size, rects.size)
    }
    usable.filter { it.label != "TABLE" && it.heightM in 0.6f..1.0f && inFront(it) }.maxByOrNull { it.area }?.let {
      return TableChoice(it, "level_surface_in_front", usable.size, rects.size)
    }
    tables.filter { it.flatDistance(head.t) <= 3f }.minByOrNull { it.flatDistance(head.t) }?.let {
      return TableChoice(it, "table_nearby", usable.size, rects.size)
    }
    val reason = if (surfaces.isEmpty()) "no_surfaces" else if (usable.isEmpty()) "no_level_surface" else "none_in_front"
    return TableChoice(null, reason, usable.size, rects.size)
  }

  /**
   * WORLD ← TABLE for an assembly with a [footprintW] (along its X) × [footprintD] (along its Z) footprint on
   * [table]: [reachM] ahead of the user's head, projected on the table, clamped so the footprint (+ [marginM]) stays
   * on it (the table centre on an axis that is too short), turned to face the user.
   */
  fun place(
      table: TableSurface,
      head: Pose3,
      footprintW: Float,
      footprintD: Float,
      reachM: Float = 0.45f,
      marginM: Float = 0.03f,
  ): Pose3 {
    val fwd = flatForward(head)
    val headOn = Vec3(head.t.x, table.y, head.t.z)
    val desired = headOn + fwd * reachM
    val yaw0 = yawFacing(desired, head.t, fwd)
    val ex = Vec3(cos(yaw0), 0f, -sin(yaw0)) // assembly +X in WORLD
    val ez = Vec3(sin(yaw0), 0f, cos(yaw0)) // assembly +Z (front) in WORLD
    val hu = abs(ex.dot(table.u)) * footprintW / 2 + abs(ez.dot(table.u)) * footprintD / 2 + marginM
    val hv = abs(ex.dot(table.v)) * footprintW / 2 + abs(ez.dot(table.v)) * footprintD / 2 + marginM
    val d = desired - table.center
    fun clamp(x: Float, lim: Float) = if (lim <= 0f) 0f else x.coerceIn(-lim, lim)
    val du = clamp(d.dot(table.u), table.halfU - hu)
    val dv = clamp(d.dot(table.v), table.halfV - hv)
    val p = table.center + table.u * du + table.v * dv
    val at = Vec3(p.x, table.y, p.z)
    return Pose3(at, Quat.yaw(yawFacing(at, head.t, fwd)))
  }

  /** Yaw about +Y that turns +Z from [from] toward [head] (horizontally); the head's back direction if on top. */
  fun yawFacing(from: Vec3, head: Vec3, fwd: Vec3): Float {
    var to = flat(head - from)
    if (to.length() < 0.05f) to = fwd * -1f
    return atan2(to.x, to.z)
  }

  /** The head's forward (−Z) direction, horizontal and unit length. */
  fun flatForward(head: Pose3): Vec3 {
    val f = flat(head.q.rotate(Vec3(0f, 0f, -1f)))
    val n = f.length()
    return if (n < 1e-3f) Vec3(0f, 0f, -1f) else f * (1f / n)
  }

  private fun flat(v: Vec3) = Vec3(v.x, 0f, v.z)
}
