package com.agj.android.vision

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Quat
import com.agj.android.core.Vec3
import com.agj.android.world.Board
import com.agj.android.world.FlatMatch
import com.agj.android.world.FlatParts
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** FLAT-PARTS: every rectangle on the surface in one pass ([PanelPose.findRects]) and the tracker's rectangle pass. */
class PanelRectsTest {
  private fun found(x: Double, y: Double, yawDeg: Double, l: Double, w: Double, rectness: Double = 0.92) =
      Math.toRadians(yawDeg).let { yaw -> FoundRect(x, y, yaw, l, w, rectness, 45.0, DoubleArray(8), PanelMath.rectCorners(x, y, yaw, l, w), 0.016) }

  // ---- pure ----

  @Test
  fun oneBlobAtSeveralThresholdsIsOneRectangleTheCleanest() {
    val a = found(0.100, 0.200, 10.0, 0.450, 0.100, 0.90)
    val b = found(0.104, 0.203, 10.5, 0.458, 0.106, 0.94) // the same board at a lower threshold
    val c = found(0.600, 0.200, 80.0, 0.350, 0.100, 0.88) // another board
    val out = PanelMath.resolveRects(listOf(a, b, c))
    assertEquals(2, out.size)
    assertTrue(out.contains(b))
    assertTrue(out.contains(c))
    assertFalse(out.contains(a))
  }

  @Test
  fun aRectangleMadeOfTwoBoardsGoesAndOneThatHidesAnotherReadingTakesItAlong() {
    // two sides 10 mm apart: a low threshold joins them into 450 × 210 (which would pass for the 400 × 200 front)
    val s1 = found(0.0, 0.055, 0.0, 0.450, 0.100)
    val s2 = found(0.0, -0.055, 0.0, 0.450, 0.100)
    val both = found(0.0, 0.0, 0.0, 0.450, 0.210, 0.95)
    assertEquals(setOf(s1, s2), PanelMath.resolveRects(listOf(both, s1, s2)).toSet())
    // a hard shadow across one board: the lit part and the whole board are both clean rectangles, 100 mm apart in
    // length: there is no telling which is the part, so neither is reported
    val whole = found(0.0, 0.5, 0.0, 0.450, 0.100)
    val litPart = found(-0.05, 0.5, 0.0, 0.350, 0.100)
    assertTrue(PanelMath.resolveRects(listOf(whole, litPart)).isEmpty())
    // boards next to each other are left alone
    assertEquals(2, PanelMath.resolveRects(listOf(s1, found(0.0, 0.5, 30.0, 0.350, 0.100))).size)
  }

  @Test
  fun foundRectContainsAndCorners() {
    val r = found(0.1, 0.4, 90.0, 0.450, 0.100)
    assertTrue(r.contains(0.1, 0.6)) // 200 mm along the long side, which points along TABLE +Y
    assertFalse(r.contains(0.3, 0.4))
    val c = r.cornersTable
    assertEquals(0.15, c[0], 1e-9) // (−,−): x = 0.1 − cos90·L/2 + sin90·W/2
    assertEquals(0.175, c[1], 1e-9)
    assertEquals(0.05, c[4], 1e-9) // (+,+)
    assertEquals(0.625, c[5], 1e-9)
  }

  // ---- the tracker's rectangle pass (no OpenCV: the passes that do not run) ----

  private fun frame(i: Long, worldFromCamera: Pose3?) =
      CameraFrame(i, ByteArray(0), 1280, 960, i * 33_000_000L, Intrinsics(870.0, 870.0, 640.0, 480.0, 1280, 960), worldFromCamera)

