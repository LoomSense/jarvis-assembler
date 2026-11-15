package com.agj.android.vision

import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.TrackSource
import com.agj.android.core.Vec3
import kotlin.math.sqrt
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect

/**
 * Marker configuration.
 *
 * @property dictionary OpenCV predefined dictionary id; default AprilTag 36h11 (`Objdetect.DICT_APRILTAG_36h11`).
 * @property markerId the id printed on the sticker.
 * @property sizeM black-square side length in metres.
 * @property assemblyFromMarker where the sticker sits on the base part, ASSEMBLY ← MARKER. Marker frame: origin at the
 *   square's centre, +X right, +Y up (in the printed image), +Z out of the sticker (OpenCV IPPE_SQUARE convention).
 */
data class MarkerConfig(
    val dictionary: Int = Objdetect.DICT_APRILTAG_36h11,
    val markerId: Int = 0,
    val sizeM: Double = 0.06,
    val assemblyFromMarker: Pose3 = Pose3.IDENTITY,
)

/**
 * Marker tracking: detect → `solvePnP(SOLVEPNP_IPPE_SQUARE)` → WORLD ← CAMERA × CAMERA ← MARKER × MARKER ← ASSEMBLY.
 *
 * Phase 1: the OpenCV pipeline is written and compiles; it has NOT run on a device or on replay data yet.
 * Distortion is assumed zero (UNVERIFIED: Quest passthrough frames may already be undistorted; Camera2
 * `LENS_DISTORTION` would give the coefficients).
 *
 * Threading: [onFrame] on the camera thread; allocates per call (fine at 10 fps, pool later).
 */
class MarkerTracker(private val config: MarkerConfig = MarkerConfig()) : ObjectTracker {
  override val source = TrackSource.MARKER

  @Volatile
  override var latest: TrackResult? = null
    private set

  private val detector: ArucoDetector? by lazy {
    if (!OpenCVLoader.initLocal()) {
      Agq.log.error("marker", "OpenCV native library did not load")
      null
    } else {
      ArucoDetector(Objdetect.getPredefinedDictionary(config.dictionary), DetectorParameters())
    }
  }

  private val objectPoints: MatOfPoint3f by lazy {
    val h = config.sizeM / 2
    MatOfPoint3f(Point3(-h, h, 0.0), Point3(h, h, 0.0), Point3(h, -h, 0.0), Point3(-h, -h, 0.0))
  }

  override fun onFrame(frame: CameraFrame): TrackResult {
    val det = detector ?: return TrackResult.none(frame.timestampNs).also { latest = it }
    val gray = Imgcodecs.imdecode(MatOfByte(*frame.jpeg), Imgcodecs.IMREAD_GRAYSCALE)
    try {
      val corners = ArrayList<Mat>()
      val ids = Mat()
      det.detectMarkers(gray, corners, ids)
      var found: Mat? = null
      for (i in 0 until ids.rows()) if (ids.get(i, 0)[0].toInt() == config.markerId) found = corners[i]
      val c = found ?: return TrackResult.none(frame.timestampNs).also { latest = it }

      val img = MatOfPoint2f()
      c.reshape(2, 4).convertTo(img, CvType.CV_32FC2)
      val k = frame.intrinsics.scaledTo(gray.cols(), gray.rows())
      val cam = Mat.zeros(3, 3, CvType.CV_64F)
      cam.put(0, 0, k.fx, 0.0, k.cx, 0.0, k.fy, k.cy, 0.0, 0.0, 1.0)
      val dist = MatOfDouble(0.0, 0.0, 0.0, 0.0)
      val rvec = Mat()
      val tvec = Mat()
      if (!Calib3d.solvePnP(objectPoints, img, cam, dist, rvec, tvec, false, Calib3d.SOLVEPNP_IPPE_SQUARE)) {
        return TrackResult.none(frame.timestampNs).also { latest = it }
      }
      val reproj = MatOfPoint2f()
      Calib3d.projectPoints(objectPoints, rvec, tvec, cam, dist, reproj)
      val err = rms(img.toArray(), reproj.toArray())

      val cameraFromMarker = Pose3(Vec3(tvec.d(0), tvec.d(1), tvec.d(2)), rodriguesToQuat(rvec))
      val cameraFromAssembly = cameraFromMarker * config.assemblyFromMarker.inverse()
      val world = frame.worldFromCamera?.let { it * cameraFromAssembly }
      // Without a camera pose the result is camera-relative; reported as not tracked in the world.
      return TrackResult(world != null, world, err, source, frame.timestampNs).also { latest = it }
    } catch (e: Exception) {
      Agq.log.error("marker", e.message)
      return TrackResult.none(frame.timestampNs).also { latest = it }
    } finally {
      gray.release()
    }
  }

  override fun reset() {
    latest = null
  }

  private fun Mat.d(i: Int) = get(i, 0)[0].toFloat()

  private fun rms(a: Array<Point>, b: Array<Point>): Double {
    var s = 0.0
    for (i in a.indices) {
      val dx = a[i].x - b[i].x
      val dy = a[i].y - b[i].y
      s += dx * dx + dy * dy
    }
    return sqrt(s / a.size)
  }

  private fun rodriguesToQuat(rvec: Mat): Quat {
    val r = Mat()
    Calib3d.Rodrigues(rvec, r)
    val m = Array(3) { i -> DoubleArray(3) { j -> r.get(i, j)[0] } }
    return matToQuat(m)
  }

  companion object {
    /** Rotation matrix (row-major) → unit quaternion. */
    fun matToQuat(m: Array<DoubleArray>): Quat {
      val tr = m[0][0] + m[1][1] + m[2][2]
      val q: DoubleArray =
          if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            doubleArrayOf((m[2][1] - m[1][2]) / s, (m[0][2] - m[2][0]) / s, (m[1][0] - m[0][1]) / s, 0.25 * s)
          } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
            val s = sqrt(1.0 + m[0][0] - m[1][1] - m[2][2]) * 2
            doubleArrayOf(0.25 * s, (m[0][1] + m[1][0]) / s, (m[0][2] + m[2][0]) / s, (m[2][1] - m[1][2]) / s)
          } else if (m[1][1] > m[2][2]) {
            val s = sqrt(1.0 + m[1][1] - m[0][0] - m[2][2]) * 2
            doubleArrayOf((m[0][1] + m[1][0]) / s, 0.25 * s, (m[1][2] + m[2][1]) / s, (m[0][2] - m[2][0]) / s)
          } else {
            val s = sqrt(1.0 + m[2][2] - m[0][0] - m[1][1]) * 2
            doubleArrayOf((m[0][2] + m[2][0]) / s, (m[1][2] + m[2][1]) / s, 0.25 * s, (m[1][0] - m[0][1]) / s)
          }
      return Quat(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat()).normalized()
    }
  }
}
