package com.agj.android.world

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.guide.Assembly
import com.agj.android.guide.Glb
import com.agj.android.guide.Guide
import com.agj.android.guide.Manifest
import com.agj.android.vision.FoundRect
import com.agj.android.vision.PanelFrames
import com.agj.android.vision.PanelMath
import com.agj.android.vision.RectsResult
import java.io.File
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** FLAT-PARTS: which board each rectangle is (world/FlatParts.kt), with the sizes the user measured on the real drawer. */
class FlatPartsTest {
  /** The real drawer, by tape: front 400 × 200, two sides 450 × 100, back 350 × 100, bottom 350 × 450. */
  private val real =
      listOf(
          Board("drawer_front", "part_drawer_front_1", 0.400, 0.200, addedAt = 0),
          Board("drawer_side_left", "part_drawer_side_left_1", 0.450, 0.100, addedAt = 1001, names = mapOf("en" to "Left side", "el" to "Αριστερό πλαϊνό")),
          Board("drawer_side_right", "part_drawer_side_right_1", 0.450, 0.100, addedAt = 1002),
          Board("drawer_bottom", "part_drawer_bottom_1", 0.450, 0.350, 0.003, addedAt = 3001),
          Board("drawer_back", "part_drawer_back_1", 0.350, 0.100, addedAt = 4001),
      )
  private val sides = "drawer_side_left+drawer_side_right"
  private val floor = PanelFrames.tableAtHeight(0f) // TABLE (x, y) → WORLD (x, 0, −y)

  private fun rect(x: Double, y: Double, yawDeg: Double, l: Double, w: Double, rectness: Double = 0.92, contrast: Double = 45.0): FoundRect {
    val yaw = Math.toRadians(yawDeg)
    return FoundRect(x, y, yaw, l, w, rectness, contrast, DoubleArray(8), PanelMath.rectCorners(x, y, yaw, l, w), 0.016)
  }

  private fun pass(vararg r: FoundRect) = RectsResult(r.toList(), "ok", 42.0, r.size, intArrayOf(1280, 960))

  private class Rig(boards: List<Board>) {
    var t = 0L
    val lines = ArrayList<String>()
    val flat = FlatParts(AgqLog({ t }, { lines += it }), { t }).also { it.setBoards(boards) }

    fun events(): List<JsonObject> = lines.map { Json.parseToJsonElement(it).jsonObject }.filter { it["ev"]!!.jsonPrimitive.content == "flat_parts" }
  }

  @Test
  fun theDevicesMeasurementsAreTheRightBoards() {
    val kinds = FlatMatch.kinds(real)
    assertEquals(listOf("drawer_front", sides, "drawer_bottom", "drawer_back"), kinds.map { it.name })
    // what the headset measured at 12:36 (sides 18 mm short, the back 30 mm short), and the other two within the same errors
    assertEquals(sides, FlatMatch.classify(0.432, 0.108, kinds)?.name)
    assertEquals(sides, FlatMatch.classify(0.431, 0.125, kinds)?.name)
    assertEquals("drawer_back", FlatMatch.classify(0.320, 0.112, kinds)?.name)
    assertEquals("drawer_front", FlatMatch.classify(0.395, 0.205, kinds)?.name)
    assertEquals("drawer_bottom", FlatMatch.classify(0.440, 0.345, kinds)?.name)
    // the old model's front is none of the real boards: nearest the back (30 and 30 mm off), the sides only 1.54 times farther
    assertNull(FlatMatch.classify(0.380, 0.130, kinds))
    // far from everything
    assertNull(FlatMatch.classify(0.600, 0.300, kinds))
    assertNull(FlatMatch.classify(0.200, 0.060, kinds))
  }

  @Test
  fun aKindMustBeWithinToleranceAndClearlyTheBest() {
    val kinds = FlatMatch.kinds(listOf(Board("a", "part_a_1", 0.400, 0.100), Board("b", "part_b_1", 0.430, 0.100)))
    assertEquals(2, kinds.size)
    assertEquals("a", FlatMatch.classify(0.405, 0.100, kinds)?.name) // 5 mm from a, 25 from b
    assertNull(FlatMatch.classify(0.412, 0.100, kinds)) // 12 mm from a, 18 from b: b is only 1.5 times farther
    assertEquals("b", FlatMatch.classify(0.460, 0.100, kinds)?.name) // 30 from b, 60 from a
    assertNull(FlatMatch.classify(0.480, 0.100, kinds)) // 50 mm from b: out of tolerance
    assertNull(FlatMatch.classify(0.430, 0.135, kinds)) // 35 mm wide of b
    assertEquals(1.0, FlatMatch.distance(0.445, 0.100, kinds[0]), 1e-9)
    assertEquals(1.0, FlatMatch.distance(0.400, 0.130, kinds[0]), 1e-9)
    assertNull(FlatMatch.classify(0.400, 0.100, emptyList()))
  }

