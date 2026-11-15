package com.agj.android.vision

import com.agj.android.core.AgqLog
import com.agj.android.core.Pose3
import com.agj.android.core.Vec3
import com.agj.android.guide.Assembly
import com.agj.android.guide.Glb
import com.agj.android.guide.Guide
import com.agj.android.guide.Manifest
import com.agj.android.world.Board
import com.agj.android.world.FlatMatch
import com.agj.android.world.FlatParts
import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs

/**
 * BOARD-CUE ([BoardCue]): templates from meshes, every hypothesis on synthetic top-down pictures, the three-in-a-row
 * rule, left against right through [FlatParts]. Pure JVM; [realReport] replays a headset recording when asked to.
 */
class BoardCueTest {
  private fun res(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())

  // ---- two mirror-image sides like the real drawer's: 450 × 110 × 16 mm, thickness along X, width along Y, length along Z ----
  // inner face: a groove 8–12 mm from the lower edge (y = 0); outer face: a rail standing 8 mm high at 44–62 mm; two
  // through holes 9.25 mm from the back end (z = −450) at 28 and 92.75 mm; two cam locks (hardware) 34.25 mm from the front end.

  private val cell = 0.00125

  /** [sign] −1 = the left side (outer face towards −X), +1 = the right one (its mirror image). */
  private fun side(sign: Int): Pair<FloatArray, IntArray> {
    val pos = ArrayList<Float>()
    val idx = ArrayList<Int>()
    fun quad(x: Double, y0: Double, y1: Double, z0: Double, z1: Double) {
      val b = pos.size / 3
      for ((y, z) in listOf(y0 to z0, y1 to z0, y1 to z1, y0 to z1)) {
        pos += (sign * x).toFloat()
        pos += y.toFloat()
        pos += z.toFloat()
      }
      idx += listOf(b, b + 1, b + 2, b, b + 2, b + 3)
    }
    fun hole(y: Double, z: Double) = (kotlin.math.abs(y - 0.028) < 0.004 || kotlin.math.abs(y - 0.09275) < 0.004) && kotlin.math.abs(z + 0.44075) < 0.004
    var j = 0
    while (j * cell < 0.110 - 1e-9) {
      val y0 = j * cell
      val yc = y0 + cell / 2
      var i = 0
      while (i * cell < 0.450 - 1e-9) {
        val z0 = -0.450 + i * cell
        val zc = z0 + cell / 2
        if (!hole(yc, zc)) {
          quad(if (yc > 0.008 && yc < 0.012) 0.006 else 0.0, y0, y0 + cell, z0, z0 + cell) // inner face (x = 0), the groove 6 mm deep
          quad(if (yc > 0.044 && yc < 0.062) 0.024 else 0.016, y0, y0 + cell, z0, z0 + cell) // outer face, the rail 8 mm high
        }
        i++
      }
      j++
    }
    return pos.toFloatArray() to idx.toIntArray()
  }

  /** The two cam locks of a side: 12 mm squares lying in its outer face. */
  private fun locks(sign: Int): List<Pair<FloatArray, IntArray>> =
      listOf(0.028, 0.09275).map { y ->
        val p = ArrayList<Float>()
        for ((dy, dz) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
          p += (sign * 0.016).toFloat()
          p += (y + dy * 0.006).toFloat()
          p += (-0.03425 + dz * 0.006).toFloat()
        }
        p.toFloatArray() to intArrayOf(0, 1, 2, 0, 2, 3)
      }

  private val sides: KindCue by lazy {
    val l = side(-1)
    val r = side(1)
    BoardCue.kind("left+right", listOf(BoardCue.BoardMesh("left", l.first, l.second, Pose3.IDENTITY), BoardCue.BoardMesh("right", r.first, r.second, Pose3.IDENTITY)), locks(-1) + locks(1))
  }

  // ---- a synthetic top-down picture of one hypothesis ----

  private val n = 400
  private val s = 0.0025
  private val x0 = -0.5
  private val y0 = -0.5

