package com.agj.android.world

import android.net.Uri
import com.agj.android.core.Agq
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.guide.GhostMat
import com.agj.android.guide.GhostMaterial
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

/** FLAT-PARTS: where the outline of the part to pick up goes. Pure, tested. */
object FlatOutlineLayout {
  /** The tubes lie this far above the part's top face. */
  const val LIFT_M = 0.003f
  const val TUBE_RADIUS_M = 0.003f
  const val PULSE_HZ = 0.4f
  const val PULSE_LOW = 0.6f

  /** Four tubes corner to corner, round the rectangle. */
  fun edges(corners: List<Vec3>): List<OutlineEdge> {
    val cs = corners.map { it + Vec3(0f, LIFT_M, 0f) }
    return cs.indices.map { i ->
      val a = cs[i]
      val d = cs[(i + 1) % cs.size] - a
      OutlineEdge(Pose3(a, Shapes.fromTo(Vec3(0f, 1f, 0f), d)), d.length())
    }
  }

  /** A slow soft pulse of the opacity, [PULSE_LOW]..1; steady at 1 with reduced motion. */
  fun pulse(timeS: Float, reducedMotion: Boolean): Float =
      if (reducedMotion) 1f else PULSE_LOW + (1f - PULSE_LOW) * (0.5f + 0.5f * sin(2 * PI.toFloat() * PULSE_HZ * timeS))
}

/**
 * FLAT-PARTS: the azure outline on the real part to pick up now ([FlatParts.lit]): four tubes on its corners, world-locked
 * between detections. Same entities and translucent material as [PlaceOutline]. Call [update] on the render tick
 * (null = hide).
 */
class FlatPartOutline(private val registerMesh: (String, () -> SceneMesh) -> Unit) {
  var reducedMotion = false

  private val edges = ArrayList<Entity>()
  private var mat: GhostMat? = null
  private var shown = false
  private var failed = false
  private var last: List<Vec3>? = null
  private var timeS = 0f

  fun update(lit: LitPart?, dtS: Float) {
    if (failed) return
    try {
      timeS += dtS
      if (lit == null || lit.opacity <= 0f) {
        if (shown) edges.forEach { it.setComponent(Visible(false)) }
        shown = false
        return
      }
      if (edges.isEmpty()) create()
      mat?.set(GhostMaterial.Tint.ACCENT, 0.95f, 0.2f, rimPower = 1.5f, opacity = lit.opacity * FlatOutlineLayout.pulse(timeS, reducedMotion))
      if (lit.corners != last) {
        last = lit.corners
        FlatOutlineLayout.edges(lit.corners).forEachIndexed { i, e ->
          edges[i].setComponent(Transform(e.pose.toSdk()))
          edges[i].setComponent(Scale(Vector3(1f, e.lengthM, 1f)))
        }
      }
      if (!shown) edges.forEach { it.setComponent(Visible(true)) }
      shown = true
    } catch (e: Throwable) {
      failed = true
      Agq.log.error("flat_outline", e.message)
    }
  }

  private fun create() {
    val m = GhostMat.create(body = 0.95f, rim = 0.2f, rimPower = 1.5f)
    mat = m
    val tube = Shapes.cylinder(FlatOutlineLayout.TUBE_RADIUS_M, 1f)
    repeat(4) { i ->
      val uri = "mesh://agj/flat/edge/$i"
      registerMesh(uri) {
        val d = SpatialMesh.fromGltf(tube)
        SceneMesh.meshWithMaterials(d.positions, d.normals, FloatArray(tube.vertexCount * 2), IntArray(tube.vertexCount) { -1 },
            d.indices, intArrayOf(0, d.indices.size), arrayOf(m.material), false)
      }
      edges += Entity.create(listOf(Mesh(Uri.parse(uri)), Transform(Pose(Vector3(0f, -10f, 0f))), Visible(false)))
    }
  }
}
