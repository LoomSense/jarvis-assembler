package com.agj.android.world

import android.content.Context
import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.guide.ActionKind
import com.agj.android.guide.Assembly
import com.agj.android.guide.GuideAction
import com.agj.android.net.ServerApi
import com.agj.android.vision.VisionModule
import com.meta.spatial.mruk.MRUKFeature
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * The world package's single entry point for MainActivity: the room's table (MRUK) for placement without a marker,
 * and the parts in the pile in 3D ([PickTarget]) during `place`/`fasten` actions.
 *
 * Wiring (MainActivity):
 * ```
 * private var mruk: MRUKFeature? = null
 * private lateinit var world: WorldModule
 * override fun registerFeatures() = listOf(VRFeature(this), ComposeFeature(), MRUKFeature(this, systemManager).also { mruk = it })
 * // onCreate, after vision + api:
 * world = WorldModule(this, vision, mruk, { api }, params.lang)
 * // after the permission prompt (startCamera), main thread:
 * world.start()
 * // showStep(): the action shown now
 * world.setAction(assembly?.jobId, step, action, act)
 * // REGISTER fallback (no marker after 5 s): the table if the scene has one, else the fixed spot
 * tableFallback = world.tablePose(head, assembly) ?: fixedTableSpot(head)
 * world.workSurfaceY = tableFallback?.t?.y          // rays onto this plane when there is no scene table
 * // the pick-up cue (ring + bobbing chevron, world/PickMarkers.kt), created in onCreate with the ghost's mesh hook:
 * pickMarkers = PickMarkers { uri, make -> systemManager.findSystem<MeshCreationSystem>().meshManager.registerMeshCreator(uri) { _, _ -> make() } }
 * // frameTick, inside `if (assembly != null && sceneReady)`, after ghost.tick(dt):
 * pickMarkers.update(if (coach.stage == CoachFlow.Stage.GUIDE && !finished) world.pickTargets else emptyList(), dt)
 * // world.pickTargets: List<PickTarget(id, label, worldPos (WORLD), confidence, ageMs, kind, method, radiusM, box)>
 * // onDestroy:
 * world.stop()
 * ```
 * Threading: [start]/[stop] on the main thread; the rest from any thread.
 */
class WorldModule(
    private val context: Context,
    private val vision: VisionModule,
    private val mruk: MRUKFeature?,
    private val api: () -> ServerApi?,
    private val lang: String,
) {
  val scene = SceneTable(context, mruk, { vision.heads.newest() }, Agq.log)
  private val depth = mruk?.let { MrukDepthRaycaster(it, Agq.log) }
  private var worker: ScheduledExecutorService? = null

  /** Height (WORLD y) of the surface the assembly stands on when no scene table is known (fixed spot / marker). */
  @Volatile var workSurfaceY: Float? = null

  val parts =
      PartsFinder(
          grab = { cb -> vision.grabFrame(1280, 80, cb) },
          query = { job, jpeg, want, step ->
            val a = api() ?: throw IllegalStateException("no server")
            runBlocking { a.parts(job, jpeg, want, step, lang) }
          },
          // no scene table: the fixed-spot / marker-tracked assembly's base height (ASSEMBLY origin on the table)
          surfaces = { PickSurfaces(scene.table, scene.floorY, workSurfaceY ?: vision.worldFromAssembly()?.t?.y) },
          submit = { r -> worker?.execute(r) },
          log = Agq.log,
          depth = depth,
          useDistortion = vision.config.detector.useDistortion,
      )

  /** The table chosen from the room model, null until (or unless) one is found. */
  val table: TableSurface?
    get() = scene.table

  /** Targets for the UI: real parts in the pile, WORLD positions; empty outside `place`/`fasten` actions. */
  val pickTargets: List<PickTarget>
    get() = parts.pickTargets

  private var started = false

  /** Idempotent (MainActivity may call startCamera twice). */
  fun start() {
    if (started) return
    started = true
    scene.start()
    depth?.start()
    if (worker == null) {
      val w = Executors.newSingleThreadScheduledExecutor { Thread(it, "agj-parts").apply { isDaemon = true } }
      worker = w
      w.scheduleWithFixedDelay(
          {
            try {
              parts.tick()
            } catch (e: Exception) {
              Agq.log.error("parts", e.message)
            }
          },
          500,
          250,
          TimeUnit.MILLISECONDS,
      )
    }
  }

  fun stop() {
    worker?.shutdownNow()
    worker = null
    depth?.stop()
  }

  /** WORLD ← TABLE on the scene table for [footprintW] × [footprintD] m, facing the wearer; null without a table. */
  fun tablePose(head: Pose3, footprintW: Float, footprintD: Float): Pose3? =
      scene.table?.let { TableFinder.place(it, head, footprintW, footprintD) }

  /** Same, with the footprint from the manifest's `overall_mm` (L along X, W along Z; 0.4 m square if unknown). */
  fun tablePose(head: Pose3, assembly: Assembly?): Pose3? {
    val o = assembly?.manifest?.overall_mm
    val w = o?.L?.takeIf { it > 0 }?.let { (it / 1000).toFloat() } ?: 0.4f
    val d = o?.W?.takeIf { it > 0 }?.let { (it / 1000).toFloat() } ?: 0.4f
    return tablePose(head, w, d)
  }

  /** The action shown now: searches the pile for its part / hardware while it is a `place` or `fasten` action. */
  fun setAction(jobId: String?, step: Int, action: Int, act: GuideAction?) {
    val pick = act != null && (act.kind == ActionKind.PLACE || act.kind == ActionKind.FASTEN)
    parts.setAction(jobId, step, action, pick, listOfNotNull(act?.partId, act?.code))
  }
}
