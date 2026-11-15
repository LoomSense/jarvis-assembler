package com.agj.android.world

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.guide.GuideJson
import com.agj.android.net.PartsResult
import com.agj.android.vision.FlowMath
import com.agj.android.vision.FrameGrab
import com.agj.android.vision.Frames
import com.agj.android.vision.Intrinsics
import com.agj.android.vision.PartFlowTracker
import java.util.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continuous part tracking without OpenCV: the world-anchored predict-vs-observe logic ([PartTracks]) with synthetic
 * camera motion, re-seed identity matching, loss, the downscaled-frame pixel mapping ([FlowMath]), marker slots,
 * and the step-mode answer through [PartsFinder].
 */
class PartTracksTest {
  private val k = Intrinsics(870.0, 870.0, 639.5, 479.5, 1280, 960)
  private val table = 0.75f

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

  private class Sim {
    var now = 1_000_000L
    val lines = ArrayList<String>()
    val log = AgqLog({ now }, { lines += it }, wallMs = { 0L })
    val tracks = PartTracks(log, { now })
    var tNs = 0L

    fun events() = lines.map { Json.parseToJsonElement(it).jsonObject }.filter { it["ev"]?.jsonPrimitive?.content == "part_track" }

    fun sum(field: String) = events().sumOf { it[field]!!.jsonPrimitive.content.toDouble() }
  }

  private fun inst(id: String, index: Int, at: Vec3, cam: Pose3, base: Boolean = false, pick: Boolean = true) =
      PartInstance(id, index, at, "plane", listOf(400.0, 400.0, 500.0, 500.0), 40.0, 30.0, (at - cam.t).length(), 0.9,
          "Label $id", "hardware", 0.03f, mapOf("en" to "Dowel", "el" to "Καβίλια"),
          mapOf("en" to "2 of 2 in view", "el" to "φαίνονται 2 από 2"), 2, 2, pick = pick, base = base)

  /** One camera frame at 25 Hz: the 2D tracker sees every track at [seen] (true WORLD position) ± [noisePx]. */
  private fun frame(s: Sim, cam: Pose3, seen: Map<Int, Vec3>, noisePx: Double = 0.0, rnd: Random? = null, offsetPx: Double = 0.0) {
    s.now += 40
    s.tNs += 40_000_000L
    val prep = s.tracks.prepare(cam, k)
    val obs = seen.keys.mapNotNull { key ->
      val w = seen[key] ?: return@mapNotNull null
      val (u, v) = PickRays.project(cam, k, w) ?: return@mapNotNull null
      val n = if (rnd != null) noisePx * (rnd.nextDouble() * 2 - 1) else 0.0
      PartFlowTracker.Obs(key, u + n + offsetPx, v + n, 10, null)
    }
    s.tracks.onFlow(obs, cam, k, s.tNs, 2.0, prep.seeds.size)
  }

  // ---- predict vs observe ----

  @Test
  fun headMovesPartStillWorldPointDoesNotMove() {
    val s = Sim()
    val w = Vec3(0.1f, table, -0.5f)
    val cam0 = lookAt(Vec3(0f, 1.3f, 0.1f), Vec3(0f, table, -0.5f))
    s.tracks.onAnswer(setOf("dowel"), listOf(inst("dowel", 0, w, cam0)), s.now)
    val key = s.tracks.targets().single().key
    val rnd = Random(3)
    // 6 s of a head that walks 30 cm sideways, bobs and turns slowly (≈ 8°/s), with ±2 px of tracker noise
    for (i in 0 until 150) {
      val t = i / 25f
      val eye = Vec3(-0.15f + 0.05f * t, 1.3f + 0.02f * kotlin.math.sin(t), 0.1f + 0.01f * t)
      frame(s, lookAt(eye, Vec3(0.05f * kotlin.math.sin(t * 0.7f), table, -0.5f)), mapOf(key to w), 2.0, rnd)
    }
    val t = s.tracks.targets().single()
    assertEquals("the world point is untouched, exactly", w, t.worldPos)
    assertTrue(t.tracked)
    assertFalse(t.moving)
    assertEquals(0.0, s.sum("moved"), 0.0)
    assertTrue("part_track is logged about once a second: ${s.events().size}", s.events().size in 5..7)
  }

