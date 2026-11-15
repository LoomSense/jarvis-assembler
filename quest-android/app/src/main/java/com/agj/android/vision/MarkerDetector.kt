package com.agj.android.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect

/** One PnP solution: OpenCV rvec/tvec (CAMERA ← MARKER) and its RMS reprojection error in pixels. */
class PnpCandidate(val rvec: DoubleArray, val tvec: DoubleArray, val reprojPx: Double)

/**
 * The marker found in one image.
 *
 * @property corners the four image corners (x0, y0, … x3, y3) in pixels, ArUco order (TL, TR, BR, BL of the tag).
 * @property candidates IPPE_SQUARE's solutions (1 or 2), best reprojection first.
 * @property ms detection + PnP wall time.
 */
class MarkerDetection(val corners: DoubleArray, val candidates: List<PnpCandidate>, val ms: Double)

/** Detector options (also settable in `vision.json`, see [VisionConfig]). */
data class DetectorOptions(
    /** CORNER_REFINE_SUBPIX, as tools/quest/replay/reference_tracker.py does. */
    val subpixel: Boolean = true,
    /** ArUco3 fast detection (OpenCV 4.6+): faster on large frames; accuracy on our marker UNVERIFIED. */
    val aruco3: Boolean = false,
    /** Pass [Intrinsics.distortion] to PnP. Off by default: whether Quest frames are already rectified is UNVERIFIED. */
    val useDistortion: Boolean = false,
)

/**
 * AprilTag 36h11 detection (OpenCV `objdetect` ArUco module) + `solvePnPGeneric(SOLVEPNP_IPPE_SQUARE)`.
 *
 * Uses only the OpenCV Java API (no `org.opencv.android`), so the same class runs on the device (OpenCV Android SDK
 * 4.10) and in JVM unit tests (desktop OpenCV Java bindings, `org.openpnp:opencv`). Loading the native library is
 * the caller's job.
 *
 * Object points follow the IPPE_SQUARE convention, identical to reference_tracker.py:
 * (−L/2, L/2, 0), (L/2, L/2, 0), (L/2, −L/2, 0), (−L/2, −L/2, 0), i.e. marker +Y toward the TOP edge.
 *
 * Not thread-safe (one instance per camera thread).
 */
class MarkerDetector(private val config: MarkerConfig, private val options: DetectorOptions = DetectorOptions()) {
  private val detector: ArucoDetector by lazy {
    val params = DetectorParameters()
    if (options.subpixel) params.set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
    if (options.aruco3) params.set_useAruco3Detection(true)
    ArucoDetector(Objdetect.getPredefinedDictionary(config.dictionary), params)
  }

  private val objectPoints: MatOfPoint3f by lazy {
    val h = config.sizeM / 2
    MatOfPoint3f(Point3(-h, h, 0.0), Point3(h, h, 0.0), Point3(h, -h, 0.0), Point3(-h, -h, 0.0))
  }

  /** Detect in a JPEG (replay frames). */
  fun detectJpeg(jpeg: ByteArray, k: Intrinsics): MarkerDetection? {
    val t0 = System.nanoTime()
    val buf = MatOfByte(*jpeg)
    val gray = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_GRAYSCALE)
    buf.release()
    try {
      if (gray.empty()) return null
      return detect(gray, k, t0)
    } finally {
      gray.release()
    }
  }

  /** Detect in a luminance plane (live frames); no copy when [GrayImage.rowStride] == width. */
  fun detectGray(img: GrayImage, k: Intrinsics): MarkerDetection? {
    val t0 = System.nanoTime()
    val full = Mat(img.height, img.rowStride, CvType.CV_8UC1)
    full.put(0, 0, img.data)
    val view = if (img.rowStride == img.width) full else full.submat(0, img.height, 0, img.width)
    try {
      return detect(view, k, t0)
    } finally {
      if (view !== full) view.release()
      full.release()
    }
  }

  /** [gray] 8-bit single channel; [k] is scaled to its size here. */
  fun detect(gray: Mat, k0: Intrinsics, t0: Long = System.nanoTime()): MarkerDetection? {
    val corners = ArrayList<Mat>()
    val ids = Mat()
    val rejected = ArrayList<Mat>()
    try {
      detector.detectMarkers(gray, corners, ids, rejected)
      var found: Mat? = null
      for (i in 0 until ids.rows()) if (ids.get(i, 0)[0].toInt() == config.markerId) found = corners[i]
      val c = found ?: return null
      val img = MatOfPoint2f()
      c.reshape(2, 4).convertTo(img, CvType.CV_32FC2)
      val pts = img.toArray()
      val k = k0.scaledTo(gray.cols(), gray.rows())
      val cam = Mat.zeros(3, 3, CvType.CV_64F)
      cam.put(0, 0, k.fx, 0.0, k.cx, 0.0, k.fy, k.cy, 0.0, 0.0, 1.0)
      val d = k.distortion?.takeIf { options.useDistortion && it.size >= 4 }
      val dist = if (d != null) MatOfDouble(*d.toDoubleArray()) else MatOfDouble()
      val rvecs = ArrayList<Mat>()
      val tvecs = ArrayList<Mat>()
      val n = Calib3d.solvePnPGeneric(objectPoints, img, cam, dist, rvecs, tvecs, false, Calib3d.SOLVEPNP_IPPE_SQUARE)
      val out = ArrayList<PnpCandidate>()
      for (i in 0 until n) {
        val r = DoubleArray(3) { rvecs[i].get(it, 0)[0] }
        val t = DoubleArray(3) { tvecs[i].get(it, 0)[0] }
        val proj = MatOfPoint2f()
        Calib3d.projectPoints(objectPoints, rvecs[i], tvecs[i], cam, dist, proj)
        val pp = proj.toArray()
        var s = 0.0
        for (j in pts.indices) {
          val dx = pts[j].x - pp[j].x
          val dy = pts[j].y - pp[j].y
          s += dx * dx + dy * dy
        }
        out += PnpCandidate(r, t, kotlin.math.sqrt(s / pts.size))
        proj.release()
      }
      rvecs.forEach { it.release() }
      tvecs.forEach { it.release() }
      cam.release()
      dist.release()
      img.release()
      if (out.isEmpty()) return null
      out.sortBy { it.reprojPx }
      val flat = DoubleArray(8)
      for (j in 0 until 4) {
        flat[2 * j] = pts[j].x
        flat[2 * j + 1] = pts[j].y
      }
      return MarkerDetection(flat, out, (System.nanoTime() - t0) / 1e6)
    } finally {
      corners.forEach { it.release() }
      rejected.forEach { it.release() }
      ids.release()
    }
  }
}
