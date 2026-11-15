package com.agj.android.vision

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Camera pose lookup (head buffer × lens pose), its failure reasons and the `camera_pose` log cadence. */
class CameraPoseTest {
  private val ms = 1_000_000L
  private val lens = Pose3(Vec3(0.03f, -0.01f, -0.05f), Quat.axisAngle(Vec3(1f, 0f, 0f), 3.14159f))

  private fun buffer(fromMs: Long, toMs: Long): HeadPoseBuffer {
    val b = HeadPoseBuffer()
    var t = fromMs
    while (t <= toMs) {
      b.push(t * ms, Pose3(Vec3(t / 1000f, 1.6f, 0f), Quat.yaw(t / 10000f)))
      t += 14
    }
    return b
  }

  @Test
  fun poseIsHeadAtCaptureTimesLens() {
    val b = buffer(1000, 2000)
    val r = CameraPoseResolver.resolve(b, lens, captureNs = 1500 * ms, nowNs = 2010 * ms)
    assertNull(r.miss)
    assertFalse(r.fallback)
    val head = b.at(1500 * ms)!!
    val want = head * lens
    assertTrue((r.worldFromCamera!!.t - want.t).length() < 1e-6f)
    assertEquals(1.5f, r.head!!.t.x, 1e-3f)
  }

  @Test
  fun reasonsWhenThereIsNoPose() {
    assertEquals(PoseMiss.NO_HEAD_SAMPLES, CameraPoseResolver.resolve(HeadPoseBuffer(), lens, 0, 0).miss)
    assertEquals(PoseMiss.NO_HEAD_SAMPLES, CameraPoseResolver.resolve(null, lens, 0, 0).miss)
    val b = buffer(1000, 2000)
    // no lens pose: head found but no camera pose
    val nl = CameraPoseResolver.resolve(b, null, 1500 * ms, 2010 * ms)
    assertEquals(PoseMiss.NO_LENS_POSE, nl.miss)
    assertNull(nl.worldFromCamera)
    assertNotNull(nl.head)
    // the render loop stopped 2 s ago
    val st = CameraPoseResolver.resolve(b, lens, 3990 * ms, 4000 * ms)
    assertEquals(PoseMiss.HEAD_STALE, st.miss)
    assertNull(st.worldFromCamera)
  }

  @Test
  fun captureClockMismatchFallsBackToNowMinusLatency() {
    val b = buffer(1000, 2000)
    // capture time on another clock: 50 s off
    val r = CameraPoseResolver.resolve(b, lens, captureNs = 52_000 * ms, nowNs = 2005 * ms, fallbackLatencyNs = 35 * ms)
    assertEquals(PoseMiss.CAPTURE_OUTSIDE_BUFFER, r.miss)
    assertTrue(r.fallback)
    assertNotNull(r.worldFromCamera)
    assertEquals(1.970f, r.head!!.t.x, 2e-3f)
    assertEquals(50_000.0, r.deltaMs!!, 20.0)
  }

  @Test
  fun logsPoseFirstThenEveryTenSecondsAndMissesWithReason() {
    var now = 0L
    val lines = ArrayList<String>()
    val log = CameraPoseLog(AgqLog({ now }, { lines += it }, wallMs = { 0L }), { now })
    fun evs() = lines.map { Json.parseToJsonElement(it).jsonObject }
    val b = buffer(1000, 2000)
    val ok = CameraPoseResolver.resolve(b, lens, 1500 * ms, 2010 * ms)
    val miss = CameraPoseResolver.resolve(HeadPoseBuffer(), lens, 0, 0)
    // 24 misses: within the grace, nothing; the 25th logs the reason
    repeat(24) { log.onFrame(it.toLong(), miss, 0, 0, true) }
    assertTrue(lines.isEmpty())
    log.onFrame(24, miss, 0, 0, true)
    val e = evs().single()
    assertEquals("error", e["ev"]!!.jsonPrimitive.content)
    assertEquals("camera_pose_missing", e["where"]!!.jsonPrimitive.content)
    assertEquals("no_head_samples", e["reason"]!!.jsonPrimitive.content)
    // more misses within 10 s: not repeated
    repeat(100) { log.onFrame(25L + it, miss, 0, 0, true) }
    assertEquals(1, lines.size)
    // first pose → camera_pose with cam_p / cam_q
    now += 1000
    log.onFrame(200, ok, 1500 * ms, 1530 * ms, true)
    val p = evs().last()
    assertEquals("camera_pose", p["ev"]!!.jsonPrimitive.content)
    assertEquals("true", p["first"]!!.jsonPrimitive.content)
    assertNotNull(p["cam_p"])
    assertNotNull(p["cam_q"])
    assertEquals(30.0, p["capture_age_ms"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    // within 10 s: quiet; after 10 s: again
    now += 5000
    log.onFrame(201, ok, 0, 0, true)
    assertEquals(2, lines.size)
    now += 5000
    log.onFrame(202, ok, 0, 0, true)
    assertEquals(3, lines.size)
    assertEquals("false", evs().last()["first"]!!.jsonPrimitive.content)
    // the clock fallback is reported once
    val fb = CameraPoseResolver.resolve(b, lens, 52_000 * ms, 2005 * ms)
    log.onFrame(203, fb, 0, 0, true)
    log.onFrame(204, fb, 0, 0, true)
    val errs = evs().filter { it["ev"]!!.jsonPrimitive.content == "error" }
    assertEquals(2, errs.size)
    assertEquals("capture_outside_buffer", errs.last()["reason"]!!.jsonPrimitive.content)
    assertEquals("true", errs.last()["ts_fallback"]!!.jsonPrimitive.content)
  }
}