  @Test
  fun fastHeadTurnWithPoseTimingErrorDoesNotMoveThePoint() {
    val s = Sim()
    val w = Vec3(0f, table, -0.5f)
    val eye = Vec3(0f, 1.3f, 0.1f)
    s.tracks.onAnswer(setOf("a"), listOf(inst("a", 0, w, lookAt(eye, w))), s.now)
    val key = s.tracks.targets().single().key
    frame(s, lookAt(eye, w), mapOf(key to w))
    // the head turns at ≈ 90°/s and the pose lags the picture: the observation is 40 px off the prediction
    for (i in 1..20) frame(s, lookAt(eye, Vec3(0.04f * i, table, -0.5f)), mapOf(key to w), offsetPx = 40.0)
    assertEquals(w, s.tracks.targets().single().worldPos)
    assertEquals(0.0, s.sum("moved"), 0.0)
    // the head rests, the error is gone: still nothing moved
    for (i in 0 until 30) frame(s, lookAt(eye, Vec3(0.8f, table, -0.5f)), mapOf(key to w))
    assertEquals(w, s.tracks.targets().single().worldPos)
    assertTrue("frames held because the head was moving fast: ${s.sum("head_gated")}", s.sum("head_gated") >= 15)
    assertEquals(0.0, s.sum("moved"), 0.0)

    // a track nobody confirms for 30 s is dropped (the tracker stops working for nothing)
    s.now += 31_000
    assertTrue(s.tracks.prepare(lookAt(eye, w), k).drops.contains(key))
    assertTrue(s.tracks.targets().isEmpty())
    assertFalse(s.tracks.wantsFrames)
  }

  @Test
  fun partMovesWorldPointFollowsAndSettles() {
    val s = Sim()
    val a = Vec3(-0.1f, table, -0.5f)
    val b = Vec3(0.15f, table, -0.42f)
    val cam0 = lookAt(Vec3(0f, 1.3f, 0.1f), Vec3(0f, table, -0.5f))
    s.tracks.onAnswer(setOf("p"), listOf(inst("p", 0, a, cam0)), s.now)
    val key = s.tracks.targets().single().key
    val rnd = Random(5)
    var maxLag = 0f
    var sawMoving = false
    for (i in 0 until 75) { // 1 s still, 1 s sliding 26 cm, 1 s still; the head drifts slowly all the time
      val u = ((i - 25) / 25f).coerceIn(0f, 1f)
      val at = a.lerp(b, u)
      val eye = Vec3(0.002f * i, 1.3f, 0.1f)
      frame(s, lookAt(eye, Vec3(0f, table, -0.5f)), mapOf(key to at), 1.0, rnd)
      val t = s.tracks.targets().single()
      if (i < 25) assertEquals("still before it is pushed", a, t.worldPos)
      if (i in 30..50) maxLag = maxOf(maxLag, (t.worldPos - at).length())
      sawMoving = sawMoving || t.moving
    }
    val t = s.tracks.targets().single()
    assertTrue(sawMoving)
    assertFalse("it came to rest", t.moving)
    assertTrue("ends on the part: ${(t.worldPos - b).length()} m", (t.worldPos - b).length() < 0.01f)
    assertEquals(table, t.worldPos.y, 1e-4f)
    assertTrue("follows within 6 cm while sliding at 26 cm/s: $maxLag", maxLag < 0.06f)
    assertEquals("one move, not one per frame", 1.0, s.sum("moved"), 0.0)
  }

  @Test
  fun basePartMoveIsReportedAsStepsThatAddUp() {
    val s = Sim()
    val a = Vec3(0f, table, -0.5f)
    val b = Vec3(0.12f, table, -0.5f)
    val cam = lookAt(Vec3(0f, 1.3f, 0.1f), a)
    var sum = Vec3.ZERO
    s.tracks.onBaseMoved = { from, to -> sum = sum + (to - from) }
    s.tracks.onAnswer(setOf("front"), listOf(inst("front", 0, a, cam, base = true, pick = false)), s.now)
    assertTrue("a base part that is not picked is not a target", s.tracks.targets().isEmpty())
    frame(s, cam, emptyMap()) // seeds it
    val key = 1
    for (i in 0 until 50) frame(s, cam, mapOf(key to a.lerp(b, (i / 25f).coerceAtMost(1f))))
    assertTrue("steps add up to the slide: $sum", (sum - (b - a)).length() < 0.012f)
  }

