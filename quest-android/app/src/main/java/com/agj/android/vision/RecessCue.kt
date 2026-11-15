package com.agj.android.vision

import com.agj.android.core.Pose3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Which way round the flat base part lies: the markerless detector ([PanelPose]) finds a rectangle, and a rectangle
 * looks the same turned by 180°. The part itself is usually not symmetric: its top face has recesses (a groove, holes)
 * and they are darker than the face round them. This file scores both orientations against the part's own mesh.
 *
 *  - [RecessMap]: the part's top face rasterised in the FOOTPRINT frame ([StepFootprint]) at the detector's resolution;
 *    a pixel is a recess when the surface seen from above lies clearly below the top face (or is a through hole).
 *    Made once per target ([StepFootprints.target] → [PanelTarget.recess]); null for a part with no recess, or whose
 *    recesses look the same turned by 180°: then there is no cue and nothing changes.
 *  - [RecessCue.score]: on the detector's OWN rectified lightness image (no second rectification, no decode), for the
 *    candidate pose and for the same pose turned by 180°: mean lightness of the band round the recesses minus the mean
 *    on the recess pixels (same lighting on both), over the pixels inside the detected blob only (hands, fittings and
 *    the table do not count). The pose is a few millimetres off and a groove is two pixels wide, so the best small
 *    shift is taken for each orientation.
 *  - [RecessTurn]: the tracker's rule. Never on one frame: the cue must be confident on [RecessTurn.needed] results in
 *    a row and say the other way round before the pose is turned; after that the turned yaw is kept by continuity.
 *
 * Pure Kotlin (no OpenCV): JVM-tested in RecessCueTest.
 */

/**
 * The recesses of a part's top face in its FOOTPRINT frame. Pixel (i, j) covers
 * x = −length/2 + (i … i + 1) × [mmPerPx], y = −width/2 + (j … j + 1) × [mmPerPx]; arrays are row-major [h] × [w].
 *
 * @property recess the surface is clearly below the top face here. @property narrow the recess pixels the cue looks at:
 *   the NARROW recesses (a groove, a hole: shadowed, so dark); a wide lowered area is lit like the face and is left out.
 * @property band top-face pixels a little away from a narrow recess on every side: what it is compared with.
 * @property asymmetric narrow pixels that are not narrow after a 180° turn (what tells the two orientations apart).
 */
class RecessMap(val w: Int, val h: Int, val mmPerPx: Double, val recess: BooleanArray, val narrow: BooleanArray, val band: BooleanArray) {
  val count: Int = recess.count { it }
  val narrowCount: Int = narrow.count { it }
  val bandCount: Int = band.count { it }
  val asymmetric: Int = (0 until w * h).count { narrow[it] && !narrow[w * h - 1 - it] }

  /** Footprint coordinates (metres) of the narrow recess pixels' centres, then of the band's: x0, y0, x1, y1, … */
  internal val recessXy: FloatArray = centres(narrow)
  internal val bandXy: FloatArray = centres(band)

  private fun centres(m: BooleanArray): FloatArray {
    val out = FloatArray(2 * m.count { it })
    var k = 0
    val s = mmPerPx / 1000.0
    for (j in 0 until h) for (i in 0 until w) {
      if (!m[j * w + i]) continue
      out[k++] = ((i + 0.5) * s - w * s / 2).toFloat()
      out[k++] = ((j + 0.5) * s - h * s / 2).toFloat()
    }
    return out
  }

  /** Is the footprint point ([x], [y]) metres a recess? (outside the rectangle: no) */
  fun isRecess(x: Double, y: Double): Boolean {
    val s = mmPerPx / 1000.0
    val i = floor(x / s + w / 2.0).toInt()
    val j = floor(y / s + h / 2.0).toInt()
    return i in 0 until w && j in 0 until h && recess[j * w + i]
  }

  override fun equals(other: Any?): Boolean =
      other is RecessMap && w == other.w && h == other.h && mmPerPx == other.mmPerPx && recess.contentEquals(other.recess) && band.contentEquals(other.band)

