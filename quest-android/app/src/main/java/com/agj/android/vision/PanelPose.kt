package com.agj.android.vision

import com.agj.android.core.Agq
import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/*
 * The MALM drawer front lying flat on the table, without a marker: x, y and yaw on the known table plane from ONE
 * colour frame. A port of server/perception/panel.py `detect_front_panel` (method, numbers and failure cases:
 * tools/markerless/FRONT_PANEL.md). Measured on renders only; NOT yet proven on real Quest frames.
 *
 * Frames (all right-handed, metres; see [Frames]):
 *  - CAMERA: OpenCV axes (+X right, +Y down, +Z forward), `CameraFrame.worldFromCamera`.
 *  - TABLE (this file only; not guide/'s Y-up TABLE spot): origin on the table surface, +Z = the table normal (up),
 *    X and Y in the surface. [PanelFrames.tableAtHeight] builds it from the scene table's WORLD height.
 *  - PANEL: origin = the panel's centre ON the table plane, +X along the 380 mm side, +Z up (the reference's
 *    `T_world_obj`). Yaw = rotation of PANEL about TABLE +Z, known modulo 180°.
 *
 * [PanelMath] and [PanelFrames] are pure Kotlin (JVM-tested against numbers exported from the Python reference);
 * [PanelPose] is the OpenCV part (Java API only, like [MarkerDetector]); [PanelPoseTracker] is the thin runtime.
 *
 * Every step, not only the first: the detector is the reference's `detect_footprint`, the same code for any flat
 * rectangle ([PanelParams.longM] × [PanelParams.shortM], top face [PanelParams.thickM] above the table), and
 * [PanelTarget] is what it looks for at one step, from the app's own guide ([StepFootprints.target]): the flat
 * base part of the assembly (FOOTPRINT frame = PANEL generalised). For the drawer that is the front panel in
 * steps 1–3 (in 2 and 3 the sides stand on it and hide parts of it, so the convex hull of the blob is measured:
 * [PanelParams.fillHull]). From step 4 the drawer is turned over; from above it is not a flat rectangle of one
 * height, the renders say it cannot be trusted (FRONT_PANEL.md "All steps"), and the tracker stays off.
 */

/** Every constant of the reference, by name, so it can be tuned on the device. Defaults = server/perception/panel.py. */
data class PanelParams(
    /** Rectangle size and the height of its top face above the table, metres (`length`, `width`, `height`; defaults `LONG`, `SHORT`, `THICK`). */
    val longM: Double = 0.380,
    val shortM: Double = 0.130,
    val thickM: Double = 0.016,
    /** `fill_hull`: area, rectness and the contrast ring use the convex hull of each blob (parts standing on the panel hide parts of it). */
    val fillHull: Boolean = false,
    /** Rectified view: millimetres per pixel (`MM_PER_PX`) and half size of the window on the table (`HALF`). */
    val mmPerPx: Double = 2.5,
    val halfM: Double = 0.7,
    /** Size gate after removing the predicted smear (`TOL_LONG`, `TOL_SHORT`). */
    val tolLongM: Double = 0.014,
    val tolShortM: Double = 0.012,
    /** Contour area / rectangle area (`MIN_RECT`). */
    val minRectness: Double = 0.86,
    /** Whiteness inside minus the ring outside, 0..255 (`MIN_CONTRAST`). */
    val minContrast: Double = 18.0,
    /** Camera at least this far above the panel's top face (`cam[2] < THICK + 0.05`). */
    val minCamAboveTopM: Double = 0.05,
    /** Optical axis z in the table frame at most this (`d[2] > -0.15` rejects: about 9° below horizontal). */
    val maxAxisZ: Double = -0.15,
    /** Window centre at most this far along the optical axis (`min(s_, 1.2)`). */
    val maxRayM: Double = 1.2,
    /** Rectified pixel is valid when its depth is above this and it lands this far inside the image. */
    val minDepth: Double = 0.1,
    val imageMarginLowPx: Double = 2.0,
    val imageMarginHighPx: Double = 3.0,
    /** Fewer valid pixels than this fraction: "table not in view". */
    val minValidFraction: Double = 0.05,
    /** Whiteness = L − [chromaWeight] × chroma (8-bit Lab). */
    val chromaWeight: Double = 1.5,
    /** Gaussian blur and opening kernel sizes, pixels. */
    val blurPx: Int = 5,
    val openPx: Int = 3,
    /** Threshold sweep: percentiles of the whiteness of the valid pixels. */
    val percentiles: List<Double> = listOf(50.0, 60.0, 70.0, 78.0, 85.0, 90.0, 94.0, 97.0),
    /** Contour area as a multiple of the panel's area. */
    val minAreaRatio: Double = 0.6,
    val maxAreaRatio: Double = 2.2,
    /** Weight of the rectness shortfall in the candidate score. */
    val rectnessPenalty: Double = 10.0,
    /** The ring round the blob = dilate([ringOuterPx]) − dilate([ringInnerPx]); more than [maxCut] outside the image: "partly out of view". */
    val ringOuterPx: Int = 15,
    val ringInnerPx: Int = 5,
    val maxCut: Double = 0.03,
)

/**
 * What the detector looks for at one step: a flat rectangle on the table, and where the assembly is relative to
 * it. From the guide: [StepFootprints.target] (null there = the step has no flat base part = do not track it).
 *
 * @property lengthM @property widthM the rectangle, metres (length ≥ width).
 * @property topM its top face above the table (the rectification height and the smear model's height).
 * @property fillHull parts stand on it during the step and hide parts of it: [PanelParams.fillHull].
 * @property assemblyFromFootprint ASSEMBLY ← FOOTPRINT (origin = the rectangle's centre ON the table plane, +X
 *   along the length, +Z up; for the drawer front alone this is [PanelFrames.ASSEMBLY_FROM_PANEL]).
 * @property slab the part node the rectangle is, for the log.
 */
data class PanelTarget(
    val lengthM: Double,
    val widthM: Double,
    val topM: Double,
    val fillHull: Boolean,
    val assemblyFromFootprint: Pose3,
    val slab: String? = null,
) {
  /** [base] with this rectangle: every gate and constant stays as it is. */
  fun params(base: PanelParams = PanelParams()): PanelParams = base.copy(longM = lengthM, shortM = widthM, thickM = topM, fillHull = fillHull)

  /** Length, width and top height in whole millimetres, for the log. */
  fun footprintMm(): List<Long> = listOf(Math.round(lengthM * 1000), Math.round(widthM * 1000), Math.round(topM * 1000))

  /** The same physical rectangle in the same place of the assembly (within 1 mm); [fillHull] may differ. */
  fun sameRectangle(o: PanelTarget?): Boolean {
    if (o == null) return false
    val d = assemblyFromFootprint.inverse() * o.assemblyFromFootprint
    return abs(lengthM - o.lengthM) < 1e-3 && abs(widthM - o.widthM) < 1e-3 && abs(topM - o.topM) < 1e-3 && d.t.length() < 1e-3f && abs(d.q.w) > 0.99999f
  }

  companion object {
    /** The MALM drawer front alone (step 1): what the tracker looks for until [PanelPoseTracker.setStep] is called. */
    val FRONT_PANEL: PanelTarget
      get() = PanelTarget(0.380, 0.130, 0.016, false, PanelFrames.ASSEMBLY_FROM_PANEL, "part_drawer_front_1")
  }
}

/**
 * One frame's result, in the TABLE frame given to the detector.
 *
 * @property trusted every gate passed. When false keep the last good pose ([x], [y], [yaw] may still be set: the
 *   best candidate, for the log).
 * @property reason "ok" or the first gate that failed (the reference's strings, see [PanelMath]).
 * @property x @property y panel centre on the table, metres. @property yaw radians, modulo 180° (nearest to
 *   `prevYaw` when given, else in [0, π)).
 * @property cornersPx the 4 top-face corners in the input image (x0, y0, … x3, y3), PANEL order (−,−), (+,−), (+,+), (−,+).
 * @property ms wall time of the detection.
 * @property score |size error| / tolerance summed over both sides (+ rectness penalty); 0 = perfect, ≤ 2 when trusted.
 */
class PanelPoseResult(
    val trusted: Boolean,
    val reason: String,
    val x: Double?,
    val y: Double?,
    val yaw: Double?,
    val cornersPx: DoubleArray?,
    val ms: Double,
    val sizeErrMm: DoubleArray? = null,
    val rectness: Double? = null,
    val contrast: Double? = null,
    val score: Double? = null,
    val imageSize: IntArray? = null,
)