  // ---- re-seed identity ----

  @Test
  fun aNewAnswerKeepsIdentitiesByNearestWorldPoint() {
    val s = Sim()
    val cam = lookAt(Vec3(0f, 1.3f, 0.1f), Vec3(0f, table, -0.5f))
    val at = listOf(Vec3(-0.10f, table, -0.5f), Vec3(-0.04f, table, -0.5f), Vec3(0.02f, table, -0.5f), Vec3(0.08f, table, -0.5f))
    s.tracks.onAnswer(setOf("screw", "side"), at.mapIndexed { i, p -> inst("screw", i, p, cam) } + inst("side", 0, Vec3(0.3f, table, -0.6f), cam), s.now)
    val before = s.tracks.targets().filter { it.id == "screw" }.associate { it.instance to it.key }
    val sideKey = s.tracks.targets().single { it.id == "side" }.key
    frame(s, cam, s.tracks.targets().associate { it.key to it.worldPos }) // all followed
    // the next answer lists the screws in another order, a few mm off, and a fifth one
    s.now += 4000
    val order = listOf(2, 0, 3, 1)
    val again = order.mapIndexed { i, o -> inst("screw", i, at[o] + Vec3(0.004f, 0f, -0.003f), cam) } + inst("screw", 4, Vec3(0.2f, table, -0.4f), cam)
    s.tracks.onAnswer(setOf("screw"), again, s.now)
    val after = s.tracks.targets().filter { it.id == "screw" }
    assertEquals(5, after.size)
    for ((i, o) in order.withIndex()) {
      val t = after.single { it.instance == i }
      assertEquals("the same real screw keeps its key", before[o], t.key)
      assertEquals("a few mm is not a move: the point stays", at[o], t.worldPos)
      assertEquals(0L, t.ageMs)
    }
    assertFalse(after.single { it.instance == 4 }.key in before.values)
    assertEquals("an item the answer did not speak about stays", sideKey, s.tracks.targets().single { it.id == "side" }.key)
    assertEquals("φαίνονται 2 από 2", after.first().countText["el"])
    assertEquals("Καβίλια · φαίνονται 2 από 2", after.first().pill("el"))

    // one screw is not seen once: kept while it is followed; not seen twice: gone
    frame(s, cam, s.tracks.targets().associate { it.key to it.worldPos })
    s.tracks.onAnswer(setOf("screw"), again.take(4), s.now)
    assertEquals(5, s.tracks.targets().count { it.id == "screw" })
    s.tracks.onAnswer(setOf("screw"), again.take(4), s.now)
    assertEquals(4, s.tracks.targets().count { it.id == "screw" })
    // the item is reported missing: all of its tracks go
    s.tracks.onAnswer(setOf("screw"), emptyList(), s.now)
    assertEquals(listOf("side"), s.tracks.targets().map { it.id })
  }

  @Test
  fun aPartThatMovedAfterThePictureKeepsItsNewerPlace() {
    val s = Sim()
    val a = Vec3(0f, table, -0.5f)
    val b = Vec3(0.2f, table, -0.5f)
    val cam = lookAt(Vec3(0f, 1.3f, 0.1f), a)
    s.tracks.onAnswer(setOf("p"), listOf(inst("p", 0, a, cam)), s.now)
    val key = s.tracks.targets().single().key
    frame(s, cam, mapOf(key to a))
    s.tracks.onGrab() // the picture for the next answer shows it at a
    for (i in 0 until 60) frame(s, cam, mapOf(key to a.lerp(b, (i / 25f).coerceAtMost(1f))))
    s.tracks.onAnswer(setOf("p"), listOf(inst("p", 0, a, cam)), s.now) // 4 s old: still says a
    val t = s.tracks.targets().single()
    assertEquals("same thing", key, t.key)
    assertTrue("the tracker's newer place wins: ${t.worldPos}", (t.worldPos - b).length() < 0.01f)
  }

