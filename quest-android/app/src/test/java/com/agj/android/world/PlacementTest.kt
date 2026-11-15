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
import com.agj.android.vision.MarkerConfig
import com.agj.android.vision.TrackResult
import com.agj.android.vision.TrackState
import com.agj.android.vision.TrackingCore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The no-marker registration path: anchor policy, placement from a server box, outline, tracker hand-over. */
class PlacementTest {
  private val k = Intrinsics(640.0, 640.0, 639.5, 479.5, 1280, 960)

  private fun near(a: Vec3, b: Vec3, eps: Float) = assertTrue("$a != $b (eps $eps)", (a - b).length() < eps)

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

  private class Rec {
    var now = 100_000L
    val lines = ArrayList<String>()
    val log = AgqLog({ now }, { lines += it }, wallMs = { 0L })

    fun places() = lines.map { Json.parseToJsonElement(it).jsonObject }.filter { it["ev"]!!.jsonPrimitive.content == "place" }
  }

  private fun at(x: Float, z: Float, yaw: Float = 0f) = Pose3(Vec3(x, 0.75f, z), Quat.yaw(yaw))

  // ---- anchor policy ----

  @Test
  fun tableReplacesTheFixedSpotAndTheFirstServerBoxMovesIt() {
    val r = Rec()
    val moves = ArrayList<PlaceSource>()
    val a = AssemblyAnchor(r.log, { r.now }, onChange = { _, s -> moves += s })
    assertFalse("nothing to align before a surface", a.wantsServerBox(Long.MAX_VALUE / 4))
    assertTrue(a.placeInitial(at(0f, -0.5f), PlaceSource.FIXED_SPOT))
    assertTrue(a.placeInitial(at(0f, -0.6f), PlaceSource.TABLE))
    assertFalse("a second table placement would be a jump", a.placeInitial(at(0.3f, -0.6f), PlaceSource.TABLE))
    assertEquals(PlaceSource.TABLE, a.source)
    assertTrue(a.wantsServerBox(Long.MAX_VALUE / 4))
    assertTrue(a.onServerBox(at(0.2f, -0.5f)))
    assertEquals(PlaceSource.SERVER_BOX, a.source)
    assertEquals(listOf(PlaceSource.FIXED_SPOT, PlaceSource.TABLE, PlaceSource.SERVER_BOX), moves)
    val p = r.places()
    assertEquals(listOf("fixed_spot", "table", "server_box"), p.map { it["source"]!!.jsonPrimitive.content })
    assertEquals(listOf("initial", "table_found", "first"), p.map { it["reason"]!!.jsonPrimitive.content })
    assertEquals("table", p[2]["prev"]!!.jsonPrimitive.content)
  }

  @Test
  fun laterServerBoxesMoveItOnlyOnLastingDisagreementOrRealign() {
    val r = Rec()
    val a = AssemblyAnchor(r.log, { r.now })
    a.placeInitial(at(0f, -0.6f), PlaceSource.TABLE)
    a.onServerBox(at(0.2f, -0.5f))
    val placed = a.pose
    // agreeing answers (within 10 cm): never move, and it only checks again every 10 s
    repeat(5) {
      r.now += 5000
      assertFalse(a.onServerBox(at(0.2f + 0.03f * (it % 2), -0.5f)))
    }
    assertEquals(placed, a.pose)
    assertFalse(a.wantsServerBox(3000))
    assertTrue(a.wantsServerBox(11_000))
    // one far answer: remembered, not applied; it asks again soon
    assertFalse(a.onServerBox(at(0.5f, -0.5f)))
    assertTrue(a.wantsServerBox(1000))
    // a different far answer: not consistent → starts over
    r.now += 5000
    assertFalse(a.onServerBox(at(-0.3f, -0.5f)))
    // the same place again, but not yet for 4 s
    r.now += 2000
    assertFalse(a.onServerBox(at(-0.31f, -0.5f)))
    r.now += 2500
    assertTrue(a.onServerBox(at(-0.3f, -0.51f)))
    assertEquals("disagree", r.places().last()["reason"]!!.jsonPrimitive.content)
    // a miss forgets a pending disagreement
    assertFalse(a.onServerBox(at(0.4f, -0.5f)))
    a.onServerMiss()
    r.now += 6000
    assertFalse(a.onServerBox(at(0.4f, -0.5f)))
    // manual wins over the camera until an explicit re-align
    assertTrue(a.nudge(0.05f, 0.10f, 0.2f))
    assertEquals(PlaceSource.MANUAL, a.source)
    assertFalse(a.wantsServerBox(60_000))
    r.now += 6000
    assertFalse(a.onServerBox(at(0.4f, -0.5f)))
    r.now += 6000
    assertFalse(a.onServerBox(at(0.4f, -0.5f)))
    a.requestRealign()
    assertTrue(a.wantsServerBox(0))
    assertTrue(a.onServerBox(at(0.4f, -0.5f)))
    assertEquals(PlaceSource.SERVER_BOX, a.source)
    assertEquals("realign", r.places().last()["reason"]!!.jsonPrimitive.content)
  }

