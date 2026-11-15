package com.agj.android

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.guide.Glb
import com.agj.android.guide.Guide
import com.agj.android.guide.Manifest
import com.agj.android.guide.MeshData
import com.agj.android.guide.SpatialMesh
import com.agj.android.vision.Frames
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One frame convention end to end: the app is right-handed (WORLD, ASSEMBLY = glTF), the Spatial SDK left-handed;
 * the renderer converts at the SDK edge only (every entity Transform with [Frames.mirrorZ], every mesh with
 * [SpatialMesh.fromGltf]). These tests simulate the SDK's entity tree (root = WORLD ← TABLE, child = TABLE ←
 * ASSEMBLY, mesh vertices in the child) in Spatial axes and check that what the wearer sees is the right-handed
 * scene, not its mirror image. Assumes the SDK composes Transforms and rotates vectors with the standard quaternion
 * formulas (the same assumption as vision/Frames.kt).
 */
class FrameConventionTest {
  private fun res(name: String) = File(javaClass.classLoader!!.getResource("malm-drawer/$name")!!.toURI())

  private val guide by lazy { Guide.build(Manifest.parse(res("manifest.json").readText()), Glb.read(res("walkthrough.glb"))) }

  private fun near(a: Vec3, b: Vec3, eps: Float = 1e-4f) = assertTrue("$a vs $b", (a - b).length() < eps)

  /** Where the SDK draws an ASSEMBLY-frame vertex [v], given our right-handed tree [worldFromTable] × [tableFromAssembly]. */
  private fun spatialRendered(worldFromTable: Pose3, tableFromAssembly: Pose3, v: Vec3): Vec3 {
    val root = Frames.mirrorZ(worldFromTable) // what Pose3.toSdk() hands the SDK
    val child = Frames.mirrorZ(tableFromAssembly)
    return root.transform(child.transform(Frames.mirrorZ(v))) // vertex as SpatialMesh.fromGltf stores it
  }

  /** Head-local coordinates in Spatial axes: (right, up, forward). */
  private fun spatialHeadLocal(headWorld: Pose3, pSpatial: Vec3): Vec3 = Frames.mirrorZ(headWorld).inverse().transform(pSpatial)

  @Test
  fun mirrorIsAnInvolutionAndComposes() {
    val a = Pose3(Vec3(0.3f, 1.1f, -0.7f), Quat.axisAngle(Vec3(0.2f, 1f, -0.4f), 0.9f))
    val b = Pose3(Vec3(-0.05f, 0.2f, 0.4f), Quat.axisAngle(Vec3(1f, 0.1f, 0.3f), -1.7f))
    val aa = Frames.mirrorZ(Frames.mirrorZ(a))
    near(a.t, aa.t)
    assertTrue(Frames.angleDeg(a.q, aa.q) < 1e-3)
    val ab = Frames.mirrorZ(a * b)
    val mab = Frames.mirrorZ(a) * Frames.mirrorZ(b)
    near(ab.t, mab.t)
    assertTrue(Frames.angleDeg(ab.q, mab.q) < 1e-3)
    // the FloatArray helper used by vision.spatialPose() agrees
    val f = Frames.worldToSpatial(a)
    val m = Frames.mirrorZ(a)
    assertEquals(listOf(m.t.x, m.t.y, m.t.z, m.q.x, m.q.y, m.q.z, m.q.w), f.toList())
  }

  @Test
  fun leftSidePanelStaysOnTheLeftInSpatialAxes() {
    val left = guide.parts["part_drawer_side_left_1"]!!.center
    val right = guide.parts["part_drawer_side_right_1"]!!.center
    val front = guide.parts["part_drawer_front_1"]!!.center
    val back = guide.parts["part_drawer_back_1"]!!.center
    // glTF ASSEMBLY: +X to the right of someone facing the front (+Z), the sides and back at Z < 0
    assertTrue("left $left right $right", left.x < 0f && right.x > 0f)
    assertTrue("front $front back $back", front.z > back.z)

    // WORLD (right-handed, session start looks down −Z): head at 1.6 m; the drawer 0.6 m ahead, its front toward us.
    val head = Pose3(Vec3(0f, 1.6f, 0f))
    val worldFromTable = Pose3(Vec3(0f, 0.8f, -0.6f))
    val l = spatialHeadLocal(head, spatialRendered(worldFromTable, Pose3.IDENTITY, left))
    val r = spatialHeadLocal(head, spatialRendered(worldFromTable, Pose3.IDENTITY, right))
    val f = spatialHeadLocal(head, spatialRendered(worldFromTable, Pose3.IDENTITY, front))
    val b = spatialHeadLocal(head, spatialRendered(worldFromTable, Pose3.IDENTITY, back))
    // Spatial: +X right, +Z forward
    assertTrue("left panel at $l must be on the viewer's left", l.x < 0f)
    assertTrue("right panel at $r must be on the viewer's right", r.x > 0f)
    assertTrue("drawer must be in front: $f", f.z > 0.3f)
    assertTrue("front ($f) must be nearer than the back ($b)", f.z < b.z)

    // The naive path (glTF vertices straight into the SDK, no mirror) shows the mirror image: back nearer than front.
    val rawRoot = Pose3(Vec3(0f, 0.8f, 0.6f)) // the same spot, written in Spatial axes
    val fRaw = rawRoot.transform(front)
    val bRaw = rawRoot.transform(back)
    assertTrue("unmirrored mesh would show its back first", bRaw.z < fRaw.z)
  }