  @Test
  fun theRectanglePassRunsWithoutATargetAndSaysWhyItDidNotLook() {
    var t = 0L
    var height: Float? = null
    val lines = ArrayList<String>()
    val tr =
        PanelPoseTracker(tableHeight = { height }, onObjectPose = { _, _, _ -> }, log = AgqLog({ t }, { lines += it }),
            loadNative = { throw IllegalStateException("the detector must not run") }, clockMs = { t })
    val got = ArrayList<Pair<RectsResult, Pose3?>>()
    var wanted = false
    tr.rectsWanted = { wanted }
    tr.onRects = { r, wt -> got += r to wt }
    val ahead = Pose3(Vec3(0f, 1.24f, 0f), Quat(1f, 0f, 0f, 0f)) // looking straight ahead
    tr.setJob("another-job") // the tracker's own target is off for this job
    assertFalse(tr.active)
    tr.onFrame(frame(0, ahead))
    assertTrue(got.isEmpty()) // nobody wants rectangles
    wanted = true
    t = 1000
    tr.onFrame(frame(1, ahead))
    assertEquals("no table", got.single().first.reason)
    assertEquals(null, got.single().second)
    height = 0.74f
    t = 1100
    tr.onFrame(frame(2, ahead)) // 3 Hz: too soon
    assertEquals(1, got.size)
    t = 1400
    tr.onFrame(frame(3, ahead))
    assertEquals(PanelMath.REASON_NOT_LOOKING_DOWN, got[1].first.reason)
    assertEquals(0.74f, got[1].second!!.t.y, 0f)
    t = 1800
    tr.onFrame(frame(4, null))
    assertEquals("no camera pose", got[2].first.reason)
    assertTrue(got.all { it.first.rects.isEmpty() })
    assertTrue(lines.none { it.contains("\"panel\"") }) // the target's own log stays silent: it is off
  }

  // ---- the whole finder (desktop OpenCV) ----

  private fun loadOpenCv(): Boolean =
      try {
        nu.pattern.OpenCV.loadLocally()
        true
      } catch (t: Throwable) {
        System.err.println("desktop OpenCV did not load: $t")
        false
      }

  private fun repoRoot(): File? {
    var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (d != null && !File(d, "tools/markerless/sample/img").isDirectory) d = d.parentFile
    return d
  }

  /** The real drawer by tape (15 Nov): front 400 × 200, two sides 450 × 100, back 350 × 100, bottom 350 × 450. */
  private val real =
      listOf(
          Board("drawer_front", "part_drawer_front_1", 0.400, 0.200, addedAt = 0),
          Board("drawer_side_left", "part_drawer_side_left_1", 0.450, 0.100, addedAt = 1001),
          Board("drawer_side_right", "part_drawer_side_right_1", 0.450, 0.100, addedAt = 1002),
          Board("drawer_bottom", "part_drawer_bottom_1", 0.450, 0.350, 0.003, addedAt = 3001),
          Board("drawer_back", "part_drawer_back_1", 0.350, 0.100, addedAt = 4001),
      )

