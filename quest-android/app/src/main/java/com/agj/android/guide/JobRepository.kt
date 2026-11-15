package com.agj.android.guide

import com.agj.android.core.Agq
import com.agj.android.net.ServerApi
import java.io.File

/**
 * Gets a built guide from the server and keeps it on disk.
 *
 * Threading: all functions are `suspend` and do network/disk I/O; call them from a coroutine on `Dispatchers.IO`
 * (implementations must not touch the Spatial SDK scene). Results are immutable.
 */
interface JobRepository {
  /** `GET /files/{job}/manifest.json`, parsed. Throws on HTTP or parse errors. */
  suspend fun manifest(jobId: String): Manifest

  /** Downloads `/files/{job}/{manifest.glb}` into the cache dir (once) and returns the local file. */
  suspend fun glb(jobId: String, manifest: Manifest): File

  /** Manifest + GLB + final node poses. Logs `job_loaded` (job, steps, actions, ms). */
  suspend fun load(jobId: String): Assembly
}

/**
 * Downloads manifest + GLB (demo key header via [ServerApi]), parses the GLB with [Glb] (JSON + BIN chunks, node
 * hierarchy, the `walkthrough` clip sampled at its last keyframe) and builds the [Guide]: each part's final transform
 * and baked mesh under the `assembly` root, each step's pose. The renderer draws from that data, not from `Mesh(Uri)`,
 * so the hidden-at-1e-4 rest pose never matters.
 */
class HttpJobRepository(private val api: ServerApi, private val cacheDir: File) : JobRepository {
  override suspend fun manifest(jobId: String): Manifest =
      Manifest.parse(api.file(jobId, "manifest.json").decodeToString())

  override suspend fun glb(jobId: String, manifest: Manifest): File {
    val out = File(cacheDir, "jobs/$jobId/${manifest.glb}")
    if (!out.exists() || out.length() == 0L) {
      out.parentFile?.mkdirs()
      val tmp = File(out.path + ".part")
      tmp.writeBytes(api.file(jobId, manifest.glb))
      tmp.renameTo(out)
    }
    return out
  }

  override suspend fun load(jobId: String): Assembly {
    val t0 = System.nanoTime()
    val m = manifest(jobId)
    val glb = glb(jobId, m)
    val t1 = System.nanoTime()
    val a = assemble(jobId, m, glb)
    val ms = (System.nanoTime() - t0) / 1_000_000
    Agq.log.event(
        "job_loaded",
        mapOf(
            "job" to jobId,
            "steps" to m.steps.size,
            "actions" to m.actionCount,
            "ms" to ms,
            "parse_ms" to (System.nanoTime() - t1) / 1_000_000,
            "parts" to (a.guide?.parts?.size ?: 0),
            "glb_bytes" to glb.length(),
            "max_err_mm" to a.guide?.maxTargetErrorMm()?.let { Math.round(it * 1000) / 1000.0 },
        ),
    )
    return a
  }

  companion object {
    /** Manifest + local GLB → [Assembly] with the [Guide] (pure; used by tests with the real job files). */
    fun assemble(jobId: String, m: Manifest, glbFile: File): Assembly {
      val guide = Guide.build(m, Glb.read(glbFile))
      return Assembly(jobId, m, glbFile.path, guide.finalPoses, guide)
    }
  }
}
