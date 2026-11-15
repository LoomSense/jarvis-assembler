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
 * Phase 1: manifest and GLB download are real; final poses are left empty.
 *
 * TODO(phase 2, guide owner): parse the GLB (glTF 2.0 binary: JSON chunk + BIN chunk), sample the `walkthrough`
 * animation's last keyframe for every node under [Manifest.rootNode], and fill [Assembly.finalPoses]. The Spatial SDK
 * loads the GLB for rendering itself (`Mesh(Uri)`), but it does not expose node animation curves to Kotlin
 * (UNVERIFIED), so read them from the file.
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
    val a = Assembly(jobId, m, glb.path)
    Agq.log.jobLoaded(jobId, m.steps.size, m.actionCount, (System.nanoTime() - t0) / 1_000_000)
    return a
  }
}