  @Test
  fun nudgeSlidesInTheAnchorsOwnAxesAndTwists() {
    val a = AssemblyAnchor(Rec().log)
    assertFalse(a.nudge(0.1f, 0f, 0f))
    a.placeInitial(at(0f, -0.6f), PlaceSource.TABLE) // yaw 0: +Z (front) toward the user at +z
    a.nudge(0.10f, 0.05f, 0f)
    near(a.pose!!.t, Vec3(0.10f, 0.75f, -0.65f), 1e-5f) // right = +X, forward = away = −Z
    a.nudge(0f, 0f, 0.5f)
    assertEquals(0.5f, AssemblyAnchor.yawOf(a.pose!!.q), 1e-4f)
    assertEquals(0.75f, a.pose!!.t.y, 0f)
  }

  @Test
  fun markerlessPosesAreGatedByConfidenceAndDeadband() {
    val r = Rec()
    var changes = 0
    val a = AssemblyAnchor(r.log, { r.now }, onChange = { _, _ -> changes++ })
    a.placeInitial(at(0f, -0.6f), PlaceSource.TABLE)
    assertFalse(a.onObjectPose(at(0.1f, -0.5f), 0.3f))
    assertTrue(a.onObjectPose(at(0.1f, -0.5f), 0.9f))
    assertEquals(PlaceSource.MARKERLESS, a.source)
    // noise of a few millimetres and under 2°: ignored, so the ghost does not shimmer
    repeat(20) { assertFalse(a.onObjectPose(at(0.1f + 0.004f * (it % 3 - 1), -0.5f, 0.01f * (it % 2)), 0.9f)) }
    assertEquals(2, changes)
    assertTrue(a.onObjectPose(at(0.14f, -0.5f), 0.9f))
    assertTrue(a.onObjectPose(at(0.14f, -0.5f, 0.1f), 0.9f))
    // while it is live the server box stays out
    assertTrue(a.markerlessFresh())
    assertFalse(a.wantsServerBox(60_000))
    assertFalse(a.onServerBox(at(0.5f, -0.5f)))
    r.now += 5000
    assertFalse(a.markerlessFresh())
    assertEquals(1, r.places().count { it["source"]!!.jsonPrimitive.content == "markerless" })
  }

  // ---- placement from a box ----

  /** 380 × 130 mm front lying flat, origin at the middle of one long edge (as the MALM drawer front in step 1). */
  private val front = Footprint(0f, 0.065f, 0.19f, 0.065f)

  private fun boxOf(cam: Pose3, worldFromTable: Pose3, fp: Footprint): List<Double> {
    val px = fp.corners().map { PickRays.project(cam, k, worldFromTable.transform(it))!! }
    val u0 = px.minOf { it.first } + 0.5
    val u1 = px.maxOf { it.first } + 0.5
    val v0 = px.minOf { it.second } + 0.5
    val v1 = px.maxOf { it.second } + 0.5
    return listOf(v0 / 960 * 1000, u0 / 1280 * 1000, v1 / 960 * 1000, u1 / 1280 * 1000)
  }

  private fun grab(cam: Pose3?) = FrameGrab(ByteArray(1), 1280, 960, k, cam, 0L, 0L)

  @Test
  fun footprintFromGeometryInTheStepPose() {
    // a 0.38 × 0.13 × 0.016 board standing in the assembly (XY plane), laid flat by a 90° turn about X
    val pts = ArrayList<Float>()
    for (x in listOf(-0.19f, 0.19f)) for (y in listOf(0f, 0.13f)) for (z in listOf(0f, 0.016f)) pts += listOf(x, y, z)
    val flat = Footprint.of(pts.toFloatArray(), Pose3(Vec3.ZERO, Quat.axisAngle(Vec3(1f, 0f, 0f), (Math.PI / 2).toFloat())))!!
    assertEquals(0.19f, flat.halfX, 1e-5f)
    assertEquals(0.065f, flat.halfZ, 1e-5f)
    assertEquals(0.065f, flat.cz, 1e-5f)
    assertTrue(flat.longAlongX)
    assertEquals(0.19f / 0.065f, flat.aspect, 1e-3f)
    val standing = Footprint.of(pts.toFloatArray(), Pose3.IDENTITY)!!
    assertEquals(0.008f, standing.halfZ, 1e-5f)
    assertNull(Footprint.of(FloatArray(3), Pose3.IDENTITY))
  }

