package com.agj.android.vision

import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.core.TrackSource

/**
 * Picks one PnP solution and turns it into world poses. Pure Kotlin (unit-tested).
 *
 * IPPE_SQUARE returns two solutions for a planar square; at oblique views they can have similar reprojection
 * errors (the classic flip ambiguity). When the second is within [ambiguityRatio] of the first and a previous world
 * pose exists, the one whose rotation is closer to it wins.
 */
class MarkerSolver(
    private val config: MarkerConfig,
    private val maxReprojPx: Double = 4.0,
    private val ambiguityRatio: Double = 1.5,
) {
  private val markerFromAssembly = config.assemblyFromMarker.inverse()
  private val tagFromStrip = config.tagFromStrip

  fun solve(det: MarkerDetection, worldFromCamera: Pose3?, prior: Pose3?, ts: Long, replayFrame: Int?): TrackResult {
    val best = det.candidates.first()
    var pick = best
    if (det.candidates.size > 1 && prior != null) {
      val second = det.candidates[1]
      if (second.reprojPx <= maxOf(best.reprojPx * ambiguityRatio, best.reprojPx + 0.3)) {
        fun cost(c: PnpCandidate): Double {
          val cm = Frames.cameraFromMarker(c.rvec, c.tvec)
          val w = worldFromCamera?.let { it * cm } ?: cm
          return Frames.angleDeg(w.q, prior.q)
        }
        if (cost(second) < cost(best)) pick = second
      }
    }
    // PnP gives CAMERA ← TAG; everything downstream uses the strip centre (MARKER = STRIP, see [MarkerConfig]).
    val cameraFromMarker = Frames.cameraFromMarker(pick.rvec, pick.tvec) * tagFromStrip
    if (pick.reprojPx > maxReprojPx) {
      return TrackResult(false, null, pick.reprojPx, TrackSource.NONE, ts, null, cameraFromMarker, det.ms, true, replayFrame)
    }
    val worldFromMarker = worldFromCamera?.let { it * cameraFromMarker }
    val world = worldFromMarker?.let { it * markerFromAssembly }
    // Without a camera pose the result is camera-relative only: detected, not tracked in the world.
    return TrackResult(
        world != null,
        world,
        pick.reprojPx,
        if (world != null) TrackSource.MARKER else TrackSource.NONE,
        ts,
        worldFromMarker,
        cameraFromMarker,
        det.ms,
        true,
        replayFrame,
    )
  }
}

/**
 * Marker tracking: detect ([MarkerDetector]) → pick a PnP solution ([MarkerSolver]) → WORLD ← CAMERA × CAMERA ←
 * MARKER × MARKER ← ASSEMBLY. Raw per-frame results; smoothing and holding are [VisionModule]'s (via [PoseFilter]).
 *
 * Live frames are tracked on their luminance plane ([CameraFrame.gray]); replay frames are decoded from JPEG.
 *
 * @param loadNative loads the OpenCV native library once (device: `OpenCVLoader.initLocal()`; JVM tests: the
 *   desktop bindings' loader).
 *
 * Threading: [onFrame] on the camera thread; [latest] from any thread.
 */
class MarkerTracker(
    val config: MarkerConfig = MarkerConfig(),
    private val options: DetectorOptions = DetectorOptions(),
    private val loadNative: () -> Boolean = { org.opencv.android.OpenCVLoader.initLocal() },
) : ObjectTracker {
  override val source = TrackSource.MARKER

  @Volatile
  override var latest: TrackResult? = null
    private set

  private val solver = MarkerSolver(config)
  private var lastWorldFromMarker: Pose3? = null

  private val detector: MarkerDetector? by lazy {
    val ok =
        try {
          loadNative()
        } catch (t: Throwable) {
          Agq.log.error("marker", "OpenCV load: ${t.message}")
          false
        }
    if (!ok) {
      Agq.log.error("marker", "OpenCV native library did not load")
      null
    } else {
      MarkerDetector(config, options)
    }
  }

  override fun onFrame(frame: CameraFrame): TrackResult {
    val det0 = detector ?: return TrackResult.none(frame.timestampNs, frame.replayFrame).also { latest = it }
    val r =
        try {
          val g = frame.gray
          val det = if (g != null) det0.detectGray(g, frame.intrinsics) else det0.detectJpeg(frame.jpeg, frame.intrinsics)
          if (det == null) TrackResult.none(frame.timestampNs, frame.replayFrame)
          else solver.solve(det, frame.worldFromCamera, lastWorldFromMarker, frame.timestampNs, frame.replayFrame)
        } catch (e: Exception) {
          Agq.log.error("marker", e.message)
          TrackResult.none(frame.timestampNs, frame.replayFrame)
        }
    if (r.tracked) lastWorldFromMarker = r.worldFromMarker
    latest = r
    return r
  }

  override fun reset() {
    latest = null
    lastWorldFromMarker = null
  }
}
