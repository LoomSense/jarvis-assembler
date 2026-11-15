package com.agj.android

import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.guide.ActionKind
import com.agj.android.guide.Glb
import com.agj.android.guide.Guide
import com.agj.android.guide.HttpJobRepository
import com.agj.android.guide.Manifest
import com.agj.android.guide.PartStatus
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against the real job files copied from jobs/malm-drawer/ (src/test/resources/malm-drawer/). */
class GuideTest {
  private fun res(name: String) = File(javaClass.classLoader!!.getResource("malm-drawer/$name")!!.toURI())

  private val manifest by lazy { Manifest.parse(res("manifest.json").readText()) }
  private val glb by lazy { Glb.read(res("walkthrough.glb")) }
  private val guide by lazy { Guide.build(manifest, glb) }

  private fun near(a: Vec3, b: Vec3, eps: Float) = assertTrue("$a vs $b (eps $eps m)", (a - b).length() < eps)

  @Test
  fun glbStructure() {
    assertEquals(28, glb.nodes.size)
    val root = glb.node("assembly")!!
    assertEquals(20, root.children.size)
    val clip = glb.animation("walkthrough")!!
    assertEquals(60, clip.channels.size)
    assertTrue(clip.duration > 44f && clip.duration < 45f)
    // Rest pose: parts hidden at 1e-4.
    assertEquals(1e-4f, glb.node("hw_118331_1")!!.rest.s.x, 1e-6f)
    val prim = glb.mesh(glb.node("part_drawer_front_1")!!.mesh!!).first()
    assertTrue(prim.vertexCount > 0 && prim.indices.size % 3 == 0)
    assertTrue(prim.indices.all { it in 0 until prim.vertexCount })
  }

  @Test
  fun partCounts() {
    // 7 boards + 12 hardware pieces (2 + 2 + 2 + 2 + 4); `marker` is not a part.
    assertEquals(19, guide.parts.size)
    assertEquals(manifest.parts.size, guide.parts.keys.count { it.startsWith("part_") })
    assertEquals(manifest.hardware.sumOf { it.nodes.size }, guide.parts.keys.count { it.startsWith("hw_") })
    assertEquals(7, guide.steps.size)
    assertEquals(19 + 1, guide.guidedCount) // 19 part actions + 1 turn (step 4)
    assertNotNull(guide.finalPoses["marker"])
  }

  @Test
  fun finalScalesAreOne() {
    for ((name, s) in guide.finalScales) {
      if (!(name.startsWith("part_") || name.startsWith("hw_"))) continue
      assertEquals("$name scale", 1f, s.x, 1e-3f)
      assertEquals("$name scale", 1f, s.y, 1e-3f)
      assertEquals("$name scale", 1f, s.z, 1e-3f)
    }
  }

  @Test
  fun finalPositionsMatchManifestTargets() {
    var checked = 0
    for (s in guide.steps) for (a in s.actions) {
      val node = a.node ?: continue
      val target = a.target ?: continue
      val pose = assertNotNull(a.finalPose).let { a.finalPose!! }
      near(target, pose.t, 0.002f) // within 2 mm after Blender Z-up → glTF Y-up
      checked++
    }
    assertEquals(19, checked)
    // A specific one, by hand: hw_118331_1 target_m [-0.166, 0, 0.055] (Blender) → (-0.166, 0.055, 0) glTF.
    near(Vec3(-0.166f, 0.055f, 0f), guide.finalPoses["hw_118331_1"]!!.t, 0.002f)
  }

  @Test
  fun hardwareGeometrySitsAtItsTarget() {
    val g = guide.parts["hw_120076_1"]!!
    val a = guide.step(3)!!.actions.first { it.node == "hw_120076_1" }
    // The cam lock's mesh lies around its target (within its own size).
    assertTrue((g.center - a.target!!).length() < maxOf(g.size.x, g.size.y, g.size.z))
    assertTrue(g.vertexCount > 10)
    // Boards: the drawer front spans ~380 mm.
    val front = guide.parts["part_drawer_front_1"]!!
    assertEquals(0.38f, maxOf(front.size.x, front.size.y, front.size.z), 0.02f)
  }

  @Test
  fun stepPosesFollowAssemblyRotation() {
    // Step 1: assemblyRotationDeg [90,0,0] Blender → +90° about glTF X. Step 4 onwards: 180° about X.
    fun angleAboutX(q: Quat): Double {
      val y = q.rotate(Vec3(0f, 1f, 0f))
      return Math.toDegrees(kotlin.math.atan2(y.z.toDouble(), y.y.toDouble()))
    }
    assertEquals(90.0, angleAboutX(guide.step(1)!!.tableFromAssembly.q), 2.0)
    assertEquals(180.0, abs(angleAboutX(guide.step(4)!!.tableFromAssembly.q)), 2.0)
    // Euler fallback agrees with the clip.
    val e = Guide.blenderEulerXyzToGltf(listOf(90.0, 0.0, 0.0))
    assertEquals(90.0, angleAboutX(e), 0.5)
  }

  @Test
  fun labelsUseManifestNamesAndCounts() {
    val a = guide.step(7)!!.actions.first { it.node == "hw_100365_2" }
    assertEquals(ActionKind.FASTEN, a.kind)
    assertEquals("Small screw · 100365  2 of 4", a.label("en")!!.line)
    assertEquals("Μικρή βίδα · 100365  2 από 4", a.label("el")!!.line)
    val side = guide.step(2)!!.actions.first { it.node == "part_drawer_side_right_1" }
    assertEquals("Δεξί πλαϊνό", side.label("el")!!.name)
    assertEquals(null, side.label("el")!!.count)
    // from_dir converted: Blender +Y → glTF −Z (assembly frame); with step 2's +90° X turn that is world up.
    near(Vec3(0f, 0f, -1f), side.fromDir!!, 1e-4f)
    near(Vec3(0f, 1f, 0f), guide.step(2)!!.tableFromAssembly.q.rotate(side.fromDir!!), 0.01f)
  }

  @Test
  fun navigationSkipsNotes() {
    assertEquals(1 to 0, guide.first(1))
    assertEquals(1 to 2, guide.next(1, 0)) // action 1 is a note
    assertEquals(2 to 1, guide.next(1, 3))
    assertEquals(1 to 3, guide.previous(2, 1))
    assertEquals(null, guide.next(7, 3))
    assertEquals(PartStatus.PLACED, guide.statusOf("part_drawer_front_1", 1, 2))
    assertEquals(PartStatus.CURRENT, guide.statusOf("hw_118331_1", 1, 2))
    assertEquals(PartStatus.LATER, guide.statusOf("hw_118331_2", 1, 2))
  }

  @Test
  fun assembleBuildsAssembly() {
    val a = HttpJobRepository.assemble("malm-drawer", manifest, res("walkthrough.glb"))
    assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), a.stepNumbers)
    assertTrue(a.finalPoses.size >= 20)
  }
}
