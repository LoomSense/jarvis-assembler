package com.agj.android.guide

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.core.blenderToGltf
import kotlin.math.PI
import kotlin.math.abs

/*
 * The guide as the headset uses it: manifest + GLB → steps → actions, each action with its part's geometry at its final
 * place (ASSEMBLY frame, glTF Y-up, metres), its insertion direction, its label, and each step's pose of the whole
 * assembly on the table. Pure Kotlin (no Spatial SDK types): JVM-tested against jobs/malm-drawer.
 *
 * Frames:
 *  - ASSEMBLY: the GLB node `assembly` (manifest `rootNode`). Parts' final transforms are read relative to it.
 *  - TABLE: the GLB scene frame (the parent of `assembly`), Y-up, the built assembly standing on the table at y = 0.
 *    `tableFromAssembly` of a step is the `assembly` node's transform at the end of that step in the `walkthrough`
 *    clip, i.e. the turned pose the guide shows for that step (manifest `assemblyRotationDeg`, already converted).
 */

/** Action kinds (manifest `type`). `note` actions carry text only and are not guided one by one. */
enum class ActionKind {
  PLACE,
  FASTEN,
  TURN,
  PRESS,
  TIGHTEN,
  NOTE;

  /** Screw-like: drawn with a turning arrow. */
  val twists: Boolean
    get() = this == FASTEN || this == TIGHTEN

  companion object {
    fun of(type: String) = entries.firstOrNull { it.name.equals(type, ignoreCase = true) } ?: NOTE
  }
}

/** One part's triangles baked into the ASSEMBLY frame at its final place. */
class PartGeometry(
    val node: String,
    /** xyz per vertex, ASSEMBLY frame (metres). */
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
    /** base colour from the GLB material (linear RGBA). */
    val color: FloatArray,
) {
  val min: Vec3
  val max: Vec3

  init {
    var x0 = Float.MAX_VALUE
    var y0 = Float.MAX_VALUE
    var z0 = Float.MAX_VALUE
    var x1 = -Float.MAX_VALUE
    var y1 = -Float.MAX_VALUE
    var z1 = -Float.MAX_VALUE
    for (i in positions.indices step 3) {
      x0 = minOf(x0, positions[i]); x1 = maxOf(x1, positions[i])
      y0 = minOf(y0, positions[i + 1]); y1 = maxOf(y1, positions[i + 1])
      z0 = minOf(z0, positions[i + 2]); z1 = maxOf(z1, positions[i + 2])
    }
    min = if (positions.isEmpty()) Vec3.ZERO else Vec3(x0, y0, z0)
    max = if (positions.isEmpty()) Vec3.ZERO else Vec3(x1, y1, z1)
  }

  val center: Vec3
    get() = (min + max) * 0.5f

  val size: Vec3
    get() = max - min

  val vertexCount: Int
    get() = positions.size / 3

  /** Extent of the part along unit [axis]. */
  fun extentAlong(axis: Vec3): Float = abs(axis.x) * size.x + abs(axis.y) * size.y + abs(axis.z) * size.z

  /** Diameter across [axis] (the largest extent perpendicular to it): screws' head size. */
  fun widthAcross(axis: Vec3): Float {
    val comps = listOf(size.x to abs(axis.x), size.y to abs(axis.y), size.z to abs(axis.z))
    return comps.filter { it.second < 0.7f }.maxOfOrNull { it.first } ?: maxOf(size.x, size.y, size.z)
  }
}

/** The label pill text for one language: "Cam bolt · 118331 · 2 of 2" (the pill draws the count as a chip). */
data class PartLabel(val name: String, val code: String?, val count: String?) {
  val line: String
    get() = listOfNotNull(name, code, count).joinToString(" · ")
}

data class GuideAction(
    /** Index into the manifest step's `actions` (what the perception socket and `step` log use). */
    val index: Int,
    val kind: ActionKind,
    val text: LocalizedText,
    val node: String?,
    val partId: String?,
    val code: String?,
    /** Unit vector from the target towards where the part comes from, ASSEMBLY frame (glTF). */
    val fromDir: Vec3?,
    /** Manifest `target_m` converted to the ASSEMBLY frame. */
    val target: Vec3?,
    /** Final transform of [node] relative to the assembly root (null for turn/note). */
    val finalPose: Pose3?,
    val geometry: PartGeometry?,
    val labels: Map<String, PartLabel>,
    /** 1-based position among the step's actions with the same name, and that count (for "2 of 4"). */
    val ordinal: Int,
    val sameCount: Int,
) {
  fun label(lang: String): PartLabel? = labels[if (lang == "el") "el" else "en"] ?: labels["en"]
}

