package com.agj.android.vision

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.guide.Glb
import com.agj.android.guide.Guide
import com.agj.android.guide.Manifest
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs

/**
 * The Kotlin port of server/perception/panel.py against numbers exported from the Python reference
 * (`panel_pose_ref.json`, written by tools/markerless/export_kotlin_ref.py from the eight sample frames).
 */
class PanelPoseTest {
  private val ref: List<JsonObject> by lazy {
    val text = javaClass.classLoader!!.getResource("panel_pose_ref.json")!!.readText()
    Json.parseToJsonElement(text).jsonObject["rows"]!!.jsonArray.map { it.jsonObject }
  }

  private fun JsonObject.mat(key: String): DoubleArray =
      this[key]!!.jsonArray.flatMap { r -> r.jsonArray.map { it.jsonPrimitive.double } }.toDoubleArray()

  private fun JsonObject.num(key: String): Double = this[key]!!.jsonPrimitive.double

  private fun JsonArray.doubles(): DoubleArray = map { it.jsonPrimitive.double }.toDoubleArray()

  private fun JsonObject.view(): PanelView {
    val k = mat("K")
    return PanelMath.view(k[0], k[4], k[2], k[5], mat("T_table_cam"))!!
  }

  private fun pose(m: DoubleArray): Pose3 =
      Pose3(
          Vec3(m[3].toFloat(), m[7].toFloat(), m[11].toFloat()),
          Frames.matToQuat(arrayOf(doubleArrayOf(m[0], m[1], m[2]), doubleArrayOf(m[4], m[5], m[6]), doubleArrayOf(m[8], m[9], m[10]))),
      )

  // ---- rectification maths ----

  @Test
  fun homographyAndWindowMatchThePythonReference() {
    assertEquals(8, ref.size)
    for (row in ref) {
      val v = row.view()
      assertEquals(row["N"]!!.jsonPrimitive.int, v.n)
      assertEquals(row.num("x0"), v.x0, 1e-12)
      assertEquals(row.num("y0"), v.y0, 1e-12)
      val h = row.mat("H")
      // rows 0–1 are image pixels (1e-4 px), row 2 is depth in metres (1e-7 m). Not exact: the reference inverts
      // its float32-sourced, not exactly orthonormal pose with a general matrix inverse, the port with the rigid
      // inverse; on these rows that is about 1e-8 relative, 1e-5 px after the cancellation in the last column.
      for (i in 0 until 9) assertEquals("${row["id"]} H[$i]", h[i], v.h[i], if (i < 6) 1e-4 else 1e-7)
      val cam = row["cam_px"]!!.jsonArray.doubles()
      assertEquals(cam[0], v.camPx[0], 1e-8)
      assertEquals(cam[1], v.camPx[1], 1e-8)
      assertEquals(row.num("hc"), v.hc, 1e-12)
    }
  }

  @Test
  fun tableFromCameraFromPosesMatchesTheReference() {
    for (row in ref) {
      val tc = PanelMath.tableFromCamera(pose(row.mat("T_world_cam")), pose(row.mat("T_world_table")))
      val want = row.mat("T_table_cam")
      // Pose3 is single precision: 1e-5 on rotation entries, 10 µm on the translation
      for (i in 0 until 12) assertEquals("${row["id"]} T[$i]", want[i], tc[i], 2e-5)
    }
  }

  @Test
  fun cornersProjectLikeTheReference() {
    for (row in ref) {
      val out = row["out"]!!.jsonObject
      val k = row.mat("K")
      val got = PanelMath.cornersPx(out.num("x"), out.num("y"), out.num("yaw"), k[0], k[4], k[2], k[5], row.view())
      val want = out["corners_px"]!!.jsonArray.flatMap { it.jsonArray.doubles().toList() }
      for (i in 0 until 8) assertEquals("${row["id"]} corner $i", want[i], got[i], 1e-4)
    }
  }