  @Test
  fun matchNearestIsGreedyAndGated() {
    val old = listOf(Vec3(0f, 0f, 0f), Vec3(0.05f, 0f, 0f))
    val fresh = listOf(Vec3(0.045f, 0f, 0f), Vec3(0.004f, 0f, 0f), Vec3(0.5f, 0f, 0f))
    assertEquals(setOf(0 to 1, 1 to 0), PartTracks.matchNearest(old, fresh) { 0.06f }.toSet())
    assertTrue(PartTracks.matchNearest(old, listOf(Vec3(0.3f, 0f, 0f))) { 0.06f }.isEmpty())
  }

  // ---- loss ----

  @Test
  fun aLostTrackStaysWorldLockedAndIsLookedForAgain() {
    val s = Sim()
    val w = Vec3(0f, table, -0.5f)
    val cam = lookAt(Vec3(0f, 1.3f, 0.1f), w)
    s.tracks.onAnswer(setOf("p"), listOf(inst("p", 0, w, cam)), s.now)
    val prep = s.tracks.prepare(cam, k)
    val seed = prep.seeds.single()
    val (u, v) = PickRays.project(cam, k, w)!!
    assertEquals("the box is seeded where the point projects", u, (seed.box[0] + seed.box[2]) / 2, 1e-6)
    assertEquals(v, (seed.box[1] + seed.box[3]) / 2, 1e-6)
    assertEquals(80.0, seed.box[2] - seed.box[0], 1e-6)
    s.tracks.onFlow(listOf(PartFlowTracker.Obs(seed.key, u, v, 12, null)), cam, k, 1L)
    assertTrue(s.tracks.targets().single().tracked)
    // a hand covers it: the 2D tracker reports it lost
    s.tNs = 40_000_000L
    s.tracks.onFlow(listOf(PartFlowTracker.Obs(seed.key, u + 30, v, 1, "few_points")), cam, k, s.tNs)
    val t = s.tracks.targets().single()
    assertFalse(t.tracked)
    assertEquals("it stands where it was", w, t.worldPos)
    assertTrue("not re-seeded at once", s.tracks.prepare(cam, k).seeds.isEmpty())
    s.now += 1100
    assertEquals(seed.key, s.tracks.prepare(cam, k).seeds.single().key)
    s.now += 1000
    s.tracks.onFlow(emptyList(), cam, k, 80_000_000L)
    assertEquals(1.0, s.sum("lost"), 0.0)
  }

  @Test
  fun aSeedIsScaledWithDistanceAndWaitsUntilThePointIsInView() {
    val s = Sim()
    val w = Vec3(0f, table, -0.5f)
    val cam = lookAt(Vec3(0f, 1.3f, 0.1f), w)
    s.tracks.onAnswer(setOf("p"), listOf(inst("p", 0, w, cam)), s.now)
    assertTrue("looking away: nothing to seed", s.tracks.prepare(lookAt(Vec3(0f, 1.3f, 0.1f), Vec3(0f, 1.3f, 2f)), k).seeds.isEmpty())
    val near = lookAt(w + (cam.t - w) * 0.5f, w) // half the distance: the box is twice as large
    val seed = s.tracks.prepare(near, k).seeds.single()
    assertEquals(160.0, seed.box[2] - seed.box[0], 0.5)
  }

  @Test
  fun anImpossibleJumpIsALossNotAMove() {
    val s = Sim()
    val w = Vec3(0f, table, -0.5f)
    val cam = lookAt(Vec3(0f, 1.3f, 0.1f), w)
    s.tracks.onAnswer(setOf("p"), listOf(inst("p", 0, w, cam)), s.now)
    val key = s.tracks.targets().single().key
    frame(s, cam, mapOf(key to w))
    for (i in 0 until 6) frame(s, cam, mapOf(key to Vec3(0.6f, table, -0.5f))) // 60 cm in one frame
    val t = s.tracks.targets().single()
    assertEquals(w, t.worldPos)
    assertFalse(t.tracked)
    assertTrue(s.tracks.prepare(cam, k).drops.contains(key))
  }

  // ---- pixels of a downscaled frame ----

