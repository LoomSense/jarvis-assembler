package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * BOARD-CUE: how a loose board lies, beyond its size: which face is up, which way round, and for mirror-image boards
 * (the two drawer sides) which one it is. [RecessCue] generalised from "the base part, two orientations" to "every
 * board, every way it can lie".
 *
 *  - [FaceTemplate]: one face of one board seen from above, from the board's own mesh (and the hardware that ends up
 *    inside it, e.g. the cam locks of a side): the pixels where the surface is not the plain face (a groove, a hole,
 *    a rail standing on it, a fitting), in GROUPS (connected), each with the band of plain face round it. A group is
 *    LONG (a groove, a rail: at least half the board's length) or SMALL (holes, locks).
 *  - [BoardLie]: one hypothesis = board × face up × which way round. [KindCue]: all of them for boards of one size.
 *  - [BoardCue.score]: on the detector's own rectified lightness (no second rectification), per hypothesis: how much
 *    darker the long groups are than their bands, and the small groups than theirs, at the best common small shift.
 *  - [BoardCue.decide]: two stages. The LONG features say which face is up; among the hypotheses that share that
 *    picture, the SMALL features say which end is which (and, where the pictures differ enough, which of two
 *    mirror-image boards it is). Each is decided only when the winner is clearly darker than every hypothesis that
 *    says otherwise; hypotheses whose pictures coincide stay undecided (nothing is hard-coded per board).
 *    What the real frames of 15 Nov 2026 proved and did not is in [BoardCue.decide] and [BoardCue.EDGE_NEAR_PX]:
 *    a side's face (rail up / groove up) and, rail up, which end the locks are at: yes. Left against right: NO.
 *  - [CueVote]: the tracker's rule: three agreeing results in a row.
 *
 * Pure Kotlin (no OpenCV): JVM-tested in BoardCueTest.
 */

/** What a face shows most (for the words: "with the rail up"). */
enum class FaceLook { RAIL, GROOVE, HOLES }

/**
 * One face seen from above in the board's FOOTPRINT frame (as [RecessMap]: pixel (i, j) covers
 * x = −length/2 + (i … i + 1) × [mmPerPx], y = −width/2 + (j … j + 1) × [mmPerPx]; row-major [h] × [w]).
 *
 * @property group per pixel: the feature group it belongs to; −1 = plain face (or a wide feature, lit like the face).
 * @property long per group: at least half the board's length (a groove, a rail). @property band per group: the plain
 *   face round it.
 */
class FaceTemplate(val w: Int, val h: Int, val mmPerPx: Double, val group: IntArray, val long: BooleanArray, val band: Array<BooleanArray>, val look: FaceLook,
    /** Per group: a long feature that runs within [BoardCue.EDGE_NEAR_PX] of a long edge (see [BoardCue.decide]: it proves little). */
    val edge: BooleanArray = BooleanArray(long.size)) {
  val groups: Int = long.size
  val size: IntArray = IntArray(groups).also { n -> group.forEach { if (it >= 0) n[it]++ } }
  val longPx: Int = (0 until groups).filter { long[it] }.sumOf { size[it] }
  val edgePx: Int = (0 until groups).filter { long[it] && edge[it] }.sumOf { size[it] }
  val interiorPx: Int = longPx - edgePx
  val smallGroups: Int = long.count { !it }

  /** Footprint coordinates (metres) of each group's pixel centres, and of its band's: x0, y0, x1, y1, … */
  internal val featXy: Array<FloatArray> = Array(groups) { g -> centres { group[it] == g } }
  internal val bandXy: Array<FloatArray> = Array(groups) { g -> centres { band[g][it] } }

  private fun centres(m: (Int) -> Boolean): FloatArray {
    val out = ArrayList<Float>()
    val s = mmPerPx / 1000.0
    for (j in 0 until h) for (i in 0 until w) {
      if (!m(j * w + i)) continue
      out += ((i + 0.5) * s - w * s / 2).toFloat()
      out += ((j + 0.5) * s - h * s / 2).toFloat()
    }
    return out.toFloatArray()
  }

  override fun toString(): String = "FaceTemplate(${w}x$h, $look, long=${long.count { it }} (${interiorPx} px inside, ${edgePx} px at an edge), small=$smallGroups)"
}

/** One hypothesis: [board] (index in the kind) lies with [face] (0 / 1) up, [turned] by 180° or not; [template] is what shows. */
class BoardLie(val board: Int, val face: Int, val turned: Boolean, val template: FaceTemplate) {
  val look: FaceLook get() = template.look
}

/**
 * Every hypothesis for boards of one size. [boards]: their part ids. [lies]: a face with nothing on it has none
 * (it cannot be recognised: it is what is left when nothing else is seen, and that is never claimed).
 */
class KindCue(val name: String, val boards: List<String>, val lies: List<BoardLie>) {
  /** Two lies show "the same face" for the stage-1 decision: the same look when the kind has several boards (a left and a right side have their rails on opposite faces), else the same face. */
  fun sameFace(a: BoardLie, b: BoardLie): Boolean = if (boards.size > 1) a.look == b.look else a.face == b.face
}

data class BoardCueParams(
    /** The winner's features are at least this much darker than the plain face round them (8-bit L). */
    val minScore: Double = 8.0,
    /** … and at least this much more than any hypothesis that says otherwise. */
    val minMargin: Double = 6.0,
    /** … which scores at most this fraction of the winner's. */
    val maxRatio: Double = 0.5,
    /** A long feature away from the edges (a rail) is there when it is this much darker than the face round it … */
    val minInterior: Double = 4.5,
    /** … and proven absent when at most this (then a face that only has features at its edges may be the one that is up). */
    val absent: Double = 2.0,
    /** A hypothesis with a long feature at an edge is shifted across the board by at most this (it must not slide onto the edge itself). */
    val edgeShiftPx: Int = 1,
    /** At least this fraction of a group and of its band must be inside the camera image. */
    val minSeen: Double = 0.6,
    /** The best common shift within ± this many rectified pixels is taken for each hypothesis. */
    val shiftPx: Int = 3,
)

/**
 * One rectangle's cue. [long] (the long features away from the edges) / [edge] (the long features at an edge) /
 * [small] per hypothesis of [kind] (NaN = that hypothesis has no such features or they were not in view). [look]: the face that is up, when decided. [board]: which of the kind's boards, when decided.
 * [turned]: the board's own +length axis points against the rectangle's yaw, when decided.
 */
class BoardCueResult(val kind: KindCue, val long: DoubleArray, val edge: DoubleArray, val small: DoubleArray, val best: Int, val look: FaceLook?, val board: Int?, val turned: Boolean?, val ms: Double) {
  val decided: Boolean get() = look != null
}

/** The hook of [PanelPose.findRects]: scores one found rectangle on the pass's own rectified lightness. */
fun interface RectCue {
  fun score(light: ByteArray, stride: Int, valid: BooleanArray, n: Int, x0: Double, y0: Double, s: Double, r: FoundRect): BoardCueResult?
}

/** BOARD-CUE: the scorer the detector's rectangle pass uses (set by world/FlatParts.kt when a guide is loaded; null = no cue). */
object BoardCueHub {
  @Volatile var scorer: RectCue? = null
}

object BoardCue {
  /** "Not the plain face": the surface is at least this far above or below it, metres. */
  const val MIN_STEP_M = 0.0015
  const val SUB = 2
  const val EDGE_PX = 2
  const val GAP_PX = 2
  const val BAND_PX = 3

  /** A feature is looked at where it is less than 2 × [NARROW_PX] + 1 pixels across (22.5 mm: the 18 mm rail is in). */
  const val NARROW_PX = 4
  const val MIN_GROUP_PX = 3
  const val LONG_SHARE = 0.5

  /**
   * A long feature this close to a long edge of the board (15 mm) is an EDGE feature: on the real frames of 15 Nov the
   * board's own edge (its side wall towards the camera, the smear into the floor) read as a "groove" 20–31 levels
   * dark on a face that has none, against 35–40 for the real one. A small feature this close to an END (20 mm) is
   * compared with the face beside it at the same distance from that end only.
   */
  const val EDGE_NEAR_PX = 6
  const val END_NEAR_PX = 8

  /** Hardware belongs to a board when it lies inside the board's box grown by this. */
  const val HW_GROW_M = 0.002

  /** A board's two FOOTPRINT frames. [up]: ASSEMBLY ← FOOTPRINT with face 0 up, then with face 1 up. */
  class Frames2(val lengthM: Double, val widthM: Double, val thickM: Double, val up: List<Pose3>)

  /**
   * The board's own box (its mesh [positions] in ASSEMBLY through the inverse of its [finalPose]) and the two ways
   * it can lie: FOOTPRINT = centre of the rectangle on the resting plane, +X along the long side (the node's own
   * positive axis in both), +Z up; face 0 up = the node's positive thickness axis up, face 1 up = the other.
   */
  fun frames(positions: FloatArray, finalPose: Pose3): Frames2 {
    val inv = finalPose.inverse()
    val lo = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
    val hi = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
    var i = 0
    while (i + 2 < positions.size) {
      val v = inv.transform(Vec3(positions[i], positions[i + 1], positions[i + 2]))
      val c = doubleArrayOf(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
      for (k in 0 until 3) {
        lo[k] = min(lo[k], c[k])
        hi[k] = max(hi[k], c[k])
      }
      i += 3
    }
    val ext = (0 until 3).map { hi[it] - lo[it] }
    val order = (0 until 3).sortedByDescending { ext[it] }
    val (aL, aW, aT) = order
    fun unit(axis: Int, sign: Double) = DoubleArray(3) { if (it == axis) sign else 0.0 }
    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    val ups = listOf(1.0, -1.0).map { sign ->
      val x = unit(aL, 1.0)
      val z = unit(aT, sign)
      val y = cross(z, x)
      val q = Frames.matToQuat(arrayOf(doubleArrayOf(x[0], y[0], z[0]), doubleArrayOf(x[1], y[1], z[1]), doubleArrayOf(x[2], y[2], z[2])))
      val o = DoubleArray(3) { (lo[it] + hi[it]) / 2 }
      o[aT] = if (sign > 0) lo[aT] else hi[aT]
      finalPose * Pose3(Vec3(o[0].toFloat(), o[1].toFloat(), o[2].toFloat()), q)
    }
    return Frames2(ext[aL], ext[aW], ext[aT], ups)
  }

  /** Highest surface under each sub-sample of a [w] × [h] pixel footprint ([SUB] per pixel side), footprint Z; −∞ = nothing. */
  private fun raster(positions: FloatArray, indices: IntArray, assemblyFromFootprint: Pose3, w: Int, h: Int, s: Double, z: FloatArray) {
    val gw = w * SUB
    val gh = h * SUB
    val gs = s / SUB
    val m = PanelMath.toMatrix(assemblyFromFootprint.inverse())
    val nv = positions.size / 3
    val fx = DoubleArray(nv)
    val fy = DoubleArray(nv)
    val fz = DoubleArray(nv)
    for (v in 0 until nv) {
      val x = positions[3 * v].toDouble()
      val y = positions[3 * v + 1].toDouble()
      val zz = positions[3 * v + 2].toDouble()
      fx[v] = (m[0] * x + m[1] * y + m[2] * zz + m[3] + w * s / 2) / gs
      fy[v] = (m[4] * x + m[5] * y + m[6] * zz + m[7] + h * s / 2) / gs
      fz[v] = m[8] * x + m[9] * y + m[10] * zz + m[11]
    }
    for (t in 0 until indices.size / 3) {
      val a = indices[3 * t]
      val b = indices[3 * t + 1]
      val c = indices[3 * t + 2]
      val det = (fy[b] - fy[c]) * (fx[a] - fx[c]) + (fx[c] - fx[b]) * (fy[a] - fy[c])
      if (abs(det) < 1e-9) continue
      val i0 = max(0, floor(min(fx[a], min(fx[b], fx[c])) - 0.5).toInt())
      val i1 = min(gw - 1, ceil(max(fx[a], max(fx[b], fx[c])) - 0.5).toInt())
      val j0 = max(0, floor(min(fy[a], min(fy[b], fy[c])) - 0.5).toInt())
      val j1 = min(gh - 1, ceil(max(fy[a], max(fy[b], fy[c])) - 0.5).toInt())
      for (j in j0..j1) {
        val py = j + 0.5
        for (i in i0..i1) {
          val px = i + 0.5
          val l0 = ((fy[b] - fy[c]) * (px - fx[c]) + (fx[c] - fx[b]) * (py - fy[c])) / det
          val l1 = ((fy[c] - fy[a]) * (px - fx[c]) + (fx[a] - fx[c]) * (py - fy[c])) / det
          val l2 = 1 - l0 - l1
          if (l0 < -1e-9 || l1 < -1e-9 || l2 < -1e-9) continue
          val zz = (l0 * fz[a] + l1 * fz[b] + l2 * fz[c]).toFloat()
          if (zz > z[j * gw + i]) z[j * gw + i] = zz
        }
      }
    }
  }

  /**
   * What one face shows: the board's triangles and its [hardware]'s (all in ASSEMBLY) seen from above in
   * [assemblyFromFootprint] ([frames]). Null = a plain face (nothing narrow on it).
   */
  fun face(positions: FloatArray, indices: IntArray, hardware: List<Pair<FloatArray, IntArray>>, assemblyFromFootprint: Pose3, lengthM: Double, widthM: Double, mmPerPx: Double = PanelParams().mmPerPx): FaceTemplate? {
    val s = mmPerPx / 1000.0
    val w = Math.round(lengthM / s).toInt()
    val h = Math.round(widthM / s).toInt()
    if (w < 4 * EDGE_PX || h < 4 * EDGE_PX || indices.size < 3) return null
    val gw = w * SUB
    val z = FloatArray(gw * h * SUB) { Float.NEGATIVE_INFINITY }
    raster(positions, indices, assemblyFromFootprint, w, h, s, z)
    val hz = FloatArray(z.size) { Float.NEGATIVE_INFINITY }
    for ((p, ix) in hardware) raster(p, ix, assemblyFromFootprint, w, h, s, hz)
    val finite = z.filter { it > Float.NEGATIVE_INFINITY }.sorted()
    if (finite.isEmpty()) return null
    val level = finite[finite.size / 2] // the plain face: where most of the board is
    val feat = BooleanArray(w * h)
    val raised = BooleanArray(w * h)
    for (j in EDGE_PX until h - EDGE_PX) for (i in EDGE_PX until w - EDGE_PX) {
      var f = 0
      var up = 0
      for (b in 0 until SUB) for (a in 0 until SUB) {
        val at = (j * SUB + b) * gw + i * SUB + a
        val zz = z[at]
        val hw = hz[at] > Float.NEGATIVE_INFINITY && hz[at] >= zz - 0.0005f
        if (hw || zz == Float.NEGATIVE_INFINITY || abs(zz - level) >= MIN_STEP_M) f++
        if (zz > level + MIN_STEP_M) up++
      }
      feat[j * w + i] = 2 * f >= SUB * SUB
      raised[j * w + i] = 2 * up >= SUB * SUB
    }
    return of(w, h, mmPerPx, feat, raised)
  }

  /** A template from a feature mask (row-major [h] × [w]); [raised]: the feature pixels that stand above the face. */
  fun of(w: Int, h: Int, mmPerPx: Double, feat: BooleanArray, raised: BooleanArray = BooleanArray(w * h)): FaceTemplate? {
    // narrow = what a (2 × NARROW_PX + 1) square does not fit in
    val k = NARROW_PX
    val core = BooleanArray(w * h)
    for (j in k until h - k) for (i in k until w - k) {
      var all = true
      for (b in j - k..j + k) for (a in i - k..i + k) if (!feat[b * w + a]) all = false
      core[j * w + i] = all
    }
    val narrow = BooleanArray(w * h)
    for (j in 0 until h) for (i in 0 until w) {
      if (!feat[j * w + i]) continue
      var wide = false
      for (b in max(0, j - k)..min(h - 1, j + k)) for (a in max(0, i - k)..min(w - 1, i + k)) if (core[b * w + a]) wide = true
      narrow[j * w + i] = !wide
    }
    // groups: connected narrow pixels
    val label = IntArray(w * h) { -1 }
    val sizes = ArrayList<Int>()
    val spans = ArrayList<Int>()
    val ups = ArrayList<Int>()
    val boxes = ArrayList<IntArray>()
    val stack = IntArray(w * h)
    for (start in 0 until w * h) {
      if (!narrow[start] || label[start] >= 0) continue
      val g = sizes.size
      var top = 0
      stack[top++] = start
      label[start] = g
      var n = 0
      var iMin = w
      var iMax = -1
      var jMin = h
      var jMax = -1
      var up = 0
      while (top > 0) {
        val at = stack[--top]
        n++
        val i = at % w
        val j = at / w
        iMin = min(iMin, i)
        iMax = max(iMax, i)
        jMin = min(jMin, j)
        jMax = max(jMax, j)
        if (raised[at]) up++
        for (b in max(0, j - 1)..min(h - 1, j + 1)) for (a in max(0, i - 1)..min(w - 1, i + 1)) {
          val q = b * w + a
          if (narrow[q] && label[q] < 0) {
            label[q] = g
            stack[top++] = q
          }
        }
      }
      sizes += n
      spans += iMax - iMin + 1
      ups += up
      boxes += intArrayOf(iMin, iMax, jMin, jMax)
    }
    val keep = sizes.indices.filter { sizes[it] >= MIN_GROUP_PX }
    if (keep.isEmpty()) return null
    val renum = IntArray(sizes.size) { -1 }
    keep.forEachIndexed { new, old -> renum[old] = new }
    val group = IntArray(w * h) { if (label[it] >= 0) renum[label[it]] else -1 }
    val long = BooleanArray(keep.size) { spans[keep[it]] >= LONG_SHARE * w }
    val edge = BooleanArray(keep.size) { long[it] && (boxes[keep[it]][2] < EDGE_NEAR_PX || boxes[keep[it]][3] >= h - EDGE_NEAR_PX) }
    // a small feature near an end: its band is the face beside it across the board, at the same distance from the end
    val beside = BooleanArray(keep.size) { !long[it] && (boxes[keep[it]][0] < END_NEAR_PX || boxes[keep[it]][1] >= w - END_NEAR_PX) }
    // the band of each group: plain face at a Chebyshev distance of GAP_PX + 1 … GAP_PX + BAND_PX from it, not near any feature
    val nearFeat = BooleanArray(w * h)
    for (j in 0 until h) for (i in 0 until w) {
      if (!feat[j * w + i]) continue
      for (b in max(0, j - GAP_PX)..min(h - 1, j + GAP_PX)) for (a in max(0, i - GAP_PX)..min(w - 1, i + GAP_PX)) nearFeat[b * w + a] = true
    }
    val far = GAP_PX + BAND_PX
    val band = Array(keep.size) { BooleanArray(w * h) }
    for (j in 0 until h) for (i in 0 until w) {
      val g = group[j * w + i]
      if (g < 0) continue
      if (beside[g]) {
        val box = boxes[keep[g]]
        for (b in max(EDGE_PX, box[2] - far)..min(h - 1 - EDGE_PX, box[3] + far)) if (!nearFeat[b * w + i] && (b < box[2] - GAP_PX || b > box[3] + GAP_PX)) band[g][b * w + i] = true
        continue
      }
      for (b in max(EDGE_PX, j - far)..min(h - 1 - EDGE_PX, j + far)) for (a in max(EDGE_PX, i - far)..min(w - 1 - EDGE_PX, i + far)) {
        if (!nearFeat[b * w + a]) band[g][b * w + a] = true
      }
    }
    val ok = (0 until keep.size).filter { g -> band[g].any { it } }
    if (ok.isEmpty()) return null
    val look =
        when {
          ok.any { long[it] && 2 * ups[keep[it]] >= sizes[keep[it]] } -> FaceLook.RAIL
          ok.any { long[it] } -> FaceLook.GROOVE
          else -> FaceLook.HOLES
        }
    if (ok.size == keep.size) return FaceTemplate(w, h, mmPerPx, group, long, band, look, edge)
    val re = IntArray(keep.size) { -1 }
    ok.forEachIndexed { new, old -> re[old] = new }
    return FaceTemplate(w, h, mmPerPx, IntArray(w * h) { if (group[it] >= 0) re[group[it]] else -1 }, BooleanArray(ok.size) { long[ok[it]] }, Array(ok.size) { band[ok[it]] }, look, BooleanArray(ok.size) { edge[ok[it]] })
  }

  /**
   * Every hypothesis for the boards of one kind. Per board: its mesh ([positions], [indices], ASSEMBLY, final place),
   * its [finalPose] and the meshes of all the guide's hardware ([hardware], ASSEMBLY, final place): the pieces that
   * lie wholly inside the board's box are seen with it. A face with no features gives no hypothesis.
   */
  fun kind(name: String, boards: List<BoardMesh>, hardware: List<Pair<FloatArray, IntArray>>, mmPerPx: Double = PanelParams().mmPerPx): KindCue {
    val lies = ArrayList<BoardLie>()
    boards.forEachIndexed { bi, b ->
      val fr = frames(b.positions, b.finalPose)
      fr.up.forEachIndexed { face, pose ->
        val inv = pose.inverse()
        val own = hardware.filter { (p, _) ->
          var inside = p.isNotEmpty()
          var i = 0
          while (i + 2 < p.size && inside) {
            val v = inv.transform(Vec3(p[i], p[i + 1], p[i + 2]))
            if (abs(v.x) > fr.lengthM / 2 + HW_GROW_M || abs(v.y) > fr.widthM / 2 + HW_GROW_M || v.z < -HW_GROW_M || v.z > fr.thickM + HW_GROW_M) inside = false
            i += 3
          }
          inside
        }
        val t = face(b.positions, b.indices, own, pose, fr.lengthM, fr.widthM, mmPerPx) ?: return@forEachIndexed
        lies += BoardLie(bi, face, false, t)
        lies += BoardLie(bi, face, true, t)
      }
    }
    return KindCue(name, boards.map { it.partId }, lies)
  }

  class BoardMesh(val partId: String, val positions: FloatArray, val indices: IntArray, val finalPose: Pose3)

  /**
   * Every hypothesis of [kind] scored for the rectangle at ([x], [y], [yaw]) (TABLE) on the rectified lightness
   * ([light], [stride] bytes per pixel with the lightness first, [n] × [n]; [valid] = inside the camera image;
   * pixel (0, 0) is TABLE ([x0], [y0]), [s] metres per pixel), then [decide].
   */
  fun score(kind: KindCue, light: ByteArray, stride: Int, valid: BooleanArray, n: Int, x0: Double, y0: Double, s: Double, x: Double, y: Double, yaw: Double, p: BoardCueParams = BoardCueParams()): BoardCueResult {
    val t0 = System.nanoTime()
    val long = DoubleArray(kind.lies.size) { Double.NaN }
    val edge = DoubleArray(kind.lies.size) { Double.NaN }
    val small = DoubleArray(kind.lies.size) { Double.NaN }
    kind.lies.forEachIndexed { i, lie ->
      val r = lieScore(lie.template, light, stride, valid, n, x0, y0, s, x, y, if (lie.turned) yaw + Math.PI else yaw, p)
      long[i] = r[0]
      edge[i] = r[1]
      small[i] = r[2]
    }
    return decide(kind, long, edge, small, p, (System.nanoTime() - t0) / 1e6)
  }

  /**
   * [long features away from the edges, long features at an edge, small features] of one template at one yaw: how much
   * darker than their bands; each long score at its own best shift (along and across the board), the small one at the
   * shift where the sum of all three is largest; NaN = none / not in view.
   */
  private fun lieScore(t: FaceTemplate, light: ByteArray, stride: Int, valid: BooleanArray, n: Int, x0: Double, y0: Double, s: Double, x: Double, y: Double, yaw: Double, p: BoardCueParams): DoubleArray {
    val ca = cos(yaw)
    val sa = sin(yaw)
    fun pixels(xy: FloatArray): IntArray {
      val out = IntArray(xy.size)
      for (k in 0 until xy.size / 2) {
        out[2 * k] = Math.round((x + ca * xy[2 * k] - sa * xy[2 * k + 1] - x0) / s).toInt()
        out[2 * k + 1] = Math.round((y + sa * xy[2 * k] + ca * xy[2 * k + 1] - y0) / s).toInt()
      }
      return out
    }
    val fp = Array(t.groups) { pixels(t.featXy[it]) }
    val bp = Array(t.groups) { pixels(t.bandXy[it]) }
    fun mean(px: IntArray, dx: Int, dy: Int): Double {
      var sum = 0L
      var c = 0
      for (k in 0 until px.size / 2) {
        val i = px[2 * k] + dx
        val j = px[2 * k + 1] + dy
        if (i < 0 || j < 0 || i >= n || j >= n) continue
        val at = j * n + i
        if (!valid[at]) continue
        sum += light[stride * at].toInt() and 0xff
        c++
      }
      return if (c > 0 && c >= p.minSeen * (px.size / 2)) sum.toDouble() / c else Double.NaN
    }
    var best = Double.NEGATIVE_INFINITY
    val out = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
    val across = if (t.edgePx > 0) min(p.edgeShiftPx, p.shiftPx) else p.shiftPx
    val tried = HashSet<Int>()
    for (dv in -across..across) for (du in -p.shiftPx..p.shiftPx) {
      // a shift of du pixels along the board and dv across it, as whole pixels of the picture
      val dx = Math.round(ca * du - sa * dv).toInt()
      val dy = Math.round(sa * du + ca * dv).toInt()
      if (!tried.add((dy + 64) * 128 + dx + 64)) continue
      var inNum = 0.0
      var inDen = 0
      var edNum = 0.0
      var edDen = 0
      var smallSum = 0.0
      var smallN = 0
      for (g in 0 until t.groups) {
        val f = mean(fp[g], dx, dy)
        if (f.isNaN()) continue
        val b = mean(bp[g], dx, dy)
        if (b.isNaN()) continue
        if (!t.long[g]) {
          smallSum += b - f
          smallN++
        } else if (t.edge[g]) {
          edNum += (b - f) * t.size[g]
          edDen += t.size[g]
        } else {
          inNum += (b - f) * t.size[g]
          inDen += t.size[g]
        }
      }
      if (inDen < t.interiorPx || edDen < t.edgePx || 2 * smallN < t.smallGroups) continue // a long feature or most of the small ones out of view
      val lo = if (t.interiorPx > 0) inNum / inDen else Double.NaN
      val ed = if (t.edgePx > 0) edNum / edDen else Double.NaN
      val sm = if (t.smallGroups > 0) smallSum / smallN else Double.NaN
      // each long score is its own best over the shifts (two hypotheses that show the same rail must score the same,
      // whatever their small features do); the small score is taken where everything together fits best
      if (!lo.isNaN() && (out[0].isNaN() || lo > out[0])) out[0] = lo
      if (!ed.isNaN() && (out[1].isNaN() || ed > out[1])) out[1] = ed
      val total = (if (lo.isNaN()) 0.0 else lo) + (if (ed.isNaN()) 0.0 else ed) + (if (sm.isNaN()) 0.0 else sm)
      if (total > best) {
        best = total
        out[2] = sm
      }
    }
    return out
  }

  /**
   * See the file comment. Per hypothesis of [kind]: [long] (long features away from the edges), [edge] (long features
   * at an edge), [small].
   *
   * Stage 1, the face. A long feature away from the edges (a rail, a groove in the middle) is the evidence: the face
   * whose own is there ([BoardCueParams.minInterior]) and clearly more than any other face's is up. A face that only
   * has features at its edges (a side's groove face) is up only when every face that has a feature away from the
   * edges is proven NOT to be (that feature is absent, [BoardCueParams.absent]) and its own edge feature is seen: an
   * edge line alone proves nothing (so a board whose only feature is at an edge, the back, is never decided).
   */
  fun decide(kind: KindCue, long: DoubleArray, edge: DoubleArray, small: DoubleArray, p: BoardCueParams = BoardCueParams(), ms: Double = 0.0): BoardCueResult {
    val lies = kind.lies
    val inside = BooleanArray(lies.size) { lies[it].template.interiorPx > 0 }
    // what each hypothesis is ranked by: its long features away from the edges, else those at an edge, else its small ones
    val first = DoubleArray(lies.size) { if (inside[it]) long[it] else if (lies[it].template.edgePx > 0) edge[it] else small[it] }
    fun top(ok: (Int) -> Boolean): Int {
      var b = -1
      for (i in lies.indices) if (ok(i) && !first[i].isNaN() && (b < 0 || first[i] > first[b])) b = i
      return b
    }
    fun clearlyBelow(v: Double, top: Double) = v.isNaN() || (v <= top - p.minMargin && v <= p.maxRatio * top)
    var b = top { inside[it] }
    fun none() = BoardCueResult(kind, long, edge, small, b, null, null, null, ms)
    if (b >= 0 && first[b] >= p.minInterior) {
      for (i in lies.indices) if (inside[i] && !kind.sameFace(lies[i], lies[b]) && !(first[i].isNaN() || first[i] <= p.maxRatio * first[b])) return none()
    } else {
      if (lies.indices.none { inside[it] } || lies.indices.any { inside[it] && !(first[it] <= p.absent) }) return none()
      b = top { !inside[it] }
      if (b < 0 || first[b] < p.minScore) return none()
      // The face is known (the other one's rail is not there), and that is ALL: which long edge the groove is at, and
      // with it which of two mirror-image boards this is, is NOT decided. Measured on the headset recording of
      // 15 Nov (67 sightings of a side lying groove up): the detector's rectangle ends AT the groove (the blob is cut
      // there, about 98 mm of the 110), so the "groove" this template found, 36–40 levels dark, was the board's
      // OTHER long edge, and the board came out as the left one 66 times when it was the right one.
      val one = lies.indices.filter { kind.sameFace(lies[it], lies[b]) }
      return BoardCueResult(kind, long, edge, small, b, lies[b].look,
          lies[one[0]].board.takeIf { v -> one.all { lies[it].board == v } }, lies[one[0]].turned.takeIf { v -> one.all { lies[it].turned == v } }, ms)
    }
    // stage 2: the hypotheses that show the same long features; the small ones tell them apart
    // (a hypothesis leaves the running only when its long features are CLEARLY less dark than the winner's: a weak
    // rail seen a little better one way than the other tells nothing)
    val same = lies.indices.filter { kind.sameFace(lies[it], lies[b]) && !first[it].isNaN() && (it == b || !clearlyBelow(first[it], first[b])) }
    val second = DoubleArray(lies.size) { if (lies[it].template.longPx > 0) small[it] else Double.NaN }
    if (same.isEmpty()) return none()
    var e = -1
    for (i in same) if (!second[i].isNaN() && (e < 0 || second[i] > second[e])) e = i
    fun <T> attr(of: (BoardLie) -> T): T? {
      if (same.all { of(lies[it]) == of(lies[same[0]]) }) return of(lies[same[0]])
      if (e < 0 || second[e] < p.minScore) return null
      return if (same.all { of(lies[it]) == of(lies[e]) || clearlyBelow(second[it], second[e]) }) of(lies[e]) else null
    }
    val board = attr { it.board }
    // "turned" is about the board's own length axis: with mirror-image boards it only means something once the board is known,
    // or when every board left in the running says the same
    val turned = attr { it.turned }
    return BoardCueResult(kind, long, edge, small, e.takeIf { it >= 0 && board != null } ?: b, lies[b].look, board, turned, ms)
  }
}

/**
 * The tracker's rule for one attribute: a value is taken (or changed) only after [needed] results in a row that
 * decide it and agree ([same]); a result that does not decide it breaks the row and changes nothing. Pure.
 */
class CueVote<T : Any>(val needed: Int = 3, private val same: (T, T) -> Boolean = { a, b -> a == b }) {
  var value: T? = null
    private set
  var streak = 0
    private set
  private var candidate: T? = null

  fun reset() {
    value = null
    candidate = null
    streak = 0
  }

  /** One result's reading (null = undecided). Returns [value]. */
  fun add(v: T?): T? {
    val c = candidate
    if (v == null) {
      streak = 0
      candidate = null
    } else if (c != null && same(c, v)) {
      streak++
      candidate = v
    } else {
      candidate = v
      streak = 1
    }
    if (v != null && streak >= needed) value = v
    return value
  }
}