  /** The floor at 40, the board at 170, what the hypothesis [lie] shows at 100; the board's centre at ([x], [y]), its rectangle's yaw [yaw]. */
  private fun picture(lie: BoardLie, x: Double, y: Double, yaw: Double): ByteArray {
    val t = lie.template
    val a = if (lie.turned) yaw + Math.PI else yaw
    val out = ByteArray(n * n)
    for (j in 0 until n) for (i in 0 until n) {
      val dx = x0 + i * s - x
      val dy = y0 + j * s - y
      val fx = cos(a) * dx + sin(a) * dy
      val fy = -sin(a) * dx + cos(a) * dy
      val ti = floor(fx / s + t.w / 2.0).toInt()
      val tj = floor(fy / s + t.h / 2.0).toInt()
      out[j * n + i] = (if (ti !in 0 until t.w || tj !in 0 until t.h) 40 else if (t.group[tj * t.w + ti] >= 0) 100 else 170).toByte()
    }
    return out
  }

  private fun score(kind: KindCue, lie: BoardLie, x: Double = 0.0113, y: Double = -0.0207, yawDeg: Double = 25.0): BoardCueResult =
      BoardCue.score(kind, picture(lie, x, y, Math.toRadians(yawDeg)), 1, BooleanArray(n * n) { true }, n, x0, y0, s, x, y, Math.toRadians(yawDeg))

  @Test
  fun aSidesTemplatesAreItsGrooveFaceAndItsRailFaceWithTheHolesAndTheLocks() {
    assertEquals("two boards × two faces × two ways round", 8, sides.lies.size)
    for (board in 0..1) {
      val looks = sides.lies.filter { it.board == board }.map { it.look }.toSet()
      assertEquals(setOf(FaceLook.GROOVE, FaceLook.RAIL), looks)
    }
    // mirror images: the left side's groove face is the face its mirror image shows the rail on
    val leftGroove = sides.lies.first { it.board == 0 && it.look == FaceLook.GROOVE }.face
    val rightGroove = sides.lies.first { it.board == 1 && it.look == FaceLook.GROOVE }.face
    assertTrue("left groove face $leftGroove, right $rightGroove", leftGroove != rightGroove)
    val groove = sides.lies.first { it.look == FaceLook.GROOVE }.template
    assertEquals("the groove", 1, groove.long.count { it })
    assertEquals("the two holes", 2, groove.smallGroups)
    val rail = sides.lies.first { it.look == FaceLook.RAIL }.template
    assertEquals("the rail", 1, rail.long.count { it })
    assertEquals("two holes and two locks", 4, rail.smallGroups)
  }

  /** A groove at the edge proves the face (the rail is not there) and nothing else: see [BoardCue.decide]. */
  @Test
  fun grooveUpTellsTheFaceAndLeavesTheBoardAndTheWayRoundOpen() {
    for (lie in sides.lies.filter { it.look == FaceLook.GROOVE }) {
      for (yaw in listOf(3.0, 25.0, 88.0, 155.0)) {
        val r = score(sides, lie, yawDeg = yaw)
        val what = "${sides.boards[lie.board]} turned ${lie.turned} at $yaw°: long ${r.long.toList()} edge ${r.edge.toList()} small ${r.small.toList()}"
        assertEquals(what, FaceLook.GROOVE, r.look)
        assertNull(what, r.board)
        assertNull(what, r.turned)
        // the scores themselves do tell the hypotheses apart on a clean picture (they are logged): the true one is the darkest
        val i = sides.lies.indexOf(lie)
        assertTrue(what, r.edge[i] >= 30 && r.small[i] >= 30)
      }
    }
  }

  @Test
  fun anEdgeLineAloneDecidesNothing() {
    // a board whose only feature is a groove at its edge (the drawer's back): never decided, however dark the line
    val feat = BooleanArray(140 * 44) { (it / 140) in 3..4 && (it % 140) in 2..137 }
    val t = BoardCue.of(140, 44, 2.5, feat)!!
    assertTrue(t.toString(), t.edgePx > 0 && t.interiorPx == 0)
    val back = KindCue("back", listOf("back"), listOf(BoardLie(0, 0, false, t), BoardLie(0, 0, true, t)))
    val r = BoardCue.decide(back, doubleArrayOf(Double.NaN, Double.NaN), doubleArrayOf(22.0, 0.0), doubleArrayOf(Double.NaN, Double.NaN))
    assertNull(r.look)
    assertNull(r.turned)
  }