  @Test
  fun boxAndPixelMappingForADownscaledFrame() {
    // 1280 × 960 → 640 × 480: the picture's edges (−0.5 and 1279.5) stay the edges (−0.5 and 639.5)
    assertEquals(-0.5, FlowMath.toSmall(-0.5, 0.5), 1e-12)
    assertEquals(639.5, FlowMath.toSmall(1279.5, 0.5), 1e-12)
    assertEquals(319.5, FlowMath.toSmall(639.5, 0.5), 1e-12) // the centre stays the centre
    for (x in listOf(0.0, 17.25, 800.0)) assertEquals(x, FlowMath.toFull(FlowMath.toSmall(x, 0.5), 0.5), 1e-9)
    // a server box → full pixels → small pixels
    val px = PickRays.boxToPx(listOf(100.0, 200.0, 300.0, 600.0), 1280, 960)!!
    val sm = FlowMath.boxToSmall(px, 0.5)
    assertEquals(0.2 * 640 - 0.5, sm[0], 1e-9)
    assertEquals(0.3 * 480 - 0.5, sm[3], 1e-9)
    assertEquals(px.toList(), FlowMath.boxToFull(sm, 0.5).toList())
    // a small screw (11 × 5 px) gets a region of at least 24 px a side, inside the image
    val r = FlowMath.featureRegion(doubleArrayOf(100.0, 100.0, 111.0, 105.0), 640, 480)!!
    assertTrue(r[2] >= 24 && r[3] >= 24)
    val edge = FlowMath.featureRegion(doubleArrayOf(630.0, 470.0, 660.0, 500.0), 640, 480)
    assertNull("centre off the image", edge)
    val corner = FlowMath.featureRegion(doubleArrayOf(600.0, 440.0, 650.0, 490.0), 640, 480)!!
    assertTrue(corner[0] + corner[2] <= 640 && corner[1] + corner[3] <= 480)
    assertEquals(1.0 / 7, FlowMath.iou(doubleArrayOf(0.0, 0.0, 2.0, 2.0), doubleArrayOf(1.0, 1.0, 3.0, 3.0)), 1e-12)
  }

  @Test
  fun medianFlowRejectsOutliersAndFailedPoints() {
    val n = 10
    val p0 = FloatArray(2 * n) { (it * 7 % 50).toFloat() }
    val p1 = FloatArray(2 * n) { p0[it] + if (it % 2 == 0) 3f else -2f } // the part moved (3, −2)
    val back = p0.copyOf()
    val okF = ByteArray(n) { 1 }
    val okB = ByteArray(n) { 1 }
    p1[0] = p0[0] + 40f // a point that jumped onto a hand
    p1[2] = p0[2] // a point on the desk (did not move)
    okF[2] = 0 // not found
    back[6] = p0[6] + 5f // fails the forward–backward check
    val f = FlowMath.medianFlow(p0, p1, back, okF, okB)!!
    assertEquals(3.0, f.dx, 1e-6)
    assertEquals(-2.0, f.dy, 1e-6)
    assertEquals(6, f.kept)
    assertFalse(f.keep[0] || f.keep[1] || f.keep[2] || f.keep[3])
    assertNull(FlowMath.medianFlow(p0, p1, back, ByteArray(n), okB))
  }

  @Test
  fun lossConditions() {
    val box = doubleArrayOf(100.0, 100.0, 140.0, 130.0)
    assertNull(FlowMath.lossReason(box, 640, 480, 8, 4, 10.0, 10.0))
    assertEquals("few_points", FlowMath.lossReason(box, 640, 480, 3, 4, 10.0, 10.0))
    assertEquals("off_image", FlowMath.lossReason(doubleArrayOf(630.0, 100.0, 670.0, 130.0), 640, 480, 8, 4, 10.0, 10.0))
    assertEquals("off_image", FlowMath.lossReason(doubleArrayOf(-50.0, 100.0, -10.0, 130.0), 640, 480, 8, 4, 10.0, 10.0))
    assertEquals("collapsed", FlowMath.lossReason(box, 640, 480, 8, 4, 2.0, 10.0))
    assertEquals("exploded", FlowMath.lossReason(box, 640, 480, 8, 4, 31.0, 10.0))
    val ring = FloatArray(16) { i -> (if (i % 2 == 0) kotlin.math.cos(i / 2 * 0.785) else kotlin.math.sin(i / 2 * 0.785)).toFloat() * 10f }
    assertEquals(10.0, FlowMath.spread(ring), 0.5)
  }

  // ---- markers ----

