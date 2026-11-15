package com.agj.android.world

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.net.PartsFound
import com.agj.android.net.PartsResult
import com.agj.android.vision.FrameGrab
import com.agj.android.vision.Frames
import com.agj.android.vision.Intrinsics
import java.util.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ray/plane math, box → pixel mapping, table choice and placement, the parts loop (world package). */
class WorldTest {
  private val k = Intrinsics(640.0, 640.0, 639.5, 479.5, 1280, 960)

  private fun near(a: Vec3, b: Vec3, eps: Float) = assertTrue("$a != $b (eps $eps)", (a - b).length() < eps)

  /** WORLD ← CAMERA (OpenCV axes: +X right, +Y down, +Z forward) for a camera at [eye] looking at [target]. */
  private fun lookAt(eye: Vec3, target: Vec3): Pose3 {
    val f = (target - eye).let { it * (1f / it.length()) }
    val r = f.cross(Vec3(0f, 1f, 0f)).let { it * (1f / it.length()) }
    val d = f.cross(r)
    val m = arrayOf(
        doubleArrayOf(r.x.toDouble(), d.x.toDouble(), f.x.toDouble()),
        doubleArrayOf(r.y.toDouble(), d.y.toDouble(), f.y.toDouble()),
        doubleArrayOf(r.z.toDouble(), d.z.toDouble(), f.z.toDouble()),
    )
    return Pose3(eye, Frames.matToQuat(m))
  }

  // ---- box → pixel ----

  @Test
  fun boxMapsToPixelsInGeminiOrder() {
    // [ymin, xmin, ymax, xmax] 0–1000 over a 1280 × 960 picture
    val px = PickRays.boxToPx(listOf(100.0, 200.0, 300.0, 600.0), 1280, 960)!!
    assertEquals(255.5, px[0], 1e-9) // xmin 0.2 × 1280 − 0.5
    assertEquals(95.5, px[1], 1e-9) // ymin 0.1 × 960 − 0.5
    assertEquals(767.5, px[2], 1e-9)
    assertEquals(287.5, px[3], 1e-9)
    val c = PickRays.boxCenterPx(listOf(100.0, 200.0, 300.0, 600.0), 1280, 960)!!
    assertEquals(511.5, c.first, 1e-9)
    assertEquals(191.5, c.second, 1e-9)
    // the picture centre is the principal point of a centred camera
    val mid = PickRays.boxCenterPx(listOf(400.0, 400.0, 600.0, 600.0), 1280, 960)!!
    assertEquals(k.cx, mid.first, 1e-9)
    assertEquals(k.cy, mid.second, 1e-9)
    // swapped corners are ordered, out-of-range clamped, malformed rejected
    assertEquals(511.5, PickRays.boxCenterPx(listOf(300.0, 600.0, 100.0, 200.0), 1280, 960)!!.first, 1e-9)
    assertEquals(1279.5, PickRays.boxToPx(listOf(0.0, 900.0, 10.0, 1200.0), 1280, 960)!![2], 1e-9)
    assertNull(PickRays.boxToPx(listOf(1.0, 2.0, 3.0), 1280, 960))
    assertNull(PickRays.boxToPx(listOf(100.0, 200.0, 100.0, 600.0), 1280, 960))
    assertNull(PickRays.boxToPx(listOf(100.0, Double.NaN, 300.0, 600.0), 1280, 960))
    // the same normalised box on the downscaled picture and on the full image gives the same ray direction
    val small = k.scaledTo(768, 576)
    val a = PickRays.boxCenterPx(listOf(100.0, 200.0, 300.0, 600.0), 768, 576)!!
    val da = PickRays.pixelToCameraDir(a.first, a.second, small)
    val db = PickRays.pixelToCameraDir(c.first, c.second, k)
    near(da, db, 2e-3f)
  }