data class GuideStep(
    val n: Int,
    val title: String,
    val page: Int?,
    val crop: String?,
    val hardware: List<HardwareQty>,
    /** TABLE ← ASSEMBLY for this step (the turned pose, standing on the table). */
    val tableFromAssembly: Pose3,
    /** Every action of the manifest step, notes included, in order. */
    val actions: List<GuideAction>,
) {
  /** Actions the user does one at a time (everything but `note`). */
  val guided: List<GuideAction>
    get() = actions.filter { it.kind != ActionKind.NOTE }

  /** First note (the guide's tip for the step), if any. */
  fun note(lang: String): String? = actions.firstOrNull { it.kind == ActionKind.NOTE }?.text?.get(lang)
}

class Guide(
    val steps: List<GuideStep>,
    /** Every part node's geometry at its final place, by node name (placed parts are drawn from here). */
    val parts: Map<String, PartGeometry>,
    /** node name → (step n, manifest action index) where it is added. */
    val addedAt: Map<String, Pair<Int, Int>>,
    /** Final transforms of every node under the root, ASSEMBLY frame (incl. `marker`). */
    val finalPoses: Map<String, Pose3>,
    val finalScales: Map<String, Vec3>,
) {
  fun step(n: Int): GuideStep? = steps.firstOrNull { it.n == n }

  /**
   * Largest distance (mm) between an action's manifest `target_m` (Blender Z-up → glTF) and where its node ends up
   * at the end of the walkthrough clip; null when no action has both. Logged in `job_loaded` as `max_err_mm`.
   */
  fun maxTargetErrorMm(): Double? =
      steps.flatMap { it.actions }
          .mapNotNull { a -> val t = a.target ?: return@mapNotNull null; val p = a.finalPose ?: return@mapNotNull null; (t - p.t).length() * 1000.0 }
          .maxOrNull()

  val stepNumbers: List<Int>
    get() = steps.map { it.n }

  /** Total guided actions (for the completion summary). */
  val guidedCount: Int
    get() = steps.sumOf { it.guided.size }

  /** The (step, action index) after [step]/[action], skipping notes; null at the end. */
  fun next(step: Int, action: Int): Pair<Int, Int>? {
    val flat = flatten()
    val i = flat.indexOfFirst { it.first == step && it.second == action }
    return if (i >= 0) flat.getOrNull(i + 1) else flat.firstOrNull { it.first > step || (it.first == step && it.second > action) }
  }

  fun previous(step: Int, action: Int): Pair<Int, Int>? {
    val flat = flatten()
    val i = flat.indexOfFirst { it.first == step && it.second == action }
    return if (i > 0) flat[i - 1] else if (i == 0) null else flat.lastOrNull { it.first < step || (it.first == step && it.second < action) }
  }

  /** First guided action of step [n] (or the step's first action if it has none). */
  fun first(n: Int): Pair<Int, Int>? {
    val s = step(n) ?: return null
    return n to (s.guided.firstOrNull()?.index ?: 0)
  }

  fun flatten(): List<Pair<Int, Int>> = steps.flatMap { s -> s.guided.map { s.n to it.index } }

  /** Status of a part node at (step, action): placed before, current, or later. */
  fun statusOf(node: String, step: Int, action: Int): PartStatus {
    val (s, a) = addedAt[node] ?: return PartStatus.PLACED // not added by any action: base / already there
    return when {
      s < step || (s == step && a < action) -> PartStatus.PLACED
      s == step && a == action -> PartStatus.CURRENT
      else -> PartStatus.LATER
    }
  }

  companion object {
    /** Build from the manifest and the parsed GLB. Throws when the root node is missing. */
    fun build(m: Manifest, glb: Glb): Guide {
      val root = glb.node(m.rootNode) ?: error("GLB has no \"${m.rootNode}\" node")
      val clipName = (m.walkthrough as? kotlinx.serialization.json.JsonObject)?.get("clip")
          ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "walkthrough"
      val clip = glb.animation(clipName)
      val end = clip?.duration ?: 0f
      val finalLocals = glb.localPosesAt(clip, end)

      val finalPoses = HashMap<String, Pose3>()
      val finalScales = HashMap<String, Vec3>()
      val parts = HashMap<String, PartGeometry>()
      for (i in glb.descendants(root.index)) {
        val node = glb.nodes[i]
        val rel = glb.relativeTo(root.index, i, finalLocals) ?: continue
        finalPoses[node.name] = rel.pose
        finalScales[node.name] = rel.s
        if (node.mesh != null && (node.name.startsWith("part_") || node.name.startsWith("hw_"))) {
          parts[node.name] = bake(glb, node, rel)
        }
      }

      val partNames = m.parts.associateBy { it.id }
      val hwNames = m.hardware.associateBy { it.code }
      fun nameOf(a: ManifestAction, lang: String): String {
        val code = a.code ?: a.partId
        val hw = code?.let { hwNames[it] }
        val raw =
            if (hw != null) hw.names.get(lang).ifEmpty { hw.short.ifEmpty { hw.name } }
            else partNames[a.partId]?.let { p -> p.names.get(lang).ifEmpty { p.short.ifEmpty { p.name } } }
        return cap(raw?.takeIf { it.isNotEmpty() } ?: a.partId ?: a.node ?: "")
      }

      val addedAt = HashMap<String, Pair<Int, Int>>()
      var lastPose = Pose3.IDENTITY
      val steps =
          m.steps.map { s ->
            val window = s.walkthrough
            val tableFromAssembly =
                if (clip != null && window != null) glb.localPosesAt(clip, window.end_s.toFloat())[root.index].pose
                else if (s.assemblyRotationDeg.any { it != 0.0 }) Pose3(Vec3.ZERO, blenderEulerXyzToGltf(s.assemblyRotationDeg))
                else lastPose
            lastPose = tableFromAssembly
            // "2 of 4": count actions with the same English name in this step.
            val names = s.actions.map { if (it.node != null) nameOf(it, "en") else null }
            val actions =
                s.actions.mapIndexed { i, a ->
                  val kind = ActionKind.of(a.type)
                  if (a.node != null) addedAt.putIfAbsent(a.node, s.n to i)
                  val same = names.withIndex().filter { it.value != null && it.value == names[i] }.map { it.index }
                  val ordinal = same.indexOf(i) + 1
                  val labels =
                      if (a.node == null) emptyMap()
                      else
                          listOf("en", "el").associateWith { lang ->
                            val count =
                                if (same.size > 1) (if (lang == "el") "$ordinal από ${same.size}" else "$ordinal of ${same.size}")
                                else null
                            PartLabel(nameOf(a, lang), a.code, count)
                          }
                  GuideAction(
                      index = i,
                      kind = kind,
                      text = a.text,
                      node = a.node,
                      partId = a.partId,
                      code = a.code,
                      fromDir = a.fromDirGltf?.let { v -> v.length().takeIf { it > 1e-6f }?.let { v * (1f / it) } },
                      target = a.targetGltf,
                      finalPose = a.node?.let { finalPoses[it] },
                      geometry = a.node?.let { parts[it] },
                      labels = labels,
                      ordinal = maxOf(ordinal, 1),
                      sameCount = maxOf(same.size, 1),
                  )
                }
            GuideStep(s.n, s.title, s.cite.page, s.crop, s.hardware, tableFromAssembly, actions)
          }
      return Guide(steps, parts, addedAt, finalPoses, finalScales)
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercaseChar() }

    private fun bake(glb: Glb, node: GlbNode, rel: Trs): PartGeometry {
      val prims = glb.mesh(node.mesh!!)
      var nv = 0
      var ni = 0
      for (p in prims) {
        nv += p.vertexCount
        ni += p.indices.size
      }
      val pos = FloatArray(nv * 3)
      val nrm = FloatArray(nv * 3)
      val idx = IntArray(ni)
      var vo = 0
      var io = 0
      for (p in prims) {
        for (v in 0 until p.vertexCount) {
          val w = rel.apply(Vec3(p.positions[v * 3], p.positions[v * 3 + 1], p.positions[v * 3 + 2]))
          pos[(vo + v) * 3] = w.x
          pos[(vo + v) * 3 + 1] = w.y
          pos[(vo + v) * 3 + 2] = w.z
          val n0 = p.normals?.let { Vec3(it[v * 3], it[v * 3 + 1], it[v * 3 + 2]) } ?: Vec3(0f, 1f, 0f)
          val n = rel.r.rotate(n0)
          nrm[(vo + v) * 3] = n.x
          nrm[(vo + v) * 3 + 1] = n.y
          nrm[(vo + v) * 3 + 2] = n.z
        }
        for (k in p.indices.indices) idx[io + k] = p.indices[k] + vo
        vo += p.vertexCount
        io += p.indices.size
      }
      val color = prims.firstOrNull()?.material?.let { glb.materials.getOrNull(it)?.baseColor } ?: floatArrayOf(1f, 1f, 1f, 1f)
      return PartGeometry(node.name, pos, nrm, idx, color)
    }

    /**
     * Blender XYZ Euler (degrees, Z-up) → glTF rotation (Y-up). Only used when the GLB has no walkthrough window for a
     * step. q_gltf = C · q_blender · C⁻¹ with C = −90° about X, which maps quaternion axes like points: (x, z, −y).
     */
    fun blenderEulerXyzToGltf(deg: List<Double>): Quat {
      val r = deg.map { (it * PI / 180.0).toFloat() }
      val qx = Quat.axisAngle(Vec3(1f, 0f, 0f), r.getOrElse(0) { 0f })
      val qy = Quat.axisAngle(Vec3(0f, 1f, 0f), r.getOrElse(1) { 0f })
      val qz = Quat.axisAngle(Vec3(0f, 0f, 1f), r.getOrElse(2) { 0f })
      val qb = (qz * qy * qx).normalized() // XYZ Euler: X first
      val axis = blenderToGltf(listOf(qb.x.toDouble(), qb.y.toDouble(), qb.z.toDouble()))
      return Quat(axis.x, axis.y, axis.z, qb.w).normalized()
    }
  }
}

enum class PartStatus {
  PLACED,
  CURRENT,
  LATER,
}
