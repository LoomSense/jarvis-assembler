package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.guide.Glb
import com.agj.android.guide.Guide
import com.agj.android.guide.Manifest
import java.io.File
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StepFootprints] (the app's own guide model) against tools/markerless/footprint.py (`step_footprints_ref.json`,
 * written from the same manifest and GLB: src/test/resources/malm-drawer/).
 */
class StepFootprintTest {
  private fun res(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())

  private val manifest by lazy { Manifest.parse(res("malm-drawer/manifest.json").readText()) }
  private val guide by lazy { Guide.build(manifest, Glb.read(res("malm-drawer/walkthrough.glb"))) }
  private val ref: JsonObject by lazy { Json.parseToJsonElement(res("step_footprints_ref.json").readText()).jsonObject["steps"]!!.jsonObject }

  /** 4 × 4 row-major of a rigid transform. */
  private fun matrix(p: Pose3): DoubleArray {
    val cols = listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f)).map { p.q.rotate(it) }
    val m = DoubleArray(16)
    for (c in 0 until 3) {
      m[c] = cols[c].x.toDouble()
      m[4 + c] = cols[c].y.toDouble()
      m[8 + c] = cols[c].z.toDouble()
    }
    m[3] = p.t.x.toDouble()
    m[7] = p.t.y.toDouble()
    m[11] = p.t.z.toDouble()
    m[15] = 1.0
    return m
  }

  private fun assertPose(label: String, expected: DoubleArray, actual: Pose3, eps: Double = 1e-4) {
    val m = matrix(actual)
    for (i in 0 until 16) assertEquals("$label [${i / 4}][${i % 4}]", expected[i], m[i], eps)
  }

  @Test
  fun everyStepAndVariantMatchesThePythonReference() {
    assertEquals(manifest.steps.map { it.n.toString() }.toSet(), ref.keys)
    var checked = 0
    var maxSizeMm = 0.0
    var maxPose = 0.0
    for (n in guide.stepNumbers) for (done in listOf(false, true)) {
      val label = "step $n ${if (done) "done" else "base"}"
      val r = ref[n.toString()]!!.jsonObject[if (done) "done" else "base"]!!.jsonObject
      val fp = StepFootprints.of(guide, manifest, n, done) ?: error("$label: no footprint")
      assertEquals(n, fp.step)
      assertEquals("$label length", r["length_m"]!!.jsonPrimitive.double, fp.lengthM, 1e-4) // 0.1 mm
      assertEquals("$label width", r["width_m"]!!.jsonPrimitive.double, fp.widthM, 1e-4)
      assertEquals("$label height", r["height_m"]!!.jsonPrimitive.double, fp.heightM, 1e-4)
      assertEquals("$label nodes", r["nodes"]!!.jsonArray.map { it.jsonPrimitive.content }, fp.nodes)
      val m = r["assembly_from_footprint"]!!.jsonArray.flatMap { row -> row.jsonArray.map { it.jsonPrimitive.double } }.toDoubleArray()
      assertPose("$label ASSEMBLY ← FOOTPRINT", m, fp.assemblyFromFootprint)
      for ((key, v) in listOf("length_m" to fp.lengthM, "width_m" to fp.widthM, "height_m" to fp.heightM)) {
        maxSizeMm = maxOf(maxSizeMm, abs(v - r[key]!!.jsonPrimitive.double) * 1000)
      }
      matrix(fp.assemblyFromFootprint).forEachIndexed { i, v -> maxPose = maxOf(maxPose, abs(v - m[i])) }
      // the reference recorded a quarter or half turn for every resting pose: the box is the oriented rectangle
      assertTrue("$label axis aligned (reference)", r["axis_aligned"]!!.jsonPrimitive.boolean)
      assertTrue("$label axis aligned", StepFootprints.isAxisAligned(guide.step(n)!!.tableFromAssembly.q))
      checked++
    }
    assertEquals(14, checked) // 7 steps × {base, done}
    println("step footprints, Kotlin vs Python: $checked variants, max size diff %.5f mm, max transform entry diff %.2e".format(java.util.Locale.ROOT, maxSizeMm, maxPose))
  }

  @Test
  fun stepOneIsThePanelFrame() {
    // the drawer front alone, inside face up: FOOTPRINT is PanelPose.kt's PANEL
    for (done in listOf(false, true)) {
      val fp = StepFootprints.of(guide, manifest, 1, done)!!
      assertEquals(listOf("part_drawer_front_1"), fp.nodes)
      assertEquals(0.380, fp.lengthM, 1e-4)
      assertEquals(0.130, fp.widthM, 1e-4)
      assertEquals(0.016, fp.heightM, 1e-4)
      assertPose("step 1 vs ASSEMBLY_FROM_PANEL", matrix(PanelFrames.ASSEMBLY_FROM_PANEL), fp.assemblyFromFootprint)
      assertPose("step 1 vs the numbers", matrix(Pose3(Vec3(0f, 0.05f, 0.016f), MarkerPlacement.MALM_DRAWER_FRONT.q)), fp.assemblyFromFootprint)
    }
  }

  @Test
  fun sizesOfTheDrawerPerStep() {
    fun mm(n: Int, done: Boolean) = StepFootprints.of(guide, manifest, n, done)!!.let { listOf(it.lengthM, it.widthM, it.heightM).map { v -> Math.round(v * 1000).toInt() } }
    assertEquals(listOf(380, 130, 16), mm(2, false))   // the front alone when step 2 starts
    assertEquals(listOf(380, 130, 418), mm(2, true))   // the sides standing on it
    assertEquals(listOf(380, 130, 418), mm(3, false))
    for (n in 4..7) for (done in listOf(false, true)) assertEquals("step $n", listOf(418, 380, 130), mm(n, done)) // turned over
    assertEquals(3, StepFootprints.of(guide, manifest, 4, false)!!.nodes.size) // front + two sides: an open U
    assertTrue("part_drawer_bottom_1" in StepFootprints.of(guide, manifest, 4, true)!!.nodes)
  }

  @Test
  fun footprintFrameEnclosesThePartsAndStandsOnTheSurface() {
    for (n in guide.stepNumbers) for (done in listOf(false, true)) {
      val fp = StepFootprints.of(guide, manifest, n, done)!!
      val footprintFromAssembly = fp.assemblyFromFootprint.inverse()
      val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
      val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
      for (node in fp.nodes) {
        val p = guide.parts[node]!!.positions
        for (i in p.indices step 3) {
          val v = footprintFromAssembly.transform(Vec3(p[i], p[i + 1], p[i + 2]))
          lo[0] = minOf(lo[0], v.x); hi[0] = maxOf(hi[0], v.x)
          lo[1] = minOf(lo[1], v.y); hi[1] = maxOf(hi[1], v.y)
          lo[2] = minOf(lo[2], v.z); hi[2] = maxOf(hi[2], v.z)
        }
      }
      val label = "step $n done=$done"
      // +X along the long side, +Y along the short side, centred; +Z up from the resting plane
      assertEquals("$label -L/2", -fp.lengthM / 2, lo[0].toDouble(), 1e-4)
      assertEquals("$label +L/2", fp.lengthM / 2, hi[0].toDouble(), 1e-4)
      assertEquals("$label -W/2", -fp.widthM / 2, lo[1].toDouble(), 1e-4)
      assertEquals("$label +W/2", fp.widthM / 2, hi[1].toDouble(), 1e-4)
      assertEquals("$label on the plane", 0.0, lo[2].toDouble(), 1e-4)
      assertEquals("$label height", fp.heightM, hi[2].toDouble(), 1e-4)
      // FOOTPRINT +Z is the step table's up, and the origin is on the surface the parts stand on
      val tableFromFootprint = guide.step(n)!!.tableFromAssembly * fp.assemblyFromFootprint
      val up = tableFromFootprint.q.rotate(Vec3(0f, 0f, 1f))
      assertTrue("$label up $up", abs(up.x) < 1e-3f && abs(up.y - 1f) < 1e-3f && abs(up.z) < 1e-3f)
      assertTrue("$label length ≥ width", fp.lengthM >= fp.widthM)
    }
  }

  @Test
  fun noFootprintWithoutPartsOrForATiltedPose() {
    assertNull(StepFootprints.of(guide, manifest, 99))
    assertTrue(StepFootprints.isAxisAligned(Quat.IDENTITY))
    assertTrue(StepFootprints.isAxisAligned(Quat.axisAngle(Vec3(1f, 0f, 0f), Math.PI.toFloat() / 2)))
    assertTrue(!StepFootprints.isAxisAligned(Quat.axisAngle(Vec3(0f, 1f, 0f), Math.toRadians(30.0).toFloat())))
    // a step resting at 30° about the vertical: the axis-aligned box is not the outline → no footprint
    val tilted = guide.steps.map { if (it.n == 1) it.copy(tableFromAssembly = Pose3(it.tableFromAssembly.t, Quat.axisAngle(Vec3(0f, 1f, 0f), Math.toRadians(30.0).toFloat()) * it.tableFromAssembly.q)) else it }
    assertNull(StepFootprints.of(Guide(tilted, guide.parts, guide.addedAt, guide.finalPoses, guide.finalScales), manifest, 1))
  }
}
