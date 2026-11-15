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
 * Plays a recorded folder as if it were the camera, at [fps] (default 10), looping.
 *
 * Folder (default `/sdcard/Android/data/com.agj.android/files/replay/`, built by the tools under tools/quest/replay/):
 *  - `*.jpg` / `*.jpeg` frames, played in file-name order;
 *  - `intrinsics.json`: `{fx, fy, cx, cy, width, height}` (pixels of the stored frames);
 *  - optional `poses.jsonl`: one camera pose per frame, WORLD ← CAMERA (OpenCV axes), one JSON object per line. Line
 *    `i` belongs to frame `i` unless it names the frame with `"frame"` (index) or `"file"` (name). Pose fields:
 *    `pos_m` [x,y,z] metres and `quat_xyzw` [x,y,z,w]; `p`/`q` are accepted as aliases. (PROPOSED format: the replay
 *    tools are being written in parallel; align there.)
 *
 * Threading: one daemon thread per [start]; [stop] ends it.
 */
class ReplayCameraSource(private val dir: File, private val fps: Double = 10.0) : CameraSource {
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
    val t =
        Thread({ loop(ds, listener) }, "agj-replay").apply {
          isDaemon = true
          start()
        }
    thread = t
    Agq.log.event("camera", mapOf("kind" to kind, "frames" to ds.frames.size, "poses" to ds.poses.size, "fps" to fps))
  }

  private fun loop(ds: ReplayDataset, listener: FrameListener) {
    val periodNs = (1e9 / fps).toLong()
    var i = 0L
    val t0 = System.nanoTime()
    while (Thread.currentThread() == thread) {
      val k = (i % ds.frames.size).toInt()
      val f = ds.frames[k]
      try {
        val bytes = f.readBytes()
        listener.onFrame(
            CameraFrame(i, bytes, ds.intrinsics.width, ds.intrinsics.height, t0 + i * periodNs, ds.intrinsics, ds.poses[k]),
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

/** The parsed replay folder. Pure JVM: unit-tested. */
class ReplayDataset(val frames: List<File>, val intrinsics: Intrinsics, val poses: Map<Int, Pose3>) {
  companion object {
    fun read(dir: File): ReplayDataset {
      val frames =
          dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg") }?.sortedBy { it.name }
              ?: emptyList()
      require(frames.isNotEmpty()) { "no JPEG frames" }
      val intr = File(dir, "intrinsics.json")
      require(intr.exists()) { "no intrinsics.json" }
      val poses = File(dir, "poses.jsonl").takeIf { it.exists() }?.readText()
      return ReplayDataset(frames, parseIntrinsics(intr.readText()), parsePoses(poses, frames.map { it.name }))
    }

    fun parseIntrinsics(json: String): Intrinsics {
      val o = GuideJson.parseToJsonElement(json).jsonObject
      fun d(k: String) = o[k]?.jsonPrimitive?.doubleOrNull ?: throw IllegalArgumentException("intrinsics.$k missing")
      return Intrinsics(d("fx"), d("fy"), d("cx"), d("cy"), d("width").toInt(), d("height").toInt())
    }

    /** Lines that do not parse are skipped. Returns frame index → WORLD ← CAMERA. */
    fun parsePoses(jsonl: String?, frameNames: List<String>): Map<Int, Pose3> {
      if (jsonl == null) return emptyMap()
      val byName = frameNames.withIndex().associate { (i, n) -> n to i }
      val out = HashMap<Int, Pose3>()
      jsonl.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { line, s ->
        val o = runCatching { GuideJson.parseToJsonElement(s).jsonObject }.getOrNull() ?: return@forEachIndexed
        val idx =
            o["frame"]?.jsonPrimitive?.intOrNull
                ?: o["file"]?.jsonPrimitive?.content?.let { byName[it] }
                ?: line
        val p = floats(o, "pos_m", "p") ?: return@forEachIndexed
        val q = floats(o, "quat_xyzw", "q") ?: return@forEachIndexed
        if (p.size < 3 || q.size < 4) return@forEachIndexed
        out[idx] = Pose3(Vec3(p[0], p[1], p[2]), Quat(q[0], q[1], q[2], q[3]).normalized())
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