  @Test
  fun cameraAxesAreOpenCv() {
    val d = PickRays.pixelToCameraDir(k.cx, k.cy, k)
    near(d, Vec3(0f, 0f, 1f), 1e-6f)
    val right = PickRays.pixelToCameraDir(k.cx + 100, k.cy, k)
    assertTrue(right.x > 0f)
    val down = PickRays.pixelToCameraDir(k.cx, k.cy + 100, k)
    assertTrue(down.y > 0f)
    // a camera looking ahead and down at a table: lower in the picture = nearer on the table
    val cam = lookAt(Vec3(0f, 1.5f, 0f), Vec3(0f, 0.75f, -0.6f))
    val nearHit = PickRays.intersectHorizontal(PickRays.worldRay(cam, k.cx, 900.0, k), 0.75f)!!
    val farHit = PickRays.intersectHorizontal(PickRays.worldRay(cam, k.cx, 100.0, k), 0.75f)!!
    assertTrue("bottom of the picture is nearer", -nearHit.z < -farHit.z)
    val rightHit = PickRays.intersectHorizontal(PickRays.worldRay(cam, 1200.0, k.cy, k), 0.75f)!!
    assertTrue("right of the picture is +X when looking −Z", rightHit.x > 0.05f)
  }

  // ---- rays and planes ----

  @Test
  fun rayPlaneIntersection() {
    val r = Ray(Vec3(0f, 1f, 0f), Vec3(0f, -1f, 0f))
    near(PickRays.intersectHorizontal(r, 0.75f)!!, Vec3(0f, 0.75f, 0f), 1e-6f)
    assertNull("behind the origin", PickRays.intersectHorizontal(r, 1.5f))
    assertNull("parallel", PickRays.intersectHorizontal(Ray(Vec3(0f, 1f, 0f), Vec3(1f, 0f, 0f)), 0.75f))
    assertNull("too far", PickRays.intersectHorizontal(r, -20f, maxM = 10f))
    val tilted = Vec3(1f, -1f, 0f).let { it * (1f / it.length()) }
    val p = PickRays.intersectPlane(Ray(Vec3(0f, 1f, 0f), tilted), Vec3(0f, 0f, 0f), Vec3(0f, 1f, 0f))!!
    near(p, Vec3(1f, 0f, 0f), 1e-5f)
    // an inclined plane
    val n = Vec3(0f, 1f, 1f).let { it * (1f / it.length()) }
    val q = PickRays.intersectPlane(Ray(Vec3(0f, 2f, 0f), Vec3(0f, -1f, 0f)), Vec3(0f, 0f, 0f), n)!!
    assertEquals(0f, q.dot(n), 1e-5f)
  }

  @Test
  fun projectThenRayHitsTheSamePointOnTheTable() {
    val r = Random(7)
    repeat(200) {
      val eye = Vec3(r.nextFloat() - 0.5f, 1.3f + r.nextFloat() * 0.4f, 0.2f)
      val target = Vec3(r.nextFloat() - 0.5f, 0.75f, -0.3f - r.nextFloat() * 0.5f)
      val cam = lookAt(eye, target + Vec3(0.05f, 0f, 0f))
      for (dist in listOf(null, listOf(0.08, -0.05, 0.001, -0.0005, 0.01))) {
        val kk = k.copy(distortion = dist)
        val (u, v) = PickRays.project(cam, kk, target, useDistortion = dist != null)!!
        val ray = PickRays.worldRay(cam, u, v, kk, useDistortion = dist != null)
        near(PickRays.intersectHorizontal(ray, 0.75f)!!, target, 1e-3f)
      }
    }
  }

  @Test
  fun undistortInvertsDistort() {
    val d = listOf(0.12, -0.07, 0.002, -0.001, 0.02)
    for (x in listOf(-0.6, -0.2, 0.0, 0.3, 0.55)) for (y in listOf(-0.4, 0.0, 0.25)) {
      val (xd, yd) = PickRays.distort(x, y, d)
      val (xu, yu) = PickRays.undistort(xd, yd, d)
      assertEquals(x, xu, 1e-7)
      assertEquals(y, yu, 1e-7)
    }
    assertEquals(0.3 to 0.2, PickRays.undistort(0.3, 0.2, null))
  }