  @Test
  fun railUpTellsTheFaceAndTheWayRoundAndNeverTheWrongBoard() {
    for (lie in sides.lies.filter { it.look == FaceLook.RAIL }) {
      val r = score(sides, lie)
      val what = "${sides.boards[lie.board]} turned ${lie.turned}: long ${r.long.toList()} edge ${r.edge.toList()} small ${r.small.toList()}"
      assertEquals(what, FaceLook.RAIL, r.look)
      assertEquals(what, lie.turned, r.turned)
      assertTrue(what, r.board == null || r.board == lie.board)
    }
  }

  @Test
  fun aPlainPictureDecidesNothing() {
    val light = ByteArray(n * n) { 170.toByte() }
    val r = BoardCue.score(sides, light, 1, BooleanArray(n * n) { true }, n, x0, y0, s, 0.0, 0.0, 0.3)
    assertNull(r.look)
    assertNull(r.board)
    assertNull(r.turned)
  }

  @Test
  fun aBoardOutOfTheCameraImageIsNotScored() {
    val lie = sides.lies.first { it.look == FaceLook.GROOVE }
    val r = BoardCue.score(sides, picture(lie, 0.0, 0.0, 0.0), 1, BooleanArray(n * n), n, x0, y0, s, 0.0, 0.0, 0.0)
    assertNull(r.look)
    assertTrue(r.long.all { it.isNaN() })
  }

  @Test
  fun aValueNeedsThreeAgreeingResultsInARow() {
    val v = CueVote<String>()
    assertNull(v.add("left"))
    assertNull(v.add("left"))
    assertNull("an undecided result breaks the row", v.add(null))
    assertNull(v.add("left"))
    assertNull(v.add("right"))
    assertNull(v.add("left"))
    assertNull(v.add("left"))
    assertEquals("left", v.add("left"))
    // it changes only after three in a row that say otherwise; undecided results change nothing
    assertEquals("left", v.add("right"))
    assertEquals("left", v.add(null))
    assertEquals("left", v.add("right"))
    assertEquals("left", v.add("right"))
    assertEquals("right", v.add("right"))
  }

  // ---- through the tracker: the left side is lit, not the nearest one ----

  private val boards =
      listOf(
          Board("drawer_side_left", "part_drawer_side_left_1", 0.450, 0.110, addedAt = 1001, names = mapOf("en" to "left side panel", "el" to "αριστερό πλαϊνό")),
          Board("drawer_side_right", "part_drawer_side_right_1", 0.450, 0.110, addedAt = 1002, names = mapOf("en" to "right side panel", "el" to "δεξί πλαϊνό")),
      )

  /**
   * A sighting whose cue says [board] (0 = left, 1 = right; null = groove up, the board not told). The result is made
   * by hand: on the real drawer [BoardCue.decide] never tells the two sides apart; the tracker's part is tested here.
   */
  private fun sighting(x: Double, board: Int?): FoundRect {
    val k = KindCue("drawer_side_left+drawer_side_right", listOf("drawer_side_left", "drawer_side_right"), sides.lies)
    val long = DoubleArray(8) // the rail is not there
    val edge = DoubleArray(8)
    val small = DoubleArray(8)
    val grooves = k.lies.indices.filter { k.lies[it].look == FaceLook.GROOVE }
    // two hypotheses show the groove at the same long edge: one per board
    val a = grooves.first { k.lies[it].board == 0 && !k.lies[it].turned }
    val b = grooves.first { k.lies[it].board == 1 && k.lies[it].turned }
    edge[a] = 25.0
    edge[b] = 25.0
    if (board != null) small[if (board == 0) a else b] = 20.0
    return FoundRect(x, 0.0, 0.0, 0.450, 0.110, 0.95, 60.0, DoubleArray(8), PanelMath.rectCorners(x, 0.0, 0.0, 0.450, 0.110), 0.016).also { it.cue = BoardCueResult(k, long, edge, small, if (board == 1) b else a, FaceLook.GROOVE, board, board?.let { it == 1 }, 0.1) }
  }