  @Test
  fun aRectangleIsFollowedInWorldAndItsSizeIsTheMedianOfTheLastFive() {
    val rig = Rig(real)
    rig.flat.setAction(1, 1, "part_drawer_side_left_1", "drawer_side_left")
    val lengths = listOf(0.432, 0.436, 0.520, 0.430, 0.434) // the third: a hand's edge joined the blob
    for ((i, l) in lengths.withIndex()) {
      rig.t = i * 333L
      rig.flat.update(pass(rect(0.30 + 0.004 * (i % 3), 0.50 - 0.003 * (i % 2), 20.0 + i, l, 0.108)), floor, null, null, rig.t)
      // one measurement is not enough to light anything
      if (i == 0) assertNull(rig.flat.lit(rig.t))
    }
    val lit = rig.flat.lit(rig.t)!!
    assertEquals("drawer_side_left", lit.partId)
    assertEquals(0.434, lit.lengthM, 1e-9)
    assertEquals(0.108, lit.widthM, 1e-9)
    assertEquals(0.304f, lit.center.x, 1e-6f) // median of 0.300, 0.304, 0.308, 0.300, 0.304
    assertEquals(0.016f, lit.center.y, 1e-6f) // the top face
    assertEquals(-0.5f, lit.center.z, 0.004f)
    assertEquals(1f, lit.opacity, 0f)
    // 10 cm away is another rectangle; the first keeps its key
    rig.t = 2400
    rig.flat.update(pass(rect(0.30, 0.50, 22.0, 0.433, 0.108), rect(0.30, 0.62, 22.0, 0.322, 0.110)), floor, null, null, rig.t)
    assertEquals(lit.key, rig.flat.lit(rig.t)!!.key)
    val parts = rig.events().last()["parts"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf(sides, "drawer_back"), parts.map { it["part"]!!.jsonPrimitive.content })
    assertEquals(listOf(6, 1), parts.map { it["n"]!!.jsonPrimitive.int })
    // world-locked between detections, then gone: 2 s after the last sighting it is not live any more
    assertNotNull(rig.flat.lit(rig.t + 1400))
    assertTrue(rig.flat.lit(rig.t + 1800)!!.opacity < 0.5f)
    assertNull(rig.flat.lit(rig.t + 2001))
  }

  @Test
  fun theTwoSidesKeepTheirIndicesAndTheOneNearestTheAssemblyIsLit() {
    val rig = Rig(real)
    rig.flat.setAction(1, 1, "part_drawer_side_left_1", "drawer_side_left")
    val left = rect(-0.40, 0.50, 0.0, 0.440, 0.105)
    val right = rect(0.40, 0.50, 90.0, 0.445, 0.102)
    val assemblyAt = Vec3(0.5f, 0f, -0.5f)
    rig.flat.update(pass(left, right), floor, null, assemblyAt, 0)
    rig.t = 333
    rig.flat.update(pass(right, left), floor, null, assemblyAt, rig.t) // the detector's order is not the parts' identity
    val lit = rig.flat.lit(rig.t)!!
    assertEquals(0.40f, lit.center.x, 1e-6f)
    assertEquals(1, lit.index) // by position: −0.40 is 0, +0.40 is 1
    val parts = rig.events().first()["parts"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf(0, 1), parts.map { it["index"]!!.jsonPrimitive.int })
    // the assembly moves next to the other one: it changes only when that one is clearly (10 cm) nearer
    rig.t = 666
    rig.flat.update(pass(left, right), floor, null, Vec3(-0.02f, 0f, -0.5f), rig.t)
    assertEquals(1, rig.flat.lit(rig.t)!!.index)
    rig.t = 1000
    rig.flat.update(pass(left, right), floor, null, Vec3(-0.3f, 0f, -0.5f), rig.t)
    assertEquals(0, rig.flat.lit(rig.t)!!.index)
    // the second side's action: the same kind, so one of them is lit again
    rig.flat.setAction(1, 2, "part_drawer_side_right_1", "drawer_side_right")
    rig.t = 1333
    rig.flat.update(pass(left, right), floor, null, Vec3(0.5f, 0f, -0.5f), rig.t)
    assertEquals("drawer_side_right", rig.flat.lit(rig.t)!!.partId)
    assertEquals(1, rig.flat.lit(rig.t)!!.index)
    // a third rectangle of the sides' size: one of the three is not a side, so none is lit
    val third = rect(0.0, 0.10, 45.0, 0.452, 0.098)
    for (i in 0 until 2) {
      rig.t += 333
      rig.flat.update(pass(left, right, third), floor, null, assemblyAt, rig.t)
    }
    assertNull(rig.flat.lit(rig.t))
  }