  @Test
  fun cameraMustLookDownAtTheTable() {
    // camera 0.5 m above the table, optical axis 45° down: fine
    assertNotNull(PanelMath.view(870.0, 870.0, 640.0, 480.0, lookingDown45()))
    // lower than 5 cm above the top face
    val low = lookingDown45().also { it[11] = 0.06 }
    assertNull(PanelMath.view(870.0, 870.0, 640.0, 480.0, low))
    // optical axis only 5° below horizontal (the gate is about 9°)
    val a = Math.toRadians(5.0)
    val grazing = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -sin(a), cos(a), -0.5, 0.0, -cos(a), -sin(a), 0.5, 0.0, 0.0, 0.0, 1.0)
    assertFalse(PanelMath.looksDown(grazing))
    assertNull(PanelMath.view(870.0, 870.0, 640.0, 480.0, grazing))
  }

  /** TABLE ← CAMERA: camera at (0, −0.5, 0.5), x right, optical axis (0, 0.707, −0.707). */
  private fun lookingDown45(): DoubleArray {
    val r = sqrt(0.5)
    return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -r, r, -0.5, 0.0, -r, -r, 0.5, 0.0, 0.0, 0.0, 1.0)
  }

  @Test
  fun percentileIsNumpyLinear() {
    val v = floatArrayOf(1f, 2f, 4f, 8f)
    assertEquals(3.0, PanelMath.percentile(v, 50.0), 1e-9) // np.percentile([1, 2, 4, 8], 50)
    assertEquals(6.8, PanelMath.percentile(v, 90.0), 1e-6) // np.percentile([1, 2, 4, 8], 90)
    assertEquals(8.0, PanelMath.percentile(v, 100.0), 1e-9)
  }

  // ---- smear correction and the far-corner anchor ----

  /** Bird-pixel box of a table-frame rectangle x0..x1 × y0..y1. */
  private fun box(v: PanelView, x0: Double, y0: Double, x1: Double, y1: Double): DoubleArray {
    fun px(x: Double, y: Double) = listOf((x - v.x0) / v.s, (y - v.y0) / v.s)
    return (px(x0, y0) + px(x1, y0) + px(x1, y1) + px(x0, y1)).toDoubleArray()
  }

  @Test
  fun smearTowardsTheCameraIsRemovedAndTheFarCornerAnchorsThePose() {
    val v = PanelMath.view(870.0, 870.0, 640.0, 480.0, lookingDown45())!!
    assertEquals(0.484, v.hc, 1e-12)
    // panel centred at (0.10, 0.05), yaw 0: top face x −0.09…0.29, y −0.015…0.115. The camera (0, −0.5, 0.5) sees
    // the side face at y = −0.015: its table edge lands THICK × 0.485 / 0.5 = 15.52 mm nearer the camera.
    val smear = 0.016 * 0.485 / 0.5
    val b = box(v, -0.09, -0.015 - smear, 0.29, 0.115)
    val area = (0.38 / v.s) * ((0.13 + smear) / v.s)
    assertTrue(PanelMath.areaOk(area, v))
    val c = PanelMath.candidate(b, area, v)!!
    assertEquals(0.0, c.errL, 1e-9)
    assertEquals(0.0, c.errS, 1e-9)
    assertEquals(1.0, c.rectness, 1e-9)
    assertEquals(0.0, c.score, 1e-6)
    val p = PanelMath.pose(c, v, null)
    assertEquals(0.10, p[0], 1e-9)
    assertEquals(0.05, p[1], 1e-9) // the blob's own centre would be 7.8 mm nearer the camera
    assertEquals(0.0, PanelConsensus.yawDiffMod180(p[2], 0.0), 1e-9)
    assertTrue(p[2] >= 0 && p[2] < Math.PI + 1e-12)
    // without the correction the short side would read 145.5 mm and fail the 12 mm gate
    assertEquals(PanelMath.REASON_SIZE, PanelMath.gate(c.errL, c.errS + smear, 1.0, 0.0, 50.0))
    // the same corners listed from another start and the other way round give the same pose
    val rolled = doubleArrayOf(b[6], b[7], b[4], b[5], b[2], b[3], b[0], b[1])
    val p2 = PanelMath.pose(PanelMath.candidate(rolled, area, v)!!, v, null)
    assertEquals(0.10, p2[0], 1e-9)
    assertEquals(0.05, p2[1], 1e-9)
  }

  @Test
  fun prevYawPicksTheNearerOfTheTwoOrientations() {
    val v = PanelMath.view(870.0, 870.0, 640.0, 480.0, lookingDown45())!!
    val c = PanelMath.candidate(box(v, -0.09, -0.015, 0.29, 0.115), (0.38 / v.s) * (0.13 / v.s), v)!!
    val free = PanelMath.pose(c, v, null)[2]
    assertEquals(free, PanelMath.pose(c, v, free + 0.3)[2], 1e-12)
    assertEquals(free + Math.PI, PanelMath.pose(c, v, free + Math.PI - 0.3)[2], 1e-12)
    assertEquals(free + Math.PI, PanelMath.pose(c, v, free - Math.PI + 0.3)[2], 1e-12)
    for (row in ref) {
      val out = row["out"]!!.jsonObject
      // the export asked again with prev_yaw = yaw + π − 0.3
      assertEquals(out.num("yaw") + Math.PI, row.num("yaw_with_prev"), 1e-6)
    }
  }

  @Test
  fun areaWindow() {
    val v = PanelMath.view(870.0, 870.0, 640.0, 480.0, lookingDown45())!!
    val a = PanelMath.panelAreaPx(v)
    assertEquals(152.0 * 52.0, a, 1e-6)
    assertFalse(PanelMath.areaOk(0.59 * a, v))
    assertTrue(PanelMath.areaOk(0.61 * a, v))
    assertTrue(PanelMath.areaOk(2.19 * a, v))
    assertFalse(PanelMath.areaOk(2.21 * a, v))
  }

  // ---- gates ----

  @Test
  fun gatesInTheReferenceOrder() {
    assertNull(PanelMath.gate(0.0139, -0.0119, 0.86, 0.03, 18.0))
    assertEquals(PanelMath.REASON_SIZE, PanelMath.gate(0.0141, 0.0, 1.0, 0.0, 50.0))
    assertEquals(PanelMath.REASON_SIZE, PanelMath.gate(0.0, -0.0121, 1.0, 0.0, 50.0))
    assertEquals(PanelMath.REASON_RECT, PanelMath.gate(0.0, 0.0, 0.859, 0.0, 50.0))
    assertEquals(PanelMath.REASON_CUT, PanelMath.gate(0.0, 0.0, 0.9, 0.031, 50.0))
    assertEquals(PanelMath.REASON_CONTRAST, PanelMath.gate(0.0, 0.0, 0.9, 0.0, 17.9))
    // size wins over everything, then rectness, then the cut, then contrast
    assertEquals(PanelMath.REASON_SIZE, PanelMath.gate(0.02, 0.0, 0.5, 0.5, 0.0))
    assertEquals(PanelMath.REASON_RECT, PanelMath.gate(0.0, 0.0, 0.5, 0.5, 0.0))
    assertEquals(PanelMath.REASON_CUT, PanelMath.gate(0.0, 0.0, 0.9, 0.5, 0.0))
    // the constants are the reference's
    val p = PanelParams()
    assertEquals(listOf(0.380, 0.130, 0.016, 2.5, 0.7, 0.014, 0.012, 0.86, 18.0), listOf(p.longM, p.shortM, p.thickM, p.mmPerPx, p.halfM, p.tolLongM, p.tolShortM, p.minRectness, p.minContrast))
    assertEquals(listOf(50.0, 60.0, 70.0, 78.0, 85.0, 90.0, 94.0, 97.0), p.percentiles)
  }

  @Test
  fun referenceRowsPassThroughTheSameGates() {
    // the candidate numbers Python reported → the same trusted / reason through the Kotlin gate (cut is not exported:
    // every sample blob is inside the view)
    for (row in ref) {
      val out = row["out"]!!.jsonObject
      val e = out["size_err_mm"]!!.jsonArray.doubles()
      val reason = PanelMath.gate(e[0] / 1000, e[1] / 1000, out.num("rectness"), 0.0, out.num("contrast"))
      assertEquals(row["id"].toString(), out["reason"]!!.jsonPrimitive.content, reason ?: PanelMath.REASON_OK)
      assertEquals(out["trusted"]!!.jsonPrimitive.boolean, reason == null)
    }
  }

  // ---- three in a row ----

  private fun sample(x: Double, y: Double, yawDeg: Double, t: Long, score: Double = 0.4) =
      PanelSample(x, y, Math.toRadians(yawDeg), 0.74f, score, t)

  @Test
  fun threeAgreeingTrustedResultsGiveTheirMedian() {
    val c = PanelConsensus()
    assertNull(c.add(sample(0.100, 0.200, 30.0, 0)))
    assertNull(c.add(sample(0.104, 0.198, 31.0, 300)))
    val m = c.add(sample(0.102, 0.203, 29.5, 600))!!
    assertEquals(0.102, m.x, 1e-12)
    assertEquals(0.200, m.y, 1e-12)
    assertEquals(30.0, Math.toDegrees(m.yaw), 1e-9)
    assertEquals(0.74f, m.tableY, 0f)
    // sliding: the next agreeing one reports again (median of the last three)
    assertEquals(0.103, c.add(sample(0.103, 0.200, 30.0, 900))!!.x, 1e-12)
  }

  @Test
  fun anUntrustedResultBreaksTheRow() {
    val c = PanelConsensus()
    c.add(sample(0.1, 0.2, 30.0, 0))
    c.add(sample(0.1, 0.2, 30.0, 300))
    assertNull(c.add(null))
    assertEquals(0, c.streak)
    assertNull(c.add(sample(0.1, 0.2, 30.0, 900)))
    assertNull(c.add(sample(0.1, 0.2, 30.0, 1200)))
    assertNotNull(c.add(sample(0.1, 0.2, 30.0, 1500)))
  }

  @Test
  fun disagreementBeyond10mmOr3DegreesDoesNotReport() {
    val c = PanelConsensus()
    c.add(sample(0.100, 0.2, 30.0, 0))
    c.add(sample(0.100, 0.2, 30.0, 300))
    assertNull(c.add(sample(0.111, 0.2, 30.0, 600))) // 11 mm from the first two
    assertNull(c.add(sample(0.111, 0.2, 30.0, 900))) // window: 0.100, 0.111, 0.111
    assertNotNull(c.add(sample(0.111, 0.2, 30.0, 1200)))
    c.reset()
    c.add(sample(0.1, 0.2, 30.0, 0))
    c.add(sample(0.1, 0.2, 31.0, 300))
    assertNull(c.add(sample(0.1, 0.2, 33.5, 600))) // 3.5° from the first
    c.reset()
    c.add(sample(0.1, 0.2, 30.0, 0))
    c.add(sample(0.1, 0.2, 32.9, 300))
    assertNotNull(c.add(sample(0.1, 0.2, 31.0, 600)))
  }

  @Test
  fun yawAgreesModulo180AndOldSamplesAreForgotten() {
    val c = PanelConsensus()
    c.add(sample(0.1, 0.2, 179.5, 0))
    c.add(sample(0.1, 0.2, 0.5, 300)) // the same rectangle, 1° away
    val m = c.add(sample(0.1, 0.2, 180.0, 600))!!
    assertEquals(180.0, Math.toDegrees(m.yaw), 1e-9) // median near the newest: 179.5, 180.5, 180
    c.reset()
    c.add(sample(0.1, 0.2, 30.0, 0))
    c.add(sample(0.1, 0.2, 30.0, 300))
    assertNull(c.add(sample(0.1, 0.2, 30.0, 5000))) // the first two are older than 3 s
    assertEquals(1, c.streak)
  }

  // ---- panel → assembly → world ----

  @Test
  fun assemblyFromPanelComesFromTheMarkerPlacementNumbers() {
    val a = PanelFrames.ASSEMBLY_FROM_PANEL
    // 15 mm from the strip centre to the middle of the panel, 16 mm down to the table, in MARKER axes
    val viaMarker = MarkerPlacement.MALM_DRAWER_FRONT * Pose3(Vec3(0f, 0.015f, -0.016f))
    assertEquals(0.0, (a.t - viaMarker.t).length().toDouble(), 1e-6)
    assertEquals(0.0, Frames.angleDeg(a.q, viaMarker.q), 1e-3)
    assertEquals(0.0, (a.t - Vec3(0f, 0.05f, 0.016f)).length().toDouble(), 1e-6)
    // PANEL +X = ASSEMBLY +X (380 mm side), PANEL +Z (up) = ASSEMBLY −Z (inside face up)
    assertEquals(0.0, (a.q.rotate(Vec3(1f, 0f, 0f)) - Vec3(1f, 0f, 0f)).length().toDouble(), 1e-6)
    assertEquals(0.0, (a.q.rotate(Vec3(0f, 0f, 1f)) - Vec3(0f, 0f, -1f)).length().toDouble(), 1e-6)
    // the four corners of the outside face (ASSEMBLY Z = 0.016) are PANEL (±0.19, ±0.065, 0): on the table
    val inv = a.inverse()
    for (x in listOf(-0.19f, 0.19f)) for (y in listOf(-0.015f, 0.115f)) {
      val p = inv.transform(Vec3(x, y, 0.016f))
      assertEquals(0.19, abs(p.x).toDouble(), 1e-6)
      assertEquals(0.065, abs(p.y).toDouble(), 1e-6)
      assertEquals(0.0, p.z.toDouble(), 1e-6)
    }
  }

  @Test
  fun tableFrameIsLevelAndRightHanded() {
    val t = PanelFrames.tableAtHeight(0.74f)
    assertEquals(0.0, (t.transform(Vec3(0f, 0f, 0f)) - Vec3(0f, 0.74f, 0f)).length().toDouble(), 1e-6)
    assertEquals(0.0, (t.q.rotate(Vec3(0f, 0f, 1f)) - Vec3(0f, 1f, 0f)).length().toDouble(), 1e-6) // normal = up
    assertEquals(0.0, (t.q.rotate(Vec3(1f, 0f, 0f)) - Vec3(1f, 0f, 0f)).length().toDouble(), 1e-6)
    assertEquals(0.0, (t.q.rotate(Vec3(0f, 1f, 0f)) - Vec3(0f, 0f, -1f)).length().toDouble(), 1e-6)
  }

  @Test
  fun worldFromAssemblyPutsTheFrontFlatOnTheTable() {
    val table = PanelFrames.tableAtHeight(0.74f)
    val yaw = Math.toRadians(25.0)
    val w = PanelFrames.worldFromAssembly(table, 0.30, 0.50, yaw)
    // centre of the outside face (ASSEMBLY (0, 0.05, 0.016)) = the panel centre on the table: TABLE (0.3, 0.5, 0)
    val onTable = w.transform(Vec3(0f, 0.05f, 0.016f))
    assertEquals(0.0, (onTable - Vec3(0.30f, 0.74f, -0.50f)).length().toDouble(), 1e-5)
    // inside face 16 mm above the table, facing up
    assertEquals(0.756, w.transform(Vec3(0f, 0.05f, 0f)).y.toDouble(), 1e-5)
    assertEquals(0.0, (w.q.rotate(Vec3(0f, 0f, -1f)) - Vec3(0f, 1f, 0f)).length().toDouble(), 1e-5)
    // the 380 mm side (ASSEMBLY +X) along the detector's yaw: TABLE (cos, sin, 0) = WORLD (cos, 0, −sin)
    val ax = w.q.rotate(Vec3(1f, 0f, 0f))
    assertEquals(0.0, (ax - Vec3(cos(yaw).toFloat(), 0f, -sin(yaw).toFloat())).length().toDouble(), 1e-5)
    // every corner of the top face is where the detector's corners are (TABLE z = 16 mm)
    assertEquals(0.0, (w.transform(Vec3(0.19f, -0.015f, 0f)) - table.transform(Vec3((0.30 + 0.19 * cos(yaw) - 0.065 * sin(yaw)).toFloat(), (0.50 + 0.19 * sin(yaw) + 0.065 * cos(yaw)).toFloat(), 0.016f))).length().toDouble(), 1e-5)
    // and back: the ghost's pose → prevYaw
    assertEquals(yaw, PanelFrames.panelYawInTable(table, w), 1e-5)
    val flipped = PanelFrames.worldFromAssembly(table, 0.30, 0.50, yaw + Math.PI)
    assertEquals(0.0, PanelConsensus.yawDiffMod180(PanelFrames.panelYawInTable(table, flipped), yaw), 1e-5)
    assertEquals(0.0, (flipped.transform(Vec3(0f, 0.05f, 0.016f)) - onTable).length().toDouble(), 1e-5)
  }

  @Test
  fun theGuidesStepOnePoseIsTheFrontLyingFlat() {
    // WorldModule.onObjectPose turns our WORLD ← ASSEMBLY into the anchor with the step's tableFromAssembly: for the
    // real MALM guide that anchor must come out level, i.e. step 1 really holds the front inside face up.
    fun res(name: String) = File(javaClass.classLoader!!.getResource("malm-drawer/$name")!!.toURI())
    val guide = Guide.build(Manifest.parse(res("manifest.json").readText()), Glb.read(res("walkthrough.glb")))
    val held = guide.steps.first().tableFromAssembly
    val table = PanelFrames.tableAtHeight(0.74f)
    val anchor = PanelFrames.worldFromAssembly(table, 0.1, 0.4, 0.6) * held.inverse()
    val up = anchor.q.rotate(Vec3(0f, 1f, 0f))
    val rest = held.transform(Vec3(0f, 0.05f, 0.016f)).y
    println("panel → guide anchor: up $up, anchor origin ${anchor.t.y - 0.74f} m above the table, outside face at TABLE y $rest, held $held")
    assertEquals(1.0, up.y.toDouble(), 1e-4)
    // the anchor (guide TABLE spot) sits exactly on the table, and the front's outside face rests on it
    assertEquals(0.74, anchor.t.y.toDouble(), 1e-4)
    assertEquals(0.0, rest.toDouble(), 1e-4)
  }

  // ---- tracker ----

  private class Got(val pose: Pose3, val confidence: Float, val source: String)

  private fun tracker(got: MutableList<Got>, lines: MutableList<String>, now: () -> Long, ghost: () -> Pose3? = { null }, height: () -> Float? = { 0.74f }) =
      PanelPoseTracker(
          tableHeight = height,
          ghostPose = ghost,
          onObjectPose = { p, c, s -> got += Got(p, c, s) },
          log = AgqLog(now, { lines += it }),
          loadNative = { throw AssertionError("the detector must not run in this test") },
          clockMs = now,
      )

  private fun trusted(x: Double, y: Double, yawDeg: Double, score: Double = 0.4) =
      PanelPoseResult(true, "ok", x, y, Math.toRadians(yawDeg), DoubleArray(8), 21.0, doubleArrayOf(2.0, 1.0), 0.95, 60.0, score)

  private fun untrusted(reason: String) = PanelPoseResult(false, reason, 0.1, 0.4, 0.5, DoubleArray(8), 20.0, doubleArrayOf(30.0, 1.0), 0.95, 60.0, 2.5)

  @Test
  fun trackerReportsTheMedianAfterThreeTrustedInARow() {
    val got = ArrayList<Got>()
    val lines = ArrayList<String>()
    var t = 0L
    val tr = tracker(got, lines, { t })
    tr.setJob("malm-drawer")
    val table = PanelFrames.tableAtHeight(0.74f)
    tr.onResult(trusted(0.100, 0.400, 20.0), table, t)
    t = 333
    tr.onResult(trusted(0.104, 0.402, 21.0), table, t)
    assertTrue(got.isEmpty())
    assertNull(tr.latest)
    t = 666
    tr.onResult(trusted(0.102, 0.398, 20.5), table, t)
    assertEquals(1, got.size)
    assertEquals("markerless", got[0].source)
    assertEquals(0.9f, got[0].confidence, 1e-6f) // 1 − 0.25 × 0.4
    val want = PanelFrames.worldFromAssembly(table, 0.102, 0.400, Math.toRadians(20.5))
    assertEquals(0.0, (got[0].pose.t - want.t).length().toDouble(), 1e-6)
    assertEquals(0.0, Frames.angleDeg(got[0].pose.q, want.q), 1e-3)
    assertEquals(want, tr.latest)
    // the same pose again: nothing new to say (no shimmer) …
    t = 1000
    tr.onResult(trusted(0.102, 0.400, 20.5), table, t)
    assertEquals(1, got.size)
    // … an untrusted frame breaks the row, and it takes three more
    t = 1333
    tr.onResult(untrusted(PanelMath.REASON_SIZE), table, t)
    for (i in 0 until 2) {
      t += 333
      tr.onResult(trusted(0.150, 0.400, 20.5), table, t)
    }
    assertEquals(1, got.size)
    t += 333
    tr.onResult(trusted(0.150, 0.400, 20.5), table, t)
    assertEquals(2, got.size)
    assertEquals(0.150f, got[1].pose.transform(Vec3(0f, 0.05f, 0.016f)).x, 1e-5f)
  }

  @Test
  fun trackerLogsPanelAtMostOnceASecond() {
    val got = ArrayList<Got>()
    val lines = ArrayList<String>()
    var t = 0L
    val tr = tracker(got, lines, { t })
    tr.setJob("malm-drawer")
    val table = PanelFrames.tableAtHeight(0.74f)
    while (t < 3000) {
      tr.onResult(if (t < 1500) trusted(0.1, 0.4, 20.0) else untrusted(PanelMath.REASON_RECT), table, t)
      t += 250
    }
    val events = lines.map { Json.parseToJsonElement(it).jsonObject }.filter { it["ev"]!!.jsonPrimitive.content == "panel" }
    assertEquals(3, events.size) // t = 0, 1000, 2000
    val first = events[0]
    assertTrue(first["trusted"]!!.jsonPrimitive.boolean)
    assertEquals("ok", first["reason"]!!.jsonPrimitive.content)
    assertEquals(21.0, first["ms"]!!.jsonPrimitive.double, 1e-9)
    val pos = first["pos_m"]!!.jsonArray.map { it.jsonPrimitive.double }
    assertEquals(listOf(0.1, 0.74, -0.4), pos.map { Math.round(it * 1e4) / 1e4 }) // WORLD: panel centre on the table
    assertFalse(events[2]["trusted"]!!.jsonPrimitive.boolean)
    assertEquals(PanelMath.REASON_RECT, events[2]["reason"]!!.jsonPrimitive.content)
    assertEquals(0, events[2]["streak"]!!.jsonPrimitive.int)
  }

  private fun frame(i: Long, worldFromCamera: Pose3?) =
      CameraFrame(i, ByteArray(0), 1280, 960, i * 33_000_000L, Intrinsics(870.0, 870.0, 640.0, 480.0, 1280, 960), worldFromCamera)

  @Test
  fun trackerStaysOffForOtherJobsAndWhenTheCameraDoesNotLookDown() {
    val got = ArrayList<Got>()
    val lines = ArrayList<String>()
    var t = 0L
    val tr = tracker(got, lines, { t })
    // camera 0.5 m above the table surface looking straight ahead (WORLD −Z): OpenCV axes = 180° about X
    val ahead = Pose3(Vec3(0f, 1.24f, 0f), Quat(1f, 0f, 0f, 0f))
    tr.onFrame(frame(0, ahead)) // no job yet
    tr.setJob("knagglig")
    assertFalse(tr.active)
    tr.onFrame(frame(1, ahead))
    assertTrue(lines.isEmpty())
    tr.setJob("malm-drawer")
    assertTrue(tr.active)
    tr.enabled = false
    tr.onFrame(frame(2, ahead))
    assertTrue(lines.isEmpty())
    tr.enabled = true
    tr.onFrame(frame(3, ahead)) // loadNative would throw if the detector ran
    assertEquals(1, lines.size)
    assertEquals(PanelMath.REASON_NOT_LOOKING_DOWN, Json.parseToJsonElement(lines[0]).jsonObject["reason"]!!.jsonPrimitive.content)
    t = 2000
    tr.onFrame(frame(4, null))
    assertEquals("no camera pose", Json.parseToJsonElement(lines[1]).jsonObject["reason"]!!.jsonPrimitive.content)
    assertTrue(got.isEmpty())
    tr.stop()
  }

  @Test
  fun trackerWithoutATableDoesNothingButSaySo() {
    val got = ArrayList<Got>()
    val lines = ArrayList<String>()
    val tr = tracker(got, lines, { 0L }, height = { null })
    tr.setJob("malm-drawer")
    tr.onFrame(frame(0, Pose3(Vec3(0f, 1.24f, 0f), Quat(1f, 0f, 0f, 0f))))
    assertEquals("no table", Json.parseToJsonElement(lines.single()).jsonObject["reason"]!!.jsonPrimitive.content)
    assertTrue(got.isEmpty())
  }

  // ---- the whole detector on the sample frames (desktop OpenCV) ----

  private fun repoRoot(): File? {
    var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (d != null && !File(d, "tools/markerless/sample/img").isDirectory) d = d.parentFile
    return d
  }

  private fun loadOpenCv(): Boolean =
      try {
        nu.pattern.OpenCV.loadLocally()
        true
      } catch (t: Throwable) {
        System.err.println("desktop OpenCV did not load: $t")
        false
      }

  @Test
  fun fullDetectorMatchesThePythonReferenceOnTheSampleFrames() {
    val root = repoRoot()
    assumeTrue("tools/markerless/sample not found", root != null)
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())
    var trustedRows = 0
    for (row in ref) {
      val id = row["id"]!!.jsonPrimitive.content
      val img = Imgcodecs.imread(File(root, "tools/markerless/sample/img/$id.jpg").path, Imgcodecs.IMREAD_COLOR)
      assertFalse("$id did not load", img.empty())
      val k = row.mat("K")
      val intr = Intrinsics(k[0], k[4], k[2], k[5], img.cols(), img.rows())
      val out = row["out"]!!.jsonObject
      val r = PanelPose.detect(img, intr, row.mat("T_table_cam"))
      val viaPoses = PanelPose.detect(img, intr, pose(row.mat("T_world_cam")), pose(row.mat("T_world_table")))
      img.release()
      val e = out["size_err_mm"]!!.jsonArray.doubles()
      println("panel %s state %d: kotlin trusted=%s '%s' x=%.4f y=%.4f yaw=%.4f err=[%.1f, %.1f] rect=%.3f contrast=%.1f %.0f ms | python trusted=%s x=%.4f y=%.4f yaw=%.4f err=[%.1f, %.1f] rect=%.3f contrast=%.1f"
          .format(id, row["state"]!!.jsonPrimitive.int, r.trusted, r.reason, r.x, r.y, r.yaw, r.sizeErrMm!![0], r.sizeErrMm!![1], r.rectness, r.contrast, r.ms,
              out["trusted"]!!.jsonPrimitive.boolean, out.num("x"), out.num("y"), out.num("yaw"), e[0], e[1], out.num("rectness"), out.num("contrast")))
      assertEquals(id, out["trusted"]!!.jsonPrimitive.boolean, r.trusted)
      assertEquals(id, out["reason"]!!.jsonPrimitive.content, r.reason)
      // the same candidate as Python: 1 mm, 0.2°, 1 px (JPEG decoders and OpenCV builds differ slightly)
      assertEquals("$id x", out.num("x"), r.x!!, 1e-3)
      assertEquals("$id y", out.num("y"), r.y!!, 1e-3)
      assertEquals("$id yaw", 0.0, Math.toDegrees(PanelConsensus.yawDiffMod180(out.num("yaw"), r.yaw!!)), 0.2)
      assertEquals("$id err long", e[0], r.sizeErrMm!![0], 1.0)
      assertEquals("$id err short", e[1], r.sizeErrMm!![1], 1.0)
      assertEquals("$id rectness", out.num("rectness"), r.rectness!!, 0.02)
      assertEquals("$id contrast", out.num("contrast"), r.contrast!!, 2.0)
      val corners = out["corners_px"]!!.jsonArray.flatMap { it.jsonArray.doubles().toList() }
      for (i in 0 until 8) assertEquals("$id corner $i", corners[i], r.cornersPx!![i], 1.5)
      // the Pose3 entry point (single precision) lands on the same result
      assertEquals(r.trusted, viaPoses.trusted)
      assertEquals("$id x via poses", r.x!!, viaPoses.x!!, 5e-4)
      assertEquals("$id y via poses", r.y!!, viaPoses.y!!, 5e-4)
      if (r.trusted) {
        trustedRows++
        // against the ground truth: inside the reference's measured envelope (max 17 mm, yaw p90 0.43°)
        val wt = row.mat("T_world_table")
        val gt = row.mat("gt_T_world_obj")
        val wx = wt[0] * r.x!! + wt[1] * r.y!! + wt[3]
        val wy = wt[4] * r.x!! + wt[5] * r.y!! + wt[7]
        val wz = wt[8] * r.x!! + wt[9] * r.y!! + wt[11]
        val mm = sqrt((wx - gt[3]) * (wx - gt[3]) + (wy - gt[7]) * (wy - gt[7]) + (wz - gt[11]) * (wz - gt[11])) * 1000
        val yawErr = Math.toDegrees(PanelConsensus.yawDiffMod180(r.yaw!!, row["gt_obj_xy_yaw"]!!.jsonArray.doubles()[2]))
        println("panel $id vs ground truth: %.1f mm, %.2f deg".format(mm, yawErr))
        assertTrue("$id $mm mm from the ground truth", mm < 17.0)
        assertTrue("$id yaw $yawErr deg from the ground truth", yawErr < 1.0)
      }
    }
    assertEquals(2, trustedRows) // s00_f010 and s02_f012 are the panel alone; s00_f023 has a hand over it; five are later states
  }

  /**
   * Optional: the whole render set (git-ignored). `AGQ_PANEL_DATASET` = the folder with `img/`, `AGQ_PANEL_REF` = the
   * JSON that `tools/markerless/export_kotlin_ref.py <folder> <json>` wrote for it. Skipped without them.
   */
  @Test
  fun fullDetectorAgreesWithPythonOnTheRenderSet() {
    val dir = System.getenv("AGQ_PANEL_DATASET")?.let { File(it) }
    val refFile = System.getenv("AGQ_PANEL_REF")?.let { File(it) }
    assumeTrue("AGQ_PANEL_DATASET / AGQ_PANEL_REF not set", dir != null && refFile != null && refFile.exists())
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())
    val rows = Json.parseToJsonElement(refFile!!.readText()).jsonObject["rows"]!!.jsonArray.map { it.jsonObject }
    var same = 0
    var bothTrusted = 0
    var kotlinTrusted = 0
    var pythonTrusted = 0
    var falselyTrusted = 0
    var onlyKotlin = 0
    var maxMm = 0.0
    val gtMm = ArrayList<Double>()
    val ms = ArrayList<Double>()
    for (row in rows) {
      val id = row["id"]!!.jsonPrimitive.content
      val img = Imgcodecs.imread(File(dir, "img/$id.jpg").path, Imgcodecs.IMREAD_COLOR)
      val k = row.mat("K")
      val r = PanelPose.detect(img, Intrinsics(k[0], k[4], k[2], k[5], img.cols(), img.rows()), row.mat("T_table_cam"))
      img.release()
      ms += r.ms
      val out = row["out"]!!.jsonObject
      val py = out["trusted"]!!.jsonPrimitive.boolean
      val pyReason = out["reason"]!!.jsonPrimitive.content
      val state = row["state"]!!.jsonPrimitive.int
      if (py) pythonTrusted++
      if (r.trusted) kotlinTrusted++
      if (py == r.trusted && pyReason == r.reason) same++ else println("panel $id differs: kotlin ${r.trusted} '${r.reason}', python $py '$pyReason'")
      if (r.trusted && state != 1) {
        falselyTrusted++
        println("panel $id (state $state) trusted by kotlin (python: $py): err ${r.sizeErrMm?.toList()} rect ${r.rectness} contrast ${r.contrast}")
      }
      if (r.trusted && !py) onlyKotlin++
      if (py && r.trusted) {
        bothTrusted++
        val dx = out.num("x") - r.x!!
        val dy = out.num("y") - r.y!!
        maxMm = maxOf(maxMm, sqrt(dx * dx + dy * dy) * 1000)
      }
      if (r.trusted && state == 1) {
        val wt = row.mat("T_world_table")
        val gt = row.mat("gt_T_world_obj")
        val d = (0 until 3).map { wt[4 * it] * r.x!! + wt[4 * it + 1] * r.y!! + wt[4 * it + 3] - gt[4 * it + 3] }
        gtMm += sqrt(d.sumOf { it * it }) * 1000
      }
    }
    gtMm.sort()
    ms.sort()
    println("panel render set: ${rows.size} frames, same trusted+reason $same, trusted kotlin $kotlinTrusted / python $pythonTrusted, both $bothTrusted " +
        "(max kotlin-python %.2f mm), kotlin trusted on later states $falselyTrusted, kotlin vs ground truth p50 %.1f p90 %.1f max %.1f mm, %.0f ms p50 / %.0f ms p90"
            .format(maxMm, gtMm[gtMm.size / 2], gtMm[gtMm.size * 9 / 10], gtMm.last(), ms[ms.size / 2], ms[ms.size * 9 / 10]))
    assertTrue("same $same of ${rows.size}", same >= rows.size * 0.98)
    // the port must not trust anything the reference does not (on these un-degraded frames the reference itself
    // trusts one later-state frame; the evaluation in FRONT_PANEL.md is on degraded images)
    assertEquals(0, onlyKotlin)
    assertTrue("kotlin-python $maxMm mm", maxMm < 2.0)
  }
}
