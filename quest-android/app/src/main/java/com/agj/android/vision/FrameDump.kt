package com.agj.android.vision

import android.content.Context
import android.os.Build
import com.agj.android.core.Agq
import com.agj.android.core.AgqLog
import com.agj.android.core.Params
import com.agj.android.core.Pose3
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject

/**
 * What MainActivity knows at the moment of a dumped frame (everything optional; unknown = null).
 *
 * @property surfaceY WORLD height (y, metres) of the work surface the panel detector uses (`world.surfaceY`).
 * @property anchor the assembly anchor, WORLD ← TABLE (`world.anchor.pose`); [anchorSource] = `world.placeSource`.
 * @property ghost WORLD ← ASSEMBLY the ghost is drawn at (`vision.worldFromAssembly()`).
 * @property track the tracker's last result (the `track` argument of `VisionListener.onFrame`).
 * @property panel the markerless tracker's last reported WORLD ← ASSEMBLY (`panel?.latest`).
 * @property more anything else worth a column (values as `AgqLog.toJson` takes them); written under `more`.
 */
class DumpExtras(
    val surfaceY: Float? = null,
    val anchor: Pose3? = null,
    val anchorSource: String? = null,
    val ghost: Pose3? = null,
    val job: String? = null,
    val step: Int? = null,
    val action: Int? = null,
    val track: TrackSnapshot? = null,
    val panel: Pose3? = null,
    val more: Map<String, Any?> = emptyMap(),
)

/**
 * The recording's file format (pure Kotlin, JVM-tested in `FrameDumpTest`).
 *
 * **It is the project's replay format, extended** (docs/QUEST_TESTING.md "Replay datasets"), not a second format:
 * a session folder holds `000000.jpg`, …, [INTRINSICS] (`fx, fy, cx, cy, width, height, fps, distortion`) and
 * [POSES], one JSON object per frame with the canonical `frame`, `t`, `file`, `cam_p`, `cam_q` plus the extra keys
 * below. So a pulled recording is a replay dataset as it is (`tools/quest/install.sh android --replay <folder>`,
 * then `--ez replay true`), `tools/quest/replay/reference_tracker.py --dataset <folder>` reads it, and
 * `tools/markerless/eval_real.py <folder>` runs the panel detector on it.
 *
 * One line of [POSES]:
 *
 * | key | meaning |
 * |---|---|
 * | `frame`, `file` | 0-based number of the dumped frame and its JPEG (`000012.jpg`) |
 * | `t` | seconds since the first dumped frame, on the capture clock |
 * | `ts_ns` | capture time: Camera2 `SENSOR_TIMESTAMP` on `elapsedRealtimeNanos` ([CameraFrame.timestampNs]) |
 * | `wall_ms`, `index` | epoch ms when the frame was dumped; the camera's own frame counter |
 * | `width`, `height` | size of the stored JPEG in pixels (read from the JPEG itself) |
 * | `fx`, `fy`, `cx`, `cy` | pinhole intrinsics **in pixels of the stored JPEG**, origin at the top-left pixel, exactly what the trackers get (`frame.intrinsics`, scaled if the JPEG is smaller than the stream) |
 * | `distortion` | OpenCV `[k1, k2, p1, p2, k3]` or null; the trackers ignore it unless `vision.json` `use_distortion` |
 * | `cam_p`, `cam_q` | WORLD ← CAMERA: camera centre (m) and rotation `[x, y, z, w]`, `w ≥ 0`; null without a pose |
 * | `T_world_cam` | the same pose as a 4 × 4 row-major matrix: `detect_front_panel`'s `T_world_cam` (see [matrix]) |
 * | `surface_y` | WORLD height of the work surface (m), null when unknown |
 * | `T_world_table` | `detect_front_panel`'s `T_world_table` for that height (see [tableMatrix]); null without `surface_y` |
 * | `anchor_p`, `anchor_q`, `anchor_source` | the assembly anchor WORLD ← TABLE and what placed it |
 * | `ghost_p`, `ghost_q` | WORLD ← ASSEMBLY the ghost is drawn at |
 * | `job`, `step`, `action` | what the guide shows |
 * | `track` | the tracker's last result: `state`, `method`, `tracked`, `reproj_px`, `solve_ms`, `age_ms`, `asm_p`, `asm_q` |
 * | `panel_p`, `panel_q` | the markerless tracker's last reported WORLD ← ASSEMBLY |
 *
 * **Conventions** (the same as everywhere in the app, vision/Frames.kt): WORLD is right-handed, +Y up, metres
 * (OpenXR tracking space); CAMERA has OpenCV axes (+X right, +Y down, +Z forward); `aFromB` maps B → A, so
 * `p_world = R(cam_q) · p_cam + cam_p`. Nothing here is in the Spatial SDK's left-handed axes: a pose read from the
 * SDK must go through [Frames.spatialToWorld] first (the app does that in `SpatialInput`).
 */