  @Test
  fun aRectangleOnTheAssemblysFootprintIsTheAssembly() {
    val rig = Rig(real)
    rig.flat.setAction(1, 1, "part_drawer_side_left_1", "drawer_side_left")
    // the front lies at the anchor: 400 × 200 round the WORLD origin
    val assembly = listOf(Vec3(-0.2f, 0f, -0.1f), Vec3(-0.2f, 0f, 0.1f), Vec3(0.2f, 0f, 0.1f), Vec3(0.2f, 0f, -0.1f))
    val onIt = rect(0.05, 0.02, 15.0, 0.440, 0.105) // what the half-built assembly may look like from above
    val loose = rect(0.0, 0.50, 0.0, 0.440, 0.105)
    for (i in 0 until 3) {
      rig.t = i * 333L
      rig.flat.update(pass(onIt), floor, assembly, Vec3.ZERO, rig.t)
    }
    assertNull(rig.flat.lit(rig.t))
    val e = rig.events().first()
    assertEquals(1, e["rects"]!!.jsonPrimitive.int)
    assertEquals(1, e["on_assembly"]!!.jsonPrimitive.int)
    assertTrue(e["parts"]!!.jsonArray.isEmpty())
    for (i in 3 until 5) {
      rig.t = i * 333L
      rig.flat.update(pass(onIt, loose), floor, assembly, Vec3.ZERO, rig.t)
    }
    assertEquals(-0.5f, rig.flat.lit(rig.t)!!.center.z, 1e-6f)
    // the ghost moves onto a rectangle that was followed as loose: it is the assembly from then on
    val moved = assembly.map { it + Vec3(0f, 0f, -0.5f) }
    rig.t += 333
    rig.flat.update(pass(), floor, moved, Vec3(0f, 0f, -0.5f), rig.t)
    assertNull(rig.flat.lit(rig.t))
    // overlap is tested on the ground, corners or not
    assertTrue(FlatMatch.overlaps(assembly, listOf(Vec3(-0.05f, 0f, -0.5f), Vec3(0.05f, 0f, -0.5f), Vec3(0.05f, 0f, 0.5f), Vec3(-0.05f, 0f, 0.5f))))
    assertTrue(!FlatMatch.overlaps(assembly, listOf(Vec3(0.21f, 0f, -0.1f), Vec3(0.5f, 0f, -0.1f), Vec3(0.5f, 0f, 0.1f), Vec3(0.21f, 0f, 0.1f))))
  }

  @Test
  fun aBoardIsLitOnlyForItsOwnPlaceAction() {
    val rig = Rig(real)
    val front = rect(0.2, 0.6, 5.0, 0.395, 0.205)
    rig.flat.setAction(0, 0, "part_drawer_front_1", "drawer_front")
    for (i in 0 until 2) {
      rig.t = i * 333L
      rig.flat.update(pass(front), floor, null, null, rig.t)
    }
    assertEquals("drawer_front", rig.flat.lit(rig.t)!!.partId)
    // a fasten action (hardware), a turn: nothing is lit
    rig.flat.setAction(0, 2, null, "118331")
    assertNull(rig.flat.lit(rig.t))
    // the side's action: the front is still seen and still known, but it is not the part to pick
    rig.flat.setAction(1, 1, "part_drawer_side_left_1", "drawer_side_left")
    rig.t = 666
    rig.flat.update(pass(front), floor, null, null, rig.t)
    assertNull(rig.flat.lit(rig.t))
    rig.t = 1000
    rig.flat.update(pass(front), floor, null, null, rig.t)
    val e = rig.events().last()
    assertEquals("drawer_side_left", e["needed"]!!.jsonPrimitive.content)
    assertEquals("drawer_front", e["parts"]!!.jsonArray.single().jsonObject["part"]!!.jsonPrimitive.content)
    assertTrue(e["lit"] is kotlinx.serialization.json.JsonNull)
  }