/** The rectified top-down window for one camera pose. [h]: 3 × 3 row-major, bird pixel (i, j, 1) → image pixel. */
class PanelView(
    val n: Int,
    val x0: Double,
    val y0: Double,
    /** Metres per bird pixel. */
    val s: Double,
    val h: DoubleArray,
    /** Camera position in bird pixels and its height above the panel's top face. */
    val camPx: DoubleArray,
    val hc: Double,
    /** CAMERA ← TABLE, 4 × 4 row-major. */
    val cameraFromTable: DoubleArray,
)

/** A contour's rectangle after the smear correction. [al]/[asv]: unit long/short axes in bird pixels. */
class PanelCandidate(
    val box: DoubleArray,
    val al: DoubleArray,
    val asv: DoubleArray,
    val ctr: DoubleArray,
    val errL: Double,
    val errS: Double,
    val rectness: Double,
    val score: Double,
)

/** The maths of server/perception/panel.py without OpenCV: 4 × 4 row-major doubles, same formulas line by line. */
object PanelMath {
  const val REASON_OK = "ok"
  const val REASON_NO_CANDIDATE = "no candidate"
  const val REASON_NOT_LOOKING_DOWN = "camera not looking down at the table"
  const val REASON_NOT_IN_VIEW = "table not in view"
  const val REASON_SIZE = "size is not 380 x 130 mm"

  /** The size gate's reason for the rectangle in [p] (the reference's f-string; [REASON_SIZE] for the defaults). */
  fun reasonSize(p: PanelParams = PanelParams()): String = "size is not ${Math.round(p.longM * 1000)} x ${Math.round(p.shortM * 1000)} mm"
  const val REASON_RECT = "not a clean rectangle (hand or object over it)"
  const val REASON_CUT = "panel partly out of the camera view"
  const val REASON_CONTRAST = "low contrast against the table"

