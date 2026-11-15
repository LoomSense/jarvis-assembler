package com.agj.android.vision

import com.agj.android.core.Pose3

/** Why a camera frame has no WORLD ← CAMERA pose (`camera_pose_missing.reason`). */
enum class PoseMiss(val wire: String) {
  /** Camera2 reported no `LENS_POSE_*`: HEAD ← CAMERA unknown. */
  NO_LENS_POSE("no_lens_pose"),
  /** The head-pose ring buffer is empty: `SpatialInput` is not registered, not ticking, or `getViewerPose()` fails. */
  NO_HEAD_SAMPLES("no_head_samples"),
  /** The newest head sample is old: the render loop stopped feeding the buffer (paused, not focused). */
  HEAD_STALE("head_stale"),
  /** The buffer is fresh but the capture time falls outside it: the camera clock is not `elapsedRealtimeNanos`. */
  CAPTURE_OUTSIDE_BUFFER("capture_outside_buffer"),
}

/**
 * One frame's pose lookup.
 *
 * @property worldFromCamera null when [miss] could not be worked around.
 * @property fallback true when the capture time did not match the buffer and the pose was taken at
 *   `now − latency` instead (then [miss] is [PoseMiss.CAPTURE_OUTSIDE_BUFFER] although the pose exists).
 * @property deltaMs lookup time − newest head sample, ms (positive = the capture claims to be newer than the newest
 *   head sample); null when the buffer is empty.
 */
data class CameraPoseResult(
    val worldFromCamera: Pose3?,
    val head: Pose3?,
    val miss: PoseMiss?,
    val fallback: Boolean,
    val headSamples: Int,
    val newestAgeMs: Double?,
    val deltaMs: Double?,
)

/**
 * WORLD ← CAMERA for a frame = head pose at the capture time ([HeadPoseBuffer.at]) × HEAD ← CAMERA. Pure, tested.
 *
 * When the head buffer is fresh but the capture time is outside it (a clock mismatch, the most likely reason the
 * first device run never confirmed `camera_pose`), it falls back to the head pose at `now − fallbackLatencyNs`
 * (the documented 20–40 ms capture latency), so marker tracking can still lock, and reports the miss.
 */
object CameraPoseResolver {
  const val STALE_NS = 250_000_000L

  fun resolve(
      heads: HeadPoseBuffer?,
      headFromCamera: Pose3?,
      captureNs: Long,
      nowNs: Long,
      offsetNs: Long = 0L,
      fallbackLatencyNs: Long = 35_000_000L,
      staleNs: Long = STALE_NS,
  ): CameraPoseResult {
    val n = heads?.size() ?: 0
    val newest = heads?.newestNs()
    if (heads == null || n == 0 || newest == null) return CameraPoseResult(null, null, PoseMiss.NO_HEAD_SAMPLES, false, 0, null, null)
    val newestAgeMs = (nowNs - newest) / 1e6
    val lookup = captureNs + offsetNs
    val deltaMs = (lookup - newest) / 1e6
    var head = heads.at(lookup)
    var miss: PoseMiss? = null
    var fallback = false
    if (head == null) {
      if (nowNs - newest > staleNs) {
        miss = PoseMiss.HEAD_STALE
      } else {
        miss = PoseMiss.CAPTURE_OUTSIDE_BUFFER
        head = heads.at(nowNs - fallbackLatencyNs + offsetNs)
        fallback = head != null
      }
    }
    if (headFromCamera == null) return CameraPoseResult(null, head, PoseMiss.NO_LENS_POSE, false, n, newestAgeMs, deltaMs)
    val world = head?.let { it * headFromCamera }
    return CameraPoseResult(world, head, miss, fallback, n, newestAgeMs, deltaMs)
  }
}

/**
 * The AGQ `camera_pose` / `camera_pose_missing` cadence for the live camera (pure, tested):
 *  - `camera_pose` with `cam_p`, `cam_q` the first time a frame has a pose, then every [everyMs] while poses exist;
 *  - `error` `where: camera_pose_missing` with `reason` ([PoseMiss.wire]) once [graceFrames] consecutive frames had
 *    none, then every [everyMs] while it lasts; and once (with `ts_fallback: true`) when the capture-clock fallback
 *    had to be used.
 */
class CameraPoseLog(
    private val log: com.agj.android.core.AgqLog,
    private val clockMs: () -> Long,
    private val everyMs: Long = 10_000L,
    private val graceFrames: Int = 25,
) {
  private var firstDone = false
  private var lastPoseMs = Long.MIN_VALUE / 2
  private var lastMissMs = Long.MIN_VALUE / 2
  private var withPose = 0
  private var withoutPose = 0
  private var consecutiveMiss = 0
  private var fallbackLogged = false

  fun onFrame(frame: Long, r: CameraPoseResult, captureNs: Long, nowNs: Long, lensPose: Boolean) {
    val now = clockMs()
    val p = r.worldFromCamera
    if (r.fallback && !fallbackLogged) {
      fallbackLogged = true
      log.event("error", missFields(r, frame, lensPose, captureNs, nowNs) + mapOf("ts_fallback" to true))
    }
    if (p != null) {
      withPose++
      consecutiveMiss = 0
      if (!firstDone || now - lastPoseMs >= everyMs) {
        log.event(
            "camera_pose",
            mapOf(
                "first" to !firstDone,
                "frame" to frame,
                "cam_p" to p.t,
                "cam_q" to listOf(p.q.x, p.q.y, p.q.z, p.q.w),
                "head_p" to r.head?.t,
                "head_samples" to r.headSamples,
                "head_newest_age_ms" to r.newestAgeMs?.let { Math.round(it * 10) / 10.0 },
                "capture_age_ms" to Math.round((nowNs - captureNs) / 1e5) / 10.0,
                "frames_with_pose" to withPose,
                "frames_without_pose" to withoutPose,
                "ts_fallback" to r.fallback,
            ),
        )
        firstDone = true
        lastPoseMs = now
        withPose = 0
        withoutPose = 0
      }
    } else {
      withoutPose++
      consecutiveMiss++
      if (consecutiveMiss >= graceFrames && now - lastMissMs >= everyMs) {
        lastMissMs = now
        log.event("error", missFields(r, frame, lensPose, captureNs, nowNs) + mapOf("ts_fallback" to false))
      }
    }
  }

  private fun missFields(r: CameraPoseResult, frame: Long, lensPose: Boolean, captureNs: Long, nowNs: Long): Map<String, Any?> {
    val reason = (r.miss ?: PoseMiss.NO_HEAD_SAMPLES).wire
    return mapOf(
        "where" to "camera_pose_missing",
        "message" to reason,
        "reason" to reason,
        "frame" to frame,
        "head_samples" to r.headSamples,
        "head_newest_age_ms" to r.newestAgeMs?.let { Math.round(it * 10) / 10.0 },
        "lookup_minus_newest_ms" to r.deltaMs?.let { Math.round(it * 10) / 10.0 },
        "capture_age_ms" to Math.round((nowNs - captureNs) / 1e5) / 10.0,
        "lens_pose" to lensPose,
        "consecutive" to consecutiveMiss,
    )
  }
}