  @Test
  fun cornersGoFromTheTableFrameToWorld() {
    val table = PanelFrames.tableAtHeight(0.74f)
    val r = rect(0.10, 0.40, 30.0, 0.450, 0.100)
    val cs = PanelFrames.cornersWorld(table, r)
    assertEquals(4, cs.size)
    for (c in cs) assertEquals(0.756f, c.y, 1e-6f) // the top face, 16 mm above the surface
    val mid = cs.fold(Vec3.ZERO) { a, b -> a + b } * 0.25f
    assertEquals(0.10f, mid.x, 1e-5f)
    assertEquals(-0.40f, mid.z, 1e-5f) // TABLE +Y is WORLD −Z
    assertEquals(0.450f, (cs[1] - cs[0]).length(), 1e-5f)
    assertEquals(0.100f, (cs[2] - cs[1]).length(), 1e-5f)
    assertEquals(0.450f, (cs[3] - cs[2]).length(), 1e-5f)
    // the long side points 30° from TABLE +X towards TABLE +Y = WORLD −Z
    val along = cs[1] - cs[0]
    assertEquals(0.450 * Math.cos(Math.toRadians(30.0)), along.x.toDouble(), 1e-5)
    assertEquals(-0.450 * Math.sin(Math.toRadians(30.0)), along.z.toDouble(), 1e-5)
    // and the outline drawn from the track is the same four points
    val rig = Rig(real)
    rig.flat.setAction(1, 1, "part_drawer_side_left_1", "drawer_side_left")
    for (i in 0 until 2) rig.flat.update(pass(r), table, null, null, i * 333L)
    val lit = rig.flat.lit(333)!!
    for (c in cs) assertTrue("corner $c", lit.corners.any { (it - c).length() < 1e-3f })
    val edges = FlatOutlineLayout.edges(lit.corners)
    assertEquals(listOf(0.45f, 0.10f, 0.45f, 0.10f), edges.map { Math.round(it.lengthM * 1000) / 1000f })
    for ((i, e) in edges.withIndex()) {
      assertEquals(0f, (e.pose.t - (lit.corners[i] + Vec3(0f, FlatOutlineLayout.LIFT_M, 0f))).length(), 1e-6f)
      // the tube (a cylinder along +Y) runs to the next corner
      val end = e.pose.transform(Vec3(0f, e.lengthM, 0f))
      assertEquals(0f, (end - (lit.corners[(i + 1) % 4] + Vec3(0f, FlatOutlineLayout.LIFT_M, 0f))).length(), 1e-4f)
    }
    assertEquals(1f, FlatOutlineLayout.pulse(0.7f, reducedMotion = true), 0f)
    for (i in 0 until 50) assertTrue(FlatOutlineLayout.pulse(i * 0.1f, false) in FlatOutlineLayout.PULSE_LOW..1f)
  }

  private fun target(id: String, instance: Int = 0, key: Int = 7) =
      PickTarget(id, "Side", Vec3(1f, 0f, 1f), 0.8, 100, "part", "plane", key = key, instance = instance, name = mapOf("el" to "Πλαϊνό"),
          countText = mapOf("el" to "φαίνονται 2 από 2"), countSeen = 2, countNeeded = 2, box = listOf(100.0, 100.0, 200.0, 300.0))

