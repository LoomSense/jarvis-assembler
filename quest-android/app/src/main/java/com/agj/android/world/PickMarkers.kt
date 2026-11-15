package com.agj.android.world

import android.net.Uri
import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.guide.GhostMat
import com.agj.android.guide.GhostMaterial
import com.agj.android.guide.MeshData
import com.agj.android.guide.Shapes
import com.agj.android.guide.SpatialMesh
import com.agj.android.guide.toSdk
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import kotlin.math.PI
import kotlin.math.sin

/**
 * Where the "pick this part" cue (XR_NATIVE_SPEC §4.6) goes for one [PickTarget]. Pure, tested.
 *
 * - ring: flat on the surface at the part, radius = the part's footprint + 2 cm;
 * - chevron: 6 cm above the part, pointing down, bobbing ±1 cm at 0.5 Hz (still with reduced motion);
 * - [opacity]: 1, fading over the last 3 s before [maxAgeMs] (an old answer fades, never stays as if fresh).
 */
object PickCue {
  const val ABOVE_M = 0.06f
  const val BOB_M = 0.01f
  const val BOB_HZ = 0.5f
  const val MAX_AGE_MS = 15_000L
  const val FADE_MS = 3_000L

  fun ringPose(t: PickTarget): Pose3 = Pose3(t.worldPos + Vec3(0f, 0.002f, 0f))

  fun ringRadius(t: PickTarget): Float = t.radiusM + 0.02f

  fun chevronPose(t: PickTarget, timeS: Float, reducedMotion: Boolean = false): Pose3 {
    val bob = if (reducedMotion) 0f else BOB_M * sin(2 * PI.toFloat() * BOB_HZ * timeS)
    // the cone points +Y: turn it 180° about X so the tip points down at the part
    return Pose3(t.worldPos + Vec3(0f, ABOVE_M + bob, 0f), Quat.axisAngle(Vec3(1f, 0f, 0f), PI.toFloat()))
  }

  /**
   * Which target each marker slot draws: a slot keeps the target whose [PickTarget.key] it drew last frame
   * ([slotKeys], 0 = free), new targets take the free slots in order. Returns, per slot, the index into [keys] or
   * null. Targets without a key (0) are simply dealt out in order.
   */
  fun assign(keys: List<Int>, slotKeys: IntArray): Array<Int?> {
    val out = arrayOfNulls<Int>(slotKeys.size)
    val placed = BooleanArray(keys.size)
    for (s in slotKeys.indices) {
      val k = slotKeys[s]
      if (k == 0) continue
      val i = keys.indexOf(k)
      if (i >= 0 && !placed[i]) {
        out[s] = i
        placed[i] = true
      }
    }
    var s = 0
    for (i in keys.indices) {
      if (placed[i]) continue
      while (s < out.size && out[s] != null) s++
      if (s >= out.size) break
      out[s] = i
    }
    return out
  }

  fun opacity(t: PickTarget, maxAgeMs: Long = MAX_AGE_MS): Float =
      when {
        t.ageMs >= maxAgeMs -> 0f
        t.ageMs <= maxAgeMs - FADE_MS -> 1f
        else -> (maxAgeMs - t.ageMs).toFloat() / FADE_MS
      }
}

/**
 * The pick-up anchor in the scene: for each [PickTarget] (up to [MAX]) an azure ring on the table under the real
 * part and a chevron above it ([PickCue]). Self-contained Spatial SDK entities (own meshes and [GhostMat]s), WORLD
 * poses converted with the app's one `toSdk` rule. Call [update] once per frame on the render tick with
 * `world.pickTargets`; it creates its entities on the first call. Logs `pick_markers` when the shown set changes.
 *
 * @param registerMesh the same `meshManager.registerMeshCreator` hook the ghost renderer uses.
 */
class PickMarkers(private val registerMesh: (String, () -> SceneMesh) -> Unit) {
  var reducedMotion = false

  private class Marker(val ring: Entity, val chevron: Entity, val ringMat: GhostMat, val chevronMat: GhostMat) {
    var shown = false
  }

