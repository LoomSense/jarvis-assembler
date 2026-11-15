package com.agj.android.vision

import com.agj.android.core.Pose3

/**
 * Pinhole intrinsics in pixels of the delivered image ([width] × [height]), origin top-left, OpenCV convention.
 * Same fields as the replay `intrinsics.json`: `{fx, fy, cx, cy, width, height}` (+ `distortion`, null there).
 *
 * @property distortion OpenCV order (k1, k2, p1, p2, k3), dimensionless (so it survives [scaledTo]); null = none.
 */
data class Intrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val width: Int,
    val height: Int,
    val distortion: List<Double>? = null,
) {
  /** The same camera delivering a resized image (aspect kept). */
  fun scaledTo(w: Int, h: Int): Intrinsics {
    if (w == width && h == height) return this
    val sx = w.toDouble() / width
    val sy = h.toDouble() / height
    return Intrinsics(fx * sx, fy * sy, cx * sx, cy * sy, w, h, distortion)
  }
}

/** 8-bit luminance image (the Y plane of a YUV frame), row-major, [rowStride] ≥ [width]. */
class GrayImage(val data: ByteArray, val width: Int, val height: Int, val rowStride: Int = width)

/**
 * One camera image.
 *
 * @property jpeg encoded image for the server (long edge ≤ 768 for live frames, the stored file for replay). Live
 *   frames encode it lazily on first access, so frames that are only tracked cost no JPEG encode.
 * @property width @property height size of the full-resolution image the [intrinsics] belong to.
 * @property timestampNs capture time. Live: Camera2 `SENSOR_TIMESTAMP` (`Image.timestamp`), whose base is
 *   `SENSOR_INFO_TIMESTAMP_SOURCE` (logged in the `camera` event). Replay: synthetic.
 * @property worldFromCamera camera pose at capture time, WORLD ← CAMERA (OpenCV axes), metres; see [Frames]. Live:
 *   head pose at [timestampNs] × HEAD ← CAMERA (`LENS_POSE_*`). Replay: `poses.jsonl` `cam_p`/`cam_q`. Null when
 *   unknown (then trackers can only report camera-relative poses).
 * @property gray full-resolution luminance for tracking (live). Null for replay: decode [jpeg] instead.
 * @property replayFrame index of the replay frame in its dataset (for `track.frame`), null for live frames.
 */
class CameraFrame(
    val index: Long,
    jpegBytes: ByteArray?,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val intrinsics: Intrinsics,
    val worldFromCamera: Pose3?,
    val gray: GrayImage? = null,
    private val encode: (() -> ByteArray)? = null,
    val replayFrame: Int? = null,
    private val encodeAt: ((Int, Int) -> ByteArray)? = null,
) {
  val jpeg: ByteArray by lazy { jpegBytes ?: encode?.invoke() ?: ByteArray(0) }

  /**
   * A JPEG with long edge [longEdge] and [quality] (live frames: encoded now, only valid inside `onFrame`; replay:
   * the stored file, whatever its size). Same picture and aspect as [jpeg], so normalised boxes map the same way.
   */
  fun jpegAt(longEdge: Int, quality: Int): ByteArray = encodeAt?.invoke(longEdge, quality) ?: jpeg
}

/**
 * A copy of one frame that outlives the camera callback ([VisionModule.grabFrame]): the encoded picture, the
 * intrinsics of the full-resolution image ([width] × [height]; the JPEG has the same aspect) and the camera pose at
 * capture time (null when unknown). [tMs] wall clock when grabbed.
 */
class FrameGrab(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val intrinsics: Intrinsics,
    val worldFromCamera: Pose3?,
    val timestampNs: Long,
    val tMs: Long,
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