  @Test
  fun theLitRectangleStandsInForTheServersBoxesOfThatPart() {
    val rig = Rig(real)
    val server = listOf(target("drawer_side_left"), target("drawer_side_left", 1, 8), target("101345", key = 9))
    rig.flat.setAction(1, 1, "part_drawer_side_left_1", "drawer_side_left")
    assertSame(server, rig.flat.merge(server, null, 0)) // nothing recognised: as before
    for (i in 0 until 2) {
      rig.t = i * 333L
      rig.flat.update(pass(rect(0.3, 0.5, 0.0, 0.440, 0.104)), floor, null, null, rig.t)
    }
    val merged = rig.flat.merge(server, null, rig.t + 100)
    assertEquals(listOf("drawer_side_left", "101345"), merged.map { it.id })
    val t = merged[0]
    assertEquals(FlatParts.METHOD, t.method)
    assertEquals(0f, (t.worldPos - Vec3(0.3f, 0.016f, -0.5f)).length(), 1e-5f) // the rectangle's centre, on its top face
    assertEquals(100L, t.ageMs)
    assertEquals(1.0, t.confidence, 0.0)
    assertTrue(t.current)
    assertEquals(0, t.instance)
    assertTrue(t.key >= FlatParts.KEY_BASE)
    assertEquals("Πλαϊνό · φαίνονται 2 από 2", t.pill("el")) // the server's name and count stay on the label
    assertEquals(4, t.box.size)
    assertSame(server[2], merged[1])
    // without a server answer the name is the guide's
    assertEquals("Αριστερό πλαϊνό", rig.flat.merge(emptyList(), null, rig.t)[0].pill("el"))
    // the base part's targets are hidden once the ghost lies on it: no rectangle target either
    assertSame(server, rig.flat.merge(server, "drawer_side_left", rig.t))
    // older than 2 s: the server's boxes again
    assertSame(server, rig.flat.merge(server, null, rig.t + 2001))
  }

  @Test
  fun logsFlatPartsAtMostOnceASecondWithWhatTuningNeeds() {
    val rig = Rig(real)
    rig.flat.setAction(1, 1, "part_drawer_side_left_1", "drawer_side_left")
    for (i in 0 until 7) {
      rig.t = i * 334L
      rig.flat.update(pass(rect(0.3, 0.5, 0.0, 0.432, 0.108, 0.91, 52.0), rect(-0.3, 0.3, 40.0, 0.380, 0.130, 0.88, 40.0)), floor, null, Vec3.ZERO, rig.t)
    }
    val ev = rig.events()
    assertEquals(3, ev.size) // t = 0, 1002, 2004
    val e = ev[1]
    assertEquals(2, e["rects"]!!.jsonPrimitive.int)
    assertEquals(42.0, e["ms"]!!.jsonPrimitive.double, 0.0)
    assertEquals("drawer_side_left", e["needed"]!!.jsonPrimitive.content)
    val p = e["parts"]!!.jsonArray.single().jsonObject
    assertEquals(sides, p["part"]!!.jsonPrimitive.content)
    assertEquals(432, p["length_mm"]!!.jsonPrimitive.int)
    assertEquals(108, p["width_mm"]!!.jsonPrimitive.int)
    assertEquals(0.91, p["rectness"]!!.jsonPrimitive.double, 1e-9)
    assertEquals(52.0, p["contrast"]!!.jsonPrimitive.double, 1e-9)
    assertEquals(4, p["n"]!!.jsonPrimitive.int)
    val u = e["unknown"]!!.jsonArray.single().jsonObject
    assertEquals(380, u["length_mm"]!!.jsonPrimitive.int)
    assertEquals(130, u["width_mm"]!!.jsonPrimitive.int)
    assertEquals("drawer_back", u["nearest"]!!.jsonPrimitive.content)
    assertEquals(1.2, u["tolerances_away"]!!.jsonPrimitive.double, 1e-9)
    val lit = e["lit"]!!.jsonObject
    assertEquals("drawer_side_left", lit["part"]!!.jsonPrimitive.content)
    assertTrue(abs(lit["pos_m"]!!.jsonArray[0].jsonPrimitive.double - 0.3) < 1e-6)
    // a pass that did not run says why, and lights nothing new
    rig.t = 3100
    rig.flat.update(RectsResult(emptyList(), "no table", 0.0), null, null, null, rig.t)
    assertEquals("no table", rig.events().last()["reason"]!!.jsonPrimitive.content)
  }

  // ---- the guide's boards (the committed malm-drawer job: the old model, front 380 × 130) ----

  private fun malm(): Assembly {
    fun res(name: String) = File(javaClass.classLoader!!.getResource("malm-drawer/$name")!!.toURI())
    val m = Manifest.parse(res("manifest.json").readText())
    return Assembly("malm-drawer", m, null, guide = Guide.build(m, Glb.read(res("walkthrough.glb"))))
  }

