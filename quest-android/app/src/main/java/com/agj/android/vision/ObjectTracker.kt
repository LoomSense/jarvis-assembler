package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.TrackSource

/**
 * One tracker answer.
 *
 * @property worldFromAssembly WORLD ← ASSEMBLY (metres), null when not tracked in this frame.
 * @property reprojPx RMS reprojection error of the solve in image pixels (marker only), null otherwise.
 * @property source which tracker produced it; goes to the `track` log event and the HUD.
 */
data class TrackResult(
    val tracked: Boolean,
    val worldFromAssembly: Pose3?,
    val reprojPx: Double?,
    val source: TrackSource,
    val timestampNs: Long,
) {
  companion object {
    fun none(ts: Long) = TrackResult(false, null, null, TrackSource.NONE, ts)
  }
}

/**
 * Finds the physical assembly's 6-DoF pose. Implementations, in order of preference:
 *  - [MarkerTracker]: an AprilTag/ArUco sticker on the base part, OpenCV detect + solvePnP + camera pose;
 *  - [TouchTracker]: two fingertip corner touches (port of client/src/xr/register.ts), the fallback and the first
 *    registration when no marker is seen.
 *
 * Threading: [onFrame] is called on the camera thread and may take up to one frame period (100 ms at 10 fps);
 * [latest] is read from the render thread, so implementations publish results through a `@Volatile` field.
 */
interface ObjectTracker {
  val source: TrackSource

  /** Process one frame. Returns the result for this frame (also stored as [latest]). */
  fun onFrame(frame: CameraFrame): TrackResult

  /** The most recent result (from any thread). */
  val latest: TrackResult?

  fun reset()
}
