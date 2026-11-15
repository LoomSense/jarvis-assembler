package com.agj.android.world

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.vision.Frames
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.MRUKAnchor
import com.meta.spatial.mruk.MRUKEnvironmentRaycastHitResult
import com.meta.spatial.mruk.MRUKEnvironmentRaycasterStatus
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLabel
import com.meta.spatial.mruk.MRUKLoadDeviceResult
import com.meta.spatial.mruk.MRUKPlane
import com.meta.spatial.mruk.MRUKRoom
import com.meta.spatial.mruk.MRUKSceneEventListener
import com.meta.spatial.mruk.MRUKVolume
import com.meta.spatial.mruk.SceneModel
import com.meta.spatial.mruk.hasLabel
import com.meta.spatial.toolkit.Transform

/**
 * The room model (Meta MRUK, `meta-spatial-sdk-mruk` 0.15.0) → the table surface ([TableFinder]) and the floor height.
 *
 * [start] (main thread, after the `com.oculus.permission.USE_SCENE` prompt): `loadSceneFromDevice` (never asks for a
 * new scan: `requestSceneCaptureIfNoDataFound = false`), then reads every anchor's plane / volume, converts it from
 * the left-handed Spatial frame into WORLD ([Frames.spatialToWorld] + [Frames.mirrorZ] on local points, the same
 * rule as the rest of the app) and picks the table. Re-evaluates when MRUK reports room changes.
 *
 * Logs `scene` (load result, rooms, anchors, labels), `table_found` (`size_m`, `height_m`, `y_m`, `center_m`,
 * `dist_m`, `reason`, `source`, `plane_up_axis`) or `table_missing` (`reason`: `permission_denied`, `no_mruk`,
 * `error_no_rooms_found` = no scene model on this headset, `no_level_surface`, `none_in_front`, `no_head_pose`, …).
 * The app then falls back to the fixed spot.
 *
 * UNVERIFIED on device: plane axes (the up axis is detected per anchor, logged as `plane_up_axis`), whether anchor
 * Transforms are already in the scene's world frame when `loadSceneFromDevice` completes.
 */
