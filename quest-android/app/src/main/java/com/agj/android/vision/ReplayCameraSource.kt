package com.agj.android.vision

import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.guide.GuideJson
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Plays a recorded folder as if it were the camera, at the dataset's `fps` (intrinsics.json; 10 when absent or when
 * [fps] is given), looping.
 *
 * Folder format: the one tools/quest/replay/ writes (docs/QUEST_TESTING.md "Replay datasets"), default
 * `/sdcard/Android/data/com.agj.android/files/replay/`:
 *  - `*.jpg` / `*.jpeg` frames, played in file-name order;
 *  - `intrinsics.json`: `{fx, fy, cx, cy, width, height, fps, distortion}` (pixels of the stored frames);
 *  - optional `poses.jsonl`: one line per frame with `frame`, `file`, `cam_p` [x,y,z] and `cam_q` [x,y,z,w]: WORLD ←
 *    CAMERA with OpenCV camera axes (`cam_q_xr` is the same camera in OpenXR axes and is not used). Older aliases
 *    `pos_m`/`quat_xyzw` and `p`/`q` are still accepted.
 *
 * Every frame is delivered (the listener runs on this thread, so a slow tracker slows playback instead of dropping
 * frames): replay runs are scored frame by frame.
 *
 * Threading: one daemon thread per [start]; [stop] ends it.
 */
class ReplayCameraSource(private val dir: File, private val fps: Double? = null) : CameraSource {
  override val kind = "replay"

  @Volatile
  override var intrinsics: Intrinsics? = null
    private set

  @Volatile private var thread: Thread? = null

  override fun start(listener: FrameListener) {
    stop()
    val ds =
        try {
          ReplayDataset.read(dir)
        } catch (e: Exception) {
          Agq.log.error("replay", "${dir.path}: ${e.message}")
          return
        }
    intrinsics = ds.intrinsics
    val rate = fps ?: ds.fps ?: 10.0
    val t =
        Thread({ loop(ds, listener, rate) }, "agj-replay").apply {
          isDaemon = true
          start()
        }
    thread = t
    Agq.log.event(
        "camera",
        mapOf(
            "kind" to kind,
            "dir" to dir.path,
            "frames" to ds.frames.size,
            "poses" to ds.poses.size,
            "fps" to rate,
            "intrinsics" to ds.intrinsics.let { listOf(it.fx, it.fy, it.cx, it.cy, it.width, it.height) },
        ),
    )
  }

  private fun loop(ds: ReplayDataset, listener: FrameListener, rate: Double) {
    val periodNs = (1e9 / rate).toLong()
    var i = 0L
    val t0 = System.nanoTime()
    while (Thread.currentThread() == thread) {
      val k = (i % ds.frames.size).toInt()
      val f = ds.frames[k]
      try {
        val bytes = f.readBytes()
        listener.onFrame(
            CameraFrame(
                i,
                bytes,
                ds.intrinsics.width,
                ds.intrinsics.height,
                t0 + i * periodNs,
                ds.intrinsics,
                ds.poses[k],
                replayFrame = ds.frameIndex(k),
            ),
        )
      } catch (e: Exception) {
        Agq.log.error("replay", "frame ${f.name}: ${e.message}")
      }
      i++
      val wait = t0 + i * periodNs - System.nanoTime()
      if (wait > 0) {
        try {
          Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        } catch (e: InterruptedException) {
          break
        }
      }
    }
  }

  override fun stop() {
    val t = thread
    thread = null
    t?.interrupt()
  }
}

/**
 * The parsed replay folder. Pure JVM: unit-tested.
 *
 * @property poses file index (position in [frames]) → WORLD ← CAMERA.
 * @property frameNumbers file index → the dataset's `frame` number (from poses.jsonl; else the file index).
 */