  @Test
  fun boxOnTheTableLocatesThePart() {
    val cam = lookAt(Vec3(0.1f, 1.45f, 0.1f), Vec3(0f, 0.75f, -0.5f))
    val part = Vec3(0.2f, 0.75f, -0.45f)
    val (u, v) = PickRays.project(cam, k, part)!!
    // a 60 × 40 px box around it, normalised the Gemini way
    val box = listOf((v - 20 + 0.5) / 960 * 1000, (u - 30 + 0.5) / 1280 * 1000, (v + 20 + 0.5) / 960 * 1000,
        (u + 30 + 0.5) / 1280 * 1000)
    val ray = PickRays.boxRay(cam, box, k)!!
    val table = TableSurface("t", "TABLE", Vec3(0f, 0.75f, -0.5f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f), 0.6f, 0.4f, 0.75f, "plane")
    val (p, m) = PartsFinder.locate(ray, null, PickSurfaces(table, 0f, null))!!
    assertEquals("table", m)
    near(p, part, 1e-3f)
    // a plausible depth hit wins; an implausible one (too near) falls through
    assertEquals("depth", PartsFinder.locate(ray, part + Vec3(0f, 0.02f, 0f), PickSurfaces(table))!!.second)
    assertEquals("table", PartsFinder.locate(ray, cam.t + ray.dir * 0.05f, PickSurfaces(table))!!.second)
    // off the table → the floor; without a scene → the work-surface plane
    val off = Vec3(1.5f, 0f, -1.2f)
    val (u2, v2) = PickRays.project(cam, k, off)!!
    val ray2 = PickRays.worldRay(cam, u2, v2, k)
    val floor = PartsFinder.locate(ray2, null, PickSurfaces(table, 0f, null))!!
    assertEquals("floor", floor.second)
    near(floor.first, off, 1e-3f)
    assertEquals("plane", PartsFinder.locate(ray, null, PickSurfaces(null, null, 0.75f))!!.second)
    assertNull(PartsFinder.locate(ray, null, PickSurfaces()))
  }

  // ---- table ----

  private val head = Pose3(Vec3(0f, 1.6f, 0f), Quat.IDENTITY) // looking −Z

  private fun rectCorners(c: Vec3, w: Float, d: Float, yawRad: Float = 0f): List<Vec3> {
    val q = Quat.yaw(yawRad)
    return listOf(Vec3(-w / 2, 0f, -d / 2), Vec3(w / 2, 0f, -d / 2), Vec3(w / 2, 0f, d / 2), Vec3(-w / 2, 0f, d / 2)).map {
      c + q.rotate(it)
    }
  }

  @Test
  fun rectFromCornersInAnyOrder() {
    val cs = rectCorners(Vec3(0.3f, 0.74f, -0.8f), 1.2f, 0.6f, 0.4f)
    val t = TableFinder.rect(SceneSurface("a", setOf("TABLE"), listOf(cs[2], cs[0], cs[3], cs[1]), "plane"), 0f)!!
    near(t.center, Vec3(0.3f, 0.74f, -0.8f), 1e-5f)
    assertEquals(1.2f, t.sizeM.first, 1e-4f)
    assertEquals(0.6f, t.sizeM.second, 1e-4f)
    assertEquals(0.74f, t.heightM, 1e-5f)
    assertEquals(0f, t.u.dot(t.v), 1e-5f)
    assertTrue(t.contains(Vec3(0.3f, 0f, -0.8f)))
    assertFalse(t.contains(Vec3(1.5f, 0.74f, -0.8f)))
    // not level → not a table
    val tilted = cs.mapIndexed { i, p -> if (i == 0) p + Vec3(0f, 0.2f, 0f) else p }
    assertNull(TableFinder.rect(SceneSurface("b", setOf("TABLE"), tilted, "plane"), 0f))
  }