  @Test
  fun boxOfAnAlignedPartGivesPositionAndYaw() {
    val truth = at(0.12f, -0.55f, 0f)
    val cam = lookAt(Vec3(0.1f, 1.5f, -0.15f), Vec3(0.1f, 0.75f, -0.5f))
    val box = boxOf(cam, truth, front)
    val cur = at(0f, -0.4f, 0.12f)
    val p = BasePlacement.fromBox(box, grab(cam), 0.75f, cur, front)!!
    assertTrue(p.yawFromBox)
    assertTrue(AssemblyAnchor.yawDiff(AssemblyAnchor.yawOf(p.worldFromTable.q), 0f) < Math.toRadians(4.0).toFloat())
    near(p.worldFromTable.t, truth.t, 0.03f)
    assertEquals(0.38f, p.lengthM!!, 0.03f)
    // of the two headings 180° apart, the one nearer the current yaw
    val flipped = BasePlacement.fromBox(box, grab(cam), 0.75f, at(0f, -0.4f, 3.0f), front)!!
    assertTrue(AssemblyAnchor.yawDiff(AssemblyAnchor.yawOf(flipped.worldFromTable.q), Math.PI.toFloat()) < 0.07f)
    // the base part's centre is at the same place either way
    near(flipped.partCenter, p.partCenter, 1e-4f)
    near(p.partCenter, truth.transform(Vec3(front.cx, 0f, front.cz)), 0.03f)
    // no camera pose, or a malformed box: no placement
    assertNull(BasePlacement.fromBox(box, grab(null), 0.75f, cur, front))
    assertNull(BasePlacement.fromBox(listOf(1.0, 2.0), grab(cam), 0.75f, cur, front))
  }

  @Test
  fun boxOfATurnedPartKeepsTheCurrentYaw() {
    val truth = at(0.05f, -0.5f, Math.toRadians(35.0).toFloat())
    val cam = lookAt(Vec3(0.1f, 1.5f, -0.15f), Vec3(0.1f, 0.75f, -0.5f))
    val cur = at(0f, -0.4f, 0.12f)
    val p = BasePlacement.fromBox(boxOf(cam, truth, front), grab(cam), 0.75f, cur, front)!!
    assertFalse("the box is not clearly elongated", p.yawFromBox)
    assertEquals(0.12f, AssemblyAnchor.yawOf(p.worldFromTable.q), 1e-4f)
    near(p.partCenter, truth.transform(Vec3(front.cx, 0f, front.cz)), 0.03f)
    // the anchor origin puts the ghost's base part centre on the real part's centre
    near(p.worldFromTable.transform(Vec3(front.cx, 0f, front.cz)), p.partCenter, 1e-4f)
    // a nearly square part never takes its yaw from the box
    val square = Footprint(0f, 0f, 0.2f, 0.18f)
    assertFalse(BasePlacement.fromBox(boxOf(cam, at(0.05f, -0.5f), square), grab(cam), 0.75f, cur, square)!!.yawFromBox)
  }

  @Test
  fun fixedSpotIsInFrontBelowAndFacesTheUser() {
    val head = Pose3(Vec3(0.2f, 1.6f, 0.1f), Quat.yaw(0.7f))
    val p = BasePlacement.fixedSpot(head)
    val f = TableFinder.flatForward(head)
    assertEquals(0.55f, Vec3(p.t.x - head.t.x, 0f, p.t.z - head.t.z).dot(f), 1e-4f)
    assertEquals(1.15f, p.t.y, 1e-5f)
    assertTrue(p.q.rotate(Vec3(0f, 0f, 1f)).dot(f) < -0.99f)
  }

  // ---- outline ----

  @Test
  fun outlineRunsAroundTheFootprintOnTheTable() {
    val w = at(0.3f, -0.6f, 0.4f)
    val es = OutlineLayout.edges(w, front)
    assertEquals(4, es.size)
    val lens = es.map { it.lengthM }.sorted()
    assertEquals(0.13f + 0.02f, lens[0], 1e-4f)
    assertEquals(0.38f + 0.02f, lens[3], 1e-4f)
    for ((i, e) in es.withIndex()) {
      assertEquals(0.753f, e.pose.t.y, 1e-5f)
      // each tube (unit +Y stretched to the length) ends where the next one starts
      val end = e.pose.transform(Vec3(0f, e.lengthM, 0f))
      near(end, es[(i + 1) % 4].pose.t, 1e-4f)
    }
    // the base part's centre is inside
    val c = w.transform(Vec3(front.cx, 0f, front.cz))
    val mid = es.map { it.pose.t }.fold(Vec3.ZERO) { a, b -> a + b } * 0.25f
    near(Vec3(mid.x, 0.75f, mid.z), c, 1e-4f)
  }

