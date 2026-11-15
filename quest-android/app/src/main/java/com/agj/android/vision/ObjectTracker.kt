package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.TrackSource

/**
 * One tracker answer (one camera frame).
 *
 * @property worldFromAssembly WORLD ← ASSEMBLY (metres), null when not tracked in this frame.
 * @property reprojPx RMS reprojection error of the solve in image pixels (marker only), null otherwise.
 * @property source which tracker produced it; goes to the `track` log event and the HUD.
 * @property worldFromMarker WORLD ← MARKER (marker tracker only).
 * @property cameraFromMarker CAMERA ← MARKER as PnP returned it (marker tracker only; also when the camera pose is
 *   unknown).
 * @property solveMs detection + PnP time for this frame.
 * @property detected the marker was found in the image (even if the camera pose was missing or the solve rejected).
 * @property replayFrame dataset frame index for replay frames.
 */
data class TrackResult(
    val tracked: Boolean,
    val worldFromAssembly: Pose3?,
    val reprojPx: Double?,
    val source: TrackSource,
    val timestampNs: Long,
    val worldFromMarker: Pose3? = null,
    val cameraFromMarker: Pose3? = null,
    val solveMs: Double? = null,
    val detected: Boolean = tracked,
    val replayFrame: Int? = null,
) {
  companion object {
    fun none(ts: Long, replayFrame: Int? = null, solveMs: Double? = null) =
        TrackResult(false, null, null, TrackSource.NONE, ts, solveMs = solveMs, replayFrame = replayFrame)
  }
}

/**
 * Finds the physical assembly's 6-DoF pose. Implementations, in order of preference:
 *  - [MarkerTracker]: an AprilTag sticker on the base part, OpenCV detect + solvePnP + camera pose;
 *  - [TouchTracker]: two fingertip corner touches (port of client/src/xr/register.ts), the fallback and the first
 *    registration when no marker is seen.
 * [VisionModule] combines them with a [PoseFilter] and the `track` log.
 *
 * Threading: [onFrame] is called on the camera thread and may take up to one frame period; [latest] is read from the
 * render thread, so implementations publish results through a `@Volatile` field.
 */
interface ObjectTracker {
  val source: TrackSource

  /** Process one frame. Returns the result for this frame (also stored as [latest]). */
  fun onFrame(frame: CameraFrame): TrackResult

  /** The most recent result (from any thread). */
  val latest: TrackResult?

  fun reset()
}
