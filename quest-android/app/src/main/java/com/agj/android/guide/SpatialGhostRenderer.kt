package com.agj.android.guide

import android.net.Uri
import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.vision.Frames
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** What the label pill shows (the ui/ layer draws it in a mesh-mode Compose panel). */
data class LabelInfo(val label: PartLabel, val state: GhostState, val lost: Boolean)

/*
 * The ONE place where our right-handed frames meet the left-handed Spatial SDK (vision/Frames.kt has the frame
 * definitions). Everything in the app (tracker poses, the guide's ASSEMBLY data, the head pose, window and label
 * placement, touch registration) is right-handed WORLD/ASSEMBLY; these functions apply the Z mirror on the way in
 * and out. Mesh vertices go through [SpatialMesh.fromGltf].
 */

/** A right-handed parent ← child pose (WORLD ← X, or a local child pose) → Spatial SDK `Pose`. */
fun Pose3.toSdk(): Pose = Frames.mirrorZ(this).let { m -> Pose(Vector3(m.t.x, m.t.y, m.t.z), Quaternion(m.q.w, m.q.x, m.q.y, m.q.z)) }

/** A right-handed point or offset → Spatial SDK `Vector3`. */
fun Vec3.toSdk(): Vector3 = Vector3(x, y, -z)

/** A Spatial SDK pose (e.g. `scene.getViewerPose()`) → right-handed WORLD. */
fun Pose.toPose3(): Pose3 = Frames.spatialToWorld(t.x, t.y, t.z, q.x, q.y, q.z, q.w)

/**
 * A panel at [posWorld] turned about the vertical to face [headWorld] (both WORLD). The rotation is built in Spatial
 * axes with the SDK's own `lookRotationAroundY(panel − head)` (the SDK's panel convention; UNVERIFIED on device that
 * the readable side then faces the viewer: if a panel shows its back, negate the vector here, once for all panels).
 */
fun facingPose(posWorld: Vec3, headWorld: Vec3): Pose {
  val p = posWorld.toSdk()
  val h = headWorld.toSdk()
  return Pose(p, Quaternion.lookRotationAroundY(Vector3(p.x - h.x, 0f, p.z - h.z)))
}

/**
 * [GhostRenderer] in the Meta Spatial SDK scene. Every part gets its own mesh built from the parsed GLB ([Guide]),
 * baked at its final place in the ASSEMBLY frame, in two roles (placed, ghost) plus a "mover" copy for the insertion
 * loop. Guidance meshes are procedural ([Shapes]). All share a handful of [GhostMaterial] instances, so state changes
 * and breathing are a few attribute writes per frame.
 *
 * Entity tree: `root` (WORLD ← TABLE) → `posed` (TABLE ← ASSEMBLY: the step pose, animated during a turn) → parts and
 * guides. Label pill, leader line and anchor dot live in WORLD (billboarded, angular size kept).
 *
 * @param registerMesh registers a mesh creator for a `mesh://…` URI (the activity forwards to the SDK's MeshManager).
 * @param viewer the head pose in WORLD (right-handed: convert `scene.getViewerPose()` with [toPose3]), for
 *   billboarding and dynamic scale.
 *
 * Frames: every pose and point this class takes or returns is right-handed WORLD / ASSEMBLY (the tracker's
 * `vision.worldFromAssembly()`, the guide's glTF data); the conversion to Spatial axes happens only in [toSdk] and
 * [SpatialMesh.fromGltf].
 * @param labelPanelId a registered panel id for the label pill (mesh render mode), or null for no label.
 * @param onLabel what the label should show now (null = hide).
 */