  private val markers = ArrayList<Marker>()
  private var timeS = 0f
  private var lastIds: List<String> = emptyList()
  private val slotKeys = IntArray(MAX)
  private var failed = false

  fun update(targets: List<PickTarget>, dtS: Float) {
    if (failed) return
    try {
      if (markers.isEmpty()) create()
      timeS += dtS
      // the action's own items first; a target keeps its marker while its track lives (no hopping between parts)
      // FLAT-PARTS: a target on a recognised rectangle has its own cue (the outline on the part, FlatPartOutline): no ring,
      // no chevron, and none for the server's boxes of that part while the rectangle stands in for them
      val live = targets.filter { it.method != FlatParts.METHOD && !it.shadowed && PickCue.opacity(it) > 0f }.sortedWith(compareBy({ !it.current }, { -it.confidence })).take(MAX)
      val slots = PickCue.assign(live.map { it.key }, slotKeys)
      for ((i, m) in markers.withIndex()) {
        val t = slots[i]?.let { live[it] }
        slotKeys[i] = t?.key ?: 0
        if (t == null) {
          if (m.shown) {
            m.ring.setComponent(Visible(false))
            m.chevron.setComponent(Visible(false))
            m.shown = false
          }
          continue
        }
        val a = PickCue.opacity(t)
        m.ringMat.set(GhostMaterial.Tint.ACCENT, 0.6f, 0f, opacity = a) // the stroke; the ring has no fill
        m.chevronMat.set(GhostMaterial.Tint.ACCENT, 0.9f, 0.1f, rimPower = 1.5f, opacity = a)
        m.ring.setComponent(Transform(PickCue.ringPose(t).toSdk()))
        m.ring.setComponent(Scale(Vector3(PickCue.ringRadius(t))))
        m.chevron.setComponent(Transform(PickCue.chevronPose(t, timeS, reducedMotion).toSdk()))
        if (!m.shown) {
          m.ring.setComponent(Visible(true))
          m.chevron.setComponent(Visible(true))
          m.shown = true
        }
      }
      val ids = live.map { if (it.instance == 0) it.id else "${it.id}#${it.instance}" }
      if (ids != lastIds) {
        lastIds = ids
        Agq.log.event("pick_markers", mapOf("ids" to ids, "pos_m" to live.map { it.worldPos },
            "method" to live.map { it.method }, "tracked" to live.map { it.tracked },
            "count" to live.firstOrNull()?.countText?.get("en")))
      }
    } catch (e: Throwable) {
      failed = true
      Agq.log.error("pick_markers", e.message)
    }
  }

  fun hide() = update(emptyList(), 0f)

  private fun create() {
    // unit ring (inner radius 1, stroke 0.08 → ~4 mm at 5 cm), scaled to the footprint; a 1.4 cm chevron cone
    val ring = Shapes.ring(1f, 0.08f)
    val cone = Shapes.cone(0.007f, 0.014f)
    repeat(MAX) { i ->
      val rm = GhostMat.create(body = 0.10f, rim = 0f)
      val cm = GhostMat.create(body = 0.9f, rim = 0.1f, rimPower = 1.5f)
      markers += Marker(entity("mesh://agj/pick/ring/$i", ring, rm), entity("mesh://agj/pick/chevron/$i", cone, cm), rm, cm)
    }
  }

  private fun entity(uri: String, d: MeshData, mat: GhostMat): Entity {
    registerMesh(uri) {
      val m = SpatialMesh.fromGltf(d)
      SceneMesh.meshWithMaterials(m.positions, m.normals, FloatArray(d.vertexCount * 2), IntArray(d.vertexCount) { -1 },
          m.indices, intArrayOf(0, m.indices.size), arrayOf(mat.material), false)
    }
    return Entity.create(listOf(Mesh(Uri.parse(uri)), Transform(Pose(Vector3(0f, -10f, 0f))), Visible(false)))
  }

  companion object {
    /** Every instance of the step's items (four screws + two panels). */
    const val MAX = 8
  }
}