object DumpFormat {
  const val POSES = "poses.jsonl"
  const val INTRINSICS = "intrinsics.json"
  const val SESSION = "session.json"
  const val VERSION = "agj-dump-1"

  fun fileName(seq: Int): String = String.format(Locale.US, "%06d.jpg", seq)

  /**
   * [p] (A ← B) as a 4 × 4 row-major matrix `[R | t; 0 0 0 1]`, `p_A = R · p_B + t`, in double precision from the
   * normalised quaternion. For WORLD ← CAMERA this is `detect_front_panel`'s `T_world_cam` with **no axis change**:
   * the detector wants OpenCV camera axes and any right-handed world, and that is what `frame.worldFromCamera` is
   * (the one RH → LH edge of the app is on the rendering side, never on this pose).
   */
  fun matrix(p: Pose3): List<List<Double>> {
    var x = p.q.x.toDouble()
    var y = p.q.y.toDouble()
    var z = p.q.z.toDouble()
    var w = p.q.w.toDouble()
    val n = Math.sqrt(x * x + y * y + z * z + w * w)
    if (n < 1e-12) {
      x = 0.0; y = 0.0; z = 0.0; w = 1.0
    } else {
      x /= n; y /= n; z /= n; w /= n
    }
    return listOf(
        listOf(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), p.t.x.toDouble()),
        listOf(2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), p.t.y.toDouble()),
        listOf(2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), p.t.z.toDouble()),
        listOf(0.0, 0.0, 0.0, 1.0),
    )
  }

  /**
   * WORLD ← TABLE for a level work surface at WORLD height [surfaceY], as `detect_front_panel`'s `T_world_table`
   * (+Z = table normal, origin on the surface): TABLE +X = WORLD +X, TABLE +Y = WORLD −Z, TABLE +Z = WORLD +Y, origin
   * (0, surfaceY, 0). The same frame as the on-device detector's `PanelFrames.tableAtHeight` (a test keeps them
   * equal), so `x`, `y`, `yaw` from the offline tool and from the app's `panel` log are the same numbers.
   */
  fun tableMatrix(surfaceY: Float): List<List<Double>> =
      listOf(
          listOf(1.0, 0.0, 0.0, 0.0),
          listOf(0.0, 0.0, 1.0, surfaceY.toDouble()),
          listOf(0.0, -1.0, 0.0, 0.0),
          listOf(0.0, 0.0, 0.0, 1.0),
      )

  /** Width × height from a JPEG's frame header (SOFn), null when [jpeg] is not a JPEG. */
  fun jpegSize(jpeg: ByteArray): Pair<Int, Int>? {
    fun u(i: Int) = jpeg[i].toInt() and 0xff
    if (jpeg.size < 4 || u(0) != 0xff || u(1) != 0xd8) return null
    var i = 2
    while (i + 9 < jpeg.size) {
      if (u(i) != 0xff) return null
      val m = u(i + 1)
      if (m == 0xff) {
        i++
        continue
      }
      if (m == 0xd8 || m == 0x01 || (m in 0xd0..0xd7)) {
        i += 2
        continue
      }
      val len = (u(i + 2) shl 8) or u(i + 3)
      if (m in 0xc0..0xcf && m != 0xc4 && m != 0xc8 && m != 0xcc) {
        val h = (u(i + 5) shl 8) or u(i + 6)
        val w = (u(i + 7) shl 8) or u(i + 8)
        return if (w > 0 && h > 0) w to h else null
      }
      if (len < 2) return null
      i += 2 + len
    }
    return null
  }

  private fun pos(p: Pose3?): List<Float>? = p?.let { listOf(it.t.x, it.t.y, it.t.z) }

  private fun quat(p: Pose3?): List<Float>? = p?.let { Frames.canonical(it.q.normalized()).let { q -> listOf(q.x, q.y, q.z, q.w) } }

  /** The fields of one [POSES] line, in file order. [k] must already be the intrinsics of the stored JPEG. */
  fun fields(
      seq: Int,
      tSeconds: Double,
      timestampNs: Long,
      wallMs: Long,
      cameraIndex: Long,
      k: Intrinsics,
      worldFromCamera: Pose3?,
      x: DumpExtras,
  ): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>()
    m["frame"] = seq
    m["t"] = tSeconds
    m["file"] = fileName(seq)
    m["ts_ns"] = timestampNs
    m["wall_ms"] = wallMs
    m["index"] = cameraIndex
    m["width"] = k.width
    m["height"] = k.height
    m["fx"] = k.fx
    m["fy"] = k.fy
    m["cx"] = k.cx
    m["cy"] = k.cy
    m["distortion"] = k.distortion
    m["cam_p"] = pos(worldFromCamera)
    m["cam_q"] = quat(worldFromCamera)
    m["T_world_cam"] = worldFromCamera?.let { matrix(it) }
    m["surface_y"] = x.surfaceY
    m["T_world_table"] = x.surfaceY?.takeIf { it.isFinite() }?.let { tableMatrix(it) }
    m["anchor_p"] = pos(x.anchor)
    m["anchor_q"] = quat(x.anchor)
    m["anchor_source"] = x.anchorSource
    m["ghost_p"] = pos(x.ghost)
    m["ghost_q"] = quat(x.ghost)
    m["job"] = x.job
    m["step"] = x.step
    m["action"] = x.action
    m["track"] =
        x.track?.let { s ->
          mapOf(
              "state" to s.state.wire,
              "method" to s.method,
              "tracked" to s.tracked,
              "reproj_px" to s.reprojPx,
              "solve_ms" to s.solveMs,
              "age_ms" to if (s.timestampNs > 0) (timestampNs - s.timestampNs) / 1e6 else null,
              "asm_p" to pos(s.worldFromAssembly),
              "asm_q" to quat(s.worldFromAssembly),
          )
        }
    m["panel_p"] = pos(x.panel)
    m["panel_q"] = quat(x.panel)
    if (x.more.isNotEmpty()) m["more"] = x.more
    return m
  }

  /** One line of [POSES] (no newline). Non-finite numbers become null, so every line is valid JSON. */
  fun line(
      seq: Int,
      tSeconds: Double,
      timestampNs: Long,
      wallMs: Long,
      cameraIndex: Long,
      k: Intrinsics,
      worldFromCamera: Pose3?,
      x: DumpExtras,
  ): String = json(fields(seq, tSeconds, timestampNs, wallMs, cameraIndex, k, worldFromCamera, x))

  /** [INTRINSICS], the replay keys: pixels of the stored JPEGs, [fps] = the dump rate. */
  fun intrinsicsJson(k: Intrinsics, fps: Double): String =
      json(
          linkedMapOf(
              "fx" to k.fx, "fy" to k.fy, "cx" to k.cx, "cy" to k.cy, "width" to k.width, "height" to k.height,
              "fps" to fps, "distortion" to k.distortion,
          ),
      )

  fun json(m: Map<String, Any?>): String = (AgqLog.toJson(m) as JsonObject).toString()
}