  @Test
  fun choosesTheTableInFrontThenALevelSurfaceThenNothing() {
    val front = SceneSurface("front", setOf("TABLE"), rectCorners(Vec3(0f, 0.75f, -0.9f), 1.2f, 0.6f), "plane")
    val behind = SceneSurface("behind", setOf("TABLE"), rectCorners(Vec3(0f, 0.75f, 1.2f), 1.2f, 0.6f), "plane")
    val floor = SceneSurface("floor", setOf("FLOOR"), rectCorners(Vec3(0f, 0f, 0f), 4f, 4f), "plane")
    val desk = SceneSurface("desk", setOf("OTHER"), rectCorners(Vec3(0.4f, 0.8f, -1.0f), 1.4f, 0.7f), "volume")
    val shelf = SceneSurface("shelf", setOf("STORAGE"), rectCorners(Vec3(-0.4f, 1.5f, -1.0f), 1.6f, 0.4f), "volume")
    assertEquals("front", TableFinder.choose(listOf(behind, floor, front, desk), head, 0f).table?.id)
    // no TABLE in front: the level surface at 0.6–1.0 m in front, not the floor or the high shelf
    val c = TableFinder.choose(listOf(behind, floor, desk, shelf), head, 0f)
    assertEquals("desk", c.table?.id)
    assertEquals("level_surface_in_front", c.reason)
    // only the table behind: still used (nearby)
    assertEquals("table_nearby", TableFinder.choose(listOf(behind, floor), head, 0f).reason)
    // nothing level besides the floor
    val none = TableFinder.choose(listOf(floor, shelf), head, 0f)
    assertNull(none.table)
    assertEquals("none_in_front", none.reason)
    assertEquals("no_surfaces", TableFinder.choose(emptyList(), head, 0f).reason)
    // the floor height matters: a 0.75 m TABLE on a floor at y = −1.0 is 1.75 m high → not a table
    assertNull(TableFinder.choose(listOf(front), head, -1.0f).table)
  }

