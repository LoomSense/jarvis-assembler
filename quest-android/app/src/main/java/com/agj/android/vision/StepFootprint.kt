package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.guide.Guide
import com.agj.android.guide.Manifest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The outline of the assembly as it stands on the work surface at one step, from the app's own guide model: what the
 * markerless detector ([PanelPose]) looks for instead of the fixed 380 × 130 × 16 mm drawer front. Same definition as
 * tools/markerless/footprint.py (the Python reference; StepFootprintTest pins the two against
 * `step_footprints_ref.json`). Pure Kotlin, no OpenCV.
 *
 * Frames (all right-handed, metres):
 *  - ASSEMBLY: the GLB root node, glTF Y-up; `Guide.parts[node].positions` are in it (each part at its final place).
 *  - STEP TABLE: the guide's table for the step, Y-up; `GuideStep.tableFromAssembly` (the turned pose, standing on
 *    the table). Not the detector's Z-up TABLE of [PanelFrames].
 *  - FOOTPRINT (the detector's object frame; for the drawer front alone it is [PanelFrames]' PANEL): origin = the
 *    centre of the bounding rectangle ON the resting plane, +Z = up (STEP TABLE +Y), +X along the long side:
 *        long side along STEP TABLE X (or square):  +X_fp = +X_table, +Y_fp = −Z_table
 *        long side along STEP TABLE Z:              +X_fp = +Z_table, +Y_fp = +X_table
 *    Both are right-handed (+Y_fp = Z_fp × X_fp). The detector's 180° ambiguity is the sign of +X_fp.
 *
 * What the detector is given per step is [StepFootprints.target], not the whole outline: the detector finds a flat
 * white rectangle seen from above, and that is only measured for a panel lying flat (tools/markerless/FRONT_PANEL.md
 * "All steps": the turned-over box is not a rectangle of one height from above and is NOT tracked). So the target
 * is the outline's SLAB, a part that rests on the plane, spans the whole outline and is thin; a step without one
 * has no target and the tracker stays off.
 */

/**
 * One step's outline.
 *
 * @property lengthM the long side of the bounding rectangle on the surface. @property widthM the short side.
 * @property heightM how far the parts rise above the resting plane.
 * @property assemblyFromFootprint ASSEMBLY ← FOOTPRINT.
 * @property nodes the part nodes the outline is made of (hardware is ignored).
 */
data class StepFootprint(
    val step: Int,
    val lengthM: Double,
    val widthM: Double,
    val heightM: Double,
    val assemblyFromFootprint: Pose3,
    val nodes: List<String>,
)

object StepFootprints {
  /** A rotation entry this close to −1, 0 or 1 counts as a quarter or half turn (`AXIS_EPS` in the reference). */
  const val AXIS_EPS = 1e-3

  /** A slab's underside is this close to the resting plane (`SLAB_REST_M` in the reference). */
  const val SLAB_REST_M = 0.001

  /** … each of its sides this close to the whole outline's (`SLAB_SPAN_M`). */
  const val SLAB_SPAN_M = 0.002

  /** … and it is at most this thick (`SLAB_MAX_M`; measured: the 16 mm drawer front). */
  const val SLAB_MAX_M = 0.030

