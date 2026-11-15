package com.agj.android.guide

import android.net.Uri
import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

/**
 * The dotted arc from the hand to the right part (pure, tested): dots from [from] towards [to], lifted in the middle
 * so the line reads as "over there" and does not run through things on the table. `phase` 0..1 marches the dots
 * towards [to]; the last dot always stops short of it (the arrowhead goes there).
 */
object HandArc {
  const val SPACING_M = 0.03f
  const val MAX_DOTS = 16
  const val MIN_LEN_M = 0.12f // nearer than this the hand is at the part: no line
  /** The arc lands this far outside the part's footprint, this high above the table. */
  const val LAND_OUT_M = 0.06f
  const val LAND_UP_M = 0.02f

  /**
   * Where the arc ends: beside the part on the hand's side, just above the table. Not above the part: the pick
   * marker's chevron and the name pill are there.
   */
  fun landing(from: Vec3, target: Vec3, radiusM: Float): Vec3 {
    val flat = Vec3(from.x - target.x, 0f, from.z - target.z)
    val n = flat.length()
    val dir = if (n < 1e-3f) Vec3(0f, 0f, 1f) else flat * (1f / n)
    return target + dir * (radiusM + LAND_OUT_M) + Vec3(0f, LAND_UP_M, 0f)
  }

  fun count(from: Vec3, to: Vec3): Int {
    val len = (to - from).length()
    if (len < MIN_LEN_M) return 0
    return (len / SPACING_M).toInt().coerceIn(4, MAX_DOTS)
  }

  fun point(from: Vec3, to: Vec3, t: Float): Vec3 {
    val len = (to - from).length()
    val lift = (0.12f * len).coerceIn(0.02f, 0.08f)
    val mid = (from + to) * 0.5f + Vec3(0f, lift * 2f, 0f) // quadratic control point: the apex is lifted by `lift`
    val a = from.lerp(mid, t)
    val b = mid.lerp(to, t)
    return a.lerp(b, t)
  }

  fun points(from: Vec3, to: Vec3, phase: Float = 0f): List<Vec3> {
    val n = count(from, to)
    return (0 until n).map { i -> point(from, to, ((i + phase.coerceIn(0f, 1f)) / (n + 1)).coerceIn(0f, 1f)) }
  }

  /** Pulse of the ring on the right part: 0..1 over one second → (radius, opacity). Still with reduced motion. */
  fun pulse(baseRadius: Float, timeS: Float, reducedMotion: Boolean): Pair<Float, Float> {
    if (reducedMotion) return (baseRadius + 0.02f) to 0.8f
    val k = timeS - kotlin.math.floor(timeS)
    return (baseRadius + 0.06f * k) to (0.9f * (1f - k))
  }
}

/** What the held-part guide asks of the scene on a wrong verdict (the JVM tests record it). */
interface HeldCues {
  var reducedMotion: Boolean

  /**
   * Every render frame. [target]: the right part's WORLD position on the table, null = hide everything. [from]: the
   * wrist holding the wrong part, null = no usable hand pose (the pulse only).
   */
  fun update(target: Vec3?, radiusM: Float, from: Vec3?, dtS: Float)
}

/**
 * Scene side of "that is the wrong part, the right one is over there": a ring that pulses on the right part's pick
 * marker and a dotted arc from the hand that lands beside it with an arrowhead pointing at it. Azure (the accent
 * marks what to take; red stays on the badge). Own entities and [GhostMat]s (created translucent, with the unlit
 * fallback), same pattern as the pick markers; nothing here touches world/PickMarkers.kt.
 *
 * Call [update] every render frame with the part's WORLD position and footprint radius; `target = null` hides
 * everything; `from = null` (no wrist pose) shows the pulse only.
 */
class VerdictCues(private val registerMesh: (String, () -> SceneMesh) -> Unit) : HeldCues {
  override var reducedMotion = false

  private var ring: Entity? = null
  private var head: Entity? = null
  private val dots = ArrayList<Entity>()
  private var ringMat: GhostMat? = null
  private var dotMat: GhostMat? = null
  private val shown = HashMap<Entity, Boolean>()
  private var timeS = 0f
  private var failed = false
  private var active = false

  override fun update(target: Vec3?, radiusM: Float, from: Vec3?, dtS: Float) {
    if (failed) return
    try {
      if (target == null) {
        if (active) hideAll()
        return
      }
      if (ring == null) create()
      active = true
      timeS += dtS
      val (r, a) = HandArc.pulse(radiusM + 0.02f, timeS, reducedMotion)
      ringMat?.set(GhostMaterial.Tint.ACCENT, 0.9f, 0f, opacity = a)
      ring?.let {
        it.setComponent(Transform(Pose3(target + Vec3(0f, 0.004f, 0f)).toSdk()))
        it.setComponent(Scale(Vector3(r)))
        show(it, true)
      }
      val end = from?.let { HandArc.landing(it, target, radiusM) }
      val pts = if (from == null || end == null) emptyList() else HandArc.points(from, end, if (reducedMotion) 0f else (timeS * 1.2f) % 1f)
      for ((i, d) in dots.withIndex()) {
        val p = pts.getOrNull(i)
        if (p != null) d.setComponent(Transform(Pose(p.toSdk())))
        show(d, p != null)
      }
      head?.let { h ->
        val on = end != null && pts.isNotEmpty()
        if (on) {
          // the cone points +Y: turn it to point at the part, from where the arc lands
          val dir = Shapes.norm(target + Vec3(0f, 0.005f, 0f) - end!!)
          h.setComponent(Transform(Pose3(end, Shapes.fromTo(Vec3(0f, 1f, 0f), dir)).toSdk()))
        }
        show(h, on)
      }
    } catch (e: Throwable) {
      failed = true
      Agq.log.error("verdict_cues", e.message)
    }
  }

  private fun hideAll() {
    active = false
    ring?.let { show(it, false) }
    head?.let { show(it, false) }
    dots.forEach { show(it, false) }
  }

  private fun show(e: Entity, on: Boolean) {
    if (shown[e] == on) return
    shown[e] = on
    e.setComponent(Visible(on))
  }

  private fun create() {
    val rm = GhostMat.create(body = 0.9f, rim = 0f)
    val dm = GhostMat.create(body = 0.9f, rim = 0.1f, rimPower = 1.5f)
    ringMat = rm
    dotMat = dm
    ring = entity("mesh://agj/verdict/ring", Shapes.ring(1f, 0.10f), rm)
    head = entity("mesh://agj/verdict/head", Shapes.cone(0.008f, 0.02f), dm)
    val dot = Shapes.sphere(0.004f)
    repeat(HandArc.MAX_DOTS) { i -> dots += entity("mesh://agj/verdict/dot/$i", dot, dm) }
  }

  private fun entity(uri: String, d: MeshData, mat: GhostMat): Entity {
    registerMesh(uri) {
      val m = SpatialMesh.fromGltf(d)
      SceneMesh.meshWithMaterials(m.positions, m.normals, FloatArray(d.vertexCount * 2), IntArray(d.vertexCount) { -1 },
          m.indices, intArrayOf(0, m.indices.size), arrayOf(mat.material), false)
    }
    return Entity.create(listOf(Mesh(Uri.parse(uri)), Transform(Pose(Vector3(0f, -10f, 0f))), Visible(false))).also { shown[it] = false }
  }
}
