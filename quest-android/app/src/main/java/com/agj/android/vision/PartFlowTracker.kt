package com.agj.android.vision

import kotlin.math.hypot
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/**
 * The arithmetic of [PartFlowTracker], without OpenCV types (JVM-tested without the native library).
 *
 * Pixels are OpenCV's (pixel `i` covers `[i − 0.5, i + 0.5]`), so a continuous coordinate `x` of the full image is
 * `(x + 0.5)·s − 0.5` in the image downscaled by `s`. Boxes are `(u0, v0, u1, v1)`.
 */
object FlowMath {
  fun toSmall(v: Double, s: Double): Double = (v + 0.5) * s - 0.5

  fun toFull(v: Double, s: Double): Double = (v + 0.5) / s - 0.5

  fun boxToSmall(b: DoubleArray, s: Double): DoubleArray = DoubleArray(4) { toSmall(b[it], s) }

  fun boxToFull(b: DoubleArray, s: Double): DoubleArray = DoubleArray(4) { toFull(b[it], s) }

  /**
   * Where features are looked for: the box grown by [pad] of its size on every side and to at least [minSide] px a
   * side (small hardware has few corners of its own: its outline and shadow just outside the box help), clipped to
   * the [w] × [h] image. `(x, y, width, height)`; null when the centre is off the image or under 6 px remain.
   */
  fun featureRegion(b: DoubleArray, w: Int, h: Int, pad: Double = 0.15, minSide: Double = 24.0): IntArray? {
    val cx = (b[0] + b[2]) / 2
    val cy = (b[1] + b[3]) / 2
    if (cx < 0 || cy < 0 || cx > w - 1 || cy > h - 1) return null
    val hw = maxOf((b[2] - b[0]) * (0.5 + pad), minSide / 2)
    val hh = maxOf((b[3] - b[1]) * (0.5 + pad), minSide / 2)
    val x0 = Math.floor(cx - hw).toInt().coerceIn(0, w - 1)
    val y0 = Math.floor(cy - hh).toInt().coerceIn(0, h - 1)
    val x1 = Math.ceil(cx + hw).toInt().coerceIn(0, w - 1)
    val y1 = Math.ceil(cy + hh).toInt().coerceIn(0, h - 1)
    if (x1 - x0 < 6 || y1 - y0 < 6) return null
    return intArrayOf(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
  }

  fun iou(a: DoubleArray, b: DoubleArray): Double {
    val iw = minOf(a[2], b[2]) - maxOf(a[0], b[0])
    val ih = minOf(a[3], b[3]) - maxOf(a[1], b[1])
    if (iw <= 0 || ih <= 0) return 0.0
    val inter = iw * ih
    val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return if (union <= 0) 0.0 else inter / union
  }

  /** The box's motion from its points: [dx], [dy]; [keep] marks the points that agree with it ([kept] of them). */
  class Flow(val dx: Double, val dy: Double, val keep: BooleanArray, val kept: Int)

  private fun median(v: DoubleArray, n: Int): Double {
    val s = v.copyOf(n)
    s.sort()
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
  }

  /**
   * Median flow. A point counts when Lucas–Kanade found it forward ([okF]) and backward ([okB]) and tracking it back
   * lands within [fbMaxPx] of where it started ([back] vs [p0]). The motion is the median of those points' moves;
   * points farther than max([outlierPx], 3 × the median deviation) from it are dropped (they sit on something else:
   * the desk, a hand) and the motion is the mean of the rest. Points are flat `(x0, y0, x1, y1, …)`. Null when no
   * point counts.
   */
  fun medianFlow(
      p0: FloatArray,
      p1: FloatArray,
      back: FloatArray,
      okF: ByteArray,
      okB: ByteArray,
      fbMaxPx: Double = 1.5,
      outlierPx: Double = 2.0,
  ): Flow? {
    val n = p0.size / 2
    val valid = BooleanArray(n)
    val dx = DoubleArray(n)
    val dy = DoubleArray(n)
    var m = 0
    for (i in 0 until n) {
      if (okF[i].toInt() == 0 || okB[i].toInt() == 0) continue
      val fb = hypot((back[2 * i] - p0[2 * i]).toDouble(), (back[2 * i + 1] - p0[2 * i + 1]).toDouble())
      if (!(fb <= fbMaxPx)) continue
      valid[i] = true
      dx[m] = (p1[2 * i] - p0[2 * i]).toDouble()
      dy[m] = (p1[2 * i + 1] - p0[2 * i + 1]).toDouble()
      m++
    }
    if (m == 0) return null
    val mx = median(dx, m)
    val my = median(dy, m)
    val dev = DoubleArray(m) { hypot(dx[it] - mx, dy[it] - my) }
    val limit = maxOf(outlierPx, 3 * median(dev, m))
    val keep = BooleanArray(n)
    var sx = 0.0
    var sy = 0.0
    var kept = 0
    var j = 0
    for (i in 0 until n) {
      if (!valid[i]) continue
      if (dev[j] <= limit) {
        keep[i] = true
        sx += dx[j]
        sy += dy[j]
        kept++
      }
      j++
    }
    return if (kept == 0) null else Flow(sx / kept, sy / kept, keep, kept)
  }

  /** How far the points spread: the median distance from their median point (px). 0 for fewer than 2 points. */
  fun spread(pts: FloatArray): Double {
    val n = pts.size / 2
    if (n < 2) return 0.0
    val mx = median(DoubleArray(n) { pts[2 * it].toDouble() }, n)
    val my = median(DoubleArray(n) { pts[2 * it + 1].toDouble() }, n)
    return median(DoubleArray(n) { hypot(pts[2 * it] - mx, pts[2 * it + 1] - my) }, n)
  }

  /**
   * Why a track is lost, or null: `few_points` (under [minPoints] survive), `off_image` (the box centre left the
   * [w] × [h] image), `collapsed` / `exploded` (the points' [spread] is under 0.3 × or over 3 × what it was when
   * seeded: they slid onto one feature, or scattered).
   */
  fun lossReason(box: DoubleArray, w: Int, h: Int, points: Int, minPoints: Int, spread: Double, seedSpread: Double): String? {
    if (points < minPoints) return "few_points"
    val cx = (box[0] + box[2]) / 2
    val cy = (box[1] + box[3]) / 2
    if (cx < 0 || cy < 0 || cx > w - 1 || cy > h - 1) return "off_image"
    if (seedSpread > 1.5) {
      if (spread < 0.3 * seedSpread) return "collapsed"
      if (spread > 3 * seedSpread) return "exploded"
    }
    return null
  }
}

/**
 * Follows boxes in the camera picture between server answers: pyramidal Lucas–Kanade on good-features inside each
 * box, median flow with a forward–backward check ([FlowMath.medianFlow]), translation only. Works on the grey (Y)
 * image downscaled to [SMALL_EDGE] px; boxes and results are in pixels of the full image.
 *
 * One call per camera frame ([process]) does every track with two `calcOpticalFlowPyrLK` calls. A track is lost
 * ([FlowMath.lossReason]) when too few points survive, its box leaves the image, or its points collapse / scatter;
 * a lost track is reported once (`Obs.lost`) and forgotten. Not thread-safe: one thread calls [process].
 *
 * Uses only the OpenCV Java API, so it runs on the device and in JVM tests (desktop bindings); loading the native
 * library is the caller's job (on the device [MarkerTracker] has loaded it before the first frame gets here).
 */
class PartFlowTracker(
    private val maxPerTrack: Int = 24,
    private val maxTotal: Int = 160,
    private val minPoints: Int = 4,
    private val refreshAfterNs: Long = 400_000_000L,
) {
  /** Start (or restart) track [key] on [box], full-image pixels, in the frame given to the same [process] call. */
  class Seed(val key: Int, val box: DoubleArray)

  /** Track [key] in this frame: box centre ([cx], [cy]) in full-image pixels; [lost] = why it ended now. */
  class Obs(val key: Int, val cx: Double, val cy: Double, val points: Int, val lost: String?)

  class Result(val obs: List<Obs>, val ms: Double, val seeded: Int, val points: Int)

  private class T(val key: Int, var box: DoubleArray, var pts: FloatArray, var seedN: Int, var seedSpread: Double, var refreshNs: Long)

  private val tracks = LinkedHashMap<Int, T>()
  private var prev: Mat? = null
  private val win = Size(15.0, 15.0)
  private val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 12, 0.03)

