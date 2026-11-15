package com.agj.android.vision

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Quat

/** `track.state` values (eval/judge/RUBRIC.md §4 names `tracked`, `lost`, `predicted`; `held` = anchored pose). */
enum class TrackState(val wire: String) {
  TRACKED("tracked"),
  HELD("held"),
  LOST("lost"),
}

/**
 * What the renderer and the HUD read.
 *
 * @property worldFromAssembly filtered or held WORLD ← ASSEMBLY (our right-handed WORLD, see [Frames]); null = lost.
 * @property method how the current pose was obtained: `marker`, `touch`, `anchor` (held after the marker was lost),
 *   `none`.
 */
data class TrackSnapshot(
    val state: TrackState,
    val method: String,
    val worldFromAssembly: Pose3?,
    val worldFromMarker: Pose3?,
    val reprojPx: Double?,
    val solveMs: Double?,
    val timestampNs: Long,
) {
  val tracked: Boolean
    get() = state == TrackState.TRACKED

  companion object {
    val LOST = TrackSnapshot(TrackState.LOST, "none", null, null, null, null, 0L)
  }
}

/**
 * Marker results + touch registrations → one filtered pose and the AGQ `track` event. Pure Kotlin (no Android,
 * no OpenCV): the same object runs on the device and in the JVM replay test.
 *
 * `track` fields (docs/QUEST_TESTING.md §5 + eval/judge/RUBRIC.md §4):
 * `fps` (render fps, from [stats]), `tracked`, `pos_m` (the marker point of the filtered pose, WORLD; on the synthetic
 * dataset the truth is the origin), `yaw_deg` (heading of the assembly's +X = marker +X about world +Y), `reproj_px`,
 * `source` (`marker`, `touch`, `anchor`, `none`), `frame` (replay), `q` (WORLD ← MARKER, xyzw), `state`, `method`,
 * `pos`, `rot` (WORLD ← ASSEMBLY), `ms` (detect + PnP), `raw_pos_m` (unfiltered marker point), `asm_pos_m`
 * (assembly origin), `age_ms` (capture → result), `rejected` (outliers so far), plus whatever [stats] adds
 * (`cam_fps`, …).
 *
 * @param logEveryNs minimum interval between `track` events; 0 = every frame (replay, for scoring). State changes
 *   are always logged.
 * @param clockNs the clock of [CameraFrame.timestampNs] (for `age_ms`).
 *
 * Threading: all methods are synchronized; call from any thread.
 */
