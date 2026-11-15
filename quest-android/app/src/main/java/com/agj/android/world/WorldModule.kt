package com.agj.android.world

import android.content.Context
import android.os.SystemClock
import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.guide.ActionKind
import com.agj.android.guide.Assembly
import com.agj.android.guide.Guide
import com.agj.android.guide.GuideAction
import com.agj.android.net.PartsFound
import com.agj.android.net.PartsResult
import com.agj.android.net.ServerApi
import com.agj.android.vision.FrameGrab
import com.agj.android.vision.VisionModule
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.runtime.SceneMesh
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * The world package's single entry point for MainActivity, and **the registration path when there is no printed
 * marker**: it owns the assembly anchor ([AssemblyAnchor], WORLD ← TABLE) and feeds it to the tracker
 * (`VisionModule.placeAssembly`), so `vision.worldFromAssembly()` is non-null as soon as something is placed and
 * MainActivity's existing flow (REGISTER → `coach.registered()`, `ghost.setAssemblyPose(pose)`) works unchanged.
 * `track.source` / `place.source` say what placed it: `table`, `fixed_spot`, `server_box`, `manual`, `markerless`
 * (or `marker` when the strip is seen, which still wins).
 *
 *  1. **Table first.** As soon as the guide is loaded ([setAssembly]) and the head pose is known, the anchor goes on
 *     the MRUK table in front of the user ([TableFinder.place], base part centred, front facing the user); without a
 *     scene table after [FIXED_SPOT_AFTER_MS], on the fixed spot (and onto the table if one turns up later).
 *  2. **Coarse placement from the camera.** [PartsFinder] asks `/parts` for the base part (the first part the guide
 *     places, e.g. the drawer front); [BasePlacement.fromBox] ray-casts its box onto the anchor plane and the anchor
 *     moves there once; later only on [realign] or a disagreement of > 10 cm that lasts ([AssemblyAnchor]).
 *  3. **Outline + coach line.** [PlaceOutline] draws the base part's rectangle on the table; [coachLine] is the
 *     sentence for the step window ("Place the drawer front inside the blue outline").
 *  4. **Manual.** [nudge] slides / twists the anchor (menu or voice); after it only [realign] lets the camera move it.
 *  5. **Markerless plug.** [onObjectPose] takes a WORLD ← ASSEMBLY pose from any other method.
 * Plus the parts in the pile ([pickTargets], drawn by [PickMarkers]).
 *
 * Wiring (MainActivity), all main thread:
 * ```
 * private var mruk: MRUKFeature? = null
 * private lateinit var world: WorldModule
 * override fun registerFeatures() = listOf(VRFeature(this), ComposeFeature(), MRUKFeature(this, systemManager).also { mruk = it })
 * // onCreate, after vision + api:
 * world = WorldModule(this, vision, mruk, { api }, params.lang) { uri, make ->   // the mesh hook draws the outline
 *   systemManager.findSystem<MeshCreationSystem>().meshManager.registerMeshCreator(uri) { _, _ -> make() }
 * }
 * // startCamera(), after vision.start():
 * world.start()
 * // openJob(), after `assembly = a`:
 * world.setAssembly(a)
 * // showStep(), after ghost.setStep(step, action):
 * world.setAction(assembly, step, action)
 * // frameTick(), first line inside `if (assembly != null && sceneReady)` is fine (the pose it places is read by
 * // `vision.worldFromAssembly()` on the next frame):
 * world.tick(dt)
 * // the pick-up cues stay as wired: pickMarkers.update(world.pickTargets, dt)
 * // step window, while world.coachLine(lang) != null: show it (REGISTER card body, or the status line in GUIDE)
 * // menu / voice: world.realign(); world.nudge(rightM, forwardM, yawDeg)
 * // markerless method (vision/PanelPose.kt): world.onObjectPose(worldFromAssembly, confidence, source = "markerless")
 * // onDestroy:
 * world.stop()
 * ```
 * Threading: [start]/[stop]/[tick] on the main thread; the rest from any thread.
 */