  @Test
  fun theLeftSideIsLitWhenTheTwoAreToldApartAndTheOtherOneGetsTheHint() {
    var t = 0L
    val flat = FlatParts(AgqLog({ t }, { }), { t }).also { it.setBoards(boards) }
    flat.setAction(1, 1, "part_drawer_side_left_1", "drawer_side_left")
    val table = PanelFrames.tableAtHeight(0f)
    val assemblyAt = Vec3(0f, 0f, 0f)
    // the right side lies nearer the assembly (x = 0.3) than the left one (x = 0.9)
    fun pass(left: Int?, right: Int?, both: Boolean = true) {
      t += 330
      flat.update(RectsResult(listOfNotNull(sighting(0.3, right), if (both) sighting(0.9, left) else null), "ok", 1.0), table, null, assemblyAt, t)
    }
    repeat(3) { pass(null, null) }
    assertEquals("nothing told apart: the nearest, as before", 0.3f, flat.lit(t)!!.center.x, 0.01f)
    assertNull(flat.describe("el", t)?.takeIf { it.contains("πλαϊνό") })
    assertEquals("the face is known, the board is not", "Με το αυλάκι προς τα πάνω", flat.describe("el", t))
    assertNull(flat.hint("el", t))
    pass(0, 1)
    pass(0, 1)
    assertEquals("two results are not enough", 0.3f, flat.lit(t)!!.center.x, 0.01f)
    pass(0, 1)
    val lit = flat.lit(t)!!
    assertEquals("the left one, though it is farther", 0.9f, lit.center.x, 0.01f)
    assertEquals("drawer_side_left", lit.which)
    assertEquals(FaceLook.GROOVE, lit.look)
    assertEquals("Αριστερό πλαϊνό · με το αυλάκι προς τα πάνω", flat.describe("el", t))
    assertNull("the needed board is in view: no hint", flat.hint("el", t))
    // only the right one in view: it is not lit, and the coach has a line
    t += 3000
    repeat(3) { pass(null, 1, both = false) }
    assertNull(flat.lit(t))
    assertEquals("Αυτό είναι το δεξί πλαϊνό, πάρε το άλλο.", flat.hint("el", t))
    assertEquals("This is the right side panel. Take the other one.", flat.hint("en", t))
  }

  // ---- the test job's meshes ----

  @Test
  fun theTestJobsBoardsGetTheirTemplatesFromTheirMeshes() {
    val manifest = Manifest.parse(res("malm-drawer/manifest.json").readText())
    val guide = Guide.build(manifest, Glb.read(res("malm-drawer/walkthrough.glb")))
    val lines = ArrayList<String>()
    val flat = FlatParts(AgqLog({ 0L }, { lines += it }))
    flat.setAssembly(Assembly("malm-drawer", manifest, null, guide.finalPoses, guide))
    try {
      assertNotNull("the detector's pass has a scorer", BoardCueHub.scorer)
      val event = lines.first { it.contains("board_cue") }
      // the front's inner face has a groove (RecessCueTest pins where): a hypothesis with a long feature exists for it
      assertTrue(event, event.contains("groove"))
    } finally {
      flat.setAssembly(null)
    }
    assertNull("no guide, no scorer", BoardCueHub.scorer)
  }

  // ---- a headset recording replayed (one-off evidence; the frames are private and not in the repository) ----

  private fun loadOpenCv(): Boolean =
      try {
        nu.pattern.OpenCV.loadLocally()
        true
      } catch (t: Throwable) {
        false
      }