  @Test
  fun placesTheFootprintOnTheTableFacingTheUser() {
    val t = TableFinder.rect(SceneSurface("t", setOf("TABLE"), rectCorners(Vec3(0.2f, 0.75f, -0.8f), 1.2f, 0.6f), "plane"), 0f)!!
    val p = TableFinder.place(t, head, 0.4f, 0.3f)
    assertEquals(0.75f, p.t.y, 1e-5f)
    // inside the table with the whole footprint
    val front = p.q.rotate(Vec3(0f, 0f, 1f))
    val side = p.q.rotate(Vec3(1f, 0f, 0f))
    for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) {
      assertTrue(t.contains(p.t + side * (0.2f * sx) + front * (0.15f * sz)))
    }
    // the assembly front (+Z) faces the user
    val toHead = Vec3(head.t.x - p.t.x, 0f, head.t.z - p.t.z).let { it * (1f / it.length()) }
    assertTrue(front.dot(toHead) > 0.99f)
    // straight ahead of the user (x = 0), clamped into the table depth (the near edge is at z = −0.5)
    assertEquals(0f, p.t.x, 0.02f)
    assertTrue(p.t.z <= -0.5f - 0.15f + 1e-3f)
    // a table smaller than the footprint: its centre
    val small = TableFinder.rect(SceneSurface("s", setOf("TABLE"), rectCorners(Vec3(0.5f, 0.7f, -0.9f), 0.3f, 0.3f), "plane"), 0f)!!
    val ps = TableFinder.place(small, head, 0.5f, 0.5f)
    near(Vec3(ps.t.x, 0f, ps.t.z), Vec3(0.5f, 0f, -0.9f), 1e-4f)
    // same frame as the fixed spot: yaw formula matches Placement.fixedTableSpot for a head looking −Z
    val turned = Pose3(Vec3(0f, 1.6f, 0f), Quat.yaw(0.5f))
    val pt = TableFinder.place(t, turned, 0.4f, 0.3f)
    val f2 = pt.q.rotate(Vec3(0f, 0f, 1f))
    val to2 = Vec3(turned.t.x - pt.t.x, 0f, turned.t.z - pt.t.z).let { it * (1f / it.length()) }
    assertTrue(f2.dot(to2) > 0.99f)
  }

  // ---- the parts loop ----

  private class Fake {
    var now = 1_000_000L
    val lines = ArrayList<String>()
    val log = AgqLog({ now }, { lines += it }, wallMs = { 0L })
    val queue = ArrayList<Runnable>()
    var grabs = 0
    val wants = ArrayList<List<String>>()
    var answer: (List<String>) -> PartsResult = { PartsResult() }

    fun drain() {
      while (queue.isNotEmpty()) queue.removeAt(0).run()
    }

    fun parts() =
        lines.map { Json.parseToJsonElement(it).jsonObject }.filter { it["ev"]?.jsonPrimitive?.content == "parts" }
  }

  @Test
  fun partsLoopAsksEveryFiveSecondsAndPlacesTargets() {
    val f = Fake()
    val cam = lookAt(Vec3(0f, 1.45f, 0.1f), Vec3(0f, 0.75f, -0.5f))
    val part = Vec3(-0.15f, 0.75f, -0.4f)
    val (u, v) = PickRays.project(cam, k, part)!!
    val box = listOf((v + 0.5) / 960 * 1000 - 10, (u + 0.5) / 1280 * 1000 - 10, (v + 0.5) / 960 * 1000 + 10,
        (u + 0.5) / 1280 * 1000 + 10)
    f.answer = { want ->
      PartsResult(found = listOf(PartsFound("side_left", "part", box, 0.9, "Left side")), missing = want - "side_left",
          latency_ms = 4100)
    }
    val table = TableSurface("t", "TABLE", Vec3(0f, 0.75f, -0.5f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f), 0.6f, 0.4f, 0.75f, "plane")
    val pf =
        PartsFinder(
            grab = { cb ->
              f.grabs++
              f.queue += Runnable { cb(FrameGrab(ByteArray(10), 1280, 960, k, cam, 0L, f.now)) }
            },
            query = { _, _, want, _ ->
              f.wants += want
              f.answer(want)
            },
            surfaces = { PickSurfaces(table, 0f, null) },
            submit = { f.queue += it },
            log = f.log,
            clockMs = { f.now },
        )
    assertFalse("nothing to look for yet", pf.tick())
    pf.setAction("malm", 2, 0, isPick = true, want = listOf("side_left", "120076", "side_left"))
    assertTrue(pf.tick())
    assertFalse("one request at a time", pf.tick())
    f.drain()
    assertEquals(listOf("side_left", "120076"), f.wants.single())
    val t = pf.pickTargets.single()
    assertEquals("side_left", t.id)
    assertEquals("Left side", t.label)
    assertEquals("table", t.method)
    near(t.worldPos, part, 2e-3f)
    // footprint from the box: ~20 units of 1280 px × distance / fx, half of it
    val dist = (part - cam.t).length()
    assertEquals((0.020 * 1280 * dist / k.fx / 2).toFloat().coerceAtLeast(0.02f), t.radiusM, 2e-3f)
    assertEquals(box, t.box)
    f.now += 1500
    assertEquals(1500L, pf.pickTargets.single().ageMs)
    // not again before 5 s
    assertFalse(pf.tick())
    f.now += 3600
    assertTrue(pf.tick())
    // now the server no longer sees it: the target goes
    f.answer = { want -> PartsResult(missing = want) }
    f.drain()
    assertTrue(pf.pickTargets.isEmpty())
    val logs = f.parts()
    assertEquals(2, logs.size)
    val first = logs[0]
    assertEquals("true", first["ok"].toString())
    assertEquals(1, first["targets"]!!.jsonPrimitive.content.toInt())
    assertEquals("[\"120076\"]", first["missing"].toString())
    assertEquals("table", first["found"]!!.jsonArray[0].jsonObject["method"]!!.jsonPrimitive.content)
    assertNotNull(first["ms"])
    // a turn / note action stops it and clears the targets
    pf.setAction("malm", 3, 0, isPick = false, want = listOf("x"))
    assertFalse(pf.active)
    f.now += 10_000
    assertFalse(pf.tick())
  }

  @Test
  fun partsLoopDropsAnswersForAnOldActionAndLogsErrors() {
    val f = Fake()
    val cam = lookAt(Vec3(0f, 1.45f, 0.1f), Vec3(0f, 0.75f, -0.5f))
    var fail = false
    val pf =
        PartsFinder(
            grab = { cb -> f.queue += Runnable { cb(FrameGrab(ByteArray(1), 1280, 960, k, cam, 0L, f.now)) } },
            query = { _, _, _, _ ->
              if (fail) throw java.io.IOException("HTTP 502")
              PartsResult(found = listOf(PartsFound("a", "part", listOf(400.0, 400.0, 600.0, 600.0), 0.8, "A")))
            },
            surfaces = { PickSurfaces(null, null, 0.75f) },
            submit = { f.queue += it },
            log = f.log,
            clockMs = { f.now },
        )
    pf.setAction("j", 1, 0, true, listOf("a"))
    assertTrue(pf.tick())
    pf.setAction("j", 2, 0, true, listOf("b")) // the action changed before the answer
    f.drain()
    assertTrue(pf.pickTargets.isEmpty())
    assertTrue(pf.tick()) // asks at once for the new want
    fail = true
    f.drain()
    val err = f.parts().last()
    assertEquals("false", err["ok"].toString())
    assertTrue(err["error"].toString().contains("502"))
    f.now += 5000
    assertTrue("retries after an error", pf.tick())
  }

  @Test
  fun pickCueSitsOnThePartAndBobs() {
    val t = PickTarget("a", "A", Vec3(0.2f, 0.75f, -0.5f), 0.9, 0, radiusM = 0.05f)
    near(PickCue.ringPose(t).t, Vec3(0.2f, 0.752f, -0.5f), 1e-5f)
    assertEquals(0.07f, PickCue.ringRadius(t), 1e-6f) // 2 cm larger than the footprint
    val ys = (0 until 40).map { PickCue.chevronPose(t, it * 0.05f).t.y }
    assertEquals(0.75f + 0.06f + 0.01f, ys.max(), 1e-3f)
    assertEquals(0.75f + 0.06f - 0.01f, ys.min(), 1e-3f)
    assertEquals(0.81f, PickCue.chevronPose(t, 0.5f, reducedMotion = true).t.y, 1e-6f)
    // the cone (+Y) is turned to point down
    assertEquals(-1f, PickCue.chevronPose(t, 0f).q.rotate(Vec3(0f, 1f, 0f)).y, 1e-5f)
    assertEquals(1f, PickCue.opacity(t), 0f)
    assertEquals(0.5f, PickCue.opacity(t.copy(ageMs = 13_500)), 1e-6f)
    assertEquals(0f, PickCue.opacity(t.copy(ageMs = 16_000)), 0f)
  }

  @Test
  fun stuckRequestIsAbandoned() {
    val f = Fake()
    val pf =
        PartsFinder(
            grab = { _ -> f.grabs++ }, // the camera never answers
            query = { _, _, _, _ -> PartsResult() },
            surfaces = { PickSurfaces() },
            submit = { f.queue += it },
            log = f.log,
            clockMs = { f.now },
        )
    pf.setAction("j", 1, 0, true, listOf("a"))
    assertTrue(pf.tick())
    f.now += 10_000
    assertFalse(pf.tick())
    f.now += 25_000
    assertTrue(pf.tick())
    assertEquals(2, f.grabs)
  }
}
