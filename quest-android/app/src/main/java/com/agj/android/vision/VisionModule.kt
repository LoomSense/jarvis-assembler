package com.agj.android.vision

import android.content.Context
import android.os.SystemClock
import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.net.HandSummary
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Callbacks from [VisionModule]. [onFrame] runs on the camera thread (read `frame.jpeg` there if you need it: live
 * buffers are reused after it returns); the others on the vision timer thread. Post to the main thread before
 * touching the scene.
 */
interface VisionListener {
  /** Every processed camera frame, after tracking. */
  fun onFrame(frame: CameraFrame, track: TrackSnapshot) {}

  /** Tracking state changed (tracked / held / lost, or the method changed). */
  fun onTrackState(s: TrackSnapshot) {}

  /** Hand summary at 10 Hz (send it with `PerceptionSocket.sendHands`). Not called when no hand source works. */
  fun onHands(h: HandSummary) {}

  /** A two-corner touch registration finished; [pose] null when the touched distance was implausible. */
  fun onTouchSnap(r: SnapResult, pose: Pose3?) {}
}

/**
 * The vision package's single entry point for MainActivity: camera (live Camera2 or replay folder), head-pose
 * sampling, marker tracking, One-Euro filtering with hold, touch fallback, hand summaries and the AGQ `camera`,
 * `camera_frame`, `track`, `hands_source` events.
 *
 * Wiring (MainActivity):
 * ```
 * private lateinit var vision: VisionModule
 * // onCreate, after params:
 * vision = VisionModule(this, params.replay)
 * systemManager.registerSystem(vision.input)       // head pose + hands + render fps, every frame
 * vision.listener = object : VisionListener { … }  // optional
 * // after the camera permissions (or at once when replay):
 * vision.start()
 * // render/tick: the ghost pose in right-handed WORLD; SpatialGhostRenderer converts to Spatial axes itself
 * ghost.setAssemblyPose(vision.worldFromAssembly())
 * // (vision.spatialPose() is the same pose already in Spatial axes, for code that sets a Transform directly)
 * // onDestroy:
 * vision.stop()
 * ```
 * The module logs `track` itself (10 Hz live, every frame in replay); MainActivity must not log `track` too.
 *
 * Threading: [start]/[stop] from the main thread; getters from any thread.
 */