/**
 * Records what the headset camera really sees, with everything the trackers were given, so the detectors can be run
 * again on the Mac (`tools/markerless/eval_real.py`) and their thresholds tuned from evidence.
 *
 * Launch extras (core/Params.kt): `--ez dump_frames true` switches it on; `--ei dump_hz 3` frames a second (default
 * 3), `--ei dump_max 400` frames at most (default 400). Without `dump_frames` every call here returns at once.
 *
 * It writes one session folder, `/sdcard/Android/data/com.agj.android/files/dump/<yyyyMMdd-HHmmss>/` (app-private
 * external storage: `adb pull` reads it without root; `tools/markerless/pull.sh` does it), in the format of
 * [DumpFormat]: full-resolution JPEGs (quality 90), `poses.jsonl`, `intrinsics.json`, `session.json`.
 *
 * Wiring (MainActivity, four lines):
 * ```
 * private var dump: FrameDump? = null
 * // onCreate, after the `if (params.markerless) { … }` block:
 * dump = FrameDump(this, params)
 * // visionListener.onFrame, first line (camera thread):
 * dump?.onFrame(frame) { DumpExtras(surfaceY = world.surfaceY, anchor = world.anchor.pose, anchorSource = world.placeSource,
 *     ghost = vision.worldFromAssembly(), job = assembly?.jobId, step = curStep, action = curAction, track = track, panel = panel?.latest) }
 * // onDestroy, before vision.stop():
 * dump?.stop()
 * ```
 *
 * Threading: [onFrame] on the camera thread. For a frame that is due it encodes the JPEG there (live buffers are
 * only valid inside the callback; about 15 ms, [hz] times a second), evaluates `extras`, and hands both to one
 * background thread that writes the files. It never waits for the disk: when [MAX_PENDING] frames are still unwritten
 * the frame is dropped and counted. It stops by itself at [max] frames, at [maxBytes], or when the storage has less
 * than [MIN_FREE_BYTES] free. A picture is written under a `.part` name and renamed, and its `poses.jsonl` line is
 * flushed right after, so a force-stop (what `tools/quest/run.sh` does at the end) loses nothing that was written.
 *
 * Logs AGQ `dump`: once at start (`path`, `hz`, `max`), after the first frame and every 50 frames (`frames`,
 * `bytes`, `dropped`, `with_pose`), and at the end (`done: true`, `reason`).
 *
 * @param dir the session folder; null = switched off.
 */