  fun toMatrix(p: Pose3): DoubleArray {
    val x = p.q.x.toDouble()
    val y = p.q.y.toDouble()
    val z = p.q.z.toDouble()
    val w = p.q.w.toDouble()
    return doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), p.t.x.toDouble(),
        2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), p.t.y.toDouble(),
        2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), p.t.z.toDouble(),
        0.0, 0.0, 0.0, 1.0,
    )
  }

  fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
    val o = DoubleArray(16)
    for (r in 0 until 4) for (c in 0 until 4) {
      var s = 0.0
      for (k in 0 until 4) s += a[4 * r + k] * b[4 * k + c]
      o[4 * r + c] = s
    }
    return o
  }

  /** Inverse of a rigid transform. */
  fun invertRigid(m: DoubleArray): DoubleArray {
    val o = DoubleArray(16)
    for (r in 0 until 3) {
      for (c in 0 until 3) o[4 * r + c] = m[4 * c + r]
      o[4 * r + 3] = -(m[r] * m[3] + m[4 + r] * m[7] + m[8 + r] * m[11])
    }
    o[15] = 1.0
    return o
  }

  /** TABLE ← CAMERA = inv(WORLD ← TABLE) × WORLD ← CAMERA. */
  fun tableFromCamera(worldFromCamera: Pose3, worldFromTable: Pose3): DoubleArray =
      mul(invertRigid(toMatrix(worldFromTable)), toMatrix(worldFromCamera))

  /** The reference's first gate: camera above the panel and its optical axis pointing down at the table. */
  fun looksDown(tableFromCamera: DoubleArray, p: PanelParams = PanelParams()): Boolean =
      !(tableFromCamera[11] < p.thickM + p.minCamAboveTopM || tableFromCamera[10] > p.maxAxisZ)

  /**
   * The rectification window and `H = K · [ s·r1 | s·r2 | x0·r1 + y0·r2 + THICK·r3 + t ]` (r, t of CAMERA ← TABLE).
   * Null = [REASON_NOT_LOOKING_DOWN].
   */
  fun view(fx: Double, fy: Double, cx: Double, cy: Double, tableFromCamera: DoubleArray, p: PanelParams = PanelParams()): PanelView? {
    val tc = tableFromCamera
    if (!looksDown(tc, p)) return null
    val camX = tc[3]
    val camY = tc[7]
    val camZ = tc[11]
    val ct = invertRigid(tc)
    val ray = min((p.thickM - camZ) / tc[10], p.maxRayM)
    val x0 = camX + ray * tc[2] - p.halfM
    val y0 = camY + ray * tc[6] - p.halfM
    val s = p.mmPerPx / 1000.0
    val n = (2 * p.halfM / s).toInt()
    val m = DoubleArray(9)
    for (r in 0 until 3) {
      m[3 * r] = ct[4 * r] * s
      m[3 * r + 1] = ct[4 * r + 1] * s
      m[3 * r + 2] = ct[4 * r] * x0 + ct[4 * r + 1] * y0 + ct[4 * r + 2] * p.thickM + ct[4 * r + 3]
    }
    val h = DoubleArray(9)
    for (c in 0 until 3) {
      h[c] = fx * m[c] + cx * m[6 + c]
      h[3 + c] = fy * m[3 + c] + cy * m[6 + c]
      h[6 + c] = m[6 + c]
    }
    return PanelView(n, x0, y0, s, h, doubleArrayOf((camX - x0) / s, (camY - y0) / s), camZ - p.thickM, ct)
  }

  /** Which bird pixels come from inside the camera image (row-major n × n). Returns the mask and its count. */
  fun validMask(v: PanelView, imgW: Int, imgH: Int, p: PanelParams = PanelParams()): Pair<BooleanArray, Int> {
    val n = v.n
    val h = v.h
    val out = BooleanArray(n * n)
    var count = 0
    val maxU = imgW - p.imageMarginHighPx
    val maxV = imgH - p.imageMarginHighPx
    for (j in 0 until n) {
      for (i in 0 until n) {
        val pw = h[6] * i + h[7] * j + h[8]
        val d = max(pw, 1e-6)
        val pu = (h[0] * i + h[1] * j + h[2]) / d
        val pv = (h[3] * i + h[4] * j + h[5]) / d
        if (pw > p.minDepth && pu > p.imageMarginLowPx && pu < maxU && pv > p.imageMarginLowPx && pv < maxV) {
          out[j * n + i] = true
          count++
        }
      }
    }
    return out to count
  }

  /** `np.percentile` (linear interpolation) of an ascending array. */
  fun percentile(sorted: FloatArray, q: Double): Double {
    if (sorted.isEmpty()) return 0.0
    val pos = q / 100.0 * (sorted.size - 1)
    val lo = floor(pos).toInt().coerceIn(0, sorted.size - 1)
    val hi = min(lo + 1, sorted.size - 1)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
  }

  /** Panel area in bird pixels. */
  fun panelAreaPx(v: PanelView, p: PanelParams = PanelParams()): Double = p.longM * p.shortM / (v.s * v.s)

  fun areaOk(area: Double, v: PanelView, p: PanelParams = PanelParams()): Boolean {
    val a = panelAreaPx(v, p)
    return !(area < p.minAreaRatio * a || area > p.maxAreaRatio * a)
  }

  /**
   * One contour's `minAreaRect` ([box]: x0, y0, … x3, y3 in bird pixels, consecutive corners) with area [area]:
   * the camera-facing side faces smear the blob towards the camera by THICK × horizontal distance / camera height,
   * so that is removed from each side before comparing with 380 × 130 mm. Null for a degenerate rectangle.
   */
  fun candidate(box: DoubleArray, area: Double, v: PanelView, p: PanelParams = PanelParams()): PanelCandidate? {
    val e0x = box[2] - box[0]
    val e0y = box[3] - box[1]
    val e1x = box[4] - box[2]
    val e1y = box[5] - box[3]
    val l0 = sqrt(e0x * e0x + e0y * e0y)
    val l1 = sqrt(e1x * e1x + e1y * e1y)
    if (l0 < 1 || l1 < 1) return null
    val first = l0 >= l1
    val al = if (first) doubleArrayOf(e0x / l0, e0y / l0) else doubleArrayOf(e1x / l1, e1y / l1)
    val asv = if (first) doubleArrayOf(e1x / l1, e1y / l1) else doubleArrayOf(e0x / l0, e0y / l0)
    val ll = if (first) l0 else l1
    val ls = if (first) l1 else l0
    val ctr = doubleArrayOf((box[0] + box[2] + box[4] + box[6]) / 4, (box[1] + box[3] + box[5] + box[7]) / 4)
    val vx = (v.camPx[0] - ctr[0]) * v.s
    val vy = (v.camPx[1] - ctr[1]) * v.s
    val smearL = p.thickM * max(abs(vx * al[0] + vy * al[1]) - ll * v.s / 2, 0.0) / v.hc
    val smearS = p.thickM * max(abs(vx * asv[0] + vy * asv[1]) - ls * v.s / 2, 0.0) / v.hc
    val errL = ll * v.s - smearL - p.longM
    val errS = ls * v.s - smearS - p.shortM
    val rectness = area / (l0 * l1)
    val score = abs(errL) / p.tolLongM + abs(errS) / p.tolShortM + max(0.0, p.minRectness - rectness) * p.rectnessPenalty
    return PanelCandidate(box, al, asv, ctr, errL, errS, rectness, score)
  }

  /**
   * (x, y, yaw) in the TABLE frame: an exact 380 × 130 rectangle anchored at the corner FARTHEST from the camera
   * (top-face silhouette, never smeared). Yaw in [0, π), or the nearer of the two to [prevYaw].
   */
  fun pose(c: PanelCandidate, v: PanelView, prevYaw: Double?, p: PanelParams = PanelParams()): DoubleArray {
    var far = 0
    var best = -1.0
    for (i in 0 until 4) {
      val dx = c.box[2 * i] - v.camPx[0]
      val dy = c.box[2 * i + 1] - v.camPx[1]
      val d = sqrt(dx * dx + dy * dy)
      if (d > best) {
        best = d
        far = i
      }
    }
    val fx = c.box[2 * far]
    val fy = c.box[2 * far + 1]
    val sl = Math.signum((c.ctr[0] - fx) * c.al[0] + (c.ctr[1] - fy) * c.al[1])
    val ss = Math.signum((c.ctr[0] - fx) * c.asv[0] + (c.ctr[1] - fy) * c.asv[1])
    val alx = c.al[0] * sl
    val aly = c.al[1] * sl
    val px = fx + alx * (p.longM / v.s / 2) + c.asv[0] * ss * (p.shortM / v.s / 2)
    val py = fy + aly * (p.longM / v.s / 2) + c.asv[1] * ss * (p.shortM / v.s / 2)
    var yaw = atan2(aly, alx).mod(Math.PI)
    if (prevYaw != null && abs((yaw - prevYaw + Math.PI).mod(2 * Math.PI) - Math.PI) > Math.PI / 2) yaw += Math.PI
    return doubleArrayOf(v.x0 + px * v.s, v.y0 + py * v.s, yaw)
  }

  /** The trust gates in the reference's order; null = trusted. */
  fun gate(errL: Double, errS: Double, rectness: Double, cut: Double, contrast: Double, p: PanelParams = PanelParams()): String? =
      when {
        abs(errL) > p.tolLongM || abs(errS) > p.tolShortM -> reasonSize(p)
        rectness < p.minRectness -> REASON_RECT
        cut > p.maxCut -> REASON_CUT
        contrast < p.minContrast -> REASON_CONTRAST
        else -> null
      }

  // ---- FLAT-PARTS ----

  /** The four corners of a [lengthM] × [widthM] rectangle at ([x], [y]) turned by [yaw], TABLE frame (x0, y0, … x3, y3), order of [cornersPx]. */
  fun rectCorners(x: Double, y: Double, yaw: Double, lengthM: Double, widthM: Double): DoubleArray {
    val ca = cos(yaw)
    val sa = sin(yaw)
    val out = DoubleArray(8)
    val signs = arrayOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)
    for ((i, sg) in signs.withIndex()) {
      val lx = sg.first * lengthM / 2
      val ly = sg.second * widthM / 2
      out[2 * i] = x + ca * lx - sa * ly
      out[2 * i + 1] = y + sa * lx + ca * ly
    }
    return out
  }

  /**
   * How much darker than the board the darkest line along the length of [c] is (grey levels of [bgr], the rectified
   * picture, [n] × [n] × 3 bytes), looking only where a board at least [minBoardPx] wide fits on either side of the
   * line; 0 when the rectangle is too narrow to be two boards. Each line is the mean over the middle 80 % of the
   * length, so holes, screws and the carpet's grain do not count; a groove near one edge (the drawer front's) is
   * outside the part that is looked at.
   */
  fun seamDip(bgr: ByteArray, n: Int, c: PanelCandidate, minBoardPx: Double): Double {
    val e0 = sqrt((c.box[2] - c.box[0]) * (c.box[2] - c.box[0]) + (c.box[3] - c.box[1]) * (c.box[3] - c.box[1]))
    val e1 = sqrt((c.box[4] - c.box[2]) * (c.box[4] - c.box[2]) + (c.box[5] - c.box[3]) * (c.box[5] - c.box[3]))
    val halfL = max(e0, e1) / 2
    val halfS = min(e0, e1) / 2
    val inner = halfS - minBoardPx
    if (inner < 2) return 0.0
    fun line(w: Double): Double {
      var sum = 0.0
      var count = 0
      var u = -0.8 * halfL
      while (u <= 0.8 * halfL) {
        val x = Math.round(c.ctr[0] + u * c.al[0] + w * c.asv[0]).toInt()
        val y = Math.round(c.ctr[1] + u * c.al[1] + w * c.asv[1]).toInt()
        if (x in 0 until n && y in 0 until n) {
          val i = 3 * (y * n + x)
          sum += ((bgr[i].toInt() and 0xff) + (bgr[i + 1].toInt() and 0xff) + (bgr[i + 2].toInt() and 0xff)) / 3.0
          count++
        }
        u += 2.0
      }
      return if (count == 0) 0.0 else sum / count
    }
    val all = ArrayList<Double>()
    var w = -halfS + 3
    while (w <= halfS - 3) {
      all += line(w)
      w += 1.0
    }
    if (all.isEmpty()) return 0.0
    val board = all.sorted()[all.size / 2]
    var darkest = Double.MAX_VALUE
    w = -inner
    while (w <= inner) {
      darkest = min(darkest, line(w))
      w += 1.0
    }
    return max(0.0, board - darkest)
  }

  /**
   * One blob seen at several thresholds, and blobs inside blobs, down to one rectangle per real thing:
   *  1. the same blob at two thresholds ([RectGates.sameCentreM], [RectGates.sameSizeM]) → the cleaner rectangle;
   *  2. a rectangle with two or more others inside it is several parts read as one (a low threshold joined them
   *     across a narrow gap): it goes, the parts stay;
   *  3. a rectangle with exactly one other, different, inside it: two readings of one place and no way to say
   *     which is the part (a hard shadow across a board, or two boards of which only one came out clean) →
   *     neither is reported. No rectangle is better than a wrong one.
   */
  fun resolveRects(found: List<FoundRect>, g: RectGates = RectGates()): List<FoundRect> {
    val reps = ArrayList<FoundRect>()
    for (r in found.sortedByDescending { it.rectness }) {
      val same = reps.any { k ->
        val dx = k.x - r.x
        val dy = k.y - r.y
        sqrt(dx * dx + dy * dy) <= g.sameCentreM && abs(k.lengthM - r.lengthM) <= g.sameSizeM && abs(k.widthM - r.widthM) <= g.sameSizeM
      }
      if (!same) reps += r
    }
    val drop = BooleanArray(reps.size)
    for ((i, a) in reps.withIndex()) {
      val inner = reps.indices.filter { j -> j != i && reps[j].lengthM * reps[j].widthM < a.lengthM * a.widthM && a.contains(reps[j].x, reps[j].y) }
      if (inner.isNotEmpty()) drop[i] = true
      if (inner.size == 1) drop[inner[0]] = true
    }
    return reps.filterIndexed { i, _ -> !drop[i] }
  }

  /** The 4 top-face corners projected into the image (x0, y0, … x3, y3). */
  fun cornersPx(x: Double, y: Double, yaw: Double, fx: Double, fy: Double, cx: Double, cy: Double, v: PanelView, p: PanelParams = PanelParams()): DoubleArray {
    val ca = cos(yaw)
    val sa = sin(yaw)
    val ct = v.cameraFromTable
    val out = DoubleArray(8)
    val signs = arrayOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)
    for ((i, sg) in signs.withIndex()) {
      val lx = sg.first * p.longM / 2
      val ly = sg.second * p.shortM / 2
      val tx = x + ca * lx - sa * ly
      val ty = y + sa * lx + ca * ly
      val tz = p.thickM
      val xc = ct[0] * tx + ct[1] * ty + ct[2] * tz + ct[3]
      val yc = ct[4] * tx + ct[5] * ty + ct[6] * tz + ct[7]
      val zc = ct[8] * tx + ct[9] * ty + ct[10] * tz + ct[11]
      out[2 * i] = xc / zc * fx + cx
      out[2 * i + 1] = yc / zc * fy + cy
    }
    return out
  }
}

/**
 * PANEL ↔ ASSEMBLY ↔ WORLD for the MALM drawer front, from the same mesh numbers as [MarkerPlacement] (node
 * `part_drawer_front_1`: X −0.19…0.19, Y −0.015…0.115, Z 0…0.016, inside face = plane Z = 0 facing −Z).
 *
 * In step 1 the front lies inside face up (the marker path's "assembly −Z pointing up"), so its outside face
 * (Z = 0.016) is on the table and PANEL's origin, the centre on the table plane, is ASSEMBLY (0, 0.05, 0.016).
 * PANEL +X = ASSEMBLY +X (the 380 mm side), PANEL +Z (up) = ASSEMBLY −Z, hence PANEL +Y = ASSEMBLY −Y: the same
 * 180° turn about X as ASSEMBLY ← MARKER. Equivalently PANEL sits (0, +0.015, −0.016) from the strip centre in
 * MARKER axes (15 mm from the middle of the marker band to the middle of the panel, 16 mm down to the table).
 * The detector's 180° ambiguity is the choice between ASSEMBLY +X and −X along the long side.
 */