class VisionModule(
    private val context: Context,
    private val replay: Boolean,
    val config: VisionConfig = load(context),
    private val replayDir: File = File(context.getExternalFilesDir(null), "replay"),
) {
  val heads = HeadPoseBuffer()

  /** Register with `systemManager.registerSystem(vision.input)`. */
  val input = SpatialInput(heads)

  @Volatile var listener: VisionListener? = null

  /** WORLD box of what the wearer should touch now (ghost part bounds), for `hand_at_target`. Null = none. */
  @Volatile var handTarget: Aabb? = null

  private val camFrames = AtomicInteger()
  @Volatile private var renderFps: Double? = null
  @Volatile private var camFps: Double? = null
  @Volatile private var solveHz: Double? = null
  @Volatile private var camPose: Boolean? = null
  private val grabRequest = AtomicReference<GrabRequest?>(null)
  private val solves = AtomicInteger()

  private val clock: () -> Long = if (replay) ({ System.nanoTime() }) else ({ SystemClock.elapsedRealtimeNanos() })

  val core =
      TrackingCore(
          config.marker,
          Agq.log,
          OneEuroPoseFilter(config.filter),
          logEveryNs = if (replay) 0L else (1e9 / config.trackLogHz.coerceAtLeast(0.2)).toLong(),
          stats = { mapOf("fps" to renderFps, "cam_fps" to camFps, "solve_hz" to solveHz, "camera" to cameraKind,
              "cam_pose" to camPose) },
          clockNs = clock,
      )

  val marker = MarkerTracker(config.marker, config.detector)

  /** Two-corner fallback; [startTouch] arms it. */
  val touch = TouchTracker(Vec3(-0.19f, 0f, 0f) to Vec3(0.19f, 0f, 0f))
  @Volatile private var touchActive = false

  private val hands = HandChecker()
  private var camera: CameraSource? = null
  private var timer: ScheduledExecutorService? = null
  @Volatile private var lastState: Pair<TrackState, String>? = null

  val cameraKind: String
    get() = if (replay) "replay" else "live"

  fun start() {
    stop()
    Agq.log.event("vision", mapOf("camera" to cameraKind, "config" to config.toLogMap()))
    val src: CameraSource = if (replay) ReplayCameraSource(replayDir) else LiveCameraSource(context, heads, config)
    camera = src
    src.start { frame ->
      camFrames.incrementAndGet()
      val r = marker.onFrame(frame)
      solves.incrementAndGet()
      val s = core.onMarker(r)
      if (touchActive && s.method == "marker" && s.tracked) touchActive = false // the marker wins
      notifyState(s)
      camPose = frame.worldFromCamera != null
      grabRequest.getAndSet(null)?.let { g ->
        try {
          g.cb(FrameGrab(frame.jpegAt(g.longEdge, g.quality), frame.width, frame.height, frame.intrinsics,
              frame.worldFromCamera, frame.timestampNs, System.currentTimeMillis()))
        } catch (e: Exception) {
          Agq.log.error("frame_grab", e.message)
        }
      }
      listener?.onFrame(frame, s)
    }
    touch.onSnap = { r, pose ->
      if (pose != null) {
        touchActive = false
        notifyState(core.onTouch(pose, clock()))
      }
      Agq.log.event(
          "touch",
          mapOf("measured_m" to r.measuredM, "model_m" to r.modelM, "yaw_deg" to Math.toDegrees(r.yawRad.toDouble()),
              "ok" to (pose != null)),
      )
      listener?.onTouchSnap(r, pose)
    }
    val t = Executors.newSingleThreadScheduledExecutor { Thread(it, "agj-vision").apply { isDaemon = true } }
    timer = t
    var lastStats = SystemClock.elapsedRealtime()
    var lastRender = input.renderFrames.get()
    t.scheduleAtFixedRate(
        {
          try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastStats >= 1000) {
              val dt = (now - lastStats) / 1000.0
              val rf = input.renderFrames.get()
              renderFps = Math.round((rf - lastRender) / dt * 10) / 10.0
              lastRender = rf
              camFps = Math.round(camFrames.getAndSet(0) / dt * 10) / 10.0
              solveHz = Math.round(solves.getAndSet(0) / dt * 10) / 10.0
              lastStats = now
              core.tick(clock())
            }
            val f = input.hands
            if (f != null && SystemClock.elapsedRealtimeNanos() - f.tNs < 500_000_000L) {
              listener?.onHands(hands.update(f, handTarget, System.currentTimeMillis()))
              if (touchActive) touch.onHands(f)
            }
          } catch (e: Exception) {
            Agq.log.error("vision", e.message)
          }
        },
        100,
        100,
        TimeUnit.MILLISECONDS,
    )
  }

  /**
   * Deliver the next camera frame (JPEG with long edge [longEdge], its intrinsics and WORLD ← CAMERA at capture) to
   * [cb], once, on the camera thread. A newer request replaces a pending one. Used by world/PartsFinder.
   */
  fun grabFrame(longEdge: Int = 1280, quality: Int = 80, cb: (FrameGrab) -> Unit) {
    grabRequest.set(GrabRequest(longEdge, quality, cb))
  }

  private class GrabRequest(val longEdge: Int, val quality: Int, val cb: (FrameGrab) -> Unit)

  fun stop() {
    camera?.stop()
    camera = null
    timer?.shutdownNow()
    timer = null
  }

  /**
   * Arm the two-corner touch registration (marker absent). [cornersPlate]: front-left and front-right bottom corners
   * in a frame whose +Y is world up; [plateFromAssembly]: that frame ← ASSEMBLY (the step's display rotation).
   */
  fun startTouch(cornersPlate: Pair<Vec3, Vec3>, plateFromAssembly: Pose3 = Pose3.IDENTITY) {
    touch.setModel(cornersPlate, plateFromAssembly)
    touchActive = true
    Agq.log.event("touch_start", mapOf("a" to cornersPlate.first, "b" to cornersPlate.second))
  }

  fun cancelTouch() {
    touchActive = false
    touch.reset()
  }

  val touching: Boolean
    get() = touchActive

  fun snapshot(): TrackSnapshot = core.snapshot()

  /** Filtered/held WORLD ← ASSEMBLY in our right-handed WORLD ([Frames]); null = lost. */
  fun worldFromAssembly(): Pose3? = core.snapshot().worldFromAssembly

  /** The same pose in Spatial SDK axes: (tx, ty, tz, qx, qy, qz, qw), ready for `Pose(Vector3, Quaternion(w, x, y, z))`. */
  fun spatialPose(): FloatArray? = worldFromAssembly()?.let { Frames.worldToSpatial(it) }

  /** Numbers for the HUD. */
  fun hud(): Map<String, Any?> {
    val s = core.snapshot()
    return mapOf("state" to s.state.wire, "method" to s.method, "reproj_px" to s.reprojPx, "cam_fps" to camFps,
        "render_fps" to renderFps, "solve_ms" to s.solveMs)
  }

  private fun notifyState(s: TrackSnapshot) {
    val k = s.state to s.method
    if (k != lastState) {
      lastState = k
      listener?.onTrackState(s)
    }
  }

  companion object {
    fun load(context: Context): VisionConfig {
      val f = File(context.getExternalFilesDir(null), "vision.json")
      val text = runCatching { if (f.exists()) f.readText() else null }.getOrNull()
      return VisionConfig.parse(text)
    }
  }
}