  @Test
  fun aMarkerStaysOnItsTargetWhenTheListChanges() {
    val slots = IntArray(4)
    var a = PickCue.assign(listOf(11, 12, 13), slots)
    assertEquals(listOf(0, 1, 2, null), a.toList())
    for (i in slots.indices) slots[i] = a[i]?.let { listOf(11, 12, 13)[it] } ?: 0
    // 12 went, 14 came, and the order changed: 11 and 13 keep their slots, 14 takes the free one
    val keys = listOf(13, 14, 11)
    a = PickCue.assign(keys, slots)
    assertEquals(listOf(11, 14, 13, null), a.map { it?.let { i -> keys[i] } })
  }

  // ---- step mode through the parts loop ----

  @Test
  fun stepModeAnswerGivesOneTargetPerInstanceWithNamesAndCounts() {
    val json =
        """{"found": [{"id": "101345", "kind": "hardware", "box_2d": [178, 616, 197, 666], "confidence": 0.98, "label": "Ξύλινη καβίλια · 101345"}],
          "missing": [], "message": "x", "step": 2, "mode": "step", "all_in_view": true,
          "items": [
           {"id": "side", "code": null, "kind": "panel", "name": {"en": "Left side panel", "el": null}, "label": "L", "visible": false,
            "box_2d": null, "boxes": [], "confidence": 0, "count_seen": 0, "count_needed": 1, "count_in_box": 1, "count_text": null},
           {"id": "101345", "code": "101345", "kind": "hardware", "name": {"en": "Wooden dowel", "el": "Ξύλινη καβίλια"},
            "label": "Ξύλινη καβίλια · 101345", "visible": true, "box_2d": [178, 616, 197, 666],
            "boxes": [[178, 616, 197, 666], [243, 707, 301, 745]], "confidence": 0.98,
            "count_seen": 2, "count_needed": 2, "count_in_box": 2, "count_text": {"en": "2 of 2 in view", "el": "φαίνονται 2 από 2"}}],
          "latency_ms": 5706, "model": "gemini-4-hydrogen", "simulated": false}"""
    val r = GuideJson.decodeFromString(PartsResult.serializer(), json)
    assertEquals(2, r.items.size)
    assertEquals(true, r.all_in_view)
    var now = 5_000L
    val lines = ArrayList<String>()
    val queue = ArrayList<Runnable>()
    val cam = lookAt(Vec3(0f, 1.45f, 0.1f), Vec3(0f, table, -0.5f))
    val k2 = Intrinsics(640.0, 640.0, 639.5, 479.5, 1280, 960)
    val pf =
        PartsFinder(
            grab = { cb -> queue += Runnable { cb(FrameGrab(ByteArray(4), 1280, 960, k2, cam, 0L, now)) } },
            query = { _, _, _, _ -> r },
            surfaces = { PickSurfaces(null, null, table) },
            submit = { queue += it },
            log = AgqLog({ now }, { lines += it }, wallMs = { 0L }),
            clockMs = { now },
        )
    pf.setAction("malm", 2, 1, true, listOf("101345", "side"), focus = listOf("101345"))
    assertTrue(pf.tick())
    while (queue.isNotEmpty()) queue.removeAt(0).run()
    val ts = pf.pickTargets
    assertEquals("both dowels", listOf(0, 1), ts.map { it.instance })
    assertNotEquals(ts[0].key, ts[1].key)
    assertTrue(ts.all { it.id == "101345" && it.current && it.kind == "hardware" })
    assertEquals("Ξύλινη καβίλια · φαίνονται 2 από 2", ts[0].pill("el"))
    assertEquals("Wooden dowel · 2 of 2 in view", ts[0].pill("en"))
    assertEquals(2, ts[0].countSeen)
    assertTrue("two different places on the table", (ts[0].worldPos - ts[1].worldPos).length() > 0.03f)
    assertEquals(table, ts[1].worldPos.y, 1e-4f)
    // the same answer again: the same two things (keys kept), no third
    now += 5000
    assertTrue(pf.tick())
    while (queue.isNotEmpty()) queue.removeAt(0).run()
    assertEquals(ts.map { it.key }, pf.pickTargets.map { it.key })
    // another step: gone
    pf.setAction("malm", 3, 0, true, listOf("x"))
    assertTrue(pf.pickTargets.isEmpty())
  }
}
