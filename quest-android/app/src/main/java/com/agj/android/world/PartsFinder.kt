package com.agj.android.world

import com.agj.android.core.AgqLog
import com.agj.android.core.Vec3
import com.agj.android.net.PartsFound
import com.agj.android.net.PartsResult
import com.agj.android.vision.FrameGrab

/**
 * A real part or hardware heap in the pile, located in WORLD, for the UI to draw an arrow + label above it (R11).
 *
 * @property worldPos where the box centre's ray meets the depth map / table / floor, WORLD metres.
 * @property ageMs since the frame it was found in was grabbed (the UI can fade old targets).
 * @property method `depth` (Meta environment raycast), `table` (MRUK table rectangle), `floor`, `plane` (the work
 *   surface height without a scene table).
 */
data class PickTarget(
    val id: String,
    val label: String,
    val worldPos: Vec3,
    val confidence: Double,
    val ageMs: Long,
    val kind: String = "",
    val method: String = "",
    /** Rough footprint radius on the table, m (box size × distance / focal length), for the ring under the part. */
    val radiusM: Float = 0.05f,
    /** The server box, `[ymin, xmin, ymax, xmax]` 0–1000 over the camera picture (for the camera preview). */
    val box: List<Double> = emptyList(),
)

/** What a ray may hit when there is no depth hit: the scene table, the floor, a work-surface height. */
data class PickSurfaces(val table: TableSurface? = null, val floorY: Float? = null, val workY: Float? = null)

/** Depth-based raycasts (Meta environment raycaster). [done] gets one hit (WORLD) or null per ray, on any thread. */
fun interface DepthRaycaster {
  fun cast(rays: List<Ray>, done: (List<Vec3?>) -> Unit)
}

/**
 * The `/parts` loop. Every [periodMs] at most, it grabs a camera frame (JPEG + intrinsics + camera pose) and asks
 * `POST /api/perception/{job}/parts` for
 *  - the current action's part id / hardware code while a `place`/`fasten` action is shown ([setAction]): each
 *    found box centre becomes a WORLD point ([locate]: depth raycast first, then the table rectangle, the floor,
 *    the work-surface plane) → [pickTargets];
 *  - the base part ([setBase]) whenever [baseDue] says so, in the same request → [onBase] (coarse placement of the
 *    assembly anchor, world/AssemblyAnchor.kt).
 * Logs AGQ `parts`.
 *
 * Threading: [tick] and the server call run on one worker (the caller's executor; [submit] queues onto it);
 * [grab] answers on the camera thread; [pickTargets] from any thread. Pure apart from the injected functions, so
 * the JVM tests drive it with fakes.
 *
 * @param query blocking `/parts` call (job, jpeg, want, step) on the worker thread.
 */