class ReplayDataset(
    val frames: List<File>,
    val intrinsics: Intrinsics,
    val poses: Map<Int, Pose3>,
    val fps: Double? = null,
    private val frameNumbers: Map<Int, Int> = emptyMap(),
) {
  fun frameIndex(fileIndex: Int): Int = frameNumbers[fileIndex] ?: fileIndex

  companion object {
    fun read(dir: File): ReplayDataset {
      val frames =
          dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg") }?.sortedBy { it.name }
              ?: emptyList()
      require(frames.isNotEmpty()) { "no JPEG frames" }
      val intr = File(dir, "intrinsics.json")
      require(intr.exists()) { "no intrinsics.json" }
      val text = intr.readText()
      val names = frames.map { it.name }
      val posesText = File(dir, "poses.jsonl").takeIf { it.exists() }?.readText()
      return ReplayDataset(
          frames,
          parseIntrinsics(text),
          parsePoses(posesText, names),
          parseFps(text),
          parseFrameNumbers(posesText, names),
      )
    }

    fun parseIntrinsics(json: String): Intrinsics {
      val o = GuideJson.parseToJsonElement(json).jsonObject
      fun d(k: String) = o[k]?.jsonPrimitive?.doubleOrNull ?: throw IllegalArgumentException("intrinsics.$k missing")
      val dist = runCatching { o["distortion"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull } }.getOrNull()
      return Intrinsics(d("fx"), d("fy"), d("cx"), d("cy"), d("width").toInt(), d("height").toInt(), dist)
    }

    fun parseFps(json: String): Double? =
        runCatching { GuideJson.parseToJsonElement(json).jsonObject["fps"]?.jsonPrimitive?.doubleOrNull }
            .getOrNull()
            ?.takeIf { it > 0 }

    private fun lines(jsonl: String?): List<JsonObject> =
        jsonl
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.mapNotNull { runCatching { GuideJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.toList() ?: emptyList()

    private fun fileIndexOf(o: JsonObject, line: Int, byName: Map<String, Int>): Int =
        o["file"]?.jsonPrimitive?.content?.let { byName[it] } ?: o["frame"]?.jsonPrimitive?.intOrNull ?: line

    /** Lines that do not parse are skipped. Returns file index → WORLD ← CAMERA. */
    fun parsePoses(jsonl: String?, frameNames: List<String>): Map<Int, Pose3> {
      val byName = frameNames.withIndex().associate { (i, n) -> n to i }
      val out = HashMap<Int, Pose3>()
      lines(jsonl).forEachIndexed { line, o ->
        val idx = fileIndexOf(o, line, byName)
        val p = floats(o, "cam_p", "pos_m", "p") ?: return@forEachIndexed
        val q = floats(o, "cam_q", "quat_xyzw", "q") ?: return@forEachIndexed
        if (p.size < 3 || q.size < 4) return@forEachIndexed
        out[idx] = Pose3(Vec3(p[0], p[1], p[2]), Quat(q[0], q[1], q[2], q[3]).normalized())
      }
      return out
    }

    /** file index → `frame` number, when poses.jsonl names files and frames. */
    fun parseFrameNumbers(jsonl: String?, frameNames: List<String>): Map<Int, Int> {
      val byName = frameNames.withIndex().associate { (i, n) -> n to i }
      val out = HashMap<Int, Int>()
      lines(jsonl).forEach { o ->
        val f = o["frame"]?.jsonPrimitive?.intOrNull ?: return@forEach
        val idx = o["file"]?.jsonPrimitive?.content?.let { byName[it] } ?: return@forEach
        out[idx] = f
      }
      return out
    }

    private fun floats(o: JsonObject, vararg keys: String): List<Float>? {
      val a: JsonArray = keys.firstNotNullOfOrNull { k -> o[k]?.let { runCatching { it.jsonArray }.getOrNull() } }
          ?: return null
      return a.map { it.jsonPrimitive.doubleOrNull?.toFloat() ?: return null }
    }
  }
}