class TrackingCore(
    val config: MarkerConfig,
    private val log: AgqLog?,
    private val filter: OneEuroPoseFilter = OneEuroPoseFilter(),
    private val logEveryNs: Long = 100_000_000L,
    private val stats: () -> Map<String, Any?> = { emptyMap() },
    private val clockNs: () -> Long = { System.nanoTime() },
) {
  private val markerFromAssembly = config.assemblyFromMarker.inverse()
  private var snap = TrackSnapshot.LOST
  private var method = "none"
  private var lastLogNs = Long.MIN_VALUE
  private var lastLoggedState: TrackState? = null
  private var lastRaw: Pose3? = null

  var events = 0
    private set

  @Synchronized fun snapshot(): TrackSnapshot = snap

  /** Feed one marker result (every processed camera frame). */
  @Synchronized
  fun onMarker(r: TrackResult): TrackSnapshot {
    val meas = if (r.tracked) r.worldFromMarker else null
    val pose = filter.update(meas, r.timestampNs)
    val accepted = meas != null && !filter.lastRejected
    if (accepted) method = "marker"
    lastRaw = meas ?: lastRaw
    val state =
        when {
          pose == null -> TrackState.LOST
          accepted -> TrackState.TRACKED
          else -> TrackState.HELD
        }
    val m = if (state == TrackState.HELD && method == "marker") "anchor" else if (pose == null) "none" else method
    snap = TrackSnapshot(state, m, pose?.let { it * markerFromAssembly }, pose, r.reprojPx, r.solveMs, r.timestampNs)
    maybeLog(r.timestampNs, r.replayFrame, meas, force = false)
    return snap
  }

  /** A two-corner touch registration landed: WORLD ← ASSEMBLY, static. The marker overrides it when seen. */
  @Synchronized
  fun onTouch(worldFromAssembly: Pose3, tNs: Long): TrackSnapshot {
    filter.reset()
    val wm = worldFromAssembly * config.assemblyFromMarker
    filter.update(wm, tNs)
    method = "touch"
    snap = TrackSnapshot(TrackState.TRACKED, "touch", worldFromAssembly, wm, null, null, tNs)
    maybeLog(tNs, null, null, force = true)
    // a touch pose is static: following frames without a marker report it as held under method "touch"
    return snap
  }

  /**
   * A pose from outside the marker pipeline (world/: the table placement, the server's box for the base part, a
   * manual nudge, the markerless panel pose): WORLD ← ASSEMBLY, held like a touch pose until the next one. [method]
   * is what `track.source` / `track.method` then say (`table`, `fixed_spot`, `server_box`, `manual`, `markerless`).
   * The state is `tracked` on this call and `held` on the following frames (a placed anchor is not tracking); a
   * marker, when seen, overrides it.
   */
  @Synchronized
  fun onExternal(worldFromAssembly: Pose3, tNs: Long, method: String): TrackSnapshot {
    filter.reset()
    val wm = worldFromAssembly * config.assemblyFromMarker
    filter.update(wm, tNs)
    this.method = method
    snap = TrackSnapshot(TrackState.TRACKED, method, worldFromAssembly, wm, null, null, tNs)
    maybeLog(tNs, null, null, force = true)
    return snap
  }

  /** Call about once a second without frames (camera failed or not started) so the log keeps a heartbeat. */
  @Synchronized
  fun tick(nowNs: Long) {
    if (lastLogNs == Long.MIN_VALUE || nowNs - lastLogNs >= 1_000_000_000L) {
      if (snap.state == TrackState.TRACKED && nowNs - snap.timestampNs > 500_000_000L) {
        snap = snap.copy(state = if (snap.worldFromAssembly != null) TrackState.HELD else TrackState.LOST,
            method = if (snap.method == "marker") "anchor" else snap.method)
      }
      maybeLog(nowNs, null, null, force = true)
    }
  }

  @Synchronized
  fun reset() {
    filter.reset()
    snap = TrackSnapshot.LOST
    method = "none"
    lastRaw = null
  }

  private fun maybeLog(tNs: Long, frame: Int?, raw: Pose3?, force: Boolean) {
    val l = log ?: return
    val changed = snap.state != lastLoggedState
    if (!force && !changed && logEveryNs > 0 && lastLogNs != Long.MIN_VALUE && tNs - lastLogNs < logEveryNs) return
    lastLogNs = tNs
    lastLoggedState = snap.state
    events++
    l.event("track", trackFields(snap, frame, raw))
  }

  private fun trackFields(s: TrackSnapshot, frame: Int?, raw: Pose3?): Map<String, Any?> {
    val wm = s.worldFromMarker
    val wa = s.worldFromAssembly
    val st = stats()
    val m = LinkedHashMap<String, Any?>()
    m["fps"] = st["fps"]
    m["tracked"] = s.tracked
    m["pos_m"] = wm?.t?.let { r6(it.x, it.y, it.z) }
    m["yaw_deg"] = wm?.q?.yawDeg()?.let { Math.round(it * 1000) / 1000.0 }
    m["reproj_px"] = s.reprojPx?.let { Math.round(it * 1000) / 1000.0 }
    m["source"] = if (s.state == TrackState.LOST) "none" else s.method
    if (frame != null) m["frame"] = frame
    m["q"] = wm?.q?.let { q6(Frames.canonical(it)) }
    m["state"] = s.state.wire
    m["method"] = s.method
    m["pos"] = m["pos_m"]
    m["rot"] = wa?.q?.let { q6(Frames.canonical(it)) }
    m["ms"] = s.solveMs?.let { Math.round(it * 10) / 10.0 }
    m["raw_pos_m"] = raw?.t?.let { r6(it.x, it.y, it.z) }
    m["asm_pos_m"] = wa?.t?.let { r6(it.x, it.y, it.z) }
    if (s.timestampNs > 0) m["age_ms"] = Math.round((clockNs() - s.timestampNs) / 1e5) / 10.0
    m["rejected"] = filter.rejected
    for ((k, v) in st) if (k != "fps") m[k] = v
    return m
  }

  private fun r6(vararg v: Float) = v.map { Math.round(it * 1e6) / 1e6 }

  private fun q6(q: Quat) = listOf(q.x, q.y, q.z, q.w).map { Math.round(it * 1e6) / 1e6 }
}