object PanelFrames {
  /** The only job the detector knows the base part of. */
  const val MALM_JOB = "malm-drawer"
  const val FRONT_Y_MIN = -0.015f
  const val FRONT_Y_MAX = 0.115f
  const val FRONT_THICK = 0.016f

  /** ASSEMBLY ← PANEL. */
  val ASSEMBLY_FROM_PANEL = Pose3(Vec3(0f, (FRONT_Y_MIN + FRONT_Y_MAX) / 2, FRONT_THICK), MarkerPlacement.MALM_DRAWER_FRONT.q)

  /** WORLD ← TABLE axes: TABLE +X = WORLD +X, TABLE +Y = WORLD −Z, TABLE +Z = WORLD +Y (up). Right-handed. */
  private val TABLE_Q: Quat = Frames.matToQuat(arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, -1.0, 0.0)))

  /** WORLD ← TABLE for a level table whose surface is at WORLD height [y] (the world module's `table.y`). */
  fun tableAtHeight(y: Float): Pose3 = Pose3(Vec3(0f, y, 0f), TABLE_Q)

  /** FLAT-PARTS: a [FoundRect]'s four top-face corners in WORLD ([worldFromTable] of the call that found it). */
  fun cornersWorld(worldFromTable: Pose3, r: FoundRect): List<Vec3> =
      (0 until 4).map { worldFromTable.transform(Vec3(r.cornersTable[2 * it].toFloat(), r.cornersTable[2 * it + 1].toFloat(), r.topM.toFloat())) }

  /** TABLE ← PANEL from the detector's result. */
  fun tableFromPanel(x: Double, y: Double, yaw: Double): Pose3 =
      Pose3(Vec3(x.toFloat(), y.toFloat(), 0f), Quat.axisAngle(Vec3(0f, 0f, 1f), yaw.toFloat()))

  /**
   * WORLD ← ASSEMBLY of the real assembly (what the marker path reports), for `ghost.setAssemblyPose`.
   * [assemblyFromFootprint]: ASSEMBLY ← FOOTPRINT of the rectangle that was detected ([PanelTarget]); default the
   * drawer front alone.
   */
  fun worldFromAssembly(worldFromTable: Pose3, x: Double, y: Double, yaw: Double, assemblyFromFootprint: Pose3 = ASSEMBLY_FROM_PANEL): Pose3 =
      worldFromTable * tableFromPanel(x, y, yaw) * assemblyFromFootprint.inverse()

  /** The rectangle's yaw a given WORLD ← ASSEMBLY corresponds to (the ghost's current pose → `prevYaw`). */
  fun panelYawInTable(worldFromTable: Pose3, worldFromAssembly: Pose3, assemblyFromFootprint: Pose3 = ASSEMBLY_FROM_PANEL): Double {
    val ax = (worldFromTable.inverse() * worldFromAssembly * assemblyFromFootprint).q.rotate(Vec3(1f, 0f, 0f))
    return atan2(ax.y.toDouble(), ax.x.toDouble())
  }
}

/**
 * The OpenCV part of the port: warp, Lab whiteness, threshold sweep, contours, `minAreaRect`, gates.
 * Java API only (device: OpenCV Android SDK; JVM tests: desktop bindings). Loading the native library is the
 * caller's job. Stateless, thread-safe.
 */
object PanelPose {
  /**
   * @param bgr 8-bit BGR image, undistorted. [k] is scaled to its size here.
   * @param worldFromCamera WORLD ← CAMERA (OpenCV axes) at the exposure time.
   * @param worldFromTable WORLD ← TABLE (+Z = table normal, origin on the surface), e.g. [PanelFrames.tableAtHeight].
   * @param prevYaw the ghost's current panel yaw (TABLE frame) to resolve the 180° ambiguity; null → yaw in [0, π).
   */
  fun detect(bgr: Mat, k: Intrinsics, worldFromCamera: Pose3, worldFromTable: Pose3, prevYaw: Double? = null, p: PanelParams = PanelParams()): PanelPoseResult =
      detect(bgr, k, PanelMath.tableFromCamera(worldFromCamera, worldFromTable), prevYaw, p)

  /** Same, from an encoded image (the cheapest colour path the camera source offers: `CameraFrame.jpegAt`). */
  fun detectJpeg(jpeg: ByteArray, k: Intrinsics, tableFromCamera: DoubleArray, prevYaw: Double? = null, p: PanelParams = PanelParams()): PanelPoseResult {
    val t0 = System.nanoTime()
    val buf = MatOfByte(*jpeg)
    val bgr = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
    buf.release()
    try {
      if (bgr.empty()) return PanelPoseResult(false, "image did not decode", null, null, null, null, (System.nanoTime() - t0) / 1e6)
      return detect(bgr, k, tableFromCamera, prevYaw, p, t0)
    } finally {
      bgr.release()
    }
  }

  /** `cv2.convexHull(cnt)`: the hull's points in OpenCV's order. Releases [cnt]. */
  private fun hullOf(cnt: MatOfPoint): MatOfPoint {
    val idx = MatOfInt()
    Imgproc.convexHull(cnt, idx)
    val pts = cnt.toArray()
    val hull = MatOfPoint(*idx.toArray().map { pts[it] }.toTypedArray())
    idx.release()
    cnt.release()
    return hull
  }