  override fun hashCode(): Int = 31 * (31 * w + h) + recess.contentHashCode()

  override fun toString(): String = "RecessMap(${w}x$h, recess=$count, narrow=$narrowCount, band=$bandCount, asymmetric=$asymmetric)"
}

/**
 * The cue's numbers. Measured on the real headset frames of 15 Nov 2026 (the drawer front on a carpet, the `drawer`
 * job's own mesh, 80 trusted results; a one-off run, the frames are private): the right orientation scored
 * 13.2 … 38.4 (median 27.0), the turned one 0.7 … 9.8 (median 1.5, 90 % below 3.0), the difference 9.3 … 36.3.
 * With the defaults 79 of the 80 were decided, all 79 right; the other one (9.8 against 19.2) was left undecided.
 */
data class RecessCueParams(
    /** The winning orientation's recesses are at least this much darker than their surroundings (8-bit L). */
    val minScore: Double = 8.0,
    /** … and at least this much more than the other orientation's. */
    val minMargin: Double = 6.0,
    /** … and the other orientation scores at most this fraction of the winner's (a clear winner, not two dark lines). */
    val maxRatio: Double = 0.5,
    /** At least this fraction of the recess and of the band pixels must be inside the blob, in both orientations. */
    val minSeen: Double = 0.6,
    /** The best shift within ± this many rectified pixels is taken for each orientation. */
    val shiftPx: Int = 3,
)

/**
 * One result's cue. [asIs] / [turned]: how much darker the recess pixels are than their surroundings (8-bit L, best
 * shift) for the detector's yaw and for yaw + 180°; null when too few of them were inside the blob.
 * [decided]: one of them is clearly the darker ([RecessCueParams]); then [saysTurned] = the right yaw is yaw + 180°.
 */
class RecessCueResult(
    val asIs: Double?,
    val turned: Double?,
    val decided: Boolean,
    val saysTurned: Boolean,
    /** Fraction of the recess pixels that were inside the blob, per orientation. */
    val seenAsIs: Double,
    val seenTurned: Double,
    val ms: Double,
) {
  /** [asIs] − [turned]: positive = the detector's yaw fits the recesses better. */
  val margin: Double?
    get() = if (asIs != null && turned != null) asIs - turned else null
}

object RecessCue {
  /** "Clearly below the top face": at least this deep, metres. */
  const val MIN_DEPTH_M = 0.0015

  /** Sub-samples per pixel side when the mesh is rasterised; a pixel is a recess when at least half of them are. */
  const val SUB = 2

  /** Pixels this close to the rectangle's edge are never a recess or band (the mesh edge, chamfers, the smear). */
  const val EDGE_PX = 2

  /** The band: top-face pixels more than [BAND_GAP_PX] and at most [BAND_GAP_PX] + [BAND_PX] from a recess pixel. */
  const val BAND_GAP_PX = 2
  const val BAND_PX = 3

  /** Fewer asymmetric narrow pixels than this: no cue. More than this fraction recessed: not a flat face, no cue. */
  const val MIN_ASYMMETRIC_PX = 12
  const val MAX_RECESS_FRACTION = 0.5

  /** A recess is narrow where it is less than 2 × [NARROW_PX] + 1 pixels across (17.5 mm at 2.5 mm per pixel). */
  const val NARROW_PX = 3

