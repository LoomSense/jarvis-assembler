package com.agj.android.guide

import android.net.Uri
import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
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

fun Pose3.toSdk() = Pose(Vector3(t.x, t.y, t.z), Quaternion(q.w, q.x, q.y, q.z))

fun Vec3.toSdk() = Vector3(x, y, z)

fun Pose.toPose3() = Pose3(Vec3(t.x, t.y, t.z), Quat(q.x, q.y, q.z, q.w))

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
 * @param viewer the head pose in WORLD, for billboarding and dynamic scale.
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

  private lateinit var ghostMat: SceneMaterial
  private lateinit var placedMat: SceneMaterial
  private lateinit var moverMat: SceneMaterial
  private lateinit var dotMat: SceneMaterial
  private lateinit var dashMat: SceneMaterial
  private lateinit var arrowMat: SceneMaterial
  private lateinit var leaderMat: SceneMaterial

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
    ghostMat = GhostMaterial.create()
    placedMat = GhostMaterial.create(GhostMaterial.Tint.WHITE, body = 0.035f, rim = 0.22f, rimPower = 3f)
    moverMat = GhostMaterial.create(body = 0.10f, rim = 0.45f)
    dotMat = GhostMaterial.create(body = 0.55f, rim = 0.3f, rimPower = 1.5f)
    dashMat = GhostMaterial.create(body = 0.95f, rim = 0.2f, rimPower = 1.5f)
    arrowMat = GhostMaterial.create(body = 0.9f, rim = 0.1f, rimPower = 1.5f)
    leaderMat = GhostMaterial.create(GhostMaterial.Tint.WHITE, body = 0.7f, rim = 0f)

    val job = assembly.jobId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val r = Entity.create(listOf(Transform(Pose())))
    val p = Entity.create(listOf(Transform(Pose()), TransformParent(r)))
    root = r
    posed = p

    fun meshEntity(uri: String, data: MeshData, mat: SceneMaterial, parent: Entity?): Entity {
      registerMesh(uri) { toSceneMesh(data, mat) }
      val comps = mutableListOf(Mesh(Uri.parse(uri)), Transform(Pose()), Visible(false))
      if (parent != null) comps += TransformParent(parent)
      return Entity.create(comps).also { visible[it] = false }
    }

    for ((node, geo) in g.parts) {
      val data = MeshData(geo.positions, geo.normals, geo.indices)
      placedE[node] = meshEntity("mesh://agj/$job/placed/$node", data, placedMat, p)
      ghostE[node] = meshEntity("mesh://agj/$job/ghost/$node", data, ghostMat, p)
      moverE[node] = meshEntity("mesh://agj/$job/mover/$node", data, moverMat, p)
    }
    for (s in g.steps) for (a in s.actions) {
      val geo = a.geometry ?: continue
      if (!a.kind.twists || a.node == null) continue
      val axis = a.fromDir ?: Vec3(0f, 1f, 0f)
      val radius = (geo.widthAcross(axis) / 2f + 0.010f).coerceIn(0.018f, 0.040f)
      turnE[a.node] = meshEntity("mesh://agj/$job/turn/${a.node}", Shapes.turnArrow(radius, 0.00175f), arrowMat, p)
    }
    val dot = Shapes.sphere(0.002f)
    repeat(MAX_DOTS) { i -> dots += meshEntity("mesh://agj/$job/dot/$i", dot, dotMat, p) }
    val big = Shapes.sphere(0.0025f)
    repeat(DASH) { i -> dash += meshEntity("mesh://agj/$job/dash/$i", big, dashMat, p) }
    arrowHead = meshEntity("mesh://agj/$job/arrowhead", Shapes.cone(0.005f, 0.014f), arrowMat, p)
    leader = meshEntity("mesh://agj/$job/leader", Shapes.cylinder(0.0006f, 1f), leaderMat, null)
    anchorDot = meshEntity("mesh://agj/$job/anchor", Shapes.sphere(0.0015f), leaderMat, null)
    labelE =
        labelPanelId?.let { id ->
          Entity.createPanelEntity(id, Transform(Pose(Vector3(0f, -10f, 0f)))).also {
            it.setComponent(Visible(false))
            visible[it] = false
          }
        }
    Agq.log.event(
        "ghost_load",
        mapOf("parts" to g.parts.size, "turn_arrows" to turnE.size, "ms" to (System.nanoTime() - t0) / 1_000_000),
    )
  }

  private fun toSceneMesh(d: MeshData, mat: SceneMaterial): SceneMesh {
    val n = d.vertexCount
    // One material → materialRanges [0, indexCount] (same under either [start,count] or [start,end] reading).
    return SceneMesh.meshWithMaterials(
        d.positions,
        d.normals,
        FloatArray(n * 2),
        IntArray(n) { -1 },
        d.indices,
        intArrayOf(0, d.indices.size),
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
    if (!turning) posedNow = stepPose

    for ((node, e) in placedE) {
      val st = g.statusOf(node, step, action)
      show(e, st == PartStatus.PLACED && !turning)
      // A turn shows the whole built assembly as one azure outline that turns (spec §4.10).
      show(ghostE[node]!!, st == PartStatus.CURRENT || (turning && st == PartStatus.PLACED))
      show(moverE[node]!!, false)
    }
    for ((node, e) in turnE) show(e, node == a?.node)

    // Insertion path + mover
    path = emptyList()
    val geo = a?.geometry
    val dir = a?.fromDir
    if (a != null && geo != null && dir != null && !turning) {
      val up = stepPose.q.conjugate().rotate(Vec3(0f, 1f, 0f))
      val small = maxOf(geo.size.x, geo.size.y, geo.size.z) < 0.06f
      val surface = geo.center + dir * (geo.extentAlong(dir) / 2f)
      path = Shapes.insertionPath(surface, dir, up, straight = a.kind.twists || small).take(MAX_DOTS)
      turnE[a.node]?.let { e ->
        val q = Shapes.fromTo(Vec3(0f, 1f, 0f), dir * -1f)
        e.setComponent(Transform(Pose3(surface + dir * 0.006f, q).toSdk()))
      }
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
    Agq.log.event(
        "ghost_show",
        mapOf("step" to step, "action" to action, "kind" to (a?.kind?.name?.lowercase()), "node" to a?.node, "path_dots" to path.size),
    )
  }

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
    pushLabel()
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
      posedNow = Pose3(prevPose.t.lerp(stepPose.t, k), prevPose.q.nlerp(stepPose.q, k))
    }
    posed?.setComponent(Transform(posedNow.toSdk()))

    // Ghost material by state (spec §4.1, 4.7–4.9, 4.12).
    val t = clock - stateSince
    val lostK = if (lost) (1f - 0.7f * ((clock - lostSince) / 0.3f).coerceIn(0f, 1f)) else 1f
    when (state) {
      GhostState.NEXT -> {
        val breathe = if (reducedMotion) 0f else 0.05f * sin(2f * PI.toFloat() * 0.42f * t)
        GhostMaterial.set(ghostMat, GhostMaterial.Tint.ACCENT, 0.14f + breathe, 0.65f, opacity = lostK)
      }
      GhostState.GOOD -> GhostMaterial.set(ghostMat, GhostMaterial.Tint.SUCCESS, 0.16f, 0.65f, opacity = lostK)
      GhostState.CORRECT -> {
        val k = 1f - (t / 0.6f).coerceIn(0f, 1f)
        val e = 1f - (1f - k).pow(3)
        GhostMaterial.set(ghostMat, GhostMaterial.Tint.SUCCESS, 0.14f * k, 1.0f * e, opacity = lostK)
      }
      GhostState.WRONG -> {
        val pulse = if (t < 3f / 0.83f) 0.8f + 0.2f * sin(2f * PI.toFloat() * 0.83f * t) else 1f
        GhostMaterial.set(ghostMat, GhostMaterial.Tint.ERROR, 0.06f, 0.65f, opacity = pulse * lostK)
      }
      GhostState.UNSURE -> GhostMaterial.set(ghostMat, GhostMaterial.Tint.UNSURE, 0.14f, 0.65f, opacity = lostK)
    }
    GhostMaterial.set(placedMat, GhostMaterial.Tint.WHITE, 0.035f, 0.22f, 3f, opacity = lostK)

    // Insertion loop: the mover slides in along from_dir in 1.5 s and fades at both ends.
    val geo = a?.geometry
    val dir = a?.fromDir
    if (a?.node != null && geo != null && dir != null && moverE[a.node]?.let { visible[it] } == true && !reducedMotion) {
      val k = (tAction % 1.5f) / 1.5f
      val travel = easeOutCubic((k / 0.8f).coerceIn(0f, 1f))
      val d = if (a.kind.twists) 0.09f else 0.12f
      moverE[a.node]!!.setComponent(Transform(Pose((dir * (d * (1f - travel))).toSdk())))
      val fade = (k / 0.12f).coerceIn(0f, 1f) * (1f - ((k - 0.85f) / 0.15f).coerceIn(0f, 1f))
      GhostMaterial.set(moverMat, GhostMaterial.Tint.ACCENT, 0.10f, 0.45f, opacity = fade * lostK)
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
      GhostMaterial.set(dashMat, GhostMaterial.Tint.ACCENT, if (c < 1.6f) 0.95f else 0f, 0.2f, 1.5f, lostK)
    }
    GhostMaterial.set(dotMat, GhostMaterial.Tint.ACCENT, 0.55f, 0.3f, 1.5f, lostK)
    // Turning arrow: static arc, brightness sweep at 0.8 Hz.
    GhostMaterial.set(arrowMat, GhostMaterial.Tint.ACCENT, if (reducedMotion) 0.85f else 0.75f + 0.2f * sin(2f * PI.toFloat() * 0.8f * tAction), 0.1f, 1.5f, lostK)

    placeLabel(wft)
  }

  /** Label pill 9 cm above the ghost's top, billboarded about Y, dynamic scale (spec §4.5, §2.2). */
  private fun placeLabel(wft: Pose3?) {
    val anchor = currentAnchorWorld()
    val show = anchor != null && wft != null && cur?.label(lang) != null && state != GhostState.CORRECT
    labelE?.let { show(it, show) }
    leader?.let { show(it, show) }
    anchorDot?.let { show(it, show) }
    if (!show) return
    val head = viewer()
    val dist = head?.let { (it.t - anchor!!).length() } ?: 0.8f
    labelScale = (dist / 0.8f).coerceIn(0.75f, 1.5f)
    val lift = 0.09f * labelScale
    val pill = anchor!! + Vec3(0f, lift + PILL_HALF_H * labelScale, 0f)
    labelE?.let { e ->
      val q =
          head?.let { h ->
            val f = pill - h.t
            Quaternion.lookRotationAroundY(Vector3(f.x, 0f, f.z)) // UNVERIFIED: panel front faces −forward
          } ?: Quaternion(1f, 0f, 0f, 0f)
      e.setComponent(Transform(Pose(pill.toSdk(), q)))
      e.setComponent(Scale(Vector3(labelScale)))
    }
    leader?.let { e ->
      e.setComponent(Transform(Pose(anchor.toSdk())))
      e.setComponent(Scale(Vector3(1f, lift, 1f)))
    }
    anchorDot?.setComponent(Transform(Pose(anchor.toSdk())))
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

  /** Top centre (world up) of the current part's box, in WORLD. */
  override fun currentAnchorWorld(): Vec3? {
    val wft = worldFromTable ?: return null
    val (lo, hi) = currentPartsBox() ?: return null
    val worldFromAssembly = wft * posedNow
    val corners =
        listOf(lo.x, hi.x).flatMap { x -> listOf(lo.y, hi.y).flatMap { y -> listOf(lo.z, hi.z).map { z -> Vec3(x, y, z) } } }
            .map { worldFromAssembly.transform(it) }
    val c = corners.reduce { acc, v -> acc + v } * (1f / corners.size)
    return Vec3(c.x, corners.maxOf { it.y }, c.z)
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
    val all = placedE.values + ghostE.values + moverE.values + turnE.values + dots + dash +
        listOfNotNull(arrowHead, leader, anchorDot, labelE, posed, root)
    for (e in all) runCatching { e.destroy() }
    placedE.clear(); ghostE.clear(); moverE.clear(); turnE.clear(); dots.clear(); dash.clear(); visible.clear()
    arrowHead = null; leader = null; anchorDot = null; labelE = null; posed = null; root = null
    guide = null
    cur = null
    onLabel(null)
  }

  companion object {
    private const val MAX_DOTS = 48
    private const val DASH = 4
    /** Half the label panel's height (m), so the pill's bottom edge sits on the leader line. */
    const val PILL_HALF_H = 0.02f

    fun easeOutCubic(x: Float) = 1f - (1f - x).pow(3)

    fun easeInOutCubic(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
  }
}
