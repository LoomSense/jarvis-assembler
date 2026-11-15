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

/** One edge of the outline: a unit cylinder along +Y placed at [pose] and stretched to [lengthM]. */
data class OutlineEdge(val pose: Pose3, val lengthM: Float)

/**
 * The blue outline on the table where the base part goes ("Place the drawer front inside the blue outline"):
 * the part's [Footprint] rectangle, grown by [GROW_M], lifted [LIFT_M] off the surface. Pure, tested.
 */
object OutlineLayout {
  const val GROW_M = 0.01f
  const val LIFT_M = 0.003f

  fun edges(worldFromTable: Pose3, fp: Footprint): List<OutlineEdge> {
    val g = Footprint(fp.cx, fp.cz, fp.halfX + GROW_M, fp.halfZ + GROW_M)
    val cs = g.corners().map { worldFromTable.transform(it + Vec3(0f, LIFT_M, 0f)) }
    return cs.indices.map { i ->
      val a = cs[i]
      val b = cs[(i + 1) % cs.size]
      val d = b - a
      OutlineEdge(Pose3(a, Shapes.fromTo(Vec3(0f, 1f, 0f), d)), d.length())
    }
  }
}

/**
 * Draws [OutlineLayout] in the scene: four azure tubes (2.5 mm) on the table around the base part's place.
 * Self-contained Spatial SDK entities; call [update] on the render tick with the anchor pose (null = hide).
 */
class PlaceOutline(private val registerMesh: (String, () -> SceneMesh) -> Unit) {
  private val edges = ArrayList<Entity>()
  private var mat: GhostMat? = null
  private var shown = false
  private var failed = false
  private var last: Pair<Pose3, Footprint>? = null

  fun update(worldFromTable: Pose3?, fp: Footprint?, opacity: Float = 1f) {
    if (failed) return
    try {
      if (worldFromTable == null || fp == null || opacity <= 0f) {
        if (shown) edges.forEach { it.setComponent(Visible(false)) }
        shown = false
        return
      }
      if (edges.isEmpty()) create()
      mat?.set(GhostMaterial.Tint.PLACE, 0.95f, 0.2f, rimPower = 1.5f, opacity = opacity)
      val key = worldFromTable to fp
      if (key != last) {
        last = key
        OutlineLayout.edges(worldFromTable, fp).forEachIndexed { i, e ->
          edges[i].setComponent(Transform(e.pose.toSdk()))
          edges[i].setComponent(Scale(Vector3(1f, e.lengthM, 1f)))
        }
      }
      if (!shown) edges.forEach { it.setComponent(Visible(true)) }
      shown = true
    } catch (e: Throwable) {
      failed = true
      Agq.log.error("place_outline", e.message)
    }
  }

  private fun create() {
    val m = GhostMat.create(body = 0.95f, rim = 0.2f, rimPower = 1.5f)
    mat = m
    val tube = Shapes.cylinder(0.00125f, 1f)
    repeat(4) { i ->
      val uri = "mesh://agj/place/edge/$i"
      registerMesh(uri) {
        val d = SpatialMesh.fromGltf(tube)
        SceneMesh.meshWithMaterials(d.positions, d.normals, FloatArray(tube.vertexCount * 2), IntArray(tube.vertexCount) { -1 },
            d.indices, intArrayOf(0, d.indices.size), arrayOf(m.material), false)
      }
      edges += Entity.create(listOf(Mesh(Uri.parse(uri)), Transform(Pose(Vector3(0f, -10f, 0f))), Visible(false)))
    }
  }
}