  /** [tableFromCamera]: TABLE ← CAMERA, 4 × 4 row-major (the reference's `T_tc`). */
  fun detect(bgr: Mat, k0: Intrinsics, tableFromCamera: DoubleArray, prevYaw: Double? = null, p: PanelParams = PanelParams(), t0: Long = System.nanoTime()): PanelPoseResult {
    val size = intArrayOf(bgr.cols(), bgr.rows())
    fun fail(reason: String) = PanelPoseResult(false, reason, null, null, null, null, (System.nanoTime() - t0) / 1e6, imageSize = size)
    val k = k0.scaledTo(bgr.cols(), bgr.rows())
    val v = PanelMath.view(k.fx, k.fy, k.cx, k.cy, tableFromCamera, p) ?: return fail(PanelMath.REASON_NOT_LOOKING_DOWN)
    val n = v.n
    val (valid, validCount) = PanelMath.validMask(v, bgr.cols(), bgr.rows(), p)
    if (validCount.toDouble() / (n * n) < p.minValidFraction) return fail(PanelMath.REASON_NOT_IN_VIEW)

    val mats = ArrayList<Mat>()
    fun mat(m: Mat): Mat {
      mats += m
      return m
    }
    var bestCnt: MatOfPoint? = null
    try {
      val hMat = mat(Mat(3, 3, CvType.CV_64F))
      hMat.put(0, 0, *v.h)
      val bird = mat(Mat())
      Imgproc.warpPerspective(bgr, bird, hMat, Size(n.toDouble(), n.toDouble()), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP)
      val blur = mat(Mat())
      Imgproc.GaussianBlur(bird, blur, Size(p.blurPx.toDouble(), p.blurPx.toDouble()), 0.0)
      val lab = mat(Mat())
      Imgproc.cvtColor(blur, lab, Imgproc.COLOR_BGR2Lab)
      val labBytes = ByteArray(n * n * 3)
      lab.get(0, 0, labBytes)
      // whiteness = L − 1.5 × chroma, clipped to 0..255, 0 outside the camera image
      val white = FloatArray(n * n)
      val vals = FloatArray(validCount)
      var vi = 0
      val cw = p.chromaWeight.toFloat()
      for (i in 0 until n * n) {
        if (!valid[i]) continue
        val l = (labBytes[3 * i].toInt() and 0xff).toFloat()
        val a = (labBytes[3 * i + 1].toInt() and 0xff) - 128f
        val b = (labBytes[3 * i + 2].toInt() and 0xff) - 128f
        val w = (l - cw * sqrt(a * a + b * b)).coerceIn(0f, 255f)
        white[i] = w
        vals[vi++] = w
      }
      vals.sort()
      val thresholds = p.percentiles.map { PanelMath.percentile(vals, it) }.distinct().sorted()

      val kernel = mat(Mat.ones(p.openPx, p.openPx, CvType.CV_8U))
      val mask = mat(Mat(n, n, CvType.CV_8UC1))
      val opened = mat(Mat())
      val hierarchy = mat(Mat())
      val maskBytes = ByteArray(n * n)
      var best: PanelCandidate? = null
      for (thr in thresholds) {
        for (i in 0 until n * n) maskBytes[i] = if (white[i] > thr) 1 else 0
        mask.put(0, 0, maskBytes)
        Imgproc.morphologyEx(mask, opened, Imgproc.MORPH_OPEN, kernel)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(opened, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        for (raw in contours) {
          var keep = false
          val cnt = if (p.fillHull) hullOf(raw) else raw
          val area = Imgproc.contourArea(cnt)
          if (PanelMath.areaOk(area, v, p)) {
            val c2 = MatOfPoint2f(*cnt.toArray())
            val rect = Imgproc.minAreaRect(c2)
            c2.release()
            val pts = arrayOfNulls<Point>(4)
            rect.points(pts)
            val box = DoubleArray(8) { if (it % 2 == 0) pts[it / 2]!!.x else pts[it / 2]!!.y }
            val c = PanelMath.candidate(box, area, v, p)
            if (c != null && (best == null || c.score < best.score)) {
              best = c
              bestCnt?.release()
              bestCnt = cnt
              keep = true
            }
          }
          if (!keep) cnt.release()
        }
      }
      val c = best ?: return fail(PanelMath.REASON_NO_CANDIDATE)
      val xyYaw = PanelMath.pose(c, v, prevYaw, p)

      // gates: the ring round the blob must be inside the camera image and darker than the blob
      val maskIn = mat(Mat.zeros(n, n, CvType.CV_8UC1))
      Imgproc.drawContours(maskIn, listOf(bestCnt!!), -1, Scalar(1.0), -1)
      val outer = mat(Mat())
      val inner = mat(Mat())
      val kOuter = mat(Mat.ones(p.ringOuterPx, p.ringOuterPx, CvType.CV_8U))
      val kInner = mat(Mat.ones(p.ringInnerPx, p.ringInnerPx, CvType.CV_8U))
      Imgproc.dilate(maskIn, outer, kOuter)
      Imgproc.dilate(maskIn, inner, kInner)
      val inB = ByteArray(n * n)
      val outerB = ByteArray(n * n)
      val innerB = ByteArray(n * n)
      maskIn.get(0, 0, inB)
      outer.get(0, 0, outerB)
      inner.get(0, 0, innerB)
      var ring = 0
      var ringCut = 0
      var ringValid = 0
      var sumRing = 0.0
      var nIn = 0
      var sumIn = 0.0
      for (i in 0 until n * n) {
        if (inB[i].toInt() != 0) {
          nIn++
          sumIn += white[i]
        }
        if (outerB[i].toInt() != 0 && innerB[i].toInt() == 0) {
          ring++
          if (valid[i]) {
            ringValid++
            sumRing += white[i]
          } else ringCut++
        }
      }
      val cut = ringCut.toDouble() / max(1, ring)
      val contrast = if (ringValid > 0 && nIn > 0) sumIn / nIn - sumRing / ringValid else 0.0
      val reason = PanelMath.gate(c.errL, c.errS, c.rectness, cut, contrast, p)
      val corners = PanelMath.cornersPx(xyYaw[0], xyYaw[1], xyYaw[2], k.fx, k.fy, k.cx, k.cy, v, p)
      return PanelPoseResult(
          reason == null,
          reason ?: PanelMath.REASON_OK,
          xyYaw[0],
          xyYaw[1],
          xyYaw[2],
          corners,
          (System.nanoTime() - t0) / 1e6,
          doubleArrayOf(c.errL * 1000, c.errS * 1000),
          c.rectness,
          contrast,
          c.score,
          size,
      )
    } finally {
      bestCnt?.release()
      mats.forEach { it.release() }
    }
  }

  /**
   * FLAT-PARTS: every clean white rectangle lying on the surface, in one pass over one frame. The same rectified
   * view, whiteness and threshold sweep as [detect], rectified at [PanelParams.thickM] above the surface (16 mm:
   * the top of a board; a thinner board is measured about 1 % large). There is no size target: a blob is a
   * rectangle when it passes [PanelParams.minRectness], [RectGates]' size range, is fully in view (the camera
   * image: [PanelParams.maxCut]; the rectified window: [RectGates.borderPx]), stands out from the ring round it
   * ([PanelParams.minContrast]) and from the ring's strip on each of its four sides. [PanelMath.resolveRects] then
   * leaves one rectangle per real thing. Stateless, thread-safe; [detect] is not touched by it.
   */
  fun findRects(bgr: Mat, k0: Intrinsics, tableFromCamera: DoubleArray, p: PanelParams = PanelParams(), g: RectGates = RectGates(), t0: Long = System.nanoTime()): RectsResult {
    val size = intArrayOf(bgr.cols(), bgr.rows())
    fun done(reason: String, rects: List<FoundRect> = emptyList(), candidates: Int = 0) = RectsResult(rects, reason, (System.nanoTime() - t0) / 1e6, candidates, size)
    val k = k0.scaledTo(bgr.cols(), bgr.rows())
    val v = PanelMath.view(k.fx, k.fy, k.cx, k.cy, tableFromCamera, p) ?: return done(PanelMath.REASON_NOT_LOOKING_DOWN)
    val n = v.n
    val (valid, validCount) = PanelMath.validMask(v, bgr.cols(), bgr.rows(), p)
    if (validCount.toDouble() / (n * n) < p.minValidFraction) return done(PanelMath.REASON_NOT_IN_VIEW)

    val mats = ArrayList<Mat>()
    fun mat(m: Mat): Mat {
      mats += m
      return m
    }
    try {
      val hMat = mat(Mat(3, 3, CvType.CV_64F))
      hMat.put(0, 0, *v.h)
      val bird = mat(Mat())
      Imgproc.warpPerspective(bgr, bird, hMat, Size(n.toDouble(), n.toDouble()), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP)
      val blur = mat(Mat())
      Imgproc.GaussianBlur(bird, blur, Size(p.blurPx.toDouble(), p.blurPx.toDouble()), 0.0)
      val lab = mat(Mat())
      Imgproc.cvtColor(blur, lab, Imgproc.COLOR_BGR2Lab)
      val labBytes = ByteArray(n * n * 3)
      lab.get(0, 0, labBytes)
      val white = FloatArray(n * n)
      val vals = FloatArray(validCount)
      var vi = 0
      val cw = p.chromaWeight.toFloat()
      for (i in 0 until n * n) {
        if (!valid[i]) continue
        val l = (labBytes[3 * i].toInt() and 0xff).toFloat()
        val a = (labBytes[3 * i + 1].toInt() and 0xff) - 128f
        val b = (labBytes[3 * i + 2].toInt() and 0xff) - 128f
        val w = (l - cw * sqrt(a * a + b * b)).coerceIn(0f, 255f)
        white[i] = w
        vals[vi++] = w
      }
      vals.sort()
      val thresholds = p.percentiles.map { PanelMath.percentile(vals, it) }.distinct().sorted()

      val kernel = mat(Mat.ones(p.openPx, p.openPx, CvType.CV_8U))
      val kOuter = mat(Mat.ones(p.ringOuterPx, p.ringOuterPx, CvType.CV_8U))
      val kInner = mat(Mat.ones(p.ringInnerPx, p.ringInnerPx, CvType.CV_8U))
      val mask = mat(Mat(n, n, CvType.CV_8UC1))
      val opened = mat(Mat())
      val hierarchy = mat(Mat())
      val noHierarchy = mat(Mat())
      val maskBytes = ByteArray(n * n)
      val pxPerM2 = 1.0 / (v.s * v.s)
      val minArea = 0.7 * g.minLongM * g.minShortM * pxPerM2
      val maxArea = 1.3 * g.maxLongM * g.maxShortM * pxPerM2
      val pad = p.ringOuterPx / 2 + 2
      val found = ArrayList<FoundRect>()
      var birdBytes: ByteArray? = null
      for (thr in thresholds) {
        for (i in 0 until n * n) maskBytes[i] = if (white[i] > thr) 1 else 0
        mask.put(0, 0, maskBytes)
        Imgproc.morphologyEx(mask, opened, Imgproc.MORPH_OPEN, kernel)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(opened, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        for (cnt in contours) {
          try {
            val area = Imgproc.contourArea(cnt)
            if (area < minArea || area > maxArea) continue
            val c2 = MatOfPoint2f(*cnt.toArray())
            val rect = Imgproc.minAreaRect(c2)
            c2.release()
            val pts = arrayOfNulls<Point>(4)
            rect.points(pts)
            val box = DoubleArray(8) { if (it % 2 == 0) pts[it / 2]!!.x else pts[it / 2]!!.y }
            val c = PanelMath.candidate(box, area, v, p) ?: continue
            // candidate() measures against p's rectangle: the measured sides are the errors plus that rectangle
            val lengthM = c.errL + p.longM
            val widthM = c.errS + p.shortM
            if (c.rectness < p.minRectness || lengthM < g.minLongM || lengthM > g.maxLongM || widthM < g.minShortM || widthM > g.maxShortM) continue
            val br = Imgproc.boundingRect(cnt)
            if (br.x < g.borderPx || br.y < g.borderPx || br.x + br.width > n - g.borderPx || br.y + br.height > n - g.borderPx) continue
            // the ring round the blob, in a window round it: inside the camera image, darker than the blob, on every side
            val rx0 = max(0, br.x - pad)
            val ry0 = max(0, br.y - pad)
            val rw = min(n, br.x + br.width + pad) - rx0
            val rh = min(n, br.y + br.height + pad) - ry0
            val maskIn = Mat.zeros(rh, rw, CvType.CV_8UC1)
            val outer = Mat()
            val inner = Mat()
            val inB = ByteArray(rw * rh)
            val outerB = ByteArray(rw * rh)
            val innerB = ByteArray(rw * rh)
            try {
              Imgproc.drawContours(maskIn, listOf(cnt), -1, Scalar(1.0), -1, Imgproc.LINE_8, noHierarchy, Int.MAX_VALUE, Point(-rx0.toDouble(), -ry0.toDouble()))
              Imgproc.dilate(maskIn, outer, kOuter)
              Imgproc.dilate(maskIn, inner, kInner)
              maskIn.get(0, 0, inB)
              outer.get(0, 0, outerB)
              inner.get(0, 0, innerB)
            } finally {
              maskIn.release()
              outer.release()
              inner.release()
            }
            val e0 = sqrt((box[2] - box[0]) * (box[2] - box[0]) + (box[3] - box[1]) * (box[3] - box[1]))
            val e1 = sqrt((box[4] - box[2]) * (box[4] - box[2]) + (box[5] - box[3]) * (box[5] - box[3]))
            val halfL = max(e0, e1) / 2
            val halfS = min(e0, e1) / 2
            var ring = 0
            var ringCut = 0
            var nIn = 0
            var sumIn = 0.0
            val sideN = IntArray(4)
            val sideSum = DoubleArray(4)
            for (j in 0 until rh) {
              for (i in 0 until rw) {
                val q = j * rw + i
                val gi = (ry0 + j) * n + rx0 + i
                if (inB[q].toInt() != 0) {
                  nIn++
                  sumIn += white[gi]
                }
                if (outerB[q].toInt() != 0 && innerB[q].toInt() == 0) {
                  ring++
                  if (!valid[gi]) {
                    ringCut++
                    continue
                  }
                  val dx = rx0 + i - c.ctr[0]
                  val dy = ry0 + j - c.ctr[1]
                  val u = dx * c.al[0] + dy * c.al[1]
                  val w = dx * c.asv[0] + dy * c.asv[1]
                  val side = if (abs(u) - halfL > abs(w) - halfS) (if (u > 0) 0 else 1) else (if (w > 0) 2 else 3)
                  sideN[side]++
                  sideSum[side] += white[gi]
                }
              }
            }
            if (nIn == 0 || ring == 0) continue
            if (ringCut.toDouble() / ring > p.maxCut) continue
            val ringValid = sideN.sum()
            if (ringValid == 0) continue
            val meanIn = sumIn / nIn
            val contrast = meanIn - sideSum.sum() / ringValid
            if (contrast < p.minContrast) continue
            val minSide = p.minContrast * g.sideContrastShare
            if ((0 until 4).any { sideN[it] == 0 || meanIn - sideSum[it] / sideN[it] < minSide }) continue
            // Measured again at the level half-way between the blob and its ring: the sweep's threshold may sit near
            // the surface's level (the blob grows by the blur, about 15 mm) or near the board's (it shrinks); half-way
            // is where the edge is. A blob that grows past its window at that level has joined something: dropped.
            val mid = meanIn - contrast / 2
            val subB = ByteArray(rw * rh)
            for (j in 0 until rh) {
              val row = (ry0 + j) * n + rx0
              for (i in 0 until rw) subB[j * rw + i] = if (white[row + i] > mid) 1 else 0
            }
            val sub = Mat(rh, rw, CvType.CV_8UC1)
            val subOpen = Mat()
            val subCnts = ArrayList<MatOfPoint>()
            try {
              sub.put(0, 0, subB)
              Imgproc.morphologyEx(sub, subOpen, Imgproc.MORPH_OPEN, kernel)
              Imgproc.findContours(subOpen, subCnts, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            } finally {
              sub.release()
              subOpen.release()
            }
            var fine: PanelCandidate? = null
            val cxr = c.ctr[0] - rx0
            val cyr = c.ctr[1] - ry0
            for (sc in subCnts) {
              val b2 = Imgproc.boundingRect(sc)
              val a2 = Imgproc.contourArea(sc)
              if (fine == null && a2 >= 0.5 * area && cxr >= b2.x && cxr <= b2.x + b2.width && cyr >= b2.y && cyr <= b2.y + b2.height &&
                  b2.x > 0 && b2.y > 0 && b2.x + b2.width < rw && b2.y + b2.height < rh) {
                val sc2 = MatOfPoint2f(*sc.toArray())
                val rr = Imgproc.minAreaRect(sc2)
                sc2.release()
                val pp = arrayOfNulls<Point>(4)
                rr.points(pp)
                fine = PanelMath.candidate(DoubleArray(8) { if (it % 2 == 0) pp[it / 2]!!.x + rx0 else pp[it / 2]!!.y + ry0 }, a2, v, p)
              }
              sc.release()
            }
            val cf = fine ?: continue
            val fineL = cf.errL + p.longM
            val fineW = cf.errS + p.shortM
            if (cf.rectness < p.minRectness || fineL < g.minLongM || fineL > g.maxLongM || fineW < g.minShortM || fineW > g.maxShortM) continue
            // two boards lying side by side are not one board
            val rectified = birdBytes ?: ByteArray(n * n * 3).also { bird.get(0, 0, it); birdBytes = it }
            if (PanelMath.seamDip(rectified, n, cf, g.seamMinBoardM / v.s) >= g.seamShare * contrast) continue
            val measured = p.copy(longM = fineL, shortM = fineW)
            val xyYaw = PanelMath.pose(cf, v, null, measured) // anchored at the far corner, never smeared
            found +=
                FoundRect(xyYaw[0], xyYaw[1], xyYaw[2], fineL, fineW, cf.rectness, contrast,
                    PanelMath.cornersPx(xyYaw[0], xyYaw[1], xyYaw[2], k.fx, k.fy, k.cx, k.cy, v, measured),
                    PanelMath.rectCorners(xyYaw[0], xyYaw[1], xyYaw[2], fineL, fineW), p.thickM)
          } finally {
            cnt.release()
          }
        }
      }
      return done(PanelMath.REASON_OK, PanelMath.resolveRects(found, g), found.size)
    } finally {
      mats.forEach { it.release() }
    }
  }
}

// ---- FLAT-PARTS: every clean white rectangle on the surface (world/FlatParts.kt says which part each one is) ----

/**
 * The gates of [PanelPose.findRects]: a plausible board, no size target (rectness, contrast and "fully in view" are
 * [PanelParams]', as for the single target).
 */
data class RectGates(
    val minLongM: Double = 0.150,
    val maxLongM: Double = 0.700,
    val minShortM: Double = 0.050,
    val maxShortM: Double = 0.600,
    /**
     * Each of the four sides must stand out from its own strip of the ring by this share of [PanelParams.minContrast]:
     * a real edge against the surface on every side. Rejects the brighter half of one board (one "edge" is only a
     * change of light) and a board lying against another white thing.
     */
    val sideContrastShare: Double = 0.5,
    /** A blob this close to the edge of the rectified window is cut by the window: its size is not the part's. */
    val borderPx: Int = 2,
    /**
     * Two boards lying side by side read as one wide rectangle (two 450 × 100 sides against each other are about the
     * 400 × 200 front). A dark line along the length, darker than the board by this share of the rectangle's
     * contrast, with room for a board ([seamMinBoardM]) on either side of it, is the gap between two boards: no
     * rectangle. 80 mm: a 100 mm board's own groove down its middle (the drawer sides have one) is not looked at.
     */
    val seamShare: Double = 0.15,
    val seamMinBoardM: Double = 0.080,
    /** The same blob at two thresholds: centres this close and both sides this close. */
    val sameCentreM: Double = 0.030,
    val sameSizeM: Double = 0.040,
)

/**
 * One clean white rectangle on the surface, TABLE frame of the call.
 *
 * @property x @property y centre, metres. @property yaw of the long side, radians in [0, π).
 * @property lengthM @property widthM measured sides after the smear correction (length ≥ width).
 * @property cornersPx the top-face corners in the input image (x0, y0, … x3, y3), order (−,−), (+,−), (+,+), (−,+)
 *   along (long, short). @property cornersTable the same corners in the TABLE frame (x0, y0, …), at height [topM].
 */
class FoundRect(
    val x: Double,
    val y: Double,
    val yaw: Double,
    val lengthM: Double,
    val widthM: Double,
    val rectness: Double,
    val contrast: Double,
    val cornersPx: DoubleArray,
    val cornersTable: DoubleArray,
    val topM: Double,
) {
  /** Is the TABLE point ([px], [py]) inside the rectangle? */
  fun contains(px: Double, py: Double): Boolean {
    val dx = px - x
    val dy = py - y
    val c = cos(yaw)
    val s = sin(yaw)
    return abs(dx * c + dy * s) <= lengthM / 2 && abs(-dx * s + dy * c) <= widthM / 2
  }
}

/** One frame's rectangles. [reason]: "ok" or why nothing was looked at. [candidates]: blobs that passed every gate, before [PanelMath.resolveRects]. */
class RectsResult(val rects: List<FoundRect>, val reason: String, val ms: Double, val candidates: Int = 0, val imageSize: IntArray? = null)

/** One trusted result: panel centre and yaw in the TABLE frame of a table at WORLD height [tableY]. */
data class PanelSample(val x: Double, val y: Double, val yaw: Double, val tableY: Float, val score: Double, val tMs: Long)

/**
 * "Move the ghost only after 3 trusted results in a row that agree within 10 mm and 3°, and use their median"
 * (FRONT_PANEL.md). An untrusted result breaks the row; a sample older than [maxAgeMs] is forgotten. Yaw is compared
 * modulo 180° and the median is taken near the newest sample's yaw. Pure, not thread-safe.
 */
class PanelConsensus(
    val needed: Int = 3,
    val maxPosM: Double = 0.010,
    val maxYawDeg: Double = 3.0,
    val maxAgeMs: Long = 3000,
) {
  private val window = ArrayDeque<PanelSample>()

  val streak: Int
    get() = window.size

  fun reset() = window.clear()

  /** [s] null = an untrusted result. Returns the median of the last [needed] samples when they agree, else null. */
  fun add(s: PanelSample?): PanelSample? {
    if (s == null) {
      window.clear()
      return null
    }
    window.addLast(s)
    while (window.size > needed || (window.isNotEmpty() && s.tMs - window.first().tMs > maxAgeMs)) window.removeFirst()
    if (window.size < needed) return null
    val w = window.toList()
    for (i in w.indices) for (j in i + 1 until w.size) {
      val dx = w[i].x - w[j].x
      val dy = w[i].y - w[j].y
      if (sqrt(dx * dx + dy * dy) > maxPosM) return null
      if (Math.toDegrees(yawDiffMod180(w[i].yaw, w[j].yaw)) > maxYawDeg) return null
    }
    // bring every yaw within ±90° of the newest before the median
    val yaws = w.map { s.yaw + wrapHalfPi(it.yaw - s.yaw) }
    return PanelSample(median(w.map { it.x }), median(w.map { it.y }), median(yaws), median(w.map { it.tableY.toDouble() }).toFloat(),
        median(w.map { it.score }), s.tMs)
  }

  companion object {
    fun median(v: List<Double>): Double = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    /** [d] modulo π into (−π/2, π/2]. */
    fun wrapHalfPi(d: Double): Double {
      val m = d.mod(Math.PI)
      return if (m > Math.PI / 2) m - Math.PI else m
    }

    /** |a − b| modulo 180°, radians (0..π/2). */
    fun yawDiffMod180(a: Double, b: Double): Double = abs(wrapHalfPi(a - b))
  }
}

/**
 * Runs [PanelPose] at [hz] on its own thread while [enabled], the job is `malm-drawer`, the step has a [PanelTarget]
 * ([setStep]), the table height is known and the camera looks down at the table; reports WORLD ← ASSEMBLY after
 * [PanelConsensus] agrees.
 *
 * Wiring (MainActivity; see quest-android/README.md "Markerless front panel"):
 * ```
 * private var panel: PanelPoseTracker? = null
 * // onCreate, after `world = WorldModule(…)`:
 * if (params.markerless) panel = PanelPoseTracker(
 *     tableHeight = { world.table?.y },                    // MRUK table surface, WORLD height
 *     ghostPose = { vision.worldFromAssembly() },          // what the ghost shows now → the 180° choice
 *     onObjectPose = { pose, confidence, source -> world.onObjectPose(pose, confidence, source) })
 * // openJob(), after world.setAssembly(a):
 * panel?.setJob(a.jobId)                                   // stays off unless malm-drawer
 * // visionListener.onFrame, first line (camera thread):
 * panel?.onFrame(frame)
 * // showStep(), after world.setAction(…): the flat base part of this step, or off where there is none
 * panel?.setStep(step, assembly?.let { a -> a.guide?.let { g -> StepFootprints.target(g, a.manifest, step) } })
 * // onDestroy:
 * panel?.stop()
 * ```
 * [onFrame] runs on the camera thread: it only checks the rate and the pose and, when a detection is due, encodes
 * one colour JPEG ([grabLongEdge], the only colour path [CameraFrame] has) and hands it to the worker thread.
 * [onObjectPose] is called on the worker thread (`WorldModule.onObjectPose` takes any thread). It is called
 * again only when the median moved more than [reportMinMoveM] / [reportMinTurnDeg] or every [reportRefreshMs], so
 * the ghost does not shimmer with the detector's noise.
 *
 * Logs AGQ `panel` at most once a second: `trusted`, `reason`, `ms`, `pos_m` (WORLD, panel centre on the table),
 * `xy_m`, `yaw_deg`, `size_err_mm`, `rectness`, `contrast`, `streak`, `reports`, `table_y_m`, `img`, and what it is
 * looking for: `on` (true), `step`, `footprint_mm` (length, width, top height), `hull`. [setStep] logs one more
 * `panel` line whenever the step's target changes: `on` (false when the step has no flat base part, the switch is
 * off or the job is another), `step`, `footprint_mm`, `hull`, `slab`, `trusted` false and the `reason` in words.
 *
 * @param tableHeight WORLD height of the table surface (the world module's MRUK table), null = unknown.
 * @param ghostPose WORLD ← ASSEMBLY the ghost shows now, for the 180° choice; null = the tracker's own last yaw.
 */
class PanelPoseTracker(
    private val tableHeight: () -> Float?,
    private val ghostPose: () -> Pose3? = { null },
    private val onObjectPose: (worldFromAssembly: Pose3, confidence: Float, source: String) -> Unit,
    private val log: AgqLog = Agq.log,
    private val params: PanelParams = PanelParams(),
    private val hz: Double = 3.0,
    private val grabLongEdge: Int = 1280,
    private val grabQuality: Int = 85,
    private val reportMinMoveM: Double = 0.003,
    private val reportMinTurnDeg: Double = 0.5,
    private val reportRefreshMs: Long = 5000,
    private val consensus: PanelConsensus = PanelConsensus(),
    private val loadNative: () -> Boolean = { org.opencv.android.OpenCVLoader.initLocal() },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  /** Switch for the launch extra (and, where [setStep] is not wired yet, for the steps after the first). */
  @Volatile var enabled = true

  /** What to look for at the step shown now; null = off for this step. Before the first [setStep]: the drawer front alone. */
  @Volatile var target: PanelTarget? = PanelTarget.FRONT_PANEL
    private set

  @Volatile private var step: Int? = null
  private var loggedState: Any? = null

  @Volatile private var job: String? = null
  private val busy = AtomicBoolean(false)
  private var worker: ExecutorService? = null
  @Volatile private var lastSubmitMs = Long.MIN_VALUE / 2
  private var lastLogMs = Long.MIN_VALUE / 2
  private var lastYaw: Double? = null
  private var lastReported: PanelSample? = null
  private var reports = 0
  private var nativeOk: Boolean? = null

  /** The latest reported WORLD ← ASSEMBLY, null before the first report. */
  @Volatile var latest: Pose3? = null
    private set

  /** The job shown now; the tracker runs for any job whose current step has a target ([setStep]). */
  fun setJob(jobId: String?) {
    if (jobId != job) {
      job = jobId
      // only the old drawer job has a built-in target; every other guide gets its target from [setStep]
      target = if (jobId == PanelFrames.MALM_JOB) PanelTarget.FRONT_PANEL else null
      synchronized(this) {
        consensus.reset()
        lastReported = null
        lastYaw = null
      }
      latest = null
    }
  }

  val active: Boolean
    get() = enabled && job != null && target != null // any guide whose step has a flat base part (StepFootprints)

  /**
   * The step shown now and its target ([StepFootprints.target]); null [target] = no flat base part at this step, the
   * tracker rests and the ghost keeps its last pose. A different rectangle starts a new three-in-a-row; the same one
   * (the drawer front in steps 1–3) carries on. Logs `panel` once per change. Any thread.
   */
  fun setStep(step: Int?, target: PanelTarget?) {
    val old = this.target
    this.step = step
    this.target = target
    if (target == null || !target.sameRectangle(old)) {
      synchronized(this) {
        consensus.reset()
        lastReported = null
        lastYaw = null
      }
    }
    val state = listOf(active, step, target)
    synchronized(this) {
      if (state == loggedState) return
      loggedState = state
    }
    log.event(
        "panel",
        mapOf(
            "on" to active,
            "step" to step,
            "footprint_mm" to target?.footprintMm(),
            "hull" to target?.fillHull,
            "slab" to target?.slab,
            "trusted" to false,
            "reason" to
                when {
                  target == null -> REASON_NO_TARGET
                  !enabled -> "tracker switched off"
                  job == null -> "tracker off: no guide loaded"
                  else -> "tracker on"
                },
        ),
    )
  }

  // FLAT-PARTS: every clean rectangle on the surface (PanelPose.findRects), in the same pass and from the same decoded
  // picture as the target above, and also on steps (and jobs) where the target is off. [rectsWanted] is asked on
  // the camera thread; [onRects] gets each pass's result with its WORLD ← TABLE on the worker thread (null table:
  // the pass did not run: no camera pose or no surface height).
  @Volatile var rectsWanted: () -> Boolean = { false }
  @Volatile var onRects: ((RectsResult, Pose3?) -> Unit)? = null

  /**
   * The rectangles' pass uses this tracker's own [params] object (so the lightness-only setting and every other
   * constant tuned on the device hold for both), with the default rectangle's top face, 16 mm above the surface,
   * whatever the step's target is.
   */
  private val rectParams: PanelParams
    get() = params

  /** Camera thread. Cheap unless a detection is due. */
  fun onFrame(frame: CameraFrame) {
    val sink = onRects?.takeIf { rectsWanted() } // FLAT-PARTS
    val t = if (active) target else null
    if (t == null && sink == null) return
    val p = t?.params(params)
    val now = clockMs()
    if (now - lastSubmitMs < (1000.0 / hz).toLong() || busy.get()) return
    val wc = frame.worldFromCamera
    val y = tableHeight()
    val skip =
        when {
          wc == null -> "no camera pose"
          y == null -> "no table"
          else -> null
        }
    if (skip != null) {
      lastSubmitMs = now
      if (t != null) onResult(PanelPoseResult(false, skip, null, null, null, null, 0.0), null, now)
      sink?.invoke(RectsResult(emptyList(), skip, 0.0), null) // FLAT-PARTS
      return
    }
    val wt = PanelFrames.tableAtHeight(y!!)
    val tc = PanelMath.tableFromCamera(wc!!, wt)
    lastSubmitMs = now
    val look = t != null && PanelMath.looksDown(tc, p!!)
    if (t != null && !look) onResult(PanelPoseResult(false, PanelMath.REASON_NOT_LOOKING_DOWN, null, null, null, null, 0.0), wt, now, t)
    // FLAT-PARTS
    val rectsLook = sink != null && PanelMath.looksDown(tc, rectParams)
    if (sink != null && !rectsLook) sink(RectsResult(emptyList(), PanelMath.REASON_NOT_LOOKING_DOWN, 0.0), wt)
    if (!look && !rectsLook) return
    if (!busy.compareAndSet(false, true)) return
    try {
      val jpeg = frame.jpegAt(grabLongEdge, grabQuality) // live buffers are only valid inside this callback
      val k = frame.intrinsics
      val prev = if (look) ghostPose()?.let { PanelFrames.panelYawInTable(wt, it, t!!.assemblyFromFootprint) } ?: synchronized(this) { lastYaw } else null
      val w = worker ?: Executors.newSingleThreadExecutor { Thread(it, "agj-panel").apply { isDaemon = true } }.also { worker = it }
      w.execute {
        try {
          if (nativeOk == null) nativeOk = runCatching { loadNative() }.getOrDefault(false).also { if (!it) log.error("panel", "OpenCV native library did not load") }
          if (nativeOk == true) {
            if (!rectsLook) {
              onResult(PanelPose.detectJpeg(jpeg, k, tc, prev, p!!), wt, clockMs(), t)
            } else {
              // FLAT-PARTS: one decode for both the step's target and the rectangles
              val t0 = System.nanoTime()
              val buf = MatOfByte(*jpeg)
              val bgr = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
              buf.release()
              try {
                if (bgr.empty()) {
                  if (look) onResult(PanelPoseResult(false, "image did not decode", null, null, null, null, (System.nanoTime() - t0) / 1e6), wt, clockMs(), t)
                  sink!!(RectsResult(emptyList(), "image did not decode", (System.nanoTime() - t0) / 1e6), wt)
                } else {
                  if (look) onResult(PanelPose.detect(bgr, k, tc, prev, p!!, t0), wt, clockMs(), t)
                  sink!!(PanelPose.findRects(bgr, k, tc, rectParams), wt)
                }
              } finally {
                bgr.release()
              }
            }
          }
        } catch (t: Throwable) {
          log.error("panel", t.message)
        } finally {
          busy.set(false)
        }
      }
    } catch (t: Throwable) {
      busy.set(false)
      log.error("panel", t.message)
    }
  }

  /**
   * One detector result → consensus → callback and log. Public for the JVM tests (no OpenCV needed).
   * [worldFromTable] the table frame the result is in (null when the detector did not run). [t] the target the
   * frame was searched for: a result for a rectangle that is no longer the step's is dropped.
   */
  @Synchronized
  fun onResult(r: PanelPoseResult, worldFromTable: Pose3?, nowMs: Long, t: PanelTarget? = target) {
    if (t == null || !t.sameRectangle(target)) return
    val sample =
        if (r.trusted && r.x != null && r.y != null && r.yaw != null && worldFromTable != null)
            PanelSample(r.x, r.y, r.yaw, worldFromTable.t.y, r.score ?: 0.0, nowMs)
        else null
    if (sample != null) lastYaw = sample.yaw
    val agreed = consensus.add(sample)
    if (agreed != null && shouldReport(agreed, nowMs)) {
      lastReported = agreed
      reports++
      val pose = PanelFrames.worldFromAssembly(PanelFrames.tableAtHeight(agreed.tableY), agreed.x, agreed.y, agreed.yaw, t.assemblyFromFootprint)
      latest = pose
      // trusted ⇒ score ≤ 2 (each side within its tolerance): 1.0 = exact size, 0.5 = at both tolerances
      val confidence = (1.0 - 0.25 * agreed.score).coerceIn(0.5, 1.0).toFloat()
      try {
        onObjectPose(pose, confidence, SOURCE)
      } catch (t: Throwable) {
        log.error("panel", t.message)
      }
    }
    if (nowMs - lastLogMs >= 1000) {
      lastLogMs = nowMs
      val pos = if (r.x != null && r.y != null && worldFromTable != null) worldFromTable.transform(Vec3(r.x.toFloat(), r.y.toFloat(), 0f)) else null
      log.event(
          "panel",
          mapOf(
              "trusted" to r.trusted,
              "reason" to r.reason,
              "ms" to Math.round(r.ms * 10) / 10.0,
              "pos_m" to pos,
              "xy_m" to (if (r.x != null && r.y != null) listOf(r.x, r.y) else null),
              "yaw_deg" to r.yaw?.let { Math.toDegrees(it) },
              "size_err_mm" to r.sizeErrMm,
              "rectness" to r.rectness,
              "contrast" to r.contrast,
              "streak" to consensus.streak,
              "reports" to reports,
              "table_y_m" to worldFromTable?.t?.y,
              "img" to r.imageSize,
              "on" to true,
              "step" to step,
              "footprint_mm" to t.footprintMm(),
              "hull" to t.fillHull,
          ),
      )
    }
  }

  private fun shouldReport(s: PanelSample, nowMs: Long): Boolean {
    val last = lastReported ?: return true
    val dx = s.x - last.x
    val dy = s.y - last.y
    return sqrt(dx * dx + dy * dy) > reportMinMoveM ||
        Math.toDegrees(abs(angleDiff(s.yaw, last.yaw))) > reportMinTurnDeg ||
        abs(s.tableY - last.tableY) > reportMinMoveM ||
        nowMs - last.tMs >= reportRefreshMs
  }

  private fun angleDiff(a: Double, b: Double): Double = (a - b + Math.PI).mod(2 * Math.PI) - Math.PI

  fun stop() {
    worker?.shutdownNow()
    worker = null
    busy.set(false)
  }

  companion object {
    const val SOURCE = "markerless"
    const val REASON_NO_TARGET = "tracker off: no flat base part at this step"
  }
}