  /**
   * The recess map of one part: its triangles ([positions] xyz per vertex in ASSEMBLY, [indices]) seen from above in
   * the FOOTPRINT frame ([assemblyFromFootprint]; rectangle [lengthM] × [widthM] centred on the origin, top face at
   * [topM]). Null = no usable cue (no recess, a symmetric pattern, or not a flat face).
   */
  fun map(positions: FloatArray, indices: IntArray, assemblyFromFootprint: Pose3, lengthM: Double, widthM: Double, topM: Double, mmPerPx: Double = PanelParams().mmPerPx): RecessMap? {
    val s = mmPerPx / 1000.0
    val w = Math.round(lengthM / s).toInt()
    val h = Math.round(widthM / s).toInt()
    if (w < 4 * EDGE_PX || h < 4 * EDGE_PX || indices.size < 3) return null
    val gw = w * SUB
    val gh = h * SUB
    val gs = s / SUB
    // highest surface under each sub-sample, footprint Z; −∞ = nothing there (a through hole)
    val z = FloatArray(gw * gh) { Float.NEGATIVE_INFINITY }
    val m = PanelMath.toMatrix(assemblyFromFootprint.inverse())
    val nv = positions.size / 3
    val fx = DoubleArray(nv)
    val fy = DoubleArray(nv)
    val fz = DoubleArray(nv)
    for (v in 0 until nv) {
      val x = positions[3 * v].toDouble()
      val y = positions[3 * v + 1].toDouble()
      val zz = positions[3 * v + 2].toDouble()
      // footprint metres → sub-sample units (sample (a, b) is at a + 0.5, b + 0.5)
      fx[v] = (m[0] * x + m[1] * y + m[2] * zz + m[3] + w * s / 2) / gs
      fy[v] = (m[4] * x + m[5] * y + m[6] * zz + m[7] + h * s / 2) / gs
      fz[v] = m[8] * x + m[9] * y + m[10] * zz + m[11]
    }
    for (t in 0 until indices.size / 3) {
      val a = indices[3 * t]
      val b = indices[3 * t + 1]
      val c = indices[3 * t + 2]
      val det = (fy[b] - fy[c]) * (fx[a] - fx[c]) + (fx[c] - fx[b]) * (fy[a] - fy[c])
      if (abs(det) < 1e-9) continue // seen edge-on from above
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
    val below = (topM - MIN_DEPTH_M).toFloat()
    val recess = BooleanArray(w * h)
    for (j in EDGE_PX until h - EDGE_PX) for (i in EDGE_PX until w - EDGE_PX) {
      var deep = 0
      for (b in 0 until SUB) for (a in 0 until SUB) if (z[(j * SUB + b) * gw + i * SUB + a] < below) deep++
      recess[j * w + i] = 2 * deep >= SUB * SUB
    }
    return of(w, h, mmPerPx, recess)
  }

  /** A map from a recess mask (row-major [h] × [w]): the band is added, and the checks of [map] are made. */
  fun of(w: Int, h: Int, mmPerPx: Double, recess: BooleanArray): RecessMap? {
    val n = recess.count { it }
    if (n == 0 || n > MAX_RECESS_FRACTION * w * h) return null
    // narrow = the recess minus its opening by a (2 × NARROW_PX + 1) square: what a square that size does not fit in
    val k = NARROW_PX
    val core = BooleanArray(w * h) // the square centred here lies wholly in the recess
    for (j in k until h - k) for (i in k until w - k) {
      var all = true
      for (b in j - k..j + k) for (a in i - k..i + k) if (!recess[b * w + a]) all = false
      core[j * w + i] = all
    }
    val narrow = BooleanArray(w * h)
    for (j in 0 until h) for (i in 0 until w) {
      if (!recess[j * w + i]) continue
      var wide = false
      for (b in max(0, j - k)..min(h - 1, j + k)) for (a in max(0, i - k)..min(w - 1, i + k)) if (core[b * w + a]) wide = true
      narrow[j * w + i] = !wide
    }
    // the band: top-face pixels at a Chebyshev distance of BAND_GAP_PX + 1 … BAND_GAP_PX + BAND_PX from a narrow pixel
    val far = BAND_GAP_PX + BAND_PX
    val band = BooleanArray(w * h)
    for (j in EDGE_PX until h - EDGE_PX) for (i in EDGE_PX until w - EDGE_PX) {
      if (recess[j * w + i]) continue
      var d = far + 1
      var nearRecess = false
      for (b in max(0, j - far)..min(h - 1, j + far)) for (a in max(0, i - far)..min(w - 1, i + far)) {
        val cheb = max(abs(a - i), abs(b - j))
        if (narrow[b * w + a]) d = min(d, cheb)
        if (recess[b * w + a] && cheb <= BAND_GAP_PX) nearRecess = true // the rim of any recess is not "the face"
      }
      band[j * w + i] = !nearRecess && d in (BAND_GAP_PX + 1)..far
    }
    val map = RecessMap(w, h, mmPerPx, recess, narrow, band)
    return if (map.asymmetric < MIN_ASYMMETRIC_PX || map.bandCount == 0) null else map
  }

  /**
   * Both orientations of one candidate, scored on the detector's rectified image.
   *
   * @param light the rectified image, [stride] bytes per pixel with the lightness first (the detector's 8-bit Lab),
   *   row-major [n] × [n]. @param valid pixels that come from inside the camera image. @param blob the detected blob
   *   (non-zero = inside), the only pixels that count.
   * @param x0 @param y0 TABLE position of rectified pixel (0, 0), @param s metres per pixel ([PanelView]).
   * @param x @param y @param yaw the candidate: the rectangle's centre and yaw in TABLE.
   */
  fun score(map: RecessMap, light: ByteArray, stride: Int, valid: BooleanArray, blob: ByteArray, n: Int, x0: Double, y0: Double, s: Double, x: Double, y: Double, yaw: Double, p: RecessCueParams = RecessCueParams()): RecessCueResult {
    val t0 = System.nanoTime()
    val seen = DoubleArray(2)
    val a = orientation(map, light, stride, valid, blob, n, x0, y0, s, x, y, yaw, p, seen, 0)
    val b = orientation(map, light, stride, valid, blob, n, x0, y0, s, x, y, yaw + Math.PI, p, seen, 1)
    var decided = false
    if (a != null && b != null) {
      val hi = max(a, b)
      val lo = min(a, b)
      decided = hi >= p.minScore && hi - lo >= p.minMargin && lo <= p.maxRatio * hi
    }
    return RecessCueResult(a, b, decided, decided && b!! > a!!, seen[0], seen[1], (System.nanoTime() - t0) / 1e6)
  }

  /** Band mean − recess mean for one yaw at its best shift; null when too little of either is inside the blob. */
  private fun orientation(map: RecessMap, light: ByteArray, stride: Int, valid: BooleanArray, blob: ByteArray, n: Int, x0: Double, y0: Double, s: Double, x: Double, y: Double, yaw: Double, p: RecessCueParams, seen: DoubleArray, slot: Int): Double? {
    val ca = cos(yaw)
    val sa = sin(yaw)
    fun pixels(xy: FloatArray): IntArray {
      val out = IntArray(xy.size)
      for (k in 0 until xy.size / 2) {
        // rectified pixel i is the TABLE point x0 + i × s (the detector's convention): the nearest pixel
        out[2 * k] = Math.round((x + ca * xy[2 * k] - sa * xy[2 * k + 1] - x0) / s).toInt()
        out[2 * k + 1] = Math.round((y + sa * xy[2 * k] + ca * xy[2 * k + 1] - y0) / s).toInt()
      }
      return out
    }
    val rec = pixels(map.recessXy)
    val band = pixels(map.bandXy)
    // mean lightness of the listed pixels shifted by (dx, dy), over those inside the blob; NaN when too few are
    val count = IntArray(1)
    fun mean(px: IntArray, dx: Int, dy: Int): Double {
      var sum = 0L
      var c = 0
      for (k in 0 until px.size / 2) {
        val i = px[2 * k] + dx
        val j = px[2 * k + 1] + dy
        if (i < 0 || j < 0 || i >= n || j >= n) continue
        val at = j * n + i
        if (blob[at].toInt() == 0 || !valid[at]) continue
        sum += light[stride * at].toInt() and 0xff
        c++
      }
      count[0] = c
      return if (c >= p.minSeen * (px.size / 2) && c > 0) sum.toDouble() / c else Double.NaN
    }
    var best: Double? = null
    for (dy in -p.shiftPx..p.shiftPx) for (dx in -p.shiftPx..p.shiftPx) {
      val r = mean(rec, dx, dy)
      val seenHere = count[0].toDouble() / max(1, rec.size / 2)
      if (dx == 0 && dy == 0) seen[slot] = seenHere
      if (r.isNaN()) continue
      val b = mean(band, dx, dy)
      if (b.isNaN()) continue
      if (best == null || b - r > best) best = b - r
    }
    return best
  }
}

/**
 * The tracker's 180° rule on top of "the yaw nearest to the ghost's": a single result never turns the pose. When the
 * cue is confident on [needed] trusted results in a row and each says the other way round, the pose is turned by 180°
 * once ([add] returns true for that result) and [yaw] holds the turned yaw from then on: the detector is given it as
 * its previous yaw, so the choice continues from the real part and no longer from the ghost. A result without a
 * confident cue (untrusted, hands over the recesses, a part with no recess) breaks the row and changes nothing.
 * Pure, not thread-safe.
 */
class RecessTurn(val needed: Int = 3) {
  /** Confident "the other way round" results in a row. */
  var streak = 0
    private set