  @Test
  fun everyPartKeepsItsPlaceRelativeToTheViewerForAnyPoses() {
    val heads =
        listOf(
            Pose3(Vec3(0f, 1.6f, 0f)),
            Pose3(Vec3(0.4f, 1.5f, 0.2f), Quat.yaw(0.8f) * Quat.axisAngle(Vec3(1f, 0f, 0f), -0.4f)),
            Pose3(Vec3(-1.2f, 1.7f, 0.9f), Quat.yaw(-2.3f)),
        )
    val tables =
        listOf(
            Pose3(Vec3(0f, 0.8f, -0.6f)),
            Pose3(Vec3(0.3f, 0.75f, -0.9f), Quat.yaw(0.6f)),
            Pose3(Vec3(-0.8f, 0.7f, 0.4f), Quat.yaw(-2.0f) * Quat.axisAngle(Vec3(0.3f, 0f, 1f), 0.2f)),
        )
    for (h in heads) for (wt in tables) for (s in guide.steps) for ((name, geo) in guide.parts) {
      val c = geo.center
      val pWorld = (wt * s.tableFromAssembly).transform(c)
      val pSpatial = spatialRendered(wt, s.tableFromAssembly, c)
      near(Frames.mirrorZ(pWorld), pSpatial)
      // WORLD head-local is (right, up, back); Spatial head-local is (right, up, forward)
      val lw = h.inverse().transform(pWorld)
      val ls = spatialHeadLocal(h, pSpatial)
      assertTrue("$name step ${s.n}: $lw vs $ls", (Vec3(lw.x, lw.y, -lw.z) - ls).length() < 1e-4f)
    }
  }

  @Test
  fun fixedSpotAndPanelsAreInFrontOfTheWearer() {
    // Head poses as the SDK reports them (left-handed Spatial: +Z forward), converted the way MainActivity does
    // (`scene.getViewerPose().toPose3()` = Frames.spatialToWorld).
    val spatialHeads =
        listOf(
            Pose3(Vec3(0f, 1.6f, 0f)), // session start: looking down Spatial +Z
            Pose3(Vec3(0.5f, 1.5f, -0.3f), Quat.yaw(1.1f)),
            Pose3(Vec3(-0.2f, 1.7f, 2.0f), Quat.yaw(-2.6f) * Quat.axisAngle(Vec3(1f, 0f, 0f), 0.3f)),
        )
    for (hs in spatialHeads) {
      val head = Frames.spatialToWorld(hs.t.x, hs.t.y, hs.t.z, hs.q.x, hs.q.y, hs.q.z, hs.q.w)
      val spot = com.agj.android.ui.Placement.fixedTableSpot(head)
      val panel = com.agj.android.ui.Placement.ahead(head, 0.8f, 0.21f)
      for ((what, pw) in listOf("fixed spot" to spot.t, "step window" to panel)) {
        val local = hs.inverse().transform(Frames.mirrorZ(pw)) // Spatial head-local: +Z forward
        assertTrue("$what $local must be in front (Spatial +Z) for head $hs", local.z > 0.5f)
        assertTrue("$what $local must be centred", kotlin.math.abs(local.x) < 1e-3f)
        assertTrue("$what $pw must be below the eyes", pw.y < head.t.y - 0.2f)
      }
      // the drawer on the fixed spot shows its front (+Z glTF) to the wearer: front nearer than back
      val front = guide.parts["part_drawer_front_1"]!!.center
      val back = guide.parts["part_drawer_back_1"]!!.center
      val f = hs.inverse().transform(spatialRendered(spot, Pose3.IDENTITY, front))
      val b = hs.inverse().transform(spatialRendered(spot, Pose3.IDENTITY, back))
      assertTrue("front $f nearer than back $b", f.z < b.z)
      val l = hs.inverse().transform(spatialRendered(spot, Pose3.IDENTITY, guide.parts["part_drawer_side_left_1"]!!.center))
      assertTrue("left side panel $l on the wearer's left", l.x < 0f)
    }
  }

  @Test
  fun spatialMeshKeepsOutwardFaces() {
    for (node in listOf("part_drawer_side_left_1", "part_drawer_front_1", "hw_100365_1")) {
      val g = guide.parts[node]!!
      val src = MeshData(g.positions, g.normals, g.indices)
      val m = SpatialMesh.fromGltf(src)
      fun v(a: FloatArray, i: Int) = Vec3(a[3 * i], a[3 * i + 1], a[3 * i + 2])
      fun facing(d: MeshData, t: Int): Float {
        val (i, j, k) = Triple(d.indices[t], d.indices[t + 1], d.indices[t + 2])
        val n = (v(d.positions, j) - v(d.positions, i)).cross(v(d.positions, k) - v(d.positions, i))
        return n.dot(v(d.normals, i) + v(d.normals, j) + v(d.normals, k))
      }
      var same = 0
      var total = 0
      for (t in 0 until src.indices.size - 2 step 3) {
        val a = facing(src, t)
        if (kotlin.math.abs(a) < 1e-12f) continue
        total++
        if ((a > 0f) == (facing(m, t) > 0f)) same++
        // the mirrored triangle's geometric normal is the mirror of the original's
        val i = m.indices[t]
        near(Frames.mirrorZ(v(src.positions, i)), v(m.positions, i), 1e-7f)
      }
      assertTrue("$node: $same of $total triangles keep their facing", total > 0 && same == total)
      // the source arrays are not modified
      assertEquals(g.positions[2], src.positions[2], 0f)
    }
  }
}