  /** Camera 1.2 m above the surface looking straight down: CAMERA X = TABLE X, CAMERA Y = −TABLE Y, CAMERA Z = −TABLE Z. */
  private val down = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 1.2, 0.0, 0.0, 0.0, 1.0)
  private val k = Intrinsics(870.0, 870.0, 640.0, 480.0, 1280, 960)

  private class Plank(val x: Double, val y: Double, val yawDeg: Double, val l: Double, val w: Double, val bgr: Scalar = Scalar(235.0, 235.0, 235.0))

  /** A picture of [planks]' top faces (16 mm above the surface) on a dark floor, from the camera [down]. */
  private fun scene(planks: List<Plank>, floor: Scalar = Scalar(70.0, 66.0, 64.0)): Mat {
    val img = Mat(960, 1280, CvType.CV_8UC3, floor)
    for (p in planks) {
      val c = PanelMath.rectCorners(p.x, p.y, Math.toRadians(p.yawDeg), p.l, p.w)
      val pts = (0 until 4).map { Point(870.0 * c[2 * it] / (1.2 - 0.016) + 640.0, 870.0 * -c[2 * it + 1] / (1.2 - 0.016) + 480.0) }
      val poly = MatOfPoint(*pts.toTypedArray())
      Imgproc.fillConvexPoly(img, poly, p.bgr, Imgproc.LINE_AA)
      poly.release()
    }
    val out = Mat()
    Imgproc.GaussianBlur(img, out, Size(3.0, 3.0), 0.0)
    img.release()
    return out
  }

  private val four =
      listOf(
          Plank(-0.33, 0.25, 0.0, 0.450, 0.350), // bottom
          Plank(0.25, 0.30, 10.0, 0.400, 0.200, Scalar(90.0, 170.0, 215.0)), // the front: wood colour, not white
          Plank(-0.20, -0.25, 30.0, 0.450, 0.100), // a side
          Plank(0.33, -0.25, -20.0, 0.350, 0.100, Scalar(200.0, 205.0, 205.0)), // the back, light grey
      )

  @Test
  fun findsEveryBoardOfTheRealSizesInOnePassAndTellsThemApart() {
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())
    val img = scene(four)
    val kinds = FlatMatch.kinds(real)
    for (p in listOf(PanelParams(chromaWeight = 0.0), PanelParams())) { // lightness only (the device setting), and the reference's whiteness
      val r = PanelPose.findRects(img, k, down, p)
      println("flat synthetic (chroma weight %.1f): %d rectangles from %d candidates in %.0f ms: %s".format(p.chromaWeight, r.rects.size, r.candidates, r.ms,
          r.rects.joinToString { "%.0f x %.0f mm at (%.3f, %.3f) rectness %.2f contrast %.0f".format(it.lengthM * 1000, it.widthM * 1000, it.x, it.y, it.rectness, it.contrast) }))
      assertEquals(PanelMath.REASON_OK, r.reason)
      // lightness only: all four, the wood-coloured front too; with the reference's whiteness the three pale ones for sure
      val want = if (p.chromaWeight == 0.0) four else four.filter { it.bgr.`val`[0] > 150 }
      if (p.chromaWeight == 0.0) assertEquals(4, r.rects.size) else assertTrue(r.rects.size >= 3)
      for (w in want) {
        val f = r.rects.minByOrNull { sqrt((it.x - w.x) * (it.x - w.x) + (it.y - w.y) * (it.y - w.y)) }!!
        assertEquals("x of ${w.l} x ${w.w}", w.x, f.x, 0.004)
        assertEquals("y of ${w.l} x ${w.w}", w.y, f.y, 0.004)
        assertEquals("length of ${w.l} x ${w.w}", w.l, f.lengthM, 0.006)
        assertEquals("width of ${w.l} x ${w.w}", w.w, f.widthM, 0.006)
        assertEquals(0.0, Math.toDegrees(PanelConsensus.yawDiffMod180(Math.toRadians(w.yawDeg), f.yaw)), 1.0)
        assertTrue(f.rectness > 0.95)
        // the four corners in the image are where the plank was drawn
        val c = PanelMath.rectCorners(w.x, w.y, Math.toRadians(w.yawDeg), w.l, w.w)
        for (i in 0 until 4) {
          val u = 870.0 * c[2 * i] / (1.2 - 0.016) + 640.0
          val v = 870.0 * -c[2 * i + 1] / (1.2 - 0.016) + 480.0
          assertTrue("corner $i of ${w.l} x ${w.w}", (0 until 4).any { j -> abs(f.cornersPx[2 * j] - u) < 6 && abs(f.cornersPx[2 * j + 1] - v) < 6 })
        }
        // and each is its own kind of board
        val kind = FlatMatch.classify(f.lengthM, f.widthM, kinds)
        assertEquals(FlatMatch.classify(w.l, w.w, kinds)!!.name, kind?.name)
      }
    }
    // cost per pass on this machine
    val p = PanelParams(chromaWeight = 0.0)
    repeat(3) { PanelPose.findRects(img, k, down, p) }
    val t0 = System.nanoTime()
    repeat(10) { PanelPose.findRects(img, k, down, p) }
    val all = (System.nanoTime() - t0) / 1e7
    val t1 = System.nanoTime()
    repeat(10) { PanelPose.detect(img, k, down, null, p) }
    val one = (System.nanoTime() - t1) / 1e7
    println("flat cost on this machine: findRects %.1f ms per pass, the single-target detect %.1f ms".format(all, one))
    img.release()
  }

  @Test
  fun aBoardCutByTheViewOrLyingAgainstAnotherIsNotARectangle() {
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())
    val p = PanelParams(chromaWeight = 0.0)
    // half out of the camera picture (the picture ends at x = ±0.87 m at this height)
    var img = scene(listOf(Plank(0.80, 0.0, 0.0, 0.450, 0.100)))
    assertTrue(PanelPose.findRects(img, k, down, p).rects.isEmpty())
    img.release()
    // inside the picture but across the edge of the rectified window (±0.70 m): it would read as a shorter board
    img = scene(listOf(Plank(0.55, 0.0, 0.0, 0.450, 0.100)))
    assertTrue(PanelPose.findRects(img, k, down, p).rects.isEmpty())
    img.release()
    // a pale floor: nothing stands out
    img = scene(four, Scalar(215.0, 215.0, 215.0))
    assertTrue(PanelPose.findRects(img, k, down, p).rects.isEmpty())
    img.release()
    // a hand across a board: not a clean rectangle
    img = scene(listOf(Plank(0.0, 0.0, 0.0, 0.450, 0.100), Plank(0.05, 0.05, 70.0, 0.200, 0.090, Scalar(150.0, 170.0, 215.0))))
    val hand = PanelPose.findRects(img, k, down, p)
    assertTrue(hand.rects.joinToString { "${it.lengthM} x ${it.widthM}" }, hand.rects.isEmpty())
    img.release()
  }

  @Test
  fun twoBoardsLyingAgainstEachOtherAreNotOneBoard() {
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())
    val p = PanelParams(chromaWeight = 0.0)
    val kinds = FlatMatch.kinds(real)
    // the two 450 × 100 sides side by side with 3 mm between them: 450 × 203, which would pass for the 400 × 200 front
    for (gap in listOf(0.003, 0.006, 0.012, 0.030)) {
      val img = scene(listOf(Plank(0.0, 0.05 + gap / 2, 0.0, 0.450, 0.100), Plank(0.0, -0.05 - gap / 2, 0.0, 0.450, 0.100)))
      val r = PanelPose.findRects(img, k, down, p)
      println("flat two sides %.0f mm apart: %s".format(gap * 1000, r.rects.joinToString { "%.0f x %.0f".format(it.lengthM * 1000, it.widthM * 1000) }))
      for (f in r.rects) assertEquals("gap $gap: ${f.lengthM} x ${f.widthM}", "drawer_side_left+drawer_side_right", FlatMatch.classify(f.lengthM, f.widthM, kinds)?.name)
      if (gap >= 0.030) assertEquals(2, r.rects.size) // clear of each other: both are found
      img.release()
    }
    // a side against the back (350 × 100): 450 × 203 again, with a step at one end
    val img = scene(listOf(Plank(0.0, 0.0515, 0.0, 0.450, 0.100), Plank(-0.05, -0.0515, 0.0, 0.350, 0.100)))
    val r = PanelPose.findRects(img, k, down, p)
    assertTrue(r.rects.joinToString { "${it.lengthM} x ${it.widthM}" }, r.rects.none { FlatMatch.classify(it.lengthM, it.widthM, kinds)?.name == "drawer_front" })
    img.release()
    // the front's own groove, 30 mm from one long edge, does not make it two boards
    val front = scene(listOf(Plank(0.0, 0.0, 15.0, 0.400, 0.200, Scalar(90.0, 170.0, 215.0)), Plank(0.0185, 0.069, 15.0, 0.390, 0.004, Scalar(40.0, 60.0, 80.0))))
    val one = PanelPose.findRects(front, k, down, p).rects
    assertEquals(1, one.size)
    assertEquals("drawer_front", FlatMatch.classify(one[0].lengthM, one[0].widthM, kinds)?.name)
    front.release()
  }

  private fun JsonObject.mat(key: String): DoubleArray = this[key]!!.jsonArray.flatMap { r -> r.jsonArray.map { it.jsonPrimitive.double } }.toDoubleArray()

  @Test
  fun theSampleFrameOfTheOldFrontComesBackAsOneRectangleOfAboutItsSize() {
    val root = repoRoot()
    assumeTrue("tools/markerless/sample not found", root != null)
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())
    val ref = Json.parseToJsonElement(javaClass.classLoader!!.getResource("panel_pose_ref.json")!!.readText()).jsonObject["rows"]!!.jsonArray.map { it.jsonObject }
    for (id in listOf("s00_f010", "s02_f012")) { // the two frames with the panel alone (380 × 130 mm, the old model)
      val row = ref.first { it["id"]!!.jsonPrimitive.content == id }
      val img = Imgcodecs.imread(File(root, "tools/markerless/sample/img/$id.jpg").path, Imgcodecs.IMREAD_COLOR)
      assertFalse("$id did not load", img.empty())
      val km = row.mat("K")
      val intr = Intrinsics(km[0], km[4], km[2], km[5], img.cols(), img.rows())
      val out = row["out"]!!.jsonObject
      for (p in listOf(PanelParams(), PanelParams(chromaWeight = 0.0))) {
        val r = PanelPose.findRects(img, intr, row.mat("T_table_cam"), p)
        println("flat sample %s (chroma weight %.1f): %d rectangles from %d candidates in %.0f ms: %s".format(id, p.chromaWeight, r.rects.size, r.candidates, r.ms,
            r.rects.joinToString { "%.0f x %.0f mm at (%.3f, %.3f) rectness %.2f contrast %.0f".format(it.lengthM * 1000, it.widthM * 1000, it.x, it.y, it.rectness, it.contrast) }))
        val panels = r.rects.filter { abs(it.lengthM - 0.380) <= 0.015 && abs(it.widthM - 0.130) <= 0.015 }
        if (p.chromaWeight == 0.0) {
          // Lightness only (the device setting, for the wood-coloured front on a dark floor): this render's table is
          // pale wood, as light as the white panel, so the panel does not stand out. Nothing found is the right
          // answer there (the app then shows the server's boxes, as before); a wrong rectangle would not be.
          assertTrue("$id rectangles", r.rects.size <= 2 && panels.size <= 1)
          continue
        }
        // the reference's whiteness: one rectangle of the front's size (the frame also shows a sheet of paper on the
        // table, a clean pale rectangle too, and reported as one)
        assertEquals("$id rectangles of the front's size", 1, panels.size)
        assertTrue("$id rectangles", r.rects.size <= 2)
        val f = panels[0]
        // where the single-target detector (and the Python reference) puts it
        assertEquals("$id x", out["x"]!!.jsonPrimitive.double, f.x, 0.008)
        assertEquals("$id y", out["y"]!!.jsonPrimitive.double, f.y, 0.008)
        assertEquals("$id yaw", 0.0, Math.toDegrees(PanelConsensus.yawDiffMod180(out["yaw"]!!.jsonPrimitive.double, f.yaw)), 1.5)
      }
      img.release()
    }
  }

  /**
   * Not a pass/fail test: runs the rectangle pass and the labelling over a frame dump from the headset
   * (`--ez dump_frames true`; `poses.jsonl` + JPEGs) and prints what each frame gives, for tuning. The dump is named
   * by the environment variable AGJ_FLAT_DUMP (overlays are written to AGJ_FLAT_OUT when set); skipped without it.
   */
  @Test
  fun realDumpReport() {
    val dir = System.getenv("AGJ_FLAT_DUMP")?.let { File(it) }
    assumeTrue("AGJ_FLAT_DUMP not set", dir != null && File(dir, "poses.jsonl").isFile)
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())
    val outDir = System.getenv("AGJ_FLAT_OUT")?.let { File(it).also { d -> d.mkdirs() } }
    val p = PanelParams(chromaWeight = (System.getenv("AGJ_FLAT_CHROMA") ?: "0").toDouble())
    var t = 0L
    val lines = ArrayList<String>()
    val flat = FlatParts(AgqLog({ t }, { lines += it }), { t }).also { it.setBoards(real) }
    val needed = System.getenv("AGJ_FLAT_NEEDED") ?: "drawer_front"
    flat.setAction(0, 0, real.first { it.partId == needed }.node, needed)
    var frames = 0
    var looked = 0
    var withRects = 0
    var msSum = 0.0
    val sizes = HashMap<String, ArrayList<Pair<Double, Double>>>()
    var litFrames = 0
    val places = ArrayList<Pair<Vec3, HashMap<String, ArrayList<Int>>>>()
    for (line in File(dir, "poses.jsonl").readLines()) {
      val row = Json.parseToJsonElement(line).jsonObject
      val wc = row["T_world_cam"]
      val sy = row["surface_y"]
      if (wc == null || wc is JsonNull || sy == null || sy is JsonNull) continue
      frames++
      val img = Imgcodecs.imread(File(dir, row["file"]!!.jsonPrimitive.content).path, Imgcodecs.IMREAD_COLOR)
      if (img.empty()) continue
      val intr = Intrinsics(row["fx"]!!.jsonPrimitive.double, row["fy"]!!.jsonPrimitive.double, row["cx"]!!.jsonPrimitive.double, row["cy"]!!.jsonPrimitive.double, img.cols(), img.rows())
      val wt = PanelFrames.tableAtHeight(sy.jsonPrimitive.double.toFloat())
      val tc = PanelMath.mul(PanelMath.invertRigid(PanelMath.toMatrix(wt)), (wc as JsonArray).flatMap { r -> r.jsonArray.map { it.jsonPrimitive.double } }.toDoubleArray())
      val r = PanelPose.findRects(img, intr, tc, p)
      t = (row["t"]!!.jsonPrimitive.double * 1000).toLong()
      flat.update(r, wt, null, null, t)
      if (r.reason == PanelMath.REASON_OK) {
        looked++
        msSum += r.ms
      }
      if (r.rects.isNotEmpty()) withRects++
      val kinds = FlatMatch.kinds(real)
      val lit = flat.lit(t)
      if (lit != null) litFrames++
      val desc = r.rects.joinToString("; ") {
        val kind = FlatMatch.classify(it.lengthM, it.widthM, kinds)?.name ?: "?"
        sizes.getOrPut(kind) { ArrayList() } += it.lengthM to it.widthM
        // the same place in WORLD (8 cm) should always be the same kind of board
        val c = wt.transform(Vec3(it.x.toFloat(), it.y.toFloat(), 0f))
        val place = places.firstOrNull { pl -> (pl.first - c).length() < 0.08f } ?: (c to HashMap<String, ArrayList<Int>>()).also { pl -> places += pl }
        place.second.getOrPut(kind) { ArrayList() } += row["frame"]!!.jsonPrimitive.content.toInt()
        "%s %.0f x %.0f rect %.2f contrast %.0f".format(kind, it.lengthM * 1000, it.widthM * 1000, it.rectness, it.contrast)
      }
      println("flat real %s: %s, %d candidates, %.0f ms | %s | lit %s".format(row["file"]!!.jsonPrimitive.content, r.reason, r.candidates, r.ms, desc,
          lit?.let { "%s %.0f x %.0f".format(it.partId, it.lengthM * 1000, it.widthM * 1000) } ?: "-"))
      if (outDir != null && r.rects.isNotEmpty()) {
        for (f in r.rects) {
          val kind = FlatMatch.classify(f.lengthM, f.widthM, kinds)?.name
          val col = if (kind == null) Scalar(0.0, 0.0, 255.0) else Scalar(255.0, 160.0, 0.0)
          for (i in 0 until 4) {
            val j = (i + 1) % 4
            Imgproc.line(img, Point(f.cornersPx[2 * i], f.cornersPx[2 * i + 1]), Point(f.cornersPx[2 * j], f.cornersPx[2 * j + 1]), col, 3)
          }
          Imgproc.putText(img, "%s %.0fx%.0f".format(kind?.take(12) ?: "?", f.lengthM * 1000, f.widthM * 1000), Point(f.cornersPx[0], f.cornersPx[1] - 6), Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, col, 2)
        }
        val small = Mat()
        Imgproc.resize(img, small, Size(640.0, 480.0))
        Imgcodecs.imwrite(File(outDir, row["file"]!!.jsonPrimitive.content).path, small)
        small.release()
      }
      img.release()
    }
    println("flat real summary: %d frames with pose and surface, %d looked down at it, %d with rectangles, %d with the needed part lit, %.0f ms per pass".format(frames, looked, withRects, litFrames, if (looked > 0) msSum / looked else 0.0))
    for ((at, kindsSeen) in places) println("flat real place (%.2f, %.2f): %s%s".format(at.x, at.z,
        kindsSeen.map { (kind, fs) -> "%s x%d (frames %d..%d)".format(kind, fs.size, fs.min(), fs.max()) }, if (kindsSeen.size > 1) "  <-- more than one kind here" else ""))
    for ((kind, v) in sizes) {
      val ls = v.map { it.first * 1000 }.sorted()
      val ws = v.map { it.second * 1000 }.sorted()
      println("flat real %s: %d sightings, length p10 %.0f p50 %.0f p90 %.0f, width p10 %.0f p50 %.0f p90 %.0f".format(kind, v.size, ls[ls.size / 10], ls[ls.size / 2], ls[ls.size * 9 / 10], ws[ws.size / 10], ws[ws.size / 2], ws[ws.size * 9 / 10]))
    }
  }
}