class SpatialGhostRenderer(
    private val registerMesh: (String, () -> SceneMesh) -> Unit,
    private val viewer: () -> Pose3?,
    private val labelPanelId: Int?,
    private val lang: String,
    private val onLabel: (LabelInfo?) -> Unit,
) : GhostRenderer {
  private var guide: Guide? = null
  private var root: Entity? = null
  private var posed: Entity? = null
  private val placedE = HashMap<String, Entity>()
  private val ghostE = HashMap<String, Entity>()
  private val moverE = HashMap<String, Entity>()
  private val turnE = HashMap<String, Entity>()
  private val dots = ArrayList<Entity>()
  private val dash = ArrayList<Entity>()
  private var arrowHead: Entity? = null
  private var leader: Entity? = null
  private var anchorDot: Entity? = null
  private var labelE: Entity? = null

  private lateinit var ghostMat: GhostMat
  private lateinit var placedMat: GhostMat
  private lateinit var moverMat: GhostMat
  private lateinit var dotMat: GhostMat
  private lateinit var dashMat: GhostMat
  private lateinit var arrowMat: GhostMat
  private lateinit var leaderMat: GhostMat
  /** Hard edges of the current ghost (solid) and its dashed outline (wrong, lost), spec §4.1 / §4.8 / §4.12. */
  private lateinit var edgeMat: GhostMat
  private lateinit var outlineMat: GhostMat
  /** Hole highlight (spec §4.4): constant ring, two ripples, dim static rings on the step's other holes. */
  private lateinit var ringMat: GhostMat
  private lateinit var rippleMat: GhostMat
  private lateinit var staticRingMat: GhostMat
  private val edgeE = HashMap<String, Entity>()
  private val outlineE = HashMap<String, Entity>()
  private var ringE: Entity? = null
  private val rippleE = ArrayList<Entity>()
  private val staticRingE = ArrayList<Entity>()
  private var hole: Hole? = null
  /** "2 of 4" of the current fastener and the holes still to do (dim rings). */
  private var repeat: Repeat? = null
  /** The current `turn` action's poses from the walkthrough clip (null: interpolate the two step poses). */
  private var turnTrack: TurnTrack? = null
  /** True until the current action's hole has been logged in WORLD (`ghost_hole`, once a pose is known). */
  private var holeWorldPending = false
  private var materialLogged = false
  private var jobKey = "job"
  private var makeEntity: ((String, MeshData, GhostMat, Entity?) -> Entity)? = null

  private var step = 0
  private var action = 0
  private var cur: GuideAction? = null
  private var state = GhostState.NEXT
  private var clock = 0f
  private var stateSince = 0f
  private var actionSince = 0f
  private var path: List<Vec3> = emptyList()

  private var stepPose = Pose3.IDENTITY
  private var prevPose = Pose3.IDENTITY
  private var posedNow = Pose3.IDENTITY
  private var worldFromTable: Pose3? = null
  private var lastMeasured: Pose3? = null
  override var lost = false
    private set
  override var reducedMotion = false

  // ACTION-COACH: 1 = bright; lower dims the target ghost and its marks (eased over ~0.15 s in [tick])
  override var emphasis = 1f
  private var emphasisNow = 1f
  private var lostSince = 0f
  private var labelScale = 1f

  private val visible = HashMap<Entity, Boolean>()

  // ---------------------------------------------------------------- load

  override fun load(assembly: Assembly) {
    clear()
    val g = assembly.guide
    if (g == null) {
      Agq.log.error("ghost", "assembly has no guide data (GLB not parsed)")
      return
    }
    guide = g
    val t0 = System.nanoTime()
    ghostMat = GhostMat.create()
    placedMat = GhostMat.create(GhostMaterial.Tint.WHITE, body = 0.035f, rim = 0.22f, rimPower = 3f)
    moverMat = GhostMat.create(body = 0.10f, rim = 0.45f)
    // Guidance marks (`mark = true`) are drawn in the token colour itself, not at the ghost body's 55 % of it.
    dotMat = GhostMat.create(body = 0.55f, rim = 0.3f, rimPower = 1.5f, mark = true)
    dashMat = GhostMat.create(body = 0.95f, rim = 0.2f, rimPower = 1.5f, mark = true)
    arrowMat = GhostMat.create(body = 0.9f, rim = 0.1f, rimPower = 1.5f, mark = true)
    leaderMat = GhostMat.create(GhostMaterial.Tint.WHITE, body = 0.7f, rim = 0f, mark = true)
    edgeMat = GhostMat.create(GhostMaterial.Tint.EDGE, body = 0.85f, rim = 0f, mark = true)
    outlineMat = GhostMat.create(GhostMaterial.Tint.ERROR, body = 1f, rim = 0f, mark = true)
    ringMat = GhostMat.create(body = 0.9f, rim = 0f, mark = true)
    rippleMat = GhostMat.create(body = 0.6f, rim = 0f, mark = true)
    staticRingMat = GhostMat.create(body = 0.35f, rim = 0f, mark = true)

    val job = assembly.jobId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val r = Entity.create(listOf(Transform(Pose())))
    val p = Entity.create(listOf(Transform(Pose()), TransformParent(r)))
    root = r
    posed = p

    jobKey = job
    fun meshEntity(uri: String, data: MeshData, mat: GhostMat, parent: Entity?): Entity {
      registerMesh(uri) { toSceneMesh(data, mat.material) }
      val comps = mutableListOf(Mesh(Uri.parse(uri)), Transform(Pose()), Visible(false))
      if (parent != null) comps += TransformParent(parent)
      return Entity.create(comps).also { visible[it] = false }
    }
    makeEntity = ::meshEntity

    for ((node, geo) in g.parts) {
      val data = MeshData(geo.positions, geo.normals, geo.indices)
      placedE[node] = meshEntity("mesh://agj/$job/placed/$node", data, placedMat, p)
      ghostE[node] = meshEntity("mesh://agj/$job/ghost/$node", data, ghostMat, p)
      moverE[node] = meshEntity("mesh://agj/$job/mover/$node", data, moverMat, p)
    }
    // A turning arrow only for hardware that is turned (screws, cam bolts, cam locks); dowels and pins go straight in.
    for (s in g.steps) for (a in s.actions) {
      val node = a.node ?: continue
      val arrow = Guidance.turnArrowOf(a) ?: continue
      turnE[node] = meshEntity("mesh://agj/$job/turn/$node", Shapes.turnArrow(arrow.radius, 0.00175f), arrowMat, p)
    }
    val dot = Shapes.sphere(0.002f)
    repeat(MAX_DOTS) { i -> dots += meshEntity("mesh://agj/$job/dot/$i", dot, dotMat, p) }
    val big = Shapes.sphere(0.0025f)
    repeat(DASH) { i -> dash += meshEntity("mesh://agj/$job/dash/$i", big, dashMat, p) }
    arrowHead = meshEntity("mesh://agj/$job/arrowhead", Shapes.cone(0.005f, 0.014f), arrowMat, p)
    leader = meshEntity("mesh://agj/$job/leader", Shapes.cylinder(0.0006f, 1f), leaderMat, null)
    anchorDot = meshEntity("mesh://agj/$job/anchor", Shapes.sphere(0.0015f), leaderMat, null)
    // Unit rings, scaled to the hole radius: stroke 0.24 × r, i.e. the spec's 1.2 mm at the smallest ring (5 mm).
    val unitRing = Shapes.ring(1f, Guidance.RING_STROKE)
    ringE = meshEntity("mesh://agj/$job/ring", unitRing, ringMat, p)
    repeat(2) { i -> rippleE += meshEntity("mesh://agj/$job/ripple/$i", unitRing, rippleMat, p) }
    // One dim ring for every hole that can still be open at once (knagglig: 7 of the 8 slat screws).
    val staticRings = Guidance.maxRemaining(g).coerceIn(1, MAX_STATIC_RINGS)
    repeat(staticRings) { i -> staticRingE += meshEntity("mesh://agj/$job/sring/$i", unitRing, staticRingMat, p) }
    labelE =
        labelPanelId?.let { id ->
          Entity.createPanelEntity(id, Transform(Pose(Vector3(0f, -10f, 0f)))).also {
            it.setComponent(Visible(false))
            visible[it] = false
          }
        }
    Agq.log.event(
        "ghost_load",
        mapOf(
            "parts" to g.parts.size,
            "turn_arrows" to turnE.size,
            "static_rings" to staticRingE.size,
            "turn_tracks" to g.steps.count { it.turnTrack != null },
            "material" to ghostMat.kind.wire,
            "ms" to (System.nanoTime() - t0) / 1_000_000,
        ),
    )
  }

  private fun toSceneMesh(d: MeshData, mat: SceneMaterial): SceneMesh {
    val n = d.vertexCount
    // One material → materialRanges [0, indexCount] (same under either [start,count] or [start,end] reading).
    // glTF / ASSEMBLY (right-handed) → Spatial axes: mirrored z + reversed winding (SpatialMesh, unit-tested).
    val m = SpatialMesh.fromGltf(d)
    return SceneMesh.meshWithMaterials(
        m.positions,
        m.normals,
        FloatArray(n * 2),
        IntArray(n) { -1 },
        m.indices,
        intArrayOf(0, m.indices.size),
        arrayOf(mat),
        false,
    )
  }

  // ---------------------------------------------------------------- step / state

  override fun setStep(step: Int, action: Int) {
    val g = guide ?: return
    val s = g.step(step) ?: return
    val a = s.actions.getOrNull(action)
    val prevStep = g.steps.lastOrNull { it.n < step }
    this.step = step
    this.action = action
    cur = a
    prevPose = prevStep?.tableFromAssembly ?: s.tableFromAssembly
    stepPose = s.tableFromAssembly
    val turning = a?.kind == ActionKind.TURN
    // The turn as the guide's own animation does it (same way round); only when it joins the two step poses.
    turnTrack = s.turnTrack?.takeIf { turning && samePose(it.start, prevPose) && samePose(it.end, stepPose) }
    if (!turning) posedNow = stepPose

    for ((node, e) in placedE) {
      val st = g.statusOf(node, step, action)
      show(e, st == PartStatus.PLACED && !turning)
      // A turn shows the whole built assembly as one azure outline that turns (spec §4.10).
      show(ghostE[node]!!, st == PartStatus.CURRENT || (turning && st == PartStatus.PLACED))
      show(moverE[node]!!, false)
    }
    for ((node, e) in turnE) show(e, node == a?.node)
    for (e in edgeE.values) show(e, false)
    for (e in outlineE.values) show(e, false)
    a?.node?.let { edgesFor(it) }
    placeHoles(s, a)

    // Insertion path (from `from_dir`, on the hole's axis for hardware) + turning arrow (clockwise seen from the head)
    path = emptyList()
    val arrow = if (a != null && !turning) Guidance.turnArrowOf(a) else null
    if (a != null && !turning) {
      val up = stepPose.q.conjugate().rotate(Vec3(0f, 1f, 0f))
      path = Guidance.insertionPath(a, up, MAX_DOTS)
      if (arrow != null) turnE[a.node]?.setComponent(Transform(Guidance.turnArrowPose(arrow).toSdk()))
    }
    dots.forEachIndexed { i, e ->
      val pt = path.getOrNull(i)
      if (pt != null) e.setComponent(Transform(Pose(pt.toSdk())))
      show(e, pt != null)
    }
    for (e in dash) show(e, path.size > DASH)
    arrowHead?.let { e ->
      if (path.size >= 2) {
        val end = path.last()
        val tangent = end - path[path.size - 2]
        e.setComponent(Transform(Pose3(end, Shapes.fromTo(Vec3(0f, 1f, 0f), tangent)).toSdk()))
      }
      show(e, path.size >= 2)
    }
    actionSince = clock
    setState(GhostState.NEXT)
    if (!materialLogged) {
      materialLogged = true
      Agq.log.event(
          "ghost_material",
          mapOf(
              "kind" to ghostMat.kind.wire,
              "shader" to if (ghostMat.kind == GhostMaterialKind.CUSTOM) GhostMaterial.SHADER else "data/shaders/unlit/unlit",
              "alpha_mode" to "translucent",
              "attributes" to if (ghostMat.kind == GhostMaterialKind.CUSTOM) GhostMaterial.ATTRIBUTES else emptyList(),
              "preferred" to GhostMaterial.preferred.wire,
              "step" to step,
              "action" to action,
          ),
      )
    }
    // Everything needed to check the guidance from a log: where the hole is, where the hardware comes from, which
    // of the step's identical fasteners this is, and which way it turns.
    val h = hole
    val wfa = worldFromTable?.let { it * posedNow }
    val fromDir = a?.fromDir?.takeIf { !turning }
    val fields =
        mutableMapOf<String, Any?>(
            "step" to step,
            "action" to action,
            "kind" to (a?.kind?.name?.lowercase()),
            "node" to a?.node,
            "code" to a?.code,
            "hw_kind" to a?.hardwareKind,
            "path_dots" to path.size,
            "hole_m" to (if (h != null && wfa != null) vec(wfa.transform(h.center)) else null),
            "hole_asm_m" to h?.let { vec(it.center) },
            "hole_src" to h?.source,
            "ring_r_mm" to h?.let { round1(it.radius * 1000f) },
            "from_dir" to (if (fromDir != null && wfa != null) vec(wfa.q.rotate(fromDir)) else null),
            "from_dir_asm" to fromDir?.let { vec(it) },
            "path_from_asm_m" to path.firstOrNull()?.let { vec(it) },
            "path_to_asm_m" to path.lastOrNull()?.let { vec(it) },
            "repeat_index" to repeat?.index,
            "repeat_of" to repeat?.of,
            "remaining_rings" to repeat?.remaining?.size,
            "label_count" to a?.label(lang)?.count,
            "turn_sense" to (if (a != null && !turning && Guidance.hasHole(a)) Guidance.turnSense(a).wire else null),
            "arrow_axis_asm" to arrow?.let { vec(it.into) },
            "axis_dev_deg" to (if (h != null && a != null) Guidance.hardwareAxis(a)?.let { round1(Guidance.angleDeg(it, h.normal * -1f).toFloat()) } else null),
            "pose_known" to (wfa != null),
        )
    if (turning) {
      fields["flip_deg"] = round1(turnTrack?.angleDeg ?: Math.toDegrees(Guidance.angleRad(prevPose.q, stepPose.q).toDouble()).toFloat())
      fields["flip_src"] = if (turnTrack != null) "clip" else "end_poses"
      fields["flip_from_q"] = quat(prevPose.q)
      fields["flip_to_q"] = quat(stepPose.q)
    }
    Agq.log.event("ghost_show", fields)
    holeWorldPending = h != null && wfa == null
  }

  /** Within 1° and 5 mm. */
  private fun samePose(a: Pose3, b: Pose3) = Guidance.angleRad(a.q, b.q) < 0.0175f && (a.t - b.t).length() < 0.005f

  private fun round1(v: Float) = Math.round(v * 10f) / 10f

  private fun vec(v: Vec3) = listOf(Math.round(v.x * 10000f) / 10000f, Math.round(v.y * 10000f) / 10000f, Math.round(v.z * 10000f) / 10000f)

  private fun quat(q: Quat) = listOf(q.x, q.y, q.z, q.w).map { Math.round(it * 10000f) / 10000f }

  override fun setState(state: GhostState) {
    this.state = state
    stateSince = clock
    val a = cur
    val moving = state == GhostState.NEXT || state == GhostState.UNSURE
    a?.node?.let { show(moverE[it] ?: return@let, moving && a.fromDir != null && !reducedMotion) }
    val guides = moving || state == GhostState.GOOD
    dots.forEachIndexed { i, e -> show(e, guides && i < path.size) }
    for (e in dash) show(e, guides && path.size > DASH && !reducedMotion)
    arrowHead?.let { show(it, guides && path.size >= 2) }
    a?.node?.let { n -> turnE[n]?.let { show(it, guides) } }
    updateEdges()
    pushLabel()
  }

  /** Lazily builds the solid edges and the dashed outline of [node] (feature edges, spec §4.1 / §4.8). */
  private fun edgesFor(node: String) {
    if (edgeE.containsKey(node)) return
    val geo = guide?.parts?.get(node) ?: return
    val mk = makeEntity ?: return
    val segs = Edges.featureEdges(geo.positions, geo.indices)
    if (segs.isEmpty()) return
    edgeE[node] = mk("mesh://agj/$jobKey/edges/$node", Edges.tubes(segs, 0.0015f), edgeMat, posed)
    outlineE[node] =
        mk("mesh://agj/$jobKey/outline/$node", Edges.tubes(Edges.dashed(segs, 0.008f, 0.005f), 0.002f), outlineMat, posed)
  }

  /** Solid edges in NEXT / GOOD / UNSURE / CORRECT; dashed outline when WRONG or lost (spec §4.8, §4.12). */
  private fun updateEdges() {
    val node = cur?.node ?: return
    val dashed = state == GhostState.WRONG || lost
    edgeE[node]?.let { show(it, !dashed) }
    outlineE[node]?.let { show(it, dashed) }
  }

  /**
   * The hole of the current action (bright ring + ripples) and the step's holes of the same code that are still to
   * do (dim rings); done ones get none (spec §4.4). Every ring sits on the receiving surface at the hardware's final
   * origin, 0.5 mm off it, facing `from_dir` ([Guidance.holeOf]).
   */
  private fun placeHoles(s: GuideStep, a: GuideAction?) {
    hole = a?.let { Guidance.holeOf(it) }
    repeat = a?.let { Guidance.repeatOf(s, it) }
    val h = hole
    ringE?.let { e ->
      if (h != null) {
        e.setComponent(Transform(ringPose(h).toSdk()))
        e.setComponent(Scale(Vector3(h.radius)))
      }
      show(e, h != null)
    }
    rippleE.forEach { show(it, h != null && !reducedMotion) }
    val later = repeat?.remaining?.mapNotNull { Guidance.holeOf(it) } ?: emptyList()
    staticRingE.forEachIndexed { i, e ->
      val o = later.getOrNull(i)
      if (o != null) {
        e.setComponent(Transform(ringPose(o).toSdk()))
        e.setComponent(Scale(Vector3(o.radius)))
      }
      show(e, o != null)
    }
  }

  /** The flat ring mesh (normal +Y) on the hole's lip. */
  private fun ringPose(h: Hole) = Pose3(h.ringCenter, Shapes.fromTo(Vec3(0f, 1f, 0f), h.normal))

  /** Two ripples, a new pair every 2.0 s: each grows 1.0 → 2.2 × r and fades 0.6 → 0 in 1.2 s (ease-out). */
  private fun updateRings(tAction: Float, lostK: Float) {
    val h = hole ?: return
    ringMat.set(GhostMaterial.Tint.ACCENT, 0.9f, 0f, opacity = lostK)
    staticRingMat.set(GhostMaterial.Tint.ACCENT, 0.35f, 0f, opacity = lostK)
    if (reducedMotion) return
    val c = tAction % 2.0f
    rippleE.forEachIndexed { i, e ->
      val k = (c - i * 0.3f) / 1.2f
      val on = k in 0f..1f
      if (on) {
        e.setComponent(Transform(ringPose(h).toSdk()))
        e.setComponent(Scale(Vector3(h.radius * (1f + 1.2f * easeOutCubic(k)))))
      }
      show(e, on)
    }
    rippleMat.set(GhostMaterial.Tint.ACCENT, 0.6f * (1f - easeOutCubic((c / 1.5f).coerceIn(0f, 1f))), 0f, opacity = lostK)
  }

  private fun pushLabel() {
    val l = cur?.label(lang)
    onLabel(l?.let { LabelInfo(it, state, lost) })
  }

  // ---------------------------------------------------------------- poses

  override fun setAssemblyPose(worldFromAssembly: Pose3?) {
    if (worldFromAssembly == null) {
      markLost(true)
      return
    }
    markLost(false)
    lastMeasured = worldFromAssembly
    // The real drawer is in the pose it holds now: before a turn is done, the previous step's pose.
    val held = if (cur?.kind == ActionKind.TURN) prevPose else stepPose
    worldFromTable = worldFromAssembly * held.inverse()
  }

  override fun setTablePose(worldFromTable: Pose3?) {
    if (worldFromTable == null) {
      markLost(true)
      return
    }
    markLost(false)
    this.worldFromTable = worldFromTable
  }

  private fun markLost(l: Boolean) {
    if (l && !lost) lostSince = clock
    if (l != lost) {
      lost = l
      updateEdges()
      pushLabel()
    }
  }

  // ---------------------------------------------------------------- per frame

  override fun tick(dtS: Float) {
    val g = guide ?: return
    val r = root ?: return
    clock += dtS
    val wft = worldFromTable
    r.setComponent(Transform((wft ?: Pose3(Vec3(0f, -100f, 0f))).toSdk()))

    // Turn preview: previous pose → new pose in 1.6 s, hold 0.8 s, three times, then stay.
    val a = cur
    val tAction = clock - actionSince
    if (a?.kind == ActionKind.TURN && reducedMotion) {
      posedNow = stepPose
    } else if (a?.kind == ActionKind.TURN) {
      val cycle = 2.4f
      val k =
          if (tAction >= cycle * 3) 1f else ((tAction % cycle) / 1.6f).coerceIn(0f, 1f).let { easeInOutCubic(it) }
      // Along the guide's own turn (same way round as its animation); without a clip, between the two step poses.
      posedNow = turnTrack?.at(k) ?: Pose3(prevPose.t.lerp(stepPose.t, k), prevPose.q.nlerp(stepPose.q, k))
    }
    posed?.setComponent(Transform(posedNow.toSdk()))
    // The hole in WORLD, once, when `ghost_show` could not give it (no pose yet at that moment).
    if (holeWorldPending && wft != null) {
      holeWorldPending = false
      hole?.let { h ->
        val wfa = wft * posedNow
        Agq.log.event(
            "ghost_hole",
            mapOf("step" to step, "action" to action, "hole_m" to vec(wfa.transform(h.center)), "from_dir" to vec(wfa.q.rotate(h.normal))),
        )
      }
    }

    // Ghost material by state (spec §4.1, 4.7–4.9, 4.12).
    val t = clock - stateSince
    val lostK = if (lost) (1f - 0.7f * ((clock - lostSince) / 0.3f).coerceIn(0f, 1f)) else 1f
    // ACTION-COACH: while the part is still being fetched the target ghost and its marks are dimmed ([emphasis]);
    // wrong and correct always show in full, the placed parts keep [lostK]
    emphasisNow += (emphasis.coerceIn(0f, 1f) - emphasisNow) * (1f - kotlin.math.exp(-dtS / 0.15f))
    val markK = lostK * (if (state == GhostState.WRONG || state == GhostState.CORRECT) 1f else emphasisNow)
    when (state) {
      GhostState.NEXT -> {
        val breathe = if (reducedMotion) 0f else 0.05f * sin(2f * PI.toFloat() * 0.42f * t)
        ghostMat.set(GhostMaterial.Tint.ACCENT, 0.14f + breathe, 0.65f, opacity = markK)
      }
      GhostState.GOOD -> ghostMat.set(GhostMaterial.Tint.SUCCESS, 0.16f, 0.65f, opacity = markK)
      GhostState.CORRECT -> {
        val k = 1f - (t / 0.6f).coerceIn(0f, 1f)
        val e = 1f - (1f - k).pow(3)
        ghostMat.set(GhostMaterial.Tint.SUCCESS, 0.14f * k, 1.0f * e, opacity = markK)
      }
      GhostState.WRONG -> {
        val pulse = if (t < 3f / 0.83f) 0.8f + 0.2f * sin(2f * PI.toFloat() * 0.83f * t) else 1f
        ghostMat.set(GhostMaterial.Tint.ERROR, 0.06f, 0.65f, opacity = pulse * markK)
      }
      GhostState.UNSURE -> ghostMat.set(GhostMaterial.Tint.UNSURE, 0.14f, 0.65f, opacity = markK)
    }
    placedMat.set(GhostMaterial.Tint.WHITE, 0.035f, 0.22f, 3f, opacity = lostK)

    // Insertion loop: the mover slides in along from_dir in 1.5 s and fades at both ends.
    val geo = a?.geometry
    val dir = a?.fromDir
    if (a?.node != null && geo != null && dir != null && moverE[a.node]?.let { visible[it] } == true && !reducedMotion) {
      val k = (tAction % 1.5f) / 1.5f
      val travel = easeOutCubic((k / 0.8f).coerceIn(0f, 1f))
      val d = if (a.kind.twists) 0.09f else 0.12f
      moverE[a.node]!!.setComponent(Transform(Pose((dir * (d * (1f - travel))).toSdk())))
      val fade = (k / 0.12f).coerceIn(0f, 1f) * (1f - ((k - 0.85f) / 0.15f).coerceIn(0f, 1f))
      moverMat.set(GhostMaterial.Tint.ACCENT, 0.10f, 0.45f, opacity = fade * markK)
    }

    // Travelling dash: 4 bright dots run start → end in 1.6 s, 0.6 s pause (0.45 Hz).
    if (path.size > DASH && dash.firstOrNull()?.let { visible[it] } == true && !reducedMotion) {
      val c = tAction % 2.2f
      val k = easeInOutCubic((c / 1.6f).coerceIn(0f, 1f))
      val head = (k * (path.size - 1)).toInt()
      dash.forEachIndexed { i, e ->
        val idx = (head - i).coerceIn(0, path.size - 1)
        e.setComponent(Transform(Pose(path[idx].toSdk())))
      }
      dashMat.set(GhostMaterial.Tint.ACCENT, if (c < 1.6f) 0.95f else 0f, 0.2f, 1.5f, markK)
    }
    dotMat.set(GhostMaterial.Tint.ACCENT, 0.55f, 0.3f, 1.5f, markK)
    // Turning arrow: static arc, brightness sweep at 0.8 Hz.
    arrowMat.set(GhostMaterial.Tint.ACCENT, if (reducedMotion) 0.85f else 0.75f + 0.2f * sin(2f * PI.toFloat() * 0.8f * tAction), 0.1f, 1.5f, markK)

    // Edges: constant (no breathing); the dashed outline pulses 0.6 ↔ 1.0 at 0.83 Hz for 3 cycles when wrong (§4.8).
    val edgeTint =
        when (state) {
          GhostState.GOOD, GhostState.CORRECT -> GhostMaterial.Tint.SUCCESS
          GhostState.UNSURE -> GhostMaterial.Tint.UNSURE
          else -> GhostMaterial.Tint.EDGE
        }
    val edgeK = if (state == GhostState.CORRECT) 1f - (t / 0.6f).coerceIn(0f, 1f) else 1f
    edgeMat.set(edgeTint, 0.85f * edgeK, 0f, opacity = markK)
    if (state == GhostState.WRONG) {
      val pulse = if (t < 3f / 0.83f && !reducedMotion) 0.8f + 0.2f * sin(2f * PI.toFloat() * 0.83f * t) else 1f
      outlineMat.set(GhostMaterial.Tint.ERROR, 1f, 0f, opacity = pulse)
    } else {
      // lost: azure dashed edges at 30 %, like the ghost (§4.12)
      outlineMat.set(GhostMaterial.Tint.EDGE, 0.85f, 0f, opacity = markK)
    }
    updateRings(tAction, markK)

    placeLabel(wft)
  }

  /**
   * Label pill 9 cm above the ghost's top, dynamic scale (spec §4.5, §2.2). UI-FLOOR: it faces the eyes and reads
   * upright in the view also from straight above ([com.agj.android.ui.Placement.billboard]); turned about the
   * vertical only, it read sideways and foreshortened when the wearer looked down at parts on the floor.
   */
  private fun placeLabel(wft: Pose3?) {
    val anchor = currentAnchorWorld()
    // ACTION-COACH: while dimmed the name is on the real part to take, not at the target
    val show = anchor != null && wft != null && cur?.label(lang) != null && state != GhostState.CORRECT &&
        (emphasis >= 0.99f || state == GhostState.WRONG)
    labelE?.let { show(it, show) }
    leader?.let { show(it, show) }
    anchorDot?.let { show(it, show) }
    if (!show) return
    val head = viewer()
    val dist = head?.let { (it.t - anchor!!).length() } ?: 0.8f
    labelScale = (dist / 0.8f).coerceIn(0.75f, 1.5f)
    val lift = 0.09f * labelScale
    val tip = anchor!! + Vec3(0f, lift, 0f) // the leader's upper end: the pill's bottom edge sits on it
    labelE?.let { e ->
      val pose =
          head?.let { h ->
            val b = com.agj.android.ui.Placement.billboard(tip, h)
            Pose3(tip + b.q.rotate(Vec3(0f, PILL_HALF_H * labelScale, 0f)), b.q).toSdk()
          } ?: Pose((tip + Vec3(0f, PILL_HALF_H * labelScale, 0f)).toSdk())
      e.setComponent(Transform(pose))
      e.setComponent(Scale(Vector3(labelScale)))
    }
    leader?.let { e ->
      e.setComponent(Transform(Pose(anchor!!.toSdk())))
      e.setComponent(Scale(Vector3(1f, lift, 1f)))
    }
    anchorDot?.setComponent(Transform(Pose(anchor!!.toSdk())))
  }

  private fun currentPartsBox(): Pair<Vec3, Vec3>? {
    val g = guide ?: return null
    val a = cur
    val geos =
        if (a?.geometry != null) listOf(a.geometry)
        else g.parts.filterKeys { g.statusOf(it, step, action) == PartStatus.PLACED }.values.toList()
    if (geos.isEmpty()) return null
    return geos.fold(geos[0].min to geos[0].max) { (lo, hi), p ->
      Vec3(minOf(lo.x, p.min.x), minOf(lo.y, p.min.y), minOf(lo.z, p.min.z)) to
          Vec3(maxOf(hi.x, p.max.x), maxOf(hi.y, p.max.y), maxOf(hi.z, p.max.z))
    }
  }

  /** The current part box's 8 corners in WORLD. */
  private fun currentCornersWorld(): List<Vec3>? {
    val wft = worldFromTable ?: return null
    val (lo, hi) = currentPartsBox() ?: return null
    val worldFromAssembly = wft * posedNow
    return listOf(lo.x, hi.x).flatMap { x -> listOf(lo.y, hi.y).flatMap { y -> listOf(lo.z, hi.z).map { z -> Vec3(x, y, z) } } }
        .map { worldFromAssembly.transform(it) }
  }

  /** Top centre (world up) of the current part's box, in WORLD. */
  override fun currentAnchorWorld(): Vec3? {
    val corners = currentCornersWorld() ?: return null
    val c = corners.reduce { acc, v -> acc + v } * (1f / corners.size)
    return Vec3(c.x, corners.maxOf { it.y }, c.z)
  }

  override fun currentTargetBoxWorld(): Pair<Vec3, Vec3>? {
    if (cur?.geometry == null) return null
    val c = currentCornersWorld() ?: return null
    return Vec3(c.minOf { it.x }, c.minOf { it.y }, c.minOf { it.z }) to Vec3(c.maxOf { it.x }, c.maxOf { it.y }, c.maxOf { it.z })
  }

  override fun assemblyCenterWorld(): Vec3? {
    val wft = worldFromTable ?: return null
    val g = guide ?: return null
    val placed = g.parts.filterKeys { g.statusOf(it, step, action) != PartStatus.LATER }.values
    if (placed.isEmpty()) return wft.t
    val c = placed.map { it.center }.reduce { a, b -> a + b } * (1f / placed.size)
    return (wft * posedNow).transform(c)
  }

  private fun show(e: Entity, on: Boolean) {
    if (visible[e] == on) return
    visible[e] = on
    e.setComponent(Visible(on))
  }

  override fun clear() {
    val all = placedE.values + ghostE.values + moverE.values + turnE.values + edgeE.values + outlineE.values + dots +
        dash + rippleE + staticRingE + listOfNotNull(arrowHead, leader, anchorDot, labelE, ringE, posed, root)
    for (e in all) runCatching { e.destroy() }
    placedE.clear(); ghostE.clear(); moverE.clear(); turnE.clear(); dots.clear(); dash.clear(); visible.clear()
    edgeE.clear(); outlineE.clear(); rippleE.clear(); staticRingE.clear(); ringE = null; hole = null; makeEntity = null
    arrowHead = null; leader = null; anchorDot = null; labelE = null; posed = null; root = null
    guide = null
    cur = null
    onLabel(null)
  }

  companion object {
    private const val MAX_DOTS = 48
    private const val DASH = 4
    /** Upper bound on the dim rings (the real number comes from the guide: [Guidance.maxRemaining]). */
    private const val MAX_STATIC_RINGS = 24

    /** Half the label panel's height (m), so the pill's bottom edge sits on the leader line. */
    const val PILL_HALF_H = 0.02f

    fun easeOutCubic(x: Float) = 1f - (1f - x).pow(3)

    fun easeInOutCubic(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
  }
}