  /** How many times the pose was turned since [reset]. */
  var flips = 0
    private set

  /** The yaw to continue from once the cue has turned the pose; null = the ghost's yaw decides (the fallback). */
  var yaw: Double? = null
    private set

  fun reset() {
    streak = 0
    flips = 0
    yaw = null
  }

  /**
   * One detector result: [trusted], its [resultYaw] (the tracker's current choice) and its cue (null = none).
   * Returns true when this result turns the pose: use `resultYaw + π` for it.
   */
  fun add(trusted: Boolean, resultYaw: Double?, cue: RecessCueResult?): Boolean {
    if (!trusted || resultYaw == null) {
      streak = 0
      return false
    }
    if (cue == null || !cue.decided || !cue.saysTurned) {
      streak = 0
      if (yaw != null) yaw = resultYaw // continuity: the part may turn slowly
      return false
    }
    streak++
    if (streak < needed) {
      if (yaw != null) yaw = resultYaw
      return false
    }
    streak = 0
    flips++
    yaw = resultYaw + Math.PI
    return true
  }
}

/** The `cue` field of the AGQ `panel` event. */
object RecessCueLog {
  /**
   * `score` [as the detector chose, turned by 180°] = how much darker the recess pixels are than their surroundings
   * (8-bit L); `margin` = the first minus the second; `seen` = the fraction of the recess pixels inside the blob, per
   * orientation; `decided` = one is clearly the darker; `says_turned` = the cue wants the other way round; `streak` =
   * such results in a row (a turn needs [RecessTurn.needed]); `flipped` = this result turned the pose; `flips` = turns
   * since the target was set; `kept_yaw_deg` = the yaw the choice continues from after a turn; `ms` = the cue's cost.
   * Null when the result has no cue (not trusted, or the part has no recess) and nothing was turned yet.
   */
  fun of(cue: RecessCueResult?, turn: RecessTurn, flipped: Boolean): Map<String, Any?>? {
    if (cue == null) return if (turn.flips > 0) mapOf("flipped" to false, "flips" to turn.flips, "kept_yaw_deg" to turn.yaw?.let { Math.toDegrees(it) }) else null
    fun r1(v: Double?) = v?.let { Math.round(it * 10) / 10.0 }
    return mapOf(
        "score" to listOf(r1(cue.asIs), r1(cue.turned)),
        "margin" to r1(cue.margin),
        "seen" to listOf(Math.round(cue.seenAsIs * 100) / 100.0, Math.round(cue.seenTurned * 100) / 100.0),
        "decided" to cue.decided,
        "says_turned" to cue.saysTurned,
        "streak" to turn.streak,
        "flipped" to flipped,
        "flips" to turn.flips,
        "kept_yaw_deg" to turn.yaw?.let { Math.toDegrees(it) },
        "ms" to Math.round(cue.ms * 100) / 100.0,
    )
  }
}
