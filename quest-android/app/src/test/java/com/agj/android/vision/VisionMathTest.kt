package com.agj.android.vision

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.TrackSource
import com.agj.android.core.Vec3
import java.io.File
import java.util.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionMathTest {
  private fun near(a: Vec3, b: Vec3, eps: Float = 1e-4f) = assertTrue("$a != $b", (a - b).length() < eps)

  private fun randQuat(r: Random) =
      Quat(r.nextGaussian().toFloat(), r.nextGaussian().toFloat(), r.nextGaussian().toFloat(), r.nextGaussian().toFloat())
          .normalized()

  private fun randVec(r: Random, s: Float = 1f) =
      Vec3(r.nextGaussian().toFloat() * s, r.nextGaussian().toFloat() * s, r.nextGaussian().toFloat() * s)

  @Test
  fun spatialMirrorIsAnInvolutionAndKeepsRotationsRigid() {
    val r = Random(1)
    repeat(20) {
      val p = Pose3(randVec(r), randQuat(r))
      val s = Frames.worldToSpatial(p)
      val back = Frames.spatialToWorld(s[0], s[1], s[2], s[3], s[4], s[5], s[6])
      near(p.t, back.t)
      assertTrue(Frames.angleDeg(p.q, back.q) < 1e-2)
      // mirroring a point and applying the mirrored pose = mirroring the transformed point
      val v = randVec(r)
      val w = p.transform(v)
      val sp = Frames.spatialToWorld(s[0], s[1], s[2], s[3], s[4], s[5], s[6]) // same pose back in WORLD
      near(w, sp.transform(v))
      // a mirrored pose acting on mirrored points: (x,y,z) → (x,y,-z)
      val ps = Pose3(Vec3(s[0], s[1], s[2]), Quat(s[3], s[4], s[5], s[6]))
      val ws = ps.transform(Vec3(v.x, v.y, -v.z))
      near(Vec3(w.x, w.y, -w.z), ws)
    }
  }

  /** Meta's showcase formula in left-handed Spatial axes, mirrored back, equals our ANDROID convention. */
  @Test
  fun lensPoseMatchesMetaShowcaseConversion() {
    val r = Random(2)
    repeat(20) {
      val q = randQuat(r)
      val t = randVec(r, 0.05f)
      val qArr = floatArrayOf(q.x, q.y, q.z, q.w)
      val tArr = floatArrayOf(t.x, t.y, t.z)
      // showcase: Quaternion(w=r3, x=-r0, y=-r1, z=r2).inverse() * Quaternion(180°, 0, 0); t = (x, y, -z)
      val qLh = Quat(-q.x, -q.y, q.z, q.w).conjugate() * Quat(1f, 0f, 0f, 0f)
      val tLh = Vec3(t.x, t.y, -t.z)
      val ours = Frames.headFromCamera(tArr, qArr)
      val pCv = randVec(r)
      // showcase camera axes: x right, y up, z forward → OpenCV (y down)
      val pLhCam = Vec3(pCv.x, -pCv.y, pCv.z)
      val headLh = qLh.rotate(pLhCam) + tLh
      val headRh = Vec3(headLh.x, headLh.y, -headLh.z)
      near(headRh, ours.transform(pCv), 1e-4f)
    }
  }

  @Test
  fun rodriguesAgreesWithMatrixPath() {
    val r = Random(3)
    repeat(20) {
      val rv = doubleArrayOf(r.nextGaussian(), r.nextGaussian(), r.nextGaussian())
      val q = Frames.rodriguesToQuat(rv)
      // Rodrigues matrix by hand
      val th = Math.sqrt(rv[0] * rv[0] + rv[1] * rv[1] + rv[2] * rv[2])
      val k = rv.map { it / th }
      val c = Math.cos(th)
      val s = Math.sin(th)
      val m =
          Array(3) { i ->
            DoubleArray(3) { j ->
              val kk = k[i] * k[j] * (1 - c)
              val cross =
                  when (i * 3 + j) {
                    1 -> -k[2] * s
                    2 -> k[1] * s
                    3 -> k[2] * s
                    5 -> -k[0] * s
                    6 -> -k[1] * s
                    7 -> k[0] * s
                    else -> 0.0
                  }
              (if (i == j) c else 0.0) + kk + cross
            }
          }
      assertTrue(Frames.angleDeg(q, Frames.matToQuat(m)) < 1e-2)
    }
  }

  @Test
  fun malmPlacementOnTheSyntheticDataset() {
    // marker face up at the origin, TOP toward world -Z (tools/quest/replay/geom.py R_WORLD_MARKER)
    val rWm = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, -1.0, 0.0))
    val worldFromMarker = Pose3(Vec3.ZERO, Frames.matToQuat(rWm))
    val worldFromAssembly = worldFromMarker * MarkerPlacement.MALM_DRAWER_FRONT.inverse()
    near(Vec3(0f, 0f, -0.065f), worldFromAssembly.t)
    near(Vec3(0f, 1f, 0f), worldFromAssembly.q.rotate(Vec3(0f, 0f, -1f))) // inside face (assembly -Z) up
    near(Vec3(1f, 0f, 0f), worldFromAssembly.q.rotate(Vec3(1f, 0f, 0f))) // length along world X
    assertEquals(0.0, worldFromAssembly.q.yawDeg(), 1e-3)
    // the marker centre is on the inside face, centred along the length, 65 mm up the front
    near(Vec3(0f, 0.065f, 0f), MarkerPlacement.MALM_DRAWER_FRONT.transform(Vec3.ZERO))
    // marker TOP (+Y) points toward the groove (assembly -Y)
    near(Vec3(0f, -1f, 0f), MarkerPlacement.MALM_DRAWER_FRONT.q.rotate(Vec3(0f, 1f, 0f)))
  }

  @Test
  fun streamIntrinsicsCropAndScale() {
    // 1280x1280 sensor, 1280x960 stream: crop 160 px top and bottom, no scale
    val k = floatArrayOf(800f, 800f, 640f, 640f, 0f)
    val a = Yuv.streamIntrinsics(k, 1280, 1280, 1280, 960)
    assertEquals(800.0, a.fx, 1e-9)
    assertEquals(640.0, a.cx, 1e-9)
    assertEquals(480.0, a.cy, 1e-9)
    // same aspect: plain scale
    val b = Yuv.streamIntrinsics(floatArrayOf(1000f, 1000f, 640f, 480f), 1280, 960, 640, 480)
    assertEquals(500.0, b.fx, 1e-9)
    assertEquals(240.0, b.cy, 1e-9)
    assertEquals(listOf(0.1, 0.2, 0.4, 0.5, 0.3), Yuv.androidToOpenCvDistortion(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f))!!.map { Math.round(it * 10) / 10.0 })
    assertNull(Yuv.androidToOpenCvDistortion(floatArrayOf(0f, 0f, 0f, 0f, 0f)))
  }

  @Test
  fun nv21ResizeKeepsPlanes() {
    val w = 8
    val h = 4
    val y = ByteArray(w * h) { (it % w * 10).toByte() } // value = 10 × column
    val u = ByteArray(w * h / 2) { 50 } // semi-planar: pixel stride 2
    val v = ByteArray(w * h / 2) { 90 }
    val out = Yuv.toNv21Scaled(y, w, u, v, w, 2, w, h, 4, 2)
    assertEquals(4 * 2 * 3 / 2, out.size)
    assertEquals(listOf(0, 20, 40, 60), out.take(4).map { it.toInt() })
    assertEquals(90, out[8].toInt()) // V first in NV21
    assertEquals(50, out[9].toInt())
    assertEquals(768 to 576, Yuv.fitLongEdge(1280, 960, 768))
  }

  @Test
  fun headPoseBufferInterpolates() {
    val b = HeadPoseBuffer(capacity = 4)
    assertNull(b.at(0))
    b.push(100, Pose3(Vec3(0f, 0f, 0f), Quat.IDENTITY))
    b.push(200, Pose3(Vec3(1f, 0f, 0f), Quat.yaw(1f)))
    b.push(150, Pose3(Vec3(9f, 9f, 9f))) // out of order: dropped
    near(Vec3(0.25f, 0f, 0f), b.at(125)!!.t)
    assertEquals(Math.toDegrees(0.5), b.at(150)!!.q.yawDeg(), 0.5)
    near(Vec3(1f, 0f, 0f), b.at(250)!!.t) // within the extrapolation window: nearest
    assertNull(b.at(200 + 200_000_000L))
    repeat(10) { b.push(300L + it * 10, Pose3(Vec3(it.toFloat(), 0f, 0f))) } // wraps the ring
    assertEquals(4, b.size())
    near(Vec3(7.5f, 0f, 0f), b.at(375)!!.t)
  }

  @Test
  fun oneEuroSmoothsRejectsOutliersRelocksAndHolds() {
    val f = OneEuroPoseFilter()
    val rnd = Random(4)
    val truth = Pose3(Vec3(0.1f, 0.8f, -0.5f), Quat.yaw(0.3f))
    var t = 0L
    var last: Pose3? = null
    val errs = ArrayList<Float>()
    repeat(90) {
      t += 33_000_000L
      val m = Pose3(truth.t + randVec(rnd, 0.002f), truth.q)
      last = f.update(m, t)
      if (it > 30) errs += (last!!.t - truth.t).length()
    }
    // jitter of 2 mm per axis (3.5 mm RMS) filtered to well under that
    assertTrue("filtered err ${errs.average()}", errs.average() < 0.0015)
    // single outlier: rejected, pose unchanged
    val before = f.current()!!
    f.update(Pose3(truth.t + Vec3(0.3f, 0f, 0f), truth.q), t + 33_000_000L)
    assertTrue(f.lastRejected)
    near(before.t, f.current()!!.t)
    // a real move: three agreeing measurements → re-lock
    val moved = Pose3(truth.t + Vec3(0.2f, 0f, 0f), Quat.yaw(1.8f))
    repeat(3) { f.update(moved, t + (2 + it) * 33_000_000L) }
    near(moved.t, f.current()!!.t, 1e-3f)
    assertEquals(1, f.relocks)
    // hidden marker: hold forever
    assertNotNull(f.update(null, t + 3_600_000_000_000L))
    assertTrue(f.holding)
  }

  @Test
  fun oneEuroFollowsASlowMoveWithinASecond() {
    val f = OneEuroPoseFilter()
    var t = 0L
    // 20 cm move at 0.2 m/s, then still; the filter should be within 5 mm 1 s after the stop
    var x = 0f
    repeat(30 * 3) {
      t += 33_333_333L
      if (it in 30..59) x += 0.2f / 30
      f.update(Pose3(Vec3(x, 0f, 0f)), t)
    }
    assertTrue("lag ${0.2f - f.current()!!.t.x}", kotlin.math.abs(0.2f - f.current()!!.t.x) < 0.005f)
  }

  private fun markerResult(p: Pose3?, ts: Long, frame: Int? = null) =
      if (p == null) TrackResult.none(ts, frame)
      else TrackResult(true, p * MarkerPlacement.MALM_DRAWER_FRONT.inverse(), 0.4, TrackSource.MARKER, ts, p, null, 3.0,
          true, frame)

  @Test
  fun trackingCoreLogsTheToolFields() {
    val lines = ArrayList<String>()
    var now = 0L
    val core = TrackingCore(MarkerConfig(), AgqLog({ now }, { lines += it }), logEveryNs = 0L,
        stats = { mapOf("fps" to 72.0, "cam_fps" to 30.0) }, clockNs = { 0L })
    val wm = Pose3(Vec3(0.01f, 0f, 0f), Quat.IDENTITY)
    core.onMarker(markerResult(wm, 1_000_000L, 7))
    core.onMarker(markerResult(null, 34_000_000L, 8))
    val a = Json.parseToJsonElement(lines[0]).jsonObject
    assertEquals("track", a["ev"]!!.jsonPrimitive.content)
    assertTrue(a["tracked"]!!.jsonPrimitive.boolean)
    assertEquals(7, a["frame"]!!.jsonPrimitive.int)
    assertEquals("marker", a["source"]!!.jsonPrimitive.content)
    assertEquals(0.01, a["pos_m"]!!.jsonArray[0].jsonPrimitive.content.toDouble(), 1e-6)
    assertEquals(4, a["q"]!!.jsonArray.size)
    assertEquals(72.0, a["fps"]!!.jsonPrimitive.content.toDouble(), 0.0)
    assertEquals(30.0, a["cam_fps"]!!.jsonPrimitive.content.toDouble(), 0.0)
    val b = Json.parseToJsonElement(lines[1]).jsonObject
    assertFalse(b["tracked"]!!.jsonPrimitive.boolean)
    assertEquals("held", b["state"]!!.jsonPrimitive.content)
    assertEquals("anchor", b["source"]!!.jsonPrimitive.content)
    assertNotNull(core.snapshot().worldFromAssembly) // still drawn
  }

  @Test
  fun trackingCoreRateLimitsLiveLogsButAlwaysLogsStateChanges() {
    val lines = ArrayList<String>()
    val core = TrackingCore(MarkerConfig(), AgqLog({ 0L }, { lines += it }), logEveryNs = 100_000_000L)
    val wm = Pose3(Vec3(0f, 0f, -0.5f))
    for (i in 0 until 30) core.onMarker(markerResult(wm, i * 33_333_334L)) // 1 s at 30 fps
    assertEquals(10, lines.size)
    core.onMarker(markerResult(null, 1_000_000_000L))
    assertEquals(11, lines.size) // state change: tracked → held
  }

  @Test
  fun solverPrefersTheSolutionCloserToThePriorWhenAmbiguous() {
    val s = MarkerSolver(MarkerConfig())
    val a = PnpCandidate(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.5), 0.50)
    val b = PnpCandidate(doubleArrayOf(0.6, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.5), 0.55)
    val det = MarkerDetection(DoubleArray(8), listOf(a, b), 1.0)
    val prior = Pose3(Vec3.ZERO, Frames.rodriguesToQuat(doubleArrayOf(0.58, 0.0, 0.0)))
    val r = s.solve(det, Pose3.IDENTITY, prior, 0L, null)
    assertTrue(Frames.angleDeg(r.cameraFromMarker!!.q, prior.q) < 2)
    val noPrior = s.solve(det, Pose3.IDENTITY, null, 0L, null)
    assertTrue(Frames.angleDeg(noPrior.cameraFromMarker!!.q, Quat.IDENTITY) < 1e-3)
    // no camera pose: detected but not tracked in the world
    val camOnly = s.solve(det, null, null, 0L, 3)
    assertFalse(camOnly.tracked)
    assertTrue(camOnly.detected)
    assertEquals(3, camOnly.replayFrame)
    // bad reprojection: rejected
    val bad = s.solve(MarkerDetection(DoubleArray(8), listOf(PnpCandidate(a.rvec, a.tvec, 9.0)), 1.0), Pose3.IDENTITY, null, 0L, null)
    assertFalse(bad.tracked)
  }

  @Test
  fun visionConfigParsesOverridesAndSurvivesGarbage() {
    assertEquals(VisionConfig(), VisionConfig.parse("not json"))
    val c =
        VisionConfig.parse(
            """{"camera_position":1,"lens_convention":"direct","head_time_offset_ms":-12.5,"track_log_hz":30,
               "aruco3":true,"marker_size_m":0.06,"marker_offset_m":[0,0.07,0]}""")
    assertEquals(1, c.cameraPosition)
    assertEquals(LensConvention.DIRECT, c.lensConvention)
    assertEquals(-12.5, c.headTimeOffsetMs, 0.0)
    assertEquals(30.0, c.trackLogHz, 0.0)
    assertTrue(c.detector.aruco3)
    assertEquals(0.06, c.marker.sizeM, 0.0)
    near(Vec3(0f, 0.07f, 0f), c.marker.assemblyFromMarker.t)
    assertEquals(MarkerPlacement.MALM_DRAWER_FRONT.q, c.marker.assemblyFromMarker.q)
  }

  @Test
  fun handCheckerPortsHandChecksTs() {
    val hc = HandChecker()
    val target = Aabb(Vec3(-0.1f, 0.7f, -0.6f), Vec3(0.1f, 0.8f, -0.4f))
    val head = Vec3(0f, 1.6f, 0f)
    var s = hc.update(HandsFrame(0L, head, null, null), target, 0)
    assertFalse(s.present)
    assertFalse(s.still)
    for (i in 0..6) {
      s = hc.update(HandsFrame(i * 100_000_000L, head, HandSample(Vec3(0f, 0.83f, -0.5f), null), null), target, 0)
    }
    assertTrue(s.present)
    assertTrue(s.handAtTarget) // 3 cm above the box
    assertTrue(s.still)
    assertFalse(s.raised)
    s = hc.update(HandsFrame(800_000_000L, head, HandSample(Vec3(0f, 1.45f, -0.1f), null), null), target, 0)
    assertTrue(s.raised)
    assertFalse(s.still)
  }

  @Test
  fun touchTrackerHoldsTwoCornersAndAppliesThePlateOffset() {
    val plateFromAssembly = Pose3(Vec3.ZERO, Quat.axisAngle(Vec3(1f, 0f, 0f), (Math.PI / 2).toFloat()))
    val tt = TouchTracker(Vec3(-0.19f, 0f, 0f) to Vec3(0.19f, 0f, 0f), plateFromAssembly)
    var pose: Pose3? = null
    fun hold(p: Vec3, t0: Long) {
      for (i in 0..9) {
        val r = tt.onHands(HandsFrame(t0 + i * 100_000_000L, null, null, HandSample(Vec3(0f, 0f, 0f), p, 0f)))
        if (r != null) pose = r
      }
    }
    hold(Vec3(0.81f, 0.75f, -1f), 0L)
    assertEquals(1, tt.corner)
    hold(Vec3(1.19f, 0.75f, -1f), 2_000_000_000L)
    assertNotNull(pose)
    near(Vec3(1f, 0.75f, -1f), pose!!.t)
    // assembly +Y maps to plate +Z after the 90° turn about X
    near(Vec3(0f, 0f, 1f), pose!!.q.rotate(Vec3(0f, 1f, 0f)))
  }

  @Test
  fun replayDatasetReadsTheToolsFormat() {
    val dir = createTempDir("replay-tools")
    try {
      File(dir, "000000.jpg").writeBytes(byteArrayOf(0))
      File(dir, "000001.jpg").writeBytes(byteArrayOf(1))
      File(dir, "intrinsics.json").writeText(
          """{"fx":880.8,"fy":880.8,"cx":642.7,"cy":477.4,"width":1280,"height":960,"fps":30.0,"distortion":null}""")
      File(dir, "poses.jsonl").writeText(
          """
          {"frame":10,"t":0.0,"file":"000000.jpg","cam_p":[-0.25,0.45,0.18],"cam_q":[0.77,-0.22,0.42,0.41],"cam_q_xr":[-0.41,-0.42,-0.22,0.77],"marker_p":[0,0,0.5],"marker_q":[0,0,0,1]}
          {"frame":11,"t":0.03,"file":"000001.jpg","cam_p":[-0.26,0.45,0.18],"cam_q":[0.77,-0.22,0.42,0.41]}
          """.trimIndent())
      val ds = ReplayDataset.read(dir)
      assertEquals(30.0, ds.fps!!, 0.0)
      assertNull(ds.intrinsics.distortion)
      near(Vec3(-0.25f, 0.45f, 0.18f), ds.poses[0]!!.t)
      assertEquals(10, ds.frameIndex(0))
      assertEquals(11, ds.frameIndex(1))
    } finally {
      dir.deleteRecursively()
    }
  }
}
