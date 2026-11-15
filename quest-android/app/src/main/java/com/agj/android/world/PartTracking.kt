package com.agj.android.world

import com.agj.android.core.AgqLog
import com.agj.android.vision.CameraFrame
import com.agj.android.vision.PartFlowTracker
import com.agj.android.vision.VisionModule
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.opencv.core.Mat

/**
 * Runs the continuous part tracking: camera frames → [PartFlowTracker] (2D, OpenCV) → [PartTracks] (WORLD).
 *
 * The camera thread ([VisionModule.frameTap]) only copies the grey image downscaled to 640 px (one 1.2 MB copy and
 * an area resize) and hands it to this class's own thread `agj-flow`; when that thread is still busy, or the last
 * frame was under [minIntervalNs] ago, the frame is **skipped, never queued**. The interval is at least 33 ms
 * (30 Hz) and at least 3 × what the last frame cost, so the tracker never takes more than a third of one core.
 * Nothing is copied while there are no tracks or the frame has no camera pose (a world point cannot be predicted
 * without one).
 */
class PartTracking(private val vision: VisionModule, private val tracks: PartTracks, private val log: AgqLog) {
  private val flow = PartFlowTracker()
  private val busy = AtomicBoolean(false)
  private var exec: ExecutorService? = null
  @Volatile private var lastNs = 0L
  @Volatile private var minIntervalNs = MIN_INTERVAL_NS
  @Volatile private var failed = false
  @Volatile private var noPoseLogNs = 0L

  fun start() {
    if (exec != null) return
    exec = Executors.newSingleThreadExecutor { Thread(it, "agj-flow").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    vision.frameTap = ::onCameraFrame
  }

  fun stop() {
    vision.frameTap = null
    exec?.shutdownNow()
    exec = null
  }

  /** Camera thread. */
  private fun onCameraFrame(f: CameraFrame) {
    if (failed || !tracks.wantsFrames) return
    val now = System.nanoTime()
    val cam = f.worldFromCamera
    if (cam == null) {
      if (now - noPoseLogNs > 5_000_000_000L) {
        noPoseLogNs = now
        log.event("part_track", mapOf("tracks" to tracks.size, "alive" to 0, "cam_pose" to false))
      }
      return
    }
    if (now - lastNs < minIntervalNs || !busy.compareAndSet(false, true)) return
    lastNs = now
    var small: Mat? = null
    try {
      small = PartFlowTracker.downscale(f)
      val e = exec
      if (small == null || e == null) {
        small?.release()
        busy.set(false)
        return
      }
      val k = f.intrinsics
      val scale = small.cols().toDouble() / k.width
      val tNs = f.timestampNs
      val copyMs = (System.nanoTime() - now) / 1e6
      val img = small
      e.execute {
        try {
          val t0 = System.nanoTime()
          val prep = tracks.prepare(cam, k)
          val r = flow.process(img, scale, prep.seeds, prep.drops) // keeps img until the next frame
          val ms = (System.nanoTime() - t0) / 1e6
          tracks.onFlow(r.obs, cam, k, tNs, ms + copyMs, r.seeded)
          minIntervalNs = maxOf(MIN_INTERVAL_NS, (3 * ms * 1e6).toLong())
        } catch (t: Throwable) {
          fail(t)
        } finally {
          busy.set(false)
        }
      }
    } catch (t: Throwable) { // OpenCV not loaded, or the executor was shut down
      small?.release()
      busy.set(false)
      if (exec != null) fail(t)
    }
  }

  private fun fail(t: Throwable) {
    failed = true
    log.error("part_track", "${t.javaClass.simpleName}: ${t.message}")
  }

  companion object {
    const val MIN_INTERVAL_NS = 33_000_000L
  }
}