  val size: Int
    get() = tracks.size

  /**
   * One frame. [cur] is the downscaled grey image (this class keeps it until the next call and releases it);
   * [scale] = its width / the full image's width. [drops] are forgotten, then the live tracks are followed from
   * the previous frame, then [seeds] are started on this frame.
   */
  fun process(cur: Mat, scale: Double, seeds: List<Seed>, drops: List<Int>, nowNs: Long = System.nanoTime()): Result {
    val t0 = System.nanoTime()
    drops.forEach { tracks.remove(it) }
    val out = LinkedHashMap<Int, Obs>()
    val w = cur.cols()
    val h = cur.rows()
    val p = prev
    val live = tracks.values.filter { it.pts.isNotEmpty() }
    if (p != null && live.isNotEmpty() && p.cols() == w && p.rows() == h) {
      val n = live.sumOf { it.pts.size / 2 }
      val all = FloatArray(2 * n)
      var o = 0
      for (t in live) {
        System.arraycopy(t.pts, 0, all, o, t.pts.size)
        o += t.pts.size
      }
      val p0 = MatOfPoint2f()
      p0.alloc(n)
      p0.put(0, 0, all)
      val p1 = MatOfPoint2f()
      val pb = MatOfPoint2f()
      val st = MatOfByte()
      val stB = MatOfByte()
      val err = MatOfFloat()
      try {
        Video.calcOpticalFlowPyrLK(p, cur, p0, p1, st, err, win, 3, criteria)
        Video.calcOpticalFlowPyrLK(cur, p, p1, pb, stB, err, win, 3, criteria)
        val a1 = FloatArray(2 * n)
        val ab = FloatArray(2 * n)
        val s1 = ByteArray(n)
        val sb = ByteArray(n)
        p1.get(0, 0, a1)
        pb.get(0, 0, ab)
        st.get(0, 0, s1)
        stB.get(0, 0, sb)
        o = 0
        for (t in live) {
          val m = t.pts.size / 2
          val f =
              FlowMath.medianFlow(t.pts, a1.copyOfRange(2 * o, 2 * (o + m)), ab.copyOfRange(2 * o, 2 * (o + m)),
                  s1.copyOfRange(o, o + m), sb.copyOfRange(o, o + m))
          var why: String? = "few_points"
          if (f != null) {
            val kept = FloatArray(2 * f.kept)
            var j = 0
            for (i in 0 until m) if (f.keep[i]) {
              kept[j++] = a1[2 * (o + i)]
              kept[j++] = a1[2 * (o + i) + 1]
            }
            t.pts = kept
            t.box = doubleArrayOf(t.box[0] + f.dx, t.box[1] + f.dy, t.box[2] + f.dx, t.box[3] + f.dy)
            why = FlowMath.lossReason(t.box, w, h, f.kept, minPoints, FlowMath.spread(kept), t.seedSpread)
          }
          o += m
          val cx = FlowMath.toFull((t.box[0] + t.box[2]) / 2, scale)
          val cy = FlowMath.toFull((t.box[1] + t.box[3]) / 2, scale)
          if (why != null) {
            tracks.remove(t.key)
            out[t.key] = Obs(t.key, cx, cy, t.pts.size / 2, why)
            continue
          }
          // points wear away (hands, blur): look for fresh ones in the box where it is now
          if (t.pts.size / 2 < maxOf(minPoints + 2, t.seedN / 2) && nowNs - t.refreshNs >= refreshAfterNs) {
            t.refreshNs = nowNs
            val fresh = detect(cur, t.box, maxPerTrack)
            if (fresh.size / 2 > t.pts.size / 2) {
              t.pts = fresh
              t.seedN = fresh.size / 2
              t.seedSpread = FlowMath.spread(fresh)
            }
          }
          out[t.key] = Obs(t.key, cx, cy, t.pts.size / 2, null)
        }
      } finally {
        p0.release(); p1.release(); pb.release(); st.release(); stB.release(); err.release()
      }
    }
    var seeded = 0
    for (s in seeds) {
      val box = FlowMath.boxToSmall(s.box, scale)
      val cap = (maxTotal / (tracks.size + 1)).coerceIn(8, maxPerTrack)
      val pts = detect(cur, box, cap)
      val cx = FlowMath.toFull((box[0] + box[2]) / 2, scale)
      val cy = FlowMath.toFull((box[1] + box[3]) / 2, scale)
      if (pts.size / 2 < minPoints) {
        tracks.remove(s.key)
        out[s.key] = Obs(s.key, cx, cy, pts.size / 2, "no_features")
      } else {
        tracks[s.key] = T(s.key, box, pts, pts.size / 2, FlowMath.spread(pts), nowNs)
        out[s.key] = Obs(s.key, cx, cy, pts.size / 2, null)
        seeded++
      }
    }
    prev?.release()
    prev = cur
    return Result(out.values.toList(), (System.nanoTime() - t0) / 1e6, seeded, tracks.values.sumOf { it.pts.size / 2 })
  }

