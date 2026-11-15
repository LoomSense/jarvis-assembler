package com.agj.android.vision

import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The dual-tag strip: the app tracks the 36h11 tag and reports the STRIP CENTRE (tools/quest/marker/marker.json). */
class MarkerStripTest {
  private fun markerJson(): File? {
    var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (d != null && !File(d, "tools/quest/marker/marker.json").isFile) d = d.parentFile
    return d?.let { File(it, "tools/quest/marker/marker.json") }
  }

  @Test
  fun defaultsMatchMarkerJson() {
    val f = markerJson()
    assumeTrue("marker.json not found", f != null)
    val o = Json.parseToJsonElement(f!!.readText()).jsonObject
    assertEquals("strip_centre", o["object"]!!.jsonPrimitive.content)
    val tag = o["tags"]!!.jsonArray.map { it.jsonObject }.first { it["key"]!!.jsonPrimitive.content == "tag36h11_0" }
    val off = tag["offset_m"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }
    val c = MarkerConfig()
    assertEquals(Vec3(off[0], off[1], off[2]), c.tagOffset)
    assertEquals(tag["size_m"]!!.jsonPrimitive.double, c.sizeM, 1e-9)
    assertEquals(tag["id"]!!.jsonPrimitive.content.toInt(), c.markerId)
    val q = tag["offset_q"]!!.jsonArray.map { it.jsonPrimitive.double }
    assertEquals(listOf(0.0, 0.0, 0.0, 1.0), q) // tags upright on the strip: offset is a pure translation
  }

  @Test
  fun solverReportsTheStripCentre() {
    // camera looks at the tag face-on, 0.5 m away; the strip centre is 50 mm to the tag's +X (offset is −0.05)
    val det = MarkerDetection(DoubleArray(8), listOf(PnpCandidate(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.1, 0.0, 0.5), 0.3)), 1.0)
    val r = MarkerSolver(MarkerConfig()).solve(det, Pose3.IDENTITY, null, 0L, null)
    assertTrue(r.tracked)
    assertTrue("${r.worldFromMarker}", (r.worldFromMarker!!.t - Vec3(0.15f, 0f, 0.5f)).length() < 1e-6f)
    // rotated tag: the offset is applied in tag axes
    val rot = MarkerDetection(DoubleArray(8), listOf(PnpCandidate(doubleArrayOf(0.0, 0.0, Math.PI / 2), doubleArrayOf(0.0, 0.0, 0.5), 0.3)), 1.0)
    val r2 = MarkerSolver(MarkerConfig()).solve(rot, Pose3.IDENTITY, null, 0L, null)
    assertTrue("${r2.worldFromMarker}", (r2.worldFromMarker!!.t - Vec3(0f, 0.05f, 0.5f)).length() < 1e-6f)
    // single-tag print: vision.json marker_tag_offset_m [0, 0, 0]
    val single = VisionConfig.parse("""{"marker_tag_offset_m":[0,0,0]}""").marker
    assertEquals(Vec3.ZERO, single.tagOffset)
    val r3 = MarkerSolver(single).solve(det, Pose3.IDENTITY, null, 0L, null)
    assertTrue((r3.worldFromMarker!!.t - Vec3(0.1f, 0f, 0.5f)).length() < 1e-6f)
    // ASSEMBLY ← MARKER (strip centre) is still the drawer-front placement
    assertEquals(MarkerPlacement.MALM_DRAWER_FRONT, VisionConfig.parse(null).marker.assemblyFromMarker)
  }
}