class FrameDump(
    private val dir: File?,
    private val hz: Double = 3.0,
    private val max: Int = 400,
    private val log: AgqLog = Agq.log,
    private val meta: Map<String, Any?> = emptyMap(),
    private val quality: Int = 90,
    private val maxBytes: Long = 600L * 1024 * 1024,
    private val wallMs: () -> Long = { System.currentTimeMillis() },
    private val writeBytes: (File, ByteArray) -> Unit = { f, b -> f.writeBytes(b) },
) {
  constructor(context: Context, params: Params) :
      this(
          if (params.dumpFrames) sessionDir(context) else null,
          params.dumpHz,
          params.dumpMax,
          Agq.log,
          if (params.dumpFrames) sessionMeta(context, params) else emptyMap(),
      )

  val enabled: Boolean
    get() = dir != null

  /** The session folder on the device, null when switched off. */
  val path: String?
    get() = dir?.path

  private val periodNs = (1e9 / hz.coerceIn(0.1, 60.0)).toLong()
  private val pending = AtomicInteger()
  private var worker: ExecutorService? = null
  private var poses: FileOutputStream? = null

  // camera thread
  private var lastNs = Long.MIN_VALUE
  private var firstNs = Long.MIN_VALUE
  private var submitted = 0
  @Volatile private var dropped = 0
  @Volatile private var closed = false

  // writer thread
  @Volatile var written = 0
    private set
  @Volatile var bytes = 0L
    private set
  private var withPose = 0
  private var failures = 0

  init {
    if (dir != null) {
      try {
        dir.mkdirs()
        File(dir, DumpFormat.SESSION).writeText(
            DumpFormat.json(
                linkedMapOf(
                    "format" to DumpFormat.VERSION, "session" to dir.name, "started_ms" to wallMs(), "hz" to hz, "max" to max,
                    "jpeg_quality" to quality, "poses" to DumpFormat.POSES,
                    "world" to "right-handed, +Y up, metres (OpenXR tracking space)",
                    "camera" to "OpenCV axes (+X right, +Y down, +Z forward); cam_p/cam_q and T_world_cam are WORLD <- CAMERA",
                    "intrinsics" to "pixels of the stored JPEG, origin at the top-left pixel",
                ) + meta,
            ),
        )
        poses = FileOutputStream(File(dir, DumpFormat.POSES), true)
        worker = Executors.newSingleThreadExecutor { Thread(it, "agj-dump").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        log.event("dump", mapOf("path" to dir.path, "hz" to hz, "max" to max, "max_bytes" to maxBytes, "quality" to quality, "frames" to 0))
      } catch (t: Throwable) {
        closed = true
        log.error("dump", "${dir.path}: ${t.message}")
      }
    }
  }

  /** Camera thread. [extras] is called only for a frame that is dumped. Never blocks on the disk, never throws. */
  fun onFrame(frame: CameraFrame, extras: () -> DumpExtras) {
    val w = worker ?: return
    if (closed || submitted >= max) return
    val ts = frame.timestampNs
    if (lastNs != Long.MIN_VALUE && ts >= lastNs && ts - lastNs < periodNs) return
    if (pending.get() >= MAX_PENDING) {
      dropped++ // the disk is behind: skip this one, try the next camera frame
      return
    }
    try {
      val jpeg = frame.jpegAt(maxOf(frame.width, frame.height), quality)
      if (jpeg.isEmpty()) return
      val (iw, ih) = DumpFormat.jpegSize(jpeg) ?: (frame.width to frame.height)
      val k = frame.intrinsics.scaledTo(iw, ih)
      val x = try { extras() } catch (t: Throwable) { DumpExtras(more = mapOf("extras_error" to (t.message ?: t.toString()))) }
      if (firstNs == Long.MIN_VALUE) firstNs = ts
      lastNs = ts
      val seq = submitted++
      val line = DumpFormat.line(seq, (ts - firstNs) / 1e9, ts, wallMs(), frame.index, k, frame.worldFromCamera, x)
      val hasPose = frame.worldFromCamera != null
      pending.incrementAndGet()
      w.execute { write(seq, jpeg, line, k, hasPose) }
    } catch (t: Throwable) {
      log.error("dump", t.message)
    }
  }

  private fun write(seq: Int, jpeg: ByteArray, line: String, k: Intrinsics, hasPose: Boolean) {
    val d = dir ?: return
    try {
      if (closed) return
      if (written == 0) File(d, DumpFormat.INTRINSICS).writeText(DumpFormat.intrinsicsJson(k, hz))
      // under a temporary name first: a picture cut short by a force-stop must not look like a frame
      val part = File(d, DumpFormat.fileName(seq) + ".part")
      writeBytes(part, jpeg)
      if (!part.renameTo(File(d, DumpFormat.fileName(seq)))) throw java.io.IOException("rename failed")
      poses?.let {
        it.write((line + "\n").toByteArray(Charsets.UTF_8))
        it.flush()
      }
      written++
      bytes += jpeg.size + line.length + 1
      if (hasPose) withPose++
      val reason =
          when {
            written >= max -> "max_frames"
            bytes >= maxBytes -> "max_bytes"
            d.usableSpace in 1 until MIN_FREE_BYTES -> "storage_low"
            else -> null
          }
      if (reason != null) finish(reason) else if (written == 1 || written % LOG_EVERY == 0) logProgress(false, null)
    } catch (t: Throwable) {
      log.error("dump", "frame $seq: ${t.message}")
      if (++failures >= 5) finish("write_errors")
    } finally {
      pending.decrementAndGet()
    }
  }

  private fun logProgress(done: Boolean, reason: String?) {
    log.event(
        "dump",
        mapOf("path" to dir?.path, "frames" to written, "bytes" to bytes, "dropped" to dropped, "with_pose" to withPose, "done" to done, "reason" to reason),
    )
  }

  private fun finish(reason: String) {
    if (closed) return
    closed = true
    runCatching { poses?.close() }
    poses = null
    logProgress(true, reason)
  }

  /** Waits (at most [timeoutMs]) until every queued frame is on disk; false on timeout. For `stop` and the tests. */
  fun flush(timeoutMs: Long = 2000): Boolean {
    val end = System.nanoTime() + timeoutMs * 1_000_000
    while (pending.get() > 0) {
      if (System.nanoTime() > end) return false
      try {
        Thread.sleep(1)
      } catch (e: InterruptedException) {
        return false
      }
    }
    return true
  }

  /** Frames skipped because the disk was behind. */
  val droppedFrames: Int
    get() = dropped

  /** Main thread (onDestroy): writes what is queued (waits at most 2 s), closes the files, logs the last `dump`. */
  fun stop() {
    val w = worker ?: return
    worker = null
    w.execute { finish("stopped") }
    w.shutdown()
    runCatching { w.awaitTermination(2, TimeUnit.SECONDS) }
  }

  companion object {
    /** Frames waiting for the disk before new ones are dropped (about 0.4 MB each). */
    const val MAX_PENDING = 3
    const val LOG_EVERY = 50
    const val MIN_FREE_BYTES = 200L * 1024 * 1024

    /** For `session.json`: the launch parameters (the key only as `key_set`), the device, and `vision.json` as it is. */
    fun sessionMeta(context: Context, params: Params): Map<String, Any?> {
      val visionJson =
          try {
            File(context.getExternalFilesDir(null), "vision.json").takeIf { it.exists() }?.readText()
          } catch (e: Exception) {
            null
          }
      return mapOf("params" to params.toLogMap(), "device" to Build.MODEL, "os" to Build.DISPLAY, "vision_json" to visionJson)
    }

    /** `<external files>/dump/<yyyyMMdd-HHmmss>`: `/sdcard/Android/data/com.agj.android/files/dump/…` on the headset. */
    fun sessionDir(context: Context): File =
        File(
            context.getExternalFilesDir("dump") ?: File(context.filesDir, "dump"), // the `dump` event says which
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()),
        )
  }
}