  /** Shi–Tomasi corners in the box's [FlowMath.featureRegion], flat (x, y, …) in pixels of [img]. */
  private fun detect(img: Mat, box: DoubleArray, max: Int): FloatArray {
    val r = FlowMath.featureRegion(box, img.cols(), img.rows()) ?: return FloatArray(0)
    val roi = img.submat(Rect(r[0], r[1], r[2], r[3]))
    val corners = MatOfPoint()
    try {
      // the quality level is relative to the best corner in the region, so a white part on a pale desk still gives
      // its own outline's corners
      Imgproc.goodFeaturesToTrack(roi, corners, max, 0.02, 3.0)
      val n = corners.rows()
      if (n == 0) return FloatArray(0)
      val xy = IntArray(2 * n)
      corners.get(0, 0, xy)
      return FloatArray(2 * n) { xy[it] + if (it % 2 == 0) r[0].toFloat() else r[1].toFloat() }
    } finally {
      corners.release()
      roi.release()
    }
  }

  fun reset() {
    tracks.clear()
    prev?.release()
    prev = null
  }

  companion object {
    /** Long edge of the image the tracker works on (1280 × 960 → 640 × 480). */
    const val SMALL_EDGE = 640

    /**
     * A grey copy of [f] with the long edge at most [edge] px (camera thread: the frame's buffer is reused after the
     * callback). Live frames: the Y plane, one copy and an area resize; replay: the JPEG decoded. Null when the
     * frame has no picture.
     */
    fun downscale(f: CameraFrame, edge: Int = SMALL_EDGE): Mat? {
      val g = f.gray
      val src: Mat
      var full: Mat? = null
      if (g != null) {
        full = Mat(g.height, g.rowStride, CvType.CV_8UC1)
        full.put(0, 0, g.data)
        src = if (g.rowStride == g.width) full else full.submat(0, g.height, 0, g.width)
      } else {
        val buf = MatOfByte(*f.jpeg)
        src = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_GRAYSCALE)
        buf.release()
        if (src.empty()) {
          src.release()
          return null
        }
      }
      try {
        val s = minOf(1.0, edge.toDouble() / maxOf(src.cols(), src.rows()))
        val out = Mat()
        if (s >= 1.0) src.copyTo(out)
        else Imgproc.resize(src, out, Size(Math.round(src.cols() * s).toDouble(), Math.round(src.rows() * s).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        return out
      } finally {
        if (full != null && src !== full) src.release()
        (full ?: src).release()
      }
    }
  }
}
