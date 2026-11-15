package com.agj.android.vision

import com.agj.android.core.AgqLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The device's tracking path ([ReplayDataset] → [MarkerTracker] (OpenCV detect + IPPE_SQUARE) → [TrackingCore]
 * (One-Euro filter + `track` events)) on the synthetic replay set, with the desktop OpenCV Java bindings.
 *
 * Writes `tools/quest/runs/android-jvm-marker-synthetic/agq.jsonl`, which `tools/quest/replay/score_track.py` scores:
 * ```
 * .venv/bin/python tools/quest/replay/score_track.py tools/quest/runs/android-jvm-marker-synthetic
 * ```
 * Skipped when the dataset has not been generated (`tools/quest/replay/make_marker_synthetic.py`) or the desktop
 * OpenCV native library does not load on this machine.
 */
class MarkerReplayAccuracyTest {
  private fun repoRoot(): File? {
    var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (d != null && !File(d, "tools/quest/replay").isDirectory) d = d.parentFile
    return d
  }

  private fun dataset(root: File): File? {
    System.getenv("AGQ_REPLAY_DATASET")?.let { return File(it).takeIf { f -> File(f, "poses.jsonl").exists() } }
    val local = File(root, "tools/quest/replay/marker-synthetic")
    if (File(local, "poses.jsonl").exists()) return local
    // a worktree: the generated set may only exist in the main checkout
    val main = root.absolutePath.substringBefore("/.claude/worktrees/", "")
    return File(main, "tools/quest/replay/marker-synthetic").takeIf { main.isNotEmpty() && File(it, "poses.jsonl").exists() }
  }

  private fun loadOpenCv(): Boolean =
      try {
        nu.pattern.OpenCV.loadLocally()
        true
      } catch (t: Throwable) {
        System.err.println("desktop OpenCV did not load: $t")
        false
      }

  @Test
  fun syntheticSetThroughTheDevicePipeline() {
    val root = repoRoot()
    assumeTrue("repo root not found", root != null)
    val ds = dataset(root!!)
    assumeTrue("marker-synthetic not generated", ds != null)
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())

    val data = ReplayDataset.read(ds!!)
    val lines = ArrayList<String>()
    var tMs = 0L
    val log = AgqLog({ tMs }, { lines += it })
    log.event("start", mapOf("app" to "android-jvm", "replay" to true, "camera_mode" to "replay", "job" to ds.name,
        "marker_offset" to MarkerConfig().assemblyFromMarker.t))
    val tracker = MarkerTracker(MarkerConfig(), DetectorOptions(), loadNative = { true })
    val core = TrackingCore(MarkerConfig(), log, OneEuroPoseFilter(), logEveryNs = 0L,
        stats = { mapOf("fps" to null, "camera" to "replay") }, clockNs = { System.nanoTime() })
    val period = (1e9 / (data.fps ?: 30.0)).toLong()
    val solveMs = ArrayList<Double>()
    for ((i, f) in data.frames.withIndex()) {
      tMs = i * period / 1_000_000
      val frame = CameraFrame(i.toLong(), f.readBytes(), data.intrinsics.width, data.intrinsics.height, i * period,
          data.intrinsics, data.poses[i], replayFrame = data.frameIndex(i))
      val r = tracker.onFrame(frame)
      r.solveMs?.let { solveMs += it }
      core.onMarker(r)
    }
    val out = File(root, "tools/quest/runs/android-jvm-marker-synthetic")
    out.mkdirs()
    File(out, "agq.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))
    File(out, "meta.json").writeText(
        """{"app": "android-jvm", "job": "${ds.name}", "replay": true, "note": "quest-android MarkerTracker + TrackingCore on the desktop JVM (openpnp OpenCV 4.9)"}""")

    // own quick metric (score_track.py is the reference): distance of the reported marker point from the origin
    val track = lines.map { Json.parseToJsonElement(it).jsonObject }.filter { it["ev"]!!.jsonPrimitive.content == "track" }
    val tracked = track.filter { it["tracked"]!!.jsonPrimitive.boolean }
    val errMm =
        tracked.map { e ->
          val p = e["pos_m"]!!.jsonArray.map { it.jsonPrimitive.double }
          Math.sqrt(p.sumOf { it * it }) * 1000
        }.sorted()
    val p50 = errMm[errMm.size / 2]
    val p95 = errMm[(errMm.size * 95) / 100]
    val yaw = tracked.map { Math.abs(it["yaw_deg"]!!.jsonPrimitive.double) }.sorted()
    println("android-jvm marker-synthetic: tracked ${tracked.size}/${track.size}, pos err p50 %.2f mm p95 %.2f mm, |yaw| p50 %.3f deg p95 %.3f deg, solve p50 %.1f ms"
        .format(p50, p95, yaw[yaw.size / 2], yaw[(yaw.size * 95) / 100], solveMs.sorted()[solveMs.size / 2]))
    assertEquals(data.frames.size, track.size)
    assertTrue("tracked ${tracked.size}", tracked.size >= data.frames.size * 0.95)
    assertTrue("p50 $p50 mm", p50 < 5.0)
    assertTrue("p95 $p95 mm", p95 < 10.0)
  }
}