  /** STEP TABLE ← FOOTPRINT rotations, row-major: the columns are the footprint axes in the table frame. */
  private val LONG_X: Quat = Frames.matToQuat(arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, -1.0, 0.0)))
  private val LONG_Z: Quat = Frames.matToQuat(arrayOf(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(1.0, 0.0, 0.0)))

  /**
   * The outline at [step]: the manifest step's `visible` nodes named `part_*` (hardware does not shape it), minus the
   * ones that are `moving` in the step (what stands there when the step starts; all visible parts when that leaves
   * nothing), or with [done] every visible part (the end of the step). Their vertices, at their final place, are
   * mapped into the STEP TABLE frame and boxed: the long and short side on the surface and the height.
   *
   * Null when the step is unknown, shows no part with geometry, or its resting pose is not a quarter or half turn
   * (then the axis-aligned box is not the oriented bounding rectangle: do not track with it).
   */
  fun of(guide: Guide, manifest: Manifest, step: Int, done: Boolean = false): StepFootprint? {
    val ms = manifest.step(step) ?: return null
    val gs = guide.step(step) ?: return null
    val visible = ms.visible.filter { it.startsWith("part_") && (guide.parts[it]?.vertexCount ?: 0) > 0 }
    val nodes = if (done) visible else visible.filter { it !in ms.moving }.ifEmpty { visible }
    if (nodes.isEmpty()) return null
    val ta = gs.tableFromAssembly
    val r = rotation(ta.q)
    if (!isAxisAligned(r)) return null
    val (lo, hi) = box(guide, nodes, ta)
    val ex = hi[0] - lo[0]
    val ez = hi[2] - lo[2]
    val longX = ex >= ez
    val tableFromFootprint = Pose3(Vec3(((lo[0] + hi[0]) / 2).toFloat(), lo[1].toFloat(), ((lo[2] + hi[2]) / 2).toFloat()), if (longX) LONG_X else LONG_Z)
    return StepFootprint(step, max(ex, ez), min(ex, ez), hi[1] - lo[1], ta.inverse() * tableFromFootprint, nodes)
  }

  /** The box of every vertex of [nodes] in the STEP TABLE frame [ta] (TABLE ← ASSEMBLY): lo and hi = (x, y up, z). */
  private fun box(guide: Guide, nodes: List<String>, ta: Pose3): Pair<DoubleArray, DoubleArray> {
    val r = rotation(ta.q)
    val lo = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
    val hi = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
    val t = doubleArrayOf(ta.t.x.toDouble(), ta.t.y.toDouble(), ta.t.z.toDouble())
    for (node in nodes) {
      val p = guide.parts[node]!!.positions
      for (i in p.indices step 3) {
        for (k in 0 until 3) {
          val v = r[3 * k] * p[i] + r[3 * k + 1] * p[i + 1] + r[3 * k + 2] * p[i + 2] + t[k]
          lo[k] = min(lo[k], v)
          hi[k] = max(hi[k], v)
        }
      }
    }
    return lo to hi
  }

  /**
   * What the markerless detector looks for at [step] (`target` in the reference), or null = leave the tracker off:
   * the SLAB of the outline the step starts with ([of] with `done = false`), i.e. a part of it that rests on the
   * plane (within [SLAB_REST_M]), spans the whole rectangle (both sides within [SLAB_SPAN_M]) and is at most
   * [SLAB_MAX_M] thick. The rectangle and the FOOTPRINT frame are the outline's; `topM` is the slab's top face.
   * `fillHull` when parts stand on the slab at some time in the step (the finished step is taller than the slab).
   * For the drawer: the front panel in steps 1–3 (hull in 2 and 3), nothing from step 4 (turned over).
   */
  fun target(guide: Guide, manifest: Manifest, step: Int): PanelTarget? {
    val base = of(guide, manifest, step, done = false) ?: return null
    val ta = guide.step(step)?.tableFromAssembly ?: return null
    val (lo, hi) = box(guide, base.nodes, ta)
    for (node in base.nodes) {
      val (nlo, nhi) = box(guide, listOf(node), ta)
      val top = nhi[1] - lo[1]
      if (abs(nlo[1] - lo[1]) <= SLAB_REST_M && top <= SLAB_MAX_M &&
          abs((nhi[0] - nlo[0]) - (hi[0] - lo[0])) <= SLAB_SPAN_M && abs((nhi[2] - nlo[2]) - (hi[2] - lo[2])) <= SLAB_SPAN_M) {
        val done = of(guide, manifest, step, done = true)
        // sizes to 0.1 mm: the drawer front comes out as exactly 0.380 × 0.130 × 0.016, the numbers of [PanelParams]
        fun tenthMm(v: Double) = Math.round(v * 1e4) / 1e4
        // RECESS-CUE: the slab's grooves and holes, for the detector's 180° choice (null when it has none)
        val slab = guide.parts[node]!!
        val recess = RecessCue.map(slab.positions, slab.indices, base.assemblyFromFootprint, tenthMm(base.lengthM), tenthMm(base.widthM), tenthMm(top))
        return PanelTarget(tenthMm(base.lengthM), tenthMm(base.widthM), tenthMm(top), done != null && done.heightM > top + SLAB_REST_M, base.assemblyFromFootprint, node, recess)
      }
    }
    return null
  }

  /** True when [q] is a quarter or half turn about the axes (every matrix entry −1, 0 or 1 within [AXIS_EPS]). */
  fun isAxisAligned(q: Quat): Boolean = isAxisAligned(rotation(q))

  private fun isAxisAligned(r: DoubleArray): Boolean = r.all { min(abs(it), abs(abs(it) - 1)) < AXIS_EPS }

  /** Rotation matrix of [q], 3 × 3 row-major. */
  private fun rotation(q: Quat): DoubleArray {
    val x = q.x.toDouble()
    val y = q.y.toDouble()
    val z = q.z.toDouble()
    val w = q.w.toDouble()
    return doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
        2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
        2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
    )
  }
}