class SceneTable(
    private val context: Context,
    private val mruk: MRUKFeature?,
    private val head: () -> Pose3?,
    private val log: AgqLog,
) {
  @Volatile var table: TableSurface? = null
    private set

  /** Median floor-anchor height; null without a scene. */
  @Volatile var floorY: Float? = null
    private set

  /** `idle`, `loading`, `loaded`, `failed`. */
  @Volatile var status: String = "idle"
    private set

  private val main = Handler(Looper.getMainLooper())
  private var permissionTries = 0
  private var headTries = 0
  private var lastLogged: String? = null
  private var listening = false

  fun start() {
    val m = mruk
    if (m == null) {
      missing("no_mruk", emptyMap())
      return
    }
    if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
      // the prompt may still be open: look again for 30 s
      if (permissionTries++ == 0) missing("permission_denied", mapOf("permission" to PERMISSION))
      if (permissionTries < 15) main.postDelayed({ start() }, 2000)
      return
    }
    status = "loading"
    val t0 = android.os.SystemClock.elapsedRealtime()
    try {
      m.loadSceneFromDevice(true, false, SceneModel.V2_FALLBACK_V1).whenComplete { r, e ->
        main.post { onLoaded(r, e, android.os.SystemClock.elapsedRealtime() - t0) }
      }
    } catch (e: Throwable) {
      status = "failed"
      missing("load_threw", mapOf("error" to e.message))
    }
    if (!listening) {
      listening = true
      runCatching {
        m.addSceneEventListener(
            object : MRUKSceneEventListener {
              override fun onRoomAdded(room: MRUKRoom) = refreshSoon()

              override fun onRoomUpdated(room: MRUKRoom) = refreshSoon()

              override fun onRoomRemoved(room: MRUKRoom) = refreshSoon()
            },
        )
      }
    }
  }

  private fun onLoaded(r: MRUKLoadDeviceResult?, e: Throwable?, ms: Long) {
    val m = mruk ?: return
    val rooms = runCatching { m.rooms }.getOrDefault(emptyList())
    log.event("scene", mapOf("result" to (r?.name?.lowercase() ?: "exception"), "error" to e?.message, "ms" to ms,
        "rooms" to rooms.size, "anchors" to rooms.sumOf { it.anchors.size }))
    if (e != null || r != MRUKLoadDeviceResult.SUCCESS) {
      status = "failed"
      missing(r?.name?.lowercase() ?: "load_failed", mapOf("error" to e?.message))
      return
    }
    status = "loaded"
    evaluate()
  }

  private var refreshPending = false

  private fun refreshSoon() {
    if (refreshPending) return
    refreshPending = true
    main.postDelayed({
      refreshPending = false
      if (status == "loaded") evaluate()
    }, 500)
  }

  /** Read the anchors and choose the table (main thread). */
  fun evaluate() {
    val m = mruk ?: return
    val h = head()
    if (h == null) {
      if (headTries++ < 20) main.postDelayed({ evaluate() }, 500) else missing("no_head_pose", emptyMap())
      return
    }
    val rooms = runCatching { m.rooms }.getOrDefault(emptyList())
    val surfaces = ArrayList<SceneSurface>()
    val floors = ArrayList<Float>()
    val labelsSeen = LinkedHashMap<String, Int>()
    val upAxes = HashMap<String, String>()
    for (room in rooms) {
      for (e in room.anchors) {
        try {
          val a = e.tryGetComponent<MRUKAnchor>() ?: continue
          val labels = MRUKLabel.entries.filter { a.hasLabelSafe(it) }.map { it.name }.toSet()
          labels.forEach { labelsSeen[it] = (labelsSeen[it] ?: 0) + 1 }
          val pose = e.worldPose() ?: continue
          if ("FLOOR" in labels) floors += pose.t.y
          val id = runCatching { a.uuid.toString() }.getOrDefault(e.id.toString())
          e.tryGetComponent<MRUKPlane>()?.let { p ->
            val (corners, up) = planeCorners(pose, p.min.x, p.min.y, p.max.x, p.max.y)
            upAxes[id] = up
            surfaces += SceneSurface(id, labels, corners, "plane")
          }
          e.tryGetComponent<MRUKVolume>()?.let { v ->
            surfaces += SceneSurface(id + "/volume", labels, volumeTop(pose, v), "volume")
          }
        } catch (t: Throwable) {
          log.error("scene_anchor", t.message)
        }
      }
    }
    val fy = floors.sorted().getOrNull(floors.size / 2)
    floorY = fy
    val choice = TableFinder.choose(surfaces, h, fy ?: 0f)
    val t = choice.table
    table = t
    if (t != null) {
      val key = "found:${t.id}:${"%.2f".format(t.y)}"
      if (key != lastLogged) {
        lastLogged = key
        log.event(
            "table_found",
            mapOf(
                "id" to t.id,
                "label" to t.label,
                "source" to t.source,
                "reason" to choice.reason,
                "size_m" to listOf(t.sizeM.first, t.sizeM.second),
                "height_m" to t.heightM,
                "y_m" to t.y,
                "center_m" to t.center,
                "dist_m" to t.flatDistance(h.t),
                "floor_y_m" to fy,
                "head_m" to h.t,
                "plane_up_axis" to upAxes[t.id],
                "candidates" to choice.candidates,
                "labels" to labelsSeen,
            ),
        )
      }
    } else {
      missing(choice.reason, mapOf("surfaces" to surfaces.size, "level" to choice.horizontal, "labels" to labelsSeen,
          "floor_y_m" to fy, "head_m" to h.t))
    }
  }

  private fun missing(reason: String, extra: Map<String, Any?>) {
    val key = "missing:$reason"
    if (key == lastLogged) return
    lastLogged = key
    log.event("table_missing", mapOf("reason" to reason, "fallback" to "fixed_spot") + extra)
  }

  // ---- MRUK geometry → WORLD ----

  private fun Entity.worldPose(): Pose3? {
    val p = tryGetComponent<Transform>()?.transform ?: return null
    return Frames.spatialToWorld(p.t.x, p.t.y, p.t.z, p.q.x, p.q.y, p.q.z, p.q.w)
  }

  /** A local (Spatial, left-handed) point of an anchor → WORLD. */
  private fun Pose3.local(x: Float, y: Float, z: Float): Vec3 = transform(Frames.mirrorZ(Vec3(x, y, z)))

  /**
   * The plane's rectangle in WORLD. The plane lies in two local axes; the third (its normal) is the one most
   * aligned with WORLD up for a table. OpenXR planes are local XY with +Z normal; if the SDK uses another axis it is
   * detected here and returned ("x", "y" or "z").
   */
  private fun planeCorners(pose: Pose3, x0: Float, y0: Float, x1: Float, y1: Float): Pair<List<Vec3>, String> {
    fun upness(a: Vec3) = kotlin.math.abs(pose.q.rotate(Frames.mirrorZ(a)).y)
    val ups = listOf("x" to upness(Vec3(1f, 0f, 0f)), "y" to upness(Vec3(0f, 1f, 0f)), "z" to upness(Vec3(0f, 0f, 1f)))
    val up = ups.maxBy { it.second }.first
    val pts =
        listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1).map { (a, b) ->
          when (up) {
            "z" -> pose.local(a, b, 0f)
            "y" -> pose.local(a, 0f, b)
            else -> pose.local(0f, a, b)
          }
        }
    return pts to up
  }

  /** The four highest of a volume's eight corners (its top face) in WORLD. */
  private fun volumeTop(pose: Pose3, v: MRUKVolume): List<Vec3> {
    val all = ArrayList<Vec3>(8)
    for (x in listOf(v.min.x, v.max.x)) for (y in listOf(v.min.y, v.max.y)) for (z in listOf(v.min.z, v.max.z)) {
      all += pose.local(x, y, z)
    }
    return all.sortedByDescending { it.y }.take(4)
  }

  private fun MRUKAnchor.hasLabelSafe(l: MRUKLabel): Boolean = runCatching { hasLabel(l) }.getOrDefault(false)

  companion object {
    const val PERMISSION = "com.oculus.permission.USE_SCENE"
  }
}