  /**
   * `AGJ_BOARD_JOB=<job folder> AGJ_FLAT_DUMP=<dump folder> ./gradlew --offline testDebugUnitTest --tests '*BoardCueTest.realReport' -i`:
   * every frame through [PanelPose.findRects] with the job's own templates; per place on the floor, what each
   * sighting's cue said and what the track ended with.
   */
  @Test
  fun realReport() {
    val job = System.getenv("AGJ_BOARD_JOB")?.let { File(it) }
    val dir = System.getenv("AGJ_FLAT_DUMP")?.let { File(it) }
    assumeTrue("AGJ_BOARD_JOB / AGJ_FLAT_DUMP not set", job != null && dir != null && File(dir, "poses.jsonl").isFile && File(job, "manifest.json").isFile)
    assumeTrue("desktop OpenCV not loadable", loadOpenCv())
    val manifest = Manifest.parse(File(job, "manifest.json").readText())
    val guide = Guide.build(manifest, Glb.read(File(job, "walkthrough.glb")))
    var t = 0L
    val lines = ArrayList<String>()
    val flat = FlatParts(AgqLog({ t }, { lines += it }), { t })
    flat.setAssembly(Assembly("real", manifest, null, guide.finalPoses, guide))
    println("board real templates: " + lines.first { it.contains("board_cue") })
    val scorer = flat.scorer()
    val p = PanelParams(chromaWeight = (System.getenv("AGJ_FLAT_CHROMA") ?: "0").toDouble())
    class Place(val at: Vec3, val kind: String) {
      val said = HashMap<String, Int>()
      val rows = ArrayList<String>()
      var n = 0
    }
    val places = ArrayList<Place>()
    var passes = 0
    var cueMs = 0.0
    var passMs = 0.0
    for (line in File(dir, "poses.jsonl").readLines()) {
      val row = Json.parseToJsonElement(line).jsonObject
      val wc = row["T_world_cam"]
      val sy = row["surface_y"]
      if (wc == null || wc is JsonNull || sy == null || sy is JsonNull) continue
      val img = Imgcodecs.imread(File(dir, row["file"]!!.jsonPrimitive.content).path, Imgcodecs.IMREAD_COLOR)
      if (img.empty()) continue
      val intr = Intrinsics(row["fx"]!!.jsonPrimitive.double, row["fy"]!!.jsonPrimitive.double, row["cx"]!!.jsonPrimitive.double, row["cy"]!!.jsonPrimitive.double, img.cols(), img.rows())
      val wt = PanelFrames.tableAtHeight(sy.jsonPrimitive.double.toFloat())
      val tc = PanelMath.mul(PanelMath.invertRigid(PanelMath.toMatrix(wt)), (wc as JsonArray).flatMap { r -> r.jsonArray.map { it.jsonPrimitive.double } }.toDoubleArray())
      val r = PanelPose.findRects(img, intr, tc, p, cue = scorer)
      // AGJ_BOARD_OVERLAY=<file name> AGJ_BOARD_OUT=<dir>: the winning hypothesis's features drawn into that frame
      // (long ones red, small ones blue; a green line from the centre along the board's own +length axis)
      if (System.getenv("AGJ_BOARD_OVERLAY") == row["file"]!!.jsonPrimitive.content && System.getenv("AGJ_BOARD_OUT") != null) {
        val ct = PanelMath.invertRigid(tc)
        fun px(tx: Double, ty: Double): org.opencv.core.Point {
          val xc = ct[0] * tx + ct[1] * ty + ct[2] * p.thickM + ct[3]
          val yc = ct[4] * tx + ct[5] * ty + ct[6] * p.thickM + ct[7]
          val zc = ct[8] * tx + ct[9] * ty + ct[10] * p.thickM + ct[11]
          return org.opencv.core.Point(xc / zc * intr.fx + intr.cx, yc / zc * intr.fy + intr.cy)
        }
        for (f in r.rects) {
          val c = f.cue ?: continue
          if (c.look == null || c.best < 0) continue
          val lie = c.kind.lies[c.best]
          val tpl = lie.template
          val a = if (lie.turned) f.yaw + Math.PI else f.yaw
          for (j in 0 until tpl.h) for (i in 0 until tpl.w) {
            val g = tpl.group[j * tpl.w + i]
            if (g < 0) continue
            val fx = (i + 0.5) * s - tpl.w * s / 2
            val fy = (j + 0.5) * s - tpl.h * s / 2
            org.opencv.imgproc.Imgproc.circle(img, px(f.x + cos(a) * fx - sin(a) * fy, f.y + sin(a) * fx + cos(a) * fy), 1,
                if (tpl.long[g]) org.opencv.core.Scalar(0.0, 0.0, 255.0) else org.opencv.core.Scalar(255.0, 0.0, 0.0), -1)
          }
          org.opencv.imgproc.Imgproc.line(img, px(f.x, f.y), px(f.x + cos(a) * 0.15, f.y + sin(a) * 0.15), org.opencv.core.Scalar(0.0, 255.0, 0.0), 2)
          org.opencv.imgproc.Imgproc.putText(img, "%s %s".format(c.board?.let { c.kind.boards[it] } ?: "?", c.look), px(f.x, f.y), org.opencv.imgproc.Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, org.opencv.core.Scalar(0.0, 255.0, 255.0), 2)
        }
        Imgcodecs.imwrite(File(System.getenv("AGJ_BOARD_OUT"), "overlay_" + row["file"]!!.jsonPrimitive.content).path, img)
      }
      img.release()
      t = (row["t"]!!.jsonPrimitive.double * 1000).toLong()
      flat.update(r, wt, null, null, t)
      if (r.reason != PanelMath.REASON_OK) continue
      passes++
      passMs += r.ms
      cueMs += r.rects.sumOf { it.cue?.ms ?: 0.0 }
      for (f in r.rects) {
        val c = f.cue ?: continue
        val at = wt.transform(Vec3(f.x.toFloat(), f.y.toFloat(), 0f))
        val place = places.firstOrNull { (it.at - at).length() < 0.08f && it.kind == c.kind.name } ?: Place(at, c.kind.name).also { places += it }
        place.n++
        // the WORLD heading of the board's own +length axis, to the nearest 90°, so that sightings can be compared
        val d = wt.q.rotate(Vec3(cos(f.yaw).toFloat(), sin(f.yaw).toFloat(), 0f))
        val head = c.turned?.let { tr -> Math.round(Math.toDegrees(kotlin.math.atan2(d.z.toDouble(), d.x.toDouble()) + if (tr) Math.PI else 0.0) / 90.0).toInt().mod(4) * 90 }
        val key = "face=${c.look?.name?.lowercase() ?: "-"} which=${c.board?.let { c.kind.boards[it] } ?: "-"} heading=${head ?: "-"}"
        place.said[key] = (place.said[key] ?: 0) + 1
        place.rows += "%s %s | %s".format(row["file"]!!.jsonPrimitive.content, key,
            c.kind.lies.indices.joinToString(" ") { i -> "%s/%s/%s:%.0f,%.0f,%.0f".format(c.kind.boards[c.kind.lies[i].board].takeLast(5), c.kind.lies[i].look.name.take(1), if (c.kind.lies[i].turned) "t" else "-", c.long[i], c.edge[i], c.small[i]) })
      }
    }
    println("board real: %d passes, %.1f ms per pass in all, %.2f ms of it the cue".format(passes, if (passes > 0) passMs / passes else 0.0, if (passes > 0) cueMs / passes else 0.0))
    for (pl in places.filter { it.n >= 3 }) {
      println("board real place (%.2f, %.2f) %s: %d sightings: %s".format(pl.at.x, pl.at.z, pl.kind, pl.n, pl.said.entries.sortedByDescending { it.value }.joinToString("; ") { "${it.key} x${it.value}" }))
      if (System.getenv("AGJ_BOARD_ROWS") != null) pl.rows.forEach { println("board real row   $it") }
    }
    println("board real last log: " + lines.lastOrNull { it.contains("\"flat_parts\"") || it.contains("flat_parts") }?.take(3000))
    flat.setAssembly(null)
  }
}