  // ---- hand-over to the tracker and the parts loop ----

  @Test
  fun placedPoseIsHeldByTheTrackerWithItsSource() {
    val r = Rec()
    var ns = 1_000_000_000L
    val core = TrackingCore(MarkerConfig(), r.log, logEveryNs = 0L, clockNs = { ns })
    val pose = Pose3(Vec3(0.1f, 0.75f, -0.5f), Quat.yaw(0.3f))
    val s = core.onExternal(pose, ns, "table")
    assertEquals("table", s.method)
    near(s.worldFromAssembly!!.t, pose.t, 1e-5f)
    // camera frames without a marker: the pose stays, state held, source still the placement
    repeat(30) {
      ns += 20_000_000L
      val h = core.onMarker(TrackResult.none(ns))
      assertEquals(TrackState.HELD, h.state)
      assertEquals("table", h.method)
      near(h.worldFromAssembly!!.t, pose.t, 1e-4f)
    }
    val tracks = r.lines.map { Json.parseToJsonElement(it).jsonObject }.filter { it["ev"]!!.jsonPrimitive.content == "track" }
    assertTrue(tracks.size > 10)
    assertEquals("table", tracks.last()["source"]!!.jsonPrimitive.content)
    assertEquals("held", tracks.last()["state"]!!.jsonPrimitive.content)
    // a later source replaces it at once
    val moved = Pose3(Vec3(0.3f, 0.75f, -0.4f), Quat.yaw(0.3f))
    core.onExternal(moved, ns, "server_box")
    ns += 20_000_000L
    val h = core.onMarker(TrackResult.none(ns))
    assertEquals("server_box", h.method)
    near(h.worldFromAssembly!!.t, moved.t, 1e-4f)
  }

  @Test
  fun partsLoopAsksForTheBasePartAndNeverUsesASimulatedBox() {
    var now = 0L
    val lines = ArrayList<String>()
    val queue = ArrayList<Runnable>()
    val cam = lookAt(Vec3(0.1f, 1.5f, -0.15f), Vec3(0.1f, 0.75f, -0.5f))
    val wants = ArrayList<List<String>>()
    var simulated = false
    val got = ArrayList<PartsFound?>()
    var due = true
    val pf =
        PartsFinder(
            grab = { cb -> queue += Runnable { cb(grab(cam)) } },
            query = { _, _, want, _ ->
              wants += want
              PartsResult(found = want.filter { it != "gone" }.map { PartsFound(it, "part", listOf(400.0, 400.0, 600.0, 600.0), 0.8, it) },
                  missing = want.filter { it == "gone" }, simulated = simulated)
            },
            surfaces = { PickSurfaces(null, null, 0.75f) },
            submit = { queue += it },
            log = AgqLog({ now }, { lines += it }, wallMs = { 0L }),
            clockMs = { now },
        )
    pf.baseDue = { due }
    pf.onBase = { f, _, _ -> got += f }
    fun drain() {
      while (queue.isNotEmpty()) queue.removeAt(0).run()
    }
    // no action shown (REGISTER): the base part alone
    pf.setBase("malm", "drawer_front")
    assertTrue(pf.tick())
    drain()
    assertEquals(listOf("drawer_front"), wants.single())
    assertEquals("drawer_front", got.single()?.id)
    assertTrue("the base part is not a pick target", pf.pickTargets.isEmpty())
    // with an action: one request for both; targets only for the action's items
    now += 5000
    pf.setAction("malm", 2, 1, true, listOf("side_left"))
    assertTrue(pf.tick())
    drain()
    assertEquals(listOf("side_left", "drawer_front"), wants.last())
    assertEquals(listOf("side_left"), pf.pickTargets.map { it.id })
    assertEquals(2, got.size)
    // not due: only the action's items
    due = false
    now += 5000
    assertTrue(pf.tick())
    drain()
    assertEquals(listOf("side_left"), wants.last())
    assertEquals(2, got.size)
    // the action's item is the base part and the anchor already sits on it: no cue on it
    pf.setAction("malm", 1, 0, true, listOf("drawer_front"))
    assertTrue(pf.tick())
    drain()
    assertEquals(listOf("drawer_front"), pf.pickTargets.map { it.id })
    pf.hideBaseTarget = { true }
    assertTrue(pf.pickTargets.isEmpty())
    pf.hideBaseTarget = { false }
    // a simulated server: boxes are placeholders, no target is invented
    simulated = true
    pf.setAction("malm", 2, 1, true, listOf("side_right"))
    assertTrue(pf.tick())
    drain()
    assertTrue(pf.pickTargets.isEmpty())
    assertNotNull(lines.lastOrNull { it.contains("\"simulated\":true") })
  }
}