/**
 * Meta's environment raycaster (depth-based, MRUK 0.15 `startEnvironmentRaycaster` / `raycastEnvironment`) as a
 * [DepthRaycaster]. Raycasts run on the main thread; rays and hits are converted between WORLD and Spatial axes.
 * UNVERIFIED on device: `start()` result and hit quality; `parts.found[].method` shows whether `depth` was used.
 */
class MrukDepthRaycaster(private val mruk: MRUKFeature, private val log: AgqLog) : DepthRaycaster {
  private val main = Handler(Looper.getMainLooper())
  @Volatile private var started = false

  /** Main thread. Logs `depth_raycaster` with the result (`success`, `error`, `unsupported`). */
  fun start() {
    val r = runCatching { mruk.startEnvironmentRaycaster().name.lowercase() }.getOrElse { "exception: ${it.message}" }
    started = r == "success"
    log.event("depth_raycaster", mapOf("result" to r))
  }

  fun stop() {
    if (started) runCatching { mruk.stopEnvironmentRaycaster() }
    started = false
  }

  override fun cast(rays: List<Ray>, done: (List<Vec3?>) -> Unit) {
    if (!started) {
      done(rays.map { null })
      return
    }
    main.post {
      val out =
          try {
            if (mruk.getEnvironmentRaycasterStatus() != MRUKEnvironmentRaycasterStatus.READY) rays.map { null }
            else
                rays.map { r ->
                  val o = Frames.mirrorZ(r.origin)
                  val d = Frames.mirrorZ(r.dir)
                  val hit = mruk.raycastEnvironment(Vector3(o.x, o.y, o.z), Vector3(d.x, d.y, d.z), PartsFinder.MAX_M)
                  if (hit.result == MRUKEnvironmentRaycastHitResult.SUCCESS)
                      Frames.mirrorZ(Vec3(hit.point.x, hit.point.y, hit.point.z))
                  else null
                }
          } catch (e: Throwable) {
            log.error("depth_raycast", e.message)
            rays.map { null }
          }
      done(out)
    }
  }
}