  @Test
  fun boardsAndTheirSizesComeFromTheGuide() {
    val a = malm()
    val boards = FlatBoards.of(a)
    for (b in boards) println("board %s (%s): %.0f x %.0f x %.0f mm, added at %s, names %s".format(b.partId, b.node, b.lengthM * 1000, b.widthM * 1000, b.thickM * 1000, b.addedAt, b.names))
    val ids = boards.map { it.partId }
    assertTrue("$ids", ids.containsAll(listOf("drawer_front", "drawer_side_left", "drawer_side_right", "drawer_back", "drawer_bottom")))
    assertTrue("runners are not boards: $ids", ids.none { it.contains("runner") })
    val front = boards.first { it.partId == "drawer_front" }
    assertEquals(0.380, front.lengthM, 1e-3)
    assertEquals(0.130, front.widthM, 1e-3)
    assertEquals(0.016, front.thickM, 1e-3)
    assertEquals(0, front.addedAt) // step 1, action 0
    assertTrue(front.names["en"]!!.isNotEmpty())
    // the two sides are one kind
    val kinds = FlatMatch.kinds(boards)
    assertTrue(kinds.map { it.name }.toString(), kinds.any { it.name == "drawer_side_left+drawer_side_right" })
    // the loaded guide's own front is recognised at its own size, whatever that is
    assertEquals("drawer_front", FlatMatch.classify(front.lengthM - 0.010, front.widthM + 0.008, kinds)?.name)
  }

  @Test
  fun theAssemblysFootprintIsWhatWasPlacedBeforeTheActionShownNow() {
    val a = malm()
    val g = a.guide!!
    val lines = ArrayList<String>()
    val flat = FlatParts(AgqLog({ 0L }, { lines += it }), { 0L })
    flat.setAssembly(a)
    assertTrue(flat.wanted)
    assertTrue(lines.single().contains("\"boards\""))
    val anchor = Pose3(Vec3(1f, 0.74f, -2f))
    // step 1, action 0 places the front: nothing stands at the anchor yet
    flat.setAction(1, 0, g.step(1)!!.actions[0])
    assertNull(flat.assemblyFootprint(anchor, g.step(1)!!.tableFromAssembly))
    assertNull(flat.assemblyFootprint(null, g.step(1)!!.tableFromAssembly))
    // step 2, the left side: the front lies there, 380 × 130 grown by 3 cm on every side, on the anchor's plane
    val act = g.step(2)!!.actions.first { it.node == "part_drawer_side_left_1" }
    flat.setAction(2, act.index, act)
    val fp = flat.assemblyFootprint(anchor, g.step(2)!!.tableFromAssembly)!!
    val xs = fp.map { it.x }
    val zs = fp.map { it.z }
    val sizes = listOf(xs.max() - xs.min(), zs.max() - zs.min()).sorted()
    assertEquals(0.190f, sizes[0], 2e-3f)
    assertEquals(0.440f, sizes[1], 2e-3f)
    for (c in fp) assertEquals(0.74f, c.y, 1e-5f)
    // a loose side is lit at step 2 …
    val floor = PanelFrames.tableAtHeight(0.74f)
    val side = boards(a).first { it.partId == "drawer_side_left" }
    val loose = rect(1.0, 1.2, 0.0, side.lengthM, side.widthM)
    for (i in 0 until 2) flat.onRects(pass(loose), floor, anchor, g.step(2)!!.tableFromAssembly, i * 333L)
    assertEquals("drawer_side_left", flat.lit(333)!!.partId)
    // … and a rectangle where the front lies (the anchor) is not
    val mid = fp.fold(Vec3.ZERO) { p, q -> p + q } * 0.25f
    val onIt = rect(mid.x.toDouble(), -mid.z.toDouble(), 0.0, side.lengthM, side.widthM)
    val flat2 = FlatParts(AgqLog({ 0L }, { }), { 0L })
    flat2.setAssembly(a)
    flat2.setAction(2, act.index, act)
    for (i in 0 until 2) flat2.onRects(pass(onIt), floor, anchor, g.step(2)!!.tableFromAssembly, i * 333L)
    assertNull(flat2.lit(333))
  }

  private fun boards(a: Assembly) = FlatBoards.of(a)
}
