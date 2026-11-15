package com.agj.android.vision

import com.agj.android.core.Pose3

/**
 * Pinhole intrinsics in pixels of the delivered image ([width] × [height]), origin top-left, OpenCV convention.
 * Same fields as the replay `intrinsics.json`: `{fx, fy, cx, cy, width, height}`.
 */
data class Intrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val width: Int,
    val height: Int,
) {
  /** The same camera delivering a resized image (aspect kept). */
  fun scaledTo(w: Int, h: Int): Intrinsics {
    val sx = w.toDouble() / width
    val sy = h.toDouble() / height
    return Intrinsics(fx * sx, fy * sy, cx * sx, cy * sy, w, h)
  }
}

/**
 * One camera image.
 *
 * @property jpeg encoded image (what the server wants and what replay stores). Decode for CV with OpenCV `imdecode`.
 * @property timestampNs capture time, `SystemClock.elapsedRealtimeNanos` base for live frames (Camera2
 *   `SENSOR_TIMESTAMP`, REALTIME source: UNVERIFIED on Quest), synthetic for replay.
 * @property worldFromCamera camera pose at capture time, WORLD ← CAMERA(OpenCV axes: +X right, +Y down, +Z forward),
 *   metres. Live: head pose at [timestampNs] × head-from-lens (`LENS_POSE_*`). Replay: from `poses.jsonl`. Null when
 *   unknown (then trackers can only report camera-relative poses).
 */
class CameraFrame(
    val index: Long,
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val intrinsics: Intrinsics,
    val worldFromCamera: Pose3?,
)

fun interface FrameListener {
  /** Called on the source's own background thread. Do not block for long: frames are dropped, not queued. */
  fun onFrame(frame: CameraFrame)
}

/**
 * A stream of camera frames with intrinsics and pose: the live passthrough camera ([LiveCameraSource]) or a recorded
 * folder ([ReplayCameraSource]). Chosen at start-up by the `replay` launch parameter.
 *
 * Threading: [start]/[stop] from any thread; [FrameListener.onFrame] runs on the source's thread.
 */
interface CameraSource {
  /** "live" or "replay", for the HUD and logs. */
  val kind: String

  /** Null until the camera is opened (live) or the folder is read (replay). */
  val intrinsics: Intrinsics?

  /** Begin delivering frames. Errors are reported through `Agq.log.error` and leave the source stopped. */
  fun start(listener: FrameListener)

  fun stop()
}