class WorldModule(
    private val context: Context,
    private val vision: VisionModule,
    private val mruk: MRUKFeature?,
    private val api: () -> ServerApi?,
    private val lang: String,
    registerMesh: ((String, () -> SceneMesh) -> Unit)? = null,
) {
  val scene = SceneTable(context, mruk, { vision.heads.newest() }, Agq.log)
  private val depth = mruk?.let { MrukDepthRaycaster(it, Agq.log) }
  private var worker: ScheduledExecutorService? = null
  private val outline = registerMesh?.let { PlaceOutline(it) }

  /** The base part: what the guide places first, and what the anchor is aligned to. */
  class BasePart(val partId: String, val node: String, val step: Int, val names: Map<String, String>, val positions: FloatArray)

  @Volatile private var assembly: Assembly? = null
  @Volatile private var guide: Guide? = null
  @Volatile var basePart: BasePart? = null
    private set
  @Volatile private var curStep: Int? = null
  @Volatile private var curKind: ActionKind? = null
  @Volatile private var assemblySetMs = 0L

  /** Height (WORLD y) of the surface the assembly stands on when nothing is placed yet (legacy fixed spot). */
  @Volatile var workSurfaceY: Float? = null

  /** The assembly anchor (WORLD ← TABLE) and its source; see [AssemblyAnchor]. */
  val anchor = AssemblyAnchor(Agq.log, onChange = { _, _ -> inject() })

  val parts =
      PartsFinder(
          grab = { cb -> vision.grabFrame(1280, 80, cb) },
          query = { job, jpeg, want, step ->
            val a = api() ?: throw IllegalStateException("no server")
            runBlocking { a.parts(job, jpeg, want, step, lang) }
          },
          surfaces = { PickSurfaces(scene.table, scene.floorY, anchor.pose?.t?.y ?: workSurfaceY ?: vision.worldFromAssembly()?.t?.y) },
          submit = { r -> worker?.execute(r) },
          log = Agq.log,
          depth = depth,
          useDistortion = vision.config.detector.useDistortion,
      ).also { p ->
        // Automatic checks only while the base part's own step is on (later the box would be the half-built
        // assembly, and a wrong jump mid-guide is worse than a small offset); a re-align asks at any time.
        p.baseDue = { since ->
          val onBaseStep = curStep == null || curStep == basePart?.step
          !markerTracking() && (anchor.realignRequested || onBaseStep) && anchor.wantsServerBox(since)
        }
        p.onBase = ::onBase
        p.hideBaseTarget = { anchor.serverPlaced }
      }

  /** The table chosen from the room model, null until (or unless) one is found. */
  val table: TableSurface?
    get() = scene.table

  /** Targets for the UI: real parts in the pile, WORLD positions; empty outside `place`/`fasten` actions. */
  val pickTargets: List<PickTarget>
    get() = parts.pickTargets

  /** `table`, `fixed_spot`, `server_box`, `manual`, `markerless`; null before anything is placed. */
  val placeSource: String?
    get() = anchor.sourceName

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

  // ---- the guide ----

  /** The loaded guide (call when the job is loaded, before REGISTER): finds the base part and arms the placement. */
  fun setAssembly(a: Assembly?) {
    if (a?.jobId != assembly?.jobId) anchor.reset()
    assembly = a
    val g = a?.guide
    guide = g
    assemblySetMs = SystemClock.elapsedRealtime()
    val b = g?.let { findBase(it) }
    basePart = b
    if (curStep == null) curStep = g?.steps?.firstOrNull()?.n
    parts.setBase(a?.jobId, b?.partId)
    Agq.log.event("place_base", mapOf("job" to a?.jobId, "part" to b?.partId, "node" to b?.node, "step" to b?.step,
        "footprint_m" to footprint()?.let { listOf(2 * it.halfX, 2 * it.halfZ) }))
  }

  /** The action shown now (after `ghost.setStep`): pile search for its part, and the anchor follows the step's pose. */
  fun setAction(a: Assembly?, step: Int, action: Int) {
    if (a != null && a !== assembly) setAssembly(a)
    val act = a?.guide?.step(step)?.actions?.getOrNull(action)
    setAction(a?.jobId, step, action, act)
  }

  /** Same, when the caller already has the action. */
  fun setAction(jobId: String?, step: Int, action: Int, act: GuideAction?) {
    curStep = step
    curKind = act?.kind
    val pick = act != null && (act.kind == ActionKind.PLACE || act.kind == ActionKind.FASTEN)
    parts.setAction(jobId, step, action, pick, listOfNotNull(act?.partId, act?.code))
    inject() // the step's pose may have changed (a turn): keep WORLD ← TABLE where it is
  }

  private fun findBase(g: Guide): BasePart? {
    for (s in g.steps) for (a in s.actions) {
      val id = a.partId ?: continue
      val node = a.node ?: continue
      val geo = a.geometry ?: continue
      if (a.kind != ActionKind.PLACE) continue
      return BasePart(id, node, s.n, a.labels.mapValues { it.value.name }, geo.positions)
    }
    return null
  }

  /** TABLE ← ASSEMBLY the real object holds now (before a turn is done: the previous step's pose), as the renderer. */
  private fun heldPose(): Pose3 {
    val g = guide ?: return Pose3.IDENTITY
    val i = g.steps.indexOfFirst { it.n == curStep }.takeIf { it >= 0 } ?: 0
    val s = g.steps.getOrNull(i) ?: return Pose3.IDENTITY
    return if (curKind == ActionKind.TURN) g.steps.getOrNull(i - 1)?.tableFromAssembly ?: s.tableFromAssembly
    else s.tableFromAssembly
  }

  /** The base part's rectangle on the table in the pose the assembly holds now. */
  fun footprint(): Footprint? = basePart?.let { Footprint.of(it.positions, heldPose()) }

  // ---- the anchor ----

  private fun markerTracking(): Boolean = vision.snapshot().let { it.method == "marker" || it.method == "anchor" }

  /** Hand the anchor to the tracker as WORLD ← ASSEMBLY (`track.source` = the anchor's source). */
  private fun inject() {
    val p = anchor.pose ?: return
    val s = anchor.source ?: return
    vision.placeAssembly(p * heldPose(), anchor.sourceName ?: s.wire)
  }

  /** Anchor pose that puts the base part's centre at [at] (a pose on the table whose origin is the wanted centre). */
  private fun centred(at: Pose3): Pose3 {
    val fp = footprint() ?: return at
    val off = at.q.rotate(com.agj.android.core.Vec3(fp.cx, 0f, fp.cz))
    return Pose3(com.agj.android.core.Vec3(at.t.x - off.x, at.t.y, at.t.z - off.z), at.q)
  }

  /**
   * Once per render frame (main thread): places the anchor when it can (table, else the fixed spot) and draws the
   * outline. (The pick-up cues are MainActivity's `pickMarkers.update(world.pickTargets, dt)`.)
   */
  @Suppress("UNUSED_PARAMETER")
  fun tick(dtS: Float) {
    val head = vision.heads.newest()
    if (assembly != null && head != null && !markerTracking()) {
      val t = scene.table
      val fp = footprint()
      val w = fp?.let { 2 * it.halfX } ?: 0.4f
      val d = fp?.let { 2 * it.halfZ } ?: 0.3f
      if (t != null && (anchor.source == null || anchor.source == PlaceSource.FIXED_SPOT)) {
        anchor.placeInitial(centred(TableFinder.place(t, head, w, d)), PlaceSource.TABLE)
      } else if (t == null && anchor.source == null && SystemClock.elapsedRealtime() - assemblySetMs >= FIXED_SPOT_AFTER_MS) {
        anchor.placeInitial(centred(BasePlacement.fixedSpot(head)), PlaceSource.FIXED_SPOT)
      }
    }
    outline?.update(if (outlineVisible()) anchor.pose else null, footprint())
  }

  /** The outline shows while the anchor is a placed one (no live tracking) and the base part's step is on. */
  private fun outlineVisible(): Boolean {
    val s = anchor.source ?: return false
    if (s == PlaceSource.MARKERLESS || markerTracking()) return false
    val b = basePart ?: return false
    return curStep == null || curStep == b.step
  }

  private fun onBase(found: PartsFound?, r: PartsResult, g: FrameGrab) {
    if (r.simulated) return // a placeholder box is not a position
    if (found == null) {
      anchor.onServerMiss()
      return
    }
    val cur = anchor.pose ?: return
    val fp = footprint() ?: return
    val bp = BasePlacement.fromBox(found.box_2d, g, cur.t.y, cur, fp, vision.config.detector.useDistortion)
    val onTable = bp != null && (scene.table?.contains(bp.partCenter, 0.10f) ?: true)
    // The box must be about the size of the part: on the first device run a 0.89 m box (not the 0.38 m drawer
    // front) at confidence 0.95 moved the ghost 1.36 m away.
    val longSide = 2f * maxOf(fp.halfX, fp.halfZ)
    val sizeOk = bp?.lengthM?.let { it >= 0.6f * longSide && it <= 1.5f * longSide } ?: false
    val ok = bp != null && onTable && sizeOk && found.confidence >= MIN_BASE_CONFIDENCE
    val applied = if (ok) anchor.onServerBox(bp!!.worldFromTable) else false
    Agq.log.event(
        "place_candidate",
        mapOf("source" to "server_box", "part" to found.id, "confidence" to found.confidence, "box_2d" to found.box_2d,
            "center_m" to bp?.partCenter, "length_m" to bp?.lengthM, "yaw_from_box" to bp?.yawFromBox,
            "dist_m" to bp?.let { AssemblyAnchor.flatDist(it.worldFromTable.t, cur.t) }, "on_table" to onTable, "size_ok" to sizeOk,
            "cam_pose" to (g.worldFromCamera != null), "applied" to applied),
    )
  }

  /** "Re-align": the next camera answer for the base part moves the anchor (also after a manual nudge). */
  fun realign() = anchor.requestRealign()

  /** Slide the ghost on the table ([rightM], [forwardM] away from the user) and twist it by [yawDeg]. */
  fun nudge(rightM: Float, forwardM: Float, yawDeg: Float = 0f): Boolean =
      anchor.nudge(rightM, forwardM, Math.toRadians(yawDeg.toDouble()).toFloat())

  /**
   * Plug for an object pose from any other method (vision/PanelPose.kt's `PanelPoseTracker`): WORLD ← ASSEMBLY
   * (right-handed WORLD, the app's frames) with a confidence 0–1. Below 0.5 it is ignored; changes under 1.5 cm and
   * 2° are ignored too (no jitter). [source] is what `track.source` / `place.source` then say (default
   * `markerless`). Returns true when the anchor moved. Any thread.
   */
  fun onObjectPose(worldFromAssembly: Pose3, confidence: Float, source: String = "markerless"): Boolean =
      anchor.onObjectPose(worldFromAssembly * heldPose().inverse(), confidence, source)

  /**
   * The coach line while the object is not tracked: "Place the drawer front inside the blue outline." Null when
   * there is nothing to say (tracked by the marker or a markerless pose, nothing placed, no base part).
   */
  fun coachLine(lang: String = this.lang): String? {
    val s = anchor.source ?: return null
    if (s == PlaceSource.MARKERLESS || markerTracking()) return null
    val b = basePart ?: return null
    val name = b.names[if (lang == "el") "el" else "en"] ?: b.names["en"] ?: return null
    return WorldStrings.placeInOutline(lang, name)
  }

  // ---- older entry points (still valid) ----

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

  companion object {
    /** Without a scene table this long after the guide is loaded, the fixed spot is used. */
    const val FIXED_SPOT_AFTER_MS = 2500L
    const val MIN_BASE_CONFIDENCE = 0.4
  }
}

/** The world package's own copy (ui/Strings.kt belongs to the UI). EL + EN. */
object WorldStrings {
  /** [name] as the label pill shows it ("Drawer front" / "Πρόσοψη συρταριού"). */
  fun placeInOutline(lang: String, name: String): String =
      if (lang == "el") "Βάλε το κομμάτι «$name» μέσα στο μπλε περίγραμμα."
      else "Place the ${name.replaceFirstChar { it.lowercase() }} inside the blue outline."

  fun realigning(lang: String): String =
      if (lang == "el") "Κοιτάζω ξανά πού είναι το κομμάτι." else "Looking again for where the part is."

  fun moved(lang: String): String = if (lang == "el") "Το μετακίνησα πάνω στο κομμάτι." else "Moved it onto the part."
}