class PartsFinder(
    private val grab: ((FrameGrab) -> Unit) -> Unit,
    private val query: (String, ByteArray, List<String>, Int?) -> PartsResult,
    private val surfaces: () -> PickSurfaces,
    private val submit: (Runnable) -> Unit,
    private val log: AgqLog,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val depth: DepthRaycaster? = null,
    val periodMs: Long = 5000L,
    private val useDistortion: Boolean = false,
    /** A request that got no frame or answer within this is abandoned. */
    private val stuckMs: Long = 30_000L,
) {
  private class Action(val job: String, val step: Int, val action: Int, val want: List<String>)

  /** One request: the action's wanted items (may be none) plus the base part (may be none). */
  private class Request(val job: String, val a: Action?, val gen: Int, val baseId: String?) {
    val want: List<String>
      get() = ((a?.want ?: emptyList()) + listOfNotNull(baseId)).distinct()
  }

  private class Stored(val t: PickTarget, val tMs: Long)

  @Volatile private var current: Action? = null
  @Volatile private var generation = 0
  @Volatile private var inFlightSince: Long? = null
  @Volatile private var lastStart = Long.MIN_VALUE / 2
  @Volatile private var stored: List<Stored> = emptyList()
  @Volatile private var base: Pair<String, String>? = null // job, part id
  @Volatile private var lastBaseAnswer = Long.MIN_VALUE / 2
  @Volatile var requests = 0
    private set

  /**
   * Should the base part be looked for now? Argument: ms since the last answer about it (huge before the first).
   * Called on the worker.
   */
  @Volatile var baseDue: (Long) -> Boolean = { false }

  /** The server's item for the base part and the frame it was found in; null item = reported missing. Worker. */
  @Volatile var onBase: (PartsFound?, PartsResult, FrameGrab) -> Unit = { _, _, _ -> }

  /** Pick targets for the base part are hidden while this says so (it already lies where the ghost is). */
  @Volatile var hideBaseTarget: () -> Boolean = { false }

  fun setBase(job: String?, partId: String?) {
    base = if (job != null && !partId.isNullOrBlank()) job to partId else null
    lastBaseAnswer = Long.MIN_VALUE / 2
  }

  /** The latest targets with their age now; empty when no `place`/`fasten` action is shown. */
  val pickTargets: List<PickTarget>
    get() {
      val now = clockMs()
      val hide = if (hideBaseTarget()) base?.second else null
      return stored.filter { it.t.id != hide }.map { it.t.copy(ageMs = now - it.tMs) }
    }

  /**
   * The action shown now. Only [isPick] actions with a non-empty [want] are searched; anything else stops it and
   * clears the targets. Changing the want list clears the old targets and asks again at the next [tick].
   */
  @Synchronized
  fun setAction(job: String?, step: Int, action: Int, isPick: Boolean, want: List<String>) {
    val w = want.filter { it.isNotBlank() }.distinct()
    val next = if (job != null && isPick && w.isNotEmpty()) Action(job, step, action, w) else null
    val cur = current
    if (cur != null && next != null && cur.job == next.job && cur.step == next.step && cur.want == next.want) {
      current = next
      return
    }
    current = next
    generation++
    stored = emptyList()
    lastStart = Long.MIN_VALUE / 2 // ask at once
  }

  val active: Boolean
    get() = current != null

  /** Call every ~250 ms on the worker: starts a request when one is due. Returns true when it started one. */
  fun tick(): Boolean {
    val now = clockMs()
    val a = current
    val b = base?.takeIf { baseDue(now - lastBaseAnswer) }
    if (a == null && b == null) return false
    val since = inFlightSince
    if (since != null) {
      if (now - since < stuckMs) return false
      log.error("parts", "no frame or answer in ${stuckMs / 1000} s, retrying")
    }
    if (now - lastStart < periodMs) return false
    lastStart = now
    inFlightSince = now
    val job = a?.job ?: b!!.first
    val req = Request(job, a, generation, b?.takeIf { it.first == job }?.second)
    grab { g -> submit { run(req, g) } }
    return true
  }

  private fun run(q: Request, g: FrameGrab) {
    val t0 = clockMs()
    val r =
        try {
          requests++
          query(q.job, g.jpeg, q.want, q.a?.step)
        } catch (e: Exception) {
          log.event("parts", mapOf("ok" to false, "ms" to clockMs() - t0, "want" to q.want, "step" to q.a?.step,
              "error" to (e.message ?: e.javaClass.simpleName)))
          inFlightSince = null
          return
        }
    val ms = clockMs() - t0
    // the base part's answer holds whatever action is shown now
    q.baseId?.let { id ->
      lastBaseAnswer = clockMs()
      try {
        onBase(r.found.firstOrNull { it.id == id }, r, g)
      } catch (e: Exception) {
        log.error("parts_base", e.message)
      }
    }
    val a = q.a
    if (a == null || q.gen != generation) { // base only, or the action changed while asking
      inFlightSince = null
      if (q.baseId != null) logAnswer(q, g, r, emptyList(), 0, ms)
      return
    }
    val rays = r.found.map { f -> g.worldFromCamera?.let { PickRays.boxRay(it, f.box_2d, g.intrinsics, useDistortion) } }
    val finish = { hits: List<Vec3?> -> complete(q, a, g, r, rays, hits, ms) }
    val d = depth
    val castable = rays.filterNotNull()
    if (d != null && castable.isNotEmpty()) {
      d.cast(castable) { hs ->
        val it = hs.iterator()
        submit { finish(rays.map { ray -> if (ray != null && it.hasNext()) it.next() else null }) }
      }
    } else {
      finish(rays.map { null })
    }
  }

  private fun complete(q: Request, a: Action, g: FrameGrab, r: PartsResult, rays: List<Ray?>, hits: List<Vec3?>, ms: Long) {
    inFlightSince = null
    if (q.gen != generation) return
    val s = surfaces()
    val logFound = ArrayList<Map<String, Any?>>()
    val fresh = ArrayList<Stored>()
    r.found.forEachIndexed { i, f ->
      val ray = rays.getOrNull(i)
      val loc = if (ray == null) null else locate(ray, hits.getOrNull(i), s)
      logFound +=
          mapOf("id" to f.id, "confidence" to f.confidence, "box_2d" to f.box_2d, "method" to (loc?.second ?: "none"),
              "pos_m" to loc?.first)
      if (loc != null && f.id in a.want && !r.simulated) { // a simulated box is a placeholder: never invent a position
        val radius = footprintRadius(f.box_2d, g, loc.first)
        fresh +=
            Stored(PickTarget(f.id, f.label.ifEmpty { f.id }, loc.first, f.confidence, 0, f.kind, loc.second, radius,
                f.box_2d), g.tMs)
      }
    }
    // keep older targets the server did not answer for (neither found nor missing) this time
    val answered = r.found.map { it.id }.toSet() + r.missing.toSet()
    stored = fresh + stored.filter { it.t.id !in answered }
    logAnswer(q, g, r, logFound, fresh.size, ms)
  }

  private fun logAnswer(q: Request, g: FrameGrab, r: PartsResult, found: List<Map<String, Any?>>, targets: Int, ms: Long) {
    log.event(
        "parts",
        mapOf(
            "ok" to true,
            "ms" to ms,
            "server_ms" to r.latency_ms,
            "step" to q.a?.step,
            "action" to q.a?.action,
            "want" to q.want,
            "base" to q.baseId,
            "found" to
                found.ifEmpty { r.found.map { mapOf("id" to it.id, "confidence" to it.confidence, "box_2d" to it.box_2d) } },
            "missing" to r.missing,
            "targets" to targets,
            "cam_pose" to (g.worldFromCamera != null),
            "frame" to listOf(g.width, g.height),
            "jpeg_bytes" to g.jpeg.size,
            "simulated" to r.simulated,
            "model" to r.model,
        ),
    )
  }

  private fun footprintRadius(box: List<Double>, g: FrameGrab, p: Vec3): Float {
    val px = PickRays.boxToPx(box, g.intrinsics.width, g.intrinsics.height) ?: return 0.05f
    val cam = g.worldFromCamera ?: return 0.05f
    val sidePx = maxOf(px[2] - px[0], px[3] - px[1])
    return (sidePx * (p - cam.t).length() / g.intrinsics.fx / 2).toFloat().coerceIn(0.02f, 0.25f)
  }

  companion object {
    /** Depth hits nearer than this or farther than [MAX_M] from the camera are ignored. */
    const val MIN_M = 0.15f
    const val MAX_M = 4f

    /**
     * WORLD point for one ray: the [depthHit] when plausible, else the table rectangle (grown 10 cm), else the floor,
     * else the work-surface plane ([PickSurfaces.workY], or the table's height unbounded). Null when nothing is hit.
     */
    fun locate(ray: Ray, depthHit: Vec3?, s: PickSurfaces): Pair<Vec3, String>? {
      if (depthHit != null) {
        val d = (depthHit - ray.origin).length()
        if (d in MIN_M..MAX_M) return depthHit to "depth"
      }
      s.table?.let { t ->
        PickRays.intersectHorizontal(ray, t.y, MAX_M)?.let { p -> if (t.contains(p, 0.10f)) return p to "table" }
      }
      s.floorY?.let { y -> PickRays.intersectHorizontal(ray, y, MAX_M)?.let { return it to "floor" } }
      (s.workY ?: s.table?.y)?.let { y -> PickRays.intersectHorizontal(ray, y, MAX_M)?.let { return it to "plane" } }
      return null
    }
  }
}
